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

    def test_summary_keeps_excluded_contexts_without_zero_cost_totals(self):
        base = {'family': 'f', 'engine': 'cp', 'index': 4, 'repetition': 2, 'mode': 'profile',
                'recipe': None, 'originalProblemCopiedVerbatim': {'sha256': 'same'},
                'observedDeclaredPhaseAndSourceEligible': False,
                'observedMetrics': dict.fromkeys(review.METRICS, 12)}
        pairs = review.paired([dict(base, arm='control'), dict(base, arm='candidate')])

        result = review.summaries(pairs)[0]

        self.assertEqual(1, result['totalOriginalPairs'])
        self.assertEqual(0, result['observedDeclaredPhaseAndSourceEligiblePairs'])
        self.assertEqual(4, result['excludedPairContexts'][0]['index'])
        self.assertEqual(2, result['excludedPairContexts'][0]['repetition'])
        for metric in result['metrics'].values():
            self.assertEqual(0, metric['observedEligiblePairs'])
            self.assertIsNone(metric['sumOfPairControlValues'])
            self.assertIsNone(metric['sumOfPairCandidateValues'])
            self.assertIsNone(metric['pairDifferenceRange'])

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
            summary = result['observedComparisonSummaries'][0]
            self.assertEqual(1, summary['totalOriginalPairs'])
            self.assertEqual(8, summary['metrics']['reportedElapsedNanos']['sumOfPairControlValues'])
            self.assertEqual(5, summary['metrics']['reportedElapsedNanos']['sumOfPairCandidateValues'])
            self.assertEqual([-3, -3], summary['metrics']['reportedElapsedNanos']['pairDifferenceRange'])
            self.assertFalse(result['acceptedSavingsCredit'])

    def test_observation_summaries_keep_unqualified_reported_costs_and_missing_values_without_comparison_credit(self):
        row = {'case': 'c', 'family': 'f', 'arm': 'candidate', 'engine': 'ls', 'index': 1, 'repetition': 0,
               'mode': 'profile', 'recipe': None, 'originalSourceStatus': 'unknown',
               'observedDeclaredPhaseAndSourceEligible': False, 'exclusions': ['Original source outcome is unknown'],
               'observedMetrics': {'rootEventCount': 2, 'reportedElapsedNanos': 17,
                                   'maxReportedElapsedNanos': 12, 'reportedSuccessfulCodeBytes': None},
               'observedC2Metrics': {'reportedElapsedNanos': None}}

        result = review.observation_summaries([row, row])

        self.assertEqual(2, len(result))
        for value in result:
            self.assertEqual(row['observedMetrics'], value['observedAllTierMetrics'])
            self.assertTrue(all(amount is None for amount in value['observedC2Metrics'].values()))
            self.assertEqual(row['exclusions'], value['exclusions'])
            self.assertFalse(value['observedDeclaredPhaseAndSourceEligible'])
            self.assertFalse(value['fullSourcePhaseCoverageProven'])
            self.assertFalse(value['runtimeSourceBindingProven'])
            self.assertFalse(value['acceptedSavingsCredit'])

    def test_compact_xml_indices_restore_every_reference_without_changing_roots_metrics_or_exclusions(self):
        for references in [[], [{'collection': collection, 'index': index}
                               for collection in review.XML_COLLECTIONS for index in (0, 3, 3, 99)]]:
            with self.subTest(references=references):
                value = {'inclusiveRootEventIndices': [1, 4, 9], 'requiredOrdinaryPhases': ['p.C.work'],
                         'observedAllTierMetrics': {'reportedElapsedNanos': None}, 'acceptedSavingsCredit': False,
                         **dict.fromkeys(review.XML_REFERENCE_FIELDS, references)}
                before = copy.deepcopy(value)

                result = review.compact_family_census(value)

                self.assertEqual(before, value)
                for field in review.XML_REFERENCE_FIELDS:
                    grouped = result.pop(field.replace('References', 'IndicesByCollection'))
                    restored = [{'collection': collection, 'index': index}
                                for collection, indices in grouped.items() for index in indices]
                    self.assertEqual(references, restored)
                self.assertEqual({k: v for k, v in value.items() if k not in review.XML_REFERENCE_FIELDS}, result)

    def test_compact_xml_indices_reject_unknown_malformed_or_reordered_reference_collections(self):
        invalid = [[{'collection': 'unknown', 'index': 0}], [{'collection': 'tasks', 'index': True}],
                   [{'collection': 'tasks', 'index': -1}], [{'collection': 'tasks', 'index': 0, 'extra': 1}],
                   [{'collection': 'tasks', 'index': 0}, {'collection': 'queued', 'index': 1}]]
        for references in invalid:
            with self.subTest(references=references), self.assertRaises(ValueError):
                review.compact_family_census(dict.fromkeys(review.XML_REFERENCE_FIELDS, references))

    def test_pair_references_recover_every_original_arm_and_duplicate_from_the_complete_observation_table(self):
        rows = [{'case': 'control', 'family': 'f', 'negative': ['source unknown']},
                {'case': 'candidate', 'family': 'f', 'negative': ['unmeasured queued root']},
                {'case': 'other', 'family': 'f', 'negative': ['failed root']}]
        pairs = [{'family': 'f', 'arms': {'control': [rows[0], rows[0]], 'candidate': [rows[1]], 'unknown': [rows[2]]},
                  'exclusions': ['Missing, duplicate or unexpected original pair arms'], 'acceptedSavingsCredit': False}]
        before = copy.deepcopy(pairs)

        result = review.referenced_pairs(pairs, rows)

        self.assertEqual(before, pairs)
        restored = {arm: [rows[value['observationIndex']] for value in values]
                    for arm, values in result[0]['arms'].items()}
        self.assertEqual(pairs[0]['arms'], restored)
        self.assertEqual(pairs[0]['exclusions'], result[0]['exclusions'])
        self.assertFalse(result[0]['acceptedSavingsCredit'])
        with self.assertRaisesRegex(ValueError, 'no retained family observation'):
            review.referenced_pairs(pairs, rows[:2])
        with self.assertRaisesRegex(ValueError, 'Repeated family observation'):
            review.referenced_pairs(pairs, rows + [rows[0]])

    def test_separate_mapping_compares_complete_saved_roots_without_replacing_original_excluded_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identity = {'size': 1, 'sha256': 'a' * 64}
            heads = {'control': 'a' * 40, 'candidate': 'b' * 40}
            measurements = [{'case': arm, 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                'recipe': None, 'arm': arm, 'sourceHashesMatch': True, 'exit': 0,
                'problem': {'format': 'MINIZINC'}, 'record': {'stats': {'sourceValidation': 'valid'}}} for arm in heads]
            original_families = {'f': {'methods': ['p.C'], 'ordinaryPhase': {arm: ['p.C.work'] for arm in heads}}}
            builds = [{'arm': arm, 'expectedHead': head, 'actualHead': head} for arm, head in heads.items()]
            selected, originals, cases = {}, {}, []
            for name, value in [('cases', measurements), ('compilation-families', original_families), ('study-builds', builds)]:
                raw = json.dumps(value).encode()
                path = root / ('original-' + name + '.json.gz')
                path.write_bytes(gzip.compress(raw))
                selected[path.name] = path
                originals[name] = {'path': path.name, 'compressed': review.cohort.file_identity(path),
                    'originalIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            for ordinal, (arm, elapsed) in enumerate([('control', 8), ('candidate', 5)]):
                roots = [{'eventIndex': index, 'id': index + 1, 'method': method, 'level': 4, 'osr': False,
                          'succeeded': True, 'reportedElapsedNanos': duration,
                          'reportedSuccessfulCodeBytes': 10, 'errors': []}
                         for index, (method, duration) in enumerate([('p.C.work()V', elapsed), ('outside.C.call()V', 2)])]
                family = {'requiredOrdinaryPhases': [], 'inclusiveRootEventIndices': [0],
                    'missingRequiredOrdinaryPhases': [], 'unresolvedSuccessfulInliningEventIndices': [],
                    'unmeasuredAllTierXmlSnapshotReferences': [], 'conflictingXmlSnapshotReferences': [],
                    'observedAllTierMetrics': {'reportedElapsedNanos': elapsed, 'maxReportedElapsedNanos': elapsed,
                        'reportedSuccessfulCodeBytes': 10, 'failedRootEventIndices': [], 'unknownOutcomeRootEventIndices': []},
                    'observedC2Metrics': {}}
                report = {'completed': True, 'originalMember': 'p/cases/' + arm + '/cli.jfr',
                    'originalIdentity': identity, 'originalCaseDirectory': arm, 'compilations': roots,
                    'inlining': [{'eventIndex': 9, 'id': 2, 'caller': 'outside.C.call()V',
                                 'callee': 'p.Helper.part()V', 'succeeded': True, 'errors': []}],
                    'eventCounts': {'jdk.Compilation': 2, 'jdk.CompilerInlining': 1}, 'families': {'f': family}}
                retained = []
                for name, value in [(f'compilation-{ordinal:06d}.json.gz', report),
                                    (f'xml-{ordinal:06d}-000.json.gz', {'completed': True, 'metadata': {}, 'error': None})]:
                    path = root / name
                    path.write_bytes(gzip.compress(json.dumps(value).encode()))
                    selected[name] = path
                    retained.append({'path': name, 'compressed': review.cohort.file_identity(path),
                                     'uncompressed': review.cohort.gzip_identity(path, review.MAX_JSON_BYTES)})
                cases.append({'caseDirectory': arm, 'completed': True, 'originalCaseMetadataIndex': ordinal,
                    'recordings': [{'originalMember': report['originalMember'], 'originalIdentity': identity, 'report': retained[0]}],
                    'xmlInputs': [{'originalMember': 'p/cases/' + arm + '/hotspot.xml', 'completed': True,
                                   'retainedMetadata': retained[1]}]})
            index = {'completed': True, 'allCaseCensusesComplete': True, 'cases': cases, 'originalMetadataFiles': originals}
            selected['compilation-cohort.json'] = root / 'index.json'
            selected['compilation-cohort.json'].write_text(json.dumps(index))
            declaration = {'schema': 1, 'inputCensusArchive': identity, 'sourceHeads': heads,
                'originalFamiliesIdentity': originals['compilation-families']['originalIdentity'],
                'originalStudyBuildsIdentity': originals['study-builds']['originalIdentity'],
                'families': {'f': {'methods': ['p.C', 'p.Helper'],
                    'ordinaryPhase': {arm: ['p.C.work', 'p.Helper.part'] for arm in heads},
                    'ordinaryPhaseAll': {arm: ['p.C.work', 'p.Helper.part'] for arm in heads}}}}

            result = review.inspect(selected, {'archive': identity}, 'profile1/', declaration)

            self.assertEqual(0, result['observedComparisonSummaries'][0]['observedDeclaredPhaseAndSourceEligiblePairs'])
            self.assertEqual(1, result['phaseMapping']['observedComparisonSummaries'][0]['observedDeclaredPhaseAndSourceEligiblePairs'])
            row = result['phaseMapping']['familyCaseObservations'][0]
            self.assertEqual([0, 1], row['mappedFamilyCensus']['inclusiveRootEventIndices'])
            original = result['familyCaseObservations'][0]
            self.assertEqual([0], original['familyCensusCopiedVerbatim']['inclusiveRootEventIndices'])
            self.assertEqual({'case': original['case'], 'family': original['family'],
                              'censusReportIdentity': original['censusReportIdentity']}, row['originalFamilyCensusReference'])
            self.assertEqual(10, row['observedMetrics']['reportedElapsedNanos'])
            self.assertFalse(result['phaseMapping']['fullSourcePhaseCoverageProven'])
            self.assertFalse(result['acceptedSavingsCredit'])

    def test_mapping_metadata_requires_both_compressed_and_original_byte_identities(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'original.json.gz'
            raw = b'{"f":{"methods":["p.C"]}}'
            path.write_bytes(gzip.compress(raw))
            record = {'path': path.name, 'compressed': review.cohort.file_identity(path),
                      'originalIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            index = {'originalMetadataFiles': {'original': record}}
            selected = {path.name: path}

            value, identity = review.mapping.original_metadata(selected, index, path.name, review.load_json, 1024)

            self.assertEqual(json.loads(raw), value)
            self.assertEqual(record['originalIdentity'], identity)
            for key in ('compressed', 'originalIdentity'):
                changed = copy.deepcopy(index)
                changed['originalMetadataFiles']['original'][key]['sha256'] = '0' * 64
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'producer identity'):
                    review.mapping.original_metadata(selected, changed, path.name, review.load_json, 1024)
            duplicate = {'originalMetadataFiles': {'one': record, 'two': record}}
            with self.assertRaisesRegex(ValueError, 'missing or ambiguous'):
                review.mapping.original_metadata(selected, duplicate, path.name, review.load_json, 1024)

    def test_mapping_binds_both_recorded_heads_and_original_metadata_to_the_complete_input_archive(self):
        identity = {'size': 1, 'sha256': '1' * 64}
        heads = {'control': 'a' * 40, 'candidate': 'b' * 40}
        builds = [{'arm': arm, 'expectedHead': head, 'actualHead': head} for arm, head in heads.items()]
        families = {'f': {'methods': ['p.C'], 'ordinaryPhase': {}, 'ordinaryPhaseAll': {}}}
        declaration = {'schema': 1, 'inputCensusArchive': identity, 'originalFamiliesIdentity': identity,
                       'originalStudyBuildsIdentity': identity, 'sourceHeads': heads, 'families': families}
        for field, value in [('inputCensusArchive', {}), ('originalFamiliesIdentity', {}),
                             ('originalStudyBuildsIdentity', {}), ('schema', True),
                             ('sourceHeads', dict(heads, candidate='c' * 40))]:
            with self.subTest(field=field), patch.object(review.mapping, 'original_metadata',
                    side_effect=[(families, identity), (builds, identity)]), self.assertRaises(ValueError):
                review.mapping.bind(dict(declaration, **{field: value}), {}, {}, {'archive': identity}, review.load_json, 1024)
        for invalid in [builds + [builds[0]], [builds[0], builds[0]],
                        [dict(builds[0], expectedHead='c' * 40), builds[1]]]:
            with self.subTest(builds=invalid), patch.object(review.mapping, 'original_metadata',
                    side_effect=[(families, identity), (invalid, identity)]), self.assertRaises(ValueError):
                review.mapping.bind(declaration, {}, {}, {'archive': identity}, review.load_json, 1024)
        with patch.object(review.mapping, 'original_metadata', side_effect=[(families, identity), (builds, identity)]):
            self.assertEqual(families, review.mapping.bind(declaration, {}, {}, {'archive': identity}, review.load_json, 1024))

    def test_mapping_cannot_drop_families_supporting_selectors_or_required_phase_declarations(self):
        identity = {'size': 1, 'sha256': '1' * 64}
        heads = {'control': 'a' * 40, 'candidate': 'b' * 40}
        builds = [{'arm': arm, 'expectedHead': head, 'actualHead': head} for arm, head in heads.items()]
        original = {'f': {'methods': ['p.C', 'p.Helper'], 'ordinaryPhase': {}}}
        family = {'methods': ['p.C', 'p.Helper'], 'ordinaryPhase': {'control': ['p.C.work']},
                  'ordinaryPhaseAll': {'control': ['p.C.work']}}
        declaration = {'schema': 1, 'inputCensusArchive': identity, 'originalFamiliesIdentity': identity,
                       'originalStudyBuildsIdentity': identity, 'sourceHeads': heads}
        variants = [{}, {'f': dict(family, methods=['p.C'])},
                    {'f': dict(family, ordinaryPhaseAll={'control': ['p.C.other']})},
                    {'f': dict(family, ordinaryPhase={'unknown': ['p.C.work']})},
                    {'f': dict(family, ordinaryPhase={'control': ['p.C.work', 'p.C.work']})},
                    {'f': dict(family, acceptedSavingsCredit=True)}]
        for families in variants:
            with self.subTest(families=families), patch.object(review.mapping, 'original_metadata',
                    side_effect=[(original, identity), (builds, identity)]), self.assertRaises(ValueError):
                review.mapping.bind(dict(declaration, families=families), {}, {}, {'archive': identity}, review.load_json, 1024)

    def test_mapped_roots_include_all_tiers_replacements_osr_and_successful_outside_callers(self):
        roots = [{'eventIndex': ordinal, 'id': ordinal + 1, 'method': method, 'level': level,
                  'osr': osr, 'succeeded': True, 'reportedElapsedNanos': ordinal + 1,
                  'reportedSuccessfulCodeBytes': 10, 'errors': []}
                 for ordinal, (method, level, osr) in enumerate([
                     ('p.Helper.part()V', 1, False), ('p.C.work()V', 4, False),
                     ('p.C.work()V', 4, True), ('outside.C.caller()V', 3, False),
                     ('p.C.work()V', 4, False)])]
        original = {'completed': True, 'compilations': roots, 'families': {'old': {'retained': True}},
                    'eventCounts': {'jdk.Compilation': 5, 'jdk.CompilerInlining': 1},
                    'inlining': [{'eventIndex': 8, 'id': 4, 'caller': 'outside.C.caller()V',
                                 'callee': 'p.Helper.part()V', 'succeeded': True, 'errors': []}]}
        before = copy.deepcopy(original)
        families = {'f': {'methods': ['p.C', 'p.Helper'], 'ordinaryPhase': {'control': ['p.C.work', 'p.Helper.part']},
                         'ordinaryPhaseAll': {'control': ['p.C.work', 'p.Helper.part']}}}
        xml = {'queued': [{'compileId': 99, 'rawAttributes': {'method': 'p/C work ()V'}}]}

        mapped = review.mapping.reform(original, families, 'control', xml)

        self.assertEqual(before, original)
        value = mapped['families']['f']
        self.assertEqual([0, 1, 2, 3, 4], value['inclusiveRootEventIndices'])
        self.assertEqual([3], value['successfulInliningRootEventIndices'])
        self.assertEqual([0, 3], value['ordinaryPhaseMethodRootEventIndices']['p.Helper.part'])
        self.assertEqual([], value['missingRequiredOrdinaryPhases'])
        self.assertEqual(15, value['observedAllTierMetrics']['reportedElapsedNanos'])
        self.assertEqual(10, value['observedC2Metrics']['reportedElapsedNanos'])
        self.assertEqual([{'collection': 'queued', 'index': 0}], value['unmeasuredAllTierXmlSnapshotReferences'])
        self.assertFalse(mapped['acceptedSavingsCredit'])
        self.assertFalse(mapped['runtimeSourceBindingProven'])

    def test_mapping_keeps_missing_xml_and_incomplete_recordings_as_explicit_exclusions(self):
        case = {'caseDirectory': 'c', 'completed': False}
        for inputs in ([], [{'originalMember': 'p/cases/c/hotspot.xml'}] * 2):
            with self.subTest(inputs=inputs):
                xml, exclusions = review.mapping.snapshots(dict(case, xmlInputs=inputs), {}, review.load_json, 1024)
                self.assertEqual({}, xml)
                self.assertTrue(exclusions)
        families = {'f': {'methods': ['p.C'], 'ordinaryPhase': {'control': ['p.C.work']},
                         'ordinaryPhaseAll': {'control': ['p.C.work']}}}
        report = review.mapping.reform({'completed': False}, families, 'control', {})
        metadata = {'case': 'c', 'engine': 'cp', 'index': 0, 'repetition': 0, 'mode': 'profile',
                    'arm': 'control', 'sourceHashesMatch': True, 'exit': 0,
                    'problem': {'format': 'MINIZINC'}, 'record': {'stats': {'sourceValidation': 'valid'}}}

        row = review.observations(case, metadata, report, {}, {}, False)[0]

        self.assertFalse(row['observedDeclaredPhaseAndSourceEligible'])
        self.assertIn('Incomplete original case or recording census', row['exclusions'])
        self.assertIn('missingRequiredOrdinaryPhases', row['exclusions'])
        self.assertIsNone(row['observedMetrics']['reportedElapsedNanos'])

    def test_retained_xml_mapping_rejects_tampering_and_keeps_parser_failures_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'xml.json.gz'
            for completed in (True, False):
                raw = json.dumps({'completed': completed, 'metadata': {'queued': []},
                                  'error': None if completed else {'type': 'ParseError'}}).encode()
                path.write_bytes(gzip.compress(raw))
                retained = {'path': 'xml-000000-000.json.gz', 'compressed': review.cohort.file_identity(path),
                            'uncompressed': review.cohort.gzip_identity(path, 1024)}
                case = {'caseDirectory': 'c', 'xmlInputs': [{'originalMember': 'p/cases/c/hotspot.xml',
                        'completed': completed, 'retainedMetadata': retained}]}
                selected = {retained['path']: path}

                xml, exclusions = review.mapping.snapshots(case, selected, review.load_json, 1024)

                self.assertEqual({'queued': []} if completed else {}, xml)
                self.assertEqual(not completed, bool(exclusions))
                for key in ('compressed', 'uncompressed'):
                    changed = copy.deepcopy(case)
                    changed['xmlInputs'][0]['retainedMetadata'][key]['sha256'] = '0' * 64
                    with self.subTest(completed=completed, key=key), self.assertRaisesRegex(ValueError, 'producer identity'):
                        review.mapping.snapshots(changed, selected, review.load_json, 1024)


if __name__ == '__main__':
    unittest.main(verbosity=2)
