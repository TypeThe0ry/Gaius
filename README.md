# Gaius

Gaius is an experimental browser port of the Minecraft Java Edition 1.21.11
client. It starts from the original client bytecode, applies browser-specific
overlays, and compiles the result with TeaVM. It is not a TypeScript rewrite of
Minecraft gameplay and it is not an older client with newer content patched in.

Gaius is independent software. Minecraft is a trademark of Microsoft/Mojang;
this repository is not affiliated with or endorsed by either company.

## What Works Today

- The TeaVM build starts the browser client in Chrome with WebGL rendering and
  Web Audio sound.
- Single-player runs the integrated server in a browser Worker. World data is
  stored locally in IndexedDB, so the render thread stays separate from server
  ticks and world generation.
- `Gaius.html` is a generated, downloadable single-player package. It has no
  Gaius-hosted backend dependency.
- Multiplayer uses a WebSocket-to-TCP bridge because a browser cannot open a
  raw TCP Minecraft connection. A Paper plugin is optional; ordinary Java
  servers do not need it for the normal bridge route.

The project is still experimental. Treat compatibility, large-world generation,
and public-server operation as work that must be tested against the specific
browser and server you intend to use.

## Distribution Boundary

Gaius is one repository: the browser port source, multiplayer bridge, optional
server plugin, and downloadable browser release share the same commit. Large
files under `port/web/dist/` and the generated platform smoke bundles are kept
in this repository through Git LFS. Git stores small pointer objects while Git
LFS transfers the corresponding release payloads.

The repository still excludes Mojang client JARs, mappings, libraries, assets,
and local worlds. Fetch those upstream inputs locally with the supplied scripts.

Do not redistribute generated game assets or client artifacts unless you have
the rights to do so. See [the release guide](docs/releasing.md) before creating
a public download.

## Architecture

| Component | Responsibility |
| --- | --- |
| `port/` | TeaVM port, Java overlays, bytecode patchers, build scripts, browser launcher |
| `port/web/dist/` | Generated browser client, Worker, Wasm, and portable HTML output |
| `apps/bridge/` | Optional backpressured WebSocket-to-TCP relay for Java multiplayer |
| `apps/server-plugin/` | Optional Paper plugin that supplies the relay endpoint next to a server |
| `packages/` | Checked-in browser protocol and local-world support modules |
| `docs/` | Feasibility, platform-gap, audit, and release documentation |

Single-player traffic stays in the browser:

```text
Chrome client <-> MessageChannel <-> integrated-server Worker <-> IndexedDB
```

Multiplayer traffic needs a bridge:

```text
Chrome client <-> WebSocket bridge or optional Paper plugin <-> Java server TCP
```

## Quick Start

Requirements: JDK 21, a current Node.js LTS release, Python 3, `curl`, `jq`,
`unzip`, and `shasum`. The full TeaVM build can use up to 20 GiB of Java heap;
32 GiB of physical memory is recommended.

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
python3 port/scripts/serve-dist.py --host 127.0.0.1 --port 8781
```

Open [http://127.0.0.1:8781/dist/](http://127.0.0.1:8781/dist/) in Chrome.
The portable package is generated at `port/web/dist/Gaius.html`; build it first,
then open it locally in Chrome to exercise the offline single-player route.

For build details and the TeaVM platform model, read
[port/README.md](port/README.md).

## Verification

Run the release build before the checks that inspect generated output:

```sh
./port/scripts/build-platform-smoke.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
node port/scripts/singleplayer-worker-runtime-smoke.mjs
```

The practical acceptance check is also visual: open the generated client in
Chrome, enter a single-player world, move through newly loaded terrain, and
confirm rendered terrain, sound, input, and the block-selection outline.

## Multiplayer Bridge

Start the local bridge when testing an ordinary Java server:

```sh
node apps/bridge/dist/main.js
```

Configure public relay nodes with repeated `bridge` query parameters or
`gaius.bridgeNodes` local storage. A relay operator must restrict destinations,
origins, connection counts, and access tokens. See
[apps/bridge/README.md](apps/bridge/README.md).

The optional Paper plugin removes the external relay hop for a server operator;
it is not a requirement for normal Java-server compatibility. See
[apps/server-plugin/README.md](apps/server-plugin/README.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. In
particular, do not commit fetched Minecraft inputs, `port/target/`, Maven
`target/` directories, or local caches. The browser package is intentionally
kept with its source through Git LFS, so install Git LFS before cloning or
building a release:

```sh
git lfs install
git lfs pull
./tools/check-lfs.sh
```

## Further Reading

- [Port build notes](port/README.md)
- [Platform-gap notes](docs/teavm-platform-gap.md)
- [EAG 26 audit](docs/eag26-audit.md)
- [Release guide](docs/releasing.md)
