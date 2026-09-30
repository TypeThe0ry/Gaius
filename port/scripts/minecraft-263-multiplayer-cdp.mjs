// Multiplayer acceptance (migration milestone M5) for a Gaius browser client in headless Chrome:
// the served dist joins a vanilla server through a RelayNode, reaches PLAY, keeps the relay's
// keepalive proxy busy, disconnects and rejoins.
//
//   DIST=D:/g263/MP-dist SERVER=127.0.0.1:25663 node port/scripts/minecraft-263-multiplayer-cdp.mjs
//
// Environment:
//   DIST            directory with index.html/classes.js (served over http://127.0.0.1:DIST_PORT)
//   PAGE_URL        alternative: a page that is already served (DIST is then ignored)
//   DIST_PORT       static server port; must be an allowed RelayNode origin (default 8780)
//   BRIDGE          RelayNode base URL passed as ?bridge= (default http://127.0.0.1:8080)
//   SERVER          host:port typed into Direct Connect (required)
//   SERVER_LOG      optional vanilla server log; checked for "<name> joined the game"
//   PROTOCOL        Minecraft protocol number the client speaks; selects the relay's
//                   profilesSelected<P>/proxiedKeepAlives<P>Play counters (default 777)
//   OUT             evidence directory (report.json, console.log, screenshots; default m5-out)
//   PLAYER_NAME     remembered player name (default GaiusM5)
//   BOOT_TIMEOUT_MS / JOIN_TIMEOUT_MS / SOAK_MS   defaults 900000 / 240000 / 45000
//   REJOIN=0        skip the disconnect -> rejoin leg
//   GAIUS_CHROME_BIN  Chrome binary (default C:/Program Files/Google/Chrome/Application/chrome.exe)
//
// Checks: boot to TitleScreen, Multiplayer -> Direct Connection -> Join Server reaches PLAY (level,
// player entity, chunks), the relay selected the PROTOCOL profile and proxied PLAY keepalives during the
// soak, the rendered frame passes the terrain visual metric, the server saw the join (SERVER_LOG),
// disconnect -> rejoin reaches PLAY again, no shader/WebGL errors, no uncaught exceptions.
// Exit 0 only on PASS. Never prints the server address into report.json when REDACT_SERVER=1.
import {spawn} from "node:child_process";
import {createReadStream, statSync, existsSync, readFileSync} from "node:fs";
import {mkdtemp, mkdir, writeFile, rm, appendFile} from "node:fs/promises";
import {createServer as netServer} from "node:net";
import {createServer as httpServer} from "node:http";
import {tmpdir} from "node:os";
import {resolve, join, extname, normalize, dirname, relative, isAbsolute} from "node:path";
import {fileURLToPath} from "node:url";
import {analyzeTerrainPng} from "../../tools/terrain-visual-metrics.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.env.OUT || "m5-out");
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const bootTimeout = Number(process.env.BOOT_TIMEOUT_MS || 900000);
const joinTimeout = Number(process.env.JOIN_TIMEOUT_MS || 240000);
const soakMs = Number(process.env.SOAK_MS || 45000);
const rejoin = process.env.REJOIN !== "0";
const server = String(process.env.SERVER || "").trim();
if (!server) { console.error("SERVER=host:port is required"); process.exit(2); }
const serverHost = server.split(":")[0];
const redactServer = process.env.REDACT_SERVER === "1";
const redact = s => redactServer ? String(s).split(serverHost).join("<server>") : String(s);
const playerName = process.env.PLAYER_NAME || "GaiusM5";
const protocol = String(Number(process.env.PROTOCOL || 777));
const selectedKey = `profilesSelected${protocol}`, playKeepAliveKey = `proxiedKeepAlives${protocol}Play`, configurationKeepAliveKey = `proxiedKeepAlives${protocol}Configuration`;
const bridge = new URL(process.env.BRIDGE || "http://127.0.0.1:8080/");
const relayRuntimeUrl = new URL("/relay-node/v1.runtime", bridge).href;
const distPort = Number(process.env.DIST_PORT || 8780);
const sleep = ms => new Promise(r => setTimeout(r, ms));
await mkdir(out, {recursive: true});
const logFile = join(out, "console.log");
await writeFile(logFile, "");
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(7);
const logLines = [];
async function log(kind, text) {
  const line = `${stamp()} ${kind} ${redact(text)}`;
  logLines.push(line);
  await appendFile(logFile, line + "\n");
}

