"""Stream standard JFR JSON and retain all-tier, nonadditive compilation roots."""
from collections import Counter, defaultdict
import codecs
import json
import math
import re

from review_jvm_task_log_cohort import unique_object

CHUNK_BYTES = 64 * 1024
MAX_EVENT_BYTES = 8 * 1024 * 1024
MAX_EXPORT_BYTES = 8 * 1024 ** 3
MAX_EVENTS = 4000000
MAX_ROOTS = 1000000
EVENT_TYPES = ('jdk.Compilation', 'jdk.CompilerInlining', 'jdk.CompilerPhase',
               'jdk.CompilationFailure', 'jdk.DataLoss', 'jdk.ActiveSetting', 'jdk.JVMInformation')


class JsonEvents:
    def __init__(self, source):
        self.source = source
        self.text = ''
        self.ended = False
        self.bytes = 0
        self.utf8 = codecs.getincrementaldecoder('utf-8')()
        self.decoder = json.JSONDecoder(object_pairs_hook=unique_object, parse_constant=self.invalid_constant,
                                        parse_float=self.finite_float)

    @staticmethod
    def invalid_constant(value):
        raise ValueError('Nonfinite JFR JSON constant: ' + value)

    @staticmethod
    def finite_float(value):
        result = float(value)
        if not math.isfinite(result):
            raise ValueError('Nonfinite JFR JSON number')
        return result

    def fill(self):
        raw = self.source.read(CHUNK_BYTES)
        self.bytes += len(raw)
        if self.bytes > MAX_EXPORT_BYTES:
            raise ValueError('JFR JSON export exceeds complete byte bounds')
        self.ended = not raw
        self.text += self.utf8.decode(raw, final=self.ended)

    def space(self):
        while True:
            self.text = self.text.lstrip(' \t\r\n')
            if self.text or self.ended:
                return
            self.fill()

    def token(self, expected):
        self.space()
        if not self.text.startswith(expected):
            raise ValueError('Unsupported or incomplete JFR JSON envelope')
        self.text = self.text[len(expected):]

    def value(self):
        self.space()
        while True:
            if len(self.text.encode('utf-8')) > MAX_EVENT_BYTES + CHUNK_BYTES:
                raise ValueError('JFR JSON event exceeds complete record bounds')
            try:
                value, end = self.decoder.raw_decode(self.text)
                if len(self.text[:end].encode('utf-8')) > MAX_EVENT_BYTES:
                    raise ValueError('JFR JSON event exceeds complete record bounds')
                self.text = self.text[end:]
                return value
            except json.JSONDecodeError as error:
                if self.ended:
                    raise ValueError('Incomplete or malformed JFR JSON event') from error
                self.fill()

    def __iter__(self):
        self.token('{')
        if self.value() != 'recording':
            raise ValueError('Missing JFR recording envelope')
        self.token(':')
        self.token('{')
        if self.value() != 'events':
            raise ValueError('Missing JFR event array')
        self.token(':')
        self.token('[')
        self.space()
        ordinal = 0
        if not self.text.startswith(']'):
            while True:
                value = self.value()
                if not isinstance(value, dict) or not isinstance(value.get('type'), str) or \
                        not isinstance(value.get('values'), dict):
                    raise ValueError('Malformed JFR event object')
                if ordinal >= MAX_EVENTS:
                    raise ValueError('JFR event count exceeds complete bounds')
                yield ordinal, value
                ordinal += 1
                self.space()
                if self.text.startswith(']'):
                    break
                self.token(',')
        self.token(']')
        self.token('}')
        self.token('}')
        self.space()
        if self.text:
            raise ValueError('Unexpected bytes after JFR recording')


def duration_nanos(value):
    if not isinstance(value, str) or len(value) > 128:
        raise ValueError('JFR duration has no verified textual unit')
    match = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', value)
    if not match or not any(part is not None for part in match.groups()):
        raise ValueError('Unsupported JFR duration')
    hours, minutes, seconds = match.groups()
    whole, _, fraction = (seconds or '0').partition('.')
    fraction = fraction.rstrip('0')
    if len(fraction) > 9:
        raise ValueError('JFR duration has subnanosecond precision')
    return int(hours or '0') * 3600000000000 + int(minutes or '0') * 60000000000 + \
        int(whole) * 1000000000 + int(fraction.ljust(9, '0'))


def method(value):
    if not isinstance(value, dict):
        return None
    owner = value.get('type')
    owner = owner.get('name') if isinstance(owner, dict) else owner
    name, descriptor = value.get('name'), value.get('descriptor')
    if not all(isinstance(part, str) and part for part in (owner, name, descriptor)) or \
            not descriptor.startswith('('):
        return None
    return owner.replace('/', '.') + '.' + name + descriptor


