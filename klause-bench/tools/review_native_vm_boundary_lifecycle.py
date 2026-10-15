"""Retain observed lifecycle nesting, delivery failures and independent identities."""
import hashlib
import json
from pathlib import Path
import sys

import native_vm_boundary_lifecycle as capture
import native_vm_event_loss as event_loss
import review_native_vm_live_arguments as previous

MAX_RECORDS = 1000000


def parse_records(text, root_pid):
    groups, other, errors = {}, [], []
    for line in text.splitlines():
        values = line.split('\t')
        category = values[0]
        if category not in ('BOUNDARY', 'LIFECYCLE', 'USDT', 'IDENTITY'):
            other.append(line)
            continue
        try:
            if category == 'BOUNDARY':
                _, kind, boundary, stamp, pid, tid, sequence = values
                if kind not in ('ENTER', 'LEAVE') or boundary not in capture.BOUNDARIES:
                    raise ValueError('Unknown source boundary callback')
                tail = []
            else:
                kind, stamp, pid, tid, sequence = values[1:6]
                boundary, tail = None, values[6:]
                if category in ('USDT', 'IDENTITY') and kind not in ('begin', 'end'):
                    raise ValueError('Unknown public compilation callback')
                if category == 'LIFECYCLE' and (kind not in ('EXEC', 'FORK', 'EXIT') or
                        len(tail) != {'EXEC': 1, 'FORK': 2, 'EXIT': 0}[kind]):
                    raise ValueError('Unknown or malformed kernel lifecycle callback')
            stamp, pid, tid, sequence = map(int, (stamp, pid, tid, sequence))
            if not 0 <= stamp < 2 ** 64 or pid != root_pid or not 0 < tid < 2 ** 32 or \
                    not 0 < sequence <= MAX_RECORDS:
                raise ValueError('Callback process, thread, sequence or timestamp is invalid')
            key = pid, tid, sequence
            if key not in groups and len(groups) >= MAX_RECORDS:
                raise ValueError('Lifecycle callback inventory exceeds review bounds')
            group = groups.setdefault(key, {'pid': pid, 'tid': tid, 'sequence': sequence,
                                            'records': [], 'primary': [], 'errors': []})
            row = {'type': category, 'kind': kind, 'boundary': boundary, 'timestamp': stamp,
                   'raw': line, 'values': tail}
            group['records'].append(row)
            if category != 'IDENTITY':
                group['primary'].append(row)
        except (ValueError, IndexError) as error:
            errors.append({'raw': line, 'error': str(error)})
    threads = {}
    for (pid, tid, _), group in groups.items():
        threads.setdefault(tid, []).append(group)
        try:
            if len(group['primary']) != 1:
                raise ValueError('Missing or duplicated primary callback')
            primary = group['primary'][0]
            if primary['type'] == 'USDT':
                previous.validate_fragments(group, None)
            elif len(group['records']) != 1:
                raise ValueError('Unexpected lifecycle or boundary payload fragments')
            if primary['type'] == 'LIFECYCLE':
                if any(not 0 <= int(value) < 2 ** 32 for value in primary['values']) or \
                        (primary['kind'] == 'FORK' and int(primary['values'][0]) != tid):
                    raise ValueError('Kernel lifecycle payload differs from its callback identity')
        except (ValueError, IndexError, KeyError, TypeError) as error:
            group['errors'].append(str(error))
    for rows in threads.values():
        rows.sort(key=lambda group: group['sequence'])
        stamp = -1
        for index, group in enumerate(rows, 1):
            if group['sequence'] != index:
                group['errors'].append('Missing initial callback or sequence gap')
            if len(group['primary']) == 1:
                current = group['primary'][0]['timestamp']
                if current < stamp:
                    group['errors'].append('Callback timestamps contradict sequence order')
                stamp = current
    return {'threads': list(threads.values()), 'otherLines': other, 'errors': errors}


