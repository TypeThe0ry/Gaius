# Shader translation corpus (26.3 -> WGSL)

The whole Minecraft 26.3 pipeline corpus through `gaius-shaderxlat`
(`port/native/crates/gaius-shaderxlat`): 216 pipelines (the static
`RenderPipelines`, required and optional, plus every pass of
`assets/minecraft/post_effect/*.json`), 432 stages, 175 distinct SPIR-V
modules per device profile.

```sh
port/native/golden/shaders/run-corpus.sh                 # dump, native, wasm (Node)
port/native/golden/shaders/run-corpus.sh --chrome        # + headless Chrome WebGPU (GPU adapter)
port/native/golden/shaders/run-corpus.sh --swiftshader   # + headless Chrome on Dawn's SwiftShader
port/native/golden/shaders/run-corpus.sh --profile gl    # the WebGL2 device profile (depth -1..1)
```

| Step | Tool | Checks |
|---|---|---|
| dump | `dump-corpus.sh`, `java/.../ShaderXlatCorpusDump.java` | the vanilla frontend (`PipelineBuilder`, `GlslCompiler`, LWJGL shaderc natives) compiles all 216 pipelines with the WebGPU device characteristics; writes `corpus.json` (uniforms, vertex buffers and attributes, color targets, blending, depth state, topology) and `spirv/<sha256>.spv` |
| native | `shaderxlat-corpus` (crate binary) | every module translates and validates in naga; per-phase timings |
| wasm | `xlat-check.mjs --stages wasm` | the wasm build gives byte-identical WGSL in Node; timings |
| chrome | `xlat-check.mjs --stages chrome` | in headless Chrome: the same translations on a synchronous main-thread instance (identical WGSL), `createShaderModule` + `getCompilationInfo` (Tint) for every module, `createRenderPipelineAsync` for every pipeline with the bind group layout from the reflection and the pipeline's real vertex, target, blend, depth and topology state |

The browser shader toolchain already proves the shaderc wasm module reproduces
the native LWJGL SPIR-V byte for byte (`port/scripts/shader-corpus-smoke.mjs`,
stage wasm), so the JVM dump is the corpus the browser sees.

`fixtures/MANIFEST` names five of the modules (one per SPIR-V patch) for the
crate's `cargo test`. They are compiled from Mojang's shader sources, so they
are not committed: the webgpu dump copies them into `fixtures/` (ignored by
git), and without them the tests skip (`GAIUS_SHADERXLAT_REQUIRE_FIXTURES=1`
makes a missing fixture fail; `run-corpus.sh` sets it after a webgpu dump).
Everything else is regenerated from `port/work/26.3` as well.

## Results (2026-10-03, Windows, RTX 4060, Chrome 154, machine shared with builds)

| | Result |
|---|---|
| naga (native) | 175/175 modules, 216/216 pipelines (both profiles) |
| wasm vs native | 175/175 byte-identical (Node and Chrome) |
| Tint (`getCompilationInfo`) | 175/175, 0 errors, 0 warnings (D3D12 adapter and SwiftShader) |
| `createRenderPipelineAsync`, real layout | 216/216 (D3D12 adapter and SwiftShader) |
| without `float32-blendable` | 185/216: the 31 `oit_depth_bounds_*` pipelines blend (`MAX`) into `RGBA32_FLOAT` |

Translation time per module (175 modules; warm = median of 20 runs per module):

| | first calls, all modules | warm mean | warm p50 | warm p95 | warm max | warm, all modules |
|---|---|---|---|---|---|---|
| native x86-64 | 39 ms | 0.150 ms | 0.11 ms | 0.38 ms | 0.71 ms | 26 ms |
| wasm, Node 24 | 66-72 ms (first job 13 ms) | 0.23 ms | 0.17 ms | 0.56 ms | 1.8 ms | 40 ms |
| wasm, Chrome main thread | 62 ms (first job 13 ms) | 0.22 ms | 0.2 ms | 0.5 ms | 0.9 ms | 39 ms |

The first job of a fresh instance pays for compiling the module's functions
(lazy compilation); a boot-time warm-up job removes it from `finishCompile`.
Native phases (mean): SPIR-V patches 35 us, naga parse 53 us, validation 30 us,
WGSL writer 53 us.

| wasm build | raw | gzip -9 | brotli 11 | Node warm mean (best of 4) |
|---|---|---|---|---|
| simd, `opt-level = "s"` (shipped) | 902,937 | 273,606 | 205,719 | 0.226 ms |
| baseline, `opt-level = "s"` | 908,250 | 274,940 | 206,421 | 0.221 ms |
| simd, `opt-level = 3` | 1,025,990 | 309,277 | 231,713 | 0.187 ms |
| simd, `opt-level = "z"` | 844,507 | 257,011 | 195,218 | 0.302 ms |

SIMD makes no difference (naga is scalar code). `"s"` saves 26 KB brotli over
`3` for 0.04 ms per module (7 ms over the whole corpus).

Chrome 154, D3D12 adapter (RTX 4060), fresh profile: `createShaderModule` +
`getCompilationInfo` 0.9 ms mean (150-160 ms for all 175 modules);
`createRenderPipelineAsync` 25-28 ms mean, p95 75-89 ms, max 150 ms,
5.3-5.9 s for all 216, 0.2 ms when repeated in the same session. Second launch
with the same profile (Dawn's disk cache): Tint 33 ms for all modules, pipelines
1.2 ms mean, 0.26 s for all 216. SwiftShader: pipelines 6.2 ms mean, 1.3 s.
Pipeline creation, not translation, is the cold-start cost.
