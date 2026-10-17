import copy
from contextlib import ExitStack
import gzip
import hashlib
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import jvm_source_syntax as syntax


class JvmSourceSyntaxTest(unittest.TestCase):
    def test_overlapping_ancestors_and_empty_end_boundary_nodes_remain_candidates(self):
        text = 'a\n'
        checksum = hashlib.sha256(text.encode()).hexdigest()
        specification = {'schema': 1, 'sources': [{'id': 'root', 'path': 'p/Root.kt', 'sourceFile': 'Root.kt',
            'sourceText': text, 'sha256': checksum, 'packages': ['p'], 'members': []}]}
        records = [
            {'record': 'schema', 'encoding': syntax.ENCODING,
             'nodeColumns': syntax.NODE_COLUMNS, 'rangeColumns': syntax.RANGE_COLUMNS},
            {'record': 'source-start', 'sourceIndex': 0, 'sourceId': 'root', 'path': 'p/Root.kt', 'sha256': checksum},
            {'record': 'psi-kind', 'index': 0, 'class': 'File'},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [0, None, 'source-file', 0, 'Root.kt', None, 'p',
                [0, 2, 0, 2, 1, 1, checksum], None, None, False, False]},
            {'record': 'psi-kind', 'index': 1, 'class': 'Node'},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [1, 0, 'expression', 1, None, None, None,
                [0, 1, 0, 1, 1, 1, hashlib.sha256(b'a').hexdigest()], None, None, False, False]},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [2, 0, 'declaration', 1, None, None, None,
                [2, 2, 2, 2, 2, 2, hashlib.sha256(b'').hexdigest()], None, None, False, False]},
            {'record': 'source-end', 'sourceIndex': 0, 'syntaxNodes': 3, 'syntaxErrors': 0}]
        raw = b''.join(json.dumps(row).encode() + b'\n' for row in records)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'syntax.gz'
            path.write_bytes(gzip.compress(raw))
            completion = {'completed': True, 'sources': 1, 'records': len(records), 'syntaxErrors': 0,
                'allInputSourcesVisited': True, 'allSourcesSyntacticallyValid': True,
                'reportEncoding': syntax.ENCODING, 'reportBytes': path.stat().st_size,
                'reportSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'uncompressedReportBytes': len(raw), 'uncompressedReportSha256': hashlib.sha256(raw).hexdigest(),
                'completeGzipRoundTripVerified': True, **{flag: False for flag in syntax.DENIED}}

            index = syntax.SourceSyntaxIndex(specification, path, completion)

            self.assertTrue(index.complete)
            self.assertEqual((0, 1), index.query(0, 1))
            self.assertEqual((2,), index.query(0, 2))
            self.assertEqual((), index.query(0, 99))
            self.assertEqual(3, index.node_count)
            index.mark(0, index.query(0, 2))
            self.assertEqual(bytearray([0, 0, 1]), index.nodes[0].hits)
            with patch.object(syntax, 'MAX_QUERY_NODES', 1), self.assertRaises(ValueError):
                index.nodes[0].query(1)

    def test_utf16_surrogates_utf8_and_crlf_positions_use_unchanged_original_bytes(self):
        offsets = syntax.SourceOffsets('\U0001f642\r\nx\rz')
        original = [4, 5, 6, 7, 2, 2, hashlib.sha256(b'x').hexdigest()]

        self.assertEqual((4, 5, 2, 2), offsets.check(original))
        self.assertEqual((6, 7, 3, 3), offsets.check([6, 7, 8, 9, 3, 3, hashlib.sha256(b'z').hexdigest()]))
        for variant in ('split-surrogate', 'byte', 'line', 'hash', 'boolean'):
            changed = list(original)
            if variant == 'split-surrogate':
                changed[0] = 1
            elif variant == 'byte':
                changed[3] = 8
            elif variant == 'line':
                changed[4] = 1
            elif variant == 'hash':
                changed[6] = 'a' * 64
            else:
                changed[0] = True
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                offsets.check(changed)
        with self.assertRaises(UnicodeError):
            syntax.SourceOffsets('\ud800')

    def test_changed_source_report_boundaries_censuses_and_scope_cannot_be_admitted(self):
        checksum = hashlib.sha256(b'x').hexdigest()
        original_sources = {'schema': 1, 'sources': [{'id': 'root', 'path': 'p/Root.kt', 'sourceFile': 'Root.kt',
            'sourceText': 'x', 'sha256': checksum, 'packages': ['p'], 'members': []}]}
        original_records = [
            {'record': 'schema', 'encoding': syntax.ENCODING,
             'nodeColumns': syntax.NODE_COLUMNS, 'rangeColumns': syntax.RANGE_COLUMNS},
            {'record': 'source-start', 'sourceIndex': 0, 'sourceId': 'root', 'path': 'p/Root.kt', 'sha256': checksum},
            {'record': 'psi-kind', 'index': 0, 'class': 'File'},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [0, None, 'source-file', 0, 'Root.kt', None, 'p',
                [0, 1, 0, 1, 1, 1, checksum], None, None, False, False]},
            {'record': 'source-end', 'sourceIndex': 0, 'syntaxNodes': 1, 'syntaxErrors': 0}]
        variants = ('source-hash', 'report-hash', 'schema', 'parent', 'package', 'byte-range', 'dictionary',
                    'start', 'end', 'missing-end', 'records', 'bytes', 'scope', 'node-scope', 'unknown',
                    'duplicate-class', 'boolean-end', 'boolean-index', 'source-bound', 'report-bound',
                    'node-bound', 'kind-bound', 'json-bound', 'depth-bound')
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                sources, records = copy.deepcopy(original_sources), copy.deepcopy(original_records)
                bounds = {}
                if variant == 'source-hash':
                    sources['sources'][0]['sourceText'] = 'y'
                elif variant == 'schema':
                    records[0]['nodeColumns'].reverse()
                elif variant == 'parent':
                    records[3]['node'][1] = 0
                elif variant == 'package':
                    records[3]['node'][6] = None
                elif variant == 'byte-range':
                    records[3]['node'][7][3] = 0
                elif variant == 'dictionary':
                    records[3]['node'][3] = 1
                elif variant == 'start':
                    records[1]['path'] = 'Other.kt'
                elif variant == 'end':
                    records[-1]['syntaxNodes'] = 2
                elif variant == 'missing-end':
                    records.pop()
                elif variant == 'node-scope':
                    records[3]['node'][-1] = True
                elif variant == 'unknown':
                    records[-1]['record'] = 'other'
                elif variant == 'duplicate-class':
                    records.insert(3, {'record': 'psi-kind', 'index': 1, 'class': 'File'})
                elif variant == 'boolean-end':
                    records[-1]['syntaxNodes'] = True
                elif variant == 'boolean-index':
                    records[3]['node'][0] = False
                elif variant.endswith('-bound'):
                    key = {'source-bound': 'MAX_SOURCE_BYTES', 'report-bound': 'MAX_REPORT_BYTES',
                           'node-bound': 'MAX_NODES', 'kind-bound': 'MAX_KINDS', 'json-bound': 'MAX_JSON_BYTES',
                           'depth-bound': 'MAX_DEPTH'}[variant]
                    bounds[key] = 0
                    if variant == 'depth-bound':
                        child = copy.deepcopy(records[3])
                        child['node'][0:3] = [1, 0, 'expression']
                        child['node'][6] = None
                        records.insert(-1, child)
                        records[-1]['syntaxNodes'] = 2
                raw = b''.join(json.dumps(row).encode() + b'\n' for row in records)
                path = Path(directory) / 'syntax.gz'
                path.write_bytes(gzip.compress(raw))
                completion = {'completed': True, 'sources': 1, 'records': len(records), 'syntaxErrors': 0,
                    'allInputSourcesVisited': True, 'allSourcesSyntacticallyValid': True,
                    'reportEncoding': syntax.ENCODING, 'reportBytes': path.stat().st_size,
                    'reportSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                    'uncompressedReportBytes': len(raw), 'uncompressedReportSha256': hashlib.sha256(raw).hexdigest(),
                    'completeGzipRoundTripVerified': True, **{flag: False for flag in syntax.DENIED}}
                if variant == 'report-hash':
                    completion['reportSha256'] = 'a' * 64
                elif variant == 'records':
                    completion['records'] += 1
                elif variant == 'bytes':
                    completion['uncompressedReportBytes'] += 1
                elif variant == 'scope':
                    completion['loadedClassSelectionProven'] = True

                with ExitStack() as stack:
                    for name, value in bounds.items():
                        stack.enter_context(patch.object(syntax, name, value))
                    with self.assertRaises(ValueError):
                        syntax.SourceSyntaxIndex(sources, path, completion)

    def test_original_syntax_errors_keep_the_complete_node_set_without_completion(self):
        checksum = hashlib.sha256(b'x').hexdigest()
        specification = {'schema': 1, 'sources': [{'id': 'root', 'path': 'Root.kt', 'sourceFile': 'Root.kt',
            'sourceText': 'x', 'sha256': checksum, 'packages': [''], 'members': []}]}
        records = [
            {'record': 'schema', 'encoding': syntax.ENCODING,
             'nodeColumns': syntax.NODE_COLUMNS, 'rangeColumns': syntax.RANGE_COLUMNS},
            {'record': 'source-start', 'sourceIndex': 0, 'sourceId': 'root', 'path': 'Root.kt', 'sha256': checksum},
            {'record': 'psi-kind', 'index': 0, 'class': 'File'},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [0, None, 'source-file', 0, 'Root.kt', None, '',
                [0, 1, 0, 1, 1, 1, checksum], None, None, False, False]},
            {'record': 'psi-kind', 'index': 1, 'class': 'Error'},
            {'record': 'syntax-node', 'sourceIndex': 0, 'node': [1, 0, 'syntax-error', 1, None, None, None,
                [1, 1, 1, 1, 1, 1, hashlib.sha256(b'').hexdigest()], 'original error', None, False, False]},
            {'record': 'source-end', 'sourceIndex': 0, 'syntaxNodes': 2, 'syntaxErrors': 1}]
        raw = b''.join(json.dumps(row).encode() + b'\n' for row in records)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'syntax.gz'
            path.write_bytes(gzip.compress(raw))
            completion = {'completed': False, 'sources': 1, 'records': len(records), 'syntaxErrors': 1,
                'allInputSourcesVisited': True, 'allSourcesSyntacticallyValid': False,
                'reportEncoding': syntax.ENCODING, 'reportBytes': path.stat().st_size,
                'reportSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'uncompressedReportBytes': len(raw), 'uncompressedReportSha256': hashlib.sha256(raw).hexdigest(),
                'completeGzipRoundTripVerified': True, **{flag: False for flag in syntax.DENIED}}

            index = syntax.SourceSyntaxIndex(specification, path, completion)

            self.assertFalse(index.complete)
            self.assertEqual(2, index.node_count)
            self.assertEqual(1, index.errors)
            self.assertEqual((0, 1), index.query(0, 1))

    def test_consumer_column_schema_matches_the_committed_compiler_producer(self):
        path = Path(__file__).resolve().parents[1] / \
            'src/main/kotlin/com/eignex/klause/bench/profile/JvmSourceSyntaxEncoding.kt'
        source = path.read_text()
        node = source.split('val NODE_COLUMNS = listOf(', 1)[1].split(')', 1)[0]
        ranges = source.split('val RANGE_COLUMNS = listOf(', 1)[1].split(')', 1)[0]

        self.assertEqual(syntax.NODE_COLUMNS, re.findall(r'"([^"]+)"', node))
        self.assertEqual(syntax.RANGE_COLUMNS, re.findall(r'"([^"]+)"', ranges))
        self.assertIn('const val ENCODING = "' + syntax.ENCODING + '"', source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
