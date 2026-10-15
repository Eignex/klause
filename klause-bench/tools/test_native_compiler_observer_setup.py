import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import native_compiler_observer as observer


class NativeCompilerObserverSetupTest(unittest.TestCase):
    def test_counter_binding_failure_does_not_release_the_observed_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary, library = root / 'observer', root / 'library'
            binary.write_bytes(b'tool')
            library.write_bytes(b'library')
            destination = root / 'out'
            destination.mkdir()
            (destination / 'observer.pid').write_text('88\n')
            original_read = Path.read_text
            stat = '88 (bpftrace) S' + ' 0' * 18 + ' 123 0'
            tracer = Mock()
            tracer.poll.return_value = 0
            factory = Mock(side_effect=ValueError('counter unavailable'))
            with patch.object(observer.os, 'pipe', return_value=(10, 11)), \
                    patch.object(observer.os, 'fork', return_value=77), \
                    patch.object(observer.os, 'close'), patch.object(observer.os, 'write') as release, \
                    patch.object(observer.os, 'killpg') as kill, \
                    patch.object(observer.os, 'waitpid', return_value=(77, 0)) as reap, \
                    patch.object(observer.subprocess, 'Popen', return_value=tracer), \
                    patch.object(observer.subprocess, 'check_output', return_value='private observer namespace'), \
                    patch.object(observer, 'ATTACHED', ''), \
                    patch.object(Path, 'read_text', autospec=True, side_effect=lambda path, *args, **kwargs:
                                 stat if str(path) == '/proc/88/stat' else 'bpftrace' if str(path) == '/proc/88/comm'
                                 else original_read(path, *args, **kwargs)), \
                    self.assertRaisesRegex(ValueError, 'counter unavailable'):
                observer.observe(destination, library, binary, ['java', '-version'], 1,
                                 loss_monitor_factory=factory)

            release.assert_not_called()
            kill.assert_called_once_with(77, observer.signal.SIGKILL)
            reap.assert_called_once_with(77, 0)
            receipt = json.loads((destination / 'observer-launch.json').read_text())
            self.assertTrue(receipt['lossMonitorRequested'])
            self.assertFalse(receipt['lossMonitorReady'])
            self.assertFalse(receipt['lossMonitorCompleted'])

    def test_observer_launch_reserves_header_space_for_the_bounded_identity_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'observer'
            library = root / 'library'
            binary.write_bytes(b'tool')
            library.write_bytes(b'library')
            destination = root / 'out'
            with patch.object(observer.os, 'pipe', return_value=(10, 11)), \
                    patch.object(observer.os, 'fork', return_value=77), \
                    patch.object(observer.os, 'close'), patch.object(observer.os, 'write') as release, \
                    patch.object(observer.os, 'killpg'), \
                    patch.object(observer.os, 'waitpid', return_value=(77, 0)), \
                    patch.object(observer.subprocess, 'Popen', side_effect=OSError('launch unavailable')) as tracer, \
                    self.assertRaisesRegex(OSError, 'launch unavailable'):
                observer.observe(destination, library, binary, ['java', '-version'], 1)

            command = tracer.call_args.args[0]
            self.assertIn('BPFTRACE_MAX_STRLEN=132', command)
            self.assertIn('arg1 < 128 ? arg1 : 128', (destination / 'observer.bt').read_text())
            release.assert_not_called()

    def test_script_failure_reaps_the_unreleased_child_without_starting_an_observer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'observer'
            library = root / 'library'
            binary.write_bytes(b'tool')
            library.write_bytes(b'library')
            destination = root / 'out'
            with patch.object(observer.os, 'pipe', return_value=(10, 11)), \
                    patch.object(observer.os, 'fork', return_value=77), \
                    patch.object(observer.os, 'close'), patch.object(observer.os, 'write') as release, \
                    patch.object(observer.os, 'killpg') as kill, \
                    patch.object(observer.os, 'waitpid', return_value=(77, 0)) as reap, \
                    patch.object(observer.subprocess, 'Popen') as tracer, \
                    self.assertRaisesRegex(ValueError, 'script unavailable'):
                observer.observe(destination, library, binary, ['java', '-version'], 1,
                                 lambda path, pid: (_ for _ in ()).throw(ValueError('script unavailable')))
            release.assert_not_called()
            tracer.assert_not_called()
            kill.assert_called_once_with(77, observer.signal.SIGKILL)
            reap.assert_called_once_with(77, 0)
            receipt = json.loads((destination / 'observer-launch.json').read_text())
            self.assertFalse(receipt['attached'])
            self.assertIsNone(receipt['scriptSha256'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
