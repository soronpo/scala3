#!/bin/bash
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JAVA17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"

if [ ! -x "$JAVA17_HOME/bin/java" ]; then
  echo "Java 17 not found at $JAVA17_HOME" >&2
  exit 1
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JAVA17_HOME\""
    echo "export PATH=\"$JAVA17_HOME/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "Default Java set to 17 ($JAVA17_HOME)"
"$JAVA17_HOME/bin/java" -version
