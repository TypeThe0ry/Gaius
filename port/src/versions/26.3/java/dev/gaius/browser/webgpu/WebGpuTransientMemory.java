package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-submit transient memory.
 *
 * <p>Every block is a GPU buffer with a CPU copy. Allocations hand out windows of the CPU copy;
 * {@link #flush()} uploads the written range of each block once, right before the command buffer
 * is submitted (through the glue's mapped staging ring, ahead of the frame's commands), so a
 * frame's uniform, vertex and staging data cost one upload per 512 KiB instead of one per
 * allocation. WebGPU orders queue writes before
 * later submissions, so a block can be reused as soon as the submission that read it is queued;
 * {@link #rotate()} therefore recycles every block after each submit. Allocations larger than a
 * block get a dedicated block from a small pool.</p>
 */
final class WebGpuTransientMemory implements TransientMemory {
    static final int BLOCK_SIZE = 512 * 1024;
    private static final int MAX_FREE_OVERSIZED_BLOCKS = 4;
    private static final int GPU_USAGE = WebGpuFormats.BUFFER_COPY_SRC | WebGpuFormats.BUFFER_COPY_DST
            | WebGpuFormats.BUFFER_VERTEX | WebGpuFormats.BUFFER_INDEX | WebGpuFormats.BUFFER_UNIFORM
            | WebGpuFormats.BUFFER_STORAGE | WebGpuFormats.BUFFER_INDIRECT;
    private static final Runnable NO_OP = () -> {
    };

    private final WebGpuDevice device;
    private final ArrayList<Block> freeBlocks = new ArrayList<>();
    private final ArrayList<Block> usedBlocks = new ArrayList<>();
    private final ArrayList<Block> oversizedBlocks = new ArrayList<>();
    private final ArrayList<Block> freeOversizedBlocks = new ArrayList<>();
    private Block current;
    private int currentOffset;
    private final ArrayList<ByteBuffer> cpuBlocks = new ArrayList<>();
    private int cpuBlockIndex;
    private int cpuOffset;
    long uploadedBytes;
    int uploadCalls;

    WebGpuTransientMemory(WebGpuDevice device) {
        this.device = device;
    }

    /** One GPU block, its CPU copy and the range written since the last flush. */
    static final class Block {
        final int size;
        final int handle;
        final ByteBuffer cpu;
        final BlockBuffer buffer;
        int dirtyStart = Integer.MAX_VALUE;
        int dirtyEnd;

        Block(WebGpuDevice device, int size) {
            this.size = size;
            this.handle = WebGpuGlue.createBuffer(size, GPU_USAGE, "gaius transient");
            this.cpu = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            this.buffer = new BlockBuffer(device, size, this.handle);
        }

        void markDirty(int start, int end) {
            if (start < this.dirtyStart) {
                this.dirtyStart = start;
            }
            if (end > this.dirtyEnd) {
                this.dirtyEnd = end;
            }
        }
    }

    /** The GpuBuffer face of a block. Transient slices are neither mappable nor closable. */
    static final class BlockBuffer extends WebGpuBuffer {
        BlockBuffer(WebGpuDevice device, int size, int handle) {
            super(device, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX
                    | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
                    | GpuBuffer.USAGE_INDIRECT_PARAMETERS, size, handle);
        }

        @Override
        public void close() {
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }
    }

    /** A window [offset, offset + size) of a block. */
    private record Allocation(Block block, int offset, int size) {
    }

    private static int align(long value, long alignment) {
        long a = Math.max(4L, alignment);
        return (int) ((value + a - 1L) / a * a);
    }

    private Allocation allocate(long size, long alignment, long minimumAllocation, long elementSize) {
        if (size > BLOCK_SIZE) {
            Block block = this.oversizedBlock(align(size, 4L));
            this.oversizedBlocks.add(block);
            return new Allocation(block, 0, (int) size);
        }
        if (this.current != null) {
            int offset = align(this.currentOffset, alignment);
            if (offset + size <= BLOCK_SIZE) {
                this.currentOffset = offset + (int) size;
                return new Allocation(this.current, offset, (int) size);
            }
            if (minimumAllocation < size && offset + minimumAllocation <= BLOCK_SIZE) {
                long element = Math.max(1L, elementSize);
                int partial = (int) ((BLOCK_SIZE - offset) / element * element);
                if (partial >= minimumAllocation) {
                    this.currentOffset = offset + partial;
                    return new Allocation(this.current, offset, partial);
                }
            }
        }
        this.current = this.freeBlocks.isEmpty() ? new Block(this.device, BLOCK_SIZE)
                : this.freeBlocks.remove(this.freeBlocks.size() - 1);
        this.usedBlocks.add(this.current);
        this.currentOffset = (int) size;
        return new Allocation(this.current, 0, (int) size);
    }

    /** A pooled oversized block of at least {@code size} bytes and at most twice that. */
    private Block oversizedBlock(int size) {
        for (int i = 0; i < this.freeOversizedBlocks.size(); i++) {
            Block block = this.freeOversizedBlocks.get(i);
            if (block.size >= size && block.size / 2 <= size) {
                this.freeOversizedBlocks.remove(i);
                return block;
            }
        }
        return new Block(this.device, size);
    }

    private static ByteBuffer window(Block block, int offset, int size) {
        ByteBuffer view = block.cpu.duplicate();
        view.limit(offset + size).position(offset);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    /** Copies {@code data} (position to limit) into a block window and returns the window's slice. */
    GpuBufferSlice stage(ByteBuffer data, long alignment) {
        int length = data.remaining();
        Allocation allocation = this.allocate(align(length, 4L), alignment, align(length, 4L), 1L);
        WebGpuGlue.copyBytes(allocation.block.cpu, allocation.offset, data);
        allocation.block.markDirty(allocation.offset, allocation.offset + allocation.size);
        return new GpuBufferSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    /** A writable block window whose contents reach the GPU at the next submit. */
    GpuBufferSlice.MappedView stagingView(long size, long alignment, long minimumAllocation, long elementSize) {
        Allocation allocation = this.allocate(size, alignment, minimumAllocation, elementSize);
        allocation.block.markDirty(allocation.offset, align(allocation.offset + allocation.size, 4L));
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(allocation.block.buffer, allocation.offset, allocation.size),
                window(allocation.block, allocation.offset, allocation.size), NO_OP);
    }

    /** Uploads the written range of every block used since the last submit. */
    void flush() {
        for (int i = 0; i < this.usedBlocks.size(); i++) {
            flushBlock(this.usedBlocks.get(i));
        }
        for (int i = 0; i < this.oversizedBlocks.size(); i++) {
            flushBlock(this.oversizedBlocks.get(i));
        }
    }

    private void flushBlock(Block block) {
        if (block.dirtyEnd > block.dirtyStart) {
            int start = block.dirtyStart & ~3;
            int end = Math.min(block.size, (block.dirtyEnd + 3) & ~3);
            WebGpuGlue.upload(block.handle, start, window(block, start, end - start));
            this.uploadedBytes += end - start;
            this.uploadCalls++;
        }
        block.dirtyStart = Integer.MAX_VALUE;
        block.dirtyEnd = 0;
    }

    /** After a submit: every block is free again; a few oversized blocks are kept for reuse. */
    void rotate() {
        this.freeBlocks.addAll(this.usedBlocks);
        this.usedBlocks.clear();
        this.freeOversizedBlocks.addAll(this.oversizedBlocks);
        this.oversizedBlocks.clear();
        while (this.freeOversizedBlocks.size() > MAX_FREE_OVERSIZED_BLOCKS) {
            WebGpuGlue.destroyBuffer(this.freeOversizedBlocks.remove(0).handle);
        }
        this.current = null;
        this.currentOffset = 0;
        this.cpuBlockIndex = 0;
        this.cpuOffset = 0;
    }

    // ---- TransientMemory -----------------------------------------------------------------------

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        int length = (int) size;
        if (length > BLOCK_SIZE) {
            return ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        }
        int offset = align(this.cpuOffset, alignment);
        if (this.cpuBlockIndex >= this.cpuBlocks.size() || offset + length > BLOCK_SIZE) {
            if (this.cpuBlockIndex < this.cpuBlocks.size()) {
                this.cpuBlockIndex++;
            }
            if (this.cpuBlockIndex >= this.cpuBlocks.size()) {
                this.cpuBlocks.add(ByteBuffer.allocateDirect(BLOCK_SIZE).order(ByteOrder.nativeOrder()));
            }
            offset = 0;
        }
        this.cpuOffset = offset + length;
        ByteBuffer view = this.cpuBlocks.get(this.cpuBlockIndex).duplicate();
        view.limit(offset + length).position(offset);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return this.stagingView(size, alignment, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation,
            long elementSize) {
        Allocation allocation = this.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return this.stagingView(size, alignment, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        long total = 0L;
        for (int i = 0; i < data.size(); i++) {
            total = align(total + data.get(i).remaining(), alignment);
        }
        Allocation allocation = this.allocate(total, alignment, minimumAllocation, elementSize);
        int offset = 0;
        for (int i = 0; i < data.size() && offset < allocation.size; i++) {
            ByteBuffer source = data.get(i);
            int length = Math.min(source.remaining(), allocation.size - offset);
            ByteBuffer part = source;
            if (length < source.remaining()) {
                part = source.duplicate();
                part.limit(part.position() + length);
            }
            WebGpuGlue.copyBytes(allocation.block.cpu, allocation.offset + offset, part);
            offset = align(offset + length, alignment);
        }
        allocation.block.markDirty(allocation.offset, allocation.offset + allocation.size);
        return new GpuBufferSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return this.multiUploadGpu(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        ArrayList<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            slices.add(this.stage(data.get(i), alignment));
        }
        return slices;
    }
}
