#!/usr/bin/env python3
"""SIP-80 beneficial-incident scanner.

Walks .scala source files and counts places where the user literally wrote
``T.X`` while the position's expected type is statically ``T``, so SIP-80's
``.X`` shorthand would apply.

Five high-confidence syntactic patterns are detected:

  P1  val/var/def/lazy val with redundant prefix
        val c: Color = Color.Red
  P2  default argument with redundant prefix
        def f(c: Color = Color.Red)
  P3  constructor self-prefix in args
        Shape(Shape.Geometry.Circle, Shape.Color.Red)
  P4  generic typed apply with redundant prefix
        Seq[Color](Color.Red, Color.Green)
  P5  match case with redundant prefix after a typed scrutinee
        val v: Color = ...; v match { case Color.Red => ... }

For each match we count one incident and len(prefix) + 1 characters saved
(the prefix and its trailing dot).

Run modes
---------
    python3 scan.py --self-test
        Run against test-cases/ and check counts vs expected.json.

    python3 scan.py --root <dir> [--root <dir2>...] [--project <name>]
        Scan one or more directory roots; emit findings.tsv and summary.json
        on stdout (or under --out-dir).

    python3 scan.py --corpus <list.txt> --corpus-root <dir> --out-dir <dir>
        Scan every project listed in <list.txt> located under <corpus-root>.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable


# ---------------------------------------------------------------------------
# Token-aware blanking of comments and string literals.
#
# We replace content inside comments/strings with spaces (preserving length and
# newlines) so the regex pass cannot match anything inside text. Scala 3 allows
# nested block comments and triple-quoted strings, both handled here.
# ---------------------------------------------------------------------------

_BLANK_KEEP_NEWLINE = str.maketrans({c: " " for c in (chr(i) for i in range(256))
                                     if c != "\n"})


def blank_strings_and_comments(src: str) -> str:
    """Return ``src`` with comment/string content replaced by spaces.

    Length and newlines are preserved so file:line:col offsets stay valid.
    """
    out = list(src)
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        # Line comment
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue

        # Block comment (nested allowed in Scala 3)
        if c == "/" and nxt == "*":
            depth = 1
            out[i] = " "
            out[i + 1] = " "
            j = i + 2
            while j < n and depth > 0:
                if j + 1 < n and src[j] == "/" and src[j + 1] == "*":
                    depth += 1
                    out[j] = " "
                    out[j + 1] = " "
                    j += 2
                elif j + 1 < n and src[j] == "*" and src[j + 1] == "/":
                    depth -= 1
                    out[j] = " "
                    out[j + 1] = " "
                    j += 2
                else:
                    if src[j] != "\n":
                        out[j] = " "
                    j += 1
            i = j
            continue

        # Triple-quoted string (raw or interpolated)
        if (c == '"' and src[i:i + 3] == '"""') or \
           (c.isalpha() and re.match(r"[A-Za-z_]\w*\"\"\"", src[i:i + 32] or "")):
            # Find the start quote and the matching end. We may have a prefix
            # like s, raw, f, etc.
            m = re.match(r'([A-Za-z_]\w*)?"""', src[i:])
            if m:
                start = i + m.end()
                end = src.find('"""', start)
                if end == -1:
                    end = n
                # Blank everything between (but keep prefix and quotes for
                # length, blanking only inner content).
                for k in range(start, end):
                    if src[k] != "\n":
                        out[k] = " "
                # Skip past closing quotes
                i = end + 3 if end + 3 <= n else n
                continue

        # Regular double-quoted string (raw or interpolated)
        if c == '"' or (c.isalpha() and re.match(r'[A-Za-z_]\w*"', src[i:i + 16] or "")):
            m = re.match(r'([A-Za-z_]\w*)?"', src[i:])
            if m:
                start = i + m.end()
                # Walk to closing quote, honoring backslash escapes.
                j = start
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    if src[j] == "\n":
                        # unterminated; bail at line end
                        break
                    j += 1
                for k in range(start, j):
                    out[k] = " "
                i = j + 1 if j < n and src[j] == '"' else j
                continue

        # Char literal 'x' or '\n' etc. Conservative: only blank inside if the
        # closing quote is within a few chars (to avoid eating apostrophes
        # used in identifiers like a' which Scala does not allow anyway).
        if c == "'" and i + 2 < n:
            if src[i + 1] == "\\" and i + 3 < n and src[i + 3] == "'":
                out[i + 1] = " "
                out[i + 2] = " "
                i += 4
                continue
            if src[i + 2] == "'":
                out[i + 1] = " "
                i += 3
                continue

        i += 1
    return "".join(out)


