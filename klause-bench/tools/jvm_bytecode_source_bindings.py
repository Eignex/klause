"""Join saved instruction positions and symbolic references without JVM resolution."""
from bisect import bisect_right
from collections import OrderedDict, deque
import json
import sqlite3

from review_jvm_declaration_bindings import digest

COLUMNS = {
    'constantPool': ['index', 'tag', 'width', 'references', 'bootstrapIndex', 'methodHandleKind', 'symbolicText'],
    'instructions': ['bci', 'opcode', 'byteLength', 'constantPoolIndex', 'localIndex', 'branchTargets',
                     'switchPaddingBytes', 'subroutineReturnTargetUnresolved'],
    'branchTargets': ['kind', 'bci', 'caseKey'],
    'exceptionHandlers': ['startBci', 'endBciExclusive', 'handlerBci', 'catchType'],
    'lineNumberTables': ['startPc', 'lineNumber', 'instructionBoundary'],
}
DENIED = ('loadedClassSelectionProven', 'runtimeCalleeSelectionProven', 'generatedBodySelectionProven',
          'sourcePhaseToJvmDescriptorMappingProven', 'fullSourcePhaseCoverageProven',
          'physicalCompilerRootCoverageProven', 'compilerElapsedTimeProven', 'acceptedSavingsCredit')
MAX_CANDIDATES = 1_000_000
MAX_HIERARCHY = 100_000
MAX_INDEX_BYTES = 8 * 1024 ** 3


def expand(value):
    if isinstance(value, list):
        return [expand(part) for part in value]
    if not isinstance(value, dict):
        return value
    result = {}
    for key, child in value.items():
        if key not in COLUMNS:
            result[key] = expand(child)
            continue
        if not isinstance(child, list):
            raise ValueError('Bytecode columns are not an array')

        def row(fields):
            if not isinstance(fields, list) or len(fields) != len(COLUMNS[key]):
                raise ValueError('Bytecode column width differs from its schema')
            return expand(dict(zip(COLUMNS[key], fields)))

        result[key] = [[row(entry) for entry in table] for table in child] if key == 'lineNumberTables' else \
            [row(entry) for entry in child]
    return result


def origin_key(origin):
    if not isinstance(origin, dict) or type(origin.get('entryIndex')) is not int or origin['entryIndex'] < 0 or \
            not isinstance(origin.get('entry'), str) or not origin['entry']:
        raise ValueError('Class entry identity is absent')
    jar, module = origin.get('jar'), origin.get('module')
    if not (isinstance(jar, str) and jar and digest(origin.get('jarSha256')) and module is None or
            isinstance(module, str) and module and jar is None):
        raise ValueError('Class container identity is ambiguous')
    return json.dumps([jar, origin.get('jarSha256'), module, origin['entry'], origin['entryIndex']],
                      separators=(',', ':'))


