"""Bind a loaded ELF image without a compiler or private loader layouts."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import struct

ELF_HEADER = struct.Struct('<16sHHIQQQIHHHHHH')
PROGRAM_HEADER = struct.Struct('<IIQQQQQQ')
PT_LOAD = 1
RTLD_DI_LINKMAP = 2
ADDRESS_BYTES = 8
MAX_HEADERS = 4096
MAX_ADDRESS = (1 << 64) - 1


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_program_headers(library):
    with library.open('rb') as stream:
        header = stream.read(ELF_HEADER.size)
        if len(header) != ELF_HEADER.size:
            raise ValueError('Truncated ELF header')
        fields = ELF_HEADER.unpack(header)
        ident, kind, machine, version = fields[:4]
        offset, header_size, entry_size, count = fields[5], fields[8], fields[9], fields[10]
        if ident[:7] != b'\x7fELF\x02\x01\x01' or (kind, machine, version) != (3, 62, 1):
            raise ValueError('Only little-endian x86_64 shared ELF images are supported')
        if header_size != ELF_HEADER.size or entry_size != PROGRAM_HEADER.size or not 0 < count <= MAX_HEADERS:
            raise ValueError('Unsupported ELF program header shape')
        size = entry_size * count
        if offset < header_size or offset + size > library.stat().st_size:
            raise ValueError('Program headers exceed the ELF file')
        stream.seek(offset)
        raw = stream.read(size)
        if len(raw) != size:
            raise ValueError('Truncated ELF program headers')
    names = ['type', 'flags', 'fileOffset', 'virtualAddress', 'physicalAddress',
             'fileBytes', 'memoryBytes', 'alignment']
    rows = [dict(zip(names, PROGRAM_HEADER.unpack_from(raw, index * entry_size))) for index in range(count)]
    for row in rows:
        if row['type'] != PT_LOAD:
            continue
        if row['fileBytes'] > row['memoryBytes'] or row['fileOffset'] + row['fileBytes'] > library.stat().st_size:
            raise ValueError('Invalid ELF load segment file bounds')
        if row['virtualAddress'] + row['memoryBytes'] > MAX_ADDRESS:
            raise ValueError('ELF load segment address overflow')
        alignment = row['alignment']
        if alignment > 1 and (alignment & (alignment - 1) or
                              row['virtualAddress'] % alignment != row['fileOffset'] % alignment):
            raise ValueError('Invalid ELF load segment alignment')
    return header, offset, raw, rows


def read_loader_address(pointer, maps):
    if pointer:
        for line in maps.splitlines():
            parts = line.split(maxsplit=5)
            start, end = [int(value, 16) for value in parts[0].split('-')]
            if parts[1][0] == 'r' and start <= pointer and pointer + ADDRESS_BYTES <= end:
                # C guarantees no padding before the documented first Elf64_Addr member.
                return int.from_bytes(ctypes.string_at(pointer, ADDRESS_BYTES), 'little')
    raise ValueError('Loader address member is outside a readable mapping')


def bind_program_headers(library, base, offset, raw, rows, reader):
    owners = [row for row in rows if row['type'] == PT_LOAD and row['fileOffset'] <= offset and
              offset + len(raw) <= row['fileOffset'] + row['fileBytes']]
    if len(owners) != 1:
        raise ValueError('Program header load ownership is missing or ambiguous')
    owner = owners[0]
    address = base + owner['virtualAddress'] + offset - owner['fileOffset']
    if address + len(raw) > MAX_ADDRESS:
        raise ValueError('Loaded program header address overflow')
    identity = library.stat()
    matches = []
    for line in reader.maps.splitlines():
        parts = line.split(maxsplit=5)
        start, end = [int(value, 16) for value in parts[0].split('-')]
        device = tuple(int(value, 16) for value in parts[3].split(':'))
        same_file = (device == (os.major(identity.st_dev), os.minor(identity.st_dev)) and
                     int(parts[4]) == identity.st_ino and len(parts) == 6 and parts[5] == str(library))
        if same_file and parts[1][0] == 'r' and start <= address and address + len(raw) <= end and \
                int(parts[2], 16) + address - start == offset:
            matches.append(line)
    if len(matches) != 1:
        raise ValueError('Loaded program headers are not bound to the exact ELF file mapping')
    if reader.bytes(address, len(raw)) != raw:
        raise ValueError('Loaded program headers differ from the pinned ELF file')
    return address, matches[0]


def capture_loaded_module(library, expected, out, module, reader):
    library = library.resolve()
    if digest(library) != expected:
        raise ValueError('Library digest mismatch before loader query')
    before = library.stat()
    header, offset, raw, rows = read_program_headers(library)
    api = ctypes.CDLL(None)
    query = api.dlinfo
    query.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
    query.restype = ctypes.c_int
    link_map = ctypes.c_void_p()
    status = query(module._handle, RTLD_DI_LINKMAP, ctypes.byref(link_map))
    (out / 'loader-query.exit').write_text(str(status) + '\n')
    (out / 'loader-query.json').write_text(json.dumps(
        {'status': status, 'linkMapAddress': link_map.value, 'library': str(library),
         'librarySha256': expected, 'request': RTLD_DI_LINKMAP}, indent=2) + '\n')
    if status != 0:
        raise ValueError('Dynamic loader rejected the module handle query')
    maps = Path('/proc/self/maps').read_text()
    (out / 'loader-proc-maps.txt').write_text(maps)
    base = read_loader_address(link_map.value, maps)
    address, mapping = bind_program_headers(library, base, offset, raw, rows, reader)
    after = library.stat()
    identity = lambda stat: (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns)
    if identity(before) != identity(after) or digest(library) != expected:
        raise ValueError('Library changed during loader query')
    data = {'device': before.st_dev, 'inode': before.st_ino, 'baseAddress': base,
            'programHeaderCount': len(rows), 'programHeaders': rows,
            'linkMapAddress': link_map.value, 'loadedProgramHeaderAddress': address,
            'programHeaderFileOffset': offset, 'programHeaderBytesSha256': hashlib.sha256(raw).hexdigest(),
            'elfHeaderSha256': hashlib.sha256(header).hexdigest(), 'bindingMapping': mapping,
            'loadedProgramHeadersByteExactToFile': True,
            'loaderQuery': 'RTLD_DI_LINKMAP; documented first Elf64_Addr member only'}
    (out / 'loaded-module.json').write_text(json.dumps(data, indent=2) + '\n')
    provenance = {'library': str(library), 'librarySha256': expected,
                  'sourceSha256': digest(Path(__file__)),
                  'moduleMetadataSha256': digest(out / 'loaded-module.json'),
                  'diagnosticOnly': True, 'acceptedSavingsCredit': False}
    (out / 'loader-query-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    return api, data
