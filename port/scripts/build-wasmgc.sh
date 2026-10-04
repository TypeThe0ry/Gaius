#!/usr/bin/env bash
set -euo pipefail

# WebAssembly GC build of one Minecraft profile (v0.4.0 design D1/D2, M0 spike S1).
#
#   build-wasmgc.sh [--role client|singleplayer-worker] [--skip-overlays] <profile>
#
# The same source tree as the JavaScript build, compiled with TeaVM 0.15
# WEBASSEMBLY_GC: overlays are built with GAIUS_TEAVM_TARGET=wasmgc (the
# target-wasmgc source sets and the wasmgc patch chain), the POM stages
# port/src/target-wasmgc/java and writes classes.wasm (client) or
# singleplayer-server.wasm (Worker).  The TeaVM WebAssembly GC runtime is then
# patched for Gaius (port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs) and the
# minimal launcher page is staged next to the module.
#
# Output roots are separate from the JavaScript build of the same profile:
#   GAIUS_BUILD_ROOT          default port/target/<profile>-wasmgc
#   GAIUS_OVERLAY_DIRECTORY   default port/work/overlays/<profile>-wasmgc
#   GAIUS_DIST_DIRECTORY      default port/web/dist/<profile>-wasmgc
# Browser resources (resource lists, staged sounds and fonts, the vanilla asset
# pack) do not depend on the backend; until build-teavm.sh shares its resource
# staging, they are taken from a JavaScript build of the same profile:
#   GAIUS_WASMGC_RESOURCE_ROOT  that build's GAIUS_BUILD_ROOT (required)
#   GAIUS_WASMGC_RESOURCE_DIST  that build's dist (vanilla-assets.pack.gz; optional)
# Compiler settings: GAIUS_TEA_OPTIMIZATION_LEVEL (default SIMPLE), MAVEN_OPTS
# (default -Xms2g -Xmx14g with a GC log in the build root).  The role options
# default to stock TeaVM async analysis for wasmgc (generate-pom.sh);
# GAIUS_TEAVM_ASYNC_DUMP writes the async census (S2).
#
# Like build-teavm.sh, the script holds port/work/.build-overlays.lock from
# before the overlays are (re)built until the TeaVM compilation has closed the
# overlay jars, also with --skip-overlays, so no other overlay build rewrites
# them and no other full build starts meanwhile.  It refuses directories of a
# JavaScript build: an overlay directory that holds js overlays, and a build
# root or dist that is the JavaScript build it takes resources from.
root="$(cd "$(dirname "$0")/../.." && pwd)"
role=client
skip_overlays=false
profile_arg=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --role)
      role="$2"
      shift 2
      ;;
    --skip-overlays)
      skip_overlays=true
      shift
      ;;
    *)
      profile_arg="$1"
      shift
      ;;
  esac
done
case "$role" in
  client|singleplayer-worker) ;;
  *)
    echo "Unknown role '$role' (expected client or singleplayer-worker)" >&2
    exit 2
    ;;
esac
if [[ -z "$profile_arg" ]]; then
  echo "usage: $0 [--role client|singleplayer-worker] [--skip-overlays] <profile>" >&2
  exit 2
