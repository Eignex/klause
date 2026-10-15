import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_dwarf_layout as layout


class ReviewNativeVmDwarfLayoutTest(unittest.TestCase):
    def test_source_text_requires_bounded_exact_utf8_byte_attributes(self):
        for value, expected in [({'bytesHex': b'int'.hex()}, 'int'), ({'bytesHex': 'ff'}, None),
                                 ({'bytesHex': 'zz'}, None), ({'bytesHex': '00', 'other': 1}, None),
                                 ({'bytesHex': '00' * 16385}, None), ('int', None)]:
            with self.subTest(value=repr(value)[:40]):
                self.assertEqual(expected, layout.byte_text(value))

    def test_source_declarations_require_the_pinned_path_line_and_unambiguous_file_entry(self):
        node = {'attributes': {'DW_AT_decl_line': {'form': 'DW_FORM_data2', 'value': 87}},
                'context': {'fileReferences': {'DW_AT_decl_file': {
                    'status': 'header-file-entry', 'file': {'name': {'bytesHex': b'compileTask.hpp'.hex()}},
                    'directory': {'status': 'header-directory',
                                  'entry': {'bytesHex': b'/build/src/hotspot/share/compiler'.hex()}}}}}}
        for mode, expected in [('valid', True), ('absolute', True), ('line', False), ('file', False), ('ambiguous', False)]:
            with self.subTest(mode=mode):
                source = copy.deepcopy(node)
                if mode == 'absolute':
                    source['context']['fileReferences']['DW_AT_decl_file']['file']['name'] = {
                        'bytesHex': ('/build/' + layout.HEADER_PATH).encode().hex()}
                    source['context']['fileReferences']['DW_AT_decl_file']['directory'] = {}
                elif mode == 'line':
                    source['attributes']['DW_AT_decl_line']['value'] = 88
                elif mode == 'file':
                    source['context']['fileReferences']['DW_AT_decl_file']['file']['name'] = {'bytesHex': b'other.hpp'.hex()}
                elif mode == 'ambiguous':
                    source['context']['fileReferences']['DW_AT_decl_file']['status'] = 'ambiguous-v5-zero-file-index'

                result = layout.source_location(source, 87)

                self.assertEqual(expected, result['status'] == 'pinned-source-declaration-concordant')

    def test_scalar_storage_uses_exported_width_and_dwarf_encoding_without_host_inference(self):
        member = {'storage': {'status': 'constant-storage-candidate', 'tag': 'DW_TAG_base_type',
                              'byteSize': 4, 'encoding': 5, 'name': 'int'}}
        exported = {'typeTable': {'rows': [{'typeName': 'int', 'isIntegerType': 1, 'isUnsigned': 0, 'size': 4}]}}
        for mode, expected in [('int', True), ('alias', True), ('width', False), ('unsigned', False),
                                ('encoding', False), ('duplicate', False), ('boolean-flag', False)]:
            with self.subTest(mode=mode):
                observed, metadata = copy.deepcopy(member), copy.deepcopy(exported)
                declared = 'CodeSection::csize_t' if mode == 'alias' else 'int'
                if mode == 'width':
                    metadata['typeTable']['rows'][0]['size'] = 8
                elif mode == 'unsigned':
                    metadata['typeTable']['rows'][0]['isUnsigned'] = 1
                elif mode == 'encoding':
                    observed['storage']['encoding'] = 7
                elif mode == 'duplicate':
                    metadata['typeTable']['rows'] *= 2
                elif mode == 'boolean-flag':
                    metadata['typeTable']['rows'][0]['isIntegerType'] = True

                self.assertEqual(expected, layout.compiled_type(observed, declared, metadata, {}))

    def test_pointer_storage_requires_its_own_width_and_a_resolved_method_target(self):
        member = {'storage': {'status': 'constant-storage-candidate', 'tag': 'DW_TAG_pointer_type',
                              'byteSize': 8, 'chain': ['pointer']}}
        graph = {'pointer': {'references': {'DW_AT_type': 'method'}},
                 'method': {'tag': 'DW_TAG_class_type', 'attributes': {
                     'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'Method'.hex()}}},
                     'references': {}, 'referenceErrors': {}}}
        for mode, expected in [('valid', True), ('width', False), ('missing', False), ('cycle', False)]:
            with self.subTest(mode=mode):
                observed, nodes = copy.deepcopy(member), copy.deepcopy(graph)
                if mode == 'width':
                    observed['storage']['byteSize'] = 4
                elif mode == 'missing':
                    nodes['pointer']['references'] = {}
                elif mode == 'cycle':
                    nodes['method'].update(tag='DW_TAG_typedef', references={'DW_AT_type': 'method'})

                self.assertEqual(expected, layout.compiled_type(observed, 'Method*', {'pointerWidth': 8}, nodes))

    def test_members_with_unknown_or_wrong_parents_cannot_bind_a_declared_owner(self):
        member = {'name': '_compile_id', 'die': 'member', 'status': 'constant-member-candidate'}
        owner = {'members': [member], 'originalSelectedRecord': {'die': 'owner'}}
        for parent, known in [('other', True), ('owner', False)]:
            with self.subTest(parent=parent, known=known):
                source = {'dwarf': {'graph': {'member': {'parent': parent, 'parentKnownFromSelectedTraversal': known}}}}

                result = layout.check_member('_compile_id', 'int', 87, owner, source, {})

                self.assertEqual('member-owner-unresolved', result['status'])
                self.assertFalse(result['actualFieldLayoutProven'])

    def test_duplicate_and_unresolved_members_remain_explicit(self):
        member = {'name': '_compile_id', 'status': 'location-unresolved'}
        for members in [[member], [member, member]]:
            with self.subTest(count=len(members)):
                result = layout.check_member('_compile_id', 'int', 87, {'members': members}, {}, {})

                self.assertEqual(members, result['members'])
                self.assertFalse(result['actualFieldLayoutProven'])

    def test_exported_metadata_requires_complete_tables_and_pinned_library_scope(self):
        metadata = {'completed': True, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                    'librarySha256': layout.LIBRARY_SHA256, 'pointerWidth': 8,
                    **{name: {'terminated': True, 'rows': []}
                       for name in ['structTable', 'typeTable', 'intConstantTable']}}
        for mode in ['incomplete', 'library', 'pointer', 'unterminated', 'oversized', 'bad-row']:
            with self.subTest(mode=mode):
                source = copy.deepcopy(metadata)
                if mode == 'incomplete':
                    source['completed'] = False
                elif mode == 'library':
                    source['librarySha256'] = '0' * 64
                elif mode == 'pointer':
                    source['pointerWidth'] = True
                elif mode == 'unterminated':
                    source['structTable']['terminated'] = False
                elif mode == 'oversized':
                    source['structTable']['rows'] = [{}] * (layout.MAX_ROWS + 1)
                else:
                    source['typeTable']['rows'] = [None]

                with self.assertRaises(ValueError):
                    layout.validate_layout(source)

    def test_only_complete_global_layout_concordance_can_prove_the_requested_fields(self):
        for mode in ['valid', 'namespace', 'owner-size', 'export-offset', 'duplicate-export', 'source-line',
                     'overlap', 'name-error', 'coverage', 'conflicting-definition']:
            with self.subTest(mode=mode):
                graph = {}
                for name, width, encoding in [('int', 4, 5), ('bool', 1, 2)]:
                    graph[name] = {'tag': 'DW_TAG_base_type', 'references': {}, 'referenceErrors': {},
                                   'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': name.encode().hex()}},
                                                  'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': width},
                                                  'DW_AT_encoding': {'form': 'DW_FORM_data1', 'value': encoding}}}
                graph['pointer'] = {'tag': 'DW_TAG_pointer_type', 'references': {'DW_AT_type': 'Method'},
                                    'referenceErrors': {}, 'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 8}}}
                graph['Method'] = {'tag': 'DW_TAG_class_type', 'references': {}, 'referenceErrors': {},
                                   'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'Method'.hex()}}}}
                graph['owner'] = {'tag': 'DW_TAG_class_type', 'references': {}, 'referenceErrors': {},
                                  'parent': 'unit', 'parentKnownFromSelectedTraversal': True, 'unitOffset': 0,
                                  'context': {'unitTopDie': 'unit'},
                                  'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 128}}}
                declarations = {**layout.FIELDS, **layout.ANCHORS}
                exports = []
                for index, (name, (declared, line)) in enumerate(declarations.items()):
                    kind = 'pointer' if declared == 'Method*' else 'bool' if declared == 'bool' else 'int'
                    graph[name] = {'tag': 'DW_TAG_member', 'references': {'DW_AT_type': kind}, 'referenceErrors': {},
                                   'parent': 'owner', 'parentKnownFromSelectedTraversal': True,
                                   'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': name.encode().hex()}},
                                                  'DW_AT_data_member_location': {'form': 'DW_FORM_data1', 'value': index * 8},
                                                  'DW_AT_decl_line': {'form': 'DW_FORM_data1', 'value': line}},
                                   'context': {'fileReferences': {'DW_AT_decl_file': {
                                       'status': 'header-file-entry', 'file': {'name': {'bytesHex': layout.HEADER_PATH.encode().hex()}},
                                       'directory': {'status': 'header-directory', 'entry': {'bytesHex': b'/build'.hex()}}}}}}
                    if name in layout.ANCHORS:
                        exports.append({'typeName': 'CompileTask', 'fieldName': name, 'typeString': declared,
                                        'isStatic': 0, 'offset': index * 8})
                selected = [{'die': 'owner', 'children': list(declarations), 'nameCandidates': [{'nameHex': b'CompileTask'.hex()}]}]
                source = {'dwarf': {'graph': graph, 'selectedRecords': selected, 'nameErrors': [],
                                    'units': [{'offset': 0, 'topTag': 'DW_TAG_compile_unit'}],
                                    'requestedUnitCoverage': {name: [{'traversalCompleted': True}] for name in
                                                             ['compileBroker.cpp', 'compileTask.cpp', 'nmethod.cpp', 'compilerThread.cpp']}},
                          'contextUnits': {'unit': {'topDie': 'unit', 'unitOffset': 0}}}
                metadata = {'completed': True, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
                            'librarySha256': layout.LIBRARY_SHA256, 'pointerWidth': 8,
                            'structTable': {'terminated': True, 'rows': exports},
                            'typeTable': {'terminated': True, 'rows': [
                                {'typeName': 'CompileTask', 'size': 128},
                                {'typeName': 'int', 'size': 4, 'isIntegerType': 1, 'isUnsigned': 0},
                                {'typeName': 'bool', 'size': 1, 'isIntegerType': 1, 'isUnsigned': 1}]},
                            'intConstantTable': {'terminated': True, 'rows': []}}
                if mode == 'namespace':
                    graph['owner']['parent'] = 'namespace'
                elif mode == 'owner-size':
                    metadata['typeTable']['rows'][0]['size'] = 120
                elif mode == 'export-offset':
                    exports[0]['offset'] += 1
                elif mode == 'duplicate-export':
                    exports.append(copy.deepcopy(exports[0]))
                elif mode == 'source-line':
                    graph['_nm_total_size']['attributes']['DW_AT_decl_line']['value'] = 1
                elif mode == 'overlap':
                    graph['_is_complete']['attributes']['DW_AT_data_member_location']['value'] = 0
                elif mode == 'name-error':
                    source['dwarf']['nameErrors'] = [{'error': 'unresolved name'}]
                elif mode == 'coverage':
                    source['dwarf']['requestedUnitCoverage']['compileTask.cpp'] = []
                elif mode == 'conflicting-definition':
                    graph['second'] = copy.deepcopy(graph['owner'])
                    graph['second']['attributes']['DW_AT_byte_size']['value'] = 120
                    selected.append({'die': 'second', 'children': [], 'nameCandidates': [{'nameHex': b'CompileTask'.hex()}]})
                result = {}

                layout.review(source, metadata, result)

                self.assertEqual(mode == 'valid', result['actualFieldLayoutProven'])
                self.assertEqual(len(selected), len(result['typeCandidates']))
                self.assertFalse(result['actualTargetCallingConventionProven'])
                self.assertFalse(result['privateFieldReadsAuthorized'])
                self.assertEqual(set(layout.FIELDS), set(result['fieldLayoutConcordance']))

    def test_input_bounds_and_hashes_are_checked_before_json_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_bytes(b'invalid')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            for maximum, expected in [(0, digest), (100, '0' * 64)]:
                with self.subTest(maximum=maximum), patch.object(layout, 'MAX_CONTEXT_BYTES', maximum), \
                        patch.object(layout.json, 'loads') as parse, self.assertRaises(ValueError):
                    layout.inspect(path, expected, path, digest, Path(directory), {})

                parse.assert_not_called()

    def test_original_exported_capture_identity_cannot_be_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_bytes(b'{}')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()

            with patch.object(layout.json, 'loads') as parse, self.assertRaises(ValueError):
                layout.inspect(path, digest, path, digest, Path(directory), {})

            parse.assert_not_called()
            self.assertEqual(b'{}', (Path(directory) / 'captured-vm-exported-layout.json').read_bytes())

    def test_context_identity_scope_and_binding_failures_precede_layout_review(self):
        flags = {name: False for name in ['actualFieldLayoutProven', 'actualTargetCallingConventionProven',
                                         'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven',
                                         'acceptedSavingsCredit', 'locationExpressionsEvaluated']}
        original = {**flags, 'sourceSha256': layout.READER_SHA256, 'contextSourceSha256': layout.CONTEXT_SHA256,
                    'completed': True, 'diagnosticOnly': True, 'contextScope': dict(flags),
                    'dwarf': {'selectedUnitTraversalCompleted': True, 'locationExpressionsEvaluated': False},
                    'fileBinding': {'externalDebugFileBound': True, 'library': {'sha256': layout.LIBRARY_SHA256},
                                    'externalDebug': {'sha256': layout.DEBUG_SHA256}}}
        for mode in ['reader', 'context-reader', 'completed', 'library', 'debug', 'binding',
                     'source-proof', 'context-proof', 'expression']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                source = copy.deepcopy(original)
                if mode == 'reader':
                    source['sourceSha256'] = '0' * 64
                elif mode == 'context-reader':
                    source['contextSourceSha256'] = '0' * 64
                elif mode == 'completed':
                    source['completed'] = False
                elif mode in ['library', 'debug']:
                    source['fileBinding']['library' if mode == 'library' else 'externalDebug']['sha256'] = '0' * 64
                elif mode == 'binding':
                    source['fileBinding']['externalDebugFileBound'] = False
                elif mode == 'source-proof':
                    source['actualFieldLayoutProven'] = True
                elif mode == 'context-proof':
                    source['contextScope']['actualFieldLayoutProven'] = True
                else:
                    source['contextScope']['locationExpressionsEvaluated'] = True
                context_path, layout_path = Path(directory) / 'context.json', Path(directory) / 'layout.json'
                context_path.write_text(json.dumps(source))
                layout_path.write_bytes(b'{}')
                context_hash = hashlib.sha256(context_path.read_bytes()).hexdigest()
                metadata_hash = hashlib.sha256(layout_path.read_bytes()).hexdigest()

                with patch.object(layout, 'LAYOUT_SHA256', metadata_hash), patch.object(layout, 'review') as review, \
                        self.assertRaises(ValueError):
                    layout.inspect(context_path, context_hash, layout_path, metadata_hash, Path(directory), {})

                review.assert_not_called()
                self.assertEqual(context_path.read_bytes(), (Path(directory) / 'captured-vm-dwarf-context.json').read_bytes())

    def test_failed_main_clears_all_partial_field_proof_claims_and_retains_the_error(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'review.json'

            def failure(*args):
                args[-1].update(actualFieldLayoutProven=True,
                                fieldLayoutConcordance={'field': {'actualFieldLayoutProven': True}})
                raise ValueError('incomplete evidence')

            with patch.object(sys, 'argv', ['review', 'context', 'hash', 'layout', 'hash', str(output)]), \
                    patch.object(layout, 'inspect', side_effect=failure), self.assertRaises(ValueError):
                layout.main()

            result = json.loads(output.read_text())
            self.assertFalse(result['completed'])
            self.assertFalse(result['actualFieldLayoutProven'])
            self.assertFalse(result['fieldLayoutConcordance']['field']['actualFieldLayoutProven'])
            self.assertFalse(result['privateFieldReadsAuthorized'])
            self.assertEqual('ValueError', result['error']['type'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
