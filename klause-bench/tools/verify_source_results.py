"""Check source verdicts, exact proofs and MPS tolerance witnesses with original source arithmetic."""

import fractions
import hashlib
import io
import json
import math
import pathlib
import re
import shlex
import tarfile
import urllib.request

import z3


SMT_CORPUS_SUITES = frozenset(("smtlib-qfidl", "smtlib-qflia", "smtlib-qflira", "smtlib-qflra", "smtlib-qfrdl"))
SMT_SUITES = SMT_CORPUS_SUITES | {"smtlib-core"}
MPS_CORPUS_SUITES = frozenset(("miplib2003", "miplib2010", "miplib2017", "orlib"))
SOURCE_SUITES = SMT_SUITES | MPS_CORPUS_SUITES | {"mps-core", "miplib3"}


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
    pending, visited, substitutions = [expression], set(), []
    while pending:
        node = pending.pop()
        identity = node.get_id()
        if identity in visited:
            continue
        visited.add(identity)
        if z3.is_const(node) and node.decl().kind() == z3.Z3_OP_UNINTERPRETED:
            name = node.decl().name()
            if name in variables and node.eq(variables[name]):
                value = values[name]
                substitutions.append((node, z3.IntVal(value.numerator) if z3.is_int(node) else rational(value)))
        else:
            pending.extend(node.children())
    return z3.simplify(z3.substitute(expression, *substitutions))


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


def source(case, archive, source_root=None):
    suite, name = case["problem"]["suite"], case["record"]["problem"]
    if suite not in SOURCE_SUITES:
        raise ValueError(f"unsupported source suite {suite}")
    if suite in (SMT_CORPUS_SUITES | MPS_CORPUS_SUITES) and source_root is None:
        raise ValueError(f"source suite {suite} requires --source-root with hash-named original bytes")
    if source_root is not None:
        digest = case["record"]["sourceHashes"]["model"]
        assert re.fullmatch(r"[0-9a-f]{64}", digest), "invalid source hash"
        data = (source_root / (digest + ".txt")).read_bytes()
    elif suite in ("mps-core", "smtlib-core"):
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
    assert hashlib.sha256(data).hexdigest() == case["record"]["sourceHashes"]["model"], "source hash differs from record"
    return data.decode("latin-1")


def exact_mode(case):
    command = case["record"].get("command")
    if command is None:
        return case["arm"].endswith("-exact")
    arguments = shlex.split(command)
    if "--" in arguments:
        arguments = arguments[:arguments.index("--")]
    return "--exact" in arguments


def require_verdict(solver, expected, problem, purpose):
    actual = solver.check()
    if actual != expected:
        reason = f'; reason: {solver.reason_unknown()}' if actual == z3.unknown else ''
        raise AssertionError(f'{problem}: {purpose} expected {expected}, got {actual}{reason}')


def binary_source_terms(expression, variables):
    expression = z3.simplify(expression)
    if expression.get_id() in variables:
        return {expression.get_id(): fractions.Fraction(1)}, fractions.Fraction(0)
    if z3.is_int_value(expression) or z3.is_rational_value(expression):
        return {}, fractions.Fraction(str(expression))
    kind = expression.decl().kind()
    if kind == z3.Z3_OP_TO_REAL:
        return binary_source_terms(expression.arg(0), variables)
    if kind == z3.Z3_OP_UMINUS:
        terms, constant = binary_source_terms(expression.arg(0), variables)
        return {key: -value for key, value in terms.items()}, -constant
    if kind in (z3.Z3_OP_ADD, z3.Z3_OP_SUB):
        terms, constant = {}, fractions.Fraction(0)
        for index, child in enumerate(expression.children()):
            child_terms, child_constant = binary_source_terms(child, variables)
            sign = -1 if kind == z3.Z3_OP_SUB and index > 0 else 1
            constant += sign * child_constant
            for key, value in child_terms.items():
                terms[key] = terms.get(key, 0) + sign * value
        return {key: value for key, value in terms.items() if value}, constant
    if kind == z3.Z3_OP_MUL:
        terms, constant = {}, fractions.Fraction(1)
        for child in expression.children():
            child_terms, child_constant = binary_source_terms(child, variables)
            if terms and child_terms:
                raise ValueError('nonlinear binary source query')
            if child_terms:
                terms = {key: constant * value for key, value in child_terms.items()}
            else:
                terms = {key: child_constant * value for key, value in terms.items()}
            constant *= child_constant
        return terms, constant
    raise ValueError('unsupported binary source term')


