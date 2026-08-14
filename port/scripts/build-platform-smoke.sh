#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

# The smoke compiler reads the same mutable overlay JARs as release builds.
# Keep the writer lock until TeaVM exits so a concurrent rebuild cannot corrupt
# an already-open ZipFile.
overlay_lock="$root/port/work/.build-overlays.lock"
while ! mkdir "$overlay_lock" 2>/dev/null; do
  lock_pid="$(cat "$overlay_lock/pid" 2>/dev/null || true)"
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" 2>/dev/null; then
    sleep 0.2
    continue
  fi
  rm -rf "$overlay_lock"
done
printf '%s\n' "$$" > "$overlay_lock/pid"
release_overlay_lock() {
  rm -rf "$overlay_lock"
}
trap release_overlay_lock EXIT

if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" >/dev/null
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

log="$root/port/target/platform-smoke-teavm.log"
MAVEN_OPTS="${MAVEN_OPTS:--Xms512m -Xmx4g -XX:+UseG1GC}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  --file "$pom" \
  package 2>&1 | tee "$log"

if grep -Fq 'Error in @JSBody' "$log"; then
  echo "Platform smoke rejected TeaVM @JSBody errors" >&2
  exit 1
fi
