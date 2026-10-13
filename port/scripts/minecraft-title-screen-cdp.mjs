// Title-screen acceptance (migration milestone M3) for a Gaius browser client in headless Chrome.
//
//   DIST=port/target/26.3/<dist> node port/scripts/minecraft-title-screen-cdp.mjs   (served on 127.0.0.1)
//   ARTIFACT=path/to/Gaius-26.2.html node port/scripts/minecraft-title-screen-cdp.mjs (portable, file://)
//   M3_SELFTEST=1 PAGE_URL=about:blank node port/scripts/minecraft-title-screen-cdp.mjs  (harness self-test)
//   ... --runtime js|wasm   (or GAIUS_RUNTIME) the runtime the page must run: with DIST it picks that
//       runtime's page (index-wasm.html or index.html?runtime=wasm), with ARTIFACT_DIST=<dir> that
//       runtime's portable page; the run fails when the page reports another runtime.
//   ... --gpu webgpu|webgl2   (or GAIUS_GPU) the graphics backend: adds ?gpu= to the page and fails
//       unless the game renders with it (window.__gaiusGpuBackend); GAIUS_GPU_EXPECT names another
//       expected backend (the fallback tests: ?gpuInject=after-configure must end on webgl2), and
//       PAGE_QUERY appends more switches (gpuInject=..., gpuPipelines=eager, ...). With webgpu the
//       WebGPU glue's errors, translation and pipeline failures fail the run (noWebGpuErrors) and
//       its startup and pipeline telemetry goes to report.gpu.
//
// Checks, in order: boot to the first screen (the first-run Edit Profile screen or TitleScreen,
// once the resource LoadingOverlay is gone), TitleScreen widgets, frame pacing, Options,
// Multiplayer -> Direct Connect typing (the Join Server button turns active), Edit Profile from
// the title button (26.2 and later; 1.21.11 has no such button), no shader compile/link failures,
// no WebGL errors (getError is drained at ~1 Hz; contexts the page loses on purpose through
// WEBGL_lose_context, such as the quality benchmark's probe, are left out), the requested runtime
// and no runtime failure (bench/harness-common.mjs: TeaVM suspension guards on js, failed Java
// threads on wasm, boot errors on both). Chrome runs isolated (bench/harness-common.mjs: no
// background traffic, non-loopback requests refused). Evidence goes to OUT
// (report.json, console.log with page and Worker console, screenshots). Exit 0 only on PASS.
// Validated on the released 26.2 client (all checks PASS) and by M3_SELFTEST (a bad GL enum and
// a broken shader are both reported).
import {LEGACY_PROFILE} from "./verification-profiles.mjs";
import {spawn} from "node:child_process";
import {createReadStream, statSync, existsSync} from "node:fs";
import {mkdtemp, mkdir, writeFile, rm, appendFile} from "node:fs/promises";
import {createServer as netServer} from "node:net";
import {createServer as httpServer} from "node:http";
import {tmpdir} from "node:os";
import {basename, resolve, join, extname, normalize, relative, isAbsolute} from "node:path";
import {pathToFileURL} from "node:url";
import {chromeIsolationArgs, fileArtifactForRuntime, pageForRuntime, RUNTIME_TELEMETRY_EXPRESSION, runtimeArgument,
  runtimeTelemetryFailures} from "./bench/harness-common.mjs";
import {GL_FRAME_GUARD_SOURCE, GL_FRAME_GUARD_WINDOW_EXPRESSION, glFrameGuardFailures} from "../../tools/gl-frame-guard.mjs";

