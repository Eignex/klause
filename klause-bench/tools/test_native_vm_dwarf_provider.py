import hashlib
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import warnings
import zipfile

import native_vm_dwarf_provider as provider


class NativeVmDwarfProviderTest(unittest.TestCase):
    def test_private_extraction_retains_member_hashes_and_license_text(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            archive.writestr('elftools/__init__.py', '__version__ = "0.33"\n')
            archive.writestr('licenses/LICENSE', 'Public domain test license')
        data = raw.getvalue()
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'provider'
            report = {}

            with patch.object(provider, 'WHEEL_SHA256', hashlib.sha256(data).hexdigest()):
                provider.unpack(data, destination, report)

            self.assertEqual(2, len(report['members']))
            self.assertEqual('Public domain test license', report['licenses'][0]['text'])
            self.assertEqual(hashlib.sha256((destination / 'elftools/__init__.py').read_bytes()).hexdigest(),
                             report['members'][0]['sha256'])

    def test_unsafe_duplicate_native_or_link_members_are_rejected_before_extraction(self):
        for name, duplicate, mode in [('../outside.py', False, 0), ('/outside.py', False, 0),
                                     ('elftools\\outside.py', False, 0), ('elftools/x.py', True, 0),
                                     ('elftools/native.so', False, 0), ('elftools/link', False, 0o120777)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                raw = io.BytesIO()
                with zipfile.ZipFile(raw, 'w') as archive:
                    member = zipfile.ZipInfo(name)
                    member.external_attr = mode << 16
                    archive.writestr(member, b'first')
                    if duplicate:
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            archive.writestr(member, b'second')
                data = raw.getvalue()
                destination = Path(directory) / 'provider'

                with patch.object(provider, 'WHEEL_SHA256', hashlib.sha256(data).hexdigest()), \
                        self.assertRaises(ValueError):
                    provider.unpack(data, destination, {})

                self.assertFalse(destination.exists())

    def test_wrong_hash_precedes_archive_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'provider'
            with patch.object(provider.zipfile, 'ZipFile') as archive, self.assertRaises(ValueError):
                provider.unpack(b'invalid', destination, {})

            archive.assert_not_called()
            self.assertFalse(destination.exists())

    def test_member_and_expanded_size_bounds_precede_extraction(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            archive.writestr('elftools/__init__.py', b'x')
        data = raw.getvalue()
        for member_bound, expanded_bound in [(0, 10), (10, 0)]:
            with self.subTest(member_bound=member_bound), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / 'provider'
                with patch.object(provider, 'WHEEL_SHA256', hashlib.sha256(data).hexdigest()), \
                        patch.object(provider, 'MAX_MEMBERS', member_bound), \
                        patch.object(provider, 'MAX_UNPACKED_BYTES', expanded_bound), self.assertRaises(ValueError):
                    provider.unpack(data, destination, {})

                self.assertFalse(destination.exists())

    def test_changed_private_member_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            (destination / 'member.py').write_bytes(b'changed')
            report = {'members': [{'name': 'member.py', 'sha256': hashlib.sha256(b'original').hexdigest()}]}

            with self.assertRaises(ValueError):
                provider.verify(destination, report)

    def test_imported_module_outside_private_storage_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'private'
            destination.mkdir()
            outside = Path(directory) / 'outside.py'
            outside.write_bytes(b'outside')
            with patch.dict(sys.modules, {'elftools': SimpleNamespace(__file__=str(outside))}), \
                    self.assertRaises(ValueError):
                provider.verify(destination, {'members': []})

    def test_preloaded_provider_is_rejected_before_network_access(self):
        with patch.dict(sys.modules, {'elftools': object()}), patch.object(provider.urllib.request, 'urlopen') as fetch, \
                self.assertRaises(ValueError):
            provider.load(Path('unused'), {})

        fetch.assert_not_called()

    def test_loaded_reader_and_all_imports_come_from_the_private_pinned_wheel(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            for name, source in [('elftools/__init__.py', '__version__ = "0.33"\n'),
                                 ('elftools/elf/__init__.py', ''),
                                 ('elftools/elf/elffile.py', 'class ELFFile:\n    pass\n')]:
                archive.writestr(name, source)
        data = raw.getvalue()
        original_path, original_bytecode = list(sys.path), sys.dont_write_bytecode
        try:
            with tempfile.TemporaryDirectory() as directory:
                report = {}
                with patch.object(provider, 'WHEEL_SHA256', hashlib.sha256(data).hexdigest()), \
                        patch.object(provider.urllib.request, 'urlopen', return_value=io.BytesIO(data)):
                    reader = provider.load(Path(directory), report)

                self.assertEqual('ELFFile', reader.__name__)
                self.assertTrue(report['ready'])
                self.assertFalse(report['globalInstallation'])
                self.assertEqual({'elftools', 'elftools.elf', 'elftools.elf.elffile'},
                                 {row['module'] for row in report['importedModules']})
        finally:
            for name in tuple(sys.modules):
                if name == 'elftools' or name.startswith('elftools.'):
                    del sys.modules[name]
            sys.path[:] = original_path
            sys.dont_write_bytecode = original_bytecode


if __name__ == '__main__':
    unittest.main(verbosity=2)
