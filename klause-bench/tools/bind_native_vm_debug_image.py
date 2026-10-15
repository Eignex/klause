"""Bind one official external debug image to the pinned JVM file without loading it."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import struct
import sys
import tarfile
import tempfile
import urllib.request
import zlib

import read_native_vm_symbols as symbols

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
ARCHIVE_URL = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-debugimage_x64_linux_hotspot_25.0.4.1_1.tar.gz'
ARCHIVE_SHA256 = '6dcf472364469f7f570732a312cdf7b02ca832cf8d7b8bc2e1caf1ab0db54ab4'
ARCHIVE_BYTES = 280104575
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_MEMBERS = 4096
MAX_NOTE_BYTES = 1024 * 1024
NOTE_HEADER = struct.Struct('<III')


def download(destination, report):
    report.update(url=ARCHIVE_URL, expectedSha256=ARCHIVE_SHA256, expectedBytes=ARCHIVE_BYTES,
                  completed=False)
    size, digest = 0, hashlib.sha256()
    with urllib.request.urlopen(ARCHIVE_URL, timeout=30) as source, destination.open('xb') as target:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            if size > min(MAX_ARCHIVE_BYTES, ARCHIVE_BYTES):
                raise ValueError('Official debug archive exceeds pinned bounds')
            target.write(chunk)
            digest.update(chunk)
    report.update(bytes=size, sha256=digest.hexdigest())
    if size != ARCHIVE_BYTES or digest.hexdigest() != ARCHIVE_SHA256:
        raise ValueError('Official debug archive differs from pinned release')
    report['completed'] = True


def extract(archive, directory, report):
    total, candidates, members = 0, [], []
    report.update(members=members, completed=False)
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            path = PurePosixPath(member.name)
            duplicate = any(row['name'] == member.name for row in members)
            members.append({'name': member.name, 'bytes': member.size, 'directory': member.isdir(),
                            'typeHex': member.type.hex(), 'linkName': member.linkname})
            if len(members) > MAX_MEMBERS or path.is_absolute() or '..' in path.parts or \
                    '\\' in member.name or not (member.isdir() or member.isfile()) or member.size < 0:
                raise ValueError('Unsupported official debug archive member')
            total += member.size
            if total > MAX_EXPANDED_BYTES or duplicate:
                raise ValueError('Debug archive exceeds bounds or repeats a member')
            if member.isfile() and path.name == 'libjvm.debuginfo':
                candidates.append(member)
        report.update(members=members, expandedBytes=total,
                      candidates=[{'name': member.name, 'bytes': member.size} for member in candidates])
        if len(candidates) != 1:
            raise ValueError('Official archive lacks exactly one JVM debug file')
        member = candidates[0]
        target = directory / 'libjvm.debuginfo'
        size, digest, crc = 0, hashlib.sha256(), 0
        with source.extractfile(member) as stream, target.open('xb') as output:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                if size > member.size:
                    raise ValueError('Debug member exceeds its declared span')
                output.write(chunk)
                digest.update(chunk)
                crc = zlib.crc32(chunk, crc)
        if size != member.size:
            raise ValueError('Debug member is truncated')
        report.update(selectedMember=member.name, selectedBytes=size,
                      selectedSha256=digest.hexdigest(), selectedGnuCrc32=crc, completed=True)
    return target


def note_ids(raw):
    if len(raw) > MAX_NOTE_BYTES:
        raise ValueError('Build-ID notes exceed bounds')
    position, result = 0, []
    while position < len(raw):
        if len(raw) - position < NOTE_HEADER.size:
            raise ValueError('Truncated ELF note header')
        names, description, kind = NOTE_HEADER.unpack_from(raw, position)
        position += NOTE_HEADER.size
        name_end = position + names
        description_start = position + ((names + 3) & ~3)
        description_end = description_start + description
        end = description_start + ((description + 3) & ~3)
        if end > len(raw):
            raise ValueError('ELF note exceeds its section')
        if raw[position:name_end] == b'GNU\0' and kind == 3:
            if not 0 < description <= 64:
                raise ValueError('Unsupported GNU build-ID width')
            result.append(raw[description_start:description_end].hex())
        position = end
    return result


def debug_link(raw):
    end = raw.find(b'\0')
    if not 0 < end <= 128:
        raise ValueError('Missing or oversized GNU debug-link filename')
    offset = (end + 1 + 3) & ~3
    if len(raw) != offset + 4:
        raise ValueError('Unsupported GNU debug-link padding or checksum span')
    name = raw[:end].decode('ascii')
    if name != 'libjvm.debuginfo':
        raise ValueError('Unexpected GNU debug-link target')
    return {'name': name, 'crc32': struct.unpack_from('<I', raw, offset)[0], 'rawHex': raw.hex()}


def metadata(path, expected, report):
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != expected or symbols.identity(before) != symbols.identity(path.stat()):
            raise ValueError('Native file identity differs before metadata inspection')
        report.update(sha256=digest, bytes=before.st_size)
        captured = report['elf'] = {}
        symbols.inspect(stream, before.st_size, captured)
        notes, links = [], []
        for section in captured['sections']:
            if section['type'] == 7:
                if section['flags'] & 0x800 or section['bytes'] > MAX_NOTE_BYTES:
                    raise ValueError('Unsupported note section encoding or size')
                raw = symbols.read_at(stream, section['fileOffset'], section['bytes'], before.st_size)
                notes.append({'sectionIndex': section['index'], 'rawHex': raw.hex(), 'buildIds': note_ids(raw)})
            if section['name'] == '.gnu_debuglink':
                if section['type'] != 1 or section['flags'] & 0x800 or section['bytes'] > 256:
                    raise ValueError('Unsupported GNU debug-link section')
                links.append(debug_link(symbols.read_at(stream, section['fileOffset'], section['bytes'], before.st_size)))
        report.update(notes=notes, buildIds=[value for row in notes for value in row['buildIds']], debugLinks=links,
                      dwarfSections=[row for row in captured['sections'] if row['name'].startswith(('.debug_', '.zdebug_'))])
        stream.seek(0)
        if hashlib.file_digest(stream, 'sha256').hexdigest() != expected or \
                symbols.identity(before) != symbols.identity(os.fstat(stream.fileno())) or \
                symbols.identity(before) != symbols.identity(path.stat()):
            raise ValueError('Native file changed during metadata inspection')
    report['completed'] = True


def bind(library, debug, extracted, report):
    native, external = report['library'], report['externalDebug']
    metadata(library, LIBRARY_SHA256, native)
    metadata(debug, extracted['selectedSha256'], external)
    if len(native['buildIds']) != 1 or len(external['buildIds']) != 1 or \
            native['buildIds'] != external['buildIds'] or len(native['debugLinks']) != 1 or \
            native['debugLinks'][0]['crc32'] != extracted['selectedGnuCrc32']:
        raise ValueError('GNU build-ID or debug-link checksum does not bind the external file')
    report['externalDebugFileBound'] = True
    report['completed'] = True


def main():
    library, output = map(Path, sys.argv[1:])
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'expectedLibrarySha256': LIBRARY_SHA256, 'externalDebugFileBound': False,
              'library': {}, 'externalDebug': {}, 'archive': {}, 'extraction': {},
              'actualTargetCallingConventionProven': False, 'actualFieldLayoutProven': False,
              'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Official hash-pinned external debug-file identity and GNU build-ID/debug-link CRC binding '
                       'only. No DWARF type/location interpretation, target loading/execution, argument/field reads, '
                       'calling convention, runtime/root/clock/size or compiler saving proof. Temporary official '
                       'archive/native debug bytes are removed; original public URL/hash and raw metadata retained.'}
    try:
        library = library.resolve()
        with library.open('rb') as stream:
            before = os.fstat(stream.fileno())
            report['libraryPreflightSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
            if report['libraryPreflightSha256'] != LIBRARY_SHA256 or \
                    symbols.identity(before) != symbols.identity(library.stat()):
                raise ValueError('Pinned target library differs before archive download')
        with tempfile.TemporaryDirectory(prefix='klause-vm-debug-') as directory:
            temporary = Path(directory)
            archive = temporary / 'official-debugimage.tar.gz'
            download(archive, report['archive'])
            debug = extract(archive, temporary, report['extraction'])
            bind(library, debug, report['extraction'], report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
