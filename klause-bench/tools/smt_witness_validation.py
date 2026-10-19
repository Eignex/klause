"""Bounded private runtime and ground-only original-source witness evaluation."""
import atexit
import ctypes
import errno
import hashlib
import http.client
import importlib.metadata
import json
import os
import pathlib
import platform
import resource
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import types
import urllib.request
import zipfile

VERSION = '4.15.3.0'
WHEEL = 'z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl'
WHEEL_URL = ('https://files.pythonhosted.org/packages/'
             'b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/' + WHEEL)
WHEEL_SHA = 'a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384'
WHEEL_BYTES = 29056003
MAC_WHEELS = {
    'arm64': ('z3_solver-4.15.3.0-py3-none-macosx_13_0_arm64.whl',
              'https://files.pythonhosted.org/packages/63/45/dd8e9d7500faa05eafa589cc8f0f0b982ce575d51b455d62dab8e19dd571/',
              '65335aab295ded7c0ce27c85556067087a87052389ff160777d1a1d48ef0d74f', 36882388),
    'x86_64': ('z3_solver-4.15.3.0-py3-none-macosx_13_0_x86_64.whl',
               'https://files.pythonhosted.org/packages/54/9e/a11186061d9fead8be43bad7c75055585694124b2ccdd896ef249fe5824f/',
               '3e62e93adff2def3537ff1ca67c3d58a6ca6d1944e0b5e774f88627b199d50e7', 39637842),
}
MAX_OUTPUT = 1048576
MAX_METADATA = 4194304
MAX_EXPANDED = 134217728
MAX_EXPANDED_FILE = 100663296
TOOLS = {'smt_witness_validation.py', 'verify_nra_source_results.py',
         'verify_source_results.py', 'requirements-source-checks.txt'}
ACTIVE = None
GROUP_LOCK = threading.RLock()
STOPPED = None
PARENT = None
DEADLINE = None
SIGNALS = {signal.SIGTERM, signal.SIGINT}
PROC_PIDTASKINFO = 4
PROC_PGRP_ONLY = 2


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def file_digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536):
            result.update(block)
    return result.hexdigest()


def save(path, value):
    raw = (json.dumps(value, indent=2) + '\n').encode()
    if len(raw) > MAX_METADATA:
        raise ValueError('metadata limit')
    temporary = path.with_name(path.name + '.partial')
    with temporary.open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def kill_group(process):
    with GROUP_LOCK:
        if process is None or ACTIVE is not process:
            return
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError:
            if platform.system() != 'Darwin' or not darwin_exited_leader_only(process):
                raise


def darwin_exited_leader_only(process):
    observed = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    if observed is None or observed.si_pid != process.pid:
        return False
    library = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    members = library.proc_listpids
    members.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_int]
    members.restype = ctypes.c_int
    pids = (ctypes.c_int * 2)()
    size = members(PROC_PGRP_ONLY, process.pid, pids, ctypes.sizeof(pids))
    if size != ctypes.sizeof(ctypes.c_int):
        return False
    return pids[0] == process.pid


def release():
    global ACTIVE
    previous = signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
    try:
        with GROUP_LOCK:
            process = ACTIVE
            if process is not None:
                try:
                    kill_group(process)
                finally:
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired as error:
                        raise RuntimeError('child reap incomplete') from error
                    finally:
                        if process.returncode is not None:
                            ACTIVE = None
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous)


def stopped():
    global STOPPED
    if STOPPED is None and PARENT is not None and os.getppid() != PARENT:
        STOPPED = 'parent-exited'
    if STOPPED is None and DEADLINE is not None and time.monotonic() >= DEADLINE:
        STOPPED = 'containing-deadline'
    return STOPPED is not None


def interrupt(signum, frame):
    global STOPPED
    if STOPPED is None:
        STOPPED = 'signal-' + str(signum)
    kill_group(ACTIVE)


class DarwinTaskInfo(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in (
        'virtual_size', 'resident_size', 'total_user', 'total_system', 'threads_user', 'threads_system',
    )] + [(name, ctypes.c_int32) for name in (
        'policy', 'faults', 'pageins', 'cow_faults', 'messages_sent', 'messages_received',
        'syscalls_mach', 'syscalls_unix', 'csw', 'threadnum', 'numrunning', 'priority',
    )]


