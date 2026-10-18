"""Authenticate complete bytecode and source-candidate archives before joining every class."""
import gzip
import hashlib
import json
from pathlib import Path
import platform
import sqlite3
import sys
import tarfile

from jvm_bytecode_source_bindings import COLUMNS, DENIED, DeclarationIndex, Symbols, bind_method, expand, origin_key
from review_jvm_declaration_bindings import MAX_INPUTS, MAX_JSON_BYTES, digest, file_identity, load_json, unique_object
from review_jvm_source_lines import encode_report_record, expand_report_record
from review_jvm_source_syntax import Inputs, Report, ReportBudget, write_completion

MAX_ROW_BYTES = 128 * 1024 * 1024
MAX_LOGICAL_BYTES = 4 * 1024 ** 3
MAX_CLASSES = 100_000


def json_bytes(value):
    return json.dumps(value, separators=(',', ':'), allow_nan=False).encode('utf-8')


def checked_records(path, proof):
    if file_identity(path) != {key: proof[key] for key in ('size', 'sha256')}:
        raise ValueError('Compressed report identity differs')
    checksum, size, count = hashlib.sha256(), 0, 0
    with gzip.open(path, 'rb') as stream:
        while raw := stream.readline(MAX_ROW_BYTES + 1):
            size += len(raw)
            count += 1
            if len(raw) > MAX_ROW_BYTES or size > MAX_LOGICAL_BYTES or not raw.strip():
                raise ValueError('Complete report exceeds bounds or has an empty record')
            checksum.update(raw)
            yield count - 1, json.loads(raw, object_pairs_hook=unique_object)
    if size != proof['uncompressedBytes'] or checksum.hexdigest() != proof['uncompressedSha256'] or \
            count != proof['records']:
        raise ValueError('Logical report identity or census differs')


def bytecode_proof(completion):
    return {'size': completion['reportBytes'], 'sha256': completion['reportSha256'],
            'uncompressedBytes': completion['uncompressedReportBytes'],
            'uncompressedSha256': completion['uncompressedReportSha256'], 'records': completion['records']}


def bytecode_rows(path, completion):
    count, ended = 0, False
    for ordinal, row in checked_records(path, bytecode_proof(completion)):
        if ordinal == 0:
            if row != {'record': 'schema', 'encoding': 'bytecode-columns-v1', 'columns': COLUMNS}:
                raise ValueError('Bytecode report schema differs')
            continue
        if not isinstance(row, dict) or ended:
            raise ValueError('Bytecode report has a malformed row or trailing records')
        if row.get('record') == 'inventory-end':
            ended = True
            if count != row.get('classes') or count != completion['classes'] or any(
                    value != completion.get(key) for key, value in row.items() if key != 'record'):
                raise ValueError('Complete bytecode census differs from producer completion')
            continue
        if row.get('record') != 'class' or count >= MAX_CLASSES:
            raise ValueError('Bytecode report has an unknown or excessive class record')
        origin_key(row['origin'])
        metadata = row['metadata']
        if metadata is not None:
            metadata = expand(metadata)
            if row.get('errorType') is not None or row.get('entryBytesAmbiguous') is not False or \
                    not digest(row.get('classSha256')) or metadata['identity']['sha256'] != row['classSha256']:
                raise ValueError('Bytecode class identity is inconsistent')
            Symbols(metadata)
        yield count, row, metadata
        count += 1
    if not ended or count == 0:
        raise ValueError('Bytecode report is incomplete')


def match_key(origin):
    key = json.loads(origin_key(origin))
    # A runtime provider's traversal ordinal is not an identity shared between observers.
    if origin.get('module') is not None:
        key[-1] = None
    return json_bytes(key).decode('utf-8')


def report_proof(completion, member):
    matches = [proof for proof in completion['reports'] if proof.get('file') == Path(member).name]
    if len(matches) != 1 or matches[0].get('completeGzipRoundTripVerified') is not True:
        raise ValueError('A complete unique producer report proof is required')
    return matches[0]


