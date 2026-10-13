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
for profile in 1.21.11 26.2 26.3; do
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
for profile in 1.21.11 26.2 26.3; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  export GAIUS_BUILD_ROOT="port/target/${profile}"
  export GAIUS_OVERLAY_DIRECTORY="port/work/overlays/${profile}"
  export GAIUS_DIST_DIRECTORY="port/web/dist/${profile}"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
  python3 port/scripts/legacy/quick-check.py
  GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS=500 \
    node port/scripts/singleplayer-worker-runtime-smoke.mjs
done
env -u GAIUS_BUILD_ROOT -u GAIUS_OVERLAY_DIRECTORY -u GAIUS_DIST_DIRECTORY \
  GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
  ./port/mvnw -B -ntp -f apps/server-plugin/pom.xml package
```

Both `26.2` and `26.3` require JDK 25 or newer; set `GAIUS_JAVA_HOME` or
`JAVA_HOME` before the loop. `1.21.11` requires JDK 21 or newer (its profile's
`javaVersion`): the same JDK 25 also satisfies it, or point `GAIUS_JAVA_HOME`
at a JDK 21 for that profile's iteration. The `26.3` client compiles its shaders with the
WebAssembly shader toolchain (shaderc and SPIRV-Cross built by
`port/scripts/build-wasm-shader-toolchain.sh`), so its release build also needs
either `GAIUS_EMSDK` (an emsdk checkout with the emscripten version pinned in
`port/wasm/shader-toolchain/pins.env`) or `GAIUS_SHADER_TOOLCHAIN_PREBUILT` (the
verified output directory of an earlier toolchain build). A release build
(`GAIUS_SHADER_TOOLCHAIN_STRICT=1`) fails without the toolchain; `1.21.11` and
`26.2` never use it.

Every profile also ships the wasm kernels (meshing, world generation, light and
noise), built by `port/native/build-wasm-variants.sh` in a SIMD128 and a
baseline variant. That needs `cargo` and the `wasm32-unknown-unknown` target
(`rustup target add wasm32-unknown-unknown`) plus Node.js, which reads the
module exports. Set `GAIUS_KERNELS_STRICT=1` for a release so a missing
toolchain fails the build; without it the build warns and the pages keep every
vanilla Java path. `GAIUS_SKIP_KERNELS=1` leaves the kernels out on purpose.
`build-version-release.sh` stages the kernels together with the boot script
(`port/web/boot`), the Service Worker (`port/web/sw`), the kernel runtime and
job codecs (`port/web/kernels`) and the graphics quality layer
(`port/web/runtime/quality`) next to `index.html`; a standalone
`build-teavm.sh` does the same for a local dist. The commands above are release gates to run, not claims about
every checkout. Record their actual results in the release notes or release
checklist. Release bundles are compiled and verified on the maintainer's
machine. GitHub Actions does not compile TeaVM release artifacts.

### WebAssembly GC release (v0.4)

From v0.4 every profile ships a WebAssembly GC client and singleplayer server
(`port/scripts/build-wasmgc.sh --release`, TeaVM `ADVANCED`) next to the
JavaScript build, which stays as the fallback for browsers without WebAssembly
GC, `try_table` or JSPI. A release role compiles the module with its name
section and then:

1. runs binaryen's `wasm-opt` on it (`port/scripts/wasm-opt-release.sh`):
   `-Os -g`, open world (the closed-world GC passes miscompiled a JSO callback),
   and only the features of the JSPI browser baseline (GC, reference types,
   exception handling, bulk memory, non-trapping float-to-int, sign extension,
   mutable globals, multi-value, SIMD; never `-all`). The output is pinned to
   binaryen 133: set `GAIUS_WASM_OPT` to its `wasm-opt`. Without it, or with
   another binaryen release, the build warns and ships TeaVM's module;
   `GAIUS_WASM_OPT_STRICT=1` (set by the release matrix below) makes that an
   error. The report is `<role build root>/wasm-opt.json`;
2. moves the name section into `<role build root>/symbols/<module>.symbols.wasm`
   (`port/tools/wasm/wasm-symbols.mjs`), so the symbol file names the functions
   of the optimized module; `port/tools/wasm/wasm-symbolize.mjs --symbols`
   turns stack traces of the shipped module back into Java frames. TeaVM's line
   tables describe the module before `wasm-opt` and are kept in
   `symbols/pre-opt/` with that module's sha256 (a `GAIUS_WASM_OPT=off` release
   build writes that module again);
3. writes the build identity `<module>.build.json` next to the module: role
   `wasm-client` (`classes.wasm`) or `wasm-singleplayer-worker`
   (`singleplayer-server.wasm`), with the common source, protocol and overlay
   identity of the JavaScript sidecars plus a `wasm` record (TeaVM settings,
   the `wasm-opt` report, the symbol file, and the inputs only the WebAssembly
   GC build reads, among them the profile's patch manifests). Verify one with
   `gaius_build_identity.py verify --role wasm-client --artifact <dist>/classes.wasm`.

Release reproducibility requires byte-identical modules, symbol files, line
tables, optimization reports (excluding timing), and identity sidecars from one
source tree. `check-wasmgc-reproducible.sh build` performs two isolated builds;
it is a full TeaVM operation and must use the machine build slot. Preparation
work that forbids TeaVM must leave this gate open.

### Release build matrix (M7 v0.4.0 preparation)

The v0.4.0 matrix contains **six WebAssembly GC compilations and three frozen JavaScript fallback rebuilds**: one WasmGC client, one WasmGC singleplayer server, and one JS bundle (client plus server) for each of 26.3, 26.2 and 1.21.11. `port/versions/<profile>.json` owns membership through `pages: {"enabled": true, "runtimes": ["wasm", "js"]}`. The packaging tools do not contain Minecraft version lists. Profiles run newest first according to `official.releaseTime`; within each profile the order is:

1. Verify and reuse the frozen JS sidecars, or rebuild that fallback only when a listed trigger applies.
2. Compile the WasmGC client with `build-wasmgc.sh --release` and `GAIUS_TEAVM_MAX_HEAP=8g`.
3. Compile the WasmGC singleplayer server with the same command and `GAIUS_TEAVM_MAX_HEAP=14g`, using the same resources and overlays.
4. Re-encode TeaVM's data segment offsets with `port/tools/wasm/wasm-data-fix.mjs` (TeaVM 0.15 writes them as unsigned LEB128; `build-wasmgc.sh` does this right after TeaVM). Run `wasm-opt` with `--skip-pass=memory-packing`, then require `WebAssembly.validate` and `wasm-data-check` for both modules. A failed parity or validation gate declines to the vanilla Java path.
5. Verify both wasm identity sidecars, package the gzip-only dual-runtime site, then run the whole-release size gate after all profiles finish.

`build-release-matrix.sh --plan --frozen-js <previous root> <release root>`
reports the JS decisions without compiling. A full release uses:

```sh
export MAVEN_ARGS=--offline
export MAVEN_OPTS="-Xms512m -Xmx3g"
export CARGO_NET_OFFLINE=true
export GAIUS_WASM_OPT=<cached binaryen 133 wasm-opt>
export GAIUS_RELEASE_BUILD_WRAPPER="bash D:/gaius-next/v040/m1/slot.sh build pages-release --"
bash port/scripts/build-release-matrix.sh --frozen-js <previous root> <release root>
```

This command is for a later build window, **not** an authorization to build
during offline M6b/M7 preparation. Every full TeaVM invocation must acquire
`slot.sh build <tag> -- <command>`; never invoke the compiler directly or run
another full build in parallel. The 3 GB JVM limit still applies. Maven's
cached launcher must support `MAVEN_ARGS`; all vanilla jars, mappings, tools,
dependencies and native kernels must already be cached. `--offline` controls
Maven dependency resolution, not downloads by other build helpers: missing
inputs block the release rather than authorize a download. Use Cargo with
`--offline --locked`. A 3 GB out-of-memory failure is an open build gate, not a
reason to raise the heap on the shared machine.

Outputs live under `<release root>/<profile>/{js,wasm}/{build,overlays,dist}`.
The complete publication set includes `Gaius-site-<profile>.tar.gz`,
`Gaius-pages-matrix.json`, `Gaius-<profile>.html`, and
`Gaius-<profile>.manifest.json`; `release-matrix.json` and `pages-size.json`
record identities and size decisions. `build-and-publish-prerelease.ps1` stages
the matrix manifest before calculating `SHA256SUMS`. Symbols, compiler logs,
and build roots stay outside the Pages archive. Site archives are
deterministic: sorted regular-file entries, fixed mode/owner/time, gzip time
zero. Checksums use `<64 lowercase hex>  <asset name>` with two spaces and LF.

The JS fallback stays frozen. `<previous root>/<profile>/js/FROZEN.json`
records its commit, artifact identities, and optional read-only `buildRoot`
and `dist`. Rebuild it only when a rule in `release-js-triggers.json` fires:

- **Storage schema:** profile `storage` metadata or browser persistence changes.
- **Save format:** profile `worldVersion` or save persistence changes.
- **Relay protocol:** client relay transport or framing changes.
- **Worker protocol:** the singleplayer Worker bootstrap or the page/Worker messages change. One bootstrap serves both server runtimes of a site, so the frozen JS server must speak the current protocol.
- **Security fixes:** explicitly pass `--js-trigger security`; this is not auto-detected.

The trigger list for this release is deliberately limited to storage schema, save format, relay protocol, the singleplayer Worker protocol and security fixes. Launcher, Service Worker, renderer and other page-only changes do not rewrite the frozen JS bundles.

A missing or invalid frozen baseline also requires a new verified JS build.
`--plan` reports changed paths and reasons, including dirty source files;
`--sites-only` packages the existing verified builds without compiling. Launcher,
SW and site-only layout edits must not rewrite the frozen compiled JS files.

When assembling a Wasm-primary portable page from that recorded fallback, pass
`--frozen-js` to `build-portable-html.py`. This flag is limited to the embedded
JS fallback components; the Wasm build, shader toolchain, and ordinary JS-primary
portable builds retain strict current-input identity checks.

**Stronghold positions (fixed in source for both runtimes, owner decision).** The
earlier JS-only patch `patchChunkGeneratorStructureStateBrowserFastRings` returned
the start chunk of each concentric-ring position instead of running vanilla's biome
search (`BiomeSource.findBiomeHorizontal` over 112 blocks, a random pick among the
matching biomes). Vanilla moves almost every position, so the singleplayer worlds of
the JS runtime placed their strongholds where vanilla does not for the same seed, and
their ender eyes and `/locate` pointed there: 699 (1.21.11), 702 (26.2) and 702
(26.3) of 768 positions moved (6 seeds each), by up to 7 chunks. Multiplayer was
unaffected (the remote server generates). The patch is removed; both targets now
apply `patchChunkGeneratorStructureStateBrowserLazyRings` (helper `BrowserRingPositions`
in the shared sources), which keeps vanilla's loop, random draws and search and only
runs each search when a caller needs it (`contains` near a start chunk, or iteration
by the first ender eye or `/locate`, which runs all 128). `node
port/scripts/structure-rings-parity.mjs --target js` and `--target wasmgc` check the
positions and the per-chunk `contains()` answers against vanilla (768/768 positions and
9558/9558 answers on 3 profiles x 6 seeds, JVM only; no browser run).

The frozen JS dists were built before the fix and keep the old behaviour until the JS
bundles are rebuilt; the v0.4.0 release rebuild of the JS fallback carries the fix. No
rule of `release-js-triggers.json` fires for it, so a JS rebuild outside that release
needs its own decision. A world whose strongholds a frozen build generated keeps those
chunks after the rebuild (ender eyes then look at vanilla's positions); worlds created
after the rebuild match vanilla.

#### Minecraft support window

The latest **two Minecraft versions** receive active wasm maintenance. When a
new version ships, the older wasm profile leaving that window freezes at its
last accepted artifacts; keep its metadata, frozen JS fallback, site and release
downloads. Do not silently rebuild that older wasm profile with the new source
tree. For v0.4.0 preparation, 26.3 and 26.2 are active and 1.21.11 is frozen
after its initial accepted pair. `wasmSupport` in the Pages matrix records this
status from release timestamps; it is not evidence of acceptance.

For later releases, build the active profiles explicitly with `--profiles` and
reuse accepted older wasm distributions when assembling the full Pages matrix.
Partial build output is not a publication set: carry forward the accepted
frozen site archives, JS/download manifests and their recorded identities before
staging the complete release. The Pages assembler refuses missing matrix sites.
Frozen artifacts remain read-only. A necessary security or shared-protocol fix
must be an explicit exception with its own identity and acceptance evidence;
it must not expand the normal support window.

#### Required release gates

- Verify kernel semantics and golden parity for every profile; unsupported jobs
 must decline to vanilla Java. Fixture success is not a compiled-client pass.
- Verify JS/wasm sidecars, profile identity, reproducibility and asset SHA-256.
- Run the boot decision-table, site/SW/cache, and release-assembly fixtures.
- Pass the 900,000,000-byte gate for the complete published tree, including
 retained files, redirects and matrix metadata. Never drop valid retained
 files merely to turn a failing gate green.
- Complete real browser checks for each client/server pair, renderer fallback,
 world saves, relay interoperability and unchanged immutable cache responses.
 Browser checks require `slot.sh chrome <tag> -- <command>` in a later permitted
 window. Node mocks do not close M6b/M7 browser or production acceptance.

For a lightweight source-only hygiene check, run:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
```

