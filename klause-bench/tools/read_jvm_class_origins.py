"""Retain saved class-load observations and their recorded installed-jar candidates."""
from collections import defaultdict
from pathlib import PurePosixPath
import re
from urllib.parse import unquote, urlsplit

MAX_LINE_BYTES = 64 * 1024
MAX_LINES = 250_000
LINE = re.compile(r'^\[([^\]]+)\]\[info\]\[class,load\]\s+(\S+) source: (.+)$')
HASH = re.compile(r'[0-9a-f]{64}')


def is_log_member(name):
    return re.search(r'/class-load\.log(?:\.[0-9]+(?:\.current)?)?$', name) is not None


def installed_jars(manifest):
    if manifest is None:
        return {}, ['Original installed CLI manifest is absent']
    if not isinstance(manifest, dict) or type(manifest.get('version')) is not int or manifest['version'] != 1:
        raise ValueError('Unsupported original installed manifest')
    launcher = manifest.get('launcher')
    provenance = manifest.get('provenance')
    if not isinstance(launcher, str) or not PurePosixPath(launcher).is_absolute() or \
            '..' in PurePosixPath(launcher).parts or not isinstance(provenance, dict):
        raise ValueError('Malformed original installed manifest')
    distribution = provenance.get('distribution')
    if not isinstance(distribution, dict):
        raise ValueError('Original installed distribution hashes are absent')
    root = PurePosixPath(launcher).parent.parent
    jars = {}
    for name, digest in distribution.items():
        if not isinstance(name, str) or not name or PurePosixPath(name).is_absolute() or \
                '..' in PurePosixPath(name).parts or not isinstance(digest, str) or not HASH.fullmatch(digest):
            raise ValueError('Malformed original installed file identity')
        if name.endswith('.jar'):
            jars[str(root / name)] = {'distributionPath': str(root), 'path': name, 'sha256': digest}
    return jars, []


def jar_path(source):
    try:
        if any(ord(char) < 32 or ord(char) == 127 for char in source) or '?' in source or '#' in source:
            return None
        uri = urlsplit(source)
        if uri.scheme != 'file' or uri.netloc not in ('', 'localhost') or uri.query or uri.fragment or \
                re.search(r'%(?![0-9a-fA-F]{2})', uri.path):
            return None
        path = unquote(uri.path, errors='strict')
        if not PurePosixPath(path).is_absolute() or '..' in PurePosixPath(path).parts or \
                any(ord(char) < 32 for char in path) or not path.endswith('.jar'):
            return None
        return str(PurePosixPath(path))
    except (ValueError, UnicodeError):
        return None


def parse(source, manifest):
    jars, exclusions = installed_jars(manifest)
    records, unparsed = [], []
    for ordinal in range(MAX_LINES + 1):
        raw = source.readline(MAX_LINE_BYTES + 1)
        if not raw:
            break
        if ordinal == MAX_LINES or len(raw) > MAX_LINE_BYTES:
            raise ValueError('Complete class-origin input exceeds bounds')
        text = raw.decode('utf-8').rstrip('\r\n')
        match = LINE.fullmatch(text)
        if match is None:
            unparsed.append({'line': ordinal + 1, 'text': text})
            continue
        timestamp, name, origin = match.groups()
        path = jar_path(origin)
        records.append({'line': ordinal + 1, 'timestampText': timestamp,
                        'className': name.replace('/', '.'), 'source': origin,
                        'recordedInstalledJar': jars.get(path), 'rawLine': text})
    return {'completed': not unparsed, 'records': records, 'unparsedLines': unparsed,
            'exclusions': exclusions, 'classLoaderIdentityProven': False,
            'runtimeSourceBindingProven': False, 'fullSourcePhaseCoverageProven': False,
            'acceptedSavingsCredit': False,
            'scope': 'Complete saved class-load lines and exact recorded installed path/hash candidates. '
                     'No class file is read, no actual loaded byte digest or class-loader identity is inferred. '
                     'CDS, generated, absent, repeated, conflicting and unparsed contexts remain explicit. '
                     'Compilation, source-phase and benefit authority remain separate.'}


def owner(method):
    if not isinstance(method, str) or '(' not in method:
        return None
    qualified = method.split('(', 1)[0]
    return qualified.rsplit('.', 1)[0].replace('/', '.') if '.' in qualified else None


def associate(census, origins):
    by_name = defaultdict(list)
    for index, record in enumerate(origins.get('records', [])):
        by_name[record['className']].append(index)
    classes, represented = [], {}

    def reference(method):
        name = owner(method)
        if name is None:
            return None
        if name not in represented:
            indices = by_name.get(name, [])
            records = [origins['records'][index] for index in indices]
            matches = [record['recordedInstalledJar'] for record in records]
            unique = matches[0] if matches and all(value == matches[0] for value in matches) else None
            represented[name] = len(classes)
            classes.append({'className': name, 'originRecordIndices': list(indices),
                            'unambiguousRecordedInstalledJar': unique if origins.get('completed') is True else None,
                            'classLoaderIdentityProven': False, 'actualLoadedByteDigestProven': False})
        return represented[name]

    roots = [{'eventIndex': row.get('eventIndex'), 'classIndex': reference(row.get('method'))}
             for row in census.get('compilations', [])]
    inlining = [{'eventIndex': row.get('eventIndex'), 'callerClassIndex': reference(row.get('caller')),
                 'calleeClassIndex': reference(row.get('callee'))} for row in census.get('inlining', [])]
    return {'classes': classes, 'compilationClassReferences': roots, 'inliningClassReferences': inlining,
            'runtimeSourceBindingProven': False, 'fullSourcePhaseCoverageProven': False,
            'acceptedSavingsCredit': False}
