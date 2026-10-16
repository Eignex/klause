import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import report_jvm_compilation_cohort as review


class ReportJvmCompilationCohortTest(unittest.TestCase):
    def test_original_checker_outcomes_keep_unknown_invalid_and_proof_limited_statuses(self):
        variants = [({}, 'unknown'), ({'error': 'bad source'}, 'invalid'),
                    ({'check': {'sourceHashVerified': True, 'witness': 'source feasible', 'proof': 'unknown'}}, 'proof-limited'),
                    ({'check': {'sourceHashVerified': True, 'witness': 'exact source feasible'}}, 'valid'),
                    ({'check': {'sourceHashVerified': True, 'verdictCheck': 'source unsat'}}, 'valid')]
        for entry, expected in variants:
            with self.subTest(entry=entry):
                self.assertEqual(expected, review.checker_status(entry))
        self.assertEqual('missing', review.source_status({'problem': {'format': 'MINIZINC'}}, {}))
        self.assertEqual('invalid', review.source_status({'case': 'c', 'problem': {'format': 'MPS'}}, {'c': 'invalid'}))

    def test_paired_observed_costs_retain_adverse_differences_and_exact_ratios(self):
        rows = [{'family': 'f', 'engine': 'cp', 'index': 1, 'repetition': 0, 'mode': 'profile',
                 'recipe': None, 'arm': arm, 'originalProblemCopiedVerbatim': {'sha256': 'original'},
                 'observedDeclaredPhaseAndSourceEligible': True,
                 'observedMetrics': {name: value for name in review.METRICS}}
                for arm, value in [('control', 7), ('candidate', 11)]]

        result = review.paired(rows)

        self.assertEqual(1, len(result))
        for value in result[0]['observedAllTierDifferences'].values():
            self.assertTrue(value['available'])
            self.assertEqual(4, value['candidateMinusControl'])
            self.assertEqual({'numerator': 11, 'denominator': 7}, value['candidateOverControl'])
        self.assertFalse(result[0]['acceptedSavingsCredit'])

    def test_missing_duplicate_and_unexpected_arms_are_retained_without_comparable_differences(self):
        base = {'family': 'f', 'engine': 'ls', 'index': 0, 'repetition': 0, 'mode': 'profile',
                'recipe': 'same', 'originalProblemCopiedVerbatim': {'sha256': 'same'},
                'observedDeclaredPhaseAndSourceEligible': True,
                'observedMetrics': dict.fromkeys(review.METRICS, 10)}
        for arms in [('control',), ('control', 'control', 'candidate'), ('control', 'candidate', 'unknown')]:
            rows = [dict(base, arm=arm) for arm in arms]
            with self.subTest(arms=arms):
                result = review.paired(rows)[0]

                self.assertTrue(result['exclusions'])
                self.assertEqual(len(arms), sum(len(value) for value in result['arms'].values()))
                self.assertTrue(all(not value['available'] for value in result['observedAllTierDifferences'].values()))

    def test_different_original_problems_and_ineligible_source_arms_are_not_compared(self):
        base = {'family': 'f', 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                'recipe': None, 'originalProblemCopiedVerbatim': {'sha256': 'same'},
                'observedDeclaredPhaseAndSourceEligible': True,
                'observedMetrics': dict.fromkeys(review.METRICS, 1)}
        for change in [{'originalProblemCopiedVerbatim': {'sha256': 'other'}},
                       {'observedDeclaredPhaseAndSourceEligible': False}]:
            with self.subTest(change=change):
                result = review.paired([dict(base, arm='control'), dict(base, arm='candidate', **change)])[0]

                self.assertFalse(result['observedDeclaredPhaseAndSourceEligible'])
                self.assertIsNone(result['observedAllTierDifferences']['reportedElapsedNanos']['candidateMinusControl'])

    def test_missing_metrics_are_unavailable_and_zero_denominators_have_no_ratio(self):
        base = {'family': 'f', 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                'recipe': None, 'originalProblemCopiedVerbatim': {},
                'observedDeclaredPhaseAndSourceEligible': True}
        for control in (None, 0):
            rows = [dict(base, arm=arm, observedMetrics=dict.fromkeys(review.METRICS, value))
                    for arm, value in [('control', control), ('candidate', 1)]]
            with self.subTest(control=control):
                result = review.paired(rows)[0]['observedAllTierDifferences']['reportedElapsedNanos']

                self.assertIsNone(result['candidateOverControl'])
                self.assertEqual(control is not None, result['available'])
                self.assertEqual(None if control is None else 1, result['candidateMinusControl'])

    def test_context_keys_keep_engines_repetitions_modes_and_recipes_in_separate_pairs(self):
        base = {'family': 'f', 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                'recipe': None, 'originalProblemCopiedVerbatim': {},
                'observedDeclaredPhaseAndSourceEligible': True,
                'observedMetrics': dict.fromkeys(review.METRICS, 1)}
        for key, value in [('engine', 'ls'), ('index', 1), ('repetition', 1), ('mode', 'cold'), ('recipe', 'other')]:
            with self.subTest(key=key):
                rows = [dict(base, arm='control'), dict(base, arm='candidate', **{key: value})]

                result = review.paired(rows)

                self.assertEqual(2, len(result))
                self.assertTrue(all(row['exclusions'] for row in result))

    def test_family_rows_keep_full_census_and_declared_eligibility_without_source_or_savings_proof(self):
        family = {'requiredOrdinaryPhases': ['p.C.work'], 'inclusiveRootEventIndices': [3],
                  'missingRequiredOrdinaryPhases': [], 'unresolvedSuccessfulInliningEventIndices': [],
                  'unmeasuredAllTierXmlSnapshotReferences': [], 'conflictingXmlSnapshotReferences': [],
                  'observedAllTierMetrics': {'reportedElapsedNanos': 8, 'maxReportedElapsedNanos': 8,
                      'reportedSuccessfulCodeBytes': 16, 'failedRootEventIndices': [], 'unknownOutcomeRootEventIndices': []},
                  'observedC2Metrics': {'reportedElapsedNanos': None}}
        report = {'completed': True, 'eventCounts': {}, 'families': {'a': family, 'b': family},
                  'compilations': [{'eventIndex': 3, 'errors': []}]}
        metadata = {'case': 'c', 'engine': 'ls', 'index': 0, 'repetition': 0, 'mode': 'profile',
                    'recipe': None, 'arm': 'control', 'sourceHashesMatch': True, 'exit': 0,
                    'problem': {'format': 'MPS'}}

        result = review.observations({'caseDirectory': 'c', 'completed': True}, metadata, report,
                                     {'c': 'valid'}, {'sha256': 'report'}, True)

        self.assertEqual(['a', 'b'], [row['family'] for row in result])
        for row in result:
            self.assertTrue(row['observedDeclaredPhaseAndSourceEligible'])
            self.assertEqual(family, row['familyCensusCopiedVerbatim'])
            self.assertFalse(row['fullSourcePhaseCoverageProven'])
            self.assertFalse(row['runtimeSourceBindingProven'])
            self.assertFalse(row['acceptedSavingsCredit'])

    def test_incomplete_lossy_ambiguous_or_unresolved_recordings_keep_exclusions(self):
        family = {'requiredOrdinaryPhases': [], 'inclusiveRootEventIndices': [],
                  'missingRequiredOrdinaryPhases': [], 'unresolvedSuccessfulInliningEventIndices': [],
                  'unmeasuredAllTierXmlSnapshotReferences': [], 'conflictingXmlSnapshotReferences': [],
                  'observedAllTierMetrics': {'failedRootEventIndices': [], 'unknownOutcomeRootEventIndices': []},
                  'observedC2Metrics': {}}
        metadata = {'case': 'c', 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                    'arm': 'control', 'sourceHashesMatch': True, 'exit': 0, 'problem': {'format': 'XCSP3'},
                    'record': {'stats': {'sourceValidation': 'valid'}}}
        variants = [{'completed': False}, {'completed': True, 'eventCounts': {'jdk.DataLoss': 1}},
                    {'completed': True, 'ambiguousCompileIds': {'1': [0, 1]}},
                    {'completed': True, 'unresolvedSuccessfulInliningEventIndices': [2]}]
        for change in variants:
            with self.subTest(change=change):
                report = dict(change, families={'f': copy.deepcopy(family)})
                row = review.observations({'caseDirectory': 'c', 'completed': True}, metadata,
                                          report, {}, {}, False)[0]

                self.assertFalse(row['observedDeclaredPhaseAndSourceEligible'])
                self.assertIn('Original cohort census is incomplete', row['exclusions'])
                self.assertIn('Original required ordinary phase declaration is empty', row['exclusions'])
                self.assertGreaterEqual(len(row['exclusions']), 4)

    def test_malformed_original_pair_context_is_not_grouped_by_missing_default_values(self):
        metadata = {'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile'}
        for key, value in [('engine', None), ('index', True), ('repetition', -1), ('mode', ''), ('recipe', {})]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                review.observations({}, dict(metadata, **{key: value}), {}, {}, {}, True)

    def test_strict_bounded_json_rejects_duplicate_nonfinite_and_invalid_utf8_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'report.gz'
            for raw in (b'{"x":0,"x":1}', b'{"x":NaN}', b'{"x":1e999}', b'{"x":"\xff"}', b'{'):
                path.write_bytes(gzip.compress(raw))
                with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                    review.load_json(path, True)
            path.write_bytes(gzip.compress(b'{"x":1}'))
            with patch.object(review, 'MAX_JSON_BYTES', 3), self.assertRaises(ValueError):
                review.load_json(path, True)

    def test_archive_authentication_verifies_unselected_bytes_before_census_interpretation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, manifest = root / 'archive.gz', root / 'manifest.json'
            payloads = {'profile9/compilation-cohort.json': b'{}', 'provenance/unchosen': b'corrupt'}
            with tarfile.open(archive, 'w:gz') as target:
                for name, raw in payloads.items():
                    entry = tarfile.TarInfo(name)
                    entry.size = len(raw)
                    target.addfile(entry, io.BytesIO(raw))
            files = {name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                     for name, raw in payloads.items()}
            files['provenance/unchosen']['sha256'] = '0' * 64
            manifest.write_text(json.dumps({'files': files, 'archive': review.cohort.file_identity(archive)}))

            with self.assertRaisesRegex(ValueError, 'member bytes differ'):
                review.authenticate(manifest, hashlib.sha256(manifest.read_bytes()).hexdigest(), archive, root)

    def test_complete_original_case_metadata_and_recording_identities_bind_the_observed_pair(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            measurements = [{'case': arm, 'engine': 'cp', 'index': 0, 'repetition': 0,
                'mode': 'profile', 'recipe': None, 'arm': arm, 'sourceHashesMatch': True, 'exit': 0,
                'problem': {'format': 'MINIZINC', 'sha256': 'original'},
                'record': {'stats': {'sourceValidation': 'valid'}}} for arm in ('control', 'candidate')]
            raw = json.dumps(measurements).encode()
            original = root / 'original-cases.json.gz'
            original.write_bytes(gzip.compress(raw))
            selected = {'original-cases.json.gz': original}
            cases = []
            for ordinal, (arm, elapsed) in enumerate([('control', 8), ('candidate', 5)]):
                name = f'compilation-{ordinal:06d}.json.gz'
                member = 'profile1/cases/' + arm + '/cli.jfr'
                identity = {'size': 1, 'sha256': 'a' * 64}
                metrics = {'reportedElapsedNanos': elapsed, 'maxReportedElapsedNanos': elapsed,
                           'reportedSuccessfulCodeBytes': 16, 'failedRootEventIndices': [],
                           'unknownOutcomeRootEventIndices': []}
                family = {'requiredOrdinaryPhases': ['p.C.work'], 'inclusiveRootEventIndices': [0],
                          'missingRequiredOrdinaryPhases': [], 'unresolvedSuccessfulInliningEventIndices': [],
                          'unmeasuredAllTierXmlSnapshotReferences': [], 'conflictingXmlSnapshotReferences': [],
                          'observedAllTierMetrics': metrics, 'observedC2Metrics': {}}
                report = {'completed': True, 'originalMember': member, 'originalIdentity': identity,
                          'originalCaseDirectory': arm, 'compilations': [{'eventIndex': 0, 'errors': []}],
                          'families': {'f': family}}
                path = root / name
                path.write_bytes(gzip.compress(json.dumps(report).encode()))
                selected[name] = path
                retained = {'path': name, 'compressed': review.cohort.file_identity(path),
                            'uncompressed': review.cohort.gzip_identity(path, review.MAX_JSON_BYTES)}
                cases.append({'caseDirectory': arm, 'completed': True, 'originalCaseMetadataIndex': ordinal,
                              'recordings': [{'originalMember': member, 'originalIdentity': identity, 'report': retained}]})
            index = {'completed': True, 'allCaseCensusesComplete': True, 'cases': cases,
                     'originalMetadataFiles': {'profile1/cases.json': {'path': 'original-cases.json.gz',
                         'compressed': review.cohort.file_identity(original),
                         'originalIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}}}
            selected['compilation-cohort.json'] = root / 'index.json'
            selected['compilation-cohort.json'].write_text(json.dumps(index))

            result = review.inspect(selected, {'archive': {'sha256': 'pinned'}}, 'profile9/')

            self.assertTrue(result['completed'])
            self.assertEqual([], result['metadataOnlyCaseNames'])
            self.assertEqual(2, len(result['recordingObservations']))
            self.assertEqual(1, len(result['allOriginalContextPairs']))
            pair = result['allOriginalContextPairs'][0]
            self.assertTrue(pair['observedDeclaredPhaseAndSourceEligible'])
            self.assertEqual(-3, pair['observedAllTierDifferences']['reportedElapsedNanos']['candidateMinusControl'])
            self.assertFalse(result['acceptedSavingsCredit'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
