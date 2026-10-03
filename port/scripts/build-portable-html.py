#!/usr/bin/env python3
"""Build a self-contained Gaius HTML file for offline singleplayer use."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path


def _native_external_path(value: str) -> Path:
    """Accept Git-Bash /c/... paths when running Windows Python."""
    if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", value):
        value = f"{value[1].upper()}:{value[2:]}"
    return Path(value).expanduser()

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))
import gaius_build_identity as build_identity

COMPILER_PROFILE_PATH = SCRIPT_DIRECTORY / "teavm-compiler-profile.py"
COMPILER_PROFILE_SPEC = importlib.util.spec_from_file_location(
    "gaius_teavm_compiler_profile", COMPILER_PROFILE_PATH
)
if COMPILER_PROFILE_SPEC is None or COMPILER_PROFILE_SPEC.loader is None:
    raise RuntimeError(f"could not load TeaVM compiler profile helper: {COMPILER_PROFILE_PATH}")
compiler_profile = importlib.util.module_from_spec(COMPILER_PROFILE_SPEC)
COMPILER_PROFILE_SPEC.loader.exec_module(compiler_profile)


MANIFEST_NAME = "Gaius.manifest.json"
MANIFEST_KIND = "gaius-portable-artifact"
MANIFEST_SCHEMA_VERSION = 2

# Generated-artifact markers survive TeaVM renaming/minifying. These are also
# the markers checked when a release reuses compiled JavaScript.
PORTABLE_SIGNATURES = (
    (
        "client-finite-long-patch",
        "classes.js",
        b"/*gaius-java-finite-long-cast*/",
    ),
    (
        "client-target-attestation",
        "classes.js",
        b"target-attestation",
    ),
    (
        "server-input-pump",
        "singleplayer-server.js",
        b"/*gaius-integrated-server-input-coroutine*/",
    ),
)


def write_text_atomically(target: Path, text: str) -> None:
    """Replace the portable artifact only after its complete contents are durable."""
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            # LF on every host: release gates and the Pages builder match this text exactly.
            newline="\n",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, target)
        temporary_name = None
        # Windows cannot open a directory for fsync; the replace is atomic anyway.
        if os.name != "nt":
            directory_fd = os.open(target.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _prepare_text(target: Path, text: str) -> str:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            # LF on every host: release gates and the Pages builder match this text exactly.
            newline="\n",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        result = temporary_name
        temporary_name = None
        return result
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _backup_path(path: Path) -> str | None:
    if not path.exists():
        return None
    descriptor, backup = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".rollback",
    )
    os.close(descriptor)
    os.unlink(backup)
    try:
        try:
            os.link(path, backup)
        except OSError:
            shutil.copy2(path, backup)
            with open(backup, "rb") as stream:
                os.fsync(stream.fileno())
        return backup
    except BaseException:
        try:
            os.unlink(backup)
        except FileNotFoundError:
            pass
        raise


def _fsync_directory(directory: Path) -> None:
    # Windows cannot open a directory for fsync.
    if os.name == "nt":
        return
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def publish_portable_pair(
    output: Path,
    portable: str,
    manifest_path: Path,
    manifest_text: str,
) -> None:
    """Publish HTML first and its sidecar last as the commit marker."""
    if output.parent != manifest_path.parent:
        raise RuntimeError("portable HTML and manifest must share a directory")
    html_temporary: str | None = None
    manifest_temporary: str | None = None
    html_backup: str | None = None
    manifest_backup: str | None = None
    html_replaced = False
    manifest_replaced = False
    try:
        html_temporary = _prepare_text(output, portable)
        manifest_temporary = _prepare_text(manifest_path, manifest_text)
        html_backup = _backup_path(output)
        manifest_backup = _backup_path(manifest_path)

        os.replace(html_temporary, output)
        html_temporary = None
        html_replaced = True
        _fsync_directory(output.parent)

        os.replace(manifest_temporary, manifest_path)
        manifest_temporary = None
        manifest_replaced = True
        _fsync_directory(output.parent)
    except BaseException:
        if manifest_replaced:
            if manifest_backup is None:
                manifest_path.unlink(missing_ok=True)
            else:
                os.replace(manifest_backup, manifest_path)
                manifest_backup = None
        if html_replaced:
            if html_backup is None:
                output.unlink(missing_ok=True)
            else:
                os.replace(html_backup, output)
                html_backup = None
        _fsync_directory(output.parent)
        raise
    finally:
        for temporary in (
            html_temporary,
            manifest_temporary,
            html_backup,
            manifest_backup,
        ):
            if temporary:
                try:
                    os.unlink(temporary)
                except FileNotFoundError:
                    pass


# Embedded payload encoding (gaius-b7-v1). Every asset is compressed once (gzip, the only
# codec every browser's DecompressionStream reads) and stored once as text: 7 bits per
# character in inert <script type="text/x-gaius-b7"> elements. The 7-bit values the HTML parser
# would alter or that could close the element (NUL, CR, "<"), and LF so that no newline
# translation (a Windows text-mode write, a git checkout) can touch the payload, become
# U+0080..U+0083. The text stays one byte per character in memory and about 1.18 bytes per
# payload byte on disk (base64 inside a JavaScript string literal cost 1.33 and had to be
# parsed as script). A decoder Worker in the page turns the parts back into bytes and inflates
# them off the main thread.
PAYLOAD_ENCODING = "gaius-b7-v1"
PAYLOAD_TYPE = "text/x-gaius-b7"
B7_ESCAPES = (0x00, 0x0A, 0x0D, 0x3C)
# Characters per payload element: a multiple of 8 (one 7-byte block encodes to 8 characters).
B7_PART_CHARS = 4 * 1024 * 1024


def _byte_table(function) -> bytes:
    return bytes(function(value) & 0xFF for value in range(256))


_B7_SHIFTS = (
    # (column, table) pairs whose OR is one 7-bit group; see decode_b7 for the inverse.
    ((0, _byte_table(lambda b: b >> 1)),),
    ((0, _byte_table(lambda b: (b & 1) << 6)), (1, _byte_table(lambda b: b >> 2))),
    ((1, _byte_table(lambda b: (b & 3) << 5)), (2, _byte_table(lambda b: b >> 3))),
    ((2, _byte_table(lambda b: (b & 7) << 4)), (3, _byte_table(lambda b: b >> 4))),
    ((3, _byte_table(lambda b: (b & 15) << 3)), (4, _byte_table(lambda b: b >> 5))),
    ((4, _byte_table(lambda b: (b & 31) << 2)), (5, _byte_table(lambda b: b >> 6))),
    ((5, _byte_table(lambda b: (b & 63) << 1)), (6, _byte_table(lambda b: b >> 7))),
    ((6, _byte_table(lambda b: b & 127)),),
)
_B7_TO_TEXT = bytes(
    0x80 + B7_ESCAPES.index(value) if value in B7_ESCAPES else value
    for value in range(256)
)


def encode_b7(data: bytes) -> str:
    """Encode bytes as gaius-b7 text (whole 7-byte blocks; the decoder truncates the padding).

    Column-wise byte translation and big-integer ORs keep the work in C: a 100 MB payload
    encodes in seconds without third-party modules.
    """
    padded = data + b"\0" * ((-len(data)) % 7)
    blocks = len(padded) // 7
    if blocks == 0:
        return ""
    columns = [padded[index::7] for index in range(7)]
    output = bytearray(blocks * 8)
    for group, parts in enumerate(_B7_SHIFTS):
        value = 0
        for column, table in parts:
            value |= int.from_bytes(columns[column].translate(table), "big")
        output[group::8] = value.to_bytes(blocks, "big")
    return bytes(output).translate(_B7_TO_TEXT).decode("latin-1")


_B7_FROM_TEXT = {0x80 + index: value for index, value in enumerate(B7_ESCAPES)}


def decode_b7(text: str, length: int) -> bytes:
    """Reference decoder (the page's decoder Worker does the same in JavaScript)."""
    groups = bytes(_B7_FROM_TEXT.get(ord(char), ord(char)) for char in text)
    output = bytearray()
    for start in range(0, len(groups) - 7, 8):
        g = groups[start:start + 8]
        output += bytes((
            (g[0] << 1) | (g[1] >> 6),
            ((g[1] & 63) << 2) | (g[2] >> 5),
            ((g[2] & 31) << 3) | (g[3] >> 4),
            ((g[3] & 15) << 4) | (g[4] >> 3),
            ((g[4] & 7) << 5) | (g[5] >> 2),
            ((g[5] & 3) << 6) | (g[6] >> 1),
            ((g[6] & 1) << 7) | g[7],
        ))
    if length > len(output):
        raise ValueError("gaius-b7 text is shorter than its declared length")
    return bytes(output[:length])


class PortablePayload:
    """The embedded assets of one portable page, in document (and decode-priority) order."""

    def __init__(self) -> None:
        self.assets: dict[str, dict[str, object]] = {}
        self.elements: list[str] = []

    def add(self, name: str, data: bytes, *, gzipped: bool) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or name in self.assets:
            raise RuntimeError(f"invalid or duplicate portable payload name: {name}")
        text = encode_b7(data)
        parts = 0
        for start in range(0, len(text), B7_PART_CHARS):
            self.elements.append(
                f'  <script type="{PAYLOAD_TYPE}" data-gaius-asset="{name}" data-part="{parts}">'
                + text[start:start + B7_PART_CHARS]
                + "</script>\n"
            )
            parts += 1
        self.assets[name] = {
            "bytes": len(data),
            "parts": parts,
            "gzip": gzipped,
            "sha256": sha256_bytes(data),
        }

    def index(self) -> dict[str, object]:
        return {"encoding": PAYLOAD_ENCODING, "assets": self.assets}

    def html(self) -> str:
        return "".join(self.elements)


def parse_portable_payload(html: str) -> dict[str, bytes]:
    """Decode every embedded asset of a portable page (tests and audits)."""
    pattern = re.compile(
        r'<script type="' + re.escape(PAYLOAD_TYPE)
        + r'" data-gaius-asset="([A-Za-z0-9_-]+)" data-part="(\d+)">([^<]*)</script>'
    )
    texts: dict[str, list[str]] = {}
    for match in pattern.finditer(html):
        parts = texts.setdefault(match.group(1), [])
        if int(match.group(2)) != len(parts):
            raise ValueError(f"portable payload {match.group(1)} parts are out of order")
        parts.append(match.group(3))
    index_marker = "const portablePayload = "
    start = html.index(index_marker) + len(index_marker)
    # The index holds names, numbers and hashes only, so ';' ends it whatever the line endings.
    end = html.index(";", start)
    index = json.loads(html[start:end])
    decoded: dict[str, bytes] = {}
    for name, text_parts in texts.items():
        decoded[name] = decode_b7("".join(text_parts), int(index["assets"][name]["bytes"]))
    return decoded


def require(path: Path) -> Path:
    if not path.is_file():
        raise FileNotFoundError(f"missing portable asset: {path}")
    return path


def require_nonempty(path: Path) -> Path:
    require(path)
    if path.stat().st_size == 0:
        raise RuntimeError(f"portable asset is empty: {path}")
    return path


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_version_profile(root: Path) -> tuple[dict, str, bytes]:
    config_path = require(root / "port" / "config.json")
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"could not read version config: {config_path}") from exc

    relative_profile = (
        os.environ.get("GAIUS_VERSION_PROFILE_PATH")
        or config.get("versionProfile")
    )
    if not isinstance(relative_profile, str) or not relative_profile:
        raise RuntimeError("port/config.json versionProfile must be a non-empty string")

    versions_directory = (root / "port" / "versions").resolve()
    profile_path = (root / "port" / relative_profile).resolve()
    try:
        profile_path.relative_to(versions_directory)
    except ValueError as exc:
        raise RuntimeError(
            "port/config.json versionProfile must point inside port/versions"
        ) from exc
    if profile_path.suffix != ".json":
        raise RuntimeError("port/config.json versionProfile must name a JSON profile")

    profile_bytes = require(profile_path).read_bytes()
    try:
        profile = json.loads(profile_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"could not read version profile: {profile_path}") from exc
    if not isinstance(profile, dict) or not isinstance(profile.get("id"), str):
        raise RuntimeError(f"version profile has no id: {profile_path}")
    if not profile["id"]:
        raise RuntimeError(f"version profile id is empty: {profile_path}")
    distribution = profile.get("clientDistribution")
    if distribution not in {"named", "obfuscated-with-mappings"}:
        raise RuntimeError(f"version profile clientDistribution is invalid: {profile_path}")
    if not isinstance(profile.get("worldVersion"), int) or profile["worldVersion"] < 0:
        raise RuntimeError(f"version profile worldVersion is invalid: {profile_path}")
    build_identity._validate_worldgen_telemetry_mode(profile)
    build_identity._validate_storage(profile)
    return profile, relative_profile, profile_bytes


