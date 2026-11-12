import argparse
import html
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGE = "de.test.antennapod.playback.hazards"
CASES = {
    "changedResolutionRetainsPassageAfterRewind": "Partial A → fresh B → restart → rewind",
    "originalEditionRetainsPassageAfterRewind": "Partial A → same A → restart → rewind",
    "replacementEditionRetainsPassageAfterRewind": "Coherent B → rewind",
}
CLASSES = ["ShortRewindTest", "support.ResolvedTaggedAudioTest", "support.PassageOracleTest",
           "support.ResolvedEpisodeServerTest"]
CONTROL_COUNTS = {"ResolvedTaggedAudioTest": 5, "PassageOracleTest": 1, "ResolvedEpisodeServerTest": 3}
MISSING = "java.lang.AssertionError: Program passage 75 missing after three 10-second rewinds."


def parse_results(output):
    results, bundle = {}, {}
    key = None
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, value = line[len("INSTRUMENTATION_STATUS: "):].split("=", 1)
            bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code != 1 and "test" in bundle and "class" in bundle:
                name = (bundle["class"], bundle["test"])
                if name in results:
                    raise ValueError(f"Duplicate test result: {name}")
                results[name] = (code, bundle.get("stack", ""))
            bundle, key = {}, None
        elif key == "stack":
            bundle[key] += "\n" + line
    expected = sum(CONTROL_COUNTS.values()) + len(CASES)
    if len(results) != expected or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.MULTILINE):
        raise ValueError(f"Incomplete run: expected {expected} finished tests, found {len(results)}")
    for method in CASES:
        if (PACKAGE + ".ShortRewindTest", method) not in results:
            raise ValueError(f"Missing playback case: {method}")
    for name, count in CONTROL_COUNTS.items():
        if sum(cls == PACKAGE + ".support." + name for cls, _ in results) != count:
            raise ValueError(f"Missing observer/server controls: {name}")
    return results


def describe(results):
    rows, failed = [], False
    for method, description in CASES.items():
        code, stack = results[(PACKAGE + ".ShortRewindTest", method)]
        failed |= code != 0
        if code == 0:
            outcome = "PASS — passage heard"
        elif code == -2 and stack.startswith(MISSING):
            outcome = "FAIL — passage missing"
        else:
            outcome = "ERROR — reproduction did not complete"
        rows.append((description, outcome, stack.splitlines()[0] if stack else ""))
    controls = [(name, code, stack) for (cls, name), (code, stack) in results.items()
                if cls != PACKAGE + ".ShortRewindTest"]
    failures = [f"{name}: {stack.splitlines()[0] if stack else code}" for name, code, stack in controls if code != 0]
    failed |= bool(failures)
    rows.append(("Audio observer and HTTP fixture controls",
                 f"{len(controls) - len(failures)}/{len(controls)} passed", "; ".join(failures)))
    hazard = results[(PACKAGE + ".ShortRewindTest", next(iter(CASES)))]
    healthy = all(results[(PACKAGE + ".ShortRewindTest", name)][0] == 0 for name in list(CASES)[1:])
    reproduced = hazard[0] == -2 and hazard[1].startswith(MISSING) and healthy and not failures
    verdict = "Hazard reproduced: passage missing only after changing the stream." if reproduced else (
        "All cases passed: the hazard was not reproduced." if not failed
        else "Inconclusive: a control, setup, or unexpected playback failure needs investigation.")
    return rows, verdict, failed


def run_adb(arguments, path, timeout=60):
    with path.open("w", encoding="utf-8") as log:
        try:
            return subprocess.run(["adb", *arguments], stdout=log, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=False).returncode
        except subprocess.TimeoutExpired:
            log.write(f"\nCommand timed out after {timeout} seconds\n")
            return 124


