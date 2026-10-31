#!/bin/bash
set -uo pipefail

variant="${1:?debug or release}"
folder="${2:-app/build/service-audio-calibration}"
classes="de.test.antennapod.playback.ServiceAudioObserverTest,de.test.antennapod.playback.ResolvedTaggedAudioTest,de.test.antennapod.playback.ResolvedEpisodeServerTest"
mkdir -p "$folder"
repetition="${3:-1}"
logcatPid=
trap '[ -z "$logcatPid" ] || kill "$logcatPid" 2>/dev/null || true' EXIT
output="$folder/repetition-$repetition"
mkdir -p "$output"
adb logcat -c > "$output/logcat-clear.txt" 2>&1
adb logcat -b all -v threadtime > "$output/logcat.txt" 2> "$output/logcat-error.txt" &
logcatPid=$!
timeout 1200s adb shell am instrument -w -r -e class "$classes" \
    de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner \
    > "$output/runner.txt" 2>&1
status=$?
kill "$logcatPid" 2>/dev/null || true
wait "$logcatPid" 2>/dev/null || true
logcatPid=
printf '%s\n' "$status" > "$output/command-status.txt"
printf 'Full evidence: service-audio-%s-%s artifact, repetition-%s/\n' "$variant" "$repetition" "$repetition"
if [ "$status" -ne 0 ] || ! grep -q '^OK (21 tests)' "$output/runner.txt" \
        || ! grep -q '^INSTRUMENTATION_CODE: -1' "$output/runner.txt"; then
    printf 'FAILED: %s repetition %s (command status %s)\n' "$variant" "$repetition" "$status"
    tail -n 40 "$output/runner.txt"
    grep -E 'ServiceAudioObserver.*(observer=|programTarget=|unavailable state=|requests=)' \
        "$output/logcat.txt" | tail -n 140 || true
    exit 1
else
    printf 'PASSED: %s repetition %s, all 21 tests\n' "$variant" "$repetition"
fi
