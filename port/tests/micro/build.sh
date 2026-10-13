#!/usr/bin/env bash
# Builds the runtime micro benchmarks (port/tests/micro, v0.4.0 WP0.6) as a small TeaVM
# WEBASSEMBLY_GC program in JSPI mode, like port/tests/jspi/build.sh: the patched
# teavm-core/teavm-classlib of a wasmgc overlay directory, the Gaius options of the wasmgc client
# role, and a runnable directory (classes.wasm, the patched TeaVM loader, gaius-jspi.js,
# index.html). BrowserMemory is compiled from this tree (port/overrides/libraries/lwjgl,
# target-wasmgc) over the overlay's LWJGL jar, with the overlay's WasmGcAliasPatcher applied as
# build-overlays.sh does, so allocator changes are measured without an overlay rebuild.
#
#   port/tests/micro/build.sh <out-dir>
#
# GAIUS_OVERLAY_DIRECTORY must name overlays built with GAIUS_TEAVM_TARGET=wasmgc. The TeaVM
# compile is a small one (-Xmx3g); Maven artifacts come from the local repository only.
# Then: node port/tests/micro/run-chrome.mjs <out-dir> [--json out.json] [--only pingpong,heap]
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: port/tests/micro/build.sh <out-dir>" >&2
  exit 2
fi
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
out="$1"
overlays="${GAIUS_OVERLAY_DIRECTORY:?GAIUS_OVERLAY_DIRECTORY must name a wasmgc overlay directory}"
if [[ "$(tr -d '\r\n' <"$overlays/TEAVM_TARGET" 2>/dev/null)" != wasmgc ]]; then
  echo "$overlays is not a complete wasmgc overlay directory (TEAVM_TARGET)" >&2
  exit 2
fi
if [[ -n "${GAIUS_JAVA_HOME:-}" ]]; then
  java_home="$GAIUS_JAVA_HOME"
  if command -v cygpath >/dev/null 2>&1; then
    java_home="$(cygpath -u "$java_home")"
  fi
  PATH="$java_home/bin:$PATH"
fi
teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json")"
repository="$(gaius_maven_repository_for_java "$root")"
separator=":"
native() { printf '%s\n' "$1"; }
if command -v cygpath >/dev/null 2>&1; then
  separator=";"
  native() { cygpath -m "$1"; }
fi
core="$(native "$overlays/teavm-core-$teavm_version-gaius.jar")"
classlib="$(native "$overlays/teavm-classlib-$teavm_version-gaius.jar")"
lwjgl_jar="$(find "$overlays/libraries/org/lwjgl/lwjgl" -name 'lwjgl-*.jar' ! -name '*-natives-*' | head -n 1)"
joml_jar="$(find "$overlays/libraries/org/joml/joml" -name 'joml-*.jar' | head -n 1)"
for jar in "$overlays/teavm-core-$teavm_version-gaius.jar" "$overlays/teavm-classlib-$teavm_version-gaius.jar" \
    "$lwjgl_jar" "$joml_jar" "$overlays/tool-classes/dev/gaius/tools/WasmGcAliasPatcher.class"; do
  [[ -n "$jar" && -f "$jar" ]] || { echo "missing $jar in $overlays" >&2; exit 2; }
done
lwjgl="$(native "$lwjgl_jar")"
joml="$(native "$joml_jar")"
deps=""
for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-jso-impl teavm-platform \
    teavm-metaprogramming-api teavm-metaprogramming-impl teavm-relocated-libs-asm \
    teavm-relocated-libs-asm-tree teavm-relocated-libs-asm-analysis teavm-relocated-libs-asm-commons \
    teavm-relocated-libs-asm-util teavm-relocated-libs-hppc teavm-relocated-libs-rhino \
    teavm-extension-apis teavm-extension-spi teavm-extension-spi-util teavm-tooling; do
  deps="$deps$separator$repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
deps="${deps#"$separator"}$separator$repository/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
deps="$deps$separator$repository/joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"
asm="$repository/org/ow2/asm/asm/9.8/asm-9.8.jar$separator$repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"

rm -rf "$out"
mkdir -p "$out/classes" "$out/memory" "$out/memory-patches"
out_native="$(native "$out")"
# This tree's BrowserMemory, with the dev.gaius.browser.wasm.LinearMemory stand-in renamed to
# the class library's java.nio.GaiusLinearMemory (WasmGcAliasPatcher, as in build-overlays.sh).
javac -J-Duser.language=en --release 21 -proc:none -encoding UTF-8 -nowarn \
  -cp "$classlib$separator$core$separator$lwjgl$separator$joml$separator$deps" -d "$out_native/memory" \
  "$(native "$root/port/overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/BrowserMemory.java")"
(cd "$out/memory" && jar --create --file ../memory.jar .)
java -cp "$(native "$overlays/tool-classes")$separator$asm" dev.gaius.tools.WasmGcAliasPatcher \
  "$out_native/memory.jar" "$out_native/memory-patches"
cp -R "$out/memory-patches/." "$out/memory/"
javac -J-Duser.language=en --release 21 -proc:none -encoding UTF-8 \
  -cp "$out_native/memory$separator$classlib$separator$core$separator$lwjgl$separator$joml$separator$deps" \
  -d "$out_native/classes" \
  $(find "$here/src" -name '*.java' | sort | while read -r source; do native "$source"; done)
mkdir -p "$out/driver"
javac -J-Duser.language=en --release 21 -proc:none -encoding UTF-8 \
  -cp "$core$separator$deps" -d "$out_native/driver" "$(native "$root/port/tests/jspi/driver/JspiSuiteBuild.java")"
java -Xmx3g -Duser.language=en \
  -cp "$out_native/driver$separator$core$separator$classlib$separator$deps$separator$out_native/classes$separator$out_native/memory$separator$lwjgl$separator$joml" \
  JspiSuiteBuild "$out_native" dev.gaius.tests.micro.MicroBench \
  gaius.teavm.role=client gaius.teavm.jspi=true gaius.teavm.telemetry=true \
  | tee "$out/build.log"
grep -q 'jspi: async analysis skipped, 0 coroutine methods' "$out/build.log" \
  || { echo "the compile did not run in JSPI mode" >&2; exit 1; }
unzip -p "$overlays/teavm-core-$teavm_version-gaius.jar" org/teavm/backend/wasm/wasm-gc-runtime.js \
  >"$out/wasm-gc-runtime.js"
node "$root/port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs" \
  "$out/wasm-gc-runtime.js" "$out/gaius-wasm-gc-runtime.js"
cp "$root/port/web/runtime/wasmgc/gaius-jspi.js" "$here/index.html" "$out/"
echo "micro benchmarks staged in $out"
