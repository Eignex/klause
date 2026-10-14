import hashlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import native_vm_entry_decoder as package
import read_native_vm_entries as entries


class NativeVmEntriesTest(unittest.TestCase):
    def test_program_table_retains_raw_headers_and_rejects_invalid_spans(self):
        import read_native_vm_symbols as symbols
        header = symbols.ELF_HEADER.pack(b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'),
                                        3, 62, 1, 0, 64, 0, 0, 64, 56, 1, 64, 0, 0)
        program = entries.PROGRAM_HEADER.pack(1, 5, 0, 100, 100, 120, 120, 1)
        raw = header + program
        output = {}

        rows = entries.program_headers(io.BytesIO(raw), len(raw), output)

        self.assertEqual(program.hex(), output['programTableHex'])
        self.assertEqual(100, rows[0]['address'])
        for replacement in [entries.PROGRAM_HEADER.pack(1, 5, 0, 100, 100, 121, 121, 1),
                            entries.PROGRAM_HEADER.pack(1, 5, 0, 100, 100, 120, 119, 1)]:
            with self.subTest(replacement=replacement.hex()), self.assertRaises(ValueError):
                entries.program_headers(io.BytesIO(header + replacement), len(raw), {})

    def test_library_digest_mismatch_prevents_any_entry_read(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'library'
            library.write_bytes(b'unpinned')
            with patch.object(entries.symbols, 'inspect') as inspect, self.assertRaises(ValueError):
                entries.inspect_file(library, 'incorrect', object(), {})
            inspect.assert_not_called()

    def test_function_bytes_require_matching_section_and_executable_segment_ownership(self):
        record = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        output = {}

        result = entries.entry_bytes(io.BytesIO(b'abcdefgh'), 8, record, [section], [segment], output)

        self.assertEqual(b'cde', result)
        self.assertEqual(2, output['fileOffset'])
        self.assertEqual('complete symbol span', output['byteCoverage'])
        self.assertEqual(hashlib.sha256(b'cde').hexdigest(), output['entrySha256'])

    def test_unowned_or_ambiguous_function_spans_are_rejected(self):
        record = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 102, 'size': 3}
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        variants = [({**record, 'size': 7}, section, [segment]),
                    (record, {**section, 'flags': 2}, [segment]),
                    (record, {**section, 'type': 8}, [segment]),
                    (record, section, []), (record, section, [segment, segment]),
                    (record, section, [{**segment, 'flags': 4}]),
                    (record, section, [{**segment, 'fileOffset': 1}]),
                    (record, section, [{**segment, 'fileBytes': 4}])]
        for candidate, owner, programs in variants:
            with self.subTest(candidate=candidate, owner=owner, programs=programs), self.assertRaises(ValueError):
                entries.entry_bytes(io.BytesIO(b'abcdefgh'), 8, candidate, [owner], programs, {})

    def test_undefined_nonfunction_and_zero_size_records_do_not_acquire_inferred_bytes(self):
        record = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 100, 'size': 0}
        for overrides in [{'undefined': True}, {'function': False}, {'ordinarySectionDefinition': False}, {}]:
            with self.subTest(overrides=overrides):
                output = {}
                result = entries.entry_bytes(io.BytesIO(), 0, {**record, **overrides}, [{}], [], output)
                self.assertIsNone(result)
                self.assertNotIn('entryHex', output)
                self.assertIn('byteCoverage', output)

    def test_large_functions_retain_an_explicitly_truncated_prefix(self):
        length = entries.MAX_ENTRY_BYTES + 1
        raw = b'x' * length
        record = {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                  'sectionIndex': 0, 'value': 100, 'size': length}
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': length, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': length, 'fileOffset': 0}
        output = {}

        result = entries.entry_bytes(io.BytesIO(raw), length, record, [section], [segment], output)

        self.assertEqual(entries.MAX_ENTRY_BYTES, len(result))
        self.assertTrue(output['truncated'])
        self.assertEqual('bounded prefix', output['byteCoverage'])

    def test_decoder_stopping_retains_the_undecoded_suffix(self):
        instruction = SimpleNamespace(address=100, bytes=b'a', mnemonic='example', op_str='operand')
        decoder = SimpleNamespace(disasm=lambda raw, address: iter([instruction]))
        output = {}

        entries.decode(b'ab', 100, decoder, output)

        self.assertEqual('stopped before input end', output['decoderCoverage'])
        self.assertEqual(b'b'.hex(), output['undecodedHex'])
        self.assertEqual(1, output['decodedBytes'])

    def test_decoder_cannot_replace_bytes_or_skip_instruction_boundaries(self):
        for address, raw in [(101, b'a'), (100, b'x'), (100, b''), (100, b'abc')]:
            with self.subTest(address=address, raw=raw):
                instruction = SimpleNamespace(address=address, bytes=raw, mnemonic='example', op_str='')
                decoder = SimpleNamespace(disasm=lambda data, start: iter([instruction]))
                with self.assertRaises(ValueError):
                    entries.decode(b'ab', 100, decoder, {})

    def test_missing_decoder_retains_a_failure_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'entries.json'
            with patch.object(entries.sys, 'argv', ['reader', '/unread/library', 'pinned', str(output)]), \
                    patch.object(package, 'load_decoder', side_effect=ImportError('unavailable')), \
                    patch.object(entries, 'inspect_file') as inspect, self.assertRaises(ImportError):
                entries.main()
            report = json.loads(output.read_text())
            inspect.assert_not_called()
            self.assertFalse(report['completed'])
            self.assertFalse(report['actualTargetCallingConventionProven'])
            self.assertEqual('ImportError', report['error']['type'])


class NativeVmEntryDecoderTest(unittest.TestCase):
    def test_unpinned_decoder_bytes_are_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'decoder'
            with self.assertRaises(ValueError):
                package.unpack(b'unpinned', destination, {})
            self.assertFalse(destination.exists())

    def test_unsafe_decoder_members_are_rejected_before_extraction(self):
        for name, mode in [('../escape', 0o100644), ('/absolute', 0o100644),
                           ('capstone\\escape', 0o100644), ('capstone/link', 0o120777)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                stream = io.BytesIO()
                with zipfile.ZipFile(stream, 'w') as archive:
                    item = zipfile.ZipInfo(name)
                    item.external_attr = mode << 16
                    archive.writestr(item, b'x')
                raw = stream.getvalue()
                destination = Path(directory) / 'decoder'
                with patch.object(package, 'WHEEL_SHA256', hashlib.sha256(raw).hexdigest()), self.assertRaises(ValueError):
                    package.unpack(raw, destination, {})
                self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