def xml_method(value):
    parts = value.split() if isinstance(value, str) else []
    return parts[0].replace('/', '.') + '.' + parts[1] + ''.join(parts[2:]) if len(parts) >= 3 else None


def matches(value, names):
    return value is not None and any(name in value or name in value.replace('$', '.') for name in names)


def metrics(roots):
    elapsed = [root['reportedElapsedNanos'] for root in roots]
    sizes = [root['reportedSuccessfulCodeBytes'] for root in roots]
    return {'rootEventCount': len(roots),
            'reportedElapsedNanos': sum(elapsed) if roots and all(x is not None for x in elapsed) else None,
            'maxReportedElapsedNanos': max(elapsed) if roots and all(x is not None for x in elapsed) else None,
            'reportedSuccessfulCodeBytes': sum(sizes) if roots and all(x is not None for x in sizes) else None,
            'missingElapsedRootEventIndices': [x['eventIndex'] for x in roots if x['reportedElapsedNanos'] is None],
            'unavailableCodeRootEventIndices': [x['eventIndex'] for x in roots if x['reportedSuccessfulCodeBytes'] is None],
            'ordinaryRootEventIndices': [x['eventIndex'] for x in roots if x['osr'] is False],
            'osrRootEventIndices': [x['eventIndex'] for x in roots if x['osr'] is True],
            'failedRootEventIndices': [x['eventIndex'] for x in roots if x['succeeded'] is False],
            'unknownOutcomeRootEventIndices': [x['eventIndex'] for x in roots if x['succeeded'] is None]}