A local pre-release of all three profiles is uploaded by
`tools/build-and-publish-prerelease.ps1` (default `-Profiles 1.21.11,26.2,26.3`), which
re-verifies every identity sidecar of each dist, including `shader-toolchain`
for a dist whose page loads the toolchain, before it stages `Gaius-<profile>.html`,
`Gaius-<profile>.manifest.json` and the site archive `Gaius-site-<profile>.tar.gz`
(all listed in `SHA256SUMS`). For a v0.4 release pass `-ReleaseRoot <release root>`
of the release matrix: nothing is built then, the size gate in `pages-size.json`
must have passed, the JavaScript fallback is checked where `release-matrix.json`
names it (`gaius_build_identity.py frozen` for a frozen one), the
`wasm-client` and `wasm-singleplayer-worker` sidecars are verified, and the site
archives come from the release root.

For a browser release, serve each `port/web/dist/<profile>/` directory locally
as the corresponding `/dist/<profile>/` launch in a real Chrome session. Enter
a new single-player world for every profile, let terrain load, move through at
least one chunk boundary, and confirm sound, visual rendering, block
interaction, and settings. For multiplayer, verify both the plugin path and
the RelayNode path for each supported protocol when those endpoints are
available. Save screenshots of the actual main menu, single-player world, and
multiplayer flow for the release documentation; do not use placeholders or
mock UI captures. Repeat the single-player check with `Gaius.html` opened
directly from disk (`file://`: no Service Worker, kernels and quality layer
inlined) and with `port/target/<profile>/pages-site/` served over HTTP (the
Pages layout).

