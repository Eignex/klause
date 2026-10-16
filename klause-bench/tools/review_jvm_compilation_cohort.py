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
import read_jvm_class_origins as origins
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
                  'boolean-source-checks.json', 'linear-source-checks.json', 'linear-source-checks.jsonl',
                  'study-plan.json', 'requested-options.json', 'processor-affinity.txt',
                  'candidate-harness-provenance.json', 'control-harness-provenance.json')


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
                (member.name.endswith(('.jfr', '/hotspot.xml', '/measurement.json')) or
                 origins.is_log_member(member.name))
            metadata = member.name in {prefix + name for name in METADATA_NAMES}
            chosen = case_record or metadata
            maximum = MAX_RECORDING_BYTES if member.name.endswith(('.jfr', '/hotspot.xml')) or \
                origins.is_log_member(member.name) else MAX_METADATA_BYTES
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


def decode_recording(path, output, index, jfr, storage, families, arm, snapshots, origin=None, class_origins=None):
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
        result['recordedInstalledClassOriginAssociations'] = \
            origins.associate(result, class_origins) if class_origins is not None else None
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


def omitted_class_origins(manifest, inventory):
    omitted = manifest.get('catalog', {}).get('sourceFilterFileInventory', {}).get('omittedButPrivatelyRetained', [])
    if not isinstance(omitted, list):
        raise ValueError('Malformed original source-filter omission inventory')
    result = {}
    for item in omitted:
        name = item.get('path') if isinstance(item, dict) else None
        if isinstance(name, str) and origins.is_log_member(name):
            name = name if name.startswith(inventory['profilePrefix']) else inventory['profilePrefix'] + name
            size = item.get('bytes', item.get('size'))
            digest = item.get('sha256')
            if not cohort.safe_name(name) or name in result or type(size) is not int or size < 0 or \
                    not isinstance(digest, str) or not origins.HASH.fullmatch(digest):
                raise ValueError('Malformed or repeated omitted class-origin identity')
            result[name] = {'size': size, 'sha256': digest}
    return result


def supplement_class_origins(inputs, manifest, original, selected, scratch, report, omitted):
    companion, inventory = cohort.catalog(inputs[0], inputs[1])
    if companion.get('catalog', {}).get('primaryArchive') != manifest['archive'] or \
            inventory['profilePrefix'] != original['profilePrefix']:
        raise ValueError('Class-origin companion belongs to another original archive')
    files = companion['files']
    if len(manifest['files']) + len(files) > cohort.MAX_MEMBERS or \
            manifest['archive']['size'] + companion['archive']['size'] > cohort.MAX_ARCHIVE_BYTES or \
            sum(v['size'] for v in manifest['files'].values()) + sum(v['size'] for v in files.values()) > \
            cohort.MAX_EXPANDED_BYTES:
        raise ValueError('Combined original and class-origin companion exceeds archive bounds')
    additions = {name: item for name, item in files.items() if name.startswith(original['casePrefix'])}
    if not additions or any(name in selected or name not in omitted or item != omitted[name]
                            for name, item in additions.items()) or \
            any(original['profilePrefix'] + name in files for name in METADATA_NAMES):
        raise ValueError('Class-origin companion must restore only exact original omitted log identities')
    if sum(path.stat().st_size for path in selected.values()) + sum(v['size'] for v in additions.values()) > \
            MAX_SELECTED_BYTES:
        raise ValueError('Combined class-origin selected inputs exceed scratch bounds')
    staging = scratch / 'class-origins'
    staging.mkdir()
    restored = authenticated_inputs(companion, inputs[2], staging, inventory)
    if set(restored) != set(additions):
        raise ValueError('Class-origin companion selected an unexpected input')
    report['classOriginSupplementInventory'] = inventory
    selected.update(restored)
    return additions