// Static dist server on a fixed port: the RelayNode only accepts allow-listed page origins
// (http://127.0.0.1:8780 and :8781 by default; see apps/bridge/dist/config.js).
const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
  ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};
let staticServer = null, pageUrl = process.env.PAGE_URL || null;
// Diagnosis-only JS probe patches: PROBE_PATCHES=file.json [{file, find, replace, label}] are
// applied to the served copy of a dist file (never written to disk); every find must match once.
const probe = new Map();
if (process.env.PROBE_PATCHES) {
  for (const p of JSON.parse(readFileSync(process.env.PROBE_PATCHES, "utf8"))) {
    if (!probe.has(p.file)) probe.set(p.file, []); probe.get(p.file).push(p);
  }
}
const probeCache = new Map();
function probed(root, name) {
  if (probeCache.has(name)) return probeCache.get(name);
  let text = readFileSync(join(root, name), "latin1");
  for (const p of probe.get(name)) {
    const n = text.split(p.find).length - 1;
    if (n !== 1) throw new Error(`probe patch ${p.label} matched ${n} times in ${name}`);
    text = text.replace(p.find, () => p.replace);
    log("probe-patch", `${name}: ${p.label}`);
  }
  const buf = Buffer.from(text, "latin1"); probeCache.set(name, buf); return buf;
}
if (!pageUrl) {
  if (!process.env.DIST) { console.error("DIST=<dist dir> or PAGE_URL is required"); process.exit(2); }
  const root = resolve(process.env.DIST);
  for (const name of probe.keys()) probed(root, name);
  staticServer = httpServer((req, res) => {
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
    res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size, "Cache-Control": "no-store"});
    createReadStream(p).pipe(res);
  });
  await new Promise((ok, bad) => { staticServer.once("error", bad); staticServer.listen(distPort, "127.0.0.1", ok); });
  pageUrl = `http://127.0.0.1:${distPort}/index.html`;
}
const pageWithBridge = pageUrl + (pageUrl.includes("?") ? "&" : "?") + "bridge=" + encodeURIComponent(bridge.href);

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
    const code = /[a-z]/i.test(ch) ? "Key" + upper : /[0-9]/.test(ch) ? "Digit" + ch : ch === "." ? "Period" : ch === ":" ? "Semicolon" : ch === "-" ? "Minus" : "";
    const vk = /[a-z0-9]/i.test(ch) ? upper.charCodeAt(0) : ch === "." ? 190 : ch === ":" ? 186 : ch === "-" ? 189 : 0;
    await key(cdp, ch, code, vk, ch, ch !== ch.toLowerCase() || ch === ":" ? 8 : 0);
    await sleep(40);
  }
}
async function screenshot(cdp, name) {
  try {
    const {data} = await cdp.send("Page.captureScreenshot", {format: "png"});
    const path = join(out, name); const buf = Buffer.from(data, "base64"); await writeFile(path, buf); return buf;
  } catch (e) { await log("shot-fail", name + " " + e); return null; }
}
async function relayRuntime() {
  try {
    const r = await fetch(relayRuntimeUrl, {cache: "no-store"}); if (!r.ok) return {error: `HTTP ${r.status}`};
    const j = await r.json(); const rt = j.runtime || j;
    const pick = {};
    for (const k of Object.keys(rt)) if (/^(profilesSelected|proxiedKeepAlive|keepAliveProxy|activeLocalTunnelSessions|activeTunnelLeases|activeTransportWebSockets|uptimeMillis|serverFramesSent|serverFrameBytesSent)/.test(k) && typeof rt[k] !== "object") pick[k] = rt[k];
    return pick;
  } catch (e) { return {error: String(e)}; }
}
function serverLogLines() {
  if (!process.env.SERVER_LOG) return null;
  try { return readFileSync(process.env.SERVER_LOG, "utf8").split(/\r?\n/); } catch (e) { return [`<unreadable: ${e}>`]; }
}

