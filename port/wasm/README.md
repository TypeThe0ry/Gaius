# Gaius Wasm hot-path modules

This directory is for performance-critical batch helpers used by the browser
port while the official Minecraft 1.21.11 Java client remains compiled through
TeaVM.

Rules for adding Wasm work:

- Keep Java/TeaVM as the authoritative client implementation.
- Only move batch-oriented pure compute paths across the JS/Wasm boundary.
- Never call Wasm once per block, item, vertex, or entity from Java.
- Always keep a JavaScript fallback until the Wasm path is proven stable.
- Prefer flat typed arrays, numeric enums, and explicit capacities over object
  graphs.

Current module:

- `hotpath/gaius_hotpath.c`
  - `gaius_shift_indices(...)`: shifts WebGL element indices for
    `drawElementsBaseVertex` fallback in bulk.
  - `gaius_repack_interleaved(...)`: repacks misaligned/interleaved vertex
    attributes into a browser-safe aligned buffer in bulk.

Build:

```sh
./port/scripts/build-wasm-hotpath.sh
```

Output:

- `port/web/dist/gaius-hotpath.wasm`
