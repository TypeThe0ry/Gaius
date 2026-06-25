package org.teavm.classlib.java.util.zip;

import java.nio.ByteBuffer;

public final class TZipModernSupport {
    private TZipModernSupport() {
    }

    public static void setInput(TInflater inflater, ByteBuffer buffer) {
        byte[] input = new byte[buffer.remaining()];
        buffer.get(input);
        inflater.setInput(input);
    }

    public static int inflate(TInflater inflater, ByteBuffer buffer) throws TDataFormatException {
        byte[] output = new byte[buffer.remaining()];
        int count = inflater.inflate(output);
        buffer.put(output, 0, count);
        return count;
    }
}