def index_syntax(index, item, paths, completion):
    report = paths[item['reportMember']]
    count = 0
    for ordinal, encoded in checked_records(report, report_proof(completion, item['reportMember'])):
        row = expand_report_record(encoded)
        origin = row['origin']
        if origin['rowOrdinal'] < 0 or not digest(origin.get('classSha256')):
            raise ValueError('Source-candidate class identity is absent')
        index.db.execute('INSERT INTO syntax VALUES (?,?,?,?,?,?)', (item['id'], origin['input'],
            match_key(origin), ordinal, json_bytes(row), 0))
        count += 1
        index.check_size()
    if count != completion['classRowsReviewed']:
        raise ValueError('Whole syntax class census differs')
    proof = report_proof(completion, item['coverageMember'])
    coverage, source_index, node_index = 0, 0, 0
    for ordinal, row in checked_records(paths[item['coverageMember']], proof):
        if ordinal == 0:
            if row.get('record') != 'schema' or row.get('encoding') != 'syntax-node-line-coverage-v1' or \
                    row.get('columns') != ['sourceIndex', 'nodeIndex', 'debugLineCandidatePresent']:
                raise ValueError('Syntax node coverage schema differs')
        else:
            if not isinstance(row, list) or len(row) != 3 or type(row[0]) is not int or \
                    type(row[1]) is not int or row[0] < 0 or row[1] < 0 or type(row[2]) is not bool:
                raise ValueError('Syntax node coverage row is malformed')
            if row[0] == source_index + 1 and row[1] == 0:
                source_index, node_index = row[0], 0
            if row[:2] != [source_index, node_index]:
                raise ValueError('Syntax node coverage order or complete source indexes differ')
            node_index += 1
            coverage += 1
    if coverage != completion['syntaxNodes'] or source_index + 1 != completion['syntaxSources']:
        raise ValueError('Complete syntax node coverage census differs')
    return count


