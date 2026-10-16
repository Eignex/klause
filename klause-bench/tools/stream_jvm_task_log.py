"""Read complete saved XML while retaining compilation metadata rather than its tree."""
import codecs
from collections import Counter
import hashlib
import xml.etree.ElementTree as ET

import review_native_vm_log_identities as identities

MAX_INPUT_BYTES = 128 * 1024 * 1024
MAX_ELEMENTS = 16000000
MAX_METADATA_ROWS = 1000000
MAX_DEPTH = 1024
CHUNK_BYTES = 64 * 1024
AUXILIARY_TAGS = ('failure', 'make_not_entrant', 'make_not_compilable', 'deoptimized')


class MetadataTarget:
    def __init__(self):
        self.result = {'rootAttributes': {}, 'logs': [], 'tasks': [], 'nmethods': [], 'queued': [],
                       'fragments': [], 'orphanedRecords': [], 'errors': [], 'otherTags': {}}
        self.auxiliary = []
        self.stack = []
        self.other = Counter()
        self.ordinal = 0
        self.rows = 0

    def reserve(self):
        self.rows += 1
        if self.rows > MAX_METADATA_ROWS:
            raise ValueError('Compilation metadata exceeds retained-row bounds')

    def start(self, tag, attributes):
        self.ordinal += 1
        if self.ordinal > MAX_ELEMENTS or len(self.stack) >= MAX_DEPTH:
            raise ValueError('Compilation XML exceeds element or depth bounds')
        parent = self.stack[-1] if self.stack else None
        if parent is None:
            if tag != 'hotspot_log':
                raise ValueError('Saved task log has an unsupported document root')
            self.result['rootAttributes'] = dict(attributes)
        else:
            parent['hasChild'] = True
        owner = parent['owner'] if parent is not None else None
        task = parent['task'] if parent is not None else None
        auxiliary_task = parent['auxiliaryTask'] if parent is not None else None
        text_rows = []
        if tag == 'compilation_log':
            self.reserve()
            row = {'documentIndex': self.ordinal, 'rawAttributes': dict(attributes), 'startMarkers': [], 'errors': []}
            row['thread'] = identities.number(attributes, 'thread', row['errors'], 1)
            if row['thread'] is None or owner is not None or task is not None:
                row['errors'].append('Missing or nested compilation-log owner')
            owner, task = len(self.result['logs']), None
            self.result['logs'].append(row)
        elif tag == 'start_compile_thread':
            self.reserve()
            row = {'documentIndex': self.ordinal, 'rawAttributes': dict(attributes), 'errors': []}
            row['thread'] = identities.number(attributes, 'thread', row['errors'], 1)
            row['process'] = identities.number(attributes, 'process', row['errors'], 1)
            if owner is None or task is not None:
                self.result['orphanedRecords'].append(row)
            else:
                self.result['logs'][owner]['startMarkers'].append(row)
        elif tag in ('task', 'nmethod', 'task_queued'):
            self.reserve()
            row = identities.identity(ET.Element(tag, attributes), self.ordinal)
            row['ownerLogIndex'] = owner
            if tag == 'task':
                row['taskDone'] = []
                if task is not None:
                    row['errors'].append('Nested task identity')
                task = len(self.result['tasks'])
                auxiliary_task = task
                self.result['tasks'].append(row)
            else:
                self.result['nmethods' if tag == 'nmethod' else 'queued'].append(row)
        elif tag == 'task_done':
            self.reserve()
            row = {'documentIndex': self.ordinal, 'rawAttributes': dict(attributes), 'errors': []}
            row['success'] = identities.number(attributes, 'success', row['errors'])
            if row['success'] not in (0, 1):
                row['errors'].append('Missing or invalid task outcome')
            if task is None or parent['tag'] != 'task':
                self.result['orphanedRecords'].append(row)
            else:
                self.result['tasks'][task]['taskDone'].append(row)
        elif tag == 'fragment':
            self.reserve()
            row = {'documentIndex': self.ordinal, 'ownerLogIndex': owner,
                   'rawAttributes': dict(attributes), 'text': None}
            self.result['fragments'].append(row)
            text_rows.append(row)
        else:
            self.other[tag] += 1
        if tag in AUXILIARY_TAGS:
            self.reserve()
            row = {'documentIndex': self.ordinal, 'tag': tag, 'taskIndex': auxiliary_task,
                   'rawAttributes': dict(attributes), 'text': None}
            self.auxiliary.append(row)
            text_rows.append(row)
        self.stack.append({'tag': tag, 'owner': owner, 'task': task, 'auxiliaryTask': auxiliary_task,
                           'hasChild': False, 'textRows': text_rows, 'textParts': []})

    def data(self, text):
        if self.stack:
            frame = self.stack[-1]
            if frame['textRows'] and not frame['hasChild']:
                frame['textParts'].append(text)

    def end(self, tag):
        frame = self.stack.pop()
        assert frame['tag'] == tag
        text = ''.join(frame['textParts']) if frame['textParts'] else None
        for row in frame['textRows']:
            row['text'] = text

    def doctype(self, name, public_id, system_id):
        raise ValueError('Saved task log contains an unsupported document declaration')

    def close(self):
        self.result['otherTags'] = dict(self.other)
        for collection in ('logs', 'tasks', 'nmethods', 'queued'):
            for position, row in enumerate(self.result[collection]):
                if row['errors']:
                    self.result['errors'].append({'collection': collection, 'index': position, 'errors': row['errors']})
        if self.result['orphanedRecords']:
            self.result['errors'].append({'reason': 'Orphaned compiler thread or task completion records'})
        return self.result, self.auxiliary


def parse_stream(source, expected):
    target = MetadataTarget()
    parser = ET.XMLParser(target=target)
    decoder = codecs.getincrementaldecoder('utf-8')()
    digest, size, overlap = hashlib.sha256(), 0, b''
    while raw := source.read(CHUNK_BYTES):
        size += len(raw)
        if size > MAX_INPUT_BYTES:
            raise ValueError('Saved task log exceeds streaming input bounds')
        digest.update(raw)
        declaration = overlap + raw
        if b'<!DOCTYPE' in declaration or b'<!ENTITY' in declaration:
            raise ValueError('Saved task log contains an unsupported document declaration')
        overlap = declaration[-16:]
        parser.feed(decoder.decode(raw))
    parser.feed(decoder.decode(b'', final=True))
    result = parser.close()
    if {'size': size, 'sha256': digest.hexdigest()} != {key: expected[key] for key in ('size', 'sha256')}:
        raise ValueError('Streamed task log differs from complete original member identity')
    return result
