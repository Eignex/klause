"""Collect exported assignments and source identities; verification runs separately in CI."""

import hashlib
import json
import pathlib
import sys
import urllib.request


def read(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def collect(job, corpus):
    base = "http://192.168.50.104:8420"
    cases = json.loads(read(f"{base}/experiments/{job}/cases"))
    witnesses = {}
    for case in cases:
        if case["status"] != "DONE":
            raise ValueError(f"incomplete case {case['index']}")
        raw = read(f"{base}/jobs/{job}/files/cases/{case['index']}/record.json")
        record = json.loads(raw)
        if record["feasible"] is not True:
            continue
        assignment = record["mpsWitness"]
        if not assignment:
            raise ValueError(f"missing feasible assignment {case['index']}")
        problem = record["problem"]
        if case["problem"]["suite"] == "mps-core":
            path = pathlib.Path("klause-bench/smoke-corpus/mps") / (problem + ".mps")
            source = {"kind": "vendored", "path": str(path)}
        else:
            path = corpus / "miplib3-mps/miplib3" / (problem + ".mps")
            source = {"kind": "miplib3", "name": problem}
        source["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        key = (problem, assignment)
        witness = witnesses.setdefault(key, {
            "problem": problem, "source": source, "assignment": assignment, "cases": [],
        })
        witness["cases"].append({
            "job": job, "index": case["index"], "arm": case["arm"],
            "recordSha256": hashlib.sha256(raw).hexdigest(),
            "buildFingerprint": record["buildFingerprint"], "gitSha": record["gitSha"],
            "command": record["command"], "objective": record["objective"], "proven": record["proven"],
        })
    return list(witnesses.values())


if __name__ == "__main__":
    print(json.dumps(collect(int(sys.argv[1]), pathlib.Path(sys.argv[2])), indent=2))
