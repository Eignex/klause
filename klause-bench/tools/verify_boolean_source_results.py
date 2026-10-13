"""Check DIMACS, WCNF and linear OPB claims with exact Boolean source arithmetic."""

import argparse
from fractions import Fraction
import gzip
import hashlib
import json
import lzma
from pathlib import Path
import re

import z3


def source(record, source_root):
    digest = record['sourceHashes']['model']
    assert re.fullmatch('[0-9a-f]{64}', digest), 'invalid source hash'
    raw = (source_root / (digest + '.txt')).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == digest, 'source hash mismatch'
    return raw.decode('utf-8')


def assignment(witness, size, opb=False, bitstring=False):
    assert witness.startswith('v ')
    if bitstring and re.fullmatch('[01]+', witness[2:]):
        assert len(witness[2:]) == size, 'incomplete source bitstring'
        return {i + 1: value == '1' for i, value in enumerate(witness[2:])}
    values = {}
    for word in witness[2:].split():
        if word == '0':
            continue
        if opb:
            assert re.fullmatch('-?x[1-9]\\d*', word)
            index = int(word.lstrip('-x'))
        else:
            assert re.fullmatch('-?[1-9]\\d*', word)
            index = abs(int(word))
        assert 1 <= index <= size and index not in values
        values[index] = not word.startswith('-')
    assert set(values) == set(range(1, size + 1)), 'incomplete source assignment'
    return values


def cnf(text, weighted=False):
    lines = []
    for line in text.splitlines():
        if line.lstrip().startswith('%'):
            break
        if line.strip() and (not line.lstrip().startswith('c')):
            lines.append(line)
    head = lines[0].split()
    if head[0] == 'p':
        lines.pop(0)
        assert head[:2] == ['p', 'wcnf' if weighted else 'cnf']
        assert len(head) in ([4, 5] if weighted else [4]), 'invalid source header'
        size, count = map(int, head[2:4])
        assert size >= 0 and count >= 0
        top = int(head[4]) if len(head) > 4 else None
    else:
        assert weighted, 'missing CNF header'
        size = count = top = None
    words = ' '.join(lines).split()
    clauses = []
    offset = 0
    while offset < len(words):
        if weighted:
            weight = None if words[offset] == 'h' else int(words[offset])
            offset += 1
        else:
            weight = None
        literals = []
        while offset < len(words) and words[offset] != '0':
            literal = int(words[offset])
            assert literal != 0 and (size is None or abs(literal) <= size)
            literals.append(literal)
            offset += 1
        assert offset < len(words), 'unterminated source clause'
        offset += 1
        if weighted:
            assert weight is None or weight >= 0
        clauses.append((None if not weighted or weight is None or (top is not None and weight >= top) else weight, literals))
    assert count is None or len(clauses) == count
    if size is None:
        size = max((abs(lit) for _, literals in clauses for lit in literals), default=0)
    return (size, clauses)


def pigeonhole(clauses):
    positive = [set(c) for _, c in clauses if c and all((x > 0 for x in c))]
    negative = [c for _, c in clauses if len(c) == 2 and all((x < 0 for x in c))]
    if not positive or sum((len(c) for c in positive)) != len(set.union(*positive)):
        return False
    vertices = set.union(*positive)
    adj = {v: set() for v in vertices}
    for a, b in negative:
        a, b = (-a, -b)
        if a not in vertices or b not in vertices:
            return False
        adj[a].add(b)
        adj[b].add(a)
    components = []
    remaining = set(vertices)
    while remaining:
        stack = [next(iter(remaining))]
        part = set()
        while stack:
            v = stack.pop()
            if v in part:
                continue
            part.add(v)
            stack.extend(adj[v] - part)
        remaining -= part
        components.append(part)
    return len(positive) > len(components) and all((all((adj[v] >= part - {v} for v in part)) and all((len(group & part) == 1 for group in positive)) for part in components))


