"""Review saved live argument records against independent JVM identities."""
import hashlib
import json
from collections import Counter
from pathlib import Path
import re
import sys
import tarfile
import xml.etree.ElementTree as ET

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_RECORDS = 1000000
PRIMARY = {'ENTRY', 'RETURN', 'USDT'}
RECORDS = PRIMARY | {'CANDIDATE', 'FIELD', 'IDENTITY'}
TASK_FIELDS = ('_method', '_compile_id', '_osr_bci', '_comp_level', '_num_inlined_bytecodes')
NMETHOD_FIELDS = ('_method', '_compile_id', '_entry_bci', '_comp_level', '_immutable_data_size')
INPUTS = ('live/live-layout.json', 'live/observer/observer-launch.json',
          'live/observer/child-before-exec.json', 'live/observer/observer.bt',
          'live/observer/observer.stdout', 'live/observer/observer.stderr',
          'child-compilations.stdout', 'live/hotspot.xml')
HOOKS = {'broker', 'post', 'total-size', 'queue-add', 'queue-remove', 'queue-stale',
         'queue-get', 'wrapper-construct', 'wrapper-destroy'}


def extract_inputs(manifest_path, expected, archive_path, destination, provenance):
    raw = read_file(manifest_path, provenance)
    if not re.fullmatch('[0-9a-f]{64}', expected) or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Saved manifest differs from pinned identity')
    manifest = json.loads(raw)
    archive = manifest['archive']
    raw_archive = read_file(archive_path, provenance)
    if len(raw_archive) != archive['size'] or hashlib.sha256(raw_archive).hexdigest() != archive['sha256']:
        raise ValueError('Saved archive differs from manifest')
    prefixes = [name.removesuffix(INPUTS[0]) for name in manifest['files'] if name.endswith('/' + INPUTS[0])]
    if len(prefixes) != 1 or not re.fullmatch(r'profile[0-9]+/', prefixes[0]):
        raise ValueError('Saved archive profile identity is absent or ambiguous')
    prefix = prefixes[0]
    selected = {prefix + name: name for name in INPUTS}
    if not selected.keys() <= manifest['files'].keys():
        raise ValueError('Saved archive lacks required independent records')
    destination.mkdir(exist_ok=False)
    seen, extracted, expanded = set(), [], 0
    with tarfile.open(archive_path, mode='r|gz') as source:
        for member in source:
            parts = member.name.split('/')
            if not member.isfile() or any(part in ('', '.', '..') for part in parts) or '\\' in member.name or \
                    member.name in seen or len(seen) >= 100000:
                raise ValueError('Saved archive has linked, unsafe, duplicate or excessive members')
            seen.add(member.name)
            expanded += member.size
            if member.size < 0 or member.size > MAX_FILE_BYTES or expanded > 512 * 1024 * 1024:
                raise ValueError('Saved archive exceeds expansion bounds')
            if member.name not in selected:
                continue
            item = manifest['files'][member.name]
            if member.size != item['size']:
                raise ValueError('Saved member size differs from manifest')
            with source.extractfile(member) as stream:
                contents = stream.read(MAX_FILE_BYTES + 1)
            if len(contents) != item['size'] or hashlib.sha256(contents).hexdigest() != item['sha256']:
                raise ValueError('Saved member hash differs from manifest')
            path = destination / selected[member.name]
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream:
                stream.write(contents)
            extracted.append(member.name)
    if seen != manifest['files'].keys() or set(extracted) != selected.keys():
        raise ValueError('Saved archive membership differs from manifest')
    return {'profilePrefix': prefix, 'manifestSha256': expected, 'archive': archive,
            'selectedMembers': extracted, 'allOtherMembersExcludedFromExtraction': True}


