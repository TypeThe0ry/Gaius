#!/usr/bin/env python3
"""Fast regression tests for atomic portable HTML publication."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("build-portable-html.py")
SPEC = importlib.util.spec_from_file_location("build_portable_html", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)


class BuildPortableHTMLTest(unittest.TestCase):
    def test_launcher_keeps_embedded_portable_worker_urls(self) -> None:
        template = (SCRIPT.parent.parent / "web" / "launcher" / "index.template.html").read_text(
            encoding="utf-8"
        )
        self.assertIn("if (window.__gaiusPortableBuild !== true)", template)
        self.assertIn("file:/// sibling paths", template)
        self.assertIn("if (window.__gaiusPortableBuild === true) await portableReady;", template)

    def test_portable_start_transfers_a_copy_of_the_server_gzip_buffer(self) -> None:
        # The gzip promise resolves to one ArrayBuffer for the page's lifetime;
        # transferring it to the first Worker detaches it, so every start must
        # transfer a fresh copy or re-entering a world without a reload fails.
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            "const transferBuffer = buffer instanceof ArrayBuffer ? buffer.slice(0) : buffer;",
            script,
        )
        self.assertIn("serverScriptGzipData: transferBuffer,", script)
        self.assertIn(
            "if (transferBuffer instanceof ArrayBuffer) transferList.push(transferBuffer);",
            script,
        )
        self.assertNotIn("transferList.push(buffer)", script)
        self.assertNotIn("serverScriptGzipData: buffer,", script)

    def test_b7_round_trip_and_html_safety(self) -> None:
        samples = [b"", b"\x00", bytes(range(256)), b"<\r\n\x00" * 999, os.urandom(70_001)]
        for data in samples:
            text = PORTABLE.encode_b7(data)
            self.assertEqual(len(text) % 8, 0)
            # Nothing the HTML parser alters (NUL, CR), that could close the element ("<") or
            # that newline translation could rewrite (LF).
            self.assertFalse({"\x00", "\r", "\n", "<"} & set(text))
            self.assertLessEqual(max(map(ord, text), default=0), 0x83)
            self.assertEqual(PORTABLE.decode_b7(text, len(data)), data)

    def test_payload_parts_split_on_block_boundaries(self) -> None:
        data = os.urandom(50_000)
        payload = PORTABLE.PortablePayload()
        with mock.patch.object(PORTABLE, "B7_PART_CHARS", 8 * 1000):
            payload.add("blob", data, gzipped=False)
        self.assertEqual(payload.assets["blob"]["parts"], len(payload.elements))
        self.assertGreater(len(payload.elements), 1)
        html = payload.html() + "const portablePayload = " + json.dumps(payload.index()) + ";\n"
        self.assertEqual(PORTABLE.parse_portable_payload(html)["blob"], data)
        with self.assertRaises(RuntimeError):
            payload.add("blob", b"again", gzipped=False)

    def test_page_decoder_matches_reference(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        source = PORTABLE.PORTABLE_BOOTSTRAP
        start = source.index("      function gaiusPortableDecoder(scope) {")
        end = source.index("\n      const decoder = (() => {", start)
        decoder = source[start:end]
        raw = os.urandom(123_457) + bytes(range(256)) * 3
        compressed = gzip.compress(raw, mtime=0)
        script = decoder + r"""
const fs = require("fs");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const scope = {postMessage: (message) => scope.results.push(message), results: []};
gaiusPortableDecoder(scope);
let id = 0;
function run(text, bytes, gunzip) {
  id++;
  scope.onmessage({data: {type: "begin", id, bytes, gunzip, blobType: null}});
  for (let i = 0; i < text.length; i += 8 * 1024) {
    scope.onmessage({data: {type: "part", id, text: text.slice(i, i + 8 * 1024)}});
  }
  scope.onmessage({data: {type: "end", id}});
}
run(input.plain, input.plainBytes, false);
run(input.gzip, input.gzipBytes, true);
setTimeout(() => {
  const crypto = require("crypto");
  const hashes = scope.results.map((message) => message.type === "done"
    ? crypto.createHash("sha256").update(Buffer.from(message.value)).digest("hex") : message.message);
  process.stdout.write(JSON.stringify(hashes));
}, 500);
"""
        with tempfile.TemporaryDirectory() as directory:
            script_path = Path(directory) / "decoder.js"
            script_path.write_text(script, encoding="utf-8")
            stdin = json.dumps({
                "plain": PORTABLE.encode_b7(raw), "plainBytes": len(raw),
                "gzip": PORTABLE.encode_b7(compressed), "gzipBytes": len(compressed),
            })
            result = subprocess.run([node, str(script_path)], input=stdin, text=True,
                                    capture_output=True, check=True, timeout=60)
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual(json.loads(result.stdout), [digest, digest])

    def test_runtime_bootstrap_inlines_boot_modules(self) -> None:
        root = SCRIPT.resolve().parents[2]
        inline, hashes = PORTABLE.runtime_bootstrap(root)
        self.assertIn("window.__gaiusBootPortable = true;", inline)
        self.assertIn('id="gaius-kernel-worker-source"', inline)
        self.assertIn('<script data-gaius-boot-module="kernels/kernel-runtime.js">', inline)
        self.assertIn("gaius-boot.js", hashes)
        # The boot script runs last, after every module it would otherwise load.
        self.assertGreater(inline.rindex("__gaiusBoot ="), inline.rindex("data-gaius-boot-module"))
        template = (root / "port" / "web" / "launcher" / "index.template.html").read_text(encoding="utf-8")
        self.assertEqual(template.count(PORTABLE.BOOT_TAG), 1)

    def test_atomic_write_replaces_complete_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Gaius.html"
            target.write_text("old release", encoding="utf-8")

            PORTABLE.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "new release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_replace_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Gaius.html"
            target.write_text("old release", encoding="utf-8")

            with mock.patch.object(PORTABLE.os, "replace", side_effect=OSError("ENOSPC")):
                with self.assertRaises(OSError):
                    PORTABLE.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "old release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
