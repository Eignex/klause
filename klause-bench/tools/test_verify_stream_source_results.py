import unittest

from verify_stream_source_results import check_stream


class VerifyStreamSourceResultsTest(unittest.TestCase):
    def test_two_original_direction_improvements(self):
        for direction, points in (('minimize', (1, 0)), ('maximize', (0, 1))):
            with self.subTest(direction=direction):
                source = '(declare-const x Int) (assert (and (>= x 0) (<= x 1))) ' \
                         '(' + direction + ' (+ 7 x)) (check-sat)'
                output = ''.join('; objective=' + str(7 + point) + '\nsat\n(\n' \
                                 '(define-fun x () Int ' + str(point) + ')\n)\n' for point in points)

                result = check_stream(source, output, interrupted=True, minimum_witnesses=2)

                self.assertEqual(1, result['strictImprovements'])
                self.assertEqual(direction, result['direction'])
                self.assertFalse(result['proofCertificateChecked'])

    def test_exact_source_objectives(self):
        root = '(root-obj (+ (^ x 2) (- 2)) 2)'
        cases = (
            ('(declare-const x Real) (assert (= (* 3 x) 1)) '
             '(define-fun shifted ((arg Real)) Real (+ 7 arg)) (minimize (shifted x)) (check-sat)',
             '(/ 22.0 3.0)', '(define-fun x () Real (/ 1.0 3.0))'),
            ('(declare-const x Real) (assert (= (* 3 x) 1)) (minimize (+ 7 x)) (check-sat)',
             '22/3', '(define-fun x () Real (/ 1.0 3.0))'),
            ('(declare-const x Real) (assert (= (* 3 x) 1)) (maximize (- x)) (check-sat)',
             '-1/3', '(define-fun x () Real (/ 1.0 3.0))'),
            ('(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0)) (maximize x) (check-sat)',
             root, '(define-fun x () Real ' + root + ')'),
            ('(declare-const x Int) (assert (= x 100000000000000000000)) (minimize (* x x)) (check-sat)',
             '10000000000000000000000000000000000000000', '(define-fun x () Int 100000000000000000000)'),
        )
        for source, cost, model in cases:
            with self.subTest(cost=cost):
                result = check_stream(source, '; objective=' + cost + '\nsat\n(\n' + model + '\n)\n')

                self.assertEqual(1, len(result['witnesses']))
                self.assertTrue(result['exactOriginalObjective'])

    def test_cost_from_a_different_objective_is_rejected(self):
        source = '(declare-const x Int) (assert (= x 2)) (maximize (+ 7 x)) (check-sat)'
        output = '; objective=-9\nsat\n(\n(define-fun x () Int 2)\n)\n'

        with self.assertRaisesRegex(AssertionError, 'incorrect original objective'):
            check_stream(source, output)

    def test_source_invalid_point_is_rejected(self):
        source = '(declare-const x Int) (assert (= x 3)) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n'

        with self.assertRaisesRegex(AssertionError, 'original source assertion'):
            check_stream(source, output)

    def test_duplicate_final_witness_is_rejected(self):
        source = '(declare-const x Int) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n'

        with self.assertRaisesRegex(AssertionError, 'does not strictly improve'):
            check_stream(source, output + output)

    def test_model_requires_its_own_cost(self):
        source = '(declare-const x Int) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n' \
                 'sat\n(\n(define-fun x () Int 1)\n)\n'

        with self.assertRaisesRegex(AssertionError, 'model without exact cost'):
            check_stream(source, output)

    def test_interruption_retains_complete_models_before_a_partial_tail(self):
        source = '(declare-const x Int) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n; objective=1\nsat\n(\n'

        result = check_stream(source, output, interrupted=True)

        self.assertEqual(1, len(result['witnesses']))
        self.assertTrue(result['truncatedPublication'])
        with self.assertRaisesRegex(AssertionError, 'incomplete final publication'):
            check_stream(source, output)

    def test_interrupted_output_cannot_claim_terminal_optimization_status(self):
        source = '(declare-const x Int) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n; optimizationStatus=optimal\n'

        with self.assertRaisesRegex(AssertionError, 'terminal optimization status'):
            check_stream(source, output, interrupted=True)

    def test_completed_output_can_report_terminal_status_after_models(self):
        source = '(declare-const x Int) (assert (= x 2)) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n; optimizationStatus=optimal\n'

        result = check_stream(source, output)

        self.assertEqual(1, len(result['witnesses']))
        self.assertFalse(result['proofCertificateChecked'])

    def test_minimum_complete_witness_count_is_enforced(self):
        source = '(declare-const x Int) (minimize x) (check-sat)'
        output = '; objective=2\nsat\n(\n(define-fun x () Int 2)\n)\n'

        with self.assertRaisesRegex(AssertionError, 'missing complete checked witnesses'):
            check_stream(source, output, minimum_witnesses=2)


if __name__ == '__main__':
    unittest.main()
