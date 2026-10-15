import json
import io
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import read_native_vm_dwarf_records as records


class ReadNativeVmDwarfRecordsTest(unittest.TestCase):
    def test_lifecycle_selection_retains_additional_names_without_changing_the_default(self):
        for selection in ['compiler', 'task-lifecycle']:
            with self.subTest(selection=selection):
                unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
                root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                                       attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=0)})
                declared = [('DW_TAG_class_type', b'CompileTask'), ('DW_TAG_class_type', b'CompileTaskWrapper'),
                            ('DW_TAG_subprogram', b'invoke_compiler_on_method'),
                            *[('DW_TAG_subprogram', name) for name in
                              [b'allocate', b'initialize', b'free', b'CompileTaskWrapper', b'~CompileTaskWrapper']],
                            ('DW_TAG_subprogram', b'unrelated')]
                entries = [SimpleNamespace(cu=unit, offset=index, tag=tag, has_children=False,
                                           attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=name, raw_value=name, offset=index)},
                                           iter_children=Mock(return_value=iter([])))
                           for index, (tag, name) in enumerate(declared, 1)]
                unit.get_top_DIE = Mock(return_value=root)
                unit.iter_DIEs = Mock(return_value=iter([root, *entries, SimpleNamespace(cu=unit, tag=None)]))
                report = {}

                records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report,
                                  selection=selection)

                expected = {b'CompileTask', b'invoke_compiler_on_method'}
                if selection == 'task-lifecycle':
                    expected |= {b'CompileTaskWrapper', b'~CompileTaskWrapper', b'allocate', b'initialize', b'free'}
                retained = {bytes.fromhex(name['nameHex']) for row in report['selectedRecords']
                            for name in row['nameCandidates']}
                self.assertEqual(expected, retained)
                self.assertEqual(selection, report['selectionPlan']['name'])
                self.assertEqual(['compileBroker.cpp', 'compileTask.cpp', 'compilerThread.cpp', 'nmethod.cpp'],
                                 sorted(report['requestedUnitCoverage']))
                self.assertTrue(report['selectedUnitTraversalCompleted'])

    def test_lifecycle_origins_and_inline_records_retain_declarations_children_and_errors(self):
        unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
        root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                               attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=0)})
        declared = SimpleNamespace(cu=unit, offset=1, tag='DW_TAG_subprogram', has_children=False,
                                   attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'free', raw_value=b'free', offset=1),
                                               'DW_AT_declaration': SimpleNamespace(form='DW_FORM_flag', value=True, raw_value=True, offset=2)},
                                   iter_children=Mock(return_value=iter([])))
        concrete = SimpleNamespace(cu=unit, offset=2, tag='DW_TAG_subprogram', has_children=False,
                                   attributes={'DW_AT_specification': SimpleNamespace(form='DW_FORM_ref4', value=1, raw_value=1, offset=3)},
                                   get_DIE_from_attribute=Mock(return_value=declared), iter_children=Mock(return_value=iter([])))
        inline = SimpleNamespace(cu=unit, offset=3, tag='DW_TAG_inlined_subroutine', has_children=True,
                                 attributes={'DW_AT_abstract_origin': SimpleNamespace(form='DW_FORM_ref4', value=1, raw_value=1, offset=4)},
                                 get_DIE_from_attribute=Mock(return_value=declared))
        child = SimpleNamespace(cu=unit, offset=4, tag='DW_TAG_formal_parameter', has_children=False,
                                attributes={'DW_AT_type': SimpleNamespace(form='DW_FORM_ref4', value=5, raw_value=5, offset=5)},
                                get_DIE_from_attribute=Mock(side_effect=LookupError('missing parameter type')))
        inline.iter_children = Mock(return_value=iter([child]))
        unit.get_top_DIE = Mock(return_value=root)
        unit.iter_DIEs = Mock(return_value=iter([root, declared, concrete, inline, child,
                                               SimpleNamespace(cu=unit, tag=None), SimpleNamespace(cu=unit, tag=None)]))
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report,
                          selection='task-lifecycle')

        self.assertEqual([records.key(declared), records.key(concrete), records.key(inline)],
                         [row['die'] for row in report['selectedRecords']])
        self.assertEqual([records.key(child)], report['selectedRecords'][2]['children'])
        self.assertEqual('LookupError', report['graph'][records.key(child)]['referenceErrors']['DW_AT_type']['type'])
        self.assertTrue(report['graph'][records.key(declared)]['attributes']['DW_AT_declaration']['value'])
        self.assertFalse(report['locationExpressionsEvaluated'])

    def test_unknown_selection_is_rejected_before_visiting_native_metadata(self):
        dwarf = Mock()
        report = {}

        with self.assertRaises(ValueError):
            records.inventory(dwarf, report, selection='other')

        dwarf.iter_CUs.assert_not_called()
        self.assertEqual({}, report)

    def test_lifecycle_and_invalid_cli_options_retain_failure_before_provider_access(self):
        for options, selection in [(['--task-lifecycle'], 'task-lifecycle'), (['--unknown'], None),
                                   (['--task-lifecycle', '--task-lifecycle'], None)]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                library, output = Path(directory) / 'library', Path(directory) / 'report.json'
                library.write_bytes(b'wrong library')
                with patch.object(records.sys, 'argv', ['reader', str(library), str(output), *options]), \
                        patch.object(records.binding, 'download') as download, patch.object(records.provider, 'load') as load, \
                        self.assertRaises(ValueError):
                    records.main()

                download.assert_not_called()
                load.assert_not_called()
                report = json.loads(output.read_text())
                self.assertEqual(selection, report.get('selectionPlan', {}).get('name'))
                self.assertEqual('ValueError', report['error']['type'])
                for key in ['completed', 'acceptedSavingsCredit', 'actualFieldLayoutProven',
                            'actualTargetCallingConventionProven', 'actualControlFlowCoverageProven', 'rootCensusProven',
                            'actualCompiledTaskLifetimeProven', 'actualTaskGenerationProven', 'privateFieldReadsAuthorized']:
                    self.assertFalse(report[key])

    def test_pinned_provider_reads_a_complete_synthetic_class_and_member_graph(self):
        abbreviations = bytes([1, 0x11, 1, 3, 8, 0, 0,
                               2, 2, 1, 3, 8, 0x0b, 0x0b, 0, 0,
                               3, 0x0d, 0, 3, 8, 0x38, 0x0b, 0x49, 0x13, 0, 0,
                               4, 0x24, 0, 3, 8, 0x0b, 0x0b, 0x3e, 0x0b, 0, 0, 0])
        prefix = b'\x01compileTask.cpp\0\x02CompileTask\0\x10\x03_code_size\0\x08'
        primitive_offset = 11 + len(prefix) + 4 + 1
        dies = prefix + struct.pack('<I', primitive_offset) + b'\0\x04int\0\x04\x05\0'
        info = struct.pack('<IHIb', 7 + len(dies), 4, 0, 8) + dies
        section_names = b'\0.shstrtab\0.debug_info\0.debug_abbrev\0'
        start = 64 + 4 * 64
        header = records.binding.symbols.ELF_HEADER.pack(
            b'\x7fELF\x02\x01\x01'.ljust(16, b'\0'), 3, 62, 1, 0, 0, 64, 0, 64, 56, 0, 64, 4, 1)
        sections = bytes(64)
        for name, kind, offset, size in [
                (b'.shstrtab', 3, start, len(section_names)),
                (b'.debug_info', 1, start + len(section_names), len(info)),
                (b'.debug_abbrev', 1, start + len(section_names) + len(info), len(abbreviations))]:
            sections += records.binding.symbols.SECTION_HEADER.pack(
                section_names.index(name), kind, 0, 0, offset, size, 0, 0, 1, 0)
        raw = header + sections + section_names + info + abbreviations
        original_path, original_bytecode = list(sys.path), sys.dont_write_bytecode
        try:
            with tempfile.TemporaryDirectory() as directory:
                provenance, report = {}, {}
                reader = records.provider.load(Path(directory), provenance)
                elf = reader(io.BytesIO(raw), stream_loader=None)

                records.inventory(elf.get_dwarf_info(relocate_dwarf_sections=False, follow_links=False), report)

                self.assertTrue(report['selectedUnitTraversalCompleted'])
                self.assertEqual(1, len(report['selectedRecords']))
                selected = report['selectedRecords'][0]
                member = report['graph'][selected['children'][0]]
                primitive = report['graph'][member['references']['DW_AT_type']]
                self.assertEqual(8, member['attributes']['DW_AT_data_member_location']['value'])
                self.assertEqual(4, primitive['attributes']['DW_AT_byte_size']['value'])
                records.provider.verify(Path(provenance['directory']), provenance)
        finally:
            for name in tuple(sys.modules):
                if name == 'elftools' or name.startswith('elftools.'):
                    del sys.modules[name]
            sys.path[:] = original_path
            sys.dont_write_bytecode = original_bytecode

    def test_attribute_values_preserve_scalar_and_byte_representations(self):
        for raw, expected in [(None, None), (True, True), (7, 7), ('name', 'name'),
                              (b'\x00\xff', {'bytesHex': '00ff'}), ([0, 255], {'bytesHex': '00ff'})]:
            with self.subTest(raw=raw):
                self.assertEqual(expected, records.value(raw))

    def test_unsupported_or_oversized_attribute_values_are_rejected(self):
        for raw in [object(), {'value': 1}, [-1], [256], 'x' * (records.MAX_VALUE_BYTES + 1),
                    b'x' * (records.MAX_VALUE_BYTES + 1)]:
            with self.subTest(kind=type(raw).__name__), self.assertRaises(ValueError):
                records.value(raw)

    def test_name_origins_and_specifications_retain_all_candidates(self):
        unit = SimpleNamespace(cu_offset=0)
        first = SimpleNamespace(cu=unit, offset=1, attributes={'DW_AT_name': SimpleNamespace(value=b'first')})
        second = SimpleNamespace(cu=unit, offset=2, attributes={'DW_AT_name': SimpleNamespace(value=b'second')})
        root = SimpleNamespace(cu=unit, offset=3,
                               attributes={'DW_AT_abstract_origin': SimpleNamespace(form='DW_FORM_ref4'),
                                           'DW_AT_specification': SimpleNamespace(form='DW_FORM_ref4')},
                               get_DIE_from_attribute=Mock(side_effect=[first, second]))

        result = records.names(root)

        self.assertEqual([{'die': records.key(first), 'nameHex': b'first'.hex()},
                          {'die': records.key(second), 'nameHex': b'second'.hex()}], result)

    def test_cyclic_name_references_are_rejected(self):
        die = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=1,
                              attributes={'DW_AT_abstract_origin': SimpleNamespace(form='DW_FORM_ref4')})
        die.get_DIE_from_attribute = Mock(return_value=die)

        with self.assertRaises(ValueError):
            records.names(die)

    def test_unit_kinds_do_not_alias_at_the_same_numeric_offsets(self):
        first = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=1)
        second = SimpleNamespace(cu=Mock(cu_offset=0), offset=1)

        self.assertNotEqual(records.key(first), records.key(second))

    def test_unselected_unit_headers_are_retained_without_visiting_entries(self):
        name = SimpleNamespace(form='DW_FORM_string', value=b'other.cpp', raw_value=b'other.cpp', offset=0)
        top = SimpleNamespace(tag='DW_TAG_compile_unit', attributes={'DW_AT_name': name})
        unit = SimpleNamespace(cu_offset=0, size=10, header={'version': 5},
                               get_top_DIE=Mock(return_value=top), iter_DIEs=Mock())
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report)

        unit.iter_DIEs.assert_not_called()
        self.assertFalse(report['units'][0]['selected'])
        self.assertEqual([], report['selectedRecords'])
        self.assertTrue(report['selectedUnitTraversalCompleted'])

    def test_selected_class_children_and_type_references_keep_raw_attributes_and_parents(self):
        unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
        name = SimpleNamespace(form='DW_FORM_string', value=b'/src/compileTask.cpp', raw_value=b'/src/compileTask.cpp', offset=0)
        root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                               attributes={'DW_AT_name': name})
        cls = SimpleNamespace(cu=unit, offset=1, tag='DW_TAG_class_type', has_children=True,
                              attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'CompileTask', raw_value=b'CompileTask', offset=1)})
        member = SimpleNamespace(cu=unit, offset=2, tag='DW_TAG_member', has_children=False,
                                 attributes={'DW_AT_type': SimpleNamespace(form='DW_FORM_ref4', value=3, raw_value=3, offset=2),
                                             'DW_AT_data_member_location': SimpleNamespace(form='DW_FORM_data1', value=8, raw_value=8, offset=3)})
        primitive = SimpleNamespace(cu=unit, offset=3, tag='DW_TAG_base_type', has_children=False,
                                    attributes={'DW_AT_byte_size': SimpleNamespace(form='DW_FORM_data1', value=4, raw_value=4, offset=4)})
        terminator = SimpleNamespace(cu=unit, tag=None)
        cls.iter_children = Mock(return_value=iter([member]))
        member.get_DIE_from_attribute = Mock(return_value=primitive)
        unit.get_top_DIE = Mock(return_value=root)
        unit.iter_DIEs = Mock(return_value=iter([root, cls, member, terminator, primitive, terminator]))
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report)

        self.assertEqual([records.key(member)], report['selectedRecords'][0]['children'])
        node = report['graph'][records.key(member)]
        self.assertEqual(records.key(cls), node['parent'])
        self.assertEqual(records.key(primitive), node['references']['DW_AT_type'])
        self.assertEqual({'form': 'DW_FORM_data1', 'value': 8, 'rawValue': 8, 'offset': 3},
                         node['attributes']['DW_AT_data_member_location'])
        self.assertFalse(report['locationExpressionsEvaluated'])

    def test_matching_definitions_in_distinct_units_remain_separate(self):
        units = []
        for offset in [0, 100]:
            unit = SimpleNamespace(cu_offset=offset, size=100, header={'version': 5})
            root = SimpleNamespace(cu=unit, offset=offset, tag='DW_TAG_compile_unit', has_children=True,
                                   attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=offset)})
            cls = SimpleNamespace(cu=unit, offset=offset + 1, tag='DW_TAG_class_type', has_children=False,
                                  attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'CompileTask', raw_value=b'CompileTask', offset=offset + 1)},
                                  iter_children=Mock(return_value=iter([])))
            unit.get_top_DIE = Mock(return_value=root)
            unit.iter_DIEs = Mock(return_value=iter([root, cls, SimpleNamespace(cu=unit, tag=None)]))
            units.append(unit)
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter(units))), report)

        self.assertEqual(2, len(report['selectedRecords']))
        self.assertNotEqual(report['selectedRecords'][0]['die'], report['selectedRecords'][1]['die'])
        self.assertEqual(2, len(report['requestedUnitCoverage']['compileTask.cpp']))

    def test_unresolved_name_references_remain_explicit(self):
        unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
        root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                               attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=0)})
        die = SimpleNamespace(cu=unit, offset=1, tag='DW_TAG_subprogram', has_children=False,
                              attributes={'DW_AT_abstract_origin': SimpleNamespace(form='DW_FORM_ref4', value=3, raw_value=3, offset=1)},
                              get_DIE_from_attribute=Mock(side_effect=LookupError('missing origin')))
        unit.get_top_DIE = Mock(return_value=root)
        unit.iter_DIEs = Mock(return_value=iter([root, die, SimpleNamespace(cu=unit, tag=None)]))
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report)

        self.assertEqual('LookupError', report['nameErrors'][0]['error']['type'])
        self.assertEqual([], report['selectedRecords'])

    def test_unresolved_type_references_do_not_discard_selected_records(self):
        unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
        root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                               attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=0)})
        cls = SimpleNamespace(cu=unit, offset=1, tag='DW_TAG_class_type', has_children=True,
                              attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'CompileTask', raw_value=b'CompileTask', offset=1)})
        member = SimpleNamespace(cu=unit, offset=2, tag='DW_TAG_member', has_children=False,
                                 attributes={'DW_AT_type': SimpleNamespace(form='DW_FORM_ref4', value=3, raw_value=3, offset=2)},
                                 get_DIE_from_attribute=Mock(side_effect=NotImplementedError('unsupported type reference')))
        cls.iter_children = Mock(return_value=iter([member]))
        unit.get_top_DIE = Mock(return_value=root)
        unit.iter_DIEs = Mock(return_value=iter([root, cls, member, SimpleNamespace(cu=unit, tag=None), SimpleNamespace(cu=unit, tag=None)]))
        report = {}

        records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report)

        self.assertEqual('NotImplementedError', report['graph'][records.key(member)]['referenceErrors']['DW_AT_type']['type'])
        self.assertEqual([records.key(member)], report['selectedRecords'][0]['children'])

    def test_inventory_resource_bounds_fail_without_claiming_completed_traversal(self):
        limits = ['MAX_UNITS', 'MAX_DIES', 'MAX_GRAPH', 'MAX_SELECTED', 'MAX_CHILDREN', 'MAX_DEPTH', 'MAX_RETAINED_BYTES']
        for selection, limit in ((mode, limit) for mode in ['compiler', 'task-lifecycle'] for limit in limits):
            with self.subTest(selection=selection, limit=limit):
                unit = SimpleNamespace(cu_offset=0, size=100, header={'version': 5})
                root = SimpleNamespace(cu=unit, offset=0, tag='DW_TAG_compile_unit', has_children=True,
                                       attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'compileTask.cpp', raw_value=b'compileTask.cpp', offset=0)})
                cls = SimpleNamespace(cu=unit, offset=1, tag='DW_TAG_class_type', has_children=True,
                                      attributes={'DW_AT_name': SimpleNamespace(form='DW_FORM_string', value=b'CompileTask', raw_value=b'CompileTask', offset=1)})
                member = SimpleNamespace(cu=unit, offset=2, tag='DW_TAG_member', has_children=False, attributes={})
                cls.iter_children = Mock(return_value=iter([member]))
                unit.get_top_DIE = Mock(return_value=root)
                unit.iter_DIEs = Mock(return_value=iter([root, cls, member, SimpleNamespace(cu=unit, tag=None), SimpleNamespace(cu=unit, tag=None)]))
                report = {}

                with patch.object(records, limit, 0), self.assertRaises(ValueError):
                    records.inventory(SimpleNamespace(iter_CUs=Mock(return_value=iter([unit]))), report,
                                      selection=selection)

                self.assertFalse(report['selectedUnitTraversalCompleted'])

    def test_wrong_library_keeps_a_failure_report_before_download_or_provider_import(self):
        with tempfile.TemporaryDirectory() as directory:
            library, output = Path(directory) / 'library', Path(directory) / 'report.json'
            library.write_bytes(b'wrong library')
            with patch.object(records.sys, 'argv', ['reader', str(library), str(output)]), \
                    patch.object(records.binding, 'download') as download, patch.object(records.provider, 'load') as load, \
                    self.assertRaises(ValueError):
                records.main()

            download.assert_not_called()
            load.assert_not_called()
            report = json.loads(output.read_text())
            for key in ['completed', 'acceptedSavingsCredit', 'actualFieldLayoutProven',
                        'actualTargetCallingConventionProven', 'actualControlFlowCoverageProven', 'rootCensusProven']:
                self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
