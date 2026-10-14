import ctypes
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import read_native_vm_metadata as metadata


class ReadNativeVmMetadataTest(unittest.TestCase):
    def test_rejected_reads_retain_the_address_and_mapping_context(self):
        reader = object.__new__(metadata.MappedReader)
        reader.ranges = [(100, 110)]
        reader.maps = 'captured mappings'
        reader.library_identity = {'path': '/opt/jdk/lib/server/libjvm.so'}

        with self.assertRaises(metadata.MappingReadError) as raised:
            reader.bytes(120, 8)

        self.assertEqual(120, raised.exception.context['address'])
        self.assertEqual('captured mappings', raised.exception.context['maps'])
        self.assertEqual(reader.library_identity, raised.exception.context['libraryIdentity'])

    def test_module_mappings_accept_line_terminators_and_spaces_in_the_path(self):
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)

        for path in ['/opt/jdk/lib/server/libjvm.so', '/opt/jdk with space/lib/server/libjvm.so']:
            with self.subTest(path=path):
                maps = f'1000-2000 r--p 00000000 08:01 7 {path}\n'
                with patch.object(Path, 'stat', return_value=stat), \
                        patch.object(Path, 'read_text', return_value=maps):
                    reader = metadata.MappedReader(Path(path))

                self.assertEqual([(0x1000, 0x2000)], reader.ranges)

    def test_module_mappings_reject_different_device_inode_or_path(self):
        library = Path('/opt/jdk/lib/server/libjvm.so')
        stat = SimpleNamespace(st_dev=os.makedev(8, 1), st_ino=7)

        for device, inode, path in [('08:02', 7, str(library)), ('08:01', 8, str(library)),
                                    ('08:01', 7, '/other/libjvm.so')]:
            with self.subTest(device=device, inode=inode, path=path):
                maps = f'1000-2000 r--p 00000000 {device} {inode} {path}\n'
                with patch.object(Path, 'stat', return_value=stat), \
                        patch.object(Path, 'read_text', return_value=maps):
                    reader = metadata.MappedReader(library)

                self.assertEqual([], reader.ranges)

    def test_reads_outside_the_bound_module_are_rejected_before_memory_access(self):
        reader = object.__new__(metadata.MappedReader)
        reader.ranges = [(100, 110)]

        for address, size in [(0, 1), (99, 1), (109, 2), (100, 0)]:
            with self.subTest(address=address, size=size), patch.object(ctypes, 'string_at') as read:
                with self.assertRaises(ValueError):
                    reader.bytes(address, size)
                read.assert_not_called()

    def test_valid_reads_keep_exact_byte_content(self):
        reader = object.__new__(metadata.MappedReader)
        reader.ranges = [(100, 110)]

        with patch.object(ctypes, 'string_at', return_value=b'\x00\xff'):
            result = reader.bytes(108, 2)

        self.assertEqual(b'\x00\xff', result)

    def test_unterminated_metadata_strings_are_rejected(self):
        reader = object.__new__(metadata.MappedReader)

        with patch.object(reader, 'bytes', return_value=b'x'), patch.object(metadata, 'MAX_STRING', 4):
            with self.assertRaises(ValueError):
                reader.string(100)

    def test_invalid_utf8_metadata_strings_are_rejected(self):
        reader = object.__new__(metadata.MappedReader)

        with patch.object(reader, 'bytes', side_effect=[b'\xff', b'\x00']):
            with self.assertRaises(UnicodeDecodeError):
                reader.string(100)


if __name__ == '__main__':
    unittest.main(verbosity=2)
