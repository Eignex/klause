"""Compare complete saved JFR censuses without promoting observed costs to proof."""
from collections import defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import tarfile
import tempfile

import jvm_compilation_mapping as mapping
import jvm_method_costs as method_costs
import jvm_recording_context as context
import review_jvm_task_log_cohort as cohort

MAX_JSON_BYTES = 512 * 1024 * 1024
CONTEXT_KEYS = ('engine', 'index', 'repetition', 'mode', 'recipe')
METRICS = ('reportedElapsedNanos', 'maxReportedElapsedNanos', 'reportedSuccessfulCodeBytes')
XML_REFERENCE_FIELDS = ('unmeasuredAllTierXmlSnapshotReferences', 'conflictingXmlSnapshotReferences')
XML_COLLECTIONS = ('queued', 'tasks', 'nmethods')
STUDY_OUTCOME_FIELDS = ('feasible', 'proven', 'objective', 'exactObjective', 'finalWitness',
                        'sourceHashes', 'checkedWitness', 'validationPolicy')
STUDY_WORK_FIELDS = ('nodes', 'failures', 'restarts', 'propagations', 'propagationWork',
                     'rootPropagationWork', 'learned', 'lsMoves', 'lsRestarts', 'lsStalls',
                     'nodeBudgetLimit', 'nodeBudgetSpent', 'nodeBudgetRemaining')


def compact_family_census(value):
    result = dict(value)
    for field in XML_REFERENCE_FIELDS:
        grouped, previous = {}, -1
        for reference in result.pop(field):
            collection, index = reference['collection'], reference['index']
            if collection not in XML_COLLECTIONS or type(index) is not int or index < 0 or \
                    set(reference) != {'collection', 'index'}:
                raise ValueError('Malformed XML snapshot reference')
            position = XML_COLLECTIONS.index(collection)
            if position < previous:
                raise ValueError('XML snapshot reference collections are out of source order')
            previous = position
            grouped.setdefault(collection, []).append(index)
        result[field.replace('References', 'IndicesByCollection')] = grouped
    return result


def referenced_pairs(pairs, rows):
    positions = {id(row): ordinal for ordinal, row in enumerate(rows)}
    if len(positions) != len(rows):
        raise ValueError('Repeated family observation object')
    result = []
    for pair in pairs:
        try:
            arms = {arm: [{'observationIndex': positions[id(row)]} for row in values]
                    for arm, values in pair['arms'].items()}
        except KeyError as error:
            raise ValueError('Paired arm has no retained family observation') from error
        result.append(dict(pair, arms=arms))
    return result


def observation_summaries(rows):
    return [{**{key: row.get(key) for key in ('case', 'family', 'arm', *CONTEXT_KEYS,
                'originalSourceStatus', 'observedDeclaredPhaseAndSourceEligible', 'exclusions')},
             'observedAllTierMetrics': {key: row['observedMetrics'].get(key) for key in ('rootEventCount', *METRICS)},
             'observedC2Metrics': {key: row['observedC2Metrics'].get(key) for key in ('rootEventCount', *METRICS)},
             'fullSourcePhaseCoverageProven': False, 'runtimeSourceBindingProven': False,
             'acceptedSavingsCredit': False} for row in rows]


def source_status(metadata, checks):
    problem = metadata.get('problem', {})
    if problem.get('format') in ('MINIZINC', 'XCSP3'):
        record = metadata.get('record')
        stats = record.get('stats', {}) if isinstance(record, dict) else {}
        return stats.get('sourceValidation', 'missing')
    return checks.get(metadata.get('case'), 'missing')


def checker_status(entry):
    if 'error' in entry:
        return 'invalid'
    check = entry.get('check', {})
    if check.get('sourceHashVerified') is not True:
        return 'unknown'
    if check.get('witness') == 'source feasible':
        return 'proof-limited' if check.get('proof') == 'unknown' else 'valid'
    if check.get('witness') == 'exact source feasible' or \
            check.get('proof') in ('source pigeonhole refutation', 'source refutation') or \
            check.get('verdictCheck') == 'source unsat':
        return 'valid'
    return 'unknown'


