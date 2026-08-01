# Fork changelog

Changes VeloPlanner's fork carries on top of upstream
[maplibre/maplibre-react-native](https://github.com/maplibre/maplibre-react-native).
Upstream's own history is in [CHANGELOG.md](./CHANGELOG.md); this file only covers what the fork
adds.

Each entry ends with an `Upstream:` line — together they are the upstreaming worklist. Keep them
current: when a PR opens, record its number; when it merges, record the release, and drop the
entry when the next `main` sync brings the change back.

- `Upstream: not submitted` — carried by the fork only
- `Upstream: PR #N` — open against maplibre/maplibre-react-native
- `Upstream: merged in vX.Y.Z` — drop the patch on the next sync

Section headings are the release tag verbatim — `.github/workflows/veloplanner-release.yml`
extracts the matching section as the GitHub release notes.

## Unreleased

_Nothing yet._

## v11.3.6-veloplanner.3

_2026-08-01 · base: upstream v11.3.6_

### Fixed

- **android** — guard `waitForLayer` against a null style during style switch
  ([`cbbeecb`](https://github.com/veloplanner/maplibre-react-native/commit/cbbeecb7))

  Updating `afterId`/`beforeId` on a mounted layer while `setStyle()` was still loading hit
  `mapLibreMap!!.style!!` and crashed (fatal NPE, Sentry `VELOPLANNER_MOBILE-A1`). The request is
  dropped instead — sources re-add their layers with current ordering props once the style
  finishes loading.

  _Upstream: not submitted._

- **android** — skip annotation hit-test renderer queries when empty or the renderer is
  unavailable
  ([`929fb4c`](https://github.com/veloplanner/maplibre-react-native/commit/929fb4c7))

  `AnnotationManager.onTap` does two synchronous renderer asks on every tap
  (`queryPointAnnotations` + `queryShapeAnnotations`) before the `OnMapClickListener` chain, so
  the `isRendererAvailable()` guards never saw them. Uniquely among sync asks they have a 200 ms
  timeout, so the caller survives while the message stays queued: a tap in the post-detach
  `onSingleTapConfirmed` window stranded it on the dead render thread, re-attach recreated the
  Renderer on the same never-closed mailbox, and the mailbox heal replayed the message into the
  freed Renderer — SIGSEGV in `RenderOrchestrator::queryShapeAnnotations` (Sentry
  `VELOPLANNER_MOBILE-GW`). `MLRNAnnotationHitTestGuard` wraps the annotation containers and
  short-circuits `obtainAllIn` when no annotations exist or the renderer is unavailable.

  _PR note:_ `MLRNAnnotationHitTestGuard` is a reflection-based workaround like
  `SurfaceViewRenderThreadGuard`. The root cause is in MapLibre Native —
  `MapRenderer.onSurfaceCreated` recreates the Renderer on a never-closed mailbox and never
  invalidates `rendererRef` — worth an issue on maplibre-native either way. Background:
  [ANR-RENDER-THREAD-FINDINGS.md](./ANR-RENDER-THREAD-FINDINGS.md) (fix 4).

  _Upstream: not submitted._

### Fork-only

Not part of the published package.

- E2E: extend the Maestro `arrange` flow's waits so slow CI simulator cold starts don't flake the
  first flows ([`343ccfd`](https://github.com/veloplanner/maplibre-react-native/commit/343ccfde))
- Docs: annotation hit-test SIGSEGV writeup in the audit and findings docs
  ([`be6ec84`](https://github.com/veloplanner/maplibre-react-native/commit/be6ec844))

## v11.3.6-veloplanner.2

_2026-07-26 · base: upstream v11.3.6_

### Fixed

- **android** — stop the shared location engine when its last consumer releases it
  ([`bb1c9ca`](https://github.com/veloplanner/maplibre-react-native/commit/bb1c9ca1))

  `LocationManager` now creates its `LocationEngine` exactly once and refcounts
  `enable(owner)`/`disable(owner)` across consumers. `MLRNCamera` releases its hold on
  `removeFromMap`/`onDropViewInstance` — previously `removeFromMap` was empty, so a tracking
  camera left a `HIGH_ACCURACY` GPS subscription running for the process lifetime.
  `MLRNLocationModule` releases only its own hold, so JS `LocationManager.stop()` no longer
  cancels the camera path.

  _PR note:_ ships a documented edge-case behavior change — while a `trackUserLocation` camera is
  mounted, host pause no longer stops the manager's engine callback. Plain-English writeup:
  [LOCATION-LIFECYCLE-FIXES.md](./LOCATION-LIFECYCLE-FIXES.md).

  _Upstream: not submitted._

- **ios** — stop unused heading updates and the location manager retain cycle
  ([`3dce901`](https://github.com/veloplanner/maplibre-react-native/commit/3dce901c))

  `MLRNLocationManager` stored `CLHeading` into a write-only property that never reached JS, while
  every magnetometer callback re-emitted `onUpdate` with a stale location. Heading updates are
  removed, `delegate` is now `weak` so the module deallocates and `CLLocationManager` actually
  stops, and `dealloc` no longer captures `self` in a dispatched block. Writeup:
  [LOCATION-LIFECYCLE-FIXES.md](./LOCATION-LIFECYCLE-FIXES.md).

  _Upstream: not submitted._

- **android** — survive dead render threads and wedged renderer mailboxes (tap ANR)
  ([`fe16b77`](https://github.com/veloplanner/maplibre-react-native/commit/fe16b77a))

  Two ways a synchronous renderer round-trip still blocked the main thread forever with the view
  attached and unpaused (Sentry `VELOPLANNER_MOBILE-FY`): the render thread can die in place and
  `queueEvent` posts into the dead thread's private list; and mbgl's `Mailbox::push` schedules its
  receive only on the empty→non-empty transition, so one receive stranded on an exited thread
  wedges the mailbox permanently. `isRendererAvailable()` adds a liveness check to the query
  guards, and stranded runnables are re-posted onto the replacement thread on re-attach so the
  mailbox drains and heals. All the reflection machinery moved into a new
  `SurfaceViewRenderThreadGuard` to keep the fork's `MLRNMapView.kt` diff small.

  _PR note:_ `SurfaceViewRenderThreadGuard` is a reflection-based workaround; upstream likely wants
  the real fix in MapLibre Native (park queued events on the view, not the render thread) — worth
  an issue on maplibre-native either way. Background:
  [ANR-RENDER-THREAD-FINDINGS.md](./ANR-RENDER-THREAD-FINDINGS.md).

  _Upstream: not submitted._

- **android** — close ANR-guard review gaps
  ([`8b9052e`](https://github.com/veloplanner/maplibre-react-native/commit/8b9052e5))

  Consumer ProGuard rules keep the reflected renderer field names through R8 (without them
  minification silently disabled every guard); `getClusterExpansionZoom` rejects instead of
  returning `0` when the renderer is unavailable; the stale render thread is released after
  migration.

  _Upstream: not submitted_ — fold into the `fe16b77a` PR.

### Performance

- Memoize style and data serialization in `Layer`, `Map` and `GeoJSONSource`
  ([`a6b65de`](https://github.com/veloplanner/maplibre-react-native/commit/a6b65ded))

  `Layer` and `Map` memoized their native-prop transforms on `[props]` — a rest object with a
  fresh identity every render — so the memo never hit and `mergeStyleProps`/`transformStyle` and
  `JSON.stringify(mapStyle)` re-ran on every parent re-render. `GeoJSONSource` stringified its
  data inline in render, per animation frame for animated sources. The memos now key on serialized
  values, and the stable `reactStyle`/`filter` identities also stop per-render native prop
  resends.

  _Upstream: not submitted._

- Gate per-frame render events on JS subscription
  ([`55b71b4`](https://github.com/veloplanner/maplibre-react-native/commit/55b71b44))

  `onWillStartRenderingFrame`/`onDidFinishRenderingFrame(Fully)` were dispatched native→JS 60+/sec
  per map even with no JS listener. Fabric `DirectEventHandler`s aren't visible to native, so a
  derived `handledMapChangedEvents` prop carries handler presence from `Map.tsx` and native skips
  dispatch unless subscribed.

  _Upstream: not submitted._

### Fork-only

- CI: gate the fork release on the upstream Review suite
  ([`bf551c7`](https://github.com/veloplanner/maplibre-react-native/commit/bf551c78))
- Docs: fork branch model and release workflow in `FORK.md`
  ([`8dcd83f`](https://github.com/veloplanner/maplibre-react-native/commit/8dcd83fe));
  location-lifecycle and render-thread ANR findings docs; audit updates

## v11.3.6-veloplanner.1

_2026-07-23 · base: upstream v11.3.6_

First fork prerelease.

### Fixed

- **android** — avoid ANR from synchronous `queryRenderedFeatures` when the render thread can't
  serve it ([`3cbec71`](https://github.com/veloplanner/maplibre-react-native/commit/3cbec712))

  `queryRenderedFeatures` posts to the render thread's event queue and blocks the caller on a
  future. The render thread exits when the view is detached from the window and is only recreated
  on re-attach, so a query posted in that window blocks the main thread forever. Taps are
  especially exposed — `onSingleTapConfirmed` fires ~300 ms after the tap, so it can land after a
  navigation transition detached the view. The worst path needed no annotations at all:
  `createSymbolManager` ran unconditionally at style load and its `MapClickResolver` ran a
  synchronous query on every tap. The `SymbolManager` is now created lazily on first
  `getSymbolManager()` call, and the click/query paths are guarded by the paused/destroyed flags
  plus `isAttachedToWindow`. Background:
  [ANR-RENDER-THREAD-FINDINGS.md](./ANR-RENDER-THREAD-FINDINGS.md).

  _Upstream: not submitted._

- **android** — guard `MapLibreSurfaceView` detach reset against a dead render thread
  ([`9d0eb7c`](https://github.com/veloplanner/maplibre-react-native/commit/9d0eb7cb))

  `onDetachedFromWindow()` notifies its detached listener (→ `MapRenderer.nativeReset()`), which
  blocks the main thread waiting on the render thread with no timeout. The reset runnable is queued
  without a liveness check, so when the thread has already exited — a second detach without
  re-attach in between, or an EGL-init crash — the task is silently swallowed and the main thread
  hangs forever. Later folded into `SurfaceViewRenderThreadGuard` by `fe16b77a`.

  _Upstream: not submitted_ — upstream together with `fe16b77a`.

- **android** — use async `getStyle` in `setTrackUserLocation` to avoid an NPE during style load
  ([`6911324`](https://github.com/veloplanner/maplibre-react-native/commit/69113243))

  `MapLibreMap.getStyle()` returns null whenever the style isn't fully loaded, so a
  `trackUserLocation` prop update landing in that window crashed on `style!!`. The async
  `getStyle` callback — the same pattern `enableLocation` already uses in this class — applies the
  update instead of crashing.

  _Upstream: not submitted._

- **ios** — serialize style image loads to avoid CUICatalog heap corruption
  ([`926e97e`](https://github.com/veloplanner/maplibre-react-native/commit/926e97ed))

  `MLRNImageQueueOperation` dispatched every image load onto the global concurrent queue. Local
  bundle assets resolve synchronously there via `+[UIImage imageNamed:]`, and dozens of concurrent
  lookups at map mount corrupted CUICatalog's lookup cache (malloc double-free in
  `-[NSCache setName:]`). Loads now go through one serial queue; network loads only serialize
  their kickoff, so downloads still overlap.

  _Upstream: not submitted._

### Fork-only

- CI: fork release workflow — pack and attach a tarball to a GitHub release on
  `v*-veloplanner.*` tags
  ([`01b3556`](https://github.com/veloplanner/maplibre-react-native/commit/01b35568))
- Docs: `AUDIT-2026-07.md`, `CLAUDE.md`
