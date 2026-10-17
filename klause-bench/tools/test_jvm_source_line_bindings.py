import base64
import copy
import hashlib
import unittest
from unittest.mock import patch

import jvm_source_line_bindings as lines


class JvmSourceLineBindingsTest(unittest.TestCase):
    def test_original_line_tables_native_members_and_duplicate_positions_are_retained(self):
        text = 'one\ntwo\nthree\n'
        specification = {'schema': 1, 'sources': [{'id': 'source', 'path': 'src/Root.kt',
            'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceText': text,
            'sourceFile': 'Root.kt', 'packages': ['p'], 'members': []}]}
        tables = [[{'startPc': 7, 'lineNumber': 2}, {'startPc': 0, 'lineNumber': 1},
                   {'startPc': 7, 'lineNumber': 3}], [{'startPc': 7, 'lineNumber': 2}]]
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
            'sourceFiles': ['Root.kt'], 'sourceDebugExtensionIdentities': [],
            'sourceDebugExtensionContentsBase64': [], 'methods': [
                {'name': 'work', 'descriptor': '()V', 'attributes': ['Code'],
                 'code': {'attributes': ['LineNumberTable', 'LineNumberTable'], 'lineNumberTables': tables}},
                {'name': 'privateNative', 'descriptor': '(I)V', 'accessFlags': 258,
                 'attributes': [], 'code': None},
                {'name': 'generated', 'descriptor': '()V', 'attributes': ['Code'],
                 'code': {'attributes': [], 'lineNumberTables': []}}]}}

        result = lines.SourceLineBindings(specification).bind(row)

        self.assertEqual(tables, result['methods'][0]['originalLineNumberTablesCopiedVerbatim'])
        self.assertEqual(['work', 'privateNative', 'generated'], [method['name'] for method in result['methods']])
        self.assertFalse(result['methods'][1]['codePresent'])
        self.assertTrue(result['methods'][1]['lineMetadataUnavailable'])
        self.assertTrue(result['methods'][2]['lineMetadataUnavailable'])
        self.assertEqual([1, 2, 3], [item['generatedLine'] for item in result['lineCandidates']])

    def test_overlapping_regions_and_other_package_basename_candidates_are_retained(self):
        text = 'one\r\ntwo\r\nthree\r\n'
        regions = [
            {'id': 'first', 'firstLine': 1, 'lastLine': 2, 'sha256': hashlib.sha256(b'one\r\ntwo\r\n').hexdigest()},
            {'id': 'second', 'firstLine': 2, 'lastLine': 3, 'sha256': hashlib.sha256(b'two\r\nthree\r\n').hexdigest()}]
        sources = [{'id': name, 'path': name + '/Root.kt', 'sourceText': text,
                    'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceFile': 'Root.kt',
                    'packages': [package], 'members': [], 'regions': regions}
                   for name, package in [('source', 'p'), ('peer', 'peer')]]
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
            'sourceFiles': ['Root.kt'], 'sourceDebugExtensionIdentities': [],
            'sourceDebugExtensionContentsBase64': [], 'methods': [
                {'name': 'work', 'descriptor': '()V', 'attributes': ['Code'], 'code': {
                    'attributes': ['LineNumberTable'], 'lineNumberTables': [[{'startPc': 0, 'lineNumber': 2}]]}}]}}
        bindings = lines.SourceLineBindings({'schema': 1, 'sources': sources})

        candidates = bindings.bind(row)['lineCandidates'][0]['candidates'][0]['sourceCandidates']

        self.assertEqual(['source', 'peer'], [candidate['sourceId'] for candidate in candidates])
        self.assertEqual([True, False], [candidate['packageAnnotationAgrees'] for candidate in candidates])
        for candidate in candidates:
            self.assertEqual(['first', 'second'], [region['regionId'] for region in candidate['matchingRegions']])
            self.assertFalse(candidate['sourceSelectionProven'])
        self.assertEqual(regions, bindings.finish()['sources'][0]['regionsCopiedVerbatim'])

    def test_inline_body_and_call_site_strata_keep_distinct_source_candidates(self):
        text = 'one\ntwo\n'
        sources = [{'id': name, 'path': 'src/' + name + '.kt', 'sourceText': text,
                    'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceFile': name + '.kt',
                    'packages': ['p'], 'members': [], 'debugPaths': ['p/' + name + '.kt']}
                   for name in ['Body', 'Caller']]
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Body.kt\np/Body.kt\n*L\n2#1:100\n*S KotlinDebug\n*F\n+ 1 Caller.kt\np/Caller.kt\n*L\n1#1:100\n*E\n'
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/GeneratedKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile', 'SourceDebugExtension'],
            'sourceFiles': ['Generated.kt'],
            'sourceDebugExtensionIdentities': [{'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}],
            'sourceDebugExtensionContentsBase64': [base64.b64encode(raw).decode()], 'methods': [
                {'name': 'generated', 'descriptor': '()V', 'attributes': ['Code'], 'code': {
                    'attributes': ['LineNumberTable'], 'lineNumberTables': [[{'startPc': 7, 'lineNumber': 100}]]}}]}}

        result = lines.SourceLineBindings({'schema': 1, 'sources': sources}).bind(row)

        candidates = result['lineCandidates'][0]['candidates']
        self.assertEqual(['Java', 'Kotlin', 'KotlinDebug'], [candidate['stratum'] for candidate in candidates])
        self.assertEqual([], candidates[0]['sourceCandidates'])
        self.assertEqual('Body', candidates[1]['sourceCandidates'][0]['sourceId'])
        self.assertEqual('Caller', candidates[2]['sourceCandidates'][0]['sourceId'])
        self.assertTrue(candidates[1]['sourceCandidates'][0]['debugPathAnnotationAgrees'])
        self.assertFalse(result['sourcePhaseToJvmDescriptorMappingProven'])
        self.assertFalse(result['loadedClassSelectionProven'])

    def test_unavailable_source_and_out_of_source_lines_are_not_zero_evidence(self):
        text = 'one\n'
        sources = [{'id': name, 'path': 'src/' + name + '.kt', 'sourceText': text,
                    'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceFile': name + '.kt',
                    'packages': ['p'], 'members': []} for name in ['Root', 'Missing']]
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
            'sourceFiles': ['Root.kt'], 'sourceDebugExtensionIdentities': [],
            'sourceDebugExtensionContentsBase64': [], 'methods': [
                {'name': 'work', 'descriptor': '()V', 'attributes': ['Code'], 'code': {
                    'attributes': ['LineNumberTable'], 'lineNumberTables': [[{'startPc': 0, 'lineNumber': 2}]]}}]}}
        bindings = lines.SourceLineBindings({'schema': 1, 'sources': sources})

        result = bindings.bind(row)

        self.assertFalse(result['lineCandidates'][0]['candidates'][0]['sourceCandidates'][0]['sourceLineExists'])
        self.assertTrue(all(source['noInRangeLineCandidate'] for source in bindings.finish()['sources']))
        self.assertFalse(result['fullSourcePhaseCoverageProven'])

    def test_debug_bounds_decline_the_review_without_discrediting_verified_payload_bytes(self):
        text = 'one\n'
        source = {'id': 'source', 'path': 'src/Root.kt', 'sourceText': text,
                  'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceFile': 'Root.kt',
                  'packages': ['p'], 'members': []}
        raw = b'SMAP\nRoot.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:1\n*E\n'
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile', 'SourceDebugExtension'],
            'sourceFiles': ['Root.kt'], 'methods': [],
            'sourceDebugExtensionIdentities': [{'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}],
            'sourceDebugExtensionContentsBase64': [base64.b64encode(raw).decode()]}}
        bindings = lines.SourceLineBindings({'schema': 1, 'sources': [source]})

        with patch('jvm_source_debug.MAX_ITEMS', 0):
            result = bindings.bind(row)

        self.assertTrue(result['inputMetadataIntegrityVerified'])
        self.assertFalse(result['reviewCompleteWithinBounds'])
        self.assertTrue(result['sourceDebugMaps'][0]['boundExceeded'])
        self.assertEqual('unavailable', result['sourceDebugMaps'][0]['status'])

    def test_source_and_region_hashes_are_checked_without_line_ending_normalization(self):
        text = 'one\r\ntwo\r\n'
        source = {'id': 'source', 'path': 'src/Root.kt', 'sourceText': text,
                  'sha256': hashlib.sha256(text.encode()).hexdigest(), 'sourceFile': 'Root.kt',
                  'packages': ['p'], 'members': [], 'regions': [
                      {'id': 'first', 'firstLine': 1, 'lastLine': 1,
                       'sha256': hashlib.sha256(b'one\r\n').hexdigest()}]}
        for variant in ['source', 'region', 'absent', 'outside', 'duplicate', 'boolean']:
            changed = copy.deepcopy(source)
            if variant == 'source':
                changed['sourceText'] = text.replace('\r\n', '\n')
            elif variant == 'region':
                changed['regions'][0]['sha256'] = hashlib.sha256(b'one\n').hexdigest()
            elif variant == 'absent':
                del changed['sourceText']
            elif variant == 'outside':
                changed['regions'][0]['lastLine'] = 3
            elif variant == 'duplicate':
                changed['regions'].append(copy.deepcopy(changed['regions'][0]))
            else:
                changed['regions'][0]['firstLine'] = True

            with self.subTest(variant=variant), self.assertRaises(ValueError):
                lines.SourceLineBindings({'schema': 1, 'sources': [changed]})

    def test_complete_source_and_region_limits_prevent_binding(self):
        source = {'id': 'source', 'path': 'src/Root.kt', 'sourceText': 'one\n',
                  'sha256': hashlib.sha256(b'one\n').hexdigest(), 'sourceFile': 'Root.kt',
                  'packages': ['p'], 'members': [], 'regions': [
                      {'id': 'first', 'firstLine': 1, 'lastLine': 1,
                       'sha256': hashlib.sha256(b'one\n').hexdigest()}]}

        for bound in ['MAX_SOURCE_BYTES', 'MAX_REGIONS']:
            with self.subTest(bound=bound), patch.object(lines, bound, 0), self.assertRaises(ValueError):
                lines.SourceLineBindings({'schema': 1, 'sources': [source]})

    def test_missing_code_or_inconsistent_class_and_line_table_identities_decline(self):
        source = {'id': 'source', 'path': 'src/Root.kt', 'sourceText': 'one\n',
                  'sha256': hashlib.sha256(b'one\n').hexdigest(), 'sourceFile': 'Root.kt',
                  'packages': ['p'], 'members': []}
        row = {'classSha256': 'a' * 64, 'error': None, 'metadata': {'internalName': 'p/RootKt',
            'identity': {'bytes': 1, 'sha256': 'a' * 64}, 'attributes': ['SourceFile'],
            'sourceFiles': ['Root.kt'], 'sourceDebugExtensionIdentities': [],
            'sourceDebugExtensionContentsBase64': [], 'methods': [
                {'name': 'work', 'descriptor': '()V', 'attributes': ['Code'], 'code': {
                    'attributes': ['LineNumberTable'], 'lineNumberTables': [[{'startPc': 0, 'lineNumber': 1}]]}}]}}
        bindings = lines.SourceLineBindings({'schema': 1, 'sources': [source]})
        for variant in ['class-hash', 'code-absent', 'code-attributes', 'table-attributes', 'boolean-line']:
            changed = copy.deepcopy(row)
            if variant == 'class-hash':
                changed['classSha256'] = 'b' * 64
            elif variant == 'code-absent':
                del changed['metadata']['methods'][0]['code']
            elif variant == 'code-attributes':
                changed['metadata']['methods'][0]['attributes'] = []
            elif variant == 'table-attributes':
                changed['metadata']['methods'][0]['code']['attributes'] = []
            else:
                changed['metadata']['methods'][0]['code']['lineNumberTables'][0][0]['lineNumber'] = True

            with self.subTest(variant=variant), self.assertRaises(ValueError):
                bindings.bind(changed)


if __name__ == '__main__':
    unittest.main(verbosity=2)
