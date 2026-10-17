"""Source-byte-checked line candidates; compiler execution and phase ownership stay separate."""
import hashlib
import re

from jvm_source_debug import map_line, payload_maps
from review_jvm_declaration_bindings import digest, source_specification

MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_REGIONS = 100_000
MAX_CLASS_LINES = 65_536
DENIED = ('loadedClassSelectionProven', 'runtimeGeneratedClassesBound',
          'sourcePhaseToJvmDescriptorMappingProven', 'fullSourcePhaseCoverageProven',
          'compilerElapsedTimeProven', 'acceptedSavingsCredit')


class SourceLineBindings:
    def __init__(self, specification, *, allow_empty_packages=False):
        self.sources = source_specification(specification, allow_empty_packages=allow_empty_packages)
        self.by_name, self.source_proofs, self.hits = {}, [], set()
        total_bytes, total_regions = 0, 0
        for index, source in enumerate(self.sources):
            text = source.get('sourceText')
            if not isinstance(text, str):
                raise ValueError('Complete original implementation source bytes are required')
            raw = text.encode('utf-8')
            total_bytes += len(raw)
            if total_bytes > MAX_SOURCE_BYTES or hashlib.sha256(raw).hexdigest() != source['sha256']:
                raise ValueError('Implementation source identity differs or exceeds bounds')
            lines = re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)', text)
            if lines and lines[-1] == '':
                lines.pop()
            regions, ids = source.get('regions', []), set()
            if not isinstance(regions, list):
                raise ValueError('Source regions are not an array')
            total_regions += len(regions)
            if total_regions > MAX_REGIONS:
                raise ValueError('Complete source region inventory exceeds bounds')
            for region in regions:
                if not isinstance(region, dict) or not isinstance(region.get('id'), str) or not region['id'] or \
                        region['id'] in ids or type(region.get('firstLine')) is not int or \
                        type(region.get('lastLine')) is not int or \
                        not 1 <= region['firstLine'] <= region['lastLine'] <= len(lines) or \
                        not digest(region.get('sha256')):
                    raise ValueError('Invalid, duplicate or out-of-source region')
                ids.add(region['id'])
                content = ''.join(lines[region['firstLine'] - 1:region['lastLine']]).encode('utf-8')
                if hashlib.sha256(content).hexdigest() != region['sha256']:
                    raise ValueError('Source region bytes differ from their identity')
            aliases = source.get('debugPaths', [])
            if not isinstance(aliases, list) or any(not isinstance(path, str) or not path for path in aliases):
                raise ValueError('Debug path annotations are malformed')
            self.by_name.setdefault(source['sourceFile'], []).append((index, source, len(lines)))
            self.source_proofs.append({'sourceIndex': index, 'id': source['id'], 'path': source['path'],
                'sha256': source['sha256'], 'bytes': len(raw), 'sourceInputBytesAuthenticated': True,
                'sourceLines': len(lines), 'regionsCopiedVerbatim': regions,
                'debugPathAnnotationsCopiedVerbatim': aliases})

    def source_candidates(self, file, line, package):
        result = []
        for index, source, line_count in self.by_name.get(file['name'], []):
            in_source = 1 <= line <= line_count
            regions = [{'regionIndex': i, 'regionId': region['id']}
                       for i, region in enumerate(source.get('regions', []))
                       if region['firstLine'] <= line <= region['lastLine']]
            if in_source:
                self.hits.add(index)
            result.append({'sourceIndex': index, 'sourceId': source['id'], 'sourceLineExists': in_source,
                'packageAnnotationAgrees': package in source['packages'],
                'debugPathAnnotationAgrees': file.get('path') is not None and
                    file['path'] in source.get('debugPaths', []),
                'matchingRegions': regions, 'sourceSelectionProven': False})
        return result

    def bind(self, row):
        metadata = row.get('metadata')
        if row.get('error') is not None or not isinstance(metadata, dict):
            return {'status': 'unavailable-class-metadata', 'originalErrorCopiedVerbatim': row.get('error'),
                    'inputMetadataIntegrityVerified': False, **{flag: False for flag in DENIED}}
        name, files, methods = metadata.get('internalName'), metadata.get('sourceFiles'), metadata.get('methods')
        if not isinstance(name, str) or not name or not isinstance(files, list) or \
                any(not isinstance(file, str) for file in files) or not isinstance(methods, list):
            raise ValueError('Malformed complete class metadata')
        identity = metadata.get('identity')
        if 'error' not in row or not isinstance(identity, dict) or type(identity.get('bytes')) is not int or \
                not 0 < identity['bytes'] <= 16 * 1024 * 1024 or \
                not digest(row.get('classSha256')) or identity.get('sha256') != row['classSha256']:
            raise ValueError('Class metadata identity is absent or inconsistent')
        package = name.rsplit('/', 1)[0].replace('/', '.') if '/' in name else ''
        maps = payload_maps(metadata)
        declared, generated_lines = [], set()
        for index, method in enumerate(methods):
            if not isinstance(method, dict) or not isinstance(method.get('name'), str) or \
                    not method['name'] or not isinstance(method.get('descriptor'), str) or not method['descriptor']:
                raise ValueError('Malformed declared method identity')
            if 'code' not in method:
                raise ValueError('Code attribute presence is unavailable')
            code = method['code']
            if code is not None and not isinstance(code, dict):
                raise ValueError('Malformed method code metadata')
            if not isinstance(method.get('attributes'), list) or \
                    method['attributes'].count('Code') != (0 if code is None else 1):
                raise ValueError('Code presence differs from method attributes')
            tables = None if code is None else code.get('lineNumberTables')
            if code is not None and not isinstance(tables, list):
                raise ValueError('Missing complete line table inventory')
            if code is not None and (not isinstance(code.get('attributes'), list) or
                    code['attributes'].count('LineNumberTable') != len(tables)):
                raise ValueError('Line table inventory differs from code attributes')
            for table in tables or []:
                if not isinstance(table, list):
                    raise ValueError('Line table is not an array')
                for entry in table:
                    if not isinstance(entry, dict) or type(entry.get('lineNumber')) is not int or \
                            not 0 <= entry['lineNumber'] <= 65535 or type(entry.get('startPc')) is not int or \
                            not 0 <= entry['startPc'] <= 65535:
                        raise ValueError('Malformed line table entry')
                    generated_lines.add(entry['lineNumber'])
            declared.append({'methodIndex': index, 'name': method['name'], 'descriptor': method['descriptor'],
                'accessFlags': method.get('accessFlags'), 'codePresent': code is not None,
                'originalLineNumberTablesCopiedVerbatim': tables,
                'lineMetadataUnavailable': not tables or not any(tables)})
        if len(generated_lines) > MAX_CLASS_LINES:
            raise ValueError('Class line query inventory exceeds bounds')
        mapped = []
        for line in sorted(generated_lines):
            candidates = []
            for index, file_name in enumerate(files):
                file = {'name': file_name, 'path': None}
                candidates.append({'kind': 'final-source-attribute', 'stratum': 'Java',
                    'sourceFileAttributeIndex': index, 'file': file, 'inputLine': line,
                    'sourceCandidates': self.source_candidates(file, line, package)})
            for item in maps:
                if item['status'] != 'parsed-resolved-smap':
                    continue
                for candidate in map_line(item['map'], line):
                    candidates.append(dict(candidate, kind='source-debug-stratum',
                        attributeIndex=item['attributeIndex'],
                        sourceCandidates=self.source_candidates(candidate['file'], candidate['inputLine'], package)))
            mapped.append({'generatedLine': line, 'candidates': candidates, 'unavailable': not candidates})
        return {'status': 'reviewed', 'internalName': name, 'sourceFilesCopiedVerbatim': files,
            'sourceFileAttributeAmbiguous': len(files) != 1, 'multipleDebugAttributes': len(maps) > 1,
            'sourceDebugMaps': maps, 'inputMetadataIntegrityVerified': all(item['inputIdentityVerified'] for item in maps),
            'reviewCompleteWithinBounds': not any(item['boundExceeded'] for item in maps),
            'allSourceDebugPayloadsParsed': all(item['status'] == 'parsed-resolved-smap' for item in maps),
            'sourceDebugAttributeAbsent': maps == [], 'methods': declared, 'lineCandidates': mapped,
            'bytecodeIntervalsOrExecutedPathsInferred': False,
            **{flag: False for flag in DENIED}}

    def finish(self):
        return {'sources': [dict(proof, noInRangeLineCandidate=index not in self.hits)
                            for index, proof in enumerate(self.source_proofs)], **{flag: False for flag in DENIED}}
