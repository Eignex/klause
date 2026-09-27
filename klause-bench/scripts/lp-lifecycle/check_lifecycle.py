#!/usr/bin/env python3
"""Check every original CP state and summarize paired lifecycle observations."""

import json
import statistics
import sys
from collections import defaultdict
from fractions import Fraction
from pathlib import Path

CAPTURE = "deb7c8bb9b3c744e71b31893b7abd1c1f1b8511d8edeecda8f965566e6d4c648"
PHASES = ("setup", "transition", "assembly", "solveNode", "certification", "disposal")


def optimum(bounds):
    lx, ux, ly, uy = bounds
    vertices = [(x, y) for x in (lx, ux) for y in (ly, uy)]
    vertices += [(x, 1 - x) for x in (lx, ux)]
    vertices += [(1 - y, y) for y in (ly, uy)]
    feasible = [(x, y) for x, y in vertices if lx <= x <= ux and ly <= y <= uy and x + y >= 1]
    assert feasible, bounds
    return min(2 * x + y + 5 for x, y in feasible)


def parse_record(line):
    fields = line.strip().split("|")
    assert len(fields) == 35, fields
    assert fields[0] == "LP_LIFECYCLE"
    arm, repetition, index = fields[1], int(fields[2]), int(fields[3])
    assert arm in ("retained", "cold")
    bounds = tuple(map(int, fields[4:8]))
    model_hash = fields[8]
    verdict, bound, px, py, witness_objective = fields[9:14]
    expected = Fraction(optimum(bounds))
    assert verdict == "ATTAINED_OPTIMUM", (arm, repetition, index, verdict)
    x, y = Fraction(px), Fraction(py)
    lx, ux, ly, uy = bounds
    assert lx <= x <= ux and ly <= y <= uy and x + y >= 1
    assert 2 * x + y + 5 == expected
    assert Fraction(bound) + 5 == expected
    assert Fraction(witness_objective) + 5 == expected
    assert all(fields[i] != "NA" and int(fields[i]) >= 0 for i in (14, 15, 32))
    assert all(fields[i] == "NA" or int(fields[i]) >= 0 for i in (33, 34))
    timing = [None if v == "NA" else int(v) for v in fields[20:26]]
    allocation = [None if v == "NA" else int(v) for v in fields[26:32]]
    assert all(v is None or v >= 0 for v in timing + allocation)
    assert timing[1:5].count(None) == 0
    assert allocation[1:5].count(None) in (0, 4)
    return (arm, repetition, index, bounds, model_hash, timing, allocation, fields)


