"""Recheck pinned caller bytes and retain decoder-bound linear incoming edges."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys

import native_vm_entry_decoder as package
import read_native_vm_entries as entries
import review_native_vm_entry_transfers as transfers
import review_native_vm_task_calls as calls

MAX_OUTPUT_BYTES = calls.MAX_OUTPUT_BYTES
MAX_DETAIL_RECORDS = 20000
GENERIC_GROUPS = {1: 'jump', 2: 'call', 3: 'ret', 4: 'int', 5: 'iret',
                  6: 'privilege', 7: 'branch_relative'}
DENIED = ('actualTargetCallingConventionProven', 'actualTaskGenerationProven',
          'actualCompiledTaskLifetimeProven', 'privateFieldReadsAuthorized',
          'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven',
          'globalLossFreeCaptureProven', 'compilerElapsedTimeProven', 'acceptedSavingsCredit',
          'nativeContinuationExclusiveEntryProven', 'returnProbeAuthorized')


def integer(value, lower, upper):
    if type(value) is not int or not lower <= value <= upper:
        raise ValueError('Decoder detail integer is outside its declared bounds')
    return value


def text(value):
    if not isinstance(value, str) or not value or len(value) > 64 or not value.isascii() or \
            any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError('Decoder detail name is absent or malformed')
    return value


def details(instruction, row):
    row.update(address=integer(instruction.address, 0, 2 ** 64 - 1),
               bytesHex=bytes(instruction.bytes).hex(), mnemonic=instruction.mnemonic,
               operandsText=instruction.op_str, id=integer(instruction.id, 1, 2 ** 32 - 1),
               groups=[], operands=[])
    groups, operands = instruction.groups, instruction.operands
    if len(groups) > 8 or len(operands) > 8:
        raise ValueError('Decoder detail inventory exceeds the pinned API bounds')
    for group in groups:
        name = instruction.group_name(group)
        row['groups'].append({'id': group, 'name': name})
        integer(group, 1, 255)
        text(name)
        if group < 128 and GENERIC_GROUPS.get(group) != name:
            raise ValueError('Unknown or inconsistent architecture-independent decoder group')
    if len(set(groups)) != len(groups):
        raise ValueError('Duplicated decoder semantic group')
    for operand in operands:
        kind = integer(operand.type, 1, 3)
        item = {'type': kind, 'size': integer(operand.size, 0, 64),
                'access': integer(operand.access, 0, 3)}
        if kind == 1:
            item.update(register=integer(operand.reg, 1, 2 ** 16 - 1),
                        registerName=text(instruction.reg_name(operand.reg)))
        elif kind == 2:
            item['immediate'] = integer(operand.imm, -(2 ** 63), 2 ** 64 - 1)
        else:
            memory = operand.mem
            item['memory'] = {key: integer(getattr(memory, key), 0, 2 ** 16 - 1)
                              for key in ['segment', 'base', 'index']}
            item['memory'].update(scale=integer(memory.scale, 0, 8),
                                  displacement=integer(memory.disp, -(2 ** 63), 2 ** 63 - 1))
        row['operands'].append(item)
    return row


def normal_edges(row):
    encoded = bytes.fromhex(row['bytesHex'])
    pc = row['address']
    if not 1 <= len(encoded) <= 15 or pc + len(encoded) >= 2 ** 64:
        raise ValueError('Instruction byte extent is invalid')
    after = pc + len(encoded)
    groups = {group['id'] for group in row['groups'] if group['id'] < 128}
    if groups & {4, 5, 6}:
        raise ValueError('Interrupt, privilege or interrupt-return semantics are unsupported')
    if 3 in groups:
        if groups != {3} or not (encoded == b'\xc3' or len(encoded) == 3 and encoded[0] == 0xc2):
            raise ValueError('Return is outside the fixed normal-return encoding')
        return []
    if groups & {1, 2, 7}:
        if not groups <= {1, 2, 7} or (1 in groups) == (2 in groups) or \
                len(row['operands']) != 1 or row['operands'][0]['type'] != 2:
            raise ValueError('Indirect or ambiguous control transfer has no bounded target')
        if 2 in groups and len(encoded) == 5 and encoded[0] == 0xe8:
            width, conditional, kind = 4, False, 'call'
        elif 1 in groups and len(encoded) == 5 and encoded[0] == 0xe9:
            width, conditional, kind = 4, False, 'jump'
        elif 1 in groups and len(encoded) == 2 and encoded[0] == 0xeb:
            width, conditional, kind = 1, False, 'jump'
        elif 1 in groups and len(encoded) == 2 and (0x70 <= encoded[0] <= 0x7f or 0xe0 <= encoded[0] <= 0xe3):
            width, conditional, kind = 1, True, 'branch'
        elif 1 in groups and len(encoded) == 6 and encoded[0] == 0x0f and 0x80 <= encoded[1] <= 0x8f:
            width, conditional, kind = 4, True, 'branch'
        else:
            raise ValueError('Transfer is outside the fixed direct-relative encodings')
        target = after + int.from_bytes(encoded[-width:], 'little', signed=True)
        if not 0 <= target < 2 ** 64 or row['operands'][0]['immediate'] % (2 ** 64) != target:
            raise ValueError('Encoded transfer and detailed immediate target differ')
        edges = [{'kind': kind, 'target': target}]
        if kind == 'call':
            edges.append({'kind': 'normal-call-return', 'target': after})
        elif conditional:
            edges.append({'kind': 'fallthrough', 'target': after})
        return edges
    mnemonic = row['mnemonic'].split()
    while len(mnemonic) > 1 and mnemonic[0] in transfers.PREFIXES:
        mnemonic.pop(0)
    if ' '.join(mnemonic) in (transfers.RETURNS | transfers.LOOPS | transfers.CONDITIONALS |
                             transfers.OTHER_TRANSFERS | {'call', 'callq', 'jmp', 'jmpq'}):
        raise ValueError('Transfer syntax has no matching decoder semantic group')
    return [{'kind': 'fallthrough', 'target': after}]


def linear_inventory(rows, start, size):
    result = {'complete': False, 'edges': [], 'errors': [],
              'scope': 'Pinned decoder normal linear transfer model only; no alternate entry, '
                       'transitive callee effects, dynamic return or whole-program control-flow proof.'}
    cursor, boundaries = start, set()
    for index, row in enumerate(rows):
        if row['address'] != cursor or row['address'] in boundaries:
            result['errors'].append({'instructionIndex': index, 'message': 'Noncontiguous detailed instructions'})
            return result
        boundaries.add(cursor)
        cursor += len(bytes.fromhex(row['bytesHex']))
    if not rows or cursor != start + size:
        result['errors'].append({'message': 'Detailed instructions do not cover the complete caller'})
        return result
    for index, row in enumerate(rows):
        try:
            for edge in normal_edges(row):
                result['edges'].append({'instructionIndex': index, **edge})
                if start <= edge['target'] < start + size and edge['target'] not in boundaries:
                    raise ValueError('Transfer enters bytes outside retained instruction boundaries')
                if edge['kind'] in {'fallthrough', 'normal-call-return'} and edge['target'] not in boundaries:
                    raise ValueError('Normal continuation leaves the complete captured caller')
        except (ValueError, KeyError, TypeError, IndexError) as error:
            result['errors'].append({'instructionIndex': index, 'message': str(error)})
    result['complete'] = not result['errors']
    return result


def continuation_guards(group, rows, inventory, concordant):
    result = []
    for original in group['transfers']:
        item = {'originalTransfer': original, 'selectedNormalLinearContinuationEntryBound': False,
                'nativeContinuationExclusiveEntryProven': False, 'returnProbeAuthorized': False,
                'status': 'outside fixed broker normal-call plan'}
        result.append(item)
        if original['normalCallContinuationProven'] is not True:
            continue
        index = original['originalTransfer']['instructionIndex']
        item['status'] = 'unproved linear continuation entry'
        if not concordant or not inventory['complete'] or not 0 <= index < len(rows):
            continue
        row = rows[index]
        before = original['instruction']
        if any(row[key] != before[key] for key in ['address', 'bytesHex', 'mnemonic']) or \
                row['operandsText'] != before['operands']:
            continue
        continuation = original['continuation']
        incoming = [edge for edge in inventory['edges'] if edge['target'] == continuation]
        item['incomingEdges'] = incoming
        if continuation != rows[0]['address'] and incoming == [
                {'instructionIndex': index, 'kind': 'normal-call-return', 'target': continuation}]:
            item.update(selectedNormalLinearContinuationEntryBound=True,
                        status='sole incoming edge in pinned normal linear model')
    return result


def inspect(paths, expected, library, out, report):
    original = report['callerSourceReview'] = {'diagnosticOnly': True, **dict.fromkeys(DENIED, False),
        'sourceSha256': hashlib.sha256(Path(calls.__file__).read_bytes()).hexdigest()}
    calls.inspect(paths, expected, out, original)
    report['inputSha256'] = dict(zip(calls.INPUTS, expected))
    saved = json.loads((out / 'captured-vm-entries.json').read_text())
    decoder = package.load_decoder(out, report)
    module = importlib.import_module('capstone')
    if module.cs_support(module.CS_SUPPORT_DIET) is not False:
        raise ValueError('Diet decoder lacks complete semantic detail')
    decoder.detail = True
    decoder.syntax = module.CS_OPT_SYNTAX_INTEL
    if decoder.detail is not True or decoder.skipdata is not False or \
            decoder.arch != module.CS_ARCH_X86 or decoder.mode != module.CS_MODE_64 or \
            decoder.syntax != module.CS_OPT_SYNTAX_INTEL:
        raise ValueError('Detailed decoder options differ')
    report['decoder'].update(detail=True, diet=False, architecture=decoder.arch,
                             mode=decoder.mode, syntax=decoder.syntax)
    members = {row['name']: row['sha256'] for row in report['decoder']['members']}
    private = Path(report['decoder']['providerPath']).parent.parent
    sources = [private / name for name in ['__init__.py', 'x86.py', 'x86_const.py']]
    for path in sources:
        if hashlib.sha256(path.read_bytes()).hexdigest() != members['capstone/' + path.name]:
            raise ValueError('Private decoder Python binding differs from pinned wheel')
    output = report['callerSemanticRecords'] = []
    used = 0
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if hashlib.file_digest(stream, 'sha256').hexdigest() != calls.abi.LIBRARY_SHA256 or \
                entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Fresh library differs before detailed caller review')
        for index, group in enumerate(original['callerTransfers']):
            entry = group['originalEntry']
            record = {'originalCallerIndex': index, 'originalEntry': entry, 'instructions': [],
                      'linearInventory': {'complete': False}, 'continuationGuards': [], 'completed': False}
            output.append(record)
            checked = {}
            data = entries.entry_bytes(stream, before.st_size, entry['originalSymbol'],
                                       saved['symbols']['sections'], saved['programHeaders'], checked)
            if data is None or any(entry.get(key) != value for key, value in checked.items()):
                raise ValueError('Detailed caller bytes differ from original executable ownership')
            previous = transfers.instructions(entry)
            used += len(previous)
            if used > MAX_DETAIL_RECORDS:
                raise ValueError('Detailed instruction inventory exceeds bounds')
            for position, instruction in enumerate(decoder.disasm(data, entry['originalSymbol']['value'])):
                row = {}
                record['instructions'].append(row)
                details(instruction, row)
                if position >= len(previous) or any(row[key] != previous[position][key]
                        for key in ['address', 'bytesHex', 'mnemonic']) or \
                        row['operandsText'] != previous[position]['operands']:
                    raise ValueError('Detailed decoder differs from original retained instruction')
            if len(record['instructions']) != len(previous):
                raise ValueError('Detailed decoder stopped before original instruction end')
            record['linearInventory'] = linear_inventory(record['instructions'], entry['originalSymbol']['value'],
                                                         entry['originalSymbol']['size'])
            record['continuationGuards'] = continuation_guards(group, record['instructions'],
                record['linearInventory'], original['callerSourceDeclarationConcordant'])
            record['completed'] = True
        stream.seek(0)
        if hashlib.file_digest(stream, 'sha256').hexdigest() != calls.abi.LIBRARY_SHA256 or \
                entries.symbols.identity(before) != entries.symbols.identity(os.fstat(stream.fileno())) or \
                entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Fresh library changed during detailed caller review')
    provider = Path(report['decoder']['providerPath'])
    if hashlib.sha256(provider.read_bytes()).hexdigest() != report['decoder']['providerSha256'] or any(
            hashlib.sha256(path.read_bytes()).hexdigest() != members['capstone/' + path.name] for path in sources):
        raise ValueError('Private detailed decoder changed during caller review')
    report['allCallerInstructionDetailsBound'] = bool(output) and all(row['completed'] for row in output)
    guards = [guard for row in output for guard in row['continuationGuards']
              if guard['originalTransfer']['normalCallContinuationProven'] is True]
    report['selectedNormalLinearContinuationEntriesBound'] = bool(guards) and all(
        guard['selectedNormalLinearContinuationEntryBound'] for guard in guards)
    report['completed'] = True


def report_bytes(report):
    raw = (json.dumps(report, separators=(',', ':')) + '\n').encode()
    if len(raw) <= MAX_OUTPUT_BYTES:
        return raw
    minimal = {key: report[key] for key in ['sourceSha256', 'diagnosticOnly', 'scope', *DENIED]}
    minimal.update(completed=False, allCallerInstructionDetailsBound=False,
                   selectedNormalLinearContinuationEntriesBound=False, derivedInventoryWithheld=True,
                   error={'type': 'ValueError', 'message': 'Detailed caller output exceeds bounds'},
                   inputSha256=report.get('inputSha256', {}))
    raw = (json.dumps(minimal, separators=(',', ':')) + '\n').encode()
    if len(raw) > MAX_OUTPUT_BYTES:
        raise ValueError('Failure metadata exceeds detailed output bounds')
    return raw


def main():
    arguments = sys.argv[1:]
    if len(arguments) != 11:
        raise ValueError('Every original task-call input, library and output is required')
    library, library_hash, *pairs, destination = arguments
    output = Path(destination)
    report = {'completed': False, 'diagnosticOnly': True,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'allCallerInstructionDetailsBound': False, 'selectedNormalLinearContinuationEntriesBound': False,
              **dict.fromkeys(DENIED, False),
              'scope': 'Pinned detailed decoder and selected normal linear incoming edges only. Every original '
                       'caller/source definition, raw instruction, unsupported and incomplete case retained. '
                       'No alternate/global entry, dynamic return, transitive callee effects, kernel coverage, '
                       'task generation/lifetime, private reads, compiler roots/clocks/size or savings authority.'}
    try:
        if library_hash != calls.abi.LIBRARY_SHA256:
            raise ValueError('Unsupported detailed caller library')
        inspect([Path(value) for value in pairs[::2]], pairs[1::2], Path(library).resolve(), output.parent, report)
    except Exception as error:
        report.update(completed=False, allCallerInstructionDetailsBound=False,
                      selectedNormalLinearContinuationEntriesBound=False,
                      error={'type': type(error).__name__, 'message': str(error)})
        for row in report.get('callerSemanticRecords', []):
            for guard in row.get('continuationGuards', []):
                guard['selectedNormalLinearContinuationEntryBound'] = False
        raise
    finally:
        raw = report_bytes(report)
        output.write_bytes(raw)
        if report['completed'] and json.loads(raw)['completed'] is False:
            raise ValueError('Detailed caller output exceeds bounds')


if __name__ == '__main__':
    main()
