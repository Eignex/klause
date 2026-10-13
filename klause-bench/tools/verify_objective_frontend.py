"""Independently check retained CLI objective publications against original manifest sources."""

import hashlib
import json
import pathlib
import re
import sys

import z3

from verify_nra_source_results import source_formula, exact_value, sexpr, witness_values
from verify_source_results import expressions
from verify_stream_source_results import check_stream


def verify(root):
    manifest = json.loads((root / 'manifest.json').read_text())
    expected = {case['family'] + '.' + engine + '.' + case['encoding']
                for case in manifest['cases'] for engine in case['engines']}
    paths = list(root.glob('*.stdout'))
    assert {path.stem for path in paths} == expected and len(paths) == len(expected), 'incomplete CLI matrix'
    records = []
    for path in sorted(root.glob('*.stdout')):
        source, engine, encoding = path.stem.rsplit('.', 2)
        raw = (root / (source + '.' + encoding + '.smt2')).read_text()
        commands = expressions(raw)
        direction, syntax = next(c for c in commands if c[0] in ('minimize', 'maximize'))
        semantics = '\n'.join(sexpr(c) for c in commands if c[0] not in ('minimize', 'maximize'))
        variables, constraints = source_formula(semantics)
        cost_name = '__frontend_cost'
        while cost_name in raw:
            cost_name += '_'
        semantics = '\n'.join(sexpr(c) for c in commands if c[0] not in ('minimize', 'maximize'))
        objective = z3.parse_smt2_string(semantics + '\n(assert (= ' + cost_name + ' ' + sexpr(syntax) + '))',
                                       decls={cost_name: z3.Real(cost_name)})[-1].arg(1)
        output = path.read_text()
        assert (path.with_suffix('.exit')).read_text().strip() == '0', path
        launch = json.loads(path.with_suffix('.launch.json').read_text())
        assert '-XX:ActiveProcessorCount=1' in launch['actualJvmArguments'], path
        assert launch['exit'] == '0', path
        original = next(case for case in manifest['cases'] if case['file'] == source + '.' + encoding + '.smt2')
        assert hashlib.sha256(raw.encode()).hexdigest() == original['sha256'], path
        metadata = dict(line[2:].split('=', 1) for line in output.splitlines() if line.startswith('; ') and '=' in line)
        status = metadata['optimizationStatus']
        solver = z3.Solver(); solver.set(timeout=20000); solver.add(*constraints)
        cost, bound = None, None
        publications = []
        if status == 'unsatisfiable':
            assert 'unsat' in output.splitlines() and solver.check() == z3.unsat, path
        else:
            assert 'sat' in output.splitlines(), path
            model = '\n'.join(line for line in output.splitlines()
                              if line.strip() and not line.startswith(';') and
                              not re.match(r'^\[\d+(?:\.\d+)?s\]\[info\]\[jfr,startup\](?: |$)', line) and
                          line.strip() not in ('sat', 'unsat', 'unknown'))
            models = expressions(model)
            assert models, 'missing original-source model'
            if len(models) > 1:
                stream = check_stream(raw, output)
                assert len(stream['witnesses']) == len(models), 'incomplete publication stream'
                publications = stream['witnesses']
            model = sexpr(models[-1])
            values = witness_values(model, variables)
            substitutions = [(variables[name], value) for name, value in values.items()]
            assert all(z3.is_true(z3.simplify(z3.substitute(row, substitutions))) for row in constraints), path
            cost = exact_value(expressions('(value ' + metadata['objective'] + ')')[0][1])
            assert z3.is_true(z3.simplify(z3.substitute(objective, substitutions) == cost)), path
            if not publications:
                publications = [dict(objective=cost.sexpr(),
                                     witnessSha256=hashlib.sha256(model.encode()).hexdigest())]
            if 'objectiveBound' in metadata:
                assert metadata['objectiveBoundDirection'] == ('lower' if direction == 'minimize' else 'upper'), path
                bound = exact_value(expressions('(value ' + metadata['objectiveBound'] + ')')[0][1])
                solver.push(); solver.add(objective < bound if direction == 'minimize' else objective > bound)
                assert solver.check() == z3.unsat, path
                solver.pop()
                if metadata['objectiveBoundStrict'] == 'true':
                    solver.push(); solver.add(objective == bound); assert solver.check() == z3.unsat, path; solver.pop()
            if status == 'optimal':
                assert bound is not None and z3.is_true(z3.simplify(cost == bound)), path
            elif status in ('unattained-infimum', 'unattained-supremum'):
                assert bound is not None and metadata['objectiveBoundStrict'] == 'true', path
                assert len(variables) == 1, path
                x = next(iter(variables.values())); t = z3.Real('__approach')
                candidate = z3.substitute(objective, (x, t))
                endpoint = z3.simplify(z3.substitute(candidate, (t, z3.RealVal(0))))
                slope = z3.simplify(z3.substitute(candidate, (t, t + 1)) - candidate)
                assert z3.is_true(z3.simplify(endpoint == bound)), path
                assert z3.is_true(z3.simplify(candidate == bound + slope*t)), path
                assert z3.is_rational_value(slope), path
                assert slope.as_fraction() > 0 if direction == 'minimize' else slope.as_fraction() < 0, path
                approach = z3.Solver(); approach.set(timeout=20000)
                approach.add(t > 0, z3.Or(*[z3.Not(z3.substitute(row, (x, t))) for row in constraints]))
                assert approach.check() == z3.unsat, path
            elif status == 'unbounded':
                assert len(variables) == 1, path
                name, x = next(iter(variables.items())); t = z3.Real('__ray')
                coefficient = z3.simplify(z3.substitute(objective, (x, z3.RealVal(1))) -
                                         z3.substitute(objective, (x, z3.RealVal(0))))
                assert z3.is_rational_value(coefficient) and coefficient.as_fraction() != 0, path
                sign = -1 if (direction == 'minimize') == (coefficient.as_fraction() > 0) else 1
                trajectory = values[name] + sign*t
                candidate = z3.substitute(objective, (x, trajectory))
                slope = z3.simplify(z3.substitute(candidate, (t, t + 1)) - candidate)
                assert z3.is_rational_value(slope), path
                assert slope.as_fraction() < 0 if direction == 'minimize' else slope.as_fraction() > 0, path
                ray = z3.Solver(); ray.set(timeout=20000)
                ray.add(t >= 0, z3.Or(*[z3.Not(z3.substitute(row, (x, trajectory))) for row in constraints]))
                assert ray.check() == z3.unsat, path
            else:
                assert status == 'best-found', path
        assert int(metadata['quadraticNativeWork']) > 0, path
        if engine in ('ls', 'alns'):
            assert status == 'best-found' and int(metadata['lsMoves']) > 0, path
        records.append(dict(source=source, engine=engine, encoding=encoding, status=status,
            objective=cost.sexpr() if cost is not None else None, bound=bound.sexpr() if bound is not None else None,
            strict=metadata.get('objectiveBoundStrict'), direction=direction,
            sourceSha256=hashlib.sha256(raw.encode()).hexdigest(), stdoutSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            witness='exact original-source checked' if cost is not None else 'none',
            publicationCount=len(publications), publicationChecks=publications,
            boundCheck='exact original-source inequality checked' if bound is not None else 'none',
            approachCheck='exact original-source affine approach checked' if status.startswith('unattained-') else 'none',
            rayCheck='exact original-source affine ray checked' if status == 'unbounded' else 'none', exportedProof='not captured'))
    for affine in [r for r in records if r['encoding'] == 'affine']:
        quadratic = next(r for r in records if r['source'] == affine['source'] and r['engine'] == affine['engine'] and r['encoding'] == 'zero-square')
        for key in ('status', 'objective', 'bound', 'strict', 'direction'):
            assert affine[key] == quadratic[key], (affine, quadratic, key)
    (root / 'source-checks.json').write_text(json.dumps(
        dict(checker='z3 exact original source/objective checks', z3Version=z3.get_version_string(), cases=records),
        indent=2) + '\n')
    package = pathlib.Path(z3.__file__).parent
    hashes = {str(p.relative_to(package)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in package.rglob('*') if p.is_file() and '__pycache__' not in str(p)}
    (root / 'checker-package-sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
    binary = pathlib.Path(sys.executable).resolve()
    (root / 'checker-runtime.json').write_text(json.dumps(dict(version=sys.version, executable=str(binary),
        sha256=hashlib.sha256(binary.read_bytes()).hexdigest()), indent=2) + '\n')
    print('independently checked', len(records), 'original source CLI outcomes')


if __name__ == '__main__':
    verify(pathlib.Path(sys.argv[1]))
