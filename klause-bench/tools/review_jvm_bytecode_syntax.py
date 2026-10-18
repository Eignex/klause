"""Join complete syntax inputs directly to bytecode carrying authenticated debug payloads."""
import json
from pathlib import Path
import platform
import sqlite3
import sys
import tarfile

from jvm_bytecode_source_bindings import DENIED, DeclarationIndex
from jvm_source_syntax import SourceSyntaxIndex
from review_jvm_bytecode_sources import bind_previous, bytecode_rows, json_bytes
from review_jvm_declaration_bindings import MAX_INPUTS, MAX_JSON_BYTES, digest, download, file_identity, load_json
from review_jvm_source_lines import encode_report_record, expand_report_record
from review_jvm_source_syntax import Inputs, Report, ReportBudget, SyntaxLineBindings, read_sources, retain_partial_reports, write_completion


def debug_view(row, metadata):
    origin = dict(row['origin'], input=row['inputId'], classSha256=row['classSha256'])
    if metadata is None:
        return {'error': row['errorType'], 'metadata': None, **origin}
    for key in ('sourceDebugExtensionIdentities', 'sourceDebugExtensionContentsBase64'):
        if key not in metadata:
            raise ValueError('Bytecode input predates complete source-debug payload retention')
    view = dict(metadata)
    methods = []
    for method in metadata['methods']:
        code = method['code']
        if code is not None:
            code = dict(code, lineNumberTables=[[
                {key: entry[key] for key in ('startPc', 'lineNumber')} for entry in table]
                for table in code['lineNumberTables']])
        methods.append(dict(method, code=code))
    view['methods'] = methods
    return {'error': None, 'metadata': view, **origin}


