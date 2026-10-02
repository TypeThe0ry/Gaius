package dev.gaius.browser.kernel.light;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.GaiusLightColumnAccess;
import net.minecraft.world.level.lighting.LightEngine;
import org.teavm.jso.JSObject;

/**
 * First light of generated chunks in the Rust light kernel (port/native/crates/gaius-light,
 * {@code light_column} jobs of port/native/crates/gaius-light-wasm), run by the kernel worker
 * pool instead of on the integrated server Worker. Used on the 26.2 and 26.3 profiles, whose
 * light engine the kernel mirrors.
 *
 * <p>{@code dev.gaius.tools.kernel.LightKernelPatches} routes
 * {@code ThreadedLevelLightEngine.lightChunk} here. For a chunk that is not lit yet, the
 * PRE_UPDATE task in which vanilla calls {@code propagateLightSources} instead turns the column's
 * light on and snapshots it into a job: the palettes of its sections and of the four
 * neighbours, their stored light and which sections store light. When the result arrives,
 * another PRE_UPDATE task writes the new layers into the vanilla storage (through
 * {@code getDataLayerToWrite}, so the changed sections reach clients like any vanilla light
 * change) and replays the light that leaves the column into the neighbours with vanilla's own
 * propagation step, so {@code runLightUpdates} carries it on exactly like vanilla. The
 * POST_UPDATE task then marks the chunk light-correct and completes the future, as vanilla's
 * does.
 *
 * <p>Block changes inside a column while its job is in flight ({@code checkBlock}, reported
 * through {@link #noteCheckBlock}) are checked again by the vanilla engine right after the
 * result is applied, so the result never keeps light from blocks that changed meanwhile.
 * Incremental lighting of loaded chunks stays on the vanilla engine.
 *
 * <p>Fallbacks: without a kernel host, with a switch off (see {@link LightKernelHost}), after a
 * failed or timed-out job, or once {@value #MAX_FAILURES} jobs failed in a row (which disables
 * the kernel for the session), chunks are lit by the vanilla engine.
 */
public final class BrowserLightKernel {
    private static final int JOB_MAGIC = 0x424a4b47;
    private static final int RESULT_MAGIC = 0x53524b47;
    private static final int ABI_VERSION = 1;
    private static final int KIND_LIGHT_COLUMN = 0x0301;
    private static final int JOB_VERSION = 1;
    private static final int RESULT_VERSION = 1;
    private static final int HEADER_LEN = 16;
    private static final int RESULT_HEADER_LEN = 32;
    private static final int OPS_INITIAL = 1;
    private static final int FLAG_SKY = 1;
    private static final int FLAG_BLOCK = 2;
    private static final int FLAG_EMIT_OUTGOING = 32;
    private static final int FLAG_OMIT_UNCHANGED = 64;
    private static final int SKY_STORING = 1;
    private static final int BLOCK_STORING = 2;
    private static final int SKY_DATA = 4;
    private static final int BLOCK_DATA = 8;
    private static final int ENCODING_SINGLE = 0;
    private static final int ENCODING_NETWORK = 1;
    private static final int LAYER_BYTES = 2048;
    private static final int OUTGOING_LEN = 12;
    private static final int MAX_FAILURES = 3;
    /** Ring sides in job order: north (z-1), south, west (x-1), east. */
    private static final int[] SIDE_DX = {0, 0, -1, 1};
    private static final int[] SIDE_DZ = {-1, 1, 0, 0};
    private static final Direction[] SIDE_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /** Chunks whose PRE_UPDATE task found every job slot taken, oldest first. */
    private static final ArrayDeque<Runnable> WAITING = new ArrayDeque<>();
    /** Snapshotted columns whose result is not applied yet, by {@code ChunkPos.pack}. */
    private static final Long2ObjectOpenHashMap<Job> IN_FLIGHT = new Long2ObjectOpenHashMap<>();
    private static boolean disabled;
    private static String disabledReason = "";
    private static int failuresInARow;
    private static int submitted;
    private static int maxInFlight;
    private static int nextJobId;
    private static long jobs;
    private static long applied;
    private static long fallbacks;
    private static long rechecks;
    /** Job encoding buffer, reused: only one snapshot is encoded at a time (light task queue). */
    private static byte[] scratch = new byte[1 << 19];
    private static int length;
    private static ByteBuf sectionBuffer;
    private static FriendlyByteBuf sectionWriter;

