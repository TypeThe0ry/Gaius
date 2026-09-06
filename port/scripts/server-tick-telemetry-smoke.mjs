import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(
  new URL("../src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java", import.meta.url),
  "utf8",
);

function extract(parameter, method) {
  const pattern = new RegExp(
    `@JSBody\\(params = "${parameter}", script = """([\\s\\S]*?)"""\\)\\s+private static native void ${method}`,
  );
  const match = source.match(pattern);
  assert.ok(match, `${method} JSBody was not found`);
  return vm.runInContext(`(function(${parameter}) {${match[1]}\n})`, context);
}

const context = {globalThis: {}, Number, Math};
vm.createContext(context);
const begin = extract("startedAt", "recordServerTickBegin");
const end = extract("endedAt", "recordServerTickEnd");

// Disabled is the release default and must not allocate or mutate telemetry.
begin.call(context, 10);
end.call(context, 15);
assert.deepEqual(context.globalThis, {});

context.globalThis.__gaiusServerTickTelemetryEnabled = true;
begin.call(context, 100);
end.call(context, 117.5);
begin.call(context, 120);
end.call(context, 125);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.schemaVersion, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickCount, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.lastTickIntervalMillis, 20);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.maxTickIntervalMillis, 20);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.lastTickDurationMillis, 5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.maxTickDurationMillis, 17.5);

// Malformed diagnostic state is fail-open and must not escape into server code.
context.globalThis.__gaiusServerTickTelemetry = null;
assert.doesNotThrow(() => begin("not-a-number"));
assert.doesNotThrow(() => end("not-a-number"));

console.log("SERVER_TICK_TELEMETRY_OK", JSON.stringify(context.globalThis.__gaiusServerTickTelemetry));
