#!/usr/bin/env python3
"""Read the TASTy major.minor version embedded in a Scala 3 jar.

Scans the jar for the first ``*.tasty`` entry, parses its header, and
prints ``major.minor`` (e.g. ``28.8``). Exits non-zero if no .tasty
entry is found or the magic doesn't match.

The TASTy header layout (from `dotty.tools.tasty.TastyFormat`):

  4 bytes  magic   = 0x5C A1 AB 1F
  varnat   majorVersion
  varnat   minorVersion
  varnat   experimentalVersion
  ...

Natural numbers are encoded MSB-first; the *terminating* byte has its
top bit set, continuation bytes have it clear. So for value 28:
  byte = 28 | 0x80 = 0x9C  (one byte, terminator).
"""

from __future__ import annotations

import sys
import zipfile


def read_natural(buf: bytes, idx: int) -> tuple[int, int]:
    n = 0
    while idx < len(buf):
        b = buf[idx]
        idx += 1
        n = (n << 7) | (b & 0x7F)
        if b & 0x80:
            return n, idx
    raise ValueError("truncated natural")


def detect(jar_path: str) -> tuple[int, int] | None:
    with zipfile.ZipFile(jar_path) as z:
        for name in z.namelist():
            if not name.endswith(".tasty"):
                continue
            with z.open(name) as f:
                hdr = f.read(64)
            if len(hdr) < 6 or hdr[:4] != b"\x5C\xA1\xAB\x1F":
                continue
            major, idx = read_natural(hdr, 4)
            minor, idx = read_natural(hdr, idx)
            return major, minor
    return None


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: detect-tasty-version.py <jar>", file=sys.stderr)
        return 2
    res = detect(argv[0])
    if res is None:
        print("no .tasty entry with valid magic found", file=sys.stderr)
        return 1
    major, minor = res
    print(f"{major}.{minor}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
