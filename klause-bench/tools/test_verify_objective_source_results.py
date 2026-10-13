"""Adversarial original-source objective probe checks."""

import hashlib
import json
import pathlib
import tempfile
import unittest

from verify_objective_probe import check_capture, check_record


class ObjectiveSourceResultsTest(unittest.TestCase):
    def test_required_capture_cannot_be_omitted_from_an_optimal_record(self):
        payload = dict(encoding='linear', result=dict(status='Optimal'))

        with self.assertRaisesRegex(AssertionError, 'required source capture is absent'):
            check_capture(payload, True)

    def test_required_native_capture_rejects_unchecked_objective_replay(self):
        payload = dict(encoding='linear', result=dict(status='Optimal'), sourceCapture=dict(
            captured=True, nativeCompletion=True, copiedObjectiveReplay='not checked',
        ))

        with self.assertRaisesRegex(AssertionError, 'copied original objective replay was not rejected'):
            check_capture(payload, True)

    def test_unavailable_derivation_coverage_remains_separate_from_runtime_capture(self):
        payload = dict(encoding='linear', result=dict(status='Optimal'), sourceCapture=dict(
            captured=True, nativeCompletion=True, copiedObjectiveReplay='foreign original objective rejected',
            derivationCheck='unavailable', unavailableReason='branch coverage not captured',
            exportedProof='not captured',
        ))

        outcome = check_capture(payload, True)

        self.assertEqual('runtime scope and replay assertions retained; no independent proof export check', outcome)

    def test_boolean_integer_and_real_coordinates_keep_their_original_sorts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = ('(declare-const b Bool) (declare-const i Int) (declare-const x Real) '
                   '(assert b) (assert (= i 1)) (assert (= x (/ 1 2))) '
                   '(minimize (+ (ite b 1 0) i x)) (check-sat)')
            identity = hashlib.sha256(raw.encode()).hexdigest()
            (root / (identity + '.smt2')).write_text(raw)
            path = root / (identity + '.BACKTRACK.linear.json')
            path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                            result=dict(status='Optimal', sense='MINIMIZE',
                                                        witness={'b': 'true', 'i': '1', 'x': '(/ 1 2)'},
                                                        objective='(/ 5 2)', bound='(/ 5 2)', strict=False))))

            checked, _ = check_record(root, path, True)

            self.assertEqual('checked', checked['witness'])
            self.assertEqual('checked', checked['bound'])

    def test_fractional_integer_coordinate_is_rejected_before_substitution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = '(declare-const i Int) (assert (= i 1)) (minimize i) (check-sat)'
            identity = hashlib.sha256(raw.encode()).hexdigest()
            (root / (identity + '.smt2')).write_text(raw)
            path = root / (identity + '.BACKTRACK.linear.json')
            path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                            result=dict(status='BestFound', sense='MINIMIZE',
                                                        witness={'i': '(/ 3 2)'}, objective='(/ 3 2)'))))

            with self.assertRaisesRegex(AssertionError, 'integer coordinate is not exact and integral'):
                check_record(root, path, True)

    def test_adjacent_large_exact_costs_remain_distinct(self):
        for direction, x, expected in (('minimize', '0', '9223372036854775807'),
                                       ('maximize', '1', '9223372036854775808')):
            with self.subTest(direction=direction), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                raw = ('(declare-const x Real) (assert (>= x 0)) (assert (<= x 1)) '
                       '(' + direction + ' (+ 9223372036854775807 x)) (check-sat)')
                identity = hashlib.sha256(raw.encode()).hexdigest()
                (root / (identity + '.smt2')).write_text(raw)
                path = root / (identity + '.BACKTRACK.linear.json')
                path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                                result=dict(status='Optimal', sense=direction.upper(),
                                                            witness={'x': x}, objective=expected, bound=expected,
                                                            strict=False))))

                checked, _ = check_record(root, path, True)

                self.assertEqual('checked', checked['witness'])
                self.assertEqual('checked', checked['bound'])

    def test_rounded_large_cost_cannot_replace_exact_source_cost(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = ('(declare-const x Real) (assert (= x 1)) '
                   '(maximize (+ 9223372036854775807 x)) (check-sat)')
            identity = hashlib.sha256(raw.encode()).hexdigest()
            (root / (identity + '.smt2')).write_text(raw)
            path = root / (identity + '.BACKTRACK.linear.json')
            path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                            result=dict(status='BestFound', sense='MAXIMIZE', witness={'x': '1'},
                                                        objective='9223372036854775807'))))

            with self.assertRaisesRegex(AssertionError, 'exact source objective differs'):
                check_record(root, path, True)

    def test_attained_source_bound_cannot_be_claimed_strict(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = '(declare-const x Real) (assert (>= x 0)) (minimize x) (check-sat)'
            identity = hashlib.sha256(raw.encode()).hexdigest()
            (root / (identity + '.smt2')).write_text(raw)
            path = root / (identity + '.BACKTRACK.linear.json')
            path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                            result=dict(status='UnattainedLimit', sense='MINIMIZE',
                                                        witness={'x': '1'}, objective='1', bound='0', strict=True))))

            with self.assertRaisesRegex(AssertionError, 'original source inequality was not established'):
                check_record(root, path, True)

    def test_false_source_boolean_pin_rejects_a_numeric_witness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = ('(declare-const b Bool) (declare-const x Real) (assert b) '
                   '(assert (= x 0)) (minimize x) (check-sat)')
            identity = hashlib.sha256(raw.encode()).hexdigest()
            (root / (identity + '.smt2')).write_text(raw)
            path = root / (identity + '.BACKTRACK.linear.json')
            path.write_text(json.dumps(dict(sourceSha256=identity, engine='BACKTRACK', encoding='linear',
                                            result=dict(status='BestFound', sense='MINIMIZE',
                                                        witness={'b': 'false', 'x': '0'}, objective='0'))))

            with self.assertRaisesRegex(AssertionError, 'witness violates the original source'):
                check_record(root, path, True)


if __name__ == '__main__':
    unittest.main()
