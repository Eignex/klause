"""Review saved task entry observations without granting compiled lifetime authority."""
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import native_vm_task_generation as capture
import native_vm_event_loss as event_loss
import review_native_vm_boundary_lifecycle as boundary
import review_native_vm_live_arguments as previous

MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_OUTPUT_BYTES = 64 * 1024 * 1024
MAX_RECORDS = 100000
MAX_RELATIONS = 1000000
INPUTS = ('live/task-generation-entries.json', 'live/captured-task-lifecycle.json',
          'live/captured-task-abi.json', 'live/observer/observer-launch.json',
          'live/observer/child-before-exec.json', 'live/observer/observer.bt',
          'live/observer/observer.stdout', 'live/observer/observer.stderr',
          'live/observer/appimage-extraction.json', 'live/observer/event-loss.json',
          'live/observer/event-loss.btf', 'native_vm_event_loss.py',
          'child-compilations.stdout', 'live/hotspot.xml')
DENIED = ('actualTargetCallingConventionProven', 'actualTaskGenerationProven',
          'actualCompiledTaskLifetimeProven', 'privateFieldReadsAuthorized',
          'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven',
          'globalLossFreeCaptureProven', 'compilerElapsedTimeProven', 'acceptedSavingsCredit')


def parse_records(text, root_pid, selected):
    if type(root_pid) is not int or not 0 < root_pid < 2 ** 32 or set(selected) != set(capture.HOOKS):
        raise ValueError('Task record process or source role inventory differs')
    names = {role: {group['name'] for group in rows} for role, rows in selected.items()}
    if any(not names[role] or not names[role] <= set(capture.HOOKS[role][0]) for role in names):
        raise ValueError('Task record physical names differ from the fixed source roles')
    groups, other, errors = {}, [], []
    lines = text.splitlines()
    if len(lines) > MAX_RECORDS * 5:
        raise ValueError('Task raw record inventory exceeds bounds')
    for line in lines:
        values = line.split('\t')
        category = values[0]
        if category not in ('TASK_ENTRY', 'USDT', 'IDENTITY', 'LIFECYCLE'):
            other.append(line)
            continue
        try:
            if category == 'TASK_ENTRY':
                _, kind, name, stamp, pid, tid, sequence, task, compile_id, receiver = values
                if kind not in names or name not in names[kind] or \
                        any(not re.fullmatch('[0-9a-f]{1,16}', value) for value in (task, receiver)):
                    raise ValueError('Task entry name, role or pointer width differs')
                task, receiver, compile_id = int(task, 16), int(receiver, 16), int(compile_id)
                if not -(2 ** 31) <= compile_id < 2 ** 31 or \
                        (kind != 'initialize' and compile_id != -1) or \
                        (kind != 'post' and receiver != 0) or (kind == 'destroy' and task != 0):
                    raise ValueError('Task entry signed identity or unused argument differs')
                tail = []
                detail = {'entryName': name, 'taskPointer': task,
                          'compileId': compile_id, 'receiverPointer': receiver}
            else:
                kind, stamp, pid, tid, sequence = values[1:6]
                tail, detail = values[6:], {}
                if category in ('USDT', 'IDENTITY') and kind not in ('begin', 'end'):
                    raise ValueError('Unknown public compilation callback')
                if category == 'LIFECYCLE' and (kind not in ('EXEC', 'FORK', 'EXIT') or
                        len(tail) != {'EXEC': 1, 'FORK': 2, 'EXIT': 0}[kind]):
                    raise ValueError('Unknown or malformed kernel lifecycle callback')
            stamp, pid, tid, sequence = map(int, (stamp, pid, tid, sequence))
            if not 0 <= stamp < 2 ** 64 or pid != root_pid or not 0 < tid < 2 ** 32 or \
                    not 0 < sequence <= MAX_RECORDS:
                raise ValueError('Task callback process, thread, sequence or timestamp is invalid')
            key = pid, tid, sequence
            if key not in groups and len(groups) >= MAX_RECORDS:
                raise ValueError('Task callback inventory exceeds bounds')
            group = groups.setdefault(key, {'pid': pid, 'tid': tid, 'sequence': sequence,
                                            'records': [], 'primary': [], 'errors': []})
            row = {'type': category, 'kind': kind, 'timestamp': stamp,
                   'raw': line, 'values': tail, **detail}
            group['records'].append(row)
            if category != 'IDENTITY':
                group['primary'].append(row)
        except (ValueError, IndexError) as error:
            errors.append({'raw': line, 'error': str(error)})
    threads = {}
    for (_, tid, _), group in groups.items():
        threads.setdefault(tid, []).append(group)
        try:
            if len(group['primary']) != 1:
                raise ValueError('Missing or duplicated task primary callback')
            primary = group['primary'][0]
            if primary['type'] == 'USDT':
                previous.validate_fragments(group, None)
            elif len(group['records']) != 1:
                raise ValueError('Unexpected task or kernel payload fragments')
            if primary['type'] == 'LIFECYCLE' and (
                    any(not 0 <= int(value) < 2 ** 32 for value in primary['values']) or
                    (primary['kind'] == 'FORK' and int(primary['values'][0]) != tid)):
                raise ValueError('Kernel payload differs from its callback identity')
        except (ValueError, IndexError, KeyError, TypeError) as error:
            group['errors'].append(str(error))
    for rows in threads.values():
        rows.sort(key=lambda group: group['sequence'])
        stamp, exited = -1, False
        for index, group in enumerate(rows, 1):
            if exited:
                group['errors'].append('Task callback after observed thread exit')
            if group['sequence'] != index:
                group['errors'].append('Missing initial task callback or sequence gap')
            if len(group['primary']) == 1:
                current = group['primary'][0]['timestamp']
                if current < stamp:
                    group['errors'].append('Task callback timestamps contradict sequence order')
                stamp = current
                if group['primary'][0]['type'] == 'LIFECYCLE' and group['primary'][0]['kind'] == 'EXIT':
                    exited = True
    return {'threads': list(threads.values()), 'otherLines': other, 'errors': errors}


