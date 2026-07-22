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

import re
import sys
from pathlib import Path


LONG_MAX = "9223372036854775807"
LONG_MIN = "-9223372036854775808"
PATCH_MARKER = "/*gaius-java-finite-long-cast*/"

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


def patched_to_long(match: re.Match[str]) -> str:
    arg = match.group("arg")
    return (
        f"{arg}=>{PATCH_MARKER}BigInt.asIntN(64,"
        f"!Number.isFinite({arg})?"
        f"({arg}!=={arg}?BigInt(0):"
        f"({arg}>0?BigInt(\"{LONG_MAX}\"):BigInt(\"{LONG_MIN}\")))"
        f":BigInt({arg}>=0?Math.floor({arg}):Math.ceil({arg})))"
    )


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
    if PATCH_MARKER in text or find_anchored(
        SAFE_TO_LONG_PATTERN,
        text,
        "Number.isFinite",
    ) is not None:
        print(f"TeaVM JS already contains finite-safe long conversion: {target}")
        return 0

    match = find_anchored(TO_LONG_PATTERN, text, "BigInt.asIntN")

    count = 1 if match is not None else 0
    patched = (
        text[:match.start()] + patched_to_long(match) + text[match.end():]
        if match is not None
        else text
    )

    if count == 0:
        print(
            f"TeaVM JS long conversion helper was not found in {target}; "
            "the generated runtime shape may have changed.",
            file=sys.stderr,
        )
        return 1

    target.write_text(patched, encoding="utf-8")
    print(f"Patched TeaVM JS finite-safe long conversion in {target} ({count} occurrence).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