def bind_class(index, item, mapping, ordinal, row, metadata):
    key = (item['id'], mapping['syntaxInputId'], match_key(row['origin']))
    found = index.db.execute('SELECT ordinal, body, uses FROM syntax WHERE context=? AND input=? AND origin=?', key).fetchall()
    if len(found) != 1:
        raise ValueError('A bytecode entry does not have exactly one original debug entry')
    source_ordinal, raw, uses = found[0]
    previous = json.loads(raw)
    if uses != 0 or previous['origin']['classSha256'] != row['classSha256']:
        raise ValueError('Debug entry reuse or original class bytes differ')
    index.db.execute('UPDATE syntax SET uses=1 WHERE context=? AND input=? AND origin=?', key)
    base = {'bytecodeClassOrdinal': ordinal, 'studySourceHead': item['studySourceHead'],
            'syntaxArchiveId': item['id'], 'syntaxClassOrdinal': source_ordinal,
            'syntaxOrigin': previous['origin'], 'expandedSyntaxRecordSha256': hashlib.sha256(json_bytes(previous)).hexdigest(),
            **{flag: False for flag in DENIED}}
    if metadata is None:
        return dict(base, status='bytecode-metadata-unavailable', methods=[], references=[])
    if previous.get('status') != 'reviewed' or previous.get('internalName') != metadata['internalName'] or \
            previous.get('sourceFilesCopiedVerbatim') != metadata['sourceFiles'] or \
            len(previous.get('methods', [])) != len(metadata['methods']):
        raise ValueError('Complete source/debug and bytecode declarations differ')
    methods = [bind_method(method, prior) for method, prior in zip(metadata['methods'], previous['methods'])]
    by_line = {}
    expected = {}
    for method in methods:
        for entry in method['lineEntries']:
            expected.setdefault(entry['lineNumber'], []).append(
                [method['methodIndex'], entry['tableIndex'], entry['entryIndex']])
    for position, candidate in enumerate(previous['lineCandidates']):
        line = candidate['generatedLine']
        if line in by_line or candidate['declaredMethodLineEntries'] != expected.get(line):
            raise ValueError('Original line-to-method references differ')
        by_line[line] = position
    if set(by_line) != set(expected):
        raise ValueError('Source/debug report omits method line points')
    for method in methods:
        for entry in method['lineEntries']:
            entry['syntaxLineCandidateIndex'] = by_line[entry['lineNumber']]
    symbols = Symbols(metadata)
    references = []
    candidate_count = 0
    for entry in metadata['constantPool']:
        if entry['tag'] == 7:
            name = symbols.class_name(entry['index'])
            candidates = index.candidates(item['studySourceHead'], name)
            record = {'poolIndex': entry['index'], 'kind': 'class', 'owner': name, 'candidates': candidates}
        elif entry['tag'] in (9, 10, 11):
            owner, name, descriptor = symbols.member(entry)
            kind = 'field' if entry['tag'] == 9 else 'method'
            candidates = index.candidates(item['studySourceHead'], owner, kind, name, descriptor)
            record = {'poolIndex': entry['index'], 'kind': kind, 'owner': owner,
                      'name': name, 'descriptor': descriptor, 'candidates': candidates}
        else:
            continue
        candidate_count += len(candidates['classOrdinals']) + len(candidates['memberCandidates'])
        if candidate_count > 1_000_000:
            raise ValueError('Whole class declaration candidate inventory exceeds bounds')
        references.append(record)
    return dict(base, status='joined-candidates', internalName=metadata['internalName'],
                classIdentity=metadata['identity'], superclass=metadata['superclass'], interfaces=metadata['interfaces'],
                methods=methods, references=references, bootstrapMethods=metadata['bootstrapMethods'],
                originalPoolAndInstructionOperandsRetainedByAuthenticatedReport=True,
                unavailableDebugPayloadsRetainedByAuthenticatedSyntaxReport=True)


