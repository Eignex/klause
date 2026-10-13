"""Check saved exact function witnesses using rational arithmetic and symbolic identities.

This ground checker does not decide transcendental sources or verify UNSAT proofs.
Algebraic roots and identities outside the supported rewrite rules remain unavailable.
"""

import argparse
from fractions import Fraction
import hashlib
import json
import pathlib
import re

from verify_source_results import expressions, symbol


class Unavailable(ValueError):
    pass


class InvalidDomain(ValueError):
    pass


def sign(value):
    if isinstance(value, Fraction):
        return (value > 0) - (value < 0)
    op, *arguments = value
    if op in ('exp', 'pi'):
        return 1
    if op == 'log' and isinstance(arguments[0], Fraction):
        return sign(arguments[0] - 1)
    raise Unavailable('symbolic sign is not certified')


def real(op, arguments):
    if any(isinstance(value, bool) for value in arguments):
        raise ValueError('Boolean arithmetic operand')
    if op == 'sum':
        terms = []
        constant = Fraction(0)
        for value in arguments:
            if isinstance(value, Fraction):
                constant += value
            elif value[0] == 'sum':
                terms.extend(value[1:])
            else:
                terms.append(value)
        if constant:
            terms.append(constant)
        return tuple(['sum'] + sorted(terms, key=repr)) if len(terms) > 1 else (terms[0] if terms else Fraction(0))
    if op == 'product':
        constant, terms = Fraction(1), []
        for value in arguments:
            if isinstance(value, Fraction):
                constant *= value
            else:
                terms.append(value)
        if constant == 0:
            return Fraction(0)
        if constant != 1:
            terms.append(constant)
        return tuple(['product'] + sorted(terms, key=repr)) if len(terms) > 1 else (terms[0] if terms else Fraction(1))
    if op == 'power':
        value, exponent = arguments
        if not isinstance(exponent, Fraction) or exponent.denominator != 1:
            raise Unavailable('nonintegral power')
        exponent = int(exponent)
        if exponent < 0 and sign(value) == 0:
            raise InvalidDomain('negative power of zero')
        if isinstance(value, Fraction):
            return value ** exponent
        return Fraction(1) if exponent == 0 else value if exponent == 1 else ('power', value, exponent)
    if len(arguments) != 1:
        raise ValueError('invalid function arity')
    value = arguments[0]
    if op == 'exp':
        if value == 0:
            return Fraction(1)
        if isinstance(value, tuple) and value[0] == 'log':
            return value[1]
    elif op == 'log':
        if sign(value) <= 0:
            raise InvalidDomain('log argument is not positive')
        if value == 1:
            return Fraction(0)
        if isinstance(value, tuple) and value[0] == 'exp':
            return value[1]
    elif op in ('sin', 'cos'):
        if value == 0:
            return Fraction(0 if op == 'sin' else 1)
        if value == ('pi',):
            return Fraction(0 if op == 'sin' else -1)
    else:
        raise Unavailable('unsupported function ' + op)
    return (op, value)


def coordinate(text):
    if isinstance(text, bool):
        return text
    if re.fullmatch(r'-?\d+(?:/\d+)?', text):
        return Fraction(text)
    if text == 'pi':
        return ('pi',)
    match = re.fullmatch(r'(exp|log|sin|cos|sum|product|power)\((.*)\)', text)
    if not match:
        raise Unavailable('unsupported exact witness representation')
    arguments, depth, start = [], 0, 0
    body = match[2]
    for index, char in enumerate(body):
        depth += (char == '(') - (char == ')')
        if depth < 0:
            raise ValueError('unbalanced witness')
        if char == ',' and depth == 0:
            arguments.append(coordinate(body[start:index]))
            start = index + 1
    if depth != 0:
        raise ValueError('unbalanced witness')
    arguments.append(coordinate(body[start:]))
    return real(match[1], arguments)


def compare(left, right):
    if left == right:
        return 0
    if isinstance(left, Fraction) and isinstance(right, Fraction):
        return sign(left - right)
    if right == 0:
        return sign(left)
    if left == 0:
        return -sign(right)
    if isinstance(left, tuple) and isinstance(right, tuple) and left[0] == right[0] and left[0] in ('exp', 'log'):
        return compare(left[1], right[1])
    raise Unavailable('symbolic comparison is not certified')


