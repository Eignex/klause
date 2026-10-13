import hashlib
import json
import unittest

from verify_quadratic_box_results import check_record


class QuadraticBoxResultsTest(unittest.TestCase):
    def test_attained_bounds_preserve_original_direction(self):
        for sense, coefficient in [('MINIMIZE', '1'), ('MAXIMIZE', '-1')]:
            with self.subTest(sense=sense):
                source = json.dumps({'semantics': 'klause-ir-exact-v1', 'reals': [{'lower': '-2', 'upper': '3'}],
                                     'objective': {'sense': sense, 'constant': '0',
                                                   'terms': [{'coefficient': coefficient, 'first': 0, 'second': 0}]}}).encode()
                record = {'sourceHash': hashlib.sha256(source).hexdigest(), 'sense': sense, 'kind': 'Optimal',
                          'witness': ['0'], 'value': '0', 'bound': '0', 'strict': False}

                result = check_record(source, record)

                self.assertEqual('Checked', result['terminalCheck'])

    def test_a_false_attainment_claim_is_rejected(self):
        source = json.dumps({'semantics': 'klause-ir-exact-v1', 'reals': [{'lower': '-2', 'upper': '3'}],
                             'objective': {'sense': 'MINIMIZE', 'constant': '0',
                                           'terms': [{'coefficient': '1', 'first': 0, 'second': 0}]}}).encode()
        record = {'sourceHash': hashlib.sha256(source).hexdigest(), 'sense': 'MINIMIZE', 'kind': 'Optimal',
                  'witness': ['1'], 'value': '1', 'bound': '0', 'strict': False}

        with self.assertRaisesRegex(AssertionError, 'does not attain'):
            check_record(source, record)

    def test_foreign_source_bytes_are_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'foreign original source'):
            check_record(b'{}', {'sourceHash': 'another-source'})

    def test_algebraic_payloads_withhold_rational_checking(self):
        source = json.dumps({'semantics': 'klause-ir-exact-v1', 'reals': [{'lower': '-2', 'upper': '3'}],
                             'objective': {'sense': 'MINIMIZE', 'constant': '0', 'terms': []}}).encode()
        record = {'sourceHash': hashlib.sha256(source).hexdigest(), 'sense': 'MINIMIZE',
                  'witness': [None], 'value': None}

        self.assertEqual('Unavailable', check_record(source, record)['terminalCheck'])

    def test_incomplete_receipts_require_strict_original_direction_improvements(self):
        for sense, values in [('MINIMIZE', ['2', '1']), ('MAXIMIZE', ['1', '2'])]:
            with self.subTest(sense=sense):
                source = json.dumps({'semantics': 'klause-ir-exact-v1', 'reals': [{'lower': '0', 'upper': '3'}],
                                     'objective': {'sense': sense, 'constant': '0',
                                                   'terms': [{'coefficient': '1', 'first': 0}]}}).encode()
                record = {'sourceHash': hashlib.sha256(source).hexdigest(), 'sense': sense, 'kind': 'Bounded',
                          'witness': [values[-1]], 'value': values[-1], 'publicationCount': 2,
                          'acceptedVersion': 2, 'acceptedIdentityRetained': True,
                          'publications': [{'version': index, 'witness': [value], 'value': value}
                                           for index, value in enumerate(values, 1)]}

                self.assertEqual('Checked', check_record(source, record)['witnessCheck'])
                record['publications'][1] = dict(record['publications'][0], version=2)
                with self.assertRaisesRegex(AssertionError, 'does not strictly improve'):
                    check_record(source, record)

    def test_an_uninstalled_final_receipt_is_rejected(self):
        source = json.dumps({'semantics': 'klause-ir-exact-v1', 'reals': [{'lower': '0', 'upper': '3'}],
                             'objective': {'sense': 'MINIMIZE', 'constant': '0',
                                           'terms': [{'coefficient': '1', 'first': 0}]}}).encode()
        record = {'sourceHash': hashlib.sha256(source).hexdigest(), 'sense': 'MINIMIZE', 'kind': 'Bounded',
                  'witness': ['0'], 'value': '0', 'publicationCount': 1, 'acceptedVersion': 1,
                  'acceptedIdentityRetained': True, 'publications': [{'version': 1, 'witness': ['1'], 'value': '1'}]}

        with self.assertRaisesRegex(AssertionError, 'final accepted value differs'):
            check_record(source, record)
