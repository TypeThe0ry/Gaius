#!/usr/bin/env bash
# Shader translation corpus test (v0.4.0 spike S4): dumps the 26.3 pipeline corpus, translates
# every module natively, builds the wasm module and checks it in Node, and with --chrome compiles
# every WGSL module and builds every pipeline in headless Chrome WebGPU.
#
#   port/native/golden/shaders/run-corpus.sh [--profile webgpu|gl] [--no-dump] [--chrome]
#                                            [--swiftshader] [--report FILE]
#
# --no-dump reuses port/native/target/shader-corpus/<profile>; --swiftshader runs the Chrome stage
# on Dawn's SwiftShader adapter (no GPU needed). Outputs land in port/native/target/shaderxlat-out.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd -- "$HERE/../.." && pwd)"
CRATE="$NATIVE/crates/gaius-shaderxlat"
PROFILE=webgpu
dump=1
stages=wasm
chrome_args=()
report=()
while (( $# > 0 )); do
    case "$1" in
        --profile) PROFILE="${2:?--profile needs webgpu or gl}"; shift 2 ;;
        --no-dump) dump=0; shift ;;
        --chrome) stages=wasm,chrome; shift ;;
        --swiftshader)
            stages=wasm,chrome
            chrome_args=(--fallback-adapter true --chrome-args "--enable-unsafe-webgpu --use-webgpu-adapter=swiftshader --enable-unsafe-swiftshader")
            shift ;;
        --report) report=(--report "${2:?--report needs a file}"); shift 2 ;;
        *) printf 'usage: %s [--profile webgpu|gl] [--no-dump] [--chrome] [--swiftshader] [--report FILE]\n' "$0" >&2; exit 2 ;;
    esac
done
CORPUS="$NATIVE/target/shader-corpus/$PROFILE"
OUT="$NATIVE/target/shaderxlat-out/$PROFILE"

step() {
    printf '\n== %s\n' "$1"
}

dumped=0
if (( dump )) || [[ ! -f "$CORPUS/corpus.json" ]]; then
    step "dump corpus ($PROFILE)"
    "$HERE/dump-corpus.sh" --profile "$PROFILE" --out "$CORPUS"
    [[ "$PROFILE" == webgpu ]] && dumped=1
fi

step "native translation"
# A webgpu dump has just refreshed the test fixtures: then the tests must not skip them.
(cd "$CRATE" && GAIUS_SHADERXLAT_REQUIRE_FIXTURES="$dumped" \
    CARGO_TARGET_DIR="$NATIVE/target/shaderxlat" cargo test --locked -p gaius-shaderxlat -p gaius-shaderxlat-wasm --quiet)
(cd "$CRATE" && CARGO_TARGET_DIR="$NATIVE/target/shaderxlat" cargo build --locked --release --quiet --features corpus --bin shaderxlat-corpus)
rm -rf "$OUT"
"$NATIVE/target/shaderxlat/release/shaderxlat-corpus" "$CORPUS" "$OUT"

step "wasm build (simd128)"
(cd "$CRATE" && CARGO_TARGET_DIR="$NATIVE/target/shaderxlat-wasm-simd" \
    CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-feature=+simd128" \
    cargo build --locked --profile release-size --quiet --target wasm32-unknown-unknown -p gaius-shaderxlat-wasm)

step "check ($stages)"
node "$HERE/xlat-check.mjs" --stages "$stages" --corpus "$CORPUS" --native "$OUT" ${chrome_args[@]+"${chrome_args[@]}"} ${report[@]+"${report[@]}"}