Every v0.4 runtime path keeps a switch back to the vanilla behaviour for these
checks: `?gaiusKernels=0` (all wasm kernels), `?worldgenKernel=0`,
`?lightKernel=0`, `?gaiusSw=0` (unregisters the Service Worker),
`?gaiusTier=low|mid|high|ultra` and `?gaiusPost=0` (quality layer),
`?maxDpr=1` (canvas ratio) and `?gaiusPixelated=0` (smooth canvas scaling).

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
| `port/web/dist/<profile>/gaius-boot.js`, `gaius-sw.js`, `kernels/`, `runtime/quality/` | The page runtime the launcher's boot tag loads in a plain dist: kernel runtime, job codecs, kernel worker, wasm kernels with `kernels/kernels.json`, quality layer; `Gaius.html` inlines all of it |
| `port/target/<profile>/Gaius-site-<profile>.tar.gz` | The profile's multi-file GitHub Pages site: content-hashed assets, `index.html`, `gaius-site.json` and the Service Worker (deterministic archive of `port/target/<profile>/pages-site/`) |
| `<release root>/<profile>/wasm/dist/{classes,singleplayer-server}.wasm` and `<module>.build.json` | v0.4: the WebAssembly GC client and server of the release matrix and their build identity (roles `wasm-client`, `wasm-singleplayer-worker`); the site ships them as `.gz` only |
| `<release root>/<profile>/wasm/build/{,server-worker/}symbols/` | Symbol files, `wasm-opt` input sha256 and TeaVM line tables of the shipped modules: kept with the release records, never published |
| `<release root>/Gaius-site-<profile>.tar.gz`, `release-matrix.json`, `pages-size.json` | v0.4: the profile's site with the WebAssembly GC runtime, what the matrix decided and built, and the size gate |
| `apps/server-plugin/target/gaius-server-plugin-<version>.jar` | Optional Paper bridge plugin |

