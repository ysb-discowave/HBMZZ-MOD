#!/usr/bin/env bash
# Runs tools/videocheck against the classes the build just produced.
#
#   ./tools/videocheck/run.sh
#
# VideoClip / VideoLibrary are plain Java (no Minecraft classes), so the whole
# "drop a zip in config/hbmzz/videos and it plays" path can be exercised without
# starting the game: folder clips, zip clips, a meta.json that lies about the
# frame count, and the name whitelist that stops a packet from reading files
# outside the video folder.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSDIR="build/classes/java/main"
OUT="tmp/videocheck-out"

if [ ! -d "$CLASSDIR" ]; then
  echo "build first: ./gradlew build (or gradle build)" >&2
  exit 1
fi

# gson parses meta.json, log4j-api is what VideoClip logs through. Both are game
# dependencies already sitting in the gradle cache.
GSON="${GSON:-}"
if [ -z "$GSON" ]; then
  GSON="$(ls -1 "$HOME"/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | tail -1)"
fi
LOG4J="${LOG4J:-}"
if [ -z "$LOG4J" ]; then
  LOG4J="$(ls -1 "$HOME"/.gradle/caches/modules-2/files-2.1/org.apache.logging.log4j/log4j-api/*/*/log4j-api-*.jar 2>/dev/null | tail -1)"
fi
if [ -z "$GSON" ] || [ ! -f "$GSON" ]; then
  echo "gson jar not found; set GSON=/path/to/gson.jar" >&2
  exit 1
fi
if [ -z "$LOG4J" ] || [ ! -f "$LOG4J" ]; then
  echo "log4j-api jar not found; set LOG4J=/path/to/log4j-api.jar" >&2
  exit 1
fi
GSON_WIN="$(cygpath -w "$GSON" 2>/dev/null || echo "$GSON")"
LOG4J_WIN="$(cygpath -w "$LOG4J" 2>/dev/null || echo "$LOG4J")"

rm -rf "$OUT"
mkdir -p "$OUT"

CP="$CLASSDIR;$GSON_WIN;$LOG4J_WIN"

"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" tools/videocheck/src/VideoCheck.java
"$JAVA" -Djava.awt.headless=true -cp "$OUT;$CP" VideoCheck
