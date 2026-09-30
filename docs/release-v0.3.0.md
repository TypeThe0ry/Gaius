# Gaius v0.3.0

This release adds a browser client for Minecraft 26.3 next to the existing
Minecraft 26.2 client. Both are published as portable single-file HTML
downloads, and both are served from GitHub Pages.

## Minecraft 26.3 support

- `Gaius-26.3.html` is a browser client for Minecraft Java 26.3 (protocol
  `777`, world version `5023`). It boots to the title screen, opens Options,
  Multiplayer, Direct Connect and the Edit Profile screen, and joins servers
  through the same Gaius Paper plugin or RelayNode path as the 26.2 client.
- Minecraft 26.3 moved its window and input layer from GLFW to SDL3 and its
  renderer to the `renderpearl` API, whose OpenGL backend compiles every shader
  through shaderc and SPIRV-Cross. The 26.3 client ships those two tools as
  WebAssembly modules (built from the exact revisions of the LWJGL 3.4.3
  natives) together with a loader that starts them before the game. The
  portable HTML embeds the modules, so it still works offline from a plain
  `file://` open; the static launcher loads them from
  `gaius-shader-toolchain.js` next to `index.html`.
- Compiled shaders are cached per profile in the browser's IndexedDB
  (`gaius-shader-cache-v1-26.3`); a cache miss compiles in the page.
- World storage of the 26.3 client is isolated from the 26.2 client
  (`gaius-fs-v2-26.3`), as every profile's storage has been since v0.2.x.
  Worlds do not migrate between the two clients.

## Two downloads

Pick the HTML file whose Minecraft version matches the server you want to
join; for singleplayer either works. `Gaius-26.2.html` and `Gaius-26.3.html`
are independent files with their own manifests and SHA256SUMS records, and
GitHub Pages now serves both:

- `https://typethe0ry.github.io/Gaius/Gaius-26.2.html`
- `https://typethe0ry.github.io/Gaius/Gaius-26.3.html`

Minecraft 1.21.11 remains retired: it is not built, not released and not
served.

## What changed for Minecraft 26.2 users

Nothing functional. The 26.2 client is built from the same client sources,
patches and launcher as v0.2.4; the release tooling changes of this version
(the shader toolchain embedding, the second Pages file, the version bump) do
not touch the 26.2 page. A 26.2 portable HTML rebuilt from the v0.2.4 inputs
with the v0.3.0 builder is byte-identical to the v0.2.4 download.

## Downloads

- `Gaius-26.2.html` — the portable Minecraft 26.2 browser client (open it in
  Chrome or Chromium). `Gaius-26.2.html.gz` is the same file compressed.
- `Gaius-26.3.html` — the portable Minecraft 26.3 browser client.
  `Gaius-26.3.html.gz` is the same file compressed.
- `Gaius-26.2.manifest.json`, `Gaius-26.3.manifest.json` — build and artifact
  identity of each client. The 26.3 manifest also records the embedded shader
  toolchain (`shaderToolchain`: loader, modules, pinned sources, identity).
- `gaius-server-plugin-0.3.0.jar` — the optional Paper plugin.
- `SHA256SUMS` — checksums for every asset.

## Known limitations

- The 26.3 client is validated with Google Chrome and Chromium; the shader
  toolchain needs WebAssembly and `DecompressionStream`.
- Pipelines that need the WIREFRAME fill mode
  (`minecraft:pipeline/wireframe*`) are reported as unsupported by the WebGL2
  device and skipped, as they are on the 26.2 client.

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