def configured_build_root(root: Path) -> Path:
    """Return the target/state root for the active profile.

    The historical default remains port/target.  Supplying
    GAIUS_BUILD_ROOT makes every generated POM, resource list, Maven output,
    log and gap report live under that caller-owned directory.
    """
    raw = os.environ.get("GAIUS_BUILD_ROOT")
    if not raw:
        if os.environ.get("GAIUS_VERSION_PROFILE_PATH"):
            profile, _, _ = load_version_profile(root)
            return root / "port" / "target" / str(profile["id"])
        return root / "port" / "target"
    path = _native_external_path(raw)
    return (root / path).resolve() if not path.is_absolute() else path.resolve()


def configured_dist_directory(root: Path, profile_id: str) -> Path:
    raw = os.environ.get("GAIUS_DIST_DIRECTORY")
    if not raw and not (
        os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    ):
        raw = os.environ.get("GAIUS_TARGET_DIRECTORY")
    if raw:
        path = _native_external_path(raw)
        return (root / path).resolve() if not path.is_absolute() else path.resolve()
    if os.environ.get("GAIUS_BUILD_ROOT") or os.environ.get("GAIUS_VERSION_PROFILE_PATH"):
        return root / "port" / "web" / "dist" / profile_id
    return root / "port" / "web" / "dist"


def launcher_argument(index: str, name: str) -> str | None:
    match = re.search(
        rf"[\"']{re.escape(name)}[\"']\s*,\s*[\"']([^\"']+)[\"']",
        index,
    )
    return match.group(1) if match is not None else None


def validate_launcher_profile(index: str, profile: dict) -> None:
    expected_version = profile["id"]
    actual_version = launcher_argument(index, "--version")
    if actual_version != expected_version:
        raise RuntimeError(
            f"portable launcher version {actual_version!r} does not match "
            f"active profile {expected_version!r}"
        )

    official = profile.get("official")
    expected_asset_index = (
        official.get("assetIndexId") if isinstance(official, dict) else None
    )
    if expected_asset_index is not None:
        actual_asset_index = launcher_argument(index, "--assetIndex")
        if actual_asset_index != str(expected_asset_index):
            raise RuntimeError(
                f"portable launcher asset index {actual_asset_index!r} does not match "
                f"active profile {expected_asset_index!r}"
            )


def launcher_global(index: str, name: str) -> object | None:
    match = re.search(
        rf"window\.{re.escape(name)}\s*=\s*(\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*'|-?\d+)\s*;",
        index,
    )
    if match is None:
        return None
    expression = match.group(1)
    if expression.startswith("'"):
        return bytes(expression[1:-1], "utf-8").decode("unicode_escape")
    return json.loads(expression)


def validate_launcher_storage(index: str, profile: dict) -> None:
    storage = build_identity._validate_storage(profile)
    expected = {
        "__gaiusProfileId": profile["id"],
        "__gaiusWorldVersion": profile["worldVersion"],
        "__gaiusStorageSchema": storage["schema"],
        "__gaiusStorageDatabaseName": storage["databaseName"],
        "__gaiusStoragePrefix": storage["prefix"],
        "__gaiusStorageOpfsDirectory": storage["opfsDirectory"],
    }
    for name, expected_value in expected.items():
        actual = launcher_global(index, name)
        if actual != expected_value:
            raise RuntimeError(
                f"portable launcher {name} {actual!r} does not match profile {expected_value!r}"
            )


