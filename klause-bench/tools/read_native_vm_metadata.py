"""Read hash-bound HotSpot serviceability layout metadata on a remote worker."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import sys

from native_vm_loaded_module import capture_loaded_module

MAX_ROWS = 16384
MAX_STRING = 512
PT_LOAD = 1
PF_R = 4
REQUIRED_FIELDS = {
    'CompileTask': ['_method', '_compile_id', '_osr_bci', '_comp_level', '_num_inlined_bytecodes'],
    'nmethod': ['_method', '_compile_id', '_entry_bci', '_comp_level', '_immutable_data_size'],
    'CodeBlob': ['_size', '_content_offset', '_data_offset'],
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class MappingReadError(ValueError):
    def __init__(self, address, size, reader):
        super().__init__('Metadata read is outside a readable mapping')
        self.context = {'address': address, 'bytes': size, 'acceptedModuleRanges': reader.ranges,
                        'maps': getattr(reader, 'maps', None),
                        'libraryIdentity': getattr(reader, 'library_identity', None),
                        'loadedModule': getattr(reader, 'loaded_module', None)}


def loaded_readable_ranges(module, identity):
    if module['device'] != identity['device'] or module['inode'] != identity['inode']:
        raise ValueError('Loaded module file identity mismatch')
    headers = module['programHeaders']
    if not 0 < module['programHeaderCount'] == len(headers) <= 4096:
        raise ValueError('Unsupported loaded program header count')
    base = module['baseAddress']
    ranges = []
    for header in headers:
        if header['type'] != PT_LOAD:
            continue
        offset, file_size, memory_size = header['virtualAddress'], header['fileBytes'], header['memoryBytes']
        if min(base, offset, file_size, memory_size) < 0 or file_size > memory_size:
            raise ValueError('Invalid loaded segment bounds')
        start, end = base + offset, base + offset + memory_size
        if end > (1 << 64) - 1:
            raise ValueError('Loaded segment address overflow')
        if memory_size and header['flags'] & PF_R:
            ranges.append((start, end))
    if not ranges:
        raise ValueError('No readable loaded module segments')
    return ranges


class MappedReader:
    def __init__(self, library, loaded_module=None):
        self.ranges = []
        self.loaded_module = loaded_module
        stat = library.stat()
        self.library_identity = {'path': str(library), 'device': stat.st_dev, 'inode': stat.st_ino}
        owned = loaded_readable_ranges(loaded_module, self.library_identity) if loaded_module else None
        self.maps = Path('/proc/self/maps').read_text()
        for line in self.maps.splitlines():
            parts = line.split(maxsplit=5)
            span, permissions = parts[:2]
            device = tuple(int(value, 16) for value in parts[3].split(':'))
            same_file = device == (os.major(stat.st_dev), os.minor(stat.st_dev)) and int(parts[4]) == stat.st_ino
            named = len(parts) == 6 and parts[5] == str(library) and same_file
            anonymous = len(parts) == 5 and device == (0, 0) and int(parts[4]) == 0 and permissions[-1] == 'p'
            if permissions[0] == 'r' and (named or owned is not None and anonymous):
                start, end = [int(value, 16) for value in span.split('-')]
                if owned is None:
                    self.ranges.append((start, end))
                else:
                    for lower, upper in owned:
                        if max(start, lower) < min(end, upper):
                            self.ranges.append((max(start, lower), min(end, upper)))

    def bytes(self, address, size):
        if not address or size <= 0 or not any(start <= address and address + size <= end
                                              for start, end in self.ranges):
            raise MappingReadError(address, size, self)
        return ctypes.string_at(address, size)

    def integer(self, address, size, signed=False):
        return int.from_bytes(self.bytes(address, size), sys.byteorder, signed=signed)

    def string(self, address):
        if not address:
            return None
        result = bytearray()
        for offset in range(MAX_STRING):
            value = self.bytes(address + offset, 1)
            if value == b'\0':
                return result.decode('utf-8', errors='strict')
            result.extend(value)
        raise ValueError('Unterminated metadata string')


def exported_u64(module, reader, name):
    symbol = ctypes.c_uint64.in_dll(module, name)
    return reader.integer(ctypes.addressof(symbol), 8)


def table(module, reader, name, members):
    prefix = 'gHotSpotVM' + name + 'Entry'
    stride = exported_u64(module, reader, prefix + 'ArrayStride')
    offsets = {label: exported_u64(module, reader, prefix + symbol + 'Offset')
               for label, symbol, width, signed, pointer in members}
    if stride <= 0 or stride > 4096:
        raise ValueError('Unsupported metadata stride')
    for label, symbol, width, signed, pointer in members:
        if offsets[label] + width > stride:
            raise ValueError('Metadata member exceeds entry stride')
    symbol = ctypes.c_void_p.in_dll(module, 'gHotSpotVM' + name + 's')
    root = reader.integer(ctypes.addressof(symbol), ctypes.sizeof(ctypes.c_void_p))
    rows = []
    terminator = 'fieldName' if name == 'Struct' else 'typeName' if name == 'Type' else 'name'
    for index in range(MAX_ROWS):
        row = {}
        for label, symbol, width, signed, pointer in members:
            raw = reader.integer(root + index * stride + offsets[label], width, signed)
            row[label] = reader.string(raw) if pointer else raw
        if row[terminator] is None:
            return {'stride': stride, 'offsets': offsets, 'terminated': True, 'rows': rows}
        rows.append(row)
    raise ValueError('Unterminated metadata table')


def inspect(library, expected, out):
    if platform.system() != 'Linux' or platform.machine() != 'x86_64' or sys.byteorder != 'little':
        raise ValueError('Only the pinned Linux x86_64 runtime is supported')
    pointer_width = ctypes.sizeof(ctypes.c_void_p)
    if pointer_width != 8 or digest(library) != expected:
        raise ValueError('Runtime ABI or digest mismatch')
    before = library.stat()
    module = ctypes.CDLL(str(library), mode=os.RTLD_LOCAL)
    file_reader = MappedReader(library)
    bridge, loaded_module = capture_loaded_module(library, expected, out, module, file_reader)
    reader = MappedReader(library, loaded_module)
    reader.loader_bridge = bridge
    structs = table(module, reader, 'Struct', [
        ('typeName', 'TypeName', pointer_width, False, True),
        ('fieldName', 'FieldName', pointer_width, False, True),
        ('typeString', 'TypeString', pointer_width, False, True),
        ('isStatic', 'IsStatic', 4, True, False),
        ('offset', 'Offset', 8, False, False),
    ])
    types = table(module, reader, 'Type', [
        ('typeName', 'TypeName', pointer_width, False, True),
        ('superclassName', 'SuperclassName', pointer_width, False, True),
        ('isIntegerType', 'IsIntegerType', 4, True, False),
        ('isUnsigned', 'IsUnsigned', 4, True, False),
        ('size', 'Size', 8, False, False),
    ])
    constants = table(module, reader, 'IntConstant', [
        ('name', 'Name', pointer_width, False, True),
        ('value', 'Value', 4, True, False),
    ])
    selected = []
    for type_name, names in REQUIRED_FIELDS.items():
        for field in names:
            matches = [row for row in structs['rows'] if row['typeName'] == type_name and row['fieldName'] == field]
            if len(matches) != 1 or matches[0]['isStatic'] != 0:
                raise ValueError('Missing, duplicate or static required field')
            row = matches[0]
            owners = [item for item in types['rows'] if item['typeName'] == type_name]
            if len(owners) != 1 or not 0 <= row['offset'] < owners[0]['size']:
                raise ValueError('Required field exceeds target type')
            if row['typeString'].endswith('*'):
                width = pointer_width
            else:
                targets = [item for item in types['rows'] if item['typeName'] == row['typeString']]
                if len(targets) != 1 or targets[0]['isIntegerType'] != 1 or targets[0]['size'] not in (1, 2, 4, 8):
                    raise ValueError('Unsupported exported field type')
                width = targets[0]['size']
            if row['offset'] + width > owners[0]['size']:
                raise ValueError('Required field width exceeds target type')
            selected.append({**row, 'targetFieldWidth': width})
    hierarchy = [row for row in types['rows'] if row['typeName'] == 'nmethod']
    if len(hierarchy) != 1 or hierarchy[0]['superclassName'] != 'CodeBlob':
        raise ValueError('Unexpected exported nmethod hierarchy')
    entry = [row for row in constants['rows'] if row['name'] == 'InvocationEntryBci']
    after = library.stat()
    identity = lambda stat: (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns)
    if len(entry) != 1 or digest(library) != expected or identity(before) != identity(after):
        raise ValueError('Missing OSR constant or runtime changed during read')
    return {'librarySha256': expected, 'pointerWidth': pointer_width,
            'libraryIdentity': {'device': before.st_dev, 'inode': before.st_ino,
                                'size': before.st_size, 'mtimeNanos': before.st_mtime_ns},
            'readableModuleRanges': reader.ranges,
            'loadedModule': reader.loaded_module,
            'structTable': structs, 'typeTable': types, 'intConstantTable': constants,
            'requiredFields': selected, 'invocationEntryBci': entry[0],
            'diagnosticOnly': True, 'acceptedSavingsCredit': False,
            'scope': 'Own-process exported static layout metadata only; no JVM creation, compiler-task instance read, '
                     'solver/JIT calls, memory writes, guessed host layout or timing/code-size reconstruction.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('library', type=Path)
    parser.add_argument('sha256')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        result = inspect(args.library.resolve(), args.sha256, args.output.parent)
    except Exception as error:
        args.output.write_text(json.dumps({'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                                         'completed': False, 'failureType': type(error).__name__,
                                         'failure': str(error),
                                         'mappingReadContext': getattr(error, 'context', None)}, indent=2) + '\n')
        raise
    args.output.write_text(json.dumps({**result, 'completed': True}, indent=2) + '\n')
