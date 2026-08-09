#!/usr/bin/env python3
"""Build a self-contained Gaius HTML file for offline singleplayer use."""

from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
from pathlib import Path


CHUNK_SIZE = 1_000_000


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


def base64_chunks(path: Path) -> list[str]:
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return [encoded[index:index + CHUNK_SIZE] for index in range(0, len(encoded), CHUNK_SIZE)]


def require(path: Path) -> Path:
    if not path.is_file():
        raise FileNotFoundError(f"missing portable asset: {path}")
    return path


def build(dist: Path, output: Path) -> None:
    index = require(dist / "index.html").read_text(encoding="utf-8")
    classes = base64_chunks(require(dist / "classes.js.gz"))
    server = base64_chunks(require(dist / "singleplayer-server.js.gz"))
    wasm = base64_chunks(require(dist / "gaius-hotpath.wasm.gz"))
    vanilla = base64_chunks(require(dist / "vanilla-assets.pack.gz"))
    worker = require(dist / "singleplayer-server-worker.js").read_text(encoding="utf-8")
    relay_registry = json.loads(require(dist / "relay-nodes.json").read_text(encoding="utf-8"))
    if (relay_registry.get("kind") != "gaius-relay-registry"
            or relay_registry.get("protocolVersion") != 1
            or not isinstance(relay_registry.get("nodes"), list)):
        raise RuntimeError("portable relay-nodes.json is incompatible")
    relay_nodes = relay_registry["nodes"][:64]

    payload = json.dumps(
        {"classes": classes, "server": server, "wasm": wasm, "vanilla": vanilla},
        ensure_ascii=True,
        separators=(",", ":"),
    )
    worker_source = json.dumps(worker, ensure_ascii=True)
    relay_nodes_source = json.dumps(relay_nodes, ensure_ascii=True, separators=(",", ":"))
    bootstrap = f'''  <script data-gaius-portable="1">
    (() => {{
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
    write_text_atomically(output, portable)
    print(f"Portable Gaius HTML: {output} ({output.stat().st_size} bytes)")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    dist = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "port" / "web" / "dist"
    output = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else dist / "Gaius.html"
    build(dist, output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
