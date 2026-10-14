"""Check exhaustive finite provider records against original integer/Boolean source relations."""

import hashlib
import itertools
import json
import platform
import sys
from pathlib import Path
from fractions import Fraction


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


def verify_mapped(control):
    source = control["source"]
    bounds = source["bounds"]
    columns = source["tableColumns"]
    if len(columns) != 1 or not 0 <= columns[0] < len(bounds):
        raise ValueError("unsupported mapped source table")
    column = columns[0]
    if control["cpSourceIds"] != columns:
        raise ValueError("CP coordinates differ from original source coordinates")
    if any(bound != {"lower": None, "upper": None} for i, bound in enumerate(bounds) if i != column):
        raise ValueError("mapped control invents restrictions on its open source columns")
    lower, upper = int(bounds[column]["lower"]), int(bounds[column]["upper"])
    if not 0 <= upper - lower <= 1000:
        raise ValueError("mapped domain exceeds checker allowance")
    domain = set(range(lower, upper + 1))
    allowed = {int(value) for value in source["tableTuples"]} & domain
    if not allowed or set(map(int, control["rootValues"])) != domain:
        raise ValueError("selected inert deduction lost the original CP domain")
    projected = []
    for record in control["witnesses"]:
        witness = list(map(int, record))
        if len(witness) != len(bounds) or witness[column] not in allowed:
            raise ValueError("mapped witness violates the original source table")
        projected.append(witness[column])
    if len(projected) != len(set(projected)) or set(projected) != allowed:
        raise ValueError("mapped enumeration differs from the bounded CP source projection")
    scores = {}
    for record in control["scores"]:
        value = int(record["value"])
        if value in scores:
            raise ValueError("duplicate mapped score")
        scores[value] = record["score"]
    if set(scores) != domain or any(score != (0 if value in allowed else 9) for value, score in scores.items()):
        raise ValueError("mapped scorer does not implement the selected source relation")
    senses = set()
    for record in control["optimization"]:
        maximize = record["maximize"]
        if type(maximize) is not bool or maximize in senses:
            raise ValueError("duplicate mapped objective direction")
        senses.add(maximize)
        expected = max(allowed) if maximize else min(allowed)
        if int(record["value"]) != expected or record["objective"] != expected:
            raise ValueError("mapped objective differs from the original source projection")
    if senses != {False, True} or type(control["relaxationEmissions"]) is not int or control["relaxationEmissions"] <= 0:
        raise ValueError("mapped relaxation control did not exercise both objective directions")
    return {"cpSourceIds": columns, "projectedWitnesses": len(projected),
            "scope": "exact source witnesses and complete bounded CP projection; open unused columns are not enumerated"}


def verify_corners(source, controls):
    all_points = set(itertools.product(itertools.product((False, True), repeat=source["boolCount"]),
                                       itertools.product(*source["domains"])))
    feasible = {assignment for assignment in all_points if accepts(source, assignment)}
    witnesses = [point(record, source) for record in controls["inertWitnesses"]]
    if len(witnesses) != len(set(witnesses)) or set(witnesses) != feasible:
        raise ValueError("inert deduction differs from the original source solution set")
    if controls["unsupported"] != "refused" or controls["uncheckable"] != "Unsupported":
        raise ValueError("unsupported or uncheckable source produced a false complete claim")
    real = controls["realSource"]
    lower, upper = Fraction(real["lower"]), Fraction(real["upper"])
    coefficient, bound = Fraction(real["coefficient"]), Fraction(real["bound"])
    if coefficient <= 0 or lower > upper:
        raise ValueError("unsupported real source")
    minimum = max(lower, bound / coefficient)
    if minimum > upper:
        raise ValueError("real source control is infeasible")
    modes = set()
    for control in controls["realControls"]:
        mode = control["mode"]
        if mode in modes:
            raise ValueError("duplicate real provider control")
        modes.add(mode)
        witness = Fraction(control["exactReal"])
        if not minimum <= witness <= upper:
            raise ValueError("real completion violates the original source")
        senses = set()
        for record in control["optimization"]:
            maximize = record["maximize"]
            if type(maximize) is not bool or maximize in senses:
                raise ValueError("duplicate real objective direction")
            senses.add(maximize)
            value = Fraction(record["exactReal"])
            if value != (upper if maximize else minimum) or Fraction(str(record["value"])) != value:
                raise ValueError("real objective differs from the exact original-direction source objective")
        if senses != {False, True}:
            raise ValueError("missing real objective direction")
    if modes != {"default", "omitted"}:
        raise ValueError("missing real provider control")
    result = {"inertWitnesses": len(witnesses), "unsupported": "refused", "uncheckable": "Unsupported",
              "realModes": sorted(modes), "realMinimum": str(minimum), "realMaximum": str(upper)}
    if "mapped" in controls:
        result["mapped"] = verify_mapped(controls["mapped"])
    elif source.get("sourceMappedRequired"):
        raise ValueError("missing required mapped source control")
    return result


def main():
    source_path, controls_path, output_path = map(Path, sys.argv[1:4])
    source_bytes = source_path.read_bytes()
    controls_bytes = controls_path.read_bytes()
    source = json.loads(source_bytes)
    result = verify(source, json.loads(controls_bytes))
    if source.get("sourceCornersRequired"):
        if len(sys.argv) != 5:
            raise ValueError("missing required provider corner controls")
        corner_bytes = Path(sys.argv[4]).read_bytes()
        result["corners"] = verify_corners(source, json.loads(corner_bytes))
        result["cornersSha256"] = hashlib.sha256(corner_bytes).hexdigest()
    result.update({"sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
                   "controlsSha256": hashlib.sha256(controls_bytes).hexdigest(),
                   "checkerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   "python": platform.python_version(),
                   "pythonExecutableSha256": hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest()})
    output_path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
