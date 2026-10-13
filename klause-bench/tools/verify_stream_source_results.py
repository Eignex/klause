"""Check every exact arithmetic incumbent in CLI SMT output against its original source."""

import argparse
import hashlib
import json
import pathlib
import re

import z3

from verify_nra_source_results import check_witness, exact_value, expressions, sexpr, source_formula, witness_values
from verify_source_results import symbol


def source_objective(source):
    commands = expressions(source)
    objectives = [command for command in commands if command[0] in ('minimize', 'maximize')]
    assert len(objectives) <= 1, 'require at most one original objective'
    objective = objectives[0] if objectives else None
    semantic_source = '\n'.join(sexpr(command) for command in commands
                                if command[0] not in ('minimize', 'maximize', 'get-objectives'))
    variables, _ = source_formula(semantic_source)
    if objective is None:
        return semantic_source, variables, None, None
    assert len(objective) == 2, 'invalid original objective'
    declarations = [command for command in commands
                    if command[0] in ('declare-const', 'declare-fun', 'define-fun')]
    names = {symbol(command[1]) for command in declarations}
    marker_name = '_klause_stream_objective'
    while marker_name in names:
        marker_name += '_'
    marker = z3.Real(marker_name)
    equality = z3.parse_smt2_string(
        '\n'.join(sexpr(command) for command in declarations)
        + '\n(assert (= ' + marker_name + ' ' + sexpr(objective[1]) + '))',
        decls={marker_name: marker},
    )[0]
    return semantic_source, variables, equality.arg(1), objective[0]


def check_stream(source, output, interrupted=False, minimum_witnesses=1):
    assert minimum_witnesses > 0, 'minimum witness count must be positive'
    semantic_source, variables, objective, direction = source_objective(source)
    pending_cost = None
    model_lines = None
    prior_cost = None
    witnesses = []
    terminal = False
    for line in output.splitlines():
        if line.startswith('; optimizationStatus='):
            assert objective is not None, 'optimization status without an objective'
            assert not interrupted, 'terminal optimization status in interrupted output'
            terminal = True
        elif line.startswith('; objective='):
            assert objective is not None, 'cost without an original objective'
            assert pending_cost is None and model_lines is None, 'cost without a preceding complete witness'
            assert not terminal, 'incumbent after terminal optimization status'
            value_text = line.split('=', 1)[1].strip()
            fraction = re.fullmatch(r'(-?\d+)/(-?\d+)', value_text)
            if fraction:
                value_text = '(/ ' + fraction[1] + ' ' + fraction[2] + ')'
            parsed = expressions('(value ' + value_text + ')')
            assert len(parsed) == 1 and len(parsed[0]) == 2, 'invalid objective value'
            pending_cost = exact_value(parsed[0][1])
        elif line in ('(', '(model'):
            assert model_lines is None, 'model before a preceding complete witness'
            assert objective is None or pending_cost is not None, 'model without exact cost'
            assert not terminal, 'model after terminal optimization status'
            model_lines = [line]
        elif model_lines is not None:
            model_lines.append(line)
            if line != ')':
                continue
            witness = '\n'.join(model_lines)
            check_witness(semantic_source, witness)
            if objective is not None:
                values = witness_values(witness, variables)
                evaluated = z3.simplify(z3.substitute(objective,
                    *[(variable, values[name]) for name, variable in variables.items()]))
                assert z3.is_true(z3.simplify(evaluated == pending_cost)), 'incorrect original objective'
                if prior_cost is not None:
                    improvement = pending_cost < prior_cost if direction == 'minimize' else pending_cost > prior_cost
                    assert z3.is_true(z3.simplify(improvement)), 'incumbent does not strictly improve in original direction'
            witnesses.append({'objective': pending_cost.sexpr() if pending_cost is not None else None,
                              'witnessSha256': hashlib.sha256(witness.encode()).hexdigest()})
            prior_cost = pending_cost
            pending_cost = None
            model_lines = None
    assert interrupted or (pending_cost is None and model_lines is None), 'incomplete final publication'
    assert len(witnesses) >= minimum_witnesses, 'missing complete checked witnesses'
    return {
        'sourceSha256': hashlib.sha256(source.encode()).hexdigest(),
        'stdoutSha256': hashlib.sha256(output.encode()).hexdigest(),
        'checker': 'z3-' + z3.get_version_string(),
        'direction': direction, 'witnesses': witnesses,
        'allOriginalAssertions': True, 'exactOriginalObjective': objective is not None,
        'strictImprovements': len(witnesses) - 1 if objective is not None else 0,
        'truncatedPublication': pending_cost is not None or model_lines is not None,
        'proofCertificateChecked': False,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('stdout', type=pathlib.Path)
    parser.add_argument('--interrupted', action='store_true')
    parser.add_argument('--minimum-witnesses', type=int, default=1)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    stdout_bytes = args.stdout.read_bytes()
    result = check_stream(source_bytes.decode(), stdout_bytes.decode(), args.interrupted, args.minimum_witnesses)
    result['sourceSha256'] = hashlib.sha256(source_bytes).hexdigest()
    result['stdoutSha256'] = hashlib.sha256(stdout_bytes).hexdigest()
    print(json.dumps(result, indent=2))