For the version in `VERSION`, stage every profile's assets outside Git's tracked source
tree, then create a checksum file:

```sh
release_dir="port/target/release-v$(tr -d '[:space:]' < VERSION)"
mkdir -p "$release_dir"
for profile in 1.21.11 26.2 26.3; do
  cp "port/web/dist/${profile}/Gaius.html" \
    "$release_dir/Gaius-${profile}.html"
  cp "port/web/dist/${profile}/Gaius.manifest.json" \
    "$release_dir/Gaius-${profile}.manifest.json"
  cp "port/target/${profile}/Gaius-site-${profile}.tar.gz" "$release_dir/"
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

Publication is a separate later operation. Offline preparation does not create
or modify a tag, release, deployment, or remote asset. The dispatch-only
`.github/workflows/pages.yml` resolves Latest or an explicit `release_tag`.
It checks out only packaging scripts and profile metadata, without LFS or
persisted Git credentials; repository files are never the published tree.
Only the verified `pages-publish` directory goes to the Pages upload action.

A v0.4.0 release supplies `Gaius-pages-matrix.json` in its `SHA256SUMS`.
The manifest carries the enabled profiles and exact asset paths derived from
`port/versions/*.json`; adding a profile requires metadata rather than script
or workflow version lists. Every matrix profile requires its site archive,
wasm client/server and frozen JS client/server. A partial matrix fails instead
of reverting to a legacy single-file package.

Archives unpack to `<profile>/`. Root `Gaius-<profile>.html` links redirect to
that directory while preserving query and fragment. The root has no index;
`gaius-pages.json` records the deployed tag, layout and profile list. Older
releases without a matrix manifest retain legacy site or portable HTML
assembly using the enabled profile metadata. A legacy portable layout installs
an unregister-only SW at `<profile>/gaius-sw.js`, without deleting other
profiles' browser caches.

Before unpacking, `gaius_pages_release.py` verifies the manifest and every site
archive against exactly one LF text-mode checksum record. Missing records,
CRLF/binary-mode records, duplicate records and wrong hashes fail. Archives
accept only unique regular relative files: no links, absolute paths or path
traversal. The run summary records per-profile JS/wasm/shared bytes, retained
bytes and verified asset hashes.

The prior deployed tag is read from `gaius-pages.json`. Its archives must pass
their own independent `SHA256SUMS`; only hashed assets absent from the new
tree are retained. The retention rule excludes `index.html`, `gaius-sw.js`,
`gaius-site.json`, client `classes.*`, `vanilla-assets.pack.*` and
`vanilla-sounds.*`. Old lazy servers, kernels and toolchain assets remain
available for already-open tabs. This preserves one previous site generation,
not unlimited historical archives. An unavailable or invalid previous release
produces a warning and no retention; valid retention is included in the hard
size gate and cannot be discarded to avoid failure.

The offline assembler can exercise the same workflow logic on local release
assets without deploying:

```sh
python port/scripts/build-pages-site.py --publish-release <asset directory> <empty output> \
 --tag <release tag> --previous-release <previous asset directory> --json <report path>
```

`--previous-release` is optional. The assembler checks the complete tree before
writing any output. Exactly 900,000,000 bytes is allowed; anything larger fails.
Redirects, root metadata, every profile and retained file count. The limit
cannot be raised by `--limit-bytes` or `GAIUS_PAGES_LIMIT_BYTES`.

#### Runtime selection and immutable module URLs

Each dual-runtime site's descriptor sets `runtimePolicy: "auto"`. The launcher
applies the same decision table to every profile:

| WasmGC + `try_table` + JSPI | WebGPU adapter and required shader assets | Runtime |
| --- | --- | --- |
| Available | Available | wasm + WebGPU |
| Available | Missing, rejected or initialization fails | wasm + WebGL2 |
| Any wasm prerequisite missing | Either | frozen JS + WebGL2 |

A gzip-only site without SW control additionally needs `DecompressionStream`;
without it the launcher stays on JS. Safari-/Firefox-like capability sets that
lack JSPI take that same fallback. Automatic server selection follows the
chosen client; a late wasm-client failure retires WebGPU and the wasm-server
choice before loading frozen JS. Explicit `runtime`, `gpu` and `serverRuntime`
query overrides remain available for diagnostics. Ordinary JS-only portable
artifacts keep their existing default behavior.

Only `classes.wasm.<hash>.gz` and `singleplayer-server.wasm.<hash>.gz` are
physical runtime modules in a release site. `gaius-site.json` maps them to
virtual `<name>.<hash>.wasm` URLs. The SW fetches gzip, decompresses it, stores
an `application/wasm` response, and serves that URL to `compileStreaming`.
The page/Worker inflation path handles first visits without SW control.
`gaius-site-inflate-v1` is required; gzip-only packaging refuses an incompatible
boot script. The release assembler rejects raw client/server wasm files.

Keep content-hashed URLs stable and return cached immutable responses unchanged.
Do not wrap an immutable cached wasm response just to add isolation headers;
isolation headers belong on documents and Workers. This preserves the browser's
code-cache path alongside CacheStorage. Actual compilation-cache persistence
is browser-dependent and remains a browser acceptance gate, not a Node result.
The release omits redundant JS `.gz` copies; frozen JS runtime bytes are reused
unchanged. WebGPU launcher assets come from the wasm distribution, not a
rewritten frozen JS distribution. If an existing wasm distribution has WebGPU
glue but predates `quality-post.js`, packaging supplies that measured page-only
script from `port/web/runtime/webgpu/`; it never restages the compiled JS or
writes into either input distribution. This closes the missing-script selection
gap while leaving browser renderer acceptance open.

#### Offline layout and size projection

The planner uses the newest complete artifacts by module modification time
within explicitly named project output roots. It does not scan drive roots or
follow `port/work` junctions. This preparation uses:

```sh
python port/scripts/build-pages-site.py \
 --matrix D:/gaius-next/v040/evidence/pages/layout --dry-run --project-missing \
 --artifact-root D:/gaius-next/v040/m1/out3 \
 --artifact-root D:/gaius-next/v040/m1/out2 \
 --json D:/gaius-next/v040/evidence/pages/pages-projection.json
```

`--dry-run` hashes and sizes the exact layout without copying artifacts or
writing archives. Only an explicitly requested JSON report is written.
Without `--project-missing`, an incomplete profile fails. Projection is legal
only in dry-run mode: missing runtime components use the newest measured donor,
with the missing component, donor path and projected status recorded per
profile. This is a size estimate, never profile-parity or release evidence.
When a previous site is supplied with `--previous-site`, its kept bytes are
measured conservatively; otherwise the planner explicitly reserves one
projected generation of retained files. A passing projection never sets
`releaseReady` true.

Run the local fixtures before handing off M6b/M7 preparation:

```sh
python port/scripts/test-build-pages-site.py
python port/scripts/test-pages-release-matrix.py
node port/scripts/gaius-boot-smoke.mjs
node port/scripts/gaius-boot-gpu-smoke.mjs
node port/scripts/service-worker-headers-smoke.mjs
```

Place temporary files and reports under the designated output directory.
These checks do not compile TeaVM, launch Chrome, verify the live site, or
close the remaining browser, build-identity and publication gates.

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
