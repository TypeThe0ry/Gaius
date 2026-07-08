package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.function.IntFunction;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProviderLocal;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

public final class BrowserOpenAL {
    static final long FUNCTION_ADDRESS = 1L;
    static final FunctionProviderLocal FUNCTION_PROVIDER = new FunctionProviderLocal() {
        @Override
        public long getFunctionAddress(ByteBuffer functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(CharSequence functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(long handle, ByteBuffer functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(long handle, CharSequence functionName) {
            return FUNCTION_ADDRESS;
        }
    };

    static final Set<String> ALC_EXTENSIONS = Set.of(
            "OpenALC10",
            "OpenALC11");

    static final Set<String> AL_EXTENSIONS = Set.of(
            "OpenAL10",
            "OpenAL11",
            "AL_EXT_source_distance_model",
            "AL_EXT_LINEAR_DISTANCE");

    static final IntFunction<PointerBuffer> POINTER_BUFFER_FACTORY =
            PointerBuffer::allocateDirect;

    private static final int AL_VENDOR = 0xB001;
    private static final int AL_VERSION = 0xB002;
    private static final int AL_RENDERER = 0xB003;
    private static final int AL_EXTENSIONS_PARAM = 0xB004;
    private static final int AL_GAIN = 0x100A;
    private static final int AL_PITCH = 0x1003;
    private static final int AL_POSITION = 0x1004;
    private static final int AL_LOOPING = 0x1007;
    private static final int AL_BUFFER = 0x1009;
    private static final int AL_SOURCE_STATE = 0x1010;
    private static final int AL_BUFFERS_QUEUED = 0x1015;
    private static final int AL_BUFFERS_PROCESSED = 0x1016;
    private static final int AL_FREQUENCY = 0x2001;
    private static final int AL_BITS = 0x2002;
    private static final int AL_CHANNELS = 0x2003;
    private static final int AL_SIZE = 0x2004;

    private BrowserOpenAL() {
    }

    @JSBody(script = """
            if (window.__gaiusOpenAL) {
              return;
            }
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            const state = {
              next: 1,
              context: null,
              buffers: new Map(),
              sources: new Map(),
              resumeHooked: false,
              stats: {
                initialized: true,
                backend: 'WebAudio',
                buffers: 0,
                sources: 0,
                bufferUploads: 0,
                sourcePlays: 0,
                sourceStops: 0,
                queuedBuffers: 0,
                unqueuedBuffers: 0,
                activeSources: 0,
                lastFormat: 0,
                lastFrequency: 0,
                lastUploadBytes: 0,
                contextState: AudioContextClass ? 'created-lazily' : 'unavailable'
              }
            };
            function ensureContext() {
              if (!AudioContextClass) return null;
              if (!state.context) {
                try {
                  state.context = new AudioContextClass();
                  state.stats.contextState = state.context.state || 'unknown';
                } catch (error) {
                  state.stats.contextState = 'failed';
                  state.stats.lastError = String(error && (error.message || error));
                  return null;
                }
              }
              if (!state.resumeHooked) {
                state.resumeHooked = true;
                                const resume = function() {
                  if (state.context && state.context.state === 'suspended') {
                                        state.context.resume().then(function() {
                      state.stats.contextState = state.context.state || 'running';
                                        }, function(ignored) {});
                  }
                };
                window.addEventListener('pointerdown', resume, {passive:true});
                window.addEventListener('keydown', resume, {passive:true});
                window.addEventListener('touchstart', resume, {passive:true});
              }
              return state.context;
            }
            function freshSource() {
              return {
                gain: 1,
                pitch: 1,
                looping: false,
                relative: false,
                position: [0, 0, 0],
                buffer: 0,
                queue: [],
                scheduled: [],
                state: 0x1011,
                gainNode: null,
                panner: null
              };
            }
            function stopNodes(source) {
              if (!source || !source.scheduled) return;
                            for (var stopIndex = 0; stopIndex < source.scheduled.length; stopIndex++) {
                                var entry = source.scheduled[stopIndex];
                if (entry && entry.node) {
                  try { entry.node.stop(); } catch (ignored) {}
                }
              }
              source.scheduled = [];
              state.stats.activeSources = Math.max(0, state.stats.activeSources - 1);
            }
            function processedCount(source) {
              if (!source || !source.scheduled || source.scheduled.length === 0) return 0;
              const ctx = state.context;
              if (!ctx) return 0;
              const now = ctx.currentTime || 0;
                            var count = 0;
                            for (var processedIndex = 0; processedIndex < source.scheduled.length; processedIndex++) {
                                var entry = source.scheduled[processedIndex];
                if (entry && entry.endTime <= now + 0.005) count++;
              }
              return Math.min(count, source.queue ? source.queue.length : count);
            }
            function refreshState(source) {
              if (!source) return 0x1014;
              if (source.state !== 0x1012) return source.state || 0x1011;
              if (source.looping) return 0x1012;
              const ctx = state.context;
              if (!ctx || !source.scheduled || source.scheduled.length === 0) {
                source.state = 0x1014;
                return source.state;
              }
              const now = ctx.currentTime || 0;
                            for (var refreshIndex = 0; refreshIndex < source.scheduled.length; refreshIndex++) {
                                var entry = source.scheduled[refreshIndex];
                if (entry && entry.endTime > now + 0.005) return 0x1012;
              }
              source.state = 0x1014;
              return source.state;
            }
            function connectSourceNode(source, node) {
              const ctx = ensureContext();
              if (!ctx) return false;
              if (!source.gainNode) {
                source.gainNode = ctx.createGain();
                source.gainNode.gain.value = Math.max(0, Number(source.gain) || 0);
                source.gainNode.connect(ctx.destination);
              }
              node.playbackRate.value = Math.max(0.01, Number(source.pitch) || 1);
              node.connect(source.gainNode);
              return true;
            }
            function scheduleBuffer(source, bufferId, startAt, loop) {
              const ctx = ensureContext();
              const buffer = state.buffers.get(bufferId|0);
              if (!ctx || !buffer || !buffer.audio) return startAt;
              const node = ctx.createBufferSource();
              node.buffer = buffer.audio;
              node.loop = !!loop;
              if (!connectSourceNode(source, node)) return startAt;
              const when = Math.max(ctx.currentTime, Number(startAt) || ctx.currentTime);
              try {
                node.start(when);
              } catch (error) {
                state.stats.lastError = String(error && (error.message || error));
                return startAt;
              }
              const duration = Math.max(0.001, buffer.audio.duration / Math.max(0.01, Number(source.pitch) || 1));
              source.scheduled.push({bufferId: bufferId|0, node: node, endTime: loop ? Number.MAX_VALUE : when + duration});
              return when + duration;
            }
            state.ensureContext = ensureContext;
            state.freshSource = freshSource;
            state.stopNodes = stopNodes;
            state.processedCount = processedCount;
            state.refreshState = refreshState;
            state.scheduleBuffer = scheduleBuffer;
            window.__gaiusOpenAL = state;
            window.__gaiusAudioStats = state.stats;
            """)
    public static native void init();

    @JSBody(script = """
            if (!window.__gaiusOpenAL) return;
            const state = window.__gaiusOpenAL;
            state.sources.forEach(function(source) { state.stopNodes(source); });
            state.sources.clear();
            state.buffers.clear();
            state.stats.sources = 0;
            state.stats.buffers = 0;
            state.stats.activeSources = 0;
            """)
    public static native void cleanup();

    public static int getError() {
        return 0;
    }

    public static int getInteger(int parameter) {
        return switch (parameter) {
            case AL_GAIN, AL_PITCH -> 1;
            default -> 0;
        };
    }

    public static float getFloat(int parameter) {
        return getInteger(parameter);
    }

    public static double getDouble(int parameter) {
        return getInteger(parameter);
    }

    public static String getString(int parameter) {
        return switch (parameter) {
            case AL_VENDOR -> "Gaius";
            case AL_VERSION -> "OpenAL 1.1 Web Audio";
            case AL_RENDERER -> "Gaius Browser OpenAL";
            case AL_EXTENSIONS_PARAM -> String.join(" ", AL_EXTENSIONS);
            default -> null;
        };
    }

    public static int genSource() {
        init();
        return genSourceJs();
    }

    public static void genSources(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int index = 0; index < sources.length; index++) {
            sources[index] = genSource();
        }
    }

    public static void genSources(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            copy.put(genSource());
        }
    }

    @JSBody(script = """
            const state = window.__gaiusOpenAL;
            const id = state.next++;
            state.sources.set(id, state.freshSource());
            state.stats.sources = state.sources.size;
            return id|0;
            """)
    private static native int genSourceJs();

    public static void deleteSource(int source) {
        deleteSourceJs(source);
    }

    public static void deleteSources(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            deleteSource(source);
        }
    }

