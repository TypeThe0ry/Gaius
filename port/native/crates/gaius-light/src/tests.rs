//! Quick sanity checks: hand-computed scenes, and the bucket increase order
//! against vanilla's first-in-first-out order on random columns.

use crate::column::{flags, ColumnSpec, Layer, LightColumn, SIDE_EAST, SIDE_NORTH, SIDE_SOUTH, SIDE_WEST};
use crate::section::SectionStates;
use crate::table::{make_props, LightTable};
use alloc::collections::BTreeMap;
use alloc::vec;
use alloc::vec::Vec;

const AIR: u16 = 0;
const STONE: u16 = 1;
const GLASS: u16 = 2;
const TORCH: u16 = 3;
const WATER: u16 = 4;
const BOTTOM_SLAB: u16 = 5;
const TOP_SLAB: u16 = 6;
const GLOWSTONE: u16 = 7;
const LEAVES: u16 = 8;

fn table() -> LightTable {
    let props = vec![
        make_props(0, 0, 0),
        make_props(15, 0, 0),
        make_props(0, 0, 0),
        make_props(0, 14, 0),
        make_props(1, 0, 0),
        make_props(0, 0, 1),
        make_props(0, 0, 2),
        make_props(15, 15, 0),
        make_props(1, 0, 0),
    ];
    // Faces: 0 empty, 1 full, 2 lower half, 3 upper half. Directions are
    // DOWN, UP, NORTH, SOUTH, WEST, EAST.
    let faces = [[0; 6], [1, 0, 2, 2, 2, 2], [0, 1, 3, 3, 3, 3]];
    LightTable::from_parts(props, &faces, 4, |a, b| {
        a == 1 || b == 1 || (a == 2 && b == 3) || (a == 3 && b == 2)
    })
    .unwrap()
}

struct Scene {
    sections: usize,
    neighbours: u8,
    /// Slot-major flat ids, (y << 8 | z << 4 | x) per section.
    states: Vec<Vec<Vec<u16>>>,
    flags: Vec<Vec<u8>>,
    ring_layers: Vec<Vec<(Vec<u8>, Vec<u8>)>>,
}

impl Scene {
    fn new(sections: usize, neighbours: u8) -> Scene {
        let light = sections + 2;
        Scene {
            sections,
            neighbours,
            states: vec![vec![vec![AIR; 4096]; sections]; 5],
            flags: vec![vec![flags::SKY_STORING | flags::BLOCK_STORING; light]; 5],
            ring_layers: vec![vec![(vec![0; 2048], vec![0; 2048]); light]; 5],
        }
    }

    fn set(&mut self, x: usize, y: usize, z: usize, id: u16) {
        self.states[0][y >> 4][((y & 15) << 8) | (z << 4) | x] = id;
    }

    fn spec(&self) -> ColumnSpec {
        ColumnSpec {
            min_section: 0,
            section_count: self.sections,
            sky_bottom_section: -1,
            sky_light_on: true,
            block_light_on: true,
            emit_outgoing: true,
            neighbours: self.neighbours,
        }
    }

    fn load(&self, column: &mut LightColumn, table: &LightTable) {
        column.begin(self.spec()).unwrap();
        for slot in 0..5 {
            if slot > 0 && self.neighbours & (1 << (slot - 1)) == 0 {
                continue;
            }
            for (section, ids) in self.states[slot].iter().enumerate() {
                let bytes: Vec<u8> = ids.iter().flat_map(|id| id.to_le_bytes()).collect();
                column
                    .load_section(slot, section, SectionStates::FlatU16(&bytes), table)
                    .unwrap();
            }
            for (light_section, &value) in self.flags[slot].iter().enumerate() {
                column.set_section_flags(slot, light_section, value).unwrap();
                if slot > 0 {
                    let (sky, block) = &self.ring_layers[slot][light_section];
                    column.load_layer(slot, light_section, Layer::Sky, sky).unwrap();
                    column.load_layer(slot, light_section, Layer::Block, block).unwrap();
                }
            }
        }
        column.compute_sources(table);
    }

    fn light_first_time(&self, column: &mut LightColumn, table: &LightTable) {
        self.load(column, table);
        column.enqueue_block_sources();
        column.propagate(Layer::Block, table);
        column.enqueue_sky_sources();
        column.propagate(Layer::Sky, table);
    }
}

