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
GLFW_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-glfw" / "src" / "main" / "java" / "org" / "lwjgl" / "glfw" / "BrowserGlfw.java"
GLFW_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglGlfwBrowserPatcher.java"
CLIENT_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "MinecraftClientPatcher.java"
VANILLA_PACK_RESOURCES = PORT / "overrides" / "client" / "src" / "main" / "java" / "net" / "minecraft" / "server" / "packs" / "VanillaPackResources.java"
BROWSER_FILE_PERSISTENCE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserFilePersistence.java"
BROWSER_GUI_ITEM_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserGuiItemCache.java"
WASM_HOTPATH_C = PORT / "wasm" / "hotpath" / "gaius_hotpath.c"
BUILD_WASM_HOTPATH = PORT / "scripts" / "build-wasm-hotpath.sh"
GENERATE_POM = PORT / "scripts" / "generate-pom.sh"
BUILD_RELEASE = PORT / "scripts" / "build-teavm-release.sh"
COMPRESS_DIST = PORT / "scripts" / "compress-dist.sh"
SERVE_DIST = PORT / "scripts" / "serve-dist.py"
INDEX_HTML = PORT / "web" / "dist" / "index.html"


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
        fatal = wc.get("fatalMessages") or []
        print(
            f"{rel(path)} | {fmt_time(path)} | verdict={wc.get('verdict')} "
            f"screen={wc.get('screen')} level={wc.get('level')} "
            f"serverStarted={wc.get('serverStarted')}"
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
    glfw_text = GLFW_BRIDGE.read_text(errors="replace") if GLFW_BRIDGE.exists() else ""
    glfw_patcher = GLFW_PATCHER.read_text(errors="replace") if GLFW_PATCHER.exists() else ""
    client_patcher = CLIENT_PATCHER.read_text(errors="replace") if CLIENT_PATCHER.exists() else ""
    vanilla_pack_resources = VANILLA_PACK_RESOURCES.read_text(errors="replace") if VANILLA_PACK_RESOURCES.exists() else ""
    browser_file_persistence = BROWSER_FILE_PERSISTENCE.read_text(errors="replace") if BROWSER_FILE_PERSISTENCE.exists() else ""
    browser_gui_item_cache = BROWSER_GUI_ITEM_CACHE.read_text(errors="replace") if BROWSER_GUI_ITEM_CACHE.exists() else ""
    wasm_hotpath_c = WASM_HOTPATH_C.read_text(errors="replace") if WASM_HOTPATH_C.exists() else ""
    build_wasm_hotpath = BUILD_WASM_HOTPATH.read_text(errors="replace") if BUILD_WASM_HOTPATH.exists() else ""
    generate_pom = GENERATE_POM.read_text(errors="replace") if GENERATE_POM.exists() else ""
    build_release = BUILD_RELEASE.read_text(errors="replace") if BUILD_RELEASE.exists() else ""
    compress_dist = COMPRESS_DIST.read_text(errors="replace") if COMPRESS_DIST.exists() else ""
    serve_dist = SERVE_DIST.read_text(errors="replace") if SERVE_DIST.exists() else ""
    index_html = INDEX_HTML.read_text(errors="replace") if INDEX_HTML.exists() else ""
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
            "Wasm hot-path module exports batch index shifting helpers",
            "gaius_shift_indices" in wasm_hotpath_c
            and "gaius_repack_interleaved" in wasm_hotpath_c
            and "gaius_shift_indices_input_ptr" in wasm_hotpath_c
            and "gaius_shift_indices_output_ptr" in wasm_hotpath_c
            and "gaius_repack_source_ptr" in wasm_hotpath_c
            and "gaius_repack_layouts_ptr" in wasm_hotpath_c
            and "MAX_INDICES" in wasm_hotpath_c
            and "MAX_REPACK_LAYOUTS" in wasm_hotpath_c
            and "GL_UNSIGNED_SHORT" in wasm_hotpath_c
            and "GL_UNSIGNED_INT" in wasm_hotpath_c,
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
            "BrowserOpenGL exposes draw-call throughput telemetry",
            "recordDrawCall" in text
            and "drawCallsPerSecond" in text
            and "drawWindowCalls" in text,
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
            "BrowserGlfw provides GLFW key names for printable keys",
            "public static String getKeyName(int key, int scancode)" in glfw_text
            and "GLFW.GLFW_KEY_A && value <= GLFW.GLFW_KEY_Z" in glfw_text
            and "GLFW.GLFW_KEY_KP_ADD" in glfw_text
            and "default -> null" in glfw_text,
        ),
        (
            "BrowserGlfw defaults to performance-safe DPR and disables slow preserveDrawingBuffer",
            "__gaiusResolvePixelRatio" in glfw_text
            and "__gaiusApplyCanvasResolution" in glfw_text
            and "__gaiusMaxDpr" in glfw_text
            and "Math.min(devicePixelRatio||1,1)" in glfw_text
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
            "Minecraft patcher caches non-animated GUI item render states in browser",
            "patchGuiGraphicsBrowserItemCache" in client_patcher
            and "BrowserGuiItemCache" in client_patcher
            and "guiState" in client_patcher
            and "TrackingItemStackRenderState" in client_patcher
            and "ItemModelResolver" in client_patcher
            and "updateForTopItem" in client_patcher
            and "MAX_ENTRIES" in browser_gui_item_cache
            and "hashItemAndComponents" in browser_gui_item_cache
            and "isAnimated" in browser_gui_item_cache,
        ),
        (
            "Minecraft patcher skips expensive level rendering while browser screens are open",
            "patchGameRendererBrowserAutoScreenshot" in client_patcher
            and "renderLevel" in client_patcher
            and "net/minecraft/client/gui/screens/Screen" in client_patcher
            and "Opcodes.IFNULL" in client_patcher
            and "skipLevelWhenScreenOpen" in client_patcher,
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
            "Minecraft patcher forces browser singleplayer distances to 1",
            "patchIntegratedServerBrowserDistances" in distance_section
            and "private static InsnList distanceClamp()" in distance_section
            and "IntegratedServer distance override patch points" in distance_section
            and "Opcodes.POP" in distance_section
            and "Opcodes.ICONST_1" in distance_section,
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
            "Browser storage seeds/enforces browser performance client options",
            "DEFAULT_BROWSER_OPTIONS" in browser_file_persistence
            and "BROWSER_PERFORMANCE_OPTIONS" in browser_file_persistence
            and "seedDefaultOptions" in browser_file_persistence
            and "enforcePerformanceOptions" in browser_file_persistence
            and "renderDistance:1" in browser_file_persistence
            and "simulationDistance:1" in browser_file_persistence
            and "maxFps:260" in browser_file_persistence
            and 'graphicsPreset:\\"fast\\"' in browser_file_persistence
            and 'renderClouds:\\"false\\"' in browser_file_persistence
            and "menuBackgroundBlurriness:0" in browser_file_persistence
            and "panoramaSpeed:0.0" in browser_file_persistence
            and "screenEffectScale:0.0" in browser_file_persistence
            and "maxAnisotropyBit:0" in browser_file_persistence
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
            "Wasm hot-path build emits dist wasm without requiring TeaVM",
            "--target=wasm32" in build_wasm_hotpath
            and "-Wl,--no-entry" in build_wasm_hotpath
            and "-Wl,--export-memory" in build_wasm_hotpath
            and "gaius-hotpath.wasm" in build_wasm_hotpath
            and "gaius_shift_indices" in build_wasm_hotpath
            and "gaius_repack_interleaved" in build_wasm_hotpath,
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
            and "Cross-Origin-Embedder-Policy" in serve_dist,
        ),
        (
            "Browser boot UI has progress and does not disable chat/commands",
            "boot-progress-bar" in index_html
            and "__gaiusSetBootProgress" in index_html
            and "__gaiusFps" in index_html
            and "perf-hud" in index_html
            and "targetFps" in index_html
            and "Game FPS" in index_html
            and "RAF FPS" in index_html
            and "__gaiusMaxDpr" in index_html
            and '|| 0.35' in index_html
            and "maybeDegradeResolutionForFps" in index_html
            and "__gaiusWasmHotpath" in index_html
            and "gaius-hotpath.wasm" in index_html
            and "WebAssembly.instantiate" in index_html
            and "shiftIndices" in index_html
            and "repackInterleaved" in index_html
            and "if (!inWorld) return" not in index_html
            and "storage-options-preflight" in index_html
            and "__gaiusFsDelete" in index_html
            and "removed persisted browser options" in index_html
            and "超过 30 秒没有进度变化" in index_html
            and '"--disableChat"' not in index_html,
        ),
    ]
    for name, ok in checks:
        print(f"{'OK' if ok else 'FAIL'} {name}")


