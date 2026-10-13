import copy
import hashlib
import unittest
from pathlib import Path

from verify_mps_linear_proof import verify


SOURCE = b"""ROWS
 N COST
COLUMNS
 X COST 1.0000000000000000001
BOUNDS
 LO B X 0.1
ENDATA
"""

GAP_SOURCE = (Path(__file__).resolve().parents[1] / "smoke-corpus/linear-authority/integer-gap.mps").read_bytes()
GAP_RECORD = {
    "sourceSHA256": hashlib.sha256(GAP_SOURCE).hexdigest(), "maximize": False,
    "kind": "optimal", "columns": {"I": "Integer/0"}, "point": {"Integer/0": "1"},
    "objective": {"Integer/0": "1"}, "constant": "0",
    "rows": {"zero": {"coefficients": {"Integer/0": "-1"}, "rhs": "0"},
             "gap": {"coefficients": {"Integer/0": "-2"}, "rhs": "-1"}},
    "bound": "1", "ray": None,
    "proof": ["int", "Integer/0", "0",
              ["sum", [["1", ["row", "gap"]], ["2", ["assume", "Integer/0", True]]]],
              ["assume", "Integer/0", False]],
}


def capture():
    return {
        "sourceSHA256": hashlib.sha256(SOURCE).hexdigest(), "maximize": False,
        "kind": "optimal", "columns": {"X": "Real/0"},
        "point": {"Real/0": "1/10", "Real/1": "10000000000000000001/100000000000000000000"},
        "objective": {"Real/1": "1"}, "constant": "0",
        "rows": {
            "lower": {"coefficients": {"Real/0": "-10"}, "rhs": "-1"},
            "cost": {"coefficients": {"Real/0": "10000000000000000001", "Real/1": "-10000000000000000000"}, "rhs": "0"},
        },
        "bound": "10000000000000000001/100000000000000000000",
        "proof": ["sum", [["10000000000000000001/100000000000000000000", ["row", "lower"]],
                            ["1/10000000000000000000", ["row", "cost"]]]],
        "ray": None,
    }


class MpsLinearProofTest(unittest.TestCase):
    def test_ray_base_and_better_incumbent_are_checked_separately(self):
        source = (Path(__file__).resolve().parents[1] / "smoke-corpus/linear-authority/recession.mps").read_bytes()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "ray", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "0", "Real/0": "0"},
                  "incumbent": {"Integer/0": "2", "Real/0": "1/5"}, "value": "-2",
                  "objective": {"Integer/0": "-1"}, "constant": "0", "rows": {},
                  "ray": {"Integer/0": "10", "Real/0": "1"}, "bound": None, "proof": "source-ray"}
        self.assertTrue(verify(source, record)["checked"])
        record["value"] = "0"
        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_constant_source_contradiction_can_supply_a_refutation(self):
        source = b"ROWS\n N COST\n L R\nRHS\n RHS R -1\nENDATA\n"
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "refutation", "columns": {}, "point": None, "objective": {}, "constant": "0",
                  "rows": {"impossible": {"coefficients": {}, "rhs": "-1"}},
                  "bound": None, "ray": None, "proof": ["row", "impossible"]}
        self.assertTrue(verify(source, record)["checked"])

    def test_integer_optimum_requires_both_scoped_alternatives(self):
        self.assertTrue(verify(GAP_SOURCE, GAP_RECORD)["checked"])

    def test_missing_sibling_cannot_establish_integer_coverage(self):
        record = copy.deepcopy(GAP_RECORD)
        record["proof"] = ["assume", "Integer/0", False]
        with self.assertRaises(KeyError):
            verify(GAP_SOURCE, record)

    def test_partial_without_witness_retains_an_independent_global_bound(self):
        record = copy.deepcopy(GAP_RECORD)
        record.update(kind="partial", point=None, bound=None, proof=None,
                      globalBound="1/2", globalProof=["sum", [["1/2", ["row", "gap"]]]])
        checked = verify(GAP_SOURCE, record)
        self.assertTrue(checked["checked"])
        self.assertEqual("1/2", checked["globalBound"])

    def test_branch_only_bound_cannot_fill_the_global_field(self):
        record = copy.deepcopy(GAP_RECORD)
        record.update(kind="partial", point=None, bound=None, proof=None,
                      globalBound="1", globalProof=["assume", "Integer/0", False])
        with self.assertRaises(KeyError):
            verify(GAP_SOURCE, record)

    def test_exact_decimal_auxiliary_equality_proves_original_objective(self):
        self.assertTrue(verify(SOURCE, capture())["checked"])

    def test_different_original_bytes_reject_capture(self):
        with self.assertRaises(AssertionError):
            verify(SOURCE.replace(b"0.1", b"0.2"), capture())

    def test_valid_point_does_not_establish_an_unproved_bound(self):
        record = capture()
        record["proof"][1][0][0] = "0"
        with self.assertRaises(AssertionError):
            verify(SOURCE, record)

    def test_negative_dual_weight_rejects(self):
        record = capture()
        record["proof"][1][0][0] = "-1"
        with self.assertRaises(AssertionError):
            verify(SOURCE, record)

    def test_foreign_row_cannot_supply_source_authority(self):
        record = capture()
        record["rows"]["lower"]["rhs"] = "-2"
        with self.assertRaises(AssertionError):
            verify(SOURCE, record)

    def test_fractional_step_cannot_move_an_integer_source_column(self):
        source = b"""ROWS
 N COST
COLUMNS
 M 'MARKER' 'INTORG'
 I COST -1
 M 'MARKER' 'INTEND'
BOUNDS
 LI B I 0
ENDATA
"""
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "ray", "columns": {"I": "Integer/0"}, "point": {"Integer/0": "0"},
                  "objective": {"Integer/0": "-1"}, "constant": "0", "rows": {},
                  "ray": {"Integer/0": "1"}, "bound": None, "proof": "source-ray"}
        self.assertTrue(verify(source, record)["checked"])
        bad = copy.deepcopy(record)
        bad["ray"]["Integer/0"] = "1/2"
        with self.assertRaises(AssertionError):
            verify(source, bad)


if __name__ == "__main__":
    unittest.main()
