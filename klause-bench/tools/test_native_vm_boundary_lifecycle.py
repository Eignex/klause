import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_vm_boundary_lifecycle as lifecycle


class NativeVmBoundaryLifecycleTest(unittest.TestCase):
    def test_duplicate_entry_aliases_keep_one_exact_probe_and_all_references(self):
        query = 'compiler_thread_loop'
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        sha = hashlib.sha256(b'cde').hexdigest()
        rows = [{'originalSymbol': {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                                    'sectionIndex': 0, 'value': 102, 'size': 3, 'symbolIndex': index,
                                    'name': '_Zloopv', 'nameHex': b'_Zloopv'.hex()},
                 'completed': True, 'fileOffset': 2, 'entrySha256': sha, 'entryBytes': 3,
                 'entryHex': b'cde'.hex(), 'executableSegment': segment,
                 'truncated': False, 'byteCoverage': 'complete symbol span'} for index in [1, 2]]
        references = [{'entryRecordIndex': index, 'sourceMatchIndex': index} for index in [0, 1]]
        definition = {'address': 102, 'declaredBytes': 3, 'fileOffset': 2, 'entrySha256': sha,
                      'sourceAndEntryRecords': references}
        group = {'uniquePhysicalEntryProven': True,
                 'expectedSourceSignature': lifecycle.binding.signatures.SIGNATURES[query],
                 'physicalDefinitions': [definition],
                 'associations': [{'sourceMatchIndex': index, 'entryRecordIndices': [index]} for index in [0, 1]]}
        associations = {'queries': [query], 'allQueriesUniquelyBound': True, 'completeSymbolTableCoverage': True,
                        'bindings': {query: group}}
        entries = {'queries': [query], 'entries': {query: rows},
                   'symbols': {'sections': [section]}, 'programHeaders': [segment]}

        with patch.object(lifecycle, 'BOUNDARIES', (query,)):
            selected = lifecycle.select_entries(io.BytesIO(b'abcdefgh'), 8, associations, entries)

        self.assertEqual('_Zloopv', selected[query]['name'])
        self.assertEqual(references, selected[query]['sourceEntryReferences'])
        self.assertEqual(definition, selected[query]['physicalDefinition'])

    def test_changed_source_entry_or_probe_identity_prevents_selection(self):
        query = 'compiler_thread_loop'
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        sha = hashlib.sha256(b'cde').hexdigest()
        entry = {'originalSymbol': {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                                   'sectionIndex': 0, 'value': 102, 'size': 3,
                                   'name': '_Zloopv', 'nameHex': b'_Zloopv'.hex()},
                 'completed': True, 'fileOffset': 2, 'entrySha256': sha, 'entryBytes': 3,
                 'entryHex': b'cde'.hex(), 'executableSegment': segment,
                 'truncated': False, 'byteCoverage': 'complete symbol span'}
        definition = {'address': 102, 'declaredBytes': 3, 'fileOffset': 2, 'entrySha256': sha,
                      'sourceAndEntryRecords': [{'entryRecordIndex': 0, 'sourceMatchIndex': 0}]}
        group = {'uniquePhysicalEntryProven': True,
                 'expectedSourceSignature': lifecycle.binding.signatures.SIGNATURES[query],
                 'physicalDefinitions': [definition], 'associations': [{'sourceMatchIndex': 0, 'entryRecordIndices': [0]}]}
        variants = [({'uniquePhysicalEntryProven': False}, {}, {}),
                    ({'expectedSourceSignature': 'other()'}, {}, {}),
                    ({}, {'fileOffset': 0}, {}), ({}, {'sourceAndEntryRecords': []}, {}),
                    ({}, {'sourceAndEntryRecords': [{'entryRecordIndex': True, 'sourceMatchIndex': 0}]}, {}),
                    ({'associations': []}, {}, {}), ({}, {}, {'completed': False}),
                    ({}, {}, {'entryHex': '000000'}),
                    ({}, {}, {'originalSymbol': {**entry['originalSymbol'], 'undefined': True}}),
                    ({}, {}, {'originalSymbol': {**entry['originalSymbol'], 'size': True}}),
                    ({}, {}, {'originalSymbol': {**entry['originalSymbol'], 'name': '*'}})]
        for group_change, definition_change, entry_change in variants:
            with self.subTest(group=group_change, definition=definition_change, entry=entry_change), \
                    patch.object(lifecycle, 'BOUNDARIES', (query,)), self.assertRaises(ValueError):
                changed = {**group, 'physicalDefinitions': [{**definition, **definition_change}], **group_change}
                lifecycle.select_entries(io.BytesIO(b'abcdefgh'), 8,
                    {'queries': [query], 'allQueriesUniquelyBound': True, 'completeSymbolTableCoverage': True,
                     'bindings': {query: changed}},
                    {'queries': [query], 'entries': {query: [{**entry, **entry_change}]},
                     'symbols': {'sections': [section]}, 'programHeaders': [segment]})

    def test_incomplete_inventory_is_rejected_before_library_reads(self):
        query = 'compiler_thread_loop'
        report = {'queries': [query], 'allQueriesUniquelyBound': True, 'completeSymbolTableCoverage': True,
                  'bindings': {query: {}}}
        entries = {'queries': [query], 'entries': {query: []}}
        for changes in [{'queries': []}, {'allQueriesUniquelyBound': False},
                        {'completeSymbolTableCoverage': False}, {'bindings': {}}]:
            with self.subTest(changes=changes), patch.object(lifecycle, 'BOUNDARIES', (query,)), \
                    patch.object(lifecycle.binding.entries, 'entry_bytes') as read, self.assertRaises(ValueError):
                lifecycle.select_entries(io.BytesIO(b'abcdefgh'), 8, {**report, **changes}, entries)

            read.assert_not_called()

    def test_different_source_boundaries_cannot_share_a_probe_location(self):
        queries = ('compiler_thread_loop', 'init_compiler_runtime')
        section = {'type': 1, 'flags': 6, 'address': 100, 'bytes': 8, 'fileOffset': 0}
        segment = {'type': 1, 'flags': 5, 'address': 100, 'fileBytes': 8, 'fileOffset': 0}
        sha = hashlib.sha256(b'cde').hexdigest()
        entries, groups = {}, {}
        for index, query in enumerate(queries):
            name = f'_Zentry{index}v'
            row = {'originalSymbol': {'function': True, 'undefined': False, 'ordinarySectionDefinition': True,
                                     'sectionIndex': 0, 'value': 102, 'size': 3,
                                     'name': name, 'nameHex': name.encode().hex()},
                   'completed': True, 'fileOffset': 2, 'entrySha256': sha, 'entryBytes': 3,
                   'entryHex': b'cde'.hex(), 'executableSegment': segment,
                   'truncated': False, 'byteCoverage': 'complete symbol span'}
            entries[query] = [row]
            groups[query] = {'uniquePhysicalEntryProven': True,
                'expectedSourceSignature': lifecycle.binding.signatures.SIGNATURES[query],
                'associations': [{'sourceMatchIndex': 0, 'entryRecordIndices': [0]}],
                'physicalDefinitions': [{'address': 102, 'declaredBytes': 3, 'fileOffset': 2, 'entrySha256': sha,
                    'sourceAndEntryRecords': [{'entryRecordIndex': 0, 'sourceMatchIndex': 0}]}]}

        with patch.object(lifecycle, 'BOUNDARIES', queries), self.assertRaises(ValueError):
            lifecycle.select_entries(io.BytesIO(b'abcdefgh'), 8,
                {'queries': list(queries), 'allQueriesUniquelyBound': True, 'completeSymbolTableCoverage': True,
                 'bindings': groups},
                {'queries': list(queries), 'entries': entries, 'symbols': {'sections': [section]},
                 'programHeaders': [segment]})

    def test_callback_program_reads_only_public_usdt_payloads(self):
        selected = {query: {'name': f'_Zentry{index}v'} for index, query in enumerate(lifecycle.BOUNDARIES)}

        script = lifecycle.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, selected)

        for line in script.splitlines():
            if line.startswith(('uprobe:', 'uretprobe:', 'tracepoint:')):
                with self.subTest(probe=line.split()[0]):
                    self.assertNotIn('arg0', line)
                    self.assertNotIn('arg1', line)
                    self.assertNotIn('buf(', line)
                    self.assertNotIn('retval', line)
                    self.assertIn('/pid == 1234/', line)
                    self.assertIn('has_key(@sequence, tid)', line)
        self.assertIn('buf(uptr(arg0), arg1 < 128 ? arg1 : 128)', script)
        self.assertIn('VALIDATION_FAULT_EXPECTED', script)

    def test_kernel_lifecycle_and_terminal_records_share_guarded_thread_sequences(self):
        selected = {query: {'name': f'_Zentry{index}v'} for index, query in enumerate(lifecycle.BOUNDARIES)}

        script = lifecycle.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, selected)

        self.assertIn('sched_process_fork /pid == 1234/', script)
        self.assertNotIn('/args->parent_pid == 1234/', script)
        self.assertIn('sched_process_exit /pid == 1234/', script)
        self.assertIn('LIFECYCLE\\tEXEC', script)
        terminal = script.splitlines()[-1]
        self.assertLess(terminal.index('FINAL_SEQUENCE\\t'), terminal.index('FINAL_SEQUENCE_TOTAL\\t'))
        self.assertLess(terminal.index('FINAL_SEQUENCE_TOTAL\\t'), terminal.index('DRAIN_MARKER\\t'))
        self.assertLess(terminal.index('DRAIN_MARKER\\t'), terminal.index('clear(@sequence)'))

    def test_invalid_process_or_probe_names_fail_before_script_attachment(self):
        selected = {query: {'name': f'_Zentry{index}v'} for index, query in enumerate(lifecycle.BOUNDARIES)}
        variants = [(Path('relative'), 1234, selected), (Path('/lib/jvm.so'), True, selected),
                    (Path('/lib/jvm.so'), 0, selected), (Path('/lib/jvm.so'), 1234, {}),
                    (Path('/lib/jvm.so'), 1234, {**selected, lifecycle.BOUNDARIES[0]: {'name': '*'}})]
        for library, pid, entries in variants:
            with self.subTest(library=library, pid=pid, entries=entries), self.assertRaises(ValueError):
                lifecycle.program(library, pid, entries)

    def test_failed_bound_inputs_retain_report_without_attaching(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            associations = out / 'associations.json'
            associations.write_bytes(b'unpinned')
            arguments = ['capture', str(out), '/unread/library', str(associations),
                         '/unread/entries', '/unread/image', '--', 'java', '-version']
            with patch('sys.argv', arguments), patch.object(lifecycle.observer, 'observe_appimage') as attach, \
                    self.assertRaises(ValueError):
                lifecycle.main()

            attach.assert_not_called()
            report = json.loads((out / 'boundary-lifecycle.json').read_text())
            self.assertFalse(report['completed'])
            self.assertFalse(report['actualCallingConventionProven'])
            self.assertFalse(report['rootCensusProven'])
            self.assertFalse(report['privateArgumentOrFieldReads'])

    def test_unpinned_observer_image_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            image = out / 'image'
            image.write_bytes(b'unpinned')
            arguments = ['capture', str(out), '/unread/library', '/unread/associations',
                         '/unread/entries', str(image), '--', 'java', '-version']
            with patch('sys.argv', arguments), patch.object(lifecycle, 'bound_entries', return_value={}), \
                    patch.object(lifecycle.observer, 'observe_appimage') as attach, self.assertRaises(ValueError):
                lifecycle.main()

            attach.assert_not_called()
            self.assertFalse(json.loads((out / 'boundary-lifecycle.json').read_text())['completed'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
