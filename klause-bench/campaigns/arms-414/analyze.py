#!/usr/bin/env python3
"""Offline analysis of archived case records; never executes a solver."""
import argparse
import csv
import hashlib
from collections import Counter, defaultdict
from decimal import Decimal
import gzip
import itertools
import json
from pathlib import Path
import random
import statistics

ROOT = Path(__file__).resolve().parent
ACCOUNTING = {'segments', 'work', 'ms', 'reward', 'failures', 'faults', 'maxMs', 'initMs', 'reseeds'}


def read(path):
    data = Path(path).read_bytes()
    return json.loads(gzip.decompress(data) if str(path).endswith('.gz') else data)


def outcome(case):
    rec = case.get('record')
    if rec is None:
        return case['status'].lower()
    stats = rec.get('stats', {})
    if 'error' in stats:
        return 'error'
    if 'unsupported' in stats:
        return 'unsupported'
    if stats.get('sourceValidation') == 'invalid':
        return 'invalid'
    if rec['proven']:
        return 'optimal' if rec['feasible'] is True else 'infeasible'
    return 'feasible' if rec['feasible'] is True else 'unknown'


def arm_fields(rec):
    return [(key[4:], dict(token.split('=', 1) for token in value.split() if '=' in token))
            for key, value in rec.get('stats', {}).items() if key.startswith('arm.')]


def objective(rec):
    if rec['feasible'] is not True:
        return None
    attr = rec.get('attribution', [])
    exact = [Decimal(x['exactObjective']) for x in attr
             if x.get('exactObjective') is not None and x.get('continuousObjective') is None]
    if exact and len(exact) == len(attr):
        return (max if rec['maximize'] else min)(exact)
    return Decimal(str(rec['objective'])) if rec.get('objective') is not None else None


def quality(a, b):
    # Positive means b improves on a. Proof is kept out of this objective comparison.
    if a['feasible'] is not True or b['feasible'] is not True:
        return int(b['feasible'] is True) - int(a['feasible'] is True)
    x, y = objective(a), objective(b)
    if x is None or y is None:
        return None
    return ((y > x) - (y < x)) * (1 if a['maximize'] else -1)


def contradictions(a, b):
    if (a['proven'] and a['feasible'] is False and b['feasible'] is True or
            b['proven'] and b['feasible'] is False and a['feasible'] is True):
        return True
    q = quality(a, b)
    return q is not None and (a['proven'] and q > 0 or b['proven'] and q < 0)


def interval(values):
    if not values:
        return None
    rng = random.Random(414)
    samples = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(2000))
    return [samples[49], samples[1949]]


def time(rec, field):
    value = rec.get(field)
    return rec['budgetMs'] if value is None else value


def repeat_ranges(rows):
    groups = defaultdict(list)
    for case in rows:
        if case.get('record'):
            groups[(case['problem']['suite'], case['problem']['problem'], case.get('seed'))].append(case['record'])
    result = []
    for (suite, problem, seed), records in groups.items():
        if len(records) < 2:
            continue
        ranges = {}
        for field in ['timeToFirstFeasibleMs', 'timeToBestMs', 'elapsedMs']:
            values = [rec[field] for rec in records if rec.get(field) is not None]
            ranges[field] = dict(observed=len(values), minimum=min(values), median=statistics.median(values),
                                 maximum=max(values)) if values else dict(observed=0)
        result.append(dict(input=suite + '/' + problem, seed=seed, repeats=len(records), ranges=ranges,
                           objectives=[str(objective(rec)) for rec in records]))
    return result


