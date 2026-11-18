#!/usr/bin/env bash
# Runs tools/reactorcheck: the reactor's decision layer, without a reactor.
#
#   ./tools/reactorcheck/run.sh
#
# A scram that fires late, a core that restarts after meltdown, or a NaN that
# turns into full power - none of these show up until the base is gone. So the
# physics and the safety rules are pure functions, and this script walks a core
# through them a few thousand times instead of once in a game.
#
# What is exercised: ReactorSpec validation, ReactorInput clamping,
# ReactorRules (target flux, feedback, heat balance, power), ReactorMachine
# (phase transitions, scram, runaway, meltdown) and the SimpleReactor api.
# What is not: the block, the tile entity, the renderer and the network packet.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
OUT="tmp/reactorcheck-out"

if [ ! -x "$JAVAC" ]; then
  echo "javac not found at $JAVAC (set JAVA_HOME)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# Compile only the pure reactor package together with the check. No Minecraft
# classes, no stubs, no gradle: this must stay runnable on a bare JDK.
"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" \
  $(find src/main/java/com/hbmzz/reactor -name '*.java') \
  tools/reactorcheck/src/ReactorCheck.java

"$JAVA" -noverify -cp "$OUT" ReactorCheck
