"""Summarize completed lab deadline/control records without running a solver."""

import collections
import hashlib
import json
import pathlib
import statistics
import sys


def summarize(cases):
    groups = collections.defaultdict(lambda: collections.defaultdict(list))
    for case in cases:
        if case["status"] != "DONE":
            raise ValueError(f"incomplete case {case['index']}")
        groups[case["record"]["problem"]][case["arm"]].append(case["record"])
    result = []
    for problem, arms in groups.items():
        entry = {"problem": problem, "arms": {}}
        for arm, records in arms.items():
            outcomes = collections.Counter((record["feasible"], record["objective"], record["proven"])
                                           for record in records)
            times = [float(record["stats"]["solveTime"]) for record in records
                     if "solveTime" in record["stats"]]
            rates = [float(record["stats"]["smtTheoryChecksPerSec"]) for record in records
                     if "smtTheoryChecksPerSec" in record["stats"]]
            entry["arms"][arm] = {
                "outcomes": [{"feasible": feasible, "objective": objective, "proven": proven, "count": count}
                             for (feasible, objective, proven), count in outcomes.items()],
                "cases": len(records),
                "fingerprints": sorted({record["buildFingerprint"] for record in records
                                        if record.get("buildFingerprint")}),
                "medianSolveSeconds": statistics.median(times) if times else None,
                "medianTheoryChecksPerSecond": statistics.median(rates) if rates else None,
                "unsupportedReasons": sorted({record["stats"]["unsupported"] for record in records
                                              if "unsupported" in record["stats"]}),
            }
        result.append(entry)
    return result


if __name__ == "__main__":
    source, prefix = pathlib.Path(sys.argv[1]), sys.argv[2]
    raw = source.read_bytes()
    cases = json.loads(raw)
    pathlib.Path(prefix + "-summary.json").write_text(json.dumps(summarize(cases), indent=2) + "\n")
    provenance = {
        "casesSha256": hashlib.sha256(raw).hexdigest(),
        "cases": [{name: case[name] for name in ("index", "arm", "seed", "repeat")} |
                  {name: case["record"].get(name) for name in (
                      "problem", "gitSha", "buildFingerprint", "validationPolicy", "command", "timestamp", "elapsedMs",
                  )} for case in cases],
    }
    pathlib.Path(prefix + "-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
