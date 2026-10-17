import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import review_jvm_compilation_cohort as review


class ReviewJvmCompilationCohortTest(unittest.TestCase):
    def test_archived_plan_distinguishes_requested_recordings_from_unprofiled_controls(self):
        variants = ('complete', 'missing recording', 'unexpected recording', 'missing control',
                    'extra case', 'metadata mismatch', 'profile mismatch', 'request hash mismatch',
                    'missing plan', 'duplicate plan case', 'nonboolean profile')
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                inputs = [dict(suite='suite', problem='problem', format='DIMACS', sourceHashes={'model': 'a' * 64})]
                plans = [dict(mode=mode, inputs=[0], repeats=1, engines=['cp'], profile=profile)
                         for mode, profile in [('profile', True), ('cold-complete', False)]]
                measurements, cases = [], []
                for plan in plans:
                    for arm in ('control', 'candidate'):
                        name = plan['mode'] + '-00-0-cp-' + arm
                        measurements.append(dict(case=name, index=0, repetition=0, engine='cp', arm=arm,
                                                 mode=plan['mode'], recipe=None, profile=plan['profile'],
                                                 problem=inputs[0], recordingPresent=plan['profile'],
                                                 compilationLogPresent=plan['profile']))
                        cases.append(dict(caseDirectory=name, completed=plan['profile'],
                                          measurementMatchesCasesJson=True, classOriginInputs=[],
                                          recordings=[{}] if plan['profile'] else [],
                                          xmlInputs=[{}] if plan['profile'] else []))
                if variant == 'missing recording':
                    cases[0].update(recordings=[], completed=False)
                elif variant == 'unexpected recording':
                    cases[2]['recordings'] = [{}]
                elif variant == 'missing control':
                    del cases[2]
                elif variant == 'extra case':
                    cases.append(dict(cases[-1], caseDirectory='unexpected'))
                elif variant == 'metadata mismatch':
                    cases[2]['measurementMatchesCasesJson'] = False
                elif variant == 'profile mismatch':
                    measurements[0]['profile'] = False
                elif variant == 'duplicate plan case':
                    plans.append(plans[0])
                elif variant == 'nonboolean profile':
                    plans[0]['profile'] = 1
                selected = {}
                for name, value in [('selection.json', inputs), ('study-plan.json', plans)]:
                    path = root / name
                    path.write_text(json.dumps(value))
                    selected['profile9/' + name] = path
                request = {'sha256': review.cohort.file_identity(root / 'selection.json')['sha256'],
                           'plan-sha256': review.cohort.file_identity(root / 'study-plan.json')['sha256']}
                if variant == 'request hash mismatch':
                    request['plan-sha256'] = '0' * 64
                path = root / 'requested-options.json'
                path.write_text(json.dumps(request))
                selected['profile9/requested-options.json'] = path
                if variant == 'missing plan':
                    del selected['profile9/study-plan.json']

                result = review.planned_recording_coverage(selected, 'profile9/', measurements, cases)

                self.assertEqual(variant == 'complete', result['complete'])
                self.assertFalse(result['expectedStudyCaseUniverseProven'])
                if variant == 'complete':
                    self.assertEqual(['profile-00-0-cp-candidate', 'profile-00-0-cp-control'],
                                     result['requestedRecordingCaseNames'])
                    self.assertEqual(['cold-complete-00-0-cp-candidate', 'cold-complete-00-0-cp-control'],
                                     result['deliberatelyUnprofiledCaseNames'])

    def test_complete_archive_hashes_large_unselected_reports_without_extracting_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scratch = root / 'scratch'
            scratch.mkdir()
            contents = {'profile9/cases/a/cli.jfr': b'saved recording',
                        'profile9/cases/a/hotspot.xml': b'<hotspot_log/>',
                        'profile9/cases/a/class-load.log': b'original log',
                        'profile9/cases/a/class-load.log.0': b'original rotation',
                        'profile9/cases/a/class-load.log.1.current': b'current rotation',
                        'profile9/control-harness-provenance.json': b'{}',
                        'profile9/study-plan.json': b'[]',
                        'profile9/cases.json': b'[]', 'profile9/study-report.json': b'x' * (200 * 1024)}
            archive = root / 'archive.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                for name, raw in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    target.addfile(info, io.BytesIO(raw))
            manifest = {'archive': review.cohort.file_identity(archive),
                        'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                  for name, raw in contents.items()}}
            inventory = {'profilePrefix': 'profile9/', 'casePrefix': 'profile9/cases/'}

            with patch.object(review.cohort, 'MAX_ARCHIVE_BYTES', 128 * 1024):
                selected = review.authenticated_inputs(manifest, archive, scratch, inventory)

            self.assertEqual(set(contents) - {'profile9/study-report.json'}, set(selected))
            self.assertEqual({name: raw for name, raw in contents.items() if name in selected},
                             {name: path.read_bytes() for name, path in selected.items()})
            self.assertTrue(inventory['allArchiveMemberBytesVerified'])

    def test_unselected_member_corruption_prevents_any_jfr_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output, scratch = root / 'out', root / 'scratch'
            output.mkdir()
            scratch.mkdir()
            contents = {'profile9/cases/a/cli.jfr': b'saved recording',
                        'profile9/cases.json': b'[]', 'profile9/unselected': b'corrupt'}
            archive = root / 'archive.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                for name, raw in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    target.addfile(info, io.BytesIO(raw))
            manifest = {'archive': {'name': archive.name, **review.cohort.file_identity(archive)},
                        'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                  for name, raw in contents.items()}}
            manifest['files']['profile9/unselected']['sha256'] = 'f' * 64
            path = root / 'manifest.json'
            path.write_text(json.dumps(manifest))
            report = {}

            with patch.object(review, 'decode_recording') as decode, self.assertRaisesRegex(ValueError, 'member bytes differ'):
                review.inspect(path, review.cohort.file_identity(path)['sha256'], archive, output,
                               root / 'jfr', scratch, report)

            decode.assert_not_called()
            self.assertFalse(report['originalInventory']['allArchiveMemberBytesVerified'])

    def test_unsafe_duplicate_unexpected_and_missing_archive_members_are_refused(self):
        for variant in ('linked', 'duplicate', 'unexpected', 'missing'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'archive.tar.gz'
                name, raw = 'profile9/cases/a/cli.jfr', b'raw'
                with tarfile.open(archive, 'w:gz') as target:
                    for member_name in ([name, name] if variant == 'duplicate' else
                                        [name, 'unexpected'] if variant == 'unexpected' else [name]):
                        info = tarfile.TarInfo(member_name)
                        info.size = len(raw)
                        if variant == 'linked':
                            info.type = tarfile.SYMTYPE
                            info.linkname = '/outside'
                        target.addfile(info, io.BytesIO(raw) if info.isfile() else None)
                files = {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
                if variant == 'missing':
                    files['absent'] = files[name]
                manifest = {'archive': review.cohort.file_identity(archive), 'files': files}
                inventory = {'profilePrefix': 'profile9/', 'casePrefix': 'profile9/cases/'}

                with self.assertRaises(ValueError):
                    review.authenticated_inputs(manifest, archive, root, inventory)

                self.assertNotIn('allArchiveMemberBytesVerified', inventory)

    def test_expansion_member_and_total_scratch_bounds_precede_recording_decode(self):
        for field, module, limit in [('MAX_EXPANDED_BYTES', review.cohort, 1),
                                     ('MAX_RECORDING_BYTES', review, 1), ('MAX_SELECTED_BYTES', review, 1)]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'archive.tar.gz'
                name, raw = 'profile9/cases/a/cli.jfr', b'recording'
                with tarfile.open(archive, 'w:gz') as target:
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    target.addfile(info, io.BytesIO(raw))
                manifest = {'archive': review.cohort.file_identity(archive),
                            'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}}
                inventory = {'profilePrefix': 'profile9/', 'casePrefix': 'profile9/cases/'}

                with patch.object(module, field, limit), self.assertRaises(ValueError):
                    review.authenticated_inputs(manifest, archive, root, inventory)

    def test_complete_jfr_export_retains_original_bytes_exact_units_and_source_denials(self):
        raw = json.dumps({'recording': {'events': [{'type': 'jdk.Compilation', 'values': {
            'compileId': 8, 'method': {'type': {'name': 'p/C'}, 'name': 'f', 'descriptor': '()V'},
            'compileLevel': 1, 'isOsr': False, 'succeded': True,
            'duration': 'PT0.000000001S', 'codeSize': 19}}]}}).encode()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            storage = review.cohort.StorageBudget(output)
            class_origins = review.origins.parse(
                io.BytesIO(b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n'),
                {'version': 1, 'launcher': '/owned/cli/bin/cli',
                 'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}})
            with patch.object(review.subprocess, 'Popen') as spawn, patch.object(review.threading, 'Timer'):
                process = spawn.return_value
                process.stdout = io.BytesIO(raw)
                process.wait.return_value = 0
                process.poll.return_value = 0

                result = review.decode_recording(output / 'original.jfr', output, 0, Path('/jdk/bin/jfr'),
                                                 storage, {}, 'control', {}, class_origins=class_origins)

            with gzip.open(output / result['exportFile']['path'], 'rb') as exported:
                self.assertEqual(raw, exported.read())
            self.assertTrue(result['completed'])
            self.assertEqual(1, result['observedAllClassMetrics']['reportedElapsedNanos'])
            self.assertEqual({'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()},
                             result['retainedExportUncompressedIdentity'])
            self.assertEqual(['print', '--json', '--events'], result['command'][1:4])
            self.assertTrue(all(result[flag] is False for flag in review.cohort.DENIED))
            self.assertNotIn('JAVA_TOOL_OPTIONS', spawn.call_args.kwargs['env'])
            association = result['recordedInstalledClassOriginAssociations']
            self.assertEqual([{'eventIndex': 0, 'classIndex': 0}], association['compilationClassReferences'])
            self.assertEqual('a' * 64, association['classes'][0]['unambiguousRecordedInstalledJar']['sha256'])
            self.assertFalse(association['runtimeSourceBindingProven'])

    def test_nonzero_tool_exit_cannot_promote_a_complete_json_prefix(self):
        raw = b'{"recording":{"events":[]}}'
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch.object(review.subprocess, 'Popen') as spawn, patch.object(review.threading, 'Timer'):
                process = spawn.return_value
                process.stdout = io.BytesIO(raw)
                process.wait.return_value = 1
                process.poll.return_value = 1

                result = review.decode_recording(output / 'original.jfr', output, 0, Path('/jdk/bin/jfr'),
                                                 review.cohort.StorageBudget(output), {}, None, {})

            self.assertFalse(result['completed'])
            self.assertEqual(1, result['exitCode'])
            self.assertIn('failed or timed out', result['error']['message'])
            with gzip.open(output / result['exportFile']['path'], 'rb') as exported:
                self.assertEqual(raw, exported.read())

    def test_truncated_export_preserves_a_negative_and_the_complete_consumed_prefix(self):
        raw = b'{"recording":{"events":[{"type":"jdk.Compilation","values":{}}'
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch.object(review.subprocess, 'Popen') as spawn, patch.object(review.threading, 'Timer'):
                process = spawn.return_value
                process.stdout = io.BytesIO(raw)
                process.wait.return_value = 0
                process.poll.return_value = 0

                result = review.decode_recording(output / 'original.jfr', output, 0, Path('/jdk/bin/jfr'),
                                                 review.cohort.StorageBudget(output), {}, None, {})

            self.assertFalse(result['completed'])
            self.assertIn('error', result)
            with gzip.open(output / result['exportFile']['path'], 'rb') as exported:
                self.assertEqual(raw, exported.read())

    def test_export_timeout_kills_only_the_owned_child_and_keeps_the_negative(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch.object(review.subprocess, 'Popen') as spawn, patch.object(review.threading, 'Timer') as timer:
                process = spawn.return_value
                process.stdout = io.BytesIO(b'{"recording":{"events":[]}}')
                process.wait.return_value = 0
                process.poll.return_value = None
                timer.return_value.start.side_effect = lambda: timer.call_args.args[1]()

                result = review.decode_recording(output / 'original.jfr', output, 0, Path('/jdk/bin/jfr'),
                                                 review.cohort.StorageBudget(output), {}, None, {})

                self.assertTrue(process.kill.called)
                self.assertTrue(timer.return_value.cancel.called)
            self.assertFalse(result['completed'])
            self.assertTrue(result['timedOut'])

    def test_whole_output_budget_failure_is_not_replaced_by_a_successful_empty_case(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            storage = review.cohort.StorageBudget(output)
            with patch.object(review.subprocess, 'Popen') as spawn, patch.object(review.threading, 'Timer'), \
                    patch.object(review.cohort, 'MAX_STORED_BYTES', 1):
                process = spawn.return_value
                process.stdout = io.BytesIO(b'{"recording":{"events":[]}}')
                process.wait.return_value = 0
                process.poll.return_value = 0

                with self.assertRaisesRegex(ValueError, 'storage exceeds bounds'):
                    review.decode_recording(output / 'original.jfr', output, 0, Path('/jdk/bin/jfr'),
                                            storage, {}, None, {})

    def test_case_metadata_missing_recordings_and_xml_errors_remain_explicit(self):
        for variant in ('complete', 'metadata mismatch', 'missing recording', 'metadata only', 'malformed xml',
                        'class origins', 'rotated class origins', 'unparsed class origins', 'malformed provenance'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output, scratch = root / 'out', root / 'scratch'
                output.mkdir()
                scratch.mkdir()
                metadata = {'case': 'a', 'arm': 'control', 'sourceHashesMatch': False}
                measurements = [metadata, {'case': 'b'}] if variant == 'metadata only' else [metadata]
                original = {**metadata, 'arm': 'candidate'} if variant == 'metadata mismatch' else metadata
                contents = {'profile9/cases/a/cli.jfr': b'original recording',
                            'profile9/cases/a/hotspot.xml': b'<hotspot_log>' if variant == 'malformed xml' else b'<hotspot_log/>',
                            'profile9/cases/a/measurement.json': json.dumps(original).encode(),
                            'profile9/cases.json': json.dumps(measurements).encode()}
                if variant == 'missing recording':
                    del contents['profile9/cases/a/cli.jfr']
                if variant in ('class origins', 'rotated class origins', 'unparsed class origins', 'malformed provenance'):
                    contents['profile9/cases/a/class-load.log'] = \
                        b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n'
                    contents['profile9/control-provenance.json'] = json.dumps({
                        'version': 2 if variant == 'malformed provenance' else 1,
                        'launcher': '/owned/cli/bin/cli',
                        'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}).encode()
                if variant == 'rotated class origins':
                    contents['profile9/cases/a/class-load.log.0'] = contents['profile9/cases/a/class-load.log']
                if variant == 'unparsed class origins':
                    contents['profile9/cases/a/class-load.log'] += b'unparsed suffix\n'
                archive = root / 'archive.tar.gz'
                with tarfile.open(archive, 'w:gz') as target:
                    for name, raw in contents.items():
                        info = tarfile.TarInfo(name)
                        info.size = len(raw)
                        target.addfile(info, io.BytesIO(raw))
                manifest = {'archive': {'name': archive.name, **review.cohort.file_identity(archive)},
                            'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                      for name, raw in contents.items()}}
                path = root / 'manifest.json'
                path.write_text(json.dumps(manifest))
                jfr = root / 'jfr'
                jfr.write_bytes(b'observer tool identity')
                report = {}

                with patch.object(review, 'decode_recording', side_effect=lambda *args: {
                        'completed': True, **{flag: False for flag in review.cohort.DENIED}}):
                    review.inspect(path, review.cohort.file_identity(path)['sha256'], archive, output, jfr, scratch, report)

                self.assertTrue(report['completed'])
                self.assertEqual(variant in ('complete', 'class origins', 'rotated class origins',
                                            'unparsed class origins', 'malformed provenance'),
                                 report['allCaseCensusesComplete'])
                self.assertEqual(['a'], [row['caseDirectory'] for row in report['cases']])
                self.assertFalse(report['cases'][0]['originalContextCopiedVerbatim']['sourceHashesMatch'])
                self.assertFalse(report['cases'][0]['acceptedSavingsCredit'])
                self.assertEqual(variant == 'class origins', report['cases'][0]['recordedClassOriginsComplete'])
                if variant == 'metadata mismatch':
                    self.assertFalse(report['cases'][0]['measurementMatchesCasesJson'])
                if variant == 'malformed xml':
                    self.assertIsNotNone(report['cases'][0]['xmlInputs'][0]['error'])
                if variant == 'metadata only':
                    self.assertEqual(['b'], report['metadataOnlyCaseNames'])

    def test_duplicate_original_case_metadata_is_refused_before_any_event_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output, scratch = root / 'out', root / 'scratch'
            output.mkdir()
            scratch.mkdir()
            contents = {'profile9/cases/a/cli.jfr': b'original',
                        'profile9/cases.json': b'[{"case":"a"},{"case":"a"}]'}
            archive = root / 'archive.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                for name, raw in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    target.addfile(info, io.BytesIO(raw))
            manifest = {'archive': {'name': archive.name, **review.cohort.file_identity(archive)},
                        'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                  for name, raw in contents.items()}}
            path = root / 'manifest.json'
            path.write_text(json.dumps(manifest))

            with patch.object(review, 'decode_recording') as decode, self.assertRaisesRegex(ValueError, 'duplicate identities'):
                review.inspect(path, review.cohort.file_identity(path)['sha256'], archive, output,
                               root / 'jfr', scratch, {})

            decode.assert_not_called()

    def test_companion_restores_only_authenticated_original_omitted_class_logs(self):
        for variant in ('complete', 'no companion', 'wrong primary', 'wrong member', 'overlap', 'extra case',
                        'corrupt provenance', 'expanded bound', 'selected bound'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output, scratch = root / 'out', root / 'scratch'
                output.mkdir()
                scratch.mkdir()
                original = {'case': 'a', 'arm': 'control', 'sourceHashesMatch': False}
                log = b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n'
                omitted = 'profile9/cases/a/class-load.log.0'
                contents = {'profile9/cases/a/cli.jfr': b'original recording',
                            'profile9/cases/a/hotspot.xml': b'<hotspot_log/>',
                            'profile9/cases/a/class-load.log': log,
                            'profile9/cases/a/measurement.json': json.dumps(original).encode(),
                            'profile9/cases.json': json.dumps([original]).encode(),
                            'profile9/control-provenance.json': json.dumps({'version': 1,
                                'launcher': '/owned/cli/bin/cli',
                                'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}).encode()}
                additional = {omitted: b'different' if variant == 'wrong member' else log,
                              'provenance/identity.txt': b'original companion metadata'}
                if variant == 'overlap':
                    additional['profile9/cases/a/class-load.log'] = additional.pop(omitted)
                if variant == 'extra case':
                    additional['profile9/cases/b/class-load.log.0'] = additional.pop(omitted)
                archives, manifests = [], []
                for index, files in enumerate([contents, additional]):
                    archive = root / f'archive{index}.tar.gz'
                    with tarfile.open(archive, 'w:gz') as target:
                        for name, raw in files.items():
                            info = tarfile.TarInfo(name)
                            info.size = len(raw)
                            target.addfile(info, io.BytesIO(raw))
                    manifests.append({'archive': {'name': archive.name, **review.cohort.file_identity(archive)},
                                      'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                                for name, raw in files.items()}})
                    archives.append(archive)
                manifests[0]['catalog'] = {'sourceFilterFileInventory': {'omittedButPrivatelyRetained': [
                    {'path': 'cases/a/class-load.log.0', 'bytes': len(log), 'sha256': hashlib.sha256(log).hexdigest()}]}}
                manifests[1]['catalog'] = {'primaryArchive': dict(manifests[0]['archive'])}
                if variant == 'wrong primary':
                    manifests[1]['catalog']['primaryArchive']['sha256'] = 'b' * 64
                if variant == 'corrupt provenance':
                    manifests[1]['files']['provenance/identity.txt']['sha256'] = 'b' * 64
                paths = [root / 'manifest0.json', root / 'manifest1.json']
                for path, manifest in zip(paths, manifests):
                    path.write_text(json.dumps(manifest))
                jfr = root / 'jfr'
                jfr.write_bytes(b'observer tool identity')
                report = {}
                field = 'MAX_SELECTED_BYTES' if variant == 'selected bound' else 'MAX_EXPANDED_BYTES'
                module = review if variant == 'selected bound' else review.cohort
                maximum = sum(len(raw) for raw in contents.values()) + len(log) - 1 \
                    if variant in ('selected bound', 'expanded bound') else getattr(module, field)
                supplement = (paths[1], review.cohort.file_identity(paths[1])['sha256'], archives[1]) \
                    if variant != 'no companion' else None

                with patch.object(module, field, maximum), patch.object(review, 'decode_recording', return_value={
                        'completed': True, **{flag: False for flag in review.cohort.DENIED}}) as decode:
                    if variant in ('complete', 'no companion'):
                        review.inspect(paths[0], review.cohort.file_identity(paths[0])['sha256'], archives[0],
                                       output, jfr, scratch, report, supplement)
                    else:
                        with self.assertRaises(ValueError):
                            review.inspect(paths[0], review.cohort.file_identity(paths[0])['sha256'], archives[0],
                                           output, jfr, scratch, report, supplement)
                        decode.assert_not_called()
                        continue

                case = report['cases'][0]
                self.assertTrue(report['allCaseCensusesComplete'])
                self.assertFalse(case['recordedClassOriginsComplete'])
                self.assertFalse(case['acceptedSavingsCredit'])
                if variant == 'complete':
                    self.assertTrue(report['classOriginSupplementInventory']['allArchiveMemberBytesVerified'])
                    self.assertEqual({'primary', 'companion'}, {r['sourceArchive'] for r in case['classOriginInputs']})
                    self.assertEqual({}, case['omittedClassOriginMembersNotRestored'])
                else:
                    self.assertEqual({omitted}, set(case['omittedClassOriginMembersNotRestored']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
