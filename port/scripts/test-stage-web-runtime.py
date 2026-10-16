#!/usr/bin/env python3
"""The page runtime reaches every distribution form: dist staging, portable page and Pages site.

gaius-boot.js loads a fixed list of modules (kernel runtime, quality layer) and hands the server
Worker another (worldgen codec and facade). stage-web-runtime.py, build-portable-html.py and
build-pages-site.py each ship those files their own way; this test keeps the four lists in step
and stages a dist the way build-teavm.sh and build-version-release.sh do.
"""

from __future__ import annotations

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parents[1]
WEB = ROOT / "port" / "web"


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


STAGE = load("stage_web_runtime", SCRIPTS / "stage-web-runtime.py")
PORTABLE = load("build_portable_html", SCRIPTS / "build-portable-html.py")
SITE = load("build_pages_site", SCRIPTS / "build-pages-site.py")
BOOT = (WEB / "boot" / "gaius-boot.js").read_text(encoding="utf-8")
# Files the dist serves at its top level, by their source.
TOP_LEVEL_SOURCES = {
    "gaius-boot.js": WEB / "boot" / "gaius-boot.js",
    "gaius-sw.js": WEB / "sw" / "gaius-sw.js",
}


def boot_list(name: str) -> list[str]:
    match = re.search(r"const " + re.escape(name) + r" = \[(.*?)\];", BOOT, flags=re.DOTALL)
    if match is None:
        raise AssertionError(f"gaius-boot.js has no {name}")
    return re.findall(r'"([^"]+)"', match.group(1))


class WebRuntimeDistributionTest(unittest.TestCase):
    def test_boot_lists_name_real_files(self) -> None:
        for name in ("KERNEL_RUNTIME_SCRIPTS", "QUALITY_SCRIPTS", "SERVER_KERNEL_SCRIPTS"):
            for script in boot_list(name):
                self.assertTrue((WEB / script).is_file(), f"{name} names a missing file: {script}")
        self.assertTrue((WEB / "kernels" / "kernel-worker.js").is_file())
        self.assertTrue((WEB / "sw" / "gaius-sw.js").is_file())

    def test_portable_page_inlines_what_the_boot_script_loads(self) -> None:
        self.assertEqual(list(PORTABLE.KERNEL_RUNTIME_MODULES), boot_list("KERNEL_RUNTIME_SCRIPTS"))
        self.assertEqual(list(PORTABLE.QUALITY_MODULES), boot_list("QUALITY_SCRIPTS"))
        worker = set(PORTABLE.SERVER_WORKER_MODULES)
        self.assertTrue(set(boot_list("SERVER_KERNEL_SCRIPTS")) <= worker)
        self.assertTrue(set(boot_list("KERNEL_RUNTIME_SCRIPTS")) <= worker, "?worldgenKernelHost=worker")
        inline, hashes = PORTABLE.runtime_bootstrap(ROOT)
        for name in PORTABLE.kernel_job_modules(WEB):
            self.assertIn(f'<script data-gaius-boot-module="{name}">', inline)
            self.assertIn(name, hashes)

    def test_site_ships_the_quality_layer_in_boot_order(self) -> None:
        self.assertEqual([f"runtime/quality/{name}" for name in SITE.QUALITY_MODULES], boot_list("QUALITY_SCRIPTS"))

    def test_stage_copies_runtime_and_kernels(self) -> None:
        with tempfile.TemporaryDirectory(prefix="gaius-stage-") as temporary:
            directory = Path(temporary)
            kernels = directory / "kernels-out"
            kernels.mkdir()
            manifest = {"schema": 1, "kernels": {}}
            for name in ("light", "mesher", "noise", "worldgen"):
                variants = {}
                for variant in ("simd", "baseline"):
                    body = f"\0asm {name} {variant}".encode("ascii")
                    (kernels / f"{name}.{variant}.wasm").write_bytes(body)
                    variants[variant] = {"file": f"{name}.{variant}.wasm", "bytes": len(body)}
                manifest["kernels"][name] = {"crate": f"gaius-{name}-wasm", "kinds": [name], "variants": variants}
            (kernels / "kernels.json").write_text(json.dumps(manifest), encoding="utf-8")
            dist = directory / "dist"
            staged = STAGE.stage(dist, kernels)

            for name in ["gaius-boot.js", "gaius-sw.js", "kernels/kernel-worker.js"] + boot_list(
                "KERNEL_RUNTIME_SCRIPTS"
            ) + boot_list("QUALITY_SCRIPTS") + boot_list("SERVER_KERNEL_SCRIPTS"):
                self.assertIn(name, staged)
                source = TOP_LEVEL_SOURCES.get(name, WEB / name)
                self.assertEqual((dist / name).read_bytes(), source.read_bytes(), name)
            written = json.loads((dist / "kernels" / "kernels.json").read_text(encoding="utf-8"))
            self.assertEqual(sorted(written["kernels"]), ["light", "mesher", "noise", "worldgen"])
            # The dist boot path loads the job codecs listed here (gaius-boot.js kernelManifest).
            self.assertEqual(written["scripts"], PORTABLE.kernel_job_modules(WEB))
            for name in manifest["kernels"]:
                for variant in ("simd", "baseline"):
                    self.assertTrue((dist / "kernels" / f"{name}.{variant}.wasm").is_file())

            # A restage without kernels (GAIUS_SKIP_KERNELS=1 after build-teavm.sh cleared the
            # directory) leaves no kernel manifest behind, so the page keeps the vanilla paths.
            (dist / "kernels").rename(directory / "old-kernels")
            STAGE.stage(dist, None)
            self.assertFalse((dist / "kernels" / "kernels.json").exists())
            self.assertTrue((dist / "runtime" / "quality" / "quality-runtime.js").is_file())


if __name__ == "__main__":
    unittest.main()
