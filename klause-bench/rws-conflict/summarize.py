"""Audit a serial klause-lab reproduction and retain its per-run evidence."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("spec", type=Path)
parser.add_argument("cases", type=Path)
parser.add_argument("arms", type=Path)
parser.add_argument("job", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
spec = json.loads(args.spec.read_text())
cases = json.loads(args.cases.read_text())
arms = {a["arm"]["label"]: a["sha"] for a in json.loads(args.arms.read_text())}
job = json.loads(args.job.read_text())
expected_arms = {a["label"]: a["ref"] for a in spec["configs"]}
assert arms.keys() == expected_arms.keys(), "arm mismatch"
assert all(re.fullmatch(r"[0-9a-f]{9,40}", ref) and arms[a].startswith(ref)
           for a, ref in expected_arms.items()), "executed revisions differ from frozen specification"
assert spec["parallel"] == 1 and spec["machines"] == 1, "timings require serial execution"
assert not spec.get("profileCli", False), "instrumented profiles are not ordinary timings"
assert job["status"] == "DONE" and job["parallel"] == 1, "job did not finish serially"
assert job["experiment"]["host"] == "aws" and job["experiment"]["machines"] == 1, "runner mismatch"
commands = sorted(job["commands"], key=lambda command: command["startedAt"])
assert all(c["status"] == "DONE" and c["exitCode"] == 0 for c in commands), "process failure"
assert all(a["finishedAt"] <= b["startedAt"] for a, b in zip(commands, commands[1:])), "overlapping cases"
keys = [(c["arm"], c["problem"]["suite"], c["problem"]["problem"], c["seed"], c.get("repeat", 0)) for c in cases]
assert cases and len(cases) == len(commands), "missing solve records"
assert len(keys) == len(set(keys)), "duplicate cases"
problems = {(k[1], k[2]) for k in keys}
selections = spec["problems"] if isinstance(spec["problems"], list) else [spec["problems"]]
assert len(problems) == sum(int(s["max"]) for s in selections), "frozen problem count mismatch"
expected = {(a, s, p, seed, rep) for a in arms for s, p in problems
            for seed in spec["seeds"] for rep in range(spec["repeats"])}
assert set(keys) == expected, "missing arm, seed or repetition"
rows = []
for case in cases:
    record = case["record"]
    assert case["status"] == "DONE" and record, f"unfinished case {case['index']}"
    assert record["gitSha"] == arms[case["arm"]], "record revision mismatch"
    assert record.get("buildFingerprint") and record.get("buildProvenance"), "missing executed binary identity"
    assert record["seed"] in spec["seeds"] and record["processors"] == 1, "execution settings mismatch"
    assert record["budgetMs"] == int(spec["base"]["timeout"]), "wall allowance mismatch"
    assert record["command"] != "ERROR" and "solveTime" in record["stats"], "solver did not run"
    params = {**spec["base"], **next(a for a in spec["configs"] if a["label"] == case["arm"])}
    for key, value in params.items():
        if key.startswith("param."):
            assert f"--param {key[6:]}={value}" in record["command"], "engine parameter mismatch"
    assert f"--lp {params['lp']}" in record["command"], "LP setting mismatch"
    rows.append({"index": case["index"], "arm": case["arm"], "problem": case["problem"],
                 "seed": case["seed"], "repeat": case.get("repeat", 0),
                 **{k: record.get(k) for k in ("gitSha", "buildFingerprint", "command", "budgetMs",
                                              "feasible", "proven", "validationPolicy", "elapsedMs", "stats")}})
args.output.write_text(json.dumps({"casesSha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                                  "problems": sorted(problems), "runs": rows}, indent=2) + "\n")
for row in rows:
    stats = row["stats"]
    print(row["arm"], row["repeat"], row["feasible"], stats.get("nodes"),
          stats.get("failures"), stats["solveTime"])
