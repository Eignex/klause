import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_task_lifecycle_abi as lifecycle


class ReviewNativeVmTaskLifecycleAbiTest(unittest.TestCase):
    def test_every_fixed_lifecycle_role_and_alias_requires_its_complete_source_read_plan(self):
        for mode in ['valid', 'split-valid', 'missing-alias', 'conflicting-free', 'task-register', 'int-width',
                     'object-lineage', 'source-line', 'split-range', 'unmatched-definition', 'return-type']:
            with self.subTest(mode=mode):
                graph = {}
                for name in ['CompileTask', 'CompileTaskWrapper']:
                    graph[name] = {'tag': 'DW_TAG_class_type', 'attributes': {'DW_AT_name': {
                        'form': 'DW_FORM_string', 'value': {'bytesHex': name.encode().hex()}}},
                        'references': {}, 'referenceErrors': {}}
                    graph[name + '*'] = {'tag': 'DW_TAG_pointer_type', 'attributes': {
                        'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 8}},
                        'references': {'DW_AT_type': name}, 'referenceErrors': {}}
                graph['int'] = {'tag': 'DW_TAG_base_type', 'attributes': {
                    'DW_AT_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': b'int'.hex()}},
                    'DW_AT_byte_size': {'form': 'DW_FORM_data1', 'value': 4},
                    'DW_AT_encoding': {'form': 'DW_FORM_data1', 'value': 5}},
                    'references': {}, 'referenceErrors': {}}
                descriptions = [
                    ('allocate', ['_ZN11CompileTask8allocateEv'], 'allocate', 0, None, 45),
                    ('initialize', ['_ZN11CompileTask10initializeEiRK12methodHandleiiiNS_13CompileReasonEb'],
                     'initialize', 8, 'CompileTask', 98),
                    ('free', ['_ZN11CompileTask4freeEPS_'], 'free', 1, None, 67),
                    ('construct', ['_ZN18CompileTaskWrapperC1EP11CompileTask', '_ZN18CompileTaskWrapperC2EP11CompileTask'],
                     'CompileTaskWrapper', 2, 'CompileTaskWrapper', 217),
                    ('destroy', ['_ZN18CompileTaskWrapperD1Ev', '_ZN18CompileTaskWrapperD2Ev'],
                     '~CompileTaskWrapper', 1, 'CompileTaskWrapper', 224),
                ]
                selected, entries = [], []
                for index, (role, symbols, name, count, owner, line) in enumerate(descriptions):
                    pc = 100 + index * 100
                    filename = 'compileBroker.cpp' if owner == 'CompileTaskWrapper' else 'compileTask.cpp'
                    graph[role] = {'tag': 'DW_TAG_subprogram', 'unitOffset': 0, 'parent': 'unit',
                                   'parentKnownFromSelectedTraversal': True,
                                   'attributes': {'DW_AT_low_pc': {'form': 'DW_FORM_addr', 'value': pc},
                                                  'DW_AT_high_pc': {'form': 'DW_FORM_data1', 'value': 10},
                                                  'DW_AT_linkage_name': {'form': 'DW_FORM_string', 'value': {'bytesHex': symbols[0].encode().hex()}},
                                                  'DW_AT_decl_file': {'form': 'DW_FORM_data1', 'value': 1},
                                                  'DW_AT_decl_line': {'form': 'DW_FORM_data1', 'value': line}},
                                   'references': {}, 'referenceErrors': {},
                                   'context': {'unitTopDie': 'unit', 'fileReferences': {'DW_AT_decl_file': {
                                       'status': 'header-file-entry', 'file': {'name': {'bytesHex': filename.encode().hex()}},
                                       'directory': {'status': 'header-directory', 'entry': {
                                           'bytesHex': b'/build/src/hotspot/share/compiler'.hex()}}}}}}
                    children = []
                    for parameter in range(count):
                        identity = role + ':' + str(parameter)
                        kind = owner + '*' if owner is not None and parameter == 0 else \
                            'CompileTask*' if role in ['free', 'construct'] else 'int'
                        location = [0x55] if role == 'free' or role == 'initialize' and parameter == 0 else \
                            [0x54] if role == 'construct' and parameter == 1 or role == 'initialize' and parameter == 1 else None
                        graph[identity] = {'tag': 'DW_TAG_formal_parameter', 'parent': role,
                                           'parentKnownFromSelectedTraversal': True, 'attributes': {},
                                           'references': {'DW_AT_type': kind}, 'referenceErrors': {}, 'context': {'locations': {}}}
                        if owner is not None and parameter == 0:
                            graph[identity]['attributes']['DW_AT_artificial'] = {'form': 'DW_FORM_flag_present', 'value': True}
                            graph[role]['references']['DW_AT_object_pointer'] = identity
                        if location is not None:
                            graph[identity]['context']['locations']['DW_AT_location'] = {
                                'status': 'decoded', 'data': [{'kind': 'LocationExpr', 'fields': {'loc_expr': location}}]}
                        children.append(identity)
                    if role == 'allocate':
                        graph[role]['references']['DW_AT_type'] = 'CompileTask*'
                    selected.append({'die': role, 'children': children, 'nameCandidates': [{'nameHex': name.encode().hex()}]})
                    for symbol in symbols:
                        entries.append({'originalSymbol': {'name': symbol, 'function': True, 'undefined': False,
                                                          'ordinarySectionDefinition': True, 'value': pc, 'size': 1},
                                        'completed': True, 'byteCoverage': 'complete symbol span', 'truncated': False,
                                        'decoderCoverage': 'complete input', 'entryHex': 'c3', 'undecodedHex': '',
                                        'entrySha256': hashlib.sha256(b'\xc3').hexdigest(), 'entryBytes': 1,
                                        'decodedBytes': 1, 'fileOffset': pc})
                if mode == 'missing-alias':
                    entries.pop()
                elif mode == 'conflicting-free':
                    duplicate = copy.deepcopy(entries[2])
                    duplicate['originalSymbol']['value'] += 1
                    entries.append(duplicate)
                elif mode == 'task-register':
                    graph['free:0']['context']['locations']['DW_AT_location']['data'][0]['fields']['loc_expr'] = [0x54]
                elif mode == 'int-width':
                    graph['int']['attributes']['DW_AT_byte_size']['value'] = 8
                elif mode == 'object-lineage':
                    graph['initialize']['references']['DW_AT_object_pointer'] = 'free:0'
                elif mode == 'source-line':
                    graph['free']['attributes']['DW_AT_decl_line']['value'] = 1
                elif mode == 'split-range':
                    graph['free']['attributes']['DW_AT_ranges'] = {'form': 'DW_FORM_sec_offset', 'value': 0}
                elif mode == 'split-valid':
                    for name in ['initialize', 'destroy']:
                        node = graph[name]
                        pc = node['attributes'].pop('DW_AT_low_pc')['value']
                        node['attributes'].pop('DW_AT_high_pc')
                        node['attributes']['DW_AT_ranges'] = {'form': 'DW_FORM_sec_offset', 'value': 0}
                        node['context']['ranges'] = {'status': 'decoded', 'data': [
                            {'kind': 'RangeEntry', 'fields': {'begin_offset': pc, 'end_offset': pc + 10, 'is_absolute': True}},
                            {'kind': 'RangeEntry', 'fields': {'begin_offset': pc + 20, 'end_offset': pc + 25, 'is_absolute': True}}]}
                elif mode == 'unmatched-definition':
                    graph['extra'] = copy.deepcopy(graph['free'])
                    graph['extra']['attributes']['DW_AT_low_pc']['value'] = 999
                    selected.append({**selected[2], 'die': 'extra'})
                elif mode == 'return-type':
                    graph['allocate']['references']['DW_AT_type'] = 'int'
                source = {'dwarf': {'graph': graph, 'selectedRecords': selected, 'nameErrors': []},
                          'contextUnits': {'unit': {'unitOffset': 0, 'topDie': 'unit', 'version': 4, 'topAttributes': {}}}}
                report = {}

                with patch.object(lifecycle, 'task_definitions', return_value={'CompileTask'}):
                    lifecycle.review(source, {}, {}, {'captured': entries}, report)

                self.assertEqual(mode in ['valid', 'split-valid'], report['lifecycleArgumentPlanProven'])
                self.assertEqual(entries, report['entries']['captured'])
                if mode in ['valid', 'split-valid']:
                    self.assertTrue(all(row['argumentPlanProven'] for row in report['roles'].values()))
                    returned = report['roles']['allocate']['entries'][0]['definitions'][0]
                    self.assertTrue(returned['pointerReturnClassProven'])
                    self.assertEqual(0, returned['pointerReturnClass']['dwarfRegister'])
                    constructed = report['roles']['construct']['entries'][0]['definitions'][0]
                    self.assertEqual(['task'], [row['name'] for row in constructed['parameterBindings']])

    def test_split_spans_require_complete_nonoverlapping_ranges_and_the_physical_symbol_start(self):
        for mode in ['absolute', 'relative', 'base-entry', 'explicit-entry', 'missing-base', 'interior',
                     'oversized', 'overlap', 'conflicting-entry', 'constant-entry', 'unknown-kind',
                     'undecoded', 'unknown-version', 'declaration', 'non-normal', 'overflow', 'empty']:
            with self.subTest(mode=mode):
                node = {'attributes': {'DW_AT_ranges': {'form': 'DW_FORM_sec_offset', 'value': 0}},
                        'context': {'ranges': {'status': 'decoded', 'data': [
                            {'kind': 'RangeEntry', 'fields': {'begin_offset': 100, 'end_offset': 110, 'is_absolute': True}},
                            {'kind': 'RangeEntry', 'fields': {'begin_offset': 50, 'end_offset': 55, 'is_absolute': True}}]}}}
                unit = {'version': 4, 'topAttributes': {}}
                pc, size = 100, 10
                if mode in ['relative', 'missing-base', 'base-entry']:
                    for row in node['context']['ranges']['data']:
                        row['fields']['is_absolute'] = False
                    if mode == 'relative':
                        unit['topAttributes']['DW_AT_low_pc'] = {'form': 'DW_FORM_addr', 'value': 0}
                    elif mode == 'base-entry':
                        node['context']['ranges']['data'].insert(0, {'kind': 'BaseAddressEntry', 'fields': {'base_address': 0}})
                elif mode in ['explicit-entry', 'conflicting-entry', 'constant-entry']:
                    node['attributes']['DW_AT_entry_pc'] = {'form': 'DW_FORM_addr' if mode != 'constant-entry' else 'DW_FORM_data1',
                                                          'value': 101 if mode == 'conflicting-entry' else 100}
                elif mode == 'interior':
                    pc, size = 101, 1
                elif mode == 'oversized':
                    size = 11
                elif mode == 'overlap':
                    node['context']['ranges']['data'][1]['fields'].update(begin_offset=105, end_offset=115)
                elif mode == 'unknown-kind':
                    node['context']['ranges']['data'][1]['kind'] = 'UnknownEntry'
                elif mode == 'undecoded':
                    node['context']['ranges']['status'] = 'error'
                elif mode == 'unknown-version':
                    unit['version'] = 2
                elif mode == 'declaration':
                    node['attributes']['DW_AT_declaration'] = {'form': 'DW_FORM_flag_present', 'value': True}
                elif mode == 'non-normal':
                    node['attributes']['DW_AT_calling_convention'] = {'form': 'DW_FORM_data1', 'value': 2}
                elif mode == 'overflow':
                    node['context']['ranges']['data'][1]['fields']['end_offset'] = lifecycle.abi.MAX_ADDRESS + 1
                elif mode == 'empty':
                    node['context']['ranges']['data'] = []

                if mode in ['absolute', 'relative', 'base-entry', 'explicit-entry']:
                    result = lifecycle.physical_function_span(node, unit, pc, size)

                    self.assertEqual('canonical-ELF-function-symbol', result['entryBasis'])
                    self.assertEqual(mode == 'explicit-entry', result['explicitDwarfEntryAddressProven'])
                    self.assertEqual(node['context']['ranges'], result['originalRangeDescription'])
                    self.assertEqual(100, result['start'])
                    self.assertEqual(110, result['end'])
                else:
                    with self.assertRaises(ValueError):
                        lifecycle.physical_function_span(node, unit, pc, size)

    def test_fatal_failure_clears_new_range_association_proofs_without_changing_saved_inputs(self):
        original = {'explicitDwarfEntryAddressProven': True}
        report = {'roles': {'initialize': {'argumentPlanProven': True, 'entries': [],
                                         'entryAssociationDiagnostics': [{'physicalEntrySpanProven': True,
                                                                         'functionRange': {'explicitDwarfEntryAddressProven': True}}]}},
                  'entries': {'original': original}}

        lifecycle.decline(report, ValueError('output limit'))

        association = report['roles']['initialize']['entryAssociationDiagnostics'][0]
        self.assertFalse(association['physicalEntrySpanProven'])
        self.assertFalse(association['functionRange']['explicitDwarfEntryAddressProven'])
        self.assertTrue(original['explicitDwarfEntryAddressProven'])

    def test_type_rebinding_allows_only_parser_accounting_to_change(self):
        for mode in ['cache', 'type-change', 'new-owner', 'missing-member']:
            with self.subTest(mode=mode):
                graph = {
                    'task': {'tag': 'DW_TAG_class_type', 'attributes': {}, 'references': {}, 'referenceErrors': {}},
                    'member': {'tag': 'DW_TAG_member', 'attributes': {'size': {'value': 4}},
                               'references': {'DW_AT_type': 'int'}, 'referenceErrors': {},
                               'context': {'parserBudget': {'bytes': 1}}},
                    'int': {'tag': 'DW_TAG_base_type', 'attributes': {'size': {'value': 4}},
                            'references': {}, 'referenceErrors': {}},
                }
                selected = [{'die': 'task', 'children': ['member'], 'nameCandidates': [{'nameHex': b'CompileTask'.hex()}]}]
                original = {'dwarf': {'graph': graph, 'selectedRecords': selected}}
                source = copy.deepcopy(original)
                source['dwarf']['graph']['member']['context']['parserBudget']['bytes'] = 10
                if mode == 'type-change':
                    source['dwarf']['graph']['int']['attributes']['size']['value'] = 8
                elif mode == 'new-owner':
                    source['dwarf']['graph']['other'] = copy.deepcopy(graph['task'])
                    source['dwarf']['selectedRecords'].append({**selected[0], 'die': 'other'})
                elif mode == 'missing-member':
                    source['dwarf']['graph'].pop('member')
                layout = {'layoutDefinitions': [{'ownerDie': 'task', 'problems': []}]}

                if mode == 'cache':
                    self.assertEqual({'task'}, lifecycle.task_definitions(source, original, layout))
                else:
                    with self.assertRaises(ValueError):
                        lifecycle.task_definitions(source, original, layout)

    def test_lifecycle_declarations_require_the_pinned_cpp_or_header_path_and_line(self):
        for filename, line, accepted in [('compileTask.cpp', 67, True), ('compileTask.hpp', 126, True),
                                          ('compileTask.cpp', 126, False), ('other.cpp', 67, False)]:
            with self.subTest(filename=filename, line=line):
                graph = {'free': {'attributes': {'DW_AT_decl_file': {'form': 'DW_FORM_data1', 'value': 1},
                                                'DW_AT_decl_line': {'form': 'DW_FORM_data1', 'value': line}},
                                  'references': {}, 'referenceErrors': {}, 'context': {'fileReferences': {
                                      'DW_AT_decl_file': {'status': 'header-file-entry',
                                          'file': {'name': {'bytesHex': filename.encode().hex()}},
                                          'directory': {'status': 'header-directory', 'entry': {
                                              'bytesHex': b'/build/src/hotspot/share/compiler'.hex()}}}}}}}

                if accepted:
                    self.assertEqual(line, lifecycle.source_declarations('free', graph, 'free')[0]['line'])
                else:
                    with self.assertRaises(ValueError):
                        lifecycle.source_declarations('free', graph, 'free')

    def test_hash_and_resource_failures_precede_json_interpretation(self):
        for mode in ['hash', 'individual', 'aggregate']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                path = out / 'input'
                path.write_bytes(b'{}')
                digest = hashlib.sha256(b'{}').hexdigest()
                inputs = {name: (digest, producer) for name, (_, producer) in lifecycle.INPUTS.items()}
                expected = [digest] * len(inputs)
                if mode == 'hash':
                    path.write_bytes(b'[]')
                with patch.object(lifecycle, 'INPUTS', inputs), \
                        patch.object(lifecycle, 'MAX_INPUT_BYTES', 1 if mode == 'individual' else 100), \
                        patch.object(lifecycle, 'MAX_TOTAL_BYTES', 3 if mode == 'aggregate' else 100), \
                        patch.object(lifecycle.json, 'loads') as decode, self.assertRaises(ValueError):
                    lifecycle.inspect([path] * len(inputs), expected, out, {})

                decode.assert_not_called()

    def test_all_hash_bound_originals_are_retained_before_json_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            path = out / 'input'
            path.write_bytes(b'not json')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            inputs = {name: (digest, producer) for name, (_, producer) in lifecycle.INPUTS.items()}

            with patch.object(lifecycle, 'INPUTS', inputs), self.assertRaises(json.JSONDecodeError):
                lifecycle.inspect([path] * len(inputs), [digest] * len(inputs), out, {})

            for name in inputs:
                self.assertEqual(b'not json', (out / ('captured-vm-' + name + '.json')).read_bytes())

    def test_fatal_decline_clears_new_partial_proofs_without_rewriting_originals(self):
        original = {'completed': True}
        definition = {'sourceBoundaryProven': True, 'argumentPlanProven': True,
                      'pointerReturnClassProven': True, 'parameterBindings': [{'readBindingProven': True}]}
        report = {'completed': True, 'lifecycleArgumentPlanProven': True, 'roles': {'free': {
            'argumentPlanProven': True, 'entries': [{'argumentPlanProven': True,
                                                    'originalEntry': original, 'definitions': [definition]}]}}}

        lifecycle.decline(report, ValueError('limit'))

        self.assertFalse(report['completed'])
        self.assertFalse(report['lifecycleArgumentPlanProven'])
        self.assertFalse(definition['sourceBoundaryProven'])
        self.assertFalse(definition['pointerReturnClassProven'])
        self.assertFalse(definition['parameterBindings'][0]['readBindingProven'])
        self.assertEqual({'completed': True}, original)
        self.assertEqual('limit', report['error']['message'])

    def test_failed_main_retains_diagnostics_and_no_new_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'report.json'
            with patch.object(sys, 'argv', ['review', *(['input', 'hash'] * 5), str(out)]), \
                    patch.object(lifecycle, 'inspect', side_effect=ValueError('identity differs')), self.assertRaises(ValueError):
                lifecycle.main()

            report = json.loads(out.read_text())
            self.assertFalse(report['completed'])
            self.assertFalse(report['lifecycleArgumentPlanProven'])
            self.assertFalse(report['privateFieldReadsAuthorized'])
            self.assertEqual('identity differs', report['error']['message'])

    def test_successful_read_plan_cannot_authorize_lifetime_or_private_field_reads(self):
        def complete(paths, expected, output, report):
            report.update(completed=True, lifecycleArgumentPlanProven=True)

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'report.json'
            with patch.object(sys, 'argv', ['review', *(['input', 'hash'] * 5), str(out)]), \
                    patch.object(lifecycle, 'inspect', side_effect=complete):
                lifecycle.main()

            report = json.loads(out.read_text())
            self.assertTrue(report['lifecycleArgumentPlanProven'])
            for key in ['actualTargetCallingConventionProven', 'actualCompiledTaskLifetimeProven',
                        'actualTaskGenerationProven', 'privateFieldReadsAuthorized', 'rootCensusProven',
                        'codeSizeProven', 'acceptedSavingsCredit']:
                self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
