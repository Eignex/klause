"""Check source verdicts, exact proofs and MPS tolerance witnesses with original source arithmetic."""

import fractions
import hashlib
import io
import json
import math
import pathlib
import re
import tarfile
import urllib.request

import z3


def expressions(text):
    tokens = re.findall(r';[^\n]*|\(|\)|\|[^|]*\||"(?:""|[^\"])*"|[^\s();]+', text)
    stack, result = [], []
    for token in tokens:
        if token.startswith(";"):
            continue
        if token == "(":
            stack.append([])
        elif token == ")":
            assert stack, "unbalanced expression"
            expression = stack.pop()
            (stack[-1] if stack else result).append(expression)
        else:
            assert stack, "unexpected top-level atom"
            stack[-1].append(token)
    assert not stack, "unbalanced expression"
    return result


def symbol(name):
    return name[1:-1] if name.startswith("|") and name.endswith("|") else name


def smt_variables(text):
    variables = {}
    for expression in expressions(text):
        if expression[0] == "declare-const":
            _, name, sort = expression
        elif expression[0] == "declare-fun":
            _, name, arguments, sort = expression
            assert arguments == [], "only constant declarations are supported"
        else:
            continue
        name = symbol(name)
        assert name not in variables
        constructors = {"Int": z3.Int, "Real": z3.Real, "Bool": z3.Bool}
        assert sort in constructors, f"unsupported source sort {sort}"
        variables[name] = constructors[sort](name)
    return variables


def number(expression):
    if isinstance(expression, str):
        assert re.fullmatch(r'-?\d+(?:\.\d+)?', expression), "nonliteral model value"
        return fractions.Fraction(expression)
    if expression[0] == "-" and len(expression) == 2:
        return -number(expression[1])
    if expression[0] == "/" and len(expression) == 3:
        return number(expression[1]) / number(expression[2])
    raise ValueError("unsupported model value")


def smt_pins(witness, variables):
    parsed = expressions(witness)
    assert len(parsed) == 1
    definitions = parsed[0]
    if definitions and definitions[0] == "model":
        definitions = definitions[1:]
    values = {}
    for definition in definitions:
        assert len(definition) == 5 and definition[0] == "define-fun" and definition[2] == []
        _, name, _, sort, value = definition
        name = symbol(name)
        assert name in variables and name not in values, "unknown or duplicate model variable"
        variable = variables[name]
        assert str(variable.sort()) == sort, "model sort differs from source declaration"
        if sort == "Bool":
            assert value in ("true", "false")
            values[name] = z3.BoolVal(value == "true")
        else:
            value = number(value)
            assert sort != "Int" or value.denominator == 1, "fractional integer model value"
            values[name] = rational(value)
    assert values.keys() == variables.keys(), "incomplete source model"
    return [variable == values[name] for name, variable in variables.items()]


def rational(value):
    value = fractions.Fraction(str(value).replace("D", "E"))
    return z3.RealVal(f"{value.numerator}/{value.denominator}")


