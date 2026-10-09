"""Validate a frozen klause-lab run and summarize paired solver measurements."""
import argparse
import collections
import csv
import hashlib
import json
import math
import random
from pathlib import Path
import statistics

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('cases', type=Path)
parser.add_argument('manifest', type=Path)
parser.add_argument('arms', type=Path)
parser.add_argument('--baseline', required=True)
parser.add_argument('--repeats', type=int, default=3)
parser.add_argument('--decision-limit', type=int, default=50000)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
rows = json.loads(args.cases.read_text())
manifest = json.loads(args.manifest.read_text())
arm_metadata = json.loads(args.arms.read_text())
expected = {(r['suite'], r['problem']): r for r in manifest}
pinned = {r['arm']['label']: r['sha'] for r in arm_metadata}
assert args.baseline in pinned, 'baseline arm absent'
expected_keys = {(a, s, p, n) for a in pinned for s, p in expected for n in range(args.repeats)}
actual_keys = [(r['arm'], r['problem']['suite'], r['problem']['problem'], r.get('repeat', 0)) for r in rows]
assert len(set(actual_keys)) == len(actual_keys), 'duplicate case keys'
assert set(actual_keys) == expected_keys, f'corpus mismatch: {len(set(actual_keys)-expected_keys)} extra cases, {len(expected_keys-set(actual_keys))} missing cases'
by_arm = collections.defaultdict(dict)
flat = []
metrics = ['openBoolDecisions', 'openIntDecisions', 'openTheoryDecisions', 'openTheoryChecks',
           'openWork', 'openLearned', 'openRelearned', 'openRestarts', 'openReductions',
           'openDropped', 'openRetained', 'openPeakRetained', 'openLearnedWatchVisits',
           'openAssertingConflicts', 'openNonAssertingConflicts', 'openClauseActivityBumps',
           'openClauseLbdImprovements', 'openReductionNs']
for row in rows:
    assert row['status'] == 'DONE' and row.get('record'), f'unfinished case {row["index"]}'
    record = row['record']
    arm = row['arm']
    assert record['gitSha'] == pinned[arm], f'wrong revision for {arm}'
    assert record.get('buildFingerprint') and record.get('buildProvenance'), 'missing binary provenance'
    stats = record['stats']
    assert 'solveTime' in stats, f'missing solve statistics: {row["index"]}'
    suite, problem = row['problem']['suite'], row['problem']['problem']
    reference = expected[suite, problem].get('reference')
    if reference is not None and record['feasible'] is not None:
        assert record['feasible'] == reference, f'reference disagreement: {problem}'
    entry = dict(arm=arm, suite=suite, problem=problem, repeat=row.get('repeat', 0),
                 sha=record['gitSha'], fingerprint=record['buildFingerprint'],
                 feasible=record['feasible'], proven=record['proven'], objective=record.get('objective'),
                 solveSeconds=float(stats['solveTime']), elapsedMs=record.get('elapsedMs'),
                 budgetMs=record['budgetMs'])
    for metric in metrics:
        entry[metric] = int(stats[metric]) if metric in stats else None
    entry['decisions'] = sum(entry[k] or 0 for k in metrics[:3])
    entry['equalWorkEligible'] = record['feasible'] is not None or entry['decisions'] == args.decision_limit
    by_arm[arm][suite, problem, entry['repeat']] = entry
    flat.append(entry)

def geometric_mean(values):
    return math.exp(statistics.mean(math.log(v) for v in values)) if values else None

def ratio_estimate(ratios):
    values = [math.log(statistics.mean(v)) for v in ratios.values()]
    if not values:
        return None
    rng = random.Random(1631)
    samples = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(5000))
    return {'value': math.exp(statistics.mean(values)),
            'low': math.exp(samples[124]), 'high': math.exp(samples[4874]),
            'problems': len(values), 'bootstrapSamples': 5000}

report = {'rawCasesSha256': hashlib.sha256(args.cases.read_bytes()).hexdigest(),
          'problems': len(expected), 'repeats': args.repeats, 'arms': {}, 'paired': {}}
