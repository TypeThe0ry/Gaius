#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
minecraft_version="$GAIUS_MINECRAFT_VERSION"
asset_index_id="$(jq -er '.assetIndex.id // .assets' "$GAIUS_VERSION_METADATA" | tr -d '\r\n')"
identity_tool="$root/port/scripts/gaius_build_identity.py"
compiler_profile_tool="$root/port/scripts/teavm-compiler-profile.py"
client_pom="$root/port/target/generated-pom.xml"
client_resource_list="$root/port/target/generated-resources/dev/gaius/browser/minecraft-resources.txt"
client_embedded_resources="$root/port/target/generated-resources/dev/gaius/browser/minecraft-embedded-resources.txt"
asset_index="$root/port/work/$minecraft_version/assets/indexes/$asset_index_id.json"
generated_sounds="$root/port/target/generated-resources/assets/minecraft/sounds.json"
generated_unifont="$root/port/target/generated-resources/assets/minecraft/font/include/unifont.json"
generated_unifont_pua="$root/port/target/generated-resources/assets/minecraft/font/include/unifont_pua.json"
server_pom="$root/port/target/server-worker/generated-pom.xml"
server_resource_list="$root/port/target/server-worker/generated-resources/dev/gaius/browser/minecraft-resources.txt"

release_lock="$root/port/target/.release-build.lock"
while ! mkdir "$release_lock" 2>/dev/null; do
  lock_pid="$(cat "$release_lock/pid" 2>/dev/null || true)"
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" 2>/dev/null; then
    sleep 0.2
    continue
  fi
  rm -rf "$release_lock"
done
printf '%s\n' "$$" >"$release_lock/pid"
release_build_lock() {
  rm -rf "$release_lock"
}
trap release_build_lock EXIT

verify_identity() {
  local role="$1"
  local artifact="$2"
  "$root/port/scripts/run-python.sh" "$identity_tool" verify \
    --root "$root" \
    --role "$role" \
    --artifact "$artifact" >/dev/null
}

write_identity() {
  local role="$1"
  local artifact="$2"
  "$root/port/scripts/run-python.sh" "$identity_tool" write \
    --root "$root" \
    --role "$role" \
    --artifact "$artifact"
}

artifact_sha256() {
  local artifact="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$artifact" | awk '{print $1}'
  else
    shasum -a 256 "$artifact" | awk '{print $1}'
  fi
}

write_client_release_profile() {
  local client_js="$1"
  local vanilla_asset_pack="$root/port/web/dist/vanilla-assets.pack.gz"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" write \
    --root "$root" \
    --role client \
    --artifact "$client_js" \
    --pom "$client_pom" \
    --resource "$client_resource_list" \
    --resource "$client_embedded_resources" \
    --resource "$asset_index" \
    --resource "$generated_sounds" \
    --resource "$generated_unifont" \
    --resource "$generated_unifont_pua" \
    --resource "$vanilla_asset_pack" \
    --require-release
}

verify_client_release_profile() {
  local client_js="$1"
  local vanilla_asset_pack="$root/port/web/dist/vanilla-assets.pack.gz"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" verify \
    --root "$root" \
    --role client \
    --artifact "$client_js" \
    --pom "$client_pom" \
    --resource "$client_resource_list" \
    --resource "$client_embedded_resources" \
    --resource "$asset_index" \
    --resource "$generated_sounds" \
    --resource "$generated_unifont" \
    --resource "$generated_unifont_pua" \
    --resource "$vanilla_asset_pack" \
    --require-release >/dev/null
}

verify_worker_release_profile() {
  local server_js="$1"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" verify \
    --root "$root" \
    --role singleplayer-worker \
    --artifact "$server_js" \
    --pom "$server_pom" \
    --resource "$server_resource_list" \
    --require-release >/dev/null
}

export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-false}"
export GAIUS_MINIFYING="${GAIUS_MINIFYING:-true}"
export GAIUS_SHORT_FILE_NAMES="${GAIUS_SHORT_FILE_NAMES:-true}"
export GAIUS_ASSERTIONS_REMOVED="${GAIUS_ASSERTIONS_REMOVED:-true}"

if [[ "$GAIUS_TEA_OPTIMIZATION_LEVEL" != "ADVANCED" \
      && "$GAIUS_TEA_OPTIMIZATION_LEVEL" != "FULL" ]] \
    || [[ "$GAIUS_SOURCE_MAPS" != "false" \
      || "$GAIUS_DEBUG_INFO" != "false" \
      || "$GAIUS_MINIFYING" != "true" \
      || "$GAIUS_SHORT_FILE_NAMES" != "true" \
      || "$GAIUS_ASSERTIONS_REMOVED" != "true" ]]; then
  echo "Release builds require ADVANCED/FULL optimization, minification, short names, removed assertions, and no debug/source maps" >&2
  exit 1
