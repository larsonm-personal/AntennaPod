#!/bin/bash
set -eu
folder=$(mktemp -d)
trap 'rm -rf "$folder"' EXIT
mkdir -p "$folder/bin"
cat > "$folder/bin/adb" <<'STUB'
#!/bin/bash
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
for attempt in $(seq 1 500); do
    [ -e "$LOG_READY" ] && break
    sleep 0.01
done
test -e "$LOG_READY" || exit 8
number=$(cat "$COUNTER")
number=$((number + 1))
printf '%s' "$number" > "$COUNTER"
if [ "$MODE" = fail-first ] && [ "$number" = 1 ]; then
    echo 'FAILURES!!!'
    echo 'Tests run: 19, Failures: 1'
elif [ "$MODE" = command-error ]; then
    echo "Synthetic transport failure" >&2
    exit 7
elif [ "$MODE" = long-failure ]; then
    for line in $(seq 1 500); do echo "Synthetic failure line $line"; done
elif [ "$MODE" = missing ]; then
    echo 'INSTRUMENTATION_FAILED: unavailable'
else
    echo "OK (${EXPECTED_TESTS:-29} tests)"
    echo 'INSTRUMENTATION_CODE: -1'
fi
STUB
chmod +x "$folder/bin/adb"
export PATH="$folder/bin:$PATH" COUNTER="$folder/count" LOG_READY="$folder/log-ready"
for MODE in pass fail-first missing command-error long-failure; do
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
    for repetition in 1; do
        test -s "$folder/$MODE/repetition-$repetition/runner.txt"
        test -f "$folder/$MODE/repetition-$repetition/command-status.txt"
        if [ "$MODE" = command-error ]; then
            test "$(cat "$folder/$MODE/repetition-$repetition/command-status.txt")" = 7
        fi
    done
done
export MODE=pass EXPECTED_TESTS=3
bash .github/workflows/runPlaybackHazards.sh debug "$folder/group" 1 mutation > "$folder/group.txt"
grep -q 'all 3 tests' "$folder/group.txt"
export MODE=fail-first EXPECTED_TESTS=29
echo 0 > "$COUNTER"
if bash .github/workflows/runPlaybackHazards.sh debug "$folder/retained" 1 > "$folder/first.txt"; then exit 1; fi
export MODE=pass
bash .github/workflows/runPlaybackHazards.sh debug "$folder/retained" 2 > "$folder/second.txt"
grep -q 'FAILURES!!!' "$folder/retained/repetition-1/runner.txt"
grep -q 'OK (29 tests)' "$folder/retained/repetition-2/runner.txt"
echo 'Hazard runner controls passed'