def terminal_delivery(native, root_pid, warning_guard):
    final, totals, drains, errors = {}, [], [], []
    for line in native['otherLines']:
        values = line.split('\t')
        try:
            if values[0] == 'DRAIN_MARKER':
                if len(values) != 2 or int(values[1]) < 0:
                    raise ValueError('Malformed drain marker')
                drains.append(int(values[1]))
            elif values[0] in ('FINAL_SEQUENCE', 'FINAL_SEQUENCE_TOTAL'):
                if len(values) != 4 or any(int(value) < 0 for value in values[1:]):
                    raise ValueError('Malformed terminal sequence record')
                stamp, key, count = map(int, values[1:])
                if values[0] == 'FINAL_SEQUENCE_TOTAL':
                    totals.append({'timestamp': stamp, 'threads': key, 'events': count})
                else:
                    if key <= 0 or key in final or count > MAX_RECORDS or (count == 0 and key != root_pid):
                        raise ValueError('Duplicate or invalid terminal sequence key')
                    final[key] = {'timestamp': stamp, 'sequence': count, 'raw': line}
        except (ValueError, IndexError) as error:
            errors.append({'raw': line, 'error': str(error)})
    observed = {}
    for rows in native['threads']:
        tid = rows[0]['tid']
        observed[tid] = len(rows)
        snapshot = final.get(tid)
        if snapshot is None or snapshot['sequence'] != len(rows) or any(group['errors'] for group in rows) or \
                any(row['timestamp'] > snapshot['timestamp'] for group in rows for row in group['records']):
            errors.append({'tid': tid, 'error': 'Callbacks or fragments do not reach a consistent terminal sequence'})
    if root_pid not in final or any(row['sequence'] != observed.get(tid, 0) for tid, row in final.items()):
        errors.append({'error': 'Terminal map includes a missing or unaccounted thread'})
    if len(totals) != 1 or len(drains) != 1:
        errors.append({'error': 'Missing or duplicate terminal total or drain marker'})
    else:
        total = totals[0]
        if total['threads'] != len(final) or total['events'] != sum(row['sequence'] for row in final.values()) or \
                total['timestamp'] > drains[0] or any(row['timestamp'] > total['timestamp'] for row in final.values()):
            errors.append({'error': 'Terminal map total or drain order differs'})
    return {'selectedSequencedDeliveryProven': warning_guard and not errors and not native['errors'],
            'finalSequences': final, 'totals': totals, 'drains': drains, 'errors': errors,
            'scope': 'Selected sequenced kernel/source-boundary/USDT callbacks and fragments only; no root census, '
                     'calling convention, hidden/inlined hooks or compiler elapsed-time proof.'}


def contexts(native, jfr):
    index = {}
    for position, row in enumerate(jfr):
        key = row['tid'], tuple(row['method']), row['compiler'].casefold(), row['succeeded']
        index.setdefault(key, []).append(position)
    result = []
    for rows in native['threads']:
        stack, pairs, public, orphaned, failures, exits = [], [], [], [], [], []
        exited = False
        for group in rows:
            if len(group['primary']) != 1 or group['errors']:
                failures.append({'sequence': group['sequence'], 'errors': group['errors']})
                continue
            row = group['primary'][0]
            if exited:
                failures.append({'sequence': group['sequence'], 'error': 'Callback after observed thread exit'})
            if row['type'] == 'BOUNDARY':
                if row['kind'] == 'ENTER':
                    stack.append({'boundary': row['boundary'], 'entry': row,
                                  'entrySequence': group['sequence']})
                elif stack and stack[-1]['boundary'] == row['boundary']:
                    frame = stack.pop()
                    pairs.append({**frame, 'leave': row, 'leaveSequence': group['sequence']})
                else:
                    orphaned.append({'sequence': group['sequence'], 'callback': row,
                                     'openBoundaries': [frame['boundary'] for frame in stack]})
            elif row['type'] == 'LIFECYCLE' and row['kind'] == 'EXIT':
                exited = True
                exits.append({'callback': row, 'sequence': group['sequence'],
                              'openBoundaries': [frame['boundary'] for frame in stack]})
            elif row['type'] == 'USDT':
                item = {'callback': row, 'sequence': group['sequence'],
                        'observedBoundaryContext': [frame['boundary'] for frame in stack],
                        'boundaryEntrySequences': [frame['entrySequence'] for frame in stack],
                        'jfrCandidates': [], 'compileIdAssociationProven': False}
                try:
                    identity = previous.decode_identity(group)
                    item['identity'] = identity
                    if row['kind'] == 'end':
                        key = (group['tid'], previous.method_name(identity), identity['compiler'].casefold(),
                               bool(int(row['values'][0])))
                        item['jfrCandidates'] = index.get(key, [])
                except (ValueError, KeyError, IndexError, UnicodeError) as error:
                    item['identityError'] = str(error)
                public.append(item)
        result.append({'tid': rows[0]['tid'], 'observedCallbackPairs': pairs, 'unfinishedCallbacks': stack,
                       'unmatchedReturns': orphaned, 'publicCompilations': public, 'threadExits': exits,
                       'lifetimeErrors': failures,
                       'scope': 'Observed callback nesting only. Thread exit does not replace a missing return; '
                                'unfinished spans and queue notifications imply no idle or zero-work result. '
                                'JFR candidates share public identity/thread fields only; no compile-ID/clock binding.'})
    return result


