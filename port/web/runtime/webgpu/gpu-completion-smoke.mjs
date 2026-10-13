import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");

function fixture() {
  const context = vm.createContext({ console, performance, Uint8Array, Int8Array });
  // Test-only seam: install a fake pass without exposing production state or APIs.
  const testSource = source.replace("  root.GaiusWebGpu = G;\n", "  G.__testSetIndirectPass=function(p){pass=p;currentPipelineValid=true;};\n  G.__testPipeline=function(label){return put({label:label,pipeline:{},valid:true});};\n  root.GaiusWebGpu = G;\n");
  vm.runInContext(testSource, context, { filename: "gpu-glue.js" });
  const gpu = context.GaiusWebGpu;
  const completions = [];
  const buffers = [];
  const copies = [];
  gpu.queue = {
    submit() {},
    onSubmittedWorkDone() {
      const completion = deferred();
      completions.push(completion);
      return completion.promise;
    }
  };
  gpu.device = {
    createTexture(descriptor) { return { ...descriptor, format: descriptor.format }; },
    createCommandEncoder() {
      return {
        copyTextureToBuffer(...args) { copies.push(args); },
        finish() { return {}; }
      };
    },
    createBuffer(descriptor) {
      const mapped = deferred();
      const buffer = {
        bytes: new Uint8Array(descriptor.size),
        mapped,
        unmapped: false,
        destroyed: false,
        mapAsync() { return mapped.promise; },
        getMappedRange() { return this.bytes.buffer; },
        unmap() { this.unmapped = true; },
        destroy() { this.destroyed = true; }
      };
      buffers.push(buffer);
      return buffer;
    }
  };
  return { gpu, context, completions, buffers, copies };
}

{
  const { gpu, context } = fixture();
  const calls = [];
  gpu.__testSetIndirectPass({setPipeline(p) { calls.push("pipeline"); },
    drawIndexed() { calls.push("draw"); }});
  context.__gaiusCloudsEnabled = false;
  context.__gaiusCapture = {optionsObserved: {cloudsOff: true}};
  gpu.setPipeline(gpu.__testPipeline("minecraft:pipeline/clouds"));
  gpu.drawIndexed(6, 1, 0, 0, 0);
  assert.deepEqual(calls, ["pipeline", "draw"],
    "the backend executes the vanilla draw stream regardless of capture metadata or label");
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

{
  const { gpu } = fixture();
  const calls = [];
  const fakePass = { drawIndexedIndirect(buffer, offset) { calls.push({buffer, offset}); } };
  const commandBuffer = gpu.createBuffer(128, 0x100, "indirect-test");
  gpu.__testSetIndirectPass(fakePass);
  gpu.drawIndexedIndirect(commandBuffer, 12, 3);
  assert.deepEqual(calls.map(call => call.offset), [12, 32, 52]);
  assert.equal(calls.every(call => call.buffer === gpu.debugObject(commandBuffer)), true);
  assert.equal(gpu.stats.indirectDraws, 3);
  assert.equal(gpu.stats.indirectBatches, 1);
  assert.equal(gpu.stats.draws, 3);
}

{
  const { gpu, completions } = fixture();
  const first = gpu.createFence();
  const sameSubmit = gpu.createFence();
  assert.equal(gpu.fenceStatus(first), 0);
  assert.equal(completions.length, 0);
  gpu.submit();
  assert.equal(completions.length, 1);
  assert.equal(gpu.fenceStatus(first), 0);
  const later = gpu.createFence();
  completions[0].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(first), 1);
  assert.equal(gpu.fenceStatus(sameSubmit), 1);
  assert.equal(gpu.fenceStatus(later), 0);
  gpu.submit();
  assert.equal(completions.length, 2);
  completions[1].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(later), 1);
  gpu.closeFence(first);
  gpu.closeFence(first);
  assert.equal(gpu.fenceStatus(first), -1);
}

{
  const { gpu, completions } = fixture();
  const rejected = gpu.createFence();
  gpu.submit();
  completions[0].reject(new Error("completion rejected"));
  await flush();
  assert.equal(gpu.fenceStatus(rejected), -1);
  const lost = gpu.createFence();
  gpu.submit();
  gpu.lostReason = "device lost";
  completions[1].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(lost), -1);
}

{
  const { gpu, buffers, copies } = fixture();
  const texture = gpu.createTexture(3, 2, 1, 1, "rgba8unorm", 1, "packing");
  const readback = gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, 4);
  assert.equal(gpu.readbackPoll(), 0);
  const staging = buffers[0];
  const expected = Uint8Array.from({ length: 24 }, (_, index) => index + 1);
  staging.bytes.fill(255);
  staging.bytes.set(expected.subarray(0, 12), 0);
  staging.bytes.set(expected.subarray(12), 256);
  assert.equal(copies[0][1].bytesPerRow, 256);
  gpu.submit();
  assert.equal(gpu.readbackPoll(), 0);
  staging.mapped.resolve();
  await flush();
  assert.equal(gpu.readbackPoll(), readback);
  const target = new Int8Array(new ArrayBuffer(40), 8, 24);
  assert.equal(gpu.readbackFetch(readback, target), 24);
  assert.deepEqual(new Uint8Array(target.buffer, 8, 24), expected);
  assert.deepEqual(new Uint8Array(target.buffer, 0, 8), new Uint8Array(8));
  assert.equal(staging.unmapped, true);
  assert.equal(staging.destroyed, true);
  assert.equal(gpu.readbackFetch(readback, target), -1);
  assert.equal(gpu.readbackPoll(), 0);
  const failed = gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, 4);
  gpu.submit();
  buffers[1].mapped.reject(new Error("mapping rejected"));
  await flush();
  assert.equal(gpu.readbackPoll(), failed);
  const unchanged = new Int8Array(24).fill(37);
  assert.equal(gpu.readbackFetch(failed, unchanged), -1);
  assert.ok(unchanged.every(value => value === 37));
  assert.equal(gpu.stats.readbackFailures, 1);
  assert.equal(buffers[1].destroyed, true);
}

console.log("WebGPU completion smoke passed: fences, rejection, device loss, packed readback, failed mapping");
