"""Join complete compiler syntax ranges to every authenticated saved class/debug line candidate."""
import gzip
import hashlib
import json
from pathlib import Path
import platform
import sys
import tarfile

from jvm_source_line_bindings import DENIED, SourceLineBindings
from jvm_source_syntax import SourceSyntaxIndex
from review_jvm_declaration_bindings import (
    MAX_ARCHIVE_BYTES, MAX_INPUTS, MAX_JSON_BYTES, MAX_METADATA_BYTES, MAX_ROWS, MAX_STAGED_BYTES,
    authenticate_archive, digest, download, file_identity, load_json, unique_object,
)
from review_jvm_source_lines import encode_report_record, expand_report_record, json_identity

MAX_SOURCE_JSON_BYTES = 128 * 1024 * 1024
MAX_LOGICAL_REPORT_BYTES = 4 * 1024 ** 3
MAX_COMPRESSED_REPORT_BYTES = 512 * 1024 * 1024
MAX_COMPLETION_BYTES = 128 * 1024 * 1024
MAX_CLASS_NODE_CANDIDATES = 1_000_000


def read_sources(path):
    if path.stat().st_size > MAX_SOURCE_JSON_BYTES:
        raise ValueError('Complete source specification exceeds bounds')
    return json.loads(path.read_bytes().decode('utf-8'), object_pairs_hook=unique_object)


class ReportBudget:
    def __init__(self):
        self.logical = self.compressed = 0

    def reserve_compressed(self, size):
        if self.compressed + size > MAX_COMPRESSED_REPORT_BYTES:
            raise ValueError('Complete compressed join reports exceed bounds')
        self.compressed += size

    def reserve_logical(self, size):
        if self.logical + size > MAX_LOGICAL_REPORT_BYTES:
            raise ValueError('Complete logical join reports exceed bounds')
        self.logical += size


class CompressedOutput:
    def __init__(self, target, budget):
        self.target, self.budget = target, budget

    def write(self, raw):
        self.budget.reserve_compressed(len(raw))
        return self.target.write(raw)

    def flush(self):
        self.target.flush()


class Report:
    def __init__(self, path, budget):
        self.path, self.budget = path, budget
        self.raw = path.open('xb')
        try:
            self.stream = gzip.GzipFile(fileobj=CompressedOutput(self.raw, budget), mode='wb', mtime=0)
        except BaseException:
            self.raw.close()
            if path.exists():
                path.rename(path.with_name(path.name.replace('.jsonl.gz', '.partial.jsonl.gz')))
            raise
        self.checksum, self.bytes, self.records = hashlib.sha256(), 0, 0
        self.closed = self.failed = False

    def write(self, value):
        try:
            for text in json.JSONEncoder(separators=(',', ':'), allow_nan=False).iterencode(value):
                self.write_bytes(text.encode('utf-8'))
            self.write_bytes(b'\n')
            self.records += 1
        except BaseException:
            self.failed = True
            raise

    def write_bytes(self, raw):
        self.budget.reserve_logical(len(raw))
        self.stream.write(raw)
        self.checksum.update(raw)
        self.bytes += len(raw)

    def close(self):
        if not self.closed:
            try:
                self.stream.close()
            except BaseException:
                self.failed = True
                raise
            finally:
                self.raw.close()
                self.closed = True

    def verify(self):
        self.close()
        if self.failed:
            raise ValueError('A failed report cannot admit its written prefix')
        checksum, size = hashlib.sha256(), 0
        with gzip.open(self.path, 'rb') as source:
            while raw := source.read(1024 * 1024):
                size += len(raw)
                if size > MAX_LOGICAL_REPORT_BYTES:
                    raise ValueError('Stored join report exceeds bounds')
                checksum.update(raw)
        if size != self.bytes or checksum.digest() != self.checksum.digest():
            raise ValueError('Complete stored join report does not round trip')
        return dict(file=self.path.name, records=self.records, uncompressedBytes=size,
                    uncompressedSha256=checksum.hexdigest(), completeGzipRoundTripVerified=True,
                    **file_identity(self.path))


