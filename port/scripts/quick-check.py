#!/usr/bin/env python3
"""Fast non-compiling diagnostics for the browser Minecraft port.

This script intentionally does not run Maven, TeaVM, overlay generation, Chrome,
or screenshots. It only reads existing logs, generated files, probe JSON, and
overlay classes.
"""

from __future__ import annotations

import base64
import glob
import gzip
import json
import mmap
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
NETTY_BROWSER_CHANNEL = PORT / "overrides" / "libraries" / "netty-transport" / "src" / "main" / "java" / "io" / "netty" / "channel" / "browser" / "BrowserWebSocketChannel.java"
NETTY_BROWSER_EVENT_LOOP = PORT / "overrides" / "libraries" / "netty-transport" / "src" / "main" / "java" / "io" / "netty" / "channel" / "browser" / "BrowserInlineEventLoop.java"
NETTY_BROWSER_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "NettyBrowserPatcher.java"
BRIDGE_CONFIG = ROOT / "apps" / "bridge" / "dist" / "config.js"
BRIDGE_MAIN = ROOT / "apps" / "bridge" / "dist" / "main.js"
BRIDGE_POLICY = ROOT / "apps" / "bridge" / "dist" / "policy.js"
BRIDGE_SMOKE = ROOT / "apps" / "bridge" / "multiplayer-smoke.mjs"
ONLINE_MODE_SERVER_SMOKE = ROOT / "apps" / "bridge" / "online-mode-server-smoke.mjs"
STB_IMAGE = PORT / "overrides" / "libraries" / "lwjgl-stb" / "src" / "main" / "java" / "org" / "lwjgl" / "stb" / "STBImage.java"
LWJGL_BROWSER_MEMORY = PORT / "overrides" / "libraries" / "lwjgl" / "src" / "main" / "java" / "org" / "lwjgl" / "system" / "BrowserMemory.java"
GLFW_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-glfw" / "src" / "main" / "java" / "org" / "lwjgl" / "glfw" / "BrowserGlfw.java"
GLFW_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglGlfwBrowserPatcher.java"
CLIENT_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "MinecraftClientPatcher.java"
CLASSLIB_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "TeaVMClasslibPatcher.java"
JOML_MATH_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "JomlMathPatcher.java"
VANILLA_PACK_RESOURCES = PORT / "overrides" / "client" / "src" / "main" / "java" / "net" / "minecraft" / "server" / "packs" / "VanillaPackResources.java"
BROWSER_FILE_PERSISTENCE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserFilePersistence.java"
MODERN_RUNTIME_SUPPORT = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "lang" / "TModernRuntimeSupport.java"
FILE_OUTPUT_STREAM = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "io" / "TFileOutputStream.java"
BROWSER_BIT_STORAGE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBitStorage.java"
BROWSER_GUI_ITEM_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserGuiItemCache.java"
BROWSER_WORLDGEN_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserWorldgenScheduler.java"
BROWSER_RENDER_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserRenderScheduler.java"
BROWSER_IMPROVED_NOISE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserImprovedNoise.java"
BROWSER_NOISE_INTERPOLATOR = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserNoiseInterpolator.java"
BROWSER_PERLIN_NOISE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserPerlinNoise.java"
BROWSER_CLIMATE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserClimate.java"
BROWSER_BLOCK_POS = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBlockPos.java"
BROWSER_BIOME_MANAGER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBiomeManager.java"
BROWSER_AQUIFER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserAquifer.java"
BROWSER_BEARDIFIER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBeardifier.java"
BROWSER_PROTO_CHUNK = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserProtoChunk.java"
BROWSER_MESH_UPLOAD = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserMeshUpload.java"
BROWSER_TARGETING = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserTargeting.java"
BROWSER_CRYPTO = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserCrypto.java"
BROWSER_AES_CFB8 = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserAesCfb8.java"
BROWSER_HTTP_PROXY = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserHttpProxy.java"
BROWSER_SIGNER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserSigner.java"
BROWSER_SINGLEPLAYER_CLIENT = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserSingleplayerClient.java"
BROWSER_INTEGRATED_SERVER_MAIN = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserIntegratedServerMain.java"
MINECRAFT_RESOURCE_SUPPLIER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "MinecraftResourceSupplier.java"
SCHEDULED_THREAD_POOL_EXECUTOR = PORT / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "util" / "concurrent" / "TScheduledThreadPoolExecutor.java"
SERVER_WORKER_BOOTSTRAP = PORT / "web" / "singleplayer" / "server-worker-bootstrap.js"
SINGLEPLAYER_WORKER_SMOKE = PORT / "web" / "smoke" / "singleplayer-worker-smoke.js"
SINGLEPLAYER_WORKER_RUNTIME_SMOKE = PORT / "scripts" / "singleplayer-worker-runtime-smoke.mjs"
SESSION_LAUNCHER_SMOKE = PORT / "scripts" / "session-launcher-smoke.mjs"
SINGLEPLAYER_LAUNCHER = PORT / "web" / "singleplayer" / "index.html"
AUTHLIB_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "AuthlibBrowserPatcher.java"
VERTEX_ARRAY_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "com" / "mojang" / "blaze3d" / "opengl" / "VertexArrayCache.java"
WASM_HOTPATH_C = PORT / "wasm" / "hotpath" / "gaius_hotpath.c"
BUILD_WASM_HOTPATH = PORT / "scripts" / "build-wasm-hotpath.sh"
GENERATE_WASM_HOTPATH = PORT / "scripts" / "generate-wasm-hotpath.py"
GENERATE_POM = PORT / "scripts" / "generate-pom.sh"
BUILD_TEAVM = PORT / "scripts" / "build-teavm.sh"
BUILD_SERVER_WORKER = PORT / "scripts" / "build-teavm-server-worker.sh"
FETCH_VERSION = PORT / "scripts" / "fetch-version.sh"
BUILD_RELEASE = PORT / "scripts" / "build-teavm-release.sh"
BUILD_OVERLAYS = PORT / "scripts" / "build-overlays.sh"
COMPRESS_DIST = PORT / "scripts" / "compress-dist.sh"
BUILD_PORTABLE_HTML = PORT / "scripts" / "build-portable-html.py"
SERVE_DIST = PORT / "scripts" / "serve-dist.py"
PLATFORM_SMOKE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "PlatformSmoke.java"
INDEX_HTML = PORT / "web" / "dist" / "index.html"
HOTPATH_WASM = PORT / "web" / "dist" / "gaius-hotpath.wasm"
GENERATED_RESOURCE_LIST = TARGET / "generated-resources" / "dev" / "gaius" / "browser" / "minecraft-resources.txt"
GENERATED_SOUNDS_JSON = TARGET / "generated-resources" / "assets" / "minecraft" / "sounds.json"
POSTPROCESS_TEAVM_JS = PORT / "scripts" / "postprocess-teavm-js.py"
POSTPROCESS_INDEX_HTML = PORT / "scripts" / "postprocess-index-html.py"
PORTABLE_HTML = DIST / "Gaius.html"
SERVER_WORKER_JS = DIST / "singleplayer-server.js"
SERVER_PLUGIN_POM = ROOT / "apps" / "server-plugin" / "pom.xml"
SERVER_PLUGIN_MAIN = ROOT / "apps" / "server-plugin" / "src" / "main" / "java" / "dev" / "gaius" / "serverplugin" / "GaiusServerBridgePlugin.java"
SERVER_PLUGIN_GATEWAY = ROOT / "apps" / "server-plugin" / "src" / "main" / "java" / "dev" / "gaius" / "serverplugin" / "GaiusWebSocketGateway.java"
SERVER_PLUGIN_YML = ROOT / "apps" / "server-plugin" / "src" / "main" / "resources" / "plugin.yml"
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


def gzip_matches(path: Path) -> bool:
    compressed = Path(str(path) + ".gz")
    if not path.is_file() or not compressed.is_file():
        return False
    try:
        with path.open("rb") as raw, gzip.open(compressed, "rb") as packed:
            while True:
                raw_chunk = raw.read(1024 * 1024)
                packed_chunk = packed.read(1024 * 1024)
                if raw_chunk != packed_chunk:
                    return False
                if not raw_chunk:
                    return True
    except OSError:
        return False


def file_matches(path: Path, pattern: bytes) -> bool:
    if not path.is_file() or path.stat().st_size == 0:
        return False
    try:
        with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            return re.search(pattern, data, re.DOTALL) is not None
    except OSError:
        return False


