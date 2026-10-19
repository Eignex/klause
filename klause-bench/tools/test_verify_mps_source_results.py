"""Ground MPS witness checks cannot obtain a replacement point or proof from a solver."""
import contextlib
import importlib.machinery
import hashlib
import json
import tempfile
import types
import fractions
import pathlib
import unittest
from unittest import mock

import z3
import verify_source_results as verifier
import mps_witness_validation as worker
import mps_stdlib_runtime as stdlib_runtime


class MpsGroundTest(unittest.TestCase):
    fixture_root = pathlib.Path(__file__).resolve().parents[1] / 'smoke-corpus/mps-linear-source-v1'
    source = ('ROWS\n N cost\n E row\nCOLUMNS\n x cost 1 row 1\n z cost 0\n'
              'RHS\n r row 1\nBOUNDS\n FR b x\n FR b z\nENDATA\n')

    def setUp(self):
        self.guards = contextlib.ExitStack()
        self.addCleanup(self.guards.close)
        for name in ('Solver', 'SolverFor', 'SimpleSolver', 'Optimize', 'Tactic', 'Then', 'ParThen', 'TryFor'):
            self.guards.enter_context(mock.patch.object(z3, name, side_effect=AssertionError('solver call forbidden')))
        for name in ('source_query_solver', 'require_verdict', 'check_mps_root_bound', 'source', 'check'):
            self.guards.enter_context(mock.patch.object(verifier, name, side_effect=AssertionError('query fallback forbidden')))

    def check(self, witness='v x=1 z=0', objective='1', exact=False, source=None, **updates):
        record = dict(feasible=True, finalWitness=witness, kind='optimize', exactObjective=objective, maximize=False)
        record.update(updates)
        raw = 'o ' + str(objective) + '\ns SATISFIABLE\n' + witness + '\n'
        return verifier.check_mps_ground(source or self.source, record, raw, exact)

    def test_complete_sparse_source_point_does_not_query_or_certify_a_proof(self):
        checked = self.check(proven=True)
        self.assertTrue(checked['exactFeasible'])
        self.assertEqual(0, checked['solverSearchQueries'])
        self.assertFalse(checked['proofCertificateChecked'])

    def test_tolerance_boundary_is_ground_checked_without_nominal_sat_fallback(self):
        for value, accepted in [('1.0000001', True), ('1.00000010000002', False)]:
            with self.subTest(value=value):
                if accepted:
                    result = self.check('v x=' + value + ' z=0', value)
                    self.assertTrue(result['toleranceAccepted'])
                    self.assertFalse(result['exactFeasible'])
                else:
                    with self.assertRaisesRegex(AssertionError, 'exceeds original source tolerance'):
                        self.check('v x=' + value + ' z=0', value)
        with self.assertRaisesRegex(AssertionError, 'violates exact original source'):
            self.check('v x=1.0000001 z=0', '1.0000001', exact=True)

    def test_ground_checks_do_not_require_per_row_expression_walks_or_objective_fallbacks(self):
        with mock.patch.object(verifier, 'mps_point', side_effect=AssertionError('per-row walk forbidden')), \
             mock.patch.object(verifier, 'mps_objective_matches', side_effect=AssertionError('legacy fallback forbidden')):
            self.assertTrue(self.check()['exactFeasible'])
            self.assertTrue(self.check('v x=1.0000001 z=0', '1.0000001')['toleranceAccepted'])
            with self.assertRaisesRegex(AssertionError, 'exceeds original source tolerance'):
                self.check('v x=1.000001 z=0', '1.000001')

    def test_hoisted_ground_evaluation_agrees_with_pointwise_evaluation_on_existing_fixtures(self):
        fixtures = (
            ('original-decimal-cancellation', 'v I=1 X=1', '1'),
            ('bounded-decimal-constant', 'v I=0 X=1/10', '1/8'),
            ('indicator-disabled', 'v I=0 X=0', '0'),
            ('zero-row-disabled', 'v I=0', '0'),
            ('primal-point-max', 'v I=0 X=1', '13'),
            ('wide-bounded-mixed', 'v I=1 X=1', '1'),
            ('continuous-decimal', 'v X=1/3', '1/30'))
        for name, witness, reported in fixtures:
            source = (self.fixture_root / (name + '.mps')).read_text()
            for tolerance in (False, True):
                with self.subTest(name=name, tolerance=tolerance):
                    rows, objective, variables, maximize = verifier.mps(source, tolerance=tolerance, ground_source=True)
                    values = verifier.mps_values(witness, variables)
                    substitutions = verifier.mps_substitutions(variables, values)
                    expected = all(z3.is_true(verifier.mps_point(row, variables, values)) for row in rows)
                    actual = z3.is_true(verifier.mps_apply_point(z3.And(*rows), substitutions))
                    expected_objective = verifier.mps_point(objective, variables, values)
                    actual_objective = verifier.mps_apply_point(objective, substitutions)
                    self.assertEqual(expected, actual)
                    self.assertTrue(z3.is_true(z3.simplify(expected_objective == actual_objective)))
                    checked = self.check(witness, reported, exact=not tolerance, source=source, maximize=maximize)
                    self.assertTrue(checked['exactFeasible'])

    def test_native_grounding_preserves_range_infinity_and_cancellation_boundaries(self):
        sources = (
            ('ROWS\n N cost\n E row\nCOLUMNS\n x cost 0 row 100000000\n'
             ' x row -99999999\nRHS\n r row 0\nBOUNDS\n FR b x\nENDATA\n', ('v x=0', 'v x=1')),
            ('ROWS\n N cost\n L row\nCOLUMNS\n x cost 0 row 1\nRHS\n r row 1e20\n'
             'RANGES\n r row 1\nBOUNDS\n FR b x\nENDATA\n', ('v x=0', 'v x=99999999999999999999')),
            ('ROWS\n N cost\n E row\nCOLUMNS\n x cost 0 row 1\nRHS\n r row 1\n'
             'BOUNDS\n FR b x\nENDATA\n', ('v x=1.0000001', 'v x=1.00000010000002')))
        for source, witnesses in sources:
            for witness in witnesses:
                for tolerance in (False, True):
                    with self.subTest(witness=witness, tolerance=tolerance):
                        rows, _, variables, _ = verifier.mps(source, tolerance=tolerance, ground_source=True)
                        values = verifier.mps_values(witness, variables)
                        expected = all(z3.is_true(verifier.mps_point(row, variables, values)) for row in rows)
                        actual = z3.is_true(verifier.mps_apply_point(z3.And(*rows),
                                                                  verifier.mps_substitutions(variables, values)))
                        self.assertEqual(expected, actual)

    def test_ground_constant_objectives_need_no_source_variables_or_rows(self):
        source = 'ROWS\n N cost\nCOLUMNS\nRHS\n r cost -0.125\nENDATA\n'
        with mock.patch.object(verifier, 'mps_point', side_effect=AssertionError('per-row walk forbidden')):
            result = self.check('v ', '1/8', exact=True, source=source)
        self.assertTrue(result['exactFeasible'])

    def test_fraction_grounding_has_no_symbolic_model_or_solver_fallback(self):
        with contextlib.ExitStack() as guards:
            for name in ('mps', 'mps_point', 'mps_substitutions', 'mps_apply_point',
                         'mps_objective_matches', 'check_mps_z3_ground'):
                guards.enter_context(mock.patch.object(verifier, name, side_effect=AssertionError('symbolic fallback forbidden')))
            for name in ('Int', 'Real', 'IntVal', 'RealVal', 'And', 'If', 'Abs', 'substitute', 'simplify'):
                guards.enter_context(mock.patch.object(z3, name, side_effect=AssertionError('symbolic construction forbidden')))
            self.assertTrue(self.check()['exactFeasible'])
            self.assertTrue(self.check('v x=1.0000001 z=0', '1.0000001')['toleranceAccepted'])
            with self.assertRaisesRegex(AssertionError, 'exceeds original source tolerance'):
                self.check('v x=1.000001 z=0', '1.000001')

    def test_fraction_checker_matches_symbolic_ground_checker_for_valid_and_rejected_boundaries(self):
        cases = [(self.source, witness, objective, False) for witness, objective in (
            ('v x=1 z=0', '1'), ('v x=1.0000001 z=0', '1.0000001'),
            ('v x=1.00000010000002 z=0', '1.00000010000002'), ('v x=1 z=0', '2'),
            ('v x=1', '1'), ('v x=1 z=0 x=1', '1'), ('v x=NaN z=0', '1'))]
        for name, witness, objective, maximize in (
                ('bounded-decimal-constant', 'v I=0 X=1/10', '1/8', False),
                ('bounded-decimal-constant', 'v I=1/2 X=1/10', '1/8', False),
                ('original-decimal-cancellation', 'v I=1 X=1', '1', False),
                ('indicator-disabled', 'v I=0 X=0', '0', False),
                ('indicator-disabled', 'v I=1 X=0', '0', False),
                ('zero-row-disabled', 'v I=0', '0', False),
                ('wide-bounded-mixed', 'v I=1 X=1', '1', False),
                ('continuous-decimal', 'v X=1/3', '1/30', False),
                ('primal-point-max', 'v I=0 X=1', '13', True)):
            cases.append(((self.fixture_root / (name + '.mps')).read_text(), witness, objective, maximize))
        for source, witness, objective, maximize in cases:
            for exact in (False, True):
                with self.subTest(witness=witness, objective=objective, exact=exact):
                    record = dict(feasible=True, finalWitness=witness, kind='optimize', exactObjective=objective,
                                  maximize=maximize)
                    raw = 'o ' + objective + '\n' + witness + '\n'
                    outcomes = []
                    for checker in (verifier.check_mps_ground, verifier.check_mps_z3_ground):
                        try:
                            outcomes.append(('valid', checker(source, record, raw, exact)))
                        except (AssertionError, ValueError, ZeroDivisionError) as error:
                            outcomes.append((type(error).__name__, str(error)))
                    self.assertEqual(outcomes[0], outcomes[1])

    def test_fraction_grounding_matches_symbolic_ranges_infinity_and_signed_word_limits(self):
        for kind in ('L', 'G', 'E'):
            for span in ('-2', '0', '2'):
                for bound in ('0', '-9223372036854775808', '9223372036854775807', '100000000000000000000'):
                    source = ('ROWS\n N cost\n ' + kind + ' row\nCOLUMNS\n x cost 0 row 1\n'
                              'RHS\n r row ' + bound + '\nRANGES\n r row ' + span + '\nBOUNDS\n FR b x\nENDATA\n')
                    for point in (fractions.Fraction(bound) - 1, fractions.Fraction(bound), fractions.Fraction(bound) + 1):
                        witness = 'v x=' + str(point)
                        record = dict(feasible=True, finalWitness=witness, kind='optimize', exactObjective='0', maximize=False)
                        raw = 'o 0\n' + witness + '\n'
                        for exact in (False, True):
                            with self.subTest(kind=kind, span=span, bound=bound, point=point, exact=exact):
                                outcomes = []
                                for checker in (verifier.check_mps_ground, verifier.check_mps_z3_ground):
                                    try:
                                        outcomes.append(('valid', checker(source, record, raw, exact)))
                                    except AssertionError as error:
                                        outcomes.append(('invalid', str(error)))
                                self.assertEqual(outcomes[0], outcomes[1])

    def test_disabled_rows_still_reject_malformed_source_numbers(self):
        source = (self.fixture_root / 'indicator-disabled.mps').read_text().replace('X A 1 B 1', 'X A invalid B 1')
        for checker in (verifier.check_mps_ground, verifier.check_mps_z3_ground):
            with self.subTest(checker=checker.__name__), self.assertRaises(ValueError):
                checker(source, dict(feasible=True, finalWitness='v I=0 X=0', kind='optimize',
                    exactObjective='0', maximize=False), 'o 0\nv I=0 X=0\n', False)

    def test_missing_extra_duplicate_nonfinite_and_fractional_integer_coordinates_are_rejected(self):
        for witness in ('v x=1', 'v x=1 z=0 extra=0', 'v x=1 z=0 x=1',
                        'v x=NaN z=0', 'v x=Infinity z=0', 'v x=1/0 z=0'):
            with self.subTest(witness=witness):
                with self.assertRaises((AssertionError, ValueError, ZeroDivisionError)):
                    self.check(witness)
        source = (self.fixture_root / 'bounded-decimal-constant.mps').read_text()
        with self.assertRaisesRegex(AssertionError, 'fractional integer'):
            self.check('v I=1/2 X=1/10', '1/8', source=source)

    def test_original_decimal_terms_constant_objectives_and_sense_use_existing_fixtures(self):
        for name, witness, objective, maximize in (
                ('original-decimal-cancellation', 'v I=1 X=1', '1', False),
                ('bounded-decimal-constant', 'v I=0 X=1/10', '1/8', False),
                ('indicator-disabled', 'v I=0 X=0', '0', False),
                ('primal-point-max', 'v I=0 X=1', '13', True)):
            with self.subTest(name=name):
                result = self.check(witness, objective, exact=True, maximize=maximize,
                                    source=(self.fixture_root / (name + '.mps')).read_text())
                self.assertTrue(result['exactFeasible'])

    def test_separate_term_magnitudes_are_opt_in_and_preserve_legacy_proof_arithmetic(self):
        source = ('ROWS\n N cost\n E row\nCOLUMNS\n x cost 0 row 100000000\n'
                  ' x row -99999999\nRHS\n r row 0\nBOUNDS\n FR b x\nENDATA\n')
        result = self.check('v x=1', '0', source=source)
        self.assertTrue(result['toleranceAccepted'])
        rows, _, variables, _ = verifier.mps(source, tolerance=True)
        self.assertFalse(all(z3.is_true(verifier.mps_point(row, variables, {'x': fractions.Fraction(1)}))
                             for row in rows))

    def test_source_and_retained_stdout_objectives_must_both_match_the_point(self):
        with self.assertRaisesRegex(AssertionError, 'point objective differs'):
            self.check(objective='2')
        record = dict(feasible=True, finalWitness='v x=1 z=0', kind='optimize', exactObjective='1', maximize=False)
        with self.assertRaisesRegex(AssertionError, 'retained objective differs'):
            verifier.check_mps_ground(self.source, record, 'o 2\nv x=1 z=0\n', False)
        for raw in ('o 1\nv x=0 z=0\n', 'o 1\nv x=1 z=0\nv\n'):
            with self.subTest(raw=raw), self.assertRaisesRegex(AssertionError, 'retained final witness differs'):
                verifier.check_mps_ground(self.source, record, raw, False)

    def test_text_infinity_convention_applies_to_column_and_each_row_side(self):
        for kind, bound in (('G', '1e20'), ('L', '-1e20'), ('E', '1e20')):
            with self.subTest(kind=kind):
                source = ('ROWS\n N cost\n ' + kind + ' row\nCOLUMNS\n x cost 0 row 1\n'
                          'RHS\n r row ' + bound + '\nBOUNDS\n FX b x 1e20\nENDATA\n')
                self.assertTrue(self.check('v x=0', '0', exact=True, source=source)['exactFeasible'])
        source = ('ROWS\n N cost\n L row\nCOLUMNS\n x cost 0 row 1\n'
                  'RHS\n r row 1e20\nRANGES\n r row 1\nBOUNDS\n FR b x\nENDATA\n')
        with self.assertRaisesRegex(AssertionError, 'violates exact original source'):
            self.check('v x=0', '0', exact=True, source=source)
        self.assertTrue(self.check('v x=99999999999999999999', '0', exact=True, source=source)['exactFeasible'])
        source = source.replace('r row 1e20', 'r row 1e20 cost 1e20').replace('RANGES\n r row 1\n', '')
        self.assertTrue(self.check('v x=0', '-100000000000000000000', exact=True, source=source)['exactFeasible'])

    def test_exact_policy_header_must_preserve_original_declaration_defaults(self):
        record = dict(feasible=True, finalWitness='v x=1 z=0', kind='optimize', exactObjective='1',
                      maximize=False, command='cli --exact -- source.mps')
        header = ('c klause-source-semantics: format=MPS numeric=DECIMAL_LITERALS overrides=true '
                  'unbounded-int-lo=0 unbounded-int-hi=1 unbounded-real-lo=0 unbounded-real-hi=open '
                  'absolute-tolerance=0 relative-tolerance=0\n')
        result = verifier.check_mps_ground(self.source, record, header + 'o 1\nv x=1 z=0\n', True)
        self.assertTrue(result['exactFeasible'])
        with self.assertRaisesRegex(ValueError, 'producer command differs'):
            verifier.check_mps_ground(self.source, record, 'o 1\nv x=1 z=0\n', False)

    def test_overridden_defaults_and_unsupported_or_incomplete_sources_are_unknown(self):
        record = dict(feasible=True, finalWitness='v x=1 z=0', kind='optimize', exactObjective='1', maximize=False)
        with self.assertRaisesRegex(ValueError, 'overridden source semantics'):
            verifier.check_mps_ground(self.source, record,
                'c klause-source-semantics: unbounded-real-lo=-1\no 1\nv x=1 z=0', False)
        for source in (self.source.replace('ENDATA', ''), self.source.replace('ENDATA', 'QUADOBJ\nENDATA')):
            with self.subTest(source=source):
                with self.assertRaises(ValueError):
                    self.check(source=source)
        with self.assertRaisesRegex(ValueError, 'no complete reported feasible'):
            self.check(feasible=False, finalWitness=None)


