package dev.gaius.browser;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;

/** Fast TeaVM/browser verification for the platform layer used by Minecraft. */
public final class PlatformSmoke {
    private static int keyEvents;
    private static int cursorEvents;
    private static int mouseEvents;
    private static int scrollEvents;

    private PlatformSmoke() {
    }

    public static void main(String[] args) {
        try {
            testRandomAccessFile();
            testManagedMemory();
            testWindowAndCallbacks();
            report(true, "Gaius platform smoke passed");
        } catch (Throwable failure) {
            failure.printStackTrace();
            report(false, failure.getClass().getName() + ": " + failure.getMessage());
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new RuntimeException(failure);
        }
    }

    private static void testRandomAccessFile() throws Exception {
        Path path = Path.of("/gaius-smoke/region-test.mca");
        byte[] expected = "official-1.21.11".getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(8192);
            channel.write(ByteBuffer.wrap(expected));
            channel.force(false);
            if (channel.size() != 8192L + expected.length) {
                throw new AssertionError("FileChannel size mismatch: " + channel.size());
            }
            ByteBuffer read = ByteBuffer.allocate(expected.length);
            channel.position(8192);
            while (read.hasRemaining() && channel.read(read) >= 0) {
                // Keep reading until the requested region is complete.
            }
            for (int index = 0; index < expected.length; index++) {
                if (read.array()[index] != expected[index]) {
                    throw new AssertionError("FileChannel data mismatch at " + index);
                }
            }
        }
    }

    private static void testManagedMemory() {
        long address = MemoryUtil.nmemAllocChecked(32);
        try {
            MemoryUtil.memPutInt(address + 4, 0x12111);
            if (MemoryUtil.memGetInt(address + 4) != 0x12111) {
                throw new AssertionError("Managed LWJGL memory mismatch");
            }
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    private static void testWindowAndCallbacks() {
        GLFWErrorCallback callback = GLFWErrorCallback.create((error, description) -> {
        });
        GLFW.glfwSetErrorCallback(callback);
        if (!GLFW.glfwInit()) {
            throw new AssertionError("GLFW browser initialization failed");
        }
        long window = GLFW.glfwCreateWindow(960, 540, "Gaius 1.21.11 platform smoke", 0L, 0L);
        if (window == 0L) {
            throw new AssertionError("Browser window was not created");
        }
        GLFW.glfwSetKeyCallback(window, (handle, key, scancode, action, modifiers) -> {
            keyEvents++;
        });
        GLFW.glfwSetCursorPosCallback(window, (handle, x, y) -> {
            cursorEvents++;
        });
        GLFW.glfwSetMouseButtonCallback(window, (handle, button, action, modifiers) -> {
            mouseEvents++;
        });
        GLFW.glfwSetScrollCallback(window, (handle, x, y) -> {
            scrollEvents++;
        });
        GLFWVidMode mode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        if (mode == null || mode.width() <= 0 || mode.height() <= 0) {
            throw new AssertionError("Browser video mode is invalid");
        }
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            throw new AssertionError("Browser framebuffer is invalid");
        }
        enqueueSyntheticInput();
        GLFW.glfwPollEvents();
        if (keyEvents != 1 || cursorEvents != 1 || mouseEvents != 1 || scrollEvents != 1) {
            throw new AssertionError(
                    "GLFW event bridge mismatch: "
                            + keyEvents + "/" + cursorEvents + "/" + mouseEvents + "/" + scrollEvents);
        }
        testWebGlRenderingSurface();
        callback.free();
    }

    private static void testWebGlRenderingSurface() {
        GL.createCapabilities();
        GL11.glClearColor(0.08F, 0.10F, 0.14F, 1.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);

        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(
                GL15.GL_ARRAY_BUFFER,
                ByteBuffer.wrap(new byte[] {0, 1, 2, 3}),
                GL15.GL_STATIC_DRAW);

        int shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(
                shader,
                "#version 300 es\n"
                        + "void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new AssertionError("WebGL shader compile failed: " + GL20.glGetShaderInfoLog(shader));
        }
        GL20.glDeleteShader(shader);
        GL15.glDeleteBuffers(buffer);
        GL11.glDeleteTextures(texture);
    }

    @JSBody(script = """
            window.__gaiusGlfwEvents.push([1,87,87,1,0,0,0]);
            window.__gaiusGlfwEvents.push([4,0,0,0,0,32,48]);
            window.__gaiusGlfwEvents.push([3,0,1,0,0,0,0]);
            window.__gaiusGlfwEvents.push([5,0,0,0,0,0,1]);
            """)
    private static native void enqueueSyntheticInput();

    @JSBody(params = {"success", "message"}, script = """
            const output=document.getElementById('status');
            if (output) {
              output.textContent=message;
              output.dataset.success=success?'true':'false';
            }
            console[success?'info':'error'](message);
            """)
    private static native void report(boolean success, String message);
}
