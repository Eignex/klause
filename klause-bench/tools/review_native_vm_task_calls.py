"""Bind saved compiler caller records and explicit normal-call continuations."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import sys

import review_native_vm_entry_abi as abi

INPUTS = {
    'context': ('bad70dc42cd1d7b89b15a0fb0e089f70d01098577fc96f8db158abc36059979c', abi.CONTEXT_READER),
    'abi': ('3aea5bb5831a570c7d5f09ff6e305a028745da5218b8dc884def3db3aa96a3c5',
            'd6b2b53bec6d32fff2dc3ffddc3812d6bfe47074eaff5ef560982d29426c8d2d'),
    'entries': ('eee7004be98e73e2eda70c3b4f74fb15b21f2301e8356ccdd5a5f0bf5a95352d',
                '2e89b7f5ef4b635772f74c8cbdb7075af5deb2833fd415f92b709205b1f078aa'),
    'transfers': ('0a360e2bdc1f8754651985d4dc6150f8a769f40e61f90491506e0a4cd3a466b7',
                  '755ff28c0fb54596ce57a12b7e3a16a16031872dbf6189ccf50787fd97428c38'),
}
QUERIES = ('compiler_thread_loop', 'CompileTaskWrapper', 'CompileTask4free',
           'invoke_compiler_on_method', 'post_compiled_method')
CALLER = '_ZN13CompileBroker20compiler_thread_loopEv'
BROKER = '_ZN13CompileBroker25invoke_compiler_on_methodEP11CompileTask'
POST = '_ZN7nmethod20post_compiled_methodEP11CompileTask'
DESTROY = ('_ZN18CompileTaskWrapperD1Ev', '_ZN18CompileTaskWrapperD2Ev')
FREE = '_ZN11CompileTask4freeEPS_'
SOURCE_REF = '520406d871955300957ef01e406ac2acd0f9b75c'
DECLARATIONS = {
    'src/hotspot/share/compiler/compileBroker.cpp':
        (1896, '7932c4d7877ae06a54854fe1137ca427bc188dac635bfae567812e6ac4856455'),
    'src/hotspot/share/compiler/compileBroker.hpp':
        (344, '6b882ded9782f83daa87575db11adfdfa2a3d6e27d9bd7385f4c876650fada5b'),
}
MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_OUTPUT_BYTES = 32 * 1024 * 1024


def byte_text(value):
    if not isinstance(value, dict) or set(value) != {'bytesHex'} or \
            not isinstance(value['bytesHex'], str) or len(value['bytesHex']) > 32768:
        return None
    try:
        return bytes.fromhex(value['bytesHex']).decode('utf-8')
    except (ValueError, UnicodeError):
        return None


def declaration(identity, graph):
    files = abi.inherited(identity, 'DW_AT_decl_file', graph)
    lines = abi.inherited(identity, 'DW_AT_decl_line', graph)
    if not files or not lines:
        raise ValueError('Caller source declaration is unresolved')
    results = []
    for origin, _ in files:
        reference = graph[origin].get('context', {}).get('fileReferences', {}).get('DW_AT_decl_file', {})
        if reference.get('status') != 'header-file-entry':
            raise ValueError('Caller source file has no unambiguous header entry')
        name = byte_text(reference['file'].get('name'))
        directory = reference.get('directory', {})
        parent = byte_text(directory.get('entry')) if directory.get('status') == 'header-directory' else \
            byte_text((directory.get('attribute') or {}).get('value')) \
            if directory.get('status') == 'compilation-directory' else None
        if name is None or not PurePosixPath(name).is_absolute() and parent is None:
            raise ValueError('Caller source path is unresolved')
        path = posixpath.normpath(name if PurePosixPath(name).is_absolute() else posixpath.join(parent, name))
        matches = [(suffix, line, digest) for suffix, (line, digest) in DECLARATIONS.items()
                   if path == suffix or path.endswith('/' + suffix)]
        if len(matches) != 1:
            raise ValueError('Caller declaration belongs to an unbound source file')
        suffix, expected_line, digest = matches[0]
        for line_origin, attr in lines:
            line = abi.dwarf.scalar({'attributes': {'line': attr}}, 'line')
            if line != expected_line:
                raise ValueError('Caller declaration line differs from pinned source')
            results.append({'fileOrigin': origin, 'lineOrigin': line_origin, 'path': path,
                            'line': line, 'sourcePath': suffix, 'sourceSha256': digest, 'sourceRef': SOURCE_REF})
    return results


def physical_identity(entry):
    span = abi.entry_span(entry)
    if entry.get('undecodedHex') != '':
        raise ValueError('Complete symbol has an undecoded suffix')
    return span['virtualAddress'], span['bytes'], span['fileOffset'], span['entrySha256']


def ordinary(symbol):
    if any(type(symbol.get(key)) is not bool for key in ['function', 'undefined', 'ordinarySectionDefinition']):
        raise ValueError('Saved symbol flags are invalid')
    return symbol['function'] and not symbol['undefined'] and symbol['ordinarySectionDefinition']


def planned(entries, names):
    rows = [row for values in entries.values() for row in values
            if row['originalSymbol']['name'] in names and ordinary(row['originalSymbol'])]
    if not rows:
        raise ValueError('Planned physical source function is missing')
    for name in names:
        named = [row for row in rows if row['originalSymbol']['name'] == name]
        if not named or len({physical_identity(row) for row in named}) != 1:
            raise ValueError('Planned source name has missing or conflicting physical definitions')
    return rows


def caller_definitions(source, entries):
    graph = source['dwarf']['graph']
    results = []
    spans = {physical_identity(row) for row in entries}
    if len(spans) != 1:
        raise ValueError('Canonical compiler caller has conflicting physical definitions')
    pc, size, _, _ = next(iter(spans))
    for selected in source['dwarf']['selectedRecords']:
        node = graph[selected['die']]
        if node['tag'] != 'DW_TAG_subprogram' or not any(
                bytes.fromhex(row['nameHex']) == b'compiler_thread_loop' for row in selected['nameCandidates']):
            continue
        result = {'selectedRecord': selected, 'record': node, 'status': 'unproved',
                  'callerSourceDeclarationConcordant': False}
        results.append(result)
        if node['attributes'].get('DW_AT_declaration', {}).get('value') in (True, 1):
            result['status'] = 'source declaration outside physical caller inventory'
            continue
        if 'DW_AT_low_pc' not in node['attributes'] and 'DW_AT_inline' in node['attributes']:
            result['status'] = 'abstract source function outside physical caller inventory'
            continue
        try:
            result['physicalSpan'] = abi.function_entry(node, pc, size)
            unit = source['contextUnits'][node['context']['unitTopDie']]
            unit_name = byte_text(unit.get('topAttributes', {}).get('DW_AT_name', {}).get('value'))
            if unit_name is None or PurePosixPath(unit_name).name != 'compileBroker.cpp' or \
                    unit.get('unitOffset') != node['unitOffset'] or \
                    node.get('parentKnownFromSelectedTraversal') is not True or node.get('parent') != unit.get('topDie'):
                raise ValueError('Caller has no globally owned compiler source unit')
            links = [*abi.inherited(selected['die'], 'DW_AT_linkage_name', graph),
                     *abi.inherited(selected['die'], 'DW_AT_MIPS_linkage_name', graph)]
            if not links or any(byte_text(attr.get('value')) != CALLER for _, attr in links):
                raise ValueError('Caller linkage differs from the fixed source signature')
            result['sourceDeclarations'] = declaration(selected['die'], graph)
            result.update(status='pinned-physical-caller-source-concordant',
                          callerSourceDeclarationConcordant=True)
        except Exception as error:
            result['error'] = {'type': type(error).__name__, 'message': str(error)}
    concrete = [row for row in results if row['status'] not in {
        'source declaration outside physical caller inventory',
        'abstract source function outside physical caller inventory'}]
    return results, bool(concrete) and all(row['callerSourceDeclarationConcordant'] for row in concrete)


def near_call(instruction, target, boundaries):
    encoded = bytes.fromhex(instruction['bytesHex'])
    pc = instruction['address']
    if type(pc) is not int or type(target) is not int or len(encoded) != 5 or encoded[0] != 0xe8:
        raise ValueError('Call is outside the fixed direct near-call encoding')
    continuation = pc + len(encoded)
    decoded_target = continuation + int.from_bytes(encoded[1:], 'little', signed=True)
    if not 0 <= pc < continuation <= abi.MAX_ADDRESS or not 0 <= decoded_target <= abi.MAX_ADDRESS or \
            decoded_target != target or continuation not in boundaries:
        raise ValueError('Direct near call or caller continuation is unbound')
    return {'instruction': instruction, 'target': target, 'continuation': continuation,
            'status': 'direct-near-call-and-retained-continuation-bound',
            'normalCallContinuationProven': True}


def review(source, original_abi, entries, transfers, report):
    if tuple(entries['queries']) != QUERIES or tuple(transfers['queries']) != QUERIES or \
            set(entries['entries']) != set(QUERIES) or set(transfers['entries']) != set(QUERIES):
        raise ValueError('Saved compiler caller query inventory differs')
    report.update(originalTransferReview=transfers, callerDefinitions=[], sourceFunctions={},
                  callerTransfers=[], brokerNormalCallContinuationsProven=False,
                  callerSourceDeclarationConcordant=False)
    for query in QUERIES:
        before, after = entries['entries'][query], transfers['entries'][query]
        if len(before) != len(after):
            raise ValueError('Saved entry/transfer inventories differ')
        for index, (entry, transfer) in enumerate(zip(before, after)):
            if transfer['entryRecordIndex'] != index or transfer['originalEntryCopiedVerbatim'] != entry or \
                    transfer['originalSymbol'] != entry['originalSymbol']:
                raise ValueError('Saved transfer no longer refers to its exact original entry')
    functions = {'caller': (CALLER,), 'broker': (BROKER,), 'post': (POST,), 'destroy': DESTROY, 'free': (FREE,)}
    for role, names in functions.items():
        report['sourceFunctions'][role] = planned(entries['entries'], names)
    for query, name in [('invoke_compiler_on_method', BROKER), ('post_compiled_method', POST)]:
        saved = original_abi['entries'][query]
        proof = [row for row in saved if row['originalEntry']['originalSymbol']['name'] == name and
                 row['status'] != 'not-an-ordinary-defined-attachment']
        if not proof or any(row['actualTargetCallingConventionProven'] is not True for row in proof):
            raise ValueError('Original fixed canonical entry ABI is incomplete')
        expected = {physical_identity(row['originalEntry']) for row in proof}
        current = {physical_identity(row) for row in report['sourceFunctions'][
            'broker' if query == 'invoke_compiler_on_method' else 'post']}
        if expected != current:
            raise ValueError('Canonical compiler entry bytes differ from the original ABI proof')
    definitions, concordant = caller_definitions(source, report['sourceFunctions']['caller'])
    report.update(callerDefinitions=definitions, callerSourceDeclarationConcordant=concordant)
    broker_targets = {row['originalSymbol']['value'] for row in report['sourceFunctions']['broker']}
    for entry, transfer in zip(entries['entries']['compiler_thread_loop'], transfers['entries']['compiler_thread_loop']):
        if entry['originalSymbol']['name'] != CALLER or not ordinary(entry['originalSymbol']):
            continue
        physical_identity(entry)
        if transfer.get('reviewCompleted') is not True or transfer.get('wholeSymbolCaptured') is not True or \
                transfer.get('allCapturedBytesDecoded') is not True:
            raise ValueError('Caller linear transfer review is incomplete')
        instructions = entry['instructions']
        boundaries = {row['address'] for row in instructions}
        records = []
        report['callerTransfers'].append({'originalEntry': entry, 'transfers': records})
        for row in transfer['transferReview']['recognizedTransfers']:
            index = row['instructionIndex']
            if type(index) is not int or not 0 <= index < len(instructions) or row['instruction'] != instructions[index]:
                raise ValueError('Caller transfer instruction is not its original captured boundary')
            item = {'originalTransfer': row, 'status': 'outside fixed direct broker call plan',
                    'normalCallContinuationProven': False}
            records.append(item)
            if row['target'] not in broker_targets:
                continue
            try:
                if row['category'] != 'call':
                    raise ValueError('Transfer to broker is not a normal call')
                item.update(near_call(row['instruction'], row['target'], boundaries))
            except Exception as error:
                item.update(status='unproved broker transfer', error={'type': type(error).__name__, 'message': str(error)})
    groups = [[row for row in group['transfers'] if row['originalTransfer']['target'] in broker_targets]
              for group in report['callerTransfers']]
    report['brokerNormalCallContinuationsProven'] = concordant and bool(groups) and all(
        rows and all(row['normalCallContinuationProven'] for row in rows) for rows in groups)
    report['sourceLifetimeContract'] = {
        'sourceRef': SOURCE_REF, 'compileBrokerCppSha256': DECLARATIONS[
            'src/hotspot/share/compiler/compileBroker.cpp'][1],
        'callerWrapperConstructionLine': 1966, 'brokerInvocationLine': 1973,
        'wrapperDestructorLine': 224,
        'scope': 'Pinned source keeps the wrapper in the caller around the broker invocation. '
                 'Normal call continuation binding does not prove transitive callee effects, kernel '
                 'return-probe behavior, task generation or live memory-read validity.'}


def inspect(paths, expected, output, report):
    if len(paths) != len(INPUTS) or len(expected) != len(INPUTS):
        raise ValueError('Every original task-call input is required')
    raw_inputs, total = [], 0
    for name, path, digest in zip(INPUTS, paths, expected):
        if digest != INPUTS[name][0] or path.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError('Unsupported or oversized task-call input')
        raw = path.read_bytes()
        total += len(raw)
        if len(raw) > MAX_INPUT_BYTES or total > MAX_TOTAL_BYTES or hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError('Task-call input bytes differ or exceed bounds')
        (output / ('captured-vm-' + name + '.json')).write_bytes(raw)
        raw_inputs.append(raw)
    data = dict(zip(INPUTS, map(json.loads, raw_inputs)))
    for name, value in data.items():
        if value.get('sourceSha256') != INPUTS[name][1] or value.get('completed') is not True or \
                value.get('diagnosticOnly') is not True or value.get('acceptedSavingsCredit') is not False:
            raise ValueError('Task-call input producer or completion scope differs')
    context, original_abi, entries, transfers = (data[name] for name in INPUTS)
    binding = context['fileBinding']
    if context.get('contextSourceSha256') != abi.CONTEXT_PROVIDER or \
            not context['dwarf']['selectedUnitTraversalCompleted'] or context['dwarf']['nameErrors'] or \
            binding.get('externalDebugFileBound') is not True or \
            binding['library']['sha256'] != abi.LIBRARY_SHA256 or binding['externalDebug']['sha256'] != abi.DEBUG_SHA256:
        raise ValueError('Bound caller DWARF context is incomplete')
    if original_abi.get('actualTargetCallingConventionProven') is not True or \
            original_abi.get('privateFieldReadsAuthorized') is not False or \
            original_abi['inputSha256'] != dict(zip(('context', 'layout', 'entries'),
                                                   (abi.CONTEXT_SHA256, abi.LAYOUT_SHA256, abi.ENTRIES_SHA256))):
        raise ValueError('Original entry ABI scope differs')
    if any(entries.get(key) != abi.LIBRARY_SHA256 for key in
           ['expectedLibrarySha256', 'librarySha256', 'librarySha256After']) or \
            transfers.get('entriesReportSha256') != INPUTS['entries'][0] or \
            transfers.get('originalProducerSha256') != INPUTS['entries'][1]:
        raise ValueError('Saved transfer or library binding differs')
    if any(value.get(key, False) is not False for value in [context, entries, transfers]
           for key in ['actualTargetCallingConventionProven', 'actualControlFlowCoverageProven',
                       'rootCensusProven', 'codeSizeProven']):
        raise ValueError('Task-call input expands the supported proof scope')
    report['inputSha256'] = dict(zip(INPUTS, expected))
    review(context, original_abi, entries, transfers, report)
    if len(json.dumps(report).encode()) > MAX_OUTPUT_BYTES:
        raise ValueError('Task-call review exceeds output bounds')
    report['completed'] = True


def decline(report, error):
    report.update(completed=False, brokerNormalCallContinuationsProven=False,
                  callerSourceDeclarationConcordant=False,
                  error={'type': type(error).__name__, 'message': str(error)})
    for row in report.get('callerDefinitions', []):
        row['callerSourceDeclarationConcordant'] = False
    for group in report.get('callerTransfers', []):
        for row in group['transfers']:
            row['normalCallContinuationProven'] = False


def main():
    arguments = sys.argv[1:]
    output = Path(arguments[-1])
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'brokerNormalCallContinuationsProven': False, 'callerSourceDeclarationConcordant': False,
              'actualCompiledTaskLifetimeProven': False, 'actualControlFlowCoverageProven': False,
              'privateFieldReadsAuthorized': False, 'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Exact saved physical caller/source declaration and direct broker call/continuation '
                       'bindings only. All transfer/alias/error/unknown records retained. No transitive '
                       'callee, runtime kernel/probe/task generation/lifetime/private-read/root/clock/size '
                       'or compiler-saving authority.'}
    try:
        if len(arguments) != 9:
            raise ValueError('Four exact input path/hash pairs and output are required')
        inspect([Path(value) for value in arguments[:-1:2]], arguments[1:-1:2], output.parent, report)
    except Exception as error:
        decline(report, error)
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