class MpsArithmeticRuntimeTest(unittest.TestCase):
    executable = '/usr/bin/python3.13'

    @staticmethod
    def mapping(path, permissions='r-xp', inode='123'):
        return '1000-2000 ' + permissions + ' 00000000 08:01 ' + inode + ' ' + path + '\n'

    def test_provider_paths_cover_static_shared_and_stable_abi_interpreters(self):
        for library in (None, '/usr/lib/libpython3.13.so.1.0', '/usr/lib/libpython3.so'):
            with self.subTest(library=library):
                maps = self.mapping(self.executable)
                if library:
                    maps += self.mapping(library)
                self.assertEqual(sorted([self.executable] + ([library] if library else [])),
                                 worker.python_provider_paths(maps, self.executable, '3.13.7'))

    def test_malformed_ambiguous_or_unbounded_provider_maps_are_declined(self):
        valid = self.mapping(self.executable)
        malformed = ('', 'malformed\n', valid + '\n', valid.replace('\n', '\r\n'), valid.replace('1000-2000', '2000-1000'),
                     valid.replace('r-xp', 'rwxz'), valid.replace('08:01', 'device'),
                     self.mapping(self.executable, inode='0'), self.mapping(self.executable, 'r--p'),
                     self.mapping(self.executable + ' (deleted)'), self.mapping(self.executable + r'\040copy'),
                     self.mapping('/other/python'), valid + self.mapping('/lib/libpython3.12.so'),
                     valid + self.mapping('/lib/libpython3.13.so (deleted)'),
                     valid + self.mapping('/lib/libpython-unknown.so'),
                     valid + ''.join(self.mapping('/lib' + str(i) + '/libpython3.13.so') for i in range(3)),
                     valid * (worker.MAX_MAP_LINES + 1), 'x' * (worker.MAX_MAP_BYTES + 1))
        for maps in malformed:
            with self.subTest(size=len(maps), prefix=maps[:70]), self.assertRaises(AssertionError):
                worker.python_provider_paths(maps, self.executable, '3.13.7')

    def test_file_identity_hashes_exact_bytes_and_enforces_its_own_cap(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / 'provider'
            path.write_bytes(b'provider')
            self.assertEqual(dict(path=str(path), bytes=8, sha256=hashlib.sha256(b'provider').hexdigest()),
                             worker.file_identity(path, 8))
            with self.assertRaises(AssertionError):
                worker.file_identity(path, 7)
            path.write_bytes(b'')
            with self.assertRaises(AssertionError):
                worker.file_identity(path, 8)

    def test_file_backed_math_requires_its_actual_origin_and_mandatory_source_modules(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / 'math.so'
            path.write_bytes(b'math')
            module = types.SimpleNamespace(__name__='math', __file__=str(path),
                                           __spec__=types.SimpleNamespace(origin=str(path)))
            with mock.patch.object(worker, 'math', module):
                result = worker.arithmetic_runtime()
                self.assertIsNone(result['builtinMath'])
                self.assertEqual({'fractions', 'numbers', 'math'}, set(result['modules']))
                for origin in (None, 'built-in', 'frozen', str(path) + '-missing'):
                    module.__spec__.origin = origin
                    with self.subTest(origin=origin), self.assertRaises((AssertionError, FileNotFoundError)):
                        worker.arithmetic_runtime()
                module.__spec__.origin = str(path)
                for name in ('fractions', 'numbers'):
                    with mock.patch.object(worker, name, types.SimpleNamespace(__name__=name)), \
                         self.assertRaises(AttributeError):
                        worker.arithmetic_runtime()

    def test_builtin_math_binds_actual_linux_interpreter_and_mapped_providers(self):
        module = types.SimpleNamespace(__name__='math', __spec__=types.SimpleNamespace(
            origin='built-in', loader=importlib.machinery.BuiltinImporter))
        with mock.patch.object(worker, 'math', module), \
             mock.patch.object(worker.sys, 'builtin_module_names', worker.sys.builtin_module_names + ('math',)):
            result = worker.arithmetic_runtime()
            builtin = result['builtinMath']
            self.assertEqual({'fractions', 'numbers'}, set(result['modules']))
            self.assertEqual('built-in', builtin['origin'])
            self.assertEqual('_frozen_importlib.BuiltinImporter', builtin['loader'])
            self.assertEqual(hashlib.sha256(builtin['maps'].encode()).hexdigest(), builtin['mapsSha256'])
            executable = next(row for row in builtin['providers'] if row['path'] == builtin['executablePath'])
            self.assertEqual(result['pythonExecutableSha256'], executable['sha256'])
            for origin, loader in (('frozen', importlib.machinery.BuiltinImporter), ('built-in', None)):
                module.__spec__ = types.SimpleNamespace(origin=origin, loader=loader)
                with self.subTest(origin=origin, loader=loader), self.assertRaises(AssertionError):
                    worker.arithmetic_runtime()
            module.__spec__ = types.SimpleNamespace(origin='built-in', loader=importlib.machinery.BuiltinImporter)
            with mock.patch.object(worker.sys, 'builtin_module_names', ()), self.assertRaises(AssertionError):
                worker.arithmetic_runtime()


class MpsRuntimeFailureTest(unittest.TestCase):
    def test_dependency_failure_or_timeout_cannot_fall_back_to_producer_acceptance(self):
        for failure in ('setup-failure', 'timeout', 'overflow', 'incomplete'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                out = pathlib.Path(temporary)
                (out / 'tools').mkdir()
                tools = {}
                for name in worker.TOOLS:
                    raw = name.encode()
                    (out / 'tools' / name).write_bytes(raw)
                    tools[name] = hashlib.sha256(raw).hexdigest()
                for name in ('source.mps', 'stdout.txt', 'record.json', 'request.json'):
                    (out / name).write_text('{}')
                (out / 'tools.json').write_text(json.dumps(tools))
                result = dict(exit=1 if failure == 'setup-failure' else 0,
                              complete=failure != 'incomplete', timedOut=failure == 'timeout',
                              overflow=failure == 'overflow', parentStop=None)
                runner = types.SimpleNamespace(SIGNALS=set(), release=mock.Mock(), stopped=lambda: False,
                    file_digest=lambda path: hashlib.sha256(path.read_bytes()).hexdigest(),
                    save=lambda path, value: path.write_text(json.dumps(value)), owned=mock.Mock(return_value=result))
                with mock.patch.object(worker, 'load', return_value=runner):
                    worker.supervise(out, worker.bindings(out), tools, 123)
                checked = json.loads((out / 'result.json').read_text())
                self.assertEqual('unknown', checked['status'])
                self.assertIsNone(checked['checkedWitness'])
                self.assertTrue(checked['cleanupComplete'])
                self.assertEqual(1, runner.owned.call_count)
                self.assertEqual(0, checked['solverSearchQueries'])
                self.assertFalse(checked['proofCertificateChecked'])


class MpsStdlibRuntimeTest(unittest.TestCase):
    def test_stdlib_identity_binds_the_actual_interpreter_symbol_and_runtime_files(self):
        runtime = stdlib_runtime.identity(worker.file_identity)
        self.assertEqual('cpython-stdlib-ground-v1', runtime['implementation'])
        self.assertEqual({'ctypes', '_ctypes'}, set(runtime['modules']))
        self.assertIn(runtime['interpreterSymbol'], ('Py_GetVersion', '_Py_GetVersion'))
        self.assertFalse(runtime['globalInstallationPerformed'])
        for file in (runtime['executable'], runtime['interpreterImage'], *runtime['modules'].values()):
            self.assertEqual(hashlib.sha256(pathlib.Path(file['path']).read_bytes()).hexdigest(), file['sha256'])

    def test_ground_import_checks_original_points_without_importing_a_symbolic_solver(self):
        utility = pathlib.Path(worker.__file__).with_name('verify_source_results.py')
        original = __import__
        def importing(name, *args, **kwargs):
            if name == 'z3' or name.startswith('z3.'):
                raise AssertionError('symbolic import forbidden')
            return original(name, *args, **kwargs)
        with mock.patch('builtins.__import__', side_effect=importing):
            verifier = worker.load(utility, 'mps_ground_only')
            record = dict(feasible=True, finalWitness='v x=1 z=0', kind='optimize', exactObjective='1', maximize=False)
            checked = verifier.check_mps_ground(MpsGroundTest.source, record, 'o 1\nv x=1 z=0\n', False)
        self.assertTrue(checked['exactFeasible'])
        self.assertEqual(0, checked['solverSearchQueries'])
        self.assertFalse(checked['proofCertificateChecked'])

    def test_darwin_growth_limit_records_and_verifies_the_actual_kernel_limit(self):
        baseline, resident = 549755813888, 67108864
        for memory in (2147483648, 4294967296):
            with self.subTest(allowance=memory), tempfile.TemporaryDirectory() as directory:
                phase = 'bootstrap' if memory == 2147483648 else 'check'
                path = pathlib.Path(directory) / (phase + '.memory.json')
                limit = baseline + memory
                with mock.patch.object(stdlib_runtime, 'task_memory', return_value=(baseline, resident)), \
                     mock.patch.object(stdlib_runtime.resource, 'setrlimit') as setting, \
                     mock.patch.object(stdlib_runtime.resource, 'getrlimit', return_value=(limit, limit)):
                    stdlib_runtime.set_growth_limit(path, phase, memory)
                setting.assert_called_once_with(stdlib_runtime.resource.RLIMIT_AS, (limit, limit))
                control = json.loads(path.read_text())
                self.assertEqual(memory, control['allowanceBytes'])
                self.assertEqual(baseline, control['baselineVirtualBytes'])
                self.assertEqual(limit, control['addressSpaceLimitBytes'])
                self.assertFalse(control['postExecVerified'])

    def test_darwin_child_rejects_changed_kernel_limits_or_process_binding(self):
        baseline, resident, memory = 549755813888, 67108864, 2147483648
        limit = baseline + memory
        for failure in ('valid', 'pid', 'phase', 'allowance', 'kernel', 'virtual', 'resident'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                out = pathlib.Path(directory)
                path = out / 'bootstrap.memory.json'
                with mock.patch.object(stdlib_runtime, 'task_memory', return_value=(baseline, resident)), \
                     mock.patch.object(stdlib_runtime.resource, 'setrlimit'), \
                     mock.patch.object(stdlib_runtime.resource, 'getrlimit', return_value=(limit, limit)):
                    stdlib_runtime.set_growth_limit(path, 'bootstrap', memory)
                control = json.loads(path.read_text())
                if failure == 'pid':
                    control['pid'] += 1
                if failure == 'phase':
                    control['phase'] = 'check'
                if failure == 'allowance':
                    control['allowanceBytes'] += 1
                path.write_text(json.dumps(control))
                limits = (limit, limit + 1) if failure == 'kernel' else (limit, limit)
                size = (limit + 1, resident) if failure == 'virtual' else (baseline, memory + 1) \
                    if failure == 'resident' else (baseline, resident)
                with mock.patch.object(stdlib_runtime.resource, 'getrlimit', return_value=limits), \
                     mock.patch.object(stdlib_runtime, 'task_memory', return_value=size):
                    if failure == 'valid':
                        stdlib_runtime.verify_growth_limit(out, 'bootstrap')
                        self.assertTrue(json.loads(path.read_text())['postExecVerified'])
                    else:
                        with self.assertRaises(AssertionError):
                            stdlib_runtime.verify_growth_limit(out, 'bootstrap')
                        self.assertFalse(json.loads(path.read_text())['postExecVerified'])

    def test_darwin_owner_restores_the_private_runner_after_child_start_failure(self):
        original = stdlib_runtime.resource
        runner = types.SimpleNamespace(resource=original, owned=mock.Mock(side_effect=RuntimeError('start failed')))
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(stdlib_runtime, 'sys', types.SimpleNamespace(platform='darwin')):
            prefix = pathlib.Path(directory) / 'bootstrap'
            with self.assertRaisesRegex(RuntimeError, 'start failed'):
                stdlib_runtime.owned(runner, ['python3', '-I', 'worker.py', 'bootstrap'],
                                     prefix, directory, 30, 2147483648)
            self.assertIs(original, runner.resource)
            self.assertFalse(prefix.with_suffix('.memory.json').exists())


if __name__ == '__main__':
    unittest.main()
