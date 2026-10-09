#!/usr/bin/env python3
"""Summarize archived lab cases, retaining measurements and checking dense-model witnesses."""
import argparse
import collections
import json
import re
import statistics
from pathlib import Path

COUNTERS = ('lpComponentSplits', 'lpComponentBlocksMax', 'lpNodePasses', 'lpStandalonePasses',
            'lpComponentPasses', 'lpRootPasses', 'lpPivots', 'lpWorkOps', 'lpOverheadOps', 'nodes',
            'lpStandaloneWorkOps', 'lpComponentWorkOps', 'lpRootWorkOps')


def witness(problem, text):
    shape = re.search(r'(?:dense|linked)-(\d+)x(\d+)', problem)
    if not shape:
        return None
    blocks, width = map(int, shape.groups())
    if problem.startswith('mps-'):
        pairs = re.findall(r'(X\d+_\d+)=([^\s]+)', text)
        values = {name: float(value) for name, value in pairs}
        expected = {f'X{b}_{c}' for b in range(blocks) for c in range(width)}
        if values.keys() != expected:
            raise ValueError(f'{problem}: incomplete MPS witness')
        flat = [values[f'X{b}_{c}'] for b in range(blocks) for c in range(width)]
    else:
        matches = list(re.finditer(r'^(?:x|X_INTRODUCED_\d+_) = .*?\[([^\]]+)\]', text, re.M))
        if not matches:
            raise ValueError(f'{problem}: missing MiniZinc witness')
        flat = [float(value.strip()) for value in matches[-1].group(1).split(',')]
        if len(flat) != blocks * width:
            raise ValueError(f'{problem}: incomplete MiniZinc witness')
        ks = list(re.finditer(r'^k = (\d+);', text, re.M))
        if not ks or int(ks[-1].group(1)) not in (0, 1) or int(ks[-1].group(1)) + flat[0] < 1 - 1e-6:
            raise ValueError(f'{problem}: invalid finite decision')
    if any(not 0 <= value <= 2 for value in flat):
        raise ValueError(f'{problem}: out-of-bounds witness')
    residual = 0.0
    for b in range(blocks):
        part = flat[b * width:(b + 1) * width]
        for value in part:
            residual = max(residual, abs(width * value + sum(part) - 2 * width))
    if problem.startswith('mzn-linked'):
        residual = max(residual, abs(sum(flat[b * width] for b in range(blocks)) - blocks))
    if residual > 1e-6:
        raise ValueError(f'{problem}: source residual {residual}')
    return residual


def analyze(cases, raw_dir):
    grouped = collections.defaultdict(list)
    for case in cases:
        record = case.get('record')
        if case['status'] != 'DONE' or record is None:
            raise ValueError(f"case {case['index']} is incomplete")
        if record.get('command') in ('ERROR', 'LOAD'):
            raise ValueError(f"case {case['index']} failed: {record.get('stats')}")
        grouped[case['problem']['suite'] + '/' + case['problem']['problem'], case['arm']].append(case)
    rows = []
    for (problem, arm), group in sorted(grouped.items()):
        records = [case['record'] for case in group]
        if any(record.get('elapsedMs') is None for record in records):
            raise ValueError(f'{problem}: missing subprocess elapsed time')
        residuals = []
        for case in group:
            if case['record']['feasible'] is True and case['problem']['suite'] == 'lp-component-split':
                raw = raw_dir / f"{case['index']}.out"
                residuals.append(witness(case['problem']['problem'], raw.read_text()))
        row = dict(problem=problem, arm=arm, runs=len(records),
                   decided=sum(record['feasible'] is not None for record in records),
                   proven=sum(record['proven'] for record in records),
                   objectives=[record['objective'] for record in records],
                   elapsedMs=[record['elapsedMs'] for record in records],
                   medianElapsedMs=statistics.median(record['elapsedMs'] for record in records),
                   solveMs=[float(record['stats']['solveTime']) * 1000 for record in records],
                   timeToBestMs=[record['timeToBestMs'] for record in records],
                   witnessMaxResidual=max(residuals, default=None))
        row['counters'] = {key: [int(float(record['stats'].get(key, '0'))) for record in records]
                           for key in COUNTERS}
        rows.append(row)
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('cases', type=Path)
    parser.add_argument('raw_dir', type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(json.loads(args.cases.read_text()), args.raw_dir), indent=2))
