# Gaius v0.3.3

Minecraft 1.21.11 is back next to 26.2 and 26.3, plus a smoother main menu, a
new boot screen, a Relays screen and faster startup for every browser client.

## Minecraft 1.21.11 is back

The 1.21.11 client is built and released again, alongside the 26.2 and 26.3
clients. 1.21.11 is the last version before the 26.x line, so it is the one to
use for servers, worlds and mod ports that still target it. All three clients
get the fixes in this release; the Edit Profile screen remains a 26.2 and 26.3
feature.

## The main menu no longer lags

New browser profiles default to "Unlimited" FPS without VSync. The title
screen is cheap to draw (about 1.5 ms per frame), so the game drew hundreds of
frames per second back to back, far more than any display shows. That kept the
GPU and the page busy, and on weaker machines (Chromebooks, laptops on battery)
the screen only updated a few times per second while the game itself reported
hundreds of FPS.

Frames are now held to the display: menus present once per screen refresh
(60 FPS on a 60 Hz display, 144 on 144 Hz, like the vanilla menu limit), and
in a world the game presents at most three frames per refresh. Frames that
already take half a refresh or longer are never held, so in-world FPS on slower
machines is unchanged. {{TITLE}}

## Minecraft-style boot screen

The loading page now looks like Minecraft: a pixel-art GAIUS logo with a
"Browser Edition" ribbon on a dirt background, and a vanilla-style progress
bar. The bar keeps moving while the client starts and loads its resources
(timed from your previous boot) instead of stopping at 82%.

## Faster startup

Two startup hot spots are gone:

- Block face sturdiness was computed eighteen times for every block state while
  the client and the singleplayer server start. It is now computed once per
  collision shape, with identical results for every block state.
- The title screen panorama was copied one pixel at a time. Image rows are now
  copied in bulk.

{{BOOT}}

## Pick your profile on the first visit

New players of the 26.2 and 26.3 clients see the Edit Profile screen the first
time they join, so they can choose a name and a skin right away. It is the same
screen as Profile on the title screen.

## Relays screen

Multiplayer has a new **Relays** button. The Relays screen lists the built-in
relays and your own: add, edit, reorder, disable or remove them, and choose
whether each relay is used for multiplayer, for LAN invites, or both. Relays
given in the page URL (`?relay=` or `?bridge=`) still take priority.

## Verified

{{VERIFIED}}

## Downloads

- `Gaius-1.21.11.html`, `Gaius-26.2.html` and `Gaius-26.3.html`: the portable
  browser clients (`.gz` variants are the same files compressed).
- `Gaius-1.21.11.manifest.json`, `Gaius-26.2.manifest.json`,
  `Gaius-26.3.manifest.json`: build and artifact identity of each client.
- `gaius-server-plugin-0.3.3.jar`: the optional Paper plugin.
- `SHA256SUMS`: checksums for every asset.

All three clients are served from GitHub Pages:

- `https://typethe0ry.github.io/Gaius/Gaius-1.21.11.html`
- `https://typethe0ry.github.io/Gaius/Gaius-26.2.html`
- `https://typethe0ry.github.io/Gaius/Gaius-26.3.html`

No server address, source IP, or private acceptance target is embedded in this
release or its notes.
