"""Retain saved task-log observations without equating them with compiler invocations."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import review_native_vm_log_identities as identities

MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_BYTES = 64 * 1024 * 1024
INPUTS = ('live/hotspot.xml',)
SOURCE_REF = '520406d871955300957ef01e406ac2acd0f9b75c'
DENIED = ('actualCompilerInvocationProven', 'actualCompiledTaskLifetimeProven',
          'rootCensusProven', 'compilerElapsedTimeProven', 'clockAlignmentProven',
          'codeSizeProven', 'globalLossFreeCaptureProven', 'acceptedSavingsCredit')


def stamp_millis(raw, errors):
    if not isinstance(raw, str) or not re.fullmatch(r'(?:0|[1-9][0-9]{0,11})\.[0-9]{3}', raw):
        errors.append('Task stamp is absent or outside the fixed decimal format')
        return None
    seconds, fraction = raw.split('.')
    return int(seconds) * 1000 + int(fraction)


def auxiliary_records(root, logs):
    tasks = {row['documentIndex']: index for index, row in enumerate(logs['tasks'])}
    records, stack, ordinal = [], [(root, None)], 0
    while stack:
        node, owner = stack.pop()
        ordinal += 1
        if node.tag == 'task':
            owner = tasks[ordinal]
        if node.tag in ('failure', 'make_not_entrant', 'make_not_compilable', 'deoptimized'):
            records.append({'documentIndex': ordinal, 'tag': node.tag, 'taskIndex': owner,
                            'rawAttributes': dict(node.attrib), 'text': node.text})
        stack.extend((child, owner) for child in reversed(list(node)))
    return records


def inspect_text(text):
    if len(text.encode('utf-8')) > MAX_INPUT_BYTES:
        raise ValueError('Saved task log exceeds input bounds')
    if '<!DOCTYPE' in text or '<!ENTITY' in text:
        raise ValueError('Saved task log contains an unsupported document declaration')
    root = ET.fromstring(text)
    if root.tag != 'hotspot_log':
        raise ValueError('Saved task log has an unsupported document root')
    logs = identities.parse_log(text)
    auxiliary = auxiliary_records(root, logs)
    return inspect_records(logs, auxiliary)


def inspect_records(logs, auxiliary):
    task_ids, queued_ids, published_ids = {}, {}, {}
    for rows, index in [(logs['tasks'], task_ids), (logs['queued'], queued_ids), (logs['nmethods'], published_ids)]:
        for position, row in enumerate(rows):
            index.setdefault(row['compileId'], []).append(position)
    candidate_ids = list(dict.fromkeys([*task_ids, *queued_ids, *published_ids]))
    candidate_index = {key: index for index, key in enumerate(candidate_ids)}
    candidates = [{'compileId': key, 'taskIndices': task_ids.get(key, []),
                   'queuedIndices': queued_ids.get(key, []), 'nmethodIndices': published_ids.get(key, [])}
                  for key in candidate_ids]
    process_errors = []
    process = identities.number(logs['rootAttributes'], 'process', process_errors, 1)
    spans = []
    for index, task in enumerate(logs['tasks']):
        errors = list(task['errors'])
        start = task['rawAttributes'].get('stamp')
        done = task['taskDone']
        row = {'taskIndex': index, 'startStampCopiedVerbatim': start,
               'doneStampsCopiedVerbatim': [entry['rawAttributes'].get('stamp') for entry in done],
               'compileIdentityCandidatesIndex': candidate_index[task['compileId']],
               'loggedStampPairValid': False, 'nominalStampDeltaMillis': None,
               'zeroCompilerElapsedTimeProven': False, 'actualCompilerInvocationProven': False,
               'errors': errors}
        spans.append(row)
        if task['method'] is None or task['compileId'] is None or task['compileId'] <= 0:
            errors.append('Task method or positive compile identity is absent')
        owner = logs['logs'][task['ownerLogIndex']] if task['ownerLogIndex'] is not None else None
        markers = owner['startMarkers'] if owner is not None else []
        if owner is None or owner['errors'] or len(markers) != 1:
            errors.append('Task log owner or unique thread start is absent or malformed')
        else:
            marker = markers[0]
            if marker['errors'] or marker['thread'] != owner['thread'] or process is None or process_errors or \
                    marker['process'] != process or marker['documentIndex'] >= task['documentIndex']:
                errors.append('Task log thread/process/start identity is inconsistent')
        before = stamp_millis(start, errors)
        if len(done) != 1:
            errors.append('Task has no unique direct completion record')
            continue
        errors.extend(done[0]['errors'])
        after = stamp_millis(done[0]['rawAttributes'].get('stamp'), errors)
        if before is not None and after is not None and after < before:
            errors.append('Task completion stamp precedes task start')
        if not errors:
            row.update(loggedStampPairValid=True, nominalStampDeltaMillis=after - before)
    return {'logRecords': logs, 'failureAndPublicationStateRecords': auxiliary,
            'compileIdCandidates': candidates, 'taskLogSpans': spans,
            'allLoggedTaskStampPairsValid': bool(spans) and all(row['loggedStampPairValid'] for row in spans)
            and not logs['errors'] and not logs['fragments'],
            'summary': {'tasks': len(spans), 'queued': len(logs['queued']), 'nmethods': len(logs['nmethods']),
                        'loggedStampPairs': sum(row['loggedStampPairValid'] for row in spans),
                        'taskSpanErrors': dict(Counter(error for row in spans for error in row['errors']))},
            'sourceContract': {'sourceRef': SOURCE_REF,
                               'taskStampBoundary': 'Interior of CompileTaskWrapper; log start/done can also enclose stale or rejected tasks without invoking a compiler.',
                               'stampRepresentation': 'Three fractional decimal places of elapsed-counter seconds; nominal decimal difference is not exact elapsed time or a proven endpoint bound.',
                               'unmeasuredWrapperWork': 'Task assignment before start; task/env clearing, completion, notification and recycling after done; queue wait and thread initialization outside the pair.',
                               'taskDoneNmsize': 'CompileTask nm_content_size, set from nmethod content_size.',
                               'jfrCompilationCodeSize': 'CompileTask nm_total_size, set from nmethod total_size; distinct from content/instruction/CodeBlob sizes.',
                               'globalNmethodSize': 'CodeBlob size(), distinct from task_done nmsize and JFR codeSize.',
                               'zeroAndMissingSizes': 'Task size fields initialize to zero; failure, absence and default zero do not prove zero compiler work or measured code size.'},
            **{key: False for key in DENIED}}


def write_report(output, report):
    raw = (json.dumps(report, indent=2) + '\n').encode()
    if len(raw) > MAX_OUTPUT_BYTES:
        negative = {'completed': False, 'diagnosticOnly': True,
                    'sourceSha256': report['sourceSha256'], 'inputFiles': report.get('inputFiles', {}),
                    'archiveBinding': report.get('archiveBinding'),
                    'error': {'type': 'OutputLimitExceeded', 'message': 'Derived task-log report withheld; original archive and extracted log retained'},
                    **{key: False for key in DENIED}}
        output.write_text(json.dumps(negative, indent=2) + '\n')
        raise ValueError('Saved task-log report exceeds output bounds')
    output.write_bytes(raw)


def main():
    manifest, expected, archive, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Saved full task-log inventory and nominal printed-stamp differences only. All tasks, queues, publications, failures, fragments and unresolved identities retained. No target load, probes, replay, native lifetime, global root, exact compiler elapsed, code-size reconstruction or savings proof.',
              **{key: False for key in DENIED}}
    try:
        directory = output.parent / 'saved-task-log-inputs'
        report['archiveBinding'] = identities.original.extract_inputs(Path(manifest), expected, Path(archive), directory,
                                                                       report.setdefault('inputFiles', {}), INPUTS)
        path = directory / INPUTS[0]
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Saved task log is absent, linked or oversized')
        raw = identities.original.read_file(path, report['inputFiles'])
        report.update(inspect_text(raw.decode('utf-8')), completed=True)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        write_report(output, report)


if __name__ == '__main__':
    main()
