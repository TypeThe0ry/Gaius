package dev.gaius.browser;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.teavm.jso.JSBody;

/** Retains a bounded set of already-signalled staged GPU buffers across browser frames. */
public final class BrowserGpuBufferPoolCache {
    public static final int MAX_COUNT = 4;
    public static final long MAX_BYTES = 1024L * 1024L;
    public static final int MAX_IDLE_FRAMES = 3;

    private static final int TELEMETRY_INTERVAL_FRAMES = 4;
    private static final int MAX_TELEMETRY_POOLS = 24;
    private static int nextPoolId = 1;

    private final List<GpuBuffer> available;
    private final BooleanSupplier contextProbe;
    private final int usage;
    private final int poolId;
    private Supplier<String> labelSupplier;
    private String resolvedLabel;

    /** Primitive metadata remains index-aligned with {@link #available}. */
    private int[] availableFrames = new int[MAX_COUNT];
    private long[] availableSizes = new long[MAX_COUNT];
    private int stampCount;
    private long availableBytes;
    private int frame;
    private boolean ownerClosed;
    private boolean contextUnavailable;
    private boolean telemetryBridgeAvailable = true;
    private boolean metadataExpanded;
    private boolean listMayNeedTrim;
    private int framesSinceTelemetry;

    /** Candidate computed once after the complete recycle sweep and verified at removeAt. */
    private GpuBuffer expectedCandidate;
    private int expectedCandidateIndex = -1;

    private long acquireCalls;
    private long createCalls;
    private long reuseCalls;
    private long recycleReadyBatches;
    private long recycleNotReady;
    private long recycledBuffers;
    private long cacheCloseCalls;
    private long cacheCloseFailures;
    private long budgetEvictions;
    private long ttlEvictions;
    private long contextDrops;
    private long anomalyDrops;
    private long overflowPurges;
    private long requestedBytes;
    private long acquiredBytes;
    private long admittedAvailableHighWater;
    private long admittedAvailableBytesHighWater;
    private int retainedAvailableHighWater;
    private long retainedAvailableBytesHighWater;
    private int lastPendingDepth;
    private long telemetryPublishes;
    private long adoptionOperations;
    private long sweepValidations;
    private long sweepIdentityComparisons;
    private long sweepSelectionComparisons;
    private long trimComparisons;
    private long listTrimCalls;
    private long storageCompactionFailures;

    public BrowserGpuBufferPoolCache(
            List<GpuBuffer> available,
            Supplier<String> label,
            int usage) {
        this(available, label, usage, BrowserGpuBufferPoolCache::browserContextAvailable);
    }

    BrowserGpuBufferPoolCache(
            List<GpuBuffer> available,
            Supplier<String> label,
            int usage,
            BooleanSupplier contextProbe) {
        if (available == null || contextProbe == null) {
            throw new IllegalArgumentException(
                    "GPU buffer cache requires an available list and context probe");
        }
        this.available = available;
        this.contextProbe = contextProbe;
        this.usage = usage;
        this.poolId = allocatePoolId();
        this.labelSupplier = label;
        if (!available.isEmpty()) {
            rebuildInitialMetadataOrClear();
        }
    }

    /** Called before the owner polls signalled recycle batches. */
    public void beforeAcquire() {
        acquireCalls++;
        clearExpectedCandidate();
        if (!requireContext()) {
            return;
        }
        if (!validateMetadata(false, 0, false)) {
            failClosedAvailable("before-acquire-metadata");
            return;
        }
        if (!purgeExpired()) {
            failClosedAvailable("before-acquire-ttl");
        }
    }

