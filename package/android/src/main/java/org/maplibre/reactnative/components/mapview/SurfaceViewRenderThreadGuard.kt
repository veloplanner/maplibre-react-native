package org.maplibre.reactnative.components.mapview

import android.view.View
import android.view.ViewGroup
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.renderer.surfaceview.MapLibreSurfaceView
import java.lang.reflect.Field

// Fork-only workaround for two MapLibre Native <= 13.2.0 render-thread defects,
// kept out of MLRNMapView to minimize the diff against upstream:
//
// 1. Detach reset deadlock: MapLibreSurfaceView.onDetachedFromWindow() first
//    notifies its detachedListener (-> MapRenderer.nativeReset()), whose native
//    side blocks the main thread on ask(&resetRenderer).wait() with no timeout.
//    The reset runnable is queued to renderThread without a liveness check, so
//    when the thread has already exited — a second detach without re-attach in
//    between (e.g. rnscreens' ScreenStack.endViewTransition re-dispatching
//    detach after the exit animation) or an EGL-init crash — the task is
//    silently swallowed and the main thread hangs forever (ANR). Upstream
//    guards its own requestExitAndWait() with isAlive() but not the listener
//    call; the wrapped listener mirrors that guard.
//
// 2. Wedged renderer mailbox: the renderer actor's Mailbox schedules its
//    receive runnable only on the empty -> non-empty transition (mbgl
//    Mailbox::push), and MapLibreSurfaceView.queueEvent posts into the current
//    renderThread's private ArrayList. A receive runnable stranded on an
//    exited thread — left undrained at thread exit, or posted during the
//    detached window while the renderThread field still referenced the dead
//    thread — wedges the mailbox permanently: its queue stays non-empty, no
//    later push schedules again, and every synchronous renderer call blocks
//    its caller forever even though the replacement render thread is alive and
//    idle (the map keeps drawing — the render loop doesn't go through the
//    mailbox). Moving the stranded runnables onto the live thread lets the
//    mailbox drain and heal.
//
// The guard also exposes render-thread liveness (isRenderThreadAlive) so
// callers can refuse synchronous renderer round-trips that would park on a
// dead thread's queue — the thread can die with the view still attached and
// unpaused, because MapLibreGLSurfaceView.guardedRun just returns when
// eglHelper.start() throws.
//
// Everything is reflection-based and fail-open: if the SDK layout changes (or
// R8 strips the fields), install() returns null and stock behavior is kept.
internal class SurfaceViewRenderThreadGuard private constructor(
    private val surfaceView: MapLibreSurfaceView,
    private val renderThreadField: Field,
) {
    // The render thread that exited on the last detach; its private eventQueue
    // may still hold scheduled mailbox receives.
    private var staleRenderThread: Thread? = null

    fun isRenderThreadAlive(): Boolean =
        try {
            val renderThread = renderThreadField.get(surfaceView) as? Thread
            renderThread == null || renderThread.isAlive
        } catch (e: Exception) {
            true // fail open: keep stock behavior
        }

    private fun onSurfaceViewDetached(original: MapLibreSurfaceView.OnSurfaceViewDetachedListener) {
        // Read renderThread at callback time — it is replaced on re-attach.
        val renderThread = renderThreadField.get(surfaceView) as? Thread
        // requestExitAndWait() runs right after this callback, possibly leaving
        // scheduled mailbox receives stranded in the thread's eventQueue;
        // remember the thread so they can be recovered on re-attach.
        staleRenderThread = renderThread
        if (renderThread != null && renderThread.isAlive) {
            original.onSurfaceViewDetached()
        }
    }

    private fun onSurfaceViewAttached() {
        // MapLibreSurfaceView.onAttachedToWindow has already started the
        // replacement render thread (attach listeners run after it), so
        // stranded events can be moved onto the live thread now. Sweep once
        // more a frame later for a push that raced the thread swap.
        migrateStaleRenderThreadEvents()
        surfaceView.post { migrateStaleRenderThreadEvents() }
    }

    private fun migrateStaleRenderThreadEvents() {
        val staleThread = staleRenderThread ?: return

        try {
            val currentThread = renderThreadField.get(surfaceView) as? Thread ?: return
            if (staleThread === currentThread) {
                return
            }

            val queueField = findFieldInHierarchy(staleThread.javaClass, "eventQueue") ?: return
            val managerField =
                findFieldInHierarchy(staleThread.javaClass, "renderThreadManager") ?: return
            // queueEvent synchronizes on renderThreadManager; take the same monitor.
            val monitor = managerField.get(staleThread) ?: return

            val stranded: List<Runnable>
            synchronized(monitor) {
                @Suppress("UNCHECKED_CAST")
                val queue = queueField.get(staleThread) as? ArrayList<Runnable> ?: return
                if (queue.isEmpty()) {
                    return
                }
                stranded = ArrayList(queue)
                queue.clear()
            }

            stranded.forEach(surfaceView::queueEvent)
            Logger.w(
                LOG_TAG,
                "Recovered ${stranded.size} render event(s) stranded on an exited render thread",
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to recover events from the exited render thread", e)
        }
    }

    companion object {
        private const val LOG_TAG = "SurfaceViewRenderThreadGuard"

        // Returns null in texture mode (no MapLibreSurfaceView child) or when
        // reflection fails — null means "no guard, stock behavior".
        fun install(mapView: ViewGroup): SurfaceViewRenderThreadGuard? {
            val surfaceView =
                (0 until mapView.childCount)
                    .map(mapView::getChildAt)
                    .filterIsInstance<MapLibreSurfaceView>()
                    .firstOrNull() ?: return null

            return try {
                val listenerField =
                    MapLibreSurfaceView::class.java
                        .getDeclaredField("detachedListener")
                        .apply { isAccessible = true }
                val threadField =
                    MapLibreSurfaceView::class.java
                        .getDeclaredField("renderThread")
                        .apply { isAccessible = true }
                val original =
                    listenerField.get(surfaceView) as? MapLibreSurfaceView.OnSurfaceViewDetachedListener
                        ?: return null

                val guard = SurfaceViewRenderThreadGuard(surfaceView, threadField)

                listenerField.set(
                    surfaceView,
                    MapLibreSurfaceView.OnSurfaceViewDetachedListener {
                        guard.onSurfaceViewDetached(original)
                    },
                )

                surfaceView.addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            guard.onSurfaceViewAttached()
                        }

                        override fun onViewDetachedFromWindow(view: View) = Unit
                    },
                )

                guard
            } catch (e: Exception) {
                // Reflection failed (SDK layout changed / minification): keep stock behavior.
                Logger.e(LOG_TAG, "Failed to install surface view render thread guard", e)
                null
            }
        }

        private fun findFieldInHierarchy(
            type: Class<*>,
            name: String,
        ): Field? {
            var current: Class<*>? = type
            while (current != null) {
                try {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            return null
        }
    }
}