def darwin_resident_bytes(pid):
    library = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    info = library.proc_pidinfo
    info.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    info.restype = ctypes.c_int
    task = DarwinTaskInfo()
    size = ctypes.sizeof(task)
    observed = info(pid, PROC_PIDTASKINFO, 0, ctypes.byref(task), size)
    if observed == 0 and ctypes.get_errno() == errno.ESRCH:
        return None
    if observed != size:
        raise OSError('incomplete Darwin process memory observation')
    return int(task.resident_size)


def owned(command, prefix, cwd, timeout, memory, resident_memory=False):
    global ACTIVE
    if stopped() or threading.active_count() != 1 or ACTIVE is not None:
        raise RuntimeError('owner stopped or drain still active')
    if not callable(getattr(os, 'waitid', None)) or not hasattr(os, 'WNOWAIT'):
        raise RuntimeError('non-reaping child observation unavailable')
    started = time.monotonic_ns()
    darwin = platform.system() == 'Darwin' and resident_memory
    memory_usage = {'method': 'darwin-proc-taskinfo-wait4-v1', 'limitBytes': memory,
                    'samplePeakBytes': 0, 'kernelPeakBytes': 0, 'samples': 0,
                    'complete': False, 'exceeded': False, 'error': None} if darwin else None
    previous = signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
    try:
        if stopped():
            raise RuntimeError('owner stopped')
        def child():
            signal.pthread_sigmask(signal.SIG_SETMASK, previous)
            if not darwin:
                resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
        ACTIVE = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True, preexec_fn=child)
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous)
    process = ACTIVE
    def poll():
        if process.returncode is not None:
            return process.returncode
        observed = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        if observed is None:
            return None
        if observed.si_code == os.CLD_EXITED:
            return observed.si_status
        if observed.si_code in (os.CLD_KILLED, os.CLD_DUMPED):
            return -observed.si_status
        raise RuntimeError('unexpected child observation')
    state = {'stdout': {'bytes': 0, 'observedBytes': 0, 'complete': False},
             'stderr': {'bytes': 0, 'observedBytes': 0, 'complete': False}}
    overflow = threading.Event()
    def drain(pipe, name):
        row = state[name]
        try:
            with pathlib.Path(str(prefix) + '.' + name).open('xb') as sink:
                while True:
                    block = pipe.read(65536)
                    if not block:
                        row['complete'] = True
                        break
                    row['observedBytes'] += len(block)
                    kept = block[:max(0, MAX_OUTPUT - row['bytes'])]
                    sink.write(kept)
                    row['bytes'] += len(kept)
                    if len(kept) != len(block):
                        overflow.set()
                        kill_group(process)
                        break
                sink.flush()
                os.fsync(sink.fileno())
        except (OSError, ValueError) as error:
            row['error'] = type(error).__name__
        finally:
            pipe.close()
    threads = [threading.Thread(target=drain, args=(process.stdout, 'stdout'), daemon=True),
               threading.Thread(target=drain, args=(process.stderr, 'stderr'), daemon=True)]
    for thread in threads:
        thread.start()
    timed_out = False
    try:
        expiry = min(time.monotonic() + timeout, DEADLINE)
        while poll() is None:
            if darwin:
                try:
                    rss = darwin_resident_bytes(process.pid)
                    if rss is None:
                        if poll() is not None:
                            break
                        raise OSError('missing Darwin process memory observation')
                    assert isinstance(rss, int) and rss >= 0
                    memory_usage['samples'] += 1
                    memory_usage['samplePeakBytes'] = max(memory_usage['samplePeakBytes'], rss)
                    memory_usage['exceeded'] |= rss > memory
                except (OSError, AssertionError, ValueError) as error:
                    memory_usage['error'] = type(error).__name__
                if memory_usage['exceeded'] or memory_usage['error'] is not None:
                    kill_group(process)
                    break
            if stopped() or overflow.is_set() or time.monotonic() >= expiry:
                timed_out = time.monotonic() >= expiry
                kill_group(process)
                break
            time.sleep(0.05)
        # The waitable leader pins the group identity while descendants release their pipes.
        kill_group(process)
        reap_deadline = time.monotonic() + 5
        while poll() is None and time.monotonic() < reap_deadline:
            time.sleep(0.01)
        previous = signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
        try:
            with GROUP_LOCK:
                pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                if pid != process.pid:
                    raise RuntimeError('child reap incomplete')
                process.returncode = os.waitstatus_to_exitcode(status)
                ACTIVE = None
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous)
        if darwin:
            peak = usage.ru_maxrss
            if isinstance(peak, int) and peak > 0:
                memory_usage['kernelPeakBytes'] = peak
                memory_usage['exceeded'] |= peak > memory
            else:
                memory_usage['error'] = 'missing final kernel memory peak'
            memory_usage['complete'] = memory_usage['error'] is None
        for thread in threads:
            thread.join(timeout=2)
        for row, name in [(state['stdout'], 'stdout'), (state['stderr'], 'stderr')]:
            path = pathlib.Path(str(prefix) + '.' + name)
            row['sha256'] = file_digest(path) if path.exists() else None
        complete = all(not thread.is_alive() for thread in threads) and all(
            row['complete'] and 'error' not in row for row in state.values())
        result = {'command': command, 'cwd': str(cwd), 'exit': poll(),
                  'timedOut': timed_out, 'overflow': overflow.is_set(), 'parentStop': STOPPED,
                  'streams': state, 'complete': complete, 'processWallNs': time.monotonic_ns() - started,
                  'addressSpaceBytes': 0 if darwin else memory, 'timeoutSeconds': timeout}
        if darwin:
            result['residentMemory'] = memory_usage
            result['complete'] &= memory_usage['complete'] and not memory_usage['exceeded']
        save(pathlib.Path(str(prefix) + '.process.json'), result)
        return result
    finally:
        try:
            release()
        finally:
            for thread in threads:
                thread.join(timeout=2)


