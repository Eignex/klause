import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_task_generation as review


class NativeVmTaskGenerationReviewTest(unittest.TestCase):
    def test_task_entries_retain_source_names_and_signed_initialization_boundaries(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        name = selected['initialize'][0]['name']
        text = '\n'.join(f'TASK_ENTRY\tinitialize\t{name}\t{index}\t10\t11\t{index}\tffffffffffffffff\t{value}\t0'
                         for index, value in enumerate([-(2 ** 31), -1, 0, 2 ** 31 - 1], 1))

        result = review.parse_records(text, 10, selected)

        self.assertEqual([], result['errors'])
        self.assertEqual([-(2 ** 31), -1, 0, 2 ** 31 - 1],
                         [group['primary'][0]['compileId'] for group in result['threads'][0]])
        self.assertTrue(all(group['primary'][0]['taskPointer'] == 2 ** 64 - 1 and not group['errors']
                            for group in result['threads'][0]))
        self.assertEqual(text.splitlines(), [group['primary'][0]['raw'] for group in result['threads'][0]])

    def test_malformed_task_fields_are_retained_as_errors_without_primary_acceptance(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        original = ['TASK_ENTRY', 'initialize', selected['initialize'][0]['name'], '1', '10', '11', '1', 'ff', '42', '0']
        for position, value in [(1, 'unknown'), (2, 'foreign'), (3, '-1'), (3, str(2 ** 64)),
                                (4, '12'), (5, '0'), (6, '0'), (7, '0x10'), (7, '1' * 17),
                                (8, str(2 ** 31)), (8, str(-(2 ** 31) - 1)), (9, '1')]:
            with self.subTest(position=position, value=value):
                fields = list(original)
                fields[position] = value
                line = '\t'.join(fields)

                result = review.parse_records(line, 10, selected)

                self.assertEqual(line, result['errors'][0]['raw'])
                self.assertEqual([], result['threads'])

    def test_unused_task_arguments_cannot_expand_the_fixed_capture_protocol(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        for role, task, identity, receiver in [('destroy', '1', '-1', '0'), ('free', '1', '42', '0'),
                                               ('broker', '1', '-1', '2')]:
            with self.subTest(role=role):
                line = f'TASK_ENTRY\t{role}\t{selected[role][0]["name"]}\t1\t10\t11\t1\t{task}\t{identity}\t{receiver}'

                result = review.parse_records(line, 10, selected)

                self.assertEqual(line, result['errors'][0]['raw'])

    def test_duplicate_primary_fragments_and_sequence_gaps_block_selected_delivery(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        name = selected['broker'][0]['name']
        for records in [f'TASK_ENTRY\tbroker\t{name}\t1\t10\t11\t2\tff\t-1\t0',
                        '\n'.join([f'TASK_ENTRY\tbroker\t{name}\t1\t10\t11\t1\tff\t-1\t0'] * 2),
                        f'TASK_ENTRY\tbroker\t{name}\t1\t10\t11\t1\tff\t-1\t0\nIDENTITY\tbegin\t1\t10\t11\t1\tcompiler\t2\t4331']:
            with self.subTest(records=records):
                text = records + '\nFINAL_SEQUENCE\t2\t10\t0\nFINAL_SEQUENCE\t2\t11\t1\nFINAL_SEQUENCE_TOTAL\t3\t2\t1\nDRAIN_MARKER\t4'

                native = review.parse_records(text, 10, selected)
                delivery = review.boundary.terminal_delivery(native, 10, True)

                self.assertFalse(delivery['selectedSequencedDeliveryProven'])
                self.assertTrue(native['threads'][0][0]['errors'])

    def test_complete_task_sequences_prove_only_selected_observed_delivery(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        text = f'TASK_ENTRY\tbroker\t{selected["broker"][0]["name"]}\t1\t10\t11\t1\tff\t-1\t0\n' + \
            'FINAL_SEQUENCE\t2\t10\t0\nFINAL_SEQUENCE\t2\t11\t1\nFINAL_SEQUENCE_TOTAL\t3\t2\t1\nDRAIN_MARKER\t4'

        native = review.parse_records(text, 10, selected)
        delivery = review.boundary.terminal_delivery(native, 10, True)

        self.assertTrue(delivery['selectedSequencedDeliveryProven'])
        self.assertFalse(review.initialization_candidates(native)['actualTaskGenerationProven'])
        self.assertFalse(review.boundary.terminal_delivery(native, 10, False)['selectedSequencedDeliveryProven'])
        changed = copy.deepcopy(native)
        changed['otherLines'].append('FINAL_SEQUENCE\t2\t12\t1')
        self.assertFalse(review.boundary.terminal_delivery(changed, 10, True)['selectedSequencedDeliveryProven'])

    def test_public_fragments_keep_truncation_errors_and_unknown_lines(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        lines = ['USDT\tbegin\t1\t10\t11\t1\t-1']
        for label, value in [('compiler', 'C1'), ('class', 'Example'), ('method', 'f'), ('signature', '()V')]:
            lines.append(f'IDENTITY\tbegin\t1\t10\t11\t1\t{label}\t{len(value)}\t{value.encode().hex()}')
        text = '\n'.join(lines + ['UNKNOWN\tretained'])

        result = review.parse_records(text, 10, selected)

        self.assertEqual([], result['threads'][0][0]['errors'])
        self.assertEqual(['UNKNOWN\tretained'], result['otherLines'])
        changed = text.replace('compiler\t2\t4331', 'compiler\t3\t4331')
        self.assertTrue(review.parse_records(changed, 10, selected)['threads'][0][0]['errors'])

    def test_record_bounds_fail_without_a_partial_success(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        with patch.object(review, 'MAX_RECORDS', 1), self.assertRaisesRegex(ValueError, 'raw record inventory'):
            review.parse_records('\n'.join(['unparsed'] * 6), 10, selected)
        for pid, entries in [(True, selected), (10, {}), (10, {**selected, 'free': [{'name': 'foreign'}]})]:
            with self.subTest(pid=pid), self.assertRaises(ValueError):
                review.parse_records('', pid, entries)

    def test_cross_thread_pointer_reuse_keeps_all_initialization_candidates_without_clock_order(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        initialize, broker = selected['initialize'][0]['name'], selected['broker'][0]['name']
        text = '\n'.join([f'TASK_ENTRY\tinitialize\t{initialize}\t1\t10\t12\t1\tff\t40\t0',
                          f'TASK_ENTRY\tinitialize\t{initialize}\t10\t10\t12\t2\tff\t41\t0',
                          f'TASK_ENTRY\tbroker\t{broker}\t5\t10\t11\t1\tff\t-1\t0'])

        result = review.initialization_candidates(review.parse_records(text, 10, selected))

        entry = next(row for row in result['taskEntries'] if row['role'] == 'broker')
        candidates = list(review.eligible_initializations(result, entry))
        self.assertEqual([40, 41], [row['compileId'] for _, row in candidates])
        self.assertEqual([True, False], [row['timestamp'] < entry['timestamp'] for _, row in candidates])
        self.assertFalse(result['crossThreadOrderProven'])
        self.assertTrue(all(not row['actualTaskGenerationProven'] for _, row in candidates))
        self.assertFalse(result['actualTaskGenerationProven'])

    def test_same_thread_sequence_excludes_later_initialization_but_free_does_not_prove_hidden_reuse_absent(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        records = [('initialize', 40), ('free', -1), ('broker', -1), ('initialize', 41)]
        text = '\n'.join(f'TASK_ENTRY\t{role}\t{selected[role][0]["name"]}\t{seq}\t10\t11\t{seq}\tff\t{identity}\t0'
                         for seq, (role, identity) in enumerate(records, 1))

        result = review.initialization_candidates(review.parse_records(text, 10, selected))

        broker = next(row for row in result['taskEntries'] if row['role'] == 'broker')
        candidates = list(review.eligible_initializations(result, broker))
        self.assertEqual([40], [row['compileId'] for _, row in candidates])
        self.assertEqual([10, 11, 1], candidates[0][1]['event'])
        self.assertFalse(broker['actualTaskGenerationProven'])

    def test_null_and_orphan_free_observations_remain_unresolved(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        text = '\n'.join(f'TASK_ENTRY\tfree\t{selected["free"][0]["name"]}\t{seq}\t10\t11\t{seq}\t{pointer}\t-1\t0'
                         for seq, pointer in enumerate(['0', 'ff'], 1))

        result = review.initialization_candidates(review.parse_records(text, 10, selected))

        self.assertEqual(['null task pointer', 'no eligible observed initialization'],
                         [row['unresolvedReason'] for row in result['taskEntries']])
        self.assertTrue(all(not list(review.eligible_initializations(result, row)) and not row['actualTaskGenerationProven']
                            for row in result['taskEntries']))

    def test_candidate_graph_resource_limits_fail_without_choosing_an_initialization(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        text = '\n'.join([f'TASK_ENTRY\tinitialize\t{selected["initialize"][0]["name"]}\t1\t10\t12\t1\tff\t42\t0',
                          f'TASK_ENTRY\tbroker\t{selected["broker"][0]["name"]}\t2\t10\t11\t1\tff\t-1\t0'])
        native = review.parse_records(text, 10, selected)
        with patch.object(review, 'MAX_RELATIONS', 0), self.assertRaisesRegex(ValueError, 'candidate graph'):
            review.initialization_candidates(native)

    def test_observed_wrapper_nesting_keeps_unfinished_and_orphan_boundaries(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        records = [('destroy', '0'), ('construct', 'ff'), ('construct', 'ee'), ('destroy', '0')]
        text = '\n'.join(f'TASK_ENTRY\t{role}\t{selected[role][0]["name"]}\t{seq}\t10\t11\t{seq}\t{pointer}\t-1\t0'
                         for seq, (role, pointer) in enumerate(records, 1)) + '\nLIFECYCLE\tEXIT\t5\t10\t11\t5'
        native = review.parse_records(text, 10, selected)

        result = review.public_contexts(native, review.initialization_candidates(native), [], [])

        self.assertEqual('destruction without construction', result['orphanCallbacks'][0]['reason'])
        self.assertIsNone(result['observedWrappers'][0]['destroy'])
        self.assertIsNotNone(result['observedWrappers'][1]['destroy'])
        self.assertTrue(any('unfinished' in row['reason'] for row in result['observedWrappers'][0]['issues']))
        self.assertTrue(all(not row['actualCompiledTaskLifetimeProven'] for row in result['observedWrappers']))

    def test_callbacks_after_observed_thread_exit_remain_invalid_and_retained(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        text = 'LIFECYCLE\tEXIT\t1\t10\t11\t1\n' + \
            f'TASK_ENTRY\tconstruct\t{selected["construct"][0]["name"]}\t2\t10\t11\t2\tff\t-1\t0'

        native = review.parse_records(text, 10, selected)
        contexts = review.public_contexts(native, review.initialization_candidates(native), [], [])

        self.assertEqual('Task callback after observed thread exit', native['threads'][0][1]['errors'][0])
        self.assertEqual([], contexts['observedWrappers'])
        self.assertEqual((10, 11, 2), tuple(contexts['invalidGroups'][0]['event']))
        self.assertEqual(native['threads'][0][1]['errors'], contexts['invalidGroups'][0]['groupErrors'])
        self.assertEqual(text.splitlines()[1], native['threads'][0][1]['primary'][0]['raw'])

    def test_shared_initialization_tables_reconstruct_every_callback_and_candidate_after_json_roundtrip(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        lines = [f'TASK_ENTRY\tinitialize\t{selected["initialize"][0]["name"]}\t{seq}\t10\t12\t{seq}\tff\t{identity}\t0'
                 for seq, identity in enumerate([40, 41], 1)]
        lines += [f'TASK_ENTRY\t{role}\t{selected[role][0]["name"]}\t{seq + 10}\t10\t11\t{seq}\tff\t-1\t0'
                  for seq, role in enumerate(['broker', 'broker', 'free'], 1)]
        native = review.parse_records('\n'.join(lines), 10, selected)
        graph = review.initialization_candidates(native)

        restored = json.loads(review.report_bytes({'nativeRecords': native, 'graph': graph}))

        groups = {(group['pid'], group['tid'], group['sequence']): group
                  for thread in restored['nativeRecords']['threads'] for group in thread}
        actual = restored['graph']
        self.assertEqual(lines, [groups[tuple(row['event'])]['primary'][row['primaryIndex']]['raw']
                                 for row in actual['taskEntries']])
        self.assertEqual([0, 1], actual['initializationEntries'])
        self.assertEqual([0, 1], actual['pointerInitializationSets'][0]['initializationEntries'])
        for entry in actual['taskEntries'][2:]:
            self.assertEqual([40, 41], [row['compileId'] for _, row in review.eligible_initializations(actual, entry)])
        self.assertFalse(actual['crossThreadOrderProven'])
        self.assertFalse(actual['actualTaskGenerationProven'])

    def test_jfr_identity_references_preserve_original_phase_and_inlining_records(self):
        document = {'recording': {'events': [
            {'type': 'jdk.CompilerPhase', 'values': {'phase': 'parse', 'compileId': 42}},
            {'type': 'jdk.Compilation', 'values': {
                'method': {'type': {'name': 'Example'}, 'name': 'f', 'descriptor': '()V'},
                'compileId': 42, 'compileLevel': 3, 'isOsr': False, 'eventThread': {'osThreadId': 11},
                'succeded': True, 'compiler': 'c1'}},
            {'type': 'jdk.CompilerInlining', 'values': {'compileId': 42, 'succeeded': False, 'message': 'retained'}}]}}
        original = copy.deepcopy(document)

        identities = review.jfr_identities(document)
        restored = json.loads(review.report_bytes({'document': document, 'identities': identities}))

        self.assertEqual(original, restored['document'])
        self.assertEqual(1, restored['identities'][0]['recordingEventIndex'])
        self.assertEqual(original['recording']['events'][1],
                         restored['document']['recording']['events'][restored['identities'][0]['recordingEventIndex']])
        self.assertEqual(42, restored['identities'][0]['compileId'])
        self.assertNotIn('raw', restored['identities'][0])

    def test_unique_public_identity_candidate_never_establishes_a_generation_or_lifetime(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
        records = [f'TASK_ENTRY\tinitialize\t{selected["initialize"][0]["name"]}\t0\t10\t12\t1\tff\t42\t0']
        for seq, role in enumerate(['construct', 'broker', 'begin', 'post', 'end', 'destroy'], 1):
            if role in ('begin', 'end'):
                records.append(f'USDT\t{role}\t{seq}\t10\t11\t{seq}\t' + ('-1' if role == 'begin' else '1'))
                for label, value in [('compiler', 'C1'), ('class', 'Example'), ('method', 'f'), ('signature', '()V')]:
                    records.append(f'IDENTITY\t{role}\t{seq}\t10\t11\t{seq}\t{label}\t{len(value)}\t{value.encode().hex()}')
            else:
                records.append(f'TASK_ENTRY\t{role}\t{selected[role][0]["name"]}\t{seq}\t10\t11\t{seq}\t' +
                               ('0' if role == 'destroy' else 'ff') + '\t-1\t' + ('aa' if role == 'post' else '0'))
        native = review.parse_records('\n'.join(records), 10, selected)
        candidates = review.initialization_candidates(native)
        jfr = [{'compileId': 42, 'tid': 11, 'method': ('Example', 'f', '()V'), 'compiler': 'c1',
                'level': 3, 'isOsr': False, 'succeeded': True}]
        logs = [{'compileId': 42, 'method': ('Example', 'f', '()V'), 'level': 3, 'compileKind': None}]

        result = review.public_contexts(native, candidates, jfr, logs)

        frame = result['observedWrappers'][0]
        self.assertTrue(frame['candidatePublicIdentityUnique'])
        self.assertEqual([0], frame['jfrCandidates'])
        self.assertEqual([0], frame['logCandidates'])
        self.assertFalse(frame['actualTaskGenerationProven'])
        self.assertFalse(frame['actualCompiledTaskLifetimeProven'])
        ambiguous = review.public_contexts(native, candidates, jfr * 2, logs)['observedWrappers'][0]
        self.assertEqual([0, 1], ambiguous['jfrCandidates'])
        self.assertFalse(ambiguous['candidatePublicIdentityUnique'])
        with patch.object(review, 'MAX_RELATIONS', 0), self.assertRaises(OverflowError):
            review.public_contexts(native, candidates, jfr, logs)

    def test_input_bounds_precede_semantic_and_native_access_and_keep_every_original(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            for name in review.INPUTS:
                path = out / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'original')
            with patch.object(review, 'MAX_TOTAL_BYTES', 1), patch.object(review.json, 'loads') as parse, \
                    patch.object(review.capture, 'bound_entries') as bind, self.assertRaises(ValueError):
                review.inspect(out, out / 'library', out, {})

            parse.assert_not_called()
            bind.assert_not_called()
            self.assertTrue(all((out / name).read_bytes() == b'original' for name in review.INPUTS))

    def test_invalid_capture_producer_is_retained_before_any_library_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            original = {'completed': False, 'diagnosticOnly': True, 'sourceSha256': 'foreign'}
            for name in review.INPUTS:
                path = out / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(json.dumps(original).encode() if name == review.INPUTS[0] else b'original')
            report = {}

            with patch.object(review.capture, 'bound_entries') as bind, self.assertRaisesRegex(ValueError, 'producer'):
                review.inspect(out, out / 'library', out, report)

            bind.assert_not_called()
            self.assertEqual(original, report['originalCaptureReport'])
            self.assertEqual(set(review.INPUTS), set(report['inputSha256']))

    def test_saved_source_process_and_counter_guards_require_their_own_complete_evidence(self):
        for mode in ['valid', 'source', 'selection', 'script', 'namespace', 'missing-counter', 'bad-counter']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                selected = {role: [{'name': names[0]}] for role, (names, _) in review.capture.HOOKS.items()}
                library = '/opt/jdk/lib/server/libjvm.so'
                script = review.capture.program(Path(library), 1234, selected).encode()
                captured = {'completed': True, 'diagnosticOnly': True, **{key: False for key in review.DENIED},
                            'sourceSha256': review.capture.observer.file_digest(Path(review.capture.__file__)),
                            'observerImageSha256': review.capture.IMAGE_SHA256,
                            'librarySha256After': review.capture.LIBRARY_SHA256,
                            'expectedLibrarySha256': review.capture.LIBRARY_SHA256, 'library': library,
                            'selectedEntries': selected,
                            'inputSha256': {'lifecycle': review.capture.PLAN_SHA256, 'abi': review.capture.ABI_SHA256}}
                command = ['java', '-version']
                child = {'pid': 1234, 'uid': 1000, 'gid': 1000, 'groups': [1000],
                         'mountNamespace': 'original', 'command': command}
                launch = {'rootPid': 1234, 'scriptSha256': hashlib.sha256(script).hexdigest(),
                          'librarySha256': review.capture.LIBRARY_SHA256, 'attached': True,
                          'childExit': 0, 'observerExit': 0, 'timedOut': False,
                          'expectedUid': 1000, 'expectedGid': 1000, 'expectedGroups': [1000],
                          'expectedMountNamespace': 'original', 'observerMountNamespace': 'private',
                          'command': command, 'observerBinarySha256': 'app-run', 'observerPid': 9,
                          'observerStartTicks': 1, 'lossMonitorRequested': mode != 'missing-counter',
                          'lossMonitorReady': True, 'lossMonitorCompleted': True}
                if mode == 'source':
                    captured['sourceSha256'] = 'foreign'
                if mode == 'namespace':
                    child['mountNamespace'] = 'foreign'
                data = {
                    'live/task-generation-entries.json': json.dumps(captured).encode(),
                    'live/captured-task-lifecycle.json': b'{}', 'live/captured-task-abi.json': b'{}',
                    'live/observer/observer-launch.json': json.dumps(launch).encode(),
                    'live/observer/child-before-exec.json': json.dumps(child).encode(),
                    'live/observer/observer.bt': script + (b'changed' if mode == 'script' else b''),
                    'live/observer/observer.stdout':
                        b'VALIDATION_FAULT_EXPECTED\t0\t00\nLIFECYCLE\tEXEC\t1\t1234\t1234\t1\t1000\n'
                        + f'TASK_ENTRY\tinitialize\t{selected["initialize"][0]["name"]}\t2\t1234\t1234\t2\tff\t42\t0\n'.encode()
                        + b'LIFECYCLE\tEXIT\t3\t1234\t1234\t3\nFINAL_SEQUENCE\t4\t1234\t3\n'
                        b'FINAL_SEQUENCE_TOTAL\t5\t1\t3\nDRAIN_MARKER\t6\n',
                    'live/observer/observer.stderr':
                        b'observer.bt:1:1-2: WARNING: Bad address\nAdditional Info - helper: probe_read_user, retcode: -14\n',
                    'live/observer/appimage-extraction.json': json.dumps({
                        'packageSha256': review.capture.IMAGE_SHA256, 'exit': 0,
                        'appRunSha256': 'app-run', 'temporaryRemoved': True, 'executableSha256': 'executable'}).encode(),
                    'live/observer/event-loss.json': b'{}', 'live/observer/event-loss.btf': b'original btf',
                    'native_vm_event_loss.py': Path(review.event_loss.__file__).read_bytes(),
                    'child-compilations.stdout': b'{"recording":{"events":[]}}',
                    'live/hotspot.xml': b'<hotspot_log><failure reason="retained"/></hotspot_log>'}
                for name, raw in data.items():
                    path = out / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(raw)
                report = {}
                with patch.object(review.capture, 'bound_entries', return_value={} if mode == 'selection' else selected), \
                        patch.object(review.event_loss, 'verify_report',
                                     return_value={'finalLossCounterProven': True, 'finalLossCounterZeroProven': True},
                                     side_effect=ValueError('counter differs') if mode == 'bad-counter' else None):
                    if mode in ['source', 'selection', 'script', 'namespace']:
                        with self.assertRaises(ValueError):
                            review.inspect(out, Path(library), out, report)
                    else:
                        review.inspect(out, Path(library), out, report)

                        self.assertTrue(report['completed'])
                        self.assertTrue(report['delivery']['selectedSequencedDeliveryProven'])
                        self.assertEqual(mode == 'valid', report['observedTaskEntryRecordsValidated'])
                        self.assertEqual(mode == 'valid', report['lossCounter']['finalLossCounterZeroProven'])
                        self.assertEqual('failure', report['independentLogNodeInventory'][1]['tag'])
                        self.assertEqual({'reason': 'retained'}, report['independentLogNodeInventory'][1]['attributes'])
                self.assertTrue(all((out / name).read_bytes() == raw for name, raw in data.items()))

    def test_changed_manifest_fails_before_semantic_review_and_preserves_transport_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            manifest, archive, output = out / 'manifest', out / 'archive', out / 'review.json'
            manifest.write_bytes(b'{}')
            archive.write_bytes(b'original archive')
            arguments = ['review', str(manifest), '0' * 64, str(archive), '/library', str(output)]

            with patch('sys.argv', arguments), patch.object(review, 'inspect') as inspect, self.assertRaises(ValueError):
                review.main()

            inspect.assert_not_called()
            self.assertEqual(b'original archive', archive.read_bytes())
            self.assertEqual(b'{}', manifest.read_bytes())
            self.assertFalse(json.loads(output.read_text())['completed'])

    def test_fatal_saved_review_clears_new_scoped_proofs_and_keeps_original_partial_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            manifest, archive, output = out / 'manifest', out / 'archive', out / 'review.json'
            manifest.write_bytes(b'original manifest')
            archive.write_bytes(b'original archive')
            def inspect(directory, library, destination, report):
                report.update(originalCaptureReport={'original': True}, delivery={'selectedSequencedDeliveryProven': True},
                              lossCounter={'finalLossCounterProven': True, 'finalLossCounterZeroProven': True},
                              actualTaskGenerationProven=True, observedTaskEntryRecordsValidated=True)
                raise ValueError('saved failure')
            arguments = ['review', str(manifest), '0' * 64, str(archive), '/library', str(output)]

            with patch('sys.argv', arguments), patch.object(review.previous, 'extract_inputs', return_value={}), \
                    patch.object(review, 'inspect', side_effect=inspect), self.assertRaises(ValueError):
                review.main()

            report = json.loads(output.read_text())
            self.assertEqual({'original': True}, report['originalCaptureReport'])
            self.assertFalse(report['completed'])
            self.assertFalse(report['observedTaskEntryRecordsValidated'])
            self.assertFalse(report['delivery']['selectedSequencedDeliveryProven'])
            self.assertFalse(report['lossCounter']['finalLossCounterZeroProven'])
            self.assertTrue(all(report[key] is False for key in review.DENIED))

    def test_output_bound_failure_withholds_derived_inventories_without_deleting_saved_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            manifest, archive, output = out / 'manifest', out / 'archive', out / 'review.json'
            manifest.write_bytes(b'original manifest')
            archive.write_bytes(b'original archive')
            def inspect(directory, library, destination, report):
                report.update(completed=True, nativeRecords={'oversized': 'x' * 8192}, observedTaskEntryRecordsValidated=True)
            arguments = ['review', str(manifest), '0' * 64, str(archive), '/library', str(output)]

            with patch('sys.argv', arguments), patch.object(review, 'MAX_OUTPUT_BYTES', 4096), \
                    patch.object(review.previous, 'extract_inputs', return_value={}), \
                    patch.object(review, 'inspect', side_effect=inspect), self.assertRaises(ValueError):
                review.main()

            report = json.loads(output.read_text())
            self.assertFalse(report['completed'])
            self.assertFalse(report['observedTaskEntryRecordsValidated'])
            self.assertNotIn('nativeRecords', report)
            self.assertEqual(b'original archive', archive.read_bytes())
            self.assertEqual(b'original manifest', manifest.read_bytes())
            self.assertLess(output.stat().st_size, 4096)


    def test_compact_output_keeps_complete_records_within_the_same_output_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            manifest, archive, output = out / 'manifest', out / 'archive', out / 'review.json'
            manifest.write_bytes(b'original manifest')
            archive.write_bytes(b'original archive')
            records = {'retained': list(range(300))}
            def inspect(directory, library, destination, report):
                report.update(completed=True, nativeRecords=records, observedTaskEntryRecordsValidated=True)
            arguments = ['review', str(manifest), '0' * 64, str(archive), '/library', str(output)]

            with patch('sys.argv', arguments), patch.object(review, 'MAX_OUTPUT_BYTES', 3000), \
                    patch.object(review.previous, 'extract_inputs', return_value={}), \
                    patch.object(review, 'inspect', side_effect=inspect):
                review.main()

            report = json.loads(output.read_text())
            self.assertTrue(report['completed'])
            self.assertTrue(report['observedTaskEntryRecordsValidated'])
            self.assertEqual(records, report['nativeRecords'])
            self.assertLess(output.stat().st_size, 3000)
            self.assertTrue(all(report[key] is False for key in review.DENIED))


if __name__ == '__main__':
    unittest.main(verbosity=2)
