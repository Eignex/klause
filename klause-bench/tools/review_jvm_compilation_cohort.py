"""Authenticate saved cohorts before streaming their original JFR compilation records."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import tarfile
import tempfile
import threading

import jvm_compilation_events as events
import review_jvm_task_log_cohort as cohort
import stream_jvm_task_log as xml

MAX_SELECTED_BYTES = 8 * 1024 ** 3
MAX_RECORDING_BYTES = 128 * 1024 * 1024
MAX_METADATA_BYTES = 32 * 1024 * 1024
MAX_STDERR_BYTES = 1024 * 1024
JFR_SECONDS = 300
METADATA_NAMES = ('cases.json', 'compilation-families.json', 'compilation.jfc', 'build-provenance.json',
                  'candidate-provenance.json', 'control-provenance.json', 'study-builds.json',
                  'completion.json', 'selection.json', 'java-flags.stdout', 'source-check-exits.json',
                  'boolean-source-checks.json', 'linear-source-checks.json', 'linear-source-checks.jsonl')


def authenticated_inputs(manifest, archive_path, scratch, inventory):
    if archive_path.stat().st_size > cohort.MAX_ARCHIVE_BYTES or cohort.file_identity(archive_path) != \
            {key: manifest['archive'][key] for key in ('size', 'sha256')}:
        raise ValueError('Saved JFR archive differs from pinned identity')
    prefix = inventory['profilePrefix']
    selected, seen, expanded, stored = {}, set(), 0, 0
    with tarfile.open(archive_path, 'r|gz') as archive:
        for member in archive:
            if not member.isfile() or not cohort.safe_name(member.name) or member.name in seen or \
                    member.name not in manifest['files'] or len(seen) >= cohort.MAX_MEMBERS:
                raise ValueError('Saved JFR archive has unsafe, duplicate or unexpected members')
            seen.add(member.name)
            expected = manifest['files'][member.name]
            expanded += member.size
            if member.size != expected['size'] or member.size < 0 or expanded > cohort.MAX_EXPANDED_BYTES:
                raise ValueError('Saved JFR archive member size or expansion exceeds bounds')
            case_record = member.name.startswith(inventory['casePrefix']) and \
                member.name.endswith(('.jfr', '/hotspot.xml', '/measurement.json'))
            metadata = member.name in {prefix + name for name in METADATA_NAMES}
            chosen = case_record or metadata
            maximum = MAX_RECORDING_BYTES if member.name.endswith(('.jfr', '/hotspot.xml')) else MAX_METADATA_BYTES
            if chosen and member.size > maximum:
                raise ValueError('Selected original JFR/XML/metadata member exceeds complete input bounds')
            stored += member.size if chosen else 0
            if stored > MAX_SELECTED_BYTES:
                raise ValueError('Complete selected JFR inputs exceed scratch bounds')
            path = scratch / (str(len(selected)) + Path(member.name).suffix) if chosen else None
            digest, size = hashlib.sha256(), 0
            target = path.open('xb') if path is not None else None
            try:
                with archive.extractfile(member) as source:
                    while raw := source.read(cohort.CHUNK_BYTES):
                        digest.update(raw)
                        size += len(raw)
                        if size > member.size:
                            raise ValueError('Saved JFR archive member exceeds its header')
                        if target is not None:
                            target.write(raw)
            finally:
                if target is not None:
                    target.close()
            if {'size': size, 'sha256': digest.hexdigest()} != {key: expected[key] for key in ('size', 'sha256')}:
                raise ValueError('Saved JFR archive member bytes differ from manifest')
            if path is not None:
                selected[member.name] = path
    if seen != set(manifest['files']):
        raise ValueError('Saved JFR archive membership differs from manifest')
    inventory['allArchiveMemberBytesVerified'] = True
    return selected


class ExportMirror:
    def __init__(self, source, target, compressed, path, storage):
        self.source, self.target, self.compressed, self.path, self.storage = source, target, compressed, path, storage
        self.digest, self.size = hashlib.sha256(), 0

    def read(self, maximum):
        raw = self.source.read(maximum)
        self.size += len(raw)
        if self.size > events.MAX_EXPORT_BYTES:
            raise ValueError('Complete JFR export exceeds retained input bounds')
        self.digest.update(raw)
        self.compressed.write(raw)
        self.storage.account(self.path, self.target.tell())
        return raw


def child_file_limit():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_STDERR_BYTES, MAX_STDERR_BYTES))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def decode_recording(path, output, index, jfr, storage, families, arm, snapshots, origin=None):
    export = output / f'events-{index:06d}.json.gz'
    stderr = output / f'jfr-{index:06d}.stderr'
    command = [str(jfr), 'print', '--json', '--events', ','.join(events.EVENT_TYPES), str(path)]
    environment = {key: value for key, value in os.environ.items()
                   if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS')}
    result = {'completed': False, 'command': command, 'requestedEventTypes': list(events.EVENT_TYPES),
              'sourceSha256': cohort.file_identity(Path(__file__))['sha256'],
              'eventReaderSourceSha256': cohort.file_identity(Path(events.__file__))['sha256'],
              'xmlReaderSourceSha256': cohort.file_identity(Path(xml.__file__))['sha256'],
              **(origin or {}),
              'durationUnit': 'nanoseconds from exact ISO-8601 JFR duration text',
              'codeQuantity': 'reported successful jdk.Compilation.codeSize',
              'diagnosticOnly': True, **{flag: False for flag in cohort.DENIED}}
    census = events.Census()
    process, timer, mirror = None, None, None
    timed_out = threading.Event()
    try:
        with stderr.open('xb') as errors, export.open('xb') as target:
            with gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) as compressed:
                process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                    stderr=errors, env=environment, preexec_fn=child_file_limit)

                def timeout():
                    if process.poll() is None:
                        timed_out.set()
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass

                timer = threading.Timer(JFR_SECONDS, timeout)
                timer.daemon = True
                timer.start()
                mirror = ExportMirror(process.stdout, target, compressed, export, storage)
                for ordinal, event in events.JsonEvents(mirror):
                    census.add(ordinal, event)
                exit_code = process.wait(timeout=JFR_SECONDS)
                timer.cancel()
                result['exitCode'] = exit_code
                if exit_code != 0 or timed_out.is_set():
                    raise ValueError('Original JFR export failed or timed out; partial export retained')
        storage.account(export)
        storage.account(stderr)
        if cohort.gzip_identity(export, events.MAX_EXPORT_BYTES) != \
                {'size': mirror.size, 'sha256': mirror.digest.hexdigest()}:
            raise ValueError('Retained JFR export gzip differs from complete tool bytes')
        result.update(census.finish(families, arm, snapshots), completed=True)
    except Exception as error:
        result['error'] = {'type': type(error).__name__, 'message': str(error)}
    finally:
        if timer is not None:
            timer.cancel()
        if process is not None:
            if process.poll() is None:
                process.kill()
            result['exitCode'] = process.wait()
            if process.stdout is not None:
                process.stdout.close()
        result['timedOut'] = timed_out.is_set()
        if mirror is not None:
            result['retainedExportUncompressedIdentity'] = {'size': mirror.size, 'sha256': mirror.digest.hexdigest()}
        for key, candidate in [('exportFile', export), ('stderrFile', stderr)]:
            if candidate.is_file():
                storage.account(candidate)
                result[key] = {'path': candidate.name, **cohort.file_identity(candidate)}
    return result


def load_json(path):
    if path.stat().st_size > MAX_METADATA_BYTES:
        raise ValueError('Original JFR metadata exceeds bounds')
    return json.loads(path.read_bytes(), object_pairs_hook=cohort.unique_object)


def write_report(output, name, value, storage):
    path = output / name
    uncompressed = cohort.write_compressed_report(path, value, storage, cohort.MAX_REVIEW_BYTES)
    return {'path': name, 'compressed': cohort.file_identity(path), 'uncompressed': uncompressed}


def inspect(manifest_path, expected, archive_path, output, jfr, scratch, report):
    manifest, original = cohort.catalog(manifest_path, expected)
    report['originalInventory'] = original
    selected = authenticated_inputs(manifest, archive_path, scratch, original)
    prefix, case_prefix = original['profilePrefix'], original['casePrefix']
    cases_path = selected.get(prefix + 'cases.json')
    if cases_path is None:
        raise ValueError('Original cases.json is absent; expected case metadata cannot be inferred')
    measurements = load_json(cases_path)
    if not isinstance(measurements, list) or not all(isinstance(row, dict) and
            isinstance(row.get('case'), str) for row in measurements):
        raise ValueError('Original case metadata is malformed')
    by_case = {row['case']: (index, row) for index, row in enumerate(measurements)}
    if len(by_case) != len(measurements):
        raise ValueError('Original case metadata has duplicate identities')
    family_path = selected.get(prefix + 'compilation-families.json')
    families = load_json(family_path) if family_path is not None else {}
    if not isinstance(families, dict):
        raise ValueError('Original compilation family declarations are malformed')
    storage = cohort.StorageBudget(output)
    report.update(cases=[], originalMetadataFiles={},
        originalJfrToolIdentity=cohort.file_identity(jfr),
        sourceClosure={path.name: cohort.file_identity(path) for path in
                       [Path(__file__), Path(events.__file__), Path(cohort.__file__), Path(xml.__file__)]})
    for name in METADATA_NAMES:
        member = prefix + name
        if member in selected:
            target = output / ('original-' + name + '.gz')
            with selected[member].open('rb') as source, target.open('xb') as raw:
                with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
                    while chunk := source.read(cohort.CHUNK_BYTES):
                        compressed.write(chunk)
                        storage.account(target, raw.tell())
            storage.account(target)
            if cohort.gzip_identity(target, MAX_METADATA_BYTES) != \
                    {key: manifest['files'][member][key] for key in ('size', 'sha256')}:
                raise ValueError('Retained original metadata gzip differs from authenticated input')
            report['originalMetadataFiles'][member] = {'path': target.name, 'originalIdentity': manifest['files'][member],
                                                      'compressed': cohort.file_identity(target)}
    archived = {row['caseDirectory'] for row in original['cases']}
    report['metadataOnlyCaseNames'] = sorted(set(by_case) - archived)
    report['archivedCasesMissingMetadata'] = sorted(archived - set(by_case))
    report['outsideCaseRecordingIdentities'] = {name: value for name, value in manifest['files'].items()
                                               if name.endswith('.jfr') and not name.startswith(case_prefix)}
    report['outsideCaseXmlIdentities'] = {name: value for name, value in manifest['files'].items()
                                         if name.endswith('/hotspot.xml') and not name.startswith(case_prefix)}
    all_decoded, recording_index = True, 0
    for case in original['cases']:
        name = case['caseDirectory']
        start = case_prefix + name + '/'
        members = {key: value for key, value in selected.items() if key.startswith(start)}
        jfrs = [key for key in members if key.endswith('.jfr')]
        logs = [key for key in members if key.endswith('/hotspot.xml')]
        metadata_index, metadata = by_case.get(name, (None, {}))
        case_report = {'caseDirectory': name, 'originalCaseMetadataIndex': metadata_index,
            'originalCaseMetadataIdentity': manifest['files'][prefix + 'cases.json'],
            'originalContextCopiedVerbatim': {key: metadata.get(key) for key in
                ('arm', 'engine', 'index', 'repetition', 'mode', 'recipe', 'sourceHashesMatch')},
            'recordings': [], 'xmlInputs': [], 'diagnosticOnly': True,
            'completed': False, **{flag: False for flag in cohort.DENIED}}
        measurement = start + 'measurement.json'
        case_report['originalMeasurementIdentity'] = manifest['files'].get(measurement)
        case_report['measurementMatchesCasesJson'] = load_json(members[measurement]) == metadata \
            if measurement in members and metadata_index is not None else None
        snapshots = {}
        for member in logs:
            parsed, auxiliary, error = {}, [], None
            try:
                with members[member].open('rb') as source:
                    parsed, auxiliary = xml.parse_stream(source, manifest['files'][member])
            except Exception as failure:
                error = {'type': type(failure).__name__, 'message': str(failure)}
            identity = write_report(output, f'xml-{len(report["cases"]):06d}-{len(case_report["xmlInputs"]):03d}.json.gz',
                                    {'completed': error is None, 'metadata': parsed, 'auxiliary': auxiliary, 'error': error}, storage)
            case_report['xmlInputs'].append({'originalMember': member, 'originalIdentity': manifest['files'][member],
                                            'completed': error is None, 'error': error, 'retainedMetadata': identity})
            if len(logs) == 1 and member == start + 'hotspot.xml':
                snapshots = parsed
        for member in jfrs:
            origin = {'originalMember': member, 'originalIdentity': manifest['files'][member],
                      'originalCaseDirectory': name, 'originalCaseMetadataIndex': metadata_index}
            derived = decode_recording(members[member], output, recording_index, jfr, storage,
                                       families, metadata.get('arm'), snapshots, origin)
            if cohort.file_identity(members[member]) != {key: manifest['files'][member][key] for key in ('size', 'sha256')}:
                derived.update(completed=False, error={'type': 'InputChanged', 'message': 'Original recording changed during export'})
            retained = write_report(output, f'compilation-{recording_index:06d}.json.gz', derived, storage)
            case_report['recordings'].append({'originalMember': member, 'originalIdentity': manifest['files'][member],
                                             'completed': derived['completed'], 'report': retained})
            recording_index += 1
        case_report['completed'] = metadata_index is not None and case_report['measurementMatchesCasesJson'] is True and \
            len(jfrs) == 1 and jfrs[0] == start + 'cli.jfr' and len(logs) == 1 and logs[0] == start + 'hotspot.xml' and \
            all(row['completed'] for row in case_report['recordings'] + case_report['xmlInputs'])
        report['cases'].append(case_report)
        all_decoded = all_decoded and case_report['completed']
        print('Reviewed saved recording case', name, 'completed', case_report['completed'], flush=True)
    report.update(completed=True, allCaseCensusesComplete=bool(report['cases']) and all_decoded and
                  not report['metadataOnlyCaseNames'] and not report['archivedCasesMissingMetadata'])


def main():
    manifest, expected, archive, output, jfr, scratch = sys.argv[1:]
    manifest, archive, output, jfr, scratch = map(Path, (manifest, archive, output, jfr, scratch))
    output.mkdir(parents=True, exist_ok=True)
    report = {'completed': False, 'allCaseCensusesComplete': False, 'diagnosticOnly': True,
              'sourceSha256': cohort.file_identity(Path(__file__))['sha256'],
              'inputFiles': {str(path): cohort.file_identity(path) for path in (manifest, archive)},
              'scope': 'Authenticated complete original archive; per-recording standard-JFR all-tier roots and '
                       'original XML metadata. Raw recordings and unselected large derived reports remain in '
                       'the immutable original archive. No captured target JVM, solver or native probe is invoked. '
                       'Original source/runtime/worker/outcome and every exclusion retain their own authority; '
                       'decoding completion and observed costs supply no current source-phase or saving proof.',
              **{flag: False for flag in cohort.DENIED}}
    try:
        with tempfile.TemporaryDirectory(prefix='jfr-inputs-', dir=scratch) as temporary:
            inspect(manifest, expected, archive, output, jfr, Path(temporary), report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        raw = (json.dumps(report, indent=2) + '\n').encode()
        if len(raw) > cohort.MAX_INDEX_BYTES:
            raw = (json.dumps({'completed': False, 'allCaseCensusesComplete': False,
                'diagnosticOnly': True, 'inputFiles': report['inputFiles'],
                'error': {'type': 'IndexLimitExceeded', 'message': 'Complete index withheld; original archive and written evidence retained'},
                **{flag: False for flag in cohort.DENIED}}) + '\n').encode()
        (output / 'compilation-cohort.json').write_bytes(raw)
        cohort.StorageBudget(output)


if __name__ == '__main__':
    main()
