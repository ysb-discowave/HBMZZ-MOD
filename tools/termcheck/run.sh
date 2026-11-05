#!/usr/bin/env bash
# Runs tools/termcheck: the terminal's grammar, completion and dispatch.
#
#   ./tools/termcheck/run.sh
#
# Compiles the check against the classes the build just produced, plus the
# stand-ins tools/linkercheck already maintains for BlockPos/NBT/World. Those
# stand-ins are shared rather than copied: two divergent fakes of the same
# class is how an offline test starts passing for the wrong reason.
#
# What is exercised is the real TerminalShell against the real CtrlPanel and
# CtrlWidget - the part of the terminal that cannot be seen without pressing
# Tab in a running game. Machine I/O is not: no NTM here, so those paths
# deliberately report "读不到", which is itself asserted.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSDIR="build/classes/java/main"
OUT="tmp/termcheck-out"

if [ ! -d "$CLASSDIR" ]; then
  echo "build first: gradle build (compileJava is enough)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# Compiling against the build output means only the classes the check actually
# touches are needed; the rest of the mod stays out of it.
"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" -cp "$CLASSDIR" \
  $(find tools/linkercheck/stubs tools/termcheck/src -name '*.java')

# -noverify: TerminalShell pulls in MachineBridge, which mentions HBMZZ (the
# Forge-side main class) in one logging path. Nothing on these paths runs, and
# without verification the JVM never has to load it.
"$JAVA" -noverify -cp "$OUT;$CLASSDIR" TermCheck
