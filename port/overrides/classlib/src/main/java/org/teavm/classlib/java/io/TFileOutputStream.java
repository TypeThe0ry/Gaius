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
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;

public class TFileOutputStream extends OutputStream {
    private static final byte[] ONE_BYTE_BUFFER = new byte[1];
    private VirtualFileAccessor accessor;
    private String path;
    private boolean dirty;

    public TFileOutputStream(TFile file) throws FileNotFoundException {
        this(file, false);
    }

    public TFileOutputStream(String path) throws FileNotFoundException {
        this(new TFile(path));
    }

    public TFileOutputStream(String path, boolean append) throws FileNotFoundException {
        this(new TFile(path), append);
    }

    public TFileOutputStream(TFile file, boolean append) throws FileNotFoundException {
        BrowserFilePersistence.mount();
        if (file.getName().isEmpty()) {
            throw new FileNotFoundException("Invalid file name");
        }
        VirtualFile parentVirtualFile = file.findParentFile();
        if (parentVirtualFile != null && parentVirtualFile.isDirectory()) {
            try {
                parentVirtualFile.createFile(file.getName());
            } catch (IOException e) {
                throw new FileNotFoundException();
            }
        }

        VirtualFile virtualFile = file.findVirtualFile();
        if (virtualFile == null || !virtualFile.isFile()) {
            throw new FileNotFoundException("Could not create file");
        }
        accessor = virtualFile.createAccessor(false, true, append);
        if (accessor == null) {
            throw new FileNotFoundException();
        }
        path = file.getAbsolutePath();
    }

    public TFileOutputStream(VirtualFileAccessor accessor) {
        this.accessor = accessor;
        this.path = null;
    }

    public TFileOutputStream(String path, VirtualFileAccessor accessor) {
        this.accessor = accessor;
        this.path = path;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b);
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IndexOutOfBoundsException();
        }
        ensureOpened();
        accessor.write(b, off, len);
        dirty = true;
    }

    @Override
    public void flush() throws IOException {
        ensureOpened();
        accessor.flush();
        persistIfDirty();
    }

    @Override
    public void close() throws IOException {
        if (accessor != null) {
            accessor.flush();
            persistIfDirty();
            accessor.close();
        }
        accessor = null;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpened();
        byte[] buffer = ONE_BYTE_BUFFER;
        buffer[0] = (byte) b;
        accessor.write(buffer, 0, 1);
        dirty = true;
    }

    private void persistIfDirty() throws IOException {
        if (!dirty || path == null) {
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

    private void ensureOpened() throws IOException {
        if (accessor == null) {
            throw new IOException("This stream is already closed");
        }
    }
}
