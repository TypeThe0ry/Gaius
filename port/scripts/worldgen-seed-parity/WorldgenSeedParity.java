package dev.gaius.parity;

import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gizmos.GizmoCollector;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

/**
 * JVM side of the 26.3 worldgen seed-parity harness (migration plan P6, acceptance
 * "seed parity"). Creates a fresh default ("minecraft:normal", structures on, no bonus chest,
 * vanilla data pack only) overworld for one seed in-process, the way the vanilla game-test
 * server builds its world, and hashes a square of chunks centred on the spawn chunk:
 * <ul>
 *   <li>{@code terrain} mode marks the world as initialized so setInitialSpawn does not
 *       generate the spawn area, asks for every chunk only up to ChunkStatus.TERRAIN (noise,
 *       surface rules, aquifers, carvers) and hashes block states and biomes right away. No
 *       feature has run yet, so these hashes do not depend on generation order.</li>
 *   <li>{@code full} mode runs the vanilla setInitialSpawn, records the world spawn, generates
 *       the chunks to FULL and hashes block states and biomes. Features may write across chunk
 *       borders, so a full-hash difference needs a per-block look before it is called a
 *       worldgen difference.</li>
 * </ul>
 * The centre is {@code ChunkGenerator.getOrigin(RandomState)}, the spawn chunk vanilla 26.3
 * and the browser's fast initial spawn both start from.
 *
 * <p>usage: {@code WorldgenSeedParity --seed <long> --mode terrain|full --side <chunks>
 * --universe <dir> --out <json> [--label <text>]}. Runs with the vanilla client (or server)
 * jar and its libraries on the class path; with the patched worldgen classes and the JVM
 * helper shims first on the class path it checks the browser patches instead.
 */
public final class WorldgenSeedParity {
    private WorldgenSeedParity() {
    }

    record Options(long seed, String mode, int side, Path universe, Path out, String label) {
        static Options parse(String[] args) {
            Long seed = null;
            String mode = null;
            int side = 8;
            Path universe = null;
            Path out = null;
            String label = "";
            for (int index = 0; index < args.length; index++) {
                String value = index + 1 < args.length ? args[index + 1] : null;
                switch (args[index]) {
                    case "--seed" -> seed = Long.parseLong(require(value, "--seed"));
                    case "--mode" -> mode = require(value, "--mode");
                    case "--side" -> side = Integer.parseInt(require(value, "--side"));
                    case "--universe" -> universe = Path.of(require(value, "--universe"));
                    case "--out" -> out = Path.of(require(value, "--out"));
                    case "--label" -> label = require(value, "--label");
                    default -> throw new IllegalArgumentException("unknown argument " + args[index]);
                }
                index++;
            }
            if (seed == null || universe == null || out == null
                    || !("terrain".equals(mode) || "full".equals(mode)) || side < 1 || side > 32) {
                throw new IllegalArgumentException("usage: WorldgenSeedParity --seed <long> "
                        + "--mode terrain|full [--side 1..32] --universe <dir> --out <json> "
                        + "[--label <text>]");
            }
            return new Options(seed, mode, side, universe, out, label);
        }

