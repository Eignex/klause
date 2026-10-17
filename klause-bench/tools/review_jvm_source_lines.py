"""Authenticate complete declared-class archives before reviewing every source line."""
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tarfile

from jvm_source_line_bindings import DENIED, SourceLineBindings
from review_jvm_declaration_bindings import (
    MAX_ARCHIVE_BYTES, MAX_INPUTS, MAX_JSON_BYTES, MAX_METADATA_BYTES, MAX_ROWS, MAX_STAGED_BYTES,
    authenticate_archive, digest, download, file_identity, load_json, unique_object,
)

MAX_SOURCE_JSON_BYTES = 128 * 1024 * 1024
MAX_REPORT_BYTES = 512 * 1024 * 1024


def review(catalog, specification, output, scratch):
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1 or \
            not isinstance(catalog.get('inputs'), list) or not 0 < len(catalog['inputs']) <= MAX_INPUTS:
        raise ValueError('Malformed or excessive source-line input catalog')
    bindings = SourceLineBindings(specification)
    authenticated, ids, downloaded, staged = [], set(), 0, 0
    for index, item in enumerate(catalog['inputs']):
        if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id'] or item['id'] in ids:
            raise ValueError('Missing or duplicate source-line input identity')
        ids.add(item['id'])
        manifest_path = output / f'input-{index:03d}-manifest.json'
        download(item['manifest'], manifest_path, MAX_JSON_BYTES, item['manifestSha256'])
        manifest = load_json(manifest_path)
        identity = manifest.get('archive', {}) if isinstance(manifest, dict) else {}
        if type(identity.get('size')) is not int or identity['size'] < 0:
            raise ValueError('Missing complete input archive identity')
        downloaded += identity['size']
        if downloaded > MAX_ARCHIVE_BYTES:
            raise ValueError('Complete archive set exceeds bounds')
        archive = scratch / f'input-{index:03d}.tar.gz'
        download(item['archive'], archive, MAX_ARCHIVE_BYTES, identity['sha256'])
        selected = [item[key] for key in ('rowsMember', 'completionMember', 'inventoryMember')]
        if len(set(selected)) != 3:
            raise ValueError('Declaration input roles are not distinct')
        directory = scratch / str(index)
        directory.mkdir()
        paths = authenticate_archive(manifest, archive, selected, directory)
        staged += sum(path.stat().st_size for path in paths.values())
        if staged > MAX_STAGED_BYTES:
            raise ValueError('Complete metadata staging exceeds bounds')
        authenticated.append((item, manifest, paths))
    target = output / 'source-line-bindings.jsonl.gz'
    proofs, complete, report_bytes, total_rows, unavailable_payloads = [], True, 0, 0, 0
    report_checksum = hashlib.sha256()
    origins = set()
    try:
        with target.open('xb') as raw_output, gzip.GzipFile(fileobj=raw_output, mode='wb', mtime=0) as stream:
            for item, manifest, paths in authenticated:
                completion, inventory = load_json(paths[item['completionMember']]), load_json(paths[item['inventoryMember']])
                if not isinstance(completion, dict) or not isinstance(inventory, dict):
                    raise ValueError('Malformed declaration completion or inventory')
                rows, identities_verified, within_bounds = 0, True, True
                path = paths[item['rowsMember']]
                if path.stat().st_size > MAX_METADATA_BYTES:
                    raise ValueError('Complete metadata stream exceeds bounds')
                with path.open('rb') as source:
                    while raw := source.readline(MAX_JSON_BYTES + 1):
                        if len(raw) > MAX_JSON_BYTES or not raw.strip() or rows >= MAX_ROWS:
                            raise ValueError('Malformed or excessive complete metadata row stream')
                        origin = {'input': item['id'], 'rowOrdinal': rows,
                                  'rowByteIdentity': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
                        try:
                            row = json.loads(raw, object_pairs_hook=unique_object)
                            if not isinstance(row, dict):
                                raise ValueError('Class row is not an object')
                            origin.update({key: row.get(key) for key in
                                           ('jar', 'jarSha256', 'module', 'entry', 'entryIndex', 'classSha256')})
                            if not isinstance(row.get('entry'), str) or not row['entry'] or \
                                    type(row.get('entryIndex')) is not int or row['entryIndex'] < 0 or \
                                    not digest(row.get('classSha256')):
                                raise ValueError('Incomplete class entry origin')
                            jar, module = row.get('jar'), row.get('module')
                            if not ((isinstance(jar, str) and jar and digest(row.get('jarSha256')) and module is None) or
                                    (isinstance(module, str) and module and jar is None)):
                                raise ValueError('Ambiguous jar or module origin')
                            key = (item['id'], jar, module, row['entry'], row['entryIndex'])
                            result = bindings.bind(row)
                            result['duplicateDeclarationOrigin'] = key in origins
                            if key in origins:
                                result['inputMetadataIntegrityVerified'] = False
                            origins.add(key)
                        except (ValueError, KeyError, TypeError, UnicodeError) as error:
                            result = {'status': 'unavailable-class-row', 'inputMetadataIntegrityVerified': False,
                                      'error': type(error).__name__ + ': ' + str(error), **{flag: False for flag in DENIED}}
                        identities_verified = identities_verified and result['inputMetadataIntegrityVerified']
                        within_bounds = within_bounds and result.get('reviewCompleteWithinBounds', True)
                        unavailable_payloads += sum(part['status'] != 'parsed-resolved-smap'
                                                    for part in result.get('sourceDebugMaps', []))
                        record = dict(result, origin=origin)
                        for chunk in json.JSONEncoder(separators=(',', ':')).iterencode(record):
                            encoded = chunk.encode('utf-8')
                            report_bytes += len(encoded)
                            if report_bytes >= MAX_REPORT_BYTES:
                                raise ValueError('Complete source-line report exceeds bounds')
                            report_checksum.update(encoded)
                            stream.write(encoded)
                        report_bytes += 1
                        report_checksum.update(b'\n')
                        stream.write(b'\n')
                        rows += 1
                        total_rows += 1
                reported = completion.get('completed') is True and inventory.get('allDeclaredClassMetadataComplete') is True
                count_matches = type(inventory.get('classes')) is int and rows == inventory['classes']
                complete = complete and identities_verified and within_bounds and reported and count_matches
                proofs.append({'inputCatalogEntryCopiedVerbatim': item, 'archive': manifest['archive'],
                    'allArchiveMemberBytesVerifiedBeforeAnyClassRow': True,
                    'rowMemberIdentity': manifest['files'][item['rowsMember']], 'streamRowCount': rows,
                    'streamRowCountMatchesInventory': count_matches, 'inputMetadataIntegrityVerified': identities_verified,
                    'sourceDebugReviewWithinBounds': within_bounds,
                    'completionCopiedVerbatim': completion, 'inventoryCopiedVerbatim': inventory})
    except BaseException:
        if target.exists():
            target.rename(output / 'source-line-bindings.partial.jsonl.gz')
        raise
    # A complete report must survive decompression before it can be admitted.
    verified, verified_checksum = 0, hashlib.sha256()
    with gzip.open(target, 'rb') as stream:
        while chunk := stream.read(1024 * 1024):
            verified += len(chunk)
            verified_checksum.update(chunk)
    if verified != report_bytes or verified_checksum.digest() != report_checksum.digest():
        raise ValueError('Stored source-line report does not round trip')
    return {'completed': complete, 'classRowsReviewed': total_rows, 'unavailableDebugPayloads': unavailable_payloads,
            'uncompressedReportBytes': report_bytes, 'uncompressedReportSha256': report_checksum.hexdigest(),
            'report': dict(file=target.name, **file_identity(target)),
            'inputArchiveProofs': proofs, 'sourceInputs': bindings.finish(),
            'scope': 'Every declared class/method and original line table is retained by authenticated row identity. '
                     'All strata, overlapping rules, missing mappings and outside-source candidates remain explicit. '
                     'Input source and region bytes are checked, but their annotations do not prove selected source, '
                     'ordinary semantic phases, BCI intervals, executed/inlined paths, loader choice, roots or costs.',
            **{flag: False for flag in DENIED}}


def main(arguments):
    catalog_url, catalog_sha, source_url, source_sha, destination, temporary = arguments
    output, scratch = Path(destination), Path(temporary)
    result = {'completed': False, **{flag: False for flag in DENIED}}
    try:
        catalog_path, source_path = output / 'input-catalog.json', output / 'source-declarations.json'
        download(catalog_url, catalog_path, MAX_JSON_BYTES, catalog_sha)
        download(source_url, source_path, MAX_SOURCE_JSON_BYTES, source_sha)
        specification = json.loads(source_path.read_bytes(), object_pairs_hook=unique_object)
        result.update(review(load_json(catalog_path), specification, output, scratch))
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
    result['readerSourceSha256'] = file_identity(Path(__file__))['sha256']
    raw = json.dumps(result, separators=(',', ':')).encode('utf-8') + b'\n'
    if len(raw) > MAX_REPORT_BYTES:
        raise ValueError('Complete source-line summary exceeds bounds')
    with (output / 'completion.json').open('xb') as stream:
        stream.write(raw)
    return 0 if result['completed'] else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