def initialization_candidates(native):
    initializations, by_pointer, entries, pointer_sets = [], {}, [], []
    for rows in native['threads']:
        for group in rows:
            for primary_index, row in enumerate(group['primary']):
                if row['type'] != 'TASK_ENTRY':
                    continue
                entry = {'event': [group['pid'], group['tid'], group['sequence']],
                         'timestamp': row['timestamp'], 'role': row['kind'],
                         'taskPointer': row['taskPointer'], 'compileId': row['compileId'],
                         'groupErrors': list(group['errors']), 'primaryIndex': primary_index,
                         'candidatePointerSet': None, 'actualTaskGenerationProven': False}
                entries.append(entry)
                if row['kind'] == 'initialize':
                    index = len(entries) - 1
                    initializations.append(index)
                    if row['taskPointer'] != 0:
                        pointer = row['taskPointer']
                        if pointer not in by_pointer:
                            by_pointer[pointer] = len(pointer_sets)
                            pointer_sets.append({'taskPointer': pointer, 'initializationEntries': []})
                        pointer_sets[by_pointer[pointer]]['initializationEntries'].append(index)
    relations = 0
    for entry in entries:
        if entry['role'] in ('initialize', 'destroy'):
            continue
        entry['candidatePointerSet'] = by_pointer.get(entry['taskPointer'])
        possible = pointer_sets[entry['candidatePointerSet']]['initializationEntries'] \
            if entry['candidatePointerSet'] is not None else []
        eligible = False
        for index in possible:
            relations += 1
            if relations > MAX_RELATIONS:
                raise ValueError('Task initialization candidate graph exceeds bounds')
            candidate = entries[index]
            same_thread = candidate['event'][:2] == entry['event'][:2]
            if not same_thread or candidate['event'][2] < entry['event'][2]:
                eligible = True
        entry['unresolvedReason'] = 'null task pointer' if entry['taskPointer'] == 0 else \
            'no eligible observed initialization' if not eligible else \
            'initialization candidates do not exclude unobserved reuse or prove cross-thread order'
    return {'schema': 2, 'initializationEntries': initializations, 'taskEntries': entries,
            'pointerInitializationSets': pointer_sets, 'crossThreadOrderProven': False,
            'actualTaskGenerationProven': False,
            'candidateRule': 'Follow candidatePointerSet to all equal-pointer initializationEntries; '
                             'exclude only same-thread initialization sequences at or after the task entry.',
            'scope': 'Every initialization and task entry retained once. Native callback references use '
                     '(event, primaryIndex); initialization indices refer to taskEntries. Shared pointer sets '
                     'and the explicit per-thread sequence rule retain every candidate without expanding '
                     'edges. Node timestamps retain every before/equal/after relation without proving '
                     'cross-thread order. Hidden initializations and frees authorize no generation or lifetime.'}