def validate_layout(layout):
    if not layout['completed'] or layout['librarySha256'] != LIBRARY_SHA256 or layout['pointerWidth'] != 8:
        raise ValueError('Live layout is incomplete or not the pinned library')
    for owner, names in [('CompileTask', TASK_FIELDS), ('nmethod', NMETHOD_FIELDS)]:
        owners = [row for row in layout['typeTable']['rows'] if row['typeName'] == owner]
        if len(owners) != 1 or type(owners[0]['size']) is not int or not 0 < owners[0]['size'] <= 1024 * 1024:
            raise ValueError('Exported field owner is absent or ambiguous')
        for name in names:
            fields = [row for row in layout['requiredFields'] if row['typeName'] == owner and row['fieldName'] == name]
            if len(fields) != 1:
                raise ValueError('Exported field is absent or ambiguous')
            field = fields[0]
            originals = [row for row in layout['structTable']['rows'] if row['typeName'] == owner and row['fieldName'] == name]
            if len(originals) != 1 or any(field[key] != value for key, value in originals[0].items()):
                raise ValueError('Selected field differs from original exported table')
            width = field['targetFieldWidth']
            if field['isStatic'] != 0 or type(width) is not int or width not in (1, 2, 4, 8) or \
                    type(field['offset']) is not int or not 0 <= field['offset'] <= owners[0]['size'] - width:
                raise ValueError('Exported field exceeds its owner or has unsupported width')
            kind = field['typeString']
            if name == '_method':
                if kind != 'Method*' or width != layout['pointerWidth']:
                    raise ValueError('Exported method pointer type or width differs')
            else:
                types = [row for row in layout['typeTable']['rows'] if row['typeName'] == kind]
                if len(types) != 1 or types[0]['isIntegerType'] != 1 or types[0]['size'] != width or \
                        types[0]['isUnsigned'] not in (0, 1) or (kind == 'int' and width != 4):
                    raise ValueError('Exported integer type is absent or ambiguous')
    if type(layout['invocationEntryBci']['value']) is not int:
        raise ValueError('Invocation entry constant is unavailable')


def read_file(path, provenance):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError('Saved review input is absent, linked or oversized')
    raw = path.read_bytes()
    provenance[str(path)] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    return raw


def parse_native(text):
    groups, other, errors = {}, [], []
    for line in text.splitlines():
        values = line.split('\t')
        if values[0] not in RECORDS:
            other.append(line)
            continue
        if len(groups) >= MAX_RECORDS:
            raise ValueError('Native record groups exceed review bounds')
        try:
            kind, stamp, pid, tid, sequence = values[1:6]
            if (values[0] == 'USDT' and kind not in ('begin', 'end')) or \
                    (values[0] in ('ENTRY', 'RETURN') and kind not in HOOKS) or int(stamp) < 0:
                raise ValueError('Unknown native hook or invalid timestamp')
            key = int(pid), int(tid), int(sequence)
            if min(key) <= 0:
                raise ValueError('Nonpositive native identity or sequence')
            group = groups.setdefault(key, {'pid': key[0], 'tid': key[1], 'sequence': key[2],
                                            'records': [], 'primary': [], 'errors': []})
            record = {'type': values[0], 'kind': kind, 'timestamp': int(stamp), 'raw': line, 'values': values[6:]}
            group['records'].append(record)
            if values[0] in PRIMARY:
                group['primary'].append(record)
        except (ValueError, IndexError) as error:
            errors.append({'raw': line, 'error': str(error)})
    threads = {}
    for key, group in groups.items():
        if len(group['primary']) != 1:
            group['errors'].append('missing or duplicate primary record')
        threads.setdefault((key[0], key[1]), []).append(group)
    for rows in threads.values():
        rows.sort(key=lambda group: group['sequence'])
        previous_stamp = -1
        for expected, group in enumerate(rows, 1):
            if group['sequence'] != expected:
                group['errors'].append('sequence gap or missing initial sequence')
            if len(group['primary']) == 1:
                stamp = group['primary'][0]['timestamp']
                if stamp < previous_stamp:
                    group['errors'].append('primary timestamps contradict sequence order')
                previous_stamp = stamp
    return {'threads': list(threads.values()), 'otherLines': other, 'errors': errors}


