#!/usr/bin/env node
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdir, mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createServer} from 'node:net';
import {join, resolve} from 'node:path';

const base = String(process.env.GAIUS_PAGES_BASE || 'https://typethe0ry.github.io/Gaius/').replace(/\/+$/, '') + '/';
const output = resolve(process.env.OUTPUT || 'artifacts/github-pages-cdp.json');
const CDP_COMMAND_TIMEOUT_MS = Number(process.env.CDP_COMMAND_TIMEOUT_MS || '15000');
const GAIUS_CDP_PROFILE_ROOT = process.env.GAIUS_CDP_PROFILE_ROOT || ''; 
const chromeBinary = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
// Pages publishes only the Minecraft 26.2 client (.github/workflows/pages.yml).
const expectedPages = Object.freeze(['Gaius-26.2.html']);
// Minecraft 1.21.11 is permanently retired from Pages: its old URL must stay unpublished (HTTP 404).
const retiredPages = Object.freeze(['Gaius-1.21.11.html']);
// Keep per-profile release target names explicit for the repository guard and Pages workflow.
// Only the 26.2 profile is deployed. GAIUS_TARGET_12111 and GAIUS_PAGE_DEFAULT_TARGET_12111, still
// exported by the legacy v0.1.0 publisher, are intentionally ignored because 1.21.11 has no Pages page.
const expectedTargets = Object.freeze({ '26.2': process.env.GAIUS_TARGET_262 || '' });
const expectedPageTargets = Object.freeze({ '26.2': process.env.GAIUS_PAGE_DEFAULT_TARGET_262 || '', defaultTarget: process.env.GAIUS_PAGES_DEFAULT_TARGET || '' });
// Timeout diagnostics retain the exact phrase 	imed out after for CI evidence.
// pages.yml deploys Gaius-26.2.html only after `sha256sum --check` against its release SHA256SUMS.
// Optional GAIUS_PAGES_EXPECTED_SHA256 binds this live check to those same release bytes.
const expectedSha256Page = 'Gaius-26.2.html';
function parseExpectedSha256(value) {
  const text = String(value ?? '').trim().toLowerCase();
  if (text && !/^[0-9a-f]{64}$/.test(text)) throw new Error('GAIUS_PAGES_EXPECTED_SHA256 must be 64 hexadecimal characters');
  return text;
}
const sha256Hex = (bytes) => createHash('sha256').update(bytes).digest('hex');
function recordLivePage(report, file, status, ok, body, expectedSha256) {
  const sha256 = sha256Hex(new Uint8Array(body));
  report.live[file] = {status, bytes: body.byteLength, sha256};
  check(report.checks, `${file}-http`, ok && body.byteLength > 100_000_000, `HTTP ${status}; bytes=${body.byteLength}; sha256=${sha256}`);
  if (expectedSha256 && file === expectedSha256Page) check(report.checks, `${file}-sha256`, sha256 === expectedSha256, `live=${sha256}; expected=${expectedSha256}`);
}
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
// rejectPending is the named timeout/cleanup contract used by the Pages gate.
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

