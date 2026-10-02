//! A few quick checks with tiny synthetic tables and sections, through the
//! same binary formats the Java exporter and the page write.

use crate::job::{flags as jf, SectionJobData};
use crate::output::{self, STATUS_MESHED, STATUS_NEEDS_VANILLA, STATUS_TABLE_MISSING};
use crate::table::*;
use crate::Kernel;

use crate::synthetic::{self, ODD, STONE, WATER};

fn table() -> ModelTable {
    synthetic::table(7)
}

fn kernel() -> Kernel {
    let mut k = Kernel::new();
    let bytes = TableWriter { table: table() }.encode();
    let info = k.load_model_table(&bytes).expect("table loads");
    assert_eq!(u32::from_le_bytes(info[0..4].try_into().unwrap()), 7);
    k
}

fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

fn f32_at(b: &[u8], at: usize) -> f32 {
    f32::from_bits(u32_at(b, at))
}

/// (quad_count, vanilla_offset) of a layer.
fn layer(b: &[u8], k: usize) -> (u32, usize) {
    (u32_at(b, 64 + 32 * k), u32_at(b, 64 + 32 * k + 4) as usize)
}

#[test]
fn parse_rejects_bad_indices() {
    let mut t = table();
    t.quad_refs[0] = 99;
    assert!(ModelTable::parse(&TableWriter { table: t }.encode()).is_err());
    let t = table();
    let parsed = ModelTable::parse(&TableWriter { table: t.clone() }.encode()).unwrap();
    assert_eq!(parsed.states, t.states);
    assert_eq!(parsed.quads, t.quads);
}

#[test]
fn lone_cube_emits_six_lit_faces() {
    let mut k = kernel();
    let mut job = SectionJobData::empty(7);
    job.section = [0, 4, 0];
    job.set(3, 4, 5, STONE);
    let r = k.mesh_section(&job.encode()).unwrap();
    assert_eq!(u32_at(&r, 0), STATUS_MESHED);
    let (quads, at) = layer(&r, 0);
    assert_eq!(quads, 6);
    assert_eq!(layer(&r, 1).0 + layer(&r, 2).0, 0);
    // DOWN face: AO with air all around is 1.0, CardinalLighting.down 0.5 -> gray 127.
    let v0 = &r[at..at + 28];
    assert_eq!(f32_at(v0, 0), 3.0);
    assert_eq!(f32_at(v0, 4), 4.0);
    assert_eq!(f32_at(v0, 8), 6.0);
    assert_eq!(&v0[12..16], &[127, 127, 127, 255]);
    // Full skylight, no block light: sky 15 << 4 in the high short.
    assert_eq!(u32_at(v0, 24), 0x00F0_0000);
    // UP face is full white.
    let up = &r[at + 4 * 28..at + 5 * 28];
    assert_eq!(&up[12..16], &[255, 255, 255, 255]);
    assert_eq!(
        u32_at(&r, 28) as u64 | (u32_at(&r, 32) as u64) << 32,
        crate::ALL_VISIBLE
    );
}

#[test]
fn touching_cubes_cull_shared_faces_and_darken_corners() {
    let mut k = kernel();
    let mut job = SectionJobData::empty(7);
    job.set(3, 4, 5, STONE);
    job.set(4, 4, 5, STONE);
    job.set(4, 5, 5, STONE);
    let r = k.mesh_section(&job.encode()).unwrap();
    let (quads, at) = layer(&r, 0);
    // 18 faces minus the two shared pairs.
    assert_eq!(quads, 14);
    // The first cube's UP face (its second quad) has the third cube on its east corner:
    // AO (1 + 0.2 + 1 + 1) / 4 = 0.8 there, 1.0 on the far side.
    let up = &r[at + 28 * 4..at + 28 * 8];
    let mut grays: Vec<u8> = (0..4).map(|v| up[v * 28 + 12]).collect();
    grays.sort_unstable();
    assert_eq!(grays, [204, 204, 255, 255]);
}

#[test]
fn water_source_renders_translucent_faces_with_backfaces_and_sorts() {
    let mut k = kernel();
    let mut job = SectionJobData::empty(7);
    job.flags |= jf::EMIT_CENTROIDS | jf::EMIT_COMPACT;
    job.set(8, 8, 8, WATER);
    let r = k.mesh_section(&job.encode()).unwrap();
    let (quads, at) = layer(&r, 2);
    // Up + backface, down, four sides + backfaces.
    assert_eq!(quads, 11);
    // Water tint (default palette water color) scaled by CardinalLighting.up = 1.
    let water = -12_618_012i32;
    let v = &r[at..at + 28];
    assert_eq!(&v[12..16], &[(water >> 16) as u8, (water >> 8) as u8, water as u8, 255]);
    // Corner height of a lone source block: (8/9 * 10) / 12 weighted with two dry sides, - 0.001.
    let corner = (0.8888889f32 * 10.0 + 0.0 + 0.0) / 12.0;
    assert_eq!(f32_at(v, 4), 8.0 + (corner - 0.001));
    let order_at = u32_at(&r, 64 + 32 * 2 + 16) as usize;
    assert_ne!(order_at, 0);
    let mut order: Vec<u32> = (0..quads).map(|q| u32_at(&r, order_at + 4 * q as usize)).collect();
    order.sort_unstable();
    assert_eq!(order, (0..quads).collect::<Vec<_>>());
    assert_ne!(u32_at(&r, 64 + 32 * 2 + 8), 0, "compact vertices present");
}

#[test]
fn missing_table_and_unsupported_states_fall_back() {
    let mut k = Kernel::new();
    let mut job = SectionJobData::empty(7);
    job.set(1, 1, 1, STONE);
    let r = k.mesh_section(&job.encode()).unwrap();
    assert_eq!(u32_at(&r, 0), STATUS_TABLE_MISSING);
    job.inline_table = TableWriter { table: table() }.encode();
    let r = k.mesh_section(&job.encode()).unwrap();
    assert_eq!(u32_at(&r, 0), STATUS_MESHED);
    assert_eq!(k.epoch(), Some(7));

    let mut job = SectionJobData::empty(7);
    job.set(2, 2, 2, ODD);
    let r = k.mesh_section(&job.encode()).unwrap();
    assert_eq!(u32_at(&r, 0), STATUS_NEEDS_VANILLA);
    assert_eq!(u32_at(&r, 52), ODD);
    assert_eq!(r.len(), output::RESULT_HEADER_LEN);
}

#[test]
fn scenes_mesh_every_path_and_repeat_exactly() {
    let mut k = kernel();
    for seed in 1..4 {
        let mut job = synthetic::scene(7, seed);
        job.flags |= jf::EMIT_COMPACT | jf::EMIT_CENTROIDS;
        let a = k.mesh_section(&job.encode()).unwrap();
        assert_eq!(u32_at(&a, 0), STATUS_MESHED);
        for l in 0..3 {
            assert!(layer(&a, l).0 > 0, "seed {seed} layer {l} is empty");
        }
        // The same job twice gives the same bytes (no state leaks between jobs).
        assert_eq!(a, k.mesh_section(&job.encode()).unwrap());
        job.flags &= !jf::AMBIENT_OCCLUSION;
        let flat = k.mesh_section(&job.encode()).unwrap();
        assert_eq!(layer(&flat, 0).0, layer(&a, 0).0);
    }
}