def eligible_initializations(graph, entry):
    index = entry['candidatePointerSet']
    if index is None:
        return
    for candidate_index in graph['pointerInitializationSets'][index]['initializationEntries']:
        candidate = graph['taskEntries'][candidate_index]
        if candidate['event'][:2] == entry['event'][:2] and candidate['event'][2] >= entry['event'][2]:
            continue
        yield candidate_index, candidate


def jfr_identities(document):
    positions = [index for index, event in enumerate(document['recording']['events'])
                 if event['type'] == 'jdk.Compilation']
    return [{**{key: value for key, value in row.items() if key != 'raw'}, 'recordingEventIndex': index}
            for row, index in zip(previous.jfr_records(document), positions, strict=True)]


def public_contexts(native, candidates, jfr, logs):
    by_event = {tuple(row['event']): index for index, row in enumerate(candidates['taskEntries'])}
    native_groups = {(group['pid'], group['tid'], group['sequence']): group
                     for rows in native['threads'] for group in rows}
    if len(jfr) > MAX_RECORDS or len(logs) > MAX_RECORDS:
        raise ValueError('Independent compilation identity inventory exceeds bounds')
    jfr_index, log_index = {}, {}
    for index, row in enumerate(jfr):
        key = row['tid'], tuple(row['method']), row['compiler'].casefold(), row['succeeded']
        jfr_index.setdefault(key, []).append(index)
    for index, row in enumerate(logs):
        log_index.setdefault((row['compileId'], tuple(row['method'])), []).append(index)
    frames, orphans, invalid = [], [], []
    for rows in native['threads']:
        stack = []
        for group in rows:
            event = group['pid'], group['tid'], group['sequence']
            if group['errors'] or len(group['primary']) != 1:
                invalid.append({'event': event, 'groupErrors': list(group['errors'])})
                continue
            row = group['primary'][0]
            if row['type'] == 'TASK_ENTRY' and row['kind'] == 'construct':
                frame = {'entryEvent': event, 'taskPointer': row['taskPointer'],
                         'destroy': None, 'brokers': [], 'posts': [], 'public': [], 'issues': []}
                stack.append(frame)
                frames.append(frame)
            elif row['type'] == 'TASK_ENTRY' and row['kind'] == 'destroy':
                if stack:
                    frame = stack.pop()
                    frame['destroy'] = {'event': event}
                else:
                    orphans.append({'event': event, 'reason': 'destruction without construction'})
            elif row['type'] == 'USDT' or (row['type'] == 'TASK_ENTRY' and row['kind'] in ('broker', 'post')):
                if not stack:
                    orphans.append({'event': event, 'reason': 'callback outside observed wrapper'})
                elif row['type'] == 'USDT':
                    stack[-1]['public'].append({'event': event})
                else:
                    stack[-1]['brokers' if row['kind'] == 'broker' else 'posts'].append(
                        {'event': event, 'entryIndex': by_event[event]})
            elif row['type'] == 'LIFECYCLE' and row['kind'] == 'EXIT':
                for frame in stack:
                    frame['issues'].append({'event': event, 'reason': 'thread exit with unfinished wrapper'})
        for frame in stack:
            frame['issues'].append({'reason': 'unfinished observed wrapper'})
    relations = 0
    for frame in frames:
        frame.update(jfrCandidates=[], logCandidates=[], candidatePublicIdentityUnique=False,
                     actualTaskGenerationProven=False, actualCompiledTaskLifetimeProven=False)
        try:
            if len(frame['brokers']) != 1 or len(frame['public']) != 2 or frame['destroy'] is None:
                raise ValueError('Observed wrapper, broker or public pair is incomplete or ambiguous')
            broker = frame['brokers'][0]
            task = candidates['taskEntries'][broker['entryIndex']]
            begin, end = frame['public']
            begin_group, end_group = native_groups[tuple(begin['event'])], native_groups[tuple(end['event'])]
            if begin_group['primary'][0]['kind'] != 'begin' or end_group['primary'][0]['kind'] != 'end' or \
                    not (frame['entryEvent'][2] < broker['event'][2] < begin['event'][2] <
                         end['event'][2] < frame['destroy']['event'][2]):
                raise ValueError('Observed wrapper and public sequence order differs')
            if frame['taskPointer'] == 0 or task['taskPointer'] != frame['taskPointer'] or \
                    any(candidates['taskEntries'][post['entryIndex']]['taskPointer'] != frame['taskPointer']
                        for post in frame['posts']):
                raise ValueError('Observed wrapper, broker or post task pointer differs')
            identity = previous.decode_identity(begin_group)
            if identity != previous.decode_identity(end_group):
                raise ValueError('Public compilation identities differ')
            frame['publicIdentity'] = identity
            ids = {row['compileId'] for _, row in eligible_initializations(candidates, task)}
            key = (frame['entryEvent'][1], previous.method_name(identity), identity['compiler'].casefold(),
                   bool(int(end_group['primary'][0]['values'][0])))
            for index in jfr_index.get(key, []):
                relations += 1
                if relations > MAX_RELATIONS:
                    raise OverflowError('Public task candidate graph exceeds bounds')
                if jfr[index]['compileId'] in ids:
                    frame['jfrCandidates'].append(index)
            matching_logs = set()
            for position in frame['jfrCandidates']:
                candidate = jfr[position]
                for index in log_index.get((candidate['compileId'], tuple(candidate['method'])), []):
                    relations += 1
                    if relations > MAX_RELATIONS:
                        raise OverflowError('Public log candidate graph exceeds bounds')
                    row = logs[index]
                    if (row['level'] is None or row['level'] == candidate['level']) and \
                            (row['compileKind'] == 'osr') == candidate['isOsr']:
                        matching_logs.add(index)
            frame['logCandidates'] = sorted(matching_logs)
            frame['candidatePublicIdentityUnique'] = len(frame['jfrCandidates']) == 1 and bool(frame['logCandidates'])
        except (ValueError, KeyError, IndexError, TypeError, UnicodeError) as error:
            frame['issues'].append({'reason': str(error)})
    return {'observedWrappers': frames, 'orphanCallbacks': orphans, 'invalidGroups': invalid,
            'scope': 'Event references address nativeRecords groups; entryIndex addresses '
                     'initializationCandidates.taskEntries. Observed per-thread nesting and identity candidates only. '
                     'Destruction has no captured receiver; nesting cannot establish actual wrapper identity '
                     'or broker return. Candidate uniqueness does not prove a task generation or lifetime.'}


