#!/usr/bin/env python3
"""Render-backend (work package P3) bytecode assertions for a Minecraft 26.3 overlay build.

Reads the output of ``build-overlays.sh`` for profile 26.3 (``port/work/overlays/26.3``) and
checks with ``javap`` that every P3 patch landed on the renderpearl classes, that no patch wrote
a stale 26.2 ``com/mojang/blaze3d`` entry, and that the LWJGL 3.4.3 OpenGL overlay delegates the
entry points 26.3 added.  When a 26.2 overlay directory exists it also checks that the 3.4.1
OpenGL overlay has no such delegate.  Works on a bring-up build (``GAIUS_BRINGUP=1``): the P3
patches are no longer on the bring-up list.

    python port/scripts/quickcheck/profile_263_render.py
    python port/scripts/quickcheck/profile_263_render.py --overlay-dir DIR [--overlay-262-dir DIR]

Exit status: 0 pass, 1 assertion failure, 2 missing prerequisite.  ``run()`` returns the list of
failures for a caller such as quick-check.py.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PROFILE = "26.3"

RP = "com/mojang/renderpearl"
GL = f"{RP}/backend/opengl"

# Entries MinecraftClientPatcher.main() used to write under their 26.2 names (render-backend
# notes V.3) plus the 26.2 targets of the dropped M262 patches.  None may exist in a 26.3 jar.
STALE_ENTRIES = (
    "com/mojang/blaze3d/platform/GLX.class",
    "com/mojang/blaze3d/opengl/GlDebug.class",
    "com/mojang/blaze3d/opengl/GlConst.class",
    "com/mojang/blaze3d/opengl/GlStateManager.class",
    "com/mojang/blaze3d/opengl/GlRenderPipeline.class",
    "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class",
    "com/mojang/blaze3d/systems/CommandEncoder.class",
    "com/mojang/blaze3d/opengl/GlBuffer$Direct$1.class",
    "com/mojang/blaze3d/vulkan/VulkanBackend.class",
    "com/mojang/blaze3d/vulkan/VulkanDebug.class",
)
STALE_PREFIXES = ("com/mojang/blaze3d/opengl/", "com/mojang/blaze3d/vulkan/")

# Every class a P3 patch writes (MinecraftClientPatcher, Minecraft262BrowserPatcher,
# RenderPatches263) plus the LWJGL 3.4.3 entry points; the JVM verifier links each of them.
VERIFIED_CLASSES = (
    "net/minecraft/client/PreferredGraphicsApi",
    f"{RP}/backend/vulkan/VulkanBackend",
    f"{GL}/GlBackend",
    f"{GL}/GlBuffer$Direct",
    f"{GL}/GlBuffer$Direct$1",
    "com/mojang/blaze3d/vertex/BufferBuilder",
    "net/minecraft/client/renderer/texture/TextureAtlas",
    "net/minecraft/client/renderer/texture/SpriteContents",
    "net/minecraft/client/renderer/GameRenderer",
    "net/minecraft/client/gui/screens/options/VideoSettingsScreen",
    f"{GL}/GlHeuristics",
    "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs",
    "com/mojang/blaze3d/platform/MacosUtil",
    f"{GL}/GlDebug",
    f"{GL}/GlConst",
    f"{GL}/GlStateManager",
    f"{GL}/GlDevice",
    f"{GL}/GlCommandEncoder",
    f"{GL}/GlTransientMemory$PersistentMapping",
    f"{GL}/GlTransientMemory$Fallback",
    "org/lwjgl/opengl/GL30C",
    "org/lwjgl/opengl/GL11C",
    "org/lwjgl/opengl/BrowserOpenGLIndexed",
)

VERIFIER_SOURCE = """
public final class P3RenderClassVerifier {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = P3RenderClassVerifier.class.getClassLoader();
        int verified = 0;
        for (String name : args) {
            Class<?> type = Class.forName(name, false, loader);
            // Reflection links the class, which runs the bytecode verifier (-Xverify:all).
            type.getDeclaredMethods();
            type.getDeclaredFields();
            verified++;
        }
        System.out.println("P3_VERIFIED " + verified);
    }
}
"""


def _native(value: str) -> Path:
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value)


def resolve_javap() -> Path | None:
    for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME"):
        home = os.environ.get(variable)
        if not home:
            continue
        for name in ("javap.exe", "javap") if os.name == "nt" else ("javap", "javap.exe"):
            candidate = _native(home) / "bin" / name
            if candidate.is_file():
                return candidate
    found = shutil.which("javap")
    return Path(found) if found else None


class Javap:
    """Runs javap once per batch of classes and splits the output per class."""

    def __init__(self, javap: Path, classpath: Path):
        self.javap = javap
        self.classpath = classpath
        self.cache: dict[str, str] = {}

    def load(self, *classes: str) -> None:
        missing = [name for name in classes if name not in self.cache]
        if not missing:
            return
        result = subprocess.run(
            [str(self.javap), "-classpath", str(self.classpath), "-c", "-p", "-constants",
             *[name.replace("/", ".") for name in missing]],
            capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
        chunks = re.split(r"(?m)^Compiled from ", result.stdout)
        texts = [chunk for chunk in chunks if chunk.strip()]
        by_name: dict[str, str] = {}
        for text in texts:
            header = re.search(r"(?m)^(?:[a-z]+ )*(?:class|interface|enum) ([\w.$]+)", text)
            if header:
                by_name[header.group(1).replace(".", "/")] = text
        for name in missing:
            self.cache[name] = by_name.get(name, "")

    def text(self, name: str) -> str:
        self.load(name)
        return self.cache[name]


def method_section(text: str, header: str) -> str:
    """The javap listing of the member whose declaration line contains ``header``."""
    for match in re.finditer(r"(?m)^  \S.*$", text):
        if header in match.group(0):
            start = match.start()
            following = re.search(r"(?m)^  \S", text[match.end():])
            end = match.end() + following.start() if following else len(text)
            return text[start:end]
    return ""


def last_putstatic_bool(section: str, field: str) -> bool | None:
    matches = re.findall(
        r"(iconst_[01])\s*\n\s*\d+:\s+putstatic\s+#\d+\s+// Field " + re.escape(field) + r":Z",
        section)
    if not matches:
        return None
    return matches[-1] == "iconst_1"


def java_tool(name: str) -> Path | None:
    javap = resolve_javap()
    if javap is None:
        return None
    candidate = javap.with_name(name + (".exe" if javap.suffix == ".exe" else ""))
    return candidate if candidate.is_file() else None


def split_classpath(text: str) -> list[str]:
    """Splits a ``;``- or ``:``-separated class path, keeping ``C:/`` drive letters."""
    if ";" in text:
        return [part.strip() for part in text.split(";") if part.strip()]
    parts: list[str] = []
    raw = text.split(":")
    index = 0
    while index < len(raw):
        part = raw[index]
        if (len(part) == 1 and part.isalpha() and index + 1 < len(raw)
                and raw[index + 1][:1] in ("/", "\\")):
            part = part + ":" + raw[index + 1]
            index += 1
        if part.strip():
            parts.append(part.strip())
        index += 1
    return parts


def verification_classpath(overlay_dir: Path) -> list[Path]:
    """Overlay jars first, then the profile's vanilla libraries they do not replace."""
    client = overlay_dir / f"client-named-{PROFILE}-gaius.jar"
    libraries = overlay_dir / "libraries"
    overlay_jars = sorted(libraries.rglob("*.jar"))
    replaced = {jar.relative_to(libraries).as_posix() for jar in overlay_jars}
    entries = [client, *overlay_jars]
    classpath_file = ROOT / "port/work" / PROFILE / "classpath.txt"
    text = classpath_file.read_text(encoding="utf-8").strip()
    for raw in split_classpath(text):
        path = _native(raw)
        marker = f"/port/work/{PROFILE}/libraries/"
        normalized = path.as_posix()
        index = normalized.find(marker)
        if index >= 0:
            relative = normalized[index + len(marker):]
            if relative in replaced:
                continue
            path = ROOT / "port/work" / PROFILE / "libraries" / relative
        entries.append(path)
    maven = Path.home() / ".m2/repository/org/teavm"
    for artifact in ("teavm-interop", "teavm-jso", "teavm-jso-apis"):
        entries.extend(sorted((maven / artifact).glob("0.15.0/*.jar")))
    return entries


