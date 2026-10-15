"""Bind a fixed pair of saved compiler entries to explicit DWARF parameter locations."""
import hashlib
import json
from pathlib import Path
import sys

import review_native_vm_dwarf_records as dwarf

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
DEBUG_SHA256 = 'b550e37e8cad30a15655109c6cd493df2306fde5e799f5ab1c13c85741bc9deb'
CONTEXT_SHA256 = 'bad70dc42cd1d7b89b15a0fb0e089f70d01098577fc96f8db158abc36059979c'
LAYOUT_SHA256 = 'af3a710b65eac8bde1895dc4bd78a33af25e7654b31befffb820e1cf3df1ffa6'
ENTRIES_SHA256 = 'b084bd021491dc839e66e01b0b3ce8543e43b205dfa783dfd68f5c44b0297812'
CONTEXT_READER = 'ba15010c801420a06249d92a0d53d0520dbb3a71ae54c690fb26756c2d14bd8d'
CONTEXT_PROVIDER = 'cae6370b725cc10414ded5a1c4fc5aa0aaca9c9a5f9c462475484a6671eeaf14'
LAYOUT_READER = 'ccc97796d8fe5772405bd3745a24587135be86a2179c0ae5f969f18197808d6e'
ENTRY_READER = '22c11b59d656a11ad2df4736ff808cf929c2b1997c0dc5f598cfe8af13555300'
QUERIES = ('invoke_compiler_on_method', 'post_compiled_method', 'total_size', 'CompileQueue', 'CompileTaskWrapper')
TARGETS = {
    'invoke_compiler_on_method': ('CompileBroker', (('task', 'CompileTask', 5),)),
    'post_compiled_method': ('nmethod', (('this', 'nmethod', 5), ('task', 'CompileTask', 4))),
}
MAX_INPUT_BYTES = 32 * 1024 * 1024
MAX_OUTPUT_BYTES = 32 * 1024 * 1024
MAX_LOCATIONS = 4096
MAX_ADDRESS = (1 << 64) - 1
ADDRESS_FORMS = {'DW_FORM_addr', 'DW_FORM_addrx', 'DW_FORM_addrx1', 'DW_FORM_addrx2',
                 'DW_FORM_addrx3', 'DW_FORM_addrx4', 'DW_FORM_GNU_addr_index'}


class AbiLimit(ValueError):
    pass


def linkage(query):
    owner = TARGETS[query][0]
    return '_ZN' + str(len(owner)) + owner + str(len(query)) + query + 'EP11CompileTask'


def address(node, name):
    attr = node['attributes'].get(name, {})
    value = attr.get('value')
    return value if attr.get('form') in ADDRESS_FORMS and type(value) is int and 0 <= value <= MAX_ADDRESS else None


def inherited(identity, attribute, graph, visited=None, remaining=None):
    visited = set() if visited is None else visited
    remaining = [64] if remaining is None else remaining
    remaining[0] -= 1
    if identity in visited or len(visited) >= dwarf.MAX_CHAIN or remaining[0] < 0:
        raise ValueError('Cyclic or oversized parameter origin chain')
    visited = visited | {identity}
    node = graph.get(identity)
    if node is None or node['referenceErrors']:
        raise ValueError('Unresolved parameter origin record')
    if attribute in node['references']:
        return [(identity, node['references'][attribute])]
    if attribute in node['attributes']:
        if attribute in {'DW_AT_type', 'DW_AT_object_pointer'}:
            raise ValueError('Reference attribute has no resolved target')
        return [(identity, node['attributes'][attribute])]
    rows = []
    for name in ['DW_AT_abstract_origin', 'DW_AT_specification']:
        if name in node['references']:
            rows.extend(inherited(node['references'][name], attribute, graph, visited, remaining))
    return rows


def text_attribute(node, name):
    attr = node['attributes'].get(name, {})
    value = attr.get('value')
    if not isinstance(value, dict) or set(value) != {'bytesHex'}:
        return None
    try:
        return bytes.fromhex(value['bytesHex']).decode('ascii')
    except (ValueError, UnicodeError):
        return None