# ---------------------------------------------------------------------------
# Argument-list scanning. Python's stdlib re cannot recurse, so we walk
# parens/brackets manually.
# ---------------------------------------------------------------------------

def find_balanced(src: str, start: int, open_ch: str, close_ch: str) -> int:
    """Given that src[start] == open_ch, return index of matching close_ch
    (one past the open). Returns -1 on mismatch.
    """
    if start >= len(src) or src[start] != open_ch:
        return -1
    depth = 1
    i = start + 1
    while i < len(src) and depth > 0:
        ch = src[i]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i
        # Also balance the *other* paren kind so nested calls don't desync.
        elif ch == "(":
            j = find_balanced(src, i, "(", ")")
            if j == -1:
                return -1
            i = j
        elif ch == "[":
            j = find_balanced(src, i, "[", "]")
            if j == -1:
                return -1
            i = j
        elif ch == "{":
            j = find_balanced(src, i, "{", "}")
            if j == -1:
                return -1
            i = j
        i += 1
    return -1


def split_top_level_commas(src: str) -> list[tuple[int, int]]:
    """Return (start, end) spans for top-level comma-separated items in src.

    Entire src is one argument list body (without enclosing parens).
    """
    spans = []
    n = len(src)
    i = 0
    last = 0
    while i < n:
        ch = src[i]
        if ch == "(":
            j = find_balanced(src, i, "(", ")")
            i = j + 1 if j != -1 else i + 1
            continue
        if ch == "[":
            j = find_balanced(src, i, "[", "]")
            i = j + 1 if j != -1 else i + 1
            continue
        if ch == "{":
            j = find_balanced(src, i, "{", "}")
            i = j + 1 if j != -1 else i + 1
            continue
        if ch == ",":
            spans.append((last, i))
            last = i + 1
        i += 1
    spans.append((last, n))
    return spans


# ---------------------------------------------------------------------------
# Findings.
# ---------------------------------------------------------------------------

@dataclass
class Finding:
    project: str
    file: str
    line: int
    pattern: str
    type_expr: str
    member: str
    before: str
    after: str
    chars_saved: int


def line_no(src: str, idx: int) -> int:
    return src.count("\n", 0, idx) + 1


def line_text(src: str, idx: int) -> str:
    start = src.rfind("\n", 0, idx) + 1
    end = src.find("\n", idx)
    if end == -1:
        end = len(src)
    return src[start:end]


# Restrictions used across patterns.

# Capitalized identifier (heuristic for "type-like" names). Lowering this to
# any identifier would catch things like ``opt.empty`` for ``val o: opt.type``
# but balloon the false-positive rate; SIP-80's main wins are over capitalized
# type names.
_TYPE_NAME = r"[A-Z][\w]*(?:\.[A-Z][\w]*)*"
# Member name: any identifier (term-level members can be lowercase, e.g. ``empty``).
_MEMBER = r"[A-Za-z_][\w]*"
# Type expression with optional dotted package + outer simple type name.
# Examples: ``Color``, ``pkg.Color``, ``a.b.Color``. We *do not* require the
# leading segment to start with a capital, since packages are conventionally
# lowercase.
_TYPE_EXPR = r"(?:[a-z_][\w]*\.)*" + _TYPE_NAME

# Type expression followed by optional ``[..]`` type-args (used on LHS of P1
# so we can spot ``val xs: List[Color] = List.empty``).
_TYPE_EXPR_WITH_TARGS = _TYPE_EXPR + r"(?:\s*\[[^\[\]]*\])?"


# ---------------------------------------------------------------------------
# Pattern P1: typed val/var/def with redundant prefix.
# ---------------------------------------------------------------------------

