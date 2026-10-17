"""Authenticate complete compiler syntax ranges and retain every overlapping line candidate."""
from array import array
from bisect import bisect_right
from collections import OrderedDict
import gzip
import hashlib
import json

from review_jvm_declaration_bindings import digest, file_identity, source_specification, unique_object

ENCODING = 'source-syntax-columns-v1'
NODE_COLUMNS = ['index', 'parentIndex', 'kind', 'psiKind', 'name', 'role', 'package', 'range',
                'syntaxError', 'referencedName', 'symbolResolutionProven', 'jvmMemberOrGeneratedBodyInferred']
RANGE_COLUMNS = ['startUtf16', 'endUtf16Exclusive', 'startByte', 'endByteExclusive', 'firstLine', 'lastLine', 'sha256']
MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_REPORT_BYTES = 4 * 1024 ** 3
MAX_NODES = 10_000_000
MAX_DEPTH = 4096
MAX_KINDS = 4096
MAX_QUERY_NODES = 100_000
MAX_CACHED_NODES = 1_000_000
MAX_CACHED_QUERIES = 4096
DENIED = ('loadedClassSelectionProven', 'symbolResolutionProven', 'sourcePhaseToJvmDescriptorMappingProven',
          'fullSourcePhaseCoverageProven', 'compilerElapsedTimeProven', 'acceptedSavingsCredit')


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


class SourceOffsets:
    def __init__(self, text):
        self.raw = text.encode('utf-8')
        self.bytes = array('i', [0])
        self.lines = array('i', [0])
        byte, unit = 0, 0
        for index, char in enumerate(text):
            if char == '\r':
                self.lines.append(unit + (2 if text[index + 1:index + 2] == '\n' else 1))
            elif char == '\n' and text[index - 1:index] != '\r':
                self.lines.append(unit + 1)
            width = 2 if ord(char) > 0xffff else 1
            byte += len(char.encode('utf-8'))
            if width == 2:
                self.bytes.append(-1)
            self.bytes.append(byte)
            unit += width

    def check(self, values):
        if not isinstance(values, list) or len(values) != len(RANGE_COLUMNS):
            raise ValueError('Malformed syntax source range')
        start, end, first_byte, last_byte, first_line, last_line, checksum = values
        if any(not integer(value) for value in values[:6]) or not start <= end < len(self.bytes) or \
                self.bytes[start] < 0 or self.bytes[end] < 0 or \
                (first_byte, last_byte) != (self.bytes[start], self.bytes[end]) or \
                first_line != bisect_right(self.lines, start) or \
                last_line != bisect_right(self.lines, end - 1 if end > start else end) or \
                not digest(checksum) or hashlib.sha256(self.raw[first_byte:last_byte]).hexdigest() != checksum:
            raise ValueError('Syntax range differs from original UTF16, UTF8, line or byte identity')
        return start, end, first_line, last_line


class SourceNodes:
    def __init__(self):
        self.first, self.last, self.low, self.high, self.after = (array('i') for _ in range(5))
        self.hits = bytearray()
        self.package = None
        self.stack = []

    def close_node(self, end):
        index, _, _ = self.stack.pop()
        self.after[index] = end
        if self.stack:
            parent = self.stack[-1][0]
            self.low[parent] = min(self.low[parent], self.low[index])
            self.high[parent] = max(self.high[parent], self.high[index])

    def add(self, node, offsets, kind_count):
        if not isinstance(node, list) or len(node) != len(NODE_COLUMNS):
            raise ValueError('Malformed complete syntax node')
        index, parent, kind, psi, name, role, package, values, error, reference, resolved, generated = node
        if type(index) is not int or index != len(self.first) or not integer(psi) or psi >= kind_count or \
                not isinstance(kind, str) or not kind or resolved is not False or generated is not False or \
                any(value is not None and not isinstance(value, str) for value in (name, role, package, error, reference)):
            raise ValueError('Syntax node index, class, text or denial flags are invalid')
        start, end, first, last = offsets.check(values)
        if index == 0:
            if parent is not None or kind != 'source-file' or not isinstance(package, str) or \
                    start != 0 or end != len(offsets.bytes) - 1:
                raise ValueError('Complete original source root is absent')
            self.package = package
        else:
            if not integer(parent) or parent >= index or package is not None or kind == 'source-file':
                raise ValueError('Syntax parent or source boundary is invalid')
            while self.stack and self.stack[-1][0] != parent:
                self.close_node(index)
            if not self.stack or not self.stack[-1][1] <= start <= end <= self.stack[-1][2]:
                raise ValueError('Syntax parent is not an enclosing active lexical ancestor')
        if len(self.stack) > MAX_DEPTH:
            raise ValueError('Complete syntax parent depth exceeds bounds')
        self.first.append(first)
        self.last.append(last)
        self.low.append(first)
        self.high.append(last)
        self.after.append(0)
        self.hits.append(0)
        self.stack.append((index, start, end))
        return int(kind == 'syntax-error')

    def finish(self):
        if not self.first:
            raise ValueError('Syntax source contains no root')
        while self.stack:
            self.close_node(len(self.first))

    def query(self, line):
        matches, index = [], 0
        while index < len(self.first):
            if not self.low[index] <= line <= self.high[index]:
                index = self.after[index]
                continue
            if self.first[index] <= line <= self.last[index]:
                if len(matches) >= MAX_QUERY_NODES:
                    raise ValueError('Complete syntax line query exceeds bounds')
                matches.append(index)
            index += 1
        return tuple(matches)


