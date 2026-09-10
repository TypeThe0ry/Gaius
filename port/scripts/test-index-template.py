#!/usr/bin/env python3
"""Verify both supported profiles can be built from the tracked launcher shell."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "port" / "web" / "launcher" / "index.template.html"
POSTPROCESS = ROOT / "port" / "scripts" / "postprocess-index-html.py"
PROFILES = (
    ROOT / "port" / "versions" / "26.2.json",
    ROOT / "port" / "versions" / "1.21.11.json",
)


def require(text: str, marker: str, profile_id: str) -> None:
    if marker not in text:
        raise AssertionError(f"launcher for {profile_id} is missing: {marker}")


def exercise_profile(profile_path: Path, directory: Path) -> None:
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    profile_id = str(profile["id"])
    official = profile["official"]
    storage = profile["storage"]
    asset_index = str(official["assetIndexId"])

    output = directory / profile_id / "index.html"
    output.parent.mkdir(parents=True)
    shutil.copyfile(TEMPLATE, output)
    classes_js = output.parent / "classes.js"
    classes_js.write_text(f"// launcher fixture {profile_id}\n", encoding="utf-8")
    (output.parent / "vanilla-assets.pack.gz").write_bytes(b"fixture-assets")
    (output.parent / "singleplayer-server.js").write_text(
        "// fixture worker\n", encoding="utf-8"
    )
    (output.parent / "singleplayer-server-worker.js").write_text(
        "// fixture bootstrap\n", encoding="utf-8"
    )

    command = [
        sys.executable,
        str(POSTPROCESS),
        str(output),
        str(classes_js),
        profile_id,
        asset_index,
    ]
    first = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if first.returncode != 0:
        raise AssertionError(first.stderr or first.stdout)
    first_bytes = output.read_bytes()
    release_version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    require(first_bytes.decode("utf-8"),
            f'<span id="gaius-shell-version">VERSION {release_version}</span>',
            profile_id)

    second = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if second.returncode != 0:
        raise AssertionError(second.stderr or second.stdout)
    if output.read_bytes() != first_bytes:
        raise AssertionError(f"launcher postprocess is not idempotent for {profile_id}")

    text = first_bytes.decode("utf-8")
    require(text, f"<title>Gaius Client {profile_id}</title>", profile_id)
    require(text, f'window.__gaiusProfileId = "{profile_id}";', profile_id)
    require(text, f"window.__gaiusWorldVersion = {profile['worldVersion']};", profile_id)
    require(text, f"window.__gaiusStorageSchema = {storage['schema']};", profile_id)
    require(text, f'window.__gaiusStorageDatabaseName = "{storage["databaseName"]}";', profile_id)
    require(text, f'window.__gaiusStoragePrefix = "{storage["prefix"]}";', profile_id)
    require(text, f'window.__gaiusStorageOpfsDirectory = "{storage["opfsDirectory"]}";', profile_id)
    require(text, f'"--version", "{profile_id}",', profile_id)
    require(text, f'"--assetIndex", "{asset_index}",', profile_id)
    require(text, f"Loading the {profile_id} client runtime...", profile_id)
    if 'const vanillaAssetsToken = "dev";' in text:
        raise AssertionError(f"launcher for {profile_id} did not hash the staged asset pack")
    if 'const fallbackBuildToken = "dev";' in text:
        raise AssertionError(f"launcher for {profile_id} did not hash staged classes.js")
    if 'const singleplayerBuildToken = "dev" +' in text:
        raise AssertionError(f"launcher for {profile_id} did not hash staged Worker assets")


def main() -> int:
    template = TEMPLATE.read_text(encoding="utf-8")
    if "../dist/index.html" in template:
        raise AssertionError("tracked launcher template must not be the redirect wrapper")
    require(template, "function decodeGaiusVanillaAssets(source)", "template")
    for forbidden in (
        "26.2",
        "1.21.11",
        "gaius.fs.v2:",
        "gaius-fs-v2-",
        "regions-v2-",
        'window.__gaiusProfileId = "26.2";',
        'window.__gaiusWorldVersion = 4903;',
        '"--assetIndex", "32",',
    ):
        if forbidden in template:
            raise AssertionError(f"tracked launcher template is profile-specific: {forbidden}")
    require(template, 'window.__gaiusProfileId = "template";', "template")
    require(template, 'const vanillaAssetsToken = "dev";', "template")
    require(template, 'const fallbackBuildToken = "dev";', "template")
    with tempfile.TemporaryDirectory(prefix="gaius-index-template-") as temporary:
        directory = Path(temporary)
        for profile_path in PROFILES:
            exercise_profile(profile_path, directory)
    print("launcher template regression passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
