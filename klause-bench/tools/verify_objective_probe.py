"""Check ObjectiveRepresentationProbe records against their retained original SMT sources."""

import argparse
import hashlib
import json
import pathlib
import sys

import z3

from verify_nra_source_results import exact_value, sexpr, source_formula
from verify_source_results import expressions


def scalar(text):
    return exact_value(expressions('(value ' + text + ')')[0][1])


def checked_unsat(constraints, extra):
    solver = z3.Solver()
    solver.set(timeout=20000)
    solver.add(*constraints, extra)
    assert solver.check() == z3.unsat, 'original source inequality was not established'


def check_capture(payload, required):
    capture = payload.get('sourceCapture')
    if not required:
        return 'not requested'
    assert isinstance(capture, dict) and capture.get('captured') is True, 'required source capture is absent'
    native = payload['result']['status'] == 'Optimal'
    assert capture.get('nativeCompletion') is native, 'native capture role differs'
    if native and payload['encoding'] == 'linear':
        assert capture.get('copiedObjectiveReplay') == 'foreign original objective rejected', \
            'copied original objective replay was not rejected'
        condition = payload.get('condition', {})
        if condition.get('bools') or condition.get('ints'):
            assert capture.get('weakerConditionReplay') == 'weaker original condition rejected', \
                'weaker original condition replay was not rejected'
    assert capture.get('derivationCheck') in ('checked', 'unavailable'), 'invalid runtime derivation coverage'
    if capture['derivationCheck'] == 'unavailable':
        assert capture.get('unavailableReason'), 'unavailable derivation coverage lacks a reason'
    assert capture.get('exportedProof') == 'not captured', 'runtime capture is not a proof export'
    return 'runtime scope and replay assertions retained; no independent proof export check'


