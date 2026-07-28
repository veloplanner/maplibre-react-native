# Android render-thread ANRs: fixes and findings

Companion to the four Android render-thread stability commits on `veloplanner` (Sentry
issues VELOPLANNER_MOBILE-FY, dist 140, and VELOPLANNER_MOBILE-GW, 2.6.1+141). Covers what
each fix does (in the order they were written),
the code review of the last one — verified against the shipped
`org.maplibre.gl:android-sdk-opengl:13.2.0` bytecode — open risks, how rnmapbox avoids this
entire bug class, and the sync→async options going forward.

**The problem in one sentence:** several map APIs (`queryRenderedFeatures` on every tap,
`querySourceFeatures`, cluster getters, snapshot) post a task to the GL render thread and
block the Android main thread until it is served — and there are multiple ways the render
thread can be gone, or its delivery queue wedged, that turn the call into a permanent
main-thread hang (ANR).

## Why this bug class exists

The mbgl C++ core is fully asynchronous — actors passing messages through mailboxes. The
synchronous Java API is manufactured by one line of JNI glue
(`android_renderer_frontend.cpp:171-188` in maplibre-native):

```cpp
// Waits for the result from the orchestration thread and returns
return mapRenderer.actor().ask(fn, box, options).get();   // no timeout
```

Delivery of those messages is fragile in three independent ways:

1. **`queueEvent` has no liveness check** (`MapLibreSurfaceView.RenderThread.queueEvent`):
   posting to a dead render thread silently appends to an `ArrayList` nobody will ever read.
2. **The queue is never drained on thread exit**: `guardedRun` checks `shouldExit` *before*
   the queue, and the EGL-init failure path just `return`s — whatever is queued is stranded.
   The thread exits on view detach (`requestExitAndWait`) and can die in place on EGL failure
   with the view still attached; the `renderThread` field is only replaced on re-attach.
3. **`Mailbox::push` schedules its receive runnable only on the empty→non-empty transition**
   (`mailbox.cpp:100-110`). One receive stranded on an exited thread wedges the mailbox
   permanently: the queue stays non-empty, so no later push ever schedules again, and every
   sync call blocks forever — while the map keeps drawing, because the render loop
   (`requestRender`) bypasses the mailbox. One mailbox is shared by renderer queries and
   `nativeReset`, so a single stranded receive wedges everything.

This surface came from Mapbox GL Native v9 (~2016). Mapbox replaced it with callback-based
APIs in the v10 rewrite (2021), after the MapLibre fork point.

## The four fixes, in order

