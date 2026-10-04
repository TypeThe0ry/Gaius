package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A compiled pipeline. WebGPU pipelines are bound to the depth format of the pass they draw
 * in, so each pipeline keeps one variant per depth format ("" is no depth attachment), created
 * on first use; the variant vanilla most likely needs is created eagerly.
 */
class WebGpuRenderPipeline implements BackendRenderPipeline {
    private static final int MAX_VARIANTS = 4;
    static final byte UNIFORM_BUFFER = 0;
    static final byte TEXTURE = 1;
    static final byte TEXEL_BUFFER = 2;

    final String name;
    private final String json;
    /** Uniform kinds in CreateInfo order. */
    final byte[] uniformKinds;
    /** Uniform indices of the uniform buffers, in binding order (the dynamic offset order). */
    final int[] uniformBufferIndices;
    final boolean hasGroup1;
    /** Identifies the bind group layouts; pipelines with equal keys share bind groups. */
    private final String layoutKey;
    private final String[] variantFormats = new String[MAX_VARIANTS];
    private final int[] variantHandles = new int[MAX_VARIANTS];
    private int variantCount;
    private final int[] modules;
    private boolean closed;

    private WebGpuRenderPipeline(String name, String json, byte[] uniformKinds, int[] modules) {
        this.name = name;
        this.json = json;
        this.uniformKinds = uniformKinds;
        this.modules = modules;
        int buffers = 0;
        boolean group1 = false;
        for (byte kind : uniformKinds) {
            if (kind == UNIFORM_BUFFER) {
                buffers++;
            } else {
                group1 = true;
            }
        }
        this.hasGroup1 = group1;
        StringBuilder key = new StringBuilder(uniformKinds.length + 1).append('L');
        for (byte kind : uniformKinds) {
            key.append((char) ('0' + kind));
        }
        this.layoutKey = key.toString();
        this.uniformBufferIndices = new int[buffers];
        int next = 0;
        for (int i = 0; i < uniformKinds.length; i++) {
            if (uniformKinds[i] == UNIFORM_BUFFER) {
                this.uniformBufferIndices[next++] = i;
            }
        }
    }

    /**
     * Builds the pipeline, or returns {@code null} (vanilla then treats the pipeline as
     * unavailable) when no WGSL is known for it or WebGPU cannot express it.
     */
    static @Nullable WebGpuRenderPipeline compile(WebGpuDevice device, CreateInfo info) {
        WgslSources.Program program = WgslSources.lookup(info.name());
        if (program == null) {
            device.debugMessage("no WGSL for pipeline " + info.name());
            return null;
        }
        String topology = WebGpuFormats.topology(info.primitiveTopology());
        if (topology == null) {
            device.debugMessage("pipeline " + info.name() + " uses " + info.primitiveTopology() + ", not supported yet");
            return null;
        }
        List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
        byte[] kinds = new byte[uniforms.size()];
        for (int i = 0; i < kinds.length; i++) {
            UniformType type = uniforms.get(i).type();
            kinds[i] = type == UniformType.UNIFORM_BUFFER ? UNIFORM_BUFFER
                    : type == UniformType.COMBINED_IMAGE_SAMPLER ? TEXTURE : TEXEL_BUFFER;
        }
        int vertexModule = WebGpuGlue.createShaderModule(program.vertexCode(), info.name());
        int fragmentModule = program.fragmentCode().equals(program.vertexCode())
                ? vertexModule
                : WebGpuGlue.createShaderModule(program.fragmentCode(), info.name());
        String json = describe(info, topology, kinds, vertexModule, program.vertexEntry(), fragmentModule,
                program.fragmentEntry());
        WebGpuRenderPipeline pipeline = new WebGpuRenderPipeline(info.name(), json, kinds,
                vertexModule == fragmentModule ? new int[] { vertexModule } : new int[] { vertexModule, fragmentModule });
        // Vanilla draws pipelines with depth state into passes with a D32 depth attachment.
        pipeline.variant(info.depthStencilState() != null ? "depth32float" : "");
        return pipeline;
    }

    String layoutKey() {
        return this.layoutKey;
    }

