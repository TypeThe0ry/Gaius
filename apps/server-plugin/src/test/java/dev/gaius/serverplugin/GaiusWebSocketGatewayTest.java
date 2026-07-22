package dev.gaius.serverplugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                    "test-token",
                    List.of("https://play.example"),
                    Logger.getLogger("GaiusWebSocketGatewayTest"));
            gateway.start();

            CountDownLatch connected = new CountDownLatch(1);
            CompletableFuture<byte[]> browserReply = new CompletableFuture<>();
            WebSocketClient client = new WebSocketClient(
                    URI.create("ws://127.0.0.1:" + gatewayPort + "/tunnel")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("{\"type\":\"connect\",\"host\":\"untrusted.example\","
                            + "\"port\":1,\"token\":\"test-token\"}");
                }

                @Override
                public void onMessage(String message) {
                    if (message.contains("\"type\":\"connected\"")) {
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
                assertTrue(connected.await(5, TimeUnit.SECONDS));
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

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
