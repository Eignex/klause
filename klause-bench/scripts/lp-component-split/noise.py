#!/usr/bin/env python3
"""Compare balanced duplicate-arm blocks against timing variation."""
import argparse
import json
import statistics
from pathlib import Path

LABELS = ("off-first", "on-first", "on-second", "off-second")
REVERSED_LABELS = ("on-first", "off-first", "off-second", "on-second")


def analyze(cases):
    if len(cases) % 4:
        raise ValueError("incomplete balanced block")
    blocks = []
    ordered = sorted(cases, key=lambda case: case["index"])
    for start in range(0, len(cases), 4):
        group = ordered[start:start + 4]
        labels = tuple(case["arm"] for case in group)
        if labels not in (LABELS, REVERSED_LABELS):
            raise ValueError("case order is not a balanced duplicate-arm block")
        if any(case["status"] != "DONE" or case.get("record") is None for case in group):
            raise ValueError("incomplete case")
        records = [case["record"] for case in group]
        outcomes = [(r["feasible"], r["objective"], r["proven"]) for r in records]
        if len(set(outcomes)) != 1:
            raise ValueError("verdicts differ within balanced block")
        block = {"indices": [case["index"] for case in group]}
        for metric in ("elapsedMs", "timeToBestMs"):
            values = {case["arm"]: case["record"][metric] for case in group}
            if any(value is None for value in values.values()):
                raise ValueError("missing timing")
            off = (values["off-first"] + values["off-second"]) / 2
            on = (values["on-first"] + values["on-second"]) / 2
            block[metric] = dict(off=off, on=on, differenceMs=on-off,
                                 ratio=on/off,
                                 duplicateOffMs=values["off-second"]-values["off-first"],
                                 duplicateOnMs=values["on-second"]-values["on-first"])
        blocks.append(block)
    summary = {}
    for metric in ("elapsedMs", "timeToBestMs"):
        samples = [block[metric] for block in blocks]
        summary[metric] = dict(
            medianOffMs=statistics.median(s["off"] for s in samples),
            medianOnMs=statistics.median(s["on"] for s in samples),
            medianRatio=statistics.median(s["ratio"] for s in samples),
            minRatio=min(s["ratio"] for s in samples),
            maxRatio=max(s["ratio"] for s in samples),
            fasterOn=sum(s["differenceMs"] < 0 for s in samples),
            slowerOn=sum(s["differenceMs"] > 0 for s in samples),
            medianDuplicateOffAbsMs=statistics.median(abs(s["duplicateOffMs"]) for s in samples),
            maxDuplicateOffAbsMs=max(abs(s["duplicateOffMs"]) for s in samples),
            medianDuplicateOnAbsMs=statistics.median(abs(s["duplicateOnMs"]) for s in samples),
            maxDuplicateOnAbsMs=max(abs(s["duplicateOnMs"]) for s in samples),
        )
    return dict(blocks=blocks, summary=summary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("cases", type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(json.loads(args.cases.read_text())), indent=2))