def download(opener, url, path, maximum):
    received = 0
    complete = False
    status = None
    effective = None
    try:
        with opener.open(urllib.request.Request(url, method='GET'), timeout=30) as response:
            status, effective = response.status, response.geturl()
            assert status == 200 and effective == url
            with path.open('xb') as stream:
                while True:
                    interrupted_read = False
                    try:
                        block = response.read(min(65536, maximum + 1 - received))
                    except http.client.IncompleteRead as error:
                        block = error.partial
                        interrupted_read = True
                    if not block:
                        assert not interrupted_read
                        complete = True
                        break
                    kept = block[:max(0, maximum + 1 - received)]
                    stream.write(kept)
                    received += len(kept)
                    stream.flush()
                    os.fsync(stream.fileno())
                    assert received <= maximum and len(kept) == len(block)
                    assert not interrupted_read
    finally:
        save(path.with_name(path.name + '.download.json'), {
            'url':url, 'status':status, 'effectiveUrl':effective, 'receivedBytes':received,
            'complete':complete, 'sha256':file_digest(path) if path.exists() else None,
            'maximumBytes':maximum, 'retryAllowed':False})
    return path.read_bytes() if maximum == MAX_METADATA else None


def pinned_wheel():
    if platform.system() == 'Darwin':
        assert tuple(map(int, platform.mac_ver()[0].split('.')[:2])) >= (13, 0)
        name, prefix, sha, size = MAC_WHEELS[platform.machine()]
        return name, prefix + name, sha, size, ('', '')
    assert platform.system() == 'Linux' and platform.machine() == 'x86_64'
    libc = platform.libc_ver()
    assert libc[0] == 'glibc' and tuple(map(int, libc[1].split('.')[:2])) >= (2, 17)
    return WHEEL, WHEEL_URL, WHEEL_SHA, WHEEL_BYTES, libc


