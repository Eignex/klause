"""Load one hash-pinned decoder in the diagnostic's private output directory."""
import hashlib
import importlib
import io
import os
from pathlib import Path, PurePosixPath
import platform
import sys
import urllib.request
import zipfile

WHEEL_URL = 'https://files.pythonhosted.org/packages/78/7f/ec0687bbe8f6b128f1d41d90ec7cedfd1aaaa4ecb1ae8d334acc7dad8013/capstone-5.0.6-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl'
WHEEL_SHA256 = '0358855773100bb91ae6681fabce7299df83156945ba943f6211061a592c54a6'
MAX_WHEEL_BYTES = 4 * 1024 * 1024
MAX_UNPACKED_BYTES = 32 * 1024 * 1024
MAX_MEMBERS = 256


def unpack(raw, destination, provenance):
    if len(raw) > MAX_WHEEL_BYTES or hashlib.sha256(raw).hexdigest() != WHEEL_SHA256:
        raise ValueError('Decoder wheel differs from pinned release')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        members = archive.infolist()
        names = set()
        if not 0 < len(members) <= MAX_MEMBERS or sum(item.file_size for item in members) > MAX_UNPACKED_BYTES:
            raise ValueError('Decoder archive exceeds extraction bounds')
        for item in members:
            path = PurePosixPath(item.filename)
            mode = item.external_attr >> 16
            if path.is_absolute() or '..' in path.parts or '\\' in item.filename or \
                    item.filename in names or (mode & 0o170000) not in (0, 0o100000, 0o040000):
                raise ValueError('Unsupported decoder archive member')
            names.add(item.filename)
        destination.mkdir()
        provenance['members'] = []
        for item in members:
            path = destination / item.filename
            if item.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(item)
            if len(data) != item.file_size:
                raise ValueError('Decoder archive member size differs')
            with path.open('xb') as stream:
                stream.write(data)
            provenance['members'].append({'name': item.filename, 'bytes': len(data),
                                           'sha256': hashlib.sha256(data).hexdigest()})


def load_decoder(out, report):
    if sys.platform != 'linux' or platform.machine() not in ('x86_64', 'AMD64') or sys.byteorder != 'little':
        raise ValueError('Pinned decoder requires little-endian Linux x86_64')
    provenance = report['decoder'] = {'url': WHEEL_URL, 'sha256': WHEEL_SHA256,
                                      'version': '5.0.6', 'ready': False, 'globalInstallation': False}
    with urllib.request.urlopen(WHEEL_URL, timeout=20) as response:
        raw = response.read(MAX_WHEEL_BYTES + 1)
    provenance['downloadBytes'] = len(raw)
    (out / 'entry-decoder.whl').write_bytes(raw)
    destination = out / 'entry-decoder'
    unpack(raw, destination, provenance)
    library = destination / 'capstone/lib/libcapstone.so'
    if not library.is_file():
        raise ValueError('Pinned decoder wheel lacks its native provider')
    if 'capstone' in sys.modules:
        raise ValueError('Decoder module was loaded before provenance binding')
    before = library.read_bytes()
    provenance['providerSha256'] = hashlib.sha256(before).hexdigest()
    os.environ['LIBCAPSTONE_PATH'] = str(library.parent.resolve())
    sys.path.insert(0, str(destination.resolve()))
    module = importlib.import_module('capstone')
    if Path(module.__file__).resolve() != (destination / 'capstone/__init__.py').resolve() or \
            module.__version__ != provenance['version'] or Path(module._cs._name).resolve() != library.resolve():
        raise ValueError('Decoder binding or native provider differs from pinned wheel')
    provenance['providerPath'] = str(library.resolve())
    provenance['providerSha256AfterLoad'] = hashlib.sha256(library.read_bytes()).hexdigest()
    if provenance['providerSha256AfterLoad'] != provenance['providerSha256']:
        raise ValueError('Decoder provider changed while loading')
    decoder = module.Cs(module.CS_ARCH_X86, module.CS_MODE_64)
    decoder.skipdata = False
    provenance.update(apiVersion=list(module.cs_version()), ready=True, skipData=False,
                      source='https://www.capstone-engine.org/lang_python.html')
    return decoder
