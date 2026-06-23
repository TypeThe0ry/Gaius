#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
"$root/port/scripts/build-overlays.sh" >/dev/null
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
  package 2>&1 | tee "$log"
build_status="${PIPESTATUS[0]}"
set -e

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