def binary_source_weights(terms):
    scale = math.lcm(*(value.denominator for value in terms.values()))
    weights = {key: int(value * scale) for key, value in terms.items()}
    if sum(abs(value) for value in weights.values()) > 2147483647:
        raise ValueError('binary source query exceeds pseudo-Boolean integer width')
    return weights, scale


def binary_source_predicate(expression, variables):
    expression = z3.simplify(expression)
    if z3.is_true(expression) or z3.is_false(expression):
        return expression
    kind = expression.decl().kind()
    if kind in (z3.Z3_OP_AND, z3.Z3_OP_OR, z3.Z3_OP_NOT, z3.Z3_OP_IMPLIES):
        constructor = {z3.Z3_OP_AND: z3.And, z3.Z3_OP_OR: z3.Or,
                       z3.Z3_OP_NOT: z3.Not, z3.Z3_OP_IMPLIES: z3.Implies}[kind]
        return constructor(*(binary_source_predicate(child, variables) for child in expression.children()))
    if kind not in (z3.Z3_OP_LE, z3.Z3_OP_GE, z3.Z3_OP_LT, z3.Z3_OP_GT, z3.Z3_OP_EQ):
        raise ValueError('unsupported binary source predicate')
    left, right = expression.children()
    terms, constant = binary_source_terms(left - right, variables)
    weights, scale = binary_source_weights(terms)
    bound = -constant * scale
    literals = []
    for key, weight in weights.items():
        if weight < 0:
            literals.append((z3.Not(variables[key]), -weight))
            bound -= weight
        else:
            literals.append((variables[key], weight))
    if kind == z3.Z3_OP_EQ:
        if bound.denominator != 1:
            return z3.BoolVal(False)
        threshold, constructor = int(bound), z3.PbEq
    elif kind in (z3.Z3_OP_LE, z3.Z3_OP_LT):
        threshold = math.floor(bound) if kind == z3.Z3_OP_LE else math.ceil(bound) - 1
        constructor = z3.PbLe
    else:
        threshold = math.ceil(bound) if kind == z3.Z3_OP_GE else math.floor(bound) + 1
        constructor = z3.PbGe
    maximum = sum(weight for _, weight in literals)
    if constructor == z3.PbEq and not 0 <= threshold <= maximum:
        return z3.BoolVal(False)
    if constructor == z3.PbLe and (threshold < 0 or threshold >= maximum):
        return z3.BoolVal(threshold >= maximum)
    if constructor == z3.PbGe and (threshold <= 0 or threshold > maximum):
        return z3.BoolVal(threshold <= 0)
    if not literals:
        return z3.BoolVal(threshold == 0)
    return constructor(literals, threshold)


