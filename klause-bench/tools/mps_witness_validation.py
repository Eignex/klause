"""Pinned private runtime for ordinary bench MPS ground witness validation."""
import atexit
import fractions
import math
import numbers
import platform
import re
import hashlib
import importlib.util
import json
import pathlib
import signal
import sys
import tempfile
import time

TOOLS = {'mps_witness_validation.py', 'smt_witness_validation.py',
         'verify_nra_source_results.py', 'verify_source_results.py', 'requirements-source-checks.txt'}
SCOPE = 'ground-original-mps-witness'
CHECKER = 'python-fractions-mps-ground-v1'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def bindings(out):
    return {name: digest((out / name).read_bytes())
            for name in ('source.mps', 'stdout.txt', 'record.json', 'request.json')}


MAX_MAP_BYTES = 262144
MAX_MAP_LINES = 4096
MAX_PROVIDERS = 3
MAX_PROVIDER_BYTES = 67108864
LIBPYTHON = re.compile(r'libpython(\d+)(?:\.(\d+))?[dmt]*\.so(?:\.\d+)*')


def file_identity(path, maximum):
    path = path.resolve(strict=True)
    identity = path.stat()
    size = identity.st_size
    assert 0 < size <= maximum, 'runtime identity file size outside allowance'
    result, count = hashlib.sha256(), 0
    with path.open('rb') as stream:
        while block := stream.read(min(65536, maximum + 1 - count)):
            count += len(block)
            assert count <= maximum, 'runtime identity file grew past allowance'
            result.update(block)
    final = path.stat()
    assert count == size == final.st_size and (identity.st_dev, identity.st_ino, identity.st_mtime_ns) == \
        (final.st_dev, final.st_ino, final.st_mtime_ns), 'runtime identity file changed'
    return {'path': str(path), 'bytes': count, 'sha256': result.hexdigest()}


def python_provider_paths(maps, executable, version):
    assert 0 < len(maps.encode('utf-8')) <= MAX_MAP_BYTES, 'runtime maps size outside allowance'
    assert maps.endswith('\n') and '\r' not in maps, 'unsupported runtime map line endings'
    lines = maps[:-1].split('\n')
    assert 0 < len(lines) <= MAX_MAP_LINES, 'runtime maps line count outside allowance'
    assert executable.startswith('/') and not any(c.isspace() or c == '\\' for c in executable)
    expected_version = version.split('.')[:2]
    providers = {executable: False}
    for line in lines:
        fields = line.split(None, 5)
        assert len(fields) in (5, 6), 'malformed runtime mapping'
        assert re.fullmatch(r'[0-9a-f]{1,16}-[0-9a-f]{1,16}', fields[0]), 'malformed mapping address'
        start, end = (int(part, 16) for part in fields[0].split('-'))
        assert start < end, 'invalid mapping interval'
        assert re.fullmatch(r'[r-][w-][x-][ps]', fields[1]), 'malformed mapping permissions'
        assert re.fullmatch(r'[0-9a-f]{1,16}', fields[2]), 'malformed mapping offset'
        assert re.fullmatch(r'[0-9a-f]{1,8}:[0-9a-f]{1,8}', fields[3]), 'malformed mapping device'
        assert re.fullmatch(r'[0-9]{1,20}', fields[4]), 'malformed mapping inode'
        if len(fields) == 5:
            continue
        path = fields[5]
        name = pathlib.PurePosixPath(path).name
        if path == executable or path.startswith(executable + ' ') or name.startswith('libpython'):
            assert path.startswith('/') and not any(c.isspace() or c == '\\' for c in path), \
                'unsupported runtime provider path'
            assert int(fields[4]) > 0, 'runtime provider is not file-backed'
            if name.startswith('libpython'):
                match = LIBPYTHON.fullmatch(name)
                assert match and match[1] == expected_version[0], 'unsupported libpython provider'
                assert match[2] is None or match[2] == expected_version[1], 'ambiguous libpython version'
            providers[path] = providers.get(path, False) or 'x' in fields[1]
    assert 1 <= len(providers) <= MAX_PROVIDERS and all(providers.values()), \
        'missing ambiguous or non-executable runtime providers'
    return sorted(providers)


