#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
smoke_directory="${GAIUS_PLATFORM_SMOKE_DIRECTORY:-$root/port/web/smoke}"
if [[ -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]] && [[ -z "${GAIUS_PLATFORM_SMOKE_DIRECTORY:-}" ]]; then
  smoke_directory="$root/port/web/smoke/$GAIUS_MINECRAFT_VERSION"
fi
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
  GAIUS_OVERLAY_DIRECTORY="$overlay_directory" GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" >/dev/null
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
fi

pom="$(
  GAIUS_MAIN_CLASS=dev.gaius.browser.PlatformSmoke \
  GAIUS_TARGET_DIRECTORY="$smoke_directory" \
  GAIUS_TARGET_FILE=platform-smoke-v4.js \
  GAIUS_BUILD_ROOT="$build_root" \
  GAIUS_OVERLAY_DIRECTORY="$overlay_directory" \
  GAIUS_POM="$build_root/platform-smoke-pom.xml" \
  "$root/port/scripts/generate-pom.sh"
)"

log="$build_root/platform-smoke-teavm.log"
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