#[test]
fn torch_light_falls_off_by_one_per_block() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    scene.set(8, 8, 8, TORCH);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    for (x, y, z, expected) in [
        (8, 8, 8, 14),
        (9, 8, 8, 13),
        (8, 12, 8, 10),
        (0, 8, 8, 6),
        (0, 0, 0, 0),
        (12, 12, 12, 2),
    ] {
        assert_eq!(
            column.level(Layer::Block, x, y, z),
            expected,
            "block light at {x} {y} {z}"
        );
    }
    // Torch light reaches the padding sections above and below the world.
    assert_eq!(column.level(Layer::Block, 8, 17, 8), 5);
    assert_eq!(column.level(Layer::Block, 8, -1, 8), 5);
}

#[test]
fn sky_light_fills_open_air_and_creeps_under_a_roof() {
    let table = table();
    let mut scene = Scene::new(2, 0);
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..4 {
                scene.set(x, y, z, STONE);
            }
        }
    }
    for x in 4..9 {
        for z in 4..9 {
            scene.set(x, 10, z, STONE);
        }
    }
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.lowest_source(0, 0), 4);
    assert_eq!(column.lowest_source(6, 6), 11);
    assert_eq!(column.level(Layer::Sky, 0, 20, 0), 15);
    assert_eq!(column.level(Layer::Sky, 0, 4, 0), 15);
    assert_eq!(column.level(Layer::Sky, 0, 3, 0), 0);
    assert_eq!(column.level(Layer::Sky, 6, 11, 6), 15);
    assert_eq!(column.level(Layer::Sky, 6, 5, 6), 12);
    assert_eq!(column.level(Layer::Sky, 4, 9, 4), 14);
}

#[test]
fn slabs_block_light_through_their_full_faces() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    // A torch with a bottom slab right above it: light cannot go up through
    // the slab's full bottom face, so the cell above the slab is lit around it.
    scene.set(8, 4, 8, TORCH);
    scene.set(8, 5, 8, BOTTOM_SLAB);
    scene.set(3, 4, 3, TORCH);
    scene.set(3, 5, 3, TOP_SLAB);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.level(Layer::Block, 8, 5, 8), 11);
    assert_eq!(column.level(Layer::Block, 3, 5, 3), 13);
}

#[test]
fn removing_a_torch_darkens_the_column() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    scene.set(8, 8, 8, TORCH);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    column.set_props(8, 8, 8, 0);
    column.compute_sources(&table);
    column.check_block(8, 8, 8);
    column.propagate(Layer::Block, &table);
    for y in -1..17 {
        for z in 0..16 {
            for x in 0..16 {
                assert_eq!(column.level(Layer::Block, x, y, z), 0);
            }
        }
    }
}

#[test]
fn sky_reaches_the_ring_and_reports_it() {
    let table = table();
    let mut scene = Scene::new(1, 1 << SIDE_EAST);
    // A stone roof over the east neighbour's touching column keeps its sky
    // sources high, so light leaks sideways out of the column below the roof.
    for z in 0..16 {
        for y in 0..16 {
            scene.states[1 + SIDE_EAST][0][(y << 8) | (z << 4)] = if y == 12 { STONE } else { AIR };
        }
    }
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.ring_level(Layer::Sky, SIDE_EAST, 5, 6), 14);
    let records: Vec<_> = column
        .outgoing()
        .iter()
        .filter(|r| r.side == SIDE_EAST as u8 && r.y == 6)
        .collect();
    assert!(records
        .iter()
        .any(|r| r.level == 14 && r.kind == crate::outgoing::SKY_INCREASE));
}

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn random_scene(seed: u64) -> Scene {
    let mut rng = Rng(seed);
    let mut scene = Scene::new(3, 0b1111);
    let palette = [
        AIR,
        AIR,
        AIR,
        AIR,
        STONE,
        STONE,
        GLASS,
        TORCH,
        WATER,
        BOTTOM_SLAB,
        TOP_SLAB,
        GLOWSTONE,
        LEAVES,
    ];
    for slot in 0..5 {
        for section in 0..scene.sections {
            let density = rng.below(4);
            for cell in 0..4096 {
                let id = if rng.below(4) < density {
                    palette[rng.below(palette.len() as u64) as usize]
                } else {
                    AIR
                };
                scene.states[slot][section][cell] = id;
            }
        }
        for light_section in 0..scene.sections + 2 {
            scene.flags[slot][light_section] = match rng.below(8) {
                0 => flags::SKY_STORING,
                1 => flags::BLOCK_STORING,
                _ => flags::SKY_STORING | flags::BLOCK_STORING,
            };
            if slot > 0 {
                let (sky, block) = &mut scene.ring_layers[slot][light_section];
                for byte in sky.iter_mut().chain(block.iter_mut()) {
                    *byte = if rng.below(3) == 0 { rng.next() as u8 } else { 0 };
                }
            }
        }
    }
    scene
}