        private static String require(String value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " needs a value");
            }
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--strip-signature")) {
            stripSignature(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        Options options = Options.parse(args);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Files.createDirectories(options.universe());
        String worldName = "parity-" + options.mode() + "-" + Long.toUnsignedString(options.seed());
        deleteRecursively(options.universe().resolve(worldName));
        LevelStorageSource source = LevelStorageSource.createDefault(options.universe());
        LevelStorageSource.LevelStorageAccess access = source.createAccess(worldName);
        PackRepository packs = ServerPacksSource.createPackRepository(access);
        long started = System.nanoTime();
        ParityServer server = MinecraftServer.spin(thread -> ParityServer.create(thread, access, packs, options));
        while (server.isRunning() || !server.finished) {
            Thread.sleep(100);
            if (server.failure != null) {
                break;
            }
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (server.failure != null) {
            server.failure.printStackTrace();
            System.out.println("WORLDGEN_SEED_PARITY_FAIL " + server.failure);
            System.exit(1);
        }
        System.out.println("WORLDGEN_SEED_PARITY_OK mode=" + options.mode() + " seed="
                + options.seed() + " chunks=" + server.chunkCount + " millis=" + millis
                + " aggregate=" + server.aggregate);
        System.exit(0);
    }

    /**
     * Copies a signed jar without its signature files. Patched worldgen classes can only
     * replace classes of the vanilla jar on the class path when the jar is unsigned (a signed
     * package rejects classes from another code source).
     */
    static void stripSignature(Path input, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(input));
                var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(output))) {
            java.util.zip.ZipEntry entry;
            int removed = 0;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
                    removed++;
                    continue;
                }
                out.putNextEntry(new java.util.zip.ZipEntry(name));
                in.transferTo(out);
                out.closeEntry();
            }
            System.out.println("WORLDGEN_SEED_PARITY_STRIPPED " + output + " signatureFiles=" + removed);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    /** A headless server in the style of vanilla's GameTestServer, for one seed. */
    static final class ParityServer extends MinecraftServer {
        private final Options options;
        private final LocalSampleLogger sampleLogger = new LocalSampleLogger(4);
        private boolean generated;
        volatile boolean finished;
        volatile Throwable failure;
        volatile int chunkCount;
        volatile String aggregate = "";

        private ParityServer(Thread thread, LevelStorageSource.LevelStorageAccess access,
                PackRepository packs, WorldStem stem, Options options) {
            super(thread, access, packs, stem, Optional.of(new GameRules(FeatureFlags.DEFAULT_FLAGS)),
                    Proxy.NO_PROXY, DataFixers.getDataFixer(), noServices(),
                    LoggingLevelLoadListener.forDedicatedServer(), false, new NotificationManager());
            this.options = options;
        }

        static ParityServer create(Thread thread, LevelStorageSource.LevelStorageAccess access,
                PackRepository packs, Options options) {
            packs.reload();
            WorldDataConfiguration dataConfiguration = new WorldDataConfiguration(
                    new DataPackConfig(List.of("vanilla"), List.of()), FeatureFlags.DEFAULT_FLAGS);
            LevelSettings settings = new LevelSettings("Gaius worldgen parity", GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT, false, dataConfiguration);
            WorldLoader.PackConfig packConfig =
                    new WorldLoader.PackConfig(packs, dataConfiguration, false, true);
            WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig,
                    Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
            WorldOptions worldOptions = new WorldOptions(options.seed(), true, false);
            try {
                WorldStem stem = Util.<WorldStem>blockUntilDone(executor -> WorldLoader.load(
                        initConfig,
                        context -> {
                            Registry<LevelStem> noDatapackDimensions = new MappedRegistry<>(
                                    Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                            WorldDimensions preset = context.datapackWorldRegistries()
                                    .lookupOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.NORMAL).value()
                                    .createWorldDimensions();
                            WorldDimensions.Complete dimensions = preset.bake(noDatapackDimensions);
                            PrimaryLevelData data = new PrimaryLevelData(settings,
                                    dimensions.specialWorldProperty(), dimensions.lifecycle());
                            if ("terrain".equals(options.mode())) {
                                // Skip setInitialSpawn: it would generate the spawn area to FULL.
                                data.setInitialized(true);
                            }
                            return new WorldLoader.DataLoadOutput<>(
                                    new LevelDataAndDimensions.WorldDataAndGenSettings(
                                            data, new WorldGenSettings(worldOptions, preset)),
                                    dimensions.dimensionsRegistryAccess());
                        },
                        WorldStem::new,
                        Util.backgroundExecutor(),
                        executor)).get();
                return new ParityServer(thread, access, packs, stem, options);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot load the vanilla data pack", exception);
            }
        }

        private static Services noServices() {
            try {
                Field field = GameTestServer.class.getDeclaredField("NO_SERVICES");
                field.setAccessible(true);
                return (Services) field.get(null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("GameTestServer.NO_SERVICES changed", exception);
            }
        }

        @Override
        protected boolean initServer() {
            setPlayerList(new PlayerList(this, registries(), playerDataStorage,
                    new EmptyNotificationService()) {
            });
            try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(GizmoCollector.NOOP)) {
                loadLevel();
            }
            return true;
        }

        @Override
        protected void tickServer(BooleanSupplier haveTime) {
            if (generated) {
                super.tickServer(haveTime);
                return;
            }
            // Hash before the first level tick: scheduled fluid and block ticks of the chunks
            // generated so far would otherwise change blocks between runs.
            generated = true;
            try {
                generateAndHash();
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                finished = true;
                halt(false);
            }
        }

        private void generateAndHash() throws IOException {
            ServerLevel level = overworld();
            ServerChunkCache chunks = level.getChunkSource();
            ChunkPos origin = chunks.getGenerator().getOrigin(chunks.randomState());
            BlockPos spawn = level.getRespawnData().pos();
            int side = options.side();
            int minX = origin.x() - side / 2;
            int minZ = origin.z() - side / 2;
            ChunkStatus status = "terrain".equals(options.mode()) ? ChunkStatus.TERRAIN : ChunkStatus.FULL;
            List<String> chunkLines = new ArrayList<>();
            MessageDigest blocksAggregate = sha256();
            MessageDigest biomesAggregate = sha256();
            Map<BlockState, byte[]> stateNames = new IdentityHashMap<>();
            Map<Holder<Biome>, byte[]> biomeNames = new IdentityHashMap<>();
            long generationNanos = 0;
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    long start = System.nanoTime();
                    ChunkAccess chunk = chunks.getChunk(x, z, status, true);
                    generationNanos += System.nanoTime() - start;
                    if (chunk == null) {
                        throw new IllegalStateException("chunk " + x + "," + z + " was not generated");
                    }
                    if (!chunk.getPersistedStatus().isOrAfter(status)) {
                        throw new IllegalStateException("chunk " + x + "," + z + " is only "
                                + chunk.getPersistedStatus());
                    }
                    String blocks = hashBlocks(chunk, stateNames);
                    String biomes = hashBiomes(chunk, biomeNames);
                    blocksAggregate.update(blocks.getBytes(StandardCharsets.US_ASCII));
                    biomesAggregate.update(biomes.getBytes(StandardCharsets.US_ASCII));
                    chunkLines.add(String.format(Locale.ROOT,
                            "    {\"x\": %d, \"z\": %d, \"status\": \"%s\", \"blocks\": \"%s\", \"biomes\": \"%s\"}",
                            x, z, chunk.getPersistedStatus().getName(), blocks, biomes));
                }
            }
            String blocks = HexFormat.of().formatHex(blocksAggregate.digest());
            String biomes = HexFormat.of().formatHex(biomesAggregate.digest());
            chunkCount = chunkLines.size();
            aggregate = "blocks:" + blocks.substring(0, 16) + ",biomes:" + biomes.substring(0, 16);
            String pulses = "";
            try {
                Class<?> shim = Class.forName("dev.gaius.browser.BrowserWorldgenDeepCheckpoint");
                pulses = String.valueOf(shim.getMethod("parityReport").invoke(null));
            } catch (ClassNotFoundException expected) {
                // Vanilla run: no browser classes on the class path.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Pulse shim report failed", exception);
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"tool\": \"worldgen-seed-parity\",\n");
            json.append("  \"format\": 1,\n");
            json.append("  \"label\": ").append(quote(options.label())).append(",\n");
            json.append("  \"minecraft\": ").append(quote(SharedConstants.getCurrentVersion().name())).append(",\n");
            json.append("  \"seed\": \"").append(options.seed()).append("\",\n");
            json.append("  \"mode\": \"").append(options.mode()).append("\",\n");
            json.append("  \"preset\": \"minecraft:normal\",\n");
            json.append("  \"side\": ").append(side).append(",\n");
            json.append(String.format(Locale.ROOT, "  \"origin\": {\"x\": %d, \"z\": %d},%n", origin.x(), origin.z()));
            json.append(String.format(Locale.ROOT, "  \"spawn\": {\"x\": %d, \"y\": %d, \"z\": %d, \"vanillaSpawnSearch\": %s},%n",
                    spawn.getX(), spawn.getY(), spawn.getZ(), "full".equals(options.mode())));
            json.append("  \"aggregate\": {\"blocks\": \"").append(blocks).append("\", \"biomes\": \"")
                    .append(biomes).append("\"},\n");
            json.append("  \"generationMillis\": ").append(TimeUnit.NANOSECONDS.toMillis(generationNanos)).append(",\n");
            json.append("  \"pulses\": ").append(pulses.isEmpty() ? "null" : pulses).append(",\n");
            json.append("  \"chunks\": [\n");
            json.append(String.join(",\n", chunkLines));
            json.append("\n  ]\n}\n");
            Files.createDirectories(options.out().toAbsolutePath().getParent());
            Files.writeString(options.out(), json, StandardCharsets.UTF_8);
        }

        /**
         * SHA-256 over every block state of the chunk, section by section in y, z, x order,
         * each written as its canonical {@code BlockState.toString()} and a newline.
         */
        private static String hashBlocks(ChunkAccess chunk, Map<BlockState, byte[]> names) {
            MessageDigest digest = sha256();
            for (LevelChunkSection section : chunk.getSections()) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            digest.update(names.computeIfAbsent(state, value ->
                                    (value.toString() + "\n").getBytes(StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        /** SHA-256 over the biome id of every 4x4x4 cell, section by section in y, z, x order. */
        private static String hashBiomes(ChunkAccess chunk, Map<Holder<Biome>, byte[]> names) {
            MessageDigest digest = sha256();
            for (LevelChunkSection section : chunk.getSections()) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        for (int x = 0; x < 4; x++) {
                            Holder<Biome> biome = section.getNoiseBiome(x, y, z);
                            digest.update(names.computeIfAbsent(biome, value -> (value.unwrapKey()
                                    .map(key -> key.identifier().toString())
                                    .orElse("unregistered") + "\n").getBytes(StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        @Override
        protected void waitUntilNextTick() {
            runAllTasks();
        }

        @Override
        protected SampleLogger getTickTimeLogger() {
            return sampleLogger;
        }

        @Override
        public boolean isTickTimeLoggingEnabled() {
            return false;
        }

        @Override
        public SystemReport fillServerSystemReport(SystemReport report) {
            report.setDetail("Type", "Gaius worldgen parity server");
            return report;
        }

        @Override
        protected void onServerExit() {
            super.onServerExit();
            finished = true;
        }

        @Override
        protected void onServerCrash(net.minecraft.CrashReport report) {
            super.onServerCrash(report);
            failure = new IllegalStateException(report.getFriendlyReport(
                    net.minecraft.ReportType.CRASH));
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public LevelBasedPermissionSet operatorUserPermissions() {
            return LevelBasedPermissionSet.OWNER;
        }

        @Override
        public PermissionSet getFunctionCompilationPermissions() {
            return LevelBasedPermissionSet.OWNER;
        }

        @Override
        public boolean shouldRconBroadcast() {
            return false;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public int getRateLimitPacketsPerSecond() {
            return 0;
        }

        @Override
        public int getCommandSpamThresholdSeconds() {
            return 0;
        }

        @Override
        public int getChatSpamThresholdSeconds() {
            return 0;
        }

        @Override
        public boolean useNativeTransport() {
            return false;
        }

        @Override
        public boolean isPublished() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public boolean isSingleplayerOwner(NameAndId player) {
            return false;
        }

        @Override
        public int getMaxPlayers() {
            return 1;
        }
    }
}