    /** The pipeline handle for a pass with the given depth format, or 0 when it failed. */
    int variant(String depthFormat) {
        for (int i = 0; i < this.variantCount; i++) {
            if (this.variantFormats[i].equals(depthFormat)) {
                return this.variantHandles[i];
            }
        }
        int handle = WebGpuGlue.createPipeline(this.json, depthFormat);
        if (this.variantCount < MAX_VARIANTS) {
            this.variantFormats[this.variantCount] = depthFormat;
            this.variantHandles[this.variantCount] = handle;
            this.variantCount++;
        }
        return handle;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (int i = 0; i < this.variantCount; i++) {
                WebGpuGlue.releaseHandle(this.variantHandles[i]);
            }
            for (int module : this.modules) {
                WebGpuGlue.releaseHandle(module);
            }
            this.variantCount = 0;
        }
    }

    private static String describe(CreateInfo info, String topology, byte[] kinds, int vertexModule, String vertexEntry,
            int fragmentModule, String fragmentEntry) {
        StringBuilder out = new StringBuilder(512);
        out.append("{\"label\":");
        quote(out, info.name());
        out.append(",\"vs\":").append(vertexModule).append(",\"vsEntry\":");
        quote(out, vertexEntry);
        out.append(",\"fs\":").append(fragmentModule).append(",\"fsEntry\":");
        quote(out, fragmentEntry);
        out.append(",\"topology\":\"").append(topology).append('"');
        out.append(",\"cull\":").append(info.cull());

        out.append(",\"uniforms\":[");
        for (int i = 0; i < kinds.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(kinds[i] == UNIFORM_BUFFER ? "\"ubo\"" : kinds[i] == TEXTURE ? "\"tex\"" : "\"texel\"");
        }
        out.append(']');

        int slots = 0;
        for (CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            slots = Math.max(slots, buffer.bufferSlot() + 1);
        }
        out.append(",\"buffers\":[");
        for (int slot = 0; slot < slots; slot++) {
            if (slot > 0) {
                out.append(',');
            }
            CreateInfo.VertexBuffer buffer = null;
            for (CreateInfo.VertexBuffer candidate : info.vertexBuffers()) {
                if (candidate.bufferSlot() == slot) {
                    buffer = candidate;
                }
            }
            if (buffer == null) {
                out.append("null");
                continue;
            }
            out.append("{\"stride\":").append(buffer.stride()).append(",\"instance\":").append(buffer.stepRate() > 0)
                    .append(",\"attributes\":[");
            boolean first = true;
            for (CreateInfo.AttribBinding attribute : info.attribBindings()) {
                if (attribute.bufferSlot() != slot) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('[').append(attribute.location()).append(',').append(attribute.offset()).append(",\"")
                        .append(WebGpuFormats.vertexFormat(attribute.format())).append("\"]");
            }
            out.append("]}");
        }
        out.append(']');

        out.append(",\"targets\":[");
        List<ColorTargetState> targets = info.colorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            ColorTargetState target = targets.get(i);
            if (target == null) {
                out.append("null");
                continue;
            }
            out.append("{\"format\":\"").append(WebGpuFormats.textureFormat(target.format())).append("\",\"mask\":")
                    .append(target.writeMask());
            if (target.blendFunction().isPresent()) {
                BlendFunction blend = target.blendFunction().get();
                out.append(",\"blend\":[");
                blendEquation(out, blend.color());
                out.append(',');
                blendEquation(out, blend.alpha());
                out.append(']');
            }
            out.append('}');
        }
        out.append(']');

        DepthStencilState depth = info.depthStencilState();
        if (depth != null) {
            // Line and point topologies must not carry a depth bias in WebGPU.
            boolean biasAllowed = !WebGpuFormats.isLineOrPoint(info.primitiveTopology());
            out.append(",\"depth\":{\"compare\":\"").append(WebGpuFormats.compare(depth.depthTest())).append("\",\"write\":")
                    .append(depth.writeDepth()).append(",\"bias\":")
                    .append(biasAllowed ? Math.round(depth.depthBiasConstant()) : 0).append(",\"slope\":")
                    .append(biasAllowed ? depth.depthBiasScaleFactor() : 0.0F).append('}');
        }
        out.append('}');
        return out.toString();
    }

    private static void blendEquation(StringBuilder out, BlendEquation equation) {
        out.append("[\"").append(WebGpuFormats.blendFactor(equation.sourceFactor())).append("\",\"")
                .append(WebGpuFormats.blendFactor(equation.destFactor())).append("\",\"")
                .append(WebGpuFormats.blendOp(equation.op())).append("\"]");
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}
