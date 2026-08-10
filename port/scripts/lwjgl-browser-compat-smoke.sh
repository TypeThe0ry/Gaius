#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/port/scripts/version-profile.sh"
gaius_load_version_profile "$ROOT"
gaius_select_java_home

MEMORY_PATH="$(gaius_library_path "org.lwjgl:lwjgl" "unsafe")"
OPENGL_PATH="$(gaius_library_path "org.lwjgl:lwjgl-opengl")"
GLFW_PATH="$(gaius_library_path "org.lwjgl:lwjgl-glfw")"
STB_PATH="$(gaius_library_path "org.lwjgl:lwjgl-stb")"
MEMORY_JAR="$ROOT/port/work/overlays/libraries/$MEMORY_PATH"
OPENGL_JAR="$ROOT/port/work/overlays/libraries/$OPENGL_PATH"
GLFW_JAR="$ROOT/port/work/overlays/libraries/$GLFW_PATH"
STB_JAR="$ROOT/port/work/overlays/libraries/$STB_PATH"
VMA_PATH="$(gaius_library_path "org.lwjgl:lwjgl-vma" 2>/dev/null || true)"
VULKAN_PATH="$(gaius_library_path "org.lwjgl:lwjgl-vulkan" 2>/dev/null || true)"
VMA_JAR="${VMA_PATH:+$ROOT/port/work/overlays/libraries/$VMA_PATH}"
VULKAN_JAR="${VULKAN_PATH:+$ROOT/port/work/overlays/libraries/$VULKAN_PATH}"

die() {
    printf 'lwjgl browser compatibility smoke failed: %s\n' "$1" >&2
    exit 1
}

[[ -f "$MEMORY_JAR" ]] || die "missing $MEMORY_JAR; run build-overlays.sh first"
[[ -f "$OPENGL_JAR" ]] || die "missing $OPENGL_JAR; run build-overlays.sh first"
[[ -f "$GLFW_JAR" ]] || die "missing $GLFW_JAR; run build-overlays.sh first"
[[ -f "$STB_JAR" ]] || die "missing $STB_JAR; run build-overlays.sh first"
if [[ -n "$VMA_JAR" ]]; then
    [[ -f "$VMA_JAR" ]] || die "missing $VMA_JAR; run build-overlays.sh first"
fi
if [[ -n "$VULKAN_JAR" ]]; then
    [[ -f "$VULKAN_JAR" ]] || die "missing $VULKAN_JAR; run build-overlays.sh first"
fi

method_block() {
    local dump="$1"
    local needle="$2"
    printf '%s\n' "$dump" | awk -v needle="$needle" '
        /^  (public|protected|private|static) / {
            if (active) active = 0
            if (index($0, needle) != 0) active = 1
        }
        active { print }
    '
}

assert_delegate() {
    local label="$1"
    local dump="$2"
    local signature="$3"
    local delegate="$4"
    local block
    block="$(method_block "$dump" "$signature")"
    [[ -n "$block" ]] || die "$label method not found: $signature"
    printf '%s\n' "$block" | rg -q "$delegate" \
        || die "$label does not delegate to $delegate: $signature"
    if printf '%s\n' "$block" | rg -q \
            'GL\.getICD|JNINativeInterface|JNI\.call|sun[/\.]misc[/\.]Unsafe\.'; then
        die "$label still contains native/Unsafe dispatch: $signature"
    fi
}

QUERY_METHODS=0
for owner in GL15 GL15C GL32 GL32C; do
    if dump="$(javap -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner" 2>/dev/null)"; then
        headers="$(printf '%s\n' "$dump" | rg \
            '^  (public|protected|private|static).* (n?glGenQueries)\(' || true)"
        if [[ -n "$headers" ]]; then
            while IFS= read -r header; do
                [[ -n "$header" ]] || continue
                QUERY_METHODS=$((QUERY_METHODS + 1))
                assert_delegate "$owner query generation" "$dump" "$header" 'BrowserOpenGL'
            done <<< "$headers"
        fi
    fi
done
[[ "$QUERY_METHODS" -gt 0 ]] || die "no GL query-generation overloads found"

DRAW_BUFFER_METHODS=0
for owner in GL20 GL20C; do
    dump="$(javap -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner")"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glDrawBuffers)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        DRAW_BUFFER_METHODS=$((DRAW_BUFFER_METHODS + 1))
        assert_delegate "$owner draw buffers" "$dump" "$header" 'BrowserOpenGL'
    done <<< "$headers"
done
[[ "$DRAW_BUFFER_METHODS" -ge 8 ]] || die "not all GL20 draw-buffer overloads were patched"

CLEAR_BUFFER_METHODS=0
for owner in GL30 GL30C; do
    dump="$(javap -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner")"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glClearBufferfv)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        CLEAR_BUFFER_METHODS=$((CLEAR_BUFFER_METHODS + 1))
        assert_delegate "$owner clear buffer" "$dump" "$header" 'BrowserOpenGL'
    done <<< "$headers"
