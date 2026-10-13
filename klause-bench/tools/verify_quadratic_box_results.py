"""Check saved rational quadratic witnesses and interval bounds at exact real box sources."""

import argparse
import hashlib
import json
import pathlib
from fractions import Fraction


def objective_range(source):
    bounds = [(Fraction(row['lower']), Fraction(row['upper'])) for row in source['reals']]
    assert all(lower <= upper for lower, upper in bounds), 'inverted original declaration'
    objective = source['objective']
    lower = upper = Fraction(objective['constant'])
    for term in objective['terms']:
        first = term['first']
        second = term.get('second')
        assert 0 <= first < len(bounds), 'foreign source coordinate'
        a, b = bounds[first]
        if second is None:
            values = [a, b]
        elif first == second:
            values = [a * a, b * b]
            if a <= 0 <= b:
                values.append(Fraction(0))
        else:
            assert 0 <= second < len(bounds), 'foreign source coordinate'
            c, d = bounds[second]
            values = [a * c, a * d, b * c, b * d]
        coefficient = Fraction(term['coefficient'])
        lower += min(coefficient * value for value in values)
        upper += max(coefficient * value for value in values)
    return bounds, lower, upper


def check_record(source_bytes, record):
    assert hashlib.sha256(source_bytes).hexdigest() == record['sourceHash'], 'foreign original source bytes'
    source = json.loads(source_bytes)
    assert source['semantics'] == 'klause-ir-exact-v1', 'unsupported source semantics'
    assert set(source) == {'semantics', 'reals', 'objective'}, 'unsupported source fields'
    objective = source['objective']
    assert record['sense'] == objective['sense'] in ('MINIMIZE', 'MAXIMIZE'), 'foreign objective direction'
    bounds, lower, upper = objective_range(source)
    if 'forwardedWork' in record:
        assert record['forwardedWork'] == record['nativeWork'], 'native meter forwarding differs'
        assert record['polynomialWork'] + record['numericalWork'] == record['nativeWork'], 'native ledger differs'
    if record.get('publicationCount', 0) > 0 and record['kind'] == 'Optimal':
        assert record['acceptedIdentityRetained'], 'final optimum lost accepted publication identity'
        assert record['acceptedVersion'] == record['publicationCount'], 'accepted version differs from publication order'
    witness = record.get('witness')
    if witness is None or record.get('value') is None or any(value is None for value in witness):
        return {'witnessCheck': 'Unavailable', 'terminalCheck': 'Unavailable'}
    assert len(witness) == len(bounds), 'incomplete source witness'
    values = list(map(Fraction, witness))
    assert all(a <= value <= b for value, (a, b) in zip(values, bounds)), 'source declaration violated'
    value = Fraction(objective['constant'])
    for term in objective['terms']:
        contribution = Fraction(term['coefficient']) * values[term['first']]
        if 'second' in term:
            contribution *= values[term['second']]
        value += contribution
    assert value == Fraction(record['value']), 'original objective differs from reported value'
    result = {'witnessCheck': 'Checked', 'boundCheck': 'Unavailable', 'terminalCheck': 'Unavailable'}
    if record.get('bound') is not None:
        bound = Fraction(record['bound'])
        minimum = record['sense'] == 'MINIMIZE'
        assert (value >= bound if minimum else value <= bound), 'bound contradicts the source witness'
        covered = bound <= lower if minimum else bound >= upper
        if not record.get('strict', False) and covered:
            result['boundCheck'] = 'Checked'
        if record['kind'] == 'Optimal':
            assert not record.get('strict', False) and value == bound, 'optimum does not attain its bound'
            if covered:
                result['terminalCheck'] = 'Checked'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('results', type=pathlib.Path)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    payload_bytes = args.results.read_bytes()
    payload = json.loads(payload_bytes)
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    assert payload['sourceHash'] == source_hash, 'foreign payload source'
    checks = [dict(control=record['control'], **check_record(source_bytes, record)) for record in payload['results']]
    print(json.dumps({'sourceHash': source_hash, 'payloadHash': hashlib.sha256(payload_bytes).hexdigest(),
                      'checkerHash': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                      'arithmetic': 'python-fractions', 'checks': checks, 'generalProofExport': False}, indent=2))
