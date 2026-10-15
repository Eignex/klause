import hashlib
import unittest
from unittest import mock

import z3

import verify_nra_source_results as check


class NraSourceCheckTest(unittest.TestCase):
    positive_sqrt_two = '(root-obj (+ (^ x 2) (- 2)) 2)'
    negative_sqrt_two = '(root-obj (+ (^ x 2) (- 2)) 1)'

    def test_absolute_value_witnesses_preserve_sign_and_arbitrary_width(self):
        source = '(declare-const x Int) (declare-const y Int) (assert (= y (abs x)))'
        for operand in (0, 3, -3, -9223372036854775808, -18446744073709551616):
            with self.subTest(operand=operand), \
                    mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
                result = check.check_witness(source,
                    f'((define-fun x () Int {operand}) (define-fun y () Int {abs(operand)}))')

                self.assertEqual(1, result['assertions'])

    def test_absolute_value_rejects_points_allowed_only_by_its_relaxation(self):
        source = '(declare-const x Int) (declare-const y Int) (assert (= y (abs x)))'
        for operand, result in ((0, 2), (-3, 4), (3, -3), (-9223372036854775808, -9223372036854775808)):
            with self.subTest(operand=operand, result=result), \
                    self.assertRaisesRegex(AssertionError, 'original source assertion 0 is false'):
                check.check_witness(source,
                    f'((define-fun x () Int {operand}) (define-fun y () Int {result}))')

    def test_bundle_checks_the_actual_original_witness_and_preserves_producer(self):
        source = '(declare-const n Int) (assert (= (* n n) 9))'
        case = {'source': source, 'hash': hashlib.sha256(source.encode()).hexdigest(), 'feasible': True,
                'witness': '((define-fun n () Int 3))', 'producerCommit': 'actual-producer'}

        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            result = check.check_bundle([case])[0]

        self.assertEqual('actual-producer', result['producerCommit'])
        self.assertEqual(1, result['assertions'])

    def test_bundle_refuses_foreign_original_bytes(self):
        with self.assertRaisesRegex(AssertionError, 'source hash mismatch'):
            check.check_bundle([{'source': '(check-sat)', 'hash': 'foreign'}])

    def test_bundle_unknown_verdict_carries_no_source_claim(self):
        source = '(check-sat)'
        case = {'source': source, 'hash': hashlib.sha256(source.encode()).hexdigest(), 'feasible': None}

        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            result = check.check_bundle([case])[0]

        self.assertEqual('incomplete verdict carries no claim', result['check'])

    def test_irrational_coordinate_is_checked_without_a_solver_query(self):
        source = '(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0))'
        witness = f'((define-fun x () Real {self.positive_sqrt_two}))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            result = check.check_witness(source, witness)
        self.assertEqual(result['coordinates'], 1)
        self.assertEqual(result['assertions'], 2)

    def test_algebraic_coordinates_preserve_coupled_source_equalities(self):
        source = '(declare-const a Real) (declare-const b Real) ' \
                 '(assert (= (* a a) 2)) (assert (= (* b b) a)) (assert (> b 0))'
        witness = f'((define-fun a () Real {self.positive_sqrt_two}) ' \
                  '(define-fun b () Real (root-obj (+ (^ x 4) (- 2)) 2)))'
        self.assertEqual(check.check_witness(source, witness)['assertions'], 3)

    def test_wrong_algebraic_root_is_rejected_by_original_sign_constraint(self):
        source = '(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0))'
        witness = f'((define-fun x () Real {self.negative_sqrt_two}))'
        with self.assertRaisesRegex(AssertionError, 'original source assertion 1 is false'):
            check.check_witness(source, witness)

    def test_rounded_coordinates_cannot_satisfy_exact_irrational_equality(self):
        source = '(declare-const x Real) (assert (= (* x x) 2))'
        for value in ('1.4142135623730951', '1.4142135623730951?'):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                check.check_witness(source, f'((define-fun x () Real {value}))')

    def test_strict_boundary_is_rejected(self):
        source = '(declare-const x Real) (assert (< (* x x) 2))'
        witness = f'((define-fun x () Real {self.positive_sqrt_two}))'
        with self.assertRaisesRegex(AssertionError, 'original source assertion 0 is false'):
            check.check_witness(source, witness)

    def test_quoted_declarations_let_and_typed_functions_retain_original_semantics(self):
        source = '(declare-const |source x| Real) (declare-const p Bool) ' \
                 '(define-fun square ((u Real)) Real (* u u)) ' \
                 '(assert (let ((a (square |source x|)) (b p)) (and b (= a 2)))) ' \
                 '(check-sat-assuming (p))'
        witness = f'(model (define-fun |source x| () Real {self.positive_sqrt_two}) ' \
                  '(define-fun p () Bool true))'
        self.assertEqual(check.check_witness(source, witness)['coordinates'], 2)

    def test_source_assumptions_are_checked(self):
        source = '(declare-const p Bool) (assert (or p (not p))) (check-sat-assuming ((not p)))'
        with self.assertRaisesRegex(AssertionError, 'original source assertion 1 is false'):
            check.check_witness(source, '((define-fun p () Bool true))')

    def test_unconstrained_source_coordinates_are_required(self):
        source = '(declare-const x Real) (declare-const unused Real) (assert (= x 0))'
        with self.assertRaisesRegex(AssertionError, 'incomplete source model'):
            check.check_witness(source, '((define-fun x () Real 0))')

    def test_unused_division_completion_preserves_all_scalar_source_checks(self):
        source = '(declare-const x Real) (assert (= (/ x 2) 1))'
        definitions = '(define-fun x () Real 2) ' \
                      '(define-fun /0 ((a Real) (b Real)) Real (ite (= b 0) a 0))'
        for envelope in ('(' + definitions + ')', '(model ' + definitions + ')'):
            with self.subTest(envelope=envelope), \
                    mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
                result = check.check_witness(source, envelope)
            self.assertEqual(1, result['coordinates'])
            self.assertEqual(1, result['assertions'])
        with self.assertRaisesRegex(AssertionError, 'original source assertion 0 is false'):
            check.check_witness(source, '(' + definitions.replace('() Real 2', '() Real 3') + ')')

    def test_division_completion_cannot_supply_missing_source_coordinates(self):
        source = '(declare-const x Real) (declare-const unused Real) (assert (= x 0))'
        witness = '((define-fun x () Real 0) (define-fun /0 ((a Real) (b Real)) Real 0))'
        with self.assertRaisesRegex(AssertionError, 'incomplete source model'):
            check.check_witness(source, witness)

    def test_auxiliary_model_definitions_require_the_unique_division_signature(self):
        source = '(declare-const x Real) (assert (= x 0))'
        division = '(define-fun /0 ((a Real) (b Real)) Real 0)'
        variants = (
            '(define-fun other ((a Real) (b Real)) Real 0)',
            division + ' ' + division,
            '(define-fun /0 ((a Int) (b Real)) Real 0)',
            '(define-fun /0 ((a Real) (b Real)) Int 0)',
            '(define-fun /0 ((a Real)) Real 0)',
            '(define-fun /0 ((a Real) (a Real)) Real 0)',
            '(define-fun /0 (a b) Real 0)',
        )
        for auxiliary in variants:
            with self.subTest(auxiliary=auxiliary), self.assertRaises(AssertionError):
                check.check_witness(source, '((define-fun x () Real 0) ' + auxiliary + ')')
        with self.assertRaisesRegex(AssertionError, 'unsupported auxiliary model definition'):
            check.witness_values('(' + division + ')', {'/0': z3.Real('/0')}, allow_unused_division=True)

    def test_division_completion_is_declined_without_source_admission(self):
        witness = '((define-fun x () Real 0) (define-fun /0 ((a Real) (b Real)) Real 0))'
        with self.assertRaisesRegex(AssertionError, 'unsupported auxiliary model definition'):
            check.witness_values(witness, {'x': z3.Real('x')})

    def test_division_completion_cannot_override_undefined_source_division(self):
        witness = '((define-fun x () Real 0) (define-fun /0 ((a Real) (b Real)) Real 0))'
        for denominator in ('0', 'x'):
            source = '(declare-const x Real) (assert (= (/ x ' + denominator + ') 0))'
            with self.subTest(denominator=denominator), \
                    mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')), \
                    self.assertRaisesRegex(AssertionError, 'nonconstant source division'):
                check.check_witness(source, witness)

    def test_ambiguous_source_models_are_rejected(self):
        source = '(declare-const x Real) (assert (= x 0))'
        variants = (
            '((define-fun x () Real 0) (define-fun x () Real 0))',
            '((define-fun x () Real 0) (define-fun other () Real 0))',
            '((define-fun x () Bool true))',
            '((define-fun x () Real y))',
        )
        for witness in variants:
            with self.subTest(witness=witness), self.assertRaises((AssertionError, ValueError)):
                check.check_witness(source, witness)

    def test_root_coordinates_cannot_depend_on_source_symbols(self):
        source = '(declare-const x Real)'
        variants = ('(root-obj (+ (^ y 2) (- 2)) 2)', '(root-obj (+ (^ x 2) (- 2)) 0)',
                    '(root-obj (+ (^ x 2) (- 2)) 3)', '(root-obj (+ (^ x -2) (- 2)) 2)')
        for value in variants:
            with self.subTest(value=value), self.assertRaises((AssertionError, z3.Z3Exception)):
                check.check_witness(source, f'((define-fun x () Real {value}))')

    def test_exact_wide_decimal_coefficients_are_not_rounded(self):
        source = '(declare-const x Real) (assert (= (* 0.10000000000000000000001 x) 1))'
        value = '(/ 100000000000000000000000 10000000000000000000001)'
        self.assertEqual(check.check_witness(source, f'((define-fun x () Real {value}))')['assertions'], 1)

    def test_nonpolynomial_and_incremental_sources_are_declined(self):
        variants = (
            '(declare-const x Int) (assert (= (div x 2) 1))',
            '(declare-const x Real) (declare-const y Real) (assert (= (/ x y) 1))',
            '(declare-const x Real) (assert (= (^ x (- 1)) 1))',
            '(declare-const x Real) (assert (= (^ x (/ 1 2)) 1))',
            '(declare-const x Real) (assert (forall ((y Real)) (= x y)))',
            '(declare-const x Real) (minimize x)',
            '(declare-const x Real) (push) (assert (= x 0))',
            '(declare-const x Real) (check-sat) (assert (= x 0))',
            '(declare-const p Bool) (check-sat-assuming ((or p (not p))))',
        )
        for source in variants:
            with self.subTest(source=source), self.assertRaises(AssertionError):
                check.source_formula(source)

    def test_mixed_integer_witness_checks_original_products_and_exact_real_roots(self):
        source = '(declare-const n Int) (declare-const x Real) ' \
                 '(assert (= (* n n) 4)) (assert (= (* x x) (to_real n)))'
        witness = f'((define-fun n () Int 2) (define-fun x () Real {self.positive_sqrt_two}))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            self.assertEqual(check.check_witness(source, witness)['assertions'], 2)

    def test_wide_integer_source_arithmetic_does_not_wrap(self):
        source = '(declare-const n Int) (assert (= (* n n) 10000000000000000000000000000000000000000))'
        witness = '((define-fun n () Int 100000000000000000000))'
        self.assertEqual(check.check_witness(source, witness)['coordinates'], 1)
        with self.assertRaisesRegex(AssertionError, 'original source assertion 0 is false'):
            check.check_witness(source, '((define-fun n () Int 7766279631452241920))')

    def test_fractional_integer_coordinate_is_rejected_before_substitution(self):
        source = '(declare-const n Int) (assert (= (* n n) 2))'
        with self.assertRaisesRegex(AssertionError, 'nonintegral integer coordinate'):
            check.check_witness(source, '((define-fun n () Int (/ 3 2)))')

    def test_integer_functions_and_assumptions_keep_lexical_meaning(self):
        source = '(declare-const n Int) (declare-const p Bool) ' \
                 '(define-fun square ((x Int)) Int (* n x)) ' \
                 '(assert (let ((n 99)) (= p (= (square 2) 6)))) (check-sat-assuming (p))'
        witness = '((define-fun n () Int 3) (define-fun p () Bool true))'
        self.assertEqual(check.check_witness(source, witness)['assertions'], 2)
        with self.assertRaisesRegex(AssertionError, 'original source assertion 0 is false'):
            check.check_witness(source, '((define-fun n () Int 99) (define-fun p () Bool true))')

    def test_deep_rendering_preserves_exact_tokens_and_spacing(self):
        variants = (
            ('|source x|', '|source x|'),
            ([], '()'),
            (['and', '|source x|', ['not', 'false'], []], '(and |source x| (not false) ())'),
        )
        for expression, expected in variants:
            with self.subTest(expression=expression):
                self.assertEqual(check.sexpr(expression), expected)
        expression = '|source x|'
        for _ in range(2000):
            expression = ['identity', expression]
        self.assertEqual(check.sexpr(expression), '(identity ' * 2000 + '|source x|' + ')' * 2000)

    def test_deep_original_assertion_is_checked_without_a_solver_query(self):
        expression = '(+ ' * 1600 + 'x' + ' 0)' * 1600
        source = '(declare-const x Int) (assert (= ' + expression + ' 3)) (assert (> x 0))'
        witness = '((define-fun x () Int 3))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            result = check.check_witness(source, witness)
        self.assertEqual(result['coordinates'], 1)
        self.assertEqual(result['assertions'], 2)

    def test_complete_model_checks_coupled_integer_real_and_boolean_assertions(self):
        source = '(declare-const n Int) (declare-const a Real) (declare-const b Real) ' \
                 '(declare-const p Bool) (declare-const unused Real) ' \
                 '(assert (= (* n n) 4)) (assert (= (* a a) (to_real n))) ' \
                 '(assert (= b a)) (assert p) (assert (> b 0))'
        witness = f'((define-fun n () Int 2) (define-fun a () Real {self.positive_sqrt_two}) ' \
                  f'(define-fun b () Real {self.positive_sqrt_two}) ' \
                  '(define-fun p () Bool true) (define-fun unused () Real (/ 1 3)))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            result = check.check_witness(source, witness)
        self.assertEqual(result['coordinates'], 5)
        self.assertEqual(result['assertions'], 5)

    def test_a_false_later_assertion_keeps_its_original_index(self):
        source = '(declare-const n Int) (declare-const x Real) (declare-const p Bool) ' \
                 '(assert (= n 2)) (assert (= x (/ 1 3))) (assert p) ' \
                 '(assert (> (* (to_real n) x) 1))'
        witness = '((define-fun n () Int 2) (define-fun x () Real (/ 1 3)) (define-fun p () Bool true))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')), \
                self.assertRaisesRegex(AssertionError, 'original source assertion 3 is false'):
            check.check_witness(source, witness)

    def test_undefined_zero_powers_cannot_receive_an_arbitrary_model_value(self):
        for expected in ('0', '1'):
            source = '(declare-const x Real) (assert (= x 0)) (assert (= (^ x 0) ' + expected + '))'
            with self.subTest(expected=expected), \
                    mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')), \
                    self.assertRaisesRegex(AssertionError, 'original source assertion 1 did not evaluate exactly'):
                check.check_witness(source, '((define-fun x () Real 0))')
        source = '(declare-const x Real) (assert (= x 1)) (assert (= (^ x 0) 1))'
        with mock.patch.object(check.z3, 'Solver', side_effect=AssertionError('no query expected')):
            self.assertEqual(check.check_witness(source, '((define-fun x () Real 1))')['assertions'], 2)

    def test_reference_unsat_comparison_is_not_labeled_a_proof_certificate(self):
        result = check.compare_verdict('(declare-const x Real) (assert (< (* x x) 0))', 'unsat')
        self.assertEqual(result['referenceComparison'], 'unsat')
        self.assertFalse(result['proofCertificateChecked'])

    def test_reference_disagreement_fails(self):
        with self.assertRaisesRegex(AssertionError, 'reference comparison expected unsat, got sat'):
            check.compare_verdict('(declare-const x Real) (assert (= (* x x) 2))', 'unsat')

    def test_reference_unknown_is_reported_as_incomplete(self):
        solver = z3.Solver()
        with mock.patch.object(check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', return_value=z3.unknown), \
                mock.patch.object(solver, 'reason_unknown', return_value='timeout'):
            with self.assertRaisesRegex(AssertionError, 'got unknown; reason: timeout'):
                check.compare_verdict('(declare-const x Real) (assert (= (* x x) 2))', 'sat')


if __name__ == '__main__':
    unittest.main()
