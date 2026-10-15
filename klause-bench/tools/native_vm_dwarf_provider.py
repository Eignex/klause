"""Load the pinned pure-Python DWARF reader from private task storage."""
import hashlib
import importlib
import io
from pathlib import Path, PurePosixPath
import sys
import urllib.request
import zipfile

WHEEL_URL = 'https://files.pythonhosted.org/packages/46/2a/f9697576603dae937727827505a6126a066affb227034e77e6f9068910da/pyelftools-0.33-py3-none-any.whl'
WHEEL_SHA256 = 'f215ad5f47d3f1373a21496a6c9e0707c622840d0622f23ff7ce08678b020036'
MAX_WHEEL_BYTES = 1024 * 1024
MAX_UNPACKED_BYTES = 8 * 1024 * 1024
MAX_MEMBERS = 512


def unpack(raw, destination, report):
    if len(raw) > MAX_WHEEL_BYTES or hashlib.sha256(raw).hexdigest() != WHEEL_SHA256:
        raise ValueError('DWARF provider wheel differs from pinned release')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        members, names = archive.infolist(), set()
        if not 0 < len(members) <= MAX_MEMBERS or sum(row.file_size for row in members) > MAX_UNPACKED_BYTES:
            raise ValueError('DWARF provider archive exceeds bounds')
        for row in members:
            path = PurePosixPath(row.filename)
            mode = row.external_attr >> 16
            if path.is_absolute() or '..' in path.parts or '\\' in row.filename or row.filename in names or \
                    mode & 0o170000 not in (0, 0o100000, 0o040000) or \
                    path.suffix in {'.so', '.pyd', '.dll', '.dylib'} or '.so.' in path.name:
                raise ValueError('Unsupported DWARF provider archive member')
            names.add(row.filename)
        destination.mkdir()
        report.update(members=[], licenses=[])
        for row in members:
            target = destination / row.filename
            if row.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(row)
            if len(data) != row.file_size:
                raise ValueError('DWARF provider member is truncated')
            with target.open('xb') as output:
                output.write(data)
            report['members'].append({'name': row.filename, 'bytes': len(data),
                                      'sha256': hashlib.sha256(data).hexdigest()})
            if PurePosixPath(row.filename).name.upper().startswith(('LICENSE', 'COPYING')):
                if len(data) > 65536:
                    raise ValueError('DWARF provider license exceeds bounds')
                report['licenses'].append({'name': row.filename, 'text': data.decode('utf-8')})


def verify(destination, report):
    expected = {row['name']: row['sha256'] for row in report['members']}
    for name, digest in expected.items():
        path = destination / name
        if not path.is_file() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Private DWARF provider member changed')
    imported = []
    for name, module in tuple(sys.modules.items()):
        if name != 'elftools' and not name.startswith('elftools.'):
            continue
        path = Path(module.__file__).resolve()
        if not path.is_relative_to(destination.resolve()):
            raise ValueError('DWARF module came from outside private storage')
        member = path.relative_to(destination.resolve()).as_posix()
        if member not in expected or hashlib.sha256(path.read_bytes()).hexdigest() != expected[member]:
            raise ValueError('Imported DWARF module differs from pinned member')
        imported.append({'module': name, 'member': member, 'sha256': expected[member]})
    if not any(row['module'] == 'elftools.elf.elffile' for row in imported):
        raise ValueError('Pinned ELF reader module was not loaded')
    report['importedModules'] = imported


def load(directory, report):
    report.update(url=WHEEL_URL, sha256=WHEEL_SHA256, version='0.33', ready=False,
                  globalInstallation=False, targetNativeLibraryLoaded=False)
    if any(name == 'elftools' or name.startswith('elftools.') for name in sys.modules):
        raise ValueError('DWARF provider was loaded before identity binding')
    with urllib.request.urlopen(WHEEL_URL, timeout=30) as source:
        raw = source.read(MAX_WHEEL_BYTES + 1)
    report['downloadBytes'] = len(raw)
    destination = directory / 'dwarf-provider'
    unpack(raw, destination, report)
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(destination.resolve()))
    module = importlib.import_module('elftools')
    if module.__version__ != report['version']:
        raise ValueError('DWARF provider version differs')
    reader = importlib.import_module('elftools.elf.elffile').ELFFile
    verify(destination, report)
    report.update(ready=True, directory=str(destination.resolve()))
    return reader
