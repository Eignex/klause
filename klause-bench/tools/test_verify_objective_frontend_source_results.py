"""Adversarial original-source CLI objective publication checks."""

import hashlib
import json
import pathlib
import tempfile
import unittest

from verify_objective_frontend import verify


class ObjectiveFrontendSourceResultsTest(unittest.TestCase):
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
