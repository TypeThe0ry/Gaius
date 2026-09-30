package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Minecraft 26.3 ui patches, owned by work package P8.
 *
 * <p>Planned scope (migration plan): ClientLevel.playBreakingSound volume 8.0f to 4.0f (reads the
 * ClientLevel already patched by MinecraftClientPatcher).
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Wrap every patch in
 * {@code PatchRegistry.run("UiPatches263.<method>", () -> ...)} and read owners, descriptors and
 * constants from {@code symbols}.
 */
public final class UiPatches263 {
    private UiPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("UiPatches263: no 26.3 ui patches yet (P8)");
    }
}
