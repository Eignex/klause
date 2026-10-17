"""Retain source-file declaration candidates without inferring JVM source attribution."""
import hashlib
import json
from pathlib import Path
import re
import sys
import tarfile
import urllib.parse
import urllib.request

MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_BYTES = 2 * 1024 ** 3
MAX_EXPANDED_BYTES = 16 * 1024 ** 3
MAX_METADATA_BYTES = 512 * 1024 * 1024
MAX_STAGED_BYTES = 2 * 1024 ** 3
MAX_REPORT_BYTES = 64 * 1024 * 1024
MAX_MEMBERS = 100_000
MAX_ROWS = 100_000
MAX_INPUTS = 8
MAX_SOURCE_MEMBERS = 10_000
DENIED = ('loadedClassSelectionProven', 'runtimeGeneratedClassesBound',
          'sourcePhaseToJvmDescriptorMappingProven', 'fullSourcePhaseCoverageProven',
          'compilerElapsedTimeProven', 'acceptedSavingsCredit')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def safe_name(value):
    return isinstance(value, str) and bool(value) and len(value) <= 4096 and '\\' not in value and \
        all(ord(char) >= 32 and ord(char) != 127 for char in value) and \
        all(part not in ('', '.', '..') for part in value.split('/'))


def digest(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def file_identity(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Input is absent or linked')
    with path.open('rb') as stream:
        return {'size': path.stat().st_size, 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}


def load_json(path):
    if path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError('JSON metadata exceeds bounds')
    return json.loads(path.read_bytes(), object_pairs_hook=unique_object)


def download(address, target, maximum, expected):
    uri = urllib.parse.urlsplit(address)
    if len(address) > 4096 or uri.scheme != 'https' or not uri.hostname or uri.username is not None or \
            uri.password is not None or '?' in address or '#' in address or not digest(expected):
        raise ValueError('Input URL or pinned identity is invalid')
    size, checksum = 0, hashlib.sha256()
    with urllib.request.urlopen(address, timeout=30) as source, target.open('xb') as output:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            if size > maximum:
                raise ValueError('Download exceeds bounds')
            checksum.update(chunk)
            output.write(chunk)
    if checksum.hexdigest() != expected:
        raise ValueError('Download differs from pinned identity')


def authenticate_archive(manifest, archive_path, selected, scratch):
    if not isinstance(manifest, dict):
        raise ValueError('Archive manifest is not an object')
    files = manifest.get('files')
    if not isinstance(files, dict) or not files or len(files) > MAX_MEMBERS:
        raise ValueError('Archive file inventory is absent or excessive')
    for name, expected in files.items():
        if not safe_name(name) or not isinstance(expected, dict) or type(expected.get('size')) is not int or \
                expected['size'] < 0 or not digest(expected.get('sha256')):
            raise ValueError('Archive file identity is invalid')
    identity = manifest.get('archive', {})
    if type(identity.get('size')) is not int or not 0 <= identity['size'] <= MAX_ARCHIVE_BYTES or \
            not digest(identity.get('sha256')) or \
            file_identity(archive_path) != {key: identity[key] for key in ('size', 'sha256')}:
        raise ValueError('Archive differs from pinned identity')
    if not set(selected) <= set(files) or any(not safe_name(name) for name in selected):
        raise ValueError('Requested complete metadata member is absent or unsafe')
    paths, seen, expanded, stored = {}, set(), 0, 0
    with tarfile.open(archive_path, 'r|gz') as archive:
        for member in archive:
            if not member.isfile() or member.name in seen or member.name not in files:
                raise ValueError('Unexpected, linked or duplicate archive member')
            seen.add(member.name)
            expected = files[member.name]
            expanded += member.size
            if member.size != expected['size'] or member.size < 0 or expanded > MAX_EXPANDED_BYTES:
                raise ValueError('Archive member size exceeds its identity or bounds')
            chosen = member.name in selected
            stored += member.size if chosen else 0
            if stored > MAX_STAGED_BYTES:
                raise ValueError('Staged declaration metadata exceeds bounds')
            if chosen and member.size > MAX_METADATA_BYTES:
                raise ValueError('Complete declaration metadata exceeds bounds')
            path = scratch / str(len(paths)) if chosen else None
            output = path.open('xb') if chosen else None
            checksum, size = hashlib.sha256(), 0
            try:
                with archive.extractfile(member) as source:
                    while chunk := source.read(1024 * 1024):
                        size += len(chunk)
                        if size > member.size:
                            raise ValueError('Archive member exceeds its header')
                        checksum.update(chunk)
                        if output is not None:
                            output.write(chunk)
            finally:
                if output is not None:
                    output.close()
            if {'size': size, 'sha256': checksum.hexdigest()} != \
                    {key: expected[key] for key in ('size', 'sha256')}:
                raise ValueError('Archive member bytes differ from manifest')
            if chosen:
                paths[member.name] = path
    if seen != set(files):
        raise ValueError('Archive membership differs from manifest')
    return paths


def source_specification(value):
    if not isinstance(value, dict) or type(value.get('schema')) is not int or value['schema'] != 1:
        raise ValueError('Unsupported source declaration schema')
    sources = value.get('sources')
    if not isinstance(sources, list) or not 0 < len(sources) <= 4096:
        raise ValueError('Source declaration inventory is absent or excessive')
    ids, member_count = set(), 0
    for source in sources:
        if not isinstance(source, dict) or not isinstance(source.get('id'), str) or not source['id'] or \
                source['id'] in ids or not safe_name(source.get('path')) or not digest(source.get('sha256')) or \
                not safe_name(source.get('sourceFile')) or '/' in source['sourceFile']:
            raise ValueError('Source identity is invalid or duplicated')
        ids.add(source['id'])
        packages = source.get('packages')
        if not isinstance(packages, list) or not packages or \
                any(not isinstance(package, str) or '/' in package or
                    (package and any(not part for part in package.split('.'))) for package in packages) or \
                len(set(packages)) != len(packages):
            raise ValueError('Explicit source packages are required')
        members, member_ids = source.get('members'), set()
        if not isinstance(members, list):
            raise ValueError('Complete requested source member inventory is required')
        member_count += len(members)
        if member_count > MAX_SOURCE_MEMBERS:
            raise ValueError('Source member inventory exceeds bounds')
        for member in members:
            if not isinstance(member, dict) or not isinstance(member.get('id'), str) or not member['id'] or \
                    member['id'] in member_ids or not isinstance(member.get('name'), str) or not member['name']:
                raise ValueError('Source member identity is invalid or duplicated')
            member_ids.add(member['id'])
    return sources


class DeclarationBindings:
    def __init__(self, specification):
        self.specification = specification
        self.sources = source_specification(specification)
        self.classes = {source['id']: [] for source in self.sources}
        self.outside = []
        self.errors = []
        self.rows = 0
        self.origins = set()
        self.report_bytes = 0
        self.account(specification)

    def account(self, value):
        self.report_bytes += len(json.dumps(value, separators=(',', ':')).encode())
        if self.report_bytes > MAX_REPORT_BYTES:
            raise ValueError('Complete retained declaration report exceeds bounds')

    def add(self, input_id, ordinal, row, raw_identity):
        self.rows += 1
        if self.rows > MAX_ROWS * MAX_INPUTS:
            raise ValueError('Declaration row inventory exceeds bounds')
        reference = {'input': input_id, 'rowOrdinal': ordinal, 'rowByteIdentity': raw_identity,
                     'jar': row.get('jar'), 'jarSha256': row.get('jarSha256'),
                     'module': row.get('module'), 'entry': row.get('entry'), 'entryIndex': row.get('entryIndex')}
        origin = (input_id, row.get('jar'), row.get('module'), row.get('entry'), row.get('entryIndex'))
        if origin in self.origins:
            failure = dict(reference, reason='Duplicate declaration origin')
            self.account(failure)
            self.errors.append(failure)
        self.origins.add(origin)
        metadata = row.get('metadata')
        if row.get('error') is not None or not isinstance(metadata, dict):
            failure = dict(reference, reason='Missing or failed class metadata', originalRow=row)
            self.account(failure)
            self.errors.append(failure)
            outside = dict(reference, sourceFiles=None, internalName=None)
            self.account(outside)
            self.outside.append(outside)
            return
        name, files, methods = metadata.get('internalName'), metadata.get('sourceFiles'), metadata.get('methods')
        if not isinstance(name, str) or not name or not isinstance(files, list) or \
                not all(isinstance(item, str) for item in files) or not isinstance(methods, list):
            raise ValueError('Malformed declared class metadata')
        for method in methods:
            if not isinstance(method, dict) or not isinstance(method.get('name'), str) or not method['name'] or \
                    not isinstance(method.get('descriptor'), str) or not method['descriptor'].startswith('(') or \
                    ')' not in method['descriptor']:
                raise ValueError('Malformed declared member identity')
        represented = False
        package = name.rsplit('/', 1)[0].replace('/', '.') if '/' in name else ''
        for source in self.sources:
            if source['sourceFile'] not in files or \
                    package not in source['packages']:
                continue
            retained = {'origin': reference, 'originalRowCopiedVerbatim': row,
                        'sourceFileAttributeAmbiguous': len(files) != 1}
            self.account(retained)
            self.classes[source['id']].append(retained)
            represented = True
        if not represented:
            outside = dict(reference, sourceFiles=files, internalName=name)
            self.account(outside)
            self.outside.append(outside)

    def finish(self):
        sources = []
        for source in self.sources:
            classes = self.classes[source['id']]
            queries, selectors, by_name = [], set(), {}
            for item in classes:
                metadata = item['originalRowCopiedVerbatim']['metadata']
                by_name.setdefault(metadata['internalName'], []).append(item['origin'])
                for method in metadata['methods']:
                    selectors.add(metadata['internalName'].replace('/', '.') + '.' + method['name'] + method['descriptor'])
            for requested in source['members']:
                candidates = []
                for item in classes:
                    metadata = item['originalRowCopiedVerbatim']['metadata']
                    for index, method in enumerate(metadata['methods']):
                        name, expected = method['name'], requested['name']
                        relation = 'exact-declared-name' if name == expected else \
                            'mangled-or-generated-name' if name.startswith((expected + '$', expected + '-')) else \
                            'accessor-name' if name == 'access$' + expected or \
                            name.startswith(('access$' + expected + '$', 'access$' + expected + '-')) else None
                        if relation is not None:
                            candidate = {'origin': item['origin'], 'methodIndex': index,
                                'selector': metadata['internalName'].replace('/', '.') + '.' + name + method['descriptor'],
                                'nameRelationship': relation}
                            self.account(candidate)
                            candidates.append(candidate)
                self.account(requested)
                queries.append({'requestedSourceMemberCopiedVerbatim': requested, 'candidates': candidates,
                                'unavailable': not candidates, 'sourceOverloadAttributionProven': False})
            sources.append({'sourceDeclarationCopiedVerbatim': source, 'wholeSourceFileClasses': classes,
                'wholeSourceFileMemberSelectors': sorted(selectors), 'sourceMemberCandidates': queries,
                'alternativeClassDeclarations': {name: values for name, values in by_name.items() if len(values) > 1},
                'sourceFileCandidateUnavailable': not classes, 'actualSourceAttributionProven': False})
        return {'schema': 1, 'completed': not self.errors, 'sourceSpecificationCopiedVerbatim': self.specification,
            'sources': sources, 'outsideRequestedSourceFiles': self.outside, 'inputClassErrors': self.errors,
            **{flag: False for flag in DENIED},
            'scope': 'Declared source-file/name candidates only. Every matching class and all of its methods, '
                     'including unqueried/private/generated and alternate declarations, remain verbatim. '
                     'Source headers are not type-erased, line/SMAP data is not interpreted, and no runtime '
                     'version or loader is selected. Descriptors are copied, never inferred. Unmatched sources '
                     'remain unavailable. No automatic ordinary-phase mapping, coverage or performance proof.'}


def read_inventory(path, input_id, bindings):
    if path.stat().st_size > MAX_METADATA_BYTES:
        raise ValueError('Complete metadata stream exceeds bounds')
    rows = 0
    with path.open('rb') as stream:
        while raw := stream.readline(MAX_JSON_BYTES + 1):
            if len(raw) > MAX_JSON_BYTES or not raw.strip() or rows >= MAX_ROWS:
                raise ValueError('Declaration row is empty, oversized or excessive')
            row = json.loads(raw, object_pairs_hook=unique_object)
            if not isinstance(row, dict):
                raise ValueError('Declaration row is not an object')
            bindings.add(input_id, rows, row, {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
            rows += 1
    return rows


def write_json(path, value):
    size = 0
    with path.open('x', encoding='utf-8') as output:
        for chunk in json.JSONEncoder(separators=(',', ':')).iterencode(value):
            size += len(chunk.encode())
            if size > MAX_REPORT_BYTES:
                raise ValueError('Complete declaration report exceeds bounds')
            output.write(chunk)
        output.write('\n')


def review(catalog, specification, output, scratch):
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1 or \
            not isinstance(catalog.get('inputs'), list) or not 0 < len(catalog['inputs']) <= MAX_INPUTS:
        raise ValueError('Declaration input catalog is malformed or excessive')
    bindings = DeclarationBindings(specification)
    authenticated, ids, downloaded, stored = [], set(), 0, 0
    for index, item in enumerate(catalog['inputs']):
        if not isinstance(item, dict):
            raise ValueError('Declaration input is not an object')
        input_id = item.get('id')
        if not isinstance(input_id, str) or not input_id or input_id in ids:
            raise ValueError('Declaration input identity is absent or duplicated')
        ids.add(input_id)
        manifest_path = output / f'input-{index:03d}-manifest.json'
        download(item['manifest'], manifest_path, MAX_JSON_BYTES, item['manifestSha256'])
        manifest = load_json(manifest_path)
        if not isinstance(manifest, dict) or not isinstance(manifest.get('archive'), dict) or \
                type(manifest['archive'].get('size')) is not int or manifest['archive']['size'] < 0:
            raise ValueError('Declaration archive identity is malformed')
        downloaded += manifest['archive']['size']
        if downloaded > MAX_ARCHIVE_BYTES:
            raise ValueError('Complete declaration archive set exceeds bounds')
        archive_path = scratch / f'input-{index:03d}.tar.gz'
        download(item['archive'], archive_path, MAX_ARCHIVE_BYTES, manifest['archive']['sha256'])
        selected = [item[key] for key in ('rowsMember', 'completionMember', 'inventoryMember')]
        directory = scratch / str(index)
        directory.mkdir()
        paths = authenticate_archive(manifest, archive_path, selected, directory)
        stored += sum(path.stat().st_size for path in paths.values())
        if stored > MAX_STAGED_BYTES:
            raise ValueError('Complete staged declaration set exceeds bounds')
        authenticated.append((item, manifest, paths))
    proofs, complete = [], True
    # Authentication of every archive precedes interpretation of any declaration row.
    for item, manifest, paths in authenticated:
        completion = load_json(paths[item['completionMember']])
        inventory = load_json(paths[item['inventoryMember']])
        if not isinstance(completion, dict) or not isinstance(inventory, dict):
            raise ValueError('Declaration completion or inventory is not an object')
        count = read_inventory(paths[item['rowsMember']], item['id'], bindings)
        reported = completion.get('completed') is True and inventory.get('allDeclaredClassMetadataComplete') is True
        count_matches = type(inventory.get('classes')) is int and count == inventory['classes']
        complete = complete and reported and count_matches
        proof = {'inputCatalogEntryCopiedVerbatim': item, 'archive': manifest['archive'],
            'allArchiveMemberBytesVerified': True, 'rowMemberIdentity': manifest['files'][item['rowsMember']],
            'streamRowCount': count, 'streamRowCountMatchesInventory': count_matches,
            'metadataCompletionReported': reported,
            'completionCopiedVerbatim': completion, 'inventoryCopiedVerbatim': inventory}
        bindings.account(proof)
        proofs.append(proof)
    result = bindings.finish()
    result['completed'] = result['completed'] and complete
    result['inputArchiveProofs'] = proofs
    write_json(output / 'declaration-bindings.json', result)
    return result['completed']


def main(arguments):
    catalog_url, catalog_sha, source_url, source_sha, destination, temporary = arguments
    output, scratch = Path(destination), Path(temporary)
    success, error = False, None
    try:
        catalog_path, source_path = output / 'input-catalog.json', output / 'source-declarations.json'
        download(catalog_url, catalog_path, MAX_JSON_BYTES, catalog_sha)
        download(source_url, source_path, MAX_JSON_BYTES, source_sha)
        success = review(load_json(catalog_path), load_json(source_path), output, scratch)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as failure:
        error = {'type': type(failure).__name__, 'message': str(failure)}
    write_json(output / 'completion.json', {'completed': success, 'error': error,
        'readerSourceSha256': file_identity(Path(__file__))['sha256'],
        **{flag: False for flag in DENIED}})
    return 0 if success else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
