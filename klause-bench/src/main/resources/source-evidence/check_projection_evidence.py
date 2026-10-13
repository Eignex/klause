"""Check exhaustive finite provider records against original integer/Boolean source relations."""

import hashlib
import itertools
import json
import platform
import sys
from pathlib import Path


def point(record, source):
    booleans = record["bools"]
    integers = tuple(int(value) for value in record["ints"])
    if len(booleans) != source["boolCount"] or any(type(value) is not bool for value in booleans):
        raise ValueError("invalid Boolean coordinates")
    if len(integers) != len(source["domains"]):
        raise ValueError("invalid integer coordinates")
    if any(value not in domain for value, domain in zip(integers, source["domains"])):
        raise ValueError("integer coordinate outside its original domain")
    return tuple(booleans), integers


def accepts(source, assignment):
    booleans, integers = assignment
    for clause in source["clauses"]:
        if not any(booleans[literal >> 1] != bool(literal & 1) for literal in clause):
            return False
    for row in source["linear"]:
        coefficients = tuple(int(value) for value in row["coefficients"])
        if len(coefficients) != len(integers):
            raise ValueError("linear row has an undeclared coordinate")
        total = sum(coefficient * value for coefficient, value in zip(coefficients, integers))
        bound = int(row["bound"])
        if not {"LE": total <= bound, "GE": total >= bound, "EQ": total == bound, "NE": total != bound}[row["op"]]:
            return False
    return True


def verify(source, controls):
    if source["schema"] != "finite-projection-controls-v1":
        raise ValueError("unsupported source schema")
    count = 2 ** source["boolCount"]
    for domain in source["domains"]:
        if not domain or len(set(domain)) != len(domain):
            raise ValueError("invalid source domain")
        count *= len(domain)
    if count > 100000:
        raise ValueError("source exceeds exhaustive checker allowance")
    if any(literal < 0 or literal >> 1 >= source["boolCount"] for clause in source["clauses"] for literal in clause):
        raise ValueError("clause has an undeclared coordinate")
    all_points = set(itertools.product(itertools.product((False, True), repeat=source["boolCount"]),
                                       itertools.product(*source["domains"])))
    feasible = {assignment for assignment in all_points if accepts(source, assignment)}
    modes = set()
    for control in controls:
        mode = control["mode"]
        if mode in modes:
            raise ValueError("duplicate provider control")
        modes.add(mode)
        witnesses = [point(record, source) for record in control["witnesses"]]
        if len(witnesses) != len(set(witnesses)) or set(witnesses) != feasible:
            raise ValueError("provider enumeration differs from the exact source solution set")
        scores = {}
        for record in control["scores"]:
            assignment = point(record["assignment"], source)
            if assignment in scores or type(record["accepted"]) is not bool:
                raise ValueError("invalid or duplicate score record")
            scores[assignment] = record["accepted"]
        if set(scores) != all_points or any(value != (assignment in feasible) for assignment, value in scores.items()):
            raise ValueError("provider score acceptance differs from the exact source relation")
        coefficients = tuple(int(value) for value in source["objective"])
        if len(coefficients) != len(source["domains"]) or not feasible:
            raise ValueError("optimization control has no supported objective")
        objectives = {sum(coefficient * value for coefficient, value in zip(coefficients, assignment[1]))
                      for assignment in feasible}
        senses = set()
        for record in control["optimization"]:
            maximize = record["maximize"]
            if type(maximize) is not bool or maximize in senses:
                raise ValueError("invalid or duplicate objective direction")
            senses.add(maximize)
            assignment = point(record["assignment"], source)
            value = sum(coefficient * coordinate for coefficient, coordinate in zip(coefficients, assignment[1]))
            expected = max(objectives) if maximize else min(objectives)
            if assignment not in feasible or record["value"] != value or value != expected:
                raise ValueError("optimization record differs from the exact original-direction objective")
        if senses != {False, True}:
            raise ValueError("missing objective direction control")
    if modes != {"default", "alternate"}:
        raise ValueError("missing provider control")
    return {"checkedAssignments": len(all_points), "feasibleAssignments": len(feasible), "modes": sorted(modes),
            "scope": "exhaustive finite source assignments and exact integer objectives", "proofFileVerification": False}


def main():
    source_path, controls_path, output_path = map(Path, sys.argv[1:])
    source_bytes = source_path.read_bytes()
    controls_bytes = controls_path.read_bytes()
    result = verify(json.loads(source_bytes), json.loads(controls_bytes))
    result.update({"sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
                   "controlsSha256": hashlib.sha256(controls_bytes).hexdigest(),
                   "checkerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   "python": platform.python_version()})
    output_path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