def register(expression):
    if not isinstance(expression, list) or not 0 < len(expression) <= 16 or \
            any(type(byte) is not int or not 0 <= byte <= 255 for byte in expression):
        return None
    if len(expression) == 1 and 0x50 <= expression[0] <= 0x6f:
        return expression[0] - 0x50
    if expression[0] != 0x90:
        return None
    value = 0
    for index, byte in enumerate(expression[1:]):
        if index >= 5:
            return None
        value |= (byte & 0x7f) << (index * 7)
        if not byte & 0x80:
            return value if index + 2 == len(expression) and value < 32 and \
                (index == 0 or byte != 0) else None
    return None


def locations_at_entry(parameter, pc, unit):
    scope = dwarf.scalar(parameter, 'DW_AT_start_scope')
    if 'DW_AT_start_scope' in parameter['attributes'] and scope != 0:
        raise ValueError('Parameter start scope does not establish entry coverage')
    location = parameter.get('context', {}).get('locations', {}).get('DW_AT_location', {})
    if location.get('status') != 'decoded' or not isinstance(location.get('data'), list) or \
            len(location['data']) > MAX_LOCATIONS:
        raise ValueError('Concrete parameter location is unresolved')
    rows = location['data']
    if len(rows) == 1 and rows[0]['kind'] == 'LocationExpr':
        return [{'kind': 'LocationExpr', 'expression': rows[0]['fields']['loc_expr']}]
    base = address({'attributes': unit.get('topAttributes', {})}, 'DW_AT_low_pc')
    matches, defaults = [], []
    for row in rows:
        fields = row['fields']
        if row['kind'] == 'BaseAddressEntry':
            base = fields['base_address']
            if type(base) is not int or not 0 <= base <= MAX_ADDRESS:
                raise ValueError('Unsupported location-list base address')
            continue
        if row['kind'] != 'LocationEntry':
            raise ValueError('Unsupported location-list record kind')
        start, end = fields['begin_offset'], fields['end_offset']
        if type(start) is not int or type(end) is not int or type(fields['is_absolute']) is not bool:
            raise ValueError('Invalid location-list range')
        result = {'kind': row['kind'], 'expression': fields['loc_expr'], 'record': row}
        if start == end == -1 and fields['is_absolute']:
            defaults.append(result)
            continue
        if start < 0 or end < 0:
            raise ValueError('Negative location-list offsets are unsupported')
        if not fields['is_absolute']:
            if base is None:
                raise ValueError('Relative location range has no compilation-unit base')
            start, end = base + start, base + end
        if not 0 <= start <= end <= MAX_ADDRESS:
            raise ValueError('Location range is invalid or overflows')
        if start <= pc < end:
            matches.append(result)
    if len(defaults) > 1:
        raise ValueError('Multiple default location descriptions are ambiguous')
    return matches if matches else defaults


def pointer_type(identity, graph, expected, task_definitions):
    nodes = inherited(identity, 'DW_AT_type', graph)
    if not nodes:
        raise ValueError('Parameter has no resolved storage type')
    result = []
    for origin, target in nodes:
        storage = dwarf.storage(target, graph)
        if storage['status'] != 'constant-storage-candidate' or storage['tag'] != 'DW_TAG_pointer_type' or \
                storage['byteSize'] != 8:
            raise ValueError('Parameter is not an explicitly sized target pointer')
        pointer = graph[storage['chain'][-1]]
        target = pointer['references'].get('DW_AT_type')
        chain = []
        while target is not None and target not in chain and len(chain) < dwarf.MAX_CHAIN:
            chain.append(target)
            node = graph.get(target)
            if node is None or node['referenceErrors']:
                raise ValueError('Pointer target type is unresolved')
            if node['tag'] not in dwarf.QUALIFIERS:
                break
            target = node['references'].get('DW_AT_type')
        else:
            raise ValueError('Pointer target chain is incomplete or cyclic')
        if node['tag'] not in {'DW_TAG_class_type', 'DW_TAG_structure_type'} or dwarf.text_name(node) != expected or \
                expected == 'CompileTask' and target not in task_definitions:
            raise ValueError('Pointer target does not bind the expected compiled type')
        result.append({'origin': origin, 'storage': storage, 'targetChain': chain, 'target': target})
    return result


