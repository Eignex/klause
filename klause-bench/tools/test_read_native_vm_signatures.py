import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import read_native_vm_signatures as signatures
from native_vm_demangler import DemanglingError


class NativeVmSignaturesTest(unittest.TestCase):
    def test_signature_matching_preserves_duplicate_and_rejected_definition_records(self):
        rows = [{'name': '_Z1fv', 'nameHex': b'_Z1fv'.hex(), 'function': function,
                 'undefined': undefined, 'ordinarySectionDefinition': ordinary}
                for function, undefined, ordinary in [(True, False, True), (True, False, True),
                                                       (False, False, True), (True, True, False)]]
        source = {'completed': True, 'librarySha256': 'pinned', 'librarySha256After': 'pinned',
                  'queries': list(signatures.SIGNATURES),
                  'candidates': {query: rows if query == 'total_size' else [] for query in signatures.SIGNATURES}}
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            captured = out / 'input.json'
            raw = json.dumps(source).encode()
            captured.write_bytes(raw)
            report = {}
            with patch.object(signatures, 'digest', return_value='pinned'), \
                    patch.object(signatures, 'NativeDemangler') as constructor:
                provider = constructor.return_value
                provider.provenance = {'library': 'retained provider'}
                provider.read.return_value = {'demangled': signatures.SIGNATURES['total_size'], 'status': 0}

                signatures.inspect(out / 'library', 'pinned', captured, hashlib.sha256(raw).hexdigest(), out, report)

            self.assertTrue(report['completed'])
            self.assertEqual(4, len(report['candidates']['total_size']))
            self.assertEqual(2, len(report['matchedSourceSignatures']['total_size']))
            self.assertEqual(rows, [item['originalSymbolRecord'] for item in report['candidates']['total_size']])
            self.assertEqual(raw, (out / 'captured-vm-symbols.json').read_bytes())
            provider.verify_unchanged.assert_called_once()

    def test_unmatched_demangled_signatures_are_retained_without_a_definition_claim(self):
        source = {'completed': True, 'librarySha256': 'pinned', 'librarySha256After': 'pinned',
                  'queries': list(signatures.SIGNATURES), 'candidates': {query: [] for query in signatures.SIGNATURES}}
        source['candidates']['total_size'] = [{'name': '_Z1fv', 'nameHex': b'_Z1fv'.hex(), 'function': True,
                                             'undefined': False, 'ordinarySectionDefinition': True}]
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            captured = out / 'input.json'
            raw = json.dumps(source).encode()
            captured.write_bytes(raw)
            report = {}
            with patch.object(signatures, 'digest', return_value='pinned'), \
                    patch.object(signatures, 'NativeDemangler') as constructor:
                constructor.return_value.provenance = {}
                constructor.return_value.read.return_value = {'demangled': 'f()', 'status': 0}

                signatures.inspect(out / 'library', 'pinned', captured, hashlib.sha256(raw).hexdigest(), out, report)

            self.assertTrue(report['completed'])
            self.assertEqual([], report['matchedSourceSignatures']['total_size'])
            self.assertEqual('f()', report['candidates']['total_size'][0]['demanglerResult']['demangled'])
            self.assertFalse(report['candidates']['total_size'][0]['exactSourceSignatureMatched'])

    def test_incomplete_or_different_captures_are_rejected_before_loading_the_provider(self):
        for completed, before, after, expected in [(False, 'pinned', 'pinned', None),
                                                   (True, 'other', 'pinned', None),
                                                   (True, 'pinned', 'other', None),
                                                   (True, 'pinned', 'pinned', 'wrong')]:
            with self.subTest(completed=completed, before=before, after=after, expected=expected), \
                    tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                captured = out / 'input.json'
                raw = json.dumps({'completed': completed, 'librarySha256': before,
                                  'librarySha256After': after}).encode()
                captured.write_bytes(raw)
                with patch.object(signatures, 'digest', return_value='pinned'), \
                        patch.object(signatures, 'NativeDemangler') as constructor, self.assertRaises(ValueError):
                    signatures.inspect(out / 'library', 'pinned', captured,
                                       expected or hashlib.sha256(raw).hexdigest(), out, {})

                constructor.assert_not_called()

    def test_failed_demangling_retains_the_original_record_and_raw_api_failure(self):
        row = {'name': '_Z1fv', 'nameHex': b'_Z1fv'.hex()}
        source = {'completed': True, 'librarySha256': 'pinned', 'librarySha256After': 'pinned',
                  'queries': list(signatures.SIGNATURES), 'candidates': {query: [] for query in signatures.SIGNATURES}}
        source['candidates']['total_size'] = [row]
        context = {'status': 0, 'allocatedBytes': 0, 'returnedPointer': 0x1000,
                   'error': {'type': 'ValueError', 'message': 'invalid capacity'}}
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            captured = out / 'input.json'
            raw = json.dumps(source).encode()
            captured.write_bytes(raw)
            report = {}
            with patch.object(signatures, 'digest', return_value='pinned'), \
                    patch.object(signatures, 'NativeDemangler') as constructor:
                constructor.return_value.provenance = {}
                constructor.return_value.read.side_effect = DemanglingError(context)

                with self.assertRaises(DemanglingError):
                    signatures.inspect(out / 'library', 'pinned', captured, hashlib.sha256(raw).hexdigest(), out, report)

            item = report['candidates']['total_size'][0]
            self.assertEqual(row, item['originalSymbolRecord'])
            self.assertEqual(context, item['demanglerResult'])
            self.assertFalse(item['exactSourceSignatureMatched'])
            self.assertNotIn('completed', report)


if __name__ == '__main__':
    unittest.main(verbosity=2)