def read_originals(directory, report):
    raw, total = {}, 0
    for name in INPUTS:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Saved task input is absent, linked or oversized')
        contents = path.read_bytes()
        total += len(contents)
        if len(contents) > MAX_INPUT_BYTES or total > MAX_TOTAL_BYTES:
            raise ValueError('Saved task input bytes exceed review bounds')
        report.setdefault('inputSha256', {})[name] = hashlib.sha256(contents).hexdigest()
        raw[name] = contents
    return raw


def inspect(directory, library, out, report):
    raw = read_originals(directory, report)
    captured = json.loads(raw['live/task-generation-entries.json'])
    report['originalCaptureReport'] = captured
    if captured.get('completed') is not True or captured.get('diagnosticOnly') is not True or \
            captured.get('sourceSha256') != capture.observer.file_digest(Path(capture.__file__)) or \
            any(captured.get(key, False) is not False for key in DENIED) or \
            captured.get('observerImageSha256') != capture.IMAGE_SHA256 or \
            captured.get('librarySha256After') != capture.LIBRARY_SHA256:
        raise ValueError('Original task capture producer, completion or authority differs')
    bindings = out / 'rechecked-bindings'
    bindings.mkdir()
    selected = capture.bound_entries(library, directory / 'live/captured-task-lifecycle.json',
                                     directory / 'live/captured-task-abi.json', bindings)
    if selected != captured['selectedEntries'] or captured.get('inputSha256') != \
            {'lifecycle': capture.PLAN_SHA256, 'abi': capture.ABI_SHA256}:
        raise ValueError('Original task selection or complete proof inputs differ')
    launch = json.loads(raw['live/observer/observer-launch.json'])
    child = json.loads(raw['live/observer/child-before-exec.json'])
    script = raw['live/observer/observer.bt']
    if script != capture.program(Path(captured['library']), launch['rootPid'], selected).encode() or \
            launch['scriptSha256'] != hashlib.sha256(script).hexdigest() or \
            launch['librarySha256'] != captured['expectedLibrarySha256'] or \
            captured['expectedLibrarySha256'] != capture.LIBRARY_SHA256:
        raise ValueError('Original task program or library identity differs')
    if not launch['attached'] or launch['childExit'] != 0 or launch['observerExit'] != 0 or launch['timedOut'] or \
            child['pid'] != launch['rootPid'] or child['uid'] != launch['expectedUid'] or \
            child['gid'] != launch['expectedGid'] or child['groups'] != launch['expectedGroups'] or \
            child['command'] != launch['command'] or child['mountNamespace'] != launch['expectedMountNamespace'] or \
            launch['observerMountNamespace'] == launch['expectedMountNamespace']:
        raise ValueError('Original task process, observer or namespace lifecycle differs')
    native = parse_records(raw['live/observer/observer.stdout'].decode(), launch['rootPid'], selected)
    report['nativeRecords'] = native
    execs = [group['primary'][0] for rows in native['threads'] for group in rows
             if len(group['primary']) == 1 and group['primary'][0]['type'] == 'LIFECYCLE' and
             group['primary'][0]['kind'] == 'EXEC']
    if len(execs) != 1 or execs[0]['values'] != [str(launch['expectedUid'])]:
        raise ValueError('Original selected-user exec callback is absent or ambiguous')
    diagnostics = previous.read_diagnostics(raw['live/observer/observer.stderr'].decode(),
                                            script.decode(), native['otherLines'])
    controls = [line.split('\t') for line in native['otherLines'] if line.startswith('VALIDATION_FAULT_EXPECTED\t')]
    diagnostics['invalidReadControlRecords'] = controls
    diagnostics['unrecognizedLines'] = [line for line in native['otherLines'] if line and
        not line.startswith(('VALIDATION_FAULT_EXPECTED\t', 'FINAL_SEQUENCE\t',
                             'FINAL_SEQUENCE_TOTAL\t', 'DRAIN_MARKER\t')) and
        not re.fullmatch(r'Attaching [0-9]+ probes\.\.\.', line)]
    diagnostics['readWarningGuardPassed'] = diagnostics['readWarningGuardPassed'] and \
        len(controls) == 1 and len(controls[0]) == 3 and controls[0][1].isdecimal() and \
        not diagnostics['unrecognizedLines']
    extraction = json.loads(raw['live/observer/appimage-extraction.json'])
    if extraction['packageSha256'] != capture.IMAGE_SHA256 or extraction['exit'] != 0 or \
            launch['observerBinarySha256'] != extraction['appRunSha256'] or not extraction['temporaryRemoved']:
        raise ValueError('Original observer extraction identity differs')
    loss = {'finalLossCounterProven': False, 'finalLossCounterZeroProven': False,
            'globalLossFreeCaptureProven': False, 'errors': []}
    try:
        source = raw['native_vm_event_loss.py']
        if source != Path(event_loss.__file__).read_bytes() or \
                not all(launch.get(key) is True for key in
                        ('lossMonitorRequested', 'lossMonitorReady', 'lossMonitorCompleted')) or \
                launch.get('lossMonitorCleanupError'):
            raise ValueError('Original owned loss source or lifecycle is unproved')
        counter = json.loads(raw['live/observer/event-loss.json'])
        report['originalLossCounter'] = counter
        expected = {'pid': launch['observerPid'], 'startTicks': launch['observerStartTicks'],
                    'executableSha256': extraction['executableSha256'], 'comm': 'bpftrace',
                    'mountNamespace': launch['observerMountNamespace']}
        loss.update(event_loss.verify_report(counter, raw['live/observer/event-loss.btf'], expected,
                                             hashlib.sha256(source).hexdigest()))
    except (ValueError, KeyError, TypeError, IndexError) as error:
        loss['errors'].append({'type': type(error).__name__, 'message': str(error)})
    delivery = boundary.terminal_delivery(native, launch['rootPid'], diagnostics['readWarningGuardPassed'])
    delivery['scope'] = 'Selected observed task-entry/kernel/USDT groups and fragments only; no hidden '
    delivery['scope'] += 'initialization, actual kernel coverage, complete compiler roots or elapsed-time proof.'
    document = json.loads(raw['child-compilations.stdout'])
    jfr = jfr_identities(document)
    log_text = raw['live/hotspot.xml'].decode()
    logs = previous.log_records(log_text)
    tree = ET.fromstring(log_text)
    log_nodes = []
    for node in tree.iter():
        if len(log_nodes) >= MAX_RECORDS:
            raise ValueError('Independent compilation XML inventory exceeds bounds')
        log_nodes.append({'tag': node.tag, 'attributes': dict(node.attrib), 'text': node.text, 'tail': node.tail})
    candidates = initialization_candidates(native)
    report.update(diagnostics=diagnostics, lossCounter=loss, delivery=delivery,
                  initializationCandidates=candidates,
                  threadContexts=public_contexts(native, candidates, jfr, logs),
                  independentJfrDocument=document, independentJfrCompilations=jfr,
                  independentLogIdentities=logs, independentLogNodeInventory=log_nodes,
                  observedTaskEntryRecordsValidated=delivery['selectedSequencedDeliveryProven'] and
                      loss['finalLossCounterZeroProven'] and bool(candidates['taskEntries']), completed=True)


