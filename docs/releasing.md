# Releasing Gaius

Gaius keeps source and the runnable browser release in the same repository.
Large browser bundles are tracked through Git LFS, which keeps GitHub's normal
Git object store free of files above its 100 MiB limit.

Before cloning or updating a release checkout, run:

```sh
git lfs install
git lfs pull
```

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

The following files are versioned in Git LFS and can also be mirrored to a
GitHub Release or static host:

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

`.gitattributes` routes release files through Git LFS. It cannot repair a large
ordinary Git blob that already exists in a branch ancestor. Start from the
clean LFS-backed mainline for new work; do not force-push `main` as a cleanup
shortcut.

Run these checks before opening a pull request:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
git rev-list --objects origin/main..HEAD \
  | git cat-file --batch-check='%(objecttype) %(objectsize) %(rest)' \
  | awk '$1 == "blob" && $2 > 100000000 { print }'
```

The final command must produce no output: every oversized release file should
be an LFS pointer in Git, visible through the preceding `git lfs ls-files`.
