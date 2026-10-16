#!/usr/bin/env python3
"""Fast fixture tests for the multi-file GitHub Pages layout (build-pages-site.py)."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import re
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("build-pages-site.py")
SPEC = importlib.util.spec_from_file_location("build_pages_site", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
SITE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SITE)

INDEX = """<!doctype html>
<html><head>
  <script data-gaius-shader-toolchain data-profile="26.3" src="gaius-shader-toolchain.js?v=00112233aabbccdd"></script>
  <script data-gaius-boot="v1" src="gaius-boot.js"></script>
</head><body>
  <script data-gaius-storage-profile="v2">
    window.__gaiusProfileId = "26.3";
  </script>
</body></html>
"""


def fixture_pack() -> bytes:
    return gzip.compress(SITE.write_pack([
        ("assets/minecraft/textures/block/stone.png", b"png" * 10),
        ("assets/minecraft/sounds/random/click.ogg", b"click"),
        ("assets/minecraft/sounds/music/menu/menu1.ogg", b"music" * 100),
        ("assets/minecraft/sounds/mob/cow/say1.ogg", b"moo"),
        ("assets/minecraft/sounds.json", b"{}"),
    ]), mtime=0)


def make_dist(directory: Path) -> Path:
    dist = directory / "dist"
    (dist / "kernels").mkdir(parents=True)
    (dist / "index.html").write_bytes(INDEX.encode("utf-8"))
    for name, body in (
        ("classes.js", b"var main = 1;"), ("singleplayer-server.js", b"var main = 2;"),
        ("singleplayer-server-worker.js", b"self.onmessage = null;"), ("gaius-hotpath.wasm", b"\0asm\1\0\0\0"),
        ("relay-nodes.json", b'{"nodes": []}'), ("gaius-shader-toolchain.js", b"// loader"),
        ("gaius-shaderc.js", b"// shaderc"), ("gaius-shaderc.wasm", b"\0asm shaderc"),
        ("gaius-spvc.js", b"// spvc"), ("gaius-spvc.wasm", b"\0asm spvc"),
    ):
        (dist / name).write_bytes(body)
    for name in ("classes.js", "singleplayer-server.js"):
        (dist / f"{name}.gz").write_bytes(gzip.compress((dist / name).read_bytes(), mtime=0))
    (dist / "vanilla-assets.pack.gz").write_bytes(fixture_pack())
    simd, baseline = b"\0asm simd", b"\0asm baseline"
    (dist / "kernels" / "mesh.simd.wasm").write_bytes(simd)
    (dist / "kernels" / "mesh.baseline.wasm").write_bytes(baseline)
    (dist / "kernels" / "kernels.json").write_text(json.dumps({"schema": 1, "kernels": {"mesh": {
        "kinds": ["mesh_section"], "variants": {
            "simd": {"file": "mesh.simd.wasm", "sha256": hashlib.sha256(simd).hexdigest()},
            "baseline": {"file": "mesh.baseline.wasm", "sha256": hashlib.sha256(baseline).hexdigest()},
        }}}}), encoding="utf-8")
    return dist


class BuildPagesSiteTest(unittest.TestCase):
    def test_site_layout_hashes_assets_and_wires_the_page(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            report = SITE.build(dist, root / "site", root / "site.tar.gz", True)
            site = root / "site"
            descriptor = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
            assets = descriptor["assets"]
            self.assertEqual(report["profile"], "26.3")
            for logical in ("classes.js", "classes.js.gz", "singleplayer-server.js", "gaius-boot.js",
                            "kernels/kernel-runtime.js", "kernels/kernel-worker.js", "vanilla-sounds.pack"):
                url = assets[logical]
                self.assertRegex(url, r"\.[0-9a-f]{16}\.[A-Za-z0-9]+$", logical)
                self.assertTrue((site / url).is_file(), url)
            self.assertEqual((site / assets["classes.js"]).read_bytes(), b"var main = 1;")
            self.assertTrue((site / "gaius-sw.js").is_file(), "the Service Worker keeps a stable name")
            self.assertTrue((site / "relay-nodes.json").is_file())
            self.assertNotIn("kernels/kernel-pool.js", assets)
            # Kernels: both builds, hashed, with their kinds.
            mesh = descriptor["kernels"]["mesh"]
            self.assertEqual(mesh["kinds"], ["mesh_section"])
            self.assertEqual((site / mesh["variants"]["simd"]).read_bytes(), b"\0asm simd")
            # The page: site descriptor before the hashed boot tag, hashed toolchain loader.
            index = (site / "index.html").read_text(encoding="utf-8")
            self.assertIn('<script data-gaius-boot="v1" src="' + assets["gaius-boot.js"] + '"></script>', index)
            self.assertLess(index.index("window.__gaiusSite = "), index.index(assets["gaius-boot.js"]))
            self.assertNotIn('src="gaius-boot.js"', index)
            self.assertIn('src="' + assets["gaius-shader-toolchain.js"] + '"', index)
            urls = json.loads(re.search(r"window\.__gaiusShaderToolchainUrls = (\{.*?\});", index).group(1))
            self.assertEqual(urls["version"], "00112233aabbccdd")
            self.assertEqual(urls["spvcWasm"], assets["gaius-spvc.wasm"])
            # Sounds: interface sounds stay in the core pack, the rest streams.
            core_index, _payload = SITE.read_pack(gzip.decompress((site / assets["vanilla-assets.pack.gz"]).read_bytes()))
            sound_index, sound_payload = SITE.read_pack((site / assets["vanilla-sounds.pack"]).read_bytes())
            self.assertIn("assets/minecraft/sounds/random/click.ogg", core_index)
            self.assertIn("assets/minecraft/sounds.json", core_index)
            self.assertEqual(sorted(sound_index), ["assets/minecraft/sounds/mob/cow/say1.ogg",
                                                   "assets/minecraft/sounds/music/menu/menu1.ogg"])
            offset, length = sound_index["assets/minecraft/sounds/mob/cow/say1.ogg"]
            self.assertEqual(sound_payload[offset:offset + length], b"moo")
            self.assertEqual(report["sounds"], 2)
            # The archive holds exactly the site's files, as plain relative files.
            with tarfile.open(root / "site.tar.gz") as archive:
                members = archive.getmembers()
                self.assertTrue(all(member.isfile() and not member.name.startswith("/") for member in members))
                self.assertEqual(sorted(member.name for member in members),
                                 sorted(path.relative_to(site).as_posix() for path in site.rglob("*") if path.is_file()))

    def test_archive_is_deterministic(self) -> None:
        digests = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                SITE.build(make_dist(root), root / "site", root / "site.tar.gz", True)
                digests.append(hashlib.sha256((root / "site.tar.gz").read_bytes()).hexdigest())
        self.assertEqual(digests[0], digests[1])

    def test_page_without_boot_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(
                INDEX.replace('  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n', "").encode("utf-8"))
            with self.assertRaises(RuntimeError):
                SITE.build(dist, root / "site", None, True)

    def test_crlf_page_is_wired_like_an_lf_page(self) -> None:
        # postprocess-index-html.py on a Windows host once wrote dist/index.html with CRLF.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(INDEX.replace("\n", "\r\n").encode("utf-8"))
            SITE.build(dist, root / "site", None, True)
            assets = json.loads((root / "site" / "gaius-site.json").read_text(encoding="utf-8"))["assets"]
            index = (root / "site" / "index.html").read_bytes().decode("utf-8")
            self.assertNotIn("\r", index)
            self.assertIn('<script data-gaius-boot="v1" src="' + assets["gaius-boot.js"] + '"></script>', index)
            self.assertIn('src="' + assets["gaius-shader-toolchain.js"] + '"', index)
            self.assertNotIn("gaius-shader-toolchain.js?v=", index)

    def test_unrecognised_toolchain_loader_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(
                INDEX.replace('data-profile="26.3" src=', 'data-profile="26.3" defer src=').encode("utf-8"))
            with self.assertRaises(RuntimeError):
                SITE.build(dist, root / "site", None, True)

    def test_hashed_names(self) -> None:
        self.assertRegex(SITE.hashed_name("classes.js", b"x"), r"^classes\.[0-9a-f]{16}\.js$")
        self.assertRegex(SITE.hashed_name("vanilla-assets.pack.gz", b"x"), r"^vanilla-assets\.pack\.[0-9a-f]{16}\.gz$")
        self.assertRegex(SITE.hashed_name("kernels/mesh.simd.wasm", b"x"), r"^kernels/mesh\.simd\.[0-9a-f]{16}\.wasm$")


if __name__ == "__main__":
    unittest.main()
