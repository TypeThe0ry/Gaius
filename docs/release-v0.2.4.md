# Gaius v0.2.4

This release continues the Minecraft 26.2 browser client line. It replaces the
browser-page player-name form with an in-game profile screen, makes the Fast
graphics preset the default, and fixes terrain chunks that stayed invisible or
black.

## In-game Edit Profile screen

- The HTML player-name card, the title-screen "Change player name" button and
  the name overlay are gone. Player name and skin are now edited in a
  vanilla-style **Edit Profile** screen inside the game: a live 3D skin
  preview, the username field, **Upload Skin...** (64x64 or 64x32 PNG),
  **Arms: Classic / Slim**, and **Use Default Skin**.
- Open it from the **Edit Profile** button in the top-right corner of the
  title screen. On first launch the game starts with a generated `PlayerNNNN`
  name and opens the screen once so you can pick your own.
- Changing the name or skin no longer reloads the page. The running client
  switches identity in place; the new name and skin apply the next time you
  join a world or a server, including worlds opened to LAN.
- Online (access-token) sessions keep their account name; only the skin can be
  changed there.

## Fast graphics by default

- New players start on the **Fast** graphics preset (render distance 8,
  simulation distance 6). Saved options that still carried the old **Fancy**
  default are moved to **Fast** once; choosing **Fancy** again afterwards is
  kept.

## Terrain holes and black chunks

Three ways a terrain section could permanently lose its rebuild are fixed:

- **Inventory screens.** While an inventory, chest or crafting screen is open
  the browser skips drawing the world, but the client kept taking section
  rebuild requests off the queue every frame and then discarding them. Chunks
  that loaded or received their light during that time stayed invisible (sky
  showing through) or kept their unlit, black mesh. Requests that were not
  turned into rebuilds are now put back and handled after the screen closes.
- **Upload timeouts.** A rebuild that waited too long for GPU upload space was
  cancelled without asking for another one. It now re-queues its section.
- **Out-of-order uploads.** Meshes upload over several frames, so an older
  rebuild of a section could finish after a newer one and replace it with stale
  (for example unlit) geometry. Only the newest rebuild of a section is
  installed now, and meshes built for a section's previous position are
  dropped.

The client also audits the visible sections once per second and re-queues any
section whose chunk is ready but which stayed unbuilt while the pipeline was
idle. Counts are available in `window.__gaiusSectionAudit`.

## Downloads

- `Gaius-26.2.html` — the portable browser client (open it in Chrome or
  Chromium). `Gaius-26.2.html.gz` is the same file compressed.
- `Gaius-26.2.manifest.json` — build and artifact identity.
- `gaius-server-plugin-0.2.4.jar` — the optional Paper plugin.
- `SHA256SUMS` — checksums for every asset.

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
