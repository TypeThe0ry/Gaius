#!/usr/bin/env python3
"""Fast regression tests for atomic portable HTML publication."""

from __future__ import annotations

import importlib.util
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