def bootstrap(out, dependencies):
    assert sys.version_info >= (3, 9)
    wheel_name, wheel_url, wheel_sha, wheel_bytes, libc = pinned_wheel()
    assert (out / 'tools/requirements-source-checks.txt').read_bytes() == b'z3-solver==4.15.3.0\n'
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise RuntimeError('redirect refused')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    metadata_url = 'https://pypi.org/pypi/z3-solver/' + VERSION + '/json'
    metadata_raw = download(opener, metadata_url, out / 'wheel-metadata.json', MAX_METADATA)
    metadata = json.loads(metadata_raw)
    assert metadata['info']['version'] == VERSION
    assert metadata['info']['requires_dist'] == ['importlib-resources; python_version < "3.9"']
    matches = [row for row in metadata['urls'] if row['filename'] == wheel_name]
    assert len(matches) == 1
    row = matches[0]
    assert row['url'] == wheel_url and row['digests']['sha256'] == wheel_sha
    assert row['size'] == wheel_bytes and row['packagetype'] == 'bdist_wheel' and not row['yanked']
    wheel = out / wheel_name
    download(opener, wheel_url, wheel, wheel_bytes)
    count = wheel.stat().st_size
    assert count == wheel_bytes and file_digest(wheel) == wheel_sha
    dependencies.mkdir()
    ledger = []
    with zipfile.ZipFile(wheel) as archive:
        entries = archive.infolist()
        assert len(entries) <= 512 and sum(entry.file_size for entry in entries) <= MAX_EXPANDED
        seen = set()
        for entry in entries:
            name = pathlib.PurePosixPath(entry.filename)
            assert not name.is_absolute() and '..' not in name.parts and '\\' not in entry.filename
            assert name.as_posix() == entry.filename.rstrip('/') and name.as_posix() not in seen
            seen.add(name.as_posix())
            mode = (entry.external_attr >> 16) & 0xffff
            assert not stat.S_ISLNK(mode) and stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR)
            assert not entry.flag_bits & 1 and 0 <= entry.file_size <= MAX_EXPANDED_FILE
            path = dependencies / name
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with archive.open(entry) as source, path.open('xb') as sink:
                while block := source.read(65536):
                    written += len(block)
                    assert written <= entry.file_size
                    sink.write(block)
            assert written == entry.file_size
            ledger.append({'path':name.as_posix(), 'bytes':written, 'sha256':file_digest(path)})
    sys.path.insert(0, str(dependencies))
    import z3
    assert pathlib.Path(z3.__file__).resolve().is_relative_to(dependencies.resolve())
    assert importlib.metadata.version('z3-solver') == VERSION and z3.get_version_string() == '4.15.3'
    result = {'pythonVersion':platform.python_version(), 'pythonExecutable':sys.executable,
        'pythonExecutableSha256':file_digest(pathlib.Path(sys.executable)), 'machine':platform.machine(),
        'libc':list(libc), 'packageVersion':VERSION, 'checkerVersion':z3.get_version_string(),
        'wheelBytes':count, 'wheelSha256':wheel_sha, 'wheelUrl':wheel_url,
        'metadataSha256':digest(metadata_raw), 'extractedFiles':ledger, 'globalInstallationPerformed':False}
    save(out / 'bootstrap.json', result)
    return result


def check_case(out, dependencies, index):
    sys.path.insert(0, str(dependencies))
    import z3
    assert pathlib.Path(z3.__file__).resolve().is_relative_to(dependencies.resolve())
    assert importlib.metadata.version('z3-solver') == VERSION and z3.get_version_string() == '4.15.3'
    hashes = json.loads((out / 'tools.json').read_bytes())
    utility_path = out / 'tools/verify_source_results.py'
    utility = types.ModuleType('verify_source_results')
    utility.__file__ = str(utility_path)
    sys.modules['verify_source_results'] = utility
    raw = utility_path.read_bytes()
    assert digest(raw) == hashes[utility_path.name]
    exec(compile(raw, str(utility_path), 'exec'), utility.__dict__)
    checker_path = out / 'tools/verify_nra_source_results.py'
    checker = types.ModuleType('verify_nra_source_results')
    checker.__file__ = str(checker_path)
    raw = checker_path.read_bytes()
    assert digest(raw) == hashes[checker_path.name]
    exec(compile(raw, str(checker_path), 'exec'), checker.__dict__)
    case = out / ('case-' + str(index))
    source = (case / 'source.smt2').read_bytes()
    model = (case / 'model.smt2').read_bytes()
    result = {'sourceSha256':digest(source), 'modelSha256':digest(model),
        'solverSearchQueries':0, 'proofCertificateChecked':False, 'scope':'ground-original-source-model'}
    try:
        checked = checker.check_witness(source.decode('utf-8'), model.decode('utf-8'))
        assert checked['sourceSha256'] == digest(source)
        result.update(checked)
        result['state'] = 'accepted'
        code = 0
    except AssertionError as error:
        message = str(error)
        false_assertion = message.startswith('original source assertion ') and message.endswith(' is false')
        result['state'] = 'rejected' if false_assertion else 'unavailable'
        result['error'] = message[:4096]
        code = 2 if result['state'] == 'rejected' else 3
    except (ValueError, UnicodeError, RuntimeError, z3.Z3Exception) as error:
        result['state'] = 'unavailable'
        result['error'] = type(error).__name__
        code = 3
    print(json.dumps(result))
    return code


