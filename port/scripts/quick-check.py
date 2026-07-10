#!/usr/bin/env python3
"""Fast non-compiling diagnostics for the browser Minecraft port.

This script intentionally does not run Maven, TeaVM, overlay generation, Chrome,
or screenshots. It only reads existing logs, generated files, probe JSON, and
overlay classes.
"""

from __future__ import annotations

import glob
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PORT = ROOT / "port"
TARGET = PORT / "target"
DIST = PORT / "web" / "dist"
OVERLAYS = PORT / "work" / "overlays"
OPENGL_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-opengl" / "src" / "main" / "java" / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.java"
OPENGL_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglOpenGLBrowserPatcher.java"
OPENAL_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-openal" / "src" / "main" / "java" / "org" / "lwjgl" / "openal" / "BrowserOpenAL.java"
OPENAL_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglOpenALBrowserPatcher.java"
STB_IMAGE = PORT / "overrides" / "libraries" / "lwjgl-stb" / "src" / "main" / "java" / "org" / "lwjgl" / "stb" / "STBImage.java"
LWJGL_BROWSER_MEMORY = PORT / "overrides" / "libraries" / "lwjgl" / "src" / "main" / "java" / "org" / "lwjgl" / "system" / "BrowserMemory.java"
GLFW_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-glfw" / "src" / "main" / "java" / "org" / "lwjgl" / "glfw" / "BrowserGlfw.java"
GLFW_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglGlfwBrowserPatcher.java"
CLIENT_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "MinecraftClientPatcher.java"
VANILLA_PACK_RESOURCES = PORT / "overrides" / "client" / "src" / "main" / "java" / "net" / "minecraft" / "server" / "packs" / "VanillaPackResources.java"
BROWSER_FILE_PERSISTENCE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserFilePersistence.java"
BROWSER_BIT_STORAGE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBitStorage.java"
BROWSER_GUI_ITEM_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserGuiItemCache.java"
VERTEX_ARRAY_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "com" / "mojang" / "blaze3d" / "opengl" / "VertexArrayCache.java"
WASM_HOTPATH_C = PORT / "wasm" / "hotpath" / "gaius_hotpath.c"
BUILD_WASM_HOTPATH = PORT / "scripts" / "build-wasm-hotpath.sh"
GENERATE_WASM_HOTPATH = PORT / "scripts" / "generate-wasm-hotpath.py"
GENERATE_POM = PORT / "scripts" / "generate-pom.sh"
BUILD_TEAVM = PORT / "scripts" / "build-teavm.sh"
BUILD_RELEASE = PORT / "scripts" / "build-teavm-release.sh"
COMPRESS_DIST = PORT / "scripts" / "compress-dist.sh"
SERVE_DIST = PORT / "scripts" / "serve-dist.py"
INDEX_HTML = PORT / "web" / "dist" / "index.html"
HOTPATH_WASM = PORT / "web" / "dist" / "gaius-hotpath.wasm"
GENERATED_RESOURCE_LIST = TARGET / "generated-resources" / "dev" / "gaius" / "browser" / "minecraft-resources.txt"
GENERATED_SOUNDS_JSON = TARGET / "generated-resources" / "assets" / "minecraft" / "sounds.json"
POSTPROCESS_TEAVM_JS = PORT / "scripts" / "postprocess-teavm-js.py"
POSTPROCESS_INDEX_HTML = PORT / "scripts" / "postprocess-index-html.py"
FAILURES: list[str] = []


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def fmt_time(path: Path) -> str:
    if not path.exists():
        return "missing"
    return datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")


def latest(pattern: str) -> Path | None:
    paths = [Path(p) for p in glob.glob(str(pattern))]
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def latest_any(*patterns: Path) -> Path | None:
    paths: list[Path] = []
    for pattern in patterns:
        paths.extend(Path(p) for p in glob.glob(str(pattern)))
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(errors="replace"))
    except Exception as exc:  # noqa: BLE001 - diagnostics should not crash early
        return {"_error": str(exc)}


def print_check(name: str, ok: bool) -> None:
    if ok:
        print(f"OK {name}")
        return
    FAILURES.append(name)
    print(f"FAIL {name}")


def latest_snapshot(data: dict) -> dict:
    snapshots = data.get("interactionSnapshots") if isinstance(data, dict) else None
    if isinstance(snapshots, list) and snapshots:
        for snapshot in reversed(snapshots):
            if isinstance(snapshot, dict):
                return snapshot
    return {}


def snapshot_minecraft_state(data: dict) -> dict:
    snapshot = latest_snapshot(data)
    state = snapshot.get("minecraftState") if isinstance(snapshot, dict) else None
    return state if isinstance(state, dict) else {}


def snapshot_gl_stats(data: dict) -> dict:
    snapshot = latest_snapshot(data)
    stats = snapshot.get("glStatsLite") or snapshot.get("glStats") if isinstance(snapshot, dict) else None
    if isinstance(stats, dict):
        return stats
    state = data.get("state") if isinstance(data, dict) else None
    stats = state.get("glStats") if isinstance(state, dict) else None
    return stats if isinstance(stats, dict) else {}


def snapshot_counters(data: dict) -> dict:
    snapshot = latest_snapshot(data)
    counters = snapshot.get("minecraftCounters") if isinstance(snapshot, dict) else None
    return counters if isinstance(counters, dict) else {}


def run_javap(classpath: Path, class_name: str) -> str:
    if not classpath.exists():
        return f"missing classpath: {rel(classpath)}"
    try:
        return subprocess.check_output(
            ["javap", "-classpath", str(classpath), "-c", "-p", class_name],
            cwd=ROOT,
            text=True,
            stderr=subprocess.STDOUT,
            timeout=10,
        )
    except Exception as exc:  # noqa: BLE001
        return f"javap failed: {exc}"


def read_zip_entry_latin1(path: Path, entry: str) -> str:
    if not path.exists():
        return ""
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.read(entry).decode("latin1", errors="ignore")
    except Exception:
        return ""


def method_section(text: str, header: str) -> str:
    start = text.find(header)
    if start < 0:
        return ""
    next_starts = [
        pos
        for marker in ("\n  public ", "\n  private ", "\n  protected ", "\n  static ")
        for pos in [text.find(marker, start + 1)]
        if pos > start
    ]
    end = min(next_starts) if next_starts else len(text)
    return text[start:end]


def last_putstatic_bool(section_text: str, field: str) -> bool | None:
    matches = re.findall(
        r"(iconst_[01])\s*\n\s*\d+:\s+putstatic\s+#\d+\s+// Field " + re.escape(field),
        section_text,
    )
    if not matches:
        return None
    return matches[-1] == "iconst_1"


def section(title: str) -> None:
    print(f"\n== {title} ==")


def check_gap() -> None:
    section("TeaVM gap")
    path = TARGET / "teavm-gap.json"
    data = load_json(path)
    if "_error" in data:
        print(f"{rel(path)}: {data['_error']}")
        return
    print(f"source: {data.get('source')}")
    print(f"completedAnalysis: {data.get('completedAnalysis')}")
    print(f"failureReason: {data.get('failureReason')}")
    print(f"totalOccurrences: {data.get('totalOccurrences')}")
    print(f"uniqueSymbols: {data.get('uniqueSymbols')}")


def check_build_timeline() -> None:
    section("Build timeline")
    classes_js = DIST / "classes.js"
    classes_map = DIST / "classes.js.map"
    latest_full = latest_any(
        TARGET / "build-teavm*.log",
        TARGET / "build-release*.log",
        TARGET / "build-*wrapper.log",
    )
    latest_overlay = latest(TARGET / "build-overlays*.log")
    browser_opengl_class = (
        OVERLAYS
        / "library-classes"
        / "lwjgl-opengl"
        / "org"
        / "lwjgl"
        / "opengl"
        / "BrowserOpenGL.class"
    )
    print(f"classes.js: {fmt_time(classes_js)} size={classes_js.stat().st_size if classes_js.exists() else 0}")
    print(f"classes.js.map: {fmt_time(classes_map)} size={classes_map.stat().st_size if classes_map.exists() else 0}")
    print(f"latest full build: {rel(latest_full) if latest_full else 'missing'} {fmt_time(latest_full) if latest_full else ''}")
    print(f"latest overlay build: {rel(latest_overlay) if latest_overlay else 'missing'} {fmt_time(latest_overlay) if latest_overlay else ''}")
    print(f"BrowserOpenGL.java: {fmt_time(OPENGL_BRIDGE)}")
    if latest_overlay and classes_js.exists() and latest_overlay.stat().st_mtime > classes_js.stat().st_mtime:
        print("WARNING: overlay patches are newer than classes.js; full TeaVM output is stale.")
    if latest_full and classes_js.exists() and latest_full.stat().st_mtime > classes_js.stat().st_mtime + 60:
        print("WARNING: latest full build log is newer than classes.js by more than 60s.")
    if (
        browser_opengl_class.exists()
        and OPENGL_BRIDGE.exists()
        and OPENGL_BRIDGE.stat().st_mtime > browser_opengl_class.stat().st_mtime
    ):
        print("WARNING: BrowserOpenGL.java is newer than overlay classes; run overlay build before full TeaVM.")


def check_latest_states() -> None:
    section("Latest world probes")
    states = sorted(TARGET.glob("state-*.json"), key=lambda p: p.stat().st_mtime)[-10:]
    if not states:
        print("no state-*.json files")
        return
    for path in states:
        data = load_json(path)
        wc = data.get("worldCheck", {}) if isinstance(data, dict) else {}
        snapshot = latest_snapshot(data)
        mc = snapshot_minecraft_state(data)
        gl = snapshot_gl_stats(data)
        counters = snapshot_counters(data)
        screen = wc.get("screen") or mc.get("screen") or snapshot.get("screen")
        level = wc.get("level") or mc.get("level") or snapshot.get("level")
        fatal = wc.get("fatalMessages") or []
        item_submits = (counters.get("guiLastSubmits") or {}).get("item")
        print(
            f"{rel(path)} | {fmt_time(path)} | verdict={wc.get('verdict')} "
            f"screen={screen} level={level} "
            f"serverStarted={wc.get('serverStarted')} "
            f"fps={data.get('fps') or mc.get('fps')} "
            f"gameFps={gl.get('gameFps')} draw/s={gl.get('drawCallsPerSecond')} "
            f"items={item_submits} texErrors={len(gl.get('textureUploadErrors') or [])}"
        )
        if fatal:
            print("  fatal:", fatal[-1].replace("\n", " | ")[:700])
        blockers: list[str] = []
        webgl_upload_errors = 0
        for event in (data.get("interesting") or []) + (data.get("tail") or []):
            text = event.get("text", "") if isinstance(event, dict) else ""
            if "texSubImage2D: ArrayBufferView not big enough" in text:
                webgl_upload_errors += 1
            if any(
                marker in text
                for marker in (
                    "Duplicate id value",
                    "Invalid player data",
                    "lost connection",
                    "Client disconnected",
                    "Couldn't place player in world",
                    "Error executing task",
                    "Exception stopping the server",
                    "texSubImage2D: ArrayBufferView not big enough",
                    "GL_INVALID_OPERATION",
                    "WebGL: INVALID_OPERATION",
                )
            ):
                normalized = text.replace("\n", " | ")[:700]
                if normalized not in blockers:
                    blockers.append(normalized)
        if webgl_upload_errors:
            print(f"  webglUploadErrors: texSubImage2D short ArrayBuffer x{webgl_upload_errors}")
        for blocker in blockers[-4:]:
            print("  blocker:", blocker)


