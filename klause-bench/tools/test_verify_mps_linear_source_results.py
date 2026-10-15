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

    def test_fixed_integer_guards_admit_exact_tiny_source_rows(self):
        for trigger in (0, 1):
            for relation, maximize, sign in (("G", False, "-1"), ("L", True, "1"), ("E", False, "-1")):
                with self.subTest(trigger=trigger, relation=relation):
                    sense = "OBJSENSE\n MAX\n" if maximize else ""
                    source = (sense + f"ROWS\n N COST\n {relation} R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                              " M1 'MARKER' 'INTEND'\n X COST 1 R 1e-20\nRHS\n RHS R 1e-20\n"
                              f"BOUNDS\n FX B I {trigger}\n LO B X 0\nINDICATORS\n IF R I {trigger}\nENDATA\n").encode()
                    record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": maximize,
                              "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                              "point": {"Integer/0": str(trigger), "Real/0": "1"},
                              "objective": {"Real/0": "1"}, "constant": "0",
                              "rows": {"enabled": {"coefficients": {"Real/0": sign}, "rhs": sign,
                                                   "integerPins": {"Integer/0": str(trigger)}}},
                              "bound": "1", "proof": ["row", "enabled"], "ray": None}

                    self.assertTrue(verify(source, record)["checked"])

    def test_conditional_source_authority_requires_its_original_integer_pin(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 1\n"
                  "BOUNDS\n FX B I 1\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        for pins in ({}, {"Integer/0": "0"}, {"Integer/99": "1"},
                     {"Real/0": "1"}, {"Integer/0": "1/2"}):
            with self.subTest(pins=pins):
                record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                          "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                          "point": {"Integer/0": "1", "Real/0": "1"},
                          "objective": {"Real/0": "1"}, "constant": "0",
                          "rows": {"enabled": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                               "integerPins": pins}},
                          "bound": "1", "proof": ["row", "enabled"], "ray": None}

                with self.assertRaises(AssertionError):
                    verify(source, record)

    def test_integer_alternatives_establish_each_indicator_pin_in_its_own_branch(self):
        source = ("ROWS\n N COST\n G R0\n G R1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R0 1e-20 R1 1e-20\nRHS\n RHS R0 1e-20 R1 1e-20\n"
                  "BOUNDS\n BV B I\n LO B X 0\nINDICATORS\n IF R0 I 0\n IF R1 I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "0", "Real/0": "1"},
                  "objective": {"Real/0": "1"}, "constant": "0",
                  "rows": {"zero": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                    "integerPins": {"Integer/0": "0"}},
                           "one": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                   "integerPins": {"Integer/0": "1"}}},
                  "bound": "1", "ray": None,
                  "proof": ["int", "Integer/0", "0", ["row", "zero"], ["row", "one"]]}

        self.assertTrue(verify(source, record)["checked"])

    def test_open_integer_sides_cannot_be_invented_to_establish_indicator_pins(self):
        for bound in ("LI B I 0", "FR B I"):
            with self.subTest(bound=bound):
                source = ("ROWS\n N COST\n G R0\n G R1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                          " M1 'MARKER' 'INTEND'\n X COST 1 R0 1 R1 1\nRHS\n RHS R0 1 R1 1\n"
                          f"BOUNDS\n {bound}\n LO B X 0\nINDICATORS\n IF R0 I 0\n IF R1 I 1\nENDATA\n").encode()
                record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                          "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                          "point": {"Integer/0": "0", "Real/0": "1"},
                          "objective": {"Real/0": "1"}, "constant": "0",
                          "rows": {"zero": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                            "integerPins": {"Integer/0": "0"}},
                                   "one": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                           "integerPins": {"Integer/0": "1"}}},
                          "bound": "1", "ray": None,
                          "proof": ["int", "Integer/0", "0", ["row", "zero"], ["row", "one"]]}

                with self.assertRaises(AssertionError):
                    verify(source, record)

    def test_conditional_conflicts_require_refutations_in_both_integer_branches(self):
        source = ("ROWS\n N COST\n G R0\n G R1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R0 1 R1 1\nRHS\n RHS R0 1 R1 1\n"
                  "BOUNDS\n BV B I\n FX B X 0\nINDICATORS\n IF R0 I 0\n IF R1 I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "refutation", "columns": {"I": "Integer/0", "X": "Real/0"}, "point": None,
                  "objective": {"Real/0": "1"}, "constant": "0",
                  "rows": {"zero": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                    "integerPins": {"Integer/0": "0"}},
                           "one": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                   "integerPins": {"Integer/0": "1"}},
                           "upper": {"coefficients": {"Real/0": "1"}, "rhs": "0"}},
                  "bound": None, "ray": None,
                  "proof": ["int", "Integer/0", "0",
                            ["sum", [["1", ["row", "zero"]], ["1", ["row", "upper"]]]],
                            ["sum", [["1", ["row", "one"]], ["1", ["row", "upper"]]]]]}

        self.assertTrue(verify(source, record)["checked"])
        record["proof"] = record["proof"][3]
        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_an_incumbent_pin_cannot_authorize_a_global_conditional_bound(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 1\n"
                  "BOUNDS\n BV B I\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "partial", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "1", "Real/0": "1"},
                  "incumbent": {"Integer/0": "1", "Real/0": "1"}, "value": "1",
                  "objective": {"Real/0": "1"}, "constant": "0",
                  "rows": {"enabled": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                       "integerPins": {"Integer/0": "1"}}},
                  "bound": None, "proof": None, "ray": None,
                  "globalBound": "1", "globalProof": ["row", "enabled"]}

        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_an_inactive_indicator_does_not_restrict_the_original_witness(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 1\n"
                  "BOUNDS\n FX B I 0\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "0", "Real/0": "0"},
                  "objective": {"Real/0": "1"}, "constant": "0",
                  "rows": {"lower": {"coefficients": {"Real/0": "-1"}, "rhs": "0"}},
                  "bound": "0", "proof": ["row", "lower"], "ray": None}

        self.assertTrue(verify(source, record)["checked"])

    def test_original_enabled_rows_are_checked_when_the_capture_omits_them(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 1\n"
                  "BOUNDS\n FX B I 1\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "1", "Real/0": "0"},
                  "objective": {"Real/0": "1"}, "constant": "0",
                  "rows": {"lower": {"coefficients": {"Real/0": "-1"}, "rhs": "0"}},
                  "bound": "0", "proof": ["row", "lower"], "ray": None}

        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_recession_checks_preserve_fixed_indicator_activation(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST -1 R 1\nRHS\n RHS R 2\n"
                  "BOUNDS\n LI B I 0\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        for integer, real in (("0", "0"), ("1", "2")):
            with self.subTest(integer=integer):
                record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                          "kind": "ray", "columns": {"I": "Integer/0", "X": "Real/0"},
                          "point": {"Integer/0": integer, "Real/0": real},
                          "objective": {"Real/0": "-1"}, "constant": "0", "rows": {},
                          "bound": None, "proof": "source-ray", "ray": {"Integer/0": "0", "Real/0": "1"}}

                self.assertTrue(verify(source, record)["checked"])

    def test_a_ray_cannot_change_an_integer_indicator_guard(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST -1 R 1\nRHS\n RHS R 2\n"
                  "BOUNDS\n LI B I 0\n LO B X 0\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "ray", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "0", "Real/0": "0"},
                  "objective": {"Real/0": "-1"}, "constant": "0", "rows": {},
                  "bound": None, "proof": "source-ray", "ray": {"Integer/0": "1", "Real/0": "1"}}

        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_an_enabled_indicator_row_checks_its_recession_sign(self):
        source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                  " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 2\n"
                  "BOUNDS\n LI B I 0\n FR B X\nINDICATORS\n IF R I 1\nENDATA\n").encode()
        record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                  "kind": "ray", "columns": {"I": "Integer/0", "X": "Real/0"},
                  "point": {"Integer/0": "1", "Real/0": "2"},
                  "objective": {"Real/0": "1"}, "constant": "0", "rows": {},
                  "bound": None, "proof": "source-ray", "ray": {"Integer/0": "0", "Real/0": "-1"}}

        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_rows_after_endata_cannot_supply_original_authority(self):
        source = SOURCE + b"ROWS\n G EXTRA\nCOLUMNS\n X EXTRA 1\nRHS\n RHS EXTRA 2\nENDATA\n"
        record = capture()
        record["sourceSHA256"] = hashlib.sha256(source).hexdigest()
        record["rows"]["lower"]["rhs"] = "-20"
        record["point"] = {"Real/0": "2", "Real/1": "10000000000000000001/5000000000000000000"}
        record["bound"] = record["point"]["Real/1"]

        with self.assertRaises(AssertionError):
            verify(source, record)

    def test_indicator_declarations_require_a_known_row_and_binary_integer_trigger(self):
        for declaration in ("IF R X 1", "IF MISSING I 1", "IF COST I 1", "IF R I 2", "IF R I 1\n IF R I 0"):
            with self.subTest(declaration=declaration):
                source = ("ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 0\n"
                          " M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 1\n"
                          f"BOUNDS\n FX B I 1\n LO B X 0\nINDICATORS\n {declaration}\nENDATA\n").encode()
                record = {"sourceSHA256": hashlib.sha256(source).hexdigest(), "maximize": False,
                          "kind": "optimal", "columns": {"I": "Integer/0", "X": "Real/0"},
                          "point": {"Integer/0": "1", "Real/0": "1"},
                          "objective": {"Real/0": "1"}, "constant": "0",
                          "rows": {"enabled": {"coefficients": {"Real/0": "-1"}, "rhs": "-1",
                                               "integerPins": {"Integer/0": "1"}}},
                          "bound": "1", "proof": ["row", "enabled"], "ray": None}

                with self.assertRaises(AssertionError):
                    verify(source, record)

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
