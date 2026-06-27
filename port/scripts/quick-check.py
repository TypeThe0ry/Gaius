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
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PORT = ROOT / "port"
TARGET = PORT / "target"
DIST = PORT / "web" / "dist"
OVERLAYS = PORT / "work" / "overlays"
OPENGL_BRIDGE = PORT / "overrides" / "libraries" / "lwjgl-opengl" / "src" / "main" / "java" / "org" / "lwjgl" / "opengl" / "BrowserOpenGL.java"


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
    latest_full = latest(TARGET / "build-teavm*.log")
    latest_overlay = latest(TARGET / "build-overlays*.log")
    print(f"classes.js: {fmt_time(classes_js)} size={classes_js.stat().st_size if classes_js.exists() else 0}")
    print(f"classes.js.map: {fmt_time(classes_map)} size={classes_map.stat().st_size if classes_map.exists() else 0}")
    print(f"latest full build: {rel(latest_full) if latest_full else 'missing'} {fmt_time(latest_full) if latest_full else ''}")
    print(f"latest overlay build: {rel(latest_overlay) if latest_overlay else 'missing'} {fmt_time(latest_overlay) if latest_overlay else ''}")
    print(f"BrowserOpenGL.java: {fmt_time(OPENGL_BRIDGE)}")
    if latest_overlay and classes_js.exists() and latest_overlay.stat().st_mtime > classes_js.stat().st_mtime:
        print("WARNING: overlay patches are newer than classes.js; full TeaVM output is stale.")
    if latest_full and classes_js.exists() and latest_full.stat().st_mtime > classes_js.stat().st_mtime + 60:
        print("WARNING: latest full build log is newer than classes.js by more than 60s.")
    if latest_overlay and OPENGL_BRIDGE.exists() and OPENGL_BRIDGE.stat().st_mtime > latest_overlay.stat().st_mtime:
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
    ]
    for name, ok in checks:
        print(f"{'OK' if ok else 'FAIL'} {name}")


def check_overlay_bytecode() -> None:
    section("Overlay bytecode checks")
    client_cp = OVERLAYS / "client-named-1.21.11-gaius.jar"
    netty_common_cp = OVERLAYS / "library-patches" / "netty-common"
    netty_cp = OVERLAYS / "library-patches" / "netty-transport"
    classlib_cp = OVERLAYS / "classlib-patches"
    lwjgl_opengl_cp = OVERLAYS / "library-classes" / "lwjgl-opengl"

    packet_encoder = run_javap(client_cp, "net.minecraft.network.PacketEncoder")
    packet_bundle_unpacker = run_javap(client_cp, "net.minecraft.network.PacketBundleUnpacker")
    varint_prepender = run_javap(client_cp, "net.minecraft.network.Varint21LengthFieldPrepender")
    cipher_encoder = run_javap(client_cp, "net.minecraft.network.CipherEncoder")
    compression_encoder = run_javap(client_cp, "net.minecraft.network.CompressionEncoder")
    class_tree_id_registry = run_javap(client_cp, "net.minecraft.util.ClassTreeIdRegistry")
    synched_entity_data = run_javap(client_cp, "net.minecraft.network.syncher.SynchedEntityData")
    gl_const = run_javap(client_cp, "com.mojang.blaze3d.opengl.GlConst")
    texture_format = run_javap(client_cp, "com.mojang.blaze3d.textures.TextureFormat")
    mac_address = run_javap(netty_common_cp, "io.netty.util.internal.MacAddressUtil")
    recycler = run_javap(netty_common_cp, "io.netty.util.Recycler")
    default_channel_id = run_javap(netty_cp, "io.netty.channel.DefaultChannelId")
    channel_handler_mask = run_javap(netty_cp, "io.netty.channel.ChannelHandlerMask")
    throwable = run_javap(classlib_cp, "org.teavm.classlib.java.lang.TThrowable")
    browser_opengl = run_javap(lwjgl_opengl_cp, "org.lwjgl.opengl.BrowserOpenGL")

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