def decode_candidate(group, role, owner, layout):
    if len(group['primary']) != 1:
        raise ValueError('Candidate has no unique primary record')
    primary = group['primary'][0]
    candidates = [row for row in group['records'] if row['type'] == 'CANDIDATE' and row['values'][:1] == [role]]
    if len(candidates) != 1 or len(candidates[0]['values']) != 2:
        raise ValueError('Missing or duplicate candidate pointer')
    pointer = int(candidates[0]['values'][1], 16)
    if pointer == 0:
        raise ValueError('Null candidate remains unaccepted')
    argument = {'broker': {'task': 0}, 'post': {'nmethod': 0, 'task': 1},
                'total-size': {'nmethod': 0}, 'queue-add': {'task': 1},
                'queue-remove': {'task': 1}, 'queue-stale': {'task': 1},
                'wrapper-construct': {'task': 1}}.get(primary['kind'], {}).get(role)
    if primary['type'] == 'ENTRY' and (argument is None or argument >= len(primary['values']) or
                                      int(primary['values'][argument], 16) != pointer):
        raise ValueError('Candidate differs from captured source argument slot')
    expected_kind = primary['kind'] + '-return' if primary['type'] == 'RETURN' else primary['kind']
    if candidates[0]['kind'] != expected_kind:
        raise ValueError('Candidate kind differs from its invocation')
    names = TASK_FIELDS if owner == 'CompileTask' else NMETHOD_FIELDS
    result = {'pointer': pointer, 'fields': {}}
    for name in names:
        field = [row for row in layout['requiredFields'] if row['typeName'] == owner and row['fieldName'] == name]
        records = [row for row in group['records'] if row['type'] == 'FIELD' and row['values'][:2] == [role, owner + '.' + name]]
        if len(field) != 1 or len(records) != 1 or len(records[0]['values']) != 5 or records[0]['kind'] != expected_kind:
            raise ValueError('Missing or duplicate exported field bytes')
        row = records[0]['values']
        raw = bytes.fromhex(row[4])
        if int(row[2], 16) != pointer or int(row[3]) != field[0]['targetFieldWidth'] or len(raw) != field[0]['targetFieldWidth']:
            raise ValueError('Candidate pointer or field width differs from exported layout')
        signed = field[0]['typeString'] == 'int'
        if field[0]['typeString'] not in ('int', 'Method*'):
            types = [item for item in layout['typeTable']['rows'] if item['typeName'] == field[0]['typeString']]
            if len(types) != 1:
                raise ValueError('Target enum signedness is ambiguous')
            signed = not types[0]['isUnsigned']
        result['fields'][name] = int.from_bytes(raw, 'little', signed=signed)
    return result


def method_name(identity):
    return (identity['class'].replace('/', '.'), identity['method'], identity['signature'])


def decode_identity(group):
    if len(group.get('primary', [])) != 1:
        raise ValueError('USDT identity lacks a unique primary record')
    result = {}
    for label in ('compiler', 'class', 'method', 'signature'):
        rows = [row for row in group['records'] if row['type'] == 'IDENTITY' and row['values'][:1] == [label]]
        if len(rows) != 1 or len(rows[0]['values']) != 3 or rows[0]['kind'] != group['primary'][0]['kind']:
            raise ValueError('Missing or duplicate USDT identity')
        declared = int(rows[0]['values'][1])
        raw = bytes.fromhex(rows[0]['values'][2])
        if declared <= 0 or declared > 128 or len(raw) != declared:
            raise ValueError('Empty, truncated or malformed USDT identity')
        result[label] = raw.decode('ascii')
    return result


