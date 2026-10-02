#!/usr/bin/env bash
# Builds the wasm kernels (release, SIMD128) and prints their sizes.
#
#   port/native/build-wasm.sh          build into target/wasm32-unknown-unknown/release/
#   port/native/build-wasm.sh --check  build, then check exports and that every probe
#                                      job answers bit for bit like the native build (test)
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET=wasm32-unknown-unknown
OUT="$HERE/target/$TARGET/release"
# crate name -> run_<kind> exports it must provide
KERNELS=("gaius_noise_wasm:run_noise_points")

check=0
case "${1:-}" in
    "") ;;
    --check) check=1 ;;
    *) printf 'usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

die() {
    printf 'build-wasm: %s\n' "$1" >&2
    exit 1
}

command -v cargo > /dev/null || die "cargo not found"
rustup target list --installed 2>/dev/null | grep -qx "$TARGET" || die "missing rust target $TARGET (rustup target add $TARGET)"

cd "$HERE"
# Scoped to the wasm target so host builds and tests keep their own cache.
export CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128"
packages=()
for entry in "${KERNELS[@]}"; do
    packages+=(-p "${entry%%:*}")
done
cargo build --release --locked --target "$TARGET" "${packages[@]//_/-}"

for entry in "${KERNELS[@]}"; do
    wasm="$OUT/${entry%%:*}.wasm"
    [[ -f "$wasm" ]] || die "missing $wasm"
    printf '%-24s %8d bytes  %s\n' "${entry%%:*}.wasm" "$(wc -c < "$wasm")" "$wasm"
done

if (( check )); then
    command -v node > /dev/null || die "node not found (needed for --check)"
    probe="$HERE/target/wasm-probe"
    rm -rf "$probe"
    cargo run --quiet --locked -p gaius-noise-wasm --example wasm_probe -- "$probe"
    for entry in "${KERNELS[@]}"; do
        crate="${entry%%:*}"
        IFS=',' read -r -a exports <<< "${entry#*:}"
        node "$HERE/wasm-check.mjs" "$OUT/$crate.wasm" "$probe" "${exports[@]}"
    done
fi
