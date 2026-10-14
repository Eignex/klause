import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_entry_registers as review


class NativeVmEntryRegistersTest(unittest.TestCase):
    def test_exported_reads_follow_only_full_width_incoming_pointer_copies(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]
        instructions = [{'mnemonic': 'mov', 'operands': 'r12, rdi'},
                        {'mnemonic': 'mov', 'operands': 'rax, qword ptr [r12 + 8]'},
                        {'mnemonic': 'call', 'operands': '0x100'},
                        {'mnemonic': 'mov', 'operands': 'rax, qword ptr [rdi + 8]'}]

        result = review.prefix_accesses(instructions, {'rdi': 'CompileTask'}, fields)

        self.assertEqual(1, len(result['matchedExportedReads']))
        self.assertEqual('rdi', result['matchedExportedReads'][0]['incomingRegister'])
        self.assertEqual('r12', result['matchedExportedReads'][0]['baseAlias'])
        self.assertEqual('control-flow boundary: call', result['stopReason'])

    def test_partial_register_writes_invalidate_pointer_aliases(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]
        for register in ['edi', 'di', 'dil']:
            with self.subTest(register=register):
                instructions = [{'mnemonic': 'mov', 'operands': register + ', 0'},
                                {'mnemonic': 'mov', 'operands': 'rax, qword ptr [rdi + 8]'}]
                result = review.prefix_accesses(instructions, {'rdi': 'CompileTask'}, fields)
                self.assertEqual([], result['matchedExportedReads'])

    def test_memory_store_and_address_calculation_are_not_field_reads(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]
        for instruction in [{'mnemonic': 'mov', 'operands': 'qword ptr [rdi + 8], rax'},
                            {'mnemonic': 'lea', 'operands': 'rax, qword ptr [rdi + 8]'},
                            {'mnemonic': 'pop', 'operands': 'qword ptr [rdi + 8]'}]:
            with self.subTest(instruction=instruction):
                result = review.prefix_accesses([instruction], {'rdi': 'CompileTask'}, fields)
                self.assertEqual([], result['matchedExportedReads'])

    def test_fields_require_exact_owner_displacement_and_width(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]
        for operands, owner in [('rax, dword ptr [rdi + 8]', 'CompileTask'),
                                ('rax, qword ptr [rdi + 16]', 'CompileTask'),
                                ('rax, qword ptr [rdi + 8]', 'nmethod'),
                                ('rax, qword ptr [rdi + rsi]', 'CompileTask')]:
            with self.subTest(operands=operands, owner=owner):
                result = review.prefix_accesses([{'mnemonic': 'mov', 'operands': operands}], {'rdi': owner}, fields)
                self.assertEqual([], result['matchedExportedReads'])

    def test_unsupported_instructions_stop_before_unproved_register_effects(self):
        instructions = [{'mnemonic': 'xchg', 'operands': 'rdi, rsi'},
                        {'mnemonic': 'mov', 'operands': 'rax, qword ptr [rdi + 8]'}]
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]

        result = review.prefix_accesses(instructions, {'rdi': 'CompileTask'}, fields)

        self.assertEqual([], result['matchedExportedReads'])
        self.assertEqual('unsupported instruction: xchg', result['stopReason'])

    def test_capture_instruction_boundaries_and_undecoded_suffix_are_verified(self):
        raw = b'ab'
        entry = {'entryHex': raw.hex(), 'entryBytes': 2, 'entrySha256': hashlib.sha256(raw).hexdigest(),
                 'originalSymbol': {'value': 100}, 'instructions': [
                     {'address': 100, 'bytesHex': b'a'.hex(), 'mnemonic': 'example', 'operands': ''}],
                 'decodedBytes': 1, 'undecodedHex': b'b'.hex()}

        self.assertEqual(entry['instructions'], review.bound_instructions(entry))
        for change in [{'decodedBytes': 2}, {'undecodedHex': ''}, {'entryBytes': 1},
                       {'instructions': [{**entry['instructions'][0], 'address': 101}]}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                review.bound_instructions({**entry, **change})

    def test_implicit_multiply_writes_stop_before_using_a_copied_pointer(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]
        instructions = [{'mnemonic': 'mov', 'operands': 'rax, rdi'},
                        {'mnemonic': 'imul', 'operands': 'r12'},
                        {'mnemonic': 'mov', 'operands': 'rsi, qword ptr [rax + 8]'}]

        result = review.prefix_accesses(instructions, {'rdi': 'CompileTask'}, fields)

        self.assertEqual([], result['matchedExportedReads'])
        self.assertEqual('unsupported implicit register writes: imul', result['stopReason'])

    def test_unknown_and_failed_entries_remain_unproved(self):
        entries = {'completed': True, 'librarySha256': review.LIBRARY_SHA256,
                   'librarySha256After': review.LIBRARY_SHA256, 'entries': {'group': [
                       {'originalSymbol': {'name': 'unknown'}, 'completed': True},
                       {'originalSymbol': {'name': next(iter(review.CONTRACTS))}, 'completed': False}]}}
        metadata = {'librarySha256': review.LIBRARY_SHA256, 'requiredLayoutFieldsCopiedVerbatim': [
            {'typeName': 'CompileTask', 'fieldName': '_method', 'isStatic': 0, 'offset': 8, 'targetFieldWidth': 8}]}
        result = {}

        review.inspect(entries, metadata, result)

        rows = result['entries']['group']
        self.assertTrue(result['completed'])
        self.assertTrue(all(not row['reviewCompleted'] and not row['actualTargetCallingConventionProven'] for row in rows))
        self.assertEqual('entry capture unavailable or failed', rows[1]['coverage'])

    def test_unpinned_input_retains_failure_without_reviewing_records(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.json'
            source.write_text('{}')
            output = root / 'review.json'
            args = ['reader', str(source), 'incorrect', str(source), 'incorrect', str(output)]
            with patch.object(review.sys, 'argv', args), patch.object(review, 'inspect') as inspect, \
                    self.assertRaises(ValueError):
                review.main()
            result = json.loads(output.read_text())
            inspect.assert_not_called()
            self.assertFalse(result['completed'])
            self.assertFalse(result['actualTargetCallingConventionProven'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
