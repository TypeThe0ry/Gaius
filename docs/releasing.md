# Releasing Gaius

Gaius keeps source and the runnable browser release in the same repository.
Large browser bundles are tracked through Git LFS, which keeps GitHub's normal
Git object store free of files above its 100 MiB limit.

The public release version is stored in the root `VERSION` file. Keep the
RelayNode package and server-plugin project version aligned with it. Tags use
the form `v<version>`; for example, `VERSION=0.1.0` produces tag `v0.1.0`.

Before cloning or updating a release checkout, run:

```sh
git lfs install
git lfs pull
```

## Build and Verify

Run the fast source checks before compiling:

```sh
node tools/check-release-metadata.mjs
node tools/check-relay-registry.mjs
node tools/check-singleplayer-lifecycle.mjs
npm ci --prefix apps/bridge
npm run smoke --prefix apps/bridge
npm run smoke:profiles --prefix apps/bridge
```

The singleplayer lifecycle checks cover storage, reload hydration, Worker
bootstrap, MessagePort ownership and retirement, and profile isolation. They
are source-level fixtures; compiled Worker and browser results are separate.
For the public multiplayer transport probe, provide the target explicitly at
runtime. Never store the target IP or private origin address in the repository:

```sh
for profile in 26.2 26.3; do
  GAIUS_PUBLIC_RELAY_TARGET="$AUTHORIZED_TARGET_HOST:$AUTHORIZED_TARGET_PORT" \
    GAIUS_PUBLIC_RELAY_MINECRAFT_VERSION="$profile" \
    npm run smoke:public --prefix apps/bridge
done
```

These probes check STATUS, target attestation, and tunnel release, not LOGIN
or PLAY. Keep their actual scope in release notes.

From a clean source checkout, build each supported Minecraft profile in its
own state and output roots. The wrapper never changes `port/config.json` and
does not reuse the legacy shared `port/target`, `port/work/overlays`, or
`port/web/dist` roots:

```sh
for profile in 26.2 26.3; do
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

Both `26.2` and `26.3` require JDK 25 or newer; set `GAIUS_JAVA_HOME` or
`JAVA_HOME` before the loop. The `26.3` client compiles its shaders with the
WebAssembly shader toolchain (shaderc and SPIRV-Cross built by
`port/scripts/build-wasm-shader-toolchain.sh`), so its release build also needs
either `GAIUS_EMSDK` (an emsdk checkout with the emscripten version pinned in
`port/wasm/shader-toolchain/pins.env`) or `GAIUS_SHADER_TOOLCHAIN_PREBUILT` (the
verified output directory of an earlier toolchain build). A release build
(`GAIUS_SHADER_TOOLCHAIN_STRICT=1`) fails without the toolchain; `26.2` never
uses it. The commands above are release gates to run, not claims about
every checkout. Record their actual results in the release notes or release
checklist. Release bundles are compiled and verified on the maintainer's
machine. GitHub Actions does not compile TeaVM release artifacts.

For a lightweight source-only hygiene check, run:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
```

A local pre-release of both profiles is uploaded by
`tools/build-and-publish-prerelease.ps1` (default `-Profiles 26.2,26.3`), which
re-verifies every identity sidecar of each dist, including `shader-toolchain`
for a dist whose page loads the toolchain, before it stages `Gaius-<profile>.html`
and `Gaius-<profile>.manifest.json`.

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

The local release build generates the following profile-scoped files. They are
not automatically added to Git; if a release maintainer deliberately checks
them in, `port/web/dist/**` is covered by the repository's Git LFS attributes:

| Artifact | Use |
| --- | --- |
| `port/web/dist/<profile>/Gaius.html` | Downloadable, browser-local single-player package |
| `port/web/dist/<profile>/Gaius.html.gz` | Optional compressed portable payload |
| `port/web/dist/<profile>/Gaius.manifest.json` | Profile, protocol, input, and artifact identity record |
| `port/web/dist/<profile>/` | Static-host deployment input for that profile's launcher |
| `port/web/dist/<profile>/gaius-shader-toolchain.{js,json}`, `gaius-shaderc.*`, `gaius-spvc.*` | `26.3` only: the WebAssembly shader toolchain the launcher loads next to `index.html`; `Gaius.html` embeds a copy |
| `port/web/dist/<profile>/<artifact>.build.json` | Build identity sidecars: six roles for every profile (`client`, `singleplayer-worker`, `wasm-hotpath`, `worker-bootstrap`, `vanilla-assets`, `relay-registry`) plus `shader-toolchain` on `gaius-shader-toolchain.json` for `26.3` |
| `apps/server-plugin/target/gaius-server-plugin-<version>.jar` | Optional Paper bridge plugin |

