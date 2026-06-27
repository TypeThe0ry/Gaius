package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.lwjgl.system.MemoryUtil;

/** WebGL2 implementation used by patched LWJGL OpenGL entry points. */
public final class BrowserOpenGL {
    private static final Map<Integer, MappedBuffer> MAPPED_BUFFERS = new HashMap<>();
    private static int unpackAlignment = 4;
    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;

    private BrowserOpenGL() {
    }

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            if (!gl) throw new Error('WebGL2 context is not initialized');
            if (!window.__gaiusGL) {
              window.__gaiusGL={next:1,textures:new Map(),buffers:new Map(),shaders:new Map(),
                programs:new Map(),framebuffers:new Map(),vaos:new Map(),samplers:new Map(),syncs:new Map(),
                bufferSizes:new Map(),boundBuffers:new Map(),framebufferBindings:{draw:0,read:0}};
            }
            """)
    public static native void initialize();

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            window.__gaiusWebGL.enable(capability);
            """)
    public static native void enable(int capability);

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            window.__gaiusWebGL.disable(capability);
            """)
    public static native void disable(int capability);

    @JSBody(params = {"red", "green", "blue", "alpha"},
            script = "window.__gaiusWebGL.clearColor(red,green,blue,alpha);")
    public static native void clearColor(float red, float green, float blue, float alpha);

    @JSBody(params = {"depth"}, script = "window.__gaiusWebGL.clearDepth(depth);")
    public static native void clearDepth(double depth);

    @JSBody(params = {"mask"}, script = "window.__gaiusWebGL.clear(mask);")
    public static native void clear(int mask);

    @JSBody(params = {"red", "green", "blue", "alpha"},
            script = "window.__gaiusWebGL.colorMask(red,green,blue,alpha);")
    public static native void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    @JSBody(params = {"func"}, script = "window.__gaiusWebGL.depthFunc(func);")
    public static native void depthFunc(int function);

    @JSBody(params = {"enabled"}, script = "window.__gaiusWebGL.depthMask(enabled);")
    public static native void depthMask(boolean enabled);

    @JSBody(params = {"mode", "first", "count"},
            script = "window.__gaiusWebGL.drawArrays(mode,first,count);")
    public static native void drawArrays(int mode, int first, int count);

    @JSBody(params = {"mode", "count", "type", "offset"},
            script = "window.__gaiusWebGL.drawElements(mode,count,type,Number(offset));")
    public static native void drawElements(int mode, int count, int type, long offset);

    @JSBody(script = "return window.__gaiusWebGL.getError()|0;")
    public static native int getError();

    @JSBody(params = {"parameter"}, script = """
            const state=window.__gaiusGL;
            if (state && parameter===0x8CA6) return state.framebufferBindings.draw|0;
            if (state && parameter===0x8CAA) return state.framebufferBindings.read|0;
            const value=window.__gaiusWebGL.getParameter(parameter);
            return typeof value==='number' ? value|0 : 0;
            """)
    public static native int getInteger(int parameter);

    @JSBody(params = {"parameter"}, script = """
            const value=window.__gaiusWebGL.getParameter(parameter);
            return typeof value==='number' ? +value : 0;
            """)
    public static native float getFloat(int parameter);

    @JSBody(params = {"parameter"}, script = """
            const gl=window.__gaiusWebGL;
            if (parameter===0x1F00) return gl.getParameter(gl.VENDOR)||'Browser';
            if (parameter===0x1F01) return gl.getParameter(gl.RENDERER)||'WebGL2';
            if (parameter===0x1F02) return 'OpenGL 3.3 (WebGL2)';
            if (parameter===0x8B8C) return gl.getParameter(gl.SHADING_LANGUAGE_VERSION)||'WebGL GLSL ES 3.00';
            return '';
            """)
    public static native String getString(int parameter);

    @JSBody(params = {"operation"}, script = """
            const gl=window.__gaiusWebGL;
            if (gl.logicOp) gl.logicOp(operation);
            """)
    public static native void logicOp(int operation);

    public static void pixelStorei(int parameter, int value) {
        int browserValue = value;
        switch (parameter) {
            case 0x0CF2 -> unpackRowLength = Math.max(0, value);
            case 0x0CF3 -> unpackSkipRows = Math.max(0, value);
            case 0x0CF4 -> unpackSkipPixels = Math.max(0, value);
            case 0x0CF5 -> {
                browserValue = webGlUnpackAlignment(value);
                unpackAlignment = browserValue;
            }
            default -> {
            }
        }
        pixelStoreiJs(parameter, browserValue);
    }

    @JSBody(params = {"parameter", "value"}, script = "window.__gaiusWebGL.pixelStorei(parameter,value);")
    private static native void pixelStoreiJs(int parameter, int value);

    public static void polygonMode(int face, int mode) {
        // WebGL only supports filled polygons.
    }

    @JSBody(params = {"factor", "units"}, script = "window.__gaiusWebGL.polygonOffset(factor,units);")
    public static native void polygonOffset(float factor, float units);

    @JSBody(params = {"x", "y", "width", "height"},
            script = "window.__gaiusWebGL.viewport(x,y,width,height);")
    public static native void viewport(int x, int y, int width, int height);

    @JSBody(params = {"x", "y", "width", "height"},
            script = "window.__gaiusWebGL.scissor(x,y,width,height);")
    public static native void scissor(int x, int y, int width, int height);

    @JSBody(params = {"sourceRgb", "destinationRgb", "sourceAlpha", "destinationAlpha"},
            script = "window.__gaiusWebGL.blendFuncSeparate(sourceRgb,destinationRgb,sourceAlpha,destinationAlpha);")
    public static native void blendFuncSeparate(
            int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);

    @JSBody(params = {"source", "destination"},
            script = "window.__gaiusWebGL.blendFunc(source,destination);")
    public static native void blendFunc(int source, int destination);

    @JSBody(params = {"mode"},
            script = "window.__gaiusWebGL.blendEquation(mode);")
    public static native void blendEquation(int mode);

    @JSBody(params = {"modeRgb", "modeAlpha"},
            script = "window.__gaiusWebGL.blendEquationSeparate(modeRgb,modeAlpha);")
    public static native void blendEquationSeparate(int modeRgb, int modeAlpha);

    @JSBody(params = {"unit"}, script = "window.__gaiusWebGL.activeTexture(unit);")
    public static native void activeTexture(int unit);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.textures.set(id,window.__gaiusWebGL.createTexture()); return id|0;
            """)
    public static native int genTexture();

    @JSBody(params = {"texture"}, script = """
            const state=window.__gaiusGL, object=state.textures.get(texture);
            if (object) window.__gaiusWebGL.deleteTexture(object); state.textures.delete(texture);
            """)
    public static native void deleteTexture(int texture);

    @JSBody(params = {"target", "texture"}, script = """
            const state=window.__gaiusGL;
            window.__gaiusWebGL.bindTexture(target,texture===0?null:state.textures.get(texture));
            """)
    public static native void bindTexture(int target, int texture);

    @JSBody(params = {"target", "parameter", "value"},
            script = "window.__gaiusWebGL.texParameteri(target,parameter,value);")
    public static native void texParameteri(int target, int parameter, int value);

    public static void texImage2D(
            int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, ByteBuffer pixels) {
        texImage2DJs(target, level, internalFormat, width, height, border, format, type, bytes(pixels));
    }

    @JSBody(params = {
            "target", "level", "internalFormat", "width", "height",
            "border", "format", "type", "pixels"
    }, script = """
            if (target === 0x8064) {
              return;
            }
            if (internalFormat === 0x81A7 && format === 0x1902 && type === 0x1406) {
              internalFormat = 0x8CAC;
            }
            if (pixels !== null && pixels !== undefined) {
              pixels = new Uint8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength);
            }
            window.__gaiusWebGL.texImage2D(
              target,level,internalFormat,width,height,border,format,type,pixels);
            """)
    private static native void texImage2DJs(
            int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, Int8Array pixels);

    public static void texSubImage2D(
            int target, int level, int x, int y, int width, int height,
            int format, int type, ByteBuffer pixels) {
        texSubImage2DJs(target, level, x, y, width, height, format, type, bytes(pixels));
    }

    public static void texSubImage2D(
            int target, int level, int x, int y, int width, int height,
            int format, int type, long pixels) {
        texSubImage2DJs(target, level, x, y, width, height, format, type,
                pointerBytes(pixels, textureUploadLength(width, height, format, type)));
    }

    @JSBody(params = {
            "target", "level", "x", "y", "width", "height", "format", "type", "pixels"
    }, script = """
            if (pixels !== null && pixels !== undefined) {
              pixels = new Uint8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength);
            }
            window.__gaiusWebGL.texSubImage2D(target,level,x,y,width,height,format,type,pixels);
            """)
    private static native void texSubImage2DJs(
            int target, int level, int x, int y, int width, int height,
            int format, int type, Int8Array pixels);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.buffers.set(id,window.__gaiusWebGL.createBuffer()); return id|0;
            """)
    public static native int genBuffer();

    public static int createBuffer() {
        return genBuffer();
    }

    @JSBody(params = {"buffer"}, script = """
            const state=window.__gaiusGL, object=state.buffers.get(buffer);
            if (object) window.__gaiusWebGL.deleteBuffer(object); state.buffers.delete(buffer);
            state.bufferSizes.delete(buffer);
            """)
    public static native void deleteBuffer(int buffer);

    @JSBody(params = {"target", "buffer"}, script = """
            const state=window.__gaiusGL;
            window.__gaiusWebGL.bindBuffer(target,buffer===0?null:state.buffers.get(buffer));
            state.boundBuffers.set(target,buffer);
            """)
    public static native void bindBuffer(int target, int buffer);

    @JSBody(params = {"target", "size", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const buffer=state.boundBuffers.get(target)|0;
            const requested=Number(size);
            const actual=target===0x8A11 ? Math.max(requested,256) : requested;
            gl.bufferData(target,actual,usage);
            if (buffer) state.bufferSizes.set(buffer,actual);
            """)
    public static native void bufferData(int target, long size, int usage);

    public static void bufferData(int target, ByteBuffer data, int usage) {
        bufferDataJs(target, bytes(data), usage);
    }

    @JSBody(params = {"target", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const buffer=state.boundBuffers.get(target)|0;
            let upload=data;
            let actual=data ? data.byteLength : 0;
            if (target===0x8A11 && actual < 256) {
              const padded=new Int8Array(256);
              if (data) padded.set(data,0);
              upload=padded;
              actual=256;
            }
            gl.bufferData(target,upload,usage);
            if (buffer) state.bufferSizes.set(buffer,actual);
            """)
    private static native void bufferDataJs(int target, Int8Array data, int usage);

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        bufferSubDataJs(target, offset, bytes(data));
    }

    @JSBody(params = {"target", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            gl.bufferSubData(target,Number(offset),data);
            const buffer=state.boundBuffers.get(target)|0;
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
            }
            """)
    private static native void bufferSubDataJs(int target, long offset, Int8Array data);

    public static void namedBufferData(int buffer, long size, int usage) {
        namedBufferDataSizeJs(buffer, size, usage);
    }

    @JSBody(params = {"buffer", "size", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            const requested=Number(size);
            const actual=Math.max(requested,256);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,actual,usage);
            if (buffer) state.bufferSizes.set(buffer,actual);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferDataSizeJs(int buffer, long size, int usage);

    public static void namedBufferData(int buffer, ByteBuffer data, int usage) {
        namedBufferDataJs(buffer, bytes(data), usage);
    }

    @JSBody(params = {"buffer", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            let upload=data;
            let actual=data ? data.byteLength : 0;
            if (actual < 256) {
              const padded=new Int8Array(256);
              if (data) padded.set(data,0);
              upload=padded;
              actual=256;
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,upload,usage);
            if (buffer) state.bufferSizes.set(buffer,actual);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferDataJs(int buffer, Int8Array data, int usage);

    public static void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        namedBufferSubDataJs(buffer, offset, bytes(data));
    }

    @JSBody(params = {"buffer", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferSubData(gl.COPY_WRITE_BUFFER,Number(offset),data);
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferSubDataJs(int buffer, long offset, Int8Array data);

    public static ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WebGL mapped buffer length: " + length);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(target, new MappedBuffer(offset, buffer));
        return buffer;
    }

    public static boolean unmapBuffer(int target) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(target);
        if (mapped == null) {
            return true;
        }
        bufferSubDataJs(target, mapped.offset, allBytes(mapped.buffer));
        return true;
    }

    public static void flushMappedBufferRange(int target, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(target);
        if (mapped == null || length <= 0L) {
            return;
        }
        long absoluteOffset = mapped.offset + offset;
        bufferSubDataJs(target, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    public static ByteBuffer mapNamedBufferRange(int buffer, long offset, long length, int access) {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WebGL mapped buffer length: " + length);
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(namedBufferKey(buffer), new MappedBuffer(offset, byteBuffer));
        return byteBuffer;
    }

    public static boolean unmapNamedBuffer(int buffer) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(namedBufferKey(buffer));
        if (mapped == null) {
            return true;
        }
        namedBufferSubDataJs(buffer, mapped.offset, allBytes(mapped.buffer));
        return true;
    }

    public static void flushMappedNamedBufferRange(int buffer, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(namedBufferKey(buffer));
        if (mapped == null || length <= 0L) {
            return;
        }
        long absoluteOffset = mapped.offset + offset;
        namedBufferSubDataJs(buffer, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    private static int namedBufferKey(int buffer) {
        return 0x40000000 | buffer;
    }

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.shaders.set(id,window.__gaiusWebGL.createShader(0x8B31)); return id|0;
            """)
    public static native int createVertexShader();

    @JSBody(params = {"type"}, script = """
            const state=window.__gaiusGL, id=state.next++;
            state.shaders.set(id,window.__gaiusWebGL.createShader(type)); return id|0;
            """)
    public static native int createShader(int type);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.programs.set(id,window.__gaiusWebGL.createProgram()); return id|0;
            """)
    public static native int createProgram();

    public static void shaderSource(int shader, CharSequence source) {
        shaderSourceJs(shader, translateShaderSource(source.toString()));
    }

    public static void shaderSourceArray(int shader, CharSequence[] sources) {
        StringBuilder joined = new StringBuilder();
        for (CharSequence source : sources) {
            if (source != null) {
                joined.append(source);
            }
        }
        shaderSourceJs(shader, translateShaderSource(joined.toString()));
    }

    public static void shaderSourceNative(int shader, int count, long strings, long lengths) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < count; i++) {
            long address = MemoryUtil.memGetAddress(strings + (long) i * 8L);
            int length = lengths == 0L ? cStringLength(address) : MemoryUtil.memGetInt(lengths + (long) i * 4L);
            if (address != 0L && length > 0) {
                byte[] bytes = new byte[length];
                for (int j = 0; j < length; j++) {
                    bytes[j] = MemoryUtil.memGetByte(address + j);
                }
                joined.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        shaderSourceJs(shader, translateShaderSource(joined.toString()));
    }

    private static int cStringLength(long address) {
        int length = 0;
        while (MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        return length;
    }

    private static String translateShaderSource(String source) {
        String translated = source;
        if (translated.startsWith("#version 330")) {
            int lineEnd = translated.indexOf('\n');
            String rest = lineEnd < 0 ? "" : translated.substring(lineEnd + 1);
            translated = "#version 300 es\nprecision highp float;\nprecision highp int;\n" + rest;
        } else if (translated.startsWith("#version 150")) {
            int lineEnd = translated.indexOf('\n');
            String rest = lineEnd < 0 ? "" : translated.substring(lineEnd + 1);
            translated = "#version 300 es\nprecision highp float;\nprecision highp int;\n" + rest;
        }
        translated = translated
                .replace("uv / 256.0", "vec2(uv) / 256.0")
                .replace("texCoord2 = UV2;", "texCoord2 = vec2(UV2);")
                .replace("floor(texCoord.x * 16) / 15", "floor(texCoord.x * 16.0) / 15.0")
                .replace("floor(texCoord.y * 16) / 15", "floor(texCoord.y * 16.0) / 15.0")
                .replace("Position + (ChunkPosition - CameraBlockPos) + CameraOffset",
                        "Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset")
                .replace("1.0f / TextureSize", "vec2(1.0) / vec2(TextureSize)")
                .replace("1.0 / TextureSize", "vec2(1.0) / vec2(TextureSize)")
                .replace("vec3(cellX, 0, cellZ)", "vec3(float(cellX), 0.0, float(cellZ))")
                .replace("linear_fog_value(vertexDistance, 0, FogCloudsEnd)",
                        "linear_fog_value(vertexDistance, 0.0, FogCloudsEnd)")
                .replace("uniform isamplerBuffer CloudFaces;", "uniform highp isampler2D CloudFaces;")
                .replace("texelFetch(CloudFaces, index).r", "texelFetch(CloudFaces, ivec2(index, 0), 0).r")
                .replace("texelFetch(CloudFaces, index + 1).r", "texelFetch(CloudFaces, ivec2(index + 1, 0), 0).r")
                .replace("texelFetch(CloudFaces, index + 2).r", "texelFetch(CloudFaces, ivec2(index + 2, 0), 0).r")
                .replace("textureLod(Sprite, texCoord0, MipMapLevel)", "textureLod(Sprite, texCoord0, float(MipMapLevel))")
                .replace("textureLod(CurrentSprite, texCoord0, MipMapLevel)", "textureLod(CurrentSprite, texCoord0, float(MipMapLevel))")
                .replace("textureLod(NextSprite, texCoord0, MipMapLevel)", "textureLod(NextSprite, texCoord0, float(MipMapLevel))")
                .replace("(gl_VertexID >> 3) / 1000.0", "float(gl_VertexID >> 3) / 1000.0");
        return stripDesktopFloatSuffixes(translated);
    }

    private static String stripDesktopFloatSuffixes(String source) {
        StringBuilder result = null;
        int length = source.length();
        for (int i = 0; i < length; i++) {
            char current = source.charAt(i);
            if ((current == 'f' || current == 'F') && i > 0 && isNumberSuffixStart(source.charAt(i - 1))
                    && (i + 1 == length || !isIdentifierPart(source.charAt(i + 1)))) {
                if (result == null) {
                    result = new StringBuilder(source.length());
                    result.append(source, 0, i);
                }
                continue;
            }
            if (result != null) {
                result.append(current);
            }
        }
        return result == null ? source : result.toString();
    }

    private static boolean isNumberSuffixStart(char character) {
        return (character >= '0' && character <= '9') || character == '.';
    }

    private static boolean isIdentifierPart(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    @JSBody(params = {"shader", "source"}, script = """
            window.__gaiusWebGL.shaderSource(window.__gaiusGL.shaders.get(shader),source);
            """)
    private static native void shaderSourceJs(int shader, String source);

    @JSBody(params = {"shader"}, script = """
            window.__gaiusWebGL.compileShader(window.__gaiusGL.shaders.get(shader));
            """)
    public static native void compileShader(int shader);

    @JSBody(params = {"program", "shader"}, script = """
            window.__gaiusWebGL.attachShader(
              window.__gaiusGL.programs.get(program),window.__gaiusGL.shaders.get(shader));
            """)
    public static native void attachShader(int program, int shader);

    public static void bindAttribLocation(int program, int index, CharSequence name) {
        bindAttribLocationJs(program, index, name.toString());
    }

    @JSBody(params = {"program", "index", "name"}, script = """
            window.__gaiusWebGL.bindAttribLocation(window.__gaiusGL.programs.get(program),index,name);
            """)
    private static native void bindAttribLocationJs(int program, int index, String name);

    @JSBody(params = {"program"}, script = """
            window.__gaiusWebGL.linkProgram(window.__gaiusGL.programs.get(program));
            """)
    public static native void linkProgram(int program);

    @JSBody(params = {"program"}, script = """
            window.__gaiusWebGL.useProgram(program===0?null:window.__gaiusGL.programs.get(program));
            """)
    public static native void useProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL, object=state.programs.get(program);
            if (object) window.__gaiusWebGL.deleteProgram(object); state.programs.delete(program);
            """)
    public static native void deleteProgram(int program);

    @JSBody(params = {"shader"}, script = """
            const state=window.__gaiusGL, object=state.shaders.get(shader);
            if (object) window.__gaiusWebGL.deleteShader(object); state.shaders.delete(shader);
            """)
    public static native void deleteShader(int shader);

    @JSBody(params = {"program", "parameter"}, script = """
            const value=window.__gaiusWebGL.getProgramParameter(
              window.__gaiusGL.programs.get(program),parameter);
            return typeof value==='boolean'?(value?1:0):(value|0);
            """)
    public static native int getProgrami(int program, int parameter);

    @JSBody(params = {"shader", "parameter"}, script = """
            const value=window.__gaiusWebGL.getShaderParameter(
              window.__gaiusGL.shaders.get(shader),parameter);
            return typeof value==='boolean'?(value?1:0):(value|0);
            """)
    public static native int getShaderi(int shader, int parameter);

    @JSBody(params = {"program", "maximumLength"}, script = """
            return window.__gaiusWebGL.getProgramInfoLog(window.__gaiusGL.programs.get(program))||'';
            """)
    public static native String getProgramInfoLog(int program, int maximumLength);

    @JSBody(params = {"shader", "maximumLength"}, script = """
            return window.__gaiusWebGL.getShaderInfoLog(window.__gaiusGL.shaders.get(shader))||'';
            """)
    public static native String getShaderInfoLog(int shader, int maximumLength);

    public static int getUniformLocation(int program, CharSequence name) {
        return getUniformLocationJs(program, name.toString());
    }

    @JSBody(params = {"program", "name"}, script = """
            const gl=window.__gaiusWebGL, object=gl.getUniformLocation(window.__gaiusGL.programs.get(program),name);
            if (object===null) return -1;
            const state=window.__gaiusGL, id=state.next++;
            if (!state.uniforms) state.uniforms=new Map(); state.uniforms.set(id,object); return id|0;
            """)
    private static native int getUniformLocationJs(int program, String name);

    @JSBody(params = {"location", "value"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1i(object,value);
            """)
    public static native void uniform1i(int location, int value);

    @JSBody(params = {"location", "value"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1f(object,value);
            """)
    public static native void uniform1f(int location, float value);

    @JSBody(params = {"location", "x", "y"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2f(object,x,y);
            """)
    public static native void uniform2f(int location, float x, float y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3f(object,x,y,z);
            """)
    public static native void uniform3f(int location, float x, float y, float z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4f(object,x,y,z,w);
            """)
    public static native void uniform4f(int location, float x, float y, float z, float w);

    @JSBody(params = {"location", "x", "y"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2i(object,x,y);
            """)
    public static native void uniform2i(int location, int x, int y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3i(object,x,y,z);
            """)
    public static native void uniform3i(int location, int x, int y, int z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4i(object,x,y,z,w);
            """)
    public static native void uniform4i(int location, int x, int y, int z, int w);

    public static void uniform1fv(int location, FloatBuffer values) {
        uniform1fvJs(location, floats(values));
    }

    public static void uniform2fv(int location, FloatBuffer values) {
        uniform2fvJs(location, floats(values));
    }

    public static void uniform3fv(int location, FloatBuffer values) {
        uniform3fvJs(location, floats(values));
    }

    public static void uniform4fv(int location, FloatBuffer values) {
        uniform4fvJs(location, floats(values));
    }

    public static void uniform1iv(int location, IntBuffer values) {
        uniform1ivJs(location, ints(values));
    }

    public static void uniform2iv(int location, IntBuffer values) {
        uniform2ivJs(location, ints(values));
    }

    public static void uniform3iv(int location, IntBuffer values) {
        uniform3ivJs(location, ints(values));
    }

    public static void uniform4iv(int location, IntBuffer values) {
        uniform4ivJs(location, ints(values));
    }

    public static void uniformMatrix2fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix2fvJs(location, transpose, floats(values));
    }

    public static void uniformMatrix3fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix3fvJs(location, transpose, floats(values));
    }

    public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix4fvJs(location, transpose, floats(values));
    }

    public static void uniform1fv(int location, float[] values) {
        uniform1fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform2fv(int location, float[] values) {
        uniform2fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform3fv(int location, float[] values) {
        uniform3fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform4fv(int location, float[] values) {
        uniform4fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform1iv(int location, int[] values) {
        uniform1ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform2iv(int location, int[] values) {
        uniform2ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform3iv(int location, int[] values) {
        uniform3ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform4iv(int location, int[] values) {
        uniform4ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniformMatrix2fv(int location, boolean transpose, float[] values) {
        uniformMatrix2fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniformMatrix3fv(int location, boolean transpose, float[] values) {
        uniformMatrix3fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniformMatrix4fv(int location, boolean transpose, float[] values) {
        uniformMatrix4fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniform1fv(int location, int count, long values) {
        uniform1fvJs(location, pointerFloats(values, count));
    }

    public static void uniform2fv(int location, int count, long values) {
        uniform2fvJs(location, pointerFloats(values, count * 2));
    }

    public static void uniform3fv(int location, int count, long values) {
        uniform3fvJs(location, pointerFloats(values, count * 3));
    }

    public static void uniform4fv(int location, int count, long values) {
        uniform4fvJs(location, pointerFloats(values, count * 4));
    }

    public static void uniform1iv(int location, int count, long values) {
        uniform1ivJs(location, pointerInts(values, count));
    }

    public static void uniform2iv(int location, int count, long values) {
        uniform2ivJs(location, pointerInts(values, count * 2));
    }

    public static void uniform3iv(int location, int count, long values) {
        uniform3ivJs(location, pointerInts(values, count * 3));
    }

    public static void uniform4iv(int location, int count, long values) {
        uniform4ivJs(location, pointerInts(values, count * 4));
    }

    public static void uniformMatrix2fv(int location, int count, boolean transpose, long values) {
        uniformMatrix2fvJs(location, transpose, pointerFloats(values, count * 4));
    }

    public static void uniformMatrix3fv(int location, int count, boolean transpose, long values) {
        uniformMatrix3fvJs(location, transpose, pointerFloats(values, count * 9));
    }

    public static void uniformMatrix4fv(int location, int count, boolean transpose, long values) {
        uniformMatrix4fvJs(location, transpose, pointerFloats(values, count * 16));
    }

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1fv(object,values);
            """)
    private static native void uniform1fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2fv(object,values);
            """)
    private static native void uniform2fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3fv(object,values);
            """)
    private static native void uniform3fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4fv(object,values);
            """)
    private static native void uniform4fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1iv(object,values);
            """)
    private static native void uniform1ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2iv(object,values);
            """)
    private static native void uniform2ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3iv(object,values);
            """)
    private static native void uniform3ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4iv(object,values);
            """)
    private static native void uniform4ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix2fv(object,transpose,values);
            """)
    private static native void uniformMatrix2fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix3fv(object,transpose,values);
            """)
    private static native void uniformMatrix3fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix4fv(object,transpose,values);
            """)
    private static native void uniformMatrix4fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"index"}, script = "window.__gaiusWebGL.enableVertexAttribArray(index);")
    public static native void enableVertexAttribArray(int index);

    @JSBody(params = {"index"}, script = "window.__gaiusWebGL.disableVertexAttribArray(index);")
    public static native void disableVertexAttribArray(int index);

    @JSBody(params = {"index", "size", "type", "normalized", "stride", "offset"}, script = """
            window.__gaiusWebGL.vertexAttribPointer(index,size,type,normalized,stride,Number(offset));
            """)
    public static native void vertexAttribPointer(
            int index, int size, int type, boolean normalized, int stride, long offset);

    @JSBody(params = {"index", "size", "type", "stride", "offset"}, script = """
            window.__gaiusWebGL.vertexAttribIPointer(index,size,type,stride,Number(offset));
            """)
    public static native void vertexAttribIPointer(
            int index, int size, int type, int stride, long offset);

    @JSBody(params = {"index", "divisor"}, script = "window.__gaiusWebGL.vertexAttribDivisor(index,divisor);")
    public static native void vertexAttribDivisor(int index, int divisor);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.vaos.set(id,window.__gaiusWebGL.createVertexArray()); return id|0;
            """)
    public static native int genVertexArray();

    @JSBody(params = {"array"}, script = """
            window.__gaiusWebGL.bindVertexArray(array===0?null:window.__gaiusGL.vaos.get(array));
            """)
    public static native void bindVertexArray(int array);

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL, object=state.vaos.get(array);
            if (object) window.__gaiusWebGL.deleteVertexArray(object); state.vaos.delete(array);
            """)
    public static native void deleteVertexArray(int array);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.framebuffers.set(id,window.__gaiusWebGL.createFramebuffer()); return id|0;
            """)
    public static native int genFramebuffer();

    public static int createFramebuffer() {
        return genFramebuffer();
    }

    @JSBody(params = {"target", "framebuffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            gl.bindFramebuffer(target,framebuffer===0?null:state.framebuffers.get(framebuffer));
            if (target===0x8D40) {
              state.framebufferBindings.draw=framebuffer|0;
              state.framebufferBindings.read=framebuffer|0;
            } else if (target===0x8CA9) {
              state.framebufferBindings.draw=framebuffer|0;
            } else if (target===0x8CA8) {
              state.framebufferBindings.read=framebuffer|0;
            }
            """)
    public static native void bindFramebuffer(int target, int framebuffer);

    @JSBody(params = {"target", "attachment", "textureTarget", "texture", "level"}, script = """
            window.__gaiusWebGL.framebufferTexture2D(
              target,attachment,textureTarget,texture===0?null:window.__gaiusGL.textures.get(texture),level);
            """)
    public static native void framebufferTexture2D(
            int target, int attachment, int textureTarget, int texture, int level);

    @JSBody(params = {"framebuffer", "attachment", "texture", "level"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(gl.FRAMEBUFFER,framebuffer===0?null:state.framebuffers.get(framebuffer));
            gl.framebufferTexture2D(
              gl.FRAMEBUFFER,attachment,gl.TEXTURE_2D,texture===0?null:state.textures.get(texture),level);
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            """)
    public static native void namedFramebufferTexture(
            int framebuffer, int attachment, int texture, int level);

    @JSBody(params = {"framebuffer", "target"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(target,framebuffer===0?null:state.framebuffers.get(framebuffer));
            const status=gl.checkFramebufferStatus(target)|0;
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            return status;
            """)
    public static native int checkNamedFramebufferStatus(int framebuffer, int target);

    @JSBody(params = {"framebuffer"}, script = """
            const state=window.__gaiusGL, object=state.framebuffers.get(framebuffer);
            if (object) window.__gaiusWebGL.deleteFramebuffer(object); state.framebuffers.delete(framebuffer);
            if (state.framebufferBindings.draw===framebuffer) state.framebufferBindings.draw=0;
            if (state.framebufferBindings.read===framebuffer) state.framebufferBindings.read=0;
            """)
    public static native void deleteFramebuffer(int framebuffer);

    @JSBody(params = {
            "sourceX0", "sourceY0", "sourceX1", "sourceY1",
            "targetX0", "targetY0", "targetX1", "targetY1", "mask", "filter"
    }, script = """
            window.__gaiusWebGL.blitFramebuffer(
              sourceX0,sourceY0,sourceX1,sourceY1,targetX0,targetY0,targetX1,targetY1,mask,filter);
            """)
    public static native void blitFramebuffer(
            int sourceX0, int sourceY0, int sourceX1, int sourceY1,
            int targetX0, int targetY0, int targetX1, int targetY1,
            int mask, int filter);

    @JSBody(params = {
            "readFramebuffer", "drawFramebuffer",
            "sourceX0", "sourceY0", "sourceX1", "sourceY1",
            "targetX0", "targetY0", "targetX1", "targetY1", "mask", "filter"
    }, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,readFramebuffer===0?null:state.framebuffers.get(readFramebuffer));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,drawFramebuffer===0?null:state.framebuffers.get(drawFramebuffer));
            gl.blitFramebuffer(
              sourceX0,sourceY0,sourceX1,sourceY1,targetX0,targetY0,targetX1,targetY1,mask,filter);
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            """)
    public static native void blitNamedFramebuffer(
            int readFramebuffer, int drawFramebuffer,
            int sourceX0, int sourceY0, int sourceX1, int sourceY1,
            int targetX0, int targetY0, int targetX1, int targetY1,
            int mask, int filter);

    @JSBody(params = {"mode", "first", "count", "instances"},
            script = "window.__gaiusWebGL.drawArraysInstanced(mode,first,count,instances);")
    public static native void drawArraysInstanced(int mode, int first, int count, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "instances"},
            script = "window.__gaiusWebGL.drawElementsInstanced(mode,count,type,Number(offset),instances);")
    public static native void drawElementsInstanced(
            int mode, int count, int type, long offset, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "baseVertex"}, script = """
            const gl=window.__gaiusWebGL;
            const extension=gl.getExtension('WEBGL_draw_instanced_base_vertex_base_instance');
            if (extension && extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL) {
              extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL(
                mode,count,type,Number(offset),1,baseVertex,0);
            } else if (extension && extension.drawElementsInstancedBaseVertexWEBGL) {
              extension.drawElementsInstancedBaseVertexWEBGL(mode,count,type,Number(offset),1,baseVertex);
            } else {
              gl.drawElements(mode,count,type,Number(offset));
            }
            """)
    public static native void drawElementsBaseVertex(
            int mode, int count, int type, long offset, int baseVertex);

    @JSBody(params = {"mode", "count", "type", "offset", "instances", "baseVertex"}, script = """
            const gl=window.__gaiusWebGL;
            const extension=gl.getExtension('WEBGL_draw_instanced_base_vertex_base_instance');
            if (extension && extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL) {
              extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL(
                mode,count,type,Number(offset),instances,baseVertex,0);
            } else if (extension && extension.drawElementsInstancedBaseVertexWEBGL) {
              extension.drawElementsInstancedBaseVertexWEBGL(mode,count,type,Number(offset),instances,baseVertex);
            } else {
              gl.drawElementsInstanced(mode,count,type,Number(offset),instances);
            }
            """)
    public static native void drawElementsInstancedBaseVertex(
            int mode, int count, int type, long offset, int instances, int baseVertex);

    @JSBody(params = {"target", "index", "buffer", "offset", "size"}, script = """
            const state=window.__gaiusGL;
            const bufferSize=state.bufferSizes.get(buffer)||Number(size);
            const available=Math.max(0,bufferSize-Number(offset));
            const range=target===0x8A11 && available>Number(size)
              ? Math.min(available,Math.max(Number(size),256))
              : Number(size);
            window.__gaiusWebGL.bindBufferRange(
              target,index,buffer===0?null:state.buffers.get(buffer),Number(offset),range);
            """)
    public static native void bindBufferRange(
            int target, int index, int buffer, long offset, long size);

    @JSBody(params = {"target", "index", "buffer"}, script = """
            const state=window.__gaiusGL;
            window.__gaiusWebGL.bindBufferBase(
              target,index,buffer===0?null:state.buffers.get(buffer));
            """)
    public static native void bindBufferBase(int target, int index, int buffer);

    @JSBody(params = {"sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"}, script = """
            window.__gaiusWebGL.copyBufferSubData(
              sourceTarget,targetTarget,Number(sourceOffset),Number(targetOffset),Number(size));
            """)
    public static native void copyBufferSubData(
            int sourceTarget, int targetTarget, long sourceOffset, long targetOffset, long size);

    @JSBody(params = {"sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousRead=gl.getParameter(gl.COPY_READ_BUFFER_BINDING);
            const previousWrite=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            gl.bindBuffer(gl.COPY_READ_BUFFER,state.buffers.get(sourceBuffer));
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(targetBuffer));
            gl.copyBufferSubData(
              gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,Number(sourceOffset),Number(targetOffset),Number(size));
            if (targetBuffer) {
              const end=Number(targetOffset)+Number(size);
              const known=state.bufferSizes.get(targetBuffer)||0;
              if (end > known) state.bufferSizes.set(targetBuffer,end);
            }
            gl.bindBuffer(gl.COPY_READ_BUFFER,previousRead);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previousWrite);
            """)
    public static native void copyNamedBufferSubData(
            int sourceBuffer, int targetBuffer, long sourceOffset, long targetOffset, long size);

    public static int getUniformBlockIndex(int program, CharSequence name) {
        return getUniformBlockIndexJs(program, name.toString());
    }

    @JSBody(params = {"program", "name"}, script = """
            const gl=window.__gaiusWebGL;
            const index=gl.getUniformBlockIndex(window.__gaiusGL.programs.get(program),name);
            return index===gl.INVALID_INDEX ? -1 : index|0;
            """)
    private static native int getUniformBlockIndexJs(int program, String name);

    @JSBody(params = {"program", "index"}, script = """
            return window.__gaiusWebGL.getActiveUniformBlockName(
              window.__gaiusGL.programs.get(program),index)||'';
            """)
    public static native String getActiveUniformBlockName(int program, int index);

    @JSBody(params = {"program", "block", "binding"}, script = """
            window.__gaiusWebGL.uniformBlockBinding(
              window.__gaiusGL.programs.get(program),block,binding);
            """)
    public static native void uniformBlockBinding(int program, int block, int binding);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.samplers.set(id,window.__gaiusWebGL.createSampler()); return id|0;
            """)
    public static native int genSampler();

    @JSBody(params = {"unit", "sampler"}, script = """
            window.__gaiusWebGL.bindSampler(
              unit,sampler===0?null:window.__gaiusGL.samplers.get(sampler));
            """)
    public static native void bindSampler(int unit, int sampler);

    @JSBody(params = {"sampler"}, script = """
            const state=window.__gaiusGL, object=state.samplers.get(sampler);
            if (object) window.__gaiusWebGL.deleteSampler(object); state.samplers.delete(sampler);
            """)
    public static native void deleteSampler(int sampler);

    @JSBody(params = {"sampler", "parameter", "value"}, script = """
            window.__gaiusWebGL.samplerParameteri(
              window.__gaiusGL.samplers.get(sampler),parameter,value);
            """)
    public static native void samplerParameteri(int sampler, int parameter, int value);

    @JSBody(params = {"sampler", "parameter", "value"}, script = """
            window.__gaiusWebGL.samplerParameterf(
              window.__gaiusGL.samplers.get(sampler),parameter,value);
            """)
    public static native void samplerParameterf(int sampler, int parameter, float value);

    @JSBody(params = {"condition", "flags"}, script = """
            const state=window.__gaiusGL,id=state.next++;
            state.syncs.set(id,window.__gaiusWebGL.fenceSync(condition,flags)); return id;
            """)
    public static native long fenceSync(int condition, int flags);

    @JSBody(params = {"sync", "flags", "timeout"}, script = """
            const object=window.__gaiusGL.syncs.get(Number(sync));
            return object?window.__gaiusWebGL.clientWaitSync(object,flags,0):0x911A;
            """)
    public static native int clientWaitSync(long sync, int flags, long timeout);

    @JSBody(params = {"sync"}, script = """
            const state=window.__gaiusGL, object=state.syncs.get(Number(sync));
            if (object) window.__gaiusWebGL.deleteSync(object); state.syncs.delete(Number(sync));
            """)
    public static native void deleteSync(long sync);

    public static void reportMinecraftState(
            Object screen, Object overlay, Object level,
            boolean noRender, boolean running, boolean pause) {
        reportMinecraftStateJs(
                className(screen), className(overlay), className(level),
                noRender, running, pause);
    }

    @JSBody(params = {"screen", "overlay", "level", "noRender", "running", "pause"}, script = """
            window.__gaiusMinecraftState = {
              "screen": screen,
              "overlay": overlay,
              "level": level,
              "noRender": noRender,
              "running": running,
              "pause": pause,
              "at": Date.now()
            };
            """)
    private static native void reportMinecraftStateJs(
            String screen, String overlay, String level,
            boolean noRender, boolean running, boolean pause);

    public static void reportMinecraftEvent(String event) {
        reportMinecraftEventJs(event, null);
    }

    public static void reportMinecraftEvent(String event, String detail) {
        reportMinecraftEventJs(event, detail);
    }

    @JSBody(params = {"event", "detail"}, script = """
            var counters = window.__gaiusMinecraftCounters;
            if (!counters) {
              counters = {};
              window.__gaiusMinecraftCounters = counters;
            }
            const key = detail == null ? event : event + ":" + detail;
            const count = (counters[key] || 0) + 1;
            counters[key] = count;
            var events = window.__gaiusMinecraftEvents;
            if (!events) {
              events = [];
              window.__gaiusMinecraftEvents = events;
            }
            if (count <= 12 || count % 100 === 0 || /ready|loaded|loadingPacketsReceived/.test(event)) {
              events.push({"event": event, "detail": detail, "count": count, "at": Date.now()});
              if (events.length > 500) events.splice(0, events.length - 500);
            }
            """)
    private static native void reportMinecraftEventJs(String event, String detail);

    private static String className(Object value) {
        return value == null ? null : value.getClass().getName();
    }

    private static Int8Array bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        return memoryBytes(buffer, copy.position(), copy.remaining());
    }

    private static Int8Array allBytes(ByteBuffer buffer) {
        return memoryBytes(buffer, 0, buffer.capacity());
    }

    private static Int8Array bytesSlice(ByteBuffer buffer, long offset, long length) {
        if (offset < 0L || length < 0L || offset + length > buffer.capacity() || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer flush range: " + offset + " + " + length);
        }
        return memoryBytes(buffer, (int) offset, (int) length);
    }

    private static Int8Array memoryBytes(ByteBuffer buffer, int offset, int length) {
        long address = MemoryUtil.memAddress0(buffer) + offset;
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = MemoryUtil.memGetByte(address + i);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array pointerBytes(long address, int length) {
        if (address == 0L) {
            return null;
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = MemoryUtil.memGetByte(address + i);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Float32Array floats(FloatBuffer buffer) {
        FloatBuffer copy = buffer.duplicate();
        float[] data = new float[copy.remaining()];
        copy.get(data);
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array ints(IntBuffer buffer) {
        IntBuffer copy = buffer.duplicate();
        int[] data = new int[copy.remaining()];
        copy.get(data);
        return Int32Array.fromJavaArray(data);
    }

    private static Float32Array pointerFloats(long address, int count) {
        if (address == 0L) {
            return null;
        }
        float[] data = new float[count];
        for (int i = 0; i < count; i++) {
            data[i] = MemoryUtil.memGetFloat(address + (long) i * 4L);
        }
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array pointerInts(long address, int count) {
        if (address == 0L) {
            return null;
        }
        int[] data = new int[count];
        for (int i = 0; i < count; i++) {
            data[i] = MemoryUtil.memGetInt(address + (long) i * 4L);
        }
        return Int32Array.fromJavaArray(data);
    }

    private static int textureUploadLength(int width, int height, int format, int type) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int bytesPerPixel = bytesPerPixel(format, type);
        int rowLength = unpackRowLength > 0 ? unpackRowLength : width;
        long rowStride = aligned((long) rowLength * bytesPerPixel, unpackAlignment);
        long length = (long) unpackSkipRows * rowStride
                + (long) unpackSkipPixels * bytesPerPixel
                + (long) (height - 1) * rowStride
                + (long) width * bytesPerPixel;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Texture upload too large: " + width + "x" + height);
        }
        return (int) length;
    }

    private static int webGlUnpackAlignment(int value) {
        return switch (value) {
            case 1, 2, 4, 8 -> value;
            default -> 1;
        };
    }

    private static long aligned(long value, int alignment) {
        if (alignment <= 1) {
            return value;
        }
        long remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    private static int bytesPerPixel(int format, int type) {
        return switch (type) {
            case 0x8033, 0x8034, 0x8035, 0x8367, 0x84FA -> 4;
            default -> componentsForFormat(format) * bytesForType(type);
        };
    }

    private static int componentsForFormat(int format) {
        return switch (format) {
            case 0x1907 -> 3; // GL_RGB
            case 0x1908, 0x80E1 -> 4; // GL_RGBA / GL_BGRA
            case 0x8227 -> 2; // GL_RG
            case 0x84F9 -> 2; // GL_DEPTH_STENCIL
            default -> 1;
        };
    }

    private static int bytesForType(int type) {
        return switch (type) {
            case 0x1403, 0x1402, 0x8D61 -> 2; // unsigned short / short / half float
            case 0x1405, 0x1404, 0x1406, 0x84FA -> 4; // uint / int / float / uint_24_8
            default -> 1;
        };
    }

    private record MappedBuffer(long offset, ByteBuffer buffer) {
    }
}
