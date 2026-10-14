import _ctypes
import ctypes
import os
from pathlib import Path
import platform
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native_vm_loaded_module as loader
import read_native_vm_metadata as metadata


class NativeVmLoadedModuleTest(unittest.TestCase):
    def test_readable_anonymous_bytes_require_loaded_segment_ownership(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        module = {'device': stat.st_dev, 'inode': 7, 'baseAddress': 0x1000, 'programHeaderCount': 1,
                  'programHeaders': [{'type': 1, 'flags': 4, 'virtualAddress': 0,
                                      'fileBytes': 0x1000, 'memoryBytes': 0x1800}]}
        maps = '1000-2000 r--p 00000000 08:01 7 /opt/jdk/lib/server/libjvm.so\n' \
               '2000-3000 rw-p 00000000 00:00 0\n'
        with patch.object(Path, 'stat', return_value=stat), patch.object(Path, 'read_text', return_value=maps):
            reader = metadata.MappedReader(library, module)

        with patch.object(ctypes, 'string_at', return_value=b'\x01') as read:
            result = reader.bytes(0x2100, 1)

        self.assertEqual(b'\x01', result)
        read.assert_called_once_with(0x2100, 1)

    def test_anonymous_memory_without_loader_proof_is_rejected(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        maps = '2000-3000 rw-p 00000000 00:00 0\n'
        with patch.object(Path, 'stat', return_value=stat), patch.object(Path, 'read_text', return_value=maps):
            reader = metadata.MappedReader(library)

        with patch.object(ctypes, 'string_at') as read, self.assertRaises(metadata.MappingReadError):
            reader.bytes(0x2100, 1)

        read.assert_not_called()

    def test_memory_beyond_the_loaded_segment_is_rejected_before_access(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        module = {'device': stat.st_dev, 'inode': 7, 'baseAddress': 0x2000, 'programHeaderCount': 1,
                  'programHeaders': [{'type': 1, 'flags': 4, 'virtualAddress': 0,
                                      'fileBytes': 0, 'memoryBytes': 0x800}]}
        maps = '2000-3000 rw-p 00000000 00:00 0\n'
        with patch.object(Path, 'stat', return_value=stat), patch.object(Path, 'read_text', return_value=maps):
            reader = metadata.MappedReader(library, module)

        for address, size in [(0x2800, 1), (0x27ff, 2)]:
            with self.subTest(address=address, size=size), patch.object(ctypes, 'string_at') as read:
                with self.assertRaises(metadata.MappingReadError):
                    reader.bytes(address, size)
                read.assert_not_called()

    def test_loader_ownership_does_not_admit_unreadable_or_foreign_mappings(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        module = {'device': stat.st_dev, 'inode': 7, 'baseAddress': 0x2000, 'programHeaderCount': 1,
                  'programHeaders': [{'type': 1, 'flags': 4, 'virtualAddress': 0,
                                      'fileBytes': 0, 'memoryBytes': 0x1000}]}
        for maps in ['2000-3000 -w-p 00000000 00:00 0\n',
                     '2000-3000 rw-p 00000000 08:01 8 /other/library.so\n',
                     '2000-3000 rw-s 00000000 00:00 0\n']:
            with self.subTest(maps=maps), patch.object(Path, 'stat', return_value=stat), \
                    patch.object(Path, 'read_text', return_value=maps):
                reader = metadata.MappedReader(library, module)
                with patch.object(ctypes, 'string_at') as read, self.assertRaises(metadata.MappingReadError):
                    reader.bytes(0x2100, 1)
                read.assert_not_called()

    def test_loaded_file_identity_must_match_the_bound_library(self):
        identity = {'device': os.makedev(8, 1), 'inode': 7}

        for device, inode in [(os.makedev(8, 2), 7), (identity['device'], 8)]:
            module = {'device': device, 'inode': inode}
            with self.subTest(device=device, inode=inode), self.assertRaises(ValueError):
                metadata.loaded_readable_ranges(module, identity)

    def test_loader_segment_read_permission_is_required(self):
        identity = {'device': os.makedev(8, 1), 'inode': 7}
        module = {**identity, 'baseAddress': 0x2000, 'programHeaderCount': 1,
                  'programHeaders': [{'type': 1, 'flags': 2, 'virtualAddress': 0,
                                      'fileBytes': 0, 'memoryBytes': 0x1000}]}

        with self.assertRaises(ValueError):
            metadata.loaded_readable_ranges(module, identity)

    def test_invalid_loaded_segment_bounds_are_rejected(self):
        identity = {'device': os.makedev(8, 1), 'inode': 7}

        for offset, file_size, memory_size in [(0, 2, 1), (-1, 0, 1), ((1 << 64) - 1, 0, 1)]:
            module = {**identity, 'baseAddress': 0x1000, 'programHeaderCount': 1,
                      'programHeaders': [{'type': 1, 'flags': 4, 'virtualAddress': offset,
                                          'fileBytes': file_size, 'memoryBytes': memory_size}]}
            with self.subTest(offset=offset, file_size=file_size, memory_size=memory_size), \
                    self.assertRaises(ValueError):
                metadata.loaded_readable_ranges(module, identity)

    def test_library_digest_mismatch_is_rejected_before_native_query(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            with patch.object(loader, 'digest', return_value='different'), \
                    patch.object(loader.ctypes, 'CDLL') as query_loader:
                with self.assertRaises(ValueError):
                    loader.capture_loaded_module(Path(_ctypes.__file__), 'expected', out, None, None)

        query_loader.assert_not_called()

    def test_loader_address_read_requires_a_complete_readable_mapping(self):
        maps = '1000-2000 r--p 00000000 00:00 0\n'

        for pointer in [None, 0, 0xfff, 0x1ff9]:
            with self.subTest(pointer=pointer), patch.object(ctypes, 'string_at') as read:
                with self.assertRaises(ValueError):
                    loader.read_loader_address(pointer, maps)
                read.assert_not_called()

    def test_loader_address_read_uses_only_the_documented_first_member(self):
        maps = '1000-2000 rw-p 00000000 00:00 0\n'

        with patch.object(ctypes, 'string_at', return_value=b'\x00\x30\x00\x00\x00\x00\x00\x00') as read:
            base = loader.read_loader_address(0x1100, maps)

        self.assertEqual(0x3000, base)
        read.assert_called_once_with(0x1100, 8)

    def test_loaded_header_bytes_must_equal_the_pinned_file(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        rows = [{'type': 1, 'fileOffset': 0, 'fileBytes': 0x1000, 'virtualAddress': 0}]
        reader = SimpleNamespace(maps='1000-2000 r--p 00000000 08:01 7 /opt/jdk/lib/server/libjvm.so\n',
                                 bytes=lambda address, size: b'different')

        with patch.object(Path, 'stat', return_value=stat), self.assertRaises(ValueError):
            loader.bind_program_headers(library, 0x1000, 0x40, b'expected', rows, reader)

    def test_loader_base_without_the_correct_file_offset_is_rejected(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)
        rows = [{'type': 1, 'fileOffset': 0, 'fileBytes': 0x1000, 'virtualAddress': 0}]
        reader = SimpleNamespace(maps='1000-2000 r--p 00001000 08:01 7 /opt/jdk/lib/server/libjvm.so\n',
                                 bytes=lambda address, size: b'expected')

        with patch.object(Path, 'stat', return_value=stat), self.assertRaises(ValueError):
            loader.bind_program_headers(library, 0x1000, 0x40, b'expected', rows, reader)

    def test_unsupported_elf_shape_is_rejected_before_native_query(self):
        for ident, entry_size, count in [(b'\x7fELF\x01\x01\x01', 56, 1),
                                         (b'\x7fELF\x02\x02\x01', 56, 1),
                                         (b'\x7fELF\x02\x01\x01', 55, 1),
                                         (b'\x7fELF\x02\x01\x01', 56, 0)]:
            with self.subTest(ident=ident, entry_size=entry_size, count=count), \
                    tempfile.TemporaryDirectory() as directory:
                library = Path(directory) / 'unsupported.so'
                library.write_bytes(loader.ELF_HEADER.pack(
                    ident.ljust(16, b'\0'), 3, 62, 1, 0, 64, 0, 0, 64, entry_size, count, 0, 0, 0))
                with self.assertRaises(ValueError):
                    loader.read_program_headers(library)

    def test_program_headers_cannot_exceed_the_library_file(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'truncated.so'
            library.write_bytes(loader.ELF_HEADER.pack(
                b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'), 3, 62, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0))

            with self.assertRaises(ValueError):
                loader.read_program_headers(library)

    @unittest.skipUnless(platform.system() == 'Linux' and platform.machine() == 'x86_64',
                         'Loader verification supports Linux x86_64')
    def test_native_loader_reports_the_loaded_file_identity(self):
        library = Path(_ctypes.__file__).resolve()
        stat = library.stat()
        with tempfile.TemporaryDirectory() as directory:
            loaded = ctypes.CDLL(str(library))
            reader = metadata.MappedReader(library)
            bridge, module = loader.capture_loaded_module(library, loader.digest(library), Path(directory), loaded, reader)

            self.assertEqual(stat.st_dev, module['device'])
            self.assertEqual(stat.st_ino, module['inode'])
            self.assertTrue(module['programHeaders'])
            self.assertIsNotNone(bridge)

    @unittest.skipUnless(platform.system() == 'Linux' and platform.machine() == 'x86_64',
                         'Loader verification supports Linux x86_64')
    def test_same_bytes_in_an_unloaded_file_do_not_prove_module_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            library = out / 'unloaded-library.so'
            library.write_bytes(Path(_ctypes.__file__).read_bytes())

            loaded = ctypes.CDLL(str(Path(_ctypes.__file__).resolve()))
            reader = metadata.MappedReader(library)
            with self.assertRaises(ValueError):
                loader.capture_loaded_module(library, loader.digest(library), out, loaded, reader)

            self.assertFalse((out / 'loaded-module.json').exists())
            self.assertTrue((out / 'loader-query.json').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
