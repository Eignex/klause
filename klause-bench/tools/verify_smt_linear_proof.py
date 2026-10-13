#!/usr/bin/env python3
"""Independently check unguarded affine SMT claims, strict approaches and split proofs.

Only declarations, affine assertions and one objective are accepted. Unsupported
SMT constructs and source mappings fail closed. Uses standard-library arithmetic.
"""

import hashlib
import json
import math
import re
import sys
from fractions import Fraction as F
from pathlib import Path

from verify_mps_linear_proof import add, clean, number


def parse_smt(text):
    tokens = re.findall(r"\(|\)|[^\s()]+", re.sub(r";[^\n]*", "", text))
    assert len(tokens) <= 100_000
    position = 0

    def read(depth=0):
        nonlocal position
        assert depth <= 128 and position < len(tokens)
        token = tokens[position]
        position += 1
        if token != "(":
            assert token != ")"
            return token
        result = []
        while position < len(tokens) and tokens[position] != ")":
            result.append(read(depth + 1))
        assert position < len(tokens)
        position += 1
        return result

    declarations, rows, objective, maximize = {}, [], None, False

    def affine(expression):
        if isinstance(expression, str):
            if expression in declarations:
                assert declarations[expression] != "Bool"
                return {expression: F(1)}, F(0)
            return {}, number(expression)
        operator, *arguments = expression
        if operator == "to_real":
            assert len(arguments) == 1
            terms, constant = affine(arguments[0])
            assert constant.denominator == 1 and all(declarations[key] == "Int" for key in terms)
            return terms, constant
        values = [affine(argument) for argument in arguments]
        if operator in {"+", "-"}:
            assert values
            terms, constant = values[0]
            if operator == "-" and len(values) == 1:
                return {key: -value for key, value in terms.items()}, -constant
            for coefficients, offset in values[1:]:
                scale = F(-1) if operator == "-" else F(1)
                terms, constant = add(terms, coefficients, scale), constant + scale * offset
            return terms, constant
        assert operator in {"*", "/"} and len(values) == 2
        (left, offset), (right, other) = values
        if operator == "/":
            assert not right and other
            return {key: value / other for key, value in left.items()}, offset / other
        assert not left or not right
        return add({key: value * other for key, value in left.items()}, right, offset), offset * other

    def assertion(expression):
        operator, *arguments = expression
        if operator == "and":
            for argument in arguments:
                assertion(argument)
            return
        assert operator in {"<=", "<", ">=", ">", "="} and len(arguments) == 2
        (left, offset), (right, other) = map(affine, arguments)
        coefficients, bound = add(left, right, F(-1)), other - offset
        if operator in {"<=", "<", "="}:
            rows.append((coefficients, bound, operator == "<"))
        if operator in {">=", ">", "="}:
            rows.append(({key: -value for key, value in coefficients.items()}, -bound, operator == ">"))

    while position < len(tokens):
        command, *arguments = read()
        if command == "set-logic":
            assert len(arguments) == 1
        elif command == "declare-fun":
            name, parameters, sort = arguments
            assert parameters == [] and name not in declarations and sort in {"Bool", "Int", "Real"}
            declarations[name] = sort
        elif command == "assert":
            assert len(arguments) == 1
            assertion(arguments[0])
        elif command in {"minimize", "maximize"}:
            assert objective is None and len(arguments) == 1
            objective = affine(arguments[0])
            maximize = command == "maximize"
        else:
            assert command == "check-sat" and not arguments
    assert objective is not None
    for name, sort in declarations.items():
        if sort == "Bool":
            rows.extend([({name: F(-1)}, F(0), False), ({name: F(1)}, F(1), False)])
    rounded = []
    for terms, bound, strict in rows:
        if terms and all(declarations[key] == "Int" for key in terms):
            denominator = math.lcm(*(value.denominator for value in terms.values()))
            divisor = math.gcd(*(abs(int(value * denominator)) for value in terms.values()))
            scale = F(denominator, divisor)
            rounded.append(({key: value * scale for key, value in terms.items()},
                            F(math.ceil(bound * scale) - 1 if strict else math.floor(bound * scale)), False))
    return declarations, rows, rounded, objective, maximize


def implied(row, original):
    terms, bound, strict = row
    expected, rhs, original_strict = original
    if terms.keys() != expected.keys():
        return False
    if terms:
        key = next(iter(terms))
        scale = terms[key] / expected[key]
        if scale <= 0 or any(terms[name] != scale * expected[name] for name in terms):
            return False
    else:
        scale = F(1)
    return bound >= scale * rhs and (not strict or original_strict or bound > scale * rhs)