def evaluate(node, values):
    if isinstance(node, str):
        if node in ('true', 'false'):
            return node == 'true'
        if symbol(node) in values:
            return values[symbol(node)]
        if node == 'pi':
            return ('pi',)
        if re.fullmatch(r'-?\d+(?:\.\d+)?', node):
            return Fraction(node)
        raise Unavailable('unsupported source atom ' + node)
    op, *terms = node
    if op == 'ite':
        condition = evaluate(terms[0], values)
        if not isinstance(condition, bool):
            raise ValueError('nonboolean condition')
        return evaluate(terms[1 if condition else 2], values)
    arguments = [evaluate(term, values) for term in terms]
    if op in ('exp', 'log', 'sin', 'cos'):
        return real(op, arguments)
    if op in ('+', '*'):
        return real('sum' if op == '+' else 'product', arguments)
    if op == '-':
        if len(arguments) == 1:
            return real('product', [Fraction(-1), arguments[0]])
        if len(arguments) == 2 and arguments[0] == arguments[1]:
            return Fraction(0)
        return real('sum', [arguments[0]] + [real('product', [Fraction(-1), value]) for value in arguments[1:]])
    if op in ('^', 'pow'):
        return real('power', arguments)
    if op == '/':
        return real('product', [arguments[0], real('power', [arguments[1], Fraction(-1)])])
    if op == 'to_real':
        return arguments[0]
    if op in ('and', 'or', 'not', '=>'):
        if not all(isinstance(value, bool) for value in arguments):
            raise ValueError('nonboolean logical operand')
        return {'and': lambda: all(arguments), 'or': lambda: any(arguments),
                'not': lambda: not arguments[0], '=>': lambda: not arguments[0] or arguments[1]}[op]()
    if op in ('=', '<', '<=', '>', '>='):
        if any(isinstance(value, bool) for value in arguments):
            if op != '=' or not all(isinstance(value, bool) for value in arguments):
                raise ValueError('mixed comparison sorts')
            return all(value == arguments[0] for value in arguments)
        comparisons = [compare(left, right) for left, right in zip(arguments, arguments[1:])]
        predicates = {'=': lambda value: value == 0, '<': lambda value: value < 0,
                      '<=': lambda value: value <= 0, '>': lambda value: value > 0, '>=': lambda value: value >= 0}
        return all(predicates[op](value) for value in comparisons)
    raise Unavailable('unsupported source operation ' + op)


def check_witness(text, witness):
    declarations, assertions = {}, []
    queried = False
    for command in expressions(text):
        op, *arguments = command
        if op in ('declare-const', 'declare-fun'):
            if queried:
                raise Unavailable('incremental source')
            name, sort = (arguments if op == 'declare-const' else (arguments[0], arguments[2]))
            if op == 'declare-fun' and arguments[1]:
                raise Unavailable('nonconstant declaration')
            name = symbol(name)
            if name in declarations or sort not in ('Real', 'Int', 'Bool'):
                raise ValueError('invalid declaration')
            declarations[name] = sort
        elif op == 'assert':
            if queried:
                raise Unavailable('incremental source')
            assertions.append(arguments[0])
        elif op == 'check-sat':
            if queried:
                raise Unavailable('multiple queries')
            queried = True
        elif op not in ('set-logic', 'set-info', 'set-option', 'get-model', 'get-value', 'exit'):
            raise Unavailable('unsupported source command ' + op)
    if set(witness) != set(declarations):
        raise ValueError('missing or foreign witness declarations')
    values = {name: coordinate(value) for name, value in witness.items()}
    for name, sort in declarations.items():
        value = values[name]
        if sort == 'Bool' and not isinstance(value, bool) or sort != 'Bool' and isinstance(value, bool):
            raise ValueError('witness sort mismatch')
        if sort == 'Int' and (not isinstance(value, Fraction) or value.denominator != 1):
            raise ValueError('integer witness is not integral')
    for assertion in assertions:
        if evaluate(assertion, values) is not True:
            raise ValueError('witness violates source assertion')


def check_bundle(sources, results):
    original = {source['name']: source for source in sources}
    if len(original) != len(sources):
        raise ValueError('duplicate source names')
    for source in sources:
        if hashlib.sha256(source['text'].encode()).hexdigest() != source['sha256']:
            raise ValueError('source hash mismatch')
    checked = []
    for result in results:
        source = original[result['name']]
        if result['sourceSha256'] != source['sha256']:
            raise ValueError('result source hash mismatch')
        row = {key: result[key] for key in ('name', 'control', 'seed', 'repetition', 'kind')}
        row['proofChecked'] = False
        row['witnessCheck'] = 'absent'
        if result['kind'] == 'Sat':
            try:
                check_witness(source['text'], result['witness'])
                row['witnessCheck'] = 'checked'
            except Unavailable as error:
                row['witnessCheck'] = 'unavailable'
                row['reason'] = str(error)
        checked.append(row)
    return checked


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources', type=pathlib.Path)
    parser.add_argument('results', type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(check_bundle(json.loads(args.sources.read_text()), json.loads(args.results.read_text())), indent=2))