// Injected before any page script: WebGL instrumentation (same probe as the M3 title harness).
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
      badShaders:bad.shaders.slice(0,20), badPrograms:bad.programs.slice(0,20), shaderFailures:rec.shaderFailures.slice(0,20), programFailures:rec.programFailures.slice(0,20), getErrors:rec.getErrors, getErrorCalls:rec.getErrorCalls, drained:(window.__m3drain(), rec.drained), drainCalls:rec.drainCalls};
  };
  try { localStorage.setItem('gaius.playerName', ${JSON.stringify(playerName)}); } catch (e) {}
})();`;

const stateExpr = `(()=>{const s=window.__gaiusMinecraftState||null;const st=document.querySelector('[data-state]');
  const bp=[...document.querySelectorAll('#boot-screen,[id*=boot],[id*=status]')].filter(e=>!e.hidden).map(e=>({id:e.id,text:String(e.textContent||'').trim().slice(0,400)})).filter(e=>e.text);
  return {screen:s?String(s.screen||''):null,screenTitle:s?s.screenTitle:null,level:!!(s&&s.level),levelInfo:s&&s.level&&typeof s.level==='object'?s.level:null,player:s?s.player||null:null,gameMode:s?s.gameMode:null,
    loadedChunkCount:s?s.loadedChunkCount:null,overlay:s?s.overlay||null:null,clientDistance:s?s.clientDistance:null,effectiveRenderDistance:s?s.effectiveRenderDistance:null,
    widgets:s&&Array.isArray(s.screenWidgets)?s.screenWidgets.map(w=>({type:String(w.type||'').split('.').pop(),text:w.text,active:w.active,visible:w.visible})):null,
    statusState:st?st.dataset.state:null,boot:bp,fps:window.__gaiusFps?{fps:window.__gaiusFps.fps,gameFps:window.__gaiusFps.gameFps}:null};})()`;
const screenExpr = "String(window.__gaiusMinecraftState?.screen||'')";
const inPlay = s => s && s.level && !s.screen && !!s.player;

const report = {pageUrl: redact(pageWithBridge), server: redact(server), bridge: bridge.href, protocol, startedAt: new Date().toISOString(), checks: {}, timeline: [], relay: {}};
const pass = (n, d) => { report.checks[n] = {ok: true, detail: d}; log("CHECK", `PASS ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const fail = (n, d) => { report.checks[n] = {ok: false, detail: d}; log("CHECK", `FAIL ${n} ${JSON.stringify(d).slice(0, 400)}`); };
const profileDir = await mkdtemp(join(tmpdir(), "gaius-m5-"));
const port = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--no-first-run", "--no-default-browser-check", "--window-size=1280,720",
  "--disable-background-networking", "--disable-component-update", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  "about:blank"], {stdio: "ignore"});
let cdp;
const exceptions = [];
const sockets = [];

async function backToServerList(cdp, label) {
  // From a DisconnectedScreen/ConnectScreen/dialog back to JoinMultiplayerScreen.
  for (let i = 0; i < 6; i++) {
    const sc = await evaluate(cdp, screenExpr).catch(() => "");
    if (/JoinMultiplayerScreen/.test(sc)) return true;
    const b = await findWidget(cdp, {text: "Back to Server List"}, 500) || await findWidget(cdp, {text: "Back"}, 500)
      || await findWidget(cdp, {text: "Cancel"}, 500) || await findWidget(cdp, {text: "Done"}, 500);
    if (b) await clickAt(cdp, b.x, b.y); else await key(cdp, "Escape", "Escape", 27);
    await sleep(1500);
  }
  await log("nav", `${label}: could not return to the server list`);
  return /JoinMultiplayerScreen/.test(await evaluate(cdp, screenExpr).catch(() => ""));
}

