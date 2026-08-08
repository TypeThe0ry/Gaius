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
    // Client packets run inline on the browser event loop. Drain cheap frames in a batch while
    // the time budget still makes a heavy chunk/model update yield after its first frame.
    private static final int MAX_CHUNKS_PER_PUMP = 64;
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
    private boolean pumping;
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

    /** Returns whether a browser transport has data waiting for the Java pipeline. */
    public static boolean hasPendingInput() {
        for (BrowserWebSocketChannel channel : channels) {
            if (channel != null && channel.open && hasPendingInbound(channel.socketId)) {
                return true;
            }
        }
        return false;
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
        if (!open || pumping) {
            return;
        }
        pumping = true;
        try {
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
            // WebSocket close is allowed to race with its final binary messages. Keep draining
            // those packets so a server's login/respawn tail is not discarded before the client
            // can emit the protocol acknowledgements it requires.
            } else if (isSocketClosed(socketId) && !hasPendingInbound(socketId)) {
                close();
            }
        } finally {
            pumping = false;
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
                return normalizeRemoteHost(host, address.getPort());
            }
            if (address.getAddress() != null) {
                return address.getAddress().getHostAddress();
            }
        }
        throw new ChannelException("Unsupported browser remote address: " + remote);
    }

    /**
     * Minecraft's browser-side address parsing can retain the typed port in an unresolved
     * InetSocketAddress host string. RelayNode receives the port separately, so remove only a
     * matching suffix and preserve ordinary IPv6 literals.
     */
    private static String normalizeRemoteHost(String host, int port) {
        String value = host.trim();
        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket > 1) {
                String remainder = value.substring(closingBracket + 1);
                if (remainder.isEmpty() || remainder.equals(":" + port)) {
                    return value.substring(1, closingBracket);
                }
            }
            return value;
        }
        int firstColon = value.indexOf(':');
        if (firstColon > 0 && firstColon == value.lastIndexOf(':')
                && value.substring(firstColon + 1).equals(Integer.toString(port))) {
            return value.substring(0, firstColon);
        }
        return value;
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
              directPluginMisses: new Map(),
              relayPreflightCache: new Map(),
              targetRelayLeases: new Map(),
              relayRegistryCache: new Map(),
              relayRegistryPromise: null,
              stats: {
                created: true,
                opened: 0,
                localOpened: 0,
                directAttempts: 0,
                directConnected: 0,
                directPluginCachedMisses: 0,
                relayAttempts: 0,
                relayFailovers: 0,
                relayPreflights: 0,
                relayPreflightSuccesses: 0,
                relayPreflightFailures: 0,
                relayPreflightCacheHits: 0,
                relayRegistryRequests: 0,
                relayRegistrySuccesses: 0,
                relayRegistryFailures: 0,
                relayRegistryCacheHits: 0,
                relayRegistryNodesLoaded: 0,
                relayRegistryRegistriesLoaded: 0,
                relayTargetActiveSelections: 0,
                relayTargetRecentSelections: 0,
                relayTargetLocalActiveSelections: 0,
                relayTargetLocalRecentSelections: 0,
                relayTargetLeaseAcquires: 0,
                relayTargetLeaseReleases: 0,
                activeRelayTargetLeases: 0,
                peakRelayTargetLeases: 0,
                relayNodeSuccesses: 0,
                relayNodeFailures: 0,
                relayNodes: Object.create(null),
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
                localFlushes: 0,
                localFlushFrames: 0,
                localFlushBytes: 0,
                localReceivedFrames: 0,
                localReceivedBytes: 0,
                peakLocalFlushFrames: 0,
                peakLocalFlushBytes: 0,
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
            const maximumInboundQueueBytes = 64 * 1024 * 1024;
            const inboundPauseBytes = 24 * 1024 * 1024;
            const inboundResumeBytes = 8 * 1024 * 1024;
            const maximumWebSocketBufferedBytes = 4 * 1024 * 1024;
            const maximumOutboundQueueBytes = 16 * 1024 * 1024;
            const relayPreflightTimeoutMs = 900;
            const relayRegistryTimeoutMs = 1500;
            const relayRegistryCacheTtlMs = 5 * 60 * 1000;
            const maximumRelayRegistryNodes = 64;
            const maximumRelayRegistryUrls = 32;
            const maximumNestedRegistriesPerResponse = 16;
            const defaultRelayRegistryUrl =
              'https://raw.githubusercontent.com/TypeThe0ry/Gaius/main/relay-nodes.json';
            // Server-list status and the subsequent join are separate Netty channels. Cache only
            // discovery metadata for a few seconds; every channel still gets its own tunnel.
            const directPluginMissTtlMs = 30 * 1000;
            const relayPreflightCacheTtlMs = 15 * 1000;
            const relayPreflightFailureCacheTtlMs = 5 * 1000;
            const defaultTargetAffinityMs = 5 * 60 * 1000;
            const maximumDiscoveryCacheEntries = 1024;
            // MessagePort has TCP-stream semantics here, so adjacent Netty writes can share one
            // transferable buffer. Bound batches to retain low input latency and fair delivery.
            const maximumLocalBatchBytes = 16 * 1024;
            function authorityHost(value) {
              const host = String(value || '127.0.0.1');
              return host.includes(':') && !(host.startsWith('[') && host.endsWith(']'))
                ? '[' + host + ']'
                : host;
            }
            function normalizedTargetKey(host, port) {
              let value = String(host || '').trim().toLowerCase();
              if (value.startsWith('[') && value.endsWith(']')) {
                value = value.slice(1, -1);
              }
              while (value.endsWith('.') && value.length > 1) {
                value = value.slice(0, -1);
              }
              return authorityHost(value) + ':' + String(port|0);
            }
            function pruneDiscoveryCache(cache, now, ttl) {
              const expired = [];
              cache.forEach(function(value, key) {
                const recordedAt = Number(value && value.recordedAt || value || 0);
                if (!Number.isFinite(recordedAt) || now - recordedAt > ttl) {
                  expired.push(key);
                }
              });
              for (let index = 0; index < expired.length; index++) {
                cache.delete(expired[index]);
              }
              while (cache.size >= maximumDiscoveryCacheEntries) {
                const oldestKey = cache.keys().next().value;
                if (oldestKey === undefined) break;
                cache.delete(oldestKey);
              }
            }
            function targetRelayLeaseKey(entry, candidate) {
              return entry.targetKey + '\\n' + candidate.url;
            }
            function targetAffinityTtl(candidate) {
              const configured = Number(candidate && candidate.targetAffinityMs);
              return Number.isFinite(configured)
                ? Math.max(1000, Math.min(60 * 60 * 1000, configured))
                : defaultTargetAffinityMs;
            }
            function pruneTargetRelayLeases(now) {
              const expired = [];
              state.targetRelayLeases.forEach(function(record, key) {
                if (record.activeTunnels <= 0 && record.expiresAt <= now) expired.push(key);
              });
              for (let index = 0; index < expired.length; index++) {
                state.targetRelayLeases.delete(expired[index]);
              }
              while (state.targetRelayLeases.size >= maximumDiscoveryCacheEntries) {
                let removable;
                state.targetRelayLeases.forEach(function(record, key) {
                  if (removable === undefined && record.activeTunnels <= 0) removable = key;
                });
                if (removable === undefined) break;
                state.targetRelayLeases.delete(removable);
              }
            }
            function localTargetRelayAffinity(entry, candidate) {
              const now = Date.now();
              pruneTargetRelayLeases(now);
              const record = state.targetRelayLeases.get(
                targetRelayLeaseKey(entry, candidate)
              );
              if (!record) return null;
              if (record.activeTunnels <= 0 && record.expiresAt <= now) return null;
              return record;
            }
            function acquireTargetRelayLease(entry, candidate) {
              if (!candidate || candidate.direct || entry.relayTargetLeaseKey) return;
              const now = Date.now();
              pruneTargetRelayLeases(now);
              const key = targetRelayLeaseKey(entry, candidate);
              let record = state.targetRelayLeases.get(key);
              if (!record) {
                record = {
                  targetKey: entry.targetKey,
                  url: candidate.url,
                  activeTunnels: 0,
                  lastSuccessAt: 0,
                  lastReleasedAt: 0,
                  ttlMs: defaultTargetAffinityMs,
                  expiresAt: 0
                };
              }
              record.ttlMs = targetAffinityTtl(candidate);
              record.activeTunnels++;
              record.lastSuccessAt = now;
              record.expiresAt = now + record.ttlMs;
              state.targetRelayLeases.delete(key);
              state.targetRelayLeases.set(key, record);
              entry.relayTargetLeaseKey = key;
              state.stats.relayTargetLeaseAcquires++;
              state.stats.activeRelayTargetLeases++;
              state.stats.peakRelayTargetLeases = Math.max(
                state.stats.peakRelayTargetLeases,
                state.stats.activeRelayTargetLeases
              );
            }
            function releaseTargetRelayLease(entry) {
              const key = entry && entry.relayTargetLeaseKey;
              if (!key) return;
              entry.relayTargetLeaseKey = null;
              const record = state.targetRelayLeases.get(key);
              if (!record) return;
              const now = Date.now();
              record.activeTunnels = Math.max(0, record.activeTunnels - 1);
              record.lastReleasedAt = now;
              record.expiresAt = Math.max(record.expiresAt, now + record.ttlMs);
              state.targetRelayLeases.delete(key);
              state.targetRelayLeases.set(key, record);
              state.stats.relayTargetLeaseReleases++;
              state.stats.activeRelayTargetLeases = Math.max(
                0,
                state.stats.activeRelayTargetLeases - 1
              );
              pruneTargetRelayLeases(now);
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
            function normalizeRelayUrl(value) {
              try {
                const parsed = new URL(String(value || '').trim(), location.href);
                if (parsed.protocol === 'http:') parsed.protocol = 'ws:';
                if (parsed.protocol === 'https:') parsed.protocol = 'wss:';
                if (parsed.protocol !== 'ws:' && parsed.protocol !== 'wss:') return null;
                if (parsed.pathname === '' || parsed.pathname === '/' ||
                    parsed.pathname === '/health' || parsed.pathname === '/relay-node/v1') {
                  parsed.pathname = '/tunnel';
                }
                parsed.hash = '';
                parsed.search = '';
                return parsed.href;
              } catch (ignored) {
                return null;
              }
            }
            function relayNodeCandidate(value) {
              const object = value && typeof value === 'object' && !Array.isArray(value)
                ? value
                : null;
              if (object && object.enabled === false) return null;
              const url = normalizeRelayUrl(object ? (object.url || object.endpoint) : value);
              if (!url) return null;
              const rawPriority = object ? Number(object.priority) : 0;
              const priority = Number.isFinite(rawPriority)
                ? Math.max(-10000, Math.min(10000, Math.floor(rawPriority)))
                : 0;
              const rawToken = object && object.token;
              const token = typeof rawToken === 'string' && rawToken.length > 0
                ? rawToken
                : undefined;
              const rawName = object && object.name;
              const name = typeof rawName === 'string' && rawName.trim()
                ? rawName.trim().slice(0, 80)
                : url;
              return {url: url, token: token, priority: priority, name: name, direct: false};
            }
            function normalizeRelayRegistryUrl(value) {
              try {
                const parsed = new URL(String(value || '').trim(), location.href);
                if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
                if (location.protocol === 'https:' && parsed.protocol !== 'https:') return null;
                parsed.hash = '';
                return parsed.href;
              } catch (ignored) {
                return null;
              }
            }
            function relayRegistryUrls() {
              const params = new URLSearchParams(location.search || '');
              const requested = params.getAll('relayRegistry');
              const values = [];
              let disableDefaults = globalThis.__gaiusDefaultRelayRegistries === false;
              for (let index = 0; index < requested.length; index++) {
                if (requested[index] === '0') disableDefaults = true;
                else values.push(requested[index]);
              }
              const configured = globalThis.__gaiusRelayRegistryUrls;
              if (Array.isArray(configured)) {
                for (let index = 0; index < configured.length; index++) {
                  values.push(configured[index]);
                }
              } else if (configured) {
                values.push(configured);
              }
              try {
                const saved = localStorage.getItem('gaius.relayRegistries');
                const registries = saved ? JSON.parse(saved) : [];
                if (Array.isArray(registries)) {
                  for (let index = 0; index < registries.length; index++) {
                    values.push(registries[index]);
                  }
                }
              } catch (ignored) {}
              if (!disableDefaults) {
                if (location.protocol === 'http:' || location.protocol === 'https:') {
                  values.push(new URL('relay-nodes.json', location.href).href);
                }
                values.push(defaultRelayRegistryUrl);
              }
              const unique = [];
              const seen = new Set();
              for (let index = 0; index < values.length; index++) {
                const url = normalizeRelayRegistryUrl(values[index]);
                if (!url || seen.has(url)) continue;
                seen.add(url);
                unique.push(url);
              }
              return unique;
            }
            function loadRelayRegistry(url) {
              const now = Date.now();
              const cached = state.relayRegistryCache.get(url);
              if (cached && now - cached.recordedAt <= relayRegistryCacheTtlMs) {
                state.stats.relayRegistryCacheHits++;
                return cached.promise;
              }
              if (cached) state.relayRegistryCache.delete(url);
              const record = {recordedAt: now, promise: null};
              state.stats.relayRegistryRequests++;
              const controller = typeof AbortController === 'function'
                ? new AbortController()
                : null;
              const timeout = setTimeout(function() {
                if (controller) controller.abort();
              }, relayRegistryTimeoutMs);
              const options = {
                method: 'GET',
                cache: 'no-store',
                headers: {accept: 'application/json'}
              };
              if (controller) options.signal = controller.signal;
              record.promise = fetch(url, options).then(function(response) {
                if (!response.ok) {
                  throw new Error('Relay registry returned ' + response.status);
                }
                return response.json();
              }).then(function(registry) {
                if (!registry || registry.kind !== 'gaius-relay-registry' ||
                    Number(registry.protocolVersion) !== 1 ||
                    !Array.isArray(registry.nodes)) {
                  throw new Error('Relay registry is incompatible');
                }
                const accepted = [];
                const limit = Math.min(registry.nodes.length, maximumRelayRegistryNodes);
                for (let index = 0; index < limit; index++) {
                  const candidate = relayNodeCandidate(registry.nodes[index]);
                  if (candidate) accepted.push(candidate);
                }
                const nested = [];
                const registries = Array.isArray(registry.registries)
                  ? registry.registries
                  : [];
                const registryLimit = Math.min(
                  registries.length,
                  maximumNestedRegistriesPerResponse
                );
                for (let index = 0; index < registryLimit; index++) {
                  const nestedUrl = normalizeRelayRegistryUrl(registries[index]);
                  if (nestedUrl) nested.push(nestedUrl);
                }
                state.stats.relayRegistrySuccesses++;
                state.stats.relayRegistryNodesLoaded += accepted.length;
                state.stats.relayRegistryRegistriesLoaded += nested.length;
                return {nodes: accepted, registries: nested};
              }).catch(function() {
                state.stats.relayRegistryFailures++;
                return {nodes: [], registries: []};
              }).finally(function() {
                clearTimeout(timeout);
              });
              state.relayRegistryCache.set(url, record);
              return record.promise;
            }
            function discoverRelayNodes() {
              if (typeof fetch !== 'function') return Promise.resolve([]);
              const pending = relayRegistryUrls();
              const requested = new Set();
              const nodes = [];
              const seenNodes = new Set();
              function loadNextBatch() {
                const urls = [];
                while (pending.length > 0 &&
                       requested.size < maximumRelayRegistryUrls) {
                  const url = pending.shift();
                  if (!url || requested.has(url)) continue;
                  requested.add(url);
                  urls.push(url);
                }
                if (urls.length === 0) return Promise.resolve(nodes);
                const requests = new Array(urls.length);
                for (let index = 0; index < urls.length; index++) {
                  requests[index] = loadRelayRegistry(urls[index]);
                }
                return Promise.all(requests).then(function(results) {
                for (let resultIndex = 0; resultIndex < results.length; resultIndex++) {
                  const result = results[resultIndex];
                  for (let nodeIndex = 0; nodeIndex < result.nodes.length; nodeIndex++) {
                    const candidate = result.nodes[nodeIndex];
                    if (!candidate || seenNodes.has(candidate.url)) continue;
                    seenNodes.add(candidate.url);
                    nodes.push(candidate);
                    if (nodes.length >= maximumRelayRegistryNodes) return nodes;
                  }
                  for (let registryIndex = 0;
                       registryIndex < result.registries.length;
                       registryIndex++) {
                    const nestedUrl = result.registries[registryIndex];
                    if (!requested.has(nestedUrl) &&
                        requested.size + pending.length < maximumRelayRegistryUrls) {
                      pending.push(nestedUrl);
                    }
                  }
                }
                return loadNextBatch();
                });
              }
              return loadNextBatch();
            }
            function bridgeUrls(discovered) {
              const params = new URLSearchParams(location.search || '');
              const candidates = params.getAll('bridge');
              const relayAliases = params.getAll('relay');
              for (let index = 0; index < relayAliases.length; index++) {
                candidates.push(relayAliases[index]);
              }
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
              if (Array.isArray(discovered)) {
                for (let index = 0; index < discovered.length; index++) {
                  candidates.push(discovered[index]);
                }
              }
              candidates.push(defaultBridgeUrl());
              const unique = [];
              const seen = new Set();
              for (let index = 0; index < candidates.length; index++) {
                const candidate = relayNodeCandidate(candidates[index]);
                if (!candidate || seen.has(candidate.url)) continue;
                seen.add(candidate.url);
                unique.push(candidate);
              }
              unique.sort(function(left, right) { return right.priority - left.priority; });
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
            function bridgeToken(candidate) {
              if (candidate && typeof candidate.token === 'string' && candidate.token.length > 0) {
                return candidate.token;
              }
              const params = new URLSearchParams(location.search || '');
              const token = params.get('bridgeToken') || params.get('relayToken') ||
                globalThis.__gaiusBridgeToken;
              return token && String(token).length ? String(token) : undefined;
            }
            function relayManifestUrl(candidate, host, port) {
              try {
                const parsed = new URL(candidate.url, location.href);
                parsed.protocol = parsed.protocol === 'wss:' ? 'https:' : 'http:';
                parsed.pathname = '/relay-node/v1';
                parsed.search = '';
                parsed.searchParams.set('host', String(host));
                parsed.searchParams.set('port', String(port));
                parsed.hash = '';
                return parsed.href;
              } catch (ignored) {
                return null;
              }
            }
            function directPluginCacheKey(entry, candidate) {
              return entry.targetKey;
            }
            function directPluginWasRecentlyUnavailable(entry, candidate) {
              const now = Date.now();
              pruneDiscoveryCache(state.directPluginMisses, now, directPluginMissTtlMs);
              const recordedAt = state.directPluginMisses.get(
                directPluginCacheKey(entry, candidate)
              );
              if (!Number.isFinite(recordedAt) || now - recordedAt > directPluginMissTtlMs) {
                return false;
              }
              state.stats.directPluginCachedMisses++;
              return true;
            }
            function rememberDirectPluginUnavailable(entry, candidate) {
              const now = Date.now();
              pruneDiscoveryCache(state.directPluginMisses, now, directPluginMissTtlMs);
              const key = directPluginCacheKey(entry, candidate);
              state.directPluginMisses.delete(key);
              state.directPluginMisses.set(key, now);
            }
            function forgetDirectPluginUnavailable(entry, candidate) {
              state.directPluginMisses.delete(directPluginCacheKey(entry, candidate));
            }
            function relayPreflightCacheKey(entry, candidate) {
              return candidate.url + '\\n' + entry.targetKey;
            }
            function applyRelayPreflight(candidate, values) {
              candidate.preflightOk = true;
              candidate.availableConnections = Number(values.availableConnections);
              candidate.targetConnectTimeoutMs = Number.isFinite(
                Number(values.targetConnectTimeoutMs)
              ) ? Math.max(100, Math.min(60000, Number(values.targetConnectTimeoutMs)))
                : 10000;
              candidate.targetAffinityMs = Number.isFinite(Number(values.targetAffinityMs))
                ? Math.max(1000, Math.min(60 * 60 * 1000, Number(values.targetAffinityMs)))
                : defaultTargetAffinityMs;
              candidate.targetActiveConnections = Number.isFinite(
                Number(values.targetActiveConnections)
              ) ? Math.max(0, Number(values.targetActiveConnections)) : 0;
              candidate.targetRecentlyReachable = !!values.targetRecentlyReachable;
              const record = relayNodeRecord(candidate);
              if (record) {
                record.lastPreflightAt = Date.now();
                record.availableConnections = candidate.availableConnections;
                record.targetConnectTimeoutMs = candidate.targetConnectTimeoutMs;
                record.targetAffinityMs = candidate.targetAffinityMs;
                record.targetActiveConnections = candidate.targetActiveConnections;
                record.targetRecentlyReachable = candidate.targetRecentlyReachable;
                record.lastPreflightError = null;
              }
            }
            function relayCandidateScore(entry, candidate) {
              let score = candidate.priority * 1000;
              const localAffinity = localTargetRelayAffinity(entry, candidate);
              if (candidate.targetActiveConnections > 0 ||
                  (localAffinity && localAffinity.activeTunnels > 0)) {
                score += 1000000000;
              } else if (candidate.targetRecentlyReachable || localAffinity) {
                score += 500000000;
              }
              if (candidate.preflightOk) score += 100000;
              if (Number.isFinite(candidate.availableConnections)) {
                score += Math.max(-100000, Math.min(10000, candidate.availableConnections));
                if (candidate.availableConnections <= 0) score -= 250000000;
              }
              return score;
            }
            function rankRemainingRelayCandidates(entry) {
              const start = entry.candidateIndex;
              const remaining = entry.candidates.slice(start);
              remaining.sort(function(left, right) {
                const score = relayCandidateScore(entry, right) -
                  relayCandidateScore(entry, left);
                if (score !== 0) return score;
                if (right.priority !== left.priority) return right.priority - left.priority;
                return left.url < right.url ? -1 : left.url > right.url ? 1 : 0;
              });
              for (let index = 0; index < remaining.length; index++) {
                entry.candidates[start + index] = remaining[index];
              }
            }
            function probeRelayCandidate(entry, candidate) {
              const now = Date.now();
              pruneDiscoveryCache(
                state.relayPreflightCache,
                now,
                relayPreflightCacheTtlMs
              );
              const cacheKey = relayPreflightCacheKey(entry, candidate);
              const cached = state.relayPreflightCache.get(cacheKey);
              const cachedTtl = cached && cached.failed
                ? relayPreflightFailureCacheTtlMs
                : relayPreflightCacheTtlMs;
              if (cached && now - cached.recordedAt <= cachedTtl) {
                if (cached.failed) candidate.preflightOk = false;
                else applyRelayPreflight(candidate, cached);
                state.stats.relayPreflightCacheHits++;
                return Promise.resolve();
              }
              if (cached) state.relayPreflightCache.delete(cacheKey);
              const url = relayManifestUrl(candidate, entry.host, entry.port);
              if (!url || typeof fetch !== 'function') return Promise.resolve();
              state.stats.relayPreflights++;
              const controller = typeof AbortController === 'function'
                ? new AbortController()
                : null;
              const timeout = setTimeout(function() {
                if (controller) controller.abort();
              }, relayPreflightTimeoutMs);
              const headers = {};
              const token = bridgeToken(candidate);
              if (token !== undefined) headers.authorization = 'Bearer ' + token;
              const options = {method: 'GET', cache: 'no-store', headers: headers};
              if (controller) options.signal = controller.signal;
              return fetch(url, options).then(function(response) {
                if (!response.ok) throw new Error('RelayNode manifest returned ' + response.status);
                return response.json();
              }).then(function(manifest) {
                if (!manifest || manifest.kind !== 'gaius-relay-node' ||
                    Number(manifest.protocolVersion) !== 1) {
                  throw new Error('RelayNode manifest is incompatible');
                }
                const target = manifest.target;
                const values = {
                  recordedAt: Date.now(),
                  availableConnections: Number(manifest.availableConnections),
                  targetConnectTimeoutMs: Number(manifest.targetConnectTimeoutMs),
                  targetAffinityMs: Number(manifest.targetAffinityMs),
                  targetActiveConnections: target &&
                    Number.isFinite(Number(target.activeConnections))
                    ? Math.max(0, Number(target.activeConnections))
                    : 0,
                  targetRecentlyReachable: !!(target && target.recentlyReachable)
                };
                applyRelayPreflight(candidate, values);
                state.relayPreflightCache.delete(cacheKey);
                state.relayPreflightCache.set(cacheKey, values);
                state.stats.relayPreflightSuccesses++;
              }).catch(function(error) {
                candidate.preflightOk = false;
                const message = String(
                  error && (error.message || error) || 'RelayNode preflight failed'
                ).slice(0, 240);
                const record = relayNodeRecord(candidate);
                if (record) {
                  record.lastPreflightAt = Date.now();
                  record.lastPreflightError = message;
                }
                state.relayPreflightCache.delete(cacheKey);
                state.relayPreflightCache.set(cacheKey, {
                  recordedAt: Date.now(),
                  failed: true,
                  error: message
                });
                state.stats.relayPreflightFailures++;
              }).finally(function() {
                clearTimeout(timeout);
              });
            }
            function prepareRelayCandidates(entry) {
              entry.relayPreflightReady = false;
              entry.relayPreflightWaitStarted = false;
              const registryPromise = state.relayRegistryPromise || discoverRelayNodes();
              state.relayRegistryPromise = registryPromise;
              entry.relayPreflightPromise = registryPromise.then(function(discovered) {
                const candidates = bridgeUrls(discovered);
                entry.candidates.push.apply(entry.candidates, candidates);
                if (candidates.length === 0 || typeof fetch !== 'function') return undefined;
                const probes = new Array(candidates.length);
                for (let index = 0; index < candidates.length; index++) {
                  probes[index] = probeRelayCandidate(entry, candidates[index]);
                }
                return Promise.all(probes);
              }).then(function() {
                entry.relayPreflightReady = true;
                rankRemainingRelayCandidates(entry);
              });
            }
            function ensureRelayCandidates(entry) {
              if (entry.relayPreparationStarted) return;
              entry.relayPreparationStarted = true;
              prepareRelayCandidates(entry);
            }
            function relayNodeRecord(candidate) {
              if (!candidate || candidate.direct) return null;
              const nodes = state.stats.relayNodes;
              let record = nodes[candidate.url];
              if (!record) {
                record = {
                  name: candidate.name,
                  priority: candidate.priority,
                  attempts: 0,
                  successes: 0,
                  failures: 0,
                  lastAttemptAt: 0,
                  lastSuccessAt: 0,
                  lastFailureAt: 0,
                  lastError: null,
                  lastPreflightAt: 0,
                  lastPreflightError: null,
                  availableConnections: null,
                  targetConnectTimeoutMs: null,
                  targetAffinityMs: null,
                  targetActiveConnections: 0,
                  targetRecentlyReachable: false
                };
                nodes[candidate.url] = record;
              }
              return record;
            }
            function recordRelayNodeAttempt(candidate) {
              const record = relayNodeRecord(candidate);
              if (!record) return;
              record.attempts++;
              record.lastAttemptAt = Date.now();
            }
            function recordRelayNodeSuccess(entry, candidate) {
              const record = relayNodeRecord(candidate);
              if (!record) return;
              record.successes++;
              record.lastSuccessAt = Date.now();
              record.lastError = null;
              state.stats.relayNodeSuccesses++;
              acquireTargetRelayLease(entry, candidate);
            }
            function recordRelayNodeFailure(candidate, message) {
              const record = relayNodeRecord(candidate);
              if (!record) return;
              record.failures++;
              record.lastFailureAt = Date.now();
              record.lastError = String(message || 'RelayNode connection failed').slice(0, 240);
              state.stats.relayNodeFailures++;
            }
            function relayTunnelConnectTimeout(candidate) {
              const configured = Number(candidate && candidate.targetConnectTimeoutMs);
              const perTarget = Number.isFinite(configured)
                ? Math.max(100, Math.min(60000, configured))
                : 10000;
              return Math.max(15000, Math.min(65000, perTarget * 2 + 5000));
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
              releaseTargetRelayLease(entry);
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
              if (entry.localPort) {
                state.stats.localReceivedFrames++;
                state.stats.localReceivedBytes += copy.byteLength;
              }
              if (entry.inboundBytes >= inboundPauseBytes) {
                setInboundPaused(entry, true);
              }
              const integratedServerSignal =
                globalThis.__gaiusIntegratedServerNetworkSignal;
              if (typeof integratedServerSignal === 'function') {
                integratedServerSignal();
              }
              if (typeof state.inboundPump === 'function') {
                state.inboundPump();
              }
            }
            function requestFlush(entry) {
              if (!entry.localPort) {
                flush(entry);
                return;
              }
              if (entry.localFlushScheduled) return;
              entry.localFlushScheduled = true;
              queueMicrotask(function() {
                entry.localFlushScheduled = false;
                flush(entry);
              });
            }
            function flush(entry) {
              if (!entry.connected || entry.remotePaused) return;
              if (!entry.localPort && (!entry.ws || entry.ws.readyState !== WebSocket.OPEN)) return;
              let localFlushFrames = 0;
              let localFlushBytes = 0;
              let localFlushBatches = 0;
              let localBatchParts = [];
              let localBatchBytes = 0;
              function flushLocalBatch() {
                if (localBatchBytes === 0) return;
                const batch = new Uint8Array(localBatchBytes);
                let offset = 0;
                for (let partIndex = 0; partIndex < localBatchParts.length; partIndex++) {
                  const part = localBatchParts[partIndex];
                  batch.set(part, offset);
                  offset += part.byteLength;
                }
                entry.localPort.postMessage(batch.buffer, [batch.buffer]);
                localFlushBatches++;
                localBatchParts = [];
                localBatchBytes = 0;
              }
              while (entry.outboundHead < entry.outbound.length) {
                if (entry.ws && entry.ws.bufferedAmount >= maximumWebSocketBufferedBytes) return;
                const bytes = entry.outbound[entry.outboundHead++];
                const byteLength = bytes.byteLength;
                entry.queuedBytes -= byteLength;
                state.stats.queuedBytes = Math.max(0, state.stats.queuedBytes - byteLength);
                try {
                  if (entry.localPort) {
                    if (localBatchBytes > 0 &&
                        localBatchBytes + byteLength > maximumLocalBatchBytes) {
                      flushLocalBatch();
                    }
                    localBatchParts.push(bytes);
                    localBatchBytes += byteLength;
                    localFlushFrames++;
                    localFlushBytes += byteLength;
                  } else {
                    entry.ws.send(bytes);
                  }
                  state.stats.sentFrames++;
                  state.stats.sentBytes += byteLength;
                } catch (error) {
                  fail(entry, error && (error.message || error));
                  return;
                }
              }
              try {
                flushLocalBatch();
              } catch (error) {
                fail(entry, error && (error.message || error));
                return;
              }
              if (localFlushFrames > 0) {
                state.stats.localFlushes += localFlushBatches;
                state.stats.localFlushFrames += localFlushFrames;
                state.stats.localFlushBytes += localFlushBytes;
                state.stats.peakLocalFlushFrames = Math.max(
                  state.stats.peakLocalFlushFrames,
                  localFlushFrames
                );
                state.stats.peakLocalFlushBytes = Math.max(
                  state.stats.peakLocalFlushBytes,
                  localFlushBytes
                );
              }
              entry.outbound = [];
              entry.outboundHead = 0;
            }
            function openRemoteCandidate(entry) {
              if (entry.closed || entry.connected) return;
              const upcoming = entry.candidates[entry.candidateIndex];
              if ((!upcoming || !upcoming.direct) && !entry.relayPreflightReady) {
                ensureRelayCandidates(entry);
                if (!entry.relayPreflightWaitStarted) {
                  entry.relayPreflightWaitStarted = true;
                  entry.relayPreflightPromise.then(function() {
                    if (entry.closed || entry.connected) return;
                    rankRemainingRelayCandidates(entry);
                    openRemoteCandidate(entry);
                  });
                }
                return;
              }
              if (entry.candidateIndex >= entry.candidates.length) {
                fail(entry, 'No Gaius direct endpoint or relay node could reach the server');
                return;
              }
              const candidate = entry.candidates[entry.candidateIndex++];
              if (candidate.direct && directPluginWasRecentlyUnavailable(entry, candidate)) {
                openRemoteCandidate(entry);
                return;
              }
              if (candidate.direct) state.stats.directAttempts++;
              else {
                state.stats.relayAttempts++;
                const localAffinity = localTargetRelayAffinity(entry, candidate);
                if (candidate.targetActiveConnections > 0 ||
                    (localAffinity && localAffinity.activeTunnels > 0)) {
                  state.stats.relayTargetActiveSelections++;
                  if (localAffinity && localAffinity.activeTunnels > 0) {
                    state.stats.relayTargetLocalActiveSelections++;
                  }
                } else if (candidate.targetRecentlyReachable) {
                  state.stats.relayTargetRecentSelections++;
                } else if (localAffinity) {
                  state.stats.relayTargetRecentSelections++;
                  state.stats.relayTargetLocalRecentSelections++;
                }
                recordRelayNodeAttempt(candidate);
              }
              const generation = ++entry.webSocketGeneration;
              let ws;
              try {
                ws = new WebSocket(candidate.url);
              } catch (error) {
                if (candidate.direct) {
                  rememberDirectPluginUnavailable(entry, candidate);
                } else {
                  state.stats.relayFailovers++;
                  recordRelayNodeFailure(candidate, error && (error.message || error));
                }
                openRemoteCandidate(entry);
                return;
              }
              entry.ws = ws;
              entry.currentCandidate = candidate;
              ws.binaryType = 'arraybuffer';
              let candidateTimeout;
              const armCandidateTimeout = function(timeoutMs) {
                clearTimeout(candidateTimeout);
                candidateTimeout = setTimeout(function() {
                if (entry.closed || entry.connected || generation !== entry.webSocketGeneration) {
                  return;
                }
                try { ws.close(); } catch (ignored) {}
                if (candidate.direct) {
                  rememberDirectPluginUnavailable(entry, candidate);
                } else {
                  state.stats.relayFailovers++;
                  recordRelayNodeFailure(candidate, 'RelayNode tunnel connection timed out');
                }
                openRemoteCandidate(entry);
                }, timeoutMs);
              };
              armCandidateTimeout(
                candidate.direct ? 800 : relayTunnelConnectTimeout(candidate)
              );
              ws.onopen = function() {
                if (generation !== entry.webSocketGeneration || entry.closed) return;
                if (!candidate.direct) {
                  armCandidateTimeout(relayTunnelConnectTimeout(candidate));
                }
                const control = {type: 'connect', host: entry.host, port: entry.port};
                const token = bridgeToken(candidate);
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
                    if (message && message.type === 'connecting' && !candidate.direct) {
                      const advertisedTimeout = Number(message.targetConnectTimeoutMs);
                      if (Number.isFinite(advertisedTimeout)) {
                        candidate.targetConnectTimeoutMs = Math.max(
                          100,
                          Math.min(60000, advertisedTimeout)
                        );
                        const record = relayNodeRecord(candidate);
                        if (record) {
                          record.targetConnectTimeoutMs = candidate.targetConnectTimeoutMs;
                        }
                      }
                      armCandidateTimeout(relayTunnelConnectTimeout(candidate));
                      return;
                    }
                    if (message && message.type === 'connected') {
                      clearTimeout(candidateTimeout);
                      entry.connected = true;
                      if (candidate.direct) {
                        forgetDirectPluginUnavailable(entry, candidate);
                        state.stats.directConnected++;
                      }
                      else recordRelayNodeSuccess(entry, candidate);
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
                  if (candidate.direct) {
                    rememberDirectPluginUnavailable(entry, candidate);
                  } else {
                    state.stats.relayFailovers++;
                    recordRelayNodeFailure(
                      candidate,
                      'RelayNode closed before tunnel connected: ' + (event && event.code || 0)
                    );
                  }
                  openRemoteCandidate(entry);
                  return;
                }
                releaseTargetRelayLease(entry);
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
                releaseTargetRelayLease(existing);
                existing.closed = true;
                try { if (existing.ws) existing.ws.close(); } catch (ignored) {}
              }
              const entry = {
                id: key,
                host: String(host),
                port: port|0,
                targetKey: normalizedTargetKey(host, port),
                ws: null,
                localPort: null,
                connected: false,
                remotePaused: false,
                localFlushScheduled: false,
                inbound: [],
                inboundHead: 0,
                inboundBytes: 0,
                outbound: [],
                outboundHead: 0,
                errors: [],
                closed: false,
                flowPaused: false,
                queuedBytes: 0,
                candidates: [],
                candidateIndex: 0,
                currentCandidate: null,
                webSocketGeneration: 0,
                relayPreparationStarted: false,
                relayPreflightReady: false,
                relayPreflightWaitStarted: false,
                relayPreflightPromise: Promise.resolve(),
                relayTargetLeaseKey: null
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
              releaseTargetRelayLease(entry);
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
            state.hasPendingInbound = function(id) {
              const entry = state.channels.get(id|0);
              return !!entry && entry.inboundHead < entry.inbound.length;
            };
            state.failLocalSession = function(sessionId, message) {
              sessionId = String(sessionId || '');
              state.channels.forEach(function(entry) {
                if (localSession(entry.host) === sessionId) {
                  fail(entry, message || 'Local server Worker stopped unexpectedly');
                }
              });
            };
            state.refreshRelayRegistry = function() {
              state.relayRegistryCache.clear();
              state.relayRegistryPromise = discoverRelayNodes();
              return state.relayRegistryPromise;
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

    @JSBody(params = {"id"}, script = """
            return !!globalThis.__gaiusNettyBridge.hasPendingInbound(id);
            """)
    private static native boolean hasPendingInbound(int id);

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