def builtin_math_identity():
    spec = getattr(math, '__spec__', None)
    origin = getattr(spec, 'origin', None)
    loader = getattr(spec, 'loader', None)
    loader_type = loader if isinstance(loader, type) else type(loader)
    loader_name = loader_type.__module__ + '.' + loader_type.__qualname__
    assert origin == 'built-in' and math.__name__ in sys.builtin_module_names, 'unsupported math origin'
    assert loader_name == '_frozen_importlib.BuiltinImporter', 'unsupported builtin math loader'
    executable = pathlib.Path(sys.executable).resolve(strict=True)
    assert pathlib.Path('/proc/self/exe').resolve(strict=True) == executable, 'interpreter provider mismatch'
    with pathlib.Path('/proc/self/maps').open('rb') as stream:
        raw = stream.read(MAX_MAP_BYTES + 1)
    assert len(raw) <= MAX_MAP_BYTES, 'runtime maps exceed allowance'
    maps = raw.decode('utf-8')
    paths = python_provider_paths(maps, str(executable), platform.python_version())
    providers = [file_identity(pathlib.Path(path), MAX_PROVIDER_BYTES) for path in paths]
    assert all(row['path'] == path for row, path in zip(providers, paths)), 'ambiguous provider aliases'
    return {'origin': origin, 'loader': loader_name, 'executablePath': str(executable),
            'maps': maps, 'mapsSha256': digest(raw), 'providers': providers}


def arithmetic_runtime():
    modules = {module.__name__: file_identity(pathlib.Path(module.__file__), 4194304)
               for module in (fractions, numbers)}
    builtin = None
    if getattr(math, '__file__', None):
        origin = getattr(getattr(math, '__spec__', None), 'origin', None)
        assert origin not in (None, 'built-in', 'frozen'), 'ambiguous file-backed math origin'
        assert pathlib.Path(origin).resolve(strict=True) == pathlib.Path(math.__file__).resolve(strict=True)
        modules['math'] = file_identity(pathlib.Path(math.__file__), 4194304)
    else:
        builtin = builtin_math_identity()
    return {'implementation': CHECKER, 'pythonVersion': platform.python_version(),
            'pythonExecutableSha256': file_identity(pathlib.Path(sys.executable), MAX_PROVIDER_BYTES)['sha256'],
            'modules': modules, 'builtinMath': builtin}


def check(out, dependencies):
    sys.path.insert(0, str(dependencies))
    import importlib.metadata
    import z3
    assert pathlib.Path(z3.__file__).resolve().is_relative_to(dependencies.resolve())
    assert importlib.metadata.version('z3-solver') == '4.15.3.0' and z3.get_version_string() == '4.15.3'
    verifier = load(out / 'tools/verify_source_results.py', 'verify_source_results')
    raw = (out / 'stdout.txt').read_bytes()
    record = json.loads((out / 'record.json').read_bytes())
    source = (out / 'source.mps').read_bytes()
    request = json.loads((out / 'request.json').read_bytes())
    assert record['sourceHashes'] == {'model': digest(source)}
    assert record['sourceOutputSha256'] == digest(raw)
    result = {'status': 'unknown', 'reason': 'source checking incomplete', 'scope': SCOPE,
              'bindings': bindings(out), 'solverSearchQueries': 0, 'proofCertificateChecked': False,
              'checkedWitness': None, 'checker': CHECKER, 'groundArithmetic': arithmetic_runtime()}
    try:
        checked = verifier.check_mps_ground(source.decode('utf-8'), record, raw.decode('utf-8'), request['exact'])
        result.update(checked)
        result.update(status='valid', reason='original source point and objective checked',
                      checkedWitness=record['finalWitness'])
    except AssertionError as error:
        result.update(status='invalid', reason=str(error)[:500])
    except (ValueError, KeyError, TypeError, UnicodeError, RuntimeError, ZeroDivisionError, OverflowError, z3.Z3Exception) as error:
        result.update(reason='unsupported source or witness: ' + str(error)[:400])
    print(json.dumps(result))


