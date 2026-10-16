"""Ground unpublished cached exact points against complete original integer SMT sources."""

import argparse
import fractions
import hashlib
import json
import pathlib
import re

import z3
import verify_source_results as source_check


class Ineligible(ValueError):
    pass


class Unsupported(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Ineligible(reason)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def captured_point(record):
    require(record.get('diagnosticCandidateOnly') is True, 'candidate-only record required')
    require(not {'feasible', 'proven', 'finalWitness'} & record.keys(), 'solver verdict fields are forbidden')
    producer = record['producer']
    require(re.fullmatch('[0-9a-f]{40}', producer['revision']), 'immutable producer revision required')
    require(type(producer['sourceJob']) is int and producer['sourceJob'] > 0, 'producer job required')
    probe_raw = record['probeText'].encode()
    root_raw = record['rootText'].encode()
    require(digest(probe_raw) == producer['probeSha256'], 'snapshot hash differs')
    require(digest(root_raw) == producer['rootSha256'], 'root hash differs')
    probe = json.loads(probe_raw)
    root = json.loads(root_raw)
    require(probe['schema'] == 2, 'unsupported point schema')
    require(root['revision'] == producer['revision'], 'root producer revision differs')
    require(root['sourceSha256'] == probe['sourceSha256'] == record['sourceHashes']['model'], 'source hash join differs')
    require(root['problem'] == record['problem'], 'root source identity differs')
    require(root['suite'] == record['sourceSuite'], 'root source suite differs')
    require(root['entryPoint'] == probe['entryPoint'] == 'direct-library-linear-root-opening',
            'unsupported observation entry point')
    require(probe['sourceVerdictClaim'] is False and probe['candidatePublished'] is False, 'published point is unsupported')
    require(probe['additionalSolveOrTraversal'] is False and root['traversalAdvanced'] is False,
            'non-root observation is unsupported')
    require(root['sourceVerdictClaim'] is False and root['decisionLevel'] == 0, 'root provenance differs')
    require(root['additionalCompilationBeforeDeadline'] is False and probe['diagnosticAfterOpening'] is True,
            'additional diagnostic compilation unsupported')
    require(root['deadlinePassedAtReturn'] is False and root['cancelledAtReturn'] is False, 'root operation expired')
    require(root['processors'] == 1 and root['seed'] == 1 and root['openingBudgetMs'] == 10000,
            'root observation settings differ')
    obs = probe['observation']
    require(obs['status'] == 'CAPTURED' and obs['snapshotStateAndValuesUnchanged'] is True, 'point capture unavailable')
    require(obs['mappingIsFeasibilityOrWitnessAuthority'] is False, 'point authority differs')
    require(obs['cachedVerdict'] in ['FEASIBLE', 'ATTAINED_OPTIMUM'], 'cached exact LP witness unavailable')
    for key in ['preparedSourceIsCompiled', 'modelKeyIsComponentModel', 'systemSourceIsComponentModel',
                'cachedFloatStateIsCurrent', 'componentContextIsSession', 'sourceNameIdsInRange']:
        require(obs[key] is True, 'unqualified point identity: ' + key)
    require(obs['sessionDecisionLevel'] == obs['stateDecisionLevel'] == 0, 'non-root point scope')
    for key in ['lpClosed', 'lpInvalidated', 'ownerClosed', 'ownerWorkingScopeActive']:
        require(obs[key] == 'false', 'inactive point owner: ' + key)
    require(set(obs['stateRevisions']) == {'matrixRevision', 'boundRevision', 'objectiveRevision', 'popRevision', 'rowRevision'},
            'incomplete state revisions')
    require(all(type(v) is int and v >= 0 for v in obs['stateRevisions'].values()), 'invalid state revisions')
    require(obs['layoutContract'] == 'LiveQfLraSystem.install: real then integer source prefix, followed by expression columns',
            'unsupported source column layout')
    mapping = obs['sourceMappingMetadata']
    require(mapping['status'] == 'CAPTURED', 'source mapping unavailable')
    for key in ['mappingSourceIsPreparedSource', 'mappingTargetIsPreparedProblem', 'mappingRebuildIsPreparedRebuild',
                'declaredWriteIntersectionsQualified', 'completeWriteSet']:
        require(mapping[key] is True, 'unqualified source mapping: ' + key)
    require(mapping['capturedMappingChainLength'] == 0 and mapping['rebuildSteps'] == [], 'source reconstruction required')
    require(mapping['reconstructionInvoked'] is False and mapping['coordinateMappingPolicyChanged'] is False,
            'source mapping observation differs')
    require(mapping['columnIdContract'] == 'SourceRebuild.RebuildStep retains eliminated columns without renumbering',
            'unsupported source mapping contract')
    for sort, root_key, component_key in [('Booleans', 'compiledBooleans', 'componentBooleans'),
                                           ('Integers', 'compiledIntegers', 'componentIntegers'),
                                           ('Reals', 'compiledReals', 'componentReals')]:
        require(mapping['source' + sort] == mapping['target' + sort] == root[root_key] == obs[component_key],
                'source coordinate space changed: ' + sort)
    require(obs['sourceRealCount'] == obs['componentReals'] == 0, 'private or source real columns unsupported')
    require(obs['declaredBooleans'] == {} and obs['declaredReals'] == {}, 'only declared integer inputs supported')
    require(root['sourceBooleanNames'] == root['sourceRealNames'] == 0, 'declared source sorts differ')
    require(root['sourceIntegerNames'] == len(obs['declaredIntegers']), 'declared integer count differs')
    prefix = obs['componentIntegers']
    require(obs['sourcePrefixLength'] == obs['installedStructuralColumns'] == obs['floatVectorLength'] == prefix,
            'source prefix layout differs')
    require(obs['installedTotalColumns'] == prefix + obs['installedLogicalRows'], 'installed column layout differs')
    exact = obs['exactPointSnapshot']
    require(obs['exactWitnessPresent'] is True and exact['status'] == 'CAPTURED', 'stored exact point unavailable')
    require(exact['authority'] == 'CACHED_CONTINUOUS_LP_POINT_ONLY', 'unsupported point authority')
    require(exact['vectorLength'] == len(exact['values']) == prefix, 'incomplete exact source prefix')
    ids = list(obs['declaredIntegers'].values())
    require(all(type(i) is int and 0 <= i < prefix for i in ids) and len(set(ids)) == len(ids), 'invalid declared IDs')
    return root, obs, exact


def ground(record, text):
    root, obs, exact = captured_point(record)
    commands = source_check.expressions(text)
    allowed = {'set-logic', 'set-info', 'set-option', 'declare-const', 'declare-fun', 'define-fun',
               'assert', 'check-sat', 'get-model', 'get-value', 'get-info', 'exit'}
    if any(not command or command[0] not in allowed for command in commands):
        raise Unsupported('source contains unsupported commands or scopes')
    if sum(command[0] == 'check-sat' for command in commands) != 1:
        raise Unsupported('one original source query required')
    try:
        variables = source_check.smt_variables(text)
    except (AssertionError, KeyError, ValueError) as error:
        raise Unsupported(str(error)) from error
    if any(not z3.is_int(variable) for variable in variables.values()):
        raise Unsupported('only original integer declarations supported')
    require(set(variables) == set(obs['declaredIntegers']), 'original declaration names differ or are incomplete')
    require(len(variables) == root['sourceIntegerNames'], 'original declaration count differs')
    values = {}
    for name, variable in obs['declaredIntegers'].items():
        raw = exact['values'][variable]
        require(re.fullmatch('-?(0|[1-9][0-9]*)', raw['numerator']) and
                re.fullmatch('[1-9][0-9]*', raw['denominator']), 'malformed exact coordinate: ' + name)
        value = fractions.Fraction(int(raw['numerator']), int(raw['denominator']))
        require(str(value.numerator) == raw['numerator'] and str(value.denominator) == raw['denominator'],
                'noncanonical exact coordinate: ' + name)
        require(value.denominator == 1, 'fractional declared integer: ' + name)
        values[name] = str(value.numerator)
    try:
        parsed = z3.Optimize()
        parsed.from_string(text)
        assertions = list(parsed.assertions())
    except z3.Z3Exception as error:
        raise Unsupported('original source parse failed: ' + str(error)) from error
    require(not list(parsed.objectives()), 'source objective unsupported')
    require(len(assertions) == sum(command[0] == 'assert' for command in commands), 'source assertion count differs')
    solver, encoding, _ = source_check.source_query_solver(assertions, variables, True, None)
    solver.add(*[variables[name] == z3.IntVal(value) for name, value in values.items()])
    query = solver.to_smt2()
    verdict = solver.check()
    assignment = json.dumps(values, sort_keys=True, separators=(',', ':'))
    result = {'declaredCoordinates': len(values), 'originalAssertions': len(assertions),
              'candidateAssignment': values, 'candidateAssignmentSha256': digest(assignment.encode()),
              'querySha256': digest(query.encode()), 'queryEncoding': encoding, 'queryResult': str(verdict),
              'checkerTimeoutMs': 120000, 'producerStrictMappingLabel': obs['coordinateMapping'],
              'projectionQualification': 'ACTUAL_EMPTY_REBUILD_PRESERVED_DECLARED_IDS'}
    if verdict == z3.sat:
        result['outcome'] = 'ACCEPTED_ORIGINAL_SOURCE_ASSIGNMENT'
    elif verdict == z3.unsat:
        result['outcome'] = 'REJECTED_PINNED_ASSIGNMENT'
    else:
        result['reason'] = solver.reason_unknown()
        result['outcome'] = 'TIMEOUT' if 'timeout' in result['reason'].lower() else 'UNKNOWN'
    return result


def check(cases, source_root):
    results = []
    for case in cases:
        result = {'index': case.get('index') if isinstance(case, dict) else None, 'diagnosticCandidateOnly': True,
                  'ordinaryPublishedVerdict': False, 'defaultVerdictCredit': False,
                  'exportedProofCertificateChecked': False}
        try:
            require(isinstance(case, dict), 'case object required')
            require(type(case.get('index')) is int and case['index'] >= 0, 'case index required')
            record = case['record']
            require(isinstance(record, dict), 'candidate record object required')
            require(case['status'] == 'DONE', 'producer case incomplete')
            require(case['problem']['suite'] in source_check.SMT_SUITES, 'unsupported source suite')
            require(case['problem']['problem'] == record['problem'], 'case source identity differs')
            require(case['problem']['suite'] == record['sourceSuite'], 'case source suite differs')
            sha = record['sourceHashes']['model']
            require(re.fullmatch('[0-9a-f]{64}', sha), 'invalid source hash')
            raw = (source_root / (sha + '.txt')).read_bytes()
            require(digest(raw) == sha, 'original source bytes differ')
            result.update({'sourceSha256': sha, 'sourceHashVerified': True,
                           'producer': record['producer']})
            result.update(ground(record, raw.decode()))
        except Ineligible as error:
            result.update(outcome='INELIGIBLE', reason=str(error))
        except Unsupported as error:
            result.update(outcome='UNSUPPORTED', reason=str(error))
        except (KeyError, IndexError, TypeError, ValueError, UnicodeDecodeError, FileNotFoundError) as error:
            result.update(outcome='INELIGIBLE', reason='missing or malformed evidence: ' + str(error))
        results.append(result)
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cases', type=pathlib.Path)
    parser.add_argument('--source-root', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(check(json.loads(args.cases.read_text()), args.source_root), indent=2) + '\n')