def compare_gzip_with_raw(raw_path: Path, compressed_path: Path) -> tuple[str, str]:
    """Verify that gzip expands to the exact current raw JavaScript bytes."""
    raw_hash = sha256_file(raw_path)
    compressed_hash = sha256_file(compressed_path)
    try:
        # GzipFile.read(size) is allowed to return a short chunk even when
        # more decompressed bytes remain. Comparing fixed-size reads from the
        # raw and gzip streams therefore produces false mismatches at arbitrary
        # buffer boundaries. Read both complete byte streams for an exact
        # identity check; this runs only during packaging and avoids accepting
        # a stale or truncated compressed asset.
        raw_bytes = raw_path.read_bytes()
        compressed_bytes = gzip.open(compressed_path, "rb").read()
        if raw_bytes != compressed_bytes:
            raise RuntimeError(
                "classes.js.gz does not expand to the current classes.js"
            )
    except (EOFError, OSError) as exc:
        raise RuntimeError(f"classes.js.gz is not a complete gzip: {compressed_path}") from exc
    return raw_hash, compressed_hash


def contains_marker(path: Path, marker: bytes) -> bool:
    overlap = b""
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            if marker in overlap + chunk:
                return True
            overlap = (overlap + chunk)[-len(marker) + 1:]
    return False


def required_signatures(profile: dict) -> tuple[tuple[str, str, bytes], ...]:
    # The named profile is the current TeaVM line. The legacy mapped profile
    # remains buildable without requiring patches introduced after that line.
    if profile.get("clientDistribution") == "named":
        return PORTABLE_SIGNATURES
    return ()


def verify_signatures(dist: Path, profile: dict, classes_hash: str) -> list[dict[str, object]]:
    verified: list[dict[str, object]] = []
    for name, asset, marker in required_signatures(profile):
        path = require_nonempty(dist / asset)
        if not contains_marker(path, marker):
            raise RuntimeError(f"portable {asset} is missing build signature {name}")
        signature: dict[str, object] = {
            "name": name,
            "asset": asset,
            "marker": marker.decode("ascii"),
            "verified": True,
        }
        if asset == "classes.js":
            signature["sha256"] = classes_hash
        verified.append(signature)
    return verified


# A client on the renderpearl render API (26.3+) compiles its shaders with the
# WebAssembly shader toolchain (PLAN D5, contract C7).  postprocess-index-html.py
# tags such a page with one loader <script> in <head>, and build-teavm.sh
# publishes the loader, the four emscripten modules and the toolchain manifest
# (sizes, sha256, pinned sources) next to index.html.  The portable page embeds
# all of them: the modules become Blob URLs published through
# window.__gaiusShaderToolchainUrls, and the loader runs inline after the
# portable bootstrap, so it finds the URLs once the embedded assets are ready.
# A 26.2 page has no loader tag and its portable output is unchanged.
SHADER_TOOLCHAIN_LOADER = "gaius-shader-toolchain.js"
SHADER_TOOLCHAIN_MANIFEST = "gaius-shader-toolchain.json"
SHADER_TOOLCHAIN_MANIFEST_SCHEMA = 1
SHADER_TOOLCHAIN_IDENTITY_ROLE = "shader-toolchain"
SHADER_TOOLCHAIN_MODULES = (
    ("shadercJs", "gaius-shaderc.js", "text/javascript"),
    ("shadercWasm", "gaius-shaderc.wasm", "application/wasm"),
    ("spvcJs", "gaius-spvc.js", "text/javascript"),
    ("spvcWasm", "gaius-spvc.wasm", "application/wasm"),
)
# The token order of postprocess-index-html.py (SHADER_TOOLCHAIN_FILES).
SHADER_TOOLCHAIN_FILES = (SHADER_TOOLCHAIN_LOADER,) + tuple(
    name for _key, name, _mime in SHADER_TOOLCHAIN_MODULES
)
SHADER_TOOLCHAIN_CLASSES_MARKER = b"__gaiusShaderToolchain"
SHADER_TOOLCHAIN_TAG = re.compile(
    r'  <script data-gaius-shader-toolchain data-profile="([^"]*)" '
    r'src="gaius-shader-toolchain\.js\?v=([0-9a-f]+)"></script>\n'
)


def shader_toolchain_token(directory: Path) -> str:
    """The loader URL token of postprocess-index-html.py (content_token)."""
    digest = hashlib.sha256()
    for name in SHADER_TOOLCHAIN_FILES:
        with (directory / name).open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()[:16]


def gzip_bytes(value: bytes) -> bytes:
    """Deterministic gzip for assets the dist does not ship compressed."""
    return gzip.compress(value, compresslevel=9, mtime=0)


def load_shader_toolchain(
    dist: Path,
    index: str,
    profile: dict,
    classes_js: Path,
    root: Path,
    common_identity: dict[str, object],
) -> dict[str, object] | None:
    """Verify and load the shader toolchain of a page that carries its loader tag.

    Returns None for a page without the tag (26.2).  A client whose classes.js
    calls the toolchain but whose page does not load it can never start, so
    that combination is an error rather than a plain portable build.
    """
    tags = SHADER_TOOLCHAIN_TAG.findall(index)
    if not tags:
        if contains_marker(classes_js, SHADER_TOOLCHAIN_CLASSES_MARKER):
            raise RuntimeError(
                "portable classes.js calls the WebAssembly shader toolchain but "
                "index.html has no gaius-shader-toolchain.js loader"
            )
        return None
    if len(tags) != 1:
        raise RuntimeError("portable index.html has more than one shader toolchain loader")
    tag_profile, token = tags[0]
    if tag_profile != profile["id"]:
        raise RuntimeError(
            f"portable shader toolchain loader profile {tag_profile!r} does not match "
            f"active profile {profile['id']!r}"
        )
    for name in SHADER_TOOLCHAIN_FILES + (SHADER_TOOLCHAIN_MANIFEST,):
        require_nonempty(dist / name)
    if shader_toolchain_token(dist) != token:
        raise RuntimeError(
            "portable shader toolchain files do not match the loader token of index.html"
        )
    manifest_path = dist / SHADER_TOOLCHAIN_MANIFEST
    try:
        toolchain_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"shader toolchain manifest is invalid: {manifest_path}") from exc
    if (
        not isinstance(toolchain_manifest, dict)
        or toolchain_manifest.get("schema") != SHADER_TOOLCHAIN_MANIFEST_SCHEMA
        or not isinstance(toolchain_manifest.get("files"), dict)
        or not isinstance(toolchain_manifest.get("sources"), dict)
        or not isinstance(toolchain_manifest.get("emscripten"), str)
    ):
        raise RuntimeError(f"shader toolchain manifest is incompatible: {manifest_path}")
    recorded_files = toolchain_manifest["files"]
    file_records: dict[str, dict[str, object]] = {}
    for name in SHADER_TOOLCHAIN_FILES:
        path = dist / name
        record = recorded_files.get(name)
        actual = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        if record != actual:
            raise RuntimeError(
                f"shader toolchain file {name} does not match {SHADER_TOOLCHAIN_MANIFEST}"
            )
        file_records[name] = actual
    loader = (dist / SHADER_TOOLCHAIN_LOADER).read_text(encoding="utf-8")
    lowered = loader.lower()
    if "</script" in lowered or "<!--" in lowered:
        raise RuntimeError("shader toolchain loader cannot be inlined into the portable page")
    modules: dict[str, dict[str, object]] = {}
    payload: dict[str, bytes] = {}
    for key, name, _mime in SHADER_TOOLCHAIN_MODULES:
        raw = (dist / name).read_bytes()
        compressed = gzip_bytes(raw)
        payload[key] = compressed
        modules[name] = {
            "rawSha256": file_records[name]["sha256"],
            "rawBytes": file_records[name]["bytes"],
            "gzipSha256": sha256_bytes(compressed),
            "gzipBytes": len(compressed),
        }
    return {
        "version": token,
        "loader": loader,
        "payload": payload,
        "manifest": {
            "version": token,
            "loader": {"name": SHADER_TOOLCHAIN_LOADER, **file_records[SHADER_TOOLCHAIN_LOADER]},
            "manifest": {
                "name": SHADER_TOOLCHAIN_MANIFEST,
                "sha256": sha256_file(manifest_path),
                "bytes": manifest_path.stat().st_size,
                "emscripten": toolchain_manifest["emscripten"],
                "sources": toolchain_manifest["sources"],
            },
            "modules": modules,
            "build": verified_component_identity(
                root, manifest_path, SHADER_TOOLCHAIN_IDENTITY_ROLE, common_identity
            ),
        },
    }