/// Sky levels, block levels (column then ring, by y) and the outgoing records.
type Snapshot = (Vec<u8>, Vec<u8>, BTreeMap<(u8, u8, u8, i32), (u8, u16)>);

fn snapshot(column: &LightColumn, sections: usize) -> Snapshot {
    let mut levels = (Vec::new(), Vec::new());
    for y in -16..(sections as i32 + 1) * 16 {
        for z in 0..16 {
            for x in 0..16 {
                levels.0.push(column.level(Layer::Sky, x, y, z));
                levels.1.push(column.level(Layer::Block, x, y, z));
            }
        }
        for side in [SIDE_NORTH, SIDE_SOUTH, SIDE_WEST, SIDE_EAST] {
            for along in 0..16 {
                levels.0.push(column.ring_level(Layer::Sky, side, along, y));
                levels.1.push(column.ring_level(Layer::Block, side, along, y));
            }
        }
    }
    // Vanilla order may raise a ring cell several times; the last (highest)
    // report is the one that sticks.
    let mut records = BTreeMap::new();
    for r in column.outgoing() {
        let slot = records.entry((r.kind, r.side, r.along, r.y)).or_insert((0u8, 0u16));
        if r.level >= slot.0 {
            *slot = (r.level, r.count);
        }
    }
    (levels.0, levels.1, records)
}

#[test]
fn bucket_order_matches_vanilla_order_on_random_columns() {
    let table = table();
    for seed in 1..41u64 {
        let scene = random_scene(seed.wrapping_mul(0x9e37_79b9_7f4a_7c15));
        let mut fast = LightColumn::new();
        let mut vanilla = LightColumn::new();
        vanilla.set_vanilla_increase_order(true);
        scene.light_first_time(&mut fast, &table);
        scene.light_first_time(&mut vanilla, &table);
        let (a, b) = (snapshot(&fast, scene.sections), snapshot(&vanilla, scene.sections));
        let sky_diff = a.0.iter().zip(&b.0).filter(|(x, y)| x != y).count();
        let block_diff = a.1.iter().zip(&b.1).filter(|(x, y)| x != y).count();
        let rec_diff: Vec<_> = a.2.iter().filter(|(k, v)| b.2.get(k) != Some(v)).take(5).collect();
        let rec_diff2: Vec<_> = b.2.iter().filter(|(k, v)| a.2.get(k) != Some(v)).take(5).collect();
        assert!(
            a == b,
            "first light, seed {seed}: sky {sky_diff} block {block_diff} records {rec_diff:?} / {rec_diff2:?}"
        );

        // Then a burst of block changes through checkNode.
        let mut rng = Rng(seed);
        let mut changes = Vec::new();
        for _ in 0..12 {
            let (x, y, z) = (rng.below(16) as i32, rng.below(48) as i32, rng.below(16) as i32);
            let id = [AIR, STONE, TORCH, GLOWSTONE, BOTTOM_SLAB][rng.below(5) as usize];
            changes.push((x, y, z, table.props(id as u32).unwrap()));
        }
        for column in [&mut fast, &mut vanilla] {
            for &(x, y, z, props) in &changes {
                column.set_props(x, y, z, props);
            }
            column.compute_sources(&table);
            for &(x, y, z, _) in &changes {
                column.check_block(x, y, z);
            }
            column.propagate(Layer::Block, &table);
            for &(x, y, z, _) in &changes {
                column.check_sky(x, y, z);
            }
            column.propagate(Layer::Sky, &table);
        }
        let (a, b) = (snapshot(&fast, scene.sections), snapshot(&vanilla, scene.sections));
        let sky_diff: Vec<_> =
            a.0.iter()
                .zip(&b.0)
                .enumerate()
                .filter(|(_, (x, y))| x != y)
                .take(6)
                .collect();
        let block_diff: Vec<_> =
            a.1.iter()
                .zip(&b.1)
                .enumerate()
                .filter(|(_, (x, y))| x != y)
                .take(6)
                .collect();
        assert!(
            a.0 == b.0 && a.1 == b.1,
            "after changes, seed {seed}: sky {sky_diff:?} block {block_diff:?} changes {changes:?}"
        );
    }
}
