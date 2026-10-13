"""Corruption controls for exhaustive projection-provider source checking."""

import copy
import importlib.util
import itertools
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[1] / "src/main/resources/source-evidence/check_projection_evidence.py"
SPEC = importlib.util.spec_from_file_location("projection_checker", CHECKER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProjectionSourceResultsTest(unittest.TestCase):
    def setUp(self):
        self.source = {"schema": "finite-projection-controls-v1", "boolCount": 1, "domains": [[0, 1]],
                       "clauses": [[0]], "linear": [{"coefficients": ["1"], "op": "GE", "bound": "1"}],
                       "objective": ["1"]}
        witness = {"bools": [True], "ints": ["1"]}
        scores = [{"assignment": {"bools": [b], "ints": [str(i)]}, "accepted": b and i == 1}
                  for b, i in itertools.product((False, True), (0, 1))]
        optimization = [{"maximize": direction, "assignment": witness, "value": 1}
                        for direction in (False, True)]
        self.controls = [{"mode": mode, "witnesses": [witness], "scores": copy.deepcopy(scores),
                          "optimization": copy.deepcopy(optimization)} for mode in ("default", "alternate")]

    def test_complete_original_source_records_are_checked(self):
        result = MODULE.verify(self.source, self.controls)
        self.assertEqual(4, result["checkedAssignments"])
        self.assertEqual(1, result["feasibleAssignments"])
        self.assertFalse(result["proofFileVerification"])

    def test_zero_score_for_a_rejected_source_assignment_is_refused(self):
        self.controls[1]["scores"][0]["accepted"] = True
        with self.assertRaisesRegex(ValueError, "score acceptance"):
            MODULE.verify(self.source, self.controls)

    def test_missing_source_witness_is_refused(self):
        self.controls[1]["witnesses"] = []
        with self.assertRaisesRegex(ValueError, "solution set"):
            MODULE.verify(self.source, self.controls)

    def test_original_objective_mismatch_is_refused(self):
        self.controls[1]["optimization"][1]["value"] = -1
        with self.assertRaisesRegex(ValueError, "original-direction"):
            MODULE.verify(self.source, self.controls)

    def test_missing_provider_is_refused(self):
        with self.assertRaisesRegex(ValueError, "missing provider"):
            MODULE.verify(self.source, self.controls[:1])


if __name__ == "__main__":
    unittest.main()