    private BrowserLightKernel() {
    }

    /** One column between its snapshot and its applied result. */
    private static final class Job {
        final ThreadedLevelLightEngine engine;
        final BrowserLightEngineHooks hooks;
        final ChunkAccess chunk;
        final CompletableFuture<ChunkAccess> done;
        final int id;
        final int chunkX;
        final int chunkZ;
        final long column;
        final int minSection;
        final int lightSections;
        final LightEngine<?, ?> sky;
        final LightEngine<?, ?> block;
        /** The column's layers as the job saw them, to tell whether they changed in flight. */
        final byte[][] skyBefore;
        final byte[][] blockBefore;
        /** SKY_STORING/BLOCK_STORING per light section of the column (slot 0) and each side. */
        final byte[] storing;
        long[] recheck;
        int recheckCount;

        Job(ThreadedLevelLightEngine engine, BrowserLightEngineHooks hooks, ChunkAccess chunk,
                CompletableFuture<ChunkAccess> done, int id, LightEngine<?, ?> sky, LightEngine<?, ?> block) {
            this.engine = engine;
            this.hooks = hooks;
            this.chunk = chunk;
            this.done = done;
            this.id = id;
            ChunkPos pos = chunk.getPos();
            this.chunkX = pos.x();
            this.chunkZ = pos.z();
            this.column = ChunkPos.pack(chunkX, chunkZ);
            this.minSection = chunk.getMinSectionY();
            this.lightSections = chunk.getSectionsCount() + 2;
            this.sky = sky;
            this.block = block;
            this.skyBefore = new byte[lightSections][];
            this.blockBefore = new byte[lightSections][];
            this.storing = new byte[5 * lightSections];
        }

        void addRecheck(long pos) {
            if (recheck == null) {
                recheck = new long[16];
            } else if (recheckCount == recheck.length) {
                long[] grown = new long[recheckCount * 2];
                System.arraycopy(recheck, 0, grown, 0, recheckCount);
                recheck = grown;
            }
            recheck[recheckCount++] = pos;
        }
    }

    /** True while new chunks go to the kernel. */
    public static boolean enabled() {
        return !disabled && LightKernelHost.available();
    }

    public static String disabledReason() {
        return disabledReason;
    }

    public static long jobs() {
        return jobs;
    }

    public static long applied() {
        return applied;
    }

    public static long fallbacks() {
        return fallbacks;
    }

    public static long rechecks() {
        return rechecks;
    }

    /**
     * Replacement for {@code ThreadedLevelLightEngine.lightChunk}: the future of the kernel path,
     * or null to let the caller run vanilla (chunk already lit, kernel off or unavailable).
     */
    public static CompletableFuture<ChunkAccess> lightChunk(ThreadedLevelLightEngine engine, ChunkAccess chunk,
            boolean lighted) {
        if (lighted || !enabled()) {
            return null;
        }
        BrowserLightEngineHooks hooks = (BrowserLightEngineHooks) (Object) engine;
        ChunkPos pos = chunk.getPos();
        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> done = new CompletableFuture<>();
        hooks.gaius$addLightTask(pos.x(), pos.z(), BrowserLightEngineHooks.PRE_UPDATE,
                () -> start(engine, hooks, chunk, done));
        return done;
    }

