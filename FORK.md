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

The worklist is the `Upstream:` line on every entry in
[FORK_CHANGELOG.md](./FORK_CHANGELOG.md) — update it when a PR opens and again when it
merges, and drop the entry once the next sync brings the change back.

## Releasing

Releases are prereleases of the upstream version currently on `veloplanner`,
e.g. `11.3.6-veloplanner.1`.

1. Add a `## vX.Y.Z-veloplanner.N` section to [FORK_CHANGELOG.md](./FORK_CHANGELOG.md)
   describing what the release carries. The heading must be the tag verbatim — the release
   workflow extracts that section as the GitHub release notes and fails the run if it's
   missing.
2. On `veloplanner`, bump `version` in `package/package.json` to `X.Y.Z-veloplanner.N`
   and commit (`chore: version X.Y.Z-veloplanner.N`).
3. Tag and push:
   ```bash
   git tag vX.Y.Z-veloplanner.N
   git push origin veloplanner vX.Y.Z-veloplanner.N
   ```
4. `.github/workflows/veloplanner-release.yml` checks the tag against
   `package/package.json` and `FORK_CHANGELOG.md`, runs the upstream Review suite
   (lint, tests, library/docs builds, Android + iOS example builds), packs the
   package, and attaches the tarball to a GitHub release.
5. The VeloPlanner app consumes the release-asset URL in its `package.json`.

## Fork-only files

Exist only on `veloplanner`, never in upstream PRs:

- `FORK.md`
- `FORK_CHANGELOG.md`
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
