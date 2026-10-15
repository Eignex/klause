"""Retain source headers and decoded range/location records without evaluating expressions."""
from contextlib import contextmanager
from collections.abc import Mapping
import hashlib
import io
import json
from pathlib import Path

import read_native_vm_dwarf_records as records

MAX_PARSE_BYTES = 256 * 1024
MAX_PARSE_OPERATIONS = 65536
MAX_ENTRIES = 4096
MAX_CONTEXT_BYTES = 8 * 1024 * 1024
SECTIONS = ('debug_line_sec', 'debug_str_sec', 'debug_line_str_sec', 'debug_addr_sec',
            'debug_loc_sec', 'debug_loclists_sec', 'debug_ranges_sec', 'debug_rnglists_sec')


class ContextLimit(ValueError):
    pass


class SectionStream:
    def __init__(self, stream, size, budget):
        self.stream, self.size, self.budget = stream, size, budget

    def charge(self):
        self.budget['operations'] += 1
        if self.budget['operations'] > MAX_PARSE_OPERATIONS:
            self.budget['exceeded'] = True
            raise ContextLimit('DWARF context parser operation bound exceeded')

    def tell(self):
        return self.stream.tell()

    def seek(self, offset, whence=io.SEEK_SET):
        self.charge()
        target = offset if whence == io.SEEK_SET else self.tell() + offset if whence == io.SEEK_CUR else \
            self.size + offset if whence == io.SEEK_END else -1
        if type(target) is not int or not 0 <= target <= self.size:
            raise ValueError('DWARF context seek outside section')
        return self.stream.seek(target)

    def read(self, size=-1):
        self.charge()
        if type(size) is not int or size < 0:
            self.budget['exceeded'] = True
            raise ContextLimit('Unbounded DWARF context read declined')
        if self.budget['bytes'] + size > MAX_PARSE_BYTES:
            self.budget['exceeded'] = True
            raise ContextLimit('DWARF context parser byte bound exceeded')
        if not 0 <= self.tell() <= self.size or size > self.size - self.tell():
            raise ValueError('DWARF context read outside section')
        self.budget['bytes'] += size
        return self.stream.read(size)


@contextmanager
def bounded_sections(dwarf):
    originals, positions = {}, {}
    budget = {'bytes': 0, 'operations': 0}
    try:
        for name in SECTIONS:
            section = getattr(dwarf, name, None)
            if section is not None:
                originals[name] = section
                positions.setdefault(id(section.stream), (section.stream, section.stream.tell()))
                setattr(dwarf, name, section._replace(stream=SectionStream(section.stream, section.size, budget)))
        yield budget
    finally:
        for name, section in originals.items():
            setattr(dwarf, name, section)
        for stream, position in positions.values():
            stream.seek(position)


def serial(raw, depth=0):
    if depth > 8:
        raise ContextLimit('DWARF context nesting exceeds bounds')
    if isinstance(raw, Mapping):
        if len(raw) > MAX_ENTRIES:
            raise ContextLimit('DWARF context mapping exceeds bounds')
        internal = {'directory_entry_format_parser', 'file_name_entry_format_parser'}
        return {str(k): serial(v, depth + 1) for k, v in raw.items()
                if not str(k).startswith('_') and k not in internal}
    if isinstance(raw, (list, tuple)) and not isinstance(raw, bytes):
        if len(raw) > MAX_ENTRIES:
            raise ContextLimit('DWARF context entries exceed bounds')
        return [serial(v, depth + 1) for v in raw]
    return records.value(raw)


def decoded_entries(entries):
    if not isinstance(entries, list) or len(entries) > MAX_ENTRIES:
        raise ContextLimit('Decoded DWARF context entries exceed bounds')
    result = []
    for entry in entries:
        if not hasattr(entry, '_asdict'):
            raise ValueError('Unsupported decoded DWARF context entry')
        result.append({'kind': type(entry).__name__, 'fields': serial(entry._asdict())})
    return result


