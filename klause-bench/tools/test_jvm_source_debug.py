import base64
import hashlib
import unittest
from unittest.mock import patch

import jvm_source_debug as debug


class JvmSourceDebugTest(unittest.TestCase):
    def test_modified_utf8_retains_nul_supplementary_and_unpaired_units(self):
        raw = b'A\xc0\x80\xc3\xa9\xed\xa0\xbd\xed\xb8\x80\xed\xa0\x80'

        result = debug.modified_utf8(raw)

        self.assertEqual('A\x00\u00e9\U0001f600\ud800', result)

    def test_invalid_modified_utf8_does_not_become_replacement_text(self):
        for raw in [b'\x00', b'\x80', b'\xc0', b'\xc1\xbf', b'\xc2A', b'\xe0\x80\x80',
                    b'\xe1\x80', b'\xe1\x80A', b'\xf0\x9f\x98\x80', b'\xff']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                debug.modified_utf8(raw)

    def test_file_forms_and_default_file_ids_are_retained(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n+ 2 Peer.kt\np/Peer.kt\n*L\n10:100\n20#2:200\n21:201\n*E\n'

        smap = debug.parse_smap(raw)

        self.assertEqual([('Root.kt', None), ('Peer.kt', 'p/Peer.kt')],
                         [(file['name'], file['path']) for file in smap['strata'][0]['files']])
        self.assertEqual([0, 2, 2], [rule['fileId'] for rule in smap['strata'][0]['lines']])

    def test_line_sections_can_precede_file_sections(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*L\n10#1:100\n*F\n1 Root.kt\n*E\n'

        matches = debug.map_line(debug.parse_smap(raw), 100)

        self.assertEqual(('Root.kt', 10), (matches[0]['file']['name'], matches[0]['inputLine']))

    def test_optional_token_whitespace_does_not_change_the_mapping(self):
        for gap in ['', ' ', '\t']:
            raw = ('SMAP\nGenerated.kt\nKotlin\n*S' + gap + 'Kotlin\n*F' + gap +
                   '\n0' + gap + 'Root.kt\n*L' + gap + '\n1' + gap + ':' + gap + '2\n*E' + gap + '\n').encode()

            with self.subTest(gap=gap):
                matches = debug.map_line(debug.parse_smap(raw), 2)

                self.assertEqual(('Root.kt', 1), (matches[0]['file']['name'], matches[0]['inputLine']))

    def test_repeat_and_output_width_map_every_output_line_in_each_range(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n20,3:100,2\n*E\n'
        smap = debug.parse_smap(raw)

        observed = [debug.map_line(smap, line)[0]['inputLine'] for line in range(100, 106)]

        self.assertEqual([20, 20, 21, 21, 22, 22], observed)
        self.assertEqual([], debug.map_line(smap, 99))
        self.assertEqual([], debug.map_line(smap, 106))

    def test_overlaps_retain_later_rules_and_mark_the_first_match(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n20,3:100,2\n40:105\n*E\n'

        matches = debug.map_line(debug.parse_smap(raw), 105)

        self.assertEqual([22, 40], [item['inputLine'] for item in matches])
        self.assertEqual([True, False], [item['firstMatchingRule'] for item in matches])

    def test_zero_width_and_large_repetition_counts_are_not_expanded(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n3,2147483647:7,0\n10,2147483647:20,2\n*E\n'
        smap = debug.parse_smap(raw)

        matches = debug.map_line(smap, 22)

        self.assertEqual([], debug.map_line(smap, 7))
        self.assertEqual([11], [item['inputLine'] for item in matches])
        self.assertEqual(0, smap['strata'][0]['lines'][0]['outputIncrement'])

    def test_each_stratum_keeps_its_own_files_and_default_identity(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n1 Body.kt\n*L\n20#1:100\n*S KotlinDebug\n*F\n1 Caller.kt\n*L\n7#1:100\n*E\n'

        matches = debug.map_line(debug.parse_smap(raw), 100)

        self.assertEqual([('Kotlin', 'Body.kt', 20, True), ('KotlinDebug', 'Caller.kt', 7, False)],
                         [(item['stratum'], item['file']['name'], item['inputLine'], item['defaultStratum'])
                          for item in matches])

    def test_java_can_be_the_default_without_an_explicit_java_stratum(self):
        raw = b'SMAP\nGenerated.java\nJava\n*S Other\n*F\n0 Body.txt\n*L\n1:10\n*E\n'

        smap = debug.parse_smap(raw)

        self.assertEqual('Java', smap['defaultStratum'])
        self.assertFalse(debug.map_line(smap, 10)[0]['defaultStratum'])

    def test_supported_line_terminators_preserve_mapping_meaning(self):
        text = 'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:2\n*E\n'

        for ending in ['\n', '\r', '\r\n']:
            with self.subTest(ending=ending):
                smap = debug.parse_smap(text.replace('\n', ending).encode())

                self.assertEqual(1, debug.map_line(smap, 2)[0]['inputLine'])

    def test_vendor_and_future_sections_are_retained_in_original_order(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*Z\nfuture\n*S Kotlin\n*V\nexample.vendor\nopaque\n*F\n0 Root.kt\n*L\n1:2\n*Q\nmore\n*E\n'

        smap = debug.parse_smap(raw)

        self.assertEqual(['*Z', '*S Kotlin', '*V', '*F', '*L', '*Q'],
                         [section['marker'] for section in smap['sections']])
        self.assertEqual(['example.vendor', 'opaque'], smap['sections'][2]['rawLines'])
        self.assertEqual(1, debug.map_line(smap, 2)[0]['inputLine'])

    def test_unresolved_embedded_maps_are_not_treated_as_final_source(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*O Kotlin\nSMAP\nMiddle.kt\nOther\n*S Other\n*F\n0 Root.kt\n*L\n1:1\n*E\n*C Kotlin\n*E\n'

        with self.assertRaisesRegex(ValueError, 'unresolved'):
            debug.parse_smap(raw)

    def test_incomplete_ambiguous_or_malformed_structure_is_unavailable(self):
        valid = 'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:2\n*E\n'
        variants = [valid.removesuffix('*E\n'), valid.removesuffix('\n'), valid + 'trailing\n',
                    valid.replace('1:2', '1#9:2'), valid.replace('0 Root.kt', '0 Root.kt\n0 Peer.kt'),
                    valid.replace('*L\n1:2', '*F\n1 Peer.kt\n*L\n1:2'),
                    valid.replace('*S Kotlin', '*F'), valid.replace('*S Kotlin', '*S Java'),
                    valid.replace('*L\n1:2', '*L\n0:2'), valid.replace('1:2', '1,0:2'),
                    valid.replace('1:2', '1:0'), valid.replace('1:2', '1:2,-1'),
                    valid.replace('1:2', '1:2147483648'), valid.replace('0 Root.kt', '+ 0 Root.kt')]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                debug.parse_smap(text.encode())

    def test_missing_payload_bytes_are_not_inferred_to_mean_absence(self):
        for metadata in [{}, {'attributes': [], 'sourceDebugExtensionIdentities': []},
                         {'attributes': ['SourceDebugExtension'], 'sourceDebugExtensionIdentities': [],
                          'sourceDebugExtensionContentsBase64': []}]:
            with self.subTest(metadata=metadata):
                result = debug.payload_maps(metadata)

                self.assertEqual('unavailable', result[0]['status'])
                self.assertFalse(result[0]['inputIdentityVerified'])

    def test_absent_and_present_empty_payloads_stay_distinct(self):
        absent = {'attributes': [], 'sourceDebugExtensionIdentities': [], 'sourceDebugExtensionContentsBase64': []}
        present = {'attributes': ['SourceDebugExtension'],
            'sourceDebugExtensionIdentities': [{'bytes': 0, 'sha256': hashlib.sha256(b'').hexdigest()}],
            'sourceDebugExtensionContentsBase64': ['']}

        missing, empty = debug.payload_maps(absent), debug.payload_maps(present)

        self.assertEqual([], missing)
        self.assertEqual('unavailable', empty[0]['status'])
        self.assertTrue(empty[0]['inputIdentityVerified'])

    def test_all_payloads_retain_separate_identity_and_parse_outcomes(self):
        raw_values = [b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:2\n*E\n', b'\xff']
        metadata = {'attributes': ['SourceDebugExtension'] * 2,
            'sourceDebugExtensionIdentities': [{'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                               for raw in raw_values],
            'sourceDebugExtensionContentsBase64': [base64.b64encode(raw).decode() for raw in raw_values]}

        result = debug.payload_maps(metadata)

        self.assertEqual(['parsed-resolved-smap', 'unavailable'], [item['status'] for item in result])
        self.assertEqual([0, 1], [item['attributeIndex'] for item in result])
        self.assertTrue(all(item['inputIdentityVerified'] for item in result))

    def test_bad_base64_or_byte_identity_prevents_payload_admission(self):
        for content, size, checksum in [('!', 1, hashlib.sha256(b'x').hexdigest()),
                                        ('eA==', 2, hashlib.sha256(b'x').hexdigest()), ('eA==', 1, 'a' * 64)]:
            metadata = {'attributes': ['SourceDebugExtension'],
                'sourceDebugExtensionIdentities': [{'bytes': size, 'sha256': checksum}],
                'sourceDebugExtensionContentsBase64': [content]}

            result = debug.payload_maps(metadata)

            self.assertFalse(result[0]['inputIdentityVerified'])
            self.assertEqual('unavailable', result[0]['status'])

    def test_payload_section_and_line_bounds_decline_the_complete_map(self):
        raw = b'SMAP\nGenerated.kt\nKotlin\n*S Kotlin\n*F\n0 Root.kt\n*L\n1:2\n*E\n'
        for bound in ['MAX_PAYLOAD_BYTES', 'MAX_SECTIONS', 'MAX_ITEMS']:
            with self.subTest(bound=bound), patch.object(debug, bound, 0), self.assertRaises(ValueError):
                debug.parse_smap(raw)


if __name__ == '__main__':
    unittest.main(verbosity=2)