def study_control_record(metadata, ordinal, checks, heads):
    record = metadata.get('record')
    present = isinstance(record, dict)
    record = record if present else {}
    stats = record.get('stats')
    stats_valid = isinstance(stats, dict) and all(isinstance(key, str) and isinstance(value, str)
                                                 for key, value in stats.items())
    stats = stats if stats_valid else {}
    arm = metadata.get('arm')
    expected = heads.get(arm)
    problem = metadata.get('problem')
    status = source_status(metadata, checks) if isinstance(problem, dict) and \
        (problem.get('format') not in ('MINIZINC', 'XCSP3') or stats_valid) else 'missing'
    return {'case': metadata['case'], 'originalCaseMetadataIndex': ordinal, 'arm': arm,
            'benchmarkExit': metadata.get('exit'), 'benchmarkInterrupted': metadata.get('interrupted'),
            'benchmarkError': metadata.get('error'), 'sourceHashesMatch': metadata.get('sourceHashesMatch'),
            'originalSourceStatus': status, 'recordPresent': present,
            'recordCommandIsError': record.get('command') == 'ERROR', 'recordStatsAreStringPairs': stats_valid,
            'expectedSourceHead': expected, 'recordedSourceHead': record.get('gitSha'),
            'recordedSourceHeadMatches': record.get('gitSha') == expected if expected is not None else None,
            'targetLifecycleControls': {key: stats[key] for key in ('exit', 'killed', 'unsupported', 'errorClass')
                                        if key in stats},
            'recordedWholeCliMillis': record.get('elapsedMs'),
            'reportedWorkFields': {key: stats[key] for key in STUDY_WORK_FIELDS if key in stats},
            'outcomeFieldValueIdentities': {key: {'present': key in record,
                'compactJsonSha256': hashlib.sha256(json.dumps(record[key], ensure_ascii=True,
                    separators=(',', ':'), allow_nan=False).encode()).hexdigest() if key in record else None}
                for key in STUDY_OUTCOME_FIELDS}}


