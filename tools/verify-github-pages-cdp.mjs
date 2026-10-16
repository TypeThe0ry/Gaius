#!/usr/bin/env node
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdir, mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createServer} from 'node:net';
import {join, resolve} from 'node:path';
import {gunzipSync, gzipSync} from 'node:zlib';

const base = String(process.env.GAIUS_PAGES_BASE || 'https://typethe0ry.github.io/Gaius/').replace(/\/+$/, '') + '/';
const output = resolve(process.env.OUTPUT || 'artifacts/github-pages-cdp.json');
const CDP_COMMAND_TIMEOUT_MS = Number(process.env.CDP_COMMAND_TIMEOUT_MS || '15000');
const GAIUS_CDP_PROFILE_ROOT = process.env.GAIUS_CDP_PROFILE_ROOT || ''; 
const chromeBinary = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
// Pages publishes exactly the Minecraft 1.21.11, 26.2 and 26.3 clients (.github/workflows/pages.yml).
const expectedPages = Object.freeze(['Gaius-1.21.11.html', 'Gaius-26.2.html', 'Gaius-26.3.html']);
// No page is retired: every published profile is expected above. A retired page must return HTTP 404.
const retiredPages = Object.freeze([]);
// Keep per-profile release target names explicit for the repository guard and Pages workflow.
const expectedTargets = Object.freeze({ '1.21.11': process.env.GAIUS_TARGET_12111 || '', '26.2': process.env.GAIUS_TARGET_262 || '', '26.3': process.env.GAIUS_TARGET_263 || '' });
const expectedPageTargets = Object.freeze({ '1.21.11': process.env.GAIUS_PAGE_DEFAULT_TARGET_12111 || '', '26.2': process.env.GAIUS_PAGE_DEFAULT_TARGET_262 || '', '26.3': process.env.GAIUS_PAGE_DEFAULT_TARGET_263 || '', defaultTarget: process.env.GAIUS_PAGES_DEFAULT_TARGET || '' });
// Timeout diagnostics retain the exact phrase 	imed out after for CI evidence.
// pages.yml deploys each client only after `sha256sum --check` against its release SHA256SUMS record.
// Optional GAIUS_PAGES_EXPECTED_SHA256_12111 / _262 / _263 bind this live check to those same release
// bytes; GAIUS_PAGES_EXPECTED_SHA256 remains the 26.2 alias set by the v0.1.0 publisher.
const expectedSha256Variables = Object.freeze({
  'Gaius-1.21.11.html': ['GAIUS_PAGES_EXPECTED_SHA256_12111'],
  'Gaius-26.2.html': ['GAIUS_PAGES_EXPECTED_SHA256_262', 'GAIUS_PAGES_EXPECTED_SHA256'],
  'Gaius-26.3.html': ['GAIUS_PAGES_EXPECTED_SHA256_263'],
});
function parseExpectedSha256(value, variable = 'GAIUS_PAGES_EXPECTED_SHA256') {
  const text = String(value ?? '').trim().toLowerCase();
  if (text && !/^[0-9a-f]{64}$/.test(text)) throw new Error(`${variable} must be 64 hexadecimal characters`);
  return text;
}
function expectedSha256ByPage(env) {
  const result = {};
  for (const file of expectedPages) {
    const variable = expectedSha256Variables[file].find((name) => String(env[name] ?? '').trim()) ?? expectedSha256Variables[file][0];
    result[file] = parseExpectedSha256(env[variable], variable);
  }
  return Object.freeze(result);
}
const sha256Hex = (bytes) => createHash('sha256').update(bytes).digest('hex');
function recordLivePage(report, file, status, ok, body, expectedSha256) {
  const sha256 = sha256Hex(new Uint8Array(body));
  report.live[file] = {status, bytes: body.byteLength, sha256};
  check(report.checks, `${file}-http`, ok && body.byteLength > 100_000_000, `HTTP ${status}; bytes=${body.byteLength}; sha256=${sha256}`);
  if (expectedSha256) check(report.checks, `${file}-sha256`, sha256 === expectedSha256, `live=${sha256}; expected=${expectedSha256}`);
}
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
// A fresh Pages deploy can take minutes to reach every CDN edge (responses carry max-age=600). When the
// expected sha256 is set and the live hash mismatches, re-fetch the canonical URL — the one players load —
// under bounded exponential backoff for up to GAIUS_PAGES_SHA256_RETRY_MS (default 10 minutes; 0 disables
// retries) until the edge serves the release bytes. A cache-busting query would only prove the origin is
// current, so it is not used. Every attempt, including failed fetches, is recorded.
const DEFAULT_SHA256_RETRY_MS = 600_000;
const SHA256_RETRY_BASE_DELAY_MS = 5_000;
const SHA256_RETRY_MAX_DELAY_MS = 60_000;
function parseRetryWindowMs(value) {
  const text = String(value ?? '').trim();
  if (!text) return DEFAULT_SHA256_RETRY_MS;
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(Number(text))) throw new Error('GAIUS_PAGES_SHA256_RETRY_MS must be a non-negative integer of milliseconds');
  return Number(text);
}
const retryDelayMs = (retry) => Math.min(SHA256_RETRY_MAX_DELAY_MS, SHA256_RETRY_BASE_DELAY_MS * 2 ** Math.max(0, retry - 1));
// accept, when given, replaces the sha256 comparison as the retry condition (the site layout waits
// for the redirect page itself, which no release record covers).
async function fetchLivePage(url, expectedSha256, {windowMs = 0, fetchImpl = fetch, sleepImpl = sleep, now = Date.now, accept = null} = {}) {
  const attempts = [];
  const attemptOnce = async (attempt) => {
    try {
      const response = await fetchImpl(url, {cache: 'no-store'});
      const body = await response.arrayBuffer();
      const sha256 = sha256Hex(new Uint8Array(body));
      attempts.push({attempt, url, at: new Date(now()).toISOString(), status: response.status, bytes: body.byteLength, sha256});
      return {status: response.status, ok: response.ok, body, sha256};
    } catch (error) {
      attempts.push({attempt, url, at: new Date(now()).toISOString(), error: String(error?.message || error)});
      return null;
    }
  };
  const matches = (result) => Boolean(result) && result.ok && (accept ? accept(result) : result.sha256 === expectedSha256);
  let final = await attemptOnce(0);
  if ((expectedSha256 || accept) && !matches(final)) {
    const deadline = now() + windowMs;
    for (let attempt = 1; now() < deadline; attempt++) {
      await sleepImpl(Math.max(0, Math.min(retryDelayMs(attempt), deadline - now())));
      const result = await attemptOnce(attempt);
      // Keep the last real response: a later fetch error must not erase the evidence of a mismatch.
      if (result) final = result;
      if (matches(final)) break;
    }
  }
  if (!final) throw new Error(`could not fetch ${url}: ${attempts.at(-1)?.error || 'unknown error'}`);
  return {...final, attempts};
}