def mps(text, tolerance=False):
    section, objective_row, maximize, marker = None, None, False, False
    rows, coefficients, rhs, ranges, lower, upper, indicators = {}, {}, {}, {}, {}, {}, {}
    columns, integer = set(), set()
    vector_names = {}
    for raw in text.splitlines():
        parts = raw.split()
        if not parts or raw.startswith("*"):
            continue
        if raw[0] not in " \t":
            section = parts[0]
            if section not in ("NAME", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "INDICATORS", "OBJSENSE", "ENDATA"):
                raise ValueError(f"unsupported MPS section {section}")
            if section != "NAME" and len(parts) != 1:
                raise ValueError(f"unsupported inline MPS section {section}")
            continue
        if section == "OBJSENSE":
            assert parts[0] in ("MIN", "MAX", "MINIMIZE", "MAXIMIZE")
            maximize = parts[0].startswith("MAX")
        elif section == "ROWS":
            kind, row = parts
            assert kind in ("N", "L", "G", "E")
            assert row not in rows, "duplicate MPS row"
            rows[row] = kind
            if kind == "N" and objective_row is None:
                objective_row = row
        elif section == "COLUMNS":
            if "'MARKER'" in parts:
                assert parts[-1] in ("'INTORG'", "'INTEND'"), "unsupported integer marker"
                marker = "'INTORG'" in parts
                continue
            column = parts[0]
            assert len(parts) >= 3 and len(parts) % 2 == 1, "invalid MPS column"
            columns.add(column)
            if marker:
                integer.add(column)
            for row, value in zip(parts[1::2], parts[2::2]):
                coefficients.setdefault(row, []).append((column, value))
        elif section in ("RHS", "RANGES"):
            values = parts[1:] if len(parts) % 2 else parts
            vector = parts[0] if len(parts) % 2 else vector_names.get(section, "")
            if vector_names.setdefault(section, vector) != vector:
                raise ValueError(f"multiple MPS {section} vectors are unsupported")
            if not values or len(values) % 2:
                raise ValueError(f"invalid MPS {section} vector")
            target = rhs if section == "RHS" else ranges
            for row, value in zip(values[::2], values[1::2]):
                assert row not in target
                target[row] = value
        elif section == "BOUNDS":
            kind, vector, column = parts[:3]
            if vector_names.setdefault(section, vector) != vector:
                raise ValueError("multiple MPS BOUNDS vectors are unsupported")
            value = parts[3] if len(parts) > 3 else None
            columns.add(column)
            if kind in ("LO", "LI"):
                lower[column] = value
            elif kind in ("UP", "UI"):
                upper[column] = value
                if fractions.Fraction(value.replace('D', 'E')) < 0 and column not in lower:
                    lower[column] = None
            elif kind == "FX":
                lower[column] = upper[column] = value
            elif kind == "FR":
                lower[column] = upper[column] = None
            elif kind == "MI":
                lower[column] = None
            elif kind == "PL":
                upper[column] = None
            elif kind == "BV":
                lower[column], upper[column] = "0", "1"
                integer.add(column)
            else:
                raise ValueError(f"unsupported bound {kind}")
            if kind in ("LI", "UI"):
                integer.add(column)
        elif section == "INDICATORS":
            assert parts[0] == "IF"
            indicators[parts[1]] = (parts[2], parts[3])
        else:
            raise ValueError(f"unsupported MPS data section {section}")
    assert all(row in rows for row in coefficients), "unknown MPS row"
    variables = {column: (z3.Int(column) if column in integer else z3.Real(column)) for column in columns}
    def slack(bound, magnitude=0):
        if not tolerance:
            return 0
        scale = rational(max(fractions.Fraction(1), abs(fractions.Fraction(str(bound).replace('D', 'E')))))
        return rational('1e-7') * z3.If(magnitude > scale, magnitude, scale)

    constraints = []
    for column, variable in variables.items():
        lo, hi = lower.get(column, "0"), upper.get(column)
        if column in integer and column not in lower and column not in upper:
            hi = "1"
        if lo is not None:
            constraints.append(variable >= rational(lo) - slack(lo))
        if hi is not None:
            constraints.append(variable <= rational(hi) + slack(hi))
    terms_by_row = {}
    for row, entries in coefficients.items():
        summed = {}
        for column, value in entries:
            summed[column] = summed.get(column, 0) + fractions.Fraction(value.replace('D', 'E'))
        terms_by_row[row] = [rational(value) * variables[column] for column, value in summed.items()]
    activities = {row: z3.Sum(terms) for row, terms in terms_by_row.items()}
    for row, kind in rows.items():
        if kind == "N":
            continue
        activity, bound = activities.get(row, z3.RealVal(0)), rational(rhs.get(row, "0"))
        span = fractions.Fraction(ranges[row].replace('D', 'E')) if row in ranges else None
        magnitude = z3.Sum([z3.Abs(term) for term in terms_by_row.get(row, [])])
        assertions = []
        def above(side):
            return activity >= side - slack(z3.simplify(side), magnitude)

        def below(side):
            return activity <= side + slack(z3.simplify(side), magnitude)

        if kind in ("E", "G"):
            assertions.append(above(bound + (rational(span) if kind == "E" and span is not None and span < 0 else 0)))
        if kind in ("E", "L"):
            assertions.append(below(bound + (rational(span) if kind == "E" and span is not None and span > 0 else 0)))
        if span is not None and kind == "L":
            assertions.append(above(bound - rational(abs(span))))
        if span is not None and kind == "G":
            assertions.append(below(bound + rational(abs(span))))
        for assertion in assertions:
            if row in indicators:
                column, value = indicators[row]
                assertion = z3.Implies(variables[column] == rational(value), assertion)
            constraints.append(assertion)
    objective = activities.get(objective_row, z3.RealVal(0)) - rational(rhs.get(objective_row, "0"))
    return constraints, -objective if maximize else objective, variables, maximize


