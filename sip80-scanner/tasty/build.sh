#!/usr/bin/env bash
# Build the SIP-80 TastyInspector and run it against the fixtures.
#
#   ./build.sh                 — compile inspector + fixtures, run self-test
#   ./build.sh fixtures        — same as default
#   ./build.sh jar <jar> <pj>  — run on a Maven Central _3.jar
#
# Outputs land under tasty/results/.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CS_JAR="${CS_JAR:-/home/user/scala3/dist/target/republish/coursier/coursier.jar}"
SCALA_VERSION="${SCALA_VERSION:-3.7.4}"

if [ ! -f "$CS_JAR" ]; then
  echo "coursier launcher not found at $CS_JAR; set CS_JAR to override" >&2
  exit 2
fi

CP="$(java -jar "$CS_JAR" fetch --classpath \
       "org.scala-lang:scala3-tasty-inspector_3:$SCALA_VERSION" 2>/dev/null)"

mkdir -p "$HERE/build" "$HERE/out/fixtures" "$HERE/results"

scalac() {
  java -jar "$CS_JAR" launch "scala3-compiler:$SCALA_VERSION" -- "$@"
}

# 1. Compile the inspector if the source is newer than the build artifact.
SRC="$HERE/src/Sip80Inspector.scala"
MARKER="$HERE/build/sip80/Main.class"
if [ ! -f "$MARKER" ] || [ "$SRC" -nt "$MARKER" ]; then
  echo "=== Compiling inspector ===" >&2
  scalac -classpath "$CP" -d "$HERE/build" "$SRC"
fi

# 2. Compile the fixtures to TASTy. Run scalac with cwd=$HERE so that the
# TASTy positions store paths like ``fixtures/Patterns.scala`` that the
# inspector can resolve back to the source files.
FIXTURES_MARKER="$HERE/out/fixtures/fixtures/Patterns.tasty"
if [ ! -f "$FIXTURES_MARKER" ] ||
   [ "$HERE/fixtures/Patterns.scala" -nt "$FIXTURES_MARKER" ]; then
  echo "=== Compiling fixtures to TASTy ===" >&2
  pushd "$HERE" > /dev/null
  scalac -d out/fixtures -Yexplicit-nulls \
    fixtures/Patterns.scala \
    fixtures/FalsePositives.scala \
    fixtures/StageB.scala \
    fixtures/Negatives.scala
  popd > /dev/null
fi

mode="${1:-fixtures}"
case "$mode" in
  fixtures)
    echo "=== Running inspector on fixtures ===" >&2
    # ``$HERE`` is the source root because TASTy positions for fixtures
    # are relative to it (we ran scalac from this directory below).
    java -cp "$HERE/build:$CP" sip80.Main fixtures \
      "$HERE/out" "$HERE/results" "$HERE"
    echo "=== Self-test ===" >&2
    python3 "$HERE/check.py" "$HERE/results" "$HERE/fixtures/expected.json"
    ;;
  jar)
    jar="${2:-}"
    project="${3:-${jar##*/}}"
    src="${4:-}"
    deps="${5:-}"
    if [ -z "$jar" ]; then
      echo "usage: $0 jar <jar> [project-name] [src-root] [deps-cp]" >&2
      exit 2
    fi
    echo "=== Running inspector on $jar ===" >&2
    java -cp "$HERE/build:$CP" sip80.Main jar \
      "$jar" "$project" "$HERE/results" "$src" "$deps"
    ;;
  maven)
    # Convenience wrapper: takes a Maven coordinate like
    # ``org.typelevel:cats-core_3:2.13.0`` and resolves jar + sources +
    # transitive deps via coursier, then runs the inspector.
    coord="${2:-}"
    if [ -z "$coord" ]; then
      echo "usage: $0 maven <group:artifact_3:version>" >&2
      exit 2
    fi
    project=$(echo "$coord" | awk -F: '{print $2}' | sed 's/_3$//')
    bin_jar=$(java -jar "$CS_JAR" fetch "$coord" 2>/dev/null \
              | grep "${project}_3-" | head -1)
    deps_cp=$(java -jar "$CS_JAR" fetch --classpath "$coord" 2>/dev/null)
    src_jar=$(java -jar "$CS_JAR" fetch --sources "$coord" 2>/dev/null \
              | grep "${project}_3-" | head -1)
    src_dir="/tmp/sip80-tasty-sources/$project"
    if [ -n "$src_jar" ] && [ ! -d "$src_dir" ]; then
      mkdir -p "$src_dir"
      ( cd "$src_dir" && jar xf "$src_jar" )
    fi
    echo "=== Running inspector on $project ===" >&2
    java -cp "$HERE/build:$CP" sip80.Main jar \
      "$bin_jar" "$project" "$HERE/results" "$src_dir" "$deps_cp"
    ;;
  *)
    echo "usage: $0 [fixtures | jar <jar> [project] [src-root]]" >&2
    exit 2
    ;;
esac