def jvm_verify(overlay_dir: Path) -> tuple[bool, str]:
    javac = java_tool("javac")
    java = java_tool("java")
    if javac is None or java is None:
        return False, "javac/java not found next to javap"
    classpath = os.pathsep.join(str(entry) for entry in verification_classpath(overlay_dir))
    with tempfile.TemporaryDirectory(prefix="gaius-p3-verify-") as temporary:
        source = Path(temporary) / "P3RenderClassVerifier.java"
        source.write_text(VERIFIER_SOURCE, encoding="utf-8")
        compiled = subprocess.run([str(javac), "-d", temporary, str(source)],
                                  capture_output=True, text=True, check=False)
        if compiled.returncode != 0:
            return False, compiled.stderr.strip()
        # The class path exceeds the Windows command-line limit; pass it in an @argfile.
        argfile = Path(temporary) / "classpath.args"
        quoted = (temporary + os.pathsep + classpath).replace("\\", "/")
        argfile.write_text(f'-classpath\n"{quoted}"\n', encoding="utf-8")
        result = subprocess.run(
            [str(java), "-Xverify:all", f"@{argfile}", "P3RenderClassVerifier",
             *[name.replace("/", ".") for name in VERIFIED_CLASSES]],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            check=False, timeout=300)
        output = (result.stdout + result.stderr).strip()
        return (result.returncode == 0
                and f"P3_VERIFIED {len(VERIFIED_CLASSES)}" in result.stdout), output


