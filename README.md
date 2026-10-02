# Gaius Client

**English** | [简体中文](README.zh-CN.md)

Gaius runs the Minecraft Java Edition client in a web browser. It is not a
reimplementation: the official client bytecode is compiled to JavaScript with
TeaVM, and the parts of Java that a browser cannot provide (windowing, OpenGL,
OpenAL, sockets, files) are replaced with browser implementations.

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** Parts of this project were written with AI coding
> assistants. Read the [AI usage](#ai-usage) and [Disclaimer](#disclaimer)
> sections before using or redistributing it.

- **Single-player in one tab.** The integrated server runs in a Web Worker next
  to the client, and worlds are saved in browser storage. The release is a
  single HTML file; no Gaius server is needed.
- **Multiplayer on ordinary Java servers.** The browser reaches a server
  through a WebSocket-to-TCP RelayNode or the optional Gaius Paper plugin. The
  server itself stays unmodified.
- **Three game versions.** Minecraft 1.21.11, 26.2 and 26.3, each built as a
  separate client.

## Screenshots

![Gaius main menu](docs/images/gaius-main-menu.png)

![Gaius single-player gameplay](docs/images/gaius-singleplayer.png)

![Gaius multiplayer server list](docs/images/gaius-multiplayer.png)

![Gaius Edit Profile screen](docs/images/gaius-player-name.png)

## Download and play

Download the HTML file whose Minecraft version matches the server you want to
join, from the [latest release](https://github.com/TypeThe0ry/Gaius/releases/latest):

| File                                                                                                     | Minecraft version                                                                  |
| -------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| [`Gaius-1.21.11.html`](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-1.21.11.html) | 1.21.11, the last pre-26.x release, for servers and mod ports that still target it |
| [`Gaius-26.2.html`](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-26.2.html)       | 26.2                                                                               |
| [`Gaius-26.3.html`](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-26.3.html)       | 26.3                                                                               |

[`SHA256SUMS`](https://github.com/TypeThe0ry/Gaius/releases/latest/download/SHA256SUMS)
lists the checksums. The optional Paper plugin is on the same release page.

Open the file in a current Chrome or Chromium browser and choose
**Singleplayer**. The file contains the launcher and its Worker payloads, so
there is nothing else to install or start.

In the 26.2 and 26.3 clients, the **Edit Profile** screen opens on first
launch. Pick a player name and, if you like, upload a 64x64 PNG skin. You can
reopen it at any time from the **Edit Profile** button on the title screen, and
changes apply without reloading the page. The 1.21.11 client has no in-game
profile editor.

### Joining a server

1. Open the HTML file whose version matches the server.
2. On the title screen, choose **Multiplayer**.
3. Choose **Add Server** or **Direct Connection**, enter the server's normal
   Java address (for example `example.net:25565`), and choose **Join Server**.

Enter the address in Minecraft's server screen, not in the browser's address
bar. A browser cannot open a raw TCP connection, so the server must run the
Gaius Paper plugin or be reachable through a configured RelayNode. If the
screen stays on **Waiting for Server**, check the RelayNode URL and the target
`host:port`, and make sure the client version matches the server.

## How it works

```text
Browser tab
  Minecraft client (TeaVM → JavaScript)
    WebGL 2 · Web Audio · keyboard and mouse
        │
        ├── MessageChannel ── integrated server (Web Worker)
        │                     worlds in IndexedDB / OPFS
        │
        └── WebSocket ── Paper plugin or RelayNode ── TCP ── Java server
```

- **Client.** Gaius fetches the official client JAR locally at build time,
  applies bytecode patches and replacement classes, and compiles the result
  with TeaVM. Game logic, rendering code and the network protocol all come
  from Mojang's client; Gaius does not maintain a separate world simulation.
- **Single-player.** The official integrated server is compiled into its own
  Web Worker. Client and server talk over a paired `MessageChannel`, and world
  data is stored in IndexedDB and the Origin Private File System.
- **Multiplayer.** The client sends the Minecraft byte stream over WebSocket.
  A RelayNode or the Paper plugin opens one TCP connection to the target
  server for each player. It is a transport bridge, not a protocol
  translator, so client and server versions and authentication settings still
  have to match.

The [port README](port/README.md) describes the build stages and the runtime
in more detail.

## Tech stack

| Area                     | Technologies                                                                                                                                                                                                                           |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Game code                | Official Minecraft Java Edition client and server, downloaded locally at build time and never committed. 1.21.11 is remapped with Mojang's official mappings using NeoForged AutoRenamingTool; 26.2 and 26.3 ship with readable names. |
| Java → browser compiler | [TeaVM](https://teavm.org/) 0.15 with JavaScript output, a patched TeaVM core, and a TeaVM class library overlay                                                                                                                        |
| Bytecode patching        | [ASM](https://asm.ow2.io/)-based patchers in `port/tools/`, replacement classes in `port/overrides/`                                                                                                                                |
| Platform layer           | Browser implementations of LWJGL (GLFW, OpenGL, OpenAL, STB, shaderc, SPIRV-Cross), Netty transport, and the logging libraries, written in Java against TeaVM's JSO interop                                                            |
| Graphics and audio       | WebGL 2, Web Audio API                                                                                                                                                                                                                 |
| Single-player runtime    | Web Workers,`MessageChannel`, IndexedDB, Origin Private File System                                                                                                                                                                  |
| WebAssembly              | C hot-path module (clang → wasm32) with a JavaScript fallback; shaderc and SPIRV-Cross built with Emscripten for the 26.3 shader pipeline                                                                                             |
| Launcher and packaging   | HTML/CSS/JavaScript launcher; Python builds the single-file portable HTML; gzip payloads decoded with`DecompressionStream`; gzip and Brotli variants for HTTP serving                                                                |
| RelayNode                | Node.js 22+,[`ws`](https://github.com/websockets/ws); Docker, nginx/Caddy and systemd deployment examples; a Cloudflare Worker origin proxy                                                                                           |
| Paper plugin             | Java 21, Paper API 1.21.11, Java-WebSocket, Gson, JUnit 5                                                                                                                                                                              |
| Shared packages          | JavaScript modules with TypeScript declarations in`packages/`                                                                                                                                                                        |
| Build                    | Maven 3.9 through the bundled`port/mvnw`, JDK 21 and JDK 25, Bash, Python 3, Node.js, Git LFS                                                                                                                                        |
| Testing and CI           | Node.js smoke tests, Python checks, Chrome DevTools Protocol browser drivers, GitHub Actions, GitHub Pages                                                                                                                             |

## Building from source

You need Git LFS, Python 3, Node.js 22 or newer, `curl`, `jq`, `unzip`,
`shasum`, and the JDK for the profile you build: JDK 25 or newer for 26.2 and
26.3, JDK 21 or newer for 1.21.11. A full client build uses a 14 GiB Java
heap, so a machine with at least 24 GiB of RAM is recommended.

```sh
git lfs install
git lfs pull

for profile in 1.21.11 26.2 26.3; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
done
```

Each profile keeps its own Maven state, overlays and output. The result goes to
`port/web/dist/<profile>/`, and the portable client is `Gaius.html` in that
directory. The 26.3 build also needs the WebAssembly shader toolchain; see
[`CONTRIBUTING.md`](CONTRIBUTING.md) and the [release guide](docs/releasing.md).

To serve the built profiles locally:

```sh
python3 port/scripts/serve-dist.py --host 127.0.0.1 --port 8781
```

Then open `/dist/1.21.11/`, `/dist/26.2/` or `/dist/26.3/` in Chrome.

## Repository layout

| Path                         | Contents                                                                |
| ---------------------------- | ----------------------------------------------------------------------- |
| `port/`                    | TeaVM port: browser platform code, launcher, patchers and build scripts |
| `port/web/dist/<profile>/` | Generated output for each profile; do not edit by hand                  |
| `apps/bridge/`             | Self-hostable WebSocket-to-TCP RelayNode                                |
| `apps/server-plugin/`      | Optional Paper plugin that provides a server-side endpoint              |
| `packages/`                | Browser protocol and local-world support code                           |
| `docs/`                    | Design notes, release notes and checks, RelayNode guide, screenshots    |
| `tools/`                   | Repository checks and release helpers                                   |

## Running a RelayNode

A RelayNode lets browsers reach Java servers that do not run the Gaius
plugin. Anyone can run one; it does not host worlds or run the game. A public
node should configure TLS, allowed origins, a destination policy, rate and
capacity limits, and an abuse contact.

The repository's transport checks read authorized targets and RelayNode URLs
from a private acceptance environment. Target hosts and origin IPs are never
committed.

See the [RelayNode guide](docs/relay-nodes.md) and
[`apps/bridge/README.md`](apps/bridge/README.md).

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. It lists the setup and the
checks to run for each area. Keep generated output in its profile directory
and list the commands you ran in the pull request. Do not commit Mojang client
files, local worlds, secrets or build state. Release work follows the
[release guide](docs/releasing.md).

## AI usage

Gaius is built with substantial help from AI coding assistants, mainly
Anthropic's Claude (through Claude Code) and OpenAI's Codex. (Also deepseek via codex)They have been
used to:

- write and refactor code, including the browser platform layer, bytecode
  patchers, RelayNode and Paper plugin;
- write build, test and release scripts;
- investigate bugs and performance problems;
- review pull requests;
- write documentation, including this README.

## Disclaimer

- **Unofficial.** NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR
  ASSOCIATED WITH MOJANG OR MICROSOFT. Minecraft, its code and its assets
  belong to Mojang Studios and Microsoft.
- **Experimental, no warranty.** Gaius is provided "as is", without warranty
  of any kind; see the [license](LICENSE). It can crash, behave differently
  from the desktop game, or lose single-player worlds. Clearing site data,
  private windows and browser storage eviction can delete saved worlds. Do not
  rely on it for worlds you cannot afford to lose.
- **AI-generated code.** Code written with AI assistance can contain mistakes
  that testing has not caught, including security problems. Review the code
  before you run a public RelayNode or install the Paper plugin on a server
  you care about.
- **Your responsibility.** You are responsible for following the
  [Minecraft EULA](https://www.minecraft.net/en-us/eula), the
  [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines), and the
  rules of any server you join. Some servers do not allow unofficial clients.
  Relay operators are responsible for the traffic their nodes carry.
- **Redistribution.** The release HTML files contain compiled Mojang code and
  game assets. This repository does not grant any right to redistribute
  Mojang or Microsoft client code, mappings, libraries, assets, or generated
  game files. Read the [feasibility and licensing notes](docs/feasibility.md)
  before redistributing them.
- **Not legal advice.** Nothing in this repository is legal advice.

## License

Gaius's own source code is released under the [MIT License](LICENSE). The
license does not cover Minecraft code, mappings, libraries or assets, which
remain the property of Mojang Studios and Microsoft. Preserve upstream notices
when you reuse code.

Report security issues through the repository's
[GitHub Security page](https://github.com/TypeThe0ry/Gaius/security), not in a
public issue.
