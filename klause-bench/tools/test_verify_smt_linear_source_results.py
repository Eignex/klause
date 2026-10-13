import copy
import hashlib
import unittest
from pathlib import Path

from verify_smt_linear_proof import verify

ROOT = Path(__file__).resolve().parents[1] / "smoke-corpus/linear-authority"
MIN_SOURCE = (ROOT / "strict-min.smt2").read_bytes()
MAX_SOURCE = (ROOT / "strict-max.smt2").read_bytes()
BOOL_SOURCE = (ROOT / "boolean-limit.smt2").read_bytes()
INT_SOURCE = (ROOT / "integer-strict.smt2").read_bytes()
CONDITION_SOURCE = (ROOT / "conditioned-affine.smt2").read_bytes()
CONDITION = (ROOT / "conditioned-affine.condition.json").read_bytes()
MIN_RECORD = {
    "sourceSHA256": hashlib.sha256(MIN_SOURCE).hexdigest(), "maximize": False,
    "kind": "limit", "columns": {"x": "Real/0"}, "point": {"Real/0": "1"},
    "incumbent": {"Real/0": "1/2"}, "value": "1/2", "endpoint": {"Real/0": "0"},
    "objective": {"Real/0": "1"}, "constant": "0", "bound": "0", "ray": None,
    "rows": {"positive": {"coefficients": {"Real/0": "-1"}, "rhs": "0", "strict": True}},
    "proof": ["row", "positive"], "globalBound": "0", "globalProof": ["row", "positive"],
}
MAX_RECORD = copy.deepcopy(MIN_RECORD)
MAX_RECORD.update(sourceSHA256=hashlib.sha256(MAX_SOURCE).hexdigest(), maximize=True, bound="1",
                  point={"Real/0": "0"}, endpoint={"Real/0": "1"}, globalBound="1")
MAX_RECORD["rows"]["positive"].update(coefficients={"Real/0": "1"}, rhs="1")
BOOL_RECORD = copy.deepcopy(MIN_RECORD)
BOOL_RECORD.update(sourceSHA256=hashlib.sha256(BOOL_SOURCE).hexdigest(),
                   columns={"x": "Real/0", "b": "Boolean/0"})
for field in ("point", "incumbent", "endpoint"):
    BOOL_RECORD[field]["Boolean/0"] = False
BOOL_RECORD["proof"] = ["bool", 0, ["row", "positive"], ["row", "positive"]]
BOOL_RECORD["globalProof"] = copy.deepcopy(BOOL_RECORD["proof"])


