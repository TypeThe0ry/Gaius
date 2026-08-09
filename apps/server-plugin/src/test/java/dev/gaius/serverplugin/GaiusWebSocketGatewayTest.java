package dev.gaius.serverplugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

final class GaiusWebSocketGatewayTest {
    @Test
    void forwardsOnlyToConfiguredMinecraftSocket() throws Exception {
        try (ServerSocket minecraft = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<byte[]> receivedByMinecraft = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var socket = minecraft.accept()) {
                    byte[] request = socket.getInputStream().readNBytes(4);
                    receivedByMinecraft.complete(request);
                    socket.getOutputStream().write("pong".getBytes(StandardCharsets.UTF_8));
                } catch (Exception exception) {
                    receivedByMinecraft.completeExceptionally(exception);
                }
            });

            int gatewayPort = availablePort();
            GaiusWebSocketGateway gateway = new GaiusWebSocketGateway(
                    new InetSocketAddress("127.0.0.1", gatewayPort),
                    "127.0.0.1",
                    minecraft.getLocalPort(),
                    2000,
                    4,
                    1024,
                    true,
                    "test-token",
                    List.of("https://play.example"),
                    Logger.getLogger("GaiusWebSocketGatewayTest"));
            gateway.start();

            CountDownLatch connected = new CountDownLatch(1);
            CompletableFuture<String> connectingControl = new CompletableFuture<>();
            CompletableFuture<String> connectedControl = new CompletableFuture<>();
            CompletableFuture<byte[]> browserReply = new CompletableFuture<>();
            WebSocketClient client = new WebSocketClient(
                    URI.create("ws://127.0.0.1:" + gatewayPort + "/tunnel")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("{\"type\":\"connect\",\"host\":\" 127.0.0.1. \","
                            + "\"port\":" + minecraft.getLocalPort() + ",\"token\":\"test-token\"}");
                }

                @Override
                public void onMessage(String message) {
                    if (message.contains("\"type\":\"connecting\"")) {
                        connectingControl.complete(message);
                    } else if (message.contains("\"type\":\"connected\"")) {
                        connectedControl.complete(message);
                        connected.countDown();
                        send("ping".getBytes(StandardCharsets.UTF_8));
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] copy = new byte[bytes.remaining()];
                    bytes.get(copy);
                    browserReply.complete(copy);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // Assertions wait on the transfer futures.
                }

                @Override
                public void onError(Exception exception) {
                    browserReply.completeExceptionally(exception);
                }
            };
            client.addHeader("Origin", "https://play.example");

            try {
                assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
                assertEquals(
                        "{\"type\":\"connecting\",\"host\":\"127.0.0.1\",\"port\":"
                                + minecraft.getLocalPort()
                                + ",\"targetConnectTimeoutMs\":2000}",
                        connectingControl.get(5, TimeUnit.SECONDS));
                assertTrue(connected.await(5, TimeUnit.SECONDS));
                assertEquals(
                        "{\"type\":\"connected\",\"host\":\"127.0.0.1\",\"port\":"
                                + minecraft.getLocalPort() + "}",
                        connectedControl.get(5, TimeUnit.SECONDS));
                assertArrayEquals(
                        "ping".getBytes(StandardCharsets.UTF_8),
                        receivedByMinecraft.get(5, TimeUnit.SECONDS));
                assertArrayEquals(
                        "pong".getBytes(StandardCharsets.UTF_8),
                        browserReply.get(5, TimeUnit.SECONDS));
            } finally {
                client.closeBlocking();
                gateway.shutdown();
            }
        }
    }

    @Test
    void legacyClientsMayOmitTargetAndReceiveConfiguredTarget() throws Exception {
        BlockingConnectSocket target = new BlockingConnectSocket();
        int gatewayPort = availablePort();
        GaiusWebSocketGateway gateway = new GaiusWebSocketGateway(
                new InetSocketAddress("127.0.0.1", gatewayPort),
                "127.0.0.1",
                25565,
                5000,
                4,
                1024,
                true,
                "test-token",
                List.of("https://play.example"),
                Logger.getLogger("GaiusWebSocketGatewayTest"),
                () -> target);
        gateway.start();

        CompletableFuture<String> connectingControl = new CompletableFuture<>();
        WebSocketClient client = new WebSocketClient(
                URI.create("ws://127.0.0.1:" + gatewayPort + "/tunnel")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                send("{\"type\":\"connect\",\"token\":\"test-token\"}");
            }

            @Override
            public void onMessage(String message) {
                if (message.contains("\"type\":\"connecting\"")) {
                    connectingControl.complete(message);
                }
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                // This test closes after the compatibility control message.
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                // The test closes the browser after receiving the control message.
            }

            @Override
            public void onError(Exception exception) {
                connectingControl.completeExceptionally(exception);
            }
        };
        client.addHeader("Origin", "https://play.example");

        try {
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            assertEquals(
                    "{\"type\":\"connecting\",\"host\":\"127.0.0.1\",\"port\":25565,"
                            + "\"targetConnectTimeoutMs\":5000}",
                    connectingControl.get(5, TimeUnit.SECONDS));
        } finally {
            client.closeBlocking();
            gateway.shutdown();
        }
    }

    @Test
    void rejectsTargetDifferentFromConfiguredMinecraftSocket() throws Exception {
        AtomicBoolean socketCreated = new AtomicBoolean();
        int gatewayPort = availablePort();
        GaiusWebSocketGateway gateway = new GaiusWebSocketGateway(
                new InetSocketAddress("127.0.0.1", gatewayPort),
                "127.0.0.1",
                25565,
                2000,
                4,
                1024,
                true,
                "test-token",
                List.of("https://play.example"),
                Logger.getLogger("GaiusWebSocketGatewayTest"),
                () -> {
                    socketCreated.set(true);
                    return new Socket();
                });
        gateway.start();

        CompletableFuture<String> close = new CompletableFuture<>();
        WebSocketClient client = new WebSocketClient(
                URI.create("ws://127.0.0.1:" + gatewayPort + "/tunnel")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                send("{\"type\":\"connect\",\"host\":\"other.example\","
                        + "\"port\":25565,\"token\":\"test-token\"}");
            }

            @Override
            public void onMessage(String message) {
                // A mismatched target must be rejected before connecting.
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                // A mismatched target must not open a binary stream.
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                close.complete(code + ":" + reason);
            }

            @Override
            public void onError(Exception exception) {
                close.completeExceptionally(exception);
            }
        };
        client.addHeader("Origin", "https://play.example");

        try {
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            String closeReason = close.get(5, TimeUnit.SECONDS);
            assertTrue(closeReason.startsWith("1008:"), closeReason);
            assertTrue(closeReason.contains("does not match"), closeReason);
            assertTrue(!socketCreated.get(), "A rejected target must not create a TCP socket");
        } finally {
            if (client.isOpen()) {
                client.closeBlocking();
            }
            gateway.shutdown();
        }
    }

    @Test
    void closingBrowserCancelsInFlightMinecraftConnect() throws Exception {
        BlockingConnectSocket target = new BlockingConnectSocket();
        int gatewayPort = availablePort();
        GaiusWebSocketGateway gateway = new GaiusWebSocketGateway(
                new InetSocketAddress("127.0.0.1", gatewayPort),
                "192.0.2.1",
                25565,
                5000,
                4,
                1024,
                true,
                "test-token",
                List.of("https://play.example"),
                Logger.getLogger("GaiusWebSocketGatewayTest"),
                () -> target);
        gateway.start();

        CountDownLatch connecting = new CountDownLatch(1);
        WebSocketClient client = new WebSocketClient(
                URI.create("ws://127.0.0.1:" + gatewayPort + "/tunnel")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                send("{\"type\":\"connect\",\"token\":\"test-token\"}");
            }

            @Override
            public void onMessage(String message) {
                if (message.contains("\"type\":\"connecting\"")) {
                    connecting.countDown();
                }
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                // This test closes before the target is connected.
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                // The latches below verify target-side cancellation.
            }

            @Override
            public void onError(Exception exception) {
                // Closing the WebSocket intentionally interrupts the target connect.
            }
        };
        client.addHeader("Origin", "https://play.example");

        try {
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            assertTrue(target.connectStarted.await(5, TimeUnit.SECONDS));
            client.closeBlocking();
            assertTrue(target.closed.await(5, TimeUnit.SECONDS));
            assertTrue(target.connectFinished.await(5, TimeUnit.SECONDS));
        } finally {
            if (client.isOpen()) {
                client.closeBlocking();
            }
            gateway.shutdown();
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class BlockingConnectSocket extends Socket {
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch connectFinished = new CountDownLatch(1);

        @Override
        public void setTcpNoDelay(boolean enabled) {
            // No native socket is needed for the deterministic cancellation test.
        }

        @Override
        public void setKeepAlive(boolean enabled) {
            // No native socket is needed for the deterministic cancellation test.
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectStarted.countDown();
            try {
                if (!closed.await(5, TimeUnit.SECONDS)) {
                    throw new SocketException("test connect was not cancelled");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SocketException("test connect was interrupted");
            } finally {
                connectFinished.countDown();
            }
            throw new SocketException("socket closed during connect");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
