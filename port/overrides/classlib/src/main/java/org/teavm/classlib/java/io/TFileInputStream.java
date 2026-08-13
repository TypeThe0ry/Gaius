/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.teavm.classlib.java.io;

import dev.gaius.browser.BrowserFilePersistence;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;

public class TFileInputStream extends InputStream {
    private static final byte[] ONE_BYTE_BUFFER = new byte[1];
    private VirtualFileAccessor accessor;
    private String path;
    private boolean materializedRetained;

    public TFileInputStream(TFile file) throws FileNotFoundException {
        BrowserFilePersistence.mount();
        String absolutePath = file.getAbsolutePath();
        try {
            BrowserFilePersistence.materializeForOpen(absolutePath);
        } catch (IOException exception) {
            FileNotFoundException failure = new FileNotFoundException(absolutePath);
            failure.initCause(exception);
            throw failure;
        }
        VirtualFile virtualFile = file.findVirtualFile();
        if (virtualFile == null || !virtualFile.isFile()) {
            throw new FileNotFoundException();
        }

        VirtualFileAccessor opened = virtualFile.createAccessor(true, false, false);
        if (opened == null) {
            throw new FileNotFoundException();
        }
        initialize(absolutePath, opened);
    }

    public TFileInputStream(String path) throws FileNotFoundException {
        this(new TFile(path));
    }

    public TFileInputStream(VirtualFileAccessor accessor) {
        this.accessor = accessor;
    }

    public TFileInputStream(String path, VirtualFileAccessor accessor) {
        initialize(path, accessor);
    }

    private void initialize(String path, VirtualFileAccessor opened) {
        this.accessor = opened;
        this.path = path;
        try {
            materializedRetained = BrowserFilePersistence.retainMaterializedChunkFile(path);
        } catch (RuntimeException | Error failure) {
            accessor = null;
            try {
                opened.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b);
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        ensureOpened();
        int result = accessor.read(b, off, len);
        return result > 0 ? result : -1;
    }

    @Override
    public long skip(long n) throws IOException {
        ensureOpened();
        if (n <= 0) {
            return 0;
        }
        int position = accessor.tell();
        long skipped = Math.min(n, Math.max(0, accessor.size() - position));
        accessor.seek(position + (int) skipped);
        return skipped;
    }

    @Override
    public int available() throws IOException {
        ensureOpened();
        return Math.max(0, accessor.size() - accessor.tell());
    }

    @Override
    public void close() throws IOException {
        VirtualFileAccessor opened = accessor;
        if (opened == null) {
            return;
        }
        accessor = null;
        IOException failure = null;
        try {
            opened.close();
        } catch (IOException exception) {
            failure = exception;
        } finally {
            if (materializedRetained) {
                materializedRetained = false;
                BrowserFilePersistence.releaseMaterializedChunkFile(path, true);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public int read() throws IOException {
        ensureOpened();
        byte[] buffer = ONE_BYTE_BUFFER;
        int read = accessor.read(buffer, 0, 1);
        return read > 0 ? buffer[0] & 0xFF : -1;
    }

    private void ensureOpened() throws IOException {
        if (accessor == null) {
            throw new IOException("This stream is already closed");
        }
    }
}
