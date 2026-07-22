# Contributing to Gaius

Gaius is a browser-porting project, so a useful contribution normally touches
the real Java client path, the TeaVM browser platform, the local server Worker,
or the bridge boundary. Keep a change small enough that its browser behavior
can be verified.

## Development Setup

Install JDK 21, a current Node.js LTS release, Python 3, `curl`, `jq`, `unzip`,
and `shasum`. Then obtain the local-only game inputs and perform the first
remap:

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

The full client compilation can use up to 20 GiB of Java heap. Use a machine
with at least 32 GiB of physical memory for reliable full-release builds.

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

For bridge changes, run the focused smoke script documented in
`apps/bridge/README.md`. A passing static check does not replace a Chrome
runtime check: enter a local world and verify input, terrain rendering, sound,
and a short movement through chunk boundaries.

## Pull Requests

Describe the player-visible behavior, the relevant platform or protocol path,
and the exact commands you ran. Include a screenshot or concise runtime result
for rendering, input, audio, or loading changes. Keep unrelated formatting and
generated artifacts out of the diff.

Browser package changes belong with their source commit through Git LFS. Verify
them with `git lfs ls-files` before opening a pull request. CI artifacts and
GitHub Releases remain useful mirrors, but are not the only copy.

## Reporting Bugs

Use the bug-report form and include the Chrome version, operating system,
whether the problem is single-player or multiplayer, server details where safe,
and the smallest reproducible sequence. Do not paste access tokens, bridge
URLs containing credentials, account session data, or private server addresses.
