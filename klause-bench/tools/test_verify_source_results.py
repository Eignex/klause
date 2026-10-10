import unittest
from unittest import mock

import z3
import verify_source_results as proof_check


class ProofCheckTest(unittest.TestCase):
    source = '(declare-const x Real) (assert (>= x (/ 1 3))) (minimize x)'

    def case(self, **updates):
        record = dict(problem='fixture', gitSha='fixture', kind='optimize', sourceHashes={'model': 'fixture'}, feasible=True,
                      proven=True, objective='1/3', maximize=False,
                      finalWitness='((define-fun x () Real (/ 1.0 3.0)))')
        record.update(updates)
        return dict(index=0, status='DONE', arm='fast-exact', problem={'suite': 'smtlib-core'}, record=record)

    def check(self, case, source=None):
        with mock.patch.object(proof_check, 'source', return_value=source or self.source):
            return proof_check.check([case])[0]['check']

    def test_exact_rational_witness_and_optimum(self):
        result = self.check(self.case())
        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_feasible_suboptimal_point_does_not_certify_optimum(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(objective=1, finalWitness='((define-fun x () Real 1.0))'))

    def test_infeasible_source_does_not_certify_sat(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(proven=False), self.source + ' (assert (< x 0))')

    def test_feasible_source_does_not_certify_unsat(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(feasible=False, objective=None, finalWitness=None))

    def test_infeasible_printed_model_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Real 0.0))'))

    def test_incomplete_printed_model_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='()'))

    def test_model_sort_must_match_source(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Int 1))'))

    def test_model_value_cannot_reference_an_unconstrained_symbol(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Real y))'))

    def test_integer_model_cannot_contain_a_fraction(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Int (/ 1 2)))'),
                       '(declare-const x Int) (assert (>= x 0))')

    def test_maximum_is_checked_in_source_orientation(self):
        case = self.case(maximize=True, objective=2,
                         finalWitness='((define-fun x () Real 2.0))')
        result = self.check(case, '(declare-const x Real) (assert (<= x 2)) (maximize x)')
        self.assertIn('optimalityCheck', result)

    def test_unknown_has_no_proof_claim(self):
        result = self.check(self.case(feasible=None, proven=False, objective=None, finalWitness=None))
        self.assertEqual(result['verdictCheck'], 'no claimed verdict')

    def test_proven_satisfaction_requires_no_objective(self):
        result = self.check(self.case(kind='satisfy', objective=None),
                            '(declare-const x Real) (assert (>= x 0))')
        self.assertEqual(result['verdictCheck'], 'source sat')
        self.assertNotIn('optimalityCheck', result)

    def test_incomplete_case_is_rejected(self):
        case = self.case()
        case['status'] = 'RUNNING'
        with self.assertRaises(AssertionError):
            self.check(case)

    def test_source_hash_must_match(self):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'(check-sat)'
        case = self.case()
        case['record']['gitSha'] = 'fixture'
        with mock.patch.object(proof_check.urllib.request, 'urlopen', return_value=response):
            with self.assertRaises(AssertionError):
                proof_check.source(case, [])

    def test_integer_mps_default_bound_is_binary(self):
        text = "NAME fixture\nROWS\n N obj\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1\n M2 'MARKER' 'INTEND'\nENDATA\n"
        constraints, _, variables, _ = proof_check.mps(text)
        solver = z3.Solver()
        solver.add(*constraints, variables['x'] == 2)
        self.assertEqual(solver.check(), z3.unsat)

    def test_mps_ranges_define_exact_feasible_intervals(self):
        for kind, span, lo, hi in (('E', '2', 5, 7), ('E', '-2', 3, 5),
                                  ('L', '2', 3, 5), ('G', '-2', 5, 7)):
            with self.subTest(kind=kind, span=span):
                text = f'NAME fixture\nROWS\n N obj\n {kind} row\nCOLUMNS\n x row 1\nRHS\n rhs row 5\nRANGES\n ranges row {span}\nENDATA\n'
                constraints, _, variables, _ = proof_check.mps(text)
                for value, expected in ((lo, z3.sat), (hi, z3.sat),
                                        (lo - 1, z3.unsat), (hi + 1, z3.unsat)):
                    solver = z3.Solver()
                    solver.add(*constraints, variables['x'] == value)
                    self.assertEqual(solver.check(), expected)

    def test_mps_objective_offset_and_maximum_orientation(self):
        text = 'NAME fixture\nOBJSENSE\n MAX\nROWS\n N obj\nCOLUMNS\n x obj 3\nRHS\n rhs obj 2\nENDATA\n'
        _, objective, variables, maximize = proof_check.mps(text)
        self.assertTrue(maximize)
        self.assertEqual(str(z3.simplify(z3.substitute(objective, (variables['x'], z3.RealVal(4))))), '-10')

    def test_unsupported_mps_sections_cannot_be_ignored(self):
        with self.assertRaises(ValueError):
            proof_check.mps('NAME fixture\nQCMATRIX row\nENDATA\n')

    def test_inline_objective_orientation_cannot_be_ignored(self):
        with self.assertRaises(ValueError):
            proof_check.mps('NAME fixture\nOBJSENSE MAX\nENDATA\n')

    def test_model_objective_must_match_reported_incumbent(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(proven=False, objective=2))


if __name__ == '__main__':
    unittest.main()