def verify(source, record, condition_source=None):
    assert record["sourceSHA256"] == hashlib.sha256(source).hexdigest()
    declarations, source_rows, rounded, (objective, constant), maximize = parse_smt(source.decode())
    mapping = record["columns"]
    assert set(mapping) == set(declarations) and len(set(mapping.values())) == len(mapping)
    prefixes = {"Bool": "Boolean", "Int": "Integer", "Real": "Real"}
    assert all(mapping[name].startswith(prefixes[sort] + "/") for name, sort in declarations.items())
    inverse = {coordinate: name for name, coordinate in mapping.items()}
    condition = json.loads(condition_source) if condition_source is not None else {}
    assert set(condition) <= {"bools", "lower", "upper"}
    assert record.get("conditionSHA256") == (
        hashlib.sha256(condition_source).hexdigest() if condition_source is not None else None)
    bool_pins, assumptions = {}, {}
    for name, pinned in condition.get("bools", {}).items():
        assert declarations[name] == "Bool" and type(pinned) is bool
        bool_pins[mapping[name]] = pinned
        source_rows.extend([({name: F(1)}, F(int(pinned)), False),
                            ({name: F(-1)}, F(-int(pinned)), False)])
    for side in ("lower", "upper"):
        for name, raw in condition.get(side, {}).items():
            assert declarations[name] in {"Int", "Real"}
            bound = number(raw)
            upper = side == "upper"
            sign = F(1) if upper else F(-1)
            assumptions[mapping[name], upper] = ({mapping[name]: sign}, sign * bound, False)
            source_rows.append(({name: sign}, sign * bound, False))
    cost = clean({coordinate: number(value) for coordinate, value in record["objective"].items()})
    assert {inverse[key]: value for key, value in cost.items()} == objective
    assert number(record["constant"]) == constant and record["maximize"] == maximize

    def substitute(terms, bound, pins):
        terms = terms.copy()
        for coordinate, pinned in pins.items():
            bound -= terms.pop(coordinate, F(0)) * int(pinned)
        return terms, bound

    rows = {}
    guards = {}
    for row_id, row in record["rows"].items():
        terms = clean({coordinate: number(value) for coordinate, value in row["coefficients"].items()})
        pins = row.get("guards", {})
        assert all(declarations[inverse[key]] == "Bool" and type(value) is bool for key, value in pins.items())
        reduced, rhs = substitute(terms, number(row["rhs"]), pins)
        candidate = {inverse[key]: value for key, value in reduced.items()}, rhs, row["strict"]
        assert type(row["strict"]) is bool
        originals = []
        for coefficients, bound, strict in source_rows + rounded:
            original, bound = substitute(coefficients, bound, {inverse[key]: value for key, value in pins.items()})
            originals.append((original, bound, strict))
        assert any(implied(candidate, original) for original in originals) or (
            not reduced and (candidate[1] > 0 or candidate[1] == 0 and not candidate[2])), row_id
        rows[row_id] = terms, number(row["rhs"]), candidate[2]
        guards[row_id] = pins

    def point_values(point, closure=False):
        assert set(point) == set(inverse)
        values = {}
        for name, sort in declarations.items():
            raw = point[mapping[name]]
            if sort == "Bool":
                assert type(raw) is bool
                values[name] = F(int(raw))
            else:
                values[name] = number(raw)
                assert sort != "Int" or values[name].denominator == 1
        for terms, bound, strict in source_rows:
            lhs = sum(value * values[name] for name, value in terms.items())
            assert lhs < bound if strict and not closure else lhs <= bound
        return values

    def value(values):
        return constant + sum(coefficient * values[name] for name, coefficient in objective.items())

    point = record["point"]
    values = point_values(point) if point is not None else None
    assert values is not None or record["kind"] in {"partial", "refutation"}
    incumbent, incumbent_value = record["incumbent"], record["value"]
    assert (incumbent is None) == (incumbent_value is None)
    if incumbent is not None:
        assert value(point_values(incumbent)) == number(incumbent_value)
    steps = 0

    def proof_check(proof, bound=None, strict=False):
        sign = F(1) if maximize else F(-1)
        target = {key: sign * coefficient for key, coefficient in cost.items()}
        rhs = None if bound is None else sign * (bound - constant)
        if rhs is not None:
            target, rhs = substitute(target, rhs, bool_pins)

        def step(depth):
            nonlocal steps
            steps += 1
            assert steps <= 100_000 and depth <= 128

        def derive(proof, assumptions, bools, depth):
            step(depth)
            kind, *arguments = proof
            if kind == "row":
                assert len(arguments) == 1
                identifier = arguments[0]
                assert all(bools.get(key) == pinned and key in bools for key, pinned in guards[identifier].items())
                terms, bound, strict = rows[identifier]
                terms, bound = substitute(terms, bound, bools)
                return terms, bound, strict
            if kind == "assume":
                coordinate, upper = arguments
                assert type(upper) is bool
                return assumptions[coordinate, upper]
            assert kind == "sum" and len(arguments) == 1
            terms, bound, strict = {}, F(0), False
            for weight, child in arguments[0]:
                weight = number(weight)
                assert weight >= 0
                coefficients, offset, child_strict = derive(child, assumptions, bools, depth + 1)
                terms, bound = add(terms, coefficients, weight), bound + weight * offset
                strict |= weight > 0 and child_strict
            return terms, bound, strict

        def coverage(proof, assumptions, bools, depth=0):
            step(depth)
            if proof[0] == "bool":
                _, identifier, when_false, when_true = proof
                coordinate = f"Boolean/{identifier}"
                assert type(identifier) is int and coordinate in inverse and coordinate not in bools
                coverage(when_false, assumptions, {**bools, coordinate: False}, depth + 1)
                coverage(when_true, assumptions, {**bools, coordinate: True}, depth + 1)
            elif proof[0] == "int":
                _, coordinate, threshold, below, above = proof
                assert declarations[inverse[coordinate]] == "Int"
                threshold = number(threshold)
                assert threshold.denominator == 1
                def branch(upper, rhs):
                    key = coordinate, upper
                    if key in assumptions:
                        rhs = min(rhs, assumptions[key][1])
                    return {**assumptions, key: ({coordinate: F(1) if upper else F(-1)}, rhs, False)}
                coverage(below, branch(True, threshold), bools, depth + 1)
                coverage(above, branch(False, -threshold - 1), bools, depth + 1)
            else:
                terms, bound, leaf_strict = derive(proof, assumptions, bools, depth + 1)
                contradiction = not terms and (bound < 0 or bound == 0 and leaf_strict)
                assert contradiction or (rhs is not None and terms == target and
                                         (bound < rhs or bound == rhs and (not strict or leaf_strict)))
        coverage(proof, assumptions, bool_pins)

    kind = record["kind"]
    if kind in {"optimal", "limit"}:
        bound = number(record["bound"])
        proof_check(record["proof"], bound, strict=kind == "limit")
        if kind == "optimal":
            assert value(values) == bound and record["endpoint"] is None
        else:
            endpoint = point_values(record["endpoint"], closure=True)
            assert value(endpoint) == bound
            assert all(endpoint[name] == values[name] for name, sort in declarations.items() if sort != "Real")
            assert value(values) < bound if maximize else value(values) > bound
    elif kind == "ray":
        assert set(record["ray"]) <= set(inverse)
        direction = {name: number(record["ray"].get(coordinate, 0)) for name, coordinate in mapping.items()}
        assert all(direction[name] == 0 for name, sort in declarations.items() if sort == "Bool")
        assert all(direction[name].denominator == 1 for name, sort in declarations.items() if sort == "Int")
        assert all(sum(coefficient * direction[name] for name, coefficient in terms.items()) <= 0
                   for terms, _, _ in source_rows)
        improvement = sum(coefficient * direction[name] for name, coefficient in objective.items())
        assert improvement > 0 if maximize else improvement < 0
    elif kind == "refutation":
        proof_check(record["proof"])
    else:
        assert kind == "partial" and record["proof"] is None and record["bound"] is None
    global_bound, global_proof = record["globalBound"], record["globalProof"]
    assert (global_bound is None) == (global_proof is None)
    if global_bound is not None:
        proof_check(global_proof, number(global_bound))
    return {"sourceSHA256": record["sourceSHA256"], "claim": kind, "checked": True,
            "conditionSHA256": record.get("conditionSHA256"),
            "checker": "smt-linear-fraction-v2", "globalBound": global_bound}


if __name__ == "__main__":
    assert len(sys.argv) in {3, 4}, "usage: verify_smt_linear_proof.py original.smt2 capture.json [condition.json]"
    print(json.dumps(verify(Path(sys.argv[1]).read_bytes(), json.loads(Path(sys.argv[2]).read_text()),
                            Path(sys.argv[3]).read_bytes() if len(sys.argv) == 4 else None)))