class Census:
    def __init__(self):
        self.roots = []
        self.inlining = []
        self.counts = Counter()

    def add(self, ordinal, event):
        kind, value = event['type'], event['values']
        self.counts[kind] += 1
        if kind not in ('jdk.Compilation', 'jdk.CompilerInlining'):
            return
        if len(self.roots) + len(self.inlining) >= MAX_ROOTS:
            raise ValueError('JFR retained root and inlining rows exceed bounds')
        errors = []
        identifier = value.get('compileId')
        if type(identifier) is not int or identifier <= 0:
            identifier = None
            errors.append('Missing or invalid compileId')
        if kind == 'jdk.CompilerInlining':
            caller, callee, success = method(value.get('caller')), method(value.get('callee')), value.get('succeeded')
            if caller is None or callee is None or type(success) is not bool:
                errors.append('Missing inlining method or outcome')
            self.inlining.append({'eventIndex': ordinal, 'id': identifier, 'caller': caller,
                                  'callee': callee, 'succeeded': success if type(success) is bool else None,
                                  'errors': errors})
            return
        name = method(value.get('method'))
        success = value.get('succeeded', value.get('succeded'))
        if 'succeeded' in value and 'succeded' in value and value['succeeded'] != value['succeded']:
            success = None
            errors.append('Conflicting compilation outcome fields')
        level, osr, size = value.get('compileLevel'), value.get('isOsr'), value.get('codeSize')
        if name is None or type(level) is not int or type(osr) is not bool or type(success) is not bool:
            errors.append('Missing compilation method, tier, kind or outcome')
        try:
            elapsed = duration_nanos(value.get('duration'))
        except ValueError as error:
            elapsed = None
            errors.append(str(error))
        if type(size) is not int or size < 0:
            errors.append('Missing or invalid reported codeSize')
        self.roots.append({'eventIndex': ordinal, 'id': identifier, 'method': name,
                           'level': level if type(level) is int else None, 'compiler': value.get('compiler'),
                           'osr': osr if type(osr) is bool else None,
                           'succeeded': success if type(success) is bool else None,
                           'reportedElapsedNanos': elapsed,
                           'reportedSuccessfulCodeBytes': size if success is True and type(size) is int and size >= 0 else None,
                           'errors': errors})

    def finish(self, families, arm, snapshots):
        ids = defaultdict(list)
        for root in self.roots:
            if root['id'] is not None:
                ids[root['id']].append(root)
        unique = {key: rows[0] for key, rows in ids.items() if len(rows) == 1}
        resolved = {row['eventIndex']: unique[row['id']] for row in self.inlining
                    if row['id'] in unique and not row['errors']}
        methods = defaultdict(list)
        tiers = defaultdict(list)
        for root in self.roots:
            methods[root['method']].append(root)
            tiers[str(root['level']) if root['level'] is not None else 'unknown'].append(root)
        definitions = {}
        for name, definition in families.items():
            names = definition['methods']
            if not isinstance(names, list) or not names or not all(isinstance(x, str) and x for x in names):
                raise ValueError('Empty or malformed original compilation family')
            direct = {root['eventIndex'] for root in self.roots if matches(root['method'], names)}
            references = [row for row in self.inlining if matches(row['callee'], names)]
            succeeded = [row for row in references if row['succeeded'] is True]
            linked = {resolved[row['eventIndex']]['eventIndex'] for row in succeeded if row['eventIndex'] in resolved}
            included = direct | linked
            selected = [root for root in self.roots if root['eventIndex'] in included]
            phases = definition.get('ordinaryPhase', {}).get(arm, [])
            required = definition.get('ordinaryPhaseAll', {}).get(arm, [])
            if not isinstance(phases, list) or not isinstance(required, list) or \
                    not all(isinstance(x, str) and x for x in phases + required) or not set(required) <= set(phases):
                raise ValueError('Malformed original ordinary phase declaration')
            ordinary = {root['eventIndex'] for root in selected if root['osr'] is False and root['succeeded'] is True}
            phase_roots = {phase: {root['eventIndex'] for root in selected if root['eventIndex'] in ordinary and
                                  root['method'] is not None and root['method'].split('(')[0] == phase} for phase in phases}
            for row in succeeded:
                if row['eventIndex'] in resolved and row['callee'] is not None:
                    root = resolved[row['eventIndex']]
                    phase = row['callee'].split('(')[0]
                    if phase in phase_roots and root['eventIndex'] in ordinary:
                        phase_roots[phase].add(root['eventIndex'])
            unmeasured, conflicting = [], []
            for collection in ('queued', 'tasks', 'nmethods'):
                for index, snapshot in enumerate(snapshots.get(collection, [])):
                    identifier = snapshot.get('compileId')
                    raw = snapshot['rawAttributes']
                    observed_method = xml_method(raw.get('method'))
                    root = unique.get(identifier)
                    if not matches(observed_method, names) and not (root and root['eventIndex'] in included):
                        continue
                    reference = {'collection': collection, 'index': index}
                    if root is None:
                        unmeasured.append(reference)
                    elif observed_method != root['method'] or \
                            (snapshot.get('level') is not None and snapshot['level'] != root['level']) or \
                            (snapshot.get('compileKind') == 'osr' and root['osr'] is not True) or \
                            (snapshot.get('compileKind') in (None, 'c2c') and root['osr'] is True):
                        conflicting.append(reference)
            definitions[name] = {'directRootEventIndices': sorted(direct),
                'successfulInliningRootEventIndices': sorted(linked),
                'inclusiveRootEventIndices': sorted(included),
                'inliningReferenceEventIndices': [row['eventIndex'] for row in references],
                'unresolvedSuccessfulInliningEventIndices': [row['eventIndex'] for row in succeeded if row['eventIndex'] not in resolved],
                'ordinaryPhaseMethodRootEventIndices': {key: sorted(value) for key, value in phase_roots.items()},
                'requiredOrdinaryPhases': required,
                'missingRequiredOrdinaryPhases': [phase for phase in required if not phase_roots[phase]],
                'unmeasuredAllTierXmlSnapshotReferences': unmeasured,
                'conflictingXmlSnapshotReferences': conflicting,
                'observedAllTierMetrics': metrics(selected),
                'observedC2Metrics': metrics([root for root in selected if root['level'] == 4]),
                'fullSourcePhaseCoverageProven': False, 'acceptedSavingsCredit': False}
        return {'eventCounts': dict(self.counts), 'compilations': self.roots, 'inlining': self.inlining,
                'ambiguousCompileIds': {str(key): [row['eventIndex'] for row in rows]
                                        for key, rows in ids.items() if len(rows) != 1},
                'unresolvedSuccessfulInliningEventIndices': [row['eventIndex'] for row in self.inlining
                                                            if row['succeeded'] is True and row['eventIndex'] not in resolved],
                'observedAllClassMetrics': metrics(self.roots),
                'observedTierMetrics': {tier: metrics(rows) for tier, rows in tiers.items()},
                'directMethodCensus': [{'method': name, 'rootEventIndices': [row['eventIndex'] for row in rows],
                                      'observedMetrics': metrics(rows)} for name, rows in methods.items()],
                'families': definitions, 'rootCensusProven': False, 'compilerElapsedTimeProven': False,
                'codeSizeProven': False, 'runtimeSourceBindingProven': False, 'acceptedSavingsCredit': False,
                'scope': 'Observed standard-JFR event durations and reported successful codeSize only. '
                         'All tiers, failures, OSR and replacement events retained; roots are counted once within '
                         'a family and shared family totals are nonadditive. Empty or missing quantities are '
                         'unavailable. No global lifetime/loss/source-phase/current-runtime or saving proof.'}