# Match `val|var|def|lazy val NAME (params)? : TYPE = TYPE.X` where TYPE on the
# RHS exactly equals the LHS type's principal class component (we approximate
# this by stripping any trailing `[...]` from the LHS type expression).
_P1_RE = re.compile(
    r"\b(?:val|var|lazy\s+val|def)\s+"
    r"(?:[A-Za-z_]\w*|`[^`]+`)"        # name
    r"(?:\s*\[[^\]]*\])?"              # optional type params on def
    r"(?:\s*\([^)]*\))*"               # optional def parameter clauses
    r"\s*:\s*"
    r"(?P<lhs>" + _TYPE_EXPR + r")"
    r"(?:\s*\[[^\[\]]*\])?"            # optional type args on LHS type
    r"(?:\s*\|\s*Null|\s*\|\s*scala\.Null)?"  # optional `| Null`
    r"\s*=\s*"
    r"(?P=lhs)\.(?P<member>" + _MEMBER + r")\b",
    re.MULTILINE,
)


def find_p1(blanked: str, original: str, project: str, rel_file: str) -> list[Finding]:
    out = []
    for m in _P1_RE.finditer(blanked):
        lhs = m.group("lhs")
        member = m.group("member")
        ln = line_no(original, m.start())
        before = line_text(original, m.start()).strip()
        after = before.replace(f"{lhs}.{member}", f".{member}", 1)
        out.append(Finding(project, rel_file, ln, "P1", lhs, member,
                           before, after, len(lhs) + 1))
    return out


# ---------------------------------------------------------------------------
# Pattern P2: default argument with redundant prefix.
# ---------------------------------------------------------------------------

# Inside a parameter clause, find `name: T = T.X` after `(` or `,`. Distinct
# from P1 because the lead-in is a paren or comma, not a val/def keyword.
_P2_RE = re.compile(
    r"(?P<lead>[(,])\s*"
    r"(?:using\s+|implicit\s+)?"
    r"(?:[A-Za-z_]\w*|`[^`]+`)"
    r"\s*:\s*"
    r"(?P<lhs>" + _TYPE_EXPR + r")"
    r"(?:\s*\[[^\[\]]*\])?"
    r"\s*=\s*"
    r"(?P=lhs)\.(?P<member>" + _MEMBER + r")\b",
)


def find_p2(blanked: str, original: str, project: str, rel_file: str) -> list[Finding]:
    out = []
    for m in _P2_RE.finditer(blanked):
        lhs = m.group("lhs")
        member = m.group("member")
        ln = line_no(original, m.start())
        before = line_text(original, m.start()).strip()
        after = before.replace(f"{lhs}.{member}", f".{member}", 1)
        out.append(Finding(project, rel_file, ln, "P2", lhs, member,
                           before, after, len(lhs) + 1))
    return out


# ---------------------------------------------------------------------------
# Pattern P3: constructor self-prefix in args.
#   T(... T.U.V ...)  where T is the call name.
# We look for any `Name(` and inspect the comma-separated args for tokens of
# form `Name.X[.Y...]`.
# ---------------------------------------------------------------------------

_CALL_HEAD_RE = re.compile(r"\b(" + _TYPE_NAME + r")\s*\(")


def find_p3(blanked: str, original: str, project: str, rel_file: str) -> list[Finding]:
    out = []
    for m in _CALL_HEAD_RE.finditer(blanked):
        name = m.group(1)
        open_idx = m.end() - 1
        close = find_balanced(blanked, open_idx, "(", ")")
        if close == -1:
            continue
        body = blanked[open_idx + 1: close]
        for s, e in split_top_level_commas(body):
            arg = body[s:e]
            arg_stripped = arg.lstrip()
            offset = open_idx + 1 + s + (len(arg) - len(arg_stripped))
            am = re.match(rf"({re.escape(name)})\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\b",
                          arg_stripped)
            if not am:
                # Allow named-argument form: `field = T.U.V`
                am2 = re.match(
                    rf"[A-Za-z_]\w*\s*=\s*({re.escape(name)})\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\b",
                    arg_stripped,
                )
                if not am2:
                    continue
                am = am2
                offset += am2.start(1)
            else:
                offset += am.start(1)
            tail = am.group(2)
            # Confidence filter: P3 is high-signal when the arg path looks
            # like a nested ADT case (multiple segments past T, e.g.
            # ``Shape.Geometry.Circle``) or a single capitalized case
            # object (``MediaType.Binary``). Single-segment lowercase
            # tails (``Stream.emit(...)``) often resolve against a
            # different companion than the outer call's, so we drop them
            # to keep the count a defensible lower bound.
            if "." not in tail and not tail[:1].isupper():
                continue
            ln = line_no(original, offset)
            *prefix_extra, kept = tail.split(".")
            full_prefix = name + ("." + ".".join(prefix_extra) if prefix_extra else "")
            before = line_text(original, offset).strip()
            after = before.replace(f"{full_prefix}.{kept}", f".{kept}", 1)
            out.append(Finding(project, rel_file, ln, "P3", full_prefix, kept,
                               before, after, len(full_prefix) + 1))
    return out


