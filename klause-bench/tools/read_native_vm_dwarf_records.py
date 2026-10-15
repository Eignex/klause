"""Retain bounded compiler DWARF records from the authenticated external debug file."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from collections import deque

import bind_native_vm_debug_image as binding
import native_vm_dwarf_provider as provider

DEBUG_SHA256 = 'b550e37e8cad30a15655109c6cd493df2306fde5e799f5ab1c13c85741bc9deb'
UNITS = {b'compileBroker.cpp', b'compileTask.cpp', b'nmethod.cpp', b'compilerThread.cpp'}
TYPE_NAMES = {b'CompileTask', b'nmethod', b'CodeBlob', b'CompilerThread'}
FUNCTION_NAMES = {b'invoke_compiler_on_method', b'post_compiled_method', b'total_size',
                  b'compiler_thread_loop', b'thread_entry'}
MATCH_TAGS = {'DW_TAG_class_type', 'DW_TAG_structure_type', 'DW_TAG_subprogram', 'DW_TAG_inlined_subroutine'}
REFERENCES = {'DW_AT_type', 'DW_AT_abstract_origin', 'DW_AT_specification',
              'DW_AT_object_pointer', 'DW_AT_containing_type'}
UNSUPPORTED_REFERENCE_FORMS = {'DW_FORM_ref_sig8', 'DW_FORM_ref_sup4', 'DW_FORM_ref_sup8', 'DW_FORM_GNU_ref_alt'}
MAX_UNITS = 4096
MAX_DIES = 2000000
MAX_GRAPH = 16384
MAX_SELECTED = 2048
MAX_CHILDREN = 512
MAX_DEPTH = 128
MAX_VALUE_BYTES = 16384
MAX_RETAINED_BYTES = 32 * 1024 * 1024


class RecordLimit(ValueError):
    pass


def value(raw):
    if raw is None or type(raw) in (bool, int):
        return raw
    if isinstance(raw, bytes):
        if len(raw) > MAX_VALUE_BYTES:
            raise ValueError('DWARF byte attribute exceeds bounds')
        return {'bytesHex': raw.hex()}
    if isinstance(raw, str) and len(raw.encode('utf-8')) <= MAX_VALUE_BYTES:
        return raw
    if isinstance(raw, (list, tuple)) and len(raw) <= MAX_VALUE_BYTES and \
            all(type(item) is int and 0 <= item <= 255 for item in raw):
        return {'bytesHex': bytes(raw).hex()}
    raise ValueError('Unsupported or oversized DWARF attribute value')


def attributes(die):
    if len(die.attributes) > 128:
        raise ValueError('DWARF attribute inventory exceeds bounds')
    return {name: {'form': row.form, 'value': value(row.value), 'rawValue': value(row.raw_value),
                   'offset': row.offset} for name, row in die.attributes.items()}


def key(die):
    return f'{type(die.cu).__name__}:{die.cu.cu_offset}:{die.offset}'


def names(die, seen=None, remaining=None):
    seen = set() if seen is None else seen
    remaining = [64] if remaining is None else remaining
    remaining[0] -= 1
    identity = key(die)
    if identity in seen or len(seen) >= 32 or remaining[0] < 0:
        raise ValueError('Cyclic or oversized DWARF name reference chain')
    seen = seen | {identity}
    result = []
    if 'DW_AT_name' in die.attributes:
        name = die.attributes['DW_AT_name'].value
        if not isinstance(name, bytes) or len(name) > MAX_VALUE_BYTES:
            raise ValueError('Unsupported DWARF name attribute')
        result.append({'die': identity, 'nameHex': name.hex()})
    for attribute in ['DW_AT_abstract_origin', 'DW_AT_specification']:
        if attribute in die.attributes:
            if die.attributes[attribute].form in UNSUPPORTED_REFERENCE_FORMS:
                raise ValueError('Type-unit or supplementary name references are unsupported')
            result.extend(names(die.get_DIE_from_attribute(attribute), seen, remaining))
    return result


def inventory(dwarf, report, context=None):
    graph, pending, parents, selected_units = {}, deque(), {}, []
    report.update(units=[], selectedRecords=[], graph=graph, nameErrors=[], scannedDies=0,
                  selectedUnitTraversalCompleted=False, locationExpressionsEvaluated=False,
                  requestedUnitCoverage={name.decode('ascii'): [] for name in sorted(UNITS)})
    retained = 0

    def retain(record):
        nonlocal retained
        retained += len(json.dumps(record).encode()) + 256
        if retained > MAX_RETAINED_BYTES:
            raise ValueError('Retained DWARF metadata exceeds bounds')
        return record

    if context is not None:
        context.bind_budget(retain)

    def enqueue(die):
        identity = key(die)
        if identity not in graph:
            if len(graph) >= MAX_GRAPH:
                raise RecordLimit('Referenced DWARF graph exceeds bounds')
            graph[identity] = None
            pending.append(die)
        return identity

    for unit in dwarf.iter_CUs():
        if len(report['units']) >= MAX_UNITS:
            raise ValueError('DWARF compilation-unit inventory exceeds bounds')
        top = unit.get_top_DIE()
        name = top.attributes.get('DW_AT_name')
        raw_name = name.value if name is not None else None
        selected = isinstance(raw_name, bytes) and raw_name.rsplit(b'/', 1)[-1] in UNITS
        row = retain({'offset': unit.cu_offset, 'size': unit.size, 'header': dict(unit.header),
                      'topTag': top.tag, 'attributes': attributes(top), 'selected': selected,
                      'traversalCompleted': False})
        report['units'].append(row)
        if selected:
            if context is not None:
                row['context'] = retain(context.unit(unit))
            selected_units.append((unit, row, raw_name))
    for unit, row, raw_name in selected_units:
        coverage = {'unitOffset': unit.cu_offset, 'traversalCompleted': False}
        report['requestedUnitCoverage'][raw_name.rsplit(b'/', 1)[-1].decode('ascii')].append(coverage)
        stack = []
        for die in unit.iter_DIEs():
            report['scannedDies'] += 1
            if report['scannedDies'] > MAX_DIES:
                raise ValueError('Selected DWARF traversal exceeds bounds')
            if die.cu is not unit:
                raise ValueError('Supplementary or imported DWARF units are unsupported')
            if die.tag is None:
                if not stack:
                    raise ValueError('Unbalanced DWARF child terminator')
                stack.pop()
                continue
            identity = key(die)
            parents[identity] = stack[-1] if stack else None
            if die.has_children:
                stack.append(identity)
                if len(stack) > MAX_DEPTH:
                    raise ValueError('DWARF scope depth exceeds bounds')
            if die.tag not in MATCH_TAGS:
                continue
            try:
                candidates = names(die)
            except Exception as error:
                if len(report['nameErrors']) >= MAX_SELECTED:
                    raise ValueError('DWARF name-error inventory exceeds bounds') from error
                report['nameErrors'].append(retain({'die': identity, 'tag': die.tag,
                                                   'attributes': attributes(die),
                                                   'error': {'type': type(error).__name__, 'message': str(error)}}))
                continue
            expected = TYPE_NAMES if die.tag in {'DW_TAG_class_type', 'DW_TAG_structure_type'} else FUNCTION_NAMES
            if not any(bytes.fromhex(candidate['nameHex']) in expected for candidate in candidates):
                continue
            if len(report['selectedRecords']) >= MAX_SELECTED:
                raise ValueError('Selected DWARF record inventory exceeds bounds')
            children = []
            for child in die.iter_children():
                if len(children) >= MAX_CHILDREN:
                    raise ValueError('Selected DWARF children exceed bounds')
                children.append(enqueue(child))
            report['selectedRecords'].append(retain({'die': enqueue(die), 'unitOffset': unit.cu_offset,
                                                      'nameCandidates': candidates, 'children': children}))
        if stack:
            raise ValueError('Unterminated DWARF scope tree')
        row['traversalCompleted'] = True
        coverage['traversalCompleted'] = True
    while pending:
        die = pending.popleft()
        references, failures = {}, {}
        for attribute in sorted(REFERENCES & die.attributes.keys()):
            try:
                if die.attributes[attribute].form in UNSUPPORTED_REFERENCE_FORMS:
                    raise ValueError('Type-unit or supplementary references are unsupported')
                references[attribute] = enqueue(die.get_DIE_from_attribute(attribute))
            except RecordLimit:
                raise
            except Exception as error:
                failures[attribute] = {'type': type(error).__name__, 'message': str(error)}
        node = {'offset': die.offset, 'unitOffset': die.cu.cu_offset, 'tag': die.tag,
                'attributes': attributes(die), 'parent': parents.get(key(die)),
                'parentKnownFromSelectedTraversal': key(die) in parents,
                'references': references, 'referenceErrors': failures}
        if context is not None:
            node['context'] = context.die(die)
        graph[key(die)] = retain(node)
    report['selectedUnitTraversalCompleted'] = True


def main(context_factory=None):
    library, output = map(Path, sys.argv[1:])
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'bindingSourceSha256': hashlib.sha256(Path(binding.__file__).read_bytes()).hexdigest(),
              'providerSourceSha256': hashlib.sha256(Path(provider.__file__).read_bytes()).hexdigest(),
              'fileBinding': {'library': {}, 'externalDebug': {}}, 'archive': {}, 'extraction': {}, 'provider': {},
              'actualFieldLayoutProven': False, 'actualTargetCallingConventionProven': False,
              'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Bound external-file DWARF records for four source compilation units only. All matching '
                       'type/function/inline candidates, children and referenced type/origin records retained. '
                       'No location-list/expression evaluation, target loading/invocation, register or field '
                       'reads, unique source layout/calling-convention/root/clock/size/runtime/compiler proof.'}
    try:
        library = library.resolve()
        with library.open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest() != binding.LIBRARY_SHA256:
                raise ValueError('Pinned JVM differs before DWARF preparation')
        with tempfile.TemporaryDirectory(prefix='klause-vm-dwarf-') as directory:
            temporary = Path(directory)
            archive = temporary / 'debugimage.tar.gz'
            binding.download(archive, report['archive'])
            debug = binding.extract(archive, temporary, report['extraction'])
            if report['extraction']['selectedSha256'] != DEBUG_SHA256:
                raise ValueError('External debug file differs from original bound file')
            binding.bind(library, debug, report['extraction'], report['fileBinding'])
            reader = provider.load(temporary, report['provider'])
            with debug.open('rb') as source:
                before = os.fstat(source.fileno())
                if hashlib.file_digest(source, 'sha256').hexdigest() != DEBUG_SHA256:
                    raise ValueError('Debug file changed before DWARF inspection')
                source.seek(0)
                elf = reader(source, stream_loader=None)
                if not elf.has_dwarf_info(strict=True):
                    raise ValueError('Bound external file has no DWARF info section')
                dwarf = elf.get_dwarf_info(relocate_dwarf_sections=False, follow_links=False)
                context = context_factory(dwarf) if context_factory is not None else None
                if context is not None:
                    report['contextSourceSha256'] = context.source_sha256()
                    report['contextScope'] = context.scope()
                    report['contextUnits'] = context.units
                inventory(dwarf, report.setdefault('dwarf', {}), context)
                source.seek(0)
                if hashlib.file_digest(source, 'sha256').hexdigest() != DEBUG_SHA256 or \
                        binding.symbols.identity(before) != binding.symbols.identity(os.fstat(source.fileno())) or \
                        binding.symbols.identity(before) != binding.symbols.identity(debug.stat()):
                    raise ValueError('Bound debug file changed during DWARF inspection')
            provider.verify(Path(report['provider']['directory']), report['provider'])
            binding.metadata(library, binding.LIBRARY_SHA256, report['fileBinding'].setdefault('libraryAfterDwarf', {}))
        report['completed'] = True
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
