#!/usr/bin/env python3
"""Check captured affine MPS proofs against original bytes using only exact fractions.

Accepts unguarded linear MPS sources, nonnegative row combinations, integer split
coverage and integral recession steps. Unsupported sources or derivations fail closed.
No Klause arithmetic, parser or proof checker is imported.
"""

import hashlib
import json
import math
import sys
from fractions import Fraction as F
from pathlib import Path


def number(text):
    return F(str(text).replace("D", "E"))


def clean(terms):
    return {key: value for key, value in terms.items() if value}


def add(left, right, scale=F(1)):
    result = left.copy()
    for key, value in right.items():
        result[key] = result.get(key, F(0)) + scale * value
    return clean(result)


def parse_mps(text):
    section, cost, maximize, marker = None, None, False, False
    rows, coefficients, rhs, ranges, lower, upper = {}, {}, {}, {}, {}, {}
    columns, integers, vectors = set(), set(), {}
    for raw in text.splitlines():
        parts = raw.split()
        if not parts or raw.startswith("*"):
            continue
        if raw[0] not in " \t":
            section = parts[0]
            assert section in {"NAME", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "OBJSENSE", "ENDATA"}
            assert section == "NAME" or len(parts) == 1
            continue
        if section == "OBJSENSE":
            assert parts[0] in {"MIN", "MAX", "MINIMIZE", "MAXIMIZE"}
            maximize = parts[0].startswith("MAX")
        elif section == "ROWS":
            kind, row = parts
            assert kind in "NLGE" and row not in rows
            rows[row] = kind
            if kind == "N" and cost is None:
                cost = row
        elif section == "COLUMNS":
            if "'MARKER'" in parts:
                assert parts[-1] in {"'INTORG'", "'INTEND'"}
                marker = parts[-1] == "'INTORG'"
                continue
            column = parts[0]
            assert len(parts) >= 3 and len(parts) % 2 == 1
            columns.add(column)
            if marker:
                integers.add(column)
            for row, value in zip(parts[1::2], parts[2::2]):
                terms = coefficients.setdefault(row, {})
                terms[column] = terms.get(column, F(0)) + number(value)
        elif section in {"RHS", "RANGES"}:
            vector = parts[0] if len(parts) % 2 else vectors.get(section, "")
            assert vectors.setdefault(section, vector) == vector
            values = parts[1:] if len(parts) % 2 else parts
            assert values and len(values) % 2 == 0
            target = rhs if section == "RHS" else ranges
            for row, value in zip(values[::2], values[1::2]):
                assert row in rows and row not in target
                target[row] = number(value)
        elif section == "BOUNDS":
            kind, vector, column = parts[:3]
            assert vectors.setdefault(section, vector) == vector
            columns.add(column)
            value = number(parts[3]) if len(parts) == 4 else None
            if kind in {"LO", "LI"}:
                assert value is not None
                lower[column] = value
            elif kind in {"UP", "UI"}:
                assert value is not None
                upper[column] = value
                if value < 0 and column not in lower:
                    lower[column] = None
            elif kind == "FX":
                assert value is not None
                lower[column] = upper[column] = value
            elif kind == "FR":
                lower[column] = upper[column] = None
            elif kind == "MI":
                lower[column] = None
            elif kind == "PL":
                upper[column] = None
            elif kind == "BV":
                lower[column], upper[column] = F(0), F(1)
                integers.add(column)
            else:
                raise ValueError(f"unsupported bound {kind}")
            if kind in {"LI", "UI"}:
                integers.add(column)
        else:
            raise ValueError(f"unsupported section {section}")
    assert cost is not None and all(row in rows for row in coefficients)
    constraints, rounded = [], []
    for column in columns:
        lo, hi = lower.get(column, F(0)), upper.get(column)
        if column in integers and column not in lower and column not in upper:
            hi = F(1)
        if lo is not None:
            constraints.append(({column: F(-1)}, -lo))
            if column in integers:
                rounded.append(({column: F(-1)}, F(-math.ceil(lo))))
        if hi is not None:
            constraints.append(({column: F(1)}, hi))
            if column in integers:
                rounded.append(({column: F(1)}, F(math.floor(hi))))
    for row, kind in rows.items():
        if kind == "N":
            continue
        terms, bound, span = clean(coefficients.get(row, {})), rhs.get(row, F(0)), ranges.get(row)
        if kind in "EG":
            side = bound + (span if kind == "E" and span is not None and span < 0 else 0)
            constraints.append(({k: -v for k, v in terms.items()}, -side))
        if kind in "EL":
            side = bound + (span if kind == "E" and span is not None and span > 0 else 0)
            constraints.append((terms, side))
        if span is not None and kind == "L":
            constraints.append(({k: -v for k, v in terms.items()}, -(bound - abs(span))))
        if span is not None and kind == "G":
            constraints.append((terms, bound + abs(span)))
    return columns, integers, constraints, rounded, clean(coefficients.get(cost, {})), -rhs.get(cost, F(0)), maximize


def multiple(row, original):
    terms, rhs = row
    expected, bound = original
    if terms.keys() != expected.keys():
        return False
    if not terms:
        return rhs == 0 if bound == 0 else rhs / bound > 0
    key = next(iter(terms))
    scale = terms[key] / expected[key]
    return scale > 0 and rhs == scale * bound and all(terms[k] == scale * expected[k] for k in terms)


