package org.lwjgl.util.shaderc;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/**
 * The browser {@link BrowserShadercToolchain}: shaderc compiled to WebAssembly,
 * driven through window.__gaiusShaderToolchain (port/wasm/shader-toolchain/loader).
 * The launcher starts the TeaVM main only after window.__gaiusShaderToolchainReady
 * resolved, so the API is present whenever a shader is compiled.
 */
final class BrowserShadercWasm implements BrowserShadercToolchain {
    private static final byte[] NO_BYTES = new byte[0];

    @Override
    public BrowserShadercJob.Output compile(BrowserShadercJob job) {
        if (!available()) {
            return BrowserShadercJob.Output.internalError(
                    "Gaius shader toolchain: window.__gaiusShaderToolchain is not loaded ("
                            + loadError() + ")");
        }
        int id = begin();
        try {
            for (BrowserShadercJob.Op op : job.ops) {
                if (op.code == BrowserShadercJob.OP_MACRO) {
                    // A @JSByRef array must not be null; the flag says "no value".
                    macro(id, op.name, op.value == null ? NO_BYTES : op.value, op.value != null);
                } else {
                    option(id, op.code, op.a, op.b);
                }
            }
            for (BrowserShadercJob.Include include : job.includes) {
                include(id, include.type, include.requested, include.requesting, include.sourceName,
                        include.content);
            }
            int status = compile(id, job.source, job.kind, job.inputFileName, job.entryPointName);
            byte[] spirv = new byte[outputLength(id)];
            if (spirv.length > 0) {
                outputCopy(id, spirv);
            }
            return new BrowserShadercJob.Output(status, spirv, errorMessage(id), warnings(id), errors(id));
        } finally {
            end(id);
        }
    }

    @JSBody(script = "return !!(window.__gaiusShaderToolchain && window.__gaiusShaderToolchain.shadercBegin);")
    private static native boolean available();

    @JSBody(script = "return String(window.__gaiusShaderToolchainError || 'no loader in this page');")
    private static native String loadError();

    @JSBody(script = "return window.__gaiusShaderToolchain.shadercBegin();")
    private static native int begin();

    @JSBody(params = {"id", "code", "a", "b"},
            script = "window.__gaiusShaderToolchain.shadercOption(id, code, a, b);")
    private static native void option(int id, int code, int a, int b);

    @JSBody(params = {"id", "name", "value", "hasValue"},
            script = "window.__gaiusShaderToolchain.shadercMacro(id, name, hasValue ? value : null);")
    private static native void macro(int id, @JSByRef byte[] name, @JSByRef byte[] value, boolean hasValue);

    @JSBody(params = {"id", "type", "requested", "requesting", "sourceName", "content"},
            script = "window.__gaiusShaderToolchain.shadercInclude(id, type, requested, requesting,"
                    + " sourceName, content);")
    private static native void include(int id, int type, @JSByRef byte[] requested, @JSByRef byte[] requesting,
            @JSByRef byte[] sourceName, @JSByRef byte[] content);

    @JSBody(params = {"id", "source", "kind", "fileName", "entryPoint"},
            script = "return window.__gaiusShaderToolchain.shadercCompile(id, source, kind, fileName,"
                    + " entryPoint);")
    private static native int compile(int id, @JSByRef byte[] source, int kind, @JSByRef byte[] fileName,
            @JSByRef byte[] entryPoint);

    @JSBody(params = "id", script = "return window.__gaiusShaderToolchain.shadercOutputLength(id);")
    private static native int outputLength(int id);

    @JSBody(params = {"id", "out"}, script = "window.__gaiusShaderToolchain.shadercOutputCopy(id, out);")
    private static native void outputCopy(int id, @JSByRef byte[] out);

    @JSBody(params = "id", script = "return window.__gaiusShaderToolchain.shadercErrorMessage(id);")
    private static native String errorMessage(int id);

    @JSBody(params = "id", script = "return window.__gaiusShaderToolchain.shadercWarnings(id);")
    private static native int warnings(int id);

    @JSBody(params = "id", script = "return window.__gaiusShaderToolchain.shadercErrors(id);")
    private static native int errors(int id);

    @JSBody(params = "id", script = "window.__gaiusShaderToolchain.shadercEnd(id);")
    private static native void end(int id);
}