const out = resolve(process.env.OUT || "m3-out");
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const bootTimeout = Number(process.env.BOOT_TIMEOUT_MS || 900000);
const requestedRuntime = runtimeArgument();
// --gpu webgpu|webgl2 (or GAIUS_GPU); null when neither is given.
function gpuArgument(argv = process.argv, env = process.env) {
  const index = argv.indexOf("--gpu");
  const inline = argv.find(value => value.startsWith("--gpu="));
  const value = index >= 0 ? argv[index + 1] : inline ? inline.slice("--gpu=".length) : env.GAIUS_GPU || null;
  if (value == null || value === "") return null;
  if (value !== "webgpu" && value !== "webgl2") throw new Error(`--gpu must be webgpu or webgl2, not ${value}`);
  return value;
}
const requestedGpu = gpuArgument();
const expectedGpu = process.env.GAIUS_GPU_EXPECT || requestedGpu;
// The page URL with the graphics switches: ?gpu= and PAGE_QUERY ("a=1&b=2").
function withPageSwitches(url) {
  const extra = new URLSearchParams(process.env.PAGE_QUERY || "");
  if (requestedGpu) extra.set("gpu", requestedGpu);
  if ([...extra.keys()].length === 0) return url;
  const parsed = new URL(url);
  for (const [key, value] of extra) parsed.searchParams.set(key, value);
  return parsed.href;
}
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
// Exploration-only JS probe patches: PROBE_PATCHES=file.json [{file, find, replace, label}] are
// applied to the served copy of a dist file (never written to disk); every find must match once.
const probe = new Map();
if (process.env.PROBE_PATCHES) {
  const {readFileSync} = await import("node:fs");
  for (const p of JSON.parse(readFileSync(process.env.PROBE_PATCHES, "utf8"))) {
    if (!probe.has(p.file)) probe.set(p.file, []); probe.get(p.file).push(p);
  }
}
const probeCache = new Map();
function probed(root, name) {
  if (probeCache.has(name)) return probeCache.get(name);
  const {readFileSync} = globalThis.__fs;
  let text = readFileSync(join(root, name), "utf8");
  for (const p of probe.get(name)) {
    const n = text.split(p.find).length - 1;
    if (n !== 1) throw new Error(`probe patch ${p.label} matched ${n} times in ${name}`);
    text = text.replace(p.find, () => p.replace);
    log("probe-patch", `${name}: ${p.label}`);
  }
  const buf = Buffer.from(text, "utf8"); probeCache.set(name, buf); return buf;
}
globalThis.__fs = await import("node:fs");
if (!pageUrl && process.env.DIST) {
  const root = resolve(process.env.DIST);
  for (const name of probe.keys()) probed(root, name);
  server = httpServer((req, res) => {
    const u = new URL(req.url, "http://127.0.0.1");
    let p = normalize(join(root, decodeURIComponent(u.pathname)));
    // Path-relative containment: a plain prefix test would also accept a sibling
    // directory whose name merely starts with the root (e.g. <root>-other).
    const contained = relative(root, p);
    if (contained.startsWith("..") || isAbsolute(contained)) { res.writeHead(403); return res.end(); }
    if (u.pathname === "/") p = join(root, "index.html");
    if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); log("http404", u.pathname); return res.end(); }
    const rel = p.slice(root.length + 1).split(String.fromCharCode(92)).join("/");
    if (probe.has(rel)) { const b = probed(root, rel); res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": b.length, "Cache-Control": "no-store"}); return res.end(b); }
    res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size,
      "Cache-Control": "no-store"});
    createReadStream(p).pipe(res);
  });
  await new Promise(ok => server.listen(0, "127.0.0.1", ok));
  pageUrl = `http://127.0.0.1:${server.address().port}/${pageForRuntime(root, requestedRuntime)}`;
}
if (!pageUrl) {
  const artifact = process.env.ARTIFACT || fileArtifactForRuntime(resolve(process.env.ARTIFACT_DIST), requestedRuntime || "js");
  pageUrl = pathToFileURL(resolve(artifact)).href;
}
if (!process.env.M3_SELFTEST) pageUrl = withPageSwitches(pageUrl);

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = new Map();
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
    const id = this.id++;
    return new Promise((ok, bad) => { this.pending.set(id, {ok, bad}); this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})})); });
  }
}
// A page whose main thread never yields again (a crashed client that keeps it busy) never answers
// Runtime.evaluate: give up after this long instead of hanging the harness (every expression here
// is a state read or a few seconds of frames).
const EVALUATE_TIMEOUT_MS = Number(process.env.GAIUS_CDP_EVALUATE_TIMEOUT_MS) || 60_000;
async function evaluate(cdp, expression, sessionId) {
  let timer;
  const timeout = new Promise((_, bad) => {
    timer = setTimeout(() => bad(new Error(`Runtime.evaluate gave no answer within ${EVALUATE_TIMEOUT_MS} ms `
      + "(page main thread busy)")), EVALUATE_TIMEOUT_MS);
  });
  let r;
  try {
    r = await Promise.race([
      cdp.send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true}, sessionId), timeout]);
  } finally {
    clearTimeout(timer);
  }
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text || "evaluate failed");
  return r.result?.value;
}
async function freePort() {
  const s = netServer(); await new Promise(ok => s.listen(0, "127.0.0.1", ok));
  const p = s.address().port; await new Promise(ok => s.close(ok)); return p;
}
async function clickAt(cdp, x, y) {
  await cdp.send("Input.dispatchMouseEvent", {type: "mouseMoved", x, y, button: "none"});
  await sleep(50);
  await cdp.send("Input.dispatchMouseEvent", {type: "mousePressed", x, y, button: "left", buttons: 1, clickCount: 1});
  await sleep(50);
  await cdp.send("Input.dispatchMouseEvent", {type: "mouseReleased", x, y, button: "left", buttons: 0, clickCount: 1});
}
async function findWidget(cdp, match, ms = 30000) {
  const m = JSON.stringify(match); const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];const m=${m};
      const ok=x=>x&&x.visible!==false;let w=null;
      if(m.type)w=ws.find(x=>ok(x)&&String(x.type||'').includes(m.type));
      else{const n=String(m.text).toLowerCase();w=ws.find(x=>ok(x)&&String(x.text||'').trim().toLowerCase()===n)||ws.find(x=>ok(x)&&String(x.text||'').toLowerCase().includes(n));}
      const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z||!z.width)return null;
      return {text:String(w.text||''),type:String(w.type||''),active:w.active!==false,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`).catch(() => null);
    if (v) return v; await sleep(250);
  }
  return null;
}
async function clickWidget(cdp, match, ms) {
  const w = await findWidget(cdp, typeof match === "string" ? {text: match} : match, ms);
  if (!w) throw new Error(`widget not found: ${JSON.stringify(match)}`);
  await clickAt(cdp, w.x, w.y); return w;
}
async function key(cdp, keyName, code, vk, text, modifiers = 0) {
  await cdp.send("Input.dispatchKeyEvent", {type: "keyDown", key: keyName, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers, ...(text ? {text} : {})});
  await cdp.send("Input.dispatchKeyEvent", {type: "keyUp", key: keyName, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers});
}
async function typeText(cdp, text) {
  for (const ch of text) {
    const upper = ch.toUpperCase();
    const code = /[a-z]/i.test(ch) ? "Key" + upper : /[0-9]/.test(ch) ? "Digit" + ch : ch === "." ? "Period" : ch === ":" ? "Semicolon" : "";
    const vk = /[a-z0-9]/i.test(ch) ? upper.charCodeAt(0) : ch === "." ? 190 : ch === ":" ? 186 : 0;
    await key(cdp, ch, code, vk, ch, ch !== ch.toLowerCase() || ch === ":" ? 8 : 0);
    await sleep(40);
  }
}
async function screenshot(cdp, name) {
  try {
    const {data} = await cdp.send("Page.captureScreenshot", {format: "png"});
    const path = join(out, name); await writeFile(path, Buffer.from(data, "base64")); return path;
  } catch (e) { await log("shot-fail", name + " " + e); return null; }
}

// Injected before any page script: WebGL instrumentation. A context that the page loses on
// purpose through WEBGL_lose_context.loseContext() (the quality layer's GPU benchmark probe,
// runtime/quality/gpu-caps.js) is not a WebGL failure: its loss, its CONTEXT_LOST_WEBGL error and
// its later getError results are counted apart (intentional*) and it is no longer drained.
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
  };
  wrap(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
  wrap(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
  const gc = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(kind, attrs){ const c = gc.call(this, kind, attrs); if (c && /webgl/.test(kind) && !rec.gls.includes(c)) { rec.contexts++; rec.gls.push(c); rec.created.push(kind); this.addEventListener('webglcontextlost', ()=>{ if (intentional.has(c)) rec.intentionalLost++; else rec.lost++; }); } return c; };
  // Active WebGL error sampling: GL error flags are sticky until getError reads them, so
  // draining at ~1 Hz observes every error code raised since the previous drain.
  window.__m3drain = () => { rec.drainCalls++; for (const gl of rec.gls) { if (intentional.has(gl)) continue; for (let i = 0; i < 16; i++) { const e = gse0.call(gl); if (!e) break; const k = names[e]||String(e); rec.drained[k]=(rec.drained[k]||0)+1; } } return rec.drained; };
  window.__m3audit = () => {
    const bad = {shaders:[], programs:[]}; let shaders=0, programs=0;
    for (const {gl,s,type} of rec.shaders) { try { if (gl.isShader(s)) { shaders++; if (gl.getShaderParameter(s,0x8B81)===false) bad.shaders.push({type, log:String(gl.getShaderInfoLog(s)).slice(0,1500)}); } } catch(e){} }
    for (const {gl,p} of rec.programs) { try { if (gl.isProgram(p)) { programs++; if (gl.getProgramParameter(p,0x8B82)===false) bad.programs.push(String(gl.getProgramInfoLog(p)).slice(0,1500)); } } catch(e){} }
    return {contexts:rec.contexts, created:rec.created, lost:rec.lost, intentionalLossRequests:rec.intentionalLossRequests, intentionalLost:rec.intentionalLost, intentionalErrors:rec.intentionalErrors, liveShaders:shaders, livePrograms:programs, shadersCreated:rec.shaders.length, programsCreated:rec.programs.length,
      badShaders:bad.shaders.slice(0,20), badPrograms:bad.programs.slice(0,20), shaderFailures:rec.shaderFailures.slice(0,20), programFailures:rec.programFailures.slice(0,20), getErrors:rec.getErrors, getErrorCalls:rec.getErrorCalls, drained:(window.__m3drain(), rec.drained), drainCalls:rec.drainCalls};
  };
})();`;

const stateExpr = `(()=>{const s=window.__gaiusMinecraftState||null;const st=document.querySelector('[data-state]');
  const bp=[...document.querySelectorAll('#boot-screen,[id*=boot],[id*=status]')].filter(e=>!e.hidden).map(e=>({id:e.id,text:String(e.textContent||'').trim().slice(0,400)})).filter(e=>e.text);
  return {screen:s?String(s.screen||''):null,overlay:s?String(s.overlay||''):null,profile:typeof window.__gaiusProfileId==='string'?window.__gaiusProfileId:null,
    failure:(window.__gaiusThreadError||window.__gaiusBootError)?String((window.__gaiusThreadError||window.__gaiusBootError).stack||window.__gaiusThreadError||window.__gaiusBootError).slice(0,2000):null,screenTitle:s?s.screenTitle:null,screenSize:s?s.screenSize:null,widgets:s&&Array.isArray(s.screenWidgets)?s.screenWidgets.map(w=>({type:String(w.type||'').split('.').pop(),text:w.text,active:w.active,visible:w.visible,focused:w.focused})):null,
    statusState:st?st.dataset.state:null,boot:bp,fps:window.__gaiusFps?{fps:window.__gaiusFps.fps,rafFps:window.__gaiusFps.rafFps,gameFps:window.__gaiusFps.gameFps,frames:window.__gaiusFps.frames,gameFrames:window.__gaiusFps.gameFrames}:null,
    keys:s?Object.keys(s).slice(0,60):null};})()`;
const screenExpr = "String(window.__gaiusMinecraftState?.screen||'')";

const report = {pageUrl, requestedRuntime, requestedGpu, expectedGpu, startedAt: new Date().toISOString(), checks: {}, timeline: []};
// What the page offered (__gaiusGpuSelection), what the game renders with (__gaiusGpuBackend), the
// canvas host's replacements and the WebGPU glue's state, errors and startup/pipeline telemetry.
const GPU_EXPRESSION = `(()=>{const g=globalThis;const s=g.__gaiusGpuSelection||null;const w=g.GaiusWebGpu||null;const h=g.__gaiusCanvasHost||null;
  const pick=o=>o?JSON.parse(JSON.stringify(o)):null;
  return {selection:s?{requested:s.requested,source:s.source,selected:s.selected,fallback:s.fallback,reason:s.reason,inject:s.inject,initMs:s.initMs}:null,
    backend:typeof g.__gaiusGpuBackend==='string'?g.__gaiusGpuBackend:null,
    canvas:h?{replacements:h.replacements,lastReason:h.lastReason,kind:(h.current()&&h.current().__gaiusContextKind)||null}:null,
    webgpu:w?{status:w.status,failure:w.failure||'',lost:w.lostReason||'',active:w.active===true,adapter:pick(w.adapterInfo),canvasFormat:w.canvasFormat||null,
      stats:pick(w.stats),pipelines:typeof w.pipelineTelemetry==='function'?pick(w.pipelineTelemetry()):null,messages:(w.messages||[]).slice(0,40)}:null};})()`;
const pass = (n, d) => { report.checks[n] = {ok: true, detail: d}; log("CHECK", `PASS ${n} ${JSON.stringify(d).slice(0, 300)}`); };
const skip = (n, why) => { report.checks[n] = {ok: true, skipped: true, detail: why}; log("CHECK", `SKIP ${n} ${why}`); };
const fail = (n, d) => { report.checks[n] = {ok: false, detail: d}; log("CHECK", `FAIL ${n} ${JSON.stringify(d).slice(0, 300)}`); };
const profileDir = await mkdtemp(join(tmpdir(), "gaius-m3-"));
const port = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore"});
let cdp;
const exceptions = [];
try {
  let targets;
  for (let i = 0; i < 80; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.length) break; } catch {} await sleep(250); }
  cdp = new Cdp(targets.find(t => t.type === "page").webSocketDebuggerUrl); await cdp.open();
  const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
  cdp.on("Runtime.consoleAPICalled", (p, sid) => log(`console.${p.type}${sid ? "[worker]" : ""}`, fmtArgs(p.args).slice(0, 4000)));
  cdp.on("Runtime.exceptionThrown", (p, sid) => { const d = p.exceptionDetails; const t = (d.exception?.description || d.text || "").slice(0, 4000); exceptions.push({sid: !!sid, t}); log(`EXCEPTION${sid ? "[worker]" : ""}`, t); });
  cdp.on("Log.entryAdded", (p, sid) => log(`log.${p.entry.level}${sid ? "[worker]" : ""}`, `${p.entry.source} ${String(p.entry.text).slice(0, 2000)} ${p.entry.url || ""}`));
  cdp.on("Target.attachedToTarget", async (p) => {
    await log("target", `${p.targetInfo.type} ${p.targetInfo.url}`);
    try { await cdp.send("Runtime.enable", {}, p.sessionId); await cdp.send("Log.enable", {}, p.sessionId);
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, p.sessionId);
      await cdp.send("Runtime.runIfWaitingForDebugger", {}, p.sessionId); } catch (e) { log("target-err", String(e)); }
  });
  await cdp.send("Runtime.enable"); await cdp.send("Page.enable"); await cdp.send("Log.enable");
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: glProbe});
  // After glProbe, so its ~1 Hz getError drain (which calls the unwrapped method) is not counted.
  if (!process.env.M3_SELFTEST) await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: GL_FRAME_GUARD_SOURCE});
  if (process.env.M3_SELFTEST) {
    // Harness self-test: a WebGL2 page with one bad call and one broken shader must be caught, and
    // a second context lost on purpose (as the quality benchmark probe does) must not count.
    const html = "<canvas id=c></canvas><canvas id=p></canvas><script>const gl=document.getElementById('c').getContext('webgl2');gl.bindBuffer(0x1234,null);const s=gl.createShader(gl.FRAGMENT_SHADER);gl.shaderSource(s,'#version 300 es'+String.fromCharCode(10)+'void main(){ nope; }');gl.compileShader(s);const probe=document.getElementById('p').getContext('webgl2');probe.getExtension('WEBGL_lose_context').loseContext();</script>";
    await cdp.send("Page.navigate", {url: "data:text/html," + encodeURIComponent(html)});
    await sleep(1500);
    const a = await evaluate(cdp, "window.__m3audit()");
    const ok = (a.drained.INVALID_ENUM || 0) >= 1 && a.badShaders.length === 1 && a.lost === 0 && a.intentionalLost === 1
      && !a.drained.CONTEXT_LOST_WEBGL;
    console.log(JSON.stringify({selftest: ok ? "PASS" : "FAIL", drained: a.drained, lost: a.lost, intentionalLost: a.intentionalLost,
      badShaders: a.badShaders.map(b => b.log.slice(0, 80))}));
    try { chrome.kill(); } catch {}
    if (server) server.close();
    process.exit(ok ? 0 : 1);
  }
  await log("navigate", pageUrl);
  await cdp.send("Page.navigate", {url: pageUrl});

  // Phase 1: boot timeline.
  let last = "", lastShot = 0, shotN = 0, reached = null;
  const bootEnd = Date.now() + bootTimeout;
  while (Date.now() < bootEnd) {
    await evaluate(cdp, 'window.__m3drain && window.__m3drain()').catch(() => null);
    let s = null; try { s = await evaluate(cdp, stateExpr); } catch (e) { s = {err: String(e).slice(0, 200)}; }
    const sig = JSON.stringify([s?.screen, s?.statusState, s?.boot?.map(b => b.text.slice(0, 120)), s?.widgets?.length]);
    if (sig !== last) { last = sig; report.timeline.push({t: stamp(), ...s}); await log("state", JSON.stringify({screen: s?.screen, statusState: s?.statusState, boot: s?.boot, widgets: s?.widgets?.length}).slice(0, 1500)); }
    if (Date.now() - lastShot > 45000) { lastShot = Date.now(); await screenshot(cdp, `boot-${String(++shotN).padStart(2, "0")}.png`); }
    // The first-run Edit Profile screen opens on the first title tick, which only runs once the
    // resource LoadingOverlay is gone; until then the title screen is not interactive.
    if (s?.screen && /TitleScreen|BrowserProfileScreen/.test(s.screen) && !/LoadingOverlay/.test(s.overlay || "")) {
      reached = s.screen;
      // Page time of the first interactive screen (polled every 250 ms), comparable across runtimes.
      report.firstScreenMillis = await evaluate(cdp, "performance.now()").catch(() => null);
      break;
    }
    if (s?.failure) { report.bootError = s; await log("runtime-failure", s.failure.slice(0, 1500)); break; }
    if (logLines.some(l => l.includes("Game crashed!"))) { await sleep(3000); report.crashed = true; report.bootError = await evaluate(cdp, stateExpr).catch(() => null); break; }
    if (s?.statusState === "error") { await sleep(5000); report.bootError = await evaluate(cdp, stateExpr).catch(() => null); break; }
    await sleep(250);
  }
  report.bootSeconds = Math.round((Date.now() - t0) / 1000);
  report.reached = reached;
  await sleep(3000);
  await screenshot(cdp, "10-first-screen.png");
  report.firstScreen = await evaluate(cdp, stateExpr).catch(e => String(e));
  if (!reached) throw new Error("title screen not reached: " + JSON.stringify(report.firstScreen).slice(0, 800));
  await evaluate(cdp, 'window.__m3drainTimer = setInterval(() => window.__m3drain && window.__m3drain(), 1000)');
  pass("bootsToFirstScreen", {screen: reached, seconds: report.bootSeconds});

  // First run opens the in-game Edit Profile screen over the title screen (26.2 behaviour). Leave it.
  if (reached.endsWith("BrowserProfileScreen")) {
    pass("firstRunProfileScreen", report.firstScreen.widgets);
    await clickWidget(cdp, "Done", 10000).catch(async () => { await key(cdp, "Escape", "Escape", 27); });
    const end = Date.now() + 20000;
    while (Date.now() < end && !(await evaluate(cdp, screenExpr)).endsWith("TitleScreen")) await sleep(250);
  }
  await sleep(1500);
  const title = await evaluate(cdp, stateExpr);
  report.title = title;
  await screenshot(cdp, "11-title.png");
  const texts = (title.widgets || []).map(w => String(w.text || ""));
  const need = ["Singleplayer", "Multiplayer", "Options", "Quit Game"];
  const missing = need.filter(n => !texts.some(t => t.includes(n)));
  title.screen.endsWith("TitleScreen") && missing.length === 0 ? pass("titleWidgets", texts) : fail("titleWidgets", {screen: title.screen, texts, missing});

  // Frame pacing on the title screen.
  const f0 = await evaluate(cdp, "({t:performance.now(),f:window.__gaiusFps?.frames||0,g:window.__gaiusFps?.gameFrames||0})");
  const raf = await evaluate(cdp, `new Promise(ok=>{const ts=[];const step=t=>{ts.push(t);if(ts.length<181)requestAnimationFrame(step);else{const d=ts.slice(1).map((x,i)=>x-ts[i]).sort((a,b)=>a-b);ok({n:d.length,p50:d[d.length>>1],p95:d[Math.floor(d.length*0.95)],max:d[d.length-1],mean:(ts[ts.length-1]-ts[0])/d.length});}};requestAnimationFrame(step);})`);
  const f1 = await evaluate(cdp, "({t:performance.now(),f:window.__gaiusFps?.frames||0,g:window.__gaiusFps?.gameFrames||0,fps:window.__gaiusFps?.fps,gameFps:window.__gaiusFps?.gameFps,rafFps:window.__gaiusFps?.rafFps})");
  const secs = (f1.t - f0.t) / 1000;
  report.pacing = {raf, seconds: secs, gameFramesPerSec: (f1.g - f0.g) / secs, framesPerSec: (f1.f - f0.f) / secs, reported: f1};
  raf.p50 < 34 && raf.p95 < 50 && (f1.fps || 0) >= 30 ? pass("framePacing", report.pacing) : fail("framePacing", report.pacing);
  // Synchronous GL calls and unique presented frames on the title screen (tools/gl-frame-guard.mjs);
  // judged in the summary once the runtime is known.
  report.glFrames = await evaluate(cdp, GL_FRAME_GUARD_WINDOW_EXPRESSION(3000)).catch(e => ({installed: false, error: String(e)}));
  await log("gl-frames", JSON.stringify(report.glFrames).slice(0, 600));

  // Options.
  await clickWidget(cdp, "Options...", 10000).catch(() => clickWidget(cdp, "Options", 10000));
  let end = Date.now() + 15000; let sc = "";
  while (Date.now() < end && !/OptionsScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  await sleep(1200); await screenshot(cdp, "12-options.png");
  const opt = await evaluate(cdp, stateExpr); report.options = opt;
  /OptionsScreen/.test(sc) ? pass("optionsOpens", {screen: sc, widgets: (opt.widgets || []).map(w => w.text)}) : fail("optionsOpens", {screen: sc});
  await clickWidget(cdp, "Done", 5000).catch(() => key(cdp, "Escape", "Escape", 27));
  end = Date.now() + 15000; while (Date.now() < end && !(await evaluate(cdp, screenExpr)).endsWith("TitleScreen")) await sleep(250);

  // Multiplayer -> Direct Connect -> type.
  await clickWidget(cdp, "Multiplayer", 10000);
  end = Date.now() + 20000;
  while (Date.now() < end && !/JoinMultiplayerScreen|MultiplayerWarningScreen|SafetyScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  await sleep(800);
  if (/Warning|Safety/.test(sc)) {
    await screenshot(cdp, "13-mp-warning.png");
    await clickWidget(cdp, "Proceed", 5000).catch(() => clickWidget(cdp, "Continue", 5000));
    end = Date.now() + 15000; while (Date.now() < end && !/JoinMultiplayerScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  }
  await sleep(1000); await screenshot(cdp, "14-multiplayer.png");
  report.multiplayer = await evaluate(cdp, stateExpr);
  /JoinMultiplayerScreen/.test(sc) ? pass("multiplayerOpens", {screen: sc}) : fail("multiplayerOpens", {screen: sc, st: report.multiplayer});
  await clickWidget(cdp, "Direct Connect", 10000);
  end = Date.now() + 15000; while (Date.now() < end && !/DirectJoinServerScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  await sleep(800);
  const before = await evaluate(cdp, stateExpr);
  const edit = await findWidget(cdp, {type: "EditBox"}, 5000);
  if (edit) await clickAt(cdp, edit.x, edit.y);
  await sleep(300);
  await key(cdp, "a", "KeyA", 65, "", 2); await key(cdp, "Backspace", "Backspace", 8);
  await typeText(cdp, "example.org:25565");
  await sleep(1000);
  await screenshot(cdp, "15-direct-connect-typed.png");
  const after = await evaluate(cdp, stateExpr);
  report.directConnect = {before, after};
  const joinBefore = (before.widgets || []).find(w => /Join Server|Connect/i.test(String(w.text)));
  const joinAfter = (after.widgets || []).find(w => /Join Server|Connect/i.test(String(w.text)));
  /DirectJoinServerScreen/.test(sc) && edit && joinAfter && joinAfter.active === true && joinBefore && joinBefore.active === false
    ? pass("directConnectTyping", {joinBefore, joinAfter, editFocused: (after.widgets || []).find(w => w.type === "EditBox")})
    : fail("directConnectTyping", {screen: sc, edit, joinBefore, joinAfter});
  await clickWidget(cdp, "Cancel", 5000).catch(() => key(cdp, "Escape", "Escape", 27));
  await sleep(800);
  await clickWidget(cdp, "Back", 5000).catch(() => key(cdp, "Escape", "Escape", 27));
  end = Date.now() + 15000; while (Date.now() < end && !(await evaluate(cdp, screenExpr)).endsWith("TitleScreen")) await sleep(250);

  // Edit Profile from the title button. MinecraftClientPatcher adds the button to the 26.2 and
  // later title screens only.
  const profileId = title.profile || basename(process.env.GAIUS_VERSION_PROFILE_PATH || "").replace(/[.]json$/, "");
  const btn = profileId === LEGACY_PROFILE.id ? null : await findWidget(cdp, {text: "Edit Profile"}, 10000);
  if (profileId === LEGACY_PROFILE.id) {
    skip("editProfileOpens", "" + LEGACY_PROFILE.id + " has no Edit Profile button on the title screen");
  } else if (btn) {
    await clickAt(cdp, btn.x, btn.y);
    end = Date.now() + 15000; while (Date.now() < end && !/BrowserProfileScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
    await sleep(1200); await screenshot(cdp, "16-edit-profile.png");
    const pr = await evaluate(cdp, stateExpr); report.profile = pr;
    /BrowserProfileScreen/.test(sc) ? pass("editProfileOpens", (pr.widgets || []).map(w => w.text)) : fail("editProfileOpens", {screen: sc});
    await clickWidget(cdp, "Cancel", 5000).catch(() => key(cdp, "Escape", "Escape", 27));
  } else fail("editProfileOpens", "no Edit Profile button");
  await sleep(1500);
} catch (e) {
  report.error = String(e.stack || e);
  await log("HARNESS-ERROR", report.error);
  try { await screenshot(cdp, "error.png"); report.errorState = await evaluate(cdp, stateExpr); } catch {}
} finally {
  try { report.gl = await evaluate(cdp, "window.__m3audit ? window.__m3audit() : null"); } catch (e) { report.gl = String(e); }
  // Runtime-neutral telemetry (bench/harness-common.mjs): which runtime ran, its boot phases and
  // memory, and the failures either runtime can report.
  try { report.runtimeTelemetry = await evaluate(cdp, RUNTIME_TELEMETRY_EXPRESSION); } catch (e) { report.runtimeTelemetry = {error: String(e)}; }
  try { report.gpu = await evaluate(cdp, GPU_EXPRESSION); } catch (e) { report.gpu = {error: String(e)}; }
  if (report.runtimeTelemetry && !report.runtimeTelemetry.error) {
    const runtimeFailures = runtimeTelemetryFailures(report.runtimeTelemetry, "page");
    runtimeFailures.length === 0 ? pass("noRuntimeFailures", {runtime: report.runtimeTelemetry.runtime}) : fail("noRuntimeFailures", runtimeFailures);
    if (requestedRuntime) {
      report.runtimeTelemetry.runtime === requestedRuntime ? pass("runtimeMatches", {requested: requestedRuntime})
        : fail("runtimeMatches", {requested: requestedRuntime, observed: report.runtimeTelemetry.runtime});
    }
  } else if (requestedRuntime) {
    fail("runtimeMatches", {requested: requestedRuntime, observed: null, error: report.runtimeTelemetry?.error});
  }
  const glConsole = logLines.filter(l => /GL_INVALID|WebGL: |INVALID_OPERATION|INVALID_ENUM|INVALID_VALUE|INVALID_FRAMEBUFFER|CONTEXT_LOST|GL ERROR|GL error/.test(l));
  // Pipelines vanilla rejects because the device reports no wireframe fill mode
  // (RenderPatches263.patchWireframeUnavailable) are a capability decision, not a compile error.
  const wireframeOnly = l => /WIREFRAME fill mode, not supported by device/.test(l)
    || (/Failed to load optional shader programs/.test(l)
        && (l.match(/ - \S+/g) || []).every(x => /^ - minecraft:pipeline\/wireframe(_multidraw)?$/.test(x)));
  report.expectedPipelineRejections = logLines.filter(l => wireframeOnly(l));
  const shaderConsole = logLines.filter(l => !wireframeOnly(l)
    && !(/\[Gaius reload\]\s+listener=Shader Loader\b/i.test(l) && /\bfailed=false\b/i.test(l))
    && ((/shader|glsl|spir|pipeline|program/i.test(l) && /error|fail|couldn't|could not|invalid/i.test(l)) || /compil\w* (error|fail)/i.test(l)));
  report.glConsole = glConsole.slice(0, 50); report.shaderConsole = shaderConsole.slice(0, 50);
  report.exceptions = exceptions.slice(0, 50);
  const g = report.gl && typeof report.gl === "object" ? report.gl : null;
  if (g) {
    const errs = Object.values(g.getErrors || {}).reduce((a, b) => a + b, 0);
    g.badShaders.length + g.badPrograms.length + g.shaderFailures.length + g.programFailures.length === 0 && shaderConsole.length === 0
      ? pass("noShaderErrors", {live: g.liveShaders, programs: g.livePrograms}) : fail("noShaderErrors", {g, shaderConsole: shaderConsole.slice(0, 5)});
    const drained = Object.values(g.drained || {}).reduce((a, b) => a + b, 0);
    errs === 0 && drained === 0 && glConsole.length === 0 && g.lost === 0 && g.drainCalls > 10
      ? pass("noWebGLErrors", {getErrorCalls: g.getErrorCalls, drainCalls: g.drainCalls, intentionalLost: g.intentionalLost})
      : fail("noWebGLErrors", {drained: g.drained, drainCalls: g.drainCalls, getErrors: g.getErrors, lost: g.lost, intentionalLost: g.intentionalLost, glConsole: glConsole.slice(0, 10)});
  }
  // Synchronous GL calls per presented frame = 0: enforced on the WebAssembly GC runtime; the
  // frozen JavaScript fallback is reported only (it predates the GL emulation fixes).
  if (report.glFrames) {
    const syncFailures = glFrameGuardFailures(report.glFrames, "title");
    const detail = {uniqueFps: report.glFrames.uniqueFps, rafFps: report.glFrames.rafFps, gameFps: report.glFrames.gameFps,
      syncPerFrame: report.glFrames.syncPerFrame, syncByName: report.glFrames.syncByName};
    if (report.runtimeTelemetry?.runtime !== "wasm") skip("noSyncGlCalls", "reported only on the js runtime: " + JSON.stringify(detail));
    else syncFailures.length === 0 ? pass("noSyncGlCalls", detail) : fail("noSyncGlCalls", {failures: syncFailures, ...detail});
  }
  // After the console scans above: these checks log the glue's pipeline telemetry.
  if (expectedGpu) {
    const backend = report.gpu && report.gpu.backend;
    backend === expectedGpu ? pass("gpuMatches", {requested: requestedGpu, expected: expectedGpu, backend, selection: report.gpu.selection, canvas: report.gpu.canvas})
      : fail("gpuMatches", {requested: requestedGpu, expected: expectedGpu, backend, selection: report.gpu && report.gpu.selection, webgpu: report.gpu && report.gpu.webgpu && {status: report.gpu.webgpu.status, failure: report.gpu.webgpu.failure}});
  }
  if (expectedGpu === "webgpu") {
    const w = report.gpu && report.gpu.webgpu;
    const st = (w && w.stats) || {};
    const problems = !w ? ["no WebGPU glue"] : [
      w.lost ? "device lost: " + w.lost : null,
      st.errors ? st.errors + " WebGPU errors" : null,
      st.translateErrors ? st.translateErrors + " translation errors" : null,
      st.pipelineFailures ? st.pipelineFailures + " pipeline failures" : null,
      st.skippedDraws ? st.skippedDraws + " draws skipped on invalid pipelines" : null,
    ].filter(Boolean);
    problems.length === 0 ? pass("noWebGpuErrors", {draws: st.draws, submits: st.submits, pipelines: w.pipelines})
      : fail("noWebGpuErrors", {problems, messages: w && w.messages});
  }
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  report.failed = failed;
  report.verdict = !report.error && failed.length === 0 ? "PASS" : "FAIL";
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  try { chrome.kill(); } catch {}
  if (server) server.close();
  await sleep(1500); await rm(profileDir, {recursive: true, force: true}).catch(() => {});
  console.log(JSON.stringify({verdict: report.verdict, runtime: report.runtimeTelemetry?.runtime || null, requestedRuntime,
    gpu: report.gpu?.backend || null, requestedGpu,
    reached: report.reached, bootSeconds: report.bootSeconds, firstScreenMillis: report.firstScreenMillis, failed, error: report.error?.split("\n")[0],
    checks: Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.skipped ? "skipped" : v.ok]))}));
  process.exit(report.verdict === "PASS" ? 0 : 1);
}
