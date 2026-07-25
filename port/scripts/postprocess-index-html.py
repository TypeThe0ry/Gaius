#!/usr/bin/env python3
"""Patch the ignored dist index page after TeaVM emits classes.js.

The browser client keeps the HTML launcher in port/web/dist, which is ignored
with the rest of the generated assets. Keep browser startup fixes reproducible
by applying them from this tracked script after every successful TeaVM build.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise RuntimeError(f"index.html patch point was not found: {label}")


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


def patch_index(index: Path, classes_js: Path) -> bool:
    build_token = content_token(classes_js)
    singleplayer_token = content_token(
        index.parent / "singleplayer-server-worker.js",
        index.parent / "singleplayer-server.js",
    )
    text = index.read_text(encoding="utf-8")
    original = text

    text = text.replace(
        '    const showPerfHud = urlParams.get("hud") !== "0";\n',
        '    const showPerfHud = urlParams.get("hud") === "1";\n',
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
        "        fps.rafFrameTimes = [];\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          const samples = fps.rafFrameTimes || (fps.rafFrameTimes = []);\n"
        "          samples.push(frameMs);\n"
        "          if (samples.length > 2048) samples.splice(0, samples.length - 2048);\n"
        "          fps.rafLongestFrameMs = Math.max(fps.rafLongestFrameMs || 0, frameMs);\n"
        "        }\n"
        "      }\n"
        "      fps.rafLastFrameAt = now;\n"
        "      fps.frames++;\n"
        "      const elapsed = now - fps.lastSampleAt;\n"
        "      if (elapsed >= 1000) {\n"
        "        fps.rafFps = Math.round((fps.frames * 1000 / elapsed) * 10) / 10;\n"
        "        const samples = fps.rafFrameTimes || [];\n"
        "        if (samples.length > 0) {\n"
        "          const ordered = samples.slice().sort((left, right) => left - right);\n"
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
        "        fps.rafFrameTimes = [];\n"
        "        fps.rafLastFrameAt = 0;\n"
        "        fps.rafLongestFrameMs = 0;\n"
        "      } else if (!inWorld) {\n"
        "        fps.rafMetricsWorldEnteredAt = 0;\n"
        "      }\n"
        "      const previousFrameAt = fps.rafLastFrameAt;\n"
        "      if (Number.isFinite(previousFrameAt) && previousFrameAt > 0) {\n"
        "        const frameMs = now - previousFrameAt;\n"
        "        if (frameMs > 0 && frameMs <= 1000 && fps.rafMetricsWorldEnteredAt) {\n"
        "          const samples = fps.rafFrameTimes || (fps.rafFrameTimes = []);\n"
        "          samples.push(frameMs);\n"
        "          if (samples.length > 2048) samples.splice(0, samples.length - 2048);\n"
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
        "        const samples = fps.rafFrameTimes || [];\n"
        "        if (samples.length > 0) {\n"
        "          const ordered = samples.slice().sort((left, right) => left - right);\n"
        "          const totalMs = ordered.reduce((sum, value) => sum + value, 0);\n"
        "          const onePercentIndex = Math.min(ordered.length - 1, Math.ceil(ordered.length * 0.99) - 1);\n"
        "          fps.rafAverageFps = Math.round((ordered.length * 1000 / totalMs) * 10) / 10;\n"
        "          fps.rafOnePercentLow = Math.round((1000 / ordered[onePercentIndex]) * 10) / 10;\n"
        "        }\n"
        "        fps.fps = fps.rafFps;\n",
    )
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

    if "Minecraft-style boot screen" not in text:
        text = replace_required(
            text,
            "  </style>\n",
            "    /* Minecraft-style boot screen */\n"
            "    #boot-screen {\n"
            "      position: fixed;\n"
            "      inset: 0;\n"
            "      z-index: 10;\n"
            "      background: #ef323d;\n"
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
            "      font: 900 72px/0.72 Arial, Helvetica, sans-serif;\n"
            "      letter-spacing: 0;\n"
            "      text-shadow: 0 4px 0 rgba(0, 0, 0, 0.16);\n"
            "      pointer-events: none;\n"
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
            "      background: #fff;\n"
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
            "  </style>\n",
            "Minecraft-style boot screen CSS",
        )
        text = replace_required(
            text,
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n',
            '  <canvas id="mc-canvas" tabindex="0"></canvas>\n'
            '  <div id="boot-screen" aria-hidden="true"></div>\n'
            '  <div id="boot-brand" aria-hidden="true">MOJANG<span>STUDIOS</span></div>\n',
            "Minecraft-style boot screen markup",
        )
        text = replace_required(
            text,
            '    const statusBox = document.getElementById("status");\n',
            '    const statusBox = document.getElementById("status");\n'
            '    const bootScreen = document.getElementById("boot-screen");\n'
            '    const bootBrand = document.getElementById("boot-brand");\n',
            "Minecraft-style boot screen elements",
        )
        text = replace_required(
            text,
            "      statusBox.hidden = true;\n",
            "      statusBox.hidden = true;\n"
            "      if (bootScreen) bootScreen.hidden = true;\n"
            "      if (bootBrand) bootBrand.hidden = true;\n",
            "Minecraft-style boot screen cleanup",
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
      let username = String(session.username || (online ? "" : "BrowserPlayer")).trim();
      let uuid = String(session.uuid || (online ? "" : "00000000000040008000000000000001"))
        .replace(/-/g, "")
        .toLowerCase();
      if (username && !/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Online session username must be 1-16 Minecraft name characters");
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
      if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) {
        throw new Error("Online session username must be 1-16 Minecraft name characters");
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
    if (urlParams.has("accessToken")) {
      const scrubbed = new URL(location.href);
      scrubbed.searchParams.delete("accessToken");
      history.replaceState(history.state, "", scrubbed.pathname + scrubbed.search + scrubbed.hash);
    }'''
    if "async function buildGaiusSessionArgs()" not in text:
        if "function buildGaiusSessionArgs()" in text:
            text, count = re.subn(
                r'    function buildGaiusSessionArgs\(\) \{.*?'
                r'    if \(urlParams\.has\("accessToken"\)\) \{\n'
                r'      const scrubbed = new URL\(location\.href\);\n'
                r'      scrubbed\.searchParams\.delete\("accessToken"\);\n'
                r'      history\.replaceState\(history\.state, "", scrubbed\.pathname \+ scrubbed\.search \+ scrubbed\.hash\);\n'
                r'    \}',
                session_block,
                text,
                count=1,
                flags=re.DOTALL,
            )
        else:
            text, count = re.subn(
                r'    window\.__gaiusDefaultArgs = \[\n.*?\n    \];',
                session_block,
                text,
                count=1,
                flags=re.DOTALL,
            )
        if count == 0:
            raise RuntimeError("index.html patch point was not found: browser session arguments")

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

    if text != original:
        index.write_text(text, encoding="utf-8")
        return True
    return False


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: postprocess-index-html.py <index.html> <classes.js>", file=sys.stderr)
        return 2

    index = Path(argv[1])
    classes_js = Path(argv[2])
    if not index.exists():
        print(f"missing index.html: {index}", file=sys.stderr)
        return 1
    if not classes_js.exists():
        print(f"missing classes.js: {classes_js}", file=sys.stderr)
        return 1

    changed = patch_index(index, classes_js)
    status = "Patched" if changed else "Index already patched"
    print(f"{status}: {index}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
