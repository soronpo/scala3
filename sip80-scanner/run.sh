#!/usr/bin/env bash
# Orchestrate a SIP-80 scanner run.
#
#   ./run.sh local                  # scan only local Scala 3 sources
#   ./run.sh corpus                 # clone curated OSS repos and scan them
#   ./run.sh all                    # both, then aggregate
#
# Outputs land under ``sip80-scanner/results/``.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCALA3_ROOT="$(cd "$HERE/.." && pwd)"
CORPUS_ROOT="${SIP80_CORPUS_ROOT:-/tmp/sip80-corpus}"
OUT_DIR="$HERE/results"

mode="${1:-all}"

mkdir -p "$OUT_DIR" "$CORPUS_ROOT"

run_local() {
  echo "=== Scanning local Scala 3 sources ===" >&2
  python3 "$HERE/scan.py" \
    --root "$SCALA3_ROOT/compiler/src" \
    --project scala3-compiler \
    --root "$SCALA3_ROOT/library/src" \
    --project scala3-library \
    --root "$SCALA3_ROOT/presentation-compiler/src" \
    --project scala3-presentation-compiler \
    --root "$SCALA3_ROOT/tests/pos" \
    --project scala3-tests-pos \
    --root "$SCALA3_ROOT/tests/run" \
    --project scala3-tests-run \
    --root "$SCALA3_ROOT/tests/neg" \
    --project scala3-tests-neg \
    --out-dir "$OUT_DIR" \
    --label local
}

clone_corpus() {
  echo "=== Cloning curated OSS corpus to $CORPUS_ROOT ===" >&2
  while IFS= read -r raw; do
    line="${raw%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] && continue
    owner="${line%%/*}"
    repo="${line##*/}"
    target="$CORPUS_ROOT/$repo"
    if [ -d "$target/.git" ]; then
      echo "[skip] $owner/$repo already present" >&2
      continue
    fi
    rm -rf "$target"
    echo "[clone] $owner/$repo" >&2
    if ! gh repo clone "$owner/$repo" "$target" -- --depth 1 --quiet 2>&1; then
      echo "[fail] $owner/$repo" >&2
    fi
  done < "$HERE/corpus.txt"
}

run_corpus() {
  echo "=== Scanning corpus under $CORPUS_ROOT ===" >&2
  python3 "$HERE/scan.py" \
    --corpus "$HERE/corpus.txt" \
    --corpus-root "$CORPUS_ROOT" \
    --out-dir "$OUT_DIR" \
    --label corpus
}

aggregate() {
  echo "=== Aggregating results ===" >&2
  python3 "$HERE/aggregate.py" "$OUT_DIR"
}

case "$mode" in
  local)
    run_local
    aggregate
    ;;
  corpus)
    clone_corpus
    run_corpus
    aggregate
    ;;
  all)
    run_local
    clone_corpus
    run_corpus
    aggregate
    ;;
  *)
    echo "usage: $0 {local|corpus|all}" >&2
    exit 2
    ;;
esac
