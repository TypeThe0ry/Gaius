//! SPIR-V to WGSL for the WebGPU backend of the renderpearl SPI.
//!
//! Input is exactly what a renderpearl backend receives in
//! `BackendRenderPipeline.CreateInfo.Shader.module().spv()`: the vanilla
//! frontend's shaderc output (Vulkan 1.2 / SPIR-V 1.5, `-O0`, debug info)
//! with every resource in descriptor set 0 at the pipeline's uniform index
//! (`CreateInfo.uniforms()`), shared by the vertex and fragment stages.
//!
//! Pipeline:
//! 1. SPIR-V patches ([`passes`]): strip debug info, drop `gl_PointSize`
//!    writes, texel buffers to storage buffers, split combined image samplers,
//!    split output arrays;
//! 2. naga's SPIR-V reader (clip space unchanged unless
//!    [`Options::flip_vertex_y`]; the depth range already matches through
//!    `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`);
//! 3. the WebGPU binding layout ([`wgsl_binding`]);
//! 4. naga validation and the WGSL writer.
//!
//! The WGSL binding layout is a pure function of the vanilla uniform index,
//! so both stages of a pipeline agree without seeing each other: group 0,
//! binding `2 * index` for the uniform buffer, texture or storage buffer of
//! uniform `index`, `2 * index + 1` for the sampler of a combined image
//! sampler.

pub mod passes;
pub mod spirv;

use naga::valid::{Capabilities, ModuleInfo, ValidationFlags, Validator};
use std::fmt;

/// Format of a texel buffer's texels (`UniformDescription.gpuFormat()`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TexelFormat {
    R8Sint,
    R8Uint,
    R16Sint,
    R16Uint,
    R32Sint,
    R32Uint,
    R32Float,
}

impl TexelFormat {
    /// Parses the renderpearl `GpuFormat` name.
    pub fn from_gpu_format(name: &str) -> Option<TexelFormat> {
        Some(match name {
            "R8_SINT" => TexelFormat::R8Sint,
            "R8_UINT" => TexelFormat::R8Uint,
            "R16_SINT" => TexelFormat::R16Sint,
            "R16_UINT" => TexelFormat::R16Uint,
            "R32_SINT" => TexelFormat::R32Sint,
            "R32_UINT" => TexelFormat::R32Uint,
            "R32_FLOAT" => TexelFormat::R32Float,
            _ => return None,
        })
    }

    pub fn code(self) -> u32 {
        self as u32
    }

    pub fn from_code(code: u32) -> Option<TexelFormat> {
        [
            TexelFormat::R8Sint,
            TexelFormat::R8Uint,
            TexelFormat::R16Sint,
            TexelFormat::R16Uint,
            TexelFormat::R32Sint,
            TexelFormat::R32Uint,
            TexelFormat::R32Float,
        ]
        .get(code as usize)
        .copied()
    }
}

