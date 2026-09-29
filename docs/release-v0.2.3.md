# Gaius v0.2.3

This release continues the Minecraft 26.2 browser client line. It focuses on
how long a new world takes to become playable. Visual quality is unchanged:
the `Fancy` preset, render distance 8, simulation distance 6, and
`mipmapLevels` 4 all stay in force.

## Faster world entry

Three independent stalls between "create world" and "first terrain" are
removed:

- **`Fancy` preset distance contract.** The `Fancy` graphics preset was forcing
  the vanilla 16 / 12 render / simulation distances instead of the browser
  contract's 8 / 6. That multiplied spawn-area worldgen and first-frame render
  work far beyond the target quality. `Fancy` is now pinned to 8 / 6 (with
  `mipmapLevels` 4) — the intended quality floor, not a reduction below it.
- **Non-blocking spawn-entity wait.** The integrated server no longer loads
  spawn entities *inside* the finish-configuration handler, where the wait
  blocked the very step the client was waiting on. The spawn-entity gate now
  drains pending entity loads itself and stays live even when the level is not
  ticking, so the client reaches PLAY without a multi-second hang.
- **Worldgen dispatcher hops.** The worldgen dispatcher paid a clamped browser
  timer hop (a minimum ~4 ms delay) for every runnable it scheduled. Priority
  0–2 runnables are now drained inline within a bounded budget, and the
  remaining hop uses a `MessageChannel` instead of the clamped timer, so chunk
  generation is no longer throttled by timer clamping.

## Hosted multiplayer relay

The public relay (`wss://ellan.site/tunnel`) accepts the GitHub Pages browser
origin again, so multiplayer from the hosted build connects and loads resource
packs through it. This was a relay-side configuration fix; the client's relay
registry is unchanged and still lists only that node. The `file://` build is
unaffected.

## Downloads

- `Gaius-26.2.html` — the portable browser client (open it in Chrome or
  Chromium). `Gaius-26.2.html.gz` is the same file compressed.
- `Gaius-26.2.manifest.json` — build and artifact identity.
- `gaius-server-plugin-0.2.3.jar` — the optional Paper plugin.
- `SHA256SUMS` — checksums for every asset.

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
