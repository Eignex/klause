import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_vm_live_arguments as live


class NativeVmLiveArgumentsTest(unittest.TestCase):
    def test_fields_require_declared_owner_bounds_and_supported_widths(self):
        fields = [{'typeName': owner, 'fieldName': name, 'typeString': 'int', 'isStatic': 0,
                   'offset': index * 8, 'targetFieldWidth': 4}
                  for owner in ('CompileTask', 'nmethod')
                  for index, name in enumerate(live.metadata.REQUIRED_FIELDS[owner])]
        source = {'librarySha256': live.LIBRARY_SHA256, 'pointerWidth': 8, 'requiredFields': fields,
                  'typeTable': {'rows': [{'typeName': owner, 'size': 64} for owner in ('CompileTask', 'nmethod')]}}

        self.assertEqual(fields, live.layout_fields(source))
        for change in [{'isStatic': 1}, {'targetFieldWidth': 3}, {'targetFieldWidth': True},
                       {'offset': -1}, {'offset': 63}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                live.layout_fields({**source, 'requiredFields': [{**fields[0], **change}, *fields[1:]]})

    def test_candidate_field_reads_use_only_supplied_exported_offsets_and_widths(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 24, 'targetFieldWidth': 8},
                  {'typeName': 'nmethod', 'fieldName': '_comp_level', 'offset': 40, 'targetFieldWidth': 1}]

        script = live.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, fields)

        self.assertIn('buf(uptr($task + 24), 8)', script)
        self.assertIn('buf(uptr($nmethod + 40), 1)', script)
        self.assertIn('if ($task != 0)', script)
        self.assertNotIn('_nm_total_size', script)
        self.assertIn('VALIDATION_FAULT_EXPECTED', script)

    def test_return_probes_do_not_dereference_destroyed_or_finished_task_arguments(self):
        fields = [{'typeName': 'CompileTask', 'fieldName': '_method', 'offset': 8, 'targetFieldWidth': 8}]

        script = live.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, fields)

        returns = [line for line in script.splitlines() if line.startswith('uretprobe:')]
        for line in returns:
            with self.subTest(probe=line.split()[0]):
                if 'queue-get-return' in line:
                    self.assertIn('buf(uptr($task + 8), 8)', line)
                else:
                    self.assertNotIn('buf(', line)

    def test_probe_scope_and_sequences_do_not_use_missing_tracking_map_values(self):
        script = live.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, [])

        self.assertIn('/pid == 1234/', script)
        self.assertIn('if (has_key(@sequence, tid)) { $sequence = @sequence[tid]; }', script)
        self.assertNotIn('find(', script)
        self.assertNotIn('@tracked[', script)
        self.assertNotIn('@sequence[tid] +', script)
        self.assertIn('DRAIN_MARKER', script)
        self.assertIn('(uint32)retval', script)

    def test_required_hooks_refuse_missing_undefined_or_ambiguous_entry_records(self):
        rows = [{'originalSymbol': {'name': name, 'value': index, 'size': 1, 'function': True,
                                   'undefined': False, 'ordinarySectionDefinition': True},
                 'completed': True, 'entryHex': '90', 'entrySha256': 'retained', 'fileOffset': index}
                for index, (label, name, candidates) in enumerate(live.HOOKS)]
        variants = [rows[1:], [{**rows[0], 'completed': False}, *rows[1:]],
                    [{**rows[0], 'originalSymbol': {**rows[0]['originalSymbol'], 'undefined': True}}, *rows[1:]],
                    [*rows, {**rows[0], 'fileOffset': 1000}]]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'entries.json'
            for records in variants:
                with self.subTest(records=records):
                    raw = json.dumps({'completed': True, 'librarySha256': live.LIBRARY_SHA256,
                                      'librarySha256After': live.LIBRARY_SHA256, 'entries': {'all': records}}).encode()
                    path.write_bytes(raw)
                    with patch.object(live, 'ENTRIES_SHA256', hashlib.sha256(raw).hexdigest()), self.assertRaises(ValueError):
                        live.bound_entries(path)

    def test_unpinned_entries_retain_failure_before_loading_layouts_or_attaching(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            entries = out / 'entries.json'
            entries.write_bytes(b'unpinned')
            arguments = ['reader', str(out), '/unread/library', str(entries), '/unread/image', '--', 'java', '-version']
            with patch('sys.argv', arguments), patch.object(live.metadata, 'inspect') as inspect, \
                    patch.object(live.observer, 'observe_appimage') as attach, self.assertRaises(ValueError):
                live.main()
            result = json.loads((out / 'live-arguments.json').read_text())
            inspect.assert_not_called()
            attach.assert_not_called()
            self.assertFalse(result['completed'])
            self.assertFalse(result['livePointerProven'])
            self.assertEqual('ValueError', result['error']['type'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