class SmtLinearProofTest(unittest.TestCase):
    def test_conditioned_bound_uses_the_callers_exact_pins(self):
        record = self.conditioned_record()
        self.assertTrue(verify(CONDITION_SOURCE, record, CONDITION)["checked"])

    def test_conditioned_proof_cannot_be_reused_without_its_condition(self):
        with self.assertRaises(AssertionError):
            verify(CONDITION_SOURCE, self.conditioned_record())

    def test_relabeling_a_conditional_bound_cannot_supply_a_parent_bound(self):
        record = self.conditioned_record()
        parent = b'{}'
        record["conditionSHA256"] = hashlib.sha256(parent).hexdigest()
        with self.assertRaises(KeyError):
            verify(CONDITION_SOURCE, record, parent)

    def test_conditioned_witness_cannot_round_its_rational_pin(self):
        record = self.conditioned_record()
        record["incumbent"]["Real/0"] = "0.3333333333333333"
        with self.assertRaises(AssertionError):
            verify(CONDITION_SOURCE, record, CONDITION)

    def test_conditioned_witness_cannot_change_its_boolean_pin(self):
        record = self.conditioned_record()
        record["point"]["Boolean/0"] = False
        with self.assertRaises(AssertionError):
            verify(CONDITION_SOURCE, record, CONDITION)

    @staticmethod
    def conditioned_record():
        point = {"Boolean/0": True, "Integer/0": "18446744073709551616", "Real/0": "1/3"}
        bound = "55340232221128654849/3"
        proof = ["sum", [["1", ["assume", "Integer/0", False]],
                         ["1", ["assume", "Real/0", False]]]]
        return {
            "sourceSHA256": hashlib.sha256(CONDITION_SOURCE).hexdigest(),
            "conditionSHA256": hashlib.sha256(CONDITION).hexdigest(), "maximize": False,
            "kind": "optimal", "columns": {"b": "Boolean/0", "i": "Integer/0", "x": "Real/0"},
            "point": point.copy(), "incumbent": point.copy(), "value": bound, "endpoint": None,
            "objective": {"Integer/0": "1", "Real/0": "1"}, "constant": "0", "bound": bound,
            "rows": {}, "proof": proof, "ray": None, "globalBound": bound, "globalProof": proof,
        }

    def test_strict_limits_check_original_direction_and_separate_incumbent(self):
        for source, record in ((MIN_SOURCE, MIN_RECORD), (MAX_SOURCE, MAX_RECORD), (BOOL_SOURCE, BOOL_RECORD)):
            with self.subTest(maximize=record["maximize"], columns=record["columns"]):
                self.assertTrue(verify(source, record)["checked"])

    def test_weak_bound_cannot_establish_nonattainment(self):
        record = copy.deepcopy(MIN_RECORD)
        record["rows"]["positive"]["strict"] = False
        with self.assertRaises(AssertionError):
            verify(MIN_SOURCE, record)

    def test_approach_cannot_change_boolean_coordinates(self):
        record = copy.deepcopy(BOOL_RECORD)
        record["endpoint"]["Boolean/0"] = True
        with self.assertRaises(AssertionError):
            verify(BOOL_SOURCE, record)

    def test_missing_boolean_sibling_cannot_supply_coverage(self):
        record = copy.deepcopy(BOOL_RECORD)
        record["proof"].pop()
        with self.assertRaises(ValueError):
            verify(BOOL_SOURCE, record)

    def test_branch_only_premise_cannot_supply_a_global_bound(self):
        record = copy.deepcopy(BOOL_RECORD)
        record["rows"]["positive"]["guards"] = {"Boolean/0": False}
        record["proof"] = ["row", "positive"]
        with self.assertRaises(AssertionError):
            verify(BOOL_SOURCE, record)

    def test_guarded_boolean_declaration_premise_requires_the_matching_pin(self):
        record = copy.deepcopy(BOOL_RECORD)
        record["rows"]["false"] = {"coefficients": {"Boolean/0": "1"}, "rhs": "0", "strict": False,
                                     "guards": {"Boolean/0": False}}
        record["proof"][2] = ["sum", [["1", ["row", "positive"]], ["1", ["row", "false"]]]]
        self.assertTrue(verify(BOOL_SOURCE, record)["checked"])
        record["proof"][3] = ["row", "false"]
        with self.assertRaises(AssertionError):
            verify(BOOL_SOURCE, record)

    def test_negative_multiplier_cannot_supply_a_bound(self):
        record = copy.deepcopy(MIN_RECORD)
        record["proof"] = ["sum", [["-1", ["row", "positive"]]]]
        with self.assertRaises(AssertionError):
            verify(MIN_SOURCE, record)

    def test_original_integer_strictness_supports_an_attained_integer_bound(self):
        record = copy.deepcopy(MIN_RECORD)
        record.update(sourceSHA256=hashlib.sha256(INT_SOURCE).hexdigest(), kind="optimal",
                      columns={"x": "Integer/0"}, objective={"Integer/0": "1"},
                      point={"Integer/0": "1"}, incumbent={"Integer/0": "1"}, value="1", endpoint=None,
                      bound="1", globalBound="1")
        record["rows"]["positive"].update(coefficients={"Integer/0": "-1"}, rhs="-1", strict=False)
        self.assertTrue(verify(INT_SOURCE, record)["checked"])

    def test_integer_closure_point_cannot_supply_a_real_approach(self):
        record = copy.deepcopy(MIN_RECORD)
        record.update(sourceSHA256=hashlib.sha256(INT_SOURCE).hexdigest(),
                      columns={"x": "Integer/0"}, objective={"Integer/0": "1"},
                      point={"Integer/0": "1"}, incumbent={"Integer/0": "1"}, value="1",
                      endpoint={"Integer/0": "0"})
        record["rows"]["positive"]["coefficients"] = {"Integer/0": "-1"}
        with self.assertRaises(AssertionError):
            verify(INT_SOURCE, record)

    def test_changed_source_bytes_withhold_the_claim(self):
        with self.assertRaises(AssertionError):
            verify(MIN_SOURCE + b"\n", MIN_RECORD)

    def test_incorrect_incumbent_value_withholds_the_claim(self):
        record = copy.deepcopy(MIN_RECORD)
        record["value"] = "0"
        with self.assertRaises(AssertionError):
            verify(MIN_SOURCE, record)
