#!/usr/bin/env bash
# Dumps the Minecraft 26.3 shader corpus (every render pipeline's SPIR-V plus the
# pipeline state a WebGPU backend needs) with the vanilla frontend on the JVM.
#
#   port/native/golden/shaders/dump-corpus.sh [--profile webgpu|gl] [--out DIR]
#
# Needs port/work/26.3 (client-named.jar, libraries/, classpath.txt from the
# 26.3 fetch) and JDK 25 (GAIUS_JAVA_HOME or JAVA_HOME, else PATH).
# DIR defaults to port/native/target/shader-corpus/<profile>.  The webgpu dump
# also refreshes fixtures/ (the modules that fixtures/MANIFEST lists for the
# crate's cargo test); they are vanilla shaders, so git ignores them.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd -- "$HERE/../.." && pwd)"
PORT="$(cd -- "$NATIVE/.." && pwd)"
PROFILE=webgpu
OUT=""
while (( $# > 0 )); do
    case "$1" in
        --profile) PROFILE="${2:?--profile needs webgpu or gl}"; shift 2 ;;
        --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
        *) printf 'usage: %s [--profile webgpu|gl] [--out DIR]\n' "$0" >&2; exit 2 ;;
    esac
done
OUT="${OUT:-$NATIVE/target/shader-corpus/$PROFILE}"
WORK="$PORT/work/26.3"

die() {
    printf 'dump-corpus: %s\n' "$1" >&2
    exit 1
}

java_tool() {
    local home
    for home in "${GAIUS_JAVA_HOME:-}" "${JAVA_HOME:-}"; do
        if [[ -n "$home" && ( -x "$home/bin/$1" || -x "$home/bin/$1.exe" ) ]]; then
            printf '%s/bin/%s' "$home" "$1"
            return
        fi
    done
    printf '%s' "$1"
}

# Paths handed to Java must be native on Windows.
native_path() {
    if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

[[ -f "$WORK/client-named.jar" ]] || die "missing $WORK/client-named.jar"
[[ -f "$WORK/classpath.txt" ]] || die "missing $WORK/classpath.txt"

SEP=":"
[[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]] && SEP=";"

# classpath.txt names the libraries of the machine that fetched them; map each
# entry onto this checkout's port/work/26.3/libraries.
mapfile -t LIBS < <(tr ':;' '\n\n' < "$WORK/classpath.txt" | sed -n 's|.*/libraries/||p' | tr -d '\r' | sort -u)
CP="$(native_path "$WORK/client-named.jar")"
count=0
for lib in "${LIBS[@]}"; do
    [[ -n "$lib" && -f "$WORK/libraries/$lib" ]] || continue
    CP+="$SEP$(native_path "$WORK/libraries/$lib")"
    count=$((count + 1))
done
(( count >= 40 )) || die "too few libraries resolved from $WORK/classpath.txt: $count"

CLASSES="$NATIVE/target/shader-corpus/classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"
"$(java_tool javac)" --release 25 -proc:none -nowarn -encoding UTF-8 -d "$(native_path "$CLASSES")" \
    -cp "$CP" "$(native_path "$HERE/java/dev/gaius/shaderxlat/ShaderXlatCorpusDump.java")"

rm -rf "$OUT"
mkdir -p "$OUT"
"$(java_tool java)" -Xmx3g --enable-native-access=ALL-UNNAMED \
    -cp "$(native_path "$CLASSES")$SEP$CP" dev.gaius.shaderxlat.ShaderXlatCorpusDump \
    "$PROFILE" "$(native_path "$WORK/client-named.jar")" "$(native_path "$OUT")"

# The crate's test fixtures: "<sha256> <file>" lines of fixtures/MANIFEST name
# modules of this dump (spirv/<sha256>.spv).
if [[ "$PROFILE" == webgpu ]]; then
    while read -r sha name; do
        [[ -z "$sha" || "${sha:0:1}" == "#" ]] && continue
        module="$OUT/spirv/$sha.spv"
        [[ -f "$module" ]] || die "the corpus has no module $sha for fixture $name"
        [[ "$(sha256sum "$module" | cut -d ' ' -f 1)" == "$sha" ]] || die "$module does not hash to $sha"
        cp "$module" "$HERE/fixtures/$name"
    done < "$HERE/fixtures/MANIFEST"
fi