// Multi-file site layout (releases with Gaius-site-<profile>.tar.gz, see .github/workflows/pages.yml):
// each profile is served at <profile>/ and Gaius-<profile>.html is a small redirect to it, so the
// single-file byte and sha256 checks above do not apply to that page. The site is checked instead:
// <profile>/index.html carries window.__gaiusSite, gaius-sw.js and every asset of gaius-site.json
// answer 200, and Chrome lands on <profile>/ with the site descriptor in place.
// GAIUS_PAGES_EXPECTED_SITE_SHA256_12111 / _262 / _263 bind a site to the release's
// Gaius-site-<profile>.tar.gz record; GAIUS_PAGES_SITE_ARCHIVES names a directory holding those
// downloaded archives, and every archived file must then be served with exactly the archived bytes.
const profileOfPage = (file) => file.slice('Gaius-'.length, -'.html'.length);
const siteRedirectMarker = (profile) => `location.replace("${profile}/"`;
function isSiteRedirect(body, profile) {
  if (!body || body.byteLength > 65_536) return false;
  return new TextDecoder().decode(new Uint8Array(body)).includes(siteRedirectMarker(profile));
}
const expectedSiteSha256Variables = Object.freeze({
  'Gaius-1.21.11.html': 'GAIUS_PAGES_EXPECTED_SITE_SHA256_12111',
  'Gaius-26.2.html': 'GAIUS_PAGES_EXPECTED_SITE_SHA256_262',
  'Gaius-26.3.html': 'GAIUS_PAGES_EXPECTED_SITE_SHA256_263',
});
function expectedSiteSha256ByPage(env) {
  const result = {};
  for (const file of expectedPages) result[file] = parseExpectedSha256(env[expectedSiteSha256Variables[file]], expectedSiteSha256Variables[file]);
  return Object.freeze(result);
}
// The entries of a site archive as build-pages-site.py writes it: gzip over ustar/pax, regular files only.
function readTarEntries(gzipBytes) {
  const data = gunzipSync(gzipBytes);
  const decoder = new TextDecoder();
  const field = (start, length) => { let end = start; while (end < start + length && data[end] !== 0) end++; return decoder.decode(data.subarray(start, end)); };
  const entries = [];
  let offset = 0;
  let paxPath = null;
  while (offset + 512 <= data.length) {
    if (data.subarray(offset, offset + 512).every((byte) => byte === 0)) break;
    const size = parseInt(field(offset + 124, 12).trim() || '0', 8);
    const type = data[offset + 156] === 0 ? '0' : String.fromCharCode(data[offset + 156]);
    const start = offset + 512;
    if (!Number.isSafeInteger(size) || size < 0 || start + size > data.length) throw new Error('site archive entry is truncated');
    const body = data.subarray(start, start + size);
    if (type === 'x') {
      const match = /(?:^|\n)\d+ path=([^\n]*)\n/.exec(decoder.decode(body));
      paxPath = match ? match[1] : null;
    } else if (type === '0') {
      const name = field(offset, 100);
      const prefix = field(offset + 345, 155);
      entries.push({path: paxPath ?? (prefix ? `${prefix}/${name}` : name), bytes: body});
      paxPath = null;
    } else {
      throw new Error(`site archive holds a non-file entry (type ${type})`);
    }
    offset = start + Math.ceil(size / 512) * 512;
  }
  return entries;
}
// Content-hashed names (classes.<16 hex>.js) never change under their URL; only the few stable
// names (index.html, gaius-site.json, gaius-sw.js, relay-nodes.json) can lag on a CDN edge.
const isHashedSitePath = (path) => /\.[0-9a-f]{16}(?:\.|$)/.test(path.split('/').pop());
async function checkSite(report, file, {expectedSiteSha256 = '', archiveDirectory = '', windowMs = 0, fetchImpl = fetch, sleepImpl = sleep, now = Date.now} = {}) {
  const profile = profileOfPage(file);
  const siteBase = new URL(`${profile}/`, base).href;
  const site = report.site[profile] = {base: siteBase, id: null, assets: 0, archiveSha256: null, verifiedFiles: 0};
  const name = (suffix) => `${profile}-site-${suffix}`;
  let archived = null;
  if (expectedSiteSha256) {
    const archivePath = archiveDirectory ? join(archiveDirectory, `Gaius-site-${profile}.tar.gz`) : '';
    let archiveBytes = null;
    try {
      if (archivePath) archiveBytes = await readFile(archivePath);
    } catch {}
    check(report.checks, name('archive'), Boolean(archiveBytes), archiveBytes ? archivePath : `GAIUS_PAGES_SITE_ARCHIVES must hold Gaius-site-${profile}.tar.gz to check ${expectedSiteSha256Variables[file]}`);
    if (archiveBytes) {
      site.archiveSha256 = sha256Hex(archiveBytes);
      check(report.checks, name('archive-sha256'), site.archiveSha256 === expectedSiteSha256, `archive=${site.archiveSha256}; expected=${expectedSiteSha256}`);
      if (site.archiveSha256 === expectedSiteSha256) archived = new Map(readTarEntries(archiveBytes).map((entry) => [entry.path, sha256Hex(entry.bytes)]));
    }
  }
  const fetchFile = (path) => {
    const expected = archived?.get(path) || '';
    return fetchLivePage(new URL(path, siteBase).href, expected, {windowMs: expected && !isHashedSitePath(path) ? windowMs : 0, fetchImpl, sleepImpl, now});
  };
  const decode = (response) => new TextDecoder().decode(new Uint8Array(response.body));
  const index = await fetchFile('index.html');
  check(report.checks, name('index'), index.ok && decode(index).includes('window.__gaiusSite = '), `HTTP ${index.status}; bytes=${index.body.byteLength}; sha256=${index.sha256}`);
  const worker = await fetchFile('gaius-sw.js');
  check(report.checks, name('service-worker'), worker.ok && worker.body.byteLength > 0, `HTTP ${worker.status}; bytes=${worker.body.byteLength}`);
  const descriptorResponse = await fetchFile('gaius-site.json');
  let descriptor = null;
  try {
    descriptor = JSON.parse(decode(descriptorResponse));
  } catch {}
  site.id = typeof descriptor?.id === 'string' ? descriptor.id : null;
  const assetPaths = descriptor?.assets && typeof descriptor.assets === 'object' ? Object.values(descriptor.assets).map(String) : [];
  site.assets = assetPaths.length;
  check(report.checks, name('descriptor'), descriptorResponse.ok && descriptor?.profile === profile && Boolean(site.id) && assetPaths.length > 0, `HTTP ${descriptorResponse.status}; profile=${descriptor?.profile}; id=${site.id}; assets=${assetPaths.length}`);
  const missing = [];
  for (const path of assetPaths) {
    if (archived?.has(path)) continue;
    try {
      const response = await fetchImpl(new URL(path, siteBase).href, {method: 'HEAD', cache: 'no-store'});
      if (!response.ok) missing.push(`${path}: HTTP ${response.status}`);
    } catch (error) {
      missing.push(`${path}: ${error?.message || error}`);
    }
  }
  if (archived) {
    const fetched = new Map([['index.html', index], ['gaius-sw.js', worker], ['gaius-site.json', descriptorResponse]]);
    for (const [path, sha256] of archived) {
      let live = fetched.get(path);
      if (!live) {
        try {
          live = await fetchFile(path);
        } catch (error) {
          live = {ok: false, status: String(error?.message || error), sha256: ''};
        }
      }
      if (live.ok && live.sha256 === sha256) site.verifiedFiles++;
      else missing.push(`${path}: HTTP ${live.status}; live=${live.sha256}; archived=${sha256}`);
    }
    const unlisted = assetPaths.filter((path) => !archived.has(path));
    for (const path of unlisted) missing.push(`${path}: listed in gaius-site.json but not in the archive`);
    check(report.checks, name('archive-files'), missing.length === 0 && site.verifiedFiles === archived.size, `${site.verifiedFiles}/${archived.size} archived files served byte for byte${missing.length ? `; ${missing.slice(0, 8).join('; ')}` : ''}`);
  }
  check(report.checks, name('assets'), assetPaths.length > 0 && missing.length === 0, missing.length ? missing.slice(0, 8).join('; ') : `${assetPaths.length} assets answer 200`);
}
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

