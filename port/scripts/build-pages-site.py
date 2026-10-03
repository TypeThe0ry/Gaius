#!/usr/bin/env python3
"""Build the multi-file GitHub Pages layout of one Gaius profile from its dist directory.

    build-pages-site.py <dist> <output-dir> [--archive Gaius-site-<profile>.tar.gz]
                        [--no-split-sounds]

The portable Gaius.html stays the offline download. The site is what players open online:

* every asset gets a content-hashed name (classes.<sha256/16>.js, kernels/mesh.simd.<...>.wasm)
  so it never changes under its URL; gaius-sw.js (port/web/sw) caches those forever and
  Chrome keeps V8 code caches for them, so a warm boot skips download and compilation;
* index.html keeps its name and carries window.__gaiusSite (the logical-name -> URL map the
  boot script port/web/boot/gaius-boot.js resolves) right before the boot script tag;
* the client script is loaded from a plain URL (streaming parse and code cache instead of a
  gunzipped Blob); .gz copies of the two big scripts let a browser fall back when the host does
  not compress them on the wire;
* the singleplayer server is fetched only when a world opens (prefetched while the title
  screen idles), and the vanilla pack is split into a core pack and a sounds pack that streams
  in while the client starts;
* the wasm kernels ship in both builds (simd128 and baseline, see
  port/native/build-wasm-variants.sh); the page picks one per kernel by feature detection.

The archive is deterministic (sorted entries, fixed mtime/owner/mode, gzip mtime 0), so its
sha256 can be recorded in the release SHA256SUMS and checked by .github/workflows/pages.yml.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import shutil
import struct
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "port" / "web"
SITE_SCHEMA = 1
HASH_LENGTH = 16
VANILLA_MAGIC = b"GAIUSVP1"
BOOT_TAG = '  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n'
SHADER_TOOLCHAIN_TAG = re.compile(
    r'  <script data-gaius-shader-toolchain data-profile="([^"]*)" '
    r'src="gaius-shader-toolchain\.js\?v=([0-9a-f]+)"></script>\n'
)
SHADER_TOOLCHAIN_MODULES = (
    ("shadercJs", "gaius-shaderc.js"),
    ("shadercWasm", "gaius-shaderc.wasm"),
    ("spvcJs", "gaius-spvc.js"),
    ("spvcWasm", "gaius-spvc.wasm"),
)
QUALITY_MODULES = (
    "gpu-caps.js", "quality-profile.js", "gl-pass.js", "upscaler.js", "post-chain.js",
    "quality-runtime.js",
)
# Small interface sounds stay in the core pack: the title screen clicks before the sound pack
# has arrived on a slow line.
CORE_SOUND_PREFIXES = ("assets/minecraft/sounds/random/", "assets/minecraft/sounds/ui/")
ARCHIVE_MTIME = 315532800  # 1980-01-01, the zip epoch: stable and accepted everywhere


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def hashed_name(logical: str, data: bytes) -> str:
    """classes.js -> classes.<hash>.js; vanilla-assets.pack.gz -> vanilla-assets.pack.<hash>.gz."""
    path = Path(logical)
    digest = sha256_bytes(data)[:HASH_LENGTH]
    stem, dot, extension = path.name.rpartition(".")
    name = f"{stem}.{digest}.{extension}" if dot else f"{path.name}.{digest}"
    return (path.parent / name).as_posix() if path.parent != Path(".") else name


def deterministic_gzip(data: bytes) -> bytes:
    return gzip.compress(data, compresslevel=9, mtime=0)


def read_pack(data: bytes) -> tuple[dict[str, list[int]], bytes]:
    if data[:8] != VANILLA_MAGIC or len(data) < 12:
        raise RuntimeError("vanilla asset pack has no GAIUSVP1 header")
    index_length = struct.unpack("<I", data[8:12])[0]
    index = json.loads(data[12:12 + index_length].decode("utf-8"))
    return index, data[12 + index_length:]


def write_pack(entries: list[tuple[str, bytes]]) -> bytes:
    index: dict[str, list[int]] = {}
    payload = io.BytesIO()
    for name, body in entries:
        index[name] = [payload.tell(), len(body)]
        payload.write(body)
    index_bytes = json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return VANILLA_MAGIC + struct.pack("<I", len(index_bytes)) + index_bytes + payload.getvalue()


def split_vanilla_pack(compressed: bytes) -> tuple[bytes, bytes, int]:
    """Returns (core pack gzip, sounds pack raw, sound count)."""
    index, payload = read_pack(gzip.decompress(compressed))
    core: list[tuple[str, bytes]] = []
    sounds: list[tuple[str, bytes]] = []
    for name in sorted(index, key=lambda key: index[key][0]):
        offset, length = index[name]
        body = payload[offset:offset + length]
        if len(body) != length:
            raise RuntimeError(f"vanilla asset pack range is truncated: {name}")
        is_sound = name.startswith("assets/minecraft/sounds/") and name.endswith(".ogg")
        if is_sound and not name.startswith(CORE_SOUND_PREFIXES):
            sounds.append((name, body))
        else:
            core.append((name, body))
    # OGG is already compressed: the sounds pack ships raw and needs no inflate on the page.
    return deterministic_gzip(write_pack(core)), write_pack(sounds), len(sounds)


class Site:
    def __init__(self, output: Path) -> None:
        self.output = output
        self.assets: dict[str, str] = {}
        self.files: dict[str, bytes] = {}

    def add(self, logical: str, data: bytes, *, hashed: bool = True) -> str:
        url = hashed_name(logical, data) if hashed else logical
        if url in self.files and self.files[url] != data:
            raise RuntimeError(f"site path collision: {url}")
        self.files[url] = data
        if hashed:
            self.assets[logical] = url
        return url

    def write(self) -> None:
        if self.output.exists():
            shutil.rmtree(self.output)
        for url, data in sorted(self.files.items()):
            target = self.output / url
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def archive(self, path: Path) -> None:
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for url, data in sorted(self.files.items()):
                info = tarfile.TarInfo(url)
                info.size = len(data)
                info.mtime = ARCHIVE_MTIME
                info.mode = 0o644
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                archive.addfile(info, io.BytesIO(data))
        path.write_bytes(deterministic_gzip(buffer.getvalue()))


def require(path: Path) -> bytes:
    if not path.is_file() or path.stat().st_size == 0:
        raise FileNotFoundError(f"missing site input: {path}")
    return path.read_bytes()


def profile_of(index_html: str) -> str:
    match = re.search(r'window\.__gaiusProfileId = "([^"]+)";', index_html)
    if not match:
        raise RuntimeError("index.html has no __gaiusProfileId")
    return match.group(1)


def add_kernels(site: Site, dist: Path) -> dict[str, dict[str, object]]:
    manifest_path = dist / "kernels" / "kernels.json"
    if not manifest_path.is_file():
        return {}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    kernels: dict[str, dict[str, object]] = {}
    for name, entry in sorted((manifest.get("kernels") or {}).items()):
        variants: dict[str, str] = {}
        for variant in ("simd", "baseline"):
            spec = (entry.get("variants") or {}).get(variant)
            if not isinstance(spec, dict):
                continue
            data = require(dist / "kernels" / str(spec["file"]))
            if spec.get("sha256") not in (None, sha256_bytes(data)):
                raise RuntimeError(f"kernel {spec['file']} does not match kernels.json")
            variants[variant] = site.add(f"kernels/{spec['file']}", data)
        if variants:
            kernels[name] = {"kinds": list(entry.get("kinds") or []), "variants": variants}
            if isinstance(entry.get("memory"), dict):
                kernels[name]["memory"] = entry["memory"]
    return kernels


def build(dist: Path, output: Path, archive: Path | None, split_sounds: bool) -> dict[str, object]:
    dist = dist.resolve()
    index_path = dist / "index.html"
    # postprocess-index-html.py on Windows once wrote CRLF; the tags below are matched as LF.
    index_html = require(index_path).decode("utf-8").replace("\r\n", "\n")
    profile = profile_of(index_html)
    if BOOT_TAG not in index_html:
        raise RuntimeError("index.html has no gaius-boot.js tag (rebuild it from the current launcher template)")
    site = Site(output.resolve())

    for name in ("classes.js", "classes.js.gz", "singleplayer-server.js", "singleplayer-server.js.gz",
                 "singleplayer-server-worker.js", "gaius-hotpath.wasm"):
        site.add(name, require(dist / name))
    # The relay registry keeps its name: pages and tools fetch it by that name.
    if (dist / "relay-nodes.json").is_file():
        site.add("relay-nodes.json", (dist / "relay-nodes.json").read_bytes(), hashed=False)

    vanilla = require(dist / "vanilla-assets.pack.gz")
    sound_count = 0
    if split_sounds:
        core, sounds, sound_count = split_vanilla_pack(vanilla)
        site.add("vanilla-assets.pack.gz", core)
        if sound_count:
            site.add("vanilla-sounds.pack", sounds)
    else:
        site.add("vanilla-assets.pack.gz", vanilla)

    # Runtime modules: boot script, kernel runtime and job codecs, kernel worker, quality layer.
    site.add("gaius-boot.js", require(WEB / "boot" / "gaius-boot.js"))
    modules: list[str] = []
    for path in sorted((WEB / "kernels").glob("*.js")):
        if path.name in ("kernel-pool.js",):
            continue
        site.add(f"kernels/{path.name}", path.read_bytes())
        if path.name.endswith("-job.js"):
            modules.append(f"kernels/{path.name}")
    for name in QUALITY_MODULES:
        path = WEB / "runtime" / "quality" / name
        if path.is_file():
            site.add(f"runtime/quality/{name}", path.read_bytes())
    kernels = add_kernels(site, dist)
    # The Service Worker keeps a stable URL so the browser can update it.
    site.add("gaius-sw.js", require(WEB / "sw" / "gaius-sw.js"), hashed=False)

    site_globals: dict[str, object] = {}
    toolchain = SHADER_TOOLCHAIN_TAG.search(index_html)
    if toolchain:
        tag_profile, token = toolchain.groups()
        loader_url = site.add("gaius-shader-toolchain.js", require(dist / "gaius-shader-toolchain.js"))
        urls: dict[str, str] = {"version": token}
        for key, name in SHADER_TOOLCHAIN_MODULES:
            urls[key] = site.add(name, require(dist / name))
        site_globals["__gaiusShaderToolchainUrls"] = urls
        index_html = index_html.replace(
            toolchain.group(0),
            f'  <script data-gaius-shader-toolchain data-profile="{tag_profile}" src="{loader_url}"></script>\n',
            1,
        )
    elif 'src="gaius-shader-toolchain.js' in index_html:
        # An unrewritten loader reference would 404 on the site (only hashed copies ship).
        raise RuntimeError("index.html references gaius-shader-toolchain.js but its loader tag is not recognised")

    site_id = sha256_bytes(json.dumps(site.assets, sort_keys=True).encode("utf-8"))[:HASH_LENGTH]
    descriptor = {
        "schema": SITE_SCHEMA,
        "id": site_id,
        "profile": profile,
        "assets": site.assets,
        "modules": modules,
        "kernels": kernels,
        "serviceWorker": "gaius-sw.js",
    }
    lines = [f"    window.__gaiusSite = {json.dumps(descriptor, sort_keys=True, separators=(',', ':'))};\n"]
    for name, value in site_globals.items():
        lines.append(f"    window.{name} = {json.dumps(value, sort_keys=True, separators=(',', ':'))};\n")
    site_script = '  <script data-gaius-site="v1">\n' + "".join(lines) + "  </script>\n"
    boot_url = site.assets["gaius-boot.js"]
    index_html = index_html.replace(
        BOOT_TAG, site_script + f'  <script data-gaius-boot="v1" src="{boot_url}"></script>\n', 1
    )
    site.add("index.html", index_html.encode("utf-8"), hashed=False)
    site.add("gaius-site.json", (json.dumps(descriptor, indent=2, sort_keys=True) + "\n").encode("utf-8"),
             hashed=False)
    site.write()
    if archive is not None:
        site.archive(archive.resolve())
    total = sum(len(data) for data in site.files.values())
    return {"profile": profile, "id": site_id, "files": len(site.files), "bytes": total,
            "sounds": sound_count, "kernels": sorted(kernels)}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--no-split-sounds", action="store_true")
    args = parser.parse_args(argv)
    report = build(args.dist, args.output, args.archive, not args.no_split_sounds)
    print(f"Gaius site {report['profile']} ({report['id']}): {report['files']} files, "
          f"{report['bytes']} bytes, {report['sounds']} streamed sounds, kernels {report['kernels']}")
    if args.archive is not None:
        print(f"Gaius site archive: {args.archive} ({args.archive.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
