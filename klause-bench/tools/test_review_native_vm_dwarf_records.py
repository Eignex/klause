import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_dwarf_records as review


class ReviewNativeVmDwarfRecordsTest(unittest.TestCase):
    def test_only_explicit_integer_constant_forms_supply_scalar_candidates(self):
        for form, value, expected in [('DW_FORM_data1', 4, 4), ('DW_FORM_sdata', -1, -1),
                                      ('DW_FORM_ref4', 4, None), ('DW_FORM_flag', True, None),
                                      ('DW_FORM_data1', True, None)]:
            with self.subTest(form=form, value=value):
                node = {'attributes': {'size': {'form': form, 'value': value}}}

                self.assertEqual(expected, review.scalar(node, 'size'))

    def test_qualifiers_retain_the_complete_storage_reference_chain(self):
        graph = {
            'alias': {'tag': 'DW_TAG_typedef', 'references': {'DW_AT_type': 'const'}, 'referenceErrors': {}},
            'const': {'tag': 'DW_TAG_const_type', 'references': {'DW_AT_type': 'int'}, 'referenceErrors': {}},
            'int': {'tag': 'DW_TAG_base_type', 'references': {}, 'referenceErrors': {},
                    'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4},
                                   'DW_AT_encoding': {'form': 'DW_FORM_data1', 'value': 5}}},
        }

        result = review.storage('alias', graph)

        self.assertEqual('constant-storage-candidate', result['status'])
        self.assertEqual(['alias', 'const', 'int'], result['chain'])
        self.assertEqual(4, result['byteSize'])
        self.assertEqual(5, result['encoding'])

    def test_missing_cyclic_and_failed_type_references_remain_unresolved(self):
        for graph, expected in [({}, 'missing-type-record'),
                                ({'x': {'tag': 'DW_TAG_typedef', 'references': {'DW_AT_type': 'x'},
                                        'referenceErrors': {}}}, 'cyclic-or-oversized-type-chain'),
                                ({'x': {'tag': 'DW_TAG_typedef', 'references': {},
                                        'referenceErrors': {'DW_AT_type': {'message': 'unavailable'}}}},
                                 'unresolved-type-reference')]:
            with self.subTest(expected=expected):
                self.assertEqual(expected, review.storage('x', graph)['status'])

    def test_missing_pointer_width_is_not_inferred_from_target_or_unit_width(self):
        for tag, attributes in [('DW_TAG_pointer_type', {}),
                                ('DW_TAG_array_type', {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 8}})]:
            with self.subTest(tag=tag):
                graph = {'x': {'tag': tag, 'attributes': attributes, 'references': {}, 'referenceErrors': {}}}

                self.assertEqual('unsupported-or-missing-storage', review.storage('x', graph)['status'])

    def test_constant_member_candidates_keep_their_original_attributes(self):
        graph = {
            'field': {'tag': 'DW_TAG_member', 'references': {'DW_AT_type': 'int'}, 'referenceErrors': {},
                      'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'_compile_id'.hex()}},
                                     'DW_AT_data_member_location': {'form': 'DW_FORM_data1', 'value': 8}}},
            'int': {'tag': 'DW_TAG_base_type', 'references': {}, 'referenceErrors': {},
                    'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4}}},
        }

        result = review.member('field', graph, 16)

        self.assertEqual('constant-member-candidate', result['status'])
        self.assertEqual(8, result['constantOffset'])
        self.assertEqual(graph['field']['attributes'], result['attributes'])

    def test_bit_fields_expression_locations_and_out_of_bounds_members_remain_unresolved(self):
        graph = {
            'field': {'tag': 'DW_TAG_member', 'references': {'DW_AT_type': 'int'}, 'referenceErrors': {},
                      'attributes': {'DW_AT_data_member_location': {'form': 'DW_FORM_data1', 'value': 8}}},
            'int': {'tag': 'DW_TAG_base_type', 'references': {}, 'referenceErrors': {},
                    'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4}}},
        }
        for attributes, size, expected in [
                ({'DW_AT_bit_size': {'form': 'DW_FORM_data1', 'value': 1}}, 16, 'bit-field-unresolved'),
                ({'DW_AT_data_member_location': {'form': 'DW_FORM_exprloc', 'value': {'bytesHex': '00'}}}, 16, 'location-unresolved'),
                ({'DW_AT_data_member_location': {'form': 'DW_FORM_sdata', 'value': -1}}, 16, 'owner-bounds-unresolved'),
                ({}, 10, 'owner-bounds-unresolved'), ({}, None, 'owner-bounds-unresolved')]:
            with self.subTest(expected=expected, size=size):
                candidate = copy.deepcopy(graph)
                candidate['field']['attributes'].update(attributes)

                self.assertEqual(expected, review.member('field', candidate, size)['status'])

    def test_declarations_and_duplicate_definitions_remain_separate_from_field_concordance(self):
        graph = {
            'definition': {'tag': 'DW_TAG_class_type', 'references': {}, 'referenceErrors': {},
                           'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 16}}},
            'declaration': {'tag': 'DW_TAG_class_type', 'references': {}, 'referenceErrors': {},
                            'attributes': {'DW_AT_declaration': {'form': 'DW_FORM_flag_present', 'value': True}}},
            'member': {'tag': 'DW_TAG_member', 'references': {'DW_AT_type': 'int'}, 'referenceErrors': {},
                       'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'_compile_id'.hex()}},
                                      'DW_AT_data_member_location': {'form': 'DW_FORM_data1', 'value': 4}}},
            'int': {'tag': 'DW_TAG_base_type', 'references': {}, 'referenceErrors': {},
                    'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4}}},
        }
        graph['duplicate'] = copy.deepcopy(graph['definition'])
        selected = [{'die': name, 'children': [] if name == 'declaration' else ['member'],
                     'nameCandidates': [{'nameHex': b'CompileTask'.hex()}]}
                    for name in ['declaration', 'definition', 'duplicate']]
        source = {'dwarf': {'graph': graph, 'selectedRecords': selected,
                            'requestedUnitCoverage': {'compileTask.cpp': []}, 'nameErrors': []}}
        result = {}

        review.review(source, result)

        self.assertEqual(3, len(result['typeCandidates']))
        concordance = result['taskFieldConcordance']['_compile_id']
        self.assertEqual('consistent-dwarf-candidates', concordance['status'])
        self.assertEqual(2, len(concordance['definitions']))
        self.assertFalse(concordance['actualFieldLayoutProven'])
        self.assertEqual('incomplete-or-conflicting', result['taskFieldConcordance']['_nm_total_size']['status'])

    def test_incomplete_or_ambiguous_owner_names_cannot_supply_consistent_fields(self):
        for names, attributes in [([b'CompileTask'], {}),
                                  ([b'CompileTask', b'Other'], {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 16}})]:
            with self.subTest(names=names):
                source = {'dwarf': {'graph': {
                    'class': {'tag': 'DW_TAG_class_type', 'references': {}, 'referenceErrors': {}, 'attributes': attributes}},
                    'selectedRecords': [{'die': 'class', 'children': [],
                                         'nameCandidates': [{'nameHex': name.hex()} for name in names]}],
                    'requestedUnitCoverage': {}, 'nameErrors': []}}
                result = {}

                review.review(source, result)

                self.assertEqual('incomplete-or-conflicting', result['taskFieldConcordance']['_compile_id']['status'])
                self.assertEqual(1, len(result['taskFieldConcordance']['_compile_id']['definitions']))

    def test_conflicting_definitions_cannot_be_resolved_by_choosing_an_offset(self):
        graph, selected = {}, []
        for index, offset in enumerate([4, 8]):
            owner, field = f'class{index}', f'field{index}'
            graph[owner] = {'tag': 'DW_TAG_class_type', 'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 16}},
                            'references': {}, 'referenceErrors': {}}
            graph[field] = {'tag': 'DW_TAG_member', 'references': {'DW_AT_type': 'int'}, 'referenceErrors': {},
                            'attributes': {'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'_compile_id'.hex()}},
                                           'DW_AT_data_member_location': {'form': 'DW_FORM_data1', 'value': offset}}}
            selected.append({'die': owner, 'children': [field], 'nameCandidates': [{'nameHex': b'CompileTask'.hex()}]})
        graph['int'] = {'tag': 'DW_TAG_base_type', 'attributes': {'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4}},
                        'references': {}, 'referenceErrors': {}}
        result = {}

        review.review({'dwarf': {'graph': graph, 'selectedRecords': selected, 'requestedUnitCoverage': {}, 'nameErrors': []}}, result)

        self.assertEqual('incomplete-or-conflicting', result['taskFieldConcordance']['_compile_id']['status'])
        self.assertEqual([4, 8], [row['members'][0]['constantOffset']
                                 for row in result['taskFieldConcordance']['_compile_id']['definitions']])

    def test_function_and_inline_parameters_keep_locations_without_abi_or_range_evaluation(self):
        graph = {'parameter': {'tag': 'DW_TAG_formal_parameter', 'attributes': {
            'DW_AT_location': {'form': 'DW_FORM_exprloc', 'value': {'bytesHex': '55'}}},
            'references': {}, 'referenceErrors': {}}}
        selected = []
        for tag in ['DW_TAG_subprogram', 'DW_TAG_inlined_subroutine']:
            graph[tag] = {'tag': tag, 'attributes': {}, 'references': {}, 'referenceErrors': {}}
            selected.append({'die': tag, 'children': ['parameter'], 'nameCandidates': [{'nameHex': b'total_size'.hex()}]})
        result = {}

        review.review({'dwarf': {'graph': graph, 'selectedRecords': selected, 'requestedUnitCoverage': {}, 'nameErrors': []}}, result)

        self.assertEqual(2, len(result['functionCandidates']))
        for candidate in result['functionCandidates']:
            self.assertEqual('unproved', candidate['callingConventionStatus'])
            self.assertEqual('not-evaluated', candidate['rangesStatus'])
            self.assertEqual(graph['parameter'], candidate['parameters'][0]['record'])
            self.assertEqual('not-evaluated', candidate['parameters'][0]['locationStatus'])

    def test_input_bounds_hash_and_producer_checks_precede_json_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'captured.json'
            path.write_bytes(b'invalid')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            for bound, expected_hash, producer in [(0, digest, review.READER_SHA256),
                                                   (100, '0' * 64, review.READER_SHA256),
                                                   (100, digest, '0' * 64)]:
                with self.subTest(bound=bound, producer=producer), patch.object(review, 'MAX_INPUT_BYTES', bound), \
                        patch.object(review.json, 'loads') as parse, self.assertRaises(ValueError):
                    review.inspect(path, expected_hash, producer, Path(directory), {})

                parse.assert_not_called()

    def test_incomplete_inventory_or_expanded_original_proof_scope_is_rejected(self):
        source = {'sourceSha256': review.READER_SHA256, 'completed': True, 'diagnosticOnly': True,
                  'acceptedSavingsCredit': False, 'fileBinding': {'externalDebugFileBound': True},
                  'dwarf': {'selectedUnitTraversalCompleted': True, 'locationExpressionsEvaluated': False},
                  'actualFieldLayoutProven': False, 'actualTargetCallingConventionProven': False,
                  'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False}
        for flag in ['completed', 'diagnosticOnly', 'acceptedSavingsCredit', 'actualFieldLayoutProven',
                     'actualTargetCallingConventionProven', 'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                changed = copy.deepcopy(source)
                changed[flag] = not changed[flag]
                raw = json.dumps(changed).encode()
                path = Path(directory) / 'input.json'
                path.write_bytes(raw)

                with self.assertRaises(ValueError):
                    review.inspect(path, hashlib.sha256(raw).hexdigest(), review.READER_SHA256, Path(directory), {})

    def test_original_bytes_and_unresolved_name_records_are_retained_before_review(self):
        source = {'sourceSha256': review.READER_SHA256, 'completed': True, 'diagnosticOnly': True,
                  'acceptedSavingsCredit': False, 'fileBinding': {'externalDebugFileBound': True},
                  'dwarf': {'selectedUnitTraversalCompleted': True, 'locationExpressionsEvaluated': False,
                            'graph': {}, 'selectedRecords': [], 'requestedUnitCoverage': {'compileTask.cpp': []},
                            'nameErrors': [{'die': 'unknown', 'error': {'message': 'unsupported reference'}}]},
                  'actualFieldLayoutProven': False, 'actualTargetCallingConventionProven': False,
                  'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False}
        raw = json.dumps(source, indent=2).encode()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_bytes(raw)
            result = {}

            review.inspect(path, hashlib.sha256(raw).hexdigest(), review.READER_SHA256, Path(directory), result)

            self.assertEqual(raw, (Path(directory) / 'captured-vm-dwarf-records.json').read_bytes())
            self.assertTrue(result['completed'])
            self.assertEqual(source['dwarf']['nameErrors'], result['originalNameErrors'])
            self.assertEqual('no-concrete-definitions', result['taskFieldConcordance']['_compile_id']['status'])

    def test_incomplete_or_oversized_saved_graphs_fail_without_review_completion(self):
        for graph, selected in [({'x': None}, []), ({'x': {}}, []), ({}, [{}])]:
            with self.subTest(graph=graph, selected=selected), patch.object(review, 'MAX_GRAPH', 0), \
                    patch.object(review, 'MAX_SELECTED', 0), self.assertRaises(ValueError):
                review.review({'dwarf': {'graph': graph, 'selectedRecords': selected}}, {})

    def test_failed_input_writes_a_report_without_expanding_any_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            captured, output = Path(directory) / 'input.json', Path(directory) / 'review.json'
            captured.write_bytes(b'invalid')
            with patch.object(sys, 'argv', ['review', str(captured), '0' * 64, review.READER_SHA256, str(output)]), \
                    self.assertRaises(ValueError):
                review.main()

            result = json.loads(output.read_text())
            for flag in ['completed', 'acceptedSavingsCredit', 'actualFieldLayoutProven',
                         'actualTargetCallingConventionProven', 'actualControlFlowCoverageProven',
                         'rootCensusProven', 'codeSizeProven']:
                self.assertFalse(result[flag])

    def test_output_bounds_retain_a_partial_report_without_claiming_completion(self):
        source = {'dwarf': {'graph': {}, 'selectedRecords': [], 'requestedUnitCoverage': {}, 'nameErrors': []}}
        result = {'completed': False}

        with patch.object(review, 'MAX_REVIEW_BYTES', 0), self.assertRaises(ValueError):
            review.review(source, result)

        self.assertFalse(result['completed'])
        self.assertEqual([], result['typeCandidates'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
