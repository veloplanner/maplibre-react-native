# Location lifecycle: background behavior, JS API, defaults

Companion to AUDIT-2026-07.md §2. The 2026-07 fixes made the location engine stop when its last
consumer unmounts (Android refcounting + `MLRNCamera` teardown; iOS retain-cycle fix). Background
behavior was deliberately left untouched — this document captures the current state and the open
questions for an issue.

## Current background behavior (after the 2026-07 fixes)

| Path | On app background |
|------|-------------------|
| Android, JS module (`UserLocation` / `useCurrentPosition`) | Pauses on host pause, resumes on host resume (`MLRNLocationModule` lifecycle listener) — correct today. |
| Android, map/camera path (`Camera trackUserLocation`, `NativeUserLocation`) | Keeps running. `MLRNMapView.onPause` only sets a flag; `LocationComponent.onStop()` is never called. With refcounting, a mounted tracking camera also keeps the shared engine's callback subscribed through host pause (previously the JS module's pause disabled it globally — an accident, not a design). |
| iOS, JS module | No lifecycle ties. With When-In-Use authorization the OS suspends delivery while backgrounded, but nothing is explicitly stopped; with Always/background modes it would keep running. |
| iOS, map path (`showsUserLocation`, `userTrackingMode`) | MapLibre Native's internal `CLLocationManager`; not controlled by this library. |

## Open questions

1. **Android map-path background pause.** Wire `LocationComponent.onStop()`/`onStart()` into host
   pause/resume, and release/re-acquire the shared engine when only camera consumers remain?
   Needs an opt-out for apps doing background tracking through the library.
2. **iOS background pause.** Observe `UIApplicationDidEnterBackground`/`WillEnterForeground` in
   `MLRNLocationManager` and stop/restart updates, with an `allowsBackgroundLocationUpdates`-style
   opt-out?
3. **Expose engine defaults to JS.** Current defaults are the most battery-hungry combination and
   are not configurable:
   - Android: interval 1000 ms, fastest 1000 ms, `PRIORITY_HIGH_ACCURACY` (only displacement is
     JS-settable). Candidates: `interval`, `fastestInterval`, `priority`.
   - iOS: `desiredAccuracy` defaults to `kCLLocationAccuracyBest`; `activityType` and
     `pausesLocationUpdatesAutomatically` unset. Candidates: `desiredAccuracy`, `activityType`.
4. **JS `LocationManager` hardening.**
   - Imperative `start()` is not counted as a consumer: a hook mount/unmount cycle afterwards
     kills tracking (`removeListener` → `stop()` at zero listeners).
   - Public `stop()` is not guarded by the listener count: it orphans live listeners, which never
     restart.
   - `setMinDisplacement` is a global last-writer-wins; should track per-consumer values and
     apply the minimum (audit §2.3). Also not re-applied after a stop/start cycle.
   - No `AppState` handling in JS on any path.

## Adjacent findings (separate issue candidates)

- iOS `getCurrentPosition` resolves the raw `MLRNLocation *` object instead of `[... toJSON]`
  (`package/ios/modules/location/MLRNLocationModule.mm:48`) — on the new architecture this likely
  resolves `undefined`.
- `heading` in location events is GPS course-over-ground on both platforms (iOS
  `CLLocation.course`, Android `Location.bearing`), not compass heading — document or rename.
  iOS compass heading was collected but never delivered; removed 2026-07.
- Examples `UserLocationDisplacement.tsx` and `SetAndroidPreferredFramesPerSecond.tsx` use the
  imperative `LocationManager.start()`/`stop()` pattern that fights the JS listener refcount.
- Android: setting `Camera trackUserLocation` after mount never enables the shared
  `LocationManager` engine (`setTrackUserLocation` doesn't call `enableLocation()`); follow works
  only via the SDK LocationComponent's own request, and `lastKnownLocation` stays null on that
  path.
- Android `LocationManager.onFailure` swallows engine errors (`// TODO`), with no JS surface.
