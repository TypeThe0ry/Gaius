//! wasm32 entry point for `gaius-shaderxlat`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version` and `run_shader_xlat` (job kind [`SHADER_XLAT`]).
//!
//! The same module serves the kernel workers (prefetch at `compilePipeline`)
//! and a synchronous instance on the main thread (`finishCompile` on a cache
//! miss); nothing is kept between jobs.
//!
//! Job payload (little-endian):
//! - `u32` flags: bit 0 [`Options::flip_vertex_y`](gaius_shaderxlat::Options);
//! - `u32` n, then n pairs `u32` uniform index, `u32` [`TexelFormat`] code
//!   (texel buffers of the pipeline);
//! - the SPIR-V module, to the end of the payload.
//!
//! Result payload:
//! - `u32` stage (0 vertex, 1 fragment);
//! - `u32` n, then n binding records of 8 `u32`: group, binding, vanilla
//!   uniform index, kind, a, b, c, used, where kind 0 is a uniform buffer
//!   (a = size), 1 a read-only storage buffer, 2 a texture (a = dimension 0 1d,
//!   1 2d, 2 2d-array, 3 3d, 4 cube, 5 cube-array; b = sample kind 0 float,
//!   1 sint, 2 uint, 3 depth; c = bit 0 multisampled, bit 1 sampled by
//!   `textureSample*`), 3 a sampler (a = comparison);
//! - `u32` WGSL length, then the WGSL text (UTF-8).

use gaius_kernel_abi::{KernelError, Reader, Status};
use gaius_shaderxlat::{BindingKind, Options, SampleKind, Stage, TexelFormat, TextureDim};

pub use gaius_kernel_abi::kind::{SHADER_FAMILY, SHADER_XLAT};

/// Decodes a job payload, translates it and encodes the result payload.
pub fn run(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut reader = Reader::new(payload);
    let flags = reader.u32()?;
    if flags & !1 != 0 {
        return Err(KernelError::new(Status::BadPayload, "unknown flags"));
    }
    let count = reader.u32()? as usize;
    if count > 64 {
        return Err(KernelError::new(Status::BadPayload, "too many texel buffers"));
    }
    let mut texel_buffers = Vec::with_capacity(count);
    for _ in 0..count {
        let uniform = reader.u32()?;
        let code = reader.u32()?;
        let format =
            TexelFormat::from_code(code).ok_or_else(|| KernelError::new(Status::BadPayload, "unknown texel format"))?;
        texel_buffers.push((uniform, format));
    }
    let spirv = reader.take(reader.remaining())?;
    let options = Options {
        texel_buffers,
        flip_vertex_y: flags & 1 != 0,
    };
    let translation = gaius_shaderxlat::translate(spirv, &options)
        .map_err(|e| KernelError::new(Status::BadPayload, e.to_string()))?;

    let mut out = Vec::with_capacity(16 + translation.bindings.len() * 32 + translation.wgsl.len());
    let put = |out: &mut Vec<u8>, value: u32| out.extend_from_slice(&value.to_le_bytes());
    put(&mut out, matches!(translation.stage, Stage::Fragment) as u32);
    put(&mut out, translation.bindings.len() as u32);
    for b in &translation.bindings {
        let (kind, a, c, d) = match b.kind {
            BindingKind::UniformBuffer { size } => (0, size, 0, 0),
            BindingKind::StorageBuffer => (1, 0, 0, 0),
            BindingKind::Texture {
                dim,
                kind,
                multisampled,
                sampled,
            } => (
                2,
                match dim {
                    TextureDim::D1 => 0,
                    TextureDim::D2 => 1,
                    TextureDim::D2Array => 2,
                    TextureDim::D3 => 3,
                    TextureDim::Cube => 4,
                    TextureDim::CubeArray => 5,
                },
                match kind {
                    SampleKind::Float => 0,
                    SampleKind::Sint => 1,
                    SampleKind::Uint => 2,
                    SampleKind::Depth => 3,
                },
                multisampled as u32 | (sampled as u32) << 1,
            ),
            BindingKind::Sampler { comparison } => (3, comparison as u32, 0, 0),
        };
        for word in [b.group, b.binding, b.uniform, kind, a, c, d, b.used as u32] {
            put(&mut out, word);
        }
    }
    put(&mut out, translation.wgsl.len() as u32);
    out.extend_from_slice(translation.wgsl.as_bytes());
    Ok(out)
}

#[cfg(target_arch = "wasm32")]
mod exports {
    use gaius_kernel_abi::{job_bytes, run_kernel};

    gaius_kernel_abi::export_runtime!();

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_shader_xlat(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        run_kernel(job, super::SHADER_XLAT, super::run)
    }
}
