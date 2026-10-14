import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import read_native_vm_symbols as symbols


class NativeVmSymbolsTest(unittest.TestCase):
    def test_all_candidate_records_are_retained_without_selecting_a_definition(self):
        strings = b'\0total_size\0'
        entries = bytes(24) + b''.join(symbols.SYMBOL.pack(1, info, 0, owner, 0x100, 16)
                                       for info, owner in [(2, 1), (2, 1), (18, 0), (17, 1)])
        for table_type in (2, 11):
            with self.subTest(table_type=table_type):
                header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                                3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
                sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, len(strings), 0, 0, 1, 0) + \
                    symbols.SECTION_HEADER.pack(0, table_type, 0, 0, 256 + len(strings), len(entries), 1, 0, 8, 24)
                raw = header + sections + strings + entries
                report = {}

                symbols.inspect(io.BytesIO(raw), len(raw), report)

                rows = report['candidates']['total_size']
                self.assertEqual(4, len(rows))
                self.assertEqual([0, 0, 1, 1], [row['binding'] for row in rows])
                self.assertEqual([False, False, True, False], [row['undefined'] for row in rows])
                self.assertEqual([True, True, True, False], [row['function'] for row in rows])
                self.assertEqual([1, 2, 3, 4], [row['symbolIndex'] for row in rows])
                self.assertEqual(entries[24:48].hex(), rows[0]['rawEntryHex'])
                self.assertTrue(report['symbolTables'][0]['completed'])

    def test_missing_names_are_scoped_to_the_available_symbol_tables(self):
        for table_type in (2, 11):
            with self.subTest(table_type=table_type):
                header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                                3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
                sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, 1, 0, 0, 1, 0) + \
                    symbols.SECTION_HEADER.pack(0, table_type, 0, 0, 257, 24, 1, 0, 8, 24)
                raw = header + sections + bytes(25)
                report = {}

                symbols.inspect(io.BytesIO(raw), len(raw), report)

                self.assertEqual(table_type == 2, report['hasFullSymbolTable'])
                self.assertEqual({query: 'absent from available tables' for query in symbols.QUERIES},
                                 report['queryCoverage'])

    def test_unsupported_or_out_of_bounds_tables_are_rejected(self):
        header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                        3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
        sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, 1, 0, 0, 1, 0) + \
            symbols.SECTION_HEADER.pack(0, 2, 0, 0, 257, 24, 1, 0, 8, 24)
        raw = header + sections + bytes(25)
        variants = [(4, b'\x01'), (5, b'\x02'), (40, (1000).to_bytes(8, 'little')),
                    (58, (63).to_bytes(2, 'little')), (60, bytes(2)),
                    (60, (4097).to_bytes(2, 'little')), (62, b'\xff\xff'),
                    (64, b'\x01'), (132, (8).to_bytes(4, 'little')),
                    (136, (0x800).to_bytes(8, 'little')), (216, (1000).to_bytes(8, 'little')),
                    (232, (3).to_bytes(4, 'little')), (248, (23).to_bytes(8, 'little')),
                    (256, b'\x01'), (257, b'\x01')]
        for offset, replacement in variants:
            with self.subTest(offset=offset, replacement=replacement.hex()):
                malformed = raw[:offset] + replacement + raw[offset + len(replacement):]

                with self.assertRaises(ValueError):
                    symbols.inspect(io.BytesIO(malformed), len(malformed), {})

    def test_invalid_symbol_names_or_owners_prevent_complete_coverage(self):
        strings = b'\0total_size\0'
        header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                        3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
        sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, len(strings), 0, 0, 1, 0) + \
            symbols.SECTION_HEADER.pack(0, 2, 0, 0, 256 + len(strings), 72, 1, 0, 8, 24)
        valid = symbols.SYMBOL.pack(1, 2, 0, 1, 0x100, 16)
        for name, owner in [(len(strings), 1), (1, 3), (1, 0xffff)]:
            with self.subTest(name=name, owner=owner):
                raw = header + sections + strings + bytes(24) + valid + \
                    symbols.SYMBOL.pack(name, 2, 0, owner, 0x100, 16)
                report = {}

                with self.assertRaises(ValueError):
                    symbols.inspect(io.BytesIO(raw), len(raw), report)

                self.assertEqual(1, len(report['candidates']['total_size']))
                self.assertFalse(report['symbolTables'][0]['completed'])
                self.assertNotIn('queryCoverage', report)

    def test_unterminated_or_oversized_names_are_rejected(self):
        for raw, offset in [(b'\0name', 1), (b'\0' + b'a' * (symbols.MAX_NAME_BYTES + 1) + b'\0', 1),
                            (b'\0', 1), (b'\0', -1)]:
            with self.subTest(size=len(raw), offset=offset), self.assertRaises(ValueError):
                symbols.string_at(raw, offset)

    def test_empty_or_missing_symbol_tables_do_not_establish_absence(self):
        for table_type, table_bytes in [(1, 24), (2, 0)]:
            with self.subTest(table_type=table_type, table_bytes=table_bytes):
                header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                                3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 3, 0)
                sections = bytes(64) + symbols.SECTION_HEADER.pack(0, 3, 0, 0, 256, 1, 0, 0, 1, 0) + \
                    symbols.SECTION_HEADER.pack(0, table_type, 0, 0, 257, table_bytes, 1, 0, 8, 24)
                raw = header + sections + bytes(25)
                report = {}

                with self.assertRaises(ValueError):
                    symbols.inspect(io.BytesIO(raw), len(raw), report)

                self.assertNotIn('queryCoverage', report)

    def test_digest_mismatch_is_rejected_before_symbol_inspection(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library.so'
            library.write_bytes(b'not the pinned library')
            report = {}

            with patch.object(symbols, 'inspect') as inspect, self.assertRaises(ValueError):
                symbols.inspect_file(library, 'incorrect', report)

            inspect.assert_not_called()
            self.assertNotIn('completed', report)

    def test_changed_library_prevents_a_completed_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library.so'
            library.write_bytes(b'original')
            report = {}

            with patch.object(symbols.hashlib, 'file_digest') as digest, \
                    patch.object(symbols, 'inspect', side_effect=lambda *args: library.write_bytes(b'changed')):
                digest.return_value.hexdigest.return_value = 'pinned'
                with self.assertRaises(ValueError):
                    symbols.inspect_file(library, 'pinned', report)

            self.assertNotIn('completed', report)

    def test_failed_cli_capture_retains_partial_records_and_error(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library.so'
            output = Path(directory) / 'symbols.json'

            def fail(library, expected, report):
                report['candidates'] = {'total_size': [{'name': 'retained'}]}
                raise ValueError('incomplete table')

            with patch.object(symbols.sys, 'argv', ['reader', str(library), 'pinned', str(output)]), \
                    patch.object(symbols, 'inspect_file', side_effect=fail), self.assertRaises(ValueError):
                symbols.main()
            report = json.loads(output.read_text())

            self.assertFalse(report['completed'])
            self.assertEqual('retained', report['candidates']['total_size'][0]['name'])
            self.assertEqual({'type': 'ValueError', 'message': 'incomplete table'}, report['error'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
