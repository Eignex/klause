import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_compiler_observer as observer


class NativeCompilerObserverSetupTest(unittest.TestCase):
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
