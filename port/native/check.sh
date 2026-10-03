#!/usr/bin/env bash
# One command for the whole native kernel stack: golden fixtures, Rust parity and unit tests,
# the release wasm builds of every kernel and the node checks of the modules, the kernel pool and
# the kernel runtime.
#
#   port/native/check.sh           regenerate fixtures only when missing or older than the
#                                  golden harness or the client jars, then run everything
#   port/native/check.sh --golden  always regenerate the fixtures first
#   port/native/check.sh --verify  also prove a fresh JVM dump reproduces the fixtures
#   port/native/check.sh --no-golden  never touch the fixtures (no JDK or jars needed); also
#                                  skips the JVM check of the StrictMath263 worldgen rewrite
#   port/native/check.sh --teavm[=classes.js]  also run the golden fixtures through the
#                                  TeaVM-compiled Java noise of a non-minified 26.3 client build
#                                  (default: port/target/26.3/boot-dist/classes.js)
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../.." && pwd)"
PROFILES=(26.3 26.2 1.21.11)
WASM="$HERE/target/wasm32-unknown-unknown/release/gaius_noise_wasm.wasm"

golden=auto
verify=0
teavm=()
for arg in "$@"; do
    case "$arg" in
        --golden) golden=always ;;
        --no-golden) golden=never ;;
        --verify) verify=1 ;;
        --teavm) teavm=(--) ;;
        --teavm=*) teavm=(-- "${arg#--teavm=}") ;;
        *) printf 'usage: %s [--golden | --no-golden] [--verify] [--teavm[=classes.js]]\n' "$0" >&2; exit 2 ;;
    esac
done

step() {
    printf '\n== %s\n' "$1"
}

die() {
    printf 'check: %s\n' "$1" >&2
    exit 1
}

# Lists the profiles whose fixtures are missing, or older than the golden harness or the
# client jar. Without the jar (a checkout that never ran the client build) existing fixtures
# are trusted as checked in.
stale_profiles() {
    local work="${GAIUS_WORK:-}"
    if [[ -z "$work" ]]; then
        work="$(dirname -- "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)")/port/work"
    fi
    local profile jar oldest
    for profile in "${PROFILES[@]}"; do
        jar="$work/$profile/client-named.jar"
        oldest="$(ls -tr "$HERE/fixtures/$profile"/*.jsonl 2>/dev/null | head -n 1 || true)"
        if [[ -z "$oldest" ]]; then
            printf '%s\n' "$profile"
        elif [[ -f "$jar" && -n "$(find "$HERE/golden/src" "$HERE/golden/run-golden.sh" "$jar" \
            -type f -newer "$oldest" -print -quit)" ]]; then
            printf '%s\n' "$profile"
        fi
    done
}

cd "$HERE"
case "$golden" in
    always)
        step "golden fixtures (all profiles)"
        ./golden/run-golden.sh "${PROFILES[@]}"
        ;;
    auto)
        mapfile -t stale < <(stale_profiles)
        if (( ${#stale[@]} > 0 )); then
            step "golden fixtures (${stale[*]})"
            ./golden/run-golden.sh "${stale[@]}"
        else
            step "golden fixtures are up to date"
        fi
        ;;
    never) step "golden fixtures left as they are" ;;
esac
if (( verify )); then
    step "golden fixtures reproduce from the jars"
    ./golden/run-golden.sh --verify "${PROFILES[@]}"
fi

if [[ "$golden" != never ]]; then
    step "StrictMath263 worldgen rewrite keeps the 26.3 fixtures (JVM)"
    ./golden/strict/strict-math-check.sh
fi

step "cargo fmt / clippy"
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked --quiet -- -D warnings

step "cargo test (unit tests, golden parity for every profile, kernel framing)"
cargo test --workspace --locked --quiet

step "wasm build (release, simd128) and native == wasm probes"
./build-wasm.sh --check

command -v node > /dev/null || die "node not found"
step "wasm kernel against the golden fixtures"
node "$HERE/wasm-fixture-check.mjs" "$WASM"

step "light kernel (simd128 and baseline) through light-job.js"
./build-light-wasm.sh --check

step "worldgen kernel (simd128 and baseline) and its pool"
./build-worldgen-wasm.sh --check
cargo run --quiet --locked -p gaius-worldgen-wasm --example example_ir -- target/wasm-worldgen
node "$ROOT/port/scripts/worldgen-kernel-pool-smoke.mjs"

step "kernel pool runtime (simulated workers)"
node "$ROOT/port/scripts/kernel-pool-smoke.mjs"

step "kernel runtime: priorities, budget, primers, trimming (simulated workers)"
node "$ROOT/port/scripts/kernel-runtime-smoke.mjs"

step "noise kernel through the kernel pool (worker threads)"
node "$ROOT/port/scripts/native-noise-pool-smoke.mjs" "$WASM"

if (( ${#teavm[@]} > 0 )); then
    step "TeaVM-compiled Java noise against the 26.3 golden fixtures"
    node "$HERE/teavm-fixture-check.mjs" "${teavm[@]:1}"
fi

printf '\ncheck: all native kernel checks passed\n'
