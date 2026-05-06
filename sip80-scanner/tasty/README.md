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

### Scala 3 community build, category A (smallest set)

The Scala 3 repo's `community-build` test harness exercises the
compiler against real OSS projects, sliced into three categories
(A/B/C). Category A is the smallest active set: 4 named projects
spanning 11 published modules. Maven coordinates were extracted from
`community-build/test/scala/dotty/communitybuild/CommunityBuildTest.scala`.

| Module                                          | Version    | Incidents | Chars saved | Import-based |
|---|---|---:|---:|---:|
| `dev.zio:izumi-reflect_3`                       | 3.0.9      |  65 |   442 |  6 |
| `org.scala-stm:scala-stm_3`                     | 0.11.1     |  31 |   319 |  5 |
| `org.scalatestplus:testng-7-12_3`               | 3.2.20.0   |   1 |     9 |  0 |
| `de.sciss:lucre-adjunct_3`                      | 4.6.6      |  29 |     0 | 29 |
| `de.sciss:lucre-base_3`                         | 4.6.6      |   1 |     0 |  1 |
| `de.sciss:lucre-bdb_3`                          | 4.6.6      |   2 |    16 |  0 |
| `de.sciss:lucre-confluent_3`                    | 4.6.6      |  22 |   132 |  1 |
| `de.sciss:lucre-core_3`                         | 4.6.6      |  37 |   248 |  1 |
| `de.sciss:lucre-data_3`                         | 4.6.6      |  25 |   212 |  2 |
| `de.sciss:lucre-expr_3`                         | 4.6.6      |  88 |   603 |  9 |
| `de.sciss:lucre-geom_3`                         | 4.6.6      |  34 |   130 |  0 |
| **Category A total**                            |            | **335** | **2,111** | **54** |

By detection pattern: 152 `typed_decl`, 123 `call_arg`, 29 `if_branch`,
18 `match_case`, 13 `default_arg`. `lucre-adjunct`'s 29 incidents are
all bare-X-after-import: a chain of `val IntSeqTop: IntSeqTop = IntSeqTop`-style
typeclass instance vals that are visible only because `Adjunct.scala`
opens its own companion via wildcard import.

Saved per-module under `results-jars/cb-a/`.

### Scala 3 community build, category B

The middle category in the community build harness, scanned at the
latest stable version per artifact. Cats-core required adding
``org.typelevel:scalac-compat-annotation_3:0.1.4`` to the deps
classpath manually (it's a Provided-scope dep that ``coursier fetch
--classpath`` excludes); the rest worked from `build.sh maven`'s
auto-resolved deps.

| Module                                              | Version     | Incidents | Chars saved | Import-based |
|---|---|---:|---:|---:|
| `org.typelevel:cats-core_3`                         | 2.13.0      |   421 | 2,354 |  68 |
| `org.typelevel:cats-kernel_3`                       | 2.13.0      |   287 | 2,650 |   4 |
| `org.typelevel:cats-laws_3`                         | 2.13.0      |   296 |   173 | 253 |
| `org.typelevel:cats-effect_3`                       | 3.6.3       |   148 |   653 |  32 |
| `org.typelevel:cats-effect-kernel_3`                | 3.6.3       |   138 |   877 |  11 |
| `org.typelevel:cats-effect-std_3`                   | 3.6.3       |    63 |   402 |   2 |
| `org.typelevel:cats-mtl_3`                          | 1.6.0       |    17 |    96 |   2 |
| `org.typelevel:coop_3`                              | 1.3.0       |    14 |    56 |   2 |
| `org.typelevel:discipline-core_3`                   | 1.7.0       |     0 |     0 |   0 |
| `org.typelevel:discipline-munit_3`                  | 2.0.0       |     2 |     6 |   0 |
| `org.typelevel:discipline-specs2_3`                 | 2.0.0       |     0 |     0 |   0 |
| `dev.optics:monocle-core_3`                         | 3.3.0       |    60 |   208 |  29 |
| `dev.optics:monocle-macro_3`                        | 3.3.0       |     0 |     0 |   0 |
| `org.typelevel:munit-cats-effect_3`                 | 2.2.0       |     6 |    12 |   0 |
| `net.katsstuff:perspective_3`                       | 0.3.0       |     8 |     0 |   8 |
| `org.typelevel:scalacheck-effect_3`                 | 2.1.0       |    50 |    83 |  39 |
| `org.scodec:scodec-core_3`                          | 2.3.3       |   159 | 1,396 |  10 |
| `org.scodec:scodec-bits_3`                          | 1.2.4       |   129 |   870 |  39 |
| **Category B total**                                |             | **1,798** | **9,836** | **499** |

Two modules in the category-B test list could not be scanned by the
TastyInspector at all: `org.scalameta:munit_3:1.3.0` (TASTy reader
crashes on its `MacroCompatScala2.scala` Scala-2 reflection helper
with `MatchError: val <none>`), and
`org.typelevel:simulacrum-scalafix-annotations_3:0.5.4` (its source
jar has corrupted timestamps that crash `jar xf` with `Negative
time`). Both are pre-existing TastyInspector / coursier issues
unrelated to SIP-80 detection.

By detection pattern across the rest: 924 `typed_decl`, 687
`call_arg`, 98 `if_branch`, 57 `default_arg`, 22 `match_case`, 10
`ascription`. The cats-* family contributes 1,372 of the 1,798
incidents (76%) — its kernel/laws/std layers are dense with typed
typeclass instance vals, which is exactly the P1 pattern.