fi

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
  actual_client_sha256="$(artifact_sha256 "$client_js")"
  if [[ "$actual_client_sha256" != "$expected_client_sha256" ]]; then
    echo "Cannot resume release: client JavaScript SHA-256 does not match" >&2
    exit 1
  fi
  grep -Fq '[INFO] BUILD SUCCESS' "$root/port/target/teavm-build.log" \
    || { echo "Cannot resume release: TeaVM log has no BUILD SUCCESS" >&2; exit 1; }
  if grep -Fq 'Error in @JSBody' "$root/port/target/teavm-build.log"; then
    echo "Cannot resume release: TeaVM log contains invalid @JSBody JavaScript" >&2
    exit 1
  fi
  grep -aFq 'gaius-java-finite-long-cast' "$client_js" \
    || { echo "Cannot resume release: finite-long postprocess marker is missing" >&2; exit 1; }
  grep -aFq 'target-attestation' "$client_js" \
    || { echo "Cannot resume release: Relay target-attestation guard is missing" >&2; exit 1; }
  node --check "$client_js"
  gzip -t "$vanilla_asset_pack"
  verify_identity client "$client_js" \
    || { echo "Cannot resume release: client build identity is stale or missing" >&2; exit 1; }
  verify_client_release_profile "$client_js" \
    || { echo "Cannot resume release: client compiler profile is stale, missing, or not release-grade" >&2; exit 1; }
  verify_identity vanilla-assets "$vanilla_asset_pack" \
    || { echo "Cannot resume release: vanilla asset build identity is stale or missing" >&2; exit 1; }
  echo "Reusing successfully compiled client JavaScript: $client_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/analyze-teavm-log.py" \
    "$root/port/target/teavm-build.log" \
    "$root/port/target/teavm-gap.json" \
    "$root/port/target/teavm-gap.md"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-index-html.py" \
    "$root/port/web/dist/index.html" \
    "$client_js" \
    "$minecraft_version" \
    "$asset_index_id"
else
  GAIUS_SKIP_COMPRESSION=true "$root/port/scripts/build-teavm.sh"
  write_client_release_profile "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}"
fi
if [[ "${GAIUS_SKIP_SERVER_WORKER:-false}" != "true" ]]; then
  GAIUS_SKIP_OVERLAY_BUILD=true GAIUS_SKIP_COMPRESSION=true \
    "$root/port/scripts/build-teavm-server-worker.sh"
else
  server_js="$root/port/web/dist/singleplayer-server.js"
  if [[ ! -s "$server_js" ]] || ! verify_identity singleplayer-worker "$server_js"; then
    echo "Cannot resume release: server Worker or matching build identity is missing at $server_js" >&2
    exit 1
  fi
  verify_worker_release_profile "$server_js" \
    || { echo "Cannot resume release: server Worker compiler profile is stale, missing, or not release-grade" >&2; exit 1; }
  cp "$root/port/web/singleplayer/server-worker-bootstrap.js" \
    "$root/port/web/dist/singleplayer-server-worker.js"
  write_identity worker-bootstrap \
    "$root/port/web/dist/singleplayer-server-worker.js"
  echo "Reusing successfully compiled server Worker JavaScript: $server_js"
fi
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/postprocess-index-html.py" \
  "$root/port/web/dist/index.html" \
  "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}" \
  "$minecraft_version" \
  "$asset_index_id"
wasm_hotpath="$root/port/web/dist/gaius-hotpath.wasm"
if [[ "${GAIUS_SKIP_WASM_HOTPATH:-false}" != "true" ]]; then
  if ! "$root/port/scripts/build-wasm-hotpath.sh"; then
    if [[ "${GAIUS_REQUIRE_WASM_HOTPATH:-false}" == "true" ]]; then
      exit 1
    fi
    if ! verify_identity wasm-hotpath "$wasm_hotpath"; then
      echo "Cannot resume release: Wasm build failed and no matching artifact identity exists" >&2
      exit 1
    fi
    echo "WARNING: Wasm build failed; reusing the identity-verified module." >&2
  else
    write_identity wasm-hotpath "$wasm_hotpath"
  fi
elif ! verify_identity wasm-hotpath "$wasm_hotpath"; then
  echo "Cannot resume release: skipped Wasm artifact has no matching build identity" >&2
  exit 1
fi
rm -f "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.map" \
  "$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}.teavmdbg"
cp "$root/relay-nodes.json" "$root/port/web/dist/relay-nodes.json"
write_identity relay-registry "$root/port/web/dist/relay-nodes.json"
GAIUS_COMPRESS_EXCLUDE=Gaius.html "$root/port/scripts/compress-dist.sh"
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/build-portable-html.py"
GAIUS_COMPRESS_FILES=Gaius.html:Gaius.manifest.json \
  "$root/port/scripts/compress-dist.sh"
