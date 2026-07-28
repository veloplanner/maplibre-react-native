package org.maplibre.android.maps;

import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.LongSparseArray;

import org.maplibre.android.annotations.Annotation;
import org.maplibre.android.annotations.BaseMarkerOptions;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.log.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

// Fork-only workaround for a MapLibre Native <= 13.2.0 use-after-free on the
// renderer (VELOPLANNER_MOBILE-GW), kept in this package because the types it
// touches (AnnotationManager, Markers, ShapeAnnotations) are package-private.
//
// AnnotationManager.onTap runs two synchronous renderer-mailbox queries on
// EVERY map tap — markers.obtainAllIn (queryPointAnnotations) and
// shapeAnnotations.obtainAllIn (queryShapeAnnotations) — before the
// OnMapClickListener chain, even when not a single annotation was ever added.
// Unlike every other synchronous renderer ask, these have a 200 ms timeout
// (NativeMapView.annotationRequestTimeout): on timeout the caller moves on but
// the ask message STAYS in the renderer mailbox. Because onSingleTapConfirmed
// fires ~300 ms after finger-up from a Handler message that survives view
// detach, such an ask can strand on a dead render thread with the app still
// alive. On re-attach MapRenderer.onSurfaceCreated destroys and recreates the
// Renderer on the SAME never-closed mailbox, so when the stranded receive is
// later executed (SurfaceViewRenderThreadGuard's mailbox heal re-posts it)
// the message dispatches into the freed Renderer — SIGSEGV in
// RenderOrchestrator::queryShapeAnnotations.
//
// The guard swaps AnnotationManager's markers/shapeAnnotations containers for
// delegating wrappers that short-circuit obtainAllIn to an empty list when
// there are no annotations at all (MLRN never uses this legacy annotation API,
// so this is the path taken on every tap — no renderer round-trip, which also
// closes the unsynchronized rendererRef window during Renderer recreation) or
// when the renderer is unavailable (dead render thread / detached view).
// Everything else delegates verbatim, so apps that somehow do add annotations
// keep stock behavior while the renderer is healthy.
//
// Reflection-based and fail-open: if the SDK layout changes (or R8 strips the
// fields — see consumer-rules.pro), install() logs, returns false, and stock
// behavior is kept.
@SuppressWarnings("deprecation")
public final class MLRNAnnotationHitTestGuard {
  private static final String LOG_TAG = "MLRNAnnotationHitTestGuard";

  public interface RendererAvailability {
    boolean isAvailable();
  }

  private MLRNAnnotationHitTestGuard() {
  }

  public static boolean install(@NonNull MapLibreMap maplibreMap,
                                @NonNull RendererAvailability availability) {
    try {
      Field managerField = MapLibreMap.class.getDeclaredField("annotationManager");
      managerField.setAccessible(true);
      AnnotationManager manager = (AnnotationManager) managerField.get(maplibreMap);
      if (manager == null) {
        return false;
      }

      Field annotationsArrayField = AnnotationManager.class.getDeclaredField("annotationsArray");
      annotationsArrayField.setAccessible(true);
      @SuppressWarnings("unchecked")
      LongSparseArray<Annotation> annotationsArray =
          (LongSparseArray<Annotation>) annotationsArrayField.get(manager);

      Field markersField = AnnotationManager.class.getDeclaredField("markers");
      markersField.setAccessible(true);
      Markers markers = (Markers) markersField.get(manager);

      Field shapeAnnotationsField = AnnotationManager.class.getDeclaredField("shapeAnnotations");
      shapeAnnotationsField.setAccessible(true);
      ShapeAnnotations shapeAnnotations = (ShapeAnnotations) shapeAnnotationsField.get(manager);

      if (annotationsArray == null || markers == null || shapeAnnotations == null) {
        return false;
      }
      if (markers instanceof GuardedMarkers || shapeAnnotations instanceof GuardedShapeAnnotations) {
        return true;
      }

      markersField.set(manager, new GuardedMarkers(markers, annotationsArray, availability));
      shapeAnnotationsField.set(
          manager, new GuardedShapeAnnotations(shapeAnnotations, annotationsArray, availability));
      // android.util.Log, not MapLibre's Logger: MLRNLogModule sets the SDK
      // Logger verbosity to WARN, which would swallow this positive signal.
      Log.i(LOG_TAG, "Annotation hit-test guard installed");
      return true;
    } catch (Exception e) {
      Logger.e(LOG_TAG, "Failed to install annotation hit-test guard; stock behavior kept", e);
      return false;
    }
  }

  private static boolean shouldQuery(@NonNull LongSparseArray<Annotation> annotationsArray,
                                     @NonNull RendererAvailability availability) {
    return !annotationsArray.isEmpty() && availability.isAvailable();
  }

  private static final class GuardedMarkers implements Markers {
    private final Markers delegate;
    private final LongSparseArray<Annotation> annotationsArray;
    private final RendererAvailability availability;

    GuardedMarkers(Markers delegate, LongSparseArray<Annotation> annotationsArray,
                   RendererAvailability availability) {
      this.delegate = delegate;
      this.annotationsArray = annotationsArray;
      this.availability = availability;
    }

    @Override
    public Marker addBy(@NonNull BaseMarkerOptions markerOptions, @NonNull MapLibreMap maplibreMap) {
      return delegate.addBy(markerOptions, maplibreMap);
    }

    @Override
    public List<Marker> addBy(@NonNull List<? extends BaseMarkerOptions> markerOptionsList,
                              @NonNull MapLibreMap maplibreMap) {
      return delegate.addBy(markerOptionsList, maplibreMap);
    }

    @Override
    public void update(@NonNull Marker updatedMarker, @NonNull MapLibreMap maplibreMap) {
      delegate.update(updatedMarker, maplibreMap);
    }

    @Override
    public List<Marker> obtainAll() {
      return delegate.obtainAll();
    }

    @NonNull
    @Override
    public List<Marker> obtainAllIn(@NonNull RectF rectangle) {
      if (!shouldQuery(annotationsArray, availability)) {
        return new ArrayList<>();
      }
      return delegate.obtainAllIn(rectangle);
    }

    @Override
    public void reload() {
      delegate.reload();
    }
  }

  private static final class GuardedShapeAnnotations implements ShapeAnnotations {
    private final ShapeAnnotations delegate;
    private final LongSparseArray<Annotation> annotationsArray;
    private final RendererAvailability availability;

    GuardedShapeAnnotations(ShapeAnnotations delegate,
                            LongSparseArray<Annotation> annotationsArray,
                            RendererAvailability availability) {
      this.delegate = delegate;
      this.annotationsArray = annotationsArray;
      this.availability = availability;
    }

    @Override
    public List<Annotation> obtainAllIn(RectF rectF) {
      if (!shouldQuery(annotationsArray, availability)) {
        return new ArrayList<>();
      }
      return delegate.obtainAllIn(rectF);
    }
  }
}
