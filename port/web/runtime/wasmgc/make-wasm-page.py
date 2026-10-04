#!/usr/bin/env python3
"""Stages the WebAssembly GC client page next to classes.wasm (v0.4.0 M0 spike S1).

usage: make-wasm-page.py <javascript-dist> <wasm-dist> [--gzip-payload]

The page is the JavaScript build's launcher (index.html) with one configuration script at the
top of <head>: window.__gaiusClassesUrl points the launcher at gaius-wasm-boot.js instead of
classes.js, so storage, assets, the shader toolchain, kernels and the boot telemetry stay the
same, and only the client runtime differs.  The supporting files of the JavaScript dist are
copied (not classes.js, the portable page or the singleplayer Worker script, which the wasm
dist replaces once the Worker has a WebAssembly GC build).  The result is written as
index-wasm.html and index.html.

--gzip-payload also writes classes.wasm.gz.js (window.__gaiusWasmGzipBase64, the gzip+base64
module for file:// pages, design D13) and index-wasm-file.html, which loads it.
"""
import base64
import gzip
import shutil
import sys
from pathlib import Path

SKIP_PREFIXES = ("classes.js", "Gaius.html", "Gaius.manifest.json", "index.html")
CONFIG = (
    '<script data-gaius-runtime="wasm">'
    'window.__gaiusRuntime="wasm";'
    'window.__gaiusClassesUrl="gaius-wasm-boot.js";'
    '%s'
    '</script>'
)


def stage_page(js_index: Path, output: Path, extra: str) -> None:
    text = js_index.read_text(encoding="utf-8")
    marker = "<head>"
    index = text.find(marker)
    if index < 0:
        raise SystemExit("no <head> in " + str(js_index))
    index += len(marker)
    output.write_text(text[:index] + CONFIG % extra + text[index:], encoding="utf-8", newline="\n")


def main() -> int:
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    js_dist, wasm_dist = Path(args[0]), Path(args[1])
    wasm_dist.mkdir(parents=True, exist_ok=True)
    for entry in js_dist.iterdir():
        if entry.name.startswith(SKIP_PREFIXES):
            continue
        target = wasm_dist / entry.name
        if entry.is_dir():
            shutil.copytree(entry, target, dirs_exist_ok=True)
        elif not target.exists() or target.stat().st_mtime < entry.stat().st_mtime:
            shutil.copy2(entry, target)
    stage_page(js_dist / "index.html", wasm_dist / "index-wasm.html", "")
    stage_page(js_dist / "index.html", wasm_dist / "index.html", "")
    if "--gzip-payload" in sys.argv:
        module = (wasm_dist / "classes.wasm").read_bytes()
        payload = base64.b64encode(gzip.compress(module, compresslevel=9)).decode("ascii")
        (wasm_dist / "classes.wasm.gz.js").write_text(
            "window.__gaiusWasmGzipBase64=\"" + payload + "\";\n", encoding="ascii")
        stage_page(js_dist / "index.html", wasm_dist / "index-wasm-file.html",
                   '</script><script src="classes.wasm.gz.js"></script><script>')
    print("staged the WebAssembly GC page in " + str(wasm_dist))
    return 0


if __name__ == "__main__":
    sys.exit(main())