def study_controls(measurements, checks, heads):
    blocks = defaultdict(lambda: defaultdict(list))
    for ordinal, metadata in enumerate(measurements):
        if not isinstance(metadata, dict) or \
                not all(isinstance(metadata.get(name), str) and metadata[name] for name in ('case', 'arm')) or \
                not all(isinstance(metadata.get(name), str) and metadata[name] for name in ('engine', 'mode')) or \
                not all(type(metadata.get(name)) is int and metadata[name] >= 0 for name in ('index', 'repetition')) or \
                not (metadata.get('recipe') is None or isinstance(metadata['recipe'], str)):
            raise ValueError('Original study control context is malformed')
        key = tuple(metadata.get(name) for name in CONTEXT_KEYS)
        blocks[key][metadata.get('arm')].append((ordinal, metadata))
    pairs = []
    for key, arms in blocks.items():
        result = {**dict(zip(CONTEXT_KEYS, key)), 'arms': {}, 'exclusions': [],
                  'outcomeFieldEquality': None, 'reportedWorkFieldEquality': None,
                  'allRecordedStatsDifferenceKeys': None, 'originalProblemDescriptionsEqual': None,
                  'descriptiveWholeCliMillis': None, 'completeWorkOrRngTraceProven': False,
                  'runtimeSourceBindingProven': False, 'acceptedSavingsCredit': False}
        for arm, rows in arms.items():
            result['arms'][arm] = [study_control_record(row, ordinal, checks, heads) for ordinal, row in rows]
        if set(arms) != {'control', 'candidate'} or any(len(rows) != 1 for rows in arms.values()):
            result['exclusions'].append('Missing, duplicate or unexpected original control pair arms')
            pairs.append(result)
            continue
        a, b = (arms[arm][0][1] for arm in ('control', 'candidate'))
        result['originalProblemDescriptionsEqual'] = a['problem'] == b['problem'] \
            if isinstance(a.get('problem'), dict) and isinstance(b.get('problem'), dict) else None
        if result['originalProblemDescriptionsEqual'] is not True:
            result['exclusions'].append('Original control problem descriptions are absent or differ')
        records = [row.get('record') for row in (a, b)]
        if not all(isinstance(row, dict) for row in records):
            result['exclusions'].append('Original benchmark record is absent or malformed')
            pairs.append(result)
            continue
        control, candidate = records
        controls = [result['arms'][arm][0] for arm in ('control', 'candidate')]
        identities = [row['outcomeFieldValueIdentities'] for row in controls]
        result['outcomeFieldEquality'] = {field:
            identities[0][field]['compactJsonSha256'] == identities[1][field]['compactJsonSha256']
            if all(row[field]['present'] for row in identities) else None for field in STUDY_OUTCOME_FIELDS}
        if any(value is not True for value in result['outcomeFieldEquality'].values()):
            result['exclusions'].append('Original outcome fields differ or are unavailable')
        if all(row['recordStatsAreStringPairs'] for row in controls):
            ac, bc = control['stats'], candidate['stats']
            result['reportedWorkFieldEquality'] = {field: ac[field] == bc[field]
                if field in ac and field in bc else None for field in STUDY_WORK_FIELDS}
            result['allRecordedStatsDifferenceKeys'] = sorted(field for field in ac.keys() | bc.keys()
                if field not in ac or field not in bc or ac[field] != bc[field])
            if any(value is False for value in result['reportedWorkFieldEquality'].values()):
                result['exclusions'].append('Original reported work fields differ')
        else:
            result['exclusions'].append('Original reported statistic fields are absent or malformed')
        for arm in ('control', 'candidate'):
            row = result['arms'][arm][0]
            if type(row['benchmarkExit']) is not int or row['benchmarkExit'] != 0 or \
                    row['benchmarkInterrupted'] is not False or row['benchmarkError'] is not None:
                result['exclusions'].append(arm + ': original benchmark did not complete successfully')
            if row['targetLifecycleControls'].get('exit') != '0' or \
                    any(field in row['targetLifecycleControls'] for field in ('killed', 'unsupported', 'errorClass')):
                result['exclusions'].append(arm + ': original target exit/refusal/failure is nonzero or unavailable')
            if row['sourceHashesMatch'] is not True or row['recordedSourceHeadMatches'] is not True:
                result['exclusions'].append(arm + ': original source hash or recorded head is unmatched or unavailable')
            if row['originalSourceStatus'] != 'valid' or row['recordCommandIsError']:
                result['exclusions'].append(arm + ': original source outcome is not independently valid')
        ac, bc = control.get('elapsedMs'), candidate.get('elapsedMs')
        available = type(ac) is int and ac >= 0 and type(bc) is int and bc >= 0
        result['descriptiveWholeCliMillis'] = {'available': available, 'control': ac, 'candidate': bc,
            'candidateMinusControl': bc - ac if available else None,
            'candidateOverControl': {'numerator': bc, 'denominator': ac} if available and ac > 0 else None,
            'scope': 'Unqualified original elapsedMs including CLI launch/output; all failures, source and work differences retained. Not compiler elapsed time, throughput, quality or savings acceptance.'}
        pairs.append(result)
    return {'allOriginalControlPairs': pairs, 'expectedRecordedSourceHeads': heads,
            'completeWorkOrRngTraceProven': False, 'runtimeSourceBindingProven': False,
            'acceptedSavingsCredit': False,
            'scope': 'Every original mode/engine/index/repetition/recipe/arm, including nonprofiled cold and fixed controls. Outcome presence and compact-JSON field identities, selected source-declared work fields and every raw-statistic difference key are retained. No normalization drops unknown or timing fields. Missing fields are unavailable, not equal-work proof. Counters and equal outcomes do not establish complete state/order/RNG/work traces; raw timings include excluded contexts.'}