class SyntaxLineBindings(SourceLineBindings):
    def __init__(self, specification, syntax):
        super().__init__(specification, allow_empty_packages=True)
        self.syntax = syntax
        self.pending, self.class_candidates = [], 0

    def bind(self, row):
        self.pending, self.class_candidates = [], 0
        prior_hits = set(self.hits)
        try:
            result = super().bind(row)
        except BaseException:
            self.pending = []
            self.hits = prior_hits
            raise
        entries = {}
        for method in result.get('methods', []):
            for table_index, table in enumerate(method['originalLineNumberTablesCopiedVerbatim'] or []):
                for entry_index, entry in enumerate(table):
                    entries.setdefault(entry['lineNumber'], []).append(
                        [method['methodIndex'], table_index, entry_index])
        for line in result.get('lineCandidates', []):
            line['declaredMethodLineEntries'] = entries[line['generatedLine']]
        return result

    def source_candidates(self, file, line, package):
        self.class_candidates += 1
        if self.class_candidates > MAX_CLASS_NODE_CANDIDATES:
            raise ValueError('Complete class line candidates exceed bounds')
        result = super().source_candidates(file, line, package)
        for item in result:
            source = item['sourceIndex']
            nodes = self.syntax.query(source, line)
            self.class_candidates += 1 + len(nodes)
            if self.class_candidates > MAX_CLASS_NODE_CANDIDATES:
                raise ValueError('Complete class syntax candidates exceed bounds')
            item['syntaxNodeCandidates'] = list(nodes)
            item['syntaxParsedPackageAgrees'] = self.syntax.nodes[source].package == package
            item['sourcePhaseToJvmDescriptorMappingProven'] = False
            self.pending.append((source, nodes))
        return result

    def commit(self):
        for source, nodes in self.pending:
            self.syntax.mark(source, nodes)
        self.pending = []


class Inputs:
    def __init__(self, output, scratch):
        self.output, self.scratch = output, scratch
        self.ids, self.items, self.bound_files = set(), [], []
        self.downloaded = self.staged = 0

    def authenticate(self, item, roles):
        if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id'] or item['id'] in self.ids:
            raise ValueError('Input archive identity is absent or duplicated')
        self.ids.add(item['id'])
        index = len(self.items)
        manifest_path = self.output / f'input-{index:03d}-manifest.json'
        download(item['manifest'], manifest_path, MAX_JSON_BYTES, item['manifestSha256'])
        self.bound_files.append((manifest_path, file_identity(manifest_path)))
        manifest = load_json(manifest_path)
        identity = manifest.get('archive', {}) if isinstance(manifest, dict) else {}
        if type(identity.get('size')) is not int or identity['size'] < 0 or not digest(identity.get('sha256')):
            raise ValueError('Complete archive identity is absent')
        self.downloaded += identity['size']
        if self.downloaded > MAX_ARCHIVE_BYTES:
            raise ValueError('Complete input archive set exceeds bounds')
        archive = self.scratch / f'input-{index:03d}.tar.gz'
        try:
            download(item['archive'], archive, MAX_ARCHIVE_BYTES, identity['sha256'])
            selected = [item[role] for role in roles]
            if len(set(selected)) != len(selected):
                raise ValueError('Complete input roles are not distinct')
            directory = self.scratch / str(index)
            directory.mkdir()
            paths = authenticate_archive(manifest, archive, selected, directory)
        except BaseException:
            if archive.is_file():
                actual = file_identity(archive)
                if actual != {key: identity[key] for key in ('size', 'sha256')}:
                    private = self.output / 'private-unverified-inputs'
                    private.mkdir(exist_ok=True)
                    archive.rename(private / archive.name)
                    (private / (archive.name + '.identity.json')).write_text(json.dumps({
                        'input': item['id'], 'originalArchiveAddress': item['archive'],
                        'expectedIdentity': identity, 'actualIdentity': actual,
                        'scope': 'Unverified download bytes retained privately; not accepted or published.'}) + '\n')
            raise
        self.staged += sum(path.stat().st_size for path in paths.values())
        if self.staged > MAX_STAGED_BYTES:
            raise ValueError('Complete staged input set exceeds bounds')
        record = {'inputCatalogEntryCopiedVerbatim': item, 'archive': manifest['archive'],
                  'allArchiveMembersAuthenticatedBeforeInterpretation': True,
                  'selectedMemberIdentities': {name: manifest['files'][name] for name in selected}}
        self.items.append(record)
        self.bound_files.append((archive, identity))
        self.bound_files.extend((path, manifest['files'][name]) for name, path in paths.items())
        return manifest, paths

    def verify_unchanged(self):
        for path, expected in self.bound_files:
            if file_identity(path) != {key: expected[key] for key in ('size', 'sha256')}:
                raise ValueError('An authenticated input changed during the complete join')


