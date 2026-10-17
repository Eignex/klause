import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import jvm_recording_context as review


class JvmRecordingContextTest(unittest.TestCase):
    def test_original_context_keeps_unknown_fields_and_export_event_indices_without_process_role_proof(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000001.json.gz'
            values = {'pid': 17, 'javaArguments': 'app.Main --version', 'jvmArguments': '-Xmx3g',
                      'jvmStartTime': 'original-time', 'unknown': {'text': 'å', 'values': [True, None]}}
            raw = json.dumps({'recording': {'events': [{'type': 'jdk.ActiveSetting', 'values': {}},
                {'type': 'jdk.JVMInformation', 'values': values}]}}, ensure_ascii=False).encode()
            path.write_bytes(gzip.compress(raw))
            report = {'completed': True, 'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                      'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()},
                      'eventCounts': {'jdk.ActiveSetting': 1, 'jdk.JVMInformation': 1}}
            before, represented = copy.deepcopy(report), set()

            result = review.inspect(report, {path.name: path}, represented)

            self.assertEqual(before, report)
            self.assertEqual({path.name}, represented)
            self.assertEqual([{'eventIndex': 1, 'values': values}], result['jvmInformationEvents'])
            self.assertTrue(result['completeSavedExportRead'])
            self.assertTrue(result['censusEventCountsMatch'])
            self.assertEqual([], result['exclusions'])
            for flag in ('targetProcessRoleProven', 'completeProcessLifetimeProven',
                         'runtimeSourceBindingProven', 'acceptedSavingsCredit'):
                self.assertFalse(result[flag])

    def test_missing_export_identity_or_published_bytes_remain_explicitly_unavailable(self):
        for report in ({}, {'exportFile': {'path': 'events-000000.json.gz', 'size': 1, 'sha256': 'a' * 64}}):
            with self.subTest(report=report):
                result = review.inspect(report, {}, set())

                self.assertFalse(result['completeSavedExportRead'])
                self.assertIsNone(result['censusEventCountsMatch'])
                self.assertEqual([], result['jvmInformationEvents'])
                self.assertTrue(result['exclusions'])

    def test_unsafe_or_malformed_export_references_cannot_select_another_member(self):
        for identity in ('events-000000.json.gz', {}, {'path': '../events-000000.json.gz'},
                         {'path': 'events-000000.json.gz', 'size': 0, 'sha256': 'a' * 64, 'extra': True},
                         {'path': 'events-000000.json.gz', 'size': True, 'sha256': 'a' * 64},
                         {'path': 'events-000000.json.gz', 'size': -1, 'sha256': 'a' * 64},
                         {'path': 'events-000000.json.gz', 'size': 0, 'sha256': 'invalid'},
                         {'path': 'events-000000.json.gz/path', 'size': 0, 'sha256': 'a' * 64}):
            with self.subTest(identity=identity), self.assertRaisesRegex(ValueError, 'Malformed'):
                review.inspect({'exportFile': identity}, {}, set())

    def test_both_compressed_and_expanded_identities_are_verified_before_event_interpretation(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            raw = b'{"recording":{"events":[]}}'
            path.write_bytes(gzip.compress(raw))
            report = {'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                      'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            for completed in (None, False):
                for key in ('exportFile', 'retainedExportUncompressedIdentity'):
                    changed = dict(copy.deepcopy(report), completed=completed)
                    changed[key]['sha256'] = '0' * 64
                    with self.subTest(key=key, completed=completed), patch.object(review.events, 'JsonEvents') as parser:
                        with self.assertRaisesRegex(ValueError, 'bytes differ'):
                            review.inspect(changed, {path.name: path}, set())
                        parser.assert_not_called()

    def test_a_saved_export_cannot_supply_two_recording_contexts(self):
        report = {'exportFile': {'path': 'events-000000.json.gz', 'size': 1, 'sha256': 'a' * 64}}
        with self.assertRaisesRegex(ValueError, 'Repeated saved'):
            review.inspect(report, {}, {'events-000000.json.gz'})

    def test_malformed_or_truncated_saved_json_cannot_authorize_a_context_prefix(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            for raw in (b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{}}]',
                        b'{"recording":{"events":[]}} trailing',
                        b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{"pid":1,"pid":2}}]}}',
                        b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{"pid":NaN}}]}}',
                        b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{"text":"\xff"}}]}}'):
                path.write_bytes(gzip.compress(raw))
                report = {'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                          'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
                with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                    review.inspect(report, {path.name: path}, set())

    def test_complete_context_byte_and_event_bounds_decline_without_returning_a_parsed_prefix(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            raw = json.dumps({'recording': {'events': [
                {'type': 'jdk.JVMInformation', 'values': {'pid': 1}}] * 2}}).encode()
            path.write_bytes(gzip.compress(raw))
            report = {'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                      'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            for limit in ('MAX_CONTEXT_BYTES', 'MAX_CONTEXT_EVENTS'):
                with self.subTest(limit=limit), patch.object(review, limit, 1), self.assertRaisesRegex(ValueError, 'context bounds'):
                    review.inspect(report, {path.name: path}, set())

    def test_missing_repeated_and_conflicting_jvm_information_do_not_select_a_process_context(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            for values in ([], [{'pid': 1}, {'pid': 1}], [{'pid': 1}, {'pid': 2}]):
                raw = json.dumps({'recording': {'events': [
                    {'type': 'jdk.JVMInformation', 'values': value} for value in values]}}).encode()
                path.write_bytes(gzip.compress(raw))
                report = {'completed': True, 'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                          'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()},
                          'eventCounts': {'jdk.JVMInformation': len(values)} if values else {}}

                with self.subTest(values=values):
                    result = review.inspect(report, {path.name: path}, set())

                    self.assertTrue(result['completeSavedExportRead'])
                    self.assertEqual([{'eventIndex': i, 'values': value} for i, value in enumerate(values)],
                                     result['jvmInformationEvents'])
                    self.assertTrue(result['exclusions'])
                    self.assertFalse(result['targetProcessRoleProven'])

    def test_missing_or_conflicting_original_event_counts_remain_distinct_from_complete_export_reading(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            raw = b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{}}]}}'
            path.write_bytes(gzip.compress(raw))
            base = {'completed': True, 'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                    'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            for counts, expected in ((None, None), ({}, False), ({'jdk.JVMInformation': 2}, False),
                                     ({'jdk.JVMInformation': True}, False), ([], False)):
                with self.subTest(counts=counts):
                    result = review.inspect(dict(base, eventCounts=counts), {path.name: path}, set())

                    self.assertTrue(result['completeSavedExportRead'])
                    self.assertEqual(expected, result['censusEventCountsMatch'])
                    self.assertTrue(result['exclusions'])

    def test_original_incomplete_recording_remains_excluded_when_its_saved_export_is_readable(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events-000000.json.gz'
            raw = b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{}}]}}'
            path.write_bytes(gzip.compress(raw))
            report = {'completed': False, 'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                      'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()},
                      'eventCounts': {'jdk.JVMInformation': 1}}

            result = review.inspect(report, {path.name: path}, set())

            self.assertTrue(result['completeSavedExportRead'])
            self.assertTrue(result['censusEventCountsMatch'])
            self.assertIn('Original recording census is incomplete', result['exclusions'])
            self.assertFalse(result['completeProcessLifetimeProven'])

    def test_failed_census_with_an_unreadable_export_retains_an_unavailable_context_without_a_prefix(self):
        for raw in (b'', b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{}}]',
                    b'{"recording":{"events":[{"type":"jdk.JVMInformation","values":{"text":"\xff"}}]}}'):
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'events-000000.json.gz'
                path.write_bytes(gzip.compress(raw))
                report = {'completed': False, 'exportFile': dict(path=path.name, **review.cohort.file_identity(path)),
                          'retainedExportUncompressedIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
                before, represented = copy.deepcopy(report), set()

                result = review.inspect(report, {path.name: path}, represented)

                self.assertEqual(before, report)
                self.assertEqual({path.name}, represented)
                self.assertEqual(report['exportFile'], result['exportIdentity'])
                self.assertEqual(report['retainedExportUncompressedIdentity'], result['uncompressedIdentity'])
                self.assertFalse(result['completeSavedExportRead'])
                self.assertIsNone(result['censusEventCountsMatch'])
                self.assertEqual([], result['jvmInformationEvents'])
                self.assertTrue(result['exportReadError'])
                self.assertIn('Original recording census is incomplete', result['exclusions'])
                self.assertFalse(result['acceptedSavingsCredit'])

                with self.assertRaises((ValueError, UnicodeError)):
                    review.inspect(dict(report, completed=True), {path.name: path}, set())


if __name__ == '__main__':
    unittest.main(verbosity=2)