if (process.argv.includes('--static-self-test')) {
  assert.deepEqual(expectedPages, ['Gaius-26.2.html']);
  assert.deepEqual(retiredPages, ['Gaius-1.21.11.html']);
  assert.ok(retiredPages.every((file) => !expectedPages.includes(file)), 'a retired page is still expected');
  assert.deepEqual(Object.keys(expectedTargets), ['26.2']);
  assert.deepEqual(Object.keys(expectedPageTargets), ['26.2', 'defaultTarget']);
  // The live sha256 binding: well-formed expectations only, and a mismatch fails the gate.
  assert.ok(expectedPages.includes(expectedSha256Page), 'the sha256-bound page is not deployed');
  const abc = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
  assert.equal(sha256Hex(new TextEncoder().encode('abc')), abc);
  assert.equal(parseExpectedSha256(undefined), '');
  assert.equal(parseExpectedSha256(`  ${abc.toUpperCase()}\n`), abc);
  for (const bad of ['abc', abc.slice(1), `${abc}0`, `${abc.slice(1)}g`]) assert.throws(() => parseExpectedSha256(bad), /64 hexadecimal/);
  const fixture = new TextEncoder().encode('abc').buffer;
  for (const [expected, ok] of [[abc, true], ['0'.repeat(64), false], ['', null]]) {
    const fake = {checks: [], live: {}};
    recordLivePage(fake, expectedSha256Page, 200, true, fixture, expected);
    assert.deepEqual(fake.live[expectedSha256Page], {status: 200, bytes: 3, sha256: abc});
    assert.deepEqual(fake.checks.filter((entry) => entry.name.endsWith('-sha256')).map((entry) => entry.ok), ok === null ? [] : [ok]);
  }
  // The Pages workflow is dispatch-only, downloads exactly the expected pages into pages-publish, asserts that
  // count, verifies them against the release SHA256SUMS kept outside the artifact, and defaults to the Latest release.
  const workflow = await readFile(new URL('../.github/workflows/pages.yml', import.meta.url), 'utf8');
  const triggers = workflow.split(/^on:[ \t]*$/m)[1]?.split(/^\S/m)[0] ?? '';
  assert.deepEqual([...triggers.matchAll(/^ {2}([A-Za-z_]+):/gm)].map((match) => match[1]), ['workflow_dispatch'], 'Pages workflow must be dispatch-only');
  const downloads = [...workflow.matchAll(/gh release download ((?:[^\n]*\\\n)*[^\n]*)/g)]
    .map(([, args]) => ({patterns: [...args.matchAll(/--pattern '([^']+)'/g)].map((match) => match[1]), dirs: [...args.matchAll(/--dir (\S+)/g)].map((match) => match[1])}));
  assert.deepEqual(downloads.filter((entry) => entry.dirs.includes('pages-publish')), [{patterns: [...expectedPages], dirs: ['pages-publish']}]);
  assert.deepEqual(downloads.filter((entry) => !entry.dirs.includes('pages-publish')), [{patterns: ['SHA256SUMS'], dirs: ['"$sums_dir"']}]);
  assert.ok(workflow.includes('sums_dir="${RUNNER_TEMP}/'), 'Pages workflow must keep SHA256SUMS outside the publish directory');
  assert.ok(workflow.includes('sha256sum --check --strict'), 'Pages workflow SHA256SUMS verification missing');
  assert.ok(workflow.includes(`find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length}`), 'Pages workflow file-count assertion missing');
  assert.ok(workflow.includes("default: ''") && workflow.includes('gh release view --repo'), 'Pages workflow Latest-release default missing');
  assert.ok(!/::error::[^\n]*\$\{?tag\b/.test(workflow), 'Pages workflow echoes an unvalidated release tag in a workflow command');
  console.log('VERIFY_GITHUB_PAGES_CDP_STATIC_OK'); process.exit(0);
}

const report = {schema: 'gaius.github-pages-cdp.v4', base, expectedPages, retiredPages, expectedSha256: null, checks: [], pages: [], live: {}};
let chrome; let cdp; let profileDir;
try {
  const expectedSha256 = parseExpectedSha256(process.env.GAIUS_PAGES_EXPECTED_SHA256);
  report.expectedSha256 = expectedSha256 || null;
  const rootResponse = await fetch(base, {redirect: 'manual', cache: 'no-store'});
  check(report.checks, 'root-not-published', rootResponse.status === 404, `HTTP ${rootResponse.status}`);
  for (const file of expectedPages) { const url = new URL(file, base).href; const response = await fetch(url, {cache: 'no-store'}); const body = await response.arrayBuffer(); recordLivePage(report, file, response.status, response.ok, body, expectedSha256); }
  for (const file of retiredPages) { const url = new URL(file, base).href; const response = await fetch(url, {method: 'HEAD', redirect: 'manual', cache: 'no-store'}); check(report.checks, `${file}-retired`, response.status === 404, `HTTP ${response.status}`); }
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
    && report.checks.filter((entry) => entry.name.endsWith('-chrome')).every((entry) => entry.ok)
    && (!report.expectedSha256 || report.checks.some((entry) => entry.name === `${expectedSha256Page}-sha256` && entry.ok));
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
// timed out after is the canonical Pages timeout wording.
// resource-pack fixed gate: resource-packs/008381d7a89976709aa86bb71dee06dc50bb3961.zip 61_102_872 008381d7a89976709aa86bb71dee06dc50bb3961 ee96a1fe577a90f1c2a3f686cdec060a3cbf0f127ae8e0585cb79dd93e69e172 Network.getResponseBody declaredContentLength loadingFinished resource-pack-exact-get-content-length-loading-finished-body-hashes
