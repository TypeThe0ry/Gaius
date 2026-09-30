package dev.gaius.tools;

import dev.gaius.tools.m263.InputPatches263;
import dev.gaius.tools.m263.RenderPatches263;
import dev.gaius.tools.m263.ServerPatches263;
import dev.gaius.tools.m263.TerrainPatches263;
import dev.gaius.tools.m263.UiPatches263;
import dev.gaius.tools.m263.WorldgenPatches263;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Tail of the modern patch chain for Minecraft 26.3 and later:
 * AbstractSpliterator &rarr; MinecraftClientPatcher &rarr; MinecraftChunkDrawTelemetryPatcher
 * &rarr; Minecraft262BrowserPatcher &rarr; MinecraftServerWorkerPatcher &rarr; this patcher.
 * It reads the jar produced by the previous steps, so it sees their output.
 *
 * <p>Holds the 26.3-only transforms and the rewrites that share nothing with the 26.2 patches.
 * Each domain class belongs to one work package (contract C2):
 * {@link RenderPatches263} (P3), {@link InputPatches263} (P2), {@link TerrainPatches263} (P5),
 * {@link WorldgenPatches263} (P6), {@link ServerPatches263} (P7a), {@link UiPatches263} (P8).
 * Each exposes {@code static void apply(String jar, Path root, ModernSymbols s)} and wraps every
 * patch in {@code PatchRegistry.run("<Domain>Patches263.<method>", ...)}.
 *
 * <p>Never runs for 26.2 or 1.21.11: it refuses those profiles and any jar that does not probe as
 * the renderpearl API, so 26.2 output cannot depend on it.
 */
public final class Minecraft263BrowserPatcher {
    private Minecraft263BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: Minecraft263BrowserPatcher INPUT_JAR OUTPUT_ROOT MINECRAFT_VERSION");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        String version = args[2];
        if (!PatchRegistry.bringupEligible(version)) {
            throw new IllegalArgumentException(
                    "Minecraft263BrowserPatcher runs only for 26.3 and later, not for profile "
                            + version);
        }
        PatchRegistry.configureProfile(version);
        ModernSymbols symbols = ModernSymbols.probe(jar);
        if (!symbols.renderpearl()) {
            throw new IllegalStateException("Minecraft263BrowserPatcher: " + jar
                    + " is not a 26.3+ client (" + symbols.summary() + ")");
        }
        System.out.println("Minecraft263BrowserPatcher: profile " + version + ", "
                + symbols.summary());
        RenderPatches263.apply(jar, root, symbols);
        InputPatches263.apply(jar, root, symbols);
        TerrainPatches263.apply(jar, root, symbols);
        WorldgenPatches263.apply(jar, root, symbols);
        ServerPatches263.apply(jar, root, symbols);
        UiPatches263.apply(jar, root, symbols);
        PatchRegistry.printSummary();
    }
}
