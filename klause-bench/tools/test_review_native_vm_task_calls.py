import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_native_vm_task_calls as review


class ReviewNativeVmTaskCallsTest(unittest.TestCase):
    def test_pinned_cpp_and_header_declarations_bind_the_same_caller_signature(self):
        for path, (line, _) in review.DECLARATIONS.items():
            with self.subTest(path=path):
                node = {'attributes': {'DW_AT_decl_file': {'form': 'DW_FORM_data1', 'value': 1},
                                       'DW_AT_decl_line': {'form': 'DW_FORM_data2', 'value': line}},
                        'references': {}, 'referenceErrors': {},
                        'context': {'fileReferences': {'DW_AT_decl_file': {
                            'status': 'header-file-entry', 'file': {'name': {'bytesHex': path.encode().hex()}},
                            'directory': {'status': 'compilation-directory',
                                          'attribute': {'value': {'bytesHex': b'/work'.hex()}}}}}}}

                actual = review.declaration('caller', {'caller': node})

                self.assertEqual(path, actual[0]['sourcePath'])
                self.assertEqual(line, actual[0]['line'])

    def test_ambiguous_paths_foreign_declarations_and_wrong_lines_cannot_bind_the_caller(self):
        for mutation in ['ambiguous', 'foreign', 'wrong-line', 'missing-directory']:
            with self.subTest(mutation=mutation):
                file = {'status': 'header-file-entry', 'file': {'name': {
                    'bytesHex': b'src/hotspot/share/compiler/compileBroker.cpp'.hex()}},
                        'directory': {'status': 'compilation-directory',
                                      'attribute': {'value': {'bytesHex': b'/work'.hex()}}}}
                node = {'attributes': {'DW_AT_decl_file': {'form': 'DW_FORM_data1', 'value': 1},
                                       'DW_AT_decl_line': {'form': 'DW_FORM_data2', 'value': 1896}},
                        'references': {}, 'referenceErrors': {},
                        'context': {'fileReferences': {'DW_AT_decl_file': file}}}
                if mutation == 'ambiguous':
                    file['status'] = 'ambiguous-file-index'
                elif mutation == 'foreign':
                    file['file']['name']['bytesHex'] = b'src/foreign/compileBroker.cpp'.hex()
                elif mutation == 'wrong-line':
                    node['attributes']['DW_AT_decl_line']['value'] = 1897
                else:
                    file['directory'] = {'status': 'unresolved'}

                with self.assertRaises(ValueError):
                    review.declaration('caller', {'caller': node})

    def test_source_declaration_origin_cycles_remain_unresolved(self):
        graph = {'caller': {'attributes': {}, 'references': {'DW_AT_specification': 'caller'},
                            'referenceErrors': {}}}

        with self.assertRaises(ValueError):
            review.declaration('caller', graph)

    def test_direct_near_call_binds_both_its_signed_target_and_caller_continuation(self):
        for pc, target in [(100, 200), (200, 100)]:
            with self.subTest(pc=pc, target=target):
                raw = b'\xe8' + (target - pc - 5).to_bytes(4, 'little', signed=True)
                row = {'address': pc, 'bytesHex': raw.hex(), 'mnemonic': 'call', 'operands': hex(target)}

                actual = review.near_call(row, target, {pc, pc + 5})

                self.assertEqual(pc + 5, actual['continuation'])
                self.assertTrue(actual['normalCallContinuationProven'])

    def test_tail_indirect_prefixed_malformed_and_unretained_continuations_are_unproved(self):
        variants = [('e95f000000', 200, {100, 105}), ('ffd0', 200, {100, 102}),
                    ('f2e85e000000', 200, {100, 106}), ('e85f0000', 200, {100, 104}),
                    ('e85f000000', 201, {100, 105}), ('e85f000000', 200, {100}),
                    ('e85f000000', True, {100, 105})]
        for encoded, target, boundaries in variants:
            with self.subTest(encoded=encoded, target=target, boundaries=boundaries):
                with self.assertRaises(ValueError):
                    review.near_call({'address': 100, 'bytesHex': encoded}, target, boundaries)

    def test_source_function_selection_preserves_aliases_and_rejects_conflicting_definitions(self):
        rows = []
        for name in review.DESTROY:
            rows.append({'originalSymbol': {'name': name, 'function': True, 'undefined': False,
                                             'ordinarySectionDefinition': True}, 'marker': name})
        identity = (400, 1, 400, 'a' * 64)
        with patch.object(review, 'physical_identity', return_value=identity):
            actual = review.planned({'wrapper': rows}, review.DESTROY)
        self.assertEqual(rows, actual)
        with patch.object(review, 'physical_identity', side_effect=[identity, (401, 1, 401, 'b' * 64)]):
            with self.assertRaises(ValueError):
                review.planned({'wrapper': [rows[0], copy.deepcopy(rows[0]), rows[1]]}, review.DESTROY)

    def test_only_source_bound_normal_calls_can_prove_broker_continuations(self):
        for variant in ['normal', 'tail', 'bad-target', 'foreign-source', 'abi-conflict', 'missing-destroy']:
            with self.subTest(variant=variant):
                queries = {query: [] for query in review.QUERIES}
                definitions = [('compiler_thread_loop', review.CALLER, 100, 'e85f000000c3'),
                               ('invoke_compiler_on_method', review.BROKER, 200, 'c3'),
                               ('post_compiled_method', review.POST, 300, 'c3'),
                               ('CompileTaskWrapper', review.DESTROY[0], 400, 'c3'),
                               ('CompileTaskWrapper', review.DESTROY[1], 400, 'c3'),
                               ('CompileTask4free', review.FREE, 500, 'c3')]
                for query, name, pc, encoded in definitions:
                    raw = bytes.fromhex(encoded)
                    instructions = [{'address': pc, 'bytesHex': encoded, 'mnemonic': 'ret', 'operands': ''}]
                    if query == 'compiler_thread_loop':
                        instructions = [{'address': 100, 'bytesHex': 'e85f000000', 'mnemonic': 'call',
                                         'operands': '0xc8'},
                                        {'address': 105, 'bytesHex': 'c3', 'mnemonic': 'ret', 'operands': ''}]
                    queries[query].append({'originalSymbol': {'name': name, 'value': pc, 'size': len(raw),
                                                              'function': True, 'undefined': False,
                                                              'ordinarySectionDefinition': True},
                                           'completed': True, 'byteCoverage': 'complete symbol span',
                                           'truncated': False, 'decoderCoverage': 'complete input',
                                           'entryHex': encoded, 'entryBytes': len(raw), 'decodedBytes': len(raw),
                                           'entrySha256': hashlib.sha256(raw).hexdigest(), 'fileOffset': pc,
                                           'undecodedHex': '', 'instructions': instructions})
                transfers = {'queries': review.QUERIES, 'entries': {query: [] for query in review.QUERIES}}
                for query, entries in queries.items():
                    for index, entry in enumerate(entries):
                        instructions = []
                        if query == 'compiler_thread_loop':
                            instructions = [{'instructionIndex': 0, 'instruction': entry['instructions'][0],
                                             'category': 'call', 'target': 200}]
                        transfers['entries'][query].append({'entryRecordIndex': index,
                            'originalSymbol': copy.deepcopy(entry['originalSymbol']),
                            'originalEntryCopiedVerbatim': copy.deepcopy(entry), 'reviewCompleted': True,
                            'wholeSymbolCaptured': True, 'allCapturedBytesDecoded': True,
                            'transferReview': {'recognizedTransfers': instructions}})
                node = {'tag': 'DW_TAG_subprogram', 'unitOffset': 0, 'parent': 'unit',
                        'parentKnownFromSelectedTraversal': True, 'references': {}, 'referenceErrors': {},
                        'attributes': {'DW_AT_low_pc': {'form': 'DW_FORM_addr', 'value': 100},
                                       'DW_AT_high_pc': {'form': 'DW_FORM_data4', 'value': 6},
                                       'DW_AT_linkage_name': {'value': {'bytesHex': review.CALLER.encode().hex()}},
                                       'DW_AT_decl_file': {'form': 'DW_FORM_data1', 'value': 1},
                                       'DW_AT_decl_line': {'form': 'DW_FORM_data2', 'value': 1896}},
                        'context': {'unitTopDie': 'unit', 'fileReferences': {'DW_AT_decl_file': {
                            'status': 'header-file-entry', 'file': {'name': {
                                'bytesHex': b'/work/src/hotspot/share/compiler/compileBroker.cpp'.hex()}},
                            'directory': {}}}}}
                source = {'contextUnits': {'unit': {'topDie': 'unit', 'unitOffset': 0, 'topAttributes': {
                    'DW_AT_name': {'value': {'bytesHex': b'compileBroker.cpp'.hex()}}}}},
                          'dwarf': {'graph': {'caller': node}, 'selectedRecords': [{
                              'die': 'caller', 'nameCandidates': [{'nameHex': b'compiler_thread_loop'.hex()}]}]}}
                original_abi = {'entries': {query: [{'originalEntry': copy.deepcopy(queries[query][0]),
                    'status': 'pinned-physical-entry-abi-proven', 'actualTargetCallingConventionProven': True}]
                    for query in ['invoke_compiler_on_method', 'post_compiled_method']}}
                if variant == 'tail':
                    transfers['entries']['compiler_thread_loop'][0]['transferReview'][
                        'recognizedTransfers'][0]['category'] = 'jump'
                elif variant == 'bad-target':
                    queries['compiler_thread_loop'][0]['instructions'][0]['bytesHex'] = 'e85e000000'
                    transfers['entries']['compiler_thread_loop'][0]['originalEntryCopiedVerbatim'] = copy.deepcopy(
                        queries['compiler_thread_loop'][0])
                elif variant == 'foreign-source':
                    node['attributes']['DW_AT_decl_line']['value'] = 1897
                elif variant == 'abi-conflict':
                    original_abi['entries']['invoke_compiler_on_method'][0]['actualTargetCallingConventionProven'] = False
                elif variant == 'missing-destroy':
                    queries['CompileTaskWrapper'].pop()
                    transfers['entries']['CompileTaskWrapper'].pop()
                result = {}

                if variant in ['abi-conflict', 'missing-destroy']:
                    with self.assertRaises(ValueError):
                        review.review(source, original_abi, {'queries': review.QUERIES, 'entries': queries},
                                      transfers, result)
                else:
                    review.review(source, original_abi, {'queries': review.QUERIES, 'entries': queries},
                                  transfers, result)
                    self.assertEqual(variant == 'normal', result['brokerNormalCallContinuationsProven'])

    def test_input_identity_and_size_are_checked_before_json_or_native_interpretation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_text('{}')
            for digest in ['a' * 64, review.INPUTS['context'][0]]:
                with self.subTest(digest=digest), patch.object(review.json, 'loads') as parse:
                    with self.assertRaises(ValueError):
                        review.inspect([path] * 4, [digest, *[row[0] for row in list(review.INPUTS.values())[1:]]],
                                       Path(directory), {})
                    parse.assert_not_called()
            with patch.object(review, 'MAX_INPUT_BYTES', 1), patch.object(review.json, 'loads') as parse:
                with self.assertRaises(ValueError):
                    review.inspect([path] * 4, [row[0] for row in review.INPUTS.values()], Path(directory), {})
                parse.assert_not_called()

    def test_aggregate_raw_input_bounds_apply_before_any_json_is_parsed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            path.write_text('{}')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            inputs = {'first': (digest, 'source'), 'second': (digest, 'source')}
            with patch.object(review, 'INPUTS', inputs), patch.object(review, 'MAX_TOTAL_BYTES', 3), \
                    patch.object(review.json, 'loads') as parse:
                with self.assertRaises(ValueError):
                    review.inspect([path, path], [digest, digest], Path(directory), {})
                parse.assert_not_called()

    def test_physical_identity_rejects_an_undecoded_suffix_after_span_binding(self):
        with patch.object(review.abi, 'entry_span', return_value={'virtualAddress': 100, 'bytes': 1,
                                                                'fileOffset': 20, 'entrySha256': 'a' * 64}):
            with self.assertRaises(ValueError):
                review.physical_identity({'undecodedHex': '90'})

    def test_failed_review_clears_all_partial_caller_and_continuation_proof_flags(self):
        report = {'completed': True, 'brokerNormalCallContinuationsProven': True,
                  'callerSourceDeclarationConcordant': True,
                  'callerDefinitions': [{'callerSourceDeclarationConcordant': True}],
                  'callerTransfers': [{'transfers': [{'normalCallContinuationProven': True}]}]}

        review.decline(report, ValueError('incomplete'))

        self.assertFalse(report['brokerNormalCallContinuationsProven'])
        self.assertFalse(report['callerDefinitions'][0]['callerSourceDeclarationConcordant'])
        self.assertFalse(report['callerTransfers'][0]['transfers'][0]['normalCallContinuationProven'])

    def test_successful_call_review_does_not_authorize_private_lifetime_or_root_claims(self):
        def complete(paths, expected, output, report):
            report.update(completed=True, brokerNormalCallContinuationsProven=True)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            arguments = ['tool', *sum(([name, digest] for name, (digest, _) in review.INPUTS.items()), []), str(output)]
            with patch.object(sys, 'argv', arguments), patch.object(review, 'inspect', side_effect=complete):
                review.main()
            actual = json.loads(output.read_text())

        for key in ['privateFieldReadsAuthorized', 'actualCompiledTaskLifetimeProven',
                    'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven', 'acceptedSavingsCredit']:
            self.assertFalse(actual[key])

    def test_main_retains_failure_diagnostics_and_declines_partial_proofs(self):
        def fail(paths, expected, output, report):
            report['brokerNormalCallContinuationsProven'] = True
            raise ValueError('unresolved')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            arguments = ['tool', *sum(([name, digest] for name, (digest, _) in review.INPUTS.items()), []), str(output)]
            with patch.object(sys, 'argv', arguments), patch.object(review, 'inspect', side_effect=fail):
                with self.assertRaises(ValueError):
                    review.main()
            actual = json.loads(output.read_text())

        self.assertFalse(actual['brokerNormalCallContinuationsProven'])
        self.assertEqual('unresolved', actual['error']['message'])


if __name__ == '__main__':
    unittest.main()
