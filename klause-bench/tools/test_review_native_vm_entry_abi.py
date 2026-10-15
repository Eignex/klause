import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_entry_abi as abi


class ReviewNativeVmEntryAbiTest(unittest.TestCase):
    def test_the_fixed_entry_plan_uses_the_pinned_cpp_source_signatures(self):
        self.assertEqual('_ZN13CompileBroker25invoke_compiler_on_methodEP11CompileTask',
                         abi.linkage('invoke_compiler_on_method'))
        self.assertEqual('_ZN7nmethod20post_compiled_methodEP11CompileTask', abi.linkage('post_compiled_method'))

    def test_only_complete_direct_register_descriptions_establish_a_register(self):
        for expression, expected in [([0x55], 5), ([0x54], 4), ([0x90, 5], 5),
                                      ([0x90, 0x85, 0], None), ([0x90, 0x80], None),
                                      ([0x55, 0x9f], None), ([0xa3, 1, 0x55], None),
                                      ([0x75, 0], None), ([True], None), ([], None), ([256], None)]:
            with self.subTest(expression=expression):
                self.assertEqual(expected, abi.register(expression))

    def test_resolved_reference_targets_take_precedence_over_raw_attribute_values(self):
        graph = {'parameter': {'attributes': {'DW_AT_type': {'form': 'DW_FORM_ref4', 'value': 17}},
                               'references': {'DW_AT_type': 'pointer'}, 'referenceErrors': {}}}

        result = abi.inherited('parameter', 'DW_AT_type', graph)

        self.assertEqual([('parameter', 'pointer')], result)

    def test_origin_diamonds_retain_all_paths_without_treating_shared_nodes_as_cycles(self):
        graph = {'root': {'attributes': {}, 'references': {'DW_AT_abstract_origin': 'a', 'DW_AT_specification': 'b'}, 'referenceErrors': {}},
                 'a': {'attributes': {}, 'references': {'DW_AT_specification': 'value'}, 'referenceErrors': {}},
                 'b': {'attributes': {}, 'references': {'DW_AT_specification': 'value'}, 'referenceErrors': {}},
                 'value': {'attributes': {'name': {'value': 1}}, 'references': {}, 'referenceErrors': {}}}

        result = abi.inherited('root', 'name', graph)

        self.assertEqual([('value', {'value': 1}), ('value', {'value': 1})], result)

    def test_cyclic_and_unresolved_origin_references_fail_explicitly(self):
        for graph in [
                {'p': {'attributes': {}, 'references': {'DW_AT_abstract_origin': 'p'}, 'referenceErrors': {}}},
                {'p': {'attributes': {}, 'references': {}, 'referenceErrors': {'DW_AT_type': {'error': 'missing'}}}},
                {'p': {'attributes': {'DW_AT_type': {'form': 'DW_FORM_ref4', 'value': 1}}, 'references': {}, 'referenceErrors': {}}}]:
            with self.subTest(graph=graph), self.assertRaises(ValueError):
                abi.inherited('p', 'DW_AT_type', graph)

    def test_lineage_cycles_cannot_bind_an_object_parameter(self):
        graph = {'p': {'references': {'DW_AT_abstract_origin': 'q'}, 'referenceErrors': {}},
                 'q': {'references': {'DW_AT_specification': 'p'}, 'referenceErrors': {}}}

        with self.assertRaises(ValueError):
            abi.parameter_lineage('p', graph)

    def test_concrete_expression_locations_are_retained_without_inheriting_abstract_locations(self):
        parameter = {'attributes': {}, 'context': {'locations': {'DW_AT_location': {
            'status': 'decoded', 'data': [{'kind': 'LocationExpr', 'fields': {'loc_expr': [0x55]}}]}}}}

        result = abi.locations_at_entry(parameter, 10, {})

        self.assertEqual([{'kind': 'LocationExpr', 'expression': [0x55]}], result)
        with self.assertRaises(ValueError):
            abi.locations_at_entry({'attributes': {}, 'references': {'DW_AT_abstract_origin': 'abstract'}}, 10, {})

    def test_location_ranges_use_the_compilation_unit_base_and_keep_overlapping_descriptions(self):
        unit = {'topAttributes': {'DW_AT_low_pc': {'form': 'DW_FORM_addr', 'value': 100}}}
        rows = [{'kind': 'LocationEntry', 'fields': {'begin_offset': 1, 'end_offset': 5,
                 'loc_expr': [0x55], 'is_absolute': False}},
                {'kind': 'LocationEntry', 'fields': {'begin_offset': 101, 'end_offset': 103,
                 'loc_expr': [0x54], 'is_absolute': True}}]
        parameter = {'attributes': {}, 'context': {'locations': {'DW_AT_location': {'status': 'decoded', 'data': rows}}}}

        result = abi.locations_at_entry(parameter, 102, unit)

        self.assertEqual([[0x55], [0x54]], [row['expression'] for row in result])

    def test_default_locations_apply_only_when_no_bounded_range_covers_entry(self):
        rows = [{'kind': 'LocationEntry', 'fields': {'begin_offset': -1, 'end_offset': -1,
                 'loc_expr': [0x54], 'is_absolute': True}},
                {'kind': 'LocationEntry', 'fields': {'begin_offset': 10, 'end_offset': 20,
                 'loc_expr': [], 'is_absolute': True}}]
        parameter = {'attributes': {}, 'context': {'locations': {'DW_AT_location': {'status': 'decoded', 'data': rows}}}}

        self.assertEqual([], abi.locations_at_entry(parameter, 10, {})[0]['expression'])
        self.assertEqual([0x54], abi.locations_at_entry(parameter, 20, {})[0]['expression'])

    def test_ambiguous_default_missing_base_and_nonentry_scope_remain_unresolved(self):
        default = {'kind': 'LocationEntry', 'fields': {'begin_offset': -1, 'end_offset': -1,
                   'loc_expr': [0x55], 'is_absolute': True}}
        relative = {'kind': 'LocationEntry', 'fields': {'begin_offset': 0, 'end_offset': 5,
                    'loc_expr': [0x55], 'is_absolute': False}}
        for rows, attributes in [([default, default], {}), ([relative], {}),
                                  ([default], {'DW_AT_start_scope': {'form': 'DW_FORM_data1', 'value': 1}})]:
            with self.subTest(rows=rows, attributes=attributes), self.assertRaises(ValueError):
                abi.locations_at_entry({'attributes': attributes, 'context': {'locations': {
                    'DW_AT_location': {'status': 'decoded', 'data': rows}}}}, 1, {})

    def test_physical_entry_requires_its_exact_start_full_span_and_normal_calling_convention(self):
        original = {'attributes': {'DW_AT_low_pc': {'form': 'DW_FORM_addr', 'value': 100},
                                  'DW_AT_high_pc': {'form': 'DW_FORM_data1', 'value': 10}}}
        self.assertEqual({'start': 100, 'end': 110, 'symbolBytes': 5}, abi.function_entry(original, 100, 5))
        for mode in ['start', 'size', 'split', 'convention']:
            with self.subTest(mode=mode):
                node = copy.deepcopy(original)
                if mode == 'start':
                    node['attributes']['DW_AT_entry_pc'] = {'form': 'DW_FORM_addr', 'value': 101}
                elif mode == 'size':
                    node['attributes']['DW_AT_high_pc']['value'] = 2
                elif mode == 'split':
                    node['attributes']['DW_AT_ranges'] = {'form': 'DW_FORM_sec_offset', 'value': 1}
                else:
                    node['attributes']['DW_AT_calling_convention'] = {'form': 'DW_FORM_data1', 'value': 2}
                with self.assertRaises(ValueError):
                    abi.function_entry(node, 100, 5)

    def test_only_the_exact_unambiguous_canonical_entry_plan_can_be_proven(self):
        for mode in ['valid', 'alias', 'conflicting-entry', 'register', 'pointer-width', 'object', 'partial', 'name-error']:
            with self.subTest(mode=mode):
                graph = {}
                for name in ['CompileTask', 'nmethod']:
                    graph[name] = {'tag': 'DW_TAG_class_type', 'attributes': {'DW_AT_name': {
                        'form': 'DW_FORM_string', 'value': {'bytesHex': name.encode().hex()}}},
                        'references': {}, 'referenceErrors': {}}
                    graph[name + '*'] = {'tag': 'DW_TAG_pointer_type', 'attributes': {
                        'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 8}},
                        'references': {'DW_AT_type': name}, 'referenceErrors': {}}
                selected = []
                groups = {query: [] for query in abi.QUERIES}
                for index, query in enumerate(abi.TARGETS):
                    pc = 100 + index * 100
                    children = []
                    graph[query] = {'tag': 'DW_TAG_subprogram', 'unitOffset': 0, 'context': {'unitTopDie': 'unit'},
                                    'attributes': {'DW_AT_low_pc': {'form': 'DW_FORM_addr', 'value': pc},
                                                   'DW_AT_high_pc': {'form': 'DW_FORM_data1', 'value': 10},
                                                   'DW_AT_linkage_name': {'form': 'DW_FORM_string',
                                                                          'value': {'bytesHex': abi.linkage(query).encode().hex()}}},
                                    'references': {}, 'referenceErrors': {}}
                    for parameter, target, register in abi.TARGETS[query][1]:
                        identity = query + ':' + parameter
                        graph[identity] = {'tag': 'DW_TAG_formal_parameter', 'parent': query,
                                           'parentKnownFromSelectedTraversal': True, 'attributes': {
                                               'DW_AT_type': {'form': 'DW_FORM_ref4', 'value': 17}},
                                           'references': {'DW_AT_type': target + '*'}, 'referenceErrors': {},
                                           'context': {'locations': {'DW_AT_location': {'status': 'decoded', 'data': [
                                               {'kind': 'LocationExpr', 'fields': {'loc_expr': [0x50 + register]}}]}}}}
                        if parameter == 'this':
                            graph[identity]['attributes']['DW_AT_artificial'] = {'form': 'DW_FORM_flag_present', 'value': True}
                            graph[query]['references']['DW_AT_object_pointer'] = identity
                        children.append(identity)
                    selected.append({'die': query, 'children': children, 'nameCandidates': [{'nameHex': query.encode().hex()}]})
                    symbol = {'name': abi.linkage(query), 'function': True, 'undefined': False,
                              'ordinarySectionDefinition': True, 'value': pc, 'size': 1}
                    groups[query].append({'originalSymbol': symbol, 'completed': True,
                                           'byteCoverage': 'complete symbol span', 'truncated': False,
                                           'decoderCoverage': 'complete input', 'entryHex': 'c3',
                                           'entrySha256': hashlib.sha256(b'\xc3').hexdigest(),
                                           'entryBytes': 1, 'decodedBytes': 1, 'fileOffset': pc})
                source = {'dwarf': {'graph': graph, 'selectedRecords': selected, 'nameErrors': []},
                          'contextUnits': {'unit': {'unitOffset': 0, 'topDie': 'unit', 'topAttributes': {}}}}
                first = 'invoke_compiler_on_method'
                if mode in ['alias', 'conflicting-entry']:
                    extra = copy.deepcopy(groups[first][0])
                    if mode == 'alias':
                        extra['originalSymbol']['name'] += '.isra.0'
                    else:
                        extra['originalSymbol']['value'] += 1
                    groups[first].append(extra)
                elif mode == 'register':
                    graph[first + ':task']['context']['locations']['DW_AT_location']['data'][0]['fields']['loc_expr'] = [0x54]
                elif mode == 'pointer-width':
                    graph['CompileTask*']['attributes'] = {}
                elif mode == 'object':
                    graph['post_compiled_method']['references'] = {}
                elif mode == 'partial':
                    groups[first][0]['truncated'] = True
                elif mode == 'name-error':
                    source['dwarf']['nameErrors'] = [{'error': 'unresolved name'}]
                entries = {'entries': groups, 'symbols': {'candidates': {
                    query: [row['originalSymbol'] for row in rows] for query, rows in groups.items()}}}
                result = {}

                abi.review(source, {'layoutDefinitions': [{'ownerDie': 'CompileTask', 'problems': []}]}, entries, result)

                self.assertEqual(mode in ['valid', 'alias'], result['actualTargetCallingConventionProven'])
                self.assertFalse(result['privateFieldReadsAuthorized'])
                self.assertEqual(groups[first], [row['originalEntry'] for row in result['entries'][first]])

    def test_input_hash_and_known_capture_identity_precede_json_decoding(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_bytes(b'bad')
            for digest in ['0' * 64, abi.CONTEXT_SHA256]:
                with self.subTest(digest=digest), patch.object(abi.json, 'loads') as parse, self.assertRaises(ValueError):
                    abi.inspect([path] * 3, [digest, abi.LAYOUT_SHA256, abi.ENTRIES_SHA256], Path(directory), {})
                parse.assert_not_called()

    def test_review_record_budget_fails_before_publishing_partial_entry_proofs(self):
        source = {'dwarf': {'graph': {'inline': {'tag': 'DW_TAG_inlined_subroutine'}},
                            'selectedRecords': [{'die': 'inline'}], 'nameErrors': []}}
        result = {}

        with patch.object(abi, 'MAX_OUTPUT_BYTES', 0), self.assertRaises(abi.AbiLimit):
            abi.review(source, {'layoutDefinitions': [{'ownerDie': 'task', 'problems': []}]}, {}, result)

        self.assertFalse(result['actualTargetCallingConventionProven'])
        self.assertFalse(result['privateFieldReadsAuthorized'])

    def test_failed_main_drops_partial_abi_claims_but_keeps_original_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'review.json'

            def failure(*args):
                args[-1].update(actualTargetCallingConventionProven=True,
                                targetConcordance={'target': {'actualTargetCallingConventionProven': True}})
                raise ValueError('incomplete entry evidence')

            with patch.object(sys, 'argv', ['review', 'c', 'h', 'l', 'h', 'e', 'h', str(output)]), \
                    patch.object(abi, 'inspect', side_effect=failure), self.assertRaises(ValueError):
                abi.main()

            result = json.loads(output.read_text())
            self.assertFalse(result['completed'])
            self.assertFalse(result['actualTargetCallingConventionProven'])
            self.assertFalse(result['targetConcordance']['target']['actualTargetCallingConventionProven'])
            self.assertFalse(result['privateFieldReadsAuthorized'])
            self.assertEqual('ValueError', result['error']['type'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
