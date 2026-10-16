import copy
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import review_native_vm_task_call_flow as review


class NativeVmTaskCallFlowTest(unittest.TestCase):
    def test_detailed_decoder_records_retain_groups_and_typed_operands(self):
        instruction = SimpleNamespace(address=100, bytes=b'\xe8\x5f\0\0\0', id=1,
            mnemonic='call', op_str='0xc8', groups=[2, 7], group_name=lambda value: review.GENERIC_GROUPS[value],
            operands=[SimpleNamespace(type=2, size=8, access=0, imm=200)])
        row = {}

        review.details(instruction, row)

        self.assertEqual([{'id': 2, 'name': 'call'}, {'id': 7, 'name': 'branch_relative'}], row['groups'])
        self.assertEqual([{'type': 2, 'size': 8, 'access': 0, 'immediate': 200}], row['operands'])
        self.assertEqual('e85f000000', row['bytesHex'])

    def test_unknown_or_inconsistent_groups_are_retained_before_declining_detail(self):
        for groups, name in [([8], 'unknown'), ([2], 'jump'), ([2, 2], 'call'), ([2], None)]:
            with self.subTest(groups=groups, name=name):
                instruction = SimpleNamespace(address=100, bytes=b'\x90', id=1, mnemonic='nop', op_str='',
                    groups=groups, group_name=lambda value: name, operands=[])
                row = {}

                with self.assertRaises(ValueError):
                    review.details(instruction, row)

                self.assertEqual(groups, [item['id'] for item in row['groups']])
                self.assertEqual('90', row['bytesHex'])

    def test_direct_calls_retain_their_target_and_separate_normal_return_edge(self):
        for pc, target in [(100, 200), (200, 100)]:
            with self.subTest(pc=pc, target=target):
                row = {'address': pc, 'bytesHex': (b'\xe8' + (target - pc - 5).to_bytes(4, 'little', signed=True)).hex(),
                       'mnemonic': 'call', 'groups': [{'id': 2}],
                       'operands': [{'type': 2, 'immediate': target}]}

                actual = review.normal_edges(row)

                self.assertEqual([{'kind': 'call', 'target': target},
                                  {'kind': 'normal-call-return', 'target': pc + 5}], actual)

    def test_relative_branches_retain_every_normal_target_and_fallthrough(self):
        variants = [('eb02', 104, False), ('e902000000', 107, False), ('7402', 104, True),
                    ('0f8402000000', 108, True), ('e2fe', 100, True)]
        for encoded, target, conditional in variants:
            with self.subTest(encoded=encoded):
                row = {'address': 100, 'bytesHex': encoded, 'mnemonic': 'jump',
                       'groups': [{'id': 1}, {'id': 7}], 'operands': [{'type': 2, 'immediate': target}]}

                actual = review.normal_edges(row)

                expected = [{'kind': 'branch' if conditional else 'jump', 'target': target}]
                if conditional:
                    expected.append({'kind': 'fallthrough', 'target': 100 + len(bytes.fromhex(encoded))})
                self.assertEqual(expected, actual)

    def test_indirect_prefixed_ambiguous_and_unsupported_transfers_remain_unproved(self):
        variants = [('ffd0', [2], [{'type': 1, 'register': 1}], 'call'),
                    ('f2e85e000000', [2], [{'type': 2, 'immediate': 200}], 'bnd call'),
                    ('e85f000000', [1, 2], [{'type': 2, 'immediate': 200}], 'call'),
                    ('e85f000000', [2], [{'type': 2, 'immediate': 201}], 'call'),
                    ('0f05', [4], [], 'syscall'), ('cf', [5], [], 'iret'), ('f4', [6], [], 'hlt'),
                    ('cb', [3], [], 'retf'), ('c3', [], [], 'ret'), ('0f0b', [], [], 'ud2'),
                    ('90', [7], [], 'nop')]
        for encoded, groups, operands, mnemonic in variants:
            with self.subTest(encoded=encoded, groups=groups):
                with self.assertRaises(ValueError):
                    review.normal_edges({'address': 100, 'bytesHex': encoded, 'mnemonic': mnemonic,
                        'groups': [{'id': value} for value in groups], 'operands': operands})

    def test_complete_linear_inventory_retains_normal_call_and_return_boundaries(self):
        rows = [{'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call', 'groups': [{'id': 2}],
                 'operands': [{'type': 2, 'immediate': 200}]},
                {'address': 105, 'bytesHex': 'c3', 'mnemonic': 'ret', 'groups': [{'id': 3}], 'operands': []}]

        actual = review.linear_inventory(rows, 100, 6)

        self.assertTrue(actual['complete'])
        self.assertEqual([{'instructionIndex': 0, 'kind': 'call', 'target': 200},
                          {'instructionIndex': 0, 'kind': 'normal-call-return', 'target': 105}], actual['edges'])

    def test_partial_middle_byte_and_indirect_linear_paths_are_retained_without_completion(self):
        for rows, size in [
            ([{'address': 100, 'bytesHex': '90', 'mnemonic': 'nop', 'groups': [], 'operands': []}], 2),
            ([{'address': 100, 'bytesHex': 'ebff', 'mnemonic': 'jmp', 'groups': [{'id': 1}],
               'operands': [{'type': 2, 'immediate': 101}]}], 2),
            ([{'address': 100, 'bytesHex': 'ffd0', 'mnemonic': 'call', 'groups': [{'id': 2}],
               'operands': [{'type': 1, 'register': 1}]}], 2),
            ([{'address': 100, 'bytesHex': '90', 'mnemonic': 'nop', 'groups': [], 'operands': []}], 1),
        ]:
            with self.subTest(rows=rows, size=size):
                actual = review.linear_inventory(rows, 100, size)

                self.assertFalse(actual['complete'])
                self.assertTrue(actual['errors'])

    def test_a_sole_normal_linear_incoming_edge_never_authorizes_a_native_return_probe(self):
        rows = [{'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call', 'operandsText': '0xc8'},
                {'address': 105, 'bytesHex': 'c3', 'mnemonic': 'ret', 'operandsText': ''}]
        transfer = {'normalCallContinuationProven': True, 'continuation': 105,
                    'instruction': {'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call', 'operands': '0xc8'},
                    'originalTransfer': {'instructionIndex': 0}}
        edge = {'instructionIndex': 0, 'kind': 'normal-call-return', 'target': 105}

        actual = review.continuation_guards({'transfers': [transfer]}, rows,
                                          {'complete': True, 'edges': [edge]}, True)[0]

        self.assertTrue(actual['selectedNormalLinearContinuationEntryBound'])
        self.assertFalse(actual['nativeContinuationExclusiveEntryProven'])
        self.assertFalse(actual['returnProbeAuthorized'])

    def test_joined_incomplete_foreign_and_unbound_continuations_decline_the_linear_guard(self):
        rows = [{'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call', 'operandsText': '0xc8'},
                {'address': 105, 'bytesHex': 'c3', 'mnemonic': 'ret', 'operandsText': ''}]
        transfer = {'normalCallContinuationProven': True, 'continuation': 105,
                    'instruction': {'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call', 'operands': '0xc8'},
                    'originalTransfer': {'instructionIndex': 0}}
        edge = {'instructionIndex': 0, 'kind': 'normal-call-return', 'target': 105}
        for variant in ['join', 'incomplete', 'foreign source', 'foreign instruction', 'outside plan']:
            with self.subTest(variant=variant):
                selected = copy.deepcopy(transfer)
                inventory = {'complete': variant != 'incomplete', 'edges': [edge]}
                if variant == 'join':
                    inventory['edges'].append({'instructionIndex': 2, 'kind': 'jump', 'target': 105})
                elif variant == 'foreign instruction':
                    selected['instruction']['bytesHex'] = 'e85e000000'
                elif variant == 'outside plan':
                    selected['normalCallContinuationProven'] = False

                actual = review.continuation_guards({'transfers': [selected]}, rows, inventory,
                                                  variant != 'foreign source')[0]

                self.assertFalse(actual['selectedNormalLinearContinuationEntryBound'])

    def test_original_input_guards_precede_private_decoder_download_or_library_access(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            path = out / 'input.json'
            path.write_bytes(b'{}')
            with patch.object(review.package, 'load_decoder') as decoder, self.assertRaises(ValueError):
                review.inspect([path] * 4, ['incorrect'] * 4, out / 'unread-library', out, {})
            decoder.assert_not_called()
            self.assertEqual(b'{}', path.read_bytes())

    def test_output_budget_withholds_derived_data_without_turning_a_failure_into_success(self):
        report = {'sourceSha256': 'source', 'diagnosticOnly': True, 'scope': 'diagnostic',
                  **dict.fromkeys(review.DENIED, False), 'completed': True,
                  'callerSemanticRecords': ['retained input reference' * 1000]}
        with patch.object(review, 'MAX_OUTPUT_BYTES', 4096):
            raw = review.report_bytes(report)

        actual = json.loads(raw)
        self.assertLessEqual(len(raw), 4096)
        self.assertFalse(actual['completed'])
        self.assertTrue(actual['derivedInventoryWithheld'])
        self.assertFalse(actual['selectedNormalLinearContinuationEntriesBound'])

    def test_unknown_diet_skip_data_or_foreign_architecture_prevents_library_interpretation(self):
        for diet, skip, arch, mode in [(None, False, 3, 8), (True, False, 3, 8),
                                      (False, True, 3, 8), (False, False, 1, 8), (False, False, 3, 4)]:
            with self.subTest(diet=diet, skip=skip, arch=arch, mode=mode), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                (out / 'captured-vm-entries.json').write_text('{}')
                decoder = SimpleNamespace(skipdata=skip, arch=arch, mode=mode)
                module = SimpleNamespace(CS_SUPPORT_DIET=1, cs_support=lambda value: diet,
                    CS_OPT_SYNTAX_INTEL=1, CS_ARCH_X86=3, CS_MODE_64=8)
                with patch.object(review.calls, 'inspect'), \
                        patch.object(review.package, 'load_decoder', return_value=decoder), \
                        patch.object(review.importlib, 'import_module', return_value=module), \
                        self.assertRaises(ValueError):
                    review.inspect([], [], out / 'unread-library', out, {})

    def test_fatal_detail_failures_clear_partial_new_flags_and_keep_original_input_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            original = Path(directory) / 'original.json'
            original.write_bytes(b'original')
            arguments = ['tool', '/library', review.calls.abi.LIBRARY_SHA256,
                         *sum(([str(original), digest] for digest, _ in review.calls.INPUTS.values()), []), str(output)]
            def fail(paths, expected, library, out, report):
                report.update(allCallerInstructionDetailsBound=True, selectedNormalLinearContinuationEntriesBound=True,
                              callerSemanticRecords=[{'continuationGuards': [
                                  {'selectedNormalLinearContinuationEntryBound': True}]}])
                raise ValueError('incomplete detail')
            with patch.object(sys, 'argv', arguments), patch.object(review, 'inspect', side_effect=fail), \
                    self.assertRaises(ValueError):
                review.main()

            actual = json.loads(output.read_bytes())
            self.assertFalse(actual['completed'])
            self.assertFalse(actual['allCallerInstructionDetailsBound'])
            self.assertFalse(actual['selectedNormalLinearContinuationEntriesBound'])
            self.assertFalse(actual['callerSemanticRecords'][0]['continuationGuards'][0][
                'selectedNormalLinearContinuationEntryBound'])
            self.assertEqual(b'original', original.read_bytes())
            self.assertTrue(all(actual[flag] is False for flag in review.DENIED))

    def test_successful_detail_review_cannot_promote_lifetime_probe_or_compiler_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            arguments = ['tool', '/library', review.calls.abi.LIBRARY_SHA256,
                         *sum(([name, digest] for name, (digest, _) in review.calls.INPUTS.items()), []), str(output)]
            def complete(paths, expected, library, out, report):
                report.update(completed=True, allCallerInstructionDetailsBound=True,
                              selectedNormalLinearContinuationEntriesBound=True)
            with patch.object(sys, 'argv', arguments), patch.object(review, 'inspect', side_effect=complete):
                review.main()

            actual = json.loads(output.read_bytes())
            self.assertTrue(actual['completed'])
            self.assertTrue(actual['selectedNormalLinearContinuationEntriesBound'])
            self.assertTrue(all(actual[flag] is False for flag in review.DENIED))


if __name__ == '__main__':
    unittest.main(verbosity=2)
