package dev.gaius.browser.webgpu;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * WGSL programs for renderpearl pipelines, keyed by the pipeline name of
 * {@code BackendRenderPipeline.CreateInfo} (the pipeline location, e.g.
 * {@code minecraft:pipeline/gui}).
 *
 * <p>The S3 backend has no SPIR-V translation of its own: programs come from this registry
 * (filled by harnesses and, until the translator lands, by pre-translated corpora). The
 * SPIR-V to WGSL translator replaces the lookup in {@link WebGpuRenderPipeline#compile}; whatever
 * produces the WGSL must follow the binding and orientation contract of the glue
 * ({@code port/web/runtime/webgpu/gpu-glue.js}).</p>
 */
public final class WgslSources {
    private static final Map<String, Program> PROGRAMS = new HashMap<>();

    private WgslSources() {
    }

    /** A vertex and a fragment entry point; both may live in the same module. */
    public record Program(String vertexCode, String vertexEntry, String fragmentCode, String fragmentEntry) {
        public static Program single(String code, String vertexEntry, String fragmentEntry) {
            return new Program(code, vertexEntry, code, fragmentEntry);
        }
    }

    public static synchronized void register(String pipelineName, Program program) {
        PROGRAMS.put(pipelineName, program);
    }

    static synchronized @Nullable Program lookup(String pipelineName) {
        return PROGRAMS.get(pipelineName);
    }
}