class SourceSyntaxIndex:
    def __init__(self, specification, path, completion):
        self.sources = source_specification(specification, allow_empty_packages=True)
        paths, total = set(), 0
        for source in self.sources:
            text, name, path_name = source.get('sourceText'), source['sourceFile'], source['path']
            if not isinstance(text, str) or len(source['id']) > 4096 or path_name in paths or ':' in path_name or \
                    path_name.rsplit('/', 1)[-1] != name or not path_name.endswith(('.kt', '.kts', '.java')) or \
                    any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in source['id'] + path_name):
                raise ValueError('Complete syntax source identity is absent or ambiguous')
            paths.add(path_name)
            raw = text.encode('utf-8')
            total += len(raw)
            if total > MAX_SOURCE_BYTES or hashlib.sha256(raw).hexdigest() != source['sha256']:
                raise ValueError('Complete syntax source bytes differ or exceed bounds')
        if not isinstance(completion, dict) or any(completion.get(flag) is not False for flag in DENIED) or \
                completion.get('reportEncoding') != ENCODING or \
                file_identity(path) != {'size': completion.get('reportBytes'), 'sha256': completion.get('reportSha256')}:
            raise ValueError('Syntax report identity, encoding or scope is not admitted')
        self.nodes, self.cache, self.cached_nodes = [], OrderedDict(), 0
        self.records = self.errors = self.node_count = self.report_bytes = 0
        checksum, kinds = hashlib.sha256(), []
        active, offsets, source_errors = None, None, 0
        with gzip.open(path, 'rb') as stream:
            while raw := stream.readline(MAX_JSON_BYTES + 1):
                self.report_bytes += len(raw)
                if len(raw) > MAX_JSON_BYTES or not raw.endswith(b'\n') or self.report_bytes > MAX_REPORT_BYTES:
                    raise ValueError('Complete syntax record stream exceeds bounds or is truncated')
                checksum.update(raw)
                row = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
                if not isinstance(row, dict):
                    raise ValueError('Syntax record is not an object')
                record = row.get('record')
                if self.records == 0:
                    if row != {'record': 'schema', 'encoding': ENCODING,
                               'nodeColumns': NODE_COLUMNS, 'rangeColumns': RANGE_COLUMNS}:
                        raise ValueError('Unknown complete syntax schema')
                elif record == 'source-start':
                    index = row.get('sourceIndex')
                    if active is not None or type(index) is not int or index != len(self.nodes) or \
                            not 0 <= index < len(self.sources):
                        raise ValueError('Syntax source order, identity or boundary is invalid')
                    source = self.sources[index]
                    if row != {'record': record, 'sourceIndex': index, 'sourceId': source['id'],
                               'path': source['path'], 'sha256': source['sha256']}:
                        raise ValueError('Syntax source differs from complete original input')
                    active, offsets, source_errors = SourceNodes(), SourceOffsets(source['sourceText']), 0
                elif record == 'psi-kind':
                    name = row.get('class')
                    if active is None or type(row.get('index')) is not int or row['index'] != len(kinds) or \
                            len(kinds) >= MAX_KINDS or not isinstance(name, str) or not name or \
                            len(name) > 4096 or name in kinds or set(row) != {'record', 'index', 'class'}:
                        raise ValueError('Invalid syntax class dictionary')
                    kinds.append(name)
                elif record == 'syntax-node':
                    if active is None or type(row.get('sourceIndex')) is not int or \
                            row['sourceIndex'] != len(self.nodes) or set(row) != {'record', 'sourceIndex', 'node'}:
                        raise ValueError('Syntax node is outside its original source')
                    self.node_count += 1
                    if self.node_count > MAX_NODES:
                        raise ValueError('Complete syntax node set exceeds bounds')
                    source_errors += active.add(row['node'], offsets, len(kinds))
                elif record == 'source-end':
                    if active is None or row != {'record': record, 'sourceIndex': len(self.nodes),
                            'syntaxNodes': len(active.first), 'syntaxErrors': source_errors} or \
                            any(type(row.get(key)) is not int for key in ('sourceIndex', 'syntaxNodes', 'syntaxErrors')):
                        raise ValueError('Syntax source completion differs from complete nodes')
                    active.finish()
                    self.nodes.append(active)
                    self.errors += source_errors
                    active, offsets = None, None
                else:
                    raise ValueError('Unknown or repeated syntax record')
                self.records += 1
        if active is not None or len(self.nodes) != len(self.sources) or \
                any(type(completion.get(key)) is not int for key in
                    ('sources', 'records', 'syntaxErrors', 'uncompressedReportBytes')) or \
                completion['sources'] != len(self.sources) or completion['records'] != self.records or \
                completion['syntaxErrors'] != self.errors or completion['uncompressedReportBytes'] != self.report_bytes or \
                completion.get('uncompressedReportSha256') != checksum.hexdigest() or \
                completion.get('allInputSourcesVisited') is not True or \
                completion.get('completeGzipRoundTripVerified') is not True:
            raise ValueError('Complete syntax stream differs from its producer census or byte identity')
        self.complete = completion.get('completed') is True and self.errors == 0 and \
            completion.get('allSourcesSyntacticallyValid') is True
        self.report_identity = file_identity(path)

    def query(self, source, line):
        if not integer(source) or source >= len(self.nodes) or not integer(line):
            raise ValueError('Invalid syntax source or line query')
        key = source, line
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        matches = self.nodes[source].query(line)
        while self.cache and (len(self.cache) >= MAX_CACHED_QUERIES or
                              self.cached_nodes + len(matches) > MAX_CACHED_NODES):
            _, prior = self.cache.popitem(last=False)
            self.cached_nodes -= len(prior)
        if MAX_CACHED_QUERIES > 0 and len(matches) <= MAX_CACHED_NODES:
            self.cache[key] = matches
            self.cached_nodes += len(matches)
        return matches

    def mark(self, source, nodes):
        for node in nodes:
            self.nodes[source].hits[node] = 1
