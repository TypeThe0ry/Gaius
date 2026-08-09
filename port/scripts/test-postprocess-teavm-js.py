#!/usr/bin/env python3
"""Fast regression tests for the TeaVM JavaScript postprocessor."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("postprocess-teavm-js.py")
SPEC = importlib.util.spec_from_file_location("postprocess_teavm_js", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
POSTPROCESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POSTPROCESS)


TEAVM_LONG_HELPER = (
    '"use strict";'
    "let Bi=BigInt(0),P3=val=>BigInt.asIntN(64,"
    "BigInt(val>=0?Math.floor(val):Math.ceil(val)));"
)


def run_postprocess(target: Path) -> int:
    with contextlib.redirect_stdout(io.StringIO()):
        return POSTPROCESS.main([str(SCRIPT), str(target)])


class PostprocessTeaVMJSTest(unittest.TestCase):
    def test_real_teavm_helper_is_patched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text(TEAVM_LONG_HELPER, encoding="utf-8")

            self.assertEqual(run_postprocess(target), 0)
            result = target.read_text(encoding="utf-8")

            self.assertIn(POSTPROCESS.PATCH_MARKER, result)
            self.assertIn("!Number.isFinite(val)?", result)
            self.assertNotIn(
                "BigInt.asIntN(64,BigInt(val>=0?Math.floor(val):Math.ceil(val)))",
                result,
            )

            self.assertEqual(run_postprocess(target), 0)
            self.assertEqual(target.read_text(encoding="utf-8"), result)

    def test_atomic_write_replaces_complete_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            target.write_text("old release", encoding="utf-8")

            POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "new release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_replace_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            original = "old release"
            target.write_text(original, encoding="utf-8")

            with mock.patch.object(
                POSTPROCESS.os,
                "replace",
                side_effect=OSError("ENOSPC"),
            ):
                with self.assertRaises(OSError):
                    POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), original)
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_fsync_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "classes.js"
            original = "old release"
            target.write_text(original, encoding="utf-8")

            with mock.patch.object(
                POSTPROCESS.os,
                "fsync",
                side_effect=OSError("ENOSPC"),
            ):
                with self.assertRaises(OSError):
                    POSTPROCESS.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), original)
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
