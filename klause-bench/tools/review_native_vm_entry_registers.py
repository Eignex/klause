"""Compare a retained entry prefix with hash-bound exported field contracts."""
import hashlib
import json
from pathlib import Path
import re
import sys

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
MAX_REPORT_BYTES = 32 * 1024 * 1024
MAX_RECORDS = 256
CONTRACTS = {
    '_ZN13CompileBroker25invoke_compiler_on_methodEP11CompileTask': {'rdi': 'CompileTask'},
    '_ZN7nmethod20post_compiled_methodEP11CompileTask': {'rdi': 'nmethod', 'rsi': 'CompileTask'},
    '_ZNK7nmethod10total_sizeEv': {'rdi': 'nmethod'},
}
REGISTERS = {'rax': 'eax', 'rbx': 'ebx', 'rcx': 'ecx', 'rdx': 'edx', 'rsi': 'esi',
             'rdi': 'edi', 'rbp': 'ebp', 'rsp': 'esp', **{f'r{i}': f'r{i}d' for i in range(8, 16)}}
SUBREGISTERS = {'rax': ('eax', 'ax', 'al', 'ah'), 'rbx': ('ebx', 'bx', 'bl', 'bh'),
                'rcx': ('ecx', 'cx', 'cl', 'ch'), 'rdx': ('edx', 'dx', 'dl', 'dh'),
                'rsi': ('esi', 'si', 'sil'), 'rdi': ('edi', 'di', 'dil'),
                'rbp': ('ebp', 'bp', 'bpl'), 'rsp': ('esp', 'sp', 'spl'),
                **{f'r{i}': (f'r{i}d', f'r{i}w', f'r{i}b') for i in range(8, 16)}}
ROOT_REGISTER = {name: root for root, names in SUBREGISTERS.items() for name in (root, *names)}
MEMORY = re.compile(r'(byte|word|dword|qword) ptr \[([a-z0-9]+)(?: ([+-]) (0x[0-9a-f]+|[0-9]+))?\]')
WIDTHS = {'byte': 1, 'word': 2, 'dword': 4, 'qword': 8}
MOVES = {'mov', 'movabs', 'movsx', 'movsxd', 'movzx'}
WRITES = {'add', 'sub', 'and', 'or', 'xor', 'adc', 'sbb', 'imul', 'shl', 'shr', 'sar', 'inc', 'dec', 'neg', 'not', 'pop'}


def captured(path, expected):
    raw = path.read_bytes()
    if len(raw) > MAX_REPORT_BYTES or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Captured report exceeds bounds or differs from pinned hash')
    return json.loads(raw)


def fields_from(metadata):
    if metadata['librarySha256'] != LIBRARY_SHA256:
        raise ValueError('Exported metadata belongs to a different library')
    fields = metadata['requiredLayoutFieldsCopiedVerbatim']
    if not 0 < len(fields) <= 256:
        raise ValueError('Exported field contract exceeds bounds')
    keys = set()
    for field in fields:
        key = field['typeName'], field['fieldName']
        if key in keys or field['isStatic'] != 0 or type(field['offset']) is not int or field['offset'] < 0 or \
                type(field['targetFieldWidth']) is not int or field['targetFieldWidth'] not in (1, 2, 4, 8):
            raise ValueError('Invalid or duplicate exported field contract')
        keys.add(key)
    return fields


def bound_instructions(entry):
    raw = bytes.fromhex(entry['entryHex'])
    if not 0 < len(raw) <= 16384 or len(raw) != entry['entryBytes'] or \
            hashlib.sha256(raw).hexdigest() != entry['entrySha256']:
        raise ValueError('Entry bytes differ from retained hash or bounds')
    address = entry['originalSymbol']['value']
    used = 0
    if len(entry['instructions']) > len(raw):
        raise ValueError('Instruction count exceeds input byte count')
    for instruction in entry['instructions']:
        encoded = bytes.fromhex(instruction['bytesHex'])
        if not encoded or instruction['address'] != address + used or \
                raw[used:used + len(encoded)] != encoded or used + len(encoded) > len(raw):
            raise ValueError('Captured instruction does not bind to retained entry bytes')
        used += len(encoded)
    if used != entry['decodedBytes'] or raw[used:].hex() != entry['undecodedHex']:
        raise ValueError('Captured decode boundary differs from retained input')
    return entry['instructions']


