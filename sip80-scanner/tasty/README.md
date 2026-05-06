# Stage B — TastyInspector-based SIP-80 scanner

A Scala 3 program built on `scala.tasty.inspector.TastyInspector` that
walks compiled `.tasty` files and reports positions where SIP-80's `.X`
shorthand would resolve to the same expression the user already wrote.
Compared with the syntactic Stage A (`../scan.py`):

- **Eliminates the false positives** Stage A's regex couldn't tell apart
  (e.g. `Arbitrary(Arbitrary.arbitrary[Int])`, where the parameter type
  is `Gen[Int]`, not `Arbitrary`). Stage B walks the typed tree and
  checks the actual expected type at the position.
- **Catches patterns Stage A misses**: bare identifiers resolved through
  wildcard imports, ascriptions, `if`/`else` branches typed by context,
  nested extractor patterns, and method-call args whose param type
  isn't visible from a regex.

## Detection model

For each `Select(qual, name)` and `Ident(name)` tree, we ask: "is this
position target-typed by a context whose principal class component
(after SIP-80's reduction rules — strip prototypes, dealias, drop
`Null` arms, take principal class) has a companion module that owns
this symbol?". If yes, we record an incident.

Walked target-typed positions:

| Category | Source |
|---|---|
| `typed_decl` | `val`/`var`/`def` with an explicit declared type, RHS |
| `default_arg` | parameter default value (Scala 3 emits these in `*$default$N` synthetic getters) |
| `call_arg` | method/constructor argument; the param's expected type drives resolution |
| `ascription` | `(expr : T)` |
| `if_branch` | the two branches of an `if`/`else` whose enclosing context provides the expected type |
| `match_case` | top-level `case T.X` with the scrutinee type |
| `nested_pattern` | `Some(T.X)`, tuple patterns, etc.; we read component types from the unapply's signature |

To compute character savings honestly, we read the actual source span
from the source jar (or a directory of source files) and report
`text.length - (memberName.length + 1)` per incident. When the user
wrote a bare `X` (resolved via wildcard import or same-companion
scope), we record the incident with `chars_saved = 0` and flag it
`import_based: true` — SIP-80 fires there but doesn't shorten the use
site, it shortens the *file* by removing the import.

## Filters that reduce false positives

