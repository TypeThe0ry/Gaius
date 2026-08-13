#!/usr/bin/env python3
"""Build a self-contained Gaius HTML file for offline singleplayer use."""

from __future__ import annotations

import base64
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


CHUNK_SIZE = 1_000_000
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


def base64_chunks(path: Path) -> list[str]:
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return [encoded[index:index + CHUNK_SIZE] for index in range(0, len(encoded), CHUNK_SIZE)]


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

    relative_profile = config.get("versionProfile")
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
    return profile, relative_profile, profile_bytes


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


def compare_gzip_with_raw(raw_path: Path, compressed_path: Path) -> tuple[str, str]:
    """Verify that gzip expands to the exact current raw JavaScript bytes."""
    raw_hash = sha256_file(raw_path)
    compressed_hash = sha256_file(compressed_path)
    try:
        with raw_path.open("rb") as raw, gzip.open(compressed_path, "rb") as compressed:
            while True:
                raw_chunk = raw.read(1024 * 1024)
                compressed_chunk = compressed.read(1024 * 1024)
                if raw_chunk != compressed_chunk:
                    raise RuntimeError(
                        "classes.js.gz does not expand to the current classes.js"
                    )
                if not raw_chunk:
                    break
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


def build(dist: Path, output: Path, root: Path | None = None) -> None:
    root = (root or Path(__file__).resolve().parents[2]).resolve()
    dist = dist.resolve()
    output = output.resolve()
    if not output.parent.is_dir():
        raise FileNotFoundError(f"portable output directory is missing: {output.parent}")

    profile, relative_profile, profile_bytes = load_version_profile(root)
    common_identity = build_identity.current_build_identity(root)
    if (
        common_identity["profile"]["id"] != profile["id"]
        or common_identity["profile"]["path"] != Path(relative_profile).as_posix()
        or common_identity["profile"]["sha256"] != sha256_bytes(profile_bytes)
    ):
        raise RuntimeError("portable profile does not match the current build identity")
    index = require(dist / "index.html").read_text(encoding="utf-8")
    validate_launcher_profile(index, profile)
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
    generated_resources = root / "port" / "target" / "generated-resources"
    client_compiler = verified_compiler_profile(
        root,
        classes_js,
        "client",
        root / "port" / "target" / "generated-pom.xml",
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
        root / "port" / "target" / "server-worker" / "generated-pom.xml",
        [
            root
            / "port"
            / "target"
            / "server-worker"
            / "generated-resources/dev/gaius/browser/minecraft-resources.txt"
        ],
    )
    signatures = verify_signatures(dist, profile, classes_hash)
    classes = base64_chunks(classes_gzip)
    server = base64_chunks(server_gzip)
    wasm = base64_chunks(wasm_gzip)
    vanilla = base64_chunks(vanilla_gzip)
    worker = worker_path.read_text(encoding="utf-8")
    relay_registry = json.loads(relay_registry_path.read_text(encoding="utf-8"))
    if (relay_registry.get("kind") != "gaius-relay-registry"
            or relay_registry.get("protocolVersion") != 1
            or not isinstance(relay_registry.get("nodes"), list)):
        raise RuntimeError("portable relay-nodes.json is incompatible")
    relay_nodes = relay_registry["nodes"][:64]

    manifest = {
        "kind": MANIFEST_KIND,
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "artifact": output.name,
        "profile": profile["id"],
        "profilePath": Path(relative_profile).as_posix(),
        "profileSha256": sha256_bytes(profile_bytes),
        "buildIdentity": common_identity,
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
    manifest_source = json.dumps(
        manifest,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )
    manifest_text = f"{manifest_source}\n"

    payload = json.dumps(
        {"classes": classes, "server": server, "wasm": wasm, "vanilla": vanilla},
        ensure_ascii=True,
        separators=(",", ":"),
    )
    worker_source = json.dumps(worker, ensure_ascii=True)
    relay_nodes_source = json.dumps(relay_nodes, ensure_ascii=True, separators=(",", ":"))
    bootstrap = f'''  <script data-gaius-portable="1">
    (() => {{
      const portableManifest = {manifest_source};
      const embedded = {payload};
      const workerSource = {worker_source};
      const embeddedRelayNodes = {relay_nodes_source};

      async function compressedBlob(chunks) {{
        const parts = new Array(chunks.length);
        for (let index = 0; index < chunks.length; index++) {{
          const binary = atob(chunks[index]);
          const bytes = new Uint8Array(binary.length);
          for (let offset = 0; offset < binary.length; offset++) {{
            bytes[offset] = binary.charCodeAt(offset);
          }}
          parts[index] = bytes;
          if ((index & 3) === 3) {{
            await new Promise((resolve) => setTimeout(resolve, 0));
          }}
        }}
        return new Blob(parts, {{type: "application/gzip"}});
      }}

      async function decompress(chunks, mimeType) {{
        if (typeof DecompressionStream !== "function") {{
          throw new Error("This browser cannot open the portable Gaius build");
        }}
        const compressed = await compressedBlob(chunks);
        const stream = compressed
          .stream()
          .pipeThrough(new DecompressionStream("gzip"));
        const decompressed = await new Response(stream).blob();
        return new Blob([decompressed], {{type: mimeType}});
      }}

      const configuredRelayNodes = Array.isArray(window.__gaiusBridgeUrls)
        ? window.__gaiusBridgeUrls
        : (window.__gaiusBridgeUrls ? [window.__gaiusBridgeUrls] : []);
      window.__gaiusBridgeUrls = embeddedRelayNodes.concat(configuredRelayNodes);
      window.__gaiusPortableManifest = portableManifest;
      window.__gaiusPortableBuild = true;
      window.__gaiusVanillaAssetsCompressedPromise = compressedBlob(embedded.vanilla);
      window.__gaiusPortableAssetsReady = (async () => {{
        const [classesBlob, wasmBlob] = await Promise.all([
          decompress(embedded.classes, "text/javascript"),
          decompress(embedded.wasm, "application/wasm"),
        ]);
        window.__gaiusClassesUrl = URL.createObjectURL(classesBlob);
        window.__gaiusHotpathWasmUrl = URL.createObjectURL(wasmBlob);
        window.__gaiusSingleplayerWorkerUrl = URL.createObjectURL(new Blob(
          [workerSource],
          {{type: "text/javascript"}},
        ));
        const serverBlob = await compressedBlob(embedded.server);
        window.__gaiusSingleplayerServerGzipUrl = URL.createObjectURL(
          serverBlob,
        );
      }})();
    }})();
  </script>
'''
    marker = "  <script>\n    if (typeof Error === \"function\")"
    if marker not in index:
        raise RuntimeError("portable launcher insertion point was not found")
    portable = index.replace(marker, bootstrap + marker, 1)
    manifest_path = manifest_path_for(output)
    if manifest_path == output:
        raise RuntimeError("portable manifest path collides with HTML output")
    publish_portable_pair(output, portable, manifest_path, manifest_text)
    print(f"Portable Gaius manifest: {manifest_path} ({manifest_path.stat().st_size} bytes)")
    print(f"Portable Gaius HTML: {output} ({output.stat().st_size} bytes)")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    dist = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "port" / "web" / "dist"
    output = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else dist / "Gaius.html"
    build(dist, output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
