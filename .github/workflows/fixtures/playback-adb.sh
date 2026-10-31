#!/bin/bash
printf '%s\n' "$*" >> "$ADB_CALLS"
if [ "$*" = 'shell cmd package path android' ]; then
    if [ "$MODE" = readiness-never ] || { [ "$MODE" = readiness-delayed ] \
            && [ "$(grep -c '^shell cmd package path android$' "$ADB_CALLS")" -lt 3 ]; }; then
        echo 'cmd: package service not ready' >&2
        exit 1
    fi
    echo 'package:/system/framework/framework-res.apk'
    exit 0
fi
if [ "$1" = install ]; then
    [ "$MODE" != install-error ] || exit 9
    case "$MODE" in
        install-retry|install-retry-long-failure|install-exhausted|install-both-transient)
            if [ "$MODE" = install-exhausted ] || [ "$(grep -c '^install ' "$ADB_CALLS")" = 1 ] \
                    || { [ "$MODE" = install-both-transient ] && [ "$2" != -r ]; }; then
                if [ "$MODE" = install-retry-long-failure ]; then
                    for line in $(seq 1 500); do echo "Synthetic install failure $line"; done
                fi
                echo "adb: failed to install ${!#}: cmd: Failure calling service package: Broken pipe (32)" >&2
                exit 1
            fi ;;
        install-near-match)
            echo "adb: failed to install ${!#}: cmd: Failure calling service package: Broken pipe (32) with extra text"
            exit 1 ;;
        install-signature|install-malformed|install-diagnostic-timeout)
            echo "Failure [INSTALL_FAILED_$MODE]" >&2
            exit 1 ;;
    esac
    echo Success
    exit 0
fi
if [ "$1" = logcat ]; then
    if [[ " $* " == *" -c "* ]]; then rm -f "$LOG_READY"; exit 0; fi
    if [ "$MODE" = install-diagnostic-timeout ]; then sleep 20; fi
    if [ "$MODE" = long-failure ] || [ "$MODE" = install-retry-long-failure ]; then
        for line in $(seq 1 500); do echo "ServiceAudioObserver observer=controller synthetic=$line"; done
    else
        echo 'Synthetic logcat evidence'
        echo 'ServiceAudioObserver expiry responses=1'
    fi
    touch "$LOG_READY"
    exit 0
fi
for _ in $(seq 1 500); do
    [ -e "$LOG_READY" ] && break
    sleep 0.01
done
test -e "$LOG_READY" || exit 8
number=$(cat "$COUNTER")
number=$((number + 1))
printf '%s' "$number" > "$COUNTER"
case "$MODE" in
    fail-first)
        if [ "$number" = 1 ]; then
            echo 'FAILURES!!!'
            echo 'Tests run: 32, Failures: 1'
            exit 0
        fi ;;
    command-error) echo 'Synthetic transport failure' >&2; exit 7 ;;
    interrupted) exit 143 ;;
    timed-out) exit 124 ;;
    empty) exit 0 ;;
    missing) echo 'INSTRUMENTATION_FAILED: unavailable'; exit 0 ;;
    missing-code) echo 'OK (32 tests)'; exit 0 ;;
    wrong-count) echo 'OK (31 tests)'; echo 'INSTRUMENTATION_CODE: -1'; exit 0 ;;
    long-failure|install-retry-long-failure)
        for line in $(seq 1 500); do echo "Synthetic failure line $line"; done
        exit 0 ;;
esac
echo "OK (${EXPECTED_TESTS:-32} tests)"
echo 'INSTRUMENTATION_CODE: -1'