- Synthetic `ValDef` / `DefDef` are skipped except for `*$default$*`
  getters (which hold the user's default-value expressions).
- `ValDef`/`DefDef` whose declared type is `Inferred` (no user
  annotation) are skipped at that level (RHS is still walked, so
  ascriptions inside still fire).
- Records whose source text doesn't actually contain the resolved
  member name are dropped — this catches synthesised `apply` insertions
  where the source position points to the type name rather than to
  `.apply`.

## Running

```bash
# Self-test against the fixtures.
bash build.sh fixtures

# Inspect a Maven Central _3.jar via coursier-resolved deps + sources.
bash build.sh maven com.lihaoyi:os-lib_3:0.11.3

# Inspect an arbitrary jar with explicit deps classpath.
bash build.sh jar /path/to/foo_3-1.2.3.jar foo /path/to/sources \
    /path/to/dep1.jar:/path/to/dep2.jar
```

The `coursier` launcher used to resolve and run Scala 3 is the one
shipped at `dist/target/republish/coursier/coursier.jar` in this
checkout. Override with `CS_JAR=...` to use a different launcher.

## Layout

```
tasty/
  src/Sip80Inspector.scala     The inspector + Main entry point.
  fixtures/                    Self-test fixtures (compiled to TASTy).
  fixtures/expected.json       Expected counts for the fixtures.
  build.sh                     Build the inspector + run.
  check.py                     Compare summary.json to expected.json.
  detect-tasty-version.py      Read major.minor from a jar's TASTy header.
  results/                     Last run's findings.tsv + summary.json.
  results-jars/                Saved per-library scan summaries.
  out/                         Compiled fixture TASTy.
  build/<version>/             Inspector classes per scala3 minor.
```

## Fixture self-test

39 incidents across four fixtures, each annotated with `//+` (must
fire) or `//-` (must not fire). `check.py` validates totals + per-
category + per-file counts against `fixtures/expected.json`:

| Fixture | Total | Import-based | Chars saved |
|---|---:|---:|---:|
| `Patterns.scala`        | 28 | 1 | 165 |
| `FalsePositives.scala`  |  0 | 0 |   0 |
| `StageB.scala`          | 11 | 3 |  40 |
| `Negatives.scala`       |  0 | 0 |   0 |
| **Total**               | **39** | **4** | **205** |

`FalsePositives.scala` shows the cases Stage A overcounts but Stage B
correctly rejects: `Arbitrary(Arbitrary.arbitrary[Int])` and
`parsedString[UUID](UUID.fromString)`. `StageB.scala` shows what Stage
A misses: bare-X-after-import, method-call args, ascriptions, if/else
branches, and nested patterns.

## Real-library results

Saved under `results-jars/`. Comparison with Stage A on the same library:

| Library | Stage A incidents | Stage B incidents | Stage A chars | Stage B chars |
|---|---:|---:|---:|---:|
| `os-lib_3:0.11.3`     | 29 | 129 |   430 | 1,102 |
| `scalatags_3:0.13.1`  |  2 |   2 |    20 |    18 |

### DFiantHDL (full library, 6 modules, hardware DSL on Scala 3)

| Module                            | Version | Incidents | Chars saved | Import-based |
|---|---|---:|---:|---:|
| `dfhdl-core_3`                    | 0.17.0  |   223 | 1,823 |  44 |
| `dfhdl-internals_3`               | 0.17.0  |    17 |   126 |   0 |
| `dfhdl-compiler-ir_3`             | 0.17.0  |   296 | 2,295 |  39 |
| `dfhdl-compiler-stages_3`         | 0.17.0  |   216 | 2,138 |   2 |
| `dfhdl-platforms_3`               | 0.17.0  |    55 |   357 |  13 |
| `dfhdl-devices_3`                 | 0.12.0  |     5 |    79 |   0 |
| **DFHDL total**                   |         | **812** | **6,818** | **98** |

DFHDL is a heavy DSL user (the very pattern SIP-80 was designed for): a
Scala 3 hardware-description language whose intermediate representation
is a large algebraic data type with deeply nested case classes. The
`dfhdl-compiler-ir` module alone has 90 top-level `case T.X` arms and
81 nested patterns that SIP-80 would shorten.

### Scala 3 itself (compiler + library + tooling, version 3.8.3)

| Module                                | Incidents | Chars saved | Import-based |
|---|---:|---:|---:|
| `scala-library:3.8.3`                 |   290 |  1,688 |  98 |
| `scala3-compiler_3:3.8.3`             | 1,010 | 12,295 | 343 |
| `scala3-presentation-compiler_3:3.8.3`|   177 |  1,817 |   2 |
| `scaladoc_3:3.8.3`                    |    89 |    630 |   1 |
| `scala3-staging_3:3.8.3`              |     2 |      4 |   1 |
| `tasty-core_3:3.8.3`                  |     1 |     15 |   0 |
| `scala3-tasty-inspector_3:3.8.3`      |     0 |      0 |   0 |
| **Scala 3 total**                     | **1,569** | **16,449** | **445** |

The compiler dwarfs the rest because it's a 100k-LOC codebase rich in
ADT pattern matching, `Set.empty`/`List.empty` factory calls, and
`Mode`/`CompileMode`-style flag enums — all situations where SIP-80
fires. Many of `scala3-compiler`'s 343 import-based hits come from
generated `semanticdb` and `scalajs-ir` code that wildcard-imports the
case-object members of large enums.

| Module                            | Version | Incidents | Chars saved | Import-based |
|---|---|---:|---:|---:|
| `dfhdl-core_3`                    | 0.17.0  |   223 | 1,823 |  44 |
| `dfhdl-internals_3`               | 0.17.0  |    17 |   126 |   0 |
| `dfhdl-compiler-ir_3`             | 0.17.0  |   296 | 2,295 |  39 |
| `dfhdl-compiler-stages_3`         | 0.17.0  |   216 | 2,138 |   2 |
| `dfhdl-platforms_3`               | 0.17.0  |    55 |   357 |  13 |
| `dfhdl-devices_3`                 | 0.12.0  |     5 |    79 |   0 |
| **DFHDL total**                   |         | **812** | **6,818** | **98** |

DFHDL is a heavy DSL user (the very pattern SIP-80 was designed for): a
Scala 3 hardware-description language whose intermediate representation
is a large algebraic data type with deeply nested case classes. The
`dfhdl-compiler-ir` module alone has 90 top-level `case T.X` arms and
81 nested patterns that SIP-80 would shorten.

(Stage A's chars-saved are slightly inflated by an off-by-one in its
formula — `len(prefix) + 1` instead of `len(prefix)`; Stage B uses
`text.length - (memberName.length + 1)` directly, which is the actual
saving.)

The 4.4× incident multiplier on os-lib is dominated by:

- Bare `CREATE`, `WRITE`, `APPEND` etc. resolved through
  `import java.nio.file.StandardOpenOption.*` (40 import-based incidents
  Stage A literally cannot see).
- `RelPath.up` and `Array.empty` passed as method args, which Stage A's
  P3/P4 don't fire on because the outer call isn't `RelPath(...)` or
  `Array[T](...)`.

## Caveats

- TASTy compatibility: `build.sh` auto-detects the target jar's TASTy
  major.minor (`detect-tasty-version.py` reads the first `.tasty`
  entry's header) and picks the latest published
  `scala3-tasty-inspector_3:3.<minor>.x` to run with. The inspector is
  recompiled and cached per minor under `build/<version>/`. Set
  `SCALA_VERSION=...` to override the default used by the fixtures
  self-test.
- Some libraries' transitive deps are not on Maven Central in a form
  the TASTy reader can chase (e.g. `cats-core_3` references
  `org.typelevel.scalaccompat.annotation.uncheckedVariance2` which
  isn't auto-resolved). Workaround: extend the deps classpath via
  `build.sh jar` or skip those libraries.
- Source paths are recovered via `pos.sourceFile.path` then resolved
  through the source root and a basename-fallback index. This is
  enough for the typical case-class/sbt project layout; pathological
  layouts may produce empty `source` columns (still counted as
  incidents but without char savings).