def function_entry(node, pc, symbol_size):
    start = address(node, 'DW_AT_low_pc')
    explicit = address(node, 'DW_AT_entry_pc')
    if start != pc or 'DW_AT_entry_pc' in node['attributes'] and explicit != pc:
        raise ValueError('Concrete DWARF entry does not equal the physical symbol entry')
    high = node['attributes'].get('DW_AT_high_pc', {})
    if high.get('form') in ADDRESS_FORMS:
        end = address(node, 'DW_AT_high_pc')
    else:
        length = dwarf.scalar(node, 'DW_AT_high_pc')
        end = pc + length if length is not None else None
    if end is None or not pc < end <= MAX_ADDRESS or pc + symbol_size > end:
        raise ValueError('Physical symbol span is outside the concrete DWARF range')
    if 'DW_AT_ranges' in node['attributes']:
        raise ValueError('Split function ranges require separate entry coverage proof')
    convention = dwarf.scalar(node, 'DW_AT_calling_convention')
    if 'DW_AT_calling_convention' in node['attributes'] and convention != 1:
        raise ValueError('Non-normal or unresolved calling convention')
    if node['attributes'].get('DW_AT_declaration', {}).get('value') not in (None, False, 0):
        raise ValueError('A declaration does not establish a physical entry')
    return {'start': pc, 'end': end, 'symbolBytes': symbol_size}


def parameter_lineage(identity, graph):
    pending, seen = [(identity, set())], set()
    while pending:
        current, path = pending.pop()
        if current in path:
            raise ValueError('Parameter lineage contains a cycle')
        if current in seen:
            continue
        if len(seen) >= 64:
            raise ValueError('Parameter lineage exceeds bounds')
        seen.add(current)
        node = graph.get(current)
        if node is None or node['referenceErrors']:
            raise ValueError('Parameter lineage contains unresolved references')
        pending.extend((node['references'][key], path | {current})
                       for key in ['DW_AT_abstract_origin', 'DW_AT_specification'] if key in node['references'])
    return seen


def parameter_artificial(identity, graph):
    rows = inherited(identity, 'DW_AT_artificial', graph)
    if any(attr.get('form') not in {'DW_FORM_flag', 'DW_FORM_flag_present'} or
           type(attr.get('value')) not in (bool, int) or attr['value'] not in (True, False, 0, 1) for _, attr in rows):
        raise ValueError('Parameter artificial flag is unsupported')
    values = {bool(attr['value']) for _, attr in rows}
    if len(values) > 1:
        raise ValueError('Parameter artificial flags conflict')
    return values.pop() if values else False


