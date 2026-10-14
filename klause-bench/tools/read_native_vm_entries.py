"""Retain bounded executable symbol bytes and remote decoder output."""
import hashlib
import json
import os
from pathlib import Path
import struct
import sys

import read_native_vm_symbols as symbols

PROGRAM_HEADER = struct.Struct('<IIQQQQQQ')
PROGRAM_KEYS = ('type', 'flags', 'fileOffset', 'address', 'physicalAddress',
                'fileBytes', 'memoryBytes', 'alignment')
MAX_ENTRY_BYTES = 16384
MAX_RECORDS = 256
QUERIES = symbols.QUERIES + ('CompileQueue', 'CompileTaskWrapper')


def program_headers(stream, file_size, report):
    raw = symbols.read_at(stream, 0, symbols.ELF_HEADER.size, file_size)
    fields = symbols.ELF_HEADER.unpack(raw)
    offset, width, count = fields[5], fields[9], fields[10]
    if width != PROGRAM_HEADER.size or not 0 < count <= symbols.MAX_SECTIONS:
        raise ValueError('Unsupported ELF program header shape')
    if offset < symbols.ELF_HEADER.size:
        raise ValueError('Invalid ELF program header location')
    raw = symbols.read_at(stream, offset, count * width, file_size)
    rows = [dict(zip(PROGRAM_KEYS, PROGRAM_HEADER.unpack_from(raw, index * width)))
            for index in range(count)]
    report.update(programTableFileOffset=offset, programTableSha256=hashlib.sha256(raw).hexdigest(),
                  programTableHex=raw.hex(), programHeaders=rows)
    for row in rows:
        if row['fileOffset'] + row['fileBytes'] > file_size or row['fileBytes'] > row['memoryBytes']:
            raise ValueError('ELF program segment exceeds file or memory span')
    return rows


def entry_bytes(stream, file_size, record, sections, programs, output):
    if not record['function'] or record['undefined'] or not record['ordinarySectionDefinition']:
        output['byteCoverage'] = 'not an ordinary defined function'
        return None
    section = sections[record['sectionIndex']]
    start, size = record['value'], record['size']
    if size == 0:
        output['byteCoverage'] = 'zero-size function; no inferred extent'
        return None
    if section['type'] != 1 or section['flags'] & 6 != 6 or section['flags'] & 0x800:
        raise ValueError('Function section is not uncompressed allocated executable data')
    if start < section['address'] or start + size > section['address'] + section['bytes']:
        raise ValueError('Function span exceeds owning executable section')
    offset = section['fileOffset'] + start - section['address']
    owners = [row for row in programs if row['type'] == 1 and row['flags'] & 1 and
              row['address'] <= start and start + size <= row['address'] + row['fileBytes'] and
              row['fileOffset'] + start - row['address'] == offset]
    if len(owners) != 1:
        raise ValueError('Function span lacks one matching executable file-backed segment')
    length = min(size, MAX_ENTRY_BYTES)
    raw = symbols.read_at(stream, offset, length, file_size)
    output.update(byteCoverage='bounded prefix' if length < size else 'complete symbol span',
                  fileOffset=offset, executableSegment=dict(owners[0]), entryBytes=len(raw),
                  entryHex=raw.hex(), entrySha256=hashlib.sha256(raw).hexdigest(),
                  truncated=length < size)
    return raw


def decode(raw, address, decoder, output):
    output.update(instructions=[], decodedBytes=0, decoderCoverage='not completed')
    for instruction in decoder.disasm(raw, address):
        used = output['decodedBytes']
        encoded = bytes(instruction.bytes)
        if instruction.address != address + used or not encoded or \
                used + len(encoded) > len(raw) or encoded != raw[used:used + len(encoded)]:
            raise ValueError('Decoder instruction does not bind to exact input bytes')
        row = {'address': instruction.address, 'bytesHex': encoded.hex(),
               'mnemonic': instruction.mnemonic, 'operands': instruction.op_str}
        output['instructions'].append(row)
        output['decodedBytes'] += len(encoded)
    output['undecodedHex'] = raw[output['decodedBytes']:].hex()
    output['decoderCoverage'] = 'complete input' if output['decodedBytes'] == len(raw) else 'stopped before input end'


def inspect_file(library, expected, decoder, report, queries=QUERIES):
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        report.update(librarySha256=actual, fileBytes=before.st_size)
        if actual != expected or symbols.identity(before) != symbols.identity(library.stat()):
            raise ValueError('Pinned library identity mismatch before entry inspection')
        captured = report['symbols'] = {}
        symbols.inspect(stream, before.st_size, captured, queries)
        programs = program_headers(stream, before.st_size, report)
        records = sum(len(rows) for rows in captured['candidates'].values())
        if records > MAX_RECORDS:
            raise ValueError('Entry candidate count exceeds diagnostic bounds')
        report['entries'] = {}
        for query, rows in captured['candidates'].items():
            entries = report['entries'][query] = []
            for record in rows:
                output = {'originalSymbol': dict(record), 'completed': False}
                entries.append(output)
                try:
                    raw = entry_bytes(stream, before.st_size, record, captured['sections'], programs, output)
                    if raw is not None:
                        decode(raw, record['value'], decoder, output)
                    output['completed'] = True
                except Exception as error:
                    output['error'] = {'type': type(error).__name__, 'message': str(error)}
        stream.seek(0)
        report['librarySha256After'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        if report['librarySha256After'] != expected or \
                symbols.identity(before) != symbols.identity(os.fstat(stream.fileno())) or \
                symbols.identity(before) != symbols.identity(library.stat()):
            raise ValueError('Library changed during entry inspection')
    report['allRecordInspectionsCompleted'] = all(row['completed'] for rows in report['entries'].values() for row in rows)
    report['completed'] = True


def main():
    library, expected, output = Path(sys.argv[1]).resolve(), sys.argv[2], Path(sys.argv[3])
    report = {'library': str(library), 'expectedLibrarySha256': expected, 'completed': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualTargetCallingConventionProven': False,
              'scope': 'Linear decoding of symbol-owned bytes; no control-flow, argument, field-offset or live task inference'}
    try:
        from native_vm_entry_decoder import load_decoder
        decoder = load_decoder(output.parent, report)
        inspect_file(library, expected, decoder, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
