"""Bounded private runtime and ground-only original-source witness evaluation."""
import atexit
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
MAX_OUTPUT = 1048576
MAX_METADATA = 4194304
MAX_EXPANDED = 134217728
MAX_EXPANDED_FILE = 100663296
TOOLS = {'smt_witness_validation.py', 'verify_nra_source_results.py',
         'verify_source_results.py', 'requirements-source-checks.txt'}
ACTIVE = None
STOPPED = None
PARENT = None
DEADLINE = None
SIGNALS = {signal.SIGTERM, signal.SIGINT}


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
    if process is not None:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def release():
    global ACTIVE
    process = ACTIVE
    if process is not None:
        kill_group(process)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        ACTIVE = None


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


def owned(command, prefix, cwd, timeout, memory):
    global ACTIVE
    if stopped() or threading.active_count() != 1 or ACTIVE is not None:
        raise RuntimeError('owner stopped or drain still active')
    started = time.monotonic_ns()
    previous = signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
    try:
        if stopped():
            raise RuntimeError('owner stopped')
        def child():
            signal.pthread_sigmask(signal.SIG_SETMASK, previous)
            resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
        ACTIVE = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True, preexec_fn=child)
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous)
    process = ACTIVE
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
        while process.poll() is None:
            if stopped() or overflow.is_set() or time.monotonic() >= expiry:
                timed_out = time.monotonic() >= expiry
                kill_group(process)
                break
            time.sleep(0.05)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            kill_group(process)
        # Descendants can keep pipes open after their parent exits.
        kill_group(process)
        for thread in threads:
            thread.join(timeout=2)
        for row, name in [(state['stdout'], 'stdout'), (state['stderr'], 'stderr')]:
            path = pathlib.Path(str(prefix) + '.' + name)
            row['sha256'] = file_digest(path) if path.exists() else None
        complete = all(not thread.is_alive() for thread in threads) and all(
            row['complete'] and 'error' not in row for row in state.values())
        result = {'command': command, 'cwd': str(cwd), 'exit': process.poll(),
                  'timedOut': timed_out, 'overflow': overflow.is_set(), 'parentStop': STOPPED,
                  'streams': state, 'complete': complete, 'processWallNs': time.monotonic_ns() - started,
                  'addressSpaceBytes': memory, 'timeoutSeconds': timeout}
        save(pathlib.Path(str(prefix) + '.process.json'), result)
        return result
    finally:
        release()


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


def bootstrap(out, dependencies):
    assert platform.system() == 'Linux' and platform.machine() == 'x86_64'
    assert sys.version_info >= (3, 9)
    libc, version = platform.libc_ver()
    assert libc == 'glibc' and tuple(map(int, version.split('.')[:2])) >= (2, 17)
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
    matches = [row for row in metadata['urls'] if row['filename'] == WHEEL]
    assert len(matches) == 1
    row = matches[0]
    assert row['url'] == WHEEL_URL and row['digests']['sha256'] == WHEEL_SHA
    assert row['size'] == WHEEL_BYTES and row['packagetype'] == 'bdist_wheel' and not row['yanked']
    wheel = out / WHEEL
    download(opener, WHEEL_URL, wheel, WHEEL_BYTES)
    count = wheel.stat().st_size
    assert count == WHEEL_BYTES and file_digest(wheel) == WHEEL_SHA
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
        'libc':[libc,version], 'packageVersion':VERSION, 'checkerVersion':z3.get_version_string(),
        'wheelBytes':count, 'wheelSha256':WHEEL_SHA, 'wheelUrl':WHEEL_URL,
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
