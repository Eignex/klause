import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import review_native_vm_live_arguments as review


class NativeVmLiveArgumentReviewTest(unittest.TestCase):
    def test_duplicate_primary_sequence_is_retained_as_incomplete(self):
        parsed = review.parse_native('ENTRY\tbroker\t10\t100\t101\t1\t1000\nRETURN\tbroker\t11\t100\t101\t1')

        self.assertIn('missing or duplicate primary record', parsed['threads'][0][0]['errors'])
        self.assertEqual(2, len(parsed['threads'][0][0]['records']))

    def test_missing_sequence_does_not_disappear_when_records_arrive_out_of_order(self):
        parsed = review.parse_native('RETURN\tbroker\t12\t100\t101\t3\nENTRY\tbroker\t10\t100\t101\t1\t1000')

        self.assertEqual([1, 3], [group['sequence'] for group in parsed['threads'][0]])
        self.assertIn('sequence gap or missing initial sequence', parsed['threads'][0][1]['errors'])

    def test_candidate_bytes_require_complete_exported_widths_and_pointer_identity(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': name, 'targetFieldWidth': 4,
                   'typeString': 'int'} for name in review.TASK_FIELDS]
        lines = ['ENTRY\tbroker\t10\t100\t101\t1\t1000',
                 'CANDIDATE\tbroker\t10\t100\t101\t1\ttask\t1000']
        lines += ['FIELD\tbroker\t10\t100\t101\t1\ttask\tCompileTask.' + name + '\t1000\t4\tff ff ff ff'
                  for name in review.TASK_FIELDS]
        group = review.parse_native('\n'.join(lines))['threads'][0][0]

        candidate = review.decode_candidate(group, 'task', 'CompileTask', {'requiredFields': fields})

        self.assertEqual(-1, candidate['fields']['_osr_bci'])
        group['records'][-1]['values'][2] = '2000'
        with self.assertRaises(ValueError):
            review.decode_candidate(group, 'task', 'CompileTask', {'requiredFields': fields})

    def test_truncated_usdt_identity_is_not_accepted(self):
        records = [{'type': 'IDENTITY', 'kind': 'begin', 'values': [label, '2', '61']} for label in
                   ['compiler', 'class', 'method', 'signature']]

        with self.assertRaises(ValueError):
            review.decode_identity({'records': records, 'primary': [{'kind': 'begin'}]})

    def test_actual_jfr_schema_retains_success_and_native_thread_identity(self):
        value = {'compileId': 7, 'compileLevel': 3, 'isOsr': False, 'succeded': True, 'compiler': 'c1',
                 'eventThread': {'osThreadId': 101},
                 'method': {'type': {'name': 'java/lang/Object'}, 'name': '<init>', 'descriptor': '()V'}}

        rows = review.jfr_records({'recording': {'events': [{'type': 'jdk.Compilation', 'values': value}]}})

        self.assertTrue(rows[0]['succeeded'])
        self.assertEqual(101, rows[0]['tid'])
        self.assertEqual(('java.lang.Object', '<init>', '()V'), rows[0]['method'])

    def test_omitted_log_level_is_preserved_without_a_guessed_highest_level(self):
        rows = review.log_records("<hotspot_log><task compile_id='7' method='java.lang.Object f ()V'/></hotspot_log>")

        self.assertIsNone(rows[0]['level'])
        self.assertIsNone(rows[0]['osrBci'])

    def test_additional_macro_read_warning_blocks_the_read_guard(self):
        script = 'BEGIN { printf("VALIDATION_FAULT_EXPECTED", buf(uptr(0), 1)); }'
        expected = '/owned/observer.bt:1:1-10: WARNING: Bad address\n' + \
            'Additional Info - helper: probe_read_user, retcode: -14\n'

        clean = review.read_diagnostics(expected, script, [])
        bad = review.read_diagnostics(expected + 'stdlib/base.bt:393-396: WARNING: Bad address\n', script, [])

        self.assertTrue(clean['invalidReadValidationObserved'])
        self.assertTrue(clean['readWarningGuardPassed'])
        self.assertFalse(bad['readWarningGuardPassed'])
        self.assertFalse(clean['finalLossCounterProven'])

    def test_incomplete_broker_lifetime_is_retained_without_argument_corroboration(self):
        native = review.parse_native('ENTRY\tbroker\t10\t100\t101\t1\t1000')

        result = review.correlate(native, {'invocationEntryBci': {'value': -1}}, [], [], {'readWarningGuardPassed': True})

        self.assertFalse(result['brokerInvocations'][0]['recordedArgumentBindingCorroborated'])
        self.assertEqual('Broker lifetime or USDT pair is incomplete', result['brokerInvocations'][0]['unprovenReason'])
        self.assertEqual('unfinished native invocation', result['nativeLifetimeIssues'][0]['reason'])

    def test_complete_identity_matches_require_read_and_delivery_guards_before_corroboration(self):
        values = [('_method', 8, 'Method*', '00 20 00 00 00 00 00 00'),
                  ('_compile_id', 4, 'int', '07 00 00 00'), ('_osr_bci', 4, 'int', 'ff ff ff ff'),
                  ('_comp_level', 4, 'int', '03 00 00 00'), ('_num_inlined_bytecodes', 4, 'int', '00 00 00 00')]
        fields = [{'typeName': 'CompileTask', 'fieldName': name, 'targetFieldWidth': width, 'typeString': kind}
                  for name, width, kind, raw in values]
        lines = ['ENTRY\tbroker\t1\t100\t101\t1\t1000', 'CANDIDATE\tbroker\t1\t100\t101\t1\ttask\t1000']
        lines += [f'FIELD\tbroker\t1\t100\t101\t1\ttask\tCompileTask.{name}\t1000\t{width}\t{raw}'
                  for name, width, kind, raw in values]
        for sequence, kind, success in [(2, 'begin', -1), (3, 'end', 1)]:
            lines.append(f'USDT\t{kind}\t{sequence}\t100\t101\t{sequence}\t{success}')
            for label, value in [('compiler', 'c1'), ('class', 'p/C'), ('method', 'f'), ('signature', '()V')]:
                lines.append(f'IDENTITY\t{kind}\t{sequence}\t100\t101\t{sequence}\t{label}\t{len(value)}\t{value.encode().hex()}')
        lines.append('RETURN\tbroker\t4\t100\t101\t4')
        native = review.parse_native('\n'.join(lines))
        layout = {'invocationEntryBci': {'value': -1}, 'requiredFields': fields}
        jfr = [{'compileId': 7, 'level': 3, 'isOsr': False, 'tid': 101,
                'method': ('p.C', 'f', '()V'), 'compiler': 'c1', 'succeeded': True}]
        logs = review.log_records("<hotspot_log><task_queued compile_id='7' level='3' method='p.C f ()V'/></hotspot_log>")

        for read, delivery, expected in [(True, True, True), (False, True, False), (True, False, False)]:
            with self.subTest(read=read, delivery=delivery):
                result = review.correlate(native, layout, jfr, logs,
                                          {'readWarningGuardPassed': read, 'sequencedDeliveryGuardPassed': delivery})

                self.assertEqual(expected, result['brokerInvocations'][0]['recordedArgumentBindingCorroborated'])
                self.assertTrue(result['brokerInvocations'][0]['independentIdentityMatched'])
                self.assertEqual(1, result['summary']['independentIdentityMatches'])
                self.assertEqual(int(expected), result['summary']['recordedArgumentBindingsCorroborated'])
                self.assertEqual(jfr, result['brokerInvocations'][0]['jfrMatches'])

    def test_complete_terminal_sequences_preserve_unfinished_callbacks_without_global_loss_credit(self):
        text = ('ENTRY\twrapper-destroy\t1\t100\t101\t1\t1000\n'
                'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t1\n'
                'FINAL_SEQUENCE_TOTAL\t5\t2\t1\nDRAIN_MARKER\t6\n')
        native = review.parse_native(text)

        for read in (True, False):
            with self.subTest(read=read):
                result = review.terminal_delivery(native, {}, 100, {'readWarningGuardPassed': read})

                self.assertEqual(read, result['sequencedNativeDeliveryProven'])
                self.assertFalse(result['globalLossFreeCaptureProven'])
                self.assertEqual([], result['errors'])

    def test_terminal_delivery_refuses_missing_tails_duplicate_snapshots_and_inconsistent_totals(self):
        prefix = 'ENTRY\twrapper-destroy\t1\t100\t101\t1\t1000\n'
        endings = [
            'DRAIN_MARKER\t6\n',
            'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t2\nFINAL_SEQUENCE_TOTAL\t5\t2\t2\nDRAIN_MARKER\t6\n',
            'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t1\nFINAL_SEQUENCE\t4\t101\t1\nFINAL_SEQUENCE_TOTAL\t5\t2\t1\nDRAIN_MARKER\t6\n',
            'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t1\nFINAL_SEQUENCE_TOTAL\t5\t3\t1\nDRAIN_MARKER\t6\n',
            'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t1\nFINAL_SEQUENCE_TOTAL\t5\t2\t0\nDRAIN_MARKER\t6\n',
            'FINAL_SEQUENCE\t3\t100\t0\nFINAL_SEQUENCE\t4\t101\t1\nFINAL_SEQUENCE_TOTAL\t5\t2\t1\nDRAIN_MARKER\t2\n',
        ]
        for ending in endings:
            with self.subTest(ending=ending):
                result = review.terminal_delivery(review.parse_native(prefix + ending), {}, 100,
                                                  {'readWarningGuardPassed': True})

                self.assertFalse(result['sequencedNativeDeliveryProven'])
                self.assertTrue(result['errors'])

    def test_terminal_sequences_do_not_hide_missing_or_extra_null_candidate_fragments(self):
        lines = ['ENTRY\tqueue-get\t1\t100\t101\t1\t1000\t2000',
                 'RETURN\tqueue-get\t2\t100\t101\t2',
                 'CANDIDATE\tqueue-get-return\t2\t100\t101\t2\ttask\t0']
        ending = ['FINAL_SEQUENCE\t3\t100\t0', 'FINAL_SEQUENCE\t4\t101\t2',
                  'FINAL_SEQUENCE_TOTAL\t5\t2\t2', 'DRAIN_MARKER\t6']
        variants = [lines, lines[:-1], lines + [lines[-1]],
                    lines + ['FIELD\tqueue-get-return\t2\t100\t101\t2\ttask\tCompileTask._method\t0\t8\t00 00 00 00 00 00 00 00']]
        for index, records in enumerate(variants):
            with self.subTest(variant=index):
                result = review.terminal_delivery(review.parse_native('\n'.join(records + ending)), {}, 100,
                                                  {'readWarningGuardPassed': True})

                self.assertEqual(index == 0, result['sequencedNativeDeliveryProven'])

    def test_terminal_counters_do_not_mask_missing_or_invalid_nonnull_field_payloads(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': name,
                   'targetFieldWidth': 8 if name == '_method' else 4} for name in review.TASK_FIELDS]
        lines = ['ENTRY\tbroker\t1\t100\t101\t1\t1000',
                 'CANDIDATE\tbroker\t1\t100\t101\t1\ttask\t1000']
        lines += [f'FIELD\tbroker\t1\t100\t101\t1\ttask\tCompileTask.{row["fieldName"]}\t1000\t{row["targetFieldWidth"]}\t' +
                  '00 ' * row['targetFieldWidth'] for row in fields]
        ending = ['FINAL_SEQUENCE\t3\t100\t0', 'FINAL_SEQUENCE\t4\t101\t1',
                  'FINAL_SEQUENCE_TOTAL\t5\t2\t1', 'DRAIN_MARKER\t6']
        variants = [lines, lines[:-1], lines + [lines[-1]],
                    lines[:-1] + [lines[-1].replace('\t4\t', '\t8\t')]]
        for index, records in enumerate(variants):
            with self.subTest(variant=index):
                result = review.terminal_delivery(review.parse_native('\n'.join(records + ending)),
                                                  {'requiredFields': fields}, 100, {'readWarningGuardPassed': True})

                self.assertEqual(index == 0, result['sequencedNativeDeliveryProven'])

    def test_delivered_truncated_identity_remains_complete_bytes_without_becoming_accepted_identity(self):
        lines = ['USDT\tbegin\t1\t100\t101\t1\t-1']
        lines += [f'IDENTITY\tbegin\t1\t100\t101\t1\t{label}\t129\t' + '61 ' * 128
                  for label in ('compiler', 'class', 'method', 'signature')]
        lines += ['FINAL_SEQUENCE\t3\t100\t0', 'FINAL_SEQUENCE\t4\t101\t1',
                  'FINAL_SEQUENCE_TOTAL\t5\t2\t1', 'DRAIN_MARKER\t6']
        native = review.parse_native('\n'.join(lines))

        result = review.terminal_delivery(native, {}, 100, {'readWarningGuardPassed': True})

        self.assertTrue(result['sequencedNativeDeliveryProven'])
        with self.assertRaisesRegex(ValueError, 'truncated'):
            review.decode_identity(native['threads'][0][0])

    def test_primary_clock_order_cannot_contradict_per_thread_sequence(self):
        parsed = review.parse_native('ENTRY\tbroker\t12\t100\t101\t1\t1000\nRETURN\tbroker\t10\t100\t101\t2')

        self.assertIn('primary timestamps contradict sequence order', parsed['threads'][0][1]['errors'])

    def test_exported_fields_require_original_table_identity_and_owner_bounds(self):
        rows = [{'typeName': owner, 'fieldName': name, 'offset': index * 8, 'isStatic': 0,
                 'targetFieldWidth': 8 if name == '_method' else 4,
                 'typeString': 'Method*' if name == '_method' else 'int'}
                for owner, names in [('CompileTask', review.TASK_FIELDS), ('nmethod', review.NMETHOD_FIELDS)]
                for index, name in enumerate(names)]
        original = [{key: value for key, value in row.items() if key != 'targetFieldWidth'} for row in rows]
        layout = {'completed': True, 'librarySha256': review.LIBRARY_SHA256, 'pointerWidth': 8,
                  'requiredFields': rows, 'structTable': {'rows': original},
                  'typeTable': {'rows': [{'typeName': name, 'size': 64} for name in ('CompileTask', 'nmethod')] +
                               [{'typeName': 'int', 'size': 4, 'isIntegerType': 1, 'isUnsigned': 0}]},
                  'invocationEntryBci': {'value': -1}}
        review.validate_layout(layout)

        for changed, value in [('offset', 63), ('isStatic', 1), ('targetFieldWidth', 3)]:
            with self.subTest(changed=changed):
                old = rows[0][changed]
                rows[0][changed] = value
                with self.assertRaises(ValueError):
                    review.validate_layout(layout)
                rows[0][changed] = old

    def test_saved_archive_extracts_only_pinned_required_regular_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'capture.tar.gz'
            contents = {f'profile7/{name}': name.encode() for name in review.INPUTS}
            contents['profile7/unneeded.jfr'] = b'excluded'
            with tarfile.open(archive, 'w:gz') as output:
                for name, raw in contents.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    output.addfile(info, io.BytesIO(raw))
            manifest = {'archive': {'size': archive.stat().st_size,
                                   'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()},
                        'files': {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                  for name, raw in contents.items()}}
            path = root / 'manifest.json'
            path.write_text(json.dumps(manifest))
            digest = hashlib.sha256(path.read_bytes()).hexdigest()

            result = review.extract_inputs(path, digest, archive, root / 'selected', {})

            self.assertEqual(len(review.INPUTS), len(result['selectedMembers']))
            self.assertFalse((root / 'selected' / 'unneeded.jfr').exists())
            self.assertEqual(review.INPUTS[0].encode(), (root / 'selected' / review.INPUTS[0]).read_bytes())
            with self.assertRaisesRegex(ValueError, 'pinned identity'):
                review.extract_inputs(path, '0' * 64, archive, root / 'bad', {})

    def test_saved_archive_refuses_traversal_links_and_duplicate_members(self):
        for name, kind in [('../escape', tarfile.REGTYPE), ('profile7/link', tarfile.SYMTYPE),
                           ('profile7/live/live-layout.json', tarfile.REGTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = root / 'capture.tar.gz'
                files = {f'profile7/{item}': {'size': 0, 'sha256': hashlib.sha256(b'').hexdigest()}
                         for item in review.INPUTS}
                with tarfile.open(archive, 'w:gz') as output:
                    for member in files:
                        output.addfile(tarfile.TarInfo(member), io.BytesIO(b''))
                    info = tarfile.TarInfo(name)
                    info.type = kind
                    info.linkname = '/escape' if kind == tarfile.SYMTYPE else ''
                    output.addfile(info, io.BytesIO(b''))
                manifest = {'archive': {'size': archive.stat().st_size,
                                       'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}, 'files': files}
                path = root / 'manifest.json'
                path.write_text(json.dumps(manifest))

                with self.assertRaisesRegex(ValueError, 'linked, unsafe, duplicate'):
                    review.extract_inputs(path, hashlib.sha256(path.read_bytes()).hexdigest(), archive, root / 'selected', {})


if __name__ == '__main__':
    unittest.main(verbosity=2)
