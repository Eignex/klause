"""Capture source-bound task lifecycle entry arguments without private fields."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal

import native_compiler_observer as observer
import native_vm_live_arguments as live
import read_native_vm_symbols as symbols
import review_native_vm_entry_abi as abi
import review_native_vm_task_lifecycle_abi as lifecycle

LIBRARY_SHA256 = abi.LIBRARY_SHA256
PLAN_SHA256 = '1184253aa561ed5983e8be38aef4d129ce21418aeb0da3dca1d6198c6713910c'
ABI_SHA256 = '3aea5bb5831a570c7d5f09ff6e305a028745da5218b8dc884def3db3aa96a3c5'
PLAN_PRODUCER = '4d400daf45bd73b9adfd1e863c80143dd831412693e8dd76550e1a5be6c0d29a'
ABI_PRODUCER = 'd6b2b53bec6d32fff2dc3ffddc3812d6bfe47074eaff5ef560982d29426c8d2d'
IMAGE_SHA256 = '16194f713ba1fbff2dd76b7abffad4cd61f91e5663b116a5b03c907ae3e20cd8'
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_SELECTED = 32
HOOKS = {
    'initialize': (lifecycle.ROLES['initialize']['names'], (('this', 5, 64), ('compile_id', 4, 32))),
    'free': (lifecycle.ROLES['free']['names'], (('task', 5, 64),)),
    'construct': (lifecycle.ROLES['construct']['names'], (('task', 4, 64),)),
    'destroy': (lifecycle.ROLES['destroy']['names'], ()),
    'broker': ((abi.linkage('invoke_compiler_on_method'),), (('task', 5, 64),)),
    'post': ((abi.linkage('post_compiled_method'),), (('this', 5, 64), ('task', 4, 64))),
}


def inputs(plan_path, abi_path, out):
    originals = []
    for name, path, expected in [('lifecycle', plan_path, PLAN_SHA256), ('abi', abi_path, ABI_SHA256)]:
        if path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Task entry proof exceeds bounds')
        raw = path.read_bytes()
        if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('Task entry proof bytes differ')
        (out / ('captured-task-' + name + '.json')).write_bytes(raw)
        originals.append(raw)
    plan, entry_abi = map(json.loads, originals)
    for report, producer in [(plan, PLAN_PRODUCER), (entry_abi, ABI_PRODUCER)]:
        if report.get('completed') is not True or report.get('diagnosticOnly') is not True or \
                report.get('acceptedSavingsCredit') is not False or report.get('sourceSha256') != producer or \
                any(report.get(key, False) is not False for key in
                    ['actualCompiledTaskLifetimeProven', 'actualTaskGenerationProven', 'privateFieldReadsAuthorized',
                     'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']):
            raise ValueError('Task proof producer, completion or authority differs')
    if plan.get('lifecycleArgumentPlanProven') is not True or plan.get('actualTargetCallingConventionProven') is not False or \
            plan.get('inputSha256') != {name: value[0] for name, value in lifecycle.INPUTS.items()} or \
            entry_abi.get('actualTargetCallingConventionProven') is not True:
        raise ValueError('Fixed lifecycle or canonical compiler argument proof is unavailable')
    return plan, entry_abi


def lifecycle_rows(plan):
    if set(plan['roles']) != set(lifecycle.ROLES):
        raise ValueError('Lifecycle proof omits a fixed role')
    selected = {}
    for role, spec in lifecycle.ROLES.items():
        group = plan['roles'][role]
        if group.get('argumentPlanProven') is not True or group['requiredSymbols'] != list(spec['names']) or \
                group['unmatchedPhysicalDefinitions'] or not group['entries']:
            raise ValueError('Lifecycle role has an unproved or unmatched definition')
        names = set()
        for row in group['entries']:
            name = row['originalEntry']['originalSymbol']['name']
            names.add(name)
            if name not in spec['names'] or row.get('argumentPlanProven') is not True or not row['definitions']:
                raise ValueError('Lifecycle physical entry has no complete definition proof')
            for definition in row['definitions']:
                if definition.get('argumentPlanProven') is not True or definition.get('sourceBoundaryProven') is not True:
                    raise ValueError('Lifecycle source definition is unproved')
                bindings = definition['parameterBindings']
                expected = [(index, name, kind, register) for index, name, kind, register in spec['reads']]
                actual = [(item['parameterIndex'], item['name'], item['targetType'], item['expectedDwarfRegister'])
                          for item in bindings]
                if actual != expected or any(type(item['parameterIndex']) is not int or
                        item.get('readBindingProven') is not True or
                        item['extractionBits'] != (32 if item['targetType'] == 'int' else 64) for item in bindings):
                    raise ValueError('Lifecycle parameter read differs from its fixed proof')
                if role == 'allocate' and definition.get('pointerReturnClassProven') is not True:
                    raise ValueError('Original allocation proof is incomplete')
        if names != set(spec['names']):
            raise ValueError('Lifecycle proof omits a required alias')
        if role != 'allocate':
            selected[role] = [{'input': 'lifecycle', 'role': role, 'index': index, 'row': row}
                              for index, row in enumerate(group['entries'])]
    return selected


def compiler_rows(report):
    selected = {}
    for role, query in [('broker', 'invoke_compiler_on_method'), ('post', 'post_compiled_method')]:
        concordance = report['targetConcordance'][query]
        name = abi.linkage(query)
        if concordance.get('actualTargetCallingConventionProven') is not True or concordance['linkageName'] != name:
            raise ValueError('Canonical compiler entry proof is incomplete')
        rows = [{'input': 'abi', 'role': role, 'index': index, 'row': row}
                for index, row in enumerate(report['entries'][query])
                if row['originalEntry']['originalSymbol']['name'] == name]
        if not rows:
            raise ValueError('Canonical compiler entry is absent')
        for reference in rows:
            row = reference['row']
            if row.get('actualTargetCallingConventionProven') is not True or not row['definitions']:
                raise ValueError('Canonical compiler physical entry is unproved')
            for definition in row['definitions']:
                if definition.get('actualTargetCallingConventionProven') is not True:
                    raise ValueError('Canonical compiler definition is unproved')
                bindings = definition['parameterBindings']
                if [(item['expectedParameter'], item['expectedDwarfRegister']) for item in bindings] != \
                        [(name, register) for name, register, _ in HOOKS[role][1]] or \
                        any(not item['entryRegisters'] or
                            any(value != item['expectedDwarfRegister'] for value in item['entryRegisters'])
                            for item in bindings):
                    raise ValueError('Canonical compiler parameter register differs')
        selected[role] = rows
    return selected


def select_entries(stream, file_size, groups):
    if set(groups) != set(HOOKS):
        raise ValueError('Task probe role inventory differs')
    selected, locations, names = {}, {}, {}
    for role, references in groups.items():
        physical = {}
        if not references or len(references) > MAX_SELECTED:
            raise ValueError('Task probe references are absent or exceed bounds')
        for reference in references:
            entry = reference['row']['originalEntry']
            symbol = entry['originalSymbol']
            name = symbol['name']
            if name not in HOOKS[role][0] or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name) or \
                    name.encode().hex() != symbol['nameHex'] or symbol.get('function') is not True or \
                    symbol.get('undefined') is not False or symbol.get('ordinarySectionDefinition') is not True:
                raise ValueError('Task probe is not a fixed ordinary function symbol')
            span = abi.entry_span(entry)
            if entry.get('undecodedHex') != '':
                raise ValueError('Task probe span has an undecoded suffix')
            start, size = span['fileOffset'], span['bytes']
            if start + size > file_size:
                raise ValueError('Task probe span exceeds the pinned file')
            stream.seek(start)
            raw = stream.read(size)
            if raw.hex() != entry['entryHex'] or hashlib.sha256(raw).hexdigest() != span['entrySha256']:
                raise ValueError('Current task probe bytes differ')
            identity = span['virtualAddress'], start, size, span['entrySha256']
            if name in names and names[name] != identity:
                raise ValueError('Task probe name resolves to conflicting spans')
            names[name] = identity
            location = span['virtualAddress'], start
            if location in locations and locations[location] != (role, identity):
                raise ValueError('Different task roles or spans share a probe location')
            locations[location] = role, identity
            group = physical.setdefault(identity, {'physicalEntry': span, 'aliases': [], 'sourceEntryReferences': []})
            if name not in group['aliases']:
                group['aliases'].append(name)
            group['sourceEntryReferences'].append(reference)
        selected[role] = []
        if {name for group in physical.values() for name in group['aliases']} != set(HOOKS[role][0]):
            raise ValueError('Task probe inventory omits a required alias')
        for group in physical.values():
            group['aliases'].sort()
            group['name'] = group['aliases'][0]
            selected[role].append(group)
    if sum(map(len, selected.values())) > MAX_SELECTED:
        raise ValueError('Physical task probe inventory exceeds bounds')
    return selected


def bound_entries(library, plan_path, abi_path, out):
    plan, entry_abi = inputs(plan_path, abi_path, out)
    groups = {**lifecycle_rows(plan), **compiler_rows(entry_abi)}
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if hashlib.file_digest(stream, 'sha256').hexdigest() != LIBRARY_SHA256 or \
                symbols.identity(before) != symbols.identity(library.stat()):
            raise ValueError('Task probe library differs before binding')
        selected = select_entries(stream, before.st_size, groups)
        stream.seek(0)
        if hashlib.file_digest(stream, 'sha256').hexdigest() != LIBRARY_SHA256 or \
                symbols.identity(before) != symbols.identity(os.fstat(stream.fileno())) or \
                symbols.identity(before) != symbols.identity(library.stat()):
            raise ValueError('Task probe library changed during binding')
    return selected


def program(library, root_pid, selected):
    if not library.is_absolute() or not re.fullmatch(r'/[A-Za-z0-9_./+\-]+', str(library)) or \
            type(root_pid) is not int or not 0 < root_pid < 2 ** 32 or set(selected) != set(HOOKS):
        raise ValueError('Invalid task probe process, library or roles')
    predicate = f'/pid == {root_pid}/'
    blocks = [f'BEGIN {{ @sequence[{root_pid}] = 0; '
              'printf("VALIDATION_FAULT_EXPECTED\\t%llu\\t%rh\\n", nsecs, buf(uptr(0), 1)); }']
    for kind, probe, tail, arguments in [
            ('EXEC', 'sched_process_exec', '\\t%d', ', uid'),
            ('FORK', 'sched_process_fork', '\\t%d\\t%d', ', args->parent_pid, args->child_pid'),
            ('EXIT', 'sched_process_exit', '', '')]:
        blocks.append(f'tracepoint:sched:{probe} {predicate} {{ ' + live.sequence() +
                      f'printf("LIFECYCLE\\t{kind}\\t%llu\\t%d\\t%d\\t%llu{tail}\\n", '
                      f'nsecs, pid, tid, $sequence{arguments}); }}')
    attached = set()
    for role, groups in selected.items():
        if not groups:
            raise ValueError('Task probe role has no physical entries')
        for group in groups:
            name = group['name']
            if name not in HOOKS[role][0] or name in attached or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
                raise ValueError('Invalid or duplicate exact task probe name')
            attached.add(name)
            task = 'arg0' if role in ('initialize', 'free', 'broker') else \
                'arg1' if role in ('construct', 'post') else '0'
            compile_id = '(int32)arg1' if role == 'initialize' else '-1'
            receiver = 'arg0' if role == 'post' else '0'
            blocks.append(f'uprobe:{library}:{name} {predicate} {{ ' + live.sequence() +
                          f'let $task = (uint64){task}; let $compile_id = (int64)({compile_id}); '
                          f'let $receiver = (uint64){receiver}; '
                          f'printf("TASK_ENTRY\\t{role}\\t{name}\\t%llu\\t%d\\t%d\\t%llu\\t%llx\\t%lld\\t%llx\\n", '
                          'nsecs, pid, tid, $sequence, $task, $compile_id, $receiver); }')
    for kind in ('begin', 'end'):
        success = 'arg8' if kind == 'end' else '-1'
        statement = (f'usdt:{library}:hotspot:method__compile__{kind} {predicate} {{ ' + live.sequence() +
                     f'printf("USDT\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t%lld\\n", '
                     f'nsecs, pid, tid, $sequence, {success}); ')
        for label, pointer, length in [('compiler', 0, 1), ('class', 2, 3), ('method', 4, 5), ('signature', 6, 7)]:
            statement += (f'printf("IDENTITY\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t{label}\\t%llu\\t%rh\\n", '
                          f'nsecs, pid, tid, $sequence, arg{length}, '
                          f'buf(uptr(arg{pointer}), arg{length} < 128 ? arg{length} : 128)); ')
        blocks.append(statement + '}')
    blocks.append('END { let $threads = 0; let $events = 0; for ($row : @sequence) { '
                  'printf("FINAL_SEQUENCE\\t%llu\\t%llu\\t%llu\\n", nsecs, $row.0, $row.1); '
                  '$threads++; $events += $row.1; } '
                  'printf("FINAL_SEQUENCE_TOTAL\\t%llu\\t%llu\\t%llu\\n", nsecs, $threads, $events); '
                  'printf("DRAIN_MARKER\\t%llu\\n", nsecs); clear(@sequence); }')
    return '\n'.join(blocks) + '\n'


def terminate(signum, frame):
    raise TimeoutError('Task entry capture was stopped')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=Path)
    parser.add_argument('library', type=Path)
    parser.add_argument('plan', type=Path)
    parser.add_argument('abi', type=Path)
    parser.add_argument('image', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    out = args.destination
    out.mkdir(parents=True, exist_ok=True)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': observer.file_digest(Path(__file__)),
              'actualTargetCallingConventionProven': False,
              'actualTaskGenerationProven': False, 'actualCompiledTaskLifetimeProven': False,
              'privateFieldReadsAuthorized': False, 'actualControlFlowCoverageProven': False,
              'rootCensusProven': False, 'codeSizeProven': False,
              'unattachedLifecycleRoles': ['allocate'],
              'scope': 'Raw source-bound lifecycle entry arguments only. Signed32 initialization compile IDs '
                       'and pointer arguments are observations, not derived generations or lifetime proofs. '
                       'Allocation returns and target private fields are not read. All aliases and original '
                       'proofs retained; no kernel delivery, complete roots, clocks, sizes or compiler saving proof.'}
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        library = args.library.resolve()
        report['selectedEntries'] = bound_entries(library, args.plan, args.abi, out)
        report.update(library=str(library), expectedLibrarySha256=LIBRARY_SHA256,
                      inputSha256={'lifecycle': PLAN_SHA256, 'abi': ABI_SHA256})
        report['observerImageSha256'] = observer.file_digest(args.image)
        if report['observerImageSha256'] != IMAGE_SHA256:
            raise ValueError('Task observer image differs from pinned source package')
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        observer.observe_appimage(out / 'observer', library, args.image, command, 120,
                                  lambda path, pid: program(path, pid, report['selectedEntries']),
                                  capture_event_loss=True)
        report['librarySha256After'] = observer.file_digest(library)
        if report['librarySha256After'] != LIBRARY_SHA256:
            raise ValueError('Task probe library changed during observation')
        report['completed'] = True
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        signal.signal(signal.SIGTERM, previous)
        (out / 'task-generation-entries.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
