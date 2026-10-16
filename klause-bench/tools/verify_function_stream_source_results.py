"""Check saved function-source SMT publications with exact ground symbolic evaluation."""

import argparse
from fractions import Fraction
import hashlib
import json
import pathlib

from verify_transcendental_source_results import Unavailable, check_witness, evaluate, expressions, symbol


def model_expression(node):
    if isinstance(node, str):
        return node
    op, *arguments = node
    if isinstance(op, list):
        if len(op) != 3 or op[:2] != ['_', 'klause-real']:
            raise Unavailable('unsupported indexed model operator')
        name = op[2]
        if name == 'pi' and not arguments:
            return 'pi'
        if name not in ('exp', 'log', 'sin', 'cos') or len(arguments) != 1:
            raise Unavailable('unsupported symbolic model operator or arity')
        op = name
    return [op] + [model_expression(argument) for argument in arguments]


def coordinate_text(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, Fraction):
        return str(value)
    if value == ('pi',):
        return 'pi'
    return value[0] + '(' + ','.join(coordinate_text(term) for term in value[1:]) + ')'


def model_coordinates(source, model):
    declarations = {}
    for command in expressions(source):
        if command[0] == 'declare-const':
            declarations[symbol(command[1])] = command[2]
        elif command[0] == 'declare-fun':
            assert not command[2], 'nonconstant source function'
            declarations[symbol(command[1])] = command[3]
    parsed = expressions(model)
    assert len(parsed) == 1, 'require one complete model'
    definitions = parsed[0]
    if definitions and definitions[0] == 'model':
        definitions = definitions[1:]
    values = {}
    for definition in definitions:
        assert len(definition) == 5 and definition[0] == 'define-fun' and not definition[2], 'invalid model definition'
        name = symbol(definition[1])
        assert name in declarations and name not in values, 'foreign or repeated model declaration'
        assert definition[3] == declarations[name], 'model declaration sort differs from source'
        values[name] = coordinate_text(evaluate(model_expression(definition[4]), {}))
    check_witness(source, values)
    return values


def check_stream(source, output, interrupted=False, minimum_witnesses=1):
    assert minimum_witnesses > 0, 'minimum witness count must be positive'
    model_lines = None
    witnesses = []
    for line in output.splitlines():
        if line.startswith('; objective=') or line.startswith('; optimizationStatus='):
            raise ValueError('function witness checking does not certify optimization publications')
        if line in ('(', '(model'):
            assert model_lines is None, 'model before a preceding complete publication'
            model_lines = [line]
        elif model_lines is not None:
            model_lines.append(line)
            if line != ')':
                continue
            model = '\n'.join(model_lines)
            model_coordinates(source, model)
            witnesses.append({'witnessSha256': hashlib.sha256(model.encode()).hexdigest()})
            model_lines = None
    assert interrupted or model_lines is None, 'incomplete final publication'
    assert len(witnesses) >= minimum_witnesses, 'missing complete checked witnesses'
    return {
        'sourceSha256': hashlib.sha256(source.encode()).hexdigest(),
        'stdoutSha256': hashlib.sha256(output.encode()).hexdigest(),
        'checker': 'exact-ground-functions',
        'witnesses': witnesses,
        'allOriginalAssertions': True,
        'truncatedPublication': model_lines is not None,
        'solverSearchQueries': 0,
        'proofCertificateChecked': False,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('stdout', type=pathlib.Path)
    parser.add_argument('--interrupted', action='store_true')
    parser.add_argument('--minimum-witnesses', type=int, default=1)
    args = parser.parse_args()
    print(json.dumps(check_stream(args.source.read_text(), args.stdout.read_text(),
                                 args.interrupted, args.minimum_witnesses), indent=2))
