import hashlib
import importlib.util
import json
import pathlib
import unittest


CHECKER = pathlib.Path(__file__).parents[1] / "src/main/resources/source-evidence/check_source_evidence.py"
SPEC = importlib.util.spec_from_file_location("source_evidence_checker", CHECKER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SourceEvidenceCheckerTest(unittest.TestCase):
    def setUp(self):
        self.source = {"semantics": "klause-ir-exact-v1", "kind": "real-box-square",
                       "lower": -2, "upper": 3, "coefficient": 1, "constant": 0, "sense": "MINIMIZE"}
        self.raw = json.dumps(self.source).encode()
        self.payload = {"sourceHash": hashlib.sha256(self.raw).hexdigest(), "sense": "MINIMIZE",
                        "result": "Optimal", "capture": "Checked", "rule": "original-quadratic-box",
                        "strict": False, "witness": "0", "value": "0", "bound": "0"}

    def test_original_bound_and_attainment_are_checked(self):
        self.assertEqual("checked", MODULE.inspect(self.raw, self.payload)["status"])

    def test_false_bounds_witnesses_values_and_identity_are_rejected(self):
        for key, value in (("bound", "1"), ("witness", "4"), ("value", "1"),
                           ("strict", True), ("sourceHash", "foreign"), ("sense", "MAXIMIZE")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                MODULE.inspect(self.raw, self.payload | {key: value})

    def test_a_feasible_nonoptimal_point_cannot_establish_attainment(self):
        with self.assertRaises(ValueError):
            MODULE.inspect(self.raw, self.payload | {"witness": "1", "value": "1", "bound": "1"})

    def test_incomplete_or_uncaptured_claims_stay_unavailable(self):
        for change in ({"result": "BestFound"}, {"capture": "Unavailable"}):
            with self.subTest(change=change):
                self.assertEqual("unavailable", MODULE.inspect(self.raw, self.payload | change)["status"])

    def test_additional_source_constraints_cannot_be_ignored(self):
        raw = json.dumps(self.source | {"constraints": ["x > 0"]}).encode()
        with self.assertRaises(ValueError):
            MODULE.inspect(raw, self.payload | {"sourceHash": hashlib.sha256(raw).hexdigest()})

    def test_maximum_and_shifted_scaled_objective_use_original_units(self):
        raw = json.dumps(self.source | {"sense": "MAXIMIZE", "coefficient": 2, "constant": 7}).encode()
        payload = self.payload | {"sourceHash": hashlib.sha256(raw).hexdigest(), "sense": "MAXIMIZE",
                                  "witness": "3", "value": "25", "bound": "25"}
        self.assertEqual("checked", MODULE.inspect(raw, payload)["status"])


if __name__ == "__main__":
    unittest.main()
