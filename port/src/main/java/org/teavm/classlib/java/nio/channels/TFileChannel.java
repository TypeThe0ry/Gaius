package org.teavm.classlib.java.nio.channels;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import dev.gaius.browser.BrowserFilePersistence;
import org.teavm.classlib.java.io.TFile;
import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.nio.file.TOpenOption;
import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TStandardOpenOption;
import org.teavm.classlib.java.nio.file.attribute.TFileAttribute;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

/**
 * Random-access channel over TeaVM's virtual filesystem.
 *
 * <p>This is the storage primitive used by vanilla RegionFile. It deliberately
 * keeps Java's synchronous API; the browser filesystem backend is mounted and
 * persisted around the game loop.</p>
 */
public class TFileChannel implements TSeekableByteChannel {
    private final VirtualFileAccessor accessor;
    private final String path;
    private final boolean readable;
    private final boolean writable;
    private boolean open = true;
    private boolean dirty;

    private TFileChannel(String path, VirtualFileAccessor accessor, boolean readable, boolean writable) {
        this.path = path;
        this.accessor = accessor;
        this.readable = readable;
        this.writable = writable;
    }

    public static TFileChannel open(TPath path, TOpenOption... options) throws IOException {
        return open(path, Set.of(options));
    }

    public static TFileChannel open(
            TPath path, Set<? extends TOpenOption> options, TFileAttribute<?>... attributes)
            throws IOException {
        return open(path, options);
    }

    private static TFileChannel open(TPath path, Set<? extends TOpenOption> options)
            throws IOException {
        boolean write = options.contains(TStandardOpenOption.WRITE)
                || options.contains(TStandardOpenOption.APPEND);
        boolean read = options.contains(TStandardOpenOption.READ) || !write;
        boolean append = options.contains(TStandardOpenOption.APPEND);
        boolean create = options.contains(TStandardOpenOption.CREATE)
                || options.contains(TStandardOpenOption.CREATE_NEW);
        boolean truncate = options.contains(TStandardOpenOption.TRUNCATE_EXISTING);

        BrowserFilePersistence.mount();
        TFile file = new TFile(path.toString());
        if (!file.exists() && create) {
            TFile parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            file.createNewFile();
        }
        VirtualFile virtualFile =
                VirtualFileSystemProvider.getInstance().getFile(file.getAbsolutePath());
        if (virtualFile == null || !virtualFile.isFile()) {
            throw new java.io.FileNotFoundException(path.toString());
        }
        VirtualFileAccessor accessor = virtualFile.createAccessor(read, write, append);
        if (accessor == null) {
            throw new java.io.FileNotFoundException(path.toString());
        }
        if (truncate && write && !append) {
            accessor.resize(0);
        }
        if (append) {
            accessor.seek(accessor.size());
        }
        return new TFileChannel(file.getAbsolutePath(), accessor, read, write);
    }

    @Override
    public int read(TByteBuffer target) throws IOException {
        ensureReadable();
        int requested = target.remaining();
        if (requested == 0) {
            return 0;
        }
        byte[] bytes = new byte[requested];
        int count = accessor.read(bytes, 0, requested);
        if (count <= 0) {
            return -1;
        }
        target.put(bytes, 0, count);
        return count;
    }

    public int read(TByteBuffer target, long position) throws IOException {
        int old = accessor.tell();
        accessor.seek(toIndex(position));
        try {
            return read(target);
        } finally {
            accessor.seek(old);
        }
    }

    public long read(TByteBuffer[] targets, int offset, int length) throws IOException {
        long total = 0;
        for (int index = offset; index < offset + length; index++) {
            int count = read(targets[index]);
            if (count < 0) {
                return total == 0 ? -1 : total;
            }
            total += count;
            if (targets[index].hasRemaining()) {
                break;
            }
        }
        return total;
    }

    public long read(TByteBuffer[] targets) throws IOException {
        return read(targets, 0, targets.length);
    }

