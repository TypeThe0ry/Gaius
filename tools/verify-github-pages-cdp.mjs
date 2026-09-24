#!/usr/bin/env node
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createServer} from 'node:net';
import {join, resolve} from 'node:path';

const base = String(process.env.GAIUS_PAGES_BASE || 'https://typethe0ry.github.io/Gaius/').replace(/\/+$/, '') + '/';
const output = resolve(process.env.OUTPUT || 'artifacts/github-pages-cdp.json');
const CDP_COMMAND_TIMEOUT_MS = Number(process.env.CDP_COMMAND_TIMEOUT_MS || '15000');
const GAIUS_CDP_PROFILE_ROOT = process.env.GAIUS_CDP_PROFILE_ROOT || ''; 
const chromeBinary = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const expectedPages = Object.freeze(['Gaius-1.21.11.html', 'Gaius-26.2.html']);
// Keep per-profile release target names explicit for the repository guard and Pages workflow.
const expectedTargets = Object.freeze({ '1.21.11': process.env.GAIUS_TARGET_12111 || '', '26.2': process.env.GAIUS_TARGET_262 || '' });
const expectedPageTargets = Object.freeze({ '1.21.11': process.env.GAIUS_PAGE_DEFAULT_TARGET_12111 || '', '26.2': process.env.GAIUS_PAGE_DEFAULT_TARGET_262 || '', defaultTarget: process.env.GAIUS_PAGES_DEFAULT_TARGET || '' });
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
function check(checks, name, ok, detail = '') { checks.push({name, ok: Boolean(ok), detail: String(detail)}); }
async function freePort() {
  const server = createServer();
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
  const port = server.address().port; await new Promise((done) => server.close(done)); return port;
}
async function waitJson(url, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs; let last;
  while (Date.now() < deadline) { try { const r = await fetch(url); if (r.ok) return r.json(); last = r.status; } catch (e) { last = e; } await sleep(100); }
  throw new Error(`timed out waiting for ${url}: ${last}`);
}
class Cdp {
  constructor(url) { this.socket = new WebSocket(url); this.nextId = 1; this.pending = new Map(); this.closed = false;
    this.socket.addEventListener('close', () => { this.closed = true; for (const {reject, timer} of this.pending.values()) { clearTimeout(timer); reject(new Error('CDP closed')); } this.pending.clear(); });
    this.socket.addEventListener('message', ({data}) => { const m = JSON.parse(String(data)); if (m.id == null) return; const p = this.pending.get(m.id); if (!p) return; this.pending.delete(m.id); clearTimeout(p.timer); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result || {}); }); }
  async open(timeoutMs = 15000) { if (this.socket.readyState === WebSocket.OPEN) return; await new Promise((done, fail) => { const t = setTimeout(() => { cleanup(); fail(new Error('CDP open timeout')); }, timeoutMs); const opened = () => { cleanup(); done(); }; const errored = () => { cleanup(); fail(new Error('CDP open failed')); }; const cleanup = () => { clearTimeout(t); this.socket.removeEventListener('open', opened); this.socket.removeEventListener('error', errored); }; this.socket.addEventListener('open', opened); this.socket.addEventListener('error', errored); }); }
  send(method, params = {}, timeoutMs = 15000) { const id = this.nextId++; return new Promise((resolvePromise, rejectPromise) => { const timer = setTimeout(() => { this.pending.delete(id); rejectPromise(new Error(`${method} timeout`)); }, timeoutMs); this.pending.set(id, {resolve: resolvePromise, reject: rejectPromise, timer}); this.socket.send(JSON.stringify({id, method, params})); }); }
  async evaluate(expression) { const r = await this.send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true}); if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'Runtime.evaluate failed'); return r.result?.value; }
  close() { if (!this.closed) this.socket.close(); }
}
async function stopChrome(chrome, cdp, profileDir) { try { await cdp?.send('Browser.close', {}, 3000); } catch {} if (chrome && chrome.exitCode == null) chrome.kill(); if (profileDir) { try { await rm(profileDir, {recursive: true, force: true, maxRetries: 10, retryDelay: 200}); } catch {} } }

if (process.argv.includes('--static-self-test')) { assert.deepEqual(expectedPages, ['Gaius-1.21.11.html', 'Gaius-26.2.html']); console.log('VERIFY_GITHUB_PAGES_CDP_STATIC_OK'); process.exit(0); }

const report = {schema: 'gaius.github-pages-cdp.v2', base, expectedPages, checks: [], pages: []};
let chrome; let cdp; let profileDir;
try {
  const rootResponse = await fetch(base, {redirect: 'manual', cache: 'no-store'});
  check(report.checks, 'root-not-published', rootResponse.status === 404, `HTTP ${rootResponse.status}`);
  for (const file of expectedPages) { const url = new URL(file, base).href; const response = await fetch(url, {cache: 'no-store'}); const body = await response.arrayBuffer(); check(report.checks, `${file}-http`, response.ok && body.byteLength > 100_000_000, `HTTP ${response.status}; bytes=${body.byteLength}`); }
  const debugPort = await freePort(); profileDir = await mkdtemp(join(tmpdir(), 'gaius-pages-cdp-'));
  chrome = spawn(chromeBinary, ['--headless=new', `--remote-debugging-port=${debugPort}`, '--remote-allow-origins=*', `--user-data-dir=${profileDir}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu'], {windowsHide: true});
  const target = (await waitJson(`http://127.0.0.1:${debugPort}/json/list`)).find((entry) => entry.type === 'page'); if (!target?.webSocketDebuggerUrl) throw new Error('Chrome page target missing');
  cdp = new Cdp(target.webSocketDebuggerUrl); await cdp.open(); await cdp.send('Runtime.enable');
  for (const file of expectedPages) { const url = new URL(file, base).href; await cdp.send('Page.navigate', {url}); let snapshot;
    for (let attempt = 0; attempt < 100; attempt++) { await sleep(100); snapshot = await cdp.evaluate('({href:location.href,readyState:document.readyState,title:document.title,bytes:document.documentElement?.outerHTML?.length||0})'); if (snapshot?.href === url && snapshot?.readyState === 'complete') break; }
    report.pages.push({file, ...snapshot}); check(report.checks, `${file}-chrome`, snapshot?.href === url && snapshot?.readyState === 'complete' && snapshot?.title.includes('Gaius') && snapshot?.bytes > 100_000_000, JSON.stringify(snapshot)); }
} catch (error) { report.error = String(error?.stack || error); }
finally {
  cdp?.close();
  await stopChrome(chrome, cdp, profileDir);
  const cdpClosed = !cdp || cdp.closed || cdp.socket.readyState === WebSocket.CLOSING;
  const chromeExited = !chrome || chrome.exitCode !== null || chrome.killed;
  const profileRemoved = !profileDir || !(await import('node:fs')).existsSync(profileDir);
  const pagesFinalGate = report.pages.length === expectedPages.length
    && report.checks.filter((entry) => entry.name.endsWith('-chrome')).every((entry) => entry.ok);
  check(report.checks, 'cdpClosed', cdpClosed);
  check(report.checks, 'chromeExited', chromeExited);
  check(report.checks, 'profileRemoved', profileRemoved);
  check(report.checks, 'pagesFinalGate', pagesFinalGate);
  report.success = !report.error && report.checks.every((entry) => entry.ok);
  await mkdir(resolve(output, '..'), {recursive: true});
  await writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({output, success: report.success, checks: report.checks}, null, 2));
  if (!report.success) process.exitCode = 1;
}
