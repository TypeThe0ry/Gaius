//! Bit-exact parity against the golden fixtures dumped from the real client
//! jars (`port/native/fixtures/<profile>/*.jsonl`, written by
//! `port/native/golden/run-golden.sh`).
//!
//! `GAIUS_FIXTURES` overrides the fixture directory.

mod support;

use gaius_noise::Profile;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};
use support::json::Case;

/// Mismatches printed per fixture file before the rest are only counted.
const SHOWN_PER_FILE: usize = 4;

/// Every profile must ship one `<kind>.jsonl` per fixture kind.
const KINDS: [&str; 11] = [
    "xoroshiro_next_long",
    "xoroshiro_next_double",
    "legacy_next_int_bound",
    "legacy_next_double",
    "positional_from_hash",
    "mth",
    "improved_noise",
    "perlin_noise",
    "normal_noise",
    "simplex_noise",
    "blended_noise",
];

fn fixtures_dir() -> PathBuf {
    match std::env::var_os("GAIUS_FIXTURES") {
        Some(dir) => PathBuf::from(dir),
        None => Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures"),
    }
}

fn fixture_files(root: &Path) -> Vec<(Profile, PathBuf)> {
    let mut files = Vec::new();
    for profile in Profile::ALL {
        let Ok(entries) = fs::read_dir(root.join(profile.name())) else {
            continue;
        };
        let mut paths: Vec<PathBuf> = entries
            .filter_map(|entry| entry.ok().map(|e| e.path()))
            .filter(|path| path.extension().is_some_and(|ext| ext == "jsonl"))
            .collect();
        paths.sort();
        files.extend(paths.into_iter().map(|path| (profile, path)));
    }
    files
}

fn describe_mismatch(case: &Case, expected: &[serde_json::Value], actual: &[serde_json::Value]) -> String {
    if expected.len() != actual.len() {
        return format!("{} outputs expected, {} computed", expected.len(), actual.len());
    }
    let (index, (want, got)) = expected
        .iter()
        .zip(actual)
        .enumerate()
        .find(|(_, (want, got))| want != got)
        .expect("a differing output");
    let differing = expected.iter().zip(actual).filter(|(want, got)| want != got).count();
    let input = case
        .inputs
        .get(index)
        .map(|row| format!(" input {}", serde_json::Value::from(row.clone())))
        .unwrap_or_default();
    format!(
        "{differing}/{} outputs differ; first at #{index}{input}: expected {want}, computed {got}",
        expected.len()
    )
}

#[test]
fn golden_fixtures_match_bit_for_bit() {
    let root = fixtures_dir();
    let files = fixture_files(&root);
    assert!(
        !files.is_empty(),
        "no golden fixtures under {} (expected <profile>/*.jsonl for {:?}); generate them with \
         port/native/golden/run-golden.sh",
        root.display(),
        Profile::ALL.map(Profile::name),
    );
    let missing: Vec<&str> = Profile::ALL
        .into_iter()
        .filter(|profile| !files.iter().any(|(p, _)| p == profile))
        .map(Profile::name)
        .collect();
    assert!(
        missing.is_empty(),
        "no golden fixtures for profile(s) {missing:?} under {}; generate them with port/native/golden/run-golden.sh",
        root.display(),
    );

    let absent: Vec<String> = Profile::ALL
        .into_iter()
        .flat_map(|profile| KINDS.map(move |kind| (profile, kind)))
        .filter(|(profile, kind)| {
            !files
                .iter()
                .any(|(p, path)| p == profile && path.file_stem().unwrap() == *kind)
        })
        .map(|(profile, kind)| format!("{}/{kind}.jsonl", profile.name()))
        .collect();
    assert!(
        absent.is_empty(),
        "missing golden fixture files {absent:?} under {}",
        root.display()
    );

    let mut report = String::new();
    let (mut total_cases, mut total_outputs, mut failed_cases) = (0usize, 0usize, 0usize);
    for (profile, path) in &files {
        let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        let label = format!("{}/{}", profile.name(), path.file_name().unwrap().to_string_lossy());
        let mut cases = Vec::new();
        for (number, line) in text.lines().enumerate().filter(|(_, line)| !line.trim().is_empty()) {
            match Case::parse(line) {
                Ok(case) if path.file_stem().is_some_and(|stem| *stem != *case.kind) => {
                    failed_cases += 1;
                    let _ = writeln!(
                        report,
                        "{label}:{}: kind \"{}\" in the wrong file",
                        number + 1,
                        case.kind
                    );
                }
                Ok(case) => cases.push((number + 1, case)),
                Err(error) => {
                    failed_cases += 1;
                    let _ = writeln!(report, "{label}:{}: {error}", number + 1);
                }
            }
        }
        let mut shown = 0;
        let mut file_failures = 0;
        for (line, case) in &cases {
            total_cases += 1;
            total_outputs += case.outputs.len();
            let problem = match support::evaluate(*profile, case) {
                Ok(actual) if actual == case.outputs => None,
                Ok(actual) => Some(describe_mismatch(case, &case.outputs, &actual)),
                Err(error) => Some(error),
            };
            if let Some(problem) = problem {
                file_failures += 1;
                if shown < SHOWN_PER_FILE {
                    shown += 1;
                    let params = serde_json::Value::Object(case.params.clone());
                    let _ = writeln!(report, "{label}:{line} [{}] {params}\n    {problem}", case.kind);
                }
            }
        }
        if file_failures > shown {
            let _ = writeln!(report, "{label}: {} more failing lines", file_failures - shown);
        }
        failed_cases += file_failures;
    }
    println!(
        "checked {total_cases} fixture lines ({total_outputs} outputs) in {} files",
        files.len()
    );
    assert!(
        failed_cases == 0,
        "{failed_cases} of {total_cases} fixture lines failed:\n{report}"
    );
}