def observations(case, metadata, report, checks, report_identity, cohort_complete):
    rows = []
    common = []
    if not cohort_complete:
        common.append('Original cohort census is incomplete')
    if case.get('completed') is not True or report.get('completed') is not True:
        common.append('Incomplete original case or recording census')
    if metadata.get('sourceHashesMatch') is not True:
        common.append('Original source hashes are not matched')
    if not all(isinstance(metadata.get(key), str) and metadata[key] for key in ('engine', 'mode')) or \
            not all(type(metadata.get(key)) is int and metadata[key] >= 0 for key in ('index', 'repetition')) or \
            not (metadata.get('recipe') is None or isinstance(metadata['recipe'], str)):
        raise ValueError('Original paired context is malformed')
    if metadata.get('exit') != 0 or type(metadata.get('exit')) is not int:
        common.append('Original invocation did not exit successfully')
    status = source_status(metadata, checks)
    if status != 'valid':
        common.append('Original source outcome is ' + str(status))
    if report.get('eventCounts', {}).get('jdk.DataLoss', 0):
        common.append('Recording contains a JFR data-loss event')
    if report.get('ambiguousCompileIds'):
        common.append('Recording has ambiguous compilation identities')
    if report.get('unresolvedSuccessfulInliningEventIndices'):
        common.append('Recording has unresolved successful inlining roots')
    for family, value in report.get('families', {}).items():
        exclusions = list(common)
        required = value['requiredOrdinaryPhases']
        if not required:
            exclusions.append('Original required ordinary phase declaration is empty')
        for key in ('missingRequiredOrdinaryPhases', 'unresolvedSuccessfulInliningEventIndices',
                    'unmeasuredAllTierXmlSnapshotReferences', 'conflictingXmlSnapshotReferences'):
            if value[key]:
                exclusions.append(key)
        metrics = value['observedAllTierMetrics']
        if not value['inclusiveRootEventIndices']:
            exclusions.append('Family has no observed inclusive root')
        included = set(value['inclusiveRootEventIndices'])
        roots = [row for row in report.get('compilations', []) if row['eventIndex'] in included]
        if len(roots) != len(included) or any(row['errors'] for row in roots):
            exclusions.append('Family roots are missing or have recording errors')
        for key in ('failedRootEventIndices', 'unknownOutcomeRootEventIndices'):
            if metrics[key]:
                exclusions.append(key)
        rows.append({'case': case['caseDirectory'], 'family': family,
            'arm': metadata.get('arm'), **{key: metadata.get(key) for key in CONTEXT_KEYS},
            'originalProblemCopiedVerbatim': metadata.get('problem'),
            'originalSourceStatus': status, 'sourceHashesMatch': metadata.get('sourceHashesMatch'),
            'censusReportIdentity': report_identity, 'familyCensusCopiedVerbatim': value,
            'observedMetrics': metrics, 'observedC2Metrics': value['observedC2Metrics'],
            'observedDeclaredPhaseAndSourceEligible': not exclusions,
            'exclusions': exclusions, 'fullSourcePhaseCoverageProven': False,
            'runtimeSourceBindingProven': False, 'acceptedSavingsCredit': False})
    return rows


def paired(rows):
    blocks = defaultdict(lambda: defaultdict(list))
    for row in rows:
        key = (row['family'], *(row[name] for name in CONTEXT_KEYS))
        blocks[key][row['arm']].append(row)
    pairs = []
    for key, arms in blocks.items():
        exclusions = []
        if set(arms) != {'control', 'candidate'} or any(len(value) != 1 for value in arms.values()):
            exclusions.append('Missing, duplicate or unexpected original pair arms')
        flat = [row for arm in arms.values() for row in arm]
        if not all(row['observedDeclaredPhaseAndSourceEligible'] for row in flat):
            exclusions.append('At least one arm has source or declared-phase exclusions')
        if any(flat[0]['originalProblemCopiedVerbatim'] != row['originalProblemCopiedVerbatim'] for row in flat):
            exclusions.append('Original problem identities differ between paired arms')
        values = {}
        for metric in METRICS:
            amounts = {arm: value[0]['observedMetrics'][metric] for arm, value in arms.items()
                       if len(value) == 1}
            available = not exclusions and set(amounts) == {'control', 'candidate'} and \
                all(type(value) is int and value >= 0 for value in amounts.values())
            values[metric] = {'available': available, 'originalArmValues': amounts,
                'candidateMinusControl': amounts['candidate'] - amounts['control'] if available else None,
                'candidateOverControl': {'numerator': amounts['candidate'], 'denominator': amounts['control']}
                    if available and amounts['control'] > 0 else None}
        pairs.append({'family': key[0], **dict(zip(CONTEXT_KEYS, key[1:])),
            'arms': dict(arms), 'exclusions': exclusions,
            'observedDeclaredPhaseAndSourceEligible': not exclusions,
            'observedAllTierDifferences': values, 'acceptedSavingsCredit': False})
    return pairs


