# The render-thread ANR guards (SurfaceViewRenderThreadGuard and the query
# guards in MLRNMapView) reflect on MapLibre SDK fields by string name:
# renderThread, detachedListener, eventQueue, renderThreadManager. R8 cannot
# see string-based lookups, so in a minified app build it renames those fields,
# the guards' reflection fails, and every ANR fix silently degrades to stock
# SDK behavior. Keep the field names; classes and methods stay optimizable.
-keepclassmembers class org.maplibre.android.maps.renderer.surfaceview.** { <fields>; }

# MLRNAnnotationHitTestGuard reflects on these fields by string name:
# annotationManager (MapLibreMap), annotationsArray/markers/shapeAnnotations
# (AnnotationManager). Same rationale as above.
-keepclassmembers class org.maplibre.android.maps.MapLibreMap { ** annotationManager; }
-keepclassmembers class org.maplibre.android.maps.AnnotationManager {
  ** annotationsArray;
  ** markers;
  ** shapeAnnotations;
}
