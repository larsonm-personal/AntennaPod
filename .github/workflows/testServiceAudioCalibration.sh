#!/bin/bash
set -eu
folder=$(mktemp -d)
trap 'rm -rf "$folder"' EXIT
mkdir -p "$folder/bin"
cat > "$folder/bin/adb" <<'STUB'
#!/bin/bash
if [ "$1" = logcat ]; then
    echo 'Synthetic logcat evidence'
    exit 0
fi
number=$(cat "$COUNTER")
number=$((number + 1))
printf '%s' "$number" > "$COUNTER"
if [ "$MODE" = fail-first ] && [ "$number" = 1 ]; then
    echo 'FAILURES!!!'
    echo 'Tests run: 15, Failures: 1'
elif [ "$MODE" = command-error ]; then
    echo "Synthetic transport failure" >&2
    exit 7
elif [ "$MODE" = long-failure ]; then
    for line in $(seq 1 500); do echo "Synthetic failure line $line"; done
elif [ "$MODE" = missing ]; then
    echo 'INSTRUMENTATION_FAILED: unavailable'
else
    echo 'OK (15 tests)'
    echo 'INSTRUMENTATION_CODE: -1'
fi
STUB
chmod +x "$folder/bin/adb"
export PATH="$folder/bin:$PATH" COUNTER="$folder/count"
for MODE in pass fail-first missing command-error long-failure; do
    export MODE
    echo 0 > "$COUNTER"
    status=0
    bash .github/workflows/runServiceAudioCalibration.sh debug "$folder/$MODE" > "$folder/$MODE.txt" || status=$?
    test "$(cat "$COUNTER")" = 3
    test "$(wc -l < "$folder/$MODE.txt")" -le 200
    if [ "$MODE" = pass ]; then test "$status" = 0; else test "$status" = 1; fi
    for repetition in 1 2 3; do
        test -s "$folder/$MODE/repetition-$repetition/runner.txt"
        test -f "$folder/$MODE/repetition-$repetition/command-status.txt"
        if [ "$MODE" = command-error ]; then
            test "$(cat "$folder/$MODE/repetition-$repetition/command-status.txt")" = 7
        fi
    done
done
echo 'Calibration runner controls passed'
