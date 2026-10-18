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

import jvm_bytecode_source_bindings as bindings
import review_jvm_bytecode_sources as reader
from review_jvm_source_lines import encode_report_record, expand_report_record


class JvmBytecodeSourceBindingsTest(unittest.TestCase):
    def setUp(self):
        self.code = {'identity': {'bytes': 4, 'sha256': 'a' * 64},
            'instructions': [{'bci': 0, 'byteLength': 3}, {'bci': 3, 'byteLength': 1}],
            'lineNumberTables': [
                [{'startPc': 3, 'lineNumber': 9, 'instructionBoundary': True},
                 {'startPc': 0, 'lineNumber': 4, 'instructionBoundary': True},
                 {'startPc': 0, 'lineNumber': 5, 'instructionBoundary': True}],
                [{'startPc': 1, 'lineNumber': 6, 'instructionBoundary': False},
                 {'startPc': 9, 'lineNumber': 7, 'instructionBoundary': False}]]}
        self.method = {'methodIndex': 0, 'name': 'privateGenerated', 'descriptor': '()V',
                       'accessFlags': 2, 'code': self.code}
        self.previous = {'methodIndex': 0, 'name': 'privateGenerated', 'descriptor': '()V',
            'accessFlags': 2, 'codePresent': True, 'originalLineNumberTablesCopiedVerbatim': [
                [{key: entry[key] for key in ('startPc', 'lineNumber')} for entry in table]
                for table in self.code['lineNumberTables']]}

    def test_duplicate_and_unordered_debug_points_keep_original_positions(self):
        result = bindings.bind_method(self.method, self.previous)

        self.assertEqual([[0, [1, 2]], [3, [0]]], result['instructionLines'])
        self.assertEqual([9, 4, 5, 6, 7], [entry['lineNumber'] for entry in result['lineEntries']])
        self.assertEqual([True, True, True, True, False], [entry['withinCode'] for entry in result['lineEntries']])
        self.assertFalse(result['lineEntries'][3]['instructionBoundary'])

    def test_unannotated_code_prefix_stays_unmatched(self):
        self.code['lineNumberTables'] = [[self.code['lineNumberTables'][0][0]]]
        self.previous['originalLineNumberTablesCopiedVerbatim'] = [[{'startPc': 3, 'lineNumber': 9}]]

        self.assertEqual([[0, []], [3, [0]]], bindings.bind_method(self.method, self.previous)['instructionLines'])

    def test_absent_native_code_has_no_invented_positions(self):
        self.method['code'] = None
        self.previous.update(codePresent=False, originalLineNumberTablesCopiedVerbatim=None)

        result = bindings.bind_method(self.method, self.previous)

        self.assertFalse(result['codePresent'])
        self.assertEqual([], result['instructionLines'])
        self.assertIsNone(result['codeIdentity'])

    def test_method_descriptor_flags_ordered_lines_and_layout_mismatches_are_rejected(self):
        for variant in ('descriptor', 'flags', 'table-order', 'gap', 'overlap', 'length', 'boundary'):
            with self.subTest(variant=variant):
                method, previous = copy.deepcopy(self.method), copy.deepcopy(self.previous)
                if variant == 'descriptor':
                    previous['descriptor'] = '(I)V'
                elif variant == 'flags':
                    previous['accessFlags'] = 1
                elif variant == 'table-order':
                    previous['originalLineNumberTablesCopiedVerbatim'][0].reverse()
                elif variant in ('gap', 'overlap'):
                    method['code']['instructions'][1]['bci'] = 4 if variant == 'gap' else 2
                elif variant == 'length':
                    method['code']['identity']['bytes'] = 5
                else:
                    method['code']['lineNumberTables'][0][0]['instructionBoundary'] = False
                with self.assertRaises(ValueError):
                    bindings.bind_method(method, previous)

    def test_column_width_and_physical_origins_are_not_guessed(self):
        with self.assertRaises(ValueError):
            bindings.expand({'instructions': [[0, 'RETURN']]})
        with self.assertRaises(ValueError):
            bindings.origin_key({'entry': 'A.class', 'entryIndex': 0, 'module': 'a', 'jar': 'a.jar'})

    def test_runtime_traversal_indexes_remain_separate_from_cross_observer_matching(self):
        first = {'entry': 'p/A.class', 'entryIndex': 3, 'module': 'a'}
        second = dict(first, entryIndex=40)

        self.assertEqual(reader.match_key(first), reader.match_key(second))
        self.assertNotEqual(bindings.origin_key(first), bindings.origin_key(second))
        first = {'entry': 'p/A.class', 'entryIndex': 3, 'jar': 'a.jar', 'jarSha256': 'a' * 64}
        self.assertNotEqual(reader.match_key(first), reader.match_key(dict(first, entryIndex=40)))

    def test_symbols_keep_dynamic_bootstrap_cycles_as_indices(self):
        metadata = {'constantPoolSlots': 9, 'constantPool': [
            {'index': 1, 'tag': 1, 'width': 1, 'references': [], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': 'p/A'},
            {'index': 2, 'tag': 7, 'width': 1, 'references': [1], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': None},
            {'index': 3, 'tag': 1, 'width': 1, 'references': [], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': 'f'},
            {'index': 4, 'tag': 1, 'width': 1, 'references': [], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': '()V'},
            {'index': 5, 'tag': 12, 'width': 1, 'references': [3, 4], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': None},
            {'index': 6, 'tag': 10, 'width': 1, 'references': [2, 5], 'bootstrapIndex': None,
             'methodHandleKind': None, 'symbolicText': None},
            {'index': 7, 'tag': 15, 'width': 1, 'references': [6], 'bootstrapIndex': None,
             'methodHandleKind': 6, 'symbolicText': None},
            {'index': 8, 'tag': 17, 'width': 1, 'references': [5], 'bootstrapIndex': 0,
             'methodHandleKind': None, 'symbolicText': None}],
            'bootstrapMethods': [{'index': 0, 'methodHandleIndex': 7, 'argumentIndices': [8]}]}

        symbols = bindings.Symbols(metadata)

        self.assertEqual(('p/A', 'f', '()V'), symbols.member(metadata['constantPool'][5]))
        metadata['constantPool'][-1]['bootstrapIndex'] = 1
        with self.assertRaises(ValueError):
            bindings.Symbols(metadata)

    def test_declaration_candidates_preserve_alternates_overloads_and_original_arm(self):
        with tempfile.TemporaryDirectory() as directory:
            index = bindings.DeclarationIndex(Path(directory) / 'index.sqlite')
            try:
                for ordinal, name, heads, parents, methods in [
                    (0, 'Child', ['a' * 40], ['Parent'], [('helper', '()V')]),
                    (1, 'Parent', ['a' * 40], ['Child'], [('f', '()V'), ('f', '(I)V')]),
                    (2, 'Parent', ['a' * 40, 'b' * 40], [], [('f', '()V')]),
                    (3, 'Parent', ['b' * 40], [], [('f', '()V')])]:
                    row = {'inputId': 'input', 'studySourceHeads': heads,
                           'origin': {'module': 'm', 'entry': name + '.class', 'entryIndex': ordinal}}
                    metadata = {'internalName': name, 'superclass': None, 'interfaces': parents, 'fields': [],
                                'methods': [{'methodIndex': i, 'name': n, 'descriptor': d}
                                            for i, (n, d) in enumerate(methods)]}
                    index.add(ordinal, row, metadata)

                result = index.candidates('a' * 40, 'Child', 'method', 'f', '()V')

                self.assertEqual([[1, 0], [2, 0]], result['memberCandidates'])
                self.assertEqual([[2, 0], [3, 0]],
                    index.candidates('b' * 40, 'Parent', 'method', 'f', '()V')['memberCandidates'])
                self.assertEqual(['Child'], result['revisitedHierarchyNames'])
                self.assertEqual([], index.candidates('a' * 40, 'Child', 'method', '<init>', '()V')['memberCandidates'])
                self.assertEqual(['Missing'], index.candidates('a' * 40, 'Missing')['missingClassNames'])
                self.assertFalse(result['jvmResolutionPerformed'])
            finally:
                index.close()

    def test_logical_report_census_is_checked_after_last_record(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.gz'
            raw = b'{"record":"one"}\n'
            path.write_bytes(gzip.compress(raw))
            proof = dict(reader.file_identity(path), uncompressedBytes=len(raw),
                         uncompressedSha256=hashlib.sha256(raw).hexdigest(), records=2)
            with self.assertRaises(ValueError):
                list(reader.checked_records(path, proof))


class ReviewJvmBytecodeSourcesTest(unittest.TestCase):
    def test_complete_authenticated_join_and_failures_keep_full_original_inventory(self):
        for variant in ('complete', 'mismatched-lines', 'unused-debug', 'unknown-context', 'corrupt-unselected'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                out, scratch = root / 'out', root / 'scratch'
                out.mkdir()
                scratch.mkdir()
                head = 'a' * 40
                origin = {'jar': 'lib/a.jar', 'jarSha256': 'b' * 64, 'entry': 'p/A.class', 'entryIndex': 3}
                code = {'identity': {'bytes': 1, 'sha256': 'c' * 64}, 'maxStack': 0, 'maxLocals': 0,
                    'attributes': ['LineNumberTable'], 'lineNumberTables': [[[0, 4, True]]],
                    'instructions': [[0, 'RETURN', 1, None, None, [], None, False]], 'exceptionHandlers': []}
                metadata = {'internalName': 'p/A', 'identity': {'bytes': 8, 'sha256': 'd' * 64},
                    'superclass': None, 'interfaces': [], 'sourceFiles': ['A.kt'], 'constantPoolSlots': 1,
                    'constantPool': [], 'bootstrapMethods': [], 'fields': [], 'methods': [
                        {'methodIndex': 0, 'name': 'privateDefault', 'descriptor': '()V', 'accessFlags': 2,
                         'attributes': ['Code'], 'code': code},
                        {'methodIndex': 1, 'name': 'nativePeer', 'descriptor': '()V', 'accessFlags': 258,
                         'attributes': [], 'code': None}]}
                rows = [{'record': 'class', 'inputId': 'jars', 'inputKind': 'jars', 'studySourceHeads': [head],
                         'origin': origin, 'classSha256': 'd' * 64, 'metadata': metadata,
                         'errorType': None, 'entryBytesAmbiguous': False}]
                previous = {'status': 'reviewed', 'internalName': 'p/A', 'sourceFilesCopiedVerbatim': ['A.kt'],
                    'origin': dict(origin, input='original-jars', rowOrdinal=0, classSha256='d' * 64),
                    'methods': [{'methodIndex': 0, 'name': 'privateDefault', 'descriptor': '()V', 'accessFlags': 2,
                        'codePresent': True, 'originalLineNumberTablesCopiedVerbatim': [[{'startPc': 0, 'lineNumber': 4}]]},
                        {'methodIndex': 1, 'name': 'nativePeer', 'descriptor': '()V', 'accessFlags': 258,
                         'codePresent': False, 'originalLineNumberTablesCopiedVerbatim': None}],
                    'lineCandidates': [{'generatedLine': 4, 'declaredMethodLineEntries': [[0, 0, 0]],
                        'candidates': [{'sourceCandidates': [{'sourceIndex': 0, 'syntaxNodeCandidates': [0],
                            'sourceSelectionProven': False}]}]}],
                    'sourceDebugMaps': [{'status': 'unavailable-unsupported-stratum', 'rawIdentity': 'opaque'}]}
                if variant == 'mismatched-lines':
                    previous['methods'][0]['originalLineNumberTablesCopiedVerbatim'][0][0]['lineNumber'] = 5
                syntax_rows = [previous]
                if variant == 'unused-debug':
                    extra = copy.deepcopy(previous)
                    extra['origin']['entry'] = 'p/Other.class'
                    extra['origin']['rowOrdinal'] = 1
                    syntax_rows.append(extra)
                bytecode_end = {'classes': 1, 'allClassEntriesVisited': True,
                    'allInputBytesAuthenticatedBeforeAndAfter': True, 'allClassMetadataAvailable': True}
                raw = b''.join(reader.json_bytes(row) + b'\n' for row in [
                    {'record': 'schema', 'encoding': 'bytecode-columns-v1', 'columns': bindings.COLUMNS},
                    *rows, {'record': 'inventory-end', **bytecode_end}])
                packed = gzip.compress(raw)
                bytecode_completion = dict(bytecode_end, completed=True, reportFile='bytecode.jsonl.gz',
                    reportBytes=len(packed), reportSha256=hashlib.sha256(packed).hexdigest(),
                    uncompressedReportBytes=len(raw), uncompressedReportSha256=hashlib.sha256(raw).hexdigest(),
                    records=3, completeGzipRoundTripVerified=True)
                files = {'bytecode': {'bytecode.jsonl.gz': packed,
                                      'completion.json': reader.json_bytes(bytecode_completion),
                                      'unselected.txt': b'original unselected evidence\n'}, 'syntax': {}}
                reports = []
                for name, records in [
                    ('syntax.jsonl.gz', [encode_report_record(row) for row in syntax_rows]),
                    ('coverage.jsonl.gz', [{'record': 'schema', 'encoding': 'syntax-node-line-coverage-v1',
                        'columns': ['sourceIndex', 'nodeIndex', 'debugLineCandidatePresent']}, [0, 0, True], [0, 1, False]])]:
                    raw = b''.join(reader.json_bytes(record) + b'\n' for record in records)
                    packed = gzip.compress(raw)
                    files['syntax'][name] = packed
                    reports.append({'file': name, 'size': len(packed), 'sha256': hashlib.sha256(packed).hexdigest(),
                        'uncompressedBytes': len(raw), 'uncompressedSha256': hashlib.sha256(raw).hexdigest(),
                        'records': len(records), 'completeGzipRoundTripVerified': True})
                files['syntax']['completion.json'] = reader.json_bytes({'completed': True, 'syntaxNodes': 2,
                    'syntaxSources': 1, 'classRowsReviewed': len(syntax_rows), 'reports': reports})
                downloads, items = {}, {}
                for name, members in files.items():
                    archive = root / (name + '.tar.gz')
                    with tarfile.open(archive, 'w:gz') as stream:
                        for path, body in members.items():
                            entry = tarfile.TarInfo(path)
                            entry.size = len(body)
                            stream.addfile(entry, io.BytesIO(body))
                    manifest = {'archive': reader.file_identity(archive), 'files': {
                        path: {'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()}
                        for path, body in members.items()}}
                    if variant == 'corrupt-unselected' and name == 'bytecode':
                        manifest['files']['unselected.txt']['sha256'] = 'f' * 64
                    raw = reader.json_bytes(manifest)
                    item = {'id': name, 'manifest': f'https://example.test/{name}.json',
                            'manifestSha256': hashlib.sha256(raw).hexdigest(),
                            'archive': f'https://example.test/{name}.tar.gz', 'completionMember': 'completion.json'}
                    if name == 'bytecode':
                        item['reportMember'] = 'bytecode.jsonl.gz'
                    else:
                        item.update(reportMember='syntax.jsonl.gz', coverageMember='coverage.jsonl.gz',
                            studySourceHead='b' * 40 if variant == 'unknown-context' else head,
                            bytecodeInputs=[{'bytecodeInputId': 'jars', 'syntaxInputId': 'original-jars'}])
                    items[name] = item
                    downloads[item['manifest']] = raw
                    downloads[item['archive']] = archive.read_bytes()
                catalog = {'schema': 1, 'bytecode': items['bytecode'], 'syntaxJoins': [items['syntax']]}
                with patch('review_jvm_source_syntax.download', side_effect=lambda url, path, limit, checksum:
                           path.write_bytes(downloads[url])):
                    if variant != 'complete':
                        with self.assertRaises(ValueError):
                            reader.review(catalog, out, scratch)
                        self.assertFalse((out / 'bytecode-source-bindings.jsonl.gz').exists())
                        if variant != 'corrupt-unselected':
                            self.assertTrue((out / 'bytecode-source-bindings.partial.jsonl.gz').exists())
                        continue
                    result = reader.review(catalog, out, scratch)
                with gzip.open(out / 'bytecode-source-bindings.jsonl.gz', 'rt') as stream:
                    records = [json.loads(line) for line in stream]
                joined = expand_report_record(records[1])
                self.assertTrue(result['completed'])
                self.assertTrue(result['allOriginalContextRowsJoinedExactlyOnce'])
                self.assertEqual(1, result['bytecodeClasses'])
                self.assertEqual([[0, [0]]], joined['methods'][0]['instructionLines'])
                self.assertFalse(joined['methods'][1]['codePresent'])
                self.assertEqual(0, joined['methods'][0]['lineEntries'][0]['syntaxLineCandidateIndex'])
                self.assertTrue(joined['unavailableDebugPayloadsRetainedByAuthenticatedSyntaxReport'])
                self.assertTrue(all(joined[flag] is False for flag in bindings.DENIED))


if __name__ == '__main__':
    unittest.main(verbosity=2)
