package io.netty.channel.browser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/** Netty channel backed by Gaius' WebSocket-to-TCP browser bridge. */
public final class BrowserWebSocketChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int INITIAL_CHANNEL_CAPACITY = 16;
    private static final int MAX_CHUNKS_PER_PUMP = 128;
    private static final int MAX_BYTES_PER_PUMP = 2 * 1024 * 1024;
    private static final double MAX_MILLIS_PER_PUMP = 4.0;
    private static final EventLoop INLINE_EVENT_LOOP = new BrowserInlineEventLoop();
    private static BrowserWebSocketChannel[] channels =
            new BrowserWebSocketChannel[INITIAL_CHANNEL_CAPACITY];
    private static int nextSocketId = 1;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final int socketId;
    private boolean open = true;
    private boolean active;
    private SocketAddress localAddress;
    private SocketAddress remoteAddress;

    public BrowserWebSocketChannel() {
        super(null);
        socketId = nextSocketId++;
        if (nextSocketId <= 0) {
            nextSocketId = 1;
        }
        addChannel(this);
        initBridge();
    }

    public static void pumpAll() {
        for (BrowserWebSocketChannel channel : channels) {
            if (channel == null) {
                continue;
            }
            channel.pump();
        }
    }

    public static boolean isAvailable() {
        return isWebSocketAvailable();
    }

    /**
     * TeaVM cannot safely block on Netty's registration promise. Browser channels therefore
     * register on the current JavaScript turn so Bootstrap.connect returns a completed future.
     */
    public static boolean shouldRegisterInline(EventLoop eventLoop, Channel channel) {
        return channel instanceof BrowserWebSocketChannel || eventLoop.inEventLoop();
    }

    /** Gives only WebSocket channels the inline executor; LocalChannel keeps its original loop. */
    public static EventLoop eventLoopFor(Channel channel, EventLoop fallback) {
        return channel instanceof BrowserWebSocketChannel ? INLINE_EVENT_LOOP : fallback;
    }

    /** Executes only browser-channel connects inline; other Netty channel types keep their loop. */
    public static boolean connectInline(
            Channel channel,
            SocketAddress remote,
            SocketAddress local,
            ChannelPromise promise) {
        if (!(channel instanceof BrowserWebSocketChannel)) {
            return false;
        }
        channel.unsafe().connect(remote, local, promise);
        return true;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open && active;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new BrowserUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        this.localAddress = localAddress;
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() {
        if (!open) {
            return;
        }
        open = false;
        active = false;
        removeChannel(this);
        closeSocket(socketId);
    }

    @Override
    protected void doBeginRead() {
        pump();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer outbound) throws Exception {
        if (!open) {
            throw new ClosedChannelException();
        }
        if (!active) {
            throw new NotYetConnectedException();
        }
        while (true) {
            Object message = outbound.current();
            if (message == null) {
                return;
            }
            if (!(message instanceof ByteBuf buffer)) {
                outbound.remove(new ChannelException(
                        "BrowserWebSocketChannel only supports ByteBuf outbound messages"));
                continue;
            }
            sendSocket(socketId, copyBytes(buffer));
            outbound.remove();
        }
    }

    @Override
    protected Object filterOutboundMessage(Object message) {
        if (message instanceof ByteBuf) {
            return message;
        }
        throw new ChannelException(
                "BrowserWebSocketChannel only supports ByteBuf outbound messages");
    }

    private void connectBrowser(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
        if (active) {
            AlreadyConnectedException exception = new AlreadyConnectedException();
            promise.tryFailure(exception);
            pipeline().fireExceptionCaught(exception);
            return;
        }
        this.remoteAddress = remote;
        this.localAddress = local != null
                ? local
                : InetSocketAddress.createUnresolved("browser", 0);
        active = true;
        openSocket(socketId, host(remote), port(remote));
        promise.trySuccess();
        pipeline().fireChannelActive();
    }

    private void pump() {
        if (!open) {
            return;
        }
        String error = pollError(socketId);
        if (error != null) {
            pipeline().fireExceptionCaught(new ChannelException(error));
            close();
            return;
        }

        ChannelPipeline pipeline = pipeline();
        double pumpStarted = monotonicMillis();
        int chunks = 0;
        int bytesPumped = 0;
        while (chunks < MAX_CHUNKS_PER_PUMP && bytesPumped < MAX_BYTES_PER_PUMP) {
            if (chunks > 0 && monotonicMillis() - pumpStarted >= MAX_MILLIS_PER_PUMP) {
                break;
            }
            Int8Array data = pollInbound(socketId);
            if (data == null) {
                break;
            }
            byte[] bytes = data.copyToJavaArray();
            if (bytes.length > 0) {
                pipeline.fireChannelRead(Unpooled.wrappedBuffer(bytes));
                chunks++;
                bytesPumped += bytes.length;
            }
        }
        if (chunks > 0) {
            pipeline.fireChannelReadComplete();
            recordPump(
                    socketId,
                    chunks,
                    bytesPumped,
                    Math.max(0.0, monotonicMillis() - pumpStarted));
        }

        error = pollError(socketId);
        if (error != null) {
            pipeline.fireExceptionCaught(new ChannelException(error));
            close();
        } else if (isSocketClosed(socketId)) {
            close();
        }
    }

    private static Int8Array copyBytes(ByteBuf buffer) {
        ByteBuf copy = buffer.duplicate();
        byte[] bytes = new byte[copy.readableBytes()];
        copy.getBytes(copy.readerIndex(), bytes);
        return Int8Array.fromJavaArray(bytes);
    }

    private static String host(SocketAddress remote) {
        if (remote instanceof InetSocketAddress address) {
            String host = address.getHostString();
            if (host != null && !host.isEmpty()) {
                return host;
            }
            if (address.getAddress() != null) {
                return address.getAddress().getHostAddress();
            }
        }
        throw new ChannelException("Unsupported browser remote address: " + remote);
    }

    private static int port(SocketAddress remote) {
        if (remote instanceof InetSocketAddress address) {
            return address.getPort();
        }
        throw new ChannelException("Unsupported browser remote address: " + remote);
    }

    private static void addChannel(BrowserWebSocketChannel channel) {
        while (true) {
            for (int index = 0; index < channels.length; index++) {
                if (channels[index] == null) {
                    channels[index] = channel;
                    return;
                }
            }
            BrowserWebSocketChannel[] grown =
                    new BrowserWebSocketChannel[channels.length * 2];
            System.arraycopy(channels, 0, grown, 0, channels.length);
            channels = grown;
        }
    }

    private static void removeChannel(BrowserWebSocketChannel channel) {
        for (int index = 0; index < channels.length; index++) {
            if (channels[index] == channel) {
                channels[index] = null;
                return;
            }
        }
    }

    private final class BrowserUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            connectBrowser(remote, local, promise);
        }
    }

    @JSBody(script = """
            return typeof WebSocket !== 'undefined';
            """)
    private static native boolean isWebSocketAvailable();

    @JSBody(script = """
            if (globalThis.__gaiusNettyBridge) return;
            const state = {
              channels: new Map(),
              stats: {
                created: true,
                opened: 0,
                localOpened: 0,
                directAttempts: 0,
                directConnected: 0,
                relayAttempts: 0,
                relayFailovers: 0,
                connected: 0,
                closed: 0,
                sentFrames: 0,
                sentBytes: 0,
                receivedFrames: 0,
                receivedBytes: 0,
                queuedBytes: 0,
                inboundQueuedBytes: 0,
                peakInboundQueuedBytes: 0,
                flowPauses: 0,
                flowResumes: 0,
                localBatches: 0,
                localBatchBytes: 0,
                peakLocalBatchBytes: 0,
                pumpCalls: 0,
                pumpChunks: 0,
                pumpBytes: 0,
                deferredPumps: 0,
                peakPumpChunks: 0,
                peakPumpBytes: 0,
                peakPumpMillis: 0,
                errors: 0
              }
            };
            const maximumLocalBatchBytes = 16 * 1024;
            const maximumInboundQueueBytes = 64 * 1024 * 1024;
            const inboundPauseBytes = 24 * 1024 * 1024;
            const inboundResumeBytes = 8 * 1024 * 1024;
            const maximumWebSocketBufferedBytes = 4 * 1024 * 1024;
            const maximumOutboundQueueBytes = 16 * 1024 * 1024;
            function authorityHost(value) {
              const host = String(value || '127.0.0.1');
              return host.includes(':') && !(host.startsWith('[') && host.endsWith(']'))
                ? '[' + host + ']'
                : host;
            }
            function defaultBridgeUrl() {
              const params = new URLSearchParams(location.search || '');
              const configured = params.get('bridge') || globalThis.__gaiusBridgeUrl;
              if (configured && String(configured).trim()) return String(configured).trim();
              const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
              const host = authorityHost(
                globalThis.__gaiusBridgeHost || location.hostname || '127.0.0.1'
              );
              const port = globalThis.__gaiusBridgePort || '8080';
              return scheme + '://' + host + ':' + port + '/tunnel';
            }
            function bridgeUrls() {
              const params = new URLSearchParams(location.search || '');
              const candidates = params.getAll('bridge');
              const configured = globalThis.__gaiusBridgeUrls;
              if (Array.isArray(configured)) {
                for (let index = 0; index < configured.length; index++) {
                  candidates.push(configured[index]);
                }
              } else if (configured) {
                candidates.push(configured);
              }
              try {
                const saved = localStorage.getItem('gaius.bridgeNodes');
                const values = saved ? JSON.parse(saved) : [];
                if (Array.isArray(values)) {
                  for (let index = 0; index < values.length; index++) {
                    candidates.push(values[index]);
                  }
                }
              } catch (ignored) {}
              candidates.push(defaultBridgeUrl());
              const unique = [];
              const seen = new Set();
              for (let index = 0; index < candidates.length; index++) {
                const value = String(candidates[index] || '').trim();
                if (!value || seen.has(value)) continue;
                seen.add(value);
                unique.push({url: value, direct: false});
              }
              return unique;
            }
            function directPluginUrl(host) {
              const params = new URLSearchParams(location.search || '');
              if (params.get('directPlugin') === '0' ||
                  globalThis.__gaiusDirectPlugin === false) {
                return null;
              }
              const requestedPort = Number(
                params.get('directPluginPort') || globalThis.__gaiusDirectPluginPort || 8081
              );
              const port = Number.isFinite(requestedPort) && requestedPort >= 1 &&
                requestedPort <= 65535 ? Math.floor(requestedPort) : 8081;
              const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
              return scheme + '://' + authorityHost(host) + ':' + port + '/tunnel';
            }
            function bridgeToken() {
              const params = new URLSearchParams(location.search || '');
              const token = params.get('bridgeToken') || globalThis.__gaiusBridgeToken;
              return token && String(token).length ? String(token) : undefined;
            }
            function localSession(host) {
              const match = /^(?:client|server)-([a-f0-9]{32})\\.gaius-local$/.exec(host);
              return match ? match[1] : null;
            }
            function takeLocalPort(sessionId) {
              const ports = globalThis.__gaiusLocalServerPorts;
              if (!ports) return null;
              if (typeof ports.get === 'function') {
                const mappedPort = ports.get(sessionId) || null;
                if (mappedPort) ports.delete(sessionId);
                return mappedPort;
              }
              const objectPort = ports[sessionId] || null;
              if (objectPort) delete ports[sessionId];
              return objectPort;
            }
            function fail(entry, message) {
              entry.errors.push(String(message || 'Browser bridge error'));
              state.stats.errors++;
              try { if (entry.ws) entry.ws.close(); } catch (ignored) {}
              try {
                if (entry.localPort) {
                  entry.localPort.postMessage({type: 'close'});
                  entry.localPort.close();
                }
              } catch (ignored) {}
              entry.closed = true;
            }
            function sendControl(entry, message) {
              if (entry.localPort) {
                entry.localPort.postMessage(message);
              } else if (entry.ws && entry.ws.readyState === WebSocket.OPEN) {
                entry.ws.send(JSON.stringify(message));
              }
            }
            function setInboundPaused(entry, paused) {
              if (entry.flowPaused === paused || !entry.connected) return;
              try {
                sendControl(entry, {type: 'flow', paused: !!paused});
                entry.flowPaused = paused;
                if (paused) state.stats.flowPauses++;
                else state.stats.flowResumes++;
              } catch (error) {
                fail(entry, error && (error.message || error));
              }
            }
            function deliverInbound(entry, buffer) {
              if (entry.closed) return;
              const source = buffer instanceof ArrayBuffer
                ? new Uint8Array(buffer)
                : new Uint8Array(buffer.buffer, buffer.byteOffset || 0, buffer.byteLength || 0);
              let copy = source;
              if (!entry.localPort || !(buffer instanceof ArrayBuffer)) {
                copy = new Uint8Array(source.byteLength);
                copy.set(source);
              }
              if (entry.inboundBytes + copy.byteLength > maximumInboundQueueBytes) {
                fail(entry, 'Browser transport inbound queue exceeded 64 MiB');
                return;
              }
              entry.inbound.push(copy);
              entry.inboundBytes += copy.byteLength;
              state.stats.inboundQueuedBytes += copy.byteLength;
              state.stats.peakInboundQueuedBytes = Math.max(
                state.stats.peakInboundQueuedBytes,
                state.stats.inboundQueuedBytes
              );
              state.stats.receivedFrames++;
              state.stats.receivedBytes += copy.byteLength;
              if (entry.inboundBytes >= inboundPauseBytes) {
                setInboundPaused(entry, true);
              }
            }
            function requestFlush(entry) {
              if (!entry.localPort) {
                flush(entry);
                return;
              }
              if (entry.outboundHead >= entry.outbound.length) return;
              if (entry.localFlushScheduled || entry.closed || entry.remotePaused) return;
              entry.localFlushScheduled = true;
              const run = function() {
                entry.localFlushScheduled = false;
                flush(entry);
              };
              if (typeof queueMicrotask === 'function') queueMicrotask(run);
              else Promise.resolve().then(run);
            }
            function flush(entry) {
              if (!entry.connected || entry.remotePaused) return;
              if (!entry.localPort && (!entry.ws || entry.ws.readyState !== WebSocket.OPEN)) return;
              if (entry.localPort) {
                if (entry.outboundHead >= entry.outbound.length) return;
                const start = entry.outboundHead;
                let end = start;
                let batchBytes = 0;
                while (end < entry.outbound.length) {
                  const nextBytes = entry.outbound[end].byteLength;
                  if (batchBytes > 0 && batchBytes + nextBytes > maximumLocalBatchBytes) break;
                  batchBytes += nextBytes;
                  end++;
                  if (batchBytes >= maximumLocalBatchBytes) break;
                }
                let batch;
                if (end === start + 1) {
                  batch = entry.outbound[start];
                } else {
                  batch = new Uint8Array(batchBytes);
                  let offset = 0;
                  for (let index = start; index < end; index++) {
                    batch.set(entry.outbound[index], offset);
                    offset += entry.outbound[index].byteLength;
                  }
                }
                entry.outboundHead = end;
                entry.queuedBytes -= batchBytes;
                state.stats.queuedBytes = Math.max(0, state.stats.queuedBytes - batchBytes);
                try {
                  entry.localPort.postMessage(batch.buffer, [batch.buffer]);
                  state.stats.sentFrames++;
                  state.stats.sentBytes += batchBytes;
                  state.stats.localBatches++;
                  state.stats.localBatchBytes += batchBytes;
                  state.stats.peakLocalBatchBytes = Math.max(
                    state.stats.peakLocalBatchBytes,
                    batchBytes
                  );
                } catch (error) {
                  fail(entry, error && (error.message || error));
                  return;
                }
                if (entry.outboundHead >= entry.outbound.length) {
                  entry.outbound = [];
                  entry.outboundHead = 0;
                } else {
                  requestFlush(entry);
                }
                return;
              }
              while (entry.outboundHead < entry.outbound.length) {
                if (entry.ws && entry.ws.bufferedAmount >= maximumWebSocketBufferedBytes) return;
                const bytes = entry.outbound[entry.outboundHead++];
                const byteLength = bytes.byteLength;
                entry.queuedBytes -= byteLength;
                state.stats.queuedBytes = Math.max(0, state.stats.queuedBytes - byteLength);
                try {
                  entry.ws.send(bytes);
                  state.stats.sentFrames++;
                  state.stats.sentBytes += byteLength;
                } catch (error) {
                  fail(entry, error && (error.message || error));
                  return;
                }
              }
              entry.outbound = [];
              entry.outboundHead = 0;
            }
            function openRemoteCandidate(entry) {
              if (entry.closed || entry.connected) return;
              if (entry.candidateIndex >= entry.candidates.length) {
                fail(entry, 'No Gaius direct endpoint or relay node could reach the server');
                return;
              }
              const candidate = entry.candidates[entry.candidateIndex++];
              if (candidate.direct) state.stats.directAttempts++;
              else state.stats.relayAttempts++;
              const generation = ++entry.webSocketGeneration;
              let ws;
              try {
                ws = new WebSocket(candidate.url);
              } catch (error) {
                if (!candidate.direct) state.stats.relayFailovers++;
                openRemoteCandidate(entry);
                return;
              }
              entry.ws = ws;
              entry.currentCandidate = candidate;
              ws.binaryType = 'arraybuffer';
              const candidateTimeout = setTimeout(function() {
                if (entry.closed || entry.connected || generation !== entry.webSocketGeneration) {
                  return;
                }
                try { ws.close(); } catch (ignored) {}
                if (!candidate.direct) state.stats.relayFailovers++;
                openRemoteCandidate(entry);
              }, candidate.direct ? 800 : 8000);
              ws.onopen = function() {
                if (generation !== entry.webSocketGeneration || entry.closed) return;
                const control = {type: 'connect', host: entry.host, port: entry.port};
                const token = bridgeToken();
                if (token !== undefined) control.token = token;
                try {
                  ws.send(JSON.stringify(control));
                } catch (error) {
                  try { ws.close(); } catch (ignored) {}
                }
              };
              ws.onmessage = function(event) {
                if (generation !== entry.webSocketGeneration || entry.closed) return;
                if (typeof event.data === 'string') {
                  try {
                    const message = JSON.parse(event.data);
                    if (message && message.type === 'connected') {
                      clearTimeout(candidateTimeout);
                      entry.connected = true;
                      if (candidate.direct) state.stats.directConnected++;
                      state.stats.connected++;
                      requestFlush(entry);
                    }
                  } catch (ignored) {}
                  return;
                }
                if (event.data instanceof ArrayBuffer) {
                  deliverInbound(entry, event.data);
                } else if (event.data && typeof event.data.arrayBuffer === 'function') {
                  event.data.arrayBuffer().then(function(buffer) {
                    deliverInbound(entry, buffer);
                  }, function(error) {
                    fail(entry, error && (error.message || error));
                  });
                }
              };
              ws.onerror = function() {
                if (entry.connected && generation === entry.webSocketGeneration) {
                  fail(entry, 'WebSocket transport error');
                }
              };
              ws.onclose = function(event) {
                clearTimeout(candidateTimeout);
                if (generation !== entry.webSocketGeneration || entry.closed) return;
                if (!entry.connected) {
                  if (!candidate.direct) state.stats.relayFailovers++;
                  openRemoteCandidate(entry);
                  return;
                }
                entry.closed = true;
                state.stats.closed++;
                if (event && event.code && event.code !== 1000 && entry.errors.length === 0) {
                  entry.errors.push(
                    'WebSocket transport closed: ' + event.code + ' ' + (event.reason || '')
                  );
                  state.stats.errors++;
                }
              };
            }
            state.open = function(id, host, port) {
              const key = id|0;
              const existing = state.channels.get(key);
              if (existing) {
                try { if (existing.ws) existing.ws.close(); } catch (ignored) {}
              }
              const entry = {
                id: key,
                host: String(host),
                port: port|0,
                ws: null,
                localPort: null,
                connected: false,
                remotePaused: false,
                inbound: [],
                inboundHead: 0,
                inboundBytes: 0,
                outbound: [],
                outboundHead: 0,
                errors: [],
                closed: false,
                flowPaused: false,
                localFlushScheduled: false,
                queuedBytes: 0,
                candidates: [],
                candidateIndex: 0,
                currentCandidate: null,
                webSocketGeneration: 0
              };
              state.channels.set(key, entry);
              state.stats.opened++;
              const sessionId = localSession(entry.host);
              if (sessionId !== null) {
                const localPort = takeLocalPort(sessionId);
                if (!localPort) {
                  fail(entry, 'Local server MessagePort is unavailable for ' + sessionId);
                  return;
                }
                entry.localPort = localPort;
                entry.connected = true;
                const workers = globalThis.__gaiusSingleplayerWorkers;
                const localWorker = workers && typeof workers.get === 'function'
                  ? workers.get(sessionId)
                  : null;
                if (localWorker) {
                  localWorker.__gaiusClientAttached = true;
                  localWorker.__gaiusHandoffPending = false;
                  if (localWorker.__gaiusHandoffTimeout) {
                    clearTimeout(localWorker.__gaiusHandoffTimeout);
                    localWorker.__gaiusHandoffTimeout = 0;
                  }
                  const events = globalThis.__gaiusMinecraftEvents ||
                    (globalThis.__gaiusMinecraftEvents = []);
                  events.push({
                    event: 'singleplayer:client-attached',
                    detail: sessionId,
                    at: Date.now()
                  });
                  if (events.length > 500) events.splice(0, events.length - 500);
                }
                state.stats.localOpened++;
                state.stats.connected++;
                localPort.onmessage = function(event) {
                  const message = event.data;
                  if (message && typeof message === 'object' && !(message instanceof ArrayBuffer) &&
                      !ArrayBuffer.isView(message)) {
                    if (message.type === 'flow' && typeof message.paused === 'boolean') {
                      entry.remotePaused = message.paused;
                      if (!entry.remotePaused) requestFlush(entry);
                    } else if (message.type === 'close') {
                      entry.closed = true;
                    }
                    return;
                  }
                  if (message instanceof ArrayBuffer || ArrayBuffer.isView(message)) {
                    deliverInbound(entry, message);
                  }
                };
                localPort.onmessageerror = function() {
                  fail(entry, 'Local server MessagePort decode failed');
                };
                if (typeof localPort.start === 'function') localPort.start();
                requestFlush(entry);
                return;
              }
              const directUrl = directPluginUrl(entry.host);
              if (directUrl) entry.candidates.push({url: directUrl, direct: true});
              entry.candidates.push.apply(entry.candidates, bridgeUrls());
              openRemoteCandidate(entry);
            };
            state.send = function(id, data) {
              const entry = state.channels.get(id|0);
              if (!entry || entry.closed) return false;
              const source = data
                ? new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength || 0)
                : new Uint8Array(0);
              const copy = new Uint8Array(source.byteLength);
              copy.set(source);
              entry.outbound.push(copy);
              entry.queuedBytes += copy.byteLength;
              state.stats.queuedBytes += copy.byteLength;
              if (entry.queuedBytes > maximumOutboundQueueBytes) {
                fail(entry, 'Browser bridge outbound queue exceeded 16 MiB');
                return false;
              }
              requestFlush(entry);
              return !entry.closed;
            };
            state.pollInbound = function(id) {
              const entry = state.channels.get(id|0);
              if (!entry) return null;
              requestFlush(entry);
              if (entry.inboundHead >= entry.inbound.length) return null;
              const chunk = entry.inbound[entry.inboundHead++];
              entry.inboundBytes = Math.max(0, entry.inboundBytes - chunk.byteLength);
              state.stats.inboundQueuedBytes = Math.max(
                0,
                state.stats.inboundQueuedBytes - chunk.byteLength
              );
              if (entry.inboundBytes <= inboundResumeBytes) {
                setInboundPaused(entry, false);
              }
              if (entry.inboundHead >= entry.inbound.length) {
                entry.inbound = [];
                entry.inboundHead = 0;
              } else if (entry.inboundHead >= 1024 &&
                         entry.inboundHead * 2 >= entry.inbound.length) {
                entry.inbound = entry.inbound.slice(entry.inboundHead);
                entry.inboundHead = 0;
              }
              return new Int8Array(chunk.buffer, chunk.byteOffset || 0, chunk.byteLength);
            };
            state.pollError = function(id) {
              const entry = state.channels.get(id|0);
              if (!entry || entry.errors.length === 0) return null;
              return String(entry.errors.shift());
            };
            state.recordPump = function(id, chunks, bytes, millis) {
              const entry = state.channels.get(id|0);
              state.stats.pumpCalls++;
              state.stats.pumpChunks += chunks|0;
              state.stats.pumpBytes += bytes|0;
              state.stats.peakPumpChunks = Math.max(state.stats.peakPumpChunks, chunks|0);
              state.stats.peakPumpBytes = Math.max(state.stats.peakPumpBytes, bytes|0);
              state.stats.peakPumpMillis = Math.max(state.stats.peakPumpMillis, +millis || 0);
              if (entry && entry.inboundBytes > 0) state.stats.deferredPumps++;
            };
            state.close = function(id) {
              const entry = state.channels.get(id|0);
              if (!entry) return;
              entry.closed = true;
              state.stats.queuedBytes = Math.max(
                0,
                state.stats.queuedBytes - entry.queuedBytes
              );
              state.stats.inboundQueuedBytes = Math.max(
                0,
                state.stats.inboundQueuedBytes - entry.inboundBytes
              );
              entry.queuedBytes = 0;
              entry.inboundBytes = 0;
              try { if (entry.ws) entry.ws.close(); } catch (ignored) {}
              try {
                if (entry.localPort) {
                  entry.localPort.postMessage({type: 'close'});
                  entry.localPort.close();
                }
              } catch (ignored) {}
              state.channels.delete(id|0);
            };
            state.closed = function(id) {
              const entry = state.channels.get(id|0);
              return !entry || !!entry.closed;
            };
            state.failLocalSession = function(sessionId, message) {
              sessionId = String(sessionId || '');
              state.channels.forEach(function(entry) {
                if (localSession(entry.host) === sessionId) {
                  fail(entry, message || 'Local server Worker stopped unexpectedly');
                }
              });
            };
            globalThis.__gaiusNettyBridge = state;
            globalThis.__gaiusNetworkStats = state.stats;
            """)
    private static native void initBridge();

    @JSBody(params = {"id", "host", "port"}, script = """
            globalThis.__gaiusNettyBridge.open(id, host, port);
            """)
    private static native void openSocket(int id, String host, int port);

    @JSBody(params = {"id", "data"}, script = """
            return !!globalThis.__gaiusNettyBridge.send(id, data);
            """)
    private static native boolean sendSocket(int id, Int8Array data);

    @JSBody(params = {"id"}, script = """
            return globalThis.__gaiusNettyBridge.pollInbound(id);
            """)
    private static native Int8Array pollInbound(int id);

    @JSBody(params = {"id"}, script = """
            return globalThis.__gaiusNettyBridge.pollError(id);
            """)
    private static native String pollError(int id);

    @JSBody(params = {"id"}, script = """
            globalThis.__gaiusNettyBridge.close(id);
            """)
    private static native void closeSocket(int id);

    @JSBody(params = {"id"}, script = """
            return !!globalThis.__gaiusNettyBridge.closed(id);
            """)
    private static native boolean isSocketClosed(int id);

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double monotonicMillis();

    @JSBody(params = {"id", "chunks", "bytes", "millis"}, script = """
            globalThis.__gaiusNettyBridge.recordPump(id, chunks, bytes, millis);
            """)
    private static native void recordPump(int id, int chunks, int bytes, double millis);
}
