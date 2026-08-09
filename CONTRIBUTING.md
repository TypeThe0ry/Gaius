# Contributing to Gaius

Gaius is a browser-porting project, so a useful contribution normally touches
the real Java client path, the TeaVM browser platform, the local server Worker,
or the bridge boundary. Keep a change small enough that its browser behavior
can be verified.

## Development Setup

Install Git LFS, JDK 21, Node.js 22 or newer, Python 3, `curl`, `jq`, `unzip`,
and `shasum`. After cloning, fetch the large checked-in release objects before
working with generated browser files:

```sh
git lfs install
git lfs pull
```

The game inputs are local-only and are not supplied by this repository. Obtain
them through the supported local workflow, then perform the first remap:

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

The full client compilation defaults to a 14 GiB Java heap. Use a machine with
at least 24 GiB of physical memory and close memory-heavy applications for
reliable full-release builds. Override `MAVEN_OPTS` only when the host has
enough headroom.

For a source checkout that already has the local inputs, the normal browser
release commands are:

```sh
./port/scripts/build-platform-smoke.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
```

The build produces the runnable package under `port/web/dist/`. Do not edit
generated files there by hand; change their source and rebuild instead.

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
./port/scripts/build-platform-smoke.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
node port/scripts/singleplayer-worker-runtime-smoke.mjs
git diff --check
```

For the Paper plugin:

```sh
(cd apps/server-plugin && ../../port/mvnw test)
```

For RelayNode changes, run `cd apps/bridge && npm run smoke`; it verifies the
manifest, required token, TCP forwarding, flow control, and local-tunnel
pairing. Use `npm run smoke:public` only against a relay endpoint you are
authorized to test. A passing static check does not replace a Chrome runtime
check: serve `port/web/` locally, open the `/dist/` launcher in Chrome, enter a
local world, and verify input, terrain rendering, sound, and a short movement
through chunk boundaries. For multiplayer changes, also verify server-list
status and a connection through the intended plugin or relay path.

Record the exact commands and environment in the pull request. Do not report a
check as passing unless it was actually run. For release-sized files, confirm
the checksum of each artifact with:

```sh
shasum -a 256 port/web/dist/Gaius.html
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