Saved per-module under `results-jars/cb-b/`.

### Scala 3 community build, category C

The largest community-build slice: 30 active named projects in
`CommunityBuildTestC`. We scanned 33 published Maven modules
(some projects, like `endpoints4s`, `specs2`, ship as several
artifacts; we picked the main ones). Five projects have no Maven
Central release (`effpi`, `intent`, `scalap`, `scas`,
`xml-interpolator`) — they're built only locally during the
community-build run — and are listed below as "skipped".

| Module                                              | Version           | Incidents | Chars saved | Import-based |
|---|---|---:|---:|---:|
| `com.lihaoyi:cask_3`                                | 0.11.3            |    55 |    94 |  46 |
| `org.endpoints4s:algebra_3`                         | 1.12.1            |    13 |    57 |   0 |
| `org.endpoints4s:openapi_3`                         | 5.0.1             |    40 |   220 |   1 |
| `org.endpoints4s:http4s-server_3`                   | 11.0.1            |    16 |   187 |   0 |
| `org.endpoints4s:json-schema-generic_3`             | 1.12.1            |     0 |     0 |   0 |
| `com.lihaoyi:fansi_3`                               | 0.5.1             |     7 |    40 |   2 |
| `com.lihaoyi:fastparse_3`                           | 3.1.1             |    36 |   158 |   0 |
| `com.lihaoyi:geny_3`                                | 1.1.1             |     1 |     3 |   0 |
| `dev.continuously.libretto:libretto-core_3`         | 0.3.10            |   219 | 1,387 |  97 |
| `io.monix:minitest_3`                               | 2.9.6             |    10 |    62 |   1 |
| `com.lihaoyi:os-lib_3`                              | 0.11.3            |   129 | 1,102 |  40 |
| `org.parboiled:parboiled_3`                         | 2.5.1             |    47 |    57 |  34 |
| `com.lihaoyi:pprint_3`                              | 0.9.6             |    12 |   103 |   1 |
| `com.lihaoyi:requests_3`                            | 0.9.3             |    11 |    95 |   0 |
| `org.scalacheck:scalacheck_3`                       | 1.19.0            |   353 | 1,102 | 163 |
| `org.scala-lang.modules:scala-java8-compat_3`       | 1.0.2             |    31 |   321 |   0 |
| `org.scala-lang.modules:scala-parallel-collections_3` | 1.2.0           |    46 |   219 |  20 |
| `org.scala-lang.modules:scala-parser-combinators_3` | 2.4.0             |    11 |    46 |   7 |
| `com.thesamet.scalapb:scalapb-runtime_3`            | 1.0.0-alpha.3     |   571 | 16,295 | 139 |
| `com.thesamet.scalapb:lenses_3`                     | 1.0.0-alpha.3     |     0 |     0 |   0 |
| `org.scalatestplus:scalacheck-1-18_3`               | 3.3.0.0-alpha.2   |     7 |    40 |   0 |
| `org.scala-lang.modules:scala-xml_3`                | 2.4.0             |    70 |   403 |  10 |
| `org.scalaz:scalaz-core_3`                          | 7.4.0-M15         | 1,483 | 4,047 | 904 |
| `org.ekrich:sconfig_3`                              | 1.12.4            |   232 | 2,246 |  67 |
| `org.typelevel:shapeless3-deriving_3`               | 3.5.0             |     8 |    36 |   2 |
| `org.typelevel:shapeless3-typeable_3`               | 3.5.0             |     2 |    12 |   0 |
| `com.lihaoyi:sourcecode_3`                          | 0.4.4             |     0 |     0 |   0 |
| `org.specs2:specs2-core_3`                          | 5.9.0             |   136 | 1,021 |  20 |
| `org.specs2:specs2-matcher_3`                       | 5.9.0             |    33 |   221 |   6 |
| `org.specs2:specs2-common_3`                        | 5.9.0             |   117 |   845 |  18 |
| `org.specs2:specs2-fp_3`                            | 5.9.0             |    61 |   227 |  30 |
| `com.lihaoyi:ujson_3`                               | 4.4.3             |    15 |    72 |   1 |
| `com.lihaoyi:utest_3`                               | 0.10.0-RC1        |    39 |   315 |   3 |
| `com.eed3si9n.verify:verify_3`                      | 1.0.0             |    15 |    95 |   1 |
| **Category C total**                                |                   | **3,826** | **31,128** | **1,613** |

Skipped (no published Maven artifact at scan time): `effpi`,
`intent`, `scalap`, `scas`, `xml-interpolator`.

Top contributors are `scalaz-core` (1,483 incidents, 904 of them
import-based — pervasive use of `import Foo._` to bring typeclass
syntax into scope), `scalapb-runtime` (571 incidents, 16,295 chars
saved — generated protobuf code with explicit types everywhere),
`scalacheck` (353), `sconfig` (232), and `libretto-core` (219).

### Community-build grand totals (A + B + C)

| Category | Incidents | Chars saved | Import-based |
|---|---:|---:|---:|
| A | 335 | 2,111 | 54 |
| B | 1,798 | 9,836 | 499 |
| C | 3,826 | 31,128 | 1,613 |
| **Total** | **5,959** | **43,075** | **2,166** |

That's roughly 6,000 places across the curated Scala 3 community
build where SIP-80's `.X` shorthand would resolve, ~36 % via bare
identifiers visible only because the user opened a wildcard import.

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
