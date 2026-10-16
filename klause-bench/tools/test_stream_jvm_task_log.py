import hashlib
import io
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import review_jvm_task_log as single
import stream_jvm_task_log as streaming


class StreamJvmTaskLogTest(unittest.TestCase):
    def test_complete_metadata_and_stamp_semantics_equal_the_tree_reader_for_chunk_boundaries(self):
        xml = ("<hotspot_log process='100'><task_queued compile_id='7' method='outside.C f ()V'/>"
               "<nmethod compile_id='7' method='outside.C f ()V' compiler='c2' size='19'/>"
               "<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
               "<task compile_id='7' method='outside.C f ()V' stamp='1.001'>"
               "<parse><phase name='ignored'/></parse><task_done success='1' stamp='1.005'/></task>"
               "<task compile_id='8' method='outside.C g ()V' compile_kind='osr' osr_bci='12' level='4' stamp='2.001'>"
               "<task_done success='0' stamp='2.003'/></task></compilation_log>"
               "<fragment>snowman \u2603</fragment></hotspot_log>").encode()
        expected = single.inspect_text(xml.decode())
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}
        for chunk in (1, 7, 65536):
            with self.subTest(chunk=chunk), patch.object(streaming, 'CHUNK_BYTES', chunk):
                logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

            self.assertEqual(expected, single.inspect_records(logs, auxiliary))

    def test_nested_task_and_log_owners_preserve_both_metadata_and_auxiliary_scopes(self):
        xml = ("<hotspot_log process='10'><compilation_log thread='11'>"
               "<task compile_id='1' method='a.C f ()V'><failure>outer</failure>"
               "<compilation_log thread='12'><failure>nested log</failure>"
               "<task compile_id='2' method='a.C g ()V'><failure>inner</failure>"
               "<task_done success='1'/></task><failure>after inner</failure></compilation_log>"
               "<task compile_id='3' method='a.C h ()V'><task_done success='0'/></task>"
               "<failure>after nested</failure><task_done success='1'/></task>"
               "</compilation_log></hotspot_log>").encode()
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

        self.assertEqual(single.inspect_text(xml.decode()), single.inspect_records(logs, auxiliary))
        self.assertEqual([0, 0, 1, 0, 0], [row['taskIndex'] for row in auxiliary])

    def test_invalid_ambiguous_and_orphaned_records_keep_the_tree_reader_errors(self):
        bodies = ["<task_done success='2'/><start_compile_thread thread='0' process='bad'/>",
                  "<compilation_log thread='bad'><task compile_id='-1' method='bad' level='x' osr_bci='-2'>"
                  "<task_done/><task_done success='0'/></task></compilation_log>",
                  "<compilation_log thread='3'><start_compile_thread thread='3' process='2'/>"
                  "<start_compile_thread thread='3' process='2'/><task compile_id='1' method='a.C f ()V' stamp='2.003'>"
                  "<phase><task_done success='1'/></phase><task_done success='1' stamp='2.001'/></task></compilation_log>"]
        for body in bodies:
            with self.subTest(body=body):
                xml = ("<hotspot_log process='2'>" + body + '</hotspot_log>').encode()
                identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

                logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

                self.assertEqual(single.inspect_text(xml.decode()), single.inspect_records(logs, auxiliary))

    def test_failure_and_fragment_text_excludes_descendant_text_and_child_tails(self):
        xml = ("<hotspot_log><fragment>before<child>inside</child>after</fragment>"
               "<failure/><make_not_entrant>first<child/>tail</make_not_entrant>"
               "<make_not_compilable>text</make_not_compilable><deoptimized>last</deoptimized></hotspot_log>").encode()
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

        self.assertEqual(single.inspect_text(xml.decode()), single.inspect_records(logs, auxiliary))
        self.assertEqual('before', logs['fragments'][0]['text'])
        self.assertEqual([None, 'first', 'text', 'last'], [row['text'] for row in auxiliary])

    def test_declaration_markers_are_rejected_even_when_split_across_reads_or_inside_comments(self):
        variants = [b'<!DOCTYPE hotspot_log [<!ENTITY x "bad">]><hotspot_log>&x;</hotspot_log>',
                    b'<hotspot_log><!-- <!ENTITY marker --> </hotspot_log>',
                    b'<!DOCTYPE hotspot_log SYSTEM "file:///absent"><hotspot_log/>']
        for xml in variants:
            for chunk in (1, 7, 65536):
                with self.subTest(xml=xml, chunk=chunk), patch.object(streaming, 'CHUNK_BYTES', chunk):
                    identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

                    with self.assertRaises(ValueError):
                        streaming.parse_stream(io.BytesIO(xml), identity)

    def test_malformed_truncated_multiple_or_unsupported_roots_decline_the_complete_log(self):
        for xml in (b'<hotspot_log><task', b'<hotspot_log><task></hotspot_log>', b'<other/>',
                    b'<hotspot_log/><hotspot_log/>', b''):
            with self.subTest(xml=xml):
                identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

                with self.assertRaises((ET.ParseError, ValueError)):
                    streaming.parse_stream(io.BytesIO(xml), identity)

    def test_invalid_or_incomplete_utf8_is_not_repaired_by_the_streaming_decoder(self):
        for xml in (b'<hotspot_log>\xff</hotspot_log>', b'<hotspot_log>\xe2\x98',
                    b'<?xml version="1.0" encoding="ISO-8859-1"?><hotspot_log>\xe9</hotspot_log>'):
            with self.subTest(xml=xml), patch.object(streaming, 'CHUNK_BYTES', 1):
                identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

                with self.assertRaises(UnicodeError):
                    streaming.parse_stream(io.BytesIO(xml), identity)

    def test_complete_stream_hash_must_equal_the_original_member(self):
        xml = b'<hotspot_log/>'

        with self.assertRaisesRegex(ValueError, 'complete original member identity'):
            streaming.parse_stream(io.BytesIO(xml), {'size': len(xml), 'sha256': 'a' * 64})

    def test_complete_stream_size_must_equal_the_original_member(self):
        xml = b'<hotspot_log/>'

        with self.assertRaisesRegex(ValueError, 'complete original member identity'):
            streaming.parse_stream(io.BytesIO(xml), {'size': len(xml) + 1, 'sha256': hashlib.sha256(xml).hexdigest()})

    def test_input_budget_accepts_the_complete_boundary_and_rejects_an_extra_byte(self):
        xml = b'<hotspot_log/>'
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}
        with patch.object(streaming, 'MAX_INPUT_BYTES', len(xml)):
            logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)
        self.assertEqual(single.inspect_text(xml.decode()), single.inspect_records(logs, auxiliary))

        with patch.object(streaming, 'MAX_INPUT_BYTES', len(xml) - 1):
            with self.assertRaisesRegex(ValueError, 'streaming input bounds'):
                streaming.parse_stream(io.BytesIO(xml), identity)

    def test_element_budget_includes_unretained_compiler_detail(self):
        xml = b'<hotspot_log><parse><ignored/></parse></hotspot_log>'
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        with patch.object(streaming, 'MAX_ELEMENTS', 2), self.assertRaisesRegex(ValueError, 'element or depth'):
            streaming.parse_stream(io.BytesIO(xml), identity)

    def test_depth_budget_declines_without_building_an_unbounded_xml_stack(self):
        xml = b'<hotspot_log><a><b><c/></b></a></hotspot_log>'
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        with patch.object(streaming, 'MAX_DEPTH', 3), self.assertRaisesRegex(ValueError, 'element or depth'):
            streaming.parse_stream(io.BytesIO(xml), identity)

    def test_retained_row_budget_includes_markers_completions_fragments_and_failures(self):
        for tag in ('compilation_log', 'task', 'task_queued', 'nmethod', 'start_compile_thread',
                    'task_done', 'fragment', 'failure'):
            with self.subTest(tag=tag), patch.object(streaming, 'MAX_METADATA_ROWS', 0):
                xml = ('<hotspot_log><' + tag + '/></hotspot_log>').encode()
                identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

                with self.assertRaisesRegex(ValueError, 'retained-row bounds'):
                    streaming.parse_stream(io.BytesIO(xml), identity)

    def test_a_complete_log_above_the_legacy_input_cap_preserves_metadata(self):
        body = ("<compilation_log thread='101'><start_compile_thread thread='101' process='100'/>"
                "<task compile_id='1' method='a.C f ()V' stamp='1.001'>"
                "<task_done success='1' stamp='1.002'/></task></compilation_log></hotspot_log>").encode()
        xml = b"<hotspot_log process='100'>" + b' ' * (16 * 1024 * 1024 + 1) + body
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

        expected = single.inspect_text("<hotspot_log process='100'>" + body.decode())
        self.assertEqual(expected, single.inspect_records(logs, auxiliary))
        self.assertTrue(single.inspect_records(logs, auxiliary)['allLoggedTaskStampPairsValid'])
        with self.assertRaisesRegex(ValueError, 'input bounds'):
            single.inspect_text(xml.decode())

    def test_trailing_document_whitespace_is_included_in_complete_byte_identity(self):
        xml = b'<hotspot_log/>\n \t\n'
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)

        self.assertEqual(single.inspect_text(xml.decode()), single.inspect_records(logs, auxiliary))
        with self.assertRaisesRegex(ValueError, 'complete original member identity'):
            streaming.parse_stream(io.BytesIO(xml), {'size': len(xml.rstrip()), 'sha256': identity['sha256']})

    def test_omitted_compiler_level_and_kind_stay_unknown_and_supply_no_cost_authority(self):
        xml = ("<hotspot_log process='2'><compilation_log thread='3'>"
               "<start_compile_thread thread='3' process='2'/><task compile_id='1' method='a.C f ()V' stamp='1.000'>"
               "<task_done success='0' stamp='1.000'/></task></compilation_log></hotspot_log>").encode()
        identity = {'size': len(xml), 'sha256': hashlib.sha256(xml).hexdigest()}

        logs, auxiliary = streaming.parse_stream(io.BytesIO(xml), identity)
        result = single.inspect_records(logs, auxiliary)

        self.assertIsNone(logs['tasks'][0]['level'])
        self.assertIsNone(logs['tasks'][0]['compileKind'])
        self.assertEqual(single.inspect_text(xml.decode()), result)
        for key in single.DENIED:
            self.assertFalse(result[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
