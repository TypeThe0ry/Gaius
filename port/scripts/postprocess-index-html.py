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


def patch_index(index: Path, classes_js: Path) -> bool:
    build_token = hashlib.sha256(classes_js.read_bytes()).hexdigest()[:16]
    text = index.read_text(encoding="utf-8")
    original = text

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
        if count == 0:
            raise RuntimeError("index.html patch point was not found: stable build token")

    if "bootTimings.fsReady" not in text:
        text = replace_required(
            text,
            "      await window.__gaiusFsReady;\n",
            "      await window.__gaiusFsReady;\n"
            "      bootTimings.fsReady = performance.now();\n",
            "fs ready timing",
        )

    if "bootTimings.beforeClassesPaint" not in text:
        text = replace_required(
            text,
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "      setStatus(\"running\", \"浏览器存储已就绪（\" + (window.__gaiusFsBackend || \"unknown\") + \"），正在加载 classes.js…\", 24, \"加载 1.21.11 TeaVM 主程序…\");\n"
            "      await waitForPaint();\n"
            "      bootTimings.beforeClassesPaint = performance.now();\n"
            "      await loadScript(\"classes.js?v=\" + encodeURIComponent(buildToken));\n",
            "paint before classes",
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
