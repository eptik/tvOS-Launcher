#!/bin/bash
# JVM tests for the launcher's pure-Java logic. No device needed.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf /tmp/tvos-jvm && mkdir -p /tmp/tvos-jvm
SRC=src/com/techgenius/tvoslauncher
javac -d /tmp/tvos-jvm "$SRC/SessionPick.java" work/SessionPickTest.java
java -cp /tmp/tvos-jvm SessionPickTest
javac -d /tmp/tvos-jvm "$SRC/WallperCatalogParse.java" work/WallperCatalogTest.java
PAGE="${WALLPER_PAGE:-/tmp/wallper.html}"
if [ ! -f "$PAGE" ]; then
  curl -s -A "Mozilla/5.0" "https://www.wallper.app/wallpapers?sort=Most+Liked" -o "$PAGE"
fi
java -cp /tmp/tvos-jvm WallperCatalogTest "$PAGE"
echo "JVM tests green"
