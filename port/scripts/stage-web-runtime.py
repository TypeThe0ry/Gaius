#!/usr/bin/env python3
"""Stage the browser runtime next to a profile's dist index.html.

    stage-web-runtime.py <dist> [--kernels DIR]

Copies, unhashed, what the launcher's gaius-boot.js tag loads in a plain dist directory (the
local static-host deployment input and the source of the portable page):

    gaius-boot.js                 port/web/boot
    gaius-sw.js                   port/web/sw (registered only by the hashed site layout)
    kernels/*.js                  port/web/kernels (runtime, policies, worker, job codecs)
    runtime/quality/*.js          port/web/runtime/quality (graphics quality layer)
    kernels/*.wasm, kernels.json  from --kernels (port/native/build-wasm-variants.sh output)

and records the kernel job codecs in kernels/kernels.json ("scripts") so the boot script loads
them. Missing optional inputs are skipped: the page then keeps its vanilla paths.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "port" / "web"


def copy(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def stage(dist: Path, kernels: Path | None) -> list[str]:
    dist.mkdir(parents=True, exist_ok=True)
    staged: list[str] = []
    for source, name in ((WEB / "boot" / "gaius-boot.js", "gaius-boot.js"),
                         (WEB / "sw" / "gaius-sw.js", "gaius-sw.js")):
        copy(source, dist / name)
        staged.append(name)
    scripts: list[str] = []
    for source in sorted((WEB / "kernels").glob("*.js")):
        copy(source, dist / "kernels" / source.name)
        staged.append(f"kernels/{source.name}")
        if source.name.endswith("-job.js"):
            scripts.append(f"kernels/{source.name}")
    quality = WEB / "runtime" / "quality"
    if quality.is_dir():
        for source in sorted(quality.glob("*.js")):
            copy(source, dist / "runtime" / "quality" / source.name)
            staged.append(f"runtime/quality/{source.name}")
    if kernels is not None and (kernels / "kernels.json").is_file():
        for source in sorted(kernels.glob("*.wasm")):
            copy(source, dist / "kernels" / source.name)
            staged.append(f"kernels/{source.name}")
        copy(kernels / "kernels.json", dist / "kernels" / "kernels.json")
    manifest_path = dist / "kernels" / "kernels.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["scripts"] = scripts
        manifest_path.write_bytes((json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
        staged.append("kernels/kernels.json")
    return staged


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", type=Path)
    parser.add_argument("--kernels", type=Path)
    args = parser.parse_args(argv)
    staged = stage(args.dist.resolve(), args.kernels.resolve() if args.kernels else None)
    print(f"Staged {len(staged)} runtime files into {args.dist}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
