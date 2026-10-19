"""Bind the executing CPython image without provisioning a symbolic solver."""
import ctypes
import json
import pathlib
import platform
import sys


def identity(file_identity):
    assert platform.python_implementation() == 'CPython' and sys.version_info >= (3, 9)
    assert sys.platform in ('linux', 'darwin')
    class DlInfo(ctypes.Structure):
        _fields_ = [('filename', ctypes.c_char_p), ('base', ctypes.c_void_p),
                    ('symbol', ctypes.c_char_p), ('address', ctypes.c_void_p)]
    library = ctypes.CDLL(None)
    try:
        locate = library.dladdr
    except AttributeError:
        assert sys.platform == 'linux'
        library = ctypes.CDLL('libdl.so.2')
        locate = library.dladdr
    locate.argtypes = (ctypes.c_void_p, ctypes.POINTER(DlInfo))
    locate.restype = ctypes.c_int
    address = ctypes.cast(ctypes.pythonapi.Py_GetVersion, ctypes.c_void_p)
    info = DlInfo()
    assert address.value and locate(address, ctypes.byref(info)) and info.filename and info.symbol
    assert len(info.filename) <= 4096 and info.symbol in (b'Py_GetVersion', b'_Py_GetVersion')
    assert info.address == address.value, 'interpreter symbol did not bind exactly'
    executable = file_identity(pathlib.Path(sys.executable), 67108864)
    image = file_identity(pathlib.Path(info.filename.decode('utf-8')), 67108864)
    modules = {}
    for name in ('ctypes', '_ctypes'):
        module = sys.modules[name]
        origin = module.__spec__.origin
        assert origin not in (None, 'built-in', 'frozen')
        assert pathlib.Path(origin).resolve(strict=True) == pathlib.Path(module.__file__).resolve(strict=True)
        modules[name] = file_identity(pathlib.Path(origin), 4194304)
    return {'implementation': 'cpython-stdlib-ground-v1', 'pythonVersion': platform.python_version(),
            'platform': sys.platform, 'machine': platform.machine(), 'executable': executable,
            'interpreterImage': image, 'interpreterSymbol': info.symbol.decode(), 'modules': modules,
            'globalInstallationPerformed': False}


def bootstrap(out, worker):
    runtime = identity(worker.file_identity)
    raw = json.dumps(runtime, sort_keys=True, separators=(',', ':')) + '\n'
    (out / 'stdlib-bootstrap.json').write_text(raw)
    print(raw, end='')
