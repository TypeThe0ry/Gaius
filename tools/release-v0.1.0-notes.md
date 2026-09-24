# Gaius Client v0.1.0 — final `main` rebuild

This clobber refresh replaces the prior `v0.1.0` assets with portable browser clients rebuilt from the final `main` source for Minecraft **1.21.11** and **26.2**. The existing `v0.1.0` Git tag is retained unchanged.

## Included fixes

- Faster single-player resource-pack startup and bounded new-chunk material/render scheduling.
- Bounded vanilla-resource caching and cooperative browser work queues to reduce long main-thread stalls.
- Rebuilt single-player server Worker, Worker bootstrap, WASM hot path, RelayNode registry, and embedded portable assets for both profiles.
- RelayNode target attestation, resource-pack download handling, bounded frame draining, reconnect handling, multi-client isolation, and close-time cleanup.
- Rebuilt Paper server plugin `gaius-server-plugin-0.1.0.jar`.

## Acceptance and provenance

Both compiled `Gaius.html` files passed direct `file://` execution in isolated Chrome driven through CDP. The single-player gates require an active level, ready WASM hot path, working local storage and IndexedDB, no runtime exceptions, no sibling file requests, and evidence hashes tied to the exact uploaded HTML bytes.

Multiplayer acceptance requires the strict terrain gate through `wss://ellan.site/tunnel` against the profile-specific targets recorded in `release.manifest.json`: `ClientLevel`, positive loaded chunks, successful RelayNode target attestation, zero bridge/runtime errors, the actual server resource pack downloaded through the relay and verified against its recorded hash, successful resource reload, and a non-blank real terrain screenshot.

`release.manifest.json` records source and artifact identities. `SHA256SUMS` covers the other seven assets. Publication performs a fresh download and verifies the exact eight-asset set before GitHub Pages is dispatched.

## GitHub Pages

- Minecraft 1.21.11: https://typethe0ry.github.io/Gaius/Gaius-1.21.11.html
- Minecraft 26.2: https://typethe0ry.github.io/Gaius/Gaius-26.2.html