# ---------------------------------------------------------------------------
# Pattern P4: generic typed apply with redundant prefix.
#   Coll[T](T.X, ...)
# ---------------------------------------------------------------------------

_GENERIC_CALL_HEAD_RE = re.compile(
    r"\b([A-Za-z_]\w*)\s*\[\s*(" + _TYPE_EXPR + r")(?:\s*\[[^\[\]]*\])?\s*\]\s*\("
)


def find_p4(blanked: str, original: str, project: str, rel_file: str) -> list[Finding]:
    out = []
    for m in _GENERIC_CALL_HEAD_RE.finditer(blanked):
        # Skip cases where the type arg is a single character — those are
        # almost always type variables (``def foo[A](xs: A*)``). Concrete
        # types are at least two characters, including ``IO`` etc.
        targ = m.group(2)
        last_seg = targ.split(".")[-1]
        if len(last_seg) <= 1:
            continue
        open_idx = m.end() - 1
        close = find_balanced(blanked, open_idx, "(", ")")
        if close == -1:
            continue
        body = blanked[open_idx + 1: close]
        for s, e in split_top_level_commas(body):
            arg = body[s:e]
            arg_stripped = arg.lstrip()
            offset = open_idx + 1 + s + (len(arg) - len(arg_stripped))
            am = re.match(rf"({re.escape(targ)})\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\b",
                          arg_stripped)
            if not am:
                continue
            ln = line_no(original, offset)
            tail = am.group(2)
            *prefix_extra, kept = tail.split(".")
            full_prefix = targ + ("." + ".".join(prefix_extra) if prefix_extra else "")
            before = line_text(original, offset).strip()
            after = before.replace(f"{full_prefix}.{kept}", f".{kept}", 1)
            out.append(Finding(project, rel_file, ln, "P4", full_prefix, kept,
                               before, after, len(full_prefix) + 1))
    return out


# ---------------------------------------------------------------------------
# Pattern P5: match case after typed scrutinee.
#
# We track every typed local declaration ``val NAME : TYPE = ...`` in the file,
# then for any subsequent ``NAME match { ... }`` block we scan its body for
# ``case TYPE.X`` (or alternatives ``case TYPE.X | TYPE.Y``).
# ---------------------------------------------------------------------------

_TYPED_DECL_RE = re.compile(
    r"\b(?:val|var|lazy\s+val|def)\s+"
    r"(?P<name>[A-Za-z_]\w*|`[^`]+`)"
    r"(?:\s*\[[^\]]*\])?"
    r"(?:\s*\([^)]*\))*"
    r"\s*:\s*"
    r"(?P<type>" + _TYPE_EXPR + r")"
    r"(?:\s*\[[^\[\]]*\])?"
    r"(?:\s*\|\s*Null|\s*\|\s*scala\.Null)?"
    r"\b"
)

_PARAM_DECL_RE = re.compile(
    r"(?P<lead>[(,])\s*"
    r"(?:using\s+|implicit\s+)?"
    r"(?P<name>[A-Za-z_]\w*|`[^`]+`)"
    r"\s*:\s*"
    r"(?P<type>" + _TYPE_EXPR + r")"
    r"(?:\s*\[[^\[\]]*\])?"
    r"(?:\s*\|\s*Null|\s*\|\s*scala\.Null)?"
)

_MATCH_RE = re.compile(r"\b([A-Za-z_]\w*)\s+match\b")
_CASE_RE = re.compile(r"\bcase\s+(" + _TYPE_EXPR + r")\.([A-Za-z_]\w*)\b")


