/*
 * WebGPU glue for the renderpearl WebGPU backend (dev.gaius.browser.webgpu, 26.3).
 *
 * The Java backend implements vanilla's backend SPI (GpuDeviceBackend, CommandEncoderBackend,
 * RenderPassBackend, GpuSurfaceBackend) and calls the functions of globalThis.GaiusWebGpu through
 * @JSBody. Every GPU object lives in a handle table here; Java only holds integer handles, so the
 * same Java code runs on the TeaVM JS target and on the WasmGC target (plain imports, no
 * object marshalling on the hot path).
 *
 * Conventions shared with the Java side and with the SPIR-V -> WGSL translator:
 *   - Binding contract. For the pipeline's flat uniform list (BackendRenderPipeline.CreateInfo
 *     .uniforms), uniform i is
 *       UNIFORM_BUFFER          -> @group(0) @binding(i), dynamic offset
 *       COMBINED_IMAGE_SAMPLER  -> @group(1) @binding(2i) texture, @group(1) @binding(2i + 1) sampler
 *       TEXEL_BUFFER            -> @group(1) @binding(2i) read-only storage buffer
 *   - Orientation. Translated vertex shaders negate the clip-space y, so textures keep the
 *     OpenGL/Vulkan row order vanilla expects (row 0 is the bottom of the image); the winding is
 *     then clockwise like vanilla's Vulkan backend (frontFace "cw"), and the surface blit samples
 *     the source so that row 0 ends up at the bottom of the canvas.
 *
 * Device acquisition is asynchronous in WebGPU, vanilla's device creation is not: the page calls
 * GaiusWebGpu.init(canvas) before the Java main starts and WebGpuBackend.loadLibrary() only reads
 * the outcome (state() === 2), so vanilla falls back to the next backend (WebGL 2) otherwise.
 */
