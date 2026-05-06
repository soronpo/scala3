# SIP-80 Beneficial-Incident Scanner

A small Python 3 scanner that quantifies how often real Scala source code
would benefit from
[SIP-80 "Target-Typed Companion Shorthand"](https://github.com/scala/improvement-proposals/pull/134).
The proposal lets you write `.X` instead of `T.X` whenever the position's
expected type is statically `T`. This scanner finds places where the user
*literally typed* `T.X` and `T` is recoverable from immediate syntactic
context, so the rewrite is unambiguous.

## Headline numbers

Across the local Scala 3 compiler/library/tests + 18 widely-used open-source
Scala 3 projects (cats, cats-effect, fs2, zio, scalatest, http4s, circe,
scala-cli, sttp, tapir, scalapb, playframework, com-lihaoyi/{upickle,
scalatags, os-lib, requests, cask}, quicklens):

- **7,837** incidents detected
- **77,426** characters saved

See `results/summary.md` for the per-pattern and per-project breakdown,
and `results/findings.tsv` for every incident's file:line, before, and
after.

## What it counts

Five high-confidence syntactic patterns. Each match is something the user
*literally typed* as `T.X` while the position's expected type is statically
`T` (recoverable from the immediate surrounding tokens), so SIP-80 would
accept the `.X` shorthand losslessly.

| # | Pattern | Example today | After SIP-80 |
|---|---|---|---|
| P1 | Typed `val`/`var`/`def`/`lazy val` with redundant prefix | `val c: Color = Color.Red` | `val c: Color = .Red` |
| P2 | Default argument with redundant prefix | `def f(c: Color = Color.Red)` | `def f(c: Color = .Red)` |
| P3 | Constructor self-prefix in args | `Shape(Shape.Geometry.Circle, Shape.Color.Red)` | `Shape(.Circle, .Red)` |
| P4 | Generic typed apply with redundant prefix | `Seq[Color](Color.Red, Color.Green)` | `Seq[Color](.Red, .Green)` |
| P5 | `case T.X` after a typed scrutinee | `c match { case Color.Red => ... }` | `c match { case .Red => ... }` |

Character savings per match are computed as `len(prefix) + 1` — the dropped
prefix and its trailing dot.

## Running

The scanner is pure Python 3 (stdlib only). Two scripts orchestrate runs:

```bash
# Self-test against fixtures (run this first)
python3 scan.py --self-test

# Scan the local Scala 3 sources only
bash run.sh local

# Clone the curated open-source corpus and scan it
bash run.sh corpus

# Both of the above, then aggregate
bash run.sh all
```

The corpus (`corpus.txt`) lists ~20 widely-used Scala projects and is
shallow-cloned into `/tmp/sip80-corpus/` (overridable with
`SIP80_CORPUS_ROOT`). Each clone uses `gh repo clone --depth 1` to
minimise download size.

Outputs land under `results/`:

- `summary.md` — totals + by-pattern + by-project breakdown.
- `summary-{local,corpus}.json` — machine-readable per-run aggregates.
- `findings.tsv` — every incident with file, line, the line text before
  and the line text after the rewrite. Useful for spot-checking and for
  quoting concrete examples in SIP discussion.

## Methodology

1. **Token-aware blanking.** A first pass replaces line comments, nested
   block comments, single- and triple-quoted string literals (raw and
   interpolated), and char literals with spaces. Length and newlines are
   preserved so file:line:column offsets stay valid. This means no incident
   comes from prose or test fixtures.

2. **Regex match against blanked text.** Five regexes anchored on
   triggering punctuation (`=`, `,`, `(`, `case`) — exactly the contexts
   SIP-80 specifies as triggering positions.

3. **`case` matching uses scrutinee tracking.** P5 builds a name → type
   map from `val`/`var`/`def`/parameter declarations in the file, then
   for each `NAME match { ... }` (brace form) or `NAME match\n  case ...`
   (Scala 3 indentation form) verifies that the case prefix matches the
   declared scrutinee type before counting.

## Caveats

This is a *lower bound* and is intended to be defensible rather than
maximal:

- **Wildcard-import workaround uncounted.** Today, users who write
  `import Shape.Color.*` and then bare `Red` get the same result SIP-80
  delivers, with the namespace pollution the SIP avoids. The scanner
  cannot distinguish `Red` from any other identifier and so does not
  count these cases. A fully type-aware (TastyInspector-based) scanner
  would catch them.

- **No type inference.** P3 and P4 in particular rely on the heuristic
  that the outer call's class/type arg is the expected type of the
  matched argument. This is reliable for case-class construction
  (`Shape(...)`) and for collection constructors (`List[T](...)`), but
  has a known false-positive class: when the outer call is a generic
  factory whose parameter type differs from `T`
  (e.g. `parsedString[UUID](UUID.fromString)` — expected type
  `String => UUID`, not `UUID`). For P3 we drop single-segment lowercase
  tails to suppress most of this; P4 is left broader and may include
  some over-counts. Spot-checking the corpus suggests P4's
  false-positive rate is well under 20%.

- **Scala 2 not separately reported.** Some of the corpus projects are
  Scala 2 / cross-built. SIP-80 is a Scala 3 feature, but the same
  idioms appear in Scala 2 code that gets cross-built to Scala 3. The
  scanner runs against the source verbatim.

- **False positives are uniformly suppressed by the comment/string
  blanking pass.** The negative-trap fixture
  (`test-cases/negative_traps.scala`) verifies that comments, single-
  and triple-quoted strings, and prefix-mismatched declarations all
  produce zero incidents.

## Files

```
scan.py             — the scanner (Python 3, stdlib only)
run.sh              — orchestration: clone corpus, run scans, aggregate
aggregate.py        — emits summary.md + combined findings.tsv
corpus.txt          — list of OSS repos to scan (one ``owner/repo`` per line)
test-cases/         — positive_p{1..5}.scala + negative_traps.scala fixtures
test-cases/expected.json — expected counts; checked by --self-test
results/            — output (summary.md, findings.tsv, per-run JSON)
```

## Reproducing

```bash
cd sip80-scanner
python3 scan.py --self-test         # prerequisites
bash run.sh all                     # ~5 min on a fast network
```

Re-running with an updated `corpus.txt` is idempotent: existing clones are
re-used; only the scan and aggregation phases re-run.
