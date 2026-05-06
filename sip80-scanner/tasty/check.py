#!/usr/bin/env python3
"""Self-test: compare the inspector's summary.json against expected.json.

usage:  check.py <results-dir> <expected.json>
        exits non-zero if any field disagrees.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: check.py <results-dir> <expected.json>", file=sys.stderr)
        return 2
    results = Path(argv[0]).resolve() / "summary.json"
    expected_path = Path(argv[1]).resolve()
    actual = json.loads(results.read_text(encoding="utf-8"))
    expected = json.loads(expected_path.read_text(encoding="utf-8"))["totals"]

    failures: list[str] = []

    def cmp(label: str, exp, act):
        if exp != act:
            failures.append(f"  {label}: expected {exp}, got {act}")

    cmp("total_incidents",
        expected["total_incidents"], actual["total_incidents"])
    cmp("import_based",
        expected["import_based"], actual["import_based"])
    cmp("total_chars_saved",
        expected["total_chars_saved"], actual["total_chars_saved"])

    for k, v in expected["by_category"].items():
        cmp(f"by_category[{k}]", v, actual["by_category"].get(k, 0))
    for k, v in actual["by_category"].items():
        if k not in expected["by_category"]:
            failures.append(f"  by_category[{k}]: unexpected (got {v})")

    for k, v in expected["by_file"].items():
        cmp(f"by_file[{k}]", v, actual["by_file"].get(k, 0))
    for k, v in actual["by_file"].items():
        if k not in expected["by_file"]:
            failures.append(f"  by_file[{k}]: unexpected (got {v})")

    if failures:
        print("FAIL:")
        for f in failures:
            print(f)
        return 1
    print("PASS: all 39 incidents and category counts match expected.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
