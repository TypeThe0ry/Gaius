//! Golden fixture evaluation, one module per family of kinds.

pub mod basic;
pub mod json;
pub mod noise32;
pub mod noise64;

use gaius_noise::Profile;
use json::{Case, Outcome};

/// Evaluates one fixture line with the Rust port; `Err` means the line could
/// not be evaluated (unknown kind, method or malformed params).
pub fn evaluate(profile: Profile, case: &Case) -> Outcome {
    let float_synth = profile.uses_synth32();
    match case.kind.as_str() {
        "xoroshiro_next_long" => basic::xoroshiro_next_long(case),
        "xoroshiro_next_double" => basic::xoroshiro_next_double(case),
        "legacy_next_int_bound" => basic::legacy_next_int_bound(case),
        "legacy_next_double" => basic::legacy_next_double(case),
        "positional_from_hash" => basic::positional_from_hash(case),
        "mth" => basic::mth(profile, case),
        "improved_noise" if float_synth => noise32::improved_noise(case),
        "improved_noise" => noise64::improved_noise(profile, case),
        "perlin_noise" if float_synth => noise32::perlin_noise(case),
        "perlin_noise" => noise64::perlin_noise(profile, case),
        "normal_noise" if float_synth => noise32::normal_noise(case),
        "normal_noise" => noise64::normal_noise(profile, case),
        "simplex_noise" if float_synth => noise32::simplex_noise(case),
        "simplex_noise" => noise64::simplex_noise(profile, case),
        "blended_noise" if float_synth => noise32::blended_noise(case),
        "blended_noise" => noise64::blended_noise(profile, case),
        other => Err(format!("unsupported kind \"{other}\"")),
    }
}
