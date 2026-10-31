#!/bin/bash
set -euo pipefail

variant="${1:?debug or release}"
folder="${2:-app/build/playback-hazards}/repetition-${3:-1}/setup"
mkdir -p "$folder"
retryUsed=0
printf 'Setup evidence: playback-hazards-%s-%s-%s artifact, repetition-%s/setup/\n' \
    "${4:-controls}" "$variant" "${3:-1}" "${3:-1}"

installApk() {
    local apk="$1" name="$2" attempt prefix log readyStatus status diagnosticStatus
    local flags=()
    for attempt in 1 2; do
        prefix="$folder/$name-$attempt"
        log="$prefix-readiness.txt"
        readyStatus=0
        timeout -k 2s 30s bash -o pipefail -s -- "$log" <<'READINESS' || readyStatus=$?
until adb shell cmd package path android 2>&1 | tee -a "$1" | grep -q '^package:'; do
    sleep 1
done
READINESS
        printf '%s\n' "$readyStatus" > "$prefix-readiness-status.txt"
        status=$readyStatus
        if [ "$readyStatus" -eq 0 ]; then
            log="$prefix-install.txt"
            adb install "${flags[@]}" "$apk" > "$log" 2>&1 || status=$?
            printf '%s\n' "$status" > "$prefix-install-status.txt"
        fi
        printf 'APK setup %s attempt %s: status %s\n' "$name" "$attempt" "$status"
        if [ "$status" -eq 0 ]; then return 0; fi
        tail -n 4 "$log" 2>/dev/null || true
        diagnosticStatus=0
        timeout -k 2s 10s adb logcat -b all -d -t 2000 -v threadtime \
            > "$prefix-logcat.txt" 2>&1 || diagnosticStatus=$?
        printf '%s\n' "$diagnosticStatus" > "$prefix-logcat-status.txt"
        if [ "$attempt" -eq 2 ] || [ "$retryUsed" -eq 1 ] \
                || [ "$readyStatus" -ne 0 ] || [ "$status" -ne 1 ] \
                || ! grep -Fxq "adb: failed to install $apk: cmd: Failure calling service package: Broken pipe (32)" "$log"; then
            return "$status"
        fi
        retryUsed=1
        flags=(-r)
    done
}

installApk "app/build/outputs/apk/play/$variant/app-play-$variant.apk" app
installApk "app/build/outputs/apk/androidTest/play/$variant/app-play-$variant-androidTest.apk" tests
exec bash .github/workflows/runPlaybackHazards.sh "$@"