def summaries(pairs):
    groups = defaultdict(list)
    for pair in pairs:
        key = (pair['family'], pair['engine'], pair['mode'], pair['recipe'])
        groups[key].append(pair)
    result = []
    for key, rows in groups.items():
        metrics = {}
        for name in METRICS:
            available = [row['observedAllTierDifferences'][name] for row in rows
                         if row['observedAllTierDifferences'][name]['available']]
            controls = [row['originalArmValues']['control'] for row in available]
            candidates = [row['originalArmValues']['candidate'] for row in available]
            differences = [row['candidateMinusControl'] for row in available]
            metrics[name] = {'observedEligiblePairs': len(available),
                'sumOfPairControlValues': sum(controls) if controls else None,
                'sumOfPairCandidateValues': sum(candidates) if candidates else None,
                'sumOfPairCandidateMinusControl': sum(differences) if differences else None,
                'pairDifferenceRange': [min(differences), max(differences)] if differences else None,
                'aggregation': 'Sum of per-recording maxima, not a cohort maximum' if
                    name == 'maxReportedElapsedNanos' else 'Sum of paired inclusive-root reported quantities'}
        result.append({'family': key[0], 'engine': key[1], 'mode': key[2], 'recipe': key[3],
            'totalOriginalPairs': len(rows),
            'observedDeclaredPhaseAndSourceEligiblePairs': sum(row['observedDeclaredPhaseAndSourceEligible'] for row in rows),
            'excludedPairContexts': [{**{name: row[name] for name in ('index', 'repetition', 'exclusions')},
                'armExclusions': {arm: [{'case': value.get('case'), 'exclusions': value.get('exclusions', [])}
                                       for value in values] for arm, values in row['arms'].items()}}
                                     for row in rows if row['exclusions']],
            'metrics': metrics, 'acceptedSavingsCredit': False})
    return result


def load_json(path, compressed=False):
    with (gzip.open(path, 'rb') if compressed else path.open('rb')) as source:
        raw = source.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError('Complete census JSON exceeds bounds')
    def invalid_constant(value):
        raise ValueError('Nonfinite census JSON constant: ' + value)

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('Nonfinite census JSON number')
        return number

    return json.loads(raw, object_pairs_hook=cohort.unique_object, parse_constant=invalid_constant,
                      parse_float=finite_float)


def authenticate(manifest_path, expected, archive_path, scratch):
    if manifest_path.stat().st_size > cohort.MAX_MANIFEST_BYTES or \
            cohort.file_identity(manifest_path)['sha256'] != expected:
        raise ValueError('Census manifest differs from pinned identity')
    manifest = load_json(manifest_path)
    files, archive = manifest['files'], manifest['archive']
    if not isinstance(files, dict) or not 0 < len(files) <= cohort.MAX_MEMBERS or \
            type(archive['size']) is not int or not 0 <= archive['size'] <= cohort.MAX_ARCHIVE_BYTES or \
            archive_path.stat().st_size > cohort.MAX_ARCHIVE_BYTES or \
            cohort.file_identity(archive_path) != {key: archive[key] for key in ('size', 'sha256')}:
        raise ValueError('Complete census archive identity or inventory is invalid')
    for name, value in files.items():
        if not cohort.safe_name(name) or type(value['size']) is not int or value['size'] < 0 or \
                not re.fullmatch('[0-9a-f]{64}', value['sha256']):
            raise ValueError('Unsafe census member identity')
    prefixes = {name.removesuffix('compilation-cohort.json') for name in files
                if re.fullmatch(r'profile[1-9][0-9]*/compilation-cohort\.json', name)}
    if len(prefixes) != 1:
        raise ValueError('Complete census index prefix is absent or ambiguous')
    prefix = prefixes.pop()
    selected, seen, expanded, stored = {}, set(), 0, 0
    with tarfile.open(archive_path, 'r|gz') as stream:
        for member in stream:
            if not member.isfile() or member.name in seen or member.name not in files or \
                    not cohort.safe_name(member.name):
                raise ValueError('Census archive has linked, duplicate or unexpected members')
            seen.add(member.name)
            value = files[member.name]
            expanded += member.size
            if member.size != value['size'] or member.size < 0 or expanded > cohort.MAX_EXPANDED_BYTES:
                raise ValueError('Complete census archive expansion differs or exceeds bounds')
            name = member.name.removeprefix(prefix) if member.name.startswith(prefix) else ''
            chosen = name == 'compilation-cohort.json' or bool(re.fullmatch(
                r'(?:(?:compilation|events)-[0-9]{6}\.json|xml-[0-9]{6}-[0-9]{3}\.json|'
                r'original-(?:cases|compilation-families|study-builds|boolean-source-checks|linear-source-checks)\.json)\.gz', name))
            if chosen:
                stored += member.size
                if stored > cohort.MAX_STORED_BYTES:
                    raise ValueError('Selected census compressed inputs exceed bounds')
            path = scratch / str(len(selected)) if chosen else None
            target = path.open('xb') if chosen else None
            digest, size = hashlib.sha256(), 0
            try:
                with stream.extractfile(member) as source:
                    while chunk := source.read(cohort.CHUNK_BYTES):
                        size += len(chunk)
                        digest.update(chunk)
                        if target is not None:
                            target.write(chunk)
            finally:
                if target is not None:
                    target.close()
            if {'size': size, 'sha256': digest.hexdigest()} != value:
                raise ValueError('Census archive member bytes differ from manifest')
            if path is not None:
                selected[name] = path
    if seen != set(files):
        raise ValueError('Complete census archive membership differs')
    return selected, manifest, prefix


