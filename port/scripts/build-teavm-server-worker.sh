#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="$root/port/web/dist"
server_target="$root/port/target/server-worker"
resource_list="$root/port/target/generated-resources/dev/gaius/browser/minecraft-resources.txt"
server_resources="$server_target/generated-resources"

if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  "$root/port/scripts/build-overlays.sh" >/dev/null
fi
if [[ ! -f "$resource_list" ]]; then
  echo "Browser resources are missing; run build-teavm.sh once first" >&2
  exit 1
fi

rm -rf "$server_resources" "$server_target/maven"
mkdir -p "$dist" "$server_target" \
  "$server_resources/dev/gaius/browser"
awk 'index($0, "data/") == 1 \
  || $0 == "assets/.mcassetsroot" \
  || $0 == "assets/minecraft/lang/deprecated.json" \
  || $0 == "assets/minecraft/lang/en_us.json" \
  || $0 == "pack.png" { print }' "$resource_list" \
  >"$server_resources/dev/gaius/browser/minecraft-resources.txt"
cp "$root/port/web/singleplayer/server-worker-bootstrap.js" \
  "$dist/singleplayer-server-worker.js"

export GAIUS_POM="$server_target/generated-pom.xml"
export GAIUS_MAIN_CLASS="dev.gaius.browser.BrowserIntegratedServerMain"
export GAIUS_TARGET_DIRECTORY="$dist"
export GAIUS_TARGET_FILE="singleplayer-server.js"
export GAIUS_MAVEN_DIRECTORY="$server_target/maven"
export GAIUS_RESOURCE_DIRECTORY="$server_resources"
export GAIUS_EXCLUDED_LIBRARY_PREFIXES="${GAIUS_SERVER_EXCLUDED_LIBRARY_PREFIXES:-com/microsoft/azure/msal4j/,com/azure/azure-json/}"
export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_SERVER_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
export GAIUS_SOURCE_MAPS="${GAIUS_SERVER_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_SERVER_DEBUG_INFO:-false}"
export GAIUS_MINIFYING="${GAIUS_SERVER_MINIFYING:-false}"
export GAIUS_SHORT_FILE_NAMES="${GAIUS_SERVER_SHORT_FILE_NAMES:-false}"
export GAIUS_ASSERTIONS_REMOVED="${GAIUS_SERVER_ASSERTIONS_REMOVED:-false}"

pom="$("$root/port/scripts/generate-pom.sh")"
log="$server_target/teavm-build.log"

echo "Compiling the official Minecraft server Worker with TeaVM"
echo "POM: $pom"
echo "Log: $log"

set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xms4g -Xmx20g -XX:+UseG1GC -XX:MaxGCPauseMillis=300}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e

tail -n 160 "$log" || true

set +e
"$root/port/scripts/analyze-teavm-log.py" \
  "$log" \
  "$server_target/teavm-gap.json" \
  "$server_target/teavm-gap.md"
analysis_status="$?"
set -e
if [[ "$analysis_status" -ne 0 ]]; then
  echo "TeaVM server analysis did not complete" >&2
fi

if [[ "$build_status" -eq 0 ]]; then
  "$root/port/scripts/postprocess-teavm-js.py" "$dist/singleplayer-server.js"
  if [[ "${GAIUS_SKIP_COMPRESSION:-false}" != "true" ]]; then
    GAIUS_COMPRESS_FILES="singleplayer-server.js:singleplayer-server-worker.js" \
      "$root/port/scripts/compress-dist.sh" >/dev/null
  fi
fi

exit "$build_status"
