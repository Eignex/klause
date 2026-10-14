import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import read_native_vm_signatures as signatures
import read_native_vm_symbols as symbols


class NativeVmSymbolQueriesTest(unittest.TestCase):
    def test_query_identifiers_reject_ambiguous_or_unbounded_requests(self):
        for values in [[], ['same', 'same'], ['x'] * 17, ['x' * 129], ['x y'], ['x*'], ['x::y'], ['å'], [''], [[]]]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                symbols.query_names(values)

    def test_source_identifier_queries_are_preserved_in_order(self):
        self.assertEqual(('CompileQueue', 'CompileTaskWrapper'),
                         symbols.query_names(['CompileQueue', 'CompileTaskWrapper']))

    def test_invalid_cli_query_retains_its_failure_without_reading_the_library(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'symbols.json'
            arguments = ['reader', '/unread/library', 'pinned', str(output), 'invalid*']

            with patch.object(symbols.sys, 'argv', arguments), patch.object(symbols, 'inspect_file') as inspect, \
                    self.assertRaises(ValueError):
                symbols.main()
            report = json.loads(output.read_text())

            inspect.assert_not_called()
            self.assertFalse(report['completed'])
            self.assertEqual(['invalid*'], report['queries'])
            self.assertEqual('ValueError', report['error']['type'])

    def test_requested_source_identifiers_are_scanned_without_replacing_raw_records(self):
        strings = b'\0CompileQueue_add\0'
        entries = bytes(24) + symbols.SYMBOL.pack(1, 2, 0, 1, 0x100, 16)
        header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                        3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
        sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, len(strings), 0, 0, 1, 0) + \
            symbols.SECTION_HEADER.pack(0, 2, 0, 0, 256 + len(strings), len(entries), 1, 0, 8, 24)
        raw = header + sections + strings + entries
        report = {}

        symbols.inspect(io.BytesIO(raw), len(raw), report, ('CompileQueue', 'CompileTaskWrapper'))

        self.assertEqual(['CompileQueue', 'CompileTaskWrapper'], list(report['candidates']))
        self.assertEqual('CompileQueue_add', report['candidates']['CompileQueue'][0]['name'])
        self.assertEqual(entries[24:].hex(), report['candidates']['CompileQueue'][0]['rawEntryHex'])
        self.assertEqual('absent from available tables', report['queryCoverage']['CompileTaskWrapper'])
        self.assertNotIn('total_size', report['candidates'])


class NativeVmCapturedQueryGroupsTest(unittest.TestCase):
    def test_unknown_source_queries_retain_demangled_records_without_signature_claims(self):
        source = {'completed': True, 'librarySha256': 'pinned', 'librarySha256After': 'pinned',
                  'queries': ['CompileQueue'], 'candidates': {'CompileQueue': [
                      {'name': '_Z1fv', 'nameHex': b'_Z1fv'.hex(), 'function': True,
                       'undefined': False, 'ordinarySectionDefinition': True}]}}
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            captured = out / 'input.json'
            raw = json.dumps(source).encode()
            captured.write_bytes(raw)
            report = {}
            with patch.object(signatures, 'digest', return_value='pinned'), \
                    patch.object(signatures, 'NativeDemangler') as constructor:
                constructor.return_value.provenance = {}
                constructor.return_value.read.return_value = {'demangled': 'f()', 'status': 0}

                signatures.inspect(out / 'library', 'pinned', captured, hashlib.sha256(raw).hexdigest(), out, report)

            item = report['candidates']['CompileQueue'][0]
            self.assertEqual('f()', item['demanglerResult']['demangled'])
            self.assertIsNone(item['expectedSourceSignature'])
            self.assertFalse(item['exactSourceSignatureMatched'])
            self.assertEqual({}, report['matchedSourceSignatures'])
            self.assertTrue(report['completed'])

    def test_invalid_captured_query_groups_are_rejected_before_loading_the_provider(self):
        for queries in [[], ['same', 'same'], [['not a string']], ['x*'], ['x' * 129]]:
            with self.subTest(queries=queries), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                captured = out / 'input.json'
                raw = json.dumps({'completed': True, 'librarySha256': 'pinned',
                                  'librarySha256After': 'pinned', 'queries': queries, 'candidates': {}}).encode()
                captured.write_bytes(raw)
                with patch.object(signatures, 'digest', return_value='pinned'), \
                        patch.object(signatures, 'NativeDemangler') as constructor, self.assertRaises(ValueError):
                    signatures.inspect(out / 'library', 'pinned', captured, hashlib.sha256(raw).hexdigest(), out, {})

                constructor.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
