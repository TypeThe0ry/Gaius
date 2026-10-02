// Gaius GPU capability layer: probes the WebGL2 features the graphics quality tiers depend on,
// runs a short fill-rate microbenchmark and classifies the device as low, mid, high or ultra.
//
// Load order (plain scripts, no module loader): gpu-caps.js, quality-profile.js, gl-pass.js,
// upscaler.js, post-chain.js, quality-runtime.js. Every file registers itself on
// window.GaiusQuality; this one adds GaiusQuality.caps and publishes window.__gaiusGpuCaps.
//
// Two entry points:
//   GaiusQuality.caps.ensureTier()  synchronous; returns the classification. The first call on a
//       device creates a throw-away WebGL2 canvas, probes it, benchmarks it (about 150 ms at
//       most) and releases it again. The result is remembered in localStorage, so later boots
//       only read it back. The launcher calls this after storage is ready and before the client
//       starts, because BrowserFilePersistence picks the first-launch option defaults from it.
//   GaiusQuality.caps.attach(gl)    called with the game's own context once it exists. Enables
//       the float colour-buffer, float-blend, indexed draw-buffer, float-linear, anisotropic,
//       parallel-compile, multi-draw and timer-query extensions on that context (WebGL
//       extensions are per context and must be re-enabled after a context loss), re-checks float
//       framebuffer completeness and republishes the capabilities.
//
// Overrides: ?gaiusTier=low|mid|high|ultra (also stored as localStorage gaius.quality.tierOverride
// by GaiusQuality.profile.setTier), ?gaiusTier=auto clears the stored override, and
// ?gaiusGpuRebench=1 discards the remembered benchmark.
(function installGaiusGpuCaps(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.caps) return;

  const STORAGE_KEY = "gaius.quality.gpuTier.v1";
  const OVERRIDE_KEY = "gaius.quality.tierOverride";
  const SCHEMA = 1;
  const TIERS = Object.freeze(["low", "mid", "high", "ultra"]);
  const BENCH_SIZE = 512;
  const BENCH_BUDGET_MS = 150;
  const BENCH_MIN_SAMPLE_MS = 6;
  // Provisional fill-rate thresholds in megapixels per second for the benchmark shader below
  // (about 24 dependent sin/cos/fract iterations per pixel). They are deliberately low: the
  // renderer string decides the ceiling, the benchmark mostly catches weak or throttled GPUs.
  // Recalibrate from the telemetry field __gaiusGpuCaps.score once real devices report it.
  const SCORE_MID = 350;
  const SCORE_HIGH = 2200;
  const SCORE_ULTRA = 7000;

  const EXTENSIONS = Object.freeze({
    colorBufferFloat: "EXT_color_buffer_float",
    colorBufferHalfFloat: "EXT_color_buffer_half_float",
    floatBlend: "EXT_float_blend",
    drawBuffersIndexed: "OES_draw_buffers_indexed",
    textureFloatLinear: "OES_texture_float_linear",
    anisotropic: "EXT_texture_filter_anisotropic",
    parallelShaderCompile: "KHR_parallel_shader_compile",
    multiDraw: "WEBGL_multi_draw",
    timerQuery: "EXT_disjoint_timer_query_webgl2",
    debugRendererInfo: "WEBGL_debug_renderer_info",
    loseContext: "WEBGL_lose_context"
  });

  const state = {
    caps: null,
    attachedContext: null,
    attachedListeners: false,
    // Fields of window.__gaiusGpuCaps this layer wrote (see publish).
    ownedKeys: null
  };

  function tierIndex(name) {
    const index = TIERS.indexOf(String(name || "").toLowerCase());
    return index < 0 ? -1 : index;
  }

  function clampTier(index, floor, cap) {
    let value = index;
    if (floor >= 0 && value < floor) value = floor;
    if (cap >= 0 && value > cap) value = cap;
    return Math.max(0, Math.min(TIERS.length - 1, value));
  }

  function urlParam(name) {
    try {
      return new URLSearchParams(root.location ? root.location.search : "").get(name);
    } catch (error) {
      return null;
    }
  }

  function storageGet(key) {
    try {
      return root.localStorage ? root.localStorage.getItem(key) : null;
    } catch (error) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      if (!root.localStorage) return false;
      if (value === null || value === undefined) root.localStorage.removeItem(key);
      else root.localStorage.setItem(key, value);
      return true;
    } catch (error) {
      return false;
    }
  }

  function readStoredRecord() {
    const raw = storageGet(STORAGE_KEY);
    if (!raw) return null;
    try {
      const record = JSON.parse(raw);
      if (!record || record.schema !== SCHEMA || tierIndex(record.tier) < 0) return null;
      return record;
    } catch (error) {
      return null;
    }
  }

  function writeStoredRecord(caps) {
    storageSet(STORAGE_KEY, JSON.stringify({
      schema: SCHEMA,
      tier: caps.tier,
      kind: caps.kind,
      score: caps.score,
      renderer: caps.renderer,
      vendor: caps.vendor,
      rebench: !!caps.rebench,
      extensions: caps.extensions || null,
      floatTargets: caps.floatTargets || null,
      maxTextureSize: caps.maxTextureSize || 0,
      at: Date.now()
    }));
  }

  function overrideTier() {
    const param = urlParam("gaiusTier");
    if (param !== null) {
      const value = String(param).toLowerCase();
      if (value === "auto" || value === "") {
        storageSet(OVERRIDE_KEY, null);
        return null;
      }
      if (tierIndex(value) >= 0) return value;
    }
    const stored = storageGet(OVERRIDE_KEY);
    return tierIndex(stored) >= 0 ? String(stored).toLowerCase() : null;
  }

  function navigatorInfo() {
    const nav = root.navigator || {};
    const ua = String(nav.userAgent || "");
    let mobile = false;
    try {
      mobile = !!(nav.userAgentData && nav.userAgentData.mobile);
    } catch (error) {
      mobile = false;
    }
    if (!mobile) mobile = /Android|iPhone|iPod|Mobile|Silk|Kindle/i.test(ua);
    // iPadOS reports a desktop Safari user agent; a Mac with touch points is an iPad.
    const ipad = /iPad/.test(ua) || (/Macintosh/.test(ua) && Number(nav.maxTouchPoints || 0) > 1);
    return {
      mobile: mobile || ipad,
      tablet: ipad || (/Android/i.test(ua) && !/Mobile/i.test(ua)),
      deviceMemory: Number(nav.deviceMemory || 0),
      hardwareConcurrency: Number(nav.hardwareConcurrency || 0),
      userAgent: ua
    };
  }

  function rendererInfo(gl) {
    let vendor = "";
    let renderer = "";
    let unmasked = false;
    try {
      const debug = gl.getExtension(EXTENSIONS.debugRendererInfo);
      if (debug) {
        vendor = String(gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) || "");
        renderer = String(gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) || "");
        unmasked = renderer.length > 0;
      }
    } catch (error) {
      unmasked = false;
    }
    if (!renderer) {
      try {
        vendor = vendor || String(gl.getParameter(gl.VENDOR) || "");
        renderer = String(gl.getParameter(gl.RENDERER) || "");
      } catch (error) {
        renderer = "";
      }
    }
    return {vendor: vendor, renderer: renderer, unmasked: unmasked};
  }

  // Coarse class of the GPU from its renderer string. ANGLE wraps the real name, e.g.
  // "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 (0x00002882) Direct3D11 vs_5_0 ps_5_0, D3D11)".
  // floor/cap are tier indexes (-1 = none).
  function classifyRenderer(renderer, vendor, nav) {
    const text = (String(renderer) + " " + String(vendor)).toLowerCase();
    if (/swiftshader|llvmpipe|softpipe|software|basic render|microsoft basic/.test(text)) {
      return {kind: "software", floor: 0, cap: 0, guess: 0};
    }
    if (/mali|adreno|powervr|videocore|immortalis|xclipse|tegra/.test(text) || nav.mobile) {
      // Snapdragon laptops (Adreno on Windows) are not phones; let them reach mid.
      const windowsArm = /adreno/.test(text) && /windows/i.test(nav.userAgent);
      return {kind: "mobile", floor: 0, cap: 1, guess: windowsArm ? 1 : 0};
    }
    if (/apple m\d+ (pro|max|ultra)/.test(text)) {
      return {kind: "integrated", floor: 1, cap: 2, guess: 2};
    }
    // Safari reports a masked "Apple GPU" on every Mac (iPhone/iPad are caught as mobile above).
    // Chrome on Intel Macs names the real GPU after the vendor, e.g. "ANGLE (Apple, ANGLE Metal
    // Renderer: AMD Radeon Pro 5500M, ...)"; those discrete parts fall through to the next case.
    if ((/apple m\d+/.test(text) || /apple/.test(text))
        && !/radeon pro|radeon rx|geforce|nvidia|quadro|firepro/.test(text)) {
      return {kind: "integrated", floor: 0, cap: 1, guess: 1};
    }
    if (/geforce|rtx|gtx|quadro|nvidia|radeon rx|radeon pro|radeon \(tm\) rx|firepro|intel\(r\) arc|intel arc|\barc a\d/.test(text)) {
      // Laptop "MX" GeForce parts are entry level; keep them out of ultra.
      const entry = /geforce mx|gt \d{3}\b|gt\d{3}\b/.test(text);
      return {kind: "discrete", floor: 1, cap: entry ? 2 : 3, guess: entry ? 1 : 2};
    }
    if (/intel|uhd graphics|iris|hd graphics|radeon\(tm\) graphics|radeon graphics|vega \d|radeon vega/.test(text)) {
      return {kind: "integrated", floor: 0, cap: 1, guess: 1};
    }
    return {kind: "unknown", floor: 0, cap: 2, guess: 1};
  }

  function tierFromScore(score) {
    if (!(score > 0)) return -1;
    if (score >= SCORE_ULTRA) return 3;
    if (score >= SCORE_HIGH) return 2;
    if (score >= SCORE_MID) return 1;
    return 0;
  }

  function enableExtensions(gl) {
    const result = {};
    const names = Object.keys(EXTENSIONS);
    for (let i = 0; i < names.length; i++) {
      const key = names[i];
      if (key === "loseContext") continue;
      let enabled = false;
      try {
        enabled = !!gl.getExtension(EXTENSIONS[key]);
      } catch (error) {
        enabled = false;
      }
      result[key] = enabled;
    }
    let supported = [];
    try {
      supported = gl.getSupportedExtensions() || [];
    } catch (error) {
      supported = [];
    }
    result.supported = supported.slice(0);
    return result;
  }

  function limits(gl, extensions) {
    const read = function (name, fallback) {
      try {
        const value = gl.getParameter(name);
        return typeof value === "number" ? value : fallback;
      } catch (error) {
        return fallback;
      }
    };
    let maxAnisotropy = 1;
    if (extensions.anisotropic) {
      try {
        const ext = gl.getExtension(EXTENSIONS.anisotropic);
        maxAnisotropy = Number(gl.getParameter(ext.MAX_TEXTURE_MAX_ANISOTROPY_EXT)) || 1;
      } catch (error) {
        maxAnisotropy = 1;
      }
    }
    return {
      maxTextureSize: read(gl.MAX_TEXTURE_SIZE, 2048),
      maxRenderbufferSize: read(gl.MAX_RENDERBUFFER_SIZE, 2048),
      maxDrawBuffers: read(gl.MAX_DRAW_BUFFERS, 4),
      maxColorAttachments: read(gl.MAX_COLOR_ATTACHMENTS, 4),
      maxSamples: read(gl.MAX_SAMPLES, 4),
      maxUniformBlockSize: read(gl.MAX_UNIFORM_BLOCK_SIZE, 16384),
      maxTextureImageUnits: read(gl.MAX_TEXTURE_IMAGE_UNITS, 16),
      maxAnisotropy: maxAnisotropy
    };
  }

  // Saves and restores exactly the bindings the completeness probe touches, so probing the
  // game's own context cannot desynchronise BrowserOpenGL's binding caches.
  // drainErrors is only set for the private probe context: on the game's context a pending error
  // belongs to BrowserOpenGL and must stay visible to it.
  function probeFloatTargets(gl, extensions, drainErrors) {
    const result = {rgba16f: false, rgba32f: false, r11fg11fb10f: false};
    if (!extensions.colorBufferFloat && !extensions.colorBufferHalfFloat) return result;
    let savedTexture = null;
    let savedDraw = null;
    let savedRead = null;
    let savedUnpack = null;
    try {
      savedTexture = gl.getParameter(gl.TEXTURE_BINDING_2D);
      savedDraw = gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING);
      savedRead = gl.getParameter(gl.READ_FRAMEBUFFER_BINDING);
      savedUnpack = gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING);
    } catch (error) {
      return result;
    }
    const formats = [
      ["rgba16f", gl.RGBA16F],
      ["rgba32f", gl.RGBA32F],
      ["r11fg11fb10f", gl.R11F_G11F_B10F]
    ];
    const framebuffer = gl.createFramebuffer();
    try {
      gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      for (let i = 0; i < formats.length; i++) {
        const texture = gl.createTexture();
        try {
          gl.bindTexture(gl.TEXTURE_2D, texture);
          gl.texStorage2D(gl.TEXTURE_2D, 1, formats[i][1], 4, 4);
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
          result[formats[i][0]] =
              gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, null, 0);
        } finally {
          gl.deleteTexture(texture);
        }
      }
    } catch (error) {
      // Leave the remaining formats false.
    } finally {
      gl.deleteFramebuffer(framebuffer);
      gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, savedDraw);
      gl.bindFramebuffer(gl.READ_FRAMEBUFFER, savedRead);
      gl.bindTexture(gl.TEXTURE_2D, savedTexture);
      gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, savedUnpack);
      if (drainErrors) {
        try {
          let guard = 16;
          while (gl.getError() !== gl.NO_ERROR && guard-- > 0) { /* drain */ }
        } catch (error) {
          // ignore
        }
      }
    }
    return result;
  }

  const BENCH_VS = "#version 300 es\n"
      + "void main() {\n"
      + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
      + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
      + "}\n";
  const BENCH_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "uniform float uSeed;\n"
      + "out vec4 outColor;\n"
      + "void main() {\n"
      + "  vec2 p = gl_FragCoord.xy * 0.0137 + uSeed;\n"
      + "  vec3 c = vec3(0.0);\n"
      + "  for (int i = 0; i < 24; i++) {\n"
      + "    p = vec2(p.x * 1.031 + sin(p.y), p.y * 0.977 + cos(p.x));\n"
      + "    c += vec3(fract(p.x), fract(p.y), fract(p.x + p.y)) * 0.041;\n"
      + "  }\n"
      + "  outColor = vec4(c, 1.0);\n"
      + "}\n";

  function compile(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error("benchmark shader: " + log);
    }
    return shader;
  }

  // Fill-rate microbenchmark on a private context: draws full-screen triangles with a fixed
  // ALU-heavy fragment shader into a 512x512 RGBA8 target and synchronises with a one-pixel
  // readPixels. Doubles the draw count until a sample takes at least BENCH_MIN_SAMPLE_MS and
  // keeps the best megapixel-per-second figure within BENCH_BUDGET_MS.
  function runBenchmark(gl) {
    const now = function () {
      return root.performance && root.performance.now ? root.performance.now() : Date.now();
    };
    const started = now();
    let program = null;
    let vs = null;
    let fs = null;
    let texture = null;
    let framebuffer = null;
    let vao = null;
    try {
      vs = compile(gl, gl.VERTEX_SHADER, BENCH_VS);
      fs = compile(gl, gl.FRAGMENT_SHADER, BENCH_FS);
      program = gl.createProgram();
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error("benchmark link: " + gl.getProgramInfoLog(program));
      }
      texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, BENCH_SIZE, BENCH_SIZE);
      framebuffer = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
        throw new Error("benchmark framebuffer incomplete");
      }
      vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      gl.useProgram(program);
      gl.viewport(0, 0, BENCH_SIZE, BENCH_SIZE);
      gl.disable(gl.BLEND);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.SCISSOR_TEST);
      const seed = gl.getUniformLocation(program, "uSeed");
      const pixel = new Uint8Array(4);
      // Warm-up: compile on first use, flush the pipeline.
      gl.uniform1f(seed, 0.5);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      let draws = 2;
      let best = 0;
      let samples = 0;
      while (now() - started < BENCH_BUDGET_MS) {
        const t0 = now();
        for (let i = 0; i < draws; i++) {
          gl.uniform1f(seed, (i + 1) * 0.37 + samples);
          gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        const elapsed = now() - t0;
        samples++;
        if (elapsed >= BENCH_MIN_SAMPLE_MS) {
          const mpix = (draws * BENCH_SIZE * BENCH_SIZE) / (elapsed * 1000);
          if (mpix > best) best = mpix;
        }
        if (elapsed < BENCH_MIN_SAMPLE_MS * 2 && draws < 4096) draws *= 2;
        if (gl.isContextLost && gl.isContextLost()) break;
      }
      return {score: Math.round(best), ms: Math.round(now() - started), samples: samples};
    } catch (error) {
      return {score: 0, ms: Math.round(now() - started), samples: 0, error: String(error)};
    } finally {
      try {
        gl.bindVertexArray(null);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.bindTexture(gl.TEXTURE_2D, null);
        gl.useProgram(null);
        if (vao) gl.deleteVertexArray(vao);
        if (framebuffer) gl.deleteFramebuffer(framebuffer);
        if (texture) gl.deleteTexture(texture);
        if (program) gl.deleteProgram(program);
        if (vs) gl.deleteShader(vs);
        if (fs) gl.deleteShader(fs);
      } catch (error) {
        // The context is about to be released anyway.
      }
    }
  }

  function buildCaps(gl, nav, extensions, float, renderer, source) {
    const lim = limits(gl, extensions);
    return {
      schema: SCHEMA,
      source: source,
      tier: "mid",
      tierIndex: 1,
      kind: "unknown",
      score: 0,
      benchmarkMs: 0,
      renderer: renderer.renderer,
      vendor: renderer.vendor,
      rendererUnmasked: renderer.unmasked,
      mobile: nav.mobile,
      tablet: nav.tablet,
      deviceMemory: nav.deviceMemory,
      hardwareConcurrency: nav.hardwareConcurrency,
      extensions: {
        colorBufferFloat: !!extensions.colorBufferFloat,
        colorBufferHalfFloat: !!extensions.colorBufferHalfFloat,
        floatBlend: !!extensions.floatBlend,
        drawBuffersIndexed: !!extensions.drawBuffersIndexed,
        textureFloatLinear: !!extensions.textureFloatLinear,
        anisotropic: !!extensions.anisotropic,
        parallelShaderCompile: !!extensions.parallelShaderCompile,
        multiDraw: !!extensions.multiDraw,
        timerQuery: !!extensions.timerQuery
      },
      supportedExtensions: extensions.supported || [],
      floatTargets: float,
      maxTextureSize: lim.maxTextureSize,
      maxRenderbufferSize: lim.maxRenderbufferSize,
      maxDrawBuffers: lim.maxDrawBuffers,
      maxColorAttachments: lim.maxColorAttachments,
      maxSamples: lim.maxSamples,
      maxUniformBlockSize: lim.maxUniformBlockSize,
      maxTextureImageUnits: lim.maxTextureImageUnits,
      maxAnisotropy: lim.maxAnisotropy,
      rebench: false
    };
  }

  // Final tier: benchmark tier (or the renderer guess when the benchmark failed), clamped by the
  // renderer class, then capped by memory, CPU threads and missing render features.
  function decideTier(caps, score) {
    const nav = {mobile: caps.mobile, userAgent: (root.navigator && root.navigator.userAgent) || ""};
    const cls = classifyRenderer(caps.renderer, caps.vendor, nav);
    caps.kind = cls.kind;
    let index = tierFromScore(score);
    if (index < 0) index = cls.guess;
    index = clampTier(index, cls.floor, cls.cap);
    let cap = TIERS.length - 1;
    if (caps.deviceMemory > 0 && caps.deviceMemory <= 2) cap = Math.min(cap, 0);
    else if (caps.deviceMemory > 0 && caps.deviceMemory <= 4) cap = Math.min(cap, 1);
    if (caps.hardwareConcurrency > 0 && caps.hardwareConcurrency <= 2) cap = Math.min(cap, 0);
    else if (caps.hardwareConcurrency > 0 && caps.hardwareConcurrency <= 4) cap = Math.min(cap, 1);
    if (caps.maxTextureSize < 4096) cap = Math.min(cap, 0);
    if (!caps.floatTargets.rgba16f) cap = Math.min(cap, 1);
    index = Math.min(index, cap);
    caps.tierIndex = index;
    caps.tier = TIERS[index];
    return caps;
  }

  function probeTemporaryContext(nav) {
    const doc = root.document;
    if (!doc || typeof doc.createElement !== "function") return null;
    let canvas = null;
    let gl = null;
    try {
      canvas = doc.createElement("canvas");
      canvas.width = BENCH_SIZE;
      canvas.height = BENCH_SIZE;
      gl = canvas.getContext("webgl2", {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        powerPreference: "high-performance",
        failIfMajorPerformanceCaveat: false
      });
    } catch (error) {
      gl = null;
    }
    if (!gl) return null;
    try {
      const extensions = enableExtensions(gl);
      const float = probeFloatTargets(gl, extensions, true);
      const renderer = rendererInfo(gl);
      const caps = buildCaps(gl, nav, extensions, float, renderer, "probe");
      const bench = runBenchmark(gl);
      caps.score = bench.score;
      caps.benchmarkMs = bench.ms;
      if (bench.error) caps.benchmarkError = bench.error;
      return decideTier(caps, bench.score);
    } finally {
      try {
        const lose = gl.getExtension(EXTENSIONS.loseContext);
        if (lose) lose.loseContext();
      } catch (error) {
        // ignore
      }
      canvas.width = 1;
      canvas.height = 1;
    }
  }

  function fromStoredRecord(record, nav) {
    const index = tierIndex(record.tier);
    return {
      schema: SCHEMA,
      source: "storage",
      tier: TIERS[index],
      tierIndex: index,
      kind: String(record.kind || "unknown"),
      score: Number(record.score) || 0,
      benchmarkMs: 0,
      renderer: String(record.renderer || ""),
      vendor: String(record.vendor || ""),
      rendererUnmasked: !!record.renderer,
      mobile: nav.mobile,
      tablet: nav.tablet,
      deviceMemory: nav.deviceMemory,
      hardwareConcurrency: nav.hardwareConcurrency,
      // Remembered from the probe; attach() replaces them with the game context's own.
      extensions: record.extensions && typeof record.extensions === "object" ? record.extensions : null,
      supportedExtensions: [],
      floatTargets: record.floatTargets && typeof record.floatTargets === "object"
          ? record.floatTargets : null,
      maxTextureSize: Number(record.maxTextureSize) || 0,
      rebench: !!record.rebench
    };
  }

  function applyOverride(caps) {
    const detected = caps.overridden && caps.detectedTier ? caps.detectedTier : caps.tier;
    const forced = overrideTier();
    caps.detectedTier = detected;
    if (forced) {
      caps.tier = forced;
      caps.tierIndex = tierIndex(forced);
      caps.overridden = true;
    } else {
      caps.tier = detected;
      caps.tierIndex = tierIndex(detected);
      caps.overridden = false;
    }
    return caps;
  }

  // window.__gaiusGpuCaps has more than one writer: BrowserOpenGL adds anisotropicFiltering,
  // maxAnisotropy, multiDraw and terrainBatchMode to it at context init. A new capability record
  // is therefore merged into the published object instead of replacing it; only the fields this
  // layer wrote earlier and no longer reports are removed.
  function publish(caps) {
    let published = caps;
    try {
      const existing = root.__gaiusGpuCaps;
      if (existing && typeof existing === "object" && existing !== caps) {
        const owned = state.ownedKeys || [];
        for (let i = 0; i < owned.length; i++) {
          if (!Object.prototype.hasOwnProperty.call(caps, owned[i])) delete existing[owned[i]];
        }
        state.ownedKeys = Object.keys(caps);
        published = Object.assign(existing, caps);
      } else if (existing !== caps) {
        state.ownedKeys = Object.keys(caps);
        root.__gaiusGpuCaps = caps;
      }
    } catch (error) {
      // ignore
    }
    state.caps = published;
    if (Q.profile && typeof Q.profile.invalidate === "function") Q.profile.invalidate();
    return published;
  }

  function ensureTier() {
    if (state.caps) return state.caps;
    const nav = navigatorInfo();
    if (urlParam("gaiusGpuRebench") === "1") storageSet(STORAGE_KEY, null);
    const record = readStoredRecord();
    let caps = null;
    if (record && !record.rebench) {
      caps = fromStoredRecord(record, nav);
    } else {
      caps = probeTemporaryContext(nav);
      if (caps) writeStoredRecord(caps);
    }
    if (!caps) {
      // No document (Worker) or no WebGL2: classify from the user agent alone.
      caps = {
        schema: SCHEMA,
        source: "fallback",
        tier: nav.mobile ? "low" : "mid",
        tierIndex: nav.mobile ? 0 : 1,
        kind: nav.mobile ? "mobile" : "unknown",
        score: 0,
        renderer: "",
        vendor: "",
        mobile: nav.mobile,
        tablet: nav.tablet,
        deviceMemory: nav.deviceMemory,
        hardwareConcurrency: nav.hardwareConcurrency,
        extensions: null,
        floatTargets: null,
        rebench: false
      };
    }
    return publish(applyOverride(caps));
  }

  function onContextLost() {
    if (state.caps) state.caps.contextLost = true;
  }

  function onContextRestored() {
    const gl = state.attachedContext;
    if (gl) attach(gl);
  }

  // Binds the capability layer to the game's context. Safe to call repeatedly (it re-enables the
  // extensions, which a restored context has forgotten).
  function attach(gl) {
    if (!gl) return ensureTier();
    const base = ensureTier();
    const nav = navigatorInfo();
    const extensions = enableExtensions(gl);
    const float = probeFloatTargets(gl, extensions, false);
    const renderer = rendererInfo(gl);
    const caps = buildCaps(gl, nav, extensions, float, renderer, "attach");
    caps.score = base.score || 0;
    caps.benchmarkMs = base.benchmarkMs || 0;
    caps.kind = base.kind;
    caps.overridden = !!base.overridden;
    caps.detectedTier = base.detectedTier || base.tier;
    caps.contextLost = false;
    const sameGpu = !base.renderer || !renderer.renderer || base.renderer === renderer.renderer;
    if (sameGpu) {
      caps.tier = base.tier;
      caps.tierIndex = base.tierIndex;
    } else {
      // The game runs on a different adapter than the probe saw (or than the stored record
      // describes): classify from the real renderer now and benchmark again next boot.
      decideTier(caps, 0);
      caps.rebench = true;
      writeStoredRecord(caps);
      caps.overridden = false;
      caps.detectedTier = null;
      applyOverride(caps);
    }
    // A probe that found no float colour targets cannot be trusted over the real context.
    if (!float.rgba16f && caps.tierIndex > 1 && !caps.overridden) {
      caps.tierIndex = 1;
      caps.tier = TIERS[1];
    }
    state.attachedContext = gl;
    if (!state.attachedListeners && gl.canvas && gl.canvas.addEventListener) {
      state.attachedListeners = true;
      gl.canvas.addEventListener("webglcontextlost", onContextLost, false);
      gl.canvas.addEventListener("webglcontextrestored", onContextRestored, false);
    }
    return publish(caps);
  }

  function setOverride(name) {
    const index = tierIndex(name);
    storageSet(OVERRIDE_KEY, index >= 0 ? TIERS[index] : null);
    if (state.caps) publish(applyOverride(state.caps));
    return state.caps;
  }

  Q.caps = Object.freeze({
    TIERS: TIERS,
    STORAGE_KEY: STORAGE_KEY,
    ensureTier: ensureTier,
    attach: attach,
    current: function () {
      return state.caps;
    },
    tierIndex: tierIndex,
    setOverride: setOverride,
    forgetBenchmark: function () {
      storageSet(STORAGE_KEY, null);
    },
    classifyRenderer: function (renderer, vendor) {
      return classifyRenderer(renderer, vendor, navigatorInfo());
    }
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