For the version in `VERSION`, stage both profile assets outside Git's tracked source
tree, then create a checksum file:

```sh
release_dir="port/target/release-v$(tr -d '[:space:]' < VERSION)"
mkdir -p "$release_dir"
for profile in 26.2 26.3; do
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

For a genuinely new version only, create a new annotated tag after all gates
pass. Never repoint an existing published tag.

Download every uploaded asset to a fresh directory, verify it against the
published `SHA256SUMS`, and repeat the Chrome launch and single-player and
multiplayer acceptance checks on the downloaded HTML files. Never move an
already published release tag to include later fixes; create a new version
instead.

The release page should identify the browser package, optional plugin, SHA256
checksums, supported client version, and any known runtime limitations.

Before publishing, review the provenance and redistribution rights for every
embedded Minecraft asset and client-derived file. The repository's source
policy does not by itself grant permission to redistribute generated game
artifacts.

### Deploy GitHub Pages

GitHub Pages serves exactly two files, the Minecraft 26.2 and 26.3 clients at
`https://typethe0ry.github.io/Gaius/Gaius-26.2.html` and
`https://typethe0ry.github.io/Gaius/Gaius-26.3.html`. Minecraft 1.21.11 is
retired from Pages and its old URL must return 404. `.github/workflows/pages.yml`
downloads `Gaius-26.2.html` and `Gaius-26.3.html` from the repository's Latest
release unless the `release_tag` input names another tag, so mark the new
release Latest first and then dispatch the workflow:

```sh
gh workflow run pages.yml --ref main -f release_token="manual-$(date +%s)"
# Pin a specific tag (for example a pre-release) instead of Latest:
gh workflow run pages.yml --ref main -f release_token="manual-$(date +%s)" -f release_tag=v0.2.2
```

The workflow runs only on dispatch and does not check out the repository at
all (so it fetches no Git LFS objects): nothing from the repository is
published, and pushes to `main` (including `docs/**` and `relay-nodes.json`)
never redeploy Pages.

Before uploading, the workflow downloads that release's `SHA256SUMS` into
`$RUNNER_TEMP`, outside the published artifact, and checks each client with
`sha256sum --check` against its one record (`Gaius-26.2.html` and
`Gaius-26.3.html` each need exactly one). A release without `SHA256SUMS`,
without either record, or with a mismatching hash fails the run before
anything is deployed. The run summary records the tag and both deployed
sha256 values only after both checks pass. `SHA256SUMS` must use LF line
endings and text-mode records (`<64 lowercase hex>  Gaius-26.2.html`, two
spaces, no `*` binary marker); a CRLF or binary-mode record does not match and
fails the run.

Check the live site with `node tools/verify-github-pages-cdp.mjs`. Set
`GAIUS_PAGES_EXPECTED_SHA256_262` and `GAIUS_PAGES_EXPECTED_SHA256_263` to the
release's `Gaius-26.2.html` and `Gaius-26.3.html` sha256 values to make the
verifier hash the live bytes and fail on a mismatch (`GAIUS_PAGES_EXPECTED_SHA256`
is still accepted as the 26.2 value);
the live sha256 is recorded in the report either way. Because a fresh deploy can take minutes
to reach every CDN edge (Pages responses carry `max-age=600`), a mismatch is
re-fetched from the same canonical URL players load, under bounded backoff for
up to `GAIUS_PAGES_SHA256_RETRY_MS` (default 600000, 10 minutes; `0` disables
retries), until the edge serves the release bytes. Every attempt's status, byte
count and sha256 (or fetch error) is recorded under
`live["<file>"].attempts`. Extract both records
and refuse to run without them, since an empty value would silently skip that
page's hash check:

```sh
GAIUS_PAGES_EXPECTED_SHA256_262="$(awk '$2 == "Gaius-26.2.html" { print $1 }' SHA256SUMS)" &&
  GAIUS_PAGES_EXPECTED_SHA256_263="$(awk '$2 == "Gaius-26.3.html" { print $1 }' SHA256SUMS)" &&
  GAIUS_PAGES_EXPECTED_SHA256_262="${GAIUS_PAGES_EXPECTED_SHA256_262:?no Gaius-26.2.html record in SHA256SUMS}" \
  GAIUS_PAGES_EXPECTED_SHA256_263="${GAIUS_PAGES_EXPECTED_SHA256_263:?no Gaius-26.3.html record in SHA256SUMS}" \
  node tools/verify-github-pages-cdp.mjs
```

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
