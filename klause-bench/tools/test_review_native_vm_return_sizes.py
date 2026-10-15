import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_return_sizes as review


class NativeVmReturnSizesTest(unittest.TestCase):
    def test_size_calls_keep_their_observed_broker_and_post_context(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': []}]}
                  for sequence, category, kind in [(1, 'ENTRY', 'total-size'), (2, 'RETURN', 'total-size'),
                       (3, 'ENTRY', 'broker'), (4, 'ENTRY', 'post'), (5, 'ENTRY', 'total-size'),
                       (6, 'RETURN', 'total-size'), (7, 'RETURN', 'post'), (8, 'ENTRY', 'total-size')]]

        calls, issues = review.call_frames({'threads': [groups], 'errors': []})

        self.assertIsNone(calls[0]['brokerEntryKey'])
        self.assertIsNone(calls[0]['postEntryKey'])
        self.assertEqual((100, 101, 3), calls[1]['brokerEntryKey'])
        self.assertEqual((100, 101, 4), calls[1]['postEntryKey'])
        self.assertIn('return', calls[1])
        self.assertEqual((100, 101, 3), calls[2]['brokerEntryKey'])
        self.assertIsNone(calls[2]['postEntryKey'])
        self.assertNotIn('return', calls[2])
        self.assertEqual(['Unfinished native invocation', 'Unfinished native invocation'],
                         [row['reason'] for row in issues])

    def test_non_lifo_returns_and_incomplete_groups_do_not_complete_a_size_call(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': errors,
                   'primary': [{'type': category, 'kind': kind, 'values': []}]}
                  for sequence, category, kind, errors in [(1, 'ENTRY', 'total-size', []),
                       (2, 'ENTRY', 'post', []), (3, 'RETURN', 'total-size', []),
                       (4, 'RETURN', 'post', []), (5, 'RETURN', 'total-size', ['missing fragment'])]]

        calls, issues = review.call_frames({'threads': [groups], 'errors': []})

        self.assertNotIn('return', calls[0])
        self.assertEqual(['Unmatched or non-LIFO return', 'Incomplete native group', 'Unfinished native invocation'],
                         [row['reason'] for row in issues])

    def test_return_metadata_checks_low_bits_and_the_signed_source_width(self):
        variants = [(['40', '64'], True), (['abcdef0000000040', '64'], True), (['0', '0'], True),
                    (['41', '64'], False), (['80000000', '2147483648'], False),
                    (['10000000000000000', '0'], False), (['-1', '0'], False),
                    (['40', '-64'], False), (['40'], False)]
        for values, accepted in variants:
            group = {'errors': [], 'primary': [{'type': 'RETURN', 'kind': 'total-size', 'values': values}]}
            with self.subTest(values=values):
                if accepted:
                    result = review.return_value(group)
                    self.assertEqual(int(values[1]), result['sourceIntValue'])
                    self.assertEqual(values[0], result['rawRegisterHex'])
                else:
                    with self.assertRaises(ValueError):
                        review.return_value(group)

    def test_nested_return_requires_exact_jfr_size_without_changing_original_binding_flags(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': values}]}
                  for sequence, category, kind, values in [(1, 'ENTRY', 'broker', []), (2, 'USDT', 'begin', []),
                       (3, 'ENTRY', 'post', []), (4, 'ENTRY', 'total-size', []),
                       (5, 'RETURN', 'total-size', ['40', '64']), (6, 'RETURN', 'post', []),
                       (7, 'USDT', 'end', []), (8, 'RETURN', 'broker', [])]]
        broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                  'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                  'posts': [{'entry': groups[2], 'return': groups[5]}],
                  'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
        baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                    'correlation': {'brokerInvocations': [broker], 'summary': {'recordedArgumentBindingsCorroborated': 0}},
                    'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                        'finalLossCounterZeroProven': True}}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        nmethod = {'pointer': 12288, 'fields': {'_compile_id': 7, '_comp_level': 3, '_entry_bci': -1, '_method': 8192}}
        for size, expected in [(64, True), (65, False)]:
            changed = copy.deepcopy(baseline)
            changed['correlation']['brokerInvocations'][0]['jfrMatches'][0]['raw']['values']['codeSize'] = size
            before = copy.deepcopy(changed)
            with self.subTest(size=size), \
                    patch.object(review.original, 'decode_candidate', side_effect=[task, task, nmethod, nmethod]):
                result = review.correlate(changed, {})

                self.assertEqual(expected, result['postAssessments'][0]['returnSizeMatchesJfr'])
                self.assertEqual(expected, result['callRecords'][0]['returnSizeMatchesJfr'])
                self.assertFalse(result['postAssessments'][0]['originalRecordedArgumentBindingCorroborated'])
                self.assertEqual(1 if expected else 0, result['summary']['returnSizeJfrMetadataMatches'])
                self.assertEqual(before, changed)

    def test_duplicate_size_calls_are_rejected_before_choosing_an_equal_return(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': values}]}
                  for sequence, category, kind, values in [(1, 'ENTRY', 'broker', []), (2, 'USDT', 'begin', []),
                       (3, 'ENTRY', 'post', []), (4, 'ENTRY', 'total-size', []),
                       (5, 'RETURN', 'total-size', ['40', '64']), (6, 'ENTRY', 'total-size', []),
                       (7, 'RETURN', 'total-size', ['41', '65']), (8, 'RETURN', 'post', []),
                       (9, 'USDT', 'end', []), (10, 'RETURN', 'broker', [])]]
        broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                  'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[8]],
                  'posts': [{'entry': groups[2], 'return': groups[7]}],
                  'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
        baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                    'correlation': {'brokerInvocations': [broker], 'summary': {}},
                    'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                        'finalLossCounterZeroProven': True}}

        with patch.object(review.original, 'decode_candidate') as decode:
            result = review.correlate(baseline, {})

        decode.assert_not_called()
        self.assertEqual([0, 1], result['postAssessments'][0]['callCandidates'])
        self.assertFalse(result['postAssessments'][0]['returnSizeMatchesJfr'])
        self.assertEqual(2, len(result['callRecords']))

    def test_missing_outside_post_and_unfinished_calls_remain_unproven(self):
        table = [(1, 'ENTRY', 'broker'), (2, 'USDT', 'begin'), (3, 'ENTRY', 'post'),
                 (4, 'ENTRY', 'total-size'), (5, 'RETURN', 'total-size'), (6, 'RETURN', 'post'),
                 (7, 'USDT', 'end'), (8, 'RETURN', 'broker')]
        for variant in ['missing', 'outside', 'unfinished']:
            groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                       'primary': [{'type': category, 'kind': kind, 'values': ['40', '64'] if sequence == 5 else []}]}
                      for sequence, category, kind in table]
            broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                      'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                      'posts': [{'entry': groups[2], 'return': groups[5]}],
                      'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
            if variant == 'missing':
                groups = groups[:3] + groups[5:]
            elif variant == 'outside':
                groups = [groups[i] for i in [0, 1, 3, 4, 2, 5, 6, 7]]
            else:
                groups = groups[:4]
            baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                        'correlation': {'brokerInvocations': [broker], 'summary': {}},
                        'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                            'finalLossCounterZeroProven': True}}
            with self.subTest(variant=variant), patch.object(review.original, 'decode_candidate') as decode:
                result = review.correlate(baseline, {})

                decode.assert_not_called()
                self.assertFalse(result['postAssessments'][0]['returnSizeMatchesJfr'])
                self.assertIn('unprovenReason', result['postAssessments'][0])

    def test_independent_success_and_size_must_be_unique_and_explicit(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': ['40', '64'] if sequence == 5 else []}]}
                  for sequence, category, kind in [(1, 'ENTRY', 'broker'), (2, 'USDT', 'begin'),
                       (3, 'ENTRY', 'post'), (4, 'ENTRY', 'total-size'), (5, 'RETURN', 'total-size'),
                       (6, 'RETURN', 'post'), (7, 'USDT', 'end'), (8, 'RETURN', 'broker')]]
        match = {'succeeded': True, 'raw': {'values': {'codeSize': 64}}}
        variants = [([], True), ([match, match], True), ([{'succeeded': False, 'raw': match['raw']}], True),
                    ([match], False), ([{'succeeded': True, 'raw': {'values': {}}}], True)]
        variants.extend(([{'succeeded': True, 'raw': {'values': {'codeSize': value}}}], True)
                        for value in [-1, 2 ** 31, True, '64'])
        for matches, independent in variants:
            broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': independent,
                      'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                      'posts': [{'entry': groups[2], 'return': groups[5]}], 'jfrMatches': matches}
            baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                        'correlation': {'brokerInvocations': [broker], 'summary': {}},
                        'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                            'finalLossCounterZeroProven': True}}
            with self.subTest(matches=matches, independent=independent), \
                    patch.object(review.original, 'decode_candidate') as decode:
                row = review.correlate(baseline, {})['postAssessments'][0]

                decode.assert_not_called()
                self.assertFalse(row['returnSizeMatchesJfr'])

    def test_native_task_and_receiver_identity_mismatches_remain_unproven(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': ['40', '64'] if sequence == 5 else []}]}
                  for sequence, category, kind in [(1, 'ENTRY', 'broker'), (2, 'USDT', 'begin'),
                       (3, 'ENTRY', 'post'), (4, 'ENTRY', 'total-size'), (5, 'RETURN', 'total-size'),
                       (6, 'RETURN', 'post'), (7, 'USDT', 'end'), (8, 'RETURN', 'broker')]]
        broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                  'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                  'posts': [{'entry': groups[2], 'return': groups[5]}],
                  'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
        baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                    'correlation': {'brokerInvocations': [broker], 'summary': {}},
                    'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                        'finalLossCounterZeroProven': True}}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        nmethod = {'pointer': 12288, 'fields': {'_compile_id': 7, '_comp_level': 3, '_entry_bci': -1, '_method': 8192}}
        for index, field, value in [(1, 'pointer', 4097), (1, '_method', 8193), (2, '_compile_id', 8),
                                     (2, '_comp_level', 2), (3, 'pointer', 12289), (3, '_method', 8193),
                                     (3, '_entry_bci', 14)]:
            candidates = [copy.deepcopy(row) for row in [task, task, nmethod, nmethod]]
            if field == 'pointer':
                candidates[index][field] = value
            else:
                candidates[index]['fields'][field] = value
            with self.subTest(index=index, field=field), \
                    patch.object(review.original, 'decode_candidate', side_effect=candidates):
                row = review.correlate(baseline, {})['postAssessments'][0]

                self.assertFalse(row['returnSizeMatchesJfr'])
                self.assertIn('metadata differ', row['unprovenReason'])

    def test_complete_public_and_post_sequence_nesting_is_required(self):
        table = [(1, 'ENTRY', 'broker'), (2, 'USDT', 'begin'), (3, 'ENTRY', 'post'),
                 (4, 'ENTRY', 'total-size'), (5, 'RETURN', 'total-size'), (6, 'RETURN', 'post'),
                 (7, 'USDT', 'end'), (8, 'RETURN', 'broker')]
        for variant in ['begin-after-call', 'end-before-call', 'post-after-call', 'missing-post-return', 'unfinished-broker']:
            groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                       'primary': [{'type': category, 'kind': kind, 'values': ['40', '64'] if sequence == 5 else []}]}
                      for sequence, category, kind in table]
            post = {'entry': groups[2], 'return': groups[5]}
            broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                      'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                      'posts': [post], 'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
            if variant == 'begin-after-call':
                groups[1]['sequence'] = 5
            elif variant == 'end-before-call':
                groups[6]['sequence'] = 4
            elif variant == 'post-after-call':
                groups[2]['sequence'] = 5
            elif variant == 'missing-post-return':
                del post['return']
            else:
                broker['completed'] = False
            baseline = {'nativeRecords': {'threads': [groups], 'errors': []},
                        'correlation': {'brokerInvocations': [broker], 'summary': {}},
                        'readDiagnostics': {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                                            'finalLossCounterZeroProven': True}}
            with self.subTest(variant=variant), patch.object(review.original, 'decode_candidate') as decode:
                row = review.correlate(baseline, {})['postAssessments'][0]

                decode.assert_not_called()
                self.assertFalse(row['returnSizeMatchesJfr'])

    def test_original_read_delivery_and_counter_guards_cannot_be_bypassed(self):
        groups = [{'pid': 100, 'tid': 101, 'sequence': sequence, 'errors': [],
                   'primary': [{'type': category, 'kind': kind, 'values': ['40', '64'] if sequence == 5 else []}]}
                  for sequence, category, kind in [(1, 'ENTRY', 'broker'), (2, 'USDT', 'begin'),
                       (3, 'ENTRY', 'post'), (4, 'ENTRY', 'total-size'), (5, 'RETURN', 'total-size'),
                       (6, 'RETURN', 'post'), (7, 'USDT', 'end'), (8, 'RETURN', 'broker')]]
        broker = {'entry': groups[0], 'completed': True, 'independentIdentityMatched': True,
                  'recordedArgumentBindingCorroborated': False, 'usdtBegin': [groups[1]], 'usdtEnd': [groups[6]],
                  'posts': [{'entry': groups[2], 'return': groups[5]}],
                  'jfrMatches': [{'succeeded': True, 'raw': {'values': {'codeSize': 64}}}]}
        for failed in ['readWarningGuardPassed', 'sequencedDeliveryGuardPassed', 'finalLossCounterZeroProven', 'parse']:
            diagnostics = {'readWarningGuardPassed': True, 'sequencedDeliveryGuardPassed': True,
                           'finalLossCounterZeroProven': True}
            if failed != 'parse':
                diagnostics[failed] = False
            baseline = {'nativeRecords': {'threads': [groups], 'errors': ['incomplete'] if failed == 'parse' else []},
                        'correlation': {'brokerInvocations': [broker], 'summary': {}}, 'readDiagnostics': diagnostics}
            with self.subTest(failed=failed), patch.object(review.original, 'decode_candidate') as decode:
                row = review.correlate(baseline, {})['postAssessments'][0]

                decode.assert_not_called()
                self.assertFalse(row['returnSizeMatchesJfr'])
                self.assertIn('guard failed', row['unprovenReason'])

    def test_baseline_and_log_review_are_retained_before_return_corroboration(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            (out / 'live').mkdir()
            (out / 'live/live-layout.json').write_text('{}')
            baseline = {'nativeRecords': {'threads': [], 'errors': []},
                        'correlation': {'brokerInvocations': [], 'summary': {'recordedArgumentBindingsCorroborated': 0}},
                        'readDiagnostics': {}, 'codeSizeProven': False}
            raw = (json.dumps(baseline) + '\n').encode()
            (out / 'original-argument-review.json').write_bytes(raw)
            report = {'originalReview': {'path': 'original-argument-review.json',
                                        'sha256': hashlib.sha256(raw).hexdigest()},
                      'logCorrelation': {'summary': {'logMetadataCorroborations': 1055}}, 'codeSizeProven': False}
            before = copy.deepcopy(report)

            with patch.object(review.log_review, 'inspect') as inspect:
                review.inspect(out, out / 'result.json', report)

            inspect.assert_called_once_with(out, out / 'result.json', report)
            self.assertEqual(raw, (out / 'original-argument-review.json').read_bytes())
            self.assertEqual(before['originalReview'], report['originalReview'])
            self.assertEqual(before['logCorrelation'], report['logCorrelation'])
            self.assertFalse(report['codeSizeProven'])

    def test_invalid_archive_identity_writes_a_failed_report_without_size_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            manifest = out / 'manifest.json'
            manifest.write_text('{}')
            output = out / 'result.json'
            arguments = ['review', str(manifest), '0' * 64, '/unread/archive', str(output)]
            with patch.object(review.sys, 'argv', arguments), self.assertRaises(ValueError):
                review.main()

            report = json.loads(output.read_text())
            for name in ['completed', 'actualFullCallingConventionProven', 'rootCensusProven',
                         'codeSizeProven', 'clockAlignmentProven', 'lossFreeCaptureProven', 'acceptedSavingsCredit']:
                self.assertFalse(report[name])


if __name__ == '__main__':
    unittest.main(verbosity=2)
