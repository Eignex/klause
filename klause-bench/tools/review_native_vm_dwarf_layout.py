"""Compare saved bound DWARF layouts with pinned source and exported metadata."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import sys

import review_native_vm_dwarf_records as candidates

READER_SHA256 = 'ba15010c801420a06249d92a0d53d0520dbb3a71ae54c690fb26756c2d14bd8d'
CONTEXT_SHA256 = 'cae6370b725cc10414ded5a1c4fc5aa0aaca9c9a5f9c462475484a6671eeaf14'
LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
DEBUG_SHA256 = 'b550e37e8cad30a15655109c6cd493df2306fde5e799f5ab1c13c85741bc9deb'
LAYOUT_SHA256 = '1240c1dafc535c2cfadd8d5892d38047f34a6852f230c259373bad87c2ccd2af'
SOURCE_REF = '520406d871955300957ef01e406ac2acd0f9b75c'
HEADER_PATH = 'src/hotspot/share/compiler/compileTask.hpp'
HEADER_SHA256 = '016c6a6c3b187b5e2a80969d631a9c44c4638103d2043f13e2a6bc4a05660489'
CODE_SIZE_ALIAS_SHA256 = 'a79a15631b7177557846e17aee7b847ff124f3dd47d6f0348617f641a7a0fb39'
MAX_CONTEXT_BYTES = 64 * 1024 * 1024
MAX_LAYOUT_BYTES = 1024 * 1024
MAX_ROWS = 16384
FIELDS = {
    '_compile_id': ('int', 87), '_osr_bci': ('int', 90), '_comp_level': ('int', 104),
    '_is_complete': ('bool', 91), '_is_success': ('bool', 92),
    '_nm_content_size': ('CodeSection::csize_t', 94),
    '_nm_total_size': ('CodeSection::csize_t', 95), '_nm_insts_size': ('CodeSection::csize_t', 96),
}
ANCHORS = {'_method': ('Method*', 88), '_compile_id': ('int', 87),
           '_osr_bci': ('int', 90), '_comp_level': ('int', 104),
           '_num_inlined_bytecodes': ('int', 105)}


def byte_text(value):
    if not isinstance(value, dict) or set(value) != {'bytesHex'} or \
            not isinstance(value['bytesHex'], str) or len(value['bytesHex']) > 32768:
        return None
    try:
        return bytes.fromhex(value['bytesHex']).decode('utf-8')
    except (ValueError, UnicodeError):
        return None


def source_location(node, expected_line):
    references = node.get('context', {}).get('fileReferences', {})
    file = references.get('DW_AT_decl_file', {})
    if file.get('status') != 'header-file-entry':
        return {'status': 'source-file-unresolved', 'reference': file}
    name = byte_text(file['file'].get('name'))
    directory = file.get('directory', {})
    if directory.get('status') == 'header-directory':
        parent = byte_text(directory.get('entry'))
    elif directory.get('status') == 'compilation-directory':
        parent = byte_text((directory.get('attribute') or {}).get('value'))
    else:
        parent = None
    path = None
    if name is not None:
        if PurePosixPath(name).is_absolute():
            path = posixpath.normpath(name)
        elif parent is not None:
            path = posixpath.normpath(posixpath.join(parent, name))
    line = candidates.scalar(node, 'DW_AT_decl_line')
    agrees = path is not None and (path == HEADER_PATH or path.endswith('/' + HEADER_PATH)) and line == expected_line
    return {'status': 'pinned-source-declaration-concordant' if agrees else 'source-declaration-unresolved',
            'path': path, 'line': line, 'expectedPath': HEADER_PATH, 'expectedLine': expected_line,
            'sourceRef': SOURCE_REF, 'sourceHeaderSha256': HEADER_SHA256}


def unique(rows, key, value):
    matches = [row for row in rows if row.get(key) == value]
    return matches[0] if len(matches) == 1 else None


def compiled_type(member, declared, layout, graph):
    storage = member.get('storage', {})
    if storage.get('status') != 'constant-storage-candidate':
        return False
    if declared == 'Method*':
        if storage['tag'] != 'DW_TAG_pointer_type' or storage['byteSize'] != layout['pointerWidth']:
            return False
        pointer = graph[storage['chain'][-1]]
        target = pointer['references'].get('DW_AT_type')
        seen = set()
        while target is not None and target not in seen and len(seen) < candidates.MAX_CHAIN:
            seen.add(target)
            node = graph.get(target)
            if node is None or node['referenceErrors']:
                return False
            if node['tag'] not in candidates.QUALIFIERS:
                return node['tag'] in {'DW_TAG_class_type', 'DW_TAG_structure_type'} and \
                    candidates.text_name(node) == 'Method'
            target = node['references'].get('DW_AT_type')
        return False
    terminal = 'int' if declared == 'CodeSection::csize_t' else declared
    exported = unique(layout['typeTable']['rows'], 'typeName', terminal)
    if exported is None or type(exported.get('isIntegerType')) is not int or exported['isIntegerType'] != 1 or \
            type(exported.get('size')) is not int or exported['size'] != storage['byteSize'] or \
            storage['tag'] != 'DW_TAG_base_type' or storage.get('name') != terminal:
        return False
    return storage.get('encoding') == (2 if terminal == 'bool' else 5) and \
        (terminal == 'bool' or type(exported.get('isUnsigned')) is int and exported['isUnsigned'] == 0)


def check_member(name, declared, line, owner, source, layout):
    matches = [row for row in owner['members'] if row['name'] == name]
    result = {'name': name, 'declaredSourceType': declared, 'members': matches,
              'status': 'member-unresolved', 'actualFieldLayoutProven': False}
    if len(matches) != 1 or matches[0]['status'] != 'constant-member-candidate':
        return result
    member = matches[0]
    node = source['dwarf']['graph'][member['die']]
    if node.get('parentKnownFromSelectedTraversal') is not True or \
            node.get('parent') != owner['originalSelectedRecord']['die']:
        result['status'] = 'member-owner-unresolved'
        return result
    location = source_location(node, line)
    result['sourceDeclaration'] = location
    if location['status'] != 'pinned-source-declaration-concordant' or \
            not compiled_type(member, declared, layout, source['dwarf']['graph']):
        return result
    result.update(status='source-and-compiled-type-concordant', offset=member['constantOffset'],
                  byteSize=member['storage']['byteSize'], encoding=member['storage']['encoding'])
    return result


def validate_layout(layout):
    if layout.get('completed') is not True or layout.get('diagnosticOnly') is not True or \
            layout.get('acceptedSavingsCredit') is not False or layout.get('librarySha256') != LIBRARY_SHA256 or \
            type(layout.get('pointerWidth')) is not int or layout['pointerWidth'] != 8:
        raise ValueError('Original exported layout identity or scope differs')
    for name in ['structTable', 'typeTable', 'intConstantTable']:
        table = layout.get(name, {})
        if table.get('terminated') is not True or not isinstance(table.get('rows'), list) or \
                len(table['rows']) > MAX_ROWS or any(not isinstance(row, dict) for row in table['rows']):
            raise ValueError('Original exported metadata table is incomplete or exceeds bounds')


def review(source, layout, report):
    validate_layout(layout)
    candidates.review(source, report)
    report.update(layoutDefinitions=[], fieldLayoutConcordance={}, actualFieldLayoutProven=False,
                  actualTargetCallingConventionProven=False, privateFieldReadsAuthorized=False,
                  pinnedSource={'ref': SOURCE_REF, 'header': HEADER_PATH, 'headerSha256': HEADER_SHA256,
                                'codeSizeAliasHeader': 'src/hotspot/share/asm/codeBuffer.hpp',
                                'codeSizeAliasHeaderSha256': CODE_SIZE_ALIAS_SHA256,
                                'codeSizeAliasLine': 95, 'codeSizeAlias': 'typedef int csize_t;'},
                  independentExportedOwnerRows=[row for row in layout['typeTable']['rows']
                                                if row.get('typeName') == 'CompileTask'])
    exported_owner = unique(layout['typeTable']['rows'], 'typeName', 'CompileTask')
    size = exported_owner.get('size') if exported_owner is not None else None
    if type(size) is not int or not 0 < size <= 1024 * 1024:
        size = None
    definitions = [row for row in report['typeCandidates'] if 'CompileTask' in row['names'] and
                   row['declarationStatus'] != 'declaration']
    coverage = source['dwarf']['requestedUnitCoverage']
    complete_coverage = set(coverage) == {'compileBroker.cpp', 'compileTask.cpp', 'nmethod.cpp', 'compilerThread.cpp'} and \
        all(rows and all(row.get('traversalCompleted') is True for row in rows) for rows in coverage.values())
    for owner in definitions:
        node = owner['record']
        context = source.get('contextUnits', {}).get(node.get('context', {}).get('unitTopDie'), {})
        indexed = unique(source['dwarf'].get('units', []), 'offset', node.get('unitOffset'))
        scoped = node.get('parentKnownFromSelectedTraversal') is True and \
            context.get('topDie') is not None and node.get('parent') == context['topDie'] and \
            node.get('unitOffset') == context.get('unitOffset') and \
            indexed is not None and indexed.get('topTag') == 'DW_TAG_compile_unit'
        problems = []
        if not complete_coverage or source['dwarf']['nameErrors']:
            problems.append('Compiler-unit coverage or original name resolution is incomplete')
        if owner['declarationStatus'] != 'definition' or owner['ownerNameStatus'] != 'single-owner' or \
                node['referenceErrors'] or not scoped:
            problems.append('Owner declaration, name, references or global source scope unresolved')
        if size is None or owner['byteSizeCandidate'] != size:
            problems.append('Owner size does not equal the unique exported type size')
        anchors = []
        for name, (declared, line) in ANCHORS.items():
            row = check_member(name, declared, line, owner, source, layout)
            exported = [item for item in layout['structTable']['rows']
                        if item.get('typeName') == 'CompileTask' and item.get('fieldName') == name]
            row['exportedRows'] = exported
            if row['status'] != 'source-and-compiled-type-concordant' or len(exported) != 1 or \
                    type(exported[0].get('offset')) is not int or exported[0]['offset'] != row.get('offset') or \
                    type(exported[0].get('isStatic')) is not int or exported[0]['isStatic'] != 0 or \
                    exported[0].get('typeString') != declared:
                problems.append('Exported anchor unresolved: ' + name)
            anchors.append(row)
        fields = [check_member(name, declared, line, owner, source, layout)
                  for name, (declared, line) in FIELDS.items()]
        spans = {}
        for row in [*anchors, *fields]:
            if row['status'] == 'source-and-compiled-type-concordant':
                spans[row['name']] = (row['offset'], row['offset'] + row['byteSize'])
        names = list(spans)
        for index, first in enumerate(names):
            for second in names[index + 1:]:
                if max(spans[first][0], spans[second][0]) < min(spans[first][1], spans[second][1]):
                    problems.append('Distinct declared fields overlap: ' + first + ', ' + second)
        report['layoutDefinitions'].append({'ownerDie': owner['originalSelectedRecord']['die'],
                                            'ownerByteSize': owner['byteSizeCandidate'],
                                            'problems': problems, 'anchors': anchors, 'fields': fields})
    for name in FIELDS:
        observations = []
        signatures = []
        for owner in report['layoutDefinitions']:
            field = next(row for row in owner['fields'] if row['name'] == name)
            observations.append({'ownerDie': owner['ownerDie'], 'ownerProblems': owner['problems'], 'field': field})
            signatures.append(None if owner['problems'] or field['status'] != 'source-and-compiled-type-concordant'
                              else (owner['ownerByteSize'], field['offset'], field['byteSize'], field['encoding']))
        agrees = bool(signatures) and signatures[0] is not None and all(row == signatures[0] for row in signatures)
        report['fieldLayoutConcordance'][name] = {
            'status': 'bound-dwarf-export-source-concordant' if agrees else 'incomplete-or-conflicting',
            'definitions': observations, 'actualFieldLayoutProven': agrees,
            'proofScope': 'Pinned library/debug files and selected compiler units only; no entry ABI or live field read'}
    report['actualFieldLayoutProven'] = all(row['actualFieldLayoutProven'] for row in report['fieldLayoutConcordance'].values())


def inspect(context_path, context_sha256, layout_path, layout_sha256, output, report):
    inputs = []
    for path, expected, maximum, target in [
            (context_path, context_sha256, MAX_CONTEXT_BYTES, 'captured-vm-dwarf-context.json'),
            (layout_path, layout_sha256, MAX_LAYOUT_BYTES, 'captured-vm-exported-layout.json')]:
        if path.stat().st_size > maximum:
            raise ValueError('Saved layout review input exceeds bounds')
        raw = path.read_bytes()
        if len(raw) > maximum or hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('Saved layout review input hash differs')
        (output / target).write_bytes(raw)
        inputs.append(raw)
    if layout_sha256 != LAYOUT_SHA256:
        raise ValueError('Exported layout is not the supported original capture')
    source, layout = map(json.loads, inputs)
    if source.get('sourceSha256') != READER_SHA256 or source.get('contextSourceSha256') != CONTEXT_SHA256 or \
            source.get('completed') is not True or source.get('diagnosticOnly') is not True or \
            source.get('acceptedSavingsCredit') is not False or \
            source.get('dwarf', {}).get('selectedUnitTraversalCompleted') is not True:
        raise ValueError('Saved context producer, completion or scope differs')
    binding = source.get('fileBinding', {})
    if binding.get('externalDebugFileBound') is not True or binding.get('library', {}).get('sha256') != LIBRARY_SHA256 or \
            binding.get('externalDebug', {}).get('sha256') != DEBUG_SHA256:
        raise ValueError('Saved context is not bound to the supported native files')
    for section in [source, source.get('contextScope', {})]:
        for name in ['actualFieldLayoutProven', 'actualTargetCallingConventionProven',
                     'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven', 'acceptedSavingsCredit']:
            if section.get(name) is not False:
                raise ValueError('Saved context changes original proof scope')
    if source['dwarf'].get('locationExpressionsEvaluated') is not False or \
            source['contextScope'].get('locationExpressionsEvaluated') is not False:
        raise ValueError('Saved context evaluated locations outside its contract')
    report.update(contextSha256=context_sha256, exportedLayoutSha256=layout_sha256,
                  originalReaderSha256=READER_SHA256, originalContextReaderSha256=CONTEXT_SHA256)
    review(source, layout, report)
    encoded = json.dumps(report, separators=(',', ':')).encode()
    if len(encoded) > candidates.MAX_REVIEW_BYTES:
        report['actualFieldLayoutProven'] = False
        for row in report['fieldLayoutConcordance'].values():
            row['actualFieldLayoutProven'] = False
        raise ValueError('Layout concordance report exceeds bounds')
    report['completed'] = True


def main():
    context_path, context_sha256, layout_path, layout_sha256, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'candidateReviewSourceSha256': hashlib.sha256(Path(candidates.__file__).read_bytes()).hexdigest(),
              'actualFieldLayoutProven': False, 'actualTargetCallingConventionProven': False,
              'privateFieldReadsAuthorized': False, 'actualControlFlowCoverageProven': False,
              'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Saved bound-file layout, pinned source declaration and original exported metadata '
                       'concordance only. Every selected candidate and failure retained. No native loading, '
                       'location evaluation, entry ABI, live field read, root/code-size/runtime/compiler proof.'}
    try:
        inspect(Path(context_path), context_sha256, Path(layout_path), layout_sha256, output.parent, report)
    except Exception as error:
        report['completed'] = False
        report['actualFieldLayoutProven'] = False
        for row in report.get('fieldLayoutConcordance', {}).values():
            row['actualFieldLayoutProven'] = False
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
