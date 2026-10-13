#!/usr/bin/env python3
"""Compare local vanilla PNGs with the actual browser STBImage Java source, offline."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
STB = ROOT / "port/overrides/libraries/lwjgl-stb/src/main/java/org/lwjgl/stb"


def write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def synthetic_pngs():
    samples = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
    depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
    width, height = 13, 11
    for color, legal_depths in depths.items():
        for depth in legal_depths:
            for filter_kind in range(5):
                previous = b""
                raw = bytearray()
                stride = max(1, (samples[color] * depth + 7) // 8)
                for row_index in range(height):
                    values = [(pixel * 73 + row_index * 41 + channel * 109) % (1 << depth)
                              for pixel in range(width) for channel in range(samples[color])]
                    if depth == 16:
                        row = b"".join(struct.pack(">H", value) for value in values)
                    elif depth == 8:
                        row = bytes(values)
                    else:
                        row_bytes = bytearray((len(values) * depth + 7) // 8)
                        for index, value in enumerate(values):
                            row_bytes[index * depth // 8] |= value << (8 - depth - index * depth % 8)
                        row = bytes(row_bytes)
                    filtered = bytearray()
                    for index, value in enumerate(row):
                        left = row[index - stride] if index >= stride else 0
                        above = previous[index] if previous else 0
                        corner = previous[index - stride] if previous and index >= stride else 0
                        estimate = left + above - corner
                        distances = [abs(estimate - candidate) for candidate in (left, above, corner)]
                        paeth = (left, above, corner)[distances.index(min(distances))]
                        prediction = (0, left, above, (left + above) // 2, paeth)[filter_kind]
                        filtered.append((value - prediction) & 255)
                    raw.extend(bytes([filter_kind]) + filtered)
                    previous = row
                header = struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, 0)
                png = bytearray(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header))
                if color == 3:
                    palette = bytes(value for index in range(1 << depth)
                                    for value in ((index * 71) & 255, (index * 53) & 255, (index * 19) & 255))
                    png.extend(chunk(b"PLTE", palette))
                    png.extend(chunk(b"tRNS", bytes([0, 123])[:1 << depth]))
                elif color == 0:
                    png.extend(chunk(b"tRNS", struct.pack(">H", 0)))
                elif color == 2:
                    png.extend(chunk(b"tRNS", struct.pack(">HHH", 0, 109, 218)))
                png.extend(chunk(b"gAMA", struct.pack(">I", 45455)))
                png.extend(chunk(b"sRGB", b"\x00"))
                compressed = zlib.compress(raw)
                midpoint = len(compressed) // 2
                png.extend(chunk(b"IDAT", compressed[:midpoint]))
                png.extend(chunk(b"IDAT", compressed[midpoint:]))
                png.extend(chunk(b"IEND", b""))
                yield f"color-{color}-depth-{depth}-filter-{filter_kind}", bytes(png)


def run(command, output, name, environment=None):
    result = subprocess.run([str(value) for value in command], cwd=ROOT, env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
    write_text(output / "logs" / (name + ".log"),
               "$ " + subprocess.list2cmdline([str(value) for value in command]) + "\n"
               + result.stdout + result.stderr + f"\nexit={result.returncode}\n")
    if result.returncode:
        raise RuntimeError(f"{name} failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")
    print(name, "passed", flush=True)
    lines = [line for line in result.stdout.splitlines() if line.startswith("{")]
    return json.loads(lines[-1]) if lines else None


def collect(arguments, output):
    inputs_dir = output / "inputs"
    expected_dir = output / "expected"
    inputs_dir.mkdir(parents=True, exist_ok=True)
    expected_dir.mkdir(parents=True, exist_ok=True)
    profiles = []
    objects_roots = [Path(value) for value in arguments.objects]
    for config_path in sorted((ROOT / "port/versions").glob("*.json")):
        config = json.loads(config_path.read_text(encoding="utf-8"))
        profile = config["id"]
        work = Path(arguments.work) / profile
        index_id = config["official"]["assetIndexId"]
        index_path = work / "assets/indexes" / (str(index_id) + ".json")
        jar_path = work / "client-named.jar"
        if not jar_path.is_file() or not index_path.is_file():
            raise FileNotFoundError(f"missing local jar or asset index for {profile}")
        entries = []
        missing = []
        with zipfile.ZipFile(jar_path) as jar:
            for entry in jar.infolist():
                if entry.filename.lower().endswith(".png") and not entry.is_dir():
                    entries.append(("jar:" + entry.filename, jar.read(entry)))
        jar_count = len(entries)
        index = json.loads(index_path.read_text(encoding="utf-8"))
        object_count = 0
        for name, metadata in sorted(index["objects"].items()):
            if not name.lower().endswith(".png"):
                continue
            digest = metadata["hash"]
            candidates = [work / "assets/objects" / digest[:2] / digest]
            candidates.extend(directory / digest[:2] / digest for directory in objects_roots)
            candidate = next((path for path in candidates if path.is_file()), None)
            if candidate is None:
                missing.append({"name": name, "sha1": digest, "bytes": metadata["size"]})
                continue
            data = candidate.read_bytes()
            if len(data) != metadata["size"] or hashlib.sha1(data).hexdigest() != digest:
                raise AssertionError(f"asset object identity mismatch: {candidate}")
            entries.append(("object:" + name, data))
            object_count += 1
        catalog = []
        parity_rows = []
        benchmark_rows = []
        layouts = {}
        for name, data in entries:
            digest = hashlib.sha256(data).hexdigest()
            path = inputs_dir / (digest + ".png")
            path.write_bytes(data)
            if data[:8] != b"\x89PNG\r\n\x1a\n":
                raise AssertionError(f"not a PNG: {profile} {name}")
            layout = f"color={data[25]},depth={data[24]},interlace={data[28]}"
            layouts[layout] = layouts.get(layout, 0) + 1
            catalog.append({"name": name, "sha256": digest, "bytes": len(data), "layout": layout})
            for desired in range(5):
                row = f"{path.as_posix()}\t{desired}\t{(expected_dir / (digest + '-' + str(desired) + '.bin')).as_posix()}\n"
                parity_rows.append(row)
                if desired == 4:
                    benchmark_rows.append(row)
        directory = output / profile
        write_text(directory / "parity.tsv", "".join(parity_rows))
        write_text(directory / "benchmark.tsv", "".join(benchmark_rows))
        profiles.append({"profile": profile, "jarPngs": jar_count, "objectPngs": object_count,
                         "count": len(entries), "bytes": sum(len(data) for _, data in entries),
                         "uniquePngs": len({entry["sha256"] for entry in catalog}), "layouts": layouts,
                         "missingObjects": missing, "entries": catalog,
                         "jarSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(),
                         "indexSha256": hashlib.sha256(index_path.read_bytes()).hexdigest()})
    synthetic_rows = []
    for name, data in synthetic_pngs():
        path = inputs_dir / (name + ".png")
        path.write_bytes(data)
        for desired in range(5):
            synthetic_rows.append(f"{path.as_posix()}\t{desired}\t{(expected_dir / (name + '-' + str(desired) + '.bin')).as_posix()}\n")
    write_text(output / "synthetic.tsv", "".join(synthetic_rows))
    write_text(output / "catalog.json", json.dumps(profiles, indent=2))
    return profiles


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--work", default=str(ROOT / "port/work"))
    parser.add_argument("--objects", action="append", default=[], help="additional existing objects root")
    parser.add_argument("--rounds", type=int, default=7)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--require-complete-index", action="store_true")
    arguments = parser.parse_args()
    if arguments.rounds < 1:
        parser.error("rounds must be positive")
    output = Path(arguments.out).resolve()
    output.mkdir(parents=True, exist_ok=True)
    profiles = collect(arguments, output)
    classes = output / "java-classes"
    classes.mkdir(exist_ok=True)
    sources = [STB / "STBImage.java", STB / "BrowserPng.java", STB / "PngPixels.java",
               HERE / "MemoryUtil.java", HERE / "PngReference.java"]
    run(["javac", "-J-Xmx3g", "-encoding", "UTF-8", "-d", classes, *sources], output, "javac-reference")
    environment = os.environ.copy()
    environment["CARGO_TARGET_DIR"] = str(output / "target")
    cargo = ["cargo", "build", "--manifest-path", ROOT / "port/native/Cargo.toml", "--offline",
             "--locked", "-j", "4", "--release", "-p", "gaius-png-wasm"]
    if not arguments.skip_build:
        run(cargo, output, "build-native", environment)
        run(cargo + ["--target", "wasm32-unknown-unknown", "--lib"], output, "build-wasm", environment)
    native = output / "target/release" / ("gaius-png-check.exe" if os.name == "nt" else "gaius-png-check")
    wasm = output / "target/wasm32-unknown-unknown/release/gaius_png_wasm.wasm"
    run(["node", HERE / "runtime-check.mjs", wasm, output / "inputs/color-6-depth-8-filter-0.png"],
        output, "runtime-check")
    java = ["java", "-Xmx3g", "-cp", classes, "org.lwjgl.stb.PngReference"]
    results = {"reference": "current browser STBImage Java source on JVM with JDK Inflater and heap allocation shim",
               "referenceSha256": hashlib.sha256((STB / "STBImage.java").read_bytes()).hexdigest(),
               "wasmSha256": hashlib.sha256(wasm.read_bytes()).hexdigest(),
               "rounds": arguments.rounds, "syntheticCases": 375, "profiles": []}
    for profile in [None, *profiles]:
        name = "synthetic" if profile is None else profile["profile"]
        parity = output / "synthetic.tsv" if profile is None else output / name / "parity.tsv"
        run(java + [parity, "1", "write"], output, name + "-java-parity")
        run([native, parity, "1"], output, name + "-native-parity")
        run(["node", HERE / "wasm-check.mjs", wasm, parity, "1"], output, name + "-wasm-parity")
        if profile is not None:
            benchmark = output / name / "benchmark.tsv"
            measurements = {}
            for engine, command in (
                    ("java", java + [benchmark, str(arguments.rounds), "verify"]),
                    ("native", [native, benchmark, str(arguments.rounds)]),
                    ("wasm", ["node", HERE / "wasm-check.mjs", wasm, benchmark, str(arguments.rounds)])):
                measurements[engine] = run(command, output, name + "-" + engine + "-benchmark")
            results["profiles"].append({key: value for key, value in profile.items() if key != "entries"}
                                       | {"measurements": measurements, "parityCases": profile["count"] * 5})
    write_text(output / "results.json", json.dumps(results, indent=2))
    print(json.dumps({"profiles": [{"profile": profile["profile"], "count": profile["count"],
                                   "missing": len(profile["missingObjects"])} for profile in profiles],
                      "allLocalPngsEqual": True, "results": str(output / "results.json")}), flush=True)
    if arguments.require_complete_index and any(profile["missingObjects"] for profile in profiles):
        raise RuntimeError("missing asset objects; local equality passed but complete-index coverage is unavailable")


if __name__ == "__main__":
    main()