#[derive(Clone, Debug, Default)]
pub struct Options {
    /// Texel format of each texel buffer, by vanilla uniform index.
    pub texel_buffers: Vec<(u32, TexelFormat)>,
    /// Negate the vertex position's y (naga's `adjust_coordinate_space`). A backend that
    /// renders with GL's row order (row 0 = bottom of clip space, as the GL backend and
    /// every `gl_FragCoord`/`texelFetch` in the vanilla shaders assume) sets it, flips the
    /// front face and flips once when presenting; see the crate README.
    pub flip_vertex_y: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Vertex,
    Fragment,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SampleKind {
    Float,
    Sint,
    Uint,
    Depth,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextureDim {
    D1,
    D2,
    D2Array,
    D3,
    Cube,
    CubeArray,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BindingKind {
    /// `var<uniform>`; `size` is the WGSL size of the block.
    UniformBuffer {
        size: u32,
    },
    /// `var<storage, read>` (texel buffers).
    StorageBuffer,
    /// `textureSample*` (`sampled`) needs a filterable texture and a filtering
    /// sampler; a texture only read with `textureLoad` can take any format.
    Texture {
        dim: TextureDim,
        kind: SampleKind,
        multisampled: bool,
        sampled: bool,
    },
    Sampler {
        comparison: bool,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Binding {
    pub group: u32,
    pub binding: u32,
    /// Vanilla uniform index (`CreateInfo.uniforms()`).
    pub uniform: u32,
    pub kind: BindingKind,
    /// Statically used by the entry point.
    pub used: bool,
}

#[derive(Clone, Debug)]
pub struct Translation {
    pub stage: Stage,
    pub wgsl: String,
    pub bindings: Vec<Binding>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    /// A SPIR-V patch refused the module.
    Spirv(String),
    /// naga's SPIR-V reader.
    Parse(String),
    /// naga validation.
    Validate(String),
    /// naga's WGSL writer.
    Write(String),
}

impl Error {
    pub fn spirv(message: impl Into<String>) -> Error {
        Error::Spirv(message.into())
    }

    pub fn phase(&self) -> &'static str {
        match self {
            Error::Spirv(_) => "spirv",
            Error::Parse(_) => "parse",
            Error::Validate(_) => "validate",
            Error::Write(_) => "write",
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Spirv(m) => write!(f, "spirv patch: {m}"),
            Error::Parse(m) => write!(f, "naga spv-in: {m}"),
            Error::Validate(m) => write!(f, "naga validation: {m}"),
            Error::Write(m) => write!(f, "naga wgsl-out: {m}"),
        }
    }
}

impl std::error::Error for Error {}

/// WGSL binding of vanilla uniform `uniform` (`sampler`: the split-off sampler).
pub const fn wgsl_binding(uniform: u32, sampler: bool) -> u32 {
    uniform * 2 + sampler as u32
}

/// Step 1: the SPIR-V patches. Returns the patched words.
pub fn patch_spirv(spirv: &[u8], options: &Options) -> Result<Vec<u32>, Error> {
    let words = spirv::words_from_bytes(spirv)?;
    let mut module = spirv::Module::parse(&words)?;
    passes::strip::run(&mut module);
    passes::point_size::run(&mut module)?;
    passes::texel_buffer::run(&mut module, &options.texel_buffers)?;
    passes::split_sampler::run(&mut module)?;
    passes::output_array::run(&mut module)?;
    Ok(module.emit())
}

/// Step 2: naga's SPIR-V reader.
pub fn parse(words: &[u32], options: &Options) -> Result<naga::Module, Error> {
    let options = naga::front::spv::Options {
        adjust_coordinate_space: options.flip_vertex_y,
        strict_capabilities: false,
        block_ctx_dump_prefix: None,
    };
    naga::front::spv::Frontend::new(words.iter().copied(), &options)
        .parse()
        .map_err(|e| Error::Parse(format!("{e}")))
}

/// Step 3: moves every resource to its WGSL binding.
pub fn assign_bindings(module: &mut naga::Module) -> Result<(), Error> {
    for (_, global) in module.global_variables.iter_mut() {
        let Some(binding) = global.binding.as_mut() else {
            continue;
        };
        if binding.group != 0 {
            return Err(Error::Validate(format!("resource in descriptor set {}", binding.group)));
        }
        let sampler = matches!(module.types[global.ty].inner, naga::TypeInner::Sampler { .. });
        binding.binding = wgsl_binding(binding.binding, sampler);
    }
    Ok(())
}

/// Step 4a: naga validation with WebGPU's capabilities.
pub fn validate(module: &naga::Module) -> Result<ModuleInfo, Error> {
    Validator::new(ValidationFlags::all(), Capabilities::empty())
        .validate(module)
        .map_err(|e| Error::Validate(format_validation(&e)))
}

fn format_validation(error: &naga::WithSpan<naga::valid::ValidationError>) -> String {
    let mut text = format!("{}", error.as_inner());
    let mut source: Option<&dyn std::error::Error> = std::error::Error::source(error.as_inner());
    while let Some(inner) = source {
        text.push_str(": ");
        text.push_str(&inner.to_string());
        source = inner.source();
    }
    text
}

/// Step 4b: the WGSL text.
pub fn write_wgsl(module: &naga::Module, info: &ModuleInfo) -> Result<String, Error> {
    naga::back::wgsl::write_string(module, info, naga::back::wgsl::WriterFlags::empty())
        .map_err(|e| Error::Write(format!("{e}")))
}

/// Describes the bindings of the module's single entry point.
pub fn reflect(module: &naga::Module, info: &ModuleInfo) -> Result<(Stage, Vec<Binding>), Error> {
    let entry = module
        .entry_points
        .first()
        .ok_or_else(|| Error::Validate("no entry point".into()))?;
    let stage = match entry.stage {
        naga::ShaderStage::Vertex => Stage::Vertex,
        naga::ShaderStage::Fragment => Stage::Fragment,
        other => return Err(Error::Validate(format!("unsupported stage {other:?}"))),
    };
    let entry_info = info.get_entry_point(0);
    let mut bindings = Vec::new();
    for (handle, global) in module.global_variables.iter() {
        let Some(binding) = global.binding.as_ref() else {
            continue;
        };
        let used = !entry_info[handle].is_empty();
        let kind = match &module.types[global.ty].inner {
            naga::TypeInner::Sampler { comparison } => BindingKind::Sampler {
                comparison: *comparison,
            },
            naga::TypeInner::Image { dim, arrayed, class } => {
                let dim = match (dim, arrayed) {
                    (naga::ImageDimension::D1, _) => TextureDim::D1,
                    (naga::ImageDimension::D2, false) => TextureDim::D2,
                    (naga::ImageDimension::D2, true) => TextureDim::D2Array,
                    (naga::ImageDimension::D3, _) => TextureDim::D3,
                    (naga::ImageDimension::Cube, false) => TextureDim::Cube,
                    (naga::ImageDimension::Cube, true) => TextureDim::CubeArray,
                };
                let (kind, multisampled) = match class {
                    naga::ImageClass::Sampled { kind, multi } => (
                        match kind {
                            naga::ScalarKind::Sint => SampleKind::Sint,
                            naga::ScalarKind::Uint => SampleKind::Uint,
                            _ => SampleKind::Float,
                        },
                        *multi,
                    ),
                    naga::ImageClass::Depth { multi } => (SampleKind::Depth, *multi),
                    _ => return Err(Error::Validate("storage textures are not expected".into())),
                };
                let sampled = entry_info.sampling_set.iter().any(|key| key.image == handle);
                BindingKind::Texture {
                    dim,
                    kind,
                    multisampled,
                    sampled,
                }
            }
            _ => match global.space {
                naga::AddressSpace::Uniform => BindingKind::UniformBuffer {
                    size: module.types[global.ty].inner.size(module.to_ctx()),
                },
                naga::AddressSpace::Storage { .. } => BindingKind::StorageBuffer,
                other => return Err(Error::Validate(format!("unexpected resource space {other:?}"))),
            },
        };
        bindings.push(Binding {
            group: binding.group,
            binding: binding.binding,
            uniform: binding.binding / 2,
            kind,
            used,
        });
    }
    bindings.sort_by_key(|b| (b.group, b.binding));
    Ok((stage, bindings))
}

/// SPIR-V of one vanilla pipeline stage to WGSL.
pub fn translate(spirv: &[u8], options: &Options) -> Result<Translation, Error> {
    let words = patch_spirv(spirv, options)?;
    let mut module = parse(&words, options)?;
    assign_bindings(&mut module)?;
    let info = validate(&module)?;
    let wgsl = write_wgsl(&module, &info)?;
    let (stage, bindings) = reflect(&module, &info)?;
    Ok(Translation { stage, wgsl, bindings })
}
