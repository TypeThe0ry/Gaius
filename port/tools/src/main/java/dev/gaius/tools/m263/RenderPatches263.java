package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Minecraft 26.3 render patches, owned by work package P3 (migration plan D4).
 *
 * <p>The 26.3 render stack is the vanilla renderpearl frontend on its OpenGL backend, running on
 * BrowserOpenGL (WebGL2). These patches are the 26.3-only part of that port; the rename-only
 * ports of the 26.2 GL patches stay in MinecraftClientPatcher and Minecraft262BrowserPatcher.
 * <ul>
 *   <li>{@link #patchGlBackendLibrary}: {@code GlBackend.loadLibrary()} marks the library loaded
 *       instead of calling {@code SDL_GL_LoadLibrary(GL.getFunctionProvider().getPath())} (the
 *       browser GL override has no function provider, so vanilla throws a NullPointerException);
 *       {@code unloadLibrary()} returns.</li>
 *   <li>{@link #patchVulkanBackend}: {@code checkBackendAvailable()} returns a
 *       VULKAN_LOADER_MISSING exception, {@code loadLibrary()} and {@code createDevice(...)}
 *       throw it, {@code unloadLibrary()} returns and {@code createWindow(...)} returns 0, so no
 *       Vulkan, VMA or SDLVulkan code is reachable. Replaces the dropped 26.2
 *       {@code Minecraft262BrowserPatcher.patchVulkanBackend}.</li>
 *   <li>{@link #patchSystemSpecsCpuInfo}: {@code DebugEntrySystemSpecs.getCpuInfo()} returns
 *       "Browser runtime" (26.3 removed {@code GLX._getCpuInfo}, the 26.2 target of
 *       {@code MinecraftClientPatcher.patchGlx}); prunes oshi from the reachable set.</li>
 *   <li>{@link #patchGlBufferExplicitFlush}: 26.3 no longer sets GL_MAP_FLUSH_EXPLICIT_BIT on
 *       write mappings, and BrowserOpenGL uploads the whole (unseeded) shadow buffer when such a
 *       mapping is unmapped, which corrupts every byte the caller did not write. The patch ORs
 *       FLUSH_EXPLICIT (16) into the write flags, records each mapped view's range and flushes
 *       exactly that range before {@code unmap()} (the semantics of the dropped 26.2
 *       {@code Minecraft262BrowserPatcher.patchGlBufferMappedViewRanges}).</li>
 *   <li>{@link #patchImprovedTransparencyOff}: order-independent transparency needs float
 *       colour targets (EXT_color_buffer_float/EXT_float_blend) that the first browser release
 *       does not provide; {@code GameRenderer.useImprovedTransparency()} returns false and the
 *       video settings no longer offer the option.</li>
 *   <li>{@link #patchWireframeUnavailable}: WebGL2 has no polygon mode, so
 *       {@code GlHeuristics.createDeviceInfo} reports {@code wireframeFillMode=false}.</li>
 *   <li>{@link #patchMacosUtil}: stubs {@code disableCloseWindowMenuItem()} (ObjC bridge) and the
 *       two SDL hint setters; MinecraftClientPatcher.patchMacosUtil already forces
 *       {@code IS_MACOS=false}, this also prunes their reachability.</li>
 * </ul>
 *
 * <p>Classes are read from {@code root} when an earlier M263 domain patch already wrote them,
 * otherwise from {@code jar} (the chain's current client jar), so patches of several domains on
 * one class compose.
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui.
 */
public final class RenderPatches263 {
    static final String GL_BACKEND = "com/mojang/blaze3d/opengl/GlBackend";
    static final String GL_BUFFER = "com/mojang/blaze3d/opengl/GlBuffer";
    static final String GL_HEURISTICS = "com/mojang/blaze3d/opengl/GlHeuristics";
    static final String DIRECT_STATE_ACCESS = "com/mojang/blaze3d/opengl/DirectStateAccess";
    static final String VULKAN_BACKEND = "com/mojang/blaze3d/vulkan/VulkanBackend";
    static final String BACKEND_CREATION_EXCEPTION =
            "com/mojang/blaze3d/systems/BackendCreationException";
    static final String GPU_DEBUG_OPTIONS = "com/mojang/blaze3d/shaders/GpuDebugOptions";
    static final String GPU_DEVICE = "com/mojang/blaze3d/systems/GpuDevice";
    static final String GPU_BUFFER_SLICE = "com/mojang/blaze3d/buffers/GpuBufferSlice";
    static final String DEVICE_FEATURES = "com/mojang/blaze3d/systems/DeviceFeatures";
    static final String DEVICE_INFO = "com/mojang/blaze3d/systems/DeviceInfo";
    static final String SYSTEM_SPECS =
            "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs";
    static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    static final String VIDEO_SETTINGS =
            "net/minecraft/client/gui/screens/options/VideoSettingsScreen";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String OPTION_INSTANCE = "net/minecraft/client/OptionInstance";
    static final String MACOS_UTIL = "com/mojang/blaze3d/platform/MacosUtil";
    static final String BROWSER_CPU_INFO = "Browser runtime";
    static final String VULKAN_UNAVAILABLE = "Vulkan is unavailable in the browser runtime";
    static final int GL_MAP_WRITE_BIT = 2;
    static final int GL_MAP_FLUSH_EXPLICIT_BIT = 16;

    private RenderPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        PatchRegistry.run("RenderPatches263.patchGlBackendLibrary",
                () -> patchGlBackendLibrary(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchVulkanBackend",
                () -> patchVulkanBackend(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchSystemSpecsCpuInfo",
                () -> patchSystemSpecsCpuInfo(jar, root));
        PatchRegistry.run("RenderPatches263.patchGlBufferExplicitFlush",
                () -> patchGlBufferExplicitFlush(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchImprovedTransparencyOff",
                () -> patchImprovedTransparencyOff(jar, root));
        PatchRegistry.run("RenderPatches263.patchWireframeUnavailable",
                () -> patchWireframeUnavailable(jar, root, symbols));
        PatchRegistry.run("RenderPatches263.patchMacosUtil",
                () -> patchMacosUtil(jar, root));
    }

    static void patchGlBackendLibrary(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String owner = symbols.renderType(GL_BACKEND);
        ClassNode node = readClass(jar, root, owner);
        requireField(node, "libraryLoaded", "Z");
        MethodNode load = find(node, "loadLibrary", "()V");
        requireCall(load, Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL", "getFunctionProvider",
                "()Lorg/lwjgl/system/FunctionProvider;");
        requireCall(load, Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVideo", "SDL_GL_LoadLibrary",
                "(Ljava/lang/CharSequence;)Z");
        InsnList loaded = new InsnList();
        loaded.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loaded.add(new InsnNode(Opcodes.ICONST_1));
        loaded.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "libraryLoaded", "Z"));
        loaded.add(new InsnNode(Opcodes.RETURN));
        replace(load, loaded);

        MethodNode unload = find(node, "unloadLibrary", "()V");
        requireCall(unload, Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVideo",
                "SDL_GL_UnloadLibrary", "()V");
        InsnList done = new InsnList();
        done.add(new InsnNode(Opcodes.RETURN));
        replace(unload, done);
        writeClass(node, root, false);
        System.out.println("Stubbed 26.3 GlBackend library loading for the browser GL context");
    }

    static void patchVulkanBackend(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String owner = symbols.renderType(VULKAN_BACKEND);
        String exception = symbols.renderType(BACKEND_CREATION_EXCEPTION);
        String reason = exception + "$Reason";
        ClassNode reasonNode = readClass(jar, root, reason);
        requireField(reasonNode, "VULKAN_LOADER_MISSING", "L" + reason + ";");
        ClassNode node = readClass(jar, root, owner);

        MethodNode check = find(node, "checkBackendAvailable", "()L" + exception + ";");
        if ((check.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(owner + ".checkBackendAvailable is no longer static");
        }
        InsnList unavailable = newUnavailable(exception, reason);
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(check, unavailable);

        MethodNode load = find(node, "loadLibrary", "()V");
        InsnList loadThrows = newUnavailable(exception, reason);
        loadThrows.add(new InsnNode(Opcodes.ATHROW));
        replace(load, loadThrows);

        MethodNode unload = find(node, "unloadLibrary", "()V");
        InsnList unloadCode = new InsnList();
        unloadCode.add(new InsnNode(Opcodes.RETURN));
        replace(unload, unloadCode);

        MethodNode create = find(node, "createDevice",
                "(L" + symbols.renderType(GPU_DEBUG_OPTIONS) + ";)L"
                        + symbols.renderType(GPU_DEVICE) + ";");
        InsnList createThrows = newUnavailable(exception, reason);
        createThrows.add(new InsnNode(Opcodes.ATHROW));
        replace(create, createThrows);

        MethodNode window = find(node, "createWindow", "(Ljava/lang/String;IIJ)J");
        InsnList noWindow = new InsnList();
        noWindow.add(new InsnNode(Opcodes.LCONST_0));
        noWindow.add(new InsnNode(Opcodes.LRETURN));
        replace(window, noWindow);
        writeClass(node, root, false);
        System.out.println("Stubbed 26.3 VulkanBackend: " + VULKAN_UNAVAILABLE);
    }

    static void patchSystemSpecsCpuInfo(String jar, Path root) throws IOException {
        ClassNode node = readClass(jar, root, SYSTEM_SPECS);
        MethodNode method = find(node, "getCpuInfo", "()Ljava/lang/String;");
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(SYSTEM_SPECS + ".getCpuInfo is no longer static");
        }
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(BROWSER_CPU_INFO));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
        writeClass(node, root, false);
        System.out.println("Patched 26.3 DebugEntrySystemSpecs.getCpuInfo to \""
                + BROWSER_CPU_INFO + "\"");
    }

    static void patchGlBufferExplicitFlush(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String direct = symbols.renderType(GL_BUFFER + "$Direct");
        String close = direct + "$1";
        String dsa = symbols.renderType(DIRECT_STATE_ACCESS);
        String mappedView = symbols.renderType(GPU_BUFFER_SLICE + "$MappedView");

        // Write mappings: OR GL_MAP_FLUSH_EXPLICIT_BIT into the flags the constructor builds.
        ClassNode directNode = readClass(jar, root, direct);
        requireField(directNode, "mappingFlags", "I");
        requireField(directNode, "dsa", "L" + dsa + ";");
        MethodNode constructor = findConstructorStoring(directNode, "mappingFlags");
        int flagsLocal = flagsLocal(constructor, direct);
        List<VarInsnNode> writeStores = new ArrayList<>();
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD
                    && load.var == flagsLocal
                    && next(load) != null && next(load).getOpcode() == Opcodes.ICONST_2
                    && next(next(load)) != null && next(next(load)).getOpcode() == Opcodes.IOR
                    && next(next(next(load))) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ISTORE && store.var == flagsLocal) {
                writeStores.add(store);
            }
        }
        if (writeStores.size() != 1) {
            throw new IllegalStateException(direct + " constructor: expected one write-mapping flag"
                    + " assignment (flags |= GL_MAP_WRITE_BIT), found " + writeStores.size());
        }
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof IntInsnNode push && push.getOpcode() == Opcodes.BIPUSH
                    && push.operand == GL_MAP_FLUSH_EXPLICIT_BIT) {
                throw new IllegalStateException(direct
                        + " constructor already uses GL_MAP_FLUSH_EXPLICIT_BIT; re-check the"
                        + " 26.3 explicit-flush port");
            }
        }
        InsnList explicitFlush = new InsnList();
        explicitFlush.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        explicitFlush.add(new IntInsnNode(Opcodes.BIPUSH, GL_MAP_FLUSH_EXPLICIT_BIT));
        explicitFlush.add(new InsnNode(Opcodes.IOR));
        explicitFlush.add(new VarInsnNode(Opcodes.ISTORE, flagsLocal));
        constructor.instructions.insert(writeStores.get(0), explicitFlush);

        // map(): pass the mapped view's (offset, length) to its close action.
        MethodNode map = find(directNode, "map", "(JJZZ)L" + mappedView + ";");
        int constructors = 0;
        for (AbstractInsnNode instruction : map.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(close) && call.name.equals("<init>")
                    && call.desc.equals("(L" + direct + ";)V")) {
                InsnList range = new InsnList();
                range.add(new VarInsnNode(Opcodes.LLOAD, 1));
                range.add(new VarInsnNode(Opcodes.LLOAD, 3));
                map.instructions.insertBefore(call, range);
                call.desc = "(L" + direct + ";JJ)V";
                constructors++;
            }
        }
        if (constructors != 1) {
            throw new IllegalStateException(direct + ".map: expected one " + close
                    + " constructor call, found " + constructors);
        }
        find(directNode, "unmap", "()V");
        // Straight-line insertions only: keep the vanilla stack map frames.
        writeClass(directNode, root, false);

        // The close action: flush the view's own range before unmap().
        ClassNode closeNode = readClass(jar, root, close);
        requireField(closeNode, "closed", "Z");
        requireField(closeNode, "this$0", "L" + direct + ";");
        MethodNode run = find(closeNode, "run", "()V");
        requireCall(run, Opcodes.INVOKEVIRTUAL, direct, "unmap", "()V");
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("flushMappedBufferRange")) {
                throw new IllegalStateException(close
                        + ".run already flushes; re-check the 26.3 explicit-flush port");
            }
        }
        closeNode.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedOffset", "J", null, null));
        closeNode.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedLength", "J", null, null));
        MethodNode init = find(closeNode, "<init>", "(L" + direct + ";)V");
        init.desc = "(L" + direct + ";JJ)V";
        InsnList initCode = new InsnList();
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "this$0", "L" + direct + ";"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "closed", "Z"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "mappedOffset", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.LLOAD, 4));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "mappedLength", "J"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode);

        LabelNode active = new LabelNode();
        LabelNode unmap = new LabelNode();
        InsnList runCode = new InsnList();
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "closed", "Z"));
        runCode.add(new JumpInsnNode(Opcodes.IFEQ, active));
        runCode.add(new InsnNode(Opcodes.RETURN));
        runCode.add(active);
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new InsnNode(Opcodes.ICONST_1));
        runCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "closed", "Z"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, direct, "mappingFlags", "I"));
        runCode.add(new IntInsnNode(Opcodes.BIPUSH, GL_MAP_FLUSH_EXPLICIT_BIT));
        runCode.add(new InsnNode(Opcodes.IAND));
        runCode.add(new JumpInsnNode(Opcodes.IFEQ, unmap));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, direct, "dsa", "L" + dsa + ";"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "handle", "()I", false));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "mappedOffset", "J"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "mappedLength", "J"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "usage", "()I", false));
        runCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, dsa, "flushMappedBufferRange", "(IJJI)V", false));
        runCode.add(unmap);
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "unmap", "()V", false));
        runCode.add(new InsnNode(Opcodes.RETURN));
        replace(run, runCode);
        requireMethod(readClass(jar, root, dsa), "flushMappedBufferRange", "(IJJI)V");
        writeClass(closeNode, root, true);
        System.out.println("Patched 26.3 GlBuffer write mappings to flush only their mapped"
                + " view ranges (GL_MAP_FLUSH_EXPLICIT_BIT)");
    }

    static void patchImprovedTransparencyOff(String jar, Path root) throws IOException {
        ClassNode renderer = readClass(jar, root, GAME_RENDERER);
        MethodNode use = find(renderer, "useImprovedTransparency", "()Z");
        boolean readsOption = false;
        for (AbstractInsnNode instruction : use.instructions.toArray()) {
            readsOption |= instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("improvedTransparency") && field.desc.equals("Z");
        }
        if (!readsOption) {
            throw new IllegalStateException(GAME_RENDERER
                    + ".useImprovedTransparency no longer reads the improvedTransparency option");
        }
        InsnList off = new InsnList();
        off.add(new InsnNode(Opcodes.ICONST_0));
        off.add(new InsnNode(Opcodes.IRETURN));
        replace(use, off);
        writeClass(renderer, root, false);

        // Video settings: rebuild qualityOptions(Options) without Options.improvedTransparency().
        ClassNode screen = readClass(jar, root, VIDEO_SETTINGS);
        String arrayDesc = "(L" + OPTIONS + ";)[L" + OPTION_INSTANCE + ";";
        MethodNode quality = find(screen, "qualityOptions", arrayDesc);
        List<MethodInsnNode> getters = new ArrayList<>();
        for (AbstractInsnNode instruction : quality.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(OPTIONS)
                        || !call.desc.equals("()L" + OPTION_INSTANCE + ";")) {
                    throw new IllegalStateException(VIDEO_SETTINGS
                            + ".qualityOptions is no longer a plain array of Options getters: "
                            + call.owner + "." + call.name + call.desc);
                }
                getters.add(call);
            } else if (instruction.getOpcode() >= 0
                    && instruction.getOpcode() != Opcodes.DUP
                    && instruction.getOpcode() != Opcodes.ALOAD
                    && instruction.getOpcode() != Opcodes.AASTORE
                    && instruction.getOpcode() != Opcodes.ANEWARRAY
                    && instruction.getOpcode() != Opcodes.ARETURN
                    && !isIntPush(instruction)) {
                throw new IllegalStateException(VIDEO_SETTINGS
                        + ".qualityOptions has an unexpected instruction (opcode "
                        + instruction.getOpcode() + ")");
            }
        }
        long transparency = getters.stream()
                .filter(call -> call.name.equals("improvedTransparency")).count();
        if (transparency != 1 || getters.size() < 2) {
            throw new IllegalStateException(VIDEO_SETTINGS + ".qualityOptions: expected one"
                    + " improvedTransparency option among " + getters.size() + " getters, found "
                    + transparency);
        }
        List<String> kept = new ArrayList<>();
        for (MethodInsnNode call : getters) {
            if (!call.name.equals("improvedTransparency")) {
                kept.add(call.name);
            }
        }
        InsnList options = new InsnList();
        options.add(intPush(kept.size()));
        options.add(new TypeInsnNode(Opcodes.ANEWARRAY, OPTION_INSTANCE));
        for (int index = 0; index < kept.size(); index++) {
            options.add(new InsnNode(Opcodes.DUP));
            options.add(intPush(index));
            options.add(new VarInsnNode(Opcodes.ALOAD, 0));
            options.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONS, kept.get(index),
                    "()L" + OPTION_INSTANCE + ";", false));
            options.add(new InsnNode(Opcodes.AASTORE));
        }
        options.add(new InsnNode(Opcodes.ARETURN));
        replace(quality, options);
        writeClass(screen, root, false);
        System.out.println("Forced 26.3 improved transparency (OIT) off and removed it from the"
                + " video settings (" + kept.size() + " quality options remain)");
    }

    static void patchWireframeUnavailable(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String heuristics = symbols.renderType(GL_HEURISTICS);
        String features = symbols.renderType(DEVICE_FEATURES);
        ClassNode node = readClass(jar, root, heuristics);
        MethodNode method = find(node, "createDeviceInfo",
                "(Lorg/lwjgl/opengl/GLCapabilities;ILjava/util/Set;)L"
                        + symbols.renderType(DEVICE_INFO) + ";");
        List<AbstractInsnNode> wireframe = new ArrayList<>();
        int constructors = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(features)) {
                AbstractInsnNode dup = next(type);
                AbstractInsnNode first = dup == null ? null : next(dup);
                if (dup == null || dup.getOpcode() != Opcodes.DUP || first == null
                        || (first.getOpcode() != Opcodes.ICONST_1
                            && first.getOpcode() != Opcodes.ICONST_0)) {
                    throw new IllegalStateException(heuristics + ".createDeviceInfo: "
                            + features + " no longer starts with a constant wireframeFillMode");
                }
                wireframe.add(first);
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(features)
                    && call.name.equals("<init>") && call.desc.equals("(ZZZZZZZZ)V")) {
                constructors++;
            }
        }
        if (wireframe.size() != 1 || constructors != 1) {
            throw new IllegalStateException(heuristics + ".createDeviceInfo: expected one "
                    + features + "(ZZZZZZZZ) construction, found " + wireframe.size() + "/"
                    + constructors);
        }
        FieldNode first = null;
        for (FieldNode field : readClass(jar, root, features).fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0) {
                first = field;
                break;
            }
        }
        if (first == null || !first.name.equals("wireframeFillMode")) {
            throw new IllegalStateException(features + " first component is "
                    + (first == null ? "missing" : first.name) + ", expected wireframeFillMode");
        }
        method.instructions.set(wireframe.get(0), new InsnNode(Opcodes.ICONST_0));
        writeClass(node, root, false);
        System.out.println("Reported 26.3 GL wireframe fill mode as unavailable (WebGL2)");
    }

    static void patchMacosUtil(String jar, Path root) throws IOException {
        ClassNode node = readClass(jar, root, MACOS_UTIL);
        int stubbed = 0;
        for (String[] target : new String[][] {
                {"disableCloseWindowMenuItem", "()V"},
                {"setFullscreenMenuVisibility", "(Z)V"},
                {"setCtrlClickEmulatesRightClick", "(Z)V"}}) {
            MethodNode method = find(node, target[0], target[1]);
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                throw new IllegalStateException(MACOS_UTIL + "." + target[0]
                        + " is no longer static");
            }
            InsnList code = new InsnList();
            code.add(new InsnNode(Opcodes.RETURN));
            replace(method, code);
            stubbed++;
        }
        writeClass(node, root, false);
        System.out.println("Stubbed " + stubbed + " 26.3 MacosUtil ObjC/SDL-hint entry points");
    }

    private static InsnList newUnavailable(String exception, String reason) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, exception));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(VULKAN_UNAVAILABLE));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, exception, "<init>",
                "(Ljava/lang/String;L" + reason + ";)V", false));
        return code;
    }

    private static MethodNode findConstructorStoring(ClassNode node, String field) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode store
                        && store.getOpcode() == Opcodes.PUTFIELD && store.owner.equals(node.name)
                        && store.name.equals(field)) {
                    if (found != null && found != method) {
                        throw new IllegalStateException(node.name
                                + ": several constructors assign " + field);
                    }
                    found = method;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException(node.name + ": no constructor assigns " + field);
        }
        return found;
    }

    /** The local that {@code this.mappingFlags = <local>} stores, asserted to be unique. */
    private static int flagsLocal(MethodNode constructor, String owner) {
        int local = -1;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner) && store.name.equals("mappingFlags")) {
                AbstractInsnNode value = previous(store);
                if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD
                        || local >= 0) {
                    throw new IllegalStateException(owner
                            + " constructor: mappingFlags is not stored once from a local");
                }
                local = load.var;
            }
        }
        if (local < 0) {
            throw new IllegalStateException(owner + " constructor: mappingFlags store not found");
        }
        return local;
    }

    private static boolean isIntPush(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH;
    }

    private static AbstractInsnNode intPush(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        return new IntInsnNode(Opcodes.SIPUSH, value);
    }

    /** The next real instruction (labels, line numbers and frames skipped). */
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static void requireField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return;
            }
        }
        throw new IllegalStateException(node.name + "." + name + ":" + desc + " was not found");
    }

    private static void requireMethod(ClassNode node, String name, String desc) {
        find(node, name, desc);
    }

    private static void requireCall(
            MethodNode method, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) {
                return;
            }
        }
        throw new IllegalStateException(method.name + method.desc + " no longer calls "
                + owner + "." + name + desc);
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + desc + " was not found");
    }

    /**
     * Replaces a method body. Callers write the class with {@code computeFrames} when the new
     * body branches; straight-line bodies need no frames.
     */
    private static void replace(MethodNode method, InsnList code) {
        method.access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        int argumentSlots = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
        if ((method.access & Opcodes.ACC_STATIC) != 0) {
            argumentSlots--;
        }
        method.maxLocals = Math.max(argumentSlots, 1);
        method.maxStack = 16;
    }

    /**
     * Reads {@code name} from {@code root} when it is there, else from {@code jar}. The root holds
     * the earlier patchers' outputs (already folded into the jar, so identical to its entries)
     * and the classes earlier M263 patches wrote in this run, which the jar does not have yet.
     */
    static ClassNode readClass(String jar, Path root, String name) throws IOException {
        Path written = root.resolve(name + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                var entry = zip.getEntry(name + ".class");
                if (entry == null) {
                    throw new IllegalStateException(name + ".class was not found in " + jar);
                }
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static void writeClass(ClassNode node, Path root, boolean computeFrames) throws IOException {
        ClassWriter writer = computeFrames
                ? new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        return "java/lang/Object";
                    }
                }
                : new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
