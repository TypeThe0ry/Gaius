package dev.gaius.serverplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

final class GaiusWebSocketGateway extends WebSocketServer {
    private static final int CLOSE_POLICY = 1008;
    private static final int CLOSE_TOO_LARGE = 1009;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final String minecraftHost;
    private final int minecraftPort;
    private final int connectTimeoutMillis;
    private final int maximumConnections;
    private final int maximumFrameBytes;
    private final byte[] accessToken;
    private final List<String> allowedOrigins;
    private final Logger logger;
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();

    GaiusWebSocketGateway(
            InetSocketAddress listenAddress,
            String minecraftHost,
            int minecraftPort,
            int connectTimeoutMillis,
            int maximumConnections,
            int maximumFrameBytes,
            String accessToken,
            List<String> allowedOrigins,
            Logger logger) {
        super(listenAddress);
        this.minecraftHost = minecraftHost;
        this.minecraftPort = minecraftPort;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.maximumConnections = maximumConnections;
        this.maximumFrameBytes = maximumFrameBytes;
        this.accessToken = accessToken.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("*")
                : List.copyOf(allowedOrigins);
        this.logger = logger;
        setReuseAddr(true);
        setTcpNoDelay(true);
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (!path(handshake).equals("/tunnel")) {
            connection.close(CLOSE_POLICY, "Unknown Gaius endpoint");
            return;
        }
        if (!originAllowed(handshake.getFieldValue("Origin"))) {
            connection.close(CLOSE_POLICY, "Origin is not allowed");
            return;
        }
        if (sessions.size() >= maximumConnections) {
            connection.close(1013, "Gaius transport is at capacity");
            return;
        }
        sessions.put(connection, new Session(connection));
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        Session session = sessions.get(connection);
        if (session == null) {
            connection.close(CLOSE_POLICY, "Unknown Gaius session");
            return;
        }
        try {
            JsonObject control = JsonParser.parseString(message).getAsJsonObject();
            String type = control.has("type") ? control.get("type").getAsString() : "";
            if (type.equals("connect")) {
                session.connect(control);
            } else if (type.equals("flow")) {
                session.setPaused(control.has("paused") && control.get("paused").getAsBoolean());
            } else {
                connection.close(CLOSE_POLICY, "Unsupported Gaius control message");
            }
        } catch (RuntimeException exception) {
            connection.close(CLOSE_POLICY, "Invalid Gaius control message");
        }
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer message) {
        Session session = sessions.get(connection);
        if (session == null || !session.connected.get()) {
            connection.close(CLOSE_POLICY, "Gaius tunnel is not connected");
            return;
        }
        if (message.remaining() > maximumFrameBytes) {
            connection.close(CLOSE_TOO_LARGE, "Gaius frame exceeds configured limit");
            return;
        }
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        session.write(bytes);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        Session session = sessions.remove(connection);
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        if (connection != null) {
            Session session = sessions.remove(connection);
            if (session != null) {
                session.close();
            }
        }
        logger.log(Level.FINE, "Gaius WebSocket transport error", exception);
    }

    @Override
    public void onStart() {
        // The plugin logs the configured endpoint after start().
    }

    void shutdown() {
        for (Session session : new ArrayList<>(sessions.values())) {
            session.close();
        }
        sessions.clear();
        try {
            stop(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean originAllowed(String origin) {
        String candidate = origin == null || origin.isBlank() ? "null" : origin;
        for (String allowed : allowedOrigins) {
            if (allowed.equals("*") || allowed.equals(candidate)) {
                return true;
            }
            if (allowed.endsWith("*")
                    && candidate.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String path(ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        int query = descriptor.indexOf('?');
        return query >= 0 ? descriptor.substring(0, query) : descriptor;
    }

    private boolean tokenMatches(JsonObject control) {
        if (accessToken.length == 0) {
            return true;
        }
        byte[] candidate = control.has("token")
                ? control.get("token").getAsString().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        return MessageDigest.isEqual(accessToken, candidate);
    }

    private final class Session {
        private final WebSocket webSocket;
        private final AtomicBoolean connected = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object outputLock = new Object();
        private final Object pauseLock = new Object();
        private volatile boolean paused;
        private Socket socket;
        private OutputStream output;

        private Session(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        private void connect(JsonObject control) {
            if (!connected.compareAndSet(false, true)) {
                webSocket.close(CLOSE_POLICY, "Gaius tunnel already connected");
                return;
            }
            if (!tokenMatches(control)) {
                webSocket.close(CLOSE_POLICY, "Invalid Gaius access token");
                return;
            }
            Thread.ofVirtual().name("gaius-websocket-connect").start(() -> {
                try {
                    Socket target = new Socket();
                    target.setTcpNoDelay(true);
                    target.setKeepAlive(true);
                    target.connect(
                            new InetSocketAddress(minecraftHost, minecraftPort),
                            connectTimeoutMillis);
                    socket = target;
                    output = target.getOutputStream();
                    webSocket.send("{\"type\":\"connected\"}");
                    copyServerToBrowser(target.getInputStream());
                } catch (IOException exception) {
                    if (!closed.get()) {
                        webSocket.close(1011, "Minecraft server connection failed");
                    }
                } finally {
                    close();
                }
            });
        }

        private void write(byte[] bytes) {
            try {
                synchronized (outputLock) {
                    if (output == null) {
                        throw new IOException("Minecraft socket is unavailable");
                    }
                    output.write(bytes);
                    output.flush();
                }
            } catch (IOException exception) {
                webSocket.close(1011, "Minecraft server write failed");
                close();
            }
        }

        private void copyServerToBrowser(InputStream input) throws IOException {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (!closed.get()) {
                awaitReadable();
                int length = input.read(buffer);
                if (length < 0) {
                    return;
                }
                while (webSocket.hasBufferedData() && !closed.get()) {
                    sleepBriefly();
                }
                if (closed.get()) {
                    return;
                }
                webSocket.send(ByteBuffer.wrap(buffer, 0, length));
            }
        }

        private void setPaused(boolean paused) {
            synchronized (pauseLock) {
                this.paused = paused;
                if (!paused) {
                    pauseLock.notifyAll();
                }
            }
        }

        private void awaitReadable() throws IOException {
            synchronized (pauseLock) {
                while (paused && !closed.get()) {
                    try {
                        pauseLock.wait(1000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Gaius relay thread interrupted", exception);
                    }
                }
            }
        }

        private void sleepBriefly() throws IOException {
            try {
                Thread.sleep(2);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Gaius relay thread interrupted", exception);
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            Socket target = socket;
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // The transport is already closing.
                }
            }
            if (webSocket.isOpen()) {
                webSocket.close(1000, "Gaius tunnel closed");
            }
        }
    }
}
