#!/bin/bash
set -uo pipefail

variant="${1:?debug or release}"
folder="${2:-app/build/playback-hazards}"
package="de.test.antennapod.playback.hazards"
group="${4:-controls}"
case "$group" in
    controls) names="ResolvedTaggedAudioTest,ResolvedEpisodeServerTest,PassageOracleTest,PlaybackLifecycleTest"; expected=32 ;;
    resolution) names="ResolutionChangeTest"; expected=4 ;;
    mutation) names="SameUrlChangeTest"; expected=3 ;;
    head-get) names="HeadGetDisagreementTest"; expected=1 ;;
    transport) names="ReconnectTest,RangeFallbackTest"; expected=4 ;;
    expiry) names="ExpiryTest"; expected=2 ;;
    cache) names="CacheEvictionTest,CacheReuseTest"; expected=4 ;;
    *) echo "Unknown hazard group: $group" >&2; exit 2 ;;
esac
classes=""
for name in ${names//,/ }; do classes+="${classes:+,}$package.$name"; done
mkdir -p "$folder"
repetition="${3:-1}"
logcatPid=
trap '[ -z "$logcatPid" ] || kill "$logcatPid" 2>/dev/null || true' EXIT
output="$folder/repetition-$repetition"
mkdir -p "$output"
adb logcat -b all -c > "$output/logcat-clear.txt" 2>&1
adb logcat -b all -v threadtime > "$output/logcat.txt" 2> "$output/logcat-error.txt" &
logcatPid=$!
timeout 1800s adb shell am instrument -w -r -e class "$classes" \
    de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner \
    > "$output/runner.txt" 2>&1
status=$?
kill "$logcatPid" 2>/dev/null || true
wait "$logcatPid" 2>/dev/null || true
logcatPid=
printf '%s\n' "$status" > "$output/command-status.txt"
printf 'Full evidence: playback-hazards-%s-%s-%s artifact, repetition-%s/\n' "$group" "$variant" "$repetition" "$repetition"
if [ "$status" -ne 0 ] || ! grep -Eq "^OK \($expected tests?\)" "$output/runner.txt" \
        || ! grep -q '^INSTRUMENTATION_CODE: -1' "$output/runner.txt"; then
    printf 'FAILED: %s repetition %s (command status %s)\n' "$variant" "$repetition" "$status"
    tail -n 40 "$output/runner.txt"
    grep -E 'ServiceAudioObserver.*(observer=|programTarget=|unavailable state=|requests=|request=|expiry |transfer |pressure |eviction )' \
        "$output/logcat.txt" | tail -n 140 || true
    exit 1
else
    printf 'PASSED: %s %s repetition %s, all %s tests\n' "$group" "$variant" "$repetition" "$expected"
    grep -E 'ServiceAudioObserver.*(expiry responses=|transfer coldBytes=)' \
        "$output/logcat.txt" | tail -n 20 || true
fi