def review(catalog, output, scratch):
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1:
        raise ValueError('Unsupported direct bytecode/syntax catalog')
    items = catalog['syntaxInputs']
    if not isinstance(items, list) or not 0 < len(items) < MAX_INPUTS:
        raise ValueError('Complete syntax context set is absent or excessive')
    inputs = Inputs(output, scratch)
    bytecode_item = catalog['bytecode']
    _, bytecode_paths = inputs.authenticate(bytecode_item, ('reportMember', 'completionMember'))
    paths_by_head = {}
    for item in items:
        head = item['studySourceHead']
        if not isinstance(head, str) or len(head) != 40 or not digest(head + '0' * 24) or head in paths_by_head:
            raise ValueError('Source context is invalid or duplicated')
        _, paths = inputs.authenticate(item, ('reportMember', 'sourcesMember', 'completionMember', 'compilerMember'))
        paths_by_head[head] = item, paths
    completion = load_json(bytecode_paths[bytecode_item['completionMember']])
    if completion.get('reportFile') != Path(bytecode_item['reportMember']).name or \
            completion.get('allClassEntriesVisited') is not True or \
            completion.get('allInputBytesAuthenticatedBeforeAndAfter') is not True or \
            completion.get('completeGzipRoundTripVerified') is not True:
        raise ValueError('Whole authenticated bytecode inventory is not recorded')
    index = DeclarationIndex(scratch / 'complete-declarations.sqlite')
    reports, contexts, observed_heads = [], {}, set()
    classes, joined, unavailable, debug_unavailable = 0, 0, 0, 0
    complete = completion.get('completed') is True and completion.get('allClassMetadataAvailable') is True
    budget = ReportBudget()
    try:
        for head, (item, paths) in paths_by_head.items():
            spec = read_sources(paths[item['sourcesMember']])
            if spec.get('originalKlauseHead') != head:
                raise ValueError('Whole source specification has another original study head')
            producer = load_json(paths[item['completionMember']])
            compiler = load_json(paths[item['compilerMember']])
            if producer.get('reportFile') != Path(item['reportMember']).name or compiler.get('version') != '2.4.10' or \
                    any(compiler.get(flag) is not False for flag in
                        ('targetSourcesCompiled', 'targetExecutableLaunched', 'sourceTextExecuted')):
                raise ValueError('Syntax report or parser identity differs')
            syntax = SourceSyntaxIndex(spec, paths[item['reportMember']], producer)
            complete = complete and syntax.complete
            context_index = len(contexts)
            lines = Report(output / f'syntax-line-bindings-{context_index:03d}.jsonl.gz', budget)
            reports.append(lines)
            contexts[head] = {'item': item, 'syntax': syntax, 'bindings': SyntaxLineBindings(spec, syntax),
                              'lines': lines, 'sourceProducer': producer, 'compiler': compiler, 'rows': 0}
        path = bytecode_paths[bytecode_item['reportMember']]
        for ordinal, row, metadata in bytecode_rows(path, completion):
            if metadata is not None:
                index.add(ordinal, row, metadata)
            classes += 1
        index.db.commit()
        index.check_size()
        report = Report(output / 'bytecode-source-bindings.jsonl.gz', budget)
        reports.append(report)
        report.write({'record': 'schema', 'encoding': 'bytecode-source-bindings-v1',
            'instructionLinesColumns': ['bci', 'lineEntryIndices'],
            'instructionTableLinesColumns': ['bci', 'tables'],
            'tableColumns': ['tableIndex', 'lineEntryIndices'],
            'memberCandidateColumns': ['bytecodeClassOrdinal', 'declarationIndex'],
            'bytecodeArchiveId': bytecode_item['id'], 'syntaxLineReportsEmittedInThisCapture': True,
            **{flag: False for flag in DENIED}})
        for ordinal, row, metadata in bytecode_rows(path, completion):
            for head in row['studySourceHeads']:
                if head not in contexts:
                    raise ValueError('An original bytecode context lacks complete syntax input')
                observed_heads.add(head)
                context = contexts[head]
                bindings = context['bindings']
                previous = bindings.bind(debug_view(row, metadata))
                previous['origin'] = dict(row['origin'], input=row['inputId'], rowOrdinal=ordinal,
                                          classSha256=row['classSha256'])
                source_ordinal = context['lines'].records
                bound = bind_previous(index, context['item'], ordinal, row, metadata, source_ordinal, previous)
                bound['syntaxLineReportFile'] = context['lines'].path.name
                encoded_lines, encoded_bound = encode_report_record(previous), encode_report_record(bound)
                if json_bytes(expand_report_record(encoded_lines)) != json_bytes(previous) or \
                        json_bytes(expand_report_record(encoded_bound)) != json_bytes(bound):
                    raise ValueError('Whole syntax/bytecode annotation does not round trip')
                context['lines'].write(encoded_lines)
                report.write(encoded_bound)
                bindings.commit()
                complete = complete and previous['inputMetadataIntegrityVerified'] and \
                    previous.get('reviewCompleteWithinBounds', True)
                debug_unavailable += sum(part['status'] != 'parsed-resolved-smap'
                                         for part in previous.get('sourceDebugMaps', []))
                context['rows'] += 1
                joined += 1
                unavailable += metadata is None
        if observed_heads != set(contexts):
            raise ValueError('A whole syntax context has no original bytecode input')
        context_proofs = []
        for context_index, (head, context) in enumerate(contexts.items()):
            syntax = context['syntax']
            coverage = Report(output / f'syntax-node-line-coverage-{context_index:03d}.jsonl.gz', budget)
            reports.append(coverage)
            coverage.write({'record': 'schema', 'encoding': 'syntax-node-line-coverage-v1',
                'columns': ['sourceIndex', 'nodeIndex', 'debugLineCandidatePresent'],
                'sourceSyntaxReportIdentity': syntax.report_identity})
            for source, nodes in enumerate(syntax.nodes):
                for position, present in enumerate(nodes.hits):
                    coverage.write([source, position, bool(present)])
            if coverage.records != syntax.node_count + 1:
                raise ValueError('Complete syntax node coverage differs')
            context_proofs.append({'studySourceHead': head, 'syntaxArchiveId': context['item']['id'],
                'sourceProducerCompletion': context['sourceProducer'], 'syntaxCompilerIdentity': context['compiler'],
                'syntaxSources': len(syntax.nodes), 'syntaxNodes': syntax.node_count, 'syntaxErrors': syntax.errors,
                'allSyntaxNodesRetainedInLineCoverage': True, 'classContextRows': context['rows'],
                'syntaxLineReportFile': context['lines'].path.name, 'syntaxNodeCoverageFile': coverage.path.name,
                'sourceInputs': context['bindings'].finish()})
        proofs = [item.verify() for item in reports]
        inputs.verify_unchanged()
    except BaseException:
        retain_partial_reports(reports, output)
        raise
    finally:
        index.close()
    return {'completed': complete and unavailable == 0, 'bytecodeClasses': classes,
        'sourceContextClassRows': joined, 'unavailableBytecodeClassContexts': unavailable,
        'unavailableDebugPayloads': debug_unavailable, 'allOriginalBytecodeContextRowsVisited': True,
        'allAuthenticatedInputsByteIdenticalAfterReview': True, 'allClassRecordValuesAndOrderRoundTripped': True,
        'sourceContexts': context_proofs, 'reports': proofs, 'inputArchiveProofs': inputs.items,
        'scope': 'Every original class/context, method, instruction and debug position is joined to all syntax '
                 'candidates and SMAP alternatives. Original invalid/unsupported debug payloads remain explicit. '
                 'Every original source syntax node remains in coverage, including no-line/cold/unmatched nodes. '
                 'Static symbolic owner/ancestor candidates, branches, handlers and bootstrap links remain '
                 'separate from actual loaded/generated/dispatch/semantic-phase/physical-root/compiler cost proof.',
        **{flag: False for flag in DENIED}}


def main(arguments):
    if len(arguments) != 4:
        raise ValueError('A pinned catalog, output and owned staging directory are required')
    address, checksum, destination, temporary = arguments
    output, scratch = Path(destination), Path(temporary)
    result = {'completed': False, **{flag: False for flag in DENIED}}
    try:
        path = output / 'input-catalog.json'
        download(address, path, MAX_JSON_BYTES, checksum)
        result.update(review(load_json(path), output, scratch))
    except (OSError, EOFError, ValueError, KeyError, TypeError, UnicodeError, sqlite3.Error, tarfile.TarError) as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
    result['readerSourceSha256'] = file_identity(Path(__file__))['sha256']
    result['pythonVersion'] = platform.python_version()
    write_completion(output / 'completion.json', result)
    return 0 if result['completed'] else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