class DwarfContext:
    def __init__(self, dwarf):
        self.dwarf = dwarf
        self.units = {}
        self.retained_bytes = 0
        self.record_retainer = None

    def bind_budget(self, retain):
        self.record_retainer = retain

    @staticmethod
    def source_sha256():
        return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

    @staticmethod
    def scope():
        return {'lineHeadersOnly': True, 'lineProgramExecuted': False,
                'rangeAndLocationRecordsDecoded': True, 'locationExpressionsEvaluated': False,
                'actualSourceFileBindingProven': False, 'actualFieldLayoutProven': False,
                'actualTargetCallingConventionProven': False, 'actualControlFlowCoverageProven': False,
                'rootCensusProven': False, 'codeSizeProven': False, 'acceptedSavingsCredit': False}

    def retain(self, row):
        self.retained_bytes += len(json.dumps(row).encode())
        if self.retained_bytes > MAX_CONTEXT_BYTES:
            raise ContextLimit('DWARF context retained byte bound exceeded')
        return self.record_retainer(row) if self.record_retainer is not None else row

    def attempt(self, action):
        budget = None
        try:
            with bounded_sections(self.dwarf) as budget:
                result = action()
            return self.retain({'status': 'decoded', 'data': result, 'parserBudget': dict(budget)})
        except ContextLimit:
            raise
        except Exception as error:
            if budget is not None and budget.get('exceeded'):
                raise ContextLimit('DWARF provider wrapped a context resource failure') from error
            return self.retain({'status': 'unresolved', 'error': {'type': type(error).__name__, 'message': str(error)}})

    def unit(self, unit):
        top = unit.get_top_DIE()
        identity = records.key(top)
        if identity in self.units:
            return self.units[identity]
        if len(self.units) >= records.MAX_UNITS:
            raise ContextLimit('DWARF context unit count exceeds bounds')
        row = {'topDie': identity, 'topOffset': top.offset, 'unitOffset': unit.cu_offset,
               'topAttributes': records.attributes(top), 'version': unit.header['version'],
               'lineProgramExecuted': False, 'dynamicFileDefinitionsIncluded': False}

        def header():
            program = self.dwarf.line_program_for_CU(unit)
            if program is None:
                return {'present': False}
            version = program.header['version']
            if version not in (2, 3, 4, 5):
                raise ValueError('Unsupported line-header version')
            formats = [*(program.header.get('directory_entry_format') or []),
                       *(program.header.get('file_name_entry_format') or [])]
            allowed = {'DW_FORM_string', 'DW_FORM_line_strp', 'DW_FORM_strp', 'DW_FORM_data1',
                       'DW_FORM_data2', 'DW_FORM_data4', 'DW_FORM_data8', 'DW_FORM_data16',
                       'DW_FORM_udata', 'DW_FORM_block', 'DW_FORM_block1', 'DW_FORM_block2', 'DW_FORM_block4'}
            if any(field['form'] not in allowed for field in formats):
                raise ValueError('Unsupported or supplementary line-header form')
            return {'present': True, 'header': serial(program.header),
                    'fileIndexBase': 0 if version == 5 else 1,
                    'directoryIndexBase': 0 if version == 5 else 1}

        row['lineHeader'] = self.attempt(header)
        self.units[identity] = self.retain(row)
        return row

    def file_reference(self, unit_context, attribute):
        raw = {'attribute': {'form': attribute.form, 'value': records.value(attribute.value),
                             'rawValue': records.value(attribute.raw_value), 'offset': attribute.offset}}
        data = unit_context['lineHeader']
        if data['status'] != 'decoded' or not data['data']['present']:
            return {**raw, 'status': 'line-header-unavailable'}
        data = data['data']
        index = attribute.value
        if type(index) is not int or attribute.form not in {
                'DW_FORM_data1', 'DW_FORM_data2', 'DW_FORM_data4', 'DW_FORM_data8', 'DW_FORM_udata'}:
            return {**raw, 'status': 'unsupported-file-index'}
        files = data['header'].get('file_entry') or []
        position = index - data['fileIndexBase']
        if not 0 <= position < len(files):
            return {**raw, 'status': 'outside-header-file-table', 'dynamicFileDefinitionsIncluded': False}
        file = files[position]
        directory = file.get('dir_index')
        directories = data['header'].get('include_directory') or []
        directory_position = directory - data['directoryIndexBase'] if type(directory) is int else -1
        if data['directoryIndexBase'] == 1 and type(directory) is int and directory == 0:
            directory_context = {'status': 'compilation-directory',
                                 'attribute': unit_context['topAttributes'].get('DW_AT_comp_dir')}
        elif 0 <= directory_position < len(directories):
            directory_context = {'status': 'header-directory', 'entry': directories[directory_position]}
        else:
            directory_context = {'status': 'unresolved-directory-index', 'index': directory}
        status = 'ambiguous-v5-zero-file-index' if data['fileIndexBase'] == 0 and index == 0 else 'header-file-entry'
        return {**raw, 'status': status, 'file': file, 'directory': directory_context,
                'sourcePathBindingProven': False}

    def die(self, die):
        unit_context = self.unit(die.cu)
        row = {'unitTopDie': unit_context['topDie'], 'fileReferences': {},
               'ranges': {'status': 'absent'}, 'locations': {},
               'locationExpressionsEvaluated': False, 'entryCallingConventionProven': False}
        for name in ('DW_AT_decl_file', 'DW_AT_call_file'):
            if name in die.attributes:
                row['fileReferences'][name] = self.file_reference(unit_context, die.attributes[name])
        if 'DW_AT_ranges' in die.attributes:
            def ranges():
                attribute = die.attributes['DW_AT_ranges']
                if attribute.form not in {'DW_FORM_sec_offset', 'DW_FORM_rnglistx', 'DW_FORM_data4', 'DW_FORM_data8'}:
                    raise ValueError('Unsupported range-list form')
                lists = self.dwarf.range_lists()
                if lists is None:
                    raise ValueError('Range-list section absent')
                return decoded_entries(lists.get_range_list_at_offset(attribute.value, die.cu))
            row['ranges'] = self.attempt(ranges)
        for name in ('DW_AT_location', 'DW_AT_frame_base', 'DW_AT_return_addr'):
            if name not in die.attributes:
                continue
            def locations():
                from elftools.dwarf.locationlists import LocationParser
                parser = LocationParser(self.dwarf.location_lists())
                decoded = parser.parse_from_attribute(die.attributes[name], die.cu.header['version'], die)
                return decoded_entries(decoded if isinstance(decoded, list) else [decoded])
            row['locations'][name] = self.attempt(locations)
        return self.retain(row)


if __name__ == '__main__':
    records.main(DwarfContext)
