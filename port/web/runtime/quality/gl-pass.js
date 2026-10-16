// Gaius full-screen pass helpers shared by the upscaler and the post-processing chain.
// Registers GaiusQuality.gl.
//
// The passes run in the middle of the game's own WebGL2 command stream (BrowserOpenGL keeps
// JavaScript-side caches of bindings and the 26.3 renderer keeps Java-side ones), so every pass
// group is wrapped in saveState()/restoreState(): whatever a pass binds or enables is put back
// exactly as the GL context reported it before the group started. Passes never touch the game's
// buffers, vertex arrays or uniform-buffer bindings; full-screen triangles come from gl_VertexID
// with a private empty vertex array.
//
// Blend enable and colour write mask are per draw buffer under OES_draw_buffers_indexed, and the
// 26.3 renderer caches them per index (its order-independent transparency pipelines blend into
// several attachments). Passes only write draw buffer 0, so with the extension they change and
// restore index 0 alone; the non-indexed enable/colorMask calls would overwrite every index
// behind the game's back.
(function installGaiusGlPass(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.gl) return;

  const FULLSCREEN_VS = "#version 300 es\n"
      + "void main() {\n"
      + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
      + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
      + "}\n";

  // Common fragment prologue: rect-aware sampling helpers. uTexel is 1/size of the textures the
  // pass reads (all pass targets share the frame size); uClamp limits linear taps to the rendered
  // sub-rectangle when the world renders below full resolution.
  const FRAGMENT_PROLOGUE = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform vec2 uTexel;\n"
      + "uniform vec2 uClamp;\n"
      + "out vec4 outColor;\n"
      + "vec2 fragUv() { return gl_FragCoord.xy * uTexel; }\n"
      + "vec2 clampUv(vec2 uv) { return clamp(uv, uTexel * 0.5, uClamp); }\n"
      + "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n";

  function compileShader(gl, type, source, name) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error("Gaius quality shader " + name + " failed to compile: " + log);
    }
    return shader;
  }

  function createProgram(gl, name, fragmentSource, vertexSource) {
    const vs = compileShader(gl, gl.VERTEX_SHADER, vertexSource || FULLSCREEN_VS, name + ".vs");
    const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource, name + ".fs");
    const program = gl.createProgram();
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS) && !gl.isContextLost()) {
      const log = gl.getProgramInfoLog(program);
      gl.deleteProgram(program);
      throw new Error("Gaius quality program " + name + " failed to link: " + log);
    }
    const uniforms = new Map();
    return {
      name: name,
      program: program,
      uniform: function (uniformName) {
        if (uniforms.has(uniformName)) return uniforms.get(uniformName);
        const location = gl.getUniformLocation(program, uniformName);
        uniforms.set(uniformName, location);
        return location;
      }
    };
  }

  // Units the passes bind textures and samplers on. Restored individually.
  const UNIT_COUNT = 4;

  // OES_draw_buffers_indexed of this context, or null. Looked up on every pass group rather
  // than cached: a restored context hands out new extension objects.
  function indexedExtension(gl) {
    let extension = null;
    try {
      extension = gl.getExtension("OES_draw_buffers_indexed") || null;
    } catch (error) {
      extension = null;
    }
    if (extension && (typeof extension.enableiOES !== "function"
        || typeof extension.disableiOES !== "function"
        || typeof extension.colorMaskiOES !== "function")) {
      return null;
    }
    return extension;
  }

  // The non-indexed BLEND and COLOR_WRITEMASK queries report draw buffer 0.
  function saveState(gl) {
    const s = {
      indexed: indexedExtension(gl),
      program: gl.getParameter(gl.CURRENT_PROGRAM),
      vao: gl.getParameter(gl.VERTEX_ARRAY_BINDING),
      drawFramebuffer: gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING),
      readFramebuffer: gl.getParameter(gl.READ_FRAMEBUFFER_BINDING),
      activeTexture: gl.getParameter(gl.ACTIVE_TEXTURE),
      unpackBuffer: gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING),
      viewport: Array.prototype.slice.call(gl.getParameter(gl.VIEWPORT)),
      scissorTest: gl.isEnabled(gl.SCISSOR_TEST),
      scissorBox: Array.prototype.slice.call(gl.getParameter(gl.SCISSOR_BOX)),
      blend: gl.isEnabled(gl.BLEND),
      depthTest: gl.isEnabled(gl.DEPTH_TEST),
      stencilTest: gl.isEnabled(gl.STENCIL_TEST),
      cullFace: gl.isEnabled(gl.CULL_FACE),
      rasterizerDiscard: gl.isEnabled(gl.RASTERIZER_DISCARD),
      colorMask: Array.prototype.slice.call(gl.getParameter(gl.COLOR_WRITEMASK)),
      textures: new Array(UNIT_COUNT),
      samplers: new Array(UNIT_COUNT)
    };
    for (let unit = 0; unit < UNIT_COUNT; unit++) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      s.textures[unit] = gl.getParameter(gl.TEXTURE_BINDING_2D);
      s.samplers[unit] = gl.getParameter(gl.SAMPLER_BINDING);
    }
    gl.activeTexture(s.activeTexture);
    return s;
  }

  // The fixed-function state every pass expects: no blending, depth, stencil, culling, scissor
  // or discard; all colour channels written; no pixel-unpack buffer (texture allocation).
  function prepareState(gl, vao) {
    const indexed = indexedExtension(gl);
    if (indexed) {
      indexed.disableiOES(gl.BLEND, 0);
      indexed.colorMaskiOES(0, true, true, true, true);
    } else {
      gl.disable(gl.BLEND);
      gl.colorMask(true, true, true, true);
    }
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.STENCIL_TEST);
    gl.disable(gl.CULL_FACE);
    gl.disable(gl.SCISSOR_TEST);
    gl.disable(gl.RASTERIZER_DISCARD);
    gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
    gl.bindVertexArray(vao);
  }

  function setEnabled(gl, cap, enabled) {
    if (enabled) gl.enable(cap);
    else gl.disable(cap);
  }

  function restoreState(gl, s) {
    for (let unit = UNIT_COUNT - 1; unit >= 0; unit--) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(gl.TEXTURE_2D, s.textures[unit]);
      gl.bindSampler(unit, s.samplers[unit]);
    }
    gl.activeTexture(s.activeTexture);
    gl.bindVertexArray(s.vao);
    gl.useProgram(s.program);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, s.drawFramebuffer);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, s.readFramebuffer);
    gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, s.unpackBuffer);
    gl.viewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
    gl.scissor(s.scissorBox[0], s.scissorBox[1], s.scissorBox[2], s.scissorBox[3]);
    setEnabled(gl, gl.SCISSOR_TEST, s.scissorTest);
    setEnabled(gl, gl.DEPTH_TEST, s.depthTest);
    setEnabled(gl, gl.STENCIL_TEST, s.stencilTest);
    setEnabled(gl, gl.CULL_FACE, s.cullFace);
    setEnabled(gl, gl.RASTERIZER_DISCARD, s.rasterizerDiscard);
    if (s.indexed) {
      if (s.blend) s.indexed.enableiOES(gl.BLEND, 0);
      else s.indexed.disableiOES(gl.BLEND, 0);
      s.indexed.colorMaskiOES(0, s.colorMask[0], s.colorMask[1], s.colorMask[2], s.colorMask[3]);
    } else {
      setEnabled(gl, gl.BLEND, s.blend);
      gl.colorMask(s.colorMask[0], s.colorMask[1], s.colorMask[2], s.colorMask[3]);
    }
  }

  function internalFormatOf(gl, format) {
    switch (format) {
      case "rgba16f": return gl.RGBA16F;
      case "r8": return gl.R8;
      case "rg8": return gl.RG8;
      default: return gl.RGBA8;
    }
  }

  function createTarget(gl, width, height, format) {
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texStorage2D(gl.TEXTURE_2D, 1, internalFormatOf(gl, format), width, height);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    const status = gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER);
    if (status !== gl.FRAMEBUFFER_COMPLETE) {
      gl.deleteFramebuffer(framebuffer);
      gl.deleteTexture(texture);
      throw new Error("Gaius quality target " + format + " " + width + "x" + height
          + " is incomplete (0x" + status.toString(16) + ")");
    }
    return {texture: texture, framebuffer: framebuffer, width: width, height: height, format: format};
  }

  function deleteTarget(gl, target) {
    if (!target) return;
    try {
      gl.deleteFramebuffer(target.framebuffer);
      gl.deleteTexture(target.texture);
    } catch (error) {
      // Lost context: nothing to free.
    }
  }

  // Named render targets that follow the frame size; reallocated only when the size or format
  // changes, so switching the world render scale never allocates.
  function TargetPool() {
    this.targets = new Map();
  }

  TargetPool.prototype.get = function (gl, name, width, height, format) {
    const w = Math.max(1, width | 0);
    const h = Math.max(1, height | 0);
    let target = this.targets.get(name);
    if (target && target.width === w && target.height === h && target.format === format) {
      return target;
    }
    deleteTarget(gl, target);
    target = createTarget(gl, w, h, format);
    this.targets.set(name, target);
    return target;
  };

  TargetPool.prototype.dispose = function (gl) {
    const targets = this.targets;
    targets.forEach(function (target) {
      deleteTarget(gl, target);
    });
    targets.clear();
  };

  TargetPool.prototype.forget = function () {
    this.targets.clear();
  };

  // Framebuffers wrapping textures the game owns (main colour, entity outline). Keyed by the
  // WebGLTexture object, so a texture BrowserOpenGL deletes and recreates gets a fresh wrapper.
  function ExternalFramebuffers() {
    this.map = new WeakMap();
  }

  ExternalFramebuffers.prototype.get = function (gl, texture) {
    let framebuffer = this.map.get(texture);
    if (framebuffer && gl.isFramebuffer(framebuffer)) return framebuffer;
    framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    if (gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
      gl.deleteFramebuffer(framebuffer);
      throw new Error("Gaius quality cannot render into the game texture");
    }
    this.map.set(texture, framebuffer);
    return framebuffer;
  };

  ExternalFramebuffers.prototype.forget = function () {
    this.map = new WeakMap();
  };

  function createSamplers(gl) {
    const make = function (filter) {
      const sampler = gl.createSampler();
      gl.samplerParameteri(sampler, gl.TEXTURE_MIN_FILTER, filter);
      gl.samplerParameteri(sampler, gl.TEXTURE_MAG_FILTER, filter);
      gl.samplerParameteri(sampler, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.samplerParameteri(sampler, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.samplerParameteri(sampler, gl.TEXTURE_COMPARE_MODE, gl.NONE);
      return sampler;
    };
    return {linear: make(gl.LINEAR), nearest: make(gl.NEAREST)};
  }

  function bindTexture(gl, unit, texture, sampler) {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.bindSampler(unit, sampler);
  }

  // Draws one full-screen triangle into framebuffer over the viewport rectangle.
  function draw(gl, framebuffer, x, y, width, height) {
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.viewport(x, y, width, height);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  function setRect(gl, program, texWidth, texHeight, rectWidth, rectHeight) {
    gl.uniform2f(program.uniform("uTexel"), 1 / texWidth, 1 / texHeight);
    gl.uniform2f(program.uniform("uClamp"),
        (rectWidth - 0.5) / texWidth, (rectHeight - 0.5) / texHeight);
  }

  Q.gl = Object.freeze({
    FULLSCREEN_VS: FULLSCREEN_VS,
    FRAGMENT_PROLOGUE: FRAGMENT_PROLOGUE,
    UNIT_COUNT: UNIT_COUNT,
    createProgram: createProgram,
    saveState: saveState,
    prepareState: prepareState,
    restoreState: restoreState,
    createTarget: createTarget,
    deleteTarget: deleteTarget,
    TargetPool: TargetPool,
    ExternalFramebuffers: ExternalFramebuffers,
    createSamplers: createSamplers,
    bindTexture: bindTexture,
    draw: draw,
    setRect: setRect
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
