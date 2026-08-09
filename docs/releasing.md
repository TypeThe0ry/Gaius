# Releasing Gaius

Gaius keeps source and the runnable browser release in the same repository.
Large browser bundles are tracked through Git LFS, which keeps GitHub's normal
Git object store free of files above its 100 MiB limit.

The public release version is stored in the root `VERSION` file. Keep the
RelayNode package and server-plugin project version aligned with it. Tags use
the form `v<version>`; for example, `VERSION=0.0.1` produces tag `v0.0.1`.

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

The commands above are release gates to run, not claims about every checkout.
Record their actual results in the release notes or release checklist. Also
run the repository hygiene checks:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
```

For a browser release, serve `port/web/` locally and verify the normal `/dist/`
launch in a real Chrome session. Enter a new single-player world, let terrain
load, move through at least one chunk boundary, and confirm sound, visual
rendering, block interaction, and settings. For multiplayer, verify both the
plugin path and the RelayNode path when those endpoints are available. Save
screenshots of the actual main menu, single-player world, and multiplayer flow
for the release documentation; do not use placeholders or mock UI captures.

## Publish Artifacts

The following files are versioned in Git LFS and can also be mirrored to a
GitHub Release or static host:

| Artifact | Use |
| --- | --- |
| `port/web/dist/Gaius.html` | Downloadable, browser-local single-player package |
| `port/web/dist/Gaius.html.gz` | Optional compressed portable payload |
| `port/web/dist/` | Static-host deployment input for the normal launcher |
| `apps/server-plugin/target/gaius-server-plugin-<version>.jar` | Optional Paper bridge plugin |

For version `0.0.1`, stage the release assets outside Git's tracked source
tree, then create a checksum file:

```sh
release_dir="port/target/release-v$(tr -d '[:space:]' < VERSION)"
mkdir -p "$release_dir"
cp port/web/dist/Gaius.html "$release_dir/Gaius.html"
cp "apps/server-plugin/target/gaius-server-plugin-$(tr -d '[:space:]' < VERSION).jar" "$release_dir/"
(cd "$release_dir" && shasum -a 256 Gaius.html gaius-server-plugin-*.jar > SHA256SUMS)
```

Publish the tag and assets with GitHub CLI after reviewing the staged diff:

```sh
version="$(tr -d '[:space:]' < VERSION)"
git tag -a "v$version" -m "Gaius Client 1.21.11 v$version"
git push origin main "v$version"
gh release create "v$version" \
  --title "Gaius Client 1.21.11 v$version" \
  --generate-notes \
  "port/target/release-v$version/Gaius.html" \
  "port/target/release-v$version"/gaius-server-plugin-*.jar \
  "port/target/release-v$version/SHA256SUMS"
```

The release page should identify the browser package, optional plugin, SHA256
checksums, supported client version, and any known runtime limitations.

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
