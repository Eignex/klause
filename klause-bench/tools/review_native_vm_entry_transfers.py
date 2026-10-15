"""Inventory explicit transfers in hash-bound saved linear instruction records."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys

import read_native_vm_entries as entries
import native_vm_entry_decoder as decoder

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
MAX_INPUT_BYTES = 32 * 1024 * 1024
LEGACY_PRODUCER_SHA256 = '22c11b59d656a11ad2df4736ff808cf929c2b1997c0dc5f598cfe8af13555300'
LEGACY_QUERIES = ('invoke_compiler_on_method', 'post_compiled_method', 'total_size',
                  'CompileQueue', 'CompileTaskWrapper')
RETURNS = {'ret', 'retq', 'retf', 'retfq', 'lret', 'iret', 'iretd', 'iretq'}
LOOPS = {'loop', 'loope', 'loopne', 'loopz', 'loopnz'}
CONDITIONALS = {'ja', 'jae', 'jb', 'jbe', 'jc', 'jcxz', 'jecxz', 'jrcxz', 'je', 'jg',
                'jge', 'jl', 'jle', 'jna', 'jnae', 'jnb', 'jnbe', 'jnc', 'jne', 'jng',
                'jnge', 'jnl', 'jnle', 'jno', 'jnp', 'jns', 'jnz', 'jo', 'jp', 'jpe',
                'jpo', 'js', 'jz'}
OTHER_TRANSFERS = {'lcall', 'ljmp', 'syscall', 'sysenter', 'sysret', 'sysretq', 'sysexit',
                   'int', 'int1', 'int3', 'into', 'ud0', 'ud1', 'ud2', 'hlt',
                   'vmcall', 'vmmcall', 'vmlaunch', 'vmresume', 'xbegin', 'xabort'}
PREFIXES = {'bnd', 'notrack', 'rep', 'repe', 'repne'}


def captured(path, expected, producer):
    if not all(re.fullmatch('[0-9a-f]{64}', value) for value in [expected, producer]):
        raise ValueError('Expected capture and producer hashes must be explicit')
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError('Saved entry report exceeds bounds')
    raw = path.read_bytes()
    if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Saved entry report hash differs')
    data = json.loads(raw)
    if data.get('sourceSha256') != producer or data.get('completed') is not True or \
            data.get('diagnosticOnly') is not True or data.get('acceptedSavingsCredit') is not False or \
            data.get('actualTargetCallingConventionProven') is not False or \
            any(data.get(name, False) is not False for name in
                ['actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']) or \
            any(data.get(name) != LIBRARY_SHA256 for name in
                ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']):
        raise ValueError('Saved producer, library or claim scope differs')
    provenance = data['decoder']
    if provenance.get('sha256') != decoder.WHEEL_SHA256 or provenance.get('version') != '5.0.6' or \
            provenance.get('ready') is not True or provenance.get('skipData') is not False or \
            provenance.get('globalInstallation') is not False or \
            not re.fullmatch('[0-9a-f]{64}', provenance.get('providerSha256', '')) or \
            provenance.get('providerSha256AfterLoad') != provenance['providerSha256']:
        raise ValueError('Original pinned decoder provenance is incomplete')
    return data


def instructions(entry):
    raw = bytes.fromhex(entry['entryHex'])
    if not 0 < len(raw) <= entries.MAX_ENTRY_BYTES or type(entry['entryBytes']) is not int or \
            len(raw) != entry['entryBytes'] or type(entry['decodedBytes']) is not int or \
            hashlib.sha256(raw).hexdigest() != entry['entrySha256']:
        raise ValueError('Saved instruction input differs from retained bytes')
    rows = entry['instructions']
    if not isinstance(rows, list) or len(rows) > len(raw):
        raise ValueError('Saved instruction count exceeds its byte bounds')
    start, used = entry['originalSymbol']['value'], 0
    for row in rows:
        encoded = bytes.fromhex(row['bytesHex'])
        if not 0 < len(encoded) <= 15 or type(row['address']) is not int or \
                row['address'] != start + used or \
                raw[used:used + len(encoded)] != encoded or used + len(encoded) > len(raw) or \
                not isinstance(row['mnemonic'], str) or not 0 < len(row['mnemonic']) <= 64 or \
                not isinstance(row['operands'], str) or len(row['operands']) > 512:
            raise ValueError('Saved instruction boundaries or text are invalid')
        used += len(encoded)
    if used != entry['decodedBytes'] or raw[used:].hex() != entry['undecodedHex']:
        raise ValueError('Saved decode boundary differs from its input')
    return rows


def classify(rows, symbol, retained_bytes, definitions):
    result = {'recognizedTransfers': [], 'otherInstructions': [],
              'actualControlFlowCoverageProven': False,
              'scope': 'Explicit recognized syntax in saved linear records only. No alternate decoding, '
                       'reachability, indirect-target, callee-return or runtime coverage inference.'}
    boundaries = {row['address'] for row in rows}
    start, end = symbol['value'], symbol['value'] + symbol['size']
    for index, row in enumerate(rows):
        parts = row['mnemonic'].split()
        while len(parts) > 1 and parts[0] in PREFIXES:
            parts.pop(0)
        mnemonic = ' '.join(parts)
        if mnemonic in {'call', 'callq'}:
            category = 'call'
        elif mnemonic in {'jmp', 'jmpq'}:
            category = 'jump'
        elif mnemonic in CONDITIONALS:
            category = 'conditional branch syntax'
        elif mnemonic in LOOPS:
            category = 'loop branch'
        elif mnemonic in RETURNS:
            category = 'return syntax'
        elif mnemonic in OTHER_TRANSFERS:
            category = 'other transfer syntax'
        else:
            result['otherInstructions'].append({'instructionIndex': index, 'instruction': dict(row)})
            continue
        transfer = {'instructionIndex': index, 'instruction': dict(row), 'category': category,
                    'target': None, 'capturedTargetDefinitions': [], 'targetStatus': 'not interpreted'}
        result['recognizedTransfers'].append(transfer)
        if category not in {'call', 'jump', 'conditional branch syntax', 'loop branch'}:
            continue
        if not re.fullmatch(r'0x[0-9a-fA-F]{1,16}', row['operands']):
            transfer['targetStatus'] = 'indirect or unsupported operand syntax'
            continue
        target = int(row['operands'], 16)
        transfer.update(target=target, capturedTargetDefinitions=definitions.get(target, []))
        if target in boundaries:
            transfer['targetStatus'] = 'retained linear instruction boundary'
        elif start <= target < start + retained_bytes:
            transfer['targetStatus'] = 'inside retained bytes without a linear instruction boundary'
        elif start <= target < end:
            transfer['targetStatus'] = 'inside unretained declared symbol span'
        else:
            transfer['targetStatus'] = 'outside this declared symbol span'
    result['recognizedTransferCounts'] = dict(Counter(row['category'] for row in result['recognizedTransfers']))
    return result


def inventory(data):
    if 'queries' in data:
        queries = entries.symbols.query_names(tuple(data['queries']))
    elif data.get('sourceSha256') == LEGACY_PRODUCER_SHA256:
        queries = entries.symbols.query_names(LEGACY_QUERIES)
    else:
        raise ValueError('Missing query contract for an unsupported producer version')
    if set(data['entries']) != set(queries) or set(data['symbols']['candidates']) != set(queries):
        raise ValueError('Saved query groups differ')
    if any(not isinstance(data['entries'][query], list) for query in queries) or \
            sum(len(data['entries'][query]) for query in queries) > entries.MAX_RECORDS:
        raise ValueError('Saved entry inventory exceeds bounds')
    definitions = {}
    for query in queries:
        rows = data['entries'][query]
        if [row['originalSymbol'] for row in rows] != data['symbols']['candidates'][query]:
            raise ValueError('Saved entry and symbol candidate inventories differ')
        for index, row in enumerate(rows):
            symbol = row['originalSymbol']
            if not all(type(symbol.get(name)) is int and 0 <= symbol[name] < 2 ** 64
                       for name in ['value', 'size', 'sectionIndex']) or symbol['value'] + symbol['size'] > 2 ** 64:
                raise ValueError('Saved symbol dimensions are invalid')
            if not all(type(symbol.get(name)) is bool for name in
                       ['function', 'undefined', 'ordinarySectionDefinition']):
                raise ValueError('Saved symbol flags are invalid')
            if symbol.get('function') is True and symbol.get('undefined') is False and \
                    symbol.get('ordinarySectionDefinition') is True and symbol['size'] > 0:
                definitions.setdefault(symbol['value'], []).append(
                    {'query': query, 'entryRecordIndex': index, 'originalSymbol': dict(symbol)})
    return queries, definitions


def inspect(library, expected, data, report):
    if expected != LIBRARY_SHA256:
        raise ValueError('Unsupported library identity')
    queries, definitions = inventory(data)
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if hashlib.file_digest(stream, 'sha256').hexdigest() != expected or \
                entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Pinned library identity differs before byte recheck')
        report.update(queries=queries, decoderCopiedVerbatim=data['decoder'], entries={},
                      queryContract='explicit saved queries' if 'queries' in data else 'pinned original producer defaults')
        for query in queries:
            output = report['entries'][query] = []
            for index, entry in enumerate(data['entries'][query]):
                row = {'entryRecordIndex': index, 'originalSymbol': entry['originalSymbol'],
                       'originalEntryCopiedVerbatim': entry,
                       'reviewCompleted': False, 'actualControlFlowCoverageProven': False}
                output.append(row)
                try:
                    if entry.get('completed') is not True or 'error' in entry:
                        row['status'] = 'original entry inspection incomplete'
                        continue
                    checked = {}
                    raw = entries.entry_bytes(stream, before.st_size, entry['originalSymbol'],
                                              data['symbols']['sections'], data['programHeaders'], checked)
                    row['byteRecheck'] = checked
                    if raw is None:
                        row['status'] = 'no defined nonempty function bytes'
                        continue
                    if any(entry.get(key) != value for key, value in checked.items()):
                        raise ValueError('Saved byte ownership differs from pinned library')
                    decoded = instructions(entry)
                    row.update(transferReview=classify(decoded, entry['originalSymbol'], len(raw), definitions),
                               wholeSymbolCaptured=not checked['truncated'],
                               allCapturedBytesDecoded=entry['decodedBytes'] == len(raw),
                               originalDecoderCoverage=entry['decoderCoverage'], reviewCompleted=True,
                               status='saved linear transfer metadata reviewed')
                except (ValueError, KeyError, IndexError, TypeError) as error:
                    row['error'] = {'type': type(error).__name__, 'message': str(error)}
        stream.seek(0)
        if hashlib.file_digest(stream, 'sha256').hexdigest() != expected or \
                entries.symbols.identity(before) != entries.symbols.identity(os.fstat(stream.fileno())) or \
                entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Pinned library changed during byte recheck')
    report['completed'] = True


def main():
    library, expected, source, source_hash, producer_hash, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'entriesReportSha256': source_hash, 'originalProducerSha256': producer_hash,
              'actualTargetCallingConventionProven': False, 'actualControlFlowCoverageProven': False,
              'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Remote saved linear transfer metadata and pinned file-byte recheck only. Original '
                       'producer/capture authentication required. No new decoding, symbol rescan, target load, '
                       'private field/register inference, reachability/inlining cause, runtime/clock/root/size '
                       'or compiler savings proof.'}
    try:
        data = captured(Path(source), source_hash, producer_hash)
        inspect(Path(library).resolve(), expected, data, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
