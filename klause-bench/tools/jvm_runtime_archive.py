"""Authenticate a retained runtime and stage only its offline image reader inputs."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import sys
import tarfile

MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_MEMBERS = 20_000
MAX_FILE_BYTES = 512 * 1024 * 1024
IMAGE_FILES = ('lib/modules', 'lib/jrt-fs.jar', 'release')


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


def main():
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
