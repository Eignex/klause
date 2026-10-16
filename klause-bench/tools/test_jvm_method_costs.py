import copy
import unittest
from unittest.mock import patch

import jvm_method_costs as review


class JvmMethodCostsTest(unittest.TestCase):
    def test_direct_costs_retain_all_tiers_replacements_osr_failed_and_unknown_roots(self):
        roots = [{'eventIndex': i, 'id': i + 1, 'method': 'p.C.work()V', 'level': level,
                  'osr': osr, 'succeeded': success, 'reportedElapsedNanos': i + 1,
                  'reportedSuccessfulCodeBytes': 10 if success is True else None, 'errors': []}
                 for i, (level, osr, success) in enumerate([
                     (1, False, True), (4, True, True), (4, False, False), (None, None, None)])]

        row = review.observations({'compilations': roots}, 3)[0]

        self.assertEqual(3, row['recordingIndex'])
        self.assertEqual([0, 1, 2, 3], row['inclusiveRootEventIndices'])
        self.assertEqual(10, row['observedDirectMetrics']['reportedElapsedNanos'])
        self.assertEqual(5, row['observedInclusiveC2Metrics']['reportedElapsedNanos'])
        self.assertIsNone(row['observedInclusiveMetrics']['reportedSuccessfulCodeBytes'])
        self.assertEqual([1], row['observedInclusiveMetrics']['osrRootEventIndices'])
        self.assertEqual([2], row['observedInclusiveMetrics']['failedRootEventIndices'])
        self.assertEqual([3], row['observedInclusiveMetrics']['unknownOutcomeRootEventIndices'])

    def test_successful_outside_inlining_roots_are_deduplicated_per_method_and_shared_between_methods(self):
        roots = [{'eventIndex': i, 'id': i + 1, 'method': name, 'level': 4, 'osr': False,
                  'succeeded': True, 'reportedElapsedNanos': elapsed,
                  'reportedSuccessfulCodeBytes': 10, 'errors': []}
                 for i, (name, elapsed) in enumerate([('outside.C.call()V', 9), ('p.C.work()V', 7)])]
        inlining = [{'eventIndex': i + 2, 'id': identifier, 'callee': name,
                     'caller': 'outside.C.call()V', 'succeeded': True, 'errors': []}
                    for i, (identifier, name) in enumerate([
                        (1, 'p.C.work()V'), (1, 'p.C.work()V'), (2, 'p.C.work()V'), (1, 'p.D.part()V')])]
        report = {'compilations': roots, 'inlining': inlining}
        before = copy.deepcopy(report)

        rows = review.observations(report, 0)

        self.assertEqual(before, report)
        self.assertEqual(['outside.C.call()V', 'p.C.work()V', 'p.D.part()V'], [row['method'] for row in rows])
        self.assertEqual([0, 1], rows[1]['inclusiveRootEventIndices'])
        self.assertEqual([2, 3, 4], rows[1]['inliningReferenceEventIndices'])
        self.assertEqual(16, rows[1]['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertEqual(7, rows[1]['observedDirectMetrics']['reportedElapsedNanos'])
        self.assertEqual([0], rows[2]['inclusiveRootEventIndices'])
        self.assertEqual(9, rows[2]['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertFalse(rows[1]['acceptedSavingsCredit'])

    def test_failed_ambiguous_missing_and_malformed_inline_references_stay_unresolved_without_zero_costs(self):
        roots = [{'eventIndex': i, 'id': 1, 'method': 'outside.C.call()V', 'level': 4, 'osr': False,
                  'succeeded': True, 'reportedElapsedNanos': 1,
                  'reportedSuccessfulCodeBytes': 1, 'errors': []} for i in range(2)]
        inlining = [{'eventIndex': i + 2, 'id': identifier, 'callee': 'p.C.part()V',
                     'caller': 'outside.C.call()V', 'succeeded': success, 'errors': errors}
                    for i, (identifier, success, errors) in enumerate([
                        (1, True, []), (None, True, []), (9, True, ['bad identity']), (1, False, [])])]
        inlining[-1]['caller'] = 'p.OnlyCaller.work()V'

        rows = review.observations({'compilations': roots, 'inlining': inlining}, 0)
        row = rows[1]

        self.assertEqual([2, 3, 4, 5], row['inliningReferenceEventIndices'])
        self.assertEqual([2, 3, 4], row['unresolvedSuccessfulInliningEventIndices'])
        self.assertEqual([], row['inclusiveRootEventIndices'])
        self.assertEqual(0, row['observedInclusiveMetrics']['rootEventCount'])
        self.assertIsNone(row['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertEqual('p.OnlyCaller.work()V', rows[2]['method'])
        self.assertEqual([5], rows[2]['callerReferenceEventIndices'])
        self.assertEqual([], rows[2]['inclusiveRootEventIndices'])
        self.assertIsNone(rows[2]['observedInclusiveMetrics']['reportedElapsedNanos'])

    def test_unknown_method_and_erroneous_roots_remain_in_the_complete_observation_table(self):
        root = {'eventIndex': 0, 'id': None, 'method': None, 'level': None, 'osr': None,
                'succeeded': None, 'reportedElapsedNanos': None,
                'reportedSuccessfulCodeBytes': None, 'errors': ['missing method']}

        rows = review.observations({'compilations': [root]}, 0)

        self.assertEqual(1, len(rows))
        self.assertIsNone(rows[0]['method'])
        self.assertEqual([{'eventIndex': 0, 'errors': ['missing method']}], rows[0]['rootErrors'])
        self.assertIsNone(rows[0]['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertFalse(rows[0]['runtimeSourceBindingProven'])

    def test_method_observation_bounds_decline_the_complete_table_without_a_selected_prefix(self):
        report = {'compilations': [{'eventIndex': i, 'id': i + 1, 'method': 'p.C.m' + str(i)} for i in range(2)]}
        with patch.object(review, 'MAX_OBSERVATIONS', 1), self.assertRaisesRegex(ValueError, 'exceed bounds'):
            review.observations(report, 0)

    def test_method_summaries_aggregate_observed_recordings_keep_absence_and_take_the_largest_root_maximum(self):
        recordings = [{'arm': 'control', 'engine': 'cp', 'mode': 'profile', 'recipe': None}] * 3
        rows = []
        for i, elapsed in enumerate([7, 11]):
            rows.extend(review.observations({'compilations': [{'eventIndex': 0, 'id': 1,
                'method': 'p.C.work()V', 'level': 4, 'osr': False, 'succeeded': True,
                'reportedElapsedNanos': elapsed, 'reportedSuccessfulCodeBytes': 10, 'errors': []}]}, i))
        before = copy.deepcopy(rows)

        result = review.summaries(rows, recordings)[0]

        self.assertEqual(before, rows)
        self.assertEqual([0, 1], result['observationIndices'])
        self.assertEqual([0, 1], result['observedRecordingIndices'])
        self.assertEqual([2], result['recordingIndicesWithoutMethodObservation'])
        self.assertEqual(18, result['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertEqual(11, result['observedInclusiveMetrics']['maxReportedElapsedNanos'])
        self.assertEqual(20, result['observedInclusiveMetrics']['reportedSuccessfulCodeBytes'])
        self.assertFalse(result['fullSourcePhaseCoverageProven'])
        self.assertFalse(result['acceptedSavingsCredit'])

    def test_distinct_arms_engines_modes_and_recipes_are_never_aggregated_together(self):
        root = {'eventIndex': 0, 'id': 1, 'method': 'p.C.work()V', 'level': 4, 'osr': False,
                'succeeded': True, 'reportedElapsedNanos': 1, 'reportedSuccessfulCodeBytes': 1, 'errors': []}
        context = {'arm': 'control', 'engine': 'cp', 'mode': 'profile', 'recipe': None}
        for key, value in [('arm', 'candidate'), ('engine', 'ls'), ('mode', 'cold'), ('recipe', 'other')]:
            with self.subTest(key=key):
                rows = review.observations({'compilations': [root]}, 0) + review.observations({'compilations': [root]}, 1)

                result = review.summaries(rows, [context, dict(context, **{key: value})])

                self.assertEqual(2, len(result))
                self.assertTrue(all(row['observedInclusiveMetrics']['reportedElapsedNanos'] == 1 for row in result))
                self.assertTrue(all(not row['recordingIndicesWithoutMethodObservation'] for row in result))

    def test_missing_quantities_are_unavailable_and_duplicate_or_invalid_recording_references_are_rejected(self):
        root = {'eventIndex': 0, 'id': 1, 'method': 'p.C.work()V', 'level': 4, 'osr': False,
                'succeeded': False, 'reportedElapsedNanos': None,
                'reportedSuccessfulCodeBytes': None, 'errors': ['missing duration']}
        rows = review.observations({'compilations': [root]}, 0)

        result = review.summaries(rows, [{}])[0]

        self.assertIsNone(result['observedInclusiveMetrics']['reportedElapsedNanos'])
        self.assertIsNone(result['observedInclusiveMetrics']['reportedSuccessfulCodeBytes'])
        with self.assertRaisesRegex(ValueError, 'Repeated method'):
            review.summaries(rows + rows, [{}])
        for index in (-1, 1, True):
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'no original recording'):
                review.summaries([dict(rows[0], recordingIndex=index)], [{}])


if __name__ == '__main__':
    unittest.main(verbosity=2)
