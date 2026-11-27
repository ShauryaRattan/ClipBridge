#!/usr/bin/env bash
# Build ClipBridge on Linux/macOS (same steps as build.bat on Windows).
#   ./build.sh            compile, run the tests, make build/clipbridge.jar
#   ./build.sh skiptests  same, without the tests
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/testclasses build/input
javac -encoding UTF-8 -Xlint:all -d build/classes $(find src -name '*.java')
cp src/clipbridge/gui/*.png build/classes/clipbridge/gui/
if [ "${1:-}" != "skiptests" ]; then
  javac -encoding UTF-8 -d build/testclasses -cp build/classes $(find test -name '*.java')
  java -cp build/classes:build/testclasses clipbridge.AllTests
fi
jar --create --file build/clipbridge.jar --main-class clipbridge.Main -C build/classes .
cp build/clipbridge.jar build/input/
echo "Built build/clipbridge.jar   (run: java -jar build/clipbridge.jar)"
