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
# SCALA_VERSION is the scala3-tasty-inspector version used to BUILD the
# inspector and to RUN it on the fixtures. For ``jar``/``maven`` targets
# we override per invocation based on the target's TASTy version (see
# pick_inspector_version below) so an inspector built against e.g. 3.7.4
# can still read TASTy 28.8 if launched via a 3.8.x runtime classpath.
SCALA_VERSION="${SCALA_VERSION:-3.7.4}"

if [ ! -f "$CS_JAR" ]; then
  echo "coursier launcher not found at $CS_JAR; set CS_JAR to override" >&2
  exit 2
fi

# pick_inspector_version <jar>
#   Read the TASTy major.minor of <jar>'s first .tasty entry and pick the
#   smallest published scala3-tasty-inspector_3 that can read it. The TASTy
#   major.minor maps directly to scala3-compiler 3.<minor>; we pick the
#   latest patch in that line for max bug-fix coverage. Our inspector's
#   source itself uses post-3.3 syntax (extension methods, ``using`` etc.)
#   so we floor the build version at 3.3.x; the older runtime forms are
#   forward-compatible with TASTy 28.0..28.<minor> through that floor.
#   Echoes the version (e.g. "3.8.3") on stdout.
pick_inspector_version() {
  local jar="$1"
  local tv
  tv="$(python3 "$HERE/detect-tasty-version.py" "$jar" 2>/dev/null)" || return 1
  case "$tv" in
    28.*) ;;
    *) return 1 ;;
  esac
  local minor="${tv#28.}"
  if [ "$minor" -lt 3 ]; then minor=3; fi
  java -jar "$CS_JAR" complete-dep \
        "org.scala-lang:scala3-tasty-inspector_3:3.${minor}." 2>/dev/null \
    | grep -E '^3\.[0-9]+\.[0-9]+$' | tail -1
}

# build_inspector_for <inspector-version>
#   Compile src/Sip80Inspector.scala against the given inspector version
#   and emit classes under build/<version>/. Skipped if cached. Echoes
#   the build classpath on stdout.
build_inspector_for() {
  local v="$1"
  local out_dir="$HERE/build/$v"
  local cp
  cp="$(java -jar "$CS_JAR" fetch --classpath \
         "org.scala-lang:scala3-tasty-inspector_3:$v" 2>/dev/null)"
  local marker="$out_dir/sip80/Main.class"
  if [ ! -f "$marker" ] || [ "$HERE/src/Sip80Inspector.scala" -nt "$marker" ]; then
    echo "=== Compiling inspector against $v ===" >&2
    mkdir -p "$out_dir"
    # The compiler launcher needs to match the inspector version too —
    # 3.M.x compilers know about 3.M.x APIs. Using a vastly newer
    # compiler against an older inspector classpath occasionally fails
    # on package-rename issues (e.g. ``scala.caps``).
    java -jar "$CS_JAR" launch "scala3-compiler:$v" -- \
        -classpath "$cp" -d "$out_dir" \
        "$HERE/src/Sip80Inspector.scala"
  fi
  echo "$out_dir:$cp"
}

CP="$(java -jar "$CS_JAR" fetch --classpath \
       "org.scala-lang:scala3-tasty-inspector_3:$SCALA_VERSION" 2>/dev/null)"

mkdir -p "$HERE/build" "$HERE/out/fixtures" "$HERE/results"

scalac() {
  java -jar "$CS_JAR" launch "scala3-compiler:$SCALA_VERSION" -- "$@"
}

# 1. Compile the inspector for the default ``$SCALA_VERSION`` (used by the
# fixtures self-test). ``jar`` and ``maven`` modes call build_inspector_for
# again, with a target-specific version.
DEFAULT_BUILD_CP="$(build_inspector_for "$SCALA_VERSION")"

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
    java -cp "$DEFAULT_BUILD_CP" sip80.Main fixtures \
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
    rt_ver="$(pick_inspector_version "$jar" 2>/dev/null || true)"
    rt_ver="${rt_ver:-$SCALA_VERSION}"
    rt_cp="$(build_inspector_for "$rt_ver")"
    echo "=== Running inspector on $jar (TASTy reader: $rt_ver) ===" >&2
    java -cp "$rt_cp" sip80.Main jar \
      "$jar" "$project" "$HERE/results" "$src" "$deps"
    ;;
  maven)
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
    rt_ver="$(pick_inspector_version "$bin_jar" 2>/dev/null || true)"
    rt_ver="${rt_ver:-$SCALA_VERSION}"
    rt_cp="$(build_inspector_for "$rt_ver")"
    echo "=== Running inspector on $project (TASTy reader: $rt_ver) ===" >&2
    java -cp "$rt_cp" sip80.Main jar \
      "$bin_jar" "$project" "$HERE/results" "$src_dir" "$deps_cp"
    ;;
  *)
    echo "usage: $0 [fixtures | jar <jar> [project] [src-root]]" >&2
    exit 2
    ;;
esac
