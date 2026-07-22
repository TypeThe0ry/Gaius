#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist}"

if [[ ! -d "$dist" ]]; then
  echo "Missing dist directory: $dist" >&2
  exit 1
fi

shopt -s nullglob
if [[ -n "${GAIUS_COMPRESS_FILES:-}" ]]; then
  IFS=: read -r -a requested_files <<<"$GAIUS_COMPRESS_FILES"
  files=()
  for requested_file in "${requested_files[@]}"; do
    if [[ "$requested_file" == */* || ! -f "$dist/$requested_file" ]]; then
      echo "Missing or invalid dist asset: $requested_file" >&2
      exit 1
    fi
    files+=("$dist/$requested_file")
  done
else
  files=(
    "$dist"/*.js
    "$dist"/*.html
    "$dist"/*.css
    "$dist"/*.json
    "$dist"/*.wasm
  )
fi

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
