#!/bin/bash
set -euo pipefail

variant="${1:?debug or release}"
apks=app/build/outputs/apk
./gradlew ":app:assemblePlay${variant^}" ":app:assemblePlay${variant^}AndroidTest" "-PtestBuildType=$variant"
sha256sum "$apks/play/$variant/"*.apk "$apks/androidTest/play/$variant/"*.apk > "$apks/hashes.txt"
cat "$apks/hashes.txt"
