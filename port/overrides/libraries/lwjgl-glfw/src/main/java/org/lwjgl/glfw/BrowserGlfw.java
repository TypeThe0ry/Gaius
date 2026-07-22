package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;

/**
 * Browser implementation behind the original LWJGL GLFW API.
 *
 * <p>DOM callbacks only enqueue primitive event records. Java callbacks are
 * invoked from {@link #pollEvents()}, preserving GLFW's single-threaded event
 * dispatch model.</p>
 */
public final class BrowserGlfw {
    private static final long WINDOW = 1L;
    private static final long MONITOR = 2L;

    private static GLFWErrorCallbackI errorCallback;
    private static GLFWMonitorCallbackI monitorCallback;
    private static GLFWWindowPosCallbackI windowPosCallback;
    private static GLFWWindowSizeCallbackI windowSizeCallback;
    private static GLFWWindowCloseCallbackI windowCloseCallback;
    private static GLFWWindowFocusCallbackI windowFocusCallback;
    private static GLFWWindowIconifyCallbackI windowIconifyCallback;
    private static GLFWFramebufferSizeCallbackI framebufferSizeCallback;
    private static GLFWCursorEnterCallbackI cursorEnterCallback;
    private static GLFWKeyCallbackI keyCallback;
    private static GLFWCharCallbackI charCallback;
    private static GLFWCharModsCallbackI charModsCallback;
    private static GLFWMouseButtonCallbackI mouseButtonCallback;
    private static GLFWCursorPosCallbackI cursorPosCallback;
    private static GLFWScrollCallbackI scrollCallback;
    private static GLFWDropCallbackI dropCallback;

    private static boolean shouldClose;
    private static double cursorX;
    private static double cursorY;
    private static ByteBuffer videoModeMemory;

    private BrowserGlfw() {
    }

    public static boolean init() {
        installDomBridge();
        return true;
    }

    public static void terminate() {
    }

    public static void defaultWindowHints() {
    }

    public static void windowHint(int hint, int value) {
    }

    public static void windowHintString(int hint, CharSequence value) {
    }

    public static long createWindow(int width, int height, CharSequence title, long monitor, long share) {
        createCanvas(width, height, title == null ? "Minecraft" : title.toString());
        shouldClose = false;
        return WINDOW;
    }

    public static void destroyWindow(long window) {
    }

    public static boolean windowShouldClose(long window) {
        return shouldClose;
    }

    public static void setWindowShouldClose(long window, boolean value) {
        shouldClose = value;
    }

    public static void setWindowTitle(long window, CharSequence title) {
        setTitle(title == null ? "Minecraft" : title.toString());
    }

    public static void setWindowIcon(long window, GLFWImage.Buffer icons) {
    }

    public static void getWindowPos(long window, int[] x, int[] y) {
        set(x, 0);
        set(y, 0);
    }

    public static void setWindowPos(long window, int x, int y) {
    }

    public static void getWindowSize(long window, int[] width, int[] height) {
        set(width, canvasCssWidth());
        set(height, canvasCssHeight());
    }

