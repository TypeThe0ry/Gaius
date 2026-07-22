# Releasing Gaius

Gaius has two separate deliverables:

- Source code and reproducible build scripts belong in Git.
- Browser bundles, portable HTML files, plugin JARs, and smoke-build output are
  generated artifacts. Deliver them through a GitHub Release, a CI artifact,
  or a static deployment, not through a source commit.

This separation matters because generated TeaVM files can exceed GitHub's
100 MiB single-file limit and make normal pushes fail.

## Build and Verify

From a clean source checkout with local Minecraft inputs already fetched:

```sh
./port/scripts/build-platform-smoke.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
node port/scripts/singleplayer-worker-runtime-smoke.mjs
(cd apps/server-plugin && ../../port/mvnw package)
```

For a browser release, also serve `port/web/` locally and verify the normal
`/dist/` launch in Chrome. Enter a single-player world, let terrain load, move
through at least one chunk boundary, and confirm sound and visual rendering.

## Publish Artifacts

Use a versioned GitHub Release or CI workflow to upload the artifacts below:

| Artifact | Use |
| --- | --- |
| `port/web/dist/Gaius.html` | Downloadable, browser-local single-player package |
| `port/web/dist/Gaius.html.gz` | Optional compressed portable payload |
| `port/web/dist/` | Static-host deployment input for the normal launcher |
| `apps/server-plugin/target/*.jar` | Optional Paper bridge plugin |

Before publishing, review the provenance and redistribution rights for every
embedded Minecraft asset and client-derived file. The repository's source
policy does not by itself grant permission to redistribute generated game
artifacts.

## Keep Git Pushable

`.gitignore` prevents new generated artifacts from being added, but it cannot
remove a large file that already exists in an ancestor commit. If a local branch
contains an oversized generated blob, create a clean branch from the remote
base and replay only source changes, or perform an explicitly reviewed history
rewrite. Do not force-push `main` as a cleanup shortcut.

Run these checks before opening a pull request:

```sh
git diff --check
git status --short
git rev-list --objects origin/main..HEAD \
  | git cat-file --batch-check='%(objecttype) %(objectsize) %(rest)' \
  | awk '$1 == "blob" && $2 > 100000000 { print }'
```

The final command must produce no output for a branch that will be pushed to
GitHub without Git LFS.
