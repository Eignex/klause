import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

import review_jvm_declaration_bindings as bindings


class ReviewJvmDeclarationBindingsTest(unittest.TestCase):
    def test_whole_source_file_retains_private_and_generated_members(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'],
            'members': [{'id': 'entry', 'name': 'work'}]}]}
        rows = [
            {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': {'internalName': 'p/ExampleKt',
                'sourceFiles': ['Example.kt'], 'methods': [
                    {'name': 'work', 'descriptor': '()V'},
                    {'name': 'privateColdPath', 'descriptor': '(I)V', 'accessFlags': 2}]}},
            {'entry': 'p/ExampleKt$work$1.class', 'entryIndex': 1, 'metadata': {
                'internalName': 'p/ExampleKt$work$1', 'sourceFiles': ['Example.kt'],
                'methods': [{'name': 'invoke', 'descriptor': '()Ljava/lang/Object;'}]}},
        ]
        review = bindings.DeclarationBindings(specification)

        for index, row in enumerate(rows):
            review.add('jar', index, row, {'size': 1, 'sha256': 'b' * 64})
        result = review.finish()['sources'][0]

        self.assertEqual(rows, [item['originalRowCopiedVerbatim'] for item in result['wholeSourceFileClasses']])
        self.assertIn('p.ExampleKt.privateColdPath(I)V', result['wholeSourceFileMemberSelectors'])
        self.assertIn('p.ExampleKt$work$1.invoke()Ljava/lang/Object;', result['wholeSourceFileMemberSelectors'])

    def test_same_named_source_overloads_keep_every_descriptor_candidate(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': [
                {'id': 'integer', 'name': 'work', 'header': 'fun work(value: Int)'},
                {'id': 'text', 'name': 'work', 'header': 'fun work(value: String)'}]}]}
        row = {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': {
            'internalName': 'p/ExampleKt', 'sourceFiles': ['Example.kt'], 'methods': [
                {'name': 'work', 'descriptor': '(I)V'},
                {'name': 'work', 'descriptor': '(Ljava/lang/String;)V'}]}}
        review = bindings.DeclarationBindings(specification)

        review.add('jar', 0, row, {})
        queries = review.finish()['sources'][0]['sourceMemberCandidates']

        for query in queries:
            with self.subTest(member=query['requestedSourceMemberCopiedVerbatim']['id']):
                self.assertEqual(['p.ExampleKt.work(I)V', 'p.ExampleKt.work(Ljava/lang/String;)V'],
                                 [item['selector'] for item in query['candidates']])
                self.assertFalse(query['sourceOverloadAttributionProven'])

    def test_same_basename_in_another_package_stays_outside_requested_source(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': []}]}
        row = {'entry': 'peer/ExampleKt.class', 'entryIndex': 0, 'metadata': {
            'internalName': 'peer/ExampleKt', 'sourceFiles': ['Example.kt'], 'methods': []}}
        review = bindings.DeclarationBindings(specification)

        review.add('jar', 0, row, {'size': 7, 'sha256': 'b' * 64})
        result = review.finish()

        self.assertTrue(result['sources'][0]['sourceFileCandidateUnavailable'])
        self.assertEqual('peer/ExampleKt', result['outsideRequestedSourceFiles'][0]['internalName'])

    def test_alternate_class_entries_are_retained_without_version_selection(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': []}]}
        review = bindings.DeclarationBindings(specification)
        entries = ['p/ExampleKt.class', 'META-INF/versions/21/p/ExampleKt.class']

        for index, entry in enumerate(entries):
            review.add('jar', index, {'entry': entry, 'entryIndex': index, 'metadata': {
                'internalName': 'p/ExampleKt', 'sourceFiles': ['Example.kt'], 'methods': []}}, {})
        source = review.finish()['sources'][0]

        self.assertEqual(entries, [item['entry'] for item in source['alternativeClassDeclarations']['p/ExampleKt']])
        self.assertFalse(source['actualSourceAttributionProven'])

    def test_mangled_and_accessor_names_remain_distinct_candidates(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'],
            'members': [{'id': 'entry', 'name': 'work'}]}]}
        review = bindings.DeclarationBindings(specification)
        row = {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': {
            'internalName': 'p/ExampleKt', 'sourceFiles': ['Example.kt'], 'methods': [
                {'name': 'work-encoded', 'descriptor': '()V'},
                {'name': 'work$default', 'descriptor': '()V'},
                {'name': 'access$work', 'descriptor': '()V'},
                {'name': 'access$worker', 'descriptor': '()V'}]}}

        review.add('jar', 0, row, {})
        candidates = review.finish()['sources'][0]['sourceMemberCandidates'][0]['candidates']

        self.assertEqual(['mangled-or-generated-name', 'mangled-or-generated-name', 'accessor-name'],
                         [item['nameRelationship'] for item in candidates])

    def test_missing_source_member_is_explicitly_unavailable(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'],
            'members': [{'id': 'entry', 'name': 'absent'}]}]}

        result = bindings.DeclarationBindings(specification).finish()
        query = result['sources'][0]['sourceMemberCandidates'][0]

        self.assertTrue(query['unavailable'])
        self.assertEqual([], query['candidates'])
        self.assertFalse(result['fullSourcePhaseCoverageProven'])

    def test_class_parse_errors_are_retained_and_prevent_completion(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': []}]}
        row = {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': None, 'error': 'Unsupported class'}
        review = bindings.DeclarationBindings(specification)

        review.add('jar', 0, row, {})
        result = review.finish()

        self.assertFalse(result['completed'])
        self.assertEqual(row, result['inputClassErrors'][0]['originalRow'])

    def test_source_debug_and_line_attributes_remain_opaque(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': []}]}
        row = {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': {
            'internalName': 'p/ExampleKt', 'sourceFiles': ['Example.kt'],
            'sourceDebugExtensionIdentities': [{'bytes': 23, 'sha256': 'b' * 64}],
            'methods': [{'name': 'work', 'descriptor': '()V', 'code': {
                'lineNumberTables': [[{'startPc': 0, 'lineNumber': 10000}]]}}]}}
        review = bindings.DeclarationBindings(specification)

        review.add('jar', 0, row, {})
        result = review.finish()

        self.assertEqual(row, result['sources'][0]['wholeSourceFileClasses'][0]['originalRowCopiedVerbatim'])
        self.assertFalse(result['sourcePhaseToJvmDescriptorMappingProven'])

    def test_multiple_source_file_attributes_remain_ambiguous(self):
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Example.kt',
            'sha256': 'a' * 64, 'sourceFile': 'Example.kt', 'packages': ['p'], 'members': []}]}
        row = {'entry': 'p/ExampleKt.class', 'entryIndex': 0, 'metadata': {
            'internalName': 'p/ExampleKt', 'sourceFiles': ['Example.kt', 'Other.kt'], 'methods': []}}
        review = bindings.DeclarationBindings(specification)

        review.add('jar', 0, row, {})
        result = review.finish()['sources'][0]

        self.assertTrue(result['wholeSourceFileClasses'][0]['sourceFileAttributeAmbiguous'])
        self.assertEqual(row, result['wholeSourceFileClasses'][0]['originalRowCopiedVerbatim'])

    def test_changed_unselected_archive_member_rejects_the_complete_input(self):
        for variant in ('changed', 'missing', 'extra', 'duplicate'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / 'input.tar.gz'
                files = {'metadata.jsonl': b'not interpreted during authentication', 'unselected.txt': b'original'}
                with tarfile.open(archive, 'w:gz') as stream:
                    for name, raw in files.items():
                        if variant == 'missing' and name == 'unselected.txt':
                            continue
                        body = b'changed!' if variant == 'changed' and name == 'unselected.txt' else raw
                        info = tarfile.TarInfo(name)
                        info.size = len(body)
                        stream.addfile(info, io.BytesIO(body))
                    if variant in ('extra', 'duplicate'):
                        info = tarfile.TarInfo('extra.txt' if variant == 'extra' else 'metadata.jsonl')
                        info.size = 1
                        stream.addfile(info, io.BytesIO(b'x'))
                manifest = {'archive': bindings.file_identity(archive), 'files': {
                    name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
                scratch = root / 'scratch'
                scratch.mkdir()

                with self.assertRaises(ValueError):
                    bindings.authenticate_archive(manifest, archive, ['metadata.jsonl'], scratch)

    def test_valid_archive_selects_only_authenticated_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'input.tar.gz'
            files = {'metadata.jsonl': b'preserved bytes\n', 'unselected.txt': b'other bytes'}
            with tarfile.open(archive, 'w:gz') as stream:
                for name, raw in files.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(raw)
                    stream.addfile(info, io.BytesIO(raw))
            manifest = {'archive': bindings.file_identity(archive), 'files': {
                name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
            scratch = root / 'scratch'
            scratch.mkdir()

            selected = bindings.authenticate_archive(manifest, archive, ['metadata.jsonl'], scratch)

            self.assertEqual(['metadata.jsonl'], list(selected))
            self.assertEqual(files['metadata.jsonl'], selected['metadata.jsonl'].read_bytes())
            self.assertFalse((scratch / 'unselected.txt').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
