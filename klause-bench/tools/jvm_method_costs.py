"""Retain every saved method's direct and successful-inlining root observations."""
from collections import defaultdict

import jvm_compilation_events as events

MAX_OBSERVATIONS = 250000
CONTEXT_KEYS = ('arm', 'engine', 'mode', 'recipe')
SCALARS = ('rootEventCount', 'reportedElapsedNanos', 'maxReportedElapsedNanos', 'reportedSuccessfulCodeBytes')
METRIC_FIELDS = ('observedDirectMetrics', 'observedInclusiveMetrics', 'observedInclusiveC2Metrics')


def observations(report, recording_index):
    roots = report.get('compilations', [])
    by_id, direct, references, callers = defaultdict(list), defaultdict(list), defaultdict(list), defaultdict(list)
    methods = {}
    for root in roots:
        name = root['method']
        methods.setdefault(name, None)
        direct[name].append(root)
        if root['id'] is not None:
            by_id[root['id']].append(root)
    for row in report.get('inlining', []):
        name = row['callee']
        methods.setdefault(name, None)
        references[name].append(row)
        methods.setdefault(row['caller'], None)
        callers[row['caller']].append(row)
    if len(methods) > MAX_OBSERVATIONS:
        raise ValueError('Complete saved method observations exceed bounds')
    result = []
    for name in methods:
        linked, unresolved = {}, []
        for row in references[name]:
            candidates = by_id.get(row['id'], [])
            if row['succeeded'] is not True:
                continue
            if len(candidates) != 1 or row['errors']:
                unresolved.append(row['eventIndex'])
            else:
                root = candidates[0]
                linked[root['eventIndex']] = root
        included = {root['eventIndex']: root for root in direct[name]}
        included.update(linked)
        selected = [included[index] for index in sorted(included)]
        result.append({'recordingIndex': recording_index, 'method': name,
            'directRootEventIndices': [root['eventIndex'] for root in direct[name]],
            'successfulInliningRootEventIndices': sorted(linked),
            'inclusiveRootEventIndices': [root['eventIndex'] for root in selected],
            'inliningReferenceEventIndices': [row['eventIndex'] for row in references[name]],
            'callerReferenceEventIndices': [row['eventIndex'] for row in callers[name]],
            'unresolvedSuccessfulInliningEventIndices': unresolved,
            'rootErrors': [{'eventIndex': root['eventIndex'], 'errors': root['errors']}
                           for root in selected if root['errors']],
            'observedDirectMetrics': events.metrics(direct[name]),
            'observedInclusiveMetrics': events.metrics(selected),
            'observedInclusiveC2Metrics': events.metrics([root for root in selected if root['level'] == 4]),
            'fullSourcePhaseCoverageProven': False, 'runtimeSourceBindingProven': False,
            'acceptedSavingsCredit': False})
    return result


def summaries(rows, recordings):
    groups, contexts = {}, defaultdict(list)
    for ordinal, recording in enumerate(recordings):
        contexts[tuple(recording.get(key) for key in CONTEXT_KEYS)].append(ordinal)
    for ordinal, row in enumerate(rows):
        recording_index = row['recordingIndex']
        if type(recording_index) is not int or not 0 <= recording_index < len(recordings):
            raise ValueError('Method observation has no original recording')
        key = (row['method'], *(recordings[recording_index].get(name) for name in CONTEXT_KEYS))
        groups.setdefault(key, []).append((ordinal, row))
    result = []
    for key, entries in groups.items():
        represented = [row['recordingIndex'] for _, row in entries]
        represented_set = set(represented)
        if len(represented_set) != len(represented):
            raise ValueError('Repeated method observation within an original recording')
        metrics = {}
        for field in METRIC_FIELDS:
            values = [row[field] for _, row in entries]
            scalars = {}
            for name in SCALARS:
                amounts = [value[name] for value in values]
                available = bool(amounts) and all(type(value) is int and value >= 0 for value in amounts)
                scalars[name] = (max(amounts) if name == 'maxReportedElapsedNanos' else sum(amounts)) \
                    if available else None
            metrics[field] = scalars
        result.append({'method': key[0], **dict(zip(CONTEXT_KEYS, key[1:])),
            'observationIndices': [ordinal for ordinal, _ in entries],
            'observedRecordingIndices': represented,
            'recordingIndicesWithoutMethodObservation': [index for index in contexts[key[1:]]
                                                        if index not in represented_set],
            **metrics, 'fullSourcePhaseCoverageProven': False, 'runtimeSourceBindingProven': False,
            'acceptedSavingsCredit': False,
            'scope': 'Reported quantities over observed recordings only; missing method contexts are unavailable, '
                     'not zero. Maximum is the largest reported root duration across observed recordings. '
                     'Successful-inlining inclusive costs are shared and nonadditive across methods. '
                     'Original recording/source/context exclusions remain in recordingObservations; '
                     'no source-phase, lifetime, outcome, current-control or savings proof.'})
    return result
