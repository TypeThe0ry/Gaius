# Gaius Client

**English** | [简体中文](README.zh-CN.md)

Gaius runs the Minecraft Java client in a browser. The game client and the
integrated server Worker stay in the same tab, so single-player does not need
another Gaius server. Multiplayer can use a Gaius Paper plugin or a RelayNode.

Gaius is an independent project and is not affiliated with Mojang Studios,
Microsoft, or Minecraft. See the [feasibility and licensing notes](docs/feasibility.md)
before redistributing generated client files or game assets.

## Screenshots

![Gaius main menu](docs/images/gaius-main-menu.png)

![Gaius single-player gameplay](docs/images/gaius-singleplayer.png)

![Gaius multiplayer server list](docs/images/gaius-multiplayer.png)

![Gaius player-name screen](docs/images/gaius-player-name.png)

## Download

Open the [latest release](https://github.com/TypeThe0ry/Gaius/releases/latest),
then download the HTML file that matches the server you want to join:

- [Minecraft 26.2 client](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-26.2.html)
- [Minecraft 1.21.11 client](https://github.com/TypeThe0ry/Gaius/releases/latest/download/Gaius-1.21.11.html)
- [SHA256 checksums](https://github.com/TypeThe0ry/Gaius/releases/latest/download/SHA256SUMS)
- The optional Paper plugin is on the same release page.

The HTML files are portable single-player clients. Download one, open it in a
current Chrome or Chromium browser, choose a player name, and select
**Singleplayer**. The file contains the browser launcher and its Worker payloads;
there is no separate web server to start for this mode.

For multiplayer, choose **Multiplayer** and enter the Java server address. The
server needs either the optional Gaius Paper plugin or a reachable RelayNode.

## From source

You need Git LFS, Python 3, Node.js LTS, `curl`, `jq`, `unzip`, `shasum`, and the
JDK required by the profile you are building. The build keeps each profile's
Maven state, overlays, and browser output separate.

```sh
git lfs install
git lfs pull

for profile in 1.21.11 26.2; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
done
```

The generated files are written to `port/web/dist/<profile>/`. The portable
client is `Gaius.html`. To serve a built profile locally:

```sh
python3 port/scripts/serve-dist.py --host 127.0.0.1 --port 8781
```

Then open `/dist/26.2/` or `/dist/1.21.11/` in Chrome.

## What is here

- `port/` — the TeaVM port, browser platform code, launcher, patchers, and
  build scripts.
- `apps/bridge/` — the self-hostable WebSocket-to-TCP RelayNode.
- `apps/server-plugin/` — the optional Paper plugin for a server-side endpoint.
- `packages/` — browser protocol and local-world support code.
- `port/web/dist/<profile>/` — generated profile output; it is not hand-edited.
- `docs/` — design notes, release checks, RelayNode notes, and screenshots.
- `tools/` — repository checks and release helpers.

## Multiplayer

The browser cannot open a raw Minecraft TCP socket. Gaius sends the stream over
WebSocket to a Paper endpoint or RelayNode, which opens one TCP connection to
the target server for that player. The relay is a transport bridge, not a
general protocol translator: the client and server still need compatible
Minecraft protocol and authentication settings.

The repository's transport checks use `t40.sjcmc.cn:14803` through
`wss://ellan.site/tunnel`. A relay operator should configure TLS, allowed
origins, destination policy, rate limits, capacity limits, and an abuse contact.
See the [RelayNode guide](docs/relay-nodes.md) and
[`apps/bridge/README.md`](apps/bridge/README.md).

## Checks

Run the checks relevant to the code you changed:

```sh
python3 port/scripts/test-postprocess-index-shell.py
python3 port/scripts/test-index-template.py
node tools/check-release-metadata.mjs
node tools/check-relay-registry.mjs
node tools/check-singleplayer-lifecycle.mjs
git diff --check
```

The static checks do not replace opening the generated HTML in Chrome. For
browser or world-generation changes, enter a world, move through newly loaded
terrain, and check rendering, input, sound, and loading behavior.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md), keep generated output profile-scoped,
and include the commands you ran in a pull request. Do not commit Mojang client
inputs, local worlds, secrets, or build state. For release work, see the
[release guide](docs/releasing.md).

## License and attribution

Preserve upstream notices and review the
[Minecraft EULA](https://www.minecraft.net/en-us/eula) and
[Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines). This
repository does not by itself grant rights to redistribute Mojang/Microsoft
client code, mappings, libraries, assets, or generated game artifacts.

Security issues belong in the repository's
[GitHub Security page](https://github.com/TypeThe0ry/Gaius/security), not in a
public issue.
