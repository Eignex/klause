import hashlib
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import z3
import smt_source_validation as worker
import verify_nra_source_results as checker


class SmtSourceClaimsTest(unittest.TestCase):
    def test_ground_exact_coordinates_and_assumptions_do_not_issue_queries(self):
        source = ('(declare-const b Bool)(declare-const x Real)(declare-const i Int)'
                  '(declare-const unused Real)(assert (= (* x x) 2))'
                  '(assert (> x 0))(assert (= i 9007199254740993))'
                  '(check-sat-assuming (b))')
        model = ('(model (define-fun b () Bool true)'
                 '(define-fun x () Real (root-obj (+ (^ x 2) (- 2)) 2))'
                 '(define-fun i () Int 9007199254740993)(define-fun unused () Real (/ 1 3)))')
        with mock.patch.multiple(z3, **{name: mock.Mock(side_effect=AssertionError('search forbidden'))
                for name in ('Solver', 'Optimize', 'Tactic', 'SolverFor', 'SimpleSolver')}):
            result = worker.evaluate(checker, source, model, worker.GROUND)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['checkedWitness'], model)
        self.assertEqual(result['solverSearchQueries'], 0)
        self.assertTrue(result['solverQueryCountObserved'])
        self.assertFalse(result['proofCertificateChecked'])

    def test_ground_check_preserves_original_abs_divmod_and_zero_power_semantics(self):
        cases = [
            ('(declare-const i Int)(assert (= (div i 3) (- 2)))'
             '(assert (= (mod i 3) 2))(assert (= (abs i) 4))',
             '(model (define-fun i () Int (- 4)))', 'valid'),
            ('(declare-const x Real)(assert (= (^ x 0) 7))',
             '(model (define-fun x () Real 0))', 'unknown'),
            ('(declare-const x Real)(assert (> x 2))',
             '(model (define-fun x () Real 1))', 'invalid'),
        ]
        for source, model, status in cases:
            with self.subTest(source=source):
                result = worker.evaluate(checker, source, model, worker.GROUND)
                self.assertEqual(result['status'], status)
                self.assertEqual(result['solverSearchQueries'], 0)

    def test_incomplete_and_sort_mismatched_models_never_gain_acceptance(self):
        source = '(declare-const i Int)(declare-const b Bool)(assert b)'
        for model in ['(model)', '(model (define-fun i () Real 1)(define-fun b () Bool true))',
                      '(model (define-fun i () Int (/ 1 2))(define-fun b () Bool true))',
                      '(model (define-fun i () Int 1)(define-fun b () Bool false))']:
            with self.subTest(model=model):
                self.assertEqual(worker.evaluate(checker, source, model, worker.GROUND)['status'], 'invalid')

    def test_unsupported_sources_do_not_fall_back_to_a_search_for_a_witness(self):
        cases = ['(declare-const x Real)(assert (= (sin x) 0))',
                 '(declare-const x Real)(minimize x)(check-sat)',
                 '(declare-const x Real)(push 1)(assert (= x 1))(check-sat)',
                 '(check-sat)(check-sat)',
                 '(declare-const x Real)(assert (= (/ x x) 1))']
        with mock.patch.object(z3, 'Solver', side_effect=AssertionError('search forbidden')):
            for source in cases:
                with self.subTest(source=source):
                    result = worker.evaluate(checker, source, '(model)', worker.GROUND)
                    self.assertEqual(result['status'], 'unknown')
                    self.assertIsNone(result['checkedWitness'])
                    self.assertEqual(result['solverSearchQueries'], 0)

    def test_refutation_uses_one_full_original_query_including_assumptions(self):
        source = '(declare-const b Bool)(assert b)(check-sat-assuming ((not b)))'
        result = worker.evaluate(checker, source, '', worker.REFUTATION)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['referenceComparison'], 'unsat')
        self.assertEqual(result['referenceLogic'], 'QF_NRA')
        self.assertEqual(result['solverSearchQueries'], 1)
        self.assertTrue(result['solverQueryCountObserved'])
        self.assertIsNone(result['checkedWitness'])
        self.assertFalse(result['proofCertificateChecked'])

    def test_reference_unknown_and_disagreement_withhold_refutation(self):
        for verdict, status in [(z3.unknown, 'unknown'), (z3.sat, 'invalid')]:
            solver = mock.Mock()
            solver.check.return_value = verdict
            solver.reason_unknown.return_value = 'bounded query'
            with self.subTest(verdict=verdict), mock.patch.object(z3, 'SolverFor', return_value=solver) as factory:
                result = worker.evaluate(checker, '(declare-const x Real)(assert (= (* x x) 2))'
                                         '(assert (= (* x x) 3))', '', worker.REFUTATION)
            self.assertEqual(result['status'], status)
            self.assertEqual(result['solverSearchQueries'], 1)
            self.assertIsNone(result['referenceComparison'])
            solver.set.assert_called_once_with(timeout=30000)
            solver.check.assert_called_once_with()
            factory.assert_called_once_with('QF_NRA')

    def test_integer_and_zero_power_sources_keep_the_general_reference_factory(self):
        sources = ['(declare-const unused Int)(assert false)',
                   '(declare-const x Real)(assert (= (^ x 0) 2))',
                   '(assert (= (div 4 3) 2))']
        for source in sources:
            solver = mock.Mock()
            solver.check.return_value = z3.unknown
            solver.reason_unknown.return_value = 'bounded query'
            with self.subTest(source=source), mock.patch.object(z3, 'Solver', return_value=solver) as general, \
                    mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('wrong fragment')):
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'unknown')
            self.assertEqual(result['referenceLogic'], 'SMT')
            self.assertEqual(result['solverSearchQueries'], 1)
            general.assert_called_once_with()
            solver.set.assert_called_once_with(timeout=30000)
            solver.check.assert_called_once_with()

    def test_signed_integer_numerals_keep_the_real_polynomial_derivation_route(self):
        constants = ['(- 2)', '(- (- (- 2)))']
        for constant in constants:
            source = '(declare-const x Real)(assert (<= x ' + constant + '))(assert (>= x (- 1)))'
            variables, constraints = checker.source_formula(source)
            with self.subTest(constant=constant), mock.patch.multiple(z3, **{
                    name: mock.Mock(side_effect=AssertionError('no query')) for name in ('Solver', 'SolverFor')}):
                self.assertEqual(worker.reference_logic(checker, variables, constraints), 'QF_NRA')
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'valid')
            self.assertEqual(result['solverSearchQueries'], 0)
            self.assertIsNotNone(result['sourceDerivation'])

    def test_real_polynomial_reference_queries_keep_every_original_assertion(self):
        source = ('(declare-const x Real)(declare-const y Real)'
                  '(assert (> (* x y) 2))(assert (< (* x y) 1))')
        solver = mock.Mock()
        solver.check.return_value = z3.unsat
        with mock.patch.object(z3, 'SolverFor', return_value=solver) as factory:
            result = worker.evaluate(checker, source, '', worker.REFUTATION)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['referenceLogic'], 'QF_NRA')
        factory.assert_called_once_with('QF_NRA')
        self.assertEqual(len(solver.add.call_args.args), 2)
        solver.check.assert_called_once_with()

    def test_strict_source_sign_contradictions_avoid_reference_queries(self):
        bodies = ['(assert (> x 0))(assert (> y 0))(assert (= (+ (* 3 x x y) (* 2 x y y)) 0))',
                  '(assert (< x 0))(assert (> y 0))(assert (= (+ (* (- 3) x x y) (* x y y)) 0))',
                  '(assert (not (<= x 0)))(assert (= (+ (* x x) x) 0))']
        for body in bodies:
            source = '(declare-const x Real)(declare-const y Real)' + body
            with self.subTest(body=body), mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'valid')
            self.assertEqual(result['solverSearchQueries'], 0)
            self.assertIsNone(result['referenceComparison'])
            self.assertIsNone(result['referenceLogic'])
            self.assertFalse(result['proofCertificateChecked'])
            self.assertEqual(result['sourceDerivation']['kind'], 'source-polynomial-enclosure-v1')
            reference = z3.SolverFor('QF_NRA')
            reference.set(timeout=1000)
            reference.add(*checker.source_formula(source)[1])
            self.assertEqual(reference.check(), z3.unsat)

    def test_square_sum_intervals_refute_original_products_without_queries(self):
        bodies = ['(assert (< (+ (* x x) (* y y)) 1))(assert (> (* x y) 1))',
                  '(assert (>= (- (- 1) (* x x) (* y y)) (- 2)))(assert (> (* x y) 1))',
                  '(assert (<= (+ (* (/ 1 3) x x) (* y y)) (/ 1 3)))(assert (> x 1))',
                  '(assert (<= (+ (/ (* x x) 3) (* y y)) (/ 1 3)))(assert (> x 1))']
        for body in bodies:
            source = '(declare-const x Real)(declare-const y Real)' + body
            with self.subTest(body=body), mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'valid')
            self.assertEqual(result['solverSearchQueries'], 0)
            self.assertIsNone(result['checkedWitness'])
            self.assertTrue(result['solverQueryCountObserved'])
            self.assertTrue(result['sourceDerivation']['bounds'])
            reference = z3.SolverFor('QF_NRA')
            reference.set(timeout=1000)
            reference.add(*checker.source_formula(source)[1])
            self.assertEqual(reference.check(), z3.unsat)

    def test_source_derived_intervals_retain_strict_original_endpoints(self):
        source = '(declare-const x Real)(assert (< (* x x) 1))(assert (>= x 1))'
        with mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
            result = worker.evaluate(checker, source, '', worker.REFUTATION)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['sourceDerivation']['method'], 'source-interval')
        self.assertEqual(result['sourceDerivation']['bounds']['x'],
                         {'lower': '1', 'lowerOpen': False, 'upper': '1', 'upperOpen': False})

    def test_source_zero_ceilings_and_disequalities_preserve_exact_zero(self):
        source = '(declare-const x Real)(assert (<= (* x x) 0))(assert (distinct x 0))'
        with mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
            result = worker.evaluate(checker, source, '', worker.REFUTATION)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['solverSearchQueries'], 0)
        self.assertEqual(result['sourceDerivation']['bounds']['x']['lower'], '0')
        self.assertEqual(result['sourceDerivation']['bounds']['x']['upper'], '0')

    def test_satisfiable_source_sign_and_interval_mutations_never_gain_derivations(self):
        bodies = ['(assert (>= x 0))(assert (= (+ (* x x) x) 0))',
                  '(assert (> x 0))(assert (> y 0))(assert (= (- x y) 0))',
                  '(assert (<= (* x x) 1))(assert (>= x 1))',
                  '(assert (<= (+ (* x x) (* y y)) 2))(assert (>= (* x y) 1))',
                  '(assert (<= (- (* x x) (* y y)) 1))(assert (> x 2))',
                  '(assert (<= (* x x y y) 1))(assert (> x 2))',
                  '(assert (<= (* x y) 1))(assert (> x 2))',
                  '(assert (= (+ (* x x) (* y y)) 0))']
        for body in bodies:
            source = '(declare-const x Real)(declare-const y Real)' + body
            variables, constraints = checker.source_formula(source)
            with self.subTest(body=body):
                self.assertIsNone(worker.source_derivation(variables, constraints))
                reference = z3.SolverFor('QF_NRA')
                reference.set(timeout=1000)
                reference.add(*constraints)
                self.assertEqual(reference.check(), z3.sat)

    def test_guarded_and_disjunctive_predicates_cannot_supply_source_bounds(self):
        guards = ['(or (> x 0) (= x 0))', '(=> b (> x 0))', '(ite b (> x 0) (= x 0))',
                  '(not (and (> x 0) (< x 0)))']
        for guard in guards:
            source = '(declare-const x Real)(declare-const b Bool)(assert (not b))' + \
                     '(assert ' + guard + ')(assert (= x 0))'
            variables, constraints = checker.source_formula(source)
            with self.subTest(guard=guard):
                self.assertIsNone(worker.source_derivation(variables, constraints))
                reference = z3.SolverFor('QF_NRA')
                reference.set(timeout=1000)
                reference.add(*constraints)
                self.assertEqual(reference.check(), z3.sat)

    def test_source_derivations_retain_original_assertion_indices_and_quoted_identity(self):
        source = ('(set-info :status sat)(declare-const |0| Real)(declare-const b Bool)'
                  '(assert b)(assert (> |0| 0))(assert (= (+ (* |0| |0|) |0|) 0))'
                  '(check-sat-assuming (b))')
        with mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
            result = worker.evaluate(checker, source, '', worker.REFUTATION)
        derivation = result['sourceDerivation']
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(derivation['assertions'], [1, 2])
        self.assertEqual(derivation['contradictionAssertion'], 2)
        self.assertEqual(set(derivation['bounds']), {'0'})
        self.assertEqual(len(derivation['expressionSha256']), 64)

    def test_unexpanded_polynomial_factors_and_higher_square_bounds_use_one_reference(self):
        sources = ['(declare-const x Real)(assert (= (* (+ x 1) (- x 1)) 0))(assert (> x 2))',
                   '(declare-const x Real)(assert (< (^ x 4) 1))(assert (> x 2))']
        for source in sources:
            solver = mock.Mock()
            solver.check.return_value = z3.unknown
            solver.reason_unknown.return_value = 'bounded query'
            with self.subTest(source=source), mock.patch.object(z3, 'SolverFor', return_value=solver) as factory:
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'unknown')
            self.assertIsNone(result['sourceDerivation'])
            self.assertEqual(result['solverSearchQueries'], 1)
            factory.assert_called_once_with('QF_NRA')
            solver.check.assert_called_once_with()

    def test_empty_source_bounds_are_an_independent_contradiction(self):
        source = '(declare-const x Real)(assert (> x 2))(assert (<= x 2))'
        with mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
            result = worker.evaluate(checker, source, '', worker.REFUTATION)
        self.assertEqual(result['status'], 'valid')
        self.assertEqual(result['sourceDerivation']['method'], 'empty-source-bounds')
        self.assertEqual(result['sourceDerivation']['assertions'], [0, 1])
        self.assertEqual(result['solverSearchQueries'], 0)

    def test_negative_source_constants_and_constant_contradictions_are_exact(self):
        sources = ['(assert false)', '(assert (not true))',
                   '(declare-const x Real)(assert (<= x (- 2)))(assert (>= x (- 1)))']
        for source in sources:
            with self.subTest(source=source), mock.patch.object(z3, 'SolverFor', side_effect=AssertionError('no query')):
                result = worker.evaluate(checker, source, '', worker.REFUTATION)
            self.assertEqual(result['status'], 'valid')
            self.assertEqual(result['solverSearchQueries'], 0)
            self.assertIsNotNone(result['sourceDerivation'])

    def test_every_checked_box_enclosure_refutes_its_complete_original_source(self):
        literals = {value: str(value) if value >= 0 else '(- ' + str(-value) + ')' for value in range(-2, 3)}
        boxes = [(-1, 1, -1, 1), (0, 0, -1, 1), (1, 2, -2, -1), (-2, -1, -2, -1)]
        polynomials = ['(* x y)', '(+ (* x x) (* y y))', '(- (* x y) x y)']
        accepted = 0
        for a, b, c, d in boxes:
            prefix = ('(declare-const x Real)(declare-const y Real)'
                      f'(assert (>= x {literals[a]}))(assert (<= x {literals[b]}))'
                      f'(assert (>= y {literals[c]}))(assert (<= y {literals[d]}))')
            for polynomial in polynomials:
                for relation in ['=', 'distinct', '<', '<=', '>', '>=']:
                    for threshold in [-1, 0, 1]:
                        source = prefix + f'(assert ({relation} {polynomial} {literals[threshold]}))'
                        variables, constraints = checker.source_formula(source)
                        derivation = worker.source_derivation(variables, constraints)
                        if derivation is None:
                            continue
                        accepted += 1
                        with self.subTest(source=source):
                            reference = z3.SolverFor('QF_NRA')
                            reference.set(timeout=1000)
                            reference.add(*constraints)
                            self.assertEqual(reference.check(), z3.unsat)
        self.assertGreater(accepted, 20)

    def test_only_native_claims_with_normal_exits_are_eligible(self):
        record = {'kind': 'satisfy', 'stats': {'exit': '0'}, 'command': 'cli',
                  'feasible': True, 'proven': False, 'finalWitness': '(model)'}
        self.assertEqual(worker.eligible(record), worker.GROUND)
        self.assertEqual(worker.eligible(dict(record, feasible=False, proven=True, finalWitness=None)), worker.REFUTATION)
        for changes in [{'feasible': None}, {'finalWitness': None}, {'kind': 'optimize'}, {'objective': 1},
                        {'stats': {'exit': '7'}}, {'feasible': False}, {'command': ''},
                        {'stats': {'exit': '0', 'killed': 'timeout'}}]:
            with self.subTest(changes=changes):
                self.assertIsNone(worker.eligible(dict(record, **changes)))

    def test_input_bindings_cover_verbatim_source_model_output_record_and_request(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            inputs = {'source.smt2': b'(assert true)', 'model.smt2': b'(model)', 'stdout.txt': b'sat\n(model)',
                      'record.json': b'{}', 'request.json': b'{}'}
            for name, raw in inputs.items():
                (out / name).write_bytes(raw)
            before = worker.bindings(out)
            self.assertEqual(before, {name: hashlib.sha256(raw).hexdigest() for name, raw in inputs.items()})
            (out / 'source.smt2').write_bytes(b'(assert false)')
            self.assertNotEqual(worker.bindings(out), before)


if __name__ == '__main__':
    unittest.main()
