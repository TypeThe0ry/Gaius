#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source_file="$root/port/wasm/hotpath/gaius_hotpath.c"
dist="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist}"
output="$dist/gaius-hotpath.wasm"

if ! command -v clang >/dev/null 2>&1; then
  echo "clang is required to build the Gaius Wasm hot-path module" >&2
  exit 1
fi

mkdir -p "$dist"

clang \
  --target=wasm32 \
  -O3 \
  -flto \
  -nostdlib \
  -fno-builtin \
  -Wl,--no-entry \
  -Wl,--export-memory \
  -Wl,--initial-memory=16777216 \
  -Wl,--max-memory=16777216 \
  -Wl,--export=gaius_hotpath_version \
  -Wl,--export=gaius_shift_indices_capacity \
  -Wl,--export=gaius_shift_indices_input_ptr \
  -Wl,--export=gaius_shift_indices_output_ptr \
  -Wl,--export=gaius_shift_indices \
  -Wl,--export=gaius_shift_indices_last_type \
  -Wl,--export=gaius_shift_indices_last_bytes \
  -Wl,--export=gaius_shift_indices_last_min \
  -Wl,--export=gaius_shift_indices_last_max \
  -Wl,--strip-all \
  "$source_file" \
  -o "$output"

echo "Built Wasm hot-path module: $output"