    /**
     * Adopts one non-null, already-signalled batch in linear time. Duplicate, closed-state and
     * candidate validation is deferred until the owner's complete removeIf sweep.
     */
    public void recycleResult(List<GpuBuffer> recycled) {
        if (recycled == null) {
            recycleNotReady++;
            return;
        }
        recycleReadyBatches++;
        GpuBuffer[] incoming = null;
        try {
            // Do not rescan the already-adopted prefix for every ready fence.  The complete
            // owner sweep is validated exactly once by afterRecycleSweep(); on an exceptional
            // transfer failClosedTransfer() snapshots the actual list (including any partially
            // appended prefix) before closing the union of both owners.
            if (available.size() != stampCount) {
                throw new IllegalStateException("GPU buffer metadata count changed");
            }
            incoming = snapshotIncomingExact(recycled);
            int oldCount = stampCount;
            int incomingCount = incoming.length;
            if (incomingCount > Integer.MAX_VALUE - oldCount) {
                throw new IllegalStateException("GPU buffer cache size overflow");
            }
            int nextCount = oldCount + incomingCount;
            ensureMetadataCapacity(nextCount);
            if (nextCount > MAX_COUNT) {
                // Set this before addAll: a non-standard ArrayList can partially expand and
                // throw, and that failure path must still compact its backing storage.
                listMayNeedTrim = true;
            }
            boolean changed = available.addAll(recycled);
            if ((incomingCount != 0 && !changed)
                    || (incomingCount == 0 && changed)
                    || available.size() != nextCount) {
                throw new IllegalStateException("GPU buffer cache adoption size changed");
            }
            long nextBytes = availableBytes;
            for (int index = 0; index < incomingCount; index++) {
                GpuBuffer buffer = incoming[index];
                long size = safeSize(buffer);
                int destination = oldCount + index;
                availableFrames[destination] = frame;
                availableSizes[destination] = size;
                if (size < 0L || nextBytes > Long.MAX_VALUE - size) {
                    throw new IllegalStateException("GPU buffer cache adoption size invalid");
                }
                nextBytes += size;
                adoptionOperations++;
            }
            stampCount = nextCount;
            availableBytes = nextBytes;
            recycledBuffers += incomingCount;
            updateAdmittedHighWater();
        } catch (Throwable failure) {
            failClosedTransfer(incoming, recycled, "recycle-adoption");
        }
    }

    /** Validates all adopted batches together before vanilla takeBestAvailable runs. */
    public void afterRecycleSweep(int requestedRoundedSize) {
        clearExpectedCandidate();
        if (!requireContext()) {
            return;
        }
        sweepValidations++;
        if (!validateMetadata(true, requestedRoundedSize, true)) {
            failClosedAvailable("after-recycle-sweep");
        }
    }

    /** Removes the candidate chosen by vanilla while keeping primitive metadata in lockstep. */
    public GpuBuffer removeAt(int index) {
        if (!requireContext()) {
            return null;
        }
        try {
            if (available.size() != stampCount
                    || index < 0
                    || index >= stampCount
                    || expectedCandidate == null
                    || index != expectedCandidateIndex
                    || available.get(index) != expectedCandidate) {
                failClosedAvailable("remove-candidate-mismatch");
                return null;
            }
            GpuBuffer buffer = removeAtInternal(index);
            if (buffer == null || safeClosed(buffer)) {
                safeClose(buffer);
                failClosedAvailable("remove-closed-buffer");
                return null;
            }
            reuseCalls++;
            clearExpectedCandidate();
            return buffer;
        } catch (Throwable failure) {
            failClosedAvailable("remove-exception");
            return null;
        }
    }

    /** Records the owner's miss path after it has created a new physical buffer. */
    public void recordCreate(GpuBuffer buffer) {
        createCalls++;
        clearExpectedCandidate();
        if (buffer == null || safeClosed(buffer) || safeSize(buffer) < 0L) {
            failClosedAvailable("created-buffer-invalid");
        }
    }

    /** Trims only after the selected buffer has moved to usedThisFrame. */
    public void afterAcquire(GpuBuffer acquired, int requestedSize) {
        clearExpectedCandidate();
        requestedBytes += Math.max(0, requestedSize);
        long size = safeSize(acquired);
        if (size >= 0L) {
            acquiredBytes += size;
        } else {
            failClosedAvailable("acquired-buffer-invalid");
        }
        if (!requireContext()) {
            return;
        }
        try {
            if (available.size() != stampCount) {
                failClosedAvailable("after-acquire-metadata");
                return;
            }
            if (!purgeExpired() || !trimBudgetLinear()) {
                failClosedAvailable("after-acquire-trim");
                return;
            }
            updateRetainedHighWater();
            compactStorageIfNeeded();
        } catch (Throwable failure) {
            failClosedAvailable("after-acquire-exception");
        }
    }

