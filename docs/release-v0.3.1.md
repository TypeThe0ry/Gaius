# Gaius v0.3.1

A performance and reliability update for both browser clients: Minecraft 26.3
and Minecraft 26.2.

## Faster singleplayer chunk loading

New terrain around the player now loads noticeably faster in singleplayer on
both clients. Measured in Chrome with a fixed seed, from "Create New World"
until the full view-distance-8 area around spawn (329 chunks) is loaded and
rendered:

| Client | v0.3.0 | v0.3.1 | Speed-up |
|---|---|---|---|
| Minecraft 26.3 | ~157 s | ~108 s | ~1.45x |
| Minecraft 26.2 | ~121 s | ~96 s | ~1.26x |

Entering a newly created world is also faster (26.3: ~23 s -> ~17 s,
26.2: ~17 s -> ~15 s). Terrain still loads without holes.

The singleplayer server runs in a single browser Worker and was spending most
of its time on overhead rather than on generating terrain:

- The Java-to-JavaScript compiler turned a large part of the server, including
  every terrain-generation routine, into resumable coroutines only because
  they could trigger a class initializer for the first time. The Worker is now
  compiled without that propagation, with class initializers made safe to run
  from ordinary code.
- Biome lookups, made hundreds of thousands of times per second by terrain
  generation and by mob-spawn bookkeeping for every entity on every tick, now
  reuse their per-seed offsets.
- Block tag checks no longer scan every tag of a block on each miss.

## Singleplayer worlds opened from a downloaded file

Chrome does not allow the Origin Private File System on pages opened from
`file://`, so worlds of the downloaded `Gaius-<profile>.html` are stored in the
browser's IndexedDB fallback. That fallback could hold only 32 MiB of saved
regions, and a world explored for roughly a quarter of an hour could no longer
be opened ("Saved regions exceed the IndexedDB compatibility cache budget").
It now holds worlds of up to 256 MiB. Worlds opened from GitHub Pages were not
affected.

## Downloads

- `Gaius-26.2.html` and `Gaius-26.3.html` — the portable browser clients
  (`.gz` variants are the same files compressed).
- `Gaius-26.2.manifest.json`, `Gaius-26.3.manifest.json` — build and artifact
  identity of each client.
- `gaius-server-plugin-0.3.1.jar` — the optional Paper plugin.
- `SHA256SUMS` — checksums for every asset.

Both clients are served from GitHub Pages:

- `https://typethe0ry.github.io/Gaius/Gaius-26.2.html`
- `https://typethe0ry.github.io/Gaius/Gaius-26.3.html`

## Verified

- Singleplayer on both clients: new world, terrain without holes, block break
  and place, Save and Quit, re-entering the world in the same page.
- Open to LAN on Minecraft 26.3: a host renames itself and changes its skin in
  game, opens the world to LAN, a second browser joins through the invite link,
  and both players see each other's names and skins.
- Multiplayer through RelayNode against an unmodified vanilla 26.3 server.

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
