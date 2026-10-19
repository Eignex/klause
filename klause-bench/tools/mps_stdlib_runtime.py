"""Bind the executing CPython image without provisioning a symbolic solver."""
import ctypes
import json
import os
import pathlib
import platform
import resource
import sys
import types


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


def task_memory():
    class TaskInfo(ctypes.Structure):
        _fields_ = [('virtual', ctypes.c_uint64), ('resident', ctypes.c_uint64),
                    ('times', ctypes.c_uint64 * 4), ('counters', ctypes.c_int32 * 12)]
    assert ctypes.sizeof(TaskInfo) == 96
    library = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    read = library.proc_pidinfo
    read.argtypes = (ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int)
    read.restype = ctypes.c_int
    info = TaskInfo()
    if read(os.getpid(), 4, 0, ctypes.byref(info), ctypes.sizeof(info)) != ctypes.sizeof(info):
        raise OSError(ctypes.get_errno(), 'proc_pidinfo did not return complete task memory')
    assert 0 < info.resident <= info.virtual <= 4398046511104
    return info.virtual, info.resident


def set_growth_limit(path, mode, memory):
    assert mode in ('bootstrap', 'check')
    assert memory == (2147483648 if mode == 'bootstrap' else 4294967296)
    baseline, resident = task_memory()
    assert resident <= memory
    limit = baseline + memory
    resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
    assert resource.getrlimit(resource.RLIMIT_AS) == (limit, limit)
    value = {'mode': 'darwin-address-space-growth-v1', 'phase': mode, 'pid': os.getpid(),
             'allowanceBytes': memory, 'baselineVirtualBytes': baseline, 'baselineResidentBytes': resident,
             'addressSpaceLimitBytes': limit, 'postExecVerified': False,
             'postExecVirtualBytes': 0, 'postExecResidentBytes': 0}
    with path.open('x') as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())


def verify_growth_limit(out, mode):
    path = out / (mode + '.memory.json')
    value = json.loads(path.read_bytes())
    assert value['pid'] == os.getpid() and value['phase'] == mode
    assert value['mode'] == 'darwin-address-space-growth-v1'
    memory = 2147483648 if mode == 'bootstrap' else 4294967296
    assert value['allowanceBytes'] == memory
    assert 0 < value['baselineResidentBytes'] <= memory
    assert value['baselineResidentBytes'] <= value['baselineVirtualBytes'] <= 4398046511104
    limit = value['baselineVirtualBytes'] + memory
    assert value['addressSpaceLimitBytes'] == limit
    assert resource.getrlimit(resource.RLIMIT_AS) == (limit, limit)
    virtual, resident = task_memory()
    assert virtual <= limit and resident <= memory
    value.update(postExecVerified=True, postExecVirtualBytes=virtual, postExecResidentBytes=resident)
    temporary = path.with_name(path.name + '.partial')
    with temporary.open('x') as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def owned(runner, command, prefix, cwd, timeout, memory):
    if sys.platform != 'darwin':
        return runner.owned(command, prefix, cwd, timeout, memory)
    mode = command[3]
    assert mode in ('bootstrap', 'check')
    path = pathlib.Path(str(prefix) + '.memory.json')
    original = runner.resource
    def apply(which, limits):
        assert which == resource.RLIMIT_AS and limits == (memory, memory)
        set_growth_limit(path, mode, memory)
    try:
        # The private MPS runner owns one child; its other lifetime and stream controls stay intact.
        runner.resource = types.SimpleNamespace(RLIMIT_AS=resource.RLIMIT_AS, setrlimit=apply)
        process = runner.owned(command, prefix, cwd, timeout, memory)
    finally:
        runner.resource = original
    control = json.loads(path.read_bytes())
    process['addressSpaceBytes'] = control['addressSpaceLimitBytes']
    process['pid'] = control['pid']
    process['memoryControl'] = control
    runner.save(pathlib.Path(str(prefix) + '.process.json'), process)
    return process
