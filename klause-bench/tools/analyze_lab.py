#!/usr/bin/env python3
"""Analyze archived AWS records, retaining incomplete blocks and outcome categories."""
import argparse
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
import gzip
import json
import math
from pathlib import Path
import random
import re
import statistics


def read(path):
    raw = Path(path).read_bytes()
    if str(path).endswith('.gz'):
        raw = gzip.decompress(raw)
    return json.loads(raw)


def category(case):
    r = case.get('record')
    if r is None:
        return 'missing-' + case['status'].lower()
    s = r.get('stats', {})
    if s.get('unsupported'):
        return 'unsupported'
    if s.get('loadError') or s.get('error') or case['status'] == 'FAILED':
        return 'error'
    if r.get('feasible') is True:
        return 'proved-witness' if r.get('proven') else 'witness'
    if r.get('feasible') is False and r.get('proven'):
        return 'refuted'
    return 'unknown'


def timing(r):
    if r.get('feasible') is not True and not r.get('proven'):
        return 2 * r['budgetMs']
    if r.get('feasible') is True and r.get('timeToBestMs') is not None:
        return max(1, r['timeToBestMs'])
    s = r.get('stats', {})
    try:
        t = float(s.get('solveTime', 'nan')) * 1000
        if math.isfinite(t) and t >= 0:
            return max(1, t)
    except (ValueError, TypeError):
        pass
    return max(1, r.get('elapsedMs') or r['budgetMs'])


def quality(a, b):
    """Positive means b improves the outcome; time is deliberately excluded."""
    aw, bw = a.get('feasible') is True, b.get('feasible') is True
    ad = aw or (a.get('feasible') is False and a.get('proven'))
    bd = bw or (b.get('feasible') is False and b.get('proven'))
    if ad != bd:
        return 1 if bd else -1
    if not ad:
        return 0
    if aw != bw:
        return None  # solution/refutation contradiction
    if aw and a.get('kind') == b.get('kind') == 'satisfy':
        return 0
    ap, bp = bool(a.get('proven')), bool(b.get('proven'))
    av, bv = a.get('objective'), b.get('objective')
    if av is not None and bv is not None and av != bv:
        direction = (1 if bv > av else -1) * (1 if b.get('maximize') else -1)
        if (ap and direction > 0) or (bp and direction < 0):
            return None
        return direction
    return (1 if bp else -1) if ap != bp else 0


def numeric(r, key):
    try:
        value = float(r.get('stats', {}).get(key, 'nan'))
        return value if math.isfinite(value) else None
    except (ValueError, TypeError):
        return None


def objective_support(r):
    if r.get('feasible') is not True or r.get('objective') is None:
        return 'not-applicable'
    witness = r.get('finalWitness') or r.get('sourceWitness')
    if witness is None:
        return 'missing-witness'
    reported = re.search(r'(?m)^\s*_objective\s*=\s*([^;]+);', witness)
    if reported is None:
        return 'missing-objective'
    try:
        return 'matched' if Decimal(reported.group(1).strip()) == Decimal(str(r['objective'])) else 'mismatch'
    except InvalidOperation:
        return 'unparsed-objective'


def process_timing(r):
    if r.get('feasible') is not True and not r.get('proven'):
        return 2 * r['budgetMs']
    value = r.get('processTimeToBestMs') if r.get('feasible') is True else r.get('elapsedMs')
    return max(1, value) if value is not None else None


def preparation_timing(r):
    if r.get('feasible') is not True and not r.get('proven'):
        return 2 * r['budgetMs']
    preparation = numeric(r, 'presolvePreparationMs')
    if preparation is None or not r.get('attribution'):
        return None
    return timing(r) + preparation


def ratio_interval(families, metric):
    values = [statistics.mean(x[metric]) for x in families.values() if x[metric]]
    ci = interval(values)
    if ci:
        for key in ['value', 'low', 'high']:
            if ci[key] is not None:
                ci[key] = math.exp(ci[key])
    return ci


def interval(values, seed=554):
    if not values:
        return None
    if len(values) < 2:
        return {'value': statistics.mean(values), 'low': None, 'high': None, 'clusters': len(values)}
    rng = random.Random(seed)
    means = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(2000))
    return {'value': statistics.mean(values), 'low': means[50], 'high': means[1949], 'clusters': len(values)}


def work_signature(r):
    s = r.get('stats', {})
    keys = ['presolveWork', 'presolveRoundEntries', 'presolveProbeCalls', 'presolveConstraintsRemoved', 'presolvePasses']
    return {k: s[k] for k in keys + sorted(k for k in s if k.startswith('presolveCalls_')) if k in s}