def review(catalog, output, scratch):
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1:
        raise ValueError('Unsupported syntax join catalog')
    metadata = catalog.get('metadata')
    if not isinstance(metadata, dict) or type(metadata.get('schema')) is not int or metadata['schema'] != 1 or \
            not isinstance(metadata.get('inputs'), list) or not 0 < len(metadata['inputs']) <= MAX_INPUTS - 2:
        raise ValueError('Complete class metadata catalog is absent or excessive')
    inputs = Inputs(output, scratch)
    syntax_item, previous_item = catalog['syntax'], catalog['previousLines']
    syntax_manifest, syntax_paths = inputs.authenticate(syntax_item,
        ('reportMember', 'sourcesMember', 'completionMember', 'compilerMember'))
    previous_manifest, previous_paths = inputs.authenticate(previous_item,
        ('sourcesMember', 'completionMember', 'catalogMember'))
    classes = []
    for item in metadata['inputs']:
        manifest, paths = inputs.authenticate(item, ('rowsMember', 'completionMember', 'inventoryMember'))
        classes.append((item, manifest, paths))
    # Complete archive authentication precedes both syntax and class interpretation.
    specification = read_sources(syntax_paths[syntax_item['sourcesMember']])
    previous_specification = read_sources(previous_paths[previous_item['sourcesMember']])
    previous_catalog = load_json(previous_paths[previous_item['catalogMember']])
    previous_completion = load_json(previous_paths[previous_item['completionMember']])
    if json_identity(previous_catalog) != json_identity(metadata) or \
            not isinstance(previous_specification, dict) or not isinstance(previous_specification.get('sources'), list) or \
            not previous_specification['sources'] or not isinstance(specification, dict) or \
            not isinstance(specification.get('sources'), list) or \
            json_identity(previous_specification['sources']) != \
            json_identity(specification['sources'][:len(previous_specification['sources'])]):
        raise ValueError('Earlier metadata catalog or complete original source prefix differs')
    previous_report = previous_manifest.get('files', {}).get(previous_item.get('reportMember'))
    declared_previous = previous_completion.get('report', {}) if isinstance(previous_completion, dict) else {}
    if not isinstance(previous_report, dict) or previous_report != {
            'size': declared_previous.get('size'), 'sha256': declared_previous.get('sha256')} or \
            declared_previous.get('file') != str(previous_item['reportMember']).rsplit('/', 1)[-1]:
        raise ValueError('Earlier complete source-line report identity differs')
    completion = load_json(syntax_paths[syntax_item['completionMember']])
    compiler = load_json(syntax_paths[syntax_item['compilerMember']])
    if completion.get('reportFile') != str(syntax_item['reportMember']).rsplit('/', 1)[-1] or \
            compiler.get('version') != '2.4.10' or \
            any(compiler.get(flag) is not False for flag in
                ('targetSourcesCompiled', 'targetExecutableLaunched', 'sourceTextExecuted')):
        raise ValueError('Syntax producer report or pinned parser identity is not admitted')
    syntax = SourceSyntaxIndex(specification, syntax_paths[syntax_item['reportMember']], completion)
    bindings = SyntaxLineBindings(specification, syntax)
    budget = ReportBudget()
    reports = []
    total, round_trips, unavailable = 0, 0, 0
    complete = syntax.complete and previous_completion.get('completed') is True
    proofs, origins = [], set()
    try:
        report = Report(output / 'syntax-line-bindings.jsonl.gz', budget)
        reports.append(report)
        for item, manifest, paths in classes:
            original_completion = load_json(paths[item['completionMember']])
            inventory = load_json(paths[item['inventoryMember']])
            if not isinstance(original_completion, dict) or not isinstance(inventory, dict):
                raise ValueError('Class completion or inventory is not an object')
            path = paths[item['rowsMember']]
            if path.stat().st_size > MAX_METADATA_BYTES:
                raise ValueError('Complete class metadata stream exceeds bounds')
            rows, valid = 0, True
            with path.open('rb') as stream:
                while raw := stream.readline(MAX_JSON_BYTES + 1):
                    if len(raw) > MAX_JSON_BYTES or not raw.strip() or rows >= MAX_ROWS:
                        raise ValueError('Complete class metadata row stream is malformed or excessive')
                    origin = {'input': item['id'], 'rowOrdinal': rows,
                              'rowByteIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
                    try:
                        row = json.loads(raw, object_pairs_hook=unique_object)
                        if not isinstance(row, dict):
                            raise ValueError('Class row is not an object')
                        for name in ('jar', 'jarSha256', 'module', 'entry', 'entryIndex', 'classSha256'):
                            origin[name] = row.get(name)
                        if not isinstance(row.get('entry'), str) or not row['entry'] or \
                                type(row.get('entryIndex')) is not int or row['entryIndex'] < 0:
                            raise ValueError('Incomplete class origin')
                        key = item['id'], row.get('jar'), row.get('module'), row['entry'], row['entryIndex']
                        jar, module = row.get('jar'), row.get('module')
                        if not digest(row.get('classSha256')) or not (
                                isinstance(jar, str) and jar and digest(row.get('jarSha256')) and module is None or
                                isinstance(module, str) and module and jar is None):
                            raise ValueError('Ambiguous class archive origin')
                        result = bindings.bind(row)
                        result['duplicateDeclarationOrigin'] = key in origins
                        if key in origins:
                            result['inputMetadataIntegrityVerified'] = False
                        origins.add(key)
                    except (ValueError, KeyError, TypeError, UnicodeError) as error:
                        bindings.pending = []
                        result = {'status': 'unavailable-class-row', 'inputMetadataIntegrityVerified': False,
                                  'error': type(error).__name__ + ': ' + str(error),
                                  **{flag: False for flag in DENIED}}
                    valid = valid and result['inputMetadataIntegrityVerified'] and result.get('reviewCompleteWithinBounds', True)
                    unavailable += sum(part['status'] != 'parsed-resolved-smap'
                                       for part in result.get('sourceDebugMaps', []))
                    record = dict(result, origin=origin)
                    encoded = encode_report_record(record)
                    if json_identity(expand_report_record(encoded)) != json_identity(record):
                        raise ValueError('Complete syntax/class annotation does not round trip')
                    report.write(encoded)
                    bindings.commit()
                    round_trips += 1
                    rows += 1
                    total += 1
            count_matches = type(inventory.get('classes')) is int and rows == inventory['classes']
            reported = original_completion.get('completed') is True and inventory.get('allDeclaredClassMetadataComplete') is True
            complete = complete and valid and count_matches and reported
            proofs.append({'input': item['id'], 'rowsReviewed': rows, 'rowCountMatchesOriginalInventory': count_matches,
                           'classAndDebugMetadataIntegrityVerified': valid,
                           'completionCopiedVerbatim': original_completion, 'inventoryCopiedVerbatim': inventory})
        report.close()
        coverage = Report(output / 'syntax-node-line-coverage.jsonl.gz', budget)
        reports.append(coverage)
        coverage.write({'record': 'schema', 'encoding': 'syntax-node-line-coverage-v1',
                        'columns': ['sourceIndex', 'nodeIndex', 'debugLineCandidatePresent'],
                        'sourceSyntaxReportIdentity': syntax.report_identity})
        for source, nodes in enumerate(syntax.nodes):
            for index, present in enumerate(nodes.hits):
                coverage.write([source, index, bool(present)])
        complete = complete and type(previous_completion.get('classRowsReviewed')) is int and \
            previous_completion['classRowsReviewed'] == total and \
            type(previous_completion.get('unavailableDebugPayloads')) is int and \
            previous_completion['unavailableDebugPayloads'] == unavailable
        report_proofs = [report.verify() for report in reports]
        inputs.verify_unchanged()
    except BaseException:
        cleanup_errors = []
        for report in reports:
            try:
                report.close()
            except BaseException as error:
                cleanup_errors.append({'file': report.path.name, 'operation': 'close',
                                       'type': type(error).__name__, 'message': str(error)})
            try:
                if report.path.exists():
                    report.path.rename(report.path.with_name(report.path.name.replace('.jsonl.gz', '.partial.jsonl.gz')))
            except OSError as error:
                cleanup_errors.append({'file': report.path.name, 'operation': 'retain-partial',
                                       'type': type(error).__name__, 'message': str(error)})
        if cleanup_errors:
            (output / 'report-cleanup-errors.json').write_text(json.dumps(cleanup_errors) + '\n')
        raise
    return {'completed': complete, 'classRowsReviewed': total, 'syntaxSources': len(syntax.nodes),
            'syntaxNodes': syntax.node_count, 'syntaxErrors': syntax.errors,
            'allSyntaxNodeRangesIndependentlyByteUtf16AndLineChecked': True,
            'allSyntaxNodesRetainedInLineCoverage': coverage.records == syntax.node_count + 1,
            'allClassRecordValuesAndOrderRoundTripped': round_trips == total,
            'unavailableDebugPayloads': unavailable, 'reports': report_proofs,
            'inputArchiveProofs': inputs.items, 'classInputCompletions': proofs,
            'allAuthenticatedInputsByteIdenticalAfterReview': True,
            'syntaxProducerCompletionCopiedVerbatim': completion, 'syntaxCompilerIdentityCopiedVerbatim': compiler,
            'earlierSourceLineCompletionCopiedVerbatim': previous_completion,
            'earlierSourceCount': len(previous_specification['sources']), 'earlierSourcePrefixUnchanged': True,
            'earlierSourceLineReportRetainedOpaque': True,
            'earlierUnavailableDebugPayloadCountMatches': previous_completion.get('unavailableDebugPayloads') == unavailable,
            'sourceInputs': bindings.finish(), 'originalSyntaxReportRemainsByteIdentical':
                file_identity(syntax_paths[syntax_item['reportMember']]) == syntax.report_identity,
            'scope': 'Every admitted original class/method, alternate entry, raw line table, parsed SMAP stratum '
                     'and overlapping/outside/missing source candidate remains present. Every compiler syntax node '
                     'is indexed and represented in the separate complete node coverage, including no-line matches. '
                     'Source positions and hashes are independently checked against complete original text; '
                     'package disagreement and all overlapping lexical ranges remain candidates. Earlier line reports '
                     'retain their earlier source universe and unavailable payloads, not relabelled as expanded coverage. '
                     'No resolved call, BCI interval, executed/inlined path, actual loader/source selection, generated '
                     'body, semantic phase, physical root, compiler cost, solver outcome or savings is inferred.',
            **{flag: False for flag in (*DENIED, 'symbolResolutionProven')}}


def write_completion(path, result):
    size = 0
    with path.open('x', encoding='utf-8') as output:
        for text in json.JSONEncoder(separators=(',', ':'), allow_nan=False).iterencode(result):
            size += len(text.encode('utf-8'))
            if size > MAX_COMPLETION_BYTES:
                raise ValueError('Complete join summary exceeds bounds')
            output.write(text)
        output.write('\n')


def main(arguments):
    if len(arguments) != 4:
        raise ValueError('A pinned catalog, output and owned staging directory are required')
    address, checksum, destination, temporary = arguments
    output, scratch = Path(destination), Path(temporary)
    result = {'completed': False, **{flag: False for flag in (*DENIED, 'symbolResolutionProven')}}
    try:
        path = output / 'input-catalog.json'
        download(address, path, MAX_JSON_BYTES, checksum)
        result.update(review(load_json(path), output, scratch))
    except (OSError, EOFError, ValueError, KeyError, TypeError, UnicodeError, tarfile.TarError) as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
    result['readerSourceSha256'] = file_identity(Path(__file__))['sha256']
    result['pythonVersion'] = platform.python_version()
    write_completion(output / 'completion.json', result)
    return 0 if result['completed'] else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