def inspect(selected, manifest, prefix, declaration=None):
    index = load_json(selected['compilation-cohort.json'])
    measurements = load_json(selected['original-cases.json.gz'], True)
    originals = index['originalMetadataFiles']
    original_cases = next(value for value in originals.values() if value['path'] == 'original-cases.json.gz')
    if cohort.file_identity(selected['original-cases.json.gz']) != original_cases['compressed'] or \
            cohort.gzip_identity(selected['original-cases.json.gz'], MAX_JSON_BYTES) != \
            {key: original_cases['originalIdentity'][key] for key in ('size', 'sha256')}:
        raise ValueError('Retained original case metadata differs from producer identity')
    metadata = {row['case']: row for row in measurements}
    if len(metadata) != len(measurements) or len({row['caseDirectory'] for row in index['cases']}) != len(index['cases']):
        raise ValueError('Duplicate original case identities')
    checks = {}
    for name in ('boolean-source-checks', 'linear-source-checks'):
        path = selected.get('original-' + name + '.json.gz')
        if path is None:
            continue
        report = load_json(path, True)
        for entry in report if isinstance(report, list) else report.get('entries', []):
            if entry.get('index') is not None:
                key = str(entry['index'])
                if key in checks:
                    raise ValueError('Ambiguous original source checker identity')
                checks[key] = checker_status(entry)
    mapped_families = mapping.bind(declaration, selected, index, manifest, load_json, MAX_JSON_BYTES) \
        if declaration is not None else None
    heads = {}
    builds = selected.get('original-study-builds.json.gz')
    if builds is not None:
        references = [value for value in originals.values() if value['path'] == 'original-study-builds.json.gz']
        if len(references) != 1 or cohort.file_identity(builds) != references[0]['compressed'] or \
                cohort.gzip_identity(builds, MAX_JSON_BYTES) != \
                {key: references[0]['originalIdentity'][key] for key in ('size', 'sha256')}:
            raise ValueError('Retained original study builds differ from producer identity')
        for build in load_json(builds, True):
            arm = build['arm']
            if arm not in ('control', 'candidate') or arm in heads or \
                    build['actualHead'] != build['expectedHead'] or \
                    not isinstance(build['actualHead'], str) or not re.fullmatch('[0-9a-f]{40}', build['actualHead']):
                raise ValueError('Original study source heads are malformed, duplicate or inconsistent')
            heads[arm] = build['actualHead']
    rows, recordings, represented, mapped_rows, represented_xml = [], [], set(), [], set()
    represented_exports = set()
    method_rows = []
    cohort_complete = index.get('completed') is True and index.get('allCaseCensusesComplete') is True and \
        not index.get('metadataOnlyCaseNames') and not index.get('archivedCasesMissingMetadata') and \
        set(metadata) == {row['caseDirectory'] for row in index['cases']}
    for case in index['cases']:
        name = case['caseDirectory']
        if name not in metadata:
            raise ValueError('Archived case has no original measurement metadata')
        if measurements[case['originalCaseMetadataIndex']] != metadata[name]:
            raise ValueError('Original case metadata ordinal differs from census')
        xml, mapping_errors = {}, []
        if mapped_families is not None:
            for entry in case.get('xmlInputs', []):
                path = entry['retainedMetadata']['path']
                if path in represented_xml:
                    raise ValueError('Repeated retained XML metadata identity')
                represented_xml.add(path)
            xml, mapping_errors = mapping.snapshots(case, selected, load_json, MAX_JSON_BYTES)
        for recording in case['recordings']:
            retained = recording['report']
            path = selected[retained['path']]
            if retained['path'] in represented or cohort.file_identity(path) != retained['compressed'] or \
                    cohort.gzip_identity(path, MAX_JSON_BYTES) != retained['uncompressed']:
                raise ValueError('Census report identity is repeated or differs from index')
            represented.add(retained['path'])
            report = load_json(path, True)
            if report.get('originalMember') != recording['originalMember'] or \
                    report.get('originalIdentity') != recording['originalIdentity'] or \
                    report.get('originalCaseDirectory') != name:
                raise ValueError('Census report original recording identity differs')
            recordings.append({'case': name, 'reportIdentity': retained,
                'arm': metadata[name].get('arm'), **{key: metadata[name].get(key) for key in CONTEXT_KEYS},
                'originalSourceStatus': source_status(metadata[name], checks),
                'sourceHashesMatch': metadata[name].get('sourceHashesMatch'), 'originalExit': metadata[name].get('exit'),
                'originalCaseCompleted': case.get('completed'), 'originalCohortComplete': cohort_complete,
                'completed': report.get('completed'), 'error': report.get('error'),
                'savedJvmContext': context.inspect(report, selected, represented_exports),
                'eventCounts': report.get('eventCounts'),
                'observedAllClassMetrics': report.get('observedAllClassMetrics'),
                'observedTierMetrics': report.get('observedTierMetrics'),
                'ambiguousCompileIds': report.get('ambiguousCompileIds'),
                'unresolvedSuccessfulInliningEventIndices': report.get('unresolvedSuccessfulInliningEventIndices'),
                'familyNames': list(report.get('families', {}))})
            method_rows.extend(method_costs.observations(report, len(recordings) - 1))
            if len(method_rows) > method_costs.MAX_OBSERVATIONS:
                raise ValueError('Complete cohort method observations exceed bounds')
            rows.extend(observations(case, metadata[name], report, checks, retained, cohort_complete))
            if mapped_families is not None:
                mapped = mapping.reform(report, mapped_families, metadata[name].get('arm'), xml)
                new_rows = observations(case, metadata[name], mapped, checks, retained, cohort_complete)
                for row in new_rows:
                    row['mappedFamilyCensus'] = row.pop('familyCensusCopiedVerbatim')
                    row['originalFamilyCensusReference'] = {'case': name, 'family': row['family'],
                        'censusReportIdentity': retained} if row['family'] in report.get('families', {}) else None
                    row['exclusions'].extend(mapping_errors)
                    row['observedDeclaredPhaseAndSourceEligible'] = not row['exclusions']
                mapped_rows.extend(new_rows)
    census_files = {name for name in selected if re.fullmatch(r'compilation-[0-9]{6}\.json\.gz', name)}
    if census_files != represented:
        raise ValueError('Unrepresented census report files')
    if mapped_families is not None and represented_xml != \
            {name for name in selected if re.fullmatch(r'xml-[0-9]{6}-[0-9]{3}\.json\.gz', name)}:
        raise ValueError('Unrepresented retained XML metadata files')
    pairs = paired(rows)
    mapped_pairs = paired(mapped_rows)
    return {'completed': True, 'sourceSha256': cohort.file_identity(Path(__file__))['sha256'],
        'completeArchiveMemberBytesVerified': True, 'inputArchive': manifest['archive'],
        'inputCensusPrefix': prefix, 'producerCohortProtocolCopiedVerbatim': index,
        'reportFormatVersion': 2,
        'pairArmRepresentation': 'observationIndex references sibling familyCaseObservations; all arms and duplicates retained',
        'metadataOnlyCaseNames': sorted(set(metadata) - {row['caseDirectory'] for row in index['cases']}),
        'recordingObservations': recordings,
        'originalPairedStudyControls': study_controls(measurements, checks, heads),
        'methodRecordingObservations': method_rows,
        'observedMethodSummaries': method_costs.summaries(method_rows, recordings),
        'unreferencedSavedExportPaths': [name for name in selected
            if re.fullmatch(r'events-[0-9]{6}\.json\.gz', name) and name not in represented_exports],
        'recordingsWithoutFamilyCensus': [row for row in recordings if not row['familyNames']],
        'familyCaseObservations': rows, 'allOriginalContextPairs': referenced_pairs(pairs, rows),
        'observedComparisonSummaries': summaries(pairs),
        'phaseMapping': {'declarationCopiedVerbatim': declaration,
            'sourceClosure': {path.name: cohort.file_identity(path) for path in
                              (Path(mapping.__file__), Path(mapping.events.__file__))},
            'familyCaseObservations': [dict(row, mappedFamilyCensus=compact_family_census(row['mappedFamilyCensus']))
                                       for row in mapped_rows],
            'allOriginalContextPairs': referenced_pairs(mapped_pairs, mapped_rows),
            'xmlSnapshotReferenceEncoding': {field: field.replace('References', 'IndicesByCollection')
                                             for field in XML_REFERENCE_FIELDS},
            'observedComparisonSummaries': summaries(mapped_pairs),
            'runtimeSourceBindingProven': False, 'fullSourcePhaseCoverageProven': False,
            'acceptedSavingsCredit': False,
            'scope': 'Separate source-declared mapping over all retained compilation/inlining roots and XML metadata. '
                     'Recorded source-head equality does not prove the loaded runtime or exhaustive source coverage. '
                     'Original declarations, original comparisons and all their exclusions remain unchanged.'}
            if declaration is not None else None,
        'compilerElapsedTimeProven': False, 'rootCensusProven': False, 'codeSizeProven': False,
        'runtimeSourceBindingProven': False, 'acceptedSavingsCredit': False,
        'scope': 'Historical observed all-tier standard-JFR duration/codeSize comparisons under original declared phases and source outcomes only. Full successful inlining roots are nonadditive across families; every ordinary/OSR/failed/replaced/unresolved/queued root and excluded pair remains in the authenticated census. Declared-phase eligibility does not prove exhaustive source phases, global loss-free lifetime, current runtime/allocation/RNG/work controls, independent confirmation or compiler benefit.'}


