import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_vm_task_generation as generation


class NativeVmTaskGenerationTest(unittest.TestCase):
    def test_physical_constructor_aliases_share_one_probe_and_retain_every_source_reference(self):
        aliases = generation.HOOKS['construct'][0]
        rows = []
        for index, name in enumerate(aliases):
            entry = {'originalSymbol': {'name': name, 'nameHex': name.encode().hex(), 'value': 100, 'size': 3,
                                        'function': True, 'undefined': False, 'ordinarySectionDefinition': True},
                     'completed': True, 'fileOffset': 1, 'entryHex': b'abc'.hex(),
                     'entrySha256': hashlib.sha256(b'abc').hexdigest(), 'entryBytes': 3, 'decodedBytes': 3,
                     'byteCoverage': 'complete symbol span', 'truncated': False,
                     'decoderCoverage': 'complete input', 'undecodedHex': ''}
            rows.append({'input': 'lifecycle', 'role': 'construct', 'index': index,
                         'row': {'originalEntry': entry}})

        with patch.object(generation, 'HOOKS', {'construct': generation.HOOKS['construct']}):
            selected = generation.select_entries(io.BytesIO(b'xabcy'), 5, {'construct': rows})

        self.assertEqual(1, len(selected['construct']))
        self.assertEqual(sorted(aliases), selected['construct'][0]['aliases'])
        self.assertEqual(rows, selected['construct'][0]['sourceEntryReferences'])
        self.assertEqual(min(aliases), selected['construct'][0]['name'])

    def test_unproved_conflicting_or_changed_physical_entries_prevent_probe_selection(self):
        name = generation.HOOKS['free'][0][0]
        entry = {'originalSymbol': {'name': name, 'nameHex': name.encode().hex(), 'value': 100, 'size': 3,
                                    'function': True, 'undefined': False, 'ordinarySectionDefinition': True},
                 'completed': True, 'fileOffset': 1, 'entryHex': b'abc'.hex(),
                 'entrySha256': hashlib.sha256(b'abc').hexdigest(), 'entryBytes': 3, 'decodedBytes': 3,
                 'byteCoverage': 'complete symbol span', 'truncated': False,
                 'decoderCoverage': 'complete input', 'undecodedHex': ''}
        for mode in ['undefined', 'partial', 'name', 'bytes', 'bounds', 'suffix', 'conflict', 'missing']:
            with self.subTest(mode=mode):
                changed = copy.deepcopy(entry)
                if mode == 'undefined':
                    changed['originalSymbol']['undefined'] = True
                elif mode == 'partial':
                    changed['truncated'] = True
                elif mode == 'name':
                    changed['originalSymbol']['nameHex'] = b'other'.hex()
                elif mode == 'bytes':
                    changed['fileOffset'] = 0
                elif mode == 'bounds':
                    changed['fileOffset'] = 3
                elif mode == 'suffix':
                    changed['undecodedHex'] = '00'
                rows = [{'row': {'originalEntry': changed}}]
                if mode == 'conflict':
                    other = copy.deepcopy(entry)
                    other['originalSymbol']['value'] += 1
                    rows.append({'row': {'originalEntry': other}})
                elif mode == 'missing':
                    rows = []

                with patch.object(generation, 'HOOKS', {'free': generation.HOOKS['free']}), self.assertRaises(ValueError):
                    generation.select_entries(io.BytesIO(b'xabcy'), 5, {'free': rows})

    def test_different_roles_cannot_share_a_physical_probe_location(self):
        groups = {}
        for role in ['free', 'broker']:
            name = generation.HOOKS[role][0][0]
            entry = {'originalSymbol': {'name': name, 'nameHex': name.encode().hex(), 'value': 100, 'size': 3,
                                        'function': True, 'undefined': False, 'ordinarySectionDefinition': True},
                     'completed': True, 'fileOffset': 1, 'entryHex': b'abc'.hex(),
                     'entrySha256': hashlib.sha256(b'abc').hexdigest(), 'entryBytes': 3, 'decodedBytes': 3,
                     'byteCoverage': 'complete symbol span', 'truncated': False,
                     'decoderCoverage': 'complete input', 'undecodedHex': ''}
            groups[role] = [{'row': {'originalEntry': entry}}]

        with patch.object(generation, 'HOOKS', {role: generation.HOOKS[role] for role in groups}), \
                self.assertRaises(ValueError):
            generation.select_entries(io.BytesIO(b'xabcy'), 5, groups)

    def test_all_lifecycle_aliases_and_needed_parameter_proofs_are_required(self):
        for mode in ['valid', 'role', 'alias', 'definition', 'register', 'width', 'index', 'allocation', 'unmatched']:
            with self.subTest(mode=mode):
                roles = {}
                for role, spec in generation.lifecycle.ROLES.items():
                    definitions = [{'argumentPlanProven': True, 'sourceBoundaryProven': True,
                                    'pointerReturnClassProven': role == 'allocate',
                                    'parameterBindings': [{'parameterIndex': index, 'name': name,
                                        'targetType': kind, 'expectedDwarfRegister': register,
                                        'extractionBits': 32 if kind == 'int' else 64, 'readBindingProven': True}
                                        for index, name, kind, register in spec['reads']]}]
                    roles[role] = {'argumentPlanProven': True, 'requiredSymbols': list(spec['names']),
                                   'unmatchedPhysicalDefinitions': [],
                                   'entries': [{'argumentPlanProven': True,
                                                'originalEntry': {'originalSymbol': {'name': name}},
                                                'definitions': copy.deepcopy(definitions)} for name in spec['names']]}
                if mode == 'role':
                    roles.pop('destroy')
                elif mode == 'alias':
                    roles['construct']['entries'].pop()
                elif mode == 'definition':
                    roles['initialize']['entries'][0]['definitions'][0]['sourceBoundaryProven'] = False
                elif mode == 'register':
                    roles['initialize']['entries'][0]['definitions'][0]['parameterBindings'][1]['expectedDwarfRegister'] = 5
                elif mode == 'width':
                    roles['initialize']['entries'][0]['definitions'][0]['parameterBindings'][1]['extractionBits'] = 64
                elif mode == 'index':
                    roles['initialize']['entries'][0]['definitions'][0]['parameterBindings'][0]['parameterIndex'] = False
                elif mode == 'allocation':
                    roles['allocate']['entries'][0]['definitions'][0]['pointerReturnClassProven'] = False
                elif mode == 'unmatched':
                    roles['destroy']['unmatchedPhysicalDefinitions'].append({'status': 'unproved'})
                original = copy.deepcopy(roles)

                if mode == 'valid':
                    selected = generation.lifecycle_rows({'roles': roles})

                    self.assertEqual({'initialize', 'free', 'construct', 'destroy'}, set(selected))
                    self.assertNotIn('allocate', selected)
                else:
                    with self.assertRaises(ValueError):
                        generation.lifecycle_rows({'roles': roles})
                self.assertEqual(original, roles)

    def test_compiler_entries_require_every_canonical_definition_and_register(self):
        for mode in ['valid', 'entry', 'definition', 'register', 'absent']:
            with self.subTest(mode=mode):
                report = {'entries': {}, 'targetConcordance': {}}
                for role, query in [('broker', 'invoke_compiler_on_method'), ('post', 'post_compiled_method')]:
                    name = generation.abi.linkage(query)
                    report['targetConcordance'][query] = {'actualTargetCallingConventionProven': True, 'linkageName': name}
                    report['entries'][query] = [{'originalEntry': {'originalSymbol': {'name': name}},
                        'actualTargetCallingConventionProven': True, 'definitions': [{
                            'actualTargetCallingConventionProven': True, 'parameterBindings': [
                                {'expectedParameter': parameter, 'expectedDwarfRegister': register, 'entryRegisters': [register]}
                                for parameter, register, _ in generation.HOOKS[role][1]]}]}]
                row = report['entries']['post_compiled_method'][0]
                if mode == 'entry':
                    row['actualTargetCallingConventionProven'] = False
                elif mode == 'definition':
                    row['definitions'][0]['actualTargetCallingConventionProven'] = False
                elif mode == 'register':
                    row['definitions'][0]['parameterBindings'][1]['entryRegisters'] = [5]
                elif mode == 'absent':
                    report['entries']['post_compiled_method'] = []

                if mode == 'valid':
                    self.assertEqual({'broker', 'post'}, set(generation.compiler_rows(report)))
                else:
                    with self.assertRaises(ValueError):
                        generation.compiler_rows(report)

    def test_hash_and_size_guards_precede_json_and_library_access(self):
        for mode in ['hash', 'size']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                plan, abi = out / 'plan', out / 'abi'
                plan.write_bytes(b'not json')
                abi.write_bytes(b'not json')
                with patch.object(generation, 'MAX_INPUT_BYTES', 1 if mode == 'size' else 1024), \
                        patch.object(generation.json, 'loads') as parse, self.assertRaises(ValueError):
                    generation.inputs(plan, abi, out)

                parse.assert_not_called()

    def test_both_originals_are_retained_before_semantic_json_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            plan, abi = out / 'plan', out / 'abi'
            plan.write_bytes(b'invalid original plan')
            abi.write_bytes(b'invalid original abi')
            with patch.object(generation, 'PLAN_SHA256', hashlib.sha256(plan.read_bytes()).hexdigest()), \
                    patch.object(generation, 'ABI_SHA256', hashlib.sha256(abi.read_bytes()).hexdigest()), \
                    self.assertRaises(ValueError):
                generation.inputs(plan, abi, out)

            self.assertEqual(plan.read_bytes(), (out / 'captured-task-lifecycle.json').read_bytes())
            self.assertEqual(abi.read_bytes(), (out / 'captured-task-abi.json').read_bytes())

    def test_entry_program_uses_signed_compile_ids_and_never_reads_target_fields_or_returns(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in generation.HOOKS.items()}

        script = generation.program(Path('/opt/jdk/lib/server/libjvm.so'), 1234, selected)

        self.assertNotIn('uretprobe:', script)
        self.assertNotIn('retval', script)
        self.assertIn('(int64)((int32)arg1)', script)
        for line in script.splitlines():
            if line.startswith('uprobe:'):
                with self.subTest(line=line.split()[0]):
                    self.assertIn('/pid == 1234/', line)
                    self.assertIn('has_key(@sequence, tid)', line)
                    self.assertNotIn('buf(', line)
                    self.assertNotIn('uptr(', line)
                    if 'TASK_ENTRY\\tdestroy\\t' in line:
                        self.assertNotIn('arg0', line)
                        self.assertNotIn('arg1', line)
        self.assertIn('buf(uptr(arg0), arg1 < 128 ? arg1 : 128)', script)
        terminal = script.splitlines()[-1]
        self.assertLess(terminal.index('FINAL_SEQUENCE\\t'), terminal.index('FINAL_SEQUENCE_TOTAL\\t'))
        self.assertLess(terminal.index('FINAL_SEQUENCE_TOTAL\\t'), terminal.index('DRAIN_MARKER\\t'))
        self.assertLess(terminal.index('DRAIN_MARKER\\t'), terminal.index('clear(@sequence)'))

    def test_invalid_process_library_or_probe_names_prevent_program_generation(self):
        selected = {role: [{'name': names[0]}] for role, (names, _) in generation.HOOKS.items()}
        variants = [(Path('relative'), 1234, selected), (Path('/lib/jvm.so'), True, selected),
                    (Path('/lib/jvm.so'), 0, selected), (Path('/lib/jvm.so'), 1234, {}),
                    (Path('/lib/jvm.so'), 1234, {**selected, 'free': [{'name': '*'}]})]
        for library, pid, entries in variants:
            with self.subTest(library=library, pid=pid), self.assertRaises(ValueError):
                generation.program(library, pid, entries)

    def test_changed_library_hash_is_rejected_before_probes_are_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            library = out / 'library'
            library.write_bytes(b'unpinned')
            with patch.object(generation, 'inputs', return_value=({}, {})), \
                    patch.object(generation, 'lifecycle_rows', return_value={}), \
                    patch.object(generation, 'compiler_rows', return_value={}), \
                    patch.object(generation, 'select_entries') as select, self.assertRaises(ValueError):
                generation.bound_entries(library, out / 'plan', out / 'abi', out)

            select.assert_not_called()

    def test_raw_capture_completion_or_failure_cannot_authorize_generation_or_private_reads(self):
        for valid in [False, True]:
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                arguments = ['capture', str(out), '/library', '/plan', '/abi', '/image', '--', 'java', '-version']
                def digest(path):
                    return generation.IMAGE_SHA256 if path.name == 'image' else generation.LIBRARY_SHA256
                with patch('sys.argv', arguments), patch.object(generation, 'bound_entries',
                        return_value={}, side_effect=None if valid else ValueError('invalid proof')), \
                        patch.object(generation.observer, 'file_digest', side_effect=digest), \
                        patch.object(generation.observer, 'observe_appimage') as attach:
                    if valid:
                        generation.main()
                        self.assertTrue(attach.call_args.kwargs['capture_event_loss'])
                    else:
                        with self.assertRaises(ValueError):
                            generation.main()
                        attach.assert_not_called()

                report = json.loads((out / 'task-generation-entries.json').read_text())
                self.assertEqual(valid, report['completed'])
                for key in ['actualTargetCallingConventionProven', 'actualTaskGenerationProven', 'actualCompiledTaskLifetimeProven',
                            'privateFieldReadsAuthorized', 'actualControlFlowCoverageProven',
                            'rootCensusProven', 'codeSizeProven', 'acceptedSavingsCredit']:
                    self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
