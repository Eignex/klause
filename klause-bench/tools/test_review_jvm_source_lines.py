import base64
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import review_jvm_source_lines as reader


class ReviewJvmSourceLinesTest(unittest.TestCase):
    def test_complete_archives_keep_outside_classes_and_alternate_entries(self):
        text = 'one\n'
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Root.kt',
            'sourceText': text, 'sha256': hashlib.sha256(text.encode()).hexdigest(),
            'sourceFile': 'Root.kt', 'packages': ['p'], 'members': []}]}
        rows = [{'jar': 'lib/example.jar', 'jarSha256': 'b' * 64, 'entry': entry, 'entryIndex': index,
                 'classSha256': 'a' * 64, 'error': None, 'metadata': {
                    'internalName': 'p/RootKt' if index < 2 else 'outside/Peer',
                    'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
                    'sourceFiles': ['Root.kt' if index < 2 else 'Peer.java'],
                    'sourceDebugExtensionIdentities': [], 'sourceDebugExtensionContentsBase64': [],
                    'methods': [{'name': 'privateGenerated', 'descriptor': '()V', 'attributes': [], 'code': None}]}}
                for index, entry in enumerate(['p/RootKt.class', 'META-INF/versions/25/p/RootKt.class', 'outside/Peer.class'])]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out, scratch = root / 'out', root / 'scratch'
            out.mkdir()
            scratch.mkdir()
            catalog, downloads = {'schema': 1, 'inputs': []}, {}
            for index, subset in enumerate([rows[:2], rows[2:]]):
                files = {'rows.jsonl': b''.join(json.dumps(row).encode() + b'\n' for row in subset),
                         'completion.json': b'{"completed":true}',
                         'inventory.json': json.dumps({'classes': len(subset), 'allDeclaredClassMetadataComplete': True}).encode(),
                         'unselected.txt': b'complete'}
                archive = root / f'{index}.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name, raw in files.items():
                        member = tarfile.TarInfo(name)
                        member.size = len(raw)
                        stream.addfile(member, io.BytesIO(raw))
                manifest = {'archive': reader.file_identity(archive), 'files': {
                    name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
                manifest_bytes = json.dumps(manifest).encode()
                downloads[f'https://example.test/{index}.json'] = manifest_bytes
                downloads[f'https://example.test/{index}.tar.gz'] = archive.read_bytes()
                catalog['inputs'].append({'id': str(index), 'manifest': f'https://example.test/{index}.json',
                    'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(), 'archive': f'https://example.test/{index}.tar.gz',
                    'rowsMember': 'rows.jsonl', 'completionMember': 'completion.json', 'inventoryMember': 'inventory.json'})

            with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected: target.write_bytes(downloads[url])):
                result = reader.review(catalog, specification, out, scratch)

            with gzip.open(out / result['report']['file'], 'rb') as stream:
                raw_report = stream.read()
            records = [json.loads(line) for line in raw_report.splitlines()]
            self.assertTrue(result['completed'])
            self.assertEqual(3, result['classRowsReviewed'])
            self.assertEqual([row['entry'] for row in rows], [row['origin']['entry'] for row in records])
            self.assertEqual(['privateGenerated'] * 3, [row['methods'][0]['name'] for row in records])
            self.assertEqual(hashlib.sha256(raw_report).hexdigest(), result['uncompressedReportSha256'])
            self.assertFalse(result['sourcePhaseToJvmDescriptorMappingProven'])
            self.assertTrue(all(proof['allArchiveMemberBytesVerifiedBeforeAnyClassRow'] for proof in result['inputArchiveProofs']))

    def test_later_archive_failure_prevents_interpretation_of_every_class_row(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Root.kt',
            'sourceText': 'one\n', 'sha256': hashlib.sha256(b'one\n').hexdigest(),
            'sourceFile': 'Root.kt', 'packages': ['p'], 'members': []}]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out, scratch = root / 'out', root / 'scratch'
            out.mkdir()
            scratch.mkdir()
            catalog, downloads = {'schema': 1, 'inputs': []}, {}
            for index in range(2):
                files = {'rows.jsonl': b'{}\n', 'completion.json': b'{"completed":true}',
                         'inventory.json': b'{"classes":1,"allDeclaredClassMetadataComplete":true}',
                         'unselected.txt': b'changed'}
                archive = root / f'{index}.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name, raw in files.items():
                        member = tarfile.TarInfo(name)
                        member.size = len(raw)
                        stream.addfile(member, io.BytesIO(raw))
                manifest = {'archive': reader.file_identity(archive), 'files': {
                    name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
                if index == 1:
                    manifest['files']['unselected.txt']['sha256'] = hashlib.sha256(b'original').hexdigest()
                manifest_bytes = json.dumps(manifest).encode()
                downloads[f'https://example.test/{index}.json'] = manifest_bytes
                downloads[f'https://example.test/{index}.tar.gz'] = archive.read_bytes()
                catalog['inputs'].append({'id': str(index), 'manifest': f'https://example.test/{index}.json',
                    'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(), 'archive': f'https://example.test/{index}.tar.gz',
                    'rowsMember': 'rows.jsonl', 'completionMember': 'completion.json', 'inventoryMember': 'inventory.json'})

            with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected: target.write_bytes(downloads[url])), \
                    patch.object(reader.SourceLineBindings, 'bind') as interpret, self.assertRaises(ValueError):
                reader.review(catalog, specification, out, scratch)

            interpret.assert_not_called()
            self.assertFalse((out / 'source-line-bindings.jsonl.gz').exists())

    def test_negative_inputs_and_report_bounds_cannot_produce_a_successful_prefix(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Root.kt',
            'sourceText': 'one\n', 'sha256': hashlib.sha256(b'one\n').hexdigest(),
            'sourceFile': 'Root.kt', 'packages': ['p'], 'members': []}]}
        for variant in ['class-error', 'count', 'completion', 'duplicate-origin', 'missing-code', 'debug-bound', 'report-bound']:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                out, scratch = root / 'out', root / 'scratch'
                out.mkdir()
                scratch.mkdir()
                row = {'jar': 'lib/example.jar', 'jarSha256': 'b' * 64, 'entry': 'p/RootKt.class', 'entryIndex': 0,
                    'classSha256': 'a' * 64, 'error': 'original error' if variant == 'class-error' else None,
                    'metadata': {'internalName': 'p/RootKt', 'identity': {'bytes': 1, 'sha256': 'a' * 64},
                        'attributes': ['SourceFile'], 'sourceFiles': ['Root.kt'],
                        'sourceDebugExtensionIdentities': [], 'sourceDebugExtensionContentsBase64': [],
                        'methods': [{'name': 'work', 'descriptor': '()V', 'attributes': [], 'code': None}]}}
                if variant == 'missing-code':
                    del row['metadata']['methods'][0]['code']
                if variant == 'debug-bound':
                    raw_debug = b'SMAP\nRoot.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:1\n*E\n'
                    row['metadata']['attributes'].append('SourceDebugExtension')
                    row['metadata']['sourceDebugExtensionIdentities'] = [
                        {'bytes': len(raw_debug), 'sha256': hashlib.sha256(raw_debug).hexdigest()}]
                    row['metadata']['sourceDebugExtensionContentsBase64'] = [base64.b64encode(raw_debug).decode()]
                rows = [row, row] if variant == 'duplicate-origin' else [row]
                files = {'rows.jsonl': b''.join(json.dumps(item).encode() + b'\n' for item in rows),
                         'completion.json': json.dumps({'completed': variant != 'completion'}).encode(),
                         'inventory.json': json.dumps({'classes': 99 if variant == 'count' else len(rows),
                                                       'allDeclaredClassMetadataComplete': True}).encode()}
                archive = root / 'input.tar.gz'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name, raw in files.items():
                        member = tarfile.TarInfo(name)
                        member.size = len(raw)
                        stream.addfile(member, io.BytesIO(raw))
                manifest = {'archive': reader.file_identity(archive), 'files': {
                    name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
                manifest_bytes = json.dumps(manifest).encode()
                downloads = {'https://example.test/input.json': manifest_bytes,
                             'https://example.test/input.tar.gz': archive.read_bytes()}
                catalog = {'schema': 1, 'inputs': [{'id': 'input', 'manifest': 'https://example.test/input.json',
                    'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(), 'archive': 'https://example.test/input.tar.gz',
                    'rowsMember': 'rows.jsonl', 'completionMember': 'completion.json', 'inventoryMember': 'inventory.json'}]}
                limit = 1 if variant == 'report-bound' else reader.MAX_REPORT_BYTES

                with patch.object(reader, 'download', side_effect=lambda url, target, maximum, expected: target.write_bytes(downloads[url])), \
                        patch.object(reader, 'MAX_REPORT_BYTES', limit), \
                        patch('jvm_source_debug.MAX_ITEMS', 0 if variant == 'debug-bound' else 100_000):
                    if variant == 'report-bound':
                        with self.assertRaises(ValueError):
                            reader.review(catalog, specification, out, scratch)
                        self.assertFalse((out / 'source-line-bindings.jsonl.gz').exists())
                        self.assertTrue((out / 'source-line-bindings.partial.jsonl.gz').exists())
                    else:
                        result = reader.review(catalog, specification, out, scratch)

                        self.assertFalse(result['completed'])
                        self.assertEqual(len(rows), result['classRowsReviewed'])
                        if variant == 'debug-bound':
                            self.assertTrue(result['inputArchiveProofs'][0]['inputMetadataIntegrityVerified'])
                            self.assertFalse(result['inputArchiveProofs'][0]['sourceDebugReviewWithinBounds'])
                        with gzip.open(out / result['report']['file'], 'rb') as stream:
                            self.assertEqual(len(rows), len(stream.readlines()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