    /**
     * Head of {@code ThreadedLevelLightEngine.checkBlock}: remembers block changes inside columns
     * whose job is in flight, and in the one-block ring around them (whose states the job used
     * for the light leaving the column), to check them again once the result is applied.
     */
    public static void noteCheckBlock(ThreadedLevelLightEngine engine, BlockPos pos) {
        if (IN_FLIGHT.isEmpty() || pos == null) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        int chunkX = SectionPos.blockToSectionCoord(x);
        int chunkZ = SectionPos.blockToSectionCoord(z);
        long packed = pos.asLong();
        noteCheck(engine, chunkX, chunkZ, packed);
        int localX = x & 15;
        int localZ = z & 15;
        if (localX == 0) {
            noteCheck(engine, chunkX - 1, chunkZ, packed);
        } else if (localX == 15) {
            noteCheck(engine, chunkX + 1, chunkZ, packed);
        }
        if (localZ == 0) {
            noteCheck(engine, chunkX, chunkZ - 1, packed);
        } else if (localZ == 15) {
            noteCheck(engine, chunkX, chunkZ + 1, packed);
        }
    }

    private static void noteCheck(ThreadedLevelLightEngine engine, int chunkX, int chunkZ, long pos) {
        Job job = IN_FLIGHT.get(ChunkPos.pack(chunkX, chunkZ));
        if (job != null && job.engine == engine) {
            job.addRecheck(pos);
        }
    }

    /** Runs inside a PRE_UPDATE light task. */
    private static void start(ThreadedLevelLightEngine engine, BrowserLightEngineHooks hooks, ChunkAccess chunk,
            CompletableFuture<ChunkAccess> done) {
        if (disabled || !LightKernelHost.available()) {
            lightVanillaInTask(hooks, chunk, done);
            return;
        }
        if (maxInFlight == 0) {
            maxInFlight = LightKernelHost.maxInFlight();
        }
        if (submitted >= maxInFlight) {
            ChunkPos pos = chunk.getPos();
            WAITING.addLast(() -> {
                hooks.gaius$addLightTask(pos.x(), pos.z(), BrowserLightEngineHooks.PRE_UPDATE,
                        () -> start(engine, hooks, chunk, done));
                engine.tryScheduleUpdate();
            });
            return;
        }
        Job job;
        try {
            job = snapshot(engine, hooks, chunk, done);
        } catch (RuntimeException error) {
            noteFailure("snapshot failed: " + error, false);
            lightVanillaInTask(hooks, chunk, done);
            return;
        }
        if (job == null) {
            lightVanillaInTask(hooks, chunk, done);
            return;
        }
        submitted++;
        jobs++;
        IN_FLIGHT.put(job.column, job);
        boolean sent = LightKernelHost.submit(scratch, length, job.chunkX, job.chunkZ, job.id,
                result -> runOnJavaThread(() -> onResult(job, result)),
                failure -> runOnJavaThread(() -> onFailure(job, failure)));
        if (!sent) {
            submitted--;
            IN_FLIGHT.remove(job.column);
            // Same task, nothing applied yet: light it the vanilla way right here.
            lightVanillaInTask(hooks, chunk, done);
        }
    }

    /** Vanilla first light from inside a PRE_UPDATE task, then completion after the update. */
    private static void lightVanillaInTask(BrowserLightEngineHooks hooks, ChunkAccess chunk,
            CompletableFuture<ChunkAccess> done) {
        fallbacks++;
        ChunkPos pos = chunk.getPos();
        hooks.gaius$propagateLightSourcesVanilla(pos);
        completeAfterUpdate(hooks, pos.x(), pos.z(), chunk, done);
    }

    private static void completeAfterUpdate(BrowserLightEngineHooks hooks, int chunkX, int chunkZ, ChunkAccess chunk,
            CompletableFuture<ChunkAccess> done) {
        hooks.gaius$addLightTask(chunkX, chunkZ, BrowserLightEngineHooks.POST_UPDATE, () -> {
            chunk.setLightCorrect(true);
            done.complete(chunk);
        });
    }

