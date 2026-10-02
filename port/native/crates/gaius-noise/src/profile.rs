//! Released Minecraft profiles and the arithmetic differences between them.

use crate::mth::FloorMode;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Profile {
    V1_21_11,
    V26_2,
    V26_3,
}

impl Profile {
    pub const ALL: [Profile; 3] = [Profile::V1_21_11, Profile::V26_2, Profile::V26_3];

    /// Parses the profile directory name used under `port/work/`.
    pub fn from_name(name: &str) -> Option<Profile> {
        match name {
            "1.21.11" => Some(Profile::V1_21_11),
            "26.2" => Some(Profile::V26_2),
            "26.3" => Some(Profile::V26_3),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Profile::V1_21_11 => "1.21.11",
            Profile::V26_2 => "26.2",
            Profile::V26_3 => "26.3",
        }
    }

    /// 1.21.11's `Mth.floor` is `(int) d` adjusted downwards; 26.2 switched to
    /// `(int) Math.floor(d)`. They disagree below `Integer.MIN_VALUE`.
    pub fn floor_mode(self) -> FloorMode {
        match self {
            Profile::V1_21_11 => FloorMode::CastAdjust,
            Profile::V26_2 | Profile::V26_3 => FloorMode::MathFloor,
        }
    }

    /// 26.3 replaced the double-precision synth classes with float ones.
    pub fn uses_synth32(self) -> bool {
        self == Profile::V26_3
    }
}
