#!/usr/bin/env bash
# Runs tools/cecpcheck: the console editor's control-type table.
#
#   ./tools/cecpcheck/run.sh
#
# The console editor (com.hbmzz.cecp) is ported code on top of the mod's own
# registry, and its failure mode is a crash on open - the screen renders once,
# throws inside initGui, and the next tick dies on an empty element stack. That
# chain is why this check exists: the interesting part is not "does it draw",
# it is "is the table it reads already complete before anything reads it".
#
# Fastutil is needed because ControlRegistry stores controls in an
# Object2ObjectLinkedOpenHashMap. It is a compile dependency of the mod, so the
# Gradle cache always has it; the path is located rather than hardcoded.
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSDIR="build/classes/java/main"
OUT="tmp/cecpcheck-out"

if [ ! -d "$CLASSDIR" ]; then
  echo "build first: gradle build (compileJava is enough)" >&2
  exit 1
fi

FASTUTIL="$(ls -1 "$HOME/.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil"/*/*/fastutil-*.jar 2>/dev/null \
  | grep -v -- '-sources' | head -1 || true)"
if [ -z "$FASTUTIL" ]; then
  echo "fastutil not found in the Gradle cache; run a build once with network on" >&2
  exit 1
fi

# MSYS hands out /c/Users/... paths; the Windows JVM reads that as a relative
# directory and then reports the missing class instead of the missing path.
if command -v cygpath >/dev/null 2>&1; then
  FASTUTIL="$(cygpath -m "$FASTUTIL")"
else
  FASTUTIL="$(echo "$FASTUTIL" | sed -E 's#^/([a-zA-Z])/#\1:/#')"
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# The stand-ins from tools/linkercheck keep CtrlPanel/NBT/World out of this:
# those stubs are shared so the three offline checks cannot drift apart.
"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" -cp "$CLASSDIR;$FASTUTIL" \
  $(find tools/linkercheck/stubs tools/cecpcheck/src -name '*.java')

# A fresh JVM matters: class initialisation is the subject here, and a warm JVM
# would already have run the addon types' static initialisers.
"$JAVA" -noverify -cp "$OUT;$CLASSDIR;$FASTUTIL" CecpCheck
