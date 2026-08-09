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

if [[ "${GAIUS_SKIP_CLIENT_BUILD:-false}" == "true" ]]; then
  client_js="$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}"
  vanilla_asset_pack="$root/port/web/dist/vanilla-assets.pack.gz"
  expected_client_sha256="${GAIUS_RESUME_CLIENT_SHA256:-}"
  if [[ ! -s "$client_js" || ! -s "$vanilla_asset_pack" ]]; then
    echo "Cannot resume release: client JavaScript or vanilla asset pack is missing" >&2
    echo "Client: $client_js" >&2
    echo "Assets: $vanilla_asset_pack" >&2
    exit 1
  fi
  if [[ -z "$expected_client_sha256" ]]; then
    echo "Cannot resume release: GAIUS_RESUME_CLIENT_SHA256 is required" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual_client_sha256="$(sha256sum "$client_js" | awk '{print $1}')"
  else
    actual_client_sha256="$(shasum -a 256 "$client_js" | awk '{print $1}')"
  fi
  if [[ "$actual_client_sha256" != "$expected_client_sha256" ]]; then
    echo "Cannot resume release: client JavaScript SHA-256 does not match" >&2
    exit 1
  fi
  grep -Fq '[INFO] BUILD SUCCESS' "$root/port/target/teavm-build.log" \
    || { echo "Cannot resume release: TeaVM log has no BUILD SUCCESS" >&2; exit 1; }
  grep -aFq 'gaius-java-finite-long-cast' "$client_js" \
    || { echo "Cannot resume release: finite-long postprocess marker is missing" >&2; exit 1; }
  grep -aFq 'target-attestation' "$client_js" \
    || { echo "Cannot resume release: Relay target-attestation guard is missing" >&2; exit 1; }
  node --check "$client_js"
  gzip -t "$vanilla_asset_pack"
  echo "Reusing successfully compiled client JavaScript: $client_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/analyze-teavm-log.py" \
    "$root/port/target/teavm-build.log" \
    "$root/port/target/teavm-gap.json" \
    "$root/port/target/teavm-gap.md"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-index-html.py" \
    "$root/port/web/dist/index.html" \
    "$client_js"
else
  "$root/port/scripts/build-teavm.sh"
fi
if [[ "${GAIUS_SKIP_SERVER_WORKER:-false}" != "true" ]]; then
  GAIUS_SKIP_OVERLAY_BUILD=true GAIUS_SKIP_COMPRESSION=true \
    "$root/port/scripts/build-teavm-server-worker.sh"
else
  server_js="$root/port/web/dist/singleplayer-server.js"
  if [[ ! -s "$server_js" ]]; then
    echo "Cannot resume release: server Worker JavaScript is missing at $server_js" >&2
    exit 1
  fi
  cp "$root/port/web/singleplayer/server-worker-bootstrap.js" \
    "$root/port/web/dist/singleplayer-server-worker.js"
  echo "Reusing successfully compiled server Worker JavaScript: $server_js"
fi
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/postprocess-index-html.py" \
  "$root/port/web/dist/index.html" \
  "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}"
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
cp "$root/relay-nodes.json" "$root/port/web/dist/relay-nodes.json"
GAIUS_COMPRESS_EXCLUDE=Gaius.html "$root/port/scripts/compress-dist.sh"
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/build-portable-html.py"
GAIUS_COMPRESS_FILES=Gaius.html "$root/port/scripts/compress-dist.sh"
