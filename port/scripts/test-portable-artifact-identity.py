#!/usr/bin/env python3
"""Fixture tests for portable artifact identity and publication safety."""

from __future__ import annotations

import contextlib
import gzip
import importlib.util
import io
import json
import os
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


@contextlib.contextmanager
def hermetic_gaius_environment():
    """Run fixture code without inheriting profile-selection overrides."""
    fixture_env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith("GAIUS_")
    }
    saved_env = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(fixture_env)
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved_env)


def compressed(value: bytes) -> bytes:
    return gzip.compress(value, mtime=0)


def teavm_pom(main_class: str, target_directory: Path, target_file: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <build><plugins><plugin>
    <groupId>org.teavm</groupId>
    <artifactId>teavm-maven-plugin</artifactId>
    <executions><execution>
    <id>compile-fixture</id>
    <phase>package</phase>
    <goals><goal>compile</goal></goals>
    <configuration>
      <mainClass>{main_class}</mainClass>
      <targetDirectory>{target_directory}</targetDirectory>
      <targetFileName>{target_file}</targetFileName>
      <optimizationLevel>ADVANCED</optimizationLevel>
      <sourceMapsGenerated>false</sourceMapsGenerated>
      <debugInformationGenerated>false</debugInformationGenerated>
      <minifying>true</minifying>
      <shortFileNames>true</shortFileNames>
      <assertionsRemoved>true</assertionsRemoved>
    </configuration></execution></executions>
  </plugin></plugins></build>
</project>
"""


class PortableArtifactIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self._fixture_environment = hermetic_gaius_environment()
        self._fixture_environment.__enter__()
        self.addCleanup(self._fixture_environment.__exit__, None, None, None)

    @staticmethod
    def write_compiler_profile(
        root: Path,
        role: str,
        artifact: Path,
        pom: Path,
        resources: list[Path],
    ) -> None:
        record = PORTABLE.compiler_profile.create_record(
            root,
            role,
            artifact,
            pom,
            resources,
            True,
        )
        PORTABLE.compiler_profile.write_atomically(
            PORTABLE.compiler_profile.default_output(artifact),
            PORTABLE.compiler_profile.canonical_json(record) + "\n",
        )

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
                    "worldVersion": 4903 if version == "26.2" else 4671,
                    "storage": {
                        "schema": 2,
                        "databaseName": f"gaius-fs-v2-{profile_id}",
                        "prefix": f"gaius.fs.v2:{profile_id}:",
                        "opfsDirectory": f"regions-v2-{profile_id}",
                    },
                    "official": {"assetIndexId": launcher_asset_index},
                }
            ),
            encoding="utf-8",
        )

        index = (
            "<!doctype html>\n"
            f'<script>const args = ["--version", "{launcher_version}", '
            f'"--assetIndex", "{launcher_asset_index}"];</script>\n'
            f'<script data-gaius-storage-profile="v2">'
            f'window.__gaiusProfileId = "{profile_id}"; '
            f'window.__gaiusWorldVersion = {4903 if version == "26.2" else 4671}; '
            'window.__gaiusStorageSchema = 2; '
            f'window.__gaiusStorageDatabaseName = "gaius-fs-v2-{profile_id}"; '
            f'window.__gaiusStoragePrefix = "gaius.fs.v2:{profile_id}:"; '
            f'window.__gaiusStorageOpfsDirectory = "regions-v2-{profile_id}";</script>\n'
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
        source_publication_gate = root / "port" / "scripts" / "teavm-publication-gate.sh"
        source_publication_gate.parent.mkdir(parents=True, exist_ok=True)
        source_publication_gate.write_text(
            "gaius_teavm_publish_allowed() { return 0; }\n",
            encoding="utf-8",
        )
        source_launcher_template = root / "port" / "web" / "launcher" / "index.template.html"
        source_launcher_template.parent.mkdir(parents=True, exist_ok=True)
        source_launcher_template.write_text(
            "<!doctype html><title>Gaius fixture</title>\n",
            encoding="utf-8",
        )
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
                    "assetIndex": {"id": launcher_asset_index},
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
        asset_index = version_work / "assets" / "indexes" / f"{launcher_asset_index}.json"
        asset_index.parent.mkdir(parents=True)
        asset_index.write_text('{"objects":{}}\n', encoding="utf-8")
        generated_resources = root / "port" / "target" / "generated-resources"
        client_resources = [
            generated_resources / "dev/gaius/browser/minecraft-resources.txt",
            generated_resources / "dev/gaius/browser/minecraft-embedded-resources.txt",
            asset_index,
            generated_resources / "assets/minecraft/sounds.json",
            generated_resources / "assets/minecraft/font/include/unifont.json",
            generated_resources / "assets/minecraft/font/include/unifont_pua.json",
            dist / "vanilla-assets.pack.gz",
        ]
        for index, resource in enumerate(client_resources[:-1]):
            if resource == asset_index:
                continue
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_text(f"fixture-resource-{index}\n", encoding="utf-8")
        client_staging_pom = root / "port" / "target" / "generated-pom.xml"
        client_staging_pom.write_text(
            teavm_pom(
                "net.minecraft.client.main.Main",
                root / "port" / "target" / ".teavm-staging" / "client" / "dist",
                "classes.js",
            ),
            encoding="utf-8",
        )
        client_pom = root / "port" / "target" / "release-generated-pom.xml"
        client_pom.write_text(
            teavm_pom("net.minecraft.client.main.Main", dist, "classes.js"),
            encoding="utf-8",
        )
        worker_resources = (
            root
            / "port"
            / "target"
            / "server-worker"
            / "generated-resources/dev/gaius/browser/minecraft-resources.txt"
        )
        worker_resources.parent.mkdir(parents=True, exist_ok=True)
        worker_resources.write_text("fixture-worker-resources\n", encoding="utf-8")
        worker_staging_pom = (
            root / "port" / "target" / "server-worker" / "generated-pom.xml"
        )
        worker_staging_pom.write_text(
            teavm_pom(
                "dev.gaius.browser.BrowserIntegratedServerMain",
                root
                / "port"
                / "target"
                / "server-worker"
                / ".teavm-staging"
                / "dist",
                "singleplayer-server.js",
            ),
            encoding="utf-8",
        )
        worker_pom = (
            root / "port" / "target" / "server-worker" / "release-generated-pom.xml"
        )
        worker_pom.write_text(
            teavm_pom(
                "dev.gaius.browser.BrowserIntegratedServerMain",
                dist,
                "singleplayer-server.js",
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
        for role, artifact, pom, resources in (
            ("client", dist / "classes.js", client_pom, client_resources),
            (
                "singleplayer-worker",
                dist / "singleplayer-server.js",
                worker_pom,
                [worker_resources],
            ),
        ):
            self.write_compiler_profile(root, role, artifact, pom, resources)
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
                mock.patch.object(QUICK_CHECK, "INDEX_HTML", dist / "index.html"), \
                mock.patch.object(
                    QUICK_CHECK,
                    "BUILD_IDENTITY_SCHEMA_VERSION",
                    PORTABLE.build_identity.IDENTITY_SCHEMA_VERSION,
                ), \
                mock.patch.object(
                    QUICK_CHECK,
                    "current_build_identity_for_quick_check",
                    side_effect=lambda *_args, **_kwargs: PORTABLE.build_identity.current_build_identity(root),
                ):
            return QUICK_CHECK.portable_artifact_identity_matches()

    def test_correct_26_2_publishes_and_embeds_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)

            self.run_build(root, dist, output)

            manifest_path = dist / "Gaius.manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["profile"], "26.2")
            self.assertEqual(manifest["worldVersion"], 4903)
            self.assertEqual(
                manifest["storage"],
                {
                    "schema": 2,
                    "databaseName": "gaius-fs-v2-26.2",
                    "prefix": "gaius.fs.v2:26.2:",
                    "opfsDirectory": "regions-v2-26.2",
                },
            )
            self.assertEqual(manifest["buildIdentity"]["worldVersion"], 4903)
            self.assertEqual(manifest["buildIdentity"]["storage"], manifest["storage"])
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

    def test_client_compiler_profile_rejects_wrong_target_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            pom = root / "port" / "target" / "release-generated-pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<targetFileName>classes.js</targetFileName>",
                    "<targetFileName>other.js</targetFileName>",
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "targetFileName"):
                self.run_build(root, dist, output)

    def test_client_compiler_profile_rejects_wrong_main_class(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            pom = root / "port" / "target" / "release-generated-pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<mainClass>net.minecraft.client.main.Main</mainClass>",
                    "<mainClass>example.WrongMain</mainClass>",
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "requires mainClass"):
                self.run_build(root, dist, output)

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

    def test_teavm_publication_gate_change_rejects_previous_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            gate = root / "port" / "scripts" / "teavm-publication-gate.sh"
            gate.write_text(
                "gaius_teavm_publish_allowed() { return 1; }\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "source does not match"):
                self.run_build(root, dist, output)

    def test_launcher_template_change_rejects_previous_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            template = root / "port" / "web" / "launcher" / "index.template.html"
            template.write_text(
                "<!doctype html><title>Changed Gaius fixture</title>\n",
                encoding="utf-8",
            )

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
            version = PORTABLE.load_version_profile(root)[0]["id"]
            metadata = json.loads(
                (root / "port" / "work" / version / "version.json").read_text(
                    encoding="utf-8"
                )
            )
            asset_index_id = metadata["assetIndex"]["id"]
            generated_resources = root / "port" / "target" / "generated-resources"
            self.write_compiler_profile(
                root,
                "client",
                dist / "classes.js",
                root / "port" / "target" / "release-generated-pom.xml",
                [
                    generated_resources / "dev/gaius/browser/minecraft-resources.txt",
                    generated_resources
                    / "dev/gaius/browser/minecraft-embedded-resources.txt",
                    root
                    / "port"
                    / "work"
                    / version
                    / "assets"
                    / "indexes"
                    / f"{asset_index_id}.json",
                    generated_resources / "assets/minecraft/sounds.json",
                    generated_resources
                    / "assets/minecraft/font/include/unifont.json",
                    generated_resources
                    / "assets/minecraft/font/include/unifont_pua.json",
                    dist / "vanilla-assets.pack.gz",
                ],
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
