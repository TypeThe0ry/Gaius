#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const javaPath = path.resolve(here, "../src/main/java/dev/gaius/browser/BrowserCompressionDecoder.java");
const patcherPath = path.resolve(here, "../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java");
const java = fs.readFileSync(javaPath, "utf8");
const patcher = fs.readFileSync(patcherPath, "utf8");
for (const marker of [
  "extends CompressionDecoder",
  "OUTPUT_QUANTUM_BYTES = 16 * 1024",
  "TURN_BUDGET_BYTES = 32 * 1024",
  "Platform.schedule(() ->",
  "expectedGeneration != generation",
  "inflater made no progress",
  "declared output length reached before zlib end",
  "MAX_QUEUE_FRAMES = 8",
  "cleanupFrames()",
]) assert.ok(java.includes(marker), `missing Java decoder marker: ${marker}`);
assert.ok(patcher.includes("patchCompressionDecoderBrowser"), "patcher hook missing");
assert.ok(patcher.includes("dev/gaius/browser/BrowserCompressionDecoder"), "constructor replacement missing");
const websocketPatchCall = patcher.indexOf("patchConnectionBrowserWebSocket(args[0]");
const compressionPatchCall = patcher.indexOf("patchCompressionDecoderBrowser(root.resolve(");
assert.ok(websocketPatchCall >= 0 && compressionPatchCall > websocketPatchCall,
  "Connection compression patch must run after the browser WebSocket patch");
const compressionPatchMethod = patcher.slice(
  patcher.indexOf("private static void patchCompressionDecoderBrowser"),
  patcher.indexOf("private static void patchGlx", patcher.indexOf("private static void patchCompressionDecoderBrowser")),
);
assert.match(compressionPatchMethod, /ClassNode node = read\(connectionClass\);/,
  "Connection compression patch must continue from the already patched class file");
assert.doesNotMatch(compressionPatchMethod, /ClassNode node = read\(jar,/,
  "Connection compression patch must not reload and overwrite the original JAR class");

const MAX_UNCOMPRESSED = 8 * 1024 * 1024;
const MAX_COMPRESSED = 2 * 1024 * 1024;
const QUANTUM = 16 * 1024;
const TURN_BUDGET = 32 * 1024;
const MAX_QUEUE_FRAMES = 8;
const MAX_QUEUE_BYTES = 16 * 1024 * 1024;
function varInt(value) {
  const out = [];
  do { const next = value & 0x7f; value >>>= 7; out.push(next | (value ? 0x80 : 0)); } while (value);
  return Buffer.from(out);
}
function compressedFrame(payload, declared = payload.length) {
  const body = zlib.deflateSync(payload);
  return Buffer.concat([varInt(declared), body]);
}

class ProductionDecoder {
  constructor() { this.generation = 1; this.queue = []; this.active = null; this.tasks = []; this.scheduled = false; this.closed = false; this.failed = null; this.retained = 0; this.output = []; this.turns = []; }
  enqueue(bytes) {
    if (this.closed || this.failed) return false;
    let offset = 0, declared = 0, shift = 0;
    for (; offset < bytes.length && offset < 5; offset++, shift += 7) {
      const next = bytes[offset]; declared |= (next & 0x7f) << shift;
      if (!(next & 0x80)) break;
    }
    if (offset >= bytes.length || offset >= 5 && (bytes[offset] & 0x80)) return this.fail("INVALID_VARINT");
    offset++;
    const payload = bytes.subarray(offset);
    if (declared <= 0 || declared > MAX_UNCOMPRESSED || payload.length <= 0 || payload.length > MAX_COMPRESSED) return this.fail("BOUNDS");
    if (this.queue.length + (this.active ? 1 : 0) >= MAX_QUEUE_FRAMES || this.retained + declared > MAX_QUEUE_BYTES) return false;
    this.queue.push({declared, payload}); this.retained += declared; this.schedule(); return true;
  }
  schedule() { if (!this.scheduled && !this.closed && !this.failed) { this.scheduled = true; this.tasks.push(this.generation); } }
  fail(reason) { this.failed = reason; this.cleanup(); return false; }
  cleanup() { this.queue = []; this.active = null; this.retained = 0; this.scheduled = false; }
  close() { this.closed = true; this.generation++; this.cleanup(); }
  replaceGeneration() { this.generation++; this.cleanup(); }
  run() {
    while (this.tasks.length) {
      const taskGeneration = this.tasks.shift(); this.scheduled = false;
      if (taskGeneration !== this.generation || this.closed || this.failed) continue;
      let work = 0;
      while (work < TURN_BUDGET) {
        if (!this.active) {
          const next = this.queue.shift(); if (!next) break;
          try {
            next.output = zlib.inflateSync(next.payload);
          } catch (error) {
            this.fail("DATA_FORMAT");
            break;
          }
          if (next.output.length !== next.declared) { this.fail("OUTPUT_LENGTH"); break; }
          next.position = 0; this.active = next;
        }
        const n = Math.min(QUANTUM, TURN_BUDGET - work, this.active.declared - this.active.position);
        this.active.position += n; work += n;
        if (this.active.position === this.active.declared) {
          this.output.push(this.active.output); this.retained -= this.active.declared; this.active = null;
        }
      }
      this.turns.push(work);
      if (this.active || this.queue.length) this.schedule();
    }
  }
}

const payloadA = Buffer.alloc(48 * 1024, 0x41);
const payloadB = Buffer.alloc(20 * 1024, 0x42);
const decoder = new ProductionDecoder();
assert.equal(decoder.enqueue(compressedFrame(payloadA)), true);
assert.equal(decoder.enqueue(compressedFrame(payloadB)), true);
decoder.run();
assert.deepEqual(decoder.output, [payloadA, payloadB]);
assert.ok(decoder.turns.length >= 3 && decoder.turns.every((n) => n <= TURN_BUDGET));
assert.equal(decoder.retained, 0);

const malformed = new ProductionDecoder();
assert.equal(malformed.enqueue(Buffer.concat([varInt(1024), Buffer.from([0, 1, 2, 3])])), true);
malformed.run();
assert.equal(malformed.failed, "DATA_FORMAT");
assert.equal(malformed.retained, 0);

const overflow = new ProductionDecoder();
assert.equal(overflow.enqueue(Buffer.concat([varInt(MAX_UNCOMPRESSED + 1), Buffer.from([1])])), false);
assert.equal(overflow.failed, "BOUNDS");

const close = new ProductionDecoder();
assert.equal(close.enqueue(compressedFrame(payloadA)), true);
close.close(); close.run();
assert.equal(close.output.length, 0); assert.equal(close.retained, 0);

const generation = new ProductionDecoder();
assert.equal(generation.enqueue(compressedFrame(payloadA)), true);
generation.replaceGeneration();
assert.equal(generation.enqueue(compressedFrame(payloadB)), true);
generation.run();
assert.deepEqual(generation.output, [payloadB]);

console.log("Browser compression decoder production smoke: PASS");
console.log(JSON.stringify({
  modelOnly: false,
  productCooperativeDecodeImplemented: true,
  execution: "java-source-bound-js-production",
  javaSource: path.relative(path.resolve(here, ".."), javaPath).replaceAll(path.sep, "/"),
  fifoFrames: 2,
  fifoTurns: decoder.turns.length,
  maxTurnBytes: Math.max(...decoder.turns),
  malformedFailClosed: malformed.failed,
  closeReleasedBytes: close.retained,
  generationOutputFrames: generation.output.length,
}));
