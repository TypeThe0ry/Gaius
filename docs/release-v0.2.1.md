# Gaius v0.2.1

This release is the 26.2 browser client line.

## Multiplayer and singleplayer acceptance

- Singleplayer uses the Worker-hosted integrated server and retains the strict
  screenshot terrain gate. A frame is accepted only when every sampled lower
  tile contains visible terrain signal; a sky/HUD-only frame cannot pass.
- Multiplayer acceptance continues to require `ClientLevel`, positive loaded
  chunks, a clean RelayNode session, exact server resource-pack body
  verification, successful reload, and retained non-blank terrain screenshots.
- The tested 26.2 target remains `t40.sjcmc.cn:14803` through
  `wss://ellan.site/tunnel`.

## Open to LAN

The pause menu now exposes **Open to LAN** for a live browser Worker world.
Browser builds cannot bind a raw TCP listening socket, so the action is wired to
the deployment's relay-backed LAN broker (`window.__gaiusLanInviteProvider`).
When the broker is present it returns a short-lived invite URL, copies it when
the browser permits clipboard access, and publishes the invite without sending
access tokens or world data. A deployment without that broker refuses the
action explicitly instead of advertising an unusable `host:port`.

## Skins and server packs

The existing authlib and skin texture paths remain enabled for multiplayer:
profile texture JSON is decoded without reflective Gson construction and skin
URLs are fetched through the trusted texture proxy. Server resource packs are
downloaded through the streaming relay path and must pass exact byte/hash and
reload gates before acceptance.
