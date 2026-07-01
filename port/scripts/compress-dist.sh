#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist}"

if [[ ! -d "$dist" ]]; then
  echo "Missing dist directory: $dist" >&2
  exit 1
fi

shopt -s nullglob
files=(
  "$dist"/*.js
  "$dist"/*.html
  "$dist"/*.css
  "$dist"/*.json
  "$dist"/*.wasm
)

if [[ "${#files[@]}" -eq 0 ]]; then
  echo "No compressible dist assets found in $dist" >&2
  exit 1
fi

for file in "${files[@]}"; do
  gzip -kf -9 "$file"
  if command -v brotli >/dev/null 2>&1; then
    brotli -f -q 11 "$file"
  fi
done

echo "Compressed ${#files[@]} dist assets in $dist"
