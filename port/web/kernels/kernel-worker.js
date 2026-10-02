// Gaius kernel worker: hosts one instance of the kernel WebAssembly.Module and runs the jobs
// that kernel-pool.js posts to it. The pool starts this script from a Blob URL of its source,
// so it must stay a self-contained classic worker script with no imports.
//
// Kernel ABI (wasm32, little-endian):
//   memory                      exported linear memory
//   alloc(len) -> ptr           reserves len bytes for the job payload; 0 when out of memory
//   run_<kind>(ptr, len) -> hdr runs one kernel; hdr points at three u32: status, data_ptr,
//                               data_len. Status 0: data is the result bytes. Otherwise data is
//                               a UTF-8 message (it may be empty).
//   dealloc(ptr, len)           optional, releases the payload after the run
//   release(hdr)                optional, releases the result after it was copied out
//   reset()                     optional, called after every job (arena allocators)
//
// Messages in:  {type: "init", module | bytes}, {type: "job", id, kind, payload}, {type: "cancel", id}
// Messages out: {type: "ready", kinds}, {type: "init-error", message},
//               {type: "result", id, result, execMs}, {type: "error", id, code, status?, message},
//               {type: "cancelled", id}
(function (scope) {
  "use strict";

  const now = () => (scope.performance && scope.performance.now ? scope.performance.now() : Date.now());
  const queue = [];
  let module = null;
  let instance = null;
  let instancing = null;
  let draining = false;

  const post = (message, transfer) => scope.postMessage(message, transfer || []);
  const describe = (error) => (error && error.message ? error.message : String(error));

  // Between jobs the worker yields one task so cancel messages can reach the queue.
  const channel = typeof scope.MessageChannel === "function" ? new scope.MessageChannel() : null;
  const yieldWaiters = [];
  if (channel) channel.port1.onmessage = () => yieldWaiters.shift()();
  const yieldTurn = () => new Promise((resolve) => {
    if (channel) {
      yieldWaiters.push(resolve);
      channel.port2.postMessage(0);
    } else {
      setTimeout(resolve, 0);
    }
  });

  function decodeText(bytes) {
    if (typeof scope.TextDecoder === "function") return new scope.TextDecoder().decode(bytes);
    let text = "";
    for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
    return text;
  }

  // Kernels are built without imports; anything the module does import fails loudly when called.
  function buildImports(wasmModule) {
    const imports = {};
    for (const entry of WebAssembly.Module.imports(wasmModule)) {
      if (entry.kind !== "function") throw new Error(`unsupported kernel import ${entry.module}.${entry.name} (${entry.kind})`);
      const space = imports[entry.module] || (imports[entry.module] = {});
      space[entry.name] = () => {
        throw new Error(`kernel called unsupported import ${entry.module}.${entry.name}`);
      };
    }
    return imports;
  }

  async function instantiate() {
    const created = await WebAssembly.instantiate(module, buildImports(module));
    const exports = created.exports;
    if (!exports.memory || typeof exports.alloc !== "function") {
      throw new Error("kernel module must export memory and alloc");
    }
    instance = created;
  }

  function ensureInstance() {
    if (instance !== null) return Promise.resolve();
    if (instancing === null) instancing = instantiate().finally(() => { instancing = null; });
    return instancing;
  }

  function kernelKinds() {
    const kinds = [];
    for (const entry of WebAssembly.Module.exports(module)) {
      if (entry.kind === "function" && entry.name.startsWith("run_")) kinds.push(entry.name.slice(4));
    }
    return kinds;
  }

  async function init(message) {
    try {
      if (message.module) module = message.module;
      else if (message.bytes) module = await WebAssembly.compile(message.bytes);
      else throw new Error("init message carries no kernel module");
      await ensureInstance();
      post({type: "ready", kinds: kernelKinds()});
      scheduleDrain();
    } catch (error) {
      post({type: "init-error", message: describe(error)});
    }
  }

  function runJob(job) {
    const exports = instance.exports;
    const entry = exports["run_" + job.kind];
    if (typeof entry !== "function") {
      post({type: "error", id: job.id, code: "unknown-kind", message: `kernel module has no export run_${job.kind}`});
      return;
    }
    const started = now();
    try {
      const input = new Uint8Array(job.payload);
      const length = input.byteLength;
      const ptr = exports.alloc(length) >>> 0;
      // A null pointer means memory.grow failed. The memory from address 0 holds the module's
      // stack, statics and allocator state, so writing the payload there would corrupt the
      // instance: treat it like a trap, which drops the instance.
      if (ptr === 0 && length > 0) {
        throw new Error(`kernel ${job.kind} is out of memory (cannot allocate a ${length} byte payload)`);
      }
      // alloc may grow memory, which replaces memory.buffer: always read it after the call.
      new Uint8Array(exports.memory.buffer, ptr, length).set(input);
      job.payload = null;
      const header = entry(ptr, length) >>> 0;
      // A null descriptor means the kernel could not allocate its result: treat it like a trap.
      if (header === 0) throw new Error(`kernel ${job.kind} returned no result (out of memory)`);
      const view = new DataView(exports.memory.buffer);
      const status = view.getUint32(header, true);
      const dataPtr = view.getUint32(header + 4, true);
      const dataLength = view.getUint32(header + 8, true);
      const bytes = new Uint8Array(exports.memory.buffer, dataPtr, dataLength).slice();
      if (typeof exports.release === "function") exports.release(header);
      if (typeof exports.dealloc === "function") exports.dealloc(ptr, length);
      if (typeof exports.reset === "function") exports.reset();
      const execMs = now() - started;
      if (status === 0) {
        post({type: "result", id: job.id, result: bytes.buffer, execMs}, [bytes.buffer]);
      } else {
        const message = decodeText(bytes) || `kernel ${job.kind} failed with status ${status}`;
        post({type: "error", id: job.id, code: "kernel-error", status, message, execMs});
      }
    } catch (error) {
      // A trap leaves the instance (allocator, statics) in an unknown state: drop it and
      // instantiate the same module again before the next job.
      instance = null;
      post({type: "error", id: job.id, code: "kernel-trap", message: describe(error), execMs: now() - started});
    }
  }

  function scheduleDrain() {
    if (draining || queue.length === 0 || module === null) return;
    draining = true;
    yieldTurn().then(drain);
  }

  async function drain() {
    try {
      while (queue.length > 0) {
        if (instance === null) await ensureInstance();
        runJob(queue.shift());
        if (queue.length > 0) await yieldTurn();
      }
    } catch (error) {
      // The module no longer instantiates: let the pool replace this worker.
      post({type: "init-error", message: describe(error)});
    } finally {
      draining = false;
    }
  }

  scope.onmessage = (event) => {
    const message = event.data;
    if (!message) return;
    if (message.type === "init") {
      init(message);
    } else if (message.type === "job") {
      queue.push(message);
      scheduleDrain();
    } else if (message.type === "cancel") {
      const index = queue.findIndex((job) => job.id === message.id);
      if (index >= 0) {
        queue.splice(index, 1);
        post({type: "cancelled", id: message.id});
      }
    }
  };
})(self);
