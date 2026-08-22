#!/usr/bin/env python3
"""Fixture coverage for quick-check's active overlay profile resolver."""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory


SCRIPT = Path(__file__).resolve().with_name("quick-check.py")
SPEC = importlib.util.spec_from_file_location("gaius_quick_check", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot import {SCRIPT}")
QUICK_CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUICK_CHECK)


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


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def artifact_path(coordinate: str, version: str, classifier: str | None = None) -> str:
    group, artifact = coordinate.split(":")
    suffix = f"-{version}"
    if classifier is not None:
        suffix += f"-{classifier}"
    return (
        f"{group.replace('.', '/')}/{artifact}/{version}/"
        f"{artifact}{suffix}.jar"
    )


def library_entries(versions: dict[str, str], distribution: str) -> list[dict]:
    lwjgl_classifier = "unsafe" if distribution == "named" else None
    libraries = [
        ("org.lwjgl:lwjgl", versions["lwjgl"], lwjgl_classifier),
        ("org.lwjgl:lwjgl-glfw", versions["lwjgl"], None),
        ("org.lwjgl:lwjgl-opengl", versions["lwjgl"], None),
        ("org.lwjgl:lwjgl-openal", versions["lwjgl"], None),
        ("io.netty:netty-transport", versions["netty"], None),
        ("com.mojang:authlib", versions["authlib"], None),
        ("org.joml:joml", versions["joml"], None),
        ("com.mojang:patchy", versions["patchy"], None),
    ]
    return [
        {
            "name": ":".join(
                part
                for part in (coordinate, version, classifier)
                if part is not None
            ),
            "downloads": {
                "artifact": {
                    "path": artifact_path(coordinate, version, classifier),
                },
            },
        }
        for coordinate, version, classifier in libraries
    ]


def write_profile(root: Path, version: str, distribution: str) -> None:
    profile_path = root / "versions" / f"{version}.json"
    profile_path.parent.mkdir(parents=True, exist_ok=True)
    profile_path.write_text(
        json.dumps({"id": version, "clientDistribution": distribution}),
        encoding="utf-8",
    )


def write_version(
    root: Path,
    version: str,
    distribution: str,
    versions: dict[str, str],
) -> None:
    work = root / "work" / version
    work.mkdir(parents=True, exist_ok=True)
    libraries = library_entries(versions, distribution)
    (work / "version.json").write_text(
        json.dumps({"id": version, "libraries": libraries}),
        encoding="utf-8",
    )
    (work / "client-version.json").write_text(
        json.dumps({"id": version}),
        encoding="utf-8",
    )

    overlays = root / "work" / "overlays"
    overlays.mkdir(parents=True, exist_ok=True)
    (overlays / f"client-named-{version}-gaius.jar").write_bytes(b"client")
    classpath_entries = []
    for library in libraries:
        path = library["downloads"]["artifact"]["path"]
        classpath_library = work / "libraries" / path
        classpath_library.parent.mkdir(parents=True, exist_ok=True)
        classpath_library.write_bytes(b"library")
        overlay_library = overlays / "libraries" / path
        overlay_library.parent.mkdir(parents=True, exist_ok=True)
        overlay_library.write_bytes(b"overlay")
        classpath_entries.append(str(classpath_library))
    (work / "classpath.txt").write_text(
        os.pathsep.join(classpath_entries),
        encoding="utf-8",
    )


def set_active_profile(root: Path, version: str) -> None:
    (root / "config.json").write_text(
        json.dumps({"versionProfile": f"versions/{version}.json"}),
        encoding="utf-8",
    )


def check_profile_scoped_defaults() -> None:
    keys = ("GAIUS_BUILD_ROOT", "GAIUS_VERSION_PROFILE_PATH")
    saved = {key: os.environ.get(key) for key in keys}
    try:
        for key in keys:
            os.environ.pop(key, None)
        base = QUICK_CHECK.PORT / "target"
        require(
            QUICK_CHECK._profile_scoped_default(base, "1.21.11") == base,
            "legacy quick-check target default unexpectedly became profile-scoped",
        )

        os.environ["GAIUS_VERSION_PROFILE_PATH"] = "versions/1.21.11.json"
        require(
            QUICK_CHECK._profile_scoped_default(base, "1.21.11") == base / "1.21.11",
            "GAIUS_VERSION_PROFILE_PATH did not select a profile-scoped target",
        )

        os.environ.pop("GAIUS_VERSION_PROFILE_PATH")
        os.environ["GAIUS_BUILD_ROOT"] = "port/target/1.21.11"
        require(
            QUICK_CHECK._profile_scoped_default(base, "1.21.11") == base / "1.21.11",
            "GAIUS_BUILD_ROOT did not select a profile-scoped target",
        )

        if os.name == "nt":
            msys_path = QUICK_CHECK._configured_path(
                "/c/gaius-profile-target",
                base,
            )
            require(
                msys_path == Path("C:/gaius-profile-target").resolve(),
                "Git-Bash /c/... path was not converted to a native Windows path",
            )
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def check_manifest_top_level_identity() -> None:
    storage = {
        "schema": 2,
        "databaseName": "gaius-fs-v2-26.2",
        "prefix": "gaius.fs.v2:26.2:",
        "opfsDirectory": "regions-v2-26.2",
    }
    expected = {
        "schemaVersion": 2,
        "profile": {
            "id": "26.2",
            "path": "versions/26.2.json",
            "sha256": "a" * 64,
            "clientDistribution": "named",
            "protocolVersion": 776,
            "worldVersion": 4903,
            "worldgenTelemetryMode": "task-pulsed",
            "storage": storage,
        },
        "worldVersion": 4903,
        "worldgenTelemetryMode": "task-pulsed",
        "storage": storage,
        "source": {"sha256": "b" * 64},
        "protocol": {"sha256": "c" * 64},
        "overlay": {"sha256": "d" * 64},
        "compatibilitySha256": "e" * 64,
    }
    manifest = {
        "profile": "26.2",
        "profilePath": "versions/26.2.json",
        "worldVersion": 4903,
        "worldgenTelemetryMode": "task-pulsed",
        "storage": storage,
        "buildIdentity": copy.deepcopy(expected),
    }
    require(
        QUICK_CHECK.manifest_top_level_identity_matches(manifest, expected),
        "matching portable top-level identity was rejected",
    )

    for field, forged in (
        ("profile", 262),
        ("profilePath", None),
        ("worldVersion", "4903"),
        ("worldgenTelemetryMode", None),
        ("storage", None),
    ):
        candidate = copy.deepcopy(manifest)
        if forged is None and field == "profilePath":
            candidate.pop(field)
        elif forged is None and field in {"worldgenTelemetryMode", "storage"}:
            candidate.pop(field)
        else:
            candidate[field] = forged
        require(
            not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
            f"forged or missing top-level {field} identity was accepted",
        )

    candidate = copy.deepcopy(manifest)
    candidate["buildIdentity"]["profile"].pop("worldVersion")
    require(
        not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
        "missing nested profile worldVersion was accepted",
    )

    candidate = copy.deepcopy(manifest)
    candidate["buildIdentity"]["storage"]["schema"] = True
    require(
        not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
        "boolean storage schema spoof was accepted as integer 2",
    )


