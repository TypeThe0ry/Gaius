#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const source = await readFile(
  `${root}/port/src/main/java/dev/gaius/browser/BrowserCooperativeExecutor.java`,
  "utf8",
);

assert.match(source, /import org\.teavm\.jso\.JSBody;/,
  "executor must be able to detect the integrated-server Worker");
assert.match(source, /if \(isWorkerRuntime\(\)\) \{\s*return delegate;/s,
  "integrated-server Worker must bypass the second cooperative queue");
assert.match(source,
  /@JSBody\(script = "return typeof WorkerGlobalScope[^\n]+/,
  "Worker runtime probe is missing");
assert.match(source, /return new BrowserCooperativeExecutor\(delegate\);/,
  "foreground callers must retain the cooperative executor");

console.log(JSON.stringify({ok: true, workerBypass: true, foregroundCooperative: true}));