(function (root) {
  "use strict";

  if (root.GaiusWebGpu && root.GaiusWebGpu.glueVersion) {
    return;
  }

  var G = {};
  G.glueVersion = 1;

  // ---- handle table -----------------------------------------------------------------------

  // Handles are never reused: the Java side caches bind groups by the handles of the resources
  // they reference, so a recycled handle would alias a destroyed resource.
  var objects = [null];
  var released = 0;

  function put(object) {
    objects.push(object);
    return objects.length - 1;
  }

  function get(handle) {
    return objects[handle];
  }

  function release(handle) {
    if (handle > 0 && handle < objects.length && objects[handle] !== null) {
      objects[handle] = null;
      released++;
    }
  }

  G.liveHandles = function () {
    return objects.length - 1 - released;
  };

  // ---- state and diagnostics --------------------------------------------------------------

  // 0 = not started, 1 = pending, 2 = ready, -1 = failed.
  G.status = 0;
  G.failure = "";
  G.lostReason = "";
  G.messages = [];
  G.stats = {
    passes: 0,
    draws: 0,
    submits: 0,
    pipelines: 0,
    bindGroups: 0,
    writeBufferCalls: 0,
    writeBufferBytes: 0,
    writeTextureCalls: 0,
    errors: 0
  };

  function note(message) {
    var text = String(message);
    if (G.messages.length < 128) {
      G.messages.push(text);
    }
    if (root.console && root.console.warn) {
      root.console.warn("[gaius-webgpu] " + text);
    }
  }

  G.note = note;

  G.state = function () {
    return G.status;
  };

  G.takeMessage = function () {
    return G.messages.length > 0 ? G.messages.shift() : null;
  };

  // ---- device -----------------------------------------------------------------------------

  var OPTIONAL_FEATURES = [
    "float32-blendable",
    "float32-filterable",
    "indirect-first-instance",
    "timestamp-query",
    "depth32float-stencil8",
    "rg11b10ufloat-renderable",
    "texture-compression-bc"
  ];

  // Limits raised to the adapter's value when it offers more than the default.
  var RAISED_LIMITS = [
    "maxBufferSize",
    "maxStorageBufferBindingSize",
    "maxUniformBufferBindingSize",
    "maxDynamicUniformBuffersPerPipelineLayout",
    "maxStorageBuffersInVertexStage",
    "maxColorAttachmentBytesPerSample",
    "maxTextureDimension2D",
    "maxSampledTexturesPerShaderStage",
    "maxSamplersPerShaderStage",
    "maxBindingsPerBindGroup"
  ];

  function fail(error) {
    G.status = -1;
    G.failure = String(error && error.message ? error.message : error);
    note("WebGPU unavailable: " + G.failure);
    return false;
  }

  /**
   * Requests the adapter and device and binds the canvas. Resolves to true when the device is
   * ready. options: { powerPreference, allowFallback }.
   */
  G.init = function (canvas, options) {
    if (G.initPromise) {
      return G.initPromise;
    }
    options = options || {};
    var gpu = root.navigator && root.navigator.gpu;
    if (!gpu) {
      G.initPromise = Promise.resolve(fail("navigator.gpu is not available"));
      return G.initPromise;
    }
    G.status = 1;
    G.initPromise = gpu.requestAdapter({ powerPreference: options.powerPreference || "high-performance" })
      .then(function (adapter) {
        if (!adapter) {
          throw new Error("requestAdapter returned null");
        }
        var info = adapter.info || {};
        G.adapterInfo = {
          vendor: info.vendor || "",
          architecture: info.architecture || "",
          device: info.device || "",
          description: info.description || "",
          isFallbackAdapter: !!(info.isFallbackAdapter || adapter.isFallbackAdapter)
        };
        if (G.adapterInfo.isFallbackAdapter && !options.allowFallback) {
          throw new Error("only a fallback (software) adapter is available");
        }
        var features = [];
        for (var i = 0; i < OPTIONAL_FEATURES.length; i++) {
          if (adapter.features.has(OPTIONAL_FEATURES[i])) {
            features.push(OPTIONAL_FEATURES[i]);
          }
        }
        var limits = {};
        for (var j = 0; j < RAISED_LIMITS.length; j++) {
          var name = RAISED_LIMITS[j];
          if (adapter.limits[name] !== undefined) {
            limits[name] = adapter.limits[name];
          }
        }
        G.adapter = adapter;
        return adapter.requestDevice({ label: "gaius", requiredFeatures: features, requiredLimits: limits });
      })
      .then(function (device) {
        G.device = device;
        G.queue = device.queue;
        device.lost.then(function (lost) {
          G.lostReason = (lost && lost.reason ? lost.reason : "unknown") + ": " + (lost && lost.message ? lost.message : "");
          note("device lost " + G.lostReason);
        });
        device.addEventListener("uncapturederror", function (event) {
          G.stats.errors++;
          note(event.error && event.error.message ? event.error.message : event.error);
        });
        G.canvasFormat = gpu.getPreferredCanvasFormat();
        // The canvas context is taken when the backend first configures the surface: a canvas
        // that has a "webgpu" context can no longer give the WebGL 2 fallback its context.
        G.canvas = canvas || null;
        G.status = 2;
        return true;
      })
      .catch(fail);
    return G.initPromise;
  };

  G.hasFeature = function (name) {
    return !!(G.device && G.device.features.has(name));
  };

  G.limit = function (name) {
    var value = G.device ? G.device.limits[name] : undefined;
    return typeof value === "number" ? value : -1;
  };

  G.adapterString = function (key) {
    return G.adapterInfo && G.adapterInfo[key] !== undefined ? String(G.adapterInfo[key]) : "";
  };

  // ---- buffers ----------------------------------------------------------------------------

  G.createBuffer = function (size, usage, label) {
    return put(G.device.createBuffer({ size: size, usage: usage, label: label || undefined }));
  };

  G.destroyBuffer = function (handle) {
    var buffer = get(handle);
    if (buffer) {
      buffer.destroy();
    }
    release(handle);
  };

  /** data: an Int8Array (a zero-copy view of a Java direct buffer); its byteLength is a multiple of 4. */
  G.writeBuffer = function (handle, offset, data) {
    G.queue.writeBuffer(get(handle), offset, data.buffer, data.byteOffset, data.byteLength);
    G.stats.writeBufferCalls++;
    G.stats.writeBufferBytes += data.byteLength;
  };

  // ---- per-submit uploads through a ring of mapped staging buffers --------------------------
  //
  // Transient memory flushes each written block once per submit. "map" copies the bytes into a
  // MAP_WRITE staging buffer that is already mapped (one TypedArray copy, no IPC payload) and
  // records a copyBufferToBuffer in an upload command buffer that is submitted ahead of the
  // frame's commands; the staging buffer is re-mapped asynchronously once the queue is past it.
  // "write" uses queue.writeBuffer instead. A staging buffer not mapped back yet is simply not
  // reused, so the ring grows to the frames in flight and then stays flat.

  G.uploadMode = "map";
  var stagingFree = {};
  var stagingInFlight = [];
  var uploadEncoder = null;

  function stagingClass(size) {
    var bytes = 65536;
    while (bytes < size) {
      bytes *= 2;
    }
    return bytes;
  }

  G.upload = function (handle, offset, data) {
    if (G.uploadMode !== "map") {
      G.writeBuffer(handle, offset, data);
      return;
    }
    var size = data.byteLength;
    var bytes = stagingClass(size);
    var pool = stagingFree[bytes] || (stagingFree[bytes] = []);
    var staging = pool.length > 0 ? pool.pop() : G.device.createBuffer({
      label: "gaius staging",
      size: bytes,
      usage: 6, // MAP_WRITE | COPY_SRC
      mappedAtCreation: true
    });
    new Int8Array(staging.getMappedRange(0, size)).set(data);
    staging.unmap();
    if (uploadEncoder === null) {
      uploadEncoder = G.device.createCommandEncoder({ label: "gaius uploads" });
    }
    uploadEncoder.copyBufferToBuffer(staging, 0, get(handle), offset, size);
    stagingInFlight.push(staging);
    G.stats.stagingUploads = (G.stats.stagingUploads || 0) + 1;
    G.stats.stagingBytes = (G.stats.stagingBytes || 0) + size;
  };

  function recycle(staging) {
    staging.mapAsync(2).then(function () { // GPUMapMode.WRITE
      var pool = stagingFree[staging.size] || (stagingFree[staging.size] = []);
      pool.push(staging);
    }, function () {
      staging.destroy();
    });
  }

  // ---- textures and samplers --------------------------------------------------------------

  G.createTexture = function (width, height, layers, mips, format, usage, label) {
    return put(G.device.createTexture({
      label: label || undefined,
      size: { width: width, height: height, depthOrArrayLayers: layers },
      mipLevelCount: mips,
      format: format,
      usage: usage,
      dimension: "2d"
    }));
  };

  G.destroyTexture = function (handle) {
    var texture = get(handle);
    if (texture) {
      texture.destroy();
    }
    release(handle);
  };

  G.createView = function (textureHandle, baseMip, mipCount, cube) {
    return put(get(textureHandle).createView({
      baseMipLevel: baseMip,
      mipLevelCount: mipCount,
      dimension: cube ? "cube" : "2d",
      baseArrayLayer: 0,
      arrayLayerCount: cube ? 6 : 1
    }));
  };

  G.releaseHandle = release;

  G.writeTexture = function (textureHandle, mip, layer, x, y, width, height, bytesPerRow, data) {
    G.queue.writeTexture(
      { texture: get(textureHandle), mipLevel: mip, origin: { x: x, y: y, z: layer } },
      data,
      { offset: 0, bytesPerRow: bytesPerRow, rowsPerImage: height },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
    G.stats.writeTextureCalls++;
  };

  G.createSampler = function (addressU, addressV, minFilter, magFilter, mipmapFilter, anisotropy, maxLod) {
    var descriptor = {
      addressModeU: addressU,
      addressModeV: addressV,
      minFilter: minFilter,
      magFilter: magFilter,
      mipmapFilter: mipmapFilter
    };
    if (maxLod >= 0) {
      descriptor.lodMaxClamp = maxLod;
    }
    // WebGPU only allows anisotropy when every filter is linear.
    if (anisotropy > 1 && minFilter === "linear" && magFilter === "linear" && mipmapFilter === "linear") {
      descriptor.maxAnisotropy = anisotropy;
    }
    return put(G.device.createSampler(descriptor));
  };

  // ---- shaders and pipelines --------------------------------------------------------------

  G.createShaderModule = function (code, label) {
    var module = G.device.createShaderModule({ code: code, label: label || undefined });
    if (module.getCompilationInfo) {
      module.getCompilationInfo().then(function (info) {
        for (var i = 0; i < info.messages.length; i++) {
          var message = info.messages[i];
          if (message.type === "error") {
            note("WGSL " + (label || "") + ":" + message.lineNum + ":" + message.linePos + " " + message.message);
          }
        }
      });
    }
    return put(module);
  };

  var layoutCache = {};

  function bindGroupLayouts(uniforms) {
    var key = uniforms.join(",");
    var cached = layoutCache[key];
    if (cached) {
      return cached;
    }
    var stages = 3; // GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT
    var group0 = [];
    var group1 = [];
    for (var i = 0; i < uniforms.length; i++) {
      var kind = uniforms[i];
      if (kind === "ubo") {
        group0.push({ binding: i, visibility: stages, buffer: { type: "uniform", hasDynamicOffset: true } });
      } else if (kind === "texel") {
        group1.push({ binding: 2 * i, visibility: stages, buffer: { type: "read-only-storage" } });
      } else {
        var unfilterable = kind === "tex-unfilterable";
        group1.push({
          binding: 2 * i,
          visibility: stages,
          texture: { sampleType: unfilterable ? "unfilterable-float" : "float", viewDimension: kind === "tex-cube" ? "cube" : "2d" }
        });
        group1.push({ binding: 2 * i + 1, visibility: stages, sampler: { type: unfilterable ? "non-filtering" : "filtering" } });
      }
    }
    var layouts = [
      G.device.createBindGroupLayout({ entries: group0 }),
      G.device.createBindGroupLayout({ entries: group1 })
    ];
    cached = {
      groups: layouts,
      pipelineLayout: G.device.createPipelineLayout({ bindGroupLayouts: layouts })
    };
    layoutCache[key] = cached;
    return cached;
  }

  function blendComponent(component) {
    return { srcFactor: component[0], dstFactor: component[1], operation: component[2] };
  }

  /**
   * Creates one depth variant of a pipeline. json is the JSON description built by
   * WebGpuRenderPipeline; depthFormat is the render pass's depth format or "" for none.
   * Returns a handle, or 0 when the descriptor is rejected synchronously.
   */
  G.createPipeline = function (json, depthFormat) {
    var d = JSON.parse(json);
    var layouts = bindGroupLayouts(d.uniforms);
    var buffers = [];
    for (var i = 0; i < d.buffers.length; i++) {
      var b = d.buffers[i];
      if (!b) {
        buffers.push(null);
        continue;
      }
      var attributes = [];
      for (var j = 0; j < b.attributes.length; j++) {
        var a = b.attributes[j];
        attributes.push({ shaderLocation: a[0], offset: a[1], format: a[2] });
      }
      buffers.push({ arrayStride: b.stride, stepMode: b.instance ? "instance" : "vertex", attributes: attributes });
    }
    var targets = [];
    for (var k = 0; k < d.targets.length; k++) {
      var t = d.targets[k];
      if (!t) {
        targets.push(null);
        continue;
      }
      var target = { format: t.format, writeMask: t.mask };
      if (t.blend) {
        target.blend = { color: blendComponent(t.blend[0]), alpha: blendComponent(t.blend[1]) };
      }
      targets.push(target);
    }
    var descriptor = {
      label: d.label,
      layout: layouts.pipelineLayout,
      vertex: { module: get(d.vs), entryPoint: d.vsEntry, buffers: buffers },
      fragment: { module: get(d.fs), entryPoint: d.fsEntry, targets: targets },
      primitive: { topology: d.topology, cullMode: d.cull ? "back" : "none", frontFace: "cw" }
    };
    if (depthFormat) {
      var depth = d.depth;
      descriptor.depthStencil = {
        format: depthFormat,
        depthWriteEnabled: depth ? depth.write : false,
        depthCompare: depth ? depth.compare : "always",
        depthBias: depth ? depth.bias : 0,
        depthBiasSlopeScale: depth ? depth.slope : 0
      };
    }
    G.device.pushErrorScope("validation");
    var pipeline;
    try {
      pipeline = G.device.createRenderPipeline(descriptor);
    } catch (error) {
      G.device.popErrorScope();
      note("pipeline " + d.label + ": " + error);
      return 0;
    }
    var entry = { pipeline: pipeline, groups: layouts.groups, label: d.label, valid: true };
    G.device.popErrorScope().then(function (error) {
      if (error) {
        entry.valid = false;
        G.stats.errors++;
        note("pipeline " + d.label + " (" + (depthFormat || "no depth") + "): " + error.message);
      }
    });
    G.stats.pipelines++;
    return put(entry);
  };

  // ---- bind groups (built entry by entry, then created) -----------------------------------

  var bindEntries = [];

  G.bindBuffer = function (binding, bufferHandle, offset, size) {
    bindEntries.push({ binding: binding, resource: { buffer: get(bufferHandle), offset: offset, size: size } });
  };

  G.bindTexture = function (binding, viewHandle, samplerHandle) {
    bindEntries.push({ binding: binding, resource: get(viewHandle) });
    bindEntries.push({ binding: binding + 1, resource: get(samplerHandle) });
  };

  G.createBindGroup = function (pipelineHandle, group) {
    var entries = bindEntries;
    bindEntries = [];
    G.stats.bindGroups++;
    return put(G.device.createBindGroup({ layout: get(pipelineHandle).groups[group], entries: entries }));
  };

  // ---- command encoding -------------------------------------------------------------------

  var encoder = null;
  var pass = null;
  var colorAttachments = [];
  var depthAttachment = null;
  var offsets = new Uint32Array(16);

  function currentEncoder() {
    if (encoder === null) {
      encoder = G.device.createCommandEncoder();
    }
    return encoder;
  }

  G.passColor = function (index, viewHandle, clear, r, g, b, a) {
    colorAttachments[index] = viewHandle === 0 ? null : {
      view: get(viewHandle),
      loadOp: clear ? "clear" : "load",
      storeOp: "store",
      clearValue: { r: r, g: g, b: b, a: a }
    };
  };

  G.passDepth = function (viewHandle, clear, depth, hasStencil) {
    depthAttachment = {
      view: get(viewHandle),
      depthLoadOp: clear ? "clear" : "load",
      depthStoreOp: "store",
      depthClearValue: depth
    };
    if (hasStencil) {
      depthAttachment.stencilLoadOp = "load";
      depthAttachment.stencilStoreOp = "store";
    }
  };

  G.passBegin = function (colorCount) {
    colorAttachments.length = colorCount;
    var descriptor = { colorAttachments: colorAttachments };
    if (depthAttachment !== null) {
      descriptor.depthStencilAttachment = depthAttachment;
    }
    pass = currentEncoder().beginRenderPass(descriptor);
    colorAttachments = [];
    depthAttachment = null;
    G.stats.passes++;
  };

  G.passEnd = function () {
    if (pass !== null) {
      pass.end();
      pass = null;
    }
  };

  G.setPipeline = function (handle) {
    pass.setPipeline(get(handle).pipeline);
  };

  G.setVertexBuffer = function (slot, bufferHandle, offset, size) {
    pass.setVertexBuffer(slot, get(bufferHandle), offset, size);
  };

  G.setIndexBuffer = function (bufferHandle, wide, offset) {
    pass.setIndexBuffer(get(bufferHandle), wide ? "uint32" : "uint16", offset);
  };

  /** Group 0 carries every uniform buffer with a dynamic offset; up to four offsets inline. */
  G.setBindGroup = function (group, bindGroupHandle, count, o0, o1, o2, o3) {
    if (count === 0) {
      pass.setBindGroup(group, get(bindGroupHandle));
      return;
    }
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    pass.setBindGroup(group, get(bindGroupHandle), offsets, 0, count);
  };

  /** For pipelines with more than four uniform buffers: stage offsets, then setBindGroupStaged. */
  G.stageOffset = function (index, value) {
    offsets[index] = value;
  };

  G.setBindGroupStaged = function (group, bindGroupHandle, count) {
    pass.setBindGroup(group, get(bindGroupHandle), offsets, 0, count);
  };

  G.setScissor = function (x, y, width, height) {
    pass.setScissorRect(x, y, width, height);
  };

  G.draw = function (vertexCount, instanceCount, firstVertex, firstInstance) {
    pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    G.stats.draws++;
  };

  G.drawIndexed = function (indexCount, instanceCount, firstIndex, baseVertex, firstInstance) {
    pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    G.stats.draws++;
  };

  /** setBindGroup(0, ...) with new dynamic offsets, fused with the draw that needs them. */
  G.drawIndexedWithOffsets = function (bindGroupHandle, count, o0, o1, o2, o3, indexCount, instanceCount, firstIndex,
      baseVertex, firstInstance) {
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    pass.setBindGroup(0, get(bindGroupHandle), offsets, 0, count);
    pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    G.stats.draws++;
  };

  G.drawWithOffsets = function (bindGroupHandle, count, o0, o1, o2, o3, vertexCount, instanceCount, firstVertex,
      firstInstance) {
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    pass.setBindGroup(0, get(bindGroupHandle), offsets, 0, count);
    pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    G.stats.draws++;
  };

  G.copyBufferToBuffer = function (source, sourceOffset, target, targetOffset, size) {
    currentEncoder().copyBufferToBuffer(get(source), sourceOffset, get(target), targetOffset, size);
  };

  G.copyTextureToTexture = function (source, target, mip, destX, destY, sourceX, sourceY, width, height) {
    currentEncoder().copyTextureToTexture(
      { texture: get(source), mipLevel: mip, origin: { x: sourceX, y: sourceY } },
      { texture: get(target), mipLevel: mip, origin: { x: destX, y: destY } },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
  };

  G.submit = function () {
    if (pass !== null) {
      note("submit with an open render pass");
      G.passEnd();
    }
    var buffers = [];
    if (uploadEncoder !== null) {
      buffers.push(uploadEncoder.finish());
      uploadEncoder = null;
    }
    if (encoder !== null) {
      buffers.push(encoder.finish());
      encoder = null;
    }
    if (buffers.length > 0) {
      G.queue.submit(buffers);
    }
    for (var i = 0; i < stagingInFlight.length; i++) {
      recycle(stagingInFlight[i]);
    }
    stagingInFlight.length = 0;
    G.stats.submits++;
  };

  // ---- surface ----------------------------------------------------------------------------

  var blit = null;
  var surfaceTexture = null;

  function bindCanvas() {
    if (!G.context && G.canvas && G.device) {
      G.context = G.canvas.getContext("webgpu");
    }
    return !!G.context;
  }

  G.configureSurface = function (width, height) {
    if (!bindCanvas()) {
      return false;
    }
    if (G.canvas.width !== width) {
      G.canvas.width = width;
    }
    if (G.canvas.height !== height) {
      G.canvas.height = height;
    }
    G.context.configure({ device: G.device, format: G.canvasFormat, alphaMode: "opaque" });
    return true;
  };

  G.acquireSurface = function () {
    if (!bindCanvas()) {
      return false;
    }
    surfaceTexture = G.context.getCurrentTexture();
    return true;
  };

  function blitPipeline() {
    if (blit !== null) {
      return blit;
    }
    var module = G.device.createShaderModule({
      label: "gaius surface blit",
      code: [
        "@group(0) @binding(0) var source: texture_2d<f32>;",
        "@group(0) @binding(1) var sourceSampler: sampler;",
        "struct Varyings { @builtin(position) position: vec4f, @location(0) uv: vec2f };",
        "@vertex fn vs(@builtin(vertex_index) index: u32) -> Varyings {",
        "  let p = vec2f(f32((index << 1u) & 2u), f32(index & 2u));",
        "  var out: Varyings;",
        "  out.position = vec4f(p * 2.0 - 1.0, 0.0, 1.0);",
        // Row 0 of the source is the bottom of the image (OpenGL/Vulkan order): uv.y = 0 at the
        // bottom edge of the canvas, which mirrors the flipped vkCmdBlitImage of vanilla Vulkan.
        "  out.uv = p;",
        "  return out;",
        "}",
        "@fragment fn fs(in: Varyings) -> @location(0) vec4f {",
        "  return vec4f(textureSample(source, sourceSampler, in.uv).rgb, 1.0);",
        "}"
      ].join("\n")
    });
    var pipeline = G.device.createRenderPipeline({
      label: "gaius surface blit",
      layout: "auto",
      vertex: { module: module, entryPoint: "vs" },
      fragment: { module: module, entryPoint: "fs", targets: [{ format: G.canvasFormat }] },
      primitive: { topology: "triangle-list" }
    });
    blit = {
      pipeline: pipeline,
      sampler: G.device.createSampler({ minFilter: "linear", magFilter: "linear" }),
      groups: new WeakMap()
    };
    return blit;
  }

  G.blitToSurface = function (viewHandle) {
    if (surfaceTexture === null) {
      return;
    }
    var state = blitPipeline();
    var view = get(viewHandle);
    var group = state.groups.get(view);
    if (!group) {
      group = G.device.createBindGroup({
        layout: state.pipeline.getBindGroupLayout(0),
        entries: [{ binding: 0, resource: view }, { binding: 1, resource: state.sampler }]
      });
      state.groups.set(view, group);
    }
    var blitPass = currentEncoder().beginRenderPass({
      colorAttachments: [{ view: surfaceTexture.createView(), loadOp: "clear", storeOp: "store", clearValue: { r: 0, g: 0, b: 0, a: 1 } }]
    });
    blitPass.setPipeline(state.pipeline);
    blitPass.setBindGroup(0, group);
    blitPass.draw(3, 1, 0, 0);
    blitPass.end();
  };

  G.present = function () {
    // The canvas presents when the current task ends; nothing to do here.
    surfaceTexture = null;
  };

  // ---- diagnostics: texel readback (asynchronous, for harnesses and golden checks) --------

  G.readTexels = function (textureHandle, x, y, width, height) {
    var texture = get(textureHandle);
    var bytesPerRow = Math.ceil(width * 4 / 256) * 256;
    var buffer = G.device.createBuffer({ size: bytesPerRow * height, usage: 9 }); // MAP_READ | COPY_DST
    var commands = G.device.createCommandEncoder();
    commands.copyTextureToBuffer(
      { texture: texture, origin: { x: x, y: y } },
      { buffer: buffer, bytesPerRow: bytesPerRow, rowsPerImage: height },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
    G.queue.submit([commands.finish()]);
    return buffer.mapAsync(1).then(function () {
      var mapped = new Uint8Array(buffer.getMappedRange());
      var out = new Uint8Array(width * height * 4);
      for (var row = 0; row < height; row++) {
        out.set(mapped.subarray(row * bytesPerRow, row * bytesPerRow + width * 4), row * width * 4);
      }
      buffer.unmap();
      buffer.destroy();
      return out;
    });
  };

  root.GaiusWebGpu = G;
})(typeof globalThis !== "undefined" ? globalThis : this);
