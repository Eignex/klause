"""Independently evaluate exact named polynomial integer and real witnesses at original sources."""

import argparse
import hashlib
import json
import pathlib
import re

import z3

from verify_source_results import expressions, number, rational, smt_variables, symbol


def sexpr(expression):
    if isinstance(expression, str):
        return expression
    parts = ['(']
    pending = [(iter(expression), True)]
    while pending:
        iterator, first = pending[-1]
        try:
            item = next(iterator)
        except StopIteration:
            parts.append(')')
            pending.pop()
            continue
        if not first:
            parts.append(' ')
        pending[-1] = (iterator, False)
        if isinstance(item, str):
            parts.append(item)
        else:
            parts.append('(')
            pending.append((iter(item), True))
    return ''.join(parts)


def root_polynomial(expression):
    if isinstance(expression, str):
        assert expression == 'x' or re.fullmatch(r'-?\d+', expression), 'invalid root polynomial atom'
        return
    assert expression, 'empty root polynomial'
    operator, *arguments = expression
    if operator == '^':
        assert len(arguments) == 2 and arguments[0] == 'x', 'invalid root polynomial power'
        assert isinstance(arguments[1], str) and re.fullmatch(r'\d+', arguments[1]), 'invalid root exponent'
    else:
        assert operator in ('+', '-', '*') and arguments, 'unsupported root polynomial operation'
        for argument in arguments:
            root_polynomial(argument)


def exact_value(expression):
    if isinstance(expression, list) and expression and expression[0] == 'root-obj':
        assert len(expression) == 3, 'invalid root coordinate'
        root_polynomial(expression[1])
        assert isinstance(expression[2], str) and re.fullmatch(r'[1-9]\d*', expression[2]), 'invalid root index'
        probe = z3.Real('_klause_nra_coordinate')
        equality = z3.parse_smt2_string(
            '(assert (= _klause_nra_coordinate ' + sexpr(expression) + '))',
            decls={'_klause_nra_coordinate': probe},
        )[0]
        value = equality.arg(1)
        assert z3.is_algebraic_value(value) or z3.is_rational_value(value), 'coordinate is not an exact scalar'
        return value
    return rational(number(expression))


def source_formula(text):
    commands = expressions(text)
    variables = smt_variables(text)
    assert all(str(value.sort()) in ('Int', 'Real', 'Bool') for value in variables.values()), 'unsupported source sorts'
    semantic, query_seen = [], False
    for command in commands:
        assert command, 'empty source command'
        operator = command[0]
        if operator in ('declare-const', 'declare-fun', 'define-fun', 'assert'):
            assert not query_seen, 'source changes after query'
            if operator == 'define-fun':
                assert len(command) == 5 and command[3] in ('Int', 'Real', 'Bool'), 'unsupported function result sort'
                assert all(len(parameter) == 2 and parameter[1] in ('Int', 'Real', 'Bool')
                           for parameter in command[2]), 'unsupported function argument sort'
            semantic.append(command)
        elif operator in ('check-sat', 'check-sat-assuming'):
            assert not query_seen, 'multiple source queries'
            query_seen = True
            if operator == 'check-sat':
                assert len(command) == 1, 'invalid source query'
            else:
                assert len(command) == 2 and isinstance(command[1], list), 'invalid assumptions'
                for assumption in command[1]:
                    literal = assumption
                    if isinstance(literal, list):
                        assert len(literal) == 2 and literal[0] == 'not', 'nonliteral source assumption'
                        literal = literal[1]
                    assert isinstance(literal, str) and symbol(literal) in variables, 'unknown source assumption'
                    assert z3.is_bool(variables[symbol(literal)]), 'nonboolean source assumption'
                    semantic.append(['assert', assumption])
        else:
            assert operator in ('set-logic', 'set-info', 'set-option', 'get-model', 'get-value', 'exit'), \
                'unsupported source command ' + operator
    constraints = list(z3.parse_smt2_string('\n'.join(map(sexpr, semantic))))
    known = {value.get_id() for value in variables.values()}
    pending = list(constraints)
    seen = set()
    allowed = {z3.Z3_OP_TRUE, z3.Z3_OP_FALSE, z3.Z3_OP_AND, z3.Z3_OP_OR, z3.Z3_OP_NOT,
               z3.Z3_OP_XOR, z3.Z3_OP_IMPLIES, z3.Z3_OP_ITE, z3.Z3_OP_EQ, z3.Z3_OP_DISTINCT,
               z3.Z3_OP_LE, z3.Z3_OP_LT, z3.Z3_OP_GE, z3.Z3_OP_GT,
               z3.Z3_OP_ADD, z3.Z3_OP_SUB, z3.Z3_OP_UMINUS, z3.Z3_OP_MUL, z3.Z3_OP_TO_REAL}
    while pending:
        node = pending.pop()
        if node.get_id() in seen:
            continue
        seen.add(node.get_id())
        assert z3.is_app(node), 'quantified or bound source expression'
        if z3.is_rational_value(node) or z3.is_int_value(node):
            continue
        kind = node.decl().kind()
        if kind == z3.Z3_OP_UNINTERPRETED:
            assert node.get_id() in known and node.num_args() == 0, 'undeclared source expression'
        elif kind == z3.Z3_OP_DIV:
            denominator = z3.simplify(node.arg(1))
            assert z3.is_rational_value(denominator) and denominator.as_fraction() != 0, 'nonconstant source division'
        elif kind == z3.Z3_OP_POWER:
            exponent = z3.simplify(node.arg(1))
            assert z3.is_int_value(exponent) or z3.is_rational_value(exponent), 'nonconstant source exponent'
            value = number(expressions('(value ' + exponent.sexpr() + ')')[0][1])
            assert value.denominator == 1 and value >= 0, 'nonpolynomial source exponent'
        else:
            assert kind in allowed, 'unsupported source expression ' + str(node.decl())
        pending.extend(node.children())
    return variables, constraints