def check_source_patches() -> None:
    section("Source patch checks")
    text = OPENGL_BRIDGE.read_text(errors="replace") if OPENGL_BRIDGE.exists() else ""
    patcher = OPENGL_PATCHER.read_text(errors="replace") if OPENGL_PATCHER.exists() else ""
    openal_bridge = OPENAL_BRIDGE.read_text(errors="replace") if OPENAL_BRIDGE.exists() else ""
    openal_patcher = OPENAL_PATCHER.read_text(errors="replace") if OPENAL_PATCHER.exists() else ""
    stb_image = STB_IMAGE.read_text(errors="replace") if STB_IMAGE.exists() else ""
    browser_memory = LWJGL_BROWSER_MEMORY.read_text(errors="replace") if LWJGL_BROWSER_MEMORY.exists() else ""
    glfw_text = GLFW_BRIDGE.read_text(errors="replace") if GLFW_BRIDGE.exists() else ""
    glfw_patcher = GLFW_PATCHER.read_text(errors="replace") if GLFW_PATCHER.exists() else ""
    client_patcher = CLIENT_PATCHER.read_text(errors="replace") if CLIENT_PATCHER.exists() else ""
    vanilla_pack_resources = VANILLA_PACK_RESOURCES.read_text(errors="replace") if VANILLA_PACK_RESOURCES.exists() else ""
    browser_file_persistence = BROWSER_FILE_PERSISTENCE.read_text(errors="replace") if BROWSER_FILE_PERSISTENCE.exists() else ""
    browser_bit_storage = BROWSER_BIT_STORAGE.read_text(errors="replace") if BROWSER_BIT_STORAGE.exists() else ""
    browser_gui_item_cache = BROWSER_GUI_ITEM_CACHE.read_text(errors="replace") if BROWSER_GUI_ITEM_CACHE.exists() else ""
    vertex_array_cache_source = VERTEX_ARRAY_CACHE.read_text(errors="replace") if VERTEX_ARRAY_CACHE.exists() else ""
    wasm_hotpath_c = WASM_HOTPATH_C.read_text(errors="replace") if WASM_HOTPATH_C.exists() else ""
    build_wasm_hotpath = BUILD_WASM_HOTPATH.read_text(errors="replace") if BUILD_WASM_HOTPATH.exists() else ""
    generate_wasm_hotpath = GENERATE_WASM_HOTPATH.read_text(errors="replace") if GENERATE_WASM_HOTPATH.exists() else ""
    generate_pom = GENERATE_POM.read_text(errors="replace") if GENERATE_POM.exists() else ""
    build_teavm = BUILD_TEAVM.read_text(errors="replace") if BUILD_TEAVM.exists() else ""
    build_release = BUILD_RELEASE.read_text(errors="replace") if BUILD_RELEASE.exists() else ""
    compress_dist = COMPRESS_DIST.read_text(errors="replace") if COMPRESS_DIST.exists() else ""
    serve_dist = SERVE_DIST.read_text(errors="replace") if SERVE_DIST.exists() else ""
    index_html = INDEX_HTML.read_text(errors="replace") if INDEX_HTML.exists() else ""
    generated_resource_list = GENERATED_RESOURCE_LIST.read_text(errors="replace") if GENERATED_RESOURCE_LIST.exists() else ""
    generated_sounds = load_json(GENERATED_SOUNDS_JSON) if GENERATED_SOUNDS_JSON.exists() else {}
    postprocess_teavm_js = POSTPROCESS_TEAVM_JS.read_text(errors="replace") if POSTPROCESS_TEAVM_JS.exists() else ""
    postprocess_index_html = POSTPROCESS_INDEX_HTML.read_text(errors="replace") if POSTPROCESS_INDEX_HTML.exists() else ""
    tex_sub_start = text.find("public static void texSubImage2D(")
    tex_sub_end = text.find("@JSBody(script = \"\"\"", tex_sub_start)
    tex_sub_section = text[tex_sub_start:tex_sub_end] if tex_sub_start >= 0 and tex_sub_end > tex_sub_start else text
    distance_start = client_patcher.find("private static void patchIntegratedServerBrowserDistances")
    distance_end = client_patcher.find("private static void patchFreeTypeUtil", distance_start)
    distance_section = (
        client_patcher[distance_start:distance_end]
        if distance_start >= 0 and distance_end > distance_start
        else client_patcher
    )
    server_catchup_start = client_patcher.find("private static boolean patchMinecraftServerBrowserCatchupReset")
    server_catchup_end = client_patcher.find("private static boolean hookMinecraftServerStopDiagnostics", server_catchup_start)
    server_catchup_section = (
        client_patcher[server_catchup_start:server_catchup_end]
        if server_catchup_start >= 0 and server_catchup_end > server_catchup_start
        else client_patcher
    )
    run_tick_state_start = client_patcher.find('} else if (method.name.equals("runTick") && method.desc.equals("(Z)V")) {')
    run_tick_state_end = client_patcher.find("        }\n        if (!found)", run_tick_state_start)
    run_tick_state_section = (
        client_patcher[run_tick_state_start:run_tick_state_end]
        if run_tick_state_start >= 0 and run_tick_state_end > run_tick_state_start
        else ""
    )
    palette_decode_start = stb_image.find("case 3 -> {")
    palette_decode_end = stb_image.find("case 4 -> {", palette_decode_start)
    palette_decode_section = (
        stb_image[palette_decode_start:palette_decode_end]
        if palette_decode_start >= 0 and palette_decode_end > palette_decode_start
        else ""
    )
    checks = [
        (
            "BrowserOpenGL tracks UNPACK_ROW_LENGTH/SKIP_ROWS/SKIP_PIXELS",
            "unpackRowLength" in text
            and "unpackSkipRows" in text
            and "unpackSkipPixels" in text
            and "case 0x0CF2" in text
            and "case 0x0CF3" in text
            and "case 0x0CF4" in text,
        ),
        (
            "BrowserOpenGL normalizes illegal WebGL unpack alignment",
            "webGlUnpackAlignment" in text
            and "case 1, 2, 4, 8" in text
            and "default -> 1" in text,
        ),
        (
            "BrowserOpenGL pointer texture upload length includes row stride and skips",
            "unpackSkipRows * rowStride" in text
            and "unpackSkipPixels * bytesPerPixel" in text
            and "(height - 1) * rowStride" in text,
        ),
        (
            "BrowserOpenGL preserves GL_UNPACK_ROW_LENGTH/SKIP_* during texture upload",
            "gl.pixelStorei(gl.UNPACK_ROW_LENGTH,0)" not in tex_sub_section
            and "gl.pixelStorei(gl.UNPACK_SKIP_ROWS,0)" not in tex_sub_section
            and "gl.pixelStorei(gl.UNPACK_SKIP_PIXELS,0)" not in tex_sub_section,
        ),
        (
            "STBImage advances 8-bit palette PNG indices for block/item textures",
            "int index = readSample(data, bits, bitDepth, source);" in palette_decode_section
            and "if (bitDepth >= 8)" in palette_decode_section
            and "source += bitDepth / 8;" in palette_decode_section,
        ),
        (
            "LWJGL patcher delegates ARB vertex-attrib-binding instead of no-oping GUI layout calls",
            'add(methods, "ARBVertexAttribBinding", "glBindVertexBuffer", "(IIJI)V", "bindVertexBuffer")' in patcher
            and 'add(methods, "ARBVertexAttribBinding", "glVertexAttribBinding", "(II)V", "vertexAttribBinding")' in patcher
            and 'add(methods, "ARBVertexAttribBinding", "glVertexAttribFormat", "(IIIZI)V", "vertexAttribFormat")' in patcher
            and 'add(methods, "ARBVertexAttribBinding", "glVertexAttribIFormat", "(IIII)V", "vertexAttribIFormat")' in patcher
            and 'noop(methods, "ARBVertexAttribBinding", "glBindVertexBuffer", "(IIJI)V")' not in patcher
            and 'noop(methods, "ARBVertexAttribBinding", "glVertexAttribFormat", "(IIIZI)V")' not in patcher,
        ),
        (
            "BrowserOpenGL avoids slow per-draw baseVertex attrib rebinding when possible",
            "drawElementsWithBaseVertex" in text
            and "cacheShiftedIndexBuffer" in text
            and "shiftedIndexCache" in text
            and "baseVertexIndexDraws" in text
            and "window.__gaiusGL.drawElementsWithBaseVertex(mode,count,type,offset,1,baseVertex);" in text,
        ),
        (
            "BrowserOpenGL can use Wasm hot-path for baseVertex index shifting",
            "window.__gaiusWasmHotpath" in text
            and "wasmHotpath.shiftIndices" in text
            and "wasmHotpath.repackInterleaved" in text
            and "baseVertexIndexWasm" in text
            and "alignedAttribWasm" in text
            and "baseVertexIndexJsFallback" in text
            and "baseVertexIndexWasmFallback" in text,
        ),
        (
            "BrowserOpenGL caches VAO attribute validation instead of rescanning every draw",
            "attribVersion" in text
            and "misalignedAttribs" in text
            and "missingEnabledAttribs" in text
            and "alignedAttribFastSkips" in text
            and "attribTypeFastSkips" in text
            and "programAttribGlobalVersion" in text
            and "programAttribCache:new Map()" in text
            and "vao.programAttribCache.set(program|0" in text
            and "if (repaired && vao.programAttribCache.size) vao.programAttribCache.clear();" in text
            and "if (!vao.misalignedAttribs || !vao.misalignedAttribs.size)" in text
            and "this.prepareDrawAttribs(vao);\n                  if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size)" in text
            and "if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size)" in text
            and "vao.missingEnabledAttribs.forEach" in text,
        ),
        (
            "BrowserOpenGL gates direct-attrib restores behind dirty temporary pointer state",
            "bindAttribPointerAtOffset=function(pointer, offset, preserveDirectCache)" in text
            and "if (!preserveDirectCache)" in text
            and "directAttribDirty:false" in text
            and "if (!vao.directAttribDirty)" in text
            and "if (!vao.directAttribDirty) {\n                  return 0;" in text
            and "vao.directAttribDirty=false" in text
            and "vao.directAttribDirty=true" in text
            and "state.bindAttribPointerAtOffset(pointer,Number(pointer.offset),true)" in text
            and "this.bindAttribPointerAtOffset(pointer,Number(pointer.offset),false)" in text
            and "this.bindAttribPointerAtOffset(pointer,shiftedOffset,false)" in text
            and "shiftedAttribPointers[i],\n                      Number(shiftedAttribPointers[i].offset),\n                      true" in text
            and "baseVertexDirectRestores" in text,
        ),
        (
            "BrowserOpenGL avoids bumping VAO versions for redundant attrib state",
            "sameAttribPointer=function(a,b)" in text
            and "enableAttribFastSkips" in text
            and "disableAttribFastSkips" in text
            and "attribPointerFastSkips" in text
            and "vertexBufferFastSkips" in text
            and "attribBindingFastSkips" in text
            and "attribFormatFastSkips" in text
            and "if (vao.enabledAttribs.has(idx))" in text
            and "if (!vao.enabledAttribs.has(idx))" in text
            and "const sizeValue=size|0;" in text
            and "const validationChanged=!samePointer" in text
            and "const skips=((state.attribPointerFastSkips||0)+1)|0;" in text
            and "if ((skips & 255)===0)" in text
            and "samePointer && previousMisaligned===misaligned && previousPresence===present" in text
            and "previousPresence && samePointer && previousMisaligned===!aligned" in text
            and "if (previousPresence===present) state.bumpVaoAttribVersion(vao)" in text
            and "if (previous\n                  && (previous.buffer|0)===(buffer|0)" in text
            and "var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});" in text,
        ),
        (
            "BrowserOpenGL limits CPU shadow buffer copies for performance",
            "bufferShadowTotalBytes" in text
            and "maxSingleBufferShadowBytes" in text
            and "maxTotalBufferShadowBytes" in text
            and "bufferShadowPolicyVersion" in text
            and "bufferShadowDecisionCache" in text
            and "bumpBufferShadowPolicyVersion" in text
            and "window.__gaiusMaxSingleBufferShadowBytes" in text
            and "window.__gaiusMaxTotalBufferShadowBytes" in text
            and "256 * 1024 * 1024" in text
            and "1024 * 1024 * 1024" in text
            and "268435456" in text
            and "trimBufferShadows" in text
            and "deleteBufferShadow" in text
            and "bufferShadowSkippedEmpty" in text
            and "bufferShadowSkippedLarge" in text,
        ),
        (
            "BrowserOpenGL tracks misaligned attribute buffers with safe scan fallback",
            "initializeMisalignedBufferRefs" in text
            and "misalignedBufferRefs=new Map()" in text
            and "v.misalignedAttribBuffers=new Map()" in text
            and "addMbr" in text
            and "delMbr" in text
            and "if(!p&&this.bumpBufferShadowPolicyVersion)" in text
            and "if(this.bumpBufferShadowPolicyVersion)this.bumpBufferShadowPolicyVersion()" in text
            and "releaseVaoMisalignedBuffers" in text
            and "this.misalignedBufferRefs.get(id)" in text
            and "this.vaoEmu.forEach(function(v)" in text
            and "state.releaseVaoMisalignedBuffers(state.vaoEmu.get(array))" in text,
        ),
        (
            "BrowserOpenGL skips redundant WebGL state calls in hot paths",
            "drawCallsCount" in text
            and "drawWindowCallsCount" in text
            and "knownCaps" in text
            and "enabledCaps" in text
            and "viewportKey" in text
            and "scissorKey" in text
            and "state.currentProgram|0" in text
            and "state.currentVaoId|0" in text,
        ),
        (
            "BrowserOpenGL repairs stale colorMask before drawing to the default framebuffer",
            "colorMask:[true,true,true,true]" in text
            and "ensureColorWritesForFramebuffer" in text
            and "ensureDefaultFramebufferColorWrites" in text
            and "defaultFramebufferColorMaskRepairs" in text
            and "state.colorMask=[!!red,!!green,!!blue,!!alpha]" in text,
        ),
        (
            "BrowserOpenGL uses bulk buffer reads instead of per-byte MemoryUtil loops",
            "copy.get(data)" in text
            and "bytes.get(data)" in text
            and "floats.get(data)" in text
            and "ints.get(data)" in text
            and "MemoryUtil.memGetByte(address + i)" not in text
            and "MemoryUtil.memGetFloat(address + (long) i * 4L)" not in text
            and "MemoryUtil.memGetInt(address + (long) i * 4L)" not in text,
        ),
        (
            "Wasm hot-path module exports batch index shifting helpers",
            "gaius_shift_indices" in wasm_hotpath_c
            and "gaius_repack_interleaved" in wasm_hotpath_c
            and "gaius_unpack_bit_storage" in wasm_hotpath_c
            and "gaius_shift_indices_input_ptr" in wasm_hotpath_c
            and "gaius_shift_indices_output_ptr" in wasm_hotpath_c
            and "gaius_repack_source_ptr" in wasm_hotpath_c
            and "gaius_repack_layouts_ptr" in wasm_hotpath_c
            and "gaius_unpack_bit_storage_input_ptr" in wasm_hotpath_c
            and "gaius_unpack_bit_storage_output_ptr" in wasm_hotpath_c
            and "MAX_BIT_STORAGE_VALUES" in wasm_hotpath_c
            and "index_scratch" in wasm_hotpath_c
            and "copy_bytes" in wasm_hotpath_c
            and "MAX_INDICES" in wasm_hotpath_c
            and "MAX_REPACK_LAYOUTS" in wasm_hotpath_c
            and "GL_UNSIGNED_SHORT" in wasm_hotpath_c
            and "GL_UNSIGNED_INT" in wasm_hotpath_c,
        ),
        (
            "SimpleBitStorage unpack has browser Wasm/JS hot-path hook",
            "patchSimpleBitStorageBrowserUnpack" in client_patcher
            and "net/minecraft/util/SimpleBitStorage.class" in client_patcher
            and "dev/gaius/browser/BrowserBitStorage" in client_patcher
            and "([J[IIIJI)Z" in client_patcher
            and "public static native boolean unpack" in browser_bit_storage
            and "hotpath.unpackBitStorage" in browser_bit_storage
            and "bitStorageJsUnpack" in browser_bit_storage,
        ),
        (
            "BrowserOpenGL repairs shader integer/float attribute binding mismatches",
            "programAttribs" in text
            and "refreshProgramAttribs" in text
            and "ensureProgramAttribTypes" in text
            and "attribTypeRepairs" in text
            and "gl.getActiveAttrib" in text
            and "vertexAttribIPointer" in text,
        ),
        (
            "VertexArrayCache normalizes byte vertex colors for textured GUI/materials",
            "private static boolean shouldNormalize" in vertex_array_cache_source
            and "VertexFormatElement.Usage.COLOR" in vertex_array_cache_source
            and "VertexFormatElement.Usage.UV" in vertex_array_cache_source
            and "VertexFormatElement.Usage.GENERIC" in vertex_array_cache_source
            and "boolean normalized = shouldNormalize(element);" in vertex_array_cache_source
            and "element.usage() == VertexFormatElement.Usage.UV || element.usage() == VertexFormatElement.Usage.GENERIC" not in vertex_array_cache_source,
        ),
        (
            "BrowserOpenGL limits attrib repack to current shader and restores direct pointers",
            "activeAttribLocations" in text
            and "attribIsActive" in text
            and "restoreDirectAttribPointers" in text
            and "activeAttribLazyRefresh" in text
            and "programAttribLazyRefresh" in text
            and "directAttribRestores" in text
            and "alignedAttribProgram" in text
            and "alignedAttribGlobalVersion" in text
            and "vao.missingEnabledAttribs.add(pointers[msi].index|0)" in text
            and "vao.missingEnabledAttribs.add(pointers[nvi].index|0)" in text
            and "vao.missingEnabledAttribs.delete(bindLayout.index|0)" in text,
        ),
        (
            "BrowserOpenGL exposes draw-call throughput telemetry",
            "recordDrawCall" in text
            and "drawCallsPerSecond" in text
            and "drawWindowCalls" in text
            and "__gaiusReadWebGLErrors" in text
            and "glErrors" in text
            and "return window.__gaiusReadWebGLErrors ? (window.__gaiusWebGL.getError()|0) : 0" in text,
        ),
        (
            "BrowserOpenGL exposes texture upload diagnostics for broken item/atlas triage",
            "recordTextureUpload" in text
            and "recordTextureError" in text
            and "textureUploadRecent" in text
            and "textureUploadErrors" in text
            and "textureInfo" in text,
        ),
        (
            "BrowserOpenGL exposes screen widget telemetry for fast UI automation",
            "describeScreenWidgets" in text
            and "screenWidgetsJson" in text
            and "screenWidgets" in text
            and "AbstractWidget" in text
            and "GuiEventListener" in text
            and "widget.getMessage()" in text
            and "widget.getX()" in text
            and "widget.getY()" in text,
        ),
        (
            "BrowserOpenGL falls back to ClientPacketListener level for state telemetry",
            "fallbackClientLevel" in text
            and "typedMinecraft.getConnection()" in text
            and "connection.getLevel()" in text
            and "ClientPacketListener" in text,
        ),
        (
            "BrowserOpenGL reports Minecraft state with safe browser class names",
            '"net.minecraft.client.multiplayer.ClientLevel"' in text
            and '"net.minecraft.client.player.LocalPlayer"' in text
            and '"<class-name-unavailable>"' in text
            and "value.getClass().getName()" in text
            and "catch (Throwable ignored)" in text,
        ),
        (
            "BrowserOpenGL throttles inventory-screen world background rendering",
            "shouldSkipWorldRenderForScreen" in text
            and "inventoryWorldRenderFrame" in text
            and "inventoryWorldRenderScreen" in text
            and "net.minecraft.client.gui.screens.inventory." in text
            and "return frame > 1" in text
            and "(frame & 3)" not in text,
        ),
        (
            "BrowserOpenGL exposes GUI item atlas telemetry",
            "guiItemAtlasCurrent" in text
            and "guiItemAtlasLast" in text
            and "reportGuiItemAtlasHit" in text
            and "reportGuiItemAtlasRender" in text
            and "reportGuiItemAtlasOversized" in text
            and "reportGuiItemAtlasInvalidated" in text
            and "guiItemAtlasTelemetryEnabled" in text
            and "atlasDiag" in text
            and "if (!counters || !counters.guiItemAtlasTelemetryEnabled) return;" in text
            and "renderAnimatedRefresh" in text
            and "renderMiss" in text,
        ),
        (
            "BrowserOpenGL disables cull face once per GUI draw batch",
            "guiDrawsRemaining" in text
            and "guiCullFaceBatchActive" in text
            and "guiCullFaceBatchDisables" in text
            and "guiCullFaceBatchRestores" in text
            and "guiCullFaceBatchForcedRestores" in text
            and "gl.disable(gl.CULL_FACE)" in text
            and "gl.enable(gl.CULL_FACE)" in text,
        ),
        (
            "BrowserOpenGL can sample GUI vertex/index state behind diag=gui",
            "sampleGuiDraw" in text
            and "guiVertexSampleRecent" in text
            and "indexSample" in text
            and "sampleVertexAttrib" in text,
        ),
        (
            "BrowserOpenGL repairs browser GUI item offscreen scissor for native item rendering",
            "isGuiItemOffscreen512Target" in text
            and "withGuiItemOffscreenScissorRepair" in text
            and "offscreen512ScissorRepairs" in text
            and "findFramebufferColorTextureId" in text
            and "framebufferColorTextures" in text
            and "framebufferColorTextureMisses" in text
            and "framebufferColorTextureFallbacks" in text
            and "this.framebufferColorTextures.has(id)" in text
            and "restoreGuiItemOffscreenScissor" in text
            and "guiItemOffscreenScissorDisabled" in text
            and "offscreen512ScissorBatchDisables" in text
            and "offscreen512ScissorBatchRestores" in text
            and "state.restoreGuiItemOffscreenScissor('bindFramebuffer')" in text
            and "state.restoreGuiItemOffscreenScissor('gui-draw-plan')" in text
            and "gl.disable(gl.SCISSOR_TEST)" in text
            and "gl.enable(gl.SCISSOR_TEST)" in text
            and "window.__gaiusGL.withGuiItemOffscreenScissorRepair(function()" in text,
        ),
        (
            "BrowserOpenGL maps WebGL buffers into registered MemoryUtil memory",
            "MemoryUtil.memAlloc((int) length)" in text
            and "MemoryUtil.memFree(mapped.buffer)" in text,
        ),
        (
            "BrowserOpenAL implements Web Audio source/buffer playback",
            "AudioContext" in openal_bridge
            and "window.__gaiusAudioStats" in openal_bridge
            and "createBufferSource" in openal_bridge
            and "getChannelData" in openal_bridge
            and "bufferDataJs" in openal_bridge
            and "sourcePlayJs" in openal_bridge,
        ),
        (
            "LWJGL OpenAL patcher delegates AL10/AL11 calls to BrowserOpenAL",
            "Redirects the OpenAL subset" in openal_patcher
            and "org/lwjgl/openal/BrowserOpenAL" in openal_patcher
            and 'add(methods, "AL10", "alBufferData"' in openal_patcher
            and 'add(methods, "AL10", "alSourcePlay"' in openal_patcher
            and 'add(methods, "AL10", "alGenSources"' in openal_patcher,
        ),
        (
            "Minecraft audio path creates browser channel pools instead of silent mode",
            "patchBrowserAudio" in client_patcher
            and "Library$CountingChannelPool" in client_patcher
            and "org/lwjgl/openal/BrowserOpenAL" in client_patcher
            and "patchSoundEngineBrowserSilent" not in client_patcher
            and "browser.sound.silent" not in client_patcher,
        ),
        (
            "BrowserOpenGL avoids unconditional CPU-side buffer shadow copies",
            "shadowRequiredBuffers" in text
            and "shouldShadowBufferTarget" in text
            and "shadowBufferDataForTarget" in text
            and "shadowBufferSubDataForTarget" in text
            and "initializeShadowDecisionCache" in text
            and "bufferShadowDecisionCache" in text
            and "c.set(id,{p:p,n:n})" in text
            and "bufferShadowSkippedUnneeded" in text
            and "bufferShadowSkippedUnneededCount" in text
            and "bufferShadowRequiredMarkCount" in text
            and "this.shadowRequiredBuffers.has(id)" in text
            and "this.vaoEmu.forEach(function(v)" in text
            and "markBufferShadowRequired" in text
            and "misaligned-attrib" in text,
        ),
        (
            "BrowserMemory preserves mapped ByteBuffer addresses through memSlice",
            "public static long register(ByteBuffer bytes)" in browser_memory
            and "registerDerived(result, buffer, offset)" in browser_memory
            and "remember(result, base + Integer.toUnsignedLong(byteOffset))" in browser_memory,
        ),
        (
            "BrowserMemory frees mapped buffers without scanning the whole address table",
            "REGION_BUFFERS" in browser_memory
            and "private static void remember(Buffer buffer, long address)" in browser_memory
            and "REGION_BUFFERS.remove(id)" in browser_memory,
        ),
        (
            "BrowserMemory avoids registering transient memCopy/memSet views",
            "private static ByteBuffer transientView(long address, int capacity)" in browser_memory
            and "private static ByteBuffer transientView(Region region, int offset, int capacity)" in browser_memory
            and "private static ByteBuffer transientView(ByteBuffer bytes, int offset, int capacity)" in browser_memory
            and "ByteBuffer target = transientView(address, (int) byteCount)" in browser_memory
            and "targetView.put(sourceView)" in browser_memory
            and "copyOverlapping(sourceRegion.bytes, sourceOffset, targetOffset, count)" in browser_memory
            and "byteBuffer(source + copied, chunk)" not in browser_memory
            and "byteBuffer(target + copied, chunk)" not in browser_memory,
        ),
        (
            "BrowserMemory reuses hot temporary arrays instead of per-call allocation",
            "private static final int TEMP_BYTES_SIZE = 65536" in browser_memory
            and "private static final ThreadLocal<byte[]> BYTE_ARRAYS" in browser_memory
            and "return BYTE_ARRAYS;" in browser_memory
            and "byte[] temporary = BYTE_ARRAYS.get()" in browser_memory
            and "byte[] bytes = temporaryBytes(length)" in browser_memory
            and "new byte[Math.min(count, 65536)]" not in browser_memory
            and "return ThreadLocal.withInitial(() -> new byte[8192])" not in browser_memory,
        ),
        (
            "BrowserMemory exposes single-pass BufferBuilder fast vertex writer",
            "public static void putFastVertex(" in browser_memory
            and "Region region = region(pointer)" in browser_memory
            and "checkRange(region, base, fullFormat ? 35 : 28)" in browser_memory
            and "putRgba(bytes, base + 12, color)" in browser_memory
            and "putPackedUv(bytes, lightStart, lightCoords)" in browser_memory
            and "private static byte normalIntValue(float value)" in browser_memory
            and "patchBufferBuilderBrowserFastVertex" in client_patcher
            and "org/lwjgl/system/BrowserMemory" in client_patcher
            and "(JFFFIFFIIFFFZ)V" in client_patcher,
        ),
        (
            "BrowserMemory exposes fast GUI/text BufferBuilder writers",
            "public static void putPosition(long pointer, float x, float y, float z)" in browser_memory
            and "public static void putTransformedPosition(long pointer, Matrix4fc pose, float x, float y, float z)" in browser_memory
            and "public static void putRgba(long pointer, int argb)" in browser_memory
            and "public static void putFloatPair(long pointer, float x, float y)" in browser_memory
            and "public static void putPackedUv(long pointer, int packedUv)" in browser_memory
            and "public static void putNormal(long pointer, float x, float y, float z)" in browser_memory
            and "patchBufferBuilderBrowserGuiWriters" in client_patcher
            and "putTransformedPosition" in client_patcher
            and "(JLorg/joml/Matrix4fc;FFF)V" in client_patcher
            and "putFloatPair" in client_patcher
            and "putPackedUv" in client_patcher,
        ),
        (
            "BrowserGlfw provides GLFW key names for printable keys",
            "public static String getKeyName(int key, int scancode)" in glfw_text
            and "GLFW.GLFW_KEY_A && value <= GLFW.GLFW_KEY_Z" in glfw_text
            and "GLFW.GLFW_KEY_KP_ADD" in glfw_text
            and "default -> null" in glfw_text,
        ),
        (
            "BrowserGlfw defaults to balanced DPR and disables slow preserveDrawingBuffer",
            "__gaiusResolvePixelRatio" in glfw_text
            and "__gaiusApplyCanvasResolution" in glfw_text
            and "__gaiusMaxDpr" in glfw_text
            and "__gaiusMinDpr" in glfw_text
            and "__gaiusMenuMinDpr" in glfw_text
            and "__gaiusWorldMinDpr" in glfw_text
            and ": 1.0" in glfw_text
            and "|| 1.0" in glfw_text
            and "1.0," in glfw_text
            and "0.9," not in glfw_text[glfw_text.find("window.__gaiusResolvePixelRatio"):glfw_text.find("window.__gaiusApplyCanvasResolution")]
            and "const inWorld = !!(minecraftState && minecraftState.level)" in glfw_text
            and "clamp(Math.min(raw, maxDpr), minDpr, 3.0)" in glfw_text
            and "preserveDrawingBuffer" in glfw_text
            and "get('preserveDrawingBuffer') === '1'" in glfw_text,
        ),
        (
            "BrowserGlfw records game FPS from swapBuffers",
            "public static native void swapBuffers(long window)" in glfw_text
            and "gameFps" in glfw_text
            and "gameFrames" in glfw_text
            and "gameLastSampleAt" in glfw_text,
        ),
        (
            "BrowserGlfw yields during waitEventsTimeout instead of busy-spinning FPS waits",
            "public static void waitEventsTimeout(double timeout)" in glfw_text
            and "sleepForBrowserMillis" in glfw_text
            and "Thread.sleep(millis)" in glfw_text
            and "Math.floor(timeout * 1000.0)" in glfw_text
            and "millis <= 0L" in glfw_text
            and "Thread.yield()" in glfw_text
            and "Math.min(7L, millis)" in glfw_text,
        ),
        (
            "BrowserGlfw primes cursor callbacks so the first menu click is not swallowed",
            "callback.invoke(WINDOW, cursorX, cursorY)" in glfw_text
            and "updateCursorFromMouseEvent" in glfw_text
            and "pushMouseMove([4,0,0,0,0,p[0],p[1]])" in glfw_text,
        ),
        (
            "BrowserGlfw automatically warms up first GUI input on a harmless corner click",
            "maybeQueueInputWarmup()" in glfw_text
            and "__gaiusInputWarmupDone" in glfw_text
            and "minecraftState.screen" in glfw_text
            and "events.push([4,0,0,0,0,1,1])" in glfw_text,
        ),
        (
            "BrowserGlfw coalesces mousemove events and avoids Array.shift in pollEvents",
            "__gaiusGlfwEventHead" in glfw_text
            and "__gaiusGlfwPendingMouseMove" in glfw_text
            and "const pushMouseMove = event =>" in glfw_text
            and "events[pending] = event" in glfw_text
            and "events.splice(0,head)" in glfw_text
            and "window.__gaiusGlfwEvents.shift()" not in glfw_text,
        ),
        (
            "GLFW patcher delegates key name/scancode lookups",
            'add(result, "glfwGetKeyName", "(II)Ljava/lang/String;", "getKeyName")' in glfw_patcher
            and 'add(result, "glfwGetKeyScancode", "(I)I", "getKeyScancode")' in glfw_patcher,
        ),
        (
            "Minecraft patcher applies FaceBakery browser float tolerance",
            "patchFaceBakeryBrowserFloatTolerance" in client_patcher
            and 'find(node, "findVertex", "([Lorg/joml/Vector3fc;IFFF)I")' in client_patcher
            and "java/lang/Math" in client_patcher
            and "1.0E-4f" in client_patcher,
        ),
        (
            "Minecraft patcher makes Entity constructor UUIDs use global browser random source",
            "patchEntityBrowserUuidUsesGlobalRandom" in client_patcher
            and "net/minecraft/world/entity/Entity.class" in client_patcher
            and "createInsecureUUID" in client_patcher
            and "(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;" in client_patcher
            and "()Ljava/util/UUID;" in client_patcher
            and "Entity UUID random source patch point changed" in client_patcher,
        ),
        (
            "Minecraft patcher routes GUI item state creation through browser safety hook",
            "patchGuiGraphicsBrowserItemCache" in client_patcher
            and "BrowserGuiItemCache" in client_patcher
            and "guiState" in client_patcher
            and "resetPool" in client_patcher
            and "TrackingItemStackRenderState" in client_patcher
            and "ItemModelResolver" in client_patcher
            and "updateForTopItem" in client_patcher
            and "STATE_POOL_SIZE" in browser_gui_item_cache
            and "resetPool" in browser_gui_item_cache
            and "resetForReuse" in browser_gui_item_cache
            and "BrowserTrackingItemStackRenderState" in browser_gui_item_cache
            and "stableModelIdentity" in browser_gui_item_cache
            and "MODEL_IDENTITY_CACHE_SIZE" in browser_gui_item_cache
            and "singleModelIdentity" in browser_gui_item_cache
            and "multiModelIdentity" in browser_gui_item_cache
            and "stableModelIdentity(super.getModelIdentity())" in browser_gui_item_cache
            and "synchronized" not in browser_gui_item_cache
            and "super.getModelIdentity()" in browser_gui_item_cache
            and "updateForTopItem" in browser_gui_item_cache
            and "hashItemAndComponents" not in browser_gui_item_cache
            and "GUI_STATES" not in browser_gui_item_cache,
        ),
        (
            "Minecraft patcher freezes cached animated GUI item atlas entries",
            "freezeGuiAnimatedItemAtlasHit" in client_patcher
            and "lambda$prepareItemElements$3" in client_patcher
            and "TrackingItemStackRenderState" in client_patcher
            and "Opcodes.POP" in client_patcher
            and "Opcodes.ICONST_0" in client_patcher
            and "reportGuiItemAtlasHit" not in client_patcher
            and "reportGuiItemAtlasRender" not in client_patcher,
        ),
        (
            "Minecraft patcher avoids per-item Component.toString for GUI item debug names",
            "replaceGuiItemRenderStateDebugName" in client_patcher
            and "browser:item" in client_patcher
            and "net/minecraft/network/chat/Component" in client_patcher
            and "net/minecraft/world/item/ItemStack" in client_patcher
            and "previousRealInstruction" in client_patcher,
        ),
        (
            "Minecraft patcher pre-sizes browser DynamicUniforms UBO storage",
            "patchDynamicUniformsBrowserInitialCapacity" in client_patcher
            and "net/minecraft/client/renderer/DynamicUniforms.class" in client_patcher
            and "net/minecraft/client/renderer/DynamicUniformStorage" in client_patcher
            and "int[] browserCapacities = {128, 128}" in client_patcher
            and "Opcodes.SIPUSH" in client_patcher
            and "previousRealInstruction(call)" in client_patcher,
        ),
        (
            "Minecraft patcher throttles browser inventory background world rendering",
            "patchGameRendererBrowserAutoScreenshot" in client_patcher
            and "patchGameRendererBrowserInventoryWorldRenderThrottle" in client_patcher
            and "shouldSkipWorldRenderForScreen" in client_patcher
            and "GameRenderer.renderLevel call was not found" in client_patcher
            and "GameRenderer world profiler pop was not found" in client_patcher,
        ),
        (
            "Minecraft patcher closes stale loading screen once world render is active",
            "closeLevelLoadingScreenBeforeWorldRender" in client_patcher
            and "client.levelReady.closeLoadingScreenFromWorldRender" in client_patcher
            and "net/minecraft/client/gui/screens/LevelLoadingScreen" in client_patcher
            and "net/minecraft/client/Minecraft" in client_patcher
            and '"player"' in client_patcher
            and '"level"' in client_patcher,
        ),
        (
            "Minecraft patcher throttles browser section compile/upload work per frame",
            "patchLevelRendererBrowserSectionCompileThrottle" in client_patcher
            and "LevelRenderer browser section compile throttle patch points" in client_patcher
            and "patchSectionRenderDispatcherBrowserThrottles" in client_patcher
            and "uploadAllPendingUploads" in client_patcher
            and "rebuildSectionSync" in client_patcher
            and "IF_ICMPLT" in client_patcher
            and "java/util/List" in client_patcher
            and "java/util/Queue" in client_patcher
            and "SectionMesh" in client_patcher,
        ),
        (
            "Minecraft patcher limits browser ClientLevel animateTick budget",
            "patchClientLevelBrowserAnimateTickBudget" in client_patcher
            and "ClientLevel.animateTick browser budget patch point" in client_patcher
            and "new IntInsnNode(Opcodes.BIPUSH, 64)" in client_patcher
            and "push.operand == 667" in client_patcher,
        ),
        (
            "Minecraft patcher uses static browser menu backgrounds for FPS",
            "patchScreenBrowserFastMenus" in client_patcher
            and "patchTitleScreenBrowserFastMenus" in client_patcher
            and "patchAbstractButtonBrowserFastSprite" in client_patcher
            and "renderPanorama" in client_patcher
            and "renderMenuBackground" in client_patcher
            and "realmsNotificationsEnabled" in client_patcher
            and "GuiGraphics" in client_patcher
            and "fill" in client_patcher
            and "PanoramaRenderer.render" not in client_patcher[client_patcher.find("patchScreenBrowserFastMenus"):client_patcher.find("patchTitleScreenBrowserFastMenus")],
        ),
        (
            "Minecraft patcher reports browser state after runTick mutations",
            "private static InsnList minecraftStateReport()" in client_patcher
            and "method.instructions.insertBefore(instruction, minecraftStateReport())" in run_tick_state_section
            and "instruction.getOpcode() == Opcodes.RETURN" in run_tick_state_section
            and "method.instructions.insert(code)" not in run_tick_state_section,
        ),
        (
            "Minecraft patcher processes queued packets during browser forced ticks",
            "private static InsnList processQueuedPacketsDuringForcedTick()" in client_patcher
            and "client.processQueuedPacketsForcedTick" in client_patcher
            and "net/minecraft/network/PacketProcessor" in client_patcher
            and "processQueuedPackets" in client_patcher
            and "Minecraft forced tick packet queue hook point was not found" in client_patcher,
        ),
        (
            "Minecraft patcher forces browser singleplayer distances to 2/2",
            "patchIntegratedServerBrowserDistances" in distance_section
            and "private static InsnList fixedBrowserDistance(int browserDistance)" in distance_section
            and "browserDistanceConstant(2)" in distance_section
            and "patchPlayerListDistanceGetter(node, \"getViewDistance\", \"viewDistance\", 2)" in distance_section
            and "patchPlayerListDistanceGetter(node, \"getSimulationDistance\", \"simulationDistance\", 2)" in distance_section
            and "IntegratedServer distance override patch points" in distance_section
            and "Opcodes.POP" in distance_section
            and "Opcodes.ICONST_2" in distance_section,
        ),
        (
            "Minecraft patcher writes browser region files without deflate compression",
            "patchRegionFileVersionBrowserNoCompression" in client_patcher
            and "net/minecraft/world/level/chunk/storage/RegionFileVersion.class" in client_patcher
            and '"getSelected"' in client_patcher
            and '"VERSION_NONE"' in client_patcher
            and "Opcodes.GETSTATIC" in client_patcher
            and "Opcodes.ARETURN" in client_patcher,
        ),
        (
            "Minecraft patcher recovers duplicate browser entity UUIDs instead of dropping entities",
            "patchPersistentEntityUuidBrowserRecovery" in client_patcher
            and "net/minecraft/world/level/entity/PersistentEntitySectionManager.class" in client_patcher
            and "UUID of added entity already exists: {}" in client_patcher
            and "net/minecraft/world/entity/Entity" in client_patcher
            and "setUUID" in client_patcher
            and "net/minecraft/util/Mth" in client_patcher
            and "createInsecureUUID" in client_patcher
            and "server.entityUuidRecovered" in client_patcher
            and "Opcodes.BIPUSH, 8" in client_patcher,
        ),
        (
            "Minecraft patcher resets browser server tick catchup",
            "patchMinecraftServerBrowserCatchupReset" in server_catchup_section
            and "Can't keep up! Is the server overloaded?" in server_catchup_section
            and "nextTickTimeNanos" in server_catchup_section
            and "lastOverloadWarningNanos" in server_catchup_section
            and "net/minecraft/util/Util" in server_catchup_section,
        ),
        (
            "Minecraft patcher uses browser fast initial spawn for normal worlds",
            "replaceInitialSpawnForBrowser" in client_patcher
            and "server.browserFastInitialSpawn" in client_patcher
            and "Climate$Sampler" in client_patcher
            and "findSpawnPosition" in client_patcher
            and "Heightmap$Types" in client_patcher
            and "WORLD_SURFACE" in client_patcher
            and "PlayerSpawnFinder" in client_patcher
            and "BlockPos.ZERO" not in client_patcher[client_patcher.find("replaceInitialSpawnForBrowser"):client_patcher.find("private static boolean patchMinecraftServerBrowserCatchupReset")]
            and "sipush        128" not in client_patcher[client_patcher.find("replaceInitialSpawnForBrowser"):client_patcher.find("private static boolean patchMinecraftServerBrowserCatchupReset")],
        ),
        (
            "Minecraft patcher disables desktop asset index probing in browser",
            "patchVanillaPackResourcesBuilder" in client_patcher
            and "patchIndexedAssetSourceBrowserNoop" in client_patcher
            and "browser-assets" in client_patcher
            and "ImmutableMap" in client_patcher,
        ),
        (
            "CreateWorldScreen keeps normal world generation and enables commands by default",
            "foundNormalPreset" in client_patcher
            and "foundDefaultOptions" in client_patcher
            and "foundNormalDimensions" in client_patcher
            and "patchedAllowCommands" in client_patcher
            and "patchedInitialAllowCommands" in client_patcher
            and "setAllowCommands" in client_patcher
            and 'field.name = "FLAT"' not in client_patcher
            and 'call.name = "testWorldWithRandomSeed"' not in client_patcher
            and 'call.name = "createFlatWorldDimensions"' not in client_patcher,
        ),
        (
            "LevelLoadTracker has browser timeout escape for missing loading packets",
            "patchLevelLoadTrackerBrowserTimeout" in client_patcher
            and "Timed out while waiting for initial level loading packets in the browser" in client_patcher
            and "LevelLoadTracker$WaitingForServer" in client_patcher
            and "LevelLoadTracker$WaitingForPlayerChunk" in client_patcher
            and 'find(tracker, "isLevelReady", "()Z")' in client_patcher
            and "client.levelReady.timeoutFallback" in client_patcher
            and "client.levelReady.playerPresentFallback" in client_patcher
            and "client.levelReady.closeLoadingScreen" in client_patcher
            and "closeLevelLoadingScreenIfPresent" in client_patcher
            and "ClientPacketListener level-ready branch shape changed" in client_patcher
            and '"timeoutAfter"' in client_patcher
            and 'find(waitingForPlayerChunk, "isReady", "()Z")' in client_patcher
            and "constant.cst = 5L" in client_patcher,
        ),
        (
            "FramerateLimitTracker disables browser AFK/menu throttling",
            "patchFramerateLimitTrackerBrowserNoThrottle" in client_patcher
            and "FramerateLimitTracker$FramerateThrottleReason" in client_patcher
            and '"getThrottleReason"' in client_patcher
            and '"NONE"' in client_patcher,
        ),
        (
            "VanillaPackResources wraps embedded streams as byte arrays",
            "new ByteArrayInputStream(input.readAllBytes())" in vanilla_pack_resources
            and "openResourceStream" in vanilla_pack_resources
            and 'getResourceAsStream("/" + normalized)' in vanilla_pack_resources,
        ),
        (
            "VanillaPackResources uses resource-list set for fast asset existence checks",
            "resourceSet" in vanilla_pack_resources
            and "new HashSet<>(List.of(this.resources))" in vanilla_pack_resources
            and "rootSupplierIfPresent" in vanilla_pack_resources
            and "existsOnClasspath" in vanilla_pack_resources,
        ),
        (
            "VanillaPackResources supplies browser pack icon fallback",
            "FALLBACK_PACK_ICON" in vanilla_pack_resources
            and "assets/minecraft/textures/misc/unknown_pack.png" in vanilla_pack_resources
            and '"pack.png".equals(resource)' in vanilla_pack_resources
            and "return supplierIfPresent(FALLBACK_PACK_ICON)" in vanilla_pack_resources,
        ),
        (
            "VanillaPackResources caches browser resource listings by prefix",
            "listedResourceCache" in vanilla_pack_resources
            and "ListedResource[]" in vanilla_pack_resources
            and "listedResources(type, namespace, path)" in vanilla_pack_resources
            and "listedResourceCache.put(key, cached)" in vanilla_pack_resources,
        ),
        (
            "Generated browser resource list contains vanilla texture atlases and representative textures",
            "assets/minecraft/atlases/blocks.json" in generated_resource_list
            and "assets/minecraft/atlases/items.json" in generated_resource_list
            and "assets/minecraft/atlases/gui.json" in generated_resource_list
            and "pack.png" in generated_resource_list
            and "assets/minecraft/textures/block/stone.png" in generated_resource_list
            and "assets/minecraft/textures/item/diamond.png" in generated_resource_list
            and "assets/minecraft/textures/gui/title/minecraft.png" in generated_resource_list
            and "data/minecraft/datapacks/minecart_improvements/pack.mcmeta" in generated_resource_list,
        ),
        (
            "Generated browser resource list contains filtered sound metadata and playable UI/grass sounds",
            "assets/minecraft/sounds.json" in generated_resource_list
            and "assets/minecraft/sounds/random/click.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/click_stereo.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/wood_click.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/levelup.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/orb.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/in.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/out.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/challenge_complete.ogg" in generated_resource_list
            and "assets/minecraft/sounds/dig/grass1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/step/grass1.ogg" in generated_resource_list,
        ),
        (
            "Generated browser sounds.json only advertises copied browser sounds",
            isinstance(generated_sounds, dict)
            and bool(generated_sounds)
            and "ui.button.click" in generated_sounds
            and "ui.toast.in" in generated_sounds
            and "block.grass.step" in generated_sounds
            and "music.overworld.forest" not in generated_sounds
            and "ambient.basalt_deltas.additions" not in generated_sounds,
        ),
        (
            "TeaVM resource list includes vanilla root pack icon",
            '$0 == "pack.png"' in build_teavm
            and "minecraft-resources.txt" in build_teavm,
        ),
        (
            "TeaVM build maps and filters selected asset-index sounds into browser resources",
            "browser_sound_assets" in build_teavm
            and "assetIndex.id" in build_teavm
            and "assets/%s" in build_teavm
            and "minecraft/sounds/random/click_stereo.ogg" in build_teavm
            and "minecraft/sounds/ui/toast/in.ogg" in build_teavm
            and "minecraft/sounds/step/grass1.ogg" in build_teavm
            and "Filtered browser sounds.json" in build_teavm
            and "Mapped browser sound assets" in build_teavm,
        ),
        (
            "Browser storage seeds/enforces browser performance client options",
            "DEFAULT_BROWSER_OPTIONS" in browser_file_persistence
            and "BROWSER_PERFORMANCE_OPTIONS" in browser_file_persistence
            and "seedDefaultOptions" in browser_file_persistence
            and "enforcePerformanceOptions" in browser_file_persistence
            and "renderDistance:2" in browser_file_persistence
            and "simulationDistance:5" in browser_file_persistence
            and "entityDistanceScaling:0.5" in browser_file_persistence
            and "maxFps:120" in browser_file_persistence
            and 'graphicsPreset:\\"fast\\"' in browser_file_persistence
            and 'renderClouds:\\"false\\"' in browser_file_persistence
            and "menuBackgroundBlurriness:0" in browser_file_persistence
            and "panoramaSpeed:0.0" in browser_file_persistence
            and "screenEffectScale:0.0" in browser_file_persistence
            and "maxAnisotropyBit:1" in browser_file_persistence
            and "textureFiltering:0" in browser_file_persistence
            and "particles:2" in browser_file_persistence
            and "storage-restore-crashed" in browser_file_persistence
            and "writeDefaultOptions" in browser_file_persistence
            and "catch (Throwable exception)" in browser_file_persistence
            and "existing != null && existing.isFile()" in browser_file_persistence,
        ),
        (
            "Minecraft patcher replaces ICU LocalTime item model path in browser",
            "patchLocalTimeItemModelProperty" in client_patcher
            and "java/util/Date" in client_patcher
            and "getMonth" in client_patcher
            and "MM-dd" in client_patcher
            and "addAppendTwoDigit" in client_patcher,
        ),
        (
            "GlDevice max texture size uses WebGL limit directly",
            "getMaxSupportedTextureSize" in client_patcher
            and "_getInteger" in client_patcher
            and "3379" in client_patcher
            and "GlStateManager._texImage2D" not in client_patcher,
        ),
        (
            "TeaVM build can switch between debug and optimized release output",
            "GAIUS_TEA_OPTIMIZATION_LEVEL" in generate_pom
            and "GAIUS_SOURCE_MAPS" in generate_pom
            and "GAIUS_DEBUG_INFO" in generate_pom
            and "GAIUS_MINIFYING" in generate_pom
            and "GAIUS_SHORT_FILE_NAMES" in generate_pom
            and "GAIUS_ASSERTIONS_REMOVED" in generate_pom
            and "<optimizationLevel>$optimization_level</optimizationLevel>" in generate_pom,
        ),
        (
            "Release build defaults to optimized minified no-debug output",
            "GAIUS_TEA_OPTIMIZATION_LEVEL" in build_release
            and "ADVANCED" in build_release
            and "GAIUS_SOURCE_MAPS" in build_release
            and "false" in build_release
            and "GAIUS_MINIFYING" in build_release
            and "true" in build_release
            and "build-wasm-hotpath.sh" in build_release
            and "GAIUS_SKIP_WASM_HOTPATH" in build_release
            and "compress-dist.sh" in build_release,
        ),
        (
            "TeaVM build can skip overlay rebuild after a verified overlay-only pass",
            "GAIUS_SKIP_OVERLAY_BUILD" in build_teavm
            and 'Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true' in build_teavm
            and "build-overlays.sh" in build_teavm,
        ),
        (
            "TeaVM JS postprocess patches NaN-safe long conversion",
            "postprocess-teavm-js.py" in build_teavm
            and "Number.isFinite" in postprocess_teavm_js
            and "9223372036854775807" in postprocess_teavm_js,
        ),
        (
            "TeaVM build postprocesses ignored launcher HTML for Chrome startup",
            "postprocess-index-html.py" in build_teavm
            and "fallbackBuildToken" in postprocess_index_html
            and "fresh" in postprocess_index_html
            and "__gaiusBootTimings" in postprocess_index_html
            and "waitForPaint" in postprocess_index_html
            and 'rel="icon"' in postprocess_index_html,
        ),
        (
            "Wasm hot-path build emits dist wasm without requiring TeaVM",
            "--target=wasm32" in build_wasm_hotpath
            and "-Wl,--no-entry" in build_wasm_hotpath
            and "-Wl,--export-memory" in build_wasm_hotpath
            and "gaius-hotpath.wasm" in build_wasm_hotpath
            and "generate-wasm-hotpath.py" in build_wasm_hotpath
            and "wasm-ld/ld.lld was not found" in build_wasm_hotpath
            and "gaius_shift_indices" in build_wasm_hotpath
            and "gaius_repack_interleaved" in build_wasm_hotpath
            and "gaius_unpack_bit_storage" in build_wasm_hotpath,
        ),
        (
            "Generated Wasm hot-path fallback exports shift/repack/unpack helpers",
            "def make_module()" in generate_wasm_hotpath
            and "gaius_shift_indices" in generate_wasm_hotpath
            and "gaius_repack_interleaved" in generate_wasm_hotpath
            and "gaius_unpack_bit_storage" in generate_wasm_hotpath
            and "unpack_bit_storage_body" in generate_wasm_hotpath
            and "MEMORY_PAGES = 1024" in generate_wasm_hotpath
            and "sleb(value" in generate_wasm_hotpath
            and "MAX_REPACK_OUTPUT_BYTES" in generate_wasm_hotpath
            and "MAX_BIT_STORAGE_VALUES" in generate_wasm_hotpath,
        ),
        (
            "Dist contains loadable Gaius Wasm hot-path module",
            HOTPATH_WASM.exists()
            and HOTPATH_WASM.stat().st_size > 1000
            and HOTPATH_WASM.read_bytes()[:4] == b"\x00asm",
        ),
        (
            "Dist assets can be precompressed for faster browser loading",
            "gzip -kf -9" in compress_dist
            and "brotli -f -q 11" in compress_dist
            and "*.js" in compress_dist
            and "*.html" in compress_dist,
        ),
        (
            "Local dist server serves precompressed classes.js when available",
            "Content-Encoding" in serve_dist
            and "Accept-Encoding" in serve_dist
            and '("br", ".br")' in serve_dist
            and '("gzip", ".gz")' in serve_dist
            and "Cross-Origin-Embedder-Policy" in serve_dist
            and "max-age=31536000, immutable" in serve_dist
            and "parse_qs" in serve_dist,
        ),
        (
            "Browser boot keeps classes.js cache stable unless fresh=1 is requested",
            'const fallbackBuildToken = "' in index_html
            and 'urlParams.get("fresh") === "1"' in index_html
            and 'urlParams.get("cache") === "0"' in index_html
            and 'buildToken += "-fresh-" + Date.now()' in index_html
            and "__gaiusBootTimings" in index_html
            and "bootTimings.classesLoaded" in index_html
            and "function waitForPaint()" in index_html
            and "bootTimings.beforeClassesPaint" in index_html
            and 'requestedBuildToken + "-fresh-" + Date.now()' not in index_html,
        ),
        (
            "Browser boot UI has progress and does not disable chat/commands",
            "boot-progress-bar" in index_html
            and "__gaiusSetBootProgress" in index_html
            and "function hideBootOverlay()" in index_html
            and 'setBootProgress(100, "Minecraft 界面已加载：" + screen)' in index_html
            and "Math.max(bootProgressValue, 92)" not in index_html
            and "__gaiusFps" in index_html
            and "perf-hud" in index_html
            and "targetFps" in index_html
            and "Display FPS" in index_html
            and "Game loop" in index_html
            and "__gaiusMaxDpr" in index_html
            and "__gaiusMinDpr" in index_html
            and "__gaiusMenuMinDpr" in index_html
            and "__gaiusWorldMinDpr" in index_html
            and "rawDevicePixelRatio" in index_html
            and "Math.min(1.5, rawDevicePixelRatio)" in index_html
            and 'Number(urlParams.get("menuMinDpr"))' in index_html
            and 'Number(urlParams.get("worldMinDpr"))' in index_html
            and 'Number(urlParams.get("maxDpr"))' in index_html
            and "Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps" in index_html
            and "LevelLoadingScreen" in index_html
            and "ProgressScreen" in index_html
            and "fps.worldEnteredAt" in index_html
            and "60000" in index_html
            and "fps.highSamples" in index_html
            and "fps.lastDprChangeAt" in index_html
            and "fps.recoveredCount" in index_html
            and "targetFps * 0.55" in index_html
            and "targetFps - 50" in index_html
            and "lowTarget + 30" in index_html
            and "fps.lowSamples < 12" in index_html
            and "window.__gaiusDefaultMaxDpr" in index_html
            and "? (Number(window.__gaiusWorldMinDpr) || 1.0)" in index_html
            and ": (Number(window.__gaiusMenuMinDpr) || 1.0)" in index_html
            and "window.__gaiusMaxDpr > 1.0 ? 1.0 : 0.9" not in index_html
            and "window.__gaiusMaxDpr = Math.max(\n        minDpr,\n        1.0\n      )" in index_html
            and "if (!inWorld && minecraftState && minecraftState.screen) return;" not in index_html
            and "singleShadowMB" in index_html
            and "totalShadowMB" in index_html
            and 'Math.min(256, Number(urlParams.get("singleShadowMB")) || 256)' in index_html
            and 'Math.min(1024, Number(urlParams.get("totalShadowMB")) || 1024)' in index_html
            and "maybeDegradeResolutionForFps" in index_html
            and "__gaiusWasmHotpath" in index_html
            and "gaius-hotpath.wasm" in index_html
            and "WebAssembly.instantiate" in index_html
            and "shiftIndices" in index_html
            and "repackInterleaved" in index_html
            and "unpackBitStorage" in index_html
            and "bitStorageWasmUnpack" in index_html
            and "if (!inWorld) return" not in index_html
            and "storage-options-preflight" in index_html
            and "__gaiusFsDelete" in index_html
            and "removed persisted browser options" in index_html
            and "超过 30 秒没有进度变化" in index_html
            and '"--disableChat"' not in index_html,
        ),
    ]
    for name, ok in checks:
        print_check(name, ok)


