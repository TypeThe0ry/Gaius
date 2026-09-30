package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Minecraft 26.3 render patches, owned by work package P3.
 *
 * <p>Planned scope (migration plan): stub GlBackend.loadLibrary/unloadLibrary;
 * DebugEntrySystemSpecs.getCpuInfo; GlBuffer$Direct explicit-flush mapping; VulkanBackend 26.3
 * stubs (checkBackendAvailable, loadLibrary, createDevice, createWindow); force improved
 * transparency (OIT) off; wireframe device feature off; MacosUtil stubs; PipelineCache if spike S5
 * requires it.
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Wrap every patch in
 * {@code PatchRegistry.run("RenderPatches263.<method>", () -> ...)} and read owners, descriptors
 * and constants from {@code symbols}.
 */
public final class RenderPatches263 {
    private RenderPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("RenderPatches263: no 26.3 render patches yet (P3)");
    }
}
