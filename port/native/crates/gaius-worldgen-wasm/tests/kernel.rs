//! Drives the worldgen jobs through the full kernel framing natively.

use gaius_kernel_abi::{
    release_descriptor, run_kernel, JobHeader, ResultHeader, RunDescriptor, Status, RESULT_HEADER_LEN,
};
use gaius_noise::Profile;
use gaius_worldgen::beard::Beard;
use gaius_worldgen::ir::example::overworld;
use gaius_worldgen::Generator;
use gaius_worldgen_wasm::job::{self, encode_surface, encode_surface_with, encode_terrain, write_ref, write_ring};

fn run(
    kind: u16,
    payload: &[u8],
    handler: fn(&[u8]) -> Result<Vec<u8>, gaius_kernel_abi::KernelError>,
) -> (u32, Vec<u8>) {
    let framed = JobHeader::frame(kind, 9, payload);
    let desc = run_kernel(&framed, kind, handler);
    assert!(!desc.is_null());
    // SAFETY: the descriptor and its data were just produced by run_kernel.
    unsafe {
        let head = std::slice::from_raw_parts(desc, RunDescriptor::LEN);
        let status = u32::from_le_bytes(head[0..4].try_into().unwrap());
        let len = u32::from_le_bytes(head[8..12].try_into().unwrap()) as usize;
        let data = std::slice::from_raw_parts(desc.add(RunDescriptor::LEN), len).to_vec();
        release_descriptor(desc);
        (status, data)
    }
}

fn payload_of(data: &[u8]) -> &[u8] {
    let header = ResultHeader::parse(data).expect("result header");
    assert_eq!(header.job_id, 9);
    &data[RESULT_HEADER_LEN..]
}

fn i32_at(b: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

#[test]
fn jobs_round_trip_through_the_framing() {
    for (key, profile) in [(1u32, Profile::V26_2), (2, Profile::V26_3)] {
        job::clear_cache();
        let ir = overworld(profile, 555).encode();

        // A job without the IR fails until the generator is loaded.
        let lazy = encode_terrain(key, &ir, false, 0, 0, job::FLAG_SURFACE, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &lazy, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with("generator-missing:"));

        let mut load = Vec::new();
        write_ref(&mut load, key, &ir, true);
        load.extend_from_slice(&0u32.to_le_bytes());
        let (status, data) = run(job::KIND_LOAD_GENERATOR, &load, job::load_generator);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let info = payload_of(&data);
        assert_eq!(i32_at(info, 20), 24, "section count");

        let (status, data) = run(job::KIND_TERRAIN, &lazy, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let chunk = payload_of(&data).to_vec();
        assert_eq!(i32_at(&chunk, 8), -64);
        assert_eq!(i32_at(&chunk, 12), 24);

        // The surface job over a plain fill gives the same chunk as the inline surface.
        let plain = encode_terrain(key, &ir, false, 0, 0, 0, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &plain, job::terrain);
        assert_eq!(status, 0);
        let filled = payload_of(&data).to_vec();
        let surface = encode_surface(key, &ir, false, 0, 0, &Beard::default(), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let surfaced = payload_of(&data);
        assert_eq!(
            &surfaced[24..],
            &chunk[24..],
            "{profile:?}: surface job matches the inline surface"
        );

        let mut biomes = Vec::new();
        write_ref(&mut biomes, key, &ir, false);
        biomes.extend_from_slice(&0i32.to_le_bytes());
        biomes.extend_from_slice(&0i32.to_le_bytes());
        let (status, data) = run(job::KIND_BIOMES, &biomes, job::biomes);
        assert_eq!(status, 0);
        let b = payload_of(&data);
        assert_eq!(b.len(), 8 + 24 * 64 * 4);
    }
}

/// The ring a server would send for chunk (cx, cz) when every neighbour stores the biomes this
/// generator computes for it.
fn computed_ring(generator: &mut Generator, cx: i32, cz: i32) -> Vec<u32> {
    let size_y = generator.section_count() as i32 * 4;
    let mut neighbours = std::collections::HashMap::new();
    let mut ring = Vec::new();
    for gx in 0..6 {
        for gz in 0..6 {
            if (1..=4).contains(&gx) && (1..=4).contains(&gz) {
                continue;
            }
            let (qx, qz) = (cx * 4 - 1 + gx, cz * 4 - 1 + gz);
            let ids = neighbours
                .entry((qx >> 2, qz >> 2))
                .or_insert_with(|| generator.run_biomes(qx >> 2, qz >> 2));
            for y in 0..size_y {
                let at = (y >> 2) * 64 + ((y & 3) * 4 + (qz & 3)) * 4 + (qx & 3);
                ring.push(ids[at as usize]);
            }
        }
    }
    ring
}

#[test]
fn ring_biomes_reach_terrain_and_surface_jobs() {
    for (key, profile) in [(11u32, Profile::V26_2), (12, Profile::V26_3)] {
        job::clear_cache();
        let ir = overworld(profile, 777).encode();
        let mut generator = Generator::load(&ir).expect("loads");
        let (cx, cz) = (-2, 3);
        let ring = computed_ring(&mut generator, cx, cz);
        assert_eq!(ring.len(), generator.ring_len());

        let plain = encode_terrain(key, &ir, true, cx, cz, job::FLAG_SURFACE, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &plain, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let expected = payload_of(&data).to_vec();

        // Neighbours storing what the kernel computes change nothing.
        let mut seeded = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut seeded, &ring);
        let (status, data) = run(job::KIND_TERRAIN, &seeded, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert_eq!(payload_of(&data), &expected[..], "{profile:?}: ring of computed biomes");

        let fill = encode_terrain(key, &ir, false, cx, cz, 0, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &fill, job::terrain);
        assert_eq!(status, 0);
        let filled = payload_of(&data).to_vec();
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&ring), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert_eq!(
            &payload_of(&data)[24..],
            &expected[24..],
            "{profile:?}: surface job with a ring"
        );

        // The surface rules read the ring: neighbours that all store the example's badlands
        // (global id 42, clay bands) change the border columns.
        assert!(
            ring.iter().any(|&id| id != 42),
            "{profile:?}: pick a chunk outside the badlands"
        );
        let badlands = vec![42u32; ring.len()];
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&badlands), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert!(
            payload_of(&data)[24..] != expected[24..],
            "{profile:?}: the surface rules ignored the ring"
        );

        // A neighbour biome outside the generator's table only refuses this chunk.
        let mut foreign = ring.clone();
        foreign[7] = 0x7fff_0000;
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&foreign), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
        let mut unknown = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut unknown, &foreign);
        let (status, data) = run(job::KIND_TERRAIN, &unknown, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));

        // A ring of the wrong size is a broken job, not a fallback.
        let mut short = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut short, &ring[1..]);
        let (status, data) = run(job::KIND_TERRAIN, &short, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(!String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
    }
}