def check_record(root, path, require_incumbent, require_capture=False):
    payload = json.loads(path.read_text())
    identity, engine, encoding = path.stem.rsplit('.', 2)
    source = root / (identity + '.smt2')
    raw = source.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == identity == payload['sourceSha256'], 'source identity differs'
    assert (engine, encoding) == (payload['engine'], payload['encoding']), 'record identity differs'
    commands = expressions(raw.decode())
    operations = [command for command in commands if command[0] in ('minimize', 'maximize')]
    assert len(operations) == 1, 'expected one original source objective'
    direction, syntax = operations[0]
    semantic = '\n'.join(sexpr(command) for command in commands
                         if command[0] not in ('minimize', 'maximize'))
    variables, constraints = source_formula(semantic)
    cost_name = '__objective_probe_cost'
    while cost_name in raw.decode():
        cost_name += '_'
    cost_declaration = z3.Real(cost_name)
    objective = z3.parse_smt2_string(semantic + '\n(assert (= ' + cost_name + ' ' + sexpr(syntax) + '))',
                                    decls={cost_name: cost_declaration})[-1].arg(1)
    result = payload['result']
    runtime_capture = check_capture(payload, require_capture)
    assert result['sense'] == direction.upper(), 'original direction differs'
    witness_check = 'absent'
    if 'witness' in result:
        assert set(result['witness']) == set(variables), 'witness does not cover original declarations'
        substitutions = []
        for name, text in result['witness'].items():
            variable = variables[name]
            if z3.is_bool(variable):
                assert text in ('true', 'false'), 'invalid Boolean coordinate'
                value = z3.BoolVal(text == 'true')
            elif z3.is_int(variable):
                exact = scalar(text)
                assert z3.is_rational_value(exact) and exact.denominator_as_long() == 1, \
                    'integer coordinate is not exact and integral'
                value = z3.IntVal(exact.numerator_as_long())
            else:
                value = scalar(text)
            substitutions.append((variable, value))
        assert all(z3.is_true(z3.simplify(z3.substitute(row, substitutions))) for row in constraints), \
            'witness violates the original source'
        actual_cost = z3.substitute(objective, substitutions)
        assert z3.is_true(z3.simplify(actual_cost == scalar(result['objective']))), 'exact source objective differs'
        witness_check = 'checked'
    else:
        assert not require_incumbent, 'required original-source incumbent is absent'
    bound_check = 'absent'
    if 'bound' in result:
        bound = scalar(result['bound'])
        checked_unsat(constraints, objective < bound if direction == 'minimize' else objective > bound)
        if result['strict']:
            checked_unsat(constraints, objective == bound)
        bound_check = 'checked'
    status = result['status']
    if status == 'Optimal':
        assert witness_check == bound_check == 'checked' and not result['strict'], 'optimum is incomplete'
        assert z3.is_true(z3.simplify(scalar(result['objective']) == scalar(result['bound']))), \
            'optimum does not attain its bound'
    elif status == 'Infeasible':
        checked_unsat(constraints, z3.BoolVal(True))
    else:
        assert status in ('BestFound', 'Unknown', 'Unbounded', 'UnattainedLimit'), 'unknown result kind'
    if status == 'UnattainedLimit':
        assert bound_check == 'checked' and result['strict'], 'limit lacks a strict original-source bound'
    return dict(sourceSha256=identity, engine=engine, encoding=encoding, status=status,
                outputSha256=hashlib.sha256(path.read_bytes()).hexdigest(), witness=witness_check,
                bound=bound_check, refutation='checked' if status == 'Infeasible' else 'absent',
                approach='unavailable' if status == 'UnattainedLimit' else 'absent',
                trajectory='unavailable' if status == 'Unbounded' else 'absent',
                runtimeCapture=runtime_capture), result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=pathlib.Path)
    parser.add_argument('--engines', default='BACKTRACK,FIXED,LOCAL_SEARCH,ALNS,MIXED')
    parser.add_argument('--require-incumbent', action='store_true')
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    engines = set(args.engines.split(','))
    sources = {path.stem for path in args.root.glob('*.smt2')}
    assert sources and engines, 'empty input selection'
    paths = sorted(args.root.glob('*.*.*.json'))
    expected = {(source, engine, encoding) for source in sources for engine in engines
                for encoding in ('linear', 'quadratic')}
    actual = {tuple(path.stem.rsplit('.', 2)) for path in paths}
    assert actual == expected and len(paths) == len(expected), 'incomplete or unexpected comparison matrix'
    settings = args.root / 'settings.json'
    mode = json.loads(settings.read_text()).get('capture', 'none') if settings.is_file() else 'none'
    assert mode in ('none', 'required'), 'unknown capture mode'
    checks, results = [], {}
    for path in paths:
        check, result = check_record(args.root, path, args.require_incumbent, mode == 'required')
        checks.append(check)
        results[tuple(path.stem.rsplit('.', 2))] = result
    for source in sources:
        for engine in engines:
            left, right = (results[(source, engine, encoding)] for encoding in ('linear', 'quadratic'))
            for field in ('status', 'sense', 'objective', 'bound', 'strict', 'reason'):
                assert left.get(field) == right.get(field), (source, engine, field, 'representation differs')
    package = pathlib.Path(z3.__file__).parent
    executable = pathlib.Path(sys.executable).resolve()
    runtime = dict(version=sys.version, executable=str(executable),
                   executableSha256=hashlib.sha256(executable.read_bytes()).hexdigest())
    package_hashes = {str(path.relative_to(package)): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in sorted(package.rglob('*'))
                      if path.is_file() and '__pycache__' not in path.parts}
    summary = dict(checker='exact original-source objective comparison', z3Version=z3.get_version_string(),
                   python=runtime, checkerPackageSha256=package_hashes,
                   exportedProof='not checked', performance='instrumented diagnostic only', cases=checks)
    if args.output:
        args.output.write_text(json.dumps(summary, indent=2) + '\n')
    print('checked', len(checks), 'original-source objective records')


if __name__ == '__main__':
    main()
