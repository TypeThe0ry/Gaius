package net.minecraft.server;

import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Test stub: only what TickParitySettle calls. */
public class MinecraftServer {
    private final List<ServerLevel> levels;

    public MinecraftServer(List<ServerLevel> levels) {
        this.levels = levels;
    }

    public Iterable<ServerLevel> getAllLevels() {
        return levels;
    }
}