def portable_embeds_gzip(portable: Path, key: str, compressed: Path) -> bool:
    if not portable.is_file() or not compressed.is_file():
        return False
    encoded = base64.b64encode(compressed.read_bytes()).decode("ascii")
    chunks = [encoded[index:index + 1_000_000] for index in range(0, len(encoded), 1_000_000)]
    expected = (f'"{key}":' + json.dumps(chunks, separators=(",", ":"))).encode("ascii")
    try:
        with portable.open("rb") as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ
        ) as data:
            return data.find(expected) >= 0
    except OSError:
        return False


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
        for marker in (
            "\n  public ",
            "\n  private ",
            "\n  protected ",
            "\n  static ",
            "\n  void ",
        )
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
    netty_browser_channel = NETTY_BROWSER_CHANNEL.read_text(errors="replace") if NETTY_BROWSER_CHANNEL.exists() else ""
    netty_browser_event_loop = NETTY_BROWSER_EVENT_LOOP.read_text(errors="replace") if NETTY_BROWSER_EVENT_LOOP.exists() else ""
    netty_browser_patcher = NETTY_BROWSER_PATCHER.read_text(errors="replace") if NETTY_BROWSER_PATCHER.exists() else ""
    bridge_config = BRIDGE_CONFIG.read_text(errors="replace") if BRIDGE_CONFIG.exists() else ""
    bridge_main = BRIDGE_MAIN.read_text(errors="replace") if BRIDGE_MAIN.exists() else ""
    bridge_policy = BRIDGE_POLICY.read_text(errors="replace") if BRIDGE_POLICY.exists() else ""
    bridge_smoke = BRIDGE_SMOKE.read_text(errors="replace") if BRIDGE_SMOKE.exists() else ""
    online_mode_server_smoke = ONLINE_MODE_SERVER_SMOKE.read_text(errors="replace") if ONLINE_MODE_SERVER_SMOKE.exists() else ""
    stb_image = STB_IMAGE.read_text(errors="replace") if STB_IMAGE.exists() else ""
    browser_memory = LWJGL_BROWSER_MEMORY.read_text(errors="replace") if LWJGL_BROWSER_MEMORY.exists() else ""
    glfw_text = GLFW_BRIDGE.read_text(errors="replace") if GLFW_BRIDGE.exists() else ""
    glfw_patcher = GLFW_PATCHER.read_text(errors="replace") if GLFW_PATCHER.exists() else ""
    client_patcher = CLIENT_PATCHER.read_text(errors="replace") if CLIENT_PATCHER.exists() else ""
    classlib_patcher = CLASSLIB_PATCHER.read_text(errors="replace") if CLASSLIB_PATCHER.exists() else ""
    joml_math_patcher = JOML_MATH_PATCHER.read_text(errors="replace") if JOML_MATH_PATCHER.exists() else ""
    vanilla_pack_resources = VANILLA_PACK_RESOURCES.read_text(errors="replace") if VANILLA_PACK_RESOURCES.exists() else ""
    browser_file_persistence = BROWSER_FILE_PERSISTENCE.read_text(errors="replace") if BROWSER_FILE_PERSISTENCE.exists() else ""
    modern_runtime_support = MODERN_RUNTIME_SUPPORT.read_text(errors="replace") if MODERN_RUNTIME_SUPPORT.exists() else ""
    file_output_stream = FILE_OUTPUT_STREAM.read_text(errors="replace") if FILE_OUTPUT_STREAM.exists() else ""
    browser_bit_storage = BROWSER_BIT_STORAGE.read_text(errors="replace") if BROWSER_BIT_STORAGE.exists() else ""
    browser_gui_item_cache = BROWSER_GUI_ITEM_CACHE.read_text(errors="replace") if BROWSER_GUI_ITEM_CACHE.exists() else ""
    browser_worldgen_scheduler = BROWSER_WORLDGEN_SCHEDULER.read_text(errors="replace") if BROWSER_WORLDGEN_SCHEDULER.exists() else ""
    browser_render_scheduler = BROWSER_RENDER_SCHEDULER.read_text(errors="replace") if BROWSER_RENDER_SCHEDULER.exists() else ""
    browser_improved_noise = BROWSER_IMPROVED_NOISE.read_text(errors="replace") if BROWSER_IMPROVED_NOISE.exists() else ""
    browser_noise_interpolator = BROWSER_NOISE_INTERPOLATOR.read_text(errors="replace") if BROWSER_NOISE_INTERPOLATOR.exists() else ""
    browser_perlin_noise = BROWSER_PERLIN_NOISE.read_text(errors="replace") if BROWSER_PERLIN_NOISE.exists() else ""
    browser_climate = BROWSER_CLIMATE.read_text(errors="replace") if BROWSER_CLIMATE.exists() else ""
    browser_block_pos = BROWSER_BLOCK_POS.read_text(errors="replace") if BROWSER_BLOCK_POS.exists() else ""
    browser_biome_manager = BROWSER_BIOME_MANAGER.read_text(errors="replace") if BROWSER_BIOME_MANAGER.exists() else ""
    browser_aquifer = BROWSER_AQUIFER.read_text(errors="replace") if BROWSER_AQUIFER.exists() else ""
    browser_beardifier = BROWSER_BEARDIFIER.read_text(errors="replace") if BROWSER_BEARDIFIER.exists() else ""
    browser_proto_chunk = BROWSER_PROTO_CHUNK.read_text(errors="replace") if BROWSER_PROTO_CHUNK.exists() else ""
    browser_mesh_upload = BROWSER_MESH_UPLOAD.read_text(errors="replace") if BROWSER_MESH_UPLOAD.exists() else ""
    browser_targeting = BROWSER_TARGETING.read_text(errors="replace") if BROWSER_TARGETING.exists() else ""
    browser_crypto = BROWSER_CRYPTO.read_text(errors="replace") if BROWSER_CRYPTO.exists() else ""
    browser_aes_cfb8 = BROWSER_AES_CFB8.read_text(errors="replace") if BROWSER_AES_CFB8.exists() else ""
    browser_http_proxy = BROWSER_HTTP_PROXY.read_text(errors="replace") if BROWSER_HTTP_PROXY.exists() else ""
    browser_signer = BROWSER_SIGNER.read_text(errors="replace") if BROWSER_SIGNER.exists() else ""
    browser_singleplayer_client = BROWSER_SINGLEPLAYER_CLIENT.read_text(errors="replace") if BROWSER_SINGLEPLAYER_CLIENT.exists() else ""
    browser_integrated_server_main = BROWSER_INTEGRATED_SERVER_MAIN.read_text(errors="replace") if BROWSER_INTEGRATED_SERVER_MAIN.exists() else ""
    minecraft_resource_supplier = MINECRAFT_RESOURCE_SUPPLIER.read_text(errors="replace") if MINECRAFT_RESOURCE_SUPPLIER.exists() else ""
    scheduled_thread_pool_executor = SCHEDULED_THREAD_POOL_EXECUTOR.read_text(errors="replace") if SCHEDULED_THREAD_POOL_EXECUTOR.exists() else ""
    server_worker_bootstrap = SERVER_WORKER_BOOTSTRAP.read_text(errors="replace") if SERVER_WORKER_BOOTSTRAP.exists() else ""
    singleplayer_worker_smoke = SINGLEPLAYER_WORKER_SMOKE.read_text(errors="replace") if SINGLEPLAYER_WORKER_SMOKE.exists() else ""
    singleplayer_worker_runtime_smoke = SINGLEPLAYER_WORKER_RUNTIME_SMOKE.read_text(errors="replace") if SINGLEPLAYER_WORKER_RUNTIME_SMOKE.exists() else ""
    session_launcher_smoke = SESSION_LAUNCHER_SMOKE.read_text(errors="replace") if SESSION_LAUNCHER_SMOKE.exists() else ""
    singleplayer_launcher = SINGLEPLAYER_LAUNCHER.read_text(errors="replace") if SINGLEPLAYER_LAUNCHER.exists() else ""
    authlib_patcher = AUTHLIB_PATCHER.read_text(errors="replace") if AUTHLIB_PATCHER.exists() else ""
    vertex_array_cache_source = VERTEX_ARRAY_CACHE.read_text(errors="replace") if VERTEX_ARRAY_CACHE.exists() else ""
    wasm_hotpath_c = WASM_HOTPATH_C.read_text(errors="replace") if WASM_HOTPATH_C.exists() else ""
    build_wasm_hotpath = BUILD_WASM_HOTPATH.read_text(errors="replace") if BUILD_WASM_HOTPATH.exists() else ""
    generate_wasm_hotpath = GENERATE_WASM_HOTPATH.read_text(errors="replace") if GENERATE_WASM_HOTPATH.exists() else ""
    generate_pom = GENERATE_POM.read_text(errors="replace") if GENERATE_POM.exists() else ""
    build_teavm = BUILD_TEAVM.read_text(errors="replace") if BUILD_TEAVM.exists() else ""
    build_server_worker = BUILD_SERVER_WORKER.read_text(errors="replace") if BUILD_SERVER_WORKER.exists() else ""
    fetch_version = FETCH_VERSION.read_text(errors="replace") if FETCH_VERSION.exists() else ""
    build_release = BUILD_RELEASE.read_text(errors="replace") if BUILD_RELEASE.exists() else ""
    build_overlays = BUILD_OVERLAYS.read_text(errors="replace") if BUILD_OVERLAYS.exists() else ""
    compress_dist = COMPRESS_DIST.read_text(errors="replace") if COMPRESS_DIST.exists() else ""
    build_portable_html = BUILD_PORTABLE_HTML.read_text(errors="replace") if BUILD_PORTABLE_HTML.exists() else ""
    serve_dist = SERVE_DIST.read_text(errors="replace") if SERVE_DIST.exists() else ""
    platform_smoke = PLATFORM_SMOKE.read_text(errors="replace") if PLATFORM_SMOKE.exists() else ""
    index_html = INDEX_HTML.read_text(errors="replace") if INDEX_HTML.exists() else ""
    generated_resource_list = GENERATED_RESOURCE_LIST.read_text(errors="replace") if GENERATED_RESOURCE_LIST.exists() else ""
    generated_sounds = load_json(GENERATED_SOUNDS_JSON) if GENERATED_SOUNDS_JSON.exists() else {}
    postprocess_teavm_js = POSTPROCESS_TEAVM_JS.read_text(errors="replace") if POSTPROCESS_TEAVM_JS.exists() else ""
    postprocess_index_html = POSTPROCESS_INDEX_HTML.read_text(errors="replace") if POSTPROCESS_INDEX_HTML.exists() else ""
    server_plugin_pom = SERVER_PLUGIN_POM.read_text(errors="replace") if SERVER_PLUGIN_POM.exists() else ""
    server_plugin_main = SERVER_PLUGIN_MAIN.read_text(errors="replace") if SERVER_PLUGIN_MAIN.exists() else ""
    server_plugin_gateway = SERVER_PLUGIN_GATEWAY.read_text(errors="replace") if SERVER_PLUGIN_GATEWAY.exists() else ""
    server_plugin_yml = SERVER_PLUGIN_YML.read_text(errors="replace") if SERVER_PLUGIN_YML.exists() else ""
    teavm_build_log = (TARGET / "teavm-build.log").read_text(errors="replace") if (TARGET / "teavm-build.log").exists() else ""
    server_worker_build_log = (TARGET / "server-worker" / "teavm-build.log").read_text(errors="replace") if (TARGET / "server-worker" / "teavm-build.log").exists() else ""
    tex_sub_start = text.find("public static void texSubImage2D(")
    tex_sub_end = text.find("@JSBody(script = \"\"\"", tex_sub_start)
    tex_sub_section = text[tex_sub_start:tex_sub_end] if tex_sub_start >= 0 and tex_sub_end > tex_sub_start else text
    server_catchup_start = client_patcher.find("private static boolean patchMinecraftServerBrowserCatchupReset")
    server_catchup_end = client_patcher.find("private static boolean hookMinecraftServerStopDiagnostics", server_catchup_start)
    server_catchup_section = (
        client_patcher[server_catchup_start:server_catchup_end]
        if server_catchup_start >= 0 and server_catchup_end > server_catchup_start
        else client_patcher
    )
    initial_spawn_start = client_patcher.find("private static void replaceInitialSpawnForBrowser")
    initial_spawn_end = client_patcher.find(
        "private static boolean patchMinecraftServerBrowserCatchupReset",
        initial_spawn_start,
    )
    initial_spawn_section = (
        client_patcher[initial_spawn_start:initial_spawn_end]
        if initial_spawn_start >= 0 and initial_spawn_end > initial_spawn_start
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
            "Browser output streams truncate existing virtual files before replacement writes",
            "truncateIfRequested" in file_output_stream
            and "accessor.resize(0)" in file_output_stream
            and "accessor.seek(0)" in file_output_stream
            and "patchDefaultFileSystemProviderOutputStream" in classlib_patcher
            and '"truncateIfRequested"' in classlib_patcher
            and '"(Lorg/teavm/runtime/fs/VirtualFileAccessor;Z)V"' in classlib_patcher
            and "Browser output stream did not truncate an existing file" in platform_smoke,
        ),
        (
            "TeaVM ZIP inflater receives the required raw DEFLATE trailing byte",
            "patchZipFileRawInflaterPadding" in classlib_patcher
            and 'className + "$ZipInflaterInputStream"' in classlib_patcher
            and 'streamClass, "mLength", "J"' in classlib_patcher
            and "Opcodes.LCONST_1" in classlib_patcher
            and "Opcodes.LADD" in classlib_patcher
            and "browser ZIP pack entry content compressed=" in platform_smoke,
        ),
        (
            "TeaVM client and server Worker logs contain no JSBody parser errors",
            "Error in @JSBody" not in teavm_build_log
            and "Error in @JSBody" not in server_worker_build_log,
        ),
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
            and "window.__gaiusGL.executeDraw(4,mode,count,type,offset,baseVertex,0);" in text,
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
            and "drawAttribPrepareFastSkips" in text
            and "drawProgramGeneration:1" in text
            and "drawReadyGeneration:-1" in text
            and "bumpDrawProgramGeneration=function()" in text
            and "programAttribCache:new Map()" in text
            and "vao.programAttribCache.set(program|0" in text
            and "if (repaired && vao.programAttribCache.size) vao.programAttribCache.clear();" in text
            and "if (!vao.misalignedAttribs || !vao.misalignedAttribs.size)" in text
            and "const fastAttribsPrepared=(vao.drawReadyGeneration|0)===drawGeneration" in text
            and "let attribsChecked=false" in text
            and "if (!fastAttribsPrepared) {" in text
            and "attribsChecked=true" in text
            and "const attribsReady=fastAttribsPrepared" in text
            and "if (attribsReady)" in text
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
            and "bufferShadowPolicyVersion" not in text
            and "bufferShadowDecisionCache" not in text
            and "bumpBufferShadowPolicyVersion" not in text
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
            "BrowserOpenGL tracks and seeds per-buffer misaligned attribute references",
            "initializeMisalignedBufferRefs" in text
            and "misalignedBufferRefs=new Map()" in text
            and "v.misalignedAttribBuffers=new Map()" in text
            and "addMbr" in text
            and "delMbr" in text
            and "v.misalignedAttribBuffers.set(i,b)" in text
            and "s.misalignedBufferRefs.set(b,(n+1)|0)" in text
            and "releaseVaoMisalignedBuffers" in text
            and "const refs=this.misalignedBufferRefs" in text
            and "if(refs)return((refs.get(id)||0)>0)" in text
            and "this.vaoEmu.forEach(function(v)" in text
            and "state.releaseVaoMisalignedBuffers(state.vaoEmu.get(array))" in text,
        ),
        (
            "BrowserOpenGL skips redundant WebGL state calls in hot paths",
            "drawCallsCount" in text
            and "drawWindowCallsCount" in text
            and "knownCaps" in text
            and "enabledCaps" in text
            and "state.viewportX===(x|0)" in text
            and "state.scissorX===(x|0)" in text
            and "state.currentProgram|0" in text
            and "state.currentVaoId|0" in text,
        ),
        (
            "BrowserOpenGL keeps WebGL byte offsets off TeaVM long JS interop",
            "private static native void drawElementsJs(int mode, int count, int type, int offset)" in text
            and "drawElementsJs(mode, count, type, (int) offset);" in text
            and "private static native void vertexAttribPointerJs(" in text
            and "private static native void bindVertexBufferJs(int binding, int buffer, int offset, int stride)" in text
            and "private static native void bindBufferRangeJs(" in text
            and "private static native int fenceSyncJs(int condition, int flags)" in text
            and "public static native void drawElements(int mode, int count, int type, long offset)" not in text
            and "public static native void bindBufferRange(" not in text,
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
            "BrowserOpenGL uploads Java buffers through zero-copy typed-array views",
            text.count("Int8Array.fromJavaBuffer") >= 4
            and "byte[] data = new byte[copy.remaining()]" not in text
            and "byte[] data = new byte[(int) length]" not in text
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
            and "([JIIIJ)I" in client_patcher
            and "([JIIIIJ)I" in client_patcher
            and "public static native boolean unpack" in browser_bit_storage
            and browser_bit_storage.count("@JSByRef long[] packed") == 3
            and "@JSByRef int[] output" in browser_bit_storage
            and "public static native int get(" in browser_bit_storage
            and "public static native int getAndSet(" in browser_bit_storage
            and "long mask" in browser_bit_storage
            and "__gaiusBitStorageMasks" not in browser_bit_storage
            and "__gaiusBitStorageWords" in browser_bit_storage
            and "new Uint32Array(source.buffer, source.byteOffset, source.length * 2)" in browser_bit_storage
            and "source.BYTES_PER_ELEMENT === 8" in browser_bit_storage
            and "offset + bitCount > 32" in browser_bit_storage
            and "const shift = BigInt(offset)" in browser_bit_storage
            and "hotpath.unpackBitStorage" in browser_bit_storage
            and "bitStorageJsUnpack" in browser_bit_storage
            and "Browser bit-storage get changed" in platform_smoke
            and "Browser bit-storage getAndSet changed" in platform_smoke
            and "Browser bit-storage set changed" in platform_smoke
            and "for (int index = 0; index < size; index++)" in platform_smoke,
        ),
        (
            "Heightmap scalar reads cache minY and use concrete browser bit storage",
            "patchHeightmapBrowserStorage" in client_patcher
            and '"browserMinY"' in client_patcher
            and '"browserData"' in client_patcher
            and '"browserValuesPerLong"' in client_patcher
            and '"browserBits"' in client_patcher
            and '"browserMask"' in client_patcher
            and '"dev/gaius/browser/BrowserBitStorage"' in client_patcher
            and 'find(node, "getFirstAvailable", "(I)I")' in client_patcher
            and 'new String[] {"getFirstAvailable", "getHighestTaken"}' in client_patcher
            and 'find(node, "setHeight", "(III)V")' in client_patcher,
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
            and "(calls & 255) !== 0" in text
            and "__gaiusReadWebGLErrors" in text
            and "glErrors" in text
            and "return window.__gaiusReadWebGLErrors ? (window.__gaiusWebGL.getError()|0) : 0" in text,
        ),
        (
            "BrowserOpenGL submits draws without allocating wrapper callbacks",
            "executeDraw=function(kind,mode,a,b,c,d,e)" in text
            and "currentVaoCacheId" in text
            and "currentVaoCache" in text
            and "const attribsPrepared=(vao.drawReadyGeneration|0)===slowDrawGeneration" in text
            and "} else if (!attribsChecked) {" in text
            and "window.__gaiusGL.executeDraw(0,mode,first,count,0,0,0);" in text
            and "window.__gaiusGL.executeDraw(5,mode,count,type,offset,instances,baseVertex);" in text
            and "window.__gaiusGL.withGuiItemOffscreenScissorRepair(function()" not in text,
        ),
        (
            "BrowserOpenGL bypasses draw cleanup machinery for stable world draws",
            "if (!guiDraw && !repairOffscreenScissor)" in text
            and "if (attribsReady)" in text
            and "case 0: gl.drawArrays(mode,a|0,b|0); break;" in text
            and text.find("if (!guiDraw && !repairOffscreenScissor)")
            < text.find("let failed=true;")
            and "drawAttribPreparedDirectDirty" not in text
            and "drawAttribPreparedMisalignedCount" not in text
            and "if(had!==!!m)v.drawReadyGeneration=-1" in text
            and "drawAttribPreparedProgram" not in text
            and "drawAttribPreparedVersion" not in text
            and "drawAttribPreparedGlobalVersion" not in text
            and "const directDirty=vao.directAttribDirty ? 1 : 0" not in text,
        ),
        (
            "BrowserOpenGL disables hot-path diagnostics during normal gameplay",
            "initializePerformanceStateJs" in text
            and "state.hotPathTelemetryEnabled=!!enabled" in text
            and "params.get('glStats')==='1'" in text
            and "diag==='perf'" in text
            and "if (this.hotPathTelemetryEnabled) this.recordDrawCall();" in text
            and "if (state.hotPathTelemetryEnabled)" in text,
        ),
        (
            "BrowserOpenGL skips redundant fixed-function and texture state",
            "state.textureBufferDefaults=state.textureBufferDefaults || new Set()" in text
            and "state.textureBindings.has(webKey)" in text
            and "if (!alreadyBound) gl.bindTexture(webTarget,object);" in text
            and "state.textureBufferDefaults.add(texture|0);" in text
            and "if (state && (state.activeTextureUnit|0)===next) return;" in text
            and "state.colorMaskBits=(mask[0]?1:0)" in text
            and "if (state && (state.colorMaskBits|0)===bits) return;" in text
            and "state.viewportX===(x|0)" in text
            and "state.scissorX===(x|0)" in text
            and "state.blendSourceRgb!==undefined" in text,
        ),
        (
            "BrowserOpenGL caches draw capabilities and framebuffer classification",
            "initializeDrawStateCacheJs();" in text
            and "state.enabledCapBits=0" in text
            and "state.capabilityBit=function(capability)" in text
            and "state.offscreen512Framebuffers=new Set()" in text
            and "state.setDrawFramebufferCache=function(framebuffer)" in text
            and "state.refreshFramebufferOffscreen512=function(framebuffer)" in text
            and "state.refreshFramebuffersForTexture=function(texture)" in text
            and "const capBits=this.enabledCapBits|0" in text
            and "(capBits & 1)!==0" in text
            and "(capBits & 2)!==0" in text
            and "this.drawFramebufferOffscreenKnown" in text
            and "&& this.enabledCaps.has(gl.CULL_FACE)" not in text
            and "&& this.enabledCaps.has(gl.SCISSOR_TEST)" not in text
            and "if (!physicallyDisabled) window.__gaiusWebGL.disable(capability);" in text,
        ),
        (
            "BrowserOpenGL keeps normal texture uploads allocation-light",
            "kind==='texImage2D' && (level|0)===0" in text
            and "if (!this.hotPathTelemetryEnabled) return;" in text
            and "stats.textureInfo[String(texture)]=info" in text
            and "Array.from(this.textureInfo.entries())" not in text,
        ),
        (
            "BrowserOpenGL caches framebuffer, sampler, texture parameter, and indexed-buffer state",
            "state.textureParameters=state.textureParameters || new Map()" in text
            and "state.samplerBindings=state.samplerBindings || new Map()" in text
            and "state.indexedBufferBindings=state.indexedBufferBindings || new Map()" in text
            and "target===gl.FRAMEBUFFER" in text
            and "state.samplerBindings.has(unit|0)" in text
            and "previous.range===true" in text
            and "previous.range===false" in text
            and "const previousId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0" in text
            and "gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING)" not in text
            and "if (!unchanged)" in text,
        ),
        (
            "BrowserOpenGL uses allocation-free binding keys in frame hot paths",
            "const keyBase=unit*65536" in text
            and "keyBase+((target|0)&65535)" in text
            and "keyBase+(gl.TEXTURE_2D&65535)" in text
            and "const key=(target|0)*65536+(index|0)" in text
            and "unit + ':' + target" not in text
            and "(target|0)+':'+(index|0)" not in text,
        ),
        (
            "BrowserOpenGL keeps base-vertex fallback cache allocation-light",
            "cacheShiftedIndexBuffer=function(vao,type,offset,count,baseVertex)" in text
            and "const cached=vao.shiftedIndexLast" in text
            and "cached && !cached.deleted" in text
            and "vao.shiftedIndexLast=entry" in text
            and "oldest.deleted=true" in text
            and "if (this.guiDrawDiagnostics && (this.guiDrawsRemaining|0)>0)" in text
            and "this.baseVertexExtensionChecked" in text
            and "const stats=this.hotPathTelemetryEnabled" in text
            and "drawElementsWithBaseVertex=function(vao,mode,count,type,offset,instances,baseVertex)"
            in text
            and "const vao=this.getVaoEmu();" not in text[
                text.find("drawElementsWithBaseVertex=function") : text.find(
                    "cacheShiftedIndexBuffer(vao,type,off,count,base)",
                    text.find("drawElementsWithBaseVertex=function"),
                )
            ],
        ),
        (
            "BrowserOpenGL caches alternating base-vertex draws without string keys",
            "shiftedIndexFastCache:new Map()" in text
            and "Math.imul((fastKey^(type|0))|0,16777619)" in text
            and "fastEntry.offset===start" in text
            and "(fastEntry.inputCount|0)===length" in text
            and "(fastEntry.base|0)===base" in text
            and "fastCache.size >= 64" in text
            and "baseVertexIndexFastCacheHits" in text
            and "this.cacheShiftedIndexBuffer(vao,type,off,count,base)" in text
            and text.find(
                "const fastEntry=fastCache.get(fastKey)",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            < text.find(
                "const source=this.bufferBytes.get(elementBuffer)",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            and text.find(
                "const cached=vao.shiftedIndexLast",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            < text.find(
                "const source=this.bufferBytes.get(elementBuffer)",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            and "Math.imul((fastKey^(version|0))|0,16777619)" not in text
            and "(cached.version|0)===(version|0)" not in text
            and "(fastEntry.version|0)===(version|0)" not in text,
        ),
        (
            "BrowserOpenGL invalidates derived buffer caches without global scans",
            "alignedAttribCacheKeys:new Map()" in text
            and "shiftedIndexCacheKeys:new Map()" in text
            and "dropBufferDerivedCaches" in text
            and "registerBufferCacheKey" in text
            and "forgetBufferCacheKey" in text
            and "alignedAttribCache.forEach" not in text
            and "shiftedIndexCache.forEach" not in text,
        ),
        (
            "BrowserOpenGL reuses binding records and defers texture lookups",
            "const object=(!alreadyBound || defaultsCandidate)" in text
            and "if (!alreadyBound) state.textureBindings.set(webKey,texture|0)" in text
            and "const aliasKey=keyBase+35882" in text
            and "let previous=state.indexedBufferBindings.get(key)" in text
            and "previous.range=true" in text
            and "state.indexedBufferBindings.set(key,{" not in text
            and "if ((vao.elementArrayBuffer|0)===nextId)" in text
            and "state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null)"
            in text
            and "const object=nextId===0?null:state.buffers.get(nextId)" in text
            and "state.bindPhysicalElementBuffer(vao,object || null)" in text,
        ),
        (
            "BrowserOpenGL defers physical element-buffer restores safely",
            "actualElementArrayBuffer:null" in text
            and "elementArrayBufferObject:null" in text
            and "bindPhysicalElementBuffer=function(vao, buffer)" in text
            and "ensureLogicalElementBuffer=function(vao)" in text
            and "this.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null)"
            in text
            and "const buffer=id ? this.buffers.get(id) : null;" not in text
            and "forgetPhysicalElementBuffer=function(buffer)" in text
            and "this.bindPhysicalElementBuffer(vao,shiftedIndex.buffer);" in text
            and "gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,originalObject || null);" not in text
            and text.count("this.ensureLogicalElementBuffer(vao); gl.drawElements") >= 2
            and text.count("state.ensureLogicalElementBuffer(state.getVaoEmu());") >= 4
            and "sourceTarget===gl.ELEMENT_ARRAY_BUFFER || targetTarget===gl.ELEMENT_ARRAY_BUFFER"
            in text
            and "state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER,vao.elementArrayBuffer|0)"
            in text,
        ),
        (
            "BrowserOpenGL skips unchanged scalar uniforms and invalidates safely",
            "state.uniform1iValues=state.uniform1iValues || new Map()" in text
            and "state.uniform1fValues=state.uniform1fValues || new Map()" in text
            and "state.programUniformLocations=state.programUniformLocations || new Map()" in text
            and "state.uniform1iValues.has(location|0)" in text
            and "Object.is(state.uniform1fValues.get(location|0),value)" in text
            and "state.uniformValueCache.delete(location|0)" in text
            and "this.uniform1fValues.delete(key)" in text
            and "this.uniform1iValues.delete(key)" in text
            and "state.clearProgramUniforms(program|0)" in text,
        ),
        (
            "BrowserOpenGL caches vector and matrix uniforms without transient arrays",
            "initializeUniformValueCacheJs();" in text
            and "uniformScalarsChanged=function(location,kind,count,x,y,z,w)" in text
            and "uniformArrayChanged=function(location,kind,transpose,values)" in text
            and "Object.is(cached[i],values[i])" in text
            and "new Float64Array(count|0)" in text
            and "values:integer ? new Int32Array(length) : new Float32Array(length)" in text
            and text.count("state.uniformArrayChanged(location,") >= 11
            and "uniformValueFastSkips" in text
            and "ThreadLocal.withInitial(UniformScratch::new)" in text
            and "buffer.get(position + i)" in text
            and "UNIFORM_SCRATCH.get().floats(count)" in text
            and "UNIFORM_SCRATCH.get().ints(count)" in text
            and "FloatBuffer copy = buffer.duplicate()" not in text
            and "IntBuffer copy = buffer.duplicate()" not in text,
        ),
        (
            "Minecraft state diagnostics are sampled instead of rebuilt every frame",
            "shouldReportMinecraftState" in text
            and "current && current.level ? 100 : 50" in text
            and '"shouldReportMinecraftState"' in client_patcher
            and "Opcodes.IFEQ, skipped" in client_patcher,
        ),
        (
            "Browser block targeting skips duplicate raycasts for an unchanged camera",
            "hasLastCamera" in browser_targeting
            and "lastCameraEntity == cameraEntity" in browser_targeting
            and "Math.abs(start.x - lastX) < 1.0E-9" in browser_targeting
            and "Math.abs(forward.x() - lastForwardX) < 1.0E-7F" in browser_targeting
            and "return current;" in browser_targeting,
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
            and "repairOffscreenScissor" in text
            and "const repairOffscreenScissor=drawFramebuffer!==0" in text
            and "if (failed && repairOffscreenScissor)" in text
            and "withGuiItemOffscreenScissorRepair=function(draw)" not in text
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
            and "window.__gaiusGL.executeDraw(1,mode,count,type,offset,0,0);" in text,
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
            "Platform smoke decodes and plays the packaged eating sound through OpenAL",
            "testEatingSoundAsset" in platform_smoke
            and "assets/minecraft/sounds/random/eat1.ogg" in platform_smoke
            and "JOrbisAudioStream" in platform_smoke
            and "decoder.readAll()" in platform_smoke
            and "AL_FORMAT_MONO16" in platform_smoke
            and "AL_FORMAT_STEREO16" in platform_smoke
            and "Eating sound did not decode to playable PCM" in platform_smoke,
        ),
        (
            "Browser Netty channel routes multiplayer ByteBufs through WebSocket TCP bridge",
            "class BrowserWebSocketChannel" in netty_browser_channel
            and "globalThis.__gaiusNettyBridge" in netty_browser_channel
            and "globalThis.__gaiusNetworkStats" in netty_browser_channel
            and "new WebSocket(candidate.url)" in netty_browser_channel
            and "__gaiusLocalServerPorts" in netty_browser_channel
            and "localPort.postMessage" in netty_browser_channel
            and "const control = {type: 'connect'" in netty_browser_channel
            and "copyBytes(ByteBuf buffer)" in netty_browser_channel
            and "pipeline.fireChannelRead(Unpooled.wrappedBuffer(bytes))" in netty_browser_channel
            and "MAX_CHUNKS_PER_PUMP" in netty_browser_channel
            and "MAX_BYTES_PER_PUMP = 2 * 1024 * 1024" in netty_browser_channel
            and "MAX_MILLIS_PER_PUMP = 4.0" in netty_browser_channel
            and "bytesPumped < MAX_BYTES_PER_PUMP" in netty_browser_channel
            and "monotonicMillis() - pumpStarted >= MAX_MILLIS_PER_PUMP" in netty_browser_channel
            and "recordPump(" in netty_browser_channel
            and "ConcurrentHashMap" not in netty_browser_channel
            and "AtomicInteger" not in netty_browser_channel
            and "Collections.newSetFromMap" not in netty_browser_channel,
        ),
        (
            "Browser Netty channel bounds both queues and backpressures large bursts",
            "const maximumInboundQueueBytes = 64 * 1024 * 1024" in netty_browser_channel
            and "const inboundPauseBytes = 24 * 1024 * 1024" in netty_browser_channel
            and "const inboundResumeBytes = 8 * 1024 * 1024" in netty_browser_channel
            and "const maximumWebSocketBufferedBytes = 4 * 1024 * 1024" in netty_browser_channel
            and "const maximumOutboundQueueBytes = 16 * 1024 * 1024" in netty_browser_channel
            and "setInboundPaused(entry, true)" in netty_browser_channel
            and "setInboundPaused(entry, false)" in netty_browser_channel
            and "{type: 'flow', paused: !!paused}" in netty_browser_channel
            and "entry.ws.bufferedAmount >= maximumWebSocketBufferedBytes" in netty_browser_channel
            and "flush(entry);" in netty_browser_channel
            and "inboundHead" in netty_browser_channel
            and "outboundHead" in netty_browser_channel
            and "entry.inbound.shift()" not in netty_browser_channel
            and "entry.outbound.shift()" not in netty_browser_channel
            and "peakInboundQueuedBytes" in netty_browser_channel
            and "peakPumpMillis" in netty_browser_channel
            and "deferredPumps" in netty_browser_channel,
        ),
        (
            "Browser Netty registration and connect complete before blocking client waits",
            "shouldRegisterInline" in netty_browser_channel
            and "eventLoopFor" in netty_browser_channel
            and "connectInline" in netty_browser_channel
            and "channel.unsafe().connect(remote, local, promise)" in netty_browser_channel
            and "class BrowserInlineEventLoop extends DefaultEventLoop" in netty_browser_event_loop
            and "return true" in netty_browser_event_loop
            and "command.run()" in netty_browser_event_loop
            and "patchAbstractChannelUnsafe" in netty_browser_patcher
            and "patchBootstrap" in netty_browser_patcher
            and '"shouldRegisterInline"' in netty_browser_patcher
            and '"connectInline"' in netty_browser_patcher,
        ),
        (
            "Platform smoke verifies browser Netty connect and outbound bridge bytes",
            "testBrowserNetwork()" in platform_smoke
            and "BrowserWebSocketChannel.class" in platform_smoke
            and "connected.isDone()" in platform_smoke
            and "connected.isSuccess()" in platform_smoke
            and "writeAndFlush" in platform_smoke
            and "networkBytesQueuedOrSent()" in platform_smoke
            and "scheduleNetworkRoundTripCheck()" in platform_smoke
            and "stats.receivedBytes" in platform_smoke,
        ),
        (
            "Browser crypto implements online-mode RSA, SHA-1, secure keys, and stateful AES/CFB8",
            "globalThis.crypto.getRandomValues" in browser_crypto
            and "private static native int secureRandomInt()" in browser_crypto
            and "bytes[offset + index] = (byte)" in browser_crypto
            and '@JSBody(params = {"bytes"}' not in browser_crypto
            and "BigInteger encrypted = message.modPow" in browser_crypto
            and "public static byte[] sha1" in browser_crypto
            and "public static byte[] sha256" in browser_crypto
            and "parseRsaPrivateKey" in browser_crypto
            and "public static byte[] signUsingKey" in browser_crypto
            and "applyPrivate" in browser_crypto
            and "parseRsaPublicKey" in browser_crypto
            and "class BrowserAesCfb8" in browser_aes_cfb8
            and "encryptFirstFeedbackByte" in browser_aes_cfb8
            and "feedback[feedback.length - 1]" in browser_aes_cfb8
            and "System.arraycopy(feedback, 1" in browser_aes_cfb8,
        ),
        (
            "Minecraft Crypt and javax.crypto stubs delegate to browser online-mode crypto",
            '"dev/gaius/browser/BrowserCrypto"' in client_patcher
            and '"generateSecretKey"' in client_patcher
            and '"digestData"' in client_patcher
            and '"parseRsaPublicKey"' in client_patcher
            and '"encryptUsingKey"' in client_patcher
            and '"dev/gaius/browser/BrowserAesCfb8"' in client_patcher
            and '"createAesCfb8"' in client_patcher,
        ),
        (
            "Browser secure-profile signing uses fetched RSA keys and SHA256withRSA",
            "BrowserCrypto.signUsingKey" in browser_signer
            and '"SHA256withRSA".equalsIgnoreCase' in browser_signer
            and '"parseRsaPrivateKey"' in client_patcher
            and '"dev/gaius/browser/BrowserSigner"' in client_patcher
            and "patchAccountProfileKeys" not in client_patcher
            and '"UNSIGNED"' not in client_patcher,
        ),
        (
            "Platform smoke verifies browser online-mode cryptographic primitives",
            "testBrowserCrypto()" in platform_smoke
            and "3b79424c9c0dd436bace9e0ed4586a4f" in platform_smoke
            and "a9993e364706816aba3e25717850c26c9cd0d89d" in platform_smoke
            and "ba7816bf8f01cfea414140de5dae2223" in platform_smoke
            and "BrowserCrypto.parseRsaPublicKey" in platform_smoke
            and "BrowserCrypto.encryptUsingKey" in platform_smoke
            and "BrowserCrypto.parseRsaPrivateKey" in platform_smoke
            and "BrowserCrypto.signUsingKey" in platform_smoke,
        ),
        (
            "Platform smoke verifies browser server-pack ZIP write and read",
            "testBrowserZipPack()" in platform_smoke
            and 'new ZipEntry("pack.mcmeta")' in platform_smoke
            and "new ZipFile(path.toFile())" in platform_smoke
            and "archive.getInputStream(entry).readAllBytes()" in platform_smoke,
        ),
        (
            "Platform smoke verifies Minecraft network zlib compression across resets",
            "testNetworkCompression()" in platform_smoke
            and "new Deflater()" in platform_smoke
            and "new Inflater()" in platform_smoke
            and "deflater.finished()" in platform_smoke
            and "inflater.setInput(compressedBuffer)" in platform_smoke
            and "inflater.inflate(output)" in platform_smoke
            and "deflater.reset()" in platform_smoke
            and "inflater.reset()" in platform_smoke,
        ),
        (
            "Browser bridge defaults to any Minecraft host while keeping origin/token gates",
            'allowedHosts: parseList("GAIUS_ALLOWED_HOSTS", ["*"])' in bridge_config
            and '"http://127.0.0.1:8781"' in bridge_config
            and 'if (allowed === "*")' in bridge_policy
            and "isOriginAllowed" in bridge_policy
            and "parseConnectRequest" in bridge_policy,
        ),
        (
            "Browser bridge resolves Minecraft SRV records in Node before TCP connect",
            'import { resolveSrv } from "node:dns/promises";' in bridge_main
            and "resolveMinecraftTargets" in bridge_main
            and "_minecraft._tcp." in bridge_main
            and "resolveSrv" in bridge_main
            and "orderSrvRecords" in bridge_main
            and "for (const target of targets)" in bridge_main
            and "connectTcpTarget" in bridge_main
            and "targets.push(request)" in bridge_main,
        ),
        (
            "Browser bridge proxies resource packs and Mojang authentication with security gates",
            '"/proxy/resource-pack"' in bridge_main
            and '"/proxy/auth"' in bridge_main
            and '"/proxy/texture"' in bridge_main
            and '"/proxy/realms"' in bridge_main
            and "isOriginAllowed(origin, config.allowedOrigins)" in bridge_main
            and "tokenMatches(requestUrl.searchParams.get" in bridge_main
            and "validateProxyTarget" in bridge_main
            and "allowedAuthHosts" in bridge_main
            and "allowedRealmsHosts" in bridge_main
            and "maximumResourcePackBytes" in bridge_main
            and "maximumTextureBytes" in bridge_main
            and "fetchWithValidatedRedirects" in bridge_main,
        ),
        (
            "Browser bridge applies bidirectional multiplayer backpressure",
            "webSocket.bufferedAmount" in bridge_main
            and "tcpPausedForWebSocket" in bridge_main
            and "tcpPausedForClient" in bridge_main
            and "updateTcpReadState" in bridge_main
            and 'message?.type !== "flow"' in bridge_main
            and "tcpSocket.pause()" in bridge_main
            and "tcpSocket.resume()" in bridge_main
            and 'idleTimeoutMs: parseInteger("GAIUS_IDLE_TIMEOUT_MS", 10 * 60_000' in bridge_config
            and 'maximumConnections: parseInteger("GAIUS_MAXIMUM_CONNECTIONS", 1024' in bridge_config
            and "webSocketServer.clients.size >= config.maximumConnections" in bridge_main
            and 'maximumFrameBytes: parseInteger("GAIUS_MAXIMUM_FRAME_BYTES", 16 * 1024 * 1024' in bridge_config,
        ),
        (
            "Browser multiplayer probes direct plugins and fails over temporary relay nodes",
            "directPluginUrl" in netty_browser_channel
            and "gaius.bridgeNodes" in netty_browser_channel
            and "params.getAll('bridge')" in netty_browser_channel
            and "openRemoteCandidate" in netty_browser_channel
            and "candidate.direct ? 800 : 8000" in netty_browser_channel
            and "No Gaius direct endpoint or relay node could reach the server" in netty_browser_channel
            and "relayFailovers" in netty_browser_channel,
        ),
        (
            "Optional Paper plugin exposes the same constrained WebSocket tunnel protocol",
            "paper-api" in server_plugin_pom
            and "Java-WebSocket" in server_plugin_pom
            and "GaiusServerBridgePlugin" in server_plugin_yml
            and "getServer().getPort()" in server_plugin_main
            and "extends WebSocketServer" in server_plugin_gateway
            and 'type.equals("connect")' in server_plugin_gateway
            and 'type.equals("flow")' in server_plugin_gateway
            and 'webSocket.send("{\\\"type\\\":\\\"connected\\\"}")' in server_plugin_gateway
            and "new InetSocketAddress(minecraftHost, minecraftPort)" in server_plugin_gateway
            and "maximumFrameBytes" in server_plugin_gateway
            and "MessageDigest.isEqual" in server_plugin_gateway,
        ),
        (
            "Browser bridge pairs isolated client/server tunnels for worker singleplayer",
            "localTunnelSessions" in bridge_main
            and "parseLocalTunnelHost" in bridge_main
            and "registerLocalTunnel" in bridge_main
            and "closeLocalTunnelSession" in bridge_main
            and "updateLocalReadState" in bridge_main
            and 'client|server' in bridge_main
            and 'gaius-local' in bridge_main
            and 'type: "connected"' in bridge_main,
        ),
        (
            "Singleplayer launches the official server in a dedicated Worker over MessageChannel",
            "new MessageChannel()" in browser_singleplayer_client
            and "new Worker(workerUrl" in browser_singleplayer_client
            and "singleplayer-server-worker.js" in browser_singleplayer_client
            and "ConnectScreen.startConnecting" in browser_singleplayer_client
            and "client-" in browser_singleplayer_client
            and "Main.main(new String[]" in browser_integrated_server_main
            and '"--nogui"' in browser_integrated_server_main
            and '"--universe", "/gaius/saves"' in browser_integrated_server_main
            and "server-" in browser_integrated_server_main
            and "syncDistances" in browser_singleplayer_client
            and "renderDistance: Math.max(2" in browser_singleplayer_client
            and "simulationDistance: Math.max(2" in browser_singleplayer_client
            and "setIntegratedServerDistances" in browser_integrated_server_main
            and "setViewDistance(view)" in browser_integrated_server_main
            and "setSimulationDistance(simulation)" in browser_integrated_server_main
            and "INITIAL_VIEW_DISTANCE = 1" in browser_integrated_server_main
            and "INITIAL_SIMULATION_DISTANCE = 1" in browser_integrated_server_main
            and "minimumServerViewDistance" in browser_integrated_server_main
            and "patchChunkMapBrowserInitialViewDistance" in client_patcher
            and '"server-distances-staged"' in browser_integrated_server_main
            and '"server-distances-ramping"' in browser_integrated_server_main
            and "activateConfiguredDistances" in browser_integrated_server_main
            and "advanceConfiguredDistances" in browser_integrated_server_main
            and "patchServerGamePacketListenerBrowserWorker" in client_patcher
            and '"handleChunkBatchReceived"' in client_patcher
            and 'message.type === "distances"' in server_worker_bootstrap
            and "__gaiusServerViewDistance" in server_worker_bootstrap
            and "__gaiusServerSimulationDistance" in server_worker_bootstrap
            and "__gaiusLocalServerPorts" in server_worker_bootstrap
            and "importScripts(scriptUrl)" in server_worker_bootstrap,
        ),
        (
            "Singleplayer Worker stops cleanly and refreshes browser storage",
            "requestWorkerStop" in browser_singleplayer_client
            and "__gaiusSingleplayerHandoff" in browser_singleplayer_client
            and "refreshPersistentFiles" in browser_singleplayer_client
            and "message.type === 'stopped'" in browser_singleplayer_client
            and "stopIntegratedServer" in browser_integrated_server_main
            and "isIntegratedServerStopped" in browser_integrated_server_main
            and 'message.type === "stop"' in server_worker_bootstrap
            and 'message.type !== "start"' in server_worker_bootstrap
            and "pendingChanges = new Map()" in server_worker_bootstrap
            and "scheduleFlush" in server_worker_bootstrap
            and "writeBatch(changes)" in server_worker_bootstrap
            and "await flushPendingChanges()" in server_worker_bootstrap
            and "resolved.search = location.search" in server_worker_bootstrap
            and "storage-write-error" in server_worker_bootstrap
            and "failLocalSession" in netty_browser_channel
            and "terminateFailedWorker" in browser_singleplayer_client
            and "__gaiusStorageRefresh" in browser_singleplayer_client
            and "workers.delete(sessionId)" in browser_singleplayer_client
            and "ports.delete(sessionId)" in browser_singleplayer_client
            and "Integrated server did not stop within 30 seconds" in browser_singleplayer_client
            and "__gaiusHandoffPending" in browser_singleplayer_client
            and "__gaiusClientAttached" in browser_singleplayer_client
            and "singleplayer:handoff-disconnect-ignored" in browser_singleplayer_client
            and "singleplayer:client-attached" in netty_browser_channel
            and "Integrated server client did not attach within 60 seconds" in browser_singleplayer_client
            and "async function" not in browser_singleplayer_client
            and "for (const" not in browser_singleplayer_client
            and "for (const" not in netty_browser_channel
            and 'type: "stopped"' in server_worker_bootstrap,
        ),
        (
            "Portable HTML keeps singleplayer assets and server execution in the browser",
            PORTABLE_HTML.exists()
            and PORTABLE_HTML.stat().st_size > 100_000_000
            and "build-portable-html.py" in build_release
            and "DecompressionStream" in build_portable_html
            and "setTimeout(resolve, 0)" in build_portable_html
            and "__gaiusPortableAssetsReady" in build_portable_html
            and "__gaiusSingleplayerWorkerUrl" in build_portable_html
            and "__gaiusSingleplayerServerGzipUrl" in build_portable_html
            and "serverScriptGzipUrl" in browser_singleplayer_client
            and "serverScriptGzipUrl" in server_worker_bootstrap
            and "URL.createObjectURL" in server_worker_bootstrap
            and "window.__gaiusClassesUrl" in postprocess_index_html
            and "window.__gaiusHotpathWasmUrl" in postprocess_index_html
            and portable_embeds_gzip(
                PORTABLE_HTML,
                "server",
                Path(str(SERVER_WORKER_JS) + ".gz"),
            ),
        ),
        (
            "Release compresses embedded assets before portable HTML and refreshes its gzip",
            build_release.count("compress-dist.sh") == 2
            and build_release.find("compress-dist.sh")
            < build_release.find("build-portable-html.py")
            < build_release.rfind("compress-dist.sh")
            and "GAIUS_COMPRESS_FILES=Gaius.html" in build_release
            and all(
                gzip_matches(path)
                for path in (
                    DIST / "classes.js",
                    DIST / "index.html",
                    DIST / "singleplayer-server.js",
                    DIST / "singleplayer-server-worker.js",
                    DIST / "gaius-hotpath.wasm",
                    PORTABLE_HTML,
                )
            ),
        ),
        (
            "Browser smoke reaches PLAY with real chunk data and clean shutdown",
            "new Worker(workerUrl" in singleplayer_worker_smoke
            and "new MessageChannel()" in singleplayer_worker_smoke
            and "PROTOCOL_VERSION = 774" in singleplayer_worker_smoke
            and "protocol.startLogin()" in singleplayer_worker_smoke
            and "encodeClientInformation" in singleplayer_worker_smoke
            and "knownPackRequests" in singleplayer_worker_smoke
            and "loginProfileId" in singleplayer_worker_smoke
            and "Integrated server changed the client profile UUID" in singleplayer_worker_smoke
            and 'message.type === "server-distances-staged"' in singleplayer_worker_smoke
            and 'message.detail === "1/1->7/3"' in singleplayer_worker_smoke
            and 'message.type === "server-distances"' in singleplayer_worker_smoke
            and 'message.detail === "7/3"' in singleplayer_worker_smoke
            and "encodePacket(10, encodeFloat(10)" in singleplayer_worker_smoke
            and "playLoginPackets" in singleplayer_worker_smoke
            and "chunkPackets" in singleplayer_worker_smoke
            and "playReady" in singleplayer_worker_smoke
            and "closeTransport" in singleplayer_worker_smoke
            and 'worker.postMessage({type: "stop"})' in singleplayer_worker_smoke
            and "removeSmokeWorld(worldId)" in singleplayer_worker_smoke
            and "Gaius singleplayer Worker smoke passed" in singleplayer_worker_smoke,
        ),
        (
            "Node runtime smoke reaches PLAY with real chunk data and clean shutdown",
            "createProtocolClient" in singleplayer_worker_runtime_smoke
            and "compressionThreshold" in singleplayer_worker_runtime_smoke
            and "configurationPackets" in singleplayer_worker_runtime_smoke
            and "encodeClientInformation" in singleplayer_worker_runtime_smoke
            and "knownPackRequests" in singleplayer_worker_runtime_smoke
            and "loginProfileId" in singleplayer_worker_runtime_smoke
            and "Integrated server changed the client profile UUID" in singleplayer_worker_runtime_smoke
            and "playLoginPackets" in singleplayer_worker_runtime_smoke
            and "chunkPackets" in singleplayer_worker_runtime_smoke
            and 'message.type === "server-distances-staged"' in singleplayer_worker_runtime_smoke
            and 'expectedStagedDistances = "1/1->7/3"' in singleplayer_worker_runtime_smoke
            and 'message.type === "server-distances-ramping"' in singleplayer_worker_runtime_smoke
            and "expectedDistanceRamp" in singleplayer_worker_runtime_smoke
            and "distance-ramp-mismatch" in singleplayer_worker_runtime_smoke
            and "sendPlayerAction(0)" in singleplayer_worker_runtime_smoke
            and "sendPlayerAction(2)" in singleplayer_worker_runtime_smoke
            and "startConfirmedBlockAction" in singleplayer_worker_runtime_smoke
            and "createBlockCandidates" in singleplayer_worker_runtime_smoke
            and 'packetId.value === 4' in singleplayer_worker_runtime_smoke
            and 'packetId.value === 8' in singleplayer_worker_runtime_smoke
            and "targetAirUpdates < 1" in singleplayer_worker_runtime_smoke
            and "completeBlockAction()" in singleplayer_worker_runtime_smoke
            and "encodePacket(10, encodeFloat(10)" in singleplayer_worker_runtime_smoke
            and "playReady" in singleplayer_worker_runtime_smoke
            and "closeTransport" in singleplayer_worker_runtime_smoke
            and 'worker.postMessage({type: "stop"})' in singleplayer_worker_runtime_smoke
            and 'type: "node-xhr-request"' in singleplayer_worker_runtime_smoke,
        ),
        (
            "Singleplayer launcher enters the current Worker-enabled client",
            'new URL("../dist/index.html", location.href)' in singleplayer_launcher
            and "target.search = location.search" in singleplayer_launcher
            and "location.replace(target.href)" in singleplayer_launcher
            and "eag26-single" not in singleplayer_launcher,
        ),
        (
            "Shared server hot paths avoid Window-only globals inside the Worker",
            "window." not in browser_bit_storage
            and "window." not in browser_http_proxy
            and "window." not in browser_crypto
            and "globalThis.__gaiusWasmHotpath" in browser_bit_storage
            and "globalThis.__gaiusBridgeUrl" in browser_http_proxy
            and "globalThis.__gaiusMinecraftCounters" in browser_crypto
            and "var counters = globalThis.__gaiusMinecraftCounters" in text
            and "var counters = window.__gaiusMinecraftCounters" not in text
            and "var events = window.__gaiusMinecraftEvents" not in text,
        ),
        (
            "Server Worker build is isolated and excludes desktop client authentication libraries",
            "GAIUS_MAVEN_DIRECTORY" in generate_pom
            and "GAIUS_RESOURCE_DIRECTORY" in generate_pom
            and "GAIUS_EXCLUDED_LIBRARY_PREFIXES" in generate_pom
            and "BrowserIntegratedServerMain" in build_server_worker
            and "singleplayer-server.js" in build_server_worker
            and "singleplayer-server-worker.js" in build_server_worker
            and 'index($0, "data/") == 1' in build_server_worker
            and '$0 == "assets/minecraft/lang/en_us.json"' in build_server_worker
            and '$0 == "assets/minecraft/lang/deprecated.json"' in build_server_worker
            and 'rm -rf "$server_resources" "$server_target/maven"' in build_server_worker
            and "GAIUS_RESOURCE_DIRECTORY" in build_server_worker
            and "com/microsoft/azure/msal4j/" in build_server_worker
            and "com/azure/azure-json/" in build_server_worker
            and 'GAIUS_SERVER_TEA_OPTIMIZATION_LEVEL:-ADVANCED' in build_server_worker
            and "GAIUS_COMPRESS_FILES" in build_server_worker
            and "GAIUS_SKIP_SERVER_WORKER" in build_release
            and "build-teavm-server-worker.sh" in build_release
            and "GAIUS_COMPRESS_FILES" in compress_dist
            and '"singleplayer-server.js"' in serve_dist
            and '"singleplayer-server-worker.js"' in serve_dist,
        ),
        (
            "Dedicated browser server removes unsupported desktop service branches",
            "patchServerMainBrowser" in client_patcher
            and '"createOffline"' in client_patcher
            and "Server offline authentication-service patch point was not found" in client_patcher
            and "patchDedicatedServerBrowser" in client_patcher
            and "patchDedicatedSettingsBrowser" in client_patcher
            and "patchServerTextFilterBrowser" in client_patcher
            and '"managementServerEnabled"' in client_patcher
            and '"enableQuery"' in client_patcher
            and '"enableRcon"' in client_patcher
            and '"enableJmxMonitoring"' in client_patcher
            and '"getMaxTickLength"' in client_patcher
            and "Minecraft singleplayer worker stop hook point was not found" in client_patcher
            and "registerServer" in client_patcher,
        ),
        (
            "Singleplayer server reports startup failures and embeds required language data",
            "rethrowStartupFailure" in client_patcher
            and "rethrowStartupFailure" in browser_integrated_server_main
            and "describeWithStack" in browser_integrated_server_main
            and "exception.$jsException" in browser_integrated_server_main
            and "Error.stackTraceLimit = 100" in server_worker_bootstrap
            and "requiredResources" in minecraft_resource_supplier
            and ".distinct()" in minecraft_resource_supplier,
        ),
        (
            "Browser scheduled executor honors delays instead of retry-spinning",
            "unit.toMillis" in scheduled_thread_pool_executor
            and "Platform.schedule" in scheduled_thread_pool_executor
            and "scheduleChunk" in scheduled_thread_pool_executor
            and "remainingMillis" in scheduled_thread_pool_executor,
        ),
        (
            "Singleplayer local server bypasses DNS and guards partial-startup saves",
            "NoopAddressResolverGroup" in client_patcher
            and 'method.name.equals("saveAllChunks")' in client_patcher
            and 'method.name.equals("overworld")' not in client_patcher
            and '"overworld"' in client_patcher
            and "patchedSaveBeforeWorldInitialization" in client_patcher,
        ),
        (
            "Bridge smoke verifies flow control and unmodified Minecraft PLAY with chunks",
            'const upload = patternedBuffer(4 * 1024 * 1024' in bridge_smoke
            and 'const floodLength = 8 * 1024 * 1024' in bridge_smoke
            and '{ type: "flow", paused: true }' in bridge_smoke
            and '{ type: "flow", paused: false }' in bridge_smoke
            and "Flow pause leaked" in bridge_smoke
            and "Resumed server burst hash mismatch" in bridge_smoke
            and "testMinecraftLogin" in bridge_smoke
            and "compressionThreshold" in bridge_smoke
            and "inflateSync" in bridge_smoke
            and "sendMinecraftPacket(3, Buffer.alloc(0))" in bridge_smoke
            and "encodeClientInformation" in bridge_smoke
            and "knownPackRequests" in bridge_smoke
            and "configurationFinished" in bridge_smoke
            and "playLoginPackets" in bridge_smoke
            and "chunkPackets" in bridge_smoke
            and 'encodeVarInt(774)' in bridge_smoke
            and '"Minecraft PLAY login and chunk data"' in bridge_smoke
            and "testLocalTunnelPair" in bridge_smoke
            and "clientToServerBytes" in bridge_smoke
            and "serverToClientBytes" in bridge_smoke,
        ),
        (
            "Online-mode smoke authenticates to an unmodified plugin-free vanilla server",
            "publicEncrypt" in bridge_smoke
            and 'createCipheriv("aes-128-cfb8"' in bridge_smoke
            and 'createDecipheriv("aes-128-cfb8"' in bridge_smoke
            and "minecraftServerHash" in bridge_smoke
            and 'new URL("session/minecraft/join"' in bridge_smoke
            and "RSA_PKCS1_PADDING" in bridge_smoke
            and 'onlineMode: encryptionRequest' in bridge_smoke
            and '"online-mode=true"' in online_mode_server_smoke
            and 'GAIUS_SMOKE_ENFORCE_SECURE_PROFILE' in online_mode_server_smoke
            and '`enforce-secure-profile=${enforceSecureProfile}`' in online_mode_server_smoke
            and "minecraft.api.session.host" in online_mode_server_smoke
            and 'pluginsInstalled: false' in online_mode_server_smoke
            and 'unmodifiedVanillaServer: true' in online_mode_server_smoke
            and 'requestUrl.pathname === "/session/minecraft/hasJoined"' in online_mode_server_smoke
            and 'login.chunkPackets < 1' in online_mode_server_smoke,
        ),
        (
            "Browser HTTP proxy rejects oversized request and response bodies",
            "ProxyRequestSizeError" in bridge_main
            and "declaredRequestLength" in bridge_main
            and "declaredLength > maximumBytes" in bridge_main
            and "response.writeHead(413" in bridge_main,
        ),
        (
            "Minecraft resource packs and authlib use the browser HTTP bridge",
            "proxyResourcePack" in browser_http_proxy
            and "proxyAuthentication" in browser_http_proxy
            and "proxyTexture" in browser_http_proxy
            and "proxyRealms" in browser_http_proxy
            and "addRealmsCookie" in browser_http_proxy
            and "bridge.pathname = '/proxy/' + String(kind)" in browser_http_proxy
            and '"proxyResourcePack"' in client_patcher
            and "HttpUtil browser download patch points were not found" in client_patcher
            and "patchSkinTextureDownloader" in client_patcher
            and "SkinTextureDownloader browser Java Proxy patch point was not found" in client_patcher
            and '"proxyAuthentication"' in authlib_patcher
            and '"(Ljava/net/Proxy;)Ljava/net/URLConnection;"' in authlib_patcher
            and 'call.desc = "()Ljava/net/URLConnection;"' in authlib_patcher
            and "Opcodes.POP" in authlib_patcher
            and '"proxyRealms"' in client_patcher
            and '"addRealmsCookie"' in client_patcher
            and "testBrowserHttpProxy()" in platform_smoke,
        ),
        (
            "Browser TCP and HTTP bridge URLs bracket IPv6 authority hosts",
            "function authorityHost(value)" in netty_browser_channel
            and "host.includes(':')" in netty_browser_channel
            and "host.startsWith('[')" in netty_browser_channel
            and "const rawHost = String(" in browser_http_proxy
            and "rawHost.includes(':')" in browser_http_proxy
            and "rawHost.startsWith('[')" in browser_http_proxy,
        ),
        (
            "Browser bridge accepts a null WebSocket send callback as success",
            "if (error) {" in bridge_main
            and "error && webSocket.readyState === WebSocket.OPEN" in bridge_main
            and "error !== undefined" not in bridge_main,
        ),
        (
            "Minecraft patcher routes multiplayer DNS and TCP through browser bridge",
            "patchBrowserServerAddressResolver" in client_patcher
            and "patchBrowserServerRedirectHandler" in client_patcher
            and "InetSocketAddress" in client_patcher
            and "createUnresolved" in client_patcher
            and "patchResolvedServerAddressBrowserUnresolved" in client_patcher
            and "getHostString" in client_patcher
            and "patchConnectionBrowserWebSocket" in client_patcher
            and "io/netty/channel/browser/BrowserWebSocketChannel" in client_patcher
            and "disableResolver" in client_patcher
            and "pumpAll" in client_patcher,
        ),
        (
            "Index postprocess removes generated launcher --disableMultiplayer flag",
            '"--disableMultiplayer"' in postprocess_index_html
            and "re.sub" in postprocess_index_html
            and r'\n\s*"--disableMultiplayer",' in postprocess_index_html,
        ),
        (
            "Browser launcher resolves online profiles without leaking access tokens",
            "async function buildGaiusSessionArgs()" in postprocess_index_html
            and "async function loadGaiusMinecraftProfile(accessToken)" in postprocess_index_html
            and '"https://api.minecraftservices.com/minecraft/profile"' in postprocess_index_html
            and '"authorization": "Bearer " + accessToken' in postprocess_index_html
            and 'window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs()' in postprocess_index_html
            and 'window.__gaiusDefaultArgsPromise.catch(() => {})' in postprocess_index_html
            and 'await window.__gaiusDefaultArgsPromise' in postprocess_index_html
            and 'sessionStorage.getItem("gaius.session")' in postprocess_index_html
            and "window.__gaiusConfigureSession" in postprocess_index_html
            and 'args.push("--offlineDeveloperMode")' in postprocess_index_html
            and 'scrubbed.searchParams.delete("accessToken")' in postprocess_index_html
            and "<redacted>" in postprocess_index_html
            and "async function buildGaiusSessionArgs()" in index_html
            and "async function loadGaiusMinecraftProfile(accessToken)" in index_html
            and 'window.__gaiusDefaultArgsPromise.catch(() => {})' in index_html
            and 'await window.__gaiusDefaultArgsPromise' in index_html
            and '"<redacted>"' in index_html
            and 'window.__gaiusSessionMode = online ? "online" : "offline"' in index_html
            and 'window.__gaiusDisplayArgs.join(" ")' in index_html,
        ),
        (
            "Session launcher smoke covers offline and token-only online identities",
            "profileResolution: true" in session_launcher_smoke
            and "completeSessionBypassesFetch: true" in session_launcher_smoke
            and "invalidProfileRejected: true" in session_launcher_smoke
            and "accessTokenScrubbed: true" in session_launcher_smoke
            and 'proxy.pathname, "/proxy/auth"' in session_launcher_smoke
            and '"https://api.minecraftservices.com/minecraft/profile"' in session_launcher_smoke,
        ),
        (
            "Browser launcher supports direct quick-play multiplayer URLs",
            'const quickPlayServer = String(urlParams.get("server")' in postprocess_index_html
            and "quickPlayServer.length > 512" in postprocess_index_html
            and 'args.push("--quickPlayMultiplayer", quickPlayServer)' in postprocess_index_html
            and 'const quickPlayServer = String(urlParams.get("server")' in index_html
            and 'args.push("--quickPlayMultiplayer", quickPlayServer)' in index_html,
        ),
        (
            "BrowserOpenGL avoids unconditional CPU-side buffer shadow copies",
            "shadowRequiredBuffers" in text
            and "shouldShadowBufferTarget" in text
            and "shadowBufferDataForTarget" in text
            and "shadowBufferSubDataForTarget" in text
            and "initializeShadowDecisionCache" in text
            and "const refs=this.misalignedBufferRefs" in text
            and "return refs ? ((refs.get(id)||0)>0) : this.bufferNeedsArrayShadow(id)"
            in text
            and "bufferShadowDecisionCache" not in text
            and "bufferShadowPolicyVersion" not in text
            and "bufferShadowSkippedUnneeded" in text
            and "bufferShadowSkippedUnneededCount" in text
            and "bufferShadowRequiredMarkCount" in text
            and "this.shadowRequiredBuffers.has(id)" in text
            and "this.vaoEmu.forEach(function(v)" in text
            and "v.misalignedAttribBuffers.set(i,b)" in text
            and "s.misalignedBufferRefs.set(b,(n+1)|0)" in text
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
            and "putFastVertexBytes(" in browser_memory
            and "@JSByRef byte[] bytes" in browser_memory
            and "new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength)" in browser_memory
            and "putRgba(bytes, base + 12, color)" in browser_memory
            and "putPackedUv(bytes, lightStart, lightCoords)" in browser_memory
            and "private static byte normalIntValue(float value)" in browser_memory
            and "patchBufferBuilderBrowserFastVertex" in client_patcher
            and "patchByteBufferBuilderBrowserReserve" in client_patcher
            and "patchCompiledSectionMeshBrowserVertexBufferReuse" in client_patcher
            and 'find(\n                node,\n                "uploadMeshLayer"' in client_patcher
            and '"dev/gaius/browser/BrowserMeshUpload"' in client_patcher
            and "vertexBufferCalls != 4 || returns != 1" in client_patcher
            and "private static ByteBuffer activeVertexBuffer" in browser_mesh_upload
            and "activeVertexBuffer = mesh.vertexBuffer()" in browser_mesh_upload
            and "activeVertexBuffer = null" in browser_mesh_upload
            and 'find(node, "reserve", "(I)J")' in client_patcher
            and '"java/lang/Math".equals(call.owner)' in client_patcher
            and 'method.instructions.set(call, new InsnNode(Opcodes.LADD))' in client_patcher
            and "replacements != 2" in client_patcher
            and "org/lwjgl/system/BrowserMemory" in client_patcher
            and "(JFFFIFFIIFFFZ)V" in client_patcher,
        ),
        (
            "Browser Math.fma uses a native numeric implementation",
            "public static native float fma(float left, float right, float addend);" in modern_runtime_support
            and "public static native double fma(double left, double right, double addend);" in modern_runtime_support
            and "return Math.fround(left * right + addend);" in modern_runtime_support
            and "return left * right + addend;" in modern_runtime_support
            and "testFloatingPointFma" in platform_smoke
            and "Browser float fma lost non-finite semantics" in platform_smoke
            and "Browser float fma lost negative zero" in platform_smoke,
        ),
        (
            "JOML browser matrices use the direct multiply-add fallback",
            'private static final String CLASS_ENTRY = "org/joml/Math.class";' in joml_math_patcher
            and 'find(classNode, "fma", "(FFF)F")' in joml_math_patcher
            and 'find(classNode, "fma", "(DDD)D")' in joml_math_patcher
            and "Opcodes.FMUL" in joml_math_patcher
            and "Opcodes.FADD" in joml_math_patcher
            and "Opcodes.DMUL" in joml_math_patcher
            and "Opcodes.DADD" in joml_math_patcher
            and "dev.gaius.tools.JomlMathPatcher" in build_overlays
            and "org/joml/Math.class" in build_overlays
            and "JOML browser fma did not use direct multiply-add fallback" in platform_smoke,
        ),
        (
            "BrowserMemory caches the active virtual memory region",
            "private static int cachedRegionId" in browser_memory
            and "private static Region cachedRegion" in browser_memory
            and "id == cachedRegionId && cachedRegion != null" in browser_memory
            and "cachedRegionId = id" in browser_memory
            and "cachedRegion = region" in browser_memory
            and "if (id == cachedRegionId)" in browser_memory,
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
            "public static void swapBuffers(long window)" in glfw_text
            and "private static native void swapBuffersJs()" in glfw_text
            and "public static native void swapBuffers(long window)" not in glfw_text
            and "gameFps" in glfw_text
            and "gameFrames" in glfw_text
            and "gameLastSampleAt" in glfw_text,
        ),
        (
            "BrowserGlfw reserves the final timer millisecond for deadline precision",
            "public static void waitEventsTimeout(double timeout)" in glfw_text
            and "sleepForBrowserMillis" in glfw_text
            and "Thread.sleep(millis)" in glfw_text
            and "Math.floor(timeout * 1000.0)" in glfw_text
            and "millis <= 1L" in glfw_text
            and "spinForBrowserSeconds" not in glfw_text
            and "GLFW.glfwWaitEventsTimeout(0.0001)" in platform_smoke
            and "Thread.yield()" in glfw_text
            and "Math.min(7L, millis - 1L)" in glfw_text,
        ),
        (
            "Browser frame pacing avoids rejected cross-class and conditional-yield experiments",
            "patchRenderSystemBrowserFramePacing" not in client_patcher
            and "patchRenderSystemBrowserSingleWait" not in client_patcher
            and "frameYieldHooked" not in client_patcher
            and "yieldAfterFrame" not in client_patcher
            and "frameAlreadyYielded" not in glfw_text
            and "waitForBrowserFrame" not in glfw_text,
        ),
        (
            "Browser frame pacing compensates sub-frame timer overshoot",
            "patchRenderSystemBrowserDeadlineCompensation" in client_patcher
            and 'find(node, "limitDisplayFPS", "(I)V")' in client_patcher
            and 'helperName = "browserCompensateFrameTime"' in client_patcher
            and 'field.name.equals("lastDrawTime")' in client_patcher
            and 'helper.instructions.add(new InsnNode(Opcodes.DDIV))' in client_patcher
            and 'helper.instructions.add(new JumpInsnNode(Opcodes.IFGE, returnNow))' in client_patcher,
        ),
        (
            "Browser section rendering uses direct coordinate arithmetic",
            "patchRenderSectionRegionBrowserDirectSectionCoordinates" in client_patcher
            and "patchSectionCompilerBrowserDirectRelativeCoordinates" in client_patcher
            and 'replaceSectionCoordinateCalls(\n                    method, "blockToSectionCoord", Opcodes.ICONST_4, Opcodes.ISHR)' in client_patcher
            and 'compile, "sectionRelative", Opcodes.BIPUSH, Opcodes.IAND' in client_patcher
            and "methodReplacements != 3" in client_patcher
            and "replacements != 9" in client_patcher,
        ),
        (
            "BrowserGlfw primes cursor callbacks so the first menu click is not swallowed",
            "callback.invoke(WINDOW, cursorX, cursorY)" in glfw_text
            and "updateCursorFromMouseEvent" in glfw_text
            and "pushMouseMove([4,0,0,0,0,p[0],p[1]])" in glfw_text,
        ),
        (
            "BrowserGlfw records input callback telemetry and carries button coordinates",
            "__gaiusInputStats" in glfw_text
            and "reportCallback(\"mouseButton\", callback != null)" in glfw_text
            and "reportInputEvent(type, a, b, c, x, y, mouseButtonCallback != null)" in glfw_text
            and "reportMouseHandlerEntry" in glfw_text
            and "reportMouseHandlerDispatch" in glfw_text
            and "reportMouseClickedResult" in glfw_text
            and "const glfwMouseButton = button => button === 2 ? 1 : (button === 1 ? 2 : button)" in glfw_text
            and "const button = glfwMouseButton(e.button)" in glfw_text
            and "pushEvent([3,button,1,mods(e),0,p[0],p[1]])" in glfw_text
            and "pushEvent([3,button,0,mods(e),0,p[0],p[1]])" in glfw_text,
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
            "Minecraft patcher dispatches browser input callbacks synchronously",
            "patchBrowserInputCallbacks" in client_patcher
            and "patchBrowserMouseHandler" in client_patcher
            and "patchBrowserKeyboardHandler" in client_patcher
            and 'find(node, "lambda$setup$3", "(JDD)V")' in client_patcher
            and 'find(node, "lambda$setup$5", "(JIII)V")' in client_patcher
            and 'find(node, "lambda$setup$7", "(JDD)V")' in client_patcher
            and 'find(node, "lambda$setup$6", "(JIIII)V")' in client_patcher
            and 'find(node, "lambda$setup$8", "(JII)V")' in client_patcher
            and '"onButton"' in client_patcher
            and '"keyPress"' in client_patcher
            and '"charTyped"' in client_patcher,
        ),
        (
            "Minecraft patcher reports browser MouseHandler button dispatch path",
            "reportMouseHandlerEntry" in client_patcher
            and "reportMouseHandlerDispatch" in client_patcher
            and "reportMouseClickedResult" in client_patcher
            and "MouseHandler.onButton browser telemetry/overlay hook points" in client_patcher,
        ),
        (
            "Minecraft patcher lets visible browser menus receive clicks through fading LoadingOverlay",
            "MouseHandler overlay gate shape changed" in client_patcher
            and "net/minecraft/client/gui/screens/LoadingOverlay" in client_patcher
            and "INSTANCEOF" in client_patcher
            and '"screen"' in client_patcher,
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
            and "patchSectionRenderDispatcherBrowserExecutor" in client_patcher
            and "BrowserRenderScheduler" in client_patcher
            and "BROWSER_SECTION_SCHEDULE_BUDGET = 4" in client_patcher
            and "BROWSER_SECTION_UPLOAD_BUDGET = 8" in client_patcher
            and "BROWSER_SECTION_CLOSE_BUDGET = 16" in client_patcher
            and "Window.requestAnimationFrame" in browser_render_scheduler
            and "Platform.schedule(BrowserRenderScheduler::runAfterPaint, 0)" in browser_render_scheduler
            and "MAX_TASKS_PER_FRAME = 4" in browser_render_scheduler
            and "FRAME_WORK_BUDGET_NANOS = 3_000_000L" in browser_render_scheduler
            and "QUEUE.pollFirst()" in browser_render_scheduler
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
            "Minecraft patcher preserves user-selected client and server distances",
            "patchIntegratedServerBrowserDistances" not in client_patcher
            and "patchPlayerListBrowserDistances" not in client_patcher
            and "fixedBrowserDistance" not in client_patcher
            and "browserDistanceConstant" not in client_patcher
            and "patchOptionsBrowserLowSimulationDistance" in client_patcher
            and "Options simulation-distance range patch point was not found" in client_patcher
            and "Options save browser distance sync point was not found" in client_patcher
            and '"syncDistances"' in client_patcher
            and '"(Lnet/minecraft/client/Minecraft;)V"' in client_patcher,
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
            "Minecraft patcher avoids full chunk generation while choosing browser spawn",
            "replaceInitialSpawnForBrowser" in client_patcher
            and "server.browserFastInitialSpawn" in client_patcher
            and "Climate$Sampler" in initial_spawn_section
            and "findSpawnPosition" in initial_spawn_section
            and "getBaseHeight" in initial_spawn_section
            and "MOTION_BLOCKING_NO_LEAVES" in initial_spawn_section
            and "getMiddleBlockX" in initial_spawn_section
            and "getMiddleBlockZ" in initial_spawn_section
            and "PlayerSpawnFinder" not in initial_spawn_section
            and "getHeightmapPos" not in initial_spawn_section
            and "BlockPos.ZERO" not in initial_spawn_section,
        ),
        (
            "Minecraft patcher skips synchronous stronghold biome relocation in browser",
            "patchChunkGeneratorStructureStateBrowserFastRings" in client_patcher
            and '"lambda$generateRingPositions$5"' in client_patcher
            and '"net/minecraft/world/level/ChunkPos"' in client_patcher
            and '"(II)V"' in client_patcher,
        ),
        (
            "Server Worker yields outside the synchronous worldgen tick graph",
            "YIELD_CHECKS_PER_TICK = 30" in browser_worldgen_scheduler
            and browser_worldgen_scheduler.count("Thread.yield()") == 1
            and "runServer before processPacketsAndTick" in browser_worldgen_scheduler
            and "worldgen descendants" in browser_worldgen_scheduler
            and "public static void checkpoint()" in browser_worldgen_scheduler
            and "public static void pulse()" in browser_worldgen_scheduler,
        ),
        (
            "Browser ImprovedNoise hot path preserves the full vanilla interpolation",
            "patchImprovedNoiseBrowserHotPath" in client_patcher
            and '"noise"' in client_patcher
            and '"sampleAndLerp"' in client_patcher
            and '"([BDDDDDDDD)D"' in client_patcher
            and '"([BIIIDDDD)D"' in client_patcher
            and '"dev/gaius/browser/BrowserImprovedNoise"' in client_patcher
            and "public static native double noise(" in browser_improved_noise
            and "public static native double sampleAndLerp(" in browser_improved_noise
            and browser_improved_noise.count("@JSByRef byte[] permutation") == 2
            and "@JSBody" in browser_improved_noise
            and "__gaiusImprovedNoiseGradients" not in browser_improved_noise
            and "const grad =" not in browser_improved_noise
            and browser_improved_noise.count("const h000 =") == 2
            and browser_improved_noise.count("const v111 =") == 2
            and "localX * localX * localX" in browser_improved_noise
            and "fadeY * fadeY * fadeY" in browser_improved_noise
            and "localZ * localZ * localZ" in browser_improved_noise
            and "testImprovedNoiseHotPath" in platform_smoke
            and "0x3fdabaf51dc7af1eL" in platform_smoke
            and "testBitStorageHotPath" in platform_smoke
            and "Browser bit-storage unpack did not update the Java array" in platform_smoke,
        ),
        (
            "Generated Server Worker passes ImprovedNoise permutations by reference",
            file_matches(
                SERVER_WORKER_JS,
                rb"ImprovedNoise_noise\s*=.{0,512}BrowserImprovedNoise_noise\$js_body.{0,256}\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"ImprovedNoise_noise\s*=.{0,512}otji_JSWrapper_javaToJs",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserImprovedNoise_noise\$js_body.{0,2048}const h000 =",
            )
            and not file_matches(SERVER_WORKER_JS, rb"const grad = \(hash, gx, gy, gz\)")
            and not file_matches(SERVER_WORKER_JS, rb"__gaiusImprovedNoiseGradients"),
        ),
        (
            "Generated Server Worker passes scalar bit storage arrays by reference",
            file_matches(
                SERVER_WORKER_JS,
                rb"SimpleBitStorage_get\s*=.{0,1024}"
                rb"BrowserBitStorage_get\$js_body.{0,256}\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"SimpleBitStorage_get\s*=.{0,1024}"
                rb"BrowserBitStorage_get\$js_body.{0,256}JSWrapper_javaToJs",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"SimpleBitStorage_getAndSet\s*=.{0,1024}"
                rb"BrowserBitStorage_getAndSet\$js_body.{0,256}\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"SimpleBitStorage_getAndSet\s*=.{0,1024}"
                rb"BrowserBitStorage_getAndSet\$js_body.{0,256}JSWrapper_javaToJs",
            ),
        ),
        (
            "Generated Server Worker keeps scalar bit storage off BigInt on typed arrays",
            file_matches(
                SERVER_WORKER_JS,
                rb"BrowserBitStorage_get\$js_body.{0,8192}"
                rb"source\.__gaiusBitStorageWords.{0,2048}"
                rb"new Uint32Array\(source\.buffer, source\.byteOffset, source\.length \* 2\).{0,4096}"
                rb"if \(words\).{0,4096}return value & numericMask \| 0;.{0,256}"
                rb"const shift = BigInt\(offset\)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserBitStorage_getAndSet\$js_body.{0,12288}"
                rb"source\.__gaiusBitStorageWords.{0,2048}"
                rb"new Uint32Array\(source\.buffer, source\.byteOffset, source\.length \* 2\).{0,8192}"
                rb"return previous & numericMask \| 0;.{0,256}"
                rb"const shift = BigInt\(offset\)",
            ),
        ),
        (
            "Generated release client keeps scalar bit storage off BigInt on typed arrays",
            file_matches(
                DIST / "classes.js",
                rb"__gaiusBitStorageWords.{0,1024}"
                rb"new Uint32Array\(source\.buffer,\s*source\.byteOffset,\s*source\.length\s*\*\s*2\).{0,4096}"
                rb"return value\s*&\s*numericMask\s*\|\s*0;.{0,256}"
                rb"const shift\s*=\s*BigInt\(offset\)",
            )
            and file_matches(
                DIST / "classes.js",
                rb"__gaiusBitStorageWords.{0,2048}"
                rb"new Uint32Array\(source\.buffer,\s*source\.byteOffset,\s*source\.length\s*\*\s*2\).{0,4096}"
                rb"numericReplacement.{0,4096}"
                rb"return previous\s*&\s*numericMask\s*\|\s*0;.{0,256}"
                rb"const shift\s*=\s*BigInt\(offset\)",
            ),
        ),
        (
            "Generated Server Worker keeps Perlin octave sampling allocation-free",
            file_matches(
                SERVER_WORKER_JS,
                rb"PerlinNoise_getValue\s*=(?:(?!\n};).)*browserAmplitudes\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"PerlinNoise_getValue\s*=(?:(?!\n};).)*(?:getDouble|\$rt_suspending|\$rt_nativeThread)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"PerlinNoise_wrap\s*=.{0,256}BrowserPerlinNoise_wrap\$js_body",
            ),
        ),
        (
            "Generated Server Worker caches immutable climate bounds and targets",
            file_matches(
                SERVER_WORKER_JS,
                rb"BrowserClimate_distance\$js_body.{0,4096}"
                rb"let value0 = values\.__gaiusClimateValue0.{0,1024}"
                rb"values\.__gaiusClimateValue6 = Number\(values\[6\]\).{0,2048}"
                rb"value = values\.__gaiusClimateValue6",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"Climate\$RTree\$Node_distance\s*=.{0,512}"
                rb"browserBounds.{0,128}\.data.{0,128}\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"Climate\$RTree\$Node_distance\s*=(?:(?!\n};).)*otji_JSWrapper_javaToJs",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"BrowserClimate_distance\$js_body.{0,4096}(?:WeakMap|Object\.keys)",
            ),
        ),
        (
            "Generated Server Worker uses direct BigInt packed block coordinates",
            file_matches(
                SERVER_WORKER_JS,
                rb"BlockPos_getX\s*=.{0,512}"
                rb"BigInt\.asIntN\(26, .{0,64} >> BigInt\(38\)\)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BlockPos_getY\s*=.{0,512}BigInt\.asIntN\(12, .{0,64}\)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BlockPos_getZ\s*=.{0,512}"
                rb"BigInt\.asIntN\(26, .{0,64} >> BigInt\(12\)\)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BlockPos_asLong[0-9]*\s*=.{0,768}BrowserBlockPos_asLong\$js_body",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserBlockPos_asLong\$js_body.{0,512}"
                rb"BigInt\.asUintN\(26, BigInt\([^)]{1,32}\)\).{0,512}"
                rb"BigInt\.asIntN\(64, packed\)",
            ),
        ),
        (
            "Generated Server Worker computes biome zoom corners in one BigInt hot path",
            file_matches(
                SERVER_WORKER_JS,
                rb"BiomeManager_getBiome\s*=.{0,4096}BrowserBiomeManager_nearestCorner",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserBiomeManager_nearestCorner\$js_body.{0,2048}"
                rb"BigInt\(\"6364136223846793005\"\).{0,1024}"
                rb"BigInt\(\"1442695040888963407\"\)",
            ),
        ),
        (
            "Generated Server Worker batches warmed aquifer center selection",
            file_matches(
                SERVER_WORKER_JS,
                rb"Aquifer\$NoiseBasedAquifer_computeSubstance\s*=.{0,16384}"
                rb"BrowserAquifer_selectNearestCached",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserAquifer_selectNearestCached\$js_body.{0,8192}"
                rb"__gaiusAquiferDecodedLocations.{0,2048}"
                rb"new WeakMap\(\).{0,4096}"
                rb"BigInt\(\"9223372036854775807\"\).{0,4096}"
                rb"Math\.imul\(dx, dx\)",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"Aquifer\$NoiseBasedAquifer_computeSubstance\s*=.{0,16384}"
                rb"BrowserAquifer_selectNearestCached\$js_body.{0,512}\.data",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"Aquifer\$NoiseBasedAquifer_computeSubstance\s*=.{0,16384}"
                rb"BrowserAquifer_selectNearestCached\$js_body.{0,512}JSWrapper_javaToJs",
            ),
        ),
        (
            "Generated Server Worker blends packed structure terrain without Java iterators",
            file_matches(
                SERVER_WORKER_JS,
                rb"Beardifier_compute\s*=.{0,4096}BrowserBeardifier_compute",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"BrowserBeardifier_compute\$js_body.{0,12288}"
                rb"__gaiusBeardifierMath.{0,4096}"
                rb"new BigInt64Array\(buffer\).{0,4096}"
                rb"BigInt\(\"6910469410427058090\"\).{0,8192}"
                rb"Math\.imul\(kernelZ, 576\)",
            ),
        ),
        (
            "Generated Server Worker interpolator updates use direct lerp arithmetic",
            all(
                not file_matches(
                    SERVER_WORKER_JS,
                    rb"NoiseChunk_" + method
                    + rb"\s*=(?:(?!\n};).)*nmu_Mth_lerp",
                )
                for method in (b"updateForY", b"updateForX", b"updateForZ")
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"NoiseChunk_updateForZ\s*=(?:(?!\n};).)*"
                rb"\.\$value[0-9]*\s*=\s*[^;\n]{0,200}"
                rb"\+[^;\n]{0,100}\*[^;\n]{0,200};",
            ),
        ),
        (
            "Generated Server Worker keeps Perlin wrap longs off the normal path",
            file_matches(
                SERVER_WORKER_JS,
                rb"BrowserPerlinNoise_wrap\$js_body[^=]*=.{0,2048}"
                rb"if \(!Number\.isFinite\(.{0,256}return [^;]+;.{0,512}"
                rb"rounded = Math\.floor\(.{0,1024}"
                rb"BigInt\(\"9223372036854775807\"\).{0,1024}"
                rb"return [^;]+ - rounded \* period;",
            ),
        ),
        (
            "Generated Server Worker reuses ProtoChunk heightmap arrays",
            file_matches(
                SERVER_WORKER_JS,
                rb"ProtoChunk_setBlockState\s*=(?:(?!\n};).)*"
                rb"browserHeightmapStatus(?:(?!\n};).)*browserHeightmaps",
            )
            and not file_matches(
                SERVER_WORKER_JS,
                rb"ProtoChunk_setBlockState\s*=(?:(?!\n};).)*GenericEnumSet\$1_next",
            ),
        ),
        (
            "Generated Server Worker uses direct ProtoChunk section access",
            all(
                file_matches(
                    SERVER_WORKER_JS,
                    rb"ProtoChunk_" + method
                    + rb"\s*=(?:(?!\n};).)*" + field,
                )
                for method in (b"getBlockState", b"getFluidState", b"setBlockState")
                for field in (
                    b"browserMinY",
                    b"browserMaxY",
                    b"browserMinSectionY",
                    b"browserSections",
                )
            )
            and all(
                not file_matches(
                    SERVER_WORKER_JS,
                    rb"ProtoChunk_" + method
                    + rb"\s*=(?:(?!\n};).)*(?:isOutsideBuildHeight|getSectionIndex|ChunkAccess_getSection)",
                )
                for method in (b"getBlockState", b"getFluidState", b"setBlockState")
            ),
        ),
        (
            "Browser configuration retains lighting neighbors but waits only for the center chunk",
            "patchPlayerSpawnFinderBrowser" in client_patcher
            and '"findSpawn"' in client_patcher
            and '"atBottomCenterOf"' in client_patcher
            and '"completedFuture"' in client_patcher
            and "patchPrepareSpawnTaskBrowser" in client_patcher
            and '"lambda$tick$0"' in client_patcher
            and '"addTicketWithRadius"' in client_patcher
            and '"getChunkFuture"' in client_patcher
            and '"net/minecraft/world/level/chunk/status/ChunkStatus"' in client_patcher
            and '"FULL"' in client_patcher,
        ),
        (
            "Worker-local singleplayer bypasses remote keepalive timeouts during chunk generation",
            "public static boolean isWorkerServer()" in browser_integrated_server_main
            and "patchServerCommonPacketListenerBrowserWorker" in client_patcher
            and '"isSingleplayerOwner"' in client_patcher
            and '"dev/gaius/browser/BrowserIntegratedServerMain"' in client_patcher
            and '"isWorkerServer"' in client_patcher,
        ),
        (
            "Minecraft patcher preserves synchronous world generation hook coverage",
            "patchNoiseBasedChunkGeneratorBrowserYield" in client_patcher
            and "cacheNoiseBasedChunkGeneratorDoFillConstants" in client_patcher
            and "patchNoiseChunkBrowserYield" in client_patcher
            and "patchClimateRTreeBrowserYield" in client_patcher
            and "patchLevelChunkSectionBrowserBiomeYield" in client_patcher
            and "patchChunkGenerationTaskBrowserYield" in client_patcher
            and "BrowserWorldgenScheduler" in client_patcher
            and "insertPulseAfterLoopCounter(method, 23, -1)" in client_patcher
            and "insertPulseAfterLoopCounter(method, 9, 1)" in client_patcher
            and "patchSurfaceSystemBrowserYield" in client_patcher
            and "patchChunkGeneratorBrowserYield" in client_patcher
            and "patchWorldCarverBrowserYield" in client_patcher
            and "patchLightEngineBrowserYield" in client_patcher
            and "insertWorldgenPulseOnLoopBackedges" in client_patcher
            and '"pulse"' in client_patcher,
        ),
        (
            "Browser worldgen removes boxed long and iterator work from its hottest loops",
            "patchNoiseChunkContextBrowserIntCounters" in client_patcher
            and "patchNoiseChunkCacheOnceBrowserIntCounters" in client_patcher
            and "convertNoiseChunkCountersToInt" in client_patcher
            and "addNoiseInterpolatorArrayCache" in client_patcher
            and "replaceNoiseInterpolatorUpdate" in client_patcher
            and "replaceNoiseInterpolatorLerpMethod" in client_patcher
            and '{"valueXZ00", "noise000", "noise010"}' in client_patcher
            and '{"valueZ0", "valueXZ00", "valueXZ10"}' in client_patcher
            and 'new String[][] {{"value", "valueZ0", "valueZ1"}}' in client_patcher
            and "patchNoiseInterpolatorBrowserLerp" in client_patcher
            and 'call.owner = "dev/gaius/browser/BrowserNoiseInterpolator"' in client_patcher
            and "public static native double lerp3" in browser_noise_interpolator
            and "testNoiseInterpolatorHotPath" in platform_smoke
            and "patchClimateRTreeNodeBrowserDoubleDistance" in client_patcher
            and '"dev/gaius/browser/BrowserClimate"' in client_patcher
            and '"browserBounds"' in client_patcher
            and '"([D[J)J"' in client_patcher
            and "public static double[] prepareBounds(Climate.Parameter[] parameterSpace)" in browser_climate
            and "@JSByRef double[] bounds" in browser_climate
            and "@JSByRef long[] target" in browser_climate
            and "WeakMap" not in browser_climate
            and "Object.keys" not in browser_climate
            and "values.__gaiusClimateValue0" in browser_climate
            and "values.__gaiusClimateValue6" in browser_climate
            and "value = values.__gaiusClimateValue5" in browser_climate
            and "testClimateDistanceHotPath" in platform_smoke
            and "double[] bounds = BrowserClimate.prepareBounds(parameters)" in platform_smoke
            and "long cached = BrowserClimate.distance(bounds, target)" in platform_smoke
            and "Browser climate distance changed" in platform_smoke,
        ),
        (
            "Browser packed block coordinates avoid TeaVM long helper chains",
            "patchBlockPosBrowserPackedCoordinates" in client_patcher
            and '"dev/gaius/browser/BrowserBlockPos"' in client_patcher
            and '"(J)I"' in client_patcher
            and '"(III)J"' in client_patcher
            and "public static native int getX(long packed)" in browser_block_pos
            and "public static native int getY(long packed)" in browser_block_pos
            and "public static native int getZ(long packed)" in browser_block_pos
            and "public static native long asLong(int x, int y, int z)" in browser_block_pos
            and "BigInt.asIntN(26, packed >> BigInt(38))" in browser_block_pos
            and "BigInt.asUintN(12, BigInt(y))" in browser_block_pos
            and "testBlockPosPackedCoordinates" in platform_smoke
            and "Browser BlockPos packing changed" in platform_smoke
            and "Browser BlockPos unpacking changed" in platform_smoke,
        ),
        (
            "Browser biome zoom preserves vanilla nearest-corner selection without a suspend state machine",
            "patchBiomeManagerBrowserNearestCorner" in client_patcher
            and '"dev/gaius/browser/BrowserBiomeManager"' in client_patcher
            and '"(JIII)I"' in client_patcher
            and "public static native int nearestCorner(" in browser_biome_manager
            and 'BigInt("6364136223846793005")' in browser_biome_manager
            and 'BigInt("1442695040888963407")' in browser_biome_manager
            and "BigInt.asIntN(" in browser_biome_manager
            and "distanceZ * distanceZ" in browser_biome_manager
            and "testBiomeNearestCornerHotPath" in platform_smoke
            and "Browser biome nearest corner changed" in platform_smoke,
        ),
        (
            "Browser aquifer batches warmed nearest-center scans without changing cache misses",
            "patchAquiferBrowserNearestCenters" in client_patcher
            and '"browserNearestResult"' in client_patcher
            and '"dev/gaius/browser/BrowserAquifer"' in client_patcher
            and '"selectNearestCached"' in client_patcher
            and '"([JIIIIIIII[I)Z"' in client_patcher
            and "public static native boolean selectNearestCached(" in browser_aquifer
            and "@JSByRef long[] packed" in browser_aquifer
            and "@JSByRef int[] output" in browser_aquifer
            and 'BigInt("9223372036854775807")' in browser_aquifer
            and "if (position === sentinel) return false" in browser_aquifer
            and "__gaiusAquiferDecodedLocations" in browser_aquifer
            and "new WeakMap()" in browser_aquifer
            and "decoded.ready[index] === 0" in browser_aquifer
            and "Math.imul(dx, dx)" in browser_aquifer
            and "testAquiferNearestCentersHotPath" in platform_smoke
            and "referenceAquiferNearestCenters" in platform_smoke
            and "Browser aquifer nearest centers changed" in platform_smoke
            and "Browser aquifer cache miss did not preserve the vanilla path" in platform_smoke,
        ),
        (
            "Browser beardifier packs structure inputs and preserves allocation-free terrain blending",
            "patchBeardifierBrowserPackedCompute" in client_patcher
            and '"browserPackedPieces"' in client_patcher
            and '"browserPackedJunctions"' in client_patcher
            and '"dev/gaius/browser/BrowserBeardifier"' in client_patcher
            and '"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;III)D"'
                in client_patcher
            and "public static int[] packPieces(" in browser_beardifier
            and "public static int[] packJunctions(" in browser_beardifier
            and "public static native double compute(" in browser_beardifier
            and "__gaiusBeardifierMath" in browser_beardifier
            and 'BigInt("6910469410427058090")' in browser_beardifier
            and "new BigInt64Array(buffer)" in browser_beardifier
            and "Math.imul(kernelZ, 576)" in browser_beardifier
            and "testBeardifierPackedComputeHotPath" in platform_smoke
            and "referenceBeardifier" in platform_smoke
            and "Browser beardifier changed at" in platform_smoke,
        ),
        (
            "Browser ProtoChunk caches heightmap arrays across block writes",
            "patchProtoChunkBrowserHeightmapCache" in client_patcher
            and '"browserMinY"' in client_patcher
            and '"browserMaxY"' in client_patcher
            and '"browserMinSectionY"' in client_patcher
            and '"browserSections"' in client_patcher
            and '"browserHeightmapStatus"' in client_patcher
            and '"browserHeightmaps"' in client_patcher
            and '"dev/gaius/browser/BrowserProtoChunk"' in client_patcher
            and '"prepareHeightmaps"' in client_patcher
            and '"updateHeightmaps"' in client_patcher
            and "private static final Heightmap.Types[] TYPES" in browser_proto_chunk
            and "Heightmap.primeHeightmaps(chunk, missing)" in browser_proto_chunk
            and "heightmaps[i].update(x, y, z, state)" in browser_proto_chunk,
        ),
        (
            "Perlin noise caches amplitudes and uses a non-suspending exact wrap",
            "patchPerlinNoiseBrowserDoubleWrap" in client_patcher
            and '"browserAmplitudes"' in client_patcher
            and '"dev/gaius/browser/BrowserPerlinNoise"' in client_patcher
            and '"copyAmplitudes"' in client_patcher
            and "Opcodes.DALOAD" in client_patcher
            and "public static double[] copyAmplitudes(DoubleList amplitudes)" in browser_perlin_noise
            and "public static native double wrap(double value)" in browser_perlin_noise
            and "Number.isFinite(value)" in browser_perlin_noise
            and "Math.floor(scaled)" in browser_perlin_noise
            and 'BigInt("9223372036854775807")' in browser_perlin_noise
            and 'BigInt("-9223372036854775808")' in browser_perlin_noise
            and "testPerlinNoiseWrapHotPath" in platform_smoke
            and "Browser Perlin wrap changed" in platform_smoke
            and "testPerlinNoiseAmplitudeHotPath" in platform_smoke
            and "0x3fd0353a3c9fb177L" in platform_smoke
            and "0xbf7a07e97df9e5c3L" in platform_smoke,
        ),
        (
            "Client and integrated server pump idle browser packets before each tick",
            client_patcher.count("browserPackets.add(pumpBrowserChannels())") == 2
            and 'method.name.equals("processPacketsAndTick")' in client_patcher
            and "patchedRunServerTickYield" in client_patcher
            and "method.instructions.insertBefore(instruction, browserWorldgenCheckpoint())"
                in client_patcher
            and "method.instructions.insert(browserPackets)" in client_patcher
            and '"io/netty/channel/browser/BrowserWebSocketChannel"' in client_patcher
            and '"pumpAll"' in client_patcher,
        ),
        (
            "Worker singleplayer block breaking follows wall time and validated client STOP",
            "public static int adjustDestroyTicks" in browser_integrated_server_main
            and "elapsedMillis / 50L" in browser_integrated_server_main
            and "public static float completeLocalDestroyProgress" in browser_integrated_server_main
            and "Math.max(progress, 0.7F)" in browser_integrated_server_main
            and "patchServerPlayerGameModeBrowserWorker" in client_patcher
            and '"browserDestroyStartMillis"' in client_patcher
            and '"adjustDestroyTicks"' in client_patcher
            and '"completeLocalDestroyProgress"' in client_patcher,
        ),
        (
            "Minecraft patcher keeps block targeting stable and mining hits audible",
            "patchMultiPlayerGameModeBrowserHitSound" in client_patcher
            and "patchGameRendererBrowserTargetingAfterCamera" in client_patcher
            and "patchLevelRendererBrowserBlockOutlineOpacity" in client_patcher
            and "BrowserTargeting" in client_patcher
            and "GameRenderer post-camera block targeting patch point was not found" in client_patcher
            and "current instanceof EntityHitResult" in browser_targeting
            and "camera.position()" in browser_targeting
            and "camera.forwardVector()" in browser_targeting
            and "minecraft.player.blockInteractionRange()" in browser_targeting
            and "minecraft.level.clip" in browser_targeting
            and 'Float.valueOf(4.0f)' in client_patcher
            and 'alpha.operand == 102' in client_patcher
            and 'Opcodes.SIPUSH, 180' in client_patcher
            and '"getMainCamera"' not in client_patcher[client_patcher.find("patchLevelRendererBrowserBlockOutlineOpacity"):client_patcher.find("patchLevelRendererBrowserSectionCompileThrottle")]
            and '"position"' not in client_patcher[client_patcher.find("patchLevelRendererBrowserBlockOutlineOpacity"):client_patcher.find("patchLevelRendererBrowserSectionCompileThrottle")],
        ),
        (
            "Minecraft patcher removes the expensive browser loading chunk grid",
            "patchLevelLoadingScreenBrowserFastProgress" in client_patcher
            and '"renderChunks"' in client_patcher
            and '"ChunkLoadStatusView;)V"' in client_patcher,
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
            "Generated browser resource list contains filtered gameplay sounds",
            "assets/minecraft/sounds.json" in generated_resource_list
            and "assets/minecraft/sounds/random/click.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/click_stereo.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/wood_click.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/levelup.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/orb.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/eat1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/eat2.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/eat3.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/drink.ogg" in generated_resource_list
            and "assets/minecraft/sounds/random/burp.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/in.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/out.ogg" in generated_resource_list
            and "assets/minecraft/sounds/ui/toast/challenge_complete.ogg" in generated_resource_list
            and "assets/minecraft/sounds/dig/grass1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/step/grass1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/dig/stone1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/step/stone1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/dig/wood1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/step/wood1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/block/cherry_wood/break1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/block/calcite/place1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/mob/cow/say1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/mob/cow/hurt1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/mob/zombie/say1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/mob/zombie/step1.ogg" in generated_resource_list
            and "assets/minecraft/sounds/entity/cow/milk1.ogg" in generated_resource_list,
        ),
        (
            "Generated browser sounds.json only advertises copied browser sounds",
            isinstance(generated_sounds, dict)
            and bool(generated_sounds)
            and "ui.button.click" in generated_sounds
            and "ui.toast.in" in generated_sounds
            and "block.stone.break" in generated_sounds
            and "block.wood.break" in generated_sounds
            and "block.grass.step" in generated_sounds
            and "block.calcite.place" in generated_sounds
            and "entity.cow.ambient" in generated_sounds
            and "entity.cow.hurt" in generated_sounds
            and "entity.cow.step" in generated_sounds
            and "entity.zombie.ambient" in generated_sounds
            and "entity.zombie.hurt" in generated_sounds
            and "entity.zombie.step" in generated_sounds
            and "entity.generic.eat" in generated_sounds
            and "entity.generic.drink" in generated_sounds
            and "entity.player.burp" in generated_sounds
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
            "browser_sound_manifest" in build_teavm
            and "assetIndex.id" in build_teavm
            and 'startswith("minecraft/sounds/block/")' in build_teavm
            and 'startswith("minecraft/sounds/random/")' in build_teavm
            and 'startswith("minecraft/sounds/random/")' in fetch_version
            and 'startswith("minecraft/sounds/dig/")' in build_teavm
            and 'startswith("minecraft/sounds/step/")' in build_teavm
            and 'startswith("minecraft/sounds/mob/")' in build_teavm
            and 'startswith("minecraft/sounds/entity/")' in build_teavm
            and 'startswith("minecraft/sounds/item/")' in build_teavm
            and "assets/%s" in build_teavm
            and "minecraft/sounds/ui/toast/in.ogg" in build_teavm
            and "Filtered browser sounds.json" in build_teavm
            and "Mapped browser sound assets" in build_teavm,
        ),
        (
            "Browser storage seeds defaults once and preserves user client options",
            "DEFAULT_BROWSER_OPTIONS" in browser_file_persistence
            and "seedDefaultOptions" in browser_file_persistence
            and "BROWSER_PERFORMANCE_OPTIONS" not in browser_file_persistence
            and "enforcePerformanceOptions" not in browser_file_persistence
            and "upsertOptions" not in browser_file_persistence
            and "renderDistance:6" in browser_file_persistence
            and "simulationDistance:4" in browser_file_persistence
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
            and 'normalized.endsWith("/servers.dat_old")' in browser_file_persistence
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
            and "9223372036854775807" in postprocess_teavm_js
            and "gaius-java-finite-long-cast" in postprocess_teavm_js
            and 'find_anchored(' in postprocess_teavm_js
            and '"Number.isFinite"' in postprocess_teavm_js,
        ),
        (
            "Generated release client uses JVM-safe finite-to-long conversion",
            file_matches(
                DIST / "classes.js",
                rb"/\*gaius-java-finite-long-cast\*/BigInt\.asIntN\(64,"
                rb"!Number\.isFinite\([A-Za-z_$][A-Za-z0-9_$]*\)\?"
                rb"\([A-Za-z_$][A-Za-z0-9_$]*!==[A-Za-z_$][A-Za-z0-9_$]*\?BigInt\(0\)",
            )
            and not file_matches(
                DIST / "classes.js",
                rb"BigInt\.asIntN\(64,BigInt\([A-Za-z_$][A-Za-z0-9_$]*>=0\?"
                rb"Math\.floor\([A-Za-z_$][A-Za-z0-9_$]*\):"
                rb"Math\.ceil\([A-Za-z_$][A-Za-z0-9_$]*\)\)\)",
            ),
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
            "Local dist server keeps serving when detached access logging fails",
            "def log_message(self, format, *args)" in serve_dist
            and "AttributeError, BrokenPipeError, OSError, ValueError" in serve_dist
            and "A detached terminal must not turn a live static server" in serve_dist,
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
            and "content_token" in postprocess_index_html
            and "singleplayerBuildToken" in index_html
            and "__gaiusSingleplayerWorkerUrl" in index_html
            and "__gaiusSingleplayerServerUrl" in index_html
            and "postprocess-index-html.py" in build_release
            and 'requestedBuildToken + "-fresh-" + Date.now()' not in index_html,
        ),
        (
            "Browser boot preserves persisted user video settings",
            'installBrowserPerformanceOptions("indexeddb browser performance profile")' not in index_html
            and 'installBrowserPerformanceOptions("localStorage browser performance profile")' not in index_html
            and "function installBrowserPerformanceOptions" not in index_html
            and 'installBrowserPerformanceOptions("indexeddb browser performance profile")' in postprocess_index_html
            and 'installBrowserPerformanceOptions("localStorage browser performance profile")' in postprocess_index_html
            and "function installBrowserPerformanceOptions" in postprocess_index_html,
        ),
        (
            "Browser boot uses Minecraft-style progress and hides diagnostics by default",
            "boot-progress-bar" in index_html
            and "Minecraft-style boot screen" in index_html
            and 'id="boot-screen"' in index_html
            and 'id="boot-brand"' in index_html
            and "MOJANG<span>STUDIOS</span>" in index_html
            and 'const showPerfHud = urlParams.get("hud") === "1"' in index_html
            and 'const showPerfHud = urlParams.get("hud") !== "0"' not in index_html
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
            and "Math.min(1.0, rawDevicePixelRatio)" in index_html
            and 'Number(urlParams.get("menuMinDpr"))' in index_html
            and 'Number(urlParams.get("worldMinDpr"))' in index_html
            and 'Number(urlParams.get("maxDpr"))' in index_html
            and "Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps" in index_html
            and "LevelLoadingScreen" in index_html
            and "ProgressScreen" in index_html
            and "fps.worldEnteredAt" in index_html
            and "10000" in index_html
            and "fps.highSamples" in index_html
            and "fps.lastDprChangeAt" in index_html
            and "fps.recoveredCount" in index_html
            and "targetFps * 0.70" in index_html
            and "targetFps - 20" in index_html
            and "lowTarget + 20" in index_html
            and "fps.lowSamples < 3" in index_html
            and "window.__gaiusMaxDpr - 0.25" in index_html
            and "window.__gaiusDefaultMaxDpr" in index_html
            and "? (Number(window.__gaiusWorldMinDpr) || 1.0)" in index_html
            and ": (Number(window.__gaiusMenuMinDpr) || 1.0)" in index_html
            and "window.__gaiusMaxDpr > 1.0 ? 1.0 : 0.9" not in index_html
            and "window.__gaiusMaxDpr = Math.max(minDpr, nextMaxDpr)" in index_html
            and "if (!inWorld && minecraftState && minecraftState.screen) return;" not in index_html
            and "singleShadowMB" in index_html
            and "totalShadowMB" in index_html
            and 'Math.min(256, Number(urlParams.get("singleShadowMB")) || 256)' in index_html
            and 'Math.min(1024, Number(urlParams.get("totalShadowMB")) || 1024)' in index_html
            and "maybeDegradeResolutionForFps" in index_html
            and "function gaiusFpsSample()" in index_html
            and "function gaiusFpsTick" not in index_html
            and "requestAnimationFrame(gaiusFpsTick)" not in index_html
            and "function gaiusFpsSample()" in postprocess_index_html
            and "__gaiusWasmHotpath" in index_html
            and "gaius-hotpath.wasm" in index_html
            and "WebAssembly.instantiate" in index_html
            and "shiftIndices" in index_html
            and "repackInterleaved" in index_html
            and "unpackBitStorage" in index_html
            and "bitStorageWasmUnpack" in index_html
            and "if (!inWorld) return" not in index_html
            and "超过 30 秒没有进度变化" in index_html
            and '"--disableMultiplayer"' not in index_html
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
    netty_transport_overlay_cp = OVERLAYS / "libraries" / "io" / "netty" / "netty-transport" / "4.2.7.Final" / "netty-transport-4.2.7.Final.jar"
    classlib_cp = OVERLAYS / "classlib-patches"
    classlib_classes_cp = OVERLAYS / "classlib-classes"
    lwjgl_cp = OVERLAYS / "library-classes" / "lwjgl"
    lwjgl_opengl_cp = OVERLAYS / "library-classes" / "lwjgl-opengl"
    lwjgl_opengl_patch_cp = OVERLAYS / "library-patches" / "lwjgl-opengl"
    lwjgl_openal_cp = OVERLAYS / "libraries" / "org" / "lwjgl" / "lwjgl-openal" / "3.3.3" / "lwjgl-openal-3.3.3.jar"
    lwjgl_openal_classes_cp = OVERLAYS / "library-classes" / "lwjgl-openal"
    lwjgl_glfw_cp = OVERLAYS / "libraries" / "org" / "lwjgl" / "lwjgl-glfw" / "3.3.3" / "lwjgl-glfw-3.3.3.jar"
    joml_cp = OVERLAYS / "libraries" / "org" / "joml" / "joml" / "1.10.8" / "joml-1.10.8.jar"
    authlib_cp = OVERLAYS / "libraries" / "com" / "mojang" / "authlib" / "7.0.61" / "authlib-7.0.61.jar"
    server_worker_classes_cp = TARGET / "server-worker" / "maven" / "classes"
    browser_opengl_class = lwjgl_opengl_cp / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.class"
    browser_openal_class = lwjgl_openal_classes_cp / "org" / "lwjgl" / "openal" / "BrowserOpenAL.class"

    packet_encoder = run_javap(client_cp, "net.minecraft.network.PacketEncoder")
    packet_bundle_unpacker = run_javap(client_cp, "net.minecraft.network.PacketBundleUnpacker")
    varint_prepender = run_javap(client_cp, "net.minecraft.network.Varint21LengthFieldPrepender")
    cipher_encoder = run_javap(client_cp, "net.minecraft.network.CipherEncoder")
    compression_encoder = run_javap(client_cp, "net.minecraft.network.CompressionEncoder")
    classlib_inflater = run_javap(
        classlib_cp,
        "org.teavm.classlib.java.util.zip.TInflater",
    )
    classlib_deflater = run_javap(
        classlib_classes_cp,
        "org.teavm.classlib.java.util.zip.TDeflater",
    )
    classlib_zip_support = run_javap(
        classlib_classes_cp,
        "org.teavm.classlib.java.util.zip.TZipModernSupport",
    )
    modern_runtime_support_class = run_javap(
        classlib_classes_cp,
        "org.teavm.classlib.java.lang.TModernRuntimeSupport",
    )
    joml_math = run_javap(joml_cp, "org.joml.Math")
    file_output_stream_class = run_javap(
        classlib_classes_cp,
        "org.teavm.classlib.java.io.TFileOutputStream",
    )
    default_file_system_provider = run_javap(
        classlib_cp,
        "org.teavm.classlib.java.nio.file.impl.TDefaultFileSystemProvider",
    )
    zip_file = run_javap(
        classlib_cp,
        "org.teavm.classlib.java.util.zip.TZipFile",
    )
    crypt = run_javap(client_cp, "net.minecraft.util.Crypt")
    signer = run_javap(client_cp, "net.minecraft.util.Signer")
    signed_message_chain = run_javap(client_cp, "net.minecraft.network.chat.SignedMessageChain")
    account_profile_keys = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.AccountProfileKeyPairManager",
    )
    http_util = run_javap(client_cp, "net.minecraft.util.HttpUtil")
    realms_request = run_javap(client_cp, "com.mojang.realmsclient.client.Request")
    authlib_client = run_javap(authlib_cp, "com.mojang.authlib.minecraft.client.MinecraftClient")
    skin_texture_downloader = run_javap(
        client_cp,
        "net.minecraft.client.renderer.texture.SkinTextureDownloader",
    )
    browser_cipher_constants = read_zip_entry_latin1(client_cp, "javax/crypto/Cipher.class")
    class_tree_id_registry = run_javap(client_cp, "net.minecraft.util.ClassTreeIdRegistry")
    synched_entity_data = run_javap(client_cp, "net.minecraft.network.syncher.SynchedEntityData")
    entity = run_javap(client_cp, "net.minecraft.world.entity.Entity")
    integrated_server = run_javap(client_cp, "net.minecraft.client.server.IntegratedServer")
    screen = run_javap(client_cp, "net.minecraft.client.gui.screens.Screen")
    level_loading_screen = run_javap(
        client_cp,
        "net.minecraft.client.gui.screens.LevelLoadingScreen",
    )
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
    multiplayer_game_mode = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.MultiPlayerGameMode",
    )
    game_renderer = run_javap(client_cp, "net.minecraft.client.renderer.GameRenderer")
    level_renderer = run_javap(client_cp, "net.minecraft.client.renderer.LevelRenderer")
    section_render_dispatcher = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
    )
    compiled_section_mesh = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.CompiledSectionMesh",
    )
    render_section_region = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.RenderSectionRegion",
    )
    section_compiler = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.SectionCompiler",
    )
    minecraft_server = run_javap(client_cp, "net.minecraft.server.MinecraftServer")
    chunk_map = run_javap(client_cp, "net.minecraft.server.level.ChunkMap")
    player_spawn_finder = run_javap(
        client_cp,
        "net.minecraft.server.level.PlayerSpawnFinder",
    )
    prepare_spawn_task = run_javap(
        client_cp,
        "net.minecraft.server.network.config.PrepareSpawnTask$Preparing",
    )
    server_common_packet_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerCommonPacketListenerImpl",
    )
    server_game_packet_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerGamePacketListenerImpl",
    )
    server_player_game_mode = run_javap(
        client_cp,
        "net.minecraft.server.level.ServerPlayerGameMode",
    )
    server_login_packet_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerLoginPacketListenerImpl",
    )
    browser_worldgen_scheduler_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserWorldgenScheduler",
    )
    chunk_generator_structure_state = run_javap(
        client_cp,
        "net.minecraft.world.level.chunk.ChunkGeneratorStructureState",
    )
    proto_chunk = run_javap(client_cp, "net.minecraft.world.level.chunk.ProtoChunk")
    block_pos = run_javap(client_cp, "net.minecraft.core.BlockPos")
    noise_based_chunk_generator = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
    )
    improved_noise = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.synth.ImprovedNoise",
    )
    perlin_noise = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.synth.PerlinNoise",
    )
    noise_chunk = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.NoiseChunk",
    )
    noise_interpolator = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator",
    )
    noise_chunk_context = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.NoiseChunk$1",
    )
    noise_chunk_cache_once = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce",
    )
    climate_rtree_subtree = run_javap(
        client_cp,
        "net.minecraft.world.level.biome.Climate$RTree$SubTree",
    )
    climate_rtree_node = run_javap(
        client_cp,
        "net.minecraft.world.level.biome.Climate$RTree$Node",
    )
    browser_integrated_server_main_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserIntegratedServerMain",
    )
    surface_system = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceSystem",
    )
    chunk_generator = run_javap(
        client_cp,
        "net.minecraft.world.level.chunk.ChunkGenerator",
    )
    world_carver = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.carver.WorldCarver",
    )
    light_engine = run_javap(
        client_cp,
        "net.minecraft.world.level.lighting.LightEngine",
    )
    level_chunk_section = run_javap(
        client_cp,
        "net.minecraft.world.level.chunk.LevelChunkSection",
    )
    chunk_generation_task = run_javap(
        client_cp,
        "net.minecraft.server.level.ChunkGenerationTask",
    )
    persistent_entity_manager = run_javap(
        client_cp,
        "net.minecraft.world.level.entity.PersistentEntitySectionManager",
    )
    gl_device = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlDevice")
    gl_command_encoder = run_javap(
        client_cp, "com.mojang.blaze3d.opengl.GlCommandEncoder"
    )
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
    render_system = run_javap(client_cp, "com.mojang.blaze3d.systems.RenderSystem")
    minecraft = run_javap(client_cp, "net.minecraft.client.Minecraft")
    client_options = run_javap(client_cp, "net.minecraft.client.Options")
    server_connection_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerConnectionListener",
    )
    server_text_filter = run_javap(client_cp, "net.minecraft.server.network.ServerTextFilter")
    server_main = run_javap(client_cp, "net.minecraft.server.Main")
    dedicated_server = run_javap(client_cp, "net.minecraft.server.dedicated.DedicatedServer")
    mouse_handler = run_javap(client_cp, "net.minecraft.client.MouseHandler")
    keyboard_handler = run_javap(client_cp, "net.minecraft.client.KeyboardHandler")
    connection = run_javap(client_cp, "net.minecraft.network.Connection")
    server_address_resolver = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.resolver.ServerAddressResolver",
    )
    resolved_server_address = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.resolver.ResolvedServerAddress$1",
    )
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
    render_system_limit_fps = method_section(render_system, "public static void limitDisplayFPS(int);")
    render_system_compensate_frame_time = method_section(
        render_system,
        "private static double browserCompensateFrameTime(double, double, int);",
    )
    minecraft_run_tick = method_section(minecraft, "private void runTick(boolean);")
    minecraft_process_packets_and_tick = method_section(
        minecraft_server,
        "public void processPacketsAndTick(boolean);",
    )
    server_game_chunk_batch = method_section(
        server_game_packet_listener,
        "public void handleChunkBatchReceived(net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket);",
    )
    server_player_handle_break = method_section(
        server_player_game_mode,
        "public void handleBlockBreakAction(net.minecraft.core.BlockPos, net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action, net.minecraft.core.Direction, int, int);",
    )
    client_options_constructor = method_section(
        client_options,
        "public net.minecraft.client.Options(net.minecraft.client.Minecraft, java.io.File);",
    )
    client_options_save = method_section(client_options, "public void save();")
    simulation_distance_start = client_options_constructor.find("String options.simulationDistance")
    simulation_distance_end = client_options_constructor.find(
        "Field simulationDistance:Lnet/minecraft/client/OptionInstance;",
        simulation_distance_start,
    )
    simulation_distance_range = (
        client_options_constructor[simulation_distance_start:simulation_distance_end]
        if simulation_distance_start >= 0 and simulation_distance_end > simulation_distance_start
        else ""
    )
    minecraft_world_load = method_section(minecraft, "public void doWorldLoad(")
    minecraft_disconnect = method_section(
        minecraft,
        "public void disconnect(net.minecraft.client.gui.screens.Screen, boolean, boolean);",
    )
    minecraft_spin = method_section(
        minecraft_server,
        "public static <S extends net.minecraft.server.MinecraftServer> S spin(",
    )
    server_listener_start = method_section(
        server_connection_listener,
        "public void startTcpServerListener(java.net.InetAddress, int) throws java.io.IOException;",
    )
    server_text_filter_create = method_section(
        server_text_filter,
        "public static net.minecraft.server.network.ServerTextFilter createFromConfig(",
    )
    dedicated_server_init = method_section(dedicated_server, "public boolean initServer() throws java.io.IOException;")
    dedicated_server_stop = method_section(dedicated_server, "public void stopServer();")
    server_login_hello = method_section(
        server_login_packet_listener,
        "public void handleHello(net.minecraft.network.protocol.login.ServerboundHelloPacket);",
    )
    mouse_setup_move = method_section(mouse_handler, "private void lambda$setup$3(long, double, double);")
    mouse_setup_button = method_section(mouse_handler, "private void lambda$setup$5(long, int, int, int);")
    mouse_setup_scroll = method_section(mouse_handler, "private void lambda$setup$7(long, double, double);")
    keyboard_setup_key = method_section(keyboard_handler, "private void lambda$setup$6(long, int, int, int, int);")
    keyboard_setup_char = method_section(keyboard_handler, "private void lambda$setup$8(long, int, int);")
    framerate_tracker = run_javap(client_cp, "com.mojang.blaze3d.platform.FramerateLimitTracker")
    player_list = run_javap(client_cp, "net.minecraft.server.players.PlayerList")
    simple_bit_storage = run_javap(client_cp, "net.minecraft.util.SimpleBitStorage")
    biome_manager = run_javap(client_cp, "net.minecraft.world.level.biome.BiomeManager")
    aquifer = run_javap(client_cp, "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
    beardifier = run_javap(client_cp, "net.minecraft.world.level.levelgen.Beardifier")
    heightmap = run_javap(client_cp, "net.minecraft.world.level.levelgen.Heightmap")
    buffer_builder = run_javap(client_cp, "com.mojang.blaze3d.vertex.BufferBuilder")
    byte_buffer_builder = run_javap(
        client_cp,
        "com.mojang.blaze3d.vertex.ByteBufferBuilder",
    )
    integrated_tick = method_section(integrated_server, "public void tickServer(java.util.function.BooleanSupplier);")
    proto_chunk_set_block_state = method_section(
        proto_chunk,
        "public net.minecraft.world.level.block.state.BlockState setBlockState(net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState, int);",
    )
    proto_chunk_get_block_state = method_section(
        proto_chunk,
        "public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos);",
    )
    proto_chunk_get_fluid_state = method_section(
        proto_chunk,
        "public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos);",
    )
    proto_chunk_constructor = method_section(
        proto_chunk,
        "public net.minecraft.world.level.chunk.ProtoChunk(net.minecraft.world.level.ChunkPos, net.minecraft.world.level.chunk.UpgradeData, net.minecraft.world.level.chunk.LevelChunkSection[]",
    )
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
    level_loading_render_chunks = method_section(
        level_loading_screen,
        "public static void renderChunks(net.minecraft.client.gui.GuiGraphics, int, int, int, int, net.minecraft.server.level.progress.ChunkLoadStatusView);",
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
    client_level_destroy_block_progress = method_section(
        client_level,
        "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);",
    )
    client_level_add_destroy_block_effect = method_section(
        client_level,
        "public void addDestroyBlockEffect(net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);",
    )
    multiplayer_continue_destroy = method_section(
        multiplayer_game_mode,
        "public boolean continueDestroyBlock(net.minecraft.core.BlockPos, net.minecraft.core.Direction);",
    )
    level_compile_sections = method_section(
        level_renderer,
        "private void compileSections(net.minecraft.client.Camera);",
    )
    level_destroy_block_progress = method_section(
        level_renderer,
        "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);",
    )
    level_render_block_outline = method_section(
        level_renderer,
        "private void renderBlockOutline(net.minecraft.client.renderer.MultiBufferSource$BufferSource, com.mojang.blaze3d.vertex.PoseStack, boolean, net.minecraft.client.renderer.state.LevelRenderState);",
    )
    level_extract_block_outline = method_section(
        level_renderer,
        "private void extractBlockOutline(net.minecraft.client.Camera, net.minecraft.client.renderer.state.LevelRenderState);",
    )
    section_uploads = method_section(
        section_render_dispatcher,
        "public void uploadAllPendingUploads();",
    )
    section_dispatcher_constructor = method_section(
        section_render_dispatcher,
        "public net.minecraft.client.renderer.chunk.SectionRenderDispatcher(",
    )
    section_dispatcher_run_task = method_section(
        section_render_dispatcher,
        "private void runTask();",
    )
    compiled_section_upload_mesh = method_section(
        compiled_section_mesh,
        "public void uploadMeshLayer(net.minecraft.client.renderer.chunk.ChunkSectionLayer, com.mojang.blaze3d.vertex.MeshData, long);",
    )
    render_section_get_block_state = method_section(
        render_section_region,
        "public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos);",
    )
    render_section_get_fluid_state = method_section(
        render_section_region,
        "public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos);",
    )
    render_section_get_block_entity = method_section(
        render_section_region,
        "public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos);",
    )
    section_compiler_compile = method_section(
        section_compiler,
        "public net.minecraft.client.renderer.chunk.SectionCompiler$Results compile(net.minecraft.core.SectionPos, net.minecraft.client.renderer.chunk.RenderSectionRegion, com.mojang.blaze3d.vertex.VertexSorting, net.minecraft.client.renderer.SectionBufferBuilderPack);",
    )
    entity_constructor = method_section(
        entity,
        "public net.minecraft.world.entity.Entity(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);",
    )
    minecraft_run_server = method_section(minecraft_server, "protected void runServer();")
    chunk_map_set_view_distance = method_section(
        chunk_map,
        "protected void setServerViewDistance(int);",
    )
    minecraft_initial_spawn = method_section(
        minecraft_server,
        "private static void setInitialSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.world.level.storage.ServerLevelData, boolean, boolean, net.minecraft.server.level.progress.LevelLoadListener);",
    )
    player_find_spawn = method_section(
        player_spawn_finder,
        "public static java.util.concurrent.CompletableFuture<net.minecraft.world.phys.Vec3> findSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos);",
    )
    prepare_spawn_load_chunks = method_section(
        prepare_spawn_task,
        "private void lambda$tick$0(net.minecraft.world.level.ChunkPos);",
    )
    server_common_is_singleplayer_owner = method_section(
        server_common_packet_listener,
        "protected boolean isSingleplayerOwner();",
    )
    worldgen_checkpoint = method_section(
        browser_worldgen_scheduler_class,
        "public static void checkpoint();",
    )
    worldgen_pulse = method_section(
        browser_worldgen_scheduler_class,
        "public static void pulse();",
    )
    browser_ring_position = method_section(
        chunk_generator_structure_state,
        "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$5(int, int, net.minecraft.core.HolderSet, net.minecraft.util.RandomSource);",
    )
    block_pos_get_x = method_section(block_pos, "public static int getX(long);")
    block_pos_get_y = method_section(block_pos, "public static int getY(long);")
    block_pos_get_z = method_section(block_pos, "public static int getZ(long);")
    block_pos_as_long = method_section(
        block_pos,
        "public static long asLong(int, int, int);",
    )
    noise_do_fill = method_section(
        noise_based_chunk_generator,
        "private net.minecraft.world.level.chunk.ChunkAccess doFill(net.minecraft.world.level.levelgen.blending.Blender, net.minecraft.world.level.StructureManager, net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.chunk.ChunkAccess, int, int);",
    )
    improved_noise_sample = method_section(
        improved_noise,
        "private double sampleAndLerp(int, int, int, double, double, double, double);",
    )
    improved_noise_noise = method_section(
        improved_noise,
        "public double noise(double, double, double, double, double);",
    )
    perlin_noise_wrap = method_section(
        perlin_noise,
        "public static double wrap(double);",
    )
    perlin_noise_constructor = method_section(
        perlin_noise,
        "protected net.minecraft.world.level.levelgen.synth.PerlinNoise(net.minecraft.util.RandomSource, com.mojang.datafixers.util.Pair<java.lang.Integer, it.unimi.dsi.fastutil.doubles.DoubleList>, boolean);",
    )
    perlin_noise_get_value = method_section(
        perlin_noise,
        "public double getValue(double, double, double, double, double, boolean);",
    )
    noise_apply_carvers = method_section(
        noise_based_chunk_generator,
        "public void applyCarvers(net.minecraft.server.level.WorldGenRegion, long, net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.biome.BiomeManager, net.minecraft.world.level.StructureManager, net.minecraft.world.level.chunk.ChunkAccess);",
    )
    noise_fill_slice = method_section(noise_chunk, "private void fillSlice(boolean, int);")
    noise_fill_direct = method_section(
        noise_chunk,
        "public void fillAllDirectly(double[], net.minecraft.world.level.levelgen.DensityFunction);",
    )
    noise_select_cell_yz = method_section(noise_chunk, "public void selectCellYZ(int, int);")
    noise_update_y = method_section(noise_chunk, "public void updateForY(int, double);")
    noise_update_x = method_section(noise_chunk, "public void updateForX(int, double);")
    noise_update_z = method_section(noise_chunk, "public void updateForZ(int, double);")
    noise_interpolator_compute = method_section(
        noise_interpolator,
        "public double compute(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext);",
    )
    noise_interpolator_update_y = method_section(
        noise_interpolator,
        "void updateForY(double);",
    )
    noise_interpolator_update_x = method_section(
        noise_interpolator,
        "void updateForX(double);",
    )
    noise_interpolator_update_z = method_section(
        noise_interpolator,
        "void updateForZ(double);",
    )
    noise_cache_compute = method_section(
        noise_chunk_cache_once,
        "public double compute(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext);",
    )
    noise_cache_fill_array = method_section(
        noise_chunk_cache_once,
        "public void fillArray(double[], net.minecraft.world.level.levelgen.DensityFunction$ContextProvider);",
    )
    noise_context_for_index = method_section(
        noise_chunk_context,
        "public net.minecraft.world.level.levelgen.DensityFunction$FunctionContext forIndex(int);",
    )
    climate_rtree_distance = method_section(
        climate_rtree_node,
        "protected long distance(long[]);",
    )
    climate_rtree_constructor = method_section(
        climate_rtree_node,
        "protected net.minecraft.world.level.biome.Climate$RTree$Node(java.util.List<net.minecraft.world.level.biome.Climate$Parameter>);",
    )
    browser_activate_distances = method_section(
        browser_integrated_server_main_class,
        "public static void activateConfiguredDistances();",
    )
    browser_advance_distances = method_section(
        browser_integrated_server_main_class,
        "public static void advanceConfiguredDistances();",
    )
    browser_adjust_destroy_ticks = method_section(
        browser_integrated_server_main_class,
        "public static int adjustDestroyTicks(int, long);",
    )
    browser_complete_destroy_progress = method_section(
        browser_integrated_server_main_class,
        "public static float completeLocalDestroyProgress(float);",
    )
    climate_rtree_search = method_section(
        climate_rtree_subtree,
        "protected net.minecraft.world.level.biome.Climate$RTree$Leaf<T> search(long[], net.minecraft.world.level.biome.Climate$RTree$Leaf<T>, net.minecraft.world.level.biome.Climate$DistanceMetric<T>);",
    )
    surface_build = method_section(
        surface_system,
        "public void buildSurface(net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.biome.BiomeManager, net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome>, boolean, net.minecraft.world.level.levelgen.WorldGenerationContext, net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.levelgen.NoiseChunk, net.minecraft.world.level.levelgen.SurfaceRules$RuleSource);",
    )
    chunk_apply_biome_decoration = method_section(
        chunk_generator,
        "public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.StructureManager);",
    )
    world_carve_ellipsoid = method_section(
        world_carver,
        "protected boolean carveEllipsoid(net.minecraft.world.level.levelgen.carver.CarvingContext, C, net.minecraft.world.level.chunk.ChunkAccess, java.util.function.Function<net.minecraft.core.BlockPos, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>, net.minecraft.world.level.levelgen.Aquifer, double, double, double, double, double, net.minecraft.world.level.chunk.CarvingMask, net.minecraft.world.level.levelgen.carver.WorldCarver$CarveSkipChecker);",
    )
    light_propagate_increases = method_section(light_engine, "private int propagateIncreases();")
    light_propagate_decreases = method_section(light_engine, "private int propagateDecreases();")
    section_fill_biomes = method_section(
        level_chunk_section,
        "public void fillBiomesFromNoise(net.minecraft.world.level.biome.BiomeResolver, net.minecraft.world.level.biome.Climate$Sampler, int, int, int);",
    )
    generation_run_until_wait = method_section(
        chunk_generation_task,
        "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    )
    entity_uuid_add = method_section(persistent_entity_manager, "private boolean addEntityUuid(T);")
    gl_device_max_texture = method_section(gl_device, "private static int getMaxSupportedTextureSize();")
    gl_device_static = method_section(gl_device, "static {};")
    gl_command_encoder_draw = method_section(
        gl_command_encoder,
        "private void drawFromBuffers(com.mojang.blaze3d.opengl.GlRenderPass, int, int, int, com.mojang.blaze3d.vertex.VertexFormat$IndexType, com.mojang.blaze3d.opengl.GlRenderPipeline, int);",
    )
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
    simple_bit_storage_get = method_section(simple_bit_storage, "public int get(int);")
    simple_bit_storage_get_and_set = method_section(
        simple_bit_storage, "public int getAndSet(int, int);"
    )
    simple_bit_storage_set = method_section(simple_bit_storage, "public void set(int, int);")
    biome_manager_get_biome = method_section(
        biome_manager,
        "public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos);",
    )
    aquifer_compute_substance = method_section(
        aquifer,
        "public net.minecraft.world.level.block.state.BlockState computeSubstance(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext, double);",
    )
    aquifer_constructor = method_section(
        aquifer,
        "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer(net.minecraft.world.level.levelgen.NoiseChunk, net.minecraft.world.level.ChunkPos, net.minecraft.world.level.levelgen.NoiseRouter, net.minecraft.world.level.levelgen.PositionalRandomFactory, int, int, net.minecraft.world.level.levelgen.Aquifer$FluidPicker);",
    )
    beardifier_compute = method_section(
        beardifier,
        "public double compute(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext);",
    )
    beardifier_constructor = method_section(
        beardifier,
        "public net.minecraft.world.level.levelgen.Beardifier(java.util.List<net.minecraft.world.level.levelgen.Beardifier$Rigid>, java.util.List<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>, net.minecraft.world.level.levelgen.structure.BoundingBox);",
    )
    heightmap_constructor = method_section(
        heightmap,
        "public net.minecraft.world.level.levelgen.Heightmap(net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.levelgen.Heightmap$Types);",
    )
    heightmap_get_first_available = method_section(
        heightmap, "public int getFirstAvailable(int, int);"
    )
    heightmap_get_highest_taken = method_section(
        heightmap, "public int getHighestTaken(int, int);"
    )
    heightmap_get_first_available_index = method_section(
        heightmap, "private int getFirstAvailable(int);"
    )
    heightmap_set_height = method_section(heightmap, "private void setHeight(int, int, int);")
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
    byte_buffer_builder_reserve = method_section(
        byte_buffer_builder,
        "public long reserve(int);",
    )
    joml_float_fma = method_section(
        joml_math,
        "public static float fma(float, float, float);",
    )
    joml_double_fma = method_section(
        joml_math,
        "public static double fma(double, double, double);",
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
    reflective_channel_factory = run_javap(
        netty_transport_overlay_cp,
        "io.netty.channel.ReflectiveChannelFactory",
    )
    abstract_channel_unsafe = run_javap(
        netty_transport_overlay_cp,
        "io.netty.channel.AbstractChannel$AbstractUnsafe",
    )
    abstract_channel_register = method_section(
        abstract_channel_unsafe,
        "public final void register(io.netty.channel.EventLoop, io.netty.channel.ChannelPromise);",
    )
    netty_bootstrap = run_javap(netty_transport_overlay_cp, "io.netty.bootstrap.Bootstrap")
    netty_bootstrap_connect = method_section(
        netty_bootstrap,
        "private static void doConnect(java.net.SocketAddress, java.net.SocketAddress, io.netty.channel.ChannelPromise);",
    )
    throwable = run_javap(classlib_cp, "org.teavm.classlib.java.lang.TThrowable")
    file_output_truncate = method_section(
        file_output_stream_class,
        "public static void truncateIfRequested(org.teavm.runtime.fs.VirtualFileAccessor, boolean) throws java.io.IOException;",
    )
    default_new_output_stream = method_section(
        default_file_system_provider,
        "public java.io.OutputStream newOutputStream(org.teavm.classlib.java.nio.file.TPath, org.teavm.classlib.java.nio.file.TOpenOption...) throws java.io.IOException;",
    )
    zip_get_input_stream = method_section(
        zip_file,
        "public java.io.InputStream getInputStream(org.teavm.classlib.java.util.zip.TZipEntry) throws java.io.IOException;",
    )
    browser_memory = run_javap(lwjgl_cp, "org.lwjgl.system.BrowserMemory")
    browser_memory_copy = method_section(browser_memory, "public static void copy(long, long, long);")
    browser_memory_copy_overlapping = method_section(browser_memory, "private static void copyOverlapping(java.nio.ByteBuffer, int, int, int);")
    browser_memory_set = method_section(browser_memory, "public static void set(long, int, long);")
    browser_memory_fast_vertex = method_section(browser_memory, "public static void putFastVertex(")
    browser_memory_region = method_section(
        browser_memory, "private static org.lwjgl.system.BrowserMemory$Region region(long);"
    )
    browser_memory_decode_utf8 = method_section(browser_memory, "public static java.lang.String decodeUtf8(long, int);")
    browser_memory_temporary_bytes = method_section(browser_memory, "private static byte[] temporaryBytes(int);")
    browser_websocket_channel = run_javap(netty_transport_overlay_cp, "io.netty.channel.browser.BrowserWebSocketChannel")
    browser_websocket_pump = method_section(
        browser_websocket_channel,
        "private void pump();",
    )
    browser_inline_event_loop = run_javap(
        netty_transport_overlay_cp,
        "io.netty.channel.browser.BrowserInlineEventLoop",
    )
    browser_inline_in_event_loop = method_section(
        browser_inline_event_loop,
        "public boolean inEventLoop(java.lang.Thread);",
    )
    browser_inline_execute = method_section(
        browser_inline_event_loop,
        "public void execute(java.lang.Runnable);",
    )
    crypt_generate_secret = method_section(
        crypt,
        "public static javax.crypto.SecretKey generateSecretKey() throws net.minecraft.util.CryptException;",
    )
    crypt_digest = method_section(
        crypt,
        "public static byte[] digestData(java.lang.String, java.security.PublicKey, javax.crypto.SecretKey) throws net.minecraft.util.CryptException;",
    )
    crypt_parse_public = method_section(
        crypt,
        "public static java.security.PublicKey byteToPublicKey(byte[]) throws net.minecraft.util.CryptException;",
    )
    crypt_parse_private = method_section(
        crypt,
        "private static java.security.PrivateKey byteToPrivateKey(byte[]) throws net.minecraft.util.CryptException;",
    )
    crypt_encrypt = method_section(
        crypt,
        "public static byte[] encryptUsingKey(java.security.Key, byte[]) throws net.minecraft.util.CryptException;",
    )
    crypt_get_cipher = method_section(
        crypt,
        "public static javax.crypto.Cipher getCipher(int, java.security.Key) throws net.minecraft.util.CryptException;",
    )
    signer_from = method_section(
        signer,
        "public static net.minecraft.util.Signer from(java.security.PrivateKey, java.lang.String);",
    )
    signed_message_encoder = method_section(
        signed_message_chain,
        "public net.minecraft.network.chat.SignedMessageChain$Encoder encoder(net.minecraft.util.Signer);",
    )
    account_prepare_key = method_section(
        account_profile_keys,
        "public java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.world.entity.player.ProfileKeyPair>> prepareKeyPair();",
    )
    http_util_download = method_section(
        http_util,
        "public static java.nio.file.Path downloadFile(java.nio.file.Path, java.net.URL, java.util.Map<java.lang.String, java.lang.String>, com.google.common.hash.HashFunction, com.google.common.hash.HashCode, int, java.net.Proxy, net.minecraft.util.HttpUtil$DownloadProgressListener);",
    )
    authlib_create_connection = method_section(
        authlib_client,
        "private java.net.HttpURLConnection createUrlConnection(java.net.URL);",
    )
    skin_texture_download = method_section(
        skin_texture_downloader,
        "private com.mojang.blaze3d.platform.NativeImage downloadSkin(java.nio.file.Path, java.lang.String) throws java.io.IOException;",
    )
    realms_request_constructor = method_section(
        realms_request,
        "public com.mojang.realmsclient.client.Request(java.lang.String, int, int);",
    )
    realms_request_cookie = method_section(
        realms_request,
        "public static void cookie(java.net.HttpURLConnection, java.lang.String, java.lang.String);",
    )
    browser_websocket_constants = read_zip_entry_latin1(
        netty_transport_overlay_cp,
        "io/netty/channel/browser/BrowserWebSocketChannel.class",
    )
    connection_connect = method_section(
        connection,
        "public static io.netty.channel.ChannelFuture connect(java.net.InetSocketAddress, net.minecraft.server.network.EventLoopGroupHolder, net.minecraft.network.Connection);",
    )
    connection_tick = method_section(connection, "public void tick();")
    server_address_resolver_lambda = method_section(
        server_address_resolver,
        "private static java.util.Optional lambda$static$0(net.minecraft.client.multiplayer.resolver.ServerAddress);",
    )
    server_redirect_handler = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.resolver.ServerRedirectHandler",
    )
    server_redirect_create = method_section(
        server_redirect_handler,
        "public static net.minecraft.client.multiplayer.resolver.ServerRedirectHandler createDnsSrvRedirectHandler();",
    )
    resolved_server_get_host_name = method_section(
        resolved_server_address,
        "public java.lang.String getHostName();",
    )
    resolved_server_get_host_ip = method_section(
        resolved_server_address,
        "public java.lang.String getHostIp();",
    )
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
            "TeaVM output stream bytecode preserves replacement-write truncation",
            "VirtualFileAccessor.resize" in file_output_truncate
            and "VirtualFileAccessor.seek" in file_output_truncate
            and "TFileOutputStream.truncateIfRequested" in default_new_output_stream
            and "TFileOutputStream.\"<init>\"" in default_new_output_stream,
        ),
        (
            "TeaVM ZIP bytecode pads only the raw inflater branch",
            "TZipFile$RAFStream.mLength" in zip_get_input_stream
            and "lconst_1" in zip_get_input_stream
            and "ladd" in zip_get_input_stream
            and zip_get_input_stream.find("if_icmpne")
                < zip_get_input_stream.find("lconst_1")
                < zip_get_input_stream.find("TZipFile$ZipInflaterInputStream"),
        ),
        (
            "Minecraft singleplayer compiled overlay hands worlds to the server Worker",
            "BrowserSingleplayerClient.open" in minecraft_world_load
            and "ireturn" not in minecraft_world_load
            and "BrowserSingleplayerClient.stop" in minecraft_disconnect
            and "BrowserIntegratedServerMain.registerServer" in minecraft_spin,
        ),
        (
            "Integrated server preserves the browser client's profile UUID",
            "ServerboundHelloPacket.profileId" in server_login_hello
            and "com/mojang/authlib/GameProfile.\"<init>\"" in server_login_hello
            and "UUIDUtil.createOfflineProfile" not in server_login_hello,
        ),
        (
            "Options compiled overlay allows simulation distances below vanilla minimum five",
            "String options.simulationDistance" in simulation_distance_range
            and "iconst_2" in simulation_distance_range
            and "DEBUG_ALLOW_LOW_SIM_DISTANCE" not in simulation_distance_range
            and "BrowserSingleplayerClient.syncDistances" in client_options_save,
        ),
        (
            "Official server compiled overlay listens over the local browser tunnel",
            "BrowserWebSocketChannel" in server_listener_start
            and "ServerConnectionListener$1" in server_listener_start
            and "BrowserIntegratedServerMain.tunnelAddress" in server_listener_start
            and "Bootstrap.connect" in server_listener_start,
        ),
        (
            "Official server compiled overlay excludes unsupported desktop services",
            "aconst_null" in server_text_filter_create
            and "areturn" in server_text_filter_create
            and "java/awt" not in server_main
            and "ProcessHandle" not in server_main
            and "YggdrasilAuthenticationService.createOffline" in server_main
            and 'YggdrasilAuthenticationService."<init>":(Ljava/net/Proxy;)V' not in server_main
            and "ManagementServer" not in dedicated_server_init
            and "QueryThreadGs4.create" not in dedicated_server_init
            and "RconThread.create" not in dedicated_server_init
            and "ServerWatchdog" not in dedicated_server_init
            and "ManagementServer.stop" not in dedicated_server_stop,
        ),
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
            "BrowserWebSocketChannel compiled into netty transport overlay",
            "public final class io.netty.channel.browser.BrowserWebSocketChannel" in browser_websocket_channel
            and "public static void pumpAll();" in browser_websocket_channel
            and "public static boolean shouldRegisterInline(io.netty.channel.EventLoop, io.netty.channel.Channel);" in browser_websocket_channel
            and "public static io.netty.channel.EventLoop eventLoopFor(io.netty.channel.Channel, io.netty.channel.EventLoop);" in browser_websocket_channel
            and "public static boolean connectInline(io.netty.channel.Channel, java.net.SocketAddress, java.net.SocketAddress, io.netty.channel.ChannelPromise);" in browser_websocket_channel
            and "protected void doWrite" in browser_websocket_channel
            and "sipush        128" in browser_websocket_pump
            and "int 2097152" in browser_websocket_pump
            and "double 4.0d" in browser_websocket_pump
            and "Method monotonicMillis:()D" in browser_websocket_pump
            and "Method recordPump:(IIID)V" in browser_websocket_pump
            and "java/util/concurrent" not in browser_websocket_channel
            and "java/util/Collections" not in browser_websocket_channel
            and "globalThis.__gaiusNettyBridge" in browser_websocket_constants
            and "globalThis.__gaiusNetworkStats" in browser_websocket_constants
            and "new WebSocket(candidate.url)" in browser_websocket_constants,
        ),
        (
            "AbstractChannel registers browser transport inline before syncUninterruptibly",
            "io/netty/channel/browser/BrowserWebSocketChannel.eventLoopFor" in abstract_channel_register
            and "io/netty/channel/browser/BrowserWebSocketChannel.shouldRegisterInline" in abstract_channel_register
            and "(Lio/netty/channel/EventLoop;Lio/netty/channel/Channel;)Z" in abstract_channel_register
            and abstract_channel_register.find("BrowserWebSocketChannel.eventLoopFor")
            < abstract_channel_register.find("AbstractChannel.isCompatible"),
        ),
        (
            "BrowserInlineEventLoop executes browser pipeline tasks on the current turn",
            "iconst_1" in browser_inline_in_event_loop
            and "ireturn" in browser_inline_in_event_loop
            and "java/lang/Runnable.run:()V" in browser_inline_execute
            and "io/netty/channel/DefaultEventLoop.execute" not in browser_inline_execute,
        ),
        (
            "Bootstrap connects BrowserWebSocketChannel inline before event-loop scheduling",
            "io/netty/channel/browser/BrowserWebSocketChannel.connectInline" in netty_bootstrap_connect
            and "io/netty/channel/EventLoop.execute" in netty_bootstrap_connect
            and netty_bootstrap_connect.find("BrowserWebSocketChannel.connectInline")
            < netty_bootstrap_connect.find("EventLoop.execute"),
        ),
        (
            "ReflectiveChannelFactory directly constructs BrowserWebSocketChannel",
            "io/netty/channel/browser/BrowserWebSocketChannel" in reflective_channel_factory
            and "new           #" in reflective_channel_factory
            and "io/netty/channel/browser/BrowserWebSocketChannel" in reflective_channel_factory
            and "java/lang/Class.getConstructor" in reflective_channel_factory
            and reflective_channel_factory.find("io/netty/channel/browser/BrowserWebSocketChannel")
            < reflective_channel_factory.find("java/lang/Class.getConstructor"),
        ),
        (
            "Connection.connect uses browser WebSocket channel without browser DNS",
            "io/netty/channel/browser/BrowserWebSocketChannel" in connection_connect
            and "io/netty/bootstrap/Bootstrap.disableResolver" in connection_connect
            and "io/netty/bootstrap/Bootstrap.connect:(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;" in connection_connect
            and "connect:(Ljava/lang/String;I)" not in connection_connect
            and "connect:(Ljava/net/InetAddress;I)" not in connection_connect,
        ),
        (
            "Connection.tick pumps browser WebSocket channels",
            "io/netty/channel/browser/BrowserWebSocketChannel.pumpAll:()V" in connection_tick,
        ),
        (
            "ServerAddressResolver leaves multiplayer hosts unresolved for bridge DNS",
            "java/net/InetSocketAddress.createUnresolved" in server_address_resolver_lambda
            and "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress.from" in server_address_resolver_lambda
            and "java/net/InetAddress.getByName" not in server_address_resolver_lambda,
        ),
        (
            "ServerRedirectHandler skips JVM JNDI SRV path in browser",
            "getstatic" in server_redirect_create
            and "Field EMPTY:" in server_redirect_create
            and "java/lang/Class.forName" not in server_redirect_create
            and "javax/naming/directory/InitialDirContext" not in server_redirect_create,
        ),
        (
            "ResolvedServerAddress handles unresolved browser host strings",
            "java/net/InetSocketAddress.getHostString" in resolved_server_get_host_name
            and "java/net/InetSocketAddress.getHostString" in resolved_server_get_host_ip
            and "java/net/InetAddress.getHostName" not in resolved_server_get_host_name
            and "java/net/InetAddress.getHostAddress" not in resolved_server_get_host_ip,
        ),
        (
            "Minecraft online-mode Crypt bytecode delegates key, digest, public-key, and RSA work",
            "BrowserCrypto.generateSecretKey" in crypt_generate_secret
            and "BrowserCrypto.digestData" in crypt_digest
            and "BrowserCrypto.parseRsaPublicKey" in crypt_parse_public
            and "BrowserCrypto.parseRsaPrivateKey" in crypt_parse_private
            and "BrowserCrypto.encryptUsingKey" in crypt_encrypt
            and "aconst_null" not in crypt_generate_secret
            and "iconst_0" not in crypt_digest,
        ),
        (
            "Minecraft secure-profile bytecode fetches keys and emits real RSA signatures",
            "dev/gaius/browser/BrowserSigner.create" in signer_from
            and "invokedynamic" in signed_message_encoder
            and "Field UNSIGNED" not in signed_message_encoder
            and "CompletableFuture.thenCompose" in account_prepare_key
            and "CompletableFuture.completedFuture" not in account_prepare_key,
        ),
        (
            "Minecraft packet cipher keeps vanilla AES/CFB8 setup and browser stateful updates",
            "AES/CFB8/NoPadding" in crypt_get_cipher
            and "javax/crypto/Cipher.getInstance" in crypt_get_cipher
            and "javax/crypto/Cipher.init" in crypt_get_cipher
            and "dev/gaius/browser/BrowserAesCfb8" in browser_cipher_constants
            and "dev/gaius/browser/BrowserCrypto" in browser_cipher_constants
            and "createAesCfb8" in browser_cipher_constants
            and "java/lang/System" not in browser_cipher_constants,
        ),
        (
            "Minecraft server-pack downloads use the browser HTTP bridge without Java Proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyResourcePack" in http_util_download
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in http_util_download
            and "java/net/URL.openConnection:()" in http_util_download,
        ),
        (
            "Authlib session requests use the browser HTTP bridge without Java Proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyAuthentication" in authlib_create_connection
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in authlib_create_connection
            and "java/net/URL.openConnection:()" in authlib_create_connection,
        ),
        (
            "Remote player textures use the browser HTTP bridge without Java Proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyTexture" in skin_texture_download
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in skin_texture_download
            and "java/net/URL.openConnection:()" in skin_texture_download,
        ),
        (
            "Realms API requests and authentication cookies route through the browser HTTP bridge",
            realms_request_constructor.count("dev/gaius/browser/BrowserHttpProxy.proxyRealms") == 2
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in realms_request_constructor
            and "dev/gaius/browser/BrowserHttpProxy.addRealmsCookie" in realms_request_cookie
            and "java/net/HttpURLConnection.setRequestProperty" not in realms_request_cookie,
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
            "TeaVM network zlib supports Minecraft ByteBuffer decode and reusable encoding",
            "public void setInput(java.nio.ByteBuffer);" in classlib_inflater
            and "TZipModernSupport.setInput" in classlib_inflater
            and "public int inflate(java.nio.ByteBuffer);" in classlib_inflater
            and "TZipModernSupport.inflate" in classlib_inflater
            and "java/nio/ByteBuffer.get" in classlib_zip_support
            and "java/nio/ByteBuffer.put" in classlib_zip_support
            and "public void finish();" in classlib_deflater
            and "public void reset();" in classlib_deflater,
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
            "SimpleBitStorage scalar access uses direct browser BigInt64Array operations",
            "dev/gaius/browser/BrowserBitStorage.get" in simple_bit_storage_get
            and "([JIIIJ)I" in simple_bit_storage_get
            and "Field mask:J" in simple_bit_storage_get
            and "Long_" not in simple_bit_storage_get
            and "dev/gaius/browser/BrowserBitStorage.getAndSet" in simple_bit_storage_get_and_set
            and "([JIIIIJ)I" in simple_bit_storage_get_and_set
            and "Field mask:J" in simple_bit_storage_get_and_set
            and "Long_" not in simple_bit_storage_get_and_set
            and "dev/gaius/browser/BrowserBitStorage.getAndSet" in simple_bit_storage_set
            and "([JIIIIJ)I" in simple_bit_storage_set
            and "Field mask:J" in simple_bit_storage_set
            and "Long_" not in simple_bit_storage_set,
        ),
        (
            "SimpleBitStorage.unpack calls browser bit-storage hot path before vanilla loop",
            "dev/gaius/browser/BrowserBitStorage.unpack" in simple_bit_storage_unpack
            and "([J[IIIJI)Z" in simple_bit_storage_unpack
            and "return" in simple_bit_storage_unpack
            and "public static native boolean unpack" in browser_bit_storage_class,
        ),
        (
            "Heightmap scalar storage avoids abstract minY and BitStorage calls in hot methods",
            "Field browserMinY:I" in heightmap_constructor
            and "Field browserData:[J" in heightmap_constructor
            and "Field browserValuesPerLong:I" in heightmap_constructor
            and "Field browserBits:I" in heightmap_constructor
            and "Field browserMask:J" in heightmap_constructor
            and "ChunkAccess.getMinY" in heightmap_constructor
            and all(
                "Field browserMinY:I" in section
                and "Field browserData:[J" in section
                and "dev/gaius/browser/BrowserBitStorage.get" in section
                and "([JIIIJ)I" in section
                and "ChunkAccess.getMinY" not in section
                and "SimpleBitStorage.get" not in section
                for section in (
                    heightmap_get_first_available,
                    heightmap_get_highest_taken,
                    heightmap_get_first_available_index,
                )
            )
            and "Field browserMinY:I" in heightmap_set_height
            and "Field browserData:[J" in heightmap_set_height
            and "dev/gaius/browser/BrowserBitStorage.getAndSet" in heightmap_set_height
            and "([JIIIIJ)I" in heightmap_set_height
            and "ChunkAccess.getMinY" not in heightmap_set_height
            and "SimpleBitStorage.set" not in heightmap_set_height,
        ),
        (
            "ProtoChunk block writes reuse cached heightmap arrays",
            "Field browserHeightmapStatus:Lnet/minecraft/world/level/chunk/status/ChunkStatus;"
                in proto_chunk_set_block_state
            and "Field browserHeightmaps:[Lnet/minecraft/world/level/levelgen/Heightmap;"
                in proto_chunk_set_block_state
            and "dev/gaius/browser/BrowserProtoChunk.prepareHeightmaps"
                in proto_chunk_set_block_state
            and "dev/gaius/browser/BrowserProtoChunk.updateHeightmaps"
                in proto_chunk_set_block_state
            and "java/util/EnumSet.iterator" not in proto_chunk_set_block_state
            and "java/util/Map.get" not in proto_chunk_set_block_state
            and "LevelChunkSection.setBlockState" in proto_chunk_set_block_state,
        ),
        (
            "ProtoChunk block access uses cached vertical bounds and sections",
            all(
                field in proto_chunk
                for field in (
                    "private final int browserMinY;",
                    "private final int browserMaxY;",
                    "private final int browserMinSectionY;",
                    "private final net.minecraft.world.level.chunk.LevelChunkSection[] browserSections;",
                )
            )
            and all(
                field in proto_chunk_constructor
                for field in (
                    "Field browserMinY:I",
                    "Field browserMaxY:I",
                    "Field browserMinSectionY:I",
                    "Field browserSections:[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
                )
            )
            and all(
                token in proto_chunk_constructor
                for token in (
                    "InterfaceMethod net/minecraft/world/level/LevelHeightAccessor.getMinY:()I",
                    "InterfaceMethod net/minecraft/world/level/LevelHeightAccessor.getMaxY:()I",
                    "InterfaceMethod net/minecraft/world/level/LevelHeightAccessor.getMinSectionY:()I",
                    "Field net/minecraft/world/level/chunk/ChunkAccess.sections:[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
                )
            )
            and all(
                all(
                    field in section
                    for field in (
                        "Field browserMinY:I",
                        "Field browserMaxY:I",
                        "Field browserMinSectionY:I",
                        "Field browserSections:[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
                    )
                )
                for section in (
                    proto_chunk_get_block_state,
                    proto_chunk_get_fluid_state,
                    proto_chunk_set_block_state,
                )
            )
            and all(
                forbidden not in section
                for section in (
                    proto_chunk_get_block_state,
                    proto_chunk_get_fluid_state,
                    proto_chunk_set_block_state,
                )
                for forbidden in (
                    "Method isOutsideBuildHeight:(I)Z",
                    "Method getSectionIndex:(I)I",
                    "Method getSection:(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
                    "Method net/minecraft/core/SectionPos.sectionRelative:(I)I",
                )
            )
            and "LevelChunkSection.getBlockState" in proto_chunk_get_block_state
            and "LevelChunkSection.getFluidState" in proto_chunk_get_fluid_state
            and "LevelChunkSection.setBlockState" in proto_chunk_set_block_state,
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
            "Compiled browser Math.fma remains native for TeaVM JSBody lowering",
            "public static native float fma(float, float, float);" in modern_runtime_support_class
            and "public static native double fma(double, double, double);" in modern_runtime_support_class,
        ),
        (
            "Compiled JOML fma bypasses the TeaVM static runtime method",
            "fmul" in joml_float_fma
            and "fadd" in joml_float_fma
            and "java/lang/Math.fma" not in joml_float_fma
            and "Runtime.HAS_Math_fma" not in joml_float_fma
            and "dmul" in joml_double_fma
            and "dadd" in joml_double_fma
            and "java/lang/Math.fma" not in joml_double_fma
            and "Runtime.HAS_Math_fma" not in joml_double_fma,
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
            "ByteBufferBuilder.reserve avoids unreachable BigInt overflow checks",
            byte_buffer_builder_reserve.count("ladd") == 2
            and "java/lang/Math.addExact" not in byte_buffer_builder_reserve
            and "Method ensureCapacity:(J)V" in byte_buffer_builder_reserve
            and "Field writeOffset:J" in byte_buffer_builder_reserve
            and "Field pointer:J" in byte_buffer_builder_reserve,
        ),
        (
            "Compiled section uploads reuse one MeshData vertex view per upload",
            compiled_section_upload_mesh.count("dev/gaius/browser/BrowserMeshUpload.vertexBuffer") == 4
            and "dev/gaius/browser/BrowserMeshUpload.begin" in compiled_section_upload_mesh
            and "dev/gaius/browser/BrowserMeshUpload.end" in compiled_section_upload_mesh
            and "com/mojang/blaze3d/vertex/MeshData.vertexBuffer" not in compiled_section_upload_mesh,
        ),
        (
            "Compiled browser section rendering uses direct coordinate arithmetic",
            render_section_get_block_state.count("ishr") == 3
            and render_section_get_fluid_state.count("ishr") == 3
            and render_section_get_block_entity.count("ishr") == 3
            and "SectionPos.blockToSectionCoord" not in render_section_get_block_state
            and "SectionPos.blockToSectionCoord" not in render_section_get_fluid_state
            and "SectionPos.blockToSectionCoord" not in render_section_get_block_entity
            and section_compiler_compile.count("iand") >= 3
            and "SectionPos.sectionRelative" not in section_compiler_compile,
        ),
        (
            "BrowserMemory compiled overlay has single-pass fast vertex writer",
            "public static void putFastVertex(long, float, float, float, int, float, float, int, int, float, float, float, boolean);" in browser_memory
            and "Method region:(J)Lorg/lwjgl/system/BrowserMemory$Region;" in browser_memory_fast_vertex
            and "Method offset:(J)I" in browser_memory_fast_vertex
            and "Method putFastVertexBytes:([BIFFFIFFIIFFFZ)V" in browser_memory_fast_vertex
            and browser_memory_fast_vertex.find("Method putFastVertexBytes:([BIFFFIFFIIFFFZ)V")
                < browser_memory_fast_vertex.find("java/nio/ByteBuffer.putFloat")
            and "Field org/lwjgl/system/BrowserMemory$Region.data:[B" in browser_memory_fast_vertex
            and "Field org/lwjgl/system/BrowserMemory$Region.arrayOffset:I" in browser_memory_fast_vertex,
        ),
        (
            "BrowserMemory compiled overlay caches the active virtual memory region",
            "Field cachedRegionId:I" in browser_memory_region
            and "Field cachedRegion:Lorg/lwjgl/system/BrowserMemory$Region;" in browser_memory_region
            and "java/util/Map.get" in browser_memory_region,
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
            "GlCommandEncoder uses the direct browser draw hot path",
            "BrowserOpenGL.bindBuffer:(II)V" in gl_command_encoder_draw
            and "BrowserOpenGL.drawFromBuffers:(IIIIIII)V" in gl_command_encoder_draw
            and gl_command_encoder_draw.count("GlRenderPipeline.info") == 1
            and gl_command_encoder_draw.count("GlConst.toGl") == 2
            and "GlStateManager._glBindBuffer" not in gl_command_encoder_draw
            and "GlStateManager._draw" not in gl_command_encoder_draw
            and "org/lwjgl/opengl/GL31.glDraw" not in gl_command_encoder_draw
            and "org/lwjgl/opengl/GL32.glDraw" not in gl_command_encoder_draw
            and "lmul" not in gl_command_encoder_draw,
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
            "BrowserOpenGL compiled overlay uses zero-copy Java buffer uploads",
            browser_opengl.count("Int8Array.fromJavaBuffer") >= 4,
        ),
        (
            "BrowserOpenGL compiled overlay narrows WebGL offsets before JS interop",
            "public static void drawElements(int, int, int, long);" in browser_opengl
            and "private static native void drawElementsJs(int, int, int, int);" in browser_opengl
            and "private static native void vertexAttribPointerJs(int, int, int, boolean, int, int);" in browser_opengl
            and "private static native void bindVertexBufferJs(int, int, int, int);" in browser_opengl
            and "private static native void bindBufferRangeJs(int, int, int, int, int);" in browser_opengl
            and "private static native int fenceSyncJs(int, int);" in browser_opengl,
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
            and "drawReadyGeneration" in browser_opengl_constants
            and "drawProgramGeneration" in browser_opengl_constants
            and "let attribsChecked=false" in browser_opengl_constants
            and "} else if (!attribsChecked) {" in browser_opengl_constants
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
            and "const refs=this.misalignedBufferRefs" in browser_opengl_constants
            and "return refs ? ((refs.get(id)||0)>0) : this.bufferNeedsArrayShadow(id)"
            in browser_opengl_constants
            and "bufferShadowPolicyVersion" not in browser_opengl_constants
            and "bufferShadowDecisionCache" not in browser_opengl_constants
            and "bumpBufferShadowPolicyVersion" not in browser_opengl_constants
            and "256 * 1024 * 1024" in browser_opengl_constants
            and "1024 * 1024 * 1024" in browser_opengl_constants
            and "268435456" in browser_opengl_constants
            and "misalignedBufferRefs" in browser_opengl_constants
            and "refs.get(id)" in browser_opengl_constants
            and "v.misalignedAttribBuffers.set(i,b)" in browser_opengl_constants
            and "s.misalignedBufferRefs.set(b,(n+1)|0)" in browser_opengl_constants
            and "releaseVaoMisalignedBuffers" in browser_opengl_constants
            and "this.vaoEmu.forEach(function(v)" in browser_opengl_constants
            and "markBufferShadowRequired" in browser_opengl_constants
            and "misaligned-attrib" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes draw-call throughput telemetry",
            "recordDrawCall" in browser_opengl_constants
            and "drawCallsPerSecond" in browser_opengl_constants
            and "(calls & 255) !== 0" in browser_opengl_constants
            and "__gaiusReadWebGLErrors" in browser_opengl_constants
            and "glErrors" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay uses the callback-free draw fast path",
            "executeDraw" in browser_opengl_constants
            and "currentVaoCacheId" in browser_opengl_constants
            and "currentVaoCache" in browser_opengl_constants
            and "const attribsPrepared=(vao.drawReadyGeneration|0)===slowDrawGeneration"
                in browser_opengl_constants
            and "window.__gaiusGL.executeDraw(0,mode,first,count,0,0,0);"
                in browser_opengl_constants
            and "window.__gaiusGL.executeDraw(5,mode,count,type,offset,instances,baseVertex);"
                in browser_opengl_constants
            and "window.__gaiusGL.withGuiItemOffscreenScissorRepair(function()"
                not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay exposes the direct command-encoder draw path",
            "drawFromBuffers" in browser_opengl
            and "Number(indexOffset)*Number(indexBytes)" in browser_opengl_constants
            and "state.executeDraw((instances|0)>1?2:0,mode,firstOrBaseVertex,count,instances,0,0);"
                in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay bypasses stable world-draw cleanup",
            "if (!guiDraw && !repairOffscreenScissor)" in browser_opengl_constants
            and "if (attribsReady)"
                in browser_opengl_constants
            and "case 0: gl.drawArrays(mode,a|0,b|0); break;"
                in browser_opengl_constants
            and browser_opengl_constants.find(
                "if (!guiDraw && !repairOffscreenScissor)"
            )
            < browser_opengl_constants.find("let failed=true;")
            and "drawAttribPreparedDirectDirty" not in browser_opengl_constants
            and "drawAttribPreparedMisalignedCount" not in browser_opengl_constants
            and "if(had!==!!m)v.drawReadyGeneration=-1"
                in browser_opengl_constants
            and "drawAttribPreparedProgram" not in browser_opengl_constants
            and "drawAttribPreparedVersion" not in browser_opengl_constants
            and "drawAttribPreparedGlobalVersion" not in browser_opengl_constants
            and "const directDirty=vao.directAttribDirty ? 1 : 0"
                not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay disables normal hot-path diagnostics",
            "hotPathTelemetryEnabled" in browser_opengl_constants
            and "__performanceStateInit" in browser_opengl_constants
            and "params.get('glStats')==='1'" in browser_opengl_constants
            and "diag==='perf'" in browser_opengl_constants
            and "if (this.hotPathTelemetryEnabled) this.recordDrawCall();"
                in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay caches fixed-function and texture state",
            "textureBufferDefaults" in browser_opengl_constants
            and "state.textureBindings.has(webKey)" in browser_opengl_constants
            and "if (!alreadyBound) gl.bindTexture(webTarget,object);"
                in browser_opengl_constants
            and "state.textureBufferDefaults.add(texture|0);"
                in browser_opengl_constants
            and "if (state && (state.activeTextureUnit|0)===next) return;"
                in browser_opengl_constants
            and "colorMaskBits" in browser_opengl_constants
            and "state.viewportX===(x|0)" in browser_opengl_constants
            and "state.scissorX===(x|0)" in browser_opengl_constants
            and "state.blendSourceRgb!==undefined" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay caches draw capabilities and framebuffer classification",
            "__drawStateCacheInit" in browser_opengl_constants
            and "state.enabledCapBits=0" in browser_opengl_constants
            and "state.capabilityBit=function(capability)" in browser_opengl_constants
            and "state.offscreen512Framebuffers=new Set()" in browser_opengl_constants
            and "state.setDrawFramebufferCache=function(framebuffer)"
                in browser_opengl_constants
            and "state.refreshFramebufferOffscreen512=function(framebuffer)"
                in browser_opengl_constants
            and "state.refreshFramebuffersForTexture=function(texture)"
                in browser_opengl_constants
            and "const capBits=this.enabledCapBits|0" in browser_opengl_constants
            and "(capBits & 1)!==0" in browser_opengl_constants
            and "(capBits & 2)!==0" in browser_opengl_constants
            and "this.drawFramebufferOffscreenKnown" in browser_opengl_constants
            and "&& this.enabledCaps.has(gl.CULL_FACE)" not in browser_opengl_constants
            and "&& this.enabledCaps.has(gl.SCISSOR_TEST)" not in browser_opengl_constants
            and "if (!physicallyDisabled) window.__gaiusWebGL.disable(capability);"
                in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay keeps texture upload telemetry allocation-light",
            "kind==='texImage2D' && (level|0)===0" in browser_opengl_constants
            and "if (!this.hotPathTelemetryEnabled) return;" in browser_opengl_constants
            and "stats.textureInfo[String(texture)]=info" in browser_opengl_constants
            and "Array.from(this.textureInfo.entries())" not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay caches repeated WebGL binding state",
            "textureParameters" in browser_opengl_constants
            and "samplerBindings" in browser_opengl_constants
            and "indexedBufferBindings" in browser_opengl_constants
            and "target===gl.FRAMEBUFFER" in browser_opengl_constants
            and "state.samplerBindings.has(unit|0)" in browser_opengl_constants
            and "previous.range===true" in browser_opengl_constants
            and "previous.range===false" in browser_opengl_constants
            and "const previousId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0"
                in browser_opengl_constants
            and "gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING)"
                not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay uses numeric hot-path binding keys",
            "const keyBase=unit*65536" in browser_opengl_constants
            and "keyBase+((target|0)&65535)" in browser_opengl_constants
            and "keyBase+(gl.TEXTURE_2D&65535)" in browser_opengl_constants
            and "const key=(target|0)*65536+(index|0)" in browser_opengl_constants
            and "unit + ':' + target" not in browser_opengl_constants
            and "(target|0)+':'+(index|0)" not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay keeps base-vertex cache allocation-light",
            "cacheShiftedIndexBuffer=function(vao,type,offset,count,baseVertex)"
            in browser_opengl_constants
            and "const cached=vao.shiftedIndexLast" in browser_opengl_constants
            and "cached && !cached.deleted" in browser_opengl_constants
            and "vao.shiftedIndexLast=entry" in browser_opengl_constants
            and "oldest.deleted=true" in browser_opengl_constants
            and "if (this.guiDrawDiagnostics && (this.guiDrawsRemaining|0)>0)"
            in browser_opengl_constants
            and "this.baseVertexExtensionChecked" in browser_opengl_constants
            and "const stats=this.hotPathTelemetryEnabled" in browser_opengl_constants
            and "drawElementsWithBaseVertex=function(vao,mode,count,type,offset,instances,baseVertex)"
            in browser_opengl_constants
            and "const vao=this.getVaoEmu();" not in browser_opengl_constants[
                browser_opengl_constants.find("drawElementsWithBaseVertex=function") :
                browser_opengl_constants.find(
                    "cacheShiftedIndexBuffer(vao,type,off,count,base)",
                    browser_opengl_constants.find("drawElementsWithBaseVertex=function"),
                )
            ],
        ),
        (
            "BrowserOpenGL compiled overlay caches alternating base-vertex draws numerically",
            "shiftedIndexFastCache:new Map()" in browser_opengl_constants
            and "Math.imul((fastKey^(type|0))|0,16777619)" in browser_opengl_constants
            and "fastEntry.offset===start" in browser_opengl_constants
            and "(fastEntry.inputCount|0)===length" in browser_opengl_constants
            and "(fastEntry.base|0)===base" in browser_opengl_constants
            and "fastCache.size >= 64" in browser_opengl_constants
            and "baseVertexIndexFastCacheHits" in browser_opengl_constants
            and "this.cacheShiftedIndexBuffer(vao,type,off,count,base)"
            in browser_opengl_constants
            and browser_opengl_constants.find(
                "const fastEntry=fastCache.get(fastKey)",
                browser_opengl_constants.find("cacheShiftedIndexBuffer=function"),
            )
            < browser_opengl_constants.find(
                "const source=this.bufferBytes.get(elementBuffer)",
                browser_opengl_constants.find("cacheShiftedIndexBuffer=function"),
            )
            and browser_opengl_constants.find(
                "const cached=vao.shiftedIndexLast",
                browser_opengl_constants.find("cacheShiftedIndexBuffer=function"),
            )
            < browser_opengl_constants.find(
                "const source=this.bufferBytes.get(elementBuffer)",
                browser_opengl_constants.find("cacheShiftedIndexBuffer=function"),
            )
            and "Math.imul((fastKey^(version|0))|0,16777619)"
            not in browser_opengl_constants
            and "(cached.version|0)===(version|0)" not in browser_opengl_constants
            and "(fastEntry.version|0)===(version|0)" not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay invalidates only per-buffer derived caches",
            "alignedAttribCacheKeys:new Map()" in browser_opengl_constants
            and "shiftedIndexCacheKeys:new Map()" in browser_opengl_constants
            and "dropBufferDerivedCaches" in browser_opengl_constants
            and "registerBufferCacheKey" in browser_opengl_constants
            and "forgetBufferCacheKey" in browser_opengl_constants
            and "alignedAttribCache.forEach" not in browser_opengl_constants
            and "shiftedIndexCache.forEach" not in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay defers physical element-buffer restores safely",
            "actualElementArrayBuffer:null" in browser_opengl_constants
            and "elementArrayBufferObject:null" in browser_opengl_constants
            and "bindPhysicalElementBuffer=function(vao, buffer)"
            in browser_opengl_constants
            and "ensureLogicalElementBuffer=function(vao)" in browser_opengl_constants
            and "this.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null)"
            in browser_opengl_constants
            and "const buffer=id ? this.buffers.get(id) : null;"
            not in browser_opengl_constants
            and "forgetPhysicalElementBuffer=function(buffer)" in browser_opengl_constants
            and "this.bindPhysicalElementBuffer(vao,shiftedIndex.buffer);"
            in browser_opengl_constants
            and "gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,originalObject || null);"
            not in browser_opengl_constants
            and browser_opengl_constants.count(
                "this.ensureLogicalElementBuffer(vao); gl.drawElements"
            )
            >= 2
            and browser_opengl_constants.count(
                "state.ensureLogicalElementBuffer(state.getVaoEmu());"
            )
            >= 4
            and "sourceTarget===gl.ELEMENT_ARRAY_BUFFER || targetTarget===gl.ELEMENT_ARRAY_BUFFER"
            in browser_opengl_constants
            and "state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER,vao.elementArrayBuffer|0)"
            in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay reuses hot-path binding records",
            "const object=(!alreadyBound || defaultsCandidate)"
            in browser_opengl_constants
            and "if (!alreadyBound) state.textureBindings.set(webKey,texture|0)"
            in browser_opengl_constants
            and "const aliasKey=keyBase+35882" in browser_opengl_constants
            and "let previous=state.indexedBufferBindings.get(key)"
            in browser_opengl_constants
            and "previous.range=true" in browser_opengl_constants
            and "state.indexedBufferBindings.set(key,{"
            not in browser_opengl_constants
            and "if ((vao.elementArrayBuffer|0)===nextId)"
            in browser_opengl_constants
            and "state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null)"
            in browser_opengl_constants
            and "const object=nextId===0?null:state.buffers.get(nextId)"
            in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay caches scalar uniforms safely",
            "uniform1iValues" in browser_opengl_constants
            and "uniform1fValues" in browser_opengl_constants
            and "programUniformLocations" in browser_opengl_constants
            and "state.uniform1iValues.has(location|0)" in browser_opengl_constants
            and "Object.is(state.uniform1fValues.get(location|0),value)"
                in browser_opengl_constants
            and "state.uniformValueCache.delete(location|0)" in browser_opengl_constants
            and "this.uniform1fValues.delete(key)" in browser_opengl_constants
            and "this.uniform1iValues.delete(key)" in browser_opengl_constants
            and "state.clearProgramUniforms(program|0)" in browser_opengl_constants,
        ),
        (
            "BrowserOpenGL compiled overlay caches vector and matrix uniforms",
            "uniformScalarsChanged=function(location,kind,count,x,y,z,w)"
            in browser_opengl_constants
            and "uniformArrayChanged=function(location,kind,transpose,values)"
            in browser_opengl_constants
            and "Object.is(cached[i],values[i])" in browser_opengl_constants
            and "new Float64Array(count|0)" in browser_opengl_constants
            and "values:integer ? new Int32Array(length) : new Float32Array(length)"
            in browser_opengl_constants
            and browser_opengl_constants.count("state.uniformArrayChanged(location,") >= 11
            and "uniformValueFastSkips" in browser_opengl_constants
            and "UNIFORM_SCRATCH" in browser_opengl,
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
            and "repairOffscreenScissor" in browser_opengl_constants
            and "const repairOffscreenScissor=drawFramebuffer!==0" in browser_opengl_constants
            and "if (failed && repairOffscreenScissor)" in browser_opengl_constants
            and "withGuiItemOffscreenScissorRepair=function(draw)"
                not in browser_opengl_constants
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
            and "window.__gaiusGL.executeDraw(1,mode,count,type,offset,0,0);"
                in browser_opengl_constants,
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
            and "private static native void swapBuffersJs();" in browser_glfw
            and "gameFps" in browser_glfw_constants
            and "gameFrames" in browser_glfw_constants
            and "gameLastSampleAt" in browser_glfw_constants,
        ),
        (
            "BrowserGlfw compiled overlay reserves the final timer millisecond",
            "public static void waitEventsTimeout(double);" in browser_glfw
            and "sleepForBrowserMillis" in browser_glfw
            and "java/lang/Thread.sleep:(J)V" in browser_glfw
            and "spinForBrowserSeconds" not in browser_glfw
            and "lsub" in browser_glfw
            and "java/lang/Thread.yield:()V" in browser_glfw,
        ),
        (
            "Compiled browser frame pacing retains the measured vanilla runTick yield",
            "org/lwjgl/glfw/GLFW.glfwWaitEventsTimeout:(D)V" in render_system_limit_fps
            and "java/lang/Thread.yield:()V" not in render_system_limit_fps
            and render_system_limit_fps.count("org/lwjgl/glfw/GLFW.glfwGetTime:()D") == 2
            and "goto" in render_system_limit_fps
            and minecraft_run_tick.count("java/lang/Thread.yield:()V") == 1
            and "org/lwjgl/glfw/BrowserGlfw.yieldAfterFrame" not in minecraft_run_tick,
        ),
        (
            "Compiled browser frame pacing compensates sub-frame timer overshoot",
            "browserCompensateFrameTime:(DDI)D" in render_system_limit_fps
            and "ddiv" in render_system_compensate_frame_time
            and "dsub" in render_system_compensate_frame_time
            and "ifge" in render_system_compensate_frame_time
            and render_system_compensate_frame_time.count("dreturn") == 2,
        ),
        (
            "BrowserGlfw compiled overlay primes cursor callbacks",
            "setCursorPosCallback" in browser_glfw
            and "GLFWCursorPosCallbackI.invoke:(JDD)V" in browser_glfw,
        ),
        (
            "BrowserGlfw compiled overlay records input callback telemetry",
            "__gaiusInputStats" in browser_glfw_constants
            and "glfwMouseButton" in browser_glfw_constants
            and "button === 2 ? 1" in browser_glfw_constants
            and "reportInputEvent" in browser_glfw
            and "reportCallback" in browser_glfw
            and "reportMouseHandlerEntry" in browser_glfw
            and "reportMouseHandlerDispatch" in browser_glfw
            and "reportMouseClickedResult" in browser_glfw
            and "GLFWMouseButtonCallbackI.invoke:(JIII)V" in browser_glfw,
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
            "MouseHandler browser callbacks dispatch synchronously",
            "onMove:(JDD)V" in mouse_setup_move
            and "Minecraft.execute" not in mouse_setup_move
            and "MouseButtonInfo.\"<init>\":(II)V" in mouse_setup_button
            and "onButton:(JLnet/minecraft/client/input/MouseButtonInfo;I)V" in mouse_setup_button
            and "Minecraft.execute" not in mouse_setup_button
            and "onScroll:(JDD)V" in mouse_setup_scroll
            and "Minecraft.execute" not in mouse_setup_scroll,
        ),
        (
            "MouseHandler browser button path reports scaled click telemetry",
            "BrowserGlfw.reportMouseHandlerEntry:(JJII)V" in mouse_handler
            and "BrowserGlfw.reportMouseHandlerDispatch:(DDZLjava/lang/Object;)V" in mouse_handler
            and "BrowserGlfw.reportMouseClickedResult:(ZDDLjava/lang/Object;)V" in mouse_handler,
        ),
        (
            "MouseHandler browser clicks pass through fading LoadingOverlay when a screen is visible",
            "net/minecraft/client/gui/screens/LoadingOverlay" in mouse_handler
            and "instanceof" in mouse_handler
            and "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;" in mouse_handler,
        ),
        (
            "KeyboardHandler browser callbacks dispatch synchronously",
            "KeyEvent.\"<init>\":(III)V" in keyboard_setup_key
            and "keyPress:(JILnet/minecraft/client/input/KeyEvent;)V" in keyboard_setup_key
            and "Minecraft.execute" not in keyboard_setup_key
            and "CharacterEvent.\"<init>\":(II)V" in keyboard_setup_char
            and "charTyped:(JLnet/minecraft/client/input/CharacterEvent;)V" in keyboard_setup_char
            and "Minecraft.execute" not in keyboard_setup_char,
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
            "BrowserWebSocketChannel.pumpAll" in minecraft_run_tick
            and "PacketProcessor.processQueuedPackets" in minecraft_run_tick
            and "client.processQueuedPacketsForcedTick" in minecraft_run_tick
            and minecraft_run_tick.find("BrowserWebSocketChannel.pumpAll")
                < minecraft_run_tick.find("PacketProcessor.processQueuedPackets")
            and minecraft_run_tick.find("ifne")
                < minecraft_run_tick.find("PacketProcessor.processQueuedPackets"),
        ),
        (
            "Minecraft compiled overlay throttles browser state diagnostics",
            "BrowserOpenGL.shouldReportMinecraftState" in minecraft_run_tick
            and "BrowserOpenGL.reportMinecraftState" in minecraft_run_tick
            and minecraft_run_tick.find("BrowserOpenGL.shouldReportMinecraftState")
                < minecraft_run_tick.find("BrowserOpenGL.reportMinecraftState")
            and "ifeq" in minecraft_run_tick
            and "shouldReportMinecraftState" in browser_opengl,
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
            "LevelLoadingScreen keeps progress UI without rebuilding the chunk grid",
            "public static void renderChunks" in level_loading_render_chunks
            and "0: return" in level_loading_render_chunks
            and "GuiGraphics.fill" not in level_loading_render_chunks
            and "ChunkLoadStatusView.get" not in level_loading_render_chunks,
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
            "ClientLevel compiled overlay preserves block break particles and progress",
            "public void addDestroyBlockEffect(net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);"
            in client_level_add_destroy_block_effect
            and "lambda$addDestroyBlockEffect" in client_level
            and "TerrainParticle" in client_level
            and "ParticleEngine.add" in client_level
            and "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);"
            in client_level_destroy_block_progress
            and "LevelRenderer.destroyBlockProgress" in client_level_destroy_block_progress
            and " 0: return" not in client_level_add_destroy_block_effect
            and " 0: return" not in client_level_destroy_block_progress,
        ),
        (
            "LevelRenderer compiled overlay preserves block break progress tracking",
            "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);"
            in level_destroy_block_progress
            and "BlockDestructionProgress" in level_destroy_block_progress
            and "destroyingBlocks" in level_destroy_block_progress
            and "destructionProgress" in level_destroy_block_progress
            and " 0: return" not in level_destroy_block_progress,
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
            "SectionRenderDispatcher compiled overlay defers compilation and limits per-frame uploads",
            "public void uploadAllPendingUploads();" in section_uploads
            and section_uploads.count("Queue.poll") >= 2
            and "Runnable.run" in section_uploads
            and "SectionMesh.close" in section_uploads
            and "if_icmpge" in section_uploads
            and "goto" in section_uploads
            and "BrowserRenderScheduler.defer" in section_dispatcher_constructor
            and "BrowserRenderScheduler.defer" in section_dispatcher_run_task,
        ),
        (
            "IntegratedServer follows client display-distance options",
            "public void tickServer(java.util.function.BooleanSupplier);" in integrated_tick
            and "Options.renderDistance" in integrated_tick
            and "Options.simulationDistance" in integrated_tick
            and "Math.max" in integrated_tick
            and "PlayerList.setViewDistance" in integrated_tick
            and "PlayerList.setSimulationDistance" in integrated_tick
            and "pop" not in integrated_tick[
                integrated_tick.find("Options.renderDistance"):integrated_tick.find("Options.simulationDistance")
            ]
            and "pop" not in integrated_tick[
                integrated_tick.find("Options.simulationDistance"):integrated_tick.find("Options.simulationDistance") + 500
            ],
        ),
        (
            "PlayerList distance getters return configured server distances",
            "public int getViewDistance();" in player_view_distance
            and "public int getSimulationDistance();" in player_sim_distance
            and "Field viewDistance:I" in player_view_distance
            and "Field simulationDistance:I" in player_sim_distance
            and "iconst_2" not in player_view_distance
            and "pop" not in player_view_distance
            and "iconst_2" not in player_sim_distance
            and "pop" not in player_sim_distance,
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
            "MinecraftServer chooses browser spawn height without generating a full chunk",
            "server.browserFastInitialSpawn" in minecraft_initial_spawn
            and "Climate$Sampler.findSpawnPosition" in minecraft_initial_spawn
            and "ChunkGenerator.getBaseHeight" in minecraft_initial_spawn
            and "Heightmap$Types.MOTION_BLOCKING_NO_LEAVES" in minecraft_initial_spawn
            and "ChunkPos.getMiddleBlockX" in minecraft_initial_spawn
            and "ChunkPos.getMiddleBlockZ" in minecraft_initial_spawn
            and "PlayerSpawnFinder.getSpawnPosInChunk" not in minecraft_initial_spawn
            and "ServerLevel.getHeightmapPos" not in minecraft_initial_spawn
            and "BlockPos.ZERO" not in minecraft_initial_spawn
        ),
        (
            "Browser spawn preparation retains neighbors but waits only for the center",
            "Vec3.atBottomCenterOf" in player_find_spawn
            and "CompletableFuture.completedFuture" in player_find_spawn
            and "PlayerSpawnFinder.getSpawnPosInChunk" not in player_find_spawn
            and "TicketType.PLAYER_SPAWN" in prepare_spawn_load_chunks
            and "iconst_1" in prepare_spawn_load_chunks
            and "iconst_3" not in prepare_spawn_load_chunks
            and "ServerChunkCache.addTicketWithRadius" in prepare_spawn_load_chunks
            and "ChunkStatus.FULL" in prepare_spawn_load_chunks
            and "ServerChunkCache.getChunkFuture" in prepare_spawn_load_chunks
            and "ServerChunkCache.addTicketAndLoadWithRadius" not in prepare_spawn_load_chunks,
        ),
        (
            "Worker-local configuration cannot time out while its first chunk is synchronous",
            "BrowserIntegratedServerMain.isWorkerServer" in server_common_is_singleplayer_owner
            and "iconst_1" in server_common_is_singleplayer_owner
            and "MinecraftServer.isSingleplayerOwner" in server_common_is_singleplayer_owner
            and server_common_is_singleplayer_owner.find("BrowserIntegratedServerMain.isWorkerServer")
                < server_common_is_singleplayer_owner.find("MinecraftServer.isSingleplayerOwner"),
        ),
        (
            "Integrated server pumps idle MessagePort actions before processing each tick",
            "BrowserWorldgenScheduler.checkpoint" in minecraft_run_server
            and "processPacketsAndTick:(Z)V" in minecraft_run_server
            and minecraft_run_server.find("BrowserWorldgenScheduler.checkpoint")
                < minecraft_run_server.find("processPacketsAndTick:(Z)V")
            and "BrowserWorldgenScheduler.checkpoint" not in minecraft_process_packets_and_tick
            and "BrowserWebSocketChannel.pumpAll" in minecraft_process_packets_and_tick
            and "PacketProcessor.processQueuedPackets" in minecraft_process_packets_and_tick
            and minecraft_process_packets_and_tick.find("BrowserWebSocketChannel.pumpAll")
                < minecraft_process_packets_and_tick.find("PacketProcessor.processQueuedPackets"),
        ),
        (
            "Worker block-break progress accounts for low TPS and queued STOP packets",
            "private long browserDestroyStartMillis;" in server_player_game_mode
            and "System.currentTimeMillis" in server_player_handle_break
            and "BrowserIntegratedServerMain.adjustDestroyTicks" in server_player_handle_break
            and "BrowserIntegratedServerMain.completeLocalDestroyProgress"
                in server_player_handle_break
            and "System.currentTimeMillis" in browser_adjust_destroy_ticks
            and "ldc2_w        #" in browser_adjust_destroy_ticks
            and "long 50l" in browser_adjust_destroy_ticks
            and "Math.max" in browser_adjust_destroy_ticks
            and "Method isWorkerRuntime:()Z" in browser_complete_destroy_progress
            and "float 0.7f" in browser_complete_destroy_progress
            and "Math.max" in browser_complete_destroy_progress,
        ),
        (
            "First chunk acknowledgement starts a ring-by-ring singleplayer distance ramp",
            "BrowserIntegratedServerMain.minimumServerViewDistance"
                in chunk_map_set_view_distance
            and "bipush        32" in chunk_map_set_view_distance
            and "Mth.clamp" in chunk_map_set_view_distance
            and "BrowserIntegratedServerMain.activateConfiguredDistances" in server_game_chunk_batch
            and "PlayerChunkSender.onChunkBatchReceivedByClient" in server_game_chunk_batch
            and server_game_chunk_batch.find("PlayerChunkSender.onChunkBatchReceivedByClient")
                < server_game_chunk_batch.find("activateConfiguredDistances")
            and "configuredDistancesActive" in browser_activate_distances
            and "Math.min" in browser_activate_distances
            and "applyActiveDistances" in browser_activate_distances
            and "activeViewDistance" in browser_advance_distances
            and "applyActiveDistances" in browser_advance_distances
            and "BrowserIntegratedServerMain.advanceConfiguredDistances" in minecraft_run_server
            and minecraft_run_server.find("processPacketsAndTick:(Z)V")
                < minecraft_run_server.find("advanceConfiguredDistances"),
        ),
        (
            "Compiled Server Worker yields only outside worldgen loop markers",
            "Thread.yield" in worldgen_checkpoint
            and "BrowserWebSocketChannel.pumpAll" not in worldgen_checkpoint
            and "0: return" in worldgen_pulse
            and "Thread.yield" not in worldgen_pulse,
        ),
        (
            "ChunkGeneratorStructureState keeps ring candidates without blocking biome searches",
            "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$5" in browser_ring_position
            and "net/minecraft/world/level/ChunkPos.\"<init>\":(II)V" in browser_ring_position
            and "BiomeSource.findBiomeHorizontal" not in browser_ring_position
            and "Climate$Sampler" not in browser_ring_position,
        ),
        (
            "BlockPos packed coordinate bytecode delegates without long helper operations",
            "BrowserBlockPos.getX" in block_pos_get_x
            and "BrowserBlockPos.getY" in block_pos_get_y
            and "BrowserBlockPos.getZ" in block_pos_get_z
            and all(
                opcode not in section
                for section in (block_pos_get_x, block_pos_get_y, block_pos_get_z)
                for opcode in ("lshl", "lshr", "l2i")
            )
            and "BrowserBlockPos.asLong" in block_pos_as_long
            and all(
                opcode not in block_pos_as_long
                for opcode in ("i2l", "land", "lshl", "lor")
            ),
        ),
        (
            "BiomeManager.getBiome delegates only nearest-corner math to the browser hot path",
            "dev/gaius/browser/BrowserBiomeManager.nearestCorner" in biome_manager_get_biome
            and "(JIII)I" in biome_manager_get_biome
            and "BiomeManager$NoiseBiomeSource.getNoiseBiome" in biome_manager_get_biome
            and "LinearCongruentialGenerator.next" not in biome_manager_get_biome
            and "getFiddledDistance" not in biome_manager_get_biome
            and "Mth.square" not in biome_manager_get_biome
            and "Long_" not in biome_manager_get_biome,
        ),
        (
            "Aquifer computeSubstance uses the warmed batch before the vanilla miss path",
            "dev/gaius/browser/BrowserAquifer.selectNearestCached" in aquifer_compute_substance
            and "([JIIIIIIII[I)Z" in aquifer_compute_substance
            and "Field browserNearestResult:[I" in aquifer_compute_substance
            and aquifer_compute_substance.find("BrowserAquifer.selectNearestCached")
                < aquifer_compute_substance.find("getAquiferStatus")
            and "newarray       int" in aquifer_constructor
            and "Field browserNearestResult:[I" in aquifer_constructor,
        ),
        (
            "Beardifier compute uses packed arrays without iterator or enum allocation chains",
            "dev/gaius/browser/BrowserBeardifier.compute" in beardifier_compute
            and "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;III)D"
                in beardifier_compute
            and "Field browserPackedPieces:[I" in beardifier_compute
            and "Field browserPackedJunctions:[I" in beardifier_compute
            and "java/util/List.iterator" not in beardifier_compute
            and "Beardifier$Rigid.box" not in beardifier_compute
            and "TerrainAdjustment.ordinal" not in beardifier_compute
            and "Mth.clampedMap" not in beardifier_compute
            and "BrowserBeardifier.packPieces" in beardifier_constructor
            and "BrowserBeardifier.packJunctions" in beardifier_constructor
            and "Field browserPackedPieces:[I" in beardifier_constructor
            and "Field browserPackedJunctions:[I" in beardifier_constructor,
        ),
        (
            "NoiseBasedChunkGenerator keeps synchronous browser hooks inside full terrain generation",
            "BrowserWorldgenScheduler.checkpoint" not in noise_do_fill
            and noise_do_fill.count("BrowserWorldgenScheduler.pulse") >= 2
            and "NoiseChunk.getInterpolatedState" in noise_do_fill
            and "LevelChunkSection.setBlockState" in noise_do_fill
            and "Heightmap.update" in noise_do_fill,
        ),
        (
            "NoiseBasedChunkGenerator caches chunk-invariant debug and default-block values",
            noise_do_fill.count("SharedConstants.debugVoidTerrain") == 1
            and noise_do_fill.count("NoiseGeneratorSettings.defaultBlock") == 1
            and noise_do_fill.find("SharedConstants.debugVoidTerrain")
                < noise_do_fill.find("NoiseChunk.initializeForFirstCellX")
            and noise_do_fill.find("NoiseGeneratorSettings.defaultBlock")
                < noise_do_fill.find("NoiseChunk.initializeForFirstCellX"),
        ),
        (
            "ImprovedNoise compiled overlay delegates its complete public sample without array copies",
            "BrowserImprovedNoise.noise" in improved_noise_noise
            and "([BDDDDDDDD)D" in improved_noise_noise
            and "Mth.floor" not in improved_noise_noise
            and "Field p:[B" in improved_noise_noise,
        ),
        (
            "ImprovedNoise compiled overlay keeps its private interpolation hot path",
            "BrowserImprovedNoise.sampleAndLerp" in improved_noise_sample
            and "([BIIIDDDD)D" in improved_noise_sample
            and "Mth.lerp3" not in improved_noise_sample
            and "SimplexNoise.dot" not in improved_noise_sample
            and "getfield" in improved_noise_sample
            and "Field p:[B" in improved_noise_sample,
        ),
        (
            "Runtime terrain carvers keep synchronous browser hooks without removing carving",
            "BrowserWorldgenScheduler.pulse" in noise_apply_carvers
            and "ConfiguredWorldCarver.carve" in noise_apply_carvers
            and "BiomeGenerationSettings.getCarvers" in noise_apply_carvers,
        ),
        (
            "NoiseChunk keeps browser hooks inside density slice creation",
            "BrowserWorldgenScheduler.pulse" in noise_fill_slice
            and "NoiseInterpolator.fillArray" in noise_fill_slice
            and "BrowserWorldgenScheduler.pulse" in noise_fill_direct
            and "DensityFunction.compute" in noise_fill_direct,
        ),
        (
            "NoiseChunk interpolation uses a cached array in per-block update loops",
            "BrowserWorldgenScheduler.pulse" in noise_select_cell_yz
            and "BrowserNoiseInterpolator.lerp3" in noise_interpolator_compute
            and "Mth.lerp3" not in noise_interpolator_compute
            and all(
                "browserInterpolators" in section
                and "aaload" in section
                and "java/util/Iterator" not in section
                and "BrowserWorldgenScheduler.pulse" not in section
                for section in (noise_update_y, noise_update_x, noise_update_z)
            ),
        ),
        (
            "NoiseInterpolator updates use direct exact lerp arithmetic",
            all(
                "Mth.lerp" not in section
                and section.count("dsub") == expected
                and section.count("dmul") == expected
                and section.count("dadd") == expected
                for section, expected in (
                    (noise_interpolator_update_y, 4),
                    (noise_interpolator_update_x, 2),
                    (noise_interpolator_update_z, 1),
                )
            ),
        ),
        (
            "NoiseChunk cache counters use native ints instead of boxed Java longs",
            "int interpolationCounter;" in noise_chunk
            and "int arrayInterpolationCounter;" in noise_chunk
            and "long interpolationCounter;" not in noise_chunk
            and "private int lastCounter;" in noise_chunk_cache_once
            and "private int lastArrayCounter;" in noise_chunk_cache_once
            and "if_icmp" in noise_cache_compute
            and "if_icmp" in noise_cache_fill_array
            and "iadd" in noise_context_for_index
            and "ladd" not in noise_context_for_index,
        ),
        (
            "PerlinNoise hot loop uses cached primitive amplitudes and browser wrap",
            "private final double[] browserAmplitudes;" in perlin_noise
            and "dev/gaius/browser/BrowserPerlinNoise.copyAmplitudes" in perlin_noise_constructor
            and "Field browserAmplitudes:[D" in perlin_noise_constructor
            and "Field browserAmplitudes:[D" in perlin_noise_get_value
            and "daload" in perlin_noise_get_value
            and "DoubleList.getDouble" not in perlin_noise_get_value
            and "dev/gaius/browser/BrowserPerlinNoise.wrap" in perlin_noise_wrap
            and "net/minecraft/util/Mth.lfloor" not in perlin_noise_wrap
            and "d2l" not in perlin_noise_wrap,
        ),
        (
            "Climate tree distance reuses prepared bounds without TeaVM wrappers",
            "private final double[] browserBounds;" in climate_rtree_node
            and "dev/gaius/browser/BrowserClimate.prepareBounds" in climate_rtree_constructor
            and "Field browserBounds:[D" in climate_rtree_constructor
            and "dev/gaius/browser/BrowserClimate.distance" in climate_rtree_distance
            and "([D[J)J" in climate_rtree_distance
            and "Field browserBounds:[D" in climate_rtree_distance
            and "Climate$Parameter.min" not in climate_rtree_distance
            and "Climate$Parameter.max" not in climate_rtree_distance
            and "l2d" not in climate_rtree_distance
            and "d2l" not in climate_rtree_distance
            and "lmul" not in climate_rtree_distance
            and "ladd" not in climate_rtree_distance,
        ),
        (
            "Biome climate tree search retains browser hook coverage during sampling",
            "BrowserWorldgenScheduler.pulse" in climate_rtree_search
            and "Climate$DistanceMetric.distance" in climate_rtree_search
            and "Climate$RTree$Node.search" in climate_rtree_search,
        ),
        (
            "Surface rules retain browser hook coverage while preserving surface application",
            surface_build.count("BrowserWorldgenScheduler.pulse") >= 1
            and "SurfaceRules$SurfaceRule.tryApply" in surface_build
            and "SurfaceRules$Context.updateY" in surface_build,
        ),
        (
            "Biome decoration retains browser hook coverage while preserving placed features",
            chunk_apply_biome_decoration.count("BrowserWorldgenScheduler.pulse") >= 1
            and "PlacedFeature.placeWithBiomeCheck" in chunk_apply_biome_decoration
            and "StructureManager.shouldGenerateStructures" in chunk_apply_biome_decoration,
        ),
        (
            "World carvers retain browser hook coverage while preserving block carving",
            world_carve_ellipsoid.count("BrowserWorldgenScheduler.pulse") >= 1
            and "Method carveBlock" in world_carve_ellipsoid
            and "CarvingMask.set" in world_carve_ellipsoid,
        ),
        (
            "Lighting propagation retains browser hook coverage in both queue directions",
            light_propagate_increases.count("BrowserWorldgenScheduler.pulse") >= 1
            and light_propagate_decreases.count("BrowserWorldgenScheduler.pulse") >= 1
            and "Method propagateIncrease" in light_propagate_increases
            and "Method propagateDecrease" in light_propagate_decreases,
        ),
        (
            "LevelChunkSection retains browser hook coverage while preserving biome sampling",
            "BrowserWorldgenScheduler.checkpoint" not in section_fill_biomes
            and section_fill_biomes.count("BrowserWorldgenScheduler.pulse") >= 1
            and "BiomeResolver.getNoiseBiome" in section_fill_biomes
            and "PalettedContainer.getAndSetUnchecked" in section_fill_biomes,
        ),
        (
            "ChunkGenerationTask retains hooks between synchronous generation stages",
            "BrowserWorldgenScheduler.checkpoint" in generation_run_until_wait
            and "Method scheduleNextLayer:()V" in generation_run_until_wait
            and generation_run_until_wait.find("BrowserWorldgenScheduler.checkpoint")
                < generation_run_until_wait.find("Method scheduleNextLayer:()V"),
        ),
        (
            "Mining hit sounds remain periodic and browser-audible",
            "SoundType.getHitSound" in multiplayer_continue_destroy
            and "SoundManager.play" in multiplayer_continue_destroy
            and "float 4.0f" in multiplayer_continue_destroy
            and "float 8.0f" not in multiplayer_continue_destroy,
        ),
        (
            "Each rendered frame updates the shared block target after refreshing its camera",
            "Method pick:(F)V" in game_render_level
            and "Method extractCamera:(F)V" in game_render_level
            and "BrowserTargeting.stabilizeBlockHit" in game_render_level
            and "Minecraft.hitResult" in game_render_level
            and "Method shouldRenderBlockOutline:()Z" in game_render_level
            and game_render_level.find("Method pick:(F)V")
                < game_render_level.find("Method shouldRenderBlockOutline:()Z")
            and game_render_level.find("Method shouldRenderBlockOutline:()Z")
                < game_render_level.find("Method extractCamera:(F)V")
            and game_render_level.find("Method extractCamera:(F)V")
                < game_render_level.find("BrowserTargeting.stabilizeBlockHit"),
        ),
        (
            "Block outline rendering uses the matching render-state camera and visible opacity",
            "CameraRenderState.pos" in level_render_block_outline
            and "sipush        180" in level_render_block_outline
            and "ARGB.black" in level_render_block_outline
            and "GameRenderer.getMainCamera" not in level_render_block_outline
            and "Camera.position" not in level_render_block_outline,
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
            "BrowserFilePersistence compiled overlay seeds user-editable browser defaults",
            "seedDefaultOptions" in browser_file_persistence_class
            and "enforcePerformanceOptions" not in browser_file_persistence_class
            and "storage-default-options" in browser_file_persistence_constants
            and "renderDistance:6" in browser_file_persistence_constants
            and "simulationDistance:4" in browser_file_persistence_constants
            and "entityDistanceScaling:0.5" in browser_file_persistence_constants
            and "maxFps:120" in browser_file_persistence_constants
            and 'graphicsPreset:"fast"' in browser_file_persistence_constants
            and 'renderClouds:"false"' in browser_file_persistence_constants
            and "menuBackgroundBlurriness:0" in browser_file_persistence_constants
            and "panoramaSpeed:0.0" in browser_file_persistence_constants
            and "screenEffectScale:0.0" in browser_file_persistence_constants
            and "maxAnisotropyBit:1" in browser_file_persistence_constants
            and "textureFiltering:0" in browser_file_persistence_constants
            and "servers.dat_old" in browser_file_persistence_constants
            and "browser defaults" in browser_file_persistence_constants,
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
