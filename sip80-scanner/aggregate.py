#!/usr/bin/env python3
"""Aggregate per-run summaries into a single Markdown report.

Reads ``summary-*.json`` and ``findings-*.tsv`` from a results directory and
emits ``summary.md`` plus a combined ``findings.tsv``.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def fmt_int(n: int) -> str:
    return f"{n:,}"


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: aggregate.py <results-dir>", file=sys.stderr)
        return 2
    results = Path(argv[0]).resolve()

    summaries = []
    for p in sorted(results.glob("summary-*.json")):
        summaries.append(json.loads(p.read_text(encoding="utf-8")))

    if not summaries:
        print("no summaries found", file=sys.stderr)
        return 1

    total_inc = sum(s["total_incidents"] for s in summaries)
    total_chars = sum(s["total_chars_saved"] for s in summaries)

    by_pattern: dict[str, dict] = {}
    by_project: dict[str, dict] = {}
    for s in summaries:
        for k, v in s["by_pattern"].items():
            d = by_pattern.setdefault(k, {"incidents": 0, "chars_saved": 0})
            d["incidents"] += v["incidents"]
            d["chars_saved"] += v["chars_saved"]
        for k, v in s["by_project"].items():
            d = by_project.setdefault(k, {"incidents": 0, "chars_saved": 0})
            d["incidents"] += v["incidents"]
            d["chars_saved"] += v["chars_saved"]

    pattern_names = {
        "P1": "Typed val/var/def with redundant prefix",
        "P2": "Default argument with redundant prefix",
        "P3": "Constructor self-prefix in args",
        "P4": "Generic typed apply with redundant prefix",
        "P5": "`case T.X` after typed scrutinee",
    }

    md_path = results / "summary.md"
    with md_path.open("w", encoding="utf-8") as f:
        f.write("# SIP-80 Beneficial-Incident Scan — Summary\n\n")
        f.write(
            "Each row is one place where the source code already writes "
            "`T.X` while the position's expected type is statically `T`, "
            "so SIP-80's `.X` shorthand would apply with zero loss of "
            "information. Character savings are conservatively computed as "
            "`len(prefix) + 1` (the dropped prefix and its trailing dot).\n\n"
        )
        f.write(f"## Totals\n\n")
        f.write(f"- **Incidents:** {fmt_int(total_inc)}\n")
        f.write(f"- **Characters saved:** {fmt_int(total_chars)}\n\n")

        f.write("## By pattern\n\n")
        f.write("| Pattern | Description | Incidents | Chars saved |\n")
        f.write("|---|---|---:|---:|\n")
        for k in sorted(by_pattern):
            v = by_pattern[k]
            f.write(f"| {k} | {pattern_names.get(k, k)} | "
                    f"{fmt_int(v['incidents'])} | {fmt_int(v['chars_saved'])} |\n")

        f.write("\n## By project\n\n")
        f.write("| Project | Incidents | Chars saved |\n")
        f.write("|---|---:|---:|\n")
        for k in sorted(by_project, key=lambda x: -by_project[x]["incidents"]):
            v = by_project[k]
            f.write(f"| `{k}` | {fmt_int(v['incidents'])} | "
                    f"{fmt_int(v['chars_saved'])} |\n")

        f.write("\n## Methodology\n\n")
        f.write(
            "- Source corpus: shallow scan of `.scala`/`.sc` files; "
            "`.git`, `target`, `out`, `node_modules`, `.bloop`, "
            "`.metals`, `.bsp`, `project` directories skipped.\n"
            "- Comments (line and nested block) and string literals "
            "(double-quoted, triple-quoted, and interpolated) are blanked "
            "before regex matching, so no incident comes from prose or "
            "test fixtures.\n"
            "- Five high-confidence patterns are recognised — see "
            "`scan.py` for definitions. Each match must come from a "
            "position where SIP-80's lexical and expected-type rules "
            "actually fire, so this number is a *lower bound*: cases "
            "where the user wrote `import T.*` and used the bare name "
            "are not counted.\n"
            "- Reproduce with `bash run.sh all` from this directory.\n"
        )

    # Combined TSV.
    combined = results / "findings.tsv"
    with combined.open("w", encoding="utf-8") as out:
        first = True
        for tsv in sorted(results.glob("findings-*.tsv")):
            with tsv.open("r", encoding="utf-8") as f:
                header = f.readline()
                if first:
                    out.write(header)
                    first = False
                for line in f:
                    out.write(line)

    print(f"wrote {md_path}")
    print(f"wrote {combined}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