def bind_function(query, selected, source, symbol, task_definitions):
    graph = source['dwarf']['graph']
    identity = selected['die']
    node = graph[identity]
    pc = symbol['value']
    result = {'die': identity, 'selectedRecord': selected, 'record': node,
              'status': 'unproved', 'parameterBindings': [], 'actualTargetCallingConventionProven': False}
    try:
        values = [*inherited(identity, 'DW_AT_linkage_name', graph),
                  *inherited(identity, 'DW_AT_MIPS_linkage_name', graph)]
        if not values or any(text_attribute({'attributes': {'value': attr}}, 'value') != linkage(query)
                             for _, attr in values):
            raise ValueError('DWARF linkage does not equal the planned source signature')
        result['functionRange'] = function_entry(node, pc, symbol['size'])
        children = [graph[child] for child in selected['children']]
        if any(child['tag'] == 'DW_TAG_unspecified_parameters' for child in children):
            raise ValueError('Variadic parameters are outside the fixed signature')
        parameters = [child for child in selected['children'] if graph[child]['tag'] == 'DW_TAG_formal_parameter']
        expected = TARGETS[query][1]
        if len(parameters) != len(expected):
            raise ValueError('Concrete parameter inventory differs from the pinned source signature')
        objects = [value for _, value in inherited(identity, 'DW_AT_object_pointer', graph)]
        if query == 'invoke_compiler_on_method' and objects:
            raise ValueError('Static broker entry has an unexpected object parameter')
        unit = source['contextUnits'][node['context']['unitTopDie']]
        if unit['unitOffset'] != node['unitOffset'] or unit['topDie'] != node['context']['unitTopDie']:
            raise ValueError('Physical function has an inconsistent compilation-unit context')
        for parameter, (name, target_type, expected_register) in zip(parameters, expected):
            child = graph[parameter]
            row = {'die': parameter, 'record': child, 'expectedParameter': name,
                   'expectedDwarfRegister': expected_register}
            result['parameterBindings'].append(row)
            if child.get('parentKnownFromSelectedTraversal') is not True or child.get('parent') != identity:
                raise ValueError('Concrete parameter does not belong directly to the physical function')
            lineage = parameter_lineage(parameter, graph)
            artificial = parameter_artificial(parameter, graph)
            if artificial != (name == 'this'):
                raise ValueError('Artificial parameter does not match the pinned source signature')
            if name == 'this' and (not objects or any(value not in lineage for value in objects)):
                raise ValueError('Member object-pointer reference does not bind its concrete parameter')
            row['typeBindings'] = pointer_type(parameter, graph, target_type, task_definitions)
            descriptions = locations_at_entry(child, pc, unit)
            row['entryLocations'] = descriptions
            registers = [register(item['expression']) for item in descriptions]
            row['entryRegisters'] = registers
            if not registers or any(value != expected_register for value in registers):
                raise ValueError('Entry location does not establish the expected direct register')
        result.update(status='pinned-physical-entry-abi-proven', actualTargetCallingConventionProven=True)
    except Exception as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
    return result


def entry_span(entry):
    symbol = entry['originalSymbol']
    if entry.get('completed') is not True or entry.get('byteCoverage') != 'complete symbol span' or \
            entry.get('truncated') is not False or entry.get('decoderCoverage') != 'complete input':
        raise ValueError('Original physical entry span is incomplete')
    raw = bytes.fromhex(entry['entryHex'])
    if any(type(value) is not int for value in [symbol['size'], entry['entryBytes'], entry['decodedBytes'],
                                               entry['fileOffset']]) or entry['fileOffset'] < 0 or \
            not 0 < len(raw) == symbol['size'] == entry['entryBytes'] == entry['decodedBytes'] <= 16384 or \
            hashlib.sha256(raw).hexdigest() != entry['entrySha256']:
        raise ValueError('Original physical entry bytes do not bind their complete symbol span')
    if type(symbol['value']) is not int or not 0 <= symbol['value'] < symbol['value'] + symbol['size'] <= MAX_ADDRESS:
        raise ValueError('Original physical symbol range is invalid')
    return {'entrySha256': entry['entrySha256'], 'fileOffset': entry['fileOffset'],
            'virtualAddress': symbol['value'], 'bytes': symbol['size']}