def supervise(out, expected_sha, expected_tools, parent):
    global PARENT, DEADLINE
    PARENT = parent
    DEADLINE = time.monotonic() + 1100
    for signum in SIGNALS:
        signal.signal(signum, interrupt)
    atexit.register(release)
    raw = (out / 'request.json').read_bytes()
    assert len(raw) <= 49152 and digest(raw) == expected_sha
    assert set(expected_tools) == TOOLS
    for name, expected in expected_tools.items():
        assert file_digest(out / 'tools' / name) == expected
    assert json.loads((out / 'tools.json').read_bytes()) == expected_tools
    request = json.loads(raw)
    assert request['schema'] == 'smt-ground-witness-request-v1' and 1 <= len(request['cases']) <= 16
    result = {'schema':'smt-original-source-ground-witness-validation-v1',
        'requestSha256':expected_sha, 'checkerSha256':expected_tools['verify_nra_source_results.py'],
        'utilitySha256':expected_tools['verify_source_results.py'],
        'workerSha256':expected_tools['smt_witness_validation.py'],
        'groundOnly':True, 'solverSearchQueries':0, 'proofCertificateChecked':False,
        'performanceOrCurrentGapEstablished':False, 'results':[
            {'index':i, 'id':row['id'], 'sourceSha256':row['source']['sha256'],
             'modelSha256':row['model']['sha256'], 'state':'notReached'} for i,row in enumerate(request['cases'])],
        'issues':[], 'allAccepted':False}
    save(out / 'summary.json', result)
    scratch = tempfile.TemporaryDirectory(prefix='smt-witness-validation-')
    dependencies = pathlib.Path(scratch.name) / 'dependencies'
    try:
        if stopped():
            raise RuntimeError('parent stopped')
        command = [sys.executable, '-I', str(out / 'tools/smt_witness_validation.py')]
        process = owned(command + ['bootstrap', str(out), str(dependencies)], out / 'bootstrap', scratch.name,
            180, 2147483648)
        assert process['exit'] == 0 and process['complete'] and not process['timedOut'] and not process['overflow']
        runtime = json.loads((out / 'bootstrap.json').read_bytes())
        result['runtime'] = runtime
        for index, row in enumerate(request['cases']):
            if stopped():
                break
            case = out / ('case-' + str(index))
            assert file_digest(case / 'source.smt2') == row['source']['sha256']
            assert file_digest(case / 'model.smt2') == row['model']['sha256']
            process = owned(command + ['check', str(out), str(dependencies), str(index)],
                case / 'check', scratch.name, 30, 17179869184)
            state = 'unavailable'
            checked = None
            if (process['complete'] and not process['timedOut'] and not process['overflow']
                    and not process['parentStop']):
                try:
                    checked = json.loads((case / 'check.stdout').read_bytes())
                    assert checked['sourceSha256'] == row['source']['sha256']
                    assert checked['modelSha256'] == row['model']['sha256'] and checked['solverSearchQueries'] == 0
                    assert checked['proofCertificateChecked'] is False
                    assert 'referenceComparison' not in checked
                    if process['exit'] == 0 and checked['state'] == 'accepted':
                        assert checked['checker'] == 'z3-' + runtime['checkerVersion']
                        assert checked['witnessCheck'] == (
                            'all original assertions evaluated true with exact algebraic coordinates')
                        state = 'accepted'
                    elif process['exit'] == 2 and checked['state'] == 'rejected':
                        state = 'rejected'
                except (KeyError, ValueError, AssertionError):
                    pass
            result['results'][index].update({'state':state, 'process':process, 'checkerResult':checked})
            save(out / 'summary.json', result)
        result['allAccepted'] = not stopped() and all(row['state'] == 'accepted' for row in result['results'])
    except (OSError, RuntimeError, AssertionError, ValueError) as error:
        result['issues'].append({'issue':type(error).__name__})
    finally:
        previous = signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
        try:
            release()
            scratch.cleanup()
            result['parentStop'] = STOPPED
            result['cleanup'] = {'temporaryDependenciesRemoved':not pathlib.Path(scratch.name).exists(),
                                 'globalInstallationPerformed':False}
            if STOPPED is not None or not result['cleanup']['temporaryDependenciesRemoved']:
                result['allAccepted'] = False
            result['counts'] = {state:sum(row['state'] == state for row in result['results'])
                                for state in ['accepted','rejected','unavailable','notReached']}
            save(out / 'summary.json', result)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous)
    if stopped():
        result['parentStop'] = STOPPED
        result['allAccepted'] = False
        save(out / 'summary.json', result)
    return 0 if result['allAccepted'] else 1


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'supervise':
        raise SystemExit(supervise(pathlib.Path(sys.argv[2]), sys.argv[3], json.loads(sys.argv[4]), int(sys.argv[5])))
    if mode == 'bootstrap':
        bootstrap(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]))
    elif mode == 'check':
        raise SystemExit(check_case(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]), int(sys.argv[4])))
    else:
        raise ValueError('unknown worker mode')
