"""Audit the frozen route acceptance and related-control claims."""
import json
from pathlib import Path

directory = Path(__file__).parent
cases = json.loads((directory / "acceptance-cases.json").read_text())
for case in cases:
    record = case["record"]
    expected = None if case["arm"] == "main-conflictDriven" else True
    assert record["feasible"] == expected, "RWS route acceptance failed"
    if case["arm"].endswith("conflictDriven"):
        expected_nodes = "873" if expected is None else "1813"
        assert record["stats"]["nodes"] == expected_nodes, "RWS progress claim mismatch"
    else:
        assert record["stats"]["nodes"] == "416" and record["stats"]["failures"] == "68"

controls = json.loads((directory / "controls-cases.json").read_text())
pairs = {}
for case in controls:
    key = (case["problem"]["suite"], case["problem"]["problem"], case["repeat"], case["seed"])
    pairs.setdefault(key, {})[case["arm"]] = case["record"]
assert len(pairs) == 24, "related-control pair count mismatch"
for pair in pairs.values():
    baseline = pair["main-conflictDriven"]
    candidate = pair["lifetime-conflictDriven"]
    for field in ("feasible", "proven", "objective"):
        assert baseline[field] == candidate[field], f"related-control {field} changed"
    for field in ("nodes", "failures"):
        assert baseline["stats"].get(field) == candidate["stats"].get(field), f"related-control {field} changed"
defaults = json.loads((directory / "default-cases.json").read_text())
assert len(defaults) == 6, "original-default case count mismatch"
for case in defaults:
    record = case["record"]
    assert record["feasible"] is True and record["proven"] is False and record["objective"] is None
    assert record["stats"]["nodes"] == "416" and record["stats"]["failures"] == "68"
    for override in ("--lp ", "--param arms=", "--param bt-arm="):
        assert override not in record["command"], "original-default route overridden"
for name in ("acceptance", "controls", "default"):
    reference = json.loads((directory / f"{name}-reference.json").read_text())
    assert not reference["disagreements"] and not reference["shortfalls"], "reference disagreement"
print("RWS acceptance: 3 candidate SAT repeats per route; original defaults: 6 SAT; related controls: 24 matching pairs")
