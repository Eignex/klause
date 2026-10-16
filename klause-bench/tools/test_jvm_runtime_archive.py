import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import jvm_runtime_archive as runtime


class JvmRuntimeArchiveTest(unittest.TestCase):
    def test_complete_runtime_matches_before_only_image_inputs_are_staged(self):
        files = {'lib/modules': b'modules', 'lib/jrt-fs.jar': b'provider', 'release': b'release', 'bin/java': b'java'}
        with tempfile.TemporaryDirectory() as directory:
            archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
            with tarfile.open(archive, 'w:gz') as stream:
                for name, raw in files.items():
                    member = tarfile.TarInfo('image/' + name)
                    member.size = len(raw)
                    stream.addfile(member, io.BytesIO(raw))
            expected = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
            report = {}

            runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

            self.assertTrue(report['completed'])
            self.assertEqual(expected, report['actualRuntime'])
            self.assertEqual(b'modules', (destination / 'lib/modules').read_bytes())
            self.assertFalse((destination / 'bin/java').exists())

    def test_file_links_retain_their_original_runtime_inventory_paths(self):
        for kind, target in [(tarfile.SYMTYPE, '../release'), (tarfile.LNKTYPE, 'image/release')]:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name in runtime.IMAGE_FILES:
                        member = tarfile.TarInfo('image/' + name)
                        member.size = 1
                        stream.addfile(member, io.BytesIO(b'x'))
                    member = tarfile.TarInfo('image/legal/copy')
                    member.type, member.linkname = kind, target
                    stream.addfile(member)
                expected = {name: hashlib.sha256(b'x').hexdigest() for name in (*runtime.IMAGE_FILES, 'legal/copy')}

                report = {}
                runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

                self.assertEqual(expected, report['actualRuntime'])
                self.assertFalse((destination / 'legal').exists())

    def test_missing_extra_or_changed_runtime_files_prevent_staging(self):
        for variant in ['missing', 'extra', 'changed']:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name in (*runtime.IMAGE_FILES, *(('bin/java',) if variant == 'extra' else ())):
                        member = tarfile.TarInfo('image/' + name)
                        member.size = 1
                        stream.addfile(member, io.BytesIO(b'x'))
                expected = {name: hashlib.sha256(b'x').hexdigest() for name in runtime.IMAGE_FILES}
                if variant == 'missing':
                    expected['bin/java'] = hashlib.sha256(b'x').hexdigest()
                elif variant == 'changed':
                    expected['release'] = 'a' * 64
                report = {}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

                self.assertFalse(report['completed'])
                self.assertFalse(destination.exists())

    def test_unsafe_or_duplicate_archive_paths_prevent_staging(self):
        for name in ['../outside', '/outside', 'image\\outside', 'image/release']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for entry in ['image/release', name]:
                        stream.addfile(tarfile.TarInfo(entry))
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_cyclic_or_external_runtime_links_prevent_staging(self):
        for target in ['modules', '/outside', '../../../outside']:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo('image/lib/modules')
                    member.type, member.linkname = tarfile.SYMTYPE, target
                    stream.addfile(member)
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_archive_member_and_expansion_bounds_prevent_staging(self):
        for bound in ['MAX_ARCHIVE_BYTES', 'MAX_MEMBERS', 'MAX_FILE_BYTES', 'MAX_EXPANDED_BYTES']:
            with self.subTest(bound=bound), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo('image/release')
                    member.size = 1
                    stream.addfile(member, io.BytesIO(b'x'))
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with patch.object(runtime, bound, 0), self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_wrong_archive_identity_prevents_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
            archive.write_bytes(b'not the retained runtime')

            with self.assertRaises(ValueError):
                runtime.prepare_runtime({'provenance': {'runtime': {}}}, archive, 'a' * 64, destination, {})

            self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