def verify(source, record):
    assert record["sourceSHA256"] == hashlib.sha256(source).hexdigest()
    names, integers, constraints, rounded, objective, constant, maximize = parse_mps(source.decode())
    mapping = record["columns"]
    assert set(mapping) == names and len(set(mapping.values())) == len(mapping)
    assert all(mapping[name].startswith("Integer/" if name in integers else "Real/") for name in names)
    inverse = {coordinate: name for name, coordinate in mapping.items()}
    captured_objective = {k: number(v) for k, v in record["objective"].items()}
    extra = set(captured_objective) - set(inverse)
    # Exact MPS lowering may name its objective with one real equality coordinate.
    assert len(extra) <= 1
    auxiliary = next(iter(extra), None)
    assert auxiliary is None or auxiliary.startswith("Real/")

    def expand(terms, bound):
        result = {}
        for coordinate, coefficient in terms.items():
            if coordinate == auxiliary:
                result = add(result, objective, coefficient)
                bound -= coefficient * constant
            else:
                name = inverse[coordinate]
                result[name] = result.get(name, F(0)) + coefficient
        return clean(result), bound

    cost_terms, cost_rhs = expand(captured_objective, -number(record["constant"]))
    assert cost_terms == objective and cost_rhs == -constant and record["maximize"] == maximize
    rows = {}
    for row_id, row in record["rows"].items():
        terms = clean({k: number(v) for k, v in row["coefficients"].items()})
        bound = number(row["rhs"])
        expanded, rhs = expand(terms, bound)
        assert (not expanded and rhs >= 0) or any(multiple((expanded, rhs), original)
                                                     for original in constraints + rounded), row_id
        rows[row_id] = terms, bound

    def check_point(point):
        values = {name: number(point[coordinate]) for name, coordinate in mapping.items()}
        assert all(values[name].denominator == 1 for name in integers)
        assert all(sum(value * values[name] for name, value in terms.items()) <= rhs
                   for terms, rhs in constraints)
        source_value = constant + sum(value * values[name] for name, value in objective.items())
        if auxiliary:
            assert number(point[auxiliary]) == source_value
        return source_value

    point = record["point"]
    if point is not None:
        source_value = check_point(point)
    else:
        assert record["kind"] in {"refutation", "partial"}
    incumbent, value = record.get("incumbent"), record.get("value")
    assert (incumbent is None) == (value is None)
    if incumbent is not None:
        assert check_point(incumbent) == number(value)

    steps = 0

    def check_proof(proof, bound=None):
        target = None
        if bound is not None:
            sign = F(1) if maximize else F(-1)
            target = clean({key: sign * value for key, value in captured_objective.items()}), sign * (bound - number(record["constant"]))

        def derive(proof, assumptions, depth=0):
            nonlocal steps
            steps += 1
            assert steps <= 100_000 and depth <= 128
            kind, *args = proof
            if kind == "row":
                return rows[args[0]]
            if kind == "assume":
                return assumptions[args[0], args[1]]
            assert kind == "sum"
            terms, rhs = {}, F(0)
            for weight, child in args[0]:
                weight = number(weight)
                assert weight >= 0
                coefficients, bound = derive(child, assumptions, depth + 1)
                terms, rhs = add(terms, coefficients, weight), rhs + weight * bound
            return terms, rhs

        def coverage(proof, assumptions, depth=0):
            nonlocal steps
            steps += 1
            assert steps <= 100_000 and depth <= 128
            if proof[0] == "int":
                _, coordinate, threshold, below, above = proof
                assert coordinate in inverse and inverse[coordinate] in integers
                threshold = number(threshold)
                assert threshold.denominator == 1
                coverage(below, {**assumptions, (coordinate, True): ({coordinate: F(1)}, threshold)}, depth + 1)
                coverage(above, {**assumptions, (coordinate, False): ({coordinate: F(-1)}, -threshold - 1)}, depth + 1)
            else:
                coefficients, rhs = derive(proof, assumptions)
                assert (not coefficients and rhs < 0) or (target is not None and coefficients == target[0] and rhs <= target[1])

        coverage(proof, {})

    if record["kind"] == "ray":
        direction = {name: number(record["ray"].get(coordinate, 0)) for name, coordinate in mapping.items()}
        assert set(record["ray"]) <= set(inverse) | extra
        assert all(direction[name].denominator == 1 for name in integers)
        assert all(sum(value * direction[name] for name, value in terms.items()) <= 0 for terms, _ in constraints)
        improvement = sum(value * direction[name] for name, value in objective.items())
        assert improvement > 0 if maximize else improvement < 0
        if auxiliary:
            assert number(record["ray"].get(auxiliary, 0)) == improvement
    elif record["kind"] == "optimal":
        bound = number(record["bound"])
        assert source_value == bound
        check_proof(record["proof"], bound)
    elif record["kind"] == "refutation":
        check_proof(record["proof"])
    else:
        assert record["kind"] == "partial" and record["proof"] is None and record["bound"] is None
    global_bound, global_proof = record.get("globalBound"), record.get("globalProof")
    assert (global_bound is None) == (global_proof is None)
    if global_bound is not None:
        check_proof(global_proof, number(global_bound))
    return {"sourceSHA256": record["sourceSHA256"], "claim": record["kind"], "checked": True,
            "checker": "mps-linear-fraction-v2", "globalBound": global_bound}


if __name__ == "__main__":
    assert len(sys.argv) == 3, "usage: verify_mps_linear_proof.py original.mps capture.json"
    print(json.dumps(verify(Path(sys.argv[1]).read_bytes(), json.loads(Path(sys.argv[2]).read_text()))))