def main():
    manifest, expected, archive, output, scratch, *mapping_options = sys.argv[1:]
    if len(mapping_options) not in (0, 2):
        raise ValueError('A phase mapping requires its file and SHA-256')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    result = {'completed': False, 'acceptedSavingsCredit': False}
    try:
        with tempfile.TemporaryDirectory(prefix='census-costs-', dir=scratch) as temporary:
            selected, inventory, prefix = authenticate(Path(manifest), expected, Path(archive), Path(temporary))
            declaration = None
            if mapping_options:
                path, digest = mapping_options
                path = Path(path)
                if path.stat().st_size > cohort.MAX_MANIFEST_BYTES or \
                        not re.fullmatch('[0-9a-f]{64}', digest) or cohort.file_identity(path)['sha256'] != digest:
                    raise ValueError('Phase mapping differs from pinned identity or exceeds bounds')
                declaration = load_json(path)
            result = inspect(selected, inventory, prefix, declaration)
            result['phaseMappingIdentity'] = cohort.file_identity(Path(mapping_options[0])) if mapping_options else None
    except Exception as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        summary = {key: result.get(key) for key in ('completed', 'sourceSha256',
                   'observedComparisonSummaries', 'error', 'scope', 'acceptedSavingsCredit')}
        summary['phaseMappingIdentity'] = result.get('phaseMappingIdentity')
        mapped = result.get('phaseMapping')
        summary['mappedComparisonSummaries'] = mapped['observedComparisonSummaries'] if mapped is not None else None
        summary['familyObservationSummaries'] = observation_summaries(result.get('familyCaseObservations', []))
        summary['mappedFamilyObservationSummaries'] = observation_summaries(mapped['familyCaseObservations']) \
            if mapped is not None else None
        summary['recordingObservationSummaries'] = [{key: row.get(key) for key in
            ('case', 'arm', *CONTEXT_KEYS, 'reportIdentity', 'completed', 'error', 'eventCounts',
             'originalSourceStatus', 'sourceHashesMatch', 'originalExit', 'originalCaseCompleted', 'originalCohortComplete',
             'savedJvmContext', 'observedAllClassMetrics', 'observedTierMetrics')}
            for row in result.get('recordingObservations', [])]
        summary['unreferencedSavedExportPaths'] = result.get('unreferencedSavedExportPaths')
        summary['originalPairedStudyControls'] = result.get('originalPairedStudyControls')
        summary['observedMethodSummaries'] = result.get('observedMethodSummaries')
        summary['observationSummaryScope'] = 'Complete descriptive reported root quantities, including excluded contexts; '
        summary['observationSummaryScope'] += 'not paired eligibility, exhaustive source costs or current-source benefit.'
        raw = (json.dumps(summary, separators=(',', ':')) + '\n').encode()
        if len(raw) > cohort.MAX_INDEX_BYTES:
            raise ValueError('Complete observed comparison summary exceeds bounds')
        (output / 'compilation-cost-summary.json').write_bytes(raw)
        cohort.write_compressed_report(output / 'compilation-cost-comparison.json.gz', result,
                                       cohort.StorageBudget(output), MAX_JSON_BYTES)
        print(raw.decode(), end='')


if __name__ == '__main__':
    main()
