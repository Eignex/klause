import copy
import hashlib
import unittest

from verify_mps_linear_proof import verify


SOURCE = b"""ROWS
 N COST
COLUMNS
 X COST 1.0000000000000000001
BOUNDS
 LO B X 0.1
ENDATA
"""


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