def opb(text):
    header = re.search('#variable=\\s*(\\d+)', text)
    size = int(header[1]) if header else None
    parts = ' '.join((line for line in text.splitlines() if not line.lstrip().startswith('*'))).split(';')
    objective = None
    rows = []
    soft_rows = []
    top = None
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if part.startswith('soft:'):
            match = re.fullmatch(r'soft:\s*([+-]?\d+)?\s*', part)
            assert match, 'unsupported WBO soft header'
            top = int(match[1]) if match[1] is not None else None
            continue
        soft_weight = None
        if part.startswith('['):
            match = re.fullmatch(r'\[([+]?\d+)\]\s*(.*)', part)
            assert match, 'unsupported WBO weight'
            soft_weight, part = int(match[1]), match[2]
        if part.startswith('min:'):
            assert soft_weight is None, 'weighted objective declaration'
            assert objective is None
            expression = part[4:]
            rhs = None
            relation = 'min'
        else:
            match = re.fullmatch('(.*?)\\s*(>=|<=|=)\\s*([+-]?\\d+)\\s*', part)
            assert match, 'unsupported OPB syntax'
            expression, relation, rhs = match.groups()
            rhs = int(rhs)
        pattern = '([+-]?\\d+)\\s+(~?x[1-9]\\d*)'
        terms = [(int(coef), int(name.lstrip('~x')), name.startswith('~')) for coef, name in re.findall(pattern, expression)]
        assert not re.sub(pattern, '', expression).strip(), 'nonlinear or unsupported OPB expression'
        assert all((size is None or 1 <= index <= size for _, index, _ in terms))
        if relation == 'min':
            objective = terms
        elif soft_weight is None:
            rows.append((terms, relation, rhs))
        else:
            soft_rows.append((soft_weight, terms, relation, rhs))
    if size is None:
        terms = (objective or []) + [term for row, _, _ in rows for term in row]
        terms += [term for _, row, _, _ in soft_rows for term in row]
        size = max((index for _, index, _ in terms), default=0)
    return (size, objective, rows, soft_rows, top)


def relation_holds(value, relation, bound):
    return value >= bound if relation == ">=" else value <= bound if relation == "<=" else value == bound


def integer_objective(record):
    value = record.get('exactObjective')
    if value is None:
        value = record.get('objective')
    result = Fraction(str(value))
    assert result.denominator == 1, 'nonintegral source objective'
    return result.numerator


