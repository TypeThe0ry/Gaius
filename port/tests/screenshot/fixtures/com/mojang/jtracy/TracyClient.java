package com.mojang.jtracy;

public final class TracyClient {
    private TracyClient() {
    }

    public static MemoryPool createMemoryPool(String name) {
        return new MemoryPool();
    }
}