def analyze(job):
    folder = ROOT / 'evidence' / str(job)
    cases = read(folder / 'cases.json.gz')
    raw = read(folder / 'raw-records.json.gz')
    for case in cases:
        if str(case['index']) in raw:
            case['record'] = raw[str(case['index'])]
    jobdata = read(folder / 'job.json.gz')
    expected = [x['arm']['label'] for x in read(folder / 'arms.json.gz')]
    groups = defaultdict(list)
    for c in sorted(cases, key=lambda x: x['index']):
        p = c['problem']
        groups[(c['arm'], p['suite'], p['problem'], c.get('seed'))].append(c)
    indexed = {}
    for (arm, suite, problem, seed), rows in groups.items():
        for repeat, c in enumerate(rows):
            indexed[(arm, suite, problem, seed, repeat)] = c
    result = dict(job=job, status=jobdata['status'], cases=len(cases), arms={}, pairs=[])
    for arm in expected:
        rows = [c for c in cases if c['arm'] == arm]
        counts = Counter(outcome(c) for c in rows)
        records = [c['record'] for c in rows if c.get('record')]
        telemetry = defaultdict(lambda: dict(cases=0, work=0, ms=0, initMs=0, segments=0,
                                             reseeds=0, failures=0, faults=0, weightedReward=0.0,
                                             finalHolders=0, credit=defaultdict(float)))
        for rec in records:
            seen_labels = set()
            for label, fields in arm_fields(rec):
                # Replica positions are distinct in evidence and fold only for contribution ranking.
                label = label.rsplit('#', 1)[0]
                t = telemetry[label]
                t['cases'] += int(label not in seen_labels)
                seen_labels.add(label)
                for key in ['work', 'ms', 'initMs', 'segments', 'reseeds', 'failures', 'faults']:
                    t[key] += int(fields.get(key, 0))
                t['weightedReward'] += int(fields.get('work', 0)) * float(fields.get('reward', 0))
                for key, value in fields.items():
                    if key not in ACCOUNTING and not key.startswith('share'):
                        t['credit'][key] += float(value)
            value = objective(rec)
            if value is not None:
                holders = [x for x in rec.get('attribution', [])
                           if x.get('exactObjective') is not None and Decimal(x['exactObjective']) == value]
                if holders:
                    telemetry[holders[-1]['label'].rsplit('#', 1)[0]]['finalHolders'] += 1
        for totals in telemetry.values():
            totals['meanReward'] = totals['weightedReward'] / totals['work'] if totals['work'] else None
        result['arms'][arm] = dict(outcomes=dict(counts), fingerprints=sorted({r.get('buildFingerprint') or 'missing' for r in records}),
            commits=sorted({r.get('gitSha') or 'missing' for r in records}),
            missingWitnesses=sum(r['feasible'] is True and not r.get('finalWitness') for r in records),
            missingSourceHashes=sum(not r.get('sourceHashes') for r in records),
            runtimeHashes=sorted({hashlib.sha256(json.dumps(r.get('buildProvenance', {}).get('runtime', {}), sort_keys=True).encode()).hexdigest() for r in records}),
            thresholds=dict(Counter(r['stats'].get('portfolioReseedStaleThreshold', 'absent') for r in records)),
            runtimeOptions=[json.loads(x) for x in sorted({json.dumps(r.get('buildProvenance', {}).get('runtimeOptions', {}), sort_keys=True) for r in records})],
            firstMs=statistics.median([time(r, 'timeToFirstFeasibleMs') for r in records]) if records else None,
            bestMs=statistics.median([time(r, 'timeToBestMs') for r in records]) if records else None,
            repeatRanges=repeat_ranges(rows),
            telemetry=dict(telemetry))
    keys = sorted({key[1:] for key in indexed})
    for a, b in itertools.combinations(expected, 2):
        matched = []
        excluded = []
        for key in keys:
            ca, cb = indexed.get((a,) + key), indexed.get((b,) + key)
            if (ca is None or cb is None or ca.get('record') is None or cb.get('record') is None or
                    outcome(ca) in ['error', 'unsupported', 'invalid'] or
                    outcome(cb) in ['error', 'unsupported', 'invalid']):
                excluded.append(list(key))
                continue
            ra, rb = ca['record'], cb['record']
            if ra.get('sourceHashes') and rb.get('sourceHashes') and ra['sourceHashes'] != rb['sourceHashes']:
                excluded.append(list(key) + ['different source bytes'])
                continue
            matched.append(dict(input='/'.join(key[:2]), suite=key[0], seed=key[2], repeat=key[3],
                quality=quality(ra, rb), proofDelta=int(rb['proven'])-int(ra['proven']),
                feasibleDelta=int(rb['feasible'] is True)-int(ra['feasible'] is True),
                firstDeltaMs=time(rb, 'timeToFirstFeasibleMs')-time(ra, 'timeToFirstFeasibleMs'),
                bestDeltaMs=time(rb, 'timeToBestMs')-time(ra, 'timeToBestMs'),
                aObjective=str(objective(ra)), bObjective=str(objective(rb)),
                aProven=ra['proven'], bProven=rb['proven'], disagreement=contradictions(ra, rb)))
        by_problem = defaultdict(list)
        for m in matched:
            if m['quality'] is not None:
                by_problem[m['input']].append(m['quality'])
        means = [statistics.mean(x) for x in by_problem.values()]
        result['pairs'].append(dict(a=a, b=b, matched=len(matched), excluded=excluded,
            qualityWins=sum(x['quality'] == 1 for x in matched), qualityLosses=sum(x['quality'] == -1 for x in matched),
            proofGains=sum(x['proofDelta'] == 1 for x in matched), proofLosses=sum(x['proofDelta'] == -1 for x in matched),
            disagreementCases=[x for x in matched if x['disagreement']],
            problemMeanQuality=statistics.mean(means) if means else None,
            problemBootstrap95=interval(means), cells=matched))
    with (folder / 'cases.csv').open('w', newline='') as stream:
        columns = ['index', 'input', 'family', 'arm', 'seed', 'outcome', 'objective', 'firstMs', 'bestMs', 'elapsedMs',
                   'proven', 'workers', 'work', 'reseeds', 'armFailures', 'armFaults', 'initMs', 'sourceHashes', 'buildFingerprint']
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        for case in cases:
            rec = case.get('record') or {}
            fields = arm_fields(rec)
            writer.writerow(dict(index=case['index'], input=case['problem']['suite'] + '/' + case['problem']['problem'],
                family=case['problem'].get('family'), arm=case['arm'], seed=case.get('seed'), outcome=outcome(case),
                objective=str(objective(rec)) if rec else None, firstMs=rec.get('timeToFirstFeasibleMs'),
                bestMs=rec.get('timeToBestMs'), elapsedMs=rec.get('elapsedMs'), proven=rec.get('proven'), workers=len(fields),
                work=sum(int(f.get('work', 0)) for _, f in fields), reseeds=sum(int(f.get('reseeds', 0)) for _, f in fields),
                armFailures=sum(int(f.get('failures', 0)) for _, f in fields),
                armFaults=sum(int(f.get('faults', 0)) for _, f in fields),
                initMs=sum(int(f.get('initMs', 0)) for _, f in fields), sourceHashes=json.dumps(rec.get('sourceHashes', {})),
                buildFingerprint=rec.get('buildFingerprint')))
    (folder / 'analysis.json.gz').write_bytes(gzip.compress(json.dumps(result).encode(), mtime=0))
    summary = dict(result, pairs=[{k: v for k, v in pair.items() if k != 'cells'} for pair in result['pairs']])
    (folder / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    for arm, summary in result['arms'].items():
        print(job, arm, summary['outcomes'], 'reseeds', sum(t['reseeds'] for t in summary['telemetry'].values()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jobs', type=int, nargs='+')
    for job in parser.parse_args().jobs:
        analyze(job)
