import base64
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import jvm_source_syntax as syntax
from jvm_bytecode_source_bindings import COLUMNS, DENIED, DeclarationIndex
from review_jvm_bytecode_sources import json_bytes
from review_jvm_source_lines import expand_report_record
import review_jvm_bytecode_syntax as reader


class ReviewJvmBytecodeSyntaxTest(unittest.TestCase):
    def test_direct_views_keep_original_debug_bytes_and_ordered_line_points(self):
        original = {'sourceDebugExtensionIdentities': [], 'sourceDebugExtensionContentsBase64': [], 'methods': [
            {'code': {'lineNumberTables': [[{'startPc': 7, 'lineNumber': 2, 'instructionBoundary': False},
                                           {'startPc': 0, 'lineNumber': 1, 'instructionBoundary': True}]]}}]}
        row = {'origin': {'module': 'm', 'entry': 'A.class', 'entryIndex': 3},
               'inputId': 'input', 'classSha256': 'a' * 64}

        view = reader.debug_view(row, original)

        self.assertEqual([[{'startPc': 7, 'lineNumber': 2}, {'startPc': 0, 'lineNumber': 1}]],
                         view['metadata']['methods'][0]['code']['lineNumberTables'])
        self.assertFalse(original['methods'][0]['code']['lineNumberTables'][0][0]['instructionBoundary'])
        self.assertEqual('input', view['input'])
        with self.assertRaises(ValueError):
            reader.debug_view(row, {'methods': []})

    def test_partial_cleanup_retains_every_report_when_one_close_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, second = root / 'first.jsonl.gz', root / 'second.jsonl.gz'
            first.write_bytes(b'first original bytes')
            second.write_bytes(b'second original bytes')
            reports = [SimpleNamespace(path=first, close=Mock(side_effect=OSError('closed unsuccessfully'))),
                       SimpleNamespace(path=second, close=Mock())]

            reader.retain_partial_reports(reports, root)

            self.assertEqual(b'first original bytes', (root / 'first.partial.jsonl.gz').read_bytes())
            self.assertEqual(b'second original bytes', (root / 'second.partial.jsonl.gz').read_bytes())
            self.assertFalse(first.exists())
            self.assertFalse(second.exists())
            reports[1].close.assert_called_once()
            errors = json.loads((root / 'report-cleanup-errors.json').read_text())
            self.assertEqual([('first.jsonl.gz', 'close')], [(row['file'], row['operation']) for row in errors])

    def test_declaration_lookup_does_not_scan_unrelated_original_contexts(self):
        with tempfile.TemporaryDirectory() as directory:
            index = DeclarationIndex(Path(directory) / 'index.sqlite')
            try:
                index.db.executemany('INSERT INTO classes VALUES (?,?,?,?,?)',
                    [(i, 'input', str(i), 'Other' + str(i), '[]') for i in range(5000)] +
                    [(5000, 'input', '5000', 'Target', '[]')])
                index.db.executemany('INSERT INTO contexts VALUES (?,?)', [(i, 'a' * 40) for i in range(5001)])
                index.db.commit()
                index.db.set_progress_handler(lambda: 1, 1000)

                result = index.candidates('a' * 40, 'Target')

                self.assertEqual([5000], result['classOrdinals'])
            finally:
                index.db.set_progress_handler(None, 0)
                index.close()

    def test_complete_direct_join_retains_cli_harness_shared_sdk_and_unmatched_nodes(self):
        for variant in ('complete', 'legacy-debug', 'wrong-source-head', 'missing-context', 'corrupt-unselected'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                out, scratch = root / 'out', root / 'scratch'
                out.mkdir()
                scratch.mkdir()
                heads = ['a' * 40, 'b' * 40]
                payload = b'unsupported-original-debug'
                metadata = {'internalName': 'p/RootKt', 'identity': {'bytes': 8, 'sha256': 'c' * 64},
                    'superclass': None, 'interfaces': [], 'attributes': ['SourceFile', 'SourceDebugExtension'],
                    'sourceFiles': ['Root.kt'], 'constantPoolSlots': 1, 'constantPool': [], 'bootstrapMethods': [],
                    'sourceDebugExtensionIdentities': [{'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}],
                    'sourceDebugExtensionContentsBase64': [base64.b64encode(payload).decode()], 'fields': [],
                    'methods': [{'methodIndex': 0, 'name': 'privateHelper', 'descriptor': '()V',
                        'accessFlags': 2, 'attributes': ['Code'], 'code': {
                            'identity': {'bytes': 1, 'sha256': 'd' * 64}, 'attributes': ['LineNumberTable'],
                            'lineNumberTables': [[[0, 1, True]]],
                            'instructions': [[0, 'RETURN', 1, None, None, [], None, False]]}}]}
                rows = []
                for ordinal, (name, source_heads) in enumerate([('control-cli', [heads[0]]),
                        ('candidate-harness', [heads[1]]), ('shared-sdk', heads)]):
                    value = copy.deepcopy(metadata)
                    value['identity']['sha256'] = str(ordinal + 1) * 64
                    origin = {'entry': 'p/RootKt.class', 'entryIndex': 0}
                    if name == 'shared-sdk':
                        origin['module'] = 'java.base'
                        value.update(internalName='java/lang/Object', sourceFiles=[], attributes=[], methods=[],
                                     sourceDebugExtensionIdentities=[], sourceDebugExtensionContentsBase64=[])
                    else:
                        origin.update(jar='lib/a.jar', jarSha256='e' * 64)
                    if variant == 'legacy-debug':
                        value.pop('sourceDebugExtensionIdentities')
                    rows.append({'record': 'class', 'inputId': name,
                        'inputKind': 'runtime-image' if name == 'shared-sdk' else 'jars',
                        'studySourceHeads': source_heads, 'origin': origin, 'classSha256': value['identity']['sha256'],
                        'metadata': value, 'errorType': None, 'entryBytesAmbiguous': False})
                end = {'classes': 3, 'allClassEntriesVisited': True,
                       'allInputBytesAuthenticatedBeforeAndAfter': True, 'allClassMetadataAvailable': True}
                raw = b''.join(json_bytes(row) + b'\n' for row in [
                    {'record': 'schema', 'encoding': 'bytecode-columns-v1', 'columns': COLUMNS},
                    *rows, {'record': 'inventory-end', **end}])
                packed = gzip.compress(raw)
                completion = dict(end, completed=True, reportFile='bytecode.gz', reportBytes=len(packed),
                    reportSha256=hashlib.sha256(packed).hexdigest(), uncompressedReportBytes=len(raw),
                    uncompressedReportSha256=hashlib.sha256(raw).hexdigest(), records=5,
                    completeGzipRoundTripVerified=True)
                files = {'bytecode': {'bytecode.gz': packed, 'completion.json': json_bytes(completion),
                                     'unselected.txt': b'whole original unselected bytes'}}
                for context, head in enumerate(heads):
                    text = 'x\n'
                    checksum = hashlib.sha256(text.encode()).hexdigest()
                    source = {'id': head + ':Root.kt', 'path': 'p/Root.kt', 'sourceFile': 'Root.kt',
                              'sourceText': text, 'sha256': checksum, 'packages': [], 'members': []}
                    records = [
                        {'record': 'schema', 'encoding': syntax.ENCODING,
                         'nodeColumns': syntax.NODE_COLUMNS, 'rangeColumns': syntax.RANGE_COLUMNS},
                        {'record': 'source-start', 'sourceIndex': 0, 'sourceId': source['id'],
                         'path': source['path'], 'sha256': checksum},
                        {'record': 'psi-kind', 'index': 0, 'class': 'File'},
                        {'record': 'syntax-node', 'sourceIndex': 0, 'node': [0, None, 'source-file', 0,
                            'Root.kt', None, 'p', [0, 2, 0, 2, 1, 1, checksum], None, None, False, False]},
                        {'record': 'psi-kind', 'index': 1, 'class': 'Node'},
                        {'record': 'syntax-node', 'sourceIndex': 0, 'node': [1, 0, 'declaration', 1,
                            None, None, None, [2, 2, 2, 2, 2, 2, hashlib.sha256(b'').hexdigest()],
                            None, None, False, False]},
                        {'record': 'source-end', 'sourceIndex': 0, 'syntaxNodes': 2, 'syntaxErrors': 0}]
                    raw = b''.join(json_bytes(row) + b'\n' for row in records)
                    packed = gzip.compress(raw)
                    completion = {'completed': True, 'sources': 1, 'records': len(records), 'syntaxErrors': 0,
                        'allInputSourcesVisited': True, 'allSourcesSyntacticallyValid': True,
                        'reportEncoding': syntax.ENCODING, 'reportFile': 'syntax.gz',
                        'reportBytes': len(packed), 'reportSha256': hashlib.sha256(packed).hexdigest(),
                        'uncompressedReportBytes': len(raw), 'uncompressedReportSha256': hashlib.sha256(raw).hexdigest(),
                        'completeGzipRoundTripVerified': True, **{flag: False for flag in syntax.DENIED}}
                    files['syntax-' + str(context)] = {'syntax.gz': packed,
                        'sources.json': json_bytes({'schema': 1, 'originalKlauseHead':
                            'c' * 40 if variant == 'wrong-source-head' else head, 'sources': [source]}),
                        'completion.json': json_bytes(completion),
                        'compiler.json': b'{"version":"2.4.10","targetSourcesCompiled":false,'
                                         b'"targetExecutableLaunched":false,"sourceTextExecuted":false}'}
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
                    if name == 'bytecode' and variant == 'corrupt-unselected':
                        manifest['files']['unselected.txt']['sha256'] = 'f' * 64
                    raw = json_bytes(manifest)
                    item = {'id': name, 'manifest': f'https://example.test/{name}.json',
                            'manifestSha256': hashlib.sha256(raw).hexdigest(),
                            'archive': f'https://example.test/{name}.tar.gz', 'completionMember': 'completion.json'}
                    if name == 'bytecode':
                        item['reportMember'] = 'bytecode.gz'
                    else:
                        item.update(reportMember='syntax.gz', sourcesMember='sources.json', compilerMember='compiler.json',
                                    studySourceHead=heads[int(name[-1])])
                    items[name] = item
                    downloads[item['manifest']] = raw
                    downloads[item['archive']] = archive.read_bytes()
                catalog = {'schema': 1, 'bytecode': items['bytecode'], 'syntaxInputs': [items['syntax-0']] +
                           ([] if variant == 'missing-context' else [items['syntax-1']])}
                with patch('review_jvm_source_syntax.download', side_effect=lambda url, path, limit, checksum:
                           path.write_bytes(downloads[url])):
                    if variant != 'complete':
                        with self.assertRaises(ValueError):
                            reader.review(catalog, out, scratch)
                        self.assertFalse((out / 'bytecode-source-bindings.jsonl.gz').exists())
                        continue
                    result = reader.review(catalog, out, scratch)
                self.assertTrue(result['completed'])
                self.assertEqual(3, result['bytecodeClasses'])
                self.assertEqual(4, result['sourceContextClassRows'])
                self.assertEqual(2, result['unavailableDebugPayloads'])
                self.assertEqual([2, 2], [context['classContextRows'] for context in result['sourceContexts']])
                self.assertTrue(all(result[flag] is False for flag in DENIED))
                for context in result['sourceContexts']:
                    with gzip.open(out / context['syntaxLineReportFile'], 'rt') as stream:
                        lines = [expand_report_record(json.loads(line)) for line in stream]
                    self.assertEqual(context['studySourceHead'] + ':Root.kt',
                                     context['sourceInputs']['sources'][0]['id'])
                    self.assertEqual([0], lines[0]['lineCandidates'][0]['candidates'][0]
                                     ['sourceCandidates'][0]['syntaxNodeCandidates'])
                    with gzip.open(out / context['syntaxNodeCoverageFile'], 'rt') as stream:
                        coverage = [json.loads(line) for line in stream]
                    self.assertEqual([[0, 0, True], [0, 1, False]], coverage[1:])


if __name__ == '__main__':
    unittest.main(verbosity=2)
