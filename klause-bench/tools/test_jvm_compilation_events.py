import io
import json
import unittest
from unittest.mock import patch

import jvm_compilation_events as review


class JvmCompilationEventsTest(unittest.TestCase):
    def test_complete_jfr_events_stream_across_utf8_and_json_chunk_boundaries(self):
        values = [{'type': 'jdk.Compilation', 'values': {'method': 'snowman \u2603', 'duration': 'PT0.000000001S'}},
                  {'type': 'jdk.CompilerPhase', 'values': {'phase': 'retained'}},
                  {'type': 'future.Event', 'values': {'unknown': [1, True, None]}}]
        raw = json.dumps({'recording': {'events': values}}, ensure_ascii=False).encode()
        for chunk in (1, 7, 65536):
            with self.subTest(chunk=chunk), patch.object(review, 'CHUNK_BYTES', chunk):
                result = list(review.JsonEvents(io.BytesIO(raw)))

            self.assertEqual(list(enumerate(values)), result)

    def test_empty_recording_has_no_event_or_zero_cost_substitute(self):
        raw = b'{"recording":{"events":[]}}'

        parsed = list(review.JsonEvents(io.BytesIO(raw)))
        result = review.Census().finish({}, None, {})

        self.assertEqual([], parsed)
        self.assertIsNone(result['observedAllClassMetrics']['reportedElapsedNanos'])
        self.assertIsNone(result['observedAllClassMetrics']['reportedSuccessfulCodeBytes'])
        self.assertFalse(result['acceptedSavingsCredit'])

    def test_malformed_duplicate_truncated_and_trailing_json_are_not_completed(self):
        variants = [b'', b'{"recording":{"events":[', b'{"recording":{"events":[],"events":[]}}}',
                    b'{"recording":{"events":[]}}{}', b'{"events":[]}',
                    b'{"recording":{"events":[{"type":"a","values":{},"values":{}}]}}',
                    b'{"recording":{"events":[{"type":"a","values":{"x":NaN}}]}}',
                    b'{"recording":{"events":[{"type":"a","values":{"x":1e999}}]}}',
                    b'{"recording":{"events":[null]}}', b'{"recording":{"events":[],}}',
                    b'{"recording":{"events":[{"type":"a","values":{}}',
                    b'{"recording":{"events":[{"type":"a","values":{},}]}}']
        for raw in variants:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                list(review.JsonEvents(io.BytesIO(raw)))

    def test_invalid_utf8_is_not_replaced_during_recording_decode(self):
        for raw in (b'{"recording":{"events":[]}}\xff', b'{"recording":{"events":[]}}\xe2\x98'):
            with self.subTest(raw=raw), self.assertRaises(UnicodeError):
                list(review.JsonEvents(io.BytesIO(raw)))

    def test_complete_event_export_and_record_limits_decline_without_a_subset(self):
        raw = json.dumps({'recording': {'events': [{'type': 'a', 'values': {'value': 'x' * 300}},
                                                 {'type': 'b', 'values': {}}]}}).encode()
        for field, limit in [('MAX_EXPORT_BYTES', 10), ('MAX_EVENT_BYTES', 100), ('MAX_EVENTS', 1)]:
            with self.subTest(field=field), patch.object(review, field, limit), self.assertRaises(ValueError):
                list(review.JsonEvents(io.BytesIO(raw)))

    def test_event_bound_applies_to_one_record_rather_than_the_whole_read_buffer(self):
        values = [{'type': 'a', 'values': {}} for _ in range(20)]
        raw = json.dumps({'recording': {'events': values}}).encode()

        with patch.object(review, 'MAX_EVENT_BYTES', 64):
            result = list(review.JsonEvents(io.BytesIO(raw)))

        self.assertEqual(list(enumerate(values)), result)

    def test_jfr_duration_units_preserve_exact_integer_nanoseconds(self):
        variants = [('PT0S', 0), ('PT0.000000001S', 1), ('PT1H2M3.000000007S', 3723000000007),
                    ('PT9.123456789S', 9123456789), ('PT0.123000000000S', 123000000),
                    ('PT123456789012345H', 444444440444442000000000000)]
        for text, expected in variants:
            with self.subTest(text=text):
                self.assertEqual(expected, review.duration_nanos(text))

    def test_numeric_unknown_negative_and_subnanosecond_durations_are_unavailable(self):
        for value in (None, 0, True, 1.5, 'PT', 'P1D', '-PT1S', 'PT-1S', 'PT0.0000000001S', '1ms'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                review.duration_nanos(value)

    def test_recorded_class_and_callee_string_owners_preserve_descriptors(self):
        for owner in ('p/C', {'name': 'p/C'}):
            with self.subTest(owner=owner):
                result = review.method({'type': owner, 'name': 'f', 'descriptor': '(Ljava/lang/String;)V'})

            self.assertEqual('p.C.f(Ljava/lang/String;)V', result)
        self.assertEqual('p.C.f(Ljava/lang/String;)V', review.xml_method('p/C f (Ljava/lang/String;)V'))
        self.assertIsNone(review.method({'type': 'p/C', 'name': 'f'}))

    def test_all_tiers_osr_failures_and_replacement_root_events_remain_in_the_census(self):
        census = review.Census()
        method = {'type': {'name': 'p/C'}, 'name': 'work', 'descriptor': '()V'}
        for ordinal, level, osr, succeeded in [(0, 1, False, True), (1, 4, False, True),
                                               (2, 3, True, True), (3, 7, False, False)]:
            census.add(ordinal, {'type': 'jdk.Compilation', 'values': {'compileId': ordinal + 1,
                'method': method, 'compileLevel': level, 'compiler': 'c1' if level < 4 else 'c2',
                'isOsr': osr, 'succeded': succeeded, 'duration': 'PT0.000000003S', 'codeSize': 11}})

        result = census.finish({'f': {'methods': ['p.C.work']}}, 'control', {})

        self.assertEqual(4, result['observedAllClassMetrics']['rootEventCount'])
        self.assertEqual(12, result['observedAllClassMetrics']['reportedElapsedNanos'])
        self.assertIsNone(result['observedAllClassMetrics']['reportedSuccessfulCodeBytes'])
        self.assertEqual([2], result['observedAllClassMetrics']['osrRootEventIndices'])
        self.assertEqual([3], result['observedAllClassMetrics']['failedRootEventIndices'])
        self.assertEqual({'1', '3', '4', '7'}, set(result['observedTierMetrics']))
        self.assertEqual(3, result['families']['f']['observedC2Metrics']['reportedElapsedNanos'])
        self.assertFalse(result['rootCensusProven'])

    def test_shared_successful_c1_inlining_roots_are_counted_once_within_each_family(self):
        census = review.Census()
        caller = {'type': 'outside/Caller', 'name': 'whole', 'descriptor': '()V'}
        census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8, 'method': caller,
            'compileLevel': 1, 'isOsr': False, 'succeeded': True, 'duration': 'PT0.000000007S', 'codeSize': 19}})
        for ordinal, name in [(1, 'left'), (2, 'left'), (3, 'right')]:
            census.add(ordinal, {'type': 'jdk.CompilerInlining', 'values': {'compileId': 8, 'caller': caller,
                'callee': {'type': 'p/C', 'name': name, 'descriptor': '()V'}, 'succeeded': True}})

        result = census.finish({'a': {'methods': ['p.C.left']}, 'b': {'methods': ['p.C.right']}}, 'control', {})

        for family in ('a', 'b'):
            self.assertEqual([0], result['families'][family]['inclusiveRootEventIndices'])
            self.assertEqual(7, result['families'][family]['observedAllTierMetrics']['reportedElapsedNanos'])
            self.assertEqual([], result['families'][family]['directRootEventIndices'])
            self.assertIsNone(result['families'][family]['observedC2Metrics']['reportedElapsedNanos'])
        self.assertEqual(1, len(result['compilations']))
        self.assertEqual(3, len(result['inlining']))

    def test_ambiguous_and_missing_root_ids_never_resolve_successful_inlining(self):
        census = review.Census()
        caller = {'type': 'outside/Caller', 'name': 'f', 'descriptor': '()V'}
        for ordinal in (0, 1):
            census.add(ordinal, {'type': 'jdk.Compilation', 'values': {'compileId': 9, 'method': caller,
                'compileLevel': 4, 'isOsr': False, 'succeeded': True, 'duration': 'PT1S', 'codeSize': 1}})
        for ordinal, identifier in [(2, 9), (3, 10)]:
            census.add(ordinal, {'type': 'jdk.CompilerInlining', 'values': {'compileId': identifier, 'caller': caller,
                'callee': {'type': 'p/C', 'name': 'work', 'descriptor': '()V'}, 'succeeded': True}})

        result = census.finish({'f': {'methods': ['p.C.work']}}, None, {})

        self.assertEqual({'9': [0, 1]}, result['ambiguousCompileIds'])
        self.assertEqual([2, 3], result['unresolvedSuccessfulInliningEventIndices'])
        self.assertEqual([], result['families']['f']['inclusiveRootEventIndices'])
        self.assertIsNone(result['families']['f']['observedAllTierMetrics']['reportedElapsedNanos'])

    def test_failed_inlining_references_are_retained_without_inventing_embedded_work(self):
        census = review.Census()
        census.add(3, {'type': 'jdk.CompilerInlining', 'values': {'compileId': 8,
            'caller': {'type': 'outside/C', 'name': 'f', 'descriptor': '()V'},
            'callee': {'type': 'p/C', 'name': 'work', 'descriptor': '()V'}, 'succeeded': False}})

        result = census.finish({'f': {'methods': ['p.C.work']}}, None, {})

        self.assertEqual([3], result['families']['f']['inliningReferenceEventIndices'])
        self.assertEqual([], result['families']['f']['inclusiveRootEventIndices'])
        self.assertFalse(result['inlining'][0]['succeeded'])

    def test_missing_inlining_caller_does_not_resolve_against_an_existing_root(self):
        census = review.Census()
        census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8,
            'method': {'type': 'outside/C', 'name': 'f', 'descriptor': '()V'}, 'compileLevel': 1,
            'isOsr': False, 'succeeded': True, 'duration': 'PT1S', 'codeSize': 1}})
        census.add(1, {'type': 'jdk.CompilerInlining', 'values': {'compileId': 8,
            'callee': {'type': 'p/C', 'name': 'work', 'descriptor': '()V'}, 'succeeded': True}})

        result = census.finish({'f': {'methods': ['p.C.work']}}, None, {})

        self.assertEqual([1], result['unresolvedSuccessfulInliningEventIndices'])
        self.assertEqual([], result['families']['f']['inclusiveRootEventIndices'])

    def test_c1_and_unknown_tier_xml_queues_and_installations_remain_unmeasured(self):
        snapshots = {key: [{'compileId': number, 'rawAttributes': {'method': 'p/C work ()V', 'level': level}}]
                     for key, number, level in [('queued', 1, '1'), ('tasks', 2, '3'), ('nmethods', 3, '?')]}

        result = review.Census().finish({'f': {'methods': ['p.C.work']}}, None, snapshots)

        self.assertEqual([{'collection': 'queued', 'index': 0}, {'collection': 'tasks', 'index': 0},
                          {'collection': 'nmethods', 'index': 0}],
                         result['families']['f']['unmeasuredAllTierXmlSnapshotReferences'])

    def test_xml_method_tier_and_osr_conflicts_are_not_bound_by_compile_id_alone(self):
        census = review.Census()
        census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8,
            'method': {'type': 'p/C', 'name': 'work', 'descriptor': '()V'}, 'compileLevel': 4,
            'isOsr': False, 'succeeded': True, 'duration': 'PT1S', 'codeSize': 1}})
        for changes in ({'method': 'p/C other ()V'}, {'level': 1}, {'compileKind': 'osr'}):
            snapshot = {'compileId': 8, 'level': 4, 'rawAttributes': {'method': 'p/C work ()V'}}
            if 'method' in changes:
                snapshot['rawAttributes']['method'] = changes['method']
            else:
                snapshot.update(changes)

            with self.subTest(changes=changes):
                result = census.finish({'f': {'methods': ['p.C.work']}}, None, {'tasks': [snapshot]})

            self.assertEqual([{'collection': 'tasks', 'index': 0}], result['families']['f']['conflictingXmlSnapshotReferences'])

    def test_required_ordinary_phases_retain_partial_all_tier_coverage(self):
        census = review.Census()
        census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8,
            'method': {'type': 'p/C', 'name': 'left', 'descriptor': '()V'}, 'compileLevel': 1,
            'isOsr': False, 'succeeded': True, 'duration': 'PT1S', 'codeSize': 1}})
        definition = {'methods': ['p.C.left', 'p.C.right'], 'ordinaryPhase': {'control': ['p.C.left', 'p.C.right']},
                      'ordinaryPhaseAll': {'control': ['p.C.left', 'p.C.right']}}

        result = census.finish({'f': definition}, 'control', {})['families']['f']

        self.assertEqual({'p.C.left': [0], 'p.C.right': []}, result['ordinaryPhaseMethodRootEventIndices'])
        self.assertEqual(['p.C.right'], result['missingRequiredOrdinaryPhases'])
        self.assertFalse(result['fullSourcePhaseCoverageProven'])
        self.assertFalse(result['acceptedSavingsCredit'])

    def test_descriptor_phases_keep_unobserved_overloads_missing_for_direct_and_inlined_work(self):
        for inlined in (False, True):
            for arm in ('control', 'candidate'):
                with self.subTest(inlined=inlined, arm=arm):
                    census = review.Census()
                    observed = {'type': 'p/C', 'name': 'work', 'descriptor': '(I)V'}
                    root = {'type': 'outside/Caller', 'name': 'whole', 'descriptor': '()V'} if inlined else observed
                    census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8,
                        'method': root, 'compileLevel': 1, 'isOsr': False, 'succeeded': True,
                        'duration': 'PT1S', 'codeSize': 1}})
                    if inlined:
                        census.add(1, {'type': 'jdk.CompilerInlining', 'values': {'compileId': 8,
                            'caller': root, 'callee': observed, 'succeeded': True}})
                    phases = ['p.C.work', 'p.C.work(I)V', 'p.C.work()V', 'p.C.work(I)I']
                    definition = {'methods': ['p.C.work'], 'ordinaryPhase': {arm: phases},
                                  'ordinaryPhaseAll': {arm: phases}}

                    result = census.finish({'f': definition}, arm, {})['families']['f']

                    self.assertEqual({'p.C.work': [0], 'p.C.work(I)V': [0],
                                      'p.C.work()V': [], 'p.C.work(I)I': []},
                                     result['ordinaryPhaseMethodRootEventIndices'])
                    self.assertEqual(['p.C.work()V', 'p.C.work(I)I'], result['missingRequiredOrdinaryPhases'])
                    self.assertEqual([0], result['inclusiveRootEventIndices'])
                    self.assertFalse(result['fullSourcePhaseCoverageProven'])
                    self.assertFalse(result['acceptedSavingsCredit'])

    def test_descriptor_phases_exclude_failed_and_osr_roots_for_direct_and_inlined_work(self):
        for inlined in (False, True):
            for osr, succeeded in ((True, True), (False, False)):
                with self.subTest(inlined=inlined, osr=osr, succeeded=succeeded):
                    census = review.Census()
                    observed = {'type': 'p/C', 'name': 'work', 'descriptor': '(I)V'}
                    root = {'type': 'outside/Caller', 'name': 'whole', 'descriptor': '()V'} if inlined else observed
                    census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': 8,
                        'method': root, 'compileLevel': 4, 'isOsr': osr, 'succeeded': succeeded,
                        'duration': 'PT1S', 'codeSize': 1}})
                    if inlined:
                        census.add(1, {'type': 'jdk.CompilerInlining', 'values': {'compileId': 8,
                            'caller': root, 'callee': observed, 'succeeded': True}})
                    definition = {'methods': ['p.C.work'], 'ordinaryPhase': {'control': ['p.C.work(I)V']},
                                  'ordinaryPhaseAll': {'control': ['p.C.work(I)V']}}

                    result = census.finish({'f': definition}, 'control', {})['families']['f']

                    self.assertEqual({'p.C.work(I)V': []}, result['ordinaryPhaseMethodRootEventIndices'])
                    self.assertEqual(['p.C.work(I)V'], result['missingRequiredOrdinaryPhases'])
                    self.assertEqual([0], result['inclusiveRootEventIndices'])

    def test_missing_units_code_size_and_conflicting_success_fields_remain_unknown(self):
        census = review.Census()
        census.add(0, {'type': 'jdk.Compilation', 'values': {'compileId': True, 'compileLevel': True,
            'isOsr': False, 'succeeded': True, 'succeded': False, 'duration': 10}})

        result = census.finish({}, None, {})

        self.assertIsNone(result['compilations'][0]['id'])
        self.assertIsNone(result['compilations'][0]['succeeded'])
        self.assertIsNone(result['compilations'][0]['level'])
        self.assertIsNone(result['observedAllClassMetrics']['reportedElapsedNanos'])
        self.assertIsNone(result['observedAllClassMetrics']['reportedSuccessfulCodeBytes'])
        self.assertTrue(result['compilations'][0]['errors'])

    def test_unknown_events_loss_settings_and_nested_phases_are_retained_as_export_references(self):
        census = review.Census()
        for ordinal, kind in enumerate(('jdk.DataLoss', 'jdk.ActiveSetting', 'jdk.CompilerPhase', 'future.Event')):
            census.add(ordinal, {'type': kind, 'values': {}})

        result = census.finish({}, None, {})

        self.assertEqual({'jdk.DataLoss': 1, 'jdk.ActiveSetting': 1, 'jdk.CompilerPhase': 1, 'future.Event': 1},
                         result['eventCounts'])
        self.assertIsNone(result['observedAllClassMetrics']['reportedElapsedNanos'])

    def test_retained_root_budget_rejects_instead_of_accepting_a_family_subset(self):
        census = review.Census()
        event = {'type': 'jdk.CompilerInlining', 'values': {}}

        with patch.object(review, 'MAX_ROOTS', 1):
            census.add(0, event)
            with self.assertRaisesRegex(ValueError, 'retained root'):
                census.add(1, event)


if __name__ == '__main__':
    unittest.main(verbosity=2)
