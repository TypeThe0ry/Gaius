# Releasing Gaius

Gaius keeps source and the runnable browser release in the same repository.
Large browser bundles are tracked through Git LFS, which keeps GitHub's normal
Git object store free of files above its 100 MiB limit.

The public release version is stored in the root `VERSION` file. Keep the
RelayNode package and server-plugin project version aligned with it. Tags use
the form `v<version>`; for example, `VERSION=0.0.3` produces tag `v0.0.3`.

Before cloning or updating a release checkout, run:

```sh
git lfs install
git lfs pull
```

## Build and Verify

From a clean source checkout, build each supported Minecraft profile in its
own state and output roots. The wrapper never changes `port/config.json` and
does not reuse the legacy shared `port/target`, `port/work/overlays`, or
`port/web/dist` roots:

```sh
for profile in 1.21.11 26.2; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  export GAIUS_BUILD_ROOT="port/target/${profile}"
  export GAIUS_OVERLAY_DIRECTORY="port/work/overlays/${profile}"
  export GAIUS_DIST_DIRECTORY="port/web/dist/${profile}"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
  python3 port/scripts/quick-check.py
  GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS=500 \
    node port/scripts/singleplayer-worker-runtime-smoke.mjs
done
env -u GAIUS_BUILD_ROOT -u GAIUS_OVERLAY_DIRECTORY -u GAIUS_DIST_DIRECTORY \
  GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
  ./port/mvnw -B -ntp -f apps/server-plugin/pom.xml package
```

The `1.21.11` profile requires JDK 21 and `26.2` requires JDK 25 or newer;
set `GAIUS_JAVA_HOME` or `JAVA_HOME` to the matching JDK before each loop
iteration. The commands above are release gates to run, not claims about
every checkout. Record their actual results in the release notes or release
checklist. The GitHub release workflow runs the same profile matrix on
separate runners and uploads one artifact per profile.

For a lightweight source-only hygiene check, run:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
```

For a browser release, serve each `port/web/dist/<profile>/` directory locally
as the corresponding `/dist/<profile>/` launch in a real Chrome session. Enter
a new single-player world for both profiles, let terrain load, move through at
least one chunk boundary, and confirm sound, visual rendering, block
interaction, and settings. For multiplayer, verify both the plugin path and
the RelayNode path for each supported protocol when those endpoints are
available. Save screenshots of the actual main menu, single-player world, and
multiplayer flow for the release documentation; do not use placeholders or
mock UI captures.

## Publish Artifacts

The release workflow generates the following profile-scoped files and uploads
them as CI/GitHub Release artifacts. They are not automatically added to Git;
if a release maintainer deliberately checks them in, `port/web/dist/**` is
covered by the repository's Git LFS attributes:

| Artifact | Use |
| --- | --- |
| `port/web/dist/<profile>/Gaius.html` | Downloadable, browser-local single-player package |
| `port/web/dist/<profile>/Gaius.html.gz` | Optional compressed portable payload |
| `port/web/dist/<profile>/Gaius.manifest.json` | Profile, protocol, input, and artifact identity record |
| `port/web/dist/<profile>/` | Static-host deployment input for that profile's launcher |
| `apps/server-plugin/target/gaius-server-plugin-<version>.jar` | Optional Paper bridge plugin |

For the version in `VERSION`, stage both profile assets outside Git's tracked source
tree, then create a checksum file:

```sh
release_dir="port/target/release-v$(tr -d '[:space:]' < VERSION)"
mkdir -p "$release_dir"
for profile in 1.21.11 26.2; do
  cp "port/web/dist/${profile}/Gaius.html" \
    "$release_dir/Gaius-${profile}.html"
  cp "port/web/dist/${profile}/Gaius.manifest.json" \
    "$release_dir/Gaius-${profile}.manifest.json"
done
cp "apps/server-plugin/target/gaius-server-plugin-$(tr -d '[:space:]' < VERSION).jar" "$release_dir/"
source port/scripts/version-profile.sh
(cd "$release_dir" && for artifact in *; do
  [[ "$artifact" == SHA256SUMS ]] && continue
  printf '%s  %s\n' "$(gaius_sha256_file "$artifact")" "$artifact"
done > SHA256SUMS)
```

Publish the tag and assets with GitHub CLI after reviewing the staged diff:

```sh
version="$(tr -d '[:space:]' < VERSION)"
git tag -a "v$version" -m "Gaius Client 1.21.11 + 26.2 v$version"
git push origin main "v$version"
gh release create "v$version" \
  --title "Gaius Client 1.21.11 + 26.2 v$version" \
  --generate-notes \
  "port/target/release-v$version"/Gaius-*.html \
  "port/target/release-v$version"/Gaius-*.manifest.json \
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