def shader_toolchain_bootstrap(toolchain: dict[str, object] | None, profile: dict) -> tuple[str, str, str]:
    """The three portable-bootstrap fragments of the toolchain (empty without one)."""
    if toolchain is None:
        return "", "", ""
    version = json.dumps(toolchain["version"], ensure_ascii=True)
    profile_attribute = json.dumps(profile["id"], ensure_ascii=True)
    globals_fragment = (
        "      // The inline shader toolchain loader below (contract C7) reads the\n"
        "      // Blob URLs from this object once __gaiusPortableAssetsReady resolves.\n"
        f"      window.__gaiusShaderToolchainUrls = {{version: {version}}};\n"
    )
    ready_lines = ["        const shaderToolchainBlobs = await Promise.all([\n"]
    for key, _name, mime in SHADER_TOOLCHAIN_MODULES:
        ready_lines.append(
            f"          decodeAsset({json.dumps(shader_toolchain_asset(key))}, "
            f"{{gunzip: true, blobType: {json.dumps(mime)}}}),\n"
        )
    ready_lines.append("        ]);\n")
    ready_lines.append("        Object.assign(window.__gaiusShaderToolchainUrls, {\n")
    for index, (key, _name, _mime) in enumerate(SHADER_TOOLCHAIN_MODULES):
        ready_lines.append(
            f"          {key}: URL.createObjectURL(shaderToolchainBlobs[{index}]),\n"
        )
    ready_lines.append("        });\n")
    loader_fragment = (
        f"  <script data-gaius-shader-toolchain data-profile={profile_attribute} "
        f"data-gaius-portable-inline=\"1\">\n"
        f"{toolchain['loader']}"
        + ("" if str(toolchain["loader"]).endswith("\n") else "\n")
        + "  </script>\n"
    )
    return globals_fragment, "".join(ready_lines), loader_fragment


def shader_toolchain_asset(key: str) -> str:
    return f"shader-{key}"


# The v0.4 runtime the launcher includes through one tag (port/web/boot/gaius-boot.js). A
# portable page has no sibling files, so the tag is replaced by the boot script and every
# module it would load, inlined in load order; the kernel worker becomes an inert text block
# the kernel runtime starts from a Blob URL.
BOOT_TAG = '  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n'
BOOT_SOURCE = Path("port/web/boot/gaius-boot.js")
KERNEL_WORKER_SOURCE = Path("port/web/kernels/kernel-worker.js")
KERNEL_RUNTIME_MODULES = ("kernels/kernel-policy.js", "kernels/kernel-runtime.js")
QUALITY_MODULES = (
    "runtime/quality/gpu-caps.js",
    "runtime/quality/quality-profile.js",
    "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js",
    "runtime/quality/post-chain.js",
    "runtime/quality/quality-runtime.js",
)
# Scripts the integrated server Worker imports: the worldgen job codec and facade, and the kernel
# policy and runtime for ?worldgenKernelHost=worker. Inert text on the page, never executed there.
SERVER_WORKER_MODULES = (
    "kernels/kernel-policy.js",
    "kernels/kernel-runtime.js",
    "kernels/worldgen-job.js",
    "kernels/worldgen-kernel.js",
)


def kernel_job_modules(web: Path) -> list[str]:
    """Kernel job codecs (port/web/kernels/*-job.js), whatever kernels exist."""
    return sorted(
        f"kernels/{path.name}" for path in (web / "kernels").glob("*-job.js") if path.is_file()
    )


def inline_safe(text: str, label: str) -> str:
    lowered = text.lower()
    if "</script" in lowered or "<!--" in lowered:
        raise RuntimeError(f"{label} cannot be inlined into the portable page")
    return text if text.endswith("\n") else text + "\n"


def runtime_bootstrap(root: Path) -> tuple[str, dict[str, str]]:
    """The inline replacement of the launcher's gaius-boot.js tag and its module hashes."""
    web = root / "port" / "web"
    boot = root / BOOT_SOURCE
    if not boot.is_file():
        return "", {}
    modules: list[tuple[str, str]] = []
    for name in KERNEL_RUNTIME_MODULES + tuple(kernel_job_modules(web)) + QUALITY_MODULES:
        path = web / name
        if path.is_file():
            modules.append((name, inline_safe(path.read_text(encoding="utf-8"), name)))
    hashes = {name: sha256_bytes(source.encode("utf-8")) for name, source in modules}
    inline_names = json.dumps({name: True for name, _source in modules}, ensure_ascii=True,
                              sort_keys=True, separators=(",", ":"))
    parts = [
        '  <script data-gaius-boot="v1" data-gaius-portable-inline="1">\n'
        "    window.__gaiusBootPortable = true;\n"
        f"    window.__gaiusBootInline = {inline_names};\n"
        "  </script>\n"
    ]
    for name, source in modules:
        parts.append(f'  <script data-gaius-boot-module="{name}">\n{source}  </script>\n')
    # The integrated server Worker imports these from the page's gaius-kernel-port message
    # (gaius-boot.js serverWorkerKernelScripts): a Worker started from a Blob has no sibling
    # files, so the page carries them as inert text the boot script hands over as sources.
    for name in SERVER_WORKER_MODULES:
        path = web / name
        if not path.is_file():
            continue
        source = inline_safe(path.read_text(encoding="utf-8"), name)
        hashes[f"server-worker/{name}"] = sha256_bytes(source.encode("utf-8"))
        parts.append(
            f'  <script type="text/plain" data-gaius-worker-script="{name}">\n{source}  </script>\n'
        )
    worker = root / KERNEL_WORKER_SOURCE
    if worker.is_file():
        worker_text = inline_safe(worker.read_text(encoding="utf-8"), "kernel-worker.js")
        hashes["kernels/kernel-worker.js"] = sha256_bytes(worker_text.encode("utf-8"))
        parts.append(
            '  <script type="text/plain" id="gaius-kernel-worker-source">\n' + worker_text + "  </script>\n"
        )
    boot_text = inline_safe(boot.read_text(encoding="utf-8"), "gaius-boot.js")
    hashes["gaius-boot.js"] = sha256_bytes(boot_text.encode("utf-8"))
    parts.append('  <script data-gaius-boot="v1" data-gaius-portable-inline="1">\n' + boot_text + "  </script>\n")
    return "".join(parts), hashes


def load_portable_kernels(dist: Path, payload: "PortablePayload") -> tuple[dict, dict]:
    """Embeds the wasm kernels listed in dist/kernels/kernels.json (both builds of each).

    Returns the page index ({name: {kinds, memory, variants: {variant: asset}}}) and the
    manifest record; both are empty when the dist carries no kernels (the page then keeps
    every vanilla path).
    """
    manifest_path = dist / "kernels" / "kernels.json"
    if not manifest_path.is_file():
        return {}, {}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"kernel manifest is invalid: {manifest_path}") from exc
    kernels = manifest.get("kernels") if isinstance(manifest, dict) else None
    if not isinstance(kernels, dict):
        raise RuntimeError(f"kernel manifest has no kernels: {manifest_path}")
    index: dict[str, dict[str, object]] = {}
    record: dict[str, dict[str, object]] = {}
    for name in sorted(kernels):
        entry = kernels[name]
        if not re.fullmatch(r"[a-z0-9_]+", name) or not isinstance(entry, dict):
            raise RuntimeError(f"kernel manifest entry is invalid: {name}")
        variants: dict[str, str] = {}
        variant_records: dict[str, dict[str, object]] = {}
        for variant in ("simd", "baseline"):
            spec = (entry.get("variants") or {}).get(variant)
            if not isinstance(spec, dict):
                continue
            path = require_nonempty(dist / "kernels" / str(spec.get("file", "")))
            raw = path.read_bytes()
            if spec.get("sha256") not in (None, sha256_bytes(raw)):
                raise RuntimeError(f"kernel {path.name} does not match kernels.json")
            asset = f"kernel-{name}-{variant}"
            compressed = gzip_bytes(raw)
            payload.add(asset, compressed, gzipped=True)
            variants[variant] = asset
            variant_records[variant] = {
                "rawSha256": sha256_bytes(raw), "rawBytes": len(raw),
                "gzipSha256": sha256_bytes(compressed), "gzipBytes": len(compressed),
            }
        if not variants:
            continue
        index[name] = {"kinds": list(entry.get("kinds") or []), "variants": variants}
        if isinstance(entry.get("memory"), dict):
            index[name]["memory"] = entry["memory"]
        record[name] = variant_records
    return index, record


