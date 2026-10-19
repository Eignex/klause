import hashlib
import os
import pathlib
import signal
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest import mock

import smt_witness_validation as worker


@unittest.skipUnless(sys.platform.startswith('linux') and hasattr(signal, 'pthread_sigmask'),
                     'Linux owned process required')
class OwnedWitnessProcessTest(unittest.TestCase):
    def test_private_runtime_selects_the_pinned_platform_native_wheel(self):
        cases = [
            ('Linux', 'x86_64', ('glibc', '2.17'), '', worker.WHEEL,
             'a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384', 29056003),
            ('Darwin', 'arm64', ('', ''), '13.0.0', 'z3_solver-4.15.3.0-py3-none-macosx_13_0_arm64.whl',
             '65335aab295ded7c0ce27c85556067087a87052389ff160777d1a1d48ef0d74f', 36882388),
            ('Darwin', 'x86_64', ('', ''), '26.0.1', 'z3_solver-4.15.3.0-py3-none-macosx_13_0_x86_64.whl',
             '3e62e93adff2def3537ff1ca67c3d58a6ca6d1944e0b5e774f88627b199d50e7', 39637842),
        ]
        for system, machine, libc, version, name, sha, size in cases:
            with self.subTest(system=system, machine=machine), mock.patch.multiple(worker.platform,
                    system=lambda: system, machine=lambda: machine, libc_ver=lambda: libc,
                    mac_ver=lambda: (version, ('', '', ''), machine)):
                selected = worker.pinned_wheel()
            self.assertEqual(selected[0], name)
            self.assertTrue(selected[1].startswith('https://files.pythonhosted.org/packages/'))
            self.assertTrue(selected[1].endswith('/' + name))
            self.assertEqual(selected[2:], (sha, size, libc))

    def test_unsupported_platforms_decline_private_runtime_provisioning(self):
        for system, machine, libc, version in [
                ('Linux', 'aarch64', ('glibc', '2.39'), ''),
                ('Linux', 'x86_64', ('musl', '1.2'), ''),
                ('Linux', 'x86_64', ('glibc', '2.16'), ''),
                ('Darwin', 'arm64', ('', ''), '12.0.0'),
                ('Darwin', 'unknown', ('', ''), '13.0.0'),
                ('Windows', 'AMD64', ('', ''), '')]:
            with self.subTest(system=system, machine=machine, version=version), mock.patch.multiple(worker.platform,
                    system=lambda: system, machine=lambda: machine, libc_ver=lambda: libc,
                    mac_ver=lambda: (version, ('', '', ''), machine)):
                with self.assertRaises((AssertionError, KeyError)):
                    worker.pinned_wheel()

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

    def run_child(self, code, prefix, resident_memory=False):
        return worker.owned([sys.executable, '-I', '-c', code], self.out / prefix,
                            self.directory.name, 5, 2147483648, resident_memory=resident_memory)

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

    def test_address_space_control_remains_the_default_for_darwin_callers(self):
        with mock.patch.object(worker.platform, 'system', return_value='Darwin'), \
                mock.patch.object(worker, 'darwin_resident_bytes', side_effect=AssertionError):
            result = self.run_child("import resource; print(*resource.getrlimit(resource.RLIMIT_AS))", 'default-as')

        self.assertEqual(result['exit'], 0)
        self.assertTrue(result['complete'])
        self.assertEqual(result['addressSpaceBytes'], 2147483648)
        self.assertNotIn('residentMemory', result)
        self.assertEqual((self.out / 'default-as.stdout').read_text().strip(), '2147483648 2147483648')

    def test_group_signals_end_before_the_leader_is_reaped_or_released(self):
        killpg, wait4 = os.killpg, os.wait4
        for resident in (False, True):
            state = {'reaped': False, 'process': None, 'signals': 0}
            def kill(pid, signum):
                self.assertFalse(state['reaped'], 'released group identity was signalled')
                observed = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                self.assertIsNotNone(observed, 'exited leader was already reaped')
                state['process'] = worker.ACTIVE
                state['signals'] += 1
                return killpg(pid, signum)
            def reap(pid, options):
                result = wait4(pid, options)
                if result[0]:
                    state['reaped'] = True
                return result
            with self.subTest(resident=resident), \
                    mock.patch.object(worker.platform, 'system', return_value='Darwin' if resident else 'Linux'), \
                    mock.patch.object(worker, 'darwin_resident_bytes', return_value=100), \
                    mock.patch.object(worker.os, 'killpg', side_effect=kill), \
                    mock.patch.object(worker.os, 'wait4', side_effect=reap):
                result = self.run_child("print('done'); raise SystemExit(7)", 'group-lifetime-' + str(resident),
                                        resident_memory=resident)
                worker.kill_group(state['process'])
            self.assertEqual(result['exit'], 7)
            self.assertTrue(result['complete'])
            self.assertEqual(state['signals'], 1)
            self.assertTrue(state['reaped'])
            self.assertIsNone(worker.ACTIVE)

    def test_missing_non_reaping_observation_declines_before_spawning(self):
        with mock.patch.object(worker.os, 'waitid', None), mock.patch.object(worker.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError, 'non-reaping child observation unavailable'):
                self.run_child("print('not allowed')", 'missing-waitid')
        spawn.assert_not_called()
        self.assertIsNone(worker.ACTIVE)

    def test_darwin_permission_denial_requires_a_sole_exited_group_leader(self):
        for index, sole in enumerate((False, True)):
            with self.subTest(sole=sole), mock.patch.object(worker.platform, 'system', return_value='Darwin'), \
                    mock.patch.object(worker, 'darwin_resident_bytes', return_value=100), \
                    mock.patch.object(worker.os, 'killpg', side_effect=PermissionError('not signalable')), \
                    mock.patch.object(worker, 'darwin_exited_leader_only', return_value=sole) as inspect:
                if sole:
                    result = self.run_child("print('done')", 'group-permission-' + str(index), resident_memory=True)
                    self.assertEqual(result['exit'], 0)
                    self.assertTrue(result['complete'])
                else:
                    with self.assertRaises(PermissionError):
                        self.run_child("print('done')", 'group-permission-' + str(index), resident_memory=True)
                inspect.assert_called()
            self.assertIsNone(worker.ACTIVE)
            self.assertEqual(threading.active_count(), self.threads)
            for name in ('stdout', 'stderr'):
                self.assertTrue((self.out / ('group-permission-' + str(index) + '.' + name)).exists())

    def test_darwin_group_inspection_declines_live_foreign_extra_or_incomplete_membership(self):
        process = types.SimpleNamespace(pid=12345)
        for exited, pids, error in [(True, [12345], 0), (False, [12345], 0), (True, [], 0),
                                    (True, [12346], 0), (True, [12345, 12346], 0), (True, [12345], 1)]:
            def list_members(kind, pid, buffer, size):
                self.assertEqual(kind, worker.PROC_PGRP_ONLY)
                self.assertEqual(pid, process.pid)
                self.assertEqual(size, 2 * worker.ctypes.sizeof(worker.ctypes.c_int))
                for index, member in enumerate(pids):
                    buffer[index] = member
                return 0 if error else len(pids) * worker.ctypes.sizeof(worker.ctypes.c_int)
            library = types.SimpleNamespace(proc_listpids=mock.Mock(side_effect=list_members))
            observed = types.SimpleNamespace(si_pid=process.pid) if exited else None
            with self.subTest(exited=exited, pids=pids, error=error), \
                    mock.patch.object(worker.os, 'waitid', return_value=observed), \
                    mock.patch.object(worker.ctypes, 'CDLL', return_value=library):
                self.assertEqual(worker.darwin_exited_leader_only(process), exited and pids == [12345] and not error)

    def test_darwin_final_kernel_peak_rejects_excesses_between_live_samples(self):
        wait4 = os.wait4
        limit = 2147483648
        for index, peak in enumerate([limit - 1, limit, limit + 1, 0]):
            def reap(pid, options):
                child, status, usage = wait4(pid, options)
                return child, status, types.SimpleNamespace(ru_maxrss=peak) if child else usage
            with self.subTest(peak=peak), mock.patch.object(worker.platform, 'system', return_value='Darwin'), \
                    mock.patch.object(worker, 'darwin_resident_bytes', return_value=100), \
                    mock.patch.object(worker.os, 'wait4', side_effect=reap):
                result = self.run_child("print('bounded')", 'darwin-peak-' + str(index), resident_memory=True)
            memory = result['residentMemory']
            self.assertEqual(result['exit'], 0)
            self.assertEqual(result['addressSpaceBytes'], 0)
            self.assertEqual(memory['limitBytes'], limit)
            self.assertEqual(memory['kernelPeakBytes'], peak)
            self.assertEqual(result['complete'], 0 < peak <= limit)
            self.assertEqual(memory['exceeded'], peak > limit)
            self.assertIsNone(worker.ACTIVE)
            self.assertEqual(threading.active_count(), self.threads)

    def test_darwin_live_memory_excess_or_unreadable_measurement_stops_the_child(self):
        for failure in ['excess', 'unreadable']:
            sample = mock.Mock(return_value=2147483649) if failure == 'excess' else mock.Mock(side_effect=OSError())
            with self.subTest(failure=failure), mock.patch.object(worker.platform, 'system', return_value='Darwin'), \
                    mock.patch.object(worker, 'darwin_resident_bytes', sample):
                result = self.run_child("import time; time.sleep(60)", 'darwin-stop-' + failure, resident_memory=True)
            self.assertFalse(result['complete'])
            self.assertNotEqual(result['exit'], 0)
            self.assertTrue(result['residentMemory']['exceeded'] or result['residentMemory']['error'] is not None)
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

    def test_natural_leader_exit_still_kills_descendants_and_drains_their_pipes(self):
        marker = self.out / 'orphan.pid'
        code = ("import pathlib, subprocess, sys; "
                "child = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)']); "
                "pathlib.Path(" + repr(str(marker)) + ").write_text(str(child.pid)); sys.exit(7)")
        try:
            result = self.run_child(code, 'natural-exit')

            self.assertEqual(result['exit'], 7)
            self.assertTrue(result['complete'])
            self.assertFalse(result['timedOut'])
            self.assertIsNone(result['parentStop'])
            status = pathlib.Path('/proc') / marker.read_text() / 'stat'
            try:
                state = status.read_text().split()[2]
            except FileNotFoundError:
                pass
            else:
                self.assertEqual(state, 'Z', 'descendant remains running')
            self.assertIsNone(worker.ACTIVE)
            self.assertEqual(threading.active_count(), self.threads)
        finally:
            if marker.exists():
                try:
                    os.kill(int(marker.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass
