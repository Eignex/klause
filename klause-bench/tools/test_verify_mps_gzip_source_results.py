"""Compressed stdout retains full-stream custody and the original ground obligations."""
import importlib.machinery
import types
import contextlib
import io
import json
import gzip
import hashlib
import pathlib
import tempfile
import unittest
from unittest import mock

import mps_stdout_gzip as transport
import verify_source_results as verifier
import mps_witness_validation as worker
import z3


class MpsGzipStdoutTest(unittest.TestCase):
    fixture = pathlib.Path(__file__).resolve().parents[1] / 'smoke-corpus/mps-linear-source-v1/bounded-decimal-constant.mps'

    def test_full_stream_above_raw_limit_retains_the_final_literal_point(self):
        raw = b'% progress\n' * 900_000 + b'o 1/8\nv I=0 X=1/10\n'
        record = dict(feasible=True, finalWitness='v I=0 X=1/10', kind='optimize',
                      exactObjective='1/8', maximize=False)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            retained, receipt = transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

        self.assertGreater(len(raw), transport.MAX_WIRE_BYTES)
        self.assertTrue(receipt['complete'])
        self.assertEqual(len(raw), receipt['decodedBytes'])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['decodedSha256'])
        self.assertTrue(verifier.check_mps_ground(self.fixture.read_text(), record, retained, False)['exactFeasible'])

    def test_a_changed_full_stream_hash_cannot_bind_the_final_point(self):
        raw = b'o 1/8\nv I=0 X=1/10\n'
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            with self.assertRaisesRegex(ValueError, 'differs from producer'):
                transport.envelope(path, '0' * 64, len(raw))

    def test_every_earlier_semantics_declaration_reaches_ground_checking(self):
        marker = b'% klause-source-semantics: overrides=true\n'
        raw = marker + b'% progress\n' * 900_000 + b'o 1/8\nv I=0 X=1/10\n'
        record = dict(feasible=True, finalWitness='v I=0 X=1/10', kind='optimize',
                      exactObjective='1/8', maximize=False)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            retained, _ = transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

        self.assertIn(marker.decode().strip(), retained)
        with self.assertRaisesRegex(ValueError, 'overridden source semantics'):
            verifier.check_mps_ground(self.fixture.read_text(), record, retained, False)

    def test_an_incomplete_later_point_cannot_fall_back_to_an_earlier_one(self):
        raw = b'o 1/8\nv I=0 X=1/10\no 0\nv I=0\n'
        record = dict(feasible=True, finalWitness='v I=0 X=1/10', kind='optimize',
                      exactObjective='1/8', maximize=False)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            retained, _ = transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

        with self.assertRaisesRegex(AssertionError, 'differs from stdout'):
            verifier.check_mps_ground(self.fixture.read_text(), record, retained, False)

    def test_decoded_bytes_are_bounded_even_for_high_compression(self):
        raw = b'% padding\n' * 1_000
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            with mock.patch.object(transport, 'MAX_DECODED_BYTES', len(raw) - 1):
                with self.assertRaisesRegex(ValueError, 'decoded stdout exceeds'):
                    transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw) - 1)

    def test_wire_bytes_are_bounded_before_decoding(self):
        raw = b'o 0\nv I=0\n'
        compressed = gzip.compress(raw, mtime=0)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(compressed)

            with mock.patch.object(transport, 'MAX_WIRE_BYTES', len(compressed) - 1):
                with self.assertRaisesRegex(ValueError, 'compressed stdout exceeds'):
                    transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_the_checked_envelope_is_bounded(self):
        raw = b'o 0\nv I=0\n'
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            with mock.patch.object(transport, 'MAX_ENVELOPE_BYTES', 4):
                with self.assertRaisesRegex(ValueError, 'envelope exceeds'):
                    transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_truncated_compressed_bytes_cannot_produce_a_complete_receipt(self):
        raw = b'o 0\nv I=0\n'
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0)[:-1])

            with self.assertRaises((EOFError, OSError)):
                transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_corrupt_crc_cannot_produce_a_complete_receipt(self):
        raw = b'o 0\nv I=0\n'
        compressed = bytearray(gzip.compress(raw, mtime=0))
        compressed[-8] ^= 1
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(compressed)

            with self.assertRaises(OSError):
                transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_concatenated_members_preserve_the_actual_last_point(self):
        first, second = b'o 1/8\nv I=0 X=1/10\n', b'o 0\nv I=0\n'
        raw = first + second
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(first, mtime=0) + gzip.compress(second, mtime=0))

            retained, receipt = transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

        self.assertEqual('v I=0\no 0\n', retained)
        self.assertEqual(len(raw), receipt['decodedBytes'])

    def test_long_lines_cannot_bypass_the_retained_envelope_bound(self):
        raw = b'% padding without a newline'
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))

            with mock.patch.object(transport, 'MAX_LINE_BYTES', len(raw) - 1):
                with self.assertRaisesRegex(ValueError, 'line exceeds'):
                    transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_transport_declarations_require_a_complete_hash_and_integer_size(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(b'', mtime=0))
            for size in (True, '0', -1, transport.MAX_DECODED_BYTES + 1):
                with self.subTest(size=size), self.assertRaises(ValueError):
                    transport.envelope(path, hashlib.sha256(b'').hexdigest(), size)
            for value in (None, 'missing', 'A' * 64):
                with self.subTest(hash=value), self.assertRaises(ValueError):
                    transport.envelope(path, value, 0)

    def test_compressed_worker_checks_original_rows_and_retains_complete_transport_custody(self):
        raw = b'% progress\n' * 900_000 + b'o 1/8\nv I=0 X=1/10\n'
        source = self.fixture.read_bytes()
        record = dict(feasible=True, finalWitness='v I=0 X=1/10', kind='optimize',
                      exactObjective='1/8', maximize=False, command='cli',
                      validationPolicy='mps-original-rational-ground-gzip-witness-v1',
                      sourceHashes={'model': hashlib.sha256(source).hexdigest()},
                      sourceOutputSha256=hashlib.sha256(raw).hexdigest())
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as guards:
            out = pathlib.Path(directory)
            (out / 'tools').mkdir()
            tools = pathlib.Path(__file__).resolve().parent
            for name in ('verify_source_results.py', 'mps_stdout_gzip.py'):
                (out / 'tools' / name).write_bytes((tools / name).read_bytes())
            (out / 'source.mps').write_bytes(source)
            (out / 'stdout.txt.gz').write_bytes(gzip.compress(raw, mtime=0))
            (out / 'record.json').write_text(json.dumps(record))
            (out / 'request.json').write_text(json.dumps(dict(exact=False, stdoutEncoding='gzip', decodedBytes=len(raw))))
            for name in ('Solver', 'SolverFor', 'SimpleSolver', 'Optimize', 'Tactic', 'Then', 'ParThen', 'TryFor'):
                guards.enter_context(mock.patch.object(z3, name, side_effect=AssertionError('query forbidden')))
            stdout = io.StringIO()

            with contextlib.redirect_stdout(stdout):
                worker.check(out, pathlib.Path(z3.__file__).resolve().parents[1])

            checked = json.loads(stdout.getvalue())
            self.assertEqual('valid', checked['status'])
            self.assertEqual(record['finalWitness'], checked['checkedWitness'])
            self.assertEqual(0, checked['solverSearchQueries'])
            self.assertFalse(checked['proofCertificateChecked'])
            self.assertEqual(worker.bindings(out), checked['bindings'])
            custody = checked['stdoutTransport']
            self.assertTrue(custody['complete'])
            self.assertEqual(record['sourceOutputSha256'], custody['decodedSha256'])
            expected_modules = {'gzip', '_compression'} if custody['decoderRuntime']['builtinZlib'] else {'gzip', 'zlib', '_compression'}
            self.assertEqual(expected_modules, set(custody['decoderRuntime']['modules']))
            self.assertEqual(hashlib.sha256((out / 'stdout.txt.gz').read_bytes()).hexdigest(), custody['wireSha256'])

    def test_compressed_exact_and_tolerance_requests_keep_the_original_ground_policy(self):
        source = ('ROWS\n N cost\n E row\nCOLUMNS\n x cost 1 row 1\n'
                  'RHS\n r row 1\nBOUNDS\n FR b x\nENDATA\n')
        raw = b'o 1.0000001\nv x=1.0000001\n'
        record = dict(feasible=True, finalWitness='v x=1.0000001', kind='optimize',
                      exactObjective='1.0000001', maximize=False)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.txt.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))
            retained, _ = transport.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

        checked = verifier.check_mps_ground(source, record, retained, False)
        self.assertTrue(checked['toleranceAccepted'])
        self.assertFalse(checked['exactFeasible'])
        with self.assertRaisesRegex(AssertionError, 'violates exact original source'):
            verifier.check_mps_ground(source, record, retained, True)

    def test_compressed_policy_requires_explicit_exact_encoding_and_size_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            for request in (dict(exact=False, stdoutEncoding='raw', decodedBytes=1),
                            dict(exact=False, stdoutEncoding='gzip'),
                            dict(exact='false', stdoutEncoding='gzip', decodedBytes=1),
                            dict(exact=False, stdoutEncoding='gzip', decodedBytes=True),
                            dict(exact=False, stdoutEncoding='gzip', decodedBytes=1, suffix=True)):
                (out / 'request.json').write_text(json.dumps(request))
                with self.subTest(request=request), self.assertRaises(AssertionError):
                    worker.request_policy(out)

    def test_builtin_zlib_binds_interpreter_and_mapped_providers(self):
        module = types.SimpleNamespace(__name__='zlib', __spec__=types.SimpleNamespace(
            origin='built-in', loader=importlib.machinery.BuiltinImporter),
            ZLIB_VERSION=transport.zlib.ZLIB_VERSION, ZLIB_RUNTIME_VERSION=transport.zlib.ZLIB_RUNTIME_VERSION)
        with mock.patch.object(transport, 'zlib', module), \
             mock.patch.object(worker.sys, 'builtin_module_names', (*worker.sys.builtin_module_names, 'zlib')):

            identity = transport.runtime_identity(worker.file_identity, worker.builtin_module_identity)

        self.assertEqual({'gzip', '_compression'}, set(identity['modules']))
        builtin = identity['builtinZlib']
        self.assertEqual('built-in', builtin['origin'])
        self.assertEqual('_frozen_importlib.BuiltinImporter', builtin['loader'])
        self.assertEqual(hashlib.sha256(builtin['maps'].encode()).hexdigest(), builtin['mapsSha256'])
        self.assertEqual(identity['pythonExecutableSha256'], next(
            row['sha256'] for row in builtin['providers'] if row['path'] == builtin['executablePath']))

    def test_builtin_zlib_rejects_unlisted_or_ambiguous_origins_and_loaders(self):
        module = types.SimpleNamespace(__name__='zlib', __spec__=types.SimpleNamespace(
            origin='built-in', loader=importlib.machinery.BuiltinImporter),
            ZLIB_VERSION=transport.zlib.ZLIB_VERSION, ZLIB_RUNTIME_VERSION=transport.zlib.ZLIB_RUNTIME_VERSION)
        for failure in ('unlisted', 'origin', 'loader'):
            module.__spec__.origin = 'frozen' if failure == 'origin' else 'built-in'
            module.__spec__.loader = object if failure == 'loader' else importlib.machinery.BuiltinImporter
            names = () if failure == 'unlisted' else (*worker.sys.builtin_module_names, 'zlib')
            with self.subTest(failure=failure), mock.patch.object(transport, 'zlib', module), \
                 mock.patch.object(worker.sys, 'builtin_module_names', names), self.assertRaises(AssertionError):
                transport.runtime_identity(worker.file_identity, worker.builtin_module_identity)

    def test_non_file_backed_gzip_cannot_borrow_builtin_zlib_authority(self):
        module = types.SimpleNamespace(__name__='gzip', __spec__=types.SimpleNamespace(
            origin='built-in', loader=importlib.machinery.BuiltinImporter))
        with mock.patch.object(transport, 'gzip', module), self.assertRaises(AssertionError):
            transport.runtime_identity(worker.file_identity, worker.builtin_module_identity)


if __name__ == '__main__':
    unittest.main()