def review(catalog, output, scratch):
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1:
        raise ValueError('Unsupported bytecode/source audit catalog')
    syntax_items = catalog['syntaxJoins']
    if not isinstance(syntax_items, list) or not 0 < len(syntax_items) < MAX_INPUTS:
        raise ValueError('Whole syntax context set is absent or excessive')
    inputs = Inputs(output, scratch)
    bytecode_item = catalog['bytecode']
    _, bytecode_paths = inputs.authenticate(bytecode_item, ('reportMember', 'completionMember'))
    syntax_inputs = []
    mappings = {}
    for item in syntax_items:
        head = item['studySourceHead']
        if not isinstance(head, str) or len(head) != 40 or not digest(head + '0' * 24):
            raise ValueError('Original source context head is invalid')
        _, paths = inputs.authenticate(item, ('reportMember', 'coverageMember', 'completionMember'))
        syntax_inputs.append((item, paths))
        if not isinstance(item['bytecodeInputs'], list) or not item['bytecodeInputs']:
            raise ValueError('Whole bytecode-to-debug input mapping is absent')
        for mapping in item['bytecodeInputs']:
            key = (mapping['bytecodeInputId'], head)
            if key in mappings or not isinstance(mapping['syntaxInputId'], str) or not mapping['syntaxInputId']:
                raise ValueError('Duplicate or invalid bytecode/source context mapping')
            mappings[key] = (item, mapping)
    completion = load_json(bytecode_paths[bytecode_item['completionMember']])
    if completion['reportFile'] != Path(bytecode_item['reportMember']).name or \
            completion.get('completeGzipRoundTripVerified') is not True or \
            completion.get('allClassEntriesVisited') is not True or \
            completion.get('allInputBytesAuthenticatedBeforeAndAfter') is not True:
        raise ValueError('Complete authenticated bytecode traversal is not recorded')
    index = DeclarationIndex(scratch / 'complete-declarations.sqlite')
    index.db.executescript('''CREATE TABLE syntax
        (context TEXT, input TEXT, origin TEXT, ordinal INTEGER, body BLOB, uses INTEGER);
        CREATE UNIQUE INDEX syntax_origin ON syntax(context, input, origin);''')
    reports, rows, joined, unavailable, syntax_count = [], 0, 0, 0, 0
    observed_mappings = set()
    complete = completion.get('completed') is True and completion.get('allClassMetadataAvailable') is True
    try:
        for item, paths in syntax_inputs:
            previous = load_json(paths[item['completionMember']])
            complete = complete and previous.get('completed') is True
            syntax_count += index_syntax(index, item, paths, previous)
        bytecode_path = bytecode_paths[bytecode_item['reportMember']]
        for ordinal, row, metadata in bytecode_rows(bytecode_path, completion):
            if metadata is not None:
                index.add(ordinal, row, metadata)
            rows += 1
        index.db.commit()
        index.check_size()
        report = Report(output / 'bytecode-source-bindings.jsonl.gz', ReportBudget())
        reports.append(report)
        report.write({'record': 'schema', 'encoding': 'bytecode-source-bindings-v1',
                      'instructionLinesColumns': ['bci', 'lineEntryIndices'],
                      'memberCandidateColumns': ['bytecodeClassOrdinal', 'declarationIndex'],
                      'bytecodeArchiveId': bytecode_item['id'], 'bytecodeReport': bytecode_proof(completion),
                      **{flag: False for flag in DENIED}})
        for ordinal, row, metadata in bytecode_rows(bytecode_path, completion):
            for head in row['studySourceHeads']:
                key = row['inputId'], head
                if key not in mappings:
                    raise ValueError('An original bytecode study context has no complete syntax mapping')
                item, mapping = mappings[key]
                observed_mappings.add(key)
                bound = bind_class(index, item, mapping, ordinal, row, metadata)
                encoded = encode_report_record(bound)
                if json_bytes(expand_report_record(encoded)) != json_bytes(bound):
                    raise ValueError('Complete bytecode/source annotation does not round trip')
                report.write(encoded)
                joined += 1
                unavailable += metadata is None
        unused = index.db.execute('SELECT count(*) FROM syntax WHERE uses!=1').fetchone()[0]
        if unused or observed_mappings != set(mappings) or joined != syntax_count:
            raise ValueError('The complete source/debug and bytecode context inventories are not bijective')
        proof = report.verify()
        inputs.verify_unchanged()
    except BaseException:
        for report in reports:
            try:
                report.close()
            finally:
                if report.path.exists():
                    report.path.rename(report.path.with_name(report.path.name.replace('.jsonl.gz', '.partial.jsonl.gz')))
        raise
    finally:
        index.close()
    return {'completed': complete and unavailable == 0, 'bytecodeClasses': rows,
            'sourceContextClassRows': joined, 'originalSyntaxClassRows': syntax_count,
            'unavailableBytecodeClassContexts': unavailable, 'allOriginalContextRowsJoinedExactlyOnce': True,
            'allAuthenticatedInputsByteIdenticalAfterReview': True, 'report': proof,
            'inputArchiveProofs': inputs.items,
            'scope': 'Every original class, method, line point and instruction position is retained by identity. '
                     'All duplicate debug positions and unavailable payloads remain explicit. Source nodes and '
                     'cold/unmatched coverage retain their original authenticated reports. Symbol candidates '
                     'include every matching declared owner and ancestor in the original study context, without '
                     'JVM lookup precedence, signature-polymorphic resolution or receiver/generated-body selection. '
                     'Pool/bootstrap/branch/handler operands remain in the complete original bytecode report. '
                     'No source semantic phase, compiler physical root, cost or solver control is inferred.',
            **{flag: False for flag in DENIED}}


def main(arguments):
    from review_jvm_declaration_bindings import download
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
