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
        command-error) expected=7 ;; # injected fixture exit status
        interrupted) expected=143 ;; # 128 + SIGTERM (15)
        timed-out) expected=124 ;; # timeout deadline exceeded
        *) expected=0 ;; # command succeeded
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
for name in ResolvedTaggedAudioTest ResolvedEpisodeServerTest PassageOracleTest; do
    grep -Fq "de.test.antennapod.playback.hazards.support.$name" "$ADB_CALLS"
done
export MODE=install-error
echo 0 > "$COUNTER"
status=0
bash .github/workflows/installPlaybackHazards.sh debug "$folder/install-error" > "$folder/install-error.txt" || status=$?
test "$status" = 9
test "$(cat "$COUNTER")" = 0
test ! -e "$folder/install-error/repetition-1/runner.txt"
for MODE in readiness-delayed readiness-never install-retry install-retry-long-failure install-exhausted \
        install-both-transient install-signature install-malformed install-near-match install-diagnostic-timeout fail-first; do
    export MODE
    echo 0 > "$COUNTER"
    : > "$ADB_CALLS"
    status=0
    started=$SECONDS
    bash .github/workflows/installPlaybackHazards.sh debug "$folder/setup-$MODE" > "$folder/setup-$MODE.txt" || status=$?
    setup="$folder/setup-$MODE/repetition-1/setup"
    test "$(wc -l < "$folder/setup-$MODE.txt")" -le 200
    case "$MODE" in
        readiness-delayed|install-retry) test "$status" = 0; test "$(cat "$COUNTER")" = 1 ;;
        readiness-never) test "$status" = 124; test "$(cat "$COUNTER")" = 0; test $((SECONDS - started)) -lt 40 ;;
        fail-first|install-retry-long-failure) test "$status" = 1; test "$(cat "$COUNTER")" = 1 ;;
        *) test "$status" = 1; test "$(cat "$COUNTER")" = 0 ;;
    esac
    case "$MODE" in
        readiness-delayed) grep -q 'not ready' "$setup/app-1-readiness.txt"; grep -q '^package:' "$setup/app-1-readiness.txt" ;;
        readiness-never) test ! -e "$setup/app-1-install.txt"; test -s "$setup/app-1-logcat.txt" ;;
        install-retry|install-retry-long-failure|install-exhausted|install-both-transient)
            test "$(cat "$setup/app-1-install-status.txt")" = 1
            grep -q 'Broken pipe (32)' "$setup/app-1-install.txt"
            test -s "$setup/app-1-logcat.txt"
            grep -Fxq 'install -r app/build/outputs/apk/play/debug/app-play-debug.apk' "$ADB_CALLS"
            if [ "$MODE" != install-exhausted ]; then
                test "$(cat "$setup/app-2-install-status.txt")" = 0
                test ! -e "$setup/tests-2-install-status.txt"
                if [ "$MODE" = install-both-transient ]; then
                    test "$(cat "$setup/tests-1-install-status.txt")" = 1
                else
                    test "$(cat "$setup/tests-1-install-status.txt")" = 0
                fi
                test "$(grep -c '^install ' "$ADB_CALLS")" = 3
                test "$(grep -c '^shell cmd package path android$' "$ADB_CALLS")" = 3
            else
                test "$(cat "$setup/app-2-install-status.txt")" = 1
                test "$(grep -c '^install ' "$ADB_CALLS")" = 2
            fi ;;
        install-signature|install-malformed|install-near-match|install-diagnostic-timeout) test "$(grep -c '^install ' "$ADB_CALLS")" = 1 ;;
        fail-first) test "$(grep -c '^install ' "$ADB_CALLS")" = 2 ;;
    esac
    if [ "$MODE" = install-retry-long-failure ]; then test "$(wc -l < "$setup/app-1-install.txt")" = 501; fi
    if [ "$MODE" = install-diagnostic-timeout ]; then
        test "$(cat "$setup/app-1-logcat-status.txt")" = 124
        test $((SECONDS - started)) -lt 20
    fi
done
echo 'Hazard runner controls passed'
