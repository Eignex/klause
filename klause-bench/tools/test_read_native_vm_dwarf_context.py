import io
from pathlib import Path
import sys
import tempfile
from collections import namedtuple
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import read_native_vm_dwarf_context as context


class ReadNativeVmDwarfContextTest(unittest.TestCase):
    def test_section_offsets_remain_absolute_across_relative_and_end_seeks(self):
        budget = {'bytes': 0, 'operations': 0}
        stream = context.SectionStream(io.BytesIO(b'abcdefgh'), 8, budget)

        stream.seek(3)
        first = stream.read(2)
        stream.seek(-1, io.SEEK_CUR)
        second = stream.read(1)
        stream.seek(-1, io.SEEK_END)
        last = stream.read(1)

        self.assertEqual((b'de', b'e', b'h'), (first, second, last))
        self.assertEqual(8, stream.tell())

    def test_section_bounds_decline_unbounded_negative_and_overflow_reads(self):
        for size in [-1, 9, True]:
            with self.subTest(size=size):
                raw = io.BytesIO(b'abcdefgh')
                stream = context.SectionStream(raw, 8, {'bytes': 0, 'operations': 0})

                with self.assertRaises(ValueError):
                    stream.read(size)

                self.assertEqual(0, raw.tell())

    def test_section_bounds_decline_seeks_outside_the_original_section(self):
        for offset, whence in [(-1, io.SEEK_SET), (9, io.SEEK_SET), (-9, io.SEEK_END), (0, 7)]:
            with self.subTest(offset=offset, whence=whence):
                stream = context.SectionStream(io.BytesIO(b'abcdefgh'), 8, {'bytes': 0, 'operations': 0})

                with self.assertRaises(ValueError):
                    stream.seek(offset, whence)

                self.assertEqual(0, stream.tell())

    def test_parser_byte_and_operation_limits_apply_before_underlying_access(self):
        for bound in ['MAX_PARSE_BYTES', 'MAX_PARSE_OPERATIONS']:
            with self.subTest(bound=bound), patch.object(context, bound, 0):
                raw = Mock()
                stream = context.SectionStream(raw, 8, {'bytes': 0, 'operations': 0})

                with self.assertRaises(context.ContextLimit):
                    stream.read(1)

                raw.read.assert_not_called()

    def test_guarded_sections_share_a_budget_and_restore_streams_after_failure(self):
        descriptor = namedtuple('Section', 'stream size')
        first, second = io.BytesIO(b'abcdefgh'), io.BytesIO(b'12345678')
        first.seek(2)
        dwarf = SimpleNamespace(debug_line_sec=descriptor(first, 8), debug_str_sec=descriptor(second, 8))
        original = dwarf.debug_line_sec

        with self.assertRaises(RuntimeError):
            with context.bounded_sections(dwarf) as budget:
                dwarf.debug_line_sec.stream.read(2)
                dwarf.debug_str_sec.stream.read(3)
                self.assertEqual(5, budget['bytes'])
                raise RuntimeError('failed parser')

        self.assertIs(original, dwarf.debug_line_sec)
        self.assertEqual((2, 0), (first.tell(), second.tell()))

    def test_serialized_headers_retain_names_and_decline_oversized_nested_values(self):
        self.assertEqual({'path': {'bytesHex': '66696c65'}, 'index': 0},
                         context.serial({'path': b'file', 'index': 0, '_io': object(),
                                         'directory_entry_format_parser': object(),
                                         'file_name_entry_format_parser': object()}))
        for raw in [[0] * (context.MAX_ENTRIES + 1), object()]:
            with self.subTest(kind=type(raw).__name__), self.assertRaises(ValueError):
                context.serial(raw)

    def test_decoded_records_preserve_base_relative_absolute_and_expression_fields(self):
        base = namedtuple('BaseAddressEntry', 'entry_offset base_address')(7, 100)
        relative = namedtuple('RangeEntry', 'begin_offset end_offset is_absolute')(1, 9, False)
        location = namedtuple('LocationEntry', 'begin_offset end_offset loc_expr is_absolute')(100, 110, [0x55], True)

        result = context.decoded_entries([base, relative, location])

        self.assertEqual({'kind': 'BaseAddressEntry', 'fields': {'entry_offset': 7, 'base_address': 100}}, result[0])
        self.assertFalse(result[1]['fields']['is_absolute'])
        self.assertEqual([0x55], result[2]['fields']['loc_expr'])

    def test_line_headers_keep_unit_root_and_version_specific_index_conventions(self):
        for version, base in [(4, 1), (5, 0)]:
            with self.subTest(version=version):
                top = SimpleNamespace(cu=SimpleNamespace(cu_offset=10), offset=21, attributes={})
                unit = SimpleNamespace(cu_offset=10, header={'version': version}, get_top_DIE=Mock(return_value=top))
                program = SimpleNamespace(header={'version': version, 'directory_entry_format': None,
                                                  'file_name_entry_format': None, 'include_directory': [b'src'],
                                                  'file_entry': [{'name': b'compileTask.cpp', 'dir_index': base}]},
                                          get_entries=Mock())
                reader = context.DwarfContext(SimpleNamespace(line_program_for_CU=Mock(return_value=program)))

                result = reader.unit(unit)

                self.assertEqual(21, result['topOffset'])
                self.assertEqual(base, result['lineHeader']['data']['fileIndexBase'])
                self.assertFalse(result['dynamicFileDefinitionsIncluded'])
                program.get_entries.assert_not_called()
                self.assertIn(result['topDie'], reader.units)

    def test_supplementary_line_header_forms_remain_unresolved(self):
        top = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=11, attributes={})
        unit = SimpleNamespace(cu_offset=0, header={'version': 5}, get_top_DIE=Mock(return_value=top))
        program = SimpleNamespace(header={'version': 5, 'file_name_entry_format': [{'form': 'DW_FORM_strp_sup'}]})
        reader = context.DwarfContext(SimpleNamespace(line_program_for_CU=Mock(return_value=program)))

        result = reader.unit(unit)

        self.assertEqual('unresolved', result['lineHeader']['status'])
        self.assertEqual('ValueError', result['lineHeader']['error']['type'])

    def test_file_references_preserve_v4_compilation_directory_and_v5_zero_ambiguity(self):
        for version, index, expected in [(4, 1, 'header-file-entry'), (5, 0, 'ambiguous-v5-zero-file-index')]:
            with self.subTest(version=version):
                top = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=11, attributes={})
                unit = SimpleNamespace(cu_offset=0, header={'version': version}, get_top_DIE=Mock(return_value=top))
                program = SimpleNamespace(header={'version': version, 'include_directory': [b'/src'],
                                                  'file_entry': [{'name': b'file.cpp', 'dir_index': 0}]})
                reader = context.DwarfContext(SimpleNamespace(line_program_for_CU=Mock(return_value=program)))
                attr = SimpleNamespace(form='DW_FORM_data1', value=index, raw_value=index, offset=3)

                result = reader.file_reference(reader.unit(unit), attr)

                self.assertEqual(expected, result['status'])
                self.assertEqual({'bytesHex': b'file.cpp'.hex()}, result['file']['name'])
                self.assertEqual('compilation-directory' if version == 4 else 'header-directory', result['directory']['status'])
                self.assertFalse(result['sourcePathBindingProven'])

    def test_missing_dynamic_and_unsupported_file_indexes_are_explicit(self):
        reader = context.DwarfContext(SimpleNamespace())
        header = {'lineHeader': {'status': 'decoded', 'data': {'present': True, 'fileIndexBase': 1,
                   'directoryIndexBase': 1, 'header': {'file_entry': []}}}}
        for form, value, expected in [('DW_FORM_data1', 2, 'outside-header-file-table'),
                                       ('DW_FORM_exprloc', [1], 'unsupported-file-index')]:
            with self.subTest(form=form):
                attr = SimpleNamespace(form=form, value=value, raw_value=value, offset=1)

                result = reader.file_reference(header, attr)

                self.assertEqual(expected, result['status'])

    def test_ranges_receive_the_original_unit_and_keep_parser_failures(self):
        for failure in [False, True]:
            with self.subTest(failure=failure):
                top = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=11, attributes={})
                unit = SimpleNamespace(cu_offset=0, header={'version': 5}, get_top_DIE=Mock(return_value=top))
                lists = Mock()
                lists.get_range_list_at_offset = Mock(side_effect=ValueError('unknown range') if failure else None, return_value=[])
                dwarf = SimpleNamespace(line_program_for_CU=Mock(return_value=None), range_lists=Mock(return_value=lists))
                die = SimpleNamespace(cu=unit, attributes={'DW_AT_ranges': SimpleNamespace(form='DW_FORM_rnglistx', value=23)})

                result = context.DwarfContext(dwarf).die(die)

                lists.get_range_list_at_offset.assert_called_once_with(23, unit)
                self.assertEqual('unresolved' if failure else 'decoded', result['ranges']['status'])
                self.assertFalse(result['entryCallingConventionProven'])

    def test_locations_receive_die_context_and_keep_unevaluated_expression_bytes(self):
        top = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=11, attributes={})
        unit = SimpleNamespace(cu_offset=0, header={'version': 5}, get_top_DIE=Mock(return_value=top))
        attr = SimpleNamespace(form='DW_FORM_exprloc', value=[0x55])
        die = SimpleNamespace(cu=unit, attributes={'DW_AT_location': attr})
        dwarf = SimpleNamespace(line_program_for_CU=Mock(return_value=None), location_lists=Mock(return_value=None))
        expression = namedtuple('LocationExpr', 'loc_expr')([0x55])
        parser = Mock(parse_from_attribute=Mock(return_value=expression))
        module = SimpleNamespace(LocationParser=Mock(return_value=parser))

        with patch.dict(sys.modules, {'elftools.dwarf.locationlists': module}):
            result = context.DwarfContext(dwarf).die(die)

        parser.parse_from_attribute.assert_called_once_with(attr, 5, die)
        self.assertEqual([0x55], result['locations']['DW_AT_location']['data'][0]['fields']['loc_expr'])
        self.assertFalse(result['locationExpressionsEvaluated'])

    def test_resource_failures_escape_without_a_decoded_or_unresolved_success(self):
        reader = context.DwarfContext(SimpleNamespace())
        with patch.object(context, 'MAX_CONTEXT_BYTES', 0):
            with self.assertRaises(context.ContextLimit):
                reader.attempt(lambda: [])
        with self.assertRaises(context.ContextLimit):
            reader.attempt(Mock(side_effect=context.ContextLimit('too much input')))
        reader.bind_budget(Mock(side_effect=context.ContextLimit('containing inventory full')))
        with self.assertRaises(context.ContextLimit):
            reader.attempt(lambda: [])

    def test_provider_wrapping_a_resource_failure_cannot_turn_it_into_an_unresolved_success(self):
        descriptor = namedtuple('Section', 'stream size')
        dwarf = SimpleNamespace(debug_line_sec=descriptor(io.BytesIO(b'abcd'), 4))
        reader = context.DwarfContext(dwarf)

        def wrapped_failure():
            try:
                dwarf.debug_line_sec.stream.read(1)
            except context.ContextLimit as error:
                raise ValueError('provider parse failed') from error

        with patch.object(context, 'MAX_PARSE_BYTES', 0):
            with self.assertRaises(context.ContextLimit):
                reader.attempt(wrapped_failure)

    def test_all_context_proof_claims_remain_false(self):
        scope = context.DwarfContext.scope()

        for key, value in scope.items():
            if key.endswith('Proven') or key in ['locationExpressionsEvaluated', 'acceptedSavingsCredit']:
                with self.subTest(claim=key):
                    self.assertFalse(value)

    def test_pinned_provider_decodes_bounded_v4_range_and_location_sections(self):
        original_path, original_bytecode = list(sys.path), sys.dont_write_bytecode
        try:
            with tempfile.TemporaryDirectory() as directory:
                provenance = {}
                context.records.provider.load(Path(directory), provenance)
                from elftools.dwarf.locationlists import LocationLists
                from elftools.dwarf.ranges import RangeLists
                from elftools.dwarf.structs import DWARFStructs
                structs = DWARFStructs(little_endian=True, dwarf_format=32, address_size=8, dwarf_version=4)
                raw_range = (10).to_bytes(8, 'little') + (20).to_bytes(8, 'little')
                raw_location = raw_range + b'\x01\x00\x55' + bytes(16)
                range_stream = context.SectionStream(io.BytesIO(raw_range + bytes(16)), 32,
                                                     {'bytes': 0, 'operations': 0})
                location_stream = context.SectionStream(io.BytesIO(raw_location), len(raw_location),
                                                        {'bytes': 0, 'operations': 0})

                ranges = RangeLists(range_stream, structs, 4, None).get_range_list_at_offset(0)
                locations = LocationLists(location_stream, structs, 4).get_location_list_at_offset(0)

                self.assertEqual(10, context.decoded_entries(ranges)[0]['fields']['begin_offset'])
                self.assertEqual([0x55], context.decoded_entries(locations)[0]['fields']['loc_expr'])
                context.records.provider.verify(Path(provenance['directory']), provenance)
        finally:
            for name in tuple(sys.modules):
                if name == 'elftools' or name.startswith('elftools.'):
                    del sys.modules[name]
            sys.path[:] = original_path
            sys.dont_write_bytecode = original_bytecode

    def test_pinned_provider_reads_line_headers_without_executing_their_programs(self):
        original_path, original_bytecode = list(sys.path), sys.dont_write_bytecode
        try:
            with tempfile.TemporaryDirectory() as directory:
                provenance = {}
                context.records.provider.load(Path(directory), provenance)
                from elftools.dwarf.dwarfinfo import DWARFInfo, DebugSectionDescriptor
                from elftools.dwarf.structs import DWARFStructs
                for version in [4, 5]:
                    with self.subTest(version=version):
                        common = bytes([1, 1, 1, 0xfb, 14, 13]) + bytes(12)
                        tables = b'src\0\0file.cpp\0\x01\x00\x00\0' if version == 4 else \
                            b'\x01\x01\x08\x01src\0\x02\x01\x08\x02\x0f\x01file.cpp\0\x00'
                        header = common + tables
                        body = version.to_bytes(2, 'little') + (b'\x08\x00' if version == 5 else b'') + \
                            len(header).to_bytes(4, 'little') + header
                        raw = len(body).to_bytes(4, 'little') + body
                        dwarf = DWARFInfo.__new__(DWARFInfo)
                        dwarf.debug_line_sec = DebugSectionDescriptor(io.BytesIO(raw), '.debug_line', 0, len(raw), 0)
                        dwarf._linetable_cache = {}
                        attr = SimpleNamespace(form='DW_FORM_sec_offset', value=0, raw_value=0, offset=1)
                        top = SimpleNamespace(cu=SimpleNamespace(cu_offset=0), offset=11, attributes={'DW_AT_stmt_list': attr})
                        unit = SimpleNamespace(cu_offset=0, header={'version': version}, get_top_DIE=Mock(return_value=top),
                                               structs=DWARFStructs(little_endian=True, dwarf_format=32,
                                                                    address_size=8, dwarf_version=version))

                        result = context.DwarfContext(dwarf).unit(unit)

                        self.assertEqual('decoded', result['lineHeader']['status'], result['lineHeader'])
                        files = result['lineHeader']['data']['header']['file_entry']
                        self.assertEqual({'bytesHex': b'file.cpp'.hex()}, files[0]['name'])
                        self.assertFalse(result['lineProgramExecuted'])
                context.records.provider.verify(Path(provenance['directory']), provenance)
        finally:
            for name in tuple(sys.modules):
                if name == 'elftools' or name.startswith('elftools.'):
                    del sys.modules[name]
            sys.path[:] = original_path
            sys.dont_write_bytecode = original_bytecode


if __name__ == '__main__':
    unittest.main(verbosity=2)
