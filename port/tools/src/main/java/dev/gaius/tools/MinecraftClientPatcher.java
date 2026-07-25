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
    private static final int BROWSER_SECTION_SCHEDULE_BUDGET = 4;
    private static final int BROWSER_SECTION_UPLOAD_BUDGET = 8;
    private static final int BROWSER_SECTION_CLOSE_BUDGET = 16;

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
        patchServerMainBrowser(
                args[0], root.resolve("net/minecraft/server/Main.class"));
        patchDedicatedServerBrowser(
                args[0], root.resolve("net/minecraft/server/dedicated/DedicatedServer.class"));
        patchDedicatedServerBrowserConsole(args[0], root.resolve(
                "net/minecraft/server/dedicated/DedicatedServer$1.class"));
        patchDedicatedSettingsBrowser(
                args[0], root.resolve("net/minecraft/server/dedicated/Settings.class"));
        patchServerTextFilterBrowser(
                args[0], root.resolve("net/minecraft/server/network/ServerTextFilter.class"));
        patchServerConnectionListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerConnectionListener.class"));
        patchBrowserServerAddressResolver(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ServerAddressResolver.class"));
        patchBrowserServerRedirectHandler(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.class"));
        patchResolvedServerAddressBrowserUnresolved(args[0], root.resolve(
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1.class"));
        patchConnectionBrowserWebSocket(args[0], root.resolve(
                "net/minecraft/network/Connection.class"));
        patchClientPacketUtilsBrowserInline(args[0], root.resolve(
                "net/minecraft/network/protocol/PacketUtils.class"));
        patchPacketProcessorBrowserSlice(args[0], root.resolve(
                "net/minecraft/network/PacketProcessor.class"));
        patchClientKeepAliveBrowser(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class"));
        patchResourceReloadProfiling(args[0], root.resolve(
                "net/minecraft/server/packs/resources/SimpleReloadInstance.class"));
        patchResourceReloadTaskLabels(
                args[0],
                root.resolve("net/minecraft/client/resources/model/ModelManager.class"),
                root.resolve("net/minecraft/client/gui/font/FontManager.class"));
        patchNetworkEncoderMatchers(args[0], root);
        patchClassTreeIdRegistry(args[0], root.resolve("net/minecraft/util/ClassTreeIdRegistry.class"));
        patchSynchedEntityDataClassInitialization(
                args[0], root.resolve("net/minecraft/network/syncher/SynchedEntityData.class"));
        patchEntityBrowserUuidUsesGlobalRandom(args[0], root.resolve(
                "net/minecraft/world/entity/Entity.class"));
        patchGlx(args[0], root.resolve("com/mojang/blaze3d/platform/GLX.class"));
        patchRenderSystemBrowserDeadlineCompensation(args[0], root.resolve(
                "com/mojang/blaze3d/systems/RenderSystem.class"));
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
        patchOptionsBrowserLowSimulationDistance(args[0], root.resolve(
                "net/minecraft/client/Options.class"));
        patchBrowserInputCallbacks(args[0], root);
        patchGuiGraphicsBrowserItemCache(args[0], root.resolve(
                "net/minecraft/client/gui/GuiGraphics.class"));
        patchGuiRenderTelemetry(args[0], root);
        patchDynamicUniformsBrowserInitialCapacity(args[0], root.resolve(
                "net/minecraft/client/renderer/DynamicUniforms.class"));
        patchFreeTypeUtil(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class"));
        patchDebugMemoryUntracker(args[0], root.resolve(
                "com/mojang/blaze3d/platform/DebugMemoryUntracker.class"));
        patchMinecraftServer(args[0], root.resolve(
                "net/minecraft/server/MinecraftServer.class"));
        patchChunkMapBrowserInitialViewDistance(args[0], root.resolve(
                "net/minecraft/server/level/ChunkMap.class"));
        patchPlayerSpawnFinderBrowser(args[0], root.resolve(
                "net/minecraft/server/level/PlayerSpawnFinder.class"));
        patchPrepareSpawnTaskBrowser(args[0], root.resolve(
                "net/minecraft/server/network/config/PrepareSpawnTask$Preparing.class"));
        patchServerCommonPacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerCommonPacketListenerImpl.class"));
        patchServerGamePacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerGamePacketListenerImpl.class"));
        patchServerPlayerGameModeBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/level/ServerPlayerGameMode.class"));
        patchChunkGeneratorStructureStateBrowserFastRings(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ChunkGeneratorStructureState.class"));
        patchBlockPosBrowserPackedCoordinates(args[0], root.resolve(
                "net/minecraft/core/BlockPos.class"));
        patchBiomeManagerBrowserNearestCorner(args[0], root.resolve(
                "net/minecraft/world/level/biome/BiomeManager.class"));
        patchAquiferBrowserNearestCenters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer.class"));
        patchBeardifierBrowserPackedCompute(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Beardifier.class"));
        patchImprovedNoiseBrowserHotPath(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/synth/ImprovedNoise.class"));
        patchPerlinNoiseBrowserDoubleWrap(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/synth/PerlinNoise.class"));
        patchNoiseBasedChunkGeneratorBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class"));
        patchNoiseChunkBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk.class"));
        patchNoiseInterpolatorBrowserLerp(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator.class"));
        patchNoiseChunkContextBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$1.class"));
        patchNoiseChunkCacheOnceBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce.class"));
        patchClimateRTreeBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$SubTree.class"));
        patchClimateRTreeNodeBrowserDoubleDistance(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$Node.class"));
        patchSurfaceSystemBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceSystem.class"));
        patchChunkGeneratorBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ChunkGenerator.class"));
        patchWorldCarverBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/carver/WorldCarver.class"));
        patchLightEngineBrowserYield(args[0], root.resolve(
                "net/minecraft/world/level/lighting/LightEngine.class"));
        patchLevelChunkSectionBrowserBiomeYield(args[0], root.resolve(
                "net/minecraft/world/level/chunk/LevelChunkSection.class"));
        patchChunkGenerationTaskBrowserYield(args[0], root.resolve(
                "net/minecraft/server/level/ChunkGenerationTask.class"));
        patchFriendlyByteBufBrowserLongArray(args[0], root.resolve(
                "net/minecraft/network/FriendlyByteBuf.class"));
        patchSimpleBitStorageBrowserUnpack(args[0], root.resolve(
                "net/minecraft/util/SimpleBitStorage.class"));
        patchProtoChunkBrowserHeightmapCache(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ProtoChunk.class"));
        patchHeightmapBrowserStorage(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/Heightmap.class"));
        patchBufferBuilderBrowserFastVertex(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/BufferBuilder.class"));
        patchByteBufferBuilderBrowserReserve(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/ByteBufferBuilder.class"));
        patchCompiledSectionMeshBrowserVertexBufferReuse(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/CompiledSectionMesh.class"));
        patchRegionFileVersionBrowserNoCompression(args[0], root.resolve(
                "net/minecraft/world/level/chunk/storage/RegionFileVersion.class"));
        patchPersistentEntityUuidBrowserRecovery(args[0], root.resolve(
                "net/minecraft/world/level/entity/PersistentEntitySectionManager.class"));
        patchServerLevelBrowserSafeDefaults(args[0], root.resolve(
                "net/minecraft/server/level/ServerLevel.class"));
        patchChaseClient(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseClient.class"));
        patchLanServerPinger(args[0], root.resolve(
                "net/minecraft/client/server/LanServerPinger.class"));
        patchHttpUtil(args[0], root.resolve("net/minecraft/util/HttpUtil.class"));
        patchSkinTextureDownloader(args[0], root.resolve(
                "net/minecraft/client/renderer/texture/SkinTextureDownloader.class"));
        patchUtilJarFileSystem(args[0], root.resolve("net/minecraft/util/Util.class"));
        patchResourceKeyRegistryRoot(args[0], root.resolve("net/minecraft/resources/ResourceKey.class"));
        patchVanillaPackResourcesBuilder(args[0], root.resolve(
                "net/minecraft/server/packs/VanillaPackResourcesBuilder.class"));
        patchFilePackResourcesBrowserAtlasOverlays(args[0], root.resolve(
                "net/minecraft/server/packs/FilePackResources$FileResourcesSupplier.class"));
        patchSingleFileBrowserAtlasFallback(args[0], root.resolve(
                "net/minecraft/client/renderer/texture/atlas/sources/SingleFile.class"));
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
        patchScreenBrowserFastMenus(args[0], root.resolve(
                "net/minecraft/client/gui/screens/Screen.class"));
        patchLevelLoadingScreenBrowserFastProgress(args[0], root.resolve(
                "net/minecraft/client/gui/screens/LevelLoadingScreen.class"));
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
        patchGlRenderPipelineDrawMetadata(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlRenderPipeline.class"));
        patchGlCommandEncoder(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlCommandEncoder.class"));
        patchGameRendererBrowserAutoScreenshot(args[0], root.resolve(
                "net/minecraft/client/renderer/GameRenderer.class"));
        patchParticleGroupBrowserTickSafety(args[0], root.resolve(
                "net/minecraft/client/particle/ParticleGroup.class"));
        patchClientLevelBrowserBlockBreakEffects(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientLevel.class"));
        patchMultiPlayerGameModeBrowserHitSound(args[0], root.resolve(
                "net/minecraft/client/multiplayer/MultiPlayerGameMode.class"));
        patchLevelRendererBrowserBlockBreakProgress(args[0], root.resolve(
                "net/minecraft/client/renderer/LevelRenderer.class"));
        patchEntityRenderDispatcherBrowserNullEntityGuard(args[0], root.resolve(
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher.class"));
        patchRenderSectionRegionBrowserDirectSectionCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/RenderSectionRegion.class"));
        patchSectionCompilerBrowserDirectRelativeCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionCompiler.class"));
        patchSectionRenderDispatcherBrowserThrottles(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class"));
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

    private static void patchOptionsBrowserLowSimulationDistance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Options.class");
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            boolean inSimulationDistance = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && "options.simulationDistance".equals(constant.cst)) {
                    inSimulationDistance = true;
                    continue;
                }
                if (inSimulationDistance
                        && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals("net/minecraft/SharedConstants")
                        && field.name.equals("DEBUG_ALLOW_LOW_SIM_DISTANCE")
                        && field.desc.equals("Z")) {
                    method.instructions.set(field, new InsnNode(Opcodes.ICONST_1));
                    patched++;
                    break;
                }
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Options simulation-distance range patch point was not found");
        }
        MethodNode save = find(node, "save", "()V");
        int distanceSyncs = 0;
        for (AbstractInsnNode instruction = save.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList sync = new InsnList();
            sync.add(new VarInsnNode(Opcodes.ALOAD, 0));
            sync.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Options",
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            sync.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserSingleplayerClient",
                    "syncDistances",
                    "(Lnet/minecraft/client/Minecraft;)V",
                    false));
            save.instructions.insertBefore(instruction, sync);
            distanceSyncs++;
        }
        if (distanceSyncs == 0) {
            throw new IllegalStateException("Options save browser distance sync point was not found");
        }
        save.maxStack = Math.max(save.maxStack, 1);
        write(node, output);
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

    private static void patchRenderSystemBrowserDeadlineCompensation(String jar, Path output)
            throws IOException {
        String owner = "com/mojang/blaze3d/systems/RenderSystem";
        String helperName = "browserCompensateFrameTime";
        ClassNode node = read(jar, owner + ".class");
        MethodNode limit = find(node, "limitDisplayFPS", "(I)V");
        FieldInsnNode finalTimestampStore = null;
        for (AbstractInsnNode instruction = limit.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner)
                    && field.name.equals("lastDrawTime")
                    && field.desc.equals("D")) {
                if (finalTimestampStore != null) {
                    throw new IllegalStateException(
                            "RenderSystem.limitDisplayFPS has multiple final timestamp stores");
                }
                finalTimestampStore = field;
            }
        }
        if (finalTimestampStore == null) {
            throw new IllegalStateException(
                    "RenderSystem.limitDisplayFPS final timestamp store was not found");
        }
        AbstractInsnNode nowLoad = previousOpcode(finalTimestampStore);
        if (!(nowLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.DLOAD
                || load.var != 3) {
            throw new IllegalStateException(
                    "RenderSystem.limitDisplayFPS final current-time load changed");
        }
        InsnList compensate = new InsnList();
        compensate.add(new VarInsnNode(Opcodes.DLOAD, 1));
        compensate.add(new VarInsnNode(Opcodes.DLOAD, 3));
        compensate.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compensate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                helperName,
                "(DDI)D",
                false));
        limit.instructions.insertBefore(nowLoad, compensate);
        limit.instructions.remove(nowLoad);
        limit.maxStack = Math.max(limit.maxStack, 5);

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                helperName,
                "(DDI)D",
                null,
                null);
        LabelNode returnNow = new LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DCMPL));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLT, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DSUB));
        helper.instructions.add(new InsnNode(Opcodes.DCONST_1));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        helper.instructions.add(new InsnNode(Opcodes.I2D));
        helper.instructions.add(new InsnNode(Opcodes.DDIV));
        helper.instructions.add(new InsnNode(Opcodes.DCMPL));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFGE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DRETURN));
        helper.instructions.add(returnNow);
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.DRETURN));
        helper.maxStack = 6;
        helper.maxLocals = 5;
        node.methods.add(helper);
        writeComputeFrames(node, output);
    }

    private static void patchChunkMapBrowserInitialViewDistance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkMap.class");
        MethodNode method = find(node, "setServerViewDistance", "(I)V");
        int patched = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ICONST_2) {
                continue;
            }
            AbstractInsnNode maximum = nextRealInstruction(instruction);
            AbstractInsnNode clamp = nextRealInstruction(maximum);
            if (!(maximum instanceof IntInsnNode integer)
                    || integer.getOpcode() != Opcodes.BIPUSH
                    || integer.operand != 32
                    || !(clamp instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("clamp")
                    || !call.desc.equals("(III)I")) {
                continue;
            }
            method.instructions.set(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "minimumServerViewDistance",
                    "()I",
                    false));
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "ChunkMap browser initial view-distance patch point was not found");
        }
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

    private static void patchEntityBrowserUuidUsesGlobalRandom(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/entity/Entity.class");
        MethodNode constructor = find(node, "<init>",
                "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V");
        int replacements = 0;
        for (var instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("createInsecureUUID")
                    || !call.desc.equals("(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;")) {
                continue;
            }
            AbstractInsnNode randomField = previousRealInstruction(call);
            AbstractInsnNode randomOwner = previousRealInstruction(randomField);
            if (!(randomField instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals("net/minecraft/world/entity/Entity")
                    || !field.name.equals("random")
                    || !field.desc.equals("Lnet/minecraft/util/RandomSource;")
                    || !(randomOwner instanceof VarInsnNode ownerLoad)
                    || ownerLoad.getOpcode() != Opcodes.ALOAD
                    || ownerLoad.var != 0) {
                throw new IllegalStateException("Entity UUID random source patch point changed");
            }
            constructor.instructions.remove(randomOwner);
            constructor.instructions.remove(randomField);
            call.desc = "()Ljava/util/UUID;";
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(
                    "Expected one Entity constructor UUID random replacement, got " + replacements);
        }
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

    private static void patchLevelLoadingScreenBrowserFastProgress(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/LevelLoadingScreen.class");
        MethodNode renderChunks = find(
                node,
                "renderChunks",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIIILnet/minecraft/server/level/progress/"
                        + "ChunkLoadStatusView;)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(renderChunks, code, 0, 6);
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
        replaceGuiItemRenderStateDebugName(method);
        method.maxStack = Math.max(method.maxStack, 6);
        writeComputeFrames(node, output);
    }

    private static void replaceGuiItemRenderStateDebugName(MethodNode method) {
        int replacements = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode toStringCall)
                    || !toStringCall.owner.equals("net/minecraft/network/chat/Component")
                    || !toStringCall.name.equals("toString")
                    || !toStringCall.desc.equals("()Ljava/lang/String;")) {
                continue;
            }
            AbstractInsnNode getNameInstruction = previousRealInstruction(toStringCall);
            AbstractInsnNode getItemInstruction = previousRealInstruction(getNameInstruction);
            AbstractInsnNode stackLoadInstruction = previousRealInstruction(getItemInstruction);
            if (!(getNameInstruction instanceof MethodInsnNode getNameCall)
                    || !getNameCall.owner.equals("net/minecraft/world/item/Item")
                    || !getNameCall.name.equals("getName")
                    || !getNameCall.desc.equals("()Lnet/minecraft/network/chat/Component;")
                    || !(getItemInstruction instanceof MethodInsnNode getItemCall)
                    || !getItemCall.owner.equals("net/minecraft/world/item/ItemStack")
                    || !getItemCall.name.equals("getItem")
                    || !getItemCall.desc.equals("()Lnet/minecraft/world/item/Item;")
                    || !(stackLoadInstruction instanceof VarInsnNode stackLoad)
                    || stackLoad.getOpcode() != Opcodes.ALOAD
                    || stackLoad.var != 3) {
                continue;
            }

            method.instructions.insertBefore(stackLoadInstruction, new LdcInsnNode("browser:item"));
            for (var remove = stackLoadInstruction; remove != null;) {
                var next = remove.getNext();
                method.instructions.remove(remove);
                if (remove == toStringCall) {
                    break;
                }
                remove = next;
            }
            replacements++;
            break;
        }
        if (replacements != 1) {
            throw new IllegalStateException("Expected one GUI item debug-name replacement, got " + replacements);
        }
    }

    private static AbstractInsnNode previousRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        var next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static void patchDynamicUniformsBrowserInitialCapacity(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/DynamicUniforms.class");
        MethodNode constructor = find(node, "<init>", "()V");
        int[] browserCapacities = {128, 128};
        int storageConstructors = 0;
        for (var instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/client/renderer/DynamicUniformStorage")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/lang/String;II)V")) {
                continue;
            }
            if (storageConstructors >= browserCapacities.length) {
                throw new IllegalStateException(
                        "Unexpected extra DynamicUniformStorage constructor in DynamicUniforms");
            }
            AbstractInsnNode capacity = previousRealInstruction(call);
            if (capacity == null || capacity.getOpcode() != Opcodes.ICONST_2) {
                throw new IllegalStateException(
                        "DynamicUniforms initial capacity instruction was not iconst_2 before constructor "
                                + storageConstructors);
            }
            constructor.instructions.set(
                    capacity,
                    new IntInsnNode(Opcodes.SIPUSH, browserCapacities[storageConstructors]));
            storageConstructors++;
        }
        if (storageConstructors != browserCapacities.length) {
            throw new IllegalStateException(
                    "Expected 2 DynamicUniformStorage constructors in DynamicUniforms, got "
                            + storageConstructors);
        }
        writeComputeFrames(node, output);
    }

    private static void patchGuiRenderTelemetry(String jar, Path root) throws IOException {
        patchGuiRenderStateTelemetry(jar, root.resolve(
                "net/minecraft/client/gui/render/state/GuiRenderState.class"));
        patchGuiRendererTelemetry(jar, root.resolve(
                "net/minecraft/client/gui/render/GuiRenderer.class"));
    }

    private static void patchGuiRenderStateTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/render/state/GuiRenderState.class");
        insertAtStart(find(node, "reset", "()V"), browserGuiItemPoolResetCode());
        writeComputeFrames(node, output);
    }

    private static InsnList browserGuiItemPoolResetCode() {
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiItemCache",
                "resetPool",
                "()V",
                false));
        return code;
    }

    private static void patchGuiRendererTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/render/GuiRenderer.class");

        MethodNode render = find(node, "render", "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V");
        insertAtStart(render, staticCall("reportGuiRenderStart", "()V"));

        MethodNode draw = find(node, "draw", "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V");
        InsnList drawPlan = new InsnList();
        drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
        drawPlan.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/render/GuiRenderer",
                "draws",
                "Ljava/util/List;"));
        drawPlan.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true));
        drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
        drawPlan.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/render/GuiRenderer",
                "meshesToDraw",
                "Ljava/util/List;"));
        drawPlan.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true));
        drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
        drawPlan.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/render/GuiRenderer",
                "firstDrawIndexAfterBlur",
                "I"));
        drawPlan.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportGuiDrawPlan",
                "(III)V",
                false));
        insertAtStart(draw, drawPlan);

        MethodNode itemLambda = find(node, "lambda$prepareItemElements$3",
                "(Lorg/apache/commons/lang3/mutable/MutableBoolean;II"
                        + "Lorg/apache/commons/lang3/mutable/MutableBoolean;"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                        + "Lnet/minecraft/client/gui/render/state/GuiItemRenderState;)V");
        freezeGuiAnimatedItemAtlasHit(itemLambda);

        writeComputeFrames(node, output);
    }

    private static void freezeGuiAnimatedItemAtlasHit(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && "net/minecraft/client/renderer/item/TrackingItemStackRenderState".equals(call.owner)
                    && "isAnimated".equals(call.name)
                    && "()Z".equals(call.desc)) {
                method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(instruction, new InsnNode(Opcodes.ICONST_0));
                method.instructions.remove(instruction);
                return;
            }
        }
        throw new IllegalStateException("GuiRenderer animated item atlas hit branch not found");
    }

    private static InsnList staticCall(String name, String desc) {
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                name,
                desc,
                false));
        return code;
    }

    private static void insertAtStart(MethodNode method, InsnList code) {
        method.instructions.insert(code);
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

    private static void patchGlRenderPipelineDrawMetadata(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlRenderPipeline";
        String pipeline = "com/mojang/blaze3d/pipeline/RenderPipeline";
        String vertexFormat = "com/mojang/blaze3d/vertex/VertexFormat";
        String mode = "com/mojang/blaze3d/vertex/VertexFormat$Mode";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "gaius$vertexFormat",
                "L" + vertexFormat + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "gaius$drawMode",
                "I",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(L" + pipeline + ";Lcom/mojang/blaze3d/opengl/GlProgram;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (constructorReturn != null) {
                    throw new IllegalStateException("GlRenderPipeline constructor has multiple returns");
                }
                constructorReturn = instruction;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("GlRenderPipeline constructor return was not found");
        }

        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pipeline,
                "getVertexFormat",
                "()L" + vertexFormat + ";",
                false));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "gaius$vertexFormat",
                "L" + vertexFormat + ";"));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pipeline,
                "getVertexFormatMode",
                "()L" + mode + ";",
                false));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlConst",
                "toGl",
                "(L" + mode + ";)I",
                false));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "gaius$drawMode",
                "I"));
        constructor.instructions.insertBefore(constructorReturn, initialize);
        constructor.maxStack = Math.max(constructor.maxStack, 2);
        writeComputeFrames(node, output);
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

        MethodNode drawFromBuffers = find(
                node,
                "drawFromBuffers",
                "(L" + pass + ";IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;"
                        + "Lcom/mojang/blaze3d/opengl/GlRenderPipeline;I)V");
        drawFromBuffers.instructions.clear();
        drawFromBuffers.tryCatchBlocks.clear();
        if (drawFromBuffers.localVariables != null) {
            drawFromBuffers.localVariables.clear();
        }
        InsnList draw = drawFromBuffers.instructions;
        draw.add(new VarInsnNode(Opcodes.ALOAD, 0));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "device",
                "Lcom/mojang/blaze3d/opengl/GlDevice;"));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlDevice",
                "vertexArrayCache",
                "()Lcom/mojang/blaze3d/opengl/VertexArrayCache;",
                false));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$vertexFormat",
                "Lcom/mojang/blaze3d/vertex/VertexFormat;"));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                pass,
                "vertexBuffers",
                "[Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new InsnNode(Opcodes.AALOAD));
        draw.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "com/mojang/blaze3d/opengl/GlBuffer"));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/VertexArrayCache",
                "bindVertexArray",
                "(Lcom/mojang/blaze3d/vertex/VertexFormat;"
                        + "Lcom/mojang/blaze3d/opengl/GlBuffer;)V",
                false));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$drawMode",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 9));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        LabelNode nonIndexed = new LabelNode();
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new JumpInsnNode(Opcodes.IFNULL, nonIndexed));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                pass,
                "indexBuffer",
                "Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        draw.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "com/mojang/blaze3d/opengl/GlBuffer"));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlBuffer",
                "handle",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/VertexFormat$IndexType",
                "bytes",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new IntInsnNode(Opcodes.SIPUSH, 5121));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 11));
        draw.add(new InsnNode(Opcodes.IADD));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(nonIndexed);
        draw.add(new VarInsnNode(Opcodes.ILOAD, 9));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 2));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 3));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 4));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 10));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 11));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 7));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 12));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "drawFromBuffers",
                "(IIIIIIII)V",
                false));
        draw.add(new InsnNode(Opcodes.RETURN));
        drawFromBuffers.maxStack = 8;
        drawFromBuffers.maxLocals = 13;
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
        String countingPool = "com/mojang/blaze3d/audio/Library$CountingChannelPool";

        MethodNode init = find(node, "init", "(Ljava/lang/String;Z)V");
        InsnList initCode = new InsnList();
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "init",
                "()V",
                false));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, countingPool));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 30));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                countingPool,
                "<init>",
                "(I)V",
                false));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "staticChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, countingPool));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 8));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                countingPool,
                "<init>",
                "(I)V",
                false));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "streamingChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_1));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentDevice", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_1));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "context", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "supportsDisconnections", "Z"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode, 4, 3);

        MethodNode getDefaultDeviceName = find(
                node, "getDefaultDeviceName", "()Ljava/lang/String;");
        InsnList defaultName = new InsnList();
        defaultName.add(new LdcInsnNode("Gaius Browser OpenAL"));
        defaultName.add(new InsnNode(Opcodes.ARETURN));
        replace(getDefaultDeviceName, defaultName, 1, 0);

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
                Opcodes.GETFIELD, owner, "staticChannels", poolDescriptor));
        LabelNode skipStaticCleanup = new LabelNode();
        cleanupCode.add(new JumpInsnNode(Opcodes.IFNULL, skipStaticCleanup));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "staticChannels", poolDescriptor));
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "com/mojang/blaze3d/audio/Library$ChannelPool",
                "cleanup",
                "()V",
                true));
        cleanupCode.add(skipStaticCleanup);
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "streamingChannels", poolDescriptor));
        LabelNode skipStreamingCleanup = new LabelNode();
        cleanupCode.add(new JumpInsnNode(Opcodes.IFNULL, skipStreamingCleanup));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "streamingChannels", poolDescriptor));
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "com/mojang/blaze3d/audio/Library$ChannelPool",
                "cleanup",
                "()V",
                true));
        cleanupCode.add(skipStreamingCleanup);
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
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "cleanup",
                "()V",
                false));
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
        MethodNode generateSecretKey = find(
                crypt, "generateSecretKey", "()Ljavax/crypto/SecretKey;");
        InsnList browserSecretKey = new InsnList();
        browserSecretKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "generateSecretKey",
                "()Ljavax/crypto/SecretKey;",
                false));
        browserSecretKey.add(new InsnNode(Opcodes.ARETURN));
        replace(generateSecretKey, browserSecretKey, 1, 0);

        MethodNode digestData = find(
                crypt,
                "digestData",
                "(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B");
        InsnList browserDigest = new InsnList();
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 2));
        browserDigest.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "digestData",
                "(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B",
                false));
        browserDigest.add(new InsnNode(Opcodes.ARETURN));
        replace(digestData, browserDigest, 3, 3);

        replaceNull(crypt, "decryptByteToSecretKey",
                "(Ljava/security/PrivateKey;[B)Ljavax/crypto/SecretKey;");
        MethodNode encryptUsingKey = find(
                crypt, "encryptUsingKey", "(Ljava/security/Key;[B)[B");
        InsnList browserEncrypt = new InsnList();
        browserEncrypt.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserEncrypt.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserEncrypt.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "encryptUsingKey",
                "(Ljava/security/Key;[B)[B",
                false));
        browserEncrypt.add(new InsnNode(Opcodes.ARETURN));
        replace(encryptUsingKey, browserEncrypt, 2, 2);
        replaceSecondByteArray(crypt, "decryptUsingKey", "(Ljava/security/Key;[B)[B");

        MethodNode privateKeyParser = find(
                crypt, "byteToPrivateKey", "([B)Ljava/security/PrivateKey;");
        InsnList browserPrivateKey = new InsnList();
        browserPrivateKey.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserPrivateKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPrivateKey",
                "([B)Ljava/security/PrivateKey;",
                false));
        browserPrivateKey.add(new InsnNode(Opcodes.ARETURN));
        replace(privateKeyParser, browserPrivateKey, 1, 1);

        MethodNode publicKeyParser = find(
                crypt, "byteToPublicKey", "([B)Ljava/security/PublicKey;");
        InsnList browserPublicKey = new InsnList();
        browserPublicKey.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserPublicKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPublicKey",
                "([B)Ljava/security/PublicKey;",
                false));
        browserPublicKey.add(new InsnNode(Opcodes.ARETURN));
        replace(publicKeyParser, browserPublicKey, 1, 1);
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
        code.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/authlib/GameProfile"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/login/ServerboundHelloPacket",
                "profileId",
                "()Ljava/util/UUID;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "requestedUsername",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/authlib/GameProfile",
                "<init>",
                "(Ljava/util/UUID;Ljava/lang/String;)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "startClientVerification",
                "(Lcom/mojang/authlib/GameProfile;)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(hello, code, 5, 2);
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
        MethodNode from = find(
                signer,
                "from",
                "(Ljava/security/PrivateKey;Ljava/lang/String;)Lnet/minecraft/util/Signer;");
        InsnList browserSigner = new InsnList();
        browserSigner.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserSigner.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserSigner.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserSigner",
                "create",
                "(Ljava/security/PrivateKey;Ljava/lang/String;)Lnet/minecraft/util/Signer;",
                false));
        browserSigner.add(new InsnNode(Opcodes.ARETURN));
        replace(from, browserSigner, 2, 2);
        write(signer, root.resolve("net/minecraft/util/Signer.class"));
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
        patchGameRendererBrowserInventoryWorldRenderThrottle(node);
        patchGameRendererBrowserTargetingAfterCamera(node);
        writeComputeFrames(node, output);
    }

    private static void patchGameRendererBrowserTargetingAfterCamera(ClassNode node) {
        MethodNode method = find(
                node,
                "renderLevel",
                "(Lnet/minecraft/client/DeltaTracker;)V");
        int patched = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/client/renderer/GameRenderer")
                    || !call.name.equals("extractCamera")
                    || !call.desc.equals("(F)V")) {
                continue;
            }

            InsnList stabilize = new InsnList();
            stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            stabilize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/GameRenderer",
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            stabilize.add(new InsnNode(Opcodes.DUP));
            stabilize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Minecraft",
                    "hitResult",
                    "Lnet/minecraft/world/phys/HitResult;"));
            stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            stabilize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/GameRenderer",
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            stabilize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/GameRenderer",
                    "mainCamera",
                    "Lnet/minecraft/client/Camera;"));
            stabilize.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserTargeting",
                    "stabilizeBlockHit",
                    "(Lnet/minecraft/world/phys/HitResult;"
                            + "Lnet/minecraft/client/Minecraft;"
                            + "Lnet/minecraft/client/Camera;)"
                            + "Lnet/minecraft/world/phys/HitResult;",
                    false));
            stabilize.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    "net/minecraft/client/Minecraft",
                    "hitResult",
                    "Lnet/minecraft/world/phys/HitResult;"));
            method.instructions.insert(instruction, stabilize);
            patched++;
            break;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "GameRenderer post-camera block targeting patch point was not found");
        }
    }

    private static void patchGameRendererBrowserInventoryWorldRenderThrottle(ClassNode node) {
        MethodNode method = find(node, "render", "(Lnet/minecraft/client/DeltaTracker;Z)V");
        MethodInsnNode renderLevelCall = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/GameRenderer")
                    && call.name.equals("renderLevel")
                    && call.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V")) {
                renderLevelCall = call;
                break;
            }
        }
        if (renderLevelCall == null) {
            throw new IllegalStateException("GameRenderer.renderLevel call was not found");
        }

        AbstractInsnNode renderLevelThis = previousRealInstruction(previousRealInstruction(renderLevelCall));
        if (!(renderLevelThis instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("Unexpected GameRenderer.renderLevel receiver sequence");
        }

        MethodInsnNode profilerPop = null;
        for (var instruction = renderLevelCall.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("net/minecraft/util/profiling/ProfilerFiller")
                    && call.name.equals("pop")
                    && call.desc.equals("()V")) {
                profilerPop = call;
                break;
            }
        }
        if (profilerPop == null || profilerPop.getNext() == null) {
            throw new IllegalStateException("GameRenderer world profiler pop was not found");
        }

        LabelNode continueWorld = new LabelNode();
        LabelNode afterWorld = new LabelNode();
        method.instructions.insertBefore(profilerPop.getNext(), afterWorld);

        method.instructions.insertBefore(renderLevelThis, closeLevelLoadingScreenBeforeWorldRender());

        InsnList throttle = new InsnList();
        throttle.add(new VarInsnNode(Opcodes.ALOAD, 0));
        throttle.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        throttle.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        throttle.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "shouldSkipWorldRenderForScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)Z",
                false));
        throttle.add(new JumpInsnNode(Opcodes.IFEQ, continueWorld));
        throttle.add(new VarInsnNode(Opcodes.ALOAD, 3));
        throttle.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "pop",
                "()V",
                true));
        throttle.add(new JumpInsnNode(Opcodes.GOTO, afterWorld));
        throttle.add(continueWorld);
        method.instructions.insertBefore(renderLevelThis, throttle);
    }

    private static InsnList closeLevelLoadingScreenBeforeWorldRender() {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        code.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/gui/screens/LevelLoadingScreen"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "player",
                "Lnet/minecraft/client/player/LocalPlayer;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        code.add(minecraftEvent("client.levelReady.closeLoadingScreenFromWorldRender"));
        code.add(done);
        return code;
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
        patchClientLevelBrowserAnimateTickBudget(node);
        write(node, output);
    }

    private static void patchClientLevelBrowserAnimateTickBudget(ClassNode node) {
        MethodNode method = find(node, "animateTick", "(III)V");
        int patched = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof IntInsnNode push
                    && push.getOpcode() == Opcodes.SIPUSH
                    && push.operand == 667) {
                method.instructions.set(push, new IntInsnNode(Opcodes.BIPUSH, 64));
                patched++;
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "ClientLevel.animateTick browser budget patch point was not found");
        }
    }

    private static void patchLevelRendererBrowserBlockBreakProgress(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/LevelRenderer.class");
        patchLevelRendererBrowserPrepareChunkRenders(node);
        patchLevelRendererBrowserSectionCompileThrottle(node);
        patchLevelRendererBrowserBlockOutlineOpacity(node);
        writeComputeFrames(node, output);
    }

    private static void patchEntityRenderDispatcherBrowserNullEntityGuard(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher.class");
        MethodNode method = find(node, "shouldRender",
                "(Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z");
        LabelNode render = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, render));
        guard.add(new InsnNode(Opcodes.ICONST_0));
        guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(render);
        method.instructions.insert(guard);
        writeComputeFrames(node, output);
    }

    private static void patchLevelRendererBrowserPrepareChunkRenders(ClassNode node) {
        MethodNode method = find(
                node,
                "prepareChunkRenders",
                "(Lorg/joml/Matrix4fc;DDD)"
                        + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;");
        MethodInsnNode millisCall = null;
        MethodInsnNode[] layerValuesCalls = new MethodInsnNode[2];
        int layerValuesCallCount = 0;
        TypeInsnNode matrixCopy = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("org/joml/Matrix4f")) {
                if (matrixCopy != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple matrix copies");
                }
                matrixCopy = allocation;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            if (call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("getMillis")
                    && call.desc.equals("()J")) {
                if (millisCall != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple clock reads");
                }
                millisCall = call;
                continue;
            }
            if (call.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    && call.name.equals("values")
                    && call.desc.equals("()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                if (layerValuesCallCount >= layerValuesCalls.length) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has extra render-layer reads");
                }
                layerValuesCalls[layerValuesCallCount++] = call;
            }
        }
        if (millisCall == null
                || layerValuesCallCount != layerValuesCalls.length
                || matrixCopy == null) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders browser hot-path patch points were not found");
        }

        AbstractInsnNode matrixDup = nextOpcode(matrixCopy);
        AbstractInsnNode matrixLoad = nextOpcode(matrixDup);
        AbstractInsnNode matrixConstructor = nextOpcode(matrixLoad);
        if (!(matrixDup instanceof InsnNode)
                || matrixDup.getOpcode() != Opcodes.DUP
                || !(matrixLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 1
                || !(matrixConstructor instanceof MethodInsnNode constructor)
                || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                || !constructor.owner.equals("org/joml/Matrix4f")
                || !constructor.name.equals("<init>")
                || !constructor.desc.equals("(Lorg/joml/Matrix4fc;)V")) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders matrix copy shape changed");
        }
        method.instructions.set(matrixCopy, new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.remove(matrixDup);
        method.instructions.remove(matrixLoad);
        method.instructions.remove(matrixConstructor);

        int frameLayersLocal = method.maxLocals;
        int frameMillisLocal = frameLayersLocal + 1;
        method.maxLocals += 3;
        InsnList frameState = new InsnList();
        frameState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkSectionLayers",
                "values",
                "()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;",
                false));
        frameState.add(new VarInsnNode(Opcodes.ASTORE, frameLayersLocal));
        frameState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        frameState.add(new VarInsnNode(Opcodes.LSTORE, frameMillisLocal));
        method.instructions.insert(frameState);
        method.instructions.set(millisCall, new VarInsnNode(Opcodes.LLOAD, frameMillisLocal));
        for (MethodInsnNode layerValuesCall : layerValuesCalls) {
            method.instructions.set(
                    layerValuesCall, new VarInsnNode(Opcodes.ALOAD, frameLayersLocal));
        }
    }

    private static void patchRenderSectionRegionBrowserDirectSectionCoordinates(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/RenderSectionRegion";
        ClassNode node = read(jar, owner + ".class");
        int replacements = 0;
        for (String[] target : new String[][] {
                {"getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"},
                {"getFluidState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"},
                {"getBlockEntity", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"}
        }) {
            MethodNode method = find(node, target[0], target[1]);
            int methodReplacements = replaceSectionCoordinateCalls(
                    method, "blockToSectionCoord", Opcodes.ICONST_4, Opcodes.ISHR);
            if (methodReplacements != 3) {
                throw new IllegalStateException(
                        owner + "." + target[0]
                                + " expected 3 blockToSectionCoord calls, found "
                                + methodReplacements);
            }
            replacements += methodReplacements;
        }
        if (replacements != 9) {
            throw new IllegalStateException(
                    "RenderSectionRegion expected 9 direct section coordinate replacements, found "
                            + replacements);
        }
        writeComputeFrames(node, output);
    }

    private static void patchSectionCompilerBrowserDirectRelativeCoordinates(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/SectionCompiler";
        ClassNode node = read(jar, owner + ".class");
        MethodNode compile = find(
                node,
                "compile",
                "(Lnet/minecraft/core/SectionPos;"
                        + "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
                        + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                        + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;");
        int replacements = replaceSectionCoordinateCalls(
                compile, "sectionRelative", Opcodes.BIPUSH, Opcodes.IAND);
        if (replacements != 3) {
            throw new IllegalStateException(
                    "SectionCompiler.compile expected 3 sectionRelative calls, found "
                            + replacements);
        }
        writeComputeFrames(node, output);
    }

    private static int replaceSectionCoordinateCalls(
            MethodNode method, String name, int constantOpcode, int operationOpcode) {
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"net/minecraft/core/SectionPos".equals(call.owner)
                    || !name.equals(call.name)
                    || !"(I)I".equals(call.desc)) {
                continue;
            }
            InsnList direct = new InsnList();
            if (constantOpcode == Opcodes.BIPUSH) {
                direct.add(new IntInsnNode(Opcodes.BIPUSH, 15));
            } else {
                direct.add(new InsnNode(constantOpcode));
            }
            direct.add(new InsnNode(operationOpcode));
            method.instructions.insertBefore(call, direct);
            method.instructions.remove(call);
            replacements++;
        }
        return replacements;
    }

    private static void patchMultiPlayerGameModeBrowserHitSound(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/MultiPlayerGameMode.class");
        MethodNode method = find(
                node,
                "continueDestroyBlock",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z");
        boolean foundHitSound = false;
        int patchedVolumeDivisors = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/block/SoundType")
                    && call.name.equals("getHitSound")
                    && call.desc.equals("()Lnet/minecraft/sounds/SoundEvent;")) {
                foundHitSound = true;
                continue;
            }
            if (foundHitSound
                    && instruction instanceof LdcInsnNode constant
                    && Float.valueOf(8.0f).equals(constant.cst)
                    && nextOpcode(instruction) != null
                    && nextOpcode(instruction).getOpcode() == Opcodes.FDIV) {
                constant.cst = Float.valueOf(4.0f);
                patchedVolumeDivisors++;
                break;
            }
        }
        if (!foundHitSound || patchedVolumeDivisors != 1) {
            throw new IllegalStateException(
                    "MultiPlayerGameMode browser hit sound volume patch point was not found");
        }
        write(node, output);
    }

    private static void patchLevelRendererBrowserBlockOutlineOpacity(ClassNode node) {
        MethodNode method = find(
                node,
                "renderBlockOutline",
                "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack;Z"
                        + "Lnet/minecraft/client/renderer/state/LevelRenderState;)V");
        boolean patchedOpacity = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof IntInsnNode alpha
                    && alpha.getOpcode() == Opcodes.BIPUSH
                    && alpha.operand == 102
                    && nextOpcode(instruction) instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/util/ARGB")
                    && call.name.equals("black")
                    && call.desc.equals("(I)I")) {
                method.instructions.set(alpha, new IntInsnNode(Opcodes.SIPUSH, 180));
                patchedOpacity = true;
                break;
            }
        }
        if (!patchedOpacity) {
            throw new IllegalStateException(
                    "LevelRenderer browser block outline opacity patch point was not found");
        }
    }

    private static void patchLevelRendererBrowserSectionCompileThrottle(ClassNode node) {
        MethodNode method = find(
                node,
                "compileSections",
                "(Lnet/minecraft/client/Camera;)V");
        boolean disabledSyncCompile = false;
        boolean throttledAsyncSchedule = false;

        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode syncCall
                    && syncCall.owner.equals("net/minecraft/client/renderer/chunk/SectionRenderDispatcher")
                    && syncCall.name.equals("rebuildSectionSync")
                    && syncCall.desc.equals("(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;"
                            + "Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V")) {
                AbstractInsnNode cursor = syncCall;
                while ((cursor = previousOpcode(cursor)) != null) {
                    if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                        AbstractInsnNode condition = previousOpcode(jump);
                        if (condition instanceof VarInsnNode load
                                && load.getOpcode() == Opcodes.ILOAD) {
                            method.instructions.set(load, new InsnNode(Opcodes.ICONST_0));
                            disabledSyncCompile = true;
                            break;
                        }
                    }
                }
                if (!disabledSyncCompile) {
                    throw new IllegalStateException(
                            "LevelRenderer.compileSections sync rebuild branch patch point was not found");
                }
                continue;
            }

            if (instruction instanceof MethodInsnNode addCall
                    && addCall.getOpcode() == Opcodes.INVOKEINTERFACE
                    && addCall.owner.equals("java/util/List")
                    && addCall.name.equals("add")
                    && addCall.desc.equals("(Ljava/lang/Object;)Z")) {
                AbstractInsnNode loadSection = previousOpcode(addCall);
                AbstractInsnNode loadList = previousOpcode(loadSection);
                if (!(loadSection instanceof VarInsnNode sectionLoad)
                        || sectionLoad.getOpcode() != Opcodes.ALOAD
                        || !(loadList instanceof VarInsnNode listLoad)
                        || listLoad.getOpcode() != Opcodes.ALOAD) {
                    continue;
                }
                AbstractInsnNode pop = nextOpcode(addCall);
                if (!(pop instanceof InsnNode) || pop.getOpcode() != Opcodes.POP) {
                    throw new IllegalStateException(
                            "LevelRenderer.compileSections List.add POP patch point was not found");
                }
                LabelNode allowAdd = new LabelNode();
                LabelNode afterAdd = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, listLoad.var));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "size",
                        "()I",
                        true));
                guard.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_SECTION_SCHEDULE_BUDGET));
                guard.add(new JumpInsnNode(Opcodes.IF_ICMPLT, allowAdd));
                guard.add(new JumpInsnNode(Opcodes.GOTO, afterAdd));
                guard.add(allowAdd);
                method.instructions.insertBefore(loadList, guard);
                method.instructions.insert(pop, afterAdd);
                throttledAsyncSchedule = true;
                break;
            }

        }

        if (!disabledSyncCompile || !throttledAsyncSchedule) {
            throw new IllegalStateException(
                    "LevelRenderer browser section compile throttle patch points were not found");
        }
        method.maxStack = Math.max(method.maxStack, 3);
    }

    private static void patchSectionRenderDispatcherBrowserThrottles(String jar, Path output)
            throws IOException {
        ClassNode node = read(
                jar,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class");
        patchSectionRenderDispatcherBrowserExecutor(node);
        MethodNode method = find(node, "uploadAllPendingUploads", "()V");
        InsnList code = new InsnList();
        LabelNode uploadLoop = new LabelNode();
        LabelNode uploadDone = new LabelNode();
        LabelNode closeLoop = new LabelNode();
        LabelNode closeDone = new LabelNode();

        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(uploadLoop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_SECTION_UPLOAD_BUDGET));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, uploadDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "toUpload",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "poll",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Runnable"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new JumpInsnNode(Opcodes.IFNULL, uploadDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Runnable",
                "run",
                "()V",
                true));
        code.add(new IincInsnNode(1, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, uploadLoop));

        code.add(uploadDone);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(closeLoop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_SECTION_CLOSE_BUDGET));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, closeDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "toClose",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "poll",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/chunk/SectionMesh"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new JumpInsnNode(Opcodes.IFNULL, closeDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/client/renderer/chunk/SectionMesh",
                "close",
                "()V",
                true));
        code.add(new IincInsnNode(3, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, closeLoop));
        code.add(closeDone);
        code.add(new InsnNode(Opcodes.RETURN));

        replace(method, code, 3, 5);
        writeComputeFrames(node, output);
    }

    private static void patchSectionRenderDispatcherBrowserExecutor(ClassNode node) {
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/client/multiplayer/ClientLevel;"
                        + "Lnet/minecraft/client/renderer/LevelRenderer;"
                        + "Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
                        + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V");
        MethodNode runTask = find(node, "runTask", "()V");
        int patched = 0;

        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/util/thread/ConsecutiveExecutor")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/util/concurrent/Executor;Ljava/lang/String;)V")) {
                continue;
            }
            AbstractInsnNode executorLoad = previousOpcode(previousOpcode(call));
            if (!(executorLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 3) {
                throw new IllegalStateException(
                        "SectionRenderDispatcher consecutive executor load was not found");
            }
            constructor.instructions.insert(executorLoad, deferBrowserRenderExecutor());
            patched++;
            break;
        }

        for (AbstractInsnNode instruction = runTask.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/TracingExecutor")
                    || !call.name.equals("forName")
                    || !call.desc.equals("(Ljava/lang/String;)Ljava/util/concurrent/Executor;")) {
                continue;
            }
            runTask.instructions.insert(call, deferBrowserRenderExecutor());
            patched++;
            break;
        }

        if (patched != 2) {
            throw new IllegalStateException(
                    "SectionRenderDispatcher browser deferred executor patch points were not found");
        }
    }

    private static MethodInsnNode deferBrowserRenderExecutor() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "defer",
                "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;",
                false);
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

        MethodNode timerHack = find(node, "startTimerHackThread", "()V");
        InsnList timerCode = new InsnList();
        timerCode.add(new InsnNode(Opcodes.RETURN));
        replace(timerHack, timerCode, 0, 0);
        write(node, output);
    }

    private static void patchDedicatedServerBrowserConsole(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/dedicated/DedicatedServer$1.class");
        MethodNode run = find(node, "run", "()V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(run, code, 0, 1);
        write(node, output);
    }

    private static void patchServerMainBrowser(String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/Main";
        ClassNode node = read(jar, owner + ".class");

        MethodNode pidFile = find(node, "writePidFile", "(Ljava/nio/file/Path;)V");
        InsnList pidCode = new InsnList();
        pidCode.add(new InsnNode(Opcodes.RETURN));
        replace(pidFile, pidCode, 0, 1);

        MethodNode main = find(node, "main", "([Ljava/lang/String;)V");
        String authenticationService =
                "com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService";
        boolean offlineAuthenticationPatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals(authenticationService)
                    || !constructor.name.equals("<init>")
                    || !constructor.desc.equals("(Ljava/net/Proxy;)V")) {
                continue;
            }
            AbstractInsnNode proxy = previousOpcode(constructor);
            AbstractInsnNode duplicate = previousOpcode(proxy);
            AbstractInsnNode allocation = previousOpcode(duplicate);
            if (!(proxy instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.owner.equals("java/net/Proxy")
                    || !field.name.equals("NO_PROXY")
                    || duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !type.desc.equals(authenticationService)) {
                throw new IllegalStateException(
                        "Server authentication-service constructor shape changed");
            }
            main.instructions.remove(allocation);
            main.instructions.remove(duplicate);
            main.instructions.set(constructor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    authenticationService,
                    "createOffline",
                    "(Ljava/net/Proxy;)L" + authenticationService + ";",
                    false));
            offlineAuthenticationPatched = true;
            break;
        }
        if (!offlineAuthenticationPatched) {
            throw new IllegalStateException(
                    "Server offline authentication-service patch point was not found");
        }

        boolean startupFailurePatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Failed to start the minecraft server".equals(constant.cst)) {
                continue;
            }
            AbstractInsnNode loggerLoad = instruction.getPrevious();
            while (loggerLoad != null
                    && !(loggerLoad instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals(owner)
                            && field.name.equals("LOGGER"))) {
                loggerLoad = loggerLoad.getPrevious();
            }
            AbstractInsnNode throwableLoad = instruction.getNext();
            while (throwableLoad != null && throwableLoad.getOpcode() < 0) {
                throwableLoad = throwableLoad.getNext();
            }
            if (loggerLoad == null
                    || !(throwableLoad instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("Server startup failure handler shape changed");
            }
            InsnList propagate = new InsnList();
            propagate.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
            propagate.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "rethrowStartupFailure",
                    "(Ljava/lang/Throwable;)V",
                    false));
            main.instructions.insertBefore(loggerLoad, propagate);
            main.maxStack = Math.max(main.maxStack, 1);
            startupFailurePatched = true;
            break;
        }
        if (!startupFailurePatched) {
            throw new IllegalStateException("Server startup failure handler was not found");
        }

        String factoryDescriptor = "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                + "Lnet/minecraft/server/WorldStem;"
                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                + "Lnet/minecraft/server/Services;"
                + "Ljoptsimple/OptionSet;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljava/lang/Thread;)Lnet/minecraft/server/dedicated/DedicatedServer;";
        MethodNode factory = find(node, "lambda$main$3", factoryDescriptor);
        String server = "net/minecraft/server/dedicated/DedicatedServer";
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, server));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 11));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "dataFixer",
                "()Lcom/mojang/datafixers/DataFixer;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                server,
                "<init>",
                "(Ljava/lang/Thread;"
                        + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                        + "Lnet/minecraft/server/packs/repository/PackRepository;"
                        + "Lnet/minecraft/server/WorldStem;"
                        + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                        + "Lcom/mojang/datafixers/DataFixer;"
                        + "Lnet/minecraft/server/Services;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "valueOf",
                "(Ljoptsimple/OptionSpec;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, server, "setPort", "(I)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "has",
                "(Ljoptsimple/OptionSpec;)Z",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, server, "setDemo", "(Z)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "valueOf",
                "(Ljoptsimple/OptionSpec;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                server,
                "setId",
                "(Ljava/lang/String;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(factory, code, 9, 13);
        writeComputeFrames(node, output);
    }

    private static void patchDedicatedServerBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/dedicated/DedicatedServer";
        String properties = "net/minecraft/server/dedicated/DedicatedServerProperties";
        ClassNode node = read(jar, owner + ".class");
        MethodNode init = find(node, "initServer", "()Z");
        removeBooleanFieldBlock(init, properties, "managementServerEnabled", 2, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableQuery", 1, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableRcon", 1, Opcodes.IFEQ);
        removeMethodConditionBlock(
                init, owner, "getMaxTickLength", "()J", 1, Opcodes.IFLE);
        removeBooleanFieldBlock(init, properties, "enableJmxMonitoring", 1, Opcodes.IFEQ);

        MethodNode tick = find(node, "tickServer", "(Ljava/util/function/BooleanSupplier;)V");
        removeNullableFieldBlock(
                tick, owner, "jsonRpcServer", "Lnet/minecraft/server/jsonrpc/ManagementServer;");

        MethodNode exit = find(node, "onServerExit", "()V");
        removeNullableFieldBlock(
                exit, owner, "gui", "Lnet/minecraft/server/gui/MinecraftServerGui;");
        removeNullableFieldBlock(
                exit, owner, "rconThread", "Lnet/minecraft/server/rcon/thread/RconThread;");
        removeNullableFieldBlock(
                exit, owner, "queryThreadGs4", "Lnet/minecraft/server/rcon/thread/QueryThreadGs4;");
        removeNullableFieldBlock(
                exit, owner, "jsonRpcServer", "Lnet/minecraft/server/jsonrpc/ManagementServer;");
        writeComputeFrames(node, output);
    }

    private static void patchDedicatedSettingsBrowser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/dedicated/Settings.class");
        MethodNode store = find(node, "store", "(Ljava/nio/file/Path;)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(store, code, 0, 2);
        write(node, output);
    }

    private static void patchServerTextFilterBrowser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/ServerTextFilter.class");
        MethodNode create = find(
                node,
                "createFromConfig",
                "(Lnet/minecraft/server/dedicated/DedicatedServerProperties;)"
                        + "Lnet/minecraft/server/network/ServerTextFilter;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(create, code, 1, 1);
        write(node, output);
    }

    private static void removeBooleanFieldBlock(
            MethodNode method,
            String owner,
            String fieldName,
            int setupInstructionCount,
            int jumpOpcode) {
        FieldInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals(fieldName)) {
                marker = field;
                break;
            }
        }
        if (marker == null) {
            throw new IllegalStateException(
                    method.name + " browser block field was not found: " + fieldName);
        }
        removeConditionalBlock(method, marker, setupInstructionCount, jumpOpcode);
    }

    private static void removeMethodConditionBlock(
            MethodNode method,
            String owner,
            String methodName,
            String descriptor,
            int setupInstructionCount,
            int jumpOpcode) {
        MethodInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(methodName)
                    && call.desc.equals(descriptor)) {
                marker = call;
                break;
            }
        }
        if (marker == null) {
            throw new IllegalStateException(
                    method.name + " browser condition was not found: " + methodName);
        }
        removeConditionalBlock(method, marker, setupInstructionCount, jumpOpcode);
    }

    private static void removeNullableFieldBlock(
            MethodNode method, String owner, String fieldName, String descriptor) {
        FieldInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals(fieldName)
                    && field.desc.equals(descriptor)) {
                marker = field;
                break;
            }
        }
        if (marker == null) {
            throw new IllegalStateException(
                    method.name + " nullable browser field was not found: " + fieldName);
        }
        removeConditionalBlock(method, marker, 1, Opcodes.IFNULL);
    }

    private static void removeConditionalBlock(
            MethodNode method,
            AbstractInsnNode marker,
            int setupInstructionCount,
            int jumpOpcode) {
        AbstractInsnNode start = marker;
        for (int index = 0; index < setupInstructionCount; index++) {
            start = previousRealInstruction(start);
            if (start == null) {
                throw new IllegalStateException(method.name + " browser block start was not found");
            }
        }
        JumpInsnNode exit = null;
        for (var instruction = nextRealInstruction(marker);
                instruction != null;
                instruction = nextRealInstruction(instruction)) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == jumpOpcode) {
                exit = jump;
                break;
            }
        }
        if (exit == null) {
            throw new IllegalStateException(method.name + " browser block exit was not found");
        }

        var removed = new java.util.HashSet<AbstractInsnNode>();
        for (var instruction = start; instruction != null && instruction != exit.label;) {
            var next = instruction.getNext();
            removed.add(instruction);
            method.instructions.remove(instruction);
            instruction = next;
        }
        method.tryCatchBlocks.removeIf(block -> removed.contains(block.start)
                || removed.contains(block.end)
                || removed.contains(block.handler));
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
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

    private static void patchServerConnectionListenerBrowserWorker(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/network/ServerConnectionListener";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "startTcpServerListener", "(Ljava/net/InetAddress;I)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "channels",
                "Ljava/util/List;"));
        code.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap",
                "<init>",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "remote",
                "(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new LdcInsnNode(Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new TypeInsnNode(Opcodes.NEW, owner + "$1"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner + "$1",
                "<init>",
                "(L" + owner + ";)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "io/netty/resolver/NoopAddressResolverGroup",
                "INSTANCE",
                "Lio/netty/resolver/NoopAddressResolverGroup;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "resolver",
                "(Lio/netty/resolver/AddressResolverGroup;)Lio/netty/bootstrap/Bootstrap;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "tunnelAddress",
                "()Ljava/net/InetSocketAddress;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "connect",
                "(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelFuture",
                "syncUninterruptibly",
                "()Lio/netty/channel/ChannelFuture;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 5, 3);
        writeComputeFrames(node, output);
    }

    private static void patchBrowserServerAddressResolver(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ServerAddressResolver.class");
        MethodNode method = find(node, "lambda$static$0",
                "(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)"
                        + "Ljava/util/Optional;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/resolver/ServerAddress",
                "getHost",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/resolver/ServerAddress",
                "getPort",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/net/InetSocketAddress",
                "createUnresolved",
                "(Ljava/lang/String;I)Ljava/net/InetSocketAddress;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress",
                "from",
                "(Ljava/net/InetSocketAddress;)"
                        + "Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "of",
                "(Ljava/lang/Object;)Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 3, 1);
        write(node, output);
    }

    private static void patchResolvedServerAddressBrowserUnresolved(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1.class");
        for (String methodName : new String[] {"getHostName", "getHostIp"}) {
            MethodNode method = find(node, methodName, "()Ljava/lang/String;");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1",
                    "val$address",
                    "Ljava/net/InetSocketAddress;"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/net/InetSocketAddress",
                    "getHostString",
                    "()Ljava/lang/String;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, 1);
        }
        write(node, output);
    }

    private static void patchBrowserServerRedirectHandler(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.class");
        MethodNode method = find(node, "createDnsSrvRedirectHandler",
                "()Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler",
                "EMPTY",
                "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    private static void patchConnectionBrowserWebSocket(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/Connection.class");
        MethodNode connect = find(node, "connect",
                "(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;"
                        + "Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap",
                "<init>",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/network/Connection$1"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/network/Connection$1",
                "<init>",
                "(Lnet/minecraft/network/Connection;)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new LdcInsnNode(Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "disableResolver",
                "()Lio/netty/bootstrap/Bootstrap;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "connect",
                "(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(connect, code, 4, 3);

        MethodNode tick = find(node, "tick", "()V");
        tick.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "pumpAll",
                "()V",
                false));
        writeComputeFrames(node, output);
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
        boolean browserChannelPumpHooked = false;
        boolean singleplayerWorkerHooked = false;
        boolean singleplayerWorkerStopHooked = false;
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
                InsnList browserPackets = new InsnList();
                browserPackets.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserClientNetwork",
                        "install",
                        "()V",
                        false));
                browserPackets.add(pumpBrowserChannels());
                method.instructions.insert(browserPackets);
                browserChannelPumpHooked = true;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, minecraftStateReport());
                        stateHooked = true;
                    }
                }
                method.maxStack = Math.max(method.maxStack, 9);
            } else if (method.name.equals("doWorldLoad")
                    && method.desc.equals(
                            "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                    + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                    + "Lnet/minecraft/server/WorldStem;Z)V")) {
                LabelNode vanillaIntegratedServer = new LabelNode();
                InsnList worker = new InsnList();
                worker.add(new VarInsnNode(Opcodes.ALOAD, 0));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 1));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 3));
                worker.add(new VarInsnNode(Opcodes.ILOAD, 4));
                worker.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserSingleplayerClient",
                        "open",
                        "(Lnet/minecraft/client/Minecraft;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/WorldStem;Z)Z",
                        false));
                worker.add(new JumpInsnNode(Opcodes.IFEQ, vanillaIntegratedServer));
                worker.add(new InsnNode(Opcodes.RETURN));
                worker.add(vanillaIntegratedServer);
                method.instructions.insert(worker);
                method.maxStack = Math.max(method.maxStack, 4);
                singleplayerWorkerHooked = true;
            } else if (method.name.equals("disconnect")
                    && method.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;ZZ)V")) {
                method.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserSingleplayerClient",
                        "stop",
                        "()V",
                        false));
                singleplayerWorkerStopHooked = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Minecraft uptime lambda was not found");
        }
        if (!stateHooked) {
            throw new IllegalStateException("Minecraft runTick hook point was not found");
        }
        if (!browserChannelPumpHooked) {
            throw new IllegalStateException("Minecraft browser channel pump hook point was not found");
        }
        if (!throwableHooked) {
            throw new IllegalStateException("Minecraft run throwable diagnostic hook point was not found");
        }
        if (!singleplayerWorkerHooked) {
            throw new IllegalStateException("Minecraft singleplayer worker hook point was not found");
        }
        if (!singleplayerWorkerStopHooked) {
            throw new IllegalStateException("Minecraft singleplayer worker stop hook point was not found");
        }
        write(node, output);
    }

    private static void patchBrowserInputCallbacks(String jar, Path root) throws IOException {
        patchBrowserMouseHandler(jar, root.resolve("net/minecraft/client/MouseHandler.class"));
        patchBrowserKeyboardHandler(jar, root.resolve("net/minecraft/client/KeyboardHandler.class"));
    }

    private static void patchBrowserMouseHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/MouseHandler.class");

        MethodNode move = find(node, "lambda$setup$3", "(JDD)V");
        InsnList moveCode = new InsnList();
        moveCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        moveCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        moveCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        moveCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        moveCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onMove",
                "(JDD)V",
                false));
        moveCode.add(new InsnNode(Opcodes.RETURN));
        replace(move, moveCode, 7, 7);

        MethodNode button = find(node, "lambda$setup$5", "(JIII)V");
        InsnList buttonCode = new InsnList();
        buttonCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        buttonCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        buttonCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/MouseButtonInfo"));
        buttonCode.add(new InsnNode(Opcodes.DUP));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        buttonCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/MouseButtonInfo",
                "<init>",
                "(II)V",
                false));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        buttonCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onButton",
                "(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
                false));
        buttonCode.add(new InsnNode(Opcodes.RETURN));
        replace(button, buttonCode, 6, 6);

        MethodNode scroll = find(node, "lambda$setup$7", "(JDD)V");
        InsnList scrollCode = new InsnList();
        scrollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        scrollCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        scrollCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        scrollCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        scrollCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onScroll",
                "(JDD)V",
                false));
        scrollCode.add(new InsnNode(Opcodes.RETURN));
        replace(scroll, scrollCode, 7, 7);

        MethodNode onButton = find(
                node,
                "onButton",
                "(JLnet/minecraft/client/input/MouseButtonInfo;I)V");
        boolean entryHooked = false;
        boolean dispatchHooked = false;
        boolean resultHooked = false;
        boolean overlayGatePatched = false;
        for (var instruction = onButton.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!entryHooked
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ASTORE
                    && variable.var == 5) {
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.LLOAD, 1));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 5));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/platform/Window",
                        "handle",
                        "()J",
                        false));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/input/MouseButtonInfo",
                        "button",
                        "()I",
                        false));
                entry.add(new VarInsnNode(Opcodes.ILOAD, 4));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseHandlerEntry",
                        "(JJII)V",
                        false));
                onButton.instructions.insert(instruction, entry);
                entryHooked = true;
            }
            if (!dispatchHooked
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ASTORE
                    && variable.var == 13
                    && previousRealInstruction(instruction) instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/input/MouseButtonEvent")
                    && call.name.equals("<init>")
                    && call.desc.equals("(DDLnet/minecraft/client/input/MouseButtonInfo;)V")) {
                InsnList dispatch = new InsnList();
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, 8));
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, 10));
                dispatch.add(new VarInsnNode(Opcodes.ILOAD, 6));
                dispatch.add(new VarInsnNode(Opcodes.ALOAD, 12));
                dispatch.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseHandlerDispatch",
                        "(DDZLjava/lang/Object;)V",
                        false));
                onButton.instructions.insert(instruction, dispatch);
                dispatchHooked = true;
            }
            if (!resultHooked
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/gui/screens/Screen")
                    && call.name.equals("mouseClicked")
                    && call.desc.equals("(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z")) {
                InsnList result = new InsnList();
                result.add(new InsnNode(Opcodes.DUP));
                result.add(new VarInsnNode(Opcodes.DLOAD, 8));
                result.add(new VarInsnNode(Opcodes.DLOAD, 10));
                result.add(new VarInsnNode(Opcodes.ALOAD, 12));
                result.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseClickedResult",
                        "(ZDDLjava/lang/Object;)V",
                        false));
                onButton.instructions.insert(instruction, result);
                resultHooked = true;
            }
            if (!overlayGatePatched
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("getOverlay")
                    && call.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                AbstractInsnNode maybeIfNull = nextRealInstruction(instruction);
                AbstractInsnNode maybeGoto = nextRealInstruction(maybeIfNull);
                if (!(maybeIfNull instanceof JumpInsnNode ifNull)
                        || ifNull.getOpcode() != Opcodes.IFNULL
                        || !(maybeGoto instanceof JumpInsnNode blocked)
                        || blocked.getOpcode() != Opcodes.GOTO) {
                    throw new IllegalStateException("MouseHandler overlay gate shape changed");
                }
                LabelNode blockedLabel = blocked.label;
                LabelNode allow = new LabelNode();
                LabelNode popAndBlock = new LabelNode();
                InsnList gate = new InsnList();
                gate.add(new InsnNode(Opcodes.DUP));
                gate.add(new JumpInsnNode(Opcodes.IFNULL, allow));
                gate.add(new InsnNode(Opcodes.DUP));
                gate.add(new TypeInsnNode(
                        Opcodes.INSTANCEOF,
                        "net/minecraft/client/gui/screens/LoadingOverlay"));
                gate.add(new JumpInsnNode(Opcodes.IFEQ, popAndBlock));
                gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
                gate.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/MouseHandler",
                        "minecraft",
                        "Lnet/minecraft/client/Minecraft;"));
                gate.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "screen",
                        "Lnet/minecraft/client/gui/screens/Screen;"));
                gate.add(new JumpInsnNode(Opcodes.IFNONNULL, allow));
                gate.add(popAndBlock);
                gate.add(new InsnNode(Opcodes.POP));
                gate.add(new JumpInsnNode(Opcodes.GOTO, blockedLabel));
                gate.add(allow);
                gate.add(new InsnNode(Opcodes.POP));
                onButton.instructions.insertBefore(maybeIfNull, gate);
                onButton.instructions.remove(maybeIfNull);
                onButton.instructions.remove(maybeGoto);
                overlayGatePatched = true;
            }
        }
        if (!entryHooked || !dispatchHooked || !resultHooked || !overlayGatePatched) {
            throw new IllegalStateException(
                    "MouseHandler.onButton browser telemetry/overlay hook points were not found");
        }

        writeComputeFrames(node, output);
    }

    private static void patchBrowserKeyboardHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/KeyboardHandler.class");

        MethodNode key = find(node, "lambda$setup$6", "(JIIII)V");
        InsnList keyCode = new InsnList();
        keyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        keyCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        keyCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/KeyEvent"));
        keyCode.add(new InsnNode(Opcodes.DUP));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 6));
        keyCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/KeyEvent",
                "<init>",
                "(III)V",
                false));
        keyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/KeyboardHandler",
                "keyPress",
                "(JILnet/minecraft/client/input/KeyEvent;)V",
                false));
        keyCode.add(new InsnNode(Opcodes.RETURN));
        replace(key, keyCode, 8, 7);

        MethodNode character = find(node, "lambda$setup$8", "(JII)V");
        InsnList charCode = new InsnList();
        charCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        charCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        charCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/CharacterEvent"));
        charCode.add(new InsnNode(Opcodes.DUP));
        charCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        charCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/CharacterEvent",
                "<init>",
                "(II)V",
                false));
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/KeyboardHandler",
                "charTyped",
                "(JLnet/minecraft/client/input/CharacterEvent;)V",
                false));
        charCode.add(new InsnNode(Opcodes.RETURN));
        replace(character, charCode, 6, 5);

        writeComputeFrames(node, output);
    }

    private static MethodInsnNode pumpBrowserChannels() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "pumpAll",
                "()V",
                false);
    }

    private static InsnList minecraftStateReport() {
        InsnList code = new InsnList();
        LabelNode skipped = new LabelNode();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "shouldReportMinecraftState",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skipped));
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
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "fallbackClientLevel",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
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
        code.add(skipped);
        return code;
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

    /**
     * Resource-pack reloads can keep the browser renderer frozen long enough for vanilla's
     * deferred configuration keepalive queue to miss every listener tick. Browser transport
     * callbacks already run on the page event loop, so reply immediately instead of waiting
     * for RenderSystem's desktop poll-events gate.
     */
    private static void patchClientKeepAliveBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class");
        MethodNode predicate = find(node, "lambda$handleKeepAlive$1", "()Z");
        if (predicate == null) {
            throw new IOException("ClientCommonPacketListenerImpl keepalive predicate was not found");
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(predicate, code, 1, 0);
        writeComputeFrames(node, output);
    }

    /**
     * Browser WebSocket callbacks already share the client page's event loop. Treating them as
     * foreign Java threads makes configuration packets requeue forever while a resource reload
     * owns the normal client tick. Restrict the inline path to the configuration listener; game
     * packets stay queued so terrain handling is sliced by the client tick.
     */
    private static void patchClientPacketUtilsBrowserInline(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/protocol/PacketUtils.class");
        MethodNode method = find(node, "ensureRunningOnSameThread",
                "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/PacketProcessor;)V");
        if (method == null) {
            throw new IOException("PacketUtils client packet scheduler patch point was not found");
        }
        LabelNode vanillaScheduling = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanillaScheduling));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(vanillaScheduling);
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 1);
        writeComputeFrames(node, output);
    }

    /** Handles at most one queued packet per browser turn so chunk listeners cannot monopolize it. */
    private static void patchPacketProcessorBrowserSlice(String jar, Path output) throws IOException {
        String owner = "net/minecraft/network/PacketProcessor";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "processQueuedPackets", "()V");
        if (method == null) {
            throw new IOException("PacketProcessor browser slice patch point was not found");
        }
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "closed", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "packetsToBeHandled", "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Queue", "poll", "()Ljava/lang/Object;", true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "net/minecraft/network/PacketProcessor$ListenerAndPacket"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/PacketProcessor$ListenerAndPacket",
                "handle",
                "()V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 2);
        writeComputeFrames(node, output);
    }

    /** Adds diagnostics around each vanilla resource reload listener without reordering it. */
    private static void patchResourceReloadProfiling(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/server/packs/resources/SimpleReloadInstance.class");
        MethodNode method = find(node, "prepareTasks",
                "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;"
                        + "Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;"
                        + "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;"
                        + "Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;");
        if (method == null) {
            throw new IOException("SimpleReloadInstance prepareTasks patch point was not found");
        }
        boolean wrapped = false;
        int listenerLocal = -1;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("net/minecraft/server/packs/resources/PreparableReloadListener")) {
                continue;
            }
            AbstractInsnNode next = instruction.getNext();
            while (next != null && next.getOpcode() < 0) {
                next = next.getNext();
            }
            if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserResourceReloadProfiler",
                    "wrap",
                    "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)"
                            + "Lnet/minecraft/server/packs/resources/PreparableReloadListener;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, store.var));
            method.instructions.insert(store, code);
            listenerLocal = store.var;
            wrapped = true;
            break;
        }
        if (!wrapped) {
            throw new IOException("SimpleReloadInstance listener iteration patch point was not found");
        }
        boolean barrierUnwrapped = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/server/packs/resources/SimpleReloadInstance")
                    || !call.name.equals("createBarrierForListener")) {
                continue;
            }
            AbstractInsnNode listenerLoad = instruction.getPrevious();
            while (listenerLoad != null) {
                if (listenerLoad instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == listenerLocal) {
                    break;
                }
                listenerLoad = listenerLoad.getPrevious();
            }
            if (listenerLoad == null) {
                continue;
            }
            method.instructions.insert(listenerLoad, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserResourceReloadProfiler",
                    "unwrap",
                    "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)"
                            + "Lnet/minecraft/server/packs/resources/PreparableReloadListener;",
                    false));
            barrierUnwrapped = true;
            break;
        }
        if (!barrierUnwrapped) {
            throw new IOException("SimpleReloadInstance barrier listener patch point was not found");
        }
        method.maxStack = Math.max(method.maxStack, 1);
        writeComputeFrames(node, output);
    }

    /** Labels the handful of large vanilla continuations that dominate custom resource-pack stalls. */
    private static void patchResourceReloadTaskLabels(String jar, Path modelOutput, Path fontOutput)
            throws IOException {
        ClassNode modelManager = read(jar, "net/minecraft/client/resources/model/ModelManager.class");
        MethodNode modelReload = find(modelManager, "reload", "(Lnet/minecraft/server/packs/resources/"
                + "PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
                + "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
                + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        if (modelReload == null) {
            throw new IOException("ModelManager reload task label patch point was not found");
        }
        String[] modelLabels = {
                "ModelManager.specialBlockModels",
                "ModelManager.discoverModelDependencies",
                "ModelManager.buildModelGroups",
        };
        int modelContinuation = 0;
        for (AbstractInsnNode instruction = modelReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenApplyAsync")) {
                continue;
            }
            if (modelContinuation >= modelLabels.length) {
                throw new IOException("ModelManager has unexpected async continuation count");
            }
            modelReload.instructions.insertBefore(call, reloadTaskLabel(modelLabels[modelContinuation++]));
        }
        if (modelContinuation != modelLabels.length) {
            throw new IOException("ModelManager async continuation patch point was not found");
        }
        patchModelManagerBrowserDependencyScheduler(modelReload);
        patchModelManagerBrowserDependencyResult(nodeMethod(modelManager, "lambda$reload$4"));
        writeComputeFrames(modelManager, modelOutput);

        ClassNode fontManager = read(jar, "net/minecraft/client/gui/font/FontManager.class");
        MethodNode fontReload = find(fontManager, "reload", "(Lnet/minecraft/server/packs/resources/"
                + "PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
                + "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
                + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        if (fontReload == null) {
            throw new IOException("FontManager reload task label patch point was not found");
        }
        int fontApply = 0;
        for (AbstractInsnNode instruction = fontReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenAcceptAsync")) {
                continue;
            }
            fontReload.instructions.insertBefore(call, reloadTaskLabel("FontManager.apply"));
            fontApply++;
        }
        if (fontApply != 1) {
            throw new IOException("FontManager apply task label patch point was not found");
        }
        patchFontManagerApplySections(fontManager);
        writeComputeFrames(fontManager, fontOutput);
    }

    /** Records the four synchronous phases of FontManager.apply without changing their order. */
    private static void patchFontManagerApplySections(ClassNode node) throws IOException {
        MethodNode apply = find(node, "apply", "(Lnet/minecraft/client/gui/font/FontManager$Preparation;"
                + "Lnet/minecraft/util/profiling/ProfilerFiller;)V");
        if (apply == null) {
            throw new IOException("FontManager apply section patch point was not found");
        }
        String[] names = {
                "FontManager.apply.closeFontSets",
                "FontManager.apply.closeProviders",
                "FontManager.apply.createFontSets",
                "FontManager.apply.bindAtlasProviders",
        };
        int section = 0;
        for (AbstractInsnNode instruction = apply.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            boolean match = (call.owner.equals("java/util/Collection") && call.name.equals("forEach"))
                    || (call.owner.equals("java/util/List") && call.name.equals("forEach"))
                    || (call.owner.equals("java/util/Map") && call.name.equals("forEach"))
                    || (call.owner.equals("net/minecraft/client/resources/model/AtlasManager")
                            && call.name.equals("forEach"));
            if (!match) {
                continue;
            }
            if (section >= names.length) {
                throw new IOException("FontManager apply has unexpected synchronous sections");
            }
            String name = names[section++];
            apply.instructions.insertBefore(call, reloadSectionEvent("sectionStarted", name));
            apply.instructions.insert(call, reloadSectionEvent("sectionCompleted", name));
        }
        if (section != names.length) {
            throw new IOException("FontManager apply synchronous section patch points were not found");
        }
    }

    private static InsnList reloadTaskLabel(String label) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(label));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                "label",
                "(Ljava/util/concurrent/Executor;Ljava/lang/String;)Ljava/util/concurrent/Executor;",
                false));
        return code;
    }

    private static InsnList reloadSectionEvent(String method, String name) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(name));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                method,
                "(Ljava/lang/String;)V",
                false));
        return code;
    }

    /** Replaces vanilla's one-turn dependency discovery continuation with a frame-budgeted stage. */
    private static void patchModelManagerBrowserDependencyScheduler(MethodNode reload)
            throws IOException {
        MethodInsnNode continuation = null;
        for (AbstractInsnNode instruction = reload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenApplyAsync")) {
                continue;
            }
            AbstractInsnNode previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) {
                previous = previous.getPrevious();
            }
            if (!(previous instanceof MethodInsnNode label)
                    || !label.owner.equals("dev/gaius/browser/BrowserResourceReloadProfiler")
                    || !label.name.equals("label")) {
                continue;
            }
            AbstractInsnNode labelText = label.getPrevious();
            while (labelText != null && labelText.getOpcode() < 0) {
                labelText = labelText.getPrevious();
            }
            if (labelText instanceof LdcInsnNode text
                    && "ModelManager.discoverModelDependencies".equals(text.cst)) {
                continuation = call;
                break;
            }
        }
        if (continuation == null) {
            throw new IOException("ModelManager dependency continuation patch point was not found");
        }
        AbstractInsnNode factory = continuation.getPrevious();
        while (factory != null) {
            if (factory instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                break;
            }
            factory = factory.getPrevious();
        }
        if (!(factory instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode)) {
            throw new IOException("ModelManager dependency continuation lambda was not found");
        }
        AbstractInsnNode thirdFuture = previousOpcode(factory);
        AbstractInsnNode secondFuture = previousOpcode(thirdFuture);
        AbstractInsnNode firstFuture = previousOpcode(secondFuture);
        if (!(firstFuture instanceof VarInsnNode first && first.getOpcode() == Opcodes.ALOAD && first.var == 8)
                || !(secondFuture instanceof VarInsnNode second && second.getOpcode() == Opcodes.ALOAD && second.var == 9)
                || !(thirdFuture instanceof VarInsnNode third && third.getOpcode() == Opcodes.ALOAD && third.var == 10)) {
            throw new IOException("ModelManager dependency continuation captures changed");
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 8));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 9));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 10));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/resources/model/BrowserModelDependencyScheduler",
                "continuation",
                "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;"
                        + "Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/function/Function;",
                false));
        reload.instructions.insertBefore(firstFuture, replacement);
        reload.instructions.remove(firstFuture);
        reload.instructions.remove(secondFuture);
        reload.instructions.remove(thirdFuture);
        reload.instructions.remove(factory);
        continuation.name = "thenComposeAsync";
    }

    /** Constructs the private ResolvedModels record inside ModelManager after frame-sliced discovery. */
    private static void patchModelManagerBrowserDependencyResult(MethodNode reloadState) throws IOException {
        if (reloadState == null) {
            throw new IOException("ModelManager reload result continuation was not found");
        }
        TypeInsnNode resolvedModelsCast = null;
        for (AbstractInsnNode instruction = reloadState.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("net/minecraft/client/resources/model/ModelManager$ResolvedModels")) {
                resolvedModelsCast = cast;
                break;
            }
        }
        if (resolvedModelsCast == null) {
            throw new IOException("ModelManager resolved-model result cast was not found");
        }
        AbstractInsnNode store = nextOpcode(resolvedModelsCast);
        if (!(store instanceof VarInsnNode resultStore) || resultStore.getOpcode() != Opcodes.ASTORE) {
            throw new IOException("ModelManager resolved-model result storage changed");
        }
        resolvedModelsCast.desc = "net/minecraft/client/resources/model/ModelDiscovery";
        InsnList constructResolvedModels = new InsnList();
        constructResolvedModels.add(new TypeInsnNode(
                Opcodes.NEW, "net/minecraft/client/resources/model/ModelManager$ResolvedModels"));
        constructResolvedModels.add(new InsnNode(Opcodes.DUP));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ALOAD, resultStore.var));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/model/ModelDiscovery",
                "missingModel",
                "()Lnet/minecraft/client/resources/model/ResolvedModel;",
                false));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ALOAD, resultStore.var));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/model/ModelDiscovery",
                "resolve",
                "()Ljava/util/Map;",
                false));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/resources/model/ModelManager$ResolvedModels",
                "<init>",
                "(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)V",
                false));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ASTORE, resultStore.var));
        reloadState.instructions.insert(store, constructResolvedModels);
    }

    private static MethodNode nodeMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        return null;
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
        boolean levelReadyFallbackHooked = false;
        boolean closeLoadingScreenHooked = false;

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
                        && call.name.equals("isLevelReady")
                        && call.desc.equals("()Z")) {
                    AbstractInsnNode next = nextOpcode(call);
                    if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                        throw new IllegalStateException("ClientPacketListener level-ready branch shape changed");
                    }
                    LabelNode ready = new LabelNode();
                    InsnList code = new InsnList();
                    code.add(new JumpInsnNode(Opcodes.IFNE, ready));
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "net/minecraft/client/multiplayer/ClientPacketListener",
                            "level",
                            "Lnet/minecraft/client/multiplayer/ClientLevel;"));
                    code.add(new JumpInsnNode(Opcodes.IFNULL, jump.label));
                    code.add(minecraftEvent("client.levelReady.playerPresentFallback"));
                    code.add(ready);
                    method.instructions.insert(call, code);
                    method.instructions.remove(jump);
                    method.maxStack = Math.max(method.maxStack, 2);
                    levelReadyFallbackHooked = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/ClientPacketListener")
                        && call.name.equals("notifyPlayerLoaded")
                        && call.desc.equals("()V")) {
                    method.instructions.insert(call, closeLevelLoadingScreenIfPresent());
                    method.maxStack = Math.max(method.maxStack, 2);
                    closeLoadingScreenHooked = true;
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
                || !notifyPlayerLoadedHooked
                || !levelReadyFallbackHooked
                || !closeLoadingScreenHooked) {
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
        MethodNode isLevelReady = find(tracker, "isLevelReady", "()Z");
        LabelNode notClientReady = new LabelNode();
        LabelNode notPlayerChunk = new LabelNode();
        LabelNode notServerWait = new LabelNode();
        InsnList levelReadyCode = new InsnList();
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        levelReadyCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "clientState",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;"));
        levelReadyCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notClientReady));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady"));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady",
                "readyAt",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        levelReadyCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "closeDelayMs",
                "J"));
        levelReadyCode.add(new InsnNode(Opcodes.LADD));
        levelReadyCode.add(new InsnNode(Opcodes.LCMP));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFLT, notServerWait));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notClientReady);
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notPlayerChunk));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notPlayerChunk);
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notServerWait));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer"));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "timeoutAfter",
                "()J",
                false));
        levelReadyCode.add(new InsnNode(Opcodes.LCMP));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFLT, notServerWait));
        levelReadyCode.add(minecraftEvent("client.levelReady.timeoutFallback"));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notServerWait);
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_0));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        replace(isLevelReady, levelReadyCode, 6, 2);
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

        ClassNode waitingForPlayerChunk = read(
                jar,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk.class");
        MethodNode isReady = find(waitingForPlayerChunk, "isReady", "()Z");
        InsnList readyCode = new InsnList();
        readyCode.add(new InsnNode(Opcodes.ICONST_1));
        readyCode.add(new InsnNode(Opcodes.IRETURN));
        replace(isReady, readyCode, 1, 1);
        write(waitingForPlayerChunk,
                root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk.class"));
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

    private static void patchChunkGeneratorStructureStateBrowserFastRings(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/chunk/ChunkGeneratorStructureState";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "lambda$generateRingPositions$5",
                "(IILnet/minecraft/core/HolderSet;Lnet/minecraft/util/RandomSource;)"
                        + "Lnet/minecraft/world/level/ChunkPos;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/world/level/ChunkPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(II)V",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 5);
        write(node, output);
    }

    private static void patchNoiseBasedChunkGeneratorBrowserYield(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class");
        MethodNode method = find(
                node,
                "doFill",
                "(Lnet/minecraft/world/level/levelgen/blending/Blender;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;II)"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;");
        cacheNoiseBasedChunkGeneratorDoFillConstants(method);
        int checkpoints = insertPulseAfterLoopCounter(method, 23, -1);
        if (checkpoints != 1) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator browser yield point was not found: " + checkpoints);
        }
        requireWorldgenLoopPulses("NoiseBasedChunkGenerator.doFill", method);
        MethodNode applyCarvers = find(
                node,
                "applyCarvers",
                "(Lnet/minecraft/server/level/WorldGenRegion;J"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V");
        requireWorldgenLoopPulses("NoiseBasedChunkGenerator.applyCarvers", applyCarvers);
        writeComputeFrames(node, output);
    }

    private static void cacheNoiseBasedChunkGeneratorDoFillConstants(MethodNode method) {
        String generator = "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator";
        String settings = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
        int debugVoidLocal = method.maxLocals++;
        int defaultBlockLocal = method.maxLocals++;
        VarInsnNode chunkPosStore = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE
                    && previousRealInstruction(store) instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/chunk/ChunkAccess")
                    && call.name.equals("getPos")
                    && call.desc.equals("()Lnet/minecraft/world/level/ChunkPos;")) {
                chunkPosStore = store;
                break;
            }
        }
        if (chunkPosStore == null) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator.doFill chunk-position cache point was not found");
        }

        InsnList constants = new InsnList();
        constants.add(new VarInsnNode(Opcodes.ALOAD, chunkPosStore.var));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/SharedConstants",
                "debugVoidTerrain",
                "(Lnet/minecraft/world/level/ChunkPos;)Z",
                false));
        constants.add(new VarInsnNode(Opcodes.ISTORE, debugVoidLocal));
        constants.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constants.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                generator,
                "settings",
                "Lnet/minecraft/core/Holder;"));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Holder",
                "value",
                "()Ljava/lang/Object;",
                true));
        constants.add(new TypeInsnNode(Opcodes.CHECKCAST, settings));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                settings,
                "defaultBlock",
                "()Lnet/minecraft/world/level/block/state/BlockState;",
                false));
        constants.add(new VarInsnNode(Opcodes.ASTORE, defaultBlockLocal));

        int debugVoidReplacements = 0;
        int defaultBlockReplacements = 0;
        for (var instruction = method.instructions.getFirst(); instruction != null;) {
            var next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/SharedConstants")
                    && call.name.equals("debugVoidTerrain")
                    && call.desc.equals("(Lnet/minecraft/world/level/ChunkPos;)Z")) {
                var getPos = previousRealInstruction(call);
                var chunk = previousRealInstruction(getPos);
                if (getPos instanceof MethodInsnNode getPosCall
                        && getPosCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && getPosCall.owner.equals("net/minecraft/world/level/chunk/ChunkAccess")
                        && getPosCall.name.equals("getPos")
                        && getPosCall.desc.equals("()Lnet/minecraft/world/level/ChunkPos;")
                        && chunk instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 4) {
                    method.instructions.insertBefore(chunk, new VarInsnNode(
                            Opcodes.ILOAD, debugVoidLocal));
                    method.instructions.remove(chunk);
                    method.instructions.remove(getPos);
                    method.instructions.remove(call);
                    debugVoidReplacements++;
                }
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(settings)
                    && call.name.equals("defaultBlock")
                    && call.desc.equals("()Lnet/minecraft/world/level/block/state/BlockState;")) {
                var cast = previousRealInstruction(call);
                var value = previousRealInstruction(cast);
                var field = previousRealInstruction(value);
                var owner = previousRealInstruction(field);
                if (cast instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.CHECKCAST
                        && type.desc.equals(settings)
                        && value instanceof MethodInsnNode valueCall
                        && valueCall.getOpcode() == Opcodes.INVOKEINTERFACE
                        && valueCall.owner.equals("net/minecraft/core/Holder")
                        && valueCall.name.equals("value")
                        && field instanceof FieldInsnNode settingsField
                        && settingsField.getOpcode() == Opcodes.GETFIELD
                        && settingsField.owner.equals(generator)
                        && settingsField.name.equals("settings")
                        && owner instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 0) {
                    method.instructions.insertBefore(owner, new VarInsnNode(
                            Opcodes.ALOAD, defaultBlockLocal));
                    method.instructions.remove(owner);
                    method.instructions.remove(field);
                    method.instructions.remove(value);
                    method.instructions.remove(cast);
                    method.instructions.remove(call);
                    defaultBlockReplacements++;
                }
            }
            instruction = next;
        }
        if (debugVoidReplacements != 1 || defaultBlockReplacements != 1) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator.doFill constant caches mismatch: debugVoid="
                            + debugVoidReplacements + ", defaultBlock=" + defaultBlockReplacements);
        }
        method.instructions.insert(chunkPosStore, constants);
    }

    private static void patchBlockPosBrowserPackedCoordinates(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/core/BlockPos.class");
        for (String name : new String[] {"getX", "getY", "getZ"}) {
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.LLOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserBlockPos",
                    name,
                    "(J)I",
                    false));
            code.add(new InsnNode(Opcodes.IRETURN));
            replace(find(node, name, "(J)I"), code, 2, 2);
        }

        InsnList asLong = new InsnList();
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 0));
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 1));
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 2));
        asLong.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBlockPos",
                "asLong",
                "(III)J",
                false));
        asLong.add(new InsnNode(Opcodes.LRETURN));
        replace(find(node, "asLong", "(III)J"), asLong, 3, 3);
        write(node, output);
    }

    private static void patchImprovedNoiseBrowserHotPath(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/synth/ImprovedNoise";
        ClassNode node = read(jar, owner + ".class");
        MethodNode noise = find(node, "noise", "(DDDDD)D");
        InsnList noiseCode = new InsnList();
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "p", "[B"));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 1));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "xo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "yo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "zo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 7));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 9));
        noiseCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserImprovedNoise",
                "noise",
                "([BDDDDDDDD)D",
                false));
        noiseCode.add(new InsnNode(Opcodes.DRETURN));
        replace(noise, noiseCode, 17, 11);

        MethodNode method = find(node, "sampleAndLerp", "(IIIDDDD)D");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "p", "[B"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.DLOAD, 4));
        code.add(new VarInsnNode(Opcodes.DLOAD, 6));
        code.add(new VarInsnNode(Opcodes.DLOAD, 8));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserImprovedNoise",
                "sampleAndLerp",
                "([BIIIDDDD)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(method, code, 12, 12);
        write(node, output);
    }

    private static void patchBiomeManagerBrowserNearestCorner(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/biome/BiomeManager";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "getBiome",
                "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;");
        InsnList code = new InsnList();
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/BlockPos",
                    coordinate == 0 ? "getX" : coordinate == 1 ? "getY" : "getZ",
                    "()I",
                    false));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISUB));
            code.add(new VarInsnNode(Opcodes.ISTORE, coordinate + 2));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "biomeZoomSeed", "J"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBiomeManager",
                "nearestCorner",
                "(JIII)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "noiseBiomeSource",
                "Lnet/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource;"));
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ILOAD, coordinate + 2));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISHR));
            code.add(new VarInsnNode(Opcodes.ILOAD, 5));
            if (coordinate < 2) {
                code.add(new InsnNode(coordinate == 0 ? Opcodes.ICONST_2 : Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IUSHR));
            }
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IAND));
            code.add(new InsnNode(Opcodes.IADD));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource",
                "getNoiseBiome",
                "(III)Lnet/minecraft/core/Holder;",
                true));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 6);
        write(node, output);
    }

    private static void patchAquiferBrowserNearestCenters(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer";
        ClassNode node = read(jar, owner + ".class");
        String resultField = "browserNearestResult";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                resultField,
                "[I",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseRouter;"
                        + "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;II"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Aquifer browser result-cache constructor return was not found");
        }
        InsnList initializeResult = new InsnList();
        initializeResult.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeResult.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        initializeResult.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        initializeResult.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, resultField, "[I"));
        constructor.instructions.insertBefore(constructorReturn, initializeResult);

        MethodNode method = find(
                node,
                "computeSubstance",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;D)"
                        + "Lnet/minecraft/world/level/block/state/BlockState;");
        MethodInsnNode firstGridX = null;
        MethodInsnNode firstStatus = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (firstGridX == null
                    && call.owner.equals(owner)
                    && call.name.equals("gridX")
                    && call.desc.equals("(I)I")) {
                firstGridX = call;
            }
            if (firstStatus == null
                    && call.owner.equals(owner)
                    && call.name.equals("getAquiferStatus")
                    && call.desc.equals("(I)Lnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;")) {
                firstStatus = call;
            }
        }
        if (firstGridX == null || firstStatus == null) {
            throw new IllegalStateException("Aquifer browser nearest-center patch points were not found");
        }
        AbstractInsnNode originalPathStart = previousRealInstruction(firstGridX);
        originalPathStart = previousRealInstruction(originalPathStart);
        originalPathStart = previousRealInstruction(originalPathStart);
        AbstractInsnNode statusReceiver = previousRealInstruction(firstStatus);
        statusReceiver = previousRealInstruction(statusReceiver);
        if (!(originalPathStart instanceof VarInsnNode loadX)
                || loadX.getOpcode() != Opcodes.ILOAD
                || loadX.var != 4
                || !(statusReceiver instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("Aquifer browser nearest-center bytecode shape changed");
        }

        LabelNode originalPath = new LabelNode();
        LabelNode nearestReady = new LabelNode();
        method.instructions.insertBefore(statusReceiver, nearestReady);
        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "aquiferLocationCache", "[J"));
        for (String field : new String[] {
                "minGridX", "minGridY", "minGridZ", "gridSizeX", "gridSizeZ"
        }) {
            fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        }
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 4));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 6));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, resultField, "[I"));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserAquifer",
                "selectNearestCached",
                "([JIIIIIIII[I)Z",
                false));
        fastPath.add(new JumpInsnNode(Opcodes.IFEQ, originalPath));
        for (int index = 0; index < 8; index++) {
            fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, resultField, "[I"));
            fastPath.add(new IntInsnNode(Opcodes.BIPUSH, index));
            fastPath.add(new InsnNode(Opcodes.IALOAD));
            fastPath.add(new VarInsnNode(Opcodes.ISTORE, 11 + index));
        }
        fastPath.add(new JumpInsnNode(Opcodes.GOTO, nearestReady));
        fastPath.add(originalPath);
        method.instructions.insertBefore(originalPathStart, fastPath);
        writeComputeFrames(node, output);
    }

    private static void patchPerlinNoiseBrowserDoubleWrap(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/synth/PerlinNoise";
        ClassNode node = read(jar, owner + ".class");
        String browserAmplitudes = "browserAmplitudes";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                browserAmplitudes,
                "[D",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/util/RandomSource;Lcom/mojang/datafixers/util/Pair;Z)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Perlin browser amplitude constructor return was not found");
        }
        InsnList initializeAmplitudes = new InsnList();
        initializeAmplitudes.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeAmplitudes.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeAmplitudes.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "amplitudes",
                "Lit/unimi/dsi/fastutil/doubles/DoubleList;"));
        initializeAmplitudes.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPerlinNoise",
                "copyAmplitudes",
                "(Lit/unimi/dsi/fastutil/doubles/DoubleList;)[D",
                false));
        initializeAmplitudes.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                browserAmplitudes,
                "[D"));
        constructor.instructions.insertBefore(constructorReturn, initializeAmplitudes);

        MethodNode getValue = find(node, "getValue", "(DDDDDZ)D");
        int amplitudeReads = 0;
        for (AbstractInsnNode instruction = getValue.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(owner)
                    || !field.name.equals("amplitudes")
                    || !field.desc.equals("Lit/unimi/dsi/fastutil/doubles/DoubleList;")) {
                continue;
            }
            AbstractInsnNode index = nextRealInstruction(field);
            AbstractInsnNode read = nextRealInstruction(index);
            if (!(index instanceof VarInsnNode loadIndex)
                    || loadIndex.getOpcode() != Opcodes.ILOAD
                    || !(read instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("it/unimi/dsi/fastutil/doubles/DoubleList")
                    || !call.name.equals("getDouble")
                    || !call.desc.equals("(I)D")) {
                continue;
            }
            field.name = browserAmplitudes;
            field.desc = "[D";
            getValue.instructions.set(call, new InsnNode(Opcodes.DALOAD));
            amplitudeReads++;
        }
        if (amplitudeReads != 1) {
            throw new IllegalStateException(
                    "Perlin browser amplitude read patch point changed: " + amplitudeReads);
        }

        MethodNode method = find(node, "wrap", "(D)D");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.DLOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPerlinNoise",
                "wrap",
                "(D)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(method, code, 2, 2);
        writeComputeFrames(node, output);
    }

    private static void patchBeardifierBrowserPackedCompute(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        ClassNode node = read(jar, owner + ".class");
        String piecesField = "browserPackedPieces";
        String junctionsField = "browserPackedJunctions";
        for (String field : new String[] {piecesField, junctionsField}) {
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    field,
                    "[I",
                    null,
                    null));
        }

        MethodNode constructor = find(
                node,
                "<init>",
                "(Ljava/util/List;Ljava/util/List;"
                        + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Beardifier browser constructor return was not found");
        }
        InsnList initializePacked = new InsnList();
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initializePacked.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "packPieces",
                "(Ljava/util/List;)[I",
                false));
        initializePacked.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, piecesField, "[I"));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 2));
        initializePacked.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "packJunctions",
                "(Ljava/util/List;)[I",
                false));
        initializePacked.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, junctionsField, "[I"));
        constructor.instructions.insertBefore(constructorReturn, initializePacked);
        constructor.maxStack = Math.max(constructor.maxStack, 2);

        MethodNode compute = find(
                node,
                "compute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D");
        LabelNode populated = new LabelNode();
        LabelNode inside = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "affectedBox",
                "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, populated));
        code.add(new InsnNode(Opcodes.DCONST_0));
        code.add(new InsnNode(Opcodes.DRETURN));
        code.add(populated);
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext",
                    coordinate == 0 ? "blockX" : coordinate == 1 ? "blockY" : "blockZ",
                    "()I",
                    true));
            code.add(new VarInsnNode(Opcodes.ISTORE, coordinate + 2));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "affectedBox",
                "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/levelgen/structure/BoundingBox",
                "isInside",
                "(III)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, inside));
        code.add(new InsnNode(Opcodes.DCONST_0));
        code.add(new InsnNode(Opcodes.DRETURN));
        code.add(inside);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, piecesField, "[I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, junctionsField, "[I"));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "BEARD_KERNEL", "[F"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "compute",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;III)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(compute, code, 6, 5);
        write(node, output);
    }

    private static void patchSurfaceSystemBrowserYield(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/SurfaceSystem.class");
        MethodNode method = find(
                node,
                "buildSurface",
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/core/Registry;Z"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V");
        requireWorldgenLoopPulses("SurfaceSystem.buildSurface", method);
        write(node, output);
    }

    private static void patchNoiseChunkBrowserYield(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk";
        ClassNode node = read(jar, owner + ".class");
        convertNoiseChunkCountersToInt(node, false);
        addNoiseInterpolatorArrayCache(node, owner);
        requireWorldgenLoopPulses("NoiseChunk.fillSlice", find(node, "fillSlice", "(ZI)V"));
        requireWorldgenLoopPulses(
                "NoiseChunk.fillAllDirectly",
                find(
                        node,
                        "fillAllDirectly",
                        "([DLnet/minecraft/world/level/levelgen/DensityFunction;)V"));
        requireWorldgenLoopPulses(
                "NoiseChunk.selectCellYZ", find(node, "selectCellYZ", "(II)V"));
        replaceNoiseInterpolatorUpdate(node, owner, "updateForY", "cellStartBlockY", "inCellY", false);
        replaceNoiseInterpolatorUpdate(node, owner, "updateForX", "cellStartBlockX", "inCellX", false);
        replaceNoiseInterpolatorUpdate(node, owner, "updateForZ", "cellStartBlockZ", "inCellZ", true);
        writeComputeFrames(node, output);
    }

    private static void patchNoiseInterpolatorBrowserLerp(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        ClassNode node = read(jar, owner + ".class");
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForY",
                new String[][] {
                    {"valueXZ00", "noise000", "noise010"},
                    {"valueXZ10", "noise100", "noise110"},
                    {"valueXZ01", "noise001", "noise011"},
                    {"valueXZ11", "noise101", "noise111"}
                });
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForX",
                new String[][] {
                    {"valueZ0", "valueXZ00", "valueXZ10"},
                    {"valueZ1", "valueXZ01", "valueXZ11"}
                });
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForZ",
                new String[][] {{"value", "valueZ0", "valueZ1"}});
        MethodNode compute = find(
                node,
                "compute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D");
        int patched = 0;
        for (AbstractInsnNode instruction = compute.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("lerp3")
                    || !call.desc.equals("(DDDDDDDDDDD)D")) {
                continue;
            }
            call.owner = "dev/gaius/browser/BrowserNoiseInterpolator";
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "NoiseInterpolator browser lerp patch point was not found: " + patched);
        }
        write(node, output);
    }

    private static void replaceNoiseInterpolatorLerpMethod(
            ClassNode node,
            String owner,
            String methodName,
            String[][] fields) {
        InsnList code = new InsnList();
        for (String[] assignment : fields) {
            String target = assignment[0];
            String start = assignment[1];
            String end = assignment[2];
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, start, "D"));
            code.add(new VarInsnNode(Opcodes.DLOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, end, "D"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, start, "D"));
            code.add(new InsnNode(Opcodes.DSUB));
            code.add(new InsnNode(Opcodes.DMUL));
            code.add(new InsnNode(Opcodes.DADD));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, target, "D"));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        replace(find(node, methodName, "(D)V"), code, 9, 3);
    }

    private static void patchNoiseChunkContextBrowserIntCounters(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$1";
        ClassNode node = read(jar, owner + ".class");
        int rewrites = convertNoiseChunkCountersToInt(node, false);
        if (rewrites < 6) {
            throw new IllegalStateException(
                    "NoiseChunk context int-counter patch points changed: " + rewrites);
        }
        writeComputeFrames(node, output);
    }

    private static void patchNoiseChunkCacheOnceBrowserIntCounters(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce";
        ClassNode node = read(jar, owner + ".class");
        int rewrites = convertNoiseChunkCountersToInt(node, true);
        if (rewrites < 12) {
            throw new IllegalStateException(
                    "NoiseChunk cache int-counter patch points changed: " + rewrites);
        }
        writeComputeFrames(node, output);
    }

    private static int convertNoiseChunkCountersToInt(ClassNode node, boolean cacheFields) {
        int rewrites = 0;
        for (FieldNode field : node.fields) {
            if (field.desc.equals("J")
                    && (isNoiseChunkCounter(field.name)
                            || (cacheFields && isNoiseCacheCounter(field.name)))) {
                field.desc = "I";
                rewrites++;
            }
        }
        for (MethodNode method : node.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field
                        && field.desc.equals("J")
                        && ((field.owner.equals("net/minecraft/world/level/levelgen/NoiseChunk")
                                        && isNoiseChunkCounter(field.name))
                                || (field.owner.equals(
                                                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce")
                                        && isNoiseCacheCounter(field.name)))) {
                    field.desc = "I";
                    rewrites++;
                }
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;) {
                var nextInstruction = instruction.getNext();
                if ((instruction.getOpcode() == Opcodes.LCONST_0
                                || instruction.getOpcode() == Opcodes.LCONST_1)
                        && (isCounterField(previousRealInstruction(instruction))
                                || isCounterField(nextRealInstruction(instruction)))) {
                    method.instructions.set(
                            instruction,
                            new InsnNode(instruction.getOpcode() == Opcodes.LCONST_0
                                    ? Opcodes.ICONST_0
                                    : Opcodes.ICONST_1));
                    rewrites++;
                } else if (instruction.getOpcode() == Opcodes.LADD
                        && isCounterField(previousRealInstruction(
                                previousRealInstruction(instruction)))) {
                    method.instructions.set(instruction, new InsnNode(Opcodes.IADD));
                    rewrites++;
                } else if (instruction.getOpcode() == Opcodes.LCMP
                        && (isCounterField(previousRealInstruction(instruction))
                                || isCounterField(previousRealInstruction(
                                        previousRealInstruction(instruction))))) {
                    var next = nextRealInstruction(instruction);
                    if (!(next instanceof JumpInsnNode jump)
                            || (jump.getOpcode() != Opcodes.IFEQ
                                    && jump.getOpcode() != Opcodes.IFNE)) {
                        throw new IllegalStateException(
                                "NoiseChunk counter comparison shape changed in " + method.name);
                    }
                    nextInstruction = jump.getNext();
                    method.instructions.insertBefore(
                            jump,
                            new JumpInsnNode(
                                    jump.getOpcode() == Opcodes.IFEQ
                                            ? Opcodes.IF_ICMPEQ
                                            : Opcodes.IF_ICMPNE,
                                    jump.label));
                    method.instructions.remove(jump);
                    method.instructions.remove(instruction);
                    rewrites++;
                }
                instruction = nextInstruction;
            }
        }
        return rewrites;
    }

    private static boolean isNoiseChunkCounter(String name) {
        return name.equals("interpolationCounter") || name.equals("arrayInterpolationCounter");
    }

    private static boolean isNoiseCacheCounter(String name) {
        return name.equals("lastCounter") || name.equals("lastArrayCounter");
    }

    private static boolean isCounterField(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode field
                && (isNoiseChunkCounter(field.name) || isNoiseCacheCounter(field.name));
    }

    private static void addNoiseInterpolatorArrayCache(ClassNode node, String owner) {
        String interpolator = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        String arrayDesc = "[L" + interpolator + ";";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserInterpolators",
                arrayDesc,
                null,
                null));

        MethodNode cache = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserInterpolators",
                "()" + arrayDesc,
                null,
                null);
        LabelNode ready = new LabelNode();
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserInterpolators", arrayDesc));
        cache.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "interpolators", "Ljava/util/List;"));
        cache.instructions.add(new InsnNode(Opcodes.ICONST_0));
        cache.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, interpolator));
        cache.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "toArray",
                "([Ljava/lang/Object;)[Ljava/lang/Object;",
                true));
        cache.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, arrayDesc));
        cache.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserInterpolators", arrayDesc));
        cache.instructions.add(ready);
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new InsnNode(Opcodes.ARETURN));
        cache.maxStack = 3;
        cache.maxLocals = 2;
        node.methods.add(cache);
    }

    private static void replaceNoiseInterpolatorUpdate(
            ClassNode node,
            String owner,
            String methodName,
            String startField,
            String positionField,
            boolean incrementCounter) {
        String interpolator = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        String arrayDesc = "[L" + interpolator + ";";
        MethodNode method = find(node, methodName, "(ID)V");
        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, startField, "I"));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, positionField, "I"));
        if (incrementCounter) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "interpolationCounter", "I"));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IADD));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "interpolationCounter", "I"));
        }
        LabelNode cached = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserInterpolators", arrayDesc));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserInterpolators",
                "()" + arrayDesc,
                false));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.DLOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                interpolator,
                methodName,
                "(D)V",
                false));
        code.add(new IincInsnNode(5, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 5, 6);
    }

    private static void patchClimateRTreeBrowserYield(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/biome/Climate$RTree$SubTree.class");
        MethodNode method = find(
                node,
                "search",
                "([JLnet/minecraft/world/level/biome/Climate$RTree$Leaf;"
                        + "Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)"
                        + "Lnet/minecraft/world/level/biome/Climate$RTree$Leaf;");
        requireWorldgenLoopPulses("Climate.RTree.SubTree.search", method);
        write(node, output);
    }

    private static void patchClimateRTreeNodeBrowserDoubleDistance(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/biome/Climate$RTree$Node";
        String parameter = "net/minecraft/world/level/biome/Climate$Parameter";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBounds",
                "[D",
                null,
                null));

        MethodNode constructor = find(node, "<init>", "(Ljava/util/List;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Climate RTree Node constructor return was not found");
        }
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "parameterSpace",
                "[L" + parameter + ";"));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClimate",
                "prepareBounds",
                "([L" + parameter + ";)[D",
                false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserBounds",
                "[D"));
        constructor.instructions.insertBefore(constructorReturn, constructorCode);

        MethodNode method = find(node, "distance", "([J)J");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserBounds",
                "[D"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClimate",
                "distance",
                "([D[J)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 2, 2);
        writeComputeFrames(node, output);
    }

    private static void patchChunkGeneratorBrowserYield(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/ChunkGenerator.class");
        MethodNode method = find(
                node,
                "applyBiomeDecoration",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/StructureManager;)V");
        requireWorldgenLoopPulses("ChunkGenerator.applyBiomeDecoration", method);
        write(node, output);
    }

    private static void patchWorldCarverBrowserYield(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/carver/WorldCarver.class");
        MethodNode method = find(
                node,
                "carveEllipsoid",
                "(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
                        + "Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer;DDDDD"
                        + "Lnet/minecraft/world/level/chunk/CarvingMask;"
                        + "Lnet/minecraft/world/level/levelgen/carver/WorldCarver$CarveSkipChecker;)Z");
        requireWorldgenLoopPulses("WorldCarver.carveEllipsoid", method);
        write(node, output);
    }

    private static void patchLightEngineBrowserYield(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/lighting/LightEngine.class");
        MethodNode increases = find(node, "propagateIncreases", "()I");
        MethodNode decreases = find(node, "propagateDecreases", "()I");
        requireWorldgenLoopPulses("LightEngine.propagateIncreases", increases);
        requireWorldgenLoopPulses("LightEngine.propagateDecreases", decreases);
        write(node, output);
    }

    private static void patchLevelChunkSectionBrowserBiomeYield(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/LevelChunkSection.class");
        MethodNode method = find(
                node,
                "fillBiomesFromNoise",
                "(Lnet/minecraft/world/level/biome/BiomeResolver;"
                        + "Lnet/minecraft/world/level/biome/Climate$Sampler;III)V");
        int checkpoints = insertPulseAfterLoopCounter(method, 9, 1);
        if (checkpoints != 1) {
            throw new IllegalStateException(
                    "LevelChunkSection browser biome yield point was not found: " + checkpoints);
        }
        write(node, output);
    }

    private static void patchChunkGenerationTaskBrowserYield(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkGenerationTask.class");
        MethodNode method = find(node, "runUntilWait", "()Ljava/util/concurrent/CompletableFuture;");
        int checkpoints = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/level/ChunkGenerationTask")
                    && call.name.equals("scheduleNextLayer")
                    && call.desc.equals("()V")) {
                method.instructions.insertBefore(instruction, browserWorldgenCheckpoint());
                checkpoints++;
            }
        }
        if (checkpoints != 1) {
            throw new IllegalStateException(
                    "ChunkGenerationTask browser yield point was not found: " + checkpoints);
        }
        write(node, output);
    }

    private static int insertPulseAfterLoopCounter(
            MethodNode method, int localVariable, int increment) {
        int checkpoints = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof IincInsnNode counter
                    && counter.var == localVariable
                    && counter.incr == increment
                    && nextRealInstruction(instruction) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                method.instructions.insert(instruction, browserWorldgenPulse());
                checkpoints++;
            }
        }
        return checkpoints;
    }

    private static MethodInsnNode browserWorldgenCheckpoint() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "checkpoint",
                "()V",
                false);
    }

    private static MethodInsnNode browserDistanceRamp() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "advanceConfiguredDistances",
                "()V",
                false);
    }

    private static void requireWorldgenLoopPulses(String label, MethodNode method) {
        int pulses = insertWorldgenPulseOnLoopBackedges(method);
        if (pulses == 0) {
            throw new IllegalStateException(label + " browser loop backedges were not found");
        }
    }

    private static int insertWorldgenPulseOnLoopBackedges(MethodNode method) {
        int pulses = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label)
                            < method.instructions.indexOf(instruction)) {
                method.instructions.insertBefore(instruction, browserWorldgenPulse());
                pulses++;
            }
        }
        return pulses;
    }

    private static MethodInsnNode browserWorldgenPulse() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "pulse",
                "()V",
                false);
    }

    private static void patchRegionFileVersionBrowserNoCompression(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/chunk/storage/RegionFileVersion";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "getSelected", "()L" + owner + ";");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                owner,
                "VERSION_NONE",
                "L" + owner + ";"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    private static void patchSimpleBitStorageBrowserUnpack(String jar, Path output) throws IOException {
        String owner = "net/minecraft/util/SimpleBitStorage";
        ClassNode node = read(jar, owner + ".class");
        MethodNode get = find(node, "get", "(I)I");
        InsnList getCode = new InsnList();
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        getCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        getCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "get",
                "([JIII)I",
                false));
        getCode.add(new InsnNode(Opcodes.IRETURN));
        replace(get, getCode, 4, 2);

        MethodNode getAndSet = find(node, "getAndSet", "(II)I");
        InsnList getAndSetCode = new InsnList();
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        getAndSetCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        getAndSetCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        getAndSetCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        getAndSetCode.add(new InsnNode(Opcodes.IRETURN));
        replace(getAndSet, getAndSetCode, 5, 3);

        MethodNode set = find(node, "set", "(II)V");
        InsnList setCode = new InsnList();
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        setCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        setCode.add(new InsnNode(Opcodes.POP));
        setCode.add(new InsnNode(Opcodes.RETURN));
        replace(set, setCode, 5, 3);

        MethodNode method = find(node, "unpack", "([I)V");
        LabelNode fallback = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "size", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "unpack",
                "([J[IIII)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(fallback);
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 5);
        writeComputeFrames(node, output);
    }

    private static void patchFriendlyByteBufBrowserLongArray(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/FriendlyByteBuf.class");
        MethodNode method = find(node, "readFixedSizeLongArray",
                "(Lio/netty/buffer/ByteBuf;[J)[J");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserLongArrayCodec",
                "readFixedSizeLongArray",
                "(Lio/netty/buffer/ByteBuf;[J)[J",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 2);
        write(node, output);
    }

    private static void patchProtoChunkBrowserHeightmapCache(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/chunk/ProtoChunk";
        String chunkAccess = "net/minecraft/world/level/chunk/ChunkAccess";
        String levelHeightAccessor = "net/minecraft/world/level/LevelHeightAccessor";
        String section = "net/minecraft/world/level/chunk/LevelChunkSection";
        String blockPos = "net/minecraft/core/BlockPos";
        String blockState = "net/minecraft/world/level/block/state/BlockState";
        String fluidState = "net/minecraft/world/level/material/FluidState";
        String chunkStatus = "net/minecraft/world/level/chunk/status/ChunkStatus";
        String heightmap = "net/minecraft/world/level/levelgen/Heightmap";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMaxY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinSectionY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserSections",
                "[L" + section + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserHeightmaps",
                "[L" + heightmap + ";",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/chunk/UpgradeData;"
                        + "[L" + section + ";"
                        + "Lnet/minecraft/world/ticks/ProtoChunkTicks;"
                        + "Lnet/minecraft/world/ticks/ProtoChunkTicks;"
                        + "L" + levelHeightAccessor + ";"
                        + "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;"
                        + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("ProtoChunk constructor return was not found");
        }
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMinY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMinY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMaxY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMaxY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMinSectionY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMinSectionY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, chunkAccess, "sections", "[L" + section + ";"));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserSections", "[L" + section + ";"));
        constructor.instructions.insertBefore(constructorReturn, constructorCode);

        String blockPosDescriptor = "L" + blockPos + ";";
        String blockStateDescriptor = "L" + blockState + ";";
        String sectionArrayDescriptor = "[L" + section + ";";
        MethodNode getBlockState = find(
                node,
                "getBlockState",
                "(" + blockPosDescriptor + ")" + blockStateDescriptor);
        LabelNode blockOutside = new LabelNode();
        LabelNode blockNotEmpty = new LabelNode();
        InsnList getBlockStateCode = new InsnList();
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPLT, blockOutside));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPGT, blockOutside));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new InsnNode(Opcodes.ICONST_4));
        getBlockStateCode.add(new InsnNode(Opcodes.ISHR));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        getBlockStateCode.add(new InsnNode(Opcodes.ISUB));
        getBlockStateCode.add(new InsnNode(Opcodes.AALOAD));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, section, "hasOnlyAir", "()Z", false));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IFEQ, blockNotEmpty));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        getBlockStateCode.add(blockNotEmpty);
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                section,
                "getBlockState",
                "(III)" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        getBlockStateCode.add(blockOutside);
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "VOID_AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(getBlockState, getBlockStateCode, 4, 4);

        MethodNode getFluidState = find(
                node,
                "getFluidState",
                "(" + blockPosDescriptor + ")L" + fluidState + ";");
        LabelNode fluidEmpty = new LabelNode();
        LabelNode fluidNotEmpty = new LabelNode();
        InsnList getFluidStateCode = new InsnList();
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPLT, fluidEmpty));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPGT, fluidEmpty));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new InsnNode(Opcodes.ICONST_4));
        getFluidStateCode.add(new InsnNode(Opcodes.ISHR));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        getFluidStateCode.add(new InsnNode(Opcodes.ISUB));
        getFluidStateCode.add(new InsnNode(Opcodes.AALOAD));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, section, "hasOnlyAir", "()Z", false));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IFEQ, fluidNotEmpty));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.GOTO, fluidEmpty));
        getFluidStateCode.add(fluidNotEmpty);
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                section,
                "getFluidState",
                "(III)L" + fluidState + ";",
                false));
        getFluidStateCode.add(new InsnNode(Opcodes.ARETURN));
        getFluidStateCode.add(fluidEmpty);
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/material/Fluids",
                "EMPTY",
                "Lnet/minecraft/world/level/material/Fluid;"));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/material/Fluid",
                "defaultFluidState",
                "()L" + fluidState + ";",
                false));
        getFluidStateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(getFluidState, getFluidStateCode, 4, 4);

        MethodNode method = find(
                node,
                "setBlockState",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                        + "Lnet/minecraft/world/level/block/state/BlockState;");
        MethodInsnNode firstHasOnlyAir = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(section)
                    && call.name.equals("hasOnlyAir")
                    && call.desc.equals("()Z")) {
                firstHasOnlyAir = call;
                break;
            }
        }
        if (firstHasOnlyAir == null) {
            throw new IllegalStateException("ProtoChunk setBlockState section prefix was not found");
        }
        AbstractInsnNode sectionReceiver = previousOpcode(firstHasOnlyAir);
        if (!(sectionReceiver instanceof VarInsnNode receiverLoad)
                || receiverLoad.getOpcode() != Opcodes.ALOAD
                || receiverLoad.var != 8) {
            throw new IllegalStateException("ProtoChunk setBlockState section receiver changed");
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != sectionReceiver;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }
        LabelNode writeOutside = new LabelNode();
        LabelNode writeSectionReady = new LabelNode();
        InsnList writePrefix = new InsnList();
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 4));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 6));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        writePrefix.add(new JumpInsnNode(Opcodes.IF_ICMPLT, writeOutside));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        writePrefix.add(new JumpInsnNode(Opcodes.IF_ICMPGT, writeOutside));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new InsnNode(Opcodes.ICONST_4));
        writePrefix.add(new InsnNode(Opcodes.ISHR));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        writePrefix.add(new InsnNode(Opcodes.ISUB));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 7));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 7));
        writePrefix.add(new InsnNode(Opcodes.AALOAD));
        writePrefix.add(new VarInsnNode(Opcodes.ASTORE, 8));
        writePrefix.add(new JumpInsnNode(Opcodes.GOTO, writeSectionReady));
        writePrefix.add(writeOutside);
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "VOID_AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        writePrefix.add(new InsnNode(Opcodes.ARETURN));
        writePrefix.add(writeSectionReady);
        method.instructions.insertBefore(sectionReceiver, writePrefix);

        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/core/SectionPos")
                    && call.name.equals("sectionRelative")
                    && call.desc.equals("(I)I")) {
                InsnList relative = new InsnList();
                relative.add(new IntInsnNode(Opcodes.BIPUSH, 15));
                relative.add(new InsnNode(Opcodes.IAND));
                method.instructions.insertBefore(call, relative);
                method.instructions.remove(call);
            }
            instruction = next;
        }
        MethodInsnNode getPersistedStatus = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("getPersistedStatus")
                    && call.desc.equals("()L" + chunkStatus + ";")) {
                getPersistedStatus = call;
                break;
            }
        }
        if (getPersistedStatus == null) {
            throw new IllegalStateException("ProtoChunk heightmap update tail was not found");
        }

        AbstractInsnNode tailStart = previousOpcode(getPersistedStatus);
        if (!(tailStart instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("ProtoChunk heightmap update tail start changed");
        }
        for (AbstractInsnNode instruction = tailStart; instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }

        LabelNode cacheReady = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "getPersistedStatus",
                "()L" + chunkStatus + ";",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 14));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, cacheReady));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                chunkStatus,
                "heightmapsAfter",
                "()Ljava/util/EnumSet;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserProtoChunk",
                "prepareHeightmaps",
                "(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/EnumSet;)"
                        + "[L" + heightmap + ";",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserHeightmaps",
                "[L" + heightmap + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";"));

        code.add(cacheReady);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserHeightmaps",
                "[L" + heightmap + ";"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 10));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserProtoChunk",
                "updateHeightmaps",
                "([L" + heightmap + ";IIILnet/minecraft/world/level/block/state/BlockState;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 13));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(code);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxLocals = Math.max(method.maxLocals, 15);
        writeComputeFrames(node, output);
    }

    private static void patchHeightmapBrowserStorage(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/Heightmap";
        String simpleStorage = "net/minecraft/util/SimpleBitStorage";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserData",
                "[J",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBits",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserValuesPerLong",
                "I",
                null,
                null));
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/Heightmap$Types;)V");
        int initialized = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList initialize = new InsnList();
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
                initialize.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/world/level/chunk/ChunkAccess",
                        "getMinY",
                        "()I",
                        false));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserMinY", "I"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new FieldInsnNode(
                        Opcodes.GETFIELD, owner, "data", "Lnet/minecraft/util/BitStorage;"));
                initialize.add(new TypeInsnNode(Opcodes.CHECKCAST, simpleStorage));
                initialize.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, simpleStorage, "getRaw", "()[J", false));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserData", "[J"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ILOAD, 3));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserBits", "I"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new IntInsnNode(Opcodes.BIPUSH, 64));
                initialize.add(new VarInsnNode(Opcodes.ILOAD, 3));
                initialize.add(new InsnNode(Opcodes.IDIV));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserValuesPerLong", "I"));
                constructor.instructions.insertBefore(instruction, initialize);
                initialized++;
            }
        }
        if (initialized != 1) {
            throw new IllegalStateException(
                    "Heightmap browser minY initialization mismatch: " + initialized);
        }
        constructor.maxStack = Math.max(constructor.maxStack, 4);

        MethodNode indexedGet = find(node, "getFirstAvailable", "(I)I");
        InsnList indexedGetCode = new InsnList();
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserData", "[J"));
        indexedGetCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBits", "I"));
        indexedGetCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "get",
                "([JIII)I",
                false));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        indexedGetCode.add(new InsnNode(Opcodes.IADD));
        indexedGetCode.add(new InsnNode(Opcodes.IRETURN));
        replace(indexedGet, indexedGetCode, 4, 2);

        for (String name : new String[] {"getFirstAvailable", "getHighestTaken"}) {
            MethodNode method = find(node, name, "(II)I");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserData", "[J"));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new IntInsnNode(Opcodes.BIPUSH, 16));
            code.add(new InsnNode(Opcodes.IMUL));
            code.add(new InsnNode(Opcodes.IADD));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBits", "I"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserBitStorage",
                    "get",
                    "([JIII)I",
                    false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserMinY", "I"));
            code.add(new InsnNode(Opcodes.IADD));
            if (name.equals("getHighestTaken")) {
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.ISUB));
            }
            code.add(new InsnNode(Opcodes.IRETURN));
            replace(method, code, 4, 3);
        }

        MethodNode setHeight = find(node, "setHeight", "(III)V");
        InsnList setCode = new InsnList();
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserData", "[J"));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        setCode.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        setCode.add(new InsnNode(Opcodes.IMUL));
        setCode.add(new InsnNode(Opcodes.IADD));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        setCode.add(new InsnNode(Opcodes.ISUB));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBits", "I"));
        setCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        setCode.add(new InsnNode(Opcodes.POP));
        setCode.add(new InsnNode(Opcodes.RETURN));
        replace(setHeight, setCode, 5, 4);
        writeComputeFrames(node, output);
    }

    private static void patchBufferBuilderBrowserFastVertex(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/BufferBuilder";
        ClassNode node = read(jar, owner + ".class");
        patchBufferBuilderBrowserGuiWriters(node, owner);
        MethodNode method = find(node, "addVertex", "(FFFIFFIIFFF)V");
        LabelNode fallback = new LabelNode();
        InsnList code = new InsnList();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fastFormat", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.FLOAD, 9));
        code.add(new VarInsnNode(Opcodes.FLOAD, 10));
        code.add(new VarInsnNode(Opcodes.FLOAD, 11));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fullFormat", "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFastVertex",
                "(JFFFIFFIIFFFZ)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));

        code.add(fallback);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.FLOAD, 9));
        code.add(new VarInsnNode(Opcodes.FLOAD, 10));
        code.add(new VarInsnNode(Opcodes.FLOAD, 11));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/blaze3d/vertex/VertexConsumer",
                "addVertex",
                "(FFFIFFIIFFF)V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));

        replace(method, code, 16, 12);
        writeComputeFrames(node, output);
    }

    private static void patchByteBufferBuilderBrowserReserve(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "reserve", "(I)J");
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"java/lang/Math".equals(call.owner)
                    || !"addExact".equals(call.name)
                    || !"(JJ)J".equals(call.desc)) {
                continue;
            }
            method.instructions.set(call, new InsnNode(Opcodes.LADD));
            replacements++;
        }
        if (replacements != 2) {
            throw new IllegalStateException(
                    "ByteBufferBuilder.reserve expected 2 Math.addExact calls, found " + replacements);
        }
        writeComputeFrames(node, output);
    }

    private static void patchCompiledSectionMeshBrowserVertexBufferReuse(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/CompiledSectionMesh";
        String meshOwner = "com/mojang/blaze3d/vertex/MeshData";
        String helperOwner = "dev/gaius/browser/BrowserMeshUpload";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "uploadMeshLayer",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lcom/mojang/blaze3d/vertex/MeshData;J)V");

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 2));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                helperOwner,
                "begin",
                "(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                false));
        method.instructions.insert(begin);

        int vertexBufferCalls = 0;
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && meshOwner.equals(call.owner)
                    && "vertexBuffer".equals(call.name)
                    && "()Ljava/nio/ByteBuffer;".equals(call.desc)) {
                method.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        helperOwner,
                        "vertexBuffer",
                        "(Lcom/mojang/blaze3d/vertex/MeshData;)Ljava/nio/ByteBuffer;",
                        false));
                vertexBufferCalls++;
            } else if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList end = new InsnList();
                end.add(new VarInsnNode(Opcodes.ALOAD, 2));
                end.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        helperOwner,
                        "end",
                        "(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                        false));
                method.instructions.insertBefore(instruction, end);
                returns++;
            }
        }
        if (vertexBufferCalls != 4 || returns != 1) {
            throw new IllegalStateException(
                    "CompiledSectionMesh.uploadMeshLayer expected 4 vertexBuffer calls and 1 return, found "
                            + vertexBufferCalls + " and " + returns);
        }
        writeComputeFrames(node, output);
    }

    private static void patchBufferBuilderBrowserGuiWriters(ClassNode node, String owner) {
        patchBufferBuilderFloatPosition(node, owner);
        patchBufferBuilderMatrixPosition(node, owner);
        patchBufferBuilderSetColor(node, owner);
        patchBufferBuilderSetUv(node, owner);
        patchBufferBuilderSetOverlayOrLight(node, owner, "setOverlay");
        patchBufferBuilderSetOverlayOrLight(node, owner, "setLight");
        patchBufferBuilderUvShort(node, owner);
        patchBufferBuilderSetNormal(node, owner);
    }

    private static void patchBufferBuilderFloatPosition(ClassNode node, String owner) {
        MethodNode method = find(node, "addVertex", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPosition",
                "(JFFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 4);
    }

    private static void patchBufferBuilderMatrixPosition(ClassNode node, String owner) {
        MethodNode method = findOrCreateMethod(
                node,
                Opcodes.ACC_PUBLIC,
                "addVertex",
                "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putTransformedPosition",
                "(JLorg/joml/Matrix4fc;FFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 7, 5);
    }

    private static InsnList beginBufferBuilderVertexPosition(String owner) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "offsetsByElement",
                "[I"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/vertex/VertexFormatElement",
                "POSITION",
                "Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/vertex/VertexFormatElement",
                "id",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.IALOAD));
        code.add(new InsnNode(Opcodes.I2L));
        code.add(new InsnNode(Opcodes.LADD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "initialElementsToFill",
                "I"));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "elementsToFill",
                "I"));
        return code;
    }

    private static void patchBufferBuilderSetColor(ClassNode node, String owner) {
        MethodNode method = find(node, "setColor", "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderElement(owner, "COLOR");
        LabelNode done = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putRgba",
                "(JI)V",
                false));
        LabelNode ret = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.GOTO, ret));
        code.add(done);
        code.add(new InsnNode(Opcodes.POP2));
        code.add(ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 2);
    }

    private static void patchBufferBuilderSetUv(ClassNode node, String owner) {
        MethodNode method = find(node, "setUv", "(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderElement(owner, "UV0");
        LabelNode done = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFloatPair",
                "(JFF)V",
                false));
        LabelNode ret = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.GOTO, ret));
        code.add(done);
        code.add(new InsnNode(Opcodes.POP2));
        code.add(ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 3);
    }

    private static void patchBufferBuilderSetOverlayOrLight(ClassNode node, String owner, String methodName) {
        MethodNode method = find(node, methodName, "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        String element = methodName.equals("setOverlay") ? "UV1" : "UV2";
        InsnList code = beginBufferBuilderElement(owner, element);
        LabelNode done = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPackedUv",
                "(JI)V",
                false));
        LabelNode ret = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.GOTO, ret));
        code.add(done);
        code.add(new InsnNode(Opcodes.POP2));
        code.add(ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 2);
    }

    private static void patchBufferBuilderUvShort(ClassNode node, String owner) {
        MethodNode method = find(node, "uvShort",
                "(SSLcom/mojang/blaze3d/vertex/VertexFormatElement;)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginElement",
                "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)J",
                false));
        LabelNode done = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putShortPair",
                "(JSS)V",
                false));
        LabelNode ret = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.GOTO, ret));
        code.add(done);
        code.add(new InsnNode(Opcodes.POP2));
        code.add(ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 4);
    }

    private static void patchBufferBuilderSetNormal(ClassNode node, String owner) {
        MethodNode method = find(node, "setNormal", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderElement(owner, "NORMAL");
        LabelNode done = new LabelNode();
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putNormal",
                "(JFFF)V",
                false));
        LabelNode ret = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.GOTO, ret));
        code.add(done);
        code.add(new InsnNode(Opcodes.POP2));
        code.add(ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 4);
    }

    private static InsnList beginBufferBuilderElement(String owner, String elementName) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/vertex/VertexFormatElement",
                elementName,
                "Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginElement",
                "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)J",
                false));
        return code;
    }

    private static MethodNode findOrCreateMethod(ClassNode node, int access, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        MethodNode method = new MethodNode(access, name, desc, null, null);
        node.methods.add(method);
        return method;
    }

    private static void patchPersistentEntityUuidBrowserRecovery(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/entity/PersistentEntitySectionManager.class");
        MethodNode method = find(node, "addEntityUuid",
                "(Lnet/minecraft/world/level/entity/EntityAccess;)Z");
        LabelNode duplicate = new LabelNode();
        LabelNode retryLoop = new LabelNode();
        LabelNode retryNext = new LabelNode();
        LabelNode warn = new LabelNode();
        LabelNode recovered = new LabelNode();

        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "knownUuids",
                "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/entity/EntityAccess",
                "getUUID",
                "()Ljava/util/UUID;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, duplicate));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));

        code.add(duplicate);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, "net/minecraft/world/entity/Entity"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, warn));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/entity/Entity"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));

        code.add(retryLoop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, warn));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Mth",
                "createInsecureUUID",
                "()Ljava/util/UUID;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/Entity",
                "setUUID",
                "(Ljava/util/UUID;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "knownUuids",
                "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/entity/EntityAccess",
                "getUUID",
                "()Ljava/util/UUID;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFNE, recovered));
        code.add(retryNext);
        code.add(new IincInsnNode(3, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, retryLoop));

        code.add(recovered);
        code.add(minecraftEvent("server.entityUuidRecovered"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));

        code.add(warn);
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                node.name,
                "LOGGER",
                "Lorg/slf4j/Logger;"));
        code.add(new LdcInsnNode("UUID of added entity already exists: {}"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/slf4j/Logger",
                "warn",
                "(Ljava/lang/String;Ljava/lang/Object;)V",
                true));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));

        replace(method, code, 3, 4);
        writeComputeFrames(node, output);
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

    private static void patchFilePackResourcesBrowserAtlasOverlays(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/server/packs/FilePackResources$FileResourcesSupplier.class");
        MethodNode openFull = find(node, "openFull",
                "(Lnet/minecraft/server/packs/PackLocationInfo;"
                        + "Lnet/minecraft/server/packs/repository/Pack$Metadata;)"
                        + "Lnet/minecraft/server/packs/PackResources;");
        boolean patched = false;
        for (AbstractInsnNode instruction = openFull.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/server/packs/repository/Pack$Metadata")
                    || !call.name.equals("overlays")
                    || !call.desc.equals("()Ljava/util/List;")) {
                continue;
            }
            AbstractInsnNode store = nextOpcode(instruction);
            if (!(store instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ASTORE) {
                throw new IllegalStateException("File pack overlay list was not stored after lookup");
            }
            InsnList compatibility = new InsnList();
            compatibility.add(new VarInsnNode(Opcodes.ALOAD, 0));
            compatibility.add(new FieldInsnNode(
                    Opcodes.GETFIELD, node.name, "content", "Ljava/io/File;"));
            compatibility.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
            compatibility.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPackOverlayCompat",
                    "mergeSafeAtlasOverlays",
                    "(Ljava/io/File;Ljava/util/List;)Ljava/util/List;",
                    false));
            compatibility.add(new VarInsnNode(Opcodes.ASTORE, variable.var));
            openFull.instructions.insert(store, compatibility);
            patched = true;
            break;
        }
        if (!patched) {
            throw new IllegalStateException("File pack overlay metadata lookup was not found");
        }
        openFull.maxStack = Math.max(openFull.maxStack, 2);
        write(node, output);
    }

    private static void patchSingleFileBrowserAtlasFallback(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/texture/atlas/sources/SingleFile.class");
        MethodNode run = find(node, "run",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                        + "Lnet/minecraft/client/renderer/texture/atlas/SpriteSource$Output;)V");
        boolean patched = false;
        for (AbstractInsnNode instruction = run.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/server/packs/resources/ResourceManager")
                    || !call.name.equals("getResource")
                    || !call.desc.equals("(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserAtlasResourceFallback";
            call.name = "getResource";
            call.desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;";
            call.itf = false;
            patched = true;
            break;
        }
        if (!patched) {
            throw new IllegalStateException("SingleFile atlas resource lookup was not found");
        }
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
        boolean patchedRunServerTickYield = false;
        boolean patchedRunServerDistanceRamp = false;
        boolean patchedRunServerStopDiagnostics = false;
        boolean patchedRunServerBrowserCatchupReset = false;
        boolean patchedSpinRegistration = false;
        boolean patchedSaveBeforeWorldInitialization = false;
        boolean patchedBrowserPacketPump = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("spin")
                    && method.desc.equals("(Ljava/util/function/Function;)"
                            + "Lnet/minecraft/server/MinecraftServer;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals("java/util/concurrent/atomic/AtomicReference")
                            && call.name.equals("set")
                            && call.desc.equals("(Ljava/lang/Object;)V")) {
                        InsnList registration = new InsnList();
                        registration.add(new VarInsnNode(Opcodes.ALOAD, 3));
                        registration.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "dev/gaius/browser/BrowserIntegratedServerMain",
                                "registerServer",
                                "(Lnet/minecraft/server/MinecraftServer;)V",
                                false));
                        method.instructions.insert(call, registration);
                        method.maxStack = Math.max(method.maxStack, 2);
                        patchedSpinRegistration = true;
                        break;
                    }
                }
            } else if (method.name.equals("dumpThreads")
                    && method.desc.equals("(Ljava/nio/file/Path;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
            } else if (method.name.equals("saveAllChunks")
                    && method.desc.equals("(ZZZ)Z")) {
                LabelNode worldInitialized = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "overworld",
                        "()Lnet/minecraft/server/level/ServerLevel;",
                        false));
                guard.add(new JumpInsnNode(Opcodes.IFNONNULL, worldInitialized));
                guard.add(new InsnNode(Opcodes.ICONST_0));
                guard.add(new InsnNode(Opcodes.IRETURN));
                guard.add(worldInitialized);
                method.instructions.insert(guard);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedSaveBeforeWorldInitialization = true;
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
            } else if (method.name.equals("processPacketsAndTick")
                    && method.desc.equals("(Z)V")) {
                InsnList browserPackets = new InsnList();
                browserPackets.add(pumpBrowserChannels());
                method.instructions.insert(browserPackets);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedBrowserPacketPump = true;
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
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals(owner)
                            && call.name.equals("processPacketsAndTick")
                            && call.desc.equals("(Z)V")) {
                        method.instructions.insertBefore(instruction, browserWorldgenCheckpoint());
                        method.instructions.insert(instruction, browserDistanceRamp());
                        patchedRunServerTickYield = true;
                        patchedRunServerDistanceRamp = true;
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
                || !patchedRunServerTickYield
                || !patchedRunServerDistanceRamp
                || !patchedRunServerStopDiagnostics
                || !patchedRunServerBrowserCatchupReset
                || !patchedSpinRegistration
                || !patchedSaveBeforeWorldInitialization
                || !patchedBrowserPacketPump) {
            throw new IllegalStateException("MinecraftServer browser patch points were not found");
        }
        writeComputeFrames(node, output);
    }

    private static void patchPlayerSpawnFinderBrowser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/PlayerSpawnFinder.class");
        MethodNode findSpawn = find(node, "findSpawn",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/CompletableFuture",
                "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(findSpawn, code, 1, 2);
        write(node, output);
    }

    private static void patchPrepareSpawnTaskBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/network/config/PrepareSpawnTask$Preparing";
        ClassNode node = read(jar, owner + ".class");
        MethodNode loadSpawnChunks = find(node, "lambda$tick$0",
                "(Lnet/minecraft/world/level/ChunkPos;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "spawnLevel",
                "Lnet/minecraft/server/level/ServerLevel;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getChunkSource",
                "()Lnet/minecraft/server/level/ServerChunkCache;",
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/TicketType",
                "PLAYER_SPAWN",
                "Lnet/minecraft/server/level/TicketType;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // Keep the eight neighbors alive for the center chunk's lighting, but
        // complete configuration as soon as the center chunk itself is full.
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "addTicketWithRadius",
                "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/world/level/ChunkPos",
                "x",
                "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/world/level/ChunkPos",
                "z",
                "I"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/chunk/status/ChunkStatus",
                "FULL",
                "Lnet/minecraft/world/level/chunk/status/ChunkStatus;"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "getChunkFuture",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "chunkLoadFuture",
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(loadSpawnChunks, code, 6, 2);
        write(node, output);
    }

    private static void patchServerPlayerGameModeBrowserWorker(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/level/ServerPlayerGameMode";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserDestroyStartMillis",
                "J",
                null,
                null));
        MethodNode handle = find(
                node,
                "handleBlockBreakAction",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
                        + "Lnet/minecraft/core/Direction;II)V");
        int startMarkers = 0;
        int stopAdjustments = 0;
        int stopCompletions = 0;
        for (var instruction = handle.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)) {
                continue;
            }
            if (field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("destroyProgressStart")
                    && field.desc.equals("I")) {
                InsnList markStart = new InsnList();
                markStart.add(new VarInsnNode(Opcodes.ALOAD, 0));
                markStart.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "currentTimeMillis",
                        "()J",
                        false));
                markStart.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        owner,
                        "browserDestroyStartMillis",
                        "J"));
                handle.instructions.insert(instruction, markStart);
                startMarkers++;
            } else if (field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("destroyProgressStart")
                    && field.desc.equals("I")) {
                AbstractInsnNode subtract = nextRealInstruction(instruction);
                AbstractInsnNode store = nextRealInstruction(subtract);
                if (subtract == null || subtract.getOpcode() != Opcodes.ISUB
                        || !(store instanceof VarInsnNode variable)
                        || store.getOpcode() != Opcodes.ISTORE) {
                    continue;
                }
                InsnList adjust = new InsnList();
                adjust.add(new VarInsnNode(Opcodes.ILOAD, variable.var));
                adjust.add(new VarInsnNode(Opcodes.ALOAD, 0));
                adjust.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "browserDestroyStartMillis",
                        "J"));
                adjust.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "adjustDestroyTicks",
                        "(IJ)I",
                        false));
                adjust.add(new VarInsnNode(Opcodes.ISTORE, variable.var));
                handle.instructions.insert(store, adjust);
                stopAdjustments++;
            }
        }
        for (var instruction = handle.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/world/level/block/state/BlockState")
                    || !call.name.equals("getDestroyProgress")
                    || !call.desc.equals("(Lnet/minecraft/world/entity/player/Player;"
                            + "Lnet/minecraft/world/level/BlockGetter;"
                            + "Lnet/minecraft/core/BlockPos;)F")) {
                continue;
            }
            AbstractInsnNode loadTicks = nextRealInstruction(instruction);
            AbstractInsnNode addOne = nextRealInstruction(loadTicks);
            AbstractInsnNode add = nextRealInstruction(addOne);
            AbstractInsnNode toFloat = nextRealInstruction(add);
            AbstractInsnNode multiply = nextRealInstruction(toFloat);
            if (!(loadTicks instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD
                    || addOne == null
                    || addOne.getOpcode() != Opcodes.ICONST_1
                    || add == null
                    || add.getOpcode() != Opcodes.IADD
                    || toFloat == null
                    || toFloat.getOpcode() != Opcodes.I2F
                    || multiply == null
                    || multiply.getOpcode() != Opcodes.FMUL) {
                continue;
            }
            handle.instructions.insert(multiply, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "completeLocalDestroyProgress",
                    "(F)F",
                    false));
            stopCompletions++;
        }
        if (startMarkers != 1 || stopAdjustments != 1 || stopCompletions != 1) {
            throw new IllegalStateException(
                    "ServerPlayerGameMode wall-clock break patch points changed: "
                            + startMarkers + "/" + stopAdjustments + "/" + stopCompletions);
        }
        handle.maxStack = Math.max(handle.maxStack, 3);
        writeComputeFrames(node, output);
    }

    private static void patchServerCommonPacketListenerBrowserWorker(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/network/ServerCommonPacketListenerImpl";
        ClassNode node = read(jar, owner + ".class");
        MethodNode isSingleplayerOwner = find(node, "isSingleplayerOwner", "()Z");
        LabelNode vanillaCheck = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerServer",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanillaCheck));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(vanillaCheck);
        isSingleplayerOwner.instructions.insert(code);
        isSingleplayerOwner.maxStack = Math.max(isSingleplayerOwner.maxStack, 1);
        writeComputeFrames(node, output);
    }

    private static void patchServerGamePacketListenerBrowserWorker(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/network/ServerGamePacketListenerImpl";
        ClassNode node = read(jar, owner + ".class");
        MethodNode chunkBatch = find(
                node,
                "handleChunkBatchReceived",
                "(Lnet/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket;)V");
        int activations = 0;
        for (var instruction = chunkBatch.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList activate = new InsnList();
                activate.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "activateConfiguredDistances",
                        "()V",
                        false));
                chunkBatch.instructions.insertBefore(instruction, activate);
                activations++;
            }
        }
        if (activations != 1) {
            throw new IllegalStateException(
                    "ServerGamePacketListenerImpl chunk-batch activation point changed: "
                            + activations);
        }
        chunkBatch.maxStack = Math.max(chunkBatch.maxStack, 1);
        writeComputeFrames(node, output);
    }

    private static void replaceInitialSpawnForBrowser(MethodNode method) {
        InsnList code = new InsnList();

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

        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/BlockPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockX",
                "()I",
                false));
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
                "getGenerator",
                "()Lnet/minecraft/world/level/chunk/ChunkGenerator;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockZ",
                "()I",
                false));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/levelgen/Heightmap$Types",
                "MOTION_BLOCKING_NO_LEAVES",
                "Lnet/minecraft/world/level/levelgen/Heightmap$Types;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
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
                "net/minecraft/world/level/chunk/ChunkGenerator",
                "getBaseHeight",
                "(IILnet/minecraft/world/level/levelgen/Heightmap$Types;"
                        + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockZ",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/core/BlockPos",
                "<init>",
                "(III)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

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
        replace(method, code, 9, 7);
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

    private static void patchSkinTextureDownloader(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/texture/SkinTextureDownloader.class");
        MethodNode downloadSkin = find(
                node,
                "downloadSkin",
                "(Ljava/nio/file/Path;Ljava/lang/String;)"
                        + "Lcom/mojang/blaze3d/platform/NativeImage;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserHttpProxy",
                "proxyTexture",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        downloadSkin.instructions.insert(code);
        downloadSkin.maxStack = Math.max(downloadSkin.maxStack, 1);
        boolean removedJavaProxy = false;
        for (var instruction = downloadSkin.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/net/URL")
                    && call.name.equals("openConnection")
                    && call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                downloadSkin.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                removedJavaProxy = true;
            }
        }
        if (!removedJavaProxy) {
            throw new IllegalStateException(
                    "SkinTextureDownloader browser Java Proxy patch point was not found");
        }
        write(node, output);
    }

    private static void patchHttpUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/HttpUtil.class");
        boolean patchedDownload = false;
        boolean removedJavaProxy = false;
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
            } else if (method.name.equals("downloadFile")
                    && method.desc.startsWith("(Ljava/nio/file/Path;Ljava/net/URL;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy",
                        "proxyResourcePack",
                        "(Ljava/net/URL;)Ljava/net/URL;",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 1));
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 1);
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !call.owner.equals("java/net/URL")
                            || !call.name.equals("openConnection")
                            || !call.desc.equals(
                                    "(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                        continue;
                    }
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                    call.desc = "()Ljava/net/URLConnection;";
                    removedJavaProxy = true;
                }
                patchedDownload = true;
            }
        }
        if (!patchedDownload || !removedJavaProxy) {
            throw new IllegalStateException(
                    "HttpUtil browser download patch points were not found: method="
                            + patchedDownload + " proxy=" + removedJavaProxy);
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
        ClassNode request = read(jar, "com/mojang/realmsclient/client/Request.class");
        MethodNode requestConstructor = find(request, "<init>", "(Ljava/lang/String;II)V");
        int proxiedUrls = 0;
        int proxyCalls = 0;
        for (AbstractInsnNode instruction = requestConstructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals("java/net/URL")
                    && call.name.equals("<init>")
                    && call.desc.equals("(Ljava/lang/String;)V")) {
                requestConstructor.instructions.insertBefore(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy",
                        "proxyRealms",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false));
                proxiedUrls++;
            } else if (call.owner.equals("java/net/URL")
                    && call.name.equals("openConnection")
                    && call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                requestConstructor.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                proxyCalls++;
            }
        }
        if (proxiedUrls != 2 || proxyCalls != 1) {
            throw new IllegalStateException(
                    "Realms Request URL patch points changed: urls=" + proxiedUrls
                            + " proxies=" + proxyCalls);
        }
        MethodNode cookie = find(
                request,
                "cookie",
                "(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;)V");
        InsnList cookieCode = new InsnList();
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cookieCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserHttpProxy",
                "addRealmsCookie",
                "(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;)V",
                false));
        cookieCode.add(new InsnNode(Opcodes.RETURN));
        replace(cookie, cookieCode, 3, 3);
        write(request, root.resolve("com/mojang/realmsclient/client/Request.class"));

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
        cipher.visitField(
                Opcodes.ACC_PRIVATE,
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;",
                null,
                null).visitEnd();
        cipher.visitField(
                Opcodes.ACC_PRIVATE,
                "transformation",
                "Ljava/lang/String;",
                null,
                null).visitEnd();
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
        getInstance.visitInsn(Opcodes.DUP);
        getInstance.visitVarInsn(Opcodes.ALOAD, 0);
        getInstance.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "transformation",
                "Ljava/lang/String;");
        getInstance.visitInsn(Opcodes.ARETURN);
        getInstance.visitMaxs(3, 1);
        getInstance.visitEnd();
        getter(
                cipher,
                "javax/crypto/Cipher",
                "getAlgorithm",
                "()Ljava/lang/String;",
                "transformation",
                "Ljava/lang/String;",
                Opcodes.ARETURN);

        var init = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "init", "(ILjava/security/Key;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "createAesCfb8",
                "(ILjava/security/Key;)Ldev/gaius/browser/BrowserAesCfb8;",
                false);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 3);
        init.visitEnd();

        var initWithParameters = cipher.visitMethod(
                Opcodes.ACC_PUBLIC,
                "init",
                "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V",
                null,
                null);
        initWithParameters.visitCode();
        initWithParameters.visitVarInsn(Opcodes.ALOAD, 0);
        initWithParameters.visitVarInsn(Opcodes.ILOAD, 1);
        initWithParameters.visitVarInsn(Opcodes.ALOAD, 2);
        initWithParameters.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "createAesCfb8",
                "(ILjava/security/Key;)Ldev/gaius/browser/BrowserAesCfb8;",
                false);
        initWithParameters.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        initWithParameters.visitInsn(Opcodes.RETURN);
        initWithParameters.visitMaxs(3, 4);
        initWithParameters.visitEnd();

        var doFinal = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "doFinal", "([B)[B", null, null);
        doFinal.visitCode();
        doFinal.visitVarInsn(Opcodes.ALOAD, 0);
        doFinal.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        doFinal.visitVarInsn(Opcodes.ALOAD, 1);
        doFinal.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([B)[B",
                false);
        doFinal.visitInsn(Opcodes.ARETURN);
        doFinal.visitMaxs(2, 2);
        doFinal.visitEnd();
        var getOutputSize = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "getOutputSize", "(I)I", null, null);
        getOutputSize.visitCode();
        getOutputSize.visitVarInsn(Opcodes.ALOAD, 0);
        getOutputSize.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        getOutputSize.visitVarInsn(Opcodes.ILOAD, 1);
        getOutputSize.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "getOutputSize",
                "(I)I",
                false);
        getOutputSize.visitInsn(Opcodes.IRETURN);
        getOutputSize.visitMaxs(2, 2);
        getOutputSize.visitEnd();
        var update = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[B)I", null, null);
        update.visitCode();
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        update.visitVarInsn(Opcodes.ALOAD, 1);
        update.visitVarInsn(Opcodes.ILOAD, 2);
        update.visitVarInsn(Opcodes.ILOAD, 3);
        update.visitVarInsn(Opcodes.ALOAD, 4);
        update.visitInsn(Opcodes.ICONST_0);
        update.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([BII[BI)I",
                false);
        update.visitInsn(Opcodes.IRETURN);
        update.visitMaxs(6, 5);
        update.visitEnd();
        var updateWithOffset = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[BI)I", null, null);
        updateWithOffset.visitCode();
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 0);
        updateWithOffset.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 1);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 2);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 3);
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 4);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 5);
        updateWithOffset.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([BII[BI)I",
                false);
        updateWithOffset.visitInsn(Opcodes.IRETURN);
        updateWithOffset.visitMaxs(6, 6);
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
        generateKey.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "generateSecretKey",
                "()Ljavax/crypto/SecretKey;",
                false);
        generateKey.visitInsn(Opcodes.ARETURN);
        generateKey.visitMaxs(1, 1);
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

    private static InsnList closeLevelLoadingScreenIfPresent() {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        code.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/gui/screens/LevelLoadingScreen"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/Minecraft",
                "screen",
                "Lnet/minecraft/client/gui/screens/Screen;"));
        code.add(minecraftEvent("client.levelReady.closeLoadingScreen"));
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