def manifest_path_for(output: Path) -> Path:
    if output.name == "Gaius.html":
        return output.with_name(MANIFEST_NAME)
    if output.suffix:
        return output.with_name(f"{output.stem}.manifest.json")
    return output.with_name(f"{output.name}.manifest.json")


def verified_component_identity(
    root: Path,
    artifact: Path,
    role: str,
    expected: dict[str, object],
) -> dict[str, object]:
    sidecar = build_identity.sidecar_path(artifact)
    record = build_identity.verify_sidecar(
        root,
        role,
        artifact,
        sidecar,
        expected_common=expected,
    )
    for key in ("profile", "source", "protocol", "overlay", "compatibilitySha256"):
        if record.get(key) != expected.get(key):
            raise RuntimeError(
                f"portable component {artifact.name} has mismatched {key} identity"
            )
    return {
        "role": role,
        "identitySha256": record["identitySha256"],
        "compatibilitySha256": record["compatibilitySha256"],
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
    }


def verified_compiler_profile(
    root: Path,
    artifact: Path,
    role: str,
    pom: Path,
    resources: list[Path],
) -> dict[str, object]:
    sidecar = compiler_profile.default_output(artifact)
    expected = compiler_profile.create_record(
        root,
        role,
        artifact,
        pom,
        resources,
        True,
    )
    try:
        actual = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"compiler profile is missing or invalid: {sidecar}") from exc
    if actual != expected:
        raise RuntimeError(f"compiler profile does not match current inputs: {sidecar}")
    return {
        "profileSha256": expected["profileSha256"],
        "sidecarSha256": sha256_file(sidecar),
        "sidecarBytes": sidecar.stat().st_size,
        "optimizationLevel": expected["compiler"]["optimizationLevel"],
        "minifying": expected["compiler"]["minifying"],
        "shortFileNames": expected["compiler"]["shortFileNames"],
        "assertionsRemoved": expected["compiler"]["assertionsRemoved"],
    }


