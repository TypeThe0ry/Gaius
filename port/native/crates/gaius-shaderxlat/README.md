# gaius-shaderxlat

SPIR-V to WGSL for the WebGPU backend of the renderpearl SPI (v0.4.0, D11).

The input is exactly what a renderpearl backend receives for each stage of a
pipeline (`BackendRenderPipeline.CreateInfo.shaders()`): the vanilla
frontend's shaderc output (Vulkan 1.2 / SPIR-V 1.5, `-O0`, debug info), every
resource in descriptor set 0 at its index in `CreateInfo.uniforms()`. The WebGPU
device reports `isZZeroToOne = true`, so the frontend compiles with
`RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` and the depth range needs no rewrite.

```
SPIR-V -> strip debug info -> drop gl_PointSize writes -> texel buffers to storage buffers
       -> split combined image samplers -> split output arrays      (src/passes, word level)
       -> naga 30.0.1 spv-in -> WGSL binding layout -> naga validation -> naga wgsl-out
```

| Patch | Why | Vanilla 26.3 users |
|---|---|---|
| `strip` | naga rejects `OpLine` in the type section | every module (the frontend always asks for debug info) |
| `point_size` | WGSL has no point size; WebGPU points are 1 px | `debug_point.vsh` (4 debug pipelines) |
| `texel_buffer` | WGSL has no texel buffers | `clouds.vsh` `isamplerBuffer CloudFaces` (`R8_SINT`, 8 pipelines) |
| `split_sampler` | naga spv-in and WGSL have no combined image samplers | every `sampler2D`/`samplerCube`, including function parameters (`sample_lightmap`) |
| `output_array` | WGSL entry point outputs cannot be arrays | `oit_add_transmittance.glsl` `out vec4 coeff[2]` |

Every patch checks the shapes it handles and refuses anything else with
`Error::Spirv` rather than guessing (a resource pack's core shader that uses,
say, sampler arrays fails that one pipeline; D11 disables only that pipeline).

## WGSL binding layout

Group 0. For vanilla uniform `i`: binding `2 * i` holds the uniform buffer,
texture or (texel buffer) storage buffer; binding `2 * i + 1` the sampler split
off a combined image sampler (`wgsl_binding`). Both stages derive it from the
uniform index alone, so a pipeline's stages agree without seeing each other.

`translate` also reflects each binding: uniform block size, texture dimension,
sample kind, and whether the texture is ever passed to `textureSample*`
(`sampled`, followed through function calls). A texture that is only read with
`textureLoad` can be bound as `unfilterable-float` (depth and 32-bit float
targets); a sampled one needs `float` and a filtering sampler.

## Texel buffers

`texelFetch(buf, i)` becomes a load of word `i >> log2(texels per word)` of
`var<storage, read> buf: struct { data: array<u32> }` and the decode of the
texel format the backend passes in `Options::texel_buffers` (from
`UniformDescription.gpuFormat()`): `R8/R16/R32` `SINT`/`UINT`, `R32_FLOAT`.
The backend binds the buffer's bytes unchanged, padded to a multiple of 4.

## Clip space

The vanilla shaders are written for GL's clip space (y up) and the GL backend
passes their SPIR-V through SPIRV-Cross without any flip. WebGPU's clip space is
also y up, but its framebuffer row 0 is the top while GL's is the bottom; every
`gl_FragCoord`-indexed `texelFetch` (OIT), every full-screen pass (`screenquad`
`uv = pos * 0.5 + 0.5`) and every readback assumes GL's row order.
`Options::flip_vertex_y` negates the vertex position's y (naga's
`adjust_coordinate_space`) so a backend can render in GL's row order; it must
then use `frontFace: "cw"` for the same culling and flip once when presenting.
The corpus check runs with the flag off (it only validates).

## Build and use

```sh
cd port/native/crates/gaius-shaderxlat
CARGO_TARGET_DIR=../../target/shaderxlat cargo test -p gaius-shaderxlat -p gaius-shaderxlat-wasm
CARGO_TARGET_DIR=../../target/shaderxlat-wasm-simd \
  CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
  cargo build --profile release-size --target wasm32-unknown-unknown -p gaius-shaderxlat-wasm
```

The wasm wrapper (`wasm/`) is a `gaius-kernel-abi` kernel: `run_shader_xlat`,
job kind `0x0501`, no imports; `port/native/golden/shaders/shaderxlat-job.js`
encodes the jobs and runs them on a synchronous instance. Corpus test and
measurements: `port/native/golden/shaders/README.md`.

The crate is its own Cargo workspace for now (naga stays out of the kernels'
lock file until it is merged into `port/native/Cargo.toml`).
