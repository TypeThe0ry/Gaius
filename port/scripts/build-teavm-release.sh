#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-false}"
export GAIUS_MINIFYING="${GAIUS_MINIFYING:-true}"
export GAIUS_SHORT_FILE_NAMES="${GAIUS_SHORT_FILE_NAMES:-true}"
export GAIUS_ASSERTIONS_REMOVED="${GAIUS_ASSERTIONS_REMOVED:-true}"

rm -f "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.map" \
  "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.teavmdbg"

"$root/port/scripts/build-teavm.sh"
if [[ "${GAIUS_SKIP_WASM_HOTPATH:-false}" != "true" ]]; then
  if ! "$root/port/scripts/build-wasm-hotpath.sh"; then
    if [[ "${GAIUS_REQUIRE_WASM_HOTPATH:-false}" == "true" ]]; then
      exit 1
    fi
    echo "WARNING: Wasm hot-path module was not built; JavaScript fallbacks will be used." >&2
  fi
fi
rm -f "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.map" \
  "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.teavmdbg"
"$root/port/scripts/compress-dist.sh"