def save_summary(summary, folder):
    report = "\n".join(summary)
    (folder / "summary.md").write_text(report, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        Path(os.environ["GITHUB_STEP_SUMMARY"]).write_text(report, encoding="utf-8")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Replay a changed ad layout through AntennaPod's service and cache")
    parser.add_argument("--output", type=Path, default=Path("app/build/playback-repro"))
    parser.add_argument("--repetitions", type=int, choices=range(1, 4), default=3)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    summary = ["## Stream changes after partial caching", "",
               "A and B contain the same program; B's inserted ad is about 60 seconds shorter.", "",
               "Expected: program seconds 75–77 remain audible after three 10-second rewinds.",
               "The test accepts either coherent edition and measures consumed PCM.",
               "A missing passage is an assertion failure, so the demonstration check remains red.", ""]
    failed = False
    try:
        for name, apk in [("app", "app/build/outputs/apk/play/debug/app-play-debug.apk"),
                          ("tests", "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk")]:
            if run_adb(["install", "-r", apk], args.output / f"install-{name}.txt", 120):
                raise RuntimeError(f"Could not install {name}; see install-{name}.txt")
        for repetition in range(1, args.repetitions + 1):
            print(f"Run {repetition}/{args.repetitions}", flush=True)
            folder = args.output / f"run-{repetition}"
            folder.mkdir(exist_ok=True)
            summary += [f"### Run {repetition}", ""]
            save_summary(summary + ["Instrumentation running; results pending.", ""], args.output)
            if run_adb(["logcat", "-c"], folder / "logcat-clear.txt"):
                raise RuntimeError("Could not clear logcat")
            with (folder / "logcat.txt").open("w", encoding="utf-8") as log:
                capture = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=log,
                                           stderr=subprocess.STDOUT)
                try:
                    status = run_adb(["shell", "am", "instrument", "-w", "-r", "-e", "class",
                                      ",".join(PACKAGE + "." + cls for cls in CLASSES),
                                      "de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner"],
                                     folder / "instrumentation.txt", 900)
                finally:
                    capture_ended_early = capture.poll() is not None
                    capture.terminate()
                    try:
                        capture_status = capture.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        capture.kill()
                        capture.wait(timeout=5)
                        raise
                    (folder / "logcat-exit-code.txt").write_text(str(capture_status), encoding="utf-8")
            (folder / "exit-code.txt").write_text(str(status), encoding="utf-8")
            summary += ["| Scenario | Result |", "|---|---|"]
            try:
                if status:
                    raise ValueError(f"adb exited with status {status}")
                results = parse_results((folder / "instrumentation.txt").read_text(encoding="utf-8"))
                rows, verdict, run_failed = describe(results)
                failed |= run_failed
                for scenario, outcome, detail in rows:
                    print(f"{scenario}: {outcome}", flush=True)
                    summary.append(f"| {scenario} | {outcome} |")
                    if detail:
                        print(detail, flush=True)
                summary += ["", verdict, ""]
                for scenario, _, detail in rows:
                    if detail:
                        summary += [f"**{scenario}**", "", html.escape(detail), ""]
                print(verdict, flush=True)
                if run_failed and os.environ.get("GITHUB_ACTIONS") == "true":
                    print(f"::error title=Playback reproduction run {repetition}::{verdict}", flush=True)
            except ValueError as error:
                failed = True
                summary += [f"| Runner | ERROR: {html.escape(str(error))} |", ""]
                print(f"Run {repetition}: {error}", flush=True)
            if capture_ended_early:
                failed = True
                error = f"Log capture ended early (status {capture_status}); device diagnostics are incomplete."
                summary += ["", f"**Capture error:** {error}", ""]
                print(error, file=sys.stderr)
            if status == 124:
                run_adb(["shell", "am", "force-stop", "de.danoeh.antennapod.debug"], folder / "stop-after-timeout.txt")
                summary += ["Further repetitions stopped after the instrumentation timeout.", ""]
            save_summary(summary, args.output)
            if status == 124:
                break
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        failed = True
        summary += ["", f"Setup/capture error: {html.escape(str(error))}", ""]
        print(str(error), file=sys.stderr)
    finally:
        summary += ["Available instrumentation and device logs are attached as `playback-repro`.",
                    "These synthetic episodes demonstrate cache/stream mixing, not the exact phone incident.", ""]
        save_summary(summary, args.output)
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
