# Gaius v0.4.0 (release notes draft)

This draft describes the v0.4.0 release package. It is intentionally kept
separate from the release operation: no build, publication, tag, or upload is
performed by this document.

## Architecture

Each supported profile (Minecraft 26.3, 26.2 and 1.21.11) has a WebAssembly GC
client and singleplayer server compiled from the profile metadata. Hot paths run
as Rust WebAssembly kernels in Workers for noise, meshing, lighting, world
generation and shader translation. Kernel job headers and golden fixtures are
profile-scoped; when a kernel cannot prove bit-exact parity, that operation stays
on the vanilla Java implementation.

The page contains a frozen JavaScript client and server fallback for every
profile. The launcher selects WasmGC when WebAssembly GC, `try_table`, JSPI and
the gzip inflation path are available. It selects WebGL2 when WebGPU is absent
or initialization fails, and selects the JavaScript fallback when a WasmGC
prerequisite is unavailable.

## Rendering

WebGL2 remains available for every profile. WebGPU is selected when the adapter,
shader translator and required browser features initialize successfully. The
WebGPU path is optional and does not change the JavaScript fallback.

## Profile status

| Profile | Client | Singleplayer server | Fallback |
| --- | --- | --- | --- |
| 26.3 | WasmGC | WasmGC | Frozen JS |
| 26.2 | WasmGC | WasmGC | Frozen JS |
| 1.21.11 | WasmGC | WasmGC | Frozen JS |

The Pages tree is profile-scoped. Each profile publishes a gzip-only WasmGC
site together with its frozen JavaScript fallback and profile manifest.

## Acceptance covered by the release checklist

- `file://` title screen and profile selection for all three profiles;
- five-of-five singleplayer checks per profile, including world creation,
  terrain, interaction, save and re-entry;
- relay multiplayer using the profile's protocol;
- storage migration between WasmGC and JavaScript runtime choices;
- deliberate fallback injection and WebGPU-to-WebGL2 fallback.

## Known limitations

- WebGPU requires a browser adapter and the shipped shader translation assets;
  its absence selects WebGL2.
- Browsers without WebAssembly GC, JSPI, `try_table` or
  `DecompressionStream` use the frozen JavaScript fallback.
- The gzip-only Pages modules require the Service Worker or the page's
  inflation path before `compileStreaming` can consume them.
- Existing worlds retain their stored chunks and are not converted between
  profile storage namespaces.
- The 1.21.11 Wasm profile is retained as a frozen compatibility profile when
  the active maintenance window advances.

## Downloads and checksums

The release assets are `Gaius-site-1.21.11.tar.gz`,
`Gaius-site-26.2.tar.gz`, `Gaius-site-26.3.tar.gz`, the three
`Gaius-<profile>.html` portable fallbacks, the three profile manifests,
`Gaius-pages-matrix.json`, the optional server plugin and `SHA256SUMS`.
Every published asset has one lowercase SHA-256 record; raw WasmGC modules and
debug symbols remain outside the Pages archive.
