import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import review_jvm_task_log_cohort as review


class ReviewJvmTaskLogCohortTest(unittest.TestCase):
    def test_catalog_keeps_all_cases_nested_logs_outside_logs_and_missing_logs(self):
        names = ['profile9/cases/cp-control/hotspot.xml', 'profile9/cases/cp-control/child/hotspot.xml',
                 'profile9/cases/ls-candidate/hotspot.xml', 'profile9/cases/unfinished/stdout',
                 'profile9/harness/hotspot.xml', 'provenance/hotspot.xml', 'provenance/config.json']
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            raw = json.dumps({'archive': {'name': 'original.tar.gz', 'size': 0, 'sha256': 'a' * 64},
                              'files': {name: {'size': 0, 'sha256': 'b' * 64} for name in names}}).encode()
            path.write_bytes(raw)

            _, result = review.catalog(path, hashlib.sha256(raw).hexdigest())

        self.assertEqual(['cp-control', 'ls-candidate', 'unfinished'], [row['caseDirectory'] for row in result['cases']])
        self.assertEqual([0, 1], result['cases'][0]['logIndices'])
        self.assertEqual([2], result['cases'][1]['logIndices'])
        self.assertEqual([2], result['missingCaseLogIndices'])
        self.assertIsNone(result['logs'][3]['caseIndex'])
        self.assertIsNone(result['logs'][4]['caseIndex'])
        self.assertFalse(result['expectedStudyCaseUniverseProven'])

    def test_ambiguous_or_absent_original_profile_prefix_is_not_resolved_by_selection(self):
        variants = [['profile9/cases/a/hotspot.xml', 'profile10/cases/b/hotspot.xml'],
                    ['profile9/live/hotspot.xml']]
        for names in variants:
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                raw = json.dumps({'archive': {'name': 'original.tar.gz', 'size': 0, 'sha256': 'a' * 64},
                                  'files': {name: {'size': 0, 'sha256': 'b' * 64} for name in names}}).encode()
                path.write_bytes(raw)

                with self.assertRaisesRegex(ValueError, 'prefix is absent or ambiguous'):
                    review.catalog(path, hashlib.sha256(raw).hexdigest())

    def test_duplicate_json_keys_and_unsafe_member_paths_are_rejected(self):
        variants = [b'{"files":{},"files":{}}']
        for name in ['profile9/cases/../hotspot.xml', '/profile9/cases/a/hotspot.xml',
                     'profile9/cases/a\\hotspot.xml', 'profile9/cases/a/\x00hotspot.xml']:
            variants.append(json.dumps({'archive': {'name': 'a.tar.gz', 'size': 0, 'sha256': 'a' * 64},
                                        'files': {name: {'size': 0, 'sha256': 'b' * 64}}}).encode())
        for raw in variants:
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                path.write_bytes(raw)

                with self.assertRaises(ValueError):
                    review.catalog(path, hashlib.sha256(raw).hexdigest())

    def test_manifest_identity_member_types_and_case_budget_are_checked_before_archive_access(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            data = {'archive': {'name': 'a.tar.gz', 'size': 0, 'sha256': 'a' * 64},
                    'files': {'profile9/cases/a/hotspot.xml': {'size': 0, 'sha256': 'b' * 64}}}
            variants = [(data, 'c' * 64, 4096),
                        ({**data, 'files': {'profile9/cases/a/hotspot.xml': {'size': True, 'sha256': 'b' * 64}}}, None, 4096),
                        (data, None, 0)]
            for changed, expected, budget in variants:
                with self.subTest(manifest=changed, budget=budget), patch.object(review, 'MAX_CASES', budget):
                    raw = json.dumps(changed).encode()
                    path.write_bytes(raw)

                    with self.assertRaises(ValueError):
                        review.catalog(path, expected or hashlib.sha256(raw).hexdigest())

    def test_archive_authentication_precedes_extraction_and_log_interpretation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'original.tar.gz'
            archive.write_bytes(b'not the authenticated archive')
            manifest = {'archive': {'size': archive.stat().st_size, 'sha256': 'a' * 64}, 'files': {}}
            output = root / 'out'
            output.mkdir()

            with patch.object(review.streaming, 'parse_stream') as inspect, self.assertRaisesRegex(ValueError, 'archive differs'):
                review.extract_logs(manifest, archive, output, {'logs': []}, review.StorageBudget(output))

            inspect.assert_not_called()
            self.assertFalse((output / 'raw-logs').exists())

    def test_all_original_members_are_verified_before_any_log_is_reviewed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'original.tar.gz'
            contents = {'profile9/cases/a/hotspot.xml': b'<hotspot_log/>', 'profile9/cases/a/record.json': b'{}'}
            with tarfile.open(archive, 'w:gz') as target:
                for name, data in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    target.addfile(info, io.BytesIO(data))
            manifest = {'archive': {'name': archive.name, **review.file_identity(archive)},
                        'files': {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                                  for name, data in contents.items()}}
            manifest['files']['profile9/cases/a/record.json']['sha256'] = 'f' * 64
            path = root / 'manifest.json'
            path.write_text(json.dumps(manifest))
            report = {'completed': False, 'sourceSha256': 'a' * 64}
            output = root / 'out'
            with patch.object(review.streaming, 'parse_stream') as inspect, self.assertRaisesRegex(ValueError, 'member bytes differ'):
                review.inspect(path, hashlib.sha256(path.read_bytes()).hexdigest(), archive, output, report)

            inspect.assert_not_called()
            self.assertFalse(report['completed'])
            self.assertFalse(report['inventory']['allArchiveMemberBytesVerified'])

    def test_complete_cohort_retains_each_log_losslessly_and_never_promotes_cost_authority(self):
        xml = ("<hotspot_log process='100'><compilation_log thread='101'>"
               "<start_compile_thread thread='101' process='100'/>"
               "<task compile_id='7' method='outside.C f ()V' stamp='1.001'>"
               "<task_done success='1' stamp='1.005'/></task></compilation_log></hotspot_log>").encode()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'original.tar.gz'
            contents = {'profile9/cases/cp-control/hotspot.xml': xml,
                        'profile9/cases/ls-candidate/hotspot.xml': xml,
                        'profile9/harness/hotspot.xml': xml}
            with tarfile.open(archive, 'w:gz') as target:
                for name, data in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    target.addfile(info, io.BytesIO(data))
            path = root / 'manifest.json'
            path.write_text(json.dumps({'archive': {'name': archive.name, **review.file_identity(archive)},
                                        'files': {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                                                  for name, data in contents.items()}}))
            report = {'completed': False, 'sourceSha256': 'a' * 64}
            output = root / 'out'

            review.inspect(path, hashlib.sha256(path.read_bytes()).hexdigest(), archive, output, report)

            self.assertTrue(report['completed'])
            self.assertTrue(report['allCaseLogReviewsCompleted'])
            self.assertTrue(report['inventory']['allArchiveMemberBytesVerified'])
            self.assertEqual(list(contents), [row['originalMember'] for row in report['inventory']['logs']])
            for row in report['inventory']['logs']:
                with gzip.open(output / row['rawCompressedFile'], 'rb') as raw:
                    self.assertEqual(xml, raw.read())
                with gzip.open(output / row['reviewCompressedFile'], 'rt') as raw:
                    derived = json.load(raw)
                self.assertEqual(row['reviewUncompressedIdentity'],
                                 review.gzip_identity(output / row['reviewCompressedFile'],
                                                      row['reviewUncompressedIdentity']['size']))
                self.assertTrue(derived['completed'])
                self.assertEqual('1.001', derived['taskLogSpans'][0]['startStampCopiedVerbatim'])
                for key in review.DENIED:
                    self.assertFalse(derived[key])
                    self.assertFalse(report[key])

    def test_missing_case_logs_keep_the_case_without_a_zero_cost_or_successful_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'original.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                info = tarfile.TarInfo('profile9/cases/unfinished/record.json')
                info.size = 2
                target.addfile(info, io.BytesIO(b'{}'))
            path = root / 'manifest.json'
            path.write_text(json.dumps({'archive': {'name': archive.name, **review.file_identity(archive)},
                                        'files': {'profile9/cases/unfinished/record.json':
                                                  {'size': 2, 'sha256': hashlib.sha256(b'{}').hexdigest()}}}))
            report = {'completed': False, 'sourceSha256': 'a' * 64}

            review.inspect(path, hashlib.sha256(path.read_bytes()).hexdigest(), archive, root / 'out', report)

            self.assertTrue(report['completed'])
            self.assertFalse(report['allCaseLogReviewsCompleted'])
            self.assertFalse(report['allLoggedTaskStampPairsValid'])
            self.assertEqual([0], report['inventory']['missingCaseLogIndices'])
            self.assertEqual('unfinished', report['inventory']['cases'][0]['caseDirectory'])
            self.assertFalse(report['compilerElapsedTimeProven'])

    def test_malformed_and_oversized_logs_keep_complete_raw_bytes_and_negative_per_log_reports(self):
        for payload, budget in [(b'<hotspot_log><unfinished', 1024), (b'<hotspot_log/>', 1)]:
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                compressed = output / 'raw.xml.gz'
                with gzip.open(compressed, 'wb') as target:
                    target.write(payload)
                row = {'originalMember': 'profile9/cases/a/hotspot.xml',
                       'originalIdentity': {'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()},
                       'caseIndex': 0, 'rawCompressedFile': compressed.name}

                with patch.object(review.streaming, 'MAX_INPUT_BYTES', budget):
                    report = review.review_log(row, output, 'a' * 64)
                review.write_log_report(0, row, report, output, review.StorageBudget(output))

                self.assertFalse(row['reviewCompleted'])
                with gzip.open(compressed, 'rb') as raw:
                    self.assertEqual(payload, raw.read())
                with gzip.open(output / row['reviewCompressedFile'], 'rt') as raw:
                    self.assertFalse(json.load(raw)['completed'])
                self.assertIn('error', report)

    def test_a_log_above_raw_retention_limits_keeps_its_full_original_archive_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            row = {'originalMember': 'profile9/cases/a/hotspot.xml',
                   'originalIdentity': {'size': review.MAX_SAVED_LOG_BYTES + 1, 'sha256': 'b' * 64},
                   'caseIndex': 0, 'rawCompressedFile': None}

            result = review.review_log(row, output, 'a' * 64)

            self.assertFalse(result['completed'])
            self.assertEqual(row['originalIdentity'], result['originalIdentity'])
            self.assertIn('original archive reference retained', result['error']['message'])
            self.assertFalse(result['compilerElapsedTimeProven'])

    def test_linked_duplicate_and_unexpected_archive_members_prevent_complete_binding(self):
        for variant in ['linked', 'duplicate', 'unexpected']:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'original.tar.gz'
                name = 'profile9/cases/a/hotspot.xml'
                with tarfile.open(archive, 'w:gz') as target:
                    entry = tarfile.TarInfo(name)
                    entry.size = 1
                    if variant == 'linked':
                        entry.type, entry.linkname, entry.size = tarfile.SYMTYPE, '/outside', 0
                        target.addfile(entry)
                    else:
                        target.addfile(entry, io.BytesIO(b'x'))
                        extra = tarfile.TarInfo(name if variant == 'duplicate' else 'other.txt')
                        extra.size = 1
                        target.addfile(extra, io.BytesIO(b'x'))
                manifest = {'archive': {'name': archive.name, **review.file_identity(archive)},
                            'files': {name: {'size': 1, 'sha256': hashlib.sha256(b'x').hexdigest()}}}
                inventory = {'logs': [{'originalMember': name}]}
                output = root / 'out'

                with self.assertRaises(ValueError):
                    review.extract_logs(manifest, archive, output, inventory, review.StorageBudget(output))

                self.assertNotIn('allArchiveMemberBytesVerified', inventory)

    def test_expansion_and_compressed_storage_limits_abort_without_selecting_a_case_subset(self):
        for constraint in ['MAX_EXPANDED_BYTES', 'MAX_STORED_BYTES']:
            with self.subTest(constraint=constraint), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'original.tar.gz'
                name = 'profile9/cases/a/hotspot.xml'
                with tarfile.open(archive, 'w:gz') as target:
                    entry = tarfile.TarInfo(name)
                    entry.size = 2
                    target.addfile(entry, io.BytesIO(b'xx'))
                manifest = {'archive': {'name': archive.name, **review.file_identity(archive)},
                            'files': {name: {'size': 2, 'sha256': hashlib.sha256(b'xx').hexdigest()}}}
                path = root / 'manifest.json'
                path.write_text(json.dumps(manifest))
                result = {'completed': False, 'sourceSha256': 'a' * 64}

                with patch.object(review, constraint, 1), patch.object(review.streaming, 'parse_stream') as inspect:
                    with self.assertRaises(ValueError):
                        review.inspect(path, hashlib.sha256(path.read_bytes()).hexdigest(), archive, root / 'out', result)

                inspect.assert_not_called()
                self.assertFalse(result['completed'])
                self.assertEqual(1, len(result['inventory']['cases']))

    def test_oversized_derived_reports_are_compressed_as_negative_reports_with_raw_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = {'completed': True, 'sourceSha256': 'a' * 64,
                      'inputFiles': {'original.xml': {'size': 1, 'sha256': 'b' * 64}}, 'taskLogSpans': ['large']}
            row = {}

            with patch.object(review, 'MAX_REVIEW_BYTES', 1):
                review.write_log_report(0, row, report, output, review.StorageBudget(output))

            self.assertFalse(row['reviewCompleted'])
            self.assertEqual('OutputLimitExceeded', row['reviewError']['type'])
            with gzip.open(output / row['reviewCompressedFile'], 'rt') as raw:
                negative = json.load(raw)
            self.assertFalse(negative['completed'])
            self.assertEqual(report['inputFiles'], negative['inputFiles'])
            self.assertNotIn('taskLogSpans', negative)

    def test_index_overflow_keeps_manifest_provenance_and_clears_all_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'cohort.json'
            report = {'completed': True, 'sourceSha256': 'a' * 64,
                      'inputFiles': {'manifest.json': {'size': 1, 'sha256': 'b' * 64}},
                      **{key: True for key in review.single.DENIED}}

            with patch.object(review, 'MAX_INDEX_BYTES', 1), self.assertRaises(ValueError):
                review.write_index(path, report)

            result = json.loads(path.read_text())
            self.assertFalse(result['completed'])
            self.assertEqual(report['inputFiles'], result['inputFiles'])
            self.assertEqual('IndexLimitExceeded', result['error']['type'])
            for key in review.single.DENIED:
                self.assertFalse(result[key])

    def test_fatal_main_input_failure_retains_a_negative_index_without_interpreting_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'manifest.json'
            archive = root / 'original.tar.gz'
            manifest.write_bytes(b'{}')
            archive.write_bytes(b'bad archive')
            output = root / 'out'
            argv = ['review_jvm_task_log_cohort.py', str(manifest), 'a' * 64, str(archive), str(output)]

            with patch.object(review.sys, 'argv', argv), patch.object(review.streaming, 'parse_stream') as inspect:
                with self.assertRaisesRegex(ValueError, 'manifest differs'):
                    review.main()

            inspect.assert_not_called()
            result = json.loads((output / 'cohort-review.json').read_text())
            self.assertFalse(result['completed'])
            self.assertFalse(result['allCaseLogReviewsCompleted'])
            self.assertEqual(review.file_identity(archive), result['inputFiles'][str(archive)])

    def test_case_log_and_member_limits_decline_the_complete_catalog_before_review(self):
        for limit in ['MAX_CASES', 'MAX_LOGS', 'MAX_MEMBERS']:
            with self.subTest(limit=limit), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                raw = json.dumps({'archive': {'name': 'a.tar.gz', 'size': 0, 'sha256': 'a' * 64},
                                  'files': {'profile9/cases/a/hotspot.xml': {'size': 0, 'sha256': 'b' * 64}}}).encode()
                path.write_bytes(raw)

                with patch.object(review, limit, 0), self.assertRaises(ValueError):
                    review.catalog(path, hashlib.sha256(raw).hexdigest())

    def test_complete_large_log_is_reviewed_without_using_the_legacy_input_cap(self):
        payload = b"<hotspot_log process='100'>" + b' ' * (16 * 1024 * 1024 + 1) + b'</hotspot_log>'
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            compressed = output / 'raw.xml.gz'
            with gzip.open(compressed, 'wb') as target:
                target.write(payload)
            row = {'originalMember': 'profile9/cases/a/hotspot.xml',
                   'originalIdentity': {'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()},
                   'caseIndex': 0, 'rawCompressedFile': compressed.name}

            result = review.review_log(row, output, 'a' * 64)
            review.write_log_report(0, row, result, output, review.StorageBudget(output))

            self.assertTrue(row['reviewCompleted'])
            self.assertTrue(result['completed'])
            with gzip.open(output / row['reviewCompressedFile'], 'rt') as source:
                self.assertEqual(result, json.load(source))
            self.assertFalse(result['compilerElapsedTimeProven'])

    def test_complete_streamed_report_can_exceed_the_legacy_uncompressed_output_cap(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = {'completed': True, 'sourceSha256': 'a' * 64, 'retained': 'x' * (64 * 1024 * 1024 + 1)}
            row = {}

            review.write_log_report(0, row, report, output, review.StorageBudget(output))

            self.assertTrue(row['reviewCompleted'])
            self.assertGreater(row['reviewUncompressedIdentity']['size'], 64 * 1024 * 1024)
            with gzip.open(output / row['reviewCompressedFile'], 'rt') as source:
                self.assertEqual(report, json.load(source))
            self.assertEqual(row['reviewUncompressedIdentity'],
                             review.gzip_identity(output / row['reviewCompressedFile'], review.MAX_REVIEW_BYTES))

    def test_retained_gzip_must_decode_completely_within_its_declared_byte_identity(self):
        for changed in ['truncated', 'expanded']:
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'raw.xml.gz'
                with gzip.open(path, 'wb') as target:
                    target.write(b'complete original bytes')
                if changed == 'truncated':
                    path.write_bytes(path.read_bytes()[:-8])

                with self.assertRaises((EOFError, ValueError)):
                    review.gzip_identity(path, 0 if changed == 'expanded' else 1024)


if __name__ == '__main__':
    unittest.main(verbosity=2)
