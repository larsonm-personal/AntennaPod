import unittest

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


if __name__ == "__main__":
    unittest.main()
