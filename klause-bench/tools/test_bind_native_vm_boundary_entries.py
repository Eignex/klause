import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import bind_native_vm_boundary_entries as binding


class NativeVmBoundaryEntriesTest(unittest.TestCase):
    def test_duplicate_source_records_retain_one_physical_definition(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        symbols = [{'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                    'sectionIndex': 0, 'value': 102, 'size': 3, 'symbolIndex': index} for index in [1, 2]]
        matches = [{'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                    'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
                   for symbol in symbols]
        records = [{'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                    'fileOffset': 2, 'executableSegment': segment, 'entryBytes': 3,
                    'entryHex': b'cde'.hex(), 'entrySha256': hashlib.sha256(b'cde').hexdigest(), 'truncated': False}
                   for symbol in symbols]

        result = binding.associate(io.BytesIO(b'abcdefgh'), 8, query, matches, records, [section], [segment], True)

        self.assertTrue(result['uniquePhysicalEntryProven'])
        self.assertEqual([0, 1], [row['sourceMatchIndex'] for row in result['associations']])
        self.assertEqual([{'sourceMatchIndex': 0, 'entryRecordIndex': 0},
                          {'sourceMatchIndex': 1, 'entryRecordIndex': 1}],
                         result['physicalDefinitions'][0]['sourceAndEntryRecords'])
        self.assertEqual(1, len(result['physicalDefinitions']))

    def test_distinct_executable_definitions_remain_ambiguous(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        symbols = [{'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                    'sectionIndex': 0, 'value': 100 + offset, 'size': 3} for offset in [2, 5]]
        matches = [{'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                    'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
                   for symbol in symbols]
        records = [{'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                    'fileOffset': offset, 'executableSegment': segment, 'entryBytes': 3,
                    'entryHex': raw.hex(), 'entrySha256': hashlib.sha256(raw).hexdigest(), 'truncated': False}
                   for symbol, offset, raw in zip(symbols, [2, 5], [b'cde', b'fgh'])]

        result = binding.associate(io.BytesIO(b'abcdefgh'), 8, query, matches, records, [section], [segment], True)

        self.assertFalse(result['uniquePhysicalEntryProven'])
        self.assertEqual(2, len(result['physicalDefinitions']))
        self.assertEqual('physical definition absent or ambiguous', result['status'])

    def test_partial_table_or_entry_coverage_cannot_prove_a_unique_definition(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        match = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                 'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
        entry = {'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                 'fileOffset': 2, 'executableSegment': segment, 'entryBytes': 3,
                 'entryHex': b'cde'.hex(), 'entrySha256': hashlib.sha256(b'cde').hexdigest(), 'truncated': False}
        for records, complete in [([], True), ([{**entry, 'completed': False}], True), ([entry], False),
                                  ([{**entry, 'originalSymbol': {**symbol, 'function': 1}}], True)]:
            with self.subTest(records=records, complete=complete):
                result = binding.associate(io.BytesIO(b'abcdefgh'), 8, query, [match], records,
                                           [section], [segment], complete)

                self.assertFalse(result['uniquePhysicalEntryProven'])

    def test_contradictory_source_claims_are_rejected_before_executable_reads(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        match = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                 'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
        variants = [{'exactSourceSignatureMatched': False}, {'expectedSourceSignature': 'other()'},
                    {'demanglerResult': {'demangled': 'other()'}},
                    {'originalSymbolRecord': {**symbol, 'function': False}},
                    {'originalSymbolRecord': {**symbol, 'undefined': True}},
                    {'originalSymbolRecord': {**symbol, 'ordinarySectionDefinition': False}},
                    {'originalSymbolRecord': {**symbol, 'sectionIndex': True}},
                    {'originalSymbolRecord': {**symbol, 'size': -1}}]
        for changes in variants:
            with self.subTest(changes=changes), patch.object(binding.entries, 'entry_bytes') as read, \
                    self.assertRaises(ValueError):
                binding.associate(io.BytesIO(b'abcdefgh'), 8, query, [{**match, **changes}], [], [{}], [], True)

            read.assert_not_called()

    def test_changed_captured_bytes_or_ownership_are_rejected(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        match = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                 'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
        entry = {'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                 'fileOffset': 2, 'executableSegment': segment, 'entryBytes': 3,
                 'entryHex': b'cde'.hex(), 'entrySha256': hashlib.sha256(b'cde').hexdigest(), 'truncated': False}
        for changes in [{'entryHex': b'xxx'.hex()}, {'entrySha256': 'wrong'}, {'fileOffset': 0},
                        {'entryBytes': 2}, {'truncated': True}, {'byteCoverage': 'bounded prefix'},
                        {'executableSegment': {**segment, 'flags': 4}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                binding.associate(io.BytesIO(b'abcdefgh'), 8, query, [match], [{**entry, **changes}],
                                  [section], [segment], True)

    def test_report_association_keeps_input_bytes_and_respects_available_source_contracts(self):
        for query, supported in [('compiler_thread_loop', True), ('unmatched_scope', False)]:
            with self.subTest(query=query), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                library = out / 'library'
                library.write_bytes(b'abcdefgh')
                pinned = hashlib.sha256(b'abcdefgh').hexdigest()
                symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                          'sectionIndex': 0, 'value': 102, 'size': 3}
                section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
                segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
                entry = {'originalSymbol': symbol, 'completed': True, 'byteCoverage': 'complete symbol span',
                         'fileOffset': 2, 'executableSegment': segment, 'entryBytes': 3, 'entryHex': b'cde'.hex(),
                         'entrySha256': hashlib.sha256(b'cde').hexdigest(), 'truncated': False}
                common = {'completed': True, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                          'actualTargetCallingConventionProven': False, 'expectedLibrarySha256': pinned,
                          'librarySha256': pinned}
                entry_data = {**common, 'sourceSha256': binding.digest(Path(binding.entries.__file__)),
                              'librarySha256After': pinned, 'queries': [query], 'programHeaders': [segment],
                              'symbols': {'hasFullSymbolTable': True, 'symbolTables': [{'completed': True}],
                                          'sections': [section], 'candidates': {query: [symbol]}},
                              'entries': {query: [entry]}}
                expected = binding.signatures.SIGNATURES.get(query)
                match = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': supported,
                         'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
                signature_data = {**common, 'sourceSha256': binding.digest(Path(binding.signatures.__file__)),
                                  'candidates': {query: [match]},
                                  'matchedSourceSignatures': {query: [match]} if supported else {}}
                entry_path, signature_path = out / 'entry.json', out / 'signature.json'
                entry_raw, signature_raw = json.dumps(entry_data).encode(), json.dumps(signature_data).encode()
                entry_path.write_bytes(entry_raw)
                signature_path.write_bytes(signature_raw)
                report = {}

                with patch.object(binding.signatures, 'NativeDemangler') as provider:
                    binding.inspect(library, pinned, entry_path, hashlib.sha256(entry_raw).hexdigest(),
                                    signature_path, hashlib.sha256(signature_raw).hexdigest(), out, report)

                self.assertTrue(report['completed'])
                self.assertEqual(supported, report['allQueriesUniquelyBound'])
                self.assertEqual(entry_raw, (out / 'captured-vm-entries.json').read_bytes())
                self.assertEqual(signature_raw, (out / 'captured-vm-signatures.json').read_bytes())
                provider.assert_not_called()

    def test_capture_identity_or_claim_failures_keep_a_failed_report_without_native_claims(self):
        common = {'completed': True, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                  'actualTargetCallingConventionProven': False,
                  'sourceSha256': binding.digest(Path(binding.entries.__file__))}
        variants = [{'completed': False}, {'diagnosticOnly': False}, {'acceptedSavingsCredit': True},
                    {'actualTargetCallingConventionProven': True}, {'rootCensusProven': True},
                    {'sourceSha256': 'other producer'}, {'wrongHash': True}, {'wrongLibrary': True}]
        for changes in variants:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                library = out / 'library'
                library.write_bytes(b'abcdefgh')
                captured = out / 'input.json'
                raw = json.dumps({**common, **changes}).encode()
                captured.write_bytes(raw)
                expected = 'wrong' if changes.get('wrongHash') else hashlib.sha256(raw).hexdigest()
                output = out / 'report.json'
                library_hash = 'wrong' if changes.get('wrongLibrary') else hashlib.sha256(b'abcdefgh').hexdigest()
                arguments = ['binder', str(library), library_hash,
                             str(captured), expected, str(captured), expected, str(output)]

                with patch.object(binding.sys, 'argv', arguments), patch.object(binding, 'associate') as associate, \
                        self.assertRaises(ValueError):
                    binding.main()

                associate.assert_not_called()
                report = json.loads(output.read_text())
                self.assertFalse(report['completed'])
                self.assertFalse(report['actualTargetCallingConventionProven'])
                self.assertFalse(report['rootCensusProven'])
                self.assertEqual('ValueError', report['error']['type'])

    def test_differing_candidate_inventories_are_rejected_before_association(self):
        query = 'compiler_thread_loop'
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True}
        candidate = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True}
        for entry_rows, signature_rows, matched_rows in [
                ([], [candidate], [candidate]),
                ([{'originalSymbol': symbol}],
                 [{**candidate, 'originalSymbolRecord': {**symbol, 'function': 1}}], [candidate]),
                ([{'originalSymbol': symbol}], [candidate], [])]:
            with self.subTest(entries=entry_rows, candidates=signature_rows, matches=matched_rows), \
                    tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                library = out / 'library'
                library.write_bytes(b'abcdefgh')
                pinned = hashlib.sha256(b'abcdefgh').hexdigest()
                common = {'librarySha256': pinned, 'librarySha256After': pinned, 'expectedLibrarySha256': pinned}
                entry_data = {**common, 'queries': [query], 'entries': {query: entry_rows},
                              'symbols': {'candidates': {query: [symbol]}, 'hasFullSymbolTable': True,
                                          'symbolTables': [{'completed': True}]}}
                signature_data = {**common, 'candidates': {query: signature_rows},
                                  'matchedSourceSignatures': {query: matched_rows}}

                with patch.object(binding, 'capture', side_effect=[entry_data, signature_data]), \
                        patch.object(binding, 'associate') as associate, self.assertRaises(ValueError):
                    binding.inspect(library, pinned, out / 'entries', pinned,
                                    out / 'signatures', pinned, out, {})

                associate.assert_not_called()

    def test_association_budget_is_checked_before_executable_reads(self):
        query = 'compiler_thread_loop'
        expected = binding.signatures.SIGNATURES[query]
        symbol = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        match = {'originalSymbolRecord': symbol, 'exactSourceSignatureMatched': True,
                 'expectedSourceSignature': expected, 'demanglerResult': {'demangled': expected}}
        with patch.object(binding, 'MAX_ASSOCIATIONS', 0), \
                patch.object(binding.entries, 'entry_bytes') as read, self.assertRaises(ValueError):
            binding.associate(io.BytesIO(b'abcdefgh'), 8, query, [match],
                              [{'originalSymbol': symbol, 'completed': True}], [{}], [], True)

        read.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