def jfr_records(document):
    records = []
    for event in document['recording']['events']:
        if event['type'] != 'jdk.Compilation':
            continue
        values = event['values']
        method = values['method']
        records.append({'raw': event, 'compileId': values['compileId'], 'level': values['compileLevel'],
                        'isOsr': values['isOsr'], 'tid': values['eventThread']['osThreadId'],
                        'method': (method['type']['name'].replace('/', '.'), method['name'], method['descriptor']),
                        'succeeded': values['succeded'], 'compiler': values['compiler']})
    return records


def log_records(text):
    records = []
    root = ET.fromstring(text)
    for node in root.iter():
        if node.tag not in ('task_queued', 'task', 'nmethod') or 'compile_id' not in node.attrib or 'method' not in node.attrib:
            continue
        parts = node.attrib['method'].split(' ')
        if len(parts) != 3:
            continue
        records.append({'tag': node.tag, 'rawAttributes': dict(node.attrib),
                        'compileId': int(node.attrib['compile_id']), 'method': (parts[0].replace('/', '.'), *parts[1:]),
                        'level': int(node.attrib['level']) if 'level' in node.attrib else None,
                        'osrBci': int(node.attrib['osr_bci']) if 'osr_bci' in node.attrib else None,
                        'compileKind': node.attrib.get('compile_kind')})
    return records


def read_diagnostics(stderr, script, other_lines):
    lines = stderr.splitlines()
    first_line = script.splitlines()[0] if script else ''
    expected, unexpected = [], []
    for index, line in enumerate(lines):
        if not re.search(r'\b(warning|error):', line, re.IGNORECASE):
            continue
        expected_fault = (re.search(r'observer\.bt:1:\d+-\d+: WARNING: Bad address$', line) is not None and
                          index + 1 < len(lines) and
                          lines[index + 1] == 'Additional Info - helper: probe_read_user, retcode: -14' and
                          'VALIDATION_FAULT_EXPECTED' in first_line and 'buf(uptr(0), 1)' in first_line)
        (expected if expected_fault else unexpected).append(line)
    losses = [line for line in lines + other_lines if re.search(r'\b(lost|dropped)\b', line, re.IGNORECASE)]
    return {'labelledInvalidReadWarnings': expected, 'unexpectedWarningsOrErrors': unexpected,
            'reportedLossLines': losses, 'finalLossCounterProven': False,
            'invalidReadValidationObserved': len(expected) == 1,
            'readWarningGuardPassed': len(expected) == 1 and not unexpected and not losses}


def task_key(candidate, owner='CompileTask'):
    fields = candidate['fields']
    return (fields['_compile_id'], fields['_comp_level'],
            fields['_osr_bci' if owner == 'CompileTask' else '_entry_bci'], fields['_method'])


