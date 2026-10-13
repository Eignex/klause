import hashlib
import os
import pathlib
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

import smt_witness_validation as worker


@unittest.skipUnless(sys.platform.startswith('linux') and hasattr(signal, 'pthread_sigmask'),
                     'Linux owned process required')
class OwnedWitnessProcessTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='owned-witness-test-')
        self.out = pathlib.Path(self.directory.name)
        self.previous = (worker.ACTIVE, worker.STOPPED, worker.PARENT, worker.DEADLINE)
        worker.ACTIVE = None
        worker.STOPPED = None
        worker.PARENT = os.getppid()
        worker.DEADLINE = time.monotonic() + 5
        self.threads = threading.active_count()

    def tearDown(self):
        worker.release()
        worker.ACTIVE, worker.STOPPED, worker.PARENT, worker.DEADLINE = self.previous
        self.directory.cleanup()

    def run_child(self, code, prefix):
        return worker.owned([sys.executable, '-I', '-c', code], self.out / prefix,
                            self.directory.name, 5, 2147483648)

    def test_true_child_exit_and_complete_raw_streams_are_preserved(self):
        for exit_code in (0, 7):
            with self.subTest(exit_code=exit_code):
                prefix = 'exit-' + str(exit_code)
                code = ("import sys; print('output'); sys.stderr.write('diagnostic'); sys.exit("
                        + str(exit_code) + ")")
                result = self.run_child(code, prefix)
                self.assertEqual(result['exit'], exit_code)
                self.assertTrue(result['complete'])
                self.assertFalse(result['timedOut'])
                self.assertFalse(result['overflow'])
                self.assertIsNone(result['parentStop'])
                for name, expected in [('stdout', b'output\n'), ('stderr', b'diagnostic')]:
                    raw = (self.out / (prefix + '.' + name)).read_bytes()
                    self.assertEqual(raw, expected)
                    self.assertEqual(result['streams'][name]['bytes'], len(expected))
                    self.assertEqual(result['streams'][name]['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertIsNone(worker.ACTIVE)
                self.assertEqual(threading.active_count(), self.threads)

    def test_output_overflow_retains_the_bounded_prefix_and_withholds_completeness(self):
        code = "import sys; sys.stdout.write('x' * " + str(worker.MAX_OUTPUT + 1) + "); sys.stdout.flush()"
        result = self.run_child(code, 'overflow')
        raw = (self.out / 'overflow.stdout').read_bytes()
        self.assertTrue(result['overflow'])
        self.assertFalse(result['complete'])
        self.assertEqual(len(raw), worker.MAX_OUTPUT)
        self.assertEqual(raw, b'x' * worker.MAX_OUTPUT)
        self.assertEqual(result['streams']['stdout']['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertIsNone(worker.ACTIVE)
        self.assertEqual(threading.active_count(), self.threads)

    def test_containing_stop_kills_descendants_drains_pipes_and_prevents_another_spawn(self):
        marker = self.out / 'descendant.pid'
        code = ("import pathlib, subprocess, sys, time; "
                "child = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)']); "
                "pathlib.Path(" + repr(str(marker)) + ").write_text(str(child.pid)); time.sleep(60)")
        original_stopped = worker.stopped
        def stop_after_descendant_started():
            if marker.exists():
                worker.DEADLINE = 0
            return original_stopped()
        try:
            with mock.patch.object(worker, 'stopped', side_effect=stop_after_descendant_started):
                result = self.run_child(code, 'stopped')
            self.assertTrue(marker.is_file(), 'fake descendant did not start before the safety watchdog')
            self.assertEqual(result['parentStop'], 'containing-deadline')
            self.assertNotEqual(result['exit'], 0)
            self.assertTrue(result['complete'])
            self.assertFalse(result['overflow'])
            pid = int(marker.read_text())
            status = pathlib.Path('/proc') / str(pid) / 'stat'
            try:
                state = status.read_text().split()[2]
            except FileNotFoundError:
                pass
            else:
                self.assertEqual(state, 'Z', 'descendant remains running')
            self.assertIsNone(worker.ACTIVE)
            self.assertEqual(threading.active_count(), self.threads)
            with self.assertRaisesRegex(RuntimeError, 'owner stopped'):
                self.run_child("print('not allowed')", 'after-stop')
            self.assertFalse((self.out / 'after-stop.stdout').exists())
        finally:
            if marker.exists():
                try:
                    os.kill(int(marker.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass
