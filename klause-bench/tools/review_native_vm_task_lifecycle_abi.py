"""Review fixed lifecycle signatures and needed ABI reads from saved captures."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import sys

import review_native_vm_entry_abi as abi

INPUTS = {
    'context': ('de9cc435cc546b8bac9e8603df64464d5ed8049ddf5401c821561df6e39489eb',
                '9815e99885e4d8b0f408f828133fe3ede80033381de59aa4ced31745aecc1fea'),
    'original-context': (abi.CONTEXT_SHA256, abi.CONTEXT_READER),
    'layout': (abi.LAYOUT_SHA256, abi.LAYOUT_READER),
    'entries': ('eee7004be98e73e2eda70c3b4f74fb15b21f2301e8356ccdd5a5f0bf5a95352d',
                '2e89b7f5ef4b635772f74c8cbdb7075af5deb2833fd415f92b709205b1f078aa'),
    'additional-entries': ('f93246b5f406c7b19531286d785b85ba9a35b01970f5ac97acbd43d2b333655f',
                           '2e89b7f5ef4b635772f74c8cbdb7075af5deb2833fd415f92b709205b1f078aa'),
}
QUERIES = ('compiler_thread_loop', 'CompileTaskWrapper', 'CompileTask4free',
           'invoke_compiler_on_method', 'post_compiled_method')
ADDITIONAL_QUERIES = ('CompileTask8allocate', 'CompileTask10initialize')
ROLES = {
    'allocate': {'names': ('_ZN11CompileTask8allocateEv',), 'sourceName': 'allocate', 'parameters': 0,
                 'owner': None, 'reads': (), 'declarations': {'compileTask.cpp': 45, 'compileTask.hpp': 125}},
    'initialize': {'names': ('_ZN11CompileTask10initializeEiRK12methodHandleiiiNS_13CompileReasonEb',),
                   'sourceName': 'initialize', 'parameters': 8, 'owner': 'CompileTask',
                   'reads': ((0, 'this', 'CompileTask', 5), (1, 'compile_id', 'int', 4)),
                   'declarations': {'compileTask.cpp': 98, 'compileTask.hpp': 121}},
    'free': {'names': ('_ZN11CompileTask4freeEPS_',), 'sourceName': 'free', 'parameters': 1,
             'owner': None, 'reads': ((0, 'task', 'CompileTask', 5),),
             'declarations': {'compileTask.cpp': 67, 'compileTask.hpp': 126}},
    'construct': {'names': ('_ZN18CompileTaskWrapperC1EP11CompileTask', '_ZN18CompileTaskWrapperC2EP11CompileTask'),
                  'sourceName': 'CompileTaskWrapper', 'parameters': 2, 'owner': 'CompileTaskWrapper',
                  'reads': ((1, 'task', 'CompileTask', 4),),
                  'declarations': {'compileBroker.cpp': 217, 'compileBroker.hpp': 158}},
    'destroy': {'names': ('_ZN18CompileTaskWrapperD1Ev', '_ZN18CompileTaskWrapperD2Ev'),
                'sourceName': '~CompileTaskWrapper', 'parameters': 1, 'owner': 'CompileTaskWrapper', 'reads': (),
                'declarations': {'compileBroker.cpp': 224, 'compileBroker.hpp': 159}},
}
SOURCE_REF = '520406d871955300957ef01e406ac2acd0f9b75c'
SOURCES = {
    'compileTask.cpp': ('src/hotspot/share/compiler/compileTask.cpp',
                        'eeeae2c447a8649d02d80066b7e6d0dcf63b2e3881ec7807595274d27a1033e1'),
    'compileTask.hpp': ('src/hotspot/share/compiler/compileTask.hpp',
                        '016c6a6c3b187b5e2a80969d631a9c44c4638103d2043f13e2a6bc4a05660489'),
    'compileBroker.cpp': ('src/hotspot/share/compiler/compileBroker.cpp',
                          '7932c4d7877ae06a54854fe1137ca427bc188dac635bfae567812e6ac4856455'),
    'compileBroker.hpp': ('src/hotspot/share/compiler/compileBroker.hpp',
                          '6b882ded9782f83daa87575db11adfdfa2a3d6e27d9bd7385f4c876650fada5b'),
}
MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_OUTPUT_BYTES = 32 * 1024 * 1024


def stable_description(value):
    if isinstance(value, dict):
        return {key: stable_description(item) for key, item in value.items() if key != 'parserBudget'}
    if isinstance(value, list):
        return [stable_description(item) for item in value]
    return value


def task_definitions(source, original, layout):
    graph, before = source['dwarf']['graph'], original['dwarf']['graph']
    owners = {row['ownerDie'] for row in layout['layoutDefinitions']}
    if not owners or any(row['problems'] for row in layout['layoutDefinitions']):
        raise ValueError('Original task layout definitions are incomplete')
    current = {row['die'] for row in source['dwarf']['selectedRecords']
               if graph[row['die']]['tag'] in {'DW_TAG_class_type', 'DW_TAG_structure_type'} and
               any(bytes.fromhex(name['nameHex']) == b'CompileTask' for name in row['nameCandidates']) and
               graph[row['die']]['attributes'].get('DW_AT_declaration', {}).get('value') not in (True, 1)}
    if current != owners:
        raise ValueError('New context changes the complete validated task owner inventory')
    old_selected = {row['die']: row for row in original['dwarf']['selectedRecords']}
    new_selected = {row['die']: row for row in source['dwarf']['selectedRecords']}
    pending, seen = [], set()
    for identity in sorted(owners):
        if old_selected.get(identity) != new_selected.get(identity):
            raise ValueError('Validated task member inventory differs in the new context')
        pending.extend([identity, *old_selected[identity]['children']])
    while pending:
        identity = pending.pop()
        if identity in seen:
            continue
        if len(seen) >= abi.dwarf.MAX_GRAPH:
            raise ValueError('Task type closure exceeds bounds')
        seen.add(identity)
        if identity not in before or identity not in graph or before[identity] is None or graph[identity] is None or \
                stable_description(before[identity]) != stable_description(graph[identity]):
            raise ValueError('Validated task type/reference description changed')
        pending.extend(before[identity]['references'].values())
    return owners


def byte_text(value):
    if not isinstance(value, dict) or set(value) != {'bytesHex'}:
        return None
    try:
        return bytes.fromhex(value['bytesHex']).decode('utf-8')
    except (ValueError, UnicodeError, TypeError):
        return None


def source_declarations(identity, graph, role):
    files = abi.inherited(identity, 'DW_AT_decl_file', graph)
    lines = abi.inherited(identity, 'DW_AT_decl_line', graph)
    if not files or not lines:
        raise ValueError('Lifecycle source declaration is unresolved')
    observations = []
    for origin, _ in files:
        reference = graph[origin].get('context', {}).get('fileReferences', {}).get('DW_AT_decl_file', {})
        if reference.get('status') != 'header-file-entry':
            raise ValueError('Lifecycle source file has no unambiguous header entry')
        name = byte_text(reference['file'].get('name'))
        directory = reference.get('directory', {})
        parent = byte_text(directory.get('entry')) if directory.get('status') == 'header-directory' else \
            byte_text((directory.get('attribute') or {}).get('value')) \
            if directory.get('status') == 'compilation-directory' else None
        if name is None or not PurePosixPath(name).is_absolute() and parent is None:
            raise ValueError('Lifecycle source path is unresolved')
        path = posixpath.normpath(name if PurePosixPath(name).is_absolute() else posixpath.join(parent, name))
        matches = [(short, line) for short, line in ROLES[role]['declarations'].items()
                   if path == SOURCES[short][0] or path.endswith('/' + SOURCES[short][0])]
        if len(matches) != 1:
            raise ValueError('Lifecycle declaration belongs to an unbound source file')
        short, expected = matches[0]
        for line_origin, value in lines:
            line = abi.dwarf.scalar({'attributes': {'line': value}}, 'line')
            if line != expected:
                raise ValueError('Lifecycle declaration line differs from pinned source')
            observations.append({'fileOrigin': origin, 'lineOrigin': line_origin, 'path': path, 'line': line,
                                 'sourcePath': SOURCES[short][0], 'sourceSha256': SOURCES[short][1],
                                 'sourceRef': SOURCE_REF})
    return observations


def void_return(identity, graph):
    for _, target in abi.inherited(identity, 'DW_AT_type', graph):
        node = graph[target]
        if node['referenceErrors'] or node['tag'] != 'DW_TAG_unspecified_type' or abi.dwarf.text_name(node) != 'void':
            raise ValueError('Lifecycle void return type differs from its source signature')


def bind_definition(role, selected, source, symbol, owners):
    plan = ROLES[role]
    graph = source['dwarf']['graph']
    identity, pc = selected['die'], symbol['value']
    node = graph[identity]
    result = {'selectedRecord': selected, 'record': node, 'status': 'unproved',
              'sourceBoundaryProven': False, 'argumentPlanProven': False, 'pointerReturnClassProven': False,
              'parameterBindings': []}
    try:
        links = [*abi.inherited(identity, 'DW_AT_linkage_name', graph),
                 *abi.inherited(identity, 'DW_AT_MIPS_linkage_name', graph)]
        if not links or any(byte_text(value.get('value')) not in plan['names'] for _, value in links):
            raise ValueError('Lifecycle linkage differs from the fixed source signatures')
        result['functionRange'] = abi.function_entry(node, pc, symbol['size'])
        unit = source['contextUnits'][node['context']['unitTopDie']]
        if unit['unitOffset'] != node['unitOffset'] or node.get('parentKnownFromSelectedTraversal') is not True:
            raise ValueError('Lifecycle function has an unresolved compilation-unit owner')
        result['sourceDeclarations'] = source_declarations(identity, graph, role)
        children = [graph[child] for child in selected['children']]
        if any(child['tag'] == 'DW_TAG_unspecified_parameters' for child in children):
            raise ValueError('Variadic lifecycle parameters are outside the fixed plan')
        parameters = [child for child in selected['children'] if graph[child]['tag'] == 'DW_TAG_formal_parameter']
        result['allOriginalParameters'] = [graph[child] for child in parameters]
        if len(parameters) != plan['parameters']:
            raise ValueError('Concrete lifecycle parameter inventory differs from source')
        objects = [value for _, value in abi.inherited(identity, 'DW_AT_object_pointer', graph)]
        if plan['owner'] is None and objects:
            raise ValueError('Static lifecycle function has an object parameter')
        for index, parameter in enumerate(parameters):
            child = graph[parameter]
            if child.get('parentKnownFromSelectedTraversal') is not True or child.get('parent') != identity:
                raise ValueError('Lifecycle parameter does not belong directly to its function')
            artificial = abi.parameter_artificial(parameter, graph)
            if artificial != (plan['owner'] is not None and index == 0):
                raise ValueError('Lifecycle artificial parameter differs from source')
            if plan['owner'] is not None and index == 0:
                lineage = abi.parameter_lineage(parameter, graph)
                if not objects or any(value not in lineage for value in objects):
                    raise ValueError('Lifecycle object pointer does not bind the concrete parameter')
                result['objectTypeBindings'] = abi.pointer_type(parameter, graph, plan['owner'], owners)
        for index, name, kind, register in plan['reads']:
            parameter = parameters[index]
            child = graph[parameter]
            binding = {'parameterIndex': index, 'die': parameter, 'name': name, 'targetType': kind,
                       'expectedDwarfRegister': register, 'readBindingProven': False}
            result['parameterBindings'].append(binding)
            if kind == 'int':
                types = abi.inherited(parameter, 'DW_AT_type', graph)
                if not types:
                    raise ValueError('Initializer compile identity has no storage type')
                storage = [abi.dwarf.storage(target, graph) for _, target in types]
                if any(row.get('status') != 'constant-storage-candidate' or row.get('tag') != 'DW_TAG_base_type' or
                       row.get('name') != 'int' or row.get('byteSize') != 4 or row.get('encoding') != 5 for row in storage):
                    raise ValueError('Initializer compile identity is not a signed 32-bit integer')
                binding.update(storageBindings=storage, extractionBits=32)
            else:
                binding['typeBindings'] = abi.pointer_type(parameter, graph, kind, owners)
                binding['extractionBits'] = 64
            descriptions = abi.locations_at_entry(child, pc, unit)
            binding['entryLocations'] = descriptions
            registers = [abi.register(row['expression']) for row in descriptions]
            binding['entryRegisters'] = registers
            if not registers or any(value != register for value in registers):
                raise ValueError('Lifecycle entry location does not establish the planned register')
            binding['readBindingProven'] = True
        if role == 'allocate':
            result['returnTypeBindings'] = abi.pointer_type(identity, graph, 'CompileTask', owners)
            result['pointerReturnClass'] = {'abiClass': 'INTEGER', 'dwarfRegister': 0, 'bits': 64}
            result['pointerReturnClassProven'] = True
        else:
            void_return(identity, graph)
        result.update(status='fixed-lifecycle-source-read-plan-bound', sourceBoundaryProven=True,
                      argumentPlanProven=True)
    except Exception as error:
        result.update(error={'type': type(error).__name__, 'message': str(error)})
        result['sourceBoundaryProven'] = result['argumentPlanProven'] = result['pointerReturnClassProven'] = False
        for binding in result['parameterBindings']:
            binding['readBindingProven'] = False
    return result


def entry_identity(entry):
    span = abi.entry_span(entry)
    if entry.get('undecodedHex') != '':
        raise ValueError('Lifecycle symbol has an undecoded suffix')
    return span['virtualAddress'], span['bytes'], span['fileOffset'], span['entrySha256']


def review(source, original, layout, groups, report):
    owners = task_definitions(source, original, layout)
    graph, selected = source['dwarf']['graph'], source['dwarf']['selectedRecords']
    report.update(reboundTaskDefinitions=sorted(owners), entries=[], roles={}, originalNameErrors=source['dwarf']['nameErrors'],
                  allOriginalFunctionRecords=[{'selectedRecord': row, 'record': graph[row['die']]}
                                              for row in selected if graph[row['die']]['tag'] in
                                              {'DW_TAG_subprogram', 'DW_TAG_inlined_subroutine'}],
                  lifecycleArgumentPlanProven=False)
    for role, plan in ROLES.items():
        target = report['roles'][role] = {'requiredSymbols': list(plan['names']), 'entries': [],
                                         'unmatchedPhysicalDefinitions': [], 'argumentPlanProven': False}
        identities = {name: set() for name in plan['names']}
        for query, entries in groups.items():
            for index, entry in enumerate(entries):
                symbol = entry['originalSymbol']
                if symbol['name'] not in plan['names']:
                    continue
                row = {'query': query, 'entryRecordIndex': index, 'originalEntry': entry, 'definitions': [],
                       'status': 'unproved', 'argumentPlanProven': False}
                target['entries'].append(row)
                try:
                    if any(type(symbol.get(key)) is not bool for key in ['function', 'undefined', 'ordinarySectionDefinition']) or \
                            not symbol['function'] or symbol['undefined'] or not symbol['ordinarySectionDefinition']:
                        raise ValueError('Canonical lifecycle symbol is not an ordinary defined function')
                    physical = entry_identity(entry)
                    identities[symbol['name']].add(physical)
                    for candidate in selected:
                        node = graph[candidate['die']]
                        if node['tag'] != 'DW_TAG_subprogram' or abi.address(node, 'DW_AT_low_pc') != symbol['value'] or \
                                not any(bytes.fromhex(name['nameHex']) == plan['sourceName'].encode('ascii')
                                        for name in candidate['nameCandidates']):
                            continue
                        row['definitions'].append(bind_definition(role, candidate, source, symbol, owners))
                    row['argumentPlanProven'] = bool(row['definitions']) and all(
                        value['argumentPlanProven'] for value in row['definitions'])
                    row['status'] = 'fixed-lifecycle-entry-read-plan-bound' if row['argumentPlanProven'] else 'unproved'
                except Exception as error:
                    row['error'] = {'type': type(error).__name__, 'message': str(error)}
        for candidate in selected:
            node = graph[candidate['die']]
            if node['tag'] != 'DW_TAG_subprogram' or node['attributes'].get('DW_AT_declaration', {}).get('value') in (True, 1) or \
                    'DW_AT_low_pc' not in node['attributes'] and 'DW_AT_inline' in node['attributes']:
                continue
            if not any(bytes.fromhex(name['nameHex']) == plan['sourceName'].encode('ascii')
                       for name in candidate['nameCandidates']):
                continue
            try:
                links = [*abi.inherited(candidate['die'], 'DW_AT_linkage_name', graph),
                         *abi.inherited(candidate['die'], 'DW_AT_MIPS_linkage_name', graph)]
                if not links:
                    raise ValueError('Possible lifecycle definition has no resolved linkage')
                if any(byte_text(value.get('value')) in plan['names'] for _, value in links) and \
                        not any(candidate['die'] == definition['selectedRecord']['die'] for row in target['entries']
                                for definition in row['definitions']):
                    target['unmatchedPhysicalDefinitions'].append({'selectedRecord': candidate, 'record': node,
                                                                   'status': 'canonical lifecycle definition outside bound physical entries'})
            except Exception as error:
                target['unmatchedPhysicalDefinitions'].append({'selectedRecord': candidate, 'record': node,
                                                               'status': 'unresolved possible lifecycle definition',
                                                               'error': {'type': type(error).__name__, 'message': str(error)}})
        target['argumentPlanProven'] = bool(target['entries']) and not target['unmatchedPhysicalDefinitions'] and \
            all(len(values) == 1 for values in identities.values()) and all(row['argumentPlanProven'] for row in target['entries'])
    report['entries'] = {query: entries for query, entries in groups.items()}
    report['lifecycleArgumentPlanProven'] = not source['dwarf']['nameErrors'] and all(
        row['argumentPlanProven'] for row in report['roles'].values())


def inspect(paths, expected, output, report):
    if len(paths) != len(INPUTS) or len(expected) != len(INPUTS):
        raise ValueError('All five original lifecycle inputs are required')
    raw_inputs, total = [], 0
    for name, path, digest in zip(INPUTS, paths, expected):
        if digest != INPUTS[name][0] or path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Unsupported or oversized lifecycle input')
        raw = path.read_bytes()
        total += len(raw)
        if len(raw) > MAX_INPUT_BYTES or total > MAX_TOTAL_BYTES or hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError('Lifecycle input bytes differ or exceed bounds')
        (output / ('captured-vm-' + name + '.json')).write_bytes(raw)
        raw_inputs.append(raw)
    data = dict(zip(INPUTS, map(json.loads, raw_inputs)))
    for name, value in data.items():
        if value.get('sourceSha256') != INPUTS[name][1] or value.get('completed') is not True or \
                value.get('diagnosticOnly') is not True or value.get('acceptedSavingsCredit') is not False:
            raise ValueError('Lifecycle input producer, completion or scope differs')
    source, original, layout = (data[name] for name in ['context', 'original-context', 'layout'])
    expected_plan = {'name': 'task-lifecycle',
                     'typeNames': ['CodeBlob', 'CompileTask', 'CompileTaskWrapper', 'CompilerThread', 'nmethod'],
                     'functionNames': ['CompileTaskWrapper', 'allocate', 'compiler_thread_loop', 'free', 'initialize',
                                       'invoke_compiler_on_method', 'post_compiled_method', 'thread_entry',
                                       'total_size', '~CompileTaskWrapper']}
    if source.get('selectionPlan') != expected_plan or source['dwarf'].get('selectionPlan') != expected_plan:
        raise ValueError('Lifecycle input selection differs from the fixed source plan')
    for context in [source, original]:
        graph, selected = context['dwarf']['graph'], context['dwarf']['selectedRecords']
        if context.get('contextSourceSha256') != abi.CONTEXT_PROVIDER or \
                context['dwarf'].get('selectedUnitTraversalCompleted') is not True or \
                context['dwarf'].get('locationExpressionsEvaluated') is not False or context['dwarf']['nameErrors'] or \
                not isinstance(graph, dict) or len(graph) > abi.dwarf.MAX_GRAPH or \
                any(not isinstance(node, dict) for node in graph.values()) or \
                not isinstance(selected, list) or len(selected) > abi.dwarf.MAX_SELECTED or \
                len({row['die'] for row in selected}) != len(selected):
            raise ValueError('Bound lifecycle context is incomplete or exceeds limits')
        coverage = context['dwarf']['requestedUnitCoverage']
        if set(coverage) != {'compileBroker.cpp', 'compileTask.cpp', 'nmethod.cpp', 'compilerThread.cpp'} or \
                any(not rows or any(row.get('traversalCompleted') is not True for row in rows)
                    for rows in coverage.values()):
            raise ValueError('Lifecycle compiler-unit coverage is incomplete')
        binding = context['fileBinding']
        if binding.get('externalDebugFileBound') is not True or \
                binding['library']['sha256'] != abi.LIBRARY_SHA256 or \
                binding['externalDebug']['sha256'] != abi.DEBUG_SHA256:
            raise ValueError('Lifecycle context belongs to different native/debug files')
        if context.get('contextScope', {}).get('locationExpressionsEvaluated') is not False or \
                any(context.get('contextScope', {}).get(key) is not False for key in
                    ['actualFieldLayoutProven', 'actualTargetCallingConventionProven',
                     'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven', 'acceptedSavingsCredit']):
            raise ValueError('Lifecycle context decoder changes its proof scope')
        if any(context.get(key, False) is not False for key in ['actualFieldLayoutProven',
                'actualTargetCallingConventionProven', 'actualControlFlowCoverageProven',
                'rootCensusProven', 'codeSizeProven', 'privateFieldReadsAuthorized']):
            raise ValueError('Lifecycle context expands its supported proof scope')
    if layout.get('actualFieldLayoutProven') is not True or layout.get('contextSha256') != abi.CONTEXT_SHA256 or \
            layout.get('privateFieldReadsAuthorized') is not False or layout.get('actualTargetCallingConventionProven') is not False or \
            set(layout['fieldLayoutConcordance']) != set(abi.dwarf.TASK_FIELDS) or \
            any(row.get('actualFieldLayoutProven') is not True for row in layout['fieldLayoutConcordance'].values()):
        raise ValueError('Original complete task layout proof is unbound')
    groups = {}
    for name, queries in [('entries', QUERIES), ('additional-entries', ADDITIONAL_QUERIES)]:
        entries = data[name]
        if tuple(entries['queries']) != queries or set(entries['entries']) != set(queries) or \
                set(entries['symbols']['candidates']) != set(queries) or \
                any(not isinstance(rows, list) for rows in entries['entries'].values()) or \
                sum(len(rows) for rows in entries['entries'].values()) > 256 or \
                any(entries.get(key) != abi.LIBRARY_SHA256 for key in
                    ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']) or \
                not entries['symbols']['symbolTables'] or \
                any(row.get('completed') is not True for row in entries['symbols']['symbolTables']) or \
                entries.get('actualTargetCallingConventionProven') is not False:
            raise ValueError('Lifecycle entry query or library identity differs')
        for query, rows in entries['entries'].items():
            if [row['originalSymbol'] for row in rows] != entries['symbols']['candidates'][query]:
                raise ValueError('Lifecycle entry and original symbol inventories differ')
            groups[query] = rows
    report['inputSha256'] = dict(zip(INPUTS, expected))
    review(source, original, layout, groups, report)
    if len(json.dumps(report, separators=(',', ':')).encode()) > MAX_OUTPUT_BYTES:
        raise ValueError('Lifecycle review output exceeds bounds')
    report['completed'] = True


def decline(report, error):
    report.update(completed=False, lifecycleArgumentPlanProven=False,
                  error={'type': type(error).__name__, 'message': str(error)})
    for role in report.get('roles', {}).values():
        role['argumentPlanProven'] = False
        for row in role['entries']:
            row['argumentPlanProven'] = False
            for definition in row['definitions']:
                definition['sourceBoundaryProven'] = definition['argumentPlanProven'] = False
                definition['pointerReturnClassProven'] = False
                for binding in definition['parameterBindings']:
                    binding['readBindingProven'] = False


def main():
    arguments = sys.argv[1:]
    output = Path(arguments[-1])
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'lifecycleArgumentPlanProven': False, 'actualTargetCallingConventionProven': False,
              'actualCompiledTaskLifetimeProven': False, 'actualTaskGenerationProven': False,
              'privateFieldReadsAuthorized': False, 'actualControlFlowCoverageProven': False,
              'rootCensusProven': False, 'codeSizeProven': False,
              'directRegisterDescriptionsInterpreted': True, 'generalLocationExpressionsEvaluated': False,
              'scope': 'Fixed source/physical lifecycle boundary and explicitly requested argument '
                       'locations or normal allocation pointer return class only. All saved aliases, '
                       'declarations, inlined records and errors retained. Unused parameters are not '
                       'assigned a location proof. No actual return/callback delivery, lifetime, '
                       'generation, kernel, private-field read, complete root/clock/size/compiler authority.'}
    try:
        if len(arguments) != 11:
            raise ValueError('Five exact input path/hash pairs and output are required')
        inspect([Path(value) for value in arguments[:-1:2]], arguments[1:-1:2], output.parent, report)
    except Exception as error:
        decline(report, error)
        raise
    finally:
        output.write_text(json.dumps(report, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
