"""Use the public C++ demangler with bounded buffers and provider identity."""
import ctypes
import json
import os
from pathlib import Path
import platform
import sys

from native_vm_loaded_module import capture_loaded_module, digest
from read_native_vm_metadata import MappedReader

MAX_INPUT_BYTES = 65536
MAX_OUTPUT_BYTES = 1024 * 1024


class DemanglingError(ValueError):
    def __init__(self, context):
        super().__init__('Native demangling failed; raw API result retained')
        self.context = context


def demangle(name, query, release):
    result = {'nameHex': name.hex(), 'status': None, 'demangled': None}
    if not name.startswith(b'_Z'):
        result['disposition'] = 'not an external C++ mangling'
        return result
    if len(name) > MAX_INPUT_BYTES or b'\0' in name:
        raise ValueError('Invalid or oversized external C++ name')
    capacity = ctypes.c_size_t(0)
    status = ctypes.c_int(-999)
    pointer = query(name, None, ctypes.byref(capacity), ctypes.byref(status))
    result.update(status=status.value, allocatedBytes=capacity.value, returnedPointer=pointer)
    try:
        if not pointer:
            if status.value == 0:
                raise ValueError('Demangler reported success without output')
            result['disposition'] = 'demangler rejected the external name'
            return result
        if status.value != 0:
            raise ValueError('Demangler returned output with failure status')
        if not 0 < capacity.value <= MAX_OUTPUT_BYTES:
            raise ValueError('Demangler allocation exceeds diagnostic bounds')
        raw = ctypes.string_at(pointer, capacity.value)
        end = raw.find(b'\0')
        if end < 0:
            raise ValueError('Demangled output is not terminated within its allocation')
        result.update(demangled=raw[:end].decode('ascii', errors='strict'),
                      demangledHex=raw[:end].hex(), disposition='demangled')
        return result
    except Exception as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise DemanglingError(result) from error
    finally:
        if pointer:
            release(pointer)


class NativeDemangler:
    def __init__(self, out):
        if platform.system() != 'Linux' or platform.machine() != 'x86_64' or sys.byteorder != 'little':
            raise ValueError('Only Linux x86_64 demangler provider binding is supported')
        self.module = ctypes.CDLL('libstdc++.so.6', mode=os.RTLD_LOCAL)
        self.query = getattr(self.module, '__cxa_demangle')
        self.query.argtypes = [ctypes.c_char_p, ctypes.c_void_p,
                               ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_int)]
        self.query.restype = ctypes.c_void_p
        self.allocator = ctypes.CDLL(None)
        self.release = self.allocator.free
        self.release.argtypes = [ctypes.c_void_p]
        self.release.restype = None
        pointer = ctypes.cast(self.query, ctypes.c_void_p).value
        maps = Path('/proc/self/maps').read_text()
        (out / 'demangler-proc-maps.txt').write_text(maps)
        matches = []
        for line in maps.splitlines():
            parts = line.split(maxsplit=5)
            start, end = [int(value, 16) for value in parts[0].split('-')]
            if pointer and start <= pointer < end and parts[1][:3] == 'r-x' and len(parts) == 6:
                library = Path(parts[5])
                if not library.name.startswith('libstdc++.so.') or library.resolve() != library:
                    raise ValueError('Demangler provider path is not an exact named library')
                stat = library.stat()
                device = tuple(int(value, 16) for value in parts[3].split(':'))
                if device != (os.major(stat.st_dev), os.minor(stat.st_dev)) or int(parts[4]) != stat.st_ino:
                    raise ValueError('Demangler provider mapping file identity differs')
                matches.append((library, line))
        if len(matches) != 1:
            raise ValueError('Demangler provider is missing or ambiguous')
        self.library, mapping = matches[0]
        self.expected = digest(self.library)
        self.identity = self.library.stat()
        reader = MappedReader(self.library)
        self.bridge, module = capture_loaded_module(self.library, self.expected, out, self.module, reader)
        executable = [row for row in module['programHeaders'] if row['type'] == 1 and row['flags'] & 1 and
                      module['baseAddress'] + row['virtualAddress'] <= pointer <
                      module['baseAddress'] + row['virtualAddress'] + row['fileBytes']]
        if len(executable) != 1:
            raise ValueError('Demangler entry is not in one file-backed executable provider segment')
        self.provenance = {'library': str(self.library), 'librarySha256': self.expected,
                           'symbol': '__cxa_demangle', 'entryPointer': pointer, 'mapping': mapping,
                           'loadedModule': module, 'sourceSha256': digest(Path(__file__)),
                           'scope': 'Public demangler provider only; target JVM library is not loaded or invoked'}
        (out / 'demangler-provider.json').write_text(json.dumps(self.provenance, indent=2) + '\n')

    def read(self, name):
        return demangle(name, self.query, self.release)

    def verify_unchanged(self):
        current = self.library.stat()
        identity = lambda stat: (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
        if identity(current) != identity(self.identity) or digest(self.library) != self.expected:
            raise ValueError('Demangler provider changed during the query')