def source_query_solver(constraints, variables, is_smt, objective):
    bounds = {variable.get_id(): set() for variable in variables.values()}
    if not is_smt:
        for constraint in constraints:
            comparison = z3.simplify(constraint)
            kind = comparison.decl().kind()
            if kind not in (z3.Z3_OP_GE, z3.Z3_OP_LE):
                continue
            left, right = comparison.children()
            if z3.is_int_value(left) or z3.is_rational_value(left):
                left, right = right, left
                kind = z3.Z3_OP_LE if kind == z3.Z3_OP_GE else z3.Z3_OP_GE
            if left.decl().kind() == z3.Z3_OP_TO_REAL:
                left = left.arg(0)
            if left.get_id() not in bounds or not (z3.is_int_value(right) or z3.is_rational_value(right)):
                continue
            value = fractions.Fraction(str(right))
            if kind == z3.Z3_OP_GE and value == 0:
                bounds[left.get_id()].add('lower')
            if kind == z3.Z3_OP_LE and value == 1:
                bounds[left.get_id()].add('upper')
    binary = not is_smt and bool(variables) and all(
        z3.is_int(variable) and bounds[variable.get_id()] == {'lower', 'upper'}
        for variable in variables.values())
    query = lambda expression: expression
    if binary:
        booleans = {variable.get_id(): z3.FreshBool('source_binary') for variable in variables.values()}
        try:
            if objective is not None:
                weights, _ = binary_source_weights(binary_source_terms(objective, booleans)[0])
                binary = len(set(weights.values())) <= 1
            if binary:
                translated = [binary_source_predicate(constraint, booleans) for constraint in constraints]
        except ValueError:
            binary = False
        else:
            if binary:
                constraints = translated
                query = lambda expression: binary_source_predicate(expression, booleans)
    solver = z3.SolverFor('QF_FD') if binary else z3.Solver()
    solver.set(timeout=120000)
    solver.add(*constraints)
    return solver, 'finite binary' if binary else 'general', query


def check(cases, source_root=None, progress=None, witness_only=False):
    archive, results, cache, sources = [], [], {}, {}
    for case in cases:
        assert case["status"] == "DONE" and case.get("record"), "incomplete case"
        record = case["record"]
        if witness_only:
            assert record["feasible"] is True and record.get("finalWitness") is not None, \
                "witness-only checking requires a reported feasible witness"
        exact = exact_mode(case)
        tolerance_optimum = record.get("stats", {}).get("optimizationStatus") == "tolerance-optimum"
        if tolerance_optimum:
            assert case["problem"]["suite"] not in SMT_SUITES and not exact, \
                "tolerance optimum requires default MPS semantics"
            assert record["feasible"] is True and record["proven"] and record["kind"] == "optimize", \
                "tolerance optimum has inconsistent result fields"
        reported_objective = record.get("exactObjective")
        if reported_objective is None:
            reported_objective = record.get("objective")
        source_key = (case["problem"]["suite"], record["problem"], record["gitSha"], record["sourceHashes"]["model"])
        if source_key not in sources:
            sources[source_key] = source(case, archive, source_root)
        text = sources[source_key]
        key = (record["sourceHashes"]["model"], record["kind"], record["feasible"], str(reported_objective),
               record.get("exactObjective") is not None, record["proven"], record.get("maximize"),
               record.get("finalWitness"), exact, tolerance_optimum)
        if key in cache:
            results.append({"index": case["index"], "check": cache[key]})
            if progress is not None:
                progress(results[-1])
            continue
        result = {"sourceHashVerified": True, "reportedFeasible": record["feasible"],
                  "reportedProven": record["proven"], "witness": "absent"}
        if witness_only:
            result["verificationScope"] = "witness-only"
            result["proofCertificateChecked"] = False
        if record["feasible"] is None:
            assert not record["proven"] and reported_objective is None
            result["verdictCheck"] = "no claimed verdict"
        else:
            is_smt = case["problem"]["suite"] in SMT_SUITES
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
            assert reported_objective is None or objective is not None, "reported objective is absent from source"
            solver, result['sourceQueryEncoding'], query = source_query_solver(constraints, variables, is_smt, objective)
            if record["feasible"] is False:
                assert record["proven"]
                require_verdict(solver, z3.unsat, record["problem"], 'source infeasibility')
                result["verdictCheck"] = "source unsat"
            else:
                witness = record.get("finalWitness")
                objective_witness_verified = False
                if witness is not None and is_smt:
                    witness_scope = record["proven"] and record["kind"] == "optimize"
                    if witness_scope:
                        solver.push()
                    solver.add(*smt_pins(witness, variables))
                    require_verdict(solver, z3.sat, record["problem"], 'complete source witness feasibility')
                    if reported_objective is not None:
                        assert objective is not None
                        value = rational(reported_objective)
                        solver.add(objective != (-value if maximize else value))
                        require_verdict(solver, z3.unsat, record["problem"], 'model objective differs from reported objective')
                    if witness_scope:
                        solver.pop()
                    result["witness"] = "exact source feasible"
                elif witness is None:
                    require_verdict(solver, z3.sat, record["problem"], 'source feasibility')
                result["verdictCheck"] = "source sat"
                if witness is not None and not is_smt:
                    values = mps_values(witness, variables)
                    exact_feasible = all(z3.is_true(mps_point(row, variables, values)) for row in constraints)
                    result["witness"] = "exact source feasible" if exact_feasible else "not exact source feasible"
                    if reported_objective is not None:
                        assert mps_objective_matches(-objective if maximize else objective, variables, values,
                                                     reported_objective, exact,
                                                     record.get("exactObjective") is not None,
                                                     witness), "point objective differs from reported objective"
                        result["objectiveCheck"] = "source point objective matches"
                        objective_witness_verified = exact_feasible and z3.is_true(z3.simplify(
                            mps_point(objective, variables, values) ==
                            (-rational(reported_objective) if maximize else rational(reported_objective))))
                    if exact:
                        assert exact_feasible, record["problem"]
                    else:
                        tolerance_rows, _, _, _ = mps(text, tolerance=True)
                        accepted = all(z3.is_true(mps_point(row, variables, values)) for row in tolerance_rows)
                        result["toleranceAccepted"] = accepted
                        assert accepted, "MPS point exceeds source tolerance"
                    if not exact_feasible:
                        require_verdict(solver, z3.sat, record["problem"], 'source feasibility')
                if tolerance_optimum:
                    result["toleranceOptimalityCheck"] = "unavailable: native tolerance certificate not captured"
                elif record["proven"] and record["kind"] == "optimize" and not witness_only:
                    assert reported_objective is not None and objective is not None
                    value = rational(reported_objective)
                    if maximize:
                        value = -value
                    if not objective_witness_verified:
                        solver.push()
                        solver.add(query(objective == value))
                        require_verdict(solver, z3.sat, record["problem"], 'claimed source objective feasibility')
                        solver.pop()
                    solver.push()
                    solver.add(query(objective < value))
                    require_verdict(solver, z3.unsat, record["problem"], 'strictly better source objective exclusion')
                    solver.pop()
                    result["optimalityCheck"] = "strictly better source objective unsat"
        cache[key] = result
        results.append({"index": case["index"], "check": result})
        if progress is not None:
            progress(results[-1])
    return results