    private static void onResult(Job job, JSObject buffer) {
        byte[] result;
        try {
            result = new byte[LightKernelHost.resultLength(buffer)];
            LightKernelHost.copyResult(buffer, result);
        } catch (RuntimeException error) {
            onFailure(job, "kernel-error:result copy failed: " + error);
            return;
        }
        release();
        schedule(job, () -> {
            IN_FLIGHT.remove(job.column);
            try {
                apply(job, result);
                failuresInARow = 0;
                applied++;
            } catch (RuntimeException error) {
                // Layers already written stay: they are a valid partial lighting, and vanilla's
                // propagateLightSources only raises levels on top of them.
                noteFailure("apply failed: " + error, false);
                fallbacks++;
                job.hooks.gaius$propagateLightSourcesVanilla(job.chunk.getPos());
            }
            // Blocks that changed while the job ran (the kernel saw their old states): vanilla
            // checks them again in this update, whether or not the result could be applied.
            recheck(job);
            completeAfterUpdate(job.hooks, job.chunkX, job.chunkZ, job.chunk, job.done);
        });
    }

    private static void onFailure(Job job, String failure) {
        release();
        noteFailure(failure, LightKernelHost.isTransient(failure));
        schedule(job, () -> {
            IN_FLIGHT.remove(job.column);
            lightVanillaInTask(job.hooks, job.chunk, job.done);
        });
    }

    /** Queues a PRE_UPDATE task for the job's column from outside the light task queue. */
    private static void schedule(Job job, Runnable task) {
        try {
            job.hooks.gaius$addLightTask(job.chunkX, job.chunkZ, BrowserLightEngineHooks.PRE_UPDATE, task);
            job.engine.tryScheduleUpdate();
        } catch (RuntimeException error) {
            // The engine is closing with its level; nothing waits for this chunk any more.
            IN_FLIGHT.remove(job.column);
            job.done.completeExceptionally(error);
        }
    }

    private static void release() {
        submitted--;
        Runnable next = WAITING.pollFirst();
        if (next != null) {
            next.run();
        }
    }

    private static void noteFailure(String message, boolean transientFailure) {
        if (transientFailure) {
            LightKernelHost.log("[light-kernel] job refused, chunk lit by the vanilla engine: " + message);
            return;
        }
        failuresInARow++;
        if (failuresInARow >= MAX_FAILURES) {
            disable("disabled after " + failuresInARow + " failures in a row: " + message);
        } else {
            LightKernelHost.log("[light-kernel] job failed, chunk lit by the vanilla engine: " + message);
        }
    }

    private static void disable(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        disabledReason = reason;
        LightKernelHost.log("[light-kernel] " + reason + "; chunks are lit by the vanilla engine");
        // Everything waiting for a slot goes back through start(), which now lights it the
        // vanilla way in its own light task.
        while (!WAITING.isEmpty()) {
            WAITING.pollFirst().run();
        }
    }

    private static void runOnJavaThread(Runnable task) {
        Thread thread = new Thread(task, "gaius-light-kernel");
        thread.setDaemon(true);
        thread.start();
    }

    // --- job encoding -------------------------------------------------------------------------

    private static void ensure(int extra) {
        if (length + extra > scratch.length) {
            int capacity = scratch.length;
            while (capacity < length + extra) {
                capacity *= 2;
            }
            byte[] grown = new byte[capacity];
            System.arraycopy(scratch, 0, grown, 0, length);
            scratch = grown;
        }
    }

    private static void putByte(int value) {
        ensure(1);
        scratch[length++] = (byte) value;
    }

    private static void putShort(int value) {
        ensure(2);
        scratch[length++] = (byte) value;
        scratch[length++] = (byte) (value >>> 8);
    }

    private static void putInt(int value) {
        ensure(4);
        scratch[length++] = (byte) value;
        scratch[length++] = (byte) (value >>> 8);
        scratch[length++] = (byte) (value >>> 16);
        scratch[length++] = (byte) (value >>> 24);
    }

    private static void putLong(long value) {
        putInt((int) value);
        putInt((int) (value >>> 32));
    }

    private static void putBytes(byte[] bytes, int offset, int count) {
        ensure(count);
        System.arraycopy(bytes, offset, scratch, length, count);
        length += count;
    }

    /** Pads to 8 bytes; the 16-byte ABI header keeps payload offsets and buffer offsets aligned. */
    private static void pad8() {
        while ((length & 7) != 0) {
            putByte(0);
        }
    }

    private static void setInt(int at, int value) {
        scratch[at] = (byte) value;
        scratch[at + 1] = (byte) (value >>> 8);
        scratch[at + 2] = (byte) (value >>> 16);
        scratch[at + 3] = (byte) (value >>> 24);
    }

