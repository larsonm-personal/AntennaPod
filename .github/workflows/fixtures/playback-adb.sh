#!/bin/bash
printf '%s\n' "$*" >> "$ADB_CALLS"
if [ "$1" = install ]; then
    [ "$MODE" != install-error ] || exit 9
    echo Success
    exit 0
fi
if [ "$1" = logcat ]; then
    if [[ " $* " == *" -c "* ]]; then rm -f "$LOG_READY"; exit 0; fi
    if [ "$MODE" = long-failure ]; then
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
    long-failure)
        for line in $(seq 1 500); do echo "Synthetic failure line $line"; done
        exit 0 ;;
esac
echo "OK (${EXPECTED_TESTS:-32} tests)"
echo 'INSTRUMENTATION_CODE: -1'
