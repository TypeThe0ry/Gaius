//! SPIR-V rewrites that make vanilla modules acceptable to naga and WGSL.
//! They run in this order: [`strip`], [`point_size`], [`texel_buffer`],
//! [`split_sampler`], [`output_array`] (texel buffers must go before the
//! sampler split, which would otherwise pair them with a sampler).

pub mod output_array;
pub mod point_size;
pub mod split_sampler;
pub mod strip;
pub mod texel_buffer;
