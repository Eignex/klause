"""Inspect bounded ELF symbol records without loading or invoking the library."""
import hashlib
import json
import os
from pathlib import Path
import struct
import sys

ELF_HEADER = struct.Struct('<16sHHIQQQIHHHHHH')
SECTION_HEADER = struct.Struct('<IIQQQQIIQQ')
SYMBOL = struct.Struct('<IBBHQQ')
MAX_SECTIONS = 4096
MAX_SYMBOLS = 2000000
MAX_TABLE_BYTES = 128 * 1024 * 1024
MAX_NAME_BYTES = 65536
MAX_MATCHES = 4096
QUERIES = ('invoke_compiler_on_method', 'post_compiled_method', 'total_size')
SECTION_KEYS = ('nameOffset', 'type', 'flags', 'address', 'fileOffset', 'bytes',
                'link', 'info', 'alignment', 'entryBytes')


def read_at(stream, offset, size, file_size):
    if offset < 0 or size < 0 or size > MAX_TABLE_BYTES or offset + size > file_size:
        raise ValueError('ELF read exceeds file or diagnostic bounds')
    stream.seek(offset)
    raw = stream.read(size)
    if len(raw) != size:
        raise ValueError('Truncated ELF read')
    return raw


def string_at(raw, offset):
    if offset == 0 and not raw:
        return b''
    if not 0 <= offset < len(raw):
        raise ValueError('ELF name index exceeds linked string table')
    end = raw.find(b'\0', offset, min(len(raw), offset + MAX_NAME_BYTES + 1))
    if end < 0:
        raise ValueError('Unterminated or oversized ELF name')
    return raw[offset:end]


def read_strings(stream, section, file_size):
    if section['type'] != 3 or section['flags'] & 0x800:
        raise ValueError('Unsupported ELF linked string section')
    raw = read_at(stream, section['fileOffset'], section['bytes'], file_size)
    if raw and (raw[0] != 0 or raw[-1] != 0):
        raise ValueError('ELF string table lacks boundary terminators')
    return raw