def bind_method(method, previous):
    index = method['methodIndex']
    if any(method[key] != previous[key] for key in ('methodIndex', 'name', 'descriptor', 'accessFlags')) or \
            (method['code'] is not None) != previous['codePresent']:
        raise ValueError('Bytecode and debug method identities differ')
    code = method['code']
    result = {'methodIndex': index, 'name': method['name'], 'descriptor': method['descriptor'],
              'accessFlags': method['accessFlags'], 'codePresent': code is not None}
    if code is None:
        if previous['originalLineNumberTablesCopiedVerbatim'] is not None:
            raise ValueError('Absent code has debug line tables')
        return dict(result, codeIdentity=None, lineEntries=[], instructionLines=[], instructionTableLines=[])
    tables = code['lineNumberTables']
    stripped = [[{key: entry[key] for key in ('startPc', 'lineNumber')} for entry in table] for table in tables]
    if stripped != previous['originalLineNumberTablesCopiedVerbatim']:
        raise ValueError('Original ordered debug line tables differ')
    length = code['identity']['bytes']
    if type(length) is not int or not 0 < length <= 65535 or not digest(code['identity']['sha256']):
        raise ValueError('Code identity is malformed')
    instructions = code['instructions']
    starts, end = set(), 0
    for instruction in instructions:
        if type(instruction['bci']) is not int or instruction['bci'] != end or \
                type(instruction['byteLength']) is not int or instruction['byteLength'] <= 0:
            raise ValueError('Instruction layout is not a complete contiguous code body')
        starts.add(end)
        end += instruction['byteLength']
    if end != length:
        raise ValueError('Instruction layout does not cover the code identity')
    points = {}
    table_points = [{} for _ in tables]
    entries = []
    for table_index, table in enumerate(tables):
        for entry_index, entry in enumerate(table):
            pc = entry['startPc']
            if type(pc) is not int or type(entry['lineNumber']) is not int or \
                    not 0 <= pc <= 65535 or not 0 <= entry['lineNumber'] <= 65535 or \
                    type(entry['instructionBoundary']) is not bool or entry['instructionBoundary'] != (pc in starts):
                raise ValueError('Debug point or instruction-boundary annotation differs')
            position = len(entries)
            entries.append({'tableIndex': table_index, 'entryIndex': entry_index, **entry,
                            'withinCode': pc < length})
            if pc < length:
                points.setdefault(pc, []).append(position)
                table_points[table_index].setdefault(pc, []).append(position)
    ordered = sorted(points)
    positions, table_positions = [], []
    table_order = [sorted(points) for points in table_points]
    for instruction in instructions:
        pc = instruction['bci']
        predecessor = bisect_right(ordered, pc) - 1
        indices = [] if predecessor < 0 else points[ordered[predecessor]]
        positions.append([pc, indices])
        per_table = []
        for table_index, order in enumerate(table_order):
            predecessor = bisect_right(order, pc) - 1
            per_table.append([table_index, [] if predecessor < 0 else
                              table_points[table_index][order[predecessor]]])
        table_positions.append([pc, per_table])
    return dict(result, codeIdentity=code['identity'], lineEntries=entries, instructionLines=positions, instructionTableLines=table_positions,
                instructionCount=len(instructions), debugPredecessorPolicy='greatest-start-pc-across-all-tables',
                debugPredecessorsAreSourceCandidatesOnly=True, runtimeDebugTableSelectionProven=False)


class Symbols:
    def __init__(self, metadata):
        self.pool = {entry['index']: entry for entry in metadata['constantPool']}
        if len(self.pool) != len(metadata['constantPool']):
            raise ValueError('Duplicate constant pool index')
        next_index = 1
        for entry in metadata['constantPool']:
            if type(entry['index']) is not int or entry['index'] != next_index or \
                    type(entry['width']) is not int or entry['width'] not in (1, 2):
                raise ValueError('Constant pool slots are not complete and ordered')
            next_index += entry['width']
        if next_index != metadata['constantPoolSlots']:
            raise ValueError('Constant pool end differs')
        bootstraps = metadata['bootstrapMethods']
        for index, entry in enumerate(bootstraps):
            if entry['index'] != index:
                raise ValueError('Bootstrap table is not complete and ordered')
            self.entry(entry['methodHandleIndex'], 15)
            for argument in entry['argumentIndices']:
                self.entry(argument)
        for entry in self.pool.values():
            for reference in entry['references']:
                self.entry(reference)
            bootstrap = entry['bootstrapIndex']
            if bootstrap is not None and (type(bootstrap) is not int or not 0 <= bootstrap < len(bootstraps)):
                raise ValueError('Dynamic reference has no bootstrap')

    def entry(self, index, tag=None):
        if type(index) is not int or index not in self.pool or tag is not None and self.pool[index]['tag'] != tag:
            raise ValueError('Missing or mismatched constant pool reference')
        return self.pool[index]

    def text(self, index):
        value = self.entry(index, 1)['symbolicText']
        if not isinstance(value, str) or not value:
            raise ValueError('Structural pool text is absent')
        return value

    def class_name(self, index):
        references = self.entry(index, 7)['references']
        if len(references) != 1:
            raise ValueError('Class symbol has wrong arity')
        return self.text(references[0])

    def member(self, entry):
        if entry['tag'] not in (9, 10, 11) or len(entry['references']) != 2:
            raise ValueError('Member reference has wrong kind or arity')
        owner, name_type = entry['references']
        parts = self.entry(name_type, 12)['references']
        if len(parts) != 2:
            raise ValueError('Name and type reference has wrong arity')
        return self.class_name(owner), self.text(parts[0]), self.text(parts[1])