    private static byte[] lightTable() {
        try {
            return BrowserLightStateTable.bytes();
        } catch (RuntimeException error) {
            // Deterministic (the registry does not change): no point in retrying per chunk.
            disable("light table unavailable: " + error);
            return null;
        }
    }

    /** Encodes the column's job into {@link #scratch}; null when the kernel cannot light it. */
    private static Job snapshot(ThreadedLevelLightEngine engine, BrowserLightEngineHooks hooks, ChunkAccess chunk,
            CompletableFuture<ChunkAccess> done) {
        LightEngine<?, ?> block = GaiusLightColumnAccess.engine(engine, LightLayer.BLOCK);
        LightEngine<?, ?> sky = GaiusLightColumnAccess.engine(engine, LightLayer.SKY);
        if (block == null) {
            return null;
        }
        byte[] table = lightTable();
        if (table == null) {
            return null;
        }
        long tableEpoch = BrowserLightStateTable.epoch();
        Job job = new Job(engine, hooks, chunk, done, nextJobId++, sky, block);
        int sectionCount = job.lightSections - 2;
        ChunkAccess[] slots = new ChunkAccess[5];
        slots[0] = chunk;
        int neighbours = 0;
        for (int side = 0; side < 4; side++) {
            LightChunk neighbour = GaiusLightColumnAccess.chunkForLighting(block,
                    job.chunkX + SIDE_DX[side], job.chunkZ + SIDE_DZ[side]);
            if (neighbour instanceof ChunkAccess access && access.getSectionsCount() == sectionCount
                    && access.getMinSectionY() == job.minSection) {
                slots[side + 1] = access;
                neighbours |= 1 << side;
            }
        }
        // propagateLightSources turns the column's light on first.
        GaiusLightColumnAccess.enableColumn(block, job.chunkX, job.chunkZ);
        if (sky != null) {
            GaiusLightColumnAccess.enableColumn(sky, job.chunkX, job.chunkZ);
        }

        length = 0;
        putInt(JOB_MAGIC);
        putShort(ABI_VERSION);
        putShort(KIND_LIGHT_COLUMN);
        putInt(job.id);
        putInt(0); // payload length, set below
        int payloadStart = length;
        putByte(JOB_VERSION);
        putByte(OPS_INITIAL);
        putShort(FLAG_BLOCK | (sky != null ? FLAG_SKY : 0) | FLAG_EMIT_OUTGOING | FLAG_OMIT_UNCHANGED);
        putInt(job.chunkX);
        putInt(job.chunkZ);
        putInt(job.minSection);
        putInt(sectionCount);
        putInt(sky != null ? GaiusLightColumnAccess.skyBottomSection(sky) : job.minSection - 1);
        putByte(neighbours);
        putByte(0);
        putShort(0);
        // The table goes inline every time: the kernel skips decoding it when the epoch matches
        // its cached copy, and the pool may hand the job to a fresh worker.
        putInt(table.length);
        putLong(tableEpoch);
        putInt(0); // no checkBlock positions
        putInt(0);
        putBytes(table, 0, table.length);
        pad8();

        // Section flags; the data bits name the stored layers that follow.
        DataLayer[][] layers = new DataLayer[5][];
        for (int slot = 0; slot < 5; slot++) {
            if (slots[slot] == null) {
                continue;
            }
            ChunkPos at = slots[slot].getPos();
            layers[slot] = new DataLayer[job.lightSections * 2];
            for (int light = 0; light < job.lightSections; light++) {
                long key = SectionPos.asLong(at.x(), job.minSection - 1 + light, at.z());
                int flags = 0;
                if (sky != null && GaiusLightColumnAccess.storing(sky, key)) {
                    flags |= SKY_STORING;
                    DataLayer data = GaiusLightColumnAccess.storedLayer(sky, key);
                    if (data != null) {
                        flags |= SKY_DATA;
                        layers[slot][light] = data;
                    }
                }
                if (GaiusLightColumnAccess.storing(block, key)) {
                    flags |= BLOCK_STORING;
                    DataLayer data = GaiusLightColumnAccess.storedLayer(block, key);
                    if (data != null) {
                        flags |= BLOCK_DATA;
                        layers[slot][job.lightSections + light] = data;
                    }
                }
                job.storing[slot * job.lightSections + light] = (byte) (flags & (SKY_STORING | BLOCK_STORING));
                putByte(flags);
            }
        }
        pad8();

        int airId = Block.getId(Blocks.AIR.defaultBlockState());
        for (int slot = 0; slot < 5; slot++) {
            if (slots[slot] == null) {
                continue;
            }
            LevelChunkSection[] sections = slots[slot].getSections();
            for (int index = 0; index < sectionCount; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    putByte(ENCODING_SINGLE);
                    putByte(0);
                    putShort(0);
                    putInt(4);
                    putInt(airId);
                } else {
                    FriendlyByteBuf writer = sectionWriter();
                    section.getStates().write(writer);
                    int bytes = writer.writerIndex();
                    putByte(ENCODING_NETWORK);
                    putByte(0);
                    putShort(0);
                    putInt(bytes);
                    ensure(bytes);
                    sectionBuffer.getBytes(0, scratch, length, bytes);
                    length += bytes;
                }
                pad8();
            }
        }