done
[[ "$CLEAR_BUFFER_METHODS" -ge 6 ]] || die "not all GL30 clear-buffer overloads were patched"

DELETE_QUERY_METHODS=0
for owner in GL15 GL15C GL32 GL32C; do
    dump="$(javap -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner" 2>/dev/null || true)"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glDeleteQueries)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        DELETE_QUERY_METHODS=$((DELETE_QUERY_METHODS + 1))
        block="$(method_block "$dump" "$header")"
        printf '%s\n' "$block" | rg -q 'return' \
            || die "$owner query deletion is not a no-op: $header"
        if printf '%s\n' "$block" | rg -q 'GLCapabilities|JNI|invoke'; then
            die "$owner query deletion still reaches native state: $header"
        fi
    done <<< "$headers"
done
[[ "$DELETE_QUERY_METHODS" -ge 8 ]] || die "not all declared query-delete overloads were patched"

MEMORY_DUMP="$(javap -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.MemoryUtil)"
MEMORY_HEADERS="$(printf '%s\n' "$MEMORY_DUMP" | rg \
    '^  (public|protected|private|static).* (memFree|memAlignedFree|memAddress0|memAddressSafe|memAddress|memDuplicate|memSlice|wrapBuffer|write8Safe|slice|duplicate)\(' \
    | rg -v 'CustomBuffer|org\.lwjgl\.system\.Pointer' || true)"
[[ -n "$MEMORY_HEADERS" ]] || die "no MemoryUtil browser-sensitive methods found"
while IFS= read -r header; do
    [[ -n "$header" ]] || continue
    assert_delegate "MemoryUtil browser memory" "$MEMORY_DUMP" "$header" 'BrowserMemory'
done <<< "$MEMORY_HEADERS"

assert_delegate \
    "MemoryUtil allocator" \
    "$MEMORY_DUMP" \
    'org.lwjgl.system.MemoryUtil$MemoryAllocator getAllocator(boolean)' \
    'BrowserMemoryAllocator.instance'

CALLBACK_I_DUMP="$(javap -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.CallbackI)"
CALLBACK_ADDRESS="$(method_block "$CALLBACK_I_DUMP" 'long address()')"
printf '%s\n' "$CALLBACK_ADDRESS" | rg -q 'lconst_1' \
    || die "CallbackI.address does not use the browser callback handle"
if printf '%s\n' "$CALLBACK_ADDRESS" | rg -q 'getDescriptor|Upcalls'; then
    die "CallbackI.address still creates a native libffi upcall"
fi

for callback in \
    'org.lwjgl.system.MemoryManage$DebugAllocator$CallbackPP' \
    org.lwjgl.glfw.GLFWPreeditCallbackI \
    org.lwjgl.stb.STBIWriteCallbackI; do
    case "$callback" in
        org.lwjgl.system.*) jar="$MEMORY_JAR" ;;
        org.lwjgl.glfw.*) jar="$GLFW_JAR" ;;
        *) jar="$STB_JAR" ;;
    esac
    callback_dump="$(javap -classpath "$jar" -p -c "$callback")"
    if printf '%s\n' "$callback_dump" | rg -q 'MethodHandles\.lookup'; then
        die "$callback still initializes a native libffi descriptor"
    fi
done

if printf '%s\n' "$MEMORY_DUMP" | rg -n \
        'getstatic .*UNSAFE|sun[/\.]misc[/\.]Unsafe\.|JNINativeInterface'; then
    die "MemoryUtil bytecode still contains reachable Unsafe/JNI implementation"
fi

if [[ -n "$VMA_JAR" ]]; then
    for owner in org.lwjgl.util.vma.Vma org.lwjgl.util.vma.LibVma; do
        dump="$(javap -classpath "$VMA_JAR" -p -c "$owner")"
        if rg -q '^  .* native .*\(' <<< "$dump"; then
            die "$owner still declares desktop-native methods"
        fi
        rg -q 'UnsupportedOperationException' <<< "$dump" \
            || die "$owner native entry points do not fail explicitly in the browser"
    done
fi

if [[ -n "$VULKAN_JAR" ]]; then
    dump="$(javap -classpath "$VULKAN_JAR" -p -c org.lwjgl.vulkan.VK10)"
    if rg -q '^  .* native .*\(' <<< "$dump"; then
        die "org.lwjgl.vulkan.VK10 still declares desktop-native methods"
    fi
    rg -q 'UnsupportedOperationException' <<< "$dump" \
        || die "Vulkan native entry points do not fail explicitly in the browser"
fi

printf 'lwjgl browser compatibility smoke passed: %s query generators, %s query deletes, %s draw-buffer and %s clear-buffer methods\n' \
    "$QUERY_METHODS" "$DELETE_QUERY_METHODS" "$DRAW_BUFFER_METHODS" "$CLEAR_BUFFER_METHODS"
