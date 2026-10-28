"""Pure harness checks. No PostgreSQL, credentials or subprocesses are used."""
import importlib.util
from pathlib import Path
from subprocess import CompletedProcess
import unittest


spec = importlib.util.spec_from_file_location("reservation_race_harness", Path(__file__).with_name("run.py"))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


def result(state=None, *, returncode=None, stderr=None):
    code = (0 if state is None else 1) if returncode is None else returncode
    return CompletedProcess([], code, stdout="synthetic-id\n" if state is None else "",
                            stderr=stderr if stderr is not None else "" if state is None else f"ERROR:  {state}: synthetic failure\n")


def wave(*deadlocked):
    results = [result("40P01" if index in deadlocked else "23P01") for index in range(50)]
    results[25] = result()
    return results


class ReservationRaceHarnessTest(unittest.TestCase):
    def test_only_exclusion_conflicts_need_no_rechecks(self):
        results = wave()
        def unexpected(_):
            self.fail("An exclusion conflict must not be retried")
        reconciled = harness.reconcile_reservation_race(results, unexpected)
        self.assertEqual(reconciled, results)
        self.assertEqual(sum(item.returncode == 0 for item in reconciled), 1)

    def test_entire_wave_finishes_before_only_deadlocks_are_rechecked_in_order(self):
        events = []
        results = wave(2, 49)
        def completed_wave():
            for index, item in enumerate(results):
                events.append(("completed", index))
                yield item
        def recheck(index):
            self.assertEqual(sum(kind == "completed" for kind, _ in events), 50)
            events.append(("recheck", index))
            return result("23P01")
        reconciled = harness.reconcile_reservation_race(completed_wave(), recheck)
        self.assertEqual([index for kind, index in events if kind == "recheck"], [2, 49])
        self.assertEqual(sum(item.returncode == 0 for item in reconciled), 1)
        self.assertTrue(all(item.returncode == 0 or harness.reservation_error_state(item) == "23P01" for item in reconciled))
        self.assertEqual(harness.reservation_error_state(results[2]), "40P01")

    def test_missing_or_multiple_commits_block_before_any_recheck(self):
        for winners in (0, 2):
            with self.subTest(winners=winners):
                results = wave(2)
                results[25] = result("23P01")
                for index in range(winners):
                    results[index + 30] = result()
                self.assertRaises(AssertionError, harness.reconcile_reservation_race, results,
                                  lambda _: self.fail("Invalid first wave must not retry"))

    def test_incomplete_wave_blocks_before_any_recheck(self):
        self.assertRaises(AssertionError, harness.reconcile_reservation_race, wave(2)[:-1],
                          lambda _: self.fail("Incomplete wave must not retry"))

    def test_unknown_or_ambiguous_error_anywhere_blocks_all_rechecks(self):
        invalid = [
            result("23503"),
            result("40P01", returncode=-9),
            result("40P01", returncode=2),
            result("40P01", stderr="DETAIL: 40P01 is not an ERROR SQLSTATE\n"),
            result("40P01", stderr="ERROR:  23503: unknown failure\nDETAIL: 40P01\n"),
            result("40P01", stderr="ERROR:  40P01: deadlock\nERROR:  08006: lost connection\n"),
            result("40P01", stderr="ERROR:  40P01: deadlock\nFATAL:  08006: lost connection\n"),
        ]
        for item in invalid:
            with self.subTest(stderr=item.stderr, returncode=item.returncode):
                results = wave(2)
                results[49] = item
                self.assertRaises(AssertionError, harness.reconcile_reservation_race, results,
                                  lambda _: self.fail("Validate every result before any retry"))

    def test_recheck_requires_exclusion_and_never_creates_another_winner(self):
        for replay in (result(), result("40P01"), result("23503"), result("40P01", returncode=2)):
            with self.subTest(returncode=replay.returncode, stderr=replay.stderr):
                calls = []
                def retry(index):
                    calls.append(index)
                    return replay
                self.assertRaises(AssertionError, harness.reconcile_reservation_race, wave(2, 49), retry)
                self.assertEqual(calls, [2])

    def test_timeout_during_recheck_propagates_without_further_attempt(self):
        calls = []
        def retry(index):
            calls.append(index)
            raise TimeoutError("synthetic timeout")
        self.assertRaises(TimeoutError, harness.reconcile_reservation_race, wave(2, 49), retry)
        self.assertEqual(calls, [2])

    def test_verbose_state_is_exactly_one_error_headline(self):
        self.assertEqual(harness.reservation_error_state(result("40P01", returncode=3)), "40P01")
        self.assertIsNone(harness.reservation_error_state(result("40P01", stderr="ERROR: 40P01 is missing delimiter\n")))
        self.assertIsNone(harness.reservation_error_state(result("40P01", stderr="ERROR:\n40P01: not on headline\n")))


if __name__ == "__main__":
    unittest.main()