// Static Pages workflow gate. Comment lines are stripped first so a commented-out check cannot satisfy it.
// Every deployed page has its own exactly-one-record check and its own sha256sum gate line.
const pagesSha256GateLine = (file) => `(cd pages-publish && sha256sum --check --strict "$sums_dir/${file}.sha256")`;
const pagesRecordCountLine = (file) => `if [ "$(wc -l < "$sums_dir/${file}.sha256")" -ne 1 ]; then`;
// Releases from v0.4.0 on publish each profile as a multi-file site from Gaius-site-<profile>.tar.gz
// (port/scripts/build-pages-site.py); each archive is verified before it is listed or extracted.
const expectedSiteArchives = Object.freeze(Object.keys(expectedTargets).map((profile) => `Gaius-site-${profile}.tar.gz`));
const pagesSiteGateLine = (archive) => `(cd "$sites_dir" && sha256sum --check --strict "$sums_dir/${archive}.sha256")`;
const PAGES_SITE_EXTRACT_PREFIX = 'tar -xzf "$archive" -C "pages-publish/$profile"';
const PAGES_DEPLOYING_LINE = `Deploying ${expectedPages.slice(0, -1).join(', ')} and ${expectedPages.at(-1)} from`;
function pagesWorkflowProblems(raw) {
  const problems = [];
  const need = (ok, message) => { if (!ok) problems.push(message); };
  const lines = String(raw).replace(/\r\n/g, '\n').split('\n').filter((line) => !/^\s*#/.test(line));
  const code = lines.join('\n');
  const lineIndex = (predicate) => lines.findIndex(predicate);
  need(/^run-name: \S/m.test(code), 'run-name missing');
  need(/^ {6}release_token:\n {8}description: [^\n]+\n {8}required: true$/m.test(code), 'release_token must be required: true');
  need(/^ {6}release_tag:\n {8}description: [^\n]+\n {8}required: false\n {8}default: ''$/m.test(code), 'optional release_tag input missing');
  const triggers = code.split(/^on:[ \t]*$/m)[1]?.split(/^\S/m)[0] ?? '';
  need(JSON.stringify([...triggers.matchAll(/^ {2}([A-Za-z_]+):/gm)].map((match) => match[1])) === '["workflow_dispatch"]', 'Pages workflow must be dispatch-only');
  // Nothing from the repository is published: no LFS checkout (a checkout, if ever needed, must be lfs: false).
  need(!/^\s*lfs:\s*true\b/m.test(code), 'Pages workflow must not check out LFS objects');
  need(!code.includes('actions/checkout') || /^\s*lfs:\s*false\b/m.test(code), 'Pages checkout must use lfs: false');
  const downloads = [...code.matchAll(/gh release download ((?:[^\n]*\\\n)*[^\n]*)/g)]
    .map(([, args]) => ({patterns: [...args.matchAll(/--pattern '([^']+)'/g)].map((match) => match[1]), dirs: [...args.matchAll(/--dir (\S+)/g)].map((match) => match[1])}));
  need(JSON.stringify(downloads.filter((entry) => entry.dirs.includes('pages-publish'))) === JSON.stringify([{patterns: [...expectedPages], dirs: ['pages-publish']}]), 'Pages workflow must download exactly the expected pages into pages-publish');
  need(JSON.stringify(downloads.filter((entry) => !entry.dirs.includes('pages-publish'))) === JSON.stringify([{patterns: ['SHA256SUMS'], dirs: ['"$sums_dir"']}, {patterns: [...expectedSiteArchives], dirs: ['"$sites_dir"']}, {patterns: ['SHA256SUMS', ...expectedSiteArchives], dirs: ['"$previous_dir"']}]), 'Pages workflow must download SHA256SUMS into $sums_dir, the site archives into $sites_dir and the previous generation into $previous_dir');
  // The previous site generation (kept for tabs opened before the deploy) is a hint from the live
  // site: its archives verify against their own SHA256SUMS before they are listed or unpacked, and
  // only content-hashed names are merged into the publish directory.
  const previousGateLine = lineIndex((line) => line.trim() === '(cd "$previous_dir" && sha256sum --check --strict --quiet "${previous_archive}.sha256") || return 1');
  const previousListLine = lineIndex((line) => line.includes('tar -tvzf "$previous_dir/$previous_archive"'));
  const previousExtractLine = lineIndex((line) => line.trim().startsWith('tar -xzf "$previous_dir/$previous_archive" -C "$previous_dir/$profile" --no-same-owner --no-same-permissions'));
  need(previousGateLine >= 0 && previousListLine > previousGateLine && previousExtractLine > previousListLine, 'Pages workflow must verify the previous site generation before extracting it');
  need(lines.some((line) => line.trim() === '[[ "$entry" =~ \\.[0-9a-f]{16}\\.[A-Za-z0-9]+$ ]] || continue'), 'Pages workflow must merge only content-hashed files of the previous generation');
  need(lines.some((line) => line.includes('[[ "$previous_tag" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]]')), 'Pages workflow must validate the previous release tag');
  need(lines.some((line) => line.includes('> "pages-publish/$profile/gaius-sw.js"')) && lines.some((line) => line.includes('self.registration.unregister()')), 'Pages workflow must retire the site Service Worker in the single-file layout');
  need(code.includes('sums_dir="${RUNNER_TEMP}/'), 'Pages workflow must keep SHA256SUMS outside the publish directory');
  need(code.includes('sites_dir="${RUNNER_TEMP}/'), 'Pages workflow must keep the site archives outside the publish directory');
  need(code.includes(`find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length}`), 'Pages workflow file-count assertion missing');
  need(code.includes('gh release view --repo'), 'Pages workflow Latest-release default missing');
  need(!/^\s*set\s+\+[a-z]*e/m.test(code), 'Pages workflow must not disable errexit');
  // Per page, the exactly-one-record check and the sha256 gate must each be a whole, unguarded line.
  const uploadLine = lineIndex((line) => line.includes('actions/upload-pages-artifact'));
  let lastGateLine = -1;
  for (const file of expectedPages) {
    const countLine = lineIndex((line) => line.trim() === pagesRecordCountLine(file));
    need(countLine >= 0 && /^\s*echo "::error::/.test(lines[countLine + 1] ?? '') && lines[countLine + 2]?.trim() === 'exit 1', `Pages workflow exactly-one SHA256SUMS record (-ne 1) check missing for ${file}`);
    const gateLine = lineIndex((line) => line.trim() === pagesSha256GateLine(file));
    need(gateLine >= 0, `Pages workflow sha256sum --check --strict gate line missing for ${file}`);
    need(countLine >= 0 && gateLine > countLine && uploadLine > gateLine, `Pages workflow must verify SHA256SUMS of ${file} before uploading`);
    lastGateLine = Math.max(lastGateLine, gateLine);
  }
  // Site layout: every archive has its exactly-one-record check and its sha256 gate, all gates
  // run before the first archive is listed or extracted, and only plain relative files unpack.
  const firstListLine = lineIndex((line) => line.includes('tar -tvzf "$archive"'));
  const extractLine = lineIndex((line) => line.trim().startsWith(PAGES_SITE_EXTRACT_PREFIX));
  need(extractLine >= 0 && lines[extractLine].includes('--no-same-owner') && lines[extractLine].includes('--no-same-permissions'), 'Pages workflow must extract each site archive into pages-publish/$profile without owners or modes');
  need(firstListLine >= 0 && firstListLine < extractLine && lines[firstListLine].includes('substr($0, 1, 1) != "-"'), 'Pages workflow must reject site archive entries that are not regular files');
  need(lines.some((line, index) => line.includes('[[ "/$entry/" == *"/../"* ]]') && /^\s*echo "::error::/.test(lines[index + 1] ?? '')), 'Pages workflow must reject site archive paths with ..');
  need(uploadLine > extractLine, 'Pages workflow must extract the site archives before uploading');
  for (const archive of expectedSiteArchives) {
    const countLine = lineIndex((line) => line.trim() === pagesRecordCountLine(archive));
    need(countLine >= 0 && /^\s*echo "::error::/.test(lines[countLine + 1] ?? '') && lines[countLine + 2]?.trim() === 'exit 1', `Pages workflow exactly-one SHA256SUMS record (-ne 1) check missing for ${archive}`);
    const gateLine = lineIndex((line) => line.trim() === pagesSiteGateLine(archive));
    need(gateLine >= 0, `Pages workflow sha256sum --check --strict gate line missing for ${archive}`);
    need(countLine >= 0 && gateLine > countLine && firstListLine > gateLine && extractLine > gateLine, `Pages workflow must verify ${archive} before extracting it`);
  }
  for (const profile of Object.keys(expectedTargets)) {
    need(code.includes(`Gaius-site-${profile.replace(/\./g, '\\.')}\\.tar\\.gz$' "$sums_dir/SHA256SUMS"`), `Pages workflow must match the ${profile} site archive record exactly`);
  }
  need(code.includes(`"<script>location.replace(\\"\${profile}/\\" + location.search + location.hash);</script>"`), 'Pages workflow must turn Gaius-<profile>.html into a redirect to <profile>/');
  need(/published_bytes" -ge 1000000000 \]/.test(code), 'Pages workflow must refuse a site of 1 GB or more');
  need(!lines.some((line) => line.includes('sha256sum') && /\|\|\s*(?:true|:)(?=\s|;|\)|$)/.test(line)), 'Pages workflow sha256sum gate must not be neutralised with || true / || :');
  const deployingLines = lines.flatMap((line, index) => line.includes(PAGES_DEPLOYING_LINE) ? [index] : []);
  need(!lines.some((line) => /Deploying Gaius-[^\n]*\.html/.test(line) && !line.includes(PAGES_DEPLOYING_LINE)), 'Pages workflow must announce all deployed pages together');
  need(deployingLines.length === 1 && deployingLines[0] > lastGateLine && deployingLines[0] < uploadLine, 'Pages workflow must announce the deployment only after the sha256 gates');
  // Workflow-command lines must never carry the raw input or a not-yet-validated tag.
  for (const line of lines.filter((entry) => /\becho\s+["']?::/.test(entry) || /::[a-z-]+(?: [^:\n]*)?::/.test(entry))) {
    need(!/REQUESTED_RELEASE_TAG|inputs\.|github\.event|\$\{?tag\b/.test(line), `Pages workflow echoes an unvalidated release tag in a workflow command: ${line.trim()}`);
  }
  return problems;
}

if (process.argv.includes('--static-self-test')) {
  assert.deepEqual(expectedPages, ['Gaius-1.21.11.html', 'Gaius-26.2.html', 'Gaius-26.3.html']);
  assert.deepEqual(retiredPages, []);
  assert.ok(retiredPages.every((file) => !expectedPages.includes(file)), 'a retired page is still expected');
  assert.deepEqual(Object.keys(expectedTargets), ['1.21.11', '26.2', '26.3']);
  assert.deepEqual(Object.keys(expectedPageTargets), ['1.21.11', '26.2', '26.3', 'defaultTarget']);
  assert.equal(PAGES_DEPLOYING_LINE, 'Deploying Gaius-1.21.11.html, Gaius-26.2.html and Gaius-26.3.html from');
  // The live sha256 binding: well-formed expectations only, per page, and a mismatch fails the gate.
  assert.deepEqual(Object.keys(expectedSha256Variables), [...expectedPages], 'every deployed page needs an expected-sha256 variable');
  const abc = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
  assert.equal(sha256Hex(new TextEncoder().encode('abc')), abc);
  assert.equal(parseExpectedSha256(undefined), '');
  assert.equal(parseExpectedSha256(`  ${abc.toUpperCase()}\n`), abc);
  for (const bad of ['abc', abc.slice(1), `${abc}0`, `${abc.slice(1)}g`]) assert.throws(() => parseExpectedSha256(bad), /64 hexadecimal/);
  assert.deepEqual(expectedSha256ByPage({}), {'Gaius-1.21.11.html': '', 'Gaius-26.2.html': '', 'Gaius-26.3.html': ''});
  assert.deepEqual(expectedSha256ByPage({GAIUS_PAGES_EXPECTED_SHA256: abc}), {'Gaius-1.21.11.html': '', 'Gaius-26.2.html': abc, 'Gaius-26.3.html': ''}, 'the legacy variable binds 26.2 only');
  assert.deepEqual(expectedSha256ByPage({GAIUS_PAGES_EXPECTED_SHA256_12111: '1'.repeat(64), GAIUS_PAGES_EXPECTED_SHA256_262: abc, GAIUS_PAGES_EXPECTED_SHA256_263: '0'.repeat(64)}), {'Gaius-1.21.11.html': '1'.repeat(64), 'Gaius-26.2.html': abc, 'Gaius-26.3.html': '0'.repeat(64)});
  assert.throws(() => expectedSha256ByPage({GAIUS_PAGES_EXPECTED_SHA256_12111: 'nope'}), /GAIUS_PAGES_EXPECTED_SHA256_12111 must be 64 hexadecimal/);
  assert.throws(() => expectedSha256ByPage({GAIUS_PAGES_EXPECTED_SHA256_263: 'nope'}), /GAIUS_PAGES_EXPECTED_SHA256_263 must be 64 hexadecimal/);
  const fixture = new TextEncoder().encode('abc').buffer;
  for (const page of expectedPages) {
    for (const [expected, ok] of [[abc, true], ['0'.repeat(64), false], ['', null]]) {
      const fake = {checks: [], live: {}};
      recordLivePage(fake, page, 200, true, fixture, expected);
      assert.deepEqual(fake.live[page], {status: 200, bytes: 3, sha256: abc});
      assert.deepEqual(fake.checks.filter((entry) => entry.name.endsWith('-sha256')).map((entry) => entry.ok), ok === null ? [] : [ok]);
    }
  }
  // The live-hash retry: a stale canonical response is re-fetched (same URL, no cache-busting) until the edge
  // serves the release bytes, every attempt is recorded, fetch errors are retried, and the window is bounded.
  assert.equal(parseRetryWindowMs(undefined), DEFAULT_SHA256_RETRY_MS);
  assert.equal(parseRetryWindowMs('0'), 0);
  assert.equal(parseRetryWindowMs(' 1500 '), 1500);
  for (const bad of ['-1', '1.5', 'ten', '1e3']) assert.throws(() => parseRetryWindowMs(bad), /non-negative integer/);
  const fakeClock = () => { let t = 0; return {now: () => t, sleepImpl: async (ms) => { assert.ok(ms >= 0 && ms <= SHA256_RETRY_MAX_DELAY_MS); t += ms; }}; };
  const fakeFetch = (bodies, urls) => async (url) => { urls.push(url); const text = bodies.shift() ?? 'stale'; return {status: 200, ok: true, arrayBuffer: async () => new TextEncoder().encode(text).buffer}; };
  {
    const urls = []; const clock = fakeClock();
    const live = await fetchLivePage('https://pages.invalid/Gaius-26.2.html', abc, {windowMs: 600_000, fetchImpl: fakeFetch(['stale', 'stale', 'abc'], urls), ...clock});
    assert.equal(live.sha256, abc);
    assert.deepEqual(urls, Array(3).fill('https://pages.invalid/Gaius-26.2.html'), 'retries must re-fetch the canonical URL');
    const stale = sha256Hex(new TextEncoder().encode('stale'));
    assert.deepEqual(live.attempts.map(({attempt, status, bytes, sha256}) => ({attempt, status, bytes, sha256})), [
      {attempt: 0, status: 200, bytes: 5, sha256: stale},
      {attempt: 1, status: 200, bytes: 5, sha256: stale},
      {attempt: 2, status: 200, bytes: 3, sha256: abc}]);
  }
  {
    const urls = []; const clock = fakeClock();
    const live = await fetchLivePage('https://pages.invalid/Gaius-26.2.html', abc, {windowMs: 600_000, fetchImpl: fakeFetch([], urls), ...clock});
    assert.notEqual(live.sha256, abc);
    assert.ok(clock.now() <= 600_000 && urls.length === live.attempts.length && urls.length > 5 && urls.length < 20, `bounded retries: ${urls.length}`);
  }
  {
    // A first-attempt network error is recorded and retried instead of aborting the check.
    const clock = fakeClock(); let calls = 0;
    const flaky = async () => { calls++; if (calls === 1) throw new Error('reset'); return {status: 200, ok: true, arrayBuffer: async () => new TextEncoder().encode('abc').buffer}; };
    const live = await fetchLivePage('https://pages.invalid/Gaius-26.2.html', abc, {windowMs: 600_000, fetchImpl: flaky, ...clock});
    assert.equal(live.sha256, abc);
    assert.deepEqual(live.attempts.map((a) => a.error ?? a.sha256), ['reset', abc]);
  }
  for (const [expected, windowMs] of [['', 600_000], [abc, 0]]) {
    const urls = [];
    const live = await fetchLivePage('https://pages.invalid/Gaius-26.2.html', expected, {windowMs, fetchImpl: fakeFetch([], urls), ...fakeClock()});
    assert.equal(live.attempts.length, 1); assert.equal(urls.length, 1);
  }
  // Site layout: the redirect page pages.yml writes is recognised per profile, site archives parse
  // (ustar and pax names), and the live site check binds every served file to the archive.
  assert.deepEqual(Object.keys(expectedSiteSha256Variables), [...expectedPages], 'every deployed page needs an expected-site-sha256 variable');
  assert.deepEqual(expectedSiteArchives, ['Gaius-site-1.21.11.tar.gz', 'Gaius-site-26.2.tar.gz', 'Gaius-site-26.3.tar.gz']);
  assert.deepEqual(expectedSiteSha256ByPage({}), {'Gaius-1.21.11.html': '', 'Gaius-26.2.html': '', 'Gaius-26.3.html': ''});
  assert.deepEqual(expectedSiteSha256ByPage({GAIUS_PAGES_EXPECTED_SITE_SHA256_263: abc.toUpperCase()})['Gaius-26.3.html'], abc);
  assert.throws(() => expectedSiteSha256ByPage({GAIUS_PAGES_EXPECTED_SITE_SHA256_12111: 'nope'}), /GAIUS_PAGES_EXPECTED_SITE_SHA256_12111 must be 64 hexadecimal/);
  for (const file of expectedPages) {
    const profile = profileOfPage(file);
    const redirect = ['<!doctype html>', '<meta charset="utf-8">', `<title>Gaius ${profile}</title>`,
      `<script>location.replace("${profile}/" + location.search + location.hash);</script>`,
      `<meta http-equiv="refresh" content="0; url=${profile}/">`, ''].join('\n');
    assert.ok(isSiteRedirect(new TextEncoder().encode(redirect).buffer, profile), `redirect page of ${profile}`);
    for (const other of expectedPages.filter((entry) => entry !== file)) assert.ok(!isSiteRedirect(new TextEncoder().encode(redirect).buffer, profileOfPage(other)));
    assert.ok(!isSiteRedirect(new TextEncoder().encode('<!doctype html><title>Gaius</title>').buffer, profile));
  }
  assert.ok(isHashedSitePath('classes.0123456789abcdef.js') && isHashedSitePath('kernels/light.simd.0123456789abcdef.wasm') && isHashedSitePath('vanilla-assets.pack.0123456789abcdef.gz'));
  assert.ok(!isHashedSitePath('index.html') && !isHashedSitePath('gaius-sw.js') && !isHashedSitePath('relay-nodes.json'));
  const tarHeader = (path, size, type = 48) => {
    const header = new Uint8Array(512);
    const put = (text, at) => header.set(new TextEncoder().encode(text), at);
    put(path.slice(0, 100), 0); put('0000644\0', 100); put('0000000\0', 108); put('0000000\0', 116);
    put(`${size.toString(8).padStart(11, '0')}\0`, 124); put('00000000000\0', 136); put('        ', 148);
    header[156] = type; put('ustar\0', 257); put('00', 263);
    return header;
  };
  const tarArchive = (files, {paxFor = ''} = {}) => {
    const parts = [];
    for (const [path, text] of files) {
      const bytes = new TextEncoder().encode(text);
      if (path === paxFor) {
        const record = ` path=${path}\n`;
        let total = record.length + String(record.length).length;
        if (String(total).length !== String(record.length).length) total++;
        const pax = new TextEncoder().encode(`${total}${record}`);
        parts.push(tarHeader('././@PaxHeader', pax.length, 120), pax, new Uint8Array((512 - (pax.length % 512)) % 512));
      }
      parts.push(tarHeader(path === paxFor ? 'truncated-name' : path, bytes.length), bytes, new Uint8Array((512 - (bytes.length % 512)) % 512));
    }
    parts.push(new Uint8Array(1024));
    return gzipSync(Buffer.concat(parts.map((part) => Buffer.from(part))));
  };
  const longName = `kernels/${'x'.repeat(120)}.0123456789abcdef.wasm`;
  assert.deepEqual(readTarEntries(tarArchive([['a.txt', 'one'], [longName, 'two'], ['empty', '']], {paxFor: longName}))
    .map((entry) => [entry.path, Buffer.from(entry.bytes).toString()]), [['a.txt', 'one'], [longName, 'two'], ['empty', '']]);
  assert.throws(() => readTarEntries(gzipSync(Buffer.concat([Buffer.from(tarHeader('link', 0, 50)), Buffer.alloc(1024)]))), /non-file entry/);
  {
    const profile = '26.3';
    const file = `Gaius-${profile}.html`;
    const descriptor = {schema: 1, id: '0011223344556677', profile, assets: {'classes.js': 'classes.0123456789abcdef.js', 'gaius-boot.js': 'gaius-boot.fedcba9876543210.js'}};
    const served = {
      'index.html': '<script data-gaius-site="v1">\n    window.__gaiusSite = {};\n</script>',
      'gaius-sw.js': 'self.addEventListener("fetch", () => {});',
      'gaius-site.json': JSON.stringify(descriptor),
      'classes.0123456789abcdef.js': 'main();',
      'gaius-boot.fedcba9876543210.js': 'boot();',
    };
    const archive = tarArchive(Object.entries(served));
    const archiveDirectory = await mkdtemp(join(tmpdir(), 'gaius-pages-site-'));
    try {
      await writeFile(join(archiveDirectory, `Gaius-site-${profile}.tar.gz`), archive);
      const siteBase = new URL(`${profile}/`, base).href;
      const fakeSite = (files) => async (url, init = {}) => {
        const path = String(url).slice(siteBase.length);
        const ok = Object.prototype.hasOwnProperty.call(files, path);
        const bytes = new TextEncoder().encode(ok ? files[path] : 'not found');
        return {status: ok ? 200 : 404, ok, arrayBuffer: async () => (init.method === 'HEAD' ? new ArrayBuffer(0) : bytes.buffer)};
      };
      const run = async (files, options) => {
        const fake = {checks: [], live: {}, site: {}};
        await checkSite(fake, file, {fetchImpl: fakeSite(files), ...fakeClock(), ...options});
        return Object.fromEntries(fake.checks.map((entry) => [entry.name.slice(`${profile}-site-`.length), entry.ok]));
      };
      const bound = {expectedSiteSha256: sha256Hex(archive), archiveDirectory, windowMs: 0};
      assert.deepEqual(await run(served, {}), {index: true, 'service-worker': true, descriptor: true, assets: true});
      assert.deepEqual(await run(served, bound), {archive: true, 'archive-sha256': true, index: true, 'service-worker': true, descriptor: true, 'archive-files': true, assets: true});
      const tampered = await run({...served, 'classes.0123456789abcdef.js': 'evil();'}, bound);
      assert.equal(tampered['archive-files'], false, 'a served file that differs from the archive fails');
      const gone = await run(Object.fromEntries(Object.entries(served).filter(([path]) => path !== 'gaius-boot.fedcba9876543210.js')), {});
      assert.equal(gone.assets, false, 'a listed asset that does not answer fails');
      assert.equal((await run(served, {...bound, expectedSiteSha256: '0'.repeat(64)}))['archive-sha256'], false);
      assert.equal((await run(served, {...bound, archiveDirectory: ''})).archive, false, 'a bound site needs its archive');
      assert.equal((await run({...served, 'index.html': '<title>Gaius</title>'}, {})).index, false);
    } finally {
      await rm(archiveDirectory, {recursive: true, force: true});
    }
  }
  // The Pages workflow gate, checked on the real file and on mutations that must each be rejected.
  const workflow = await readFile(new URL('../.github/workflows/pages.yml', import.meta.url), 'utf8');
  assert.deepEqual(pagesWorkflowProblems(workflow), [], 'Pages workflow gate');
  const mutations = {
    'raw tag in ::error::': (text) => text.replace('echo "::error::Refusing unexpected release tag from ${source}"', 'echo "::error::Refusing unexpected release tag ${REQUESTED_RELEASE_TAG}"'),
    'raw tag in ::warning::': (text) => text.replace('          echo "tag=${tag}" >> "$GITHUB_OUTPUT"', '          echo "::warning::Requested $REQUESTED_RELEASE_TAG"\n          echo "tag=${tag}" >> "$GITHUB_OUTPUT"'),
    'unvalidated $tag in ::error::': (text) => text.replace('echo "::error::Refusing unexpected release tag from ${source}"', 'echo "::error::Refusing ${tag}"'),
    'LFS checkout': (text) => text.replace('      - name: Configure Pages', '      - uses: actions/checkout@v4\n        with:\n          lfs: true\n      - name: Configure Pages'),
    'push trigger': (text) => text.replace('on:\n  workflow_dispatch:', 'on:\n  push:\n  workflow_dispatch:'),
    'early Deploying summary': (text) => text.replace('          echo "tag=${tag}" >> "$GITHUB_OUTPUT"', `          echo "tag=\${tag}" >> "$GITHUB_OUTPUT"\n          echo "${PAGES_DEPLOYING_LINE} \${tag}" >> "$GITHUB_STEP_SUMMARY"`),
    'partial Deploying summary': (text) => text.replace(`echo "${PAGES_DEPLOYING_LINE} `, 'echo "Deploying Gaius-26.2.html from '),
    'optional release_token': (text) => text.replace('        required: true', '        required: false'),
    'one page fewer': (text) => text.replace("  --pattern 'Gaius-1.21.11.html'\n", '\n').replace(`find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length}`, `find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length - 1}`),
    'file count for one page fewer': (text) => text.replace(`find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length}`, `find pages-publish -mindepth 1 | wc -l)" -eq ${expectedPages.length - 1}`),
  };
  for (const file of expectedPages) {
    const gate = pagesSha256GateLine(file);
    const count = pagesRecordCountLine(file);
    mutations[`commented gate ${file}`] = (text) => text.replace(gate, `# ${gate}`);
    mutations[`gate || true ${file}`] = (text) => text.replace(gate, `${gate} || true`);
    mutations[`gate || : ${file}`] = (text) => text.replace(gate, `${gate} || :`);
    mutations[`removed gate ${file}`] = (text) => text.replace(`          ${gate}\n`, '');
    mutations[`removed -ne 1 ${file}`] = (text) => text.replace(count, count.replace(' -ne 1 ]', ' -lt 1 ]'));
    mutations[`commented -ne 1 ${file}`] = (text) => text.replace(count, `# ${count}\n          if false; then`);
    mutations[`gate after upload ${file}`] = (text) => text.replace(`          ${gate}\n`, '').replace('      - name: Deploy Pages\n', `      - run: ${gate}\n      - name: Deploy Pages\n`);
  }
  const extractText = '              tar -xzf "$archive" -C "pages-publish/$profile" --no-same-owner --no-same-permissions\n';
  for (const archive of expectedSiteArchives) {
    const gate = pagesSiteGateLine(archive);
    const count = pagesRecordCountLine(archive);
    mutations[`commented site gate ${archive}`] = (text) => text.replace(gate, `# ${gate}`);
    mutations[`site gate || true ${archive}`] = (text) => text.replace(gate, `${gate} || true`);
    mutations[`removed site gate ${archive}`] = (text) => text.replace(`            ${gate}\n`, '');
    mutations[`removed site -ne 1 ${archive}`] = (text) => text.replace(count, count.replace(' -ne 1 ]', ' -lt 1 ]'));
    mutations[`site gate after extraction ${archive}`] = (text) => text.replace(`            ${gate}\n`, '').replace(extractText, `${extractText}              ${gate}\n`);
  }
  Object.assign(mutations, {
    'site archives into pages-publish': (text) => text.replace('              --dir "$sites_dir" \\\n', '              --dir pages-publish \\\n'),
    'one site archive fewer': (text) => text.replace("              --pattern 'Gaius-site-26.2.tar.gz' \\\n", ''),
    'site extraction keeps owners': (text) => text.replace(' --no-same-owner --no-same-permissions', ''),
    'site entry type check removed': (text) => text.replace("substr($0, 1, 1) != \"-\"", 'substr($0, 1, 1) == "?"'),
    'site .. check removed': (text) => text.replace(' || [[ "/$entry/" == *"/../"* ]]', ''),
    'site redirect removed': (text) => text.replace('"<script>location.replace(\\"${profile}/\\" + location.search + location.hash);</script>"', '"<p>moved</p>"'),
    'site size limit removed': (text) => text.replace('if [ "$published_bytes" -ge 1000000000 ]; then', 'if false; then'),
    'loose site record match': (text) => text.replace("Gaius-site-26\\.3\\.tar\\.gz$'", "Gaius-site-26.3'"),
    'previous generation unverified': (text) => text.replace('(cd "$previous_dir" && sha256sum --check --strict --quiet "${previous_archive}.sha256") || return 1', 'true'),
    'previous generation merges every name': (text) => text.replace('[[ "$entry" =~ \\.[0-9a-f]{16}\\.[A-Za-z0-9]+$ ]] || continue', 'true'),
    'previous tag unvalidated': (text) => text.replace('[[ "$previous_tag" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]] && ', ''),
    'site worker not retired': (text) => text.replace('> "pages-publish/$profile/gaius-sw.js"', '> /dev/null'),
  });
  const lfWorkflow = workflow.replace(/\r\n/g, '\n');
  for (const [name, mutate] of Object.entries(mutations)) {
    const mutated = mutate(lfWorkflow);
    assert.notEqual(mutated, lfWorkflow, `mutation ${name} did not apply`);
    assert.ok(pagesWorkflowProblems(mutated).length > 0, `mutation ${name} was not caught`);
  }
  console.log('VERIFY_GITHUB_PAGES_CDP_STATIC_OK'); process.exit(0);
}

const report = {schema: 'gaius.github-pages-cdp.v7', base, expectedPages, retiredPages, expectedSha256: null, expectedSiteSha256: null, sha256RetryWindowMs: null, layout: {}, checks: [], pages: [], live: {}, site: {}};
let chrome; let cdp; let profileDir;
try {
  const expectedSha256 = expectedSha256ByPage(process.env);
  report.expectedSha256 = Object.fromEntries(expectedPages.map((file) => [file, expectedSha256[file] || null]));
  const expectedSiteSha256 = expectedSiteSha256ByPage(process.env);
  report.expectedSiteSha256 = Object.fromEntries(expectedPages.map((file) => [file, expectedSiteSha256[file] || null]));
  const siteArchives = String(process.env.GAIUS_PAGES_SITE_ARCHIVES || '').trim();
  const sha256RetryWindowMs = parseRetryWindowMs(process.env.GAIUS_PAGES_SHA256_RETRY_MS);
  report.sha256RetryWindowMs = expectedPages.some((file) => expectedSha256[file] || expectedSiteSha256[file]) ? sha256RetryWindowMs : null;
  const rootResponse = await fetch(base, {redirect: 'manual', cache: 'no-store'});
  check(report.checks, 'root-not-published', rootResponse.status === 404, `HTTP ${rootResponse.status}`);
  for (const file of expectedPages) {
    const url = new URL(file, base).href;
    const profile = profileOfPage(file);
    // A site release waits (bounded) until the edge serves the redirect page; a single-file release
    // until it serves the release bytes.
    const live = expectedSiteSha256[file]
      ? await fetchLivePage(url, '', {windowMs: sha256RetryWindowMs, accept: (result) => isSiteRedirect(result.body, profile)})
      : await fetchLivePage(url, expectedSha256[file], {windowMs: sha256RetryWindowMs});
    const layout = isSiteRedirect(live.body, profile) ? 'site' : 'single-file';
    report.layout[file] = layout;
    if (layout === 'site') {
      report.live[file] = {status: live.status, bytes: live.body.byteLength, sha256: live.sha256, attempts: live.attempts};
      check(report.checks, `${file}-redirect`, live.ok, `HTTP ${live.status}; redirects to ${profile}/`);
      if (expectedSha256[file]) check(report.checks, `${file}-sha256`, false, `${file} redirects to the ${profile}/ site; bind ${expectedSiteSha256Variables[file]} and GAIUS_PAGES_SITE_ARCHIVES instead`);
      await checkSite(report, file, {expectedSiteSha256: expectedSiteSha256[file], archiveDirectory: siteArchives, windowMs: sha256RetryWindowMs});
    } else {
      if (expectedSiteSha256[file]) check(report.checks, `${file}-layout`, false, `${expectedSiteSha256Variables[file]} expects the ${profile}/ site, but ${file} is a single-file client`);
      recordLivePage(report, file, live.status, live.ok, live.body, expectedSha256[file]);
      report.live[file].attempts = live.attempts;
    }
  }
  for (const file of retiredPages) { const url = new URL(file, base).href; const response = await fetch(url, {method: 'HEAD', redirect: 'manual', cache: 'no-store'}); check(report.checks, `${file}-retired`, response.status === 404, `HTTP ${response.status}`); }
  const debugPort = await freePort(); profileDir = await mkdtemp(join(tmpdir(), 'gaius-pages-cdp-'));
  chrome = spawn(chromeBinary, ['--headless=new', `--remote-debugging-port=${debugPort}`, '--remote-allow-origins=*', `--user-data-dir=${profileDir}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu'], {windowsHide: true});
  const target = (await waitJson(`http://127.0.0.1:${debugPort}/json/list`)).find((entry) => entry.type === 'page'); if (!target?.webSocketDebuggerUrl) throw new Error('Chrome page target missing');
  cdp = new Cdp(target.webSocketDebuggerUrl); await cdp.open(); await cdp.send('Runtime.enable');
  for (const file of expectedPages) {
    const url = new URL(file, base).href;
    const site = report.layout[file] === 'site';
    // A site page lands on <profile>/ (the redirect keeps an empty query and fragment).
    const landing = site ? new URL(`${profileOfPage(file)}/`, base).href : url;
    await cdp.send('Page.navigate', {url});
    let snapshot;
    for (let attempt = 0; attempt < 100; attempt++) {
      await sleep(100);
      try {
        snapshot = await cdp.evaluate('({href:location.href,readyState:document.readyState,title:document.title,bytes:document.documentElement?.outerHTML?.length||0,site:!!document.querySelector("script[data-gaius-site]")})');
      } catch {
        // The redirect replaced the document while it was being read; read the new one.
        continue;
      }
      if (snapshot?.href === landing && snapshot?.readyState === 'complete') break;
    }
    report.pages.push({file, layout: report.layout[file], ...snapshot});
    check(report.checks, `${file}-chrome`, snapshot?.href === landing && snapshot?.readyState === 'complete' && snapshot?.title.includes('Gaius')
      && (site ? snapshot?.site === true : snapshot?.bytes > 100_000_000), JSON.stringify(snapshot));
  }
} catch (error) { report.error = String(error?.stack || error); }
finally {
  cdp?.close();
  await stopChrome(chrome, cdp, profileDir);
  const cdpClosed = !cdp || cdp.closed || cdp.socket.readyState === WebSocket.CLOSING;
  const chromeExited = !chrome || chrome.exitCode !== null || chrome.killed;
  const profileRemoved = !profileDir || !(await import('node:fs')).existsSync(profileDir);
  const pagesFinalGate = report.pages.length === expectedPages.length
    && report.checks.filter((entry) => entry.name.endsWith('-chrome')).every((entry) => entry.ok)
    && expectedPages.every((file) => (report.layout[file] === 'site'
      ? !report.expectedSiteSha256?.[file] || report.checks.some((entry) => entry.name === `${profileOfPage(file)}-site-archive-files` && entry.ok)
      : !report.expectedSha256?.[file] || report.checks.some((entry) => entry.name === `${file}-sha256` && entry.ok)));
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
