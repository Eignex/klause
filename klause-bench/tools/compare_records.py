#!/usr/bin/env python3
"""MiniZinc Challenge pairwise scores for saved solve records."""
import argparse
from fractions import Fraction
import json
import math
from pathlib import Path


def objective(record):
    value = record.get('exactObjective')
    if value is None:
        value = record.get('objective')
    try:
        return None if value is None else Fraction(str(value))
    except (ValueError, ZeroDivisionError):
        return None


def solved(record):
    return record.get('feasible') is not None


def optimal(record):
    return bool(record.get('proven')) or record.get('feasible') is False or (
        record.get('kind') == 'satisfy' and solved(record))


def time_used(record):
    if solved(record):
        if record.get('timeToBestMs') is not None:
            return record['timeToBestMs']
        try:
            value = float(record.get('stats', {}).get('solveTime', 'nan'))
            if math.isfinite(value) and value >= 0:
                return math.floor(value * 1000)
        except (TypeError, ValueError):
            pass
        if record.get('elapsedMs') is not None:
            return record['elapsedMs']
    return record.get('budgetMs') or 0


def compare(a, b, complete):
    av, bv = objective(a), objective(b)
    quality = 0 if av is None or bv is None else (av > bv) - (av < bv)
    quality *= 1 if a.get('maximize') else -1
    if solved(a) != solved(b):
        comparison = 1 if solved(a) else -1
    elif not solved(a):
        comparison = 0
    elif complete and optimal(a) != optimal(b):
        comparison = 1 if optimal(a) else -1
    else:
        comparison = quality
    at, bt = time_used(a), time_used(b)
    tie_a = bt / (at + bt) if complete and at + bt else 0.5
    pa = 0 if not solved(a) or comparison < 0 else 1 if comparison > 0 else tie_a
    pb = 0 if not solved(b) or comparison > 0 else 1 if comparison < 0 else 1 - tie_a
    unsound = (quality > 0 and b.get('proven')) or (
        a.get('feasible') is True and b.get('feasible') is False)
    return comparison, pa, pb, bool(unsound), at, bt


def display(record):
    if record.get('feasible') is False:
        return 'UNSAT'
    for key in ('exactObjective', 'objective', 'feasible'):
        if record.get(key) is not None:
            return str(record[key]).lower()
    return '-'


def read(directory):
    return [json.loads(path.read_text()) for path in sorted(directory.glob('*.json'))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--incomplete', action='store_true')
    mode.add_argument('--complete', action='store_true')
    parser.add_argument('a', type=Path)
    parser.add_argument('b', type=Path)
    args = parser.parse_args()
    complete = not args.incomplete
    b = {record['problem']: record for record in read(args.b)}
    rows = [(record, b[record['problem']], compare(record, b[record['problem']], complete))
            for record in read(args.a) if record['problem'] in b]
    name = 'complete' if complete else 'incomplete'
    print(f'=== MiniZinc Challenge {name} scoring:  {args.a.name}  vs  {args.b.name}   ({len(rows)} shared) ===')
    unsound = [a['problem'] for a, _, result in rows if result[3]]
    if unsound:
        print('  !! UNSOUND (A beats a proven optimum / SAT-vs-UNSAT): ' + ', '.join(unsound))
    for a, b, (_, pa, pb, _, at, bt) in rows:
        direction = ' (max)' if a.get('maximize') else ''
        print(f"  A{'+' if pa >= pb else ' '}{round(pa, 2)} [{a['problem']}]{direction} "
              f"A={display(a)}{'!' if optimal(a) else ''}@{at}ms  "
              f"B={display(b)}{'!' if optimal(b) else ''}@{bt}ms")
    sa, sb = (round(sum(row[2][index] for row in rows), 2) for index in (1, 2))
    wins = sum(row[2][0] > 0 for row in rows)
    losses = sum(row[2][0] < 0 for row in rows)
    ties = sum(row[2][0] == 0 and solved(row[0]) and solved(row[1]) for row in rows)
    unknown = sum(not solved(row[0]) for row in rows)
    missed = [a['problem'] for a, b, _ in rows if solved(b) and not solved(a)]
    print(f'\n  BORDA SCORE:  {args.a.name} = {sa}   {args.b.name} = {sb}   (of {len(rows)})')
    print(f'  A strict wins {wins}, strict losses {losses}, ties {ties}, A-unsolved {unknown}')
    print(f"  B-solved that A did not: {len(missed)}" + ('  -> ' + ', '.join(missed) if missed else ''))


if __name__ == '__main__':
    main()
