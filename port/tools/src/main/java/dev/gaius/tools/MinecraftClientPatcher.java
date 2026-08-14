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
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Removes desktop-only diagnostics from the official browser client graph. */
public final class MinecraftClientPatcher {
    private static final double BROWSER_SECTION_QUEUE_DISTANCE_EPSILON = 1.0E-6D;
    private static final double BROWSER_SECTION_QUEUE_REBUILD_DISTANCE = 16.0D;
    private static final int BROWSER_SECTION_UPLOAD_BUDGET = 8;
    private static final int BROWSER_SECTION_CLOSE_BUDGET = 16;
    private static final int BROWSER_SECTION_VERTEX_HEAP_BYTES = 16 * 1024 * 1024;
    private static final int BROWSER_SECTION_INDEX_HEAP_BYTES = 4 * 1024 * 1024;
    private static final int BROWSER_SECTION_STAGING_BYTES = 16 * 1024 * 1024;
    private static final int BROWSER_GPU_RETIRE_SLOTS = 8;

    private MinecraftClientPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[1]);
        String minecraftVersion = args.length >= 3 ? args[2] : "1.21.11";
        patchNativeModuleLister(
                args[0], root.resolve("net/minecraft/util/NativeModuleLister.class"));
        patchJvmProfiler(
                args[0], root.resolve("net/minecraft/util/profiling/jfr/JvmProfiler.class"));
        patchEventLoopGroupHolder(
                args[0], root.resolve("net/minecraft/server/network/EventLoopGroupHolder.class"));
        patchServerMainBrowser(
                args[0], root.resolve("net/minecraft/server/Main.class"));
        patchWorldLoaderBrowserStartupTelemetry(
                args[0], root.resolve("net/minecraft/server/WorldLoader.class"));
        patchBlocksBrowserStartupYield(
                args[0], root.resolve("net/minecraft/world/level/block/Blocks.class"));
        patchBlockStateBaseBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.class"));
        patchBuiltInRegistriesBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/core/registries/BuiltInRegistries.class"));
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
        patchConnectScreenBrowserRecovery(args[0], root.resolve(
                "net/minecraft/client/gui/screens/ConnectScreen.class"));
        patchPacketProcessorBrowserSlice(args[0], root.resolve(
                "net/minecraft/network/PacketProcessor.class"));
        patchClientKeepAliveBrowser(args[0], root.resolve(
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class"));
        patchDownloadedPackSourceBrowserRecovery(args[0], root.resolve(
                "net/minecraft/client/resources/server/DownloadedPackSource.class"));
        patchEarlyBrowserServerPackSuccess(args[0], root.resolve(
                "net/minecraft/client/resources/server/DownloadedPackSource$6.class"));
        patchResourceReloadProfiling(args[0], root.resolve(
                "net/minecraft/server/packs/resources/SimpleReloadInstance.class"));
        patchSimpleJsonResourceReloadListenerBrowserStartupYield(args[0], root.resolve(
                "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.class"));
        patchResourceReloadTaskLabels(
                args[0],
                root.resolve("net/minecraft/client/resources/model/ModelManager.class"),
                root.resolve("net/minecraft/client/gui/font/FontManager.class"));
        patchAtlasManagerReloadTaskLabels(args[0], root);
        patchUnihexProviderBrowserBulkParser(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition.class"));
        patchUnihexProviderBrowserAccess(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/UnihexProvider.class"));
        patchNetworkEncoderMatchers(args[0], root);
        patchClassTreeIdRegistry(args[0], root.resolve("net/minecraft/util/ClassTreeIdRegistry.class"));
        patchSynchedEntityDataClassInitialization(
                args[0], root.resolve("net/minecraft/network/syncher/SynchedEntityData.class"));
        patchEntityBrowserUuidUsesGlobalRandom(args[0], root.resolve(
                "net/minecraft/world/entity/Entity.class"));
        patchGlx(args[0], root.resolve("com/mojang/blaze3d/platform/GLX.class"));
        patchGlDebugBrowserNoCallback(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlDebug.class"));
        patchRenderSystemBrowserDeadlineCompensation(args[0], root);
        patchFramerateLimitTrackerBrowserNoThrottle(args[0], root.resolve(
                "com/mojang/blaze3d/platform/FramerateLimitTracker.class"));
        patchTracyZoneFiller(
                args[0], root.resolve("net/minecraft/util/profiling/TracyZoneFiller.class"));
        patchBlockableEventLoopBrowser(args[0], root.resolve(
                "net/minecraft/util/thread/BlockableEventLoop.class"));
        patchTracingExecutorBrowser(args[0], root.resolve("net/minecraft/TracingExecutor.class"));
        patchMacosUtil(
                args[0], root.resolve("com/mojang/blaze3d/platform/MacosUtil.class"));
        patchInputConstants(
                args[0], root.resolve("com/mojang/blaze3d/platform/InputConstants.class"));
        patchMemoryDebug(args[0], root.resolve(
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class"));
        patchMainBrowserStorageMount(args[0], root.resolve("net/minecraft/client/main/Main.class"));
        patchMinecraft(args[0], root);
        patchWorldStemBrowserSave(args[0], root.resolve(
                "net/minecraft/server/WorldStem.class"));
        patchCommandEncoderLegacyTextureUpload(args[0], root.resolve(
                "com/mojang/blaze3d/systems/CommandEncoder.class"));
        patchLoadingOverlayBrowserForeground(args[0], root.resolve(
                "net/minecraft/client/gui/screens/LoadingOverlay.class"));
        patchPauseScreenBrowserSingleplayer(args[0], root.resolve(
                "net/minecraft/client/gui/screens/PauseScreen.class"));
        patchOptionsBrowserLowSimulationDistance(args[0], root.resolve(
                "net/minecraft/client/Options.class"));
        patchBrowserInputCallbacks(args[0], root);
        patchGuiGraphicsBrowserItemCache(args[0], root);
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
        patchChunkTaskPriorityQueueBrowserNearestFirst(args[0], root.resolve(
                "net/minecraft/server/level/ChunkTaskPriorityQueue.class"));
        patchPlayerSpawnFinderBrowser(args[0], root.resolve(
                "net/minecraft/server/level/PlayerSpawnFinder.class"));
        patchPrepareSpawnTaskBrowser(args[0], root);
        patchStructureTemplateManagerBrowserGzip(args[0], root);
        patchServerCommonPacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerCommonPacketListenerImpl.class"));
        patchServerGamePacketListenerBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/ServerGamePacketListenerImpl.class"));
        patchPlayerChunkSenderBrowserWorker(args[0], root.resolve(
                "net/minecraft/server/network/PlayerChunkSender.class"));
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
        patchNoiseBasedChunkGeneratorBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class"));
        patchNoiseChunkBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk.class"));
        patchNoiseInterpolatorBrowserLerp(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator.class"));
        patchNoiseChunkContextBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$1.class"));
        patchNoiseChunkCacheOnceBrowserIntCounters(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce.class"));
        patchDensityFunctionsPureTransformersBrowserDirect(args[0], root);
        patchWorldgenRecordHashCodeCaches(args[0], root);
        patchClimateRTreeBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$SubTree.class"));
        patchClimateRTreeNodeBrowserDoubleDistance(args[0], root.resolve(
                "net/minecraft/world/level/biome/Climate$RTree$Node.class"));
        patchSurfaceSystemBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceSystem.class"));
        patchSurfaceRulesContextBrowserReusableBiomeSupplier(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceRules$Context.class"));
        patchSurfaceRulesLazyConditionBrowserPrimitiveCache(args[0], root);
        patchSurfaceRulesSequenceBrowserIndexed(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule.class"));
        patchChunkGeneratorBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/chunk/ChunkGenerator.class"));
        patchWorldCarverBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/levelgen/carver/WorldCarver.class"));
        patchLightEngineBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/lighting/LightEngine.class"));
        patchLevelChunkSectionBrowserSynchronous(args[0], root.resolve(
                "net/minecraft/world/level/chunk/LevelChunkSection.class"));
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
        patchUtilRunNamedBrowserOutput(root.resolve("net/minecraft/util/Util.class"));
        patchUtilBlockUntilDoneBrowserOutput(root.resolve("net/minecraft/util/Util.class"));
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
        patchDetectedVersion(
                args[0], root.resolve("net/minecraft/DetectedVersion.class"), minecraftVersion);
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
        patchGlDevice(args[0], root);
        patchGlConstWebGLTextureFormats(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlConst.class"));
        patchTextureFormatWebGLColorAspect(args[0], root);
        patchGlStateManagerTextureBinding(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlStateManager.class"));
        patchGlRenderPipelineDrawMetadata(args[0], root.resolve(
                "com/mojang/blaze3d/opengl/GlRenderPipeline.class"));
        patchGlCommandEncoder(args[0], root);
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
        patchCurrentLevelExtractorBrowserSectionCompileThrottle(args[0], root.resolve(
                "net/minecraft/client/renderer/extract/LevelExtractor.class"));
        patchEntityRenderDispatcherBrowserNullEntityGuard(args[0], root.resolve(
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher.class"));
        patchRenderSectionRegionBrowserDirectSectionCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/RenderSectionRegion.class"));
        patchSectionCompilerBrowserDirectRelativeCoordinates(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionCompiler.class"));
        patchSectionRenderDispatcherBrowserThrottles(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class"));
        patchCurrentSectionTaskQueueBrowserPriorities(args[0], root.resolve(
                "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue.class"));
        patchUberGpuBufferBrowserTelemetry(args[0], root.resolve(
                "com/mojang/blaze3d/vertex/UberGpuBuffer.class"));
        patchFaceBakeryBrowserFloatTolerance(args[0], root);
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
        boolean lazyDataFixerOptimizationHooked = false;
        boolean bootstrapPhaseHooked = false;
        boolean clientBootstrapPhaseHooked = false;
        boolean bootstrapValidationPhaseHooked = false;
        boolean dataFixerJoinPhaseHooked = false;
        boolean renderThreadPhaseHooked = false;
        AbstractInsnNode startupCompletionPoint = null;
        for (AbstractInsnNode instruction : main.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals("com/mojang/blaze3d/TracyBootstrap")
                    && call.name.equals("setup") && call.desc.equals("()V")) {
                main.instructions.set(call, new InsnNode(Opcodes.NOP));
            } else if (call.owner.equals("com/mojang/jtracy/TracyClient")
                    && call.name.equals("reportAppInfo") && call.desc.equals("(Ljava/lang/String;)V")) {
                main.instructions.set(call, new InsnNode(Opcodes.POP));
            } else if (call.owner.equals("net/minecraft/util/datafix/DataFixers")
                    && call.name.equals("optimize")
                    && call.desc.equals("(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;")) {
                call.owner = "dev/gaius/browser/BrowserLazyDataFixer";
                call.name = "skipEagerOptimization";
                lazyDataFixerOptimizationHooked = true;
            } else if (call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("bootStrap") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("bootstrap-complete"));
                bootstrapPhaseHooked = true;
            } else if (call.owner.equals("net/minecraft/client/ClientBootstrap")
                    && call.name.equals("bootstrap") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("client-bootstrap-complete"));
                clientBootstrapPhaseHooked = true;
            } else if (call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("validate") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("bootstrap-validated"));
                bootstrapValidationPhaseHooked = true;
            } else if (call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("join") && call.desc.equals("()Ljava/lang/Object;")) {
                main.instructions.insert(
                        call, browserStartupPhase("datafixer-optimization-complete"));
                dataFixerJoinPhaseHooked = true;
            } else if (call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    && call.name.equals("initRenderThread") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("render-thread-ready"));
                renderThreadPhaseHooked = true;
            } else if (call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("<init>")
                    && call.desc.equals("(Lnet/minecraft/client/main/GameConfig;)V")) {
                AbstractInsnNode store = nextRealInstruction(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Minecraft constructor result store changed");
                }
                startupCompletionPoint = store;
            }
        }
        if (!lazyDataFixerOptimizationHooked) {
            throw new IllegalStateException("Main eager data fixer optimization hook point was not found");
        }
        if (!bootstrapPhaseHooked || !clientBootstrapPhaseHooked
                || !bootstrapValidationPhaseHooked || !dataFixerJoinPhaseHooked
                || !renderThreadPhaseHooked) {
            throw new IllegalStateException("Main browser startup phase hook points were not found");
        }
        if (startupCompletionPoint == null) {
            throw new IllegalStateException(
                    "Minecraft browser startup completion point was not found");
        }
        main.instructions.insert(startupCompletionPoint, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "complete",
                "()V",
                false));
        main.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "mount",
                "()V",
                false));
        write(node, output);
    }

    private static InsnList browserStartupPhase(String phase) {
        InsnList checkpoint = new InsnList();
        checkpoint.add(new LdcInsnNode(phase));
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "phase",
                "(Ljava/lang/String;)V",
                false));
        return checkpoint;
    }

    private static void patchAtlasManagerReloadTaskLabels(String jar, Path outputRoot)
            throws IOException {
        String legacyEntry =
                "net/minecraft/client/resources/model/AtlasManager$AtlasEntry.class";
        String currentEntry =
                "net/minecraft/client/resources/model/sprite/AtlasManager$AtlasEntry.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        ClassNode node = read(jar, entry);
        MethodNode scheduleLoad = find(node, "scheduleLoad", "(Lnet/minecraft/server/packs/"
                + "resources/ResourceManager;Ljava/util/concurrent/Executor;I)"
                + "Ljava/util/concurrent/CompletableFuture;");
        InsnList label = new InsnList();
        label.add(new VarInsnNode(Opcodes.ALOAD, 2));
        label.add(new VarInsnNode(Opcodes.ALOAD, 0));
        label.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                "labelAtlas",
                "(Ljava/util/concurrent/Executor;Ljava/lang/Object;)Ljava/util/concurrent/Executor;",
                false));
        label.add(new VarInsnNode(Opcodes.ASTORE, 2));
        scheduleLoad.instructions.insert(label);
        scheduleLoad.maxStack = Math.max(scheduleLoad.maxStack, 2);
        write(node, outputRoot.resolve(entry));
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

    private static void patchRenderSystemBrowserDeadlineCompensation(String jar, Path outputRoot)
            throws IOException {
        String currentEntry = "net/minecraft/client/FramerateLimiter.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(currentEntry) != null) {
                patchCurrentFramerateLimiter(
                        jar, outputRoot.resolve(currentEntry));
                return;
            }
        }
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
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
    }

    private static void patchCurrentFramerateLimiter(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/FramerateLimiter";
        String helperName = "browserCompensateFrameTime";
        ClassNode node = read(jar, owner + ".class");
        MethodNode limit = find(node, "limitDisplayFPS", "(I)V");
        FieldInsnNode finalTimestampStore = null;
        for (AbstractInsnNode instruction = limit.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner)
                    && field.name.equals("lastFrameTime")
                    && field.desc.equals("J")) {
                finalTimestampStore = field;
            }
        }
        if (finalTimestampStore == null) {
            throw new IllegalStateException(
                    "FramerateLimiter final timestamp store was not found");
        }
        AbstractInsnNode nowCall = previousOpcode(finalTimestampStore);
        if (!(nowCall instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKESTATIC
                || !call.owner.equals("java/lang/System")
                || !call.name.equals("nanoTime")
                || !call.desc.equals("()J")) {
            throw new IllegalStateException(
                    "FramerateLimiter final current-time call changed");
        }
        InsnList compensate = new InsnList();
        compensate.add(new VarInsnNode(Opcodes.LLOAD, 3));
        compensate.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compensate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                helperName,
                "(JI)J",
                false));
        limit.instructions.insertBefore(nowCall, compensate);
        limit.instructions.remove(nowCall);
        limit.maxStack = Math.max(limit.maxStack, 4);

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                helperName,
                "(JI)J",
                null,
                null);
        LabelNode returnNow = new LabelNode();
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "nanoTime",
                "()J",
                false));
        helper.instructions.add(new VarInsnNode(Opcodes.LSTORE, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LCMP));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLT, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LSUB));
        helper.instructions.add(new LdcInsnNode(1_000_000_000L));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.I2L));
        helper.instructions.add(new InsnNode(Opcodes.LDIV));
        helper.instructions.add(new InsnNode(Opcodes.LCMP));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFGE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LRETURN));
        helper.instructions.add(returnNow);
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new InsnNode(Opcodes.LRETURN));
        helper.maxStack = 6;
        helper.maxLocals = 5;
        node.methods.add(helper);
        writeComputeFrames(node, output);
    }

    private static void patchChunkMapBrowserInitialViewDistance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkMap.class");
        MethodNode setDistance = find(node, "setServerViewDistance", "(I)V");
        int setDistancePatched = 0;
        for (AbstractInsnNode instruction = setDistance.instructions.getFirst();
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
            setDistance.instructions.set(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "minimumServerViewDistance",
                    "()I",
                    false));
            setDistancePatched++;
        }

        MethodNode playerDistance = find(
                node,
                "getPlayerViewDistance",
                "(Lnet/minecraft/server/level/ServerPlayer;)I");
        int playerDistancePatched = 0;
        for (AbstractInsnNode instruction = playerDistance.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ICONST_2) {
                continue;
            }
            AbstractInsnNode requestedDistance = previousRealInstruction(instruction);
            AbstractInsnNode owner = nextRealInstruction(instruction);
            AbstractInsnNode maximum = nextRealInstruction(owner);
            AbstractInsnNode clamp = nextRealInstruction(maximum);
            if (!(requestedDistance instanceof MethodInsnNode requestedCall)
                    || !requestedCall.owner.equals("net/minecraft/server/level/ServerPlayer")
                    || !requestedCall.name.equals("requestedViewDistance")
                    || !requestedCall.desc.equals("()I")
                    || !(owner instanceof VarInsnNode ownerLoad)
                    || ownerLoad.getOpcode() != Opcodes.ALOAD
                    || ownerLoad.var != 0
                    || !(maximum instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals("net/minecraft/server/level/ChunkMap")
                    || !field.name.equals("serverViewDistance")
                    || !field.desc.equals("I")
                    || !(clamp instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("clamp")
                    || !call.desc.equals("(III)I")) {
                continue;
            }
            playerDistance.instructions.set(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "minimumServerViewDistance",
                    "()I",
                    false));
            playerDistancePatched++;
        }
        if (setDistancePatched != 1 || playerDistancePatched != 1) {
            throw new IllegalStateException(
                    "ChunkMap browser initial view-distance patch points were not found: set="
                            + setDistancePatched
                            + ", player="
                            + playerDistancePatched);
        }
        MethodNode updatePlayerPos = find(
                node,
                "updatePlayerPos",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        InsnList recordPlayerPosition = new InsnList();
        recordPlayerPosition.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerPlayer",
                "getX",
                "()D",
                false));
        recordPlayerPosition.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerPlayer",
                "getZ",
                "()D",
                false));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkTaskPriority",
                "recordPlayerPosition",
                "(DD)V",
                false));
        updatePlayerPos.instructions.insert(recordPlayerPosition);
        updatePlayerPos.maxStack = Math.max(updatePlayerPos.maxStack, 4);
        write(node, output);
    }

    private static void patchChunkTaskPriorityQueueBrowserNearestFirst(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkTaskPriorityQueue.class");
        MethodNode pop = find(
                node,
                "pop",
                "()Lnet/minecraft/server/level/ChunkTaskPriorityQueue$TasksForChunk;");
        int selectedKeyLocal = -1;
        int chooserPatched = 0;
        for (AbstractInsnNode instruction = pop.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap")
                    || !call.name.equals("firstLongKey")
                    || !call.desc.equals("()J")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserChunkTaskPriority";
            call.name = "chooseNext";
            call.desc = "(Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;)J";
            call.itf = false;
            AbstractInsnNode store = nextRealInstruction(call);
            if (!(store instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.LSTORE) {
                throw new IllegalStateException(
                        "ChunkTaskPriorityQueue selected-key local changed");
            }
            selectedKeyLocal = variable.var;
            chooserPatched++;
        }
        int removalPatched = 0;
        for (AbstractInsnNode instruction = pop.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap")
                    || !call.name.equals("removeFirst")
                    || !call.desc.equals("()Ljava/lang/Object;")) {
                continue;
            }
            pop.instructions.insertBefore(
                    call,
                    new VarInsnNode(Opcodes.LLOAD, selectedKeyLocal));
            call.name = "remove";
            call.desc = "(J)Ljava/lang/Object;";
            removalPatched++;
        }
        if (chooserPatched != 1 || removalPatched != 1 || selectedKeyLocal < 0) {
            throw new IllegalStateException(
                    "ChunkTaskPriorityQueue nearest-first patch points changed: choose="
                            + chooserPatched
                            + ", remove="
                            + removalPatched);
        }
        write(node, output);
    }

    private static void patchFaceBakeryBrowserFloatTolerance(String jar, Path root)
            throws IOException {
        String oldEntry = "net/minecraft/client/renderer/block/model/FaceBakery.class";
        String currentEntry = "net/minecraft/client/resources/model/cuboid/FaceBakery.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(oldEntry) != null) {
                entry = oldEntry;
            } else if (input.getEntry(currentEntry) != null) {
                entry = currentEntry;
            } else {
                throw new IllegalStateException("FaceBakery browser target was not found");
            }
        }
        ClassNode node = read(jar, entry);
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
        write(node, root.resolve(entry));
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
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractWidgetRenderState"));
        MethodNode renderWidget = find(
                node,
                extractedGui ? "extractWidgetRenderState" : "renderWidget",
                extractedGui
                        ? "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
                        : "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
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
            } else if (method.name.startsWith("lambda$openFresh$")
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
            throw new IllegalStateException(
                    "CreateWorldScreen browser default world patch points were not found: normalPreset="
                            + foundNormalPreset
                            + ", defaultOptions=" + foundDefaultOptions
                            + ", normalDimensions=" + foundNormalDimensions
                            + ", allowCommands=" + patchedAllowCommands
                            + ", initialAllowCommands=" + patchedInitialAllowCommands);
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
            call.owner = "java/util/UUID";
            call.name = "randomUUID";
            call.desc = "()Ljava/util/UUID;";
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(
                    "Expected one Entity constructor UUID random replacement, got " + replacements);
        }
        verifyBrowserUuidCalls(constructor, 1);
        writeComputeFrames(node, output);
    }

    private static void verifyBrowserUuidCalls(MethodNode method, int expectedRandomUuidCalls) {
        int randomUuidCalls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            if (call.owner.equals("net/minecraft/util/Mth")
                    && call.name.equals("createInsecureUUID")
                    && call.desc.equals("()Ljava/util/UUID;")) {
                throw new IllegalStateException(
                        "Browser patch introduced missing Mth.createInsecureUUID() in "
                                + method.name + method.desc);
            }
            if (call.owner.equals("java/util/UUID")
                    && call.name.equals("randomUUID")
                    && call.desc.equals("()Ljava/util/UUID;")) {
                randomUuidCalls++;
            }
        }
        if (randomUuidCalls != expectedRandomUuidCalls) {
            throw new IllegalStateException(
                    "Expected " + expectedRandomUuidCalls + " UUID.randomUUID calls in "
                            + method.name + method.desc + ", got " + randomUuidCalls);
        }
    }

    private static void patchScreenBrowserFastMenus(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/Screen.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractPanorama"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";

        MethodNode panorama = find(
                node,
                extractedGui ? "extractPanorama" : "renderPanorama",
                "(L" + graphics + ";F)V");
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
                graphics,
                "fill",
                "(IIIII)V",
                false));
        panoramaCode.add(new InsnNode(Opcodes.RETURN));
        replace(panorama, panoramaCode, 6, 3);

        MethodNode menuBackground = find(
                node,
                extractedGui ? "extractMenuBackground" : "renderMenuBackground",
                "(L" + graphics + ";IIII)V");
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
                graphics,
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
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractChunksForRendering"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";
        MethodNode renderChunks = find(
                node,
                extractedGui ? "extractChunksForRendering" : "renderChunks",
                "(L" + graphics + ";IIIILnet/minecraft/server/level/progress/"
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

        MethodNode initializer = find(node, "<clinit>", "()V");
        int attributionRewrites = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode literal)
                    || !"title.credits".equals(literal.cst)) {
                continue;
            }
            AbstractInsnNode next = nextRealInstruction(instruction);
            if (!(next instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/network/chat/Component")
                    || !call.name.equals("translatable")
                    || !call.desc.equals("(Ljava/lang/String;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;")) {
                throw new IllegalStateException("TitleScreen attribution shape changed");
            }
            literal.cst = "Gaius is independent and is not affiliated with Mojang or Microsoft.";
            call.name = "literal";
            attributionRewrites++;
        }
        if (attributionRewrites != 1) {
            throw new IllegalStateException(
                    "TitleScreen browser attribution patch point was not found: "
                            + attributionRewrites);
        }

        int attributionCallbacks = 0;
        for (MethodNode method : node.methods) {
            boolean opensVanillaCredits = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals(
                                "net/minecraft/client/gui/screens/CreditsAndAttributionScreen")) {
                    opensVanillaCredits = true;
                    break;
                }
            }
            if (!opensVanillaCredits) {
                continue;
            }
            InsnList noOp = new InsnList();
            noOp.add(new InsnNode(Opcodes.RETURN));
            replace(method, noOp, 0, 2);
            attributionCallbacks++;
        }
        if (attributionCallbacks != 1) {
            throw new IllegalStateException(
                    "TitleScreen vanilla credits callback patch point was not found: "
                            + attributionCallbacks);
        }
        write(node, output);
    }

    private static void patchAbstractButtonBrowserFastSprite(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/components/AbstractButton.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("extractDefaultSprite"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";
        MethodNode method = find(
                node,
                extractedGui ? "extractDefaultSprite" : "renderDefaultSprite",
                "(L" + graphics + ";)V");
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
                graphics,
                "fill",
                "(IIIII)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 6, 2);
        write(node, output);
    }

    private static void patchGuiGraphicsBrowserItemCache(String jar, Path outputRoot)
            throws IOException {
        String legacyEntry = "net/minecraft/client/gui/GuiGraphics.class";
        String currentEntry = "net/minecraft/client/gui/GuiGraphicsExtractor.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        String owner = entry.substring(0, entry.length() - ".class".length());
        ClassNode node = read(jar, entry);
        MethodNode method = find(node, entry.equals(currentEntry) ? "item" : "renderItem",
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
                owner,
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
        writeComputeFrames(node, outputRoot.resolve(entry));
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
        if (replacements == 1) {
            return;
        }

        int lazyDetailReplacements = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic)
                    || !dynamic.desc.equals("(Lnet/minecraft/world/item/ItemStack;)"
                            + "Lnet/minecraft/CrashReportDetail;")) {
                continue;
            }
            AbstractInsnNode stackLoadInstruction = previousRealInstruction(instruction);
            if (!(stackLoadInstruction instanceof VarInsnNode stackLoad)
                    || stackLoad.getOpcode() != Opcodes.ALOAD
                    || stackLoad.var != 3) {
                throw new IllegalStateException(
                        "GUI item lazy debug detail no longer captures item stack local 3");
            }
            method.instructions.insertBefore(stackLoadInstruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserGuiItemCache",
                    "itemDebugDetail",
                    "()Lnet/minecraft/CrashReportDetail;",
                    false));
            method.instructions.remove(stackLoadInstruction);
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next == null ? null : next.getPrevious();
            lazyDetailReplacements++;
        }
        if (lazyDetailReplacements != 3) {
            throw new IllegalStateException(
                    "Expected one eager or three lazy GUI item debug-name replacements, got "
                            + replacements + " eager and " + lazyDetailReplacements + " lazy");
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
        patchGuiRenderStateTelemetry(jar, root);
        patchGuiRendererTelemetry(jar, root.resolve(
                "net/minecraft/client/gui/render/GuiRenderer.class"), root);
    }

    private static void patchGuiRenderStateTelemetry(String jar, Path outputRoot) throws IOException {
        String legacyEntry = "net/minecraft/client/gui/render/state/GuiRenderState.class";
        String currentEntry = "net/minecraft/client/renderer/state/gui/GuiRenderState.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        ClassNode node = read(jar, entry);
        insertAtStart(find(node, "reset", "()V"), browserGuiItemPoolResetCode());
        writeComputeFrames(node, outputRoot.resolve(entry));
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

    private static void patchGuiRendererTelemetry(String jar, Path output, Path outputRoot)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/render/GuiRenderer.class");
        boolean currentGuiPipeline = node.methods.stream()
                .anyMatch(method -> method.name.equals("render") && method.desc.equals("()V"));

        String renderDescriptor = currentGuiPipeline
                ? "()V"
                : "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";
        MethodNode render = find(node, "render", renderDescriptor);
        insertAtStart(render, staticCall("reportGuiRenderStart", "()V"));

        MethodNode draw = find(node, "draw", renderDescriptor);
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
        if (currentGuiPipeline) {
            drawPlan.add(new InsnNode(Opcodes.ICONST_0));
        } else {
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
        }
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

        if (currentGuiPipeline) {
            String itemAtlasEntry = "net/minecraft/client/gui/render/GuiItemAtlas.class";
            ClassNode itemAtlas = read(jar, itemAtlasEntry);
            MethodNode getOrUpdate = find(
                    itemAtlas,
                    "getOrUpdate",
                    "(Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;)"
                            + "Lnet/minecraft/client/gui/render/GuiItemAtlas$SlotView;");
            freezeGuiAnimatedItemAtlasHit(getOrUpdate);
            writeComputeFrames(itemAtlas, outputRoot.resolve(itemAtlasEntry));
        } else {
            MethodNode itemLambda = find(node, "lambda$prepareItemElements$3",
                    "(Lorg/apache/commons/lang3/mutable/MutableBoolean;II"
                            + "Lorg/apache/commons/lang3/mutable/MutableBoolean;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/gui/render/state/GuiItemRenderState;)V");
            freezeGuiAnimatedItemAtlasHit(itemLambda);
        }

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

    private static void patchGlDevice(String jar, Path outputRoot) throws IOException {
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
        MethodNode maxTextureSize = node.methods.stream()
                .filter(method -> method.name.equals("getMaxSupportedTextureSize")
                        && method.desc.equals("()I"))
                .findFirst()
                .orElse(null);
        ClassNode maxTextureOwner = node;
        String maxTextureEntry = owner + ".class";
        if (maxTextureSize == null) {
            String heuristicsOwner = "com/mojang/blaze3d/opengl/GlHeuristics";
            maxTextureOwner = read(jar, heuristicsOwner + ".class");
            maxTextureSize = find(maxTextureOwner, "getMaxSupportedTextureSize", "()I");
            maxTextureEntry = heuristicsOwner + ".class";
        }
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
        write(node, outputRoot.resolve(owner + ".class"));
        if (maxTextureOwner != node) {
            write(maxTextureOwner, outputRoot.resolve(maxTextureEntry));
        }
    }

    private static void patchGlConstWebGLTextureFormats(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlConst.class");
        boolean gpuFormat = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("toGlInternalId")
                        && candidate.desc.equals("(Lcom/mojang/blaze3d/GpuFormat;)I"));
        MethodNode method = find(
                node,
                "toGlInternalId",
                gpuFormat
                        ? "(Lcom/mojang/blaze3d/GpuFormat;)I"
                        : "(Lcom/mojang/blaze3d/textures/TextureFormat;)I");
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

    private static void patchTextureFormatWebGLColorAspect(String jar, Path root) throws IOException {
        String oldEntry = "com/mojang/blaze3d/textures/TextureFormat.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(oldEntry) == null) {
                String currentEntry = "com/mojang/blaze3d/GpuFormat.class";
                if (input.getEntry(currentEntry) == null) {
                    throw new IllegalStateException("Neither TextureFormat nor GpuFormat was found");
                }
                ClassNode current = read(jar, currentEntry);
                MethodNode hasColorAspect = find(current, "hasColorAspect", "()Z");
                boolean checksDepth = false;
                boolean checksStencil = false;
                for (AbstractInsnNode instruction : hasColorAspect.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("com/mojang/blaze3d/GpuFormat")
                            && call.desc.equals("()Z")) {
                        checksDepth |= call.name.equals("hasDepthAspect");
                        checksStencil |= call.name.equals("hasStencilAspect");
                    }
                }
                if (!checksDepth || !checksStencil) {
                    throw new IllegalStateException(
                            "GpuFormat.hasColorAspect no longer excludes depth and stencil formats");
                }
                System.out.println(
                        "Verified current GpuFormat color/depth/stencil aspect semantics");
                return;
            }
        }

        ClassNode node = read(jar, oldEntry);
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
        writeComputeFrames(node, root.resolve(oldEntry));
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
        String primitiveTopology = "com/mojang/blaze3d/PrimitiveTopology";
        ClassNode node = read(jar, owner + ".class");
        ClassNode pipelineNode = read(jar, pipeline + ".class");
        boolean multiBufferPipeline = pipelineNode.methods.stream()
                .anyMatch(method -> method.name.equals("getVertexFormatBindings")
                        && method.desc.equals("()[L" + vertexFormat + ";"));
        if (multiBufferPipeline) {
            find(node, "info", "()L" + pipeline + ";");
            find(pipelineNode, "getPrimitiveTopology", "()L" + primitiveTopology + ";");
            node.fields.add(new FieldNode(
                    Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "gaius$primitiveTopology",
                    "I",
                    null,
                    null));

            MethodNode constructor = find(
                    node,
                    "<init>",
                    "(L" + pipeline + ";Lcom/mojang/blaze3d/opengl/GlProgram;)V");
            AbstractInsnNode constructorReturn = null;
            for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    if (constructorReturn != null) {
                        throw new IllegalStateException(
                                "GlRenderPipeline constructor has multiple returns");
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
                    "getPrimitiveTopology",
                    "()L" + primitiveTopology + ";",
                    false));
            initialize.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/mojang/blaze3d/opengl/GlConst",
                    "toGl",
                    "(L" + primitiveTopology + ";)I",
                    false));
            initialize.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "gaius$primitiveTopology",
                    "I"));
            constructor.instructions.insertBefore(constructorReturn, initialize);
            constructor.maxStack = Math.max(constructor.maxStack, 2);
            writeComputeFrames(node, output);
            System.out.println("Cached current multi-buffer render pipeline topology");
            return;
        }
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

    private static void patchGlCommandEncoder(String jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlCommandEncoder.class");
        String owner = "com/mojang/blaze3d/opengl/GlCommandEncoder";
        String pass = "com/mojang/blaze3d/opengl/GlRenderPass";
        String renderSystem = "com/mojang/blaze3d/systems/RenderSystem";
        String slice = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
        String buffer = "Lcom/mojang/blaze3d/buffers/GpuBuffer;";
        Path output = outputRoot.resolve(owner + ".class");

        if (findNullable(node, "submit", "()V") != null) {
            patchCurrentGlCommandEncoderGpuRetire(jar, node, owner, outputRoot);
        }

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

        String currentDrawDescriptor =
                "(L" + pass + ";IIILcom/mojang/blaze3d/IndexType;"
                        + "Lcom/mojang/blaze3d/opengl/GlRenderPipeline;II)V";
        MethodNode currentDrawFromBuffers = node.methods.stream()
                .filter(method -> method.name.equals("drawFromBuffers")
                        && method.desc.equals(currentDrawDescriptor))
                .findFirst()
                .orElse(null);
        if (currentDrawFromBuffers != null) {
            replaceCurrentGlCommandDraw(currentDrawFromBuffers, owner, pass);
            writeComputeFrames(node, output);
            return;
        }

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

    private static void patchCurrentGlCommandEncoderGpuRetire(
            String jar, ClassNode node, String owner, Path outputRoot) throws IOException {
        FieldNode maxSubmits = node.fields.stream()
                .filter(field -> field.name.equals("MAX_SUBMITS_IN_FLIGHT")
                        && field.desc.equals("I"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current GlCommandEncoder submit limit field was not found"));
        if (maxSubmits.value != null
                && (!(maxSubmits.value instanceof Integer value) || value != 2)) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder submit limit changed: " + maxSubmits.value);
        }
        maxSubmits.value = BROWSER_GPU_RETIRE_SLOTS;

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lcom/mojang/blaze3d/opengl/GlDevice;)V");
        FieldInsnNode fencesStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("fences")
                    && field.desc.equals("[J")) {
                fencesStore = field;
                break;
            }
        }
        if (fencesStore == null) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder fences initialization was not found");
        }
        AbstractInsnNode newArray = previousOpcode(fencesStore);
        AbstractInsnNode oldSize = newArray == null ? null : previousOpcode(newArray);
        if (newArray == null || newArray.getOpcode() != Opcodes.NEWARRAY
                || !(newArray instanceof IntInsnNode array)
                || array.operand != Opcodes.T_LONG
                || oldSize == null || oldSize.getOpcode() != Opcodes.ICONST_2) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder fences array shape changed");
        }
        constructor.instructions.set(
                oldSize, new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        FieldInsnNode transientMemoryStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("transientMemory")
                    && field.desc.equals("Lcom/mojang/blaze3d/opengl/GlTransientMemory;")) {
                transientMemoryStore = field;
                break;
            }
        }
        if (transientMemoryStore == null) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder transient memory initialization was not found");
        }
        constructor.instructions.insertBefore(
                transientMemoryStore,
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        "com/mojang/blaze3d/opengl/GlTransientMemory"));

        MethodNode currentSubmitSlot = find(node, "currentSubmitSlot", "()I");
        InsnList currentSlotCode = new InsnList();
        currentSlotCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        currentSlotCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        currentSlotCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        currentSlotCode.add(new InsnNode(Opcodes.LREM));
        currentSlotCode.add(new InsnNode(Opcodes.L2I));
        currentSlotCode.add(new InsnNode(Opcodes.IRETURN));
        replace(currentSubmitSlot, currentSlotCode, 4, 1);

        MethodNode backlog = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "gaius$retireBacklog",
                "()I",
                null,
                null);
        backlog.instructions.add(new InsnNode(Opcodes.ICONST_0));
        backlog.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode empty = new LabelNode();
            backlog.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            backlog.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "fences", "[J"));
            backlog.instructions.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            backlog.instructions.add(new InsnNode(Opcodes.LALOAD));
            backlog.instructions.add(new InsnNode(Opcodes.LCONST_0));
            backlog.instructions.add(new InsnNode(Opcodes.LCMP));
            backlog.instructions.add(new JumpInsnNode(Opcodes.IFEQ, empty));
            backlog.instructions.add(new IincInsnNode(1, 1));
            backlog.instructions.add(empty);
        }
        backlog.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        backlog.instructions.add(new InsnNode(Opcodes.IRETURN));
        backlog.maxStack = 4;
        backlog.maxLocals = 2;
        node.methods.add(backlog);

        MethodNode poll = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "gaius$pollRetireSlot",
                "(IJ)Z",
                null,
                null);
        LabelNode pollFence = new LabelNode();
        LabelNode signaled = new LabelNode();
        LabelNode pending = new LabelNode();
        poll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        poll.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        poll.instructions.add(new InsnNode(Opcodes.LALOAD));
        poll.instructions.add(new VarInsnNode(Opcodes.LSTORE, 4));
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new InsnNode(Opcodes.LCONST_0));
        poll.instructions.add(new InsnNode(Opcodes.LCMP));
        poll.instructions.add(new JumpInsnNode(Opcodes.IFNE, pollFence));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.instructions.add(pollFence);
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        poll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlStateManager",
                "_glClientWaitSync",
                "(JIJ)I",
                false));
        poll.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        poll.instructions.add(new LdcInsnNode(37146));
        poll.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, signaled));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        poll.instructions.add(new LdcInsnNode(37148));
        poll.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, signaled));
        poll.instructions.add(new JumpInsnNode(Opcodes.GOTO, pending));
        poll.instructions.add(signaled);
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glDeleteSync",
                "(J)V",
                false));
        poll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        poll.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        poll.instructions.add(new InsnNode(Opcodes.LCONST_0));
        poll.instructions.add(new InsnNode(Opcodes.LASTORE));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.instructions.add(pending);
        poll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.maxStack = 5;
        poll.maxLocals = 7;
        node.methods.add(poll);

        MethodNode submit = find(node, "submit", "()V");
        InsnList submitCode = new InsnList();
        LabelNode slotAvailable = new LabelNode();
        LabelNode fenceCreated = new LabelNode();
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        submitCode.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "beginGpuRetireFrame",
                "(II)V",
                false));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "currentSubmitSlot", "()I", false));
        submitCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new InsnNode(Opcodes.LALOAD));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new InsnNode(Opcodes.LCMP));
        submitCode.add(new JumpInsnNode(Opcodes.IFEQ, slotAvailable));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
        submitCode.add(new JumpInsnNode(Opcodes.IFNE, slotAvailable));
        addGpuRetireFrameEnd(submitCode, owner, true);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        submitCode.add(slotAvailable);
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "markNextFenceRetireOwned",
                "()V",
                false));
        submitCode.add(new LdcInsnNode(37143));
        submitCode.add(new InsnNode(Opcodes.ICONST_0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glFenceSync",
                "(II)J",
                false));
        submitCode.add(new VarInsnNode(Opcodes.LSTORE, 2));
        submitCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new InsnNode(Opcodes.LCMP));
        submitCode.add(new JumpInsnNode(Opcodes.IFNE, fenceCreated));
        addGpuRetireFrameEnd(submitCode, owner, true);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        submitCode.add(fenceCreated);
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        submitCode.add(new InsnNode(Opcodes.LASTORE));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "transientMemory",
                "Lcom/mojang/blaze3d/opengl/GlTransientMemory;"));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlTransientMemory",
                "rotate",
                "()V",
                false));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new InsnNode(Opcodes.DUP));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        submitCode.add(new InsnNode(Opcodes.LCONST_1));
        submitCode.add(new InsnNode(Opcodes.LADD));
        submitCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentSubmitIndex", "J"));
        addGpuRetireFrameEnd(submitCode, owner, false);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        replace(submit, submitCode, 5, 4);

        MethodNode awaitSubmit = find(node, "awaitSubmit", "(JJ)Z");
        InsnList awaitCode = new InsnList();
        LabelNode submitted = new LabelNode();
        LabelNode inRetireWindow = new LabelNode();
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        awaitCode.add(new InsnNode(Opcodes.LCMP));
        awaitCode.add(new JumpInsnNode(Opcodes.IFLT, submitted));
        awaitCode.add(new InsnNode(Opcodes.ICONST_0));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        awaitCode.add(submitted);
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new InsnNode(Opcodes.LSUB));
        awaitCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        awaitCode.add(new InsnNode(Opcodes.LCMP));
        awaitCode.add(new JumpInsnNode(Opcodes.IFLE, inRetireWindow));
        awaitCode.add(new InsnNode(Opcodes.ICONST_1));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        awaitCode.add(inRetireWindow);
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        awaitCode.add(new InsnNode(Opcodes.LREM));
        awaitCode.add(new InsnNode(Opcodes.L2I));
        awaitCode.add(new VarInsnNode(Opcodes.ISTORE, 5));
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 3));
        awaitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        replace(awaitSubmit, awaitCode, 4, 6);

        MethodNode close = find(node, "close", "()V");
        InsnList closeCode = new InsnList();
        closeCode.add(new InsnNode(Opcodes.ICONST_1));
        closeCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode retired = new LabelNode();
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.LCONST_0));
            closeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
            closeCode.add(new JumpInsnNode(Opcodes.IFNE, retired));
            closeCode.add(new InsnNode(Opcodes.ICONST_0));
            closeCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
            closeCode.add(retired);
        }
        LabelNode closeDeferred = new LabelNode();
        LabelNode closeDone = new LabelNode();
        closeCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        closeCode.add(new JumpInsnNode(Opcodes.IFEQ, closeDeferred));
        closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        closeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "transientMemory",
                "Lcom/mojang/blaze3d/opengl/GlTransientMemory;"));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlTransientMemory",
                "close",
                "()V",
                false));
        closeCode.add(new InsnNode(Opcodes.ICONST_0));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "gpuRetireClose",
                "(I)V",
                false));
        closeCode.add(new JumpInsnNode(Opcodes.GOTO, closeDone));
        closeCode.add(closeDeferred);
        closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "gpuRetireClose",
                "(I)V",
                false));
        closeCode.add(closeDone);
        closeCode.add(new InsnNode(Opcodes.RETURN));
        replace(close, closeCode, 4, 2);

        patchCurrentGlTransientMemoryRotations(jar, outputRoot);
        System.out.println(
                "Patched current GlCommandEncoder with " + BROWSER_GPU_RETIRE_SLOTS
                        + "-slot asynchronous GPU retire");
    }

    private static void addGpuRetireFrameEnd(
            InsnList code, String owner, boolean backpressure) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        code.add(new InsnNode(backpressure ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "endGpuRetireFrame",
                "(IZ)V",
                false));
    }

    private static void patchCurrentGlTransientMemoryRotations(
            String jar, Path outputRoot) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlTransientMemory$PersistentMapping";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lcom/mojang/blaze3d/opengl/GlDevice;"
                        + "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;)V");
        FieldInsnNode rotationsStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("rotations")
                    && field.desc.equals(
                            "[Lcom/mojang/blaze3d/opengl/"
                                    + "GlTransientMemory$PersistentMapping$Rotation;")) {
                rotationsStore = field;
                break;
            }
        }
        if (rotationsStore == null) {
            throw new IllegalStateException(
                    "Current persistent transient rotation array was not found");
        }
        AbstractInsnNode newArray = previousOpcode(rotationsStore);
        AbstractInsnNode oldSize = newArray == null ? null : previousOpcode(newArray);
        if (newArray == null || newArray.getOpcode() != Opcodes.ANEWARRAY
                || oldSize == null || oldSize.getOpcode() != Opcodes.ICONST_2) {
            throw new IllegalStateException(
                    "Current persistent transient rotation array shape changed");
        }
        constructor.instructions.set(
                oldSize, new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
        patchCurrentGlTransientMemoryFallback(jar, outputRoot);
    }

    private static void patchCurrentGlTransientMemoryFallback(
            String jar, Path outputRoot) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlTransientMemory$Fallback";
        String base = "com/mojang/blaze3d/opengl/GlTransientMemory";
        String rotationsField = "gaius$retireRotations";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                rotationsField,
                "[Ljava/lang/Runnable;",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lcom/mojang/blaze3d/opengl/GlDevice;"
                        + "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException(
                    "Current fallback transient constructor return was not found");
        }
        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        initialize.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Runnable"));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        constructor.instructions.insertBefore(constructorReturn, initialize);

        MethodNode rotate = find(node, "rotate", "()V");
        InsnList rotateCode = new InsnList();
        LabelNode noPrevious = new LabelNode();
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                base,
                "encoder",
                "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;"));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlCommandEncoder",
                "currentSubmitSlot",
                "()I",
                false));
        rotateCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new InsnNode(Opcodes.AALOAD));
        rotateCode.add(new VarInsnNode(Opcodes.ASTORE, 2));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new InsnNode(Opcodes.ACONST_NULL));
        rotateCode.add(new InsnNode(Opcodes.AASTORE));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rotateCode.add(new JumpInsnNode(Opcodes.IFNULL, noPrevious));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Runnable",
                "run",
                "()V",
                true));
        rotateCode.add(noPrevious);
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "blockAllocator",
                "Lcom/mojang/blaze3d/util/TransientBlockAllocator;"));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/util/TransientBlockAllocator",
                "rotate",
                "()Ljava/lang/Runnable;",
                false));
        rotateCode.add(new InsnNode(Opcodes.AASTORE));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, base, "rotate", "()V", false));
        rotateCode.add(new InsnNode(Opcodes.RETURN));
        replace(rotate, rotateCode, 4, 3);

        MethodNode close = find(node, "close", "()V");
        InsnList closeCode = new InsnList();
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode empty = new LabelNode();
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.AALOAD));
            closeCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.ACONST_NULL));
            closeCode.add(new InsnNode(Opcodes.AASTORE));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            closeCode.add(new JumpInsnNode(Opcodes.IFNULL, empty));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            closeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/lang/Runnable",
                    "run",
                    "()V",
                    true));
            closeCode.add(empty);
        }
        closeCode.add(new InsnNode(Opcodes.RETURN));
        replace(close, closeCode, 4, 2);
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
    }

    private static void replaceCurrentGlCommandDraw(
            MethodNode method, String owner, String pass) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        InsnList draw = method.instructions;
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$primitiveTopology",
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
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlBuffer",
                "handle",
                "()I",
                false));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/IndexType",
                "bytes",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlConst",
                "toGl",
                "(Lcom/mojang/blaze3d/IndexType;)I",
                false));
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
        draw.add(new VarInsnNode(Opcodes.ILOAD, 8));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "drawFromBuffers",
                "(IIIIIIIII)V",
                false));
        draw.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 9;
        method.maxLocals = 13;
        System.out.println("Patched current GlCommandEncoder draw path for WebGL");
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
        boolean trackedDevices = node.methods.stream()
                .anyMatch(method -> method.name.equals("createDeviceTracker")
                        && method.desc.equals("()Lcom/mojang/blaze3d/audio/DeviceTracker;"));

        MethodNode init = find(
                node,
                "init",
                trackedDevices
                        ? "(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V"
                        : "(Ljava/lang/String;Z)V");
        int initLocals = init.maxLocals;
        InsnList initCode = new InsnList();
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "init",
                "()V",
                false));
        initCode.add(new VarInsnNode(Opcodes.ILOAD, trackedDevices ? 3 : 2));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "setDirectionalAudio",
                "(Z)V",
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
        if (trackedDevices) {
            initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initCode.add(new LdcInsnNode("Gaius Browser OpenAL"));
            initCode.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "currentDeviceName", "Ljava/lang/String;"));
        }
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "supportsDisconnections", "Z"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode, 4, initLocals);

        MethodNode getCurrentDeviceName = find(
                node,
                trackedDevices ? "currentDeviceName" : "getCurrentDeviceName",
                "()Ljava/lang/String;");
        InsnList currentName = new InsnList();
        currentName.add(new VarInsnNode(Opcodes.ALOAD, 0));
        currentName.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                trackedDevices ? "currentDeviceName" : "defaultDeviceName",
                "Ljava/lang/String;"));
        currentName.add(new InsnNode(Opcodes.ARETURN));
        replace(getCurrentDeviceName, currentName, 1, 1);

        if (trackedDevices) {
            MethodNode createDeviceTracker = find(
                    node,
                    "createDeviceTracker",
                    "()Lcom/mojang/blaze3d/audio/DeviceTracker;");
            InsnList tracker = new InsnList();
            tracker.add(new TypeInsnNode(
                    Opcodes.NEW, "dev/gaius/browser/BrowserDeviceTracker"));
            tracker.add(new InsnNode(Opcodes.DUP));
            tracker.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "dev/gaius/browser/BrowserDeviceTracker",
                    "<init>",
                    "()V",
                    false));
            tracker.add(new InsnNode(Opcodes.ARETURN));
            replace(createDeviceTracker, tracker, 2, 0);
        } else {
            MethodNode getDefaultDeviceName = find(
                    node, "getDefaultDeviceName", "()Ljava/lang/String;");
            InsnList defaultName = new InsnList();
            defaultName.add(new LdcInsnNode("Gaius Browser OpenAL"));
            defaultName.add(new InsnNode(Opcodes.ARETURN));
            replace(getDefaultDeviceName, defaultName, 1, 0);

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
        }

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
        String transform = "com/mojang/blaze3d/audio/ListenerTransform";
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
        code.add(new LdcInsnNode(0x1004));
        appendListenerVector(code, transform, "position");
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "listener3f",
                "(IFFF)V",
                false));
        appendListenerVector(code, transform, "forward");
        appendListenerVector(code, transform, "up");
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "listenerOrientation",
                "(FFFFFF)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(setTransform, code, 7, 2);
        write(node, output);
    }

    private static void appendListenerVector(
            InsnList code, String transformOwner, String accessor) {
        for (String component : new String[] {"x", "y", "z"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    transformOwner,
                    accessor,
                    "()Lnet/minecraft/world/phys/Vec3;",
                    false));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/world/phys/Vec3",
                    component,
                    "D"));
            code.add(new InsnNode(Opcodes.D2F));
        }
    }

    private static void patchClientShutdownWatchdog(String jar, Path output) throws IOException {
        ClassNode node = read(
                jar, "com/mojang/blaze3d/platform/ClientShutdownWatchdog.class");
        MethodNode start = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("startShutdownWatchdog")
                    || Type.getReturnType(candidate.desc).getSort() != Type.VOID) {
                continue;
            }
            if (start != null) {
                throw new IllegalStateException("Multiple client shutdown watchdog methods found");
            }
            start = candidate;
        }
        if (start == null) {
            throw new IllegalStateException("Client shutdown watchdog method was not found");
        }
        int maxLocals = start.maxLocals;
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(start, code, 0, maxLocals);
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
        patchGameRendererBrowserFrameBudget(node);
        patchGameRendererBrowserInventoryWorldRenderThrottle(node);
        patchGameRendererBrowserTargetingAfterCamera(node);
        writeComputeFrames(node, output);
    }

    private static void patchGameRendererBrowserFrameBudget(ClassNode node) {
        MethodNode render = find(
                node,
                "render",
                "(Lnet/minecraft/client/DeltaTracker;Z)V");
        render.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginFrame",
                "()V",
                false));
        System.out.println("Patched GameRenderer frame-scoped browser upload budget");
    }

    private static void patchGameRendererBrowserTargetingAfterCamera(ClassNode node) {
        MethodNode method = null;
        MethodInsnNode cameraExtraction = null;
        for (MethodNode candidate : node.methods) {
            for (AbstractInsnNode instruction = candidate.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals("net/minecraft/client/renderer/GameRenderer")
                        || !call.name.equals("extractCamera")
                        || !(call.desc.equals("(F)V")
                                || call.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V"))) {
                    continue;
                }
                if (cameraExtraction != null) {
                    throw new IllegalStateException(
                            "GameRenderer has multiple camera extraction patch points");
                }
                method = candidate;
                cameraExtraction = call;
            }
        }
        if (method == null || cameraExtraction == null) {
            throw new IllegalStateException(
                    "GameRenderer post-camera block targeting patch point was not found");
        }

        if (cameraExtraction.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V")) {
            // Current Minecraft already runs GameRenderer.update -> Minecraft.pick ->
            // GameRenderer.extract once per rendered frame. A second post-camera raycast
            // duplicates the hottest targeting work and can desynchronize
            // crosshairPickEntity from hitResult.
            System.out.println("Verified current vanilla single-raycast block targeting");
            return;
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
        stabilize.add(new VarInsnNode(
                Opcodes.FLOAD,
                2));
        stabilize.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "stabilizeBlockHit",
                "(Lnet/minecraft/world/phys/HitResult;"
                        + "Lnet/minecraft/client/Minecraft;"
                        + "Lnet/minecraft/client/Camera;F)"
                        + "Lnet/minecraft/world/phys/HitResult;",
                false));
        stabilize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/Minecraft",
                "hitResult",
                "Lnet/minecraft/world/phys/HitResult;"));
        method.instructions.insert(cameraExtraction, stabilize);
        System.out.println("Patched block targeting after " + method.name + method.desc);
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
        throttle.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V",
                false));
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
        LabelNode remove = new LabelNode();
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
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "getParticleLimit",
                "()Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Optional",
                "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/core/particles/ParticleLimit"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, remove));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "engine",
                "Lnet/minecraft/client/particle/ParticleEngine;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/ParticleEngine",
                "updateCount",
                "(Lnet/minecraft/core/particles/ParticleLimit;I)V",
                false));
        code.add(remove);
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
        replace(method, code, 3, 4);
    }

    private static void patchParticleGroupAddNullGuard(ClassNode node) {
        String voidDescriptor = "(Lnet/minecraft/client/particle/Particle;)V";
        String booleanDescriptor = "(Lnet/minecraft/client/particle/Particle;)Z";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("add")
                        && (candidate.desc.equals(voidDescriptor)
                                || candidate.desc.equals(booleanDescriptor)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ParticleGroup.add browser null-guard target was not found"));
        LabelNode nonNull = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
        if (method.desc.equals(booleanDescriptor)) {
            guard.add(new InsnNode(Opcodes.ICONST_0));
            guard.add(new InsnNode(Opcodes.IRETURN));
        } else {
            guard.add(new InsnNode(Opcodes.RETURN));
        }
        guard.add(nonNull);
        method.instructions.insert(guard);
        method.maxStack = Math.max(method.maxStack, 1);
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
        String returnType = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("prepareChunkRenders")
                        && (candidate.desc.equals("(Lorg/joml/Matrix4fc;DDD)" + returnType)
                                || candidate.desc.equals("(Lorg/joml/Matrix4fc;)" + returnType)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LevelRenderer.prepareChunkRenders browser target was not found"));
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
        MethodNode method = node.methods.stream()
                .filter(candidate -> (candidate.name.equals("renderBlockOutline")
                                && candidate.desc.equals(
                                        "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                                                + "Lcom/mojang/blaze3d/vertex/PoseStack;Z"
                                                + "Lnet/minecraft/client/renderer/state/LevelRenderState;)V"))
                        || (candidate.name.equals("submitBlockOutline")
                                && candidate.desc.equals(
                                        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LevelRenderer block outline opacity target was not found"));
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
        MethodNode currentMethod = node.methods.stream()
                .filter(candidate -> candidate.name.equals("compileSections")
                        && candidate.desc.equals(
                                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
                .findFirst()
                .orElse(null);
        if (currentMethod != null) {
            patchCurrentLevelRendererSectionCompileThrottle(currentMethod);
            return;
        }

        MethodNode method = find(node, "compileSections", "(Lnet/minecraft/client/Camera;)V");
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
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserRenderScheduler",
                        "canScheduleSection",
                        "(I)Z",
                        false));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, afterAdd));
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

    private static void patchCurrentLevelRendererSectionCompileThrottle(MethodNode method) {
        MethodInsnNode syncCall = null;
        MethodInsnNode asyncCall = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals(
                            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection")) {
                continue;
            }
            if (call.name.equals("compileSync")
                    && call.desc.equals(
                            "(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V")) {
                syncCall = call;
            } else if (call.name.equals("compileAsync")
                    && call.desc.equals(
                            "(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V")) {
                asyncCall = call;
            }
        }
        if (syncCall == null || asyncCall == null) {
            throw new IllegalStateException(
                    "Current LevelRenderer section compile calls were not found");
        }

        boolean disabledSyncCompile = false;
        for (AbstractInsnNode cursor = syncCall;
                (cursor = previousOpcode(cursor)) != null;) {
            if (!(cursor instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                continue;
            }
            AbstractInsnNode condition = previousOpcode(jump);
            if (condition instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                method.instructions.set(load, new InsnNode(Opcodes.ICONST_0));
                disabledSyncCompile = true;
                break;
            }
        }
        if (!disabledSyncCompile) {
            throw new IllegalStateException(
                    "Current LevelRenderer synchronous compile branch was not found");
        }

        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("dev/gaius/browser/BrowserRenderScheduler")
                    && call.name.equals("canScheduleSection")) {
                throw new IllegalStateException(
                        "Current compileSections must not drop an extracted dirty section");
            }
        }
        System.out.println("Patched current section compiles to remain asynchronous");
    }

    /**
     * Applies backpressure before 26.2 clears SectionDirtyState. Skipped sections therefore stay
     * dirty and are extracted again on the next frame instead of disappearing from the pipeline.
     */
    private static void patchCurrentLevelExtractorBrowserSectionCompileThrottle(
            String jar,
            Path output) throws IOException {
        String entry = "net/minecraft/client/renderer/extract/LevelExtractor.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(entry) == null) {
                return;
            }
        }

        ClassNode node = read(jar, entry);
        MethodNode extract = find(
                node,
                "extract",
                "(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V");
        int patched = 0;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode add)
                    || add.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !add.owner.equals("java/util/List")
                    || !add.name.equals("add")
                    || !add.desc.equals("(Ljava/lang/Object;)Z")) {
                continue;
            }
            AbstractInsnNode creation = previousOpcode(add);
            if (!(creation instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals(
                            "net/minecraft/client/renderer/state/level/SectionUpdateRenderState")
                    || !constructor.name.equals("<init>")) {
                continue;
            }
            AbstractInsnNode allocation = findPreviousNew(creation, constructor.owner);
            AbstractInsnNode listFieldInstruction = previousOpcode(allocation);
            if (!(listFieldInstruction instanceof FieldInsnNode listField)
                    || listField.getOpcode() != Opcodes.GETFIELD
                    || !listField.owner.equals(
                            "net/minecraft/client/renderer/state/level/LevelRenderState")
                    || !listField.name.equals("sectionUpdateRenderStates")
                    || !listField.desc.equals("Ljava/util/List;")) {
                throw new IllegalStateException(
                        "Current section update list load shape changed");
            }
            AbstractInsnNode levelStateFieldInstruction = previousOpcode(listField);
            AbstractInsnNode thisLoadInstruction = previousOpcode(levelStateFieldInstruction);
            if (!(levelStateFieldInstruction instanceof FieldInsnNode levelStateField)
                    || levelStateField.getOpcode() != Opcodes.GETFIELD
                    || !levelStateField.owner.equals(node.name)
                    || !levelStateField.name.equals("levelRenderState")
                    || !(thisLoadInstruction instanceof VarInsnNode thisLoad)
                    || thisLoad.getOpcode() != Opcodes.ALOAD
                    || thisLoad.var != 0) {
                throw new IllegalStateException(
                        "Current section update list owner load shape changed");
            }

            AbstractInsnNode setNotDirty = nextOpcode(add);
            while (setNotDirty != null
                    && (!(setNotDirty instanceof MethodInsnNode call)
                            || !call.owner.equals(
                                    "net/minecraft/client/SectionUpdateTracker$SectionDirtyState")
                            || !call.name.equals("setNotDirty")
                            || !call.desc.equals("()V"))) {
                setNotDirty = nextOpcode(setNotDirty);
            }
            if (setNotDirty == null) {
                throw new IllegalStateException(
                        "Current section dirty clear after update extraction was not found");
            }

            LabelNode retryNextFrame = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    node.name,
                    "levelRenderState",
                    "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/state/level/LevelRenderState",
                    "sectionUpdateRenderStates",
                    "Ljava/util/List;"));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "size",
                    "()I",
                    true));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "canScheduleSection",
                    "(I)Z",
                    false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, retryNextFrame));
            extract.instructions.insertBefore(thisLoad, guard);
            extract.instructions.insert(setNotDirty, retryNextFrame);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Current section dirty-preserving throttle changed: " + patched);
        }
        writeComputeFrames(node, output);
        System.out.println("Patched current section extraction with dirty-preserving backpressure");
    }

    private static AbstractInsnNode findPreviousNew(
            AbstractInsnNode instruction,
            String owner) {
        for (AbstractInsnNode cursor = previousOpcode(instruction);
                cursor != null;
                cursor = previousOpcode(cursor)) {
            if (cursor instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(owner)) {
                return cursor;
            }
        }
        throw new IllegalStateException("Constructor allocation was not found for " + owner);
    }

    private static void patchSectionRenderDispatcherBrowserThrottles(String jar, Path output)
            throws IOException {
        ClassNode node = read(
                jar,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class");
        patchSectionRenderDispatcherBrowserExecutor(node);
        MethodNode currentUpload = node.methods.stream()
                .filter(candidate -> candidate.name.equals("uploadTerrainBuffersToGpu")
                        && candidate.desc.equals("()V"))
                .findFirst()
                .orElse(null);
        if (currentUpload != null) {
            patchCurrentSectionRenderDispatcherBufferBudgets(node);
            patchCurrentSectionRenderDispatcherTelemetry(currentUpload);
            int uploadCalls = 0;
            for (AbstractInsnNode instruction : currentUpload.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("com/mojang/blaze3d/vertex/UberGpuBuffer")
                        && call.name.equals("uploadStagedAllocations")) {
                    uploadCalls++;
                }
            }
            if (uploadCalls != 2) {
                throw new IllegalStateException(
                        "Current terrain upload batching shape changed: " + uploadCalls);
            }
            System.out.println(
                    "Verified current staged terrain uploads behind the browser compile budget");
            writeComputeFrames(node, output);
            return;
        }
        MethodNode method = find(node, "uploadAllPendingUploads", "()V");
        InsnList code = new InsnList();
        LabelNode uploadLoop = new LabelNode();
        LabelNode uploadDone = new LabelNode();
        LabelNode closeLoop = new LabelNode();
        LabelNode closeDone = new LabelNode();

        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUploadPass",
                "(I)V",
                false));
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "endUploadPass",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));

        replace(method, code, 3, 5);
        writeComputeFrames(node, output);
    }

    private static void patchSectionRenderDispatcherBrowserExecutor(ClassNode node) {
        String currentConstructorDescriptor =
                "(Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler;"
                        + "Ljava/util/function/Consumer;)V";
        boolean current = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("<init>")
                        && candidate.desc.equals(currentConstructorDescriptor));
        if (current) {
            MethodNode schedule = find(
                    node,
                    "schedule",
                    "(Lnet/minecraft/client/renderer/chunk/"
                            + "SectionRenderDispatcher$RenderSection$SectionTask;)V");
            MethodNode runTask = find(node, "runTask", "()V");
            MethodInsnNode scheduleCall = findTracingExecutorCall(schedule);
            MethodInsnNode continuationCall = findTracingExecutorCall(runTask);

            InsnList scheduleContext = new InsnList();
            scheduleContext.add(new VarInsnNode(Opcodes.ALOAD, 0));
            scheduleContext.add(new VarInsnNode(Opcodes.ALOAD, 0));
            scheduleContext.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    node.name,
                    "getCompileQueueSize",
                    "()I",
                    false));
            schedule.instructions.insertBefore(scheduleCall, scheduleContext);
            schedule.instructions.set(scheduleCall, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "scheduleDispatcher",
                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Object;I)V",
                    false));

            runTask.instructions.insertBefore(
                    continuationCall,
                    new VarInsnNode(Opcodes.ALOAD, 0));
            runTask.instructions.set(continuationCall, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "rememberDispatcherContinuation",
                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Object;)V",
                    false));

            int finishedReturns = 0;
            for (AbstractInsnNode instruction : runTask.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList finish = new InsnList();
                finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
                finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
                finish.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        node.name,
                        "getCompileQueueSize",
                        "()I",
                        false));
                finish.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserRenderScheduler",
                        "finishDispatcherRun",
                        "(Ljava/lang/Object;I)V",
                        false));
                runTask.instructions.insertBefore(instruction, finish);
                finishedReturns++;
            }
            if (finishedReturns != 3) {
                throw new IllegalStateException(
                        "Current section runner return shape changed: " + finishedReturns);
            }

            MethodNode dispose = find(node, "dispose", "()V");
            InsnList release = new InsnList();
            release.add(new VarInsnNode(Opcodes.ALOAD, 0));
            release.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "disposeDispatcher",
                    "(Ljava/lang/Object;)V",
                    false));
            dispose.instructions.insertBefore(dispose.instructions.getFirst(), release);
            System.out.println("Patched current section renderer with one lifecycle-owned runner");
            return;
        }

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

    private static MethodInsnNode findTracingExecutorCall(MethodNode method) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/TracingExecutor")
                    || !call.name.equals("execute")
                    || !call.desc.equals("(Ljava/lang/Runnable;)V")) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException(
                        method.name + method.desc + " has multiple TracingExecutor calls");
            }
            found = call;
        }
        if (found == null) {
            throw new IllegalStateException(
                    method.name + method.desc + " TracingExecutor call was not found");
        }
        return found;
    }

    private static void patchCurrentSectionTaskQueueBrowserPriorities(String jar, Path output)
            throws IOException {
        String entry = "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(entry) == null) {
                return;
            }
        }
        ClassNode node = read(jar, entry);
        MethodNode poll = findNullable(
                node,
                "poll",
                "(Lnet/minecraft/world/phys/Vec3;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$SectionTask;");
        if (poll == null) {
            return;
        }
        String owner = "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue";
        String task = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$SectionTask";
        String compileTask = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$CompileTask";
        String taskDescriptor = "L" + task + ";";
        String queueDescriptor = "Ljava/util/ArrayList;";
        String cameraDescriptor = "Lnet/minecraft/world/phys/Vec3;";

        FieldNode tasks = node.fields.stream()
                .filter(field -> field.name.equals("tasks")
                        && field.desc.equals("Ljava/util/List;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current section task list field shape changed"));
        tasks.desc = queueDescriptor;
        tasks.signature = "Ljava/util/ArrayList<" + taskDescriptor + ">;";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserDirtyTasks",
                queueDescriptor,
                "Ljava/util/ArrayList<" + taskDescriptor + ">;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;",
                "Ljava/util/IdentityHashMap<" + taskDescriptor + "[J>;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserScratchTasks",
                "Ljava/util/ArrayList;",
                "Ljava/util/ArrayList<" + taskDescriptor + ">;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserCamera", cameraDescriptor, null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserNextOrder", "J", null, null));
        MethodNode constructor = find(node, "<init>", "()V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new InsnNode(Opcodes.ICONST_2));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        addSectionTaskArrayListInitialization(
                constructorCode, owner, "tasks", queueDescriptor);
        addSectionTaskArrayListInitialization(
                constructorCode, owner, "browserDirtyTasks", queueDescriptor);
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/IdentityHashMap",
                "<init>",
                "()V",
                false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserScratchTasks",
                "Ljava/util/ArrayList;"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 5, 1);

        MethodNode add = find(node, "add", "(" + taskDescriptor + ")V");
        InsnList addCode = new InsnList();
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserNextOrder", "J"));
        addCode.add(new VarInsnNode(Opcodes.LSTORE, 2));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        addCode.add(new InsnNode(Opcodes.LCONST_1));
        addCode.add(new InsnNode(Opcodes.LADD));
        addCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserNextOrder", "J"));
        addCode.add(new InsnNode(Opcodes.ICONST_5));
        addCode.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        addCode.add(new VarInsnNode(Opcodes.ASTORE, 4));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                task,
                "getRenderOrigin",
                "()Lnet/minecraft/core/BlockPos;",
                false));
        addCode.add(new VarInsnNode(Opcodes.ASTORE, 5));
        int coordinateIndex = 0;
        for (String coordinate : new String[] {"getX", "getY", "getZ"}) {
            addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
            addCode.add(new InsnNode(Opcodes.ICONST_0 + coordinateIndex++));
            addCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
            addCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/BlockPos",
                    coordinate,
                    "()I",
                    false));
            addCode.add(new InsnNode(Opcodes.I2L));
            addCode.add(new InsnNode(Opcodes.LASTORE));
        }
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        addCode.add(new InsnNode(Opcodes.ICONST_3));
        addCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        addCode.add(new InsnNode(Opcodes.LASTORE));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        addCode.add(new InsnNode(Opcodes.POP));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                false));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "browserIsDirtyCompile",
                "(" + taskDescriptor + ")Z",
                false));
        LabelNode addRegular = new LabelNode();
        LabelNode addSelected = new LabelNode();
        addCode.add(new JumpInsnNode(Opcodes.IFEQ, addRegular));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        addCode.add(new JumpInsnNode(Opcodes.GOTO, addSelected));
        addCode.add(addRegular);
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        addCode.add(addSelected);
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        addCode.add(new InsnNode(Opcodes.RETURN));
        replace(add, addCode, 6, 6);

        InsnList pollCode = new InsnList();
        LabelNode rebuildCamera = new LabelNode();
        LabelNode heapsReady = new LabelNode();
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, rebuildCamera));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(Lnet/minecraft/world/phys/Vec3;)D",
                false));
        pollCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new LdcInsnNode(
                BROWSER_SECTION_QUEUE_REBUILD_DISTANCE
                        * BROWSER_SECTION_QUEUE_REBUILD_DISTANCE));
        pollCode.add(new InsnNode(Opcodes.DCMPG));
        pollCode.add(new JumpInsnNode(Opcodes.IFGE, rebuildCamera));
        pollCode.add(new JumpInsnNode(Opcodes.GOTO, heapsReady));
        pollCode.add(rebuildCamera);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserCamera", cameraDescriptor));
        addSectionTaskQueueHelperCall(
                pollCode, owner, "tasks", "browserRebuild", "(" + queueDescriptor + ")V");
        addSectionTaskQueueHelperCall(
                pollCode,
                owner,
                "browserDirtyTasks",
                "browserRebuild",
                "(" + queueDescriptor + ")V");
        pollCode.add(new InsnNode(Opcodes.DCONST_0));
        pollCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
        pollCode.add(heapsReady);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                false));
        pollCode.add(new VarInsnNode(Opcodes.ASTORE, 4));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                false));
        pollCode.add(new VarInsnNode(Opcodes.ASTORE, 5));
        LabelNode chooseRegular = new LabelNode();
        LabelNode chooseDirty = new LabelNode();
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, chooseRegular));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, chooseDirty));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new JumpInsnNode(Opcodes.IFLE, chooseRegular));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        pollCode.add(new InsnNode(Opcodes.DCMPG));
        pollCode.add(new JumpInsnNode(Opcodes.IFLT, chooseDirty));
        pollCode.add(chooseRegular);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new InsnNode(Opcodes.ICONST_2));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                false));
        pollCode.add(new InsnNode(Opcodes.ARETURN));
        pollCode.add(chooseDirty);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new InsnNode(Opcodes.DUP));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new InsnNode(Opcodes.ICONST_1));
        pollCode.add(new InsnNode(Opcodes.ISUB));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                false));
        pollCode.add(new InsnNode(Opcodes.ARETURN));
        replace(poll, pollCode, 6, 6);

        MethodNode size = find(node, "size", "()I");
        InsnList sizeCode = new InsnList();
        sizeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sizeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        sizeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        sizeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sizeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        sizeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        sizeCode.add(new InsnNode(Opcodes.IADD));
        sizeCode.add(new InsnNode(Opcodes.IRETURN));
        replace(size, sizeCode, 2, 1);

        MethodNode clear = find(node, "clear", "()V");
        InsnList clearCode = new InsnList();
        addSectionTaskQueueHelperCall(
                clearCode,
                owner,
                "tasks",
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V");
        addSectionTaskQueueHelperCall(
                clearCode,
                owner,
                "browserDirtyTasks",
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V");
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        clearCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "clear",
                "()V",
                false));
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        clearCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new InsnNode(Opcodes.LCONST_0));
        clearCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserNextOrder", "J"));
        clearCode.add(new InsnNode(Opcodes.RETURN));
        replace(clear, clearCode, 3, 1);

        MethodNode removeByIndex = find(
                node, "removeTaskByIndex", "(I)" + taskDescriptor);
        node.methods.remove(removeByIndex);
        addSectionTaskQueueHelpers(
                node, owner, task, compileTask, taskDescriptor, queueDescriptor, cameraDescriptor);
        writeComputeFrames(node, output);
        System.out.println(
                "Patched current section queue with linear-rebuild dirty/regular heaps");
    }

    private static void addSectionTaskArrayListInitialization(
            InsnList code, String owner, String field, String queueDescriptor) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 11));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(I)V",
                false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, queueDescriptor));
    }

    private static void addSectionTaskQueueHelperCall(
            InsnList code,
            String owner,
            String field,
            String method,
            String descriptor) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, field, "Ljava/util/ArrayList;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, method, descriptor, false));
    }

    private static void addSectionTaskQueueHelpers(
            ClassNode node,
            String owner,
            String task,
            String compileTask,
            String taskDescriptor,
            String queueDescriptor,
            String cameraDescriptor) {
        MethodNode dirty = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "browserIsDirtyCompile",
                "(" + taskDescriptor + ")Z",
                null,
                null);
        LabelNode notDirty = new LabelNode();
        dirty.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dirty.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, task, "isRecompile", "()Z", false));
        dirty.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notDirty));
        dirty.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dirty.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, compileTask));
        dirty.instructions.add(new InsnNode(Opcodes.IRETURN));
        dirty.instructions.add(notDirty);
        dirty.instructions.add(new InsnNode(Opcodes.ICONST_0));
        dirty.instructions.add(new InsnNode(Opcodes.IRETURN));
        dirty.maxStack = 1;
        dirty.maxLocals = 1;
        node.methods.add(dirty);

        MethodNode distance = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                null,
                null);
        distance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        distance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        distance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        distance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        distance.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        distance.instructions.add(new InsnNode(Opcodes.ICONST_4));
        distance.instructions.add(new InsnNode(Opcodes.LALOAD));
        distance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "longBitsToDouble",
                "(J)D",
                false));
        distance.instructions.add(new InsnNode(Opcodes.DRETURN));
        distance.maxStack = 2;
        distance.maxLocals = 2;
        node.methods.add(distance);

        MethodNode updateDistance = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                null,
                null);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        updateDistance.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        LabelNode updateHasCamera = new LabelNode();
        LabelNode updateStore = new LabelNode();
        updateDistance.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, updateHasCamera));
        updateDistance.instructions.add(new InsnNode(Opcodes.DCONST_0));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DSTORE, 3));
        updateDistance.instructions.add(new JumpInsnNode(Opcodes.GOTO, updateStore));
        updateDistance.instructions.add(updateHasCamera);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        int updateCoordinateIndex = 0;
        for (int ignored = 0; ignored < 3; ignored++) {
            updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            updateDistance.instructions.add(new InsnNode(
                    Opcodes.ICONST_0 + updateCoordinateIndex++));
            updateDistance.instructions.add(new InsnNode(Opcodes.LALOAD));
            updateDistance.instructions.add(new InsnNode(Opcodes.L2I));
            updateDistance.instructions.add(new InsnNode(Opcodes.I2D));
            updateDistance.instructions.add(new LdcInsnNode(0.5D));
            updateDistance.instructions.add(new InsnNode(Opcodes.DADD));
        }
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(DDD)D",
                false));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DSTORE, 3));
        updateDistance.instructions.add(updateStore);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        updateDistance.instructions.add(new InsnNode(Opcodes.ICONST_4));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DLOAD, 3));
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "doubleToRawLongBits",
                "(D)J",
                false));
        updateDistance.instructions.add(new InsnNode(Opcodes.LASTORE));
        updateDistance.instructions.add(new InsnNode(Opcodes.RETURN));
        updateDistance.maxStack = 9;
        updateDistance.maxLocals = 5;
        node.methods.add(updateDistance);

        MethodNode distanceFrom = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                null,
                null);
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        distanceFrom.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        distanceFrom.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        distanceFrom.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        int currentCoordinateIndex = 0;
        for (int ignored = 0; ignored < 3; ignored++) {
            distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            distanceFrom.instructions.add(new InsnNode(
                    Opcodes.ICONST_0 + currentCoordinateIndex++));
            distanceFrom.instructions.add(new InsnNode(Opcodes.LALOAD));
            distanceFrom.instructions.add(new InsnNode(Opcodes.L2I));
            distanceFrom.instructions.add(new InsnNode(Opcodes.I2D));
            distanceFrom.instructions.add(new LdcInsnNode(0.5D));
            distanceFrom.instructions.add(new InsnNode(Opcodes.DADD));
        }
        distanceFrom.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(DDD)D",
                false));
        distanceFrom.instructions.add(new InsnNode(Opcodes.DRETURN));
        distanceFrom.maxStack = 9;
        distanceFrom.maxLocals = 4;
        node.methods.add(distanceFrom);

        MethodNode order = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                null,
                null);
        order.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        order.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        order.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        order.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        order.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        order.instructions.add(new InsnNode(Opcodes.ICONST_3));
        order.instructions.add(new InsnNode(Opcodes.LALOAD));
        order.instructions.add(new InsnNode(Opcodes.LRETURN));
        order.maxStack = 2;
        order.maxLocals = 2;
        node.methods.add(order);

        MethodNode compare = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                null,
                null);
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        compare.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        compare.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        compare.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        compare.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false));
        compare.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        LabelNode compareOrder = new LabelNode();
        compare.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        compare.instructions.add(new JumpInsnNode(Opcodes.IFEQ, compareOrder));
        compare.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        compare.instructions.add(new InsnNode(Opcodes.IRETURN));
        compare.instructions.add(compareOrder);
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Long", "compare", "(JJ)I", false));
        compare.instructions.add(new InsnNode(Opcodes.IRETURN));
        compare.maxStack = 5;
        compare.maxLocals = 6;
        node.methods.add(compare);

        MethodNode heapAdd = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                null,
                null);
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "add",
                "(Ljava/lang/Object;)Z",
                false));
        heapAdd.instructions.add(new InsnNode(Opcodes.POP));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftUp",
                "(" + queueDescriptor + "I)V",
                false));
        heapAdd.instructions.add(new InsnNode(Opcodes.RETURN));
        heapAdd.maxStack = 3;
        heapAdd.maxLocals = 4;
        node.methods.add(heapAdd);

        MethodNode heapPoll = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                null,
                null);
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ISUB));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode heapPollEmpty = new LabelNode();
        LabelNode heapPollDone = new LabelNode();
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new JumpInsnNode(Opcodes.IFLT, heapPollEmpty));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "remove",
                "(I)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new JumpInsnNode(Opcodes.IFLE, heapPollDone));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new InsnNode(Opcodes.POP));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                false));
        heapPoll.instructions.add(heapPollDone);
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        heapPoll.instructions.add(new InsnNode(Opcodes.ARETURN));
        heapPoll.instructions.add(heapPollEmpty);
        heapPoll.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        heapPoll.instructions.add(new InsnNode(Opcodes.ARETURN));
        heapPoll.maxStack = 3;
        heapPoll.maxLocals = 5;
        node.methods.add(heapPoll);

        MethodNode siftUp = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserSiftUp",
                "(" + queueDescriptor + "I)V",
                null,
                null);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode siftUpLoop = new LabelNode();
        LabelNode siftUpDone = new LabelNode();
        siftUp.instructions.add(siftUpLoop);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.IFLE, siftUpDone));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftUp.instructions.add(new InsnNode(Opcodes.ISUB));
        siftUp.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftUp.instructions.add(new InsnNode(Opcodes.IUSHR));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ISTORE, 4));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.IFGE, siftUpDone));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new InsnNode(Opcodes.POP));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.GOTO, siftUpLoop));
        siftUp.instructions.add(siftUpDone);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new InsnNode(Opcodes.POP));
        siftUp.instructions.add(new InsnNode(Opcodes.RETURN));
        siftUp.maxStack = 3;
        siftUp.maxLocals = 6;
        node.methods.add(siftUp);

        MethodNode siftDown = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                null,
                null);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IUSHR));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        LabelNode siftDownLoop = new LabelNode();
        LabelNode siftDownDone = new LabelNode();
        LabelNode siftDownChildReady = new LabelNode();
        siftDown.instructions.add(siftDownLoop);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, siftDownDone));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.ISHL));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IADD));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IADD));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, siftDownChildReady));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IFGE, siftDownChildReady));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        siftDown.instructions.add(siftDownChildReady);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IFLE, siftDownDone));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new InsnNode(Opcodes.POP));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.GOTO, siftDownLoop));
        siftDown.instructions.add(siftDownDone);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new InsnNode(Opcodes.POP));
        siftDown.instructions.add(new InsnNode(Opcodes.RETURN));
        siftDown.maxStack = 3;
        siftDown.maxLocals = 10;
        node.methods.add(siftDown);

        MethodNode rebuild = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserRebuild",
                "(" + queueDescriptor + ")V",
                null,
                null);
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode updateLoop = new LabelNode();
        LabelNode updateDone = new LabelNode();
        rebuild.instructions.add(updateLoop);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "size",
                "()I",
                false));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, updateDone));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                false));
        rebuild.instructions.add(new IincInsnNode(2, 1));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.GOTO, updateLoop));
        rebuild.instructions.add(updateDone);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_1));
        rebuild.instructions.add(new InsnNode(Opcodes.IUSHR));
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_1));
        rebuild.instructions.add(new InsnNode(Opcodes.ISUB));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode heapifyLoop = new LabelNode();
        LabelNode heapifyDone = new LabelNode();
        rebuild.instructions.add(heapifyLoop);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFLT, heapifyDone));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                false));
        rebuild.instructions.add(new IincInsnNode(2, -1));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.GOTO, heapifyLoop));
        rebuild.instructions.add(heapifyDone);
        rebuild.instructions.add(new InsnNode(Opcodes.RETURN));
        rebuild.maxStack = 3;
        rebuild.maxLocals = 3;
        node.methods.add(rebuild);

        // A squared triangle-inequality bound keeps selection exact without sqrt while the
        // heap remains ordered around its last camera anchor.
        MethodNode takeNearest = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                null,
                null);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        takeNearest.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        takeNearest.instructions.add(new LdcInsnNode(Double.MAX_VALUE));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 6));
        LabelNode takeLoop = new LabelNode();
        LabelNode takeLive = new LabelNode();
        LabelNode takeInspect = new LabelNode();
        LabelNode takeUpdate = new LabelNode();
        LabelNode takeContinue = new LabelNode();
        LabelNode takeDone = new LabelNode();
        takeNearest.instructions.add(takeLoop);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "isEmpty",
                "()Z",
                false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFNE, takeDone));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new InsnNode(Opcodes.ICONST_0));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                task,
                "isCancelled",
                "Ljava/util/concurrent/atomic/AtomicBoolean;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicBoolean",
                "get",
                "()Z",
                false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFEQ, takeLive));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, takeLoop));
        takeNearest.instructions.add(takeLive);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        takeNearest.instructions.add(new LdcInsnNode(0.5D));
        takeNearest.instructions.add(new InsnNode(Opcodes.DMUL));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 3));
        takeNearest.instructions.add(new InsnNode(Opcodes.DSUB));
        takeNearest.instructions.add(new LdcInsnNode(BROWSER_SECTION_QUEUE_DISTANCE_EPSILON));
        takeNearest.instructions.add(new InsnNode(Opcodes.DSUB));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 9));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFNULL, takeInspect));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 9));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 6));
        takeNearest.instructions.add(new InsnNode(Opcodes.DCMPG));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGT, takeDone));
        takeNearest.instructions.add(takeInspect);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "add",
                "(Ljava/lang/Object;)Z",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 6));
        takeNearest.instructions.add(new InsnNode(Opcodes.DCMPG));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ISTORE, 13));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFLT, takeUpdate));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGT, takeContinue));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.LCMP));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGE, takeContinue));
        takeNearest.instructions.add(takeUpdate);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 6));
        takeNearest.instructions.add(takeContinue);
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, takeLoop));
        takeNearest.instructions.add(takeDone);
        takeNearest.instructions.add(new InsnNode(Opcodes.ICONST_0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ISTORE, 13));
        LabelNode restoreLoop = new LabelNode();
        LabelNode restoreDone = new LabelNode();
        LabelNode skipBest = new LabelNode();
        takeNearest.instructions.add(restoreLoop);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, restoreDone));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, skipBest));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        takeNearest.instructions.add(skipBest);
        takeNearest.instructions.add(new IincInsnNode(13, 1));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, restoreLoop));
        takeNearest.instructions.add(restoreDone);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new InsnNode(Opcodes.ARETURN));
        takeNearest.maxStack = 5;
        takeNearest.maxLocals = 14;
        node.methods.add(takeNearest);

        MethodNode requeue = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                null,
                null);
        LabelNode requeueDone = new LabelNode();
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        requeue.instructions.add(new JumpInsnNode(Opcodes.IFNULL, requeueDone));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        requeue.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        requeue.instructions.add(requeueDone);
        requeue.instructions.add(new InsnNode(Opcodes.RETURN));
        requeue.maxStack = 2;
        requeue.maxLocals = 3;
        node.methods.add(requeue);

        MethodNode finishTask = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                null,
                null);
        LabelNode finishDone = new LabelNode();
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new JumpInsnNode(Opcodes.IFNULL, finishDone));
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finishTask.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        finishTask.instructions.add(new InsnNode(Opcodes.POP));
        finishTask.instructions.add(finishDone);
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new InsnNode(Opcodes.ARETURN));
        finishTask.maxStack = 2;
        finishTask.maxLocals = 2;
        node.methods.add(finishTask);

        MethodNode cancelAndClear = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V",
                null,
                null);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "toArray",
                "()[Ljava/lang/Object;",
                false));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.ICONST_0));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode cancelLoop = new LabelNode();
        LabelNode cancelDone = new LabelNode();
        cancelAndClear.instructions.add(cancelLoop);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        cancelAndClear.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, cancelDone));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.AALOAD));
        cancelAndClear.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, task, "cancel", "()V", false));
        cancelAndClear.instructions.add(new IincInsnNode(3, 1));
        cancelAndClear.instructions.add(new JumpInsnNode(Opcodes.GOTO, cancelLoop));
        cancelAndClear.instructions.add(cancelDone);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.RETURN));
        cancelAndClear.maxStack = 2;
        cancelAndClear.maxLocals = 4;
        node.methods.add(cancelAndClear);
    }

    private static void patchCurrentSectionRenderDispatcherBufferBudgets(ClassNode node) {
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler;"
                        + "Ljava/util/function/Consumer;)V");
        int vertexHeap = 0;
        int indexHeap = 0;
        int staging = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !(constant.cst instanceof Integer value)) {
                continue;
            }
            if (value == 134217728) {
                constant.cst = BROWSER_SECTION_VERTEX_HEAP_BYTES;
                vertexHeap++;
            } else if (value == 33554432) {
                constant.cst = BROWSER_SECTION_INDEX_HEAP_BYTES;
                indexHeap++;
            } else if (value == 102760448) {
                constant.cst = BROWSER_SECTION_STAGING_BYTES;
                staging++;
            }
        }
        if (vertexHeap != 1 || indexHeap != 1 || staging != 2) {
            throw new IllegalStateException(
                    "Current section renderer buffer budget constants changed: vertex="
                            + vertexHeap + ", index=" + indexHeap + ", staging=" + staging);
        }
        System.out.println("Reduced current section renderer browser allocation units");
    }

    private static void patchCurrentSectionRenderDispatcherTelemetry(MethodNode upload) {
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "getCompileQueueSize",
                "()I",
                false));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUploadPass",
                "(I)V",
                false));
        upload.instructions.insert(begin);

        int returns = 0;
        int terminalThrows = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode != Opcodes.RETURN && opcode != Opcodes.ATHROW) {
                continue;
            }
            InsnList end = new InsnList();
            end.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "endUploadPass",
                    "()V",
                    false));
            upload.instructions.insertBefore(instruction, end);
            if (opcode == Opcodes.RETURN) {
                returns++;
            } else {
                terminalThrows++;
            }
        }
        if (returns != 1 || terminalThrows != 1) {
            throw new IllegalStateException(
                    "Current terrain upload telemetry control flow changed: returns="
                            + returns + ", throws=" + terminalThrows);
        }
        upload.maxStack = Math.max(upload.maxStack, 1);
        System.out.println("Instrumented current section compile and upload telemetry");
    }

    private static void patchUberGpuBufferBrowserTelemetry(String jar, Path output)
            throws IOException {
        String entry = "com/mojang/blaze3d/vertex/UberGpuBuffer.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(entry) == null) {
                return;
            }
        }
        ClassNode node = read(jar, entry);
        patchCurrentUberGpuBufferUploadBudget(node);
        patchUberGpuBufferBrowserLifecycle(node);
        MethodNode addAllocation = find(
                node,
                "addAllocation",
                "(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/"
                        + "UberGpuBuffer$UploadCallback;Ljava/nio/ByteBuffer;)Z");
        MethodNode uploadAllocations = find(
                node,
                "uploadStagedAllocations",
                "(Lcom/mojang/blaze3d/systems/GpuDevice;"
                        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z");
        int addReturns = instrumentUberGpuBufferBacklogReturns(node, addAllocation);
        int uploadReturns = instrumentUberGpuBufferBacklogReturns(node, uploadAllocations);
        if (addReturns != 2 || uploadReturns != 1) {
            throw new IllegalStateException(
                    "UberGpuBuffer telemetry return shape changed: add="
                            + addReturns + ", upload=" + uploadReturns);
        }
        writeComputeFrames(node, output);
        System.out.println("Instrumented staged terrain upload backlog telemetry");
    }

    private static void patchCurrentUberGpuBufferUploadBudget(ClassNode node) {
        MethodNode upload = find(
                node,
                "uploadStagedAllocations",
                "(Lcom/mojang/blaze3d/systems/GpuDevice;"
                        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z");
        MethodInsnNode initialFree = null;
        MethodInsnNode keySet = null;
        MethodInsnNode entrySet = null;
        MethodInsnNode stagedClear = null;
        MethodInsnNode skippedClear = null;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals(node.name)
                    && call.name.equals("freeAllocation")
                    && call.desc.equals("(Ljava/lang/Object;)V")) {
                if (initialFree == null) {
                    initialFree = call;
                }
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("keySet")) {
                keySet = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("entrySet")) {
                entrySet = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("clear")) {
                stagedClear = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet")
                    && call.name.equals("clear")) {
                skippedClear = call;
            }
        }
        if (initialFree == null || keySet == null || entrySet == null
                || stagedClear == null || skippedClear == null) {
            throw new IllegalStateException("Current UberGpuBuffer upload structure changed");
        }

        JumpInsnNode initialLoopExit = null;
        for (AbstractInsnNode instruction = keySet;
                instruction != null && instruction != initialFree;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")
                    && nextOpcode(call) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ) {
                initialLoopExit = jump;
                break;
            }
        }
        AbstractInsnNode initialMapLoad = previousOpcode(previousOpcode(keySet));
        if (initialLoopExit == null
                || !(initialMapLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0) {
            throw new IllegalStateException("Current UberGpuBuffer initial free loop changed");
        }
        upload.instructions.insertBefore(
                initialMapLoad,
                new JumpInsnNode(Opcodes.GOTO, initialLoopExit.label));

        VarInsnNode entryIteratorStore = null;
        MethodInsnNode entryHasNext = null;
        for (AbstractInsnNode instruction = entrySet.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (entryIteratorStore == null
                    && instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                entryIteratorStore = store;
                continue;
            }
            if (entryIteratorStore != null
                    && instruction instanceof MethodInsnNode call
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")) {
                entryHasNext = call;
                break;
            }
        }
        if (entryIteratorStore == null
                || entryHasNext == null
                || !(nextOpcode(entryHasNext) instanceof JumpInsnNode entryLoopExit)
                || entryLoopExit.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("Current UberGpuBuffer staged entry loop changed");
        }

        LabelNode entryLoop = null;
        AbstractInsnNode iteratorLoad = previousOpcode(entryHasNext);
        for (AbstractInsnNode cursor = iteratorLoad.getPrevious(); cursor != null;
                cursor = cursor.getPrevious()) {
            if (cursor instanceof LabelNode label) {
                entryLoop = label;
                break;
            }
            if (cursor.getOpcode() >= 0) {
                break;
            }
        }
        if (entryLoop == null) {
            throw new IllegalStateException("Current UberGpuBuffer staged loop label changed");
        }

        InsnList budgetGuard = new InsnList();
        budgetGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        budgetGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "shouldUploadNext",
                "(Ljava/lang/Object;)Z",
                false));
        budgetGuard.add(new JumpInsnNode(Opcodes.IFEQ, entryLoopExit.label));
        upload.instructions.insert(entryLoopExit, budgetGuard);

        VarInsnNode entryStore = null;
        for (AbstractInsnNode instruction = entryHasNext.getNext();
                instruction != null && instruction != entryLoopExit.label;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                entryStore = store;
                break;
            }
        }
        if (entryStore == null) {
            throw new IllegalStateException("Current UberGpuBuffer staged entry local changed");
        }
        InsnList freeSelected = new InsnList();
        freeSelected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeSelected.add(new VarInsnNode(Opcodes.ALOAD, entryStore.var));
        freeSelected.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        freeSelected.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                node.name,
                "freeAllocation",
                "(Ljava/lang/Object;)V",
                false));
        upload.instructions.insert(entryStore, freeSelected);

        LabelNode processedEntry = new LabelNode();
        InsnList removeProcessed = new InsnList();
        removeProcessed.add(processedEntry);
        removeProcessed.add(new VarInsnNode(Opcodes.ALOAD, entryIteratorStore.var));
        removeProcessed.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        removeProcessed.add(new JumpInsnNode(Opcodes.GOTO, entryLoop));
        upload.instructions.insertBefore(entryLoopExit.label, removeProcessed);

        int redirectedCompletions = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.label == entryLoop
                    && upload.instructions.indexOf(jump) > upload.instructions.indexOf(entryStore)
                    && upload.instructions.indexOf(jump) < upload.instructions.indexOf(processedEntry)) {
                jump.label = processedEntry;
                redirectedCompletions++;
            }
        }
        if (redirectedCompletions != 5) {
            throw new IllegalStateException(
                    "Current UberGpuBuffer staged completion shape changed: "
                            + redirectedCompletions);
        }

        AbstractInsnNode stagedClearReceiver = previousOpcode(previousOpcode(stagedClear));
        if (!(stagedClearReceiver instanceof VarInsnNode clearReceiverLoad)
                || clearReceiverLoad.getOpcode() != Opcodes.ALOAD
                || clearReceiverLoad.var != 0) {
            throw new IllegalStateException("Current UberGpuBuffer staged clear receiver changed");
        }
        InsnList preservePending = new InsnList();
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "stagedAllocations",
                "Lit/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;"));
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "skippedStagedAllocations",
                "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;"));
        preservePending.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "finishUploadBuffer",
                "(Ljava/lang/Object;Ljava/util/Map;Ljava/util/Set;)V",
                false));
        upload.instructions.insertBefore(stagedClearReceiver, preservePending);
        upload.instructions.set(stagedClear, new InsnNode(Opcodes.POP));
        upload.instructions.set(skippedClear, new InsnNode(Opcodes.POP));
        upload.maxStack = Math.max(upload.maxStack, 4);
        System.out.println("Patched current staged terrain uploads with a resumable frame budget");
    }

    private static void patchUberGpuBufferBrowserLifecycle(ClassNode node) {
        MethodNode close = find(node, "close", "()V");
        InsnList release = new InsnList();
        release.add(new VarInsnNode(Opcodes.ALOAD, 0));
        release.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "releaseUploadBuffer",
                "(Ljava/lang/Object;)V",
                false));
        close.instructions.insertBefore(close.instructions.getFirst(), release);
    }

    private static int instrumentUberGpuBufferBacklogReturns(
            ClassNode owner,
            MethodNode method) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            InsnList telemetry = new InsnList();
            telemetry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            telemetry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            telemetry.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner.name,
                    "stagedAllocations",
                    "Lit/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;"));
            telemetry.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap",
                    "size",
                    "()I",
                    false));
            telemetry.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "recordUploadBacklogResult",
                    "(ZLjava/lang/Object;I)Z",
                    false));
            method.instructions.insertBefore(instruction, telemetry);
            patched++;
        }
        method.maxStack = Math.max(method.maxStack, 3);
        return patched;
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
        MethodNode render = null;
        for (MethodNode candidate : node.methods) {
            boolean hasThreadPool = false;
            boolean hasSubmit = false;
            for (AbstractInsnNode instruction : candidate.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(
                                "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList")
                        && field.name.equals("THREAD_POOL")) {
                    hasThreadPool = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("java/util/concurrent/ThreadPoolExecutor")
                        && call.name.equals("submit")
                        && call.desc.equals(
                                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;")) {
                    hasSubmit = true;
                }
            }
            if (!hasThreadPool || !hasSubmit) {
                continue;
            }
            if (render != null) {
                throw new IllegalStateException(
                        "Multiple multiplayer server ping executor methods were found");
            }
            render = candidate;
        }
        if (render == null) {
            throw new IllegalStateException("Multiplayer server ping executor method was not found");
        }
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

    private static void patchDetectedVersion(String jar, Path output, String minecraftVersion)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/DetectedVersion.class");
        MethodNode detect = find(
                node, "tryDetectVersion", "()Lnet/minecraft/WorldVersion;");
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(minecraftVersion));
        code.add(new LdcInsnNode(minecraftVersion));
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

        boolean bootstrapCheckpointPatched = false;
        boolean bootstrapValidatedPatched = false;
        boolean settingsCheckpointPatched = false;
        boolean packRepositoryCheckpointPatched = false;
        boolean worldLoadConfigCheckpointPatched = false;
        boolean worldLoadStartedPatched = false;
        boolean datapackCheckpointPatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (!bootstrapCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("bootStrap")
                    && call.desc.equals("()V")) {
                InsnList checkpoint = new InsnList();
                checkpoint.add(new LdcInsnNode("bootstrap-complete"));
                checkpoint.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserStartupScheduler",
                        "phase",
                        "(Ljava/lang/String;)V",
                        false));
                main.instructions.insert(call, checkpoint);
                bootstrapCheckpointPatched = true;
                continue;
            }
            if (!bootstrapValidatedPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("validate")
                    && call.desc.equals("()V")) {
                main.instructions.insert(call, serverStartupPhase("bootstrap-validated"));
                bootstrapValidatedPatched = true;
                continue;
            }
            if (!settingsCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/dedicated/DedicatedServerSettings")
                    && call.name.equals("forceSave")
                    && call.desc.equals("()V")) {
                main.instructions.insert(call, serverStartupPhase("server-settings-ready"));
                settingsCheckpointPatched = true;
                continue;
            }
            if (!packRepositoryCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/packs/repository/ServerPacksSource")
                    && call.name.equals("createPackRepository")) {
                AbstractInsnNode store = nextOpcode(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Server pack repository checkpoint shape changed");
                }
                main.instructions.insert(store, serverStartupPhase("server-pack-repository-ready"));
                packRepositoryCheckpointPatched = true;
                continue;
            }
            if (!worldLoadConfigCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner)
                    && call.name.equals("loadOrCreateConfig")) {
                AbstractInsnNode store = nextOpcode(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Server world load config checkpoint shape changed");
                }
                main.instructions.insert(store, serverStartupPhase("server-world-load-config-ready"));
                worldLoadConfigCheckpointPatched = true;
                continue;
            }
            if (!worldLoadStartedPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("blockUntilDone")
                    && call.desc.equals("(Ljava/util/function/Function;)"
                            + "Ljava/util/concurrent/CompletableFuture;")) {
                main.instructions.insertBefore(call, serverStartupPhase("server-world-load-started"));
                worldLoadStartedPatched = true;
                continue;
            }
            if (!datapackCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("get")
                    && call.desc.equals("()Ljava/lang/Object;")) {
                AbstractInsnNode cast = nextOpcode(call);
                AbstractInsnNode store = cast == null ? null : nextOpcode(cast);
                if (!(cast instanceof TypeInsnNode type)
                        || cast.getOpcode() != Opcodes.CHECKCAST
                        || !type.desc.equals("net/minecraft/server/WorldStem")
                        || !(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException("Server datapack completion shape changed");
                }
                InsnList checkpoint = new InsnList();
                checkpoint.add(new LdcInsnNode("datapacks-loaded"));
                checkpoint.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserStartupScheduler",
                        "phase",
                        "(Ljava/lang/String;)V",
                        false));
                main.instructions.insert(store, checkpoint);
                datapackCheckpointPatched = true;
            }
        }
        if (!bootstrapCheckpointPatched
                || !bootstrapValidatedPatched
                || !settingsCheckpointPatched
                || !packRepositoryCheckpointPatched
                || !worldLoadConfigCheckpointPatched
                || !worldLoadStartedPatched
                || !datapackCheckpointPatched) {
            throw new IllegalStateException("Server startup checkpoints were not found");
        }
        main.maxStack = Math.max(main.maxStack, 8);

        patchServerWorldLoaderCooperativeExecutor(node);

        String legacyFactoryDescriptor = "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
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
        String currentFactoryDescriptor =
                "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                        + "Lnet/minecraft/server/packs/repository/PackRepository;"
                        + "Lnet/minecraft/server/WorldStem;"
                        + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                        + "Lnet/minecraft/server/Services;"
                        + "Lnet/minecraft/server/jsonrpc/ManagementServer;"
                        + "Lnet/minecraft/server/notifications/NotificationManager;"
                        + "Ljoptsimple/OptionSet;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljava/lang/Thread;)Lnet/minecraft/server/dedicated/DedicatedServer;";
        MethodNode factory = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$main$")
                    || !Type.getReturnType(method.desc).getDescriptor()
                            .equals("Lnet/minecraft/server/dedicated/DedicatedServer;")) {
                continue;
            }
            if (factory != null) {
                throw new IllegalStateException("Multiple dedicated-server factory lambdas found");
            }
            factory = method;
        }
        if (factory == null
                || (!factory.desc.equals(legacyFactoryDescriptor)
                        && !factory.desc.equals(currentFactoryDescriptor))) {
            throw new IllegalStateException("Unsupported dedicated-server factory descriptor: "
                    + (factory == null ? "missing" : factory.desc));
        }
        String server = "net/minecraft/server/dedicated/DedicatedServer";
        boolean currentFactory = factory.desc.equals(currentFactoryDescriptor);
        int optionSetLocal = currentFactory ? 7 : 5;
        int firstOptionLocal = currentFactory ? 8 : 6;
        int serverThreadLocal = currentFactory ? 13 : 11;
        int serverLocal = currentFactory ? 14 : 12;
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, server));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverThreadLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (currentFactory) {
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Optional",
                    "empty",
                    "()Ljava/util/Optional;",
                    false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "dataFixer",
                "()Lcom/mojang/datafixers/DataFixer;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        if (currentFactory) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 5));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                server,
                "<init>",
                currentFactory
                        ? "(Ljava/lang/Thread;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/WorldStem;"
                                + "Ljava/util/Optional;"
                                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                                + "Lcom/mojang/datafixers/DataFixer;"
                                + "Lnet/minecraft/server/Services;"
                                + "Lnet/minecraft/server/jsonrpc/ManagementServer;"
                                + "Lnet/minecraft/server/notifications/NotificationManager;)V"
                        : "(Ljava/lang/Thread;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/WorldStem;"
                                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                                + "Lcom/mojang/datafixers/DataFixer;"
                                + "Lnet/minecraft/server/Services;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, serverLocal));
        if (currentFactory) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/notifications/NotificationManager",
                    "setServer",
                    "(Lnet/minecraft/server/dedicated/DedicatedServer;)V",
                    false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal));
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
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal + 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "has",
                "(Ljoptsimple/OptionSpec;)Z",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, server, "setDemo", "(Z)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal + 2));
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
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(factory, code, currentFactory ? 12 : 9, serverLocal + 1);
        writeComputeFrames(node, output);
    }

    /**
     * A browser Worker has one JavaScript execution thread, so Minecraft's background executor
     * cannot provide real parallelism here. Route WorldLoader's background stages through the
     * queue supplied by Util.blockUntilDone instead. This avoids deeply nested direct-executor
     * continuations and gives every registry stage an explicit cooperative resume point.
     */
    private static void patchServerWorldLoaderCooperativeExecutor(ClassNode node) {
        MethodNode target = null;
        MethodInsnNode backgroundExecutor = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$main$")
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || !Type.getReturnType(method.desc).getDescriptor()
                            .equals("Ljava/util/concurrent/CompletableFuture;")) {
                continue;
            }
            MethodInsnNode candidateBackground = null;
            boolean worldLoaderCall = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/util/Util")
                        && call.name.equals("backgroundExecutor")
                        && call.desc.equals("()Lnet/minecraft/TracingExecutor;")) {
                    candidateBackground = call;
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/server/WorldLoader")
                        && call.name.equals("load")
                        && call.desc.endsWith("Ljava/util/concurrent/CompletableFuture;")) {
                    worldLoaderCall = true;
                }
            }
            if (!worldLoaderCall || candidateBackground == null) {
                continue;
            }
            if (target != null) {
                throw new IllegalStateException(
                        "Multiple server WorldLoader executor lambdas were found");
            }
            target = method;
            backgroundExecutor = candidateBackground;
        }
        if (target == null || backgroundExecutor == null) {
            throw new IllegalStateException(
                    "Server WorldLoader background executor patch point was not found");
        }

        Type[] arguments = Type.getArgumentTypes(target.desc);
        if (arguments.length == 0
                || !arguments[arguments.length - 1].getDescriptor()
                        .equals("Ljava/util/concurrent/Executor;")) {
            throw new IllegalStateException(
                    "Server WorldLoader lambda no longer ends with its cooperative executor: "
                            + target.desc);
        }
        int executorLocal = 0;
        for (int index = 0; index < arguments.length - 1; index++) {
            executorLocal += arguments[index].getSize();
        }
        target.instructions.set(
                backgroundExecutor,
                new VarInsnNode(Opcodes.ALOAD, executorLocal));

        MethodInsnNode worldLoaderCall = null;
        for (AbstractInsnNode instruction : target.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/WorldLoader")
                    && call.name.equals("load")) {
                worldLoaderCall = call;
                break;
            }
        }
        if (worldLoaderCall == null) {
            throw new IllegalStateException("Server WorldLoader call disappeared during patching");
        }
        target.instructions.insertBefore(
                worldLoaderCall,
                serverStartupPhase("world-loader-cooperative-executor"));
        target.maxStack = Math.max(target.maxStack, 8);
    }

    private static InsnList serverStartupPhase(String phase) {
        InsnList checkpoint = new InsnList();
        checkpoint.add(new LdcInsnNode(phase));
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "phase",
                "(Ljava/lang/String;)V",
                false));
        return checkpoint;
    }

    private static void patchWorldLoaderBrowserStartupTelemetry(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/WorldLoader";
        ClassNode node = read(jar, owner + ".class");
        MethodNode load = find(node, "load",
                "(Lnet/minecraft/server/WorldLoader$InitConfig;"
                        + "Lnet/minecraft/server/WorldLoader$WorldDataSupplier;"
                        + "Lnet/minecraft/server/WorldLoader$ResultFactory;"
                        + "Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        load.instructions.insert(serverStartupPhase("world-loader-started"));

        boolean worldgenRegistryPatched = false;
        boolean dimensionRegistryPatched = false;
        boolean serverResourcesPatched = false;
        boolean resultPatched = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/resources/RegistryDataLoader")
                        && call.name.equals("load")) {
                    FieldInsnNode registryList = previousFieldInstruction(call,
                            "net/minecraft/resources/RegistryDataLoader");
                    if (registryList == null) {
                        continue;
                    }
                    if (!worldgenRegistryPatched
                            && registryList.name.equals("WORLDGEN_REGISTRIES")) {
                        method.instructions.insertBefore(
                                call,
                                serverStartupPhase("world-loader-worldgen-registries-started"));
                        worldgenRegistryPatched = true;
                    } else if (!dimensionRegistryPatched
                            && registryList.name.equals("DIMENSION_REGISTRIES")) {
                        method.instructions.insertBefore(
                                call,
                                serverStartupPhase("world-loader-dimension-registries-started"));
                        dimensionRegistryPatched = true;
                    }
                } else if (!serverResourcesPatched
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/server/ReloadableServerResources")
                        && call.name.equals("loadResources")) {
                    method.instructions.insertBefore(
                            call,
                            serverStartupPhase("world-loader-server-resources-started"));
                    serverResourcesPatched = true;
                } else if (!resultPatched
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals("net/minecraft/server/WorldLoader$ResultFactory")
                        && call.name.equals("create")) {
                    method.instructions.insertBefore(
                            call,
                            serverStartupPhase("world-loader-server-resources-ready"));
                    resultPatched = true;
                }
            }
            method.maxStack = Math.max(method.maxStack, 16);
        }
        if (!worldgenRegistryPatched
                || !dimensionRegistryPatched
                || !serverResourcesPatched
                || !resultPatched) {
            throw new IOException("WorldLoader startup telemetry patch points changed: worldgen="
                    + worldgenRegistryPatched + ", dimensions=" + dimensionRegistryPatched
                    + ", resources=" + serverResourcesPatched + ", result=" + resultPatched);
        }
        writeComputeFrames(node, output);
    }

    private static FieldInsnNode previousFieldInstruction(
            AbstractInsnNode start, String owner) {
        int remaining = 12;
        for (AbstractInsnNode instruction = start.getPrevious();
                instruction != null && remaining-- > 0;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner)) {
                return field;
            }
        }
        return null;
    }

    private static void patchBlocksBrowserStartupYield(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/block/Blocks.class");
        MethodNode register = find(
                node,
                "register",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)"
                        + "Lnet/minecraft/world/level/block/Block;");
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = register.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList checkpoint = new InsnList();
            checkpoint.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "blockRegistered",
                    "()V",
                    false));
            register.instructions.insertBefore(instruction, checkpoint);
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException("Blocks register return shape changed");
        }
        write(node, output);
    }

    private static void patchBlockStateBaseBrowserStartupYield(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        ClassNode node = read(jar, owner + ".class");
        MethodNode initCache = find(node, "initCache", "()V");
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = initCache.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList checkpoint = new InsnList();
            checkpoint.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "blockStateInitialized",
                    "()V",
                    false));
            initCache.instructions.insertBefore(instruction, checkpoint);
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException("Block state cache return shape changed");
        }
        write(node, output);
    }

    private static void patchBuiltInRegistriesBrowserStartupYield(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/core/registries/BuiltInRegistries";
        ClassNode node = read(jar, owner + ".class");
        String entryDescriptor =
                "(Lnet/minecraft/resources/Identifier;Ljava/util/function/Supplier;)V";
        MethodNode createContentsEntry = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$createContents$")
                    || !method.desc.equals(entryDescriptor)) {
                continue;
            }
            if (createContentsEntry != null) {
                throw new IllegalStateException(
                        "Multiple BuiltInRegistries createContents entry lambdas found");
            }
            createContentsEntry = method;
        }
        if (createContentsEntry == null) {
            throw new IllegalStateException(
                    "BuiltInRegistries createContents entry lambda was not found");
        }
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = createContentsEntry.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            createContentsEntry.instructions.insertBefore(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "registryBootstrapCompleted",
                    "()V",
                    false));
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException(
                    "BuiltInRegistries createContents entry return shape changed");
        }
        write(node, output);
    }

    private static void patchSimpleJsonResourceReloadListenerBrowserStartupYield(
            String jar,
            Path output) throws IOException {
        String owner = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
        ClassNode node = read(jar, owner + ".class");
        MethodNode scanDirectory = find(
                node,
                "scanDirectory",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                        + "Lnet/minecraft/resources/FileToIdConverter;"
                        + "Lcom/mojang/serialization/DynamicOps;"
                        + "Lcom/mojang/serialization/Codec;Ljava/util/Map;)V");
        MethodInsnNode hasNext = null;
        for (AbstractInsnNode instruction = scanDirectory.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Iterator")
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")) {
                hasNext = call;
                break;
            }
        }
        if (hasNext == null) {
            throw new IllegalStateException("Datapack resource loop condition was not found");
        }
        LabelNode loopStart = null;
        for (AbstractInsnNode instruction = hasNext.getPrevious();
                instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof LabelNode label) {
                loopStart = label;
                break;
            }
        }
        JumpInsnNode loopBack = null;
        for (AbstractInsnNode instruction = hasNext.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && jump.label == loopStart) {
                loopBack = jump;
                break;
            }
        }
        if (loopStart == null || loopBack == null) {
            throw new IllegalStateException("Datapack resource loop back edge was not found");
        }
        InsnList checkpoint = new InsnList();
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "datapackResourceDecoded",
                "()V",
                false));
        scanDirectory.instructions.insertBefore(loopBack, checkpoint);
        write(node, output);
    }

    private static void patchDedicatedServerBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/dedicated/DedicatedServer";
        String properties = "net/minecraft/server/dedicated/DedicatedServerProperties";
        ClassNode node = read(jar, owner + ".class");
        MethodNode init = find(node, "initServer", "()Z");
        tryRemoveBooleanFieldBlock(
                init, properties, "managementServerEnabled", 2, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableQuery", 1, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableRcon", 1, Opcodes.IFEQ);
        removeMethodConditionBlock(
                init, owner, "getMaxTickLength", "()J", 1, Opcodes.IFLE);
        removeBooleanFieldBlock(init, properties, "enableJmxMonitoring", 1, Opcodes.IFEQ);

        boolean playerListConfigured = false;
        for (AbstractInsnNode instruction = init.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(owner)
                    || !call.name.equals("setPlayerList")
                    || !call.desc.equals("(Lnet/minecraft/server/players/PlayerList;)V")) {
                continue;
            }
            InsnList configure = new InsnList();
            configure.add(new VarInsnNode(Opcodes.ALOAD, 0));
            configure.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "getPlayerList",
                    "()Lnet/minecraft/server/dedicated/DedicatedPlayerList;",
                    false));
            configure.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "configurePlayerList",
                    "(Lnet/minecraft/server/players/PlayerList;)V",
                    false));
            init.instructions.insert(call, configure);
            init.maxStack = Math.max(init.maxStack, 3);
            playerListConfigured = true;
            break;
        }
        if (!playerListConfigured) {
            throw new IllegalStateException("DedicatedServer player list configuration point was not found");
        }

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
        if (!tryRemoveBooleanFieldBlock(
                method, owner, fieldName, setupInstructionCount, jumpOpcode)) {
            throw new IllegalStateException(
                    method.name + " browser block field was not found: " + fieldName);
        }
    }

    private static boolean tryRemoveBooleanFieldBlock(
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
            return false;
        }
        removeConditionalBlock(method, marker, setupInstructionCount, jumpOpcode);
        return true;
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "markServerListenerReady",
                "()V",
                false));
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

    private static void patchGlDebugBrowserNoCallback(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/opengl/GlDebug.class");
        MethodNode method = find(
                node,
                "enableDebugCallback",
                "(IZLjava/util/Set;)Lcom/mojang/blaze3d/opengl/GlDebug;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 3);
        write(node, output);
    }

    private static void patchBlockableEventLoopBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/thread/BlockableEventLoop.class");
        MethodNode method = find(node, "doRunTask", "(Ljava/lang/Runnable;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1, 2);
        writeComputeFrames(node, output);
    }

    private static void patchUtilRunNamedBrowserOutput(Path output) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        MethodNode method = find(node, "runNamed", "(Ljava/lang/Runnable;Ljava/lang/String;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1, 2);
        writeComputeFrames(node, output);
    }

    /** Replaces Minecraft's browser-incompatible timed queue spin with a cooperative pump. */
    private static void patchUtilBlockUntilDoneBrowserOutput(Path output) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        MethodNode method = find(node, "blockUntilDone",
                "(Ljava/util/function/Function;Ljava/util/function/Predicate;)Ljava/lang/Object;");
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("java/util/concurrent/BlockingQueue")
                    || !call.name.equals("poll")
                    || !call.desc.equals("(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserFuturePump";
            call.name = "poll";
            call.desc = "(Ljava/util/concurrent/BlockingQueue;JLjava/util/concurrent/TimeUnit;)"
                    + "Ljava/lang/Object;";
            call.itf = false;
            patched++;
        }
        if (patched != 1) {
            throw new IOException("Util.blockUntilDone timed queue poll patch count changed: "
                    + patched);
        }
        writeComputeFrames(node, output);
    }

    private static void patchTracyZoneFiller(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/TracyZoneFiller.class");
        boolean pushFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("push") && method.desc.equals("(Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
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

    /** Removes Tracy's StackWalker/native loader path from browser executors. */
    private static void patchTracingExecutorBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/TracingExecutor.class");
        MethodNode wrap = find(node, "wrapUnnamed", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(wrap, code, 1, 1);
        MethodNode forName = find(node, "forName", "(Ljava/lang/String;)Ljava/util/concurrent/Executor;");
        InsnList directExecutor = new InsnList();
        directExecutor.add(new VarInsnNode(Opcodes.ALOAD, 0));
        directExecutor.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/TracingExecutor",
                "service",
                "Ljava/util/concurrent/ExecutorService;"));
        directExecutor.add(new InsnNode(Opcodes.ARETURN));
        replace(forName, directExecutor, 1, 2);
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
        boolean hasRawMouseSupportHandle = node.fields.stream().anyMatch(field ->
                field.name.equals("GLFW_RAW_MOUSE_MOTION_SUPPORTED")
                        && field.desc.equals("Ljava/lang/invoke/MethodHandle;"));
        boolean hasRawMouseMotionConstant = node.fields.stream().anyMatch(field ->
                field.name.equals("GLFW_RAW_MOUSE_MOTION") && field.desc.equals("I"));
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                if (hasRawMouseSupportHandle) {
                    code.add(new InsnNode(Opcodes.ACONST_NULL));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "com/mojang/blaze3d/platform/InputConstants",
                            "GLFW_RAW_MOUSE_MOTION_SUPPORTED",
                            "Ljava/lang/invoke/MethodHandle;"));
                }
                if (hasRawMouseMotionConstant) {
                    code.add(new InsnNode(Opcodes.ICONST_0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "com/mojang/blaze3d/platform/InputConstants",
                            "GLFW_RAW_MOUSE_MOTION",
                            "I"));
                }
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

    private static void patchMinecraft(String jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Minecraft.class");
        boolean hasNoRender = node.fields.stream().anyMatch(field ->
                field.name.equals("noRender") && field.desc.equals("Z"));
        boolean found = false;
        boolean stateHooked = false;
        boolean throwableHooked = false;
        boolean browserChannelPumpHooked = false;
        boolean singleplayerWorkerHooked = false;
        boolean singleplayerWorkerStopHooked = false;
        boolean singleplayerWorkerReturnHooked = false;
        boolean browserCrashHooked = false;
        boolean lazyDataFixerHooked = false;
        boolean foregroundResourceReloadHooked = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals("net/minecraft/util/datafix/DataFixers")
                            && call.name.equals("getDataFixer")
                            && call.desc.equals("()Lcom/mojang/datafixers/DataFixer;")) {
                        call.owner = "dev/gaius/browser/BrowserLazyDataFixer";
                        call.name = "instance";
                        lazyDataFixerHooked = true;
                    }
                }
            }
            if (method.name.equals("run") && method.desc.equals("()V")) {
                throwableHooked = hookMinecraftRunCatchDiagnostics(method);
            } else if (isJvmUptimeLambda(method)) {
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
                        method.instructions.insertBefore(
                                instruction, minecraftStateReport(hasNoRender));
                        stateHooked = true;
                    }
                }
                method.maxStack = Math.max(method.maxStack, 9);
            } else if (method.name.equals("debugClientMetricsStart")
                    && method.desc.equals("(Ljava/util/function/Consumer;)Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 2);
            } else if (method.name.equals("doWorldLoad")
                    && (method.desc.equals(
                                    "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                            + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                            + "Lnet/minecraft/server/WorldStem;Z)V")
                            || method.desc.equals(
                                    "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                            + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                            + "Lnet/minecraft/server/WorldStem;Ljava/util/Optional;Z)V"))) {
                int newWorldLocal = method.desc.contains("Ljava/util/Optional;") ? 5 : 4;
                LabelNode vanillaIntegratedServer = new LabelNode();
                InsnList worker = new InsnList();
                worker.add(new VarInsnNode(Opcodes.ALOAD, 0));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 1));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 3));
                worker.add(new VarInsnNode(Opcodes.ILOAD, newWorldLocal));
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
            } else if (method.name.equals("disconnectFromWorld")
                    && method.desc.equals("(Lnet/minecraft/network/chat/Component;)V")) {
                singleplayerWorkerReturnHooked = replaceLocalServerCheck(method);
            } else if (method.name.equals("crash")
                    && (method.desc.equals(
                                    "(Lnet/minecraft/client/Minecraft;Ljava/io/File;"
                                            + "Lnet/minecraft/CrashReport;)V")
                            || method.desc.equals(
                                    "(Lnet/minecraft/client/Minecraft;Ljava/io/File;"
                                            + "Lnet/minecraft/CrashReport;I)V"))) {
                boolean hasExitCode = method.desc.endsWith(";I)V");
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                if (hasExitCode) {
                    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                }
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "net/minecraft/client/Minecraft",
                        "saveReport",
                        hasExitCode
                                ? "(Ljava/io/File;Lnet/minecraft/CrashReport;I)I"
                                : "(Ljava/io/File;Lnet/minecraft/CrashReport;)I",
                        false));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, hasExitCode ? 3 : 2, hasExitCode ? 4 : 3);
                browserCrashHooked = true;
            } else if (method.name.equals("getOverlay")
                    && method.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                LabelNode returnOverlay = new LabelNode();
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "overlay",
                        "Lnet/minecraft/client/gui/screens/Overlay;"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new TypeInsnNode(
                        Opcodes.INSTANCEOF,
                        "net/minecraft/client/gui/screens/LoadingOverlay"));
                code.add(new JumpInsnNode(Opcodes.IFEQ, returnOverlay));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "level",
                        "Lnet/minecraft/client/multiplayer/ClientLevel;"));
                code.add(new JumpInsnNode(Opcodes.IFNULL, returnOverlay));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(returnOverlay);
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 2, 1);
                foregroundResourceReloadHooked = true;
            }
        }
        if (!foregroundResourceReloadHooked) {
            patchGuiForegroundResourceReload(
                    jar, outputRoot.resolve("net/minecraft/client/gui/Gui.class"));
            foregroundResourceReloadHooked = true;
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
        if (!singleplayerWorkerReturnHooked) {
            throw new IllegalStateException(
                    "Minecraft singleplayer worker return-screen hook point was not found");
        }
        if (!browserCrashHooked) {
            throw new IllegalStateException("Minecraft browser crash hook point was not found");
        }
        if (!lazyDataFixerHooked) {
            throw new IllegalStateException("Minecraft lazy data fixer hook point was not found");
        }
        if (!foregroundResourceReloadHooked) {
            throw new IllegalStateException(
                    "Minecraft foreground resource reload overlay hook point was not found");
        }
        addMinecraftUiBridges(node);
        write(node, outputRoot.resolve("net/minecraft/client/Minecraft.class"));
    }

    private static void addMinecraftUiBridges(ClassNode node) {
        String owner = "net/minecraft/client/Minecraft";
        String screenDescriptor = "()Lnet/minecraft/client/gui/screens/Screen;";
        node.methods.removeIf(method -> method.name.equals("gaius$getScreen")
                && method.desc.equals(screenDescriptor));
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getScreen",
                screenDescriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        boolean directScreen = node.fields.stream().anyMatch(field -> field.name.equals("screen")
                && field.desc.equals("Lnet/minecraft/client/gui/screens/Screen;"));
        if (directScreen) {
            bridge.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "screen",
                    "Lnet/minecraft/client/gui/screens/Screen;"));
        } else {
            bridge.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "screen",
                    screenDescriptor,
                    false));
        }
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.maxStack = 1;
        bridge.maxLocals = 1;
        node.methods.add(bridge);

        String setScreenDescriptor = "(Lnet/minecraft/client/gui/screens/Screen;)V";
        node.methods.removeIf(method -> method.name.equals("gaius$setScreen")
                && method.desc.equals(setScreenDescriptor));
        MethodNode setScreen = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setScreen",
                setScreenDescriptor,
                null,
                null);
        setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directScreen) {
            setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setScreen.instructions.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "screen",
                    "Lnet/minecraft/client/gui/screens/Screen;"));
        } else {
            setScreen.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setScreen.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "setScreen",
                    setScreenDescriptor,
                    false));
        }
        setScreen.instructions.add(new InsnNode(Opcodes.RETURN));
        setScreen.maxStack = 2;
        setScreen.maxLocals = 2;
        node.methods.add(setScreen);

        boolean directOverlay = node.fields.stream().anyMatch(field -> field.name.equals("overlay")
                && field.desc.equals("Lnet/minecraft/client/gui/screens/Overlay;"));
        String overlayDescriptor = "()Lnet/minecraft/client/gui/screens/Overlay;";
        node.methods.removeIf(method -> method.name.equals("gaius$getOverlay")
                && method.desc.equals(overlayDescriptor));
        MethodNode getOverlay = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getOverlay",
                overlayDescriptor,
                null,
                null);
        getOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directOverlay) {
            getOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "overlay",
                    "Lnet/minecraft/client/gui/screens/Overlay;"));
        } else {
            getOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            getOverlay.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "overlay",
                    overlayDescriptor,
                    false));
        }
        getOverlay.instructions.add(new InsnNode(Opcodes.ARETURN));
        getOverlay.maxStack = 1;
        getOverlay.maxLocals = 1;
        node.methods.add(getOverlay);

        String setOverlayDescriptor = "(Lnet/minecraft/client/gui/screens/Overlay;)V";
        node.methods.removeIf(method -> method.name.equals("gaius$setOverlay")
                && method.desc.equals(setOverlayDescriptor));
        MethodNode setOverlay = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setOverlay",
                setOverlayDescriptor,
                null,
                null);
        setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directOverlay) {
            setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "overlay",
                    "Lnet/minecraft/client/gui/screens/Overlay;"));
        } else {
            setOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setOverlay.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "setOverlay",
                    setOverlayDescriptor,
                    false));
        }
        setOverlay.instructions.add(new InsnNode(Opcodes.RETURN));
        setOverlay.maxStack = 2;
        setOverlay.maxLocals = 2;
        node.methods.add(setOverlay);
    }

    private static void patchWorldStemBrowserSave(String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/WorldStem";
        String storage = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
        ClassNode node = read(jar, owner + ".class");
        String descriptor = "(L" + storage + ";)V";
        node.methods.removeIf(method -> method.name.equals("gaius$saveDataTag")
                && method.desc.equals(descriptor));
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$saveDataTag",
                descriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        MethodNode currentWorldData = findNullable(
                node,
                "worldDataAndGenSettings",
                "()Lnet/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings;");
        if (currentWorldData != null) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    currentWorldData.name,
                    currentWorldData.desc,
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings",
                    "data",
                    "()Lnet/minecraft/world/level/storage/WorldData;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    storage,
                    "saveDataTag",
                    "(Lnet/minecraft/world/level/storage/WorldData;)V",
                    false));
        } else {
            MethodNode legacyWorldData = findNullable(
                    node,
                    "worldData",
                    "()Lnet/minecraft/world/level/storage/WorldData;");
            if (legacyWorldData == null) {
                throw new IOException("WorldStem world-data accessor was not found");
            }
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "registries",
                    "()Lnet/minecraft/core/LayeredRegistryAccess;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/LayeredRegistryAccess",
                    "compositeAccess",
                    "()Lnet/minecraft/core/RegistryAccess$Frozen;",
                    false));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    legacyWorldData.name,
                    legacyWorldData.desc,
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    storage,
                    "saveDataTag",
                    "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;)V",
                    false));
        }
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 3;
        bridge.maxLocals = 2;
        node.methods.add(bridge);
        writeComputeFrames(node, output);
    }

    private static void patchCommandEncoderLegacyTextureUpload(String jar, Path output)
            throws IOException {
        String owner = "com/mojang/blaze3d/systems/CommandEncoder";
        ClassNode node = read(jar, owner + ".class");
        String legacyDescriptor = "(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;"
                + "Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V";
        if (findNullable(node, "writeToTexture", legacyDescriptor) != null) {
            write(node, output);
            return;
        }
        String currentDescriptor = "(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;"
                + "IIIIII)V";
        if (findNullable(node, "writeToTexture", currentDescriptor) == null) {
            throw new IOException("CommandEncoder byte-buffer texture upload was not found");
        }
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "writeToTexture",
                legacyDescriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        for (int local = 4; local <= 9; local++) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "writeToTexture",
                currentDescriptor,
                false));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 9;
        bridge.maxLocals = 10;
        node.methods.add(bridge);
        writeComputeFrames(node, output);
    }

    private static boolean isJvmUptimeLambda(MethodNode method) {
        if (!method.name.startsWith("lambda$fillUptime$")
                || !method.desc.equals("()Ljava/lang/String;")) {
            return false;
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/management/ManagementFactory")
                    && call.name.equals("getRuntimeMXBean")) {
                return true;
            }
        }
        return false;
    }

    private static void patchGuiForegroundResourceReload(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/gui/Gui";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node, "overlay", "()Lnet/minecraft/client/gui/screens/Overlay;");
        LabelNode returnOverlay = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "overlay",
                "Lnet/minecraft/client/gui/screens/Overlay;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/gui/screens/LoadingOverlay"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, returnOverlay));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "minecraft", "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, returnOverlay));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(returnOverlay);
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 1);
        write(node, output);
    }

    /** Keep a joined remote world visible and interactive while its verified pack reloads. */
    private static void patchLoadingOverlayBrowserForeground(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/gui/screens/LoadingOverlay";
        MethodNode tick;
        ClassNode node = read(jar, owner + ".class");
        tick = find(node, "tick", "()V");
        int completions = 0;
        for (AbstractInsnNode instruction : tick.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner)
                    || !field.name.equals("fadeOutStart")
                    || !field.desc.equals("J")) {
                continue;
            }
            LabelNode keepOverlay = new LabelNode();
            InsnList clearForegroundOverlay = new InsnList();
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Minecraft",
                    "level",
                    "Lnet/minecraft/client/multiplayer/ClientLevel;"));
            clearForegroundOverlay.add(new JumpInsnNode(Opcodes.IFNULL, keepOverlay));
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            clearForegroundOverlay.add(new InsnNode(Opcodes.ACONST_NULL));
            clearForegroundOverlay.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/Minecraft",
                    "gaius$setOverlay",
                    "(Lnet/minecraft/client/gui/screens/Overlay;)V",
                    false));
            clearForegroundOverlay.add(keepOverlay);
            tick.instructions.insert(instruction, clearForegroundOverlay);
            completions++;
        }
        if (completions != 1) {
            throw new IllegalStateException(
                    "LoadingOverlay foreground completion point changed: " + completions);
        }
        writeComputeFrames(node, output);
    }

    private static void patchPauseScreenBrowserSingleplayer(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/PauseScreen.class");
        MethodNode createPauseMenu = find(node, "createPauseMenu", "()V");
        if (!replaceLocalServerCheck(createPauseMenu)) {
            throw new IllegalStateException(
                    "PauseScreen singleplayer worker label hook point was not found");
        }
        write(node, output);
    }

    private static boolean replaceLocalServerCheck(MethodNode method) {
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/client/Minecraft")
                    || !call.name.equals("isLocalServer")
                    || !call.desc.equals("()Z")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserSingleplayerClient";
            call.name = "isLocalSession";
            call.desc = "(Lnet/minecraft/client/Minecraft;)Z";
            replacements++;
        }
        return replacements == 1;
    }

    private static void patchBrowserInputCallbacks(String jar, Path root) throws IOException {
        patchBrowserMouseHandler(jar, root.resolve("net/minecraft/client/MouseHandler.class"));
        patchBrowserKeyboardHandler(jar, root.resolve("net/minecraft/client/KeyboardHandler.class"));
    }

    private static void patchBrowserMouseHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/MouseHandler.class");

        MethodNode move = findInputSetupDispatch(node, "onMove", "(JDD)V");
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

        MethodNode button = findInputSetupDispatch(node, "onButton", "(JIII)V");
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

        MethodNode scroll = findInputSetupDispatch(node, "onScroll", "(JDD)V");
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
                    && ((call.owner.equals("net/minecraft/client/Minecraft")
                                    && call.name.equals("getOverlay"))
                            || (call.owner.equals("net/minecraft/client/gui/Gui")
                                    && call.name.equals("overlay")))
                    && call.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                boolean guiOwnsScreen = call.owner.equals("net/minecraft/client/gui/Gui");
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
                if (guiOwnsScreen) {
                    gate.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "net/minecraft/client/Minecraft",
                            "gui",
                            "Lnet/minecraft/client/gui/Gui;"));
                    gate.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "net/minecraft/client/gui/Gui",
                            "screen",
                            "()Lnet/minecraft/client/gui/screens/Screen;",
                            false));
                } else {
                    gate.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "net/minecraft/client/Minecraft",
                            "screen",
                            "Lnet/minecraft/client/gui/screens/Screen;"));
                }
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

    private static MethodNode findInputSetupDispatch(
            ClassNode node, String targetName, String... descriptors) throws IOException {
        MethodNode match = null;
        for (MethodNode method : node.methods) {
            boolean descriptorMatches = false;
            for (String descriptor : descriptors) {
                if (method.desc.equals(descriptor)) {
                    descriptorMatches = true;
                    break;
                }
            }
            if (!method.name.startsWith("lambda$setup$") || !descriptorMatches) {
                continue;
            }
            boolean dispatchesViaClient = false;
            String lambdaTarget = null;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/client/Minecraft")
                        && call.name.equals("execute")
                        && call.desc.equals("(Ljava/lang/Runnable;)V")) {
                    dispatchesViaClient = true;
                } else if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof org.objectweb.asm.Handle handle
                                && handle.getOwner().equals(node.name)) {
                            lambdaTarget = handle.getName();
                        }
                    }
                }
            }
            if (!dispatchesViaClient || lambdaTarget == null) {
                continue;
            }
            MethodNode target = null;
            for (MethodNode candidate : node.methods) {
                if (candidate.name.equals(lambdaTarget)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            boolean callsTarget = false;
            for (AbstractInsnNode instruction = target.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(node.name)
                        && call.name.equals(targetName)) {
                    callsTarget = true;
                    break;
                }
            }
            if (!callsTarget) {
                continue;
            }
            if (match != null) {
                throw new IOException("Multiple input setup dispatchers found for " + targetName);
            }
            match = method;
        }
        if (match == null) {
            throw new IOException("Input setup dispatcher was not found for " + targetName);
        }
        return match;
    }

    private static void patchBrowserKeyboardHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/KeyboardHandler.class");

        MethodNode key = findInputSetupDispatch(node, "keyPress", "(JIIII)V");
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

        MethodNode character = findInputSetupDispatch(node, "charTyped", "(JII)V", "(JI)V");
        boolean characterHasModifiers = character.desc.equals("(JII)V");
        InsnList charCode = new InsnList();
        charCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        charCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        charCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/CharacterEvent"));
        charCode.add(new InsnNode(Opcodes.DUP));
        charCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        if (characterHasModifiers) {
            charCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        }
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/CharacterEvent",
                "<init>",
                characterHasModifiers ? "(II)V" : "(I)V",
                false));
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/KeyboardHandler",
                "charTyped",
                "(JLnet/minecraft/client/input/CharacterEvent;)V",
                false));
        charCode.add(new InsnNode(Opcodes.RETURN));
        replace(character, charCode, 6, characterHasModifiers ? 5 : 4);

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

    private static InsnList minecraftStateReport(boolean hasNoRender) {
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getOverlay",
                "()Lnet/minecraft/client/gui/screens/Overlay;",
                false));
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
        if (hasNoRender) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Minecraft",
                    "noRender",
                    "Z"));
        } else {
            code.add(new InsnNode(Opcodes.ICONST_0));
        }
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
     * for RenderSystem's desktop poll-events gate. Required server packs must also start
     * immediately: quick-connect pages do not have a reliable opportunity to answer vanilla's
     * modal prompt before servers enforce their configuration timeout.
     */
    private static void patchClientKeepAliveBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class");
        MethodNode predicate = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$handleKeepAlive$")
                    || !method.desc.equals("()Z")) {
                continue;
            }
            if (predicate != null) {
                throw new IOException(
                        "Multiple ClientCommonPacketListenerImpl keepalive predicates found");
            }
            predicate = method;
        }
        if (predicate == null) {
            throw new IOException("ClientCommonPacketListenerImpl keepalive predicate was not found");
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(predicate, code, 1, 0);

        MethodNode resourcePack = find(
                node,
                "handleResourcePackPush",
                "(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V");
        if (resourcePack == null) {
            throw new IOException("ClientCommonPacketListenerImpl resource-pack handler was not found");
        }
        MethodInsnNode threadCheck = null;
        for (var instruction = resourcePack.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/network/protocol/PacketUtils")
                    && call.name.equals("ensureRunningOnSameThread")) {
                threadCheck = call;
                break;
            }
        }
        if (threadCheck == null) {
            throw new IOException("ClientCommonPacketListenerImpl resource-pack thread check was not found");
        }
        int urlLocal = resourcePack.maxLocals++;
        LabelNode vanillaResourcePackHandling = new LabelNode();
        InsnList requiredPack = new InsnList();
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "required",
                "()Z",
                false));
        requiredPack.add(new JumpInsnNode(Opcodes.IFEQ, vanillaResourcePackHandling));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "url",
                "()Ljava/lang/String;",
                false));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "parseResourcePackUrl",
                "(Ljava/lang/String;)Ljava/net/URL;",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ASTORE, urlLocal));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new JumpInsnNode(Opcodes.IFNULL, vanillaResourcePackHandling));
        LabelNode pushRequiredPack = new LabelNode();
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requiredPack.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "connection",
                "Lnet/minecraft/network/Connection;"));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserServerPackReuse",
                "handleRequiredPack",
                "(Lnet/minecraft/network/Connection;"
                        + "Lnet/minecraft/network/protocol/common/"
                        + "ClientboundResourcePackPushPacket;Ljava/net/URL;)Z",
                false));
        requiredPack.add(new JumpInsnNode(Opcodes.IFEQ, pushRequiredPack));
        requiredPack.add(new InsnNode(Opcodes.RETURN));
        requiredPack.add(pushRequiredPack);
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requiredPack.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "getDownloadedPackSource",
                "()Lnet/minecraft/client/resources/server/DownloadedPackSource;",
                false));
        requiredPack.add(new InsnNode(Opcodes.DUP));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/server/DownloadedPackSource",
                "allowServerPacks",
                "()V",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "id",
                "()Ljava/util/UUID;",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "hash",
                "()Ljava/lang/String;",
                false));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/server/DownloadedPackSource",
                "pushPack",
                "(Ljava/util/UUID;Ljava/net/URL;Ljava/lang/String;)V",
                false));
        requiredPack.add(new InsnNode(Opcodes.RETURN));
        requiredPack.add(vanillaResourcePackHandling);
        resourcePack.instructions.insert(threadCheck, requiredPack);
        resourcePack.maxStack = Math.max(resourcePack.maxStack, 4);

        MethodNode onDisconnect = find(
                node,
                "onDisconnect",
                "(Lnet/minecraft/network/DisconnectionDetails;)V");
        if (onDisconnect == null) {
            throw new IOException("ClientCommonPacketListenerImpl disconnect handler was not found");
        }
        MethodInsnNode disconnectCall = null;
        for (var instruction = onDisconnect.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("disconnect")
                    && call.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;Z)V")) {
                disconnectCall = call;
                break;
            }
        }
        if (disconnectCall == null) {
            throw new IOException("ClientCommonPacketListenerImpl disconnect call was not found");
        }
        InsnList prepareColdPackRecovery = new InsnList();
        prepareColdPackRecovery.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareColdPackRecovery.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "serverData",
                "Lnet/minecraft/client/multiplayer/ServerData;"));
        prepareColdPackRecovery.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/DisconnectionDetails",
                "reason",
                "()Lnet/minecraft/network/chat/Component;",
                false));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/network/chat/Component",
                "getString",
                "()Ljava/lang/String;",
                true));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserMultiplayerRecovery",
                "prepareDisconnect",
                "(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/String;)Z",
                false));
        prepareColdPackRecovery.add(new InsnNode(Opcodes.POP));
        onDisconnect.instructions.insert(prepareColdPackRecovery);
        InsnList recoverColdPackTimeout = new InsnList();
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recoverColdPackTimeout.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recoverColdPackTimeout.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "serverData",
                "Lnet/minecraft/client/multiplayer/ServerData;"));
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/DisconnectionDetails",
                "reason",
                "()Lnet/minecraft/network/chat/Component;",
                false));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/network/chat/Component",
                "getString",
                "()Ljava/lang/String;",
                true));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserMultiplayerRecovery",
                "maybeReconnect",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ServerData;"
                        + "Ljava/lang/String;)Z",
                false));
        recoverColdPackTimeout.add(new InsnNode(Opcodes.POP));
        onDisconnect.instructions.insert(disconnectCall, recoverColdPackTimeout);
        onDisconnect.maxStack = Math.max(onDisconnect.maxStack, 3);
        writeComputeFrames(node, output);
    }

    private static void patchDownloadedPackSourceBrowserRecovery(String jar, Path output)
            throws IOException {
        ClassNode node = read(
                jar, "net/minecraft/client/resources/server/DownloadedPackSource.class");
        MethodNode cleanup = find(node, "cleanupAfterDisconnect", "()V");
        if (cleanup == null) {
            throw new IOException("DownloadedPackSource cleanupAfterDisconnect was not found");
        }
        MethodInsnNode popAll = null;
        for (AbstractInsnNode instruction = cleanup.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(
                            "net/minecraft/client/resources/server/ServerPackManager")
                    && call.name.equals("popAll")
                    && call.desc.equals("()V")) {
                popAll = call;
                break;
            }
        }
        if (popAll == null) {
            throw new IOException("DownloadedPackSource popAll disconnect cleanup was not found");
        }
        AbstractInsnNode popAllStart = popAll.getPrevious();
        while (popAllStart != null && popAllStart.getOpcode() < 0) {
            popAllStart = popAllStart.getPrevious();
        }
        if (!(popAllStart instanceof FieldInsnNode managerLoad)
                || managerLoad.getOpcode() != Opcodes.GETFIELD) {
            throw new IOException("DownloadedPackSource manager load before popAll was not found");
        }
        popAllStart = managerLoad.getPrevious();
        while (popAllStart != null && popAllStart.getOpcode() < 0) {
            popAllStart = popAllStart.getPrevious();
        }
        if (!(popAllStart instanceof VarInsnNode receiverLoad)
                || receiverLoad.getOpcode() != Opcodes.ALOAD) {
            throw new IOException("DownloadedPackSource receiver before popAll was not found");
        }

        LabelNode vanillaCleanup = new LabelNode();
        LabelNode afterPopAll = new LabelNode();
        InsnList preservePack = new InsnList();
        preservePack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserServerPackReuse",
                "keepServerPackForRecovery",
                "()Z",
                false));
        preservePack.add(new JumpInsnNode(Opcodes.IFEQ, vanillaCleanup));
        preservePack.add(new JumpInsnNode(Opcodes.GOTO, afterPopAll));
        preservePack.add(vanillaCleanup);
        cleanup.instructions.insertBefore(popAllStart, preservePack);
        cleanup.instructions.insert(popAll, afterPopAll);
        writeComputeFrames(node, output);
    }

    private static void patchConnectScreenBrowserRecovery(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/ConnectScreen.class");
        MethodNode startConnecting = find(
                node,
                "startConnecting",
                "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;"
                        + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
                        + "Lnet/minecraft/client/multiplayer/ServerData;Z"
                        + "Lnet/minecraft/client/multiplayer/TransferState;)V");
        if (startConnecting == null) {
            throw new IOException("ConnectScreen browser recovery entry point was not found");
        }
        InsnList beginAttempt = new InsnList();
        beginAttempt.add(new VarInsnNode(Opcodes.ALOAD, 3));
        beginAttempt.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserMultiplayerRecovery",
                "beginConnection",
                "(Lnet/minecraft/client/multiplayer/ServerData;)V",
                false));
        startConnecting.instructions.insert(beginAttempt);
        startConnecting.maxStack = Math.max(startConnecting.maxStack, 1);
        writeComputeFrames(node, output);
    }

    /**
     * Server packs can take tens of seconds to rebuild on the browser main thread after their
     * bytes and SHA-1 are already verified. Let configuration finish at that verified boundary;
     * RelayNode keeps PLAY alive while the foreground reload completes. Suppress only the later
     * duplicate APPLIED result, while preserving real activation failures.
     */
    private static void patchEarlyBrowserServerPackSuccess(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/resources/server/DownloadedPackSource$6";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserEarlyApplied",
                "Ljava/util/Set;",
                "Ljava/util/Set<Ljava/util/UUID;>;",
                null));

        MethodNode constructor = find(node, "<init>",
                "(Lnet/minecraft/network/Connection;)V");
        if (constructor == null) {
            throw new IOException("DownloadedPackSource response sender constructor was not found");
        }
        MethodInsnNode superCall = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")) {
                superCall = call;
                break;
            }
        }
        if (superCall == null) {
            throw new IOException("DownloadedPackSource response sender super call was not found");
        }
        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
        initialize.add(new InsnNode(Opcodes.DUP));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserEarlyApplied", "Ljava/util/Set;"));
        constructor.instructions.insert(superCall, initialize);

        MethodNode reportUpdate = find(node, "reportUpdate",
                "(Ljava/util/UUID;Lnet/minecraft/client/resources/server/"
                        + "PackLoadFeedback$Update;)V");
        if (reportUpdate == null) {
            throw new IOException("DownloadedPackSource reportUpdate was not found");
        }
        AbstractInsnNode updateReturn = reportUpdate.instructions.getLast();
        while (updateReturn != null && updateReturn.getOpcode() != Opcodes.RETURN) {
            updateReturn = updateReturn.getPrevious();
        }
        if (updateReturn == null) {
            throw new IOException("DownloadedPackSource reportUpdate return was not found");
        }
        LabelNode updateDone = new LabelNode();
        InsnList earlySuccess = new InsnList();
        earlySuccess.add(new VarInsnNode(Opcodes.ALOAD, 2));
        earlySuccess.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/resources/server/PackLoadFeedback$Update",
                "DOWNLOADED",
                "Lnet/minecraft/client/resources/server/PackLoadFeedback$Update;"));
        earlySuccess.add(new JumpInsnNode(Opcodes.IF_ACMPNE, updateDone));
        earlySuccess.add(new VarInsnNode(Opcodes.ALOAD, 0));
        earlySuccess.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserEarlyApplied", "Ljava/util/Set;"));
        earlySuccess.add(new VarInsnNode(Opcodes.ALOAD, 1));
        earlySuccess.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        earlySuccess.add(new InsnNode(Opcodes.POP));
        earlySuccess.add(new VarInsnNode(Opcodes.ALOAD, 0));
        earlySuccess.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "val$connection",
                "Lnet/minecraft/network/Connection;"));
        earlySuccess.add(new TypeInsnNode(
                Opcodes.NEW,
                "net/minecraft/network/protocol/common/ServerboundResourcePackPacket"));
        earlySuccess.add(new InsnNode(Opcodes.DUP));
        earlySuccess.add(new VarInsnNode(Opcodes.ALOAD, 1));
        earlySuccess.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/network/protocol/common/ServerboundResourcePackPacket$Action",
                "SUCCESSFULLY_LOADED",
                "Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket$Action;"));
        earlySuccess.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/network/protocol/common/ServerboundResourcePackPacket",
                "<init>",
                "(Ljava/util/UUID;Lnet/minecraft/network/protocol/common/"
                        + "ServerboundResourcePackPacket$Action;)V",
                false));
        earlySuccess.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/Connection",
                "send",
                "(Lnet/minecraft/network/protocol/Packet;)V",
                false));
        earlySuccess.add(updateDone);
        reportUpdate.instructions.insertBefore(updateReturn, earlySuccess);

        MethodNode reportFinal = find(node, "reportFinalResult",
                "(Ljava/util/UUID;Lnet/minecraft/client/resources/server/"
                        + "PackLoadFeedback$FinalResult;)V");
        if (reportFinal == null) {
            throw new IOException("DownloadedPackSource reportFinalResult was not found");
        }
        LabelNode reportVanilla = new LabelNode();
        InsnList suppressDuplicate = new InsnList();
        LabelNode reportInstanceResult = new LabelNode();
        suppressDuplicate.add(new VarInsnNode(Opcodes.ALOAD, 1));
        suppressDuplicate.add(new VarInsnNode(Opcodes.ALOAD, 2));
        suppressDuplicate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserServerPackReuse",
                "suppressEarlyApplied",
                "(Ljava/util/UUID;Lnet/minecraft/client/resources/server/"
                        + "PackLoadFeedback$FinalResult;)Z",
                false));
        suppressDuplicate.add(new JumpInsnNode(Opcodes.IFEQ, reportInstanceResult));
        suppressDuplicate.add(new InsnNode(Opcodes.RETURN));
        suppressDuplicate.add(reportInstanceResult);
        suppressDuplicate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        suppressDuplicate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserEarlyApplied", "Ljava/util/Set;"));
        suppressDuplicate.add(new VarInsnNode(Opcodes.ALOAD, 1));
        suppressDuplicate.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "remove",
                "(Ljava/lang/Object;)Z",
                true));
        suppressDuplicate.add(new VarInsnNode(Opcodes.ISTORE, 3));
        suppressDuplicate.add(new VarInsnNode(Opcodes.ALOAD, 2));
        suppressDuplicate.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/resources/server/PackLoadFeedback$FinalResult",
                "APPLIED",
                "Lnet/minecraft/client/resources/server/PackLoadFeedback$FinalResult;"));
        suppressDuplicate.add(new JumpInsnNode(Opcodes.IF_ACMPNE, reportVanilla));
        suppressDuplicate.add(new VarInsnNode(Opcodes.ILOAD, 3));
        suppressDuplicate.add(new JumpInsnNode(Opcodes.IFEQ, reportVanilla));
        suppressDuplicate.add(new InsnNode(Opcodes.RETURN));
        suppressDuplicate.add(reportVanilla);
        reportFinal.instructions.insert(suppressDuplicate);
        reportFinal.maxLocals = Math.max(reportFinal.maxLocals, 4);
        writeComputeFrames(node, output);
    }

    /**
     * Browser WebSocket callbacks already share the client page's event loop. Treating them as
     * foreign Java threads makes configuration packets requeue forever while a resource reload
     * owns the normal client tick. Inline the configuration listener, the payloadless PLAY packet
     * that enters configuration, and the PLAY login packet that creates the replacement level
     * after configuration completes. Ordinary game packets stay queued so terrain handling is
     * still sliced by the client tick.
     */
    private static void patchClientPacketUtilsBrowserInline(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/protocol/PacketUtils.class");
        MethodNode method = find(node, "ensureRunningOnSameThread",
                "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/PacketProcessor;)V");
        if (method == null) {
            throw new IOException("PacketUtils client packet scheduler patch point was not found");
        }
        LabelNode inline = new LabelNode();
        LabelNode vanillaScheduling = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl"));
        code.add(new JumpInsnNode(Opcodes.IFNE, inline));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/network/protocol/game/ClientboundStartConfigurationPacket"));
        code.add(new JumpInsnNode(Opcodes.IFNE, inline));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/network/protocol/game/ClientboundLoginPacket"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanillaScheduling));
        code.add(inline);
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(vanillaScheduling);
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 1);
        writeComputeFrames(node, output);
    }

    /** Drains a short packet batch so actions stay responsive without monopolizing a browser turn. */
    private static void patchPacketProcessorBrowserSlice(String jar, Path output) throws IOException {
        String owner = "net/minecraft/network/PacketProcessor";
        ClassNode node = read(jar, owner + ".class");
        MethodNode schedule = find(
                node,
                "scheduleIfPossible",
                "(Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/protocol/Packet;)V");
        patchPacketProcessorQueuedAccounting(schedule);
        MethodNode method = find(node, "processQueuedPackets", "()V");
        if (method == null) {
            throw new IOException("PacketProcessor browser slice patch point was not found");
        }
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode handleStart = new LabelNode();
        LabelNode handleEnd = new LabelNode();
        LabelNode handleFailure = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "beginBatch",
                "()V",
                false));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "closed", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "shouldProcessNext",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
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
        code.add(handleStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/PacketProcessor$ListenerAndPacket",
                "handle",
                "()V",
                false));
        code.add(handleEnd);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "packetProcessed",
                "()V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(handleFailure);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "packetProcessed",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 3);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                handleStart,
                handleEnd,
                handleFailure,
                null));
        patchPacketProcessorCloseAccounting(node, owner);
        verifyPacketProcessorAccounting(node);
        writeComputeFrames(node, output);
    }

    private static void patchPacketProcessorQueuedAccounting(MethodNode schedule) {
        int patched = 0;
        for (AbstractInsnNode instruction : schedule.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode add)
                    || add.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !add.owner.equals("java/util/Queue")
                    || !add.name.equals("add")
                    || !add.desc.equals("(Ljava/lang/Object;)Z")) {
                continue;
            }
            AbstractInsnNode discard = nextOpcode(add);
            if (discard == null || discard.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException(
                        "PacketProcessor.scheduleIfPossible Queue.add result is no longer discarded");
            }
            LabelNode notQueued = new LabelNode();
            InsnList accounting = new InsnList();
            accounting.add(new InsnNode(Opcodes.DUP));
            accounting.add(new JumpInsnNode(Opcodes.IFEQ, notQueued));
            accounting.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPacketScheduler",
                    "packetQueued",
                    "()V",
                    false));
            accounting.add(notQueued);
            schedule.instructions.insert(add, accounting);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "PacketProcessor.scheduleIfPossible Queue.add telemetry changed: " + patched);
        }
        schedule.maxStack = Math.max(schedule.maxStack, 4);
    }

    private static void patchPacketProcessorCloseAccounting(ClassNode node, String owner) {
        MethodNode close = find(node, "close", "()V");
        int patched = 0;
        for (AbstractInsnNode instruction : close.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList cleanup = new InsnList();
            cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cleanup.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "packetsToBeHandled",
                    "Ljava/util/Queue;"));
            cleanup.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Queue",
                    "clear",
                    "()V",
                    true));
            cleanup.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPacketScheduler",
                    "reset",
                    "()V",
                    false));
            close.instructions.insertBefore(instruction, cleanup);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "PacketProcessor.close cleanup return shape changed: " + patched);
        }
        close.maxStack = Math.max(close.maxStack, 1);
    }

    private static void verifyPacketProcessorAccounting(ClassNode node) {
        int queued = 0;
        int processed = 0;
        int reset = 0;
        int shouldProcess = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("dev/gaius/browser/BrowserPacketScheduler")) {
                    continue;
                }
                if (call.name.equals("packetQueued") && call.desc.equals("()V")) {
                    queued++;
                } else if (call.name.equals("packetProcessed") && call.desc.equals("()V")) {
                    processed++;
                } else if (call.name.equals("reset") && call.desc.equals("()V")) {
                    reset++;
                } else if (call.name.equals("shouldProcessNext") && call.desc.equals("()Z")) {
                    shouldProcess++;
                }
            }
        }
        if (queued != 1 || processed != 2 || reset != 1 || shouldProcess != 1) {
            throw new IllegalStateException(
                    "PacketProcessor accounting hooks changed: queued=" + queued
                            + ", processed=" + processed
                            + ", reset=" + reset
                            + ", shouldProcess=" + shouldProcess);
        }
        System.out.println("Instrumented exact decoded-packet queue accounting");
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
        int modelContinuationCount = 0;
        for (AbstractInsnNode instruction = modelReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("thenApplyAsync")) {
                modelContinuationCount++;
            }
        }
        String[] modelLabels;
        if (modelContinuationCount == 3) {
            modelLabels = new String[] {
                    "ModelManager.specialBlockModels",
                    "ModelManager.discoverModelDependencies",
                    "ModelManager.buildModelGroups",
            };
        } else if (modelContinuationCount == 2) {
            modelLabels = new String[] {
                    "ModelManager.discoverModelDependencies",
                    "ModelManager.buildModelGroups",
            };
        } else {
            throw new IOException(
                    "Unsupported ModelManager async continuation count: "
                            + modelContinuationCount);
        }
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

    private static void patchUnihexProviderBrowserBulkParser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition.class");
        MethodNode loadData = find(node, "loadData",
                "(Ljava/io/InputStream;)Lnet/minecraft/client/gui/font/providers/UnihexProvider;");
        if (loadData == null) {
            throw new IOException("UnihexProvider bulk parser patch point was not found");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition",
                "sizeOverrides",
                "Ljava/util/List;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/gui/font/providers/BrowserUnihexLoader",
                "load",
                "(Ljava/io/InputStream;Ljava/util/List;)"
                        + "Lnet/minecraft/client/gui/font/providers/UnihexProvider;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(loadData, code, 2, 2);

        addUnihexOverrideBridge(node, "browserOverrideFrom", "from", "()I");
        addUnihexOverrideBridge(node, "browserOverrideTo", "to", "()I");
        MethodNode dimensions = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "browserOverrideDimensions",
                "(Ljava/lang/Object;)I",
                null,
                null);
        dimensions.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dimensions.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange"));
        dimensions.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange",
                "dimensions",
                "()Lnet/minecraft/client/gui/font/providers/UnihexProvider$Dimensions;",
                false));
        dimensions.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Dimensions",
                "pack",
                "()I",
                false));
        dimensions.instructions.add(new InsnNode(Opcodes.IRETURN));
        dimensions.maxStack = 1;
        dimensions.maxLocals = 1;
        node.methods.add(dimensions);
        writeComputeFrames(node, output);
    }

    private static void patchUnihexProviderBrowserAccess(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/UnihexProvider.class");
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/client/gui/font/CodepointMap;)V");
        MethodNode unpack = find(
                node,
                "unpackBitsToBytes",
                "(Ljava/nio/IntBuffer;"
                        + "Lnet/minecraft/client/gui/font/providers/UnihexProvider$LineData;II)V");
        if (constructor == null || unpack == null) {
            throw new IOException("UnihexProvider browser access points were not found");
        }
        constructor.access = (constructor.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
        unpack.access = (unpack.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
        write(node, output);
    }

    private static void addUnihexOverrideBridge(
            ClassNode node, String bridgeName, String accessorName, String accessorDescriptor) {
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                bridgeName,
                "(Ljava/lang/Object;)I",
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange"));
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange",
                accessorName,
                accessorDescriptor,
                false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        bridge.maxStack = 1;
        bridge.maxLocals = 1;
        node.methods.add(bridge);
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
                    || ((call.owner.equals("net/minecraft/client/resources/model/AtlasManager")
                                    || call.owner.equals(
                                            "net/minecraft/client/resources/model/sprite/AtlasManager"))
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
        if (!(firstFuture instanceof VarInsnNode first && first.getOpcode() == Opcodes.ALOAD)
                || !(secondFuture instanceof VarInsnNode second && second.getOpcode() == Opcodes.ALOAD)
                || !(thirdFuture instanceof VarInsnNode third && third.getOpcode() == Opcodes.ALOAD)) {
            throw new IOException("ModelManager dependency continuation captures changed");
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, first.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, second.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, third.var));
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
        boolean handleLoginImmediateReadyHooked = false;
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
                int returnHooks = 0;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) {
                        continue;
                    }
                    InsnList ready = new InsnList();
                    ready.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    ready.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "net/minecraft/client/multiplayer/ClientPacketListener",
                            "notifyPlayerLoaded",
                            "()V",
                            false));
                    method.instructions.insertBefore(instruction, ready);
                    returnHooks++;
                }
                if (returnHooks != 1) {
                    throw new IllegalStateException(
                            "ClientPacketListener immediate player-ready return changed: "
                                    + returnHooks);
                }
                handleLoginImmediateReadyHooked = true;
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
                || !handleLoginImmediateReadyHooked
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
        String descriptor =
                "(IILnet/minecraft/core/HolderSet;Lnet/minecraft/util/RandomSource;)"
                        + "Lnet/minecraft/world/level/ChunkPos;";
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.startsWith("lambda$generateRingPositions$")
                    || !candidate.desc.equals(descriptor)) {
                continue;
            }
            if (method != null) {
                throw new IllegalStateException(
                        "Multiple ChunkGeneratorStructureState ring-position lambdas found");
            }
            method = candidate;
        }
        if (method == null) {
            throw new IllegalStateException(
                    "ChunkGeneratorStructureState ring-position lambda was not found");
        }
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

    private static void patchNoiseBasedChunkGeneratorBrowserSynchronous(
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
        requireWorldgenSchedulerCalls("NoiseBasedChunkGenerator.doFill", method, 0);
        MethodNode applyCarvers = find(
                node,
                "applyCarvers",
                "(Lnet/minecraft/server/level/WorldGenRegion;J"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V");
        requireWorldgenSchedulerCalls(
                "NoiseBasedChunkGenerator.applyCarvers", applyCarvers, 0);
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

        MethodNode getValue = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("getValue")
                    || (!candidate.desc.equals("(DDDDDZ)D")
                            && !candidate.desc.equals("(DDDDD)D"))) {
                continue;
            }
            if (getValue != null) {
                throw new IllegalStateException("Multiple PerlinNoise detailed getValue methods found");
            }
            getValue = candidate;
        }
        if (getValue == null) {
            throw new IllegalStateException("PerlinNoise detailed getValue method was not found");
        }
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

        MethodNode compatibilityValue = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getValue",
                "(DDDDDZ)D",
                null,
                null);
        compatibilityValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int local = 1; local <= 9; local += 2) {
            compatibilityValue.instructions.add(new VarInsnNode(Opcodes.DLOAD, local));
        }
        if (getValue.desc.endsWith("Z)D")) {
            compatibilityValue.instructions.add(new VarInsnNode(Opcodes.ILOAD, 11));
        }
        compatibilityValue.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                getValue.name,
                getValue.desc,
                false));
        compatibilityValue.instructions.add(new InsnNode(Opcodes.DRETURN));
        compatibilityValue.maxStack = getValue.desc.endsWith("Z)D") ? 12 : 11;
        compatibilityValue.maxLocals = 12;
        node.methods.add(compatibilityValue);

        MethodNode hasOriginY = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "gaius$hasOriginY",
                "()Z",
                null,
                null);
        hasOriginY.instructions.add(new InsnNode(
                getValue.desc.endsWith("Z)D") ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        hasOriginY.instructions.add(new InsnNode(Opcodes.IRETURN));
        hasOriginY.maxStack = 1;
        hasOriginY.maxLocals = 0;
        node.methods.add(hasOriginY);
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

    private static void patchSurfaceSystemBrowserSynchronous(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/SurfaceSystem.class");
        String legacyDescriptor =
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/core/Registry;Z"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V";
        String currentDescriptor =
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;Z"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"
                        + "Ljava/util/Set;)V";
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("buildSurface")
                    || (!candidate.desc.equals(legacyDescriptor)
                            && !candidate.desc.equals(currentDescriptor))) {
                continue;
            }
            if (method != null) {
                throw new IllegalStateException("Multiple SurfaceSystem.buildSurface methods found");
            }
            method = candidate;
        }
        if (method == null) {
            throw new IllegalStateException("SurfaceSystem.buildSurface method was not found");
        }
        requireWorldgenSchedulerCalls("SurfaceSystem.buildSurface", method, 0);
        write(node, output);
    }

    private static void patchSurfaceRulesContextBrowserReusableBiomeSupplier(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/SurfaceRules$Context";
        String helper = "dev/gaius/browser/BrowserSurfaceBiomeSupplier";
        String helperDescriptor = "L" + helper + ";";
        ClassNode node = read(jar, owner + ".class");
        boolean currentContext = node.methods.stream()
                .anyMatch(method -> method.name.equals("updateY")
                        && method.desc.equals("(IIII)V"));
        node.fields.add(new FieldNode(0, "browserUpdateXZ", "I", null, null));
        node.fields.add(new FieldNode(0, "browserUpdateY", "I", null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBiomeSupplier",
                helperDescriptor,
                null,
                null));

        String constructorDescriptor = currentContext
                ? "(Lnet/minecraft/world/level/levelgen/SurfaceSystem;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Ljava/util/Set;)V"
                : "(Lnet/minecraft/world/level/levelgen/SurfaceSystem;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Ljava/util/function/Function;"
                        + "Lnet/minecraft/core/Registry;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)V";
        MethodNode constructor = find(node, "<init>", constructorDescriptor);
        int returns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList initialize = new InsnList();
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new TypeInsnNode(Opcodes.NEW, helper));
            initialize.add(new InsnNode(Opcodes.DUP));
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 5));
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "pos",
                    "Lnet/minecraft/core/BlockPos$MutableBlockPos;"));
            initialize.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    helper,
                    "<init>",
                    "(Ljava/util/function/Function;"
                            + "Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
                    false));
            initialize.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "browserBiomeSupplier",
                    helperDescriptor));
            constructor.instructions.insertBefore(instruction, initialize);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException(
                    "SurfaceRules.Context constructor RETURN shape changed: " + returns);
        }

        MethodNode updateXZ = find(node, "updateXZ", "(II)V");
        InsnList updateXZCounters = new InsnList();
        appendIntFieldIncrement(updateXZCounters, owner, "browserUpdateXZ");
        appendIntFieldIncrement(updateXZCounters, owner, "browserUpdateY");
        updateXZ.instructions.insertBefore(updateXZ.instructions.getFirst(), updateXZCounters);

        MethodNode updateY = find(node, "updateY", currentContext ? "(IIII)V" : "(IIIIII)V");
        if (currentContext) {
            InsnList updateYCounter = new InsnList();
            appendIntFieldIncrement(updateYCounter, owner, "browserUpdateY");
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockX", "I"));
            updateYCounter.add(new VarInsnNode(Opcodes.ILOAD, 4));
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockZ", "I"));
            updateYCounter.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, helper, "reset", "(III)V", false));
            updateY.instructions.insertBefore(updateY.instructions.getFirst(), updateYCounter);
            MethodNode getBiome = find(
                    node,
                    "getBiome",
                    "()Lnet/minecraft/core/Holder;");
            LabelNode cached = new LabelNode();
            InsnList getBiomeCode = new InsnList();
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
            getBiomeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, helper, "get", "()Lnet/minecraft/core/Holder;", false));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(cached);
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(new InsnNode(Opcodes.ARETURN));
            replace(getBiome, getBiomeCode, 2, 1);
            writeComputeFrames(node, output);
            return;
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "lastUpdateY", "J"));
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "lastUpdateY", "J"));
        appendIntFieldIncrement(code, owner, "browserUpdateY");
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, helper, "reset", "(III)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "biome", "Ljava/util/function/Supplier;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "blockY", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "waterHeight", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "stoneDepthBelow", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "stoneDepthAbove", "I"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(updateY, code, 5, 7);
        writeComputeFrames(node, output);
    }

    private static void appendIntFieldIncrement(InsnList code, String owner, String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }

    private static void patchSurfaceRulesLazyConditionBrowserPrimitiveCache(
            String jar, Path root) throws IOException {
        String lazyOwner = "net/minecraft/world/level/levelgen/SurfaceRules$LazyCondition";
        ClassNode lazy = read(jar, lazyOwner + ".class");
        lazy.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "browserLastUpdate", "I", null, null));
        lazy.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "browserResult", "Z", null, null));
        lazy.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserResultInitialized", "Z", null, null));
        lazy.methods.add(new MethodNode(
                Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                "browserContextLastUpdate",
                "()I",
                null,
                null));

        MethodNode test = find(lazy, "test", "()Z");
        LabelNode refresh = new LabelNode();
        LabelNode cached = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                lazyOwner,
                "browserContextLastUpdate",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, lazyOwner, "browserResultInitialized", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, refresh));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, lazyOwner, "browserLastUpdate", "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, cached));
        code.add(refresh);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, lazyOwner, "browserResultInitialized", "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, lazyOwner, "browserLastUpdate", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, lazyOwner, "compute", "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, lazyOwner, "browserResult", "Z"));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, lazyOwner, "browserResult", "Z"));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(test, code, 3, 2);
        writeComputeFrames(lazy, root.resolve(lazyOwner + ".class"));

        patchSurfaceRulesLazyCounterAccessor(
                jar, root, lazyOwner, "SurfaceRules$LazyXZCondition", "browserUpdateXZ");
        patchSurfaceRulesLazyCounterAccessor(
                jar, root, lazyOwner, "SurfaceRules$LazyYCondition", "browserUpdateY");
    }

    private static void patchSurfaceRulesLazyCounterAccessor(
            String jar, Path root, String lazyOwner, String simpleName, String counterField)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/" + simpleName;
        String context = "net/minecraft/world/level/levelgen/SurfaceRules$Context";
        ClassNode node = read(jar, owner + ".class");
        MethodNode accessor = new MethodNode(
                Opcodes.ACC_PROTECTED,
                "browserContextLastUpdate",
                "()I",
                null,
                null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, lazyOwner, "context", "L" + context + ";"));
        accessor.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, context, counterField, "I"));
        accessor.instructions.add(new InsnNode(Opcodes.IRETURN));
        accessor.maxStack = 1;
        accessor.maxLocals = 1;
        node.methods.add(accessor);
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    private static void patchDensityFunctionsPureTransformersBrowserDirect(
            String jar, Path root) throws IOException {
        String densityFunction = "net/minecraft/world/level/levelgen/DensityFunction";
        String context = densityFunction + "$FunctionContext";
        String provider = densityFunction + "$ContextProvider";
        String[] owners = {
            "net/minecraft/world/level/levelgen/DensityFunctions$Clamp",
            "net/minecraft/world/level/levelgen/DensityFunctions$MulOrAdd",
            "net/minecraft/world/level/levelgen/DensityFunctions$Mapped"
        };

        for (String owner : owners) {
            ClassNode node = read(jar, owner + ".class");
            MethodNode compute = findOrCreateMethod(
                    node,
                    Opcodes.ACC_PUBLIC,
                    "compute",
                    "(L" + context + ";)D");
            InsnList computeCode = new InsnList();
            computeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            computeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "input", "L" + densityFunction + ";"));
            computeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            computeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    densityFunction,
                    "compute",
                    "(L" + context + ";)D",
                    true));
            computeCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
            appendBrowserDensityTransformFromLocal(computeCode, owner, 2);
            computeCode.add(new InsnNode(Opcodes.DRETURN));
            replace(compute, computeCode, 6, 4);

            MethodNode fillArray = findOrCreateMethod(
                    node,
                    Opcodes.ACC_PUBLIC,
                    "fillArray",
                    "([DL" + provider + ";)V");
            LabelNode loop = new LabelNode();
            LabelNode done = new LabelNode();
            InsnList fillCode = new InsnList();
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fillCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "input", "L" + densityFunction + ";"));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fillCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    densityFunction,
                    "fillArray",
                    "([DL" + provider + ";)V",
                    true));
            fillCode.add(new InsnNode(Opcodes.ICONST_0));
            fillCode.add(new VarInsnNode(Opcodes.ISTORE, 3));
            fillCode.add(loop);
            fillCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new InsnNode(Opcodes.ARRAYLENGTH));
            fillCode.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
            appendBrowserDensityTransformFromArray(fillCode, owner, 1, 3);
            fillCode.add(new InsnNode(Opcodes.DASTORE));
            fillCode.add(new IincInsnNode(3, 1));
            fillCode.add(new JumpInsnNode(Opcodes.GOTO, loop));
            fillCode.add(done);
            fillCode.add(new InsnNode(Opcodes.RETURN));
            replace(fillArray, fillCode, 8, 4);

            writeComputeFrames(node, root.resolve(owner + ".class"));
        }
    }

    private static void patchWorldgenRecordHashCodeCaches(String jar, Path root)
            throws IOException {
        String[] owners = {
            "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder",
            "net/minecraft/world/level/levelgen/DensityFunctions$Ap2",
            "net/minecraft/world/level/levelgen/DensityFunctions$Clamp",
            "net/minecraft/world/level/levelgen/DensityFunctions$Constant",
            "net/minecraft/world/level/levelgen/DensityFunctions$HolderHolder",
            "net/minecraft/world/level/levelgen/DensityFunctions$Mapped",
            "net/minecraft/world/level/levelgen/DensityFunctions$Marker",
            "net/minecraft/world/level/levelgen/DensityFunctions$MulOrAdd",
            "net/minecraft/world/level/levelgen/DensityFunctions$Noise",
            "net/minecraft/world/level/levelgen/DensityFunctions$RangeChoice",
            "net/minecraft/world/level/levelgen/DensityFunctions$Shift",
            "net/minecraft/world/level/levelgen/DensityFunctions$ShiftA",
            "net/minecraft/world/level/levelgen/DensityFunctions$ShiftB",
            "net/minecraft/world/level/levelgen/DensityFunctions$ShiftedNoise",
            "net/minecraft/world/level/levelgen/DensityFunctions$Spline",
            "net/minecraft/world/level/levelgen/DensityFunctions$Spline$Coordinate",
            "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler",
            "net/minecraft/world/level/levelgen/DensityFunctions$YClampedGradient",
            "net/minecraft/util/CubicSpline$Constant",
            "net/minecraft/util/CubicSpline$Multipoint"
        };

        try (ZipFile input = new ZipFile(jar)) {
            for (String owner : owners) {
                Path output = root.resolve(owner + ".class");
                if (!Files.isRegularFile(output) && input.getEntry(owner + ".class") == null) {
                    System.out.println("Skipping removed worldgen hash cache target " + owner);
                    continue;
                }
                ClassNode node = Files.isRegularFile(output)
                        ? read(output)
                        : read(jar, owner + ".class");
                cacheImmutableRecordHashCode(node, owner);
                writeComputeFrames(node, output);
            }
        }
    }

    private static void cacheImmutableRecordHashCode(ClassNode node, String owner) {
        boolean record = (node.access & Opcodes.ACC_RECORD) != 0;
        boolean finalImmutableClass = (node.access & Opcodes.ACC_FINAL) != 0
                && node.fields.stream()
                        .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
                        .allMatch(field -> (field.access & Opcodes.ACC_FINAL) != 0);
        if (!record && !finalImmutableClass) {
            throw new IllegalStateException(
                    "Worldgen hash cache target is not immutable: " + owner);
        }
        if (node.fields.stream().anyMatch(field -> field.name.startsWith("browserHashCode"))) {
            throw new IllegalStateException("Worldgen hash cache fields already exist: " + owner);
        }

        MethodNode hashCode = find(node, "hashCode", "()I");
        if ((hashCode.access & Opcodes.ACC_FINAL) == 0
                && (node.access & Opcodes.ACC_FINAL) == 0) {
            throw new IllegalStateException("Final worldgen hashCode was not found: " + owner);
        }
        AbstractInsnNode[] originalInstructions = hashCode.instructions.toArray();
        int returns = 0;
        int resultLocal = hashCode.maxLocals++;
        for (AbstractInsnNode instruction : originalInstructions) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            InsnList remember = new InsnList();
            remember.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
            remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
            remember.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            remember.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserHashCode", "I"));
            remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
            remember.add(new InsnNode(Opcodes.ICONST_1));
            remember.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserHashCodeComputed", "Z"));
            remember.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            hashCode.instructions.insertBefore(instruction, remember);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("Worldgen record hashCode has no return: " + owner);
        }

        LabelNode compute = new LabelNode();
        InsnList cached = new InsnList();
        cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cached.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserHashCodeComputed", "Z"));
        cached.add(new JumpInsnNode(Opcodes.IFEQ, compute));
        cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cached.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "browserHashCode", "I"));
        cached.add(new InsnNode(Opcodes.IRETURN));
        cached.add(compute);
        hashCode.instructions.insert(cached);

        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        node.fields.add(new FieldNode(fieldAccess, "browserHashCode", "I", null, null));
        node.fields.add(new FieldNode(
                fieldAccess, "browserHashCodeComputed", "Z", null, null));
    }

    private static void appendBrowserDensityTransformFromLocal(
            InsnList code, String owner, int valueLocal) {
        code.add(new VarInsnNode(Opcodes.DLOAD, valueLocal));
        appendBrowserDensityTransform(code, owner);
    }

    private static void appendBrowserDensityTransformFromArray(
            InsnList code, String owner, int arrayLocal, int indexLocal) {
        code.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        code.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        code.add(new InsnNode(Opcodes.DALOAD));
        appendBrowserDensityTransform(code, owner);
    }

    private static void appendBrowserDensityTransform(InsnList code, String owner) {
        String helper = "dev/gaius/browser/BrowserDensityFunctions";
        if (owner.endsWith("$Clamp")) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "minValue", "D"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "maxValue", "D"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, helper, "clamp", "(DDD)D", false));
            return;
        }

        String typeField;
        String typeOwner;
        String helperMethod;
        String helperDescriptor;
        if (owner.endsWith("$MulOrAdd")) {
            typeField = "specificType";
            typeOwner = owner + "$Type";
            helperMethod = "transformMulOrAdd";
            helperDescriptor = "(DID)D";
        } else if (owner.endsWith("$Mapped")) {
            typeField = "type";
            typeOwner = owner + "$Type";
            helperMethod = "transformMapped";
            helperDescriptor = "(DI)D";
        } else {
            throw new IllegalArgumentException("Unsupported density transformer: " + owner);
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, typeField, "L" + typeOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, typeOwner, "ordinal", "()I", false));
        if (owner.endsWith("$MulOrAdd")) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "argument", "D"));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, helper, helperMethod, helperDescriptor, false));
    }

    private static void patchSurfaceRulesSequenceBrowserIndexed(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule";
        String rule = "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "tryApply",
                "(III)Lnet/minecraft/world/level/block/state/BlockState;");
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "rules", "Ljava/util/List;"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 6));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, rule));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                rule,
                "tryApply",
                "(III)Lnet/minecraft/world/level/block/state/BlockState;",
                true));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, next));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(next);
        code.add(new InsnNode(Opcodes.POP));
        code.add(new IincInsnNode(6, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 7);
        writeComputeFrames(node, output);
    }

    private static void patchNoiseChunkBrowserSynchronous(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk";
        ClassNode node = read(jar, owner + ".class");
        convertNoiseChunkCountersToInt(node, false);
        addNoiseInterpolatorArrayCache(node, owner);
        MethodNode fillSlice = find(node, "fillSlice", "(ZI)V");
        requireWorldgenSchedulerCalls("NoiseChunk.fillSlice", fillSlice, 0);
        requireWorldgenSchedulerCalls(
                "NoiseChunk.fillAllDirectly",
                find(
                        node,
                        "fillAllDirectly",
                        "([DLnet/minecraft/world/level/levelgen/DensityFunction;)V"),
                0);
        requireWorldgenSchedulerCalls(
                "NoiseChunk.selectCellYZ", find(node, "selectCellYZ", "(II)V"), 0);
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

    private static void patchClimateRTreeBrowserSynchronous(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/biome/Climate$RTree$SubTree.class");
        MethodNode method = find(
                node,
                "search",
                "([JLnet/minecraft/world/level/biome/Climate$RTree$Leaf;"
                        + "Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)"
                        + "Lnet/minecraft/world/level/biome/Climate$RTree$Leaf;");
        requireWorldgenSchedulerCalls("Climate.RTree.SubTree.search", method, 0);
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

    private static void patchChunkGeneratorBrowserSynchronous(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/ChunkGenerator.class");
        MethodNode decoration = find(
                node,
                "applyBiomeDecoration",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/StructureManager;)V");
        requireWorldgenSchedulerCalls("ChunkGenerator.applyBiomeDecoration", decoration, 0);

        String structureSetDescriptor =
                "(Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/core/SectionPos;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"
                        + "Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/core/RegistryAccess;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/"
                        + "StructureTemplateManager;"
                        + "Lnet/minecraft/resources/ResourceKey;"
                        + "Lnet/minecraft/core/Holder;)V";
        MethodNode structureSets = null;
        for (MethodNode candidate : node.methods) {
            if (candidate.name.startsWith("lambda$createStructures$")
                    && candidate.desc.equals(structureSetDescriptor)) {
                if (structureSets != null) {
                    throw new IllegalStateException(
                            "ChunkGenerator has multiple createStructures lambdas");
                }
                structureSets = candidate;
            }
        }
        if (structureSets == null) {
            throw new IllegalStateException("ChunkGenerator createStructures lambda was not found");
        }
        requireWorldgenSchedulerCalls("ChunkGenerator.createStructures", structureSets, 0);

        MethodNode references = find(
                node,
                "createReferences",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V");
        requireWorldgenSchedulerCalls("ChunkGenerator.createReferences", references, 0);
        write(node, output);
    }

    private static void patchWorldCarverBrowserSynchronous(String jar, Path output)
            throws IOException {
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
        requireWorldgenSchedulerCalls("WorldCarver.carveEllipsoid", method, 0);
        write(node, output);
    }

    private static void patchLightEngineBrowserSynchronous(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/lighting/LightEngine.class");
        MethodNode increases = find(node, "propagateIncreases", "()I");
        MethodNode decreases = find(node, "propagateDecreases", "()I");
        requireWorldgenSchedulerCalls("LightEngine.propagateIncreases", increases, 0);
        requireWorldgenSchedulerCalls("LightEngine.propagateDecreases", decreases, 0);
        write(node, output);
    }

    private static void patchLevelChunkSectionBrowserSynchronous(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/LevelChunkSection.class");
        MethodNode method = find(
                node,
                "fillBiomesFromNoise",
                "(Lnet/minecraft/world/level/biome/BiomeResolver;"
                        + "Lnet/minecraft/world/level/biome/Climate$Sampler;III)V");
        requireWorldgenSchedulerCalls("LevelChunkSection.fillBiomesFromNoise", method, 0);
        write(node, output);
    }

    private static MethodInsnNode browserWorldgenCheckpoint() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "checkpoint",
                "()V",
                false);
    }

    private static void requireWorldgenSchedulerCalls(
            String label, MethodNode method, int expectedCalls) {
        int pulses = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserWorldgenScheduler")
                    && call.desc.equals("()V")) {
                pulses++;
            }
        }
        if (pulses != expectedCalls) {
            throw new IllegalStateException(
                    label + " browser scheduler calls changed: " + pulses
                            + " (expected " + expectedCalls + ")");
        }
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
        boolean semanticElementIds = node.methods.stream()
                .anyMatch(method -> method.name.equals("beginElement")
                        && method.desc.equals("(I)J"));
        addBrowserBeginElementOffset(node, owner, semanticElementIds);
        patchBufferBuilderBrowserGuiWriters(node, owner, semanticElementIds);
        MethodNode method = find(node, "addVertex", "(FFFIFFIIFFF)V");
        LabelNode fallback = new LabelNode();
        LabelNode fastPath = new LabelNode();
        InsnList code = new InsnList();

        if (semanticElementIds) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFNE, fastPath));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "entityFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
            code.add(fastPath);
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fastFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        code.add(new InsnNode(Opcodes.POP2));
        loadBrowserCurrentVertexTarget(code, owner);
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
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                semanticElementIds ? "entityFormat" : "fullFormat",
                "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFastVertexBytes",
                "([BIFFFIFFIIFFFZ)V",
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
        node.fields.add(new FieldNode(0, "browserData", "[B", null, null));
        node.fields.add(new FieldNode(0, "browserDataOffset", "I", null, null));
        node.fields.add(new FieldNode(0, "browserLastReserveOffset", "I", null, null));

        MethodNode constructor = find(node, "<init>", "(IJ)V");
        injectBrowserBufferCacheBeforeReturn(constructor, owner);

        MethodNode resize = find(node, "resize", "(J)V");
        injectBrowserBufferCacheBeforeReturn(resize, owner);

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
        int reserveOffsets = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.LSTORE
                    && store.var == 2) {
                InsnList cacheOffset = new InsnList();
                cacheOffset.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cacheOffset.add(new VarInsnNode(Opcodes.LLOAD, 2));
                cacheOffset.add(new InsnNode(Opcodes.L2I));
                cacheOffset.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserLastReserveOffset", "I"));
                method.instructions.insert(instruction, cacheOffset);
                reserveOffsets++;
            }
        }
        if (reserveOffsets != 1) {
            throw new IllegalStateException(
                    "ByteBufferBuilder.reserve expected one starting offset, found " + reserveOffsets);
        }
        writeComputeFrames(node, output);
    }

    private static void injectBrowserBufferCacheBeforeReturn(MethodNode method, String owner) {
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList cache = new InsnList();
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pointer", "J"));
            cache.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "data",
                    "(J)[B",
                    false));
            cache.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "browserData", "[B"));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pointer", "J"));
            cache.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            cache.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserDataOffset", "I"));
            method.instructions.insertBefore(instruction, cache);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException(
                    method.name + method.desc + " expected one return, found " + returns);
        }
    }

    private static void patchCompiledSectionMeshBrowserVertexBufferReuse(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/CompiledSectionMesh";
        String meshOwner = "com/mojang/blaze3d/vertex/MeshData";
        String helperOwner = "dev/gaius/browser/BrowserMeshUpload";
        ClassNode node = read(jar, owner + ".class");
        String uploadDescriptor =
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lcom/mojang/blaze3d/vertex/MeshData;J)V";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("uploadMeshLayer")
                        && candidate.desc.equals(uploadDescriptor))
                .findFirst()
                .orElse(null);
        if (method == null) {
            verifyCurrentSectionMeshSingleViewUpload(jar, meshOwner);
            return;
        }

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

    private static void verifyCurrentSectionMeshSingleViewUpload(String jar, String meshOwner)
            throws IOException {
        String compileTask =
                "net/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$CompileTask";
        ClassNode node = read(jar, compileTask + ".class");
        MethodNode method = find(
                node,
                "doTask",
                "(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;");
        int vertexViews = 0;
        int indexViews = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(meshOwner)
                    || !call.desc.equals("()Ljava/nio/ByteBuffer;")) {
                continue;
            }
            if (call.name.equals("vertexBuffer")) {
                vertexViews++;
            } else if (call.name.equals("indexBuffer")) {
                indexViews++;
            }
        }
        if (vertexViews != 1 || indexViews != 1) {
            throw new IllegalStateException(
                    "Current section upload expected one vertex and one index view, found "
                            + vertexViews + " and " + indexViews);
        }
        System.out.println("Verified current single-view section mesh upload");
    }

    private static void patchBufferBuilderBrowserGuiWriters(
            ClassNode node, String owner, boolean semanticElementIds) {
        patchBufferBuilderFloatPosition(node, owner, semanticElementIds);
        patchBufferBuilderMatrixPosition(node, owner, semanticElementIds);
        patchBufferBuilderSetColor(node, owner, semanticElementIds);
        patchBufferBuilderSetUv(node, owner, semanticElementIds);
        patchBufferBuilderSetOverlayOrLight(node, owner, "setOverlay", semanticElementIds);
        patchBufferBuilderSetOverlayOrLight(node, owner, "setLight", semanticElementIds);
        patchBufferBuilderUvShort(node, owner, semanticElementIds);
        patchBufferBuilderSetNormal(node, owner, semanticElementIds);
    }

    private static void addBrowserBeginElementOffset(
            ClassNode node, String owner, boolean semanticElementIds) {
        String element = "com/mojang/blaze3d/vertex/VertexFormatElement";
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserBeginElementOffset",
                semanticElementIds ? "(I)I" : "(L" + element + ";)I",
                null,
                null);
        LabelNode present = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(semanticElementIds ? Opcodes.ILOAD : Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginElement",
                semanticElementIds ? "(I)J" : "(L" + element + ";)J",
                false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 2));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFNE, present));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(present);
        if (semanticElementIds) {
            code.add(new VarInsnNode(Opcodes.LLOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            code.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 4;
            method.maxLocals = 4;
            node.methods.add(method);
            return;
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/ByteBufferBuilder",
                "browserDataOffset",
                "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/ByteBufferBuilder",
                "browserLastReserveOffset",
                "I"));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "offsetsByElement", "[I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, element, "id", "()I", false));
        code.add(new InsnNode(Opcodes.IALOAD));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 4;
        method.maxLocals = 4;
        node.methods.add(method);
    }

    private static void loadBrowserCurrentVertexTarget(InsnList code, String owner) {
        String buffer = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserData", "[B"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserDataOffset", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserLastReserveOffset", "I"));
        code.add(new InsnNode(Opcodes.IADD));
    }

    private static void patchBufferBuilderFloatPosition(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "addVertex", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner, 4, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPositionBytes",
                "([BIFFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, semanticElementIds ? 5 : 4);
    }

    private static void patchBufferBuilderMatrixPosition(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = findOrCreateMethod(
                node,
                Opcodes.ACC_PUBLIC,
                "addVertex",
                "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner, 5, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putTransformedPositionBytes",
                "([BILorg/joml/Matrix4fc;FFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 7, semanticElementIds ? 6 : 5);
    }

    private static InsnList beginBufferBuilderVertexPosition(
            String owner, int offsetLocal, boolean semanticElementIds) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        if (semanticElementIds) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "elements",
                    "[Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.AALOAD));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/mojang/blaze3d/vertex/VertexFormatElement",
                    "offset",
                    "()I",
                    false));
            code.add(new InsnNode(Opcodes.I2L));
            code.add(new InsnNode(Opcodes.LADD));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            code.add(new VarInsnNode(Opcodes.ISTORE, offsetLocal));
        } else {
            code.add(new InsnNode(Opcodes.POP2));
        }
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
        if (semanticElementIds) {
            loadBrowserData(code, owner);
            code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        } else {
            loadBrowserCurrentVertexTarget(code, owner);
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "offsetsByElement", "[I"));
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
            code.add(new InsnNode(Opcodes.IADD));
        }
        return code;
    }

    private static void patchBufferBuilderSetColor(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "setColor", "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "COLOR", 2, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putRgbaBytes",
                "([BII)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 3);
    }

    private static void patchBufferBuilderSetUv(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "setUv", "(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "UV0", 3, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFloatPairBytes",
                "([BIFF)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 4);
    }

    private static void patchBufferBuilderSetOverlayOrLight(
            ClassNode node, String owner, String methodName, boolean semanticElementIds) {
        MethodNode method = find(node, methodName, "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        String element = methodName.equals("setOverlay") ? "UV1" : "UV2";
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, element, 2, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPackedUvBytes",
                "([BII)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 3);
    }

    private static void patchBufferBuilderUvShort(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "uvShort",
                semanticElementIds
                        ? "(SSI)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                        : "(SSLcom/mojang/blaze3d/vertex/VertexFormatElement;)"
                                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(semanticElementIds ? Opcodes.ILOAD : Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "browserBeginElementOffset",
                semanticElementIds
                        ? "(I)I"
                        : "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        loadBrowserData(code, owner);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putShortPairBytes",
                "([BISS)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 5);
    }

    private static void patchBufferBuilderSetNormal(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "setNormal", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "NORMAL", 4, semanticElementIds);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putNormalBytes",
                "([BIFFF)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 5);
    }

    private static LabelNode beginBrowserElementWrite(
            InsnList code,
            String owner,
            String elementName,
            int offsetLocal,
            boolean semanticElementIds) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (semanticElementIds) {
            code.add(new InsnNode(Opcodes.ICONST_0 + bufferBuilderSemanticId(elementName)));
        } else {
            code.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/mojang/blaze3d/vertex/VertexFormatElement",
                    elementName,
                    "Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "browserBeginElementOffset",
                semanticElementIds
                        ? "(I)I"
                        : "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, offsetLocal));
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        loadBrowserData(code, owner);
        code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        return done;
    }

    private static int bufferBuilderSemanticId(String elementName) {
        if (elementName.equals("POSITION")) {
            return 0;
        }
        if (elementName.equals("COLOR")) {
            return 1;
        }
        if (elementName.equals("UV0")) {
            return 2;
        }
        if (elementName.equals("UV1")) {
            return 3;
        }
        if (elementName.equals("UV2")) {
            return 4;
        }
        if (elementName.equals("NORMAL")) {
            return 5;
        }
        throw new IllegalArgumentException("Unknown BufferBuilder semantic " + elementName);
    }

    private static void loadBrowserData(InsnList code, String owner) {
        String buffer = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserData", "[B"));
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
                "java/util/UUID",
                "randomUUID",
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
        verifyBrowserUuidCalls(method, 1);
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
        boolean patchedRunServerStopDiagnostics = false;
        boolean patchedRunServerBrowserCatchupReset = false;
        boolean patchedRunServerStoppedSignal = false;
        boolean patchedSpinRegistration = false;
        boolean patchedSaveBeforeWorldInitialization = false;
        boolean patchedBrowserPacketPump = false;
        boolean patchedBrowserWaitingPacketPump = false;
        boolean patchedBrowserMetricsRecorder = false;
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
                browserPackets.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "tickIntegratedServerDistances",
                        "()V",
                        false));
                method.instructions.insert(browserPackets);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedBrowserPacketPump = true;
            } else if (method.name.equals("pollTask") && method.desc.equals("()Z")) {
                method.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "pumpUrgentPacketsIfPending",
                        "()V",
                        false));
                patchedBrowserWaitingPacketPump = true;
            } else if (method.name.equals("createProfiler")
                    && method.desc.equals("()Lnet/minecraft/util/profiling/ProfilerFiller;")) {
                // ServerMetricsSamplersProvider probes OSHI/JNA for desktop CPU and hardware
                // metrics. It is neither available nor useful in an in-browser integrated server.
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/util/profiling/metrics/profiling/InactiveMetricsRecorder",
                        "INSTANCE",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        owner,
                        "metricsRecorder",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "metricsRecorder",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/util/profiling/metrics/profiling/MetricsRecorder",
                        "getProfiler",
                        "()Lnet/minecraft/util/profiling/ProfilerFiller;",
                        true));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 1);
                patchedBrowserMetricsRecorder = true;
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
                        patchedRunServerTickYield = true;
                        break;
                    }
                }
                patchedRunServerStopDiagnostics = hookMinecraftServerStopDiagnostics(method);
                patchedRunServerBrowserCatchupReset = patchMinecraftServerBrowserCatchupReset(method, owner);
                int stoppedSignals = 0;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) {
                        continue;
                    }
                    InsnList stopped = new InsnList();
                    stopped.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    stopped.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserIntegratedServerMain",
                            "markIntegratedServerStopped",
                            "(Lnet/minecraft/server/MinecraftServer;)V",
                            false));
                    method.instructions.insertBefore(instruction, stopped);
                    stoppedSignals++;
                }
                if (stoppedSignals != 1) {
                    throw new IllegalStateException(
                            "MinecraftServer runServer exit shape changed: " + stoppedSignals);
                }
                method.maxStack = Math.max(method.maxStack, 1);
                patchedRunServerStoppedSignal = true;
            }
        }
        if (!patchedPrepareLevels
                || !patchedInitialSpawn
                || !patchedRunServerReady
                || !patchedRunServerTickYield
                || !patchedRunServerStopDiagnostics
                || !patchedRunServerBrowserCatchupReset
                || !patchedRunServerStoppedSignal
                || !patchedSpinRegistration
                || !patchedSaveBeforeWorldInitialization
                || !patchedBrowserPacketPump
                || !patchedBrowserWaitingPacketPump
                || !patchedBrowserMetricsRecorder) {
            throw new IllegalStateException("MinecraftServer browser patch points were not found");
        }
        writeComputeFrames(node, output);
    }

    private static void patchPlayerSpawnFinderBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/level/PlayerSpawnFinder";
        ClassNode node = read(jar, owner + ".class");
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
        replace(findSpawn, code, 2, 2);

        MethodNode fixupLoadedSpawn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$fixupLoadedSpawn",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)"
                        + "Lnet/minecraft/world/phys/Vec3;",
                null,
                null);
        LabelNode heightmapFallback = new LabelNode();
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/BlockPos",
                "containing",
                "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixupLoadedSpawn.instructions.add(new TypeInsnNode(
                Opcodes.NEW,
                "net/minecraft/world/level/ChunkPos"));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.DUP));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getX",
                "()I",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ICONST_4));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ISHR));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getZ",
                "()I",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ICONST_4));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ISHR));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(II)V",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "getSpawnPosInChunk",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)"
                        + "Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.DUP));
        fixupLoadedSpawn.instructions.add(new JumpInsnNode(
                Opcodes.IFNULL,
                heightmapFallback));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ARETURN));
        fixupLoadedSpawn.instructions.add(heightmapFallback);
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.POP));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixupLoadedSpawn.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/levelgen/Heightmap$Types",
                "MOTION_BLOCKING_NO_LEAVES",
                "Lnet/minecraft/world/level/levelgen/Heightmap$Types;"));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/BlockPos",
                "containing",
                "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getHeightmapPos",
                "(Lnet/minecraft/world/level/levelgen/Heightmap$Types;"
                        + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ARETURN));
        fixupLoadedSpawn.maxStack = 4;
        fixupLoadedSpawn.maxLocals = 3;
        node.methods.add(fixupLoadedSpawn);
        write(node, output);
    }

    private static void patchPrepareSpawnTaskBrowser(String jar, Path root)
            throws IOException {
        String chunkCacheOwner = "net/minecraft/server/level/ServerChunkCache";
        ClassNode chunkCache = read(jar, chunkCacheOwner + ".class");
        MethodNode getChunkFutureMainThread = find(
                chunkCache,
                "getChunkFutureMainThread",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        if ((getChunkFutureMainThread.access & Opcodes.ACC_PRIVATE) == 0) {
            throw new IllegalStateException(
                    "ServerChunkCache.getChunkFutureMainThread access changed");
        }
        getChunkFutureMainThread.access =
                (getChunkFutureMainThread.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        write(chunkCache, root.resolve(chunkCacheOwner + ".class"));

        String preparingOwner = "net/minecraft/server/network/config/PrepareSpawnTask$Preparing";
        ClassNode preparing = read(jar, preparingOwner + ".class");
        MethodNode loadSpawnChunks = find(preparing, "lambda$tick$0",
                "(Lnet/minecraft/world/level/ChunkPos;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                preparingOwner,
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
                "getChunkFutureMainThread",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                preparingOwner,
                "chunkLoadFuture",
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(loadSpawnChunks, code, 6, 2);

        MethodNode prepareTick = find(preparing, "tick",
                "()Lnet/minecraft/server/network/config/PrepareSpawnTask$Ready;");
        int loadedSpawnFixups = 0;
        for (var instruction = prepareTick.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(
                            "net/minecraft/server/network/config/PrepareSpawnTask$Ready")) {
                continue;
            }
            InsnList fixup = new InsnList();
            fixup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fixup.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    preparingOwner,
                    "spawnLevel",
                    "Lnet/minecraft/server/level/ServerLevel;"));
            fixup.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fixup.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/server/level/PlayerSpawnFinder",
                    "gaius$fixupLoadedSpawn",
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)"
                            + "Lnet/minecraft/world/phys/Vec3;",
                    false));
            fixup.add(new VarInsnNode(Opcodes.ASTORE, 1));
            prepareTick.instructions.insertBefore(instruction, fixup);
            loadedSpawnFixups++;
        }
        if (loadedSpawnFixups != 1) {
            throw new IllegalStateException(
                    "PrepareSpawnTask loaded-spawn fixup point changed: "
                            + loadedSpawnFixups);
        }
        prepareTick.maxStack = Math.max(prepareTick.maxStack, 2);
        write(preparing, root.resolve(preparingOwner + ".class"));

        String readyOwner = "net/minecraft/server/network/config/PrepareSpawnTask$Ready";
        ClassNode ready = read(jar, readyOwner + ".class");
        replaceIntArgumentBeforeCall(
                find(ready, "keepAlive", "()V"),
                "net/minecraft/server/level/ServerChunkCache",
                "addTicketWithRadius",
                3,
                1);
        replaceIntArgumentBeforeCall(
                find(ready, "spawn", "(Lnet/minecraft/network/Connection;"
                        + "Lnet/minecraft/server/network/CommonListenerCookie;)"
                        + "Lnet/minecraft/server/level/ServerPlayer;"),
                "net/minecraft/server/level/ServerLevel",
                "waitForEntities",
                3,
                0);
        write(ready, root.resolve(readyOwner + ".class"));
    }

    private static void patchStructureTemplateManagerBrowserGzip(String jar, Path outputRoot)
            throws IOException {
        String legacyOwner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager";
        String currentOwner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/loader/TemplateSource";
        boolean currentLoader;
        try (ZipFile input = new ZipFile(jar)) {
            currentLoader = input.getEntry(currentOwner + ".class") != null;
        }
        String owner = currentLoader ? currentOwner : legacyOwner;
        String template = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
        ClassNode node = read(jar, owner + ".class");
        if (currentLoader) {
            MethodNode method = find(
                    node,
                    "readStructure",
                    "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserGzip",
                    "readCompressedNbt",
                    "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, 1);
            write(node, outputRoot.resolve(owner + ".class"));
            return;
        }
        MethodNode method = find(
                node,
                "readStructure",
                "(Ljava/io/InputStream;)L" + template + ";");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGzip",
                "readCompressedNbt",
                "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "readStructure",
                "(Lnet/minecraft/nbt/CompoundTag;)L" + template + ";",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 2);
        write(node, outputRoot.resolve(owner + ".class"));
    }

    private static void replaceIntArgumentBeforeCall(
            MethodNode method,
            String callOwner,
            String callName,
            int expected,
            int replacement) {
        int patches = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals(callOwner)
                    || !call.name.equals(callName)) {
                continue;
            }
            AbstractInsnNode argument = call.getPrevious();
            while (argument != null && (argument.getType() == AbstractInsnNode.LABEL
                    || argument.getType() == AbstractInsnNode.LINE
                    || argument.getType() == AbstractInsnNode.FRAME)) {
                argument = argument.getPrevious();
            }
            if (argument == null || argument.getOpcode() != Opcodes.ICONST_0 + expected) {
                throw new IllegalStateException(callOwner + "." + callName
                        + " browser radius argument changed");
            }
            method.instructions.set(argument, new InsnNode(Opcodes.ICONST_0 + replacement));
            patches++;
        }
        if (patches != 1) {
            throw new IllegalStateException(callOwner + "." + callName
                    + " browser radius patch point was not found");
        }
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
                        "acknowledgeChunkBatch",
                        "()V",
                        false));
                chunkBatch.instructions.insertBefore(instruction, activate);
                activations++;
            }
        }
        if (activations != 1) {
            throw new IllegalStateException(
                    "ServerGamePacketListenerImpl chunk-batch acknowledgement point changed: "
                            + activations);
        }
        chunkBatch.maxStack = Math.max(chunkBatch.maxStack, 1);
        writeComputeFrames(node, output);
    }

    private static void patchPlayerChunkSenderBrowserWorker(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/network/PlayerChunkSender";
        ClassNode node = read(jar, owner + ".class");
        MethodNode sendNextChunks = find(
                node,
                "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        int records = 0;
        for (AbstractInsnNode instruction = sendNextChunks.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(
                            "net/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket")) {
                continue;
            }
            AbstractInsnNode batchFinishedSend = instruction.getNext();
            while (batchFinishedSend != null
                    && (!(batchFinishedSend instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !call.owner.equals(
                                    "net/minecraft/server/network/ServerGamePacketListenerImpl")
                            || !call.name.equals("send")
                            || !call.desc.equals(
                                    "(Lnet/minecraft/network/protocol/Packet;)V"))) {
                batchFinishedSend = batchFinishedSend.getNext();
            }
            if (batchFinishedSend == null) {
                continue;
            }
            InsnList record = new InsnList();
            record.add(new VarInsnNode(Opcodes.ALOAD, 5));
            record.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "size",
                    "()I",
                    true));
            record.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "recordChunkBatchSent",
                    "(I)V",
                    false));
            sendNextChunks.instructions.insert(batchFinishedSend, record);
            records++;
        }
        if (records != 1) {
            throw new IllegalStateException(
                    "PlayerChunkSender chunk-batch size point changed: " + records);
        }
        sendNextChunks.maxStack = Math.max(sendNextChunks.maxStack, 2);
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
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/world/level/ChunkPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getX",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getZ",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(II)V",
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
                "getMaxY",
                "()I",
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
        replace(method, code, 9, 8);
        verifyChunkPosIntConstructor(method, 1);
    }

    private static void verifyChunkPosIntConstructor(
            MethodNode method, int expectedIntConstructors) {
        int intConstructors = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/world/level/ChunkPos")
                    || !call.name.equals("<init>")) {
                continue;
            }
            if (call.desc.equals("(Lnet/minecraft/core/BlockPos;)V")) {
                throw new IllegalStateException(
                        "Browser patch introduced removed ChunkPos(BlockPos) in "
                                + method.name + method.desc);
            }
            if (call.desc.equals("(II)V")) {
                intConstructors++;
            }
        }
        if (intConstructors != expectedIntConstructors) {
            throw new IllegalStateException(
                    "Expected " + expectedIntConstructors + " ChunkPos(int,int) calls in "
                            + method.name + method.desc + ", got " + intConstructors);
        }
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
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy",
                        "browserSafeHeaders",
                        "(Ljava/util/Map;)Ljava/util/Map;",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2));
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
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
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V",
                false));
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

    private static MethodNode findNullable(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
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

    private static ClassNode read(Path path) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
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