    public static void deleteSources(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            deleteSource(copy.get());
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            source = source|0;
            const src = state.sources.get(source);
            if (src) state.stopNodes(src);
            state.sources.delete(source);
            state.stats.sources = state.sources.size;
            """)
    private static native void deleteSourceJs(int source);

    @JSBody(params = {"source"}, script = """
            return !!(window.__gaiusOpenAL && window.__gaiusOpenAL.sources.has(source|0));
            """)
    public static native boolean isSource(int source);

    public static void sourcef(int source, int parameter, float value) {
        sourcefJs(source, parameter, value);
    }

    public static void source3f(int source, int parameter, float x, float y, float z) {
        source3fJs(source, parameter, x, y, z);
    }

    public static void sourcefv(int source, int parameter, FloatBuffer values) {
        if (values == null || values.remaining() < 3) {
            return;
        }
        FloatBuffer copy = values.duplicate();
        source3f(source, parameter, copy.get(), copy.get(), copy.get());
    }

    public static void sourcefv(int source, int parameter, float[] values) {
        if (values == null || values.length < 3) {
            return;
        }
        source3f(source, parameter, values[0], values[1], values[2]);
    }

    @JSBody(params = {"source", "parameter", "value"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            parameter = parameter|0;
            value = Number(value) || 0;
            if (parameter === 0x100A) {
              src.gain = Math.max(0, value);
              if (src.gainNode) src.gainNode.gain.value = src.gain;
            } else if (parameter === 0x1003) {
              src.pitch = Math.max(0.01, value);
            } else {
              src[parameter] = value;
            }
            """)
    private static native void sourcefJs(int source, int parameter, float value);