def inspect(manifest_path, expected, archive_path, output, jfr, scratch, report, class_origin_supplement=None):
    manifest, original = cohort.catalog(manifest_path, expected)
    report['originalInventory'] = original
    selected = authenticated_inputs(manifest, archive_path, scratch, original)
    omitted = omitted_class_origins(manifest, original)
    identities = dict(manifest['files'])
    restored = supplement_class_origins(class_origin_supplement, manifest, original, selected, scratch, report, omitted) \
        if class_origin_supplement is not None else {}
    identities.update(restored)
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
                       [Path(__file__), Path(events.__file__), Path(cohort.__file__), Path(xml.__file__),
                        Path(origins.__file__)]})
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
        class_logs = [key for key in members if origins.is_log_member(key)]
        metadata_index, metadata = by_case.get(name, (None, {}))
        case_report = {'caseDirectory': name, 'originalCaseMetadataIndex': metadata_index,
            'originalCaseMetadataIdentity': manifest['files'][prefix + 'cases.json'],
            'originalContextCopiedVerbatim': {key: metadata.get(key) for key in
                ('arm', 'engine', 'index', 'repetition', 'mode', 'recipe', 'sourceHashesMatch')},
            'recordings': [], 'xmlInputs': [], 'classOriginInputs': [], 'diagnosticOnly': True,
            'completed': False, **{flag: False for flag in cohort.DENIED}}
        measurement = start + 'measurement.json'
        case_report['originalMeasurementIdentity'] = manifest['files'].get(measurement)
        case_report['measurementMatchesCasesJson'] = load_json(members[measurement]) == metadata \
            if measurement in members and metadata_index is not None else None
        case_report['omittedClassOriginMembersNotRestored'] = {key: value for key, value in omitted.items()
                                                              if key.startswith(start) and key not in restored}
        class_origins = None
        for member in class_logs:
            parsed, error = {}, None
            try:
                arm = metadata.get('arm')
                provenance = selected.get(prefix + arm + '-provenance.json') if arm in ('control', 'candidate') else None
                with members[member].open('rb') as source:
                    parsed = origins.parse(source, load_json(provenance) if provenance is not None else None)
            except Exception as failure:
                error = {'type': type(failure).__name__, 'message': str(failure)}
            retained = write_report(output, f'class-origins-{len(report["cases"]):06d}-'
                                    f'{len(case_report["classOriginInputs"]):03d}.json.gz',
                                    {'completed': error is None, 'observations': parsed, 'error': error}, storage)
            case_report['classOriginInputs'].append({'originalMember': member,
                'originalIdentity': identities[member], 'sourceArchive': 'companion' if member in restored else 'primary',
                'completed': error is None,
                'allLinesParsed': parsed.get('completed') is True, 'error': error, 'retainedMetadata': retained})
            if len(class_logs) == 1 and member == start + 'class-load.log' and error is None and \
                    not case_report['omittedClassOriginMembersNotRestored']:
                class_origins = parsed
        case_report['recordedClassOriginsComplete'] = class_origins is not None and class_origins['completed']
        case_report['classOriginScope'] = ('Optional saved observations, separate from JFR/XML completion. Missing, '
            'rotated or multiple logs do not supply a single complete origin stream; '
            'no loader identity, loaded byte digest or source/phase/saving proof follows.')
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
                                       families, metadata.get('arm'), snapshots, origin, class_origins)
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
    arguments = sys.argv[1:]
    if len(arguments) not in (6, 9):
        raise ValueError('Expected primary archive inputs and an optional complete class-origin companion triple')
    manifest, expected, archive, output, jfr, scratch = arguments[:6]
    manifest, archive, output, jfr, scratch = map(Path, (manifest, archive, output, jfr, scratch))
    companion = (Path(arguments[6]), arguments[7], Path(arguments[8])) if len(arguments) == 9 else None
    output.mkdir(parents=True, exist_ok=True)
    report = {'completed': False, 'allCaseCensusesComplete': False, 'diagnosticOnly': True,
              'sourceSha256': cohort.file_identity(Path(__file__))['sha256'],
              'inputFiles': {str(path): cohort.file_identity(path) for path in
                             ((manifest, archive, companion[0], companion[2]) if companion else (manifest, archive))},
              'scope': 'Authenticated complete original archive; per-recording standard-JFR all-tier roots and '
                       'original XML metadata. Raw recordings and unselected large derived reports remain in '
                       'the immutable original archive. No captured target JVM, solver or native probe is invoked. '
                       'Original source/runtime/worker/outcome and every exclusion retain their own authority; '
                       'decoding completion and observed costs supply no current source-phase or saving proof.',
              **{flag: False for flag in cohort.DENIED}}
    try:
        with tempfile.TemporaryDirectory(prefix='jfr-inputs-', dir=scratch) as temporary:
            inspect(manifest, expected, archive, output, jfr, Path(temporary), report, companion)
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