for arm, entries in by_arm.items():
    entries = list(entries.values())
    report['arms'][arm] = {'sha': pinned[arm], 'records': len(entries),
        'sat': sum(e['feasible'] is True for e in entries),
        'unsat': sum(e['feasible'] is False and e['proven'] for e in entries),
        'unknown': sum(e['feasible'] is None for e in entries),
        'solveSeconds': sum(e['solveSeconds'] for e in entries),
        'elapsedMs': sum(e['elapsedMs'] for e in entries) if all(e['elapsedMs'] is not None for e in entries) else None,
        'fingerprints': sorted({e['fingerprint'] for e in entries}),
        'totals': {k: sum(e[k] for e in entries) if all(e[k] is not None for e in entries) else None for k in metrics},
        'partiallyObserved': {k: {'records': sum(e[k] is not None for e in entries),
                                  'sum': sum(e[k] for e in entries if e[k] is not None)}
                              for k in metrics if any(e[k] is None for e in entries)}}
base = by_arm[args.baseline]
for arm, entries in by_arm.items():
    if arm == args.baseline:
        continue
    ratios = collections.defaultdict(list)
    completed_ratios = collections.defaultdict(list)
    elapsed_ratios = collections.defaultdict(list)
    reduction_ratios = collections.defaultdict(list)
    equal_work = logical_matches = 0
    differences, lost, gained = [], [], []
    for key, e in entries.items():
        b = base[key]
        verdict = lambda x: (x['feasible'], x['proven'], x['objective'])
        if verdict(e) != verdict(b): differences.append(key)
        if b['feasible'] is not None and e['feasible'] is None: lost.append(key)
        if e['feasible'] is not None and b['feasible'] is None: gained.append(key)
        if e['solveSeconds'] > 0 and b['solveSeconds'] > 0:
            ratios[key[:2]].append(e['solveSeconds'] / b['solveSeconds'])
            if e['feasible'] is not None and verdict(e) == verdict(b):
                completed_ratios[key[:2]].append(e['solveSeconds'] / b['solveSeconds'])
        if e['elapsedMs'] and b['elapsedMs']:
            elapsed_ratios[key[:2]].append(e['elapsedMs'] / b['elapsedMs'])
        if e['openReductionNs'] and b['openReductionNs']:
            reduction_ratios[key[:2]].append(e['openReductionNs'] / b['openReductionNs'])
        if b['equalWorkEligible'] and e['equalWorkEligible']:
            equal_work += 1
            comparable = [k for k in metrics if b[k] is not None and e[k] is not None and k != 'openReductionNs']
            logical_matches += all(b[k] == e[k] for k in comparable)
    report['paired'][arm] = {'pairs': len(entries), 'verdictDifferences': differences,
        'lostCompletions': lost, 'gainedCompletions': gained, 'equalWorkPairs': equal_work,
        'logicalMatches': logical_matches,
        'perProblemMeanSolveRatio': geometric_mean([statistics.mean(v) for v in ratios.values()]),
        'completedPerProblemMeanSolveRatio': geometric_mean([statistics.mean(v) for v in completed_ratios.values()]),
        'perProblemMeanElapsedRatio': geometric_mean([statistics.mean(v) for v in elapsed_ratios.values()]),
        'elapsedProblems': len(elapsed_ratios),
        'perProblemMeanReductionRatio': geometric_mean([statistics.mean(v) for v in reduction_ratios.values()]),
        'reductionProblems': len(reduction_ratios),
        'solveRatioEstimate': ratio_estimate(ratios),
        'completedSolveRatioEstimate': ratio_estimate(completed_ratios),
        'elapsedRatioEstimate': ratio_estimate(elapsed_ratios),
        'reductionRatioEstimate': ratio_estimate(reduction_ratios),
        'completedProblems': len(completed_ratios)}
args.output.with_suffix('.json').write_text(json.dumps(report, indent=2)+'\n')
with args.output.with_suffix('.csv').open('w') as file:
    writer = csv.DictWriter(file, fieldnames=list(flat[0]))
    writer.writeheader()
    writer.writerows(sorted(flat, key=lambda r: (r['suite'], r['problem'], r['repeat'], r['arm'])))
print(json.dumps(report, indent=2))
