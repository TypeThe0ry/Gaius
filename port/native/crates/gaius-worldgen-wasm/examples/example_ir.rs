//! Writes the example generator IRs (`26.2.gwir`, `26.3.gwir`) into a directory, for
//! exercising the wasm kernel and `port/web/kernels/worldgen-job.js` from node.
//!
//!   cargo run -p gaius-worldgen-wasm --example example_ir -- <out-dir>

use gaius_noise::Profile;
use gaius_worldgen::ir::example::overworld;

fn main() {
    let dir = std::env::args().nth(1).expect("usage: example_ir <out-dir>");
    std::fs::create_dir_all(&dir).expect("output directory");
    for profile in [Profile::V26_2, Profile::V26_3] {
        let path = std::path::Path::new(&dir).join(format!("{}.gwir", profile.name()));
        std::fs::write(&path, overworld(profile, 1234).encode()).expect("write IR");
        println!("{}", path.display());
    }
}
