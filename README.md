# Gaius Client

Gaius Client `0.0.1` is an experimental browser port of Minecraft Java
Edition `1.21.11`. It uses the original Java client path with TeaVM and
browser-specific platform overlays. It is not a TypeScript recreation of
Minecraft gameplay.

**Status:** experimental public release. The downloadable client is intended
for evaluation and local single-player use. Browser, server, resource-pack,
world-generation, rendering, and performance compatibility are not guaranteed.
Use a current Chrome or Chromium browser for the primary target experience.

Gaius is independent software and is not affiliated with, endorsed by, or
operated by Mojang Studios, Microsoft, or Minecraft. Minecraft and related
marks are the property of their respective owners. Required attribution and
provenance information remains in the source and documentation. Review the
[Minecraft EULA](https://www.minecraft.net/en-us/eula),
[Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines),
and the [feasibility and licensing notes](docs/feasibility.md) before using or
redistributing generated client files or game assets.

## Screenshots

These are runtime captures of the client UI and gameplay flows. The files are
kept under `docs/images/` so the public README remains reviewable without
opening the generated browser bundle.

![Gaius main menu](docs/images/gaius-main-menu.png)

![Gaius single-player gameplay](docs/images/gaius-singleplayer.png)

![Gaius multiplayer server list](docs/images/gaius-multiplayer.png)

![Gaius player-name screen](docs/images/gaius-player-name.png)

## Download

The `v0.0.1` release page contains the portable browser client and the optional
Paper plugin:

- [Download `Gaius.html`](https://github.com/TypeThe0ry/Gaius/releases/download/v0.0.1/Gaius.html)
- [Download the optional Paper plugin](https://github.com/TypeThe0ry/Gaius/releases/download/v0.0.1/gaius-server-plugin-0.0.1.jar)
- [Download `SHA256SUMS`](https://github.com/TypeThe0ry/Gaius/releases/download/v0.0.1/SHA256SUMS)
- [Open the `v0.0.1` release page](https://github.com/TypeThe0ry/Gaius/releases/tag/v0.0.1)

`Gaius.html` is the frontend-only single-player package. It can be downloaded
and opened locally in Chrome; it does not require a Gaius-hosted web server for
single-player. Multiplayer still requires either a compatible server-side Gaius
plugin or a reachable RelayNode.

## Browser Quick Start

1. Download `Gaius.html` from the release above.
2. Open it in Chrome or Chromium. Allow audio after the first user gesture if
   the browser requests permission.
3. Enter a player name before joining a world or server.
4. Select **Singleplayer** for the browser-local integrated server, or open
   **Multiplayer** and enter a Java server address.

For a source checkout, the normal development launcher is served over HTTP:

```sh
python3 port/scripts/serve-dist.py --host 127.0.0.1 --port 8781
```

Open <http://127.0.0.1:8781/dist/> in Chrome after building the client. Do not
open the generated `/dist/` files from an arbitrary path and assume that every
browser security policy will behave the same as an HTTP origin.

## Current Features

- Minecraft Java Edition `1.21.11` client path compiled to the browser with
  TeaVM.
- WebGL rendering and browser Web Audio integration through the Gaius browser
  platform layer.
- Browser-local single-player with the integrated server running in a Worker.
- IndexedDB-backed local world storage, with client and server communication
  over a `MessageChannel`.
- Portable `Gaius.html` output containing the browser launcher and generated
  runtime payloads.
- Multiplayer status and join routing through a WebSocket-to-TCP RelayNode.
- Optional Paper plugin for a server-side WebSocket endpoint.
- Git LFS tracking for the generated browser release and smoke bundles, so the
  source and runnable release stay in one repository.

## Multiplayer Routing

The browser cannot open a raw TCP connection to an arbitrary Minecraft server.
When a player enters a server address, Gaius uses this order:

1. Probe the optional same-host Gaius/Paper plugin.
2. If it is unavailable, discover configured or public RelayNodes from the
   curated [`relay-nodes.json`](relay-nodes.json) registry and related registry
   entries.
3. Ask eligible nodes about reachability and target affinity, then create an
   isolated temporary WebSocket-to-TCP tunnel for that player.
4. Close that player's tunnel when they leave. A long-running RelayNode may
   continue serving other players; the player's TCP session is not shared.

The RelayNode can translate browser WebSocket traffic to a public Java server,
but it cannot bypass the destination server's version, authentication,
allow-list, proxy, firewall, resource-pack, or online-mode requirements. A
public node must be able to resolve and reach the target, and no arbitrary
server is guaranteed to work. Relay operators are responsible for TLS, origin
policy, destination restrictions, capacity, rate limits, abuse handling, and
privacy. See the [RelayNode guide](docs/relay-nodes.md) and
[`apps/bridge/README.md`](apps/bridge/README.md).

The optional Paper plugin is installed beside a Java server and can remove the
external RelayNode hop for that server. It is not required for the normal
RelayNode route. See the
[`apps/server-plugin/README.md`](apps/server-plugin/README.md).

## Single-Player Architecture

The downloadable client keeps the game frontend and integrated server in the
player's browser:

```text
Chrome tab <-> MessageChannel <-> integrated-server Worker <-> IndexedDB
```

The Worker owns server ticks, world generation, and local-world persistence.
The browser render/input side receives server packets through the channel and
uses the browser platform overlays for graphics, timing, audio, storage, and
network boundaries. This architecture avoids requiring a hosted Gaius backend
for single-player, but it does not remove the CPU and memory limits of the
player's browser tab.

## Repository Map

| Path | Purpose |
| --- | --- |
| `port/` | TeaVM port, browser overlays, bytecode patchers, build scripts, and launcher |
| `port/web/` | Browser launcher, Worker bootstrap, smoke pages, and generated release input |
| `port/web/dist/` | Generated client, Worker, Wasm, compressed payloads, and portable `Gaius.html` |
| `apps/bridge/` | Self-hostable RelayNode, registry process, routing logic, and smoke tests |
| `apps/server-plugin/` | Optional Paper plugin for a server-side Gaius endpoint |
| `packages/` | Checked-in browser protocol and local-world support modules |
| `docs/` | Architecture, feasibility, performance, RelayNode, audit, and release notes |
| `tools/` | Repository validation scripts, including Git LFS and registry checks |
| `relay-nodes.json` | Curated public RelayNode registry bootstrap |

Generated browser artifacts are intentionally kept with the source through Git
LFS. Mojang client JARs, mappings, libraries, assets, local worlds, secrets,
`port/target/`, and Maven build output are not source files and must remain
local.

## Build From Source

Requirements: JDK 21, a current Node.js LTS release, Python 3, `curl`, `jq`,
`unzip`, `shasum`, and Git LFS. The build defaults to a 14 GiB Java heap;
24 GiB or more of physical memory is recommended, with memory-heavy apps closed.

Acquire the local-only Minecraft inputs and build the browser release:

```sh
git lfs install
git lfs pull
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
./port/scripts/build-teavm-release.sh
```

The generated portable client is `port/web/dist/Gaius.html`. Never hand-edit
files in `port/web/dist/`; rebuild them from the launcher and platform source.
More TeaVM details are in [`port/README.md`](port/README.md).

## Tests and Checks

Run checks relevant to the code you change. A full browser-port verification
sequence is:

```sh
./port/scripts/build-platform-smoke.sh
./port/scripts/build-teavm-release.sh
python3 port/scripts/quick-check.py
node port/scripts/singleplayer-worker-runtime-smoke.mjs
git diff --check
```

RelayNode changes should also run:

```sh
cd apps/bridge
npm run smoke
npm run smoke:public
```

Paper plugin changes should run:

```sh
(cd apps/server-plugin && ../../port/mvnw test)
```

Static checks do not replace a Chrome runtime check. For rendering, input,
audio, world generation, and chunk-loading changes, enter a real world, move
through newly loaded terrain, and record the browser and server behavior.

## Known Limitations

- This is an experimental browser port, not a compatibility promise for every
  Chrome version, device, Java server, proxy, mod, plugin, resource pack, or
  network topology.
- Single-player world generation and newly loaded chunks can be CPU-intensive
  in a browser Worker. Large or complex worlds may cause visible frame-time
  spikes or slow initial loading.
- The portable HTML is frontend-only. It cannot start a RelayNode on another
  computer; multiplayer needs a reachable public/private RelayNode or a
  server-side plugin endpoint.
- A RelayNode does not make offline, firewalled, private-network, incompatible,
  or otherwise unreachable Java servers accessible.
- Browser security policies, autoplay restrictions, WebGL limits, memory
  pressure, and tab suspension can affect rendering, sound, latency, and
  stability.
- The generated client and game assets remain subject to provenance,
  entitlement, redistribution, and platform-policy review. This repository is
  independent software and does not grant rights to Mojang-owned material.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request. Keep
browser-visible behavior changes close to their platform source, include the
exact checks you ran, and attach a concise Chrome runtime result for rendering,
input, audio, loading, or networking work. Install Git LFS before changing the
checked-in browser release:

```sh
git lfs install
git lfs pull
./tools/check-lfs.sh
```

The [RelayNode documentation](docs/relay-nodes.md),
[release guide](docs/releasing.md), and
[TeaVM platform-gap notes](docs/teavm-platform-gap.md) describe the current
boundaries and open engineering work.

## Security

Report security issues privately through the repository's
[GitHub Security page](https://github.com/TypeThe0ry/Gaius/security). Do
not publish bridge tokens, private server addresses, session data, or
credentials in issues or pull requests. Relay operators must treat a public
node as an exposed network service and configure TLS, origin checks,
destination policy, rate limits, capacity limits, logging, and abuse contact
information.

## License and Attribution

Gaius is independent software. This repository does not by itself grant a
license to redistribute Mojang/Microsoft client code, mappings, libraries,
assets, or generated game artifacts. Preserve all upstream notices and review
the [Minecraft EULA](https://www.minecraft.net/en-us/eula),
[Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines), and
[official 1.21.11 release information](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11).
For project-level licensing and attribution review, see the
[feasibility notes](docs/feasibility.md) and the
[GitHub repository](https://github.com/TypeThe0ry/Gaius).

## Release Documentation

- [Release guide](docs/releasing.md)
- [RelayNode registry guide](docs/relay-nodes.md)
- [Performance targets](docs/performance-targets.md)
- [Platform-gap notes](docs/teavm-platform-gap.md)
- [EAG 26 audit](docs/eag26-audit.md)
