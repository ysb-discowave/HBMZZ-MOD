#!/usr/bin/env bash
# Structural parity check of the ported control panel against the upstream jar.
#
#   ./tools/parity/run.sh "/c/path/to/NTM-CE.jar"
#
# Needs javap (JDK 8 is fine) and a JDK 11+ `java` for vineflower. Override with
# JAVA=/path/to/java when the default is not on PATH.
set -euo pipefail
cd "$(dirname "$0")/../.."

JAR="${1:?usage: run.sh <NTM-CE.jar>}"
JAVA="${JAVA:-C:/Program Files/Java/jdk-25.0.4/bin/java}"
JAVAP="${JAVAP:-C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot/bin/javap.exe}"
PY="${PY:-C:/Users/Yang Sibo/.workbuddy/binaries/python/versions/3.13.12/python.exe}"

"$PY" tools/parity/parity.py "$JAR" --java "$JAVA" --javap "$JAVAP"
