# VeloPlanner fork

This is VeloPlanner's fork of [maplibre/maplibre-react-native](https://github.com/maplibre/maplibre-react-native).
It carries fixes we need before they land upstream, plus fork-only CI and docs.

## Remotes

| Remote     | URL                                                     |
| ---------- | ------------------------------------------------------- |
| `origin`   | `git@github.com:veloplanner/maplibre-react-native.git`  |
| `upstream` | `https://github.com/maplibre/maplibre-react-native.git` |

## Branch model

- **`main`** — pure mirror of `upstream/main`. Fast-forward only, **never commit to it**.
  It tracks `upstream/main`, so pushing to the fork is always the explicit
  `git push origin main`.
- **`veloplanner`** — the fork's default branch. All fork-only work lands here
  (directly or via short-lived branches): fixes, fork CI, fork docs, release tags.

## Syncing with upstream

```bash
git fetch upstream
git checkout main && git merge --ff-only upstream/main && git push origin main
git checkout veloplanner && git merge main
```

## Contributing upstream

Branch off `main`, cherry-pick from `veloplanner` if the fix originated there, and open
the PR against `maplibre/maplibre-react-native`:

```bash
git checkout -b fix/xyz main
git cherry-pick <sha>   # if the fix already exists on veloplanner
```

Fork-only files (see below) must never appear in these branches — branching from `main`
guarantees that. Once upstream merges, the change returns via the sync flow.

## Releasing

Releases are prereleases of the upstream version currently on `veloplanner`,
e.g. `11.3.6-veloplanner.1`.

1. On `veloplanner`, bump `version` in `package/package.json` to `X.Y.Z-veloplanner.N`
   and commit (`chore: version X.Y.Z-veloplanner.N`).
2. Tag and push:
   ```bash
   git tag vX.Y.Z-veloplanner.N
   git push origin veloplanner vX.Y.Z-veloplanner.N
   ```
3. `.github/workflows/veloplanner-release.yml` runs the upstream Review suite
   (lint, tests, library/docs builds, Android + iOS example builds), packs the
   package, and attaches the tarball to a GitHub release.
4. The VeloPlanner app consumes the release-asset URL in its `package.json`.

## Fork-only files

Exist only on `veloplanner`, never in upstream PRs:

- `FORK.md`
- `CLAUDE.md`
- `AUDIT-2026-07.md`
- `ANR-RENDER-THREAD-FINDINGS.md`
- `LOCATION-LIFECYCLE-FIXES.md`
- `LOCATION-LIFECYCLE-PROPOSAL.md`
- `.github/workflows/veloplanner-release.yml`

## GitHub settings (not visible in the repo)

- Default branch is `veloplanner`.
- The upstream `Release` workflow (semantic-release) is disabled in the Actions UI —
  it triggers on pushes to `main`/`beta`/`alpha` with no repository guard, so every
  upstream sync push would fire it.

## PRs to open to the upstream

- perf: memoize style and data serialization in Layer, Map, and GeoJSONSource
  - https://github.com/veloplanner/maplibre-react-native/commit/a6b65ded21210162d5a824745dab36e7123e28db
- perf: gate per-frame render events on JS subscription
  - https://github.com/veloplanner/maplibre-react-native/commit/55b71b44e6f3dee2924b40e59f17a941cb46de8e
- fix(android): stop shared location engine when its last consumer releases it
  - https://github.com/veloplanner/maplibre-react-native/commit/bb1c9ca1bd87c167fc0334d525c432a5d8de1384
  - plain-English explanation: [LOCATION-LIFECYCLE-FIXES.md](./LOCATION-LIFECYCLE-FIXES.md)
- fix(ios): stop unused heading updates and location manager retain cycle
  - https://github.com/veloplanner/maplibre-react-native/commit/3dce901c5e72baced31262c5df793f972baf0357
  - plain-English explanation: [LOCATION-LIFECYCLE-FIXES.md](./LOCATION-LIFECYCLE-FIXES.md)
- fix(android): survive dead render threads and wedged renderer mailboxes (tap ANR)
  - https://github.com/veloplanner/maplibre-react-native/commit/fe16b77ab7fb16d892242394ee675355f4f093e5
  - note for the PR: `SurfaceViewRenderThreadGuard` is a reflection-based workaround; upstream
    likely wants the real fix in MapLibre Native instead (park queued events on the view, not
    the render thread) — worth an issue on maplibre-native either way
- fix(android): render-thread ANR prevention (three commits; revisit the PointAnnotation
  resolver gap before upstreaming — see the doc below)
  - https://github.com/veloplanner/maplibre-react-native/commit/3cbec712e8fdef53d29ba7ad89ade4c180589411
  - https://github.com/veloplanner/maplibre-react-native/commit/9d0eb7cbe096aed6dd269cfdece205b91d35862d
  - https://github.com/veloplanner/maplibre-react-native/commit/1003899aa68c17e4778e939562d211b8b46daec7
  - plain-English explanation: [ANR-RENDER-THREAD-FINDINGS.md](./ANR-RENDER-THREAD-FINDINGS.md)