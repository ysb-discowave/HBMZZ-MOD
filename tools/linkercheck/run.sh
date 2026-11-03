#!/usr/bin/env bash
# Runs tools/linkercheck against the classes the build just produced.
#
#   ./tools/linkercheck/run.sh [path-to-NTM-CE.jar]
#
# The control panel's machine import and its event broadcast are the two steps
# that used to fail silently: a coordinate that resolved to nothing simply
# disappeared, and an event aimed at a linked machine was looked up in a table
# that never holds it and dropped without a word. This script exercises both
# for real - the real LinkerImport, the real ItemMonitorLinker NBT parser, the
# real ControlEventSystem, the real MachineBridge probe - against small
# stand-ins for BlockPos/NBT/World (same packing, same tag ids). Passing means
# the coordinate -> machine and panel -> machine paths work; it does not touch
# the GUI.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSDIR="build/classes/java/main"
OUT="tmp/linkercheck-out"
CE="${1:-}"

if [ ! -d "$CLASSDIR" ]; then
  echo "build first: ./gradlew build (or gradle build)" >&2
  exit 1
fi

# fastutil is a plain jar dependency of the game, not something we can stand in
# for: ControlEventSystem keys its tables with Long2ObjectOpenHashMap, and the
# compiled class binds to the real method descriptors. One of the cache copies
# is enough - any 8.5.x works.
FASTUTIL="${FASTUTIL:-}"
if [ -z "$FASTUTIL" ]; then
  FASTUTIL="$(ls -1 "$HOME"/.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil/*/*/fastutil-*.jar 2>/dev/null | tail -1)"
fi
if [ -z "$FASTUTIL" ] || [ ! -f "$FASTUTIL" ]; then
  echo "fastutil jar not found; set FASTUTIL=/path/to/fastutil.jar" >&2
  exit 1
fi
FASTUTIL_WIN="$(cygpath -w "$FASTUTIL" 2>/dev/null || echo "$FASTUTIL")"

rm -rf "$OUT"
mkdir -p "$OUT"

CP="$CLASSDIR;$FASTUTIL_WIN"
if [ -n "$CE" ]; then
  CP="$CP;$CE"
fi

# shellcheck disable=SC2046
"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" $(find tools/linkercheck/stubs tools/linkercheck/src -name '*.java')

# -noverify: without it the JVM insists on loading every class the stand-ins
# stand in for, including the whole entity/player graph the CE items pull in.
"$JAVA" -noverify -cp "$OUT;$CP" LinkerCheck
