package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Removes desktop-only diagnostics from the official browser client graph. */
public final class MinecraftClientPatcher {
    private MinecraftClientPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[1]);
        patchNativeModuleLister(
                args[0], root.resolve("net/minecraft/util/NativeModuleLister.class"));
        patchJvmProfiler(
                args[0], root.resolve("net/minecraft/util/profiling/jfr/JvmProfiler.class"));
        patchEventLoopGroupHolder(
                args[0], root.resolve("net/minecraft/server/network/EventLoopGroupHolder.class"));
        patchNetworkEncoderMatchers(args[0], root);
        patchClassTreeIdRegistry(args[0], root.resolve("net/minecraft/util/ClassTreeIdRegistry.class"));
        patchSynchedEntityDataClassInitialization(
                args[0], root.resolve("net/minecraft/network/syncher/SynchedEntityData.class"));
        patchGlx(args[0], root.resolve("com/mojang/blaze3d/platform/GLX.class"));
        patchFramerateLimitTrackerBrowserNoThrottle(args[0], root.resolve(
                "com/mojang/blaze3d/platform/FramerateLimitTracker.class"));
        patchTracyZoneFiller(
                args[0], root.resolve("net/minecraft/util/profiling/TracyZoneFiller.class"));
        patchMacosUtil(
                args[0], root.resolve("com/mojang/blaze3d/platform/MacosUtil.class"));
        patchInputConstants(
                args[0], root.resolve("com/mojang/blaze3d/platform/InputConstants.class"));
        patchMemoryDebug(args[0], root.resolve(
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class"));
        patchMainBrowserStorageMount(args[0], root.resolve("net/minecraft/client/main/Main.class"));
        patchMinecraft(args[0], root.resolve("net/minecraft/client/Minecraft.class"));
        patchGuiGraphicsBrowserItemCache(args[0], root.resolve(
                "net/minecraft/client/gui/GuiGraphics.class"));
        patchFreeTypeUtil(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class"));
        patchDebugMemoryUntracker(args[0], root.resolve(
                "com/mojang/blaze3d/platform/DebugMemoryUntracker.class"));
        patchMinecraftServer(args[0], root.resolve(
                "net/minecraft/server/MinecraftServer.class"));
        patchIntegratedServerBrowserDistances(args[0], root.resolve(
                "net/minecraft/client/server/IntegratedServer.class"));
        patchPlayerListBrowserDistances(args[0], root.resolve(
                "net/minecraft/server/players/PlayerList.class"));
        patchServerLevelBrowserSafeDefaults(args[0], root.resolve(
                "net/minecraft/server/level/ServerLevel.class"));
        patchChaseClient(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseClient.class"));
        patchLanServerPinger(args[0], root.resolve(
                "net/minecraft/client/server/LanServerPinger.class"));
        patchHttpUtil(args[0], root.resolve("net/minecraft/util/HttpUtil.class"));
        patchUtilJarFileSystem(args[0], root.resolve("net/minecraft/util/Util.class"));
        patchResourceKeyRegistryRoot(args[0], root.resolve("net/minecraft/resources/ResourceKey.class"));
        patchVanillaPackResourcesBuilder(args[0], root.resolve(
                "net/minecraft/server/packs/VanillaPackResourcesBuilder.class"));
        patchIndexedAssetSourceBrowserNoop(args[0], root.resolve(
                "net/minecraft/client/resources/IndexedAssetSource.class"));
        patchLocalTimeItemModelProperty(args[0], root.resolve(
                "net/minecraft/client/renderer/item/properties/select/LocalTime.class"));
        patchLanServerDetector(args[0], root.resolve(
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class"));
        patchPackWatcher(args[0], root.resolve(
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class"));
        patchChaseServer(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseServer.class"));
        patchOpenUri(args[0], root.resolve("net/minecraft/util/Util$OS.class"));
        patchRealmsNetwork(args[0], root);
        patchReflectivePatternArray(args[0], root.resolve(
                "net/minecraft/world/level/block/state/pattern/BlockPatternBuilder.class"));
        patchChunkPosSpliterator(args[0], root.resolve(
                "net/minecraft/world/level/ChunkPos$2.class"));
        patchDetectedVersion(args[0], root.resolve("net/minecraft/DetectedVersion.class"));
        patchSingleplayerCrypto(args[0], root);
        patchSingleplayerLogin(args[0], root.resolve(
                "net/minecraft/server/network/ServerLoginPacketListenerImpl.class"));
        patchChatSigning(args[0], root);
        patchMultiplayerExecutor(args[0], root.resolve(
                "net/minecraft/client/gui/screens/multiplayer/"
                        + "ServerSelectionList$OnlineServerEntry.class"));
        patchClientShutdownWatchdog(args[0], root.resolve(
                "com/mojang/blaze3d/platform/ClientShutdownWatchdog.class"));
        patchAccountProfileKeys(args[0], root.resolve(
                "net/minecraft/client/multiplayer/AccountProfileKeyPairManager.class"));
        patchScreenBrowserFastMenus(args[0], root.resolve(
                "net/minecraft/client/gui/screens/Screen.class"));
        patchTitleScreenBrowserFastMenus(args[0], root.resolve(
                "net/minecraft/client/gui/screens/TitleScreen.class"));
        patchAbstractButtonBrowserFastSprite(args[0], root.resolve(
                "net/minecraft/client/gui/components/AbstractButton.class"));
        patchCreateWorldScreenBrowserDefaults(args[0], root.resolve(
                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.class"));
        patchWorldSelectionListTelemetry(args[0], root.resolve(
                "net/minecraft/client/gui/screens/worldselection/WorldSelectionList.class"));
        patchBrowserAudio(args[0], root.resolve(
                "com/mojang/blaze3d/audio/Library.class"));
        patchBrowserAudioListener(args[0], root.resolve(
                "com/mojang/blaze3d/audio/Listener.class"));
        patchSoundEngineBrowserSilent(args[0], root.resolve(
                "net/minecraft/client/sounds/SoundEngine.class"));
        patchGlslPreprocessor(args[0], root.resolve(
                "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class"));
        patchGlDevice(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlDevice.class"));
        patchGlConstWebGLTextureFormats(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlConst.class"));
        patchTextureFormatWebGLColorAspect(args[0], root.resolve(
                "com/mojang/blaze3d/textures/TextureFormat.class"));
        patchGlStateManagerTextureBinding(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlStateManager.class"));
        patchGlCommandEncoder(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlCommandEncoder.class"));
        patchGameRendererBrowserAutoScreenshot(args[0], root.resolve(
                "net/minecraft/client/renderer/GameRenderer.class"));
        patchParticleGroupBrowserTickSafety(args[0], root.resolve(
                "net/minecraft/client/particle/ParticleGroup.class"));
        patchClientLevelBrowserBlockBreakEffects(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientLevel.class"));
        patchLevelRendererBrowserBlockBreakProgress(args[0], root.resolve(
                "net/minecraft/client/renderer/LevelRenderer.class"));
        patchFaceBakeryBrowserFloatTolerance(args[0], root.resolve(
                "net/minecraft/client/renderer/block/model/FaceBakery.class"));
        patchLevelLoadTrackerBrowserTimeout(args[0], root);
        patchClientPacketListenerLoadingDiagnostics(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientPacketListener.class"));
        patchWorldUnloadTelemetry(args[0], root.resolve(
                "net/minecraft/client/telemetry/events/WorldUnloadEvent.class"));
        generateSoundApiStubs(root);
        generateCryptoApiStubs(root);
        generateUnsafeStub(root);
    }

    private static void patchMainBrowserStorageMount(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/main/Main.class");
        MethodNode main = find(node, "main", "([Ljava/lang/String;)V");
        main.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "mount",
                "()V",
                false));
        write(node, output);
    }

    private static void patchFramerateLimitTrackerBrowserNoThrottle(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/FramerateLimitTracker.class");
        MethodNode method = find(
                node,
                "getThrottleReason",
                "()Lcom/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason;");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason",
                "NONE",
                "Lcom/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, output);
    }

    private static void patchFaceBakeryBrowserFloatTolerance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/block/model/FaceBakery.class");
        MethodNode method = find(node, "findVertex", "([Lorg/joml/Vector3fc;IFFF)I");

        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode end = new LabelNode();

        // Vanilla compares the baked winding vertex coordinates with exact fcmpl checks.
        // In the TeaVM/WebGL path tiny float differences can appear during model baking,
        // which turns valid quads into "Can't find vertex to swap" warnings and stalls
        // startup. Keep the same search order, but accept a tight browser-safe epsilon.
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));

        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        addFloatAbsGreaterThanEpsilonJump(code, 2, "x", next);
        addFloatAbsGreaterThanEpsilonJump(code, 3, "y", next);
        addFloatAbsGreaterThanEpsilonJump(code, 4, "z", next);

        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.IRETURN));

        code.add(next);
        code.add(new IincInsnNode(5, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));

        code.add(end);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));

        replace(method, code, 4, 7);
        write(node, output);
    }

    private static void addFloatAbsGreaterThanEpsilonJump(
            InsnList code, int expectedLocal, String accessor, LabelNode jumpTarget) {
        code.add(new VarInsnNode(Opcodes.FLOAD, expectedLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/joml/Vector3fc",
                accessor,
                "()F",
                true));
        code.add(new InsnNode(Opcodes.FSUB));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "abs",
                "(F)F",
                false));
        code.add(new LdcInsnNode(Float.valueOf(1.0E-4f)));
        code.add(new InsnNode(Opcodes.FCMPG));
        code.add(new JumpInsnNode(Opcodes.IFGT, jumpTarget));
    }

    private static void patchWorldSelectionListTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/worldselection/WorldSelectionList.class");
        MethodNode renderWidget = find(node, "renderWidget",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportWorldSelectionList",
                "(Ljava/lang/Object;)V",
                false));
        renderWidget.instructions.insert(code);
        renderWidget.maxStack = Math.max(renderWidget.maxStack, 1);
        write(node, output);
    }

    private static void patchCreateWorldScreenBrowserDefaults(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.class");
        boolean foundNormalPreset = false;
        boolean foundDefaultOptions = false;
        boolean foundNormalDimensions = false;
        boolean patchedAllowCommands = false;
        boolean patchedInitialAllowCommands = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("openFresh")
                    && method.desc.equals("(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals("net/minecraft/world/level/levelgen/presets/WorldPresets")
                            && field.name.equals("NORMAL")
                            && field.desc.equals("Lnet/minecraft/resources/ResourceKey;")) {
                        foundNormalPreset = true;
                    }
                }
            } else if (method.name.equals("lambda$openFresh$4")
                    && method.desc.equals("(Lnet/minecraft/server/WorldLoader$DataLoadContext;)"
                            + "Lnet/minecraft/world/level/levelgen/WorldGenSettings;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/world/level/levelgen/WorldOptions")
                            && call.name.equals("defaultWithRandomSeed")
                            && call.desc.equals("()Lnet/minecraft/world/level/levelgen/WorldOptions;")) {
                        foundDefaultOptions = true;
                    } else if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/world/level/levelgen/presets/WorldPresets")
                            && call.name.equals("createNormalWorldDimensions")
                            && call.desc.equals("(Lnet/minecraft/core/HolderLookup$Provider;)"
                                    + "Lnet/minecraft/world/level/levelgen/WorldDimensions;")) {
                        foundNormalDimensions = true;
                    }
                }
            } else if (method.name.equals("createLevelSettings")
                    && method.desc.equals("(Z)Lnet/minecraft/world/level/LevelSettings;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/client/gui/screens/worldselection/WorldCreationUiState")
                            && call.name.equals("isAllowCommands")
                            && call.desc.equals("()Z")) {
                        InsnList replacement = new InsnList();
                        replacement.add(new InsnNode(Opcodes.POP));
                        replacement.add(new InsnNode(Opcodes.ICONST_1));
                        method.instructions.insertBefore(call, replacement);
                        method.instructions.remove(call);
                        patchedAllowCommands = true;
                        break;
                    }
                }
            } else if (method.name.equals("<init>")
                    && method.desc.equals("(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;"
                            + "Ljava/util/Optional;Ljava/util/OptionalLong;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.PUTFIELD
                            && field.owner.equals("net/minecraft/client/gui/screens/worldselection/CreateWorldScreen")
                            && field.name.equals("uiState")
                            && field.desc.equals("Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;")) {
                        InsnList enableCommands = new InsnList();
                        enableCommands.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        enableCommands.add(new FieldInsnNode(
                                Opcodes.GETFIELD,
                                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen",
                                "uiState",
                                "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;"));
                        enableCommands.add(new InsnNode(Opcodes.ICONST_1));
                        enableCommands.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/client/gui/screens/worldselection/WorldCreationUiState",
                                "setAllowCommands",
                                "(Z)V",
                                false));
                        method.instructions.insert(field, enableCommands);
                        method.maxStack = Math.max(method.maxStack, 3);
                        patchedInitialAllowCommands = true;
                        break;
                    }
                }
            }
        }
        if (!foundNormalPreset
                || !foundDefaultOptions
                || !foundNormalDimensions
                || !patchedAllowCommands
                || !patchedInitialAllowCommands) {
            throw new IllegalStateException("CreateWorldScreen browser default world patch points were not found");
        }
        write(node, output);
    }

    private static void patchSynchedEntityDataClassInitialization(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/syncher/SynchedEntityData.class");
        MethodNode defineId = find(node, "defineId",
                "(Ljava/lang/Class;Lnet/minecraft/network/syncher/EntityDataSerializer;)"
                        + "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        boolean patched = false;
        for (var instruction = defineId.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                    && field.name.equals("ID_REGISTRY")
                    && field.desc.equals("Lnet/minecraft/util/ClassTreeIdRegistry;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "net/minecraft/network/syncher/SynchedEntityData",
                        "gaius$initializeSynchedDataSuperclasses",
                        "(Ljava/lang/Class;)V",
                        false));
                defineId.instructions.insertBefore(instruction, code);
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("SynchedEntityData.defineId registry patch point was not found");
        }
        addInitializeSynchedDataSuperclassesHelper(node);
        writeComputeFrames(node, output);
    }

    private static void patchScreenBrowserFastMenus(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/Screen.class");

        MethodNode panorama = find(node, "renderPanorama",
                "(Lnet/minecraft/client/gui/GuiGraphics;F)V");
        InsnList panoramaCode = new InsnList();
        panoramaCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        panoramaCode.add(new InsnNode(Opcodes.ICONST_0));
        panoramaCode.add(new InsnNode(Opcodes.ICONST_0));
        panoramaCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        panoramaCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/screens/Screen",
                "width",
                "I"));
        panoramaCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        panoramaCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/screens/Screen",
                "height",
                "I"));
        panoramaCode.add(new LdcInsnNode(0xFF101820));
        panoramaCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/GuiGraphics",
                "fill",
                "(IIIII)V",
                false));
        panoramaCode.add(new InsnNode(Opcodes.RETURN));
        replace(panorama, panoramaCode, 6, 3);

        MethodNode menuBackground = find(node, "renderMenuBackground",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIII)V");
        InsnList menuCode = new InsnList();
        menuCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        menuCode.add(new InsnNode(Opcodes.IADD));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        menuCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        menuCode.add(new InsnNode(Opcodes.IADD));
        menuCode.add(new LdcInsnNode(0xC0101820));
        menuCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/GuiGraphics",
                "fill",
                "(IIIII)V",
                false));
        menuCode.add(new InsnNode(Opcodes.RETURN));
        replace(menuBackground, menuCode, 6, 6);

        write(node, output);
    }

    private static void patchTitleScreenBrowserFastMenus(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/TitleScreen.class");
        MethodNode realms = find(node, "realmsNotificationsEnabled", "()Z");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(realms, code, 1, 1);
        write(node, output);
    }

    private static void patchAbstractButtonBrowserFastSprite(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/components/AbstractButton.class");
        MethodNode method = find(node, "renderDefaultSprite",
                "(Lnet/minecraft/client/gui/GuiGraphics;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getY",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getWidth",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getY",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getHeight",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new LdcInsnNode(0xD02A3440));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/GuiGraphics",
                "fill",
                "(IIIII)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 6, 2);
        write(node, output);
    }

    private static void patchGuiGraphicsBrowserItemCache(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/GuiGraphics.class");
        MethodNode method = find(node, "renderItem",
                "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/item/ItemStack;III)V");
        AbstractInsnNode start = null;
        AbstractInsnNode end = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (start == null
                    && instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/client/renderer/item/TrackingItemStackRenderState")) {
                start = instruction;
            }
            if (start != null
                    && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/item/ItemModelResolver")
                    && call.name.equals("updateForTopItem")
                    && call.desc.equals("(Lnet/minecraft/client/renderer/item/ItemStackRenderState;"
                            + "Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/entity/ItemOwner;I)V")) {
                end = instruction;
                break;
            }
        }
        if (start == null || end == null) {
            throw new IllegalStateException("GuiGraphics browser item cache patch points were not found");
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/GuiGraphics",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 3));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new VarInsnNode(Opcodes.ILOAD, 6));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiItemCache",
                "guiState",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)"
                        + "Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;",
                false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, 7));

        method.instructions.insertBefore(start, replacement);
        for (var instruction = start; instruction != null;) {
            var next = instruction.getNext();
            method.instructions.remove(instruction);
            if (instruction == end) {
                break;
            }
            instruction = next;
        }
        method.maxStack = Math.max(method.maxStack, 6);
        writeComputeFrames(node, output);
    }

    private static void addInitializeSynchedDataSuperclassesHelper(ClassNode node) {
        node.methods.removeIf(method -> method.name.equals("gaius$initializeSynchedDataSuperclasses")
                && method.desc.equals("(Ljava/lang/Class;)V"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$initializeSynchedDataSuperclasses",
                "(Ljava/lang/Class;)V",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode done = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getSuperclass",
                "()Ljava/lang/Class;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new LdcInsnNode(Type.getObjectType("java/lang/Object")));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/network/syncher/SynchedEntityData",
                "gaius$initializeSynchedDataSuperclasses",
                "(Ljava/lang/Class;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "initialize",
                "()V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        node.methods.add(method);
    }

    private static void patchClassTreeIdRegistry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/ClassTreeIdRegistry.class");
        MethodNode method = find(node, "getLastIdFor", "(Ljava/lang/Class;)I");
        InsnList code = new InsnList();
        LabelNode scanAssignable = new LabelNode();
        LabelNode superclassLoop = new LabelNode();
        LabelNode scanLoop = new LabelNode();
        LabelNode returnBest = new LabelNode();
        LabelNode superclassNameLookup = new LabelNode();

        // int exact = classToLastIdCache.getInt(clazz);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "getInt",
                "(Ljava/lang/Object;)I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassLoop));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Keep vanilla behavior first: walk Class.getSuperclass().
        code.add(superclassLoop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode superclassNext = new LabelNode();
        code.add(superclassNext);
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getSuperclass",
                "()Ljava/lang/Class;",
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, scanAssignable));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new LdcInsnNode(Type.getObjectType("java/lang/Object")));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, scanAssignable));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "getInt",
                "(Ljava/lang/Object;)I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassNameLookup));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(superclassNameLookup);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/util/ClassTreeIdRegistry",
                "gaius$getCachedIdByName",
                "(Ljava/lang/Class;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassNext));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Browser fallback: Class objects produced by getSuperclass() can miss
        // map identity matches after TeaVM translation. Scan known registered
        // classes and use assignability to find the deepest inherited data id.
        code.add(scanAssignable);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "object2IntEntrySet",
                "()Lit/unimi/dsi/fastutil/objects/ObjectSet;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Iterable",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(scanLoop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, returnBest));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Class"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, scanLoop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "isAssignableFrom",
                "(Ljava/lang/Class;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, scanLoop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry",
                "getIntValue",
                "()I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 8));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, scanLoop));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new JumpInsnNode(Opcodes.GOTO, scanLoop));
        code.add(returnBest);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));

        replace(method, code, 4, 9);
        addCachedIdByNameHelper(node);
        writeComputeFrames(node, output);
    }

    private static void addCachedIdByNameHelper(ClassNode node) {
        node.methods.removeIf(method -> method.name.equals("gaius$getCachedIdByName")
                && method.desc.equals("(Ljava/lang/Class;)I"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "gaius$getCachedIdByName",
                "(Ljava/lang/Class;)I",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode loop = new LabelNode();
        LabelNode missing = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getName",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "object2IntEntrySet",
                "()Lit/unimi/dsi/fastutil/objects/ObjectSet;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Iterable",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, missing));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Class"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getName",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, loop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry",
                "getIntValue",
                "()I",
                true));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(missing);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 5;
        node.methods.add(method);
    }

    private static void patchNetworkEncoderMatchers(String jar, Path root) throws IOException {
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/PacketEncoder.class"),
                "net/minecraft/network/PacketEncoder",
                "net/minecraft/network/protocol/Packet");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/PacketBundleUnpacker.class"),
                "net/minecraft/network/PacketBundleUnpacker",
                "net/minecraft/network/protocol/Packet");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/Varint21LengthFieldPrepender.class"),
                "net/minecraft/network/Varint21LengthFieldPrepender",
                "io/netty/buffer/ByteBuf");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/CipherEncoder.class"),
                "net/minecraft/network/CipherEncoder",
                "io/netty/buffer/ByteBuf");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/CompressionEncoder.class"),
                "net/minecraft/network/CompressionEncoder",
                "io/netty/buffer/ByteBuf");
    }

    private static void patchAcceptOutboundMessage(
            String jar, Path output, String owner, String acceptedInternalName) throws IOException {
        ClassNode node = read(jar, owner + ".class");
        node.methods.removeIf(method -> method.name.equals("acceptOutboundMessage")
                && method.desc.equals("(Ljava/lang/Object;)Z"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "acceptOutboundMessage",
                "(Ljava/lang/Object;)Z",
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, acceptedInternalName));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        node.methods.add(method);
        write(node, output);
    }

    private static void patchGlDevice(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlDevice.class");
        String owner = "com/mojang/blaze3d/opengl/GlDevice";
        boolean patchedCapability = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        owner,
                        "USE_GL_ARB_vertex_attrib_binding",
                        "Z"));
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, code);
                        method.maxStack = Math.max(method.maxStack, 1);
                        patchedCapability = true;
                        break;
                    }
                }
            }
        }
        MethodNode maxTextureSize = find(node, "getMaxSupportedTextureSize", "()I");
        InsnList code = new InsnList();
        code.add(new IntInsnNode(Opcodes.SIPUSH, 3379));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlStateManager",
                "_getInteger",
                "(I)I",
                false));
        code.add(new IntInsnNode(Opcodes.SIPUSH, 1024));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "max",
                "(II)I",
                false));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(maxTextureSize, code, 2, 0);
        if (!patchedCapability) {
            throw new IllegalStateException("GlDevice capability patch point was not found");
        }
        write(node, output);
    }

    private static void patchGlConstWebGLTextureFormats(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlConst.class");
        MethodNode method = find(node, "toGlInternalId",
                "(Lcom/mojang/blaze3d/textures/TextureFormat;)I");
        boolean patched = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldc
                    && Integer.valueOf(33329).equals(ldc.cst)) {
                // RED8I is GL_R8I on desktop. WebGL rejects sampling an integer
                // texture through Minecraft's ordinary sampler2D pipelines, so
                // normalize it to GL_R8 for the browser build.
                ldc.cst = 33321;
                patched = true;
            }
        }
        if (!patched) {
            throw new IllegalStateException("GlConst RED8I internal format patch point was not found");
        }
        write(node, output);
    }

    private static void patchTextureFormatWebGLColorAspect(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/textures/TextureFormat.class");
        MethodNode method = find(node, "hasColorAspect", "()Z");
        InsnList code = new InsnList();
        LabelNode depth = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/textures/TextureFormat",
                "DEPTH32",
                "Lcom/mojang/blaze3d/textures/TextureFormat;"));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, depth));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(depth);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 2, 1);
        writeComputeFrames(node, output);
    }

    private static void patchGlStateManagerTextureBinding(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlStateManager.class");
        String owner = "com/mojang/blaze3d/opengl/GlStateManager";
        boolean patchedBindTexture = false;
        boolean patchedActiveTexture = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("_bindTexture") && method.desc.equals("(I)V")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode(33984));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        owner,
                        "activeTexture",
                        "I"));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL13",
                        "glActiveTexture",
                        "(I)V",
                        false));
                code.add(new IntInsnNode(Opcodes.SIPUSH, 3553));
                code.add(new VarInsnNode(Opcodes.ILOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL11",
                        "glBindTexture",
                        "(II)V",
                        false));
                insertAfterFirstRenderThreadAssert(method, code);
                method.maxStack = Math.max(method.maxStack, 2);
                patchedBindTexture = true;
            } else if (method.name.equals("_activeTexture") && method.desc.equals("(I)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ILOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL13",
                        "glActiveTexture",
                        "(I)V",
                        false));
                insertAfterFirstRenderThreadAssert(method, code);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedActiveTexture = true;
            }
        }
        if (!patchedBindTexture || !patchedActiveTexture) {
            throw new IllegalStateException("GlStateManager texture binding patch points were not found");
        }
        writeComputeFrames(node, output);
    }

    private static void insertAfterFirstRenderThreadAssert(MethodNode method, InsnList code) {
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    && call.name.equals("assertOnRenderThread")
                    && call.desc.equals("()V")) {
                method.instructions.insert(instruction, code);
                return;
            }
        }
        throw new IllegalStateException("RenderSystem.assertOnRenderThread patch point was not found in "
                + method.name + method.desc);
    }

    private static void patchGlCommandEncoder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlCommandEncoder.class");
        String owner = "com/mojang/blaze3d/opengl/GlCommandEncoder";
        String pass = "com/mojang/blaze3d/opengl/GlRenderPass";
        String renderSystem = "com/mojang/blaze3d/systems/RenderSystem";
        String slice = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
        String buffer = "Lcom/mojang/blaze3d/buffers/GpuBuffer;";

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$bindDefaultUniforms",
                "(L" + pass + ";)V",
                null,
                null);
        bindDefaultSlice(helper, renderSystem, pass, "getProjectionMatrixBuffer", "Projection", slice);
        bindDefaultSlice(helper, renderSystem, pass, "getShaderFog", "Fog", slice);
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderSystem,
                "getGlobalSettingsUniform",
                "()" + buffer,
                false));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        org.objectweb.asm.tree.LabelNode noGlobals = new org.objectweb.asm.tree.LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, noGlobals));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new LdcInsnNode("Globals"));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pass,
                "setUniform",
                "(Ljava/lang/String;" + buffer + ")V",
                false));
        helper.instructions.add(noGlobals);
        bindDefaultSlice(helper, renderSystem, pass, "getShaderLights", "Lighting", slice);
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 3;
        helper.maxLocals = 2;
        node.methods.add(helper);

        MethodNode trySetup = find(
                node,
                "trySetup",
                "(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z");
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 1));
        call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "gaius$bindDefaultUniforms",
                "(L" + pass + ";)V",
                false));
        trySetup.instructions.insert(call);
        trySetup.maxStack = Math.max(trySetup.maxStack, 3);
        writeComputeFrames(node, output);
    }

    private static void bindDefaultSlice(
            MethodNode method, String renderSystem, String pass,
            String getter, String uniformName, String sliceDescriptor) {
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderSystem,
                getter,
                "()" + sliceDescriptor,
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        org.objectweb.asm.tree.LabelNode missing = new org.objectweb.asm.tree.LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, missing));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(uniformName));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pass,
                "setUniform",
                "(Ljava/lang/String;" + sliceDescriptor + ")V",
                false));
        method.instructions.add(missing);
    }

    private static void patchGlslPreprocessor(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class");
        MethodNode clinit = find(node, "<clinit>", "()V");
        boolean changed = false;
        for (var instruction = clinit.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) {
                continue;
            }
            String replacement = switch (text) {
                case "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*moj_import(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(?:\"(.*)\"|<(.*)>))" ->
                        "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*moj_import(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*(?:\"(.*)\"|<(.*)>))";
                case "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*version(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(\\d+))\\b" ->
                        "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*version(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*(\\d+))\\b";
                case "(?:^|\\v)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/(//[^\\v]*))*\\z" ->
                        null;
                case "(?:^|\\v)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/|(//[^\\v]*))*\\z" ->
                        "(?:^|\\n)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/|(//[^\\n]*))*$";
                default -> null;
            };
            if (replacement != null) {
                ldc.cst = replacement;
                changed = true;
            }
        }
        if (!changed) {
            throw new IllegalStateException("GlslPreprocessor regex constants were not found");
        }
        write(node, output);
    }

    private static void patchBrowserAudio(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/audio/Library.class");
        String owner = "com/mojang/blaze3d/audio/Library";
        String poolDescriptor = "Lcom/mojang/blaze3d/audio/Library$ChannelPool;";

        MethodNode init = find(node, "init", "(Ljava/lang/String;Z)V");
        InsnList initCode = new InsnList();
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "staticChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "streamingChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentDevice", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "context", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "supportsDisconnections", "Z"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode, 3, 3);

        MethodNode getDefaultDeviceName = find(
                node, "getDefaultDeviceName", "()Ljava/lang/String;");
        InsnList nullString = new InsnList();
        nullString.add(new InsnNode(Opcodes.ACONST_NULL));
        nullString.add(new InsnNode(Opcodes.ARETURN));
        replace(getDefaultDeviceName, nullString, 1, 0);

        MethodNode getCurrentDeviceName = find(
                node, "getCurrentDeviceName", "()Ljava/lang/String;");
        InsnList currentName = new InsnList();
        currentName.add(new VarInsnNode(Opcodes.ALOAD, 0));
        currentName.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "defaultDeviceName", "Ljava/lang/String;"));
        currentName.add(new InsnNode(Opcodes.ARETURN));
        replace(getCurrentDeviceName, currentName, 1, 1);

        MethodNode hasDefaultDeviceChanged = find(
                node, "hasDefaultDeviceChanged", "()Z");
        InsnList falseResult = new InsnList();
        falseResult.add(new InsnNode(Opcodes.ICONST_0));
        falseResult.add(new InsnNode(Opcodes.IRETURN));
        replace(hasDefaultDeviceChanged, falseResult, 1, 1);

        MethodNode getAvailableSoundDevices = find(
                node, "getAvailableSoundDevices", "()Ljava/util/List;");
        InsnList emptyList = new InsnList();
        emptyList.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false));
        emptyList.add(new InsnNode(Opcodes.ARETURN));
        replace(getAvailableSoundDevices, emptyList, 1, 1);

        MethodNode isCurrentDeviceDisconnected = find(
                node, "isCurrentDeviceDisconnected", "()Z");
        InsnList connected = new InsnList();
        connected.add(new InsnNode(Opcodes.ICONST_0));
        connected.add(new InsnNode(Opcodes.IRETURN));
        replace(isCurrentDeviceDisconnected, connected, 1, 1);

        MethodNode cleanup = find(node, "cleanup", "()V");
        InsnList cleanupCode = new InsnList();
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "staticChannels", poolDescriptor));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "streamingChannels", poolDescriptor));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new InsnNode(Opcodes.LCONST_0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentDevice", "J"));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new InsnNode(Opcodes.LCONST_0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "context", "J"));
        cleanupCode.add(new InsnNode(Opcodes.RETURN));
        replace(cleanup, cleanupCode, 3, 1);

        write(node, output);
    }

    private static void patchBrowserAudioListener(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/audio/Listener.class");
        String owner = "com/mojang/blaze3d/audio/Listener";
        MethodNode setTransform = find(
                node,
                "setTransform",
                "(Lcom/mojang/blaze3d/audio/ListenerTransform;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "transform",
                "Lcom/mojang/blaze3d/audio/ListenerTransform;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(setTransform, code, 2, 2);
        write(node, output);
    }

    private static void patchSoundEngineBrowserSilent(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/sounds/SoundEngine.class");
        String owner = "net/minecraft/client/sounds/SoundEngine";
        MethodNode loadLibrary = find(node, "loadLibrary", "()V");

        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "loaded", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "library",
                "Lcom/mojang/blaze3d/audio/Library;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/audio/Library",
                "init",
                "(Ljava/lang/String;Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "preloadQueue",
                "Ljava/util/List;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "clear",
                "()V",
                true));
        code.add(new LdcInsnNode("browser.sound.silent"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftEvent",
                "(Ljava/lang/String;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "loaded", "Z"));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(loadLibrary, code, 3, 1);

        write(node, output);
    }

    private static void patchClientShutdownWatchdog(String jar, Path output) throws IOException {
        ClassNode node = read(
                jar, "com/mojang/blaze3d/platform/ClientShutdownWatchdog.class");
        MethodNode start = find(
                node, "startShutdownWatchdog", "(Ljava/io/File;J)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(start, code, 0, 3);
        write(node, output);
    }

    private static void patchAccountProfileKeys(String jar, Path output) throws IOException {
        ClassNode node = read(
                jar, "net/minecraft/client/multiplayer/AccountProfileKeyPairManager.class");
        MethodNode prepare = find(
                node, "prepareKeyPair", "()Ljava/util/concurrent/CompletableFuture;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "empty",
                "()Ljava/util/Optional;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/CompletableFuture",
                "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(prepare, code, 1, 1);
        write(node, output);
    }

    private static void patchResourceKeyRegistryRoot(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/resources/ResourceKey.class");
        MethodNode createRegistryKey = find(
                node,
                "createRegistryKey",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/resources/ResourceKey;");
        InsnList code = new InsnList();
        code.add(new LdcInsnNode("root"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/resources/Identifier",
                "withDefaultNamespace",
                "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/resources/ResourceKey",
                "create",
                "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;)"
                        + "Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(createRegistryKey, code, 2, 1);

        MethodNode clinit = find(node, "<clinit>", "()V");
        InsnList init = new InsnList();
        init.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/concurrent/ConcurrentHashMap",
                "<init>",
                "()V",
                false));
        init.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                "net/minecraft/resources/ResourceKey",
                "VALUES",
                "Ljava/util/concurrent/ConcurrentMap;"));
        init.add(new InsnNode(Opcodes.RETURN));
        replace(clinit, init, 2, 0);
        write(node, output);
    }

    private static void patchSingleplayerCrypto(String jar, Path root) throws IOException {
        ClassNode crypt = read(jar, "net/minecraft/util/Crypt.class");
        MethodNode generate = find(crypt, "generateKeyPair", "()Ljava/security/KeyPair;");
        InsnList unavailable = new InsnList();
        unavailable.add(new InsnNode(Opcodes.ACONST_NULL));
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(generate, unavailable, 1, 0);
        replaceNull(crypt, "generateSecretKey", "()Ljavax/crypto/SecretKey;");
        replaceEmptyBytes(crypt, "digestData",
                "(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B");
        replaceNull(crypt, "decryptByteToSecretKey",
                "(Ljava/security/PrivateKey;[B)Ljavax/crypto/SecretKey;");
        replaceSecondByteArray(crypt, "encryptUsingKey", "(Ljava/security/Key;[B)[B");
        replaceSecondByteArray(crypt, "decryptUsingKey", "(Ljava/security/Key;[B)[B");
        replaceNull(crypt, "getCipher", "(ILjava/security/Key;)Ljavax/crypto/Cipher;");
        for (String methodName : new String[] {"byteToPrivateKey", "byteToPublicKey"}) {
            MethodNode parser = crypt.methods.stream()
                    .filter(method -> method.name.equals(methodName)
                            && method.desc.startsWith("([B)Ljava/security/"))
                    .findFirst()
                    .orElseThrow();
            InsnList noKey = new InsnList();
            noKey.add(new InsnNode(Opcodes.ACONST_NULL));
            noKey.add(new InsnNode(Opcodes.ARETURN));
            replace(parser, noKey, 1, 1);
        }
        write(crypt, root.resolve("net/minecraft/util/Crypt.class"));

        ClassNode data = read(
                jar, "net/minecraft/world/entity/player/ProfilePublicKey$Data.class");
        MethodNode equals = find(data, "equals", "(Ljava/lang/Object;)Z");
        InsnList identity = new InsnList();
        identity.add(new VarInsnNode(Opcodes.ALOAD, 0));
        identity.add(new VarInsnNode(Opcodes.ALOAD, 1));
        org.objectweb.asm.tree.LabelNode different = new org.objectweb.asm.tree.LabelNode();
        identity.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ACMPNE, different));
        identity.add(new InsnNode(Opcodes.ICONST_1));
        identity.add(new InsnNode(Opcodes.IRETURN));
        identity.add(different);
        identity.add(new InsnNode(Opcodes.ICONST_0));
        identity.add(new InsnNode(Opcodes.IRETURN));
        replace(equals, identity, 2, 2);
        write(data, root.resolve(
                "net/minecraft/world/entity/player/ProfilePublicKey$Data.class"));
    }

    private static void patchSingleplayerLogin(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/ServerLoginPacketListenerImpl.class");
        MethodNode hello = find(node, "handleHello",
                "(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/login/ServerboundHelloPacket",
                "name",
                "()Ljava/lang/String;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "requestedUsername",
                "Ljava/lang/String;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "requestedUsername",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/UUIDUtil",
                "createOfflineProfile",
                "(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "startClientVerification",
                "(Lcom/mojang/authlib/GameProfile;)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(hello, code, 3, 2);
        write(node, output);
    }

    private static void patchChatSigning(String jar, Path root) throws IOException {
        ClassNode validator = read(jar, "net/minecraft/util/SignatureValidator.class");
        replaceStaticFieldReturn(
                validator,
                "from",
                "(Ljava/security/PublicKey;Ljava/lang/String;)Lnet/minecraft/util/SignatureValidator;",
                "net/minecraft/util/SignatureValidator",
                "NO_VALIDATION",
                "Lnet/minecraft/util/SignatureValidator;");
        replaceStaticFieldReturn(
                validator,
                "from",
                "(Lcom/mojang/authlib/yggdrasil/ServicesKeySet;"
                        + "Lcom/mojang/authlib/yggdrasil/ServicesKeyType;)"
                        + "Lnet/minecraft/util/SignatureValidator;",
                "net/minecraft/util/SignatureValidator",
                "NO_VALIDATION",
                "Lnet/minecraft/util/SignatureValidator;");
        write(validator, root.resolve("net/minecraft/util/SignatureValidator.class"));

        ClassNode signer = read(jar, "net/minecraft/util/Signer.class");
        replaceNull(signer, "from",
                "(Ljava/security/PrivateKey;Ljava/lang/String;)Lnet/minecraft/util/Signer;");
        write(signer, root.resolve("net/minecraft/util/Signer.class"));

        ClassNode chain = read(jar, "net/minecraft/network/chat/SignedMessageChain.class");
        replaceStaticFieldReturn(
                chain,
                "encoder",
                "(Lnet/minecraft/util/Signer;)Lnet/minecraft/network/chat/SignedMessageChain$Encoder;",
                "net/minecraft/network/chat/SignedMessageChain$Encoder",
                "UNSIGNED",
                "Lnet/minecraft/network/chat/SignedMessageChain$Encoder;");
        write(chain, root.resolve("net/minecraft/network/chat/SignedMessageChain.class"));
    }

    private static void patchWorldUnloadTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/telemetry/events/WorldUnloadEvent.class");
        MethodNode method = find(node, "getTimeInSecondsSinceLoad", "(Ljava/time/Instant;)I");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, 2);
        write(node, output);
    }

    private static void patchGameRendererBrowserAutoScreenshot(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/GameRenderer.class");
        MethodNode renderLevel = find(node, "renderLevel",
                "(Lnet/minecraft/client/DeltaTracker;)V");
        InsnList skipLevelWhenScreenOpen = new InsnList();
        LabelNode noScreen = new LabelNode();
        skipLevelWhenScreenOpen.add(new VarInsnNode(Opcodes.ALOAD, 0));
        skipLevelWhenScreenOpen.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        skipLevelWhenScreenOpen.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        skipLevelWhenScreenOpen.add(new JumpInsnNode(Opcodes.IFNULL, noScreen));
        skipLevelWhenScreenOpen.add(new InsnNode(Opcodes.RETURN));
        skipLevelWhenScreenOpen.add(noScreen);
        renderLevel.instructions.insert(skipLevelWhenScreenOpen);
        renderLevel.maxStack = Math.max(renderLevel.maxStack, 1);

        MethodNode method = find(node, "tryTakeScreenshotIfNeeded", "()V");
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "hasWorldScreenshot",
                "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "hasWorldScreenshot",
                "Z"));
        code.add(new LdcInsnNode("browser.autoWorldScreenshot.disabled"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftEvent",
                "(Ljava/lang/String;)V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 1);
        writeComputeFrames(node, output);
    }

    private static void patchParticleGroupBrowserTickSafety(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/particle/ParticleGroup.class");
        MethodNode method = find(node, "tickParticle",
                "(Lnet/minecraft/client/particle/Particle;)V");
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "tick",
                "()V",
                false));
        code.add(end);
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new LdcInsnNode("particle.tick"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftThrowable",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "remove",
                "()V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 3);
        method.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        patchParticleGroupTickParticlesLoop(node);
        patchParticleGroupAddNullGuard(node);
        writeComputeFrames(node, output);
    }

    private static void patchParticleGroupTickParticlesLoop(ClassNode node) {
        MethodNode method = find(node, "tickParticles", "()V");
        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode tick = new LabelNode();
        LabelNode alive = new LabelNode();
        LabelNode done = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "particles",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "isEmpty",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "particles",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/particle/Particle"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, tick));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(tick);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/ParticleGroup",
                "tickParticle",
                "(Lnet/minecraft/client/particle/Particle;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "isAlive",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, alive));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        code.add(alive);
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 3);
    }

    private static void patchParticleGroupAddNullGuard(ClassNode node) {
        MethodNode method = find(node, "add",
                "(Lnet/minecraft/client/particle/Particle;)V");
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "particles",
                "Ljava/util/Queue;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 2);
    }

    private static void patchClientLevelBrowserBlockBreakEffects(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/ClientLevel.class");
        MethodNode addDestroyBlockEffect = find(node, "addDestroyBlockEffect",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        InsnList addDestroyCode = new InsnList();
        addDestroyCode.add(new InsnNode(Opcodes.RETURN));
        replace(addDestroyBlockEffect, addDestroyCode, 1, 3);

        MethodNode destroyBlockProgress = find(node, "destroyBlockProgress",
                "(ILnet/minecraft/core/BlockPos;I)V");
        InsnList destroyProgressCode = new InsnList();
        destroyProgressCode.add(new InsnNode(Opcodes.RETURN));
        replace(destroyBlockProgress, destroyProgressCode, 1, 4);
        write(node, output);
    }

    private static void patchLevelRendererBrowserBlockBreakProgress(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/LevelRenderer.class");
        MethodNode method = find(node, "destroyBlockProgress",
                "(ILnet/minecraft/core/BlockPos;I)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1, 4);
        write(node, output);
    }

    private static void patchMultiplayerExecutor(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/multiplayer/"
                        + "ServerSelectionList$OnlineServerEntry.class");
        MethodNode render = node.methods.stream()
                .filter(method -> method.name.equals("renderContent"))
                .findFirst()
                .orElseThrow();
        boolean fieldRemoved = false;
        boolean submitReplaced = false;
        for (var instruction = render.instructions.getFirst();
                instruction != null;) {
            var next = instruction.getNext();
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(
                            "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList")
                    && field.name.equals("THREAD_POOL")) {
                render.instructions.remove(field);
                fieldRemoved = true;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/util/concurrent/ThreadPoolExecutor")
                    && call.name.equals("submit")
                    && call.desc.equals(
                            "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;")) {
                MethodInsnNode run = new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/lang/Runnable",
                        "run",
                        "()V",
                        true);
                render.instructions.set(call, run);
                if (run.getNext() instanceof InsnNode pop
                        && pop.getOpcode() == Opcodes.POP) {
                    render.instructions.remove(pop);
                }
                submitReplaced = true;
            }
            instruction = next;
        }
        if (!fieldRemoved || !submitReplaced) {
            throw new IllegalStateException("Multiplayer executor call was not found");
        }
        write(node, output);
    }

    private static void patchReflectivePatternArray(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/world/level/block/state/pattern/BlockPatternBuilder.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/reflect/Array")
                        && call.name.equals("newInstance")
                        && call.desc.equals("(Ljava/lang/Class;[I)Ljava/lang/Object;")) {
                    call.owner = "dev/gaius/browser/BrowserArrays";
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("BlockPatternBuilder reflective array call not found");
        }
        write(node, output);
    }

    private static void patchChunkPosSpliterator(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/ChunkPos$2";
        ClassNode node = read(jar, owner + ".class");
        node.superName = "java/lang/Object";
        if (!node.interfaces.contains("java/util/Spliterator")) {
            node.interfaces.add("java/util/Spliterator");
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserRemaining", "J", null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserCharacteristics", "I", null, null));

        MethodNode constructor = find(node, "<init>",
                "(JILnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/ChunkPos;II)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        putField(code, owner, "val$from", "Lnet/minecraft/world/level/ChunkPos;",
                Opcodes.ALOAD, 4);
        putField(code, owner, "val$to", "Lnet/minecraft/world/level/ChunkPos;",
                Opcodes.ALOAD, 5);
        putField(code, owner, "val$zDiff", "I", Opcodes.ILOAD, 6);
        putField(code, owner, "val$xDiff", "I", Opcodes.ILOAD, 7);
        putField(code, owner, "browserRemaining", "J", Opcodes.LLOAD, 1);
        putField(code, owner, "browserCharacteristics", "I", Opcodes.ILOAD, 3);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, code, 3, 8);

        MethodNode split = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "trySplit",
                "()Ljava/util/Spliterator;",
                null,
                null);
        split.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        split.instructions.add(new InsnNode(Opcodes.ARETURN));
        split.maxStack = 1;
        split.maxLocals = 1;
        node.methods.add(split);

        MethodNode size = new MethodNode(
                Opcodes.ACC_PUBLIC, "estimateSize", "()J", null, null);
        size.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        size.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserRemaining", "J"));
        size.instructions.add(new InsnNode(Opcodes.LRETURN));
        size.maxStack = 2;
        size.maxLocals = 1;
        node.methods.add(size);

        MethodNode characteristics = new MethodNode(
                Opcodes.ACC_PUBLIC, "characteristics", "()I", null, null);
        characteristics.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        characteristics.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCharacteristics", "I"));
        characteristics.instructions.add(new InsnNode(Opcodes.IRETURN));
        characteristics.maxStack = 1;
        characteristics.maxLocals = 1;
        node.methods.add(characteristics);
        write(node, output);
    }

    private static void patchDetectedVersion(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/DetectedVersion.class");
        MethodNode detect = find(
                node, "tryDetectVersion", "()Lnet/minecraft/WorldVersion;");
        InsnList code = new InsnList();
        code.add(new LdcInsnNode("1.21.11"));
        code.add(new LdcInsnNode("1.21.11"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/DetectedVersion",
                "createBuiltIn",
                "(Ljava/lang/String;Ljava/lang/String;Z)Lnet/minecraft/WorldVersion;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(detect, code, 3, 0);
        write(node, output);
    }

    private static void patchUtilJarFileSystem(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/Util.class");
        MethodNode clinit = find(node, "<clinit>", "()V");
        var start = clinit.instructions.getFirst();
        while (start != null) {
            if (start instanceof MethodInsnNode call
                    && call.owner.equals("java/nio/file/spi/FileSystemProvider")
                    && call.name.equals("installedProviders")
                    && call.desc.equals("()Ljava/util/List;")) {
                break;
            }
            start = start.getNext();
        }
        if (start == null) {
            throw new IllegalStateException("Util jar provider lookup was not found");
        }

        var end = start;
        while (end != null) {
            if (end instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals("net/minecraft/util/Util")
                    && field.name.equals("ZIP_FILE_SYSTEM_PROVIDER")) {
                break;
            }
            end = end.getNext();
        }
        if (end == null) {
            throw new IllegalStateException("Util ZIP_FILE_SYSTEM_PROVIDER write was not found");
        }

        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                "net/minecraft/util/Util",
                "ZIP_FILE_SYSTEM_PROVIDER",
                "Ljava/nio/file/spi/FileSystemProvider;"));
        clinit.instructions.insertBefore(start, code);

        var current = start;
        while (current != null) {
            var next = current.getNext();
            clinit.instructions.remove(current);
            if (current == end) {
                break;
            }
            current = next;
        }
        replace(clinit, clinit.instructions, clinit.maxStack, clinit.maxLocals);
        write(node, output);
    }

    private static void patchNativeModuleLister(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/NativeModuleLister.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("listModules") && method.desc.equals("()Ljava/util/List;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                        "()Ljava/util/List;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 1;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("NativeModuleLister.listModules was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchJvmProfiler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/jfr/JvmProfiler.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler",
                        "<init>",
                        "()V",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/jfr/JvmProfiler",
                        "INSTANCE",
                        "Lnet/minecraft/util/profiling/jfr/JvmProfiler;"));
                code.add(new InsnNode(Opcodes.RETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 2;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("JvmProfiler.<clinit> was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchEventLoopGroupHolder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/EventLoopGroupHolder.class");
        boolean clinitFound = false;
        boolean remoteFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/server/network/EventLoopGroupHolder$4"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Local"));
                code.add(new LdcInsnNode(Type.getObjectType("io/netty/channel/local/LocalChannel")));
                code.add(new LdcInsnNode(
                        Type.getObjectType("io/netty/channel/local/LocalServerChannel")));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/server/network/EventLoopGroupHolder$4",
                        "<init>",
                        "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)V",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 0));
                for (String field : new String[] {"NIO", "EPOLL", "KQUEUE", "LOCAL"}) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "net/minecraft/server/network/EventLoopGroupHolder",
                            field,
                            "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                }
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 5, 1);
                clinitFound = true;
            } else if (method.name.equals("remote")
                    && method.desc.equals("(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;")) {
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/network/EventLoopGroupHolder",
                        "LOCAL",
                        "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 1);
                remoteFound = true;
            }
        }
        if (!clinitFound || !remoteFound) {
            throw new IllegalStateException("EventLoopGroupHolder patch points were not found");
        }
        write(node, output);
    }

    private static void patchGlx(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/GLX.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("_getCpuInfo") && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("GLX._getCpuInfo was not found");
        }
        write(node, output);
    }

    private static void patchTracyZoneFiller(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/TracyZoneFiller.class");
        boolean pushFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("push") && method.desc.equals("(Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new LdcInsnNode(""));
                code.add(new LdcInsnNode(""));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/mojang/jtracy/TracyClient",
                        "beginZone",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)"
                                + "Lcom/mojang/jtracy/Zone;",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "activeZones",
                        "Ljava/util/List;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "add",
                        "(Ljava/lang/Object;)Z",
                        true));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 4, 3);
                pushFound = true;
            } else if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/mojang/logging/LogUtils",
                        "getLogger",
                        "()Lorg/slf4j/Logger;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "LOGGER",
                        "Lorg/slf4j/Logger;"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "STACK_WALKER",
                        "Ljava/lang/StackWalker;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        if (!pushFound) {
            throw new IllegalStateException("TracyZoneFiller.push was not found");
        }
        write(node, output);
    }

    private static void patchMacosUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/MacosUtil.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/MacosUtil",
                        "IS_MACOS",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if ((method.name.equals("exitNativeFullscreen")
                    || method.name.equals("clearResizableBit")
                    || method.name.equals("loadIcon"))
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchInputConstants(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/InputConstants.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "GLFW_RAW_MOUSE_MOTION_SUPPORTED",
                        "Ljava/lang/invoke/MethodHandle;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "GLFW_RAW_MOUSE_MOTION",
                        "I"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "KEYSYM",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Type;"));
                code.add(new InsnNode(Opcodes.ICONST_M1));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "getOrCreate",
                        "(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "UNKNOWN",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Key;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 0);
            } else if (method.name.equals("isRawMouseInputSupported")
                    && method.desc.equals("()Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    private static void patchMemoryDebug(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/Collections",
                        "emptyList",
                        "()Ljava/util/List;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/client/gui/components/debug/"
                                + "DebugEntryMemory$AllocationRateCalculator",
                        "GC_MBEANS",
                        "Ljava/util/List;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    private static void patchMinecraft(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Minecraft.class");
        boolean found = false;
        boolean stateHooked = false;
        boolean throwableHooked = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("run") && method.desc.equals("()V")) {
                throwableHooked = hookMinecraftRunCatchDiagnostics(method);
            } else if (method.name.equals("lambda$fillUptime$40")
                    && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            } else if (method.name.equals("runTick") && method.desc.equals("(Z)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "screen",
                        "Lnet/minecraft/client/gui/screens/Screen;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "overlay",
                        "Lnet/minecraft/client/gui/screens/Overlay;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "level",
                        "Lnet/minecraft/client/multiplayer/ClientLevel;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "player",
                        "Lnet/minecraft/client/player/LocalPlayer;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "gameMode",
                        "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "hitResult",
                        "Lnet/minecraft/world/phys/HitResult;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "noRender",
                        "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "running",
                        "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "pause",
                        "Z"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/BrowserOpenGL",
                        "reportMinecraftState",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                                + "Ljava/lang/Object;Ljava/lang/Object;ZZZ)V",
                        false));
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 9);
                stateHooked = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Minecraft uptime lambda was not found");
        }
        if (!stateHooked) {
            throw new IllegalStateException("Minecraft runTick hook point was not found");
        }
        if (!throwableHooked) {
            throw new IllegalStateException("Minecraft run throwable diagnostic hook point was not found");
        }
        write(node, output);
    }

    private static boolean hookMinecraftRunCatchDiagnostics(MethodNode method) {
        boolean reportedHooked = false;
        boolean unreportedHooked = false;
        for (var block : method.tryCatchBlocks) {
            String phase;
            if ("net/minecraft/ReportedException".equals(block.type)) {
                phase = "run.reported";
            } else if ("java/lang/Throwable".equals(block.type)) {
                phase = "run.unreported";
            } else {
                continue;
            }
            var instruction = block.handler.getNext();
            while (instruction != null && instruction.getOpcode() < 0) {
                instruction = instruction.getNext();
            }
            if (!(instruction instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new LdcInsnNode(phase));
            code.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/BrowserOpenGL",
                    "reportMinecraftThrowable",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false));
            method.instructions.insert(instruction, code);
            method.maxStack = Math.max(method.maxStack, 2);
            if ("run.reported".equals(phase)) {
                reportedHooked = true;
            } else {
                unreportedHooked = true;
            }
        }
        return reportedHooked && unreportedHooked;
    }

    private static void patchClientPacketListenerLoadingDiagnostics(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/ClientPacketListener.class");
        boolean handleLoginHooked = false;
        boolean handleLoginDifficultyHooked = false;
        boolean startWaitingHooked = false;
        boolean levelChunkHooked = false;
        boolean batchStartHooked = false;
        boolean batchStartLoadingPacketsHooked = false;
        boolean batchFinishedHooked = false;
        boolean tickClientLoadHooked = false;
        boolean loadingPacketsHooked = false;
        boolean notifyPlayerLoadedHooked = false;

        for (MethodNode method : node.methods) {
            if (method.name.equals("handleLogin")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V")) {
                method.instructions.insert(minecraftEvent("client.handleLogin"));
                method.maxStack = Math.max(method.maxStack, 1);
                handleLoginHooked = true;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals("net/minecraft/world/Difficulty")
                            && field.name.equals("NORMAL")
                            && field.desc.equals("Lnet/minecraft/world/Difficulty;")) {
                        field.name = "PEACEFUL";
                        handleLoginDifficultyHooked = true;
                    }
                }
            } else if (method.name.equals("startWaitingForNewLevel")
                    && method.desc.equals("(Lnet/minecraft/client/player/LocalPlayer;"
                            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                            + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V")) {
                method.instructions.insert(minecraftEvent("client.startWaitingForNewLevel"));
                method.maxStack = Math.max(method.maxStack, 1);
                startWaitingHooked = true;
            } else if (method.name.equals("handleLevelChunkWithLight")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundLevelChunkWithLightPacket;)V")) {
                method.instructions.insert(minecraftEvent("client.handleLevelChunkWithLight"));
                method.maxStack = Math.max(method.maxStack, 1);
                levelChunkHooked = true;
            } else if (method.name.equals("handleChunkBatchStart")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundChunkBatchStartPacket;)V")) {
                InsnList code = new InsnList();
                code.add(minecraftEvent("client.handleChunkBatchStart"));
                code.add(notifyLoadingPacketsReceived("client.loadingPacketsReceived.chunkBatchStart"));
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 2);
                batchStartHooked = true;
                batchStartLoadingPacketsHooked = true;
            } else if (method.name.equals("handleChunkBatchFinished")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundChunkBatchFinishedPacket;)V")) {
                method.instructions.insert(minecraftEvent("client.handleChunkBatchFinished"));
                method.maxStack = Math.max(method.maxStack, 1);
                batchFinishedHooked = true;
            } else if (method.name.equals("notifyPlayerLoaded")
                    && method.desc.equals("()V")) {
                method.instructions.insert(minecraftEvent("client.notifyPlayerLoaded"));
                method.maxStack = Math.max(method.maxStack, 1);
                notifyPlayerLoadedHooked = true;
            }

            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                        && call.name.equals("tickClientLoad")
                        && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, minecraftEvent("client.tickClientLoad"));
                    method.maxStack = Math.max(method.maxStack, 2);
                    tickClientLoadHooked = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                        && call.name.equals("loadingPacketsReceived")
                        && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, minecraftEvent("client.loadingPacketsReceived"));
                    method.maxStack = Math.max(method.maxStack, 2);
                    loadingPacketsHooked = true;
                }
            }
        }

        if (!handleLoginHooked
                || !handleLoginDifficultyHooked
                || !startWaitingHooked
                || !levelChunkHooked
                || !batchStartHooked
                || !batchStartLoadingPacketsHooked
                || !batchFinishedHooked
                || !tickClientLoadHooked
                || !loadingPacketsHooked
                || !notifyPlayerLoadedHooked) {
            throw new IllegalStateException("ClientPacketListener loading diagnostic patch points were not found");
        }
        write(node, output);
    }

    private static void patchLevelLoadTrackerBrowserTimeout(String jar, Path root) throws IOException {
        ClassNode tracker = read(jar, "net/minecraft/client/multiplayer/LevelLoadTracker.class");
        boolean patchedClientWaitTimeout = false;
        for (MethodNode method : tracker.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && constant.cst instanceof Long value
                        && value == 30L) {
                    constant.cst = 5L;
                    patchedClientWaitTimeout = true;
                    break;
                }
            }
        }
        if (!patchedClientWaitTimeout) {
            throw new IllegalStateException("LevelLoadTracker timeout patch point was not found");
        }
        write(tracker, root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker.class"));

        ClassNode waiting = read(jar, "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer.class");
        MethodNode tick = null;
        for (MethodNode method : waiting.methods) {
            if (method.name.equals("tick")
                    && method.desc.equals("()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;")) {
                tick = method;
                break;
            }
        }
        if (tick == null) {
            tick = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "tick",
                    "()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;",
                    null,
                    null);
            waiting.methods.add(tick);
        }
        LabelNode notTimedOut = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "timeoutAfter",
                "J"));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFLE, notTimedOut));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "LOGGER",
                "Lorg/slf4j/Logger;"));
        code.add(new LdcInsnNode(
                "Timed out while waiting for initial level loading packets in the browser, continuing anyway"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/slf4j/Logger",
                "warn",
                "(Ljava/lang/String;)V",
                true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "loadingPacketsReceived",
                "()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(notTimedOut);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(tick, code, 4, 1);
        write(waiting, root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer.class"));
    }

    private static void patchServerLevelBrowserSafeDefaults(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ServerLevel.class");
        MethodNode method = find(node, "isSpawningMonsters", "()Z");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, 1);
        write(node, output);
    }

    private static void patchIntegratedServerBrowserDistances(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/server/IntegratedServer.class");
        MethodNode method = find(node, "tickServer", "(Ljava/util/function/BooleanSupplier;)V");
        boolean afterServerTick = false;
        int patchedStores = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/server/MinecraftServer")
                    && call.name.equals("tickServer")
                    && call.desc.equals("(Ljava/util/function/BooleanSupplier;)V")) {
                afterServerTick = true;
                continue;
            }
            if (afterServerTick
                    && instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ISTORE) {
                InsnList override = new InsnList();
                override.add(new InsnNode(Opcodes.POP));
                override.add(new InsnNode(Opcodes.ICONST_1));
                method.instructions.insertBefore(instruction, override);
                patchedStores++;
                if (patchedStores == 2) {
                    break;
                }
            }
        }
        if (patchedStores != 2) {
            throw new IllegalStateException("IntegratedServer distance override patch points were not found");
        }
        method.maxStack = Math.max(method.maxStack, 2);
        write(node, output);
    }

    private static void patchPlayerListBrowserDistances(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/players/PlayerList.class");
        patchPlayerListDistanceGetter(node, "getViewDistance", "viewDistance");
        patchPlayerListDistanceGetter(node, "getSimulationDistance", "simulationDistance");
        patchPlayerListDistanceSetter(node, "setViewDistance");
        patchPlayerListDistanceSetter(node, "setSimulationDistance");
        writeComputeFrames(node, output);
    }

    private static void patchPlayerListDistanceGetter(ClassNode node, String methodName, String fieldName) {
        MethodNode method = find(node, methodName, "()I");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, fieldName, "I"));
        code.add(distanceClamp());
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 2, 1);
    }

    private static void patchPlayerListDistanceSetter(ClassNode node, String methodName) {
        MethodNode method = find(node, methodName, "(I)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(distanceClamp());
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static InsnList distanceClamp() {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.ICONST_1));
        return code;
    }

    private static void patchFreeTypeUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("destroy") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("FreeTypeUtil.destroy was not found");
        }
        write(node, output);
    }

    private static void patchVanillaPackResourcesBuilder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/packs/VanillaPackResourcesBuilder.class");
        MethodNode method = find(node, "lambda$static$1", "()Lcom/google/common/collect/ImmutableMap;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/google/common/collect/ImmutableMap",
                "of",
                "()Lcom/google/common/collect/ImmutableMap;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    private static void patchIndexedAssetSourceBrowserNoop(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/resources/IndexedAssetSource.class");
        MethodNode method = find(node, "createIndexFs",
                "(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/nio/file/Path;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/packs/linkfs/LinkFileSystem",
                "builder",
                "()Lnet/minecraft/server/packs/linkfs/LinkFileSystem$Builder;",
                false));
        code.add(new LdcInsnNode("browser-assets"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/packs/linkfs/LinkFileSystem$Builder",
                "build",
                "(Ljava/lang/String;)Ljava/nio/file/FileSystem;",
                false));
        code.add(new LdcInsnNode("/"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/file/FileSystem",
                "getPath",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 3, 2);
        write(node, output);
    }

    private static void patchLocalTimeItemModelProperty(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/item/properties/select/LocalTime";
        String data = "net/minecraft/client/renderer/item/properties/select/LocalTime$Data";
        ClassNode node = read(jar, owner + ".class");

        MethodNode create = find(node, "create",
                "(L" + data + ";)Lcom/mojang/serialization/DataResult;");
        InsnList createCode = new InsnList();
        createCode.add(new TypeInsnNode(Opcodes.NEW, owner));
        createCode.add(new InsnNode(Opcodes.DUP));
        createCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        createCode.add(new InsnNode(Opcodes.ACONST_NULL));
        createCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                "(L" + data + ";Lcom/ibm/icu/text/DateFormat;)V",
                false));
        createCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/serialization/DataResult",
                "success",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
                true));
        createCode.add(new InsnNode(Opcodes.ARETURN));
        replace(create, createCode, 4, 1);

        MethodNode update = find(node, "update", "()Ljava/lang/String;");
        InsnList updateCode = new InsnList();
        LabelNode monthDay = new LabelNode();

        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "L" + data + ";"));
        updateCode.add(new FieldInsnNode(Opcodes.GETFIELD, data, "format", "Ljava/lang/String;"));
        updateCode.add(new LdcInsnNode("MM-dd"));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false));
        updateCode.add(new JumpInsnNode(Opcodes.IFNE, monthDay));
        updateCode.add(new LdcInsnNode(""));
        updateCode.add(new InsnNode(Opcodes.ARETURN));

        updateCode.add(monthDay);
        updateCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/Date"));
        updateCode.add(new InsnNode(Opcodes.DUP));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/Date",
                "<init>",
                "()V",
                false));
        updateCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Date",
                "getMonth",
                "()I",
                false));
        updateCode.add(new InsnNode(Opcodes.ICONST_1));
        updateCode.add(new InsnNode(Opcodes.IADD));
        updateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Date",
                "getDate",
                "()I",
                false));
        updateCode.add(new VarInsnNode(Opcodes.ISTORE, 3));
        updateCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        updateCode.add(new InsnNode(Opcodes.DUP));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "()V",
                false));
        addAppendTwoDigit(updateCode, 2);
        updateCode.add(new LdcInsnNode("-"));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        addAppendTwoDigit(updateCode, 3);
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false));
        updateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(update, updateCode, 4, 4);

        write(node, output);
    }

    private static void addAppendTwoDigit(InsnList code, int local) {
        LabelNode noZero = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, local));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, noZero));
        code.add(new LdcInsnNode("0"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        code.add(noZero);
        code.add(new VarInsnNode(Opcodes.ILOAD, local));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(I)Ljava/lang/StringBuilder;",
                false));
    }

    private static void patchDebugMemoryUntracker(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/DebugMemoryUntracker.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/DebugMemoryUntracker",
                        "UNTRACK", "Ljava/lang/invoke/MethodHandle;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if (method.name.equals("untrack")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchMinecraftServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/MinecraftServer.class");
        String owner = "net/minecraft/server/MinecraftServer";
        boolean patchedPrepareLevels = false;
        boolean patchedInitialSpawn = false;
        boolean patchedRunServerReady = false;
        boolean patchedRunServerStopDiagnostics = false;
        boolean patchedRunServerBrowserCatchupReset = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("dumpThreads")
                    && method.desc.equals("(Ljava/nio/file/Path;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
            } else if (method.name.equals("prepareLevels") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_INITIAL_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "start",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_INITIAL_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "finish",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_PLAYER_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "start",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_PLAYER_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "finish",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "updateMobSpawningFlags",
                        "()V",
                        false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "updateEffectiveRespawnData",
                        "()V",
                        false));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 3, 1);
                patchedPrepareLevels = true;
            } else if (method.name.equals("setInitialSpawn")
                    && method.desc.equals("(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/level/storage/ServerLevelData;"
                            + "ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V")) {
                replaceInitialSpawnForBrowser(method);
                patchedInitialSpawn = true;
            } else if (method.name.equals("runServer") && method.desc.equals("()V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.IFEQ) {
                        InsnList code = new InsnList();
                        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        code.add(new InsnNode(Opcodes.ICONST_1));
                        code.add(new FieldInsnNode(
                                Opcodes.PUTFIELD,
                                owner,
                                "isReady",
                                "Z"));
                        method.instructions.insert(instruction, code);
                        method.maxStack = Math.max(method.maxStack, 2);
                        patchedRunServerReady = true;
                        break;
                    }
                }
                patchedRunServerStopDiagnostics = hookMinecraftServerStopDiagnostics(method);
                patchedRunServerBrowserCatchupReset = patchMinecraftServerBrowserCatchupReset(method, owner);
            }
        }
        if (!patchedPrepareLevels
                || !patchedInitialSpawn
                || !patchedRunServerReady
                || !patchedRunServerStopDiagnostics
                || !patchedRunServerBrowserCatchupReset) {
            throw new IllegalStateException("MinecraftServer browser patch points were not found");
        }
        writeComputeFrames(node, output);
    }

    private static void replaceInitialSpawnForBrowser(MethodNode method) {
        InsnList code = new InsnList();
        LabelNode foundSpawn = new LabelNode();

        code.add(minecraftEvent("server.browserFastInitialSpawn"));

        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                "PREPARE_GLOBAL_SPAWN",
                "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "start",
                "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                true));

        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/world/level/ChunkPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getChunkSource",
                "()Lnet/minecraft/server/level/ServerChunkCache;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "randomState",
                "()Lnet/minecraft/world/level/levelgen/RandomState;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/levelgen/RandomState",
                "sampler",
                "()Lnet/minecraft/world/level/biome/Climate$Sampler;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/biome/Climate$Sampler",
                "findSpawnPosition",
                "()Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(Lnet/minecraft/core/BlockPos;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));

        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "dimension",
                "()Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "updateFocus",
                "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/ChunkPos;)V",
                true));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/level/PlayerSpawnFinder",
                "getSpawnPosInChunk",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)"
                        + "Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, foundSpawn));
        code.add(minecraftEvent("server.browserFastInitialSpawnFallback"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/levelgen/Heightmap$Types",
                "WORLD_SURFACE",
                "Lnet/minecraft/world/level/levelgen/Heightmap$Types;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getWorldPosition",
                "()Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/BlockPos",
                "offset",
                "(III)Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getHeightmapPos",
                "(Lnet/minecraft/world/level/levelgen/Heightmap$Types;"
                        + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(foundSpawn);

        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "dimension",
                "()Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/level/storage/LevelData$RespawnData",
                "of",
                "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;FF)"
                        + "Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/storage/ServerLevelData",
                "setSpawn",
                "(Lnet/minecraft/world/level/storage/LevelData$RespawnData;)V",
                true));

        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                "PREPARE_GLOBAL_SPAWN",
                "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "finish",
                "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 7, 7);
    }

    private static boolean patchMinecraftServerBrowserCatchupReset(MethodNode method, String owner) {
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind"
                            .equals(constant.cst)) {
                continue;
            }
            var loggerLoad = previousOpcode(instruction);
            if (!(loggerLoad instanceof FieldInsnNode logger)
                    || logger.getOpcode() != Opcodes.GETSTATIC
                    || !"Lorg/slf4j/Logger;".equals(logger.desc)) {
                continue;
            }
            AbstractInsnNode end = null;
            for (var candidate = instruction.getNext(); candidate != null; candidate = candidate.getNext()) {
                if (candidate instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(owner)
                        && field.name.equals("lastOverloadWarningNanos")
                        && field.desc.equals("J")) {
                    end = field;
                    break;
                }
            }
            if (end == null) {
                continue;
            }

            LabelNode afterVanillaCatchup = new LabelNode();
            method.instructions.insert(end, afterVanillaCatchup);

            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/util/Util",
                    "getNanos",
                    "()J",
                    false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "nextTickTimeNanos",
                    "J"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "nextTickTimeNanos",
                    "J"));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "lastOverloadWarningNanos",
                    "J"));
            code.add(new JumpInsnNode(Opcodes.GOTO, afterVanillaCatchup));
            method.instructions.insertBefore(loggerLoad, code);
            method.maxStack = Math.max(method.maxStack, 4);
            return true;
        }
        return false;
    }

    private static boolean hookMinecraftServerStopDiagnostics(MethodNode method) {
        int hooked = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Exception stopping the server".equals(constant.cst)) {
                continue;
            }
            var throwableLoad = nextOpcode(instruction);
            if (!(throwableLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD) {
                continue;
            }
            var loggerLoad = previousOpcode(instruction);
            if (!(loggerLoad instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !"Lorg/slf4j/Logger;".equals(field.desc)) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new LdcInsnNode("server.stop"));
            code.add(new VarInsnNode(Opcodes.ALOAD, load.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/BrowserOpenGL",
                    "reportMinecraftThrowable",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false));
            method.instructions.insertBefore(loggerLoad, code);
            method.maxStack = Math.max(method.maxStack, 2);
            hooked++;
        }
        return hooked >= 1;
    }

    private static void patchChaseClient(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseClient.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchLanServerPinger(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/server/LanServerPinger.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")
                    && method.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode("LanServerPinger-browser"));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                        "java/lang/Thread", "<init>", "(Ljava/lang/String;)V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "isRunning", "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "motd", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "serverAddress", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "socket", "Ljava/net/DatagramSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 3);
            } else if (method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    private static void patchHttpUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/HttpUtil.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("getAvailablePort") && method.desc.equals("()I")) {
                InsnList code = new InsnList();
                code.add(new IntInsnNode(Opcodes.SIPUSH, 25564));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 0);
            } else if (method.name.equals("isPortAvailable") && method.desc.equals("(I)Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 1);
            }
        }
        write(node, output);
    }

    private static void patchLanServerDetector(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class");
        String owner = "net/minecraft/client/server/LanServerDetection$LanServerDetector";
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "serverList",
                        "Lnet/minecraft/client/server/LanServerDetection$LanServerList;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "pingGroup", "Ljava/net/InetAddress;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "socket", "Ljava/net/MulticastSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 2);
            } else if (method.name.equals("run") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    private static void patchPackWatcher(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("create")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("pollForChanges")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("close") || method.name.equals("watchDir")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchChaseServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseServer.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("runSender") || method.name.equals("runAcceptor")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchOpenUri(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/Util$OS.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("openUri") && method.desc.equals("(Ljava/net/URI;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchRealmsNetwork(String jar, Path root) throws IOException {
        ClassNode download = read(jar, "com/mojang/realmsclient/client/FileDownload.class");
        for (MethodNode method : download.methods) {
            if (method.name.equals("contentLength")
                    && method.desc.equals("(Ljava/lang/String;)Ljava/util/OptionalLong;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/OptionalLong", "empty",
                        "()Ljava/util/OptionalLong;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("download")
                    && method.desc.startsWith("(Lcom/mojang/realmsclient/dto/WorldDownload;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "error",
                        "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "finished",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, method.maxLocals);
            }
        }
        write(download, root.resolve("com/mojang/realmsclient/client/FileDownload.class"));

        ClassNode upload = read(jar, "com/mojang/realmsclient/client/FileUpload.class");
        for (MethodNode method : upload.methods) {
            if (method.name.equals("<init>")) {
                String owner = "com/mojang/realmsclient/client/FileUpload";
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                putField(code, owner, "file", "Ljava/io/File;", Opcodes.ALOAD, 1);
                putField(code, owner, "realmId", "J", Opcodes.LLOAD, 2);
                putField(code, owner, "slotId", "I", Opcodes.ILOAD, 4);
                putField(code, owner, "uploadInfo",
                        "Lcom/mojang/realmsclient/dto/UploadInfo;", Opcodes.ALOAD, 5);
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 6));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/User",
                        "getSessionId",
                        "()Ljava/lang/String;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "sessionId", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 6));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/User",
                        "getName",
                        "()Ljava/lang/String;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "username", "Ljava/lang/String;"));
                putField(code, owner, "clientVersion", "Ljava/lang/String;", Opcodes.ALOAD, 7);
                putField(code, owner, "worldVersion", "Ljava/lang/String;", Opcodes.ALOAD, 8);
                putField(code, owner, "uploadStatus",
                        "Lcom/mojang/realmsclient/client/UploadStatus;", Opcodes.ALOAD, 9);
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 3, method.maxLocals);
            } else if (method.name.equals("close")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            } else if (method.name.equals("startUpload")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Realms upload is unavailable in the browser"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/lang/UnsupportedOperationException",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/concurrent/CompletableFuture",
                        "failedFuture",
                        "(Ljava/lang/Throwable;)Ljava/util/concurrent/CompletableFuture;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 3, method.maxLocals);
            }
        }
        write(upload, root.resolve("com/mojang/realmsclient/client/FileUpload.class"));

        ClassNode ping = read(jar, "com/mojang/realmsclient/client/Ping.class");
        for (MethodNode method : ping.methods) {
            if ((method.name.equals("ping") && method.access == (method.access | Opcodes.ACC_STATIC))
                    || method.name.equals("pingAllRegions")) {
                if (Type.getReturnType(method.desc).getDescriptor().equals("Ljava/util/List;")) {
                    InsnList code = new InsnList();
                    code.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                            "()Ljava/util/List;", false));
                    code.add(new InsnNode(Opcodes.ARETURN));
                    replace(method, code, 1, method.maxLocals);
                }
            }
        }
        write(ping, root.resolve("com/mojang/realmsclient/client/Ping.class"));
    }

    private static void generateSoundApiStubs(Path root) throws IOException {
        ClassWriter encoding = new ClassWriter(0);
        encoding.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/sound/sampled/AudioFormat$Encoding", null,
                "java/lang/Object", null);
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_SIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_UNSIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        var constructor = encoding.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var init = encoding.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String field : new String[] {"PCM_SIGNED", "PCM_UNSIGNED"}) {
            init.visitTypeInsn(Opcodes.NEW, "javax/sound/sampled/AudioFormat$Encoding");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    "<init>",
                    "()V",
                    false);
            init.visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    field,
                    "Ljavax/sound/sampled/AudioFormat$Encoding;");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 0);
        init.visitEnd();
        encoding.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat$Encoding.class"),
                encoding.toByteArray());

        ClassWriter format = new ClassWriter(0);
        String owner = "javax/sound/sampled/AudioFormat";
        format.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", null);
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleRate",
                "F", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleSize",
                "I", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "channels",
                "I", null, null).visitEnd();
        var formatConstructor = format.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(FIIZZ)V", null, null);
        formatConstructor.visitCode();
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitVarInsn(Opcodes.ILOAD, 4);
        org.objectweb.asm.Label unsigned = new org.objectweb.asm.Label();
        org.objectweb.asm.Label encodingDone = new org.objectweb.asm.Label();
        formatConstructor.visitJumpInsn(Opcodes.IFEQ, unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_SIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitJumpInsn(Opcodes.GOTO, encodingDone);
        formatConstructor.visitLabel(unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_UNSIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitLabel(encodingDone);
        formatConstructor.visitFieldInsn(
                Opcodes.PUTFIELD, owner, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        putConstructorField(formatConstructor, owner, "sampleRate", "F", Opcodes.FLOAD, 1);
        putConstructorField(formatConstructor, owner, "sampleSize", "I", Opcodes.ILOAD, 2);
        putConstructorField(formatConstructor, owner, "channels", "I", Opcodes.ILOAD, 3);
        formatConstructor.visitInsn(Opcodes.RETURN);
        formatConstructor.visitMaxs(3, 6);
        formatConstructor.visitEnd();
        getter(format, owner, "getEncoding",
                "()Ljavax/sound/sampled/AudioFormat$Encoding;",
                "encoding", "Ljavax/sound/sampled/AudioFormat$Encoding;", Opcodes.ARETURN);
        getter(format, owner, "getSampleRate", "()F", "sampleRate", "F", Opcodes.FRETURN);
        getter(format, owner, "getSampleSizeInBits", "()I", "sampleSize", "I", Opcodes.IRETURN);
        getter(format, owner, "getChannels", "()I", "channels", "I", Opcodes.IRETURN);
        format.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat.class"), format.toByteArray());
    }

    private static void generateUnsafeStub(Path root) throws IOException {
        ClassWriter unsafe = new ClassWriter(0);
        unsafe.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "sun/misc/Unsafe", null, "java/lang/Object", null);
        var constructor = unsafe.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        emptyMethod(unsafe, "putByte", "(Ljava/lang/Object;JB)V", 5);
        unsafe.visitEnd();
        writeBytes(root.resolve("sun/misc/Unsafe.class"), unsafe.toByteArray());
    }

    private static void generateCryptoApiStubs(Path root) throws IOException {
        ClassWriter secretKey = new ClassWriter(0);
        secretKey.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "javax/crypto/SecretKey", null, "java/lang/Object",
                new String[] {"java/security/Key"});
        secretKey.visitEnd();
        writeBytes(root.resolve("javax/crypto/SecretKey.class"), secretKey.toByteArray());

        ClassWriter cipher = new ClassWriter(0);
        cipher.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/crypto/Cipher", null, "java/lang/Object", null);
        for (String field : new String[] {"ENCRYPT_MODE", "DECRYPT_MODE", "WRAP_MODE", "UNWRAP_MODE"}) {
            cipher.visitField(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    field, "I", null, switch (field) {
                        case "ENCRYPT_MODE" -> 1;
                        case "DECRYPT_MODE" -> 2;
                        case "WRAP_MODE" -> 3;
                        default -> 4;
                    }).visitEnd();
        }
        simpleConstructor(cipher, "javax/crypto/Cipher", Opcodes.ACC_PUBLIC, "()V");
        var getInstance = cipher.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;", null, null);
        getInstance.visitCode();
        getInstance.visitTypeInsn(Opcodes.NEW, "javax/crypto/Cipher");
        getInstance.visitInsn(Opcodes.DUP);
        getInstance.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "javax/crypto/Cipher", "<init>", "()V", false);
        getInstance.visitInsn(Opcodes.ARETURN);
        getInstance.visitMaxs(2, 1);
        getInstance.visitEnd();
        emptyMethod(cipher, "init", "(ILjava/security/Key;)V", 3);
        emptyMethod(cipher, "init",
                "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V", 4);
        var doFinal = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "doFinal", "([B)[B", null, null);
        doFinal.visitCode();
        doFinal.visitVarInsn(Opcodes.ALOAD, 1);
        doFinal.visitInsn(Opcodes.ARETURN);
        doFinal.visitMaxs(1, 2);
        doFinal.visitEnd();
        var getOutputSize = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "getOutputSize", "(I)I", null, null);
        getOutputSize.visitCode();
        getOutputSize.visitVarInsn(Opcodes.ILOAD, 1);
        getOutputSize.visitInsn(Opcodes.IRETURN);
        getOutputSize.visitMaxs(1, 2);
        getOutputSize.visitEnd();
        var update = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[B)I", null, null);
        update.visitCode();
        update.visitVarInsn(Opcodes.ALOAD, 1);
        update.visitVarInsn(Opcodes.ILOAD, 2);
        update.visitVarInsn(Opcodes.ALOAD, 4);
        update.visitInsn(Opcodes.ICONST_0);
        update.visitVarInsn(Opcodes.ILOAD, 3);
        update.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V",
                false);
        update.visitVarInsn(Opcodes.ILOAD, 3);
        update.visitInsn(Opcodes.IRETURN);
        update.visitMaxs(5, 5);
        update.visitEnd();
        var updateWithOffset = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[BI)I", null, null);
        updateWithOffset.visitCode();
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 1);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 2);
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 4);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 5);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 3);
        updateWithOffset.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V",
                false);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 3);
        updateWithOffset.visitInsn(Opcodes.IRETURN);
        updateWithOffset.visitMaxs(5, 6);
        updateWithOffset.visitEnd();
        cipher.visitEnd();
        writeBytes(root.resolve("javax/crypto/Cipher.class"), cipher.toByteArray());

        ClassWriter keyGenerator = new ClassWriter(0);
        keyGenerator.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/crypto/KeyGenerator", null, "java/lang/Object", null);
        keyGenerator.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "algorithm", "Ljava/lang/String;", null, null).visitEnd();
        var kgConstructor = keyGenerator.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        kgConstructor.visitCode();
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        kgConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        kgConstructor.visitFieldInsn(
                Opcodes.PUTFIELD, "javax/crypto/KeyGenerator",
                "algorithm", "Ljava/lang/String;");
        kgConstructor.visitInsn(Opcodes.RETURN);
        kgConstructor.visitMaxs(2, 2);
        kgConstructor.visitEnd();
        var kgGetInstance = keyGenerator.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getInstance", "(Ljava/lang/String;)Ljavax/crypto/KeyGenerator;", null, null);
        kgGetInstance.visitCode();
        kgGetInstance.visitTypeInsn(Opcodes.NEW, "javax/crypto/KeyGenerator");
        kgGetInstance.visitInsn(Opcodes.DUP);
        kgGetInstance.visitVarInsn(Opcodes.ALOAD, 0);
        kgGetInstance.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "javax/crypto/KeyGenerator",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        kgGetInstance.visitInsn(Opcodes.ARETURN);
        kgGetInstance.visitMaxs(3, 1);
        kgGetInstance.visitEnd();
        emptyMethod(keyGenerator, "init", "(I)V", 2);
        var generateKey = keyGenerator.visitMethod(
                Opcodes.ACC_PUBLIC, "generateKey", "()Ljavax/crypto/SecretKey;", null, null);
        generateKey.visitCode();
        generateKey.visitTypeInsn(Opcodes.NEW, "javax/crypto/spec/SecretKeySpec");
        generateKey.visitInsn(Opcodes.DUP);
        generateKey.visitIntInsn(Opcodes.BIPUSH, 16);
        generateKey.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        generateKey.visitVarInsn(Opcodes.ALOAD, 0);
        generateKey.visitFieldInsn(
                Opcodes.GETFIELD, "javax/crypto/KeyGenerator",
                "algorithm", "Ljava/lang/String;");
        generateKey.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "javax/crypto/spec/SecretKeySpec",
                "<init>",
                "([BLjava/lang/String;)V",
                false);
        generateKey.visitInsn(Opcodes.ARETURN);
        generateKey.visitMaxs(4, 1);
        generateKey.visitEnd();
        keyGenerator.visitEnd();
        writeBytes(root.resolve("javax/crypto/KeyGenerator.class"), keyGenerator.toByteArray());

        generateSecretKeySpecStub(root);
        generateIvParameterSpecStub(root);
    }

    private static void generateSecretKeySpecStub(Path root) throws IOException {
        ClassWriter spec = new ClassWriter(0);
        String owner = "javax/crypto/spec/SecretKeySpec";
        spec.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", new String[] {"javax/crypto/SecretKey"});
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "key", "[B", null, null).visitEnd();
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "algorithm", "Ljava/lang/String;", null, null).visitEnd();
        var constructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BLjava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        putConstructorField(constructor, owner, "key", "[B", Opcodes.ALOAD, 1);
        putConstructorField(constructor, owner, "algorithm", "Ljava/lang/String;", Opcodes.ALOAD, 2);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 3);
        constructor.visitEnd();
        var rangedConstructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BIILjava/lang/String;)V", null, null);
        rangedConstructor.visitCode();
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 4);
        rangedConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, owner, "<init>", "([BLjava/lang/String;)V", false);
        rangedConstructor.visitInsn(Opcodes.RETURN);
        rangedConstructor.visitMaxs(3, 5);
        rangedConstructor.visitEnd();
        getter(spec, owner, "getAlgorithm", "()Ljava/lang/String;",
                "algorithm", "Ljava/lang/String;", Opcodes.ARETURN);
        constantStringMethod(spec, "getFormat", "RAW");
        getter(spec, owner, "getEncoded", "()[B", "key", "[B", Opcodes.ARETURN);
        spec.visitEnd();
        writeBytes(root.resolve("javax/crypto/spec/SecretKeySpec.class"), spec.toByteArray());
    }

    private static void generateIvParameterSpecStub(Path root) throws IOException {
        ClassWriter spec = new ClassWriter(0);
        String owner = "javax/crypto/spec/IvParameterSpec";
        spec.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object",
                new String[] {"java/security/spec/AlgorithmParameterSpec"});
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "iv", "[B", null, null).visitEnd();
        var constructor = spec.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([B)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        putConstructorField(constructor, owner, "iv", "[B", Opcodes.ALOAD, 1);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        var rangedConstructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BII)V", null, null);
        rangedConstructor.visitCode();
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        rangedConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, owner, "<init>", "([B)V", false);
        rangedConstructor.visitInsn(Opcodes.RETURN);
        rangedConstructor.visitMaxs(2, 4);
        rangedConstructor.visitEnd();
        getter(spec, owner, "getIV", "()[B", "iv", "[B", Opcodes.ARETURN);
        spec.visitEnd();
        writeBytes(root.resolve("javax/crypto/spec/IvParameterSpec.class"), spec.toByteArray());
    }

    private static void putConstructorField(
            org.objectweb.asm.MethodVisitor method, String owner,
            String field, String descriptor, int loadOpcode, int local) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(loadOpcode, local);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, descriptor);
    }

    private static void simpleConstructor(
            ClassWriter writer, String owner, int access, String descriptor) {
        var method = writer.visitMethod(access, "<init>", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void emptyMethod(
            ClassWriter writer, String name, String descriptor, int maxLocals) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, maxLocals);
        method.visitEnd();
    }

    private static void constantStringMethod(ClassWriter writer, String name, String value) {
        var method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void putField(
            InsnList code, String owner, String field, String descriptor,
            int loadOpcode, int local) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(loadOpcode, local));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, descriptor));
    }

    private static InsnList minecraftEvent(String event) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(event));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftEvent",
                "(Ljava/lang/String;)V",
                false));
        return code;
    }

    private static InsnList notifyLoadingPacketsReceived(String event) {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "levelLoadTracker",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(minecraftEvent(event));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "levelLoadTracker",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "loadingPacketsReceived",
                "()V",
                false));
        code.add(done);
        return code;
    }

    private static void getter(
            ClassWriter writer, String owner, String name, String descriptor,
            String field, String fieldDescriptor, int returnOpcode) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, fieldDescriptor);
        method.visitInsn(returnOpcode);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void writeBytes(Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    private static void replaceNull(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replaceEmptyBytes(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replaceSecondByteArray(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replaceStaticFieldReturn(
            ClassNode node, String name, String descriptor,
            String owner, String field, String fieldDescriptor) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field, fieldDescriptor));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1,
                Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            try (var stream = jar.getInputStream(jar.getEntry(entryName))) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
