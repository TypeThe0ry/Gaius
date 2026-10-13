package net.minecraft.server.level;

/** Test stub: only what TickParitySettle calls. */
public class ServerLevel {
    private final ServerChunkCache chunkSource;

    public ServerLevel(ServerChunkCache chunkSource) {
        this.chunkSource = chunkSource;
    }

    public ServerChunkCache getChunkSource() {
        return chunkSource;
    }
}
