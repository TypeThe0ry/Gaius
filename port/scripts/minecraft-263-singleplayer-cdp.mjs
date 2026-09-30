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
// Env: SEED (default 26300), PROFILE (26.2|26.3, only picks the daylight gamerule name),
// BOOT_TIMEOUT_MS (900000), WORLD_TIMEOUT_MS (420000), SETTLE_S (60), PLAYER_NAME (M4Check),
// REENTER=same|reload (reload the page before re-entering the world; the portable 26.2 page
// transfers its embedded Worker payload once and cannot start a second Worker in one session),
// WORKER_EXC_DEBUG=1 (pause the Worker on every exception and record the JS call stack plus
// source context of native TypeErrors into report.workerExceptions; slows the Worker down).
import {spawn, execFileSync} from "node:child_process";
import {createReadStream, statSync, existsSync} from "node:fs";
import {mkdtemp, mkdir, writeFile, rm, appendFile} from "node:fs/promises";
import {createServer as netServer} from "node:net";
import {createServer as httpServer} from "node:http";
import {tmpdir} from "node:os";
import {resolve, join, extname, normalize} from "node:path";
import {pathToFileURL} from "node:url";

const out = resolve(process.env.OUT || "m4-out");
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const bootTimeout = Number(process.env.BOOT_TIMEOUT_MS || 900000);
const worldTimeout = Number(process.env.WORLD_TIMEOUT_MS || 420000);
const settleMs = Number(process.env.SETTLE_S || 60) * 1000;
const seed = String(process.env.SEED || "26300");
const profile = process.env.PROFILE || (process.env.DIST && /26\.3/.test(process.env.DIST) ? "26.3" : "26.2");
const playerName = process.env.PLAYER_NAME || "M4Check";
const reenterMode = process.env.REENTER || "same";
const workerExcDebug = process.env.WORKER_EXC_DEBUG === "1";
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
if (!pageUrl && process.env.DIST) {
  const root = resolve(process.env.DIST);
  server = httpServer((req, res) => {
    const u = new URL(req.url, "http://127.0.0.1");
    let p = normalize(join(root, decodeURIComponent(u.pathname)));
    if (!p.startsWith(root)) { res.writeHead(403); return res.end(); }
    if (u.pathname === "/") p = join(root, "index.html");
    if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); log("http404", u.pathname); return res.end(); }
    res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size, "Cache-Control": "no-store"});
    createReadStream(p).pipe(res);
  });
  await new Promise(ok => server.listen(0, "127.0.0.1", ok));
  pageUrl = `http://127.0.0.1:${server.address().port}/index.html`;
}
if (!pageUrl) pageUrl = pathToFileURL(resolve(process.env.ARTIFACT)).href;

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
const glProbe = `(()=>{
  const rec = window.__m3gl = {gls:[], drained:{}, drainCalls:0, contexts:0, shaderFailures:[], programFailures:[], getErrors:{}, getErrorCalls:0, shaders:[], programs:[], lost:0, created:[]};
  const names = {1280:'INVALID_ENUM',1281:'INVALID_VALUE',1282:'INVALID_OPERATION',1285:'OUT_OF_MEMORY',1286:'INVALID_FRAMEBUFFER_OPERATION',37442:'CONTEXT_LOST_WEBGL'};
  let gse0 = null;
  const wrap = proto => {
    if (!proto || proto.__m3) return; proto.__m3 = true;
    const gse = proto.getError; if (!gse0 && proto === (window.WebGL2RenderingContext && WebGL2RenderingContext.prototype)) gse0 = gse; proto.getError = function(){ const e = gse.call(this); rec.getErrorCalls++; if (e) { const k = names[e]||String(e); rec.getErrors[k]=(rec.getErrors[k]||0)+1; } return e; };
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
  HTMLCanvasElement.prototype.getContext = function(kind, attrs){ const c = gc.call(this, kind, attrs); if (c && /webgl/.test(kind)) { rec.contexts++; if (!rec.gls.includes(c)) rec.gls.push(c); rec.created.push(kind); this.addEventListener('webglcontextlost', ()=>rec.lost++); } return c; };
  window.__m3drain = () => { rec.drainCalls++; for (const gl of rec.gls) { for (let i = 0; i < 16; i++) { const e = gse0.call(gl); if (!e) break; const k = names[e]||String(e); rec.drained[k]=(rec.drained[k]||0)+1; } } return rec.drained; };
  window.__m3audit = () => {
    const bad = {shaders:[], programs:[]}; let shaders=0, programs=0;
    for (const {gl,s,type} of rec.shaders) { try { if (gl.isShader(s)) { shaders++; if (gl.getShaderParameter(s,0x8B81)===false) bad.shaders.push({type, log:String(gl.getShaderInfoLog(s)).slice(0,1500)}); } } catch(e){} }
    for (const {gl,p} of rec.programs) { try { if (gl.isProgram(p)) { programs++; if (gl.getProgramParameter(p,0x8B82)===false) bad.programs.push(String(gl.getProgramInfoLog(p)).slice(0,1500)); } } catch(e){} }
    return {contexts:rec.contexts, created:rec.created, lost:rec.lost, liveShaders:shaders, livePrograms:programs, shadersCreated:rec.shaders.length, programsCreated:rec.programs.length,
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
const inWorldExpr = "!!window.__gaiusMinecraftState?.level&&!window.__gaiusMinecraftState?.screen&&!!window.__gaiusMinecraftState?.player";

const report = {pageUrl, profile, seed, startedAt: new Date().toISOString(), checks: {}, timeline: [], phases: {}, samples: []};
const pass = (n, d) => { report.checks[n] = {ok: true, detail: d}; log("CHECK", `PASS ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const fail = (n, d) => { report.checks[n] = {ok: false, detail: d}; log("CHECK", `FAIL ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const phase = (n, v) => { report.phases[n] = v; return log("phase", `${n} ${JSON.stringify(v).slice(0, 300)}`); };

async function command(cdp, text) {
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

const profileDir = await mkdtemp(join(tmpdir(), "gaius-m4-"));
const port = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--no-first-run", "--no-default-browser-check", "--window-size=1280,720",
  "--disable-background-networking", "--disable-component-update", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  "about:blank"], {stdio: "ignore"});
let cdp;
const exceptions = [];
const workerLines = [];
try {
  let targets;
  for (let i = 0; i < 80; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.length) break; } catch {} await sleep(250); }
  cdp = new Cdp(targets.find(t => t.type === "page").webSocketDebuggerUrl); await cdp.open();
  const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
  cdp.on("Runtime.consoleAPICalled", (p, sid) => { const t = fmtArgs(p.args).slice(0, 4000); if (sid) workerLines.push(t); log(`console.${p.type}${sid ? "[worker]" : ""}`, t); });
  cdp.on("Runtime.exceptionThrown", (p, sid) => { const d = p.exceptionDetails; const t = (d.exception?.description || d.text || "").slice(0, 4000); exceptions.push({sid: !!sid, t, at: stamp()}); log(`EXCEPTION${sid ? "[worker]" : ""}`, t); });
  cdp.on("Log.entryAdded", (p, sid) => log(`log.${p.entry.level}${sid ? "[worker]" : ""}`, `${p.entry.source} ${String(p.entry.text).slice(0, 2000)} ${p.entry.url || ""}`));
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
    try { await cdp.send("Runtime.enable", {}, p.sessionId); await cdp.send("Log.enable", {}, p.sessionId);
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, p.sessionId);
      if (workerExcDebug && p.targetInfo.type === "worker") {
        await cdp.send("Debugger.enable", {maxScriptsCacheSize: 1e9}, p.sessionId);
        await cdp.send("Debugger.setPauseOnExceptions", {state: "all"}, p.sessionId);
        await log("worker-exc-debug", "pause on exceptions enabled");
      }
      await cdp.send("Runtime.runIfWaitingForDebugger", {}, p.sessionId); } catch (e) { log("target-err", String(e)); }
  });
  await cdp.send("Runtime.enable"); await cdp.send("Page.enable"); await cdp.send("Log.enable");
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: glProbe});
  await log("navigate", pageUrl);
  await cdp.send("Page.navigate", {url: pageUrl});

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
  if (!reached) throw new Error("title screen not reached: " + JSON.stringify(report.bootError).slice(0, 800));
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
  // World tab -> seed edit box.
  const worldTab = await findWidget(cdp, {text: "World"}, 3000);
  if (worldTab && worldTab.text.trim().toLowerCase() === "world") await clickAt(cdp, worldTab.x, worldTab.y);
  else { await keyEvent(cdp, "keyDown", "Control", "ControlLeft", 17, "", 2); await key(cdp, "2", "Digit2", 50, "", 2); await keyEvent(cdp, "keyUp", "Control", "ControlLeft", 17, "", 0); }
  await sleep(500);
  const seedPred = `w=>w&&w.visible!==false&&w.active!==false&&(String(w.type||'').endsWith('CreateWorldScreen$WorldTab$1')||(/EditBox$/.test(String(w.type||''))&&/seed/i.test(String(w.text||''))))`;
  await waitFor(cdp, `(window.__gaiusMinecraftState?.screenWidgets||[]).filter(${seedPred}).length===1`, 8000, "seed edit box");
  const seedBox = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const w=(s.screenWidgets||[]).filter(${seedPred})[0];const c=document.querySelector('canvas');const r=c.getBoundingClientRect();const z=s.screenSize;
    return {text:w.text,type:w.type,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`);
  await clickAt(cdp, seedBox.x, seedBox.y);
  await waitFor(cdp, `(window.__gaiusMinecraftState?.screenWidgets||[]).filter(${seedPred})[0]?.focused===true`, 3000, "seed focus").catch(() => log("warn", "seed box focus not observed"));
  await key(cdp, "End", "End", 35);
  for (let i = 0; i < 32; i++) await key(cdp, "Backspace", "Backspace", 8);
  await typeText(cdp, seed);
  await sleep(500);
  await screenshot(cdp, "13-create-world-seed.png");
  report.createScreen = await evaluate(cdp, stateExpr);
  // The seed EditBox stays focused after typing; the typed value itself is verified through the world's seed later if exposed.
  const seedFocused = (report.createScreen.widgets || []).some(w => /EditBox|WorldTab\$1/.test(w.type) && w.focused === true);
  seedFocused ? pass("seedTyped", {seed, focused: true}) : fail("seedTyped", {seed, widgets: report.createScreen.widgets});
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
  if (enteredAt === null) { report.worldEntryState = await evaluate(cdp, stateExpr).catch(() => null); throw new Error("world not entered: " + JSON.stringify(report.worldEntryState).slice(0, 600)); }
  await phase("worldEntered", {seconds: enteredAt});
  pass("worldEntered", {seconds: enteredAt, firstLoadedChunkSeconds: firstChunkAt});

  // Phase 4: terrain settle: first rendered terrain, chunk growth, section audit.
  let firstTerrainAt = null, prevLoaded = -1, stableSince = null;
  const settleEnd = Date.now() + settleMs;
  while (Date.now() < settleEnd) {
    const row = await sample(cdp, "spawn", true);
    if (firstTerrainAt === null && row.px && !row.px.error && row.px.other > 0.3) { firstTerrainAt = (Date.now() - createdAt) / 1000; await phase("firstTerrainRendered", {seconds: firstTerrainAt, px: row.px, loaded: row.loaded}); }
    if (row.loaded === prevLoaded && row.audit && row.audit.uncompiled === 0) { if (stableSince === null) stableSince = Date.now(); else if (Date.now() - stableSince > 8000 && firstTerrainAt !== null) break; }
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

  // Phase 5: noon, frozen daylight, top-down shot from above the spawn.
  await command(cdp, profile === "26.3" ? "/gamerule advance_time false" : "/gamerule doDaylightCycle false");
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
  const px2 = Math.floor(after?.x ?? sx) + 0.5, pz2 = Math.floor(after?.z ?? sz) + 0.5;
  await command(cdp, `/tp @s ${px2} ${groundY} ${pz2} 0 90`);
  await command(cdp, "/item replace entity @s hotbar.0 with minecraft:stone 1");
  await key(cdp, "1", "Digit1", 49, "1");
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
    await log("reload", "navigating the page again before re-entering the world");
    await cdp.send("Page.navigate", {url: pageUrl});
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
  // Chunks reload.
  let reLoaded = 0; { const end = Date.now() + settleMs; let prev = -1, since = null;
    while (Date.now() < end) { const row = await sample(cdp, "reenter", true); reLoaded = row.loaded || 0;
      if (row.loaded === prev && row.audit && row.audit.uncompiled === 0 && row.px && row.px.other > 0.3) { if (since === null) since = Date.now(); else if (Date.now() - since > 8000) break; } else since = null;
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
  diff && !diff.error && diff.meanDiff < 20 && diff.changedFraction < 0.15 && td2.sky < 0.05 ? pass("terrainBaseline", report.terrainBaseline) : fail("terrainBaseline", report.terrainBaseline);
  await command(cdp, "/gamemode creative");
  await sleep(1000);
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
  const shaderConsole = logLines.filter(l => !wireframeOnly(l) && ((/shader|glsl|spir|pipeline|program/i.test(l) && /error|fail|couldn't|could not|invalid/i.test(l)) || /compil\w* (error|fail)/i.test(l)));
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
    errs === 0 && drained === 0 && glConsole.length === 0 && g.lost === 0 ? pass("noWebGLErrors", {getErrorCalls: g.getErrorCalls, drainCalls: g.drainCalls}) : fail("noWebGLErrors", {drained: g.drained, drainCalls: g.drainCalls, getErrors: g.getErrors, lost: g.lost, glConsole: glConsole.slice(0, 10)});
  }
  exceptions.length === 0 ? pass("noUncaughtExceptions", {}) : fail("noUncaughtExceptions", {count: exceptions.length, first: exceptions.slice(0, 3)});
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  report.failed = failed;
  report.verdict = !report.error && failed.length === 0 ? "PASS" : "FAIL";
  report.totalSeconds = Math.round((Date.now() - t0) / 1000);
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  try { chrome.kill(); } catch {}
  if (server) server.close();
  await sleep(1500); await rm(profileDir, {recursive: true, force: true}).catch(() => {});
  console.log(JSON.stringify({verdict: report.verdict, profile, bootSeconds: report.bootSeconds, phases: report.phases, failed, error: report.error?.split("\n")[0],
    checks: Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.ok]))}));
  process.exit(report.verdict === "PASS" ? 0 : 1);
}