fi
case "$profile_arg" in
  versions/*.json) profile="$profile_arg" ;;
  *.json) profile="versions/$profile_arg" ;;
  *) profile="versions/$profile_arg.json" ;;
esac
export GAIUS_VERSION_PROFILE_PATH="$profile"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
if command -v cygpath >/dev/null 2>&1; then
  PATH="$(cygpath -u "$GAIUS_JAVA_HOME")/bin:$PATH"
  export PATH
fi
version="$GAIUS_MINECRAFT_VERSION"
work="$root/port/work/$version"

export GAIUS_TEAVM_TARGET=wasmgc
export GAIUS_BUILD_ROOT="${GAIUS_BUILD_ROOT:-$root/port/target/$version-wasmgc}"
export GAIUS_OVERLAY_DIRECTORY="${GAIUS_OVERLAY_DIRECTORY:-$root/port/work/overlays/$version-wasmgc}"
export GAIUS_DIST_DIRECTORY="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist/$version-wasmgc}"
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
dist="$(gaius_dist_directory "$root")"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
resource_root="${GAIUS_WASMGC_RESOURCE_ROOT:-}"
if [[ -z "$resource_root" || ! -d "$resource_root/generated-resources" ]]; then
  echo "GAIUS_WASMGC_RESOURCE_ROOT must name the build root of a JavaScript build of $version" >&2
  echo "(it provides generated-resources and server-worker/generated-resources)" >&2
  exit 2
fi
same_directory() {
  [[ -n "$1" && -n "$2" && -d "$1" && -d "$2" ]] \
    && [[ "$(cd "$1" && pwd -P)" == "$(cd "$2" && pwd -P)" ]]
}
if same_directory "$build_root" "$resource_root"; then
  echo "GAIUS_BUILD_ROOT is the JavaScript build root GAIUS_WASMGC_RESOURCE_ROOT ($resource_root)" >&2
  exit 2
fi
if same_directory "$dist" "${GAIUS_WASMGC_RESOURCE_DIST:-}"; then
  echo "GAIUS_DIST_DIRECTORY is the JavaScript dist GAIUS_WASMGC_RESOURCE_DIST ($dist);" >&2
  echo "the wasm launcher page would replace its index.html" >&2
  exit 2
fi
# An overlay directory without a TEAVM_TARGET marker predates the marker and
# holds js overlays (generate-pom.sh reads it the same way); build-overlays.sh
# writes "<target>-incomplete" while it rebuilds one.
overlay_marker="$(tr -d '\r\n' 2>/dev/null <"$overlay_directory/TEAVM_TARGET" || true)"
if [[ "$overlay_marker" == js || "$overlay_marker" == js-incomplete \
    || ( -z "$overlay_marker" && -f "$overlay_directory/client-named-$version-gaius.jar" ) ]]; then
  echo "$overlay_directory holds JavaScript overlays; set GAIUS_OVERLAY_DIRECTORY to a wasmgc one" >&2
  exit 2
fi
mkdir -p "$build_root" "$dist"
timings="$build_root/wasmgc-timings.txt"
: >"$timings"
phase_started=0
phase_begin() {
  phase_started="$(date +%s)"
  echo "== $1 ($(date -Is))"
}
phase_end() {
  printf '%s %ss\n' "$1" "$(( $(date +%s) - phase_started ))" | tee -a "$timings"
}

overlay_lock="$root/port/work/.build-overlays.lock"
overlay_lock_owner=""
release_overlay_lock() {
  if [[ -n "${overlay_lock_owner:-}" ]]; then
    gaius_teavm_lock_release "$overlay_lock" "$overlay_lock_owner" || true
    overlay_lock_owner=""
  fi
}
trap release_overlay_lock EXIT
# TeaVM keeps the overlay jars open for the whole compilation (build-teavm.sh):
# hold the overlay writer lock from here until the script exits.
gaius_teavm_lock_acquire "$overlay_lock"
overlay_lock_owner="$GAIUS_TEA_LOCK_OWNER_TOKEN"

if [[ "$skip_overlays" != true ]]; then
  phase_begin overlays
  GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" \
    >"$build_root/overlays.log" 2>&1 \
    || { tail -n 60 "$build_root/overlays.log" >&2; exit 1; }
  phase_end overlays
fi
if [[ "$(tr -d '\r\n' <"$overlay_directory/TEAVM_TARGET" 2>/dev/null)" != wasmgc ]]; then
  echo "$overlay_directory holds no wasmgc overlays" >&2
  exit 1
fi

asm_classpath="$maven_repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$maven_repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
tool_classpath="$overlay_directory/tool-classes:$asm_classpath"
if command -v cygpath >/dev/null 2>&1; then
  tool_classpath="$(cygpath -mp "$tool_classpath")"
fi
client_overlay="$overlay_directory/client-named-$version-gaius.jar"

if [[ "$role" == client ]]; then
  phase_begin resources
  # The same client-jar steps as build-teavm.sh: Gson TypeToken capture fixes and
  # the generated Unicode font definitions.
  gson_patches="$build_root/gson-type-token-client-patches"
  rm -rf "$gson_patches"
  mkdir -p "$gson_patches"
  java -classpath "$tool_classpath" dev.gaius.tools.GsonTypeTokenClientPatcher \
    "$client_overlay" "$gson_patches" "$work/libraries"
  jar --update --file "$client_overlay" -C "$gson_patches" .
  rm -rf "$build_root/generated-resources"
  cp -R "$resource_root/generated-resources" "$build_root/generated-resources"
  for font_definition in \
    assets/minecraft/font/include/unifont.json \
    assets/minecraft/font/include/unifont_pua.json; do
    if [[ -f "$build_root/generated-resources/$font_definition" ]]; then
      zip -q -d "$client_overlay" "$font_definition" >/dev/null 2>&1 || true
      (cd "$build_root/generated-resources" && jar uf "$client_overlay" "$font_definition")
    fi
  done
  resource_dist="${GAIUS_WASMGC_RESOURCE_DIST:-}"
  if [[ -n "$resource_dist" && -f "$resource_dist/vanilla-assets.pack.gz" ]]; then
    cp "$resource_dist/vanilla-assets.pack.gz" "$dist/vanilla-assets.pack.gz"
  fi
  phase_end resources
  role_root="$build_root"
  target_file="classes.wasm"
  resource_directory="$build_root/generated-resources"
  export GAIUS_EXCLUDED_LIBRARY_PREFIXES="org/apache/logging/log4j/log4j-core/,org/apache/logging/log4j/log4j-slf4j2-impl/"
else
  role_root="$build_root/server-worker"
  rm -rf "$role_root/generated-resources"
  mkdir -p "$role_root"
  cp -R "$resource_root/server-worker/generated-resources" "$role_root/generated-resources"
  target_file="singleplayer-server.wasm"
  resource_directory="$role_root/generated-resources"
  export GAIUS_MAIN_CLASS="dev.gaius.browser.BrowserIntegratedServerMain"
  export GAIUS_EXCLUDED_LIBRARY_PREFIXES="${GAIUS_SERVER_EXCLUDED_LIBRARY_PREFIXES:-com/microsoft/azure/msal4j/,com/azure/azure-json/}"
fi

phase_begin pom
mkdir -p "$role_root"
export GAIUS_TEAVM_ROLE="$role"
export GAIUS_POM="$role_root/generated-pom.xml"
export GAIUS_TARGET_DIRECTORY="$dist"
export GAIUS_TARGET_FILE="$target_file"
export GAIUS_MAVEN_DIRECTORY="$role_root/maven"
export GAIUS_RESOURCE_DIRECTORY="$resource_directory"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-false}"
pom="$("$root/port/scripts/generate-pom.sh")"
phase_end pom

phase_begin teavm
log="$role_root/teavm-build.log"
gc_log="$role_root/teavm-gc.log"
gc_log_option="-Xlog:gc:file=$gc_log:time,uptime"
if command -v cygpath >/dev/null 2>&1; then
  gc_log_option="-Xlog:gc:file=$(cygpath -m "$gc_log"):time,uptime"
fi
echo "POM: $pom"
echo "Log: $log"
set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xms2g -Xmx14g -XX:+UseG1GC -XX:MaxGCPauseMillis=500 $gc_log_option}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  "-Dmaven.repo.local=$maven_repository_for_java" \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e
phase_end teavm
grep -E '^\[ERROR\] [A-Z]' "$log" | sed -E 's/^\[ERROR\] //' | sort | uniq -c | sort -rn \
  >"$role_root/teavm-errors.txt" || true
echo "TeaVM exit status $build_status; $(grep -c '^\[ERROR\]' "$log" || true) error lines" | tee -a "$timings"
if [[ "$build_status" -ne 0 || ! -f "$dist/$target_file" ]]; then
  head -n 40 "$role_root/teavm-errors.txt" >&2 || true
  exit 1
fi

phase_begin runtime
# TeaVM ships the WebAssembly GC loader as a resource of teavm-core; it is not
# written by the compiler.  Gaius patches it once per build (D5, D13).
teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json")"
runtime_source="$role_root/wasm-gc-runtime.js"
unzip -p "$overlay_directory/teavm-core-$teavm_version-gaius.jar" \
  org/teavm/backend/wasm/wasm-gc-runtime.js >"$runtime_source"
node "$root/port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs" \
  "$runtime_source" "$dist/gaius-wasm-gc-runtime.js"
if [[ "$role" == client ]]; then
  cp "$root/port/web/runtime/wasmgc/gaius-wasm-boot.js" "$dist/gaius-wasm-boot.js"
  cp "$root/port/web/runtime/wasmgc/gaius-jspi.js" "$dist/gaius-jspi.js"
  if [[ -n "${GAIUS_WASMGC_RESOURCE_DIST:-}" && -f "$GAIUS_WASMGC_RESOURCE_DIST/index.html" ]]; then
    "$root/port/scripts/run-python.sh" "$root/port/web/runtime/wasmgc/make-wasm-page.py" \
      "$GAIUS_WASMGC_RESOURCE_DIST" "$dist" --gzip-payload
  fi
fi
phase_end runtime

raw_bytes="$(wc -c <"$dist/$target_file" | tr -d ' ')"
gzip_bytes="$(gzip -9 -c "$dist/$target_file" | wc -c | tr -d ' ')"
echo "$target_file raw=$raw_bytes gzip=$gzip_bytes" | tee -a "$timings"
echo "BUILD_EXIT=0"
