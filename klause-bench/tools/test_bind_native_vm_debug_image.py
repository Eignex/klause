import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import bind_native_vm_debug_image as binding


class BindNativeVmDebugImageTest(unittest.TestCase):
    def test_download_requires_exact_pinned_size_and_hash(self):
        raw = b'official archive'
        for expected_size, expected_hash, valid in [(len(raw), hashlib.sha256(raw).hexdigest(), True),
                                                   (len(raw) + 1, hashlib.sha256(raw).hexdigest(), False),
                                                   (len(raw), 'a' * 64, False)]:
            with self.subTest(size=expected_size, valid=valid), tempfile.TemporaryDirectory() as directory:
                report = {}
                with patch.object(binding.urllib.request, 'urlopen', return_value=io.BytesIO(raw)), \
                        patch.object(binding, 'ARCHIVE_BYTES', expected_size), \
                        patch.object(binding, 'ARCHIVE_SHA256', expected_hash):
                    if valid:
                        binding.download(Path(directory) / 'archive', report)
                        self.assertTrue(report['completed'])
                    else:
                        with self.assertRaises(ValueError):
                            binding.download(Path(directory) / 'archive', report)
                        self.assertFalse(report['completed'])

    def test_oversized_download_stops_before_writing_the_excess_chunk(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'archive'
            report = {}
            with patch.object(binding.urllib.request, 'urlopen', return_value=io.BytesIO(b'12345')), \
                    patch.object(binding, 'MAX_ARCHIVE_BYTES', 4), self.assertRaises(ValueError):
                binding.download(path, report)

            self.assertEqual(b'', path.read_bytes())
            self.assertFalse(report['completed'])

    def test_only_the_unique_debug_member_is_extracted_and_hashed(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            archive = out / 'archive.tar.gz'
            with tarfile.open(archive, 'w:gz') as stream:
                for name, raw in [('image/other.debuginfo', b'other'), ('image/lib/server/libjvm.debuginfo', b'123456789')]:
                    member = tarfile.TarInfo(name)
                    member.size = len(raw)
                    stream.addfile(member, io.BytesIO(raw))
            report = {}

            target = binding.extract(archive, out, report)

            self.assertEqual(b'123456789', target.read_bytes())
            self.assertFalse((out / 'image').exists())
            self.assertEqual(hashlib.sha256(b'123456789').hexdigest(), report['selectedSha256'])
            self.assertEqual(0xcbf43926, report['selectedGnuCrc32'])
            self.assertEqual(2, len(report['members']))

    def test_unsafe_or_unsupported_archive_members_are_retained_and_rejected(self):
        for name, kind in [('../libjvm.debuginfo', tarfile.REGTYPE), ('/libjvm.debuginfo', tarfile.REGTYPE),
                           ('image\\libjvm.debuginfo', tarfile.REGTYPE), ('image/link', tarfile.SYMTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                archive = out / 'archive.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    stream.addfile(member)
                report = {}

                with self.assertRaises(ValueError):
                    binding.extract(archive, out, report)

                self.assertFalse(report['completed'])
                self.assertEqual(name, report['members'][0]['name'])
                self.assertFalse((out / 'libjvm.debuginfo').exists())

    def test_missing_duplicate_or_ambiguous_debug_members_are_rejected(self):
        for names in [[], ['other'], ['image/libjvm.debuginfo'] * 2,
                      ['image/libjvm.debuginfo', 'another/libjvm.debuginfo']]:
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                archive = out / 'archive.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name in names:
                        stream.addfile(tarfile.TarInfo(name))

                with self.assertRaises(ValueError):
                    binding.extract(archive, out, {})

                self.assertFalse((out / 'libjvm.debuginfo').exists())

    def test_archive_count_and_declared_size_bounds_precede_extraction(self):
        for count, expanded in [(0, 100), (100, 0)]:
            with self.subTest(count=count, expanded=expanded), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                archive = out / 'archive.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo('image/libjvm.debuginfo')
                    member.size = 1
                    stream.addfile(member, io.BytesIO(b'x'))

                with patch.object(binding, 'MAX_MEMBERS', count), \
                        patch.object(binding, 'MAX_EXPANDED_BYTES', expanded), self.assertRaises(ValueError):
                    binding.extract(archive, out, {})

                self.assertFalse((out / 'libjvm.debuginfo').exists())

    def test_note_inventory_retains_every_gnu_build_id(self):
        raw = binding.NOTE_HEADER.pack(4, 4, 3) + b'GNU\0' + b'abcd'
        other = binding.NOTE_HEADER.pack(4, 4, 1) + b'GNU\0' + b'efgh'

        result = binding.note_ids(raw + other + raw)

        self.assertEqual([b'abcd'.hex(), b'abcd'.hex()], result)

    def test_malformed_or_oversized_build_id_notes_are_rejected(self):
        variants = [b'x', binding.NOTE_HEADER.pack(4, 4, 3) + b'GNU\0',
                    binding.NOTE_HEADER.pack(4, 0, 3) + b'GNU\0',
                    binding.NOTE_HEADER.pack(4, 65, 3) + b'GNU\0' + bytes(68)]
        for raw in variants:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                binding.note_ids(raw)
        with patch.object(binding, 'MAX_NOTE_BYTES', 1), self.assertRaises(ValueError):
            binding.note_ids(bytes(2))

    def test_debug_link_retains_the_filename_raw_bytes_and_little_endian_checksum(self):
        name = b'libjvm.debuginfo\0'
        raw = name + bytes((-len(name)) % 4) + struct.pack('<I', 0x12345678)

        result = binding.debug_link(raw)

        self.assertEqual({'name': 'libjvm.debuginfo', 'crc32': 0x12345678, 'rawHex': raw.hex()}, result)

    def test_debug_link_rejects_missing_wrong_or_malformed_identity(self):
        name = b'libjvm.debuginfo\0'
        valid = name + bytes((-len(name)) % 4) + struct.pack('<I', 1)
        variants = [b'', b'x' * 129 + b'\0' + bytes(4), b'other\0\0\0' + bytes(4),
                    valid[:-1], valid + b'x', b'libjvm.debuginfo' + b'x' + valid[16:]]
        for raw in variants:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                binding.debug_link(raw)

    def test_binding_requires_unique_matching_build_ids_and_complete_debug_checksum(self):
        native = {'buildIds': ['abcd'], 'debugLinks': [{'name': 'libjvm.debuginfo', 'crc32': 7}]}
        variants = [({}, {}, True), ({'buildIds': []}, {}, False), ({'buildIds': ['abcd', 'abcd']}, {}, False),
                    ({}, {'buildIds': ['other']}, False), ({}, {'buildIds': ['abcd', 'abcd']}, False),
                    ({'debugLinks': []}, {}, False), ({'debugLinks': [{'crc32': 8}]}, {}, False),
                    ({'debugLinks': [{'crc32': 7}, {'crc32': 7}]}, {}, False)]
        for left, right, valid in variants:
            with self.subTest(left=left, right=right):
                report = {'library': {}, 'externalDebug': {}, 'completed': False, 'externalDebugFileBound': False}
                with patch.object(binding, 'metadata', side_effect=[None, None]) as inspect:
                    report['library'].update({**native, **left})
                    report['externalDebug'].update({'buildIds': ['abcd'], **right})
                    if valid:
                        binding.bind(Path('library'), Path('debug'), {'selectedSha256': 'a' * 64, 'selectedGnuCrc32': 7}, report)
                        self.assertTrue(report['externalDebugFileBound'])
                    else:
                        with self.assertRaises(ValueError):
                            binding.bind(Path('library'), Path('debug'), {'selectedSha256': 'a' * 64, 'selectedGnuCrc32': 7}, report)
                        self.assertFalse(report['completed'])
                        self.assertFalse(report['externalDebugFileBound'])
                    self.assertEqual(2, inspect.call_count)

    def test_metadata_rejects_changed_file_bytes_after_inspection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'library'
            path.write_bytes(b'original')
            report = {}
            with patch.object(binding.symbols, 'inspect', side_effect=lambda stream, size, out:
                              (out.update(sections=[]), path.write_bytes(b'modified'))), self.assertRaises(ValueError):
                binding.metadata(path, hashlib.sha256(b'original').hexdigest(), report)

            self.assertNotIn('completed', report)

    def test_metadata_rejects_wrong_identity_before_elf_inspection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'library'
            path.write_bytes(b'original')
            with patch.object(binding.symbols, 'inspect') as inspect, self.assertRaises(ValueError):
                binding.metadata(path, 'a' * 64, {})

            inspect.assert_not_called()

    def test_wrong_library_preflight_keeps_failure_report_without_network_or_native_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'library'
            path.write_bytes(b'original')
            output = Path(directory) / 'report.json'
            with patch.object(binding.sys, 'argv', ['binder', str(path), str(output)]), \
                    patch.object(binding, 'download') as download, self.assertRaises(ValueError):
                binding.main()

            download.assert_not_called()
            report = json.loads(output.read_text())
            for key in ['completed', 'externalDebugFileBound', 'acceptedSavingsCredit',
                        'actualTargetCallingConventionProven', 'actualFieldLayoutProven',
                        'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']:
                self.assertFalse(report[key])

    def test_handled_extraction_failure_removes_temporary_archive_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            path = out / 'library'
            path.write_bytes(b'original')
            output = out / 'report.json'
            factory = tempfile.TemporaryDirectory
            with patch.object(binding.sys, 'argv', ['binder', str(path), str(output)]), \
                    patch.object(binding, 'LIBRARY_SHA256', hashlib.sha256(b'original').hexdigest()), \
                    patch.object(binding.tempfile, 'TemporaryDirectory',
                                 side_effect=lambda prefix: factory(prefix=prefix, dir=out)), \
                    patch.object(binding, 'download', side_effect=lambda destination, report:
                                 destination.write_bytes(b'archive')), \
                    patch.object(binding, 'extract', side_effect=ValueError('invalid archive')), \
                    self.assertRaises(ValueError):
                binding.main()

            self.assertEqual({'library', 'report.json'}, {file.name for file in out.iterdir()})
            report = json.loads(output.read_text())
            self.assertFalse(report['completed'])
            self.assertFalse(report['externalDebugFileBound'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
