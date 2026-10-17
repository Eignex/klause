"""Pinned private runtime for ordinary bench MPS ground witness validation."""
import atexit
import fractions
import math
import numbers
import platform
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


def arithmetic_runtime():
    modules = {}
    for module in (fractions, numbers, math):
        path = pathlib.Path(module.__file__).resolve()
        raw = path.read_bytes()
        assert 0 < len(raw) <= 4194304
        modules[module.__name__] = {'path': str(path), 'bytes': len(raw), 'sha256': digest(raw)}
    return {'implementation': CHECKER, 'pythonVersion': platform.python_version(),
            'pythonExecutableSha256': digest(pathlib.Path(sys.executable).read_bytes()), 'modules': modules}


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