def find_p5(blanked: str, original: str, project: str, rel_file: str) -> list[Finding]:
    """Detect ``case T.X`` arms whose scrutinee was declared with type ``T``.

    Heuristic: build a name → type map from typed val/def/param declarations,
    then for each ``NAME match { ... }`` whose NAME is in the map, scan its
    body (Scala-3 indentation-form bodies are matched up to the next blank
    or de-dented top-level ``case``/``end``).
    """
    name_to_type: dict[str, str] = {}
    for m in _TYPED_DECL_RE.finditer(blanked):
        name = m.group("name").strip("`")
        name_to_type[name] = m.group("type")
    for m in _PARAM_DECL_RE.finditer(blanked):
        name = m.group("name").strip("`")
        # Param scope is local to its method; we accept some leakage in
        # exchange for cross-method coverage. P5 alternatives still require
        # a textual match between the param's type and the case prefix.
        name_to_type.setdefault(name, m.group("type"))

    out = []
    for mm in _MATCH_RE.finditer(blanked):
        scrut = mm.group(1)
        if scrut not in name_to_type:
            continue
        scrut_ty = name_to_type[scrut]
        # Determine body span. Look at the first non-whitespace token after
        # ``match``: brace form ``match {`` uses balanced braces; otherwise
        # we treat it as Scala-3 indentation form and follow lines whose
        # indent is strictly greater than the ``match`` keyword's indent.
        body_start = mm.end()
        peek = body_start
        while peek < len(blanked) and blanked[peek] in " \t":
            peek += 1
        if peek < len(blanked) and blanked[peek] == "{":
            close = find_balanced(blanked, peek, "{", "}")
            if close == -1:
                continue
            body = blanked[peek + 1: close]
            body_origin = peek + 1
        else:  # indentation form: ``match`` then newline then indented cases
            line_start = blanked.rfind("\n", 0, mm.start()) + 1
            indent = len(blanked[line_start: mm.start()]) - \
                     len(blanked[line_start: mm.start()].lstrip())
            j = body_start
            # Skip to start of next line.
            nl = blanked.find("\n", j)
            if nl == -1:
                continue
            j = nl + 1
            body_origin = j
            while j < len(blanked):
                line_end = blanked.find("\n", j)
                line_end = len(blanked) if line_end == -1 else line_end
                line = blanked[j: line_end]
                if line.strip() == "":
                    j = line_end + 1
                    continue
                cur_indent = len(line) - len(line.lstrip())
                if cur_indent <= indent:
                    break
                j = line_end + 1
            body = blanked[body_origin: j]

        for cm in _CASE_RE.finditer(body):
            ty = cm.group(1)
            mem = cm.group(2)
            # The case's expected type is the scrutinee type. We accept a
            # match if the case prefix textually equals the scrutinee type
            # OR if it equals the last segment (e.g. scrut: pkg.Color, case
            # Color.Red).
            if ty != scrut_ty and ty != scrut_ty.split(".")[-1]:
                continue
            offset = body_origin + cm.start()
            ln = line_no(original, offset)
            before = line_text(original, offset).strip()
            after = before.replace(f"{ty}.{mem}", f".{mem}", 1)
            out.append(Finding(project, rel_file, ln, "P5", ty, mem,
                               before, after, len(ty) + 1))
    return out


# ---------------------------------------------------------------------------
# Driver.
# ---------------------------------------------------------------------------

def scan_file(path: Path, project: str, root: Path) -> list[Finding]:
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except (OSError, UnicodeDecodeError):
        return []
    blanked = blank_strings_and_comments(original)
    rel = str(path.relative_to(root)) if root in path.parents or path == root else str(path)
    findings = []
    findings += find_p1(blanked, original, project, rel)
    findings += find_p2(blanked, original, project, rel)
    findings += find_p3(blanked, original, project, rel)
    findings += find_p4(blanked, original, project, rel)
    findings += find_p5(blanked, original, project, rel)
    return findings