def review(source, layout, entries, report):
    graph = source['dwarf']['graph']
    selected = source['dwarf']['selectedRecords']
    if not isinstance(graph, dict) or len(graph) > dwarf.MAX_GRAPH or not isinstance(selected, list) or \
            len(selected) > dwarf.MAX_SELECTED or any(not isinstance(node, dict) for node in graph.values()):
        raise ValueError('Saved DWARF graph is incomplete or exceeds bounds')
    task_definitions = {row['ownerDie'] for row in layout['layoutDefinitions']}
    if not task_definitions or any(row['problems'] for row in layout['layoutDefinitions']):
        raise ValueError('Saved task layout definitions are incomplete')
    retained = 0

    def retain(row):
        nonlocal retained
        retained += 1024
        for chunk in json.JSONEncoder().iterencode(row):
            retained += len(chunk.encode())
            if retained > MAX_OUTPUT_BYTES:
                raise AbiLimit('Entry ABI review records exceed output bounds')
        return row

    report.update(entries={}, targetConcordance={}, actualTargetCallingConventionProven=False,
                  privateFieldReadsAuthorized=False, originalNameErrors=source['dwarf']['nameErrors'],
                  originalFunctionRecords=[],
                  fixedEntryPlan={query: {'linkageName': linkage(query), 'parameters': [
                      {'name': name, 'targetType': kind, 'dwarfRegister': register}
                      for name, kind, register in TARGETS[query][1]]} for query in TARGETS})
    for row in selected:
        if graph[row['die']]['tag'] in {'DW_TAG_subprogram', 'DW_TAG_inlined_subroutine'}:
            node = graph[row['die']]
            status = 'inline record outside physical entry plan' if node['tag'] == 'DW_TAG_inlined_subroutine' else \
                'direct entry address retained' if address(node, 'DW_AT_low_pc') is not None else \
                'entry address unresolved with split ranges' if 'DW_AT_ranges' in node['attributes'] else \
                'entry address unresolved'
            report['originalFunctionRecords'].append(retain({'selectedRecord': row, 'record': node,
                                                             'physicalEntryStatus': status}))
    for query in QUERIES:
        rows = entries['entries'][query]
        if [row['originalSymbol'] for row in rows] != entries['symbols']['candidates'][query]:
            raise ValueError('Original entry and symbol inventories differ')
        output = report['entries'][query] = []
        for index, entry in enumerate(rows):
            symbol = entry['originalSymbol']
            row = {'entryRecordIndex': index, 'originalEntry': entry, 'status': 'outside-the-fixed-entry-plan',
                   'definitions': [], 'actualTargetCallingConventionProven': False}
            output.append(retain(row))
            if query not in TARGETS or symbol.get('name') != linkage(query):
                continue
            if any(type(symbol.get(name)) is not bool for name in ['function', 'undefined', 'ordinarySectionDefinition']):
                row.update(status='unproved-planned-entry', error={'message': 'Original symbol flags are invalid'})
                continue
            if not symbol.get('function') or symbol.get('undefined') or not symbol.get('ordinarySectionDefinition'):
                row['status'] = 'not-an-ordinary-defined-attachment'
                continue
            row['status'] = 'unproved-planned-entry'
            try:
                row['physicalEntry'] = entry_span(entry)
                for candidate in selected:
                    node = graph[candidate['die']]
                    if node['tag'] != 'DW_TAG_subprogram' or address(node, 'DW_AT_low_pc') != symbol['value']:
                        continue
                    names = [bytes.fromhex(item['nameHex']).decode('ascii') for item in candidate['nameCandidates']]
                    if query in names:
                        row['definitions'].append(retain(bind_function(query, candidate, source, symbol, task_definitions)))
                if row['definitions'] and all(item['actualTargetCallingConventionProven'] for item in row['definitions']):
                    row.update(status='pinned-physical-entry-abi-proven', actualTargetCallingConventionProven=True)
                elif not row['definitions']:
                    row['error'] = {'message': 'No concrete DWARF definition at the exact physical entry'}
            except Exception as error:
                if isinstance(error, AbiLimit):
                    raise
                row['error'] = {'type': type(error).__name__, 'message': str(error)}
    for query in TARGETS:
        rows = [row for row in report['entries'][query] if row['status'] != 'not-an-ordinary-defined-attachment' and
                row['originalEntry']['originalSymbol'].get('name') == linkage(query)]
        signatures = {(row['originalEntry']['originalSymbol'].get('value'),
                       row['originalEntry']['originalSymbol'].get('size'),
                       row.get('physicalEntry', {}).get('fileOffset')) for row in rows}
        proven = bool(rows) and len(signatures) == 1 and not source['dwarf']['nameErrors'] and \
            all(row['actualTargetCallingConventionProven'] for row in rows)
        report['targetConcordance'][query] = {'linkageName': linkage(query),
                                             'status': 'pinned-canonical-entry-abi-proven' if proven else 'unproved',
                                             'actualTargetCallingConventionProven': proven}
    report['actualTargetCallingConventionProven'] = all(row['actualTargetCallingConventionProven']
                                                        for row in report['targetConcordance'].values())