def analyze(cases, control):
    labels = sorted({c['arm'] for c in cases})
    occurrences = Counter()
    blocks = defaultdict(dict)
    summaries = {}
    for c in sorted(cases, key=lambda c: c['index']):
        identity = (c['problem']['suite'], c['problem']['problem'], c.get('seed'))
        key = identity + (occurrences[(identity, c['arm'])],)
        occurrences[(identity, c['arm'])] += 1
        blocks[key][c['arm']] = c
    complete = {k: v for k, v in blocks.items() if set(v) == set(labels) and
                all(c.get('record') is not None and c['status'] == 'DONE' for c in v.values())}
    incomplete = [{'identity': k, 'status': {l: category(v[l]) if l in v else 'absent' for l in labels}}
                  for k, v in blocks.items() if k not in complete]
    for label in labels:
        arm = [c for c in cases if c['arm'] == label]
        recorded = [c['record'] for c in arm if c.get('record')]
        metrics = {}
        for key in ['presolvePreparationMs', 'presolveWork', 'presolveWorkAllowance', 'presolveRoundEntries',
                    'presolveProbeCalls', 'presolveMaxRounds', 'presolveAbortFraction',
                    'presolveProbeBudgetPerVar', 'presolveProbeTotalBudget']:
            values = [v for r in recorded if (v := numeric(r, key)) is not None]
            metrics[key] = {'n': len(values), 'median': statistics.median(values) if values else None,
                            'max': max(values) if values else None}
        for key in ['timeToBestMs', 'timeToFirstFeasibleMs', 'processTimeToBestMs',
                    'processTimeToFirstFeasibleMs']:
            values = [r[key] for r in recorded if r.get(key) is not None]
            metrics[key] = {'n': len(values), 'median': statistics.median(values) if values else None,
                            'max': max(values) if values else None}
        summaries[label] = {'planned': len(arm), 'outcomes': dict(Counter(category(c) for c in arm)),
                            'metrics': metrics,
                            'elapsedMs': {'n': sum(r.get('elapsedMs') is not None for r in recorded),
                                'median': statistics.median([r['elapsedMs'] for r in recorded if r.get('elapsedMs') is not None])
                                    if any(r.get('elapsedMs') is not None for r in recorded) else None,
                                'max': max((r['elapsedMs'] for r in recorded if r.get('elapsedMs') is not None), default=None)},
                            'poolCardinality': dict(Counter(sum(k.startswith('arm.') for k in r.get('stats', {}))
                                                          for r in recorded if r.get('command') != 'LOAD')),
                            'fingerprints': dict(Counter(r.get('buildFingerprint', 'missing') for r in recorded)),
                            'validationPolicies': dict(Counter(r.get('validationPolicy', 'missing') for r in recorded)),
                            'sourceValidation': dict(Counter(r.get('stats', {}).get('sourceValidation', 'absent')
                                                              for r in recorded)),
                            'objectiveSupport': dict(Counter(objective_support(r) for r in recorded)),
                            'retainedWitnesses': sum(r.get('finalWitness') is not None or
                                r.get('sourceWitness') is not None for r in recorded),
                            'retainedOutputHashes': sum(r.get('sourceOutputSha256') is not None for r in recorded)}
    pairs = {}
    for label in labels:
        if label == control:
            continue
        by_family = defaultdict(lambda: defaultdict(list))
        differences = []
        same_work = changed_work = unmetered = 0
        skipped = []
        identity_unverified = checked_witness_pairs = 0
        timing_pairs = Counter()
        for key, block in complete.items():
            a, b = block[control]['record'], block[label]['record']
            if any(category(block[arm]) in ('error', 'unsupported') for arm in (control, label)):
                skipped.append({'identity': key, 'reason': 'error or unsupported'})
                continue
            if any(objective_support(r) == 'mismatch' for r in (a, b)):
                skipped.append({'identity': key, 'reason': 'retained witness disagrees with reported objective'})
                continue
            ah, bh = a.get('sourceHashes'), b.get('sourceHashes')
            if ah and bh and ah != bh:
                skipped.append({'identity': key, 'reason': 'different source hashes'})
                continue
            fam = block[label]['problem']['family'].split('/')[-1]
            q = quality(a, b)
            if q is None:
                skipped.append({'identity': key, 'reason': 'contradictory reported outcomes'})
                continue
            identity_unverified += not ah or not bh
            checked_witness_pairs += all(r.get('feasible') is True and
                r.get('stats', {}).get('sourceValidation') == 'valid' for r in (a, b))
            ratio = math.log(timing(b) / timing(a))
            by_family[fam]['log_time_ratio'].append(ratio)
            for metric, measure in [('processPar2', process_timing),
                                    ('preparationAdjustedPar2', preparation_timing),
                                    ('subprocessDuration', lambda r: r.get('elapsedMs')),
                                    ('preparationDuration', lambda r: numeric(r, 'presolvePreparationMs'))]:
                av, bv = measure(a), measure(b)
                if av is not None and bv is not None:
                    by_family[fam][metric].append(math.log(max(1, bv) / max(1, av)))
                    timing_pairs[metric] += 1
            equal_objective = (a.get('feasible') is not True or a.get('kind') == 'satisfy' or
                               (a.get('objective') is not None and a.get('objective') == b.get('objective')))
            if q == 0 and a.get('kind') == b.get('kind') and equal_objective:
                av, bv = process_timing(a), process_timing(b)
                if av is not None and bv is not None:
                    by_family[fam]['equalOutcomeProcessPar2'].append(math.log(bv / av))
                    timing_pairs['equalOutcomeProcessPar2'] += 1
            if q is not None:
                by_family[fam]['quality'].append(q)
            by_family[fam]['controlOutcomes'].append(category(block[control]))
            by_family[fam]['candidateOutcomes'].append(category(block[label]))
            for metric, record in [('controlPreparationMs', a), ('candidatePreparationMs', b)]:
                value = numeric(record, 'presolvePreparationMs')
                if value is not None:
                    by_family[fam][metric].append(value)
            sa, sb = work_signature(a), work_signature(b)
            if 'presolveWork' not in sa or 'presolveWork' not in sb:
                unmetered += 1
            elif sa == sb:
                same_work += 1
            else:
                changed_work += 1
            if q != 0 or sa != sb:
                differences.append({'identity': key, 'family': fam, 'quality': q,
                                    'controlObjective': a.get('objective'), 'candidateObjective': b.get('objective'),
                                    'controlCategory': category(block[control]),
                                    'candidateCategory': category(block[label]),
                                    'controlBestMs': a.get('timeToBestMs'), 'candidateBestMs': b.get('timeToBestMs'),
                                    'controlFirstMs': a.get('timeToFirstFeasibleMs'),
                                    'candidateFirstMs': b.get('timeToFirstFeasibleMs'),
                                    'controlWork': sa, 'candidateWork': sb})
        ci = ratio_interval(by_family, 'log_time_ratio')
        pairs[label] = {'completeBlocks': len(complete), 'eligiblePairs': len(complete) - len(skipped),
                        'skippedPairs': skipped, 'inputIdentityUnverified': identity_unverified,
                        'independentlyCheckedWitnessPairs': checked_witness_pairs,
                        'sameWork': same_work, 'changedWork': changed_work,
                        'unmetered': unmetered, 'geomeanPar2RatioFamilyBootstrap': ci,
                        'timingPairs': dict(timing_pairs),
                        'processPar2RatioFamilyBootstrap': ratio_interval(by_family, 'processPar2'),
                        'equalOutcomeProcessPar2RatioFamilyBootstrap': ratio_interval(by_family, 'equalOutcomeProcessPar2'),
                        'preparationAdjustedPar2RatioFamilyBootstrap': ratio_interval(by_family, 'preparationAdjustedPar2'),
                        'subprocessDurationRatioFamilyBootstrap': ratio_interval(by_family, 'subprocessDuration'),
                        'preparationDurationRatioFamilyBootstrap': ratio_interval(by_family, 'preparationDuration'),
                        'qualityFamilyBootstrap': interval([statistics.mean(x['quality']) for x in by_family.values()
                                                            if x['quality']]),
                        'familyOutcomes': {f: dict(Counter(x['quality'])) for f, x in by_family.items()},
                        'familyComparisons': {f: {
                            'eligiblePairs': len(x['quality']),
                            'qualityCounts': dict(Counter(x['quality'])),
                            'meanQuality': statistics.mean(x['quality']),
                            'controlOutcomes': dict(Counter(x['controlOutcomes'])),
                            'candidateOutcomes': dict(Counter(x['candidateOutcomes'])),
                            'processPar2Ratio': math.exp(statistics.mean(x['processPar2'])) if x['processPar2'] else None,
                            'equalOutcomeProcessPar2Ratio': math.exp(statistics.mean(x['equalOutcomeProcessPar2']))
                                if x['equalOutcomeProcessPar2'] else None,
                            'equalOutcomeTimingPairs': len(x['equalOutcomeProcessPar2']),
                            'medianControlPreparationMs': statistics.median(x['controlPreparationMs'])
                                if x['controlPreparationMs'] else None,
                            'medianCandidatePreparationMs': statistics.median(x['candidatePreparationMs'])
                                if x['candidatePreparationMs'] else None,
                        } for f, x in by_family.items()},
                        'differences': differences}
    return {'timingPolicy': {
                'reportedPar2': 'Legacy search attribution time; separators when attribution is absent. Not an end-to-end measure.',
                'processPar2': 'Subprocess incumbent arrival including launch, load, preparation and search; refutations use process duration. Unavailable legacy witness timings are excluded.',
                'equalOutcomeProcessPar2': 'Descriptive subset with tied reported quality and objective. Coverage is disclosed; this outcome-conditioned subset is not a causal estimate.',
                'preparationAdjustedPar2': 'Search attribution plus measured preparation; excludes launch, frontend loading and routing.',
                'subprocessDuration': 'Total subprocess duration regardless of result, before source checking; not time to best.',
                'unknownPenalty': 'PAR2 assigns twice the nominal budget to undecided cases; duration summaries disclose overshoot.'},
            'control': control, 'completeBlocks': len(complete), 'incompleteBlocks': incomplete,
            'arms': summaries, 'pairs': pairs}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('cases')
    parser.add_argument('--control', default='default')
    args = parser.parse_args()
    print(json.dumps(analyze(read(args.cases), args.control), indent=2, allow_nan=False))
