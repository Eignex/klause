import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import jvm_source_syntax as syntax
import review_jvm_source_syntax as reader


class ReviewJvmSourceSyntaxTest(unittest.TestCase):
    def test_class_candidate_limit_rolls_back_unwritten_source_and_node_coverage(self):
        text = 'one\n'
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'p/Root.kt',
            'sourceFile': 'Root.kt', 'sourceText': text, 'sha256': hashlib.sha256(text.encode()).hexdigest(),
            'packages': ['p'], 'members': []}]}
        index = SimpleNamespace(query=Mock(return_value=(0, 1)), nodes=[SimpleNamespace(package='p')], mark=Mock())
        bindings = reader.SyntaxLineBindings(specification, index)
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
            'sourceFiles': ['Root.kt'], 'sourceDebugExtensionIdentities': [],
            'sourceDebugExtensionContentsBase64': [], 'methods': [{'name': 'privateHelper',
                'descriptor': '()V', 'attributes': ['Code'], 'code': {'attributes': ['LineNumberTable'],
                    'lineNumberTables': [[{'startPc': 0, 'lineNumber': 1}]]}}]}}

        with patch.object(reader, 'MAX_CLASS_NODE_CANDIDATES', 2), self.assertRaises(ValueError):
            bindings.bind(row)
        bindings.commit()

        index.mark.assert_not_called()
        self.assertTrue(bindings.finish()['sources'][0]['noInRangeLineCandidate'])

    def test_complete_join_keeps_alternate_private_outside_and_other_package_candidates(self):
        text = 'fun x() = 1\n'
        checksum = hashlib.sha256(text.encode()).hexdigest()
        sources = [{'id': name, 'path': name + '/Root.kt', 'sourceFile': 'Root.kt',
                    'sourceText': text, 'sha256': checksum, 'packages': [package] if name == 'source' else [], 'members': []}
                   for name, package in [('source', 'p'), ('peer', 'peer')]]
        specification = {'schema': 1, 'sources': sources}
        records = [{'record': 'schema', 'encoding': syntax.ENCODING,
                    'nodeColumns': syntax.NODE_COLUMNS, 'rangeColumns': syntax.RANGE_COLUMNS}]
        for index, source in enumerate(sources):
            records.append({'record': 'source-start', 'sourceIndex': index, 'sourceId': source['id'],
                            'path': source['path'], 'sha256': checksum})
            if index == 0:
                records.append({'record': 'psi-kind', 'index': 0, 'class': 'File'})
            records.append({'record': 'syntax-node', 'sourceIndex': index,
                'node': [0, None, 'source-file', 0, 'Root.kt', None, 'p' if index == 0 else 'peer',
                         [0, len(text), 0, len(text), 1, 1, checksum], None, None, False, False]})
            if index == 0:
                records.append({'record': 'psi-kind', 'index': 1, 'class': 'Function'})
            records.append({'record': 'syntax-node', 'sourceIndex': index,
                'node': [1, 0, 'function', 1, 'x', None, None,
                         [0, len(text) - 1, 0, len(text) - 1, 1, 1,
                          hashlib.sha256(text[:-1].encode()).hexdigest()], None, None, False, False]})
            records.append({'record': 'source-end', 'sourceIndex': index, 'syntaxNodes': 2, 'syntaxErrors': 0})
        raw = b''.join(json.dumps(row).encode() + b'\n' for row in records)
        packed = gzip.compress(raw)
        syntax_completion = {'completed': True, 'sources': 2, 'records': len(records), 'syntaxErrors': 0,
            'allInputSourcesVisited': True, 'allSourcesSyntacticallyValid': True,
            'reportEncoding': syntax.ENCODING, 'reportFile': 'syntax.jsonl.gz',
            'reportBytes': len(packed), 'reportSha256': hashlib.sha256(packed).hexdigest(),
            'uncompressedReportBytes': len(raw), 'uncompressedReportSha256': hashlib.sha256(raw).hexdigest(),
            'completeGzipRoundTripVerified': True, **{flag: False for flag in syntax.DENIED}}
        tables = [[{'startPc': 7, 'lineNumber': 1}, {'startPc': 0, 'lineNumber': 1},
                   {'startPc': 7, 'lineNumber': 2}], [{'startPc': 7, 'lineNumber': 1}]]
        metadata = {'internalName': 'p/RootKt', 'identity': {'bytes': 1, 'sha256': 'a' * 64},
            'attributes': ['SourceFile'], 'sourceFiles': ['Root.kt'],
            'sourceDebugExtensionIdentities': [], 'sourceDebugExtensionContentsBase64': [], 'methods': [
                {'name': 'privateGenerated', 'descriptor': '(I)I', 'accessFlags': 2, 'attributes': ['Code'],
                 'code': {'attributes': ['LineNumberTable', 'LineNumberTable'], 'lineNumberTables': tables}},
                {'name': 'privateNative', 'descriptor': '()V', 'accessFlags': 258, 'attributes': [], 'code': None},
                {'name': 'withoutDebug', 'descriptor': '()V', 'attributes': ['Code'],
                 'code': {'attributes': [], 'lineNumberTables': []}}]}
        rows = [{'jar': 'lib/example.jar', 'jarSha256': 'b' * 64, 'entry': name, 'entryIndex': index,
                 'classSha256': 'a' * 64, 'error': None, 'metadata': copy.deepcopy(metadata)}
                for index, name in enumerate(['p/RootKt.class', 'META-INF/versions/25/p/RootKt.class', 'outside/Peer.class'])]
        rows[-1]['metadata'].update(internalName='outside/Peer', sourceFiles=['Peer.java'])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out, scratch = root / 'out', root / 'scratch'
            out.mkdir()
            scratch.mkdir()
            downloads, items = {}, {}
            files_by_role = {
                'classes': {'rows.jsonl': b''.join(json.dumps(row).encode() + b'\n' for row in rows),
                            'completion.json': b'{"completed":true}',
                            'inventory.json': b'{"classes":3,"allDeclaredClassMetadataComplete":true}'},
                'syntax': {'syntax.jsonl.gz': packed, 'sources.json': json.dumps(specification).encode(),
                           'completion.json': json.dumps(syntax_completion).encode(),
                           'compiler.json': b'{"version":"2.4.10","targetSourcesCompiled":false,'
                                            b'"targetExecutableLaunched":false,"sourceTextExecuted":false}'}}
            for name in ('classes', 'syntax', 'previous'):
                if name == 'previous':
                    prior = gzip.compress(b'original opaque line report\n')
                    files_by_role[name] = {'old.jsonl.gz': prior,
                        'sources.json': json.dumps({'schema': 1, 'sources': sources[:1]}).encode(),
                        'catalog.json': json.dumps({'schema': 1, 'inputs': [items['classes']]}).encode(),
                        'completion.json': json.dumps({'completed': True, 'classRowsReviewed': 3,
                            'unavailableDebugPayloads': 0, 'report': {'file': 'old.jsonl.gz', 'size': len(prior),
                                'sha256': hashlib.sha256(prior).hexdigest()}}).encode()}
                files = files_by_role[name]
                archive = root / (name + '.tar.gz')
                with tarfile.open(archive, 'w:gz') as stream:
                    for path, body in files.items():
                        entry = tarfile.TarInfo(path)
                        entry.size = len(body)
                        stream.addfile(entry, io.BytesIO(body))
                manifest = {'archive': reader.file_identity(archive), 'files': {
                    path: {'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()} for path, body in files.items()}}
                manifest_raw = json.dumps(manifest).encode()
                item = {'id': name, 'manifest': f'https://example.test/{name}.json',
                        'manifestSha256': hashlib.sha256(manifest_raw).hexdigest(),
                        'archive': f'https://example.test/{name}.tar.gz'}
                if name == 'classes':
                    item.update(rowsMember='rows.jsonl', completionMember='completion.json', inventoryMember='inventory.json')
                elif name == 'syntax':
                    item.update(reportMember='syntax.jsonl.gz', sourcesMember='sources.json',
                                completionMember='completion.json', compilerMember='compiler.json')
                else:
                    item.update(reportMember='old.jsonl.gz', sourcesMember='sources.json',
                                completionMember='completion.json', catalogMember='catalog.json')
                items[name] = item
                downloads[item['manifest']] = manifest_raw
                downloads[item['archive']] = archive.read_bytes()
            catalog = {'schema': 1, 'metadata': {'schema': 1, 'inputs': [items['classes']]},
                       'syntax': items['syntax'], 'previousLines': items['previous']}

            with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected:
                              target.write_bytes(downloads[url])):
                result = reader.review(catalog, out, scratch)

            self.assertTrue(result['completed'])
            self.assertEqual(3, result['classRowsReviewed'])
            self.assertEqual(4, result['syntaxNodes'])
            self.assertTrue(result['allAuthenticatedInputsByteIdenticalAfterReview'])
            self.assertTrue(result['earlierSourcePrefixUnchanged'])
            self.assertTrue(result['earlierSourceLineReportRetainedOpaque'])
            with gzip.open(out / 'syntax-line-bindings.jsonl.gz', 'rt') as stream:
                joined = [reader.expand_report_record(json.loads(line)) for line in stream]
            self.assertEqual([row['entry'] for row in rows], [row['origin']['entry'] for row in joined])
            self.assertEqual(tables, joined[0]['methods'][0]['originalLineNumberTablesCopiedVerbatim'])
            self.assertEqual(['privateGenerated', 'privateNative', 'withoutDebug'],
                             [row['name'] for row in joined[0]['methods']])
            line = joined[0]['lineCandidates'][0]
            self.assertEqual([[0, 0, 0], [0, 0, 1], [0, 1, 0]], line['declaredMethodLineEntries'])
            candidates = line['candidates'][0]['sourceCandidates']
            self.assertEqual(['source', 'peer'], [item['sourceId'] for item in candidates])
            self.assertEqual([[0, 1], [0, 1]], [item['syntaxNodeCandidates'] for item in candidates])
            self.assertEqual([True, False], [item['syntaxParsedPackageAgrees'] for item in candidates])
            self.assertTrue(all(item['sourceSelectionProven'] is False for item in candidates))
            self.assertEqual([], joined[-1]['lineCandidates'][0]['candidates'][0]['sourceCandidates'])
            with gzip.open(out / 'syntax-node-line-coverage.jsonl.gz', 'rt') as stream:
                coverage = [json.loads(line) for line in stream]
            self.assertEqual([[0, 0, True], [0, 1, True], [1, 0, True], [1, 1, True]], coverage[1:])
            self.assertFalse(result['sourcePhaseToJvmDescriptorMappingProven'])
            self.assertFalse(result['acceptedSavingsCredit'])

    def test_every_archive_member_is_authenticated_before_syntax_or_class_interpretation(self):
        for variant in ('changed', 'missing', 'extra', 'duplicate', 'download'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                out, scratch = root / 'out', root / 'scratch'
                out.mkdir()
                scratch.mkdir()
                downloads, items = {}, {}
                for name, files, roles in [
                    ('syntax', {'report': b'not interpreted', 'sources': b'bad json', 'completion': b'bad json',
                                'compiler': b'bad json'},
                     {'reportMember': 'report', 'sourcesMember': 'sources', 'completionMember': 'completion',
                      'compilerMember': 'compiler'}),
                    ('previous', {'sources': b'bad json', 'completion': b'bad json', 'catalog': b'bad json'},
                     {'sourcesMember': 'sources', 'completionMember': 'completion', 'catalogMember': 'catalog'}),
                    ('classes', {'rows': b'not interpreted', 'completion': b'bad json', 'inventory': b'bad json',
                                 'unselected': b'original'},
                     {'rowsMember': 'rows', 'completionMember': 'completion', 'inventoryMember': 'inventory'})]:
                    archive = root / (name + '.tar.gz')
                    with tarfile.open(archive, 'w:gz') as stream:
                        for path, body in files.items():
                            if name == 'classes' and path == 'unselected' and variant == 'missing':
                                continue
                            actual = b'changed!' if name == 'classes' and path == 'unselected' and variant == 'changed' else body
                            entry = tarfile.TarInfo(path)
                            entry.size = len(actual)
                            stream.addfile(entry, io.BytesIO(actual))
                        if name == 'classes' and variant in ('extra', 'duplicate'):
                            entry = tarfile.TarInfo('extra' if variant == 'extra' else 'rows')
                            entry.size = 0
                            stream.addfile(entry, io.BytesIO())
                    manifest = {'archive': reader.file_identity(archive), 'files': {
                        path: {'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()} for path, body in files.items()}}
                    manifest_raw = json.dumps(manifest).encode()
                    item = {'id': name, 'manifest': f'https://example.test/{name}.json',
                            'manifestSha256': hashlib.sha256(manifest_raw).hexdigest(),
                            'archive': f'https://example.test/{name}.tar.gz', **roles}
                    items[name] = item
                    downloads[item['manifest']] = manifest_raw
                    downloads[item['archive']] = b'wrong' if name == 'classes' and variant == 'download' else archive.read_bytes()
                catalog = {'schema': 1, 'metadata': {'schema': 1, 'inputs': [items['classes']]},
                           'syntax': items['syntax'], 'previousLines': items['previous']}

                with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected:
                                  target.write_bytes(downloads[url])), \
                        patch.object(reader, 'SourceSyntaxIndex') as interpret, self.assertRaises(ValueError) as failure:
                    reader.review(catalog, out, scratch)

                self.assertIn('archive', str(failure.exception).lower())
                interpret.assert_not_called()
                self.assertFalse((out / 'syntax-line-bindings.jsonl.gz').exists())
                if variant == 'download':
                    private = list((out / 'private-unverified-inputs').glob('*.tar.gz'))
                    self.assertEqual(1, len(private))
                    self.assertEqual(b'wrong', private[0].read_bytes())

    def test_changed_authenticated_inputs_cannot_admit_a_complete_join(self):
        for role in ('manifest', 'archive', 'selected'):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                out, scratch = root / 'out', root / 'scratch'
                out.mkdir()
                scratch.mkdir()
                archive = root / 'original.tar.gz'
                body = b'complete original member'
                with tarfile.open(archive, 'w:gz') as stream:
                    entry = tarfile.TarInfo('member')
                    entry.size = len(body)
                    stream.addfile(entry, io.BytesIO(body))
                manifest = {'archive': reader.file_identity(archive), 'files': {'member': {
                    'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()}}}
                raw = json.dumps(manifest).encode()
                item = {'id': 'input', 'manifest': 'https://example.test/manifest.json',
                        'manifestSha256': hashlib.sha256(raw).hexdigest(),
                        'archive': 'https://example.test/archive.tar.gz', 'member': 'member'}
                downloads = {item['manifest']: raw, item['archive']: archive.read_bytes()}
                inputs = reader.Inputs(out, scratch)
                with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected:
                                  target.write_bytes(downloads[url])):
                    _, selected = inputs.authenticate(item, ('member',))
                inputs.verify_unchanged()
                target = {'manifest': out / 'input-000-manifest.json',
                          'archive': scratch / 'input-000.tar.gz', 'selected': selected['member']}[role]

                target.write_bytes(target.read_bytes() + b'changed')

                with self.assertRaisesRegex(ValueError, 'authenticated input changed'):
                    inputs.verify_unchanged()

    def test_failed_report_writes_cannot_admit_their_complete_gzip_prefix(self):
        for variant in ('logical', 'compressed'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'report.jsonl.gz'
                budget = reader.ReportBudget()
                report = reader.Report(path, budget)
                bound = 'MAX_LOGICAL_REPORT_BYTES' if variant == 'logical' else 'MAX_COMPRESSED_REPORT_BYTES'
                with patch.object(reader, bound, 0), self.assertRaises(ValueError):
                    if variant == 'logical':
                        report.write({'complete': False})
                    else:
                        report.close()
                with self.assertRaises(ValueError):
                    report.verify()
                report.close()

    def test_gzip_header_bound_failure_retains_only_a_partial_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.jsonl.gz'

            with patch.object(reader, 'MAX_COMPRESSED_REPORT_BYTES', 1), self.assertRaises(ValueError):
                reader.Report(path, reader.ReportBudget())

            self.assertFalse(path.exists())
            self.assertTrue((path.parent / 'report.partial.jsonl.gz').is_file())


if __name__ == '__main__':
    unittest.main(verbosity=2)
