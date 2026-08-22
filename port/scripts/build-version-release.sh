#!/usr/bin/env bash
set -euo pipefail

# Build one Minecraft profile into version-scoped state/output directories.
# This wrapper never changes port/config.json and never reuses another
# profile's generated resources, Maven target, overlay JARs, or dist assets.
root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
profile_arg="${1:-}"
if [[ -z "$profile_arg" ]]; then
  echo "usage: $0 <version-id|versions/<version>.json>" >&2
  exit 2
fi

case "$profile_arg" in
  versions/*.json) profile="$profile_arg" ;;
  *.json) profile="versions/$profile_arg" ;;
  *) profile="versions/$profile_arg.json" ;;
esac
profile_path="$root/port/$profile"
if [[ ! -f "$profile_path" ]]; then
  echo "Version profile is missing: $profile_path" >&2
  exit 1
fi
version="$(jq -er '.id' "$profile_path")"

# A caller may override the roots for a disposable build, but the wrapper must
# never silently turn a profile build back into the historical shared output.
# This catches stale GAIUS_* variables left by an older 26.2 invocation.
canonical_path() {
  local value="$1"
  if command -v cygpath >/dev/null 2>&1; then
    value="$(cygpath -u "$value")"
  fi
  if command -v realpath >/dev/null 2>&1 \
      && realpath -m -- "$value" >/dev/null 2>&1; then
    # `-m` normalizes non-existent disposable roots and resolves any existing
    # symlink parents, preventing `profile/..` from bypassing shared-root checks.
    realpath -m -- "$value"
  elif ! command -v cygpath >/dev/null 2>&1; then
    "$root/port/scripts/run-python.sh" -c \
      'import os, sys; print(os.path.realpath(os.path.abspath(sys.argv[1])))' \
      "$value"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$value"
  else
    printf '%s\n' "$value"
  fi
}

canonical_root="$(canonical_path "$root")"
reject_shared_root() {
  local label="$1"
  local raw="$2"
  local resolved
  local shared_root
  local relative
  resolved="$(canonical_path "$(gaius_resolve_path "$root" "$raw")")"
  for shared_root in \
    "$canonical_root/port/target" \
    "$canonical_root/port/web/dist" \
    "$canonical_root/port/work/overlays"; do
    case "$resolved" in
      "$shared_root")
        echo "$label points at a shared legacy root: $raw" >&2
        echo "Use a profile-scoped path containing /$version instead" >&2
        exit 1
        ;;
      "$shared_root"/*)
        relative="${resolved#"$shared_root"/}"
        case "$relative" in
          "$version"|"$version"/*) ;;
          *)
            echo "$label points at another profile's root: $raw" >&2
            echo "Use a profile-scoped path containing /$version instead" >&2
            exit 1
            ;;
        esac
        ;;
    esac
  done
}

build_root="${GAIUS_BUILD_ROOT:-$root/port/target/$version}"
overlay_directory="${GAIUS_OVERLAY_DIRECTORY:-$root/port/work/overlays/$version}"
dist_directory="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist/$version}"
[[ -n "$build_root" ]] && reject_shared_root GAIUS_BUILD_ROOT "$build_root"
[[ -n "$overlay_directory" ]] && reject_shared_root GAIUS_OVERLAY_DIRECTORY "$overlay_directory"
[[ -n "$dist_directory" ]] && reject_shared_root GAIUS_DIST_DIRECTORY "$dist_directory"

export GAIUS_VERSION_PROFILE_PATH="$profile"
export GAIUS_BUILD_ROOT="$build_root"
export GAIUS_OVERLAY_DIRECTORY="$overlay_directory"
export GAIUS_DIST_DIRECTORY="$dist_directory"

# Keep the success marker in the release log itself instead of relying on a
# caller to append its captured status after the build pipeline has finished.
# Running the child as an `if` condition intentionally suppresses errexit for
# this one command so its status can be forwarded without ever claiming
# success after a failed build.
if "$root/port/scripts/build-teavm-release.sh"; then
  printf 'BUILD_EXIT=0\n'
else
  build_status="$?"
  exit "$build_status"
fi