def correlate(native, layout, jfr, logs, diagnostics):
    invocations, issues = [], []
    ordinary = layout['invocationEntryBci']['value']
    for thread in native['threads']:
        stack, active = [], []
        for group in thread:
            if group['errors']:
                issues.append({'group': group, 'reason': 'native group/sequence incomplete'})
                continue
            primary = group['primary'][0]
            kind, category = primary['kind'], primary['type']
            if category == 'ENTRY':
                frame = {'kind': kind, 'entry': group}
                stack.append(frame)
                if kind == 'broker':
                    frame.update(usdtBegin=[], usdtEnd=[], posts=[], completed=False)
                    invocations.append(frame)
                    active.append(frame)
                if active and kind == 'post':
                    active[-1]['posts'].append(frame)
            elif category == 'RETURN':
                if not stack or stack[-1]['kind'] != kind:
                    issues.append({'group': group, 'reason': 'unmatched or non-LIFO return'})
                    continue
                frame = stack.pop()
                frame['return'] = group
                if kind == 'broker':
                    frame['completed'] = True
                    if active and active[-1] is frame:
                        active.pop()
            elif category == 'USDT':
                if not active:
                    issues.append({'group': group, 'reason': 'USDT outside recorded broker lifetime'})
                else:
                    active[-1]['usdtBegin' if kind == 'begin' else 'usdtEnd'].append(group)
        for frame in stack:
            issues.append({'group': frame['entry'], 'reason': 'unfinished native invocation'})
    for frame in invocations:
        frame['recordedArgumentBindingCorroborated'] = False
        frame['independentIdentityMatched'] = False
        try:
            if not frame['completed'] or len(frame['usdtBegin']) != 1 or len(frame['usdtEnd']) != 1:
                raise ValueError('Broker lifetime or USDT pair is incomplete')
            begin, end = decode_identity(frame['usdtBegin'][0]), decode_identity(frame['usdtEnd'][0])
            if frame['usdtBegin'][0]['sequence'] >= frame['usdtEnd'][0]['sequence']:
                raise ValueError('USDT end does not follow begin')
            if begin != end:
                raise ValueError('USDT method/compiler identity changed')
            task = decode_candidate(frame['entry'], 'task', 'CompileTask', layout)
            key = task_key(task)
            wanted = method_name(begin)
            by_id = [row for row in jfr if row['compileId'] == key[0]]
            by_method = [row for row in by_id if row['level'] == key[1] and
                         row['isOsr'] == (key[2] != ordinary) and row['method'] == wanted]
            matches = [row for row in by_method if row['tid'] == frame['entry']['tid']]
            frame.update(taskCandidate=task, usdtIdentity=begin, jfrCompileIdCandidates=by_id,
                         independentMatchStages={'compileId': len(by_id), 'methodLevelOsr': len(by_method),
                                                 'nativeThread': len(matches)})
            if len(matches) != 1:
                raise ValueError('Independent JFR identity is absent or ambiguous')
            log_matches = [row for row in logs if row['compileId'] == key[0] and row['method'] == wanted and
                           (row['level'] is None or row['level'] == key[1]) and
                           (row['osrBci'] == key[2] if key[2] != ordinary else row['compileKind'] != 'osr')]
            if not log_matches:
                raise ValueError('Independent compilation log identity is absent')
            if begin['compiler'].casefold() != matches[0]['compiler'].casefold():
                raise ValueError('Native and JFR compiler identities differ')
            success = frame['usdtEnd'][0]['primary'][0]['values']
            if len(success) != 1 or int(success[0]) not in (0, 1) or bool(int(success[0])) != matches[0]['succeeded']:
                raise ValueError('Native and JFR success identities differ')
            posts = []
            for post in frame['posts']:
                if 'return' not in post or not (frame['usdtBegin'][0]['sequence'] < post['entry']['sequence'] <
                                               post['return']['sequence'] < frame['usdtEnd'][0]['sequence']):
                    raise ValueError('Post invocation is incomplete or outside USDT compilation')
                post_task = decode_candidate(post['entry'], 'task', 'CompileTask', layout)
                nmethod = decode_candidate(post['entry'], 'nmethod', 'nmethod', layout)
                if post_task['pointer'] != task['pointer'] or task_key(post_task) != key or task_key(nmethod, 'nmethod') != key:
                    raise ValueError('Post receiver/task identity differs from broker task')
                posts.append({'task': post_task, 'nmethod': nmethod, 'returned': 'return' in post})
            frame.update(task=task, usdtIdentity=begin, jfrMatches=matches, logMatches=log_matches,
                         postCandidateMatches=posts,
                         independentIdentityMatched=True,
                         recordedArgumentBindingCorroborated=diagnostics['readWarningGuardPassed'] and not native['errors'] and not issues)
        except (ValueError, KeyError, IndexError) as error:
            frame['unprovenReason'] = str(error)
    matched = {id(row) for frame in invocations for row in frame.get('jfrMatches', [])}
    unmatched = [row for row in jfr if id(row) not in matched]
    summary = {'brokerInvocations': len(invocations),
               'completedBrokerInvocations': sum(frame['completed'] for frame in invocations),
               'independentIdentityMatches': sum(frame['independentIdentityMatched'] for frame in invocations),
               'recordedArgumentBindingsCorroborated': sum(frame['recordedArgumentBindingCorroborated'] for frame in invocations),
               'brokerUnprovenReasons': dict(Counter(frame['unprovenReason'] for frame in invocations if 'unprovenReason' in frame)),
               'nativeLifetimeIssueReasons': dict(Counter(issue['reason'] for issue in issues)),
               'nativeParseErrors': len(native['errors']), 'independentJfrRecords': len(jfr),
               'independentLogRecords': len(logs), 'unmatchedIndependentJfrRecords': len(unmatched),
               'scope': 'Saved-record counts only. Independent identity matches remain distinct from capture-wide binding guards; unfinished/missing records are not zero-cost roots or compiler savings.'}
    return {'brokerInvocations': invocations, 'nativeLifetimeIssues': issues,
            'unmatchedIndependentJfrRecords': unmatched, 'summary': summary}


