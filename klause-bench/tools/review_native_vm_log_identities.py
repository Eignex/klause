"""Corroborate saved invocation metadata with thread-scoped compilation logs."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import review_native_vm_live_arguments as original

MAX_ELEMENTS = 1000000


def number(attributes, name, errors, minimum=0):
    text = attributes.get(name)
    if text is None:
        return None
    if not re.fullmatch(r'[0-9]+', text) or not minimum <= int(text) < 2 ** 63:
        errors.append('Invalid ' + name)
        return None
    return int(text)


def identity(node, ordinal):
    attributes = dict(node.attrib)
    errors = []
    parts = attributes.get('method', '').split(' ')
    method = (parts[0].replace('/', '.'), *parts[1:]) if len(parts) == 3 and all(parts) else None
    if method is None and 'method' in attributes:
        errors.append('Malformed original method identity')
    return {'documentIndex': ordinal, 'tag': node.tag, 'rawAttributes': attributes, 'method': method,
            'compileId': number(attributes, 'compile_id', errors),
            'level': number(attributes, 'level', errors),
            'osrBci': number(attributes, 'osr_bci', errors), 'compileKind': attributes.get('compile_kind'),
            'compiler': attributes.get('compiler'), 'errors': errors}


def parse_log(text):
    root = ET.fromstring(text)
    result = {'rootAttributes': dict(root.attrib), 'logs': [], 'tasks': [], 'nmethods': [], 'queued': [],
              'fragments': [], 'orphanedRecords': [], 'errors': [], 'otherTags': {}}
    stack, other, ordinal = [(root, None, None, None)], Counter(), 0
    while stack:
        node, owner, task, parent = stack.pop()
        ordinal += 1
        if ordinal > MAX_ELEMENTS:
            raise ValueError('Compilation log exceeds element bounds')
        if node.tag == 'compilation_log':
            row = {'documentIndex': ordinal, 'rawAttributes': dict(node.attrib), 'startMarkers': [], 'errors': []}
            row['thread'] = number(node.attrib, 'thread', row['errors'], 1)
            if row['thread'] is None or owner is not None or task is not None:
                row['errors'].append('Missing or nested compilation-log owner')
            owner, task = len(result['logs']), None
            result['logs'].append(row)
        elif node.tag == 'start_compile_thread':
            row = {'documentIndex': ordinal, 'rawAttributes': dict(node.attrib), 'errors': []}
            row['thread'] = number(node.attrib, 'thread', row['errors'], 1)
            row['process'] = number(node.attrib, 'process', row['errors'], 1)
            if owner is None or task is not None:
                result['orphanedRecords'].append(row)
            else:
                result['logs'][owner]['startMarkers'].append(row)
        elif node.tag in ('task', 'nmethod', 'task_queued'):
            row = identity(node, ordinal)
            row['ownerLogIndex'] = owner
            if node.tag == 'task':
                row['taskDone'] = []
                if task is not None:
                    row['errors'].append('Nested task identity')
                task = len(result['tasks'])
                result['tasks'].append(row)
            else:
                result['nmethods' if node.tag == 'nmethod' else 'queued'].append(row)
        elif node.tag == 'task_done':
            row = {'documentIndex': ordinal, 'rawAttributes': dict(node.attrib), 'errors': []}
            row['success'] = number(node.attrib, 'success', row['errors'])
            if row['success'] not in (0, 1):
                row['errors'].append('Missing or invalid task outcome')
            if task is None or parent != 'task':
                result['orphanedRecords'].append(row)
            else:
                result['tasks'][task]['taskDone'].append(row)
        elif node.tag == 'fragment':
            result['fragments'].append({'documentIndex': ordinal, 'ownerLogIndex': owner,
                                        'rawAttributes': dict(node.attrib), 'text': node.text})
        else:
            other[node.tag] += 1
        stack.extend((child, owner, task, node.tag) for child in reversed(list(node)))
    result['otherTags'] = dict(other)
    for collection in ['logs', 'tasks', 'nmethods', 'queued']:
        for position, row in enumerate(result[collection]):
            if row['errors']:
                result['errors'].append({'collection': collection, 'index': position, 'errors': row['errors']})
    if result['orphanedRecords']:
        result['errors'].append({'reason': 'Orphaned compiler thread or task completion records'})
    return result


def key_checks(row, key, method, ordinary, publication=False):
    if key[2] == ordinary:
        osr = row['compileKind'] in (None, 'c2c') and row['osrBci'] is None
    else:
        osr = row['compileKind'] == 'osr' and \
            (row['osrBci'] == key[2] or (publication and row['osrBci'] is None))
    return {'compileId': row['compileId'] == key[0], 'method': row['method'] == method,
            'explicitLevel': row['level'] is not None and row['level'] == key[1],
            'osr': osr, 'wellFormed': not row['errors']}


def correlate(frames, layout, logs):
    tasks, artifacts = {}, {}
    for position, row in enumerate(logs['tasks']):
        tasks.setdefault(row['compileId'], []).append(position)
    for position, row in enumerate(logs['nmethods']):
        artifacts.setdefault(row['compileId'], []).append(position)
    records = []
    ordinary = layout['invocationEntryBci']['value']
    for position, frame in enumerate(frames):
        record = {'originalBrokerIndex': position, 'originalIndependentIdentityMatched': frame['independentIdentityMatched'],
                  'originalRecordedArgumentBindingCorroborated': frame['recordedArgumentBindingCorroborated'],
                  'originalUnprovenReason': frame.get('unprovenReason'), 'threadTaskMetadataMatched': False,
                  'compilerMetadataMatched': False, 'postMetadataConsistent': False,
                  'logMetadataCorroborated': False, 'taskCandidates': [], 'nmethodCandidates': []}
        records.append(record)
        try:
            if not frame['completed'] or len(frame['usdtBegin']) != 1 or len(frame['usdtEnd']) != 1:
                raise ValueError('Original broker lifetime or public compilation pair is incomplete')
            begin_group, end_group = frame['usdtBegin'][0], frame['usdtEnd'][0]
            begin, end = original.decode_identity(begin_group), original.decode_identity(end_group)
            if begin != end or begin_group['sequence'] >= end_group['sequence']:
                raise ValueError('Public method/compiler identity or pair ordering differs')
            if frame['entry']['errors']:
                raise ValueError('Original native entry is incomplete')
            success_values = end_group['primary'][0]['values']
            if len(success_values) != 1 or success_values[0] not in ('0', '1'):
                raise ValueError('Public compilation outcome is malformed')
            succeeded = int(success_values[0])
            task = original.decode_candidate(frame['entry'], 'task', 'CompileTask', layout)
            key, method = original.task_key(task), original.method_name(begin)
            record.update(nativeTaskKey=key, publicIdentity=begin, publicSuccess=succeeded)
            if key[0] <= 0 or key[1] not in (1, 2, 3, 4) or (key[2] != ordinary and key[2] < 0):
                raise ValueError('Native compilation key is outside the supported task identity domain')
            for index in tasks.get(key[0], []):
                row = logs['tasks'][index]
                checks = key_checks(row, key, method, ordinary)
                owner = logs['logs'][row['ownerLogIndex']] if row['ownerLogIndex'] is not None else None
                markers = owner['startMarkers'] if owner is not None else []
                checks['ownerThread'] = owner is not None and not owner['errors'] and owner['thread'] == frame['entry']['tid']
                checks['startMarker'] = len(markers) == 1 and not markers[0]['errors'] and \
                    markers[0]['thread'] == frame['entry']['tid'] and markers[0]['process'] == frame['entry']['pid'] and \
                    markers[0]['documentIndex'] < row['documentIndex']
                done = row['taskDone']
                checks['outcome'] = len(done) == 1 and not done[0]['errors'] and done[0]['success'] == succeeded
                record['taskCandidates'].append({'taskIndex': index, 'checks': checks})
            matching_tasks = [row for row in record['taskCandidates']
                              if all(value for name, value in row['checks'].items() if name != 'outcome')]
            if logs['errors'] or len(matching_tasks) != 1:
                raise ValueError('Thread-owned explicit task identity is absent, malformed or ambiguous')
            if not matching_tasks[0]['checks']['outcome']:
                raise ValueError('Unique thread-owned task outcome is absent, contradictory or ambiguous')
            record['threadTaskMetadataMatched'] = True
            for index in artifacts.get(key[0], []):
                row = logs['nmethods'][index]
                checks = key_checks(row, key, method, ordinary, publication=True)
                checks['compiler'] = isinstance(row['compiler'], str) and row['compiler'].casefold() == begin['compiler'].casefold()
                checks['globalPublication'] = row['ownerLogIndex'] is None
                record['nmethodCandidates'].append({'nmethodIndex': index, 'checks': checks,
                                                    'recordedOsrBci': row['osrBci']})
            matching_artifacts = [row for row in record['nmethodCandidates']
                                  if all(value for name, value in row['checks'].items() if name != 'compiler')]
            if not succeeded or len(matching_artifacts) != 1 or not matching_artifacts[0]['checks']['compiler']:
                raise ValueError('Independent compiler publication identity is absent or ambiguous')
            record['compilerMetadataMatched'] = True
            if not frame['posts']:
                raise ValueError('No observed post callback corroborates the successful publication')
            posts = []
            for index, post in enumerate(frame['posts']):
                if 'return' not in post or not (begin_group['sequence'] < post['entry']['sequence'] <
                                               post['return']['sequence'] < end_group['sequence']):
                    raise ValueError('Original post callback is incomplete or outside its public compilation pair')
                post_task = original.decode_candidate(post['entry'], 'task', 'CompileTask', layout)
                nmethod = original.decode_candidate(post['entry'], 'nmethod', 'nmethod', layout)
                if post_task['pointer'] != task['pointer'] or original.task_key(post_task) != key or \
                        original.task_key(nmethod, 'nmethod') != key:
                    raise ValueError('Post task/nmethod metadata differs from its original broker entry')
                posts.append({'postIndex': index, 'metadataConsistent': True})
            record.update(postCandidates=posts, postMetadataConsistent=True, logMetadataCorroborated=True)
        except (ValueError, KeyError, IndexError, TypeError, UnicodeError) as error:
            record['unprovenReason'] = str(error)
    summary = {'brokerInvocations': len(records),
               'threadTaskMetadataMatches': sum(row['threadTaskMetadataMatched'] for row in records),
               'compilerMetadataMatches': sum(row['compilerMetadataMatched'] for row in records),
               'logMetadataCorroborations': sum(row['logMetadataCorroborated'] for row in records),
               'originalJfrIdentityMatches': sum(row['originalIndependentIdentityMatched'] for row in records),
               'originalRecordedArgumentBindingsCorroborated': sum(row['originalRecordedArgumentBindingCorroborated'] for row in records),
               'unprovenReasons': dict(Counter(row['unprovenReason'] for row in records if 'unprovenReason' in row)),
               'scope': 'Complementary saved log metadata only. Omitted levels remain unknown, failed/missing/ambiguous '
                        'publications unaccepted, and original JFR/lifetime/read/delivery/loss guards unchanged. '
                        'No calling convention, complete root/clock/code-size/compiler savings proof.'}
    return {'brokerRecords': records, 'summary': summary}


def inspect(directory, output, report):
    baseline = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                'actualFullCallingConventionProven': False, 'rootCensusProven': False,
                'codeSizeProven': False, 'clockAlignmentProven': False, 'lossFreeCaptureProven': False,
                'scope': 'Unchanged original saved-argument review reproduced independently of complementary log matching.'}
    original.inspect(directory, baseline)
    raw_baseline = (json.dumps(baseline, indent=2) + '\n').encode()
    baseline_path = output.parent / 'original-argument-review.json'
    baseline_path.write_bytes(raw_baseline)
    provenance = report.setdefault('inputFiles', {})
    layout = json.loads(original.read_file(directory / 'live/live-layout.json', provenance))
    logs = parse_log(original.read_file(directory / 'live/hotspot.xml', provenance).decode('utf-8'))
    report.update(originalReview={'path': baseline_path.name, 'sha256': hashlib.sha256(raw_baseline).hexdigest(),
                                 'summaryCopiedVerbatim': baseline['correlation']['summary'],
                                 'readDiagnosticsCopiedVerbatim': baseline['readDiagnostics'],
                                 'terminalDeliveryCopiedVerbatim': baseline['terminalDelivery'],
                                 'counterCopiedVerbatim': baseline['eventLossCounter']},
                  threadScopedLogRecords=logs,
                  logCorrelation=correlate(baseline['correlation']['brokerInvocations'], layout, logs), completed=True)


def main():
    manifest, expected, archive, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualFullCallingConventionProven': False, 'rootCensusProven': False,
              'codeSizeProven': False, 'clockAlignmentProven': False, 'lossFreeCaptureProven': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Complementary remote saved invocation/log-thread metadata review only. Original complete '
                       'JFR/field/lifetime/read/delivery/loss guards reproduced without modification; no target load, '
                       'probe attachment, original JVM/solver replay, ABI/private memory/clock/size or compiler savings.'}
    try:
        directory = output.parent / 'saved-live-inputs'
        report['archiveBinding'] = original.extract_inputs(Path(manifest), expected, Path(archive), directory,
                                                          report.setdefault('inputFiles', {}))
        inspect(directory, output, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
