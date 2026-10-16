import copy
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import z3
import verify_candidate_source_results as candidate_check


class CandidateSourceCheckTest(unittest.TestCase):
    def setUp(self):
        self.source = (pathlib.Path(__file__).parents[1] / 'smoke-corpus' / 'nonlinear-integer-v1' /
                       'bounded-product-sat.smt2').read_text()
        self.root = dict(revision='a' * 40, sourceSha256=candidate_check.digest(self.source.encode()),
                         problem='fixture', traversalAdvanced=False, sourceVerdictClaim=False, decisionLevel=0,
                         processors=1, seed=1, openingBudgetMs=10000, compiledBooleans=0, compiledIntegers=2,
                         compiledReals=0, sourceBooleanNames=0, sourceIntegerNames=2, sourceRealNames=0)
        self.root.update(suite='smtlib-core', entryPoint='direct-library-linear-root-opening',
                         additionalCompilationBeforeDeadline=False, deadlinePassedAtReturn=False, cancelledAtReturn=False)
        mapping = dict(status='CAPTURED', mappingSourceIsPreparedSource=True, mappingTargetIsPreparedProblem=True,
                       mappingRebuildIsPreparedRebuild=True, declaredWriteIntersectionsQualified=True,
                       completeWriteSet=True, capturedMappingChainLength=0, rebuildSteps=[], reconstructionInvoked=False,
                       coordinateMappingPolicyChanged=False, sourceBooleans=0, targetBooleans=0, sourceIntegers=2,
                       targetIntegers=2, sourceReals=0, targetReals=0,
                       columnIdContract='SourceRebuild.RebuildStep retains eliminated columns without renumbering')
        obs = dict(status='CAPTURED', snapshotStateAndValuesUnchanged=True, preparedSourceIsCompiled=True,
                   modelKeyIsComponentModel=True, systemSourceIsComponentModel=True, cachedFloatStateIsCurrent=True,
                   componentContextIsSession=True, sourceNameIdsInRange=True, sessionDecisionLevel=0, stateDecisionLevel=0,
                   lpClosed='false', lpInvalidated='false', ownerClosed='false', ownerWorkingScopeActive='false',
                   stateRevisions=dict(matrixRevision=0, boundRevision=1, objectiveRevision=0, popRevision=0, rowRevision=0),
                   layoutContract='LiveQfLraSystem.install: real then integer source prefix, followed by expression columns',
                   sourceMappingMetadata=mapping, componentBooleans=0, componentIntegers=2, componentReals=0,
                   sourceRealCount=0, declaredBooleans={}, declaredIntegers=dict(x=0, y=1), declaredReals={},
                   sourcePrefixLength=2, installedStructuralColumns=2, floatVectorLength=2, installedTotalColumns=2,
                   installedLogicalRows=0, exactWitnessPresent=True, coordinateMapping='UNVERIFIED',
                   mappingIsFeasibilityOrWitnessAuthority=False, cachedVerdict='ATTAINED_OPTIMUM',
                   exactPointSnapshot=dict(status='CAPTURED', authority='CACHED_CONTINUOUS_LP_POINT_ONLY', vectorLength=2,
                                           values=[dict(numerator='2', denominator='1'),
                                                   dict(numerator='3', denominator='1')]))
        self.probe = dict(schema=2, sourceSha256=self.root['sourceSha256'], sourceVerdictClaim=False,
                          candidatePublished=False, additionalSolveOrTraversal=False, observation=obs)
        self.probe.update(entryPoint='direct-library-linear-root-opening', diagnosticAfterOpening=True)

    def record(self, probe=None, root=None):
        probe_text = json.dumps(probe or self.probe)
        root_text = json.dumps(root or self.root)
        return dict(problem='fixture', sourceSuite='smtlib-core', diagnosticCandidateOnly=True,
                    sourceHashes=dict(model=self.root['sourceSha256']),
                    probeText=probe_text, rootText=root_text,
                    producer=dict(revision=self.root['revision'], sourceJob=1,
                                  probeSha256=candidate_check.digest(probe_text.encode()),
                                  rootSha256=candidate_check.digest(root_text.encode())))

    def test_checks_the_complete_pinned_assignment_against_original_assertions(self):
        for point, outcome in [(['2', '3'], 'ACCEPTED_ORIGINAL_SOURCE_ASSIGNMENT'),
                               (['1', '1'], 'REJECTED_PINNED_ASSIGNMENT')]:
            with self.subTest(point=point):
                probe = copy.deepcopy(self.probe)
                for entry, value in zip(probe['observation']['exactPointSnapshot']['values'], point):
                    entry['numerator'] = value

                result = candidate_check.ground(self.record(probe), self.source)

                self.assertEqual(result['outcome'], outcome)
                self.assertEqual(result['declaredCoordinates'], 2)
                self.assertEqual(result['originalAssertions'], 1)
                self.assertEqual(result['producerStrictMappingLabel'], 'UNVERIFIED')

    def test_fractional_declared_integer_is_ineligible(self):
        self.probe['observation']['exactPointSnapshot']['values'][0] = dict(numerator='1', denominator='2')

        with self.assertRaisesRegex(candidate_check.Ineligible, 'fractional declared integer'):
            candidate_check.ground(self.record(), self.source)

    def test_missing_extra_and_duplicate_declared_inputs_are_ineligible(self):
        for names in [dict(x=0), dict(x=0, y=1, extra=2), dict(x=0, y=0)]:
            with self.subTest(names=names):
                probe = copy.deepcopy(self.probe)
                root = copy.deepcopy(self.root)
                probe['observation']['declaredIntegers'] = names
                root['sourceIntegerNames'] = len(names)

                with self.assertRaises(candidate_check.Ineligible):
                    candidate_check.ground(self.record(probe, root), self.source)

    def test_tampered_capture_hash_is_ineligible(self):
        record = self.record()
        record['probeText'] += ' '

        with self.assertRaisesRegex(candidate_check.Ineligible, 'snapshot hash differs'):
            candidate_check.ground(record, self.source)

    def test_stale_state_or_different_installed_model_is_ineligible(self):
        for field in ['cachedFloatStateIsCurrent', 'modelKeyIsComponentModel', 'systemSourceIsComponentModel']:
            with self.subTest(field=field):
                probe = copy.deepcopy(self.probe)
                probe['observation'][field] = False

                with self.assertRaises(candidate_check.Ineligible):
                    candidate_check.ground(self.record(probe), self.source)

    def test_reconstruction_unknown_mapping_and_unknown_layout_are_ineligible(self):
        for field, value in [('rebuildSteps', [dict(kind='unknown')]), ('completeWriteSet', False),
                             ('capturedMappingChainLength', 1)]:
            with self.subTest(field=field):
                probe = copy.deepcopy(self.probe)
                probe['observation']['sourceMappingMetadata'][field] = value

                with self.assertRaises(candidate_check.Ineligible):
                    candidate_check.ground(self.record(probe), self.source)
        self.probe['observation']['layoutContract'] = 'unknown'

        with self.assertRaisesRegex(candidate_check.Ineligible, 'unsupported source column layout'):
            candidate_check.ground(self.record(), self.source)

    def test_truncated_exact_point_is_ineligible(self):
        self.probe['observation']['exactPointSnapshot']['values'].pop()

        with self.assertRaisesRegex(candidate_check.Ineligible, 'incomplete exact source prefix'):
            candidate_check.ground(self.record(), self.source)

    def test_solver_result_records_are_not_candidate_inputs(self):
        for field in ['feasible', 'proven', 'finalWitness']:
            with self.subTest(field=field):
                record = self.record()
                record[field] = None

                with self.assertRaisesRegex(candidate_check.Ineligible, 'solver verdict fields are forbidden'):
                    candidate_check.ground(record, self.source)

    def test_candidate_checks_never_publish_solver_verdicts(self):
        record = self.record()
        case = dict(index=0, status='DONE', problem=dict(suite='smtlib-core', problem='fixture'), record=record)
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (self.root['sourceSha256'] + '.txt')).write_text(self.source)

            result = candidate_check.check([case], root)[0]

        self.assertEqual(result['outcome'], 'ACCEPTED_ORIGINAL_SOURCE_ASSIGNMENT')
        self.assertTrue(result['diagnosticCandidateOnly'])
        self.assertFalse(result['ordinaryPublishedVerdict'])
        self.assertFalse(result['defaultVerdictCredit'])
        self.assertNotIn('feasible', result)
        self.assertNotIn('proven', result)

    def test_missing_case_fields_are_ineligible_without_aborting_other_cases(self):
        cases = [dict(index=0), dict(index=1, record={}), dict(status='DONE')]

        results = candidate_check.check(cases, pathlib.Path('.'))

        self.assertEqual([result['outcome'] for result in results], ['INELIGIBLE'] * 3)

    def test_original_real_sorts_and_source_scopes_are_unsupported(self):
        corpus = pathlib.Path(__file__).parents[1] / 'smoke-corpus'
        for path in ['nonlinear-integer-v1/mixed-finite-product.smt2',
                     'qfnra-v1/extensions/session/multiple-queries.smt2']:
            with self.subTest(path=path):
                source = (corpus / path).read_text()
                self.root['sourceSha256'] = candidate_check.digest(source.encode())
                self.probe['sourceSha256'] = self.root['sourceSha256']

                with self.assertRaises(candidate_check.Unsupported):
                    candidate_check.ground(self.record(), source)

    def test_unknown_and_timeout_remain_distinct(self):
        for reason, expected in [('timeout', 'TIMEOUT'), ('incomplete', 'UNKNOWN')]:
            with self.subTest(reason=reason):
                solver = mock.Mock()
                solver.to_smt2.return_value = '(check-sat)'
                solver.check.return_value = z3.unknown
                solver.reason_unknown.return_value = reason

                with mock.patch.object(candidate_check.source_check, 'source_query_solver',
                                       return_value=(solver, 'general', lambda value: value)):
                    result = candidate_check.ground(self.record(), self.source)

                self.assertEqual(result['outcome'], expected)


if __name__ == '__main__':
    unittest.main()
