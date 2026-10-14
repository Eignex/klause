import hashlib
import pathlib
import tempfile
import unittest
from unittest import mock

import z3
import verify_source_results as proof_check


class ProofCheckTest(unittest.TestCase):
    source = '(declare-const x Real) (assert (>= x (/ 1 3))) (minimize x)'
    mps_source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x obj 0.1 row 1\nRHS\n rhs row 1 obj 0.2\nBOUNDS\n FR b x\nENDATA\n'

    def case(self, suite='smtlib-core', arm='fast-exact', **updates):
        record = dict(problem='fixture', gitSha='fixture', kind='optimize', sourceHashes={'model': 'fixture'}, feasible=True,
                      proven=True, objective='1/3', maximize=False,
                      finalWitness='((define-fun x () Real (/ 1.0 3.0)))')
        record.update(updates)
        return dict(index=0, status='DONE', arm=arm, problem={'suite': suite}, record=record)

    def check(self, case, source=None):
        with mock.patch.object(proof_check, 'source', return_value=source or self.source):
            return proof_check.check([case])[0]['check']

    def test_exact_rational_witness_and_optimum(self):
        result = self.check(self.case())
        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_exact_mps_objective_witness_establishes_attainment_without_sat_search(self):
        solver = z3.Solver()
        query = solver.check
        case = self.case(suite='mps-core', objective='-1/10', exactObjective='-1/10', finalWitness='v x=1')
        with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', side_effect=lambda: z3.unknown if query() == z3.sat else query()):
            result = self.check(case, self.mps_source)

        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_complete_source_witness_establishes_sat_when_unpinned_query_is_unknown(self):
        solver = z3.Solver()
        query = solver.check

        def check_pinned():
            solver.push()
            solver.add(z3.Real('x') != 1)
            pinned = query() == z3.unsat
            solver.pop()
            return query() if pinned else z3.unknown

        case = self.case(kind='satisfy', objective=None, proven=False,
                         finalWitness='((define-fun x () Real 1))')
        with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', side_effect=check_pinned):
            result = self.check(case, '(declare-const x Real) (assert (>= x 0))')

        self.assertEqual(result['verdictCheck'], 'source sat')
        self.assertEqual(result['witness'], 'exact source feasible')

    def test_unknown_queries_report_the_failed_claim_and_reason(self):
        variants = (
            ({'finalWitness': None}, 'source feasibility expected sat'),
            ({}, 'complete source witness feasibility expected sat'),
            ({'feasible': False, 'finalWitness': None}, 'source infeasibility expected unsat'),
        )
        for updates, purpose in variants:
            with self.subTest(purpose=purpose):
                solver = z3.Solver()
                case = self.case(kind='satisfy', objective=None, **updates)
                with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                        mock.patch.object(solver, 'check', return_value=z3.unknown), \
                        mock.patch.object(solver, 'reason_unknown', return_value='timeout'):
                    with self.assertRaisesRegex(AssertionError, 'fixture: ' + purpose + ', got unknown; reason: timeout'):
                        self.check(case, '(declare-const x Real) (assert (>= x 0))')

    def test_complete_satisfaction_witness_avoids_incremental_search(self):
        for proven in (False, True):
            with self.subTest(proven=proven):
                solver = z3.Solver()
                case = self.case(kind='satisfy', objective=None, proven=proven,
                                 finalWitness='((define-fun x () Real 1))')
                with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                        mock.patch.object(solver, 'push', side_effect=AssertionError('incremental search started')), \
                        mock.patch.object(solver, 'pop', side_effect=AssertionError('incremental scope popped')):
                    result = self.check(case, '(declare-const x Real) (assert (>= x 0))')

                self.assertEqual(result['verdictCheck'], 'source sat')
                self.assertEqual(result['witness'], 'exact source feasible')

    def test_progress_preserves_completed_and_cached_records_before_a_failure(self):
        first = self.case(kind='satisfy', objective=None, proven=False,
                          finalWitness='((define-fun x () Real 1))')
        cached = self.case(kind='satisfy', objective=None, proven=False,
                           finalWitness='((define-fun x () Real 1))')
        cached['index'] = 1
        failed = self.case(kind='satisfy', objective=None, proven=False,
                          finalWitness='((define-fun x () Real 0))')
        failed['index'] = 2
        progress = []
        with mock.patch.object(proof_check, 'source', return_value='(declare-const x Real) (assert (>= x 1))'):
            with self.assertRaisesRegex(AssertionError, 'complete source witness feasibility expected sat, got unsat'):
                proof_check.check([first, cached, failed], progress=progress.append)

        self.assertEqual([entry['index'] for entry in progress], [0, 1])
        self.assertTrue(all(entry['check']['witness'] == 'exact source feasible' for entry in progress))

    def test_exact_objective_overrides_rounded_numeric_objective(self):
        for value in ('9007199254740993', '9223372036854775809', '1/3'):
            for maximize in (False, True):
                direction = 'maximize' if maximize else 'minimize'
                bound = '<=' if maximize else '>='
                source = f'(declare-const x Real) (assert ({bound} x {value if "/" not in value else "(/ 1 3)"})) ({direction} x)'
                witness = value if '/' not in value else '(/ 1.0 3.0)'
                case = self.case(objective=1.0, exactObjective=value, maximize=maximize,
                                 finalWitness=f'((define-fun x () Real {witness}))')
                self.assertIn('optimalityCheck', self.check(case, source))

    def test_feasible_suboptimal_point_does_not_certify_optimum(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(objective=1, finalWitness='((define-fun x () Real 1.0))'))

    def test_infeasible_source_does_not_certify_sat(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(proven=False), self.source + ' (assert (< x 0))')

    def test_feasible_source_does_not_certify_unsat(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(feasible=False, objective=None, finalWitness=None))

    def test_infeasible_printed_model_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Real 0.0))'))

    def test_incomplete_printed_model_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='()'))

    def test_model_sort_must_match_source(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Int 1))'))

    def test_model_value_cannot_reference_an_unconstrained_symbol(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Real y))'))

    def test_integer_model_cannot_contain_a_fraction(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(finalWitness='((define-fun x () Int (/ 1 2)))'),
                       '(declare-const x Int) (assert (>= x 0))')

    def test_maximum_is_checked_in_source_orientation(self):
        case = self.case(maximize=True, objective=2,
                         finalWitness='((define-fun x () Real 2.0))')
        result = self.check(case, '(declare-const x Real) (assert (<= x 2)) (maximize x)')
        self.assertIn('optimalityCheck', result)

    def test_unknown_has_no_proof_claim(self):
        result = self.check(self.case(feasible=None, proven=False, objective=None, finalWitness=None))
        self.assertEqual(result['verdictCheck'], 'no claimed verdict')

    def test_proven_satisfaction_requires_no_objective(self):
        result = self.check(self.case(kind='satisfy', objective=None),
                            '(declare-const x Real) (assert (>= x 0))')
        self.assertEqual(result['verdictCheck'], 'source sat')
        self.assertNotIn('optimalityCheck', result)

    def test_satisfaction_without_an_objective_rejects_a_reported_value_without_a_witness(self):
        case = self.case(kind='satisfy', proven=False, objective=1, finalWitness=None)

        with self.assertRaisesRegex(AssertionError, 'objective is absent from source'):
            self.check(case, '(declare-const x Real) (assert (>= x 0))')

    def test_incomplete_case_is_rejected(self):
        case = self.case()
        case['status'] = 'RUNNING'
        with self.assertRaises(AssertionError):
            self.check(case)

    def test_source_hash_must_match(self):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'(check-sat)'
        case = self.case()
        case['record']['gitSha'] = 'fixture'
        with mock.patch.object(proof_check.urllib.request, 'urlopen', return_value=response):
            with self.assertRaises(AssertionError):
                proof_check.source(case, [])

    def test_smt_corpus_sources_route_from_hash_named_original_bytes(self):
        fixtures = (
            ('smtlib-qfidl', '(declare-const x Int) (declare-const y Int) (assert (= (- x y) 3))',
             '((define-fun x () Int 3) (define-fun y () Int 0))'),
            ('smtlib-qflia', '(declare-const x Int) (assert (= (* 2 x) 4))', '((define-fun x () Int 2))'),
            ('smtlib-qflira', '(declare-const x Int) (declare-const y Real) (assert (= (+ (to_real x) y) (/ 3 2)))',
             '((define-fun x () Int 1) (define-fun y () Real (/ 1 2)))'),
            ('smtlib-qflra', '(declare-const x Real) (assert (= x (/ 1 3)))', '((define-fun x () Real (/ 1 3)))'),
            ('smtlib-qfrdl', '(declare-const x Real) (declare-const y Real) (assert (= (- x y) (/ 1 3)))',
             '((define-fun x () Real (/ 1 3)) (define-fun y () Real 0))'),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for suite, text, witness in fixtures:
                with self.subTest(suite=suite):
                    data = text.encode('utf-8')
                    digest = hashlib.sha256(data).hexdigest()
                    (root / (digest + '.txt')).write_bytes(data)
                    case = self.case(suite=suite, kind='satisfy', objective=None,
                                     sourceHashes={'model': digest}, finalWitness=witness)

                    result = proof_check.check([case], root)[0]['check']

                    self.assertTrue(result['sourceHashVerified'])
                    self.assertEqual(result['verdictCheck'], 'source sat')
                    self.assertEqual(result['witness'], 'exact source feasible')

    def test_smt_corpus_sources_require_an_original_source_root(self):
        for suite in sorted(proof_check.SMT_CORPUS_SUITES):
            with self.subTest(suite=suite), self.assertRaisesRegex(ValueError, '--source-root'):
                proof_check.check([self.case(suite=suite)])

    def test_mps_corpus_sources_require_hash_matched_original_bytes(self):
        data = self.mps_source.encode('latin-1')
        digest = hashlib.sha256(data).hexdigest()
        for suite in sorted(proof_check.MPS_CORPUS_SUITES):
            with self.subTest(suite=suite), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                case = self.case(suite=suite, sourceHashes={'model': digest})
                with self.assertRaisesRegex(ValueError, '--source-root'):
                    proof_check.source(case, [])
                (root / (digest + '.txt')).write_bytes(data)
                self.assertEqual(self.mps_source, proof_check.source(case, [], root))
                (root / (digest + '.txt')).write_bytes(data + b'\n')
                with self.assertRaisesRegex(AssertionError, 'source hash differs'):
                    proof_check.source(case, [], root)

    def test_mps_corpus_sources_route_from_hash_named_original_bytes(self):
        data = self.mps_source.encode('utf-8')
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (digest + '.txt')).write_bytes(data)
            for suite in sorted(proof_check.MPS_CORPUS_SUITES):
                with self.subTest(suite=suite):
                    case = self.case(suite=suite, arm='default', proven=False, objective='-1/10',
                                     sourceHashes={'model': digest}, finalWitness='v x=1')

                    result = proof_check.check([case], root)[0]['check']

                    self.assertTrue(result['sourceHashVerified'])
                    self.assertEqual(result['witness'], 'exact source feasible')
                    self.assertEqual(result['objectiveCheck'], 'source point objective matches')

    def test_mps_corpus_sources_require_an_original_source_root(self):
        for suite in sorted(proof_check.MPS_CORPUS_SUITES):
            with self.subTest(suite=suite), self.assertRaisesRegex(ValueError, '--source-root'):
                proof_check.check([self.case(suite=suite)])

    def test_hash_named_corpus_source_bytes_must_match_the_record(self):
        data = b'(declare-const x Int) (assert (= x 1))'
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (digest + '.txt')).write_bytes(data + b'\n')
            case = self.case(suite='smtlib-qflia', sourceHashes={'model': digest})

            with self.assertRaisesRegex(AssertionError, 'source hash differs'):
                proof_check.check([case], root)

    def test_source_root_hash_names_cannot_escape_or_use_invalid_digests(self):
        for digest in ('../outside', 'a' * 63, 'A' * 64):
            with self.subTest(digest=digest), tempfile.TemporaryDirectory() as directory:
                case = self.case(suite='smtlib-qflia', sourceHashes={'model': digest})

                with self.assertRaisesRegex(AssertionError, 'invalid source hash'):
                    proof_check.check([case], pathlib.Path(directory))

    def test_an_unsupported_suite_is_declined_even_with_a_source_root_and_no_verdict(self):
        data = b'(declare-const x Int) (assert (= x 1))'
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (digest + '.txt')).write_bytes(data)
            case = self.case(suite='unsupported', feasible=None, proven=False, objective=None,
                             sourceHashes={'model': digest}, finalWitness=None)

            with self.assertRaisesRegex(ValueError, 'unsupported source suite'):
                proof_check.check([case], root)

    def test_corpus_false_verdict_claims_are_rejected_against_original_constraints(self):
        fixtures = (
            (False, '(declare-const x Int) (assert (= x 1))', None),
            (True, '(declare-const x Int) (assert (= x 1)) (assert (= x 2))', '((define-fun x () Int 1))'),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for feasible, text, witness in fixtures:
                with self.subTest(feasible=feasible):
                    data = text.encode('utf-8')
                    digest = hashlib.sha256(data).hexdigest()
                    (root / (digest + '.txt')).write_bytes(data)
                    case = self.case(suite='smtlib-qflia', kind='satisfy', objective=None, feasible=feasible,
                                     sourceHashes={'model': digest}, finalWitness=witness)

                    with self.assertRaises(AssertionError):
                        proof_check.check([case], root)

    def test_corpus_incomplete_infeasible_and_wrong_sort_witnesses_are_rejected(self):
        data = b'(declare-const x Int) (declare-const b Bool) (assert (= x 1)) (assert b)'
        digest = hashlib.sha256(data).hexdigest()
        witnesses = (
            '', '()', '((define-fun x () Int 1))',
            '((define-fun x () Int 2) (define-fun b () Bool true))',
            '((define-fun x () Real 1) (define-fun b () Bool true))',
        )
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (digest + '.txt')).write_bytes(data)
            for witness in witnesses:
                with self.subTest(witness=witness):
                    case = self.case(suite='smtlib-qfidl', kind='satisfy', objective=None,
                                     sourceHashes={'model': digest}, finalWitness=witness)

                    with self.assertRaises(AssertionError):
                        proof_check.check([case], root)

    def test_corpus_exact_objectives_and_orientation_use_original_source_arithmetic(self):
        data = self.source.encode('utf-8')
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / (digest + '.txt')).write_bytes(data)
            case = self.case(suite='smtlib-qflra', sourceHashes={'model': digest})

            result = proof_check.check([case], root)[0]['check']

            self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')
            for updates in ({'objective': '2/3'}, {'maximize': True}):
                with self.subTest(updates=updates), self.assertRaises(AssertionError):
                    proof_check.check([self.case(suite='smtlib-qflra', sourceHashes={'model': digest}, **updates)], root)

    def test_core_smt_source_still_fetches_its_recorded_revision(self):
        data = self.source.encode('utf-8')
        case = self.case(gitSha='a' * 40, sourceHashes={'model': hashlib.sha256(data).hexdigest()})
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = data
        with mock.patch.object(proof_check.urllib.request, 'urlopen', return_value=response) as fetch:

            result = proof_check.check([case])[0]['check']

            fetch.assert_called_once_with(
                'https://raw.githubusercontent.com/Eignex/klause/' + 'a' * 40 +
                '/klause-bench/smoke-corpus/smtlib/fixture.smt2', timeout=60,
            )
            self.assertEqual(result['witness'], 'exact source feasible')

    def test_integer_mps_default_bound_is_binary(self):
        text = "NAME fixture\nROWS\n N obj\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1\n M2 'MARKER' 'INTEND'\nENDATA\n"
        constraints, _, variables, _ = proof_check.mps(text)
        solver = z3.Solver()
        solver.add(*constraints, variables['x'] == 2)
        self.assertEqual(solver.check(), z3.unsat)

    def test_binary_mps_optimum_queries_keep_all_original_rows(self):
        source = "NAME fixture\nROWS\n N obj\n G row\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1 row 2\n y obj 1 row 3\n M2 'MARKER' 'INTEND'\nRHS\n rhs row 2\nENDATA\n"
        case = self.case(suite='mps-core', arm='default', objective=1, finalWitness='v x=1 y=0')

        result = self.check(case, source)

        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_binary_mps_queries_reject_an_impossible_source_objective(self):
        source = "NAME fixture\nROWS\n N obj\n G row\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1 row 2\n y obj 1 row 3\n M2 'MARKER' 'INTEND'\nRHS\n rhs row 2\nENDATA\n"

        with self.assertRaisesRegex(AssertionError, 'claimed source objective feasibility expected sat, got unsat'):
            self.check(self.case(suite='mps-core', arm='default', objective=0, finalWitness=None), source)

    def test_binary_mps_query_reports_finite_domain_encoding(self):
        source = "NAME fixture\nROWS\n N obj\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1\n M2 'MARKER' 'INTEND'\nENDATA\n"
        case = self.case(suite='mps-core', arm='default', objective=0, finalWitness='v x=0')

        result = self.check(case, source)

        self.assertEqual(result['sourceQueryEncoding'], 'finite binary')

    def test_binary_source_queries_preserve_rational_objectives_and_strict_thresholds(self):
        for coefficient, row_kind, value in (('0.1', 'E', '-1/10'), ('-0.1', 'L', '-3/10')):
            with self.subTest(coefficient=coefficient):
                source = self.mps_source.replace('obj 0.1', 'obj ' + coefficient).replace(' E row', ' ' + row_kind + ' row')
                source = source.replace('COLUMNS\n', "COLUMNS\n M1 'MARKER' 'INTORG'\n")
                source = source.replace('RHS\n', " M2 'MARKER' 'INTEND'\nRHS\n").replace(' FR b x', ' BV b x')
                case = self.case(suite='mps-core', arm='default', objective=value, finalWitness='v x=1')

                result = self.check(case, source)

                self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_binary_source_query_weights_cannot_truncate_large_coefficients(self):
        coefficient = 2 ** 64
        source = self.mps_source.replace('obj 0.1', 'obj ' + str(coefficient))
        source = source.replace('COLUMNS\n', "COLUMNS\n M1 'MARKER' 'INTORG'\n")
        source = source.replace('RHS\n', " M2 'MARKER' 'INTEND'\nRHS\n").replace(' FR b x', ' BV b x')
        case = self.case(suite='mps-core', arm='default', exactObjective=f'{5 * coefficient - 1}/5', finalWitness='v x=1')

        result = self.check(case, source)

        self.assertEqual(result['sourceQueryEncoding'], 'general')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_mps_query_preserves_a_nonbinary_integer_domain(self):
        source = "NAME fixture\nROWS\n N obj\n G row\nCOLUMNS\n M1 'MARKER' 'INTORG'\n x obj 1 row 1\n M2 'MARKER' 'INTEND'\nRHS\n rhs row 2\nBOUNDS\n UI b x 3\nENDATA\n"
        case = self.case(suite='mps-core', arm='default', objective=2, finalWitness='v x=2')

        result = self.check(case, source)

        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['optimalityCheck'], 'strictly better source objective unsat')

    def test_mps_ranges_define_exact_feasible_intervals(self):
        for kind, span, lo, hi in (('E', '2', 5, 7), ('E', '-2', 3, 5),
                                  ('L', '2', 3, 5), ('G', '-2', 5, 7)):
            with self.subTest(kind=kind, span=span):
                text = f'NAME fixture\nROWS\n N obj\n {kind} row\nCOLUMNS\n x row 1\nRHS\n rhs row 5\nRANGES\n ranges row {span}\nENDATA\n'
                constraints, _, variables, _ = proof_check.mps(text)
                for value, expected in ((lo, z3.sat), (hi, z3.sat),
                                        (lo - 1, z3.unsat), (hi + 1, z3.unsat)):
                    solver = z3.Solver()
                    solver.add(*constraints, variables['x'] == value)
                    self.assertEqual(solver.check(), expected)

    def test_mps_objective_offset_and_maximum_orientation(self):
        text = 'NAME fixture\nOBJSENSE\n MAX\nROWS\n N obj\nCOLUMNS\n x obj 3\nRHS\n rhs obj 2\nENDATA\n'
        _, objective, variables, maximize = proof_check.mps(text)
        self.assertTrue(maximize)
        self.assertEqual(str(z3.simplify(z3.substitute(objective, (variables['x'], z3.RealVal(4))))), '-10')

    def test_unsupported_mps_sections_cannot_be_ignored(self):
        with self.assertRaises(ValueError):
            proof_check.mps('NAME fixture\nQCMATRIX row\nENDATA\n')

    def test_inline_objective_orientation_cannot_be_ignored(self):
        with self.assertRaises(ValueError):
            proof_check.mps('NAME fixture\nOBJSENSE MAX\nENDATA\n')

    def test_model_objective_must_match_reported_incumbent(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(proven=False, objective=2))

    def test_distinct_mps_vectors_cannot_be_combined(self):
        prefix = 'NAME fixture\nROWS\n N obj\n E r1\n E r2\nCOLUMNS\n x r1 1\n y r2 1\n'
        for section in ('RHS\n a r1 1\n b r2 2\n',
                        'RANGES\n a r1 1\n b r2 2\n',
                        'BOUNDS\n LO a x 1\n UP b y 2\n'):
            with self.subTest(section=section.splitlines()[0]):
                with self.assertRaises(ValueError):
                    proof_check.mps(prefix + section + 'ENDATA\n')

    def test_unlabelled_mps_vector_continuation_keeps_the_selected_vector(self):
        text = 'NAME fixture\nROWS\n N obj\n E r1\n E r2\nCOLUMNS\n x r1 1\n y r2 1\nRHS\n a r1 1\n r2 2\nENDATA\n'
        constraints, _, variables, _ = proof_check.mps(text)
        solver = z3.Solver()
        solver.add(*constraints, variables['x'] == 1, variables['y'] == 2)
        self.assertEqual(solver.check(), z3.sat)

    def test_mps_tolerance_acceptance_is_separate_from_exact_feasibility(self):
        case = self.case(suite='mps-core', arm='default', proven=False,
                         finalWitness='v x=1.00000001', exactObjective='-0.099999999')
        result = self.check(case, self.mps_source)
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        self.assertNotIn('optimalityCheck', result)

    def test_mps_row_tolerance_rejects_excess_residuals(self):
        for value in ('0.99999989', '1.00000011'):
            with self.subTest(value=value), self.assertRaisesRegex(AssertionError, 'source tolerance'):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x={value}')
                self.check(case, self.mps_source)

    def test_mps_ranged_rows_scale_each_side_independently(self):
        for kind, span, rhs in (('E', '10', '0'), ('E', '-10', '10'), ('L', '10', '10'), ('G', '-10', '0')):
            source = f'NAME fixture\nROWS\n N obj\n {kind} row\nCOLUMNS\n x row 1\nRHS\n rhs row {rhs}\nRANGES\n r row {span}\nBOUNDS\n FR b x\nENDATA\n'
            for value, accepted in (('-0.0000001', True), ('-0.00000011', False),
                                    ('10.000001', True), ('10.0000011', False)):
                with self.subTest(kind=kind, span=span, value=value):
                    case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}')
                    if accepted:
                        self.assertTrue(self.check(case, source)['toleranceAccepted'])
                    else:
                        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                            self.check(case, source)

    def test_mps_column_bounds_use_bound_scale(self):
        for bound in ('LO', 'UP', 'FX'):
            source = f'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0\nBOUNDS\n {bound} b x 1\nENDATA\n'
            values = ('0.9999999', '0.99999989') if bound == 'LO' else ('1.0000001', '1.00000011')
            for value, accepted in zip(values, (True, False)):
                with self.subTest(bound=bound, value=value):
                    case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}')
                    if accepted:
                        self.assertTrue(self.check(case, source)['toleranceAccepted'])
                    else:
                        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                            self.check(case, source)

    def test_mps_integrality_is_exact_under_tolerance(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n m1 \'MARKER\' \'INTORG\'\n x obj 0\n m2 \'MARKER\' \'INTEND\'\nENDATA\n'
        for value in ('0.99999999', '1.00000001', '2'):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                self.check(self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=f'v x={value}'), source)

    def test_mps_invalid_coordinates_cannot_receive_tolerance_credit(self):
        for witness in ('', 'v', 'v y=1', 'v x=1 x=1', 'v x', 'v x=', 'v x=1=2',
                        'v x=NaN', 'v x=Infinity', 'v x=-inf', 'v x=1/0'):
            with self.subTest(witness=witness), self.assertRaises((AssertionError, ValueError, ZeroDivisionError)):
                self.check(self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                     finalWitness=witness), self.mps_source)

    def test_mps_objectives_are_checked_even_for_inexact_points(self):
        for objective in ('0', '-0.09999999', 'NaN', 'Infinity'):
            with self.subTest(objective=objective), self.assertRaises((AssertionError, ValueError)):
                self.check(self.case(suite='mps-core', arm='default', proven=False,
                                     finalWitness='v x=1.00000001', exactObjective=objective), self.mps_source)

    def test_mps_objective_uses_original_decimal_coefficients(self):
        for maximize in (False, True):
            source = self.mps_source.replace('ROWS', 'OBJSENSE\n MAX\nROWS') if maximize else self.mps_source
            result = self.check(self.case(suite='mps-core', arm='default', proven=False,
                                         finalWitness='v x=1', exactObjective='-0.1', maximize=maximize), source)
            self.assertEqual(result['objectiveCheck'], 'source point objective matches')

    def test_mps_legacy_objective_accepts_only_binary64_rounding(self):
        case = self.case(suite='mps-core', arm='default', proven=False,
                         finalWitness='v x=1.00000001', objective=-0.099999999)
        self.assertTrue(self.check(case, self.mps_source)['toleranceAccepted'])
        case['record']['objective'] = -0.09999999
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, self.mps_source)

    def test_mps_float_objective_can_use_encoded_binary64_coordinates(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0.1\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, finalWitness='v x=0.1',
                         exactObjective='3602879701896397/360287970189639680')
        self.assertEqual(self.check(case, source)['objectiveCheck'], 'source point objective matches')
        case['record']['exactObjective'] = '3602879701896398/360287970189639680'
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, source)

    def test_mps_large_objective_cannot_overflow_into_equality(self):
        source = 'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 1e308\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, finalWitness='v x=1e308',
                         exactObjective='1e616')
        self.assertEqual(self.check(case, source)['objectiveCheck'], 'source point objective matches')
        case['record']['exactObjective'] = '2e616'
        with self.assertRaisesRegex(AssertionError, 'objective'):
            self.check(case, source)

    def test_mps_nonfinite_binary64_activity_is_checked_with_exact_decimals(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1e308\n y row -1e308\nBOUNDS\n FR b x\n FR b y\nENDATA\n'
        for value, accepted in (('1.00000001e308', True), ('1.000001e308', False)):
            with self.subTest(value=value):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x=1e308 y={value}')
                if accepted:
                    result = self.check(case, source)
                    self.assertTrue(result['toleranceAccepted'])
                    self.assertEqual(result['witness'], 'not exact source feasible')
                else:
                    with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                        self.check(case, source)

    def test_mps_exact_mode_does_not_accept_tolerance_points(self):
        with self.assertRaises(AssertionError):
            self.check(self.case(suite='mps-core', proven=False, objective=None,
                                 finalWitness='v x=1.00000001'), self.mps_source)

    def test_complete_mps_witness_establishes_sat_when_unpinned_query_is_unknown(self):
        solver = z3.Solver()
        case = self.case(suite='mps-core', proven=False, objective='-1/10', finalWitness='v x=1')

        with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', return_value=z3.unknown):
            result = self.check(case, self.mps_source)

        self.assertEqual(result['verdictCheck'], 'source sat')
        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')

    def test_mps_witness_does_not_replace_strict_improvement_exclusion(self):
        solver = z3.Solver()
        case = self.case(suite='mps-core', objective='-1/10', finalWitness='v x=1')

        with mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', return_value=z3.unknown), \
                mock.patch.object(solver, 'reason_unknown', return_value='timeout'):
            with self.assertRaisesRegex(AssertionError, 'strictly better source objective exclusion.*unknown'):
                self.check(case, self.mps_source)

    def test_witness_only_check_preserves_the_unchecked_reported_proof(self):
        solver = z3.Solver()
        case = self.case(suite='mps-core', objective='-1/10', finalWitness='v x=1')

        with mock.patch.object(proof_check, 'source', return_value=self.mps_source), \
                mock.patch.object(proof_check.z3, 'Solver', return_value=solver), \
                mock.patch.object(solver, 'check', side_effect=AssertionError('proof query started')):
            result = proof_check.check([case], witness_only=True)[0]['check']

        self.assertEqual(result['witness'], 'exact source feasible')
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        self.assertTrue(result['reportedProven'])
        self.assertEqual(result['verificationScope'], 'witness-only')
        self.assertFalse(result['proofCertificateChecked'])
        self.assertNotIn('optimalityCheck', result)

    def test_witness_only_check_requires_an_actual_feasible_witness(self):
        for updates in ({'feasible': None}, {'feasible': False}, {'finalWitness': None}):
            with self.subTest(updates=updates):
                with self.assertRaisesRegex(AssertionError, 'requires a reported feasible witness'):
                    proof_check.check([self.case(**updates)], witness_only=True)

    def test_recorded_command_selects_exact_mode_independently_of_arm_name(self):
        case = self.case(suite='mps-core', arm='latest-main', command='klause-cli --exact fixture.mps',
                         proven=False, objective=None, finalWitness='v x=1.00000001')

        with self.assertRaises(AssertionError):
            self.check(case, self.mps_source)

    def test_recorded_default_command_overrides_a_legacy_exact_arm_name(self):
        case = self.case(suite='mps-core', arm='fast-exact', command='klause-cli fixture.mps',
                         proven=False, objective=None, finalWitness='v x=1.00000001')

        result = self.check(case, self.mps_source)

        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertTrue(result['toleranceAccepted'])

    def test_command_exactness_distinguishes_cached_results(self):
        cases = [self.case(suite='mps-core', arm='latest-main', command=command,
                           proven=False, objective=None, finalWitness='v x=1.00000001')
                 for command in ('klause-cli fixture.mps', 'klause-cli --exact fixture.mps')]
        cases[1]['index'] = 1
        with mock.patch.object(proof_check, 'source', return_value=self.mps_source):

            with self.assertRaises(AssertionError):
                proof_check.check(cases)

    def test_exact_objective_presence_distinguishes_cached_rounded_results(self):
        source = self.mps_source.replace(
            ' x obj 0.1 row 1', " M1 'MARKER' 'INTORG'\n x obj 1 row 1\n M2 'MARKER' 'INTEND'",
        ).replace('rhs row 1 obj 0.2', 'rhs row 9007199254740993')
        first = self.case(suite='mps-core', arm='default', proven=False,
                          finalWitness='v x=9007199254740993', objective=9007199254740992)
        second = self.case(suite='mps-core', arm='default', proven=False,
                           finalWitness='v x=9007199254740993', objective=9007199254740992,
                           exactObjective='9007199254740992')
        second['index'] = 1
        self.assertEqual(self.check(first, source)['objectiveCheck'], 'source point objective matches')
        with mock.patch.object(proof_check, 'source', return_value=source):

            with self.assertRaisesRegex(AssertionError, 'objective'):
                proof_check.check([first, second])

    def test_quoted_parameter_values_and_source_names_do_not_select_exact_mode(self):
        for command in ("klause-cli --param 'value=--exact' fixture.mps", 'klause-cli -- --exact'):
            with self.subTest(command=command):
                case = self.case(command=command)

                self.assertFalse(proof_check.exact_mode(case))

    def test_mps_tolerance_point_cannot_prove_a_rounded_optimum(self):
        source = self.mps_source.replace(' E row', ' G row')
        case = self.case(suite='mps-core', arm='default', finalWitness='v x=0.99999999',
                         exactObjective='-0.100000001')
        with self.assertRaises(AssertionError):
            self.check(case, source)

    def test_tolerance_optimum_keeps_point_checks_separate_from_exact_optimality(self):
        source = self.mps_source.replace(' E row', ' G row')
        case = self.case(suite='mps-core', arm='default', finalWitness='v x=0.99999999',
                         exactObjective='-0.100000001', stats={'optimizationStatus': 'tolerance-optimum'})

        result = self.check(case, source)

        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        self.assertNotIn('optimalityCheck', result)
        self.assertEqual(result['toleranceOptimalityCheck'],
                         'unavailable: native tolerance certificate not captured')

    def test_tolerance_optimum_cannot_weaken_exact_mode_or_smt_claims(self):
        for suite, command in (('mps-core', 'klause-cli --exact fixture.mps'),
                               ('smtlib-core', 'klause-cli fixture.smt2')):
            with self.subTest(suite=suite):
                case = self.case(suite=suite, command=command,
                                 stats={'optimizationStatus': 'tolerance-optimum'})
                with self.assertRaisesRegex(AssertionError, 'default MPS semantics'):
                    self.check(case)

    def test_mps_exact_optimum_can_be_checked_independently_of_rounded_point(self):
        source = self.mps_source.replace(' x obj 0.1 row 1', ' x row 1\n y obj 1').replace('rhs row 1 obj 0.2', 'rhs row 1')
        result = self.check(self.case(suite='mps-core', arm='default', finalWitness='v x=1.00000001 y=0',
                                      exactObjective='0'), source)
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertIn('optimalityCheck', result)

    def test_mps_infeasibility_is_checked_against_exact_source(self):
        source = self.mps_source.replace(' FR b x', ' FX b x 0.99999999')
        result = self.check(self.case(suite='mps-core', arm='default', feasible=False, objective=None,
                                     finalWitness=None), source)
        self.assertEqual(result['verdictCheck'], 'source unsat')
        self.assertNotIn('toleranceAccepted', result)

    def test_mps_rounded_egout_flow_coordinates_are_tolerance_accepted(self):
        source = 'NAME egout-flow\nROWS\n N COST\n E 031\n E 033\nCOLUMNS\n F....031 031 -1\n F.030031 COST 0.012 031 -1\n F.031032 COST 0.004 031 1\n F.029031 COST 0.012 031 -1\n F....033 033 -1\n F.032033 COST 0.002 033 -1\n F.033037 COST 0.004 033 1\nBOUNDS\n FX BOUNDS F....031 0.28\n FX BOUNDS F....033 0.19\nENDATA\n'
        witness = 'v F....031=0.28 F.030031=0.28 F.031032=7.140000000000001 F.029031=6.58 F....033=0.19 F.032033=89.85 F.033037=90.03999999999999'
        case = self.case(suite='mps-core', arm='default', proven=False, objective=0.65074,
                         finalWitness=witness)
        result = self.check(case, source)
        self.assertTrue(result['toleranceAccepted'])
        self.assertEqual(result['witness'], 'not exact source feasible')
        self.assertEqual(result['objectiveCheck'], 'source point objective matches')
        for before, after in (('7.140000000000001', '7.14001'), ('90.03999999999999', '90.0399')):
            with self.subTest(value=after), self.assertRaisesRegex(AssertionError, 'source tolerance'):
                case['record'].update(finalWitness=witness.replace(before, after), objective=None)
                self.check(case, source)

    def test_mps_indicator_rows_are_checked_only_when_active(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1\nBOUNDS\n BV b active\nINDICATORS\n IF row active 1\nENDATA\n'
        for active, value, accepted in ((0, '1', True), (1, '0.0000001', True), (1, '0.00000011', False)):
            with self.subTest(active=active, value=value):
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v active={active} x={value}')
                if accepted:
                    self.assertTrue(self.check(case, source)['toleranceAccepted'])
                else:
                    with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                        self.check(case, source)

    def test_mps_duplicate_coefficients_do_not_inflate_tolerance_scale(self):
        source = 'NAME fixture\nROWS\n N obj\n E row\nCOLUMNS\n x row 1e308\n x row -1e308\n y row 1\nENDATA\n'
        case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                         finalWitness='v x=1 y=0.00000011')
        with self.assertRaisesRegex(AssertionError, 'source tolerance'):
            self.check(case, source)

    def test_mps_exact_integer_objective_retains_values_beyond_binary64(self):
        for value in ('9007199254740993', '9223372036854775809'):
            for maximize in (False, True):
                with self.subTest(value=value, maximize=maximize):
                    sense = 'OBJSENSE\n MAX\n' if maximize else ''
                    source = f'NAME fixture\n{sense}ROWS\n N obj\nCOLUMNS\n x obj 1\nBOUNDS\n LI b x {value}\n UI b x {value}\nENDATA\n'
                    case = self.case(suite='mps-core', arm='default', finalWitness=f'v x={value}',
                                     exactObjective=value, objective=1, maximize=maximize)
                    result = self.check(case, source)
                    self.assertEqual(result['witness'], 'exact source feasible')
                    self.assertIn('optimalityCheck', result)
                    case['record']['exactObjective'] = str(int(value) + 1)
                    with self.assertRaisesRegex(AssertionError, 'objective'):
                        self.check(case, source)

    def test_mps_column_tolerance_uses_absolute_large_bound(self):
        for bound, accepted, rejected in (('10', '10.000001', '10.0000011'),
                                         ('-10', '-10.000001', '-10.0000011')):
            with self.subTest(bound=bound):
                source = f'NAME fixture\nROWS\n N obj\nCOLUMNS\n x obj 0\nBOUNDS\n FX b x {bound}\nENDATA\n'
                case = self.case(suite='mps-core', arm='default', proven=False, objective=None,
                                 finalWitness=f'v x={accepted}')
                self.assertTrue(self.check(case, source)['toleranceAccepted'])
                case['record']['finalWitness'] = f'v x={rejected}'
                with self.assertRaisesRegex(AssertionError, 'source tolerance'):
                    self.check(case, source)


if __name__ == '__main__':
    unittest.main()