    @JSBody(params = {"source", "parameter", "x", "y", "z"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            if ((parameter|0) === 0x1004) {
              src.position = [Number(x)||0, Number(y)||0, Number(z)||0];
            } else {
              src[parameter|0] = [Number(x)||0, Number(y)||0, Number(z)||0];
            }
            """)
    private static native void source3fJs(int source, int parameter, float x, float y, float z);

    public static void sourcei(int source, int parameter, int value) {
        sourceiJs(source, parameter, value);
    }

    public static void source3i(int source, int parameter, int x, int y, int z) {
        source3f(source, parameter, x, y, z);
    }

    public static void sourceiv(int source, int parameter, IntBuffer values) {
        if (values == null || values.remaining() == 0) {
            return;
        }
        IntBuffer copy = values.duplicate();
        if (copy.remaining() >= 3) {
            source3i(source, parameter, copy.get(), copy.get(), copy.get());
        } else {
            sourcei(source, parameter, copy.get());
        }
    }

    public static void sourceiv(int source, int parameter, int[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (values.length >= 3) {
            source3i(source, parameter, values[0], values[1], values[2]);
        } else {
            sourcei(source, parameter, values[0]);
        }
    }

    @JSBody(params = {"source", "parameter", "value"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            parameter = parameter|0;
            value = value|0;
            if (parameter === 0x1007) {
              src.looping = value !== 0;
            } else if (parameter === 0x1009) {
              src.buffer = value;
            } else if (parameter === 0x0202) {
              src.relative = value !== 0;
            } else {
              src[parameter] = value;
            }
            """)
    private static native void sourceiJs(int source, int parameter, int value);

    public static int getSourcei(int source, int parameter) {
        return getSourceiJs(source, parameter);
    }

    public static void getSourcei(int source, int parameter, IntBuffer values) {
        if (values != null && values.hasRemaining()) {
            values.duplicate().put(getSourcei(source, parameter));
        }
    }

    public static void getSourcei(int source, int parameter, int[] values) {
        if (values != null && values.length > 0) {
            values[0] = getSourcei(source, parameter);
        }
    }

    public static void getSourceiv(int source, int parameter, IntBuffer values) {
        getSourcei(source, parameter, values);
    }

    public static void getSourceiv(int source, int parameter, int[] values) {
        getSourcei(source, parameter, values);
    }

    @JSBody(params = {"source", "parameter"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const src = state.sources.get(source|0);
            if (!src) return 0;
            parameter = parameter|0;
            if (parameter === 0x1010) return state.refreshState(src)|0;
            if (parameter === 0x1015) return (src.queue ? src.queue.length : 0)|0;
            if (parameter === 0x1016) return state.processedCount(src)|0;
            if (parameter === 0x1009) return (src.buffer || 0)|0;
            return (src[parameter] || 0)|0;
            """)
    private static native int getSourceiJs(int source, int parameter);

    public static void sourceQueueBuffers(int source, int buffer) {
        sourceQueueBufferJs(source, buffer);
    }

    public static void sourceQueueBuffers(int source, int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int buffer : buffers) {
            sourceQueueBuffers(source, buffer);
        }
    }

    public static void sourceQueueBuffers(int source, IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            sourceQueueBuffers(source, copy.get());
        }
    }

    @JSBody(params = {"source", "buffer"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            src.queue.push(buffer|0);
            state.stats.queuedBuffers++;
            """)
    private static native void sourceQueueBufferJs(int source, int buffer);

    public static int sourceUnqueueBuffer(int source) {
        return sourceUnqueueBufferJs(source);
    }

    public static void sourceUnqueueBuffers(int source, int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = sourceUnqueueBuffer(source);
        }
    }

    public static void sourceUnqueueBuffers(int source, IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            copy.put(sourceUnqueueBuffer(source));
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const src = state.sources.get(source|0);
            if (!src || !src.queue || src.queue.length === 0) return 0;
            const processed = state.processedCount(src);
            if (processed <= 0 && state.refreshState(src) === 0x1012) return 0;
            const id = src.queue.shift() || 0;
            if (src.scheduled && src.scheduled.length > 0) src.scheduled.shift();
            state.stats.unqueuedBuffers++;
            return id|0;
            """)
    private static native int sourceUnqueueBufferJs(int source);

    public static void sourcePlay(int source) {
        sourcePlayJs(source);
    }

    public static void sourcePause(int source) {
        sourcePauseJs(source);
    }

    public static void sourceStop(int source) {
        sourceStopJs(source);
    }

    public static void sourceRewind(int source) {
        sourceStop(source);
    }

    public static void sourcePlayv(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourcePlay(source);
        }
    }

    public static void sourcePlayv(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourcePlay(copy.get());
        }
    }

    public static void sourcePausev(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourcePause(source);
        }
    }

    public static void sourcePausev(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourcePause(copy.get());
        }
    }

    public static void sourceStopv(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourceStop(source);
        }
    }

    public static void sourceStopv(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourceStop(copy.get());
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            state.ensureContext();
            state.stopNodes(src);
            src.scheduled = [];
            if (src.buffer) {
              state.scheduleBuffer(src, src.buffer|0, 0, src.looping);
            } else if (src.queue && src.queue.length) {
                            var at = state.context ? state.context.currentTime : 0;
                            for (var queueIndex = 0; queueIndex < src.queue.length; queueIndex++) {
                                at = state.scheduleBuffer(src, src.queue[queueIndex]|0, at, false);
              }
            }
            src.state = 0x1012;
            state.stats.sourcePlays++;
            state.stats.activeSources = Math.max(state.stats.activeSources, 1);
            if (state.context) state.stats.contextState = state.context.state || 'unknown';
            """)
    private static native void sourcePlayJs(int source);

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            state.stopNodes(src);
            src.state = 0x1013;
            """)
    private static native void sourcePauseJs(int source);

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            state.stopNodes(src);
            src.state = 0x1014;
            state.stats.sourceStops++;
            """)
    private static native void sourceStopJs(int source);