def check(case, source_root):
    r = case['record']
    format_name = case['problem']['format']
    text = source(r, source_root)
    result = {'sourceHashVerified': True, 'reportedProven': r.get('proven'), 'witness': 'absent'}
    if r.get('feasible') is None:
        assert not r.get('proven')
        result['claim'] = 'none'
        return result
    weighted = format_name == 'WCNF'
    is_opb = format_name == 'OPB'
    if weighted or is_opb:
        assert not r.get('maximize', False), 'source objective orientation differs'
    if is_opb:
        size, objective, rows, soft_rows, top = opb(text)

        def value(terms, values):
            return sum((c * (not values[i] if neg else values[i]) for c, i, neg in terms))
    else:
        size, clauses = cnf(text, weighted)
    if r['feasible'] is True:
        values = assignment(r['finalWitness'], size, is_opb, weighted)
        if is_opb:
            for terms, rel, rhs in rows:
                n = value(terms, values)
                assert n >= rhs if rel == '>=' else n <= rhs if rel == '<=' else n == rhs
            soft_cost = sum(weight for weight, terms, rel, rhs in soft_rows
                            if not relation_holds(value(terms, values), rel, rhs))
            if top is not None and soft_rows:
                assert soft_cost < top, 'WBO soft top exceeded'
            cost = (value(objective or [], values) + soft_cost) if objective is not None or soft_rows else None
        else:
            cost = 0
            for weight, literals in clauses:
                satisfied = any((values[abs(lit)] == (lit > 0) for lit in literals))
                if weight is None:
                    assert satisfied, 'violated source clause'
                elif not satisfied:
                    cost += weight
            if not weighted:
                cost = None
        if cost is not None:
            reported = integer_objective(r)
            assert cost == reported, f'source objective mismatch: source={cost}, reported={reported}'
        else:
            assert r.get('exactObjective') is None and r.get('objective') is None, 'source has no objective'
        result['witness'] = 'source feasible'
        result['objective'] = cost
        if not r.get('proven'):
            return result
        if cost is None:
            result['proof'] = 'source satisfaction'
            return result
    else:
        assert r.get('proven')
        if not is_opb and (not weighted) and pigeonhole(clauses):
            result['proof'] = 'source pigeonhole refutation'
            return result
    variables = {i: z3.Bool(f'x{i}') for i in range(1, size + 1)}
    solver = z3.Solver()
    solver.set(timeout=10000)
    if is_opb:

        def expression(terms):
            return z3.Sum([z3.If(z3.Not(variables[i]) if neg else variables[i], c, 0) for c, i, neg in terms])
        for terms, rel, rhs in rows:
            solver.add(relation_holds(expression(terms), rel, rhs))
        soft_cost = z3.Sum([z3.If(relation_holds(expression(terms), rel, rhs), 0, weight)
                            for weight, terms, rel, rhs in soft_rows])
        if top is not None and soft_rows:
            solver.add(soft_cost < top)
        if r['feasible'] is True:
            solver.add(expression(objective or []) + soft_cost < integer_objective(r))
    else:
        soft = []
        for weight, literals in clauses:
            clause = z3.Or([variables[abs(lit)] if lit > 0 else z3.Not(variables[abs(lit)]) for lit in literals])
            if weight is None:
                solver.add(clause)
            else:
                soft.append(z3.If(clause, 0, weight))
        if r['feasible'] is True:
            solver.add(z3.Sum(soft) < integer_objective(r))
    answer = solver.check()
    if answer == z3.unsat:
        result['proof'] = 'strictly better source objective excluded' if r['feasible'] else 'source refutation'
    elif answer == z3.unknown:
        result['proof'] = 'unknown'
        result['reason'] = solver.reason_unknown()
    else:
        raise AssertionError('source counterexample to proof claim')
    return result


def check_cases(cases, source_root):
    checks, cache = ([], {})
    for case in cases:
        if case['problem']['format'] not in ['DIMACS', 'WCNF', 'OPB']:
            continue
        try:
            assert case['status'] == 'DONE', 'unfinished case'
            r = case['record']
            reported_objective = r.get('exactObjective')
            if reported_objective is None:
                reported_objective = r.get('objective')
            key = (case['problem']['format'], r['sourceHashes']['model'], r.get('feasible'), r.get('proven'), r.get('finalWitness'), str(reported_objective))
            if key not in cache:
                cache[key] = check(case, source_root)
            checks.append({'index': case['index'], 'check': cache[key]})
        except Exception as error:
            checks.append({'index': case['index'], 'error': type(error).__name__ + ': ' + str(error)})
    return checks
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Check Boolean source witnesses and proof claims using original source bytes.')
    parser.add_argument('cases', type=Path)
    parser.add_argument('--source-root', required=True, type=Path, help='Original uncompressed sources named <record sourceHashes.model>.txt.')
    parser.add_argument('--records-root', type=Path, help='Use authoritative raw cases/<index>/record.json instead of embedded records.')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    opener = gzip.open if args.cases.suffix == '.gz' else lzma.open if args.cases.suffix == '.xz' else open
    with opener(args.cases, 'rt') as stream:
        cases = json.load(stream)
    if args.records_root:
        for case in cases:
            case['record'] = json.loads((args.records_root / 'cases' / str(case['index']) / 'record.json').read_text())
    checks = check_cases(cases, args.source_root)
    args.output.write_text(json.dumps(checks, indent=2, sort_keys=True) + '\n')
    raise SystemExit(1 if any(('error' in entry for entry in checks)) else 0)