def inspect(directory, report):
    provenance = report.setdefault('inputs', {})
    load = lambda name: previous.read_file(directory / name, provenance)
    captured = json.loads(load('live/boundary-lifecycle.json'))
    if captured.get('completed') is not True or captured.get('diagnosticOnly') is not True or \
            captured.get('acceptedSavingsCredit') is not False or \
            captured.get('actualCallingConventionProven') is not False or \
            captured.get('rootCensusProven') is not False or captured.get('privateArgumentOrFieldReads') is not False or \
            captured.get('observerImageSha256') != capture.IMAGE_SHA256 or \
            captured.get('sourceSha256') != capture.observer.file_digest(Path(capture.__file__)):
        raise ValueError('Boundary capture is incomplete or belongs to another producer')
    load('live/captured-boundary-associations.json')
    load('live/captured-boundary-entries.json')
    selected = capture.bound_entries(Path(captured['library']), directory / 'live/captured-boundary-associations.json',
                                     directory / 'live/captured-boundary-entries.json', directory / 'live')
    if selected != captured['selectedEntries']:
        raise ValueError('Captured probe selection differs from its pinned source and entry inputs')
    launch = json.loads(load('live/observer/observer-launch.json'))
    child = json.loads(load('live/observer/child-before-exec.json'))
    script = load('live/observer/observer.bt')
    expected_script = capture.program(Path(captured['library']), launch['rootPid'], captured['selectedEntries']).encode()
    if script != expected_script or launch['scriptSha256'] != hashlib.sha256(script).hexdigest() or \
            launch['librarySha256'] != capture.LIBRARY_SHA256 or captured['expectedLibrarySha256'] != capture.LIBRARY_SHA256:
        raise ValueError('Executed boundary script or library differs')
    if not launch['attached'] or launch['childExit'] != 0 or launch['observerExit'] != 0 or launch['timedOut'] or \
            child['pid'] != launch['rootPid'] or child['uid'] != launch['expectedUid'] or \
            child['gid'] != launch['expectedGid'] or child['groups'] != launch['expectedGroups'] or \
            child['command'] != launch['command'] or \
            child['mountNamespace'] != launch['expectedMountNamespace'] or \
            launch['observerMountNamespace'] == launch['expectedMountNamespace']:
        raise ValueError('Original process, observer or namespace lifetime differs')
    native = parse_records(load('live/observer/observer.stdout').decode(), launch['rootPid'])
    exec_rows = [group['primary'][0] for rows in native['threads'] for group in rows
                 if len(group['primary']) == 1 and group['primary'][0]['type'] == 'LIFECYCLE' and
                 group['primary'][0]['kind'] == 'EXEC']
    if len(exec_rows) != 1 or exec_rows[0]['values'] != [str(launch['expectedUid'])]:
        raise ValueError('Original-user exec callback is absent or ambiguous')
    diagnostics = previous.read_diagnostics(load('live/observer/observer.stderr').decode(), script.decode(),
                                            native['otherLines'])
    controls = [line.split('\t') for line in native['otherLines'] if line.startswith('VALIDATION_FAULT_EXPECTED\t')]
    diagnostics['invalidReadControlRecords'] = controls
    diagnostics['readWarningGuardPassed'] = diagnostics['readWarningGuardPassed'] and len(controls) == 1 and \
        len(controls[0]) == 3 and controls[0][1].isdecimal()
    extraction = json.loads(load('live/observer/appimage-extraction.json'))
    if extraction['packageSha256'] != capture.IMAGE_SHA256 or extraction['exit'] != 0 or \
            launch['observerBinarySha256'] != extraction['appRunSha256'] or not extraction['temporaryRemoved']:
        raise ValueError('Pinned observer extraction identity differs')
    counter = {'finalLossCounterProven': False, 'finalLossCounterZeroProven': False,
               'globalLossFreeCaptureProven': False, 'errors': []}
    try:
        source = load('native_vm_event_loss.py')
        if source != Path(event_loss.__file__).read_bytes() or \
                not all(launch.get(key) is True for key in ('lossMonitorRequested', 'lossMonitorReady', 'lossMonitorCompleted')) or \
                launch.get('lossMonitorCleanupError'):
            raise ValueError('Executed loss-counter source or lifecycle is unproved')
        expected = {'pid': launch['observerPid'], 'startTicks': launch['observerStartTicks'],
                    'executableSha256': extraction['executableSha256'], 'comm': 'bpftrace',
                    'mountNamespace': launch['observerMountNamespace']}
        raw_counter = json.loads(load('live/observer/event-loss.json'))
        report['rawLossCounter'] = raw_counter
        counter.update(event_loss.verify_report(raw_counter, load('live/observer/event-loss.btf'), expected,
                                               hashlib.sha256(source).hexdigest()))
    except (ValueError, KeyError, TypeError, IndexError) as error:
        counter['errors'].append({'type': type(error).__name__, 'message': str(error)})
    jfr = previous.jfr_records(json.loads(load('child-compilations.stdout')))
    report.update(nativeRecords=native, diagnostics=diagnostics, lossCounter=counter,
                  delivery=terminal_delivery(native, launch['rootPid'], diagnostics['readWarningGuardPassed']),
                  threadContexts=contexts(native, jfr), independentJfrRecords=jfr, completed=True)


def main():
    directory, output = map(Path, sys.argv[1:])
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualCallingConventionProven': False, 'rootCensusProven': False,
              'globalLossFreeCaptureProven': False, 'compilerElapsedTimeProven': False,
              'sourceSha256': capture.observer.file_digest(Path(__file__)),
              'scope': 'Remote raw lifecycle/delivery/public-identity review only; all unknown, malformed, incomplete, '
                       'unmatched and unfinished records retained. Context intervals are not compiler elapsed time. '
                       'No argument-register/private-field interpretation, live task IDs, full-root/clock/size or savings proof.'}
    try:
        inspect(directory, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