    /** Called once after the owner has moved usedThisFrame into its fenced pending list. */
    public void endFrame(int pendingDepth) {
        lastPendingDepth = Math.max(0, pendingDepth);
        if (frame == Integer.MAX_VALUE) {
            overflowPurges++;
            closeAndClearAvailableNoThrow();
            frame = 0;
            publishTelemetry(false, "frame-overflow");
        } else {
            frame++;
        }
        if (requireContext()) {
            try {
                if (!validateMetadata(false, 0, false)
                        || !purgeExpired()
                        || !trimBudgetLinear()) {
                    failClosedAvailable("end-frame-validation");
                } else {
                    updateRetainedHighWater();
                    compactStorageIfNeeded();
                }
            } catch (Throwable failure) {
                failClosedAvailable("end-frame-exception");
            }
        }
        publishPeriodicTelemetry();
    }

    /** Called only after the owner has closed and cleared available, used and pending lists. */
    public void ownerClosed() {
        if (ownerClosed) {
            return;
        }
        ownerClosed = true;
        clearExpectedCandidate();
        boolean clean = false;
        try {
            // The owner's close() deliberately closes and clears the real list before this
            // hook.  Non-zero stamps therefore describe the just-closed list and are reset
            // here; only a still-populated owner list is anomalous.
            clean = available.isEmpty();
        } catch (Throwable failure) {
            // Fall through to the fail-closed owner cleanup.
        }
        if (!clean) {
            anomalyDrops++;
            closeAndClearAvailableNoThrow();
        } else {
            resetMetadata();
            compactStorageIfNeeded();
        }
        publishTelemetry(true, "owner-close");
    }