if __name__ == '__main__':
    import argparse
    import gzip
    import lzma

    parser = argparse.ArgumentParser(description='Check lab verdicts, models and finite optimum claims against exact source constraints.')
    parser.add_argument('cases', type=pathlib.Path)
    parser.add_argument('--records-root', type=pathlib.Path, help='Directory containing cases/<index>/record.json; use these raw records as authority.')
    parser.add_argument('--source-root', type=pathlib.Path, help='Original uncompressed sources named <record sourceHashes.model>.txt; required for SMT corpus suites, otherwise fetch the canonical core or MIPLIB source.')
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--progress', type=pathlib.Path, help='Flush completed per-record checks as JSON lines, preserving the checked prefix if a later record fails.')
    parser.add_argument('--witness-only', action='store_true', help='Check actual feasible witnesses and objectives, preserving reported proof claims without checking them.')
    args = parser.parse_args()
    opener = gzip.open if args.cases.suffix == '.gz' else lzma.open if args.cases.suffix == '.xz' else open
    with opener(args.cases, 'rt') as stream:
        cases = json.load(stream)
    if args.records_root:
        for case in cases:
            record_path = args.records_root / 'cases' / str(case['index']) / 'record.json'
            case['record'] = json.loads(record_path.read_text())
            assert case['problem']['problem'] == case['record']['problem']
    if args.progress:
        with args.progress.open('w') as stream:
            def progress(entry):
                stream.write(json.dumps(entry, sort_keys=True) + '\n')
                stream.flush()
            checks = check(cases, args.source_root, progress, witness_only=args.witness_only)
    else:
        checks = check(cases, args.source_root, witness_only=args.witness_only)
    result = json.dumps(checks, indent=2, sort_keys=True) + '\n'
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end='')