def walk_scala(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        # Skip vendored / build output directories.
        dirnames[:] = [d for d in dirnames if d not in {
            ".git", "target", "out", "node_modules", ".bloop", ".metals",
            ".bsp", "project",
        }]
        for fn in filenames:
            if fn.endswith(".scala") or fn.endswith(".sc"):
                yield Path(dirpath) / fn


def write_outputs(findings: list[Finding], out_dir: Path, label: str) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    tsv = out_dir / f"findings-{label}.tsv"
    with tsv.open("w", encoding="utf-8") as f:
        f.write("project\tfile\tline\tpattern\ttype\tmember\tchars_saved\tbefore\tafter\n")
        for x in findings:
            f.write("\t".join([
                x.project, x.file, str(x.line), x.pattern,
                x.type_expr, x.member, str(x.chars_saved),
                x.before.replace("\t", " "),
                x.after.replace("\t", " "),
            ]) + "\n")

    summary = aggregate(findings, label)
    (out_dir / f"summary-{label}.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    return summary


def aggregate(findings: list[Finding], label: str) -> dict:
    by_pattern: dict[str, dict] = {}
    by_project: dict[str, dict] = {}
    for x in findings:
        bp = by_pattern.setdefault(x.pattern, {"incidents": 0, "chars_saved": 0})
        bp["incidents"] += 1
        bp["chars_saved"] += x.chars_saved
        bj = by_project.setdefault(x.project, {"incidents": 0, "chars_saved": 0})
        bj["incidents"] += 1
        bj["chars_saved"] += x.chars_saved
    return {
        "label": label,
        "total_incidents": len(findings),
        "total_chars_saved": sum(x.chars_saved for x in findings),
        "by_pattern": by_pattern,
        "by_project": by_project,
    }


# ---------------------------------------------------------------------------
# Self-test.
# ---------------------------------------------------------------------------

def self_test(test_dir: Path) -> int:
    expected_path = test_dir / "expected.json"
    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    failed = 0
    for case in expected["cases"]:
        path = test_dir / case["file"]
        findings = scan_file(path, "test", test_dir)
        actual = len(findings)
        actual_by_pat: dict[str, int] = {}
        for f in findings:
            actual_by_pat[f.pattern] = actual_by_pat.get(f.pattern, 0) + 1
        ok_total = actual == case["incidents"]
        ok_by_pat = actual_by_pat == case.get("by_pattern", actual_by_pat)
        status = "OK" if ok_total and ok_by_pat else "FAIL"
        if not (ok_total and ok_by_pat):
            failed += 1
            print(f"[{status}] {case['file']}: expected {case['incidents']}"
                  f" {case.get('by_pattern', {})}, got {actual} {actual_by_pat}")
            for f in findings:
                print(f"        {f.pattern}: {f.type_expr}.{f.member} "
                      f"@ line {f.line}")
        else:
            print(f"[{status}] {case['file']}: {actual} incidents matched")
    return failed


# ---------------------------------------------------------------------------
# Main.
# ---------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--root", action="append", default=[],
                   help="directory to scan; may be passed multiple times")
    p.add_argument("--project", action="append", default=[],
                   help="project label for the matching --root; if fewer "
                        "--project flags than --root, remainder default to "
                        "the root's basename")
    p.add_argument("--corpus", default=None,
                   help="path to a corpus list file (one project name per line)")
    p.add_argument("--corpus-root", default=None,
                   help="root directory under which corpus projects live")
    p.add_argument("--out-dir", default="results",
                   help="output directory for findings/summary")
    p.add_argument("--label", default="run", help="label for output files")
    args = p.parse_args(argv)

    here = Path(__file__).resolve().parent

    if args.self_test:
        rc = self_test(here / "test-cases")
        print(f"\n{'PASS' if rc == 0 else 'FAIL'}: {rc} failure(s)")
        return 0 if rc == 0 else 1

    findings: list[Finding] = []

    if args.corpus:
        if not args.corpus_root:
            print("--corpus requires --corpus-root", file=sys.stderr)
            return 2
        corpus_root = Path(args.corpus_root).resolve()
        for raw in Path(args.corpus).read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            project_name = line.split("/")[-1]
            project_dir = corpus_root / project_name
            if not project_dir.is_dir():
                print(f"[skip] {project_name} (not found at {project_dir})",
                      file=sys.stderr)
                continue
            count = 0
            for path in walk_scala(project_dir):
                fs = scan_file(path, project_name, project_dir)
                findings.extend(fs)
                count += len(fs)
            print(f"[scan] {project_name}: {count} incidents", file=sys.stderr)

    for idx, r in enumerate(args.root):
        root = Path(r).resolve()
        proj = args.project[idx] if idx < len(args.project) else root.name
        count = 0
        for path in walk_scala(root):
            fs = scan_file(path, proj, root)
            findings.extend(fs)
            count += len(fs)
        print(f"[scan] {proj} ({root}): {count} incidents", file=sys.stderr)

    out = Path(args.out_dir).resolve()
    summary = write_outputs(findings, out, args.label)
    print(json.dumps({
        "total_incidents": summary["total_incidents"],
        "total_chars_saved": summary["total_chars_saved"],
        "by_pattern": summary["by_pattern"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