def summarize(path):
    groups = defaultdict(list)
    disposals = defaultdict(list)
    owners = {}
    captures = []
    for line in Path(path).read_text().splitlines():
        if line.startswith("LP_LIFECYCLE|"):
            record = parse_record(line)
            groups[record[:2]].append(record)
        elif line.startswith("LP_LIFECYCLE_DISPOSAL|"):
            fields = line.split("|")
            assert len(fields) == 5
            elapsed = int(fields[3])
            allocation = None if fields[4] == "NA" else int(fields[4])
            assert elapsed >= 0 and (allocation is None or allocation >= 0)
            disposals[(fields[1], int(fields[2]))].append((elapsed, allocation))
        elif line.startswith("LP_LIFECYCLE_OWNERS|"):
            fields = line.split("|", 6)
            assert len(fields) == 7
            key = (fields[1], int(fields[2]))
            assert key not in owners
            owners[key] = (int(fields[3]), int(fields[4]), fields[5], fields[6])
        elif line.startswith("LP_LIFECYCLE_CAPTURE|"):
            captures.append(line.split("|"))
    expected_keys = {(arm, rep) for arm in ("retained", "cold") for rep in (-1, 0, 1, 2)}
    assert set(groups) == set(disposals) == set(owners) == expected_keys
    assert captures == [["LP_LIFECYCLE_CAPTURE", CAPTURE, "21"]]
    baseline = [(r[3], r[4]) for r in groups[("retained", -1)]]
    expected_bounds = [(-3, 7, -3, 7), (0, 7, -3, 7), (0, 7, 2, 7),
                       (0, 7, -3, 7), (0, 2, -1, 7)]
    for key, rows in groups.items():
        assert len(rows) == 21 and [r[2] for r in rows] == list(range(21))
        assert [(r[3], r[4]) for r in rows] == baseline, key
        assert [r[3] for r in rows] == [expected_bounds[i % 5] for i in range(21)]
        assert owners[key][:2] == ((1, 1) if key[0] == "retained" else (21, 21))
        assert len(disposals[key]) == (1 if key[0] == "retained" else 21)
        assert all(r[5][0] is not None for r in rows if key[0] == "cold")
        assert sum(r[5][0] is not None for r in rows) == (1 if key[0] == "retained" else 21)
        assert all(int(r[7][14]) < 1_000_000 for r in rows), key
    for repetition in (-1, 0, 1, 2):
        retained = groups[("retained", repetition)]
        cold = groups[("cold", repetition)]
        assert [r[7][9:14] for r in retained] == [r[7][9:14] for r in cold], repetition
    profiles = {v[2] for v in owners.values()}
    assert len(profiles) == 1, profiles
    per_run = []
    for (arm, repetition), rows in sorted(groups.items()):
        elapsed = [sum(r[5][i] or 0 for r in rows) for i in range(5)]
        elapsed.append(sum(v[0] for v in disposals[(arm, repetition)]))
        allocations = []
        for i in range(5):
            values = [r[6][i] for r in rows if r[5][i] is not None]
            allocations.append(sum(values) if all(v is not None for v in values) else None)
        closed_alloc = [v[1] for v in disposals[(arm, repetition)]]
        allocations.append(sum(closed_alloc) if all(v is not None for v in closed_alloc) else None)
        reconstruction = [r[7][16] for r in rows]
        exact_reconstruction = (sum(map(int, reconstruction)) if all(v != "NA" for v in reconstruction)
                                else None)
        preparation = [r[7][33] for r in rows]
        preparation_factors = [r[7][34] for r in rows]
        if all(v != "NA" for v in preparation):
            preparation_work = int(preparation[-1]) if arm == "retained" else sum(map(int, preparation))
            update_preparation_work = (int(preparation[-1]) - int(preparation[0]) if arm == "retained"
                                       else sum(map(int, preparation[1:])))
        else:
            preparation_work = update_preparation_work = None
        if all(v != "NA" for v in preparation_factors):
            preparation_refactors = (int(preparation_factors[-1]) if arm == "retained"
                                     else sum(map(int, preparation_factors)))
        else:
            preparation_refactors = None
        if arm == "retained" and preparation_work is not None:
            assert all(int(left) <= int(right) for left, right in zip(preparation, preparation[1:]))
        if arm == "retained" and preparation_refactors is not None:
            assert all(int(left) <= int(right) for left, right in
                       zip(preparation_factors, preparation_factors[1:]))
        per_run.append({"arm": arm, "repetition": repetition, "states": len(rows),
                        "updates": len(rows) - 1,
                        "observedSolveWorkOps": sum(int(r[7][14]) for r in rows),
                        "updateObservedSolveWorkOps": sum(int(r[7][14]) for r in rows[1:]),
                        "pivots": sum(int(r[7][15]) for r in rows),
                        "solveFactorizations": sum(int(r[7][32]) for r in rows),
                        "preparationWork": preparation_work,
                        "updatePreparationWork": update_preparation_work,
                        "preparationFactorizations": preparation_refactors,
                        "exactReconstructionWork": exact_reconstruction,
                        "unavailableExactWorkFields": [name for name, position in
                                                       (("basisVerification", 17), ("refinement", 18),
                                                        ("continuation", 19))
                                                       if any(r[7][position] == "NA" for r in rows)],
                        "nanos": dict(zip(PHASES, elapsed)),
                        "totalNanos": sum(elapsed),
                        "javaThreadBytes": dict(zip(PHASES, allocations)),
                        "totalJavaThreadBytes": sum(allocations) if all(v is not None for v in allocations) else None})
    paired = []
    for repetition in range(3):
        retained = next(r for r in per_run if r["arm"] == "retained" and r["repetition"] == repetition)
        cold = next(r for r in per_run if r["arm"] == "cold" and r["repetition"] == repetition)
        paired.append({"repetition": repetition,
                       "firstArm": "retained" if repetition % 2 == 0 else "cold",
                       "retainedTotalNanos": retained["totalNanos"], "coldTotalNanos": cold["totalNanos"]})
    distributions = {}
    for arm in ("retained", "cold"):
        values = [r["totalNanos"] for r in per_run if r["arm"] == arm and r["repetition"] >= 0]
        distributions[arm] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
    return {"schema": 1, "captureSha256": CAPTURE, "sourceChecked": sum(len(v) for v in groups.values()),
            "profiles": list(profiles), "requestedWorkRanges": {f"{key[0]}:{key[1]}": value[3]
                                                        for key, value in owners.items()},
            "perRun": per_run, "paired": paired,
            "totalElapsedDistributionNanos": distributions,
            "limits": ["Java thread allocations exclude native allocations",
                       "solveNode combines projection/import and float solve",
                       "hashing, output and independent checking are outside phase timing"]}


if __name__ == "__main__":
    assert len(sys.argv) == 2, "usage: check_lifecycle.py LOG"
    print(json.dumps(summarize(sys.argv[1]), indent=2))
