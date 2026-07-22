package dev.gaius.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.InetSocketAddress;
import net.minecraft.server.Main;
import net.minecraft.server.MinecraftServer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;

/** TeaVM entry point for the official dedicated-server runtime inside a Web Worker. */
public final class BrowserIntegratedServerMain {
    private static final int INITIAL_VIEW_DISTANCE = 1;
    private static final int INITIAL_SIMULATION_DISTANCE = 1;
    private static MinecraftServer server;
    private static int configuredViewDistance = 6;
    private static int configuredSimulationDistance = 4;
    private static int activeViewDistance = INITIAL_VIEW_DISTANCE;
    private static int activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
    private static boolean configuredDistancesActive;

    private static String serverProperties() {
        int viewDistance = clampDistance(workerViewDistance(), 6);
        int simulationDistance = clampDistance(workerSimulationDistance(), 4);
        return String.join("\n",
            "allow-flight=true",
            "enable-command-block=true",
            "enable-query=false",
            "enable-rcon=false",
            "enable-status=false",
            "enforce-secure-profile=false",
            "force-gamemode=false",
            "gamemode=survival",
            "generate-structures=true",
            "hardcore=false",
            "level-name=world",
            "max-players=1",
            "max-tick-time=-1",
            "motd=Gaius Integrated Server",
            "network-compression-threshold=256",
            "online-mode=false",
            "pause-when-empty-seconds=-1",
            "player-idle-timeout=0",
            "prevent-proxy-connections=false",
            "simulation-distance=" + simulationDistance,
            "spawn-protection=0",
            "sync-chunk-writes=false",
            "view-distance=" + viewDistance) + "\n";
    }

    private BrowserIntegratedServerMain() {
    }

    public static InetSocketAddress tunnelAddress() {
        String sessionId = workerSessionId();
        if (!isSafeSessionId(sessionId)) {
            throw new IllegalStateException("Browser server worker session is unavailable");
        }
        return InetSocketAddress.createUnresolved(
                "server-" + sessionId + ".gaius-local",
                25565);
    }

    public static void registerServer(MinecraftServer minecraftServer) {
        if (!isWorkerRuntime()) {
            return;
        }
        server = minecraftServer;
        configuredViewDistance = clampDistance(workerViewDistance(), 6);
        configuredSimulationDistance = clampDistance(workerSimulationDistance(), 4);
        activeViewDistance = INITIAL_VIEW_DISTANCE;
        activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
        configuredDistancesActive = false;
        setIntegratedServerDistances(workerViewDistance(), workerSimulationDistance());
        report("server-created", workerWorldId());
    }

    @JSExport
    public static void setIntegratedServerDistances(int viewDistance, int simulationDistance) {
        configuredViewDistance = clampDistance(viewDistance, 6);
        configuredSimulationDistance = clampDistance(simulationDistance, 4);
        if (configuredDistancesActive) {
            activeViewDistance = Math.min(activeViewDistance, configuredViewDistance);
            activeSimulationDistance = Math.min(
                    activeSimulationDistance,
                    configuredSimulationDistance);
        }
        applyActiveDistances();
    }

    private static void applyActiveDistances() {
        MinecraftServer current = server;
        if (current != null && current.getPlayerList() != null) {
            int view = configuredDistancesActive
                    ? activeViewDistance
                    : INITIAL_VIEW_DISTANCE;
            int simulation = configuredDistancesActive
                    ? activeSimulationDistance
                    : INITIAL_SIMULATION_DISTANCE;
            current.getPlayerList().setViewDistance(view);
            current.getPlayerList().setSimulationDistance(simulation);
            if (configuredDistancesActive) {
                String event = view == configuredViewDistance
                                && simulation == configuredSimulationDistance
                        ? "server-distances"
                        : "server-distances-ramping";
                report(event, view + "/" + simulation);
            } else {
                report(
                        "server-distances-staged",
                        view + "/" + simulation + "->"
                                + configuredViewDistance + "/" + configuredSimulationDistance);
            }
        }
    }

    /** Restores the user's distances after the client confirms its first chunk batch. */
    public static void activateConfiguredDistances() {
        if (!isWorkerRuntime() || configuredDistancesActive) {
            return;
        }
        configuredDistancesActive = true;
        activeViewDistance = Math.min(configuredViewDistance, INITIAL_VIEW_DISTANCE + 1);
        activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
        applyActiveDistances();
    }

