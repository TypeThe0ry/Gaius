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
import org.teavm.platform.Platform;

/** Netty channel backed by Gaius' WebSocket-to-TCP browser bridge. */
public final class BrowserWebSocketChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int INITIAL_CHANNEL_CAPACITY = 16;
    // Decoder work runs on the browser event loop. A single pipeline handoff cannot be preempted
    // by the time check below, so the JS scheduler splits large TCP frames into 4 KiB slices. Tiny
    // relay frames are cheap enough to batch more deeply; the 2 ms check still runs between every
    // handoff and the byte cap keeps a turn bounded even when all slices are cheap.
    private static final int MAX_CHUNKS_PER_PUMP = 64;
    private static final int MAX_BYTES_PER_PUMP = 256 * 1024;
    private static final double MAX_MILLIS_PER_PUMP = 2.0;
    // A server/client runtime can own many browser channels (one per multiplayer player).  The
    // per-channel bound above is not a global bound: iterating sixteen busy channels could keep
    // one JavaScript turn runnable for roughly 32 ms.  Keep a small aggregate budget and resume
    // from a round-robin cursor so a busy first channel cannot starve the rest.
    private static final double MAX_TOTAL_MILLIS_PER_PUMP = 4.0;
    private static final int MAX_OUTBOUND_WRITES_PER_PUMP = 32;
    private static final int MAX_OUTBOUND_BYTES_PER_PUMP = 256 * 1024;
    private static final double MAX_OUTBOUND_MILLIS_PER_PUMP = 2.0;
    private static final EventLoop INLINE_EVENT_LOOP = new BrowserInlineEventLoop();
    private static BrowserWebSocketChannel[] channels =
            new BrowserWebSocketChannel[INITIAL_CHANNEL_CAPACITY];
    private static int nextSocketId = 1;
    private static int nextPumpChannelIndex;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final int socketId;
    private boolean open = true;
    private boolean active;
    private boolean pumping;
    private boolean outboundRetryScheduled;
    private Object pendingOutboundMessage;
    private int pendingOutboundOffset;
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
        initBridgeTail();
        initOutboundScheduler();
        initInboundScheduler();
    }

    public static void pumpAll() {
        pumpAllAndReportProgress();
    }

    /**
     * Runs one bounded raw-transport turn.
     *
     * <p>The result is a continuation hint: it is true when a slice was consumed or when the
     * aggregate budget stopped the round-robin scan before the captured channel set was visited.
     * Callers must pair this hint with {@link #hasPumpableInput()} before scheduling another turn;
     * the budget-only case lets a ready channel that was later in the cursor order get a chance
     * without creating an idle busy loop.</p>
     */
    public static boolean pumpAllAndReportProgress() {
        boolean progressed = false;
        final double startedAt = monotonicMillis();
        int channelsVisited = 0;
        int openChannelsVisited = 0;
        boolean budgetExhausted = false;
        // Capture the current length. A pipeline callback may create another channel or grow the
        // array; that channel is intentionally left for the next turn rather than extending this
        // turn's work without bound.
        final int channelCount = channels.length;
        if (channelCount > 0) {
            int index = nextPumpChannelIndex;
            if (index < 0 || index >= channelCount) {
                index = 0;
            }
            while (channelsVisited < channelCount) {
                // Always inspect at least one slot. This preserves error/close handling for a
                // newly signalled channel even if the clock is already at the aggregate limit.
                if (openChannelsVisited > 0
                        && monotonicMillis() - startedAt >= MAX_TOTAL_MILLIS_PER_PUMP) {
                    budgetExhausted = true;
                    break;
                }
                BrowserWebSocketChannel channel = channels[index];
                index++;
                if (index >= channelCount) {
                    index = 0;
                }
                channelsVisited++;
                if (channel == null || !channel.open) {
                    continue;
                }
                openChannelsVisited++;
                progressed |= channel.pump();
            }
            // Keep the cursor valid if a callback grew the backing array during this turn.
            int currentLength = channels.length;
            nextPumpChannelIndex = currentLength == 0 ? 0 : index % currentLength;
        }
        double elapsed = Math.max(0.0, monotonicMillis() - startedAt);
        recordPumpAllTelemetry(channelsVisited, elapsed, budgetExhausted);
        return progressed || budgetExhausted;
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

    /** Returns whether an unpaused channel has a ready slice for the Java decoder. */
    public static boolean hasPumpableInput() {
        for (BrowserWebSocketChannel channel : channels) {
            if (channel != null && channel.open && hasPumpableInbound(channel.socketId)) {
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
        pendingOutboundMessage = null;
        pendingOutboundOffset = 0;
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
        double startedAt = monotonicMillis();
        int writes = 0;
        int bytesWritten = 0;
        while (true) {
            Object message = outbound.current();
            if (message == null) {
                pendingOutboundMessage = null;
                pendingOutboundOffset = 0;
                return;
            }
            if (!(message instanceof ByteBuf buffer)) {
                pendingOutboundMessage = null;
                pendingOutboundOffset = 0;
                outbound.remove(new ChannelException(
                        "BrowserWebSocketChannel only supports ByteBuf outbound messages"));
                continue;
            }
            if (pendingOutboundMessage != message) {
                pendingOutboundMessage = message;
                pendingOutboundOffset = 0;
            }
            int readableBytes = buffer.readableBytes();
            if (pendingOutboundOffset >= readableBytes) {
                outbound.remove();
                pendingOutboundMessage = null;
                pendingOutboundOffset = 0;
                continue;
            }
            if (writes >= MAX_OUTBOUND_WRITES_PER_PUMP
                    || (writes > 0 && bytesWritten >= MAX_OUTBOUND_BYTES_PER_PUMP)
                    || (writes > 0 && monotonicMillis() - startedAt >= MAX_OUTBOUND_MILLIS_PER_PUMP)) {
                scheduleOutboundRetry();
                return;
            }
            int chunkLength = Math.min(
                    MAX_OUTBOUND_BYTES_PER_PUMP - bytesWritten,
                    readableBytes - pendingOutboundOffset);
            Int8Array chunk = copyBytes(
                    buffer,
                    buffer.readerIndex() + pendingOutboundOffset,
                    chunkLength);
            if (!sendSocket(socketId, chunk)) {
                // Keep the current ByteBuf in ChannelOutboundBuffer. The browser bridge is
                // applying backpressure; removing it here would silently lose protocol bytes.
                if (isSocketClosed(socketId)) {
                    close();
                    return;
                }
                scheduleOutboundRetry();
                return;
            }
            pendingOutboundOffset += chunkLength;
            writes++;
            bytesWritten += chunkLength;
            if (pendingOutboundOffset >= readableBytes) {
                outbound.remove();
                pendingOutboundMessage = null;
                pendingOutboundOffset = 0;
            }
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

    private boolean pump() {
        if (!open || pumping) {
            return false;
        }
        pumping = true;
        try {
            String error = pollError(socketId);
            if (error != null) {
                pipeline().fireExceptionCaught(new ChannelException(error));
                close();
                return false;
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
                    recordDecodedSlice(socketId, bytes.length);
                    try {
                        pipeline.fireChannelRead(Unpooled.wrappedBuffer(bytes));
                    } finally {
                        finishDecodedSlice(socketId);
                    }
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
            return chunks > 0;
        } finally {
            pumping = false;
        }
    }

    private static Int8Array copyBytes(ByteBuf buffer, int index, int length) {
        byte[] bytes = new byte[length];
        buffer.getBytes(index, bytes);
        return Int8Array.fromJavaArray(bytes);
    }

    private void scheduleOutboundRetry() {
        if (!open || !active || outboundRetryScheduled) {
            return;
        }
        outboundRetryScheduled = true;
        Platform.schedule(() -> {
            outboundRetryScheduled = false;
            if (open && active) {
                unsafe().flush();
            }
        }, 0);
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
            localSessionOwners: new Map(),
            directPluginMisses: new Map(),
            relayPreflightCache: new Map(),
            targetRelayLeases: new Map(),
            relayRegistryCache: new Map(),
            relayRegistryPromise: null,
            activeDecoderEntryId: 0,
            activeDecoderSliceBytes: 0,
            activeDecoderScopeIds: [],
            activeDecoderScopeBytes: [],
            activeDecoderScopeDepth: 0,
            // Queue accounting does not carry a socket id across the Java bridge. If a
            // re-entrant decoder scope from another channel is observed, owner-specific
            // O(1) updates are ambiguous and must fall back to the previous all-channel path.
            activeDecoderOwnerAmbiguous: false,
            // The active high-watermark duration is the sum of each live episode's
            // (sampledAt - startedAt). Keep the count and start-time sum incrementally so
            // packet/slice accounting does not scan every multiplayer channel on each callback.
            activeHighWatermarkStartCount: 0,
            activeHighWatermarkStartSumMillis: 0,
            exactPacketQueuePaused: false,
            gapProbeTimer: 0,
            gapProbeExpectedAt: 0,
            stats: {
              created: true,
              executionContext: typeof document === 'undefined' ? 'worker' : 'window',
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
              relayTargetAttestationFailures: 0,
              relayNodes: Object.create(null),
              connectPhases: [],
              relayParallelPreparations: 0,
              relaySelectionDeadlineHits: 0,
              relaySelectionReadyBeforeDeadline: 0,
              connected: 0,
              closed: 0,
              sentFrames: 0,
              sentBytes: 0,
              receivedFrames: 0,
              receivedBytes: 0,
              queuedBytes: 0,
              inboundQueuedBytes: 0,
              peakInboundQueuedBytes: 0,
              inboundSlices: 0,
              inboundSlicePumps: 0,
              inboundImmediateSchedules: 0,
              inboundRafSchedules: 0,
              inboundTimerSchedules: 0,
              inboundMessageChannelSchedules: 0,
              inboundMessageChannelCallbacks: 0,
              inboundContinuationMacrotasks: 0,
              inboundMessageChannelFailures: 0,
              inboundContinuationStaleCallbacks: 0,
              inboundSliceScheduleWaitSamples: 0,
              maxInboundSliceScheduleWaitMillis: 0,
              maxInboundSliceQueue: 0,
              longestInboundSlicePumpMillis: 0,
              inboundPumpInstalled: 0,
              inboundPumpRequested: 0,
              inboundPumpScheduled: 0,
              inboundPumpCoalesced: 0,
              inboundPumpCallbacks: 0,
              inboundPumpMessageCallbacks: 0,
              inboundPumpTimerCallbacks: 0,
              inboundPumpWatchdogCallbacks: 0,
              inboundPumpStaleCallbacks: 0,
              inboundPumpJavaStarted: 0,
              inboundPumpJavaCompleted: 0,
              inboundPumpJavaSkipped: 0,
              inboundPumpJavaFailures: 0,
              inboundPumpRescheduled: 0,
              inboundPumpBlockedByExactQueue: 0,
              inboundPumpMessageChannelFailures: 0,
              maxInboundPumpScheduleWaitMillis: 0,
              inboundPumpLastCallbackAtMillis: 0,
              decodedSliceBacklog: 0,
              maxDecodedSliceBacklog: 0,
              decodedSliceBacklogPauses: 0,
              decodedSliceBacklogResumes: 0,
              decoderCumulationBytes: 0,
              maxDecoderCumulationBytes: 0,
              decoderCumulationPauseBytes: 12 * 1024 * 1024,
              decoderCumulationLimitBytes: 16 * 1024 * 1024,
              decodedPacketQueue: 0,
              maxDecodedPacketQueue: 0,
              decodedPacketQueuePauses: 0,
              decodedPacketQueueResumes: 0,
              decodedPacketDrainSignals: 0,
              queuedPacketHandleSamples: 0,
              maxQueuedPacketHandleMillis: 0,
              maxQueuedPacketHandleType: '',
              slowQueuedPacketEventSequence: 0,
              slowQueuedPacketEvents: [],
              slowQueuedPacketEventsDropped: 0,
              inlineDecodedPackets: 0,
              activeHighWatermarks: 0,
              highWatermarkDurationMillis: 0,
              longestHighWatermarkMillis: 0,
              activeHighWatermarkMillis: 0,
              highWatermarkEventSequence: 0,
              highWatermarkEvents: [],
              highWatermarkEventsDropped: 0,
              flowPauses: 0,
              flowResumes: 0,
              localFlushes: 0,
              localFlushFrames: 0,
              localFlushBytes: 0,
              localReceivedFrames: 0,
              localReceivedBytes: 0,
              localClaimWaits: 0,
              localClaimRetries: 0,
              localClaimTimeouts: 0,
              localDuplicateOpens: 0,
              localSupersededClaims: 0,
              peakLocalFlushFrames: 0,
              peakLocalFlushBytes: 0,
              outboundTurns: 0,
              outboundTurnFrames: 0,
              outboundTurnBytes: 0,
              maxOutboundTurnFrames: 0,
              maxOutboundTurnBytes: 0,
              maxOutboundTurnMillis: 0,
              outboundYields: 0,
              outboundImmediateFlushes: 0,
              outboundTimerFlushes: 0,
              outboundContinuationTimers: 0,
              outboundMessageChannelFlushes: 0,
              outboundMessageChannelCallbacks: 0,
              outboundContinuationMacrotasks: 0,
              outboundFlushWaitSamples: 0,
              maxOutboundFlushWaitMillis: 0,
              outboundEmptyTurns: 0,
              outboundBackpressureDeferrals: 0,
              queuedFrames: 0,
              peakQueuedFrames: 0,
              queuedControlBytes: 0,
              peakQueuedControlBytes: 0,
              controlQueueOverflows: 0,
              webSocketBackpressureWaits: 0,
              localMessagePortSends: 0,
              webSocketSends: 0,
              outboundFrameLimit: 32,
              outboundByteLimit: 256 * 1024,
              outboundMillisLimit: 2,
              pumpCalls: 0,
              pumpChunks: 0,
              pumpBytes: 0,
              pumpAllTurns: 0,
              pumpAllChannelsVisited: 0,
              pumpAllBudgetYields: 0,
              pumpAllMaxTurnMillis: 0,
              pumpAllMaxChannelsPerTurn: 0,
              pumpAllLastTurnMillis: 0,
              pumpAllLastChannelsVisited: 0,
              deferredPumps: 0,
              peakPumpChunks: 0,
              peakPumpBytes: 0,
              peakPumpMillis: 0,
              longestPumpMillis: 0,
              eventLoopGapSamples: 0,
              eventLoopGapsOver500: 0,
              longestEventLoopGapMillis: 0,
              remoteCloseRetireScheduled: 0,
              remoteCloseRetireDeferred: 0,
              remoteCloseRetireForced: 0,
              remoteCloseRetireFinalized: 0,
              errors: 0
            }
            };
            const maximumWebSocketBufferedBytes = 4 * 1024 * 1024;
            const maximumOutboundQueueBytes = 16 * 1024 * 1024;
            const maximumOutboundQueueFrames = 4096;
            const maximumOutboundControlFrames = 64;
            const maximumOutboundControlBytes = 64 * 1024;
            const maximumOutboundFramesPerTurn = 32;
            const maximumOutboundBytesPerTurn = 256 * 1024;
            const maximumOutboundMillisPerTurn = 2;
            const webSocketBackpressureRetryMs = 4;
            const relayPreflightTimeoutMs = 900;
            const relayRegistryTimeoutMs = 1500;
            const relayParallelPreparationDelayMs = 50;
            const relaySelectionDeadlineMs = 120;
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
            const configuredLocalClaimTimeout = Number(
            globalThis.__gaiusLocalPortClaimTimeoutMs
            );
            const localPortClaimTimeoutMs = Number.isFinite(configuredLocalClaimTimeout)
            ? Math.max(50, Math.min(30000, configuredLocalClaimTimeout))
            : 10000;
            const localPortClaimRetryMs = 8;
            function recordConnectPhase(entry, phase, detail) {
            const now = typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            if (!entry.connectStartedAt) entry.connectStartedAt = now;
            const event = {
              id: entry.id,
              target: entry.targetKey,
              phase: String(phase),
              elapsedMillis: Math.max(0, now - entry.connectStartedAt),
              at: Date.now()
            };
            if (detail !== undefined && detail !== null) {
              event.detail = String(detail).slice(0, 160);
            }
            state.stats.connectPhases.push(event);
            if (state.stats.connectPhases.length > 256) {
              state.stats.connectPhases.splice(0, state.stats.connectPhases.length - 256);
            }
            }
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
            candidate.targetAttestation = !!values.targetAttestation;
            const record = relayNodeRecord(candidate);
            if (record) {
              record.lastPreflightAt = Date.now();
              record.availableConnections = candidate.availableConnections;
              record.targetConnectTimeoutMs = candidate.targetConnectTimeoutMs;
              record.targetAffinityMs = candidate.targetAffinityMs;
              record.targetActiveConnections = candidate.targetActiveConnections;
              record.targetRecentlyReachable = candidate.targetRecentlyReachable;
              record.targetAttestation = candidate.targetAttestation;
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
                targetRecentlyReachable: !!(target && target.recentlyReachable),
                targetAttestation: Array.isArray(manifest.capabilities) &&
                  manifest.capabilities.includes('target-attestation')
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
            function appendRelayCandidates(entry, candidates) {
            const added = [];
            for (let index = 0; index < candidates.length; index++) {
              const candidate = candidates[index];
              if (!candidate || entry.candidateUrls.has(candidate.url)) continue;
              entry.candidateUrls.add(candidate.url);
              entry.candidates.push(candidate);
              added.push(candidate);
            }
            return added;
            }
            function probeRelayCandidates(entry, candidates) {
            if (candidates.length === 0 || typeof fetch !== 'function') {
              return Promise.resolve();
            }
            const probes = new Array(candidates.length);
            for (let index = 0; index < candidates.length; index++) {
              probes[index] = probeRelayCandidate(entry, candidates[index]);
            }
            return Promise.all(probes);
            }
            function prepareRelayCandidates(entry) {
            entry.relayPreflightReady = false;
            entry.relayPreflightWaitStarted = false;
            recordConnectPhase(entry, 'relay-preparation-start');
            const immediate = appendRelayCandidates(entry, bridgeUrls([]));
            const immediateProbes = probeRelayCandidates(entry, immediate);
            const registryPromise = state.relayRegistryPromise || discoverRelayNodes();
            state.relayRegistryPromise = registryPromise;
            const discoveredProbes = registryPromise.then(function(discovered) {
              return probeRelayCandidates(
                entry,
                appendRelayCandidates(entry, bridgeUrls(discovered))
              );
            });
            entry.relayPreflightPromise = Promise.all([
              immediateProbes,
              discoveredProbes
            ]).then(function() {
              entry.relayPreflightReady = true;
              rankRemainingRelayCandidates(entry);
            });
            entry.relaySelectionPromise = Promise.race([
              entry.relayPreflightPromise.then(function() { return true; }),
              new Promise(function(resolve) {
                entry.relaySelectionTimer = setTimeout(function() {
                  entry.relaySelectionTimer = 0;
                  resolve(false);
                }, relaySelectionDeadlineMs);
              })
            ]).then(function(readyBeforeDeadline) {
              if (entry.relaySelectionTimer) {
                clearTimeout(entry.relaySelectionTimer);
                entry.relaySelectionTimer = 0;
              }
              if (entry.closed) return;
              entry.relaySelectionReady = true;
              rankRemainingRelayCandidates(entry);
              if (readyBeforeDeadline) {
                state.stats.relaySelectionReadyBeforeDeadline++;
              } else {
                state.stats.relaySelectionDeadlineHits++;
              }
              recordConnectPhase(
                entry,
                'relay-selection-ready',
                readyBeforeDeadline ? 'preflight' : '120ms-deadline'
              );
            });
            }
            function ensureRelayCandidates(entry) {
            if (entry.relayPreparationStarted) return;
            if (entry.relayPreparationTimer) {
              clearTimeout(entry.relayPreparationTimer);
              entry.relayPreparationTimer = 0;
            }
            entry.relayPreparationStarted = true;
            state.stats.relayParallelPreparations++;
            prepareRelayCandidates(entry);
            }
            function scheduleRelayPreparation(entry) {
            if (entry.relayPreparationStarted || entry.relayPreparationTimer) return;
            entry.relayPreparationTimer = setTimeout(function() {
              entry.relayPreparationTimer = 0;
              if (entry.closed || entry.connected || entry.directNegotiating) return;
              ensureRelayCandidates(entry);
            }, relayParallelPreparationDelayMs);
            }
            // initBridgeTail is a separate @JSBody method, so its helper lexical scope is not
            // visible to callbacks declared in this method. Resolve the tail implementation
            // through the shared state instead of relying on a cross-script bare identifier.
            function relayNodeRecord(candidate) {
            const resolver = state.relayNodeRecordResolver;
            return typeof resolver === 'function' ? resolver(candidate) : null;
            }
            globalThis.__gaiusNettyBridgeBootstrapState = state;
            globalThis.__gaiusNettyBridgeBootstrapScope = {
              recordConnectPhase: recordConnectPhase,
              authorityHost: authorityHost,
              normalizedTargetKey: normalizedTargetKey,
              pruneDiscoveryCache: pruneDiscoveryCache,
              targetRelayLeaseKey: targetRelayLeaseKey,
              targetAffinityTtl: targetAffinityTtl,
              pruneTargetRelayLeases: pruneTargetRelayLeases,
              localTargetRelayAffinity: localTargetRelayAffinity,
              acquireTargetRelayLease: acquireTargetRelayLease,
              releaseTargetRelayLease: releaseTargetRelayLease,
              defaultBridgeUrl: defaultBridgeUrl,
              normalizeRelayUrl: normalizeRelayUrl,
              relayNodeCandidate: relayNodeCandidate,
              normalizeRelayRegistryUrl: normalizeRelayRegistryUrl,
              relayRegistryUrls: relayRegistryUrls,
              loadRelayRegistry: loadRelayRegistry,
              discoverRelayNodes: discoverRelayNodes,
              bridgeUrls: bridgeUrls,
              directPluginUrl: directPluginUrl,
              bridgeToken: bridgeToken,
              relayManifestUrl: relayManifestUrl,
              directPluginCacheKey: directPluginCacheKey,
              directPluginWasRecentlyUnavailable: directPluginWasRecentlyUnavailable,
              rememberDirectPluginUnavailable: rememberDirectPluginUnavailable,
              forgetDirectPluginUnavailable: forgetDirectPluginUnavailable,
              relayPreflightCacheKey: relayPreflightCacheKey,
              applyRelayPreflight: applyRelayPreflight,
              relayCandidateScore: relayCandidateScore,
              rankRemainingRelayCandidates: rankRemainingRelayCandidates,
              probeRelayCandidate: probeRelayCandidate,
              appendRelayCandidates: appendRelayCandidates,
              probeRelayCandidates: probeRelayCandidates,
              prepareRelayCandidates: prepareRelayCandidates,
              ensureRelayCandidates: ensureRelayCandidates,
              scheduleRelayPreparation: scheduleRelayPreparation,
              maximumWebSocketBufferedBytes: maximumWebSocketBufferedBytes,
              maximumOutboundQueueBytes: maximumOutboundQueueBytes,
              maximumOutboundQueueFrames: maximumOutboundQueueFrames,
              maximumOutboundControlFrames: maximumOutboundControlFrames,
              maximumOutboundControlBytes: maximumOutboundControlBytes,
              maximumOutboundFramesPerTurn: maximumOutboundFramesPerTurn,
              maximumOutboundBytesPerTurn: maximumOutboundBytesPerTurn,
              maximumOutboundMillisPerTurn: maximumOutboundMillisPerTurn,
              webSocketBackpressureRetryMs: webSocketBackpressureRetryMs,
              relayPreflightTimeoutMs: relayPreflightTimeoutMs,
              relayRegistryTimeoutMs: relayRegistryTimeoutMs,
              relayParallelPreparationDelayMs: relayParallelPreparationDelayMs,
              relaySelectionDeadlineMs: relaySelectionDeadlineMs,
              relayRegistryCacheTtlMs: relayRegistryCacheTtlMs,
              maximumRelayRegistryNodes: maximumRelayRegistryNodes,
              maximumRelayRegistryUrls: maximumRelayRegistryUrls,
              maximumNestedRegistriesPerResponse: maximumNestedRegistriesPerResponse,
              defaultRelayRegistryUrl: defaultRelayRegistryUrl,
              directPluginMissTtlMs: directPluginMissTtlMs,
              relayPreflightCacheTtlMs: relayPreflightCacheTtlMs,
              relayPreflightFailureCacheTtlMs: relayPreflightFailureCacheTtlMs,
              defaultTargetAffinityMs: defaultTargetAffinityMs,
              maximumDiscoveryCacheEntries: maximumDiscoveryCacheEntries,
              maximumLocalBatchBytes: maximumLocalBatchBytes,
              configuredLocalClaimTimeout: configuredLocalClaimTimeout,
              localPortClaimTimeoutMs: localPortClaimTimeoutMs,
              localPortClaimRetryMs: localPortClaimRetryMs
            };
            """)
    private static native void initBridge();

    @JSBody(script = """
            const state = globalThis.__gaiusNettyBridgeBootstrapState;
            const scope = globalThis.__gaiusNettyBridgeBootstrapScope;
            if (!state || !scope) return;
            const {
              recordConnectPhase,
              normalizedTargetKey,
              localTargetRelayAffinity,
              acquireTargetRelayLease,
              releaseTargetRelayLease,
              discoverRelayNodes,
              directPluginUrl,
              bridgeToken,
              directPluginWasRecentlyUnavailable,
              rememberDirectPluginUnavailable,
              forgetDirectPluginUnavailable,
              rankRemainingRelayCandidates,
              ensureRelayCandidates,
              scheduleRelayPreparation,
              maximumOutboundQueueBytes,
              maximumOutboundQueueFrames,
              maximumOutboundBytesPerTurn,
              webSocketBackpressureRetryMs,
              localPortClaimTimeoutMs,
              localPortClaimRetryMs
            } = scope;
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
                targetRecentlyReachable: false,
                targetAttestation: false
              };
              nodes[candidate.url] = record;
            }
            return record;
            }
            state.relayNodeRecordResolver = relayNodeRecord;
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
            function directPluginTunnelConnectTimeout(candidate) {
            const configured = Number(candidate && candidate.targetConnectTimeoutMs);
            if (!Number.isFinite(configured)) return 800;
            return Math.max(1000, Math.min(61000, configured + 1000));
            }
            function localSession(host) {
            const match = /^(?:client|server)-([a-f0-9]{32})\\.gaius-local$/.exec(host);
            return match ? match[1] : null;
            }
            function localPortMap() {
            const ports = globalThis.__gaiusLocalServerPorts;
            if (!ports) return null;
            if (typeof ports.get === 'function' &&
                typeof ports.set === 'function' &&
                !ports.__gaiusLocalPortObserver) {
              const originalSet = ports.set;
              try {
                Object.defineProperty(ports, '__gaiusLocalPortObserver', {
                  value: true,
                  configurable: false,
                  enumerable: false
                });
                ports.set = function(sessionId, port) {
                  const result = originalSet.call(this, sessionId, port);
                  queueMicrotask(function() {
                    notifyLocalPortAvailable(String(sessionId || ''));
                  });
                  return result;
                };
              } catch (ignored) {}
            }
            return ports;
            }
            function localWorkerGeneration(sessionId) {
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const key = String(sessionId || '');
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            const generation = worker && !worker.__gaiusTerminal
              ? String(worker.__gaiusLaunchGeneration || '')
              : '';
            if (worker) return /^[1-9][0-9]*$/.test(generation) ? generation : '';
            const serverSession = String(globalThis.__gaiusServerSessionId || '');
            if (serverSession !== key) return '';
            const serverGeneration = String(
              globalThis.__gaiusServerLaunchGeneration || ''
            );
            return /^[1-9][0-9]*$/.test(serverGeneration) ? serverGeneration : '';
            }
            function localPortGeneration(port) {
            return port ? String(port.__gaiusLaunchGeneration || '') : '';
            }
            function activeLocalPortOwner(sessionId, generation, port) {
            const key = String(sessionId || '');
            const expected = String(generation || '');
            if (!key || !/^[1-9][0-9]*$/.test(expected) || !port) return false;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (worker) {
              return !worker.__gaiusTerminal &&
                String(worker.__gaiusLaunchGeneration || '') === expected &&
                worker.__gaiusClientPort === port;
            }
            return String(globalThis.__gaiusServerSessionId || '') === key &&
              String(globalThis.__gaiusServerLaunchGeneration || '') === expected &&
              globalThis.__gaiusServerClientPort === port;
            }
            function takeLocalPort(sessionId, expectedGeneration) {
            const ports = localPortMap();
            if (!ports) return null;
            const expected = String(expectedGeneration || '');
            if (!/^[1-9][0-9]*$/.test(expected)) {
              return {__gaiusLocalPortGenerationMismatch: true};
            }
            if (typeof ports.get === 'function') {
              const mappedPort = ports.get(sessionId) || null;
              if (!mappedPort) return null;
              if (expected && localPortGeneration(mappedPort) !== expected) {
                return {__gaiusLocalPortGenerationMismatch: true};
              }
              ports.delete(sessionId);
              return mappedPort;
            }
            const objectPort = ports[sessionId] || null;
            if (!objectPort) return null;
            if (expected && localPortGeneration(objectPort) !== expected) {
              return {__gaiusLocalPortGenerationMismatch: true};
            }
            delete ports[sessionId];
            return objectPort;
            }
            function clearLocalClaim(entry) {
            if (!entry) return;
            if (entry.localClaimTimer) {
              clearTimeout(entry.localClaimTimer);
              entry.localClaimTimer = 0;
            }
            entry.localClaimGeneration++;
            }
            function clearCandidateTimeout(entry) {
            if (!entry || !entry.candidateTimeout) return;
            clearTimeout(entry.candidateTimeout);
            entry.candidateTimeout = 0;
            }
            function clearRelayPreparation(entry) {
            if (!entry) return;
            clearCandidateTimeout(entry);
            if (entry.relayPreparationTimer) {
              clearTimeout(entry.relayPreparationTimer);
              entry.relayPreparationTimer = 0;
            }
            if (entry.relaySelectionTimer) {
              clearTimeout(entry.relaySelectionTimer);
              entry.relaySelectionTimer = 0;
            }
            }
            function forgetLocalOwner(entry) {
            if (!entry || !entry.localSessionId) return;
            if (state.localSessionOwners.get(entry.localSessionId) === entry) {
              state.localSessionOwners.delete(entry.localSessionId);
            }
            }
            function notifyLocalPortAvailable(sessionId) {
            const entry = state.localSessionOwners.get(sessionId);
            if (!entry || entry.closed || entry.connected) return;
            if (entry.localClaimTimer) {
              clearTimeout(entry.localClaimTimer);
              entry.localClaimTimer = 0;
            }
            tryClaimLocalPort(entry, entry.localClaimGeneration);
            }
            function localWorkerOwnsPort(entry, localPort) {
            const expectedGeneration = String(entry && entry.localLaunchGeneration || '');
            if (!entry || !/^[1-9][0-9]*$/.test(expectedGeneration) ||
                !localPort || !/^[1-9][0-9]*$/.test(localPortGeneration(localPort))) {
              return false;
            }
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const sessionId = String(entry.localSessionId || '');
            const localWorker = workers && typeof workers.get === 'function'
              ? workers.get(sessionId)
              : null;
            if (localWorker && !localWorker.__gaiusTerminal) {
              return String(localWorker.__gaiusLaunchGeneration || '') === expectedGeneration &&
                localWorker.__gaiusClientPort === localPort;
            }
            return String(globalThis.__gaiusServerSessionId || '') === sessionId &&
              String(globalThis.__gaiusServerLaunchGeneration || '') === expectedGeneration &&
              globalThis.__gaiusServerClientPort === localPort;
            }
            function attachLocalPort(entry, localPort) {
            if (entry.closed || !localPort) return false;
            if (!localWorkerOwnsPort(entry, localPort)) return false;
            clearLocalClaim(entry);
            entry.localPort = localPort;
            entry.connected = true;
            const sessionId = entry.localSessionId;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const localWorker = workers && typeof workers.get === 'function'
              ? workers.get(sessionId)
              : null;
            const ownsLocalWorker = !!localWorker;
            if (ownsLocalWorker) {
              localWorker.__gaiusClientAttached = true;
              localWorker.__gaiusHandoffPending = false;
              const handoff = globalThis.__gaiusSingleplayerHandoff;
              const handoffSession = handoff && typeof handoff === 'object'
                ? String(handoff.sessionId || '')
                : String(handoff || '');
              const handoffGeneration = handoff && typeof handoff === 'object'
                ? String(handoff.generation || '')
                : String(globalThis.__gaiusSingleplayerHandoffGeneration || '');
              if (handoffSession === sessionId && handoffGeneration &&
                  handoffGeneration ===
                    String(localWorker.__gaiusLaunchGeneration || '')) {
                globalThis.__gaiusSingleplayerHandoff = '';
                globalThis.__gaiusSingleplayerHandoffGeneration = '';
              }
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
              if (entry.closed || entry.localPort !== localPort) return;
              const message = event.data;
              if (message && typeof message === 'object' && !(message instanceof ArrayBuffer) &&
                  !ArrayBuffer.isView(message)) {
                if (message.type === 'flow' && typeof message.paused === 'boolean') {
                  entry.remotePaused = message.paused;
                  if (!entry.remotePaused) requestFlush(entry);
                } else if (message.type === 'close') {
                  entry.connected = false;
                  entry.closed = true;
                  if (typeof state.cancelOutboundFlush === 'function') {
                    state.cancelOutboundFlush(entry);
                  }
                  discardOutboundControls(entry);
                  discardOutboundData(entry);
                  entry.localPort = null;
                  forgetLocalOwner(entry);
                  try { localPort.close(); } catch (ignored) {}
                  state.stats.closed++;
                  if (typeof state.retireClosedEntry === 'function') {
                    state.retireClosedEntry(entry);
                  }
                }
                return;
              }
              if (message instanceof ArrayBuffer || ArrayBuffer.isView(message)) {
                entry.localActivity = true;
                deliverInbound(entry, message);
              }
            };
            localPort.onmessageerror = function() {
              if (entry.localPort === localPort) {
                fail(entry, 'Local server MessagePort decode failed');
              }
            };
            if (typeof localPort.start === 'function') localPort.start();
            requestFlush(entry);
            return true;
            }
            function tryClaimLocalPort(entry, generation) {
            if (entry.closed || entry.connected || generation !== entry.localClaimGeneration) {
              return;
            }
            if (entry.localGenerationRequired &&
                !/^[1-9][0-9]*$/.test(String(entry.localLaunchGeneration || ''))) {
              fail(entry, 'Local server Worker launch generation is missing or invalid');
              return;
            }
            const localPort = takeLocalPort(
              entry.localSessionId,
              entry.localLaunchGeneration
            );
            if (localPort && localPort.__gaiusLocalPortGenerationMismatch) {
              fail(
                entry,
                'Local server MessagePort generation changed for ' + entry.localSessionId
              );
              return;
            }
            if (localPort) {
              if (!localWorkerOwnsPort(entry, localPort)) {
                try { localPort.close(); } catch (ignored) {}
                fail(
                  entry,
                  'Local server MessagePort owner changed for ' + entry.localSessionId
                );
                return;
              }
              if (!attachLocalPort(entry, localPort)) {
                try { localPort.close(); } catch (ignored) {}
                fail(
                  entry,
                  'Local server MessagePort attach generation mismatch for ' +
                    entry.localSessionId
                );
              }
              return;
            }
            if (Date.now() >= entry.localClaimDeadline) {
              state.stats.localClaimTimeouts++;
              fail(
                entry,
                'Local server MessagePort did not register within ' +
                  localPortClaimTimeoutMs + ' ms for ' + entry.localSessionId
              );
              return;
            }
            state.stats.localClaimRetries++;
            entry.localClaimTimer = setTimeout(function() {
              entry.localClaimTimer = 0;
              tryClaimLocalPort(entry, generation);
            }, localPortClaimRetryMs);
            }
            function claimLocalPort(entry, sessionId, launchGeneration, generationRequired) {
            entry.localSessionId = sessionId;
            entry.localLaunchGeneration = String(launchGeneration || '');
            entry.localGenerationRequired = !!generationRequired;
            const previous = state.localSessionOwners.get(sessionId);
            if (previous && previous !== entry && !previous.closed) {
              if (previous.connected) {
                fail(entry, 'Local server session already has an active transport for ' + sessionId);
                return;
              }
              state.stats.localSupersededClaims++;
              fail(previous, 'Local server connection attempt was superseded for ' + sessionId);
            }
            state.localSessionOwners.set(sessionId, entry);
            if (entry.localGenerationRequired &&
                !/^[1-9][0-9]*$/.test(String(entry.localLaunchGeneration || ''))) {
              fail(entry, 'Local server Worker launch generation is missing or invalid');
              return;
            }
            const localPort = takeLocalPort(sessionId, entry.localLaunchGeneration);
            if (localPort && localPort.__gaiusLocalPortGenerationMismatch) {
              fail(entry, 'Local server MessagePort generation changed for ' + sessionId);
              return;
            }
            if (localPort) {
              if (!localWorkerOwnsPort(entry, localPort)) {
                try { localPort.close(); } catch (ignored) {}
                fail(entry, 'Local server MessagePort owner changed for ' + sessionId);
                return;
              }
              if (!attachLocalPort(entry, localPort)) {
                try { localPort.close(); } catch (ignored) {}
                fail(entry, 'Local server MessagePort attach generation mismatch for ' + sessionId);
              }
              return;
            }
            state.stats.localClaimWaits++;
            entry.localClaimDeadline = Date.now() + localPortClaimTimeoutMs;
            const generation = ++entry.localClaimGeneration;
            tryClaimLocalPort(entry, generation);
            }
            function fail(entry, message) {
            if (!entry || entry.closed) return;
            entry.errors.push(String(message || 'Browser bridge error'));
            if (entry.errors.length > 16) entry.errors.splice(0, entry.errors.length - 16);
            state.stats.errors++;
            releaseTargetRelayLease(entry);
            clearLocalClaim(entry);
            clearRelayPreparation(entry);
            forgetLocalOwner(entry);
            if (entry.retireClosedHandle) {
              clearTimeout(entry.retireClosedHandle);
              entry.retireClosedHandle = 0;
            }
            entry.retireClosedPending = false;
            // Mark the entry closed before asking the browser to close the socket.  Some
            // WebSocket implementations can deliver `onclose` reentrantly; the error path
            // must own retirement exactly once rather than racing that callback and marking
            // the entry disposed before the shared retire helper can schedule its finalizer.
            entry.closed = true;
            try { if (entry.ws) entry.ws.close(); } catch (ignored) {}
            if (typeof state.cancelOutboundFlush === 'function') {
              state.cancelOutboundFlush(entry);
            }
            discardOutboundControls(entry);
            if (entry.localPort) queueControl(entry, {type: 'close'}, entry.localPort, true);
            entry.connected = false;
            entry.localPort = null;
            discardOutboundData(entry);
            state.discardInbound(entry);
            state.stopEventLoopGapProbeIfIdle();
            // Reuse the identity-checked remote-close retirement path so an onerror/fail
            // without a later Java tick still wakes the bounded Java channel cleanup.
            state.retireClosedEntry(entry);
            }
            function sendControl(entry, message) {
            const target = entry.localPort || entry.ws;
            if (!target) return false;
            queueControl(entry, message, target, false);
            return true;
            }
            function setInboundPaused(entry, paused, reason, depth, queuedBytes) {
            if (entry.flowPaused === paused || !entry.connected) return;
            try {
              sendControl(entry, {type: 'flow', paused: !!paused});
              entry.flowPaused = paused;
              if (paused) {
                state.stats.flowPauses++;
                state.startHighWatermark(entry, reason, depth, queuedBytes);
              } else {
                state.stats.flowResumes++;
                state.finishHighWatermark(entry, depth, queuedBytes);
              }
            } catch (error) {
              fail(entry, error && (error.message || error));
            }
            }
            function deliverInbound(entry, buffer) {
            state.deliverInbound(entry, buffer);
            }
            function discardOutboundData(entry) {
            state.discardOutboundData(entry);
            }
            function discardOutboundControls(entry) {
            state.discardOutboundControls(entry);
            }
            function queueControl(entry, message, target, closeAfterSend, generation) {
            state.queueOutboundControl(
              entry,
              message,
              target,
              closeAfterSend,
              generation
            );
            }
            function requestFlush(entry, delayMillis) {
            state.requestOutboundFlush(entry, delayMillis);
            }
            function openRemoteCandidate(entry) {
            if (entry.closed || entry.connected) return;
            const upcoming = entry.candidates[entry.candidateIndex];
            if ((!upcoming || !upcoming.direct) && !entry.relaySelectionReady) {
              ensureRelayCandidates(entry);
              if (!entry.relayPreflightWaitStarted) {
                entry.relayPreflightWaitStarted = true;
                entry.relaySelectionPromise.then(function() {
                  if (entry.closed || entry.connected) return;
                  rankRemainingRelayCandidates(entry);
                  openRemoteCandidate(entry);
                });
              }
              return;
            }
            if (entry.candidateIndex >= entry.candidates.length) {
              if (!entry.relayPreflightReady && !entry.relayExhaustionWaitStarted) {
                entry.relayExhaustionWaitStarted = true;
                entry.relayPreflightPromise.then(function() {
                  if (entry.closed || entry.connected) return;
                  entry.relayExhaustionWaitStarted = false;
                  rankRemainingRelayCandidates(entry);
                  openRemoteCandidate(entry);
                });
                return;
              }
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
            recordConnectPhase(
              entry,
              candidate.direct ? 'direct-websocket-start' : 'relay-websocket-start',
              candidate.url
            );
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
            const armCandidateTimeout = function(timeoutMs) {
              clearCandidateTimeout(entry);
              entry.candidateTimeout = setTimeout(function() {
              entry.candidateTimeout = 0;
              if (entry.closed || entry.connected || generation !== entry.webSocketGeneration) {
                return;
              }
              recordConnectPhase(
                entry,
                candidate.direct ? 'direct-failed' : 'relay-failed',
                candidate.direct ? 'websocket-timeout' : 'target-timeout'
              );
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
              recordConnectPhase(
                entry,
                candidate.direct ? 'direct-websocket-open' : 'relay-websocket-open',
                candidate.url
              );
              if (!candidate.direct) {
                armCandidateTimeout(relayTunnelConnectTimeout(candidate));
              }
              const control = {type: 'connect', host: entry.host, port: entry.port};
              const token = bridgeToken(candidate);
              if (token !== undefined) control.token = token;
              queueControl(entry, control, ws, false, generation);
            };
            ws.onmessage = function(event) {
              if (generation !== entry.webSocketGeneration || entry.closed) return;
              if (typeof event.data === 'string') {
                try {
                  const message = JSON.parse(event.data);
                  if (message && message.type === 'connecting') {
                    if (candidate.direct) {
                      entry.directNegotiating = true;
                      if (entry.relayPreparationTimer) {
                        clearTimeout(entry.relayPreparationTimer);
                        entry.relayPreparationTimer = 0;
                      }
                    }
                    recordConnectPhase(
                      entry,
                      candidate.direct ? 'direct-target-connecting' : 'relay-target-connecting',
                      candidate.url
                    );
                    const advertisedTimeout = Number(message.targetConnectTimeoutMs);
                    if (Number.isFinite(advertisedTimeout)) {
                      candidate.targetConnectTimeoutMs = Math.max(
                        100,
                        Math.min(60000, advertisedTimeout)
                      );
                      const record = candidate.direct ? null : relayNodeRecord(candidate);
                      if (record) {
                        record.targetConnectTimeoutMs = candidate.targetConnectTimeoutMs;
                      }
                    }
                    armCandidateTimeout(candidate.direct
                      ? directPluginTunnelConnectTimeout(candidate)
                      : relayTunnelConnectTimeout(candidate));
                    return;
                  }
                  if (message && message.type === 'connected') {
                    if (entry.connected) return;
                    const attestationPresent = typeof message.host === 'string' &&
                      Number.isInteger(Number(message.port));
                    const attestationMatches = attestationPresent &&
                      normalizedTargetKey(message.host, Number(message.port)) === entry.targetKey;
                    if ((!candidate.direct && !attestationPresent) ||
                        (attestationPresent && !attestationMatches)) {
                      clearCandidateTimeout(entry);
                      state.stats.relayTargetAttestationFailures++;
                      try { ws.close(1008, 'RelayNode target attestation mismatch'); }
                      catch (ignored) {}
                      if (candidate.direct) {
                        rememberDirectPluginUnavailable(entry, candidate);
                      } else {
                        state.stats.relayFailovers++;
                        recordRelayNodeFailure(candidate, 'RelayNode target attestation mismatch');
                      }
                      openRemoteCandidate(entry);
                      return;
                    }
                    clearCandidateTimeout(entry);
                    entry.connected = true;
                    entry.directNegotiating = false;
                    if (entry.relayPreparationTimer) {
                      clearTimeout(entry.relayPreparationTimer);
                      entry.relayPreparationTimer = 0;
                    }
                    if (candidate.direct) {
                      forgetDirectPluginUnavailable(entry, candidate);
                      state.stats.directConnected++;
                    }
                    else recordRelayNodeSuccess(entry, candidate);
                    state.stats.connected++;
                    recordConnectPhase(
                      entry,
                      candidate.direct ? 'direct-connected' : 'relay-connected',
                      candidate.url
                    );
                    requestFlush(entry);
                  }
                } catch (ignored) {}
                return;
              }
              if (event.data instanceof ArrayBuffer) {
                deliverInbound(entry, event.data);
              } else if (event.data && typeof event.data.arrayBuffer === 'function') {
                event.data.arrayBuffer().then(function(buffer) {
                  if (generation !== entry.webSocketGeneration || entry.closed) return;
                  deliverInbound(entry, buffer);
                }, function(error) {
                  if (generation !== entry.webSocketGeneration || entry.closed) return;
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
              if (generation !== entry.webSocketGeneration || entry.closed) return;
              clearCandidateTimeout(entry);
              if (!entry.connected) {
                entry.directNegotiating = false;
                recordConnectPhase(
                  entry,
                  candidate.direct ? 'direct-failed' : 'relay-failed',
                  candidate.url
                );
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
              entry.connected = false;
              if (typeof state.cancelOutboundFlush === 'function') {
                state.cancelOutboundFlush(entry);
              }
              discardOutboundControls(entry);
              discardOutboundData(entry);
              state.stats.closed++;
              if (event && event.code && event.code !== 1000 && entry.errors.length === 0) {
                entry.errors.push(
                  'WebSocket transport closed: ' + event.code + ' ' + (event.reason || '')
                );
                if (entry.errors.length > 16) {
                  entry.errors.splice(0, entry.errors.length - 16);
                }
                state.stats.errors++;
              }
              if (typeof state.retireClosedEntry === 'function') {
                state.retireClosedEntry(entry);
              }
            };
            }
            state.open = function(id, host, port) {
            const key = id|0;
            const normalizedHost = String(host);
            const normalizedPort = port|0;
            const existing = state.channels.get(key);
            if (existing) {
              if (!existing.closed && existing.host === normalizedHost &&
                  existing.port === normalizedPort) {
                if (localSession(normalizedHost) !== null) {
                  state.stats.localDuplicateOpens++;
                }
                return;
              }
              releaseTargetRelayLease(existing);
              clearLocalClaim(existing);
              clearRelayPreparation(existing);
              forgetLocalOwner(existing);
              if (existing.retireClosedHandle) {
                clearTimeout(existing.retireClosedHandle);
                existing.retireClosedHandle = 0;
              }
              existing.retireClosedPending = false;
              state.discardInbound(existing);
              existing.disposed = true;
              existing.closed = true;
              state.stopEventLoopGapProbeIfIdle();
              try { if (existing.ws) existing.ws.close(); } catch (ignored) {}
              if (typeof state.cancelOutboundFlush === 'function') {
                state.cancelOutboundFlush(existing);
              }
              discardOutboundControls(existing);
              if (existing.localPort) {
                queueControl(existing, {type: 'close'}, existing.localPort, true);
              }
              discardOutboundData(existing);
            }
            const entry = {
              id: key,
              host: normalizedHost,
              port: normalizedPort,
              targetKey: normalizedTargetKey(host, port),
              ws: null,
              localPort: null,
              localSessionId: null,
              localClaimTimer: 0,
              localClaimGeneration: 0,
              localLaunchGeneration: '',
              localGenerationRequired: false,
              localClaimDeadline: 0,
              localActivity: false,
              connected: false,
              remotePaused: false,
              candidateTimeout: 0,
              outboundFlushScheduled: false,
              outboundFlushHandle: 0,
              outboundFlushGeneration: 0,
              outboundFlushKind: '',
              inbound: [],
              inboundHead: 0,
              inboundBytes: 0,
              pendingInbound: [],
              pendingInboundHead: 0,
              pendingInboundBytes: 0,
              inboundSliceScheduled: false,
              inboundSliceHandle: null,
              inboundSliceUsesRaf: false,
              inboundSliceScheduledAt: 0,
              inboundSliceSchedulerKind: '',
              inboundSliceGeneration: 0,
              inboundSliceMessageChannel: null,
              inboundSliceMessageCallback: null,
              decodedSliceBacklog: 0,
              decoderCumulationBytes: 0,
              decodeFlowPaused: false,
              highWatermarkStartedAt: 0,
              highWatermarkEpisode: null,
              outbound: [],
              outboundHead: 0,
              outboundControls: [],
              outboundControlHead: 0,
              queuedControlBytes: 0,
              errors: [],
              closed: false,
              disposed: false,
              remoteClosedAt: 0,
              retireClosedHandle: 0,
              retireClosedPending: false,
              flowPaused: false,
              queuedBytes: 0,
              queuedFrames: 0,
              candidates: [],
              candidateUrls: new Set(),
              candidateIndex: 0,
              currentCandidate: null,
              webSocketGeneration: 0,
              connectStartedAt: 0,
              directNegotiating: false,
              relayPreparationStarted: false,
              relayPreparationTimer: 0,
              relayPreflightReady: false,
              relayPreflightWaitStarted: false,
              relayPreflightPromise: Promise.resolve(),
              relaySelectionReady: false,
              relaySelectionTimer: 0,
              relaySelectionPromise: Promise.resolve(),
              relayExhaustionWaitStarted: false,
              relayTargetLeaseKey: null
            };
            state.channels.set(key, entry);
            state.stats.opened++;
            state.scheduleEventLoopGapProbe();
            const sessionId = localSession(entry.host);
            if (sessionId !== null) {
              claimLocalPort(
                entry,
                sessionId,
                localWorkerGeneration(sessionId),
                true
              );
              return;
            }
            recordConnectPhase(entry, 'open');
            const directUrl = directPluginUrl(entry.host);
            if (directUrl) {
              entry.candidates.push({url: directUrl, direct: true});
              entry.candidateUrls.add(directUrl);
              scheduleRelayPreparation(entry);
            } else {
              ensureRelayCandidates(entry);
            }
            openRemoteCandidate(entry);
            };
            state.send = function(id, data) {
            const entry = state.channels.get(id|0);
            if (!entry || entry.closed) return false;
            const source = data
              ? new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength || 0)
              : new Uint8Array(0);
            const frameCount = source.byteLength === 0
              ? 1
              : Math.ceil(source.byteLength / maximumOutboundBytesPerTurn);
            if (entry.queuedBytes + source.byteLength > maximumOutboundQueueBytes ||
                entry.queuedFrames + frameCount > maximumOutboundQueueFrames) {
              // Keep the ByteBuf in Netty's outbound buffer. Java retries after the browser
              // queue drains; failing this call must never remove protocol bytes silently.
              entry.outboundBackpressured = true;
              state.stats.outboundBackpressureDeferrals++;
              requestFlush(entry, webSocketBackpressureRetryMs);
              return false;
            }
            entry.outboundBackpressured = false;
            if (source.byteLength === 0) {
              entry.outbound.push(new Uint8Array(0));
            } else {
              for (let offset = 0; offset < source.byteLength;
                   offset += maximumOutboundBytesPerTurn) {
                const length = Math.min(
                  maximumOutboundBytesPerTurn,
                  source.byteLength - offset
                );
                const copy = new Uint8Array(length);
                copy.set(source.subarray(offset, offset + length));
                entry.outbound.push(copy);
              }
            }
            entry.queuedBytes += source.byteLength;
            entry.queuedFrames += frameCount;
            state.stats.queuedBytes += source.byteLength;
            state.stats.queuedFrames += frameCount;
            state.stats.peakQueuedFrames = Math.max(
              state.stats.peakQueuedFrames,
              state.stats.queuedFrames
            );
            requestFlush(entry);
            return !entry.closed;
            };
            state.pollInbound = function(id) {
            return state.pollInboundScheduled(id, requestFlush);
            };
            state.pollError = function(id) {
            const entry = state.channels.get(id|0);
            if (!entry || entry.errors.length === 0) return null;
            return String(entry.errors.shift());
            };
            state.recordPump = function(id, chunks, bytes, millis) {
            state.recordPumpTelemetry(id, chunks, bytes, millis);
            };
            state.recordDecodedSlice = function(id, bytes) {
            state.recordDecodedSliceScheduled(id, bytes);
            };
            state.finishDecodedSlice = function(id) {
            state.finishDecodedSliceScheduled(id);
            };
            state.recordDecodedPacketQueue = function(
                depth, paused, processed, handleMillis, handleType) {
            state.recordDecodedPacketQueueScheduled(
                depth, paused, processed, handleMillis, handleType);
            };
            state.recordInlineDecodedPacket = function() {
            state.recordInlineDecodedPacketScheduled();
            };
            state.close = function(id) {
            const entry = state.channels.get(id|0);
            if (!entry) return;
            if (entry.retireClosedHandle) {
              clearTimeout(entry.retireClosedHandle);
              entry.retireClosedHandle = 0;
            }
            entry.retireClosedPending = false;
            releaseTargetRelayLease(entry);
            clearLocalClaim(entry);
            clearRelayPreparation(entry);
            forgetLocalOwner(entry);
            if (typeof state.cancelOutboundFlush === 'function') {
              state.cancelOutboundFlush(entry);
            }
            discardOutboundControls(entry);
            if (entry.localPort) queueControl(entry, {type: 'close'}, entry.localPort, true);
            discardOutboundData(entry);
            entry.disposed = true;
            entry.closed = true;
            entry.connected = false;
            state.discardInbound(entry);
            try { if (entry.ws) entry.ws.close(); } catch (ignored) {}
            entry.localPort = null;
            state.channels.delete(id|0);
            state.stopEventLoopGapProbeIfIdle();
            };
            state.closed = function(id) {
            const entry = state.channels.get(id|0);
            return !entry || !!entry.closed;
            };
            state.hasPendingInbound = function(id) {
            return state.hasPendingInboundScheduled(id);
            };
            state.hasPumpableInbound = function(id) {
            return state.hasPumpableInboundScheduled(id);
            };
            state.failLocalSession = function(sessionId, message, launchGeneration) {
            sessionId = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            if (!/^[1-9][0-9]*$/.test(expectedGeneration)) return false;
            state.channels.forEach(function(entry) {
              if (localSession(entry.host) === sessionId &&
                  String(entry.localLaunchGeneration || '') === expectedGeneration) {
                fail(entry, message || 'Local server Worker stopped unexpectedly');
              }
            });
            return true;
            };
            state.registerLocalPort = function(sessionId, port, launchGeneration) {
            sessionId = String(sessionId || '');
            if (!sessionId || !port) return false;
            const generation = String(
              launchGeneration || ''
            );
            if (!/^[1-9][0-9]*$/.test(generation)) return false;
            let extensible = false;
            try { extensible = Object.isExtensible(port); } catch (ignored) {}
            if (!extensible || localPortGeneration(port) !== generation) return false;
            if (!activeLocalPortOwner(sessionId, generation, port)) return false;
            const existingPorts = globalThis.__gaiusLocalServerPorts;
            const existingPort = existingPorts && typeof existingPorts.get === 'function'
              ? existingPorts.get(sessionId)
              : existingPorts && existingPorts[sessionId];
            if (existingPort && existingPort !== port) return false;
            let ports = globalThis.__gaiusLocalServerPorts;
            if (!ports) {
              ports = new Map();
              globalThis.__gaiusLocalServerPorts = ports;
            }
            if (typeof ports.set === 'function') ports.set(sessionId, port);
            else ports[sessionId] = port;
            notifyLocalPortAvailable(sessionId);
            return true;
            };
            state.refreshRelayRegistry = function() {
            state.relayRegistryCache.clear();
            state.relayRegistryPromise = discoverRelayNodes();
            return state.relayRegistryPromise;
            };
            // Lightweight defaults keep source-level bridge harnesses operational. The TeaVM
            // constructor immediately replaces these with the bounded scheduler below.
            state.scheduleEventLoopGapProbe = function() {};
            state.stopEventLoopGapProbeIfIdle = function() {};
            state.startHighWatermark = function() {};
            state.finishHighWatermark = function() {};
            state.discardInbound = function(entry) {
            if (!entry) return;
            state.finishHighWatermark(entry);
            state.stats.inboundQueuedBytes = Math.max(
              0,
              state.stats.inboundQueuedBytes - entry.inboundBytes - entry.pendingInboundBytes
            );
            entry.inbound = [];
            entry.inboundHead = 0;
            entry.inboundBytes = 0;
            entry.pendingInbound = [];
            entry.pendingInboundHead = 0;
            entry.pendingInboundBytes = 0;
            entry.flowPaused = false;
            };
            state.deliverInbound = function(entry, buffer) {
            if (!entry || entry.closed) return;
            const bytes = buffer instanceof ArrayBuffer
              ? new Uint8Array(buffer)
              : new Uint8Array(buffer.buffer, buffer.byteOffset || 0, buffer.byteLength || 0);
            entry.inbound.push(bytes);
            entry.inboundBytes += bytes.byteLength;
            state.stats.inboundQueuedBytes += bytes.byteLength;
            state.stats.receivedFrames++;
            state.stats.receivedBytes += bytes.byteLength;
            };
            state.pollInboundScheduled = function(id, requestFlush) {
            const entry = state.channels.get(id|0);
            if (!entry || entry.inboundHead >= entry.inbound.length) return null;
            requestFlush(entry);
            const chunk = entry.inbound[entry.inboundHead++];
            entry.inboundBytes = Math.max(0, entry.inboundBytes - chunk.byteLength);
            state.stats.inboundQueuedBytes = Math.max(
              0,
              state.stats.inboundQueuedBytes - chunk.byteLength
            );
            if (entry.inboundHead >= entry.inbound.length) {
              entry.inbound = [];
              entry.inboundHead = 0;
            }
            return new Int8Array(chunk.buffer, chunk.byteOffset || 0, chunk.byteLength);
            };
            state.recordPumpTelemetry = function() {};
            state.recordDecodedSliceScheduled = function() {};
            state.finishDecodedSliceScheduled = function() {};
            state.recordDecodedPacketQueueScheduled = function() {};
            state.recordInlineDecodedPacketScheduled = function() {};
            state.hasPendingInboundScheduled = function(id) {
            const entry = state.channels.get(id|0);
            return !!entry && entry.inboundHead < entry.inbound.length;
            };
            state.hasPumpableInboundScheduled = function(id) {
            const entry = state.channels.get(id|0);
            return !!entry && !entry.disposed && !state.exactPacketQueuePaused &&
              entry.inboundHead < entry.inbound.length;
            };
            state.setInboundPaused = setInboundPaused;
            state.failInbound = fail;
            localPortMap();
            globalThis.__gaiusNettyBridge = state;
            globalThis.__gaiusNetworkStats = state.stats;
            delete globalThis.__gaiusNettyBridgeBootstrapState;
            delete globalThis.__gaiusNettyBridgeBootstrapScope;
            """)
    private static native void initBridgeTail();

    @JSBody(script = """
            const state = globalThis.__gaiusNettyBridge;
            if (!state || state.outboundSchedulerReady) return;
            state.outboundSchedulerReady = true;
            const maximumWebSocketBufferedBytes = 4 * 1024 * 1024;
            const maximumOutboundFramesPerTurn = 32;
            const maximumOutboundBytesPerTurn = 256 * 1024;
            const maximumOutboundMillisPerTurn = 2;
            const maximumOutboundControlFrames = 64;
            const maximumOutboundControlBytes = 64 * 1024;
            const webSocketBackpressureRetryMs = 4;
            const maximumLocalBatchBytes = 16 * 1024;
            function now() {
              return typeof performance !== 'undefined' && performance.now
                ? performance.now()
                : Date.now();
            }
            function encodedBytes(value) {
              const text = String(value || '');
              if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(text).byteLength;
              return text.length;
            }
            function compactQueue(entry, name, headName) {
              const queue = entry[name];
              const head = entry[headName];
              if (head >= queue.length) {
                entry[name] = [];
                entry[headName] = 0;
              } else if (head >= 256 && head * 2 >= queue.length) {
                entry[name] = queue.slice(head);
                entry[headName] = 0;
              }
            }
            function discardData(entry) {
              for (let index = entry.outboundHead; index < entry.outbound.length; index++) {
                entry.outbound[index] = null;
              }
              state.stats.queuedBytes = Math.max(0, state.stats.queuedBytes - entry.queuedBytes);
              state.stats.queuedFrames = Math.max(0, state.stats.queuedFrames - entry.queuedFrames);
              entry.queuedBytes = 0;
              entry.queuedFrames = 0;
              entry.outbound = [];
              entry.outboundHead = 0;
            }
            function discardControls(entry) {
              for (let index = entry.outboundControlHead;
                   index < entry.outboundControls.length;
                   index++) {
                entry.outboundControls[index] = null;
              }
              state.stats.queuedControlBytes = Math.max(
                0,
                state.stats.queuedControlBytes - entry.queuedControlBytes
              );
              entry.queuedControlBytes = 0;
              entry.outboundControls = [];
              entry.outboundControlHead = 0;
            }
            function releaseControl(entry, item) {
              if (item) {
                entry.queuedControlBytes = Math.max(
                  0,
                  entry.queuedControlBytes - item.bytes
                );
                state.stats.queuedControlBytes = Math.max(
                  0,
                  state.stats.queuedControlBytes - item.bytes
                );
              }
              entry.outboundControls[entry.outboundControlHead] = null;
              entry.outboundControlHead++;
            }
            function controlIsCurrent(entry, item) {
              if (item.closeAfterSend) return true;
              if (item.local) return item.target === entry.localPort;
              return !entry.closed && item.target === entry.ws &&
                item.generation === entry.webSocketGeneration &&
                item.target.readyState <= WebSocket.OPEN;
            }
            function webSocketBlocked(target) {
              return target && target.bufferedAmount >= maximumWebSocketBufferedBytes;
            }
            // A zero-delay setTimeout continuation is eventually clamped to roughly 4 ms by
            // browsers. Large multiplayer packet bursts can need several bounded turns, so the
            // clamp becomes visible as avoidable movement/input latency. MessageChannel keeps a
            // real macrotask boundary (rendering and inbound network tasks may run between turns)
            // without creating a chain of clamped timers. Process exactly one callback per
            // message task; generation guards in requestFlush make queued close-race callbacks
            // harmless. The fallback remains the old timer path for unusual runtimes.
            const outboundContinuationScheduler = (function() {
              if (typeof MessageChannel !== 'function') return null;
              try {
                const channel = new MessageChannel();
                const callbacks = [];
                let head = 0;
                let posted = false;
                channel.port1.onmessage = function() {
                  posted = false;
                  const callback = head < callbacks.length ? callbacks[head++] : null;
                  if (head >= callbacks.length) {
                    callbacks.length = 0;
                    head = 0;
                  } else {
                    posted = true;
                    channel.port2.postMessage(0);
                  }
                  if (callback) {
                    state.stats.outboundMessageChannelCallbacks++;
                    callback();
                  }
                };
                if (typeof channel.port1.start === 'function') channel.port1.start();
                if (typeof channel.port1.unref === 'function') channel.port1.unref();
                if (typeof channel.port2.unref === 'function') channel.port2.unref();
                return function(callback) {
                  callbacks.push(callback);
                  if (!posted) {
                    posted = true;
                    channel.port2.postMessage(0);
                  }
                };
              } catch (_error) {
                return null;
              }
            })();
            function hasFlushableOutbound(entry) {
              if (!entry) return false;
              if (entry.outboundControlHead < entry.outboundControls.length) return true;
              return entry.connected && !entry.closed && !entry.remotePaused &&
                entry.outboundHead < entry.outbound.length;
            }
            function requestFlush(entry, delayMillis, continuation) {
              if (!entry || entry.outboundFlushScheduled || !hasFlushableOutbound(entry)) return;
              const delay = Math.max(0, delayMillis|0);
              const generation = (entry.outboundFlushGeneration || 0) + 1;
              const requestedAt = now();
              entry.outboundFlushGeneration = generation;
              entry.outboundFlushScheduled = true;
              const run = function() {
                if (!entry.outboundFlushScheduled ||
                    entry.outboundFlushGeneration !== generation) return;
                entry.outboundFlushScheduled = false;
                entry.outboundFlushHandle = 0;
                entry.outboundFlushKind = '';
                state.stats.outboundFlushWaitSamples++;
                state.stats.maxOutboundFlushWaitMillis = Math.max(
                  state.stats.maxOutboundFlushWaitMillis,
                  Math.max(0, now() - requestedAt)
                );
                flush(entry);
              };
              if (!continuation && delay === 0 && typeof queueMicrotask === 'function') {
                entry.outboundFlushKind = 'microtask';
                state.stats.outboundImmediateFlushes++;
                queueMicrotask(run);
                return;
              }
              if (continuation && delay === 0 && outboundContinuationScheduler) {
                entry.outboundFlushKind = 'message-channel';
                state.stats.outboundMessageChannelFlushes++;
                state.stats.outboundContinuationMacrotasks++;
                outboundContinuationScheduler(run);
                return;
              }
              entry.outboundFlushKind = 'timer';
              state.stats.outboundTimerFlushes++;
              if (continuation) {
                state.stats.outboundContinuationTimers++;
                state.stats.outboundContinuationMacrotasks++;
              }
              entry.outboundFlushHandle = setTimeout(run, delay);
            }
            function cancelFlush(entry) {
              if (!entry) return;
              entry.outboundFlushGeneration = (entry.outboundFlushGeneration || 0) + 1;
              if (entry.outboundFlushKind === 'timer') {
                clearTimeout(entry.outboundFlushHandle);
              }
              entry.outboundFlushScheduled = false;
              entry.outboundFlushHandle = 0;
              entry.outboundFlushKind = '';
            }
            function queueControl(entry, message, target, closeAfterSend, generation) {
              if (!entry || !target) return;
              const serialized = JSON.stringify(message);
              const byteLength = encodedBytes(serialized);
              if (byteLength > maximumOutboundBytesPerTurn) {
                state.failInbound(entry, 'Browser bridge control frame exceeded 256 KiB');
                return;
              }
              const queuedControlFrames = entry.outboundControls.length - entry.outboundControlHead;
              if (queuedControlFrames >= maximumOutboundControlFrames ||
                  entry.queuedControlBytes + byteLength > maximumOutboundControlBytes) {
                state.stats.controlQueueOverflows++;
                state.failInbound(entry, 'Browser bridge control queue exceeded its limit');
                return;
              }
              entry.outboundControls.push({
                target: target,
                local: target === entry.localPort || closeAfterSend,
                payload: target === entry.localPort || closeAfterSend ? message : serialized,
                bytes: byteLength,
                closeAfterSend: !!closeAfterSend,
                generation: generation === undefined ? entry.webSocketGeneration : generation
              });
              entry.queuedControlBytes += byteLength;
              state.stats.queuedControlBytes += byteLength;
              state.stats.peakQueuedControlBytes = Math.max(
                state.stats.peakQueuedControlBytes,
                state.stats.queuedControlBytes
              );
              requestFlush(entry);
            }
            function flush(entry) {
              const startedAt = now();
              let outboundFrames = 0;
              let outboundBytes = 0;
              let webSocketBackpressured = false;
              let localFlushFrames = 0;
              let localFlushBytes = 0;
              let localFlushBatches = 0;
              function budgetAvailable(nextBytes) {
                if (outboundFrames >= maximumOutboundFramesPerTurn) return false;
                if (outboundFrames > 0 &&
                    outboundBytes + nextBytes > maximumOutboundBytesPerTurn) return false;
                return outboundFrames === 0 ||
                  now() - startedAt < maximumOutboundMillisPerTurn;
              }
              while (entry.outboundControlHead < entry.outboundControls.length) {
                const item = entry.outboundControls[entry.outboundControlHead];
                if (!item) {
                  releaseControl(entry, null);
                  continue;
                }
                if (!budgetAvailable(item.bytes)) break;
                if (!controlIsCurrent(entry, item)) {
                  releaseControl(entry, item);
                  outboundFrames++;
                  continue;
                }
                if (!item.local) {
                  if (!item.target || item.target.readyState !== WebSocket.OPEN) break;
                  if (webSocketBlocked(item.target)) {
                    webSocketBackpressured = true;
                    break;
                  }
                }
                try {
                  if (item.local) {
                    item.target.postMessage(item.payload);
                    state.stats.localMessagePortSends++;
                    if (item.closeAfterSend && typeof item.target.close === 'function') {
                      item.target.close();
                    }
                  } else {
                    item.target.send(item.payload);
                    state.stats.webSocketSends++;
                  }
                } catch (error) {
                  releaseControl(entry, item);
                  state.failInbound(entry, error && (error.message || error));
                  break;
                }
                outboundFrames++;
                outboundBytes += item.bytes;
                releaseControl(entry, item);
              }
              compactQueue(entry, 'outboundControls', 'outboundControlHead');

              while (entry.connected && !entry.closed && !entry.remotePaused &&
                     entry.outboundHead < entry.outbound.length) {
                const first = entry.outbound[entry.outboundHead];
                if (!first) {
                  entry.outboundHead++;
                  continue;
                }
                if (!budgetAvailable(first.byteLength)) break;
                if (entry.localPort) {
                  const localBatchParts = [];
                  const localBatchStart = entry.outboundHead;
                  let localBatchBytes = 0;
                  let localBatchFrames = 0;
                  while (entry.outboundHead + localBatchFrames < entry.outbound.length) {
                    const queuedPart = entry.outbound[entry.outboundHead + localBatchFrames];
                    if (!queuedPart) {
                      localBatchFrames++;
                      continue;
                    }
                    if (!budgetAvailable(localBatchBytes + queuedPart.byteLength)) break;
                    if (localBatchBytes > 0 &&
                        localBatchBytes + queuedPart.byteLength > maximumLocalBatchBytes) break;
                    localBatchParts.push(queuedPart);
                    localBatchBytes += queuedPart.byteLength;
                    localBatchFrames++;
                    if (outboundFrames + localBatchFrames >= maximumOutboundFramesPerTurn) break;
                  }
                  if (localBatchFrames === 0) break;
                  const batch = new Uint8Array(localBatchBytes);
                  let offset = 0;
                  for (let partIndex = 0; partIndex < localBatchParts.length; partIndex++) {
                    const part = localBatchParts[partIndex];
                    batch.set(part, offset);
                    offset += part.byteLength;
                  }
                  try {
                    entry.localPort.postMessage(batch.buffer, [batch.buffer]);
                    state.stats.localMessagePortSends++;
                  } catch (error) {
                    state.failInbound(entry, error && (error.message || error));
                    break;
                  }
                  entry.localActivity = true;
                  localFlushBatches++;
                  localFlushFrames += localBatchFrames;
                  localFlushBytes += localBatchBytes;
                  for (let index = localBatchStart;
                       index < localBatchStart + localBatchFrames;
                       index++) {
                    const sentPart = entry.outbound[index];
                    if (sentPart) {
                      entry.queuedBytes -= sentPart.byteLength;
                      state.stats.queuedBytes = Math.max(
                        0,
                        state.stats.queuedBytes - sentPart.byteLength
                      );
                      entry.queuedFrames = Math.max(0, entry.queuedFrames - 1);
                      state.stats.queuedFrames = Math.max(0, state.stats.queuedFrames - 1);
                      state.stats.sentFrames++;
                      state.stats.sentBytes += sentPart.byteLength;
                    }
                    entry.outbound[index] = null;
                  }
                  entry.outboundHead += localBatchFrames;
                  outboundFrames += localBatchFrames;
                  outboundBytes += localBatchBytes;
                } else {
                  if (!entry.ws || entry.ws.readyState !== WebSocket.OPEN) break;
                  if (webSocketBlocked(entry.ws)) {
                    webSocketBackpressured = true;
                    break;
                  }
                  try {
                    entry.ws.send(first);
                    state.stats.webSocketSends++;
                  } catch (error) {
                    state.failInbound(entry, error && (error.message || error));
                    break;
                  }
                  entry.queuedBytes -= first.byteLength;
                  state.stats.queuedBytes = Math.max(
                    0,
                    state.stats.queuedBytes - first.byteLength
                  );
                  entry.queuedFrames = Math.max(0, entry.queuedFrames - 1);
                  state.stats.queuedFrames = Math.max(0, state.stats.queuedFrames - 1);
                  state.stats.sentFrames++;
                  state.stats.sentBytes += first.byteLength;
                  entry.outbound[entry.outboundHead] = null;
                  entry.outboundHead++;
                  outboundFrames++;
                  outboundBytes += first.byteLength;
                }
              }
              compactQueue(entry, 'outbound', 'outboundHead');
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
              const elapsed = Math.max(0, now() - startedAt);
              state.stats.outboundTurns++;
              state.stats.outboundTurnFrames += outboundFrames;
              state.stats.outboundTurnBytes += outboundBytes;
              if (outboundFrames === 0) state.stats.outboundEmptyTurns++;
              state.stats.maxOutboundTurnFrames = Math.max(
                state.stats.maxOutboundTurnFrames,
                outboundFrames
              );
              state.stats.maxOutboundTurnBytes = Math.max(
                state.stats.maxOutboundTurnBytes,
                outboundBytes
              );
              state.stats.maxOutboundTurnMillis = Math.max(
                state.stats.maxOutboundTurnMillis,
                elapsed
              );
              const controlsPending = entry.outboundControlHead < entry.outboundControls.length;
              const dataPending = entry.outboundHead < entry.outbound.length;
              if (webSocketBackpressured) {
                state.stats.webSocketBackpressureWaits++;
                requestFlush(entry, webSocketBackpressureRetryMs);
              } else if (controlsPending ||
                         (dataPending && entry.connected && !entry.remotePaused && !entry.closed)) {
                state.stats.outboundYields++;
                // The first idle -> active transition uses a microtask to avoid the browser's
                // zero-delay timer clamp. Once a bounded turn exhausts its frame/byte/time
                // budget, continue on a macrotask so rendering and inbound network tasks keep
                // their event-loop turn.
                requestFlush(entry, 0, true);
              }
            }
            state.discardOutboundData = discardData;
            state.discardOutboundControls = discardControls;
            state.queueOutboundControl = queueControl;
            state.requestOutboundFlush = requestFlush;
            state.cancelOutboundFlush = cancelFlush;
            """)
    private static native void initOutboundScheduler();

    @JSBody(script = """
            const state = globalThis.__gaiusNettyBridge;
            if (!state || state.inboundSchedulerReady) return;
            state.inboundSchedulerReady = true;
            const maximumInboundQueueBytes = 64 * 1024 * 1024;
            const inboundPauseBytes = 24 * 1024 * 1024;
            const inboundResumeBytes = 8 * 1024 * 1024;
            // A 64 KiB compressed chunk handoff took 365 ms in a real 26.2 multiplayer burst.
            // The elapsed-time guard is necessarily checked after that first handoff, so cap the
            // non-preemptible unit itself rather than pretending the 2 ms turn budget can stop it.
            const maximumInboundSliceBytes = 4 * 1024;
            const decodedSliceHighWatermark = 256;
            const decodedSliceLowWatermark = 64;
            const decoderCumulationPauseBytes = 12 * 1024 * 1024;
            const maximumDecoderCumulationBytes = 16 * 1024 * 1024;
            const inboundSliceBudgetMillis = 2.0;
            const eventLoopGapIntervalMillis = 100;
            const maximumHighWatermarkEvents = 64;
            const maximumSlowQueuedPacketEvents = 64;
            const slowQueuedPacketThresholdMillis = 50;
            // A remote close can race the final Java decoder handoff. Retire it after a short,
            // bounded grace period so a missing next tick cannot leave a dead channel in the map.
            const remoteCloseRetireGraceMillis = 5000;
            const remoteCloseRetireRetryMillis = 16;
            function now() {
              return typeof performance !== 'undefined' && performance.now
                ? performance.now()
                : Date.now();
            }
            function sliceCount(entry) {
              return Math.max(0, entry.inbound.length - entry.inboundHead);
            }
            function queuedBytes(entry) {
              return Math.max(0, entry.inboundBytes + entry.pendingInboundBytes);
            }
            function workDepth(entry) {
              return sliceCount(entry);
            }
            function refreshHighWatermark() {
              const sampledAt = now();
              const activeCount = Math.max(
                0,
                Math.floor(Number(state.activeHighWatermarkStartCount) || 0)
              );
              const startSum = Number(state.activeHighWatermarkStartSumMillis);
              state.stats.activeHighWatermarkMillis = activeCount > 0 &&
                  Number.isFinite(startSum)
                ? Math.max(0, sampledAt * activeCount - startSum)
                : 0;
            }
            function boundedCount(value) {
              return Math.max(0, Math.floor(Number(value) || 0));
            }
            state.startHighWatermark = function(entry, reason, depth, queuedByteCount) {
              if (!entry || entry.highWatermarkEpisode) return;
              const startedAtMillis = now();
              const sequence = boundedCount(state.stats.highWatermarkEventSequence) + 1;
              const normalizedReason = reason === 'inbound-bytes' ||
                  reason === 'exact-packet-queue'
                ? reason
                : 'inbound-slice-depth';
              state.stats.highWatermarkEventSequence = sequence;
              entry.highWatermarkEpisode = {
                sequence: sequence,
                channelId: entry.id|0,
                reason: normalizedReason,
                startedAtMillis: startedAtMillis,
                startDepth: boundedCount(depth),
                startQueuedBytes: boundedCount(queuedByteCount),
                startDecodedPacketQueue: boundedCount(state.stats.decodedPacketQueue),
                startDecodedPacketDrainSignals: boundedCount(
                  state.stats.decodedPacketDrainSignals
                ),
                startPumpCalls: boundedCount(state.stats.pumpCalls),
                startInboundSlicePumps: boundedCount(state.stats.inboundSlicePumps),
                startInboundPumpJavaCompleted: boundedCount(
                  state.stats.inboundPumpJavaCompleted
                ),
                startExactPacketQueuePaused: !!state.exactPacketQueuePaused
              };
              entry.highWatermarkStartedAt = startedAtMillis;
              state.activeHighWatermarkStartCount = Math.min(
                Number.MAX_SAFE_INTEGER,
                (Number(state.activeHighWatermarkStartCount) || 0) + 1
              );
              state.activeHighWatermarkStartSumMillis =
                (Number(state.activeHighWatermarkStartSumMillis) || 0) + startedAtMillis;
              state.stats.activeHighWatermarks++;
              refreshHighWatermark();
            };
            state.finishHighWatermark = function(entry, depth, queuedByteCount) {
              if (!entry || !entry.highWatermarkEpisode) return;
              const episode = entry.highWatermarkEpisode;
              const endedAtMillis = now();
              const duration = Math.max(0, endedAtMillis - episode.startedAtMillis);
              const event = {
                sequence: episode.sequence,
                channelId: episode.channelId,
                reason: episode.reason,
                startedAtMillis: episode.startedAtMillis,
                endedAtMillis: endedAtMillis,
                durationMillis: duration,
                startDepth: episode.startDepth,
                endDepth: boundedCount(depth),
                startQueuedBytes: episode.startQueuedBytes,
                endQueuedBytes: boundedCount(queuedByteCount),
                startDecodedPacketQueue: episode.startDecodedPacketQueue,
                endDecodedPacketQueue: boundedCount(state.stats.decodedPacketQueue),
                startDecodedPacketDrainSignals: episode.startDecodedPacketDrainSignals,
                endDecodedPacketDrainSignals: boundedCount(
                  state.stats.decodedPacketDrainSignals
                ),
                startPumpCalls: episode.startPumpCalls,
                endPumpCalls: boundedCount(state.stats.pumpCalls),
                startInboundSlicePumps: episode.startInboundSlicePumps,
                endInboundSlicePumps: boundedCount(state.stats.inboundSlicePumps),
                startInboundPumpJavaCompleted: episode.startInboundPumpJavaCompleted,
                endInboundPumpJavaCompleted: boundedCount(
                  state.stats.inboundPumpJavaCompleted
                ),
                startExactPacketQueuePaused: !!episode.startExactPacketQueuePaused,
                endExactPacketQueuePaused: !!state.exactPacketQueuePaused
              };
              entry.highWatermarkEpisode = null;
              entry.highWatermarkStartedAt = 0;
              const startedAt = Number(episode.startedAtMillis);
              state.activeHighWatermarkStartCount = Math.max(
                0,
                (Number(state.activeHighWatermarkStartCount) || 0) - 1
              );
              if (Number.isFinite(startedAt) && startedAt >= 0) {
                state.activeHighWatermarkStartSumMillis = Math.max(
                  0,
                  (Number(state.activeHighWatermarkStartSumMillis) || 0) - startedAt
                );
              }
              state.stats.activeHighWatermarks = Math.max(
                0,
                state.stats.activeHighWatermarks - 1
              );
              state.stats.highWatermarkDurationMillis += duration;
              state.stats.longestHighWatermarkMillis = Math.max(
                state.stats.longestHighWatermarkMillis,
                duration
              );
              if (!Array.isArray(state.stats.highWatermarkEvents)) {
                state.stats.highWatermarkEvents = [];
              }
              state.stats.highWatermarkEvents.push(event);
              state.stats.highWatermarkEvents.sort(function(left, right) {
                return left.sequence - right.sequence;
              });
              if (state.stats.highWatermarkEvents.length > maximumHighWatermarkEvents) {
                const dropped = state.stats.highWatermarkEvents.length -
                  maximumHighWatermarkEvents;
                state.stats.highWatermarkEvents.splice(0, dropped);
                state.stats.highWatermarkEventsDropped = boundedCount(
                  state.stats.highWatermarkEventsDropped
                ) + dropped;
              }
              refreshHighWatermark();
            };
            function hasActiveChannels() {
              let active = false;
              state.channels.forEach(function(entry) {
                if (!entry.disposed) active = true;
              });
              return active;
            }
            state.scheduleEventLoopGapProbe = function() {
              if (state.gapProbeTimer || !hasActiveChannels()) return;
              state.gapProbeExpectedAt = now() + eventLoopGapIntervalMillis;
              state.gapProbeTimer = setTimeout(function() {
                state.gapProbeTimer = 0;
                const gap = Math.max(0, now() - state.gapProbeExpectedAt);
                state.stats.eventLoopGapSamples++;
                state.stats.longestEventLoopGapMillis = Math.max(
                  state.stats.longestEventLoopGapMillis,
                  gap
                );
                if (gap >= 500) state.stats.eventLoopGapsOver500++;
                state.scheduleEventLoopGapProbe();
              }, eventLoopGapIntervalMillis);
            };
            state.stopEventLoopGapProbeIfIdle = function() {
              if (hasActiveChannels()) return;
              if (state.gapProbeTimer) clearTimeout(state.gapProbeTimer);
              state.gapProbeTimer = 0;
              state.gapProbeExpectedAt = 0;
            };
            function signalInbound(reason) {
              const integratedPump = globalThis.__gaiusStartIntegratedServerPump;
              const integratedSignal = globalThis.__gaiusIntegratedServerNetworkSignal;
              if (typeof integratedPump === 'function') integratedPump();
              else if (typeof integratedSignal === 'function') integratedSignal();
              if (typeof state.inboundPump === 'function') {
                state.inboundPump(String(reason || 'requested'));
              }
            }
            function applyFlowControl(entry) {
              if (!entry || entry.disposed) return;
              const depth = workDepth(entry);
              const bytes = queuedBytes(entry);
              if (depth >= decodedSliceHighWatermark || bytes >= inboundPauseBytes ||
                  state.exactPacketQueuePaused) {
                const reason = state.exactPacketQueuePaused
                  ? 'exact-packet-queue'
                  : depth >= decodedSliceHighWatermark
                    ? 'inbound-slice-depth'
                    : 'inbound-bytes';
                if (!entry.decodeFlowPaused) {
                  entry.decodeFlowPaused = true;
                  state.stats.decodedSliceBacklogPauses++;
                }
                state.setInboundPaused(entry, true, reason, depth, bytes);
                return;
              }
              if (entry.decodeFlowPaused && !state.exactPacketQueuePaused &&
                  depth <= decodedSliceLowWatermark && bytes <= inboundResumeBytes) {
                // Decoder cumulation can be the unfinished tail of a length-prefixed packet.
                // Pausing the only TCP source until that tail shrinks is a self-deadlock: it
                // cannot shrink until the remaining packet bytes arrive.  Keep the independent
                // 16 MiB hard limit below, but gate transport pause/resume only on work that the
                // browser can actually drain while the relay is paused.
                entry.decodeFlowPaused = false;
                state.stats.decodedSliceBacklogResumes++;
                state.setInboundPaused(entry, false, null, depth, bytes);
              }
            }
            function compactPending(entry) {
              if (entry.pendingInboundHead >= entry.pendingInbound.length) {
                entry.pendingInbound = [];
                entry.pendingInboundHead = 0;
              } else if (entry.pendingInboundHead >= 128 &&
                         entry.pendingInboundHead * 2 >= entry.pendingInbound.length) {
                entry.pendingInbound = entry.pendingInbound.slice(entry.pendingInboundHead);
                entry.pendingInboundHead = 0;
              }
            }
            const maximumInboundContinuationStat = 0x7fffffff;
            function incrementInboundContinuationStat(name, amount) {
              const current = Number(state.stats[name]);
              const increment = Math.max(0, Math.floor(Number(amount) || 1));
              state.stats[name] = Math.min(
                maximumInboundContinuationStat,
                Math.max(0, Number.isFinite(current) ? current : 0) + increment
              );
            }
            function nextInboundSliceGeneration(value) {
              let generation = ((Number(value) || 0) + 1) >>> 0;
              if (generation === 0) generation = 1;
              return generation;
            }
            function closeInboundSliceMessageChannel(entry) {
              const channel = entry && entry.inboundSliceMessageChannel;
              if (!channel) return;
              try { channel.port1.onmessage = null; } catch (ignored) {}
              try { if (channel.port1.close) channel.port1.close(); } catch (ignored) {}
              try { if (channel.port2.close) channel.port2.close(); } catch (ignored) {}
              entry.inboundSliceMessageChannel = null;
              entry.inboundSliceMessageCallback = null;
            }
            function ensureInboundSliceMessageChannel(entry) {
              if (entry.inboundSliceMessageChannel) return true;
              if (typeof MessageChannel !== 'function') return false;
              let channel = null;
              try {
                channel = new MessageChannel();
                const owner = entry;
                channel.port1.onmessage = function(event) {
                  incrementInboundContinuationStat('inboundMessageChannelCallbacks');
                  const callback = owner.inboundSliceMessageCallback;
                  if (typeof callback !== 'function') {
                    incrementInboundContinuationStat('inboundContinuationStaleCallbacks');
                    return;
                  }
                  callback(Number(event && event.data) || 0);
                };
                if (typeof channel.port1.start === 'function') channel.port1.start();
                if (typeof channel.port1.unref === 'function') channel.port1.unref();
                if (typeof channel.port2.unref === 'function') channel.port2.unref();
                entry.inboundSliceMessageChannel = channel;
                return true;
              } catch (error) {
                incrementInboundContinuationStat('inboundMessageChannelFailures');
                if (channel) {
                  try { channel.port1.onmessage = null; } catch (ignored) {}
                  try { if (channel.port1.close) channel.port1.close(); } catch (ignored) {}
                  try { if (channel.port2.close) channel.port2.close(); } catch (ignored) {}
                }
                return false;
              }
            }
            function postInboundSliceMessage(entry, generation, callback) {
              if (!ensureInboundSliceMessageChannel(entry)) return false;
              entry.inboundSliceMessageCallback = callback;
              try {
                entry.inboundSliceMessageChannel.port2.postMessage(generation);
                return true;
              } catch (error) {
                incrementInboundContinuationStat('inboundMessageChannelFailures');
                closeInboundSliceMessageChannel(entry);
                return false;
              }
            }
            function shouldBlockInboundSliceAdmission(entry) {
              // An exact decoded-packet queue pause must stop decoding completely.  Slice-depth
              // pressure only blocks while Java drains above the low watermark.  A byte-only
              // pause still admits already-received pending bytes once depth is low enough;
              // otherwise pendingInboundBytes can never fall below the resume threshold.
              return !!entry && entry.decodeFlowPaused &&
                (state.exactPacketQueuePaused ||
                 workDepth(entry) > decodedSliceLowWatermark);
            }
            function scheduleSlices(entry, immediate) {
              if (!entry || entry.disposed || entry.inboundSliceScheduled ||
                  entry.pendingInboundHead >= entry.pendingInbound.length ||
                  workDepth(entry) >= decodedSliceHighWatermark ||
                  shouldBlockInboundSliceAdmission(entry)) {
                return;
              }
              const scheduledAt = now();
              const generation = nextInboundSliceGeneration(entry.inboundSliceGeneration);
              entry.inboundSliceGeneration = generation;
              entry.inboundSliceScheduled = true;
              entry.inboundSliceScheduledAt = scheduledAt;
              const run = function(deliveredGeneration) {
                const generationMatches = entry.inboundSliceGeneration === generation;
                const scheduled = !!entry.inboundSliceScheduled;
                const deliveredGenerationMatches = Number(deliveredGeneration) === generation;
                if (entry.disposed || !scheduled || !generationMatches ||
                    !deliveredGenerationMatches) {
                  incrementInboundContinuationStat('inboundContinuationStaleCallbacks');
                  // A malformed token for the currently active generation must not leave the
                  // entry permanently armed.  Close the current channel, advance the epoch, and
                  // re-arm only the still-live pending input.  A retired/older callback remains a
                  // pure no-op because discardInbound() already cleared its schedule state.
                  if (!entry.disposed && scheduled && generationMatches &&
                      !deliveredGenerationMatches) {
                    entry.inboundSliceScheduled = false;
                    entry.inboundSliceHandle = null;
                    entry.inboundSliceUsesRaf = false;
                    entry.inboundSliceScheduledAt = 0;
                    entry.inboundSliceSchedulerKind = '';
                    if (entry.inboundSliceMessageCallback === run) {
                      entry.inboundSliceMessageCallback = null;
                    }
                    closeInboundSliceMessageChannel(entry);
                    entry.inboundSliceGeneration = nextInboundSliceGeneration(
                      entry.inboundSliceGeneration
                    );
                    scheduleSlices(entry, false);
                  }
                  return;
                }
                state.stats.inboundSliceScheduleWaitSamples++;
                state.stats.maxInboundSliceScheduleWaitMillis = Math.max(
                  state.stats.maxInboundSliceScheduleWaitMillis,
                  Math.max(0, now() - scheduledAt)
                );
                entry.inboundSliceScheduled = false;
                entry.inboundSliceHandle = null;
                entry.inboundSliceUsesRaf = false;
                entry.inboundSliceScheduledAt = 0;
                entry.inboundSliceSchedulerKind = '';
                if (entry.inboundSliceMessageCallback === run) {
                  entry.inboundSliceMessageCallback = null;
                }
                pumpSlices(entry);
              };
              if (immediate && typeof queueMicrotask === 'function') {
                entry.inboundSliceSchedulerKind = 'microtask';
                try {
                  queueMicrotask(function() { run(generation); });
                  state.stats.inboundImmediateSchedules++;
                  return;
                } catch (error) {
                  entry.inboundSliceSchedulerKind = '';
                }
              }
              if (postInboundSliceMessage(entry, generation, run)) {
                entry.inboundSliceSchedulerKind = 'message-channel';
                state.stats.inboundMessageChannelSchedules = Math.min(
                  maximumInboundContinuationStat,
                  Math.max(0, Number(state.stats.inboundMessageChannelSchedules) || 0) + 1
                );
                incrementInboundContinuationStat('inboundContinuationMacrotasks');
                return;
              }
              if (typeof globalThis.requestAnimationFrame === 'function') {
                entry.inboundSliceUsesRaf = true;
                entry.inboundSliceSchedulerKind = 'raf';
                state.stats.inboundRafSchedules++;
                entry.inboundSliceHandle = globalThis.requestAnimationFrame(function() {
                  run(generation);
                });
                incrementInboundContinuationStat('inboundContinuationMacrotasks');
              } else {
                entry.inboundSliceSchedulerKind = 'timer';
                state.stats.inboundTimerSchedules++;
                entry.inboundSliceHandle = setTimeout(function() {
                  run(generation);
                }, 0);
                incrementInboundContinuationStat('inboundContinuationMacrotasks');
              }
            }
            function pumpSlices(entry) {
              if (!entry || entry.disposed) return;
              // A slice/exact-queue pause must drain toward 64 without refilling 255 -> 256.
              // A byte-only pause is different: pending bytes are already in this process, so
              // admitting them below the depth low watermark is how the byte count can drain.
              applyFlowControl(entry);
              if (shouldBlockInboundSliceAdmission(entry)) return;
              const startedAt = now();
              let slices = 0;
              while (entry.pendingInboundHead < entry.pendingInbound.length &&
                     workDepth(entry) < decodedSliceHighWatermark) {
                if (slices > 0 && now() - startedAt >= inboundSliceBudgetMillis) break;
                const frame = entry.pendingInbound[entry.pendingInboundHead];
                const remaining = frame.bytes.byteLength - frame.offset;
                if (remaining <= 0) {
                  entry.pendingInboundHead++;
                  continue;
                }
                const byteLength = Math.min(maximumInboundSliceBytes, remaining);
                const chunk = frame.bytes.subarray(frame.offset, frame.offset + byteLength);
                frame.offset += byteLength;
                entry.pendingInboundBytes = Math.max(0, entry.pendingInboundBytes - byteLength);
                entry.inbound.push(chunk);
                entry.inboundBytes += byteLength;
                slices++;
                state.stats.inboundSlices++;
                state.stats.maxInboundSliceQueue = Math.max(
                  state.stats.maxInboundSliceQueue,
                  sliceCount(entry)
                );
                if (frame.offset >= frame.bytes.byteLength) entry.pendingInboundHead++;
              }
              compactPending(entry);
              const elapsed = Math.max(0, now() - startedAt);
              state.stats.inboundSlicePumps++;
              state.stats.longestInboundSlicePumpMillis = Math.max(
                state.stats.longestInboundSlicePumpMillis,
                elapsed
              );
              applyFlowControl(entry);
              if (slices > 0) signalInbound();
              if (entry.pendingInboundHead < entry.pendingInbound.length &&
                  workDepth(entry) < decodedSliceHighWatermark) scheduleSlices(entry, false);
            }
            function releaseDecoderCumulation(entry, retainedBytes) {
              if (!entry) return;
              const retained = Math.max(
                0,
                Math.min(entry.decoderCumulationBytes, Number(retainedBytes) || 0)
              );
              state.stats.decoderCumulationBytes = Math.max(
                0,
                state.stats.decoderCumulationBytes -
                  (entry.decoderCumulationBytes - retained)
              );
              entry.decoderCumulationBytes = retained;
            }
            function releaseDecodedSliceOwnership(entry) {
              if (!entry || entry.decodedSliceBacklog <= 0) return;
              state.stats.decodedSliceBacklog = Math.max(
                0,
                state.stats.decodedSliceBacklog - entry.decodedSliceBacklog
              );
              entry.decodedSliceBacklog = 0;
            }
            state.discardInbound = function(entry) {
              if (!entry) return;
              entry.inboundSliceGeneration = nextInboundSliceGeneration(
                entry.inboundSliceGeneration
              );
              entry.inboundSliceMessageCallback = null;
              state.finishHighWatermark(entry, workDepth(entry), queuedBytes(entry));
              releaseDecoderCumulation(entry, 0);
              releaseDecodedSliceOwnership(entry);
              discardDecoderScopes(entry.id);
              if (entry.inboundSliceHandle !== null) {
                if (entry.inboundSliceUsesRaf &&
                    typeof globalThis.cancelAnimationFrame === 'function') {
                  globalThis.cancelAnimationFrame(entry.inboundSliceHandle);
                } else if (entry.inboundSliceSchedulerKind === 'timer') {
                  clearTimeout(entry.inboundSliceHandle);
                }
              }
              closeInboundSliceMessageChannel(entry);
              state.stats.inboundQueuedBytes = Math.max(
                0,
                state.stats.inboundQueuedBytes - queuedBytes(entry)
              );
              entry.inbound = [];
              entry.inboundHead = 0;
              entry.inboundBytes = 0;
              entry.pendingInbound = [];
              entry.pendingInboundHead = 0;
              entry.pendingInboundBytes = 0;
              entry.inboundSliceScheduled = false;
              entry.inboundSliceHandle = null;
              entry.inboundSliceUsesRaf = false;
              entry.inboundSliceScheduledAt = 0;
              entry.inboundSliceSchedulerKind = '';
              entry.decodeFlowPaused = false;
              entry.flowPaused = false;
            };
            function hasRemoteCloseRetireWork(entry) {
              if (!entry || entry.disposed) return false;
              return entry.inboundHead < entry.inbound.length ||
                entry.pendingInboundHead < entry.pendingInbound.length ||
                !!entry.inboundSliceScheduled ||
                entry.decodedSliceBacklog > 0 ||
                (state.activeDecoderEntryId === (entry.id|0) &&
                 state.activeDecoderScopeDepth > 0);
            }
            function finalizeRemoteCloseRetire(entry, forced) {
              if (!entry || entry.disposed) return;
              if (entry.retireClosedHandle) {
                clearTimeout(entry.retireClosedHandle);
                entry.retireClosedHandle = 0;
              }
              entry.retireClosedPending = false;
              // Keep the identity check before deleting the bridge entry.  A delayed callback
              // from an older generation must never wake the Java pump for a replacement that
              // reused the same numeric socket id.
              const entryId = entry.id|0;
              const ownsCurrentEntry = state.channels.get(entryId) === entry;
              state.discardInbound(entry);
              entry.disposed = true;
              if (ownsCurrentEntry) {
                state.channels.delete(entryId);
              }
              state.stats.remoteCloseRetireFinalized =
                boundedCount(state.stats.remoteCloseRetireFinalized) + 1;
              if (forced) {
                state.stats.remoteCloseRetireForced =
                  boundedCount(state.stats.remoteCloseRetireForced) + 1;
              }
              state.stopEventLoopGapProbeIfIdle();
              // JS retirement alone cannot remove the Java BrowserWebSocketChannel from its
              // static channels[] registry.  One identity-guarded bounded pump gives a stale
              // Java channel the existing `closed(id) && !hasPendingInbound(id)` close path.
              // This wake is deliberately emitted only for the entry that was actually current
              // and deleted; it never invokes PLAY handlers or bypasses packet FIFO.
              if (ownsCurrentEntry && typeof state.inboundPump === 'function') {
                state.inboundPump('remote-close-retire');
              }
            }
            state.retireClosedEntry = function(entry) {
              if (!entry || entry.disposed) return;
              const startedAt = Number(entry.remoteClosedAt) || now();
              entry.remoteClosedAt = startedAt;
              if (entry.retireClosedPending) return;
              state.stats.remoteCloseRetireScheduled =
                boundedCount(state.stats.remoteCloseRetireScheduled) + 1;
              const retry = function() {
                entry.retireClosedPending = false;
                entry.retireClosedHandle = 0;
                if (!entry || entry.disposed) return;
                const elapsed = Math.max(0, now() - startedAt);
                if (hasRemoteCloseRetireWork(entry) &&
                    elapsed < remoteCloseRetireGraceMillis) {
                  state.stats.remoteCloseRetireDeferred =
                    boundedCount(state.stats.remoteCloseRetireDeferred) + 1;
                  // Give the Java-side decoder one last bounded chance to drain buffered bytes
                  // before retiring the closed transport. No new network data can arrive after
                  // onclose, so this signal only advances already queued inbound work.
                  signalInbound();
                  entry.retireClosedPending = true;
                  entry.retireClosedHandle = setTimeout(
                    retry,
                    remoteCloseRetireRetryMillis
                  );
                  return;
                }
                finalizeRemoteCloseRetire(
                  entry,
                  hasRemoteCloseRetireWork(entry) &&
                    elapsed >= remoteCloseRetireGraceMillis
                );
              };
              entry.retireClosedPending = true;
              entry.retireClosedHandle = setTimeout(retry, 0);
            };
            state.deliverInbound = function(entry, buffer) {
              if (!entry || entry.closed) return;
              const source = buffer instanceof ArrayBuffer
                ? new Uint8Array(buffer)
                : new Uint8Array(buffer.buffer, buffer.byteOffset || 0, buffer.byteLength || 0);
              if (queuedBytes(entry) + source.byteLength > maximumInboundQueueBytes) {
                state.failInbound(entry, 'Browser transport inbound queue exceeded 64 MiB');
                return;
              }
              entry.pendingInbound.push({bytes: source, offset: 0});
              entry.pendingInboundBytes += source.byteLength;
              state.stats.inboundQueuedBytes += source.byteLength;
              state.stats.peakInboundQueuedBytes = Math.max(
                state.stats.peakInboundQueuedBytes,
                state.stats.inboundQueuedBytes
              );
              state.stats.receivedFrames++;
              state.stats.receivedBytes += source.byteLength;
              if (entry.localPort) {
                state.stats.localReceivedFrames++;
                state.stats.localReceivedBytes += source.byteLength;
              }
              applyFlowControl(entry);
              scheduleSlices(entry, true);
            };
            state.pollInboundScheduled = function(id, requestFlush) {
              const entry = state.channels.get(id|0);
              if (!entry) return null;
              requestFlush(entry);
              if (entry.inboundHead >= entry.inbound.length) {
                scheduleSlices(entry, true);
                return null;
              }
              if (state.exactPacketQueuePaused) {
                applyFlowControl(entry);
                return null;
              }
              const nextChunk = entry.inbound[entry.inboundHead];
              if (entry.decoderCumulationBytes + nextChunk.byteLength >
                  maximumDecoderCumulationBytes) {
                state.failInbound(entry, 'Browser decoder cumulation exceeded 16 MiB');
                return null;
              }
              const chunk = entry.inbound[entry.inboundHead++];
              entry.inboundBytes = Math.max(0, entry.inboundBytes - chunk.byteLength);
              state.stats.inboundQueuedBytes = Math.max(
                0,
                state.stats.inboundQueuedBytes - chunk.byteLength
              );
              if (entry.inboundHead >= entry.inbound.length) {
                entry.inbound = [];
                entry.inboundHead = 0;
              } else if (entry.inboundHead >= 1024 &&
                         entry.inboundHead * 2 >= entry.inbound.length) {
                entry.inbound = entry.inbound.slice(entry.inboundHead);
                entry.inboundHead = 0;
              }
              applyFlowControl(entry);
              scheduleSlices(entry, false);
              return new Int8Array(chunk.buffer, chunk.byteOffset || 0, chunk.byteLength);
            };
            state.recordPumpTelemetry = function(id, chunks, bytes, millis) {
              const entry = state.channels.get(id|0);
              state.stats.pumpCalls++;
              state.stats.pumpChunks += chunks|0;
              state.stats.pumpBytes += bytes|0;
              state.stats.peakPumpChunks = Math.max(state.stats.peakPumpChunks, chunks|0);
              state.stats.peakPumpBytes = Math.max(state.stats.peakPumpBytes, bytes|0);
              state.stats.peakPumpMillis = Math.max(state.stats.peakPumpMillis, +millis || 0);
              state.stats.longestPumpMillis = Math.max(
                state.stats.longestPumpMillis,
                +millis || 0
              );
              refreshHighWatermark();
              if (entry && queuedBytes(entry) > 0) state.stats.deferredPumps++;
            };
            function syncDecoderScopeOwner() {
              const ids = state.activeDecoderScopeIds;
              const byteLengths = state.activeDecoderScopeBytes;
              const depth = Math.min(ids.length, byteLengths.length);
              if (ids.length !== byteLengths.length) {
                ids.length = depth;
                byteLengths.length = depth;
                state.activeDecoderOwnerAmbiguous = true;
              }
              state.activeDecoderScopeDepth = depth;
              if (depth <= 0) {
                state.activeDecoderEntryId = 0;
                state.activeDecoderSliceBytes = 0;
                state.activeDecoderOwnerAmbiguous = false;
                return;
              }
              state.activeDecoderEntryId = ids[depth - 1]|0;
              state.activeDecoderSliceBytes = Math.max(
                0,
                Number(byteLengths[depth - 1]) || 0
              );
            }
            function pushDecoderScope(entryId, byteLength) {
              state.activeDecoderScopeIds.push(entryId|0);
              state.activeDecoderScopeBytes.push(Math.max(0, Number(byteLength) || 0));
              syncDecoderScopeOwner();
            }
            function finishDecoderScope(entryId) {
              const ids = state.activeDecoderScopeIds;
              const byteLengths = state.activeDecoderScopeBytes;
              if (ids.length <= 0 || byteLengths.length <= 0) return false;
              const owner = entryId|0;
              const last = Math.min(ids.length, byteLengths.length) - 1;
              if ((ids[last]|0) === owner) {
                ids.length = last;
                byteLengths.length = last;
                syncDecoderScopeOwner();
                return true;
              }
              state.activeDecoderOwnerAmbiguous = true;
              let found = -1;
              for (let index = last - 1; index >= 0; index--) {
                if ((ids[index]|0) === owner) {
                  found = index;
                  break;
                }
              }
              if (found < 0) {
                syncDecoderScopeOwner();
                return false;
              }
              for (let index = found; index < last; index++) {
                ids[index] = ids[index + 1];
                byteLengths[index] = byteLengths[index + 1];
              }
              ids.length = last;
              byteLengths.length = last;
              syncDecoderScopeOwner();
              return true;
            }
            function discardDecoderScopes(entryId) {
              const ids = state.activeDecoderScopeIds;
              const byteLengths = state.activeDecoderScopeBytes;
              const owner = entryId|0;
              let write = 0;
              const limit = Math.min(ids.length, byteLengths.length);
              for (let read = 0; read < limit; read++) {
                if ((ids[read]|0) === owner) continue;
                ids[write] = ids[read];
                byteLengths[write] = byteLengths[read];
                write++;
              }
              ids.length = write;
              byteLengths.length = write;
              syncDecoderScopeOwner();
            }
            state.recordDecodedSliceScheduled = function(id, bytes) {
              const entry = state.channels.get(id|0);
              if (!entry || entry.disposed) return;
              const byteLength = Math.max(0, Number(bytes) || 0);
              entry.decodedSliceBacklog++;
              entry.decoderCumulationBytes += byteLength;
              state.stats.decodedSliceBacklog++;
              state.stats.decoderCumulationBytes += byteLength;
              state.stats.maxDecodedSliceBacklog = Math.max(
                state.stats.maxDecodedSliceBacklog,
                state.stats.decodedSliceBacklog
              );
              state.stats.maxDecoderCumulationBytes = Math.max(
                state.stats.maxDecoderCumulationBytes,
                state.stats.decoderCumulationBytes
              );
              if (state.activeDecoderScopeDepth > 0) {
                state.activeDecoderOwnerAmbiguous = true;
              }
              pushDecoderScope(entry.id, byteLength);
              applyFlowControl(entry);
            };
            state.finishDecodedSliceScheduled = function(id) {
              const entry = state.channels.get(id|0);
              if (!entry || entry.disposed) return;
              const scopeFinished = finishDecoderScope(entry.id);
              if (scopeFinished && entry.decodedSliceBacklog > 0) {
                entry.decodedSliceBacklog--;
                state.stats.decodedSliceBacklog = Math.max(
                  0,
                  state.stats.decodedSliceBacklog - 1
                );
              }
              applyFlowControl(entry);
              scheduleSlices(entry, false);
            };
            state.recordDecodedPacketQueueScheduled = function(
                depth, paused, processed, handleMillis, handleType) {
              const queueDepth = Math.max(0, Number(depth) || 0);
              const wasPaused = state.exactPacketQueuePaused;
              state.exactPacketQueuePaused = !!paused;
              const exactQueuePauseChanged =
                wasPaused !== state.exactPacketQueuePaused;
              state.stats.decodedPacketQueue = queueDepth;
              state.stats.maxDecodedPacketQueue = Math.max(
                state.stats.maxDecodedPacketQueue,
                queueDepth
              );
              if (!wasPaused && state.exactPacketQueuePaused) {
                state.stats.decodedPacketQueuePauses++;
              } else if (wasPaused && !state.exactPacketQueuePaused) {
                state.stats.decodedPacketQueueResumes++;
                // Raw slices may already be waiting with no new WebSocket frame left to wake the
                // independent Java transport pump. Resume it on the exact-queue falling edge.
                signalInbound();
              }
              if (processed) {
                state.stats.decodedPacketDrainSignals++;
                const elapsed = Number(handleMillis);
                if (Number.isFinite(elapsed) && elapsed >= 0) {
                  state.stats.queuedPacketHandleSamples++;
                  if (elapsed > state.stats.maxQueuedPacketHandleMillis) {
                    state.stats.maxQueuedPacketHandleMillis = elapsed;
                    state.stats.maxQueuedPacketHandleType = String(handleType || 'unknown');
                  }
                  if (elapsed >= slowQueuedPacketThresholdMillis) {
                    const sequence = boundedCount(
                      state.stats.slowQueuedPacketEventSequence
                    ) + 1;
                    state.stats.slowQueuedPacketEventSequence = sequence;
                    state.stats.slowQueuedPacketEvents.push({
                      sequence: sequence,
                      atMillis: now(),
                      packetType: String(handleType || 'unknown'),
                      elapsedMillis: elapsed,
                      queueDepthAfter: queueDepth
                    });
                    if (state.stats.slowQueuedPacketEvents.length >
                        maximumSlowQueuedPacketEvents) {
                      const dropped = state.stats.slowQueuedPacketEvents.length -
                        maximumSlowQueuedPacketEvents;
                      state.stats.slowQueuedPacketEvents.splice(0, dropped);
                      state.stats.slowQueuedPacketEventsDropped = boundedCount(
                        state.stats.slowQueuedPacketEventsDropped
                      ) + dropped;
                    }
                  }
                }
              } else if (queueDepth > 0 && state.activeDecoderEntryId &&
                  !state.activeDecoderOwnerAmbiguous) {
                const entry = state.channels.get(state.activeDecoderEntryId|0);
                if (entry && !entry.disposed) {
                  // The decoder may have left the tail of the active slice for the next packet.
                  // Retaining at most that slice keeps accounting conservative and bounded.
                  releaseDecoderCumulation(entry, state.activeDecoderSliceBytes);
                }
              }
              if (!processed && queueDepth >= 64 &&
                  typeof state.clientPacketDrain === 'function') {
                // This hook only records pressure demand for Minecraft.runTick's one scheduled
                // PacketProcessor boundary. It never schedules or executes a PLAY handler here.
                state.clientPacketDrain();
              }
              // Queue accounting runs for every decoded packet.  In the normal unpaused path the
              // global exact-queue state did not change, so walking every multiplayer channel
              // here is pure O(N) overhead.  A queue transition still fans out to all channels so
              // an exact high-watermark edge pauses/resumes every decoder.  When a packet was
              // just queued, only the active decoder's cumulation changed; re-evaluate that one
              // entry and leave unrelated channels alone until their own event or a global edge.
              if (exactQueuePauseChanged) {
                state.channels.forEach(function(entry) {
                  applyFlowControl(entry);
                  scheduleSlices(entry, false);
                });
              } else if (!processed && queueDepth > 0) {
                const activeEntry = !state.activeDecoderOwnerAmbiguous &&
                    state.activeDecoderEntryId
                  ? state.channels.get(state.activeDecoderEntryId|0)
                  : null;
                if (activeEntry && !activeEntry.disposed) {
                  applyFlowControl(activeEntry);
                  scheduleSlices(activeEntry, false);
                } else {
                  // Queue accounting has no channel owner in the Java bridge. If the active
                  // decoder scope was cleared or became stale, preserve the previous correctness
                  // contract instead of silently starving a different channel's pending input.
                  state.channels.forEach(function(entry) {
                    applyFlowControl(entry);
                    scheduleSlices(entry, false);
                  });
                }
              }
              refreshHighWatermark();
            };
            state.recordInlineDecodedPacketScheduled = function() {
              if (state.activeDecoderOwnerAmbiguous) return;
              const entry = state.channels.get(state.activeDecoderEntryId|0);
              if (!entry || entry.disposed) return;
              // A browser-inline handler proves that at least one complete packet was
              // decoded from the active slice.  Any retained partial packet can therefore use at
              // most the active slice; older cumulation has been consumed by completed packets.
              releaseDecoderCumulation(entry, state.activeDecoderSliceBytes);
              state.stats.inlineDecodedPackets++;
              applyFlowControl(entry);
            };
            state.hasPendingInboundScheduled = function(id) {
              const entry = state.channels.get(id|0);
              return !!entry && (entry.inboundHead < entry.inbound.length ||
                entry.pendingInboundHead < entry.pendingInbound.length);
            };
            state.hasPumpableInboundScheduled = function(id) {
              const entry = state.channels.get(id|0);
              return !!entry && !entry.disposed && !state.exactPacketQueuePaused &&
                entry.inboundHead < entry.inbound.length;
            };
            """)
    private static native void initInboundScheduler();

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

    @JSBody(params = {"id"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            return !!bridge && typeof bridge.hasPumpableInbound === 'function' &&
              !!bridge.hasPumpableInbound(id);
            """)
    private static native boolean hasPumpableInbound(int id);

    /** Updates exact PacketProcessor queue depth and its independent transport pause state. */
    public static void recordDecodedPacketQueue(
            int depth,
            boolean paused,
            boolean processed,
            double handleMillis,
            String handleType) {
        recordDecodedPacketQueueJs(depth, paused, processed, handleMillis, handleType);
    }

    /** Retires decoder cumulation for a packet handled inline before PacketProcessor. */
    public static void recordInlineDecodedPacket() {
        recordInlineDecodedPacketJs();
    }

    @JSBody(params = {"depth", "paused", "processed", "handleMillis", "handleType"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.recordDecodedPacketQueue === 'function') {
              bridge.recordDecodedPacketQueue(
                depth, paused, processed, handleMillis, handleType);
            }
            """)
    private static native void recordDecodedPacketQueueJs(
            int depth,
            boolean paused,
            boolean processed,
            double handleMillis,
            String handleType);

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.recordInlineDecodedPacket === 'function') {
              bridge.recordInlineDecodedPacket();
            }
            """)
    private static native void recordInlineDecodedPacketJs();

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double monotonicMillis();

    @JSBody(params = {"id", "bytes"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.recordDecodedSlice === 'function') {
              bridge.recordDecodedSlice(id, bytes);
            }
            """)
    private static native void recordDecodedSlice(int id, int bytes);

    @JSBody(params = {"id"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.finishDecodedSlice === 'function') {
              bridge.finishDecodedSlice(id);
            }
            """)
    private static native void finishDecodedSlice(int id);

    @JSBody(params = {"id", "chunks", "bytes", "millis"}, script = """
            globalThis.__gaiusNettyBridge.recordPump(id, chunks, bytes, millis);
            """)
    private static native void recordPump(int id, int chunks, int bytes, double millis);

    @JSBody(params = {"channelsVisited", "millis", "budgetExhausted"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            if (!stats) return;
            const visited = Math.max(0, Number(channelsVisited) || 0);
            const elapsed = Math.max(0, Number(millis) || 0);
            stats.pumpAllTurns = (Number(stats.pumpAllTurns) || 0) + 1;
            stats.pumpAllChannelsVisited =
              (Number(stats.pumpAllChannelsVisited) || 0) + visited;
            stats.pumpAllBudgetYields =
              (Number(stats.pumpAllBudgetYields) || 0) + (budgetExhausted ? 1 : 0);
            stats.pumpAllMaxTurnMillis = Math.max(
              Number(stats.pumpAllMaxTurnMillis) || 0,
              elapsed
            );
            stats.pumpAllMaxChannelsPerTurn = Math.max(
              Number(stats.pumpAllMaxChannelsPerTurn) || 0,
              visited
            );
            stats.pumpAllLastTurnMillis = elapsed;
            stats.pumpAllLastChannelsVisited = visited;
            """)
    private static native void recordPumpAllTelemetry(
            int channelsVisited,
            double millis,
            boolean budgetExhausted);
}
