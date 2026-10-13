#!/usr/bin/env python3
"""Prepare frozen-JS inputs and build the acceptance artifacts for a Wasm train."""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
READY_RE = re.compile(r"^(\S+) client \S*/classes\.wasm ")


def run(command: list[str], cwd: Path = ROOT) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def profiles_from_ready(train: Path) -> list[str]:
    ready = train / "READY.txt"
    if not ready.is_file():
        return []
    profiles = []
    for line in ready.read_text(encoding="utf-8").splitlines():
        match = READY_RE.match(line)
        if match and match.group(1) not in profiles:
            profiles.append(match.group(1))
    return profiles


def copy_tree(source: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for item in source.iterdir():
        destination = target / item.name
        if item.is_dir() and not item.is_symlink():
            shutil.copytree(item, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(item, destination)


def preserve_existing(path: Path) -> None:
    if not path.exists():
        return
    index = 1
    while path.with_name(path.name + f".previous-{index}").exists():
        index += 1
    path.rename(path.with_name(path.name + f".previous-{index}"))


def prepare_input(profile: str, frozen: Path | None, fallback: Path, output: Path) -> Path:
    preserve_existing(output)
    copy_tree(frozen if frozen is not None else fallback, output)
    shutil.copy2(ROOT / "port/web/launcher/index.template.html", output / "index.html")
    shutil.copy2(ROOT / "port/web/boot/gaius-boot.js", output / "gaius-boot.js")
    shutil.copy2(ROOT / "port/web/sw/gaius-sw.js", output / "gaius-sw.js")
    classes = output / "classes.js"
    if not classes.is_file():
        classes = output / "classes.wasm"
        if not classes.is_file():
            raise RuntimeError(f"{profile}: frozen input has no classes.js/classes.wasm")
    if not classes.is_file():
        raise RuntimeError(f"{profile}: frozen input has no classes.js")
    run([sys.executable, str(ROOT / "port/scripts/postprocess-index-html.py"),
         str(output / "index.html"), str(classes), profile])
    return output


def wasm_paths(train: Path, profile: str) -> tuple[Path, Path]:
    client = train / "dist" / f"wasm-{profile}-client"
    server = train / "dist" / f"wasm-{profile}-server"
    if not (client / "classes.wasm").is_file():
        raise RuntimeError(f"{profile}: client artifact is not ready")
    ready_lines = (train / "READY.txt").read_text(encoding="utf-8").splitlines()
    if not any(line.startswith(profile + " server ") for line in ready_lines):
        raise RuntimeError(f"{profile}: server artifact is not ready (no READY.txt server gate)")
    if not (server / "singleplayer-server.wasm").is_file():
        raise RuntimeError(f"{profile}: server artifact is not ready")
    return client, server


def stage_profile(train: Path, profile: str, frozen: Path | None, staged: Path,
                  scratch: Path, allow_mixed_provenance: bool = False) -> dict[str, object]:
    client, server = wasm_paths(train, profile)
    input_dir = prepare_input(profile, frozen, client, scratch / profile)
    site = staged / f"site-{profile}"
    acceptance = staged / f"acceptance-{profile}"
    preserve_existing(site)
    preserve_existing(acceptance)
    site_policy = ["--no-js-fallback"] if frozen is None else []
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(site),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), *site_policy])
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(acceptance),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), "--no-js-gzip",
         "--no-js-fallback"])
    portable = staged / f"portable-{profile}" / f"Gaius-{profile}.html"
    run([sys.executable, str(ROOT / "port/scripts/build-portable-html.py"), str(input_dir), str(portable),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), "--frozen-js",
         "--no-js-fallback", "--site-shell", str(acceptance),
         *(["--allow-mixed-provenance"] if allow_mixed_provenance else [])])
    return {"profile": profile, "site": str(site), "acceptance": str(acceptance), "portable": str(portable),
            "mixedProvenance": allow_mixed_provenance}


def check_total(staged: Path, profiles: list[str]) -> None:
    sites = [str(staged / f"site-{profile}") for profile in profiles]
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), "--check-total", *sites])


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("train", type=Path)
    parser.add_argument("--frozen-root", type=Path, required=True,
                        help="directory containing one frozen JS dist per profile")
    parser.add_argument("--frozen", action="append", default=[], metavar="PROFILE=DIR",
                        help="override a profile's frozen JS dist; may be repeated")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--profile", action="append", default=[])
    parser.add_argument("--allow-mixed-provenance", action="store_true",
                        help="accept a client and server built from different commits (test trains only)")
    args = parser.parse_args(argv)
    train = args.train.resolve()
    staged = (args.output or train / "staged").resolve()
    staged.mkdir(parents=True, exist_ok=True)
    scratch = (args.scratch or staged / "inputs").resolve()
    results = []
    pending = []
    overrides = {}
    for item in args.frozen:
        profile, separator, directory = item.partition("=")
        if not separator or not profile or not directory:
            parser.error("--frozen must be PROFILE=DIR")
        overrides[profile] = Path(directory).resolve()
    for profile in profiles_from_ready(train):
        if args.profile and profile not in args.profile:
            continue
        frozen = overrides.get(profile, args.frozen_root.resolve() / profile)
        if not frozen.is_dir() and not (train / "dist" / f"wasm-{profile}-client").is_dir():
            pending.append(f"{profile}: frozen JS and Wasm client inputs are missing")
            continue
        if frozen.is_dir() and not ((frozen / "classes.js").is_file() or (frozen / "classes.wasm").is_file()):
            frozen = None
        if frozen is None or not frozen.is_dir():
            frozen = None
        try:
            results.append(stage_profile(train, profile, frozen, staged, scratch, args.allow_mixed_provenance))
        except RuntimeError as error:
            pending.append(str(error))
    receipt_path = staged / "STAGED.txt"
    previous = json.loads(receipt_path.read_text(encoding="utf-8")) if receipt_path.is_file() else {}
    updated = {str(item["profile"]): item for item in previous.get("profiles", [])}
    updated.update({str(item["profile"]): item for item in results})
    results = list(updated.values())
    if results:
        check_total(staged, list(updated))
    receipt = {"profiles": results, "pending": pending}
    (staged / "STAGED.txt").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    return 0 if not pending else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