def main():
    manifest, expected, archive, library, output = sys.argv[1:]
    manifest, archive, library, output = map(Path, (manifest, archive, library, output))
    report = {'schema': 2, 'completed': False, 'diagnosticOnly': True, 'observedTaskEntryRecordsValidated': False,
              'sourceSha256': capture.observer.file_digest(Path(__file__)),
              **{key: False for key in DENIED},
              'scope': 'Saved task entries, initialization and public-identity candidates only. Every '
                       'alias/definition, malformed/orphan/reused/cross-thread/unfinished/unknown state retained. '
                       'No new probes, target execution, private reads, actual generation/lifetime/coverage, '
                       'complete compiler roots, clocks, size or savings authority.'}
    try:
        if manifest.stat().st_size > 4 * 1024 * 1024 or archive.stat().st_size > 32 * 1024 * 1024:
            raise ValueError('Saved task transport exceeds review bounds')
        selected = output.parent / 'selected-inputs'
        report['savedArchive'] = previous.extract_inputs(manifest, expected, archive, selected, {}, INPUTS)
        inspect(selected, library, output.parent, report)
        if len(report_bytes(report)) > MAX_OUTPUT_BYTES:
            raise ValueError('Saved task review output exceeds bounds')
    except Exception as error:
        report.update(completed=False, observedTaskEntryRecordsValidated=False,
                      error={'type': type(error).__name__, 'message': str(error)})
        if 'delivery' in report:
            report['delivery']['selectedSequencedDeliveryProven'] = False
        if 'lossCounter' in report:
            report['lossCounter'].update(finalLossCounterProven=False, finalLossCounterZeroProven=False)
        raise
    finally:
        report.update({key: False for key in DENIED})
        if len(report_bytes(report)) > MAX_OUTPUT_BYTES:
            for key in ['nativeRecords', 'initializationCandidates', 'threadContexts', 'independentJfrDocument',
                        'independentJfrCompilations', 'independentLogIdentities', 'independentLogNodeInventory',
                        'originalCaptureReport', 'originalLossCounter']:
                report.pop(key, None)
            report.update(completed=False, observedTaskEntryRecordsValidated=False,
                          outputRetention='Oversized derived inventories withheld; complete raw selected inputs retained')
            if 'delivery' in report:
                report['delivery']['selectedSequencedDeliveryProven'] = False
            if 'lossCounter' in report:
                report['lossCounter'].update(finalLossCounterProven=False, finalLossCounterZeroProven=False)
        output.write_bytes(report_bytes(report))


def report_bytes(report):
    return (json.dumps(report, separators=(',', ':')) + '\n').encode()


if __name__ == '__main__':
    main()