# The portable bootstrap. A plain string with __GAIUS_PORTABLE_*__ placeholders (no f-string, so
# the JavaScript keeps its own braces). It runs after the payload elements were parsed.
PORTABLE_BOOTSTRAP = r'''  <script data-gaius-portable="1">
    (() => {
      const portableManifest = __GAIUS_PORTABLE_MANIFEST__;
      const portablePayload = __GAIUS_PORTABLE_PAYLOAD__;
      const portableKernelIndex = __GAIUS_PORTABLE_KERNELS__;
      const workerSource = __GAIUS_PORTABLE_WORKER_SOURCE__;
      const embeddedRelayNodes = __GAIUS_PORTABLE_RELAY_NODES__;

      // gaius-b7-v1 decoder. Runs in a Worker started from this function's source (or on this
      // thread when Workers are unavailable): 7 bits per character, U+0080..U+0083 stand for
      // the 7-bit values 0x00, 0x0A, 0x0D and 0x3C; every 8 characters are 7 bytes. A gzip asset is
      // inflated with DecompressionStream while its parts arrive.
      function gaiusPortableDecoder(scope) {
        const table = new Uint8Array(256);
        for (let code = 0; code < 128; code++) table[code] = code;
        table[0x80] = 0x00;
        table[0x81] = 0x0A;
        table[0x82] = 0x0D;
        table[0x83] = 0x3C;
        const jobs = new Map();
        function decodePart(text, job) {
          const blocks = text.length >> 3;
          let count = blocks * 7;
          if (job.received + count > job.bytes) count = Math.max(0, job.bytes - job.received);
          const out = new Uint8Array(blocks * 7);
          for (let i = 0, j = 0; i < blocks * 8; i += 8, j += 7) {
            const g0 = table[text.charCodeAt(i)];
            const g1 = table[text.charCodeAt(i + 1)];
            const g2 = table[text.charCodeAt(i + 2)];
            const g3 = table[text.charCodeAt(i + 3)];
            const g4 = table[text.charCodeAt(i + 4)];
            const g5 = table[text.charCodeAt(i + 5)];
            const g6 = table[text.charCodeAt(i + 6)];
            const g7 = table[text.charCodeAt(i + 7)];
            out[j] = (g0 << 1) | (g1 >> 6);
            out[j + 1] = ((g1 & 63) << 2) | (g2 >> 5);
            out[j + 2] = ((g2 & 31) << 3) | (g3 >> 4);
            out[j + 3] = ((g3 & 15) << 4) | (g4 >> 3);
            out[j + 4] = ((g4 & 7) << 5) | (g5 >> 2);
            out[j + 5] = ((g5 & 3) << 6) | (g6 >> 1);
            out[j + 6] = ((g6 & 1) << 7) | g7;
          }
          job.received += count;
          return count === out.length ? out : out.subarray(0, count);
        }
        async function finish(job) {
          let value;
          if (job.writer) {
            await job.writer.close();
            value = await job.result;
          } else {
            const joined = new Uint8Array(job.received);
            let at = 0;
            for (const chunk of job.chunks) {
              joined.set(chunk, at);
              at += chunk.length;
            }
            value = job.blobType ? new Blob([joined], {type: job.blobType}) : joined.buffer;
          }
          if (job.received !== job.bytes) throw new Error("portable asset is truncated");
          return value;
        }
        scope.onmessage = (event) => {
          const message = event.data;
          const job = jobs.get(message.id);
          try {
            if (message.type === "begin") {
              const next = {bytes: message.bytes, received: 0, chunks: [], blobType: message.blobType,
                writer: null, result: null};
              if (message.gunzip) {
                const stream = new DecompressionStream("gzip");
                next.writer = stream.writable.getWriter();
                const response = new Response(stream.readable);
                next.result = message.blobType
                  ? response.blob().then((blob) => new Blob([blob], {type: message.blobType}))
                  : response.arrayBuffer();
                next.result.catch(() => {});
              }
              jobs.set(message.id, next);
            } else if (message.type === "part" && job) {
              const bytes = decodePart(message.text, job);
              if (job.writer) job.writer.write(bytes).catch(() => {});
              else job.chunks.push(bytes);
            } else if (message.type === "end" && job) {
              jobs.delete(message.id);
              finish(job).then((value) => {
                scope.postMessage({type: "done", id: message.id, value},
                  value instanceof ArrayBuffer ? [value] : []);
              }, (error) => {
                scope.postMessage({type: "failed", id: message.id, message: String(error && error.message || error)});
              });
            }
          } catch (error) {
            jobs.delete(message.id);
            scope.postMessage({type: "failed", id: message.id, message: String(error && error.message || error)});
          }
        };
      }

      const decoder = (() => {
        const pending = new Map();
        const deliver = (message) => {
          const entry = pending.get(message.id);
          if (!entry) return;
          pending.delete(message.id);
          if (message.type === "done") entry.resolve(message.value);
          else entry.reject(new Error("Could not unpack the portable Gaius build: " + message.message));
        };
        let post = null;
        try {
          if (typeof DecompressionStream !== "function") {
            throw new Error("This browser cannot open the portable Gaius build");
          }
          const url = URL.createObjectURL(new Blob(
            ["(" + gaiusPortableDecoder.toString() + ")(self);"], {type: "text/javascript"}));
          const worker = new Worker(url, {name: "gaius-portable-decoder"});
          worker.onmessage = (event) => deliver(event.data);
          worker.onerror = (event) => {
            const message = String(event && event.message || "decoder worker failed");
            Array.from(pending.keys()).forEach((id) => deliver({type: "failed", id, message}));
          };
          post = (message) => worker.postMessage(message);
        } catch (_) {
          const scope = {postMessage: (message) => Promise.resolve().then(() => deliver(message))};
          gaiusPortableDecoder(scope);
          post = (message) => scope.onmessage({data: message});
        }
        let nextId = 1;
        let queue = Promise.resolve();
        const cache = new Map();
        // Parts are read in priority order, one asset after another; each part leaves the DOM
        // as soon as it was handed to the decoder.
        function decode(name, options) {
          if (cache.has(name)) return cache.get(name);
          const info = portablePayload.assets[name];
          if (!info) return Promise.reject(new Error("portable asset is missing: " + name));
          const id = nextId++;
          const result = new Promise((resolve, reject) => pending.set(id, {resolve, reject}));
          const run = async () => {
            try {
              post({type: "begin", id, bytes: info.bytes, gunzip: !!options.gunzip, blobType: options.blobType || null});
              const parts = document.querySelectorAll('script[data-gaius-asset="' + name + '"]');
              if (parts.length !== info.parts) throw new Error("portable asset " + name + " has missing parts");
              for (let index = 0; index < parts.length; index++) {
                const element = parts[index];
                const text = element.textContent;
                element.textContent = "";
                if (element.parentNode) element.parentNode.removeChild(element);
                post({type: "part", id, text});
                if ((index & 3) === 3) {
                  await new Promise((resolve) => setTimeout(resolve, 0));
                }
              }
              post({type: "end", id});
            } catch (error) {
              deliver({type: "failed", id, message: String(error && error.message || error)});
            }
          };
          queue = queue.then(run, run);
          cache.set(name, result);
          return result;
        }
        return {decode};
      })();
      const decodeAsset = (name, options) => decoder.decode(name, options || {});

      const configuredRelayNodes = Array.isArray(window.__gaiusBridgeUrls)
        ? window.__gaiusBridgeUrls
        : (window.__gaiusBridgeUrls ? [window.__gaiusBridgeUrls] : []);
      window.__gaiusBridgeUrls = embeddedRelayNodes.concat(configuredRelayNodes);
      window.__gaiusPortableManifest = portableManifest;
      window.__gaiusPortablePayload = portablePayload;
      window.__gaiusPortableBuild = true;
      __GAIUS_PORTABLE_TOOLCHAIN_GLOBALS__
      if (portableKernelIndex) {
        const kernels = {};
        Object.keys(portableKernelIndex).forEach((name) => {
          const entry = portableKernelIndex[name];
          const variants = {};
          Object.keys(entry.variants).forEach((variant) => {
            const asset = entry.variants[variant];
            variants[variant] = {load: () => decodeAsset(asset, {gunzip: true})};
          });
          kernels[name] = {kinds: entry.kinds, memory: entry.memory, variants};
        });
        window.__gaiusPortableKernels = kernels;
      }
      const portableBridgeTrace = window.__gaiusPortableBridgeTrace ||
        (window.__gaiusPortableBridgeTrace = []);
      const portablePendingLocalPorts = window.__gaiusPortablePendingLocalPorts ||
        (window.__gaiusPortablePendingLocalPorts = new Map());
      const tracePortableBridge = (event, detail) => {
        portableBridgeTrace.push(Object.assign({event, at: Date.now()}, detail || {}));
        if (portableBridgeTrace.length > 256) portableBridgeTrace.splice(0, portableBridgeTrace.length - 256);
      };
      const consumePortablePendingLocalPorts = () => {
        const bridge = window.__gaiusNettyBridge || window.__gaiusNettyBridgeBootstrapState;
        if (!bridge || typeof bridge.registerLocalPort !== "function") return false;
        let consumed = false;
        portablePendingLocalPorts.forEach((pending, sessionId) => {
          if (!pending || !pending.port) {
            portablePendingLocalPorts.delete(sessionId);
            return;
          }
          let result = false;
          try {
            result = bridge.registerLocalPort(
              String(sessionId), pending.port, String(pending.launchGeneration || ""));
          } catch (error) {
            tracePortableBridge("registerLocalPort-throw", {
              sessionId: String(sessionId),
              launchGeneration: String(pending.launchGeneration || ""),
              error: String(error && (error.stack || error.message) || error),
            });
          }
          tracePortableBridge("registerLocalPort-result", {
            sessionId: String(sessionId),
            launchGeneration: String(pending.launchGeneration || ""),
            result: !!result,
            bridgeReady: true,
          });
          if (result) {
            portablePendingLocalPorts.delete(sessionId);
            consumed = true;
          }
        });
        return consumed;
      };
      // Keep the generated transport untouched, but normalize the one
      // integrated-server wildcard endpoint at the bridge boundary.  Some
      // TeaVM socket paths resolve the synthetic local name to 0.0.0.0 before
      // invoking the JS bridge; the page registry already contains the active
      // session and its MessagePort by this point.
      const installLocalBridgeHostPatch = () => {
        const wrap = (bridge) => {
          if (!bridge || typeof bridge.open !== "function") return false;
          if (bridge.__gaiusLocalHostPatch) return true;
          const originalOpen = bridge.open;
          bridge.open = function(id, host, port) {
            let effectiveHost = host;
            if ((String(host) === "0.0.0.0" || String(host).toLowerCase() === "localhost") && Number(port) === 25565) {
              let session = "";
              const workers = window.__gaiusSingleplayerWorkers;
              if (workers && typeof workers.forEach === "function") workers.forEach((worker, key) => {
                if (!session && /^[a-f0-9]{32}$/.test(String(key || "")) && worker && !worker.__gaiusTerminal) session = String(key);
              });
              const direct = String(window.__gaiusServerSessionId || "");
              if (!session && /^[a-f0-9]{32}$/.test(direct)) session = direct;
              if (!session) {
                const ports = window.__gaiusLocalServerPorts;
                if (ports && typeof ports.forEach === "function") ports.forEach((value, key) => { if (!session && /^[a-f0-9]{32}$/.test(String(key || ""))) session = String(key); });
              }
              if (session) effectiveHost = "client-" + session + ".gaius-local";
            }
            tracePortableBridge("bridge-open", {id,host:String(host),port:Number(port),effectiveHost:String(effectiveHost),session:String(window.__gaiusServerSessionId||""),workers:window.__gaiusSingleplayerWorkers?.size||0,ports:window.__gaiusLocalServerPorts?.size||0});
            return originalOpen.call(this, id, effectiveHost, port);
          };
          bridge.__gaiusLocalHostPatch = true;
          return true;
        };
        let bridge = window.__gaiusNettyBridge;
        if (!bridge && window.__gaiusNettyBridgeBootstrapState && typeof window.__gaiusNettyBridgeBootstrapState.open === "function") {
          try { window.__gaiusNettyBridge = window.__gaiusNettyBridgeBootstrapState; bridge = window.__gaiusNettyBridge; } catch (_) {}
        }
        if (wrap(bridge)) {
          tracePortableBridge("bridge-init", {bridgeReady: true});
          consumePortablePendingLocalPorts();
          return true;
        }
        const bootstrapState = window.__gaiusNettyBridgeBootstrapState;
        if (wrap(bootstrapState)) {
          tracePortableBridge("bridge-bootstrap", {bridgeReady: true});
          consumePortablePendingLocalPorts();
          return true;
        }
        if (!window.__gaiusPortableBridgeAccessorInstalled) {
          try {
            const descriptor = Object.getOwnPropertyDescriptor(window, "__gaiusNettyBridge");
            if (!descriptor || descriptor.configurable) {
              let value = descriptor ? (descriptor.get ? descriptor.get.call(window) : descriptor.value) : undefined;
              Object.defineProperty(window, "__gaiusNettyBridge", {
                configurable: true, enumerable: descriptor ? descriptor.enumerable : true,
                get() { return value; },
                set(next) { value = next; wrap(next); }
              });
              window.__gaiusPortableBridgeAccessorInstalled = true;
            }
          } catch (_) {}
        }
        const ready = !!wrap(window.__gaiusNettyBridge);
        if (ready) consumePortablePendingLocalPorts();
        return ready;
      };
      installLocalBridgeHostPatch();
      setInterval(() => { installLocalBridgeHostPatch(); consumePortablePendingLocalPorts(); }, 10);
      // Decode order follows need: the client script and its wasm gate the boot, the asset
      // pack comes next (inflated off this thread, so the launcher gets the raw pack), and the
      // singleplayer server last: it is only needed when a world opens.
      const classesBlobPromise = decodeAsset("classes", {gunzip: true, blobType: "text/javascript"});
      const wasmBlobPromise = decodeAsset("wasm", {gunzip: true, blobType: "application/wasm"});
      window.__gaiusVanillaAssetsCompressedPromise = decodeAsset("vanilla", {gunzip: true})
        .then((buffer) => new Uint8Array(buffer));
      // The page must transfer the embedded server bytes to its Worker. A
      // page-created blob:null URL cannot be fetched from a dedicated Worker
      // when the launcher is opened directly via file://.
      window.__gaiusSingleplayerServerGzipDataPromise = decodeAsset("server", {gunzip: false});
      window.__gaiusSingleplayerServerGzipDataPromise.then((buffer) => {
        window.__gaiusSingleplayerServerGzipUrl = URL.createObjectURL(
          new Blob([buffer], {type: "application/gzip"}),
        );
      }, () => {});
      // Bridge the generated Java launcher without recompiling TeaVM: defer
      // only the integrated-server start message until the transferable gzip
      // buffer is ready. The original MessagePort remains untransferred until
      // the native postMessage call below.
      if (typeof Worker === "function" && Worker.prototype &&
          typeof Worker.prototype.postMessage === "function") {
        const nativeWorkerPostMessage = Worker.prototype.postMessage;
        Worker.prototype.postMessage = function(message, transfer) {
          if (window.__gaiusPortableBuild === true && message &&
              message.type === "start" &&
              window.__gaiusSingleplayerServerGzipDataPromise &&
              typeof window.__gaiusSingleplayerServerGzipDataPromise.then === "function") {
            const worker = this;
            const originalTransfer = Array.isArray(transfer) ? transfer.slice() : [];
            const sessionId = String(message.sessionId || "");
            const launchGeneration = String(message.launchGeneration || "");
            const pagePort = worker && worker.__gaiusClientPort;
            if (/^[a-f0-9]{32}$/.test(sessionId) && pagePort && /^[1-9][0-9]*$/.test(launchGeneration)) {
              const ports = window.__gaiusLocalServerPorts ||
                (window.__gaiusLocalServerPorts = new Map());
              const existing = ports.get(sessionId);
              if (!existing) ports.set(sessionId, pagePort);
              window.__gaiusServerSessionId = sessionId;
              window.__gaiusServerLaunchGeneration = launchGeneration;
              portablePendingLocalPorts.set(sessionId, {port: pagePort, launchGeneration});
              tracePortableBridge("start-pre-register", {
                sessionId, launchGeneration, hasPagePort: true,
                existingPort: !!existing, pending: portablePendingLocalPorts.size,
              });
              consumePortablePendingLocalPorts();
            } else {
              tracePortableBridge("start-pre-register", {
                sessionId, launchGeneration, hasPagePort: !!pagePort,
                pending: portablePendingLocalPorts.size,
              });
            }
            window.__gaiusSingleplayerServerGzipDataPromise.then((buffer) => {
              // Transferring detaches the buffer, and the promise resolves to the
              // same buffer for every integrated-server start of this page, so
              // each start transfers its own copy (a second start in the same
              // session otherwise fails with DataCloneError).
              const transferBuffer = buffer instanceof ArrayBuffer ? buffer.slice(0) : buffer;
              const payload = Object.assign({}, message, {
                serverScriptGzipData: transferBuffer,
                serverScriptGzipUrl: null,
              });
              const transferList = originalTransfer.slice();
              if (transferBuffer instanceof ArrayBuffer) transferList.push(transferBuffer);
              nativeWorkerPostMessage.call(worker, payload, transferList);
              tracePortableBridge("start-post-register", {sessionId, launchGeneration, transferred: true});
            }, () => {
              nativeWorkerPostMessage.call(worker, message, originalTransfer);
              tracePortableBridge("start-post-register", {sessionId, launchGeneration, transferred: false});
            });
            return;
          }
          return nativeWorkerPostMessage.call(this, message, transfer);
        };
      }
      window.__gaiusPortableAssetsReady = (async () => {
        const [classesBlob, wasmBlob] = await Promise.all([classesBlobPromise, wasmBlobPromise]);
        window.__gaiusClassesUrl = URL.createObjectURL(classesBlob);
        window.__gaiusHotpathWasmUrl = URL.createObjectURL(wasmBlob);
        window.__gaiusSingleplayerWorkerUrl = URL.createObjectURL(new Blob(
          [workerSource],
          {type: "text/javascript"},
        ));
        __GAIUS_PORTABLE_TOOLCHAIN_READY__
      })();
    })();
  </script>
'''


