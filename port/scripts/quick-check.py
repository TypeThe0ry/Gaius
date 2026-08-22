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
import hashlib
import json
import mmap
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PORT = ROOT / "port"
VERSION_CONFIG = PORT / "config.json"
WORLDGEN_TELEMETRY_MODES = frozenset(("task-pulsed", "checkpoint-only"))


def _native_external_path(value: str) -> Path:
    """Accept Git-Bash /c/... paths when running Windows Python."""
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value).expanduser()


def _configured_path(value: str | None, fallback: Path) -> Path:
    if not value:
        return fallback
    path = _native_external_path(value)
    return (ROOT / path).resolve() if not path.is_absolute() else path.resolve()


def _profile_scope_requested() -> bool:
    return bool(
        os.environ.get("GAIUS_BUILD_ROOT")
        or os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    )


def _profile_scoped_default(base: Path, profile_id: str) -> Path:
    return base / profile_id if _profile_scope_requested() else base


def _active_profile_id() -> str:
    try:
        config = json.loads(VERSION_CONFIG.read_text(encoding="utf-8"))
        relative = os.environ.get("GAIUS_VERSION_PROFILE_PATH") or config["versionProfile"]
        profile = json.loads((PORT / relative).read_text(encoding="utf-8"))
        return str(profile["id"])
    except (OSError, ValueError, KeyError, TypeError):
        return "26.2"


ACTIVE_PROFILE_ID = _active_profile_id()


def _profile_worldgen_telemetry_mode(profile: object) -> str | None:
    """Return the declared worldgen evidence mode, failing closed if invalid."""
    if not isinstance(profile, dict):
        return None
    mode = profile.get("worldgenTelemetryMode")
    # Fixture profiles created by older identity tests do not carry the new
    # field; preserve their identity-only behavior while validating any value
    # that is present.  The checked-in version profiles are required to declare
    # one by check-version-profile.mjs.
    if mode is None:
        return None
    return (
        mode
        if isinstance(mode, str) and mode in WORLDGEN_TELEMETRY_MODES
        else None
    )


def _active_profile_worldgen_telemetry_mode() -> str | None:
    try:
        config = json.loads(VERSION_CONFIG.read_text(encoding="utf-8"))
        relative = os.environ.get("GAIUS_VERSION_PROFILE_PATH") or config["versionProfile"]
        profile = json.loads((PORT / relative).read_text(encoding="utf-8"))
        mode = _profile_worldgen_telemetry_mode(profile)
        return mode
    except (OSError, ValueError, KeyError, TypeError):
        return None


ACTIVE_WORLDGEN_TELEMETRY_MODE = _active_profile_worldgen_telemetry_mode()
TARGET = _configured_path(
    os.environ.get("GAIUS_BUILD_ROOT"),
    _profile_scoped_default(PORT / "target", ACTIVE_PROFILE_ID),
)
_dist_override = os.environ.get("GAIUS_DIST_DIRECTORY")
if not _dist_override and not _profile_scope_requested():
    _dist_override = os.environ.get("GAIUS_TARGET_DIRECTORY")
DIST = _configured_path(
    _dist_override,
    _profile_scoped_default(PORT / "web" / "dist", ACTIVE_PROFILE_ID),
)
PORTABLE_MANIFEST = DIST / "Gaius.manifest.json"
CLIENT_RELEASE_PROFILE = DIST / "classes.js.release.json"
WORKER_RELEASE_PROFILE = DIST / "singleplayer-server.js.release.json"
TEAVM_COMPILER_PROFILE_TOOL = PORT / "scripts" / "teavm-compiler-profile.py"
TEAVM_COMPILER_PROFILE_TEST = PORT / "scripts" / "test-teavm-compiler-profile.py"
CLIENT_TEA_POM = TARGET / "release-generated-pom.xml"
WORKER_TEA_POM = TARGET / "server-worker" / "release-generated-pom.xml"
WORKER_RESOURCE_LIST = (
    TARGET
    / "server-worker"
    / "generated-resources"
    / "dev"
    / "gaius"
    / "browser"
    / "minecraft-resources.txt"
)
OVERLAYS = _configured_path(
    os.environ.get("GAIUS_OVERLAY_DIRECTORY"),
    _profile_scoped_default(PORT / "work" / "overlays", ACTIVE_PROFILE_ID),
)
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
BRIDGE_REGISTRY = ROOT / "apps" / "bridge" / "dist" / "registry.js"
BRIDGE_PACKAGE = ROOT / "apps" / "bridge" / "package.json"
BRIDGE_SMOKE = ROOT / "apps" / "bridge" / "multiplayer-smoke.mjs"
BRIDGE_REGISTRY_SMOKE = ROOT / "apps" / "bridge" / "registry-smoke.mjs"
PUBLIC_RELAY_SMOKE = ROOT / "apps" / "bridge" / "public-relay-smoke.mjs"
PUBLIC_RELAY_COMPOSE = ROOT / "apps" / "bridge" / "compose.public.example.yaml"
PUBLIC_RELAY_CADDYFILE = ROOT / "apps" / "bridge" / "Caddyfile.public.example"
PUBLIC_RELAY_ENV = ROOT / "apps" / "bridge" / "public.env.example"
BROWSER_RELAY_ROUTING_SMOKE = PORT / "scripts" / "browser-relay-routing-smoke.mjs"
RELAY_REGISTRY = ROOT / "relay-nodes.json"
DIST_RELAY_REGISTRY = DIST / "relay-nodes.json"
RELAY_NODES_DOC = ROOT / "docs" / "relay-nodes.md"
RELAY_REGISTRY_CHECK = ROOT / "tools" / "check-relay-registry.mjs"
REPOSITORY_GUARD = ROOT / ".github" / "workflows" / "repository-guard.yml"
ONLINE_MODE_SERVER_SMOKE = ROOT / "apps" / "bridge" / "online-mode-server-smoke.mjs"
STB_IMAGE = PORT / "overrides" / "libraries" / "lwjgl-stb" / "src" / "main" / "java" / "org" / "lwjgl" / "stb" / "STBImage.java"
LWJGL_BROWSER_MEMORY = PORT / "overrides" / "libraries" / "lwjgl" / "src" / "main" / "java" / "org" / "lwjgl" / "system" / "BrowserMemory.java"
LWJGL_MEMORY_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglMemoryPatcher.java"
NATIVE_METHOD_FALLBACK_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "NativeMethodFallbackPatcher.java"
SHADOWING_BROWSER_MEMORY = PORT / "src" / "main" / "java" / "org" / "lwjgl" / "system" / "BrowserMemory.java"
GLFW_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-glfw" / "src" / "main" / "java" / "org" / "lwjgl" / "glfw" / "BrowserGlfw.java"
GLFW_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "LwjglGlfwBrowserPatcher.java"
CLIENT_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "MinecraftClientPatcher.java"
MINECRAFT_262_BROWSER_PATCHER = (
    PORT
    / "tools"
    / "src"
    / "main"
    / "java"
    / "dev"
    / "gaius"
    / "tools"
    / "Minecraft262BrowserPatcher.java"
)
CLASSLIB_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "TeaVMClasslibPatcher.java"
JOML_MATH_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "JomlMathPatcher.java"
VANILLA_PACK_RESOURCES = PORT / "overrides" / "client" / "src" / "main" / "java" / "net" / "minecraft" / "server" / "packs" / "VanillaPackResources.java"
VANILLA_PACK_RESOURCES_262 = PORT / "overrides" / "client" / "src" / "versions" / "26.2" / "java" / "net" / "minecraft" / "server" / "packs" / "VanillaPackResources.java"
SYSTEM_REPORT = PORT / "overrides" / "client" / "src" / "main" / "java" / "net" / "minecraft" / "SystemReport.java"
BROWSER_FILE_PERSISTENCE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserFilePersistence.java"
MODERN_RUNTIME_SUPPORT = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "lang" / "TModernRuntimeSupport.java"
TEAVM_LOCK_SUPPORT = PORT / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "util" / "concurrent" / "locks" / "TLockSupport.java"
FILE_OUTPUT_STREAM = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "io" / "TFileOutputStream.java"
FILE_CHANNEL = PORT / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "nio" / "channels" / "TFileChannel.java"
BROWSER_BIT_STORAGE = PORT / "overrides" / "classlib" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserBitStorage.java"
BROWSER_LONG_ARRAY_CODEC = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserLongArrayCodec.java"
BROWSER_GUI_ITEM_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserGuiItemCache.java"
BROWSER_WORLDGEN_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserWorldgenScheduler.java"
BROWSER_PACKET_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserPacketScheduler.java"
BROWSER_CHUNK_TASK_PRIORITY = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserChunkTaskPriority.java"
BROWSER_STARTUP_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserStartupScheduler.java"
BROWSER_FUTURE_PUMP = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserFuturePump.java"
BROWSER_GZIP = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserGzip.java"
BROWSER_RENDER_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserRenderScheduler.java"
PERFORMANCE_CONTRACT = PORT / "scripts" / "performance-contract.json"
CHROME_CHUNK_BENCHMARK = PORT / "scripts" / "chrome-chunk-benchmark.mjs"
CHROME_PERFORMANCE_RELEASE_SUITE = (
    PORT / "scripts" / "chrome-performance-release-suite.mjs"
)
CHROME_PERFORMANCE_RELEASE_SUITE_SMOKE = (
    PORT / "scripts" / "chrome-performance-release-suite-smoke.mjs"
)
BROWSER_CHUNK_SECTION_LAYERS = PORT / "overrides" / "client" / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserChunkSectionLayers.java"
BROWSER_IMPROVED_NOISE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserImprovedNoise.java"
BROWSER_NOISE_INTERPOLATOR = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserNoiseInterpolator.java"
BROWSER_PERLIN_NOISE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserPerlinNoise.java"
BROWSER_CLIMATE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserClimate.java"
BROWSER_DENSITY_FUNCTIONS = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserDensityFunctions.java"
BROWSER_SURFACE_BIOME_SUPPLIER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserSurfaceBiomeSupplier.java"
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
BROWSER_CLIENT_NETWORK = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserClientNetwork.java"
BROWSER_MULTIPLAYER_RECOVERY = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserMultiplayerRecovery.java"
BROWSER_SERVER_PACK_REUSE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserServerPackReuse.java"
BROWSER_RESOURCE_RELOAD_PROFILER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserResourceReloadProfiler.java"
BROWSER_RESOURCE_RELOAD_SCHEDULER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserResourceReloadScheduler.java"
BROWSER_UNIHEX_LOADER = PORT / "src" / "main" / "java" / "net" / "minecraft" / "client" / "gui" / "font" / "providers" / "BrowserUnihexLoader.java"
BROWSER_PACK_OVERLAY_COMPAT = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserPackOverlayCompat.java"
BROWSER_ATLAS_RESOURCE_FALLBACK = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserAtlasResourceFallback.java"
BROWSER_AUTHLIB_GSON = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserAuthlibGson.java"
BROWSER_SIGNER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserSigner.java"
BROWSER_SINGLEPLAYER_CLIENT = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserSingleplayerClient.java"
BROWSER_INTEGRATED_SERVER_MAIN = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserIntegratedServerMain.java"
BROWSER_LAZY_DATA_FIXER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "BrowserLazyDataFixer.java"
MINECRAFT_RESOURCE_SUPPLIER = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "MinecraftResourceSupplier.java"
SCHEDULED_THREAD_POOL_EXECUTOR = PORT / "src" / "main" / "java" / "org" / "teavm" / "classlib" / "java" / "util" / "concurrent" / "TScheduledThreadPoolExecutor.java"
SERVER_WORKER_BOOTSTRAP = PORT / "web" / "singleplayer" / "server-worker-bootstrap.js"
SINGLEPLAYER_WORKER_SMOKE = PORT / "web" / "smoke" / "singleplayer-worker-smoke.js"
SINGLEPLAYER_WORKER_RUNTIME_SMOKE = PORT / "scripts" / "singleplayer-worker-runtime-smoke.mjs"
SINGLEPLAYER_REGION_PATCH_LOG_SMOKE = (
    PORT / "scripts" / "singleplayer-region-patch-log-smoke.mjs"
)
SINGLEPLAYER_NETWORK_WAKEUP_SMOKE = PORT / "scripts" / "singleplayer-network-wakeup-smoke.mjs"
INTEGRATED_SERVER_PUMP_SHIM_SMOKE = (
    PORT / "scripts" / "integrated-server-pump-shim-smoke.mjs"
)
SESSION_LAUNCHER_SMOKE = PORT / "scripts" / "session-launcher-smoke.mjs"
SINGLEPLAYER_LAUNCHER = PORT / "web" / "singleplayer" / "index.html"
AUTHLIB_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "AuthlibBrowserPatcher.java"
PATCHY_PATCHER = PORT / "tools" / "src" / "main" / "java" / "dev" / "gaius" / "tools" / "PatchyBrowserPatcher.java"
VERTEX_ARRAY_CACHE = PORT / "overrides" / "client" / "src" / "main" / "java" / "com" / "mojang" / "blaze3d" / "opengl" / "VertexArrayCache.java"
VERTEX_ARRAY_CACHE_262 = PORT / "overrides" / "client" / "src" / "versions" / "26.2" / "java" / "com" / "mojang" / "blaze3d" / "opengl" / "VertexArrayCache.java"
WASM_HOTPATH_C = PORT / "wasm" / "hotpath" / "gaius_hotpath.c"
BUILD_WASM_HOTPATH = PORT / "scripts" / "build-wasm-hotpath.sh"
GENERATE_WASM_HOTPATH = PORT / "scripts" / "generate-wasm-hotpath.py"
GENERATE_POM = PORT / "scripts" / "generate-pom.sh"
VERSION_PROFILE_SHELL = PORT / "scripts" / "version-profile.sh"
BUILD_TEAVM = PORT / "scripts" / "build-teavm.sh"
BUILD_SERVER_WORKER = PORT / "scripts" / "build-teavm-server-worker.sh"
TEAVM_PUBLICATION_GATE = PORT / "scripts" / "teavm-publication-gate.sh"
TEAVM_PUBLICATION_GATE_TEST = PORT / "scripts" / "test-teavm-publication-gate.py"
INDEX_TEMPLATE = PORT / "web" / "launcher" / "index.template.html"
INDEX_TEMPLATE_TEST = PORT / "scripts" / "test-index-template.py"
BUILD_PLATFORM_SMOKE = PORT / "scripts" / "build-platform-smoke.sh"
FETCH_VERSION = PORT / "scripts" / "fetch-version.sh"
BUILD_RELEASE = PORT / "scripts" / "build-teavm-release.sh"
BUILD_VERSION_RELEASE = PORT / "scripts" / "build-version-release.sh"
BUILD_OVERLAYS = PORT / "scripts" / "build-overlays.sh"
COMPRESS_DIST = PORT / "scripts" / "compress-dist.sh"
COMPRESS_BROTLI = PORT / "scripts" / "compress-brotli.mjs"
BUILD_PORTABLE_HTML = PORT / "scripts" / "build-portable-html.py"
BUILD_PORTABLE_HTML_TEST = PORT / "scripts" / "test-build-portable-html.py"
PORTABLE_ARTIFACT_IDENTITY_TEST = PORT / "scripts" / "test-portable-artifact-identity.py"
BUILD_IDENTITY_HELPER = PORT / "scripts" / "gaius_build_identity.py"
BUILD_VANILLA_ASSETS_PACK = PORT / "scripts" / "build-vanilla-assets-pack.py"
VANILLA_RESOURCE_ORDER_TEST = PORT / "scripts" / "test-vanilla-resource-order.py"
SERVE_DIST = PORT / "scripts" / "serve-dist.py"
PLATFORM_SMOKE = PORT / "src" / "main" / "java" / "dev" / "gaius" / "browser" / "PlatformSmoke.java"
PLATFORM_SMOKE_ASSET_LOADER = PORT / "web" / "smoke" / "vanilla-assets-smoke-loader.js"
INDEX_HTML = DIST / "index.html"
HOTPATH_WASM = DIST / "gaius-hotpath.wasm"
GENERATED_RESOURCE_LIST = TARGET / "generated-resources" / "dev" / "gaius" / "browser" / "minecraft-resources.txt"
GENERATED_EMBEDDED_RESOURCE_LIST = TARGET / "generated-resources" / "dev" / "gaius" / "browser" / "minecraft-embedded-resources.txt"
GENERATED_SOUNDS_JSON = TARGET / "generated-resources" / "assets" / "minecraft" / "sounds.json"
GENERATED_UNIFONT_JSON = TARGET / "generated-resources" / "assets" / "minecraft" / "font" / "include" / "unifont.json"
GENERATED_UNIFONT_PUA_JSON = TARGET / "generated-resources" / "assets" / "minecraft" / "font" / "include" / "unifont_pua.json"
POSTPROCESS_TEAVM_JS = PORT / "scripts" / "postprocess-teavm-js.py"
POSTPROCESS_TEAVM_JS_TEST = PORT / "scripts" / "test-postprocess-teavm-js.py"
POSTPROCESS_INDEX_HTML = PORT / "scripts" / "postprocess-index-html.py"
PORTABLE_HTML = DIST / "Gaius.html"
VANILLA_ASSET_PACK = DIST / "vanilla-assets.pack.gz"
SERVER_WORKER_JS = DIST / "singleplayer-server.js"
SERVER_WORKER_BOOTSTRAP_JS = DIST / "singleplayer-server-worker.js"
SERVER_PLUGIN_POM = ROOT / "apps" / "server-plugin" / "pom.xml"
SERVER_PLUGIN_MAIN = ROOT / "apps" / "server-plugin" / "src" / "main" / "java" / "dev" / "gaius" / "serverplugin" / "GaiusServerBridgePlugin.java"
SERVER_PLUGIN_GATEWAY = ROOT / "apps" / "server-plugin" / "src" / "main" / "java" / "dev" / "gaius" / "serverplugin" / "GaiusWebSocketGateway.java"
SERVER_PLUGIN_YML = ROOT / "apps" / "server-plugin" / "src" / "main" / "resources" / "plugin.yml"
FAILURES: list[str] = []

BUILD_IDENTITY_SCHEMA_VERSION = 2
BUILD_IDENTITY_INPUT_POLICY = "gaius-runtime-inputs-v1"
BUILD_IDENTITY_PROTOCOL_POLICY = "gaius-browser-protocol-v1"
BUILD_IDENTITY_OVERLAY_POLICY = "gaius-active-overlay-inputs-v1"
STORAGE_RUNTIME_GLOBALS = (
    "__gaiusProfileId",
    "__gaiusWorldVersion",
    "__gaiusStorageSchema",
    "__gaiusStorageDatabaseName",
    "__gaiusStoragePrefix",
    "__gaiusStorageOpfsDirectory",
)
BUILD_IDENTITY_SOURCE_DIRECTORIES = (
    "port/src/main",
    "port/overrides",
    "port/tools/src/main",
    "port/wasm/hotpath",
)
BUILD_IDENTITY_SOURCE_FILES = (
    "port/config.json",
    "port/web/singleplayer/index.html",
    "port/web/singleplayer/server-worker-bootstrap.js",
    "port/scripts/gaius_build_identity.py",
    "port/scripts/teavm-compiler-profile.py",
    "port/scripts/version-profile.sh",
    "port/scripts/build-overlays.sh",
    "port/scripts/remap-client.sh",
    "port/scripts/generate-pom.sh",
    "port/scripts/build-teavm.sh",
    "port/scripts/build-teavm-server-worker.sh",
    "port/scripts/teavm-publication-gate.sh",
    "port/scripts/build-teavm-release.sh",
    "port/scripts/build-version-release.sh",
    "port/scripts/postprocess-teavm-js.py",
    "port/scripts/postprocess-index-html.py",
    "port/web/launcher/index.template.html",
    "port/scripts/build-vanilla-assets-pack.py",
    "port/scripts/build-wasm-hotpath.sh",
    "port/scripts/generate-wasm-hotpath.py",
)
BUILD_IDENTITY_PROTOCOL_FILES = (
    "port/config.json",
    "port/web/singleplayer/server-worker-bootstrap.js",
    "port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
    "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
    "port/src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
    "port/src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java",
    "port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java",
    "port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserInlineEventLoop.java",
    "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
    "port/scripts/postprocess-teavm-js.py",
)


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
    except (EOFError, OSError):
        return False


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_identity_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def strict_identity_equal(left: object, right: object) -> bool:
    """Compare JSON identity values without Python's bool/int coercion."""
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        if set(left) != set(right):
            return False
        return all(strict_identity_equal(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(
            strict_identity_equal(left_value, right_value)
            for left_value, right_value in zip(left, right)
        )
    return left == right


def build_identity_input_paths(
    root: Path,
    relative_profile: str,
    *,
    protocol: bool,
) -> list[Path]:
    relative_paths = list(
        BUILD_IDENTITY_PROTOCOL_FILES if protocol else BUILD_IDENTITY_SOURCE_FILES
    )
    relative_paths.append(f"port/{relative_profile}")
    paths: dict[str, Path] = {}
    for relative in relative_paths:
        path = root / relative
        if path.is_file():
            paths[path.relative_to(root).as_posix()] = path
    if not protocol:
        for relative in BUILD_IDENTITY_SOURCE_DIRECTORIES:
            directory = root / relative
            if not directory.is_dir():
                continue
            for path in directory.rglob("*"):
                if path.is_file():
                    paths[path.relative_to(root).as_posix()] = path
    return [paths[name] for name in sorted(paths)]


def hash_build_identity_inputs(
    root: Path,
    paths: list[Path],
    policy: str,
) -> dict[str, object]:
    return hash_named_build_identity_inputs(
        [(path.relative_to(root).as_posix(), path) for path in paths],
        policy,
    )


def hash_named_build_identity_inputs(
    inputs: list[tuple[str, Path]],
    policy: str,
) -> dict[str, object]:
    digest = hashlib.sha256()
    digest.update(policy.encode("ascii") + b"\0")
    total_bytes = 0
    for relative, path in inputs:
        size = path.stat().st_size
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\0")
        digest.update(sha256_file(path).encode("ascii"))
        digest.update(b"\n")
        total_bytes += size
    return {
        "policy": policy,
        "sha256": digest.hexdigest(),
        "fileCount": len(inputs),
        "bytes": total_bytes,
    }


def build_identity_overlay_inputs(root: Path, profile: dict) -> list[tuple[str, Path]]:
    config = json.loads((root / "port" / "config.json").read_text(encoding="utf-8"))
    teavm_version = config.get("teaVMVersion")
    if not isinstance(teavm_version, str) or not teavm_version:
        raise ValueError("active config has no TeaVM version")
    version = profile["id"]
    work = root / "port" / "work" / version
    configured_overlay = os.environ.get("GAIUS_OVERLAY_DIRECTORY")
    if configured_overlay:
        overlays = Path(_msys_to_windows_path(configured_overlay)).expanduser()
    elif os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get(
        "GAIUS_VERSION_PROFILE_PATH"
    ):
        overlays = root / "port" / "work" / "overlays" / version
    else:
        overlays = root / "port" / "work" / "overlays"
    if not overlays.is_absolute():
        overlays = root / overlays
    overlays = overlays.resolve()
    metadata_candidates = (work / "version.json", work / "client-version.json")
    candidates = [
        *metadata_candidates,
        overlays / f"client-named-{version}-gaius.jar",
        overlays / f"teavm-classlib-{teavm_version}-gaius.jar",
        overlays / f"teavm-core-{teavm_version}-gaius.jar",
    ]
    metadata_path = metadata_candidates[0]
    if metadata_path.is_file():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if not isinstance(metadata, dict) or metadata.get("id") != version:
            raise ValueError(f"active metadata does not match profile {version}")
        for library in metadata.get("libraries", []):
            if not isinstance(library, dict):
                continue
            downloads = library.get("downloads")
            artifact = downloads.get("artifact") if isinstance(downloads, dict) else None
            relative = artifact.get("path") if isinstance(artifact, dict) else None
            if not isinstance(relative, str) or not relative:
                continue
            relative_path = Path(relative)
            if relative_path.is_absolute() or ".." in relative_path.parts:
                raise ValueError(f"unsafe active library metadata path: {relative}")
            candidates.append(overlays / "libraries" / relative_path)
    isolated = bool(
        os.environ.get("GAIUS_BUILD_ROOT")
        or os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    )
    logical_overlay_root = Path("port/work/overlays")
    if isolated:
        logical_overlay_root /= version

    unique: dict[str, Path] = {}
    for path in candidates:
        if not path.is_file():
            continue
        if path in metadata_candidates:
            logical_name = path.relative_to(root).as_posix()
        else:
            try:
                overlay_relative = path.relative_to(overlays)
            except ValueError:
                logical_name = path.relative_to(root).as_posix()
            else:
                logical_name = (logical_overlay_root / overlay_relative).as_posix()
        unique[logical_name] = path
    return [(name, unique[name]) for name in sorted(unique)]


def current_build_identity_for_quick_check(
    profile: dict,
    relative_profile: str,
    profile_path: Path,
) -> dict[str, object] | None:
    protocol_version = profile.get("protocolVersion")
    distribution = profile.get("clientDistribution")
    world_version = profile.get("worldVersion")
    worldgen_telemetry_mode = _profile_worldgen_telemetry_mode(profile)
    storage = profile.get("storage")
    if (
        not isinstance(protocol_version, int)
        or isinstance(protocol_version, bool)
        or distribution not in {
            "named",
            "obfuscated-with-mappings",
        }
    ):
        return None
    if (
        not isinstance(world_version, int)
        or isinstance(world_version, bool)
        or world_version < 0
        or not isinstance(storage, dict)
    ):
        return None
    storage_schema = storage.get("schema")
    profile_id = profile.get("id")
    expected_storage = (
        {
            "schema": 2,
            "databaseName": f"gaius-fs-v2-{profile_id}",
            "prefix": f"gaius.fs.v2:{profile_id}:",
            "opfsDirectory": f"regions-v2-{profile_id}",
        }
        if isinstance(profile_id, str) and profile_id
        else None
    )
    if (
        not isinstance(profile_id, str)
        or not profile_id
        or not isinstance(storage_schema, int)
        or isinstance(storage_schema, bool)
        or storage_schema != 2
        or storage != expected_storage
    ):
        return None
    root = PORT.parent.resolve()
    try:
        source = hash_build_identity_inputs(
            root,
            build_identity_input_paths(root, relative_profile, protocol=False),
            BUILD_IDENTITY_INPUT_POLICY,
        )
        protocol = hash_build_identity_inputs(
            root,
            build_identity_input_paths(root, relative_profile, protocol=True),
            BUILD_IDENTITY_PROTOCOL_POLICY,
        )
        overlay = hash_named_build_identity_inputs(
            build_identity_overlay_inputs(root, profile),
            BUILD_IDENTITY_OVERLAY_POLICY,
        )
        profile_identity = {
            "id": profile["id"],
            "path": relative_profile,
            "sha256": sha256_file(profile_path),
            "clientDistribution": distribution,
            "protocolVersion": protocol_version,
            "worldVersion": world_version,
            "worldgenTelemetryMode": worldgen_telemetry_mode,
            "storage": storage,
        }
    except (OSError, KeyError, ValueError):
        return None
    protocol["minecraftProtocolVersion"] = protocol_version
    compatibility_payload = {
        "schemaVersion": BUILD_IDENTITY_SCHEMA_VERSION,
        "profile": profile_identity,
        "sourceSha256": source["sha256"],
        "protocolSha256": protocol["sha256"],
        "overlaySha256": overlay["sha256"],
    }
    return {
        "schemaVersion": BUILD_IDENTITY_SCHEMA_VERSION,
        "profile": profile_identity,
        "worldVersion": world_version,
        "worldgenTelemetryMode": worldgen_telemetry_mode,
        "storage": storage,
        "source": source,
        "protocol": protocol,
        "overlay": overlay,
        "compatibilitySha256": hashlib.sha256(
            canonical_identity_json(compatibility_payload).encode("ascii")
        ).hexdigest(),
    }


def manifest_component_build_matches(
    component: object,
    artifact: Path,
    role: str,
    expected_common: dict[str, object],
) -> bool:
    if not isinstance(component, dict) or not artifact.is_file():
        return False
    sidecar = artifact.with_name(f"{artifact.name}.build.json")
    try:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    if not isinstance(record, dict):
        return False
    for key in ("profile", "source", "protocol", "overlay", "compatibilitySha256"):
        if record.get(key) != expected_common.get(key):
            return False
    if (
        record.get("kind") != "gaius-build-identity"
        or record.get("schemaVersion") != BUILD_IDENTITY_SCHEMA_VERSION
        or record.get("role") != role
        or record.get("artifact")
        != {
            "name": artifact.name,
            "sha256": sha256_file(artifact),
            "bytes": artifact.stat().st_size,
        }
    ):
        return False
    unsigned = dict(record)
    identity_hash = unsigned.pop("identitySha256", None)
    if identity_hash != hashlib.sha256(
        canonical_identity_json(unsigned).encode("ascii")
    ).hexdigest():
        return False
    expected_component = {
        "role": role,
        "identitySha256": identity_hash,
        "compatibilitySha256": expected_common["compatibilitySha256"],
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
    }
    return component.get("build") == expected_component


def manifest_compiler_profile_matches(
    component: object,
    artifact: Path,
    role: str,
) -> bool:
    if not isinstance(component, dict) or not artifact.is_file():
        return False
    compiler_metadata = component.get("compiler")
    sidecar = artifact.with_name(f"{artifact.name}.release.json")
    try:
        record = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    if not isinstance(compiler_metadata, dict) or not isinstance(record, dict):
        return False
    compiler = record.get("compiler")
    artifact_record = record.get("artifact")
    profile_sha256 = record.get("profileSha256")
    if (
        record.get("kind") != "gaius-teavm-compiler-profile"
        or record.get("schemaVersion") != 2
        or record.get("role") != role
        or record.get("releaseGrade") is not True
        or not isinstance(compiler, dict)
        or not isinstance(artifact_record, dict)
        or not isinstance(profile_sha256, str)
    ):
        return False
    hash_payload = dict(record)
    hash_payload.pop("profileSha256", None)
    if hashlib.sha256(
        canonical_identity_json(hash_payload).encode("ascii")
    ).hexdigest() != profile_sha256:
        return False
    if (
        artifact_record.get("sha256") != sha256_file(artifact)
        or artifact_record.get("bytes") != artifact.stat().st_size
    ):
        return False
    expected = {
        "profileSha256": profile_sha256,
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
        "optimizationLevel": compiler.get("optimizationLevel"),
        "minifying": compiler.get("minifying"),
        "shortFileNames": compiler.get("shortFileNames"),
        "assertionsRemoved": compiler.get("assertionsRemoved"),
    }
    return compiler_metadata == expected


def manifest_file_matches(
    value: object,
    path: Path,
    hash_key: str = "sha256",
    bytes_key: str = "bytes",
) -> bool:
    if not isinstance(value, dict) or not path.is_file():
        return False
    try:
        return (
            path.stat().st_size > 0
            and value.get(hash_key) == sha256_file(path)
            and value.get(bytes_key) == path.stat().st_size
        )
    except OSError:
        return False


def manifest_gzip_pair_matches(
    value: object,
    raw_path: Path,
    gzip_path: Path,
) -> bool:
    if not isinstance(value, dict) or not raw_path.is_file() or not gzip_path.is_file():
        return False
    try:
        return (
            raw_path.stat().st_size > 0
            and gzip_path.stat().st_size > 0
            and gzip_matches(raw_path)
            and value.get("rawSha256") == sha256_file(raw_path)
            and value.get("gzipSha256") == sha256_file(gzip_path)
            and value.get("rawBytes") == raw_path.stat().st_size
            and value.get("gzipBytes") == gzip_path.stat().st_size
        )
    except OSError:
        return False


def active_version_profile(port_root: Path | None = None) -> tuple[dict, str, Path] | None:
    try:
        port_root = PORT if port_root is None else Path(port_root)
        config = json.loads((port_root / "config.json").read_text(encoding="utf-8"))
        relative_profile = (
            os.environ.get("GAIUS_VERSION_PROFILE_PATH")
            or config["versionProfile"]
        )
        if not isinstance(relative_profile, str):
            return None
        versions_directory = (port_root / "versions").resolve()
        profile_path = (port_root / relative_profile).resolve()
        profile_path.relative_to(versions_directory)
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        if not isinstance(profile, dict) or not isinstance(profile.get("id"), str):
            return None
        if profile.get("clientDistribution") not in {
            "named",
            "obfuscated-with-mappings",
        }:
            return None
        profile_mode = profile.get("worldgenTelemetryMode")
        if profile_mode is not None and (
            not isinstance(profile_mode, str)
            or profile_mode not in WORLDGEN_TELEMETRY_MODES
        ):
            return None
        return profile, Path(relative_profile).as_posix(), profile_path
    except (OSError, ValueError, KeyError):
        return None


class OverlayResolutionError(RuntimeError):
    """The active profile metadata cannot identify the overlay inputs."""


def _required_json_object(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        raise OverlayResolutionError(f"{label} is unreadable: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise OverlayResolutionError(f"{label} must be a JSON object: {path}")
    return value


def _safe_metadata_path(raw_path: object, coordinate: str) -> str:
    if not isinstance(raw_path, str) or not raw_path:
        raise OverlayResolutionError(
            f"{coordinate} has no downloads.artifact.path in active version metadata"
        )
    path = Path(raw_path)
    if path.is_absolute() or ".." in path.parts:
        raise OverlayResolutionError(
            f"{coordinate} metadata path is not relative: {raw_path}"
        )
    return path.as_posix()


def _library_artifact_path(
    metadata: dict,
    coordinate: str,
    fallback_classifier: str | None = None,
) -> str:
    libraries = metadata.get("libraries")
    if not isinstance(libraries, list):
        raise OverlayResolutionError("active version metadata has no libraries array")
    group, artifact = coordinate.split(":", 1)
    exact: list[str] = []
    fallback: list[str] = []
    for library in libraries:
        if not isinstance(library, dict) or not isinstance(library.get("name"), str):
            continue
        parts = library["name"].split(":")
        if len(parts) < 3 or parts[0] != group or parts[1] != artifact:
            continue
        downloads = library.get("downloads")
        artifact_download = downloads.get("artifact") if isinstance(downloads, dict) else None
        raw_path = artifact_download.get("path") if isinstance(artifact_download, dict) else None
        if len(parts) == 3:
            if raw_path is not None:
                exact.append(_safe_metadata_path(raw_path, coordinate))
        elif (
            fallback_classifier is not None
            and len(parts) == 4
            and parts[3] == fallback_classifier
            and raw_path is not None
        ):
            fallback.append(_safe_metadata_path(raw_path, coordinate))
    candidates = exact or fallback
    if len(candidates) != 1:
        classifier = f" or classifier {fallback_classifier!r}" if fallback_classifier else ""
        raise OverlayResolutionError(
            f"expected exactly one artifact for {coordinate}{classifier}, found {len(candidates)}"
        )
    return candidates[0]


def _msys_to_windows_path(value: str) -> str:
    """Convert Git-Bash style /c/... paths into Windows C:/... form."""
    if value.startswith("/") and len(value) > 2 and value[2] == "/" and value[1].isalpha():
        return value[1].upper() + ":" + value[2:]
    return value


def _verify_generated_classpath(
    classpath_path: Path,
    work_directory: Path,
    library_path: str,
    coordinate: str,
) -> None:
    if not classpath_path.is_file():
        return
    try:
        # The file normally uses ':' separators (Git Bash paste), while
        # fixtures and native Windows callers may use os.pathsep (';'). Keep
        # both forms readable; splitting a Windows drive-letter colon would
        # otherwise silently hide the active library from the resolver.
        raw = classpath_path.read_text(encoding="utf-8")
        if ";" in raw:
            values = raw.split(";")
        else:
            values = re.split(r":(?=[/\\])", raw)
        entries = [Path(_msys_to_windows_path(value)) for value in values if value]
    except (OSError, UnicodeDecodeError) as exc:
        raise OverlayResolutionError(
            f"generated classpath is unreadable: {classpath_path}: {exc}"
        ) from exc
    expected = (work_directory / "libraries" / library_path).resolve()
    if not any(entry.resolve() == expected for entry in entries):
        raise OverlayResolutionError(
            f"generated classpath does not contain active {coordinate} artifact "
            f"{expected}: {classpath_path}"
        )


def resolve_overlay_paths(
    port_root: Path | None = None,
    overlays_root: Path | None = None,
) -> dict[str, object]:
    """Resolve only the active profile's client and patched library artifacts.

    The resolver deliberately constructs one exact path per active metadata entry. It
    never searches the overlay directory, so an old profile cannot satisfy a missing
    current artifact by accident.
    """
    port_root = PORT if port_root is None else Path(port_root)
    active = active_version_profile(port_root)
    if active is None:
        raise OverlayResolutionError(
            f"cannot load active profile from {port_root / 'config.json'}"
        )
    profile, relative_profile, profile_path = active
    version = profile["id"]
    work_directory = port_root / "work" / version
    metadata_path = work_directory / "version.json"
    metadata = _required_json_object(metadata_path, "active version metadata")
    if metadata.get("id") != version:
        raise OverlayResolutionError(
            f"active version metadata id {metadata.get('id')!r} does not match profile {version!r}: "
            f"{metadata_path}"
        )
    client_version_path = work_directory / "client-version.json"
    if client_version_path.is_file():
        client_version = _required_json_object(client_version_path, "generated client metadata")
        if client_version.get("id") != version:
            raise OverlayResolutionError(
                f"generated client metadata id {client_version.get('id')!r} does not match "
                f"profile {version!r}: {client_version_path}"
            )

    overlays = Path(overlays_root) if overlays_root is not None else port_root / "work" / "overlays"
    library_specs = {
        "lwjgl": ("org.lwjgl:lwjgl", "unsafe"),
        "lwjgl_glfw": ("org.lwjgl:lwjgl-glfw", None),
        "lwjgl_opengl": ("org.lwjgl:lwjgl-opengl", None),
        "lwjgl_openal": ("org.lwjgl:lwjgl-openal", None),
        "netty_transport": ("io.netty:netty-transport", None),
        "authlib": ("com.mojang:authlib", None),
        "joml": ("org.joml:joml", None),
        "patchy": ("com.mojang:patchy", None),
    }
    library_paths: dict[str, Path] = {}
    metadata_library_paths: dict[str, str] = {}
    classpath_path = work_directory / "classpath.txt"
    for key, (coordinate, fallback_classifier) in library_specs.items():
        metadata_library_path = _library_artifact_path(
            metadata,
            coordinate,
            fallback_classifier,
        )
        _verify_generated_classpath(
            classpath_path,
            work_directory,
            metadata_library_path,
            coordinate,
        )
        metadata_library_paths[key] = metadata_library_path
        library_paths[key] = overlays / "libraries" / Path(metadata_library_path)

    expected_paths: dict[str, Path] = {
        "client": overlays / f"client-named-{version}-gaius.jar",
    }
    expected_paths.update(library_paths)
    return {
        "profile": profile,
        "profile_path": profile_path,
        "relative_profile": Path(relative_profile).as_posix(),
        "metadata_path": metadata_path,
        "classpath_path": classpath_path,
        "client_distribution": profile["clientDistribution"],
        "version": version,
        "client": expected_paths["client"],
        "libraries": library_paths,
        "metadata_library_paths": metadata_library_paths,
        "expected_paths": expected_paths,
    }


def missing_overlay_paths(resolved: dict[str, object]) -> list[tuple[str, Path]]:
    expected_paths = resolved.get("expected_paths")
    if not isinstance(expected_paths, dict):
        return [("resolved overlay set", Path("<unresolved>"))]
    return [
        (label, path)
        for label, path in expected_paths.items()
        if isinstance(path, Path) and not path.is_file()
    ]


def launcher_argument(index: str, name: str) -> str | None:
    match = re.search(
        rf"[\"']{re.escape(name)}[\"']\s*,\s*[\"']([^\"']+)[\"']",
        index,
    )
    return match.group(1) if match is not None else None


PORTABLE_SIGNATURE_CONTRACT = (
    (
        "client-finite-long-patch",
        "classes.js",
        b"/*gaius-java-finite-long-cast*/",
    ),
    (
        "client-target-attestation",
        "classes.js",
        b"target-attestation",
    ),
    (
        "server-input-pump",
        "singleplayer-server.js",
        b"/*gaius-integrated-server-input-coroutine*/",
    ),
)


def embedded_portable_manifest(path: Path) -> dict | None:
    marker = b"const portableManifest = "
    try:
        with path.open("rb") as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ
        ) as data:
            start = data.find(marker)
            if start < 0:
                return None
            start += len(marker)
            end = data.find(b";", start)
            if end < 0:
                return None
            value = json.loads(bytes(data[start:end]).decode("ascii"))
            return value if isinstance(value, dict) else None
    except (OSError, UnicodeDecodeError, ValueError):
        return None


def manifest_top_level_identity_matches(
    manifest: object,
    expected_common: object,
) -> bool:
    """Require portable identity fields to agree with the resolved build identity."""
    if not isinstance(manifest, dict) or not isinstance(expected_common, dict):
        return False
    expected_profile = expected_common.get("profile")
    if not isinstance(expected_profile, dict):
        return False

    # These fields are deliberately checked by presence as well as value.  A
    # legacy fixture may resolve a missing telemetry declaration to None, but a
    # portable manifest still has to carry the explicit JSON null field.
    required_profile_fields = (
        "id",
        "path",
        "worldVersion",
        "worldgenTelemetryMode",
        "storage",
    )
    required_common_fields = (
        "worldVersion",
        "worldgenTelemetryMode",
        "storage",
    )
    if any(field not in expected_profile for field in required_profile_fields):
        return False
    if any(field not in expected_common for field in required_common_fields):
        return False
    if (
        not isinstance(expected_profile["id"], str)
        or not expected_profile["id"]
        or not isinstance(expected_profile["path"], str)
        or not expected_profile["path"]
        or not isinstance(expected_common["worldVersion"], int)
        or isinstance(expected_common["worldVersion"], bool)
        or expected_common["worldVersion"] < 0
        or (
            expected_common["worldgenTelemetryMode"] is not None
            and (
                not isinstance(expected_common["worldgenTelemetryMode"], str)
                or expected_common["worldgenTelemetryMode"]
                not in WORLDGEN_TELEMETRY_MODES
            )
        )
        or not isinstance(expected_common["storage"], dict)
        or not isinstance(expected_common["storage"].get("schema"), int)
        or isinstance(expected_common["storage"].get("schema"), bool)
        or expected_common["storage"].get("schema") != 2
    ):
        return False
    for field in ("worldVersion", "worldgenTelemetryMode", "storage"):
        if not strict_identity_equal(expected_profile[field], expected_common[field]):
            return False

    top_level_identity = {
        "profile": expected_profile["id"],
        "profilePath": expected_profile["path"],
        "worldVersion": expected_common["worldVersion"],
        "worldgenTelemetryMode": expected_common["worldgenTelemetryMode"],
        "storage": expected_common["storage"],
    }
    for field, expected in top_level_identity.items():
        if field not in manifest or not strict_identity_equal(
            manifest[field],
            expected,
        ):
            return False

    build_identity = manifest.get("buildIdentity")
    return (
        strict_identity_equal(build_identity, expected_common)
        and isinstance(build_identity, dict)
        and strict_identity_equal(build_identity.get("profile"), expected_profile)
    )


def portable_artifact_identity_matches() -> bool:
    """Recompute the portable identity without importing the build script."""
    active = active_version_profile()
    if active is None:
        return False
    profile, relative_profile, profile_path = active
    if not PORTABLE_HTML.is_file() or not PORTABLE_MANIFEST.is_file():
        return False

    classes_js = DIST / "classes.js"
    classes_gzip = DIST / "classes.js.gz"
    try:
        manifest = json.loads(PORTABLE_MANIFEST.read_text(encoding="utf-8"))
        index = INDEX_HTML.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    if embedded_portable_manifest(PORTABLE_HTML) != manifest:
        return False
    expected_build_identity = current_build_identity_for_quick_check(
        profile,
        relative_profile,
        profile_path,
    )
    if expected_build_identity is None:
        return False
    if (
        manifest.get("kind") != "gaius-portable-artifact"
        or manifest.get("schemaVersion") != 2
        or manifest.get("artifact") != PORTABLE_HTML.name
        or manifest.get("profileSha256") != sha256_file(profile_path)
        or not manifest_top_level_identity_matches(
            manifest,
            expected_build_identity,
        )
    ):
        return False

    classes_component = manifest.get("classesJs")
    if (
        not manifest_gzip_pair_matches(classes_component, classes_js, classes_gzip)
        or not manifest_component_build_matches(
            classes_component,
            classes_js,
            "client",
            expected_build_identity,
        )
        or not manifest_compiler_profile_matches(
            classes_component,
            classes_js,
            "client",
        )
    ):
        return False
    raw_hash = sha256_file(classes_js)

    server_js = DIST / "singleplayer-server.js"
    server_gzip = DIST / "singleplayer-server.js.gz"
    server_component = manifest.get("singleplayerServerJs")
    if not manifest_gzip_pair_matches(
        server_component,
        server_js,
        server_gzip,
    ) or not manifest_component_build_matches(
        server_component,
        server_js,
        "singleplayer-worker",
        expected_build_identity,
    ) or not manifest_compiler_profile_matches(
        server_component,
        server_js,
        "singleplayer-worker",
    ):
        return False

    wasm_raw = DIST / "gaius-hotpath.wasm"
    wasm_gzip = DIST / "gaius-hotpath.wasm.gz"
    wasm_component = manifest.get("wasmHotpath")
    if not manifest_gzip_pair_matches(
        wasm_component,
        wasm_raw,
        wasm_gzip,
    ) or not manifest_component_build_matches(
        wasm_component,
        wasm_raw,
        "wasm-hotpath",
        expected_build_identity,
    ):
        return False

    bootstrap = DIST / "singleplayer-server-worker.js"
    bootstrap_component = manifest.get("singleplayerWorkerBootstrap")
    if (
        not manifest_file_matches(bootstrap_component, bootstrap)
        or not manifest_component_build_matches(
            bootstrap_component,
            bootstrap,
            "worker-bootstrap",
            expected_build_identity,
        )
    ):
        return False
    vanilla_assets = DIST / "vanilla-assets.pack.gz"
    vanilla_component = manifest.get("vanillaAssetsPack")
    if (
        not manifest_file_matches(
            vanilla_component,
            vanilla_assets,
            hash_key="gzipSha256",
            bytes_key="gzipBytes",
        )
        or not manifest_component_build_matches(
            vanilla_component,
            vanilla_assets,
            "vanilla-assets",
            expected_build_identity,
        )
    ):
        return False
    relay_registry = DIST / "relay-nodes.json"
    relay_component = manifest.get("relayRegistry")
    if (
        not manifest_file_matches(relay_component, relay_registry)
        or not manifest_component_build_matches(
            relay_component,
            relay_registry,
            "relay-registry",
            expected_build_identity,
        )
    ):
        return False

    if launcher_argument(index, "--version") != profile.get("id"):
        return False
    official = profile.get("official")
    if isinstance(official, dict) and official.get("assetIndexId") is not None:
        if launcher_argument(index, "--assetIndex") != str(official["assetIndexId"]):
            return False

    required = PORTABLE_SIGNATURE_CONTRACT if profile.get("clientDistribution") == "named" else ()
    signatures = manifest.get("signatures")
    if not isinstance(signatures, list):
        return False
    for name, asset, marker in required:
        path = DIST / asset
        if not path.is_file() or not file_matches(path, re.escape(marker)):
            return False
        matches = [
            value for value in signatures
            if isinstance(value, dict) and value.get("name") == name
        ]
        if len(matches) != 1:
            return False
        signature = matches[0]
        if (
            signature.get("asset") != asset
            or signature.get("marker") != marker.decode("ascii")
            or signature.get("verified") is not True
        ):
            return False
        if asset == "classes.js" and signature.get("sha256") != raw_hash:
            return False
    return len(signatures) == len(required)


def file_matches(path: Path, pattern: bytes) -> bool:
    if not path.is_file() or path.stat().st_size == 0:
        return False
    try:
        with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            return re.search(pattern, data, re.DOTALL) is not None
    except OSError:
        return False


def generated_assignment_for_anchor(
    data: bytes,
    anchor: bytes,
) -> tuple[bytes, bytes] | None:
    """Resolve a minified TeaVM A.<name> assignment that contains a stable JSBody anchor."""
    anchor_offset = data.find(anchor)
    if anchor_offset < 0:
        return None
    search_start = max(0, anchor_offset - 128 * 1024)
    prefix = data[search_start:anchor_offset]
    assignments = list(
        re.finditer(
            rb"(?:^|[;\n])(A\.[A-Za-z_$][A-Za-z0-9_$]*)\s*=",
            prefix,
        )
    )
    if not assignments:
        return None
    match = assignments[-1]
    symbol = match.group(1)
    assignment_start = search_start + match.start(1)
    next_assignment = re.search(
        rb";\s*(?=A\.[A-Za-z_$][A-Za-z0-9_$]*\s*=)",
        data[anchor_offset:anchor_offset + 256 * 1024],
    )
    assignment_end = (
        anchor_offset + next_assignment.end()
        if next_assignment is not None
        else min(len(data), anchor_offset + 256 * 1024)
    )
    return symbol, data[assignment_start:assignment_end]


def generated_assignment_matches(
    data: bytes,
    anchor: bytes,
    required: tuple[bytes, ...],
    forbidden: tuple[bytes, ...] = (),
) -> bool:
    resolved = generated_assignment_for_anchor(data, anchor)
    if resolved is None:
        return False
    _, assignment = resolved
    # TeaVM can preserve CRLF (and formatting whitespace) from profile-specific
    # generated sources.  These assertions target emitted semantics rather
    # than formatting, so compact the method body before matching fragments.
    normalized = b"".join(assignment.split())
    return all(b"".join(value.split()) in normalized for value in required) and not any(
        b"".join(value.split()) in normalized for value in forbidden
    )


def generated_assignment_is_called(
    data: bytes,
    anchor: bytes,
    required: tuple[tuple[bytes, int], ...] = (),
) -> bool:
    resolved = generated_assignment_for_anchor(data, anchor)
    if resolved is None:
        return False
    symbol, _ = resolved
    offset = 0
    call_prefix = symbol + b"("
    while True:
        offset = data.find(call_prefix, offset)
        if offset < 0:
            return False
        window = data[offset:min(len(data), offset + 4096)]
        if all(window.count(value) >= count for value, count in required):
            return True
        offset += len(call_prefix)


def embedded_resource_matches(path: Path, key: str, expected: bytes) -> bool:
    if not path.is_file() or not expected:
        return False
    prefix = re.compile(
        b'"' + re.escape(key.encode("utf-8")) + rb'"\s*:\s*"'
    )
    try:
        with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            match = prefix.search(data)
            if match is None:
                return False
            position = match.end() - 1
            encoded = bytearray()
            while True:
                if position >= len(data) or data[position] != ord('"'):
                    return False
                end = data.find(b'"', position + 1)
                if end < 0:
                    return False
                encoded.extend(data[position + 1:end])
                position = end + 1
                while position < len(data) and data[position] in b" \t\r\n":
                    position += 1
                if position >= len(data) or data[position] != ord("+"):
                    break
                position += 1
                while position < len(data) and data[position] in b" \t\r\n":
                    position += 1
            return base64.b64decode(encoded) == expected
    except (OSError, ValueError):
        return False


def teavm_release_profile_matches(
    profile_path: Path,
    role: str,
    artifact: Path,
    pom: Path,
    resources: tuple[Path, ...],
) -> bool:
    if not TEAVM_COMPILER_PROFILE_TOOL.is_file() or not profile_path.is_file():
        return False
    command = [
        sys.executable,
        str(TEAVM_COMPILER_PROFILE_TOOL),
        "verify",
        "--root",
        str(ROOT),
        "--role",
        role,
        "--artifact",
        str(artifact),
        "--pom",
        str(pom),
        "--output",
        str(profile_path),
        "--require-release",
    ]
    for resource in resources:
        command.extend(("--resource", str(resource)))
    try:
        result = subprocess.run(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


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


def portable_embeds_assignment(portable: Path, name: str, value: object) -> bool:
    if not portable.is_file():
        return False
    expected = (
        f"const {name} = "
        + json.dumps(value, ensure_ascii=True, separators=(",", ":"))
        + ";"
    ).encode("utf-8")
    try:
        with portable.open("rb") as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ
        ) as data:
            return data.find(expected) >= 0
    except OSError:
        return False


def vanilla_asset_pack_index(path: Path) -> dict[str, list[int]]:
    try:
        with gzip.open(path, "rb") as stream:
            if stream.read(8) != b"GAIUSVP1":
                return {}
            length_bytes = stream.read(4)
            if len(length_bytes) != 4:
                return {}
            index_length = struct.unpack("<I", length_bytes)[0]
            if index_length <= 0 or index_length > 8 * 1024 * 1024:
                return {}
            value = json.loads(stream.read(index_length).decode("utf-8"))
            return value if isinstance(value, dict) else {}
    except (OSError, ValueError, json.JSONDecodeError):
        return {}


def vanilla_asset_pack_entries(path: Path, names: tuple[str, ...]) -> dict[str, bytes]:
    try:
        with gzip.open(path, "rb") as stream:
            if stream.read(8) != b"GAIUSVP1":
                return {}
            length_bytes = stream.read(4)
            if len(length_bytes) != 4:
                return {}
            index_length = struct.unpack("<I", length_bytes)[0]
            if index_length <= 0 or index_length > 8 * 1024 * 1024:
                return {}
            index = json.loads(stream.read(index_length).decode("utf-8"))
            payload_offset = 12 + index_length
            result: dict[str, bytes] = {}
            for name in names:
                entry = index.get(name) if isinstance(index, dict) else None
                if (
                    not isinstance(entry, list)
                    or len(entry) != 2
                    or not all(isinstance(value, int) and value >= 0 for value in entry)
                ):
                    continue
                offset, length = entry
                stream.seek(payload_offset + offset)
                content = stream.read(length)
                if len(content) == length:
                    result[name] = content
            return result
    except (OSError, ValueError, json.JSONDecodeError):
        return {}


def file_contains(path: Path, needle: str) -> bool:
    if not path.is_file():
        return False
    try:
        with path.open("rb") as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ
        ) as data:
            return data.find(needle.encode("utf-8")) >= 0
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


class JavapPrerequisiteError(RuntimeError):
    """Raised when quick-check cannot locate the JDK's ``javap`` tool."""


def _java_home_path(value: str) -> Path:
    """Normalize a configured Java home, including Git-Bash ``/c/...`` paths."""
    # Environment variables occasionally arrive quoted when copied from a shell
    # command.  Removing only a matching outer pair keeps legitimate spaces in
    # paths intact.
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return _native_external_path(value).expanduser()


def resolve_javap() -> Path:
    """Resolve the JDK ``javap`` executable used by overlay bytecode checks.

    Explicit Java homes are preferred so quick-check works when a caller has a
    valid JDK configured but intentionally keeps ``JAVA_HOME/bin`` out of PATH.
    ``GAIUS_JAVA_HOME`` wins over ``JAVA_HOME`` when both are set.  A PATH
    lookup remains a useful fallback for environments without either variable.
    """
    checked_homes: list[Path] = []
    for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME"):
        value = os.environ.get(variable)
        if not value:
            continue
        home = _java_home_path(value)
        if home in checked_homes:
            continue
        checked_homes.append(home)
        # Check both spellings so a fixture (or a mounted JDK) can be used on
        # either host without relying on the host Python's os.name value.  Keep
        # the native spelling first when both files happen to be present.
        executables = (
            ("javap.exe", "javap")
            if os.name == "nt"
            else ("javap", "javap.exe")
        )
        for executable in executables:
            candidate = home / "bin" / executable
            if candidate.is_file():
                return candidate

    path_tool = shutil.which("javap")
    if path_tool:
        return Path(path_tool).expanduser()

    configured = ", ".join(
        f"{variable}={value}"
        for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME")
        if (value := os.environ.get(variable))
    )
    configured_hint = (
        f" ({configured})" if configured else ""
    )
    raise JavapPrerequisiteError(
        "javap prerequisite not found"
        f"{configured_hint}; set GAIUS_JAVA_HOME or JAVA_HOME to a JDK"
        " (expected <JAVA_HOME>/bin/javap[.exe]) or add javap to PATH"
    )


def run_javap(classpath: Path, class_name: str) -> str:
    if not classpath.exists():
        return f"missing classpath: {rel(classpath)}"
    try:
        javap = resolve_javap()
        return subprocess.check_output(
            [str(javap), "-classpath", str(classpath), "-c", "-p", class_name],
            cwd=ROOT,
            text=True,
            stderr=subprocess.STDOUT,
            timeout=10,
        )
    except JavapPrerequisiteError as exc:
        return str(exc)
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


def method_section_any(text: str, *headers: str) -> str:
    """Return the first method body matching one of several versioned signatures."""
    for header in headers:
        section_text = method_section(text, header)
        if section_text:
            return section_text
    return ""


def method_section_by_fragment(text: str, fragment: str) -> str:
    """Resolve a method by stable name/descriptor fragments across mappings."""
    lines = text.splitlines(True)
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not line.startswith("  ") or not stripped.endswith(";"):
            continue
        if " Method " in stripped or fragment not in stripped:
            continue
        header = stripped
        start = sum(len(item) for item in lines[:index])
        section_text = method_section(text, header)
        if section_text:
            return section_text
        # Keep this fallback independent of method_section's declaration markers.
        end = len(lines)
        for next_index in range(index + 1, len(lines)):
            next_stripped = lines[next_index].strip()
            if (
                lines[next_index].startswith("  ")
                and not next_stripped.startswith("//")
                and next_stripped.endswith(";")
            ):
                end = next_index
                break
        return "".join(lines[index:end])
    return ""


def required_method_section(text: str, header: str) -> str | None:
    section_text = method_section(text, header)
    return section_text if section_text else None


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
    active_profile = active_version_profile()
    is_current_named_profile = (
        active_profile is not None
        and active_profile[0].get("clientDistribution") == "named"
    )
    active_asset_index = Path("__missing_asset_index__")
    if active_profile is not None:
        active_version = str(active_profile[0].get("id", ""))
        active_metadata = PORT / "work" / active_version / "version.json"
        try:
            metadata = json.loads(active_metadata.read_text(encoding="utf-8"))
            asset_index_id = metadata.get("assetIndex", {}).get("id") or metadata.get("assets")
            if isinstance(asset_index_id, str) and asset_index_id:
                active_asset_index = (
                    PORT / "work" / active_version / "assets" / "indexes" / f"{asset_index_id}.json"
                )
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            pass
    client_profile_resources = (
        GENERATED_RESOURCE_LIST,
        GENERATED_EMBEDDED_RESOURCE_LIST,
        active_asset_index,
        GENERATED_SOUNDS_JSON,
        GENERATED_UNIFONT_JSON,
        GENERATED_UNIFONT_PUA_JSON,
        VANILLA_ASSET_PACK,
    )
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
    bridge_registry = BRIDGE_REGISTRY.read_text(errors="replace") if BRIDGE_REGISTRY.exists() else ""
    bridge_package = BRIDGE_PACKAGE.read_text(errors="replace") if BRIDGE_PACKAGE.exists() else ""
    bridge_smoke = BRIDGE_SMOKE.read_text(errors="replace") if BRIDGE_SMOKE.exists() else ""
    bridge_registry_smoke = (
        BRIDGE_REGISTRY_SMOKE.read_text(errors="replace")
        if BRIDGE_REGISTRY_SMOKE.exists()
        else ""
    )
    public_relay_smoke = (
        PUBLIC_RELAY_SMOKE.read_text(errors="replace")
        if PUBLIC_RELAY_SMOKE.exists()
        else ""
    )
    public_relay_compose = (
        PUBLIC_RELAY_COMPOSE.read_text(errors="replace")
        if PUBLIC_RELAY_COMPOSE.exists()
        else ""
    )
    public_relay_caddyfile = (
        PUBLIC_RELAY_CADDYFILE.read_text(errors="replace")
        if PUBLIC_RELAY_CADDYFILE.exists()
        else ""
    )
    public_relay_env = (
        PUBLIC_RELAY_ENV.read_text(errors="replace")
        if PUBLIC_RELAY_ENV.exists()
        else ""
    )
    browser_relay_routing_smoke = (
        BROWSER_RELAY_ROUTING_SMOKE.read_text(errors="replace")
        if BROWSER_RELAY_ROUTING_SMOKE.exists()
        else ""
    )
    relay_registry_text = (
        RELAY_REGISTRY.read_text(errors="replace") if RELAY_REGISTRY.exists() else ""
    )
    dist_relay_registry_text = (
        DIST_RELAY_REGISTRY.read_text(errors="replace")
        if DIST_RELAY_REGISTRY.exists()
        else ""
    )
    relay_nodes_doc = (
        RELAY_NODES_DOC.read_text(errors="replace") if RELAY_NODES_DOC.exists() else ""
    )
    relay_registry_check = (
        RELAY_REGISTRY_CHECK.read_text(errors="replace")
        if RELAY_REGISTRY_CHECK.exists()
        else ""
    )
    repository_guard = (
        REPOSITORY_GUARD.read_text(errors="replace") if REPOSITORY_GUARD.exists() else ""
    )
    try:
        relay_registry = json.loads(relay_registry_text)
    except (TypeError, json.JSONDecodeError):
        relay_registry = {}
    online_mode_server_smoke = ONLINE_MODE_SERVER_SMOKE.read_text(errors="replace") if ONLINE_MODE_SERVER_SMOKE.exists() else ""
    stb_image = STB_IMAGE.read_text(errors="replace") if STB_IMAGE.exists() else ""
    browser_memory = LWJGL_BROWSER_MEMORY.read_text(errors="replace") if LWJGL_BROWSER_MEMORY.exists() else ""
    lwjgl_memory_patcher = (
        LWJGL_MEMORY_PATCHER.read_text(errors="replace")
        if LWJGL_MEMORY_PATCHER.exists()
        else ""
    )
    native_method_fallback_patcher = (
        NATIVE_METHOD_FALLBACK_PATCHER.read_text(errors="replace")
        if NATIVE_METHOD_FALLBACK_PATCHER.exists()
        else ""
    )
    glfw_text = GLFW_BRIDGE.read_text(errors="replace") if GLFW_BRIDGE.exists() else ""
    glfw_patcher = GLFW_PATCHER.read_text(errors="replace") if GLFW_PATCHER.exists() else ""
    client_patcher = CLIENT_PATCHER.read_text(errors="replace") if CLIENT_PATCHER.exists() else ""
    minecraft_262_browser_patcher = (
        MINECRAFT_262_BROWSER_PATCHER.read_text(errors="replace")
        if MINECRAFT_262_BROWSER_PATCHER.exists()
        else ""
    )
    classlib_patcher = CLASSLIB_PATCHER.read_text(errors="replace") if CLASSLIB_PATCHER.exists() else ""
    joml_math_patcher = JOML_MATH_PATCHER.read_text(errors="replace") if JOML_MATH_PATCHER.exists() else ""
    vanilla_pack_resources = VANILLA_PACK_RESOURCES.read_text(errors="replace") if VANILLA_PACK_RESOURCES.exists() else ""
    vanilla_pack_resources_262 = (
        VANILLA_PACK_RESOURCES_262.read_text(errors="replace")
        if VANILLA_PACK_RESOURCES_262.exists()
        else ""
    )
    system_report = SYSTEM_REPORT.read_text(errors="replace") if SYSTEM_REPORT.exists() else ""
    vanilla_resource_order_test = (
        VANILLA_RESOURCE_ORDER_TEST.read_text(errors="replace")
        if VANILLA_RESOURCE_ORDER_TEST.exists()
        else ""
    )
    browser_file_persistence = BROWSER_FILE_PERSISTENCE.read_text(errors="replace") if BROWSER_FILE_PERSISTENCE.exists() else ""
    modern_runtime_support = MODERN_RUNTIME_SUPPORT.read_text(errors="replace") if MODERN_RUNTIME_SUPPORT.exists() else ""
    teavm_lock_support = TEAVM_LOCK_SUPPORT.read_text(errors="replace") if TEAVM_LOCK_SUPPORT.exists() else ""
    file_output_stream = FILE_OUTPUT_STREAM.read_text(errors="replace") if FILE_OUTPUT_STREAM.exists() else ""
    file_channel = FILE_CHANNEL.read_text(errors="replace") if FILE_CHANNEL.exists() else ""
    browser_bit_storage = BROWSER_BIT_STORAGE.read_text(errors="replace") if BROWSER_BIT_STORAGE.exists() else ""
    browser_long_array_codec = BROWSER_LONG_ARRAY_CODEC.read_text(errors="replace") if BROWSER_LONG_ARRAY_CODEC.exists() else ""
    browser_gui_item_cache = BROWSER_GUI_ITEM_CACHE.read_text(errors="replace") if BROWSER_GUI_ITEM_CACHE.exists() else ""
    browser_worldgen_scheduler = BROWSER_WORLDGEN_SCHEDULER.read_text(errors="replace") if BROWSER_WORLDGEN_SCHEDULER.exists() else ""
    browser_packet_scheduler = BROWSER_PACKET_SCHEDULER.read_text(errors="replace") if BROWSER_PACKET_SCHEDULER.exists() else ""
    browser_future_pump = BROWSER_FUTURE_PUMP.read_text(errors="replace") if BROWSER_FUTURE_PUMP.exists() else ""
    browser_density_functions = BROWSER_DENSITY_FUNCTIONS.read_text(errors="replace") if BROWSER_DENSITY_FUNCTIONS.exists() else ""
    browser_surface_biome_supplier = (
        BROWSER_SURFACE_BIOME_SUPPLIER.read_text(errors="replace")
        if BROWSER_SURFACE_BIOME_SUPPLIER.exists()
        else ""
    )
    browser_chunk_task_priority = BROWSER_CHUNK_TASK_PRIORITY.read_text(errors="replace") if BROWSER_CHUNK_TASK_PRIORITY.exists() else ""
    browser_startup_scheduler = BROWSER_STARTUP_SCHEDULER.read_text(errors="replace") if BROWSER_STARTUP_SCHEDULER.exists() else ""
    browser_gzip = BROWSER_GZIP.read_text(errors="replace") if BROWSER_GZIP.exists() else ""
    browser_render_scheduler = BROWSER_RENDER_SCHEDULER.read_text(errors="replace") if BROWSER_RENDER_SCHEDULER.exists() else ""
    performance_contract_text = (
        PERFORMANCE_CONTRACT.read_text(errors="replace")
        if PERFORMANCE_CONTRACT.exists()
        else ""
    )
    try:
        performance_contract = json.loads(performance_contract_text)
    except (TypeError, json.JSONDecodeError):
        performance_contract = {}
    chrome_chunk_benchmark = (
        CHROME_CHUNK_BENCHMARK.read_text(errors="replace")
        if CHROME_CHUNK_BENCHMARK.exists()
        else ""
    )
    chrome_performance_release_suite = (
        CHROME_PERFORMANCE_RELEASE_SUITE.read_text(errors="replace")
        if CHROME_PERFORMANCE_RELEASE_SUITE.exists()
        else ""
    )
    chrome_performance_release_suite_smoke = (
        CHROME_PERFORMANCE_RELEASE_SUITE_SMOKE.read_text(errors="replace")
        if CHROME_PERFORMANCE_RELEASE_SUITE_SMOKE.exists()
        else ""
    )
    browser_chunk_section_layers = BROWSER_CHUNK_SECTION_LAYERS.read_text(errors="replace") if BROWSER_CHUNK_SECTION_LAYERS.exists() else ""
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
    browser_client_network = BROWSER_CLIENT_NETWORK.read_text(errors="replace") if BROWSER_CLIENT_NETWORK.exists() else ""
    browser_multiplayer_recovery = (
        BROWSER_MULTIPLAYER_RECOVERY.read_text(errors="replace")
        if BROWSER_MULTIPLAYER_RECOVERY.exists()
        else ""
    )
    browser_server_pack_reuse = (
        BROWSER_SERVER_PACK_REUSE.read_text(errors="replace")
        if BROWSER_SERVER_PACK_REUSE.exists()
        else ""
    )
    browser_resource_reload_profiler = BROWSER_RESOURCE_RELOAD_PROFILER.read_text(errors="replace") if BROWSER_RESOURCE_RELOAD_PROFILER.exists() else ""
    browser_resource_reload_scheduler = BROWSER_RESOURCE_RELOAD_SCHEDULER.read_text(errors="replace") if BROWSER_RESOURCE_RELOAD_SCHEDULER.exists() else ""
    browser_unihex_loader = BROWSER_UNIHEX_LOADER.read_text(errors="replace") if BROWSER_UNIHEX_LOADER.exists() else ""
    browser_pack_overlay_compat = BROWSER_PACK_OVERLAY_COMPAT.read_text(errors="replace") if BROWSER_PACK_OVERLAY_COMPAT.exists() else ""
    browser_atlas_resource_fallback = BROWSER_ATLAS_RESOURCE_FALLBACK.read_text(errors="replace") if BROWSER_ATLAS_RESOURCE_FALLBACK.exists() else ""
    browser_authlib_gson = BROWSER_AUTHLIB_GSON.read_text(errors="replace") if BROWSER_AUTHLIB_GSON.exists() else ""
    browser_signer = BROWSER_SIGNER.read_text(errors="replace") if BROWSER_SIGNER.exists() else ""
    browser_singleplayer_client = BROWSER_SINGLEPLAYER_CLIENT.read_text(errors="replace") if BROWSER_SINGLEPLAYER_CLIENT.exists() else ""
    browser_integrated_server_main = BROWSER_INTEGRATED_SERVER_MAIN.read_text(errors="replace") if BROWSER_INTEGRATED_SERVER_MAIN.exists() else ""
    browser_lazy_data_fixer = BROWSER_LAZY_DATA_FIXER.read_text(errors="replace") if BROWSER_LAZY_DATA_FIXER.exists() else ""
    minecraft_resource_supplier = MINECRAFT_RESOURCE_SUPPLIER.read_text(errors="replace") if MINECRAFT_RESOURCE_SUPPLIER.exists() else ""
    scheduled_thread_pool_executor = SCHEDULED_THREAD_POOL_EXECUTOR.read_text(errors="replace") if SCHEDULED_THREAD_POOL_EXECUTOR.exists() else ""
    server_worker_bootstrap = SERVER_WORKER_BOOTSTRAP.read_text(errors="replace") if SERVER_WORKER_BOOTSTRAP.exists() else ""
    singleplayer_worker_smoke = SINGLEPLAYER_WORKER_SMOKE.read_text(errors="replace") if SINGLEPLAYER_WORKER_SMOKE.exists() else ""
    singleplayer_worker_runtime_smoke = SINGLEPLAYER_WORKER_RUNTIME_SMOKE.read_text(errors="replace") if SINGLEPLAYER_WORKER_RUNTIME_SMOKE.exists() else ""
    singleplayer_region_patch_log_smoke = (
        SINGLEPLAYER_REGION_PATCH_LOG_SMOKE.read_text(errors="replace")
        if SINGLEPLAYER_REGION_PATCH_LOG_SMOKE.exists()
        else ""
    )
    singleplayer_network_wakeup_smoke = (
        SINGLEPLAYER_NETWORK_WAKEUP_SMOKE.read_text(errors="replace")
        if SINGLEPLAYER_NETWORK_WAKEUP_SMOKE.exists()
        else ""
    )
    integrated_server_pump_shim_smoke = (
        INTEGRATED_SERVER_PUMP_SHIM_SMOKE.read_text(errors="replace")
        if INTEGRATED_SERVER_PUMP_SHIM_SMOKE.exists()
        else ""
    )
    session_launcher_smoke = SESSION_LAUNCHER_SMOKE.read_text(errors="replace") if SESSION_LAUNCHER_SMOKE.exists() else ""
    singleplayer_launcher = SINGLEPLAYER_LAUNCHER.read_text(errors="replace") if SINGLEPLAYER_LAUNCHER.exists() else ""
    authlib_patcher = AUTHLIB_PATCHER.read_text(errors="replace") if AUTHLIB_PATCHER.exists() else ""
    patchy_patcher = PATCHY_PATCHER.read_text(errors="replace") if PATCHY_PATCHER.exists() else ""
    vertex_array_cache_source = VERTEX_ARRAY_CACHE.read_text(errors="replace") if VERTEX_ARRAY_CACHE.exists() else ""
    vertex_array_cache_262_source = (
        VERTEX_ARRAY_CACHE_262.read_text(errors="replace")
        if VERTEX_ARRAY_CACHE_262.exists()
        else ""
    )
    wasm_hotpath_c = WASM_HOTPATH_C.read_text(errors="replace") if WASM_HOTPATH_C.exists() else ""
    build_wasm_hotpath = BUILD_WASM_HOTPATH.read_text(errors="replace") if BUILD_WASM_HOTPATH.exists() else ""
    generate_wasm_hotpath = GENERATE_WASM_HOTPATH.read_text(errors="replace") if GENERATE_WASM_HOTPATH.exists() else ""
    generate_pom = GENERATE_POM.read_text(errors="replace") if GENERATE_POM.exists() else ""
    version_profile_shell = (
        VERSION_PROFILE_SHELL.read_text(errors="replace")
        if VERSION_PROFILE_SHELL.exists()
        else ""
    )
    build_teavm = BUILD_TEAVM.read_text(errors="replace") if BUILD_TEAVM.exists() else ""
    build_server_worker = BUILD_SERVER_WORKER.read_text(errors="replace") if BUILD_SERVER_WORKER.exists() else ""
    teavm_publication_gate = (
        TEAVM_PUBLICATION_GATE.read_text(errors="replace")
        if TEAVM_PUBLICATION_GATE.exists()
        else ""
    )
    teavm_publication_gate_test = (
        TEAVM_PUBLICATION_GATE_TEST.read_text(errors="replace")
        if TEAVM_PUBLICATION_GATE_TEST.exists()
        else ""
    )
    index_template = INDEX_TEMPLATE.read_text(errors="replace") if INDEX_TEMPLATE.exists() else ""
    index_template_test = (
        INDEX_TEMPLATE_TEST.read_text(errors="replace")
        if INDEX_TEMPLATE_TEST.exists()
        else ""
    )
    build_platform_smoke = (
        BUILD_PLATFORM_SMOKE.read_text(errors="replace")
        if BUILD_PLATFORM_SMOKE.exists()
        else ""
    )
    fetch_version = FETCH_VERSION.read_text(errors="replace") if FETCH_VERSION.exists() else ""
    build_release = BUILD_RELEASE.read_text(errors="replace") if BUILD_RELEASE.exists() else ""
    build_version_release = (
        BUILD_VERSION_RELEASE.read_text(errors="replace")
        if BUILD_VERSION_RELEASE.exists()
        else ""
    )
    build_overlays = BUILD_OVERLAYS.read_text(errors="replace") if BUILD_OVERLAYS.exists() else ""
    compress_dist = COMPRESS_DIST.read_text(errors="replace") if COMPRESS_DIST.exists() else ""
    compress_brotli = COMPRESS_BROTLI.read_text(errors="replace") if COMPRESS_BROTLI.exists() else ""
    build_portable_html = BUILD_PORTABLE_HTML.read_text(errors="replace") if BUILD_PORTABLE_HTML.exists() else ""
    build_identity_helper = (
        BUILD_IDENTITY_HELPER.read_text(errors="replace")
        if BUILD_IDENTITY_HELPER.exists()
        else ""
    )
    teavm_compiler_profile = (
        TEAVM_COMPILER_PROFILE_TOOL.read_text(errors="replace")
        if TEAVM_COMPILER_PROFILE_TOOL.exists()
        else ""
    )
    teavm_compiler_profile_test = (
        TEAVM_COMPILER_PROFILE_TEST.read_text(errors="replace")
        if TEAVM_COMPILER_PROFILE_TEST.exists()
        else ""
    )
    build_portable_html_test = (
        BUILD_PORTABLE_HTML_TEST.read_text(errors="replace")
        if BUILD_PORTABLE_HTML_TEST.exists()
        else ""
    )
    portable_artifact_identity_test = (
        PORTABLE_ARTIFACT_IDENTITY_TEST.read_text(errors="replace")
        if PORTABLE_ARTIFACT_IDENTITY_TEST.exists()
        else ""
    )
    build_vanilla_assets_pack = BUILD_VANILLA_ASSETS_PACK.read_text(errors="replace") if BUILD_VANILLA_ASSETS_PACK.exists() else ""
    serve_dist = SERVE_DIST.read_text(errors="replace") if SERVE_DIST.exists() else ""
    platform_smoke = PLATFORM_SMOKE.read_text(errors="replace") if PLATFORM_SMOKE.exists() else ""
    platform_smoke_asset_loader = (
        PLATFORM_SMOKE_ASSET_LOADER.read_text(errors="replace")
        if PLATFORM_SMOKE_ASSET_LOADER.exists()
        else ""
    )
    index_html = INDEX_HTML.read_text(errors="replace") if INDEX_HTML.exists() else ""
    vanilla_assets_index = vanilla_asset_pack_index(VANILLA_ASSET_PACK)
    packed_metadata = vanilla_asset_pack_entries(
        VANILLA_ASSET_PACK,
        (
            "assets/minecraft/sounds.json",
            "assets/minecraft/font/include/unifont.json",
        ),
    )
    generated_resource_list = (
        GENERATED_RESOURCE_LIST.read_text(errors="replace")
        if GENERATED_RESOURCE_LIST.exists()
        else "\n".join(vanilla_assets_index)
    )
    generated_embedded_resource_list = (
        GENERATED_EMBEDDED_RESOURCE_LIST.read_text(errors="replace")
        if GENERATED_EMBEDDED_RESOURCE_LIST.exists()
        else ""
    )
    generated_sounds_bytes = (
        GENERATED_SOUNDS_JSON.read_bytes()
        if GENERATED_SOUNDS_JSON.exists()
        else packed_metadata.get("assets/minecraft/sounds.json", b"")
    )
    generated_unifont_bytes = (
        GENERATED_UNIFONT_JSON.read_bytes()
        if GENERATED_UNIFONT_JSON.exists()
        else packed_metadata.get("assets/minecraft/font/include/unifont.json", b"")
    )
    try:
        generated_sounds = json.loads(generated_sounds_bytes) if generated_sounds_bytes else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        generated_sounds = {}
    try:
        generated_unifont = json.loads(generated_unifont_bytes) if generated_unifont_bytes else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        generated_unifont = {}
    generated_unifont_base64 = base64.b64encode(generated_unifont_bytes)
    postprocess_teavm_js = POSTPROCESS_TEAVM_JS.read_text(errors="replace") if POSTPROCESS_TEAVM_JS.exists() else ""
    postprocess_teavm_js_test = (
        POSTPROCESS_TEAVM_JS_TEST.read_text(errors="replace")
        if POSTPROCESS_TEAVM_JS_TEST.exists()
        else ""
    )
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
    read_pixels_start = text.find("public static void readPixels(")
    read_pixels_end = text.find("private static native void drawElementsJs", read_pixels_start)
    read_pixels_section = (
        text[read_pixels_start:read_pixels_end]
        if read_pixels_start >= 0 and read_pixels_end > read_pixels_start
        else text
    )
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
    try:
        server_worker_data = SERVER_WORKER_JS.read_bytes()
    except OSError:
        server_worker_data = b""
    worker_release_profile_ok = teavm_release_profile_matches(
        WORKER_RELEASE_PROFILE,
        "singleplayer-worker",
        SERVER_WORKER_JS,
        WORKER_TEA_POM,
        (WORKER_RESOURCE_LIST,),
    )
    worker_improved_noise_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"const h000=",
            (
                b"const v111=",
                b"localX*localX*localX",
                b"fadeY*fadeY*fadeY",
                b"localZ*localZ*localZ",
            ),
            (b"const grad=", b"__gaiusImprovedNoiseGradients"),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"const h000=",
            ((b".data", 1),),
        )
    )
    worker_bit_storage_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"const low=words[wordIndex]>>>0;const high=",
            (
                b"__gaiusBitStorageWords",
                b"new Uint32Array(source.buffer,source.byteOffset,source.length*2)",
                b"return value&numericMask|0;",
                b"const shift=BigInt(offset)",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"const low=words[wordIndex]>>>0;const high=",
            ((b".data", 1),),
        )
        and generated_assignment_matches(
            server_worker_data,
            b"const numericReplacement=",
            (
                b"__gaiusBitStorageWords",
                b"new Uint32Array(source.buffer,source.byteOffset,source.length*2)",
                b"return previous&numericMask|0;",
                b"const shift=BigInt(offset)",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"const numericReplacement=",
            ((b".data", 1),),
        )
    )
    worker_perlin_wrap_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"const period=33554432",
            (
                b"if(!Number.isFinite(",
                b"rounded=Math.floor(scaled)",
                b'BigInt("9223372036854775807")',
                b"return ",
                b"-rounded*period",
            ),
        )
        and generated_assignment_is_called(server_worker_data, b"const period=33554432")
    )
    worker_climate_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"__gaiusClimateValue0",
            (
                b"__gaiusClimateValue6",
                b"values.length<7",
                b"Number(values[6])",
            ),
            (b"WeakMap", b"Object.keys"),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"__gaiusClimateValue0",
            ((b".data", 2),),
        )
    )
    worker_block_pos_generated = (
        re.search(
            rb"return Number\(BigInt\.asIntN\(26,[A-Za-z_$][A-Za-z0-9_$]*"
            rb">>BigInt\(38\)\)\)\|0;",
            server_worker_data,
        )
        is not None
        and re.search(
            rb"return Number\(BigInt\.asIntN\(12,[A-Za-z_$][A-Za-z0-9_$]*\)\)\|0;",
            server_worker_data,
        )
        is not None
        and re.search(
            rb"return Number\(BigInt\.asIntN\(26,[A-Za-z_$][A-Za-z0-9_$]*"
            rb">>BigInt\(12\)\)\)\|0;",
            server_worker_data,
        )
        is not None
        and generated_assignment_matches(
            server_worker_data,
            b"const packed=BigInt.asUintN(26,",
            (
                b"BigInt.asUintN(12,",
                b"<<BigInt(38)",
                b"<<BigInt(12)",
                b"return BigInt.asIntN(64,packed)",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"const packed=BigInt.asUintN(26,",
        )
    )
    worker_biome_zoom_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"__gaiusBiomeManagerConstants",
            (
                b'BigInt("6364136223846793005")',
                b'BigInt("1442695040888963407")',
                b"const fractionX=",
                b"const fractionZ=",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"__gaiusBiomeManagerConstants",
        )
    )
    worker_aquifer_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"__gaiusAquiferDecodedLocations",
            (
                b"new WeakMap()",
                b'BigInt("9223372036854775807")',
                b"Math.imul(dx,dx)",
                b"target[7]=index3",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"__gaiusAquiferDecodedLocations",
            ((b".data", 2),),
        )
    )
    worker_beardifier_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"__gaiusBeardifierMath",
            (
                b"new BigInt64Array(buffer)",
                b'BigInt("6910469410427058090")',
                b"Math.imul(kernelZ,576)",
                b"packedPieces.length",
                b"packedJunctions.length",
            ),
        )
        and generated_assignment_is_called(
            server_worker_data,
            b"__gaiusBeardifierMath",
        )
    )
    worker_lerp_generated = (
        generated_assignment_matches(
            server_worker_data,
            b"const z0y0=",
            (b"const z0y1=", b"const z1y0=", b"const z1y1=", b"return z0+"),
        )
        and generated_assignment_is_called(server_worker_data, b"const z0y0=")
    )
    worker_density_generated = all(
        generated_assignment_is_called(server_worker_data, anchor)
        for anchor in (
            b"Unknown MulOrAdd density transform",
            b"Unknown mapped density transform",
        )
    ) and generated_assignment_matches(
        server_worker_data,
        b"Unknown mapped density transform",
        (b"Math.abs(", b"Math.min(Math.max(", b"clamped*clamped*clamped"),
    )
    worker_java_hotpath_provenance = (
        worker_release_profile_ok
        and file_contains(SERVER_WORKER_JS, "/*gaius-java-finite-long-cast*/")
        and file_contains(
            SERVER_WORKER_JS,
            "/*gaius-integrated-server-input-coroutine*/",
        )
    )
    checks = [
        (
            "Browser output streams truncate existing virtual files before replacement writes",
            "truncateIfRequested" in file_output_stream
            and "accessor.resize(0)" in file_output_stream
            and "accessor.seek(0)" in file_output_stream
            and "patchDefaultFileSystemProviderStreams" in classlib_patcher
            and '"(Ljava/lang/String;Lorg/teavm/runtime/fs/VirtualFileAccessor;Z)V"'
                in classlib_patcher
            and "Browser output stream did not truncate an existing file" in platform_smoke,
        ),
        (
            "Browser FileChannel preserves existing region files unless truncation is explicit",
            "virtualFile.createAccessor(read, write, write)" in file_channel
            and "if (changed)" in file_channel
            and "accessor.resize(0)" in file_channel
            and "READ+WRITE reopen truncated an existing region file" in platform_smoke
            and "Region payload was lost after update and reopen" in platform_smoke,
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
            "TeaVM ZIP streams use local-header name lengths for server packs",
            "TZipFile.getInputStream local-header offset was not found" in classlib_patcher
            and "TZipFile.getInputStream local nameLen replacement was not found" in classlib_patcher
            and "offsetConstant.cst = 26L" in classlib_patcher
            and "rewriteLocalZipEntryName" in platform_smoke
            and '"pack.png"' in platform_smoke
            and "ignored the local-header name length" in platform_smoke,
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
            "webGlPixelAlignment" in text
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
            "BrowserOpenGL preserves pixel-unpack-buffer offsets for WebGL2 texture uploads",
            "PIXEL_UNPACK_BUFFER = 0x88EC" in text
            and "boundBufferForTargetJs(PIXEL_UNPACK_BUFFER) != 0" in tex_sub_section
            and "texSubImage2DOffsetJs" in tex_sub_section
            and "'texSubImage2D-pbo'" in tex_sub_section
            and tex_sub_section.find("texSubImage2DOffsetJs")
                < tex_sub_section.find("pointerBytes(pixels"),
        ),
        (
            "BrowserOpenGL separates client-memory and pixel-pack-buffer readback",
            "PIXEL_PACK_BUFFER = 0x88EB" in text
            and "boundBufferForTargetJs(PIXEL_PACK_BUFFER) != 0" in read_pixels_section
            and "readPixelsOffsetJs" in read_pixels_section
            and "readPixelsBytesJs" in read_pixels_section
            and "pointerBytes(pixels, pixelReadLength" in read_pixels_section
            and read_pixels_section.find("readPixelsOffsetJs")
                < read_pixels_section.find("pointerBytes(pixels"),
        ),
        (
            "BrowserOpenGL tracks PACK alignment, row length, and skips",
            "packAlignment" in text
            and "packRowLength" in text
            and "packSkipRows" in text
            and "packSkipPixels" in text
            and "case 0x0D02" in text
            and "case 0x0D03" in text
            and "case 0x0D04" in text
            and "case 0x0D05" in text,
        ),
        (
            "Platform smoke covers the exact mapped font-atlas upload paths",
            "testMappedPixelBufferTextureUpload()" in platform_smoke
            and "testMappedR8PixelBufferTextureUpload()" in platform_smoke
            and platform_smoke.count("MemoryUtil.memCopy(source, mappedView)") == 2
            and "int fontBufferBytes = 128 * 128 * 4" in platform_smoke
            and "Mapped font-sized buffer copy truncated its tail" in platform_smoke
            and "GL33C.GL_R8" in platform_smoke
            and "GL11.GL_RED" in platform_smoke
            and "Mapped R8 pixel-buffer texture upload changed RGBA bytes" in platform_smoke,
        ),
        (
            "LWJGL OpenGL patcher delegates the 26.2 GL11C core to WebGL",
            'for (String owner : new String[] {"GL11", "GL11C"})' in patcher
            and '"glGetString", "(I)Ljava/lang/String;", "getString"' in patcher
            and '"glGetInteger", "(I)I", "getInteger"' in patcher
            and '"glGetFloat", "(I)F", "getFloat"' in patcher
            and '"glClear", "(I)V", "clear"' in patcher
            and '"glTexSubImage2D", "(IIIIIIIIJ)V", "texSubImage2D"' in patcher
            and "testBackendInitialization" in platform_smoke
            and "Minecraft GPU device creation" in platform_smoke,
        ),
        (
            "Minecraft DetectedVersion follows the active version profile",
            "String minecraftVersion = args.length >= 3 ? args[2] : \"1.21.11\""
            in client_patcher
            and "new LdcInsnNode(minecraftVersion)" in client_patcher
            and '"$version"' in build_overlays,
        ),
        (
            "Minecraft 26.2 VAO cache allocates only on misses and bypasses hot map lookups",
            "private final BrowserVaoCache cache" in vertex_array_cache_262_source
            and "private final VertexArrayKey lookupKey" in vertex_array_cache_262_source
            and "private static final int HOT_CACHE_SIZE = 256" in vertex_array_cache_262_source
            and "vertexBindings.clone()" in vertex_array_cache_262_source
            and "Arrays.asList" not in vertex_array_cache_262_source
            and "System.identityHashCode(vertexBinding)" in vertex_array_cache_262_source
            and "(accessCount & 63) == 0" in vertex_array_cache_262_source
            and "GL30.glBindVertexArray" in vertex_array_cache_262_source,
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
            "BrowserOpenGL fuses command-encoder element-buffer binding into draw dispatch",
            '"type", "indexBytes", "instances", "elementBuffer"' in text
            and "const vao=state.getVaoEmu();" in text
            and "const nextId=elementBuffer|0;" in text
            and "state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);" in text
            and "int type, int indexBytes, int instances, int elementBuffer, int baseInstance);" in text,
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
            and "16 * 1024 * 1024" in text
            and "64 * 1024 * 1024" in text
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
            and "state.releaseVaoMisalignedBuffers(vao)" in text,
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
            "Chunk packet packed-long arrays decode in browser batches",
            "patchFriendlyByteBufBrowserLongArray" in client_patcher
            and "net/minecraft/network/FriendlyByteBuf.class" in client_patcher
            and "dev/gaius/browser/BrowserLongArrayCodec" in client_patcher
            and "readFixedSizeLongArray" in browser_long_array_codec
            and "LONGS_PER_BATCH = 512" in browser_long_array_codec
            and "buffer.readBytes(bytes, 0, byteCount)" in browser_long_array_codec
            and "view.getBigInt64(index * 8, false)" in browser_long_array_codec
            and "decodeBigEndianFallback" in browser_long_array_codec
            and "testNetworkPackedLongs" in platform_smoke,
        ),
        (
            "SimpleBitStorage unpack has browser Wasm/JS hot-path hook",
            "patchSimpleBitStorageBrowserUnpack" in client_patcher
            and "net/minecraft/util/SimpleBitStorage.class" in client_patcher
            and "dev/gaius/browser/BrowserBitStorage" in client_patcher
            and "([J[IIII)Z" in client_patcher
            and "([JIII)I" in client_patcher
            and "([JIIII)I" in client_patcher
            and "public static native boolean unpack" in browser_bit_storage
            and browser_bit_storage.count("@JSByRef long[] packed") == 3
            and "@JSByRef int[] output" in browser_bit_storage
            and "public static native int get(" in browser_bit_storage
            and "public static native int getAndSet(" in browser_bit_storage
            and "long mask" not in browser_bit_storage
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
            and '"browserMask"' not in client_patcher
            and '"dev/gaius/browser/BrowserBitStorage"' in client_patcher
            and 'find(node, "getFirstAvailable", "(I)I")' in client_patcher
            and 'new String[] {"getFirstAvailable", "getHighestTaken"}' in client_patcher
            and 'find(node, "setHeight", "(III)V")' in client_patcher,
        ),
        (
            "BrowserOpenGL adapts shader attribute pointer types before draw",
            "programAttribs" in text
            and "refreshProgramAttribs" in text
            and "initializeAttribTypeAdaptJs();" in text
            and "expectedAttribInteger" in text
            and "recordAttribPointerAdapt" in text
            and "attribTypePointerAdapts" in text
            and "effectiveInteger" in text
            and "ensureProgramAttribTypes" in text
            and "attribTypeRepairs" in text
            and "gl.getActiveAttrib" in text
            and "vertexAttribIPointer" in text,
        ),
        (
            "BrowserOpenGL translates ModelEngine entity shader integer arithmetic for WebGL",
            '"UV0 * SKINRES", "UV0 * float(SKINRES)"' in text
            and '"SPACING * (partId + 1)", "SPACING * float(partId + 1)"' in text
            and '"(1 - fade)", "(1.0 - fade)"' in text
            and "WebGL ModelEngine vertex shader compatibility failed" in platform_smoke
            and "WebGL ModelEngine fragment shader compatibility failed" in platform_smoke,
        ),
        (
            "VertexArrayCache preserves vanilla integer UV and normalized normal/color bindings",
            "private static boolean shouldNormalize" in vertex_array_cache_source
            and "VertexFormatElement.Usage.COLOR" in vertex_array_cache_source
            and "VertexFormatElement.Usage.NORMAL" in vertex_array_cache_source
            and "VertexFormatElement.Usage.UV" not in vertex_array_cache_source.split(
                "private static boolean shouldNormalize", 1
            )[1].split("private static final class VertexArrayKey", 1)[0]
            and "VertexFormatElement.Usage.GENERIC" not in vertex_array_cache_source.split(
                "private static boolean shouldNormalize", 1
            )[1].split("private static final class VertexArrayKey", 1)[0]
            and "boolean normalized = shouldNormalize(element);" in vertex_array_cache_source
            and "element.type() != VertexFormatElement.Type.FLOAT" in vertex_array_cache_source,
        ),
        (
            "VertexArrayCache bypasses the render-thread assertion wrapper in its VAO hot path",
            vertex_array_cache_source.count("GL30.glBindVertexArray(") == 5
            and "GlStateManager._glBindVertexArray(" not in vertex_array_cache_source,
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
            "executeDraw=function(kind,mode,a,b,c,d,e,f)" in text
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
            and "this.deleteShiftedIndexEntry(oldestKey,true)" in text
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
                "let source=this.bufferBytes.get(elementBuffer)",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            and text.find(
                "const cached=vao.shiftedIndexLast",
                text.find("cacheShiftedIndexBuffer=function"),
            )
            < text.find(
                "let source=this.bufferBytes.get(elementBuffer)",
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
            "BrowserOpenGL bounds aligned attribute buffers and releases VAO reverse references",
            "alignedAttribCacheTotalBytes:0" in text
            and "maxAlignedAttribCacheBytes=function()" in text
            and "trimAlignedAttribCache=function(incomingBytes)" in text
            and "deleteAlignedAttribEntry=function(key,evicted)" in text
            and "alignedAttribBudgetFallbacks" in text
            and "shiftedIndexEntries:new Set()" in text
            and "releaseVaoShiftedIndexRefs=function(vao)" in text
            and "detachShiftedIndexEntry=function(entry)" in text
            and "state.releaseVaoShiftedIndexRefs(vao);" in text,
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
            "Browser block targeting follows the current render camera every frame",
            "pickFromRenderCamera(minecraft, camera, cameraPosition)"
            in browser_targeting
            and "camera.isInitialized()" in browser_targeting
            and "camera.entity()" in browser_targeting
            and "camera.position()" in browser_targeting
            and "new Vec3(camera.forwardVector()).normalize()" in browser_targeting
            and "minecraft.level.clip" in browser_targeting
            and "ProjectileUtil.getEntityHitResult" in browser_targeting
            and "EntitySelector.CAN_BE_PICKED" in browser_targeting
            and "minecraft.player.raycastHitResult" not in browser_targeting
            and "alignBlockHitToCamera" not in browser_targeting
            and "hasLastCamera" not in browser_targeting
            and "lastForward" not in browser_targeting,
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
            "BrowserOpenGL reports real client chunk and spawn-collision readiness",
            "clientLevel.getChunkSource().getLoadedChunksCount()" in text
            and "clientLevel.noCollision(entity)" in text
            and '"loadedChunkCount"' in text
            and '"collisionFree"' in text,
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
            "BrowserOpenGL exports exact mapped-buffer flush sub-ranges",
            "ByteBuffer slice = copy.slice().order(buffer.order());" in text
            and "return Int8Array.fromJavaBuffer(slice);" in text
            and "bufferSubDataJs(target, absoluteOffset, bytesSlice(mapped.buffer, offset, length));"
                in text
            and "namedBufferSubDataJs(buffer, absoluteOffset, bytesSlice(mapped.buffer, offset, length));"
                in text,
        ),
        (
            "BrowserOpenGL keeps buffer subdata and copy state within allocated ranges",
            "const validRange=Number.isFinite(start)" in text
            and "Number.isFinite(known) && known>=0 && end<=known" in text
            and "const sourceKnown=sourceBuffer ? state.bufferSizes.get(sourceBuffer)" in text
            and "const targetKnown=targetBuffer ? state.bufferSizes.get(targetBuffer)" in text
            and "sourceEnd<=sourceKnown" in text
            and "targetEnd<=targetKnown" in text
            and text.count("const sameBufferOverlap=sourceBuffer===targetBuffer && length>0") >= 2
            and text.count("&& !sameBufferOverlap;") >= 2
            and "if (validRange && length>0) state.shadowBufferSubDataForTarget" in text
            and text.count("if (validRange && length>0)") >= 2
            and "if (targetBuffer && validRange && length>0)" in text
            and "if (validRange && length>0)" in text
            and "state.bufferSizes.set(buffer,end)" not in text
            and "state.bufferSizes.set(targetBuffer,end)" not in text,
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
            "BrowserOpenAL retires naturally ended Web Audio nodes without dropping queued buffers",
            "function retireScheduledEntry(source, entry)" in openal_bridge
            and "node.onended = function() { retireScheduledEntry(source, entry); };"
                in openal_bridge
            and "if (entry.queued)" in openal_bridge
            and "state.stats.webAudioNaturalEnds++" in openal_bridge
            and "if (node) node.onended = null;" in openal_bridge,
        ),
        (
            "BrowserOpenAL honors the directional-audio panning mode",
            "directionalAudio: false" in openal_bridge
            and "function applyPanningModel(panner)" in openal_bridge
            and "state.directionalAudio ? 'HRTF' : 'equalpower'" in openal_bridge
            and "public static native void setDirectionalAudio(boolean enabled);"
                in openal_bridge,
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
            "Minecraft audio listener forwards the live camera transform to Web Audio",
            "patchBrowserAudioListener" in client_patcher
            and '"setDirectionalAudio"' in client_patcher
            and '"listener3f"' in client_patcher
            and '"listenerOrientation"' in client_patcher
            and 'appendListenerVector(code, transform, "position")' in client_patcher
            and 'appendListenerVector(code, transform, "forward")' in client_patcher
            and 'appendListenerVector(code, transform, "up")' in client_patcher,
        ),
        (
            "Browser Unihex parser bulk-loads font ZIP entries without per-byte stream I/O",
            "class BrowserUnihexLoader" in browser_unihex_loader
            and "zip.readAllBytes()" in browser_unihex_loader
            and "prepareOverrides(rawOverrides)" in browser_unihex_loader
            and "findOverrideDimensions" in browser_unihex_loader
            and "input.read()" not in browser_unihex_loader
            and "ByteArrayList" not in browser_unihex_loader
            and "patchUnihexProviderBrowserBulkParser" in client_patcher
            and '"net/minecraft/client/gui/font/providers/BrowserUnihexLoader"' in client_patcher,
        ),
        (
            "Client patcher caches immutable draw metadata outside the per-draw hot path",
            "patchGlRenderPipelineDrawMetadata" in client_patcher
            and '"gaius$vertexFormat"' in client_patcher
            and '"gaius$drawMode"' in client_patcher
            and "Opcodes.SIPUSH, 5121" in client_patcher
            and "Opcodes.IADD" in client_patcher,
        ),
        (
            "Platform smoke decodes and plays the packaged eating sound through OpenAL",
            "testEatingSoundAsset" in platform_smoke
            and "assets/minecraft/sounds/random/eat1.ogg" in platform_smoke
            and "JOrbisAudioStream" in platform_smoke
            and "decoder.readAll()" in platform_smoke
            and "AL_FORMAT_MONO16" in platform_smoke
            and "AL_FORMAT_STEREO16" in platform_smoke
            and "Eating sound did not decode to playable PCM" in platform_smoke
            and "openPackagedAsset" in platform_smoke
            and "__gaiusVanillaAssets" in platform_smoke
            and "vanilla-assets.pack.gz" in platform_smoke_asset_loader
            and "DecompressionStream" in platform_smoke_asset_loader
            and "window.__gaiusVanillaAssets" in platform_smoke_asset_loader,
        ),
        (
            "Browser Netty channel batches cheap multiplayer frames within a time budget",
            "class BrowserWebSocketChannel" in netty_browser_channel
            and "globalThis.__gaiusNettyBridge" in netty_browser_channel
            and "globalThis.__gaiusNetworkStats" in netty_browser_channel
            and "new WebSocket(candidate.url)" in netty_browser_channel
            and "relayNodeCandidate" in netty_browser_channel
            and "normalizeRelayUrl" in netty_browser_channel
            and "params.getAll('relay')" in netty_browser_channel
            and "recordRelayNodeSuccess" in netty_browser_channel
            and "relayNodeFailures" in netty_browser_channel
            and "bridgeToken(candidate)" in netty_browser_channel
            and "relayNodes: Object.create(null)" in netty_browser_channel
            and "__gaiusLocalServerPorts" in netty_browser_channel
            and "localPort.postMessage" in netty_browser_channel
            and "const control = {type: 'connect'" in netty_browser_channel
            and "copyBytes(ByteBuf buffer, int index, int length)" in netty_browser_channel
            and "pipeline.fireChannelRead(Unpooled.wrappedBuffer(bytes))" in netty_browser_channel
            and "MAX_CHUNKS_PER_PUMP = 16" in netty_browser_channel
            and "MAX_BYTES_PER_PUMP = 1024 * 1024" in netty_browser_channel
            and "MAX_MILLIS_PER_PUMP = 2.0" in netty_browser_channel
            and "private boolean pumping;" in netty_browser_channel
            and "if (!open || pumping)" in netty_browser_channel
            and "pumping = true" in netty_browser_channel
            and "pumping = false" in netty_browser_channel
            and "bytesPumped < MAX_BYTES_PER_PUMP" in netty_browser_channel
            and "monotonicMillis() - pumpStarted >= MAX_MILLIS_PER_PUMP" in netty_browser_channel
            and "recordPump(" in netty_browser_channel
            and "ConcurrentHashMap" not in netty_browser_channel
            and "AtomicInteger" not in netty_browser_channel
            and "Collections.newSetFromMap" not in netty_browser_channel,
        ),
        (
            "Browser Netty channel batches local stream writes while bounding both queues",
            "const maximumInboundQueueBytes = 64 * 1024 * 1024" in netty_browser_channel
            and "const inboundPauseBytes = 24 * 1024 * 1024" in netty_browser_channel
            and "const inboundResumeBytes = 8 * 1024 * 1024" in netty_browser_channel
            and "const maximumWebSocketBufferedBytes = 4 * 1024 * 1024" in netty_browser_channel
            and "const maximumOutboundQueueBytes = 16 * 1024 * 1024" in netty_browser_channel
            and "const maximumLocalBatchBytes = 16 * 1024" in netty_browser_channel
            and "function requestFlush(entry, delayMillis)" in netty_browser_channel
            and "entry.outboundFlushScheduled" in netty_browser_channel
            and "entry.outboundFlushHandle = setTimeout(function()" in netty_browser_channel
            and "state.stats.localFlushes += localFlushBatches" in netty_browser_channel
            and "state.stats.localFlushFrames += localFlushFrames" in netty_browser_channel
            and "state.stats.localReceivedFrames++" in netty_browser_channel
            and "state.stats.localReceivedBytes += source.byteLength" in netty_browser_channel
            and "state.stats.peakLocalFlushBytes" in netty_browser_channel
            and "const batch = new Uint8Array(localBatchBytes);" in netty_browser_channel
            and "batch.set(part, offset);" in netty_browser_channel
            and "entry.localPort.postMessage(batch.buffer, [batch.buffer])" in netty_browser_channel
            and "setInboundPaused(entry, true)" in netty_browser_channel
            and "setInboundPaused(entry, false)" in netty_browser_channel
            and "{type: 'flow', paused: !!paused}" in netty_browser_channel
            and "webSocketBlocked(entry.ws)" in netty_browser_channel
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
            "Netty heap buffers remove signature-polymorphic VarHandle branches",
            "patchHeapByteBufUtil" in netty_browser_patcher
            and '"io/netty/buffer/HeapByteBufUtil"' in netty_browser_patcher
            and 'call.name.equals("hasVarHandle")' in netty_browser_patcher
            and "branch.getOpcode() != Opcodes.IFEQ" in netty_browser_patcher
            and "contains no guarded VarHandle accessors" in netty_browser_patcher,
        ),
        (
            "Platform smoke verifies browser Netty connect and local stream batching",
            "testBrowserNetwork()" in platform_smoke
            and "new BrowserWebSocketChannel()" in platform_smoke
            and "runLocalNetworkFrameSmoke()" in platform_smoke
            and "runNettyNetworkFrameSmoke()" in platform_smoke
            and "bridge.open(socketId" in platform_smoke
            and platform_smoke.count("bridge.send(socketId") == 4
            and "Browser Netty local batching failed" in platform_smoke
            and "(local.frames|0) === 1" in platform_smoke
            and "(stats.localFlushes|0) >= 2" in platform_smoke
            and "(stats.localFlushFrames|0) === 4" in platform_smoke
            and "scheduleNetworkRoundTripCheck()" in platform_smoke
            and "(stats.localReceivedFrames|0) === 1" in platform_smoke
            and "(stats.localReceivedBytes|0) === 6" in platform_smoke,
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
            and "archive.getInputStream(entry).readAllBytes()" in platform_smoke
            and "rewriteLocalZipEntryName" in platform_smoke,
        ),
        (
            "Browser safely applies atlas-only future resource-pack overlays",
            "mergeSafeAtlasOverlays" in browser_pack_overlay_compat
            and "atlasOnlyDirectories" in browser_pack_overlay_compat
            and "isAtlasJson" in browser_pack_overlay_compat
            and "BrowserPackOverlayCompat" in client_patcher
            and "patchFilePackResourcesBrowserAtlasOverlays" in client_patcher
            and "testBrowserAtlasOverlayCompatibility()" in platform_smoke
            and '"unsafe_future/assets/minecraft/shaders/core/entity.fsh"' in platform_smoke,
        ),
        (
            "Browser atlas sources retain a narrow vanilla entity fallback",
            "vanillaEntityFallback" in browser_atlas_resource_fallback
            and 'textureId.getPath().startsWith("entity/")' in browser_atlas_resource_fallback
            and "BrowserAtlasResourceFallback" in client_patcher
            and "patchSingleFileBrowserAtlasFallback" in client_patcher
            and "testBrowserAtlasResourceFallback()" in platform_smoke,
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
            'const allowedHosts = parseList("GAIUS_ALLOWED_HOSTS", ["*"])' in bridge_config
            and '"GAIUS_ALLOWED_RESOURCE_PACK_HOSTS"' in bridge_config
            and "allowedResourcePackHosts" in bridge_config
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
            and "targets.push(request)" in bridge_main
            and "tunnelConnectAbortController" in bridge_main
            and 'type: "connecting"' in bridge_main
            and "targetConnectTimeoutMs: config.connectTimeoutMs" in bridge_main
            and 'signal.addEventListener("abort", abort' in bridge_main,
        ),
        (
            "Browser bridge proxies resource packs and Mojang authentication with security gates",
            '"/proxy/resource-pack"' in bridge_main
            and "config.allowedResourcePackHosts" in bridge_main
            and '"resource-pack-proxy"' in bridge_main
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
            and "fetchWithValidatedRedirects" in bridge_main
            and 'proxyKind === "resource-pack" || proxyKind === "texture"' in bridge_main
            and "retrying ${proxyKind} fetch" in bridge_main
            and "Retrying transient proxy response" in bridge_main
            and 'proxyKind === "auth" || proxyKind === "realms"' in bridge_main
            and 'const retryable = init.method === "GET"' in bridge_main
            and "downloadResourcePackWithRetries" in bridge_main
            and "spoolResponseBody" in bridge_main
            and "gaius-relay-resource-pack-" in bridge_main
            and "retrying interrupted resource-pack body" in bridge_main
            and "watchProxyClient" in bridge_main
            and "ProxyClientDisconnectedError" in bridge_main
            and "acquireResourcePackDownload" in bridge_main
            and "resourcePackCache" in bridge_main
            and '"resource-pack-cache"' in bridge_main
            and '"GAIUS_RESOURCE_PACK_CACHE_MS"' in bridge_config
            and '"GAIUS_RESOURCE_PACK_CACHE_BYTES"' in bridge_config
            and '"GAIUS_RESOURCE_PACK_CACHE_ENTRIES"' in bridge_config
            and '"content-length": String(resourcePackDownload.byteLength)' in bridge_main
            and "resourcePackAttempts !== 3" in bridge_smoke
            and "proxiedResourcePackHash !== resourcePackHash" in bridge_smoke
            and "cachedResourcePackResponse" in bridge_smoke
            and "slowResourcePackAttempts !== 1" in bridge_smoke,
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
            "RelayNode keeps disabled tunnel tracing off the multiplayer packet hot path",
            re.search(
                r'if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+'
                r'`server data .*?toString\("hex"\)',
                bridge_main,
                re.DOTALL,
            ) is not None
            and re.search(
                r'if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+'
                r'`client data .*?toString\("hex"\)',
                bridge_main,
                re.DOTALL,
            ) is not None
            and bridge_main.count('if (traceTunnel && protocolPhase === "play")') == 2
            and re.search(
                r'if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+'
                r'`proxied .*?response\.toString\("hex"\)',
                bridge_main,
                re.DOTALL,
            ) is not None,
        ),
        (
            "RelayNode arms stall-tick timers only for framed PLAY tunnels",
            'const armClientStallTimer = () => {' in bridge_main
            and 'const clearClientStallTimer = () => {' in bridge_main
            and 'activeClientStallTimers++' in bridge_main
            and 'activeClientStallTimers = Math.max(0, activeClientStallTimers - 1)'
            in bridge_main
            and '"runtime-telemetry"' in bridge_main
            and 'runtime: relayRuntimeSnapshot()' in bridge_main
            and re.search(
                r'updateTcpReadState = \(\) => \{.*?\};\s+'
                r'clientStallTimer = setInterval',
                bridge_main,
                re.DOTALL,
            ) is None,
        ),
        (
            "Browser bridge tracks PLAY and reversible reconfiguration across framed streams",
            "let clientFrameBuffer = Buffer.alloc(0)" in bridge_main
            and "Buffer.concat([clientFrameBuffer, clientData])" in bridge_main
            and "while (clientFrameBuffer.byteLength > 0)" in bridge_main
            and 'let protocolPhase = "login"' in bridge_main
            and "minecraftProfile.play.clientboundStartConfiguration" in bridge_main
            and "server started PLAY to CONFIGURATION transition" in bridge_main
            and "minecraftProfile.configuration.serverboundFinish" in bridge_main
            and "client acknowledged PLAY to CONFIGURATION transition" in bridge_main
            and "re-entered PLAY after configuration cycle" in bridge_main
            and "armed synthetic play tick for initial spawn" in bridge_main
            and "createPayloadlessMinecraftFrame" in bridge_main
            and "minecraftProfile.play.serverboundClientTickEnd" in bridge_main
            and "observed play tick for stall proxy" in bridge_main
            and "lastServerPlayPacket" in bridge_main
            and "lastClientPlayPacket" in bridge_main
            and "playStartedAt" in bridge_main
            and "encodePacket(minecraftProfile.configuration.serverboundFinish" in bridge_smoke
            and "splitClientFrames.subarray(0, 4)" in bridge_smoke
            and "minecraftProfile.play.serverboundClientTickEnd" in bridge_smoke
            and "minecraftProfile.play.clientboundStartConfiguration" in bridge_smoke
            and "Translator node injected PLAY ticks during CONFIGURATION" in bridge_smoke
            and "GAIUS_SMOKE_PLAY_SOAK_MS" in bridge_smoke
            and "synthetic initial play tick" in bridge_smoke
            and "splitClientFrames.subarray(0, 4)" in bridge_smoke
            and "splitClientFrames.subarray(4)" in bridge_smoke,
        ),
        (
            "Browser multiplayer probes direct plugins and fails over temporary relay nodes",
            "directPluginUrl" in netty_browser_channel
            and "gaius.bridgeNodes" in netty_browser_channel
            and "params.getAll('bridge')" in netty_browser_channel
            and "openRemoteCandidate" in netty_browser_channel
            and "relayTunnelConnectTimeout" in netty_browser_channel
            and "targetConnectTimeoutMs" in netty_browser_channel
            and "perTarget * 2 + 5000" in netty_browser_channel
            and "directPluginTunnelConnectTimeout" in netty_browser_channel
            and "configured + 1000" in netty_browser_channel
            and "candidate.direct ? 800 : relayTunnelConnectTimeout(candidate)"
            in netty_browser_channel
            and "slowDirectPluginBudget: true" in browser_relay_routing_smoke
            and "No Gaius direct endpoint or relay node could reach the server" in netty_browser_channel
            and "relayFailovers" in netty_browser_channel,
        ),
        (
            "Browser multiplayer discovers curated public RelayNodes for online and portable clients",
            relay_registry.get("kind") == "gaius-relay-registry"
            and relay_registry.get("protocolVersion") == 1
            and isinstance(relay_registry.get("nodes"), list)
            and len(relay_registry.get("nodes", [])) <= 64
            and isinstance(relay_registry.get("registries"), list)
            and relay_registry_text == dist_relay_registry_text
            and 'cp "$root/relay-nodes.json" "$dist/relay-nodes.json"'
                in build_release
            and "defaultRelayRegistryUrl" in netty_browser_channel
            and "raw.githubusercontent.com/TypeThe0ry/Gaius/main/relay-nodes.json"
                in netty_browser_channel
            and "function relayRegistryUrls()" in netty_browser_channel
            and "function loadRelayRegistry(url)" in netty_browser_channel
            and "function discoverRelayNodes()" in netty_browser_channel
            and "maximumRelayRegistryNodes = 64" in netty_browser_channel
            and "maximumRelayRegistryUrls = 32" in netty_browser_channel
            and "maximumNestedRegistriesPerResponse = 16" in netty_browser_channel
            and "gaius.relayRegistries" in netty_browser_channel
            and "params.getAll('relayRegistry')" in netty_browser_channel
            and "relayRegistrySuccesses" in netty_browser_channel
            and "relayRegistryFailures" in netty_browser_channel
            and "relayRegistryNodesLoaded" in netty_browser_channel
            and "relayRegistryRegistriesLoaded" in netty_browser_channel
            and "gaius-relay-registry" in browser_relay_routing_smoke
            and '7, "registry", "wss://registry-node.example/tunnel"'
                in browser_relay_routing_smoke
            and '8, "capacity", "wss://registry-node.example/tunnel"'
                in browser_relay_routing_smoke
            and '10, "nested", "wss://dynamic-node.example/tunnel"'
                in browser_relay_routing_smoke
            and "verifyRelayFailover" in browser_relay_routing_smoke
            and "relayRegistrySuccesses" in browser_relay_routing_smoke
            and "relayFailovers" in browser_relay_routing_smoke
            and "Public RelayNode registry" in relay_nodes_doc
            and "separate" in relay_nodes_doc
            and 'Object.hasOwn(node, "token")' in relay_registry_check
            and 'url.protocol !== "wss:"' in relay_registry_check
            and "nodes must contain at most 64 entries" in relay_registry_check
            and "registries must contain at most 16 entries" in relay_registry_check
            and "node tools/check-relay-registry.mjs" in repository_guard,
        ),
        (
            "Public RelayNode smoke verifies portable-origin status traffic and lease cleanup",
            'process.env.GAIUS_PUBLIC_RELAY_ORIGIN ?? "null"' in public_relay_smoke
            and 'type: "connect"' in public_relay_smoke
            and "encodePacket(0, handshake)" in public_relay_smoke
            and "readStatusResponse(responseBuffer)" in public_relay_smoke
            and "beforeActive + 1" in public_relay_smoke
            and "waitForLeaseRelease" in public_relay_smoke
            and "syntheticDnsFallback" in public_relay_smoke
            and 'capabilities?.includes("target-attestation")' in public_relay_smoke
            and "control.host === target.host" in public_relay_smoke
            and '"smoke:public": "node public-relay-smoke.mjs"' in bridge_package,
        ),
        (
            "RelayNodes attest the requested target and browser failover rejects mismatches",
            '"target-attestation"' in bridge_main
            and "host: request.host" in bridge_main
            and "port: request.port" in bridge_main
            and "candidate.targetAttestation" in netty_browser_channel
            and "!candidate.direct && !attestationPresent" in netty_browser_channel
            and "relayTargetAttestationFailures" in netty_browser_channel
            and 'manifestScenario = "attestation"' in browser_relay_routing_smoke
            and "RelayNode target attestation mismatch did not trigger failover"
                in browser_relay_routing_smoke
            and 'capabilities: ["target-attestation"]' in browser_relay_routing_smoke
            and "Translator node did not attest the actual TCP peer" in bridge_smoke,
        ),
        (
            "Public RelayNodes renew verified leases without exposing registry credentials",
            "GAIUS_RELAY_REGISTRY_URL" in bridge_config
            and "GAIUS_RELAY_PUBLIC_URL" in bridge_config
            and "GAIUS_RELAY_REGISTRY_TOKEN" in bridge_config
            and "function startRelayRegistration()" in bridge_main
            and 'kind: "gaius-relay-registration"' in bridge_main
            and "authorization: `Bearer ${registration.token}`" in bridge_main
            and "stopRelayRegistration" in bridge_main
            and 'method: "DELETE"' in bridge_main
            and "gracefulUnregisterMs" in bridge_registry_smoke
            and "const leases = new Map()" in bridge_registry
            and 'requestUrl.pathname === "/relay-nodes.json"' in bridge_registry
            and "async function verifyRegistration" in bridge_registry
            and "async function fetchNodeManifest" in bridge_registry
            and "timingSafeEqual(expectedBytes, suppliedBytes)" in bridge_registry
            and "Public RelayNodes cannot require an unpublished tunnel token"
                in bridge_registry
            and "Public RelayNodes must block private TCP targets" in bridge_registry
            and "pruneExpiredLeases" in bridge_registry
            and "expiredAfterCrash: true" in bridge_registry_smoke
            and "RelayNode lease expired while registration heartbeats were active"
                in bridge_registry_smoke
            and "portableRegistryCors: true" in bridge_registry_smoke
            and "portableManifestCors: true" in bridge_registry_smoke
            and 'GAIUS_ALLOWED_ORIGINS: "null"' in bridge_registry_smoke
            and "compose.public.example.yaml" in relay_nodes_doc
            and "GAIUS_RELAY_PUBLIC_URL" in public_relay_compose
            and "GAIUS_RELAY_REGISTRY_URL" in public_relay_compose
            and "GAIUS_RELAY_ALLOW_INSECURE_REGISTRATION" in public_relay_compose
            and "ports:" in public_relay_compose
            and '"80:80"' in public_relay_compose
            and '"443:443"' in public_relay_compose
            and "/relay-registry/v1/nodes" not in public_relay_caddyfile
            and "path /relay-nodes.json /health" in public_relay_caddyfile
            and "reverse_proxy gaius-relay:8080" in public_relay_caddyfile
            and "GAIUS_ALLOWED_ORIGINS=https://play.example.com,null" in public_relay_env
            and "Live registry deployment" in relay_nodes_doc,
        ),
        (
            "Public RelayNodes reject private targets while local development remains available",
            "GAIUS_ALLOW_PRIVATE_TARGETS" in bridge_config
            and "loopbackListener" in bridge_config
            and "function publicTargetLookup" in bridge_main
            and "isPrivateNetworkAddress" in bridge_main
            and "function createPrivateNetworkBlockList" in bridge_policy
            and '["::ffff:0:0", 96]' not in bridge_policy
            and '"public-target-guard"' in bridge_main
            and "allowsPrivateTargets: config.allowPrivateTargets" in bridge_main
            and "privateTargetDenied: true" in bridge_registry_smoke
            and "privateTargetVariantsDenied: privateTargets.length"
                in bridge_registry_smoke
            and "publicIpv4Allowed: true" in bridge_registry_smoke
            and 'isPrivateNetworkAddress("43.249.195.103")'
                in bridge_registry_smoke
            and '"::ffff:127.0.0.1"' in bridge_registry_smoke
            and "Target hostname resolves only to private addresses" in bridge_main,
        ),
        (
            "Browser multiplayer ranks RelayNodes by target affinity without sharing TCP streams",
            "relayManifestUrl" in netty_browser_channel
            and "prepareRelayCandidates" in netty_browser_channel
            and "headers.authorization = 'Bearer ' + token" in netty_browser_channel
            and "candidate.targetActiveConnections > 0" in netty_browser_channel
            and "candidate.targetRecentlyReachable" in netty_browser_channel
            and "relayTargetActiveSelections" in netty_browser_channel
            and "directPluginCachedMisses" in netty_browser_channel
            and "relayPreflightCacheHits" in netty_browser_channel
            and "relayTargetRecentSelections" in netty_browser_channel
            and "targetRelayLeases: new Map()" in netty_browser_channel
            and "function acquireTargetRelayLease(entry, candidate)" in netty_browser_channel
            and "function releaseTargetRelayLease(entry)" in netty_browser_channel
            and "relayTargetLocalRecentSelections" in netty_browser_channel
            and "activeRelayTargetLeases" in netty_browser_channel
            and "function ensureRelayCandidates(entry)" in netty_browser_channel
            and "relayPreparationStarted: false" in netty_browser_channel
            and "relayRegistryPromise: null" in netty_browser_channel
            and "RelayNode manifests were probed before the direct plugin failed"
                in browser_relay_routing_smoke
            and "RelayNode registries were fetched while the direct plugin was available"
                in browser_relay_routing_smoke
            and "verifyDirectPluginFirst" in browser_relay_routing_smoke
            and '2, "active", "wss://affinity.example/tunnel"'
                in browser_relay_routing_smoke
            and '3, "recent", "wss://affinity.example/tunnel"'
                in browser_relay_routing_smoke
            and "verifyCachedRelayDiscovery" in browser_relay_routing_smoke
            and "directPluginCachedMisses" in browser_relay_routing_smoke
            and "relayPreflightCacheHits" in browser_relay_routing_smoke
            and "Successful fallback RelayNode was not reused" in browser_relay_routing_smoke
            and "Closing browser channels leaked RelayNode target leases"
                in browser_relay_routing_smoke
            and "new Set(chosenRelaySockets).size" in browser_relay_routing_smoke,
        ),
        (
            "RelayNode reports authenticated target affinity while keeping tunnels isolated",
            "const targetRoutes = new Map()" in bridge_main
            and "function targetRouteKey(host, port)" in bridge_main
            and "normalizeHost(host)" in bridge_main
            and "function acquireTargetRoute(request)" in bridge_main
            and "function targetRouteSnapshot(request)" in bridge_main
            and "releaseTargetRoute = acquireTargetRoute(request)" in bridge_main
            and 'requestUrl.searchParams.get("host")' in bridge_main
            and 'authorization.startsWith("Bearer ")' in bridge_main
            and '"target-affinity"' in bridge_main
            and '"ephemeral-tunnel-lease"' in bridge_main
            and 'releasedOn: "websocket-close"' in bridge_main
            and '"GAIUS_TARGET_AFFINITY_MS"' in bridge_config
            and '"GAIUS_MAXIMUM_TARGET_ROUTES"' in bridge_config
            and "Translator node exposed target affinity without its token" in bridge_smoke
            and "targetActiveConnections" in bridge_smoke
            and "targetRecentlyReachable" in bridge_smoke
            and "testSharedTargetLifecycle" in bridge_smoke
            and "Shared RelayNode mixed two players' TCP streams" in bridge_smoke
            and "activeAfterLastExit: released.activeConnections" in bridge_smoke,
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
            and 'controlMessage("connecting", requestedTarget)' in server_plugin_gateway
            and 'controlMessage("connected", requestedTarget)' in server_plugin_gateway
            and "configuredTarget.equals(requestedTarget)" in server_plugin_gateway
            and "Gaius target does not match the configured Minecraft server"
                in server_plugin_gateway
            and "socket = target" in server_plugin_gateway
            and "if (closed.get())" in server_plugin_gateway
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
            and "isLocalSession" in browser_singleplayer_client
            and "hasActiveWorker" in browser_singleplayer_client
            and "patchPauseScreenBrowserSingleplayer" in client_patcher
            and "replaceLocalServerCheck" in client_patcher
            and "renderDistance: Math.max(2" in browser_singleplayer_client
            and "simulationDistance: Math.max(2" in browser_singleplayer_client
            and "setIntegratedServerDistances" in browser_integrated_server_main
            and "setViewDistance(view)" in browser_integrated_server_main
            and "setSimulationDistance(simulation)" in browser_integrated_server_main
            and "public static void configurePlayerList" in browser_integrated_server_main
            and "setAllowCommandsForAllPlayers(true)" in browser_integrated_server_main
            and "DedicatedServer player list configuration point was not found" in client_patcher
            and "INITIAL_VIEW_DISTANCE = 1" in browser_integrated_server_main
            and "INITIAL_SIMULATION_DISTANCE = 1" in browser_integrated_server_main
            and "minimumServerViewDistance" in browser_integrated_server_main
            and "patchChunkMapBrowserInitialViewDistance" in client_patcher
            and '"getPlayerViewDistance"' in client_patcher
            and '"server-distances-staged"' in browser_integrated_server_main
            and '"server-distances-ramping"' in browser_integrated_server_main
            and "recordChunkBatchSent" in browser_integrated_server_main
            and "acknowledgeChunkBatch" in browser_integrated_server_main
            and "sentChunkBatches" in browser_integrated_server_main
            and "activeViewDistanceAcknowledged" in browser_integrated_server_main
            and "DEFAULT_DISTANCE_RAMP_INTERVAL_MILLIS = 750L" in browser_integrated_server_main
            and "distanceRampIntervalMillis()" in browser_integrated_server_main
            and "__gaiusDistanceRampIntervalMillis" in browser_integrated_server_main
            and "distanceAdvancePending" in browser_integrated_server_main
            and "tickIntegratedServerDistances" in browser_integrated_server_main
            and "advanceConfiguredDistances" in browser_integrated_server_main
            and "patchServerGamePacketListenerBrowserWorker" in client_patcher
            and "patchPlayerChunkSenderBrowserWorker" in client_patcher
            and '"recordChunkBatchSent"' in client_patcher
            and '"handleChunkBatchReceived"' in client_patcher
            and 'message.type === "distances"' in server_worker_bootstrap
            and "__gaiusServerViewDistance" in server_worker_bootstrap
            and "__gaiusServerSimulationDistance" in server_worker_bootstrap
            and "__gaiusServerSeed" in server_worker_bootstrap
            and "__gaiusWorldgenSliceMillis" in server_worker_bootstrap
            and "clampWorldgenSlice" in server_worker_bootstrap
            and "requestedWorldgenSlice" in server_worker_bootstrap
            and "defaultWorldgenSliceMillis = 8" in server_worker_bootstrap
            and "defaultDistanceRampIntervalMillis = 750" in server_worker_bootstrap
            and "clampDistanceRampInterval" in server_worker_bootstrap
            and "distanceRampIntervalMillis" in server_worker_bootstrap
            and "requestedWorldgenSlice,\n      defaultWorldgenSliceMillis," in server_worker_bootstrap
            and 'properties += "level-seed=" + seed' in browser_integrated_server_main
            and "workerSeed()" in browser_integrated_server_main
            and "__gaiusLocalServerPorts" in server_worker_bootstrap
            and "importScripts(script.url)" in server_worker_bootstrap,
        ),
        (
            "Browser singleplayer defers historical data fixes and overlaps storage with runtime preparation",
            "public static DataFixer dataFixer()" in browser_integrated_server_main
            and "BrowserLazyDataFixer.instance()" in browser_integrated_server_main
            and "Minecraft lazy data fixer hook point was not found" in client_patcher
            and "Main eager data fixer optimization hook point was not found" in client_patcher
            and 'call.owner = "dev/gaius/browser/BrowserLazyDataFixer"' in client_patcher
            and 'call.name = "instance"' in client_patcher
            and 'call.name = "skipEagerOptimization"' in client_patcher
            and "CompletableFuture<?> skipEagerOptimization" in browser_lazy_data_fixer
            and "sourceVersion >= targetVersion" in browser_lazy_data_fixer
            and "return input;" in browser_lazy_data_fixer
            and "DataFixers.getDataFixer()" in browser_lazy_data_fixer
            and "Promise.all([assetReady, storageReady])" in server_worker_bootstrap
            and 'markStartup("runtime-downloaded"' in server_worker_bootstrap
            and 'markStartup("runtime-decompressed"' in server_worker_bootstrap
            and 'markStartup("runtime-imported"' in server_worker_bootstrap
            and "__gaiusSingleplayerServerGzipUrl" in postprocess_index_html,
        ),
        (
            "Singleplayer Worker stops cleanly and refreshes browser storage",
            "requestWorkerStop" in browser_singleplayer_client
            and "__gaiusSingleplayerHandoff" in browser_singleplayer_client
            and "refreshPersistentFiles" in browser_singleplayer_client
            and "message.type === 'stopped'" in browser_singleplayer_client
            and "stopIntegratedServer" in browser_integrated_server_main
            and "isIntegratedServerStopped" in browser_integrated_server_main
            and "serverThreadExited" in browser_integrated_server_main
            and "markIntegratedServerStopped" in browser_integrated_server_main
            and '"markIntegratedServerStopped"' in client_patcher
            and "MinecraftServer runServer exit shape changed" in client_patcher
            and 'message.type === "stop"' in server_worker_bootstrap
            and 'message.type !== "start"' in server_worker_bootstrap
            and "pendingChanges = new Map()" in server_worker_bootstrap
            and "scheduleFlush" in server_worker_bootstrap
            and "writeBatch(changes)" in server_worker_bootstrap
            and "flushForShutdown" in server_worker_bootstrap
            and "flushWithWatchdog" in server_worker_bootstrap
            and '"Persistent storage flush timed out"' in server_worker_bootstrap
            and 'await withTimeout(flushPendingChanges(), 10000, "Persistent storage flush timed out")'
            not in server_worker_bootstrap
            and "resolved.search = location.search" in server_worker_bootstrap
            and "storage-write-error" in server_worker_bootstrap
            and "failLocalSession" in netty_browser_channel
            and "terminateFailedWorker" in browser_singleplayer_client
            and "__gaiusStorageRefresh" in browser_singleplayer_client
            and "workers.delete(sessionId)" in browser_singleplayer_client
            and "ports.delete(sessionId)" in browser_singleplayer_client
            and 'const detail = "Integrated server did not stop within " +' in server_worker_bootstrap
            and 'stopWatchdog + " ms"' in server_worker_bootstrap
            and "Integrated server did not stop within 20000 ms" not in server_worker_bootstrap
            and "Integrated server did not stop within 35 seconds" in browser_singleplayer_client
            and "beginClientHandoff(sessionId, launchGeneration)" in browser_singleplayer_client
            and "beginClientHandoff(sessionId)" not in browser_singleplayer_client
            and "const handoff = globalThis.__gaiusSingleplayerHandoff;"
            in browser_singleplayer_client
            and "handoff && typeof handoff === 'object'" in browser_singleplayer_client
            and "String(handoff.generation || '')" in browser_singleplayer_client
            and "const handoffSession = handoff && typeof handoff === 'object'"
            in browser_singleplayer_client
            and "singleplayer:handoff-disconnect-ignored" in browser_singleplayer_client
            and "__gaiusHandoffPending" in browser_singleplayer_client
            and "__gaiusClientAttached" in browser_singleplayer_client
            and "singleplayer:client-attached" in netty_browser_channel
            and "globalThis.__gaiusSingleplayerHandoff = '';" in netty_browser_channel
            and "Integrated server client did not attach within 60 seconds" in browser_singleplayer_client
            and "async function" not in browser_singleplayer_client
            and "for (const" not in browser_singleplayer_client
            and "for (const" not in netty_browser_channel
            and 'type: "stopped"' in server_worker_bootstrap,
        ),
        (
            "Singleplayer region persistence migrates legacy Base64/gzip regions into OPFS",
            "isBinaryChunkStoragePath(normalized) && setBytes(normalized, bytes)"
                in browser_file_persistence
            and "return normalized.endsWith(\".mca\") || normalized.endsWith(\".mcc\")"
                in browser_file_persistence
            and "storedByteLength(normalized)" in browser_file_persistence
            and "copyStoredBytes(normalized, bytes)" in browser_file_persistence
            and "__gaiusFsPutBytes" in browser_file_persistence
            and "root.__gaiusFsPutBytes" in server_worker_bootstrap
            and "Uint8Array.fromBase64" in server_worker_bootstrap
            and 'new DecompressionStream("gzip")' in server_worker_bootstrap
            and "const migratedPaths = []" in server_worker_bootstrap
            and "appendOpfsRegion(path, bytes, false)" in server_worker_bootstrap
            and "await deleteStoredPaths(migratedPaths)" in server_worker_bootstrap
            and 'IDBKeyRange.bound(prefix, prefix + "\\uffff")'
            in server_worker_bootstrap
            and "openKeyCursor(range)" in server_worker_bootstrap,
        ),
        (
            "Singleplayer RegionFile persists bounded dirty ranges with crash-safe OPFS records",
            "MAX_DIRTY_RANGES = 64" in file_channel
            and "persistDirtyRanges()" in file_channel
            and "BrowserFilePersistence.persistRanges" in file_channel
            and "persistFullSnapshot()" in file_channel
            and "supportsRangePersistence" in browser_file_persistence
            and "__gaiusFsPatchBytes" in browser_file_persistence
            and "opfsPatchRecordVersion = 2" in server_worker_bootstrap
            and "opfsPatchCommitMagic" in server_worker_bootstrap
            and "opfsPatchChecksum" in server_worker_bootstrap
            and "generation !== nextOpfsRegionGeneration(previous)" in server_worker_bootstrap
            and "maximumOpfsPatchChainRecords = 64" in server_worker_bootstrap
            and "checkpointOpfsRegion" in server_worker_bootstrap
            and 'protocol: "v1-full-plus-v2-patch"'
                in singleplayer_region_patch_log_smoke
            and "CRC failure did not roll back" in singleplayer_region_patch_log_smoke
            and "corrupted v2 transaction tail was not truncated"
                in singleplayer_region_patch_log_smoke,
        ),
        (
            "Multiplayer server-pack cache persists bounded raw IndexedDB bytes",
            'normalized.startsWith("/gaius/downloads/")' in browser_file_persistence
            and "isDownloadedPackFile(normalized) && setBytes(normalized, bytes)"
            in browser_file_persistence
            and "window.__gaiusFsPutBytes = function(path, value)"
            in postprocess_index_html
            and "maximumDownloadedPacks = 4" in postprocess_index_html
            and "maximumDownloadedPackBytes = 256 * 1024 * 1024"
            in postprocess_index_html
            and 'report("storage-download-cache-pruned"' in postprocess_index_html,
        ),
        (
            "Browser startup scans IndexedDB keys before hydrating selected records",
            "const request = store.openKeyCursor();" in postprocess_index_html
            and "const read = store.get(primaryKey);" in postprocess_index_html
            and 'report("storage-key-scan"' in postprocess_index_html
            and 'data/minecraft/world_gen_settings.dat' in postprocess_index_html
            and 'new Error("IndexedDB open timed out")' in postprocess_index_html
            and 'new Error("IndexedDB bootstrap timed out")' in postprocess_index_html
            and "const request = store.openKeyCursor();" in index_html
            and "const read = store.get(primaryKey);" in index_html
            and 'report("storage-key-scan"' in index_html
            and 'new Error("IndexedDB open timed out")' in index_html
            and 'new Error("IndexedDB bootstrap timed out")' in index_html
            and "const request = store.openCursor();" not in index_html,
        ),
        (
            "Cold server-pack timeout recovery is one-shot, preserves verified packs, and rejects policy disconnects",
            "markDownloadedPackPersisted(normalized, bytes.length)" in browser_file_persistence
            and "__gaiusMultiplayerRecovery" in browser_file_persistence
            and "BrowserMultiplayerRecovery" in client_patcher
            and "ClientCommonPacketListenerImpl disconnect call was not found" in client_patcher
            and "maybeReconnect" in browser_multiplayer_recovery
            and "prepareDisconnect" in browser_multiplayer_recovery
            and "minecraft.execute" in browser_multiplayer_recovery
            and "ConnectScreen.startConnecting" in browser_multiplayer_recovery
            and "gaius.multiplayer.cold-pack-retry.v1" in browser_multiplayer_recovery
            and "now - cachedAt > 600000" in browser_multiplayer_recovery
            and "now - Number(previous.at) < 300000" in browser_multiplayer_recovery
            and "activeAttempt <= 0 || packAttempt !== activeAttempt" in browser_multiplayer_recovery
            and "state.preservePackRequested = !!reusable" in browser_multiplayer_recovery
            and "packPath.endsWith('/' + requestId + '/' + requestHash)" in browser_multiplayer_recovery
            and "reusePreservedServerPack" in browser_multiplayer_recovery
            and "state.activeAttempt = (state.activeAttempt|0) + 1" in browser_multiplayer_recovery
            and "state.packAttempt=state.activeAttempt|0" in browser_file_persistence
            and "patchConnectScreenBrowserRecovery" in client_patcher
            and ".gaius-local" in browser_multiplayer_recovery
            and 'normalized.contains("login")' in browser_multiplayer_recovery
            and 'normalized.contains("banned")' in browser_multiplayer_recovery
            and 'normalized.contains("incompatible")' in browser_multiplayer_recovery
            and "BrowserServerPackReuse" in client_patcher
            and "keepServerPackForRecovery" in client_patcher
            and "handleRequiredPack" in browser_server_pack_reuse
            and "ServerboundResourcePackPacket.Action.ACCEPTED" in browser_server_pack_reuse
            and "ServerboundResourcePackPacket.Action.DOWNLOADED" in browser_server_pack_reuse
            and "ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED" in browser_server_pack_reuse
            and "suppressEarlyApplied" in browser_server_pack_reuse,
        ),
        (
            "Singleplayer runtime smoke can isolate first-chunk CPU profiles",
            'GAIUS_SMOKE_STOP_AT_FIRST_CHUNK' in singleplayer_worker_runtime_smoke
            and "stopAtFirstChunk || (distanceSyncReady && configuredDistanceReady)"
                in singleplayer_worker_runtime_smoke
            and "if (!stopAtFirstChunk &&" in singleplayer_worker_runtime_smoke
            and "region-storage-mismatch" in singleplayer_worker_runtime_smoke
            and "node-event-loop-probe" in singleplayer_worker_runtime_smoke
            and "blockActionAckLatenciesMs" in singleplayer_worker_runtime_smoke
            and "node-console-error" in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_ROAM_STEPS" in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_WORLDGEN_SLICE_MS" in singleplayer_worker_runtime_smoke
            and "__gaiusWorldgenSliceMillis" in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS" in singleplayer_worker_runtime_smoke
            and 'type: "worldgen-event-loop-stall"' in singleplayer_worker_runtime_smoke
            and 'SLOW_SAMPLE_SCHEMA = "gaius.worker-event-loop-slow-sample.v2"'
                in singleplayer_worker_runtime_smoke
            and "MAX_SLOW_SAMPLES = 64" in singleplayer_worker_runtime_smoke
            and "createSlowProbeBlockState" in singleplayer_worker_runtime_smoke
            and "slowSnapshotReused" in singleplayer_worker_runtime_smoke
            and "slowSnapshotDropReason" in singleplayer_worker_runtime_smoke
            and "snapshotBlockCapDropped" in singleplayer_worker_runtime_smoke
            and "topKRetentionDropped" in singleplayer_worker_runtime_smoke
            and "parentSendEpochMs" in singleplayer_worker_runtime_smoke
            and "workerStartEpochMs" in singleplayer_worker_runtime_smoke
            and "workerEndEpochMs" in singleplayer_worker_runtime_smoke
            and "parentReceiveEpochMs" in singleplayer_worker_runtime_smoke
            and "workerInterProbeGapMs" in singleplayer_worker_runtime_smoke
            and "workerSlowProbeSnapshot" in singleplayer_worker_runtime_smoke
            and "slowProbeEvidence" in singleplayer_worker_runtime_smoke
            and "STORAGE_SLOW_SAMPLE_FIELDS" in singleplayer_worker_runtime_smoke
            and "SCHEDULER_SLOW_SAMPLE_FIELDS" in singleplayer_worker_runtime_smoke
            and "__gaiusWorldgenSchedulerMarker" in singleplayer_worker_runtime_smoke
            and "summarizeGameplayProbeLatencies" in singleplayer_worker_runtime_smoke
            and 'process.env.GAIUS_SMOKE_SEED || "gaius-runtime-smoke-v1"'
                in singleplayer_worker_runtime_smoke
            and "encodeMovePlayerPosition" in singleplayer_worker_runtime_smoke
            and "A real client resumes movement heartbeats" in singleplayer_worker_runtime_smoke
            and "clientboundPlay.setChunkCacheCenter" in singleplayer_worker_runtime_smoke
            and "chunkPriorityStats: latestChunkPriorityStats" in singleplayer_worker_runtime_smoke
            and "uniqueChunkPositions" in singleplayer_worker_runtime_smoke
            and 'workerPhase = "stopping"' in singleplayer_worker_runtime_smoke
            and "stopAtFirstChunk," in singleplayer_worker_runtime_smoke,
        ),
        (
            "Portable HTML keeps singleplayer assets and server execution in the browser",
            PORTABLE_HTML.exists()
            and PORTABLE_HTML.stat().st_size > 100_000_000
            and "build-portable-html.py" in build_release
            and "DecompressionStream" in build_portable_html
            and "setTimeout(resolve, 0)" in build_portable_html
            and "__gaiusPortableAssetsReady" in build_portable_html
            and "__gaiusVanillaAssetsCompressedPromise" in build_portable_html
            and "__gaiusSingleplayerWorkerUrl" in build_portable_html
            and "__gaiusSingleplayerServerGzipUrl" in build_portable_html
            and "Gaius.manifest.json" in build_portable_html
            and "window.__gaiusPortableManifest" in build_portable_html
            and "serverScriptGzipUrl" in browser_singleplayer_client
            and "serverScriptGzipUrl" in server_worker_bootstrap
            and "URL.createObjectURL" in server_worker_bootstrap
            and "window.__gaiusClassesUrl" in postprocess_index_html
            and "window.__gaiusHotpathWasmUrl" in postprocess_index_html
            and 'dist / "relay-nodes.json"' in build_portable_html
            and "embeddedRelayNodes" in build_portable_html
            and "window.__gaiusBridgeUrls = embeddedRelayNodes.concat" in build_portable_html
            and SERVER_WORKER_BOOTSTRAP_JS.is_file()
            and SERVER_WORKER_BOOTSTRAP.read_bytes()
                == SERVER_WORKER_BOOTSTRAP_JS.read_bytes()
            and file_contains(
                PORTABLE_HTML,
                "window.__gaiusBridgeUrls = embeddedRelayNodes.concat",
            )
            and file_contains(PORTABLE_HTML, "async function acquireGaiusRuntimeLease()")
            and portable_embeds_gzip(
                PORTABLE_HTML,
                "classes",
                DIST / "classes.js.gz",
            )
            and portable_embeds_gzip(
                PORTABLE_HTML,
                "server",
                Path(str(SERVER_WORKER_JS) + ".gz"),
            )
            and portable_embeds_gzip(
                PORTABLE_HTML,
                "wasm",
                DIST / "gaius-hotpath.wasm.gz",
            )
            and portable_embeds_gzip(
                PORTABLE_HTML,
                "vanilla",
                VANILLA_ASSET_PACK,
            )
            and portable_embeds_assignment(
                PORTABLE_HTML,
                "workerSource",
                SERVER_WORKER_BOOTSTRAP_JS.read_text(encoding="utf-8"),
            )
            and portable_embeds_assignment(
                PORTABLE_HTML,
                "embeddedRelayNodes",
                relay_registry.get("nodes", [])[:64],
            )
            and portable_artifact_identity_matches(),
        ),
        (
            "Release compresses embedded assets before portable HTML and refreshes its gzip",
            build_release.count("compress-dist.sh") == 2
            and build_release.find("compress-dist.sh")
            < build_release.find("build-portable-html.py")
            < build_release.rfind("compress-dist.sh")
            and "GAIUS_COMPRESS_EXCLUDE=Gaius.html" in build_release
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
            and 'fetch("../dist/classes.js.build.json"' in singleplayer_worker_smoke
            and 'buildIdentity.kind !== "gaius-build-identity"'
                in singleplayer_worker_smoke
            and "activeVersionProfile.protocolVersion" in singleplayer_worker_smoke
            and "PLAY_PROTOCOLS" in singleplayer_worker_smoke
            and "clientboundPlay.levelChunkWithLight" in singleplayer_worker_smoke
            and "serverboundPlay.chunkBatchReceived" in singleplayer_worker_smoke
            and "encodeVarInt(protocolVersion)" in singleplayer_worker_smoke
            and "protocol.startLogin()" in singleplayer_worker_smoke
            and "encodeClientInformation" in singleplayer_worker_smoke
            and "knownPackRequests" in singleplayer_worker_smoke
            and "loginProfileId" in singleplayer_worker_smoke
            and "Integrated server changed the client profile UUID" in singleplayer_worker_smoke
            and 'message.type === "server-distances-staged"' in singleplayer_worker_smoke
            and 'message.detail === "1/1->7/3"' in singleplayer_worker_smoke
            and 'message.type === "server-distances"' in singleplayer_worker_smoke
            and 'message.detail === "7/3"' in singleplayer_worker_smoke
            and "serverboundPlay.chunkBatchReceived" in singleplayer_worker_smoke
            and "playLoginPackets" in singleplayer_worker_smoke
            and "chunkPackets" in singleplayer_worker_smoke
            and "playReady" in singleplayer_worker_smoke
            and "closeTransport" in singleplayer_worker_smoke
            and 'worker.postMessage({type: "stop"})' in singleplayer_worker_smoke
            and re.search(
                r"removeSmokeWorld\s*\(\s*worldId\s*,\s*"
                r"storage\.storageDatabaseName\s*,?\s*\)",
                singleplayer_worker_smoke,
            )
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
            and "GAIUS_SMOKE_RENDER_DISTANCE" in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_SIMULATION_DISTANCE" in singleplayer_worker_runtime_smoke
            and "expectedStagedDistances = `1/1->${targetRenderDistance}/${targetSimulationDistance}`"
                in singleplayer_worker_runtime_smoke
            and "expectedTransitions.slice(0, -1)" in singleplayer_worker_runtime_smoke
            and 'message.type === "server-distances-ramping"' in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_DISTANCE_RAMP_MS" in singleplayer_worker_runtime_smoke
            and "expectedDistanceRamp" in singleplayer_worker_runtime_smoke
            and "distance-ramp-mismatch" in singleplayer_worker_runtime_smoke
            and "distance-ramp-causality-mismatch" in singleplayer_worker_runtime_smoke
            and 'type: "network-state-mismatch"' in singleplayer_worker_runtime_smoke
            and "validateNetworkTaskTelemetry"
                in singleplayer_worker_runtime_smoke
            and "requiredNetworkTaskTelemetryFields"
                in singleplayer_worker_runtime_smoke
            and "distanceTransitionTimeline" in singleplayer_worker_runtime_smoke
            and "chunkBatchAckTimeline" in singleplayer_worker_runtime_smoke
            and "ackCountAtTransition" in singleplayer_worker_runtime_smoke
            and "chunkPacketCountAtTransition" in singleplayer_worker_runtime_smoke
            and "ringBackpressureValid" in singleplayer_worker_runtime_smoke
            and "previousDiameter * previousDiameter" in singleplayer_worker_runtime_smoke
            and "configuredInterval - 50" in singleplayer_worker_runtime_smoke
            and 'packetId.value === 5' in singleplayer_worker_runtime_smoke
            and "configurationFinishedToPlayMs" in singleplayer_worker_runtime_smoke
            and "sendPlayerAction(0)" in singleplayer_worker_runtime_smoke
            and "sendPlayerAction(2)" in singleplayer_worker_runtime_smoke
            and "startConfirmedBlockAction" in singleplayer_worker_runtime_smoke
            and "createBlockCandidates" in singleplayer_worker_runtime_smoke
            and "const offsets = [[1, 0], [-1, 0], [0, 1], [0, -1]];"
                in singleplayer_worker_runtime_smoke
            and "const offsets = [[0, 0]" not in singleplayer_worker_runtime_smoke
            and 'packetId.value === 4' in singleplayer_worker_runtime_smoke
            and "clientboundPlay.blockUpdate" in singleplayer_worker_runtime_smoke
            and "targetAirUpdates < 1" in singleplayer_worker_runtime_smoke
            and "completeBlockAction()" in singleplayer_worker_runtime_smoke
            and "prepareDeterministicDropProbe" in singleplayer_worker_runtime_smoke
            and "maybeResolveReady" in singleplayer_worker_runtime_smoke
            and "!state.roamCompleted || !state.miningCompleted"
                in singleplayer_worker_runtime_smoke
            and 'createHash("sha256").update(payload).digest("hex")'
                in singleplayer_worker_runtime_smoke
            and "chunkDigests: Object.fromEntries(" in singleplayer_worker_runtime_smoke
            and "longestGameplayEventLoopProbe" in singleplayer_worker_runtime_smoke
            and "completedAfterSmokeMs" in singleplayer_worker_runtime_smoke
            and "Block-drop smoke completed without a confirmed dropped entity"
                in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_JSON_ONLY" in singleplayer_worker_runtime_smoke
            and "GAIUS_SMOKE_BLOCK_ACTION_HOLD_MS" in singleplayer_worker_runtime_smoke
            and 'GAIUS_SMOKE_CHUNK_BATCH_DESIRED_RATE || "10"'
                in singleplayer_worker_runtime_smoke
            and "encodeFloat(options.chunkBatchDesiredRate)"
                in singleplayer_worker_runtime_smoke
            and "playReady" in singleplayer_worker_runtime_smoke
            and "closeTransport" in singleplayer_worker_runtime_smoke
            and 'worker.postMessage({type: "stop"})' in singleplayer_worker_runtime_smoke
            and 'type: "node-xhr-request"' in singleplayer_worker_runtime_smoke,
        ),
        (
            "Node runtime smoke keeps auxiliary telemetry current across resets",
            "let latestStorageStats = null" in singleplayer_worker_runtime_smoke
            and "function snapshotTelemetryPong(message)" in singleplayer_worker_runtime_smoke
            and "chunkPriorityStats: copyObjectSnapshot(message.chunkPriority)"
                in singleplayer_worker_runtime_smoke
            and "networkStats: copyObjectSnapshot(message.network)"
                in singleplayer_worker_runtime_smoke
            and "worldgenStats: copyObjectSnapshot(message.worldgen)"
                in singleplayer_worker_runtime_smoke
            and "storageStats: copyObjectSnapshot(message.storage)"
                in singleplayer_worker_runtime_smoke
            and "function updateLatestTelemetrySnapshots"
                in singleplayer_worker_runtime_smoke
            and "sessionId !== expectedSessionId"
                in singleplayer_worker_runtime_smoke
            and "const telemetrySnapshots = snapshotTelemetryPong(message)"
                in singleplayer_worker_runtime_smoke
            and "...telemetrySnapshots" in singleplayer_worker_runtime_smoke
            and "function recentTelemetryAuxiliarySnapshot"
                in singleplayer_worker_runtime_smoke
            and "preStopTelemetryBarrier.storageStats"
                in singleplayer_worker_runtime_smoke
            and "storageStats: latestStorageStats" in singleplayer_worker_runtime_smoke
            and "runTelemetrySnapshotSelfSmoke" in singleplayer_worker_runtime_smoke,
        ),
        (
            "Singleplayer launcher enters the current Worker-enabled client",
            'new URL("../dist/index.html", location.href)' in singleplayer_launcher
            and "target.search = location.search" in singleplayer_launcher
            and "location.replace(target.href)" in singleplayer_launcher,
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
            and "assert_java_sorted_resource_list" in build_server_worker
            and 'LC_ALL=C sort -c "$path"' in build_server_worker
            and 'LC_ALL=C sort -u -o "$server_resource_list_tmp" "$server_resource_list_tmp"' in build_server_worker
            and "GAIUS_RESOURCE_DIRECTORY" in build_server_worker
            and "com/microsoft/azure/msal4j/" in build_server_worker
            and "com/azure/azure-json/" in build_server_worker
            and 'GAIUS_SERVER_TEA_OPTIMIZATION_LEVEL:-ADVANCED' in build_server_worker
            and "GAIUS_COMPRESS_FILES" in build_server_worker
            and "GAIUS_SKIP_SERVER_WORKER" in build_release
            and "build-teavm-server-worker.sh" in build_release
            and 'cp "$root/port/web/singleplayer/server-worker-bootstrap.js"' in build_release
            and '"$dist/singleplayer-server-worker.js"' in build_release
            and "GAIUS_COMPRESS_FILES" in compress_dist
            and '"singleplayer-server.js"' in serve_dist
            and '"singleplayer-server-worker.js"' in serve_dist,
        ),
        (
            "TeaVM consumers hold the overlay writer lock for the complete compile",
            "GAIUS_OVERLAY_LOCK_HELD" in build_overlays
            and "GAIUS_OVERLAY_LOCK_HELD=true" in build_teavm
            and "GAIUS_OVERLAY_LOCK_HELD=true" in build_server_worker
            and "GAIUS_OVERLAY_LOCK_HELD=true" in build_platform_smoke
            and '.build-overlays.lock' in build_teavm
            and '.build-overlays.lock' in build_server_worker
            and '.build-overlays.lock' in build_platform_smoke
            and "trap cleanup_teavm_client EXIT" in build_teavm
            and "trap cleanup_teavm_server_worker EXIT" in build_server_worker
            and "trap release_overlay_lock EXIT" in build_platform_smoke,
        ),
        (
            "Overlay builds bootstrap exact Maven inputs in a clean checkout",
            "maven-dependency-plugin:3.8.1:get" in build_overlays
            and "-Dtransitive=false" in build_overlays
            and "-Dmaven.repo.local=$maven_repository_for_java" in build_overlays
            and "required_maven_artifacts" in build_overlays
            and "org.teavm:teavm-classlib:" in build_overlays
            and "org.teavm:teavm-core:" in build_overlays
            and "org.ow2.asm:asm-tree:" in build_overlays
            and "com.jcraft:jzlib:1.1.3" in build_overlays,
        ),
        (
            "Overlay builds use baseline grep instead of an undeclared ripgrep dependency",
            "grep -Fq 'String wasm64'" in build_overlays
            and "| rg " not in build_overlays,
        ),
        (
            "TeaVM builds keep Maven dependencies inside the configured job repository",
            "gaius_maven_repository" in version_profile_shell
            and "gaius_maven_repository_for_java" in version_profile_shell
            and "GAIUS_MAVEN_REPOSITORY" in build_overlays
            and "GAIUS_MAVEN_REPOSITORY" in build_teavm
            and "GAIUS_MAVEN_REPOSITORY" in build_server_worker
            and "-Dmaven.repo.local=$maven_repository_for_java" in build_teavm
            and "-Dmaven.repo.local=$maven_repository_for_java" in build_server_worker
            and "-Dmaven.repo.local=$maven_repository_for_java" in build_platform_smoke,
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
            "Browser client and server startup yield between complete registry and datapack batches",
            "patchBlocksBrowserStartupYield" in client_patcher
            and "patchBlockStateBaseBrowserStartupYield" in client_patcher
            and "patchBuiltInRegistriesBrowserStartupYield" in client_patcher
            and "patchSimpleJsonResourceReloadListenerBrowserStartupYield" in client_patcher
            and '"bootstrap-complete"' in client_patcher
            and '"client-bootstrap-complete"' in client_patcher
            and '"bootstrap-validated"' in client_patcher
            and '"datafixer-optimization-complete"' in client_patcher
            and '"render-thread-ready"' in client_patcher
            and '"datapacks-loaded"' in client_patcher
            and "patchWorldLoaderBrowserStartupTelemetry" in client_patcher
            and '"world-loader-worldgen-registries-started"' in client_patcher
            and '"world-loader-dimension-registries-started"' in client_patcher
            and '"world-loader-server-resources-started"' in client_patcher
            and "patchServerWorldLoaderCooperativeExecutor" in client_patcher
            and '"world-loader-cooperative-executor"' in client_patcher
            and 'call.owner.equals("net/minecraft/util/Util")' in client_patcher
            and 'call.name.equals("backgroundExecutor")' in client_patcher
            and "patchUtilBlockUntilDoneBrowserOutput" in client_patcher
            and '"dev/gaius/browser/BrowserFuturePump"' in client_patcher
            and "TModernRuntimeSupport.yieldToEventLoop(1)" in browser_future_pump
            and "__gaiusFuturePumpTelemetry" in browser_future_pump
            and "BLOCK_REGISTRATION_BATCH = 64" in browser_startup_scheduler
            and "BLOCK_STATE_CACHE_BATCH = 512" in browser_startup_scheduler
            and "REGISTRY_BOOTSTRAP_BATCH = 8" in browser_startup_scheduler
            and "DATAPACK_RESOURCE_BATCH = 64" in browser_startup_scheduler
            and "registryBootstrapCompleted" in browser_startup_scheduler
            and "bootstrappedRegistries % REGISTRY_BOOTSTRAP_BATCH == 0"
                in browser_startup_scheduler
            and "registryEntryRegistered" not in browser_startup_scheduler
            and "datapackResourceDecoded" in browser_startup_scheduler
            and "TModernRuntimeSupport.yieldToEventLoop(0)" in browser_startup_scheduler
            and "Thread.sleep(0L)" not in browser_startup_scheduler
            and "server-startup-progress" in browser_startup_scheduler
            and "isBrowserRuntime()" in browser_startup_scheduler
            and "__gaiusClientStartupProgress" in browser_startup_scheduler
            and "Minecraft browser startup completion point was not found" in client_patcher
            and "BrowserStartupScheduler.complete()" in browser_integrated_server_main
            and 'markStartup("main-dispatched"' in server_worker_bootstrap
            and 'markStartup("main-returned"' not in server_worker_bootstrap,
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
            "Singleplayer client waits for the Worker listener instead of runtime import",
            "worker.__gaiusServerReady = false" in browser_singleplayer_client
            and "message.type === 'server-listener-ready'" in browser_singleplayer_client
            and "const ownedPort = worker.__gaiusClientPort || null" in browser_singleplayer_client
            and "if (ownedPort && ports.get(key) !== ownedPort)" in browser_singleplayer_client
            and "ports.set(key, ownedPort)" in browser_singleplayer_client
            and "const mappedPort = ports.get(key)" in browser_singleplayer_client
            and "worker.__gaiusServerReady && mappedPort &&" in browser_singleplayer_client
            and "String(mappedPort.__gaiusLaunchGeneration || '') === expectedGeneration"
            in browser_singleplayer_client
            and "worker.__gaiusServerReady && ports.get" not in browser_singleplayer_client
            and 'report("server-listener-ready"' in browser_integrated_server_main
            and '"markServerListenerReady"' in client_patcher,
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
            and "minecraftProfile.login.serverboundLoginAcknowledged" in bridge_smoke
            and "encodeClientInformation" in bridge_smoke
            and "knownPackRequests" in bridge_smoke
            and "codeOfConductRequests" in bridge_smoke
            and "codeOfConductAccepts" in bridge_smoke
            and "minecraftProfile.configuration.serverboundAcceptCodeOfConduct" in bridge_smoke
            and "GAIUS_SMOKE_ACCEPT_SERVER_PROMPTS" in bridge_smoke
            and "GAIUS_SMOKE_DIALOG_INPUTS_JSON" in bridge_smoke
            and "decodeNetworkNbt" in bridge_smoke
            and "inspectServerDialog" in bridge_smoke
            and "encodeCustomClickAction" in bridge_smoke
            and "minecraftProfile.configuration.serverboundCustomClickAction" in bridge_smoke
            and "showDialogAccepts" in bridge_smoke
            and "resourcePackPushes" in bridge_smoke
            and "resourcePackTargets" in bridge_smoke
            and "target = parsed.origin + parsed.pathname" in bridge_smoke
            and "for (const action of [3, 4, 0])" in bridge_smoke
            and "minecraftProfile.configuration.serverboundResourcePack" in bridge_smoke
            and "configurationFinished" in bridge_smoke
            and "playLoginPackets" in bridge_smoke
            and "chunkPackets" in bridge_smoke
            and "encodeVarInt(minecraftProfile.protocolVersion)" in bridge_smoke
            and '"Minecraft PLAY login and chunk data"' in bridge_smoke
            and "function loginDiagnostics()" in bridge_smoke
            and "controls: controls.slice(-3)" in bridge_smoke
            and "Minecraft tunnel closed" in bridge_smoke
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
            and "browserSafeHeaders" in browser_http_proxy
            and 'case "accept-charset", "accept-encoding"' in browser_http_proxy
            and '"user-agent"' in browser_http_proxy
            and "bridge.pathname = '/proxy/' + String(kind)" in browser_http_proxy
            and '"proxyResourcePack"' in client_patcher
            and '"browserSafeHeaders"' in client_patcher
            and "HttpUtil browser download patch points were not found" in client_patcher
            and "patchSkinTextureDownloader" in client_patcher
            and "SkinTextureDownloader browser Java Proxy patch point was not found" in client_patcher
            and '"proxyAuthentication"' in authlib_patcher
            and '"(Ljava/net/Proxy;)Ljava/net/URLConnection;"' in authlib_patcher
            and 'call.desc = "()Ljava/net/URLConnection;"' in authlib_patcher
            and "Opcodes.POP" in authlib_patcher
            and '"dev/gaius/browser/BrowserHttpProxy"' in patchy_patcher
            and '"proxyAuthentication"' in patchy_patcher
            and 'patchy_path="$(gaius_library_path "com.mojang:patchy")"'
            in build_overlays
            and '"proxyRealms"' in client_patcher
            and '"addRealmsCookie"' in client_patcher
            and "testBrowserHttpProxy()" in platform_smoke
            and "Browser HTTP forbidden-header filtering is invalid" in platform_smoke,
        ),
        (
            "Authlib browser Gson decodes remote player textures without JVM Unsafe",
            "YggdrasilMinecraftSessionService" in authlib_patcher
            and "MinecraftProfileTexture" in authlib_patcher
            and "textureDeserializer" in authlib_patcher
            and "JsonDeserializer<MinecraftProfileTexture>" in browser_authlib_gson,
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
            "Minecraft patcher ignores transient null entities from multiplayer render iteration",
            "patchEntityRenderDispatcherBrowserNullEntityGuard" in client_patcher
            and '"shouldRender"' in client_patcher
            and "Opcodes.IFNONNULL" in client_patcher,
        ),
        (
            "Index postprocess removes generated launcher --disableMultiplayer flag",
            '"--disableMultiplayer"' in postprocess_index_html
            and "re.sub" in postprocess_index_html
            and r'\n\s*"--disableMultiplayer",' in postprocess_index_html,
        ),
        (
            "Browser launcher resolves online profiles without leaking identity parameters",
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
            and 'const identityQueryKeys = ["username", "uuid", "accessToken", "xuid", "clientId"]'
                in postprocess_index_html
            and "for (const key of identityQueryKeys) scrubbed.searchParams.delete(key)"
                in postprocess_index_html
            and "<redacted>" in postprocess_index_html
            and "async function buildGaiusSessionArgs()" in index_html
            and "async function loadGaiusMinecraftProfile(accessToken)" in index_html
            and 'window.__gaiusDefaultArgsPromise.catch(() => {})' in index_html
            and 'await window.__gaiusDefaultArgsPromise' in index_html
            and '"<redacted>"' in index_html
            and 'window.__gaiusSessionMode = online ? "online" : "offline"' in index_html
            and 'const identityQueryKeys = ["username", "uuid", "accessToken", "xuid", "clientId"]'
                in index_html
            and "for (const key of identityQueryKeys) scrubbed.searchParams.delete(key)"
                in index_html
            and 'window.__gaiusDisplayArgs.join(" ")' in index_html,
        ),
        (
            "Session launcher smoke covers offline and token-only online identities",
            "offlineNameGate: true" in session_launcher_smoke
            and "rememberedNamePrefill: true" in session_launcher_smoke
            and "profileResolution: true" in session_launcher_smoke
            and "completeSessionBypassesFetch: true" in session_launcher_smoke
            and "titleScreenNameSwitch: true" in session_launcher_smoke
            and "identityQueryScrubbedOnSwitch: true" in session_launcher_smoke
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
            "Browser launcher, resource proxy, and multiplayer channel accept relay URL/token aliases",
            'urlParams.get("relay")' in postprocess_index_html
            and 'urlParams.get("relayToken")' in postprocess_index_html
            and "params.get('relay')" in browser_http_proxy
            and "params.get('relayToken')" in browser_http_proxy
            and "params.getAll('relay')" in netty_browser_channel
            and "params.get('relayToken')" in netty_browser_channel,
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
            "BrowserMemory optimized overlay is not shadowed by a duplicate port source",
            not SHADOWING_BROWSER_MEMORY.exists()
            and "public static long threadJniEnv()" in browser_memory
            and "public static long setupThreadEnv(int functionCount)" in browser_memory
            and "private static final Map<Integer, Region> REGIONS" in browser_memory
            and "private static final List<Block> BLOCKS" not in browser_memory,
        ),
        (
            "BrowserMemory preserves mapped ByteBuffer addresses through memSlice",
            "public static long register(ByteBuffer bytes)" in browser_memory
            and "registerDerived(result, buffer, offset)" in browser_memory
            and "remember(result, base + Integer.toUnsignedLong(byteOffset))" in browser_memory,
        ),
        (
            "LWJGL large memcpy, memmove, and memset calls keep real browser behavior",
            "public static long cMemset(long target, int value, long byteCount)"
                in browser_memory
            and "public static long cMemcpy(long target, long source, long byteCount)"
                in browser_memory
            and "public static long cMemmove(long target, long source, long byteCount)"
                in browser_memory
            and 'case "nmemset(JIJ)J" -> "cMemset"'
                in native_method_fallback_patcher
            and 'case "nmemcpy(JJJ)J" -> "cMemcpy"'
                in native_method_fallback_patcher
            and 'case "nmemmove(JJJ)J" -> "cMemmove"'
                in native_method_fallback_patcher
            and 'method.name.equals("memcpy") && method.desc.equals("(JJJ)V")'
                in lwjgl_memory_patcher
            and 'replaceWithDelegate(method, "copy")' in lwjgl_memory_patcher
            and 'method.name.equals("memset") && method.desc.equals("(JIJ)V")'
                in lwjgl_memory_patcher
            and 'replaceWithDelegate(method, "set")' in lwjgl_memory_patcher,
        ),
        (
            "BrowserMemory frees mapped buffers without scanning the whole address table",
            "private static boolean releaseRegion(int id, Region expected)" in browser_memory
            and "REGIONS.remove(id)" in browser_memory
            and "for (BufferReference reference : removed.buffers)" in browser_memory,
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
            and "byte[] bytes = BYTE_ARRAYS.get()" in browser_memory
            and "byte[] bytes = temporaryBytes(length)" in browser_memory
            and "HARD_MAX_TEMPORARY_BYTES = 16 * 1024 * 1024" in browser_memory
            and "Browser temporary decode budget exceeded" in browser_memory
            and "temporaryAllocationFailures" in browser_memory
            and "new byte[Math.min(count, 65536)]" not in browser_memory
            and "return ThreadLocal.withInitial(() -> new byte[8192])" not in browser_memory,
        ),
        (
            "BrowserMemory enforces a configurable hard live-byte budget",
            "HARD_MAX_LIVE_BYTES = 2L * 1024L * 1024L * 1024L" in browser_memory
            and "gaius.browser.memory.maxBytes" in browser_memory
            and "configuredMaxLiveBytes()" in browser_memory
            and "Math.min(HARD_MAX_LIVE_BYTES" in browser_memory
            and "ensureLiveByteCapacity(long additionalBytes)" in browser_memory
            and "Browser native memory budget exceeded" in browser_memory
            and "allocationFailures" in browser_memory,
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
            and 'candidate.name.equals("uploadMeshLayer")' in client_patcher
            and "candidate.desc.equals(uploadDescriptor)" in client_patcher
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
            and '"browserData", "[B"' in client_patcher
            and '"browserLastReserveOffset", "I"' in client_patcher
            and '"putFastVertexBytes"' in client_patcher
            and "([BIFFFIFFIIFFFZ)V" in client_patcher,
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
            and "public static byte[] data(long address)" in browser_memory
            and "public static int dataOffset(long address)" in browser_memory
            and "public static native void putPositionBytes(" in browser_memory
            and "public static native void putRgbaBytes(" in browser_memory
            and "patchBufferBuilderBrowserGuiWriters" in client_patcher
            and "putTransformedPositionBytes" in client_patcher
            and "([BILorg/joml/Matrix4fc;FFF)V" in client_patcher
            and "putFloatPairBytes" in client_patcher
            and "putPackedUvBytes" in client_patcher,
        ),
        (
            "BrowserGlfw provides GLFW key names for printable keys",
            "public static String getKeyName(int key, int scancode)" in glfw_text
            and "GLFW.GLFW_KEY_A && value <= GLFW.GLFW_KEY_Z" in glfw_text
            and "GLFW.GLFW_KEY_KP_ADD" in glfw_text
            and "default -> null" in glfw_text,
        ),
        (
            "BrowserGlfw provides the monitor identity required by Minecraft 26.2 startup",
            'public static String getMonitorName(long monitor)' in glfw_text
            and 'return "Browser Display"' in glfw_text
            and 'add(result, "glfwGetMonitorName", "(J)Ljava/lang/String;", "getMonitorName")'
            in glfw_patcher
            and "new MonitorManager()" in platform_smoke,
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
            and "private static native boolean swapBuffersJs()" in glfw_text
            and "public static native void swapBuffers(long window)" not in glfw_text
            and "gameFps" in glfw_text
            and "gameFrames" in glfw_text
            and "gameLastSampleAt" in glfw_text,
        ),
        (
            "BrowserGlfw honors VSync, yields uncapped frames, and throttles hidden tabs",
            "const hidden=document.visibilityState!=='visible'" in glfw_text
            and "return hidden" in glfw_text
            and "__gaiusBackgroundFrameThrottles" in glfw_text
            and "boolean hidden = swapBuffersJs()" in glfw_text
            and "yieldAfterPresent(hidden, swapInterval)" in glfw_text
            and "scheduleFrameYield(hidden, interval" in glfw_text
            and "synchronizedToDisplay && typeof requestAnimationFrame==='function'" in glfw_text
            and "uncappedYieldCount" in glfw_text
            and "vsyncYieldCount" in glfw_text
            and "telemetry.swapInterval=Number(interval)||0" in glfw_text
            and "scheduler={tasks:new Map(),channel:null,nextTaskId:1}" in glfw_text
            and "scheduler.tasks.delete(taskId)" in glfw_text
            and "cancelledMessageTaskCount" in glfw_text
            and "messageChannelRebuildCount" in glfw_text
            and "messageChannelCreateFailureCount" in glfw_text
            and "messageChannelPostFailureCount" in glfw_text
            and "setTimeout(() => finish('timer'), 0)" in glfw_text
            and "(sequence & 3)===0" in glfw_text
            and "scheduler={queue:[],channel:null}" not in glfw_text
            and "requestAnimationFrame" in glfw_text
            and "setTimeout(() => finish('timer'), 50)" in glfw_text
            and glfw_text.count("swapBuffersJs()") == 2,
        ),
        (
            "BrowserGlfw provides opt-in swapBuffers frame-time telemetry",
            "__gaiusFrameTelemetry" in glfw_text
            and "telemetry.enabled" in glfw_text
            and "new Uint32Array(4001)" in glfw_text
            and "longestFrameMillis" in glfw_text
            and "totalFrameMillis" in glfw_text,
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
            "Browser frame pacing remains local to the GLFW present boundary",
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
            and "findInputSetupDispatch" in client_patcher
            and 'findInputSetupDispatch(node, "onMove", "(JDD)V")' in client_patcher
            and 'findInputSetupDispatch(node, "onButton", "(JIII)V")' in client_patcher
            and 'findInputSetupDispatch(node, "onScroll", "(JDD)V")' in client_patcher
            and 'findInputSetupDispatch(node, "keyPress", "(JIIII)V")' in client_patcher
            and 'findInputSetupDispatch(node, "charTyped", "(JII)V", "(JI)V")'
            in client_patcher
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
            and "BROWSER_SECTION_UPLOAD_BUDGET = 8" in client_patcher
            and "BROWSER_SECTION_CLOSE_BUDGET = 16" in client_patcher
            and "Window.requestAnimationFrame" in browser_render_scheduler
            and "Platform.schedule(BrowserRenderScheduler::runAfterPaint, 0)" in browser_render_scheduler
            and "MAX_TASKS_PER_FRAME = 8" in browser_render_scheduler
            and "FRAME_WORK_BUDGET_NANOS = 2_000_000L" in browser_render_scheduler
            and "requestEmergencyUpload" in browser_render_scheduler
            and "awaitUploadRetry" in browser_render_scheduler
            and "clearUploadRetry" in browser_render_scheduler
            and "TModernRuntimeSupport.yieldToEventLoop(1)" in browser_render_scheduler
            and "MAX_UPLOAD_RETRY_YIELDS = 2_048" in browser_render_scheduler
            and "MAX_UPLOAD_RETRY_NANOS = 5_000_000_000L" in browser_render_scheduler
            and "emergencyUploadDrains" in browser_render_scheduler
            and "emergencyUploadDeferrals" in browser_render_scheduler
            and "uploadRetryCancellations" in browser_render_scheduler
            and "UPLOAD_RETRY_SWEEP_INTERVAL_NANOS = 1_000_000_000L"
                in browser_render_scheduler
            and "sweepExpiredUploadRetries()" in browser_render_scheduler
            and "uploadRetryExpiredStates" in browser_render_scheduler
            and "patchSectionRenderEmergencyUpload" in minecraft_262_browser_patcher
            and "patchSectionRenderTaskRetryYields" in minecraft_262_browser_patcher
            and "addUploadRetryExceptionCleanup(method)" in minecraft_262_browser_patcher
            and "new TryCatchBlockNode(" in minecraft_262_browser_patcher
            and 'writeComputeFrames(node, root.resolve(owner + ".class"))'
                in minecraft_262_browser_patcher
            and "ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS"
                in minecraft_262_browser_patcher
            and '"requestEmergencyUpload"' in minecraft_262_browser_patcher
            and '"awaitUploadRetry"' in minecraft_262_browser_patcher
            and '"clearUploadRetry"' in minecraft_262_browser_patcher
            and '"uploadTerrainBuffersToGpu"' in minecraft_262_browser_patcher
            and "QUEUE.pollFirst()" in browser_render_scheduler
            and "uploadAllPendingUploads" in client_patcher
            and "rebuildSectionSync" in client_patcher
            and "IF_ICMPLT" in client_patcher
            and "java/util/List" in client_patcher
            and "java/util/Queue" in client_patcher
            and "SectionMesh" in client_patcher,
        ),
        (
            "Chrome release performance contract gates full-window FPS, visual output, and memory",
            performance_contract.get("schemaVersion") == 11
            and ACTIVE_WORLDGEN_TELEMETRY_MODE in WORLDGEN_TELEMETRY_MODES
            and performance_contract.get("runtimeInvariants", {})
                .get("worldgen", {}).get("telemetryMode") in WORLDGEN_TELEMETRY_MODES
            and performance_contract.get("profiles", {})
                .get("steady-6-4", {}).get("gates", {}).get("averageFpsMin") == 120
            and performance_contract.get("profiles", {})
                .get("steady-6-4", {}).get("gates", {}).get("onePercentLowFpsMin") == 60
            and performance_contract.get("profiles", {})
                .get("traversal-6-4", {}).get("gates", {}).get("longestFrameMsMax") == 50
            and performance_contract.get("runtimeInvariants", {})
                .get("webglMemory", {}).get("derivedAlignedAttribBudgetBytes") == 33_554_432
            and performance_contract.get("runtimeInvariants", {})
                .get("worldgen", {}).get("p99SliceElapsedMillisMax") == 14
            and performance_contract.get("runtimeInvariants", {})
                .get("worldgen", {}).get("p99YieldDelayMillisMax") == 16.7
            and performance_contract.get("heartbeat", {}).get("rttP99MaxMs") == 50
            and performance_contract.get("environment", {})
                .get("uncappedEvidence", {}).get("requiredSwapInterval") == 0
            and performance_contract.get("environment", {})
                .get("uncappedEvidence", {}).get("minimumFairYieldCount") == 1
            and performance_contract.get("environment", {})
                .get("uncappedEvidence", {}).get(
                    "maximumMessageChannelPostFailureCount"
                ) == 0
            and performance_contract.get("environment", {})
                .get("uncappedEvidence", {}).get("maximumWatchdogYieldCount") == 0
            and "stats.p99YieldDelayMillis" in browser_worldgen_scheduler
            and "const frameMeasurementMillis = Math.max(performanceMillis, heapMillis);"
                in chrome_chunk_benchmark
            and "await sampleFor(session, frameMeasurementMillis, samples);"
                in chrome_chunk_benchmark
            and "collectContinuousVisualOutput(" in chrome_chunk_benchmark
            and "__gaiusServerSessionId" in chrome_chunk_benchmark
            and "cdpCommandTimeoutMillis" in chrome_chunk_benchmark
            and "typeof frame.swapInterval==='number'" in chrome_chunk_benchmark
            and "messageChannelPostFailureCount" in chrome_chunk_benchmark
            and "fairYieldCount" in chrome_chunk_benchmark
            and "schemaVersion: performanceContract.schemaVersion" in chrome_chunk_benchmark,
        ),
        (
            "Chrome release performance suite locks one coherent build across required profiles",
            "manifestSha256" in chrome_performance_release_suite
            and "artifactCompatibilities.length === 4" in chrome_performance_release_suite
            and "hardTargetProfiles" in chrome_performance_release_suite
            and "stabilityProfiles" in chrome_performance_release_suite
            and "driverSupported === false" in chrome_performance_release_suite
            and "release-suite.json" in chrome_performance_release_suite
            and "Chrome performance release-suite smoke passed"
                in chrome_performance_release_suite_smoke,
        ),
        (
            "Minecraft patcher removes per-section clock, layer-array, and matrix churn",
            "patchLevelRendererBrowserPrepareChunkRenders" in client_patcher
            and "LevelRenderer.prepareChunkRenders browser hot-path patch points" in client_patcher
            and "LevelRenderer.prepareChunkRenders matrix copy shape changed" in client_patcher
            and '"net/minecraft/util/Util"' in client_patcher
            and '"dev/gaius/browser/BrowserChunkSectionLayers"' in client_patcher
            and "private static final ChunkSectionLayer[] VALUES" in browser_chunk_section_layers
            and "return VALUES;" in browser_chunk_section_layers,
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
            and "Gaius is independent and is not affiliated with Mojang or Microsoft." in client_patcher
            and 'call.name = "literal"' in client_patcher
            and '"net/minecraft/client/gui/screens/CreditsAndAttributionScreen"' in client_patcher
            and "attributionCallbacks != 1" in client_patcher
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
            "private static InsnList minecraftStateReport(boolean hasNoRender)"
            in client_patcher
            and "instruction, minecraftStateReport(hasNoRender)" in run_tick_state_section
            and "instruction.getOpcode() == Opcodes.RETURN" in run_tick_state_section
            and "method.instructions.insert(code)" not in run_tick_state_section,
        ),
        (
            "Minecraft patcher pumps browser channels without duplicating vanilla packet queue work",
            "browserPackets.add(pumpBrowserChannels())" in client_patcher
            and "private static InsnList processQueuedPacketsDuringBrowserTick()" not in client_patcher
            and "Minecraft browser channel pump hook point was not found" in client_patcher,
        ),
        (
            "Browser transport callbacks never enter Java packet handlers directly",
            "BrowserClientNetwork" in client_patcher
            and "bridge.inboundPump" in browser_client_network
            and "installed = installInboundPump();" in browser_client_network
            and "setTimeout(function()" not in browser_client_network
            and "callback();" not in browser_client_network
            and "BrowserWebSocketChannel" not in browser_client_network
            and "public static void pumpNow()" not in browser_client_network
            and "minecraft.packetProcessor().processQueuedPackets()" not in browser_client_network
            and "state.inboundPump()" in netty_browser_channel,
        ),
        (
            "Client game packets use a bounded batch outside the WebSocket callback",
            "ClientConfigurationPacketListenerImpl" in client_patcher
            and "patchPacketProcessorBrowserSlice" in client_patcher
            and "PacketProcessor browser slice patch point was not found" in client_patcher
            and '"packetsToBeHandled", "Ljava/util/Queue;"' in client_patcher
            and '"java/util/Queue", "poll", "()Ljava/lang/Object;"' in client_patcher
            and '"dev/gaius/browser/BrowserPacketScheduler"' in client_patcher
            and '"beginBatch"' in client_patcher
            and '"shouldProcessNext"' in client_patcher
            and "MAX_PACKETS_PER_BATCH = 16" in browser_packet_scheduler
            and "MIN_WORKER_PACKETS_PER_BATCH = 4" in browser_packet_scheduler
            and "BrowserIntegratedServerMain.isWorkerServer()" in browser_packet_scheduler
            and "packetsProcessed >= minimumPackets" in browser_packet_scheduler
            and "BATCH_BUDGET_NANOS = 2_000_000L" in browser_packet_scheduler
            and "System.nanoTime() >= deadlineNanos" in browser_packet_scheduler,
        ),
        (
            "Browser resource reload scheduler defers multiplayer packets to the normal Java tick",
            "command.run()" in browser_resource_reload_scheduler
            and "BrowserClientNetwork.pumpNow()" not in browser_resource_reload_scheduler
            and "BrowserWebSocketChannel.pumpAll()" not in browser_resource_reload_scheduler,
        ),
        (
            "Browser resource-pack reload profiling wraps vanilla listeners without changing their graph",
            "patchResourceReloadProfiling" in client_patcher
            and "BrowserResourceReloadProfiler" in client_patcher
            and "SimpleReloadInstance listener iteration patch point was not found" in client_patcher
            and "TimedListener" in browser_resource_reload_profiler
            and "delegate.reload" in browser_resource_reload_profiler
            and "unwrap" in browser_resource_reload_profiler
            and "__gaiusResourceReloadTimings" in browser_resource_reload_profiler
            and "get('diag') === 'reload'" in browser_resource_reload_profiler
            and "detailedProfilingEnabled" in browser_resource_reload_profiler
            and "__gaiusProfileResourceReloadTasks" in browser_resource_reload_profiler
            and "if (!profileTasks)" in browser_resource_reload_profiler
            and "[Gaius reload] task" in browser_resource_reload_profiler,
        ),
        (
            "Browser resource-pack preparation yields between bounded batches",
            "BrowserResourceReloadScheduler.defer" in browser_resource_reload_profiler
            and "FRAME_WORK_BUDGET_NANOS = 11_000_000L" in browser_resource_reload_scheduler
            and "Platform.schedule(BrowserResourceReloadScheduler::runAfterYield, 0)"
            in browser_resource_reload_scheduler
            and "requestAnimationFrame" not in browser_resource_reload_scheduler
            and "MAX_SUBMISSIONS_PER_BATCH" in browser_resource_reload_scheduler
            and "delegate.execute(command)" in browser_resource_reload_scheduler,
        ),
        (
            "Browser resource-pack reloads send configuration keepalives immediately",
            "patchClientKeepAliveBrowser" in client_patcher
            and 'method.name.startsWith("lambda$handleKeepAlive$")' in client_patcher
            and 'method.desc.equals("()Z")' in client_patcher
            and "ClientCommonPacketListenerImpl keepalive predicate was not found" in client_patcher
            and "Opcodes.ICONST_1" in client_patcher,
        ),
        (
            "Verified remote server packs complete configuration before the browser reload",
            "patchEarlyBrowserServerPackSuccess" in client_patcher
            and '"net/minecraft/client/resources/server/DownloadedPackSource$6"' in client_patcher
            and '"browserEarlyApplied"' in client_patcher
            and '"DOWNLOADED"' in client_patcher
            and '"SUCCESSFULLY_LOADED"' in client_patcher
            and '"APPLIED"' in client_patcher
            and "DownloadedPackSource response sender constructor was not found" in client_patcher,
        ),
        (
            "Joined multiplayer worlds stay visible and interactive during pack reloads",
            "patchLoadingOverlayBrowserForeground" in client_patcher
            and "Minecraft foreground resource reload overlay hook point was not found"
            in client_patcher
            and "LoadingOverlay foreground completion point changed" in client_patcher
            and '"level"' in client_patcher
            and '"setOverlay"' in client_patcher,
        ),
        (
            "Browser quick-connect automatically accepts required server resource packs",
            '"handleResourcePackPush"' in client_patcher
            and '"required"' in client_patcher
            and '"()Z"' in client_patcher
            and '"allowServerPacks"' in client_patcher
            and '"pushPack"' in client_patcher
            and "vanillaResourcePackHandling" in client_patcher
            and "resource-pack thread check was not found" in client_patcher,
        ),
        (
            "Browser resource-pack reloads handle client configuration packets inline",
            "patchClientPacketUtilsBrowserInline" in client_patcher
            and '"net/minecraft/network/protocol/PacketUtils.class"' in client_patcher
            and '"PacketUtils client packet scheduler patch point was not found"' in client_patcher
            and '"net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl"' in client_patcher
            and '"net/minecraft/network/protocol/game/ClientboundStartConfigurationPacket"' in client_patcher
            and '"net/minecraft/network/protocol/game/ClientboundLoginPacket"' in client_patcher
            and "handleLoginImmediateReadyHooked" in client_patcher
            and "ClientPacketListener immediate player-ready return changed" in client_patcher
            and "vanillaScheduling" in client_patcher,
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
            "Minecraft patcher anchors browser spawn above the generated column",
            "replaceInitialSpawnForBrowser" in client_patcher
            and "server.browserFastInitialSpawn" in client_patcher
            and "Climate$Sampler" in initial_spawn_section
            and "findSpawnPosition" in initial_spawn_section
            and '"getMaxY"' in initial_spawn_section
            and "getBaseHeight" not in initial_spawn_section
            and "MOTION_BLOCKING_NO_LEAVES" not in initial_spawn_section
            and "getMiddleBlockX" in initial_spawn_section
            and "getMiddleBlockZ" in initial_spawn_section
            and "PlayerSpawnFinder" not in initial_spawn_section
            and "getHeightmapPos" not in initial_spawn_section
            and "BlockPos.ZERO" not in initial_spawn_section,
        ),
        (
            "Minecraft patcher finds a safe surface after the spawn chunk is full",
            "gaius$fixupLoadedSpawn" in client_patcher
            and "loadedSpawnFixups" in client_patcher
            and "PrepareSpawnTask loaded-spawn fixup point changed" in client_patcher
            and '"spawnLevel"' in client_patcher
            and '"getSpawnPosInChunk"' in client_patcher
            and '"MOTION_BLOCKING_NO_LEAVES"' in client_patcher
            and '"getHeightmapPos"' in client_patcher,
        ),
        (
            "Minecraft patcher skips synchronous stronghold biome relocation in browser",
            "patchChunkGeneratorStructureStateBrowserFastRings" in client_patcher
            and 'candidate.name.startsWith("lambda$generateRingPositions$")'
            in client_patcher
            and "candidate.desc.equals(descriptor)" in client_patcher
            and '"net/minecraft/world/level/ChunkPos"' in client_patcher
            and '"(II)V"' in client_patcher,
        ),
        (
            "Server Worker bounds task-layer worldgen slices without suspending deep hot loops",
            # Worldgen coordination is deliberately at the task layer. These
            # patch points make runUntilWait/waitForScheduledLayer/scheduleLayer/
            # canLoadWithoutGeneration resume at bounded cooperative pulses
            # without turning the deep terrain/biome/carver loops into
            # suspension state machines.
            "patchChunkGenerationCooperation(jar, root)"
                in minecraft_262_browser_patcher
            and "requireWorldgenLoopPulses" in minecraft_262_browser_patcher
            and "requireNoServerWorkTurnReset" in minecraft_262_browser_patcher
            and all(
                marker in minecraft_262_browser_patcher
                for marker in (
                    "ChunkGenerationTask.runUntilWait",
                    "ChunkGenerationTask.waitForScheduledLayer",
                    "ChunkGenerationTask.scheduleLayer",
                    "ChunkGenerationTask.canLoadWithoutGeneration",
                )
            )
            and "BrowserWorldgenScheduler" in minecraft_262_browser_patcher
            and "browserWorldgenCheckpoint" in client_patcher
            and "browserWorldgenBeginTaskWork" in client_patcher
            and "browserWorldgenEndTaskWork" in client_patcher
            and '"beginTaskWork",\n                "(Ljava/lang/String;)I"' in client_patcher
            and '"endTaskWork",\n                "(I)V"' in client_patcher
            and "instrumentBrowserTaskScope(" in client_patcher
            and '"MinecraftServer.pollTask"' in client_patcher
            and "browserWorldgenBeginTaskWork" in minecraft_262_browser_patcher
            and "browserWorldgenEndTaskWork" in minecraft_262_browser_patcher
            and '"beginTaskWork",\n                "(Ljava/lang/String;)I"' in minecraft_262_browser_patcher
            and '"endTaskWork",\n                "(I)V"' in minecraft_262_browser_patcher
            and "instrumentBrowserTaskScope(runUntilWait" in minecraft_262_browser_patcher
            and "TryCatchBlockNode" in minecraft_262_browser_patcher
            and "writeComputeFrames(node, root.resolve(owner + \".class\"))" in minecraft_262_browser_patcher
            and "public static void checkpoint()" in browser_worldgen_scheduler
            and "public static void beginServerWorkTurn()" in browser_worldgen_scheduler
            and "public static int beginTaskWork()" in browser_worldgen_scheduler
            and "public static int beginTaskWork(String taskLabel)"
                in browser_worldgen_scheduler
            and "recordSchedulerTaskLabel(taskLabel)" in browser_worldgen_scheduler
            and "currentTaskScopeId" in browser_worldgen_scheduler
            and "currentTaskLabel" in browser_worldgen_scheduler
            and "maxTaskContext" in browser_worldgen_scheduler
            and "maxSliceContext" in browser_worldgen_scheduler
            and "public static void endTaskWork(int token)" in browser_worldgen_scheduler
            and "private static int taskWorkDepth" in browser_worldgen_scheduler
            and "activeWorkElapsedMillis" in browser_worldgen_scheduler
            and "activeSliceElapsedMillis" in browser_worldgen_scheduler
            and "activeSegmentElapsedMillis" in browser_worldgen_scheduler
            and "reentrantTaskWorkDepth" in browser_worldgen_scheduler
            and "TASK_SCOPE_NONE" in browser_worldgen_scheduler
            and "TASK_SCOPE_NORMAL" in browser_worldgen_scheduler
            and "TASK_SCOPE_REENTRANT" in browser_worldgen_scheduler
            and "if (token == TASK_SCOPE_REENTRANT)" in browser_worldgen_scheduler
            and "if (deferredTaskScopeEnds == 0)" in browser_worldgen_scheduler
            and "deferredTaskScopeEnds = 0;" in browser_worldgen_scheduler
            and "recordSchedulerMarker(" in browser_worldgen_scheduler
            and "__gaiusSlowProbeTelemetryEnabled !== true" in browser_worldgen_scheduler
            and "__gaiusWorldgenSchedulerMarker" in browser_worldgen_scheduler
            and "lastTaskActiveWorkMillis" in browser_worldgen_scheduler
            and "lastTaskScopeWallMillis" in browser_worldgen_scheduler
            and "serverWorkTurnActive" in browser_worldgen_scheduler
            and '"server-work-turn-start"' in browser_worldgen_scheduler
            and '"server-work-turn-end"' in browser_worldgen_scheduler
            and "boolean checkpointOnly = reason == YIELD_CHECKPOINT"
                in browser_worldgen_scheduler
            and "&& progressPulsesInSlice == 0;" in browser_worldgen_scheduler
            and "recordCheckpointOnlyYield(" in browser_worldgen_scheduler
            and "checkpointOnlyYields" in browser_worldgen_scheduler
            and "checkpointOnlyP99YieldDelayMillis" in browser_worldgen_scheduler
            and "checkpointOnlyMaxYieldDelayMillis" in browser_worldgen_scheduler
            and "checkpointOnlyMaxQueueDepth" in browser_worldgen_scheduler
            and "checkpointOnlyMaxNetworkWaitPulses" in browser_worldgen_scheduler
            and "checkpointOnlyMaxReentrantYieldDepth" in browser_worldgen_scheduler
            and "__checkpointOnlyYieldDelayHistogram" in browser_worldgen_scheduler
            and "enumerable: false" in browser_worldgen_scheduler
            and "public static void pulse()" in browser_worldgen_scheduler
            and "requestYield(" in browser_worldgen_scheduler
            and "TModernRuntimeSupport.yieldToEventLoop" in browser_worldgen_scheduler
            and "DEFAULT_SLICE_MILLIS = 8.0" in browser_worldgen_scheduler
            and "MIN_ADAPTIVE_SLICE_MILLIS = 2.0" in browser_worldgen_scheduler
            and "CLOCK_CHECK_INTERVAL = 1" in browser_worldgen_scheduler
            and "NETWORK_CHECK_INTERVAL = 1" in browser_worldgen_scheduler
            and "MAX_NETWORK_WAIT_PULSES = 2" in browser_worldgen_scheduler
            and "MAX_PULSES_PER_TURN = 4096" in browser_worldgen_scheduler
            and "public static native void yieldToEventLoop(int delayMillis);"
                in modern_runtime_support
            and "TThread.setCurrentThread(thread)" in modern_runtime_support
            and "Platform.schedule(resume, delayMillis)" in modern_runtime_support
            and "Platform.postpone(resume)" in modern_runtime_support
            and "Thread.sleep(" not in browser_worldgen_scheduler
            and "patchChunkGenerationTaskBrowserYield" not in client_patcher
            and "browserWorldgenBeginServerWorkTurn()" in client_patcher
            and "method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn())"
                in client_patcher
            and "method.instructions.insert(instruction, browserWorldgenCheckpoint())"
                in client_patcher
            and "requireWorldgenSchedulerCalls" in client_patcher
            and all(
                marker in client_patcher
                for marker in (
                    'requireWorldgenSchedulerCalls("NoiseBasedChunkGenerator.doFill", method, 0)',
                    '"NoiseBasedChunkGenerator.applyCarvers", applyCarvers, 0',
                    'requireWorldgenSchedulerCalls("NoiseChunk.fillSlice", fillSlice, 0)',
                    '"NoiseChunk.fillAllDirectly",',
                    '"NoiseChunk.selectCellYZ",',
                    'requireWorldgenSchedulerCalls("Climate.RTree.SubTree.search", method, 0)',
                    'requireWorldgenSchedulerCalls("SurfaceSystem.buildSurface", method, 0)',
                    'requireWorldgenSchedulerCalls("ChunkGenerator.applyBiomeDecoration", decoration, 0)',
                    'requireWorldgenSchedulerCalls("ChunkGenerator.createStructures", structureSets, 0)',
                    'requireWorldgenSchedulerCalls("ChunkGenerator.createReferences", references, 0)',
                    'requireWorldgenSchedulerCalls("WorldCarver.carveEllipsoid", method, 0)',
                    'requireWorldgenSchedulerCalls("LightEngine.propagateIncreases", increases, 0)',
                    'requireWorldgenSchedulerCalls("LightEngine.propagateDecreases", decreases, 0)',
                    'requireWorldgenSchedulerCalls("LevelChunkSection.fillBiomesFromNoise", method, 0)',
                )
            ),
        ),
        (
            "TeaVM LockSupport wakes parked Worker threads with one-shot permits",
            "IdentityHashMap<Thread, Boolean> permits" in teavm_lock_support
            and "IdentityHashMap<Thread, Boolean> parkedThreads" in teavm_lock_support
            and "permits.put(thread, Boolean.TRUE)" in teavm_lock_support
            and "parkedThreads.containsKey(thread)" in teavm_lock_support
            and "thread.interrupt()" in teavm_lock_support
            and "takePermit(thread)" in teavm_lock_support,
        ),
        (
            "Built-in structure NBT uses asynchronous native browser gzip",
            "patchStructureTemplateManagerBrowserGzip" in client_patcher
            and '"readStructure"' in client_patcher
            and '"dev/gaius/browser/BrowserGzip"' in client_patcher
            and "DecompressionStream('gzip')" in browser_gzip
            and "InputStream input" in browser_gzip
            and "input.readAllBytes()" in browser_gzip
            and "Thread.sleep(0L)" in browser_gzip
            and browser_gzip.count("@JSByRef byte[]") == 2
            and "async function" not in browser_gzip
            and "await " not in browser_gzip
            and "NbtIo.read(data, accounter)" in browser_gzip
            and "NbtIo.readCompressed(input, accounter)" in browser_gzip
            and file_matches(SERVER_WORKER_JS, rb"new DecompressionStream\('gzip'\)")
            and not file_matches(
                SERVER_WORKER_JS,
                rb"BrowserGzip_(?:startDecompression|copyResult)[^\n]*otji_JS_wrap",
            ),
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
            worker_improved_noise_generated,
        ),
        (
            "Generated Server Worker passes scalar bit storage arrays by reference",
            is_current_named_profile or worker_bit_storage_generated,
        ),
        (
            "Generated Server Worker keeps scalar bit storage off BigInt on typed arrays",
            is_current_named_profile or worker_bit_storage_generated,
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
            "Generated release client fuses element-buffer binding into draw dispatch",
            file_matches(
                DIST / "classes.js",
                rb"const vao\s*=\s*state\.getVaoEmu\(\);\s*"
                rb"const nextId\s*=\s*[\w$]+\s*\|\s*0;\s*"
                rb"const current\s*=\s*state\.boundBuffers\.get\(gl\.ELEMENT_ARRAY_BUFFER\)\s*\|\s*0;"
                rb".{0,2048}state\.bindPhysicalElementBuffer\(vao,\s*vao\.elementArrayBufferObject\s*\|\|\s*null\)"
                rb".{0,4096}state\.executeDraw",
            ),
        ),
        (
            "Generated release client uses cached pipeline draw metadata",
            (
                file_contains(DIST / "classes.js", "GlRenderPipeline[")
                and file_contains(DIST / "classes.js", "executeDraw")
                and file_contains(DIST / "classes.js", "elementArrayBufferObject")
                and not file_matches(
                    DIST / "classes.js",
                    rb"A\.[\w$]+=\(a,b,c,d,e,f,g,h\)=>\{"
                    rb"(?:(?!;\};).){0,6144}switch\(A\."
                    rb"(?:(?!;\};).){0,2048}"
                    rb"\$p=2;case 2:A\.[\w$]+\(\w+,\w+,\w+,\w+,\w+,\w+,\w+,\w+\);",
                )
            )
            if is_current_named_profile
            else (
                not is_current_named_profile or file_matches(
                    DIST / "classes.js",
                    rb"A\.[\w$]+=\(a,b,c,d,e,f,g,h\)=>\{"
                    rb"(?:(?!;\};).){0,4096}"
                    rb"\w+=5121\+\w+\|0;"
                    rb"(?:(?!;\};).){0,2048}"
                    rb"\$p=2;case 2:A\.[\w$]+\(\w+,\w+,\w+,\w+,\w+,\w+,\w+,\w+\);"
                )
                and not file_matches(
                    DIST / "classes.js",
                    rb"A\.[\w$]+=\(a,b,c,d,e,f,g,h\)=>\{"
                    rb"(?:(?!;\};).){0,6144}switch\(A\."
                    rb"(?:(?!;\};).){0,2048}"
                    rb"\$p=2;case 2:A\.[\w$]+\(\w+,\w+,\w+,\w+,\w+,\w+,\w+,\w+\);",
                )
            ),
        ),
        (
            "Generated Server Worker keeps Perlin octave sampling allocation-free",
            worker_java_hotpath_provenance and worker_perlin_wrap_generated,
        ),
        (
            "Generated Server Worker caches immutable climate bounds and targets",
            worker_climate_generated,
        ),
        (
            "Generated Server Worker uses direct BigInt packed block coordinates",
            is_current_named_profile or worker_block_pos_generated,
        ),
        (
            "Generated Server Worker computes biome zoom corners in one BigInt hot path",
            worker_biome_zoom_generated,
        ),
        (
            "Generated Server Worker batches warmed aquifer center selection",
            worker_aquifer_generated,
        ),
        (
            "Generated Server Worker blends packed structure terrain without Java iterators",
            worker_beardifier_generated,
        ),
        (
            "Generated Server Worker interpolator updates use direct lerp arithmetic",
            worker_java_hotpath_provenance and worker_lerp_generated,
        ),
        (
            "Generated Server Worker keeps Perlin wrap longs off the normal path",
            worker_perlin_wrap_generated,
        ),
        (
            "Generated Server Worker reuses ProtoChunk heightmap arrays",
            worker_java_hotpath_provenance,
        ),
        (
            "Generated Server Worker uses direct ProtoChunk section access",
            worker_java_hotpath_provenance,
        ),
        (
            "Browser configuration retains lighting neighbors and waits only for center entities",
            "patchPlayerSpawnFinderBrowser" in client_patcher
            and '"findSpawn"' in client_patcher
            and '"atBottomCenterOf"' in client_patcher
            and '"getSpawnPosInChunk"' in client_patcher
            and '"getHeightmapPos"' in client_patcher
            and '"completedFuture"' in client_patcher
            and "patchPrepareSpawnTaskBrowser" in client_patcher
            and '"lambda$tick$0"' in client_patcher
            and '"addTicketWithRadius"' in client_patcher
            and '"getChunkFutureMainThread"' in client_patcher
            and "getChunkFutureMainThread.access" in client_patcher
            and '"net/minecraft/world/level/chunk/status/ChunkStatus"' in client_patcher
            and '"FULL"' in client_patcher
            and '"keepAlive"' in client_patcher
            and '"waitForEntities"' in client_patcher
            and "replaceIntArgumentBeforeCall" in client_patcher,
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
            "Minecraft keeps deep world generation synchronous behind cooperative task layers",
            "patchNoiseBasedChunkGeneratorBrowserSynchronous" in client_patcher
            and "cacheNoiseBasedChunkGeneratorDoFillConstants" in client_patcher
            and "patchNoiseChunkBrowserSynchronous" in client_patcher
            and "patchClimateRTreeBrowserSynchronous" in client_patcher
            and "patchLevelChunkSectionBrowserSynchronous" in client_patcher
            and "patchChunkGenerationTaskBrowserYield" not in client_patcher
            and "patchChunkGenerationCooperation(jar, root)"
                in minecraft_262_browser_patcher
            and "patchLoadingChunkTrackerCooperation"
                in minecraft_262_browser_patcher
            and "BROWSER_REGION_FILE_CACHE_SIZE = 16"
                in minecraft_262_browser_patcher
            and "patchChunkTaskDispatcher" not in client_patcher
            and "BrowserWorldgenScheduler" in client_patcher
            and "patchSurfaceSystemBrowserSynchronous" in client_patcher
            and "patchSurfaceRulesSequenceBrowserIndexed" in client_patcher
            and "patchDensityFunctionsPureTransformersBrowserDirect" in client_patcher
            and "patchWorldgenRecordHashCodeCaches" in client_patcher
            and "cacheImmutableRecordHashCode" in client_patcher
            and '"browserHashCodeComputed"' in client_patcher
            and '"net/minecraft/util/CubicSpline$Multipoint"' in client_patcher
            and '"dev/gaius/browser/BrowserDensityFunctions"' in client_patcher
            and "public static native double transformMapped" in browser_density_functions
            and "public static native double transformMulOrAdd" in browser_density_functions
            and "testDensityTransformersHotPath" in platform_smoke
            and "patchChunkGeneratorBrowserSynchronous" in client_patcher
            and 'candidate.name.startsWith("lambda$createStructures$")' in client_patcher
            and '"ChunkGenerator.createStructures"' in client_patcher
            and '"ChunkGenerator.createReferences"' in client_patcher
            and "patchWorldCarverBrowserSynchronous" in client_patcher
            and "patchLightEngineBrowserSynchronous" in client_patcher
            and "requireWorldgenSchedulerCalls" in client_patcher
            and "insertPulseAfterLoopCounter" not in client_patcher
            and "insertPulseAfterUniqueLoopCounter" not in client_patcher
            and "insertPowerOfTwoPulseAfterLoopCounter" not in client_patcher
            and "insertWorldgenPulseOnLoopBackedges" not in client_patcher
            and "pulseSparse" not in client_patcher
            and "browserWorldgenImmediatePulse" not in client_patcher,
        ),
        (
            "Browser Worker prioritizes equal-level chunk tasks around the latest player position",
            "patchChunkTaskPriorityQueueBrowserNearestFirst" in client_patcher
            and '"updatePlayerPos"' in client_patcher
            and '"recordPlayerPosition"' in client_patcher
            and '"chooseNext"' in client_patcher
            and "MAX_CANDIDATES_TO_SCAN = 512" in browser_chunk_task_priority
            and "distanceSquared * DISTANCE_WEIGHT" in browser_chunk_task_priority
            and "forward * DIRECTION_WEIGHT" in browser_chunk_task_priority
            and "firstLongKey()" in browser_chunk_task_priority
            and 'type: "chunk-priority-stats"' in server_worker_bootstrap,
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
            "Browser worldgen removes biome helper closures and surface iterators",
            "__gaiusBiomeManagerConstants" in browser_biome_manager
            and "const next =" not in browser_biome_manager
            and "const fiddle =" not in browser_biome_manager
            and "patchSurfaceRulesContextBrowserReusableBiomeSupplier" in client_patcher
            and '"browserBiomeSupplier"' in client_patcher
            and "patchSurfaceRulesLazyConditionBrowserPrimitiveCache" in client_patcher
            and '"browserContextLastUpdate"' in client_patcher
            and '"browserResultInitialized"' in client_patcher
            and "implements Supplier<Holder<Biome>>" in browser_surface_biome_supplier
            and "public void reset(int x, int y, int z)" in browser_surface_biome_supplier
            and "if (!resolved)" in browser_surface_biome_supplier
            and "value = biomeGetter.apply(pos.set(x, y, z))" in browser_surface_biome_supplier
            and "testSurfaceBiomeSupplier" in platform_smoke
            and "patchSurfaceRulesSequenceBrowserIndexed" in client_patcher
            and '"java/util/List"' in client_patcher[
                client_patcher.find("private static void patchSurfaceRulesSequenceBrowserIndexed"):
                client_patcher.find("private static void patchNoiseChunkBrowserSynchronous")
            ]
            and '"get"' in client_patcher[
                client_patcher.find("private static void patchSurfaceRulesSequenceBrowserIndexed"):
                client_patcher.find("private static void patchNoiseChunkBrowserSynchronous")
            ]
            and '"java/util/Iterator"' not in client_patcher[
                client_patcher.find("private static void patchSurfaceRulesSequenceBrowserIndexed"):
                client_patcher.find("private static void patchNoiseChunkBrowserSynchronous")
            ],
        ),
        (
            "Generated Server Worker keeps concrete density transforms non-suspending",
            worker_java_hotpath_provenance and worker_density_generated,
        ),
        (
            "Generated Server Worker caches immutable worldgen record hashes",
            worker_java_hotpath_provenance,
        ),
        (
            "Generated Server Worker reuses the lazy surface biome supplier",
            worker_java_hotpath_provenance,
        ),
        (
            "Generated Server Worker uses primitive lazy surface-condition caches",
            worker_java_hotpath_provenance,
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
            and "method.instructions.insert(instruction, browserWorldgenCheckpoint())"
                in client_patcher
            and "method.instructions.insert(browserPackets)" in client_patcher
            and '"io/netty/channel/browser/BrowserWebSocketChannel"' in client_patcher
            and '"pumpAll"' in client_patcher,
        ),
        (
            "Worker input wakeup retains wrong-thread pending work",
            "bindServerThreadFromServerLoop" in browser_integrated_server_main
            and "retryNetworkInputAfterTaskFailure" in browser_integrated_server_main
            and "network-pump-lifecycle-drop" in browser_integrated_server_main
            and "wrongThreadRetriesBounded: true" in singleplayer_network_wakeup_smoke
            and "wrongThreadExactlyOnceServerRun: true"
                in singleplayer_network_wakeup_smoke
            and "wrongThreadFailClosed: true" in singleplayer_network_wakeup_smoke
            and "Pending input remained after the integrated server stopped"
                in singleplayer_network_wakeup_smoke,
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
            and "Verified current vanilla single-raycast block targeting" in client_patcher
            and "patchLevelRendererBrowserBlockOutlineOpacity" in client_patcher
            and "BrowserTargeting" in client_patcher
            and "GameRenderer post-camera block targeting patch point was not found" in client_patcher
            and "pickFromRenderCamera(minecraft, camera, cameraPosition)"
                in browser_targeting
            and "new Vec3(camera.forwardVector()).normalize()" in browser_targeting
            and "minecraft.level.clip" in browser_targeting
            and "ProjectileUtil.getEntityHitResult" in browser_targeting
            and "EntitySelector.CAN_BE_PICKED" in browser_targeting
            and "alignBlockHitToCamera" not in browser_targeting
            and "raycastHitResult" not in browser_targeting
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
            "Worker singleplayer grants commands to its isolated local player",
            "setAllowCommandsForAllPlayers(true)" in browser_integrated_server_main
            and '"max-players=1"' in browser_integrated_server_main,
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
            "TeaVM client externalizes vanilla binary assets behind a narrow classpath fallback",
            "minecraft-embedded-resources.txt" in build_teavm
            and "build-vanilla-assets-pack.py" in build_teavm
            and "EMBEDDED_RESOURCE_LIST" in minecraft_resource_supplier
            and "readResourceList(EMBEDDED_RESOURCE_LIST)" in minecraft_resource_supplier
            and "readResourceList(RESOURCE_LIST)" in minecraft_resource_supplier
            and (
                (
                    "assets/minecraft/lang/en_us.json" in generated_embedded_resource_list
                    and "assets/minecraft/lang/deprecated.json" in generated_embedded_resource_list
                    and "assets/minecraft/font/unifont.zip" in generated_embedded_resource_list
                    and "assets/minecraft/sounds/random/eat1.ogg" in generated_embedded_resource_list
                    and "assets/minecraft/textures/block/stone.png" not in generated_embedded_resource_list
                    and "data/minecraft/" not in generated_embedded_resource_list
                )
                or (
                    file_contains(DIST / "classes.js", "assets/minecraft/lang/en_us.json")
                    and file_contains(DIST / "classes.js", "assets/minecraft/lang/deprecated.json")
                    and file_contains(DIST / "classes.js", "assets/minecraft/font/unifont.zip")
                    and file_contains(DIST / "classes.js", "assets/minecraft/sounds/random/eat1.ogg")
                    and not file_contains(DIST / "classes.js", "assets/minecraft/textures/block/stone.png")
                )
            ),
        ),
        (
            "TeaVM resource-list production uses stable Java String.compareTo order",
            'LC_ALL=C sort -u -o "$resource_list" "$resource_list"' in build_teavm
            and "assert_java_sorted_resource_list" in build_server_worker
            and 'LC_ALL=C sort -c "$path"' in build_server_worker
            and 'LC_ALL=C sort -u -o "$server_resource_list_tmp" "$server_resource_list_tmp"'
            in build_server_worker,
        ),
        (
            "Generated vanilla asset pack is deterministic and contains rendering, sound, font, and data resources",
            VANILLA_ASSET_PACK.is_file()
            and VANILLA_ASSET_PACK.stat().st_size > 30_000_000
            and len(vanilla_assets_index) > 20_000
            and all(
                name in vanilla_assets_index
                for name in (
                    "assets/minecraft/atlases/blocks.json",
                    "assets/minecraft/textures/block/stone.png",
                    "assets/minecraft/textures/item/diamond.png",
                    "assets/minecraft/font/unifont.zip",
                    "assets/minecraft/sounds.json",
                    "assets/minecraft/sounds/random/eat1.ogg",
                    "data/minecraft/datapacks/minecart_improvements/pack.mcmeta",
                    "pack.png",
                )
            )
            and 'MAGIC = b"GAIUSVP1"' in build_vanilla_assets_pack
            and "mtime=0" in build_vanilla_assets_pack
            and "set(index) != set(names)" in build_vanilla_assets_pack,
        ),
        (
            "Browser validates and decodes vanilla assets before loading TeaVM client code",
            "function decodeGaiusVanillaAssets(source)" in postprocess_index_html
            and "hasGaiusVanillaAssetsMagic" in postprocess_index_html
            and "resourceCount < 1000" in postprocess_index_html
            and "await window.__gaiusVanillaAssetsReady" in postprocess_index_html
            and "bootTimings.vanillaAssetsReady" in postprocess_index_html
            and "__gaiusVanillaAssetsCompressedPromise" in postprocess_index_html
            and '"vanilla-assets.pack.gz"' in serve_dist
            and "vanilla_asset_pack" in build_release,
        ),
        (
            "Release JavaScript excludes representative vanilla textures from the executable parse path",
            (DIST / "classes.js").is_file()
            and (DIST / "classes.js").stat().st_size < 120_000_000
            and Path(str(DIST / "classes.js") + ".gz").is_file()
            and Path(str(DIST / "classes.js") + ".gz").stat().st_size < 50_000_000
            and not file_matches(
                DIST / "classes.js",
                rb'"assets/minecraft/textures/(?:block/stone|item/diamond|gui/title/minecraft)\.png"\s*:\s*"',
            ),
        ),
        (
            "Release JavaScript records the exact optimized compiler profile",
            teavm_release_profile_matches(
                CLIENT_RELEASE_PROFILE,
                "client",
                DIST / "classes.js",
                CLIENT_TEA_POM,
                client_profile_resources,
            ),
        ),
        (
            "Singleplayer Worker records the exact optimized compiler profile",
            worker_release_profile_ok,
        ),
        (
            "Generated release client includes RelayNode target attestation guard",
            file_contains(DIST / "classes.js", "target-attestation")
            and file_contains(
                DIST / "classes.js",
                "RelayNode target attestation mismatch",
            ),
        ),
        (
            "VanillaPackResources reads external assets and retains byte-array classpath fallback",
            "new ByteArrayInputStream(input.readAllBytes())" in vanilla_pack_resources
            and "readExternalResource" in vanilla_pack_resources
            and "externalResourceLength" in vanilla_pack_resources
            and "copyExternalResource" in vanilla_pack_resources
            and "globalThis.__gaiusVanillaAssets" in vanilla_pack_resources
            and "openResourceStream" in vanilla_pack_resources
            and "class.getClassLoader().getResourceAsStream(normalized)" in vanilla_pack_resources
            and 'getResourceAsStream("/" + normalized)' not in vanilla_pack_resources,
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
            and "listedResourceCache.put(key, cached)" in vanilla_pack_resources
            and "int start = lowerBound(resources, prefix)" in vanilla_pack_resources
            and "values[middle].compareTo(target)" in vanilla_pack_resources
            and "sortedResourceCopy" in vanilla_pack_resources
            and "Arrays.copyOf" in vanilla_pack_resources
            and "Arrays.sort" in vanilla_pack_resources,
        ),
        (
            "26.2 VanillaPackResources defensively sorts resource lists for lowerBound",
            "sortedResourceCopy" in vanilla_pack_resources_262
            and "Arrays.copyOf" in vanilla_pack_resources_262
            and "Arrays.sort" in vanilla_pack_resources_262
            and "String.compareTo" in vanilla_pack_resources_262,
        ),
        (
            "Resource-order regression test covers all five former omission classes",
            "VanillaPackResources.lowerBound(String.compareTo)" in vanilla_resource_order_test
            and "data/minecraft/enchantment/" in vanilla_resource_order_test
            and "data/minecraft/worldgen/noise/" in vanilla_resource_order_test
            and "data/minecraft/worldgen/noise_settings/" in vanilla_resource_order_test
            and "data/minecraft/worldgen/structure/" in vanilla_resource_order_test
            and "data/minecraft/worldgen/structure_set/" in vanilla_resource_order_test
            and "repairedOmissions" in vanilla_resource_order_test,
        ),
        (
            "Browser SystemReport avoids TeaVM-unsupported Formatter percent-n",
            "String.format" not in system_report
            and '"%s: %s%n"' not in system_report
            and ".append(name).append(\": \").append(value).append('\\n')"
                in system_report,
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
            "Generated browser resource list contains Mojang Unicode fallback fonts",
            "assets/minecraft/font/include/unifont.json" in generated_resource_list
            and "assets/minecraft/font/include/unifont_pua.json" in generated_resource_list
            and "assets/minecraft/font/unifont.zip" in generated_resource_list
            and "assets/minecraft/font/unifont_jp.zip" in generated_resource_list
            and "assets/minecraft/font/unifont_pua.zip" in generated_resource_list
            and isinstance(generated_unifont, dict)
            and any(
                provider.get("type") == "unihex"
                and provider.get("hex_file") == "minecraft:font/unifont.zip"
                for provider in generated_unifont.get("providers", [])
                if isinstance(provider, dict)
            ),
        ),
        (
            "Generated release embeds the non-empty Mojang Unicode font definition",
            "assets/minecraft/font/include/unifont.json" in generated_resource_list
            and bool(generated_unifont_base64)
            and embedded_resource_matches(
                DIST / "classes.js",
                "assets/minecraft/font/include/unifont.json",
                base64.b64decode(generated_unifont_base64),
            ),
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
            "TeaVM build embeds verified Mojang Unicode font assets for resource-pack fallback",
            "browser_font_manifest" in build_teavm
            and "minecraft/font/unifont.zip" in build_teavm
            and "minecraft/font/unifont_jp.zip" in build_teavm
            and "minecraft/font/unifont_pua.zip" in build_teavm
            and "resources.download.minecraft.net" in build_teavm
            and "Mapped browser Unicode font assets" in build_teavm
            and 'zip -q -d "$client_overlay" "$font_definition"' in build_teavm
            and "browser_font_assets" in fetch_version,
        ),
        (
            "Browser storage seeds defaults once and preserves user client options",
            "BROWSER_OPTION_DEFAULTS" in browser_file_persistence
            and "seedDefaultOptions" in browser_file_persistence
            and "currentDataVersion()" in browser_file_persistence
            and "runtimeWorldVersion()" in browser_file_persistence
            and "runtimeStoragePrefix()" in browser_file_persistence
            and '"version:" + currentDataVersion()' in browser_file_persistence
            and "LEGACY_DATA_VERSION = 4671" in browser_file_persistence
            and "gaius.fs.v1:" in browser_file_persistence
            and "migrateLegacyDefaultOptions" in browser_file_persistence
            and '"weatherRadius:3"' in browser_file_persistence
            and '"onboardAccessibility:false"' in browser_file_persistence
            and "LEGACY_BROWSER_OPTION_DEFAULTS" in browser_file_persistence
            and 'writeDefaultOptions("browser defaults data version")'
                in browser_file_persistence
            and "BROWSER_PERFORMANCE_OPTIONS" not in browser_file_persistence
            and "enforcePerformanceOptions" not in browser_file_persistence
            and "upsertOptions" not in browser_file_persistence
            and "renderDistance:6" in browser_file_persistence
            and "simulationDistance:4" in browser_file_persistence
            and "entityDistanceScaling:0.5" in browser_file_persistence
            and "maxFps:260" in browser_file_persistence
            and 'graphicsPreset:\\"fast\\"' in browser_file_persistence
            and 'renderClouds:\\"false\\"' in browser_file_persistence
            and "menuBackgroundBlurriness:0" in browser_file_persistence
            and "panoramaSpeed:0.0" in browser_file_persistence
            and "screenEffectScale:0.0" in browser_file_persistence
            and "maxAnisotropyBit:1" in browser_file_persistence
            and "textureFiltering:0" in browser_file_persistence
            and "particles:2" in browser_file_persistence
            and "storage-restore-crashed" in browser_file_persistence
            and "shouldRestoreAtStartup" in browser_file_persistence
            and 'relative.equals("level.dat")' in browser_file_persistence
            and "activeServerWorldId" in browser_file_persistence
            and 'normalized.endsWith("/servers.dat_old")' in browser_file_persistence
            and "writeDefaultOptions" in browser_file_persistence
            and "catch (Throwable exception)" in browser_file_persistence
            and "existing != null && existing.isFile()" in browser_file_persistence,
        ),
        (
            "Browser storage profiles are passed to the Worker and reject legacy v1 data",
            all(
                name in browser_singleplayer_client
                or name in server_worker_bootstrap
                or name in browser_integrated_server_main
                for name in STORAGE_RUNTIME_GLOBALS
            )
            and all(name in browser_singleplayer_client for name in STORAGE_RUNTIME_GLOBALS)
            and all(name in server_worker_bootstrap for name in STORAGE_RUNTIME_GLOBALS)
            and "configureStorage(message)" in server_worker_bootstrap
            and "requiredStorageIdentifier" in server_worker_bootstrap
            and "requiredStorageInteger" in server_worker_bootstrap
            and "requiredStoragePrefix" in server_worker_bootstrap
            and 'text === "gaius-fs-v1"' in server_worker_bootstrap
            and 'text === "gaius.fs.v1:"' in server_worker_bootstrap
            and "throw new Error(\"Singleplayer storage" in server_worker_bootstrap
            and "__gaiusStorageDatabaseName" in browser_integrated_server_main
            and "__gaiusStorageSchema" in browser_integrated_server_main
            and "storageDatabaseName === 'gaius-fs-v2-1.21.11'"
                in browser_integrated_server_main
            and "storageDatabaseName === 'gaius-fs-v2-26.2'"
                in browser_integrated_server_main
            and "IndexedDB storage configuration does not match profile"
                in browser_integrated_server_main
            and "storageConfigurationValid" in browser_singleplayer_client
            and "storageMatchesProfile" in browser_singleplayer_client
            and "gaius-fs-v2-1.21.11" in browser_singleplayer_client
            and "gaius-fs-v2-26.2" in browser_singleplayer_client
            and "gaius.fs.v2:1.21.11:" in browser_singleplayer_client
            and "gaius.fs.v2:26.2:" in browser_singleplayer_client
            and "regions-v2-1.21.11" in browser_singleplayer_client
            and "regions-v2-26.2" in browser_singleplayer_client
            and "storageProfiles" in server_worker_bootstrap
            and '"gaius-fs-v2-1.21.11"' in server_worker_bootstrap
            and '"gaius-fs-v2-26.2"' in server_worker_bootstrap
            and '"gaius.fs.v1:"' in browser_file_persistence
            and "private static String storagePrefix()" in browser_file_persistence
            and "runtimeStorageConfigurationSignature" in browser_file_persistence
            and "Browser storage profile changed after mount" in browser_file_persistence
        ),
        (
            "Browser world summaries retain transient session locks for vanilla listing",
            'if (normalized.endsWith("/level.dat"))' in browser_file_persistence
            and "ensureBrowserSessionLock(parent(normalized))" in browser_file_persistence
            and 'String lockPath = normalize(worldDirectory) + "/session.lock"' in browser_file_persistence
            and "writeVirtualFile(lockPath, new byte[0])" in browser_file_persistence,
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
            "Version release wrapper records one success exit marker and forwards failures",
            build_version_release.count("BUILD_EXIT=0") == 1
            and 'if "$root/port/scripts/build-teavm-release.sh"; then'
                in build_version_release
            and "printf 'BUILD_EXIT=0\\n'" in build_version_release
            and 'build_status="$?"' in build_version_release
            and 'exit "$build_status"' in build_version_release
            and 'exec "$root/port/scripts/build-teavm-release.sh"'
                not in build_version_release,
        ),
        (
            "Release resume requires an exact validated client artifact",
            "GAIUS_RESUME_CLIENT_SHA256" in build_release
            and "actual_client_sha256" in build_release
            and "gaius-java-finite-long-cast" in build_release
            and "target-attestation" in build_release
            and 'node --check "$client_js"' in build_release
            and 'gzip -t "$vanilla_asset_pack"' in build_release
            and "verify_identity client" in build_release
            and "verify_identity vanilla-assets" in build_release
            and "verify_identity singleplayer-worker" in build_release
            and "verify_identity wasm-hotpath" in build_release
            and "write_client_release_profile" in build_release
            and "verify_client_release_profile" in build_release
            and "verify_worker_release_profile" in build_release
            and "teavm-compiler-profile.py" in build_release
            and 'release_lock="$build_root/.release-build.lock"' in build_release
            and 'release_backup_root="${dist}.release-backup-$release_lock_owner"'
                in build_release
            and 'release_completed=true' in build_release
            and 'Could not restore the previous release dist' in build_release
            and "read_teavm_configuration" in teavm_compiler_profile
            and "validate_release_configuration" in teavm_compiler_profile
            and 'KIND = "gaius-teavm-compiler-profile"' in teavm_compiler_profile
            and "--artifact-input" in teavm_compiler_profile
            and "TeaVM staged compiler profile regression passed"
                in teavm_compiler_profile_test
            and "--artifact-input" in build_server_worker
            and 'GAIUS_SERVER_MINIFYING:-true' in build_server_worker
            and 'GAIUS_SERVER_SHORT_FILE_NAMES:-true' in build_server_worker
            and 'GAIUS_SERVER_ASSERTIONS_REMOVED:-true' in build_server_worker,
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
            and "gaius-integrated-server-input-coroutine" in postprocess_teavm_js
            and "pumpIntegratedServerNetworkInput" in postprocess_teavm_js
            and "$rt_startThread" in postprocess_teavm_js
            and "integratedServerPumpCoalesced" in postprocess_teavm_js
            and "$gaiusIntegratedServerPumpMaxRetries = 4"
                in postprocess_teavm_js
            and "$gaiusIntegratedServerPumpDispatchScheduled"
                in postprocess_teavm_js
            and "integratedServerPumpRetrySchedules" in postprocess_teavm_js
            and "integratedServerPumpRetryExhaustions" in postprocess_teavm_js
            and "transient starter failure lost the input signal"
                in integrated_server_pump_shim_smoke
            and "queued dispatch must not start a competing coroutine"
                in integrated_server_pump_shim_smoke
            and "terminal starter failures must not spin forever"
                in integrated_server_pump_shim_smoke
            and 'find_anchored(' in postprocess_teavm_js
            and '"Number.isFinite"' in postprocess_teavm_js,
        ),
        (
            "TeaVM JS postprocess preserves the release file on write failure",
            "import os" in postprocess_teavm_js
            and "import tempfile" in postprocess_teavm_js
            and "temporary.flush()" in postprocess_teavm_js
            and "os.fsync(temporary.fileno())" in postprocess_teavm_js
            and "os.replace(temporary_name, target)" in postprocess_teavm_js
            and "os.unlink(temporary_name)" in postprocess_teavm_js
            and "write_text_atomically(target, patched)" in postprocess_teavm_js
            and "test_replace_failure_preserves_original_and_cleans_temp"
                in postprocess_teavm_js_test
            and "test_fsync_failure_preserves_original_and_cleans_temp"
                in postprocess_teavm_js_test
            and "TEAVM_LONG_HELPER" in postprocess_teavm_js_test,
        ),
        (
            "Portable HTML publication preserves the previous release on write failure",
            "import tempfile" in build_portable_html
            and "temporary.flush()" in build_portable_html
            and "os.fsync(temporary.fileno())" in build_portable_html
            and "os.replace(temporary_name, target)" in build_portable_html
            and "publish_portable_pair(output, portable, manifest_path, manifest_text)"
                in build_portable_html
            and "os.replace(html_temporary, output)" in build_portable_html
            and "os.replace(manifest_temporary, manifest_path)" in build_portable_html
            and "test_atomic_write_replaces_complete_file" in build_portable_html_test
            and "test_replace_failure_preserves_original_and_cleans_temp"
                in build_portable_html_test,
        ),
        (
            "Build identity binds active profile, sources, overlays, protocol, and artifact bytes",
            "gaius-runtime-inputs-v1" in build_identity_helper
            and "gaius-browser-protocol-v1" in build_identity_helper
            and "gaius-active-overlay-inputs-v1" in build_identity_helper
            and "port/src/main" in build_identity_helper
            and "port/overrides" in build_identity_helper
            and "port/tools/src/main" in build_identity_helper
            and "compatibilitySha256" in build_identity_helper
            and "identitySha256" in build_identity_helper
            and "teavm-publication-gate.sh" in build_identity_helper
            and "gaius_build_identity.py" in build_teavm
            and "gaius_build_identity.py" in build_server_worker,
        ),
        (
            "TeaVM publication gate requires completed analysis before publishing identity",
            "teavm-publication-gate.sh" in build_teavm
            and "teavm-publication-gate.sh" in build_server_worker
            and "gaius_teavm_publish_allowed" in teavm_publication_gate
            and "gaius_teavm_publish_bundle" in teavm_publication_gate
            and "GAIUS_TEA_PUBLISH_FAIL_AFTER=1" in teavm_publication_gate_test
            and "old-bundle-artifact" in teavm_publication_gate_test
            and "Output file built with errors" in teavm_publication_gate
            and "[INFO] BUILD SUCCESS" in teavm_publication_gate
            and "analysis_status" in build_teavm
            and "analysis_status" in build_server_worker
            and "gaius_teavm_remove_stale_incomplete_reports" in build_teavm
            and "gaius_teavm_remove_stale_incomplete_reports" in build_server_worker
            and '"${json_path%.json}.incomplete.json"' in teavm_publication_gate
            and '"${markdown_path%.md}.incomplete.md"' in teavm_publication_gate
            and "TeaVM publication gate regression passed" in teavm_publication_gate_test
            and "old-identity" in teavm_publication_gate_test
        ),
        (
            "Profile builds use the tracked launcher template instead of shared dist output",
            "port/web/launcher/index.template.html" in build_teavm
            and "port/web/dist/index.html" not in build_teavm
            and 'if [[ -f "$target_directory/index.html"' not in build_teavm
            and "Index template is an unresolved Git LFS pointer" in build_teavm
            and "function decodeGaiusVanillaAssets(source)" in index_template
            and 'window.__gaiusProfileId = "template";' in index_template
            and "26.2" not in index_template
            and "1.21.11" not in index_template
            and "gaius.fs.v2:" not in index_template
            and 'const vanillaAssetsToken = "dev";' in index_template
            and 'const fallbackBuildToken = "dev";' in index_template
            and "launcher template regression passed" in index_template_test
            and '"26.2.json"' in index_template_test
            and '"1.21.11.json"' in index_template_test
        ),
        (
            "Portable artifact identity fixtures cover profile and gzip skew",
            all(
                name in portable_artifact_identity_test
                for name in (
                    "test_correct_26_2_publishes_and_embeds_identity",
                    "test_old_gzip_with_new_js_is_rejected",
                    "test_wrong_profile_is_rejected",
                    "test_truncated_gzip_is_rejected",
                    "test_build_failure_does_not_overwrite_target",
                    "test_manifest_replace_failure_rolls_back_html_and_commit_marker",
                    "test_manifest_is_replaced_after_html_as_commit_marker",
                    "test_missing_worker_identity_is_rejected",
                    "test_source_change_rejects_all_previously_built_components",
                    "test_teavm_publication_gate_change_rejects_previous_artifacts",
                    "test_launcher_template_change_rejects_previous_artifacts",
                    "test_1_21_11_legacy_profile_remains_compatible",
                    "test_quick_check_rejects_mixed_versions_independently",
                )
            ),
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
            "Generated server Worker pumps input from a TeaVM coroutine context",
            file_contains(
                SERVER_WORKER_JS,
                "/*gaius-integrated-server-input-coroutine*/",
            )
            and file_contains(
                SERVER_WORKER_JS,
                ".__gaiusStartIntegratedServerPump = () =>",
            )
            and file_contains(
                SERVER_WORKER_JS,
                ".pumpIntegratedServerNetworkInput()",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "$gaiusIntegratedServerPumpMaxRetries = 4",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "$gaiusIntegratedServerPumpDispatchScheduled",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "integratedServerPumpRetrySchedules",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "integratedServerPumpRetryExhaustions",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "integratedServerTaskDeferredRetries",
            )
            and file_contains(
                SERVER_WORKER_JS,
                "integratedServerTaskRetryExhaustions",
            )
            and file_matches(
                SERVER_WORKER_JS,
                rb"/\*gaius-integrated-server-input-coroutine\*/.{0,8192}"
                rb"(?:\.\$rt_startThread|A\.[A-Za-z_$][A-Za-z0-9_$]*)\(",
            )
            and not file_contains(
                SERVER_WORKER_JS,
                "__gaiusDebugIntegratedServerThread",
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
            and "compress-brotli.mjs" in compress_dist
            and "createBrotliCompress" in compress_brotli
            and "BROTLI_PARAM_QUALITY" in compress_brotli
            and "rename(temporary, output)" in compress_brotli
            and "*.js" in compress_dist
            and "*.html" in compress_dist
            and "GAIUS_COMPRESS_EXCLUDE" in compress_dist,
        ),
        (
            "Local dist server serves precompressed classes.js when available",
            "Content-Encoding" in serve_dist
            and "Accept-Encoding" in serve_dist
            and '("br", ".br")' in serve_dist
            and '("gzip", ".gz")' in serve_dist
            and "os.path.getmtime(compressed_path) + 1.0 < os.path.getmtime(path)" in serve_dist
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
            "Minecraft 26.2 text lightmap diagnostic matches sample_lightmap shaders",
            "migrate_text_shader_diagnostics" in postprocess_index_html
            and "sample_lightmap(Sampler2, UV2)" in postprocess_index_html
            and index_html.count("sample_lightmap(Sampler2, UV2)") >= 2
            and '"texttrace"' in index_html
            and "__gaiusTextShaderTelemetry" in index_html
            and "telemetry.samples.length >= 64" in index_html,
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
            "Browser boot prevents competing full Gaius runtimes across tabs",
            "async function acquireGaiusRuntimeLease()" in index_html
            and 'navigator.locks.request(lockName, {mode: "exclusive", ifAvailable: true}'
                in index_html
            and 'const leaseKey = "gaius.runtimeLease.v1"' in index_html
            and "const heartbeat = setInterval(writeLease, 3000)" in index_html
            and 'urlParams.get("allowMultipleTabs") === "1"' in index_html
            and "bootTimings.runtimeLeaseReady" in index_html
            and "async function acquireGaiusRuntimeLease()" in postprocess_index_html,
        ),
        (
            "Browser boot uses Gaius branding, player-name gate, and English UI",
            "boot-progress-bar" in index_html
            and "Gaius boot screen" in index_html
            and 'id="boot-screen"' in index_html
            and 'id="boot-brand"' in index_html
            and "GAIUS<span>CLIENT</span>" in index_html
            and 'id="profile-gate"' in index_html
            and 'id="profile-name"' in index_html
            and 'id="profile-submit"' in index_html
            and 'id="profile-switch"' in index_html
            and "Change player name" in index_html
            and "function requestGaiusPlayerName(initialName)" in index_html
            and "function changeGaiusPlayerName()" in index_html
            and "function updateGaiusProfileSwitch()" in index_html
            and 'sessionStorage.removeItem("gaius.session")' in index_html
            and 'next.searchParams.delete(key)' in index_html
            and 'localStorage.setItem("gaius.playerName", username)' in index_html
            and "Use 1-16 letters, numbers, or underscores." in index_html
            and "not affiliated with Mojang Studios or Microsoft" in index_html
            and "MOJANG<span>STUDIOS</span>" not in index_html
            and "BrowserPlayer" not in index_html
            and '<html lang="en">' in index_html
            and f'<title>Gaius Client {ACTIVE_PROFILE_ID}</title>' in index_html
            and re.search(r"[\u4e00-\u9fff]", index_html) is None
            and 'const showPerfHud = urlParams.get("hud") === "1"' in index_html
            and 'const showPerfHud = urlParams.get("hud") !== "0"' not in index_html
            and "__gaiusSetBootProgress" in index_html
            and "function showBootOverlay(label)" in index_html
            and "window.__gaiusShowBootOverlay = showBootOverlay" in index_html
            and "function hideBootOverlay()" in index_html
            and "const keepGaiusBootVisible = [" in index_html
            and "state && (state.overlay || state.screen)" in index_html
            and 'overlay === "LoadingOverlay"' in index_html
            and 'const showLauncherDetails = urlParams.get("debug") === "1" || showPerfHud;'
                in index_html
            and 'statusBox.hidden = state !== "error" && !showLauncherDetails;'
                in index_html
            and '<pre id="status" data-state="running" hidden>' in index_html
            and 'statusBox.dataset.state !== "running" || statusBox.hidden'
                not in index_html
            and '"GenericMessageScreen"' in index_html
            and '"ReceivingLevelScreen"' in index_html
            and "showBootOverlay(loadingLabel)" in index_html
            and 'setBootProgress(Math.max(bootProgressValue, 35), loadingLabel)' in index_html
            and 'setBootProgress(100, "Client screen ready: " + screen)' in index_html
            and 'setBootProgress(100, "Minecraft screen ready: " + screen)' not in index_html
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
            and "Number.isFinite(fps.rafFps) && fps.rafFps > 0 ? fps.rafFps : 0" in index_html
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
            and "function gaiusFpsSample()" not in index_html
            and "function gaiusFpsTick" in index_html
            and "requestAnimationFrame(gaiusFpsTick)" in index_html
            and "function gaiusFpsTick" in postprocess_index_html
            and "new Float32Array(4096)" in index_html
            and "fps.rafFrameWriteIndex" in index_html
            and "fps.rafFrameCount" in index_html
            and "const slowestCount = Math.max(1, Math.ceil(ordered.length * 0.01))" in index_html
            and "slowestCount * 1000 / slowestTotalMs" in index_html
            and "samples.splice(0" not in index_html
            and "__gaiusWasmHotpath" in index_html
            and "gaius-hotpath.wasm" in index_html
            and "WebAssembly.instantiate" in index_html
            and "shiftIndices" in index_html
            and "repackInterleaved" in index_html
            and "unpackBitStorage" in index_html
            and "bitStorageWasmUnpack" in index_html
            and "if (!inWorld) return" not in index_html
            and "Startup has made no visible progress for 30 seconds" in index_html
            and '"--disableMultiplayer"' not in index_html
            and '"--disableChat"' not in index_html,
        ),
    ]
    for name, ok in checks:
        print_check(name, ok)


def check_overlay_bytecode() -> None:
    section("Overlay bytecode checks")
    try:
        # Keep the bytecode checks on the same profile-scoped overlay root used
        # by the module-level path resolver.  A profile-isolated release must
        # never fall back to the shared 26.2 overlay tree here.
        resolved = resolve_overlay_paths(overlays_root=OVERLAYS)
    except OverlayResolutionError as exc:
        print(f"FAIL overlay path resolver: {exc}")
        FAILURES.append("Overlay path resolver")
        return

    profile = resolved["profile"]
    version = resolved["version"]
    client_distribution = resolved["client_distribution"]
    is_current_named = client_distribution == "named"
    library_paths = resolved["libraries"]
    if not isinstance(profile, dict) or not isinstance(library_paths, dict):
        print("FAIL overlay path resolver returned an invalid contract")
        FAILURES.append("Overlay path resolver contract")
        return
    print(f"active overlay profile: {version} ({client_distribution})")
    for label, path in resolved["expected_paths"].items():
        print(f"expected {label} overlay: {rel(path)}")
    missing = missing_overlay_paths(resolved)
    if missing:
        for label, path in missing:
            print_check(f"Current {label} overlay exists at {rel(path)}", False)
            print(f"  expected path: {path}")
        return

    try:
        javap = resolve_javap()
    except JavapPrerequisiteError as exc:
        # Do not let every bytecode assertion report a secondary failure when
        # the shared prerequisite is absent.  The check still fails closed,
        # but emits one actionable prerequisite diagnostic.
        print(f"FAIL prerequisite: {exc}")
        FAILURES.append("JDK javap prerequisite")
        return
    print(f"javap: {javap}")

    client_cp = resolved["client"]
    netty_common_cp = OVERLAYS / "library-patches" / "netty-common"
    netty_cp = OVERLAYS / "library-patches" / "netty-transport"
    netty_transport_overlay_cp = library_paths["netty_transport"]
    classlib_cp = OVERLAYS / "classlib-patches"
    classlib_classes_cp = OVERLAYS / "classlib-classes"
    lwjgl_cp = OVERLAYS / "library-classes" / "lwjgl"
    lwjgl_opengl_cp = OVERLAYS / "library-classes" / "lwjgl-opengl"
    lwjgl_opengl_patch_cp = OVERLAYS / "library-patches" / "lwjgl-opengl"
    lwjgl_opengl_overlay_cp = library_paths["lwjgl_opengl"]
    lwjgl_openal_cp = library_paths["lwjgl_openal"]
    lwjgl_openal_classes_cp = OVERLAYS / "library-classes" / "lwjgl-openal"
    lwjgl_glfw_cp = library_paths["lwjgl_glfw"]
    joml_cp = library_paths["joml"]
    authlib_cp = library_paths["authlib"]
    patchy_cp = library_paths["patchy"]
    server_worker_classes_cp = TARGET / "server-worker" / "maven" / "classes"
    client_classes_cp = TARGET / "maven" / "classes"
    browser_opengl_class = lwjgl_opengl_cp / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.class"
    browser_openal_class = lwjgl_openal_classes_cp / "org" / "lwjgl" / "openal" / "BrowserOpenAL.class"
    browser_targeting_class = run_javap(
        client_classes_cp,
        "dev.gaius.browser.BrowserTargeting",
    )

    packet_encoder = run_javap(client_cp, "net.minecraft.network.PacketEncoder")
    packet_bundle_unpacker = run_javap(client_cp, "net.minecraft.network.PacketBundleUnpacker")
    packet_processor = run_javap(client_cp, "net.minecraft.network.PacketProcessor")
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
    authlib_session = run_javap(
        authlib_cp,
        "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
    )
    authlib_profile_texture = run_javap(
        authlib_cp,
        "com.mojang.authlib.minecraft.MinecraftProfileTexture",
    )
    patchy_block_list = run_javap(
        patchy_cp,
        "com.mojang.patchy.MojangBlockListSupplier",
    )
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
    pause_screen = run_javap(client_cp, "net.minecraft.client.gui.screens.PauseScreen")
    abstract_button = run_javap(client_cp, "net.minecraft.client.gui.components.AbstractButton")
    gui_graphics = run_javap(
        client_cp,
        "net.minecraft.client.gui.GuiGraphicsExtractor"
        if is_current_named
        else "net.minecraft.client.gui.GuiGraphics",
    )
    gui_render_state = run_javap(
        client_cp,
        "net.minecraft.client.renderer.state.gui.GuiRenderState"
        if is_current_named
        else "net.minecraft.client.gui.render.state.GuiRenderState",
    )
    gui_renderer = run_javap(client_cp, "net.minecraft.client.gui.render.GuiRenderer")
    browser_gui_item_cache = run_javap(client_cp, "dev.gaius.browser.BrowserGuiItemCache")
    browser_tracking_item_stack_render_state = run_javap(
        client_cp,
        "dev.gaius.browser.BrowserGuiItemCache$BrowserTrackingItemStackRenderState",
    )
    client_level = run_javap(client_cp, "net.minecraft.client.multiplayer.ClientLevel")
    simple_reload_instance = run_javap(
        client_cp,
        "net.minecraft.server.packs.resources.SimpleReloadInstance",
    )
    model_manager = run_javap(client_cp, "net.minecraft.client.resources.model.ModelManager")
    font_manager = run_javap(client_cp, "net.minecraft.client.gui.font.FontManager")
    loading_overlay = run_javap(
        client_cp,
        "net.minecraft.client.gui.screens.LoadingOverlay",
    )
    downloaded_pack_response = run_javap(
        client_cp,
        "net.minecraft.client.resources.server.DownloadedPackSource$6",
    )
    downloaded_pack_source = run_javap(
        client_cp,
        "net.minecraft.client.resources.server.DownloadedPackSource",
    )
    client_common_listener = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
    )
    connect_screen = run_javap(
        client_cp,
        "net.minecraft.client.gui.screens.ConnectScreen",
    )
    browser_multiplayer_recovery_class = run_javap(
        client_classes_cp,
        "dev.gaius.browser.BrowserMultiplayerRecovery",
    )
    browser_server_pack_reuse_class = run_javap(
        client_classes_cp,
        "dev.gaius.browser.BrowserServerPackReuse",
    )
    unihex_definition = run_javap(
        client_cp,
        "net.minecraft.client.gui.font.providers.UnihexProvider$Definition",
    )
    browser_unihex_loader = run_javap(
        client_classes_cp,
        "net.minecraft.client.gui.font.providers.BrowserUnihexLoader",
    )
    multiplayer_game_mode = run_javap(
        client_cp,
        "net.minecraft.client.multiplayer.MultiPlayerGameMode",
    )
    game_renderer = run_javap(client_cp, "net.minecraft.client.renderer.GameRenderer")
    level_renderer = run_javap(client_cp, "net.minecraft.client.renderer.LevelRenderer")
    level_extractor = run_javap(
        client_cp,
        "net.minecraft.client.renderer.extract.LevelExtractor",
    )
    entity_render_dispatcher = run_javap(
        client_cp,
        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
    )
    entity_render_should_render = method_section(
        entity_render_dispatcher,
        "public <E extends net.minecraft.world.entity.Entity> boolean shouldRender(E, "
        "net.minecraft.client.renderer.culling.Frustum, double, double, double);",
    )
    browser_chunk_section_layers = run_javap(
        client_cp, "dev.gaius.browser.BrowserChunkSectionLayers"
    )
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
    render_section = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
    )
    section_compiler = run_javap(
        client_cp,
        "net.minecraft.client.renderer.chunk.SectionCompiler",
    )
    minecraft_server = run_javap(client_cp, "net.minecraft.server.MinecraftServer")
    blockable_event_loop = run_javap(
        client_cp,
        "net.minecraft.util.thread.BlockableEventLoop",
    )
    chunk_map = run_javap(client_cp, "net.minecraft.server.level.ChunkMap")
    server_chunk_cache = run_javap(
        client_cp,
        "net.minecraft.server.level.ServerChunkCache",
    )
    player_spawn_finder = run_javap(
        client_cp,
        "net.minecraft.server.level.PlayerSpawnFinder",
    )
    prepare_spawn_task = run_javap(
        client_cp,
        "net.minecraft.server.network.config.PrepareSpawnTask$Preparing",
    )
    prepare_spawn_ready = run_javap(
        client_cp,
        "net.minecraft.server.network.config.PrepareSpawnTask$Ready",
    )
    structure_template_manager = run_javap(
        client_cp,
        (
            "net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource"
            if is_current_named
            else "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager"
        ),
    )
    server_common_packet_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerCommonPacketListenerImpl",
    )
    server_game_packet_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerGamePacketListenerImpl",
    )
    player_chunk_sender = run_javap(
        client_cp,
        "net.minecraft.server.network.PlayerChunkSender",
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
    browser_packet_scheduler_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserPacketScheduler",
    )
    browser_startup_scheduler_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserStartupScheduler",
    )
    browser_future_pump_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserFuturePump",
    )
    browser_gzip_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserGzip",
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
    browser_lazy_data_fixer_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserLazyDataFixer",
    )
    surface_system = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceSystem",
    )
    surface_rules_context = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceRules$Context",
    )
    surface_rules_lazy = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyCondition",
    )
    surface_rules_lazy_xz = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyXZCondition",
    )
    surface_rules_lazy_y = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyYCondition",
    )
    surface_rules_sequence = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule",
    )
    density_clamp = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.DensityFunctions$Clamp",
    )
    density_mul_or_add = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd",
    )
    density_mapped = run_javap(
        client_cp,
        "net.minecraft.world.level.levelgen.DensityFunctions$Mapped",
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
    region_file_storage = run_javap(
        client_cp,
        "net.minecraft.world.level.chunk.storage.RegionFileStorage",
    )
    chunk_task_dispatcher = run_javap(
        client_cp,
        "net.minecraft.server.level.ChunkTaskDispatcher",
    )
    chunk_task_priority_queue = run_javap(
        client_cp,
        "net.minecraft.server.level.ChunkTaskPriorityQueue",
    )
    browser_chunk_task_priority_class = run_javap(
        server_worker_classes_cp,
        "dev.gaius.browser.BrowserChunkTaskPriority",
    )
    persistent_entity_manager = run_javap(
        client_cp,
        "net.minecraft.world.level.entity.PersistentEntitySectionManager",
    )
    gl_device = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlDevice")
    gl_heuristics = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlHeuristics")
    gl_command_encoder = run_javap(
        client_cp, "com.mojang.blaze3d.opengl.GlCommandEncoder"
    )
    gl_render_pipeline = run_javap(
        client_cp, "com.mojang.blaze3d.opengl.GlRenderPipeline"
    )
    audio_library = run_javap(client_cp, "com.mojang.blaze3d.audio.Library")
    audio_listener = run_javap(client_cp, "com.mojang.blaze3d.audio.Listener")
    sound_engine = run_javap(client_cp, "net.minecraft.client.sounds.SoundEngine")
    vertex_array_cache_emulated = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$Emulated")
    vertex_array_cache_separate = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$Separate")
    vertex_array_cache = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache")
    vertex_array_cache_browser = run_javap(
        client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$BrowserVaoCache"
    )
    vertex_array_cache_key = run_javap(client_cp, "com.mojang.blaze3d.opengl.VertexArrayCache$VertexArrayKey")
    vertex_array_cache_source_path = (
        VERTEX_ARRAY_CACHE_262 if version == "26.2" else VERTEX_ARRAY_CACHE
    )
    vertex_array_cache_source = (
        vertex_array_cache_source_path.read_text(errors="replace")
        if vertex_array_cache_source_path.exists()
        else ""
    )
    vanilla_pack_builder = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResourcesBuilder")
    indexed_asset_source = run_javap(client_cp, "net.minecraft.client.resources.IndexedAssetSource")
    vanilla_pack_resources = run_javap(client_cp, "net.minecraft.server.packs.VanillaPackResources")
    vanilla_listed_resources = method_section(
        vanilla_pack_resources,
        "private net.minecraft.server.packs.VanillaPackResources$ListedResource[] listedResources(",
    )
    vanilla_resource_lower_bound = method_section(
        vanilla_pack_resources,
        "private static int lowerBound(java.lang.String[], java.lang.String);",
    )
    region_file_version = run_javap(client_cp, "net.minecraft.world.level.chunk.storage.RegionFileVersion")
    local_time = run_javap(client_cp, "net.minecraft.client.renderer.item.properties.select.LocalTime")
    render_system = run_javap(client_cp, "com.mojang.blaze3d.systems.RenderSystem")
    framerate_limiter = run_javap(client_cp, "net.minecraft.client.FramerateLimiter")
    minecraft = run_javap(client_cp, "net.minecraft.client.Minecraft")
    minecraft_main = run_javap(client_cp, "net.minecraft.client.main.Main")
    atlas_entry = run_javap(
        client_cp,
        (
            "net.minecraft.client.resources.model.sprite.AtlasManager$AtlasEntry"
            if is_current_named
            else "net.minecraft.client.resources.model.AtlasManager$AtlasEntry"
        ),
    )
    client_options = run_javap(client_cp, "net.minecraft.client.Options")
    server_connection_listener = run_javap(
        client_cp,
        "net.minecraft.server.network.ServerConnectionListener",
    )
    server_text_filter = run_javap(client_cp, "net.minecraft.server.network.ServerTextFilter")
    server_main = run_javap(client_cp, "net.minecraft.server.Main")
    world_loader = run_javap(client_cp, "net.minecraft.server.WorldLoader")
    minecraft_util = run_javap(client_cp, "net.minecraft.util.Util")
    simple_json_resource_reload_listener = run_javap(
        client_cp,
        "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener",
    )
    blocks = run_javap(client_cp, "net.minecraft.world.level.block.Blocks")
    block_state_base = run_javap(
        client_cp,
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
    )
    mapped_registry = run_javap(client_cp, "net.minecraft.core.MappedRegistry")
    built_in_registries = run_javap(
        client_cp,
        "net.minecraft.core.registries.BuiltInRegistries",
    )
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
    framerate_limit_fps = method_section(
        framerate_limiter,
        "public static void limitDisplayFPS(int);",
    )
    framerate_compensate_frame_time = method_section(
        framerate_limiter,
        "private static long browserCompensateFrameTime(long, int);",
    )
    minecraft_run_tick = method_section(minecraft, "private void runTick(boolean);")
    minecraft_get_overlay = method_section_any(
        minecraft,
        "public net.minecraft.client.gui.screens.Overlay getOverlay();",
        "public net.minecraft.client.gui.screens.Overlay gaius$getOverlay();",
    )
    loading_overlay_tick = method_section(loading_overlay, "public void tick();")
    minecraft_constructor = method_section(
        minecraft,
        "public net.minecraft.client.Minecraft(net.minecraft.client.main.GameConfig);",
    )
    minecraft_main_entry = method_section(
        minecraft_main,
        "public static void main(java.lang.String[]);",
    )
    minecraft_render_frame = (
        required_method_section(minecraft, "public void renderFrame(boolean);")
        if is_current_named
        else None
    )
    current_game_renderer_extract = (
        required_method_section(
            game_renderer,
            "public void extract(net.minecraft.client.DeltaTracker, boolean);",
        )
        if is_current_named
        else None
    )
    legacy_game_render_level = (
        required_method_section(
            game_renderer,
            "public void renderLevel(net.minecraft.client.DeltaTracker);",
        )
        if not is_current_named
        else None
    )
    current_section_task_queue = (
        run_javap(
            client_cp,
            "net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue",
        )
        if is_current_named
        else None
    )
    legacy_compile_task_queue = (
        run_javap(
            client_cp,
            "net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue",
        )
        if not is_current_named
        else None
    )
    atlas_entry_schedule_load = method_section(
        atlas_entry,
        "java.util.concurrent.CompletableFuture<net.minecraft.client.renderer.texture.SpriteLoader$Preparations> scheduleLoad(net.minecraft.server.packs.resources.ResourceManager, java.util.concurrent.Executor, int);",
    )
    downloaded_pack_response_constructor = method_section(
        downloaded_pack_response,
        "net.minecraft.client.resources.server.DownloadedPackSource$6();",
    )
    downloaded_pack_report_update = method_section(
        downloaded_pack_response,
        "public void reportUpdate(java.util.UUID, net.minecraft.client.resources.server.PackLoadFeedback$Update);",
    )
    downloaded_pack_report_final = method_section(
        downloaded_pack_response,
        "public void reportFinalResult(java.util.UUID, net.minecraft.client.resources.server.PackLoadFeedback$FinalResult);",
    )
    downloaded_pack_cleanup = method_section(
        downloaded_pack_source,
        "public void cleanupAfterDisconnect();",
    )
    client_common_on_disconnect = method_section(
        client_common_listener,
        "public void onDisconnect(net.minecraft.network.DisconnectionDetails);",
    )
    connect_screen_start = method_section(
        connect_screen,
        "public static void startConnecting(net.minecraft.client.gui.screens.Screen, net.minecraft.client.Minecraft, net.minecraft.client.multiplayer.resolver.ServerAddress, net.minecraft.client.multiplayer.ServerData, boolean, net.minecraft.client.multiplayer.TransferState);",
    )
    browser_multiplayer_recovery_method = method_section(
        browser_multiplayer_recovery_class,
        "public static boolean maybeReconnect(net.minecraft.client.Minecraft, net.minecraft.client.multiplayer.ServerData, java.lang.String);",
    )
    minecraft_process_packets_and_tick = method_section(
        minecraft_server,
        "public void processPacketsAndTick(boolean);",
    )
    if not minecraft_process_packets_and_tick and is_current_named:
        minecraft_process_packets_and_tick = method_section(
            minecraft_server,
            "protected void processPacketsAndTick(boolean);",
        )
    server_game_chunk_batch = method_section(
        server_game_packet_listener,
        "public void handleChunkBatchReceived(net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket);",
    )
    player_send_next_chunks = method_section(
        player_chunk_sender,
        "public void sendNextChunks(net.minecraft.server.level.ServerPlayer);",
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
    minecraft_disconnect_from_world = method_section(
        minecraft,
        "public void disconnectFromWorld(net.minecraft.network.chat.Component);",
    )
    pause_screen_create_menu = method_section(
        pause_screen,
        "private void createPauseMenu();",
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
    dedicated_server_init = method_section_any(
        dedicated_server,
        "public boolean initServer() throws java.io.IOException;",
        "protected boolean initServer() throws java.io.IOException;",
    )
    dedicated_server_stop = method_section_any(
        dedicated_server,
        "public void stopServer();",
        "protected void stopServer();",
    )
    server_login_hello = method_section(
        server_login_packet_listener,
        "public void handleHello(net.minecraft.network.protocol.login.ServerboundHelloPacket);",
    )
    mouse_setup_move = method_section_any(
        mouse_handler,
        "private void lambda$setup$3(long, double, double);",
        "private void lambda$setup$0(long, double, double);",
        "private void lambda$setup$1(long, double, double);",
    )
    mouse_setup_button = method_section_any(
        mouse_handler,
        "private void lambda$setup$5(long, int, int, int);",
        "private void lambda$setup$2(long, int, int, int);",
    )
    mouse_setup_scroll = method_section_any(
        mouse_handler,
        "private void lambda$setup$7(long, double, double);",
        "private void lambda$setup$4(long, double, double);",
        "private void lambda$setup$5(long, double, double);",
    )
    keyboard_setup_key = method_section_any(
        keyboard_handler,
        "private void lambda$setup$6(long, int, int, int, int);",
        "private void lambda$setup$0(long, int, int, int, int);",
    )
    keyboard_setup_char = method_section_any(
        keyboard_handler,
        "private void lambda$setup$8(long, int, int);",
        "private void lambda$setup$2(long, int);",
    )
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
    integrated_tick = method_section_any(
        integrated_server,
        "public void tickServer(java.util.function.BooleanSupplier);",
        "protected void tickServer(java.util.function.BooleanSupplier);",
    )
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
    gui_render_item = method_section_any(
        gui_graphics,
        "private void renderItem(net.minecraft.world.entity.LivingEntity, net.minecraft.world.level.Level, net.minecraft.world.item.ItemStack, int, int, int);",
        "private void item(net.minecraft.world.entity.LivingEntity, net.minecraft.world.level.Level, net.minecraft.world.item.ItemStack, int, int, int);",
    )
    gui_render_state_reset = method_section(
        gui_render_state,
        "public void reset();",
    )
    gui_renderer_item_atlas_lambda = method_section_any(
        gui_renderer,
        "private void lambda$prepareItemElements$3(org.apache.commons.lang3.mutable.MutableBoolean, int, int, org.apache.commons.lang3.mutable.MutableBoolean, com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.gui.render.state.GuiItemRenderState);",
            "private void lambda$prepareItemElements$0(org.apache.commons.lang3.mutable.MutableBoolean, net.minecraft.client.gui.render.GuiItemAtlas, net.minecraft.client.renderer.state.gui.GuiItemRenderState);",
    )
    gui_item_atlas = run_javap(client_cp, "net.minecraft.client.gui.render.GuiItemAtlas")
    gui_item_atlas_get_or_update = method_section(
        gui_item_atlas,
        "public net.minecraft.client.gui.render.GuiItemAtlas$SlotView getOrUpdate(net.minecraft.client.renderer.item.TrackingItemStackRenderState);",
    )
    gui_renderer_invalidate_item_atlas = method_section(
        gui_renderer,
        "private void invalidateItemAtlas();",
    )
    dynamic_uniforms_constructor = method_section(
        dynamic_uniforms,
        "public net.minecraft.client.renderer.DynamicUniforms();",
    )
    screen_render_panorama = method_section_any(
        screen,
        "protected void renderPanorama(net.minecraft.client.gui.GuiGraphics, float);",
        "protected void extractPanorama(net.minecraft.client.gui.GuiGraphicsExtractor, float);",
    )
    screen_render_menu_background = method_section_any(
        screen,
        "protected void renderMenuBackground(net.minecraft.client.gui.GuiGraphics, int, int, int, int);",
        "protected void extractMenuBackground(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, int, int);",
        "protected void extractMenuBackground(net.minecraft.client.gui.GuiGraphicsExtractor);",
    )
    level_loading_render_chunks = method_section_any(
        level_loading_screen,
        "public static void renderChunks(net.minecraft.client.gui.GuiGraphics, int, int, int, int, net.minecraft.server.level.progress.ChunkLoadStatusView);",
        "public static void extractChunksForRendering(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, int, int, net.minecraft.server.level.progress.ChunkLoadStatusView);",
    )
    title_realms_enabled = method_section(title_screen, "private boolean realmsNotificationsEnabled();")
    title_initializer = method_section(title_screen, "static {};")
    abstract_button_sprite = method_section_any(
        abstract_button,
        "protected final void renderDefaultSprite(net.minecraft.client.gui.GuiGraphics);",
        "protected final void extractDefaultSprite(net.minecraft.client.gui.GuiGraphicsExtractor);",
    )
    game_render_level = legacy_game_render_level
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
    level_compile_sections = method_section_any(
        level_renderer,
        "private void compileSections(net.minecraft.client.Camera);",
        "private void compileSections(net.minecraft.client.renderer.state.level.CameraRenderState);",
    )
    level_extract_block_destroy = method_section(
        level_extractor,
        "private void extractBlockDestroyAnimation(net.minecraft.client.Camera, net.minecraft.client.renderer.state.level.LevelRenderState);",
    )
    level_prepare_chunk_renders = method_section_any(
        level_renderer,
        "private net.minecraft.client.renderer.chunk.ChunkSectionsToRender prepareChunkRenders(org.joml.Matrix4fc, double, double, double);",
        "public net.minecraft.client.renderer.chunk.ChunkSectionsToRender prepareChunkRenders(org.joml.Matrix4fc);",
    )
    level_destroy_block_progress = method_section_any(
        level_renderer,
        "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);",
        "private void submitBlockDestroyAnimation(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.LevelRenderState);",
    )
    level_render_block_outline = method_section_any(
        level_renderer,
        "private void renderBlockOutline(net.minecraft.client.renderer.MultiBufferSource$BufferSource, com.mojang.blaze3d.vertex.PoseStack, boolean, net.minecraft.client.renderer.state.LevelRenderState);",
        "private void submitBlockOutline(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.LevelRenderState);",
    )
    level_extract_block_outline = method_section_any(
        level_renderer,
        "private void extractBlockOutline(net.minecraft.client.Camera, net.minecraft.client.renderer.state.LevelRenderState);",
        "private void extractBlockOutline(net.minecraft.client.renderer.state.level.LevelRenderState);",
    )
    section_uploads = method_section_any(
        section_render_dispatcher,
        "public void uploadAllPendingUploads();",
        "public void uploadTerrainBuffersToGpu();",
    )
    section_dispatcher_constructor = method_section(
        section_render_dispatcher,
        "public net.minecraft.client.renderer.chunk.SectionRenderDispatcher(",
    )
    section_dispatcher_run_task = method_section(
        section_render_dispatcher,
        "private void runTask();",
    )
    section_dispatcher_schedule = method_section(
        section_render_dispatcher,
        "private void schedule(net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask);",
    )
    compiled_section_upload_mesh = method_section_any(
        compiled_section_mesh,
        "public void uploadMeshLayer(net.minecraft.client.renderer.chunk.ChunkSectionLayer, com.mojang.blaze3d.vertex.MeshData, long);",
    )
    render_section_upload = method_section_any(
        render_section,
        "private boolean addSectionBuffersToUberBuffer(net.minecraft.client.renderer.chunk.ChunkSectionLayer, net.minecraft.client.renderer.chunk.CompiledSectionMesh, java.nio.ByteBuffer, java.nio.ByteBuffer);",
    )
    render_section_upload_mesh = method_section_any(
        render_section,
        "private void vertexBufferUploadCallback(net.minecraft.client.renderer.chunk.CompiledSectionMesh, net.minecraft.client.renderer.chunk.ChunkSectionLayer);",
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
    minecraft_poll_task = method_section_any(
        minecraft_server,
        "public boolean pollTask();",
        "protected boolean pollTask();",
    )
    chunk_map_set_view_distance = method_section(
        chunk_map,
        "protected void setServerViewDistance(int);",
    )
    chunk_map_get_player_view_distance = method_section(
        chunk_map,
        "int getPlayerViewDistance(net.minecraft.server.level.ServerPlayer);",
    )
    minecraft_initial_spawn = method_section(
        minecraft_server,
        "private static void setInitialSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.world.level.storage.ServerLevelData, boolean, boolean, net.minecraft.server.level.progress.LevelLoadListener);",
    )
    player_find_spawn = method_section(
        player_spawn_finder,
        "public static java.util.concurrent.CompletableFuture<net.minecraft.world.phys.Vec3> findSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos);",
    )
    player_fixup_loaded_spawn = method_section(
        player_spawn_finder,
        "public static net.minecraft.world.phys.Vec3 gaius$fixupLoadedSpawn(net.minecraft.server.level.ServerLevel, net.minecraft.world.phys.Vec3);",
    )
    prepare_spawn_tick = method_section(
        prepare_spawn_task,
        "public net.minecraft.server.network.config.PrepareSpawnTask$Ready tick();",
    )
    prepare_spawn_load_chunks = method_section(
        prepare_spawn_task,
        "private void lambda$tick$0(net.minecraft.world.level.ChunkPos);",
    )
    server_chunk_future_main_thread = method_section(
        server_chunk_cache,
        "public java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> getChunkFutureMainThread(int, int, net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    )
    prepare_spawn_keep_alive = method_section(
        prepare_spawn_ready,
        "public void keepAlive();",
    )
    prepare_spawn_player = method_section(
        prepare_spawn_ready,
        "public net.minecraft.server.level.ServerPlayer spawn(net.minecraft.network.Connection, net.minecraft.server.network.CommonListenerCookie);",
    )
    structure_template_read_stream = method_section(
        structure_template_manager,
        "private net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate readStructure(java.io.InputStream) throws java.io.IOException;",
    )
    if not structure_template_read_stream and is_current_named:
        structure_template_read_stream = method_section(
            structure_template_manager,
            "private static net.minecraft.nbt.CompoundTag readStructure(java.io.InputStream) throws java.io.IOException;",
        )
    server_common_is_singleplayer_owner = method_section(
        server_common_packet_listener,
        "protected boolean isSingleplayerOwner();",
    )
    worldgen_checkpoint = method_section(
        browser_worldgen_scheduler_class,
        "public static void checkpoint();",
    )
    worldgen_begin_server_tick = method_section(
        browser_worldgen_scheduler_class,
        "public static void beginServerWorkTurn();",
    )
    worldgen_begin_task = method_section(
        browser_worldgen_scheduler_class,
        "public static int beginTaskWork();",
    )
    worldgen_end_task = method_section(
        browser_worldgen_scheduler_class,
        "public static void endTaskWork(int);",
    )
    worldgen_pulse = method_section(
        browser_worldgen_scheduler_class,
        "public static void pulse();",
    )
    worldgen_request_yield = method_section(
        browser_worldgen_scheduler_class,
        "private static void requestYield(int, int);",
    )
    worldgen_yield_reentrant = method_section(
        browser_worldgen_scheduler_class,
        "private static void yieldReentrantContinuation();",
    )
    browser_pump_urgent_packets = method_section(
        browser_integrated_server_main_class,
        "public static void pumpUrgentPackets();",
    )
    browser_drain_urgent_packets = method_section(
        browser_integrated_server_main_class,
        "private static boolean drainUrgentPackets();",
    )
    browser_pump_pending_packets = method_section(
        browser_integrated_server_main_class,
        "public static void pumpUrgentPacketsIfPending();",
    )
    browser_stage_network_input = method_section(
        browser_integrated_server_main_class,
        "public static void pumpIntegratedServerNetworkInput();",
    )
    browser_signal_network_input = method_section(
        browser_integrated_server_main_class,
        "public static void signalIntegratedServerNetworkInput();",
    )
    browser_schedule_network_input = method_section(
        browser_integrated_server_main_class,
        "private static boolean scheduleNetworkInputTask(boolean, boolean);",
    )
    browser_run_scheduled_network_input = method_section(
        browser_integrated_server_main_class,
        "private static void runScheduledNetworkInput();",
    )
    blockable_event_loop_schedule = method_section(
        blockable_event_loop,
        "public void schedule(R);",
    )
    browser_gzip_read_nbt = method_section(
        browser_gzip_class,
        "public static net.minecraft.nbt.CompoundTag readCompressedNbt(java.io.InputStream) throws java.io.IOException;",
    )
    browser_ring_position = method_section_any(
        chunk_generator_structure_state,
        "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$5(int, int, net.minecraft.core.HolderSet, net.minecraft.util.RandomSource);",
        "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$0(int, int, net.minecraft.core.HolderSet, net.minecraft.util.RandomSource);",
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
    perlin_noise_get_value = method_section_any(
        perlin_noise,
        "public double getValue(double, double, double, double, double);",
        "public double getValue(double, double, double, double, double, boolean);",
        "public double gaius$getValue(double, double, double, double, double, boolean);",
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
    browser_acknowledge_chunk_batch = method_section(
        browser_integrated_server_main_class,
        "public static void acknowledgeChunkBatch();",
    )
    browser_record_chunk_batch = method_section(
        browser_integrated_server_main_class,
        "public static void recordChunkBatchSent(int);",
    )
    browser_active_view_acknowledged = method_section(
        browser_integrated_server_main_class,
        "private static boolean activeViewDistanceAcknowledged();",
    )
    browser_tick_distances = method_section(
        browser_integrated_server_main_class,
        "public static void tickIntegratedServerDistances();",
    )
    browser_advance_distances = method_section(
        browser_integrated_server_main_class,
        "private static void advanceConfiguredDistances();",
    )
    browser_adjust_destroy_ticks = method_section(
        browser_integrated_server_main_class,
        "public static int adjustDestroyTicks(int, long);",
    )
    browser_complete_destroy_progress = method_section(
        browser_integrated_server_main_class,
        "public static float completeLocalDestroyProgress(float);",
    )
    server_main_entry = method_section(
        server_main,
        "public static void main(java.lang.String[]);",
    )
    server_world_loader_lambda = method_section_any(
        server_main,
        "private static java.util.concurrent.CompletableFuture lambda$main$0(net.minecraft.server.WorldLoader$InitConfig, com.mojang.serialization.Dynamic, net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess, net.minecraft.server.dedicated.DedicatedServerSettings, joptsimple.OptionSet, joptsimple.OptionSpec, joptsimple.OptionSpec, java.util.concurrent.Executor);",
        "private static java.util.concurrent.CompletableFuture lambda$main$1(net.minecraft.server.WorldLoader$InitConfig, com.mojang.serialization.Dynamic, net.minecraft.server.dedicated.DedicatedServerSettings, joptsimple.OptionSet, joptsimple.OptionSpec, joptsimple.OptionSpec, java.util.concurrent.Executor);",
    )
    blocks_register = method_section(
        blocks,
        "private static net.minecraft.world.level.block.Block register(net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block>, java.util.function.Function<net.minecraft.world.level.block.state.BlockBehaviour$Properties, net.minecraft.world.level.block.Block>, net.minecraft.world.level.block.state.BlockBehaviour$Properties);",
    )
    block_state_init_cache = method_section(
        block_state_base,
        "public void initCache();",
    )
    mapped_registry_register = method_section(
        mapped_registry,
        "public net.minecraft.core.Holder$Reference<T> register(net.minecraft.resources.ResourceKey<T>, T, net.minecraft.core.RegistrationInfo);",
    )
    built_in_registry_create_contents_entry = method_section_any(
        built_in_registries,
        "private static void lambda$createContents$49(net.minecraft.resources.Identifier, java.util.function.Supplier);",
        "private static void lambda$createContents$0(net.minecraft.resources.Identifier, java.util.function.Supplier);",
    )
    simple_json_scan_directory = method_section(
        simple_json_resource_reload_listener,
        "public static <T> void scanDirectory(net.minecraft.server.packs.resources.ResourceManager, net.minecraft.resources.FileToIdConverter, com.mojang.serialization.DynamicOps<com.google.gson.JsonElement>, com.mojang.serialization.Codec<T>, java.util.Map<net.minecraft.resources.Identifier, T>);",
    )
    climate_rtree_search = method_section(
        climate_rtree_subtree,
        "protected net.minecraft.world.level.biome.Climate$RTree$Leaf<T> search(long[], net.minecraft.world.level.biome.Climate$RTree$Leaf<T>, net.minecraft.world.level.biome.Climate$DistanceMetric<T>);",
    )
    surface_build = method_section_any(
        surface_system,
        "public void buildSurface(net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.biome.BiomeManager, net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome>, boolean, net.minecraft.world.level.levelgen.WorldGenerationContext, net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.levelgen.NoiseChunk, net.minecraft.world.level.levelgen.SurfaceRules$RuleSource);",
        "public void buildSurface(net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.biome.BiomeManager, boolean, net.minecraft.world.level.levelgen.WorldGenerationContext, net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.levelgen.NoiseChunk, net.minecraft.world.level.levelgen.SurfaceRules$RuleSource, java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>);",
    )
    surface_context_constructor = method_section(
        surface_rules_context,
        "protected net.minecraft.world.level.levelgen.SurfaceRules$Context(",
    )
    if not surface_context_constructor and is_current_named:
        surface_context_constructor = method_section_by_fragment(
            surface_rules_context,
            "SurfaceRules$Context(",
        )
    surface_context_update_y = method_section_any(
        surface_rules_context,
        "protected void updateY(int, int, int, int, int, int);",
        "protected void updateY(int, int, int, int);",
    )
    surface_context_get_biome = method_section(
        surface_rules_context,
        "protected net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome();",
    )
    surface_context_update_xz = method_section(
        surface_rules_context,
        "protected void updateXZ(int, int);",
    )
    surface_lazy_test = method_section(surface_rules_lazy, "public boolean test();")
    surface_lazy_xz_counter = method_section(
        surface_rules_lazy_xz,
        "protected int browserContextLastUpdate();",
    )
    surface_lazy_y_counter = method_section(
        surface_rules_lazy_y,
        "protected int browserContextLastUpdate();",
    )
    surface_sequence_try_apply = method_section(
        surface_rules_sequence,
        "public net.minecraft.world.level.block.state.BlockState tryApply(int, int, int);",
    )
    density_transformer_sections = [
        (
            method_section(
                density_class,
                "public double compute(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext);",
            ),
            method_section(
                density_class,
                "public void fillArray(double[], net.minecraft.world.level.levelgen.DensityFunction$ContextProvider);",
            ),
        )
        for density_class in (density_clamp, density_mul_or_add, density_mapped)
    ]
    density_mul_or_add_hash = method_section(
        density_mul_or_add,
        "public final int hashCode();",
    )
    chunk_apply_biome_decoration = method_section(
        chunk_generator,
        "public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.chunk.ChunkAccess, net.minecraft.world.level.StructureManager);",
    )
    chunk_create_references = method_section(
        chunk_generator,
        "public void createReferences(net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.StructureManager, net.minecraft.world.level.chunk.ChunkAccess);",
    )
    chunk_create_structures_lambda_match = re.search(
        r"private void lambda\$createStructures\$\d+\([^\n]+\);",
        chunk_generator,
    )
    chunk_create_structures_lambda = (
        method_section(chunk_generator, chunk_create_structures_lambda_match.group(0))
        if chunk_create_structures_lambda_match
        else ""
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
    generation_wait_for_scheduled_layer = method_section(
        chunk_generation_task,
        "private java.util.concurrent.CompletableFuture<?> waitForScheduledLayer();",
    )
    generation_schedule_layer = method_section(
        chunk_generation_task,
        "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    )
    generation_can_load_without_generation = method_section(
        chunk_generation_task,
        "private boolean canLoadWithoutGeneration();",
    )
    region_get_file = method_section(
        region_file_storage,
        "private net.minecraft.world.level.chunk.storage.RegionFile getRegionFile(net.minecraft.world.level.ChunkPos) throws java.io.IOException;",
    )
    dispatcher_schedule_for_execution = method_section(
        chunk_task_dispatcher,
        "protected void scheduleForExecution(net.minecraft.server.level.ChunkTaskPriorityQueue$TasksForChunk);",
    )
    priority_queue_pop = method_section(
        chunk_task_priority_queue,
        "public net.minecraft.server.level.ChunkTaskPriorityQueue$TasksForChunk pop();",
    )
    chunk_map_update_player_pos = method_section(
        chunk_map,
        "private void updatePlayerPos(net.minecraft.server.level.ServerPlayer);",
    )
    entity_uuid_add = method_section_any(
        persistent_entity_manager,
        "private boolean addEntityUuid(T);",
        "private boolean addEntityUuid(net.minecraft.world.level.entity.EntityAccess);",
    )
    gl_device_max_texture = method_section(gl_device, "private static int getMaxSupportedTextureSize();")
    if not gl_device_max_texture and is_current_named:
        gl_device_max_texture = method_section(gl_heuristics, "private static int getMaxSupportedTextureSize();")
    gl_device_static = method_section(gl_device, "static {};")
    gl_command_encoder_draw = method_section(
        gl_command_encoder,
        "private void drawFromBuffers(com.mojang.blaze3d.opengl.GlRenderPass, int, int, int, com.mojang.blaze3d.IndexType, com.mojang.blaze3d.opengl.GlRenderPipeline, int, int);",
    )
    if not gl_command_encoder_draw:
        gl_command_encoder_draw = method_section(
            gl_command_encoder,
            "private void drawFromBuffers(com.mojang.blaze3d.opengl.GlRenderPass, int, int, int, com.mojang.blaze3d.vertex.VertexFormat$IndexType, com.mojang.blaze3d.opengl.GlRenderPipeline, int);",
        )
    vertex_array_cache_get = method_section(
        vertex_array_cache_browser,
        "private com.mojang.blaze3d.opengl.VertexArrayCache$VertexArray get(com.mojang.blaze3d.vertex.VertexFormat[]);",
    )
    vertex_array_cache_put = method_section(
        vertex_array_cache_browser,
        "private void put(com.mojang.blaze3d.vertex.VertexFormat[], com.mojang.blaze3d.opengl.VertexArrayCache$VertexArray);",
    )
    audio_library_init = method_section_any(
        audio_library,
        "public void init(java.lang.String, boolean);",
        "public void init(java.lang.String, com.mojang.blaze3d.audio.DeviceList, boolean);",
    )
    audio_listener_set_transform = method_section(
        audio_listener,
        "public void setTransform(com.mojang.blaze3d.audio.ListenerTransform);",
    )
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
    create_world_fresh = method_section_any(
        create_world_screen,
        "private static net.minecraft.world.level.levelgen.WorldGenSettings lambda$openFresh$4(net.minecraft.server.WorldLoader$DataLoadContext);",
        "private static net.minecraft.world.level.levelgen.WorldGenSettings lambda$openFresh$2(net.minecraft.server.WorldLoader$DataLoadContext);",
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
    texture_format = run_javap(
        client_cp,
        "com.mojang.blaze3d.GpuFormat"
        if is_current_named
        else "com.mojang.blaze3d.textures.TextureFormat",
    )
    texture_has_color_aspect = method_section(
        texture_format,
        "public boolean hasColorAspect();",
    )
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
    file_output_constructor = method_section(
        file_output_stream_class,
        "public org.teavm.classlib.java.io.TFileOutputStream(java.lang.String, org.teavm.runtime.fs.VirtualFileAccessor, boolean) throws java.io.IOException;",
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
    patchy_create_block_list = method_section(
        patchy_block_list,
        "public java.util.function.Predicate<java.lang.String> createBlockList();",
    )
    authlib_session_constructor = method_section(
        authlib_session,
        "protected com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService(com.mojang.authlib.yggdrasil.ServicesKeySet, java.net.Proxy, com.mojang.authlib.Environment);",
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
    arb_vertex_attrib = run_javap(lwjgl_opengl_overlay_cp, "org.lwjgl.opengl.ARBVertexAttribBinding")
    gl30 = run_javap(lwjgl_opengl_overlay_cp, "org.lwjgl.opengl.GL30")
    gl11c = run_javap(lwjgl_opengl_overlay_cp, "org.lwjgl.opengl.GL11C")
    browser_glfw = run_javap(lwjgl_glfw_cp, "org.lwjgl.glfw.BrowserGlfw")
    glfw = run_javap(lwjgl_glfw_cp, "org.lwjgl.glfw.GLFW")
    face_bakery = run_javap(
        client_cp,
        "net.minecraft.client.resources.model.cuboid.FaceBakery"
        if is_current_named
        else "net.minecraft.client.renderer.block.model.FaceBakery",
    )

    if minecraft_render_frame is not None and current_game_renderer_extract is not None:
        render_frame_order = [
            minecraft_render_frame.find(
                "Method net/minecraft/client/renderer/GameRenderer.update:"
            ),
            minecraft_render_frame.find("BrowserTargeting.deferFramePick"),
            minecraft_render_frame.find(
                "Method net/minecraft/client/renderer/GameRenderer.extract:"
            ),
        ]
        current_render_frame_contract = all(
            position >= 0 and position < next_position
            for position, next_position in zip(render_frame_order, render_frame_order[1:])
        ) and render_frame_order[0] >= 0
        camera_extract_position = current_game_renderer_extract.find("Method extractCamera:")
        targeting_refresh_position = current_game_renderer_extract.find(
            "BrowserTargeting.refreshFramePick"
        )
        camera_extract_line_end = current_game_renderer_extract.find(
            "\n", camera_extract_position
        )
        current_targeting_bridge = (
            current_game_renderer_extract[
                camera_extract_line_end:targeting_refresh_position
            ]
            if camera_extract_position >= 0
            and camera_extract_line_end >= 0
            and targeting_refresh_position > camera_extract_line_end
            else ""
        )
    else:
        current_render_frame_contract = False
        current_targeting_bridge = ""

    current_queue_poll = (
        required_method_section(
            current_section_task_queue,
            "public synchronized net.minecraft.client.renderer.chunk."
            "SectionRenderDispatcher$RenderSection$SectionTask poll(net.minecraft.world.phys.Vec3);",
        )
        if current_section_task_queue is not None
        else None
    )
    current_queue_clear = (
        required_method_section(
            current_section_task_queue,
            "public synchronized void clear();",
        )
        if current_section_task_queue is not None
        else None
    )
    current_section_queue_contract = (
        current_section_task_queue is not None
        and current_queue_poll is not None
        and current_queue_clear is not None
        and "java/util/ArrayList" in current_section_task_queue
        and "java/util/PriorityQueue" not in current_section_task_queue
        and "java/util/ListIterator" not in current_section_task_queue
        and "java/util/List.remove" not in current_section_task_queue
        and all(
            helper in current_section_task_queue
            for helper in (
                "browserDirtyTasks",
                "browserTaskOrder",
                "browserRebuild",
                "browserTakeNearest",
                "browserCancelAndClear",
                "browserIsDirtyCompile",
                "browserDistance",
                "browserDistanceFrom",
                "browserOrder",
                "browserRequeue",
                "browserFinishTask",
                "browserHeapAdd",
                "browserHeapPoll",
                "browserSiftUp",
                "browserSiftDown",
            )
        )
        and "browserRebuild" in current_queue_poll
        and "browserTakeNearest" in current_queue_poll
        and "browserCancelAndClear" in current_queue_clear
    )

    checks = [
        (
            "Resource reload bytecode wraps each listener with the browser timing profiler",
            "BrowserResourceReloadProfiler.wrap" in simple_reload_instance,
        ),
        (
            "Resource reload bytecode labels the model and font continuations that can block frames",
            "ModelManager.discoverModelDependencies" in model_manager
            and "ModelManager.buildModelGroups" in model_manager
            and "BrowserResourceReloadProfiler.label" in model_manager
            and "FontManager.apply" in font_manager
            and "BrowserResourceReloadProfiler.label" in font_manager,
        ),
        (
            "Remote pack bytecode reports verified downloads early without hiding reload failures",
            "java/util/HashSet.\"<init>\"" in downloaded_pack_response_constructor
            and "browserEarlyApplied" in downloaded_pack_response_constructor
            and downloaded_pack_report_update.count("Connection.send") == 2
            and 0
            <= downloaded_pack_report_update.find("PackLoadFeedback$Update.DOWNLOADED")
            < downloaded_pack_report_update.find("Set.add")
            < downloaded_pack_report_update.find("Action.SUCCESSFULLY_LOADED")
            and "Set.remove" in downloaded_pack_report_final
            and "PackLoadFeedback$FinalResult.APPLIED" in downloaded_pack_report_final
            and "return" in downloaded_pack_report_final,
        ),
        (
            "Cold server-pack timeout recovery is present in compiled client bytecode",
            "BrowserMultiplayerRecovery.prepareDisconnect" in client_common_on_disconnect
            and "Minecraft.disconnect" in client_common_on_disconnect
            and "BrowserMultiplayerRecovery.maybeReconnect" in client_common_on_disconnect
            and client_common_on_disconnect.find("BrowserMultiplayerRecovery.prepareDisconnect")
            < client_common_on_disconnect.find("Minecraft.disconnect")
            < client_common_on_disconnect.find("BrowserMultiplayerRecovery.maybeReconnect")
            and "BrowserMultiplayerRecovery.beginConnection" in connect_screen_start
            and (
                (
                    is_current_named
                    and connect_screen_start.find("BrowserMultiplayerRecovery.beginConnection")
                    < connect_screen_start.find("Minecraft.gui")
                )
                or (
                    not is_current_named
                    and connect_screen_start.find("BrowserMultiplayerRecovery.beginConnection")
                    < connect_screen_start.find("Minecraft.screen")
                )
            )
            and "Minecraft.execute" in browser_multiplayer_recovery_method
            and "ServerAddress.isValidAddress" in browser_multiplayer_recovery_method
            and "ConnectScreen.startConnecting" in browser_multiplayer_recovery_class
            and "BrowserServerPackReuse.keepServerPackForRecovery" in downloaded_pack_cleanup
            and "ServerPackManager.popAll" in downloaded_pack_cleanup
            and downloaded_pack_cleanup.find("BrowserServerPackReuse.keepServerPackForRecovery")
            < downloaded_pack_cleanup.find("ServerPackManager.popAll")
            and "BrowserMultiplayerRecovery.reusePreservedServerPack"
            in browser_server_pack_reuse_class
            and "Action.ACCEPTED" in browser_server_pack_reuse_class
            and "Action.DOWNLOADED" in browser_server_pack_reuse_class
            and "Action.SUCCESSFULLY_LOADED" in browser_server_pack_reuse_class
            and "BrowserServerPackReuse.suppressEarlyApplied" in downloaded_pack_report_final,
        ),
        (
            "Joined-world pack reload bytecode hides only the foreground overlay and still finishes it",
            (
                (
                    is_current_named
                    and "Gui.overlay" in minecraft_get_overlay
                    and "Minecraft.gaius$setOverlay" in loading_overlay_tick
                )
                or (
                    not is_current_named
                    and "LoadingOverlay" in minecraft_get_overlay
                    and "Field level:Lnet/minecraft/client/multiplayer/ClientLevel;"
                        in minecraft_get_overlay
                    and "aconst_null" in minecraft_get_overlay
                    and (
                        "Minecraft.setOverlay" in loading_overlay_tick
                        or "Minecraft.gaius$setOverlay" in loading_overlay_tick
                    )
                )
            )
            and "ReloadInstance.checkExceptions" in loading_overlay_tick
            and "Consumer.accept" in loading_overlay_tick
            and "Minecraft.level" in loading_overlay_tick
            and ("Minecraft.gaius$setOverlay" in loading_overlay_tick if is_current_named else True),
        ),
        (
            "FontManager compiled overlay records each synchronous apply subsection",
            font_manager.count("BrowserResourceReloadProfiler.sectionStarted") == 4
            and font_manager.count("BrowserResourceReloadProfiler.sectionCompleted") == 4
            and all(
                section in font_manager
                for section in (
                    "FontManager.apply.closeFontSets",
                    "FontManager.apply.closeProviders",
                    "FontManager.apply.createFontSets",
                    "FontManager.apply.bindAtlasProviders",
                )
            ),
        ),
        (
            "Unihex compiled path delegates to the bulk browser parser and keeps override bridges",
            "BrowserUnihexLoader.load" in unihex_definition
            and "browserOverrideFrom" in unihex_definition
            and "browserOverrideTo" in unihex_definition
            and "browserOverrideDimensions" in unihex_definition
            and "ZipInputStream.readAllBytes" in browser_unihex_loader
            and "prepareOverrides" in browser_unihex_loader
            and "findOverrideDimensions" in browser_unihex_loader,
        ),
        (
            "Model dependency discovery yields through a browser frame-budgeted continuation",
            "BrowserModelDependencyScheduler.continuation" in model_manager
            and model_manager.count("CompletableFuture.thenComposeAsync") >= 2
            and "checkcast     #" in model_manager
            and "net.minecraft.client.resources.model.ModelDiscovery" in model_manager,
        ),
        (
            "TeaVM output stream bytecode preserves replacement-write truncation",
            (
                is_current_named
                and "VirtualFileAccessor.resize" in file_output_truncate
                and "VirtualFileAccessor.seek" in file_output_truncate
                and "Method truncateIfRequested:(Lorg/teavm/runtime/fs/VirtualFileAccessor;Z)V"
                    in file_output_constructor
                and "TFileOutputStream.\"<init>\"" in default_new_output_stream
            )
            or (
                not is_current_named
                and "VirtualFileAccessor.resize" in file_output_truncate
                and "VirtualFileAccessor.seek" in file_output_truncate
                and "TFileOutputStream.\"<init>\"" in default_new_output_stream
            ),
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
            "TeaVM ZIP bytecode reads both local-header lengths before compressed data",
            "long 26l" in zip_get_input_stream.lower()
            and zip_get_input_stream.count("LittleEndianReader.readShortLE") >= 2
            and "TZipEntry.nameLen" not in zip_get_input_stream,
        ),
        (
            "Minecraft singleplayer compiled overlay hands worlds to the server Worker",
            "BrowserSingleplayerClient.open" in minecraft_world_load
            and "ireturn" not in minecraft_world_load
            and "BrowserSingleplayerClient.stop" in minecraft_disconnect
            and "BrowserSingleplayerClient.isLocalSession" in minecraft_disconnect_from_world
            and "Minecraft.isLocalServer" not in minecraft_disconnect_from_world
            and "BrowserSingleplayerClient.isLocalSession" in pause_screen_create_menu
            and "Minecraft.isLocalServer" not in pause_screen_create_menu
            and "BrowserIntegratedServerMain.registerServer" in minecraft_spin,
        ),
        (
            "Official server defers the historical data-fixer graph for current-version worlds",
            "BrowserIntegratedServerMain.dataFixer" in server_main
            and server_main.count("DataFixers.getDataFixer") == 1
            and "implements com.mojang.datafixers.DataFixer" in browser_lazy_data_fixer_class
            and "DataFixers.getDataFixer" in browser_lazy_data_fixer_class
            and "Dynamic<T> update" in browser_lazy_data_fixer_class,
        ),
        (
            "Compiled browser server startup preserves complete caches while yielding",
            server_main_entry.count("BrowserStartupScheduler.phase") == 7
            and "bootstrap-complete" in server_main_entry
            and "bootstrap-validated" in server_main_entry
            and "server-settings-ready" in server_main_entry
            and "server-pack-repository-ready" in server_main_entry
            and "server-world-load-config-ready" in server_main_entry
            and "server-world-load-started" in server_main_entry
            and "datapacks-loaded" in server_main_entry
            and world_loader.count("BrowserStartupScheduler.phase") == 5
            and "world-loader-started" in world_loader
            and "world-loader-worldgen-registries-started" in world_loader
            and "world-loader-dimension-registries-started" in world_loader
            and "world-loader-server-resources-started" in world_loader
            and "world-loader-server-resources-ready" in world_loader
            and "world-loader-cooperative-executor" in server_world_loader_lambda
            and "Util.backgroundExecutor" not in server_world_loader_lambda
            and "WorldLoader.load" in server_world_loader_lambda
            and "BrowserFuturePump.poll" in minecraft_util
            and "TModernRuntimeSupport.yieldToEventLoop" in browser_future_pump_class
            and "java/lang/Thread.sleep" not in browser_future_pump_class
            and "BrowserStartupScheduler.blockRegistered" in blocks_register
            and "BrowserStartupScheduler.blockStateInitialized" in block_state_init_cache
            and "BrowserStartupScheduler.registryBootstrapCompleted"
                in built_in_registry_create_contents_entry
            and "BrowserStartupScheduler" not in mapped_registry_register
            and "BrowserStartupScheduler.datapackResourceDecoded"
                in simple_json_scan_directory
            and "TModernRuntimeSupport.yieldToEventLoop" in browser_startup_scheduler_class
            and "java/lang/Thread.sleep:(J)V" not in browser_startup_scheduler_class
            and minecraft_main_entry.count("BrowserStartupScheduler.phase") == 5
            and "bootstrap-complete" in minecraft_main_entry
            and "client-bootstrap-complete" in minecraft_main_entry
            and "bootstrap-validated" in minecraft_main_entry
            and "datafixer-optimization-complete" in minecraft_main_entry
            and "render-thread-ready" in minecraft_main_entry
            and minecraft_main_entry.count("BrowserStartupScheduler.complete") == 1
            and "BrowserStartupScheduler.complete" in browser_integrated_server_main_class,
        ),
        (
            "Minecraft client defers the historical data-fixer graph until migration is required",
            "BrowserLazyDataFixer.instance" in minecraft_constructor
            and "DataFixers.getDataFixer" not in minecraft_constructor
            and "BrowserLazyDataFixer.skipEagerOptimization" in minecraft_main_entry
            and "DataFixers.optimize" not in minecraft_main_entry,
        ),
        (
            "Atlas reload diagnostics preserve the concrete atlas identity",
            "BrowserResourceReloadProfiler.labelAtlas" in atlas_entry_schedule_load,
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
            and "Bootstrap.connect" in server_listener_start
            and "BrowserIntegratedServerMain.markServerListenerReady" in server_listener_start
            and server_listener_start.find("Bootstrap.connect")
                < server_listener_start.find(
                    "BrowserIntegratedServerMain.markServerListenerReady"
                ),
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
            "Browser dedicated server grants commands after its local player list exists",
            "BrowserIntegratedServerMain.configurePlayerList" in dedicated_server_init
            and "setPlayerList:(Lnet/minecraft/server/players/PlayerList;)V" in dedicated_server_init
            and dedicated_server_init.find(
                "setPlayerList:(Lnet/minecraft/server/players/PlayerList;)V"
            )
                < dedicated_server_init.find("BrowserIntegratedServerMain.configurePlayerList"),
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
            and "iconst_1" in browser_websocket_pump
            and (
                "int 1048576" in browser_websocket_pump
                and "double 2.0d" in browser_websocket_pump
            )
            and "Field pumping:Z" in browser_websocket_pump
            and "Method monotonicMillis:()D" in browser_websocket_pump
            and "Method recordPump:(IIID)V" in browser_websocket_pump
            and "java/util/concurrent" not in browser_websocket_channel
            and "java/util/Collections" not in browser_websocket_channel
            and "globalThis.__gaiusNettyBridge" in browser_websocket_constants
            and "globalThis.__gaiusNetworkStats" in browser_websocket_constants
            and "new WebSocket(candidate.url)" in browser_websocket_constants
            and "relayNodeCandidate" in browser_websocket_constants
            and "recordRelayNodeFailure" in browser_websocket_constants
            and "gaius-relay-registry" in browser_websocket_constants
            and "relayRegistryNodesLoaded" in browser_websocket_constants
            and "raw.githubusercontent.com/TypeThe0ry/Gaius/main/relay-nodes.json"
                in browser_websocket_constants,
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
            and "dev/gaius/browser/BrowserHttpProxy.browserSafeHeaders" in http_util_download
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in http_util_download
            and "java/net/URL.openConnection:()" in http_util_download,
        ),
        (
            "Mojang blocked-server checks use the browser authentication proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyAuthentication"
            in patchy_create_block_list
            and "sessionserver.mojang.com/blockedservers" in patchy_create_block_list,
        ),
        (
            "Authlib session requests use the browser HTTP bridge without Java Proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyAuthentication" in authlib_create_connection
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in authlib_create_connection
            and "java/net/URL.openConnection:()" in authlib_create_connection,
        ),
        (
            "Authlib texture profile Gson bytecode registers browser-safe decoding",
            "dev/gaius/browser/BrowserAuthlibGson.textureDeserializer"
            in authlib_session_constructor
            and "com/mojang/authlib/minecraft/MinecraftProfileTexture"
            in authlib_session_constructor.replace(".", "/")
            and authlib_session_constructor.find(
                "BrowserAuthlibGson.textureDeserializer"
            )
            < authlib_session_constructor.find("UUIDTypeAdapter"),
        ),
        (
            "Authlib texture payload bypasses TeaVM Gson reflection",
            "dev/gaius/browser/BrowserAuthlibGson.decodeTextures"
            in method_section(
                authlib_session,
                "public com.mojang.authlib.minecraft.MinecraftProfileTextures unpackTextures(com.mojang.authlib.properties.Property);",
            )
            and "com/google/gson/Gson.fromJson" not in method_section(
                authlib_session,
                "public com.mojang.authlib.minecraft.MinecraftProfileTextures unpackTextures(com.mojang.authlib.properties.Property);",
            ),
        ),
        (
            "Authlib profile texture exposes a browser-safe Gson no-argument constructor",
            "public com.mojang.authlib.minecraft.MinecraftProfileTexture();"
            in authlib_profile_texture
            and "java/util/Collections.emptyMap" in authlib_profile_texture,
        ),
        (
            "Remote player textures use the browser HTTP bridge without Java Proxy",
            "dev/gaius/browser/BrowserHttpProxy.proxyTexture" in skin_texture_download
            and "java/net/URL.openConnection:(Ljava/net/Proxy;)" not in skin_texture_download
            and "java/net/URL.openConnection:()" in skin_texture_download,
        ),
        (
            "Entity renderer skips transient null entities instead of crashing multiplayer worlds",
            "aload_1" in entity_render_should_render
            and "ifnonnull" in entity_render_should_render
            and "iconst_0" in entity_render_should_render
            and "ireturn" in entity_render_should_render
            and entity_render_should_render.find("ifnonnull")
                < entity_render_should_render.find("getRenderer"),
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
            (
                is_current_named
                and "public net.minecraft.world.entity.Entity(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);"
                    in entity_constructor
                and "java/util/UUID.randomUUID:()Ljava/util/UUID;" in entity_constructor
                and "net/minecraft/util/Mth.createInsecureUUID" not in entity_constructor
            )
            or (
                not is_current_named
                and "public net.minecraft.world.entity.Entity(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);"
                    in entity_constructor
                and "java/util/UUID.randomUUID:()Ljava/util/UUID;" in entity_constructor
                and "net/minecraft/util/Mth.createInsecureUUID" not in entity_constructor
            ),
        ),
        (
            "SimpleBitStorage scalar access uses direct browser BigInt64Array operations",
            "dev/gaius/browser/BrowserBitStorage.get" in simple_bit_storage_get
            and "([JIII)I" in simple_bit_storage_get
            and "Field mask:J" not in simple_bit_storage_get
            and "Long_" not in simple_bit_storage_get
            and "dev/gaius/browser/BrowserBitStorage.getAndSet" in simple_bit_storage_get_and_set
            and "([JIIII)I" in simple_bit_storage_get_and_set
            and "Field mask:J" not in simple_bit_storage_get_and_set
            and "Long_" not in simple_bit_storage_get_and_set
            and "dev/gaius/browser/BrowserBitStorage.getAndSet" in simple_bit_storage_set
            and "([JIIII)I" in simple_bit_storage_set
            and "Field mask:J" not in simple_bit_storage_set
            and "Long_" not in simple_bit_storage_set,
        ),
        (
            "SimpleBitStorage.unpack calls browser bit-storage hot path before vanilla loop",
            "dev/gaius/browser/BrowserBitStorage.unpack" in simple_bit_storage_unpack
            and "([J[IIII)Z" in simple_bit_storage_unpack
            and "return" in simple_bit_storage_unpack
            and "public static native boolean unpack" in browser_bit_storage_class,
        ),
        (
            "Heightmap scalar storage avoids abstract minY and BitStorage calls in hot methods",
            "Field browserMinY:I" in heightmap_constructor
            and "Field browserData:[J" in heightmap_constructor
            and "Field browserValuesPerLong:I" in heightmap_constructor
            and "Field browserBits:I" in heightmap_constructor
            and "Field browserMask:J" not in heightmap_constructor
            and "ChunkAccess.getMinY" in heightmap_constructor
            and all(
                "Field browserMinY:I" in section
                and "Field browserData:[J" in section
                and "dev/gaius/browser/BrowserBitStorage.get" in section
                and "([JIII)I" in section
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
            and "([JIIII)I" in heightmap_set_height
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
            (
                ("Field fastFormat:Z" in buffer_builder_add_vertex)
                if not is_current_named
                else True
            )
            and "Method beginVertex:()J" in buffer_builder_add_vertex
            and "Field com/mojang/blaze3d/vertex/ByteBufferBuilder.browserData:[B" in buffer_builder_add_vertex
            and "Field com/mojang/blaze3d/vertex/ByteBufferBuilder.browserLastReserveOffset:I" in buffer_builder_add_vertex
            and "org/lwjgl/system/BrowserMemory.putFastVertexBytes" in buffer_builder_add_vertex
            and "([BIFFFIFFIIFFFZ)V" in buffer_builder_add_vertex
            and "InterfaceMethod com/mojang/blaze3d/vertex/VertexConsumer.addVertex:(FFFIFFIIFFF)V" in buffer_builder_add_vertex
            and "org/lwjgl/system/MemoryUtil.memPutFloat" not in buffer_builder_add_vertex
            and "org/lwjgl/system/MemoryUtil.memPutByte" not in buffer_builder_add_vertex,
        ),
        (
            "Compiled browser Math.fma remains native for TeaVM JSBody lowering",
            "public static native float fma(float, float, float);" in modern_runtime_support_class
            and "public static native double fma(double, double, double);" in modern_runtime_support_class
            and "public static native void yieldToEventLoop(int);" in modern_runtime_support_class,
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
            "BufferBuilder GUI/text writers use cached byte-array offsets",
            "org/lwjgl/system/BrowserMemory.putPositionBytes" in buffer_builder_add_vertex_float
            and "org/lwjgl/system/BrowserMemory.putTransformedPositionBytes" in buffer_builder_add_vertex_matrix
            and "org/lwjgl/system/BrowserMemory.putRgbaBytes" in buffer_builder_set_color
            and "org/lwjgl/system/BrowserMemory.putFloatPairBytes" in buffer_builder_set_uv
            and "org/lwjgl/system/BrowserMemory.putPackedUvBytes" in buffer_builder_set_light
            and "org/lwjgl/system/BrowserMemory.putNormalBytes" in buffer_builder_set_normal
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
            and "Field pointer:J" in byte_buffer_builder_reserve
            and "Field browserLastReserveOffset:I" in byte_buffer_builder_reserve,
        ),
        (
            "Compiled section uploads reuse one MeshData vertex view per upload",
            (
                is_current_named
                and "CompiledSectionMesh.getSectionDraw" in render_section_upload
                and render_section_upload.count("UberGpuBuffer.addAllocation") >= 2
                and "BrowserRenderScheduler.requestEmergencyUpload" in render_section_upload
                and "SectionRenderDispatcher.uploadTerrainBuffersToGpu" in render_section_upload
                and "CompiledSectionMesh.setIndexBufferUploaded" in render_section_upload
                and "MeshData.vertexBuffer" not in render_section_upload
            )
            or (
                not is_current_named
                and compiled_section_upload_mesh.count("dev/gaius/browser/BrowserMeshUpload.vertexBuffer") == 4
                and "dev/gaius/browser/BrowserMeshUpload.begin" in compiled_section_upload_mesh
                and "dev/gaius/browser/BrowserMeshUpload.end" in compiled_section_upload_mesh
                and "com/mojang/blaze3d/vertex/MeshData.vertexBuffer" not in compiled_section_upload_mesh
            ),
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
            "BrowserMemory compiled overlay provides JNI stand-ins without losing constant-time regions",
            "public static long threadJniEnv();" in browser_memory
            and "public static long setupThreadEnv(int);" in browser_memory
            and "private static final java.util.Map<java.lang.Integer, org.lwjgl.system.BrowserMemory$Region> REGIONS;" in browser_memory
            and "BrowserMemory$Block" not in browser_memory,
        ),
        (
            "BrowserMemory compiled overlay exposes its hard allocation budget",
            "public static long maxLiveBytes();" in browser_memory
            and "public static long allocationFailures();" in browser_memory
            and "public static int maxTemporaryBytes();" in browser_memory
            and "public static int peakTemporaryBytes();" in browser_memory
            and "public static long temporaryAllocationFailures();" in browser_memory
            and "private static void ensureLiveByteCapacity(long);" in browser_memory
            and "private static int configuredMaxTemporaryBytes();" in browser_memory
            and "DEFAULT_MAX_LIVE_BYTES" in browser_memory
            and "MAX_LIVE_BYTES" in browser_memory
            and "MAX_TEMPORARY_BYTES" in browser_memory,
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
            and "public static byte[] data(long);" in browser_memory
            and "public static int dataOffset(long);" in browser_memory
            and "public static native void putPositionBytes(byte[], int, float, float, float);" in browser_memory
            and "public static native void putRgbaBytes(byte[], int, int);" in browser_memory
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
            (
                "BrowserOpenGL.drawFromBuffers:(IIIIIIIII)V" in gl_command_encoder_draw
                and "Field com/mojang/blaze3d/opengl/GlRenderPipeline.gaius$primitiveTopology:I"
                    in gl_command_encoder_draw
                and "GlRenderPipeline.info" not in gl_command_encoder_draw
                and "GlConst.toGl:(Lcom/mojang/blaze3d/PrimitiveTopology;)I"
                    not in gl_command_encoder_draw
                and "BrowserOpenGL.bindBuffer:(II)V" not in gl_command_encoder_draw
                and "GlStateManager._glBindBuffer" not in gl_command_encoder_draw
                and "lmul" not in gl_command_encoder_draw
            )
            if "gaius$primitiveTopology" in gl_render_pipeline
            else (
                "BrowserOpenGL.bindBuffer:(II)V" not in gl_command_encoder_draw
                and "BrowserOpenGL.drawFromBuffers:(IIIIIIII)V" in gl_command_encoder_draw
                and "Field com/mojang/blaze3d/opengl/GlRenderPipeline.gaius$vertexFormat"
                    in gl_command_encoder_draw
                and "Field com/mojang/blaze3d/opengl/GlRenderPipeline.gaius$drawMode:I"
                    in gl_command_encoder_draw
                and "GlRenderPipeline.info" not in gl_command_encoder_draw
                and "GlConst.toGl" not in gl_command_encoder_draw
                and "lmul" not in gl_command_encoder_draw
            ),
        ),
        (
            "GlRenderPipeline caches immutable browser draw metadata once",
            (
                "final int gaius$primitiveTopology;" in gl_render_pipeline
                and "RenderPipeline.getPrimitiveTopology" in gl_render_pipeline
                and "GlConst.toGl:(Lcom/mojang/blaze3d/PrimitiveTopology;)I"
                    in gl_render_pipeline
                and "Field gaius$primitiveTopology:I" in gl_render_pipeline
            )
            if version == "26.2"
            else (
                "final com.mojang.blaze3d.vertex.VertexFormat gaius$vertexFormat;"
                    in gl_render_pipeline
                and "final int gaius$drawMode;" in gl_render_pipeline
                and "RenderPipeline.getVertexFormat" in gl_render_pipeline
                and "RenderPipeline.getVertexFormatMode" in gl_render_pipeline
                and "GlConst.toGl" in gl_render_pipeline
            ),
        ),
        (
            "VertexArrayCache uses a bounded LRU instead of rebuilding overflow VAOs",
            (not is_current_named)
            or (
                "java/util/LinkedHashMap" in vertex_array_cache_browser
                and "sipush        2048" in vertex_array_cache_browser
                and "java/util/LinkedHashMap.entrySet" in vertex_array_cache_browser
                and "java/util/Iterator.remove" in vertex_array_cache_browser
                and "org/lwjgl/opengl/GL30.glDeleteVertexArrays" in vertex_array_cache_browser
                and "VertexArrayKey" in vertex_array_cache_key
                and "overflowCache" not in vertex_array_cache_browser
            ),
        ),
        (
            "VertexArrayCache reuses its hot-path lookup key and allocates only on VAO cache misses",
            (not is_current_named)
            or (
                "lookupKey" in vertex_array_cache_browser
                and "VertexArrayKey.set" in vertex_array_cache_get
                and "VertexArrayKey.\"<init>\"" not in vertex_array_cache_get
                and "VertexArrayKey.\"<init>\"" in vertex_array_cache_put
                and "clone" not in vertex_array_cache_get
                and "clone" in vertex_array_cache_key
                and "private com.mojang.blaze3d.opengl.VertexArrayCache$VertexArrayKey set" in vertex_array_cache_key
                and "java/lang/Record" not in vertex_array_cache_key
            ),
        ),
        (
            "VertexArrayCache bypasses generic map lookup for recently used browser VAOs",
            (not is_current_named)
            or (
                "sipush        256" in vertex_array_cache_browser
                and "hotKeys" in vertex_array_cache_browser
                and "hotVertexArrays" in vertex_array_cache_browser
                and "hotAccessCounts" in vertex_array_cache_browser
            and "cacheHot" in vertex_array_cache_browser
            and "clearHot" in vertex_array_cache_browser
            and "(hashCode ^ hashCode >>> 16)" in vertex_array_cache_source
            and "(accessCount & 63) == 0" in vertex_array_cache_source
            and "vertexArray.cacheKey = key" in vertex_array_cache_source
                and "private int hashCode;" in vertex_array_cache_source
            ),
        ),
        (
            "VertexArrayCache compiled overlay binds VAOs directly through browser GL30",
            vertex_array_cache_emulated.count("org/lwjgl/opengl/GL30.glBindVertexArray") >= 2
            and vertex_array_cache_separate.count("org/lwjgl/opengl/GL30.glBindVertexArray") >= 2
            and "GlStateManager._glBindVertexArray" not in vertex_array_cache_emulated
            and "GlStateManager._glBindVertexArray" not in vertex_array_cache_separate,
        ),
        (
            "LWJGL GL30 delegates browser VAO deletion for LRU eviction",
            "public static void glDeleteVertexArrays(int);" in gl30
            and "BrowserOpenGL.deleteVertexArray" in method_section(
                gl30,
                "public static void glDeleteVertexArrays(int);",
            ),
        ),
        (
            "LWJGL GL11C delegates 26.2 state, queries, and texture uploads to WebGL",
            "BrowserOpenGL.getString:(I)Ljava/lang/String;" in method_section(
                gl11c, "public static java.lang.String glGetString(int);"
            )
            and "BrowserOpenGL.getInteger:(I)I" in method_section(
                gl11c, "public static int glGetInteger(int);"
            )
            and "BrowserOpenGL.getFloat:(I)F" in method_section(
                gl11c, "public static float glGetFloat(int);"
            )
            and "BrowserOpenGL.clear:(I)V" in method_section(
                gl11c, "public static void glClear(int);"
            )
            and "BrowserOpenGL.texSubImage2D:(IIIIIIIIJ)V" in method_section(
                gl11c,
                "public static void glTexSubImage2D(int, int, int, int, int, int, int, int, long);",
            ),
        ),
        (
            "VertexArrayCache compiled overlay preserves vanilla UV/normal/color attribute types",
            (
                "GlConst.isFormatNormalized" in vertex_array_cache_emulated
                and "GlConst.isGlFormatInteger" in vertex_array_cache_emulated
                and "GlStateManager._vertexAttribIPointer" in vertex_array_cache_emulated
                and "GlStateManager._vertexAttribPointer" in vertex_array_cache_emulated
            )
            if version == "26.2"
            else (
                "private static boolean shouldNormalize" in vertex_array_cache
                and "VertexFormatElement$Usage.COLOR" in vertex_array_cache
                and "VertexFormatElement$Usage.NORMAL" in vertex_array_cache
                and "VertexFormatElement$Usage.UV" not in vertex_array_cache
                and "VertexFormatElement$Usage.GENERIC" not in vertex_array_cache
                and "shouldNormalize" in vertex_array_cache_emulated
                and "shouldNormalize" in vertex_array_cache_separate
            ),
        ),
        (
            "TextureFormat.hasColorAspect treats all non-depth formats as color",
            (
                is_current_named
                and "public boolean hasColorAspect();" in texture_has_color_aspect
                and "hasDepthAspect" in texture_has_color_aspect
                and "hasStencilAspect" in texture_has_color_aspect
                and "iconst_1" in texture_has_color_aspect
                and "iconst_0" in texture_has_color_aspect
            )
            or (
                not is_current_named
                and "public boolean hasColorAspect();" in texture_format
                and "DEPTH32" in texture_format
                and "if_acmpeq" in texture_format
            ),
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
            and "webGlPixelAlignment" in browser_opengl
            and "bytesPerPixel" in browser_opengl,
        ),
        (
            "BrowserOpenGL compiled overlay splits client and PBO framebuffer readback",
            "public static void readPixels(int, int, int, int, int, int, long);"
                in browser_opengl
            and "Method boundBufferForTargetJs:(I)I" in method_section(
                browser_opengl,
                "public static void readPixels(int, int, int, int, int, int, long);",
            )
            and "Method readPixelsOffsetJs:(IIIIIII)V" in method_section(
                browser_opengl,
                "public static void readPixels(int, int, int, int, int, int, long);",
            )
            and "Method readPixelsBytesJs:" in method_section(
                browser_opengl,
                "public static void readPixels(int, int, int, int, int, int, long);",
            )
            and "Method pixelReadLength:(IIII)I" in method_section(
                browser_opengl,
                "public static void readPixels(int, int, int, int, int, int, long);",
            ),
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
            "BrowserOpenGL compiled overlay adapts shader attribute pointer types before draw",
            "programAttribs" in browser_opengl_constants
            and "refreshProgramAttribs" in browser_opengl_constants
            and "expectedAttribInteger" in browser_opengl_constants
            and "recordAttribPointerAdapt" in browser_opengl_constants
            and "attribTypePointerAdapts" in browser_opengl_constants
            and "effectiveInteger" in browser_opengl_constants
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
            (
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
                and "268435456" in browser_opengl_constants
                and "misalignedBufferRefs" in browser_opengl_constants
                and "refs.get(id)" in browser_opengl_constants
                and "v.misalignedAttribBuffers.set(i,b)" in browser_opengl_constants
                and "s.misalignedBufferRefs.set(b,(n+1)|0)" in browser_opengl_constants
                and "releaseVaoMisalignedBuffers" in browser_opengl_constants
                and "this.vaoEmu.forEach(function(v)" in browser_opengl_constants
                and "markBufferShadowRequired" in browser_opengl_constants
                and "misaligned-attrib" in browser_opengl_constants
            )
            if is_current_named
            else (
                not is_current_named
                or (
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
                and "misaligned-attrib" in browser_opengl_constants
                )
            ),
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
            (
                is_current_named
                and "drawFromBuffers" in browser_opengl
                and "Number(indexOffset)*Number(indexBytes)" in browser_opengl_constants
                and "const nextId=elementBuffer|0;" in browser_opengl_constants
                and "state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);"
                    in browser_opengl_constants
                and "state.executeDraw" in browser_opengl_constants
                and "firstOrBaseVertex" in browser_opengl_constants
            )
            or (
                not is_current_named
                or (
                    "drawFromBuffers" in browser_opengl
                    and "Number(indexOffset)*Number(indexBytes)" in browser_opengl_constants
                    and "const nextId=elementBuffer|0;" in browser_opengl_constants
                    and "state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);"
                        in browser_opengl_constants
                    and "state.executeDraw((instances|0)>1?2:0,mode,firstOrBaseVertex,count,instances,0,0);"
                        in browser_opengl_constants
                )
            ),
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
            (not is_current_named)
            or (
            "cacheShiftedIndexBuffer=function(vao,type,offset,count,baseVertex)"
            in browser_opengl_constants
            and "const cached=vao.shiftedIndexLast" in browser_opengl_constants
            and "cached && !cached.deleted" in browser_opengl_constants
            and "vao.shiftedIndexLast=entry" in browser_opengl_constants
            and (
                "entry.deleted=true" in browser_opengl_constants
                if is_current_named
                else "oldest.deleted=true" in browser_opengl_constants
            )
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
        ),
        (
            "BrowserOpenGL compiled overlay caches alternating base-vertex draws numerically",
            (not is_current_named)
            or (
            "shiftedIndexFastCache:new Map()" in browser_opengl_constants
            and "Math.imul((fastKey^(type|0))|0,16777619)" in browser_opengl_constants
            and "fastEntry.offset===start" in browser_opengl_constants
            and "(fastEntry.inputCount|0)===length" in browser_opengl_constants
            and "(fastEntry.base|0)===base" in browser_opengl_constants
            and "fastCache.size >= 64" in browser_opengl_constants
            and "baseVertexIndexFastCacheHits" in browser_opengl_constants
            and "this.cacheShiftedIndexBuffer(vao,type,off,count,base)"
            in browser_opengl_constants
            and (
                is_current_named
                or (
                    browser_opengl_constants.find(
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
                        "let source=this.bufferBytes.get(elementBuffer)",
                        browser_opengl_constants.find("cacheShiftedIndexBuffer=function"),
                    )
                )
            )
            and (
                is_current_named
                or (
                    "Math.imul((fastKey^(version|0))|0,16777619)"
                    not in browser_opengl_constants
                    and "(cached.version|0)===(version|0)"
                        not in browser_opengl_constants
                    and "(fastEntry.version|0)===(version|0)"
                        not in browser_opengl_constants
                )
            ),
            ),
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
            "BrowserOpenGL compiled overlay bounds aligned buffers and releases VAO references",
            "alignedAttribCacheTotalBytes:0" in browser_opengl_constants
            and "maxAlignedAttribCacheBytes=function()" in browser_opengl_constants
            and "trimAlignedAttribCache=function(incomingBytes)" in browser_opengl_constants
            and "deleteAlignedAttribEntry=function(key,evicted)" in browser_opengl_constants
            and "shiftedIndexEntries:new Set()" in browser_opengl_constants
            and "releaseVaoShiftedIndexRefs=function(vao)" in browser_opengl_constants
            and "state.releaseVaoShiftedIndexRefs(vao);" in browser_opengl_constants,
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
            "BrowserOpenGL compiled overlay preserves pixel-unpack-buffer offsets",
            "int 35052" in method_section(
                browser_opengl,
                "public static void texSubImage2D(int, int, int, int, int, int, int, int, long);",
            )
            and "boundBufferForTargetJs:(I)I" in method_section(
                browser_opengl,
                "public static void texSubImage2D(int, int, int, int, int, int, int, int, long);",
            )
            and "texSubImage2DOffsetJs:(IIIIIIIII)V" in method_section(
                browser_opengl,
                "public static void texSubImage2D(int, int, int, int, int, int, int, int, long);",
            )
            and "pointerBytes:(JI)Lorg/teavm/jso/typedarrays/Int8Array;" in method_section(
                browser_opengl,
                "public static void texSubImage2D(int, int, int, int, int, int, int, int, long);",
            ),
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
            "BrowserOpenGL compiled overlay exports exact mapped-buffer flush sub-ranges",
            "java/nio/ByteBuffer.slice:()Ljava/nio/ByteBuffer;" in browser_opengl
            and "Int8Array.fromJavaBuffer" in method_section(
                browser_opengl,
                "private static org.teavm.jso.typedarrays.Int8Array bytesSlice(java.nio.ByteBuffer, long, long);",
            )
            and "Method bytesSlice:(Ljava/nio/ByteBuffer;JJ)Lorg/teavm/jso/typedarrays/Int8Array;"
                in method_section(
                    browser_opengl,
                    "public static void flushMappedBufferRange(int, long, long);",
                )
            and "Method bytesSlice:(Ljava/nio/ByteBuffer;JJ)Lorg/teavm/jso/typedarrays/Int8Array;"
                in method_section(
                    browser_opengl,
                    "public static void flushMappedNamedBufferRange(int, long, long);",
                ),
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
            "BrowserOpenAL compiled overlay retires naturally ended nodes",
            "retireScheduledEntry" in browser_openal_constants
            and "node.onended = function() { retireScheduledEntry(source, entry); };"
                in browser_openal_constants
            and "webAudioNaturalEnds" in browser_openal_constants,
        ),
        (
            "BrowserOpenAL compiled overlay supports equalpower and HRTF panning",
            "setDirectionalAudio" in browser_openal
            and "applyPanningModel" in browser_openal_constants
            and "directionalAudio" in browser_openal_constants
            and "HRTF" in browser_openal_constants
            and "equalpower" in browser_openal_constants,
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
            and "BrowserOpenAL.setDirectionalAudio" in audio_library_init
            and "Library$CountingChannelPool" in audio_library_init
            and "bipush        30" in audio_library_init
            and "bipush        8" in audio_library_init
            and "SoundBufferLibrary.preload" in sound_engine_load_library
            and "browser.sound.silent" not in sound_engine,
        ),
        (
            "Minecraft audio listener compiled overlay tracks camera position and orientation",
            "BrowserOpenAL.listener3f" in audio_listener_set_transform
            and "BrowserOpenAL.listenerOrientation" in audio_listener_set_transform
            and "ListenerTransform.position" in audio_listener_set_transform
            and "ListenerTransform.forward" in audio_listener_set_transform
            and "ListenerTransform.up" in audio_listener_set_transform,
        ),
        (
            "BrowserMemory compiled overlay preserves mapped ByteBuffer addresses through memSlice",
            "public static long register(java.nio.ByteBuffer);" in browser_memory
            and "private static void registerDerived(java.nio.Buffer, java.nio.Buffer, int);" in browser_memory,
        ),
        (
            "BrowserMemory compiled overlay frees mapped buffers without scanning the whole address table",
            (
                is_current_named
                and "REGIONS" in browser_memory
                and "remember" in browser_memory
                and "releaseRegion" in browser_memory
                and "releaseAutomaticRegionIfUnreferenced" in browser_memory
                and "java/util/Map.remove" in browser_memory
            )
            or (
                not is_current_named
                and "REGIONS" in browser_memory
                and "remember" in browser_memory
                and "releaseRegion" in browser_memory
                and "releaseAutomaticRegionIfUnreferenced" in browser_memory
                and "java/util/Map.remove" in browser_memory
            ),
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
            and "private static native boolean swapBuffersJs();" in browser_glfw
            and "gameFps" in browser_glfw_constants
            and "gameFrames" in browser_glfw_constants
            and "gameLastSampleAt" in browser_glfw_constants,
        ),
        (
            "BrowserGlfw compiled overlay honors VSync and yields uncapped frames",
            "private static native boolean swapBuffersJs();" in browser_glfw
            and "document.visibilityState" in browser_glfw_constants
            and "__gaiusBackgroundFrameThrottles" in browser_glfw_constants
            and "private static int swapInterval;" in browser_glfw
            and "private static native void yieldAfterPresent(boolean, int);" in browser_glfw
            and "private static native void scheduleFrameYield(boolean, int," in browser_glfw
            and "Field swapInterval:I" in method_section(
                browser_glfw, "public static void swapBuffers(long);"
            )
            and "Method yieldAfterPresent:(ZI)V" in method_section(
                browser_glfw, "public static void swapBuffers(long);"
            )
            and "synchronizedToDisplay" in browser_glfw_constants
            and "uncappedYieldCount" in browser_glfw_constants
            and "vsyncYieldCount" in browser_glfw_constants
            and "telemetry.swapInterval=Number(interval)||0" in browser_glfw_constants
            and "scheduler={tasks:new Map(),channel:null,nextTaskId:1}"
                in browser_glfw_constants
            and "scheduler.tasks.delete(taskId)" in browser_glfw_constants
            and "cancelledMessageTaskCount" in browser_glfw_constants
            and "messageChannelRebuildCount" in browser_glfw_constants
            and "messageChannelCreateFailureCount" in browser_glfw_constants
            and "messageChannelPostFailureCount" in browser_glfw_constants
            and "setTimeout(() => finish('timer'), 0)" in browser_glfw_constants
            and "(sequence & 3)===0" in browser_glfw_constants
            and "scheduler={queue:[],channel:null}" not in browser_glfw_constants,
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
            (
                is_current_named
                and "public static void limitDisplayFPS(int);" in framerate_limit_fps
                and "java/lang/System.nanoTime:()J" in framerate_limit_fps
                and "java/util/concurrent/locks/LockSupport.parkNanos:(J)V"
                    in framerate_limit_fps
                and "java/lang/Thread.interrupted:()Z" in framerate_limit_fps
                and "goto" in framerate_limit_fps
                and "browserCompensateFrameTime:(JI)J" in framerate_limit_fps
            )
            or (
                not is_current_named
                and "org/lwjgl/glfw/GLFW.glfwWaitEventsTimeout:(D)V"
                    in render_system_limit_fps
                and "java/lang/Thread.yield:()V" not in render_system_limit_fps
                and render_system_limit_fps.count("org/lwjgl/glfw/GLFW.glfwGetTime:()D") == 2
                and "goto" in render_system_limit_fps
                and minecraft_run_tick.count("java/lang/Thread.yield:()V") == 1
                and "org/lwjgl/glfw/BrowserGlfw.yieldAfterFrame" not in minecraft_run_tick
            ),
        ),
        (
            "Compiled browser frame pacing compensates sub-frame timer overshoot",
            (
                is_current_named
                and "browserCompensateFrameTime:(JI)J" in framerate_limit_fps
                and "java/lang/System.nanoTime:()J" in framerate_compensate_frame_time
                and "lsub" in framerate_compensate_frame_time
                and "ldiv" in framerate_compensate_frame_time
                and "lcmp" in framerate_compensate_frame_time
                and framerate_compensate_frame_time.count("lreturn") >= 2
            )
            or (
                not is_current_named
                and "browserCompensateFrameTime:(DDI)D" in render_system_limit_fps
                and "ddiv" in render_system_compensate_frame_time
                and "dsub" in render_system_compensate_frame_time
                and "ifge" in render_system_compensate_frame_time
                and render_system_compensate_frame_time.count("dreturn") == 2
            ),
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
            and (
                "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;"
                in mouse_handler
                or (
                    is_current_named
                    and "Gui.overlay" in mouse_handler
                    and "Gui.screen" in mouse_handler
                )
            ),
        ),
        (
            "KeyboardHandler browser callbacks dispatch synchronously",
            "KeyEvent.\"<init>\":(III)V" in keyboard_setup_key
            and "keyPress:(JILnet/minecraft/client/input/KeyEvent;)V" in keyboard_setup_key
            and "Minecraft.execute" not in keyboard_setup_key
            and (
                (
                    "CharacterEvent.\"<init>\":(I)V" in keyboard_setup_char
                    if is_current_named
                    else "CharacterEvent.\"<init>\":(II)V" in keyboard_setup_char
                )
            )
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
            (
                is_current_named
                and "getOrAllocate" in gui_item_atlas_get_or_update
                and "iconst_0" in gui_item_atlas_get_or_update
                and "TrackingItemStackRenderState.isAnimated"
                    not in gui_item_atlas_get_or_update
                and "BrowserOpenGL.reportGuiItemAtlas" not in gui_item_atlas_get_or_update
                and "BrowserOpenGL.reportGuiItemAtlas" not in gui_renderer_invalidate_item_atlas
            )
            or (
                not is_current_named
                and "pop" in gui_renderer_item_atlas_lambda
                and "iconst_0" in gui_renderer_item_atlas_lambda
                and "TrackingItemStackRenderState.isAnimated" in gui_renderer_item_atlas_lambda
                and "BrowserOpenGL.reportGuiItemAtlas" not in gui_renderer_item_atlas_lambda
                and "BrowserOpenGL.reportGuiItemAtlas" not in gui_renderer_invalidate_item_atlas
            ),
        ),
        (
            "GuiGraphics.renderItem uses constant browser item debug names",
            (
                "browser:item" in gui_render_item
                and "net/minecraft/network/chat/Component.toString" not in gui_render_item
                and "net/minecraft/world/item/Item.getName" not in gui_render_item
            )
            or (
                is_current_named
                and "browser:item" in browser_gui_item_cache
                and "net/minecraft/network/chat/Component.toString" not in gui_render_item
                and "net/minecraft/world/item/Item.getName" not in gui_render_item
            ),
        ),
        (
            "DynamicUniforms constructor uses browser initial UBO capacities",
            "Dynamic Transforms UBO" in dynamic_uniforms_constructor
            and dynamic_uniforms_constructor.count("sipush        128") >= 2
            and "Chunk Sections UBO" in dynamic_uniforms_constructor
            and "iconst_2" not in dynamic_uniforms_constructor,
        ),
        (
            "Minecraft compiled overlay processes queued packets on every browser tick",
            "BrowserWebSocketChannel.pumpAll" in minecraft_run_tick
            and "PacketProcessor.processQueuedPackets" in minecraft_run_tick
            and minecraft_run_tick.find("BrowserWebSocketChannel.pumpAll")
                < minecraft_run_tick.find("PacketProcessor.processQueuedPackets"),
        ),
        (
            "Compiled packet queue drains a count- and time-bounded browser batch",
            "BrowserPacketScheduler.beginBatch" in packet_processor
            and "BrowserPacketScheduler.shouldProcessNext" in packet_processor
            and "Queue.poll" in packet_processor
            and "ListenerAndPacket.handle" in packet_processor
            and "goto" in packet_processor
            and "bipush        16" in browser_packet_scheduler_class
            and "BrowserIntegratedServerMain.isWorkerServer" in browser_packet_scheduler_class
            and "iconst_4" in browser_packet_scheduler_class
            and "long 2000000l" in browser_packet_scheduler_class
            and "System.nanoTime" in browser_packet_scheduler_class,
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
            (
                (
                    "net/minecraft/client/gui/GuiGraphicsExtractor.fill:(IIIII)V"
                    in screen_render_panorama
                    and "net/minecraft/client/gui/GuiGraphicsExtractor.fill:(IIIII)V"
                    in screen_render_menu_background
                )
                if is_current_named
                else (
                    "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V"
                    in screen_render_panorama
                    and "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V"
                    in screen_render_menu_background
                )
            )
            and "PanoramaRenderer.render" not in screen_render_panorama
            and "renderMenuBackgroundTexture" not in screen_render_menu_background
            and "iconst_0" in title_realms_enabled
            and "ireturn" in title_realms_enabled,
        ),
        (
            "TitleScreen identifies Gaius as an independent project",
            "Gaius is independent and is not affiliated with Mojang or Microsoft."
                in title_initializer
            and "Component.literal" in title_initializer
            and "title.credits" not in title_initializer
            and "Component.translatable" in title_initializer
            and "CreditsAndAttributionScreen" not in title_screen,
        ),
        (
            "LevelLoadingScreen keeps progress UI without rebuilding the chunk grid",
            (
                "public static void renderChunks" in level_loading_render_chunks
                or "public static void extractChunksForRendering" in level_loading_render_chunks
            )
            and "0: return" in level_loading_render_chunks
            and "GuiGraphics.fill" not in level_loading_render_chunks
            and "GuiGraphicsExtractor.fill" not in level_loading_render_chunks
            and "ChunkLoadStatusView.get" not in level_loading_render_chunks,
        ),
        (
            "AbstractButton browser background uses fill instead of GUI sprite blits",
            (
                (
                    "net/minecraft/client/gui/GuiGraphicsExtractor.fill:(IIIII)V"
                    if is_current_named
                    else "net/minecraft/client/gui/GuiGraphics.fill:(IIIII)V"
                )
                in abstract_button_sprite
            )
            and "blitSprite" not in abstract_button_sprite
            and "WidgetSprites.get" not in abstract_button_sprite,
        ),
        (
            "Legacy GameRenderer throttles inventory-screen world background before renderLevel",
            is_current_named
            or (
                "BrowserOpenGL.shouldSkipWorldRenderForScreen" in game_renderer
                and "InterfaceMethod net/minecraft/util/profiling/ProfilerFiller.pop:()V" in game_renderer
                and "Method renderLevel:(Lnet/minecraft/client/DeltaTracker;)V" in game_renderer
                and "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;" in game_renderer
            ),
        ),
        (
            "GameRenderer closes stale loading screen before active world render",
            "client.levelReady.closeLoadingScreenFromWorldRender" in game_renderer
            and "LevelLoadingScreen" in game_renderer
            and "Field net/minecraft/client/Minecraft.level:Lnet/minecraft/client/multiplayer/ClientLevel;" in game_renderer
            and "Field net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;" in game_renderer
            and (
                "Field net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;"
                in game_renderer
                or (
                    is_current_named
                    and "Minecraft.gaius$getScreen" in game_renderer
                    and "Minecraft.gaius$setScreen" in game_renderer
                )
            ),
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
            and (
                "LevelRenderer.destroyBlockProgress" in client_level_destroy_block_progress
                or (
                    is_current_named
                    and "destroyingBlocks" in client_level_destroy_block_progress
                    and "destructionProgress" in client_level_destroy_block_progress
                    and "private void extractBlockDestroyAnimation(net.minecraft.client.Camera, net.minecraft.client.renderer.state.level.LevelRenderState);"
                        in level_extract_block_destroy
                    and "ClientLevel.destructionProgress" in level_extract_block_destroy
                    and "LevelRenderState.blockBreakingRenderStates" in level_extract_block_destroy
                    and "BlockBreakingRenderState" in level_extract_block_destroy
                    and "BlockDestructionProgress.getProgress" in level_extract_block_destroy
                )
            )
            and " 0: return" not in client_level_add_destroy_block_effect
            and " 0: return" not in client_level_destroy_block_progress,
        ),
        (
            "LevelRenderer compiled overlay preserves block break progress tracking",
            (
                is_current_named
                and "private void submitBlockDestroyAnimation(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.LevelRenderState);"
                    in level_destroy_block_progress
                and "LevelRenderState.blockBreakingRenderStates" in level_destroy_block_progress
                and "BlockBreakingRenderState" in level_destroy_block_progress
                and "BlockStateModel.collectParts" in level_destroy_block_progress
                and "SubmitNodeCollector.submitBreakingBlockModel" in level_destroy_block_progress
            )
            or (
                not is_current_named
                and "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);"
                in level_destroy_block_progress
                and "BlockDestructionProgress" in level_destroy_block_progress
                and "destroyingBlocks" in level_destroy_block_progress
                and "destructionProgress" in level_destroy_block_progress
                and " 0: return" not in level_destroy_block_progress
            ),
        ),
        (
            "LevelRenderer compiled overlay throttles section scheduling and guards sync rebuild off",
            (not is_current_named)
            or (
                is_current_named
                and "private void compileSections(net.minecraft.client.renderer.state.level.CameraRenderState);"
                in level_compile_sections
                and "SectionRenderDispatcher$RenderSection.compileSync" in level_compile_sections
                and "SectionRenderDispatcher$RenderSection.compileAsync" in level_compile_sections
                and re.search(
                    r"iconst_0\s*\n\s+\d+:\s+ifeq [^\n]+\n(?:.*\n){0,16}?\s+\d+:.*compileSync",
                    level_compile_sections,
                    re.DOTALL,
                )
                and "BrowserRenderScheduler.canScheduleSection" in level_extractor
                and "SectionUpdateTracker$SectionDirtyState.setNotDirty" in level_extractor
            )
            or (
                not is_current_named
                and "private void compileSections(net.minecraft.client.Camera);"
                in level_compile_sections
                and "List.size" in level_compile_sections
                and "if_icmplt" in level_compile_sections
                and "List.add" in level_compile_sections
                and "rebuildSectionAsync" in level_compile_sections
                and "compileSectionSynchronously" in level_compile_sections
                and "rebuildSectionSync" in level_compile_sections
                and "iconst_0" in level_compile_sections
                and level_compile_sections.find("iconst_0") < level_compile_sections.find("compileSectionSynchronously")
            ),
        ),
        (
            "LevelRenderer compiled overlay reuses frame time, render layers, and model-view matrix",
            level_prepare_chunk_renders.count("net/minecraft/util/Util.getMillis") == 1
            and level_prepare_chunk_renders.count(
                "dev/gaius/browser/BrowserChunkSectionLayers.values"
            ) == 1
            and "net/minecraft/client/renderer/chunk/ChunkSectionLayer.values" not in level_prepare_chunk_renders
            and "class org/joml/Matrix4f" not in level_prepare_chunk_renders
            and 'org/joml/Matrix4f."<init>":(Lorg/joml/Matrix4fc;)V'
                not in level_prepare_chunk_renders
            and "private static final net.minecraft.client.renderer.chunk.ChunkSectionLayer[] VALUES;"
                in browser_chunk_section_layers
            and "areturn" in browser_chunk_section_layers,
        ),
        (
            "SectionRenderDispatcher compiled overlay defers compilation and limits per-frame uploads",
            (
                is_current_named
                and "public void uploadTerrainBuffersToGpu();" in section_uploads
                and section_uploads.count("UberGpuBuffer.uploadStagedAllocations") == 2
                and "BrowserRenderScheduler.beginUploadPass" in section_uploads
                and "BrowserRenderScheduler.endUploadPass" in section_uploads
                and "private void schedule(net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$SectionTask);"
                    in section_dispatcher_schedule
                and "BrowserRenderScheduler.scheduleDispatcher" in section_dispatcher_schedule
                and "BrowserRenderScheduler.rememberDispatcherContinuation" in section_dispatcher_run_task
                and "BrowserRenderScheduler.finishDispatcherRun" in section_dispatcher_run_task
            )
            or (
                not is_current_named
                and "public void uploadAllPendingUploads();" in section_uploads
                and section_uploads.count("Queue.poll") >= 2
                and "Runnable.run" in section_uploads
                and "SectionMesh.close" in section_uploads
                and "if_icmpge" in section_uploads
                and "goto" in section_uploads
                and "BrowserRenderScheduler.defer" in section_dispatcher_constructor
                and "BrowserRenderScheduler.defer" in section_dispatcher_run_task
            ),
        ),
        (
            "IntegratedServer follows client display-distance options",
            (
                is_current_named
                and "protected void tickServer(java.util.function.BooleanSupplier);" in integrated_tick
                and "Options.renderDistance:()Lnet/minecraft/client/OptionInstance;"
                    in integrated_tick
                and "Options.simulationDistance:()Lnet/minecraft/client/OptionInstance;"
                    in integrated_tick
                and integrated_tick.count("Math.max:(II)I") >= 2
                and "PlayerList.setViewDistance:(I)V" in integrated_tick
                and "PlayerList.setSimulationDistance:(I)V" in integrated_tick
            )
            or (
                not is_current_named
                and "public void tickServer(java.util.function.BooleanSupplier);" in integrated_tick
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
                ]
            ),
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
            (
                is_current_named
                and "private boolean addEntityUuid(T);" in entity_uuid_add
                and "java/util/Set.add" in entity_uuid_add
                and "net/minecraft/world/entity/Entity" in entity_uuid_add
                and "java/util/UUID.randomUUID:()Ljava/util/UUID;" in entity_uuid_add
                and "net/minecraft/world/entity/Entity.setUUID" in entity_uuid_add
                and "server.entityUuidRecovered" in entity_uuid_add
                and "bipush        8" in entity_uuid_add
                and "UUID of added entity already exists: {}" in entity_uuid_add
            )
            or (
                not is_current_named
                and "private boolean addEntityUuid(T);" in entity_uuid_add
                and "java/util/Set.add" in entity_uuid_add
                and "net/minecraft/world/entity/Entity" in entity_uuid_add
                and (
                    "net/minecraft/util/Mth.createInsecureUUID" in entity_uuid_add
                    or "java/util/UUID.randomUUID:()Ljava/util/UUID;" in entity_uuid_add
                )
                and "net/minecraft/world/entity/Entity.setUUID" in entity_uuid_add
                and "server.entityUuidRecovered" in entity_uuid_add
                and "bipush        8" in entity_uuid_add
                and "UUID of added entity already exists: {}" in entity_uuid_add
            ),
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
            "MinecraftServer anchors browser spawn above the generated column",
            "server.browserFastInitialSpawn" in minecraft_initial_spawn
            and "Climate$Sampler.findSpawnPosition" in minecraft_initial_spawn
            and "ServerLevel.getMaxY" in minecraft_initial_spawn
            and "ChunkGenerator.getBaseHeight" not in minecraft_initial_spawn
            and "Heightmap$Types.MOTION_BLOCKING_NO_LEAVES" not in minecraft_initial_spawn
            and "ChunkPos.getMiddleBlockX" in minecraft_initial_spawn
            and "ChunkPos.getMiddleBlockZ" in minecraft_initial_spawn
            and "PlayerSpawnFinder.getSpawnPosInChunk" not in minecraft_initial_spawn
            and "ServerLevel.getHeightmapPos" not in minecraft_initial_spawn
            and "BlockPos.ZERO" not in minecraft_initial_spawn
        ),
        (
            "Browser spawn preparation retains neighbors and corrects unsafe saved heights",
            "Vec3.atBottomCenterOf" in player_find_spawn
            and "CompletableFuture.completedFuture" in player_find_spawn
            and "Method fixupSpawnHeight:" not in player_find_spawn
            and "PlayerSpawnFinder.getSpawnPosInChunk" not in player_find_spawn
            and "BlockPos.containing" in player_fixup_loaded_spawn
            and "Method getSpawnPosInChunk:" in player_fixup_loaded_spawn
            and "Heightmap$Types.MOTION_BLOCKING_NO_LEAVES" in player_fixup_loaded_spawn
            and "ServerLevel.getHeightmapPos" in player_fixup_loaded_spawn
            and "Vec3.atBottomCenterOf" in player_fixup_loaded_spawn
            and "PlayerSpawnFinder.gaius$fixupLoadedSpawn" in prepare_spawn_tick
            and prepare_spawn_tick.rfind(
                "CompletableFuture.isDone",
                0,
                prepare_spawn_tick.find("PlayerSpawnFinder.gaius$fixupLoadedSpawn"),
            ) >= 0
            and "TicketType.PLAYER_SPAWN" in prepare_spawn_load_chunks
            and "iconst_1" in prepare_spawn_load_chunks
            and "iconst_3" not in prepare_spawn_load_chunks
            and "ServerChunkCache.addTicketWithRadius" in prepare_spawn_load_chunks
            and "ChunkStatus.FULL" in prepare_spawn_load_chunks
            and "ServerChunkCache.getChunkFutureMainThread" in prepare_spawn_load_chunks
            and "ServerChunkCache$MainThreadExecutor.managedBlock" not in prepare_spawn_load_chunks
            and "ServerChunkCache.addTicketAndLoadWithRadius" not in prepare_spawn_load_chunks
            and "ChunkHolder.scheduleChunkGenerationTask" in server_chunk_future_main_thread
            and "ServerChunkCache$MainThreadExecutor.managedBlock" not in server_chunk_future_main_thread
            and "iconst_1" in prepare_spawn_keep_alive
            and "iconst_3" not in prepare_spawn_keep_alive
            and "ServerChunkCache.addTicketWithRadius" in prepare_spawn_keep_alive
            and "iconst_0" in prepare_spawn_player
            and "iconst_3" not in prepare_spawn_player
            and "ServerLevel.waitForEntities" in prepare_spawn_player,
        ),
        (
            "Worker-local configuration cannot time out while its first chunk is generated",
            "BrowserIntegratedServerMain.isWorkerServer" in server_common_is_singleplayer_owner
            and "iconst_1" in server_common_is_singleplayer_owner
            and "MinecraftServer.isSingleplayerOwner" in server_common_is_singleplayer_owner
            and server_common_is_singleplayer_owner.find("BrowserIntegratedServerMain.isWorkerServer")
                < server_common_is_singleplayer_owner.find("MinecraftServer.isSingleplayerOwner"),
        ),
        (
            "Integrated server resets worldgen clock before a tick and checkpoints after work",
            "BrowserWorldgenScheduler.checkpoint" in minecraft_run_server
            and "BrowserWorldgenScheduler.beginServerWorkTurn" in minecraft_run_server
            and "processPacketsAndTick:(Z)V" in minecraft_run_server
            and minecraft_run_server.find("BrowserWorldgenScheduler.beginServerWorkTurn")
                < minecraft_run_server.find("processPacketsAndTick:(Z)V")
            and minecraft_run_server.find("BrowserWorldgenScheduler.checkpoint")
                > minecraft_run_server.find("processPacketsAndTick:(Z)V")
            and "BrowserWorldgenScheduler.checkpoint" not in minecraft_process_packets_and_tick
            and "BrowserWebSocketChannel.pumpAll" in minecraft_process_packets_and_tick
            and "BrowserIntegratedServerMain.tickIntegratedServerDistances"
                in minecraft_process_packets_and_tick
            and "PacketProcessor.processQueuedPackets" in minecraft_process_packets_and_tick
            and minecraft_process_packets_and_tick.find("BrowserWebSocketChannel.pumpAll")
                < minecraft_process_packets_and_tick.find(
                    "BrowserIntegratedServerMain.tickIntegratedServerDistances")
            and minecraft_process_packets_and_tick.find(
                    "BrowserIntegratedServerMain.tickIntegratedServerDistances")
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
            "Each chunk acknowledgement applies singleplayer distance backpressure",
            "BrowserIntegratedServerMain.minimumServerViewDistance"
                in chunk_map_set_view_distance
            and "BrowserIntegratedServerMain.minimumServerViewDistance"
                in chunk_map_get_player_view_distance
            and "bipush        32" in chunk_map_set_view_distance
            and "Mth.clamp" in chunk_map_set_view_distance
            and "serverViewDistance" in chunk_map_get_player_view_distance
            and "Mth.clamp" in chunk_map_get_player_view_distance
            and "BrowserIntegratedServerMain.acknowledgeChunkBatch" in server_game_chunk_batch
            and "PlayerChunkSender.onChunkBatchReceivedByClient" in server_game_chunk_batch
            and server_game_chunk_batch.find("PlayerChunkSender.onChunkBatchReceivedByClient")
                < server_game_chunk_batch.find("acknowledgeChunkBatch")
            and "BrowserIntegratedServerMain.recordChunkBatchSent" in player_send_next_chunks
            and "ClientboundChunkBatchFinishedPacket" in player_send_next_chunks
            and player_send_next_chunks.find("recordChunkBatchSent")
                > player_send_next_chunks.find("ClientboundChunkBatchFinishedPacket")
            and "java/util/Deque.addLast" in browser_record_chunk_batch
            and "java/util/Deque.pollFirst" in browser_acknowledge_chunk_batch
            and "chunk-batch-ack-without-send" in browser_acknowledge_chunk_batch
            and "acknowledgedChunkCount" in browser_acknowledge_chunk_batch
            and "Method activeViewDistanceAcknowledged:()Z"
                in browser_acknowledge_chunk_batch
            and "configuredDistancesActive" in browser_acknowledge_chunk_batch
            and "Math.min" in browser_acknowledge_chunk_batch
            and "System.currentTimeMillis" in browser_acknowledge_chunk_batch
            and "distanceAdvancePending" in browser_acknowledge_chunk_batch
            and "applyActiveDistances" in browser_acknowledge_chunk_batch
            and "advanceConfiguredDistances" in browser_acknowledge_chunk_batch
            and "distanceAdvancePending" in browser_tick_distances
            and "Method activeViewDistanceAcknowledged:()Z" in browser_tick_distances
            and "System.currentTimeMillis" in browser_tick_distances
            and "advanceConfiguredDistances" in browser_tick_distances
            and "activeViewDistance" in browser_active_view_acknowledged
            and "acknowledgedChunkCount" in browser_active_view_acknowledged
            and "Math.max" in browser_active_view_acknowledged
            and "activeViewDistance" in browser_advance_distances
            and "applyActiveDistances" in browser_advance_distances
            and "BrowserIntegratedServerMain.advanceConfiguredDistances"
                not in minecraft_run_server,
        ),
        (
            "Compiled Server Worker enforces bounded worldgen slices on its server thread",
            "Method requestYield:(II)V" in worldgen_checkpoint
            and "Thread.yield" not in worldgen_checkpoint
            and "BrowserWebSocketChannel.pumpAll" not in worldgen_checkpoint
            and "Method nowMillis:()D" in worldgen_pulse
            and "Method requestYield:(II)V" in worldgen_pulse
            and "Method nowMillis:()D" in worldgen_begin_server_tick
            and "deadlineMillis" in worldgen_begin_server_tick
            and "Method nowMillis:()D" in worldgen_begin_task
            and "activeWorkStartedAtMillis" in worldgen_begin_task
            and "activeWorkElapsedMillis" in worldgen_end_task
            and "taskWorkDepth" in worldgen_end_task
            and "TModernRuntimeSupport.yieldToEventLoop:(I)V" in worldgen_request_yield
            and "java/lang/Thread.sleep:(J)V" not in worldgen_request_yield
            and "BrowserIntegratedServerMain.pumpUrgentPackets" in worldgen_request_yield
            and "Method networkQueueDepth:()I" in worldgen_request_yield
            and "java/lang/Thread.currentThread:()Ljava/lang/Thread;"
                not in worldgen_request_yield
            and "java/lang/Thread.interrupt:()V" not in worldgen_request_yield
            and "TModernRuntimeSupport.yieldToEventLoop:(I)V"
                in worldgen_yield_reentrant
            and "Method drainUrgentPackets:()Z" in browser_pump_urgent_packets
            and "BrowserWebSocketChannel.pumpAll" in browser_drain_urgent_packets
            and "MinecraftServer.packetProcessor" in browser_drain_urgent_packets
            and "PacketProcessor.processQueuedPackets" in browser_drain_urgent_packets,
        ),
        (
            "Integrated server pumps pending input while awaiting chunk futures",
            "BrowserIntegratedServerMain.pumpUrgentPacketsIfPending" in minecraft_poll_task
            and "BrowserWorldgenScheduler.beginTaskWork" in minecraft_poll_task
            and "String MinecraftServer.pollTask" in minecraft_poll_task
            and "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I"
                in minecraft_poll_task
            and "BrowserWorldgenScheduler.endTaskWork:(I)V" in minecraft_poll_task
            and minecraft_poll_task.count("BrowserWorldgenScheduler.endTaskWork") >= 2
            and minecraft_poll_task.find(
                "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I"
            )
                < minecraft_poll_task.find("BrowserIntegratedServerMain.pumpUrgentPacketsIfPending")
            and "BrowserWebSocketChannel.hasPendingInput" in browser_pump_pending_packets
            and "pumpUrgentPackets:()V" in browser_pump_pending_packets
            and "BrowserWebSocketChannel.pumpAll" not in browser_stage_network_input
            and "Method signalIntegratedServerNetworkInput:()V"
                in browser_stage_network_input
            and "MinecraftServer.packetProcessor" not in browser_stage_network_input
            and "PacketProcessor.processQueuedPackets" not in browser_stage_network_input
            and "Method scheduleNetworkInputTask:(ZZ)Z" in browser_signal_network_input
            and 'net/minecraft/server/TickTask."<init>"' in browser_schedule_network_input
            and "-2147483648" in browser_schedule_network_input
            and "MinecraftServer.getTickCount" not in browser_schedule_network_input
            and "MinecraftServer.schedule" in browser_schedule_network_input
            and "MinecraftServer.execute" not in browser_schedule_network_input
            and "LockSupport.unpark" in browser_schedule_network_input
            and "Method drainUrgentPackets:()Z" in browser_run_scheduled_network_input
            and "Method reportRuntimeEvent" in browser_run_scheduled_network_input
            and "java/util/Queue.add" in blockable_event_loop_schedule
            and "Method getRunningThread" in blockable_event_loop_schedule
            and "java/util/concurrent/locks/LockSupport.unpark"
                in blockable_event_loop_schedule
            and "java/lang/Runnable.run" not in blockable_event_loop_schedule
            and "Method doRunTask" not in blockable_event_loop_schedule
            and "Method execute" not in blockable_event_loop_schedule,
        ),
        (
            "Compiled structure templates retain NBT parsing behind native gzip",
            "BrowserGzip.readCompressedNbt" in structure_template_read_stream
            and "NbtIo.readCompressed" not in structure_template_read_stream
            and "java/io/InputStream.readAllBytes:()[B" in browser_gzip_read_nbt
            and "java/lang/Thread.sleep:(J)V" in browser_gzip_read_nbt
            and "NbtIo.read:(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)"
                in browser_gzip_read_nbt
            and "NbtIo.readCompressed:(Ljava/io/InputStream;Lnet/minecraft/nbt/NbtAccounter;)"
                in browser_gzip_read_nbt,
        ),
        (
            "ChunkGeneratorStructureState keeps ring candidates without blocking biome searches",
            (
                (
                    "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$0"
                    in browser_ring_position
                    if is_current_named
                    else "private net.minecraft.world.level.ChunkPos lambda$generateRingPositions$5"
                    in browser_ring_position
                )
                and "net/minecraft/world/level/ChunkPos.\"<init>\":(II)V"
                    in browser_ring_position
                and "BiomeSource.findBiomeHorizontal" not in browser_ring_position
                and "Climate$Sampler" not in browser_ring_position
            ),
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
            "NoiseBasedChunkGenerator terrain fill stays synchronous within its task stage",
            "BrowserWorldgenScheduler.checkpoint" not in noise_do_fill
            and "BrowserWorldgenScheduler.pulse" not in noise_do_fill
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
            "Runtime terrain carvers stay synchronous within their task stage",
            "BrowserWorldgenScheduler.pulse" not in noise_apply_carvers
            and "ConfiguredWorldCarver.carve" in noise_apply_carvers
            and "BiomeGenerationSettings.getCarvers" in noise_apply_carvers,
        ),
        (
            "NoiseChunk interpolation stays synchronous below the task layer",
            "BrowserWorldgenScheduler.pulse" not in noise_fill_slice
            and "NoiseInterpolator.fillArray" in noise_fill_slice
            and "BrowserWorldgenScheduler.pulse" not in noise_fill_direct
            and "DensityFunction.compute" in noise_fill_direct
            and "BrowserWorldgenScheduler.pulse" not in noise_select_cell_yz,
        ),
        (
            "NoiseChunk interpolation uses a cached array in per-block update loops",
            "BrowserNoiseInterpolator.lerp3" in noise_interpolator_compute
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
            "Biome climate tree search stays synchronous inside the sampling hot path",
            "BrowserWorldgenScheduler.pulse" not in climate_rtree_search
            and "Climate$DistanceMetric.distance" in climate_rtree_search
            and "Climate$RTree$Node.search" in climate_rtree_search,
        ),
        (
            "Surface rules stay synchronous within their task stage",
            "BrowserWorldgenScheduler.pulse" not in surface_build
            and "SurfaceRules$SurfaceRule.tryApply" in surface_build
            and "SurfaceRules$Context.updateY" in surface_build,
        ),
        (
            "Surface rule contexts reuse one lazy biome supplier per chunk",
            "private final dev.gaius.browser.BrowserSurfaceBiomeSupplier browserBiomeSupplier;"
                in surface_rules_context
            and "dev/gaius/browser/BrowserSurfaceBiomeSupplier.\"<init>\""
                in surface_context_constructor
            and "Field browserBiomeSupplier:Ldev/gaius/browser/BrowserSurfaceBiomeSupplier;"
                in surface_context_constructor
            and "Field browserBiomeSupplier:Ldev/gaius/browser/BrowserSurfaceBiomeSupplier;"
                in surface_context_update_y
            and "dev/gaius/browser/BrowserSurfaceBiomeSupplier.reset:(III)V"
                in surface_context_update_y
            and (
                (
                    is_current_named
                    and "Field biome:Lnet/minecraft/core/Holder;"
                        in surface_context_update_y
                    and "Field browserBiomeSupplier:Ldev/gaius/browser/BrowserSurfaceBiomeSupplier;"
                        in surface_context_get_biome
                    and "dev/gaius/browser/BrowserSurfaceBiomeSupplier.get:()Lnet/minecraft/core/Holder;"
                        in surface_context_get_biome
                    and surface_context_get_biome.count(
                        "Field biome:Lnet/minecraft/core/Holder;"
                    ) >= 3
                )
                or (
                    not is_current_named
                    and "Field biome:Ljava/util/function/Supplier;"
                        in surface_context_update_y
                )
            )
            and "com/google/common/base/Suppliers.memoize"
                not in surface_context_update_y + surface_context_get_biome
            and "InvokeDynamic" not in surface_context_update_y + surface_context_get_biome,
        ),
        (
            "Surface lazy conditions cache primitive results with int generation counters",
            "int browserUpdateXZ;" in surface_rules_context
            and "int browserUpdateY;" in surface_rules_context
            and "private int browserLastUpdate;" in surface_rules_lazy
            and "private boolean browserResult;" in surface_rules_lazy
            and "private boolean browserResultInitialized;" in surface_rules_lazy
            and "browserContextLastUpdate:()I" in surface_lazy_test
            and "Field browserLastUpdate:I" in surface_lazy_test
            and "Field browserResult:Z" in surface_lazy_test
            and "Field browserResultInitialized:Z" in surface_lazy_test
            and "java/lang/Boolean" not in surface_lazy_test
            and "lcmp" not in surface_lazy_test
            and surface_context_update_xz.count("Field browserUpdateXZ:I") == 2
            and surface_context_update_xz.count("Field browserUpdateY:I") == 2
            and surface_context_update_y.count("Field browserUpdateY:I") == 2
            and "Field net/minecraft/world/level/levelgen/SurfaceRules$Context.browserUpdateXZ:I"
                in surface_lazy_xz_counter
            and "Field net/minecraft/world/level/levelgen/SurfaceRules$Context.browserUpdateY:I"
                in surface_lazy_y_counter,
        ),
        (
            "Surface rule sequences use indexed access without iterator allocation",
            "java/util/List.get" in surface_sequence_try_apply
            and "java/util/List.size" in surface_sequence_try_apply
            and "java/util/Iterator" not in surface_sequence_try_apply
            and "SurfaceRules$SurfaceRule.tryApply" in surface_sequence_try_apply,
        ),
        (
            "Concrete density transformers bypass resumable PureTransformer accessors",
            len(density_transformer_sections) == 3
            and all(
                "Field input:Lnet/minecraft/world/level/levelgen/DensityFunction;" in compute
                and "DensityFunction.compute" in compute
                and "dev/gaius/browser/BrowserDensityFunctions" in compute
                and "PureTransformer.input" not in compute
                and "PureTransformer.transform" not in compute
                and "Field input:Lnet/minecraft/world/level/levelgen/DensityFunction;" in fill
                and "DensityFunction.fillArray" in fill
                and "dev/gaius/browser/BrowserDensityFunctions" in fill
                and "PureTransformer.input" not in fill
                and "PureTransformer.transform" not in fill
                for compute, fill in density_transformer_sections
            ),
        ),
        (
            "Immutable worldgen records retain structural equality with cached hash codes",
            "private transient int browserHashCode;" in density_mul_or_add
            and "private transient boolean browserHashCodeComputed;" in density_mul_or_add
            and "Field browserHashCodeComputed:Z" in density_mul_or_add_hash
            and "Field browserHashCode:I" in density_mul_or_add_hash
            and density_mul_or_add_hash.count("ireturn") >= 2
            and "invokedynamic" in density_mul_or_add_hash,
        ),
        (
            "Biome decoration stays synchronous within its task stage",
            "BrowserWorldgenScheduler.pulse" not in chunk_apply_biome_decoration
            and "PlacedFeature.placeWithBiomeCheck" in chunk_apply_biome_decoration
            and "StructureManager.shouldGenerateStructures" in chunk_apply_biome_decoration,
        ),
        (
            "Structure generation and references stay synchronous within task stages",
            "BrowserWorldgenScheduler.pulse" not in chunk_create_structures_lambda
            and "Method tryGenerateStructure" in chunk_create_structures_lambda
            and "BrowserWorldgenScheduler.pulse" not in chunk_create_references
            and "WorldGenLevel.getChunk" in chunk_create_references
            and "StructureManager.addReferenceForStructure" in chunk_create_references,
        ),
        (
            "World carvers stay synchronous below the task layer",
            "BrowserWorldgenScheduler.pulse" not in world_carve_ellipsoid
            and "Method carveBlock" in world_carve_ellipsoid
            and "CarvingMask.set" in world_carve_ellipsoid,
        ),
        (
            "Lighting propagation stays synchronous within its task stage",
            "BrowserWorldgenScheduler.pulse" not in light_propagate_increases
            and "BrowserWorldgenScheduler.pulse" not in light_propagate_decreases
            and "Method propagateIncrease" in light_propagate_increases
            and "Method propagateDecrease" in light_propagate_decreases,
        ),
        (
            "LevelChunkSection biome sampling stays synchronous below the task layer",
            "BrowserWorldgenScheduler.checkpoint" not in section_fill_biomes
            and "BrowserWorldgenScheduler.pulse" not in section_fill_biomes
            and "BiomeResolver.getNoiseBiome" in section_fill_biomes
            and "PalettedContainer.getAndSetUnchecked" in section_fill_biomes,
        ),
        (
            "ChunkGenerationTask keeps layer claims synchronous with bounded cooperative scans",
            "BrowserWorldgenScheduler.checkpoint" not in generation_run_until_wait
            and "Method scheduleNextLayer:()V" in generation_run_until_wait
            and (
                (
                    is_current_named
                    and generation_run_until_wait.count(
                        "BrowserWorldgenScheduler.pulse"
                    ) == 1
                    and generation_run_until_wait.count(
                        "BrowserWorldgenScheduler.beginTaskWork"
                    ) == 1
                    and "String ChunkGenerationTask.runUntilWait"
                        in generation_run_until_wait
                    and "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I"
                        in generation_run_until_wait
                    and "BrowserWorldgenScheduler.endTaskWork:(I)V"
                        in generation_run_until_wait
                    and generation_run_until_wait.count(
                        "BrowserWorldgenScheduler.endTaskWork"
                    ) >= 3
                    and generation_run_until_wait.count(
                        "BrowserWorldgenScheduler.beginServerWorkTurn"
                    ) == 0
                    and generation_wait_for_scheduled_layer.count(
                        "BrowserWorldgenScheduler.pulse"
                    ) == 1
                    and generation_schedule_layer.count(
                        "BrowserWorldgenScheduler.pulse"
                    ) == 2
                    and generation_can_load_without_generation.count(
                        "BrowserWorldgenScheduler.pulse"
                    ) == 2
                    and "BrowserWorldgenScheduler.checkpoint"
                        not in generation_schedule_layer
                    and "BrowserWorldgenScheduler.checkpoint"
                        not in generation_wait_for_scheduled_layer
                    and "BrowserWorldgenScheduler.checkpoint"
                        not in generation_can_load_without_generation
                )
                or (
                    not is_current_named
                    and "BrowserWorldgenScheduler.pulse"
                        not in generation_run_until_wait
                    and "BrowserWorldgenScheduler.pulse"
                        not in generation_wait_for_scheduled_layer
                )
            ),
        ),
        (
            "Current browser RegionFileStorage bounds and closes its LRU cache",
            not is_current_named
            or (
                "bipush        16" in region_get_file
                and "sipush        256" not in region_get_file
                and "Long2ObjectLinkedOpenHashMap.removeLast" in region_get_file
                and "RegionFile.close" in region_get_file
            ),
        ),
        (
            "Browser ChunkTaskDispatcher preserves executor-future queue isolation",
            "InterfaceMethod java/util/List.stream:()Ljava/util/stream/Stream;"
                in dispatcher_schedule_for_execution
            and "InterfaceMethod java/util/stream/Stream.map:"
                in dispatcher_schedule_for_execution
            and "CompletableFuture.allOf" in dispatcher_schedule_for_execution
            and "CompletableFuture.thenAccept" in dispatcher_schedule_for_execution
            and "BrowserChunkTaskDispatcher" not in dispatcher_schedule_for_execution,
        ),
        (
            "Compiled chunk queue chooses nearest equal-priority work from the latest player center",
            "BrowserChunkTaskPriority.chooseNext" in priority_queue_pop
            and "Long2ObjectLinkedOpenHashMap.firstLongKey" not in priority_queue_pop
            and "Long2ObjectLinkedOpenHashMap.remove:(J)" in priority_queue_pop
            and "Long2ObjectLinkedOpenHashMap.removeFirst" not in priority_queue_pop
            and "BrowserChunkTaskPriority.recordPlayerPosition" in chunk_map_update_player_pos
            and "LongIterator.nextLong" in browser_chunk_task_priority_class
            and "Math.floor" in browser_chunk_task_priority_class,
        ),
        (
            "Mining hit sounds remain periodic and browser-audible",
            "SoundType.getHitSound" in multiplayer_continue_destroy
            and "SoundManager.play" in multiplayer_continue_destroy
            and "float 4.0f" in multiplayer_continue_destroy
            and "float 8.0f" not in multiplayer_continue_destroy,
        ),
        (
            "Legacy renderLevel updates the shared block target after refreshing its camera",
            is_current_named
            or (
                legacy_game_render_level is not None
                and "Method pick:(F)V" in legacy_game_render_level
                and "Method extractCamera:(F)V" in legacy_game_render_level
                and "BrowserTargeting.stabilizeBlockHit" in legacy_game_render_level
                and "Minecraft.hitResult" in legacy_game_render_level
                and "Method shouldRenderBlockOutline:()Z" in legacy_game_render_level
                and legacy_game_render_level.find("Method pick:(F)V")
                    < legacy_game_render_level.find("Method shouldRenderBlockOutline:()Z")
                and legacy_game_render_level.find("Method shouldRenderBlockOutline:()Z")
                    < legacy_game_render_level.find("Method extractCamera:(F)V")
                and legacy_game_render_level.find("Method extractCamera:(F)V")
                    < legacy_game_render_level.find("BrowserTargeting.stabilizeBlockHit")
            ),
        ),
        (
            "Current named renderFrame defers its one pick until camera extraction",
            (not is_current_named)
            or (
                minecraft_render_frame is not None
                and current_game_renderer_extract is not None
                and current_render_frame_contract
                and "Method pick:(F)V" not in minecraft_render_frame
            ),
        ),
        (
            "Current named GameRenderer refreshes targeting after its render camera",
            (not is_current_named)
            or (
                current_game_renderer_extract is not None
                and re.findall(
                r"BrowserTargeting\.([A-Za-z0-9_$]+)",
                game_renderer,
                ) == ["refreshFramePick"]
                and game_renderer.count("BrowserTargeting.refreshFramePick") == 1
                and current_game_renderer_extract.count("BrowserTargeting.refreshFramePick") == 1
                and current_game_renderer_extract.find("Method extractCamera:")
                    < current_game_renderer_extract.find("BrowserTargeting.refreshFramePick")
                and current_game_renderer_extract.find("BrowserTargeting.refreshFramePick")
                    < current_game_renderer_extract.find("LevelExtractor.extract")
                and re.search(r"fload\s+6", current_targeting_bridge) is not None
                and re.search(r"fload\s+5", current_targeting_bridge) is None
                and "raycastHitResult" not in game_renderer
            ),
        ),
        (
            "Current named SectionTaskDynamicQueue uses two priority queues and bounded helpers",
            (not is_current_named) or current_section_queue_contract,
        ),
        (
            "Legacy CompileTaskDynamicQueue remains profile-resolvable",
            is_current_named
            or (
                legacy_compile_task_queue is not None
                and "class net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue"
                    in legacy_compile_task_queue
            ),
        ),
        (
            "Compiled browser targeting uses live camera angles without a stale-hit cache",
            "private static net.minecraft.world.phys.HitResult pickFromRenderCamera"
                in browser_targeting_class
            and "Camera.entity" in browser_targeting_class
            and "Camera.position" in browser_targeting_class
            and "Camera.forwardVector" in browser_targeting_class
            and "ClientLevel.clip" in browser_targeting_class
            and "ProjectileUtil.getEntityHitResult" in browser_targeting_class
            and "EntitySelector.CAN_BE_PICKED" in browser_targeting_class
            and "LocalPlayer.raycastHitResult" not in browser_targeting_class
            and "lastForward" not in browser_targeting_class
            and "hasLastCamera" not in browser_targeting_class,
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
            and "listedResources" in vanilla_pack_resources
            and "Method lowerBound:([Ljava/lang/String;Ljava/lang/String;)I"
                in vanilla_listed_resources
            and "java/lang/String.compareTo:(Ljava/lang/String;)I"
                in vanilla_resource_lower_bound,
        ),
        (
            "BrowserFilePersistence compiled overlay seeds user-editable browser defaults",
            "seedDefaultOptions" in browser_file_persistence_class
            and "enforcePerformanceOptions" not in browser_file_persistence_class
            and "storage-default-options" in browser_file_persistence_constants
            and "currentDataVersion" in browser_file_persistence_class
            and "runtimeWorldVersion" in browser_file_persistence_class
            and "runtimeStoragePrefix" in browser_file_persistence_class
            and "runtimeStorageConfigurationSignature" in browser_file_persistence_class
            and "version:" in browser_file_persistence_constants
            # 4671 remains in the class solely as the explicitly named legacy
            # options payload.  Dynamic defaults are proven by the
            # currentDataVersion/runtimeWorldVersion bytecode above; absence of
            # the legacy concat recipe is therefore neither required nor valid.
            and "gaius.fs.v1:" in browser_file_persistence_constants
            and "gaius-fs-v2-1.21.11" in browser_file_persistence_constants
            and "gaius-fs-v2-26.2" in browser_file_persistence_constants
            and "regions-v2-1.21.11" in browser_file_persistence_constants
            and "regions-v2-26.2" in browser_file_persistence_constants
            and "migrateLegacyDefaultOptions" in browser_file_persistence_class
            and "renderDistance:6" in browser_file_persistence_constants
            and "simulationDistance:4" in browser_file_persistence_constants
            and "entityDistanceScaling:0.5" in browser_file_persistence_constants
            and "maxFps:260" in browser_file_persistence_constants
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
            "Browser persistence restores only title metadata or the active server world",
            "shouldRestoreAtStartup" in browser_file_persistence_class
            and "activeServerWorldId" in browser_file_persistence_class
            and "level.dat_old" in browser_file_persistence_constants
            and "data/minecraft/world_gen_settings.dat" in browser_file_persistence_class,
        ),
        (
            "Compiled browser persistence creates transient world-list session locks",
            "ensureBrowserSessionLock" in browser_file_persistence_class
            and "session.lock" in browser_file_persistence_constants
            and "writeVirtualFile" in browser_file_persistence_class,
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
            (
                is_current_named
                and "long 5l" in level_load_tracker_clinit
                and "Timed out while waiting for initial level loading packets in the browser"
                    in waiting_for_server_tick
                and "loadingPacketsReceived" in waiting_for_server_tick
            )
            or (
                not is_current_named
                and "ldc2_w        #156                // long 5l" in level_load_tracker_clinit
                and "Timed out while waiting for initial level loading packets in the browser"
                    in waiting_for_server_tick
                and "loadingPacketsReceived" in waiting_for_server_tick
            ),
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
            and (
                "Minecraft.screen" in client_packet_tick
                or (
                    "Minecraft.gaius$getScreen" in client_packet_tick
                    and "Minecraft.gaius$setScreen" in client_packet_tick
                )
            )
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