def inspect(directory, report):
    files = report.setdefault('inputFiles', {})
    load = lambda name: read_file(directory / name, files)
    layout = json.loads(load('live/live-layout.json'))
    validate_layout(layout)
    launch = json.loads(load('live/observer/observer-launch.json'))
    child = json.loads(load('live/observer/child-before-exec.json'))
    script = load('live/observer/observer.bt')
    if launch['scriptSha256'] != hashlib.sha256(script).hexdigest() or launch['librarySha256'] != LIBRARY_SHA256:
        raise ValueError('Executed probe script or target library differs')
    if not launch['attached'] or launch['childExit'] != 0 or launch['observerExit'] != 0 or launch['timedOut']:
        raise ValueError('Original child/observer did not complete')
    if child['pid'] != launch['rootPid'] or child['uid'] != launch['expectedUid'] or child['gid'] != launch['expectedGid'] or \
            child['groups'] != launch['expectedGroups'] or child['mountNamespace'] != launch['expectedMountNamespace'] or \
            launch['observerMountNamespace'] == launch['expectedMountNamespace']:
        raise ValueError('Original-user or private observer namespace identity differs')
    native = parse_native(load('live/observer/observer.stdout').decode('utf-8'))
    exec_rows = [line.split('\t') for line in native['otherLines'] if line.startswith('EXEC\t')]
    if len(exec_rows) != 1 or len(exec_rows[0]) != 5 or int(exec_rows[0][2]) != launch['rootPid'] or \
            int(exec_rows[0][4]) != launch['expectedUid']:
        raise ValueError('Selected original-user exec identity is absent or ambiguous')
    diagnostics = read_diagnostics(load('live/observer/observer.stderr').decode('utf-8'), script.decode(), native['otherLines'])
    if any(group['pid'] != launch['rootPid'] for thread in native['threads'] for group in thread):
        raise ValueError('Captured native record belongs to a different process')
    report.update(nativeRecords=native, readDiagnostics=diagnostics, rawClockAnchors=launch['clockAnchors'])
    jfr = jfr_records(json.loads(load('child-compilations.stdout')))
    logs = log_records(load('live/hotspot.xml').decode('utf-8'))
    report.update(independentJfrRecords=jfr, independentLogRecords=logs,
                  correlation=correlate(native, layout, jfr, logs, diagnostics), completed=True)


def main():
    manifest, expected, archive, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualFullCallingConventionProven': False, 'rootCensusProven': False,
              'codeSizeProven': False, 'clockAlignmentProven': False, 'lossFreeCaptureProven': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Remote saved-record association review only. Per-record corroboration is separate from full argument/lifetime/root/clock/loss/size/compiler acceptance; all unknown and negative cases retained.'}
    try:
        directory = output.parent / 'saved-live-inputs'
        report['archiveBinding'] = extract_inputs(Path(manifest), expected, Path(archive), directory,
                                                  report.setdefault('inputFiles', {}))
        inspect(directory, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