def inspect(stream, file_size, report, queries=QUERIES):
    queries = query_names(queries)
    raw_header = read_at(stream, 0, ELF_HEADER.size, file_size)
    fields = ELF_HEADER.unpack(raw_header)
    ident, kind, machine, version = fields[:4]
    if ident[:7] != b'\x7fELF\x02\x01\x01' or (kind, machine, version) != (3, 62, 1):
        raise ValueError('Only little-endian x86_64 shared ELF images are supported')
    offset, header_size = fields[6], fields[8]
    entry_size, count, names_index = fields[11:14]
    report['elfHeaderHex'] = raw_header.hex()
    if header_size != ELF_HEADER.size or entry_size != SECTION_HEADER.size:
        raise ValueError('Unsupported ELF section header shape')
    if not 0 < count <= MAX_SECTIONS or count >= 0xff00 or names_index == 0xffff:
        raise ValueError('Absent, extended or oversized ELF section table')
    if offset < header_size or not 0 <= names_index < count:
        raise ValueError('Invalid ELF section table location or names index')
    raw_sections = read_at(stream, offset, count * entry_size, file_size)
    sections = [dict(zip(SECTION_KEYS, SECTION_HEADER.unpack_from(raw_sections, i * entry_size)))
                for i in range(count)]
    if any(raw_sections[:entry_size]):
        raise ValueError('Unsupported nonzero first ELF section header')
    for section in sections[1:]:
        if section['type'] != 8 and section['fileOffset'] + section['bytes'] > file_size:
            raise ValueError('ELF section exceeds file')
    names = read_strings(stream, sections[names_index], file_size) if names_index else b''
    for index, section in enumerate(sections):
        name = string_at(names, section['nameOffset'])
        section.update(index=index, nameHex=name.hex(), name=name.decode('ascii', errors='backslashreplace'))
    report.update(sectionTableFileOffset=offset, sectionTableSha256=hashlib.sha256(raw_sections).hexdigest(),
                  sections=sections, symbolTables=[], candidates={query: [] for query in queries})
    total_symbols = 0
    for section in sections:
        if section['type'] not in (2, 11):
            continue
        if section['flags'] & 0x800 or section['entryBytes'] != SYMBOL.size or section['bytes'] % SYMBOL.size:
            raise ValueError('Unsupported ELF symbol table shape')
        count = section['bytes'] // SYMBOL.size
        total_symbols += count
        if count == 0 or total_symbols > MAX_SYMBOLS or not 0 < section['link'] < len(sections):
            raise ValueError('Invalid ELF symbol table count or linked section')
        if section['info'] > count:
            raise ValueError('ELF first nonlocal symbol index exceeds table')
        strings_section = sections[section['link']]
        strings = read_strings(stream, strings_section, file_size)
        symbols = read_at(stream, section['fileOffset'], section['bytes'], file_size)
        if any(symbols[:SYMBOL.size]):
            raise ValueError('Invalid ELF undefined first symbol')
        table = {'sectionIndex': section['index'], 'sectionType': section['type'],
                 'symbolCount': count, 'symbolsScanned': 0, 'completed': False,
                 'symbolTableSha256': hashlib.sha256(symbols).hexdigest(),
                 'stringSectionIndex': section['link'], 'stringTableSha256': hashlib.sha256(strings).hexdigest()}
        report['symbolTables'].append(table)
        for index in range(count):
            raw_entry = symbols[index * SYMBOL.size:(index + 1) * SYMBOL.size]
            name_offset, info, other, owner, value, size = SYMBOL.unpack(raw_entry)
            name = string_at(strings, name_offset)
            if owner == 0xffff:
                raise ValueError('Extended ELF symbol section indexes are unsupported')
            if 0 < owner < 0xff00 and owner >= len(sections):
                raise ValueError('ELF symbol owner exceeds section table')
            for query in queries:
                if query.encode('ascii') not in name:
                    continue
                records = report['candidates'][query]
                if len(records) >= MAX_MATCHES:
                    raise ValueError('ELF candidate record limit exceeded')
                records.append({'tableSectionIndex': section['index'], 'symbolIndex': index,
                                'rawEntryHex': raw_entry.hex(), 'nameOffset': name_offset,
                                'nameHex': name.hex(), 'name': name.decode('ascii', errors='backslashreplace'),
                                'info': info, 'other': other, 'sectionIndex': owner, 'value': value, 'size': size,
                                'binding': info >> 4, 'symbolType': info & 15, 'undefined': owner == 0,
                                'ordinarySectionDefinition': 0 < owner < 0xff00,
                                'function': (info & 15) == 2})
            table['symbolsScanned'] = index + 1
        table['completed'] = True
    if not report['symbolTables']:
        raise ValueError('No ELF symbol tables available')
    report['hasFullSymbolTable'] = any(table['sectionType'] == 2 for table in report['symbolTables'])
    report['queryCoverage'] = {query: 'records retained' if records else 'absent from available tables'
                               for query, records in report['candidates'].items()}


def identity(stat):
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns


def inspect_file(library, expected, report, queries=QUERIES):
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        report.update(librarySha256=actual, fileBytes=before.st_size, device=before.st_dev, inode=before.st_ino)
        if actual != expected or identity(before) != identity(library.stat()):
            raise ValueError('Pinned library identity mismatch before inspection')
        inspect(stream, before.st_size, report, queries)
        stream.seek(0)
        after_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
        report['librarySha256After'] = after_hash
        if after_hash != expected or identity(before) != identity(os.fstat(stream.fileno())) or \
                identity(before) != identity(library.stat()):
            raise ValueError('Library changed during symbol inspection')
    report['completed'] = True


def query_names(values):
    if not 0 < len(values) <= 16:
        raise ValueError('Symbol query names are empty or oversized')
    for value in values:
        if not isinstance(value, str) or not 1 <= len(value) <= 128 or not value.isascii() or \
                not all(character.isalnum() or character == '_' for character in value):
            raise ValueError('Symbol query names must be bounded source identifiers')
    if len(set(values)) != len(values):
        raise ValueError('Symbol query names are duplicate')
    return tuple(values)


def main():
    library, expected, output = Path(sys.argv[1]).resolve(), sys.argv[2], Path(sys.argv[3])
    queries = tuple(sys.argv[4:]) if len(sys.argv) > 4 else QUERIES
    report = {'library': str(library), 'expectedLibrarySha256': expected, 'completed': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'queries': list(queries), 'matchPolicy': 'Raw source method-name substring; no mangled-name or ABI inference',
              'diagnosticOnly': True, 'acceptedSavingsCredit': False}
    try:
        queries = query_names(queries)
        if queries == QUERIES:
            inspect_file(library, expected, report)
        else:
            inspect_file(library, expected, report, queries)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