def witness_values(witness, variables):
    parsed = expressions(witness)
    assert len(parsed) == 1, 'invalid witness envelope'
    definitions = parsed[0]
    if definitions and definitions[0] == 'model':
        definitions = definitions[1:]
    values = {}
    for definition in definitions:
        assert len(definition) == 5 and definition[0] == 'define-fun' and definition[2] == [], 'invalid witness definition'
        _, name, _, sort, expression = definition
        name = symbol(name)
        assert name in variables and name not in values, 'unknown or duplicate model variable'
        assert str(variables[name].sort()) == sort, 'model sort differs from source declaration'
        if sort == 'Bool':
            assert expression in ('true', 'false'), 'nonliteral Boolean coordinate'
            values[name] = z3.BoolVal(expression == 'true')
        elif sort == 'Int':
            value = number(expression)
            assert value.denominator == 1, 'nonintegral integer coordinate'
            values[name] = z3.IntVal(value.numerator)
        else:
            values[name] = exact_value(expression)
    assert values.keys() == variables.keys(), 'incomplete source model'
    return values


def contains_zero_power(expression, cache):
    pending = [(expression, False)]
    while pending:
        node, expanded = pending.pop()
        identity = node.get_id()
        if identity in cache:
            continue
        children = node.children()
        if not expanded:
            pending.append((node, True))
            pending.extend((child, False) for child in children)
            continue
        zero_power = False
        if node.decl().kind() == z3.Z3_OP_POWER:
            exponent = z3.simplify(node.arg(1))
            zero_power = (z3.is_int_value(exponent) and exponent.as_long() == 0) or \
                (z3.is_rational_value(exponent) and exponent.as_fraction() == 0)
        cache[identity] = zero_power or any(cache[child.get_id()] for child in children)
    return cache[expression.get_id()]


def check_witness(text, witness):
    variables, constraints = source_formula(text)
    values = witness_values(witness, variables)
    model = z3.Model()
    for name, variable in variables.items():
        model.update_value(variable, values[name])
    zero_power_cache = {}
    replacements = None
    for index, constraint in enumerate(constraints):
        if contains_zero_power(constraint, zero_power_cache):
            # Model evaluation supplies a default for undefined zero powers even without completion.
            if replacements is None:
                replacements = [(variable, values[name]) for name, variable in variables.items()]
            evaluated = z3.simplify(z3.substitute(constraint, *replacements))
        else:
            evaluated = z3.simplify(model.eval(constraint, model_completion=False))
        assert not z3.is_false(evaluated), f'original source assertion {index} is false'
        assert z3.is_true(evaluated), f'original source assertion {index} did not evaluate exactly'
    return {'sourceSha256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
            'checker': 'z3-' + z3.get_version_string(),
            'witnessCheck': 'all original assertions evaluated true with exact algebraic coordinates',
            'coordinates': len(values), 'assertions': len(constraints)}


def compare_verdict(text, expected, timeout_ms=120000):
    assert expected in ('sat', 'unsat'), 'unsupported comparison verdict'
    _, constraints = source_formula(text)
    solver = z3.Solver()
    solver.set(timeout=timeout_ms)
    solver.add(*constraints)
    verdict = solver.check()
    reason = '; reason: ' + solver.reason_unknown() if verdict == z3.unknown else ''
    assert str(verdict) == expected, f'reference comparison expected {expected}, got {verdict}{reason}'
    return {'checker': 'z3-' + z3.get_version_string(), 'referenceComparison': str(verdict),
            'proofCertificateChecked': False}


def check_bundle(cases, timeout_ms=120000):
    results = []
    for case in cases:
        raw = case['source'].encode('utf-8')
        assert hashlib.sha256(raw).hexdigest() == case['hash'], 'original source hash mismatch'
        result = {key: value for key, value in case.items() if key not in ('source', 'witness')}
        if case['feasible'] is True:
            result.update(check_witness(case['source'], case['witness']))
        elif case['feasible'] is False:
            result.update(compare_verdict(case['source'], 'unsat', timeout_ms))
        else:
            result['check'] = 'incomplete verdict carries no claim'
        results.append(result)
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path, nargs='?')
    parser.add_argument('--bundle', type=pathlib.Path)
    parser.add_argument('--witness', type=pathlib.Path)
    parser.add_argument('--expected', choices=('sat', 'unsat'))
    parser.add_argument('--timeout-ms', type=int, default=120000)
    args = parser.parse_args()
    if args.bundle is not None:
        if args.source is not None or args.witness is not None or args.expected is not None:
            parser.error('--bundle cannot be combined with a single-source check')
        raw = args.bundle.read_bytes()
        print(json.dumps({'inputHash': hashlib.sha256(raw).hexdigest(),
                          'checkerHash': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                          'checks': check_bundle(json.loads(raw), args.timeout_ms)}, indent=2))
        raise SystemExit(0)
    if args.source is None:
        parser.error('supply a source or --bundle')
    if args.witness is None and args.expected is None:
        parser.error('supply --witness or --expected')
    source_bytes = args.source.read_bytes()
    text = source_bytes.decode('utf-8')
    result = {'sourceSha256': hashlib.sha256(source_bytes).hexdigest()}
    if args.witness is not None:
        result.update(check_witness(text, args.witness.read_text()))
    if args.expected is not None:
        result.update(compare_verdict(text, args.expected, args.timeout_ms))
    print(json.dumps(result, indent=2))