def inspect(paths, expected, output, report):
    names = ('context', 'layout', 'entries')
    supported = (CONTEXT_SHA256, LAYOUT_SHA256, ENTRIES_SHA256)
    raw_inputs = []
    for path, digest, known, name in zip(paths, expected, supported, names):
        if digest != known or path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Unsupported or oversized original ABI input')
        raw = path.read_bytes()
        if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError('Original ABI input bytes differ')
        (output / ('captured-vm-' + name + '.json')).write_bytes(raw)
        raw_inputs.append(raw)
    source, layout, entries = map(json.loads, raw_inputs)
    if source.get('sourceSha256') != CONTEXT_READER or source.get('contextSourceSha256') != CONTEXT_PROVIDER or \
            layout.get('sourceSha256') != LAYOUT_READER or entries.get('sourceSha256') != ENTRY_READER:
        raise ValueError('Original ABI input producer differs')
    if any(data.get('completed') is not True or data.get('diagnosticOnly') is not True or \
           data.get('acceptedSavingsCredit') is not False for data in [source, layout, entries]):
        raise ValueError('Original ABI input completion or scope differs')
    if source.get('dwarf', {}).get('selectedUnitTraversalCompleted') is not True or \
            source['dwarf'].get('locationExpressionsEvaluated') is not False or \
            source.get('contextScope', {}).get('locationExpressionsEvaluated') is not False:
        raise ValueError('Original context traversal or location contract differs')
    if layout.get('actualFieldLayoutProven') is not True or layout.get('contextSha256') != CONTEXT_SHA256 or \
            layout.get('actualTargetCallingConventionProven') is not False or \
            layout.get('privateFieldReadsAuthorized') is not False:
        raise ValueError('Original pinned layout proof is incomplete or changes read scope')
    if set(layout.get('fieldLayoutConcordance', {})) != set(dwarf.TASK_FIELDS) or \
            any(row.get('actualFieldLayoutProven') is not True for row in layout['fieldLayoutConcordance'].values()):
        raise ValueError('Original layout proof does not cover every requested task field')
    for data in [source, layout, entries]:
        if data.get('actualTargetCallingConventionProven') is not False or \
                any(data.get(key, False) is not False for key in
                    ['actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']):
            raise ValueError('Original inputs expand the supported proof scope')
    binding = source.get('fileBinding', {})
    if binding.get('externalDebugFileBound') is not True or binding.get('library', {}).get('sha256') != LIBRARY_SHA256 or \
            binding.get('externalDebug', {}).get('sha256') != DEBUG_SHA256 or \
            any(entries.get(key) != LIBRARY_SHA256 for key in
                ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']):
        raise ValueError('Original ABI input library identities differ')
    queries = entries.get('queries', QUERIES)
    if tuple(queries) != QUERIES or set(entries['entries']) != set(QUERIES) or \
            set(entries['symbols']['candidates']) != set(QUERIES) or \
            any(not isinstance(rows, list) for rows in entries['entries'].values()) or \
            sum(len(rows) for rows in entries['entries'].values()) > 256:
        raise ValueError('Original ABI entry query inventory differs')
    report['inputSha256'] = dict(zip(names, expected))
    review(source, layout, entries, report)
    if len(json.dumps(report).encode()) > MAX_OUTPUT_BYTES:
        raise ValueError('Entry ABI report exceeds bounds')
    report['completed'] = True


def main():
    context, context_hash, layout, layout_hash, entries, entries_hash, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'actualTargetCallingConventionProven': False, 'privateFieldReadsAuthorized': False,
              'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False,
              'directRegisterDescriptionsInterpreted': True, 'generalLocationExpressionsEvaluated': False,
              'scope': 'Only the pinned canonical broker/post compiler entry plan, normal AMD64 calling '
                       'convention and direct register locations at exact physical entries. All original '
                       'candidates and errors retained. No native loading, target/private read, task-lifetime '
                       'or control-flow/root/clock/code-size/runtime/compiler saving proof.'}
    try:
        inspect([Path(context), Path(layout), Path(entries)], [context_hash, layout_hash, entries_hash], output.parent, report)
    except Exception as error:
        report['completed'] = False
        report['actualTargetCallingConventionProven'] = False
        for row in report.get('targetConcordance', {}).values():
            row['actualTargetCallingConventionProven'] = False
        for rows in report.get('entries', {}).values():
            for row in rows:
                row['actualTargetCallingConventionProven'] = False
                for definition in row.get('definitions', []):
                    definition['actualTargetCallingConventionProven'] = False
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