    @Override
    public int write(TByteBuffer source) throws IOException {
        ensureWritable();
        int count = source.remaining();
        if (count == 0) {
            return 0;
        }
        byte[] bytes = new byte[count];
        source.get(bytes);
        accessor.write(bytes, 0, count);
        dirty = true;
        return count;
    }

    public int write(TByteBuffer source, long position) throws IOException {
        int old = accessor.tell();
        accessor.seek(toIndex(position));
        try {
            return write(source);
        } finally {
            accessor.seek(old);
        }
    }

    public long write(TByteBuffer[] sources, int offset, int length) throws IOException {
        long total = 0;
        for (int index = offset; index < offset + length; index++) {
            total += write(sources[index]);
        }
        return total;
    }

    public long write(TByteBuffer[] sources) throws IOException {
        return write(sources, 0, sources.length);
    }

    @Override
    public long position() {
        if (!open) {
            throw new IllegalStateException("Channel is closed");
        }
        try {
            return accessor.tell();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public TFileChannel position(long position) throws IOException {
        ensureOpen();
        accessor.seek(toIndex(position));
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return accessor.size();
    }

    @Override
    public TFileChannel truncate(long size) throws IOException {
        ensureWritable();
        int target = toIndex(size);
        accessor.resize(target);
        if (accessor.tell() > target) {
            accessor.seek(target);
        }
        dirty = true;
        return this;
    }

    public void force(boolean metadata) throws IOException {
        ensureOpen();
        accessor.flush();
        persistIfDirty();
    }

    public long transferTo(long position, long count, TWritableByteChannel target)
            throws IOException {
        int size = (int) Math.min(count, Math.max(0, size() - position));
        TByteBuffer buffer = TByteBuffer.allocate(Math.min(size, 65536));
        long transferred = 0;
        while (transferred < size) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), size - transferred));
            int read = read(buffer, position + transferred);
            if (read < 0) {
                break;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                target.write(buffer);
            }
            transferred += read;
        }
        return transferred;
    }

    public long transferFrom(TReadableByteChannel source, long position, long count)
            throws IOException {
        TByteBuffer buffer = TByteBuffer.allocate((int) Math.min(count, 65536));
        long transferred = 0;
        while (transferred < count) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), count - transferred));
            int read = source.read(buffer);
            if (read < 0) {
                break;
            }
            buffer.flip();
            write(buffer, position + transferred);
            transferred += read;
        }
        return transferred;
    }

    public TFileLock lock() throws IOException {
        return lock(0, Long.MAX_VALUE, false);
    }

    public TFileLock lock(long position, long size, boolean shared) throws IOException {
        ensureOpen();
        return new TFileLock(this, position, size, shared);
    }

    public TFileLock tryLock() throws IOException {
        return tryLock(0, Long.MAX_VALUE, false);
    }

    public TFileLock tryLock(long position, long size, boolean shared) throws IOException {
        return lock(position, size, shared);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (open) {
            accessor.flush();
            persistIfDirty();
            accessor.close();
            open = false;
        }
    }

    private void persistIfDirty() throws IOException {
        if (!writable || !dirty) {
            return;
        }
        int oldPosition = accessor.tell();
        int size = accessor.size();
        byte[] bytes = new byte[size];
        accessor.seek(0);
        int offset = 0;
        while (offset < size) {
            int read = accessor.read(bytes, offset, size - offset);
            if (read <= 0) {
                break;
            }
            offset += read;
        }
        accessor.seek(oldPosition);
        if (offset < size) {
            bytes = Arrays.copyOf(bytes, offset);
        }
        BrowserFilePersistence.persist(path, bytes);
        dirty = false;
    }

    private void ensureOpen() throws TClosedChannelException {
        if (!open) {
            throw new TClosedChannelException();
        }
    }

    private void ensureReadable() throws IOException {
        ensureOpen();
        if (!readable) {
            throw new TNonReadableChannelException();
        }
    }

    private void ensureWritable() throws IOException {
        ensureOpen();
        if (!writable) {
            throw new TNonWritableChannelException();
        }
    }

    private static int toIndex(long value) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("Browser virtual file offset is out of range: " + value);
        }
        return (int) value;
    }
}
