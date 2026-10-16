#!/usr/bin/env python3
"""Static smoke test for the generated Gaius browser-client shell."""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POSTPROCESS = ROOT / "port/scripts/postprocess-index-html.py"
SOURCE_INDEX = ROOT / "port/web/launcher/index.template.html"


def require(text: str, needle: str) -> None:
    if needle not in text:
        raise AssertionError(f"generated launcher is missing: {needle}")


def check_inline_script_syntax(html: str, directory: Path) -> None:
    node = shutil.which("node")
    if not node:
        return
    scripts = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", html, re.DOTALL)
    if not scripts:
        raise AssertionError("generated launcher has no inline scripts")
    for index, source in enumerate(scripts):
        script = directory / f"inline-{index}.js"
        script.write_text(source, encoding="utf-8")
        result = subprocess.run(
            [node, "--check", str(script)],
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"inline script {index} failed Node syntax check:\n{result.stderr}"
            )


def main() -> int:
    if not SOURCE_INDEX.is_file():
        raise SystemExit(f"missing tracked launcher template: {SOURCE_INDEX}")

    with tempfile.TemporaryDirectory(prefix="gaius-shell-smoke-") as temporary:
        directory = Path(temporary)
        index = directory / "index.html"
        classes = directory / "classes.js"
        index.write_text(SOURCE_INDEX.read_text(encoding="utf-8"), encoding="utf-8")
        classes.write_text("window.__gaiusShellSmokeClasses = true;\n", encoding="utf-8")

        first = subprocess.run(
            [
                sys.executable,
                str(POSTPROCESS),
                str(index),
                str(classes),
                "26.2",
                "32",
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        if first.returncode != 0:
            raise AssertionError(f"postprocess failed:\n{first.stdout}\n{first.stderr}")

        generated = index.read_text(encoding="utf-8")
        for selector in (
            'id="mc-canvas"',
            'id="boot-screen"',
            'id="boot-progress"',
            'id="status"',
            'id="gaius-shell-header"',
            'id="gaius-shell-footer"',
            'id="gaius-error-actions"',
            'id="gaius-retry"',
            'id="gaius-error-toggle"',
            'id="gaius-error-details"',
        ):
            require(generated, selector)

        # The in-game Edit Profile screen replaces the HTML gate, switch and overlay.
        for removed in ('id="profile-gate"', 'id="profile-switch"', 'id="mc-name-overlay"',
                        'requestGaiusPlayerName', 'gaius.pendingPlayerName'):
            if removed in generated:
                raise AssertionError(f"generated launcher still contains legacy profile UI: {removed}")

        for contract in (
            "window.__gaiusSetBootProgress",
            "window.__gaiusShowBootOverlay",
            "window.__gaiusDefaultArgsPromise",
            "window.__gaiusConfigureSession",
            "customGaiusSkinDescriptor",
            "window.__gaiusProfile = {",
            "function generateGaiusDefaultPlayerName()",
            "window.__gaiusProfile.firstRun = true;",
            "function gaiusTexturesValue(dataUrl, slim)",
            "function decodeGaiusSkinFile(file)",
            "window.__gaiusSkinDescriptor = customSkinDescriptor || session.skinDescriptor || null;",
            "new Float32Array(4096)",
            "fps.rafFrameWriteIndex",
            "fps.rafFrameCount",
            "const slowestCount = Math.max(1, Math.ceil(ordered.length * 0.01))",
            "slowestCount * 1000 / slowestTotalMs",
            'localStorage.setItem("gaius.playerName", username)',
            'sessionStorage.setItem("gaius.session"',
            'args.push("--quickPlayMultiplayer", quickPlayServer)',
            'next.searchParams.set("retry", String(Date.now()))',
            'window.__gaiusShell = {',
            'data-gaius-shell="v2"',
            '<title>Gaius Client 26.2</title>',
            '"--version", "26.2"',
            '"--assetIndex", "32"',
            'const prefix = "gaius.fs.v2:26.2:"',
            'const dbName = "gaius-fs-v2-26.2"',
            'indexedDB.open(dbName, 2)',
            'window.__gaiusProfileId = "26.2"',
            '  <script data-gaius-profile-early="v1">\n',
            "window.__gaiusApplyQualityPixelRatio = function applyGaiusQualityPixelRatio()",
            "try { GaiusQuality.caps.ensureTier(); } catch (e) {}",
            'window.__gaiusWorldVersion = 4903',
            'window.__gaiusStorageSchema = 2',
            'window.__gaiusStorageDatabaseName = "gaius-fs-v2-26.2"',
            'window.__gaiusStoragePrefix = "gaius.fs.v2:26.2:"',
            'window.__gaiusStorageOpfsDirectory = "regions-v2-26.2"',
            'relative === "data/minecraft/world_gen_settings.dat"',
            "Starting Gaius Client 26.2...",
            "Loading the 26.2 client runtime...",
        ):
            require(generated, contract)

        template = (ROOT / "port" / "web" / "launcher" / "index.template.html").read_text(encoding="utf-8")
        for contract in (
            'gaius.customSkin',
            'gaius.customSkinModel',
            'window.__gaiusProfile = {',
            'customGaiusSkinDescriptor',
            'data:image\\/png;base64,',
        ):
            require(template, contract)

        for visible_text in (
            "GAIUS",
            "CLIENT",
            "BROWSER CLIENT",
            "Retry startup",
            "Show diagnostics",
            "independent browser software",
        ):
            require(generated, visible_text)

        if "MOJANG AB" in generated.upper():
            raise AssertionError("launcher contains the prohibited Mojang AB brand text")
        if 'id="boot-brand" aria-hidden="true">MOJANG' in generated:
            raise AssertionError("launcher still uses a Mojang boot brand")
        if generated.count('data-gaius-shell="v2"') != 2:
            raise AssertionError("Gaius shell marker is not installed exactly twice")
        if generated.count('data-gaius-boot-art="v1"') != 2:
            raise AssertionError("Gaius boot art marker is not installed exactly twice")
        # Touch controls: one style and one script, after the boot art, plus a phone viewport.
        if generated.count('data-gaius-touch-controls="v1"') != 2:
            raise AssertionError("Gaius touch controls marker is not installed exactly twice")
        for contract in (
            "(function installGaiusTouchControls() {",
            "window.__gaiusInput",
            "w.__gaiusTouchActive = true;",
            ".gt-root {",
            "html.gaius-touch #mc-canvas {",
            "env(safe-area-inset-bottom, 0px)",
        ):
            require(generated, contract)
        if generated.count('<meta name="viewport"') != 1:
            raise AssertionError("launcher must have exactly one viewport meta")
        require(
            generated,
            '<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, '
            'user-scalable=no, interactive-widget=resizes-visual">',
        )
        if generated.find('<style data-gaius-touch-controls="v1">') > generated.find("</head>"):
            raise AssertionError("touch controls CSS is not in the document head")
        if generated.find('<script data-gaius-touch-controls="v1">') < generated.find(
            '<script data-gaius-boot-art="v1">'
        ):
            raise AssertionError("touch controls script must run after the boot art")
        if generated.count("const singleplayerBuildToken =") != 1:
            raise AssertionError("singleplayer build token must be declared exactly once")
        for assignment in (
            "window.__gaiusSingleplayerWorkerUrl = new URL(",
            "window.__gaiusSingleplayerServerUrl = new URL(",
            "window.__gaiusSingleplayerServerGzipUrl = new URL(",
        ):
            if generated.count(assignment) != 1:
                raise AssertionError(
                    f"singleplayer URL assignment must occur exactly once: {assignment}"
                )
        if "background: linear-gradient" in generated:
            raise AssertionError("shell must not use a decorative gradient background")
        if "samples.splice(0" in generated:
            raise AssertionError("FPS telemetry must not shift an array on every frame")
        if generated.find('data-gaius-storage-profile="v2"') > generated.find(
            "(function installPersistentFsBootstrap() {"
        ):
            raise AssertionError("storage profile globals execute after persistence bootstrap")

        check_inline_script_syntax(generated, directory)

        before_second_run = generated
        second = subprocess.run(
            [
                sys.executable,
                str(POSTPROCESS),
                str(index),
                str(classes),
                "26.2",
                "32",
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        if second.returncode != 0:
            raise AssertionError(f"second postprocess failed:\n{second.stdout}\n{second.stderr}")
        after_second_run = index.read_text(encoding="utf-8")
        if before_second_run != after_second_run:
            raise AssertionError("postprocess is not idempotent")
        if "Index already patched" not in second.stdout:
            raise AssertionError("second postprocess did not report an idempotent result")
        # build-pages-site.py and the release gates match the page's tags byte-for-byte, so the
        # page is LF on every host, and a CRLF page from an earlier run is rewritten as LF.
        if b"\r" in index.read_bytes():
            raise AssertionError("postprocess wrote CRLF line endings")
        index.write_bytes(index.read_bytes().replace(b"\n", b"\r\n"))
        third = subprocess.run(
            [sys.executable, str(POSTPROCESS), str(index), str(classes), "26.2", "32"],
            text=True,
            capture_output=True,
            check=False,
        )
        if third.returncode != 0:
            raise AssertionError(f"CRLF postprocess failed:\n{third.stdout}\n{third.stderr}")
        if index.read_bytes() != before_second_run.encode("utf-8"):
            raise AssertionError("a CRLF page is not rewritten to the same LF page")

    print("Gaius browser-client shell smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
