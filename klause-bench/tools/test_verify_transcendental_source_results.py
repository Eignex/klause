import unittest

from verify_transcendental_source_results import InvalidDomain, Unavailable, check_witness


class TranscendentalSourceResultsTest(unittest.TestCase):
    def test_inverse_witness_satisfies_exact_source_but_an_approximation_does_not(self):
        source = '(declare-const x Real) (assert (= (exp x) 2)) (check-sat)'
        check_witness(source, {'x': 'log(2)'})
        with self.assertRaises(ValueError):
            check_witness(source, {'x': '0'})

    def test_strict_source_endpoint_rejects_the_exact_boundary(self):
        source = '(declare-const x Real) (assert (> x 0)) (assert (= (exp x) 1)) (check-sat)'
        with self.assertRaises(ValueError):
            check_witness(source, {'x': '0'})

    def test_undefined_log_is_invalid_inside_negation_and_disjunction(self):
        for assertion in ('(not (= (log x) 0))', '(or true (= (log x) 0))'):
            with self.subTest(assertion=assertion), self.assertRaises(InvalidDomain):
                check_witness('(declare-const x Real) (assert ' + assertion + ')', {'x': '0'})

    def test_unselected_conditional_branch_does_not_require_a_log_domain(self):
        check_witness('(declare-const x Real) (assert (= (ite true 1 (log x)) 1))', {'x': '0'})

    def test_unknown_algebraic_representation_is_withheld(self):
        with self.assertRaises(Unavailable):
            check_witness('(declare-const x Real) (assert (> (exp x) 0))', {'x': 'root(0;[-2,0,1])'})

    def test_nonintegral_and_missing_coordinates_are_rejected(self):
        for witness in ({'i': '1/2'}, {}):
            with self.subTest(witness=witness), self.assertRaises(ValueError):
                check_witness('(declare-const i Int) (assert (> (exp (to_real i)) 0))', witness)

    def test_exact_pi_function_identities_are_checked(self):
        check_witness('(declare-const x Real) (assert (= (sin x) 0)) (assert (= (cos x) (- 1)))', {'x': 'pi'})

    def test_unavailable_function_equality_does_not_use_a_residual(self):
        with self.assertRaises(Unavailable):
            check_witness('(declare-const x Real) (assert (= (sin x) 1))', {'x': '1'})
