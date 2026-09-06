package dev.gaius.browser;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Reads structure NBT without suspending its synchronous resource-loader callback. */
public final class BrowserGzip {
    private BrowserGzip() {
    }

    public static CompoundTag readCompressedNbt(InputStream input) throws IOException {
        // Structure loaders can be invoked from a native JS callback, outside a
        // TeaVM thread. Waiting for DecompressionStream with Thread.sleep here
        // throws before the structure can be read. Vanilla's gzip/NBT reader is
        // synchronous and retains its format validation and exception semantics.
        return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
    }
}