async function directConnect(cdp, label) {
  let sc = "", end = Date.now() + 20000;
  while (Date.now() < end && !/JoinMultiplayerScreen|MultiplayerWarningScreen|SafetyScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  if (/Warning|Safety/.test(sc)) {
    await clickWidget(cdp, "Proceed", 5000).catch(() => clickWidget(cdp, "Continue", 5000));
    end = Date.now() + 15000; while (Date.now() < end && !/JoinMultiplayerScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  }
  await sleep(800);
  await clickWidget(cdp, "Direct Conn", 15000);
  end = Date.now() + 15000; while (Date.now() < end && !/DirectJoinServerScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
  await sleep(600);
  const edit = await findWidget(cdp, {type: "EditBox"}, 5000);
  if (!edit) throw new Error(`${label}: no address EditBox on ${sc}`);
  await clickAt(cdp, edit.x, edit.y); await sleep(200);
  await key(cdp, "a", "KeyA", 65, "", 2); await key(cdp, "Backspace", "Backspace", 8);
  await typeText(cdp, server);
  await sleep(600);
  const st = await evaluate(cdp, stateExpr);
  const join = (st.widgets || []).find(w => /Join Server/i.test(String(w.text)));
  await screenshot(cdp, `${label}-typed.png`);
  if (!join || join.active === false) throw new Error(`${label}: Join Server not active after typing (${JSON.stringify(st.widgets)})`);
  await clickWidget(cdp, "Join Server", 5000);
  return Date.now();
}

async function waitForPlay(cdp, label, ms) {
  const end = Date.now() + ms; let last = ""; let firstLevelAt = null; const t = Date.now();
  while (Date.now() < end) {
    let s = null; try { s = await evaluate(cdp, stateExpr); } catch (e) { s = {err: String(e).slice(0, 200)}; }
    const sig = JSON.stringify([s?.screen, s?.level, !!s?.player, s?.loadedChunkCount, s?.overlay]);
    if (sig !== last) { last = sig; report.timeline.push({t: stamp(), label, screen: s?.screen, level: s?.level, player: !!s?.player, loadedChunkCount: s?.loadedChunkCount, overlay: s?.overlay, widgets: (s?.widgets || []).map(w => w.text).slice(0, 12)});
      await log("state", `${label} ${JSON.stringify({screen: s?.screen, level: s?.level, player: !!s?.player, chunks: s?.loadedChunkCount, overlay: s?.overlay, widgets: (s?.widgets || []).map(w => w.text).slice(0, 12)})}`); }
    if (s?.level && firstLevelAt === null) firstLevelAt = Date.now();
    if (inPlay(s)) return {result: "PLAY", seconds: (Date.now() - t) / 1000, levelAfterSeconds: firstLevelAt ? (firstLevelAt - t) / 1000 : null, state: s};
    if (/DisconnectedScreen/.test(s?.screen || "")) return {result: "DISCONNECTED", seconds: (Date.now() - t) / 1000, state: s};
    if (/MultiButtonDialogScreen|dialog\./.test(s?.screen || "")) return {result: "SERVER_DIALOG", seconds: (Date.now() - t) / 1000, state: s};
    if (/ConfirmScreen|PackConfirm|ServerPack|CodeOfConduct/i.test(s?.screen || "")) {
      const yes = await findWidget(cdp, {text: "Yes"}, 1000) || await findWidget(cdp, {text: "Proceed"}, 1000) || await findWidget(cdp, {text: "Accept"}, 1000);
      if (yes) { await clickAt(cdp, yes.x, yes.y); await log("nav", `${label}: accepted ${s.screen}`); }
    }
    if (logLines.some(l => l.includes("Game crashed!"))) return {result: "CRASH", seconds: (Date.now() - t) / 1000, state: s};
    await sleep(1000);
  }
  return {result: "TIMEOUT", seconds: ms / 1000, state: await evaluate(cdp, stateExpr).catch(() => null)};
}

try {
  let targets;
  for (let i = 0; i < 80; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.length) break; } catch {} await sleep(250); }
  cdp = new Cdp(targets.find(t => t.type === "page").webSocketDebuggerUrl); await cdp.open();
  const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
  cdp.on("Runtime.consoleAPICalled", (p, sid) => log(`console.${p.type}${sid ? "[worker]" : ""}`, fmtArgs(p.args).slice(0, 4000)));
  cdp.on("Runtime.exceptionThrown", (p, sid) => { const d = p.exceptionDetails; const t = (d.exception?.description || d.text || "").slice(0, 4000); exceptions.push({sid: !!sid, t: redact(t), at: stamp()}); log(`EXCEPTION${sid ? "[worker]" : ""}`, t); });
  cdp.on("Log.entryAdded", (p, sid) => log(`log.${p.entry.level}${sid ? "[worker]" : ""}`, `${p.entry.source} ${String(p.entry.text).slice(0, 2000)} ${p.entry.url || ""}`));
  cdp.on("Network.webSocketCreated", p => { sockets.push({t: stamp(), url: redact(p.url)}); log("websocket", p.url); });
  cdp.on("Network.webSocketClosed", p => log("websocket-closed", p.requestId));
  cdp.on("Network.webSocketFrameError", p => log("websocket-error", p.errorMessage));
  cdp.on("Target.attachedToTarget", async (p) => {
    await log("target", `${p.targetInfo.type} ${p.targetInfo.url}`);
    try { await cdp.send("Runtime.enable", {}, p.sessionId); await cdp.send("Log.enable", {}, p.sessionId);
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, p.sessionId);
      await cdp.send("Runtime.runIfWaitingForDebugger", {}, p.sessionId); } catch (e) { log("target-err", String(e)); }
  });
  await cdp.send("Runtime.enable"); await cdp.send("Page.enable"); await cdp.send("Log.enable"); await cdp.send("Network.enable");
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: glProbe});
  report.relay.beforeBoot = await relayRuntime();
  await log("relay", JSON.stringify(report.relay.beforeBoot));
  await log("navigate", pageWithBridge);
  await cdp.send("Page.navigate", {url: pageWithBridge});

  // Phase 1: boot to the title screen.
  let last = "", lastShot = 0, shotN = 0, reached = null;
  const bootEnd = Date.now() + bootTimeout;
  while (Date.now() < bootEnd) {
    await evaluate(cdp, 'window.__m3drain && window.__m3drain()').catch(() => null);
    let s = null; try { s = await evaluate(cdp, stateExpr); } catch (e) { s = {err: String(e).slice(0, 200)}; }
    const sig = JSON.stringify([s?.screen, s?.statusState, s?.boot?.map(b => b.text.slice(0, 120)), s?.widgets?.length]);
    if (sig !== last) { last = sig; report.timeline.push({t: stamp(), label: "boot", screen: s?.screen, statusState: s?.statusState, boot: s?.boot}); await log("state", JSON.stringify({screen: s?.screen, statusState: s?.statusState, boot: s?.boot, widgets: s?.widgets?.length}).slice(0, 1500)); }
    if (Date.now() - lastShot > 60000) { lastShot = Date.now(); await screenshot(cdp, `boot-${String(++shotN).padStart(2, "0")}.png`); }
    if (s?.screen && /TitleScreen|BrowserProfileScreen/.test(s.screen)) { reached = s.screen; break; }
    if (logLines.some(l => l.includes("Game crashed!"))) { await sleep(3000); report.crashed = true; break; }
    if (s?.statusState === "error") { await sleep(5000); report.bootError = await evaluate(cdp, stateExpr).catch(() => null); break; }
    await sleep(1000);
  }
  report.bootSeconds = Math.round((Date.now() - t0) / 1000);
  await sleep(2000);
  await screenshot(cdp, "10-first-screen.png");
  if (!reached) throw new Error("title screen not reached: " + JSON.stringify(await evaluate(cdp, stateExpr).catch(e => String(e))).slice(0, 800));
  await evaluate(cdp, 'window.__m3drainTimer = setInterval(() => window.__m3drain && window.__m3drain(), 1000)');
  pass("bootsToTitle", {screen: reached, seconds: report.bootSeconds});
  if (reached.endsWith("BrowserProfileScreen")) {
    await clickWidget(cdp, "Done", 10000).catch(async () => { await key(cdp, "Escape", "Escape", 27); });
    const end = Date.now() + 20000;
    while (Date.now() < end && !(await evaluate(cdp, screenExpr)).endsWith("TitleScreen")) await sleep(250);
  }
  await sleep(1000);

  // Phase 2: Multiplayer -> Direct Connection -> Join Server -> PLAY.
  await clickWidget(cdp, "Multiplayer", 10000);
  const joinedAt = await directConnect(cdp, "join1");
  report.relay.afterJoinClick = await relayRuntime();
  const play1 = await waitForPlay(cdp, "join1", joinTimeout);
  report.join1 = {result: play1.result, seconds: play1.seconds, levelAfterSeconds: play1.levelAfterSeconds, state: play1.state};
  await screenshot(cdp, "20-join1-result.png");
  if (play1.result === "PLAY") pass("joinReachesPlay", {seconds: play1.seconds, levelAfterSeconds: play1.levelAfterSeconds, player: play1.state.player, gameMode: play1.state.gameMode, chunks: play1.state.loadedChunkCount});
  else fail("joinReachesPlay", {result: play1.result, seconds: play1.seconds, screen: play1.state?.screen, widgets: (play1.state?.widgets || []).map(w => w.text), overlay: play1.state?.overlay});

  if (play1.result === "PLAY") {
    // Phase 3: soak in PLAY; sample chunks/keepalives; render evidence.
    const r0 = await relayRuntime(); report.relay.playStart = r0;
    const samples = [];
    const soakEnd = Date.now() + soakMs;
    while (Date.now() < soakEnd) {
      const s = await evaluate(cdp, stateExpr).catch(() => null);
      samples.push({t: stamp(), screen: s?.screen, level: s?.level, chunks: s?.loadedChunkCount, player: !!s?.player, fps: s?.fps});
      if (s?.screen && !/ChatScreen/.test(s.screen)) { await log("soak", `unexpected screen during PLAY: ${s.screen}`); }
      await sleep(5000);
    }
    report.soak = samples;
    const r1 = await relayRuntime(); report.relay.playEnd = r1;
    await log("relay", JSON.stringify(r1));
    const shot = await screenshot(cdp, "21-play-world.png");
    const chunks = samples.map(x => Number(x.chunks) || 0);
    const maxChunks = Math.max(...chunks, 0);
    const stillPlaying = samples.every(x => x.level && x.player) && !samples.some(x => /DisconnectedScreen/.test(x.screen || ""));
    maxChunks > 0 && stillPlaying ? pass("chunksArriveAndStayInPlay", {maxChunks, samples: samples.length, soakMs}) : fail("chunksArriveAndStayInPlay", {maxChunks, samples});
    const selected = Number(r1[selectedKey] || 0) - Number(report.relay.beforeBoot[selectedKey] || 0);
    const kaPlay = Number(r1[playKeepAliveKey] || 0) - Number(r0[playKeepAliveKey] || 0);
    const kaConf = Number(r1[configurationKeepAliveKey] || 0) - Number(report.relay.beforeBoot[configurationKeepAliveKey] || 0);
    const gap = Number(r1.proxiedKeepAliveMaxGapMillis || 0);
    selected >= 1 && kaPlay >= 1 && r1.keepAliveProxyEnabled !== false
      ? pass("relayProxiesKeepAlives", {protocol, profilesSelected: selected, playKeepAlivesDuringSoak: kaPlay, configurationKeepAlives: kaConf, maxGapMillis: gap, writeErrors: r1.keepAliveProxyWriteErrors, opaqueTransitions: r1.keepAliveProxyOpaqueTransitions})
      : fail("relayProxiesKeepAlives", {protocol, selected, kaPlay, kaConf, r0, r1});
    let visual = null;
    try { visual = analyzeTerrainPng(shot); } catch (e) { visual = {error: String(e)}; }
    report.visual = visual;
    // The strict daytime terrain metric (terrainVisualPass) is recorded; the check itself accepts a
    // vanilla server's night-time frame too (moonlit terrain keeps colour variety and block edges but
    // not the daylight luminance the strict metric wants).
    const rendered = visual && !visual.error && visual.nonBlackRatio >= 0.5 && visual.colorBuckets >= 16 && visual.luminanceStdDev >= 4
      && visual.lowerColorBuckets >= 8 && visual.lowerEdgeDensity >= 0.02 && visual.lowerTexturedTileCount >= 2 && visual.dominantColorRatio <= 0.9;
    const visualSummary = visual && {terrainVisualPass: visual.terrainVisualPass, nonBlackRatio: visual.nonBlackRatio, colorBuckets: visual.colorBuckets, luminanceStdDev: visual.luminanceStdDev,
      lowerColorBuckets: visual.lowerColorBuckets, lowerTexturedTileCount: visual.lowerTexturedTileCount, lowerEdgeDensity: visual.lowerEdgeDensity, dominantColorRatio: visual.dominantColorRatio, error: visual.error};
    rendered ? pass("worldRendered", visualSummary) : fail("worldRendered", visualSummary);
    const sl = serverLogLines();
    if (sl) {
      const joined = sl.filter(l => l.includes(`${playerName} joined the game`)).length;
      const logged = sl.filter(l => l.includes(`${playerName}[`) && /logged in with entity id/.test(l)).length;
      joined >= 1 && logged >= 1 ? pass("serverSawJoin", {joined, logged}) : fail("serverSawJoin", {joined, logged, tail: sl.slice(-8)});
    }

    if (rejoin) {
      // Phase 4: Escape -> Disconnect -> server list -> rejoin -> PLAY.
      await key(cdp, "Escape", "Escape", 27);
      let sc = "", end = Date.now() + 10000;
      while (Date.now() < end && !/PauseScreen/.test(sc = await evaluate(cdp, screenExpr))) await sleep(250);
      await sleep(500); await screenshot(cdp, "30-pause.png");
      const dc = await findWidget(cdp, {text: "Disconnect"}, 5000);
      if (!dc) fail("disconnectRejoin", {stage: "no Disconnect button", screen: sc, widgets: ((await evaluate(cdp, stateExpr)).widgets || []).map(w => w.text)});
      else {
        await clickAt(cdp, dc.x, dc.y);
        end = Date.now() + 30000;
        let st = null;
        while (Date.now() < end) { st = await evaluate(cdp, stateExpr).catch(() => null); if (st && !st.level && /JoinMultiplayerScreen|TitleScreen|DisconnectedScreen/.test(st.screen || "")) break; await sleep(500); }
        await sleep(1000); await screenshot(cdp, "31-after-disconnect.png");
        report.afterDisconnect = {screen: st?.screen, level: st?.level, relay: await relayRuntime()};
        await log("state", `after disconnect ${JSON.stringify({screen: st?.screen, level: st?.level})}`);
        const sl2 = serverLogLines();
        report.afterDisconnect.serverSawLeave = sl2 ? sl2.some(l => l.includes(`${playerName} left the game`)) : null;
        const ok = await backToServerList(cdp, "rejoin");
        if (!ok) fail("disconnectRejoin", {stage: "not on server list", screen: await evaluate(cdp, screenExpr).catch(() => null)});
        else {
          await directConnect(cdp, "join2");
          const play2 = await waitForPlay(cdp, "join2", joinTimeout);
          report.join2 = {result: play2.result, seconds: play2.seconds, levelAfterSeconds: play2.levelAfterSeconds, state: play2.state};
          await sleep(8000);
          await screenshot(cdp, "32-join2-result.png");
          const r2 = await relayRuntime(); report.relay.afterRejoin = r2;
          const s2 = await evaluate(cdp, stateExpr).catch(() => null);
          const selected2 = Number(r2[selectedKey] || 0) - Number(r1[selectedKey] || 0);
          play2.result === "PLAY" && inPlay(s2) && selected2 >= 1
            ? pass("disconnectRejoin", {seconds: play2.seconds, chunks: s2.loadedChunkCount, afterDisconnectScreen: report.afterDisconnect.screen, serverSawLeave: report.afterDisconnect.serverSawLeave, profilesSelectedDelta: selected2, activeTunnels: r2.activeLocalTunnelSessions})
            : fail("disconnectRejoin", {result: play2.result, screen: play2.state?.screen, widgets: (play2.state?.widgets || []).map(w => w.text), selected2, stillInPlay: inPlay(s2)});
        }
      }
    }
  }
  await sleep(1000);
} catch (e) {
  report.error = redact(String(e.stack || e));
  await log("HARNESS-ERROR", report.error);
  try { await screenshot(cdp, "error.png"); report.errorState = await evaluate(cdp, stateExpr); } catch {}
} finally {
  try { report.gl = await evaluate(cdp, "window.__m3audit ? window.__m3audit() : null"); } catch (e) { report.gl = String(e); }
  report.relay.final = await relayRuntime();
  const glConsole = logLines.filter(l => /GL_INVALID|WebGL: |INVALID_OPERATION|INVALID_ENUM|INVALID_VALUE|INVALID_FRAMEBUFFER|CONTEXT_LOST|GL ERROR|GL error/.test(l));
  const wireframeOnly = l => /WIREFRAME fill mode, not supported by device/.test(l)
    || (/Failed to load optional shader programs/.test(l) && (l.match(/ - \S+/g) || []).every(x => /^ - minecraft:pipeline\/wireframe(_multidraw)?$/.test(x)));
  const shaderConsole = logLines.filter(l => !wireframeOnly(l) && ((/shader|glsl|spir|pipeline|program/i.test(l) && /error|fail|couldn't|could not|invalid/i.test(l)) || /compil\w* (error|fail)/i.test(l)));
  report.glConsole = glConsole.slice(0, 50); report.shaderConsole = shaderConsole.slice(0, 50);
  report.exceptions = exceptions.slice(0, 50);
  report.sockets = sockets;
  report.errorConsole = logLines.filter(l => /^\s*\S+ (console\.error|EXCEPTION|log\.error)/.test(l) && !/Missing sound|favicon/.test(l)).slice(0, 80);
  const g = report.gl && typeof report.gl === "object" ? report.gl : null;
  if (g) {
    const errs = Object.values(g.getErrors || {}).reduce((a, b) => a + b, 0);
    g.badShaders.length + g.badPrograms.length + g.shaderFailures.length + g.programFailures.length === 0 && shaderConsole.length === 0
      ? pass("noShaderErrors", {live: g.liveShaders, programs: g.livePrograms}) : fail("noShaderErrors", {g: {badShaders: g.badShaders, badPrograms: g.badPrograms, shaderFailures: g.shaderFailures, programFailures: g.programFailures}, shaderConsole: shaderConsole.slice(0, 5)});
    const drained = Object.values(g.drained || {}).reduce((a, b) => a + b, 0);
    errs === 0 && drained === 0 && glConsole.length === 0 && g.lost === 0 && g.drainCalls > 10 ? pass("noWebGLErrors", {getErrorCalls: g.getErrorCalls, drainCalls: g.drainCalls}) : fail("noWebGLErrors", {drained: g.drained, drainCalls: g.drainCalls, getErrors: g.getErrors, lost: g.lost, glConsole: glConsole.slice(0, 10)});
  }
  exceptions.length === 0 ? pass("noUncaughtExceptions", {}) : fail("noUncaughtExceptions", exceptions.slice(0, 5));
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  report.failed = failed;
  report.verdict = !report.error && failed.length === 0 ? "PASS" : "FAIL";
  await writeFile(join(out, "report.json"), redact(JSON.stringify(report, null, 2)));
  try { chrome.kill(); } catch {}
  if (staticServer) staticServer.close();
  await sleep(1500); await rm(profileDir, {recursive: true, force: true}).catch(() => {});
  console.log(redact(JSON.stringify({verdict: report.verdict, bootSeconds: report.bootSeconds, join1: report.join1 && {result: report.join1.result, seconds: report.join1.seconds}, join2: report.join2 && {result: report.join2.result, seconds: report.join2.seconds},
    failed, error: report.error?.split("\n")[0], checks: Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.ok]))})));
  process.exit(report.verdict === "PASS" ? 0 : 1);
}
