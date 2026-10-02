# Contributing to Gaius

Gaius is a browser-porting project, so a useful contribution normally touches
the real Java client path, the TeaVM browser platform, the local server Worker,
or the bridge boundary. Keep a change small enough that its browser behavior
can be verified.

## Development Setup

Install Git LFS, JDK 25 or newer (for `26.2` and `26.3`), JDK 21 (for
`1.21.11` and the Paper plugin; a JDK 25 also builds the `1.21.11` client),
Node.js 22 or newer, Python 3, `curl`, `jq`, `unzip`, and `shasum`. After
cloning, fetch the large checked-in release objects before working with
generated browser files:

```sh
git lfs install
git lfs pull
```

The game inputs are local-only and are not supplied by this repository. Obtain
them through the supported local workflow, then perform the first remap:

```sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json ./port/scripts/fetch-version.sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json ./port/scripts/remap-client.sh
```

The full client compilation defaults to a 14 GiB Java heap. Use a machine with
at least 24 GiB of physical memory and close memory-heavy applications for
reliable full-release builds. Override `MAVEN_OPTS` only when the host has
enough headroom.

For a source checkout that already has the local inputs, the normal browser
release commands are:

```sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json \
  bash port/scripts/build-version-release.sh 26.2
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json \
  python3 port/scripts/quick-check.py
```

The build produces the runnable package under `port/web/dist/<profile>/`. Do not
edit generated files there by hand; change their source and rebuild instead.

## Repository Boundaries

- Put TeaVM-facing browser implementations in `port/src/main/java/`.
- Put class replacements in `port/overrides/` and bytecode transforms in
  `port/tools/`.
- Keep browser launcher and Worker bootstrap source under `port/web/`; never
  hand-edit the generated contents of `port/web/dist/`. Rebuild them and stage
  the result through Git LFS when intentionally updating the browser release.
- Keep bridge code under `apps/bridge/` and the optional Paper plugin under
  `apps/server-plugin/`.
- Do not add Minecraft JARs, assets, libraries, mappings, local worlds, bridge
  secrets, `port/target/`, or Maven `target/` directories to Git.

The small checked-in `packages/*/dist/` modules are part of the current source
layout. Do not replace or delete them merely because their directory is named
`dist`.

## Required Checks

Run the checks that cover the area you changed. For a full browser-port change,
use this order:

```sh
for profile in 1.21.11 26.2 26.3; do
  GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json" \
    bash port/scripts/build-version-release.sh "$profile"
  GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json" \
    python3 port/scripts/quick-check.py
  GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json" \
    node port/scripts/singleplayer-worker-runtime-smoke.mjs
done
git diff --check
```

The `26.3` release build also needs the WebAssembly shader toolchain: set
`GAIUS_EMSDK` (an emsdk checkout with the pinned emscripten version) or
`GAIUS_SHADER_TOOLCHAIN_PREBUILT` (a directory with the four pinned modules from
an earlier build); see `docs/releasing.md`. For a quicker `26.3` check without
a full TeaVM link, run the overlay build, the javac-only compile of its source
set, and quick-check's `26.3` domain modules (CI's `version-profiles` job runs
these and the `26.3` patcher smokes):

```sh
export GAIUS_VERSION_PROFILE_PATH=versions/26.3.json
./port/scripts/fetch-version.sh && ./port/scripts/remap-client.sh
bash port/scripts/build-overlays.sh
bash port/scripts/generate-pom.sh
./port/mvnw --file port/target/26.3/generated-pom.xml compile
python3 port/scripts/quick-check.py --domain-modules
```

quick-check judges every profile by the rule set `PROFILE_RULES` names for it
in `port/scripts/quick-check.py`: `1.21.11` has its own legacy-family rules,
`26.2` and `26.3` share the named-family rules, a rule that does not hold for `26.3` is listed in `NOT_APPLICABLE_263`
with the reason and the check that covers its replacement, and the `26.3`
patch assertions live in `port/scripts/quickcheck/profile_263_<domain>.py`. A
profile without rules fails quick-check.

For the Paper plugin (which targets the 1.21.11/JDK-21 profile):

```sh
GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
  ./port/mvnw -B -ntp -f apps/server-plugin/pom.xml test
```

For RelayNode changes, run the smoke suite for all supported profiles; it
verifies the manifest, required token, TCP forwarding, flow control, and
local-tunnel pairing. Use `npm run smoke:public` only against a relay endpoint
you are authorized to test. A passing static check does not replace a Chrome
runtime check: serve `port/web/` locally, open `/dist/<profile>/` in Chrome,
enter a local world, and verify input, terrain rendering, sound, and a short
movement through chunk boundaries. For multiplayer changes, also verify
server-list status and a connection through the intended plugin or relay path.

Record the exact commands and environment in the pull request. Do not report a
check as passing unless it was actually run. For release-sized files, confirm
the checksum of each artifact with:

```sh
for profile in 1.21.11 26.2 26.3; do
  shasum -a 256 "port/web/dist/${profile}/Gaius.html"
done
```

## Pull Requests

Describe the player-visible behavior, the relevant platform or protocol path,
and the exact commands you ran. Include a screenshot or concise runtime result
for rendering, input, audio, or loading changes. Keep unrelated formatting and
generated artifacts out of the diff.

Browser package changes belong with their source commit through Git LFS. Verify
them with `./tools/check-lfs.sh` before opening a pull request. CI artifacts and
GitHub Releases remain useful mirrors, but are not the only copy. Do not move
the generated browser release to a separate repository.

Keep generated release files in LFS and never commit local runtime output,
worlds, credentials, or server access details. If a change adds a new large
artifact, update `.gitattributes` deliberately and verify that the Git index
contains an LFS pointer rather than a normal large blob.

## Reporting Bugs

Use the bug-report form and include the Chrome version, operating system,
whether the problem is single-player or multiplayer, server details where safe,
and the smallest reproducible sequence. Do not paste access tokens, bridge
URLs containing credentials, account session data, or private server addresses.
