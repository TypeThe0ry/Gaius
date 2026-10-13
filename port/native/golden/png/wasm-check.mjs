import { readFileSync } from "node:fs";
import { runInThisContext } from "node:vm";
import { performance } from "node:perf_hooks";
import assert from "node:assert/strict";

const [wasmPath, manifestPath, roundsText] = process.argv.slice(2);
const rounds = Number(roundsText);
assert.ok(Number.isInteger(rounds) && rounds > 0, "positive rounds required");
const bridge = new URL("../../../web/runtime/wasmgc/png-kernel.js", import.meta.url);
runInThisContext(readFileSync(bridge, "utf8"), { filename: bridge.pathname });
const kernel = globalThis.GaiusPngKernel;
assert.equal(kernel.enabled, false);
assert.equal(await kernel.initialize(), false, "off must not fetch or instantiate");
kernel.setEnabled(true);
assert.equal(await kernel.initialize({ bytes: readFileSync(wasmPath) }), true, kernel.lastError);
const inputs = readFileSync(manifestPath, "utf8").trim().split(/\r?\n/).map(line => {
  const [path, desired, expectedPath] = line.split("\t");
  const encoded = new Uint8Array(readFileSync(path));
  const expected = new Uint8Array(readFileSync(expectedPath));
  const actual = kernel.decode(encoded, Number(desired));
  assert.deepEqual(actual, expected, path + " desired=" + desired);
  return { encoded, desired: Number(desired), expected };
});
assert.equal(kernel.decode(new Uint8Array(32), 4), null);
assert.equal(kernel.decode(inputs[0].encoded, 5), null);
const interlaced = inputs[0].encoded.slice();
interlaced[28] = 1;
assert.equal(kernel.decode(interlaced, 4), null, "current Java rejects Adam7");
kernel.setEnabled(false);
assert.equal(kernel.decode(inputs[0].encoded, 4), null);
kernel.setEnabled(true);
for (let warmup = 0; warmup < 2; warmup++) {
  for (const input of inputs) assert.ok(kernel.decode(input.encoded, input.desired));
}
const started = performance.now();
for (let round = 0; round < rounds; round++) {
  for (const input of inputs) assert.ok(kernel.decode(input.encoded, input.desired));
}
const totalMs = performance.now() - started;
console.log(JSON.stringify({ count: inputs.length,
  bytes: inputs.reduce((sum, input) => sum + input.encoded.length, 0),
  outputBytes: inputs.reduce((sum, input) => sum + input.expected.length - 16, 0),
  rounds, totalMs, msPerRound: totalMs / rounds, parity: true, metrics: kernel.metrics }));
