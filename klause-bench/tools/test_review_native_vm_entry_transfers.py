import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_entry_transfers as review


class ReviewNativeVmEntryTransfersTest(unittest.TestCase):
    def test_direct_targets_keep_boundary_and_capture_span_distinctions(self):
        rows = [{'address': 100 + index, 'bytesHex': '90', 'mnemonic': 'call', 'operands': operand}
                for index, operand in enumerate(['0x64', '0x69', '0x70', '0x80'])]

        result = review.classify(rows, {'value': 100, 'size': 20}, 10, {})

        self.assertEqual(['retained linear instruction boundary',
                          'inside retained bytes without a linear instruction boundary',
                          'inside unretained declared symbol span', 'outside this declared symbol span'],
                         [row['targetStatus'] for row in result['recognizedTransfers']])
        self.assertFalse(result['actualControlFlowCoverageProven'])

    def test_same_address_definitions_retain_every_alias(self):
        aliases = [{'query': name, 'entryRecordIndex': index, 'originalSymbol': {'name': name}}
                   for index, name in enumerate(['first', 'second'])]
        row = {'address': 100, 'bytesHex': '90', 'mnemonic': 'jmp', 'operands': '0x80'}

        result = review.classify([row], {'value': 100, 'size': 1}, 1, {128: aliases})

        self.assertEqual(aliases, result['recognizedTransfers'][0]['capturedTargetDefinitions'])
        self.assertEqual(row, result['recognizedTransfers'][0]['instruction'])

    def test_indirect_and_unsupported_operands_remain_unresolved(self):
        operands = ['rax', 'qword ptr [rax + 8]', '128', '0x10000000000000000', '0x80, rax']
        for operand in operands:
            with self.subTest(operand=operand):
                row = {'address': 100, 'bytesHex': '90', 'mnemonic': 'call', 'operands': operand}

                result = review.classify([row], {'value': 100, 'size': 1}, 1, {})

                transfer = result['recognizedTransfers'][0]
                self.assertIsNone(transfer['target'])
                self.assertEqual('indirect or unsupported operand syntax', transfer['targetStatus'])

    def test_recognized_syntax_and_unrecognized_instructions_are_retained(self):
        categories = [('bnd call', 'call'), ('notrack jmp', 'jump'), ('jne', 'conditional branch syntax'),
                      ('loop', 'loop branch'), ('rep ret', 'return syntax'), ('syscall', 'other transfer syntax')]
        rows = [{'address': index, 'bytesHex': '90', 'mnemonic': mnemonic, 'operands': '0x80'}
                for index, (mnemonic, _) in enumerate(categories)]
        other = {'address': 6, 'bytesHex': '90', 'mnemonic': 'junknown', 'operands': ''}

        result = review.classify(rows + [other], {'value': 0, 'size': 7}, 7, {})

        self.assertEqual([category for _, category in categories],
                         [row['category'] for row in result['recognizedTransfers']])
        self.assertEqual([{'instructionIndex': 6, 'instruction': other}], result['otherInstructions'])

    def test_empty_transfer_inventory_makes_no_control_flow_claim(self):
        row = {'address': 100, 'bytesHex': '90', 'mnemonic': 'nop', 'operands': ''}

        result = review.classify([row], {'value': 100, 'size': 1}, 1, {})

        self.assertEqual([], result['recognizedTransfers'])
        self.assertEqual({}, result['recognizedTransferCounts'])
        self.assertFalse(result['actualControlFlowCoverageProven'])

    def test_partial_linear_decode_retains_its_exact_undecoded_suffix(self):
        entry = {'entryHex': '9091', 'entryBytes': 2, 'entrySha256': hashlib.sha256(b'\x90\x91').hexdigest(),
                 'originalSymbol': {'value': 100}, 'decodedBytes': 1, 'undecodedHex': '91',
                 'instructions': [{'address': 100, 'bytesHex': '90', 'mnemonic': 'nop', 'operands': ''}]}

        result = review.instructions(entry)

        self.assertEqual(entry['instructions'], result)

    def test_inconsistent_saved_instruction_bindings_are_rejected(self):
        entry = {'entryHex': '9091', 'entryBytes': 2, 'entrySha256': hashlib.sha256(b'\x90\x91').hexdigest(),
                 'originalSymbol': {'value': 100}, 'decodedBytes': 1, 'undecodedHex': '91',
                 'instructions': [{'address': 100, 'bytesHex': '90', 'mnemonic': 'nop', 'operands': ''}]}
        variants = [{'entryBytes': True}, {'decodedBytes': True}, {'entrySha256': 'wrong'},
                    {'decodedBytes': 2}, {'undecodedHex': ''},
                    *[{'instructions': [{**entry['instructions'][0], **change}]} for change in
                      [{'address': 101}, {'bytesHex': '91'}, {'bytesHex': ''},
                       {'bytesHex': '90' * 16}, {'mnemonic': ''}, {'operands': 'x' * 513}]]]
        for changes in variants:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                review.instructions({**entry, **changes})

    def test_original_producer_hash_is_authenticated_independently_of_current_reader(self):
        producer = 'a' * 64
        data = {'sourceSha256': producer, 'completed': True, 'diagnosticOnly': True,
                'acceptedSavingsCredit': False, 'actualTargetCallingConventionProven': False,
                **{key: review.LIBRARY_SHA256 for key in
                   ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']},
                'decoder': {'sha256': review.decoder.WHEEL_SHA256, 'version': '5.0.6', 'ready': True,
                            'skipData': False, 'globalInstallation': False, 'providerSha256': 'b' * 64,
                            'providerSha256AfterLoad': 'b' * 64}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'capture.json'
            raw = json.dumps(data).encode()
            path.write_bytes(raw)

            result = review.captured(path, hashlib.sha256(raw).hexdigest(), producer)

            self.assertEqual(data, result)

    def test_changed_capture_provenance_and_expanded_claims_are_rejected(self):
        data = {'sourceSha256': 'a' * 64, 'completed': True, 'diagnosticOnly': True,
                'acceptedSavingsCredit': False, 'actualTargetCallingConventionProven': False,
                **{key: review.LIBRARY_SHA256 for key in
                   ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']},
                'decoder': {'sha256': review.decoder.WHEEL_SHA256, 'version': '5.0.6', 'ready': True,
                            'skipData': False, 'globalInstallation': False, 'providerSha256': 'b' * 64,
                            'providerSha256AfterLoad': 'b' * 64}}
        variants = [{'sourceSha256': 'c' * 64}, {'completed': False}, {'diagnosticOnly': False},
                    {'acceptedSavingsCredit': True}, {'actualTargetCallingConventionProven': True},
                    {'rootCensusProven': True}, {'codeSizeProven': True},
                    {'librarySha256After': 'c' * 64}, {'wrongHash': True},
                    *[{'decoder': {**data['decoder'], **change}} for change in
                      [{'version': 'other'}, {'sha256': 'c' * 64}, {'skipData': True},
                       {'globalInstallation': True}, {'providerSha256AfterLoad': 'c' * 64}]]]
        for changes in variants:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'capture.json'
                raw = json.dumps({**data, **changes}).encode()
                path.write_bytes(raw)
                expected = 'c' * 64 if changes.get('wrongHash') else hashlib.sha256(raw).hexdigest()

                with self.assertRaises(ValueError):
                    review.captured(path, expected, 'a' * 64)

    def test_candidate_inventory_rejects_dimensions_flags_and_missing_records(self):
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 100, 'size': 1}
        for changes in [{'value': True}, {'size': -1}, {'value': 2 ** 64 - 1, 'size': 2},
                        {'sectionIndex': -1}, {'function': 1}, {'undefined': 0}, {'missingRecord': True}]:
            with self.subTest(changes=changes):
                changed = {**symbol, **changes}
                data = {'queries': ['example'], 'symbols': {'candidates': {'example': [changed]}},
                        'entries': {'example': [] if changes.get('missingRecord') else [{'originalSymbol': changed}]}}

                with self.assertRaises(ValueError):
                    review.inventory(data)

    def test_byte_recheck_retains_aliases_partial_decodes_and_original_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library'
            library.write_bytes(b'abcdefgh')
            pinned = hashlib.sha256(b'abcdefgh').hexdigest()
            section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
            segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
            symbols = [{'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                        'sectionIndex': 0, 'value': 102, 'size': 3, 'symbolIndex': index} for index in [1, 2, 3]]
            records = [{'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                        'fileOffset': 2, 'executableSegment': segment, 'entryBytes': 3,
                        'entryHex': b'cde'.hex(), 'entrySha256': hashlib.sha256(b'cde').hexdigest(),
                        'truncated': False, 'decodedBytes': 1, 'undecodedHex': b'de'.hex(),
                        'decoderCoverage': 'stopped before input end',
                        'instructions': [{'address': 102, 'bytesHex': b'c'.hex(),
                                          'mnemonic': 'call', 'operands': '0x66'}]} for symbol in symbols]
            records[-1].update(completed=False, error={'type': 'ValueError', 'message': 'original error'})
            data = {'queries': ['example'], 'decoder': {'retained': 'provenance'},
                    'symbols': {'sections': [section], 'candidates': {'example': symbols}},
                    'programHeaders': [segment], 'entries': {'example': records}}
            report = {}

            with patch.object(review, 'LIBRARY_SHA256', pinned), \
                    patch.object(review.decoder, 'load_decoder') as decoder:
                review.inspect(library, pinned, data, report)

            self.assertTrue(report['completed'])
            self.assertEqual(records, [row['originalEntryCopiedVerbatim'] for row in report['entries']['example']])
            row = report['entries']['example'][0]
            self.assertTrue(row['wholeSymbolCaptured'])
            self.assertFalse(row['allCapturedBytesDecoded'])
            self.assertEqual(3, len(row['transferReview']['recognizedTransfers'][0]['capturedTargetDefinitions']))
            self.assertFalse(report['entries']['example'][-1]['reviewCompleted'])
            decoder.assert_not_called()

    def test_changed_saved_bytes_fail_per_record_without_discarding_input(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library'
            library.write_bytes(b'abcdefgh')
            pinned = hashlib.sha256(b'abcdefgh').hexdigest()
            symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                      'sectionIndex': 0, 'value': 102, 'size': 3}
            record = {'originalSymbol': symbol, 'completed': True, 'entryHex': b'xxx'.hex()}
            data = {'queries': ['example'], 'decoder': {}, 'symbols': {
                    'sections': [{'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}],
                    'candidates': {'example': [symbol]}}, 'programHeaders': [
                        {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}],
                    'entries': {'example': [record]}}
            original = copy.deepcopy(data)
            report = {}

            with patch.object(review, 'LIBRARY_SHA256', pinned):
                review.inspect(library, pinned, data, report)

            self.assertTrue(report['completed'])
            row = report['entries']['example'][0]
            self.assertFalse(row['reviewCompleted'])
            self.assertEqual('ValueError', row['error']['type'])
            self.assertEqual(original, data)

    def test_capture_size_bound_precedes_json_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'capture.json'
            raw = b'x' * 17
            path.write_bytes(raw)

            with patch.object(review, 'MAX_INPUT_BYTES', 16), patch.object(review.json, 'loads') as parse, \
                    self.assertRaises(ValueError):
                review.captured(path, hashlib.sha256(raw).hexdigest(), 'a' * 64)

            parse.assert_not_called()

    def test_failed_library_identity_keeps_report_without_expanding_proof_scope(self):
        data = {'queries': ['example'], 'entries': {'example': []},
                'symbols': {'candidates': {'example': []}}}
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library'
            library.write_bytes(b'abcdefgh')
            output = Path(directory) / 'report.json'
            args = ['review', str(library), review.LIBRARY_SHA256, 'saved.json', 'a' * 64, 'b' * 64, str(output)]

            with patch.object(review.sys, 'argv', args), patch.object(review, 'captured', return_value=data), \
                    patch.object(review.entries, 'entry_bytes') as read, self.assertRaises(ValueError):
                review.main()

            read.assert_not_called()
            report = json.loads(output.read_text())
            for key in ['completed', 'acceptedSavingsCredit', 'actualTargetCallingConventionProven',
                        'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']:
                self.assertFalse(report[key])
            self.assertEqual('ValueError', report['error']['type'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
