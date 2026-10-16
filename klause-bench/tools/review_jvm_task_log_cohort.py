"""Review every archived cohort log without replaying the captured JVM."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import tarfile

import review_jvm_task_log as single
import stream_jvm_task_log as streaming

MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_BYTES = 2 * 1024 ** 3
MAX_EXPANDED_BYTES = 16 * 1024 ** 3
MAX_MEMBERS = 100000
MAX_CASES = 4096
MAX_LOGS = 8192
MAX_SAVED_LOG_BYTES = 128 * 1024 * 1024
MAX_STORED_BYTES = 512 * 1024 * 1024
MAX_REVIEW_BYTES = 512 * 1024 * 1024
MAX_INDEX_BYTES = 64 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024
DENIED = single.DENIED + ('runtimeSourceBindingProven', 'expectedStudyCaseUniverseProven')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Manifest contains a duplicate JSON key')
        result[key] = value
    return result


def safe_name(name):
    return isinstance(name, str) and len(name) <= 4096 and '\\' not in name and \
        all(ord(character) >= 32 and ord(character) != 127 for character in name) and \
        all(part not in ('', '.', '..') for part in name.split('/'))


def file_identity(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Saved cohort input is absent or linked')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(CHUNK_BYTES):
            digest.update(chunk)
    return {'size': path.stat().st_size, 'sha256': digest.hexdigest()}


def gzip_identity(path, maximum):
    digest, size = hashlib.sha256(), 0
    with gzip.open(path, 'rb') as stream:
        while chunk := stream.read(CHUNK_BYTES):
            size += len(chunk)
            if size > maximum:
                raise ValueError('Retained gzip exceeds its complete uncompressed byte identity')
            digest.update(chunk)
    return {'size': size, 'sha256': digest.hexdigest()}


def catalog(manifest_path, expected):
    if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
        raise ValueError('Saved cohort manifest is absent, linked or oversized')
    raw = manifest_path.read_bytes()
    if not re.fullmatch('[0-9a-f]{64}', expected) or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Saved cohort manifest differs from pinned identity')
    manifest = json.loads(raw, object_pairs_hook=unique_object)
    files = manifest['files']
    if not isinstance(files, dict) or not files or len(files) > MAX_MEMBERS:
        raise ValueError('Saved cohort manifest has no bounded file inventory')
    for name, item in files.items():
        if not isinstance(item, dict) or not {'size', 'sha256'} <= item.keys() or not safe_name(name) or \
                type(item['size']) is not int or item['size'] < 0 or \
                not isinstance(item['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', item['sha256']):
            raise ValueError('Saved cohort member identity is unsafe or malformed')
    archive = manifest['archive']
    if not isinstance(archive, dict) or not {'name', 'size', 'sha256'} <= archive.keys() or \
            not safe_name(archive['name']) or '/' in archive['name'] or type(archive['size']) is not int or \
            not 0 <= archive['size'] <= MAX_ARCHIVE_BYTES or not isinstance(archive['sha256'], str) or \
            not re.fullmatch('[0-9a-f]{64}', archive['sha256']):
        raise ValueError('Saved cohort archive identity is malformed or oversized')
    prefixes = {match.group(1) for name in files if (match := re.match(r'(profile[1-9][0-9]*/cases/)[^/]+/', name))}
    if len(prefixes) != 1:
        raise ValueError('Saved cohort case prefix is absent or ambiguous')
    prefix = prefixes.pop()
    profile = prefix.removesuffix('cases/')
    cases = {}
    for name in files:
        if name.startswith(prefix):
            case, _ = name.removeprefix(prefix).split('/', 1)
            cases.setdefault(case, []).append(name)
    if len(cases) > MAX_CASES:
        raise ValueError('Saved cohort case inventory exceeds bounds')
    case_rows = [{'caseDirectory': case, 'memberNames': members, 'logIndices': []} for case, members in cases.items()]
    case_indices = {row['caseDirectory']: index for index, row in enumerate(case_rows)}
    logs = []
    for name, item in files.items():
        if name.rsplit('/', 1)[-1] == 'hotspot.xml':
            case_index = case_indices[name.removeprefix(prefix).split('/', 1)[0]] if name.startswith(prefix) else None
            index = len(logs)
            logs.append({'originalMember': name, 'originalIdentity': item, 'caseIndex': case_index,
                         'rawCompressedFile': None, 'rawCompressedIdentity': None,
                         'reviewCompressedFile': None, 'reviewCompressedIdentity': None,
                         'reviewUncompressedIdentity': None,
                         'reviewCompleted': False, 'allLoggedTaskStampPairsValid': False})
            if case_index is not None:
                case_rows[case_index]['logIndices'].append(index)
    if len(logs) > MAX_LOGS:
        raise ValueError('Saved cohort log inventory exceeds bounds')
    return manifest, {'manifestSha256': expected, 'archive': archive, 'profilePrefix': profile,
                      'casePrefix': prefix, 'cases': case_rows, 'logs': logs,
                      'missingCaseLogIndices': [index for index, row in enumerate(case_rows) if not row['logIndices']],
                      'allArchivedCaseDirectoriesEnumerated': True,
                      'expectedStudyCaseUniverseProven': False, 'allArchiveMemberBytesVerified': False}


class StorageBudget:
    def __init__(self, output):
        self.sizes = {path: path.stat().st_size for path in output.rglob('*')
                      if path.is_file() and path.name != 'profile.jfr'}
        self.used = sum(self.sizes.values())
        self.check()

    def check(self):
        if self.used > MAX_STORED_BYTES:
            raise ValueError('Whole cohort output storage exceeds bounds; no complete review accepted')

    def account(self, path, size=None):
        size = path.stat().st_size if size is None else size
        self.used += size - self.sizes.get(path, 0)
        self.sizes[path] = size
        self.check()


def extract_logs(manifest, archive_path, output, inventory, storage):
    if archive_path.stat().st_size > MAX_ARCHIVE_BYTES or file_identity(archive_path) != \
            {key: manifest['archive'][key] for key in ('size', 'sha256')}:
        raise ValueError('Saved cohort archive differs from pinned manifest')
    output.mkdir(parents=True, exist_ok=True)
    raw_directory = output / 'raw-logs'
    raw_directory.mkdir(exist_ok=False)
    selected = {row['originalMember']: index for index, row in enumerate(inventory['logs'])}
    seen, expanded = set(), 0
    with tarfile.open(archive_path, mode='r|gz') as archive:
        for member in archive:
            if not member.isfile() or not safe_name(member.name) or member.name in seen or len(seen) >= MAX_MEMBERS:
                raise ValueError('Saved cohort archive has linked, unsafe, duplicate or excessive members')
            seen.add(member.name)
            if member.name not in manifest['files']:
                raise ValueError('Saved cohort archive has an unexpected member')
            expected = manifest['files'][member.name]
            expanded += member.size
            if member.size != expected['size'] or member.size < 0 or expanded > MAX_EXPANDED_BYTES:
                raise ValueError('Saved cohort member size or expansion exceeds manifest bounds')
            index = selected.get(member.name)
            row = inventory['logs'][index] if index is not None else None
            path = raw_directory / f'log-{index:06d}.xml.gz' if index is not None else None
            saved = path is not None and member.size <= MAX_SAVED_LOG_BYTES
            digest, size = hashlib.sha256(), 0
            target = path.open('xb') if saved else None
            compressed = gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) if target is not None else None
            try:
                with archive.extractfile(member) as stream:
                    while chunk := stream.read(CHUNK_BYTES):
                        size += len(chunk)
                        digest.update(chunk)
                        if compressed is not None:
                            compressed.write(chunk)
                            storage.account(path, target.tell())
            finally:
                if compressed is not None:
                    compressed.close()
                    target.close()
            if size != expected['size'] or digest.hexdigest() != expected['sha256']:
                if row is not None and saved:
                    row['unverifiedRawCompressedFile'] = str(path.relative_to(output))
                raise ValueError('Saved cohort member bytes differ from manifest')
            if row is not None:
                if saved:
                    if gzip_identity(path, expected['size']) != {key: expected[key] for key in ('size', 'sha256')}:
                        raise ValueError('Retained raw gzip differs from complete original member')
                    row.update(rawCompressedFile=str(path.relative_to(output)), rawCompressedIdentity=file_identity(path))
                else:
                    row['rawRetentionLimit'] = 'Full original member remains recoverable from pinned public archive; no truncated log saved'
                if saved:
                    storage.account(path)
    if seen != set(manifest['files']):
        raise ValueError('Saved cohort archive membership differs from manifest')
    inventory['allArchiveMemberBytesVerified'] = True


def review_log(row, output, source_sha):
    report = {'completed': False, 'diagnosticOnly': True, 'sourceSha256': source_sha,
              'taskLogReviewerSourceSha256': hashlib.sha256(Path(single.__file__).read_bytes()).hexdigest(),
              'streamingReaderSourceSha256': hashlib.sha256(Path(streaming.__file__).read_bytes()).hexdigest(),
              'originalMember': row['originalMember'], 'originalIdentity': row['originalIdentity'],
              'caseIndex': row['caseIndex'], 'inputFiles': {row['originalMember']: row['originalIdentity']},
              **{key: False for key in DENIED}}
    try:
        if row['rawCompressedFile'] is None:
            raise ValueError('Original log exceeds raw retention bounds; complete original archive reference retained')
        with gzip.open(output / row['rawCompressedFile'], 'rb') as stream:
            logs, auxiliary = streaming.parse_stream(stream, row['originalIdentity'])
        report.update(single.inspect_records(logs, auxiliary), completed=True)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
    report.update({key: False for key in DENIED})
    return report


class ReportLimitExceeded(ValueError):
    pass


def write_compressed_report(path, report, storage, maximum):
    digest, size = hashlib.sha256(), 0
    buffer = bytearray()
    encoder = json.JSONEncoder(separators=(',', ':'))
    with path.open('wb') as target:
        storage.account(path, 0)
        with gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) as compressed:
            for part in encoder.iterencode(report):
                raw = part.encode('utf-8')
                for start in range(0, len(raw), CHUNK_BYTES):
                    chunk = raw[start:start + CHUNK_BYTES]
                    size += len(chunk)
                    if maximum is not None and size > maximum:
                        raise ReportLimitExceeded('Derived report exceeds streaming output bounds')
                    digest.update(chunk)
                    buffer.extend(chunk)
                    if len(buffer) >= CHUNK_BYTES:
                        compressed.write(buffer)
                        buffer.clear()
                        storage.account(path, target.tell())
            if buffer:
                compressed.write(buffer)
    storage.account(path)
    uncompressed = {'size': size, 'sha256': digest.hexdigest()}
    if gzip_identity(path, size) != uncompressed:
        raise ValueError('Retained report gzip differs from complete derived output')
    return uncompressed


def write_log_report(index, row, report, output, storage):
    report.update({key: False for key in DENIED})
    directory = output / 'log-reviews'
    directory.mkdir(exist_ok=True)
    path = directory / f'log-{index:06d}.json.gz'
    limited = False
    try:
        uncompressed = write_compressed_report(path, report, storage, MAX_REVIEW_BYTES)
    except ReportLimitExceeded:
        limited = True
        negative = {'completed': False, 'diagnosticOnly': True, 'sourceSha256': report['sourceSha256'],
                    'inputFiles': report.get('inputFiles', {}),
                    'error': {'type': 'OutputLimitExceeded', 'message': 'Derived report withheld; complete raw log retained'},
                    **{key: False for key in DENIED}}
        for key in ('taskLogReviewerSourceSha256', 'streamingReaderSourceSha256',
                    'originalMember', 'originalIdentity', 'caseIndex'):
            if key in report:
                negative[key] = report[key]
        uncompressed = write_compressed_report(path, negative, storage, None)
    row.update(reviewCompressedFile=str(path.relative_to(output)), reviewCompressedIdentity=file_identity(path),
               reviewUncompressedIdentity=uncompressed,
               reviewCompleted=report['completed'] and not limited,
               allLoggedTaskStampPairsValid=report['completed'] and
               report.get('allLoggedTaskStampPairsValid', False) and not limited)
    if limited:
        row['reviewError'] = {'type': 'OutputLimitExceeded', 'message': 'Derived report withheld; complete raw log retained'}
    elif 'error' in report:
        row['reviewError'] = report['error']
    storage.account(path)


def inspect(manifest_path, expected, archive_path, output, report):
    report.update({key: False for key in DENIED})
    manifest, inventory = catalog(manifest_path, expected)
    report['inventory'] = inventory
    storage = StorageBudget(output)
    extract_logs(manifest, archive_path, output, inventory, storage)
    for index, row in enumerate(inventory['logs']):
        derived = review_log(row, output, report['sourceSha256'])
        write_log_report(index, row, derived, output, storage)
    complete = bool(inventory['logs']) and not inventory['missingCaseLogIndices'] and \
        all(row['reviewCompleted'] for row in inventory['logs'])
    report.update(completed=True, allCaseLogReviewsCompleted=complete,
                  allLoggedTaskStampPairsValid=complete and all(row['allLoggedTaskStampPairsValid'] for row in inventory['logs']))


def write_index(path, report):
    report.update({key: False for key in DENIED})
    raw = (json.dumps(report, indent=2) + '\n').encode()
    if len(raw) > MAX_INDEX_BYTES:
        negative = {'completed': False, 'diagnosticOnly': True, 'sourceSha256': report['sourceSha256'],
                    'allCaseLogReviewsCompleted': False, 'allLoggedTaskStampPairsValid': False,
                    'inputFiles': report.get('inputFiles', {}),
                    'error': {'type': 'IndexLimitExceeded', 'message': 'Derived index withheld; full input manifest, original archive reference and any written raw/review files retained'},
                    **{key: False for key in DENIED}}
        path.write_text(json.dumps(negative, indent=2) + '\n')
        raise ValueError('Saved cohort index exceeds bounds')
    path.write_bytes(raw)


def main():
    manifest, expected, archive, output = sys.argv[1:]
    manifest, archive, output = Path(manifest), Path(archive), Path(output)
    report = {'completed': False, 'diagnosticOnly': True,
              'allCaseLogReviewsCompleted': False, 'allLoggedTaskStampPairsValid': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Every archived case directory and every hotspot.xml member, including outside-case logs, retained without family/outcome selection. Missing, malformed, oversized and unresolved logs remain explicit; expected original study case universe, runtime source binding, full compiler roots/elapsed/code size and savings unproved. Original public archive and raw manifest supply complete recovery; captured target/JVM/probes are not replayed.',
              **{key: False for key in DENIED}}
    try:
        if manifest.stat().st_size > MAX_MANIFEST_BYTES or archive.stat().st_size > MAX_ARCHIVE_BYTES:
            raise ValueError('Saved cohort input exceeds transport bounds')
        report['inputFiles'] = {str(manifest): file_identity(manifest), str(archive): file_identity(archive)}
        inspect(manifest, expected, archive, output, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.mkdir(parents=True, exist_ok=True)
        write_index(output / 'cohort-review.json', report)


if __name__ == '__main__':
    main()
