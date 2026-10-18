"""Bounded lossless MPS stdout decoding; semantic evaluation stays with the ground checker."""
import _compression
import gzip
import hashlib
import io
import re
import pathlib
import platform
import sys
import zlib

MAX_WIRE_BYTES = 8 * 1024 * 1024
MAX_DECODED_BYTES = 64 * 1024 * 1024
MAX_LINE_BYTES = 8 * 1024 * 1024
MAX_ENVELOPE_BYTES = 8 * 1024 * 1024


def envelope(path, expected_sha256, expected_bytes):
    if not isinstance(expected_sha256, str) or not re.fullmatch('[0-9a-f]{64}', expected_sha256):
        raise ValueError('missing complete stdout hash')
    if type(expected_bytes) is not int or not 0 <= expected_bytes <= MAX_DECODED_BYTES:
        raise ValueError('decoded stdout size outside transport limit')
    with path.open('rb') as source:
        compressed = source.read(MAX_WIRE_BYTES + 1)
    if not compressed or len(compressed) > MAX_WIRE_BYTES:
        raise ValueError('compressed stdout exceeds checker limit')
    digest = hashlib.sha256()
    total = 0
    semantics = []
    semantic_bytes = 0
    witness = objective = None
    witness_bytes = objective_bytes = 0
    with gzip.GzipFile(fileobj=io.BytesIO(compressed), mode='rb') as stream:
        while raw := stream.readline(MAX_LINE_BYTES + 1):
            if len(raw) > MAX_LINE_BYTES:
                raise ValueError('stdout line exceeds checker limit')
            total += len(raw)
            if total > MAX_DECODED_BYTES:
                raise ValueError('decoded stdout exceeds compressed transport limit')
            digest.update(raw)
            for line in raw.decode('utf-8').splitlines():
                size = len(line.encode('utf-8')) + 1
                if 'klause-source-semantics:' in line:
                    semantics.append(line)
                    semantic_bytes += size
                if line == 'v' or (len(line) > 1 and line[0] == 'v' and line[1].isspace()):
                    witness = line
                    witness_bytes = size
                if line.startswith('o '):
                    objective = line
                    objective_bytes = size
                if semantic_bytes + witness_bytes + objective_bytes > MAX_ENVELOPE_BYTES:
                    raise ValueError('stdout checking envelope exceeds limit')
    if total != expected_bytes or digest.hexdigest() != expected_sha256:
        raise ValueError('complete decoded stdout differs from producer')
    lines = semantics + ([witness] if witness is not None else []) + ([objective] if objective is not None else [])
    retained = ('\n'.join(lines) + ('\n' if lines else '')).encode('utf-8')
    if len(retained) > MAX_ENVELOPE_BYTES:
        raise ValueError('stdout checking envelope exceeds limit')
    receipt = {
        'encoding': 'gzip', 'wireBytes': len(compressed), 'decodedBytes': total,
        'wireSha256': hashlib.sha256(compressed).hexdigest(), 'decodedSha256': digest.hexdigest(),
        'envelopeBytes': len(retained), 'envelopeSha256': hashlib.sha256(retained).hexdigest(),
        'complete': True,
    }
    return retained.decode('utf-8'), receipt


def runtime_identity(file_identity, builtin_identity):
    modules = {}
    builtin_zlib = None
    for module in (gzip, zlib, _compression):
        origin = getattr(getattr(module, '__spec__', None), 'origin', None)
        path = getattr(module, '__file__', None)
        if module is zlib and origin == 'built-in' and path is None:
            builtin_zlib = builtin_identity(module)
            continue
        assert origin not in (None, 'built-in', 'frozen') and path, 'unsupported decoder module origin'
        assert pathlib.Path(origin).resolve(strict=True) == pathlib.Path(path).resolve(strict=True)
        modules[module.__name__] = file_identity(pathlib.Path(path), 4194304)
    with pathlib.Path('/proc/self/maps').open('rb') as stream:
        raw = stream.read(262145)
    assert 0 < len(raw) <= 262144, 'decoder maps exceed allowance'
    maps = raw.decode('utf-8')
    assert maps.endswith('\n') and '\r' not in maps, 'unsupported decoder mapping lines'
    lines = maps[:-1].split('\n')
    assert 0 < len(lines) <= 4096, 'decoder mapping count exceeds allowance'
    providers = {}
    for line in lines:
        fields = line.split(None, 5)
        assert len(fields) in (5, 6), 'malformed decoder mapping'
        assert re.fullmatch(r'[0-9a-f]{1,16}-[0-9a-f]{1,16}', fields[0])
        start, end = (int(part, 16) for part in fields[0].split('-'))
        assert start < end and re.fullmatch(r'[r-][w-][x-][ps]', fields[1])
        assert re.fullmatch(r'[0-9a-f]{1,16}', fields[2])
        assert re.fullmatch(r'[0-9a-f]{1,8}:[0-9a-f]{1,8}', fields[3])
        assert re.fullmatch(r'[0-9]{1,20}', fields[4])
        if len(fields) == 6 and pathlib.PurePosixPath(fields[5]).name.startswith('libz.so'):
            path = fields[5]
            assert path.startswith('/') and not any(c.isspace() or c == '\\' for c in path)
            assert re.fullmatch(r'libz\.so(?:\.[0-9]+)*', pathlib.PurePosixPath(path).name)
            assert int(fields[4]) > 0, 'decoder provider is not file-backed'
            providers[path] = providers.get(path, False) or 'x' in fields[1]
    assert len(providers) <= 3 and all(providers.values()), 'ambiguous decoder providers'
    identities = [file_identity(pathlib.Path(path), 67108864) for path in sorted(providers)]
    assert all(row['path'] == path for row, path in zip(identities, sorted(providers))), 'decoder aliases differ'
    return {'pythonVersion': platform.python_version(),
            'pythonExecutableSha256': file_identity(pathlib.Path(sys.executable), 67108864)['sha256'],
            'modules': modules, 'builtinZlib': builtin_zlib, 'zlibVersion': zlib.ZLIB_VERSION, 'zlibRuntimeVersion': zlib.ZLIB_RUNTIME_VERSION,
            'maps': maps, 'mapsSha256': hashlib.sha256(raw).hexdigest(), 'nativeProviders': identities}
