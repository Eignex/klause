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
import zlib
import os
import mps_stdout_deflate as portable


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


    def test_stdlib_supervisor_checks_a_compressed_original_point_without_provisioning_dependencies(self):
        raw = b'% progress\n' * 40_000 + b'o 1/8\nv I=0 X=1/10\n'
        source = self.fixture.read_bytes()
        record = dict(feasible=True, finalWitness='v I=0 X=1/10', kind='optimize',
                      exactObjective='1/8', maximize=False, command='cli',
                      validationPolicy='mps-original-rational-ground-gzip-witness-v1',
                      sourceHashes={'model': hashlib.sha256(source).hexdigest()},
                      sourceOutputSha256=hashlib.sha256(raw).hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            (out / 'tools').mkdir()
            names = worker.TOOLS | {'mps_stdout_gzip.py'}
            tools = {}
            for name in names:
                content = pathlib.Path(worker.__file__).with_name(name).read_bytes()
                (out / 'tools' / name).write_bytes(content)
                tools[name] = hashlib.sha256(content).hexdigest()
            (out / 'tools.json').write_text(json.dumps(tools))
            (out / 'source.mps').write_bytes(source)
            (out / 'stdout.txt.gz').write_bytes(gzip.compress(raw, mtime=0))
            (out / 'record.json').write_text(json.dumps(record))
            (out / 'request.json').write_text(json.dumps(dict(exact=False, stdoutEncoding='gzip', decodedBytes=len(raw))))

            worker.supervise(out, worker.bindings(out), tools, os.getppid())

            checked = json.loads((out / 'result.json').read_text())
            self.assertEqual('valid', checked['status'], checked.get('reason'))
            self.assertEqual(record['finalWitness'], checked['checkedWitness'])
            self.assertTrue(checked['cleanupComplete'])
            self.assertEqual(0, checked['solverSearchQueries'])
            self.assertFalse(checked['proofCertificateChecked'])
            self.assertNotIn('runtime', checked)
            self.assertEqual('cpython-stdlib-ground-v1', checked['stdlibRuntime']['implementation'])
            self.assertEqual('python-deflate-gzip-v1', checked['stdoutTransport']['decoderRuntime']['implementation'])
            for phase, memory in (('bootstrap', 2147483648), ('check', 4294967296)):
                process = checked[phase + 'Process']
                self.assertTrue(process['complete'])
                self.assertEqual(0, process['exit'])
                self.assertEqual(30, process['timeoutSeconds'])
                self.assertEqual(memory, process['addressSpaceBytes'])


class MpsPortableGzipTest(unittest.TestCase):
    def test_portable_decoding_matches_native_gzip_for_stored_fixed_and_dynamic_blocks(self):
        raw_cases = (b'', b'o 1/8\nv I=0 X=1/10\n', bytes(range(256)) * 300,
                     b'% progress\n' * 40_000 + b'o 1/8\nv I=0 X=1/10\n')
        for raw in raw_cases:
            for level, strategy in ((0, zlib.Z_DEFAULT_STRATEGY), (6, zlib.Z_FIXED),
                                    (1, zlib.Z_DEFAULT_STRATEGY), (9, zlib.Z_DEFAULT_STRATEGY)):
                with self.subTest(bytes=len(raw), level=level, strategy=strategy):
                    encoder = zlib.compressobj(level, zlib.DEFLATED, 31, strategy=strategy)
                    compressed = encoder.compress(raw) + encoder.flush()
                    self.assertEqual(gzip.decompress(compressed), portable.decode(compressed))

    def test_portable_decoding_checks_every_member_and_its_crc_and_size(self):
        members = [gzip.compress(raw, mtime=0) for raw in (b'first\n', b'', b'last\n')]
        self.assertEqual(b'first\nlast\n', portable.decode(b''.join(members)))
        for index in range(len(members)):
            for offset in (-8, -4):
                changed = bytearray(members[index])
                changed[offset] ^= 1
                with self.subTest(member=index, field=offset), self.assertRaises(ValueError):
                    portable.decode(b''.join(members[:index] + [changed] + members[index + 1:]))

    def test_portable_decoding_rejects_truncation_reserved_blocks_and_trailing_data(self):
        compressed = gzip.compress(b'o 1\nv x=1\n', mtime=0)
        for size in range(len(compressed)):
            with self.subTest(truncated=size), self.assertRaises(ValueError):
                portable.decode(compressed[:size])
        for suffix in (b'x', b'\0', b'\x1f\x8b'):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                portable.decode(compressed + suffix)
        changed = bytearray(compressed)
        changed[10] = (changed[10] & ~7) | 7
        with self.assertRaisesRegex(ValueError, 'reserved deflate block'):
            portable.decode(changed)

    def test_portable_decoding_applies_the_decoded_limit_across_member_boundaries(self):
        member = gzip.compress(b'x' * 32, mtime=0)
        self.assertEqual(b'x' * 64, portable.decode(member + member, maximum=64))
        for maximum in (0, 31, 63):
            with self.subTest(maximum=maximum), self.assertRaises(ValueError):
                portable.decode(member + member, maximum=maximum)

    def test_portable_decoding_verifies_optional_header_fields_and_header_crc(self):
        compressed = gzip.compress(b'point', mtime=0)
        header = compressed[:3] + bytes([30]) + compressed[4:10] + b'\x03\0abcname\0comment\0'
        encoded = header + (portable.crc32(header) & 65535).to_bytes(2, 'little') + compressed[10:]
        self.assertEqual(b'point', portable.decode(encoded))
        changed = bytearray(encoded)
        changed[len(header)] ^= 1
        with self.assertRaisesRegex(ValueError, 'header CRC'):
            portable.decode(changed)

    def test_portable_envelope_matches_existing_transport_and_preserves_physical_line_limit(self):
        raw = b'% old\no 1\nv x=0\n% klause-source-semantics: test\no 2\nv x=1\n'
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'stdout.gz'
            path.write_bytes(gzip.compress(raw, mtime=0))
            arguments = (path, hashlib.sha256(raw).hexdigest(), len(raw))
            self.assertEqual(transport.envelope(*arguments), portable.envelope(*arguments))
            raw = b'a\r' * 16 + b'\n'
            path.write_bytes(gzip.compress(raw, mtime=0))
            with mock.patch.object(portable, 'MAX_LINE_BYTES', 16), self.assertRaisesRegex(ValueError, 'stdout line'):
                portable.envelope(path, hashlib.sha256(raw).hexdigest(), len(raw))

    def test_portable_decoding_uses_no_native_compression_or_crc_fallback(self):
        raw = b'% progress\n' * 20_000
        compressed = gzip.compress(raw, mtime=0)
        with contextlib.ExitStack() as guards:
            for name in ('decompress', 'decompressobj', 'crc32'):
                guards.enter_context(mock.patch.object(zlib, name, side_effect=AssertionError('native fallback')))
            guards.enter_context(mock.patch.object(gzip, 'decompress', side_effect=AssertionError('native fallback')))
            self.assertEqual(raw, portable.decode(compressed))


if __name__ == '__main__':
    unittest.main()
