import unittest

from verify_function_stream_source_results import check_stream
from verify_transcendental_source_results import InvalidDomain, Unavailable


class FunctionStreamSourceTest(unittest.TestCase):
    def test_ground_function_identity_accepts_original_point(self):
        source = '(declare-const x Real) (assert (= x (exp 0))) (check-sat)'
        report = check_stream(source, 'sat\n(\n  (define-fun x () Real 1.0)\n)\n')
        self.assertTrue(report['allOriginalAssertions'])
        self.assertEqual(1, len(report['witnesses']))
        self.assertEqual(0, report['solverSearchQueries'])
        self.assertFalse(report['proofCertificateChecked'])

    def test_symbolic_function_value_retains_its_exact_identity(self):
        source = '(declare-const x Real) (assert (= x (exp 1))) (assert (> x 0))'
        report = check_stream(source, '(\n  (define-fun x () Real (exp 1.0))\n)\n')
        self.assertEqual(1, len(report['witnesses']))

    def test_invalid_original_function_domain_rejects_a_candidate(self):
        source = '(declare-const x Real) (assert (= x (log (- 1))))'
        with self.assertRaises(InvalidDomain):
            check_stream(source, '(\n  (define-fun x () Real 0.0)\n)\n')

    def test_missing_original_declaration_rejects_a_model(self):
        source = '(declare-const x Real) (declare-const b Bool) (assert (= x (exp 0)))'
        with self.assertRaises(ValueError):
            check_stream(source, '(\n  (define-fun x () Real 1.0)\n)\n')

    def test_foreign_model_sort_rejects_a_candidate(self):
        source = '(declare-const x Real) (assert (= x (exp 0)))'
        with self.assertRaises(AssertionError):
            check_stream(source, '(\n  (define-fun x () Int 1)\n)\n')

    def test_algebraic_carrier_does_not_receive_symbolic_function_credit(self):
        source = '(declare-const x Real) (assert (> x 0))'
        with self.assertRaises(Unavailable):
            check_stream(source, '(\n  (define-fun x () Real (root-obj (- (^ x 2) 2) 2))\n)\n')

    def test_every_completed_publication_must_satisfy_the_original_source(self):
        source = '(declare-const x Real) (assert (= x (exp 0)))'
        output = '(\n  (define-fun x () Real 1.0)\n)\n(\n  (define-fun x () Real 0.0)\n)\n'
        with self.assertRaises(ValueError):
            check_stream(source, output)

    def test_interrupted_output_can_retain_a_checked_complete_prefix(self):
        source = '(declare-const x Real) (assert (= x (exp 0)))'
        output = '(\n  (define-fun x () Real 1.0)\n)\n(\n'
        with self.assertRaises(AssertionError):
            check_stream(source, output)
        report = check_stream(source, output, interrupted=True)
        self.assertTrue(report['truncatedPublication'])
        self.assertEqual(1, len(report['witnesses']))

    def test_optimization_metadata_receives_no_function_witness_credit(self):
        source = '(declare-const x Real) (assert (= x (exp 0)))'
        with self.assertRaises(ValueError):
            check_stream(source, '; optimizationStatus=optimal\n(\n  (define-fun x () Real 1.0)\n)\n')
