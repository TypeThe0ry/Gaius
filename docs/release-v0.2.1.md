# Gaius v0.2.1

This release is the 26.2 browser client line.

## Multiplayer and singleplayer acceptance

- Singleplayer uses the Worker-hosted integrated server and retains the strict
  screenshot terrain gate. A frame is accepted only when every sampled lower
  tile contains visible terrain signal; a sky/HUD-only frame cannot pass.
- Multiplayer acceptance continues to require `ClientLevel`, positive loaded
  chunks, a clean RelayNode session, exact server resource-pack body
  verification, successful reload, and retained non-blank terrain screenshots.
- The 26.2 target is supplied only through the private acceptance environment;
  no public target or source IP is embedded in the release documentation.

## Open to LAN

The pause menu now exposes **Open to LAN** for a live browser Worker world.
Browser builds cannot bind a raw TCP listening socket, so each invite creates a
fresh server-side Netty channel and a matching `client-<session>.gaius-local`
relay tunnel. The built-in launcher provider emits a URL containing that
session; deployments can replace it with `window.__gaiusLanInviteProvider` to
add short-lived invite storage or an authenticated broker. A joining browser
only treats the hostname as local when it owns the matching Worker generation;
otherwise it correctly goes through RelayNode. No access token or world bytes
are placed in the invite URL.

## Skins and server packs

The existing authlib and skin texture paths remain enabled for multiplayer:
profile texture JSON is decoded without reflective Gson construction and skin
URLs are fetched through the trusted texture proxy. Online profiles therefore
retain their custom skin on both the host and joining browser. Offline players
can upload a 64×64 PNG skin in User Profile; it is stored locally, carried to
LAN peers through the relay, and rendered for every player in the world. Each
offline player gets the vanilla name-derived UUID, so a joiner never collides
with the host. Server resource packs are
downloaded through the streaming relay path and must pass exact byte/hash and
reload gates before acceptance.

Two browsers on one LAN world, each with its own uploaded skin (host magenta,
joiner green):

| Host sees the joiner | Joiner sees the host |
| --- | --- |
| ![Host view of the joining player's green skin](images/gaius-lan-skins-host.png) | ![Joiner view of the host's magenta skin](images/gaius-lan-skins-joiner.png) |
