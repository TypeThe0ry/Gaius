#!/usr/bin/env python3
"""Patch TeaVM JavaScript output for browser-only runtime compatibility.

TeaVM 0.15 emits a helper for Java double/float -> long conversion in the form:

    val => BigInt.asIntN(64, BigInt(val >= 0 ? Math.floor(val) : Math.ceil(val)))

Java casts NaN to 0 and saturates infinities, but JavaScript's BigInt(number)
throws for NaN/Infinity. Minecraft can naturally feed NaN into math paths while
recovering camera/interpolation state; on the JVM this is non-fatal, but in the
browser it crashes the client. This post-process keeps the generated output
semantically closer to Java and prevents those fatal browser exceptions.
"""

from __future__ import annotations

import os
import re
import sys
import tempfile
from pathlib import Path


LONG_MAX = "9223372036854775807"
LONG_MIN = "-9223372036854775808"
PATCH_MARKER = "/*gaius-java-finite-long-cast*/"
INTEGRATED_SERVER_PUMP_MARKER = "/*gaius-integrated-server-input-coroutine*/"

TO_LONG_PATTERN = re.compile(
    r"(?P<arg>[A-Za-z_$][A-Za-z0-9_$]*)\s*=>\s*"
    r"BigInt\.asIntN\(\s*64\s*,\s*BigInt\(\s*"
    r"(?P=arg)\s*>=\s*0\s*\?\s*Math\.floor\(\s*(?P=arg)\s*\)\s*"
    r":\s*Math\.ceil\(\s*(?P=arg)\s*\)\s*"
    r"\)\s*\)"
)

SAFE_TO_LONG_PATTERN = re.compile(
    r"(?P<arg>[A-Za-z_$][A-Za-z0-9_$]*)\s*=>\s*"
    r"(?:/\*gaius-java-finite-long-cast\*/)?\s*"
    r"BigInt\.asIntN\(\s*64\s*,\s*!\s*Number\.isFinite\(\s*(?P=arg)\s*\)\s*\?\s*"
    r"\(\s*(?P=arg)\s*!==\s*(?P=arg)\s*\?\s*BigInt\(\s*0\s*\)"
)

INTEGRATED_SERVER_EXPORT_PATTERN = re.compile(
    r"(?P<exports>[A-Za-z_$][A-Za-z0-9_$]*)"
    r"\.pumpIntegratedServerNetworkInput\s*=\s*"
    r"(?P<function>[A-Za-z_$][A-Za-z0-9_$]*)\s*;"
)

RUNTIME_THREAD_START_PATTERN = re.compile(
    r"(?P<call>[A-Za-z_$][A-Za-z0-9_$]*\.\$rt_startThread)\s*="
)

MINIFIED_RUNTIME_THREAD_START_PATTERN = re.compile(
    r"(?P<call>[A-Za-z_$][A-Za-z0-9_$]*\.[A-Za-z_$][A-Za-z0-9_$]*)"
    r"\(\(\)=>\{f\.call\(null,javaArgs\);\},callback\);"
)


def patched_to_long(match: re.Match[str]) -> str:
    arg = match.group("arg")
    return (
        f"{arg}=>{PATCH_MARKER}BigInt.asIntN(64,"
        f"!Number.isFinite({arg})?"
        f"({arg}!=={arg}?BigInt(0):"
        f"({arg}>0?BigInt(\"{LONG_MAX}\"):BigInt(\"{LONG_MIN}\")))"
        f":BigInt({arg}>=0?Math.floor({arg}):Math.ceil({arg})))"
    )


