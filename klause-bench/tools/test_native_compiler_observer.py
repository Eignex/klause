import json
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

import native_compiler_observer as observer


class NativeCompilerObserverTest(unittest.TestCase):
    def test_cleanup_refuses_visible_mounts_without_removing_extracted_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            for name in ['tool', 'tool with space']:
                with self.subTest(name=name):
                    directory = Path(temporary) / name
                    directory.mkdir()
                    sentinel = directory / 'retained'
                    sentinel.write_bytes(b'evidence')
                    point = str(directory / 'mountroot').replace(' ', r'\040')
                    mountinfo = f'1 0 0:1 / {point} rw - tmpfs tmpfs rw\n'
                    with patch.object(Path, 'read_text', return_value=mountinfo):
                        with self.assertRaises(RuntimeError):
                            observer.remove_extraction(directory, {})

                    self.assertEqual(b'evidence', sentinel.read_bytes())

    def test_cleanup_does_not_follow_symlinks_outside_the_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            retained = root / 'retained'
            retained.write_bytes(b'evidence')
            directory = root / 'extracted'
            directory.mkdir()
            (directory / 'link').symlink_to(retained)

            observer.remove_extraction(directory, {})

            self.assertEqual(b'evidence', retained.read_bytes())

    def test_failed_extraction_does_not_launch_the_observed_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / 'image'
            image.write_bytes(b'package')
            destination = root / 'out/observer'
            with patch.object(observer.subprocess, 'run', return_value=CompletedProcess([], 1)), \
                    patch.object(observer, 'observe') as launch:
                with self.assertRaises(AssertionError):
                    observer.observe_appimage(destination, Path('/opt/jdk/lib/server/libjvm.so'),
                                              image, ['java', '-version'], 120)

            launch.assert_not_called()
            self.assertEqual(1, json.loads((destination / 'appimage-extraction.json').read_text())['exit'])
            self.assertEqual([], list(root.glob('bpftrace-tool-*')))

    def test_extracted_package_is_removed_after_observer_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / 'image'
            image.write_bytes(b'package')
            destination = root / 'out/observer'

            def extract(command, *, cwd, **kwargs):
                unpacked = Path(cwd) / 'squashfs-root'
                executable = unpacked / 'nix/store/tool/bin/bpftrace'
                executable.parent.mkdir(parents=True)
                executable.write_bytes(b'compiler observer')
                (unpacked / 'AppRun').write_bytes(b'entry point')
                (unpacked / 'entrypoint').symlink_to('/nix/store/tool/bin/bpftrace')
                return CompletedProcess(command, 0)

            with patch.object(observer.subprocess, 'run', side_effect=extract), \
                    patch.object(observer, 'observe') as launch:
                observer.observe_appimage(destination, Path('/opt/jdk/lib/server/libjvm.so'),
                                          image, ['java', '-version'], 120)

            launch.assert_called_once()
            receipt = json.loads((destination / 'appimage-extraction.json').read_text())
            self.assertEqual('/nix/store/tool/bin/bpftrace', receipt['entrypoint'])
            self.assertFalse(Path(receipt['appRun']).exists())
            self.assertEqual([], list(root.glob('bpftrace-tool-*')))

    def test_identity_fields_keep_their_declared_lengths_with_bounded_byte_reads(self):
        script = observer.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234)

        for pointer, length in [(0, 1), (2, 3), (4, 5), (6, 7)]:
            with self.subTest(pointer=pointer):
                self.assertIn(f'arg{length}, buf(uptr(arg{pointer}), ', script)
                self.assertIn(f'arg{length} < 128 ? arg{length} : 128', script)
        self.assertNotIn('str(', script)

    def test_probe_selection_tracks_the_original_child_and_its_descendants(self):
        script = observer.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234)

        self.assertIn('@tracked[1234] = 1', script)
        self.assertIn('@tracked[args->child_pid] = 1', script)
        self.assertIn('/@tracked[pid] || @tracked[tid]/', script)
        self.assertIn('method__compile__begin', script)
        self.assertIn('method__compile__end', script)


if __name__ == '__main__':
    unittest.main(verbosity=2)
