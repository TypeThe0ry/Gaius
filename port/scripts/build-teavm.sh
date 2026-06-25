#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
"$root/port/scripts/build-overlays.sh" >/dev/null
version="$(jq -er '.minecraftVersion' "$root/port/config.json")"
resource_list_dir="$root/port/target/generated-resources/dev/gaius/browser"
resource_list="$resource_list_dir/minecraft-resources.txt"
mkdir -p "$resource_list_dir"
jar tf "$root/port/work/overlays/client-named-$version-gaius.jar" |
  awk '(index($0, "assets/") == 1 || index($0, "data/") == 1) && substr($0, length($0), 1) != "/" { print }' >"$resource_list"
jar tf "$root/port/work/overlays/libraries/com/ibm/icu/icu4j/77.1/icu4j-77.1.jar" |
  awk 'index($0, "com/ibm/icu/impl/data/icudata/") == 1 && substr($0, length($0), 1) != "/" { print }' >>"$resource_list"
sort -u -o "$resource_list" "$resource_list"
echo "Generated browser resource list: $(wc -l <"$resource_list" | tr -d ' ') entries"
pom="$("$root/port/scripts/generate-pom.sh")"
log="$root/port/target/teavm-build.log"

echo "Compiling the official Minecraft 1.21.11 client with TeaVM"
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
  "$root/port/target/teavm-gap.json" \
  "$root/port/target/teavm-gap.md"
analysis_status="$?"
set -e

if [[ "$analysis_status" -ne 0 ]]; then
  echo "TeaVM analysis did not complete; canonical gap report was preserved" >&2
fi

exit "$build_status"