### 1. Guard queries + lazy SymbolManager —
[3cbec712](https://github.com/veloplanner/maplibre-react-native/commit/3cbec712e8fdef53d29ba7ad89ade4c180589411) (2026-07-20)

`onSingleTapConfirmed` fires ~300 ms after a tap, so it regularly lands after a navigation
push / tab switch has detached the view (render thread exited) or backgrounding paused it.
The worst path needed no app code at all: the SymbolManager was created eagerly at style load
and its `MapClickResolver` ran a synchronous query on every tap. Fix: create the SymbolManager
lazily on first `PointAnnotation` use, and guard `onMapClick`/`onMapLongClick`/
`queryRenderedFeaturesWithPoint`/`WithRect` with `paused`/`destroyed`/`isAttachedToWindow`
(returning consumed-click / empty results).

### 2. Detach-reset guard —
[9d0eb7cb](https://github.com/veloplanner/maplibre-react-native/commit/9d0eb7cbe096aed6dd269cfdece205b91d35862d) (2026-07-21)

`MapLibreSurfaceView.onDetachedFromWindow()` notifies its `detachedListener`
(→ `MapRenderer.nativeReset()`, which blocks on `ask(&resetRenderer).wait()`) *before* its
own `isAlive()`-guarded `requestExitAndWait()`. A second detach without re-attach (rnscreens'
`ScreenStack.endViewTransition` re-dispatching detach) or an EGL-init crash hits the listener
with a dead thread → permanent hang. Fix: wrap the listener via reflection
(`setDetachedListener` throws once set) and only call through when the thread is alive.

### 3. Dead-thread liveness + mailbox recovery —
[1003899a](https://github.com/veloplanner/maplibre-react-native/commit/1003899aa68c17e4778e939562d211b8b46daec7) (2026-07-24)

The remaining Sentry ANRs happened with the view *attached and unpaused*: (a) the render
thread died in place (EGL failure), or (b) the mailbox got wedged by a receive stranded on an
exited thread (mechanism 2+3 above) — the map keeps drawing while every sync call hangs.
Fix: `isRendererAvailable()` adds a render-thread liveness check to all query guards;
`migrateStaleRenderThreadEvents()` re-posts stranded runnables onto the replacement thread on
re-attach so the mailbox drains and heals; `takeSnap` now rejects its promise;
`querySourceFeatures` and the GeoJSON cluster getters return empty results.

### 4. Annotation hit-test gate — (2026-07-28)

The mailbox heal (fix 3) turned one residual failure into a SIGSEGV (Sentry
VELOPLANNER_MOBILE-GW, first seen on 2.6.1+141 / `-veloplanner.2`): `AnnotationManager.onTap`
runs two synchronous renderer asks on every tap (`queryPointAnnotations` +
`queryShapeAnnotations`) *before* the `OnMapClickListener` chain, so no MLRN-side guard can
reach them — and uniquely among sync asks they carry a 200 ms timeout
(`NativeMapView.annotationRequestTimeout`), so on timeout the caller survives while the ask
message stays queued in the mailbox. A tap landing after detach (the 300 ms
`onSingleTapConfirmed` window) strands the message on the dead thread; re-attach recreates
the Renderer on the *same never-closed mailbox* (`MapRenderer.onSurfaceCreated` — upstream
bug: `rendererRef` is never invalidated and the mailbox never closed); the heal then replays
the stranded receive, dispatching the ask into the freed Renderer →
`RenderOrchestrator::queryShapeAnnotations` reads `*layerImpls` from recycled heap →
`vector::begin` segfault.

Fix: `MLRNAnnotationHitTestGuard` (Java shim in `org.maplibre.android.maps` — the touched
types are package-private — reflection-based, fail-open) swaps `AnnotationManager`'s
`markers`/`shapeAnnotations` containers for wrappers that short-circuit `obtainAllIn` to an
empty list when no annotations exist (always true under MLRN, which never uses the legacy
annotation API — taps stop doing renderer round-trips entirely) or when
`isRendererAvailable()` is false. Emptiness is checked first: it also closes the
unsynchronized `rendererRef` window during Renderer recreation that no liveness check can
see. This restores fix 3's replay-safety invariant: no renderer-targeting sync ask can be
stranded-and-replayed.

## Review of 1003899a (2026-07-24)

All load-bearing assumptions were verified against the shipped 13.2.0 artifact (via `javap`
on the AAR in the gradle cache — no sources jar exists — cross-checked with a maplibre-native
checkout). The commit is correct:

- `eventQueue: ArrayList<Runnable>` and `renderThreadManager` live on the
  `MapLibreSurfaceView$RenderThread` base class (`GLThread` extends it, not `Thread`
  directly), so the `findFieldInHierarchy` walk is required and finds them.
- **Monitor identity holds** — the critical assumption. `RenderThreadManager` is per-view
  (upstream made it non-static in 3c250e84e55) and passed into each thread's constructor, so
  the dead and replacement threads share the *same* monitor object; locking the stale
  thread's manager locks exactly what `queueEvent` locks.
- No drain on exit, confirmed in bytecode: the only `eventQueue` opcodes in `guardedRun` are
  `isEmpty()`/`remove(0)`; `threadExiting` just sets `exited` and notifies.
- Attach ordering, confirmed against framework source: `onAttachedToWindow()` (creates +
  starts the replacement thread) runs before `OnAttachStateChangeListener`s — so the
  migration really does run with a live target thread.
- Texture mode uses stock `android.view.TextureView` (there is no `MapLibreTextureView`), so
  the guard's `filterIsInstance<MapLibreSurfaceView>()` correctly no-ops there. (The texture
  render thread also has different internals — `lock`/`LinkedList` — so it must never be
  reflected with these field names.)
- Concurrency: all guard state is touched on the main thread only; the migration copies the
  queue under the monitor and posts outside it; reflection failure degrades to stock behavior.

## Open risks and gaps

### R8/minification silently disables all four fixes (highest)

All four fixes reflect on SDK fields **by string name** (`"renderThread"`,
`"detachedListener"`, `"eventQueue"`, `"renderThreadManager"`, and — fix 4 —
`"annotationManager"`, `"annotationsArray"`, `"markers"`, `"shapeAnnotations"`). R8 renames fields based on
static analysis of compiled references; a string passed to `getDeclaredField` is invisible to
it. In a minified app build the lookup throws `NoSuchFieldException`, the deliberate
`catch` falls back to stock SDK behavior, and every ANR fix becomes a no-op — no crash, no
Sentry event, just one logcat line (`Failed to install surface view detach guard`, the
detection signal). Neither the MapLibre AAR's consumer rules (which keep gson/models/enums
but nothing under `org.maplibre.android.maps.renderer.**`) nor this library
(no `consumerProguardFiles` in `package/android`) keep those names.

Mitigations: the RN template defaults `enableProguardInReleaseBuilds = false`, and the
dist-140 Sentry data shows the earlier guards working, so the VeloPlanner app is almost
certainly unminified today. Recommended anyway (library-grade insurance): ship a consumer
rule from `package/android`:

```
-keepclassmembers class org.maplibre.android.maps.renderer.surfaceview.** { <fields>; }
```

**Fixed (2026-07-25):** `package/android/consumer-rules.pro` ships exactly this rule, wired via
`consumerProguardFiles` — AGP merges consumer rules into the app's R8 configuration, so minified
app builds keep the four field names. Classes and methods stay optimizable. (2026-07-28: fix 4's
`MapLibreMap`/`AnnotationManager` field names added to the same file.)

### PointAnnotation apps bypass the guards

Once `createSymbolManager` runs, its `MapClickResolver` is deliberately re-prioritized ahead
of the guarded `onMapClick` (`MLRNMapView.kt`, `createSymbolManager`) and runs an unguarded
synchronous `queryRenderedFeatures` on every tap/long-press — all the ANR scenarios above
still apply on that path. Accepted for now because VeloPlanner doesn't use `PointAnnotation`;
must be revisited before upstreaming or if annotations are ever adopted.

### `waitForEmpty()` is another unguarded hang path

`MapRenderer::waitForEmpty` → `RenderThread.waitForEmpty` waits on the shared monitor until
`eventQueue.isEmpty()` with no exited/liveness check — it blocks its caller forever on a dead
thread with a stranded event, and `isRendererAvailable()` doesn't cover its callers. Not seen
in Sentry yet; check who calls it (likely the destroy path) before deciding whether to guard.

**Resolved — unreachable in 13.2.0 (2026-07-25), no guard needed.** Verified in the shipped
bytecode (javap over every class in the AAR) and the `android-v13.2.0` source tag: the only
Java references are the delegation chain itself (`MapRendererScheduler` → renderers → render
threads), and the JNI bridge `mbgl::android::MapRenderer::waitForEmpty` — compiled in as a
mandatory `Scheduler` override — has zero callers. Core's two `waitForEmpty` call sites go
elsewhere: `render_orchestrator.cpp` waits on the worker `ThreadedScheduler` pool, and
`ActionJournal::flush` on its own `Scheduler::GetSequenced()`. Dead code today; re-check on
every MapLibre upgrade alongside the reflection guard.

### Minor

- `getClusterExpansionZoom` returns `0` when the renderer is unavailable; JS often feeds that
  straight into a camera zoom. Rejecting (as `takeSnap` now does) would be less surprising
  than fake data. Empty query results are the established convention from 3cbec712, so this
  is a consistency tradeoff, not a bug. **Fixed (2026-07-25):** now rejects with the
  `takeSnap`-style message; the source method returns `null` and the module rejects.
- `staleRenderThread` is never cleared after a successful migration — keeps a dead `Thread`
  (and its `eglHelper`) referenced until the next detach. Idempotent and bounded; cosmetic.
  **Fixed (2026-07-25):** released after the deferred second sweep, only if the view is still
  attached — a detach racing the posted runnable must keep the reference for the next
  re-attach's migration.
- Migration doesn't preserve ordering relative to events queued on the new thread before
  re-attach. Safe for mailbox receives (order-independent), but it is an unstated assumption.
  A second unstated assumption was *not* safe: a replayed receive dispatches its message into
  the Renderer captured at push time, and re-attach recreates the Renderer on the same
  never-closed mailbox — the annotation hit-test asks (the only sync asks with a timeout, so
  their caller survives the strand) replayed as a use-after-free. **Closed (2026-07-28)** by
  fix 4 (VELOPLANNER_MOBILE-GW).
- Comment nits in `MLRNMapView.kt`: the reflected fields are `protected`, not "private";
  `queueEvent` is declared on `MapLibreSurfaceView`, not `MapLibreGLSurfaceView`.
  **Fixed (2026-07-25)** — the stale "private" wording was in `SurfaceViewRenderThreadGuard.kt`;
  the `queueEvent` attribution was already correct.

## Why rnmapbox has none of this

rnmapbox/maps (the pre-fork ancestor of maplibre-react-native's lineage) targets Mapbox Maps
SDK 11.x, where **every renderer round-trip is callback-based**: tap handling is a recursive
async chain (`RNMBXMapView.handleTapInSources`), snapshot uses the async listener, and all
source/cluster queries take callbacks. Nothing can park the main thread on the render thread,
so the ANR class is structurally unreachable — they have zero lifecycle guards, zero
reflection, and zero ANR commits in their history. The deleted pre-v10 code path (removed
2023-09, rnmapbox commit 1449659b) — the direct ancestor of this repo's Android code — had
the same blocking `queryRenderedFeatures` loop with **no** guards. rnmapbox escaped by SDK
redesign, not by guarding; their residual failure mode is promises that never settle (silent,
no ANR). There is no prior art to borrow — these guards compensate for a design Mapbox
retired in v10.

## Sync → async: what's possible

**A fork-side wrapper is not clean.** The obvious move — run the blocking call on a
background thread and resolve the RN promise from there — is blocked by the SDK itself:
`NativeMapView.checkState()` throws `CalledFromWorkerThreadException` for any map call off
the main thread. Bypassing it means invoking the private `nativeQueryRenderedFeatures*` JNI
methods via reflection from a worker — a dependency on private native method signatures
(more fragile than two field names), unsupported, and every wedged call would permanently
park a worker thread. A timeout wrapper is impossible from Java: the block happens inside
the JNI call, so there is no future on the Java side to time-limit.

In that light, the current guards *are* the sync→async adaptation at the only layer the fork
can safely reach: they detect "this `.get()` would never return" and short-circuit to an
empty/rejected result instead of calling it.

**The durable fixes belong in maplibre-native:**

1. Root-cause hardening (makes the existing sync API safe for everyone): a liveness check in
   `queueEvent`, draining or migrating `eventQueue` on thread exit, and rescheduling the
   mailbox receive on the replacement thread. The `detachedListener` call in
   `onDetachedFromWindow` also needs the same `isAlive()` guard upstream already applies to
   `requestExitAndWait()`.
2. Async Java API variants: the core is one `.get()` away — the change is delivering the
   future's result through a JNI callback instead of blocking (the snapshot path already
   works this way). That is the shape Mapbox v10 has, and it deletes this bug class outright.
