// Singleplayer-world acceptance (migration milestone M4) for a Gaius browser client in headless Chrome.
//
//   DIST=port/target/26.3/<dist> OUT=out node port/scripts/minecraft-263-singleplayer-cdp.mjs   (served on 127.0.0.1)
//   ARTIFACT=path/to/Gaius-26.2.html OUT=out node port/scripts/minecraft-263-singleplayer-cdp.mjs (portable, file://)
//
// Flow: boot -> title -> Singleplayer -> Create New World (creative, fixed SEED) -> level + player ->
// terrain timings (first loaded chunk, first rendered terrain, chunk growth, section audit) ->
// /time set 6000 with the daylight cycle frozen -> spawn and top-down screenshots -> move (W) ->
// break and place a block under the player (verified through the hit-result telemetry) ->
// pause menu (LAN button) -> Save and Quit -> re-enter the world from the world list -> chunks
// reload, player position and the placed block persisted, top-down screenshot within tolerance.
// Evidence goes to OUT (report.json, console.log with page and Worker console, screenshots).
// Env: SEED (default 26300), PROFILE (1.21.11|26.2|26.3, recorded in the report),
// RUNTIME=js|wasm (or GAIUS_RUNTIME / --runtime; QUERY runtime overrides it),
// BOOT_TIMEOUT_MS (900000), WORLD_TIMEOUT_MS (420000), SETTLE_S (60), PLAYER_NAME (M4Check),
// REENTER=same|reload (reload the page before re-entering the world; the portable 26.2 page
// transfers its embedded Worker payload once and cannot start a second Worker in one session),
// REENTER_PAGE_URL (with REENTER=reload: navigate to this page instead, e.g. another build of the
// same profile on the same origin, which shares the browser storage; storage-compat-gate.mjs),
// WORKER_EXC_DEBUG=1 (pause the Worker on every exception and record the JS call stack plus
// source context of native TypeErrors into report.workerExceptions; slows the Worker down).
import {spawn, execFileSync} from "node:child_process";
import {createReadStream, statSync, existsSync, readFileSync} from "node:fs";
import {mkdtemp, mkdir, writeFile, rm, appendFile, readFile} from "node:fs/promises";
import {createServer as netServer} from "node:net";
import {createServer as httpServer} from "node:http";
import {chromeIsolationArgs, RUNTIME_TELEMETRY_EXPRESSION, runtimeArgument, runtimePageUrl, runtimeSelectionMatches, isShaderConsoleError} from "./bench/harness-common.mjs";
import {GL_FRAME_GUARD_SOURCE, GL_FRAME_GUARD_WINDOW_EXPRESSION, glFrameGuardFailures} from "../../tools/gl-frame-guard.mjs";
import {configureWorldSeed} from "../../tools/configure-browser-world-seed.mjs";
import {CAPTURE_BOOT_SOURCE, CAPTURE_OPTIONS_EXPRESSION, captureDeterministicWorld, stageAnimationCapture, freezeCaptureWorld} from "../../tools/deterministic-world-capture.mjs";
import {resolve, join, extname, normalize, relative, isAbsolute} from "node:path";
import {pathToFileURL} from "node:url";

const out = resolve(process.env.OUT || "m4-out");
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const bootTimeout = Number(process.env.BOOT_TIMEOUT_MS || 900000);
const worldTimeout = Number(process.env.WORLD_TIMEOUT_MS || 420000);
const settleMs = Number(process.env.SETTLE_S || 60) * 1000;
const seed = String(process.env.SEED || "26300");
let clientIdentity = null;
if (process.env.DIST) {
  const root = resolve(process.env.DIST);
  const identityPath = join(root, "classes.js.build.json");
  if (existsSync(identityPath)) clientIdentity = JSON.parse(await readFile(identityPath, "utf8"));
  else {
    const sitePath = join(root, "gaius-site.json");
    if (existsSync(sitePath)) clientIdentity = {profile: {id: JSON.parse(await readFile(sitePath, "utf8")).profile}};
  }
}
const profile = process.env.PROFILE || clientIdentity?.profile.id;
if (!profile) throw new Error("PROFILE or a DIST build identity is required");
if (clientIdentity && profile !== clientIdentity.profile.id) throw new Error("PROFILE does not match DIST build identity");
const playerName = process.env.PLAYER_NAME || "M4Check";
const reenterMode = process.env.REENTER || "same";
const reenterPageUrl = process.env.REENTER_PAGE_URL || null;
const workerExcDebug = process.env.WORKER_EXC_DEBUG === "1";
const bypassServiceWorker = process.env.BYPASS_SW === "1";
const captureMode = process.env.CAPTURE_MODE === "1";
const stageCapture = process.env.CAPTURE_STAGE_JS === "1";
const requestedRuntime = runtimeArgument(process.argv, {
  ...process.env,
  GAIUS_RUNTIME: process.env.RUNTIME || process.env.GAIUS_RUNTIME,
});
const queryRuntime = new URLSearchParams(process.env.QUERY || "").get("runtime");
const effectiveRequestedRuntime = queryRuntime || requestedRuntime;
const captureCameras = process.env.CAPTURE_CAMERAS ? JSON.parse(await readFile(process.env.CAPTURE_CAMERAS, "utf8")) : null;
const worldgenBench = process.env.WORLDGEN_BENCH === "1";
const perfCapture = process.env.PERF_CAPTURE === "1";
const perfDurationMs = Math.max(1000, Number(process.env.PERF_DURATION_MS || 20000));
console.error(`[perf-capture-config] PERF_CAPTURE=${process.env.PERF_CAPTURE ?? "<unset>"} PERF_DURATION_MS=${process.env.PERF_DURATION_MS ?? "<unset>"} enabled=${perfCapture} durationMs=${perfDurationMs}`);
const sleep = ms => new Promise(r => setTimeout(r, ms));
await mkdir(out, {recursive: true});
const logFile = join(out, "console.log");
await writeFile(logFile, "");
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(7);
const logLines = [];
async function log(kind, text) {
  const line = `${stamp()} ${kind} ${text}`;
  logLines.push(line);
  await appendFile(logFile, line + "\n");
}

const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
  ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};
