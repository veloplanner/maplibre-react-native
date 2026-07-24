# Location lifecycle fixes, explained

Plain-English companion to the two location commits (`fix(android): stop shared location
engine when its last consumer releases it`, `fix(ios): stop unused heading updates and
location manager retain cycle`). The technical audit trail is AUDIT-2026-07.md §2; deferred
follow-ups are in LOCATION-LIFECYCLE-PROPOSAL.md.

**The problem in one sentence:** once anything turned GPS on, several paths never turned it
off — a `Camera` with `trackUserLocation` left a 1 Hz high-accuracy GPS subscription running
for the rest of the app process even after the map unmounted (Android), and on iOS every JS
reload leaked a live `CLLocationManager` plus a compass that ran purely to produce data
nobody read.

## Android

### One engine instead of a new one per `enable()`

`LocationManager` (the wrapper) was always a singleton, but the `LocationEngine` inside it
was re-created on every `enable()` call. The engine object is a proxy that holds the mapping
between your callbacks and the OS location services — you can only cancel a subscription
through the *same proxy instance* you requested it on. The SDK `LocationComponent` is handed
the engine reference exactly once; after any second `enable()` (e.g. JS calling
`setMinDisplacement` while a camera tracks) it was left holding a stale proxy, giving two
divergent OS-level subscriptions with different params — and the stale one was unreachable
by `disable()` forever. Now the engine is created once in `init` and never replaced; only
the *request* (interval/priority/displacement) is rebuilt on re-enable.

### Owner tokens instead of a lone boolean

Think of the engine as a shared room light: each consumer (each `MLRNCamera`, the JS
location module) drops a token in a bowl when it needs the light (`enable(owner)`) and takes
it out when done (`disable(owner)`). The light goes off only when the bowl is empty.
Previously there was no bowl at all — JS calling `LocationManager.stop()` switched the light
off for the camera too, and the camera never switched it off for anyone (its `removeFromMap`
was an empty method). A set of owner objects (not a counter) makes double-`enable` from the
same consumer harmless.

This is also why the old `if (!locationManager.isActive())` guard in `MLRNCamera` had to
go: if the JS module had already started the engine, the guard made the camera skip
`enable(this)` — so its token was never in the bowl, and the JS module stopping would kill
the engine out from under a still-tracking camera.

### Two teardown hooks

The camera now releases its token in `removeFromMap` *and* in a new
`MLRNCameraManager.onDropViewInstance`. Both are needed because on the new architecture
(Fabric), removing a camera directly calls `removeFromMap`, but tearing down a whole subtree
(navigating away from the screen, dev reload) is only guaranteed to call
`onDropViewInstance`. The release is idempotent, so both firing is fine.

### Known behavior change

While a `trackUserLocation` camera is mounted, backgrounding the app no longer stops the
shared engine's callback — the JS module releases only its own token on host pause. The old
global stop was an accident of the shared singleton, not a design; the SDK
`LocationComponent`'s own subscription already kept running in background. Proper background
handling is an open question in LOCATION-LIFECYCLE-PROPOSAL.md.

## iOS

### Compass (heading) updates removed

Every `start` also called `startUpdatingHeading`, powering the magnetometer. The compass
readings were stored into a property that nothing ever read — the `heading` field JS
receives is `CLLocation.course` (GPS direction of travel), which is unchanged. Worse, with
the default settings every compass tick re-sent the *previous, unchanged* location to JS, so
physically rotating the phone spammed duplicate bridge events. Now the magnetometer stays
off and `onUpdate` fires only on actual GPS fixes.

### `delegate` made `weak`

There was a reference loop: the module strongly owns the manager, and the manager's `strong`
delegate property pointed back at the module. Each kept the other alive, so neither could
ever be freed — on every JS reload, React Native dropped the module but the module/manager
pair kept itself (and a running `CLLocationManager`) alive. One leaked GPS session per
reload. `weak` is the standard delegate convention for exactly this reason: the owner owns
the worker; the worker's back-pointer must not add ownership.

### `dealloc` rewritten

`dealloc` runs while an object is being destroyed. The old body called `[self stop]`, which
schedules a block holding a reference to `self` — taking a fresh reference to an object
mid-destruction is undefined behavior (it gets torn down twice when the block releases it).
This never crashed before only because the retain cycle meant `dealloc` was unreachable.
Once the cycle is fixed it runs for real, so the new version copies the `CLLocationManager`
into a local, disconnects the delegate immediately, and schedules only that local manager to
stop — the dying object is never captured.
