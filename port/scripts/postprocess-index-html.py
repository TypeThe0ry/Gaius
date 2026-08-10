#!/usr/bin/env python3
"""Patch the ignored dist index page after TeaVM emits classes.js.

The browser client keeps the HTML launcher in port/web/dist, which is ignored
with the rest of the generated assets. Keep browser startup fixes reproducible
by applying them from this tracked script after every successful TeaVM build.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise RuntimeError(f"index.html patch point was not found: {label}")


def migrate_raf_frame_samples(text: str) -> str:
    """Replace the legacy shifting FPS sample array with a bounded ring."""
    text = text.replace(
        "        fps.rafFrameTimes = [];\n"
        "        fps.rafLastFrameAt = 0;\n",
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n",
    )
    text = text.replace(
        "          const samples = fps.rafFrameTimes || (fps.rafFrameTimes = []);\n"
        "          samples.push(frameMs);\n"
        "          if (samples.length > 2048) samples.splice(0, samples.length - 2048);\n",
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n",
    )
    text = text.replace(
        "        const samples = fps.rafFrameTimes || [];\n"
        "        if (samples.length > 0) {\n"
        "          const ordered = samples.slice().sort((left, right) => left - right);\n",
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n",
    )
    text = text.replace(
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n",
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const slowestCount = Math.max(1, Math.ceil(ordered.length * 0.01));\n"
        "          let slowestTotalMs = 0;\n"
        "          for (let index = ordered.length - slowestCount; index < ordered.length; index++) {\n"
        "            slowestTotalMs += ordered[index];\n"
        "          }\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((slowestCount * 1000 / slowestTotalMs) * 10) / 10;\n",
    )
    return text


def content_token(*paths: Path) -> str:
    digest = hashlib.sha256()
    found = False
    for path in paths:
        if not path.is_file():
            continue
        found = True
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()[:16] if found else "dev"

GAIUS_SHELL_MARKER = 'data-gaius-shell="v2"'

GAIUS_SHELL_CSS = r'''
    /* Gaius Client shell v2: browser launcher controls only. */
    :root {
      --gaius-shell-bg: #0b1116;
      --gaius-shell-panel: #111a21;
      --gaius-shell-panel-strong: #17232c;
      --gaius-shell-line: #31414c;
      --gaius-shell-muted: #9aaab4;
      --gaius-shell-text: #f1f5f7;
      --gaius-shell-accent: #9bd36a;
      --gaius-shell-accent-strong: #c3ec8f;
      --gaius-shell-danger: #f08a7b;
    }

    html,
    body {
      background: var(--gaius-shell-bg);
      color: var(--gaius-shell-text);
    }

    #mc-canvas {
      position: fixed;
      inset: 0;
      width: 100vw;
      height: 100vh;
      background: #05080a;
    }

    #boot-screen {
      z-index: 10;
      background: var(--gaius-shell-bg);
    }

    #boot-screen::before {
      position: absolute;
      inset: 18px;
      content: "";
      border: 1px solid rgba(155, 211, 106, 0.18);
      pointer-events: none;
    }

    #gaius-shell-header {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 12;
      display: flex;
      align-items: center;
      min-height: 56px;
      box-sizing: border-box;
      padding: 0 24px;
      border-bottom: 2px solid var(--gaius-shell-line);
      background: var(--gaius-shell-bg);
      pointer-events: none;
      transition: opacity 140ms linear, visibility 140ms linear;
    }

    #gaius-shell-brand {
      display: flex;
      align-items: baseline;
      gap: 10px;
      font: 800 15px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #gaius-shell-brand strong {
      color: var(--gaius-shell-accent-strong);
      font-size: 19px;
    }

    #gaius-shell-mode,
    #gaius-shell-version {
      color: var(--gaius-shell-muted);
      font: 700 11px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #gaius-shell-mode {
      margin-left: auto;
      margin-right: 18px;
      color: var(--gaius-shell-accent);
    }

    #gaius-shell-footer {
      position: fixed;
      left: 24px;
      right: 24px;
      bottom: 18px;
      z-index: 12;
      display: flex;
      justify-content: space-between;
      gap: 16px;
      color: var(--gaius-shell-muted);
      font: 11px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      pointer-events: none;
      transition: opacity 140ms linear, visibility 140ms linear;
    }

    #gaius-shell-footer strong {
      color: var(--gaius-shell-text);
      font-weight: 700;
    }

    html[data-gaius-shell-view="canvas"] #gaius-shell-header,
    html[data-gaius-shell-view="canvas"] #gaius-shell-footer {
      visibility: hidden;
      opacity: 0;
    }

    #boot-brand {
      top: 40%;
      z-index: 11;
      max-width: calc(100vw - 32px);
      color: var(--gaius-shell-text);
      font: 900 72px/0.84 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-shadow: 0 4px 0 #27343b;
    }

    #boot-brand span {
      margin-top: 14px;
      color: var(--gaius-shell-accent);
      font-size: 12px;
      font-weight: 700;
      line-height: 1;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #boot-progress {
      top: 58%;
      z-index: 11;
      width: min(480px, calc(100vw - 48px));
      height: 14px;
      padding: 2px;
      border: 2px solid var(--gaius-shell-text);
      border-radius: 0;
      background: transparent;
    }

    #boot-progress-bar {
      min-width: 2px;
      background: var(--gaius-shell-accent);
      transition: width 160ms linear;
    }

    #boot-progress-text {
      top: calc(58% + 28px);
      z-index: 11;
      color: var(--gaius-shell-muted);
      font: 700 12px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: center;
      text-shadow: none;
    }

    #status {
      top: calc(58% + 62px);
      z-index: 11;
      width: min(660px, calc(100vw - 48px));
      max-height: 22vh;
      box-sizing: border-box;
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      color: var(--gaius-shell-muted);
      font: 12px/1.45 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      letter-spacing: 0;
      text-align: center;
    }

    #status[data-state="error"] {
      max-height: 25vh;
      overflow: auto;
      padding: 12px 14px;
      border: 2px solid var(--gaius-shell-danger);
      border-left-width: 5px;
      background: var(--gaius-shell-panel);
      color: var(--gaius-shell-text);
      text-align: left;
    }

    #gaius-error-actions {
      position: fixed;
      top: calc(58% + 190px);
      left: 50%;
      z-index: 12;
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 8px;
      width: min(660px, calc(100vw - 48px));
      transform: translateX(-50%);
    }

    #gaius-error-actions[hidden],
    #gaius-error-details[hidden] {
      display: none;
    }

    #gaius-error-actions button {
      min-height: 36px;
      box-sizing: border-box;
      padding: 0 14px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: var(--gaius-shell-panel-strong);
      color: var(--gaius-shell-text);
      font: 700 12px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      cursor: pointer;
    }

    #gaius-error-actions button:first-child {
      border-color: var(--gaius-shell-accent);
      background: var(--gaius-shell-accent);
      color: #0b1116;
    }

    #gaius-error-actions button:hover,
    #gaius-error-actions button:focus-visible {
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    #gaius-error-actions button:disabled {
      cursor: wait;
      opacity: 0.65;
    }

    #gaius-error-details {
      position: fixed;
      top: calc(58% + 238px);
      left: 50%;
      z-index: 12;
      width: min(660px, calc(100vw - 48px));
      max-height: 20vh;
      box-sizing: border-box;
      margin: 0;
      padding: 10px 12px;
      overflow: auto;
      border: 1px solid var(--gaius-shell-line);
      background: #080d11;
      color: var(--gaius-shell-muted);
      font: 11px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      letter-spacing: 0;
      transform: translateX(-50%);
      white-space: pre-wrap;
    }

    #profile-gate {
      z-index: 14;
      padding: 32px 16px;
      background: var(--gaius-shell-bg);
      color: var(--gaius-shell-text);
    }

    #profile-form {
      width: min(440px, calc(100vw - 32px));
      box-sizing: border-box;
      padding: 30px 32px 26px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: var(--gaius-shell-panel);
      box-shadow: 8px 8px 0 #06090c;
    }

    #profile-title {
      margin: 0;
      color: var(--gaius-shell-text);
      font: 900 52px/0.9 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: left;
    }

    #profile-kicker {
      margin: 10px 0 28px;
      color: var(--gaius-shell-accent);
      font: 700 11px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #profile-subtitle {
      margin: -16px 0 24px;
      color: var(--gaius-shell-muted);
      font: 13px/1.45 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-form label {
      margin-bottom: 8px;
      color: var(--gaius-shell-text);
      font: 700 12px/1.2 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-name {
      height: 46px;
      border: 2px solid #5a6b76;
      border-radius: 0;
      background: #0a1014;
      color: var(--gaius-shell-text);
      font: 18px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-name:focus {
      border-color: var(--gaius-shell-accent);
      box-shadow: 0 0 0 2px rgba(155, 211, 106, 0.18);
    }

    #profile-submit {
      height: 46px;
      margin-top: 14px;
      border: 2px solid var(--gaius-shell-accent);
      border-radius: 0;
      background: var(--gaius-shell-accent);
      color: #0b1116;
      font: 800 14px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    #profile-submit:hover,
    #profile-submit:focus-visible {
      background: var(--gaius-shell-accent-strong);
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    #profile-error {
      min-height: 18px;
      margin: 9px 0 0;
      color: var(--gaius-shell-danger);
      font: 12px/1.4 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-legal {
      margin: 22px 0 0;
      color: var(--gaius-shell-muted);
      font: 11px/1.5 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: left;
    }

    #profile-switch {
      top: 16px;
      right: 16px;
      left: auto;
      z-index: 30;
      min-height: 36px;
      padding: 0 12px;
      border: 2px solid var(--gaius-shell-line);
      border-radius: 0;
      background: rgba(11, 17, 22, 0.96);
      color: var(--gaius-shell-text);
      font: 700 12px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
    }

    #profile-switch:hover,
    #profile-switch:focus-visible {
      border-color: var(--gaius-shell-accent);
      background: var(--gaius-shell-panel-strong);
      outline: 2px solid var(--gaius-shell-text);
      outline-offset: 2px;
    }

    @media (max-width: 600px) {
      #gaius-shell-header {
        min-height: 48px;
        padding: 0 16px;
      }

      #gaius-shell-version {
        display: none;
      }

      #gaius-shell-footer {
        left: 16px;
        right: 16px;
        bottom: 12px;
      }

      #gaius-shell-footer span:last-child {
        display: none;
      }

      #boot-brand {
        font-size: 46px;
      }

      #profile-form {
        padding: 24px 20px 22px;
      }

      #profile-title {
        font-size: 44px;
      }

      #gaius-error-actions {
        top: calc(58% + 160px);
      }

      #gaius-error-details {
        top: calc(58% + 208px);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      #gaius-shell-header,
      #gaius-shell-footer,
      #boot-brand,
      #boot-progress-bar {
        animation: none;
        transition: none;
      }
    }
'''

GAIUS_SHELL_SCRIPT = r'''  <script>
    (function installGaiusClientShellV2() {
      const root = document.documentElement;
      const bootScreen = document.getElementById("boot-screen");
      const profileGate = document.getElementById("profile-gate");
      const statusBox = document.getElementById("status");
      const shellMode = document.getElementById("gaius-shell-mode");
      const errorActions = document.getElementById("gaius-error-actions");
      const retryButton = document.getElementById("gaius-retry");
      const detailsButton = document.getElementById("gaius-error-toggle");
      const detailsBox = document.getElementById("gaius-error-details");
      if (!root || !statusBox) return;

      function bootDiagnostics() {
        const diagnostics = [];
        const bootError = window.__gaiusBootError;
        if (bootError && bootError.stack) diagnostics.push(String(bootError.stack));
        const extra = window.__gaiusBootErrorDetails;
        if (Array.isArray(extra)) {
          for (const line of extra) diagnostics.push(String(line));
        }
        return diagnostics.join("\n");
      }

      function renderShell() {
        const profileOpen = !!(profileGate && !profileGate.hidden);
        const bootOpen = !!(bootScreen && !bootScreen.hidden);
        const failed = statusBox.dataset.state === "error";
        const view = profileOpen ? "profile" : (failed ? "error" : (bootOpen ? "boot" : "canvas"));
        root.dataset.gaiusShellView = view;
        if (shellMode) {
          const sessionMode = String(window.__gaiusSessionMode || "").toLowerCase();
          shellMode.textContent = sessionMode === "online"
            ? "ONLINE SESSION"
            : (location.protocol === "file:" ? "PORTABLE HTML" : "BROWSER CLIENT");
        }
        if (errorActions) errorActions.hidden = !failed;
        if (detailsButton) {
          detailsButton.hidden = !failed;
          detailsButton.textContent = detailsBox && detailsBox.dataset.open === "1"
            ? "Hide diagnostics"
            : "Show diagnostics";
        }
        if (detailsBox) {
          detailsBox.textContent = bootDiagnostics() || "No additional diagnostics were captured.";
          detailsBox.hidden = !failed || detailsBox.dataset.open !== "1";
        }
      }

      function retryGaiusStartup() {
        if (retryButton) {
          retryButton.disabled = true;
          retryButton.textContent = "Restarting...";
        }
        const next = new URL(location.href);
        next.searchParams.set("retry", String(Date.now()));
        location.replace(next.href);
      }

      if (retryButton) retryButton.addEventListener("click", retryGaiusStartup);
      if (detailsButton && detailsBox) {
        detailsButton.addEventListener("click", () => {
          detailsBox.dataset.open = detailsBox.dataset.open === "1" ? "0" : "1";
          renderShell();
        });
      }

      const observer = new MutationObserver(renderShell);
      observer.observe(statusBox, {attributes: true, childList: true, subtree: true});
      if (bootScreen) observer.observe(bootScreen, {attributes: true});
      if (profileGate) observer.observe(profileGate, {attributes: true});
      window.__gaiusShell = {
        version: 2,
        refresh: renderShell,
        retry: retryGaiusStartup
      };
      renderShell();
    })();
  </script>
'''

def apply_gaius_client_shell(text: str) -> str:
    """Install the stable browser-client shell without changing game contracts."""
    if GAIUS_SHELL_MARKER in text:
        return text

    text = replace_required(
        text,
        "  </style>\n",
        GAIUS_SHELL_CSS + "  </style>\n",
        "Gaius Client shell v2 CSS",
    )

    shell_markup = '''  <div id="gaius-shell-header" data-gaius-shell="v2" aria-hidden="true">
    <div id="gaius-shell-brand"><strong>GAIUS</strong><span>CLIENT</span></div>
    <span id="gaius-shell-mode">BROWSER CLIENT</span>
    <span id="gaius-shell-version">VERSION 0.0.1</span>
  </div>
'''
    brand_pattern = re.compile(
        r'(?m)^  <div id="boot-brand"[^>]*>.*?</div>\n',
        flags=re.DOTALL,
    )
    text, brand_count = brand_pattern.subn(
        lambda match: match.group(0) + shell_markup,
        text,
        count=1,
    )
    if brand_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius shell header")

    profile_title = '''      <h1 id="profile-title">GAIUS</h1>
      <p id="profile-kicker">Browser client | offline and online play</p>
      <p id="profile-subtitle">Choose a player name to continue.</p>
'''
    title_pattern = re.compile(
        r'(?m)^      <h1 id="profile-title">.*?</h1>\n',
        flags=re.DOTALL,
    )
    text, title_count = title_pattern.subn(profile_title, text, count=1)
    if title_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius profile heading")

    status_pattern = re.compile(
        r'(?ms)^  <pre id="status"[^>]*>.*?</pre>\n',
    )
    error_markup = '''  <div id="gaius-error-actions" hidden>
    <button id="gaius-retry" type="button">Retry startup</button>
    <button id="gaius-error-toggle" type="button">Show diagnostics</button>
  </div>
  <pre id="gaius-error-details" hidden></pre>
  <div id="gaius-shell-footer" data-gaius-shell="v2" aria-hidden="true">
    <span><strong>Gaius Client</strong> | independent browser software</span>
    <span>HTML runtime | local storage enabled</span>
  </div>
'''
    text, status_count = status_pattern.subn(
        lambda match: match.group(0) + error_markup,
        text,
        count=1,
    )
    if status_count != 1:
        raise RuntimeError("index.html patch point was not found: Gaius error actions")

    text = text.replace(
        "Gaius is an independent project and is not affiliated with Mojang Studios or Microsoft.",
        "Gaius is an independent browser client. It is not affiliated with Mojang Studios or Microsoft.",
        1,
    )
    text = text.replace(
        'script.onerror = () => reject(new Error("无法加载 " + src));',
        'script.onerror = () => reject(new Error("Could not load " + src));',
        1,
    )
    text = replace_required(
        text,
        "</body>\n",
        GAIUS_SHELL_SCRIPT + "</body>\n",
        "Gaius Client shell v2 controller",
    )
    return text



def patch_index(
    index: Path,
    classes_js: Path,
    minecraft_version: str = "1.21.11",
    asset_index_id: str | None = None,
) -> bool:
    asset_index_id = asset_index_id or minecraft_version
    build_token = content_token(classes_js)
    vanilla_assets_token = content_token(index.parent / "vanilla-assets.pack.gz")
    singleplayer_token = content_token(
        index.parent / "singleplayer-server-worker.js",
        index.parent / "singleplayer-server.js",
    )
    text = index.read_text(encoding="utf-8")
    original = text

    vanilla_assets_loader = '''    const vanillaAssetsToken = "__VANILLA_ASSETS_TOKEN__";
    function hasGaiusVanillaAssetsMagic(bytes) {
      const magic = "GAIUSVP1";
      if (!(bytes instanceof Uint8Array) || bytes.length < 12) return false;
      for (let index = 0; index < magic.length; index++) {
        if (bytes[index] !== magic.charCodeAt(index)) return false;
      }
      return true;
    }

    async function gunzipGaiusVanillaAssets(bytes) {
      if (hasGaiusVanillaAssetsMagic(bytes)) return bytes;
      if (typeof DecompressionStream !== "function") {
        throw new Error("This browser cannot decompress the Gaius vanilla asset pack");
      }
      const stream = new Blob([bytes])
        .stream()
        .pipeThrough(new DecompressionStream("gzip"));
      const unpacked = new Uint8Array(await new Response(stream).arrayBuffer());
      if (!hasGaiusVanillaAssetsMagic(unpacked)) {
        throw new Error("The Gaius vanilla asset pack has an invalid header");
      }
      return unpacked;
    }

    async function decodeGaiusVanillaAssets(source) {
      const compressed = source instanceof Uint8Array
        ? source
        : new Uint8Array(await source.arrayBuffer());
      const bytes = await gunzipGaiusVanillaAssets(compressed);
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      const indexLength = view.getUint32(8, true);
      const indexStart = 12;
      const dataOffset = indexStart + indexLength;
      if (indexLength <= 0 || dataOffset > bytes.length) {
        throw new Error("The Gaius vanilla asset index is truncated");
      }
      let index;
      try {
        index = JSON.parse(new TextDecoder().decode(bytes.subarray(indexStart, dataOffset)));
      } catch (error) {
        throw new Error("The Gaius vanilla asset index is invalid: " + String(error));
      }
      if (!index || typeof index !== "object" || Array.isArray(index)) {
        throw new Error("The Gaius vanilla asset index is not an object");
      }
      const payloadLength = bytes.length - dataOffset;
      let resourceCount = 0;
      for (const name in index) {
        if (!Object.prototype.hasOwnProperty.call(index, name)) continue;
        const range = index[name];
        if (!Array.isArray(range) || range.length !== 2 ||
            !Number.isSafeInteger(range[0]) || !Number.isSafeInteger(range[1]) ||
            range[0] < 0 || range[1] < 0 || range[0] + range[1] > payloadLength) {
          throw new Error("The Gaius vanilla asset range is invalid: " + name);
        }
        resourceCount++;
      }
      if (resourceCount < 1000) {
        throw new Error("The Gaius vanilla asset pack is incomplete");
      }
      const root = {bytes, index, dataOffset, resourceCount};
      window.__gaiusVanillaAssets = root;
      bootTimings.vanillaAssetsDecoded = performance.now();
      return root;
    }

    async function loadGaiusVanillaAssets() {
      bootTimings.vanillaAssetsRequestStart = performance.now();
      const portableSource = window.__gaiusVanillaAssetsCompressedPromise;
      if (portableSource) {
        return decodeGaiusVanillaAssets(await portableSource);
      }
      const source = new URL(
        urlParams.get("vanillaAssets") || "vanilla-assets.pack.gz",
        location.href
      );
      const version = urlParams.get("fresh") === "1" || urlParams.get("cache") === "0"
        ? vanillaAssetsToken + "-fresh-" + Date.now()
        : vanillaAssetsToken;
      source.searchParams.set("v", version);
      const response = await fetch(source, {cache: "force-cache"});
      if (!response.ok) {
        throw new Error("Could not load the Gaius vanilla asset pack: HTTP " + response.status);
      }
      return decodeGaiusVanillaAssets(await response.blob());
    }

    window.__gaiusDecodeVanillaAssets = decodeGaiusVanillaAssets;
    window.__gaiusVanillaAssetsReady = window.__gaiusVanillaAssetsReady ||
      loadGaiusVanillaAssets();
    window.__gaiusVanillaAssetsReady.catch(() => {});
'''.replace("__VANILLA_ASSETS_TOKEN__", vanilla_assets_token)

    if "function decodeGaiusVanillaAssets(source)" not in text:
        text = replace_required(
            text,
            "    const urlParams = new URLSearchParams(location.search);\n",
            "    const urlParams = new URLSearchParams(location.search);\n"
            + vanilla_assets_loader,
            "vanilla asset pack loader",
        )
    else:
        text, token_count = re.subn(
            r'    const vanillaAssetsToken = "[^"]+";',
            f'    const vanillaAssetsToken = "{vanilla_assets_token}";',
            text,
            count=1,
        )
        if token_count != 1:
            raise RuntimeError("index.html patch point was not found: vanilla asset token")

    text = text.replace('<html lang="zh-CN">', '<html lang="en">', 1)
    text = re.sub(
        r'<title>Gaius (?:Minecraft|Client) [^<]+</title>',
        f'<title>Gaius Client {minecraft_version}</title>',
        text,
        count=1,
    )

    text = text.replace(
        '    const showPerfHud = urlParams.get("hud") !== "0";\n',
        '    const showPerfHud = urlParams.get("hud") === "1";\n',
    )
    if 'const showLauncherDetails = urlParams.get("debug") === "1" || showPerfHud;' not in text:
        text = replace_required(
            text,
            '    const showPerfHud = urlParams.get("hud") === "1";\n',
            '    const showPerfHud = urlParams.get("hud") === "1";\n'
            '    const showLauncherDetails = urlParams.get("debug") === "1" || showPerfHud;\n',
            "launcher detail visibility",
        )
    text = text.replace(
        '<pre id="status" data-state="running">',
        '<pre id="status" data-state="running" hidden>',
        1,
    )
    text = text.replace(
        "    const defaultMaxDpr = Math.min(1.5, rawDevicePixelRatio);\n",
        "    const defaultMaxDpr = Math.min(1.0, rawDevicePixelRatio);\n",
    )
    text = text.replace(
        "        if (nowMs - fps.worldEnteredAt < 60000) {\n",
        "        if (nowMs - fps.worldEnteredAt < 10000) {\n",
    )
    text = text.replace(
        "      if (fps.lastDprChangeAt && nowMs - fps.lastDprChangeAt < 10000) {\n",
        "      if (fps.lastDprChangeAt && nowMs - fps.lastDprChangeAt < 8000) {\n",
    )
    text = text.replace(
        "    setInterval(function gaiusFpsSample() {\n"
        "      const fps = window.__gaiusFps;\n"
        "      const measured = Number.isFinite(fps.gameFps) && fps.gameFps > 0\n"
        "        ? fps.gameFps\n"
        "        : fps.rafFps;\n"
        "      if (Number.isFinite(measured) && measured > 0) fps.fps = measured;\n"
        "      fps.lastSampleAt = performance.now();\n"
        "      maybeDegradeResolutionForFps();\n"
        "    }, 1000);\n",
        "    requestAnimationFrame(function gaiusFpsTick(now) {\n"
        "      const fps = window.__gaiusFps;\n"
        "      const inWorld = !!window.__gaiusMinecraftState?.level;\n"
        "      if (inWorld && !fps.rafMetricsWorldEnteredAt) {\n"
        "        fps.rafMetricsWorldEnteredAt = now;\n"
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n"
        "          fps.rafLongestFrameMs = Math.max(fps.rafLongestFrameMs || 0, frameMs);\n"
        "        }\n"
        "      }\n"
        "      fps.rafLastFrameAt = now;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n"
        "      if (elapsed >= 1000) {\n"
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n"
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n"
        "        }\n"
        "        fps.fps = fps.rafFps;\n"
        "        fps.frames = 0;\n"
        "        fps.lastSampleAt = now;\n"
        "        maybeDegradeResolutionForFps();\n"
        "      }\n"
        "      requestAnimationFrame(gaiusFpsTick);\n"
        "    });\n",
    )
    text = text.replace(
        "      const measured = Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps;\n",
        "      const measured = Number.isFinite(fps.rafFps) && fps.rafFps > 0 ? fps.rafFps : 0;\n",
    )
    text = text.replace(
        "      const shownFps = Number.isFinite(fps.gameFps) && fps.gameFps > 0 ? fps.gameFps : fps.rafFps;\n",
        "      const shownFps = Number.isFinite(fps.rafFps) && fps.rafFps > 0 ? fps.rafFps : 0;\n",
    )
    text = text.replace(
        "      const fps = window.__gaiusFps;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n",
        "      const fps = window.__gaiusFps;\n"
        "      const inWorld = !!window.__gaiusMinecraftState?.level;\n"
        "      if (inWorld && !fps.rafMetricsWorldEnteredAt) {\n"
        "        fps.rafMetricsWorldEnteredAt = now;\n"
        "        fps.rafFrameTimes = new Float32Array(4096);\n"
        "        fps.rafFrameWriteIndex = 0;\n"
        "        fps.rafFrameCount = 0;\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          let samples = fps.rafFrameTimes;\n"
        "          if (!(samples instanceof Float32Array) || samples.length !== 4096) {\n"
        "            samples = new Float32Array(4096);\n"
        "            fps.rafFrameTimes = samples;\n"
        "            fps.rafFrameWriteIndex = 0;\n"
        "            fps.rafFrameCount = 0;\n"
        "          }\n"
        "          const writeIndex = (Number(fps.rafFrameWriteIndex) || 0) % samples.length;\n"
        "          samples[writeIndex] = frameMs;\n"
        "          fps.rafFrameWriteIndex = (writeIndex + 1) % samples.length;\n"
        "          fps.rafFrameCount = Math.min(samples.length, (Number(fps.rafFrameCount) || 0) + 1);\n"
        "          fps.rafLongestFrameMs = Math.max(fps.rafLongestFrameMs || 0, frameMs);\n"
        "        }\n"
        "      }\n"
        "      fps.rafLastFrameAt = now;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n",
    )
    text = text.replace(
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        fps.fps = fps.rafFps;\n",
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        const samples = fps.rafFrameTimes;\n"
        "        const sampleCount = samples instanceof Float32Array\n"
        "          ? Math.min(samples.length, Number(fps.rafFrameCount) || 0)\n"
        "          : 0;\n"
        "        if (sampleCount > 0) {\n"
        "          const ordered = Array.from(samples.subarray(0, sampleCount))\n"
        "            .sort((left, right) => left - right);\n"
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n"
        "        }\n"
        "        fps.fps = fps.rafFps;\n",
    )
    text = migrate_raf_frame_samples(text)
    text = text.replace(
        "      const lowTarget = Math.max(45, Math.min(targetFps * 0.55, targetFps - 50));\n"
        "      const recoveredTarget = Math.max(90, Math.min(targetFps, lowTarget + 30));\n",
        "      const lowTarget = Math.max(45, Math.min(targetFps * 0.70, targetFps - 20));\n"
        "      const recoveredTarget = Math.max(lowTarget + 10, Math.min(targetFps, lowTarget + 20));\n",
    )
    text = text.replace(
        "      if (fps.lowSamples < 12 || window.__gaiusMaxDpr <= minDpr) return;\n"
        "      window.__gaiusMaxDpr = Math.max(\n"
        "        minDpr,\n"
        "        1.0\n"
        "      );\n",
        "      if (fps.lowSamples < 3 || window.__gaiusMaxDpr <= minDpr) return;\n"
        "      const nextMaxDpr = Math.round((window.__gaiusMaxDpr - 0.25) * 4) / 4;\n"
        "      window.__gaiusMaxDpr = Math.max(minDpr, nextMaxDpr);\n",
    )
    text = text.replace(
        '      const wasmUrl = new URL(urlParams.get("hotpathWasm") || "gaius-hotpath.wasm", location.href);\n',
        "",
    )
    if "window.__gaiusHotpathWasmUrl || new URL" not in text:
        text = replace_required(
            text,
            "      state.readyPromise = (async () => {\n"
            "        const response = await fetch(wasmUrl, { cache: \"force-cache\" });\n",
            "      state.readyPromise = (async () => {\n"
            "        await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "        const wasmUrl = window.__gaiusHotpathWasmUrl || new URL(\n"
            "          urlParams.get(\"hotpathWasm\") || \"gaius-hotpath.wasm\",\n"
            "          location.href\n"
            "        );\n"
            "        const response = await fetch(wasmUrl, { cache: \"force-cache\" });\n",
            "portable Wasm asset URL",
        )

    if 'id="boot-screen"' not in text:
        text = replace_required(
            text,
            "  </style>\n",
            "    /* Gaius boot screen */\n"
            "    #boot-screen {\n"
            "      position: fixed;\n"
            "      inset: 0;\n"
            "      z-index: 10;\n"
            "      background: #101511;\n"
            "      pointer-events: none;\n"
            "    }\n"
            "\n"
            "    #boot-brand {\n"
            "      position: fixed;\n"
            "      left: 50%;\n"
            "      top: 38%;\n"
            "      z-index: 11;\n"
            "      transform: translate(-50%, -50%);\n"
            "      color: #fff;\n"
            "      text-align: center;\n"
            "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n"
            "      letter-spacing: 0;\n"
            "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.28);\n"
            "      pointer-events: none;\n"
            "      animation: gaius-logo-enter 420ms ease-out both;\n"
            "    }\n"
            "\n"
            "    #boot-brand span {\n"
            "      display: block;\n"
            "      margin-top: 16px;\n"
            "      font-size: 0.28em;\n"
            "      line-height: 1;\n"
            "      letter-spacing: 0;\n"
            "    }\n"
            "\n"
            "    #boot-progress {\n"
            "      left: 50%;\n"
            "      right: auto;\n"
            "      top: 58%;\n"
            "      z-index: 11;\n"
            "      width: min(460px, calc(100vw - 48px));\n"
            "      height: 10px;\n"
            "      box-sizing: border-box;\n"
            "      padding: 2px;\n"
            "      transform: translateX(-50%);\n"
            "      border: 2px solid #fff;\n"
            "      border-radius: 0;\n"
            "      background: transparent;\n"
            "    }\n"
            "\n"
            "    #boot-progress-bar {\n"
            "      min-width: 2px;\n"
            "      background: #54d68b;\n"
            "      transition: width 160ms linear;\n"
            "    }\n"
            "\n"
            "    #boot-progress-text {\n"
            "      left: 24px;\n"
            "      right: 24px;\n"
            "      top: calc(58% + 24px);\n"
            "      z-index: 11;\n"
            "      color: #fff;\n"
            "      text-align: center;\n"
            "      font-size: 12px;\n"
            "      text-shadow: none;\n"
            "    }\n"
            "\n"
            "    #status {\n"
            "      left: 50%;\n"
            "      right: auto;\n"
            "      top: calc(58% + 56px);\n"
            "      z-index: 11;\n"
            "      width: min(640px, calc(100vw - 48px));\n"
            "      max-height: 28vh;\n"
            "      transform: translateX(-50%);\n"
            "      border: 0;\n"
            "      border-radius: 0;\n"
            "      background: transparent;\n"
            "      color: rgba(255, 255, 255, 0.9);\n"
            "      text-align: center;\n"
            "      font: 12px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;\n"
            "    }\n"
            "\n"
            "    #status[data-state=\"error\"] {\n"
            "      padding: 14px 16px;\n"
            "      border: 2px solid rgba(255, 255, 255, 0.82);\n"
            "      background: rgba(0, 0, 0, 0.76);\n"
            "      color: #fff;\n"
            "      text-align: left;\n"
            "    }\n"
            "\n"
            "    @media (max-width: 600px) {\n"
            "      #boot-brand { font-size: 44px; }\n"
            "    }\n"
            "\n"
            "    @keyframes gaius-logo-enter {\n"
            "      from { opacity: 0; transform: translate(-50%, -46%) scale(0.96); }\n"
            "      to { opacity: 1; transform: translate(-50%, -50%) scale(1); }\n"
            "    }\n"
            "  </style>\n",
            "Gaius boot screen CSS",
        )
        text = replace_required(
            text,
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n',
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n'
            '  <div id="boot-screen" aria-hidden="true"></div>\n'
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n',
            "Gaius boot screen markup",
        )
        text = replace_required(
            text,
            '    const statusBox = document.getElementById("status");\n',
            '    const statusBox = document.getElementById("status");\n'
            '    const bootScreen = document.getElementById("boot-screen");\n'
            '    const bootBrand = document.getElementById("boot-brand");\n',
            "Gaius boot screen elements",
        )
        text = replace_required(
            text,
            "      statusBox.hidden = true;\n",
            "      statusBox.hidden = true;\n"
            "      if (bootScreen) bootScreen.hidden = true;\n"
            "      if (bootBrand) bootBrand.hidden = true;\n",
            "Gaius boot screen cleanup",
        )

    text = text.replace("/* Minecraft-style boot screen */", "/* Gaius boot screen */", 1)
    text = text.replace("      background: #ef323d;\n", "      background: #101511;\n", 1)
    text = text.replace(
        "      font: 900 clamp(42px, 8vw, 92px)/0.72 Arial, Helvetica, sans-serif;\n",
        "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n",
        1,
    )
    text = text.replace(
        "      font: 900 72px/0.72 Arial, Helvetica, sans-serif;\n",
        "      font: 900 72px/0.82 Arial, Helvetica, sans-serif;\n",
        1,
    )
    text = text.replace(
        "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.16);\n",
        "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.28);\n"
        "      animation: gaius-logo-enter 420ms ease-out both;\n",
        1,
    )
    text = text.replace(
        '  <div id="boot-brand" aria-hidden="true">MOJANG<span>STUDIOS</span></div>',
        '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>',
        1,
    )
    text = text.replace(
        "      background: linear-gradient(90deg, #38bdf8, #22c55e);\n",
        "      background: #54d68b;\n",
        1,
    )
    text = text.replace("      background: #fff;\n", "      background: #54d68b;\n", 1)
    if "@keyframes gaius-logo-enter" not in text:
        text = replace_required(
            text,
            "  </style>\n",
            "    @keyframes gaius-logo-enter {\n"
            "      from { opacity: 0; transform: translate(-50%, -46%) scale(0.96); }\n"
            "      to { opacity: 1; transform: translate(-50%, -50%) scale(1); }\n"
            "    }\n"
            "\n"
            "    @media (max-width: 600px) {\n"
            "      #boot-brand { font-size: 44px; }\n"
            "    }\n"
            "  </style>\n",
            "Gaius boot animation",
        )

    # Minecraft's own early loading screens include the Mojang Studios splash.
    # Keep the Gaius shell above those transient screens and only reveal the
    # canvas once a real menu or world is ready.
    boot_screen_pattern = re.compile(
        r'(?P<indent>[ \t]+)if \(state && state\.screen\) \{\n'
        r'(?P=indent)  const screen = String\(state\.screen\)\.split\("\."\)\.pop\(\);\n'
        r'(?P=indent)  setBootProgress\(100, "Minecraft screen ready: " \+ screen\);\n'
        r'(?P=indent)  hideBootOverlay\(\);\n'
        r'(?P=indent)  return;\n'
        r'(?P=indent)\}'
    )
    boot_screen_replacement = (
        r'\g<indent>if (state && (state.overlay || state.screen)) {\n'
        r'\g<indent>  const overlay = state.overlay ? String(state.overlay).split(".").pop() : "";\n'
        r'\g<indent>  const screen = state.screen ? String(state.screen).split(".").pop() : "";\n'
        r'\g<indent>  const keepGaiusBootVisible = [\n'
        r'\g<indent>    "GenericMessageScreen",\n'
        r'\g<indent>    "ProgressScreen",\n'
        r'\g<indent>    "LevelLoadingScreen",\n'
        r'\g<indent>    "ReceivingLevelScreen"\n'
        r'\g<indent>  ].includes(screen) || overlay === "LoadingOverlay";\n'
        r'\g<indent>  if (keepGaiusBootVisible) {\n'
        r'\g<indent>    setBootProgress(Math.max(bootProgressValue, 92), "Loading game resources...");\n'
        r'\g<indent>    return;\n'
        r'\g<indent>  }\n'
        r'\g<indent>  if (screen) {\n'
        r'\g<indent>    setBootProgress(100, "Client screen ready: " + screen);\n'
        r'\g<indent>    hideBootOverlay();\n'
        r'\g<indent>    return;\n'
        r'\g<indent>  }\n'
        r'\g<indent>}'
    )
    if "const keepGaiusBootVisible = [" not in text:
        text, boot_screen_count = boot_screen_pattern.subn(
            boot_screen_replacement,
            text,
            count=1,
        )
        if boot_screen_count != 1:
            raise RuntimeError("index.html patch point was not found: Gaius boot screen lifetime")

    if "function showBootOverlay(label)" not in text:
        text = replace_required(
            text,
            "    function hideBootOverlay() {\n",
            "    function showBootOverlay(label) {\n"
            "      const restarting = !!(bootScreen && bootScreen.hidden);\n"
            "      if (restarting) {\n"
            "        bootProgressValue = 8;\n"
            "        bootLastProgressAt = performance.now();\n"
            "        bootWarnedStall = false;\n"
            "        if (bootProgressBar) bootProgressBar.style.width = \"8%\";\n"
            "      }\n"
            "      if (bootScreen) bootScreen.hidden = false;\n"
            "      if (bootBrand) bootBrand.hidden = false;\n"
            "      if (profileGate) profileGate.hidden = true;\n"
            "      if (bootProgress) bootProgress.hidden = false;\n"
            "      if (bootProgressText) bootProgressText.hidden = false;\n"
            "      statusBox.hidden = !showLauncherDetails;\n"
            "      statusBox.dataset.state = \"running\";\n"
            "      statusBox.textContent = label || \"Loading...\";\n"
            "    }\n"
            "\n"
            "    window.__gaiusShowBootOverlay = showBootOverlay;\n"
            "\n"
            "    function hideBootOverlay() {\n",
            "reusable Gaius loading overlay",
        )

    text = text.replace(
        "      statusBox.hidden = false;\n"
        "      statusBox.dataset.state = \"running\";\n"
        "      statusBox.textContent = label || \"Loading...\";\n",
        "      statusBox.hidden = !showLauncherDetails;\n"
        "      statusBox.dataset.state = \"running\";\n"
        "      statusBox.textContent = label || \"Loading...\";\n",
        1,
    )
    text = text.replace(
        "      statusBox.hidden = false;\n"
        "      statusBox.dataset.state = state;\n"
        "      statusBox.textContent = message;\n",
        "      statusBox.hidden = state !== \"error\" && !showLauncherDetails;\n"
        "      statusBox.dataset.state = state;\n"
        "      statusBox.textContent = message;\n",
        1,
    )
    text = text.replace(
        '      if (statusBox.dataset.state !== "running" || statusBox.hidden) return;\n',
        '      if (statusBox.dataset.state !== "running") return;\n',
        1,
    )

    if "showBootOverlay(loadingLabel);" not in text:
        loading_screen_pattern = re.compile(
            r'(?P<indent>[ \t]+)if \(keepGaiusBootVisible\) \{\n'
            r'(?P=indent)  setBootProgress\(Math\.max\(bootProgressValue, 92\), '
            r'"Loading game resources\.\.\."\);\n'
        )
        loading_screen_replacement = (
            r'\g<indent>if (keepGaiusBootVisible) {\n'
            r'\g<indent>  const loadingLabel = screen === "ReceivingLevelScreen"\n'
            r'\g<indent>    ? "Connecting to world..."\n'
            r'\g<indent>    : (screen === "LevelLoadingScreen"\n'
            r'\g<indent>      ? "Loading world..."\n'
            r'\g<indent>      : "Loading game resources...");\n'
            r'\g<indent>  showBootOverlay(loadingLabel);\n'
            r'\g<indent>  setBootProgress(Math.max(bootProgressValue, 35), loadingLabel);\n'
        )
        text, loading_screen_count = loading_screen_pattern.subn(
            loading_screen_replacement,
            text,
            count=1,
        )
        if loading_screen_count != 1:
            raise RuntimeError(
                "index.html patch point was not found: reopen Gaius loading overlay"
            )

    if 'id="profile-gate"' not in text:
        profile_css = '''
    #profile-gate {
      position: fixed;
      inset: 0;
      z-index: 14;
      display: grid;
      place-items: center;
      box-sizing: border-box;
      padding: 32px 24px;
      background: #101511;
      color: #f4f7f5;
    }

    #profile-gate[hidden] {
      display: none;
    }

    #profile-form {
      width: min(380px, 100%);
      margin: 0;
    }

    #profile-title {
      margin: 0 0 34px;
      font: 900 54px/1 Arial, Helvetica, sans-serif;
      letter-spacing: 0;
      text-align: center;
    }

    #profile-form label {
      display: block;
      margin-bottom: 8px;
      color: #d6ddd8;
      font: 600 13px/1.2 Arial, Helvetica, sans-serif;
    }

    #profile-name {
      width: 100%;
      height: 46px;
      box-sizing: border-box;
      padding: 0 12px;
      border: 2px solid #66756c;
      border-radius: 4px;
      outline: none;
      background: #171d19;
      color: #fff;
      font: 18px/1 Arial, Helvetica, sans-serif;
    }

    #profile-name:focus {
      border-color: #54d68b;
    }

    #profile-submit {
      width: 100%;
      height: 46px;
      margin-top: 14px;
      border: 0;
      border-radius: 4px;
      background: #54d68b;
      color: #08100b;
      font: 700 15px/1 Arial, Helvetica, sans-serif;
      cursor: pointer;
    }

    #profile-submit:hover,
    #profile-submit:focus-visible {
      background: #72e7a2;
      outline: 2px solid #fff;
      outline-offset: 2px;
    }

    #profile-error {
      min-height: 18px;
      margin: 8px 0 0;
      color: #ff9b9b;
      font: 12px/1.4 Arial, Helvetica, sans-serif;
    }

    #profile-legal {
      margin: 24px 0 0;
      color: #9eaaa2;
      font: 11px/1.45 Arial, Helvetica, sans-serif;
      text-align: center;
    }
'''
        text = replace_required(
            text,
            "  </style>\n",
            profile_css + "  </style>\n",
            "player-name gate CSS",
        )
        text = replace_required(
            text,
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n',
            '  <div id="boot-brand" aria-hidden="true">GAIUS<span>CLIENT</span></div>\n'
            '  <div id="profile-gate" hidden>\n'
            '    <form id="profile-form" novalidate>\n'
            '      <h1 id="profile-title">GAIUS</h1>\n'
            '      <label for="profile-name">Player name</label>\n'
            '      <input id="profile-name" name="username" type="text" maxlength="16" '
            'pattern="[A-Za-z0-9_]{1,16}" autocomplete="username" spellcheck="false">\n'
            '      <button id="profile-submit" type="submit">Play</button>\n'
            '      <p id="profile-error" role="alert"></p>\n'
            '      <p id="profile-legal">Gaius is an independent project and is not affiliated '
            'with Mojang Studios or Microsoft.</p>\n'
            '    </form>\n'
            '  </div>\n',
            "player-name gate markup",
        )

    if 'const profileGate = document.getElementById("profile-gate");' not in text:
        text = replace_required(
            text,
            '    const bootBrand = document.getElementById("boot-brand");\n',
            '    const bootBrand = document.getElementById("boot-brand");\n'
            '    const profileGate = document.getElementById("profile-gate");\n'
            '    const profileForm = document.getElementById("profile-form");\n'
            '    const profileName = document.getElementById("profile-name");\n'
            '    const profileError = document.getElementById("profile-error");\n',
            "player-name gate elements",
        )

    if 'id="profile-switch"' not in text:
        profile_switch_css = '''
    #profile-switch {
      position: fixed;
      left: 16px;
      top: 16px;
      z-index: 9;
      min-height: 36px;
      box-sizing: border-box;
      padding: 0 14px;
      border: 1px solid #7b8980;
      border-radius: 4px;
      background: rgba(16, 21, 17, 0.94);
      color: #f4f7f5;
      font: 600 13px/1 Arial, Helvetica, sans-serif;
      cursor: pointer;
    }

    #profile-switch[hidden] {
      display: none;
    }

    #profile-switch:hover,
    #profile-switch:focus-visible {
      border-color: #54d68b;
      background: #1b241e;
      outline: 2px solid #fff;
      outline-offset: 2px;
    }

    #profile-switch:disabled {
      cursor: wait;
      opacity: 0.65;
    }
'''
        text = replace_required(
            text,
            "  </style>\n",
            profile_switch_css + "  </style>\n",
            "player-name switch CSS",
        )
        text = replace_required(
            text,
            '  <pre id="status" data-state="running" hidden>',
            '  <button id="profile-switch" type="button" hidden>'
            'Change player name</button>\n'
            '  <pre id="status" data-state="running" hidden>',
            "player-name switch button",
        )

    if 'const profileSwitch = document.getElementById("profile-switch");' not in text:
        text = replace_required(
            text,
            '    const profileError = document.getElementById("profile-error");\n',
            '    const profileError = document.getElementById("profile-error");\n'
            '    const profileSwitch = document.getElementById("profile-switch");\n',
            "player-name switch element",
        )
    if "if (profileGate) profileGate.hidden = true;" not in text:
        text = replace_required(
            text,
            "      if (bootBrand) bootBrand.hidden = true;\n",
            "      if (bootBrand) bootBrand.hidden = true;\n"
            "      if (profileGate) profileGate.hidden = true;\n",
            "player-name gate cleanup",
        )

    # Existing options are user data. Older launchers deleted options.txt during
    # IndexedDB preload, which made every video-setting change look unsaved.
    text = text.replace(
        '          installBrowserPerformanceOptions("indexeddb browser performance profile");\n',
        "",
    )
    text = text.replace(
        '          installBrowserPerformanceOptions("localStorage browser performance profile");\n',
        "",
    )
    text = re.sub(
        r'\n      function installBrowserPerformanceOptions\(reason\) \{.*?\n      \}\n',
        "\n",
        text,
        count=1,
        flags=re.DOTALL,
    )

    if '<link rel="icon" href="data:,">' not in text:
        text = replace_required(
            text,
            '  <meta name="viewport" content="width=device-width, initial-scale=1">\n',
            '  <meta name="viewport" content="width=device-width, initial-scale=1">\n'
            '  <link rel="icon" href="data:,">\n',
            "favicon",
        )

    if "__gaiusBootTimings" not in text:
        text = replace_required(
            text,
            '    const perfHud = document.getElementById("perf-hud");\n',
            '    const perfHud = document.getElementById("perf-hud");\n'
            '    const bootTimings = window.__gaiusBootTimings = {\n'
            '      pageStart: performance.now()\n'
            '    };\n',
            "boot timings declaration",
        )

    if "bootTimings.classesLoaded" not in text:
        text = replace_required(
            text,
            "        script.onload = resolve;\n",
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n",
            "script load timing",
        )

    if "function waitForPaint()" not in text:
        text = replace_required(
            text,
            "    function loadScript(src) {\n"
            "      return new Promise((resolve, reject) => {\n"
            "        const script = document.createElement(\"script\");\n"
            "        script.src = src;\n"
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n"
            "        script.onerror = () => reject(new Error(\"无法加载 \" + src));\n"
            "        document.body.appendChild(script);\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "    function loadScript(src) {\n"
            "      return new Promise((resolve, reject) => {\n"
            "        const script = document.createElement(\"script\");\n"
            "        script.src = src;\n"
            "        script.onload = () => {\n"
            "          bootTimings.classesLoaded = performance.now();\n"
            "          resolve();\n"
            "        };\n"
            "        script.onerror = () => reject(new Error(\"无法加载 \" + src));\n"
            "        document.body.appendChild(script);\n"
            "      });\n"
            "    }\n"
            "\n"
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "paint yield helper",
        )

    if "async function acquireGaiusRuntimeLease()" not in text:
        text = replace_required(
            text,
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "    function waitForPaint() {\n"
            "      return new Promise(resolve => {\n"
            "        requestAnimationFrame(() => setTimeout(resolve, 0));\n"
            "      });\n"
            "    }\n"
            "\n"
            "    async function acquireGaiusRuntimeLease() {\n"
            "      if (window.__gaiusRuntimeLeaseHeld || urlParams.get(\"allowMultipleTabs\") === \"1\") {\n"
            "        return true;\n"
            "      }\n"
            "      const lockName = \"gaius-client-runtime-v1\";\n"
            "      if (navigator.locks && typeof navigator.locks.request === \"function\") {\n"
            "        const acquired = await new Promise(resolve => {\n"
            "          let settled = false;\n"
            "          const finish = value => {\n"
            "            if (settled) return;\n"
            "            settled = true;\n"
            "            resolve(value);\n"
            "          };\n"
            "          try {\n"
            "            navigator.locks.request(lockName, {mode: \"exclusive\", ifAvailable: true}, lock => {\n"
            "              if (!lock) {\n"
            "                finish(false);\n"
            "                return;\n"
            "              }\n"
            "              window.__gaiusRuntimeLeaseHeld = true;\n"
            "              finish(true);\n"
            "              return new Promise(release => {\n"
            "                window.__gaiusReleaseRuntimeLease = release;\n"
            "              });\n"
            "            }).catch(error => {\n"
            "              console.warn(\"Web Locks runtime lease is unavailable\", error);\n"
            "              finish(null);\n"
            "            });\n"
            "          } catch (error) {\n"
            "            console.warn(\"Web Locks runtime lease failed\", error);\n"
            "            finish(null);\n"
            "          }\n"
            "        });\n"
            "        if (acquired !== null) return acquired;\n"
            "      }\n"
            "\n"
            "      const leaseKey = \"gaius.runtimeLease.v1\";\n"
            "      const leaseId = (crypto.randomUUID ? crypto.randomUUID() :\n"
            "        Date.now().toString(36) + Math.random().toString(36).slice(2));\n"
            "      const readLease = () => {\n"
            "        try {\n"
            "          return JSON.parse(localStorage.getItem(leaseKey) || \"null\");\n"
            "        } catch (error) {\n"
            "          return null;\n"
            "        }\n"
            "      };\n"
            "      const writeLease = () => {\n"
            "        localStorage.setItem(leaseKey, JSON.stringify({id: leaseId, at: Date.now()}));\n"
            "      };\n"
            "      try {\n"
            "        const current = readLease();\n"
            "        if (current && current.id !== leaseId && Date.now() - Number(current.at || 0) < 10000) {\n"
            "          return false;\n"
            "        }\n"
            "        writeLease();\n"
            "        await new Promise(resolve => setTimeout(resolve, 80));\n"
            "        const claimed = readLease();\n"
            "        if (!claimed || claimed.id !== leaseId) return false;\n"
            "        const heartbeat = setInterval(writeLease, 3000);\n"
            "        const release = () => {\n"
            "          clearInterval(heartbeat);\n"
            "          const owned = readLease();\n"
            "          if (owned && owned.id === leaseId) localStorage.removeItem(leaseKey);\n"
            "        };\n"
            "        addEventListener(\"pagehide\", release, {once: true});\n"
            "        addEventListener(\"beforeunload\", release, {once: true});\n"
            "        window.__gaiusReleaseRuntimeLease = release;\n"
            "        window.__gaiusRuntimeLeaseHeld = true;\n"
            "        return true;\n"
            "      } catch (error) {\n"
            "        console.warn(\"Cross-tab runtime lease is unavailable\", error);\n"
            "        return true;\n"
            "      }\n"
            "    }\n"
            "\n"
            "    (async () => {\n",
            "single active Gaius runtime lease",
        )

    stable_build_block = (
        f'      const fallbackBuildToken = "{build_token}";\n'
        '      const requestedBuildToken = new URLSearchParams(location.search).get("build");\n'
        '      let buildToken = requestedBuildToken && requestedBuildToken.trim()\n'
        '        ? requestedBuildToken.trim()\n'
        '        : fallbackBuildToken;\n'
        '      if (urlParams.get("fresh") === "1" || urlParams.get("cache") === "0") {\n'
        '        buildToken += "-fresh-" + Date.now();\n'
        '      }\n'
        '      bootTimings.buildToken = buildToken;\n'
        '      bootTimings.classesStart = performance.now();'
    )
    old_fresh_block = (
        '      const requestedBuildToken = new URLSearchParams(location.search).get("build") || "20260629090000";\n'
        '      const buildToken = requestedBuildToken + "-fresh-" + Date.now();'
    )
    if old_fresh_block in text:
        text = text.replace(old_fresh_block, stable_build_block, 1)
    else:
        text, count = re.subn(
            r'      const fallbackBuildToken = "[^"]+";\n'
            r'      const requestedBuildToken = new URLSearchParams\(location\.search\)\.get\("build"\);\n'
            r'      let buildToken = requestedBuildToken && requestedBuildToken\.trim\(\)\n'
            r'        \? requestedBuildToken\.trim\(\)\n'
            r'        : fallbackBuildToken;\n'
            r'      if \(urlParams\.get\("fresh"\) === "1" \|\| urlParams\.get\("cache"\) === "0"\) \{\n'
            r'        buildToken \+= "-fresh-" \+ Date\.now\(\);\n'
            r'      \}\n'
            r'      bootTimings\.buildToken = buildToken;\n'
            r'      bootTimings\.classesStart = performance\.now\(\);',
            stable_build_block,
            text,
            count=1,
        )
        if count == 0 and all(
            marker in text
            for marker in (
                '      const requestedBuildToken = new URLSearchParams(location.search).get("build");',
                "      bootTimings.buildToken = buildToken;",
                "      bootTimings.classesStart = performance.now();",
            )
        ):
            text, count = re.subn(
                r'      const fallbackBuildToken = "[^"]+";',
                f'      const fallbackBuildToken = "{build_token}";',
                text,
                count=1,
            )
        if count == 0:
            raise RuntimeError("index.html patch point was not found: stable build token")

    singleplayer_build_block = (
        f'      const singleplayerBuildToken = "{singleplayer_token}" +\n'
        '        (urlParams.get("fresh") === "1" || urlParams.get("cache") === "0"\n'
        '          ? "-fresh-" + Date.now()\n'
        '          : "");\n'
        '      window.__gaiusSingleplayerWorkerUrl = new URL(\n'
        '        "singleplayer-server-worker.js?v=" + encodeURIComponent(singleplayerBuildToken),\n'
        '        location.href\n'
        '      ).href;\n'
        '      window.__gaiusSingleplayerServerUrl = new URL(\n'
        '        "singleplayer-server.js?v=" + encodeURIComponent(singleplayerBuildToken),\n'
        '        location.href\n'
        '      ).href;\n'
        '      window.__gaiusSingleplayerServerGzipUrl = new URL(\n'
        '        "singleplayer-server.js.gz?v=" + encodeURIComponent(singleplayerBuildToken),\n'
        '        location.href\n'
        '      ).href;\n'
    )
    text, singleplayer_count = re.subn(
        r'      const singleplayerBuildToken = "[^"]+" \+\n'
        r'        \(urlParams\.get\("fresh"\) === "1" \|\| urlParams\.get\("cache"\) === "0"\n'
        r'          \? "-fresh-" \+ Date\.now\(\)\n'
        r'          : ""\);\n'
        r'      window\.__gaiusSingleplayerWorkerUrl = new URL\(\n'
        r'        "singleplayer-server-worker\.js\?v=" \+ encodeURIComponent\(singleplayerBuildToken\),\n'
        r'        location\.href\n'
        r'      \)\.href;\n'
        r'      window\.__gaiusSingleplayerServerUrl = new URL\(\n'
        r'        "singleplayer-server\.js\?v=" \+ encodeURIComponent\(singleplayerBuildToken\),\n'
        r'        location\.href\n'
        r'      \)\.href;\n'
        r'(?:      window\.__gaiusSingleplayerServerGzipUrl = new URL\(\n'
        r'        "singleplayer-server\.js\.gz\?v=" \+ encodeURIComponent\(singleplayerBuildToken\),\n'
        r'        location\.href\n'
        r'      \)\.href;\n)?',
        singleplayer_build_block,
        text,
        count=1,
    )
    if singleplayer_count == 0:
        text = replace_required(
            text,
            '      bootTimings.buildToken = buildToken;\n',
            '      bootTimings.buildToken = buildToken;\n' + singleplayer_build_block,
            "singleplayer content build token",
        )

    if "bootTimings.fsReady" not in text:
        text = replace_required(
            text,
            "      await window.__gaiusFsReady;\n",
            "      await window.__gaiusFsReady;\n"
            "      bootTimings.fsReady = performance.now();\n",
            "fs ready timing",
        )

    # Do not hydrate region files into the title-screen filesystem. World
    # metadata remains available for the world picker; the selected world's
    # complete data is loaded by the dedicated integrated-server Worker.
    if "function isClientBootstrapPath(path)" not in text:
        text = replace_required(
            text,
            "      function openDatabase() {\n",
            "      function isClientBootstrapPath(path) {\n"
            "        path = normalize(path);\n"
            "        const savesRoot = \"/gaius/saves/\";\n"
            "        if (!path.startsWith(savesRoot)) return true;\n"
            "        const worldSeparator = path.indexOf(\"/\", savesRoot.length);\n"
            "        if (worldSeparator < 0 || worldSeparator + 1 >= path.length) return false;\n"
            "        const relative = path.slice(worldSeparator + 1);\n"
            "        return relative === \"level.dat\" || relative === \"level.dat_old\" ||\n"
            "          relative === \"icon.png\";\n"
            "      }\n\n"
            "      function openDatabase() {\n",
            "client IndexedDB bootstrap filter",
        )
        text = replace_required(
            text,
            '            if (value && typeof value.path === "string" && typeof value.value === "string") {\n'
            '              files[normalize(value.path)] = value.value;\n'
            "            }\n",
            '            if (value && typeof value.path === "string" && typeof value.value === "string") {\n'
            '              const path = normalize(value.path);\n'
            '              if (isClientBootstrapPath(path)) files[path] = value.value;\n'
            "            }\n",
            "client IndexedDB read filter",
        )
        text = replace_required(
            text,
            "              files[path] = value;\n"
            "              migrated++;\n",
            "              if (isClientBootstrapPath(path)) files[path] = value;\n"
            "              migrated++;\n",
            "client IndexedDB migration filter",
        )
        text = replace_required(
            text,
            '              if (typeof value === "string") {\n'
            '                files[normalize(key.substring(prefix.length))] = value;\n'
            "                restored++;\n"
            "              }\n",
            '              if (typeof value === "string") {\n'
            '                const path = normalize(key.substring(prefix.length));\n'
            '                if (isClientBootstrapPath(path)) files[path] = value;\n'
            "                restored++;\n"
            "              }\n",
            "client localStorage fallback filter",
        )

    if "function copyPersistentBytes(value)" not in text:
        text = replace_required(
            text,
            "      function openDatabase() {\n",
            "      function copyPersistentBytes(value) {\n"
            "        if (value instanceof Uint8Array) return value.slice();\n"
            "        if (value instanceof ArrayBuffer) return new Uint8Array(value.slice(0));\n"
            "        if (ArrayBuffer.isView(value)) {\n"
            "          return new Uint8Array(value.buffer, value.byteOffset, value.byteLength).slice();\n"
            "        }\n"
            "        return null;\n"
            "      }\n\n"
            "      function isDownloadedPackBinaryPath(path) {\n"
            "        path = normalize(path);\n"
            "        return path.startsWith(\"/gaius/downloads/\") && !path.endsWith(\"/log.json\");\n"
            "      }\n\n"
            "      function persistentValueByteLength(value) {\n"
            "        if (typeof value === \"string\") {\n"
            "          const padding = value.endsWith(\"==\") ? 2 : (value.endsWith(\"=\") ? 1 : 0);\n"
            "          return Math.max(0, Math.floor(value.length * 3 / 4) - padding);\n"
            "        }\n"
            "        if (value instanceof ArrayBuffer) return value.byteLength;\n"
            "        if (ArrayBuffer.isView(value)) return value.byteLength;\n"
            "        return 0;\n"
            "      }\n\n"
            "      function openDatabase() {\n",
            "binary IndexedDB persistence helpers",
        )

    text = replace_required(
        text,
        '''      function openDatabase() {
        return new Promise((resolve, reject) => {
          if (!("indexedDB" in window)) {
            reject(new Error("IndexedDB is not available"));
            return;
          }
          const request = indexedDB.open(dbName, 1);
          request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains(storeName)) {
              database.createObjectStore(storeName, { keyPath: "path" });
            }
          };
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
          request.onblocked = () => reject(new Error("IndexedDB open blocked"));
        });
      }
''',
        '''      function openDatabase() {
        return new Promise((resolve, reject) => {
          if (!("indexedDB" in window)) {
            reject(new Error("IndexedDB is not available"));
            return;
          }
          let settled = false;
          const timeout = setTimeout(() => {
            if (settled) return;
            settled = true;
            reject(new Error("IndexedDB open timed out"));
          }, 8000);
          const fail = error => {
            if (settled) return;
            settled = true;
            clearTimeout(timeout);
            reject(error);
          };
          const request = indexedDB.open(dbName, 1);
          request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains(storeName)) {
              database.createObjectStore(storeName, { keyPath: "path" });
            }
          };
          request.onsuccess = () => {
            const database = request.result;
            if (settled) {
              database.close();
              return;
            }
            settled = true;
            clearTimeout(timeout);
            resolve(database);
          };
          request.onerror = () => fail(request.error || new Error("IndexedDB open failed"));
          request.onblocked = () => fail(new Error("IndexedDB open blocked"));
        });
      }
''',
        "bounded IndexedDB open",
    )

    legacy_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        await new Promise((resolve, reject) => {
          const request = store.openCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) {
              resolve();
              return;
            }
            const value = cursor.value;
            if (value && typeof value.path === "string" && typeof value.value === "string") {
              const path = normalize(value.path);
              if (isClientBootstrapPath(path)) files[path] = value.value;
            }
            cursor.continue();
          };
          request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
        });
      }
'''
    value_cursor_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        const downloadedPacks = [];
        await new Promise((resolve, reject) => {
          const request = store.openCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) {
              resolve();
              return;
            }
            const value = cursor.value;
            if (value && typeof value.path === "string") {
              const path = normalize(value.path);
              const storedValue = value.value;
              const supported = typeof storedValue === "string" ||
                storedValue instanceof ArrayBuffer || ArrayBuffer.isView(storedValue);
              if (supported && isClientBootstrapPath(path)) {
                if (isDownloadedPackBinaryPath(path)) {
                  downloadedPacks.push({
                    path,
                    value: storedValue,
                    updatedAt: Number(value.updatedAt) || 0
                  });
                } else {
                  const bytes = copyPersistentBytes(storedValue);
                  files[path] = bytes === null ? storedValue : bytes;
                }
              }
            }
            cursor.continue();
          };
          request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
        });

        downloadedPacks.sort((left, right) => right.updatedAt - left.updatedAt ||
          left.path.localeCompare(right.path));
        const maximumDownloadedPacks = 4;
        const maximumDownloadedPackBytes = 256 * 1024 * 1024;
        const evicted = [];
        let kept = 0;
        let keptBytes = 0;
        for (const entry of downloadedPacks) {
          const byteLength = persistentValueByteLength(entry.value);
          const withinBudget = kept < maximumDownloadedPacks &&
            (kept === 0 || keptBytes + byteLength <= maximumDownloadedPackBytes);
          if (withinBudget) {
            const bytes = copyPersistentBytes(entry.value);
            files[entry.path] = bytes === null ? entry.value : bytes;
            kept++;
            keptBytes += byteLength;
          } else {
            evicted.push(entry.path);
          }
        }
        if (evicted.length > 0) {
          const cleanup = database.transaction(storeName, "readwrite");
          const cleanupStore = cleanup.objectStore(storeName);
          for (const path of evicted) cleanupStore.delete(path);
          await transactionDone(cleanup);
          report("storage-download-cache-pruned", evicted.length + " files");
        }
      }
'''
    key_cursor_indexeddb_bootstrap = '''      async function readIndexedDbFiles(database) {
        const transaction = database.transaction(storeName, "readonly");
        const store = transaction.objectStore(storeName);
        const downloadedPacks = [];
        let scannedKeys = 0;
        let hydratedRecords = 0;
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            reject(new Error("IndexedDB bootstrap timed out"));
            try {
              transaction.abort();
            } catch (error) {
              // The transaction may have completed between the timer and abort.
            }
          }, 8000);
          transaction.oncomplete = () => {
            clearTimeout(timeout);
            resolve();
          };
          transaction.onerror = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          };
          transaction.onabort = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
          };
          const request = store.openKeyCursor();
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) return;
            scannedKeys++;
            const primaryKey = cursor.primaryKey;
            const path = normalize(primaryKey);
            if (isClientBootstrapPath(path)) {
              const read = store.get(primaryKey);
              read.onsuccess = () => {
                const value = read.result;
                if (value && typeof value.path === "string") {
                  const storedValue = value.value;
                  const supported = typeof storedValue === "string" ||
                    storedValue instanceof ArrayBuffer || ArrayBuffer.isView(storedValue);
                  if (supported) {
                    hydratedRecords++;
                    if (isDownloadedPackBinaryPath(path)) {
                      downloadedPacks.push({
                        path,
                        value: storedValue,
                        updatedAt: Number(value.updatedAt) || 0
                      });
                    } else {
                      const bytes = copyPersistentBytes(storedValue);
                      files[path] = bytes === null ? storedValue : bytes;
                    }
                  }
                }
              };
            }
            cursor.continue();
          };
          request.onerror = () => reject(
            request.error || new Error("IndexedDB key cursor failed"));
        });
        report("storage-key-scan", scannedKeys + " keys, hydrated=" + hydratedRecords);

        downloadedPacks.sort((left, right) => right.updatedAt - left.updatedAt ||
          left.path.localeCompare(right.path));
        const maximumDownloadedPacks = 4;
        const maximumDownloadedPackBytes = 256 * 1024 * 1024;
        const evicted = [];
        let kept = 0;
        let keptBytes = 0;
        for (const entry of downloadedPacks) {
          const byteLength = persistentValueByteLength(entry.value);
          const withinBudget = kept < maximumDownloadedPacks &&
            (kept === 0 || keptBytes + byteLength <= maximumDownloadedPackBytes);
          if (withinBudget) {
            const bytes = copyPersistentBytes(entry.value);
            files[entry.path] = bytes === null ? entry.value : bytes;
            kept++;
            keptBytes += byteLength;
          } else {
            evicted.push(entry.path);
          }
        }
        if (evicted.length > 0) {
          const cleanup = database.transaction(storeName, "readwrite");
          const cleanupStore = cleanup.objectStore(storeName);
          for (const path of evicted) cleanupStore.delete(path);
          await transactionDone(cleanup);
          report("storage-download-cache-pruned", evicted.length + " files");
        }
      }
'''
    untimed_key_cursor_indexeddb_bootstrap = key_cursor_indexeddb_bootstrap.replace(
        '''          const timeout = setTimeout(() => {
            reject(new Error("IndexedDB bootstrap timed out"));
            try {
              transaction.abort();
            } catch (error) {
              // The transaction may have completed between the timer and abort.
            }
          }, 8000);
          transaction.oncomplete = () => {
            clearTimeout(timeout);
            resolve();
          };
          transaction.onerror = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          };
          transaction.onabort = () => {
            clearTimeout(timeout);
            reject(transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
          };
''',
        '''          transaction.oncomplete = () => resolve();
          transaction.onerror = () => reject(
            transaction.error || new Error("IndexedDB bootstrap transaction failed"));
          transaction.onabort = () => reject(
            transaction.error || new Error("IndexedDB bootstrap transaction aborted"));
''',
        1,
    )
    if value_cursor_indexeddb_bootstrap in text:
        text = text.replace(
            value_cursor_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            1,
        )
    elif untimed_key_cursor_indexeddb_bootstrap in text:
        text = text.replace(
            untimed_key_cursor_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            1,
        )
    else:
        text = replace_required(
            text,
            legacy_indexeddb_bootstrap,
            key_cursor_indexeddb_bootstrap,
            "key-only IndexedDB bootstrap",
        )

    text = replace_required(
        text,
        '''        } catch (error) {
          const restored = loadLocalStorageFallback();
          window.__gaiusFsBackend = "localStorage";
          report("storage-indexeddb-unavailable", (error && error.message ? error.message : String(error)) + ", fallbackFiles=" + restored);
        }
''',
        '''        } catch (error) {
          if (db) {
            try {
              db.close();
            } catch (closeError) {
              // The failed transaction may already have closed the database.
            }
            db = null;
          }
          const restored = loadLocalStorageFallback();
          window.__gaiusFsBackend = "localStorage";
          report("storage-indexeddb-unavailable", (error && error.message ? error.message : String(error)) + ", fallbackFiles=" + restored);
        }
''',
        "IndexedDB startup fallback closes unusable database",
    )

    text = replace_required(
        text,
        '''      async function putIndexedDbFile(path, value) {
        const transaction = db.transaction(storeName, "readwrite");
        transaction.objectStore(storeName).put({ path: normalize(path), value: String(value || "") });
        await transactionDone(transaction);
      }
''',
        '''      async function putIndexedDbFile(path, value) {
        const transaction = db.transaction(storeName, "readwrite");
        const bytes = copyPersistentBytes(value);
        const storedValue = bytes === null ? String(value || "") : bytes;
        transaction.objectStore(storeName).put({
          path: normalize(path),
          value: storedValue,
          updatedAt: Date.now()
        });
        await transactionDone(transaction);
      }
''',
        "binary IndexedDB writer",
    )

    if "window.__gaiusFsPutBytes = function(path, value)" not in text:
        text = replace_required(
            text,
            '''      window.__gaiusFsDelete = function(path) {
''',
            '''      window.__gaiusFsPutBytes = function(path, value) {
        path = normalize(path);
        const bytes = copyPersistentBytes(value);
        if (bytes === null || !db) return false;
        files[path] = bytes;
        pending = pending.then(() => putIndexedDbFile(path, bytes)).catch(error => {
          report("storage-indexeddb-write-error", path + ": " + (error && error.message ? error.message : String(error)));
        });
        return true;
      };

      window.__gaiusFsDelete = function(path) {
''',
            "binary IndexedDB browser writer",
        )

    if "bootTimings.beforeClassesPaint" not in text:
        text = replace_required(
            text,
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      await waitForPaint();\n"
            "      bootTimings.beforeClassesPaint = performance.now();\n"
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n"
            "        (\"classes.js?v=\" + encodeURIComponent(buildToken));\n"
            "      await loadScript(classesUrl);\n",
            "paint before classes",
        )

    if "const classesUrl = window.__gaiusClassesUrl" not in text:
        text = replace_required(
            text,
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n"
            "        (\"classes.js?v=\" + encodeURIComponent(buildToken));\n"
            "      await loadScript(classesUrl);\n",
            "portable TeaVM client asset URL",
        )

    if "bootTimings.vanillaAssetsReady" not in text:
        text = replace_required(
            text,
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n",
            "      await (window.__gaiusPortableAssetsReady || Promise.resolve());\n"
            "      setBootProgress(Math.max(bootProgressValue, 30), \"Loading game assets...\");\n"
            "      await window.__gaiusVanillaAssetsReady;\n"
            "      bootTimings.vanillaAssetsReady = performance.now();\n"
            "      const classesUrl = window.__gaiusClassesUrl ||\n",
            "vanilla assets before TeaVM client",
        )

    if "bootTimings.mainStart" not in text:
        text = replace_required(
            text,
            "\t        setStatus(\"running\", \"已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…\\n\" + window.__gaiusDefaultArgs.join(\" \"), 68, \"启动 Minecraft 客户端…\");\n"
            "\t        main(window.__gaiusDefaultArgs);\n"
            "\t        setBootProgress(82, \"等待 Minecraft 首帧/主界面…\");\n",
            "\t        setStatus(\"running\", \"已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…\\n\" + window.__gaiusDefaultArgs.join(\" \"), 68, \"启动 Minecraft 客户端…\");\n"
            "\t        await waitForPaint();\n"
            "\t        bootTimings.mainStart = performance.now();\n"
            "\t        main(window.__gaiusDefaultArgs);\n"
            "\t        bootTimings.mainReturned = performance.now();\n"
            "\t        setBootProgress(82, \"等待 Minecraft 首帧/主界面…\");\n",
            "main timing",
        )

    text = re.sub(
        r'\n\s*"--disableMultiplayer",',
        "",
        text,
        count=1,
    )

    session_block = '''    function createGaiusProxyUrl(target, kind) {
      const configured = urlParams.get("bridge") || urlParams.get("relay") || window.__gaiusBridgeUrl;
      let bridge;
      if (configured && String(configured).trim()) {
        bridge = new URL(String(configured).trim(), location.href);
      } else {
        const scheme = location.protocol === "https:" ? "https:" : "http:";
        const rawHost = String(window.__gaiusBridgeHost || location.hostname || "127.0.0.1");
        const host = rawHost.includes(":") &&
          !(rawHost.startsWith("[") && rawHost.endsWith("]"))
          ? "[" + rawHost + "]"
          : rawHost;
        const port = window.__gaiusBridgePort || "8080";
        bridge = new URL(scheme + "//" + host + ":" + port + "/");
      }
      if (bridge.protocol === "ws:") bridge.protocol = "http:";
      if (bridge.protocol === "wss:") bridge.protocol = "https:";
      if (bridge.protocol !== "http:" && bridge.protocol !== "https:") {
        throw new Error("Unsupported Gaius bridge protocol: " + bridge.protocol);
      }
      bridge.pathname = "/proxy/" + String(kind);
      bridge.hash = "";
      bridge.search = "";
      bridge.searchParams.set("url", String(target));
      const token = urlParams.get("bridgeToken") || urlParams.get("relayToken") || window.__gaiusBridgeToken;
      if (token && String(token).length) bridge.searchParams.set("token", String(token));
      return bridge.href;
    }

    async function loadGaiusMinecraftProfile(accessToken) {
      const response = await fetch(createGaiusProxyUrl(
        "https://api.minecraftservices.com/minecraft/profile",
        "auth"
      ), {
        method: "GET",
        headers: {
          "accept": "application/json",
          "authorization": "Bearer " + accessToken
        },
        cache: "no-store"
      });
      if (!response.ok) {
        throw new Error("Minecraft profile request failed with HTTP " + response.status);
      }
      const profile = await response.json();
      const username = String(profile && profile.name || "").trim();
      const uuid = String(profile && profile.id || "").replace(/-/g, "").toLowerCase();
      if (!/^[A-Za-z0-9_]{1,16}$/.test(username) || !/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Minecraft profile response did not contain a valid Java profile");
      }
      return {username, uuid};
    }

    function requestGaiusPlayerName(initialName) {
      if (!profileGate || !profileForm || !profileName || !profileError) {
        throw new Error("The Gaius player-name screen is unavailable");
      }
      profileName.value = String(initialName || "").trim();
      profileError.textContent = "";
      profileGate.hidden = false;
      if (bootBrand) bootBrand.hidden = true;
      if (bootProgress) bootProgress.hidden = true;
      if (bootProgressText) bootProgressText.hidden = true;
      statusBox.hidden = true;
      return new Promise(resolve => {
        const submit = event => {
          event.preventDefault();
          const username = profileName.value.trim();
          if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) {
            profileError.textContent = "Use 1-16 letters, numbers, or underscores.";
            profileName.focus();
            return;
          }
          profileForm.removeEventListener("submit", submit);
          try {
            localStorage.setItem("gaius.playerName", username);
          } catch (error) {
            console.warn("Could not remember the Gaius player name", error);
          }
          profileGate.hidden = true;
          if (bootBrand) bootBrand.hidden = false;
          if (bootProgress) bootProgress.hidden = false;
          if (bootProgressText) bootProgressText.hidden = false;
          statusBox.hidden = !showLauncherDetails;
          setBootProgress(Math.max(bootProgressValue, 3), "Preparing the browser runtime...");
          resolve(username);
        };
        profileForm.addEventListener("submit", submit);
        requestAnimationFrame(() => {
          profileName.focus();
          profileName.select();
        });
      });
    }

    function changeGaiusPlayerName() {
      if (profileSwitch) profileSwitch.disabled = true;
      try {
        sessionStorage.removeItem("gaius.session");
      } catch (error) {
        console.warn("Could not clear the current Gaius session", error);
      }
      window.__gaiusSession = {};
      const next = new URL(location.href);
      for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
        next.searchParams.delete(key);
      }
      try {
        if (typeof window.__gaiusReleaseRuntimeLease === "function") {
          window.__gaiusReleaseRuntimeLease();
        }
      } finally {
        location.replace(next.href);
      }
    }

    function updateGaiusProfileSwitch() {
      if (!profileSwitch) return;
      const state = window.__gaiusMinecraftState;
      const screen = state && state.screen
        ? String(state.screen).split(".").pop()
        : "";
      profileSwitch.hidden = !profileGate || !profileGate.hidden || screen !== "TitleScreen";
    }

    if (profileSwitch) {
      profileSwitch.addEventListener("click", changeGaiusPlayerName);
      setInterval(updateGaiusProfileSwitch, 250);
      updateGaiusProfileSwitch();
    }
    window.__gaiusChangePlayerName = changeGaiusPlayerName;

    async function buildGaiusSessionArgs() {
      let stored = {};
      try {
        const value = sessionStorage.getItem("gaius.session");
        stored = value ? JSON.parse(value) : {};
      } catch (error) {
        console.warn("Ignoring invalid Gaius sessionStorage data", error);
      }
      const injected = window.__gaiusSession && typeof window.__gaiusSession === "object"
        ? window.__gaiusSession
        : {};
      const queried = {};
      for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
        const value = urlParams.get(key);
        if (value !== null && value.trim()) queried[key] = value.trim();
      }
      const session = Object.assign({}, stored, injected, queried);
      const quickPlayServer = String(urlParams.get("server") || "").trim();
      if (quickPlayServer.length > 512) {
        throw new Error("Quick-play server address is too long");
      }
      const accessToken = String(session.accessToken || "").trim();
      const online = accessToken.length > 0 && accessToken !== "0";
      let username = String(session.username || "").trim();
      let uuid = String(session.uuid || (online ? "" : "00000000000040008000000000000001"))
        .replace(/-/g, "")
        .toLowerCase();
      if (username && !/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Player name must use 1-16 letters, numbers, or underscores");
      }
      if (uuid && !/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Online session UUID must contain 32 hexadecimal digits");
      }
      if (online && (!username || !uuid)) {
        const profile = await loadGaiusMinecraftProfile(accessToken);
        if (!username) username = profile.username;
        if (!uuid) uuid = profile.uuid;
        session.username = username;
        session.uuid = uuid;
        sessionStorage.setItem("gaius.session", JSON.stringify(session));
      }
      if (!online && !username) {
        let rememberedName = "";
        try {
          rememberedName = localStorage.getItem("gaius.playerName") || "";
        } catch (error) {
          console.warn("Could not read the remembered Gaius player name", error);
        }
        username = await requestGaiusPlayerName(rememberedName);
        session.username = username;
        session.uuid = uuid;
        sessionStorage.setItem("gaius.session", JSON.stringify(session));
      }
      if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Player name must use 1-16 letters, numbers, or underscores");
      }
      if (!/^[0-9a-f]{32}$/.test(uuid)) {
        throw new Error("Online session UUID must contain 32 hexadecimal digits");
      }
      const args = [
        "--version", "1.21.11",
        "--versionType", "release",
        "--accessToken", online ? accessToken : "0",
        "--username", username,
        "--uuid", uuid,
        "--gameDir", "/gaius",
        "--assetsDir", "/gaius/assets",
        "--assetIndex", "1.21.11",
        "--resourcePackDir", "/gaius/resourcepacks",
        "--width", String(Math.max(854, window.innerWidth || 854)),
        "--height", String(Math.max(480, window.innerHeight || 480))
      ];
      if (online) {
        if (session.xuid) args.push("--xuid", String(session.xuid));
        if (session.clientId) args.push("--clientId", String(session.clientId));
      } else {
        args.push("--offlineDeveloperMode");
      }
      if (quickPlayServer) args.push("--quickPlayMultiplayer", quickPlayServer);
      window.__gaiusSessionMode = online ? "online" : "offline";
      return args;
    }

    window.__gaiusConfigureSession = session => {
      if (!session || typeof session !== "object") throw new TypeError("Session must be an object");
      sessionStorage.setItem("gaius.session", JSON.stringify(session));
    };
    window.__gaiusClearSession = () => sessionStorage.removeItem("gaius.session");
    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();
    window.__gaiusDefaultArgsPromise.catch(() => {});
    const identityQueryKeys = ["username", "uuid", "accessToken", "xuid", "clientId"];
    if (identityQueryKeys.some(key => urlParams.has(key))) {
      const scrubbed = new URL(location.href);
      for (const key of identityQueryKeys) scrubbed.searchParams.delete(key);
      history.replaceState(history.state, "", scrubbed.pathname + scrubbed.search + scrubbed.hash);
    }'''
    if "function createGaiusProxyUrl(target, kind)" in text:
        text, count = re.subn(
            r'    function createGaiusProxyUrl\(target, kind\) \{.*?'
            r'(?:    const identityQueryKeys = \["username", "uuid", "accessToken", "xuid", "clientId"\];\n)?'
            r'    if \((?:identityQueryKeys\.some\(key => urlParams\.has\(key\)\)'
            r'|urlParams\.has\("accessToken"\))\) \{\n'
            r'      const scrubbed = new URL\(location\.href\);\n'
            r'(?:      for \(const key of identityQueryKeys\) scrubbed\.searchParams\.delete\(key\);'
            r'|      scrubbed\.searchParams\.delete\("accessToken"\);)\n'
            r'      history\.replaceState\(history\.state, "", scrubbed\.pathname \+ scrubbed\.search \+ scrubbed\.hash\);\n'
            r'    \}',
            lambda _match: session_block,
            text,
            count=1,
            flags=re.DOTALL,
        )
    else:
        text, count = re.subn(
            r'    window\.__gaiusDefaultArgs = \[\n.*?\n    \];',
            lambda _match: session_block,
            text,
            count=1,
            flags=re.DOTALL,
        )
    if count == 0:
        raise RuntimeError("index.html patch point was not found: browser session arguments")

    # Keep previously postprocessed launchers current when relay aliases are added later.
    text = text.replace(
        'const configured = urlParams.get("bridge") || window.__gaiusBridgeUrl;',
        'const configured = urlParams.get("bridge") || urlParams.get("relay") || window.__gaiusBridgeUrl;',
        1,
    )
    text = text.replace(
        'const token = urlParams.get("bridgeToken") || window.__gaiusBridgeToken;',
        'const token = urlParams.get("bridgeToken") || urlParams.get("relayToken") || window.__gaiusBridgeToken;',
        1,
    )

    if "window.__gaiusDefaultArgsPromise.catch(() => {});" not in text:
        text = replace_required(
            text,
            "    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();\n",
            "    window.__gaiusDefaultArgsPromise = buildGaiusSessionArgs();\n"
            "    window.__gaiusDefaultArgsPromise.catch(() => {});\n",
            "online session rejection handler",
        )

    if "const quickPlayServer = String(urlParams.get(\"server\")" not in text:
        text = text.replace(
            "      const session = Object.assign({}, stored, injected, queried);\n",
            "      const session = Object.assign({}, stored, injected, queried);\n"
            "      const quickPlayServer = String(urlParams.get(\"server\") || \"\").trim();\n"
            "      if (quickPlayServer.length > 512) {\n"
            "        throw new Error(\"Quick-play server address is too long\");\n"
            "      }\n",
            1,
        )
        text = text.replace(
            "      window.__gaiusSessionMode = online ? \"online\" : \"offline\";\n",
            "      if (quickPlayServer) args.push(\"--quickPlayMultiplayer\", quickPlayServer);\n"
            "      window.__gaiusSessionMode = online ? \"online\" : \"offline\";\n",
            1,
        )
        if ("const quickPlayServer = String(urlParams.get(\"server\")" not in text
                or "--quickPlayMultiplayer" not in text):
            raise RuntimeError("index.html patch point was not found: quick-play multiplayer")

    text = text.replace(
        'window.__gaiusDefaultArgs.join(" ")',
        'window.__gaiusDisplayArgs.join(" ")',
    )

    if "bootTimings.sessionReady" not in text:
        text = replace_required(
            text,
            "      bootTimings.fsReady = performance.now();\n",
            "      bootTimings.fsReady = performance.now();\n"
            "      window.__gaiusDefaultArgs = await window.__gaiusDefaultArgsPromise;\n"
            "      window.__gaiusDisplayArgs = window.__gaiusDefaultArgs.map((value, index, args) =>\n"
            "        index > 0 && args[index - 1] === \"--accessToken\" ? \"<redacted>\" : value\n"
            "      );\n"
            "      bootTimings.sessionReady = performance.now();\n",
            "online session resolution",
        )

    if "bootTimings.runtimeLeaseReady" not in text:
        text = replace_required(
            text,
            "      bootTimings.sessionReady = performance.now();\n",
            "      bootTimings.sessionReady = performance.now();\n"
            "      if (!await acquireGaiusRuntimeLease()) {\n"
            "        throw new Error(\"Gaius is already running in another tab. Close that tab, then reload this page.\");\n"
            "      }\n"
            "      bootTimings.runtimeLeaseReady = performance.now();\n",
            "single active Gaius runtime acquisition",
        )

    if "window.__gaiusBootError = error;" not in text:
        text = replace_required(
            text,
            "    })().catch(error => {\n"
            "      setStatus(\"error\", [\n",
            "    })().catch(error => {\n"
            "      window.__gaiusBootError = error;\n"
            "      setStatus(\"error\", [\n",
            "boot error diagnostics",
        )

    if "window.__gaiusBootErrorDetails" not in text:
        text = replace_required(
            text,
            "      window.__gaiusBootError = error;\n"
            "      setStatus(\"error\", [\n",
            "      window.__gaiusBootError = error;\n"
            "      const bootErrorDetails = [];\n"
            "      const primitiveFields = value => Object.fromEntries(\n"
            "        Object.getOwnPropertyNames(value).slice(0, 80).flatMap(key => {\n"
            "          const field = value[key];\n"
            "          return field == null || [\"string\", \"number\", \"boolean\", \"bigint\"].includes(typeof field)\n"
            "            ? [[key, String(field)]] : [];\n"
            "        })\n"
            "      );\n"
            "      for (const symbol of Object.getOwnPropertySymbols(error || {})) {\n"
            "        const javaError = error[symbol];\n"
            "        if (!javaError || typeof javaError !== \"object\") continue;\n"
            "        bootErrorDetails.push(\"Java fields: \" + JSON.stringify(primitiveFields(javaError)));\n"
            "        const constructor = javaError.constructor;\n"
            "        for (const metadataSymbol of Object.getOwnPropertySymbols(constructor || {})) {\n"
            "          const metadata = constructor[metadataSymbol];\n"
            "          if (metadata && typeof metadata === \"object\") {\n"
            "            const fields = primitiveFields(metadata);\n"
            "            if (Object.keys(fields).length) bootErrorDetails.push(\"Java class: \" + JSON.stringify(fields));\n"
            "          }\n"
            "        }\n"
            "      }\n"
            "      window.__gaiusBootErrorDetails = bootErrorDetails;\n"
            "      setStatus(\"error\", [\n",
            "boot Java exception diagnostics",
        )

        text = replace_required(
            text,
            "        error && error.stack ? error.stack : String(error)\n"
            "      ].join(\"\\n\"));\n",
            "        error && error.stack ? error.stack : String(error),\n"
            "        ...bootErrorDetails\n"
            "      ].join(\"\\n\"));\n",
            "boot Java exception diagnostic display",
        )

    english_launcher_text = {
        "正在启动官方 Minecraft Java Edition 1.21.11 TeaVM 客户端…":
            "Starting Gaius Client 1.21.11...",
        "0% 初始化…": "0% Initializing...",
        'let bootProgressLabel = "初始化…";':
            'let bootProgressLabel = "Initializing...";',
        "初始化浏览器运行环境…": "Preparing the browser runtime...",
        "已进入世界": "World ready",
        "Minecraft 界面已加载：": "Minecraft screen ready: ",
        "检测到启动阶段超过 30 秒没有进度变化；浏览器仍在运行，但可能卡在资源加载、世界生成或主线程长任务中。":
            "Startup has made no visible progress for 30 seconds. The browser is still running, "
            "but resource loading, world generation, or a long main-thread task may be stalled.",
        "浏览器运行时错误：": "Browser runtime error:",
        "浏览器 Promise 未处理异常：": "Unhandled browser promise rejection:",
        "无法加载 ": "Could not load ",
        "正在加载浏览器持久化存储 IndexedDB…":
            "Loading persistent browser storage...",
        "加载浏览器持久化存储…": "Loading browser storage...",
        "浏览器存储已就绪（": "Browser storage is ready (",
        "），正在加载 classes.js…": "); loading classes.js...",
        "加载 1.21.11 TeaVM 主程序…": "Loading the 1.21.11 client runtime...",
        "TeaVM 产物未暴露 main(args)，无法启动。":
            "The TeaVM build did not expose main(args).",
        "已加载 classes.js，调用 net.minecraft.client.main.Main.main(args)…":
            "classes.js loaded; calling net.minecraft.client.main.Main.main(args)...",
        "启动 Minecraft 客户端…": "Starting Gaius Client...",
        "等待 Minecraft 首帧/主界面…": "Waiting for the first client frame...",
        "启动 Minecraft TeaVM 客户端失败：": "Gaius Client failed to start:",
    }
    for source, replacement in english_launcher_text.items():
        text = text.replace(source, replacement)

    text = re.sub(
        r'Starting Gaius Client [A-Za-z0-9._+-]+\.\.\.',
        f'Starting Gaius Client {minecraft_version}...',
        text,
        count=1,
    )
    text = re.sub(
        r'Loading the [A-Za-z0-9._+-]+ client runtime\.\.\.',
        f'Loading the {minecraft_version} client runtime...',
        text,
        count=1,
    )
    text, version_argument_count = re.subn(
        r'("--version", ")[^"]+("\s*,)',
        rf'\g<1>{minecraft_version}\g<2>',
        text,
        count=1,
    )
    if version_argument_count != 1:
        raise RuntimeError("index.html patch point was not found: Minecraft version argument")
    text, asset_index_argument_count = re.subn(
        r'("--assetIndex", ")[^"]+("\s*,)',
        rf'\g<1>{asset_index_id}\g<2>',
        text,
        count=1,
    )
    if asset_index_argument_count != 1:
        raise RuntimeError("index.html patch point was not found: asset index argument")

    text = apply_gaius_client_shell(text)

    if text != original:
        index.write_text(text, encoding="utf-8")
        return True
    return False


def version_defaults() -> tuple[str, str]:
    minecraft_version = os.environ.get("GAIUS_MINECRAFT_VERSION", "1.21.11").strip()
    asset_index_id = os.environ.get("GAIUS_ASSET_INDEX_ID", "").strip()
    metadata_path = os.environ.get("GAIUS_VERSION_METADATA", "").strip()
    if not asset_index_id and metadata_path:
        try:
            metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
            asset_index_id = str(
                metadata.get("assetIndex", {}).get("id") or metadata.get("assets") or ""
            ).strip()
        except (OSError, ValueError, TypeError):
            asset_index_id = ""
    return minecraft_version, asset_index_id or minecraft_version


def main(argv: list[str]) -> int:
    if len(argv) not in (3, 5):
        print(
            "usage: postprocess-index-html.py <index.html> <classes.js> "
            "[<minecraft-version> <asset-index-id>]",
            file=sys.stderr,
        )
        return 2

    index = Path(argv[1])
    classes_js = Path(argv[2])
    minecraft_version, asset_index_id = version_defaults()
    if len(argv) == 5:
        minecraft_version = argv[3].strip()
        asset_index_id = argv[4].strip()
    identifier = re.compile(r"^[A-Za-z0-9._+-]+$")
    if not identifier.fullmatch(minecraft_version) or not identifier.fullmatch(asset_index_id):
        print("invalid Minecraft version or asset index identifier", file=sys.stderr)
        return 2
    if not index.exists():
        print(f"missing index.html: {index}", file=sys.stderr)
        return 1
    if not classes_js.exists():
        print(f"missing classes.js: {classes_js}", file=sys.stderr)
        return 1

    changed = patch_index(index, classes_js, minecraft_version, asset_index_id)
    status = "Patched" if changed else "Index already patched"
    print(f"{status}: {index}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