let server = null, pageUrl = process.env.PAGE_URL || null;
let stageReceipt = null, stagedClasses = null;
  if (stageCapture && effectiveRequestedRuntime === "wasm") {
    throw new Error("CAPTURE_STAGE_JS is incompatible with WasmGC; use the native captureAnimationTick hook");
  }
  if (stageCapture) {
  if (!captureMode || !process.env.DIST || pageUrl) throw new Error("capture staging requires CAPTURE_MODE=1 and a local DIST");
  const root = resolve(process.env.DIST);
  let classesPath = join(root, "classes.js");
  if (!existsSync(classesPath)) {
    const site = JSON.parse(await readFile(join(root, "gaius-site.json"), "utf8"));
    classesPath = join(root, site.assets["classes.js"]);
  }
  const staged = stageAnimationCapture(await readFile(classesPath, "utf8"));
  stageReceipt = staged.receipt;
  stagedClasses = join(out, "capture-classes.js");
  await writeFile(stagedClasses, staged.source);
  await writeFile(join(out, "capture-staging.json"), JSON.stringify(stageReceipt, null, 2));
}
if (!pageUrl && process.env.DIST) {
  const root = resolve(process.env.DIST);
  let siteManifest = null;
  try { siteManifest = JSON.parse(readFileSync(join(root, "gaius-site.json"), "utf8")); } catch {}
  server = httpServer((req, res) => {
    const u = new URL(req.url, "http://127.0.0.1");
    log("http-request", `${req.method} ${u.pathname}${u.search} range=${req.headers.range || ""}`).catch(() => {});
    let p = normalize(join(root, decodeURIComponent(u.pathname)));
    // Path-relative containment: a plain prefix test would also accept a sibling
    // directory whose name merely starts with the root (e.g. <root>-other).
    const rel = relative(root, p);
    if (rel.startsWith("..") || isAbsolute(rel)) { res.writeHead(403); return res.end(); }
    if (u.pathname === "/") p = join(root, "index.html");
    const logical = u.pathname.replace(/^\//, "");
    if (siteManifest?.assets?.[logical]) p = join(root, siteManifest.assets[logical]);
    if (u.pathname === "/classes.js" && !existsSync(p)) {
      try { const site = JSON.parse(readFileSync(join(root, "gaius-site.json"), "utf8")); p = join(root, site.assets["classes.js"]); } catch {}
    }
    if (stagedClasses && u.pathname === "/classes.js") p = stagedClasses;
    if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); log("http404", u.pathname); return res.end(); }
    const size = statSync(p).size;
    const headers = {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": size,
      "Accept-Ranges": "none", "Cache-Control": "no-store", "Cross-Origin-Resource-Policy": "same-origin",
      "Cross-Origin-Embedder-Policy": "require-corp", "Cross-Origin-Opener-Policy": "same-origin"};
    // The launcher fetches the raw .gz bytes and decompresses them itself. Do not
    // advertise Content-Encoding, and answer Range requests with one complete 200
    // response so a retry cannot splice an incomplete asset into the cache.
    res.writeHead(200, headers);
    if (req.method === "HEAD") return res.end();
    const stream = createReadStream(p);
    stream.on("error", error => { log("http-error", `${u.pathname} ${error}`); res.destroy(error); });
    stream.on("close", () => { if (!res.writableFinished) log("http-aborted", `${u.pathname} ${size}`); });
    stream.pipe(res);
  });
  await new Promise(ok => server.listen(0, "127.0.0.1", ok));
  pageUrl = `http://127.0.0.1:${server.address().port}/index.html`;
}
if (!pageUrl) pageUrl = pathToFileURL(resolve(process.env.ARTIFACT)).href;
pageUrl = runtimePageUrl(pageUrl, requestedRuntime, process.env.QUERY || "");
const benchParams = [];
if (process.env.WORLDGEN_KERNEL_OFF === "1") benchParams.push("worldgenKernel=0");
if (worldgenBench) benchParams.push("gaiusServerTickTelemetry=1");
if (benchParams.length) pageUrl += (pageUrl.includes("?") ? "&" : "?") + benchParams.join("&");

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = new Map();
    this.ws.addEventListener("close", () => {
      for (const pending of this.pending.values()) pending.bad(new Error("CDP connection closed"));
      this.pending.clear();
    });
    this.ws.addEventListener("message", e => {
      const m = JSON.parse(e.data);
      if (m.id && this.pending.has(m.id)) {
        const {ok, bad} = this.pending.get(m.id); this.pending.delete(m.id);
        m.error ? bad(new Error(m.error.message)) : ok(m.result);
      } else if (m.method) {
        for (const fn of this.listeners.get(m.method) || []) fn(m.params || {}, m.sessionId);
      }
    });
  }
  on(method, fn) { if (!this.listeners.has(method)) this.listeners.set(method, []); this.listeners.get(method).push(fn); }
  open() { return new Promise((ok, bad) => { this.ws.addEventListener("open", ok); this.ws.addEventListener("error", bad); }); }
  send(method, params = {}, sessionId) {
    if (this.ws.readyState !== WebSocket.OPEN) return Promise.reject(new Error("CDP is not connected"));
    const id = this.id++;
    return new Promise((ok, bad) => {
      const timer = setTimeout(() => { this.pending.delete(id); bad(new Error(`CDP timeout: ${method}`)); }, Number(process.env.CDP_TIMEOUT_MS || 20000));
      this.pending.set(id, {ok: value => { clearTimeout(timer); ok(value); }, bad: error => { clearTimeout(timer); bad(error); }});
      this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    });
  }
}
async function evaluate(cdp, expression, sessionId) {
  const r = await cdp.send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true}, sessionId);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text || "evaluate failed");
  return r.result?.value;
}
async function waitFor(cdp, expr, ms, label) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { if (await evaluate(cdp, expr)) return true; } catch {} await sleep(250); }
  throw new Error(`timeout: ${label}`);
}
async function freePort() {
  const s = netServer(); await new Promise(ok => s.listen(0, "127.0.0.1", ok));
  const p = s.address().port; await new Promise(ok => s.close(ok)); return p;
}
async function mouse(cdp, type, x, y, button, buttons) {
  await cdp.send("Input.dispatchMouseEvent", {type, x, y, button, buttons, clickCount: type === "mouseMoved" ? 0 : 1});
}
async function clickAt(cdp, x, y, button = "left") {
  const bits = button === "left" ? 1 : button === "right" ? 2 : 4;
  await mouse(cdp, "mouseMoved", x, y, "none", 0); await sleep(50);
  await mouse(cdp, "mousePressed", x, y, button, bits); await sleep(80);
  await mouse(cdp, "mouseReleased", x, y, button, 0);
}
async function findWidget(cdp, match, ms = 30000) {
  const m = JSON.stringify(match); const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];const m=${m};
      const ok=x=>x&&x.visible!==false&&(m.inactive||x.active!==false);let w=null;
      if(m.type)w=ws.find(x=>ok(x)&&String(x.type||'').includes(m.type)&&(!m.text||String(x.text||'').toLowerCase().includes(String(m.text).toLowerCase())));
      else{const n=String(m.text).toLowerCase();w=ws.find(x=>ok(x)&&String(x.text||'').trim().toLowerCase()===n)||ws.find(x=>ok(x)&&String(x.text||'').toLowerCase().includes(n));}
      const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z||!z.width)return null;
      return {text:String(w.text||''),type:String(w.type||''),active:w.active!==false,focused:w.focused===true,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`).catch(() => null);
    if (v) return v; await sleep(250);
  }
  return null;
}
async function clickWidget(cdp, match, ms) {
  const w = await findWidget(cdp, typeof match === "string" ? {text: match} : match, ms);
  if (!w) throw new Error(`widget not found: ${JSON.stringify(match)}`);
  await clickAt(cdp, w.x, w.y); await log("click", `${w.text} (${w.type.split(".").pop()})`); return w;
}
const KEYS = {" ": ["Space", 32], "/": ["Slash", 191], "-": ["Minus", 189], ".": ["Period", 190], ",": ["Comma", 188], "~": ["Backquote", 192, 8],
  "@": ["Digit2", 50, 8], ":": ["Semicolon", 186, 8], "_": ["Minus", 189, 8], "!": ["Digit1", 49, 8], "'": ["Quote", 222], "[": ["BracketLeft", 219], "]": ["BracketRight", 221]};
function keyDef(ch) {
  if (/^[a-z]$/i.test(ch)) return {code: "Key" + ch.toUpperCase(), vk: ch.toUpperCase().charCodeAt(0), mod: ch !== ch.toLowerCase() ? 8 : 0};
  if (/^[0-9]$/.test(ch)) return {code: "Digit" + ch, vk: ch.charCodeAt(0), mod: 0};
  const k = KEYS[ch]; return k ? {code: k[0], vk: k[1], mod: k[2] || 0} : {code: "Unidentified", vk: 0, mod: 0};
}
async function keyEvent(cdp, type, keyName, code, vk, text, modifiers = 0) {
  await cdp.send("Input.dispatchKeyEvent", {type, key: keyName, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers, ...(text && type === "keyDown" ? {text, unmodifiedText: text} : {})});
}
async function key(cdp, keyName, code, vk, text, modifiers = 0) {
  await keyEvent(cdp, "keyDown", keyName, code, vk, text, modifiers); await sleep(30);
  await keyEvent(cdp, "keyUp", keyName, code, vk, "", modifiers);
}
async function typeText(cdp, text) {
  for (const ch of text) { const d = keyDef(ch); await key(cdp, ch, d.code, d.vk, ch, d.mod); await sleep(35); }
}
async function holdKey(cdp, keyName, code, vk, ms) {
  await keyEvent(cdp, "keyDown", keyName, code, vk, "");
  const end = Date.now() + ms;
  while (Date.now() < end) { await sleep(100); await cdp.send("Input.dispatchKeyEvent", {type: "keyDown", key: keyName, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, autoRepeat: true}); }
  await keyEvent(cdp, "keyUp", keyName, code, vk, "");
}
async function releaseInput(cdp) {
  for (const button of ["left", "middle", "right"]) await mouse(cdp, "mouseReleased", 0, 0, button, 0).catch(() => {});
  await evaluate(cdp, "(()=>{if(document.exitPointerLock)document.exitPointerLock();const s=window.getSelection&&window.getSelection();if(s)s.removeAllRanges();return true;})()").catch(() => {});
}
async function captureMainPerformance(cdp, state) {
  const safe = String(state).replace(/[^a-z0-9_-]+/gi, "-");
  await log("perf-entry", `${state} durationMs=${perfDurationMs}`);
  console.error(`[perf-capture-entry] ${state} durationMs=${perfDurationMs}`);
  const traceEvents = [];
  let tracingComplete = null;
  const traceListener = p => {
    if (p.value) traceEvents.push(...(Array.isArray(p.value) ? p.value : []));
    if (p.stream) tracingComplete = p.stream;
  };
  cdp.on("Tracing.dataCollected", traceListener);
  cdp.on("Tracing.tracingComplete", traceListener);
  await cdp.send("Profiler.enable");
  await cdp.send("Profiler.setSamplingInterval", {interval: 1000});
  await cdp.send("Tracing.start", {categories: "-*,devtools.timeline,v8.execute,v8.cpu_profiler,disabled-by-default-v8.cpu_profiler,disabled-by-default-gpu.service,disabled-by-default-devtools.timeline", options: "sampling-frequency=10000"});
  await cdp.send("Profiler.start");
  const started = Date.now();
  if (state === "flying") {
    await keyEvent(cdp, "keyDown", "w", "KeyW", 87, "");
    await keyEvent(cdp, "keyDown", " ", "Space", 32, "");
    while (Date.now() - started < perfDurationMs) await sleep(100);
    await keyEvent(cdp, "keyUp", " ", "Space", 32, "");
    await keyEvent(cdp, "keyUp", "w", "KeyW", 87, "");
  } else {
    await sleep(perfDurationMs);
  }
  const profile = await cdp.send("Profiler.stop");
  await cdp.send("Tracing.end");
  const end = Date.now() + 10000;
  while (!tracingComplete && Date.now() < end) await sleep(50);
  let trace = traceEvents;
  if (tracingComplete) {
    let text = "";
    while (true) {
      const chunk = await cdp.send("IO.read", {handle: tracingComplete});
      text += chunk.data || "";
      if (chunk.eof) break;
    }
    await cdp.send("IO.close", {handle: tracingComplete}).catch(() => {});
    try { trace = JSON.parse(text).traceEvents || trace; } catch {}
  }
  await writeFile(join(out, `${safe}.cpuprofile`), JSON.stringify(profile.profile));
  await log("perf-file", `${safe}.cpuprofile bytes=${(await statSync(join(out, `${safe}.cpuprofile`))).size}`);
  await writeFile(join(out, `${safe}.trace.json`), JSON.stringify({traceEvents: trace}));
  await log("perf-file", `${safe}.trace.json bytes=${(await statSync(join(out, `${safe}.trace.json`))).size}`);
  await log("perf-exit", `${state} samples=${profile.profile?.samples?.length || 0} traceEvents=${trace.length}`);
  console.error(`[perf-capture-exit] ${state} samples=${profile.profile?.samples?.length || 0} traceEvents=${trace.length}`);
  return {state, durationMs: Date.now() - started, samples: profile.profile?.samples?.length || 0, traceEvents: trace.length};
}
async function screenshot(cdp, name) {
  try {
    const {data} = await cdp.send("Page.captureScreenshot", {format: "png"});
    const path = join(out, name); await writeFile(path, Buffer.from(data, "base64")); return path;
  } catch (e) { await log("shot-fail", name + " " + e); return null; }
}
// Pixel metrics (PIL): fraction of sky-blue / near-black / other pixels in a crop, and a diff between two shots.
const PIXELS_PY = `
import json, sys
try:
    from PIL import Image, ImageChops
except Exception as e:
    print(json.dumps({"error": "PIL unavailable: %s" % e})); sys.exit(0)
mode = sys.argv[1]
if mode == "classify":
    img = Image.open(sys.argv[2]).convert("RGB"); w, h = img.size
    x0, y0, x1, y1 = [float(v) for v in sys.argv[3].split(",")]
    crop = img.crop((int(w*x0), int(h*y0), int(w*x1), int(h*y1))); px = crop.load(); cw, ch = crop.size
    sky = dark = other = n = 0
    for y in range(0, ch, 2):
        for x in range(0, cw, 2):
            r, g, b = px[x, y]; n += 1
            if b > 150 and b > r + 30 and b > g + 10 and g > 100: sky += 1
            elif r < 20 and g < 20 and b < 20: dark += 1
            else: other += 1
    print(json.dumps({"sky": round(sky/max(n,1), 4), "dark": round(dark/max(n,1), 4), "other": round(other/max(n,1), 4), "size": [w, h]}))
else:
    a = Image.open(sys.argv[2]).convert("RGB"); b = Image.open(sys.argv[3]).convert("RGB")
    if a.size != b.size: print(json.dumps({"error": "size mismatch", "a": a.size, "b": b.size})); sys.exit(0)
    w, h = a.size; x0, y0, x1, y1 = [float(v) for v in sys.argv[4].split(",")]
    box = (int(w*x0), int(h*y0), int(w*x1), int(h*y1)); a = a.crop(box); b = b.crop(box)
    d = ImageChops.difference(a, b).convert("L"); px = d.load(); cw, ch = d.size
    tot = n = big = 0
    for y in range(0, ch, 2):
        for x in range(0, cw, 2):
            v = px[x, y]; tot += v; n += 1
            if v > 48: big += 1
    print(json.dumps({"meanDiff": round(tot/max(n,1), 2), "changedFraction": round(big/max(n,1), 4), "box": box}))
`;
function pixels(mode, ...args) {
  try { return JSON.parse(execFileSync("python", ["-c", PIXELS_PY, mode, ...args], {encoding: "utf8"})); } catch (e) { return {error: String(e).slice(0, 200)}; }
}

// Injected before any page script: WebGL instrumentation (same as the M3 harness).
// A context that the page loses on purpose through WEBGL_lose_context.loseContext() (the quality
// layer's GPU benchmark probe, runtime/quality/gpu-caps.js) is not a WebGL failure: it is counted
// apart (intentional*) and no longer drained, as in minecraft-title-screen-cdp.mjs.
const glProbe = `(()=>{
  const rec = window.__m3gl = {gls:[], drained:{}, drainCalls:0, contexts:0, shaderFailures:[], programFailures:[], getErrors:{}, getErrorCalls:0, shaders:[], programs:[], lost:0, created:[],
    intentionalLossRequests:0, intentionalLost:0, intentionalErrors:{}};
  const intentional = new WeakSet();
  const names = {1280:'INVALID_ENUM',1281:'INVALID_VALUE',1282:'INVALID_OPERATION',1285:'OUT_OF_MEMORY',1286:'INVALID_FRAMEBUFFER_OPERATION',37442:'CONTEXT_LOST_WEBGL'};
  let gse0 = null;
  const wrap = proto => {
    if (!proto || proto.__m3) return; proto.__m3 = true;
    const gse = proto.getError; if (!gse0 && proto === (window.WebGL2RenderingContext && WebGL2RenderingContext.prototype)) gse0 = gse; proto.getError = function(){ const e = gse.call(this); rec.getErrorCalls++; if (e) { const k = names[e]||String(e); const into = intentional.has(this) ? rec.intentionalErrors : rec.getErrors; into[k]=(into[k]||0)+1; } return e; };
    const gex = proto.getExtension; proto.getExtension = function(name){ const ext = gex.call(this, name); if (ext && String(name).toLowerCase() === 'webgl_lose_context' && !ext.__m3) { const gl = this; const lose = ext.loseContext; ext.loseContext = function(){ intentional.add(gl); rec.intentionalLossRequests++; return lose.apply(this, arguments); }; ext.__m3 = true; } return ext; };
    const cs = proto.createShader; proto.createShader = function(t){ const s = cs.call(this,t); if (s && rec.shaders.length < 4000) rec.shaders.push({gl:this, s, type:t}); return s; };
    const cp = proto.createProgram; proto.createProgram = function(){ const p = cp.call(this); if (p && rec.programs.length < 4000) rec.programs.push({gl:this, p}); return p; };
    const gsp = proto.getShaderParameter; proto.getShaderParameter = function(s,n){ const v = gsp.call(this,s,n); if (n===0x8B81 && v===false && rec.shaderFailures.length<50) rec.shaderFailures.push(String(this.getShaderInfoLog(s)).slice(0,2000)); return v; };
    const gpp = proto.getProgramParameter; proto.getProgramParameter = function(p,n){ const v = gpp.call(this,p,n); if (n===0x8B82 && v===false && rec.programFailures.length<50) rec.programFailures.push(String(this.getProgramInfoLog(p)).slice(0,2000)); return v; };
    // Integer attribute pointers with a type WebGL2 rejects: record the call and the bound program's attribute.
    const up = proto.useProgram; if (up) proto.useProgram = function(p){ this.__m3program = p; return up.call(this, p); };
    const vip = proto.vertexAttribIPointer; if (vip) proto.vertexAttribIPointer = function(index,size,type,stride,offset){
      if (![5120,5121,5122,5123,5124,5125].includes(type)) { rec.badIPointer = rec.badIPointer || {count:0, samples:[]}; rec.badIPointer.count++;
        if (rec.badIPointer.samples.length < 12) { let attr = null; try { const p = this.__m3program; const n = p ? this.getProgramParameter(p, 0x8B89) : 0; for (let i = 0; i < n; i++) { const a = this.getActiveAttrib(p, i); if (a && this.getAttribLocation(p, a.name) === index) attr = {name:a.name, type:a.type, size:a.size}; } } catch (e) { attr = String(e); }
          rec.badIPointer.samples.push({index,size,type,stride,offset,attr,stack:String(new Error().stack).split(String.fromCharCode(10)).slice(1,6).map(l=>l.trim().slice(0,140))}); } }
      return vip.call(this,index,size,type,stride,offset); };
  };
  wrap(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
  wrap(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
  const gc = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(kind, attrs){ const c = gc.call(this, kind, attrs); if (c && /webgl/.test(kind) && !rec.gls.includes(c)) { rec.contexts++; rec.gls.push(c); rec.created.push(kind); this.addEventListener('webglcontextlost', ()=>{ if (intentional.has(c)) rec.intentionalLost++; else rec.lost++; }); } return c; };
  window.__m3drain = () => { rec.drainCalls++; for (const gl of rec.gls) { if (intentional.has(gl)) continue; for (let i = 0; i < 16; i++) { const e = gse0.call(gl); if (!e) break; const k = names[e]||String(e); rec.drained[k]=(rec.drained[k]||0)+1; } } return rec.drained; };
  window.__m3audit = () => {
    const bad = {shaders:[], programs:[]}; let shaders=0, programs=0;
    for (const {gl,s,type} of rec.shaders) { try { if (gl.isShader(s)) { shaders++; if (gl.getShaderParameter(s,0x8B81)===false) bad.shaders.push({type, log:String(gl.getShaderInfoLog(s)).slice(0,1500)}); } } catch(e){} }
    for (const {gl,p} of rec.programs) { try { if (gl.isProgram(p)) { programs++; if (gl.getProgramParameter(p,0x8B82)===false) bad.programs.push(String(gl.getProgramInfoLog(p)).slice(0,1500)); } } catch(e){} }
    return {contexts:rec.contexts, created:rec.created, lost:rec.lost, intentionalLossRequests:rec.intentionalLossRequests, intentionalLost:rec.intentionalLost, intentionalErrors:rec.intentionalErrors, liveShaders:shaders, livePrograms:programs, shadersCreated:rec.shaders.length, programsCreated:rec.programs.length,
      badIPointer:rec.badIPointer||null, badShaders:bad.shaders.slice(0,20), badPrograms:bad.programs.slice(0,20), shaderFailures:rec.shaderFailures.slice(0,20), programFailures:rec.programFailures.slice(0,20), getErrors:rec.getErrors, getErrorCalls:rec.getErrorCalls, drained:(window.__m3drain(), rec.drained), drainCalls:rec.drainCalls};
  };
  try { localStorage.setItem('gaius.playerName', ${JSON.stringify(playerName)}); } catch (e) {}
})();`;

const stateExpr = `(()=>{const s=window.__gaiusMinecraftState||null;const st=document.querySelector('[data-state]');const p=window.__gaiusChunkPipelineTelemetry||{};const a=window.__gaiusSectionAudit||null;
  const bp=[...document.querySelectorAll('#boot-screen,[id*=boot],[id*=status]')].filter(e=>!e.hidden).map(e=>({id:e.id,text:String(e.textContent||'').trim().slice(0,300)})).filter(e=>e.text);
  return {screen:s?String(s.screen||''):null,screenTitle:s?s.screenTitle:null,overlay:s?s.overlay:null,level:s?s.level:null,loaded:s?s.loadedChunkCount:null,
    player:s&&s.player?{x:s.player.x,y:s.player.y,z:s.player.z,yaw:s.player.yaw,pitch:s.player.pitch,mode:s.player.gameMode,item:s.player.selectedItem,collisionFree:s.player.collisionFree}:null,
    hit:s?s.hit:null,pause:s?s.pause:null,noRender:s?s.noRender:null,dist:s?s.clientDistance:null,worldSelection:s?s.worldSelection:null,
    widgets:s&&Array.isArray(s.screenWidgets)?s.screenWidgets.map(w=>({type:String(w.type||'').split('.').pop(),text:w.text,active:w.active,visible:w.visible,focused:w.focused})):null,
    statusState:st?st.dataset.state:null,boot:bp,fps:window.__gaiusFps?{fps:window.__gaiusFps.fps,gameFps:window.__gaiusFps.gameFps}:null,
    pipeline:{compile:Number(p.compileBacklog)||0,upload:Number(p.uploadBacklog)||0,peakCompile:Number(p.peakCompileBacklog)||0},
    audit:a&&{visible:a.visible,sections:a.sections,uncompiled:a.uncompiled,dirty:a.uncompiledDirty,waiting:a.uncompiledWaiting,lost:a.uncompiledLost,redirtied:a.redirtiedTotal,audits:a.audits,ageMs:Date.now()-a.at},
    pointerLock:document.pointerLockElement?document.pointerLockElement.id||'yes':null};})()`;
const screenExpr = "String(window.__gaiusMinecraftState?.screen||'')";
// The client runtime the page selected, the runtime the live singleplayer server Worker reports in
// its pong (Workers of builds before the ping message time out: server.timeout) and the mesh
// kernel counters of the client since the page loaded (MeshKernelBridge publishes them every 64
// jobs; poolJobs: mesher jobs the kernel pool completed). Recorded at world entry and re-entry,
// and again before Save and Quit (beforeQuit) and at the end (end).
const runtimeExpr = `(async()=>{const sel=window.__gaiusRuntimeSelection||null;const boot=window.__gaiusBoot||{};
  const pipe=window.__gaiusChunkPipelineTelemetry||{};
  const out={client:sel?sel.selected:(boot.runtime||null),clientReason:sel?sel.reason:null,bootServerRuntime:boot.serverRuntime||null,server:null,
    meshKernel:{submitted:pipe.meshKernelSubmitted??null,meshed:pipe.meshKernelMeshed??null,fallbacks:pipe.meshKernelVanillaFallbacks??null,disabled:pipe.meshKernelDisabled??null,
      poolJobs:(()=>{try{const t=window.__gaiusKernels&&window.__gaiusKernels.telemetry&&window.__gaiusKernels.telemetry();return t&&t.kernels&&t.kernels.mesher?t.kernels.mesher.completed:null;}catch(e){return null;}})()}};
  const ws=window.__gaiusSingleplayerWorkers;const live=ws&&typeof ws.values==='function'?[...ws.values()].filter(w=>w&&!w.__gaiusTerminal&&!w.__gaiusStopped):[];
  if(!live.length)return out;const w=live[live.length-1];const sequence=970000+Math.floor(Math.random()*20000);
  out.server=await new Promise(done=>{let timer=null;const onMessage=e=>{const d=e.data;if(d&&d.type==='pong'&&d.sequence===sequence){clearTimeout(timer);w.removeEventListener('message',onMessage);
    done({runtime:d.runtime||null,runtimeStarted:d.runtimeStarted??null,wasmPhase:d.wasm?d.wasm.phase:null});}};
    timer=setTimeout(()=>{w.removeEventListener('message',onMessage);done({timeout:true});},5000);
    w.addEventListener('message',onMessage);try{w.postMessage({type:'ping',sequence});}catch(e){clearTimeout(timer);done({error:String(e)});}});
  return out;})()`;
const inWorldExpr = "!!window.__gaiusMinecraftState?.level&&!window.__gaiusMinecraftState?.screen&&!!window.__gaiusMinecraftState?.player";

const report = {pageUrl, reenterPageUrl, profile, seed, startedAt: new Date().toISOString(), checks: {}, timeline: [], phases: {}, samples: []};
const pass = (n, d) => { report.checks[n] = {ok: true, detail: d}; log("CHECK", `PASS ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const fail = (n, d) => { report.checks[n] = {ok: false, detail: d}; log("CHECK", `FAIL ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const phase = (n, v) => { report.phases[n] = v; return log("phase", `${n} ${JSON.stringify(v).slice(0, 300)}`); };

async function command(cdp, text) {
  const firstLog = logLines.length;
  // Open chat with T, type the command, Enter. The chat screen must appear before typing.
  await key(cdp, "t", "KeyT", 84, "t");
  const end = Date.now() + 5000;
  while (Date.now() < end && !/ChatScreen/.test(await evaluate(cdp, screenExpr))) await sleep(100);
  await sleep(150);
  await typeText(cdp, text);
  await sleep(120);
  await key(cdp, "Enter", "Enter", 13);
  await log("command", text);
  const end2 = Date.now() + 5000;
  while (Date.now() < end2 && /ChatScreen/.test(await evaluate(cdp, screenExpr))) await sleep(100);
  await sleep(250);
  if (captureMode) {
    const rule = text.startsWith("/gamerule ") ? text.split(" ").slice(1) : null;
    const acknowledgement = rule ? new RegExp(`(?:Gamerule|Game rule) ${rule[0]} is (?:now|already) set to:? ${rule[1]}(?:$|\\s)`, "i")
      : text.startsWith("/time ") ? /Set the time to|Set minecraft:overworld to 6000 tick/i
      : text.startsWith("/weather ") ? /Set the weather to clear/i
      : text.startsWith("/gamemode ") ? /Set own game mode to Spectator Mode/i
      : text.startsWith("/kill ") ? /Killed|No entity was found/i
      : text.startsWith("/summon ") ? /Summoned new|Successfully summoned/i
      : text.startsWith("/tp ") ? /Teleported/i
      : text === "/tick freeze" ? /Game is (?:now )?frozen/i : /Game is (?:now )?(?:running normally|unfrozen)/i;
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline) {
      const response = logLines.slice(firstLog).find(line => /\[CHAT\]/.test(line) && acknowledgement.test(line));
      if (response) return {ok: true, response};
      await sleep(100);
    }
    return {ok: false, response: logLines.slice(firstLog).filter(line => /\[CHAT\]/.test(line))};
  }
}
let sampleN = 0;
async function sample(cdp, tag, shot) {
  const s = await evaluate(cdp, stateExpr).catch(e => ({err: String(e).slice(0, 200)}));
  const row = {t: Number(stamp()), tag, screen: s.screen, overlay: s.overlay, level: s.level, loaded: s.loaded, player: s.player && {x: Math.round(s.player.x), y: Math.round(s.player.y), z: Math.round(s.player.z)},
    pipeline: s.pipeline, audit: s.audit && {visible: s.audit.visible, sections: s.audit.sections, uncompiled: s.audit.uncompiled, lost: s.audit.lost, redirtied: s.audit.redirtied}, fps: s.fps, pause: s.pause};
  if (shot) { const p = await screenshot(cdp, `${tag}-${String(++sampleN).padStart(3, "0")}.png`); if (p) row.shot = p.split(/[\\/]/).pop(); row.px = p ? pixels("classify", p, "0.05,0.35,0.95,0.95") : null; }
  report.samples.push(row);
  await log("sample", JSON.stringify(row).slice(0, 600));
  return row;
}

const profileDir = await mkdtemp(join(out, "chrome-profile-"));
const port = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  // No background traffic; non-loopback requests are refused (the relay and bridge are local).
  ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore"});
let cdp;
const exceptions = [];
const workerLines = [];
  let serverWorkerSessionId = null;
try {
  let targets;
  for (let i = 0; i < 80; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.length) break; } catch {} await sleep(250); }
  cdp = new Cdp(targets.find(t => t.type === "page").webSocketDebuggerUrl); await cdp.open();
  const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
  cdp.on("Runtime.consoleAPICalled", (p, sid) => { const t = fmtArgs(p.args).slice(0, 4000); if (sid) workerLines.push(t); log(`console.${p.type}${sid ? "[worker]" : ""}`, t); });
  cdp.on("Runtime.exceptionThrown", (p, sid) => { const d = p.exceptionDetails; const t = (d.exception?.description || d.text || "").slice(0, 4000); exceptions.push({sid: !!sid, t, at: stamp()}); log(`EXCEPTION${sid ? "[worker]" : ""}`, t); });
  cdp.on("Log.entryAdded", (p, sid) => log(`log.${p.entry.level}${sid ? "[worker]" : ""}`, `${p.entry.source} ${String(p.entry.text).slice(0, 2000)} ${p.entry.url || ""}`));
  cdp.on("Network.requestWillBeSent", (p, sid) => {
    if (/vanilla-assets\.pack\./.test(p.request?.url || "")) log(`asset-request${sid ? "[target]" : ""}`, JSON.stringify({url:p.request.url, method:p.request.method, headers:p.request.headers}));
  });
  cdp.on("Network.loadingFailed", (p, sid) => {
    if (p.requestId) log(`asset-failed${sid ? "[target]" : ""}`, JSON.stringify({requestId:p.requestId,errorText:p.errorText,canceled:p.canceled}));
  });
  cdp.on("Network.loadingFailed", p => log("network-fail", `${p.errorText || ""} ${p.requestId || ""}`));
  const scriptSources = new Map();
  const workerExceptions = [];
  report.workerExceptions = workerExceptions;
  cdp.on("Debugger.paused", async (p, sid) => {
    try {
      const d = p.data || {};
      const desc = String(d.description || d.value || "");
      if (p.reason === "exception" && (d.className === "TypeError" || d.className === "RangeError" || d.className === "ReferenceError" || /TypeError|is not a function|Cannot read properties/.test(desc)) && workerExceptions.length < 20) {
        const frames = [];
        for (const f of (p.callFrames || []).slice(0, 14)) {
          const loc = f.location; let context = null;
          try {
            if (!scriptSources.has(loc.scriptId)) { const src = await cdp.send("Debugger.getScriptSource", {scriptId: loc.scriptId}, sid); scriptSources.set(loc.scriptId, String(src.scriptSource || "").split("\n")); }
            const line = scriptSources.get(loc.scriptId)[loc.lineNumber] || "";
            context = line.slice(Math.max(0, loc.columnNumber - 350), loc.columnNumber) + " <<<HERE>>> " + line.slice(loc.columnNumber, loc.columnNumber + 250);
          } catch (e) { context = "source unavailable: " + e; }
          frames.push({fn: f.functionName, line: loc.lineNumber + 1, col: loc.columnNumber, context});
        }
        workerExceptions.push({at: stamp(), description: desc.slice(0, 500), className: d.className, frames});
        await log("WORKER-EXC", desc.split("\n")[0].slice(0, 300) + " @ " + frames.map(f => `${f.fn || "?"}:${f.line}:${f.col}`).join(" < "));
      }
    } catch (e) { await log("worker-exc-err", String(e)); }
    try { await cdp.send("Debugger.resume", {}, sid); } catch {}
  });
  cdp.on("Target.attachedToTarget", async (p) => {
    await log("target", `${p.targetInfo.type} ${p.targetInfo.url}`);
    if (p.targetInfo.type === "worker" && /singleplayer-server|server-worker/i.test(String(p.targetInfo.url))) {
      serverWorkerSessionId = p.sessionId;
    }
    try { await cdp.send("Runtime.enable", {}, p.sessionId); await cdp.send("Log.enable", {}, p.sessionId); await cdp.send("Network.enable", {}, p.sessionId);
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, p.sessionId);
      if (workerExcDebug && p.targetInfo.type === "worker") {
        await cdp.send("Debugger.enable", {maxScriptsCacheSize: 1e9}, p.sessionId);
        await cdp.send("Debugger.setPauseOnExceptions", {state: "all"}, p.sessionId);
        await log("worker-exc-debug", "pause on exceptions enabled");
      }
      await cdp.send("Runtime.runIfWaitingForDebugger", {}, p.sessionId); } catch (e) { log("target-err", String(e)); }
  });
  await cdp.send("Runtime.enable"); await cdp.send("Page.enable"); await cdp.send("Log.enable"); await cdp.send("Network.enable");
  if (bypassServiceWorker) await cdp.send("Network.setBypassServiceWorker", {bypass: true});
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: glProbe});
  // After glProbe, so its getError drain (which calls the unwrapped method) is not counted.
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: GL_FRAME_GUARD_SOURCE});
  if (captureMode) await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: CAPTURE_BOOT_SOURCE});
  await log("navigate", pageUrl);
  await cdp.send("Page.navigate", {url: pageUrl});
  if (captureMode) {
    await waitFor(cdp, "typeof globalThis.__gaiusReleaseBoot==='function'", bootTimeout, "capture boot barrier");
    report.captureOptions = await evaluate(cdp, CAPTURE_OPTIONS_EXPRESSION);
  }

  // Phase 1: boot to the title screen.
  let last = "", reached = null;
  const bootEnd = Date.now() + bootTimeout;
  while (Date.now() < bootEnd) {
    await evaluate(cdp, 'window.__m3drain && window.__m3drain()').catch(() => null);
    let s = null; try { s = await evaluate(cdp, stateExpr); } catch (e) { s = {err: String(e).slice(0, 200)}; }
    const sig = JSON.stringify([s?.screen, s?.statusState, s?.boot?.map(b => b.text.slice(0, 120)), s?.widgets?.length]);
    if (sig !== last) { last = sig; report.timeline.push({t: stamp(), screen: s?.screen, statusState: s?.statusState, boot: s?.boot}); await log("state", JSON.stringify({screen: s?.screen, statusState: s?.statusState, boot: s?.boot, widgets: s?.widgets?.length}).slice(0, 1000)); }
    if (s?.screen && /TitleScreen|BrowserProfileScreen/.test(s.screen)) { reached = s.screen; break; }
    if (logLines.some(l => l.includes("Game crashed!")) || s?.statusState === "error") { await sleep(4000); report.bootError = await evaluate(cdp, stateExpr).catch(() => null); break; }
    await sleep(1000);
  }
  report.bootSeconds = Math.round((Date.now() - t0) / 1000);
  await screenshot(cdp, "10-first-screen.png");
  if (!reached) throw new Error("title screen not reached: " + JSON.stringify(report.bootError ?? null).slice(0, 800));
  await evaluate(cdp, 'window.__m3drainTimer = setInterval(() => window.__m3drain && window.__m3drain(), 1000)');
  pass("bootsToTitle", {screen: reached, seconds: report.bootSeconds});
  if (reached.endsWith("BrowserProfileScreen")) {
    await clickWidget(cdp, "Done", 10000).catch(async () => { await key(cdp, "Escape", "Escape", 27); });
    await waitFor(cdp, `${screenExpr}.endsWith('TitleScreen')`, 20000, "title after profile");
  }
  // Older launchers show an HTML name gate before the title screen.
  if (await evaluate(cdp, "document.querySelector('#profile-gate')?.hidden===false").catch(() => false)) {
    await evaluate(cdp, `(()=>{const i=document.querySelector('#profile-name');i.value=${JSON.stringify(playerName)};i.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#profile-submit').click();return true;})()`);
    await waitFor(cdp, `${screenExpr}.endsWith('TitleScreen')`, 60000, "title after gate");
  }
  await sleep(1500);
  await screenshot(cdp, "11-title.png");

  // Phase 2: Singleplayer -> Create New World.
  await clickWidget(cdp, "Singleplayer", 10000);
  await waitFor(cdp, `/SelectWorldScreen|CreateWorldScreen/.test(${screenExpr})`, 60000, "world screen");
  if ((await evaluate(cdp, screenExpr)).includes("SelectWorldScreen")) {
    await sleep(800); await screenshot(cdp, "12-select-world.png");
    report.worldListBefore = (await evaluate(cdp, stateExpr)).worldSelection;
    await clickWidget(cdp, "Create New World", 15000);
  }
  await waitFor(cdp, `${screenExpr}.includes('CreateWorldScreen')`, 60000, "create screen");
  await sleep(800);
  // Game mode -> Creative (the button cycles through Survival/Hardcore/Creative).
  let modeText = null;
  for (let i = 0; i < 4; i++) { const w = await findWidget(cdp, {text: "Game Mode"}, 3000); modeText = w?.text; if (!w || /creative/i.test(w.text)) break; await clickAt(cdp, w.x, w.y); await sleep(400); }
  const gm = await findWidget(cdp, {text: "Game Mode"}, 2000);
  /creative/i.test(gm?.text || "") ? pass("creativeSelected", gm?.text) : fail("creativeSelected", {modeText, gm});
  report.seedConfiguration = await configureWorldSeed(cdp, seed, {
    findButton: (session, text, timeout) => findWidget(session, {text}, timeout),
    click: clickAt,
    dispatchKey: async (session, code, type) => {
      const control = code === "ControlLeft";
      await keyEvent(session, type, control ? "Control" : "2", code, control ? 17 : 50, "", type === "keyUp" && control ? 0 : 2);
    },
    evaluate, waitFor, sleep, outputPath: join(out, "world-seed"),
  });
  report.createScreen = await evaluate(cdp, stateExpr);
  pass("seedTyped", report.seedConfiguration);
  const createdAt = Date.now();
  await clickWidget(cdp, "Create New World", 10000);

  // Phase 3: world entry timeline (screens shown while the Worker starts and the level loads).
  let lastScreen = "", firstChunkAt = null, enteredAt = null;
  const worldEnd = Date.now() + worldTimeout;
  while (Date.now() < worldEnd) {
    const s = await evaluate(cdp, stateExpr).catch(() => null);
    const sig = `${s?.screen}|${s?.overlay}|${s?.level}|${!!s?.player}`;
    if (sig !== lastScreen) { lastScreen = sig; report.timeline.push({t: stamp(), screen: s?.screen, overlay: s?.overlay, level: s?.level, loaded: s?.loaded, player: !!s?.player}); await log("state", sig); }
    if (s?.loaded > 0 && firstChunkAt === null) { firstChunkAt = (Date.now() - createdAt) / 1000; await phase("firstLoadedChunk", {seconds: firstChunkAt, loaded: s.loaded, screen: s.screen}); }
    if (s?.level && !s?.screen && s?.player) { enteredAt = (Date.now() - createdAt) / 1000; break; }
    if (logLines.some(l => l.includes("Game crashed!"))) { await sleep(3000); report.crashed = true; break; }
    if (/DisconnectedScreen|ErrorScreen|AlertScreen/.test(String(s?.screen))) { await sleep(2500); report.disconnected = await evaluate(cdp, stateExpr).catch(() => null); await log("disconnected", JSON.stringify(report.disconnected).slice(0, 1500)); break; }
    await sleep(500);
  }
  await screenshot(cdp, "20-world-entry.png");
  if (enteredAt === null) { report.worldEntryState = await evaluate(cdp, stateExpr).catch(() => null); throw new Error("world not entered: " + JSON.stringify(report.worldEntryState ?? null).slice(0, 600)); }
  await phase("worldEntered", {seconds: enteredAt});
  if (captureMode) report.captureEarlyFreeze = await freezeCaptureWorld(text => command(cdp, text));
  report.runtimes = {world: await evaluate(cdp, runtimeExpr).catch(e => ({error: String(e.message || e)}))};
  await log("runtime", "world " + JSON.stringify(report.runtimes.world));
  const runtimeSelection = {requested: effectiveRequestedRuntime, observed: report.runtimes.world.client};
  runtimeSelectionMatches(runtimeSelection.requested, runtimeSelection.observed)
    ? pass("runtimeSelection", runtimeSelection) : fail("runtimeSelection", runtimeSelection);
  pass("worldEntered", {seconds: enteredAt, firstLoadedChunkSeconds: firstChunkAt});

  if (worldgenBench) {
    const started = Date.now();
    const bench = {seed, targetChunks: 400, samples: [], destinations: []};
    report.worldgenBenchmark = bench;
    const attachEnd = Date.now() + 10_000;
    while (!serverWorkerSessionId && Date.now() < attachEnd) await sleep(100);
    if (!serverWorkerSessionId) throw new Error("worldgen benchmark: server Worker target not attached");
    await cdp.send("Runtime.enable", {}, serverWorkerSessionId);
    await cdp.send("Profiler.enable", {}, serverWorkerSessionId);
    await cdp.send("Profiler.setSamplingInterval", {interval: 1000}, serverWorkerSessionId);
    await cdp.send("Profiler.start", {}, serverWorkerSessionId);
    const benchStatsExpr = "(()=>{const s=globalThis.__gaiusMinecraftState||{};const c=globalThis.__gaiusMinecraftCounters||{};const p=globalThis.__gaiusClientChunkPacketPositions||{};return {worldgen:globalThis.__gaiusWorldgenStats||{},distance:globalThis.__gaiusServerDistanceTelemetry||{},tick:globalThis.__gaiusServerTickTelemetry||{},clientChunkPackets:Number(c['client.handleLevelChunkWithLight'])||0,clientChunkPacketPositions:Array.isArray(p.positions)?p.positions:[],clientChunkPacketPositionCount:Number(p.count)||0,loadedChunkCount:Number(s.loadedChunkCount)||0}})()";
    bench.startStats = await evaluate(cdp, benchStatsExpr, serverWorkerSessionId);
    bench.startClientStats = await evaluate(cdp, "(()=>{const s=globalThis.__gaiusMinecraftState||{},c=globalThis.__gaiusMinecraftCounters||{},p=globalThis.__gaiusClientChunkPacketPositions||{};return {clientChunkPackets:Number(c['client.handleLevelChunkWithLight'])||0,clientChunkPacketPositionCount:Number(p.count)||0,loadedChunkCount:Number(s.loadedChunkCount)||0}})()");
    bench.workerEvaluateLatencyMs = [];
    bench.tickDurationSamples = [];
    const destinations = [2048, 4096, 6144, 8192, 10240];
    for (const x of destinations) {
      const commandAt = Date.now();
      await command(cdp, `/tp @s ${x} 120 ${x}`);
      const placedDeadline = Date.now() + 60_000;
      let placedAt = null;
      while (Date.now() < placedDeadline) {
        const s = await evaluate(cdp, "globalThis.__gaiusMinecraftState||{}", undefined).catch(() => null);
        if (s?.player && Math.hypot(Number(s.player.x)-x, Number(s.player.z)-x) < 32) { placedAt = Date.now(); break; }
        await sleep(100);
      }
      const loadedDeadline = Date.now() + 90_000;
      let loadedAt = null; let loaded = null; let relativeCount = 0;
      while (Date.now() < loadedDeadline) {
        const s = await evaluate(cdp, "globalThis.__gaiusMinecraftState||{}", undefined).catch(() => null);
        loaded = Number(s?.loadedChunkCount) || 0;
        const packetState = await evaluate(cdp, "globalThis.__gaiusClientChunkPacketPositions||{}", undefined).catch(() => null);
        const seen = new Set((Array.isArray(packetState?.positions) ? packetState.positions : [])
          .filter(v => Array.isArray(v) && Math.abs(Number(v[0]) - Math.floor(x / 16)) <= 8
            && Math.abs(Number(v[1]) - Math.floor(x / 16)) <= 8)
          .map(v => `${v[0]},${v[1]}`));
        relativeCount = seen.size;
        if (relativeCount >= 289) { loadedAt = Date.now(); break; }
        await sleep(250);
      }
      const stats = await (async () => { const t = Date.now(); const v = await evaluate(cdp, benchStatsExpr, serverWorkerSessionId).catch(() => null); bench.workerEvaluateLatencyMs.push(Date.now() - t); if (Number.isFinite(v?.tick?.lastTickDurationMillis)) bench.tickDurationSamples.push(v.tick.lastTickDurationMillis); return v; })();
      bench.destinations.push({x, z:x, commandMs: commandAt-started, playerPlacedMs: placedAt ? placedAt-commandAt : null, clientRenderDistanceChunks: 289, destinationRelativePacketChunks: relativeCount, loadedChunkCount: loaded, loadedAllMs: loadedAt ? loadedAt-commandAt : null, stats});
    }
    bench.samples = bench.destinations;
    await sleep(1000);
    const stopped = await cdp.send("Profiler.stop", {}, serverWorkerSessionId);
    bench.endStats = await evaluate(cdp, benchStatsExpr, serverWorkerSessionId).catch(() => null);
    bench.endClientStats = await evaluate(cdp, "(()=>{const s=globalThis.__gaiusMinecraftState||{},c=globalThis.__gaiusMinecraftCounters||{},p=globalThis.__gaiusClientChunkPacketPositions||{};return {clientChunkPackets:Number(c['client.handleLevelChunkWithLight'])||0,clientChunkPacketPositions:Array.isArray(p.positions)?p.positions:[],clientChunkPacketPositionCount:Number(p.count)||0,loadedChunkCount:Number(s.loadedChunkCount)||0}})()").catch(() => null);
    bench.durationMs = Date.now() - started;
    bench.profileSamples = stopped.profile?.samples?.length || 0;
    bench.profileMaxSampleGapMs = (stopped.profile?.timeDeltas || []).reduce((m, v) => Math.max(m, Number(v) || 0), 0) / 1000;
    await writeFile(join(out, "worldgen-worker.cpuprofile"), JSON.stringify(stopped.profile));
    bench.numericDelta = {};
    for (const [key, value] of Object.entries(bench.endStats?.worldgen || {})) {
      if (typeof value === "number") bench.numericDelta[key] = value - (Number(bench.startStats?.worldgen?.[key]) || 0);
    }
    bench.newChunks = Math.max(0, Number(bench.endStats?.distance?.acknowledgedChunkCount || 0)
      - Number(bench.startStats?.distance?.acknowledgedChunkCount || 0));
    bench.generatedChunks = bench.newChunks;
    bench.clientChunkPackets = Math.max(0, Number(bench.endClientStats?.clientChunkPackets || 0)
      - Number(bench.startClientStats?.clientChunkPackets || 0));
    bench.clientLoadedChunkDelta = Math.max(0, Number(bench.endClientStats?.loadedChunkCount || 0)
      - Number(bench.startClientStats?.loadedChunkCount || 0));
    const pct = (values, fraction) => { const a = values.filter(Number.isFinite).sort((x,y)=>x-y); return a.length ? a[Math.min(a.length-1, Math.floor(a.length*fraction))] : null; };
    bench.destinationPlayerPlacedP50Ms = pct(bench.destinations.map(d => d.playerPlacedMs), 0.5);
    bench.destinationPlayerPlacedP95Ms = pct(bench.destinations.map(d => d.playerPlacedMs), 0.95);
    bench.destinationLoadedAllP50Ms = pct(bench.destinations.map(d => d.loadedAllMs), 0.5);
    bench.destinationLoadedAllP95Ms = pct(bench.destinations.map(d => d.loadedAllMs), 0.95);
    bench.destinationExpectedChunks = 289;
    bench.destinationChunksPerSecondP50 = bench.destinationLoadedAllP50Ms ? 289 / (bench.destinationLoadedAllP50Ms / 1000) : null;
    bench.chunksPerSecond = bench.generatedChunks && bench.durationMs
      ? bench.generatedChunks / (bench.durationMs / 1000) : null;
    bench.workerEvaluateLatencyMaxMs = Math.max(...bench.workerEvaluateLatencyMs, 0);
    bench.workerEvaluateLatencyP95Ms = bench.workerEvaluateLatencyMs.sort((a,b)=>a-b)[Math.floor(bench.workerEvaluateLatencyMs.length*0.95)] || 0;
    bench.serverTick = bench.endStats?.tick || null;
    const ticks = bench.tickDurationSamples.slice().sort((a,b)=>a-b);
    bench.serverTickP50Ms = ticks.length ? ticks[Math.floor(ticks.length * 0.50)] : null;
    bench.serverTickP95Ms = ticks.length ? ticks[Math.floor(ticks.length * 0.95)] : null;
  }
  if (process.env.ACCEPT_EXTENDED === "1") {
    await key(cdp, "F2", "F2", 113); await sleep(1800);
    report.f2Storage = await evaluate(cdp, `(()=>{const f=globalThis.__gaiusPersistentFiles||{};const keys=Object.keys(f).filter(k=>/screenshot|png/i.test(k));return {keys:keys.map(k=>({path:k,bytes:typeof f[k]==='string'?Math.floor(f[k].length*3/4):(f[k]?.byteLength||0)})),backend:String(globalThis.__gaiusFsBackend||'')};})()`).catch(e=>({error:String(e)}));
    const png=(report.f2Storage.keys||[]).find(x=>x.bytes>1000); png?pass("f2Screenshot",report.f2Storage):fail("f2Screenshot",report.f2Storage);
    // 26.3's command identifiers are the serialized rule names.  Capture the
    // server's chat component text (the [CHAT] line), rather than treating a
    // command submission as success.
    for (const cmd of ["/gamerule random_tick_speed 7","/gamerule advance_time false","/gamerule random_tick_speed","/gamerule advance_time"]) await command(cdp,cmd);
    const rules=logLines.filter(l=>/\[CHAT\]/i.test(l)&&/random_tick_speed|advance_time|gamerule/i.test(l)); report.gameRulesOutput=rules;
    const ruleText=rules.map(l=>l.replace(/^.*\[CHAT\]\s*/,"")).join(" ");
    /random_tick_speed\s+7\b/.test(ruleText)&&/advance_time\s+false\b/.test(ruleText)
      ? pass("gameRules",{chat:rules,text:ruleText}) : fail("gameRules",{chat:rules,text:ruleText});
    await command(cdp,"/seed"); await sleep(500);
    const seedLines=logLines.filter(l=>/\[CHAT\]/i.test(l)&&/seed/i.test(l));
    const seedText=seedLines.map(l=>l.replace(/^.*\[CHAT\]\s*/,"")).join(" ");
    const seedMatch=/(?:Seed:\s*)?(-?\d{1,20})\b/.exec(seedText);
    report.browserSeed={lines:seedLines,text:seedText,value:seedMatch?seedMatch[1]:null};
    seedMatch?pass("seedReadback",report.browserSeed):fail("seedReadback",report.browserSeed);
    const locatePlayer=(await evaluate(cdp,stateExpr).catch(()=>null))?.player||null;
    await command(cdp,"/locate structure stronghold"); await sleep(1500);
    const loc=logLines.filter(l=>/\[CHAT\]/i.test(l)&&/stronghold/i.test(l));
    const locText=loc.map(l=>l.replace(/^.*\[CHAT\]\s*/,"")).join(" ");
    const coord=/\[\s*(-?\d+)\s*,\s*~\s*,\s*(-?\d+)\s*\]/.exec(locText);
    const actualBlocks=coord?[Number(coord[1]),Number(coord[2])]:null;
    const actualChunks=actualBlocks&&actualBlocks.every(Number.isFinite)?actualBlocks.map(v=>Math.floor(v/16)):null;
    const distanceMatch=/\((-?\d+) blocks away\)/.exec(locText);
    report.locate={lines:loc,text:locText,actualBlocks,actualChunks,distanceBlocks:distanceMatch?Number(distanceMatch[1]):null,player:locatePlayer,
      seed:report.browserSeed.value,vanillaNearest:null,vanillaContains:null};
    // The JVM-derived expected nearest is filled by the acceptance driver after
    // reading this seed and player position; browser-side evidence remains raw.
    actualChunks?pass("locateStronghold",report.locate):fail("locateStronghold",report.locate);
  }

  // Phase 4: terrain settle: first rendered terrain, chunk growth, section audit.
  let firstTerrainAt = null, prevLoaded = -1, stableSince = null;
  const settleEnd = Date.now() + settleMs;
  while (Date.now() < settleEnd) {
    const row = await sample(cdp, "spawn", true);
    if (firstTerrainAt === null && row.px && !row.px.error && row.px.other > 0.3) { firstTerrainAt = (Date.now() - createdAt) / 1000; await phase("firstTerrainRendered", {seconds: firstTerrainAt, px: row.px, loaded: row.loaded}); }
    if (row.loaded >= 9 && row.loaded === prevLoaded && row.audit && row.audit.uncompiled === 0) { if (stableSince === null) stableSince = Date.now(); else if (Date.now() - stableSince > 8000 && firstTerrainAt !== null) break; }
    else stableSince = null;
    prevLoaded = row.loaded;
    await sleep(2000);
  }
  const spawn = await evaluate(cdp, stateExpr);
  report.spawn = spawn;
  await screenshot(cdp, "21-spawn.png");
  const spawnPx = pixels("classify", join(out, "21-spawn.png"), "0.05,0.35,0.95,0.95");
  const loadedOk = (spawn.loaded || 0) >= 9;
  loadedOk && firstTerrainAt !== null ? pass("terrainRendered", {firstTerrainSeconds: firstTerrainAt, loaded: spawn.loaded, audit: spawn.audit, px: spawnPx})
    : fail("terrainRendered", {firstTerrainSeconds: firstTerrainAt, loaded: spawn.loaded, audit: spawn.audit, px: spawnPx, pipeline: spawn.pipeline});
  const p0 = spawn.player;
  await log("player", JSON.stringify(p0));
  await log("stage", `frame-metrics enter captureMode=${captureMode} CAPTURE_MODE=${process.env.CAPTURE_MODE || ""} worldgenBench=${worldgenBench} ACCEPT_EXTENDED=${process.env.ACCEPT_EXTENDED || ""}`);
  if (perfCapture) {
    await log("perf-enabled", `entry state=${JSON.stringify({perfCapture, perfDurationMs, out})}`);
    console.error(`[perf-capture-enabled] out=${out}`);
    report.performance = [];
    report.performance.push(await captureMainPerformance(cdp, "perf-standing"));
    await clickAt(cdp, 640, 360); await sleep(300);
    report.performance.push(await captureMainPerformance(cdp, "perf-flying"));
    await releaseInput(cdp);
    await log("performance", JSON.stringify(report.performance));
  }
  // Synchronous GL calls and unique presented frames in the settled world (tools/gl-frame-guard.mjs).
  report.glFrames = await evaluate(cdp, GL_FRAME_GUARD_WINDOW_EXPRESSION(2000)).catch(e => ({installed: false, error: String(e)}));
  await log("gl-frames", JSON.stringify(report.glFrames).slice(0, 600));
  report.frameMetrics = await evaluate(cdp, `(async()=>{
    const G=globalThis.__gaiusGlFrameGuard||{}; const start=performance.now(); const rows=[];
    let last=performance.now(), lastFrames=G.frames||0, lastTick=null;
    await new Promise(resolve=>{const loop=t=>{const s=globalThis.__gaiusMinecraftState||{};const tick=globalThis.__gaiusClientTickTelemetry?.tick??globalThis.__gaiusClientTick??null;
      rows.push({dt:t-last,frame:(G.frames||0)-lastFrames,tick,ts:t});last=t;lastFrames=G.frames||0;lastTick=tick;
      if(t-start<3000) requestAnimationFrame(loop); else resolve();};requestAnimationFrame(loop);});
    const intervals=rows.map(x=>x.dt).filter(x=>x>0), sorted=intervals.slice().sort((a,b)=>a-b), q=p=>sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))]||null;
    const frames=rows.reduce((n,x)=>n+x.frame,0), span=rows.at(-1)?.ts-start||0;
    let adapter=null;try{if(navigator.gpu){const a=await navigator.gpu.requestAdapter();adapter=a?{name:a.name||null,info:a.info||null,features:a.features?[...a.features]:[]}:null;}}catch(e){adapter={error:String(e)}}
    let webgl=null;try{const c=document.createElement('canvas'),gl=c.getContext('webgl2')||c.getContext('webgl'),x=gl?.getExtension('WEBGL_debug_renderer_info');webgl=x?{vendor:gl.getParameter(x.UNMASKED_VENDOR_WEBGL),renderer:gl.getParameter(x.UNMASKED_RENDERER_WEBGL)}:null;}catch(e){webgl={error:String(e)}}
    const tickRows=rows.filter(x=>x.tick!==null), tickFrames=tickRows.filter(x=>x.tick!==lastTick).reduce((n,x)=>n+x.frame,0);
    return {spanMs:span,rafFps:rows.length*1000/span,uniqueFps:frames*1000/span,frameTimes:{p50:q(.5),p95:q(.95),p99:q(.99)},frames,tickSamples:tickRows.length,tickFrames,nonTickFrames:frames-tickFrames,adapter,webgl};
  })()`).catch(e=>({error:String(e)}));
  report.adapterInfo = report.frameMetrics?.adapter ?? null;
  report.rendererInfo = report.frameMetrics?.webgl ?? null;
  await log("frame-metrics", JSON.stringify(report.frameMetrics).slice(0, 1600));
  await log("stage", "frame-metrics exit");

  if (captureMode) {
    await log("stage", `capture enter captureMode=${captureMode} CAPTURE_MODE=${process.env.CAPTURE_MODE || ""}`);
    report.capture = await captureDeterministicWorld({
      command: text => command(cdp, text), evaluate: expression => evaluate(cdp, expression),
      waitFor: (expression, timeout, label) => waitFor(cdp, expression, timeout, label),
      key: (name, code, vk) => key(cdp, name, code, vk), sleep,
      screenshot: name => screenshot(cdp, name), player: p0, outputPath: out,
      seed, profile, stageReceipt, clientIdentity, cameras: captureCameras,
      backend: new URL(pageUrl).searchParams.get("gpu"),
    });
    pass("deterministicCapture", {complete: report.capture.complete, views: Object.keys(report.capture.views)});
    await log("stage", "capture exit");
  } else {
  // Phase 5: noon, frozen daylight, top-down shot from above the spawn.
  // 1.21.11, 26.2 and 26.3 all name the daylight cycle rule advance_time.
  await command(cdp, "/gamerule advance_time false");
  await command(cdp, "/time set 6000");
  const sx = Math.floor(p0.x) + 0.5, sz = Math.floor(p0.z) + 0.5, groundY = Math.floor(p0.y);
  const camY = groundY + 40;
  await command(cdp, "/gamemode spectator");
  await command(cdp, `/tp @s ${sx} ${camY} ${sz} 0 90`);
  await sleep(4000);
  const topdown = await sample(cdp, "topdown", true);
  await screenshot(cdp, "22-topdown-noon.png");
  const tdPx = pixels("classify", join(out, "22-topdown-noon.png"), "0.12,0.08,0.72,0.80");
  report.topdown = {player: topdown.player, px: tdPx, audit: topdown.audit};
  tdPx && !tdPx.error && tdPx.sky < 0.05 && tdPx.dark < 0.2 ? pass("topDownTerrain", tdPx) : fail("topDownTerrain", tdPx);
  await command(cdp, "/gamemode creative");
  await command(cdp, `/tp @s ${sx} ${groundY} ${sz} 0 0`);
  await sleep(1500);

  // Phase 6: move forward with W.
  const before = (await evaluate(cdp, stateExpr)).player;
  await clickAt(cdp, 640, 360); // focus / grab the mouse as a user would
  await sleep(300);
  await holdKey(cdp, "w", "KeyW", 87, 1500);
  await sleep(800);
  const after = (await evaluate(cdp, stateExpr)).player;
  const moved = before && after ? Math.hypot(after.x - before.x, after.z - before.z) : 0;
  report.move = {before, after, distance: moved};
  moved > 0.5 ? pass("movesWithW", {distance: +moved.toFixed(2)}) : fail("movesWithW", report.move);
  await screenshot(cdp, "23-after-move.png");

  // Phase 7: break and place the block under the player (look straight down).
  // Return to the measured spawn surface: the movement check can cross a slope, where
  // reusing spawn's height at the new coordinates puts the player inside a solid block.
  const px2 = sx, pz2 = sz;
  await command(cdp, `/tp @s ${px2} ${groundY} ${pz2} 0 90`);
  await command(cdp, "/item replace entity @s hotbar.0 with minecraft:stone 1");
  await key(cdp, "1", "Digit1", 49, "1");
  await waitFor(cdp, "globalThis.__gaiusMinecraftState?.player?.selectedItem?.item==='minecraft:stone'", 15000, "stone inventory update");
  await sleep(1200);
  const h0 = await evaluate(cdp, stateExpr);
  await log("hit0", JSON.stringify(h0.hit) + " item=" + JSON.stringify(h0.player?.item));
  await screenshot(cdp, "24-before-break.png");
  await clickAt(cdp, 640, 360, "left");
  let h1 = null; { const end = Date.now() + 6000; while (Date.now() < end) { h1 = await evaluate(cdp, stateExpr); if (h1.hit && h0.hit && (h1.hit.blockPos !== h0.hit.blockPos || h1.hit.blockState !== h0.hit.blockState)) break; await sleep(150); } }
  await log("hit1", JSON.stringify(h1?.hit));
  await screenshot(cdp, "25-after-break.png");
  const broke = h0.hit?.type === "BLOCK" && h1?.hit && (h1.hit.blockPos !== h0.hit.blockPos || /air/.test(String(h1.hit.blockState)));
  broke ? pass("breaksBlock", {before: h0.hit, after: h1.hit}) : fail("breaksBlock", {before: h0.hit, after: h1?.hit, item: h0.player?.item, mode: h0.player?.mode});
  await clickAt(cdp, 640, 360, "right");
  let h2 = null; { const end = Date.now() + 6000; while (Date.now() < end) { h2 = await evaluate(cdp, stateExpr); if (h2.hit && h1?.hit && (h2.hit.blockPos !== h1.hit.blockPos || h2.hit.blockState !== h1.hit.blockState)) break; await sleep(150); } }
  await log("hit2", JSON.stringify(h2?.hit));
  await screenshot(cdp, "26-after-place.png");
  const placed = h2?.hit && /stone/i.test(String(h2.hit.blockState)) && !/stone/i.test(String(h1?.hit?.blockState || ""));
  placed ? pass("placesBlock", {after: h2.hit, item: h2.player?.item}) : fail("placesBlock", {before: h1?.hit, after: h2?.hit, item: h2?.player?.item});
  report.placedBlock = {pos: h2?.hit?.blockPos, state: h2?.hit?.blockState, playerAt: h2?.player};
  const bd = pixels("diff", join(out, "24-before-break.png"), join(out, "26-after-place.png"), "0.3,0.3,0.7,0.7");
  report.breakPlaceScreenDiff = bd;

  // Phase 8: pause menu. Release any captured input first (a delayed pointer-lock transition
  // otherwise swallows the Escape as "exit pointer lock" and the pause screen never opens).
  await releaseInput(cdp);
  for (let i = 0; i < 4 && !/PauseScreen/.test(await evaluate(cdp, screenExpr)); i++) { await key(cdp, "Escape", "Escape", 27); await sleep(1500); }
  await waitFor(cdp, `${screenExpr}.includes('PauseScreen')`, 5000, "pause screen");
  await sleep(800);
  await screenshot(cdp, "30-pause.png");
  const pauseState = await evaluate(cdp, stateExpr);
  report.pause = pauseState.widgets;
  const lan = (pauseState.widgets || []).find(w => /LAN/i.test(String(w.text)));
  const sq = (pauseState.widgets || []).find(w => /Save and Quit/i.test(String(w.text)));
  lan && sq ? pass("pauseMenu", {lan: lan.text, saveQuit: sq.text, widgets: (pauseState.widgets || []).map(w => w.text)}) : fail("pauseMenu", {widgets: (pauseState.widgets || []).map(w => w.text)});
  const leavePos = pauseState.player;

  // Phase 9: Save and Quit -> title.
  const quitAt = Date.now();
  report.runtimes = {...(report.runtimes || {}), beforeQuit: await evaluate(cdp, runtimeExpr).catch(e => ({error: String(e.message || e)}))};
  await log("runtime", "beforeQuit " + JSON.stringify(report.runtimes.beforeQuit));
  await clickWidget(cdp, "Save and Quit to Title", 10000);
  await waitFor(cdp, `${screenExpr}.endsWith('TitleScreen')&&!window.__gaiusMinecraftState?.level`, 120000, "title after save");
  const titleAt = (Date.now() - quitAt) / 1000;
  // The client shows the title screen before the Worker has flushed the world to browser
  // storage; the launcher terminates and forgets the Worker once it reports "stopped".
  const workerStopExpr = "(()=>{const w=globalThis.__gaiusSingleplayerWorkers;if(!w||typeof w.values!=='function')return true;return [...w.values()].every(x=>x.__gaiusStopped||x.__gaiusTerminal);})()";
  const stopped = await waitFor(cdp, workerStopExpr, 120000, "worker stopped after save").then(() => true).catch(() => false);
  await phase("saveAndQuit", {titleSeconds: titleAt, workerStoppedSeconds: (Date.now() - quitAt) / 1000, workerStopped: stopped});
  stopped ? pass("saveAndQuit", report.phases.saveAndQuit) : fail("saveAndQuit", report.phases.saveAndQuit);
  await sleep(1500);
  await screenshot(cdp, "31-title-after-quit.png");

  // Phase 10: re-enter the world from the world list.
  if (reenterMode === "reload") {
    await log("reload", `navigating to ${reenterPageUrl || "the page again"} before re-entering the world`);
    await cdp.send("Page.navigate", {url: reenterPageUrl || pageUrl});
    const end = Date.now() + bootTimeout; let sc = "";
    while (Date.now() < end && !/TitleScreen|BrowserProfileScreen/.test(sc = await evaluate(cdp, screenExpr).catch(() => ""))) await sleep(1000);
    if (/BrowserProfileScreen/.test(sc)) { await clickWidget(cdp, "Done", 10000).catch(() => key(cdp, "Escape", "Escape", 27)); await waitFor(cdp, `${screenExpr}.endsWith('TitleScreen')`, 20000, "title after profile (reload)"); }
    if (await evaluate(cdp, "document.querySelector('#profile-gate')?.hidden===false").catch(() => false)) {
      await evaluate(cdp, `(()=>{const i=document.querySelector('#profile-name');i.value=${JSON.stringify(playerName)};i.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#profile-submit').click();return true;})()`);
      await waitFor(cdp, `${screenExpr}.endsWith('TitleScreen')`, 60000, "title after gate (reload)");
    }
    await evaluate(cdp, 'window.__m3drainTimer = setInterval(() => window.__m3drain && window.__m3drain(), 1000)').catch(() => {});
    await sleep(1500);
  }
  await clickWidget(cdp, "Singleplayer", 10000);
  await waitFor(cdp, `${screenExpr}.includes('SelectWorldScreen')`, 60000, "select world screen");
  let entry = null; { const end = Date.now() + 20000; while (Date.now() < end) { entry = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const f=s.worldSelection&&s.worldSelection.first;const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!f||!r||!z)return null;
    return {name:f.name,count:s.worldSelection.count,x:r.left+(Number(f.x)+Number(f.width)/2)*r.width/Number(z.width),y:r.top+(Number(f.y)+Number(f.height)/2)*r.height/Number(z.height)};})()`); if (entry) break; await sleep(250); } }
  await sleep(500); await screenshot(cdp, "32-world-list.png");
  report.worldListAfter = entry;
  if (!entry) throw new Error("world list is empty after Save and Quit");
  pass("worldListed", entry);
  const reenterAt = Date.now();
  await clickAt(cdp, entry.x, entry.y);
  await sleep(500);
  if (!(await evaluate(cdp, "!!window.__gaiusMinecraftState?.level"))) await clickWidget(cdp, "Play Selected World", 5000);
  let reFirstChunk = null, reEntered = null;
  { const end = Date.now() + worldTimeout; lastScreen = "";
    while (Date.now() < end) { const s = await evaluate(cdp, stateExpr).catch(() => null);
      const sig = `${s?.screen}|${s?.overlay}|${s?.level}|${!!s?.player}`;
      if (sig !== lastScreen) { lastScreen = sig; report.timeline.push({t: stamp(), reenter: true, screen: s?.screen, overlay: s?.overlay, level: s?.level, loaded: s?.loaded}); await log("state", "reenter " + sig); }
      if (s?.loaded > 0 && reFirstChunk === null) reFirstChunk = (Date.now() - reenterAt) / 1000;
      if (s?.level && !s?.screen && s?.player) { reEntered = (Date.now() - reenterAt) / 1000; break; }
      if (logLines.some(l => l.includes("Game crashed!"))) { report.crashed = true; break; }
      if (/DisconnectedScreen|ErrorScreen|AlertScreen/.test(String(s?.screen))) { await sleep(2500); report.disconnected = await evaluate(cdp, stateExpr).catch(() => null); await log("disconnected", JSON.stringify(report.disconnected).slice(0, 1500)); break; }
      await sleep(500); } }
  await screenshot(cdp, "40-reentered.png");
  if (reEntered === null) throw new Error("world not re-entered: " + JSON.stringify(await evaluate(cdp, stateExpr).catch(() => null)).slice(0, 600));
  await phase("reentered", {seconds: reEntered, firstLoadedChunkSeconds: reFirstChunk});
  report.runtimes = {...(report.runtimes || {}), reentered: await evaluate(cdp, runtimeExpr).catch(e => ({error: String(e.message || e)}))};
  await log("runtime", "reentered " + JSON.stringify(report.runtimes.reentered));
  // Chunks reload.
  let reLoaded = 0; { const end = Date.now() + settleMs; let prev = -1, since = null;
    while (Date.now() < end) { const row = await sample(cdp, "reenter", true); reLoaded = row.loaded || 0;
      if (row.loaded >= 9 && row.loaded === prev && row.audit && row.audit.uncompiled === 0 && row.px && row.px.other > 0.3) { if (since === null) since = Date.now(); else if (Date.now() - since > 8000) break; } else since = null;
      prev = row.loaded; await sleep(2000); } }
  const re = await evaluate(cdp, stateExpr);
  report.reentered = {player: re.player, loaded: re.loaded, audit: re.audit, seconds: reEntered};
  reLoaded >= 9 ? pass("chunksReload", {loaded: reLoaded, seconds: reEntered, firstChunk: reFirstChunk}) : fail("chunksReload", {loaded: reLoaded, audit: re.audit});
  const posDelta = leavePos && re.player ? Math.hypot(re.player.x - leavePos.x, re.player.y - leavePos.y, re.player.z - leavePos.z) : null;
  posDelta !== null && posDelta < 2.5 ? pass("playerPositionPersisted", {leavePos, now: re.player, delta: +posDelta.toFixed(2)}) : fail("playerPositionPersisted", {leavePos, now: re.player, delta: posDelta});
  // Placed block persisted: look straight down again from the same spot.
  await command(cdp, `/tp @s ${px2} ${groundY} ${pz2} 0 90`);
  await sleep(1500);
  const h3 = await evaluate(cdp, stateExpr);
  await log("hit3", JSON.stringify(h3.hit));
  await screenshot(cdp, "41-placed-block-after-reload.png");
  h3.hit && h3.hit.blockPos === report.placedBlock.pos && /stone/i.test(String(h3.hit.blockState)) ? pass("placedBlockPersisted", h3.hit) : fail("placedBlockPersisted", {expected: report.placedBlock, got: h3.hit});
  // Terrain baseline: same top-down camera as before.
  await command(cdp, "/gamemode spectator");
  await command(cdp, `/tp @s ${sx} ${camY} ${sz} 0 90`);
  await sleep(5000);
  await sample(cdp, "topdown2", false);
  await screenshot(cdp, "42-topdown-after-reload.png");
  const td2 = pixels("classify", join(out, "42-topdown-after-reload.png"), "0.12,0.08,0.72,0.80");
  const diff = pixels("diff", join(out, "22-topdown-noon.png"), join(out, "42-topdown-after-reload.png"), "0.12,0.08,0.72,0.80");
  report.terrainBaseline = {before: tdPx, after: td2, diff};
  report.runtimes = {...(report.runtimes || {}), end: await evaluate(cdp, runtimeExpr).catch(e => ({error: String(e.message || e)}))};
  await log("runtime", "end " + JSON.stringify(report.runtimes.end));
  diff && !diff.error && diff.meanDiff < 20 && diff.changedFraction < 0.15 && td2.sky < 0.05 ? pass("terrainBaseline", report.terrainBaseline) : fail("terrainBaseline", report.terrainBaseline);
  await command(cdp, "/gamemode creative");
  await sleep(1000);
  }
} catch (e) {
  report.error = String(e.stack || e);
  await log("HARNESS-ERROR", report.error);
  try { await screenshot(cdp, "error.png"); report.errorState = await evaluate(cdp, stateExpr); } catch {}
} finally {
  try { report.gl = await evaluate(cdp, "window.__m3audit ? window.__m3audit() : null"); } catch (e) { report.gl = String(e); }
  const glConsole = logLines.filter(l => /GL_INVALID|WebGL: |INVALID_OPERATION|INVALID_ENUM|INVALID_VALUE|INVALID_FRAMEBUFFER|CONTEXT_LOST|GL ERROR|GL error/.test(l));
  const wireframeOnly = l => /WIREFRAME fill mode, not supported by device/.test(l)
    || (/Failed to load optional shader programs/.test(l) && (l.match(/ - \S+/g) || []).every(x => /^ - minecraft:pipeline\/wireframe(_multidraw)?$/.test(x)));
  report.expectedPipelineRejections = logLines.filter(l => wireframeOnly(l));
  const shaderConsole = logLines.filter(l => !wireframeOnly(l) && isShaderConsoleError(l));
  report.glConsole = glConsole.slice(0, 50); report.shaderConsole = shaderConsole.slice(0, 50);
  report.exceptions = exceptions.slice(0, 80);
  report.workerErrorLines = logLines.filter(l => /\[worker\]/.test(l) && /error|exception|fatal|crash/i.test(l)).slice(0, 80);
  report.pageErrorLines = logLines.filter(l => /console\.error|log\.error|EXCEPTION/.test(l) && !/\[worker\]/.test(l)).slice(0, 80);
  const g = report.gl && typeof report.gl === "object" ? report.gl : null;
  if (g) {
    const errs = Object.values(g.getErrors || {}).reduce((a, b) => a + b, 0);
    g.badShaders.length + g.badPrograms.length + g.shaderFailures.length + g.programFailures.length === 0 && shaderConsole.length === 0
      ? pass("noShaderErrors", {live: g.liveShaders, programs: g.livePrograms}) : fail("noShaderErrors", {bad: g.badShaders.length + g.badPrograms.length, shaderConsole: shaderConsole.slice(0, 5)});
    const drained = Object.values(g.drained || {}).reduce((a, b) => a + b, 0);
    errs === 0 && drained === 0 && glConsole.length === 0 && g.lost === 0 ? pass("noWebGLErrors", {getErrorCalls: g.getErrorCalls, drainCalls: g.drainCalls}) : fail("noWebGLErrors", {drained: g.drained, drainCalls: g.drainCalls, getErrors: g.getErrors, lost: g.lost, intentionalLost: g.intentionalLost, glConsole: glConsole.slice(0, 10)});
  }
  exceptions.length === 0 ? pass("noUncaughtExceptions", {}) : fail("noUncaughtExceptions", {count: exceptions.length, first: exceptions.slice(0, 3)});
  // Synchronous GL calls per presented frame = 0 in the world: enforced on the WebAssembly GC
  // runtime; the frozen JavaScript fallback is reported only.
  if (report.glFrames) {
    let runtime = null;
    try { runtime = (await evaluate(cdp, RUNTIME_TELEMETRY_EXPRESSION))?.runtime || null; } catch {}
    const syncFailures = glFrameGuardFailures(report.glFrames, "world");
    const detail = {runtime, uniqueFps: report.glFrames.uniqueFps, rafFps: report.glFrames.rafFps, gameFps: report.glFrames.gameFps,
      syncPerFrame: report.glFrames.syncPerFrame, syncByName: report.glFrames.syncByName};
    if (runtime !== "wasm") pass("noSyncGlCalls", {reportedOnly: true, ...detail});
    else syncFailures.length === 0 ? pass("noSyncGlCalls", detail) : fail("noSyncGlCalls", {failures: syncFailures, ...detail});
  }
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  report.failed = failed;
  report.verdict = !report.error && failed.length === 0 ? "PASS" : "FAIL";
  report.totalSeconds = Math.round((Date.now() - t0) / 1000);
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  try { if (cdp) await cdp.send("Browser.close"); } catch {}
  await sleep(1500);
  if (chrome.exitCode === null && chrome.signalCode === null) {
    try {
      if (process.platform === "win32") execFileSync("taskkill.exe", ["/PID", String(chrome.pid), "/T", "/F"], {windowsHide: true, timeout: 10000, stdio: "pipe"});
      else chrome.kill("SIGKILL");
    } catch {}
  }
  if (server) server.close();
  await sleep(1500);
  const profileRelative = relative(out, resolve(profileDir));
  if (!profileRelative || isAbsolute(profileRelative) || profileRelative === ".." || profileRelative.startsWith(".." + (process.platform === "win32" ? "\\" : "/"))) {
    throw new Error("Chrome profile cleanup escaped the output directory");
  }
  await rm(profileDir, {recursive: true, force: true}).catch(() => {});
  console.log(JSON.stringify({verdict: report.verdict, profile, bootSeconds: report.bootSeconds, phases: report.phases, failed, error: report.error?.split("\n")[0],
    checks: Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.ok]))}));
  process.exit(report.verdict === "PASS" ? 0 : 1);
}

