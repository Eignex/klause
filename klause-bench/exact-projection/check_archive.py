"""CI checks of executed-build provenance and matched work against raw lab records."""

import gzip
import hashlib
import json
import pathlib


root = pathlib.Path(__file__).parent
raw = gzip.decompress((root / "raw/job-865-cases.json.gz").read_bytes())
cases = json.loads(raw)
provenance = json.loads((root / "fixed-work-provenance.json").read_text())
assert hashlib.sha256(raw).hexdigest() == provenance["casesSha256"]
assert len(cases) == len(provenance["cases"]) == 30
for case, recorded in zip(cases, provenance["cases"]):
    record = case["record"]
    build = record["buildProvenance"]
    expected = {name: case[name] for name in ("index", "arm", "seed", "repeat")} | {
        name: record.get(name) for name in (
            "problem", "gitSha", "buildFingerprint", "validationPolicy", "command", "timestamp", "elapsedMs",
        )
    } | {
        "runtimeOptions": build["runtimeOptions"], "osName": build["osName"], "osArch": build["osArch"],
        "solverJarSha256": build["distribution"]["lib/klause-jvm-SNAPSHOT.jar"],
        "cliJarSha256": build["distribution"]["lib/klause-cli-jvm-SNAPSHOT.jar"],
        "javaSha256": build["runtime"]["bin/java"],
    }
    assert recorded == expected
    assert case["status"] == "DONE" and record["stats"]["openWork"] == "1000"
    assert record["feasible"] is None and record["proven"] is False
summary = json.loads((root / "fixed-work-summary.json").read_text())
assert len(summary) == 3
for problem in summary:
    assert len(problem["pairs"]) == 5
    for pair in problem["pairs"]:
        assert pair["differences"] == {}
        assert pair["counterHashes"][0] == pair["counterHashes"][1]
print("30 executed cases match provenance, work and every non-timing counter")
