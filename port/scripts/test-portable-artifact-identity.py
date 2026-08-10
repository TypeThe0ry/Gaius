#!/usr/bin/env python3
"""Fixture tests for portable artifact identity and publication safety."""

from __future__ import annotations

import contextlib
import gzip
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("build-portable-html.py")
SPEC = importlib.util.spec_from_file_location("build_portable_html", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)

QUICK_CHECK_SCRIPT = Path(__file__).with_name("quick-check.py")
QUICK_CHECK_SPEC = importlib.util.spec_from_file_location(
    "quick_check", QUICK_CHECK_SCRIPT
)
assert QUICK_CHECK_SPEC is not None and QUICK_CHECK_SPEC.loader is not None
QUICK_CHECK = importlib.util.module_from_spec(QUICK_CHECK_SPEC)
QUICK_CHECK_SPEC.loader.exec_module(QUICK_CHECK)


def compressed(value: bytes) -> bytes:
    return gzip.compress(value, mtime=0)


class PortableArtifactIdentityTest(unittest.TestCase):
    def make_fixture(
        self,
        directory: str,
        *,
        version: str = "26.2",
        distribution: str = "named",
        profile_file: str | None = None,
        profile_id: str | None = None,
        launcher_version: str | None = None,
        launcher_asset_index: str | None = None,
        classes: bytes | None = None,
        classes_gzip: bytes | None = None,
    ) -> tuple[Path, Path, Path]:
        root = Path(directory) / "project"
        dist = root / "port" / "web" / "dist"
        (root / "port" / "versions").mkdir(parents=True)
        dist.mkdir(parents=True)

        profile_file = profile_file or f"{version}.json"
        profile_id = profile_id or version
        launcher_version = launcher_version or version
        launcher_asset_index = launcher_asset_index or ("32" if version == "26.2" else "29")
        (root / "port" / "config.json").write_text(
            json.dumps(
                {
                    "versionProfile": f"versions/{profile_file}",
                    "teaVMVersion": "0.15.0",
                }
            ),
            encoding="utf-8",
        )
        (root / "port" / "versions" / profile_file).write_text(
            json.dumps(
                {
                    "id": profile_id,
                    "releaseType": "release",
                    "clientDistribution": distribution,
                    "protocolVersion": 776 if version == "26.2" else 774,
                    "official": {"assetIndexId": launcher_asset_index},
                }
            ),
            encoding="utf-8",
        )

        index = (
            "<!doctype html>\n"
            f'<script>const args = ["--version", "{launcher_version}", '
            f'"--assetIndex", "{launcher_asset_index}"];</script>\n'
            '  <script>\n    if (typeof Error === "function") {}\n  </script>\n'
        )
        (dist / "index.html").write_text(index, encoding="utf-8")

        classes = classes or (
            b'"use strict";'
            b"/*gaius-java-finite-long-cast*/"
            b"target-attestation;262-startup"
        )
        (dist / "classes.js").write_bytes(classes)
        (dist / "classes.js.gz").write_bytes(
            classes_gzip if classes_gzip is not None else compressed(classes)
        )
        server = b"/*gaius-integrated-server-input-coroutine*/server-startup"
        (dist / "singleplayer-server.js").write_bytes(server)
        (dist / "singleplayer-server.js.gz").write_bytes(compressed(server))
        (dist / "gaius-hotpath.wasm").write_bytes(b"wasm")
        (dist / "gaius-hotpath.wasm.gz").write_bytes(compressed(b"wasm"))
        (dist / "vanilla-assets.pack.gz").write_bytes(compressed(b"vanilla"))
        (dist / "singleplayer-server-worker.js").write_text(
            "self.onmessage = () => {};\n",
            encoding="utf-8",
        )
        (dist / "relay-nodes.json").write_text(
            json.dumps(
                {"kind": "gaius-relay-registry", "protocolVersion": 1, "nodes": []}
            ),
            encoding="utf-8",
        )
        source_bootstrap = root / "port" / "web" / "singleplayer" / "server-worker-bootstrap.js"
        source_bootstrap.parent.mkdir(parents=True)
        source_bootstrap.write_text("self.__gaiusBootstrap = true;\n", encoding="utf-8")
        source_file = (
            root
            / "port"
            / "src"
            / "main"
            / "java"
            / "dev"
            / "gaius"
            / "browser"
            / "Fixture.java"
        )
        source_file.parent.mkdir(parents=True)
        source_file.write_text("final class Fixture {}\n", encoding="utf-8")
        work = root / "port" / "work"
        version_work = work / version
        overlays = work / "overlays"
        version_work.mkdir(parents=True)
        (version_work / "version.json").write_text(
            json.dumps(
                {
                    "id": version,
                    "libraries": [
                        {
                            "name": "example:browser-library:1.0",
                            "downloads": {
                                "artifact": {"path": "example/browser-library-1.0.jar"}
                            },
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        overlay_files = {
            overlays / f"client-named-{version}-gaius.jar": b"client-overlay",
            overlays / "teavm-classlib-0.15.0-gaius.jar": b"classlib-overlay",
            overlays / "teavm-core-0.15.0-gaius.jar": b"core-overlay",
            overlays / "libraries" / "example" / "browser-library-1.0.jar": b"library-overlay",
        }
        for overlay, value in overlay_files.items():
            overlay.parent.mkdir(parents=True, exist_ok=True)
            overlay.write_bytes(value)
        for artifact_name, role in (
            ("classes.js", "client"),
            ("singleplayer-server.js", "singleplayer-worker"),
            ("gaius-hotpath.wasm", "wasm-hotpath"),
            ("singleplayer-server-worker.js", "worker-bootstrap"),
            ("vanilla-assets.pack.gz", "vanilla-assets"),
            ("relay-nodes.json", "relay-registry"),
        ):
            PORTABLE.build_identity.write_sidecar(
                root,
                role,
                dist / artifact_name,
            )
        return root, dist, dist / "Gaius.html"

    @staticmethod
    def run_build(root: Path, dist: Path, output: Path) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            PORTABLE.build(dist, output, root=root)

    @staticmethod
    def embedded_manifest(html: Path) -> dict:
        marker = "const portableManifest = "
        text = html.read_text(encoding="utf-8")
        start = text.index(marker) + len(marker)
        end = text.index(";", start)
        return json.loads(text[start:end])

    @staticmethod
    def quick_check_identity(root: Path, dist: Path, output: Path) -> bool:
        with mock.patch.object(QUICK_CHECK, "PORT", root / "port"), \
                mock.patch.object(QUICK_CHECK, "VERSION_CONFIG", root / "port" / "config.json"), \
                mock.patch.object(QUICK_CHECK, "DIST", dist), \
                mock.patch.object(QUICK_CHECK, "PORTABLE_HTML", output), \
                mock.patch.object(QUICK_CHECK, "PORTABLE_MANIFEST", dist / "Gaius.manifest.json"), \
                mock.patch.object(QUICK_CHECK, "INDEX_HTML", dist / "index.html"):
            return QUICK_CHECK.portable_artifact_identity_matches()

    def test_correct_26_2_publishes_and_embeds_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)

            self.run_build(root, dist, output)

            manifest_path = dist / "Gaius.manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["profile"], "26.2")
            self.assertEqual(
                manifest["buildIdentity"]["compatibilitySha256"],
                manifest["classesJs"]["build"]["compatibilitySha256"],
            )
            self.assertEqual(
                manifest["classesJs"]["rawSha256"],
                PORTABLE.sha256_file(dist / "classes.js"),
            )
            self.assertEqual(
                manifest["classesJs"]["gzipSha256"],
                PORTABLE.sha256_file(dist / "classes.js.gz"),
            )
            self.assertEqual(
                manifest["singleplayerServerJs"]["rawSha256"],
                PORTABLE.sha256_file(dist / "singleplayer-server.js"),
            )
            self.assertEqual(
                manifest["wasmHotpath"]["gzipSha256"],
                PORTABLE.sha256_file(dist / "gaius-hotpath.wasm.gz"),
            )
            self.assertEqual(self.embedded_manifest(output), manifest)
            self.assertTrue(self.quick_check_identity(root, dist, output))
            self.assertEqual(
                gzip.decompress((dist / "classes.js.gz").read_bytes()),
                (dist / "classes.js").read_bytes(),
            )
            self.assertEqual(
                {signature["name"] for signature in manifest["signatures"]},
                {
                    "client-finite-long-patch",
                    "client-target-attestation",
                    "server-input-pump",
                },
            )

    def test_old_gzip_with_new_js_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            old_classes = b"old-1.21.11-client"
            root, dist, output = self.make_fixture(
                directory,
                classes=b"new-26.2-client",
                classes_gzip=compressed(old_classes),
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_quick_check_rejects_mixed_versions_independently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)
            (dist / "classes.js.gz").write_bytes(compressed(b"old-1.21.11-client"))

            self.assertFalse(self.quick_check_identity(root, dist, output))

    def test_old_server_gzip_is_rejected_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            (dist / "singleplayer-server.js.gz").write_bytes(
                compressed(b"old-singleplayer-server")
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_old_wasm_gzip_is_rejected_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            (dist / "gaius-hotpath.wasm.gz").write_bytes(compressed(b"old-wasm"))
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_quick_check_rejects_changed_embedded_components(self) -> None:
        for artifact_name in (
            "classes.js",
            "singleplayer-server.js",
            "gaius-hotpath.wasm",
            "singleplayer-server-worker.js",
            "vanilla-assets.pack.gz",
            "relay-nodes.json",
        ):
            with self.subTest(artifact=artifact_name), tempfile.TemporaryDirectory() as directory:
                root, dist, output = self.make_fixture(directory)
                self.run_build(root, dist, output)

                artifact = dist / artifact_name
                artifact.write_bytes(artifact.read_bytes() + b"changed")
                self.assertFalse(self.quick_check_identity(root, dist, output))

    def test_stale_worker_identity_is_rejected_even_when_gzip_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            server = b"/*gaius-integrated-server-input-coroutine*/new-server"
            (dist / "singleplayer-server.js").write_bytes(server)
            (dist / "singleplayer-server.js.gz").write_bytes(compressed(server))

            with self.assertRaisesRegex(RuntimeError, "artifact hash does not match"):
                self.run_build(root, dist, output)

    def test_missing_worker_identity_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            PORTABLE.build_identity.sidecar_path(
                dist / "singleplayer-server.js"
            ).unlink()

            with self.assertRaisesRegex(RuntimeError, "sidecar is missing"):
                self.run_build(root, dist, output)

    def test_source_change_rejects_all_previously_built_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            source = (
                root
                / "port"
                / "src"
                / "main"
                / "java"
                / "dev"
                / "gaius"
                / "browser"
                / "Fixture.java"
            )
            source.write_text("final class Fixture { int changed; }\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "source does not match"):
                self.run_build(root, dist, output)

    def test_overlay_change_rejects_all_previously_built_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            overlay = (
                root
                / "port"
                / "work"
                / "overlays"
                / "libraries"
                / "example"
                / "browser-library-1.0.jar"
            )
            overlay.write_bytes(b"changed-overlay")

            with self.assertRaisesRegex(RuntimeError, "overlay does not match"):
                self.run_build(root, dist, output)

    def test_wrong_profile_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                profile_file="26.2.json",
                profile_id="1.21.11",
                launcher_version="26.2",
                launcher_asset_index="32",
            )

            with self.assertRaisesRegex(RuntimeError, "launcher version"):
                self.run_build(root, dist, output)
            self.assertFalse(output.exists())

    def test_truncated_gzip_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            classes = (
                b'"use strict";'
                b"/*gaius-java-finite-long-cast*/target-attestation;262-startup"
            )
            root, dist, output = self.make_fixture(
                directory,
                classes=classes,
                classes_gzip=compressed(classes)[:-8],
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "complete gzip"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_build_failure_does_not_overwrite_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            output.write_text("previous portable release", encoding="utf-8")
            original_replace = PORTABLE.os.replace

            def fail_for_html(source: str, target: str) -> None:
                if Path(target).name == output.name:
                    raise OSError("simulated publication failure")
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=fail_for_html):
                with self.assertRaisesRegex(OSError, "publication failure"):
                    self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")
            self.assertFalse((dist / "Gaius.manifest.json").exists())
            self.assertEqual(list(dist.glob(f".{output.name}.*.tmp")), [])

    def test_manifest_replace_failure_rolls_back_html_and_commit_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)
            manifest_path = dist / "Gaius.manifest.json"
            previous_html = output.read_bytes()
            previous_manifest = manifest_path.read_bytes()

            classes = (dist / "classes.js").read_bytes() + b";next-release"
            (dist / "classes.js").write_bytes(classes)
            (dist / "classes.js.gz").write_bytes(compressed(classes))
            PORTABLE.build_identity.write_sidecar(
                root,
                "client",
                dist / "classes.js",
            )
            original_replace = PORTABLE.os.replace
            failed = False

            def fail_commit_marker_once(source: str, target: str) -> None:
                nonlocal failed
                if Path(target).resolve() == manifest_path.resolve() and not failed:
                    failed = True
                    raise OSError("simulated commit marker failure")
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=fail_commit_marker_once):
                with self.assertRaisesRegex(OSError, "commit marker failure"):
                    self.run_build(root, dist, output)

            self.assertEqual(output.read_bytes(), previous_html)
            self.assertEqual(manifest_path.read_bytes(), previous_manifest)
            self.assertEqual(self.embedded_manifest(output), json.loads(previous_manifest))

    def test_manifest_is_replaced_after_html_as_commit_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            manifest_path = dist / "Gaius.manifest.json"
            original_replace = PORTABLE.os.replace
            replacements: list[Path] = []

            def record_replace(source: str, target: str) -> None:
                replacements.append(Path(target))
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=record_replace):
                self.run_build(root, dist, output)

            normalized = [path.resolve() for path in replacements]
            self.assertLess(
                normalized.index(output.resolve()),
                normalized.index(manifest_path.resolve()),
            )
            self.assertEqual(self.embedded_manifest(output), json.loads(manifest_path.read_bytes()))

    def test_1_21_11_legacy_profile_remains_compatible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                version="1.21.11",
                distribution="obfuscated-with-mappings",
                classes=b"legacy-1.21.11-client",
            )

            self.run_build(root, dist, output)

            manifest = json.loads(
                (dist / "Gaius.manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(manifest["profile"], "1.21.11")
            self.assertEqual(manifest["signatures"], [])


if __name__ == "__main__":
    unittest.main()
