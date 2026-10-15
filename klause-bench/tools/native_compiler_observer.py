"""Launch an original-user command after pinned native compiler probes attach."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time

ATTACHED = '__BPFTRACE_NOTIFY_PROBES_ATTACHED'
MAX_BYTES = 128
BUFFER_HEADER_BYTES = 4


def file_digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def remove_extraction(directory, receipt):
    assert directory.is_dir() and not directory.is_symlink()
    directory = directory.resolve()
    mounts = []
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        point = re.sub(r'\\([0-7]{3})', lambda match: chr(int(match[1], 8)), line.split()[4])
        path = Path(point)
        if path == directory or directory in path.parents:
            mounts.append(point)
    receipt['cleanupMountPoints'] = mounts
    if mounts:
        raise RuntimeError('Refusing extraction cleanup across visible mounts')
    shutil.rmtree(directory)
    receipt['temporaryRemoved'] = not directory.exists()
    assert receipt['temporaryRemoved']


def observe_appimage(destination, library, image, command, timeout, script_builder=None):
    destination.mkdir(parents=True, exist_ok=True)
    extraction = dict(packageSha256=file_digest(image), exit=None,
                      scope='Hash-pinned package extraction in an owned temporary directory. '
                      'AppRun uses its private mount namespace; the original-user child is separate.')
    temporary = Path(tempfile.mkdtemp(prefix='bpftrace-tool-', dir=destination.parent.parent))
    try:
        with (destination / 'appimage-extract.stdout').open('wb') as output, \
                (destination / 'appimage-extract.stderr').open('wb') as errors:
            process = subprocess.run([str(image), '--appimage-extract'], cwd=temporary,
                                     stdout=output, stderr=errors, timeout=60, check=False)
        extraction['exit'] = process.returncode
        assert process.returncode == 0, 'AppImage extraction failed'
        unpacked = Path(temporary) / 'squashfs-root'
        app_run = unpacked / 'AppRun'
        assert app_run.is_file() and not app_run.is_symlink()
        entrypoint = os.readlink(unpacked / 'entrypoint')
        assert entrypoint.startswith('/nix/store/')
        executable = (unpacked / entrypoint.removeprefix('/')).resolve()
        assert executable.is_relative_to(unpacked.resolve()) and executable.is_file()
        extraction.update(appRunSha256=file_digest(app_run), entrypoint=entrypoint,
                          executableSha256=file_digest(executable), appRun=str(app_run))
        if script_builder is None:
            observe(destination, library, app_run, command, timeout)
        else:
            observe(destination, library, app_run, command, timeout, script_builder)
    finally:
        try:
            remove_extraction(temporary, extraction)
        finally:
            (destination / 'appimage-extraction.json').write_text(json.dumps(extraction, indent=2) + '\n')


def program(library, root_pid):
    assert library.is_absolute() and not any(c in str(library) for c in '\n"*')
    blocks = [f'BEGIN {{ @tracked[{root_pid}] = 1; }}',
              'tracepoint:sched:sched_process_fork /@tracked[pid] || @tracked[tid]/ '
              '{ @tracked[args->child_pid] = 1; '
              'printf("FORK\\t%llu\\t%d\\t%d\\t%d\\t%d\\n", nsecs, pid, tid, '
              'args->parent_pid, args->child_pid); }']
    for kind in ['begin', 'end']:
        probe = f'usdt:{library}:hotspot:method__compile__{kind}'
        prefix = f'{probe} /@tracked[pid] || @tracked[tid]/'
        success = 'arg8' if kind == 'end' else '-1'
        blocks.append(prefix + ' { '
                      '@sequence[tid] = @sequence[tid] + 1; '
                      f'printf("EVENT\\t{kind}\\t%llu\\t%d\\t%d\\t%d\\t%llu\\t%lld\\n", '
                      f'nsecs, pid, tid, uid, @sequence[tid], {success}); }}')
        for label, pointer, length in [('compiler', 0, 1), ('class', 2, 3),
                                       ('method', 4, 5), ('signature', 6, 7)]:
            blocks.append(prefix + ' { '
                          f'@identity_sequence[tid, "{label}"] = @identity_sequence[tid, "{label}"] + 1; '
                          f'printf("IDENTITY\\t{kind}\\t%d\\t%d\\t%llu\\t{label}\\t%llu\\t%rh\\n", '
                          f'pid, tid, @identity_sequence[tid, "{label}"], arg{length}, buf(uptr(arg{pointer}), '
                          f'arg{length} < {MAX_BYTES} ? arg{length} : {MAX_BYTES})); }}')
    blocks.append('END { clear(@tracked); clear(@sequence); clear(@identity_sequence); }')
    return '\n'.join(blocks) + '\n'


def observe(destination, library, observer_binary, command, timeout, script_builder=None):
    destination.mkdir(parents=True, exist_ok=True)
    assert command and timeout > 0
    expected_uid, expected_gid = os.getuid(), os.getgid()
    expected_groups = os.getgroups()
    expected_mount_namespace = os.readlink('/proc/self/ns/mnt')
    binary_digest, library_digest = file_digest(observer_binary), file_digest(library)
    read_gate, write_gate = os.pipe()
    child = os.fork()
    if child == 0:
        os.close(write_gate)
        try:
            os.setsid()
            if os.read(read_gate, 1) != b'R':
                os._exit(125)
            os.close(read_gate)
            identity = dict(pid=os.getpid(), uid=os.getuid(), gid=os.getgid(),
                            groups=os.getgroups(), mountNamespace=os.readlink('/proc/self/ns/mnt'), command=command)
            (destination / 'child-before-exec.json').write_text(json.dumps(identity, indent=2) + '\n')
            if (identity['uid'] != expected_uid or identity['gid'] != expected_gid or
                    identity['groups'] != expected_groups or identity['mountNamespace'] != expected_mount_namespace):
                os._exit(126)
            os.execvpe(command[0], command, os.environ)
        except BaseException as error:
            (destination / 'child-exec-error.json').write_text(json.dumps(dict(type=type(error).__name__)) + '\n')
            os._exit(127)
    os.close(read_gate)
    script = destination / 'observer.bt'
    observer_pid = destination / 'observer.pid'
    observer_command = ['sudo', '-n', 'unshare', '--mount', '--propagation', 'private',
                        'env', ATTACHED + '=1', 'BPFTRACE_MAX_STRLEN=' + str(MAX_BYTES + BUFFER_HEADER_BYTES),
                        'sh', '-c', 'printf "%s\\n" "$$" > "$1"; exec "$3" -k -B none "$2"',
                        'observer', str(observer_pid), str(script), str(observer_binary)]
    receipt = dict(command=command, observerCommand=observer_command, rootPid=child,
                   expectedUid=expected_uid, expectedGid=expected_gid, attached=False,
                   expectedGroups=expected_groups, observerBinary=str(observer_binary),
                   expectedMountNamespace=expected_mount_namespace, observerMountNamespace=None,
                   observerBinarySha256=binary_digest, librarySha256=library_digest,
                   childExit=None, observerExit=None, timedOut=False,
                   clockAnchors=[],
                   scope='Native invocation diagnostic only; no JFR metric substitution or savings credit. '
                   'Raw events/lengths/errors retained. Compile ID/OSR/root/clock/loss association unproved.')
    tracer = None
    tracer_identity = None
    child_reaped = False
    try:
        script.write_text((script_builder or program)(library, child))
        with (destination / 'observer.stdout').open('wb') as output, \
                (destination / 'observer.stderr').open('wb') as errors:
            tracer = subprocess.Popen(observer_command, stdout=output, stderr=errors)
            deadline = time.monotonic() + 60
            while ATTACHED not in ((destination / 'observer.stdout').read_text(errors='replace') +
                                   (destination / 'observer.stderr').read_text(errors='replace')):
                if tracer_identity is None and observer_pid.exists():
                    try:
                        candidate = int(observer_pid.read_text().strip())
                        status = Path(f'/proc/{candidate}/stat').read_text()
                        if os.fsencode(observer_binary) in Path(f'/proc/{candidate}/cmdline').read_bytes():
                            tracer_identity = status[status.rindex(')') + 2:].split()[19]
                            receipt['observerPid'] = candidate
                            receipt['observerStartTicks'] = tracer_identity
                    except (ValueError, FileNotFoundError):
                        pass
                if tracer.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Observer attachment failed; command was not released')
                time.sleep(0.05)
            receipt['attached'] = True
            actual_tracer = int(observer_pid.read_text().strip())
            status = Path(f'/proc/{actual_tracer}/stat').read_text()
            tracer_identity = status[status.rindex(')') + 2:].split()[19]
            assert Path(f'/proc/{actual_tracer}/comm').read_text().strip() == 'bpftrace'
            receipt['observerPid'] = actual_tracer
            receipt['observerStartTicks'] = tracer_identity
            receipt['observerMountNamespace'] = subprocess.check_output(
                ['sudo', '-n', 'readlink', f'/proc/{actual_tracer}/ns/mnt'], text=True).strip()
            assert receipt['observerMountNamespace'] != expected_mount_namespace
            receipt['clockAnchors'].append(clock_anchor('before-child-release'))
            os.write(write_gate, b'R')
            os.close(write_gate)
            write_gate = None
            deadline = time.monotonic() + timeout
            while True:
                ended, status = os.waitpid(child, os.WNOHANG)
                if ended:
                    child_reaped = True
                    receipt['childExit'] = os.waitstatus_to_exitcode(status)
                    break
                if tracer.poll() is not None:
                    raise RuntimeError('Observer exited while command was live')
                if time.monotonic() >= deadline:
                    receipt['timedOut'] = True
                    raise RuntimeError('Observed command timed out')
                time.sleep(0.05)
            receipt['clockAnchors'].append(clock_anchor('after-child-exit'))
            subprocess.run(['sudo', '-n', 'kill', '-INT', str(actual_tracer)], check=True)
            receipt['observerExit'] = tracer.wait(timeout=30)
            receipt['clockAnchors'].append(clock_anchor('after-observer-drain'))
    finally:
        if write_gate is not None:
            os.close(write_gate)
        if not child_reaped:
            try:
                os.killpg(child, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _, status = os.waitpid(child, 0)
            receipt['childExit'] = os.waitstatus_to_exitcode(status)
        if tracer is not None and tracer.poll() is None:
            if observer_pid.exists():
                actual_tracer = int(observer_pid.read_text().strip())
                process_stat = Path(f'/proc/{actual_tracer}/stat')
                if tracer_identity is not None and process_stat.exists():
                    status = process_stat.read_text()
                    if status[status.rindex(')') + 2:].split()[19] == tracer_identity:
                        subprocess.run(['sudo', '-n', 'kill', '-KILL', str(actual_tracer)], check=False)
            try:
                tracer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        receipt['observerExit'] = tracer.poll() if tracer is not None else None
        receipt['scriptSha256'] = hashlib.sha256(script.read_bytes()).hexdigest() if script.is_file() else None
        (destination / 'observer-launch.json').write_text(json.dumps(receipt, indent=2) + '\n')
    assert receipt['childExit'] == 0 and receipt['observerExit'] == 0 and not receipt['timedOut']


def clock_anchor(stage):
    return dict(stage=stage, bootBefore=time.clock_gettime_ns(time.CLOCK_BOOTTIME),
                monotonic=time.clock_gettime_ns(time.CLOCK_MONOTONIC), realtime=time.time_ns(),
                bootAfter=time.clock_gettime_ns(time.CLOCK_BOOTTIME))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--extract-appimage', action='store_true')
    parser.add_argument('destination', type=Path)
    parser.add_argument('library', type=Path)
    parser.add_argument('observer_binary', type=Path)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    run = observe_appimage if args.extract_appimage else observe
    run(args.destination, args.library, args.observer_binary, command, args.timeout)
