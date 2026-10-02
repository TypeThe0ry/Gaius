//! Biome tint with blend radius 0: `BiomeManager.getBiome(pos)` (the fuzzy
//! zoom from quarts to blocks) followed by the biome's color resolver, as
//! `ClientLevel.calculateBlockTint` does when `biomeBlendRadius` is 0.

use crate::job::{BiomeColors, SectionJob, GRASS_MODIFIER_DARK_FOREST, GRASS_MODIFIER_SWAMP, QUARTS};
use crate::mth::{lcg_next, opaque};
use crate::table::tint;

/// Per-job memo of block -> palette index, for x, z in 0..16 and y in -1..16
/// (the layer below the section serves `GRASS_BELOW`).
pub struct BiomeCache {
    slots: Vec<u8>,
}

const UNSET: u8 = 0xFF;
const CACHE_HEIGHT: i32 = 17;

impl Default for BiomeCache {
    fn default() -> Self {
        BiomeCache {
            slots: vec![UNSET; 16 * 16 * CACHE_HEIGHT as usize],
        }
    }
}

impl BiomeCache {
    pub fn reset(&mut self) {
        self.slots.fill(UNSET);
    }
}

#[inline]
fn fiddle(l: i64) -> f64 {
    let d = (l >> 24).rem_euclid(1024) as f64 / 1024.0;
    (d - 0.5) * 0.9
}

/// `BiomeManager.getFiddledDistance`.
#[inline]
fn fiddled_distance(seed: i64, x: i32, y: i32, z: i32, xf: f64, yf: f64, zf: f64) -> f64 {
    let mut m = lcg_next(seed, x as i64);
    m = lcg_next(m, y as i64);
    m = lcg_next(m, z as i64);
    m = lcg_next(m, x as i64);
    m = lcg_next(m, y as i64);
    m = lcg_next(m, z as i64);
    let d = fiddle(m);
    m = lcg_next(m, seed);
    let e = fiddle(m);
    m = lcg_next(m, seed);
    let f = fiddle(m);
    let sq = |v: f64| v * v;
    sq(zf + f) + sq(yf + e) + sq(xf + d)
}

/// `BiomeManager.getBiome(x, y, z)` reduced to the quart it picks.
pub fn zoom(seed: i64, x: i32, y: i32, z: i32) -> (i32, i32, i32) {
    let i = x - 2;
    let j = y - 2;
    let k = z - 2;
    let l = i >> 2;
    let m = j >> 2;
    let n = k >> 2;
    let d = (i & 3) as f64 / 4.0;
    let e = (j & 3) as f64 / 4.0;
    let f = (k & 3) as f64 / 4.0;
    let mut best = 0;
    let mut best_distance = f64::INFINITY;
    for p in 0..8 {
        let bx = p & 4 == 0;
        let by = p & 2 == 0;
        let bz = p & 1 == 0;
        let q = if bx { l } else { l + 1 };
        let r = if by { m } else { m + 1 };
        let s = if bz { n } else { n + 1 };
        let h = if bx { d } else { d - 1.0 };
        let t = if by { e } else { e - 1.0 };
        let u = if bz { f } else { f - 1.0 };
        let v = fiddled_distance(seed, q, r, s, h, t, u);
        if best_distance > v {
            best = p;
            best_distance = v;
        }
    }
    (
        if best & 4 == 0 { l } else { l + 1 },
        if best & 2 == 0 { m } else { m + 1 },
        if best & 1 == 0 { n } else { n + 1 },
    )
}

/// The color resolvers of one job.
pub struct Tinter<'j, 'a> {
    pub job: &'j SectionJob<'a>,
    pub origin: [i32; 3],
}

impl Tinter<'_, '_> {
    /// Palette index of the biome at a world position.
    fn biome(&self, cache: &mut BiomeCache, x: i32, y: i32, z: i32) -> usize {
        let lx = x - self.origin[0];
        let ly = y - self.origin[1];
        let lz = z - self.origin[2];
        let slot = if (0..16).contains(&lx) && (-1..16).contains(&ly) && (0..16).contains(&lz) {
            Some((((ly + 1) * 16 + lz) * 16 + lx) as usize)
        } else {
            None
        };
        if let Some(s) = slot {
            let v = cache.slots[s];
            if v != UNSET {
                return v as usize;
            }
        }
        let (qx, qy, qz) = zoom(self.job.biome_zoom_seed, x, y, z);
        let base = |o: i32| (o >> 2) - 1;
        let gx = (qx - base(self.origin[0])).clamp(0, QUARTS as i32 - 1);
        let gy = (qy - base(self.origin[1])).clamp(0, QUARTS as i32 - 1);
        let gz = (qz - base(self.origin[2])).clamp(0, QUARTS as i32 - 1);
        let index = self.job.biome_quarts[((gy * QUARTS as i32 + gz) * QUARTS as i32 + gx) as usize];
        if let Some(s) = slot {
            cache.slots[s] = index;
        }
        index as usize
    }

    /// `Biome.getGrassColor(x, z)` with the swamp noise precomputed per column.
    fn grass(&self, colors: &BiomeColors, x: i32, z: i32) -> i32 {
        match colors.grass_modifier {
            GRASS_MODIFIER_DARK_FOREST => opaque(((colors.grass & 16_711_422) + 2_634_762) >> 1),
            GRASS_MODIFIER_SWAMP => {
                let bit = (((z & 15) << 4) | (x & 15)) as usize;
                if self.job.swamp_mask[bit >> 3] & (1 << (bit & 7)) != 0 {
                    -11_766_212
                } else {
                    -9_801_671
                }
            }
            _ => colors.grass,
        }
    }

    /// `BlockTintSource.colorInWorld` for a tint kind at a world position.
    pub fn color(&self, cache: &mut BiomeCache, kind: u8, constant: i32, x: i32, y: i32, z: i32) -> i32 {
        match kind {
            tint::CONSTANT => constant,
            tint::GRASS_BELOW => {
                let b = self.biome(cache, x, y - 1, z);
                self.grass(&self.job.palette[b], x, z)
            }
            _ => {
                let b = self.biome(cache, x, y, z);
                let colors = &self.job.palette[b];
                match kind {
                    tint::GRASS => self.grass(colors, x, z),
                    tint::FOLIAGE => colors.foliage,
                    tint::DRY_FOLIAGE => colors.dry_foliage,
                    _ => colors.water,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::zoom;

    #[test]
    fn zoom_stays_next_to_the_block_quart() {
        for &(x, y, z) in &[(0, 64, 0), (-17, -60, 33), (1000, 300, -999)] {
            let (qx, qy, qz) = zoom(123_456_789, x, y, z);
            assert!((qx - ((x - 2) >> 2)).abs() <= 1);
            assert!((qy - ((y - 2) >> 2)).abs() <= 1);
            assert!((qz - ((z - 2) >> 2)).abs() <= 1);
        }
    }
}
