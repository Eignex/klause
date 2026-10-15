import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_log_identities as review


class ReviewNativeVmLogIdentitiesTest(unittest.TestCase):
    def test_task_ownership_and_completion_keep_their_original_xml_context(self):
        text = ("<hotspot_log process='100'><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1'/>"
                "<compilation_log thread='101'><start_compile_thread thread='101' process='100' name='C1 CompilerThread0'/>"
                "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1' nmsize='999'/></task>"
                "<fragment>partial raw tail</fragment></compilation_log></hotspot_log>")

        records = review.parse_log(text)

        self.assertEqual([], records['errors'])
        self.assertEqual(101, records['logs'][0]['thread'])
        self.assertEqual(100, records['logs'][0]['startMarkers'][0]['process'])
        self.assertEqual(0, records['tasks'][0]['ownerLogIndex'])
        self.assertEqual(1, records['tasks'][0]['taskDone'][0]['success'])
        self.assertEqual('999', records['tasks'][0]['taskDone'][0]['rawAttributes']['nmsize'])
        self.assertIsNone(records['nmethods'][0]['ownerLogIndex'])
        self.assertEqual('partial raw tail', records['fragments'][0]['text'])

    def test_missing_optional_fields_and_native_wrapper_levels_remain_unknown(self):
        records = review.parse_log(
            "<hotspot_log><nmethod compile_id='0' method='p.C nativeCall ()V' level='0' compiler='none'/>"
            "<task_queued compile_id='8'/><compilation_log thread='101'>"
            "<task compile_id='7' method='p.C f ()V'><task_done success='1'/></task>"
            "</compilation_log></hotspot_log>")

        self.assertEqual([], records['errors'])
        self.assertEqual(0, records['nmethods'][0]['level'])
        self.assertIsNone(records['tasks'][0]['level'])
        self.assertIsNone(records['tasks'][0]['osrBci'])
        self.assertIsNone(records['queued'][0]['method'])

    def test_log_metadata_corroboration_does_not_change_original_jfr_or_binding_results(self):
        text = ("<hotspot_log><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='C1'/>"
                "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
                "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1'/></task>"
                "</compilation_log></hotspot_log>")
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'unprovenReason': 'Independent JFR identity is absent or ambiguous',
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}],
                 'posts': [{'entry': {'sequence': 3}, 'return': {'sequence': 4}}]}
        before = copy.deepcopy(frame)
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        nmethod = {'pointer': 12288, 'fields': {'_compile_id': 7, '_comp_level': 3, '_entry_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}

        with patch.object(review.original, 'decode_identity', return_value=identity), \
                patch.object(review.original, 'decode_candidate', side_effect=lambda group, role, owner, layout:
                             nmethod if owner == 'nmethod' else task):
            result = review.correlate([frame], {'invocationEntryBci': {'value': -1}}, review.parse_log(text))

        self.assertTrue(result['brokerRecords'][0]['logMetadataCorroborated'])
        self.assertTrue(result['brokerRecords'][0]['threadTaskMetadataMatched'])
        self.assertFalse(result['brokerRecords'][0]['originalIndependentIdentityMatched'])
        self.assertFalse(result['brokerRecords'][0]['originalRecordedArgumentBindingCorroborated'])
        self.assertEqual(0, result['summary']['originalRecordedArgumentBindingsCorroborated'])
        self.assertEqual(before, frame)

    def test_thread_process_method_level_and_outcome_mismatches_remain_unproven(self):
        text = ("<hotspot_log><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1'/>"
                "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
                "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1'/></task>"
                "</compilation_log></hotspot_log>")
        variants = [text.replace("compilation_log thread='101'", "compilation_log thread='102'"),
                    text.replace("process='100'", "process='102'"),
                    text.replace("start_compile_thread thread='101'", "start_compile_thread thread='102'"),
                    text.replace("method='p.C f ()V'", "method='p.C other ()V'"),
                    text.replace(" level='3'", ''), text.replace("success='1'", "success='0'"),
                    text.replace("<task_done success='1'/>", ''),
                    text.replace("<start_compile_thread thread='101' process='100'/>", '')]
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}],
                 'posts': [{'entry': {'sequence': 3}, 'return': {'sequence': 4}}]}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}
        for changed in variants:
            with self.subTest(log=changed), patch.object(review.original, 'decode_identity', return_value=identity), \
                    patch.object(review.original, 'decode_candidate', return_value=task):
                row = review.correlate([frame], {'invocationEntryBci': {'value': -1}},
                                       review.parse_log(changed))['brokerRecords'][0]

                self.assertFalse(row['logMetadataCorroborated'])
                self.assertFalse(row['originalRecordedArgumentBindingCorroborated'])
                self.assertIn('unprovenReason', row)

    def test_duplicate_or_contradictory_records_cannot_be_resolved_by_choosing_an_outcome(self):
        artifact = "<nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1'/>"
        task_xml = "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1'/></task>"
        prefix = "<hotspot_log>" + artifact + "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
        suffix = '</compilation_log></hotspot_log>'
        variants = [prefix + task_xml * 2 + suffix,
                    prefix + task_xml + task_xml.replace("success='1'", "success='0'") + suffix,
                    prefix + task_xml.replace("<task_done success='1'/>", "<task_done success='1'/><task_done success='0'/>") + suffix,
                    (prefix + task_xml + suffix).replace(artifact, artifact * 2),
                    (prefix + task_xml + suffix).replace(artifact, artifact + artifact.replace("compiler='c1'", "compiler='c2'"))]
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}],
                 'posts': [{'entry': {'sequence': 3}, 'return': {'sequence': 4}}]}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}
        for text in variants:
            with self.subTest(log=text), patch.object(review.original, 'decode_identity', return_value=identity), \
                    patch.object(review.original, 'decode_candidate', return_value=task):
                row = review.correlate([frame], {'invocationEntryBci': {'value': -1}},
                                       review.parse_log(text))['brokerRecords'][0]

                self.assertFalse(row['logMetadataCorroborated'])
                self.assertTrue(row['taskCandidates'])

    def test_osr_requires_the_exact_task_bci_and_preserves_unrecorded_publication_bci(self):
        text = ("<hotspot_log><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1' compile_kind='osr'/>"
                "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
                "<task compile_id='7' method='p.C f ()V' level='3' compile_kind='osr' osr_bci='14'>"
                "<task_done success='1'/></task></compilation_log></hotspot_log>")
        variants = [(text, True), (text.replace("osr_bci='14'", "osr_bci='15'"), False),
                    (text.replace(" osr_bci='14'", ''), False),
                    (text.replace("compiler='c1'", "compiler='c1' osr_bci='15'"), False)]
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}],
                 'posts': [{'entry': {'sequence': 3}, 'return': {'sequence': 4}}]}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': 14, '_method': 8192}}
        nmethod = {'pointer': 12288, 'fields': {'_compile_id': 7, '_comp_level': 3, '_entry_bci': 14, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}
        for changed, expected in variants:
            with self.subTest(log=changed), patch.object(review.original, 'decode_identity', return_value=identity), \
                    patch.object(review.original, 'decode_candidate', side_effect=lambda group, role, owner, layout:
                                 nmethod if owner == 'nmethod' else task):
                row = review.correlate([frame], {'invocationEntryBci': {'value': -1}},
                                       review.parse_log(changed))['brokerRecords'][0]

                self.assertEqual(expected, row['logMetadataCorroborated'])
                if expected:
                    self.assertIsNone(row['nmethodCandidates'][0]['recordedOsrBci'])

    def test_failed_compilation_keeps_explicit_task_outcome_without_publication_credit(self):
        logs = review.parse_log(
            "<hotspot_log><compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='p.C f ()V' level='3'><failure reason='declined'/>"
            "<task_done success='0'/></task></compilation_log></hotspot_log>")
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['0']}]}], 'posts': []}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}

        with patch.object(review.original, 'decode_identity', return_value=identity), \
                patch.object(review.original, 'decode_candidate', return_value=task):
            row = review.correlate([frame], {'invocationEntryBci': {'value': -1}}, logs)['brokerRecords'][0]

        self.assertTrue(row['threadTaskMetadataMatched'])
        self.assertFalse(row['compilerMetadataMatched'])
        self.assertFalse(row['logMetadataCorroborated'])
        self.assertEqual(0, row['publicSuccess'])

    def test_missing_or_unfinished_post_callbacks_remain_unproven(self):
        logs = review.parse_log(
            "<hotspot_log><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1'/>"
            "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1'/></task>"
            "</compilation_log></hotspot_log>")
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}
        for posts in [[], [{'entry': {'sequence': 3}}], [{'entry': {'sequence': 3}, 'return': {'sequence': 6}}]]:
            frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                     'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                     'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}], 'posts': posts}
            with self.subTest(posts=posts), patch.object(review.original, 'decode_identity', return_value=identity), \
                    patch.object(review.original, 'decode_candidate', return_value=task):
                row = review.correlate([frame], {'invocationEntryBci': {'value': -1}}, logs)['brokerRecords'][0]

                self.assertTrue(row['threadTaskMetadataMatched'])
                self.assertTrue(row['compilerMetadataMatched'])
                self.assertFalse(row['postMetadataConsistent'])
                self.assertFalse(row['logMetadataCorroborated'])

    def test_post_receiver_and_task_keys_must_equal_the_original_broker_task(self):
        logs = review.parse_log(
            "<hotspot_log><nmethod compile_id='7' method='p.C f ()V' level='3' compiler='c1'/>"
            "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='p.C f ()V' level='3'><task_done success='1'/></task>"
            "</compilation_log></hotspot_log>")
        frame = {'completed': True, 'independentIdentityMatched': False, 'recordedArgumentBindingCorroborated': False,
                 'entry': {'pid': 100, 'tid': 101, 'sequence': 1, 'errors': []},
                 'usdtBegin': [{'sequence': 2}], 'usdtEnd': [{'sequence': 5, 'primary': [{'values': ['1']}]}],
                 'posts': [{'entry': {'sequence': 3}, 'return': {'sequence': 4}}]}
        task = {'pointer': 4096, 'fields': {'_compile_id': 7, '_comp_level': 3, '_osr_bci': -1, '_method': 8192}}
        nmethod = {'pointer': 12288, 'fields': {'_compile_id': 7, '_comp_level': 3, '_entry_bci': -1, '_method': 8192}}
        identity = {'compiler': 'c1', 'class': 'p/C', 'method': 'f', 'signature': '()V'}
        variants = [('task', 'pointer', 4097), ('task', '_method', 8193), ('task', '_compile_id', 8),
                    ('task', '_comp_level', 2), ('task', '_osr_bci', 14), ('nmethod', '_method', 8193),
                    ('nmethod', '_compile_id', 8), ('nmethod', '_comp_level', 2), ('nmethod', '_entry_bci', 14)]
        for owner, field, value in variants:
            post_task, receiver = copy.deepcopy(task), copy.deepcopy(nmethod)
            target = post_task if owner == 'task' else receiver
            if field == 'pointer':
                target[field] = value
            else:
                target['fields'][field] = value
            with self.subTest(owner=owner, field=field), \
                    patch.object(review.original, 'decode_identity', return_value=identity), \
                    patch.object(review.original, 'decode_candidate', side_effect=[task, post_task, receiver]):
                row = review.correlate([frame], {'invocationEntryBci': {'value': -1}}, logs)['brokerRecords'][0]

                self.assertTrue(row['compilerMetadataMatched'])
                self.assertFalse(row['postMetadataConsistent'])
                self.assertFalse(row['logMetadataCorroborated'])

    def test_incomplete_native_lifetimes_are_retained_before_log_matching(self):
        frame = {'completed': False, 'independentIdentityMatched': False,
                 'recordedArgumentBindingCorroborated': False, 'usdtBegin': [], 'usdtEnd': []}
        with patch.object(review.original, 'decode_candidate') as decode:
            result = review.correlate([frame], {'invocationEntryBci': {'value': -1}}, review.parse_log('<hotspot_log/>'))

        decode.assert_not_called()
        self.assertFalse(result['brokerRecords'][0]['logMetadataCorroborated'])
        self.assertIn('incomplete', result['brokerRecords'][0]['unprovenReason'])

    def test_original_review_is_saved_unchanged_before_complementary_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            (out / 'live').mkdir()
            (out / 'live/live-layout.json').write_text(json.dumps({'invocationEntryBci': {'value': -1}}))
            (out / 'live/hotspot.xml').write_text('<hotspot_log/>')
            baseline = {'completed': True, 'correlation': {'summary': {'recordedArgumentBindingsCorroborated': 0},
                        'brokerInvocations': [], 'nativeLifetimeIssues': [{'reason': 'unfinished native invocation'}]},
                        'readDiagnostics': {'readWarningGuardPassed': True},
                        'terminalDelivery': {'sequencedNativeDeliveryProven': True},
                        'eventLossCounter': {'finalLossCounterZeroProven': True, 'globalLossFreeCaptureProven': False}}
            report = {}

            with patch.object(review.original, 'inspect', side_effect=lambda path, result: result.update(copy.deepcopy(baseline))):
                review.inspect(out, out / 'result.json', report)

            retained = json.loads((out / 'original-argument-review.json').read_text())
            self.assertEqual(baseline['correlation'], retained['correlation'])
            self.assertFalse(retained['actualFullCallingConventionProven'])
            self.assertFalse(retained['rootCensusProven'])
            self.assertFalse(retained['lossFreeCaptureProven'])
            self.assertEqual(baseline['correlation']['summary'], report['originalReview']['summaryCopiedVerbatim'])

    def test_invalid_archive_identity_retains_a_failed_report_without_claims(self):
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
                         'codeSizeProven', 'clockAlignmentProven', 'lossFreeCaptureProven']:
                self.assertFalse(report[name])


if __name__ == '__main__':
    unittest.main(verbosity=2)