def mps_values(witness, variables):
    tokens = witness.split()
    assert tokens and tokens[0] == 'v', "invalid MPS witness"
    values = {}
    for token in tokens[1:]:
        assert token.count('=') == 1, "invalid MPS coordinate"
        name, value = token.split('=')
        assert name in variables and name not in values, "unknown or duplicate MPS coordinate"
        assert re.fullmatch(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|[+-]?\d+/\d+', value), "invalid MPS value"
        value = fractions.Fraction(value)
        assert not z3.is_int(variables[name]) or value.denominator == 1, "fractional integer MPS coordinate"
        values[name] = value
    assert values.keys() == variables.keys(), "incomplete MPS witness"
    return values


def mps_point(expression, variables, values):
    return z3.simplify(z3.substitute(expression, *[(variable, z3.IntVal(values[name].numerator) if z3.is_int(variable)
                                                 else rational(values[name]))
                                                for name, variable in variables.items()]))


def mps_objective_matches(objective, variables, values, reported, exact_mode, exact_objective, witness):
    candidates = [mps_point(objective, variables, values)]
    if not exact_mode and '/' not in witness:
        try:
            # Float witnesses render shortest decimals; their objective uses the binary64 values they encode.
            binary_values = {name: value if z3.is_int(variables[name]) else fractions.Fraction.from_float(float(value))
                             for name, value in values.items()}
            candidates.append(mps_point(objective, variables, binary_values))
        except (OverflowError, ValueError):
            pass
    value = rational(reported)
    for candidate in candidates:
        if z3.is_true(z3.simplify(candidate == value)):
            return True
        if not exact_mode and not exact_objective:
            try:
                expected = float(fractions.Fraction(str(candidate)))
                actual = float(reported)
                if math.isfinite(expected) and math.isfinite(actual) and expected == actual:
                    return True
            except (OverflowError, ValueError):
                pass
    return False


def source(case, archive):
    suite, name = case["problem"]["suite"], case["record"]["problem"]
    if suite in ("mps-core", "smtlib-core"):
        format_name, extension = ("mps", "mps") if suite == "mps-core" else ("smtlib", "smt2")
        path = f"klause-bench/smoke-corpus/{format_name}/{name}.{extension}"
        revision = case["record"]["gitSha"]
        with urllib.request.urlopen(f"https://raw.githubusercontent.com/Eignex/klause/{revision}/{path}", timeout=60) as response:
            data = response.read()
    elif suite == "miplib3":
        if not archive:
            with urllib.request.urlopen("https://miplib2010.zib.de/miplib3/miplib3.tar.gz", timeout=60) as response:
                archive.append(tarfile.open(fileobj=io.BytesIO(response.read()), mode="r:gz"))
        member = next(member for member in archive[0].getmembers()
                      if member.isfile() and member.name.split("/")[-1] in (name, name + ".mps"))
        data = archive[0].extractfile(member).read()
    else:
        raise ValueError(f"unsupported source suite {suite}")
    assert hashlib.sha256(data).hexdigest() == case["record"]["sourceHashes"]["model"]
    return data.decode("latin-1")


def check(cases):
    archive, results, cache, sources = [], [], {}, {}
    for case in cases:
        assert case["status"] == "DONE" and case.get("record"), "incomplete case"
        record = case["record"]
        reported_objective = record.get("exactObjective")
        if reported_objective is None:
            reported_objective = record.get("objective")
        source_key = (case["problem"]["suite"], record["problem"], record["gitSha"], record["sourceHashes"]["model"])
        if source_key not in sources:
            sources[source_key] = source(case, archive)
        text = sources[source_key]
        key = (record["sourceHashes"]["model"], record["kind"], record["feasible"], str(reported_objective),
               record["proven"], record.get("maximize"), record.get("finalWitness"), case["arm"].endswith("-exact"))
        if key in cache:
            results.append({"index": case["index"], "check": cache[key]})
            continue
        result = {"sourceHashVerified": True, "reportedFeasible": record["feasible"],
                  "reportedProven": record["proven"], "witness": "absent"}
        if record["feasible"] is None:
            assert not record["proven"] and reported_objective is None
            result["verdictCheck"] = "no claimed verdict"
        else:
            is_smt = case["problem"]["suite"] == "smtlib-core"
            if is_smt:
                parsed = z3.Optimize()
                parsed.from_string(text)
                constraints = list(parsed.assertions())
                objectives = list(parsed.objectives())
                objective = objectives[0] if len(objectives) == 1 else None
                commands = [command for command in expressions(text) if command[0] in ("minimize", "maximize")]
                maximize = len(commands) == 1 and commands[0][0] == "maximize"
                assert maximize == record["maximize"], "objective orientation differs from source"
                variables = smt_variables(text)
            else:
                constraints, objective, variables, maximize = mps(text)
                assert maximize == record["maximize"]
            solver = z3.Solver()
            solver.set(timeout=120000)
            solver.add(*constraints)
            if record["feasible"] is False:
                assert record["proven"]
                assert solver.check() == z3.unsat, record["problem"]
                result["verdictCheck"] = "source unsat"
            else:
                assert solver.check() == z3.sat, record["problem"]
                result["verdictCheck"] = "source sat"
                witness = record.get("finalWitness")
                if witness and is_smt:
                    solver.push()
                    solver.add(*smt_pins(witness, variables))
                    assert solver.check() == z3.sat, record["problem"]
                    if reported_objective is not None:
                        assert objective is not None
                        value = rational(reported_objective)
                        solver.add(objective != (-value if maximize else value))
                        assert solver.check() == z3.unsat, "model objective differs from reported objective"
                    solver.pop()
                    result["witness"] = "exact source feasible"
                if witness is not None and not is_smt:
                    values = mps_values(witness, variables)
                    exact_mode = case["arm"].endswith("-exact")
                    exact_feasible = all(z3.is_true(mps_point(row, variables, values)) for row in constraints)
                    result["witness"] = "exact source feasible" if exact_feasible else "not exact source feasible"
                    if reported_objective is not None:
                        assert mps_objective_matches(-objective if maximize else objective, variables, values,
                                                     reported_objective, exact_mode,
                                                     record.get("exactObjective") is not None,
                                                     witness), "point objective differs from reported objective"
                        result["objectiveCheck"] = "source point objective matches"
                    if exact_mode:
                        assert exact_feasible, record["problem"]
                    else:
                        tolerance_rows, _, _, _ = mps(text, tolerance=True)
                        accepted = all(z3.is_true(mps_point(row, variables, values)) for row in tolerance_rows)
                        result["toleranceAccepted"] = accepted
                        assert accepted, "MPS point exceeds source tolerance"
                if record["proven"] and record["kind"] == "optimize":
                    assert reported_objective is not None and objective is not None
                    value = rational(reported_objective)
                    if maximize:
                        value = -value
                    solver.push()
                    solver.add(objective == value)
                    assert solver.check() == z3.sat, record["problem"]
                    solver.pop()
                    solver.push()
                    solver.add(objective < value)
                    assert solver.check() == z3.unsat, record["problem"]
                    solver.pop()
                    result["optimalityCheck"] = "strictly better source objective unsat"
        cache[key] = result
        results.append({"index": case["index"], "check": result})
    return results


if __name__ == '__main__':
    import argparse
    import gzip
    import lzma

    parser = argparse.ArgumentParser(description='Check lab verdicts, models and finite optimum claims against exact source constraints.')
    parser.add_argument('cases', type=pathlib.Path)
    parser.add_argument('--records-root', type=pathlib.Path, help='Directory containing cases/<index>/record.json; use these raw records as authority.')
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    opener = gzip.open if args.cases.suffix == '.gz' else lzma.open if args.cases.suffix == '.xz' else open
    with opener(args.cases, 'rt') as stream:
        cases = json.load(stream)
    if args.records_root:
        for case in cases:
            record_path = args.records_root / 'cases' / str(case['index']) / 'record.json'
            case['record'] = json.loads(record_path.read_text())
            assert case['problem']['problem'] == case['record']['problem']
    result = json.dumps(check(cases), indent=2, sort_keys=True) + '\n'
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end='')