    /** Adds one distance ring only after the preceding server tick has completed. */
    public static void advanceConfiguredDistances() {
        if (!isWorkerRuntime() || !configuredDistancesActive) {
            return;
        }
        int nextView = Math.min(configuredViewDistance, activeViewDistance + 1);
        int nextSimulation = Math.min(
                configuredSimulationDistance,
                activeSimulationDistance + 1);
        if (nextView == activeViewDistance && nextSimulation == activeSimulationDistance) {
            return;
        }
        activeViewDistance = nextView;
        activeSimulationDistance = nextSimulation;
        applyActiveDistances();
    }

    public static boolean isWorkerServer() {
        return isWorkerRuntime();
    }

    /** Vanilla's minimum of two forces 25 chunks before a browser player can enter. */
    public static int minimumServerViewDistance() {
        return isWorkerRuntime() ? INITIAL_VIEW_DISTANCE : 2;
    }

    /** Keeps local block breaking tied to wall time when world generation lowers server TPS. */
    public static int adjustDestroyTicks(int serverTicks, long startedAtMillis) {
        if (!isWorkerRuntime() || startedAtMillis <= 0L) {
            return serverTicks;
        }
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        int wallTicks = (int) Math.min(Integer.MAX_VALUE, elapsedMillis / 50L);
        return Math.max(serverTicks, wallTicks);
    }

    /** The local client sends STOP only after its validated break progress has completed. */
    public static float completeLocalDestroyProgress(float progress) {
        return isWorkerRuntime() ? Math.max(progress, 0.7F) : progress;
    }

    /** Re-throws the failure swallowed by the vanilla dedicated-server entry point. */
    public static void rethrowStartupFailure(Throwable exception) {
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(exception);
    }

    @JSExport
    public static void stopIntegratedServer() {
        MinecraftServer current = server;
        if (current != null && current.isRunning()) {
            report("stopping", workerWorldId());
            current.halt(false);
        }
    }

    @JSExport
    public static boolean isIntegratedServerStopped() {
        MinecraftServer current = server;
        return current == null || current.isStopped();
    }

    public static void main(String[] args) {
        try {
            BrowserFilePersistence.mount();
            String worldId = workerWorldId();
            String sessionId = workerSessionId();
            if (!isSafeIdentifier(worldId) || !isSafeSessionId(sessionId)) {
                throw new IllegalArgumentException("Invalid browser singleplayer worker initialization");
            }
            writeServerConfiguration();
            report("booting", worldId);
            Main.main(new String[] {
                    "--nogui",
                    "--universe", "/gaius/saves",
                    "--world", worldId,
                    "--port", "25565"
            });
        } catch (Throwable exception) {
            report("crash", describeWithStack(exception));
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(exception);
        }
    }

    private static void writeServerConfiguration() throws Exception {
        Path properties = Path.of("server.properties");
        Files.writeString(
                properties,
                serverProperties(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.writeString(
                Path.of("eula.txt"),
                "eula=true\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= 128
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && !value.equals(".")
                && !value.equals("..");
    }

    private static int clampDistance(int value, int fallback) {
        int selected = value > 0 ? value : fallback;
        return Math.max(2, Math.min(32, selected));
    }

    private static boolean isSafeSessionId(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String describeWithStack(Throwable exception) {
        StringBuilder description = new StringBuilder();
        String nativeStack = nativeStack(exception);
        if (nativeStack != null && !nativeStack.isEmpty()) {
            description.append(nativeStack);
        }
        Throwable current = exception;
        int causeCount = 0;
        while (current != null && causeCount < 4 && description.length() < 12_000) {
            if (description.length() > 0) {
                description.append("\nCaused by: ");
            }
            description.append(describe(current));
            StackTraceElement[] stack = current.getStackTrace();
            int frameCount = Math.min(stack.length, 32);
            for (int index = 0; index < frameCount && description.length() < 12_000; index++) {
                description.append("\n  at ").append(stack[index]);
            }
            current = current.getCause();
            causeCount++;
        }
        return description.toString();
    }

    @JSBody(params = "exception", script = """
            try {
              var nativeError = exception && exception.$jsException;
              return String(nativeError && (nativeError.stack || nativeError.message) || '');
            } catch (ignored) {
              return '';
            }
            """)
    private static native String nativeStack(Throwable exception);

    @JSBody(script = "return String(globalThis.__gaiusServerWorldId || '');")
    private static native String workerWorldId();

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    @JSBody(script = "return String(globalThis.__gaiusServerSessionId || '');")
    private static native String workerSessionId();

    @JSBody(script = "return Number(globalThis.__gaiusServerViewDistance || 6) | 0;")
    private static native int workerViewDistance();

    @JSBody(script = "return Number(globalThis.__gaiusServerSimulationDistance || 4) | 0;")
    private static native int workerSimulationDistance();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              postMessage({type: String(event), detail: String(detail), at: Date.now()});
            } catch (ignored) {}
            """)
    private static native void report(String event, String detail);
}
