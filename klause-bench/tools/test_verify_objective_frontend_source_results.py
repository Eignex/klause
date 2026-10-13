"""Adversarial original-source CLI objective publication checks."""

import hashlib
import json
import pathlib
import tempfile
import unittest

from verify_objective_frontend import verify


class ObjectiveFrontendSourceResultsTest(unittest.TestCase):
    def test_every_original_direction_improvement_precedes_the_final_terminal_check(self):
        for direction, points in (('minimize', (1, 0)), ('maximize', (0, 1))):
            with self.subTest(direction=direction), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                cases = []
                for encoding in ('affine', 'zero-square'):
                    objective = 'x' if encoding == 'affine' else '(+ x (* 0 x x))'
                    raw = '(declare-const x Real) (assert (and (>= x 0) (<= x 1))) ' \
                          '(' + direction + ' ' + objective + ') (check-sat)\n'
                    file = 'progress.' + encoding + '.smt2'
                    (root / file).write_text(raw)
                    cases.append(dict(file=file, family='progress', encoding=encoding, engines=['cp'],
                                      sha256=hashlib.sha256(raw.encode()).hexdigest()))
                    stem = 'progress.cp.' + encoding
                    output = ''.join('; objective=' + str(point) + '\nsat\n(\n' \
                                     '(define-fun x () Real ' + str(point) + ')\n)\n' for point in points)
                    output += '; optimizationStatus=optimal\n; objectiveBound=' + str(points[-1]) + '\n' \
                              '; objectiveBoundDirection=' + ('lower' if direction == 'minimize' else 'upper') + \
                              '\n; objectiveBoundStrict=false\n; quadraticNativeWork=1\n; lsMoves=0\n'
                    (root / (stem + '.stdout')).write_text(output)
                    (root / (stem + '.exit')).write_text('0\n')
                    (root / (stem + '.launch.json')).write_text(json.dumps(
                        dict(actualJvmArguments='-XX:ActiveProcessorCount=1', exit='0')))
                (root / 'manifest.json').write_text(json.dumps(dict(cases=cases)))

                verify(root)

                checks = json.loads((root / 'source-checks.json').read_text())
                self.assertEqual(2, len(checks['cases']))
                self.assertTrue(all(case['status'] == 'optimal' for case in checks['cases']))
                self.assertTrue(all(case['publicationCount'] == 2 for case in checks['cases']))
                self.assertTrue(all(len(case['publicationChecks']) == 2 for case in checks['cases']))

    def test_a_valid_final_model_cannot_hide_an_invalid_earlier_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = '(declare-const x Real) (assert (and (>= x 0) (<= x 1))) (minimize x) (check-sat)\n'
            (root / 'progress.affine.smt2').write_text(raw)
            (root / 'manifest.json').write_text(json.dumps(dict(cases=[
                dict(file='progress.affine.smt2', family='progress', encoding='affine', engines=['cp'],
                     sha256=hashlib.sha256(raw.encode()).hexdigest()),
            ])))
            output = ''.join('; objective=' + str(point) + '\nsat\n(\n' \
                             '(define-fun x () Real ' + str(point) + ')\n)\n' for point in (2, 0))
            output += '; optimizationStatus=optimal\n; quadraticNativeWork=1\n; lsMoves=0\n'
            (root / 'progress.cp.affine.stdout').write_text(output)
            (root / 'progress.cp.affine.exit').write_text('0\n')
            (root / 'progress.cp.affine.launch.json').write_text(json.dumps(
                dict(actualJvmArguments='-XX:ActiveProcessorCount=1', exit='0')))

            with self.assertRaisesRegex(AssertionError, 'original source assertion'):
                verify(root)

    def test_jfr_startup_diagnostics_do_not_enter_the_original_witness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            cases = []
            for encoding in ('affine', 'zero-square'):
                objective = 'x' if encoding == 'affine' else '(+ x (* 0 x x))'
                raw = '(declare-const x Real) (assert (= x 0)) (minimize ' + objective + ') (check-sat)\n'
                file = 'zero.' + encoding + '.smt2'
                (root / file).write_text(raw)
                cases.append(dict(file=file, family='zero', encoding=encoding, engines=['cp'],
                                  sha256=hashlib.sha256(raw.encode()).hexdigest()))
                stem = 'zero.cp.' + encoding
                (root / (stem + '.stdout')).write_text(
                    '[0.220s][info][jfr,startup] Started recording 1.\n'
                    '; optimizationStatus=optimal\n; objective=0\n; objectiveBound=0\n'
                    '; objectiveBoundDirection=lower\n; objectiveBoundStrict=false\n'
                    '; quadraticNativeWork=1\n; lsMoves=0\nsat\n(model (define-fun x () Real 0))\n')
                (root / (stem + '.exit')).write_text('0\n')
                (root / (stem + '.launch.json')).write_text(json.dumps(
                    dict(actualJvmArguments='-XX:ActiveProcessorCount=1', exit='0')))
            (root / 'manifest.json').write_text(json.dumps(dict(cases=cases)))

            verify(root)

            checks = json.loads((root / 'source-checks.json').read_text())
            self.assertEqual(2, len(checks['cases']))
            self.assertTrue(all(case['status'] == 'optimal' for case in checks['cases']))

    def test_unbounded_source_ray_is_anchored_at_the_actual_witness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            cases = []
            for encoding in ('affine', 'zero-square'):
                objective = '(- x)' if encoding == 'affine' else '(+ (- x) (* 0 x x))'
                raw = '(declare-const x Real) (assert (>= x 2)) (minimize ' + objective + ') (check-sat)\n'
                file = 'ray.' + encoding + '.smt2'
                (root / file).write_text(raw)
                cases.append(dict(file=file, family='ray', encoding=encoding, engines=['cp'],
                                  sha256=hashlib.sha256(raw.encode()).hexdigest()))
                stem = 'ray.cp.' + encoding
                (root / (stem + '.stdout')).write_text(
                    '; optimizationStatus=unbounded\n; objective=(- 2)\n; quadraticNativeWork=1\n'
                    '; lsMoves=0\nsat\n(model (define-fun x () Real 2))\n')
                (root / (stem + '.exit')).write_text('0\n')
                (root / (stem + '.launch.json')).write_text(json.dumps(
                    dict(actualJvmArguments='-XX:ActiveProcessorCount=1', exit='0')))
            (root / 'manifest.json').write_text(json.dumps(dict(cases=cases)))

            verify(root)

            checks = json.loads((root / 'source-checks.json').read_text())
            self.assertEqual(2, len(checks['cases']))
            self.assertTrue(all(case['rayCheck'] != 'none' for case in checks['cases']))

    def test_bounded_source_cannot_publish_an_unbounded_maximum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            raw = '(declare-const x Real) (assert (>= x 2)) (maximize (- x)) (check-sat)\n'
            (root / 'bounded.affine.smt2').write_text(raw)
            (root / 'manifest.json').write_text(json.dumps(dict(cases=[
                dict(file='bounded.affine.smt2', family='bounded', encoding='affine', engines=['cp'],
                     sha256=hashlib.sha256(raw.encode()).hexdigest()),
            ])))
            (root / 'bounded.cp.affine.stdout').write_text(
                '; optimizationStatus=unbounded\n; objective=(- 2)\n; quadraticNativeWork=1\n'
                '; lsMoves=0\nsat\n(model (define-fun x () Real 2))\n')
            (root / 'bounded.cp.affine.exit').write_text('0\n')
            (root / 'bounded.cp.affine.launch.json').write_text(json.dumps(
                dict(actualJvmArguments='-XX:ActiveProcessorCount=1', exit='0')))

            with self.assertRaises(AssertionError):
                verify(root)


if __name__ == '__main__':
    unittest.main()
