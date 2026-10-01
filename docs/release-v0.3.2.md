# Gaius v0.3.2

A stability and performance update for both browser clients: Minecraft 26.3
and Minecraft 26.2.

## Singleplayer no longer crashes with "Maximum call stack size exceeded"

While a lot of terrain was loading, the singleplayer server could stop with
`RangeError: Maximum call stack size exceeded` and the game showed
"Connection Lost". Inside the browser, the server's lighting and
world-generation task queues ran their pending tasks by calling back into
themselves once per task. A burst of queued tasks therefore used one more
level of the JavaScript stack per task until the stack ran out. The queues now
run their backlog in a loop. Tasks run in the same order, and the same errors
are reported, without the stack growing. Both clients are fixed.

## Faster chunk loading on Minecraft 26.3

Every entity, on every server tick, re-merged the biome and dimension mob
spawn settings, which are two values that never change. That cost about 5% of
the singleplayer server while terrain loads. The merged result is now reused.
Measured in Chrome with a fixed seed, from "Create New World" until the full
view-distance-8 area around spawn (329 chunks) is loaded and rendered:

| Client | v0.3.1 | v0.3.2 |
|---|---|---|
| Minecraft 26.3 | ~108 s | ~98 s |

Terrain still loads without holes.

## Downloads

- `Gaius-26.2.html` and `Gaius-26.3.html`: the portable browser clients
  (`.gz` variants are the same files compressed).
- `Gaius-26.2.manifest.json`, `Gaius-26.3.manifest.json`: build and artifact
  identity of each client.
- `gaius-server-plugin-0.3.2.jar`: the optional Paper plugin.
- `SHA256SUMS`: checksums for every asset.

Both clients are served from GitHub Pages:

- `https://typethe0ry.github.io/Gaius/Gaius-26.2.html`
- `https://typethe0ry.github.io/Gaius/Gaius-26.3.html`

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