    private boolean validateMetadata(
            boolean countSweepComparisons,
            int requestedRoundedSize,
            boolean selectCandidate) {
        try {
            if (available.size() != stampCount
                    || stampCount < 0
                    || availableFrames.length < stampCount
                    || availableSizes.length < stampCount) {
                return false;
            }
            int maximumSize = requestedRoundedSize * 4;
            long bestSize = (long) (maximumSize + 1);
            GpuBuffer best = null;
            int bestIndex = -1;
            boolean exactFound = false;
            long total = 0L;
            for (int index = 0; index < stampCount; index++) {
                GpuBuffer buffer = available.get(index);
                long size = safeSize(buffer);
                if (buffer == null || safeClosed(buffer) || size < 0L
                        || size != availableSizes[index]) {
                    return false;
                }
                for (int previous = 0; previous < index; previous++) {
                    if (countSweepComparisons) {
                        sweepIdentityComparisons++;
                    }
                    if (available.get(previous) == buffer) {
                        return false;
                    }
                }
                if (total > Long.MAX_VALUE - size) {
                    return false;
                }
                total += size;
                if (selectCandidate) {
                    sweepSelectionComparisons++;
                    if (!exactFound && size == (long) requestedRoundedSize) {
                        exactFound = true;
                        best = buffer;
                        bestIndex = index;
                    } else if (!exactFound && size > (long) requestedRoundedSize
                            && size < bestSize) {
                        bestSize = size;
                        best = buffer;
                        bestIndex = index;
                    }
                }
            }
            if (total != availableBytes) {
                return false;
            }
            if (selectCandidate) {
                expectedCandidate = best;
                expectedCandidateIndex = bestIndex;
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean purgeExpired() {
        try {
            for (int index = stampCount - 1; index >= 0; index--) {
                int stamp = availableFrames[index];
                if (stamp < 0 || stamp > frame) {
                    return false;
                }
                if (frame - stamp > MAX_IDLE_FRAMES) {
                    GpuBuffer expired = removeAtInternal(index);
                    ttlEvictions++;
                    safeClose(expired);
                }
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Selects the least-evictable entries in O(n * MAX_COUNT). */
    private boolean trimBudgetLinear() {
        if (stampCount <= MAX_COUNT && availableBytes <= MAX_BYTES) {
            return true;
        }
        try {
            int[] retained = {-1, -1, -1, -1};
            int retainedCount = 0;
            for (int index = 0; index < stampCount; index++) {
                if (retainedCount < MAX_COUNT) {
                    retained[retainedCount++] = index;
                    continue;
                }
                int mostEvictableSlot = 0;
                for (int slot = 1; slot < retainedCount; slot++) {
                    trimComparisons++;
                    if (evictsBefore(retained[slot], retained[mostEvictableSlot])) {
                        mostEvictableSlot = slot;
                    }
                }
                trimComparisons++;
                if (evictsBefore(retained[mostEvictableSlot], index)) {
                    retained[mostEvictableSlot] = index;
                }
            }
            long retainedBytes = 0L;
            for (int slot = 0; slot < retainedCount; slot++) {
                retainedBytes += availableSizes[retained[slot]];
            }
            while (retainedCount > 0 && retainedBytes > MAX_BYTES) {
                int mostEvictableSlot = -1;
                for (int slot = 0; slot < retained.length; slot++) {
                    if (retained[slot] < 0) continue;
                    if (mostEvictableSlot < 0
                            || evictsBefore(retained[slot], retained[mostEvictableSlot])) {
                        mostEvictableSlot = slot;
                    }
                    trimComparisons++;
                }
                retainedBytes -= availableSizes[retained[mostEvictableSlot]];
                retained[mostEvictableSlot] = -1;
                retainedCount--;
            }
            for (int index = stampCount - 1; index >= 0; index--) {
                boolean keep = false;
                for (int retainedIndex : retained) {
                    trimComparisons++;
                    if (retainedIndex == index) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    GpuBuffer evicted = removeAtInternal(index);
                    budgetEvictions++;
                    safeClose(evicted);
                }
            }
            return stampCount <= MAX_COUNT && availableBytes <= MAX_BYTES;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Oldest first, then largest, then the lowest stable list index. */
    private boolean evictsBefore(int left, int right) {
        int leftAge = frame - availableFrames[left];
        int rightAge = frame - availableFrames[right];
        if (leftAge != rightAge) return leftAge > rightAge;
        long leftSize = availableSizes[left];
        long rightSize = availableSizes[right];
        if (leftSize != rightSize) return leftSize > rightSize;
        return left < right;
    }

    private GpuBuffer removeAtInternal(int index) {
        GpuBuffer buffer = available.remove(index);
        long size = availableSizes[index];
        int trailing = stampCount - index - 1;
        if (trailing > 0) {
            System.arraycopy(availableFrames, index + 1, availableFrames, index, trailing);
            System.arraycopy(availableSizes, index + 1, availableSizes, index, trailing);
        }
        stampCount--;
        availableFrames[stampCount] = 0;
        availableSizes[stampCount] = 0L;
        availableBytes -= size;
        if (availableBytes < 0L) availableBytes = 0L;
        return buffer;
    }

    private static GpuBuffer[] snapshotIncomingExact(List<GpuBuffer> incoming) {
        Object[] objects = incoming.toArray();
        GpuBuffer[] snapshot = new GpuBuffer[objects.length];
        for (int index = 0; index < objects.length; index++) {
            Object object = objects[index];
            if (object != null && !(object instanceof GpuBuffer)) {
                throw new IllegalArgumentException("Recycle batch contains a non-GpuBuffer value");
            }
            snapshot[index] = (GpuBuffer) object;
        }
        return snapshot;
    }

    private GpuBuffer[] bestEffortAvailableSnapshot() {
        try {
            int count = Math.max(0, available.size());
            GpuBuffer[] snapshot = new GpuBuffer[count];
            int copied = 0;
            try {
                for (; copied < count; copied++) snapshot[copied] = available.get(copied);
                return snapshot;
            } catch (Throwable failure) {
                return Arrays.copyOf(snapshot, copied);
            }
        } catch (Throwable failure) {
            return new GpuBuffer[0];
        }
    }

    private static GpuBuffer[] bestEffortIncomingSnapshot(List<GpuBuffer> incoming) {
        if (incoming == null) return new GpuBuffer[0];
        try {
            return snapshotIncomingExact(incoming);
        } catch (Throwable failure) {
            return new GpuBuffer[0];
        }
    }

    private void failClosedTransfer(
            GpuBuffer[] incoming,
            List<GpuBuffer> recycled,
            String reason) {
        anomalyDrops++;
        GpuBuffer[] actual = bestEffortAvailableSnapshot();
        GpuBuffer[] safeIncoming = incoming == null
                ? bestEffortIncomingSnapshot(recycled) : incoming;
        // actual owns the original available prefix plus any partial addAll suffix; incoming
        // owns every still-unadded batch member.  Identity de-duplication makes the union safe.
        closeUniqueNoThrow(actual, safeIncoming);
        forceClearAvailableNoThrow();
        resetMetadata();
        compactStorageIfNeeded();
        clearExpectedCandidate();
        publishTelemetry(false, reason);
    }

    private void failClosedAvailable(String reason) {
        anomalyDrops++;
        closeAndClearAvailableNoThrow();
        clearExpectedCandidate();
        publishTelemetry(false, reason);
    }

    private void dropAvailableForContext() {
        boolean firstObservation = !contextUnavailable;
        contextUnavailable = true;
        if (firstObservation) contextDrops++;
        closeAndClearAvailableNoThrow();
        clearExpectedCandidate();
        if (firstObservation) publishTelemetry(false, "context-unavailable");
    }

    private void closeAndClearAvailableNoThrow() {
        GpuBuffer[] snapshot = bestEffortAvailableSnapshot();
        closeUniqueNoThrow(snapshot);
        forceClearAvailableNoThrow();
        resetMetadata();
        compactStorageIfNeeded();
    }

    private void closeUniqueNoThrow(GpuBuffer[]... sources) {
        for (int sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
            GpuBuffer[] source = sources[sourceIndex];
            if (source == null) continue;
            for (int index = 0; index < source.length; index++) {
                GpuBuffer candidate = source[index];
                if (candidate == null || seenEarlier(sources, sourceIndex, index, candidate)) {
                    continue;
                }
                safeClose(candidate);
            }
        }
    }

    private static boolean seenEarlier(
            GpuBuffer[][] sources, int sourceIndex, int index, GpuBuffer candidate) {
        for (int previousSource = 0; previousSource <= sourceIndex; previousSource++) {
            GpuBuffer[] source = sources[previousSource];
            if (source == null) continue;
            int limit = previousSource == sourceIndex ? index : source.length;
            for (int previous = 0; previous < limit; previous++) {
                if (source[previous] == candidate) return true;
            }
        }
        return false;
    }

    private void forceClearAvailableNoThrow() {
        try {
            available.clear();
            return;
        } catch (Throwable failure) {
            // A non-standard list may reject clear; make bounded best-effort removals.
        }
        int attempts;
        try {
            attempts = Math.max(0, available.size());
        } catch (Throwable failure) {
            return;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                int size = available.size();
                if (size == 0) return;
                available.remove(size - 1);
            } catch (Throwable failure) {
                return;
            }
        }
    }

    private void resetMetadata() {
        int clearCount = Math.min(Math.max(0, stampCount),
                Math.min(availableFrames.length, availableSizes.length));
        if (clearCount > 0) {
            Arrays.fill(availableFrames, 0, clearCount, 0);
            Arrays.fill(availableSizes, 0, clearCount, 0L);
        }
        stampCount = 0;
        availableBytes = 0L;
    }

    private void rebuildInitialMetadataOrClear() {
        try {
            int count = available.size();
            ensureMetadataCapacity(count);
            stampCount = count;
            availableBytes = 0L;
            for (int index = 0; index < count; index++) {
                availableFrames[index] = frame;
                long size = safeSize(available.get(index));
                availableSizes[index] = size;
                if (size < 0L || availableBytes > Long.MAX_VALUE - size) {
                    throw new IllegalStateException("Initial GPU buffer metadata invalid");
                }
                availableBytes += size;
            }
            if (!validateMetadata(false, 0, false)) {
                throw new IllegalStateException("Initial GPU buffer list invalid");
            }
            updateAdmittedHighWater();
            updateRetainedHighWater();
        } catch (Throwable failure) {
            anomalyDrops++;
            closeAndClearAvailableNoThrow();
        }
    }

    private void ensureMetadataCapacity(int requested) {
        if (requested <= availableFrames.length) return;
        int doubled = availableFrames.length > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE : availableFrames.length * 2;
        int capacity = Math.max(requested, Math.max(MAX_COUNT, doubled));
        availableFrames = Arrays.copyOf(availableFrames, capacity);
        availableSizes = Arrays.copyOf(availableSizes, capacity);
        metadataExpanded = true;
    }

    private void compactStorageIfNeeded() {
        if (stampCount > MAX_COUNT) return;
        if (metadataExpanded || availableFrames.length != MAX_COUNT) {
            try {
                availableFrames = Arrays.copyOf(availableFrames, MAX_COUNT);
                availableSizes = Arrays.copyOf(availableSizes, MAX_COUNT);
                metadataExpanded = false;
            } catch (Throwable failure) {
                storageCompactionFailures++;
            }
        }
        if (listMayNeedTrim && available instanceof ArrayList<?> arrayList) {
            try {
                arrayList.trimToSize();
                listTrimCalls++;
                listMayNeedTrim = false;
            } catch (Throwable failure) {
                storageCompactionFailures++;
            }
        }
    }

    private void safeClose(GpuBuffer buffer) {
        if (buffer == null) return;
        boolean closed = false;
        try {
            closed = buffer.isClosed();
        } catch (Throwable failure) {
            cacheCloseFailures++;
        }
        if (closed) return;
        try {
            buffer.close();
            cacheCloseCalls++;
        } catch (Throwable failure) {
            cacheCloseFailures++;
        }
    }

    private static long safeSize(GpuBuffer buffer) {
        if (buffer == null) return -1L;
        try {
            long size = buffer.size();
            return size >= 0L ? size : -1L;
        } catch (Throwable failure) {
            return -1L;
        }
    }

    private static boolean safeClosed(GpuBuffer buffer) {
        if (buffer == null) return true;
        try {
            return buffer.isClosed();
        } catch (Throwable failure) {
            return true;
        }
    }

    private boolean requireContext() {
        if (ownerClosed) return false;
        boolean availableNow;
        try {
            availableNow = contextProbe.getAsBoolean();
        } catch (Throwable failure) {
            availableNow = false;
        }
        if (!availableNow) {
            dropAvailableForContext();
            return false;
        }
        contextUnavailable = false;
        return true;
    }

    private void clearExpectedCandidate() {
        expectedCandidate = null;
        expectedCandidateIndex = -1;
    }

    private void updateAdmittedHighWater() {
        admittedAvailableHighWater = Math.max(admittedAvailableHighWater, stampCount);
        admittedAvailableBytesHighWater = Math.max(admittedAvailableBytesHighWater, availableBytes);
    }

    private void updateRetainedHighWater() {
        retainedAvailableHighWater = Math.max(retainedAvailableHighWater, stampCount);
        retainedAvailableBytesHighWater = Math.max(retainedAvailableBytesHighWater, availableBytes);
    }

    private void publishPeriodicTelemetry() {
        framesSinceTelemetry++;
        if (framesSinceTelemetry >= TELEMETRY_INTERVAL_FRAMES) {
            publishTelemetry(false, "periodic");
        }
    }

    private void publishTelemetry(boolean closed, String reason) {
        telemetryPublishes++;
        framesSinceTelemetry = 0;
        if (!telemetryBridgeAvailable) return;
        String label = labelForPublish();
        try {
            publishTelemetryBrowser(
                    poolId, label, usage, frame, stampCount, availableBytes,
                    admittedAvailableHighWater, admittedAvailableBytesHighWater,
                    retainedAvailableHighWater, retainedAvailableBytesHighWater,
                    lastPendingDepth, acquireCalls, createCalls, reuseCalls,
                    recycleReadyBatches, recycleNotReady, recycledBuffers,
                    cacheCloseCalls, cacheCloseFailures, budgetEvictions, ttlEvictions,
                    contextDrops, anomalyDrops, overflowPurges, requestedBytes, acquiredBytes,
                    telemetryPublishes, adoptionOperations, sweepValidations,
                    sweepIdentityComparisons, sweepSelectionComparisons, trimComparisons,
                    listTrimCalls, storageCompactionFailures, closed, reason,
                    MAX_TELEMETRY_POOLS);
        } catch (Throwable failure) {
            telemetryBridgeAvailable = false;
        }
    }

    private String labelForPublish() {
        if (resolvedLabel != null) return resolvedLabel;
        String value;
        try {
            value = labelSupplier == null ? null : labelSupplier.get();
        } catch (Throwable failure) {
            value = null;
        }
        labelSupplier = null;
        if (value == null || value.isBlank()) value = "usage-" + usage;
        resolvedLabel = value.length() <= 96 ? value : value.substring(0, 96);
        return resolvedLabel;
    }

    private static int allocatePoolId() {
        int id = nextPoolId;
        nextPoolId = nextPoolId == Integer.MAX_VALUE ? 1 : nextPoolId + 1;
        return id;
    }

    private static boolean browserContextAvailable() {
        try {
            return browserContextAvailableJs();
        } catch (Throwable failure) {
            return false;
        }
    }

    @JSBody(script = """
            const state=globalThis.__gaiusGL;
            const gl=globalThis.__gaiusWebGL;
            if (!state || !gl || state.gpuContextLost || state.gpuSubmissionBlocked
                    || typeof gl.isContextLost !== 'function') return false;
            try { return !gl.isContextLost(); } catch (error) { return false; }
            """)
    private static native boolean browserContextAvailableJs();

    @JSBody(params = {
            "poolId", "label", "usage", "frame", "availableCount", "availableBytes",
            "admittedAvailableHighWater", "admittedAvailableBytesHighWater",
            "retainedAvailableHighWater", "retainedAvailableBytesHighWater", "pendingDepth",
            "acquireCalls", "createCalls", "reuseCalls", "recycleReadyBatches",
            "recycleNotReady", "recycledBuffers", "cacheCloseCalls", "cacheCloseFailures",
            "budgetEvictions", "ttlEvictions", "contextDrops", "anomalyDrops",
            "overflowPurges", "requestedBytes", "acquiredBytes", "telemetryPublishes",
            "adoptionOperations", "sweepValidations", "sweepIdentityComparisons",
            "sweepSelectionComparisons", "trimComparisons", "listTrimCalls",
            "storageCompactionFailures", "ownerClosed", "reason", "maxPools"
    }, script = """
            const stats=globalThis.__gaiusGLStats || (globalThis.__gaiusGLStats={});
            let root=stats.gpuBufferPoolCache;
            if (!root || root.version!==2 || !Array.isArray(root.pools)) {
              root={version:2,pools:[],overflowPublishes:0,totalPublishes:0,
                    closedPools:0,lastClosedPoolId:0};
              stats.gpuBufferPoolCache=root;
            }
            const boundedIncrement=(value)=>Math.min(2147483647,
                    Math.max(0,Number(value)||0)+1);
            root.totalPublishes=boundedIncrement(root.totalPublishes);
            let slotIndex=-1;
            for (let index=0;index<root.pools.length;index++) {
              if ((root.pools[index].poolId|0)===(poolId|0)) {
                slotIndex=index;
                break;
              }
            }
            if (ownerClosed) {
              if (slotIndex>=0) root.pools.splice(slotIndex,1);
              root.closedPools=boundedIncrement(root.closedPools);
              root.lastClosedPoolId=poolId|0;
              root.lastCloseReason=String(reason||'owner-close').slice(0,48);
              return;
            }
            let slot=slotIndex>=0?root.pools[slotIndex]:null;
            if (!slot && root.pools.length<(maxPools|0)) {
              slot={poolId:poolId|0};
              root.pools.push(slot);
            }
            if (!slot) {
              root.overflowPublishes=boundedIncrement(root.overflowPublishes);
              root.lastOverflowPoolId=poolId|0;
              root.lastOverflowUsage=usage|0;
              return;
            }
            slot.label=String(label||'').slice(0,96);
            slot.usage=usage|0;
            slot.frame=frame|0;
            slot.availableCount=availableCount|0;
            slot.availableBytes=Number(availableBytes);
            slot.admittedAvailableHighWater=Number(admittedAvailableHighWater);
            slot.admittedAvailableBytesHighWater=Number(admittedAvailableBytesHighWater);
            slot.retainedAvailableHighWater=retainedAvailableHighWater|0;
            slot.retainedAvailableBytesHighWater=Number(retainedAvailableBytesHighWater);
            slot.pendingDepth=pendingDepth|0;
            slot.acquireCalls=Number(acquireCalls);
            slot.createCalls=Number(createCalls);
            slot.reuseCalls=Number(reuseCalls);
            slot.recycleReadyBatches=Number(recycleReadyBatches);
            slot.recycleNotReady=Number(recycleNotReady);
            slot.recycledBuffers=Number(recycledBuffers);
            slot.cacheCloseCalls=Number(cacheCloseCalls);
            slot.cacheCloseFailures=Number(cacheCloseFailures);
            slot.budgetEvictions=Number(budgetEvictions);
            slot.ttlEvictions=Number(ttlEvictions);
            slot.contextDrops=Number(contextDrops);
            slot.anomalyDrops=Number(anomalyDrops);
            slot.overflowPurges=Number(overflowPurges);
            slot.requestedBytes=Number(requestedBytes);
            slot.acquiredBytes=Number(acquiredBytes);
            slot.telemetryPublishes=Number(telemetryPublishes);
            slot.adoptionOperations=Number(adoptionOperations);
            slot.sweepValidations=Number(sweepValidations);
            slot.sweepIdentityComparisons=Number(sweepIdentityComparisons);
            slot.sweepSelectionComparisons=Number(sweepSelectionComparisons);
            slot.trimComparisons=Number(trimComparisons);
            slot.listTrimCalls=Number(listTrimCalls);
            slot.storageCompactionFailures=Number(storageCompactionFailures);
            slot.lastPublishReason=String(reason||'periodic').slice(0,48);
            """)
    private static native void publishTelemetryBrowser(
            int poolId, String label, int usage, int frame, int availableCount,
            long availableBytes, long admittedAvailableHighWater,
            long admittedAvailableBytesHighWater, int retainedAvailableHighWater,
            long retainedAvailableBytesHighWater, int pendingDepth, long acquireCalls,
            long createCalls, long reuseCalls, long recycleReadyBatches,
            long recycleNotReady, long recycledBuffers, long cacheCloseCalls,
            long cacheCloseFailures, long budgetEvictions, long ttlEvictions,
            long contextDrops, long anomalyDrops, long overflowPurges,
            long requestedBytes, long acquiredBytes, long telemetryPublishes,
            long adoptionOperations, long sweepValidations, long sweepIdentityComparisons,
            long sweepSelectionComparisons, long trimComparisons, long listTrimCalls,
            long storageCompactionFailures, boolean ownerClosed, String reason, int maxPools);

    int availableCountForTesting() { return stampCount; }
    long availableBytesForTesting() { return availableBytes; }
    int metadataCapacityForTesting() {
        return Math.min(availableFrames.length, availableSizes.length);
    }
    long createCallsForTesting() { return createCalls; }
    long reuseCallsForTesting() { return reuseCalls; }
    long recycleNotReadyForTesting() { return recycleNotReady; }
    long anomalyDropsForTesting() { return anomalyDrops; }
    long budgetEvictionsForTesting() { return budgetEvictions; }
    long ttlEvictionsForTesting() { return ttlEvictions; }
    long contextDropsForTesting() { return contextDrops; }
    long overflowPurgesForTesting() { return overflowPurges; }
    long telemetryPublishesForTesting() { return telemetryPublishes; }
    long adoptionOperationsForTesting() { return adoptionOperations; }
    long sweepIdentityComparisonsForTesting() { return sweepIdentityComparisons; }
    long sweepSelectionComparisonsForTesting() { return sweepSelectionComparisons; }
    long trimComparisonsForTesting() { return trimComparisons; }
    long listTrimCallsForTesting() { return listTrimCalls; }
    long admittedHighWaterForTesting() { return admittedAvailableHighWater; }
    int retainedHighWaterForTesting() { return retainedAvailableHighWater; }
    int poolIdForTesting() { return poolId; }
    void setFrameForTesting(int value) { frame = value; }
}
