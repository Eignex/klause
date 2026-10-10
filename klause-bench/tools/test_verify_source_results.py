import unittest
from unittest import mock

import z3
import verify_source_results as proof_check


class ProofCheckTest(unittest.TestCase):
    source = '(declare-const x Real) (assert (>= x (/ 1 3))) (minimize x)'
    mps_source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x obj 0.1 row 1\nRHS\n rhs row 1 obj 0.2\nBOUNDS\n FR b x\nENDATA\n'

    def case(self, suite='smtlib-core', arm='fast-exact', **updates):
        record = dict(problem='fixture', gitSha='fixture', kind='optimize', sourceHashes={'model': 'fixture'}, feasible=True,
                      proven=True, objective='1/3', maximize=False,
                      finalWitness='((define-fun x () Real (/ 1.0 3.0)))')
        record.update(updates)
        return dict(index=0, status='DONE', arm=arm, problem={'suite': suite}, record=record)

    def check(self, case, source=None):
        with mock.patch.object(proof_check, 'source', return_value=source or self.source):
            return proof_check.check([case])[0]['check']

    def test_exact_rational_witness_and_optimum(self):
        result = self.check(self.case())
        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_exact_objective_overrides_rounded_numeric_objective(self):
        for value in ('9007199254740993', '9223372036854775809', '1/3'):
            for maximize in (False, True):
                direction = 'maximize' if maximize else 'minimize'
                bound = '<=' if maximize else '>='
                source = f'(declare-const x Real) (assert ({bound} x {value if "/" not in value else "(/ 1 3)"})) ({direction} x)'
                witness = value if '/' not in value else '(/ 1.0 3.0)'
                case = self.case(objective=1.0, exactObjective=value, maximize=maximize,
                                 finalWitness=f'((define-fun x () Real {witness}))')
                self.assertIn('optimalityCheck', self.check(case, source))

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

    def test_distinct_mps_vectors_cannot_be_combined(self):
        prefix = 'NAME fixture\nROWS\n N obj\n E r1\n E r2\nCOLUMNS\n x r1 1\n y r2 1\n'
        for section in ('RHS\n a r1 1\n b r2 2\n',
                        'RANGES\n a r1 1\n b r2 2\n',
                        'BOUNDS\n LO a x 1\n UP b y 2\n'):
            with self.subTest(section=section.splitlines()[0]):
                with self.assertRaises(ValueError):
                    proof_check.mps(prefix + section + 'ENDATA\n')

    def test_unlabelled_mps_vector_continuation_keeps_the_selected_vector(self):
        text = 'NAME fixture\nROWS\n N obj\n E r1\n E r2\nCOLUMNS\n x r1 1\n y r2 1\nRHS\n a r1 1\n r2 2\nENDATA\n'
        constraints, _, variables, _ = proof_check.mps(text)
        solver = z3.Solver()
        solver.add(*constraints, variables['x'] == 1, variables['y'] == 2)
        self.assertEqual(solver.check(), z3.sat)

    def test_mps_tolerance_acceptance_is_separate_from_exact_feasibility(self):
        case = self.case(suite='mps-core', arm='default', proven=False,
                         finalWitness='v x=1.00000001', exactObjective='-0.099999999')
        result = self.check(case, self.mps_source)
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        self.assertNotIn('optimalityCheck', result)

    def test_mps_row_tolerance_rejects_excess_residuals(self):
        for value in ('0.99999989', '1.00000011'):
            with self.subTest(value=value), self.assertRaisesRegex(AssertionError, 'source tolerance'):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x={value}')
                self.check(case, self.mps_source)

    def test_mps_ranged_rows_scale_each_side_independently(self):
        for kind, span, rhs in (('E', '10', '0'), ('E', '-10', '10'), ('L', '10', '10'), ('G', '-10', '0')):
            source = f'NAME fixture\nROWS\n N obj\n {kind} row\nCOLUMNS\n x row 1\nRHS\n rhs row {rhs}\nRANGES\n r row {span}\nBOUNDS\n FR b x\nENDATA\n'
            for value, accepted in (('-0.0000001', True), ('-0.00000011', False),
                                    ('10.000001', True), ('10.0000011', False)):
                with self.subTest(kind=kind, span=span, value=value):
                    case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}')
                    if accepted:
                        self.assertTrue(self.check(case, source)['toleranceAccepted'])
                    else:
                        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                            self.check(case, source)

    def test_mps_column_bounds_use_bound_scale(self):
        for bound in ('LO', 'UP', 'FX'):
            source = f'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0\nBOUNDS\n {bound} b x 1\nENDATA\n'
            values = ('0.9999999', '0.99999989') if bound == 'LO' else ('1.0000001', '1.00000011')
            for value, accepted in zip(values, (True, False)):
                with self.subTest(bound=bound, value=value):
                    case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}')
                    if accepted:
                        self.assertTrue(self.check(case, source)['toleranceAccepted'])
                    else:
                        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                            self.check(case, source)

    def test_mps_integrality_is_exact_under_tolerance(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n m1 \'MARKER\' \'INTORG\'\n x obj 0\n m2 \'MARKER\' \'INTEND\'\nENDATA\n'
        for value in ('0.99999999', '1.00000001', '2'):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                self.check(self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}'), source)

    def test_mps_invalid_coordinates_cannot_receive_tolerance_credit(self):
        for witness in ('', 'v', 'v y=1', 'v x=1 x=1', 'v x', 'v x=', 'v x=1=2',
                        'v x=NaN', 'v x=Infinity', 'v x=-inf', 'v x=1/0'):
            with self.subTest(witness=witness), self.assertRaises((AssertionError, ValueError, ZeroDivisionError)):
                self.check(self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=witness), self.mps_source)

    def test_mps_objectives_are_checked_even_for_inexact_points(self):
        for objective in ('0', '-0.09999999', 'NaN', 'Infinity'):
            with self.subTest(objective=objective), self.assertRaises((AssertionError, ValueError)):
                self.check(self.case(suite='mps-core', arm='default', proven=False,
                                     finalWitness='v x=1.00000001', exactObjective=objective), self.mps_source)

    def test_mps_objective_uses_original_decimal_coefficients(self):
        for maximize in (False, True):
            source = self.mps_source.replace('ROWS', 'OBJSENSE\n MAX\nROWS') if maximize else self.mps_source
            result = self.check(self.case(suite='mps-core', arm='default', proven=False,
                                         finalWitness='v x=1', exactObjective='-0.1', maximize=maximize), source)
            self.assertEqual(result['objectiveCheck'], 'source point objective matches')

    def test_mps_legacy_objective_accepts_only_binary64_rounding(self):
        case = self.case(suite='mps-core', arm='default', proven=False,
                         finalWitness='v x=1.00000001', objective=-0.099999999)
        self.assertTrue(self.check(case, self.mps_source)['toleranceAccepted'])
        case['record']['objective'] = -0.09999999
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, self.mps_source)

    def test_mps_float_objective_can_use_encoded_binary64_coordinates(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0.1\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, finalWitness='v x=0.1',
                         exactObjective='3602879701896397/360287970189639680')
        self.assertEqual(self.check(case, source)['objectiveCheck'], 'source point objective matches')
        case['record']['exactObjective'] = '3602879701896398/360287970189639680'
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, source)

    def test_mps_large_objective_cannot_overflow_into_equality(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 1e308\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, finalWitness='v x=1e308',
                         exactObjective='1e616')
        self.assertEqual(self.check(case, source)['objectiveCheck'], 'source point objective matches')
        case['record']['exactObjective'] = '2e616'
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, source)

    def test_mps_nonfinite_binary64_activity_is_checked_with_exact_decimals(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1e308\n y row -1e308\nBOUNDS\n FR b x\n FR b y\nENDATA\n'
        for value, accepted in (('1.00000001e308', True), ('1.000001e308', False)):
            with self.subTest(value=value):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x=1e308 y={value}')
                if accepted:
                    result = self.check(case, source)
                    self.assertTrue(result['toleranceAccepted'])
                    self.assertEqual(result['witness'], 'not exact source feasible')
                else:
                    with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                        self.check(case, source)

    def test_mps_exact_mode_does_not_accept_tolerance_points(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(suite='mps-core', proven=False, objective=None,
                                 finalWitness='v x=1.00000001'), self.mps_source)

    def test_mps_tolerance_point_cannot_prove_a_rounded_optimum(self):
        source = self.mps_source.replace(' E row', ' G row')
        case = self.case(suite='mps-core', arm='default', finalWitness='v x=0.99999999',
                         exactObjective='-0.100000001')
        with self.assertRaises(AssertionError):
            self.check(case, source)

    def test_mps_exact_optimum_can_be_checked_independently_of_rounded_point(self):
        source = self.mps_source.replace(' x obj 0.1 row 1', ' x row 1\n y obj 1').replace('rhs row 1 obj 0.2', 'rhs row 1')
        result = self.check(self.case(suite='mps-core', arm='default', finalWitness='v x=1.00000001 y=0',
                                      exactObjective='0'), source)
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertIn('optimalityCheck', result)

    def test_mps_infeasibility_is_checked_against_exact_source(self):
        source = self.mps_source.replace(' FR b x', ' FX b x 0.99999999')
        result = self.check(self.case(suite='mps-core', arm='default', feasible=False, objective=None,
                                     finalWitness=None), source)
        self.assertEqual(result['verdictCheck'], 'source unsat')
        self.assertNotIn('toleranceAccepted', result)

    def test_mps_rounded_egout_flow_coordinates_are_tolerance_accepted(self):
        source = 'NAME egout-flow\nROWS\n N COST\n E 031\n E 033\nCOLUMNS\n F....031 031 -1\n F.030031 COST 0.012 031 -1\n F.031032 COST 0.004 031 1\n F.029031 COST 0.012 031 -1\n F....033 033 -1\n F.032033 COST 0.002 033 -1\n F.033037 COST 0.004 033 1\nBOUNDS\n FX BOUNDS F....031 0.28\n FX BOUNDS F....033 0.19\nENDATA\n'
        witness = 'v F....031=0.28 F.030031=0.28 F.031032=7.140000000000001 F.029031=6.58 F....033=0.19 F.032033=89.85 F.033037=90.03999999999999'
        case = self.case(suite='mps-core', arm='default', proven=False, objective=0.65074,
                         finalWitness=witness)
        result = self.check(case, source)
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        for before, after in (('7.140000000000001', '7.14001'), ('90.03999999999999', '90.0399')):
            with self.subTest(value=after), self.assertRaisesRegex(AssertionError, 'source tolerance'):
                case['record'].update(finalWitness=witness.replace(before, after), objective=None)
                self.check(case, source)

    def test_mps_indicator_rows_are_checked_only_when_active(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1\nBOUNDS\n BV b active\nINDICATORS\n IF row active 1\nENDATA\n'
        for active, value, accepted in ((0, '1', True), (1, '0.0000001', True), (1, '0.00000011', False)):
            with self.subTest(active=active, value=value):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v active={active} x={value}')
                if accepted:
                    self.assertTrue(self.check(case, source)['toleranceAccepted'])
                else:
                    with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                        self.check(case, source)

    def test_mps_duplicate_coefficients_do_not_inflate_tolerance_scale(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1e308\n x row -1e308\n y row 1\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                         finalWitness='v x=1 y=0.00000011')
        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
            self.check(case, source)

    def test_mps_exact_integer_objective_retains_values_beyond_binary64(self):
        for value in ('9007199254740993', '9223372036854775809'):
            for maximize in (False, True):
                with self.subTest(value=value, maximize=maximize):
                    sense = 'OBJSENSE\n MAX\n' if maximize else ''
                    source = f'NAME fixture\n{sense}ROWS\n N obj\nCOLUMNS\n x obj 1\nBOUNDS\n LI b x {value}\n UI b x {value}\nENDATA\n'
                    case = self.case(suite='mps-core', arm='default', finalWitness=f'v x={value}',
                                     exactObjective=value, objective=1, maximize=maximize)
                    result = self.check(case, source)
                    self.assertEqual(result['witness'], 'exact source feasible')
                    self.assertIn('optimalityCheck', result)
                    case['record']['exactObjective'] = str(int(value) + 1)
                    with self.assertRaisesRegex(AssertionError, 'objective'):
                        self.check(case, source)

    def test_mps_column_tolerance_uses_absolute_large_bound(self):
        for bound, accepted, rejected in (('10', '10.000001', '10.0000011'),
                                         ('-10', '-10.000001', '-10.0000011')):
            with self.subTest(bound=bound):
                source = f'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0\nBOUNDS\n FX b x {bound}\nENDATA\n'
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x={accepted}')
                self.assertTrue(self.check(case, source)['toleranceAccepted'])
                case['record']['finalWitness'] = f'v x={rejected}'
                with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                    self.check(case, source)


if __name__ == '__main__':
    unittest.main()
