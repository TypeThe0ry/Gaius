// Gaius shader translation job codec: frames run_shader_xlat jobs for the gaius-shaderxlat wasm
// module (port/native/crates/gaius-shaderxlat/wasm) and reads its results. The layout mirrors
// port/native/crates/gaius-kernel-abi (job/result headers) and the wasm crate's payload; all
// integers are little-endian.
//
// A plain script (installs globalThis.GaiusShaderXlatJob) so it loads from a <script> tag, a
// worker or a Node import alike:
//
//   const xlat = GaiusShaderXlatJob.instantiateSync(module);   // main thread, finishCompile
//   const {stage, bindings, wgsl} = xlat.translate(spirvBytes, [[uniformIndex, "R8_SINT"]], {flipVertexY});
//
// translate() throws GaiusShaderXlatJob.TranslateError (with .status) when the module refuses
// the SPIR-V; the message names the failing step (spirv patch, naga spv-in, validation, wgsl-out).
(function (global) {
  "use strict";

  var KIND = 0x0501;
  var ABI_VERSION = 1;
  var JOB_MAGIC = 0x424a4b47; // "GKJB"
  var RESULT_MAGIC = 0x53524b47; // "GKRS"
  var HEADER = 16;
  var FORMATS = {R8_SINT: 0, R8_UINT: 1, R16_SINT: 2, R16_UINT: 3, R32_SINT: 4, R32_UINT: 5, R32_FLOAT: 6};
  var DIMS = ["1d", "2d", "2d-array", "3d", "cube", "cube-array"];
  var SAMPLE_KINDS = ["float", "sint", "uint", "depth"];
  var KINDS = ["uniform", "storage", "texture", "sampler"];

  function TranslateError(status, message) {
    var error = new Error(message);
    error.name = "TranslateError";
    error.status = status;
    return error;
  }

  // texelBuffers: [[uniform index, renderpearl GpuFormat name], ...]; options.flipVertexY negates
  // the vertex position's y (see the crate README).
  function encode(spirv, texelBuffers, jobId, options) {
    var buffers = texelBuffers || [];
    var payloadLen = 8 + buffers.length * 8 + spirv.byteLength;
    var bytes = new Uint8Array(HEADER + payloadLen);
    var view = new DataView(bytes.buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, KIND, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLen, true);
    view.setUint32(16, options && options.flipVertexY ? 1 : 0, true);
    view.setUint32(20, buffers.length, true);
    for (var i = 0; i < buffers.length; i++) {
      var code = FORMATS[buffers[i][1]];
      if (code === undefined) throw new TypeError("unsupported texel buffer format " + buffers[i][1]);
      view.setUint32(24 + i * 8, buffers[i][0] >>> 0, true);
      view.setUint32(28 + i * 8, code, true);
    }
    bytes.set(new Uint8Array(spirv.buffer || spirv, spirv.byteOffset || 0, spirv.byteLength), 24 + buffers.length * 8);
    return bytes;
  }

  // data: the descriptor's data bytes of a successful run (result header + payload).
  function decode(data, jobId) {
    var view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    if (view.getUint32(0, true) !== RESULT_MAGIC || view.getUint16(4, true) !== ABI_VERSION) {
      throw TranslateError(-1, "bad shaderxlat result header");
    }
    if (jobId !== undefined && view.getUint32(8, true) !== (jobId >>> 0)) {
      throw TranslateError(-1, "shaderxlat result for another job");
    }
    var at = HEADER;
    var stage = view.getUint32(at, true) === 1 ? "fragment" : "vertex";
    var count = view.getUint32(at + 4, true);
    at += 8;
    var bindings = [];
    for (var i = 0; i < count; i++, at += 32) {
      var w = function (k) { return view.getUint32(at + k * 4, true); };
      var kind = KINDS[w(3)];
      var binding = {group: w(0), binding: w(1), uniform: w(2), type: kind, used: w(7) === 1};
      if (kind === "uniform") binding.size = w(4);
      if (kind === "texture") {
        binding.dim = DIMS[w(4)];
        binding.sampleKind = SAMPLE_KINDS[w(5)];
        binding.multisampled = (w(6) & 1) !== 0;
        binding.sampled = (w(6) & 2) !== 0;
      }
      if (kind === "sampler") binding.comparison = w(4) === 1;
      bindings.push(binding);
    }
    var length = view.getUint32(at, true);
    var wgsl = new TextDecoder().decode(data.subarray(at + 4, at + 4 + length));
    return {stage: stage, bindings: bindings, wgsl: wgsl};
  }

  // Runs jobs on one instance in the calling thread.
  function fromInstance(instance) {
    var ex = instance.exports;
    var nextId = 1;
    return {
      translate: function (spirv, texelBuffers, options) {
        var jobId = nextId++;
        var job = encode(spirv, texelBuffers, jobId, options);
        var ptr = ex.alloc(job.length);
        if (!ptr) throw TranslateError(6, "shaderxlat: out of memory");
        new Uint8Array(ex.memory.buffer, ptr, job.length).set(job);
        var desc = ex.run_shader_xlat(ptr, job.length);
        ex.dealloc(ptr, job.length);
        if (!desc) throw TranslateError(6, "shaderxlat: out of memory");
        try {
          var head = new DataView(ex.memory.buffer, desc, 16);
          var status = head.getUint32(0, true);
          var data = new Uint8Array(ex.memory.buffer, head.getUint32(4, true), head.getUint32(8, true));
          if (status !== 0) throw TranslateError(status, new TextDecoder().decode(data));
          return decode(data.slice(), jobId);
        } finally {
          ex.release(desc);
        }
      }
    };
  }

  function instantiateSync(module) {
    return fromInstance(new WebAssembly.Instance(module, {}));
  }

  global.GaiusShaderXlatJob = {
    KIND: KIND,
    FORMATS: FORMATS,
    TranslateError: TranslateError,
    encode: encode,
    decode: decode,
    fromInstance: fromInstance,
    instantiateSync: instantiateSync
  };
})(typeof globalThis !== "undefined" ? globalThis : self);
