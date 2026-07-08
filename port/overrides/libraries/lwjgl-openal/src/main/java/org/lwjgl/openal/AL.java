package org.lwjgl.openal;

import java.util.function.IntFunction;
import org.lwjgl.PointerBuffer;

public final class AL {
    private static ALCapabilities processCaps;
    private static final ThreadLocal<ALCapabilities> capabilitiesTLS = new ThreadLocal<>();

    private AL() {
    }

    static void init() {
        if (processCaps == null) {
            processCaps = createCapabilities(ALC.getCapabilities());
        }
    }

    static void destroy() {
        capabilitiesTLS.remove();
        processCaps = null;
    }

    public static void setCurrentProcess(ALCapabilities caps) {
        processCaps = caps;
    }

    public static void setCurrentThread(ALCapabilities caps) {
        capabilitiesTLS.set(caps);
    }

    public static ALCapabilities getCapabilities() {
        ALCapabilities caps = capabilitiesTLS.get();
        if (caps != null) {
            return caps;
        }
        if (processCaps == null) {
            processCaps = createCapabilities(ALC.getCapabilities());
        }
        return processCaps;
    }

    public static ALCapabilities createCapabilities(ALCCapabilities alcCaps) {
        return createCapabilities(alcCaps, BrowserOpenAL.POINTER_BUFFER_FACTORY);
    }

    public static ALCapabilities createCapabilities(
            ALCCapabilities alcCaps,
            IntFunction<PointerBuffer> bufferFactory) {
        IntFunction<PointerBuffer> factory =
                bufferFactory == null ? BrowserOpenAL.POINTER_BUFFER_FACTORY : bufferFactory;
        return new ALCapabilities(
                BrowserOpenAL.FUNCTION_PROVIDER,
                BrowserOpenAL.AL_EXTENSIONS,
                factory);
    }

    static ALCapabilities getICD() {
        return getCapabilities();
    }
}