    public static int genBuffer() {
        init();
        return genBufferJs();
    }

    public static void genBuffers(int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = genBuffer();
        }
    }

    public static void genBuffers(IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            copy.put(genBuffer());
        }
    }

    @JSBody(script = """
            const state = window.__gaiusOpenAL;
            const id = state.next++;
            state.buffers.set(id, {format:0, frequency:0, size:0, audio:null});
            state.stats.buffers = state.buffers.size;
            return id|0;
            """)
    private static native int genBufferJs();

    public static void deleteBuffer(int buffer) {
        deleteBufferJs(buffer);
    }

    public static void deleteBuffers(int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int buffer : buffers) {
            deleteBuffer(buffer);
        }
    }

    public static void deleteBuffers(IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            deleteBuffer(copy.get());
        }
    }

    @JSBody(params = {"buffer"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.buffers.delete(buffer|0);
            state.stats.buffers = state.buffers.size;
            """)
    private static native void deleteBufferJs(int buffer);

    @JSBody(params = {"buffer"}, script = """
            return !!(window.__gaiusOpenAL && window.__gaiusOpenAL.buffers.has(buffer|0));
            """)
    public static native boolean isBuffer(int buffer);

    public static void bufferData(int buffer, int format, ByteBuffer data, int frequency) {
        bufferDataJs(buffer, format, bytes(data), frequency);
    }

    public static void bufferData(int buffer, int format, ShortBuffer data, int frequency) {
        bufferDataJs(buffer, format, shorts(data), frequency);
    }

    public static void bufferData(int buffer, int format, IntBuffer data, int frequency) {
        bufferDataJs(buffer, format, ints(data), frequency);
    }

    public static void bufferData(int buffer, int format, FloatBuffer data, int frequency) {
        bufferDataJs(buffer, format, floats(data), frequency);
    }

    public static void bufferData(int buffer, int format, short[] data, int frequency) {
        bufferDataJs(buffer, format, shorts(data), frequency);
    }

    public static void bufferData(int buffer, int format, int[] data, int frequency) {
        bufferDataJs(buffer, format, ints(data), frequency);
    }

    public static void bufferData(int buffer, int format, float[] data, int frequency) {
        bufferDataJs(buffer, format, floats(data), frequency);
    }

    @JSBody(params = {"buffer", "format", "data", "frequency"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const ctx = state.ensureContext();
            format = format|0;
            frequency = Math.max(1, frequency|0);
            const bytes = data ? new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength || 0) : new Uint8Array(0);
            const channels = (format === 0x1102 || format === 0x1103) ? 2 : 1;
            const bits = (format === 0x1101 || format === 0x1103) ? 16 : 8;
            const bytesPerSample = bits >> 3;
            const frameCount = Math.max(0, Math.floor(bytes.length / Math.max(1, channels * bytesPerSample)));
            var audio = null;
            if (ctx && frameCount > 0) {
              audio = ctx.createBuffer(channels, frameCount, frequency);
              if (bits === 16) {
                                const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                                        var offset = (frame * channels + channel) * 2;
                    out[frame] = Math.max(-1, Math.min(1, view.getInt16(offset, true) / 32768));
                  }
                }
              } else {
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                    out[frame] = ((bytes[frame * channels + channel] || 0) - 128) / 128;
                  }
                }
              }
            }
                        state.buffers.set(buffer|0, {
                            format: format,
                            frequency: frequency,
                            size: bytes.length,
                            channels: channels,
                            bits: bits,
                            audio: audio
                        });
            state.stats.buffers = state.buffers.size;
            state.stats.bufferUploads++;
            state.stats.lastFormat = format;
            state.stats.lastFrequency = frequency;
            state.stats.lastUploadBytes = bytes.length;
            if (state.context) state.stats.contextState = state.context.state || 'unknown';
            """)
    private static native void bufferDataJs(int buffer, int format, Int8Array data, int frequency);

    public static int getBufferi(int buffer, int parameter) {
        return getBufferiJs(buffer, parameter);
    }

    public static void getBufferi(int buffer, int parameter, IntBuffer values) {
        if (values != null && values.hasRemaining()) {
            values.duplicate().put(getBufferi(buffer, parameter));
        }
    }

    public static void getBufferi(int buffer, int parameter, int[] values) {
        if (values != null && values.length > 0) {
            values[0] = getBufferi(buffer, parameter);
        }
    }

    @JSBody(params = {"buffer", "parameter"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const entry = state.buffers.get(buffer|0);
            if (!entry) return 0;
            parameter = parameter|0;
            if (parameter === 0x2001) return entry.frequency|0;
            if (parameter === 0x2002) return entry.bits|0;
            if (parameter === 0x2003) return entry.channels|0;
            if (parameter === 0x2004) return entry.size|0;
            return 0;
            """)
    private static native int getBufferiJs(int buffer, int parameter);

    public static void listenerf(int parameter, float value) {
    }

    public static void listeneri(int parameter, int value) {
    }

    public static void listener3f(int parameter, float x, float y, float z) {
    }

    public static void listenerfv(int parameter, FloatBuffer values) {
    }

    public static void listenerfv(int parameter, float[] values) {
    }

    public static void distanceModel(int model) {
    }

    public static void dopplerFactor(float value) {
    }

    public static void dopplerVelocity(float value) {
    }

    public static void speedOfSound(float value) {
    }

    public static boolean isExtensionPresent(CharSequence extension) {
        return extension != null && (AL_EXTENSIONS.contains(extension.toString())
                || ALC_EXTENSIONS.contains(extension.toString()));
    }

    public static int getEnumValue(CharSequence name) {
        return 0;
    }

    public static long getProcAddress(CharSequence name) {
        return FUNCTION_ADDRESS;
    }

    public static void bufferf(int buffer, int parameter, float value) {
    }

    public static void buffer3f(int buffer, int parameter, float x, float y, float z) {
    }

    public static void bufferi(int buffer, int parameter, int value) {
    }

    public static void buffer3i(int buffer, int parameter, int x, int y, int z) {
    }

    public static void bufferfv(int buffer, int parameter, FloatBuffer values) {
    }

    public static void bufferfv(int buffer, int parameter, float[] values) {
    }

    public static void bufferiv(int buffer, int parameter, IntBuffer values) {
    }

    public static void bufferiv(int buffer, int parameter, int[] values) {
    }

    private static Int8Array bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array shorts(ShortBuffer buffer) {
        if (buffer == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        ShortBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 2];
        int offset = 0;
        while (copy.hasRemaining()) {
            short value = copy.get();
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array shorts(short[] values) {
        if (values == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        byte[] data = new byte[values.length * 2];
        int offset = 0;
        for (short value : values) {
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array ints(IntBuffer buffer) {
        if (buffer == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        IntBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 4];
        int offset = 0;
        while (copy.hasRemaining()) {
            int value = copy.get();
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array ints(int[] values) {
        if (values == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        byte[] data = new byte[values.length * 4];
        int offset = 0;
        for (int value : values) {
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array floats(FloatBuffer buffer) {
        if (buffer == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        FloatBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 4];
        int offset = 0;
        while (copy.hasRemaining()) {
            int value = Float.floatToRawIntBits(copy.get());
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array floats(float[] values) {
        if (values == null) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        byte[] data = new byte[values.length * 4];
        int offset = 0;
        for (float number : values) {
            int value = Float.floatToRawIntBits(number);
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return Int8Array.fromJavaArray(data);
    }
}