class DeclarationIndex:
    def __init__(self, path):
        self.path = path
        self.cache, self.cache_weight = OrderedDict(), 0
        self.db = sqlite3.connect(str(path))
        self.db.executescript('''
            PRAGMA journal_mode=OFF;
            PRAGMA cache_size=-16384;
            CREATE TABLE classes (ordinal INTEGER PRIMARY KEY, input TEXT, origin TEXT, name TEXT, parents TEXT);
            CREATE UNIQUE INDEX physical_class ON classes(input, origin);
            CREATE INDEX class_name ON classes(name);
            CREATE TABLE contexts (ordinal INTEGER, head TEXT);
            CREATE INDEX context_head ON contexts(head, ordinal);
            CREATE TABLE members (ordinal INTEGER, kind TEXT, position INTEGER, name TEXT, descriptor TEXT);
            CREATE INDEX member_name ON members(ordinal, kind, name, descriptor);
        ''')

    def check_size(self):
        if self.path.stat().st_size > MAX_INDEX_BYTES:
            raise ValueError('Complete declaration index exceeds bounds')

    def add(self, ordinal, row, metadata):
        self.cache.clear()
        self.cache_weight = 0
        heads = row['studySourceHeads']
        if not isinstance(heads, list) or not 0 < len(heads) <= 16 or len(set(heads)) != len(heads) or \
                any(not isinstance(head, str) or len(head) != 40 or not digest(head + '0' * 24) for head in heads):
            raise ValueError('Study contexts are malformed')
        parents = ([metadata['superclass']] if metadata['superclass'] is not None else []) + metadata['interfaces']
        self.db.execute('INSERT INTO classes VALUES (?,?,?,?,?)',
                        (ordinal, row['inputId'], origin_key(row['origin']), metadata['internalName'], json.dumps(parents)))
        self.db.executemany('INSERT INTO contexts VALUES (?,?)', [(ordinal, head) for head in heads])
        for kind, key, position_key in [('method', 'methods', 'methodIndex'), ('field', 'fields', 'fieldIndex')]:
            for position, member in enumerate(metadata[key]):
                if member[position_key] != position:
                    raise ValueError('Declaration indexes are not complete and ordered')
                self.db.execute('INSERT INTO members VALUES (?,?,?,?,?)',
                                (ordinal, kind, position, member['name'], member['descriptor']))
        self.check_size()

    def candidates(self, head, name, kind=None, member=None, descriptor=None):
        key = head, name, kind, member, descriptor
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        seen, pending, classes, matches, missing, cycles = set(), deque([name]), [], [], [], []
        while pending:
            current = pending.popleft()
            if current in seen:
                cycles.append(current)
                continue
            seen.add(current)
            if len(seen) > MAX_HIERARCHY:
                raise ValueError('Complete structural hierarchy exceeds bounds')
            rows = self.db.execute('''SELECT c.ordinal, c.parents
                FROM classes c INDEXED BY class_name CROSS JOIN contexts h INDEXED BY context_head
                WHERE c.name=? AND h.head=? AND c.ordinal=h.ordinal ORDER BY c.ordinal''', (current, head)).fetchall()
            if not rows:
                missing.append(current)
            for ordinal, parents in rows:
                classes.append(ordinal)
                if kind is not None:
                    matches.extend([ordinal, position] for position, in self.db.execute(
                        'SELECT position FROM members WHERE ordinal=? AND kind=? AND name=? AND descriptor=? ORDER BY position',
                        (ordinal, kind, member, descriptor)))
                    # Constructors are not inherited; other ancestors remain candidates without lookup precedence.
                    if member not in ('<init>', '<clinit>'):
                        pending.extend(json.loads(parents))
                if len(classes) + len(matches) > MAX_CANDIDATES:
                    raise ValueError('Complete symbolic declaration candidates exceed bounds')
        result = {'classOrdinals': classes, 'memberCandidates': matches, 'missingClassNames': missing,
                  'revisitedHierarchyNames': cycles, 'jvmResolutionPerformed': False,
                  'virtualDispatchReceiversUnbound': True, 'runtimeGeneratedTargetsUnbound': True}
        weight = 1 + len(classes) + len(matches) + len(missing) + len(cycles)
        while self.cache and (len(self.cache) >= 4096 or self.cache_weight + weight > MAX_CANDIDATES):
            _, removed = self.cache.popitem(last=False)
            self.cache_weight -= 1 + sum(len(removed[part]) for part in
                ('classOrdinals', 'memberCandidates', 'missingClassNames', 'revisitedHierarchyNames'))
        if weight <= MAX_CANDIDATES:
            self.cache[key] = result
            self.cache_weight += weight
        return result

    def close(self):
        self.db.close()