def supervise(out, expected, tools, parent):
    assert set(tools) == TOOLS
    assert bindings(out) == expected
    assert json.loads((out / 'tools.json').read_bytes()) == tools
    assert all(digest((out / 'tools' / name).read_bytes()) == value for name, value in tools.items())
    runner = load(out / 'tools/smt_witness_validation.py', 'smt_witness_validation')
    runner.PARENT = parent
    runner.DEADLINE = time.monotonic() + 60
    for signum in runner.SIGNALS:
        signal.signal(signum, runner.interrupt)
    atexit.register(runner.release)
    result = {'status': 'unknown', 'reason': 'source checking incomplete', 'scope': SCOPE,
              'bindings': expected, 'toolHashes': tools, 'solverSearchQueries': 0,
              'proofCertificateChecked': False, 'checkedWitness': None,
              'setupMs': 0, 'checkMs': 0, 'cleanupComplete': False}
    runner.save(out / 'result.json', result)
    scratch = tempfile.TemporaryDirectory(prefix='mps-witness-runtime-')
    dependencies = pathlib.Path(scratch.name) / 'dependencies'
    try:
        start = time.monotonic_ns()
        try:
            bootstrap = runner.owned([sys.executable, '-I', str(out / 'tools/smt_witness_validation.py'),
                                     'bootstrap', str(out), str(dependencies)], out / 'bootstrap', scratch.name,
                                     30, 2147483648)
        finally:
            result['setupMs'] = (time.monotonic_ns() - start) // 1000000
        result['bootstrapProcess'] = bootstrap
        if (bootstrap['exit'] != 0 or not bootstrap['complete'] or bootstrap['timedOut']
                or bootstrap['overflow'] or bootstrap['parentStop']):
            raise RuntimeError('pinned checker setup incomplete')
        result['runtime'] = json.loads((out / 'bootstrap.json').read_bytes())
        start = time.monotonic_ns()
        try:
            process = runner.owned([sys.executable, '-I', str(out / 'tools/mps_witness_validation.py'),
                                   'check', str(out), str(dependencies)], out / 'check', scratch.name,
                                   30, 4294967296)
        finally:
            result['checkMs'] = (time.monotonic_ns() - start) // 1000000
        result['checkProcess'] = process
        if (process['exit'] != 0 or not process['complete'] or process['timedOut']
                or process['overflow'] or process['parentStop']):
            raise RuntimeError('ground checker process incomplete')
        checked = json.loads((out / 'check.stdout').read_bytes())
        assert checked['bindings'] == expected and checked['solverSearchQueries'] == 0
        assert checked['proofCertificateChecked'] is False and checked['scope'] == SCOPE
        assert checked['status'] in ('valid', 'invalid', 'unknown')
        assert checked['checker'] == CHECKER
        assert checked['status'] != 'valid' or checked['checkedWitness'] == json.loads(
            (out / 'record.json').read_bytes())['finalWitness']
        result.update(checked)
    except (OSError, AssertionError, RuntimeError, ValueError, KeyError, TypeError) as error:
        result.update(status='unknown', reason=str(error)[:500], checkedWitness=None)
    finally:
        runner.release()
        result['processLogs'] = {}
        for prefix in ('bootstrap', 'check'):
            for stream in ('stdout', 'stderr'):
                path = out / (prefix + '.' + stream)
                if path.is_file():
                    result['processLogs'][path.name] = path.read_bytes().decode('utf-8', errors='replace')
        scratch.cleanup()
        result['cleanupComplete'] = not pathlib.Path(scratch.name).exists()
        if (not result['cleanupComplete'] or runner.stopped() or bindings(out) != expected
                or any(runner.file_digest(out / 'tools' / name) != value for name, value in tools.items())):
            result.update(status='unknown', reason='checker custody or cleanup incomplete', checkedWitness=None)
        runner.save(out / 'result.json', result)
    return 0


if __name__ == '__main__':
    if sys.argv[1] == 'supervise':
        raise SystemExit(supervise(pathlib.Path(sys.argv[2]), json.loads(sys.argv[3]),
                                   json.loads(sys.argv[4]), int(sys.argv[5])))
    elif sys.argv[1] == 'check':
        check(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]))
    else:
        raise ValueError('unsupported fixed checker mode')
