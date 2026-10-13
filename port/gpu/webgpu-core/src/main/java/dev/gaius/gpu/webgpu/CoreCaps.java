package dev.gaius.gpu.webgpu;

import java.util.Set;

/** What the glue's device reports: adapter identity, the optional features it has and its limits. */
public record CoreCaps(String vendor, String architecture, String description, boolean fallbackAdapter,
        Set<String> features, int minUniformOffsetAlignment, int maxTextureDimension2D, long maxBufferSize,
        int maxColorAttachments) {

    /** A display name: the adapter description, or vendor and architecture. */
    public String name() {
        return this.description.isEmpty() ? ("WebGPU " + this.vendor + " " + this.architecture).trim() : this.description;
    }
}
