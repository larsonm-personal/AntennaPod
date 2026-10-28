#!/bin/bash

set -o pipefail

buildType="${1:-debug}"
apiLevel="${2:-unknown}"
artifact="test-report-api-${apiLevel}-${buildType}"
diagnostics=0
logcatPid=
trap '[ -z "$logcatPid" ] || kill "$logcatPid" 2>/dev/null || true' EXIT

printDiagnostic() {
    if [ "$diagnostics" -lt 200 ]; then
        printf '%s\n' "$1"
        diagnostics=$((diagnostics + 1))
    fi
}

runTests() {
    if [ "$buildType" = "release" ]; then
        ./gradlew connectedPlayReleaseAndroidTest -PtestBuildType=release \
            -Pandroid.testInstrumentationRunnerArguments.notAnnotation=de.test.antennapod.IgnoreOnCi
    else
        ./gradlew connectedPlayDebugAndroidTest connectedDebugAndroidTest -PtestBuildType=debug \
            -Pandroid.testInstrumentationRunnerArguments.notAnnotation=de.test.antennapod.IgnoreOnCi
    fi
}

printDiagnostic "Emulator tests: API ${apiLevel}, ${buildType}. Diagnostic excerpts are capped at 200 lines per job."
# Retry tests to make them less flaky
for attempt in 1 2 3; do
    folder="app/build/ci-emulator/attempt-${attempt}"
    mkdir -p "$folder"
    timeout 15s adb logcat -c > "$folder/logcat-clear.txt" 2>&1
    adb logcat -b all -v threadtime > "$folder/logcat.txt" 2> "$folder/logcat-error.txt" &
    logcatPid=$!
    runTests 2>&1 | tee "$folder/gradle.txt"
    status=${PIPESTATUS[0]}
    kill "$logcatPid" 2>/dev/null || true
    wait "$logcatPid" 2>/dev/null || true
    logcatPid=
    printf '%s\n' "$status" > "$folder/status.txt"
    if [ -d app/build/reports/androidTests ]; then
        cp -R app/build/reports/androidTests "$folder/reports" 2> "$folder/report-copy-error.txt"
    fi
    printDiagnostic "API ${apiLevel} ${buildType}, attempt ${attempt}: test exit status ${status}."
    printDiagnostic "Full logs/report: artifact ${artifact}, ci-emulator/attempt-${attempt}/ (workflow run > Artifacts; GitHub sign-in required)."
    if [ ! -s "$folder/logcat.txt" ] || [ -s "$folder/logcat-error.txt" ]; then
        printDiagnostic "Logcat unavailable or incomplete; see logcat-error.txt and gradle.txt in that attempt directory."
    fi
    if [ -s "$folder/report-copy-error.txt" ]; then
        printDiagnostic "Report copy failed; see report-copy-error.txt and the artifact's reports/androidTests directory."
    fi
    if [ "$status" -eq 0 ]; then
        printDiagnostic "Tests succeeded; earlier attempt evidence is retained."
        exit 0
    fi
    grep -E 'ResolvedUrlPlaybackTest.*(cached|boundary=|requests=)|Media3ServiceRestartTest:|DynamicAdPlaybackTest:|TestRunner.*(failed:|Error|Exception|at de.test.antennapod.playback)|AndroidRuntime.*FATAL EXCEPTION|ExoPlaybackException' \
        "$folder/logcat.txt" > "$folder/playback-diagnostics.txt"
    printDiagnostic "Playback excerpt: last 60 matching lines; the full logcat is in the artifact above."
    while IFS= read -r line; do
        printDiagnostic "  $line"
    done < <(tail -n 60 "$folder/playback-diagnostics.txt")
done
printDiagnostic "Tests FAILED; all attempt evidence is retained."
exit "$status"
