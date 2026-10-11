import unittest
from unittest import mock

import z3

import verify_nra_source_results as check


class NraSourceCheckTest(unittest.TestCase):
    positive_sqrt_two = '(root-obj (+ (^ x 2) (- 2)) 2)'
    negative_sqrt_two = '(root-obj (+ (^ x 2) (- 2)) 1)'

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
            '(declare-const x Int) (assert (= x 1))',
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
