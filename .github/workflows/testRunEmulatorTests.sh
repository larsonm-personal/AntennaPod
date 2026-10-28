#!/bin/bash
set -euo pipefail

runner="$(realpath "$(dirname "${BASH_SOURCE[0]}")/runEmulatorTests.sh")"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
mkdir -p "$temporary/bin"
cat > "$temporary/bin/adb" <<'ADB'
#!/bin/bash
if [ "${ADB_MODE:-normal}" = missing ]; then
    echo 'stub: device unavailable' >&2
    exit 42
fi
if [ "${ADB_MODE:-normal}" = empty ]; then
    exit 0
fi
if [ "$2" = -b ]; then
    for line in {1..400}; do
        echo "I ResolvedUrlPlaybackTest: boundary=95 A-before=33 B-after=14 marker-${line}"
    done
    echo "E TestRunner: java.lang.NoSuchMethodError: missing playback API"
fi
ADB
cat > "$temporary/gradlew" <<'GRADLE'
#!/bin/bash
attempt=0
[ ! -f count ] || read -r attempt < count
attempt=$((attempt + 1))
echo "$attempt" > count
read -r -a statuses <<< "$TEST_STATUSES"
mkdir -p app/build/reports/androidTests
printf 'report from attempt %s\n' "$attempt" > app/build/reports/androidTests/index.html
printf '%s\n' "$*" >> arguments.txt
sleep 0.1
echo 'GRADLE: output retained without filtering'
exit "${statuses[$((attempt - 1))]}"
GRADLE
chmod +x "$temporary/bin/adb" "$temporary/gradlew"

runCase() {
    local name="$1" statuses="$2" expected="$3" mode="$4" build="$5"
    local directory="$temporary/$name"
    mkdir -p "$directory"
    cp "$temporary/gradlew" "$directory/gradlew"
    set +e
    PATH="$temporary/bin:$PATH" TEST_STATUSES="$statuses" ADB_MODE="$mode" \
        python3 - "$runner" "$directory" "$build" > "$directory/output.txt" 2>&1 <<'PYTHON'
import subprocess
import sys
sys.exit(subprocess.run(['bash', sys.argv[1], sys.argv[3], '30'], cwd=sys.argv[2]).returncode)
PYTHON
    local actual=$?
    set -e
    test "$actual" -eq "$expected"
    test "$(grep -vc '^GRADLE:' "$directory/output.txt")" -le 200
    grep -q "artifact test-report-api-30-${build}, ci-emulator/attempt-1/" "$directory/output.txt"
    grep -q 'GitHub sign-in required' "$directory/output.txt"
    test -f "$directory/app/build/ci-emulator/attempt-1/gradle.txt"
    grep -q 'report from attempt 1' "$directory/app/build/ci-emulator/attempt-1/reports/index.html"
}

runCase failed_then_passed '7 0' 0 normal debug
grep -qx 7 "$temporary/failed_then_passed/app/build/ci-emulator/attempt-1/status.txt"
grep -qx 0 "$temporary/failed_then_passed/app/build/ci-emulator/attempt-2/status.txt"
grep -q 'report from attempt 2' "$temporary/failed_then_passed/app/build/ci-emulator/attempt-2/reports/index.html"
test "$(wc -l < "$temporary/failed_then_passed/app/build/ci-emulator/attempt-1/logcat.txt")" -eq 401
grep -q '  I ResolvedUrlPlaybackTest:.*marker-400' "$temporary/failed_then_passed/output.txt"
grep -q '  E TestRunner: java.lang.NoSuchMethodError' "$temporary/failed_then_passed/output.txt"
! grep -q '  I ResolvedUrlPlaybackTest:.*marker-340$' "$temporary/failed_then_passed/output.txt"
grep -q 'connectedPlayDebugAndroidTest connectedDebugAndroidTest' "$temporary/failed_then_passed/arguments.txt"

runCase all_failed '7 9 11' 11 normal release
test "$(cat "$temporary/all_failed/count")" -eq 3
test "$(grep -c '^  ' "$temporary/all_failed/output.txt")" -eq 180
grep -q 'connectedPlayReleaseAndroidTest' "$temporary/all_failed/arguments.txt"

runCase missing_adb '4 5 6' 6 missing debug
grep -q 'Logcat unavailable' "$temporary/missing_adb/output.txt"
grep -q 'device unavailable' "$temporary/missing_adb/app/build/ci-emulator/attempt-1/logcat-error.txt"
runCase missing_adb_success '0' 0 missing debug
runCase empty_adb '1 2 3' 3 empty debug
grep -q 'Logcat unavailable' "$temporary/empty_adb/output.txt"
runCase immediate_success '0' 0 normal debug
test "$(cat "$temporary/immediate_success/count")" -eq 1
printf 'PASS: bounded excerpts, per-attempt evidence, retry success, missing adb, debug/release commands and exit statuses\n'
