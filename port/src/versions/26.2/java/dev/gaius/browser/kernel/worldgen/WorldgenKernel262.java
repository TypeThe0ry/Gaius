package dev.gaius.browser.kernel.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.GaiusKernelSections;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.GaiusWorldgenExport262;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * 26.2 seam between the server's chunk generation and the worldgen kernel.
 * {@code dev.gaius.tools.kernel.WorldgenKernelPatches} renames {@code NoiseBasedChunkGenerator.fillFromNoise}
 * and {@code createBiomes} to {@code gaius$fillFromNoiseVanilla} / {@code gaius$createBiomesVanilla};
 * the new methods first call {@link #fillFromNoise} / {@link #createBiomes} and run the vanilla
 * method when those return {@code null}.
 *
 * <p>The kernel takes the BIOMES step, the NOISE step (density fill, aquifers, ore veins, the
 * worldgen heightmaps and fluid post-processing marks) and the SURFACE step: NOISE keeps its
 * decoded chunk in the facade (bounded byte budget) and {@code ChunkStatusTasks.generateSurface}
 * runs the kernel surface rules on it, applying only the changed blocks. The surface rules read
 * the biomes the neighbouring chunks store (sent with the job, see {@link #ringBiomes}) like
 * vanilla's {@code BiomeManager} does. The surface rules never run during NOISE, so a chunk saved
 * between NOISE and SURFACE (a new proto chunk object, with nothing kept) simply takes the vanilla
 * surface step. CARVERS stays vanilla. Blended, upgrading and below-zero-retrogen chunks,
 * generators the exporter does not model and any kernel failure stay on (or fall back to) the
 * vanilla path; a NOISE result is built off the chunk first, so one that cannot be installed
 * leaves the chunk untouched for vanilla. A transient refusal (also a neighbour biome the kernel
 * does not know) only sends that chunk there.
 */
public final class WorldgenKernel262 {
    private static final Map<RandomState, Slot> SLOTS = new IdentityHashMap<>();
    private static final int[] NO_BEARD = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    /** The IR profile byte of 26.2 ({@code gaius-worldgen} ir::Profile). */
    private static final int PROFILE_26_2 = 1;

    private WorldgenKernel262() {
    }

    private static final class Slot {
        int key;
        boolean failed;
        IdMap<Holder<Biome>> biomeIds;
        /**
         * Kept noise chunks of this generator by {@code ChunkPos.pack}, oldest first. Only the
         * position and the proto chunk's identity hash are held, never the chunk: a chunk whose
         * generation stopped after NOISE (cancelled, then saved and unloaded) leaves a few ints
         * behind until {@link #MAX_KEPT} pushes them out.
         */
        final Long2ObjectLinkedOpenHashMap<KeptNoise> kept = new Long2ObjectLinkedOpenHashMap<>();
    }

    /** The kernel generator slot of a RandomState, exporting it on first use. */
    private static Slot slot(NoiseBasedChunkGenerator generator, RandomState randomState, StructureManager structures,
            ChunkAccess chunk) {
        // Worldgen tasks run cooperatively on one JS thread and the export never suspends, so the
        // map needs no lock (TeaVM monitors would only make this path async).
        Slot slot = SLOTS.get(randomState);
        if (slot == null) {
            slot = new Slot();
            SLOTS.put(randomState, slot);
            try {
                net.minecraft.core.Registry<Biome> biomeRegistry =
                        structures.registryAccess().lookupOrThrow(Registries.BIOME);
                slot.biomeIds = biomeRegistry.asHolderIdMap();
                long seed = ((WorldgenSeedSource262) (Object) structures).gaius$worldSeed();
                byte[] ir = GaiusWorldgenExport262.export(generator, randomState, structures.registryAccess(),
                        biomeRegistry::getId, chunk.getHeightAccessorForGeneration(), seed, PROFILE_26_2);
                slot.key = BrowserWorldgenKernel.allocateKey();
                slot.failed = !BrowserWorldgenKernel.registerGenerator(slot.key, ir);
            } catch (RuntimeException e) {
                slot.failed = true;
                System.out.println("[Gaius] worldgen kernel export skipped: " + e);
            }
        }
        return slot.failed || !BrowserWorldgenKernel.generatorUsable(slot.key) ? null : slot;
    }

    private static boolean eligible(ChunkAccess chunk, Blender blender) {
        return chunk instanceof ProtoChunk
                && blender.isEmpty()
                && !chunk.isUpgrading()
                && chunk.getBelowZeroRetrogen() == null;
    }

    /** Marks the slot failed unless the error was a transient refusal for this one job. */
    private static void failed(Slot slot, String what, String message) {
        if (!BrowserWorldgenKernel.isTransient(message)) {
            slot.failed = true;
            System.out.println("[Gaius] worldgen kernel " + what + " failed, using the Java path: " + message);
        }
    }

    /** Hook in place of {@code NoiseBasedChunkGenerator.fillFromNoise}; {@code null} runs vanilla. */
    public static CompletableFuture<ChunkAccess> fillFromNoise(
            NoiseBasedChunkGenerator generator,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {
        if (!BrowserWorldgenKernel.available() || structureManager == null || !eligible(chunk, blender)) {
            return null;
        }
        NoiseSettings noise = generator.generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        if (noise.height() <= 0) {
            return null;
        }
        Slot slot = slot(generator, randomState, structureManager, chunk);
        if (slot == null) {
            return null;
        }
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        ChunkPos pos = chunk.getPos();
        int[] beard = beard(structureManager, pos);
        Supplier<CompletableFuture<ChunkAccess>> vanilla = () -> ((WorldgenKernelHooks262) (Object) generator)
                .gaius$fillFromNoiseVanilla(blender, randomState, structureManager, chunk);
        boolean keep = BrowserWorldgenKernel.surfaceSupported();
        try {
            BrowserWorldgenKernel.submitTerrain(slot.key, pos.x(), pos.z(),
                    keep ? BrowserWorldgenKernel.FLAG_KEEP_NOISE : 0, beard, null,
                    // The result object holds fresh typed arrays (worldgen-kernel.js flatten), so it can
                    // be read later on a Java thread instead of inside the JS callback.
                    result -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        int token = BrowserWorldgenKernel.chunkInt(result, "token");
                        TerrainData data;
                        try {
                            data = TerrainData.read(chunk, result);
                        } catch (RuntimeException e) {
                            BrowserWorldgenKernel.dropKeptNoise(token);
                            slot.failed = true;
                            BrowserWorldgenKernel.disable("terrain result rejected: " + e);
                            forward(future, vanilla);
                            return;
                        }
                        forward(future, () -> CompletableFuture.supplyAsync(() -> {
                            LevelChunkSection[] built;
                            try {
                                built = data.buildSections(chunk);
                            } catch (RuntimeException e) {
                                // Nothing of the chunk changed yet: vanilla fills it from scratch.
                                BrowserWorldgenKernel.dropKeptNoise(token);
                                slot.failed = true;
                                BrowserWorldgenKernel.disable("terrain result install failed: " + e);
                                return null;
                            }
                            data.commit(chunk, built);
                            keepNoise(chunk, slot, token, beard);
                            return chunk;
                        }, Util.backgroundExecutor().forName("wgen_fill_noise"))
                                .thenCompose(c -> c != null ? CompletableFuture.completedFuture(c) : vanilla.get()));
                    })),
                    message -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        failed(slot, "terrain", message);
                        forward(future, vanilla);
                    })));
        } catch (RuntimeException e) {
            // Nothing was dispatched: the caller runs vanilla.
            failed(slot, "terrain submit", String.valueOf(e));
            return null;
        }
        return future;
    }

    // ---- SURFACE (kernel surface rules on the kept noise chunk) ----

    /**
     * A noise chunk the kernel kept for the SURFACE step of the same proto chunk object, told
     * apart by {@code System.identityHashCode}. A chunk saved and loaded again between NOISE and
     * SURFACE is a new object and takes the vanilla surface step; should its identity hash match
     * by chance, its blocks are still the kept NOISE output (saved as is), so the diff still fits.
     */
    private static final class KeptNoise {
        final int identity;
        final int token;
        final int[] beard;

        KeptNoise(int identity, int token, int[] beard) {
            this.identity = identity;
            this.token = token;
            this.beard = beard;
        }
    }

    /**
     * Most kept noise chunks remembered per generator: the facade keeps at most 32 MB of them
     * (about 300 chunks), and a chunk on the normal path takes its entry at SURFACE right away.
     */
    private static final int MAX_KEPT = 256;

    private static void keepNoise(ChunkAccess chunk, Slot slot, int token, int[] beard) {
        if (token == 0) {
            return;
        }
        KeptNoise previous = slot.kept.put(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()),
                new KeptNoise(System.identityHashCode(chunk), token, beard));
        if (previous != null) {
            BrowserWorldgenKernel.dropKeptNoise(previous.token);
        }
        while (slot.kept.size() > MAX_KEPT) {
            BrowserWorldgenKernel.dropKeptNoise(slot.kept.removeFirst().token);
        }
    }

    /**
     * Hook at the head of {@code ChunkStatusTasks.generateSurface}; {@code null} runs vanilla. Only
     * a chunk whose NOISE step ran in the kernel (and was kept) takes the kernel surface rules;
     * the changed blocks go through {@code ChunkAccess.setBlockState} plus the fluid
     * post-processing mark, like vanilla's surface block column.
     */
    public static CompletableFuture<ChunkAccess> generateSurface(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk) {
        ServerLevel level = context.level();
        RandomState randomState = level.getChunkSource().randomState();
        Slot slot = SLOTS.get(randomState);
        KeptNoise kept = slot == null ? null : slot.kept.remove(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
        if (kept == null) {
            return null;
        }
        if (kept.identity != System.identityHashCode(chunk) || !BrowserWorldgenKernel.available() || slot.failed
                || !(context.generator() instanceof NoiseBasedChunkGenerator generator)
                || !BrowserWorldgenKernel.generatorUsable(slot.key)) {
            BrowserWorldgenKernel.dropKeptNoise(kept.token);
            return null;
        }
        WorldGenRegion region = new WorldGenRegion(level, cache, step, chunk);
        if (!Blender.of(region).isEmpty()) {
            BrowserWorldgenKernel.dropKeptNoise(kept.token);
            return null;
        }
        StructureManager structures = level.structureManager().forWorldGenRegion(region);
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        ChunkPos pos = chunk.getPos();
        Supplier<CompletableFuture<ChunkAccess>> vanilla = () -> {
            generator.buildSurface(region, structures, randomState, chunk);
            return CompletableFuture.completedFuture(chunk);
        };
        int[] ring;
        try {
            ring = ringBiomes(region.getBiomeManager(), slot.biomeIds, chunk);
        } catch (RuntimeException e) {
            BrowserWorldgenKernel.dropKeptNoise(kept.token);
            failed(slot, "surface ring", String.valueOf(e));
            return null;
        }
        try {
            BrowserWorldgenKernel.submitSurface(slot.key, kept.token, pos.x(), pos.z(), kept.beard, ring,
                    diff -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        int[] positions = BrowserWorldgenKernel.chunkInts(diff, "positions");
                        int[] states = BrowserWorldgenKernel.chunkInts(diff, "states");
                        BlockState[] resolved = surfaceStates(chunk, diff, positions, states);
                        if (resolved == null) {
                            slot.failed = true;
                            BrowserWorldgenKernel.disable("surface result rejected");
                            forward(future, vanilla);
                            return;
                        }
                        applySurface(chunk, positions, resolved);
                        future.complete(chunk);
                    })),
                    message -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        failed(slot, "surface", message);
                        forward(future, vanilla);
                    })));
        } catch (RuntimeException e) {
            failed(slot, "surface submit", String.valueOf(e));
            return null;
        }
        return future;
    }

    /** Validates a surface diff; {@code null} when it does not fit the chunk. */
    private static BlockState[] surfaceStates(ChunkAccess chunk, BrowserWorldgenKernel.KernelChunk diff,
            int[] positions, int[] states) {
        ChunkPos pos = chunk.getPos();
        if (positions == null || states == null || positions.length != states.length
                || BrowserWorldgenKernel.chunkInt(diff, "chunkX") != pos.x()
                || BrowserWorldgenKernel.chunkInt(diff, "chunkZ") != pos.z()) {
            return null;
        }
        int sections = chunk.getSectionsCount();
        BlockState[] resolved = new BlockState[states.length];
        for (int i = 0; i < states.length; i++) {
            if ((positions[i] >>> 12) >= sections) {
                return null;
            }
            resolved[i] = Block.BLOCK_STATE_REGISTRY.byId(states[i]);
            if (resolved[i] == null) {
                return null;
            }
        }
        return resolved;
    }

    private static void applySurface(ChunkAccess chunk, int[] positions, BlockState[] states) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int minSection = chunk.getMinSectionY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < positions.length; i++) {
            int packed = positions[i];
            int section = packed >>> 12;
            int index = packed & 4095;
            cursor.set(minX + (index & 15), ((minSection + section) << 4) + (index >>> 8),
                    minZ + ((index >>> 4) & 15));
            BlockState state = states[i];
            chunk.setBlockState(cursor, state);
            if (!state.getFluidState().isEmpty()) {
                chunk.markPosForPostProcessing(cursor);
            }
        }
    }

    /**
     * Runs a result handler; if it throws before completing {@code future}, the chunk falls back to
     * the vanilla future (and to an exceptional completion when that throws too), so a dispatched
     * future is never left pending.
     */
    private static void guarded(CompletableFuture<ChunkAccess> future, Supplier<CompletableFuture<ChunkAccess>> vanilla,
            Runnable handler) {
        try {
            handler.run();
        } catch (Throwable t) {
            if (!future.isDone()) {
                System.out.println("[Gaius] worldgen kernel result handler failed, using the Java path: " + t);
                forward(future, vanilla);
            }
        }
    }

    /** Completes {@code future} with the outcome of {@code source} (which may also throw). */
    private static void forward(CompletableFuture<ChunkAccess> future, Supplier<CompletableFuture<ChunkAccess>> source) {
        CompletableFuture<ChunkAccess> inner;
        try {
            inner = source.get();
        } catch (Throwable t) {
            future.completeExceptionally(t);
            return;
        }
        inner.whenComplete((c, t) -> {
            if (t != null) {
                future.completeExceptionally(t);
            } else {
                future.complete(c);
            }
        });
    }

    /** Hook in place of {@code NoiseBasedChunkGenerator.createBiomes}; {@code null} runs vanilla. */
    public static CompletableFuture<ChunkAccess> createBiomes(
            NoiseBasedChunkGenerator noiseGenerator, RandomState randomState, Blender blender,
            StructureManager structureManager, ChunkAccess chunk) {
        if (!BrowserWorldgenKernel.available() || structureManager == null || !eligible(chunk, blender)) {
            return null;
        }
        Slot slot = slot(noiseGenerator, randomState, structureManager, chunk);
        if (slot == null) {
            return null;
        }
        IdMap<Holder<Biome>> biomes = slot.biomeIds;
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        ChunkPos pos = chunk.getPos();
        Supplier<CompletableFuture<ChunkAccess>> vanilla = () -> ((WorldgenKernelHooks262) (Object) noiseGenerator)
                .gaius$createBiomesVanilla(randomState, blender, structureManager, chunk);
        try {
            BrowserWorldgenKernel.submitBiomes(slot.key, pos.x(), pos.z(),
                    ids -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        int[] values = BrowserWorldgenKernel.ids(ids);
                        Holder<Biome>[] holders = resolveBiomes(biomes, values);
                        if (holders == null || values.length != chunk.getSectionsCount() * 64) {
                            slot.failed = true;
                            BrowserWorldgenKernel.disable("biome result rejected");
                            forward(future, vanilla);
                            return;
                        }
                        try {
                            int minQuartY = QuartPos.fromBlock(chunk.getMinY());
                            chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
                                int y = qy - minQuartY;
                                return holders[((y >> 2) << 6) | ((y & 3) << 4) | ((qz & 3) << 2) | (qx & 3)];
                            }, null);
                            future.complete(chunk);
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    })),
                    message -> BrowserWorldgenKernel.runOnJavaThread(() -> guarded(future, vanilla, () -> {
                        failed(slot, "biomes", message);
                        forward(future, vanilla);
                    })));
        } catch (RuntimeException e) {
            failed(slot, "biomes submit", String.valueOf(e));
            return null;
        }
        return future;
    }

    /** Quart columns around a chunk outside its own 4 x 4. */
    private static final int RING_COLUMNS = 20;

    /**
     * The biomes the neighbouring chunks store around {@code chunk}, as the kernel's ring:
     * for each quart column of the 6 x 6 grid outside the chunk's own 4 x 4 (x outer, z inner,
     * starting one quart before the chunk), every quart y from the bottom of the level, as biome
     * registry ids ({@code -1} for a biome without one; the kernel then refuses the chunk). Read
     * through {@code biomeManager} (the region's), like vanilla's surface rules.
     */
    static int[] ringBiomes(BiomeManager biomeManager, IdMap<Holder<Biome>> biomeIds, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minQx = (pos.x() << 2) - 1;
        int minQz = (pos.z() << 2) - 1;
        int minQy = QuartPos.fromBlock(chunk.getMinY());
        int quartsY = chunk.getSectionsCount() * 4;
        int[] ring = new int[RING_COLUMNS * quartsY];
        Holder<Biome> last = null;
        int lastId = -1;
        int k = 0;
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                if (x >= 1 && x <= 4 && z >= 1 && z <= 4) {
                    continue;
                }
                for (int y = 0; y < quartsY; y++) {
                    Holder<Biome> holder = biomeManager.getNoiseBiomeAtQuart(minQx + x, minQy + y, minQz + z);
                    if (holder != last) {
                        last = holder;
                        lastId = biomeIds.getId(holder);
                    }
                    ring[k++] = lastId;
                }
            }
        }
        return ring;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] resolveBiomes(IdMap<Holder<Biome>> biomes, int[] ids) {
        Holder<Biome>[] holders = new Holder[ids.length];
        for (int i = 0; i < ids.length; i++) {
            holders[i] = biomes.byId(ids[i]);
            if (holders[i] == null) {
                return null;
            }
        }
        return holders;
    }

    // ---- beardifier ----

    /**
     * {@code Beardifier.forStructuresInChunk}, packed for the kernel:
     * {@code [flags, affected x6, rigidCount, rigid x8 ..., junctionCount, junction x3 ...]}.
     */
    static int[] beard(StructureManager structures, ChunkPos chunkPos) {
        List<StructureStart> starts = structures.startsForStructure(
                chunkPos, s -> s.terrainAdaptation() != TerrainAdjustment.NONE);
        if (starts.isEmpty()) {
            return NO_BEARD;
        }
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        IntBuffer rigids = new IntBuffer();
        IntBuffer junctions = new IntBuffer();
        int rigidCount = 0;
        int junctionCount = 0;
        BoundingBox any = null;
        for (StructureStart start : starts) {
            TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, 12)) {
                    continue;
                }
                if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        rigid(rigids, pool.getBoundingBox(), adjustment, pool.getGroundLevelDelta());
                        rigidCount++;
                        any = include(any, piece.getBoundingBox());
                    }
                    for (JigsawJunction junction : pool.getJunctions()) {
                        int jx = junction.getSourceX();
                        int jz = junction.getSourceZ();
                        if (jx > minX - 12 && jz > minZ - 12 && jx < minX + 15 + 12 && jz < minZ + 15 + 12) {
                            junctions.add(jx).add(junction.getSourceGroundY()).add(jz);
                            junctionCount++;
                            any = include(any, new BoundingBox(new BlockPos(jx, junction.getSourceGroundY(), jz)));
                        }
                    }
                } else {
                    rigid(rigids, piece.getBoundingBox(), adjustment, 0);
                    rigidCount++;
                    any = include(any, piece.getBoundingBox());
                }
            }
        }
        if (any == null) {
            return NO_BEARD;
        }
        BoundingBox affected = any.inflatedBy(24);
        IntBuffer out = new IntBuffer();
        out.add(1).add(affected.minX()).add(affected.minY()).add(affected.minZ())
                .add(affected.maxX()).add(affected.maxY()).add(affected.maxZ());
        out.add(rigidCount).addAll(rigids).add(junctionCount).addAll(junctions);
        return out.toArray();
    }

    private static BoundingBox include(BoundingBox box, BoundingBox add) {
        return box == null ? add : BoundingBox.encapsulating(box, add);
    }

    private static void rigid(IntBuffer out, BoundingBox box, TerrainAdjustment adjustment, int groundDelta) {
        int code = switch (adjustment) {
            case NONE -> 0;
            case BURY -> 1;
            case BEARD_THIN -> 2;
            case BEARD_BOX -> 3;
            case ENCAPSULATE -> 4;
        };
        out.add(box.minX()).add(box.minY()).add(box.minZ()).add(box.maxX()).add(box.maxY()).add(box.maxZ())
                .add(code).add(groundDelta);
    }

    private static final class IntBuffer {
        private int[] data = new int[32];
        private int size;

        IntBuffer add(int v) {
            if (size == data.length) {
                int[] grown = new int[size * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = v;
            return this;
        }

        IntBuffer addAll(IntBuffer other) {
            for (int i = 0; i < other.size; i++) {
                add(other.data[i]);
            }
            return this;
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    // ---- result installation ----

    /** A validated terrain result; {@link #read} rejects anything that does not fit the chunk. */
    private static final class TerrainData {
        int sectionCount;
        int[] paletteOffsets;
        BlockState[] palette;
        short[] indices;
        byte[] uniform;
        int[] worldSurface;
        int[] oceanFloor;
        int[] post;

        static TerrainData read(ChunkAccess chunk, BrowserWorldgenKernel.KernelChunk result) {
            TerrainData d = new TerrainData();
            ChunkPos pos = chunk.getPos();
            if (BrowserWorldgenKernel.chunkInt(result, "chunkX") != pos.x()
                    || BrowserWorldgenKernel.chunkInt(result, "chunkZ") != pos.z()
                    || BrowserWorldgenKernel.chunkInt(result, "minY") != chunk.getMinY()) {
                throw new IllegalStateException("kernel chunk does not match " + pos);
            }
            d.sectionCount = BrowserWorldgenKernel.chunkInt(result, "sectionCount");
            if (d.sectionCount != chunk.getSectionsCount()) {
                throw new IllegalStateException("kernel chunk has " + d.sectionCount + " sections");
            }
            d.paletteOffsets = BrowserWorldgenKernel.chunkInts(result, "paletteOffsets");
            int[] ids = BrowserWorldgenKernel.chunkInts(result, "palettes");
            d.indices = BrowserWorldgenKernel.chunkIndices(result);
            d.uniform = BrowserWorldgenKernel.chunkBytes(result, "uniform");
            d.worldSurface = BrowserWorldgenKernel.chunkInts(result, "worldSurface");
            d.oceanFloor = BrowserWorldgenKernel.chunkInts(result, "oceanFloor");
            d.post = BrowserWorldgenKernel.chunkInts(result, "postProcessing");
            if (d.paletteOffsets == null || ids == null || d.worldSurface == null || d.oceanFloor == null
                    || d.post == null || d.paletteOffsets.length != d.sectionCount + 1
                    || d.indices.length != d.sectionCount * 4096 || d.uniform.length != d.sectionCount
                    || d.worldSurface.length != 256 || d.oceanFloor.length != 256) {
                throw new IllegalStateException("kernel chunk arrays have unexpected sizes");
            }
            d.palette = new BlockState[ids.length];
            for (int i = 0; i < ids.length; i++) {
                d.palette[i] = Block.BLOCK_STATE_REGISTRY.byId(ids[i]);
                if (d.palette[i] == null) {
                    throw new IllegalStateException("unknown block state id " + ids[i]);
                }
            }
            for (int s = 0; s < d.sectionCount; s++) {
                int size = d.paletteOffsets[s + 1] - d.paletteOffsets[s];
                if (size <= 0) {
                    throw new IllegalStateException("empty section palette");
                }
                if (d.uniform[s] == 0) {
                    for (int i = s * 4096, end = i + 4096; i < end; i++) {
                        if ((d.indices[i] & 0xFFFF) >= size) {
                            throw new IllegalStateException("palette index out of range");
                        }
                    }
                }
            }
            return d;
        }

        /**
         * Every section the result changes, built off the chunk (bulk: {@code GaiusKernelSections}
         * builds each block state container in one step, like a loaded section; block by block
         * like {@code doFill}, on a copy of the old section, only for a palette the bulk path
         * refuses); {@code null} entries keep the old section. Throws without touching the chunk,
         * so a result that cannot be installed leaves it to vanilla.
         */
        LevelChunkSection[] buildSections(ChunkAccess chunk) {
            LevelChunkSection[] built = new LevelChunkSection[sectionCount];
            for (int s = 0; s < sectionCount; s++) {
                int base = paletteOffsets[s];
                int size = paletteOffsets[s + 1] - base;
                boolean isUniform = uniform[s] != 0;
                if (isUniform && palette[base].isAir()) {
                    continue;
                }
                LevelChunkSection old = chunk.getSection(s);
                PalettedContainer<BlockState> states =
                        GaiusKernelSections.container(palette, base, size, indices, s * 4096, isUniform);
                if (states != null) {
                    built[s] = new LevelChunkSection(states, old.getBiomes());
                    continue;
                }
                LevelChunkSection section = old.copy();
                if (isUniform) {
                    BlockState state = palette[base];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                section.setBlockState(x, y, z, state, false);
                            }
                        }
                    }
                } else {
                    int offset = s * 4096;
                    for (int i = 0; i < 4096; i++) {
                        BlockState state = palette[base + (indices[offset + i] & 0xFFFF)];
                        if (!state.isAir()) {
                            section.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, state, false);
                        }
                    }
                }
                built[s] = section;
            }
            return built;
        }

        /**
         * Installs {@link #buildSections}'s sections (plain assignments), then the heightmaps and
         * post-processing marks (vanilla bounds-checks both).
         */
        void commit(ChunkAccess chunk, LevelChunkSection[] built) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sectionCount; s++) {
                if (built[s] != null) {
                    sections[s] = built[s];
                }
            }
            ChunkPos pos = chunk.getPos();
            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();
            int minY = chunk.getMinY();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            Heightmap oceanFloorMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap worldSurfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            for (int column = 0; column < 256; column++) {
                int x = column & 15;
                int z = column >>> 4;
                if (oceanFloor[column] > minY) {
                    int y = oceanFloor[column] - 1;
                    oceanFloorMap.update(x, y, z, chunk.getBlockState(cursor.set(minX + x, y, minZ + z)));
                }
                if (worldSurface[column] > minY) {
                    int y = worldSurface[column] - 1;
                    worldSurfaceMap.update(x, y, z, chunk.getBlockState(cursor.set(minX + x, y, minZ + z)));
                }
            }
            int minSection = chunk.getMinSectionY();
            for (int entry : post) {
                int section = entry >>> 16;
                int packed = entry & 0xFFFF;
                chunk.markPosForPostProcessing(new BlockPos(
                        minX + (packed & 15), ((minSection + section) << 4) + ((packed >>> 4) & 15),
                        minZ + ((packed >>> 8) & 15)));
            }
        }
    }
}
