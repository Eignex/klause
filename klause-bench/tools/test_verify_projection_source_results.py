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
        self.corners = {"inertWitnesses": [witness], "unsupported": "refused", "uncheckable": "Unsupported",
                        "realSource": {"lower": "1/2", "upper": "5/2", "coefficient": "1", "bound": "3/2"},
                        "realControls": [{"mode": mode, "exactReal": "3/2", "optimization": [
                            {"maximize": False, "exactReal": "3/2", "value": 1.5},
                            {"maximize": True, "exactReal": "5/2", "value": 2.5}]} for mode in ("default", "omitted")]}

        self.mapped = {
            "source": {"bounds": [{"lower": None, "upper": None}, {"lower": None, "upper": None},
                                   {"lower": "0", "upper": "2"}],
                       "tableColumns": [2], "tableTuples": ["1", "2"]},
            "cpSourceIds": [2], "rootValues": ["0", "1", "2"],
            "witnesses": [["0", "0", "1"], ["0", "0", "2"]],
            "scores": [{"value": "0", "score": 9}, {"value": "1", "score": 0}, {"value": "2", "score": 0}],
            "optimization": [{"maximize": False, "value": "1", "objective": 1},
                             {"maximize": True, "value": "2", "objective": 2}],
            "relaxationEmissions": 2,
        }

    def test_original_coordinate_projection_is_checked(self):
        self.assertEqual([2], MODULE.verify_mapped(self.mapped)["cpSourceIds"])

    def test_wrong_original_coordinate_is_refused(self):
        self.mapped["cpSourceIds"] = [0]
        with self.assertRaisesRegex(ValueError, "original source coordinates"):
            MODULE.verify_mapped(self.mapped)

    def test_missing_required_mapped_control_is_refused(self):
        self.source["sourceMappedRequired"] = True
        with self.assertRaisesRegex(ValueError, "missing required mapped"):
            MODULE.verify_corners(self.source, self.corners)

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

    def test_omitted_real_rows_still_require_original_source_feasibility(self):
        self.corners["realControls"][1]["exactReal"] = "1/2"
        with self.assertRaisesRegex(ValueError, "original source"):
            MODULE.verify_corners(self.source, self.corners)

    def test_rational_original_objective_directions_are_checked(self):
        result = MODULE.verify_corners(self.source, self.corners)
        self.assertEqual("3/2", result["realMinimum"])
        self.assertEqual("5/2", result["realMaximum"])

    def test_uncheckable_source_cannot_claim_complete_satisfaction(self):
        self.corners["uncheckable"] = "Sat"
        with self.assertRaisesRegex(ValueError, "false complete claim"):
            MODULE.verify_corners(self.source, self.corners)


if __name__ == "__main__":
    unittest.main()
