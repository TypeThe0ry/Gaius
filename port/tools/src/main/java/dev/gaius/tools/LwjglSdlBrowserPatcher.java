package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Redirects the LWJGL 3.4.3 SDL3 bindings that Minecraft 26.3 uses to the browser shim
 * {@code org.lwjgl.sdl.BrowserSdl} (work package P2, decision D3).
 *
 * <ul>
 *   <li>{@code SDL.<clinit>} becomes {@code SDL = null}: no native library is loaded.</li>
 *   <li>The <em>body</em> of each of the {@value #ENTRY_COUNT} public static {@code SDL_*}
 *       entry points in {@link #ENTRIES} becomes an {@code invokestatic} of the
 *       {@code BrowserSdl} method with the same name and descriptor. Bodies rather than
 *       call sites, because {@code SDLTimer.SDL_GetTicksNS} is also reached through the
 *       {@code REF_invokeStatic} method handle of {@code RenderSystem.initBackendSystem}.</li>
 * </ul>
 *
 * <p>Fail-closed: every entry must exist in the jar as a public static method, the
 * {@code BrowserSdl} overlay (compiled into the jar by build-overlays.sh before this step)
 * must declare a public static target with the identical descriptor, and exactly
 * {@value #ENTRY_COUNT} bodies must be replaced. {@code InputPatches263} checks that the
 * client references no SDL entry point outside {@link #ENTRIES}.
 *
 * <p>The module has no native methods and no {@code sun.misc.Unsafe} use; its callback
 * interfaces ({@code SDL_LogOutputFunctionI} and others) still need the {@code callbacks}
 * step (LwjglCallbackDescriptorPatcher) of build-overlays.sh.
 */
public final class LwjglSdlBrowserPatcher {
    public static final String PACKAGE = "org/lwjgl/sdl/";
    public static final String SDL = PACKAGE + "SDL";
    public static final String BROWSER = PACKAGE + "BrowserSdl";
    public static final int ENTRY_COUNT = 84;

    /**
     * Owner (simple name in {@code org/lwjgl/sdl}), name and descriptor of every SDL entry
     * point that the Minecraft 26.3 client reaches: 84 {@code SDL_*} methods (migration
     * notes input-window V.2-1). {@code SDL.getLibrary()} is only reached from the no-op'ed
     * NativeLibrariesBootstrap and keeps returning the (now null) library.
     */
    static final String[][] ENTRIES = {
            {"SDLClipboard", "SDL_GetClipboardText", "()Ljava/lang/String;"},
            {"SDLClipboard", "SDL_SetClipboardText", "(Ljava/lang/CharSequence;)Z"},
            {"SDLError", "SDL_GetError", "()Ljava/lang/String;"},
            {"SDLEvents", "SDL_FlushEvents", "(II)V"},
            {"SDLEvents", "SDL_GetWindowFromEvent", "(Lorg/lwjgl/sdl/SDL_Event;)J"},
            {"SDLEvents", "SDL_PollEvent", "(Lorg/lwjgl/sdl/SDL_Event;)Z"},
            {"SDLEvents", "SDL_PumpEvents", "()V"},
            {"SDLHints", "SDL_SetHint", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z"},
            {"SDLInit", "SDL_Init", "(I)Z"},
            {"SDLInit", "SDL_Quit", "()V"},
            {"SDLInit", "SDL_SetAppMetadataProperty", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z"},
            {"SDLKeyboard", "SDL_ClearComposition", "(J)Z"},
            {"SDLKeyboard", "SDL_GetKeyFromScancode", "(ISZ)I"},
            {"SDLKeyboard", "SDL_GetKeyName", "(I)Ljava/lang/String;"},
            {"SDLKeyboard", "SDL_GetKeyboardState", "()Ljava/nio/ByteBuffer;"},
            {"SDLKeyboard", "SDL_GetModState", "()S"},
            {"SDLKeyboard", "SDL_SetTextInputArea", "(JLorg/lwjgl/sdl/SDL_Rect$Buffer;I)Z"},
            {"SDLKeyboard", "SDL_StartTextInput", "(J)Z"},
            {"SDLKeyboard", "SDL_StopTextInput", "(J)Z"},
            {"SDLLog", "SDL_SetLogOutputFunction", "(Lorg/lwjgl/sdl/SDL_LogOutputFunctionI;J)V"},
            {"SDLLog", "SDL_SetLogPriorities", "(I)V"},
            {"SDLMessageBox", "SDL_ShowMessageBox", "(Lorg/lwjgl/sdl/SDL_MessageBoxData;Ljava/nio/IntBuffer;)Z"},
            {"SDLMessageBox", "SDL_ShowSimpleMessageBox", "(ILjava/lang/CharSequence;Ljava/lang/CharSequence;J)Z"},
            {"SDLMisc", "SDL_OpenURL", "(Ljava/lang/CharSequence;)Z"},
            {"SDLMouse", "SDL_CreateSystemCursor", "(I)J"},
            {"SDLMouse", "SDL_GetDefaultCursor", "()J"},
            {"SDLMouse", "SDL_GetGlobalMouseState", "(Ljava/nio/FloatBuffer;Ljava/nio/FloatBuffer;)I"},
            {"SDLMouse", "SDL_GetMouseState", "(Ljava/nio/FloatBuffer;Ljava/nio/FloatBuffer;)I"},
            {"SDLMouse", "SDL_SetCursor", "(J)Z"},
            {"SDLMouse", "SDL_SetWindowRelativeMouseMode", "(JZ)Z"},
            {"SDLMouse", "SDL_WarpMouseInWindow", "(JFF)V"},
            {"SDLPixels", "SDL_GetPixelFormatDetails", "(I)Lorg/lwjgl/sdl/SDL_PixelFormatDetails;"},
            {"SDLPlatform", "SDL_GetPlatform", "()Ljava/lang/String;"},
            {"SDLStdinc", "SDL_free", "(Ljava/nio/IntBuffer;)V"},
            {"SDLStdinc", "SDL_free", "(Lorg/lwjgl/PointerBuffer;)V"},
            {"SDLSurface", "SDL_AddSurfaceAlternateImage", "(Lorg/lwjgl/sdl/SDL_Surface;Lorg/lwjgl/sdl/SDL_Surface;)Z"},
            {"SDLSurface", "SDL_CreateSurfaceFrom", "(IIILjava/nio/ByteBuffer;I)Lorg/lwjgl/sdl/SDL_Surface;"},
            {"SDLSurface", "SDL_DestroySurface", "(Lorg/lwjgl/sdl/SDL_Surface;)V"},
            {"SDLTimer", "SDL_GetTicksNS", "()J"},
            {"SDLVideo", "SDL_CreateWindow", "(Ljava/lang/CharSequence;IIJ)J"},
            {"SDLVideo", "SDL_DestroyWindow", "(J)V"},
            {"SDLVideo", "SDL_GL_CreateContext", "(J)J"},
            {"SDLVideo", "SDL_GL_DestroyContext", "(J)Z"},
            {"SDLVideo", "SDL_GL_GetAttribute", "(ILjava/nio/IntBuffer;)Z"},
            {"SDLVideo", "SDL_GL_GetProcAddress", "(Ljava/lang/CharSequence;)J"},
            {"SDLVideo", "SDL_GL_LoadLibrary", "(Ljava/lang/CharSequence;)Z"},
            {"SDLVideo", "SDL_GL_MakeCurrent", "(JJ)Z"},
            {"SDLVideo", "SDL_GL_SetAttribute", "(II)Z"},
            {"SDLVideo", "SDL_GL_SetSwapInterval", "(I)Z"},
            {"SDLVideo", "SDL_GL_SwapWindow", "(J)Z"},
            {"SDLVideo", "SDL_GL_UnloadLibrary", "()V"},
            {"SDLVideo", "SDL_GetClosestFullscreenDisplayMode", "(IIIFZLorg/lwjgl/sdl/SDL_DisplayMode;)Z"},
            {"SDLVideo", "SDL_GetCurrentDisplayMode", "(I)Lorg/lwjgl/sdl/SDL_DisplayMode;"},
            {"SDLVideo", "SDL_GetCurrentVideoDriver", "()Ljava/lang/String;"},
            {"SDLVideo", "SDL_GetDesktopDisplayMode", "(I)Lorg/lwjgl/sdl/SDL_DisplayMode;"},
            {"SDLVideo", "SDL_GetDisplayBounds", "(ILorg/lwjgl/sdl/SDL_Rect;)Z"},
            {"SDLVideo", "SDL_GetDisplayForWindow", "(J)I"},
            {"SDLVideo", "SDL_GetDisplayName", "(I)Ljava/lang/String;"},
            {"SDLVideo", "SDL_GetDisplays", "()Ljava/nio/IntBuffer;"},
            {"SDLVideo", "SDL_GetFullscreenDisplayModes", "(I)Lorg/lwjgl/PointerBuffer;"},
            {"SDLVideo", "SDL_GetPrimaryDisplay", "()I"},
            {"SDLVideo", "SDL_GetWindowFlags", "(J)J"},
            {"SDLVideo", "SDL_GetWindowFullscreenMode", "(J)Lorg/lwjgl/sdl/SDL_DisplayMode;"},
            {"SDLVideo", "SDL_GetWindowPixelDensity", "(J)F"},
            {"SDLVideo", "SDL_GetWindowPosition", "(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Z"},
            {"SDLVideo", "SDL_GetWindowSizeInPixels", "(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Z"},
            {"SDLVideo", "SDL_RestoreWindow", "(J)Z"},
            {"SDLVideo", "SDL_SetWindowBordered", "(JZ)Z"},
            {"SDLVideo", "SDL_SetWindowFullscreen", "(JZ)Z"},
            {"SDLVideo", "SDL_SetWindowFullscreenMode", "(JLorg/lwjgl/sdl/SDL_DisplayMode;)Z"},
            {"SDLVideo", "SDL_SetWindowIcon", "(JLorg/lwjgl/sdl/SDL_Surface;)Z"},
            {"SDLVideo", "SDL_SetWindowMaximumSize", "(JII)Z"},
            {"SDLVideo", "SDL_SetWindowMinimumSize", "(JII)Z"},
            {"SDLVideo", "SDL_SetWindowMouseGrab", "(JZ)Z"},
            {"SDLVideo", "SDL_SetWindowPosition", "(JII)Z"},
            {"SDLVideo", "SDL_SetWindowSize", "(JII)Z"},
            {"SDLVideo", "SDL_SetWindowTitle", "(JLjava/lang/CharSequence;)Z"},
            {"SDLVideo", "SDL_SyncWindow", "(J)Z"},
            {"SDLVulkan", "SDL_Vulkan_CreateSurface",
                    "(JLorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)Z"},
            {"SDLVulkan", "SDL_Vulkan_GetInstanceExtensions", "()Lorg/lwjgl/PointerBuffer;"},
            {"SDLVulkan", "SDL_Vulkan_GetPresentationSupport",
                    "(Lorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkPhysicalDevice;I)Z"},
            {"SDLVulkan", "SDL_Vulkan_GetVkGetInstanceProcAddr", "()J"},
            {"SDLVulkan", "SDL_Vulkan_LoadLibrary", "(Ljava/lang/CharSequence;)Z"},
            {"SDLVulkan", "SDL_Vulkan_UnloadLibrary", "()V"},
    };

    private LwjglSdlBrowserPatcher() {
    }

    /** {@code org/lwjgl/sdl/<Owner>.<name><descriptor>} of every redirected entry point. */
    public static Set<String> entryKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String[] entry : ENTRIES) {
            keys.add(PACKAGE + entry[0] + "." + entry[1] + entry[2]);
        }
        return Collections.unmodifiableSet(keys);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglSdlBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        if (ENTRIES.length != ENTRY_COUNT || entryKeys().size() != ENTRY_COUNT) {
            throw new IllegalStateException("SDL entry table has " + ENTRIES.length + " rows and "
                    + entryKeys().size() + " distinct keys, expected " + ENTRY_COUNT);
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        requireBrowserTargets(jar);
        patchLibraryInitializer(jar, root.resolve(SDL + ".class"));
        int replaced = redirectEntries(jar, root);
        if (replaced != ENTRY_COUNT) {
            throw new IllegalStateException("Redirected " + replaced + " SDL entry points, expected "
                    + ENTRY_COUNT);
        }
        System.out.println("LwjglSdlBrowserPatcher: redirected " + replaced
                + " SDL entry points to " + BROWSER + "; SDL.<clinit> loads no native library");
    }

    private static void requireBrowserTargets(String jar) throws IOException {
        ClassNode browser = read(jar, BROWSER + ".class");
        Set<String> targets = new LinkedHashSet<>();
        for (MethodNode method : browser.methods) {
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) {
                targets.add(method.name + method.desc);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String[] entry : ENTRIES) {
            if (!targets.contains(entry[1] + entry[2])) {
                missing.add(entry[0] + "." + entry[1] + entry[2]);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(BROWSER + " is missing public static targets: "
                    + String.join(", ", missing));
        }
    }

    private static void patchLibraryInitializer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, SDL + ".class");
        MethodNode initializer = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                initializer = method;
            }
        }
        if (initializer == null) {
            throw new IllegalStateException(SDL + ".<clinit> not found");
        }
        int loads = 0;
        int stores = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/lwjgl/system/Library")
                    && call.name.equals("loadNative")) {
                loads++;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(SDL)) {
                if (!field.name.equals("SDL") || !field.desc.equals("Lorg/lwjgl/system/SharedLibrary;")) {
                    throw new IllegalStateException("Unexpected SDL.<clinit> store " + field.name);
                }
                stores++;
            }
        }
        if (loads != 1 || stores != 1) {
            throw new IllegalStateException("SDL.<clinit> shape changed: loadNative=" + loads
                    + " stores=" + stores);
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, SDL, "SDL", "Lorg/lwjgl/system/SharedLibrary;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, code, 1);
        write(node, output);
    }

    private static int redirectEntries(String jar, Path root) throws IOException {
        Map<String, Map<String, Boolean>> byOwner = new LinkedHashMap<>();
        for (String[] entry : ENTRIES) {
            byOwner.computeIfAbsent(entry[0], owner -> new LinkedHashMap<>())
                    .put(entry[1] + entry[2], Boolean.FALSE);
        }
        int replaced = 0;
        for (Map.Entry<String, Map<String, Boolean>> owner : byOwner.entrySet()) {
            String className = PACKAGE + owner.getKey();
            ClassNode node = read(jar, className + ".class");
            Map<String, Boolean> wanted = owner.getValue();
            for (MethodNode method : node.methods) {
                String key = method.name + method.desc;
                Boolean seen = wanted.get(key);
                if (seen == null) {
                    continue;
                }
                if (seen) {
                    throw new IllegalStateException("Duplicate method " + className + "." + key);
                }
                if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                        || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                    throw new IllegalStateException(className + "." + key + " is not a public static method");
                }
                delegate(method);
                wanted.put(key, Boolean.TRUE);
                replaced++;
            }
            List<String> missing = new ArrayList<>();
            wanted.forEach((key, done) -> {
                if (!done) {
                    missing.add(className + "." + key);
                }
            });
            if (!missing.isEmpty()) {
                throw new IllegalStateException("SDL entry points not found: " + String.join(", ", missing));
            }
            write(node, root.resolve(className + ".class"));
        }
        return replaced;
    }

    private static void delegate(MethodNode method) {
        InsnList code = new InsnList();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER, method.name, method.desc, false));
        code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(2, local));
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