def build(dist: Path, output: Path, root: Path | None = None) -> None:
    root = (root or Path(__file__).resolve().parents[2]).resolve()
    dist = dist.resolve()
    output = output.resolve()
    if not output.parent.is_dir():
        raise FileNotFoundError(f"portable output directory is missing: {output.parent}")

    profile, relative_profile, profile_bytes = load_version_profile(root)
    build_root = configured_build_root(root)
    common_identity = build_identity.current_build_identity(root)
    if (
        common_identity["profile"]["id"] != profile["id"]
        or common_identity["profile"]["path"] != Path(relative_profile).as_posix()
        or common_identity["profile"]["sha256"] != sha256_bytes(profile_bytes)
    ):
        raise RuntimeError("portable profile does not match the current build identity")
    index = require(dist / "index.html").read_text(encoding="utf-8")
    validate_launcher_profile(index, profile)
    validate_launcher_storage(index, profile)
    classes_js = require_nonempty(dist / "classes.js")
    classes_gzip = require_nonempty(dist / "classes.js.gz")
    classes_hash, classes_gzip_hash = compare_gzip_with_raw(classes_js, classes_gzip)
    server_js = require_nonempty(dist / "singleplayer-server.js")
    server_gzip = require_nonempty(dist / "singleplayer-server.js.gz")
    server_hash, server_gzip_hash = compare_gzip_with_raw(server_js, server_gzip)
    wasm_raw = require_nonempty(dist / "gaius-hotpath.wasm")
    wasm_gzip = require_nonempty(dist / "gaius-hotpath.wasm.gz")
    wasm_hash, wasm_gzip_hash = compare_gzip_with_raw(wasm_raw, wasm_gzip)
    vanilla_gzip = require_nonempty(dist / "vanilla-assets.pack.gz")
    worker_path = require_nonempty(dist / "singleplayer-server-worker.js")
    relay_registry_path = require_nonempty(dist / "relay-nodes.json")
    component_identities = {
        "classesJs": verified_component_identity(
            root, classes_js, "client", common_identity
        ),
        "singleplayerServerJs": verified_component_identity(
            root, server_js, "singleplayer-worker", common_identity
        ),
        "wasmHotpath": verified_component_identity(
            root, wasm_raw, "wasm-hotpath", common_identity
        ),
        "singleplayerWorkerBootstrap": verified_component_identity(
            root, worker_path, "worker-bootstrap", common_identity
        ),
        "vanillaAssetsPack": verified_component_identity(
            root, vanilla_gzip, "vanilla-assets", common_identity
        ),
        "relayRegistry": verified_component_identity(
            root, relay_registry_path, "relay-registry", common_identity
        ),
    }
    metadata = json.loads(
        (root / "port" / "work" / profile["id"] / "version.json").read_text(
            encoding="utf-8"
        )
    )
    asset_index_id = metadata.get("assetIndex", {}).get("id") or metadata.get("assets")
    if not isinstance(asset_index_id, str) or not asset_index_id:
        raise RuntimeError("active version metadata has no asset index")
    generated_resources = build_root / "generated-resources"
    client_compiler = verified_compiler_profile(
        root,
        classes_js,
        "client",
        build_root / "release-generated-pom.xml",
        [
            generated_resources / "dev/gaius/browser/minecraft-resources.txt",
            generated_resources / "dev/gaius/browser/minecraft-embedded-resources.txt",
            root / "port" / "work" / profile["id"] / "assets" / "indexes" / f"{asset_index_id}.json",
            generated_resources / "assets/minecraft/sounds.json",
            generated_resources / "assets/minecraft/font/include/unifont.json",
            generated_resources / "assets/minecraft/font/include/unifont_pua.json",
            vanilla_gzip,
        ],
    )
    worker_compiler = verified_compiler_profile(
        root,
        server_js,
        "singleplayer-worker",
        build_root / "server-worker" / "release-generated-pom.xml",
        [
            build_root
            / "server-worker"
            / "generated-resources/dev/gaius/browser/minecraft-resources.txt"
        ],
    )
    signatures = verify_signatures(dist, profile, classes_hash)
    shader_toolchain = load_shader_toolchain(
        dist, index, profile, classes_js, root, common_identity
    )
    worker = worker_path.read_text(encoding="utf-8")
    relay_registry = json.loads(relay_registry_path.read_text(encoding="utf-8"))
    if (relay_registry.get("kind") != "gaius-relay-registry"
            or relay_registry.get("protocolVersion") != 1
            or not isinstance(relay_registry.get("nodes"), list)):
        raise RuntimeError("portable relay-nodes.json is incompatible")
    relay_nodes = relay_registry["nodes"][:64]

    # Document order is decode order: the client and its wasm first (they gate the boot), the
    # shader toolchain next, then the asset pack, the singleplayer server (only needed when a
    # world opens) and the kernels (decoded when the kernel runtime first loads them).
    payload = PortablePayload()
    payload.add("classes", classes_gzip.read_bytes(), gzipped=True)
    payload.add("wasm", wasm_gzip.read_bytes(), gzipped=True)
    if shader_toolchain is not None:
        for key, _name, _mime in SHADER_TOOLCHAIN_MODULES:
            payload.add(shader_toolchain_asset(key), shader_toolchain["payload"][key], gzipped=True)
    payload.add("vanilla", vanilla_gzip.read_bytes(), gzipped=True)
    payload.add("server", server_gzip.read_bytes(), gzipped=True)
    kernel_index, kernel_record = load_portable_kernels(dist, payload)
    runtime_inline, runtime_hashes = runtime_bootstrap(root)

    manifest = {
        "kind": MANIFEST_KIND,
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "artifact": output.name,
        "profile": profile["id"],
        "profilePath": Path(relative_profile).as_posix(),
        "profileSha256": sha256_bytes(profile_bytes),
        "worldVersion": profile["worldVersion"],
        "worldgenTelemetryMode": profile.get("worldgenTelemetryMode"),
        "storage": profile["storage"],
        "buildIdentity": common_identity,
        "payloadEncoding": PAYLOAD_ENCODING,
        "classesJs": {
            "rawSha256": classes_hash,
            "gzipSha256": classes_gzip_hash,
            "rawBytes": classes_js.stat().st_size,
            "gzipBytes": classes_gzip.stat().st_size,
            "build": component_identities["classesJs"],
            "compiler": client_compiler,
        },
        "singleplayerServerJs": {
            "rawSha256": server_hash,
            "gzipSha256": server_gzip_hash,
            "rawBytes": server_js.stat().st_size,
            "gzipBytes": server_gzip.stat().st_size,
            "build": component_identities["singleplayerServerJs"],
            "compiler": worker_compiler,
        },
        "wasmHotpath": {
            "rawSha256": wasm_hash,
            "gzipSha256": wasm_gzip_hash,
            "rawBytes": wasm_raw.stat().st_size,
            "gzipBytes": wasm_gzip.stat().st_size,
            "build": component_identities["wasmHotpath"],
        },
        "singleplayerWorkerBootstrap": {
            "sha256": sha256_file(worker_path),
            "bytes": worker_path.stat().st_size,
            "build": component_identities["singleplayerWorkerBootstrap"],
        },
        "vanillaAssetsPack": {
            "gzipSha256": sha256_file(vanilla_gzip),
            "gzipBytes": vanilla_gzip.stat().st_size,
            "build": component_identities["vanillaAssetsPack"],
        },
        "relayRegistry": {
            "sha256": sha256_file(relay_registry_path),
            "bytes": relay_registry_path.stat().st_size,
            "build": component_identities["relayRegistry"],
        },
        "signatures": signatures,
    }
    if shader_toolchain is not None:
        manifest["shaderToolchain"] = shader_toolchain["manifest"]
    if runtime_hashes:
        manifest["runtimeModules"] = runtime_hashes
    if kernel_record:
        manifest["kernels"] = kernel_record
    manifest_source = json.dumps(
        manifest,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )
    manifest_text = f"{manifest_source}\n"

    payload_index = json.dumps(payload.index(), ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    kernel_source = json.dumps(kernel_index or None, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    worker_source = json.dumps(worker, ensure_ascii=True)
    relay_nodes_source = json.dumps(relay_nodes, ensure_ascii=True, separators=(",", ":"))
    toolchain_globals, toolchain_ready, toolchain_loader = shader_toolchain_bootstrap(
        shader_toolchain, profile
    )
    bootstrap = (
        PORTABLE_BOOTSTRAP
        .replace("__GAIUS_PORTABLE_MANIFEST__", manifest_source)
        .replace("__GAIUS_PORTABLE_PAYLOAD__", payload_index)
        .replace("__GAIUS_PORTABLE_KERNELS__", kernel_source)
        .replace("__GAIUS_PORTABLE_WORKER_SOURCE__", worker_source)
        .replace("__GAIUS_PORTABLE_RELAY_NODES__", relay_nodes_source)
        .replace("      __GAIUS_PORTABLE_TOOLCHAIN_GLOBALS__\n", toolchain_globals)
        .replace("        __GAIUS_PORTABLE_TOOLCHAIN_READY__\n", toolchain_ready)
    )
    if "__GAIUS_PORTABLE_" in bootstrap:
        raise RuntimeError("portable bootstrap template has an unfilled placeholder")
    marker = "  <script>\n    if (typeof Error === \"function\")"
    if marker not in index:
        raise RuntimeError("portable launcher insertion point was not found")
    if shader_toolchain is not None:
        # The page's own loader tag would run before the portable bootstrap
        # and fall back to sibling files; the inline copy after the bootstrap
        # awaits the embedded Blob URLs instead.
        index, removed = SHADER_TOOLCHAIN_TAG.subn("", index)
        if removed != 1:
            raise RuntimeError("shader toolchain loader tag was not replaced")
    if runtime_inline and BOOT_TAG in index:
        index = index.replace(BOOT_TAG, runtime_inline, 1)
    elif BOOT_TAG in index:
        # No boot sources next to this script: drop the tag, it would only 404 on file://.
        index = index.replace(BOOT_TAG, "", 1)
    # The payload elements precede the bootstrap, so the bootstrap finds every part when it runs.
    portable = index.replace(marker, payload.html() + bootstrap + toolchain_loader + marker, 1)
    manifest_path = manifest_path_for(output)
    if manifest_path == output:
        raise RuntimeError("portable manifest path collides with HTML output")
    publish_portable_pair(output, portable, manifest_path, manifest_text)
    print(f"Portable Gaius manifest: {manifest_path} ({manifest_path.stat().st_size} bytes)")
    print(f"Portable Gaius HTML: {output} ({output.stat().st_size} bytes)")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    if len(sys.argv) > 1:
        dist = Path(sys.argv[1]).resolve()
    else:
        profile, _, _ = load_version_profile(root)
        dist = configured_dist_directory(root, profile["id"])
    output = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else dist / "Gaius.html"
    build(dist, output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