def run(overlay_dir: Path, overlay_262_dir: Path | None = None,
        verify: bool = True) -> list[str]:
    failures: list[str] = []

    def check(name: str, ok: bool) -> None:
        print(f"{'PASS' if ok else 'FAIL'} {name}")
        if not ok:
            failures.append(name)

    client = overlay_dir / f"client-named-{PROFILE}-gaius.jar"
    opengl = overlay_dir / "libraries/org/lwjgl/lwjgl-opengl/3.4.3/lwjgl-opengl-3.4.3.jar"
    javap = resolve_javap()
    for label, path in (("patched client jar", client), ("lwjgl-opengl 3.4.3 overlay", opengl)):
        if not path.is_file():
            raise FileNotFoundError(f"{label} is missing: {path} (run build-overlays.sh for 26.3)")
    if javap is None:
        raise FileNotFoundError("javap not found (set GAIUS_JAVA_HOME or JAVA_HOME)")
    classes = Javap(javap, client)
    classes.load(
        "net/minecraft/client/PreferredGraphicsApi",
        f"{RP}/backend/vulkan/VulkanBackend",
        f"{GL}/GlBackend",
        f"{GL}/GlBuffer$Direct",
        f"{GL}/GlBuffer$Direct$1",
        "com/mojang/blaze3d/vertex/BufferBuilder",
        "net/minecraft/client/renderer/texture/TextureAtlas",
        "net/minecraft/client/renderer/GameRenderer",
        "net/minecraft/client/gui/screens/options/VideoSettingsScreen",
        f"{GL}/GlHeuristics",
        "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs",
        "com/mojang/blaze3d/platform/MacosUtil",
        f"{GL}/GlDebug",
        f"{GL}/GlConst",
        f"{GL}/GlStateManager",
        f"{GL}/GlDevice",
        f"{GL}/GlCommandEncoder",
        f"{GL}/GlTransientMemory$PersistentMapping",
        f"{GL}/GlTransientMemory$Fallback",
    )

    # Backend selection (M262 patchPreferredGraphicsApi, renamed).
    backends = method_section(
        classes.text("net/minecraft/client/PreferredGraphicsApi"), " getBackendsToTry()")
    check("PreferredGraphicsApi.getBackendsToTry returns only the GL backend",
          f"class {GL}/GlBackend" in backends and "VulkanBackend" not in backends
          and re.search(r"iconst_1\s*\n\s*\d+: anewarray\s+#\d+\s+// class "
                        + re.escape(f"{RP}/api/device/GpuBackend"), backends) is not None)

    # VulkanBackend (RenderPatches263.patchVulkanBackend).
    vulkan = classes.text(f"{RP}/backend/vulkan/VulkanBackend")
    check_available = method_section(vulkan, " checkBackendAvailable()")
    check("VulkanBackend.checkBackendAvailable returns VULKAN_LOADER_MISSING without probing",
          bool(check_available) and "static" in check_available.splitlines()[0]
          and "VULKAN_LOADER_MISSING" in check_available and "areturn" in check_available
          and "isVulkanLoaderAvailable" not in check_available
          and "loadLibrary" not in check_available)
    load_vulkan = method_section(vulkan, " loadLibrary()")
    check("VulkanBackend.loadLibrary throws without touching VK/SDLVulkan",
          "athrow" in load_vulkan and "org/lwjgl" not in load_vulkan)
    check("VulkanBackend.unloadLibrary is empty",
          "SDLVulkan" not in method_section(vulkan, " unloadLibrary()"))
    create_vulkan = method_section(vulkan, " createDevice(")
    check("VulkanBackend.createDevice throws",
          "athrow" in create_vulkan and "VulkanInstance" not in create_vulkan)
    check("VulkanBackend.createWindow returns 0",
          "lconst_0" in method_section(vulkan, " createWindow(")
          and "SDL_CreateWindow" not in method_section(vulkan, " createWindow("))

    # GlBackend library stubs.
    gl_backend = classes.text(f"{GL}/GlBackend")
    load_gl = method_section(gl_backend, " loadLibrary()")
    check("GlBackend.loadLibrary only marks the library loaded",
          "putfield" in load_gl and "libraryLoaded:Z" in load_gl
          and "SDL_GL_LoadLibrary" not in load_gl and "getFunctionProvider" not in load_gl)
    check("GlBackend.unloadLibrary is empty",
          "SDL_GL_UnloadLibrary" not in method_section(gl_backend, " unloadLibrary()"))

    # GlBuffer$Direct explicit flush.
    direct = classes.text(f"{GL}/GlBuffer$Direct")
    direct_init = method_section(direct, "GlBuffer$Direct(")
    check("GlBuffer$Direct write mappings add GL_MAP_FLUSH_EXPLICIT_BIT",
          re.search(r"iconst_2\s*\n\s*\d+: ior\s*\n\s*\d+: istore\s+(\d+)\s*\n\s*\d+: iload\s+\1"
                    r"\s*\n\s*\d+: bipush\s+16\s*\n\s*\d+: ior", direct_init) is not None)
    check("GlBuffer$Direct.map passes the view range to its close action",
          f"{GL}/GlBuffer$Direct$1.\"<init>\":(L{GL}/GlBuffer$Direct;JJ)V"
          in method_section(direct, " map(long, long, boolean, boolean)"))
    close_run = method_section(classes.text(f"{GL}/GlBuffer$Direct$1"), " run()")
    flush_at = close_run.find("DirectStateAccess.flushMappedBufferRange:(IJJI)V")
    unmap_at = close_run.find("GlBuffer$Direct.unmap:()V")
    check("GlBuffer$Direct$1.run flushes the mapped range before unmap",
          0 <= flush_at < unmap_at and "mappedOffset:J" in close_run
          and "mappedLength:J" in close_run)

    # BufferBuilder semantic ids and element owner.
    builder = classes.text("com/mojang/blaze3d/vertex/BufferBuilder")
    set_normal = method_section(builder, " setNormal(float, float, float)")
    check("BufferBuilder.setNormal writes semantic id 6 (26.3 NORMAL)",
          re.search(r"bipush\s+6\s*\n\s*\d+: invokevirtual\s+#\d+\s+// Method "
                    r"browserBeginElementOffset:\(I\)I", set_normal) is not None)
    check("BufferBuilder has no blaze3d VertexFormatElement reference",
          "com/mojang/blaze3d/vertex/VertexFormatElement" not in builder
          and "browserBeginElementOffset" in builder)

    # TextureAtlas sprite UBO close.
    atlas = classes.text("net/minecraft/client/renderer/texture/TextureAtlas")
    prepare = method_section(atlas, " browserPrepareForSpriteReload()")
    check("TextureAtlas closes spriteUbos through INVOKEINTERFACE on the renderpearl GpuBuffer",
          re.search(r"invokeinterface\s+#\d+,\s+1\s+// InterfaceMethod "
                    + re.escape(f"{RP}/api/buffers/GpuBuffer.close:()V"), prepare) is not None
          and "com/mojang/blaze3d/buffers/GpuBuffer" not in atlas)

    # OIT off.
    improved = method_section(
        classes.text("net/minecraft/client/renderer/GameRenderer"), " useImprovedTransparency()")
    check("GameRenderer.useImprovedTransparency returns false",
          re.search(r"0: iconst_0\s*\n\s*1: ireturn", improved) is not None)
    quality = method_section(
        classes.text("net/minecraft/client/gui/screens/options/VideoSettingsScreen"),
        " qualityOptions(")
    check("VideoSettingsScreen no longer offers improved transparency",
          "Options.improvedTransparency" not in quality and "Options.biomeBlendRadius" in quality)

    # Wireframe unavailable.
    heuristics = classes.text(f"{GL}/GlHeuristics")
    device_info = method_section(heuristics, " createDeviceInfo(")
    check("GlHeuristics.createDeviceInfo reports wireframeFillMode=false",
          re.search(r"new\s+#\d+\s+// class " + re.escape(f"{RP}/api/device/DeviceFeatures")
                    + r"\s*\n\s*\d+: dup\s*\n\s*\d+: iconst_0", device_info) is not None)
    max_texture = method_section(heuristics, " getMaxSupportedTextureSize()")
    check("GlHeuristics.getMaxSupportedTextureSize uses GL_MAX_TEXTURE_SIZE",
          "3379" in max_texture and "GlStateManager._getInteger" in max_texture
          and "32868" not in max_texture)

    # CPU info (replaces GLX._getCpuInfo).
    cpu = method_section(
        classes.text("net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs"),
        " getCpuInfo()")
    check("DebugEntrySystemSpecs.getCpuInfo returns \"Browser runtime\"",
          "String Browser runtime" in cpu and "oshi" not in cpu)

    # MacosUtil.
    macos = classes.text("com/mojang/blaze3d/platform/MacosUtil")
    check("MacosUtil entry points are stubbed",
          "ca/weblite" not in macos and "SDL_SetHint" not in macos
          and last_putstatic_bool(method_section(macos, "static {}"), "IS_MACOS") is False)

    # Renamed GL backend patches.
    check("GlDebug.enableDebugCallback returns null",
          re.search(r"0: aconst_null\s*\n\s*1: areturn",
                    method_section(classes.text(f"{GL}/GlDebug"), " enableDebugCallback(")) is not None)
    internal_id = method_section(classes.text(f"{GL}/GlConst"), " toGlInternalId(")
    check("GlConst.toGlInternalId maps R8I to R8",
          "int 33321" in internal_id and "int 33329" not in internal_id)
    state = classes.text(f"{GL}/GlStateManager")
    check("GlStateManager texture binding re-activates the texture unit",
          "GL13.glActiveTexture" in method_section(state, " _bindTexture(int)")
          and "GL13.glActiveTexture" in method_section(state, " _activeTexture(int)"))
    device_clinit = method_section(classes.text(f"{GL}/GlDevice"), "static {}")
    check("GlDevice disables the ARB extension paths",
          all(last_putstatic_bool(device_clinit, field) is False for field in (
              "USE_GL_ARB_vertex_attrib_binding", "USE_GL_ARB_base_instance",
              "USE_GL_ARB_draw_indirect", "USE_GL_ARB_multi_draw_indirect",
              "USE_GL_ARB_shader_draw_parameters")))
    encoder = classes.text(f"{GL}/GlCommandEncoder")
    submit = method_section(encoder, " submit()")
    check("GlCommandEncoder keeps the 8-slot asynchronous GPU retire",
          "MAX_SUBMITS_IN_FLIGHT = 8" in encoder and "gaius$pollRetireSlot" in submit
          and "BrowserOpenGL.beginGpuRetireFrame" in submit
          and "IllegalStateException" not in submit)
    check("GlCommandEncoder keeps the vanilla draw path (no 26.2 draw helpers)",
          "gaius$bindDefaultUniforms" not in encoder and "drawFromBuffers" not in encoder
          and " setupDraw(" in encoder)
    check("GlTransientMemory rotations use the 8-slot retire ring",
          re.search(r"bipush\s+8\s*\n\s*\d+: anewarray",
                    classes.text(f"{GL}/GlTransientMemory$PersistentMapping")) is not None
          and "gaius$retireRotations" in classes.text(f"{GL}/GlTransientMemory$Fallback"))

    # No stale 26.2 entries (MinecraftClientPatcher main output paths, dropped patches).
    with zipfile.ZipFile(client) as jar:
        names = set(jar.namelist())
    stale = sorted(name for name in names
                   if name in STALE_ENTRIES or name.startswith(STALE_PREFIXES))
    if stale:
        print("  stale entries: " + ", ".join(stale[:10]))
    check("no stale 26.2 render entries in the 26.3 client jar", not stale)

    # LWJGL 3.4.3 OpenGL entry points.
    opengl_classes = Javap(javap, opengl)
    gl30c = opengl_classes.text("org/lwjgl/opengl/GL30C")
    gl11c = opengl_classes.text("org/lwjgl/opengl/GL11C")
    check("lwjgl-opengl 3.4.3 GL30C.glEnablei/glDisablei delegate to BrowserOpenGLIndexed",
          "BrowserOpenGLIndexed.enablei:(II)V" in method_section(gl30c, " glEnablei(int, int)")
          and "BrowserOpenGLIndexed.disablei:(II)V"
          in method_section(gl30c, " glDisablei(int, int)"))
    check("lwjgl-opengl 3.4.3 GL11C.glReadBuffer delegates to BrowserOpenGLIndexed",
          "BrowserOpenGLIndexed.readBuffer:(I)V" in method_section(gl11c, " glReadBuffer(int)"))
    if verify:
        verified, output = jvm_verify(overlay_dir)
        if not verified:
            for line in output.splitlines()[-15:]:
                print("  " + line)
        check(f"JVM verifier accepts the {len(VERIFIED_CLASSES)} P3-patched classes", verified)
    if overlay_262_dir is not None:
        opengl_341 = overlay_262_dir / "libraries/org/lwjgl/lwjgl-opengl/3.4.1/lwjgl-opengl-3.4.1.jar"
        if opengl_341.is_file():
            with zipfile.ZipFile(opengl_341) as jar:
                has_indexed = "org/lwjgl/opengl/BrowserOpenGLIndexed.class" in jar.namelist()
            gl30c_341 = Javap(javap, opengl_341).text("org/lwjgl/opengl/GL30C")
            check("lwjgl-opengl 3.4.1 (26.2) has no BrowserOpenGLIndexed delegate",
                  not has_indexed and "BrowserOpenGLIndexed" not in gl30c_341)
        else:
            print(f"SKIP 26.2 lwjgl-opengl 3.4.1 check: {opengl_341} is missing")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--overlay-dir", default=str(ROOT / "port/work/overlays" / PROFILE))
    parser.add_argument("--overlay-262-dir", default=str(ROOT / "port/work/overlays/26.2"))
    parser.add_argument("--no-verify", action="store_true",
                        help="skip the JVM bytecode verifier run")
    arguments = parser.parse_args()
    overlay_262 = _native(arguments.overlay_262_dir)
    try:
        failures = run(_native(arguments.overlay_dir),
                       overlay_262 if overlay_262.is_dir() else None,
                       verify=not arguments.no_verify)
    except FileNotFoundError as missing:
        print(f"MISSING {missing}")
        return 2
    print(f"PROFILE_263_RENDER {'PASS' if not failures else 'FAIL'} failures={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
