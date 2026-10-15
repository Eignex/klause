import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_boundary_lifecycle as review


class ReviewNativeVmBoundaryLifecycleTest(unittest.TestCase):
    def test_complete_sequenced_callbacks_reach_the_terminal_snapshot(self):
        text = ('LIFECYCLE\tEXEC\t1\t1234\t1234\t1\t1000\n'
                'LIFECYCLE\tEXIT\t2\t1234\t1234\t2\n'
                'FINAL_SEQUENCE\t3\t1234\t2\nFINAL_SEQUENCE_TOTAL\t4\t1\t2\nDRAIN_MARKER\t5\n')

        native = review.parse_records(text, 1234)
        delivery = review.terminal_delivery(native, 1234, True)

        self.assertTrue(delivery['selectedSequencedDeliveryProven'])
        self.assertEqual([], delivery['errors'])
        self.assertEqual(2, delivery['finalSequences'][1234]['sequence'])

    def test_missing_duplicate_or_contradictory_terminal_records_refuse_delivery(self):
        events = 'LIFECYCLE\tEXEC\t1\t1234\t1234\t1\t1000\n'
        terminal = 'FINAL_SEQUENCE\t2\t1234\t1\nFINAL_SEQUENCE_TOTAL\t3\t1\t1\nDRAIN_MARKER\t4\n'
        variants = [terminal.replace('FINAL_SEQUENCE\t2\t1234\t1\n', ''),
                    terminal + 'FINAL_SEQUENCE\t2\t1234\t1\n',
                    terminal.replace('FINAL_SEQUENCE\t2\t1234\t1', 'FINAL_SEQUENCE\t2\t1234\t2'),
                    terminal.replace('DRAIN_MARKER\t4', 'DRAIN_MARKER\t0'),
                    terminal + 'FINAL_SEQUENCE_TOTAL\t3\t1\t1\n']
        for changed in variants:
            with self.subTest(terminal=changed):
                delivery = review.terminal_delivery(review.parse_records(events + changed, 1234), 1234, True)

                self.assertFalse(delivery['selectedSequencedDeliveryProven'])
                self.assertTrue(delivery['errors'])

    def test_malformed_and_sequence_inconsistent_callbacks_remain_errors(self):
        variants = ['BOUNDARY\tENTER\tunknown\t1\t1234\t1234\t1\n',
                    'BOUNDARY\tENTER\tcompiler_thread_loop\t1\t999\t1234\t1\n',
                    'BOUNDARY\tENTER\tcompiler_thread_loop\t1\t1234\t1234\t0\n',
                    'BOUNDARY\tENTER\tcompiler_thread_loop\t1\t1234\t1234\t2\n',
                    'LIFECYCLE\tFORK\t1\t1234\t1234\t1\t9\t1235\n',
                    'LIFECYCLE\tEXIT\t2\t1234\t1234\t1\nLIFECYCLE\tEXEC\t1\t1234\t1234\t2\t1000\n',
                    'LIFECYCLE\tEXIT\t1\t1234\t1234\t1\nLIFECYCLE\tEXIT\t1\t1234\t1234\t1\n']
        for text in variants:
            with self.subTest(records=text):
                native = review.parse_records(text, 1234)

                self.assertTrue(native['errors'] or any(group['errors'] for rows in native['threads'] for group in rows))

    def test_public_compilation_keeps_nested_boundaries_and_all_jfr_candidates(self):
        text = ('BOUNDARY\tENTER\tcompiler_thread_loop\t1\t1234\t1234\t1\n'
                'BOUNDARY\tENTER\tinit_compiler_runtime\t2\t1234\t1234\t2\n'
                'USDT\tend\t3\t1234\t1234\t3\t1\n'
                'IDENTITY\tend\t3\t1234\t1234\t3\tcompiler\t2\t4332\n'
                'IDENTITY\tend\t3\t1234\t1234\t3\tclass\t1\t43\n'
                'IDENTITY\tend\t3\t1234\t1234\t3\tmethod\t1\t6d\n'
                'IDENTITY\tend\t3\t1234\t1234\t3\tsignature\t3\t282956\n'
                'BOUNDARY\tLEAVE\tinit_compiler_runtime\t4\t1234\t1234\t4\n'
                'BOUNDARY\tLEAVE\tcompiler_thread_loop\t5\t1234\t1234\t5\n')
        jfr = [{'tid': 1234, 'method': ('C', 'm', '()V'), 'compiler': compiler, 'succeeded': True,
                'compileId': identifier} for identifier, compiler in [(1, 'C2'), (2, 'c2')]]

        thread = review.contexts(review.parse_records(text, 1234), jfr)[0]

        self.assertEqual(['init_compiler_runtime', 'compiler_thread_loop'],
                         [row['boundary'] for row in thread['observedCallbackPairs']])
        item = thread['publicCompilations'][0]
        self.assertEqual(['compiler_thread_loop', 'init_compiler_runtime'], item['observedBoundaryContext'])
        self.assertEqual([0, 1], item['jfrCandidates'])
        self.assertFalse(item['compileIdAssociationProven'])
        self.assertEqual([], thread['unfinishedCallbacks'])

    def test_thread_exit_and_mismatched_returns_do_not_close_observed_frames(self):
        text = ('BOUNDARY\tENTER\tcompiler_thread_loop\t1\t1234\t1234\t1\n'
                'BOUNDARY\tENTER\tinit_compiler_runtime\t2\t1234\t1234\t2\n'
                'BOUNDARY\tLEAVE\tcompiler_thread_loop\t3\t1234\t1234\t3\n'
                'LIFECYCLE\tEXIT\t4\t1234\t1234\t4\n')

        thread = review.contexts(review.parse_records(text, 1234), [])[0]

        self.assertEqual(['compiler_thread_loop', 'init_compiler_runtime'],
                         [row['boundary'] for row in thread['unfinishedCallbacks']])
        self.assertEqual([], thread['observedCallbackPairs'])
        self.assertEqual(1, len(thread['unmatchedReturns']))
        self.assertEqual(['compiler_thread_loop', 'init_compiler_runtime'], thread['threadExits'][0]['openBoundaries'])

    def test_callbacks_after_observed_exit_remain_lifetime_failures(self):
        text = ('LIFECYCLE\tEXIT\t1\t1234\t1234\t1\n'
                'BOUNDARY\tENTER\tcompiler_thread_loop\t2\t1234\t1234\t2\n')

        thread = review.contexts(review.parse_records(text, 1234), [])[0]

        self.assertEqual('Callback after observed thread exit', thread['lifetimeErrors'][0]['error'])
        self.assertEqual('compiler_thread_loop', thread['unfinishedCallbacks'][0]['boundary'])

    def test_delivered_truncated_identities_stay_unassociated(self):
        text = ('USDT\tend\t1\t1234\t1234\t1\t1\n'
                'IDENTITY\tend\t1\t1234\t1234\t1\tcompiler\t2\t4332\n'
                'IDENTITY\tend\t1\t1234\t1234\t1\tclass\t1\t43\n'
                'IDENTITY\tend\t1\t1234\t1234\t1\tmethod\t129\t' + '6d' * 128 + '\n'
                'IDENTITY\tend\t1\t1234\t1234\t1\tsignature\t3\t282956\n'
                'FINAL_SEQUENCE\t2\t1234\t1\nFINAL_SEQUENCE_TOTAL\t3\t1\t1\nDRAIN_MARKER\t4\n')

        native = review.parse_records(text, 1234)
        item = review.contexts(native, [])[0]['publicCompilations'][0]

        self.assertTrue(review.terminal_delivery(native, 1234, True)['selectedSequencedDeliveryProven'])
        self.assertIn('truncated', item['identityError'])
        self.assertEqual([], item['jfrCandidates'])
        self.assertFalse(item['compileIdAssociationProven'])

    def test_missing_public_fragments_prevent_selected_delivery(self):
        text = ('USDT\tbegin\t1\t1234\t1234\t1\t-1\n'
                'FINAL_SEQUENCE\t2\t1234\t1\nFINAL_SEQUENCE_TOTAL\t3\t1\t1\nDRAIN_MARKER\t4\n')

        native = review.parse_records(text, 1234)

        self.assertFalse(review.terminal_delivery(native, 1234, True)['selectedSequencedDeliveryProven'])
        self.assertTrue(native['threads'][0][0]['errors'])

    def test_original_process_review_keeps_missing_counter_outside_delivery_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            live = out / 'live'
            observer = live / 'observer'
            observer.mkdir(parents=True)
            selected = {query: {'name': f'_Zentry{index}v'} for index, query in enumerate(review.capture.BOUNDARIES)}
            library = '/opt/jdk/lib/server/libjvm.so'
            script = review.capture.program(Path(library), 1234, selected).encode()
            captured = {'completed': True, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                        'actualCallingConventionProven': False, 'rootCensusProven': False,
                        'privateArgumentOrFieldReads': False, 'observerImageSha256': review.capture.IMAGE_SHA256,
                        'sourceSha256': review.capture.observer.file_digest(Path(review.capture.__file__)),
                        'library': library, 'selectedEntries': selected,
                        'expectedLibrarySha256': review.capture.LIBRARY_SHA256}
            command = ['java', '-version']
            child = {'pid': 1234, 'uid': 1000, 'gid': 1000, 'groups': [1000],
                     'mountNamespace': 'original', 'command': command}
            launch = {'rootPid': 1234, 'scriptSha256': hashlib.sha256(script).hexdigest(),
                      'librarySha256': review.capture.LIBRARY_SHA256, 'attached': True,
                      'childExit': 0, 'observerExit': 0, 'timedOut': False,
                      'expectedUid': 1000, 'expectedGid': 1000, 'expectedGroups': [1000],
                      'expectedMountNamespace': 'original', 'observerMountNamespace': 'private',
                      'command': command, 'observerBinarySha256': 'app-run', 'lossMonitorRequested': False}
            (live / 'boundary-lifecycle.json').write_text(json.dumps(captured))
            (live / 'captured-boundary-associations.json').write_text('{}')
            (live / 'captured-boundary-entries.json').write_text('{}')
            (observer / 'observer-launch.json').write_text(json.dumps(launch))
            (observer / 'child-before-exec.json').write_text(json.dumps(child))
            (observer / 'observer.bt').write_bytes(script)
            (observer / 'observer.stdout').write_text(
                'VALIDATION_FAULT_EXPECTED\t0\t00\nLIFECYCLE\tEXEC\t1\t1234\t1234\t1\t1000\n'
                'LIFECYCLE\tEXIT\t2\t1234\t1234\t2\nFINAL_SEQUENCE\t3\t1234\t2\n'
                'FINAL_SEQUENCE_TOTAL\t4\t1\t2\nDRAIN_MARKER\t5\n')
            (observer / 'observer.stderr').write_text(
                'observer.bt:1:1-2: WARNING: Bad address\nAdditional Info - helper: probe_read_user, retcode: -14\n')
            (observer / 'appimage-extraction.json').write_text(json.dumps(
                {'packageSha256': review.capture.IMAGE_SHA256, 'exit': 0,
                 'appRunSha256': 'app-run', 'temporaryRemoved': True}))
            (out / 'native_vm_event_loss.py').write_bytes(Path(review.event_loss.__file__).read_bytes())
            (out / 'child-compilations.stdout').write_text(json.dumps({'recording': {'events': []}}))
            report = {}

            with patch.object(review.capture, 'bound_entries', return_value=selected):
                review.inspect(out, report)

            self.assertTrue(report['completed'])
            self.assertTrue(report['delivery']['selectedSequencedDeliveryProven'])
            self.assertFalse(report['lossCounter']['finalLossCounterProven'])
            self.assertFalse(report['lossCounter']['globalLossFreeCaptureProven'])
            self.assertTrue(report['lossCounter']['errors'])

    def test_failed_original_capture_keeps_a_failed_review_without_broader_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            (out / 'live').mkdir()
            (out / 'live/boundary-lifecycle.json').write_text(json.dumps({'completed': False}))
            output = out / 'review.json'
            with patch.object(review.sys, 'argv', ['review', str(out), str(output)]), self.assertRaises(ValueError):
                review.main()

            report = json.loads(output.read_text())
            self.assertFalse(report['completed'])
            self.assertFalse(report['actualCallingConventionProven'])
            self.assertFalse(report['rootCensusProven'])
            self.assertFalse(report['globalLossFreeCaptureProven'])
            self.assertFalse(report['compilerElapsedTimeProven'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
