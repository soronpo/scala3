# SIP-80 Beneficial-Incident Scan — Summary

Each row is one place where the source code already writes `T.X` while the position's expected type is statically `T`, so SIP-80's `.X` shorthand would apply with zero loss of information. Character savings are conservatively computed as `len(prefix) + 1` (the dropped prefix and its trailing dot).

## Totals

- **Incidents:** 7,837
- **Characters saved:** 77,426

## By pattern

| Pattern | Description | Incidents | Chars saved |
|---|---|---:|---:|
| P1 | Typed val/var/def with redundant prefix | 4,885 | 40,643 |
| P2 | Default argument with redundant prefix | 925 | 13,039 |
| P3 | Constructor self-prefix in args | 192 | 2,935 |
| P4 | Generic typed apply with redundant prefix | 246 | 2,239 |
| P5 | `case T.X` after typed scrutinee | 1,589 | 18,570 |

## By project

| Project | Incidents | Chars saved |
|---|---:|---:|
| `zio` | 1,644 | 12,568 |
| `tapir` | 1,250 | 14,756 |
| `http4s` | 558 | 5,267 |
| `cats` | 527 | 3,482 |
| `scala-cli` | 493 | 5,148 |
| `cats-effect` | 450 | 2,744 |
| `fs2` | 399 | 3,009 |
| `circe` | 377 | 2,909 |
| `sttp` | 356 | 4,011 |
| `playframework` | 356 | 3,621 |
| `ScalaPB` | 313 | 7,077 |
| `scalatest` | 280 | 2,780 |
| `scala3-compiler` | 277 | 4,485 |
| `scala3-library` | 163 | 1,856 |
| `upickle` | 100 | 1,256 |
| `scala3-tests-pos` | 88 | 539 |
| `scala3-tests-run` | 76 | 547 |
| `scala3-presentation-compiler` | 31 | 472 |
| `scala3-tests-neg` | 30 | 176 |
| `os-lib` | 29 | 430 |
| `quicklens` | 24 | 131 |
| `requests-scala` | 9 | 91 |
| `cask` | 5 | 51 |
| `scalatags` | 2 | 20 |

## Methodology

- Source corpus: shallow scan of `.scala`/`.sc` files; `.git`, `target`, `out`, `node_modules`, `.bloop`, `.metals`, `.bsp`, `project` directories skipped.
- Comments (line and nested block) and string literals (double-quoted, triple-quoted, and interpolated) are blanked before regex matching, so no incident comes from prose or test fixtures.
- Five high-confidence patterns are recognised — see `scan.py` for definitions. Each match must come from a position where SIP-80's lexical and expected-type rules actually fire, so this number is a *lower bound*: cases where the user wrote `import T.*` and used the bare name are not counted.
- Reproduce with `bash run.sh all` from this directory.
