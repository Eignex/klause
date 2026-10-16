"""Read original JVM information from authenticated saved standard-JFR exports."""
from collections import Counter
import gzip
import json
import re

import jvm_compilation_events as events
import review_jvm_task_log_cohort as cohort

MAX_CONTEXT_BYTES = 16 * 1024 * 1024
MAX_CONTEXT_EVENTS = 4096


def inspect(report, selected, represented):
    result = {'completeSavedExportRead': False, 'exportIdentity': report.get('exportFile'),
              'uncompressedIdentity': report.get('retainedExportUncompressedIdentity'),
              'jvmInformationEvents': [], 'censusEventCountsMatch': None, 'exclusions': [],
              'targetProcessRoleProven': False, 'completeProcessLifetimeProven': False,
              'runtimeSourceBindingProven': False, 'acceptedSavingsCredit': False,
              'scope': 'Original recorded JVMInformation values, with event indices in the complete saved '
                       'export. Repeated or conflicting contexts remain separate. Recorded commands, arguments '
                       'and process identities do not establish target role, complete lifetime or loaded bytes.'}
    identity = report.get('exportFile')
    if identity is None:
        result['exclusions'].append('Original census has no retained export identity')
        return result
    name = identity.get('path') if isinstance(identity, dict) else None
    if not isinstance(name, str) or not re.fullmatch(r'events-[0-9]{6}\.json\.gz', name) or \
            set(identity) != {'path', 'size', 'sha256'} or \
            type(identity['size']) is not int or not 0 <= identity['size'] <= cohort.MAX_STORED_BYTES or \
            not isinstance(identity['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', identity['sha256']):
        raise ValueError('Malformed saved JFR export identity')
    if name in represented:
        raise ValueError('Repeated saved JFR export reference')
    represented.add(name)
    if name not in selected:
        result['exclusions'].append('Referenced original export is absent from published census')
        return result
    path = selected[name]
    if cohort.file_identity(path) != {key: identity[key] for key in ('size', 'sha256')} or \
            cohort.gzip_identity(path, events.MAX_EXPORT_BYTES) != result['uncompressedIdentity']:
        raise ValueError('Saved JFR export bytes differ from original census identity')
    counts, size = Counter(), 0
    with gzip.open(path, 'rb') as source:
        for ordinal, event in events.JsonEvents(source):
            counts[event['type']] += 1
            if event['type'] != 'jdk.JVMInformation':
                continue
            size += len(json.dumps(event, ensure_ascii=True).encode('utf-8'))
            if size > MAX_CONTEXT_BYTES or len(result['jvmInformationEvents']) >= MAX_CONTEXT_EVENTS:
                raise ValueError('Complete saved JVM information exceeds context bounds')
            result['jvmInformationEvents'].append({'eventIndex': ordinal, 'values': event['values']})
    result['completeSavedExportRead'] = True
    original_counts = report.get('eventCounts')
    if original_counts is not None:
        result['censusEventCountsMatch'] = isinstance(original_counts, dict) and \
            all(isinstance(key, str) and type(value) is int and value >= 0
                for key, value in original_counts.items()) and dict(counts) == original_counts
    if result['censusEventCountsMatch'] is not True:
        result['exclusions'].append('Saved export event counts are missing or differ from original census')
    if not result['jvmInformationEvents']:
        result['exclusions'].append('Saved export has no JVMInformation event')
    elif len(result['jvmInformationEvents']) > 1:
        result['exclusions'].append('Saved export has multiple JVMInformation events; no context is selected')
    if report.get('completed') is not True:
        result['exclusions'].append('Original recording census is incomplete')
    return result