def check_overlay_bytecode() -> None:
    section("Overlay bytecode checks")
    client_cp = OVERLAYS / "client-named-1.21.11-gaius.jar"
    netty_common_cp = OVERLAYS / "library-patches" / "netty-common"
    netty_cp = OVERLAYS / "library-patches" / "netty-transport"
    classlib_cp = OVERLAYS / "classlib-patches"
    classlib_classes_cp = OVERLAYS / "classlib-classes"
    lwjgl_opengl_cp = OVERLAYS / "library-classes" / "lwjgl-opengl"
    lwjgl_opengl_patch_cp = OVERLAYS / "library-patches" / "lwjgl-opengl"
    lwjgl_glfw_cp = OVERLAYS / "libraries" / "org" / "lwjgl" / "lwjgl-glfw" / "3.3.3" / "lwjgl-glfw-3.3.3.jar"
    browser_opengl_class = lwjgl_opengl_cp / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.class"

    packet_encoder = run_javap(client_cp, "net.minecraft.network.PacketEncoder")
    packet_bundle_unpacker = run_javap(client_cp, "net.minecraft.network.PacketBundleUnpacker")
    varint_prepender = run_javap(client_cp, "net.minecraft.network.Varint21LengthFieldPrepender")
    cipher_encoder = run_javap(client_cp, "net.minecraft.network.CipherEncoder")
    compression_encoder = run_javap(client_cp, "net.minecraft.network.CompressionEncoder")
    class_tree_id_registry = run_javap(client_cp, "net.minecraft.util.ClassTreeIdRegistry")
    synched_entity_data = run_javap(client_cp, "net.minecraft.network.syncher.SynchedEntityData")
    integrated_server = run_javap(client_cp, "net.minecraft.client.server.IntegratedServer")
    screen = run_javap(client_cp, "net.minecraft.client.gui.screens.Screen")
    title_screen = run_javap(client_cp, "net.minecraft.client.gui.screens.TitleScreen")
    abstract_button = run_javap(client_cp, "net.minecraft.client.gui.components.AbstractButton")
    gui_graphics = run_javap(client_cp, "net.minecraft.client.gui.GuiGraphics")
    browser_gui_item_cache = run_javap(client_cp, "dev.gaius.browser.BrowserGuiItemCache")
    game_renderer = run_javap(client_cp, "net.minecraft.client.renderer.GameRenderer")
    minecraft_server = run_javap(client_cp, "net.minecraft.server.MinecraftServer")
    gl_device = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlDevice")
    vanilla_pack_builder = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResourcesBuilder")
    indexed_asset_source = run_javap(client_cp, "net.minecraft.client.resources.IndexedAssetSource")
    vanilla_pack_resources = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResources")
    local_time = run_javap(client_cp, "net.minecraft.client.renderer.item.properties.select.LocalTime")
    create_world_screen = run_javap(client_cp, "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen")
    level_load_tracker = run_javap(client_cp, "net.minecraft.client.multiplayer.LevelLoadTracker")
    waiting_for_server = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForServer",
    )
    framerate_tracker = run_javap(client_cp, "com.mojang.blaze3d.platform.FramerateLimitTracker")
    player_list = run_javap(client_cp, "net.minecraft.server.players.PlayerList")
    integrated_tick = method_section(integrated_server, "public void tickServer(java.util.function.BooleanSupplier);")
    gui_render_item = method_section(
        gui_graphics,
        "private void renderItem(net.minecraft.world.entity.LivingEntity, net.minecraft.world.level.Level, net.minecraft.world.item.ItemStack, int, int, int);",
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
    minecraft_run_server = method_section(minecraft_server, "protected void runServer();")
    minecraft_initial_spawn = method_section(
        minecraft_server,
        "private static void setInitialSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.world.level.storage.ServerLevelData, boolean, boolean, net.minecraft.server.level.progress.LevelLoadListener);",
    )
    gl_device_max_texture = method_section(gl_device, "private static int getMaxSupportedTextureSize();")
    overload_at = minecraft_run_server.find("Field OVERLOADED_WARNING_INTERVAL_NANOS:J")
    overload_window = (
        minecraft_run_server[max(0, overload_at - 800):overload_at + 1400]
        if overload_at >= 0
        else ""
    )
    player_view_distance = method_section(player_list, "public int getViewDistance();")
    player_sim_distance = method_section(player_list, "public int getSimulationDistance();")
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
    waiting_for_server_tick = method_section(
        waiting_for_server,
        "public net.minecraft.client.multiplayer.LevelLoadTracker$ClientState tick();",
    )
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
    browser_opengl = run_javap(lwjgl_opengl_cp, "org.lwjgl.opengl.BrowserOpenGL")
    browser_file_persistence_class = run_javap(classlib_classes_cp, "dev.gaius.browser.BrowserFilePersistence")
    browser_opengl_constants = (
        browser_opengl_class.read_bytes().decode("latin1", errors="ignore")
        if browser_opengl_class.exists()
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
            "BrowserOpenGL compiled overlay exposes draw-call throughput telemetry",
            "recordDrawCall" in browser_opengl_constants
            and "drawCallsPerSecond" in browser_opengl_constants,
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
            "GuiGraphics.renderItem uses browser GUI item state cache",
            "dev/gaius/browser/BrowserGuiItemCache.guiState" in gui_render_item
            and "ItemModelResolver.updateForTopItem" not in gui_render_item
            and "net/minecraft/client/renderer/item/TrackingItemStackRenderState" in gui_render_item
            and "hashItemAndComponents" in browser_gui_item_cache
            and "ItemStackRenderState.isAnimated:()Z" in browser_gui_item_cache,
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
            "GameRenderer.renderLevel skips world render behind browser screens",
            "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;" in game_render_level_head
            and "ifnull" in game_render_level_head
            and "return" in game_render_level_head
            and game_render_level_head.find("Field net/minecraft/client/Minecraft.screen") < game_render_level_head.find("InterfaceMethod net/minecraft/client/DeltaTracker.getGameTimeDeltaPartialTick"),
        ),
        (
            "IntegratedServer forces browser distances to 1",
            "public void tickServer(java.util.function.BooleanSupplier);" in integrated_tick
            and "iconst_4" not in integrated_tick
            and integrated_tick.count("iconst_1") >= 2
            and "pop" in integrated_tick,
        ),
        (
            "PlayerList distance getters force browser distances to 1",
            "public int getViewDistance();" in player_view_distance
            and "public int getSimulationDistance();" in player_sim_distance
            and "iconst_4" not in player_view_distance
            and "iconst_4" not in player_sim_distance
            and "iconst_1" in player_view_distance
            and "pop" in player_view_distance
            and "iconst_1" in player_sim_distance
            and "pop" in player_sim_distance,
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
            "BrowserFilePersistence compiled overlay enforces browser performance options",
            "seedDefaultOptions" in browser_file_persistence_class
            and "enforcePerformanceOptions" in browser_file_persistence_class
            and "storage-default-options" in browser_file_persistence_constants
            and "renderDistance:1" in browser_file_persistence_constants
            and "simulationDistance:1" in browser_file_persistence_constants
            and "maxFps:260" in browser_file_persistence_constants
            and 'graphicsPreset:"fast"' in browser_file_persistence_constants
            and 'renderClouds:"false"' in browser_file_persistence_constants
            and "menuBackgroundBlurriness:0" in browser_file_persistence_constants
            and "panoramaSpeed:0.0" in browser_file_persistence_constants
            and "screenEffectScale:0.0" in browser_file_persistence_constants
            and "maxAnisotropyBit:0" in browser_file_persistence_constants
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
            "FramerateLimitTracker compiled overlay returns NONE throttle reason in browser",
            "FramerateLimitTracker$FramerateThrottleReason.NONE" in framerate_reason
            and "areturn" in framerate_reason
            and "isIconified" not in framerate_reason
            and "InactivityFpsLimit" not in framerate_reason,
        ),
    ]
    for name, ok in checks:
        print(f"{'OK' if ok else 'FAIL'} {name}")


def main() -> int:
    os.chdir(ROOT)
    print(f"root: {ROOT}")
    check_gap()
    check_build_timeline()
    check_latest_states()
    check_source_patches()
    check_overlay_bytecode()
    return 0


if __name__ == "__main__":
    sys.exit(main())
