#!/usr/bin/env bash
# Runs tools/rbmkcheck: the staged RBMK blow-up, without a reactor.
#
#   ./tools/rbmkcheck/run.sh
#
# A meltdown is the one feature in this mod that cannot be checked by looking at
# it: by the time it runs, the test save is on fire. So the three parts that
# decide *when* it happens and *how* it looks are pure - no world, no tile
# entity, no client - and this script runs them a few thousand times instead.
#
# What is exercised: RbmkTiming (trip condition, one-minute countdown, jump
# curve, launch speeds), RbmkCluster (which columns are one reactor) and
# RbmkLayout (the plate's contents on the wire). What is not: the explosions and
# the flying plate itself, which need the game and a player willing to melt one
# down.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSDIR="build/classes/java/main"
OUT="tmp/rbmkcheck-out"

if [ ! -d "$CLASSDIR" ]; then
  echo "build first: gradle build (compileJava is enough)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# Same BlockPos stand-in the other offline checks use: two of these fakes would
# be two different BlockPos classes, and the tests would pass for the wrong
# reason.
"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" -cp "$CLASSDIR" \
  $(find tools/linkercheck/stubs tools/rbmkcheck/src -name '*.java')

"$JAVA" -noverify -cp "$OUT;$CLASSDIR" RbmkCheck
