#!/bin/bash
set -eu
for script in .github/workflows/*PlaybackHazards.sh .github/workflows/fixtures/playback-adb.sh; do
    bash -n "$script"
done
folder=$(mktemp -d)
trap 'rm -rf "$folder"' EXIT
mkdir -p "$folder/bin"
cp .github/workflows/fixtures/playback-adb.sh "$folder/bin/adb"
chmod +x "$folder/bin/adb"
export PATH="$folder/bin:$PATH" COUNTER="$folder/count" LOG_READY="$folder/log-ready" ADB_CALLS="$folder/adb-calls"
for MODE in pass fail-first missing missing-code wrong-count empty command-error interrupted timed-out long-failure; do
    export MODE
    echo 0 > "$COUNTER"
    status=0
    bash .github/workflows/runPlaybackHazards.sh debug "$folder/$MODE" > "$folder/$MODE.txt" || status=$?
    test "$(cat "$COUNTER")" = 1
    test "$(wc -l < "$folder/$MODE.txt")" -le 200
    if [ "$MODE" = long-failure ]; then grep -q "observer=controller" "$folder/$MODE.txt"; fi
    if [ "$MODE" = pass ]; then
        test "$status" = 0
        grep -q 'expiry responses=1' "$folder/$MODE.txt"
    else
        test "$status" = 1
    fi
    output="$folder/$MODE/repetition-1"
    test -f "$output/runner.txt"
    case "$MODE" in
        empty|interrupted|timed-out) test ! -s "$output/runner.txt" ;;
        *) test -s "$output/runner.txt" ;;
    esac
    case "$MODE" in
        command-error) expected=7 ;;
        interrupted) expected=143 ;;
        timed-out) expected=124 ;;
        *) expected=0 ;;
    esac
    test "$(cat "$output/command-status.txt")" = "$expected"
    test -s "$output/logcat.txt"
    if [ "$MODE" = long-failure ]; then
        test "$(wc -l < "$output/runner.txt")" = 500
        test "$(wc -l < "$output/logcat.txt")" = 500
    fi
done
export MODE=pass EXPECTED_TESTS=3
bash .github/workflows/runPlaybackHazards.sh debug "$folder/group" 1 mutation > "$folder/group.txt"
grep -q 'all 3 tests' "$folder/group.txt"
export MODE=fail-first EXPECTED_TESTS=32
echo 0 > "$COUNTER"
if bash .github/workflows/runPlaybackHazards.sh debug "$folder/retained" 1 > "$folder/first.txt"; then exit 1; fi
export MODE=pass
bash .github/workflows/runPlaybackHazards.sh debug "$folder/retained" 2 > "$folder/second.txt"
grep -q 'FAILURES!!!' "$folder/retained/repetition-1/runner.txt"
grep -q 'OK (32 tests)' "$folder/retained/repetition-2/runner.txt"
for variant in debug release; do
    echo 0 > "$COUNTER"
    : > "$ADB_CALLS"
    bash .github/workflows/installPlaybackHazards.sh "$variant" "$folder/install-$variant" > "$folder/install-$variant.txt"
    test "$(cat "$COUNTER")" = 1
    grep -Fxq "install app/build/outputs/apk/play/$variant/app-play-$variant.apk" "$ADB_CALLS"
    grep -Fxq "install app/build/outputs/apk/androidTest/play/$variant/app-play-$variant-androidTest.apk" "$ADB_CALLS"
done
export MODE=install-error
echo 0 > "$COUNTER"
status=0
bash .github/workflows/installPlaybackHazards.sh debug "$folder/install-error" > "$folder/install-error.txt" || status=$?
test "$status" = 9
test "$(cat "$COUNTER")" = 0
test ! -e "$folder/install-error/repetition-1/runner.txt"
echo 'Hazard runner controls passed'