def check_overlay_bytecode() -> None:
    section("Overlay bytecode checks")
    client_cp = OVERLAYS / "client-named-1.21.11-gaius.jar"
    netty_common_cp = OVERLAYS / "library-patches" / "netty-common"
    netty_cp = OVERLAYS / "library-patches" / "netty-transport"
    classlib_cp = OVERLAYS / "classlib-patches"
    classlib_classes_cp = OVERLAYS / "classlib-classes"
    lwjgl_cp = OVERLAYS / "library-classes" / "lwjgl"
    lwjgl_opengl_cp = OVERLAYS / "library-classes" / "lwjgl-opengl"
    lwjgl_opengl_patch_cp = OVERLAYS / "library-patches" / "lwjgl-opengl"
    lwjgl_openal_cp = OVERLAYS / "libraries" / "org" / "lwjgl" / "lwjgl-openal" / "3.3.3" / "lwjgl-openal-3.3.3.jar"
    lwjgl_openal_classes_cp = OVERLAYS / "library-classes" / "lwjgl-openal"
    lwjgl_glfw_cp = OVERLAYS / "libraries" / "org" / "lwjgl" / "lwjgl-glfw" / "3.3.3" / "lwjgl-glfw-3.3.3.jar"
    browser_opengl_class = lwjgl_opengl_cp / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.class"
    browser_openal_class = lwjgl_openal_classes_cp / "org" / "lwjgl" / "openal" / "BrowserOpenAL.class"

    packet_encoder = run_javap(client_cp, "net.minecraft.network.PacketEncoder")
    packet_bundle_unpacker = run_javap(client_cp, "net.minecraft.network.PacketBundleUnpacker")
    varint_prepender = run_javap(client_cp, "net.minecraft.network.Varint21LengthFieldPrepender")
    cipher_encoder = run_javap(client_cp, "net.minecraft.network.CipherEncoder")
    compression_encoder = run_javap(client_cp, "net.minecraft.network.CompressionEncoder")
    class_tree_id_registry = run_javap(client_cp, "net.minecraft.util.ClassTreeIdRegistry")
    synched_entity_data = run_javap(client_cp, "net.minecraft.network.syncher.SynchedEntityData")
    entity = run_javap(client_cp, "net.minecraft.world.entity.Entity")
    integrated_server = run_javap(client_cp, "net.minecraft.client.server.IntegratedServer")
    screen = run_javap(client_cp, "net.minecraft.client.gui.screens.Screen")
    title_screen = run_javap(client_cp, "net.minecraft.client.gui.screens.TitleScreen")
    abstract_button = run_javap(client_cp, "net.minecraft.client.gui.components.AbstractButton")
    gui_graphics = run_javap(client_cp, "net.minecraft.client.gui.GuiGraphics")
    gui_render_state = run_javap(client_cp, "net.minecraft.client.gui.render.state.GuiRenderState")
    gui_renderer = run_javap(client_cp, "net.minecraft.client.gui.render.GuiRenderer")
    browser_gui_item_cache = run_javap(client_cp, "dev.gaius.browser.BrowserGuiItemCache")
    browser_tracking_item_stack_render_state = run_javap(
        client_cp,
        "dev.gaius.browser.BrowserGuiItemCache$BrowserTrackingItemStackRenderState",
    )
    client_level = run_javap(client_cp, "net.minecraft.client.multiplayer.ClientLevel")
    game_renderer = run_javap(client_cp, "net.minecraft.client.renderer.GameRenderer")
    level_renderer = run_javap(client_cp, "net.minecraft.client.renderer.LevelRenderer")
    section_render_dispatcher = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
    )
    minecraft_server = run_javap(client_cp, "net.minecraft.server.MinecraftServer")
    persistent_entity_manager = run_javap(
        client_cp,
        "net.minecraft.world.level.entity.PersistentEntitySectionManager",
    )
    gl_device = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlDevice")
    audio_library = run_javap(client_cp, "com.mojang.blaze3d.audio.Library")
    sound_engine = run_javap(client_cp, "net.minecraft.client.sounds.SoundEngine")
    vertex_array_cache_emulated = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$Emulated")
    vertex_array_cache_separate = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$Separate")
    vertex_array_cache = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache")
    vertex_array_cache_key = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$VertexArrayKey")
    vanilla_pack_builder = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResourcesBuilder")
    indexed_asset_source = run_javap(client_cp, "net.minecraft.client.resources.IndexedAssetSource")
    vanilla_pack_resources = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResources")
    region_file_version = run_javap(client_cp, "net.minecraft.world.level.chunk.storage.RegionFileVersion")
    local_time = run_javap(client_cp, "net.minecraft.client.renderer.item.properties.select.LocalTime")
    minecraft = run_javap(client_cp, "net.minecraft.client.Minecraft")
    create_world_screen = run_javap(client_cp, "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen")
    dynamic_uniforms = run_javap(client_cp, "net.minecraft.client.renderer.DynamicUniforms")
    level_load_tracker = run_javap(client_cp, "net.minecraft.client.multiplayer.LevelLoadTracker")
    waiting_for_server = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForServer",
    )
    waiting_for_player_chunk = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk",
    )
    client_packet_listener = run_javap(client_cp, "net.minecraft.client.multiplayer.ClientPacketListener")
    minecraft_run_tick = method_section(minecraft, "private void runTick(boolean);")
    framerate_tracker = run_javap(client_cp, "com.mojang.blaze3d.platform.FramerateLimitTracker")
    player_list = run_javap(client_cp, "net.minecraft.server.players.PlayerList")
    simple_bit_storage = run_javap(client_cp, "net.minecraft.util.SimpleBitStorage")
    buffer_builder = run_javap(client_cp, "com.mojang.blaze3d.vertex.BufferBuilder")
    integrated_tick = method_section(integrated_server, "public void tickServer(java.util.function.BooleanSupplier);")
    gui_render_item = method_section(
        gui_graphics,
        "private void renderItem(net.minecraft.world.entity.LivingEntity, net.minecraft.world.level.Level, net.minecraft.world.item.ItemStack, int, int, int);",
    )
    gui_render_state_reset = method_section(
        gui_render_state,
        "public void reset();",
    )
    gui_renderer_item_atlas_lambda = method_section(
        gui_renderer,
        "private void lambda$prepareItemElements$3(org.apache.commons.lang3.mutable.MutableBoolean, int, int, org.apache.commons.lang3.mutable.MutableBoolean, com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.gui.render.state.GuiItemRenderState);",
    )
    gui_renderer_invalidate_item_atlas = method_section(
        gui_renderer,
        "private void invalidateItemAtlas();",
    )
    dynamic_uniforms_constructor = method_section(
        dynamic_uniforms,
        "public net.minecraft.client.renderer.DynamicUniforms();",
    )
    screen_render_panorama = method_section(
        screen,
        "protected void renderPanorama(net.minecraft.client.gui.GuiGraphics, float);",
    )
    screen_render_menu_background = method_section(
        screen,
        "protected void renderMenuBackground(net.minecraft.client.gui.GuiGraphics, int, int, int, int);",
    )
    title_realms_enabled = method_section(title_screen, "private boolean realmsNotificationsEnabled();")
    abstract_button_sprite = method_section(
        abstract_button,
        "protected final void renderDefaultSprite(net.minecraft.client.gui.GuiGraphics);",
    )
    game_render_level = method_section(game_renderer, "public void renderLevel(net.minecraft.client.DeltaTracker);")
    game_render_level_head = game_render_level[:1000]
    client_level_animate_tick = method_section(
        client_level,
        "public void animateTick(int, int, int);",
    )
    level_compile_sections = method_section(
        level_renderer,
        "private void compileSections(net.minecraft.client.Camera);",
    )
    section_uploads = method_section(
        section_render_dispatcher,
        "public void uploadAllPendingUploads();",
    )
    entity_constructor = method_section(
        entity,
        "public net.minecraft.world.entity.Entity(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);",
    )
    minecraft_run_server = method_section(minecraft_server, "protected void runServer();")
    minecraft_initial_spawn = method_section(
        minecraft_server,
        "private static void setInitialSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.world.level.storage.ServerLevelData, boolean, boolean, net.minecraft.server.level.progress.LevelLoadListener);",
    )
    entity_uuid_add = method_section(persistent_entity_manager, "private boolean addEntityUuid(T);")
    gl_device_max_texture = method_section(gl_device, "private static int getMaxSupportedTextureSize();")
    gl_device_static = method_section(gl_device, "static {};")
    audio_library_init = method_section(audio_library, "public void init(java.lang.String, boolean);")
    sound_engine_load_library = method_section(sound_engine, "private synchronized void loadLibrary();")
    overload_at = minecraft_run_server.find("Field OVERLOADED_WARNING_INTERVAL_NANOS:J")
    overload_window = (
        minecraft_run_server[max(0, overload_at - 800):overload_at + 1400]
        if overload_at >= 0
        else ""
    )
    player_view_distance = method_section(player_list, "public int getViewDistance();")
    player_sim_distance = method_section(player_list, "public int getSimulationDistance();")
    region_file_get_selected = method_section(
        region_file_version,
        "public static net.minecraft.world.level.chunk.storage.RegionFileVersion getSelected();",
    )
    simple_bit_storage_unpack = method_section(simple_bit_storage, "public void unpack(int[]);")
    buffer_builder_add_vertex = method_section(
        buffer_builder,
        "public void addVertex(float, float, float, int, float, float, int, int, float, float, float);",
    )
    buffer_builder_add_vertex_float = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float, float, float);",
    )
    buffer_builder_add_vertex_matrix = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer addVertex(org.joml.Matrix4fc, float, float, float);",
    )
    buffer_builder_set_color = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer setColor(int);",
    )
    buffer_builder_set_uv = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer setUv(float, float);",
    )
    buffer_builder_set_light = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer setLight(int);",
    )
    buffer_builder_set_normal = method_section(
        buffer_builder,
        "public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float, float, float);",
    )
    vanilla_static_root = method_section(
        vanilla_pack_builder,
        "private static com.google.common.collect.ImmutableMap lambda$static$1();",
    )
    local_time_create = method_section(
        local_time,
        "private static com.mojang.serialization.DataResult<net.minecraft.client.renderer.item.properties.select.LocalTime> create(net.minecraft.client.renderer.item.properties.select.LocalTime$Data);",
    )
    local_time_update = method_section(local_time, "private java.lang.String update();")
    create_world_fresh = method_section(
        create_world_screen,
        "private static net.minecraft.world.level.levelgen.WorldGenSettings lambda$openFresh$4(net.minecraft.server.WorldLoader$DataLoadContext);",
    )
    create_world_constructor = method_section(
        create_world_screen,
        "private net.minecraft.client.gui.screens.worldselection.CreateWorldScreen(net.minecraft.client.Minecraft, java.lang.Runnable, net.minecraft.client.gui.screens.worldselection.WorldCreationContext, java.util.Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset>>, java.util.OptionalLong, net.minecraft.client.gui.screens.worldselection.CreateWorldCallback);",
    )
    create_world_settings = method_section(
        create_world_screen,
        "private net.minecraft.world.level.LevelSettings createLevelSettings(boolean);",
    )
    level_load_tracker_clinit = method_section(level_load_tracker, "static {};")
    level_load_tracker_is_ready = method_section(
        level_load_tracker,
        "public boolean isLevelReady();",
    )
    waiting_for_server_tick = method_section(
        waiting_for_server,
        "public net.minecraft.client.multiplayer.LevelLoadTracker$ClientState tick();",
    )
    client_packet_tick = method_section(client_packet_listener, "public void tick();")
    framerate_reason = method_section(
        framerate_tracker,
        "public com.mojang.blaze3d.platform.FramerateLimitTracker$FramerateThrottleReason getThrottleReason();",
    )
    indexed_create_fs = method_section(
        indexed_asset_source,
        "public static java.nio.file.Path createIndexFs(java.nio.file.Path, java.lang.String);",
    )
    gl_const = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlConst")
    texture_format = run_javap(client_cp, "com.mojang.blaze3d.textures.TextureFormat")
    mac_address = run_javap(netty_common_cp, "io.netty.util.internal.MacAddressUtil")
    recycler = run_javap(netty_common_cp, "io.netty.util.Recycler")
    default_channel_id = run_javap(netty_cp, "io.netty.channel.DefaultChannelId")
    channel_handler_mask = run_javap(netty_cp, "io.netty.channel.ChannelHandlerMask")
    throwable = run_javap(classlib_cp, "org.teavm.classlib.java.lang.TThrowable")
    browser_memory = run_javap(lwjgl_cp, "org.lwjgl.system.BrowserMemory")
    browser_memory_copy = method_section(browser_memory, "public static void copy(long, long, long);")
    browser_memory_copy_overlapping = method_section(browser_memory, "private static void copyOverlapping(java.nio.ByteBuffer, int, int, int);")
    browser_memory_set = method_section(browser_memory, "public static void set(long, int, long);")
    browser_memory_fast_vertex = method_section(browser_memory, "public static void putFastVertex(")
    browser_memory_decode_utf8 = method_section(browser_memory, "public static java.lang.String decodeUtf8(long, int);")
    browser_memory_temporary_bytes = method_section(browser_memory, "private static byte[] temporaryBytes(int);")
    browser_opengl = run_javap(lwjgl_opengl_cp, "org.lwjgl.opengl.BrowserOpenGL")
    browser_openal = run_javap(lwjgl_openal_classes_cp, "org.lwjgl.openal.BrowserOpenAL")
    openal_al10 = run_javap(lwjgl_openal_cp, "org.lwjgl.openal.AL10")
    browser_file_persistence_class = run_javap(classlib_classes_cp, "dev.gaius.browser.BrowserFilePersistence")
    browser_bit_storage_class = run_javap(classlib_classes_cp, "dev.gaius.browser.BrowserBitStorage")
    browser_opengl_constants = (
        browser_opengl_class.read_bytes().decode("latin1", errors="ignore")
        if browser_opengl_class.exists()
        else ""
    )
    browser_openal_constants = (
        browser_openal_class.read_bytes().decode("latin1", errors="ignore")
        if browser_openal_class.exists()
        else ""
    )
    browser_file_persistence_constants = (
        (classlib_classes_cp / "dev" / "gaius" / "browser" / "BrowserFilePersistence.class").read_bytes().decode("latin1", errors="ignore")
        if (classlib_classes_cp / "dev" / "gaius" / "browser" / "BrowserFilePersistence.class").exists()
        else ""
    )
    browser_glfw_constants = read_zip_entry_latin1(lwjgl_glfw_cp, "org/lwjgl/glfw/BrowserGlfw.class")
    arb_vertex_attrib = run_javap(lwjgl_opengl_patch_cp, "org.lwjgl.opengl.ARBVertexAttribBinding")
    browser_glfw = run_javap(lwjgl_glfw_cp, "org.lwjgl.glfw.BrowserGlfw")
    glfw = run_javap(lwjgl_glfw_cp, "org.lwjgl.glfw.GLFW")
    face_bakery = run_javap(client_cp, "net.minecraft.client.renderer.block.model.FaceBakery")

    checks = [
        (
            "DefaultChannelId.defaultProcessId -> 1",
            "static int defaultProcessId();" in default_channel_id
            and "iconst_1" in default_channel_id
            and "ireturn" in default_channel_id,
        ),
        (
            "DefaultChannelId.newInstance has fixed GAIUS byte array",
            "public static io.netty.channel.DefaultChannelId newInstance();" in default_channel_id
            and "bipush        71" in default_channel_id
            and "bipush        65" in default_channel_id
            and "bipush        73" in default_channel_id
            and "bipush        85" in default_channel_id
            and "bipush        83" in default_channel_id,
        ),
        (
            "ChannelHandlerMask.isSkippable -> false",
            "private static boolean isSkippable" in channel_handler_mask
            and "iconst_0" in channel_handler_mask
            and "ireturn" in channel_handler_mask,
        ),
        (
            "MacAddressUtil.defaultMachineId has fixed GAIUS byte array",
            "static byte[] defaultMachineId();" in mac_address
            and "bipush        71" in mac_address
            and "bipush        65" in mac_address
            and "bipush        73" in mac_address
            and "bipush        85" in mac_address
            and "bipush        83" in mac_address,
        ),
        (
            "Recycler.get uses NOOP_HANDLE",
            "public final T get();" in recycler
            and "getstatic" in recycler
            and "NOOP_HANDLE" in recycler
            and "newObject" in recycler,
        ),
        (
            "PacketEncoder.acceptOutboundMessage -> Packet",
            "public boolean acceptOutboundMessage(java.lang.Object);" in packet_encoder
            and "instanceof" in packet_encoder
            and "net/minecraft/network/protocol/Packet" in packet_encoder.replace(".", "/"),
        ),
        (
            "PacketBundleUnpacker.acceptOutboundMessage -> Packet",
            "public boolean acceptOutboundMessage(java.lang.Object);" in packet_bundle_unpacker
            and "instanceof" in packet_bundle_unpacker
            and "net/minecraft/network/protocol/Packet" in packet_bundle_unpacker.replace(".", "/"),
        ),
        (
            "Varint21LengthFieldPrepender.acceptOutboundMessage -> ByteBuf",
            "public boolean acceptOutboundMessage(java.lang.Object);" in varint_prepender
            and "instanceof" in varint_prepender
            and "io/netty/buffer/ByteBuf" in varint_prepender.replace(".", "/"),
        ),
        (
            "CipherEncoder.acceptOutboundMessage -> ByteBuf",
            "public boolean acceptOutboundMessage(java.lang.Object);" in cipher_encoder
            and "instanceof" in cipher_encoder
            and "io/netty/buffer/ByteBuf" in cipher_encoder.replace(".", "/"),
        ),
        (
            "CompressionEncoder.acceptOutboundMessage -> ByteBuf",
            "public boolean acceptOutboundMessage(java.lang.Object);" in compression_encoder
            and "instanceof" in compression_encoder
            and "io/netty/buffer/ByteBuf" in compression_encoder.replace(".", "/"),
        ),
        (
            "ClassTreeIdRegistry.getLastIdFor scans cached classes by name/assignability",
            "public int getLastIdFor(java.lang.Class<?>);" in class_tree_id_registry
            and "getSuperclass" in class_tree_id_registry
            and "getName" in class_tree_id_registry
            and "gaius$getCachedIdByName" in class_tree_id_registry
            and "object2IntEntrySet" in class_tree_id_registry
            and "isAssignableFrom" in class_tree_id_registry,
        ),
        (
            "SynchedEntityData.defineId initializes superclass chain",
            "gaius$initializeSynchedDataSuperclasses" in synched_entity_data
            and "java/lang/Class.initialize" in synched_entity_data,
        ),
        (
            "Entity constructor uses global browser UUID random source",
            "public net.minecraft.world.entity.Entity(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);" in entity_constructor
            and "net/minecraft/util/Mth.createInsecureUUID:()Ljava/util/UUID;" in entity_constructor
            and "net/minecraft/util/Mth.createInsecureUUID:(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;" not in entity_constructor,
        ),
        (
            "SimpleBitStorage.unpack calls browser bit-storage hot path before vanilla loop",
            "dev/gaius/browser/BrowserBitStorage.unpack" in simple_bit_storage_unpack
            and "([J[IIIJI)Z" in simple_bit_storage_unpack
            and "return" in simple_bit_storage_unpack
            and "public static native boolean unpack" in browser_bit_storage_class,
        ),
        (
            "BufferBuilder.addVertex fast path uses BrowserMemory single-pass vertex writer",
            "Field fastFormat:Z" in buffer_builder_add_vertex
            and "Method beginVertex:()J" in buffer_builder_add_vertex
            and "org/lwjgl/system/BrowserMemory.putFastVertex" in buffer_builder_add_vertex
            and "(JFFFIFFIIFFFZ)V" in buffer_builder_add_vertex
            and "InterfaceMethod com/mojang/blaze3d/vertex/VertexConsumer.addVertex:(FFFIFFIIFFF)V" in buffer_builder_add_vertex
            and "org/lwjgl/system/MemoryUtil.memPutFloat" not in buffer_builder_add_vertex
            and "org/lwjgl/system/MemoryUtil.memPutByte" not in buffer_builder_add_vertex,
        ),
        (
            "BufferBuilder GUI/text writers use BrowserMemory fast pointer writes",
            "org/lwjgl/system/BrowserMemory.putPosition" in buffer_builder_add_vertex_float
            and "org/lwjgl/system/BrowserMemory.putTransformedPosition" in buffer_builder_add_vertex_matrix
            and "org/lwjgl/system/BrowserMemory.putRgba" in buffer_builder_set_color
            and "org/lwjgl/system/BrowserMemory.putFloatPair" in buffer_builder_set_uv
            and "org/lwjgl/system/BrowserMemory.putPackedUv" in buffer_builder_set_light
            and "org/lwjgl/system/BrowserMemory.putNormal" in buffer_builder_set_normal
            and "org/lwjgl/system/MemoryUtil.memPutFloat" not in buffer_builder_add_vertex_float
            and "org/lwjgl/system/MemoryUtil.memPutFloat" not in buffer_builder_add_vertex_matrix
            and "org/lwjgl/system/MemoryUtil.memPutFloat" not in buffer_builder_set_uv
            and "org/lwjgl/system/MemoryUtil.memPutByte" not in buffer_builder_set_normal,
        ),
        (
            "BrowserMemory compiled overlay has single-pass fast vertex writer",
            "public static void putFastVertex(long, float, float, float, int, float, float, int, int, float, float, float, boolean);" in browser_memory
            and "Method region:(J)Lorg/lwjgl/system/BrowserMemory$Region;" in browser_memory_fast_vertex
            and "Method offset:(J)I" in browser_memory_fast_vertex
            and "java/nio/ByteBuffer.putFloat" in browser_memory_fast_vertex
            and "Method putRgba:(Ljava/nio/ByteBuffer;II)V" in browser_memory_fast_vertex
            and "Method putPackedUv:(Ljava/nio/ByteBuffer;II)V" in browser_memory_fast_vertex
            and "java/nio/ByteBuffer.put:(IB)Ljava/nio/ByteBuffer;" in browser_memory_fast_vertex,
        ),
        (
            "BrowserMemory compiled overlay has fast GUI/text writer helpers",
            "public static void putPosition(long, float, float, float);" in browser_memory
            and "public static void putTransformedPosition(long, org.joml.Matrix4fc, float, float, float);" in browser_memory
            and "public static void putRgba(long, int);" in browser_memory
            and "public static void putFloatPair(long, float, float);" in browser_memory
            and "public static void putPackedUv(long, int);" in browser_memory
            and "public static void putNormal(long, float, float, float);" in browser_memory
            and "org/joml/Matrix4fc.m00:()F" in browser_memory
            and "Method putPosition:(JFFF)V" in browser_memory,
        ),
        (
            "GlConst RED8I internal format is WebGL-safe R8",
            "public static int toGlInternalId" in gl_const
            and "int 33321" in gl_const
            and "int 33329" not in gl_const,
        ),
        (
            "GlDevice skips desktop proxy texture size probing",
            "private static int getMaxSupportedTextureSize();" in gl_device_max_texture
            and "GlStateManager._getInteger" in gl_device_max_texture
            and "java/lang/Math.max:(II)I" in gl_device_max_texture
            and "GlStateManager._texImage2D" not in gl_device_max_texture
            and "Failed to determine maximum texture size" not in gl_device_max_texture,
        ),
        (
            "GlDevice keeps browser on WebGL VAO emulation path",
            last_putstatic_bool(gl_device_static, "USE_GL_ARB_vertex_attrib_binding:Z") is False,
        ),
        (
            "VertexArrayCache caches browser VAOs by format and buffer handle",
            "MAX_CACHED_WEBGL_VAOS" in vertex_array_cache_emulated
            and "java/util/LinkedHashMap" in vertex_array_cache_emulated
            and "overflowCache" in vertex_array_cache_emulated
            and "bindOverflowVertexArray" in vertex_array_cache_emulated
            and "VertexArrayKey" in vertex_array_cache_key
            and "bufferHandle" in vertex_array_cache_key,
        ),
        (
            "VertexArrayCache compiled overlay normalizes COLOR attributes",
            "private static boolean shouldNormalize" in vertex_array_cache
            and "VertexFormatElement$Usage.COLOR" in vertex_array_cache
            and "VertexFormatElement$Usage.UV" in vertex_array_cache
            and "VertexFormatElement$Usage.GENERIC" in vertex_array_cache
            and "shouldNormalize" in vertex_array_cache_emulated
            and "shouldNormalize" in vertex_array_cache_separate,
        ),
        (
            "TextureFormat.hasColorAspect treats all non-depth formats as color",
            "public boolean hasColorAspect();" in texture_format
            and "DEPTH32" in texture_format
            and "if_acmpeq" in texture_format,
        ),
        (
            "TThrowable.getSuppressed0 null-safe",
            "getSuppressed0" in throwable
            and "ifnonnull" in throwable
            and "anewarray" in throwable
            and "areturn" in throwable,
        ),
        (
            "TThrowable.addSuppressed initializes missing array",
            "addSuppressed" in throwable
            and "putfield" in throwable
            and "aastore" in throwable,
        ),
        (
            "BrowserOpenGL compiled overlay tracks texture UNPACK skip state",
            "unpackRowLength" in browser_opengl
            and "unpackSkipRows" in browser_opengl
            and "unpackSkipPixels" in browser_opengl
            and "webGlUnpackAlignment" in browser_opengl
            and "bytesPerPixel" in browser_opengl,
        ),
        (
            "BrowserOpenGL compiled overlay caches shifted indices for baseVertex fallback",
            "drawElementsWithBaseVertex" in browser_opengl_constants
            and "cacheShiftedIndexBuffer" in browser_opengl_constants
            and "shiftedIndexCache" in browser_opengl_constants
            and "baseVertexIndexDraws" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay repairs shader attribute type mismatches",
            "programAttribs" in browser_opengl_constants
            and "refreshProgramAttribs" in browser_opengl_constants
            and "ensureProgramAttribTypes" in browser_opengl_constants
            and "attribTypeRepairs" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay skips redundant native vertexAttribPointer calls",
            "sameAttribPointer" in browser_opengl_constants
            and "attribPointerFastSkips" in browser_opengl_constants
            and "vertexBufferFastSkips" in browser_opengl_constants
            and "attribBindingFastSkips" in browser_opengl_constants
            and "attribFormatFastSkips" in browser_opengl_constants
            and "validationChanged" in browser_opengl_constants
            and "samePointer" in browser_opengl_constants
            and "previousMisaligned===misaligned" in browser_opengl_constants
            and "previousPresence===present" in browser_opengl_constants
            and "state.attribPointerFastSkips" in browser_opengl_constants
            and "previousPresence && samePointer" in browser_opengl_constants
            and "previous.buffer|0" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay limits attrib repack to active shader inputs",
            "activeAttribLocations" in browser_opengl_constants
            and "restoreDirectAttribPointers" in browser_opengl_constants
            and "if (!vao.misalignedAttribs || !vao.misalignedAttribs.size)" in browser_opengl_constants
            and "this.prepareDrawAttribs(vao);" in browser_opengl_constants
            and browser_opengl_constants.find("this.prepareDrawAttribs(vao);")
                < browser_opengl_constants.find("if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size)")
            and "if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size)" in browser_opengl_constants
            and "programAttribCache:new Map()" in browser_opengl_constants
            and "vao.programAttribCache.set(program|0" in browser_opengl_constants
            and "if (repaired && vao.programAttribCache.size) vao.programAttribCache.clear();" in browser_opengl_constants
            and "activeAttribLazyRefresh" in browser_opengl_constants
            and "programAttribLazyRefresh" in browser_opengl_constants
            and "directAttribRestores" in browser_opengl_constants
            and "if (!vao.directAttribDirty) {" in browser_opengl_constants
            and "return 0;" in browser_opengl_constants
            and "directAttribDirty" in browser_opengl_constants
            and "drawAttribPreparedVersion" in browser_opengl_constants
            and "prepareDrawAttribs" in browser_opengl_constants
            and "drawAttribPrepareFastSkips" in browser_opengl_constants
            and "vao.missingEnabledAttribs.add(pointers[msi].index|0)" in browser_opengl_constants
            and "vao.missingEnabledAttribs.add(pointers[nvi].index|0)" in browser_opengl_constants
            and "vao.missingEnabledAttribs.delete(bindLayout.index|0)" in browser_opengl_constants
            and "baseVertexDirectRestores" in browser_opengl_constants
            and "alignedAttribProgram" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay avoids unconditional buffer shadow copies",
            "shadowRequiredBuffers" in browser_opengl_constants
            and "shouldShadowBufferTarget" in browser_opengl_constants
            and "shadowBufferDataForTarget" in browser_opengl_constants
            and "shadowBufferSubDataForTarget" in browser_opengl_constants
            and "bufferShadowSkippedUnneeded" in browser_opengl_constants
            and "bufferShadowSkippedUnneededCount" in browser_opengl_constants
            and "bufferShadowRequiredMarkCount" in browser_opengl_constants
            and "this.shadowRequiredBuffers.has(id)" in browser_opengl_constants
            and "window.__gaiusMaxSingleBufferShadowBytes" in browser_opengl_constants
            and "window.__gaiusMaxTotalBufferShadowBytes" in browser_opengl_constants
            and "bufferShadowPolicyVersion" in browser_opengl_constants
            and "bufferShadowDecisionCache" in browser_opengl_constants
            and "bumpBufferShadowPolicyVersion" in browser_opengl_constants
            and "256 * 1024 * 1024" in browser_opengl_constants
            and "1024 * 1024 * 1024" in browser_opengl_constants
            and "268435456" in browser_opengl_constants
            and "misalignedBufferRefs" in browser_opengl_constants
            and "this.misalignedBufferRefs.get(id)" in browser_opengl_constants
            and "releaseVaoMisalignedBuffers" in browser_opengl_constants
            and "this.vaoEmu.forEach(function(v)" in browser_opengl_constants
            and "markBufferShadowRequired" in browser_opengl_constants
            and "misaligned-attrib" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes draw-call throughput telemetry",
            "recordDrawCall" in browser_opengl_constants
            and "drawCallsPerSecond" in browser_opengl_constants
            and "__gaiusReadWebGLErrors" in browser_opengl_constants
            and "glErrors" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes texture upload diagnostics",
            "recordTextureUpload" in browser_opengl_constants
            and "recordTextureError" in browser_opengl_constants
            and "textureUploadRecent" in browser_opengl_constants
            and "textureUploadErrors" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes screen widget telemetry",
            "describeScreenWidgets" in browser_opengl
            and "screenWidgetsJson" in browser_opengl_constants
            and "screenWidgets" in browser_opengl_constants
            and "screenTitle" in browser_opengl_constants
            and "screenSize" in browser_opengl_constants
            and "getMessage" in browser_opengl,
        ),
        (
            "BrowserOpenGL compiled overlay falls back to connection level for state telemetry",
            "fallbackClientLevel" in browser_opengl
            and "Minecraft.getConnection" in browser_opengl
            and "ClientPacketListener.getLevel" in browser_opengl,
        ),
        (
            "BrowserOpenGL compiled overlay reports Minecraft state with safe class names",
            "net.minecraft.client.multiplayer.ClientLevel" in browser_opengl_constants
            and "net.minecraft.client.player.LocalPlayer" in browser_opengl_constants
            and "<class-name-unavailable>" in browser_opengl_constants
            and "java/lang/Object.getClass" in browser_opengl,
        ),
        (
            "BrowserOpenGL compiled overlay throttles inventory-screen world background rendering",
            "shouldSkipWorldRenderForScreen" in browser_opengl
            and "inventoryWorldRenderFrame" in browser_opengl
            and "inventoryWorldRenderScreen" in browser_opengl
            and "java/lang/String.startsWith" in browser_opengl
            and "net.minecraft.client.gui.screens.inventory." in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes GUI item atlas telemetry",
            "reportGuiItemAtlasHit" in browser_opengl
            and "reportGuiItemAtlasRender" in browser_opengl
            and "reportGuiItemAtlasOversized" in browser_opengl
            and "reportGuiItemAtlasInvalidated" in browser_opengl
            and "guiItemAtlasCurrent" in browser_opengl_constants
            and "guiItemAtlasLast" in browser_opengl_constants
            and "guiItemAtlasTelemetryEnabled" in browser_opengl_constants
            and "atlasDiag" in browser_opengl_constants
            and "renderAnimatedRefresh" in browser_opengl_constants
            and "renderMiss" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay disables cull face once per GUI draw batch",
            "guiDrawsRemaining" in browser_opengl_constants
            and "guiCullFaceBatchActive" in browser_opengl_constants
            and "guiCullFaceBatchDisables" in browser_opengl_constants
            and "guiCullFaceBatchRestores" in browser_opengl_constants
            and "guiCullFaceBatchForcedRestores" in browser_opengl_constants
            and "gl.disable(gl.CULL_FACE)" in browser_opengl_constants
            and "gl.enable(gl.CULL_FACE)" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay can sample GUI vertex/index state behind diag=gui",
            "sampleGuiDraw" in browser_opengl_constants
            and "guiVertexSampleRecent" in browser_opengl_constants
            and "indexSample" in browser_opengl_constants
            and "sampleVertexAttrib" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay repairs GUI item offscreen scissor for native item rendering",
            "isGuiItemOffscreen512Target" in browser_opengl_constants
            and "withGuiItemOffscreenScissorRepair" in browser_opengl_constants
            and "offscreen512ScissorRepairs" in browser_opengl_constants
            and "findFramebufferColorTextureId" in browser_opengl_constants
            and "framebufferColorTextures" in browser_opengl_constants
            and "framebufferColorTextureMisses" in browser_opengl_constants
            and "framebufferColorTextureFallbacks" in browser_opengl_constants
            and "this.framebufferColorTextures.has(id)" in browser_opengl_constants
            and "restoreGuiItemOffscreenScissor" in browser_opengl_constants
            and "guiItemOffscreenScissorDisabled" in browser_opengl_constants
            and "offscreen512ScissorBatchDisables" in browser_opengl_constants
            and "offscreen512ScissorBatchRestores" in browser_opengl_constants
            and "state.restoreGuiItemOffscreenScissor('bindFramebuffer')" in browser_opengl_constants
            and "state.restoreGuiItemOffscreenScissor('gui-draw-plan')" in browser_opengl_constants
            and "gl.disable(gl.SCISSOR_TEST)" in browser_opengl_constants
            and "gl.enable(gl.SCISSOR_TEST)" in browser_opengl_constants
            and "window.__gaiusGL.withGuiItemOffscreenScissorRepair(function()" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay maps WebGL buffers into registered MemoryUtil memory",
            "org/lwjgl/system/MemoryUtil.memAlloc" in browser_opengl
            and "org/lwjgl/system/MemoryUtil.memFree" in browser_opengl,
        ),
        (
            "BrowserOpenAL compiled overlay exposes Web Audio backend",
            "bufferDataJs" in browser_openal
            and "sourcePlayJs" in browser_openal
            and "AudioContext" in browser_openal_constants
            and "window.__gaiusAudioStats" in browser_openal_constants
            and "createBufferSource" in browser_openal_constants,
        ),
        (
            "OpenAL AL10 overlay delegates source and buffer calls to BrowserOpenAL",
            "BrowserOpenAL.genSource" in openal_al10
            and "BrowserOpenAL.genBuffer" in openal_al10
            and "BrowserOpenAL.bufferData" in openal_al10
            and "BrowserOpenAL.sourcePlay" in openal_al10,
        ),
        (
            "Minecraft audio library creates browser channels and keeps preload path",
            "BrowserOpenAL.init" in audio_library_init
            and "Library$CountingChannelPool" in audio_library_init
            and "bipush        30" in audio_library_init
            and "bipush        8" in audio_library_init
            and "SoundBufferLibrary.preload" in sound_engine_load_library
            and "browser.sound.silent" not in sound_engine,
        ),
        (
            "BrowserMemory compiled overlay preserves mapped ByteBuffer addresses through memSlice",
            "public static long register(java.nio.ByteBuffer);" in browser_memory
            and "private static void registerDerived(java.nio.Buffer, java.nio.Buffer, int);" in browser_memory,
        ),
        (
            "BrowserMemory compiled overlay frees mapped buffers without scanning the whole address table",
            "REGION_BUFFERS" in browser_memory
            and "remember" in browser_memory,
        ),
        (
            "BrowserMemory compiled overlay avoids registering transient memCopy/memSet views",
            "private static java.nio.ByteBuffer transientView(long, int);" in browser_memory
            and "private static java.nio.ByteBuffer transientView(org.lwjgl.system.BrowserMemory$Region, int, int);" in browser_memory
            and "private static java.nio.ByteBuffer transientView(java.nio.ByteBuffer, int, int);" in browser_memory
            and "transientView:(JI)Ljava/nio/ByteBuffer;" in browser_memory_set
            and "java/nio/ByteBuffer.putLong" in browser_memory_set
            and "transientView:(Lorg/lwjgl/system/BrowserMemory$Region;II)Ljava/nio/ByteBuffer;" in browser_memory_copy
            and "java/nio/ByteBuffer.put:(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;" in browser_memory_copy
            and "byteBuffer:(JI)Ljava/nio/ByteBuffer;" not in browser_memory_copy
            and "byteBuffer:(JI)Ljava/nio/ByteBuffer;" not in browser_memory_set,
        ),
        (
            "BrowserMemory compiled overlay reuses hot temporary arrays",
            "private static final java.lang.ThreadLocal<byte[]> BYTE_ARRAYS;" in browser_memory
            and "TEMP_BYTES_SIZE" in browser_memory
            and "BYTE_ARRAYS" in browser_memory_copy_overlapping
            and "BYTE_ARRAYS" in browser_memory_temporary_bytes
            and "temporaryBytes" in browser_memory_decode_utf8
            and "byteBuffer:(JI)Ljava/nio/ByteBuffer;" not in browser_memory_decode_utf8,
        ),
        (
            "ARBVertexAttribBinding overlay delegates GUI vertex layout calls to BrowserOpenGL",
            "public static void glBindVertexBuffer(int, int, long, int);" in arb_vertex_attrib
            and "BrowserOpenGL.bindVertexBuffer" in arb_vertex_attrib
            and "BrowserOpenGL.vertexAttribFormat" in arb_vertex_attrib
            and "BrowserOpenGL.vertexAttribIFormat" in arb_vertex_attrib
            and "BrowserOpenGL.vertexAttribBinding" in arb_vertex_attrib,
        ),
        (
            "BrowserGlfw compiled overlay implements printable key names",
            "public static java.lang.String getKeyName(int, int);" in browser_glfw
            and "bipush        65" in browser_glfw
            and "bipush        90" in browser_glfw
            and "java/lang/Character.toString:(C)Ljava/lang/String;" in browser_glfw
            and "334:" in browser_glfw
            and "aconst_null" in browser_glfw,
        ),
        (
            "BrowserGlfw compiled overlay clamps browser DPR and gates preserveDrawingBuffer",
            "__gaiusResolvePixelRatio" in browser_glfw_constants
            and "__gaiusApplyCanvasResolution" in browser_glfw_constants
            and "__gaiusMaxDpr" in browser_glfw_constants
            and "preserveDrawingBuffer" in browser_glfw_constants,
        ),
        (
            "BrowserGlfw compiled overlay records game FPS from swapBuffers",
            "swapBuffers(long);" in browser_glfw
            and "gameFps" in browser_glfw_constants
            and "gameFrames" in browser_glfw_constants
            and "gameLastSampleAt" in browser_glfw_constants,
        ),
        (
            "BrowserGlfw compiled overlay yields during waitEventsTimeout",
            "public static void waitEventsTimeout(double);" in browser_glfw
            and "sleepForBrowserMillis" in browser_glfw
            and "java/lang/Thread.sleep:(J)V" in browser_glfw
            and "java/lang/Thread.yield:()V" in browser_glfw,
        ),
        (
            "BrowserGlfw compiled overlay primes cursor callbacks",
            "setCursorPosCallback" in browser_glfw
            and "GLFWCursorPosCallbackI.invoke:(JDD)V" in browser_glfw,
        ),
        (
            "BrowserGlfw compiled overlay warms up first GUI input",
            "maybeQueueInputWarmup" in browser_glfw
            and "__gaiusInputWarmupDone" in browser_glfw_constants,
        ),
        (
            "GLFW compiled overlay delegates glfwGetKeyName/getKeyScancode to BrowserGlfw",
            "public static java.lang.String glfwGetKeyName(int, int);" in glfw
            and "BrowserGlfw.getKeyName" in glfw
            and "public static int glfwGetKeyScancode(int);" in glfw
            and "BrowserGlfw.getKeyScancode" in glfw,
        ),
        (
            "FaceBakery.findVertex uses browser float tolerance",
            "private static int findVertex(org.joml.Vector3fc[], int, float, float, float);" in face_bakery
            and "java/lang/Math.abs:(F)F" in face_bakery
            and "float 1.0E-4f" in face_bakery,
        ),
        (
            "GuiGraphics.renderItem uses browser GUI item state safety hook",
            "dev/gaius/browser/BrowserGuiItemCache.guiState" in gui_render_item
            and "ItemModelResolver.updateForTopItem" not in gui_render_item
            and "net/minecraft/client/renderer/item/TrackingItemStackRenderState" in gui_render_item
            and "ItemModelResolver.updateForTopItem" in browser_gui_item_cache
            and "STATE_POOL_SIZE" in browser_gui_item_cache
            and "BrowserTrackingItemStackRenderState" in browser_gui_item_cache
            and "MODEL_IDENTITY_CACHE_SIZE" in browser_gui_item_cache
            and "singleModelIdentity" in browser_gui_item_cache
            and "multiModelIdentity" in browser_gui_item_cache
            and "java/util/Collections.singletonList" in browser_gui_item_cache
            and "java/util/Collections.unmodifiableList" in browser_gui_item_cache
            and "dev/gaius/browser/BrowserGuiItemCache$ModelIdentityEntry.matches" in browser_gui_item_cache
            and "monitorenter" not in browser_gui_item_cache
            and "stableModelIdentity" in browser_tracking_item_stack_render_state
            and "BrowserGuiItemCache.stableModelIdentity" in browser_tracking_item_stack_render_state
            and "TrackingItemStackRenderState.getModelIdentity" in browser_tracking_item_stack_render_state
            and "java/util/List.clear" in browser_tracking_item_stack_render_state
            and "resetForReuse" in browser_tracking_item_stack_render_state
            and "dev/gaius/browser/BrowserGuiItemCache.resetPool" in gui_render_state_reset
            and "ItemStack.hashItemAndComponents" not in browser_gui_item_cache,
        ),
        (
            "GuiRenderer freezes cached animated GUI item atlas entries without telemetry calls",
            "pop" in gui_renderer_item_atlas_lambda
            and "iconst_0" in gui_renderer_item_atlas_lambda
            and "TrackingItemStackRenderState.isAnimated" in gui_renderer_item_atlas_lambda
            and "BrowserOpenGL.reportGuiItemAtlas" not in gui_renderer_item_atlas_lambda
            and "BrowserOpenGL.reportGuiItemAtlas" not in gui_renderer_invalidate_item_atlas,
        ),
        (
            "GuiGraphics.renderItem uses constant browser item debug names",
            "browser:item" in gui_render_item
            and "net/minecraft/network/chat/Component.toString" not in gui_render_item
            and "net/minecraft/world/item/Item.getName" not in gui_render_item,
        ),
        (
            "DynamicUniforms constructor uses browser initial UBO capacities",
            "Dynamic Transforms UBO" in dynamic_uniforms_constructor
            and dynamic_uniforms_constructor.count("sipush        128") >= 2
            and "Chunk Sections UBO" in dynamic_uniforms_constructor
            and "iconst_2" not in dynamic_uniforms_constructor,
        ),
        (
            "Minecraft compiled overlay processes queued packets during forced ticks",
            "PacketProcessor.processQueuedPackets" in minecraft_run_tick
            and "client.processQueuedPacketsForcedTick" in minecraft_run_tick
            and minecraft_run_tick.find("ifne")
                < minecraft_run_tick.find("PacketProcessor.processQueuedPackets"),
        ),
        (
            "Screen browser menus use static fill instead of dynamic panorama textures",
            "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V" in screen_render_panorama
            and "PanoramaRenderer.render" not in screen_render_panorama
            and "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V" in screen_render_menu_background
            and "renderMenuBackgroundTexture" not in screen_render_menu_background
            and "iconst_0" in title_realms_enabled
            and "ireturn" in title_realms_enabled,
        ),
        (
            "AbstractButton browser background uses fill instead of GUI sprite blits",
            "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V" in abstract_button_sprite
            and "blitSprite" not in abstract_button_sprite
            and "WidgetSprites.get" not in abstract_button_sprite,
        ),
        (
            "GameRenderer throttles inventory-screen world background before renderLevel",
            "BrowserOpenGL.shouldSkipWorldRenderForScreen" in game_renderer
            and "InterfaceMethod net/minecraft/util/profiling/ProfilerFiller.pop:()V" in game_renderer
            and "Method renderLevel:(Lnet/minecraft/client/DeltaTracker;)V" in game_renderer
            and "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;" in game_renderer,
        ),
        (
            "GameRenderer closes stale loading screen before active world render",
            "client.levelReady.closeLoadingScreenFromWorldRender" in game_renderer
            and "LevelLoadingScreen" in game_renderer
            and "Field net/minecraft/client/Minecraft.level:Lnet/minecraft/client/multiplayer/ClientLevel;" in game_renderer
            and "Field net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;" in game_renderer
            and "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;" in game_renderer,
        ),
        (
            "ClientLevel compiled overlay limits animateTick browser budget",
            "public void animateTick(int, int, int);" in client_level_animate_tick
            and "bipush        64" in client_level_animate_tick
            and "sipush        667" not in client_level_animate_tick,
        ),
        (
            "LevelRenderer compiled overlay throttles section scheduling and guards sync rebuild off",
            "private void compileSections(net.minecraft.client.Camera);" in level_compile_sections
            and "List.size" in level_compile_sections
            and "if_icmplt" in level_compile_sections
            and "List.add" in level_compile_sections
            and "rebuildSectionAsync" in level_compile_sections
            and "compileSectionSynchronously" in level_compile_sections
            and "rebuildSectionSync" in level_compile_sections
            and "iconst_0" in level_compile_sections
            and level_compile_sections.find("iconst_0") < level_compile_sections.find("compileSectionSynchronously"),
        ),
        (
            "SectionRenderDispatcher compiled overlay limits per-frame uploads",
            "public void uploadAllPendingUploads();" in section_uploads
            and section_uploads.count("Queue.poll") >= 2
            and "Runnable.run" in section_uploads
            and "SectionMesh.close" in section_uploads
            and "if_icmpge" in section_uploads
            and "goto" in section_uploads,
        ),
        (
            "IntegratedServer forces browser distances to 2/2",
            "public void tickServer(java.util.function.BooleanSupplier);" in integrated_tick
            and "iconst_2" in integrated_tick
            and "pop" in integrated_tick,
        ),
        (
            "PlayerList distance getters force browser distances to 2/2",
            "public int getViewDistance();" in player_view_distance
            and "public int getSimulationDistance();" in player_sim_distance
            and "iconst_2" in player_view_distance
            and "pop" in player_view_distance
            and "iconst_2" in player_sim_distance
            and "pop" in player_sim_distance,
        ),
        (
            "RegionFileVersion writes browser chunks without deflate compression",
            "public static net.minecraft.world.level.chunk.storage.RegionFileVersion getSelected();" in region_file_get_selected
            and "Field VERSION_NONE:Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;" in region_file_get_selected
            and "areturn" in region_file_get_selected
            and "Field selected:Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;" not in region_file_get_selected,
        ),
        (
            "PersistentEntitySectionManager recovers duplicate browser entity UUIDs",
            "private boolean addEntityUuid(T);" in entity_uuid_add
            and "java/util/Set.add" in entity_uuid_add
            and "net/minecraft/world/entity/Entity" in entity_uuid_add
            and "net/minecraft/util/Mth.createInsecureUUID" in entity_uuid_add
            and "net/minecraft/world/entity/Entity.setUUID" in entity_uuid_add
            and "server.entityUuidRecovered" in entity_uuid_add
            and "bipush        8" in entity_uuid_add
            and "UUID of added entity already exists: {}" in entity_uuid_add,
        ),
        (
            "MinecraftServer resets browser tick catchup before overload warning",
            "Field OVERLOADED_WARNING_INTERVAL_NANOS:J" in overload_window
            and "net/minecraft/util/Util.getNanos:()J" in overload_window
            and "Field nextTickTimeNanos:J" in overload_window
            and "Field lastOverloadWarningNanos:J" in overload_window
            and "goto" in overload_window
            and "Can't keep up! Is the server overloaded?" not in minecraft_run_server,
        ),
        (
            "MinecraftServer uses browser fast initial spawn path",
            "server.browserFastInitialSpawn" in minecraft_initial_spawn
            and "PlayerSpawnFinder.getSpawnPosInChunk" in minecraft_initial_spawn
            and "Climate$Sampler.findSpawnPosition" in minecraft_initial_spawn
            and "Heightmap$Types.WORLD_SURFACE" in minecraft_initial_spawn
            and "bipush        11" not in minecraft_initial_spawn
            and "BlockPos.ZERO" not in minecraft_initial_spawn
            and "sipush        128" not in minecraft_initial_spawn,
        ),
        (
            "VanillaPackResourcesBuilder skips desktop classpath root probing",
            "private static com.google.common.collect.ImmutableMap lambda$static$1();" in vanilla_static_root
            and "com/google/common/collect/ImmutableMap.of:()Lcom/google/common/collect/ImmutableMap;" in vanilla_static_root
            and "File {} does not exist in classpath" not in vanilla_static_root,
        ),
        (
            "IndexedAssetSource returns empty browser asset index filesystem",
            "public static java.nio.file.Path createIndexFs(java.nio.file.Path, java.lang.String);" in indexed_create_fs
            and "browser-assets" in indexed_create_fs
            and "net/minecraft/server/packs/linkfs/LinkFileSystem.builder" in indexed_create_fs
            and "java/nio/file/Files.newBufferedReader" not in indexed_create_fs,
        ),
        (
            "VanillaPackResources compiled overlay wraps resources as byte arrays",
            "private static java.io.InputStream lambda$openClasspathResource" in vanilla_pack_resources
            and "java/io/ByteArrayInputStream" in vanilla_pack_resources
            and "java/io/InputStream.readAllBytes:()[B" in vanilla_pack_resources,
        ),
        (
            "VanillaPackResources compiled overlay falls back for browser pack icon",
            "private net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> rootSupplierIfPresent" in vanilla_pack_resources
            and "pack.png" in vanilla_pack_resources
            and "assets/minecraft/textures/misc/unknown_pack.png" in vanilla_pack_resources
            and "supplierIfPresent" in vanilla_pack_resources,
        ),
        (
            "VanillaPackResources compiled overlay caches browser resource listings",
            "listedResourceCache" in vanilla_pack_resources
            and "ListedResource" in vanilla_pack_resources
            and "java/util/HashMap" in vanilla_pack_resources
            and "listedResources" in vanilla_pack_resources,
        ),
        (
            "BrowserFilePersistence compiled overlay enforces browser performance options",
            "seedDefaultOptions" in browser_file_persistence_class
            and "enforcePerformanceOptions" in browser_file_persistence_class
            and "storage-default-options" in browser_file_persistence_constants
            and "renderDistance:2" in browser_file_persistence_constants
            and "simulationDistance:5" in browser_file_persistence_constants
            and "entityDistanceScaling:0.5" in browser_file_persistence_constants
            and "maxFps:120" in browser_file_persistence_constants
            and 'graphicsPreset:"fast"' in browser_file_persistence_constants
            and 'renderClouds:"false"' in browser_file_persistence_constants
            and "menuBackgroundBlurriness:0" in browser_file_persistence_constants
            and "panoramaSpeed:0.0" in browser_file_persistence_constants
            and "screenEffectScale:0.0" in browser_file_persistence_constants
            and "maxAnisotropyBit:1" in browser_file_persistence_constants
            and "textureFiltering:0" in browser_file_persistence_constants
            and "browser low settings after migration failure" in browser_file_persistence_constants,
        ),
        (
            "LocalTime item model property avoids ICU formatter path in browser",
            "com/mojang/serialization/DataResult.success:(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;" in local_time_create
            and "com/ibm/icu/text/SimpleDateFormat" not in local_time_create
            and "java/util/Date.getMonth:()I" in local_time_update
            and "java/util/Date.getDate:()I" in local_time_update
            and "java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;" in local_time_update,
        ),
        (
            "CreateWorldScreen compiled overlay defaults to normal dimensions and commands enabled",
            "WorldOptions.defaultWithRandomSeed" in create_world_fresh
            and "WorldPresets.createNormalWorldDimensions" in create_world_fresh
            and "WorldOptions.testWorldWithRandomSeed" not in create_world_fresh
            and "WorldPresets.createFlatWorldDimensions" not in create_world_fresh
            and "WorldCreationUiState.setAllowCommands:(Z)V" in create_world_constructor
            and "iconst_1" in create_world_constructor
            and "WorldCreationUiState.isAllowCommands" not in create_world_settings
            and "iconst_1" in create_world_settings,
        ),
        (
            "LevelLoadTracker compiled overlay times out missing loading packets quickly",
            "ldc2_w        #156                // long 5l" in level_load_tracker_clinit
            and "Timed out while waiting for initial level loading packets in the browser" in waiting_for_server_tick
            and "loadingPacketsReceived" in waiting_for_server_tick,
        ),
        (
            "LevelLoadTracker compiled overlay lets loading screen close on browser timeout",
            "LevelLoadTracker$WaitingForServer" in level_load_tracker_is_ready
            and "LevelLoadTracker$WaitingForPlayerChunk" in level_load_tracker_is_ready
            and "timeoutAfter" in level_load_tracker_is_ready
            and "client.levelReady.timeoutFallback" in level_load_tracker_is_ready,
        ),
        (
            "ClientPacketListener compiled overlay exits loading when level exists",
            "client.levelReady.playerPresentFallback" in client_packet_tick
            and "client.levelReady.closeLoadingScreen" in client_packet_tick
            and "Minecraft.screen" in client_packet_tick
            and "LevelLoadingScreen" in client_packet_tick
            and "notifyPlayerLoaded" in client_packet_tick,
        ),
        (
            "LevelLoadTracker compiled overlay does not wait for hidden-screen section compilation",
            "private boolean isReady();" in waiting_for_player_chunk
            and "iconst_1" in method_section(waiting_for_player_chunk, "private boolean isReady();")
            and "ireturn" in method_section(waiting_for_player_chunk, "private boolean isReady();")
            and "isSectionCompiledAndVisible" not in method_section(
                waiting_for_player_chunk,
                "private boolean isReady();",
            ),
        ),
        (
            "FramerateLimitTracker compiled overlay returns NONE throttle reason in browser",
            "FramerateLimitTracker$FramerateThrottleReason.NONE" in framerate_reason
            and "areturn" in framerate_reason
            and "isIconified" not in framerate_reason
            and "InactivityFpsLimit" not in framerate_reason,
        ),
    ]
    for name, ok in checks:
        print_check(name, ok)


def main() -> int:
    os.chdir(ROOT)
    print(f"root: {ROOT}")
    check_gap()
    check_build_timeline()
    check_latest_states()
    check_source_patches()
    check_overlay_bytecode()
    if FAILURES:
        print(f"\n{len(FAILURES)} quick-check failure(s):")
        for name in FAILURES:
            print(f" - {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
