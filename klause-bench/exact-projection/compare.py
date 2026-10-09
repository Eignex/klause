"""Check paired lab cases and summarize uninstrumented fixed-work timings."""

import collections
import json
import statistics
import sys


def timing_stat(name):
    return (name == "solveTime" or name.endswith(("Ns", "Nanos", "Ms", "Rate", "PerSec")))


def summarize(cases):
    paired = collections.defaultdict(dict)
    for case in cases:
        key = (case["problem"]["problem"], case.get("seed"), case.get("repeat"))
        if case["status"] != "DONE" or not case.get("record"):
            raise ValueError(f"incomplete case {case['index']}")
        if case["arm"] in paired[key]:
            raise ValueError(f"duplicate arm {key}")
        paired[key][case["arm"]] = case["record"]
    groups = collections.defaultdict(list)
    for key, arms in paired.items():
        before, after = arms["main"], arms["candidate"]
        outcomes = ("feasible", "objective", "proven", "solutions")
        differences = {name: [before.get(name), after.get(name)] for name in outcomes
                       if before.get(name) != after.get(name)}
        for name in before["stats"].keys() | after["stats"].keys():
            a, b = before["stats"].get(name), after["stats"].get(name)
            if not timing_stat(name) and a != b:
                differences[name] = [a, b]
        groups[key[0]].append({
            "seed": key[1], "repeat": key[2], "differences": differences,
            "seconds": [float(arm["stats"]["solveTime"]) for arm in (before, after)],
            "fingerprints": [arm.get("buildFingerprint") for arm in (before, after)],
            "outcomes": [{name: arm.get(name) for name in outcomes} for arm in (before, after)],
            "work": [arm["stats"].get("openWork") for arm in (before, after)],
            "theoryChecks": [arm["stats"].get("smtTheoryChecks") for arm in (before, after)],
        })
    return [{
        "problem": problem,
        "medianSeconds": [statistics.median(pair["seconds"][index] for pair in pairs)
                          for index in (0, 1)],
        "pairs": pairs,
    } for problem, pairs in groups.items()]


if __name__ == "__main__":
    with open(sys.argv[1]) as source:
        print(json.dumps(summarize(json.load(source)), indent=2))