@hermetic_gaius_environment()
def main() -> None:
    check_profile_scoped_defaults()
    check_manifest_top_level_identity()
    current_versions = {
        "lwjgl": "3.4.1",
        "netty": "4.2.15.Final",
        "authlib": "9.0.75",
        "joml": "1.10.8",
        "patchy": "2.2.10",
    }
    legacy_versions = {
        "lwjgl": "3.3.3",
        "netty": "4.2.7.Final",
        "authlib": "7.0.61",
        "joml": "1.10.8",
        "patchy": "2.2.10",
    }

    with TemporaryDirectory(prefix="gaius-quick-check-") as temporary:
        root = Path(temporary)
        write_profile(root, "26.2", "named")
        write_profile(root, "1.21.11", "obfuscated-with-mappings")
        write_version(root, "26.2", "named", current_versions)
        write_version(root, "1.21.11", "obfuscated-with-mappings", legacy_versions)

        set_active_profile(root, "26.2")
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(resolved["version"] == "26.2", "active 26.2 profile was not selected")
        require(resolved["client_distribution"] == "named", "26.2 distribution was not selected")
        require(
            resolved["client"]
            == root / "work" / "overlays" / "client-named-26.2-gaius.jar",
            "26.2 client overlay path is not profile-derived",
        )
        require(
            "3.4.1" in str(resolved["libraries"]["lwjgl"])
            and "4.2.15.Final" in str(resolved["libraries"]["netty_transport"])
            and "9.0.75" in str(resolved["libraries"]["authlib"]),
            "26.2 library overlay versions were not resolved",
        )
        require(not QUICK_CHECK.missing_overlay_paths(resolved), "complete 26.2 fixture is missing artifacts")

        set_active_profile(root, "1.21.11")
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(resolved["version"] == "1.21.11", "profile switch to 1.21.11 was not selected")
        require(
            resolved["client"]
            == root / "work" / "overlays" / "client-named-1.21.11-gaius.jar",
            "legacy client overlay path is not profile-derived",
        )
        require(
            "3.3.3" in str(resolved["libraries"]["lwjgl"])
            and "4.2.7.Final" in str(resolved["libraries"]["netty_transport"])
            and "7.0.61" in str(resolved["libraries"]["authlib"]),
            "1.21.11 library overlay versions were not resolved",
        )

        set_active_profile(root, "26.2")
        current_client = root / "work" / "overlays" / "client-named-26.2-gaius.jar"
        current_client.unlink()
        (root / "work" / "overlays" / "client-named-1.21.11-gaius.jar").write_bytes(
            b"old overlay must not satisfy current profile"
        )
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        missing = QUICK_CHECK.missing_overlay_paths(resolved)
        require(
            ("client", current_client) in missing,
            "missing current client overlay was not reported at its exact path",
        )
        require(resolved["client"] == current_client, "resolver fell back to the legacy client overlay")

        changed_versions = {
            "lwjgl": "3.4.2",
            "netty": "4.2.16.Final",
            "authlib": "9.0.76",
            "joml": "1.10.9",
            "patchy": "2.2.11",
        }
        write_version(root, "26.2", "named", changed_versions)
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(
            all(
                version in str(resolved["libraries"][key])
                for key, version in (
                    ("lwjgl", "3.4.2"),
                    ("netty_transport", "4.2.16.Final"),
                    ("authlib", "9.0.76"),
                )
            ),
            "changed library metadata did not move overlay paths",
        )
        require(not QUICK_CHECK.missing_overlay_paths(resolved), "changed library fixture is incomplete")

    print("quick-check profile resolver fixture passed")


if __name__ == "__main__":
    main()