def integrated_server_pump_shim(exports: str, runtime_start_call: str) -> str:
    return f"""
{INTEGRATED_SERVER_PUMP_MARKER}
let $gaiusIntegratedServerPumpRunning = false;
let $gaiusIntegratedServerPumpPending = false;
const $gaiusScheduleIntegratedServerPump = typeof queueMicrotask === 'function'
    ? queueMicrotask
    : callback => Promise.resolve().then(callback);
{exports}.__gaiusStartIntegratedServerPump = () => {{
    const stats = globalThis.__gaiusNetworkStats;
    if (stats) {{
        stats.integratedServerPumpRequests =
            (stats.integratedServerPumpRequests || 0) + 1;
    }}
    if ($gaiusIntegratedServerPumpRunning) {{
        $gaiusIntegratedServerPumpPending = true;
        if (stats) {{
            stats.integratedServerPumpCoalesced =
                (stats.integratedServerPumpCoalesced || 0) + 1;
        }}
        return;
    }}
    const run = () => {{
        $gaiusIntegratedServerPumpPending = false;
        $gaiusIntegratedServerPumpRunning = true;
        if (stats) {{
            stats.integratedServerPumpStarts =
                (stats.integratedServerPumpStarts || 0) + 1;
        }}
        try {{
            {runtime_start_call}(
                () => {exports}.pumpIntegratedServerNetworkInput(),
                result => {{
                    $gaiusIntegratedServerPumpRunning = false;
                    if (result instanceof Error) {{
                        globalThis.__gaiusIntegratedServerPumpError =
                            String(result.stack || result);
                        if (stats) {{
                            stats.integratedServerPumpFailures =
                                (stats.integratedServerPumpFailures || 0) + 1;
                        }}
                    }}
                    if ($gaiusIntegratedServerPumpPending) {{
                        $gaiusScheduleIntegratedServerPump(run);
                    }}
                }}
            );
        }} catch (error) {{
            $gaiusIntegratedServerPumpRunning = false;
            globalThis.__gaiusIntegratedServerPumpError =
                String(error && (error.stack || error) || error);
            if (stats) {{
                stats.integratedServerPumpFailures =
                    (stats.integratedServerPumpFailures || 0) + 1;
            }}
        }}
    }};
    run();
}};
"""


def write_text_atomically(target: Path, text: str) -> None:
    """Replace target only after the complete postprocessed file is durable."""
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


def find_anchored(
    pattern: re.Pattern[str],
    text: str,
    anchor_text: str,
    before: int = 192,
    after: int = 512,
) -> re.Match[str] | None:
    search_offset = 0
    while True:
        anchor = text.find(anchor_text, search_offset)
        if anchor < 0:
            return None
        match = pattern.search(
            text,
            max(0, anchor - before),
            min(len(text), anchor + after),
        )
        if match is not None and match.start() <= anchor < match.end():
            return match
        search_offset = anchor + len(anchor_text)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: postprocess-teavm-js.py <classes.js>", file=sys.stderr)
        return 2

    target = Path(argv[1])
    text = target.read_text(encoding="utf-8")
    patched = text
    messages: list[str] = []

    if PATCH_MARKER in patched or find_anchored(
        SAFE_TO_LONG_PATTERN,
        patched,
        "Number.isFinite",
    ) is not None:
        messages.append(f"TeaVM JS already contains finite-safe long conversion: {target}")
    else:
        match = find_anchored(TO_LONG_PATTERN, patched, "BigInt.asIntN")
        if match is None:
            print(
                f"TeaVM JS long conversion helper was not found in {target}; "
                "the generated runtime shape may have changed.",
                file=sys.stderr,
            )
            return 1
        patched = patched[:match.start()] + patched_to_long(match) + patched[match.end():]
        messages.append(
            f"Patched TeaVM JS finite-safe long conversion in {target} (1 occurrence)."
        )

    worker_export = find_anchored(
        INTEGRATED_SERVER_EXPORT_PATTERN,
        patched,
        ".pumpIntegratedServerNetworkInput=",
        before=128,
        after=256,
    )
    if worker_export is not None:
        if INTEGRATED_SERVER_PUMP_MARKER in patched:
            messages.append(
                f"TeaVM server Worker already contains coroutine input pump: {target}"
            )
        else:
            runtime = RUNTIME_THREAD_START_PATTERN.search(patched)
            if runtime is None:
                runtime = MINIFIED_RUNTIME_THREAD_START_PATTERN.search(patched)
            if runtime is None:
                print(
                    f"TeaVM native thread starter was not found in {target}; "
                    "the generated runtime shape may have changed.",
                    file=sys.stderr,
                )
                return 1
            insert_at = worker_export.end()
            shim = integrated_server_pump_shim(
                worker_export.group("exports"),
                runtime.group("call"),
            )
            patched = patched[:insert_at] + shim + patched[insert_at:]
            messages.append(f"Injected TeaVM server Worker coroutine input pump in {target}.")

    if patched != text:
        write_text_atomically(target, patched)
    for message in messages:
        print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
