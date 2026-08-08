#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  "$root/port/scripts/build-overlays.sh" >/dev/null
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
fi

pom="$(
  GAIUS_MAIN_CLASS=dev.gaius.browser.PlatformSmoke \
  GAIUS_TARGET_DIRECTORY="$root/port/web/smoke" \
  GAIUS_TARGET_FILE=platform-smoke-v4.js \
  GAIUS_POM="$root/port/target/platform-smoke-pom.xml" \
  "$root/port/scripts/generate-pom.sh"
)"

MAVEN_OPTS="${MAVEN_OPTS:--Xms512m -Xmx4g -XX:+UseG1GC}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  --file "$pom" \
  package