def prefix_accesses(instructions, incoming, fields):
    aliases = {register: (register, owner) for register, owner in incoming.items()}
    result = {'sourceArgumentContract': incoming, 'matchedExportedReads': [],
              'stopReason': 'end of captured instructions', 'inspectedInstructions': 0}
    for index, instruction in enumerate(instructions):
        mnemonic, operands = instruction['mnemonic'], instruction['operands'].split(', ')
        result['inspectedInstructions'] = index + 1
        if mnemonic.startswith('j') or mnemonic in {'call', 'ret', 'retf', 'syscall', 'int', 'ud2'}:
            result['stopReason'] = 'control-flow boundary: ' + mnemonic
            break
        if mnemonic in {'nop', 'endbr64'}:
            continue
        if mnemonic not in MOVES | WRITES | {'lea', 'cmp', 'test', 'push'}:
            result['stopReason'] = 'unsupported instruction: ' + mnemonic
            break
        if mnemonic == 'imul' and len(operands) == 1:
            result['stopReason'] = 'unsupported implicit register writes: imul'
            break
        for position, operand in enumerate(operands):
            memory = MEMORY.fullmatch(operand)
            if memory is None or mnemonic == 'lea' or mnemonic in MOVES | {'pop'} and position == 0:
                continue
            width, base, sign, displacement = memory.groups()
            if base not in aliases:
                continue
            offset = int(displacement, 0) if displacement else 0
            if sign == '-':
                offset = -offset
            register, owner = aliases[base]
            for field in fields:
                if field['typeName'] == owner and field['offset'] == offset and field['targetFieldWidth'] == WIDTHS[width]:
                    result['matchedExportedReads'].append({'instruction': dict(instruction),
                        'incomingRegister': register, 'baseAlias': base, 'exportedField': dict(field)})
        destination = ROOT_REGISTER.get(operands[0]) if operands else None
        if mnemonic in MOVES | WRITES | {'lea'} and destination:
            source_alias = aliases.get(operands[1]) if len(operands) == 2 else None
            if mnemonic == 'mov' and operands[0] in REGISTERS and operands[1] in REGISTERS and source_alias:
                aliases[destination] = source_alias
            else:
                aliases.pop(destination, None)
        if mnemonic in {'push', 'pop'}:
            aliases.pop('rsp', None)
    result['scope'] = 'Straight-line prefix only; source ABI contract corroboration, not live pointer or whole-function proof'
    return result


def inspect(entries, metadata, report):
    fields = fields_from(metadata)
    if not entries['completed'] or entries['librarySha256'] != LIBRARY_SHA256 or \
            entries['librarySha256After'] != LIBRARY_SHA256:
        raise ValueError('Entry capture is incomplete or belongs to a different library')
    groups = entries['entries']
    if sum(len(rows) for rows in groups.values()) > MAX_RECORDS:
        raise ValueError('Captured entry records exceed bounds')
    report['entries'] = {}
    for query, rows in groups.items():
        reviewed = report['entries'][query] = []
        for entry in rows:
            output = {'originalSymbol': entry['originalSymbol'], 'reviewCompleted': False,
                      'actualTargetCallingConventionProven': False}
            reviewed.append(output)
            incoming = CONTRACTS.get(entry['originalSymbol']['name'])
            if incoming is None:
                output['coverage'] = 'no exact source argument contract for this record'
                continue
            if not entry['completed'] or 'entryHex' not in entry:
                output['coverage'] = 'entry capture unavailable or failed'
                continue
            try:
                instructions = bound_instructions(entry)
                output['prefix'] = prefix_accesses(instructions, incoming, fields)
                output['reviewCompleted'] = True
            except Exception as error:
                output['error'] = {'type': type(error).__name__, 'message': str(error)}
    report['completed'] = True


def main():
    source, expected, metadata, metadata_hash, output = sys.argv[1:]
    report = {'completed': False, 'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'entriesReportSha256': expected, 'metadataReportSha256': metadata_hash,
              'diagnosticOnly': True, 'acceptedSavingsCredit': False, 'actualTargetCallingConventionProven': False,
              'scope': 'Remote saved-record prefix/field contract comparison only; no binary decode, target library load, private field inference, live pointers, root census, code-size, timing or savings'}
    try:
        inspect(captured(Path(source), expected), captured(Path(metadata), metadata_hash), report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        Path(output).write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
