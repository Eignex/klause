"""Authenticate a retained runtime and stage only its offline image reader inputs."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import sys
import tarfile
import tempfile

MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_MEMBERS = 20_000
MAX_FILE_BYTES = 512 * 1024 * 1024
IMAGE_FILES = ('lib/modules', 'lib/jrt-fs.jar', 'release')
MAX_JSON_BYTES = 2 * 1024 * 1024


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def safe_name(name):
    if not name or name.startswith('/') or '\\' in name or any(x in ('', '.', '..') for x in name.split('/')):
        raise ValueError('Unsafe archive name: ' + name)
    return name


def resolved_member(name, members):
    visited = set()
    for _ in range(32):
        if name in visited:
            raise ValueError('Cyclic runtime file link: ' + name)
        visited.add(name)
        if name not in members:
            raise ValueError('Runtime file link target is absent: ' + name)
        member = members[name]
        if member.isfile():
            return member
        if not (member.issym() or member.islnk()):
            raise ValueError('Runtime link does not resolve to a file: ' + name)
        link = member.linkname
        if not link or link.startswith('/') or '\\' in link:
            raise ValueError('Unsafe runtime file link: ' + link)
        target = posixpath.join(posixpath.dirname(name), link) if member.issym() else link
        target = safe_name(posixpath.normpath(target))
        if target.split('/')[0] != name.split('/')[0]:
            raise ValueError('Runtime file link leaves archive root')
        name = target
    raise ValueError('Runtime file link chain exceeds bounds')


def prepare_runtime(manifest, archive, expected_archive_hash, destination, report):
    report['completed'] = False
    if not re.fullmatch('[0-9a-f]{64}', expected_archive_hash):
        raise ValueError('Invalid archive identity')
    if archive.stat().st_size > MAX_ARCHIVE_BYTES or digest(archive) != expected_archive_hash:
        raise ValueError('Runtime archive identity mismatch')
    expected = manifest['provenance']['runtime']
    if not expected or len(expected) > MAX_MEMBERS:
        raise ValueError('Invalid original runtime inventory')
    for name, identity in expected.items():
        safe_name(name)
        if not isinstance(identity, str) or not re.fullmatch('[0-9a-f]{64}', identity):
            raise ValueError('Invalid original runtime file identity')
    if not set(IMAGE_FILES).issubset(expected):
        raise ValueError('Original runtime lacks image/provider/release identities')
    members, actual, total = {}, {}, 0
    report.update(expectedRuntime=expected, actualRuntime=actual, members=[])
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            report['members'].append({'name': member.name, 'bytes': member.size, 'type': member.type.decode('ascii')})
            name = safe_name(member.name)
            if name in members or len(members) >= MAX_MEMBERS:
                raise ValueError('Duplicate or excessive runtime archive members')
            if not (member.isdir() or member.isfile() or member.issym() or member.islnk()):
                raise ValueError('Unsupported runtime archive member')
            total += member.size
            if member.size < 0 or member.size > MAX_FILE_BYTES or total > MAX_EXPANDED_BYTES:
                raise ValueError('Runtime archive expanded size exceeds bounds')
            members[name] = member
        roots = {name.split('/')[0] for name in members}
        if len(roots) != 1:
            raise ValueError('Runtime archive must have one root')
        root = next(iter(roots))
        logical_bytes = 0
        file_hashes = {}
        for name, member in members.items():
            if member.isdir():
                continue
            selected = resolved_member(name, members)
            relative = name.removeprefix(root + '/')
            logical_bytes += selected.size
            if logical_bytes > MAX_EXPANDED_BYTES:
                raise ValueError('Resolved runtime file size exceeds bounds')
            if selected.name not in file_hashes:
                value = hashlib.sha256()
                with source.extractfile(selected) as stream:
                    while chunk := stream.read(1024 * 1024):
                        value.update(chunk)
                file_hashes[selected.name] = value.hexdigest()
            actual[relative] = file_hashes[selected.name]
        report['allRuntimeArchiveFilesMatchOriginalManifest'] = actual == expected
        if actual != expected:
            raise ValueError('Complete runtime archive differs from retained original')
        destination.mkdir()
        copied = {}
        for name in IMAGE_FILES:
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            selected = resolved_member(root + '/' + name, members)
            with source.extractfile(selected) as stream, target.open('xb') as output:
                while chunk := stream.read(1024 * 1024):
                    output.write(chunk)
            copied[name] = digest(target)
            if copied[name] != expected[name]:
                raise ValueError('Staged image input differs from original: ' + name)
        report['stagedRuntimeFiles'] = copied
    if digest(archive) != expected_archive_hash:
        raise ValueError('Runtime archive changed while preparing image inputs')
    report['completed'] = True


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON key')
        value[key] = item
    return value


def prepare_saved_distribution(manifest, capture, archive, expected_archive_hash, expected_manifest_hash,
                               source_head, destination, report):
    report['completed'] = False
    if any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value)
           for value in (expected_archive_hash, expected_manifest_hash)) or \
            not isinstance(source_head, str) or not re.fullmatch('[0-9a-f]{40}', source_head):
        raise ValueError('Invalid saved capture identity')
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size > MAX_ARCHIVE_BYTES or \
            digest(archive) != expected_archive_hash:
        raise ValueError('Saved capture archive identity mismatch')
    if not isinstance(capture, dict) or type(capture.get('schema')) is not int or capture['schema'] != 1 or \
            type(capture.get('archiveBytes')) is not int or capture['archiveBytes'] != archive.stat().st_size or \
            capture.get('archiveSha256') != expected_archive_hash:
        raise ValueError('Saved capture manifest differs from archive identity')
    expected = capture.get('files')
    if not isinstance(expected, dict) or not expected or len(expected) > MAX_MEMBERS:
        raise ValueError('Saved capture member inventory is absent or excessive')
    for name, item in expected.items():
        safe_name(name)
        if any(ord(char) < 32 or ord(char) == 127 for char in name) or not isinstance(item, dict) or \
                type(item.get('bytes')) is not int or not 0 <= item['bytes'] <= MAX_FILE_BYTES or \
                not isinstance(item.get('sha256'), str) or not re.fullmatch('[0-9a-f]{64}', item['sha256']):
            raise ValueError('Invalid saved capture member identity')
    distribution = manifest['provenance']['distribution']
    if not isinstance(distribution, dict) or not distribution or len(distribution) > MAX_MEMBERS:
        raise ValueError('Original distribution inventory is absent or excessive')
    for name, value in distribution.items():
        safe_name(name)
        if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value):
            raise ValueError('Invalid original distribution identity')
    jars = {name: value for name, value in distribution.items() if name.endswith('.jar')}
    if not jars or any(not name.startswith('lib/') for name in jars):
        raise ValueError('Original jar inventory is absent or unsafe')
    selected = {'private-installed-jars/' + name: value for name, value in jars.items()}
    if {name for name in expected if name.startswith('private-installed-jars/')} != set(selected):
        raise ValueError('Saved capture does not retain the complete original jar set')
    proofs = ('captured-original-manifest.json', 'completion.json',
              'control-distribution-match.json', 'candidate-distribution-match.json')
    if not set(proofs).issubset(expected) or \
            expected[proofs[0]]['sha256'] != expected_manifest_hash or \
            any(expected[name]['bytes'] > MAX_JSON_BYTES for name in proofs):
        raise ValueError('Original capture provenance is absent or oversized')
    report.update(originalDistribution=distribution, expectedCaptureMembers=expected, verifiedCaptureMembers={})
    actual, directories, seen, paths, total = {}, [], set(), {}, 0
    with tempfile.TemporaryDirectory(prefix='saved-original-jars-', dir=destination.parent) as temporary:
        temporary = Path(temporary)
        with tarfile.open(archive, 'r|gz') as source:
            for member in source:
                name = member.name.rstrip('/') if member.isdir() else member.name
                safe_name(name)
                if name in seen or len(seen) >= MAX_MEMBERS or member.issym() or member.islnk() or member.sparse:
                    raise ValueError('Duplicate, linked or excessive saved capture member')
                seen.add(name)
                if member.isdir():
                    if name in expected:
                        raise ValueError('Saved capture payload became a directory')
                    directories.append(name)
                    continue
                if not member.isfile() or name not in expected:
                    raise ValueError('Unexpected saved capture member')
                total += member.size
                if member.size != expected[name]['bytes'] or member.size < 0 or total > MAX_EXPANDED_BYTES:
                    raise ValueError('Saved capture member size differs or exceeds bounds')
                retained = name in selected or name in proofs
                path = temporary / str(len(paths)) if retained else None
                output = path.open('xb') if retained else None
                checksum, size = hashlib.sha256(), 0
                try:
                    with source.extractfile(member) as stream:
                        while chunk := stream.read(1024 * 1024):
                            size += len(chunk)
                            if size > member.size:
                                raise ValueError('Saved capture member exceeds its header')
                            checksum.update(chunk)
                            if output is not None:
                                output.write(chunk)
                finally:
                    if output is not None:
                        output.close()
                actual[name] = {'bytes': size, 'sha256': checksum.hexdigest()}
                report['verifiedCaptureMembers'][name] = actual[name]
                if actual[name] != expected[name]:
                    raise ValueError('Saved capture member differs from its original identity')
                if retained:
                    paths[name] = path
        if actual != expected:
            raise ValueError('Complete saved capture membership differs from manifest')
        headers = {name: json.loads(paths[name].read_bytes(), object_pairs_hook=unique_object) for name in proofs}
        if not all(isinstance(value, dict) for value in headers.values()):
            raise ValueError('Original capture provenance is not an object')
        if headers[proofs[0]] != manifest:
            raise ValueError('Saved original manifest differs from the requested original')
        completion = headers['completion.json']
        if completion.get('completed') is not True or completion.get('requestedSourceHead') != source_head or \
                completion.get('allInstalledDistributionBytesMatchedBeforeInspection') is not True:
            raise ValueError('Original capture did not authenticate the requested source distribution')
        for name in proofs[2:]:
            proof = headers[name]
            if proof.get('matchesOriginal') is not True or proof.get('expected') != distribution or \
                    proof.get('actual') != distribution:
                raise ValueError('Original capture distribution-match proof is incomplete')
        if any(actual[name]['sha256'] != value for name, value in selected.items()):
            raise ValueError('Saved jar bytes differ from the original distribution')
        if digest(archive) != expected_archive_hash:
            raise ValueError('Saved archive changed during authentication')
        destination.mkdir()
        staged = {}
        for name, value in sorted(jars.items()):
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            paths['private-installed-jars/' + name].replace(target)
            staged[name] = digest(target)
            if staged[name] != value:
                raise ValueError('Staged jar differs from the original distribution')
    report.update(completed=True, allCaptureMemberBytesVerifiedBeforeStaging=True,
                  allOriginalJarBytesMatchRetainedDistribution=True, originalSourceHead=source_head,
                  originalCaptureCompletionCopiedVerbatim=completion,
                  originalCaptureDistributionProofsCopiedVerbatim={name: headers[name] for name in proofs[2:]},
                  explicitArchiveDirectories=directories, stagedJarFiles=staged)


def saved_main():
    manifest, capture, archive, archive_hash, manifest_hash, source, destination, report_path = sys.argv[2:]
    report = {'completed': False, 'scope': 'Whole saved capture and original jar byte authentication only; no class reader, original CLI, solver or SDK executable is invoked.'}
    try:
        for path in (manifest, capture):
            if Path(path).is_symlink() or not Path(path).is_file() or Path(path).stat().st_size > MAX_JSON_BYTES:
                raise ValueError('Saved capture metadata is absent or excessive')
        if digest(Path(manifest)) != manifest_hash:
            raise ValueError('Requested original manifest identity differs')
        prepare_saved_distribution(json.loads(Path(manifest).read_bytes(), object_pairs_hook=unique_object),
                                   json.loads(Path(capture).read_bytes(), object_pairs_hook=unique_object),
                                   Path(archive), archive_hash, manifest_hash, source, Path(destination), report)
    except BaseException as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        Path(report_path).write_text(json.dumps(report, indent=2) + '\n')
        raise
    Path(report_path).write_text(json.dumps(report, indent=2) + '\n')


def prepare_study_distributions(capture, archive, expected_archive_hash, source_heads, destination, report):
    report['completed'] = False
    if not isinstance(source_heads, dict) or set(source_heads) != {'control', 'candidate'} or \
            any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value)
                for value in source_heads.values()) or not isinstance(expected_archive_hash, str) or \
            not re.fullmatch('[0-9a-f]{64}', expected_archive_hash):
        raise ValueError('Invalid saved study source or archive identity')
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size > MAX_ARCHIVE_BYTES or \
            digest(archive) != expected_archive_hash:
        raise ValueError('Saved study archive identity mismatch')
    if not isinstance(capture, dict) or type(capture.get('schema')) is not int or capture['schema'] != 1 or \
            type(capture.get('archiveBytes')) is not int or capture['archiveBytes'] != archive.stat().st_size or \
            capture.get('archiveSha256') != expected_archive_hash:
        raise ValueError('Saved study capture manifest differs from archive identity')
    expected = capture.get('files')
    if not isinstance(expected, dict) or not expected or len(expected) > MAX_MEMBERS:
        raise ValueError('Saved study member inventory is absent or excessive')
    for name, item in expected.items():
        safe_name(name)
        parts = name.split('/')
        if len(name.encode()) > 4096 or len(parts) > 64 or \
                any(ord(char) < 32 or ord(char) == 127 for char in name) or \
                any('/'.join(parts[:index]) in expected for index in range(1, len(parts))) or \
                not isinstance(item, dict) or type(item.get('bytes')) is not int or \
                not 0 <= item['bytes'] <= MAX_FILE_BYTES or not isinstance(item.get('sha256'), str) or \
                not re.fullmatch('[0-9a-f]{64}', item['sha256']):
            raise ValueError('Invalid saved study member identity or path')
    proofs = {'allocation-request.json', 'study-builds.json'} | {
        arm + suffix for arm in source_heads
        for suffix in ('-provenance.json', '-harness-provenance.json', '-retained-distributions.json')}
    if not proofs <= set(expected) or any(expected[name]['bytes'] > MAX_JSON_BYTES for name in proofs):
        raise ValueError('Original saved study provenance is absent or oversized')
    selected = {name for name in expected if name.endswith('.jar') and any(
        name.startswith('private-installed/' + arm + '/cli/lib/') for arm in source_heads)}
    if not selected or destination.exists() or destination.is_symlink():
        raise ValueError('Original saved study jars are absent or staging already exists')
    actual, seen, paths, directories, total = {}, set(), {}, [], 0
    report.update(expectedCaptureMembers=expected, verifiedCaptureMembers=actual)
    with tempfile.TemporaryDirectory(prefix='saved-study-jars-', dir=destination.parent) as temporary:
        temporary = Path(temporary)
        with tarfile.open(archive, 'r|gz') as source:
            for member in source:
                name = member.name.rstrip('/') if member.isdir() else member.name
                safe_name(name)
                parts = name.split('/')
                if name in seen or len(seen) >= MAX_MEMBERS or len(name.encode()) > 4096 or len(parts) > 64 or \
                        any(ord(char) < 32 or ord(char) == 127 for char in name) or \
                        member.issym() or member.islnk() or member.sparse:
                    raise ValueError('Duplicate, linked, unsafe or excessive saved study member')
                seen.add(name)
                if member.isdir():
                    if name in expected or any('/'.join(parts[:index]) in expected
                                               for index in range(1, len(parts))):
                        raise ValueError('Saved study file conflicts with a directory')
                    directories.append(name)
                    continue
                if not member.isfile() or name not in expected:
                    raise ValueError('Unexpected saved study member')
                total += member.size
                if member.size != expected[name]['bytes'] or member.size < 0 or total > MAX_EXPANDED_BYTES:
                    raise ValueError('Saved study member size differs or exceeds bounds')
                retained = name in proofs or name in selected
                path = temporary / str(len(paths)) if retained else None
                output = path.open('xb') if retained else None
                checksum, size = hashlib.sha256(), 0
                try:
                    with source.extractfile(member) as stream:
                        while chunk := stream.read(1024 * 1024):
                            size += len(chunk)
                            if size > member.size:
                                raise ValueError('Saved study member exceeds its header')
                            checksum.update(chunk)
                            if output is not None:
                                output.write(chunk)
                finally:
                    if output is not None:
                        output.close()
                actual[name] = {'bytes': size, 'sha256': checksum.hexdigest()}
                if actual[name] != expected[name]:
                    raise ValueError('Saved study member differs from its original identity')
                if retained:
                    paths[name] = path
        if actual != expected:
            raise ValueError('Complete saved study membership differs from manifest')
        headers = {name: json.loads(paths[name].read_bytes(), object_pairs_hook=unique_object) for name in proofs}
        if headers['allocation-request.json'] != source_heads:
            raise ValueError('Original study request differs from pinned source heads')
        builds = headers['study-builds.json']
        if not isinstance(builds, list) or len(builds) != 2 or \
                any(not isinstance(build, dict) for build in builds) or \
                {build.get('arm') for build in builds} != set(source_heads):
            raise ValueError('Original study builds are missing or repeated')
        distributions = {}
        for build in builds:
            arm = build['arm']
            if build.get('expectedHead') != source_heads[arm] or build.get('actualHead') != source_heads[arm]:
                raise ValueError('Original study build source differs')
            retained = headers[arm + '-retained-distributions.json']
            if not isinstance(retained, dict) or set(retained) != {'cli', 'harness'}:
                raise ValueError('Original retained distributions are incomplete')
            for kind, suffix, hash_key in [('cli', '-provenance.json', 'provenanceSha256'),
                                           ('harness', '-harness-provenance.json', 'harnessProvenanceSha256')]:
                name = arm + suffix
                original = headers[name]
                files = retained[kind]
                if actual[name]['sha256'] != build.get(hash_key) or not isinstance(original, dict) or \
                        not isinstance(files, dict) or not files:
                    raise ValueError('Original study distribution provenance differs')
                prefix = 'private-installed/' + arm + '/' + kind + '/'
                archived = {key.removeprefix(prefix): item for key, item in actual.items()
                            if key.startswith(prefix)}
                if archived != files or not isinstance(original.get('provenance'), dict) or \
                        original['provenance'].get('distribution') != \
                        {key: item['sha256'] for key, item in archived.items()}:
                    raise ValueError('Complete original study distribution differs from retained bytes')
                if kind == 'cli':
                    if original.get('launcher') != build.get('cli'):
                        raise ValueError('Original study CLI launcher provenance differs')
                    distributions[arm] = original['provenance']['distribution']
        staged = temporary / 'distribution'
        staged.mkdir()
        jar_files = {}
        for arm, distribution in distributions.items():
            jars = {name: value for name, value in distribution.items() if name.endswith('.jar')}
            if not jars or any(not name.startswith('lib/') for name in jars):
                raise ValueError('Original study CLI jar set is absent or unsafe')
            jar_files[arm] = {}
            for name, value in sorted(jars.items()):
                target = staged / arm / name
                target.parent.mkdir(parents=True, exist_ok=True)
                paths['private-installed/' + arm + '/cli/' + name].replace(target)
                jar_files[arm][name] = digest(target)
                if jar_files[arm][name] != value:
                    raise ValueError('Staged study jar differs from original distribution')
        if digest(archive) != expected_archive_hash:
            raise ValueError('Saved study archive changed during authentication')
        staged.replace(destination)
        report.update(completed=True, allCaptureMemberBytesVerifiedBeforeStaging=True,
                      bothCompleteOriginalDistributionsVerified=True, originalSourceHeads=source_heads,
                      originalStudyHeadersCopiedVerbatim=headers, originalDistributions=distributions,
                      explicitArchiveDirectories=directories, stagedJarFiles=jar_files)


def study_main():
    capture, capture_hash, archive, archive_hash, control, candidate, destination, report_path = sys.argv[2:]
    report = {'completed': False, 'scope': 'Complete original study archive and both original CLI/harness distribution bytes only; no original executable, class reader or workload runs during staging.'}
    try:
        capture = Path(capture)
        if capture.is_symlink() or not capture.is_file() or capture.stat().st_size > MAX_JSON_BYTES or \
                not re.fullmatch('[0-9a-f]{64}', capture_hash) or digest(capture) != capture_hash:
            raise ValueError('Saved study capture manifest identity differs')
        prepare_study_distributions(json.loads(capture.read_bytes(), object_pairs_hook=unique_object),
                                    Path(archive), archive_hash, {'control': control, 'candidate': candidate},
                                    Path(destination), report)
    except BaseException as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        Path(report_path).write_text(json.dumps(report, indent=2) + '\n')
        raise
    Path(report_path).write_text(json.dumps(report, indent=2) + '\n')


def main():
    if sys.argv[1:2] == ['study-distributions']:
        study_main()
        return
    if sys.argv[1:2] == ['saved-distribution']:
        saved_main()
        return
    manifest, archive, expected, destination, report_path = sys.argv[1:]
    report = {'completed': False, 'scope': 'File/archive hashes only; no runtime executable or image/class reader is invoked.'}
    try:
        prepare_runtime(json.loads(Path(manifest).read_text()), Path(archive), expected, Path(destination), report)
    except BaseException as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        try:
            Path(report_path).write_text(json.dumps(report, indent=2) + '\n')
        except OSError as write_error:
            error.add_note('Cannot retain preparation report: ' + str(write_error))
        raise
    Path(report_path).write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