    public static void setWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
    }

    public static void setWindowSize(long window, int width, int height) {
        resizeCanvas(width, height);
    }

    public static void getFramebufferSize(long window, int[] width, int[] height) {
        set(width, framebufferWidth());
        set(height, framebufferHeight());
    }

    public static long getWindowMonitor(long window) {
        return 0L;
    }

    public static void setWindowMonitor(
            long window, long monitor, int x, int y, int width, int height, int refreshRate) {
        resizeCanvas(width, height);
    }

    public static int getWindowAttrib(long window, int attribute) {
        return attribute == GLFW.GLFW_FOCUSED ? 1 : 0;
    }

    public static int getPlatform() {
        return GLFW.GLFW_PLATFORM_NULL;
    }

    public static boolean platformSupported(int platform) {
        return platform == GLFW.GLFW_PLATFORM_NULL;
    }

    public static PointerBuffer getMonitors() {
        PointerBuffer result = PointerBuffer.allocateDirect(1);
        result.put(0, MONITOR);
        return result;
    }

    public static long getPrimaryMonitor() {
        return MONITOR;
    }

    public static void getMonitorPos(long monitor, int[] x, int[] y) {
        set(x, 0);
        set(y, 0);
    }

    public static void getMonitorContentScale(long monitor, float[] x, float[] y) {
        float scale = (float) devicePixelRatio();
        set(x, scale);
        set(y, scale);
    }

    public static GLFWVidMode getVideoMode(long monitor) {
        ensureVideoMode();
        return new GLFWVidMode(videoModeMemory.duplicate().order(ByteOrder.nativeOrder()));
    }

    public static GLFWVidMode.Buffer getVideoModes(long monitor) {
        ensureVideoMode();
        return GLFWVidMode.create(MemoryUtil.memAddress(videoModeMemory), 1);
    }

    private static void ensureVideoMode() {
        if (videoModeMemory != null) {
            return;
        }
        videoModeMemory = ByteBuffer.allocateDirect(GLFWVidMode.SIZEOF).order(ByteOrder.nativeOrder());
        videoModeMemory.putInt(GLFWVidMode.WIDTH, screenWidth());
        videoModeMemory.putInt(GLFWVidMode.HEIGHT, screenHeight());
        videoModeMemory.putInt(GLFWVidMode.REDBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.GREENBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.BLUEBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.REFRESHRATE, 60);
    }

    public static void pollEvents() {
        maybeQueueInputWarmup();
        while (eventCount() > 0) {
            int type = eventInt(0);
            int a = eventInt(1);
            int b = eventInt(2);
            int c = eventInt(3);
            int d = eventInt(4);
            double x = eventDouble(5);
            double y = eventDouble(6);
            removeEvent();
            switch (type) {
                case 1 -> {
                    reportInputEvent(type, a, b, c, x, y, keyCallback != null);
                    if (keyCallback != null) {
                        keyCallback.invoke(WINDOW, a, b, c, d);
                    }
                }
                case 2 -> {
                    reportInputEvent(type, a, b, c, x, y, charCallback != null || charModsCallback != null);
                    if (charCallback != null) {
                        charCallback.invoke(WINDOW, a);
                    }
                    if (charModsCallback != null) {
                        charModsCallback.invoke(WINDOW, a, b);
                    }
                }
                case 3 -> {
                    cursorX = x;
                    cursorY = y;
                    reportInputEvent(type, a, b, c, x, y, mouseButtonCallback != null);
                    if (mouseButtonCallback != null) {
                        mouseButtonCallback.invoke(WINDOW, a, b, c);
                    }
                }
                case 4 -> {
                    cursorX = x;
                    cursorY = y;
                    reportInputEvent(type, a, b, c, x, y, cursorPosCallback != null);
                    if (cursorPosCallback != null) {
                        cursorPosCallback.invoke(WINDOW, x, y);
                    }
                }
                case 5 -> {
                    reportInputEvent(type, a, b, c, x, y, scrollCallback != null);
                    if (scrollCallback != null) {
                        scrollCallback.invoke(WINDOW, x, y);
                    }
                }
                case 6 -> {
                    if (windowFocusCallback != null) {
                        windowFocusCallback.invoke(WINDOW, a != 0);
                    }
                }
                case 7 -> {
                    if (windowSizeCallback != null) {
                        windowSizeCallback.invoke(WINDOW, a, b);
                    }
                    if (framebufferSizeCallback != null) {
                        framebufferSizeCallback.invoke(WINDOW, c, d);
                    }
                }
                case 8 -> {
                    shouldClose = true;
                    if (windowCloseCallback != null) {
                        windowCloseCallback.invoke(WINDOW);
                    }
                }
                case 9 -> {
                    if (cursorEnterCallback != null) {
                        cursorEnterCallback.invoke(WINDOW, a != 0);
                    }
                }
                default -> {
                }
            }
        }
    }

    public static void waitEvents() {
        pollEvents();
        sleepForBrowserMillis(1L);
    }

    public static void waitEventsTimeout(double timeout) {
        pollEvents();
        if (timeout > 0.0 && Double.isFinite(timeout)) {
            long millis = (long) Math.floor(timeout * 1000.0);
            // A second 1 ms browser timer commonly overshoots the frame deadline.
            if (millis <= 1L) {
                return;
            }
            sleepForBrowserMillis(Math.max(1L, Math.min(7L, millis - 1L)));
        } else {
            Thread.yield();
        }
    }

    public static void postEmptyEvent() {
    }

    private static void sleepForBrowserMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static int getInputMode(long window, int mode) {
        return inputMode(mode);
    }

    public static void setInputMode(long window, int mode, int value) {
        setInputModeJs(mode, value);
    }

    public static boolean rawMouseMotionSupported() {
        return false;
    }

    public static String getKeyName(int key, int scancode) {
        int value = key >= 0 ? key : scancode;
        if (value >= GLFW.GLFW_KEY_A && value <= GLFW.GLFW_KEY_Z) {
            return Character.toString((char) ('a' + (value - GLFW.GLFW_KEY_A)));
        }
        if (value >= GLFW.GLFW_KEY_0 && value <= GLFW.GLFW_KEY_9) {
            return Character.toString((char) ('0' + (value - GLFW.GLFW_KEY_0)));
        }
        return switch (value) {
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_KP_0 -> "0";
            case GLFW.GLFW_KEY_KP_1 -> "1";
            case GLFW.GLFW_KEY_KP_2 -> "2";
            case GLFW.GLFW_KEY_KP_3 -> "3";
            case GLFW.GLFW_KEY_KP_4 -> "4";
            case GLFW.GLFW_KEY_KP_5 -> "5";
            case GLFW.GLFW_KEY_KP_6 -> "6";
            case GLFW.GLFW_KEY_KP_7 -> "7";
            case GLFW.GLFW_KEY_KP_8 -> "8";
            case GLFW.GLFW_KEY_KP_9 -> "9";
            case GLFW.GLFW_KEY_KP_DECIMAL -> ".";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "/";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
            case GLFW.GLFW_KEY_KP_ADD -> "+";
            case GLFW.GLFW_KEY_KP_EQUAL -> "=";
            default -> null;
        };
    }

    public static int getKeyScancode(int key) {
        return key;
    }

    public static int getKey(long window, int key) {
        return keyDown(key) ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
    }

    public static int getMouseButton(long window, int button) {
        return mouseDown(button) ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
    }

    public static void getCursorPos(long window, double[] x, double[] y) {
        set(x, cursorX);
        set(y, cursorY);
    }

    public static void setCursorPos(long window, double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public static long createStandardCursor(int shape) {
        return shape + 16L;
    }

    public static void destroyCursor(long cursor) {
    }

    public static void setCursor(long window, long cursor) {
    }

    public static boolean joystickPresent(int joystick) {
        return false;
    }

    public static void setClipboardString(long window, CharSequence value) {
        writeClipboard(value == null ? "" : value.toString());
    }

    public static String getClipboardString(long window) {
        return readClipboard();
    }

    public static double getTime() {
        return now();
    }

    public static void setTime(double time) {
    }

    public static long getTimerValue() {
        return (long) (now() * 1_000_000.0);
    }

    public static long getTimerFrequency() {
        return 1_000_000L;
    }

    public static void makeContextCurrent(long window) {
    }

    public static long getCurrentContext() {
        return WINDOW;
    }

    @JSBody(script = """
            const fps=window.__gaiusFps || (window.__gaiusFps={});
            const telemetry=window.__gaiusFrameTelemetry;
            let now;
            if (telemetry && telemetry.enabled) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              const previous=telemetry.lastFrameAt;
              if (Number.isFinite(previous) && previous > 0) {
                const frameElapsed=Math.max(0, now-previous);
                const bucket=Math.min(4000, Math.floor(frameElapsed*4));
                let histogram=telemetry.histogram;
                if (!histogram || histogram.length !== 4001) {
                  histogram=new Uint32Array(4001);
                  telemetry.histogram=histogram;
                }
                histogram[bucket]=histogram[bucket]+1;
                telemetry.frameCount=(telemetry.frameCount||0)+1;
                telemetry.totalFrameMillis=(telemetry.totalFrameMillis||0)+frameElapsed;
                telemetry.longestFrameMillis=Math.max(telemetry.longestFrameMillis||0, frameElapsed);
              }
              telemetry.lastFrameAt=now;
            }
            fps.gameFrames=(fps.gameFrames||0)+1;
            fps.gameSampleCounter=((fps.gameSampleCounter||0)+1)&15;
            if (fps.gameSampleCounter !== 0 && fps.gameLastSampleAt) {
              return;
            }
            if (now === undefined) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            }
            if (!fps.gameLastSampleAt) fps.gameLastSampleAt=now;
            const elapsed=now-fps.gameLastSampleAt;
            if (elapsed >= 1000) {
              fps.gameFps=Math.round((fps.gameFrames*1000/elapsed)*10)/10;
              fps.gameFrames=0;
              fps.gameLastSampleAt=now;
            }
            """)
    private static native void swapBuffersJs();

    public static void swapBuffers(long window) {
        swapBuffersJs();
    }

    public static void swapInterval(int interval) {
    }

    public static int getError(PointerBuffer description) {
        return GLFW.GLFW_NO_ERROR;
    }

    public static GLFWErrorCallback setErrorCallback(GLFWErrorCallbackI callback) {
        errorCallback = callback;
        return null;
    }

    public static GLFWMonitorCallback setMonitorCallback(GLFWMonitorCallbackI callback) {
        monitorCallback = callback;
        return null;
    }

    public static GLFWWindowPosCallback setWindowPosCallback(long window, GLFWWindowPosCallbackI callback) {
        windowPosCallback = callback;
        return null;
    }

    public static GLFWWindowSizeCallback setWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) {
        windowSizeCallback = callback;
        return null;
    }

    public static GLFWWindowCloseCallback setWindowCloseCallback(long window, GLFWWindowCloseCallbackI callback) {
        windowCloseCallback = callback;
        return null;
    }

    public static GLFWWindowFocusCallback setWindowFocusCallback(long window, GLFWWindowFocusCallbackI callback) {
        windowFocusCallback = callback;
        return null;
    }

    public static GLFWWindowIconifyCallback setWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI callback) {
        windowIconifyCallback = callback;
        return null;
    }

    public static GLFWFramebufferSizeCallback setFramebufferSizeCallback(
            long window, GLFWFramebufferSizeCallbackI callback) {
        framebufferSizeCallback = callback;
        return null;
    }

    public static GLFWCursorEnterCallback setCursorEnterCallback(long window, GLFWCursorEnterCallbackI callback) {
        cursorEnterCallback = callback;
        return null;
    }

    public static GLFWKeyCallback setKeyCallback(long window, GLFWKeyCallbackI callback) {
        keyCallback = callback;
        reportCallback("key", callback != null);
        return null;
    }

    public static GLFWCharCallback setCharCallback(long window, GLFWCharCallbackI callback) {
        charCallback = callback;
        reportCallback("char", callback != null);
        return null;
    }

    public static GLFWCharModsCallback setCharModsCallback(long window, GLFWCharModsCallbackI callback) {
        charModsCallback = callback;
        reportCallback("charMods", callback != null);
        return null;
    }

    public static GLFWMouseButtonCallback setMouseButtonCallback(long window, GLFWMouseButtonCallbackI callback) {
        mouseButtonCallback = callback;
        reportCallback("mouseButton", callback != null);
        return null;
    }

    public static GLFWCursorPosCallback setCursorPosCallback(long window, GLFWCursorPosCallbackI callback) {
        cursorPosCallback = callback;
        reportCallback("cursorPos", callback != null);
        if (callback != null) {
            callback.invoke(WINDOW, cursorX, cursorY);
        }
        return null;
    }

    public static GLFWScrollCallback setScrollCallback(long window, GLFWScrollCallbackI callback) {
        scrollCallback = callback;
        reportCallback("scroll", callback != null);
        return null;
    }

    public static GLFWDropCallback setDropCallback(long window, GLFWDropCallbackI callback) {
        dropCallback = callback;
        return null;
    }

    private static void set(int[] values, int value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    private static void set(float[] values, float value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    private static void set(double[] values, double value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    @JSBody(script = """
            if (window.__gaiusGlfwInstalled) return;
            window.__gaiusGlfwInstalled = true;
            window.__gaiusGlfwEvents = [];
            window.__gaiusGlfwEventHead = 0;
            window.__gaiusGlfwPendingMouseMove = -1;
            window.__gaiusGlfwKeys = Object.create(null);
            window.__gaiusGlfwButtons = Object.create(null);
            window.__gaiusGlfwInputModes = Object.create(null);
            window.__gaiusInputStats = window.__gaiusInputStats || {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            };
            window.__gaiusWantPointerLock = false;
            window.__gaiusPointerLockErrors = [];
            window.__gaiusMaxDpr = Number.isFinite(Number(window.__gaiusMaxDpr))
              ? Number(window.__gaiusMaxDpr)
              : 1.0;
            const rememberPointerLockError = error => {
              const message = String(error && (error.message || error.name) || error);
              window.__gaiusPointerLockLastError = message;
              window.__gaiusPointerLockErrors.push({message: message, at: Date.now()});
              if (window.__gaiusPointerLockErrors.length > 20) window.__gaiusPointerLockErrors.shift();
            };
            const requestPointerLockIfWanted = () => {
              const c = canvas();
              if (!window.__gaiusWantPointerLock || !c || !c.requestPointerLock || document.pointerLockElement === c) {
                return;
              }
              try {
                const lockResult = c.requestPointerLock();
                if (lockResult && lockResult.catch) lockResult.catch(rememberPointerLockError);
              } catch (error) {
                rememberPointerLockError(error);
              }
            };
            const codeMap = {
              Space:32,Apostrophe:39,Comma:44,Minus:45,Period:46,Slash:47,
              Digit0:48,Digit1:49,Digit2:50,Digit3:51,Digit4:52,Digit5:53,Digit6:54,Digit7:55,Digit8:56,Digit9:57,
              Semicolon:59,Equal:61,KeyA:65,KeyB:66,KeyC:67,KeyD:68,KeyE:69,KeyF:70,KeyG:71,KeyH:72,KeyI:73,
              KeyJ:74,KeyK:75,KeyL:76,KeyM:77,KeyN:78,KeyO:79,KeyP:80,KeyQ:81,KeyR:82,KeyS:83,KeyT:84,
              KeyU:85,KeyV:86,KeyW:87,KeyX:88,KeyY:89,KeyZ:90,BracketLeft:91,Backslash:92,BracketRight:93,
              Backquote:96,Escape:256,Enter:257,Tab:258,Backspace:259,Insert:260,Delete:261,ArrowRight:262,
              ArrowLeft:263,ArrowDown:264,ArrowUp:265,PageUp:266,PageDown:267,Home:268,End:269,CapsLock:280,
              ScrollLock:281,NumLock:282,PrintScreen:283,Pause:284,F1:290,F2:291,F3:292,F4:293,F5:294,F6:295,
              F7:296,F8:297,F9:298,F10:299,F11:300,F12:301,Numpad0:320,Numpad1:321,Numpad2:322,Numpad3:323,
              Numpad4:324,Numpad5:325,Numpad6:326,Numpad7:327,Numpad8:328,Numpad9:329,NumpadDecimal:330,
              NumpadDivide:331,NumpadMultiply:332,NumpadSubtract:333,NumpadAdd:334,NumpadEnter:335,NumpadEqual:336,
              ShiftLeft:340,ControlLeft:341,AltLeft:342,MetaLeft:343,ShiftRight:344,ControlRight:345,AltRight:346,MetaRight:347
            };
            const mods = e => (e.shiftKey?1:0)|(e.ctrlKey?2:0)|(e.altKey?4:0)|(e.metaKey?8:0);
            const canvas = () => document.getElementById('mc-canvas');
            const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
            const glfwMouseButton = button => button === 2 ? 1 : (button === 1 ? 2 : button);
            const pushEvent = event => {
              if (!window.__gaiusGlfwEvents) window.__gaiusGlfwEvents = [];
              window.__gaiusGlfwEvents.push(event);
            };
            const pushMouseMove = event => {
              const events = window.__gaiusGlfwEvents || (window.__gaiusGlfwEvents = []);
              const head = window.__gaiusGlfwEventHead | 0;
              const pending = window.__gaiusGlfwPendingMouseMove | 0;
              if (pending >= head && pending < events.length && events[pending] && events[pending][0] === 4) {
                events[pending] = event;
                return;
              }
              window.__gaiusGlfwPendingMouseMove = events.length;
              events.push(event);
            };
            const canvasRect = c => {
              const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              const cached = window.__gaiusCanvasRect;
              if (cached && now - (window.__gaiusCanvasRectAt || 0) < 250) return cached;
              const rect = c ? c.getBoundingClientRect() : {left:0,top:0,width:0,height:0};
              window.__gaiusCanvasRect = {left:rect.left, top:rect.top, width:rect.width, height:rect.height};
              window.__gaiusCanvasRectAt = now;
              return window.__gaiusCanvasRect;
            };
            const updateCursorFromMouseEvent = e => {
              const c=canvas(), r=canvasRect(c);
              const locked=document.pointerLockElement===c;
              window.__gaiusCursorX=locked?(window.__gaiusCursorX||0)+e.movementX:e.clientX-r.left;
              window.__gaiusCursorY=locked?(window.__gaiusCursorY||0)+e.movementY:e.clientY-r.top;
              return [window.__gaiusCursorX, window.__gaiusCursorY];
            };
            const urlNumber = name => {
              try {
                const value = new URLSearchParams(location.search).get(name);
                const number = Number(value);
                return Number.isFinite(number) ? number : NaN;
              } catch (ignored) {
                return NaN;
              }
            };
            window.__gaiusResolvePixelRatio = () => {
              const minecraftState = window.__gaiusMinecraftState || null;
              const inWorld = !!(minecraftState && minecraftState.level);
              const minDpr = clamp(
                inWorld
                  ? (Number(window.__gaiusWorldMinDpr) || Number(window.__gaiusMinDpr) || 1.0)
                  : (Number(window.__gaiusMenuMinDpr) || Number(window.__gaiusMinDpr) || 1.0),
                1.0,
                3.0);
              const forced = urlNumber('dpr');
              if (forced > 0) return clamp(forced, 0.2, 3.0);
              const forcedPixelRatio = urlNumber('pixelRatio');
              if (forcedPixelRatio > 0) return clamp(forcedPixelRatio, 0.2, 3.0);
              const urlMax = urlNumber('maxDpr');
              const maxDpr = urlMax > 0 ? urlMax : (Number(window.__gaiusMaxDpr) || 1.0);
              const raw = Number(devicePixelRatio) || 1.0;
              return clamp(Math.min(raw, maxDpr), minDpr, 3.0);
            };
            window.__gaiusApplyCanvasResolution = (width, height, emitEvent) => {
              const c = canvas();
              if (!c) return;
              const cssWidth = Math.max(1, Math.round(Number(width) || innerWidth || 1));
              const cssHeight = Math.max(1, Math.round(Number(height) || innerHeight || 1));
              const pixelRatio = window.__gaiusResolvePixelRatio();
              const framebufferWidth = Math.max(1, Math.round(cssWidth * pixelRatio));
              const framebufferHeight = Math.max(1, Math.round(cssHeight * pixelRatio));
              c.style.width = cssWidth + 'px';
              c.style.height = cssHeight + 'px';
              const changed = c.width !== framebufferWidth || c.height !== framebufferHeight;
              c.width = framebufferWidth;
              c.height = framebufferHeight;
              window.__gaiusDisplay = {
                cssWidth: cssWidth,
                cssHeight: cssHeight,
                framebufferWidth: framebufferWidth,
                framebufferHeight: framebufferHeight,
                pixelRatio: pixelRatio,
                rawDevicePixelRatio: Number(devicePixelRatio) || 1.0,
                maxDpr: Number(window.__gaiusMaxDpr) || 1.0
              };
              window.__gaiusCanvasRect = null;
              if (emitEvent && changed) {
                pushEvent([7,cssWidth,cssHeight,framebufferWidth,framebufferHeight,0,0]);
              }
            };
            addEventListener('keydown', e => {
              const key=codeMap[e.code]===undefined?-1:codeMap[e.code]; window.__gaiusGlfwKeys[key]=true;
              pushEvent([1,key,e.keyCode,e.repeat?2:1,mods(e),0,0]);
              if (e.key && e.key.length>0 && e.key.length<=2) pushEvent([2,e.key.codePointAt(0),mods(e),0,0,0,0]);
              if (document.activeElement===canvas()) e.preventDefault();
            });
            addEventListener('keyup', e => {
              const key=codeMap[e.code]===undefined?-1:codeMap[e.code]; window.__gaiusGlfwKeys[key]=false;
              pushEvent([1,key,e.keyCode,0,mods(e),0,0]);
            });
            addEventListener('mousedown', e => {
              const c = canvas();
              if (c) c.focus();
              const p = updateCursorFromMouseEvent(e);
              const button = glfwMouseButton(e.button);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
              window.__gaiusGlfwButtons[button]=true;
              pushEvent([3,button,1,mods(e),0,p[0],p[1]]);
              requestPointerLockIfWanted();
            });
            addEventListener('mouseup', e => {
              const p = updateCursorFromMouseEvent(e);
              const button = glfwMouseButton(e.button);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
              window.__gaiusGlfwButtons[button]=false;
              pushEvent([3,button,0,mods(e),0,p[0],p[1]]);
            });
            addEventListener('mousemove', e => {
              const p = updateCursorFromMouseEvent(e);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
            });
            addEventListener('wheel', e => {
              pushEvent([5,0,0,0,0,-e.deltaX/100,-e.deltaY/100]);
              if (document.activeElement===canvas()) e.preventDefault();
            }, {passive:false});
            addEventListener('focus', () => pushEvent([6,1,0,0,0,0,0]));
            addEventListener('blur', () => pushEvent([6,0,0,0,0,0,0]));
            addEventListener('resize', () => {
              window.__gaiusApplyCanvasResolution(innerWidth, innerHeight, true);
            });
            addEventListener('beforeunload', () => pushEvent([8,0,0,0,0,0,0]));
            """)
    private static native void installDomBridge();

    @JSBody(params = {"width", "height", "title"}, script = """
            let canvasElement=document.getElementById('mc-canvas');
            if (!canvasElement) {
              canvasElement=document.createElement('canvas');
              canvasElement.id='mc-canvas';
              canvasElement.tabIndex=0;
              document.body.appendChild(canvasElement);
            }
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const pixelRatio=Math.min(devicePixelRatio||1,1);
              canvasElement.style.width=width+'px'; canvasElement.style.height=height+'px';
              canvasElement.width=Math.max(1,Math.round(width*pixelRatio));
              canvasElement.height=Math.max(1,Math.round(height*pixelRatio));
            }
            document.title=title; canvasElement.focus();
            let preserveDrawingBuffer = false;
            try {
              preserveDrawingBuffer = new URLSearchParams(location.search).get('preserveDrawingBuffer') === '1';
            } catch (ignored) {
              preserveDrawingBuffer = false;
            }
            window.__gaiusWebGL=canvasElement.getContext('webgl2',{
              alpha:false,
              antialias:false,
              depth:true,
              stencil:true,
              powerPreference:'high-performance',
              preserveDrawingBuffer: preserveDrawingBuffer
            });
            if (!window.__gaiusWebGL) throw new Error('WebGL 2 is required');
            """)
    private static native void createCanvas(int width, int height, String title);

    @JSBody(params = {"title"}, script = "document.title=title;")
    private static native void setTitle(String title);

    @JSBody(params = {"width", "height"}, script = """
            const canvasElement=document.getElementById('mc-canvas'); if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const pixelRatio=Math.min(devicePixelRatio||1,1);
              canvasElement.style.width=width+'px'; canvasElement.style.height=height+'px';
              canvasElement.width=Math.max(1,Math.round(width*pixelRatio));
              canvasElement.height=Math.max(1,Math.round(height*pixelRatio));
            }
            """)
    private static native void resizeCanvas(int width, int height);

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?Math.round(canvasElement.getBoundingClientRect().width):innerWidth;")
    private static native int canvasCssWidth();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?Math.round(canvasElement.getBoundingClientRect().height):innerHeight;")
    private static native int canvasCssHeight();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?canvasElement.width:innerWidth;")
    private static native int framebufferWidth();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?canvasElement.height:innerHeight;")
    private static native int framebufferHeight();

    @JSBody(script = "return screen.width|0;")
    private static native int screenWidth();

    @JSBody(script = "return screen.height|0;")
    private static native int screenHeight();

    @JSBody(script = "return window.__gaiusResolvePixelRatio?window.__gaiusResolvePixelRatio():(devicePixelRatio||1);")
    private static native double devicePixelRatio();

    @JSBody(script = "return Math.max(0,((window.__gaiusGlfwEvents||[]).length|0)-((window.__gaiusGlfwEventHead||0)|0))|0;")
    private static native int eventCount();

    @JSBody(script = """
            if (window.__gaiusInputWarmupDone) return;
            const minecraftState = window.__gaiusMinecraftState || null;
            if (!minecraftState || !minecraftState.screen || minecraftState.level) return;
            const events = window.__gaiusGlfwEvents || (window.__gaiusGlfwEvents = []);
            const head = (window.__gaiusGlfwEventHead || 0) | 0;
            if (events.length - head > 0) return;
            window.__gaiusInputWarmupDone = true;
            window.__gaiusCursorX = 1;
            window.__gaiusCursorY = 1;
            events.push([4,0,0,0,0,1,1]);
            events.push([3,0,1,0,0,0,0]);
            events.push([3,0,0,0,0,0,0]);
            """)
    private static native void maybeQueueInputWarmup();

    @JSBody(params = {"index"}, script = "return window.__gaiusGlfwEvents[window.__gaiusGlfwEventHead||0][index]|0;")
    private static native int eventInt(int index);

    @JSBody(params = {"index"}, script = "return +window.__gaiusGlfwEvents[window.__gaiusGlfwEventHead||0][index];")
    private static native double eventDouble(int index);

    @JSBody(params = {"name", "installed"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.callbacks[name] = !!installed;
            stats.lastCallback = {name: name, installed: !!installed, at: Date.now()};
            """)
    private static native void reportCallback(String name, boolean installed);

    @JSBody(params = {"type", "a", "b", "c", "x", "y", "callbackInstalled"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            const key = String(type | 0);
            stats.totalEvents = (stats.totalEvents || 0) + 1;
            stats.events[key] = (stats.events[key] || 0) + 1;
            if (!callbackInstalled) stats.callbackMisses[key] = (stats.callbackMisses[key] || 0) + 1;
            stats.lastEvent = {
              type: type | 0,
              a: a | 0,
              b: b | 0,
              c: c | 0,
              x: Number(x),
              y: Number(y),
              callbackInstalled: !!callbackInstalled,
              at: Date.now()
            };
            """)
    private static native void reportInputEvent(
            int type, int a, int b, int c, double x, double y, boolean callbackInstalled);

    @JSBody(params = {"callbackWindow", "minecraftWindow", "button", "action"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseHandlerEntry = {
              callbackWindow: Number(callbackWindow),
              minecraftWindow: Number(minecraftWindow),
              windowMatches: Number(callbackWindow) === Number(minecraftWindow),
              button: button | 0,
              action: action | 0,
              at: Date.now()
            };
            """)
    public static native void reportMouseHandlerEntry(
            long callbackWindow, long minecraftWindow, int button, int action);

    @JSBody(params = {"scaledX", "scaledY", "pressed", "screen"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseHandlerDispatch = {
              scaledX: Number(scaledX),
              scaledY: Number(scaledY),
              pressed: !!pressed,
              screen: screen == null ? null : String(screen),
              at: Date.now()
            };
            """)
    public static native void reportMouseHandlerDispatch(
            double scaledX, double scaledY, boolean pressed, Object screen);

    @JSBody(params = {"clicked", "scaledX", "scaledY", "screen"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseClickedResult = {
              clicked: !!clicked,
              scaledX: Number(scaledX),
              scaledY: Number(scaledY),
              screen: screen == null ? null : String(screen),
              at: Date.now()
            };
            """)
    public static native void reportMouseClickedResult(
            boolean clicked, double scaledX, double scaledY, Object screen);

    @JSBody(script = """
            const events=window.__gaiusGlfwEvents || [];
            const head=((window.__gaiusGlfwEventHead || 0) + 1)|0;
            window.__gaiusGlfwEventHead=head;
            if ((window.__gaiusGlfwPendingMouseMove|0) < head) {
              window.__gaiusGlfwPendingMouseMove=-1;
            }
            if (head > 128 && head * 2 > events.length) {
              events.splice(0,head);
              window.__gaiusGlfwEventHead=0;
              if ((window.__gaiusGlfwPendingMouseMove|0) >= head) {
                window.__gaiusGlfwPendingMouseMove=(window.__gaiusGlfwPendingMouseMove|0)-head;
              }
            }
            """)
    private static native void removeEvent();

    @JSBody(params = {"mode"}, script = "return (window.__gaiusGlfwInputModes[mode]||0)|0;")
    private static native int inputMode(int mode);

    @JSBody(params = {"mode", "value"}, script = """
            window.__gaiusGlfwInputModes[mode]=value;
            if (mode===0x00033001) {
              const canvasElement=document.getElementById('mc-canvas');
              if (value===0x00034003) {
                window.__gaiusWantPointerLock = true;
              } else if (document.exitPointerLock) {
                window.__gaiusWantPointerLock = false;
                try {
                  const exitResult = document.exitPointerLock();
                  if (exitResult && exitResult.catch) exitResult.catch(() => {});
                } catch (error) {
                  // Ignore pointer-lock exit failures; GLFW treats this as best-effort cursor state.
                }
              }
            }
            """)
    private static native void setInputModeJs(int mode, int value);

    @JSBody(params = {"key"}, script = "return !!window.__gaiusGlfwKeys[key];")
    private static native boolean keyDown(int key);

    @JSBody(params = {"button"}, script = "return !!window.__gaiusGlfwButtons[button];")
    private static native boolean mouseDown(int button);

    @JSBody(params = {"value"}, script = "if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(value);")
    private static native void writeClipboard(String value);

    @JSBody(script = "return window.__gaiusClipboard||'';")
    private static native String readClipboard();

    @JSBody(script = "return performance.now()/1000;")
    private static native double now();
}