        for (int slot = 0; slot < 5; slot++) {
            if (slots[slot] == null) {
                continue;
            }
            for (int half = 0; half < 2; half++) {
                for (int light = 0; light < job.lightSections; light++) {
                    DataLayer data = layers[slot][half * job.lightSections + light];
                    if (data == null) {
                        continue;
                    }
                    ensure(LAYER_BYTES);
                    GaiusLightColumnAccess.copyLayer(data, scratch, length);
                    if (slot == 0) {
                        // The live layer changes while the job runs; keep what the job saw.
                        byte[] copy = new byte[LAYER_BYTES];
                        System.arraycopy(scratch, length, copy, 0, LAYER_BYTES);
                        (half == 0 ? job.skyBefore : job.blockBefore)[light] = copy;
                    }
                    length += LAYER_BYTES;
                }
            }
        }
        setInt(12, length - payloadStart);
        return job;
    }

    private static FriendlyByteBuf sectionWriter() {
        if (sectionWriter == null) {
            sectionBuffer = Unpooled.buffer(16 * 1024);
            sectionWriter = new FriendlyByteBuf(sectionBuffer);
        }
        sectionBuffer.clear();
        return sectionWriter;
    }

    // --- result ---------------------------------------------------------------------------------

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8;
    }

    private static int i32(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8 | (bytes[at + 2] & 0xff) << 16
                | (bytes[at + 3] & 0xff) << 24;
    }

    /** Runs inside a PRE_UPDATE light task. */
    private static void apply(Job job, byte[] result) {
        if (result.length < HEADER_LEN || i32(result, 0) != RESULT_MAGIC || u16(result, 4) != ABI_VERSION) {
            throw new IllegalStateException("bad light_column result header");
        }
        if (u16(result, 6) != 0 || i32(result, 8) != job.id) {
            throw new IllegalStateException("light_column result for another job or with an error status");
        }
        int payloadLength = i32(result, 12);
        if (payloadLength < RESULT_HEADER_LEN || HEADER_LEN + payloadLength > result.length) {
            throw new IllegalStateException("light_column result is truncated");
        }
        int end = HEADER_LEN + payloadLength;
        int base = HEADER_LEN;
        if ((result[base] & 0xff) != RESULT_VERSION || u16(result, base + 2) != job.lightSections) {
            throw new IllegalStateException("light_column result does not match the column");
        }
        int records = i32(result, base + 12);
        int flagsAt = base + RESULT_HEADER_LEN;
        int at = base + ((RESULT_HEADER_LEN + job.lightSections + 7) & ~7);
        for (int half = 0; half < 2; half++) {
            LightEngine<?, ?> engine = half == 0 ? job.sky : job.block;
            for (int light = 0; light < job.lightSections; light++) {
                if ((result[flagsAt + light] & (1 << half)) == 0) {
                    continue;
                }
                if (at + LAYER_BYTES > end || engine == null) {
                    throw new IllegalStateException("light_column result layers are inconsistent");
                }
                long key = SectionPos.asLong(job.chunkX, job.minSection - 1 + light, job.chunkZ);
                byte[] before = (half == 0 ? job.skyBefore : job.blockBefore)[light];
                GaiusLightColumnAccess.writeLayer(engine, key, result, at, before);
                at += LAYER_BYTES;
            }
        }
        if (records < 0 || at + (long) records * OUTGOING_LEN > end) {
            throw new IllegalStateException("light_column result records are truncated");
        }
        int minX = job.chunkX * 16;
        int minZ = job.chunkZ * 16;
        for (int i = 0; i < records; i++, at += OUTGOING_LEN) {
            int kind = result[at] & 0xff;
            int side = result[at + 1] & 0xff;
            int along = result[at + 2] & 0xff;
            int level = result[at + 3] & 0xff;
            int y = i32(result, at + 4);
            int entry = u16(result, at + 8);
            int emptySections = u16(result, at + 10);
            if (side > 3 || kind > 3 || along > 15 || level > 15) {
                throw new IllegalStateException("light_column result has an invalid record");
            }
            int x = side == 2 ? minX - 1 : side == 3 ? minX + 16 : minX + along;
            int z = side == 0 ? minZ - 1 : side == 1 ? minZ + 16 : minZ + along;
            LightEngine<?, ?> engine = (kind & 1) == 0 ? job.sky : job.block;
            if (engine == null) {
                continue;
            }
            GaiusLightColumnAccess.applyOutgoing(engine, kind, BlockPos.asLong(x, y, z), SIDE_DIRECTIONS[side],
                    level, entry, emptySections);
        }
        pushIntoNewSections(job);
    }

    private static void recheck(Job job) {
        for (int i = 0; i < job.recheckCount; i++) {
            BlockPos pos = BlockPos.of(job.recheck[i]);
            job.block.checkBlock(pos);
            if (job.sky != null) {
                job.sky.checkBlock(pos);
            }
        }
        rechecks += job.recheckCount;
    }

    /**
     * Sections next to the column that only started storing light while the job ran got neither
     * the job's outgoing light nor vanilla's (vanilla pulled from the column before the result
     * was written): let vanilla carry the column's new light into them across the shared face.
     */
    private static void pushIntoNewSections(Job job) {
        for (int light = 0; light < job.lightSections; light++) {
            int sectionY = job.minSection - 1 + light;
            long own = SectionPos.asLong(job.chunkX, sectionY, job.chunkZ);
            for (int side = 0; side < 4; side++) {
                long key = SectionPos.asLong(job.chunkX + SIDE_DX[side], sectionY, job.chunkZ + SIDE_DZ[side]);
                pushIfNew(job, own, key, job.storing[(side + 1) * job.lightSections + light], SIDE_DIRECTIONS[side]);
            }
            // The column's own sections: from the one below and the one above into this one.
            int then = job.storing[light];
            if (light > 0) {
                pushIfNew(job, SectionPos.asLong(job.chunkX, sectionY - 1, job.chunkZ), own, then, Direction.UP);
            }
            if (light + 1 < job.lightSections) {
                pushIfNew(job, SectionPos.asLong(job.chunkX, sectionY + 1, job.chunkZ), own, then, Direction.DOWN);
            }
        }
    }

    /** Pushes {@code from}'s face towards {@code to} for each layer {@code to} newly stores. */
    private static void pushIfNew(Job job, long from, long to, int storingThen, Direction outward) {
        if (job.sky != null && (storingThen & SKY_STORING) == 0 && GaiusLightColumnAccess.storing(job.sky, to)) {
            GaiusLightColumnAccess.pushFace(job.sky, from, outward);
        }
        if ((storingThen & BLOCK_STORING) == 0 && GaiusLightColumnAccess.storing(job.block, to)) {
            GaiusLightColumnAccess.pushFace(job.block, from, outward);
        }
    }
}
