//! Compares the kernel with reference data dumped from the real 26.3 / 26.2 classes by
//! `port/native/golden/worldgen/run-worldgen-reference.sh` (set `GAIUS_WORLDGEN_REFERENCE` to
//! its output directory). Without the variable the test does nothing.
#![allow(clippy::needless_range_loop)]

use gaius_worldgen::{Generator, TerrainRequest};
use std::path::Path;

fn ints(bytes: &[u8]) -> Vec<i32> {
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect()
}

#[test]
fn kernel_matches_vanilla_reference() {
    let Ok(dir) = std::env::var("GAIUS_WORLDGEN_REFERENCE") else {
        eprintln!("GAIUS_WORLDGEN_REFERENCE is not set; skipping");
        return;
    };
    let dir = Path::new(&dir);
    let ir = std::fs::read(dir.join("overworld.gwir")).expect("overworld.gwir");
    let mut generator = Generator::load(&ir).unwrap_or_else(|e| panic!("IR does not load: {e}"));
    let mut failures = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().and_then(|e| e.to_str()) != Some("ref") {
            continue;
        }
        let data = ints(&std::fs::read(&path).unwrap());
        let (cx, cz) = (data[0], data[1]);
        let volume = 16 * 384 * 16;
        let density_ref = &data[2..2 + volume];
        let biomes_ref = &data[2 + volume..2 + volume + 24 * 64];
        let states_ref = &data[2 + volume + 24 * 64..2 + 2 * volume + 24 * 64];
        let surface_ref = &data[2 + 2 * volume + 24 * 64..2 + 3 * volume + 24 * 64];
        let heights_ref = &data[2 + 3 * volume + 24 * 64..];

        // 26.2 references carry no density block (zeros): only 26.3 has a volume sampler.
        if let Some(density) = generator.debug_final_density(cx, cz) {
            let mut mismatches = 0;
            let mut worst = 0i64;
            for (i, (&d, &r)) in density.iter().zip(density_ref).enumerate() {
                let ulps = (d.to_bits() as i32 as i64 - r as i64).abs();
                if ulps != 0 {
                    if mismatches < 5 {
                        eprintln!(
                            "chunk {cx},{cz} density[{i}]: kernel {d} vanilla {}",
                            f32::from_bits(r as u32)
                        );
                    }
                    mismatches += 1;
                    worst = worst.max(ulps);
                }
            }
            eprintln!("chunk {cx},{cz}: density mismatches {mismatches} (worst {worst} ulps)");
            if mismatches > 0 {
                failures.push(format!("{cx},{cz} density"));
            }
        }

        let biomes: Vec<i32> = generator.run_biomes(cx, cz).iter().map(|&b| b as i32).collect();
        // Vanilla warm-starts each RTree search with the thread's previous result, so on an
        // exact fitness tie the winner depends on the query history; only real differences count.
        let mut biome_mismatches = 0;
        for (i, (a, b)) in biomes.iter().zip(biomes_ref).enumerate() {
            if a != b {
                let (section, local) = (i as i32 / 64, i as i32 % 64);
                let (qy, qz, qx) = (local / 16, (local / 4) % 4, local % 4);
                let q = [cx * 4 + qx, -16 + section * 4 + qy, cz * 4 + qz];
                let Some(target) = generator.debug_climate_target(q[0], q[1], q[2]) else {
                    eprintln!("chunk {cx},{cz} quart {q:?}: kernel biome {a} vanilla {b}");
                    biome_mismatches += 1;
                    continue;
                };
                let fitness = |biome: i32| -> i64 {
                    let ir = gaius_worldgen::Ir::decode(&ir).unwrap();
                    let gaius_worldgen::ir::BiomeSource::MultiNoise { entries, .. } = &ir.biomes.source else {
                        unreachable!()
                    };
                    entries
                        .iter()
                        .filter(|e| ir.biomes.biomes[e.biome as usize].global_id as i32 == biome)
                        .map(|e| {
                            let mut f = 0i64;
                            for d in 0..7 {
                                let t = if d < 6 { target[d] } else { 0 };
                                let (lo, hi) = (e.params[d][0], e.params[d][1]);
                                let dist = if t > hi {
                                    t - hi
                                } else if lo > t {
                                    lo - t
                                } else {
                                    0
                                };
                                f += dist * dist;
                            }
                            f
                        })
                        .min()
                        .unwrap()
                };
                let (fa, fb) = (fitness(*a), fitness(*b));
                eprintln!("chunk {cx},{cz} quart {q:?}: kernel biome {a} (fitness {fa}) vanilla {b} (fitness {fb})");
                if fa != fb {
                    biome_mismatches += 1;
                }
            }
        }
        eprintln!("chunk {cx},{cz}: biome mismatches {biome_mismatches}");
        if biome_mismatches > 0 {
            failures.push(format!("{cx},{cz} biomes"));
        }

        for (label, surface, reference) in [("fill", false, states_ref), ("surface", true, surface_ref)] {
            let result = generator.run_terrain(&TerrainRequest {
                chunk_x: cx,
                chunk_z: cz,
                surface,
                ..TerrainRequest::default()
            });
            let chunk = &result.chunk;
            let mut state_mismatches = 0;
            for z in 0..16 {
                for x in 0..16 {
                    for y in 0..384 {
                        let local = chunk.get(cx * 16 + x, y - 64, cz * 16 + z);
                        let global = generator.states.global_ids[local as usize] as i32;
                        let expected = reference[(y + (x + z * 16) * 384) as usize];
                        if global != expected {
                            if state_mismatches < 8 {
                                eprintln!(
                                    "chunk {cx},{cz} {label} block {x} {} {z}: kernel {global} vanilla {expected}",
                                    y - 64
                                );
                            }
                            state_mismatches += 1;
                        }
                    }
                }
            }
            eprintln!("chunk {cx},{cz}: {label} block mismatches {state_mismatches}");
            if state_mismatches > 0 {
                failures.push(format!("{cx},{cz} {label} blocks"));
            }
            if surface {
                let heights: Vec<i32> = chunk
                    .world_surface
                    .iter()
                    .chain(chunk.ocean_floor.iter())
                    .copied()
                    .collect();
                let height_mismatches = heights.iter().zip(heights_ref).filter(|(a, b)| a != b).count();
                eprintln!("chunk {cx},{cz}: heightmap mismatches {height_mismatches}");
                if height_mismatches > 0 {
                    failures.push(format!("{cx},{cz} heightmaps"));
                }
            }
        }
    }
    assert!(failures.is_empty(), "vanilla mismatches: {failures:?}");
}
