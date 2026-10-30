#!/bin/bash
set -euo pipefail

variant="${1:?debug or release}"
adb install "app/build/outputs/apk/play/$variant/app-play-$variant.apk"
adb install "app/build/outputs/apk/androidTest/play/$variant/app-play-$variant-androidTest.apk"
exec bash .github/workflows/runPlaybackHazards.sh "$@"
