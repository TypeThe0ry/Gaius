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

TO_LONG_PATTERN = re.compile(
    r"(?P<arg>[A-Za-z_$][A-Za-z0-9_$]*)=>"
    r"BigInt\.asIntN\(64,BigInt\("
    r"(?P=arg)>=0\?Math\.floor\((?P=arg)\):Math\.ceil\((?P=arg)\)"
    r"\)\)"
)


def patched_to_long(match: re.Match[str]) -> str:
    arg = match.group("arg")
    return (
        f"{arg}=>BigInt.asIntN(64,"
        f"!Number.isFinite({arg})?"
        f"({arg}!=={arg}?BigInt(0):"
        f"({arg}>0?BigInt(\"{LONG_MAX}\"):BigInt(\"{LONG_MIN}\")))"
        f":BigInt({arg}>=0?Math.floor({arg}):Math.ceil({arg})))"
    )


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: postprocess-teavm-js.py <classes.js>", file=sys.stderr)
        return 2

    target = Path(argv[1])
    text = target.read_text(encoding="utf-8")
    patched, count = TO_LONG_PATTERN.subn(patched_to_long, text)

    if count == 0:
        if "Number.isFinite" in text and f'BigInt("{LONG_MAX}")' in text:
            print(f"TeaVM JS already contains finite-safe long conversion: {target}")
            return 0
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
