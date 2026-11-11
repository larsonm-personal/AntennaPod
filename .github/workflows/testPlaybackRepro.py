from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import runPlaybackRepro as repro


class ReporterTest(unittest.TestCase):
    def results(self):
        cases = {(repro.PACKAGE + ".ShortRewindTest", name): (0, "") for name in repro.CASES}
        for name, count in repro.CONTROL_COUNTS.items():
            cases.update({(repro.PACKAGE + ".support." + name, f"control{index}"): (0, "")
                          for index in range(count)})
        return cases

    def output(self, results):
        output = []
        for (cls, name), (code, stack) in results.items():
            output += [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={name}",
                       f"INSTRUMENTATION_STATUS: stack={stack}", f"INSTRUMENTATION_STATUS_CODE: {code}"]
        return "\n".join(output + ["INSTRUMENTATION_CODE: -1", ""])

    def test_completed_success_is_not_a_reproduced_hazard(self):
        _, verdict, failed = repro.describe(repro.parse_results(self.output(self.results())))
        self.assertFalse(failed)
        self.assertIn("not reproduced", verdict)

    def test_content_failure_with_healthy_controls_identifies_the_hazard(self):
        results = self.results()
        results[(repro.PACKAGE + ".ShortRewindTest", next(iter(repro.CASES)))] = (-2, repro.MISSING + " replay: A -> B")
        _, verdict, failed = repro.describe(repro.parse_results(self.output(results)))
        self.assertTrue(failed)
        self.assertTrue(verdict.startswith("Hazard reproduced:"))

    def test_setup_failure_does_not_identify_the_hazard(self):
        results = self.results()
        results[(repro.PACKAGE + ".ShortRewindTest", next(iter(repro.CASES)))] = (-2, "Timed out preparing player")
        _, verdict, failed = repro.describe(results)
        self.assertTrue(failed)
        self.assertTrue(verdict.startswith("Inconclusive:"))

    def test_failed_or_skipped_control_invalidates_the_demonstration(self):
        for status in (-1, -2, -3):
            with self.subTest(status=status):
                results = self.results()
                results[(repro.PACKAGE + ".ShortRewindTest", next(iter(repro.CASES)))] = (-2, repro.MISSING)
                results[(repro.PACKAGE + ".support.PassageOracleTest", "control0")] = (status, "Control failed")
                _, verdict, failed = repro.describe(results)
                self.assertTrue(failed)
                self.assertTrue(verdict.startswith("Inconclusive:"))

    def test_unfinished_instrumentation_is_rejected(self):
        with self.assertRaises(ValueError):
            repro.parse_results(self.output(self.results()).replace("INSTRUMENTATION_CODE: -1", ""))

    def test_missing_test_is_rejected(self):
        results = self.results()
        results.popitem()
        with self.assertRaises(ValueError):
            repro.parse_results(self.output(results))

    def test_duplicate_test_is_rejected(self):
        output = self.output(self.results())
        with self.assertRaises(ValueError):
            repro.parse_results(output + output)

    def run_script(self, results, statuses, capture_status=None):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            calls = []

            def adb(arguments, path, timeout=60):
                calls.append(arguments)
                if arguments[0] == "shell" and arguments[1] == "am" and arguments[2] == "instrument":
                    saved = (folder / "summary.md").read_text(encoding="utf-8")
                    self.assertIn("results pending", saved)
                    if len([call for call in calls if "instrument" in call]) > 1:
                        self.assertIn("| Audio observer and HTTP fixture controls |", saved)
                    path.write_text(self.output(results), encoding="utf-8")
                    return statuses.pop(0)
                path.write_text("Success", encoding="utf-8")
                return 0

            capture = Mock()
            capture.poll.return_value = capture_status
            capture.wait.return_value = capture_status if capture_status is not None else -15
            with patch.object(repro, "run_adb", side_effect=adb), \
                    patch.object(repro.subprocess, "Popen", return_value=capture), \
                    patch.object(repro.sys, "argv", ["repro", "--output", directory]), \
                    patch.object(repro.sys, "stdout"), patch.object(repro.sys, "stderr"), \
                    patch.dict(repro.os.environ, {"GITHUB_ACTIONS": "false", "GITHUB_STEP_SUMMARY": ""}):
                status = repro.main()
            return status, (folder / "summary.md").read_text(encoding="utf-8"), calls

    def test_capture_failure_is_reported_even_with_complete_test_results(self):
        status, summary, _ = self.run_script(self.results(), [0, 0, 0], capture_status=1)
        self.assertEqual(1, status)
        self.assertIn("Capture error", summary)
        self.assertIn("diagnostics are incomplete", summary)

    def test_timeout_stops_instrumentation_and_further_repetitions(self):
        status, summary, calls = self.run_script(self.results(), [124])
        self.assertEqual(1, status)
        self.assertEqual(1, len([call for call in calls if "instrument" in call]))
        self.assertIn(["shell", "am", "force-stop", "de.danoeh.antennapod.debug"], calls)
        self.assertIn("Further repetitions stopped", summary)

    def test_hazards_continue_and_each_result_is_saved_before_the_next_run(self):
        results = self.results()
        results[(repro.PACKAGE + ".ShortRewindTest", next(iter(repro.CASES)))] = (-2, repro.MISSING)
        status, summary, calls = self.run_script(results, [0, 0, 0])
        self.assertEqual(1, status)
        self.assertEqual(3, len([call for call in calls if "instrument" in call]))
        self.assertEqual(3, summary.count("Hazard reproduced:"))
        self.assertNotIn("results pending", summary)


if __name__ == "__main__":
    unittest.main()
