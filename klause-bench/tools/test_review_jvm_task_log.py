import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_jvm_task_log as review


class ReviewJvmTaskLogTest(unittest.TestCase):
    def test_complete_task_keeps_raw_stamps_and_a_separate_nominal_difference(self):
        text = ("<hotspot_log process='100'><compilation_log thread='101'>"
                "<start_compile_thread thread='101' process='100'/>"
                "<task compile_id='7' method='p.C f ()V' stamp='1.001'>"
                "<task_done success='1' stamp='1.005' nmsize='99'/></task></compilation_log></hotspot_log>")

        result = review.inspect_text(text)

        self.assertEqual(review.identities.parse_log(text), result['logRecords'])
        row = result['taskLogSpans'][0]
        self.assertTrue(row['loggedStampPairValid'])
        self.assertEqual('1.001', row['startStampCopiedVerbatim'])
        self.assertEqual(['1.005'], row['doneStampsCopiedVerbatim'])
        self.assertEqual(4, row['nominalStampDeltaMillis'])
        for key in review.DENIED:
            self.assertFalse(result[key])

    def test_equal_printed_stamps_do_not_prove_zero_compiler_work(self):
        result = review.inspect_text(
            "<hotspot_log process='100'><compilation_log thread='101'>"
            "<start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='p.C f ()V' stamp='1.001'>"
            "<task_done success='1' stamp='1.001'/></task></compilation_log></hotspot_log>")

        self.assertEqual(0, result['taskLogSpans'][0]['nominalStampDeltaMillis'])
        self.assertFalse(result['taskLogSpans'][0]['zeroCompilerElapsedTimeProven'])
        self.assertFalse(result['compilerElapsedTimeProven'])

    def test_missing_duplicate_malformed_and_regressing_completion_stamps_stay_unresolved(self):
        completions = ['', "<task_done success='1' stamp='1.005'/>" * 2,
                       "<task_done success='1'/>", "<task_done success='1' stamp='1e3'/>",
                       "<task_done success='1' stamp='1.00'/>", "<task_done success='1' stamp='0.999'/>",
                       "<task_done success='9' stamp='1.005'/>"]
        for completion in completions:
            with self.subTest(completion=completion):
                result = review.inspect_text(
                    "<hotspot_log process='100'><compilation_log thread='101'>"
                    "<start_compile_thread thread='101' process='100'/>"
                    "<task compile_id='7' method='p.C f ()V' stamp='1.001'>" + completion +
                    '</task></compilation_log></hotspot_log>')

                row = result['taskLogSpans'][0]
                self.assertFalse(row['loggedStampPairValid'])
                self.assertIsNone(row['nominalStampDeltaMillis'])
                self.assertTrue(row['errors'])
                self.assertEqual(1, len(result['logRecords']['tasks']))

    def test_missing_and_invalid_start_stamps_remain_raw_without_a_difference(self):
        for stamp in ['', " stamp='-1.000'", " stamp='1.0010'", " stamp='01.001'"]:
            with self.subTest(stamp=stamp):
                result = review.inspect_text(
                    "<hotspot_log process='100'><compilation_log thread='101'>"
                    "<start_compile_thread thread='101' process='100'/>"
                    "<task compile_id='7' method='p.C f ()V'" + stamp + ">"
                    "<task_done success='1' stamp='1.005'/></task></compilation_log></hotspot_log>")

                self.assertFalse(result['taskLogSpans'][0]['loggedStampPairValid'])
                self.assertIsNone(result['taskLogSpans'][0]['nominalStampDeltaMillis'])

    def test_failed_stale_and_rejected_tasks_are_retained_without_invocation_authority(self):
        for reason in ['stale task', 'compilation is disabled', 'breakpoints are present', 'other failure']:
            with self.subTest(reason=reason):
                result = review.inspect_text(
                    "<hotspot_log process='100'><compilation_log thread='101'>"
                    "<start_compile_thread thread='101' process='100'/>"
                    "<task compile_id='7' method='p.C f ()V' stamp='1.001'><failure reason='" + reason + "'/>"
                    "<task_done success='0' nmsize='0' stamp='1.005'/></task></compilation_log></hotspot_log>")

                self.assertEqual(reason, result['failureAndPublicationStateRecords'][0]['rawAttributes']['reason'])
                self.assertEqual(0, result['failureAndPublicationStateRecords'][0]['taskIndex'])
                self.assertTrue(result['taskLogSpans'][0]['loggedStampPairValid'])
                self.assertFalse(result['taskLogSpans'][0]['actualCompilerInvocationProven'])
                self.assertFalse(result['codeSizeProven'])

    def test_omitted_level_and_compile_kind_remain_unknown(self):
        result = review.inspect_text(
            "<hotspot_log process='100'><compilation_log thread='101'>"
            "<start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='p.C f ()V' stamp='1.001'>"
            "<task_done success='1' stamp='1.005'/></task></compilation_log></hotspot_log>")

        task = result['logRecords']['tasks'][0]
        self.assertIsNone(task['level'])
        self.assertIsNone(task['compileKind'])
        self.assertIsNone(task['osrBci'])

    def test_queues_osr_publications_and_replacement_records_are_not_filtered_by_family(self):
        result = review.inspect_text(
            "<hotspot_log process='100'><task_queued compile_id='8' method='outside.C queued ()V'/>"
            "<nmethod compile_id='7' method='outside.C f ()V' size='400' compiler='c1'/>"
            "<make_not_entrant compile_id='7' stamp='1.010'/><compilation_log thread='101'>"
            "<start_compile_thread thread='101' process='100'/>"
            "<task compile_id='7' method='outside.C f ()V' compile_kind='osr' osr_bci='14' level='3' stamp='1.001'>"
            "<task_done success='1' stamp='1.005' nmsize='99'/></task></compilation_log></hotspot_log>")

        self.assertEqual('outside.C queued ()V', result['logRecords']['queued'][0]['rawAttributes']['method'])
        self.assertEqual(14, result['logRecords']['tasks'][0]['osrBci'])
        self.assertEqual('400', result['logRecords']['nmethods'][0]['rawAttributes']['size'])
        self.assertEqual('99', result['logRecords']['tasks'][0]['taskDone'][0]['rawAttributes']['nmsize'])
        self.assertEqual('make_not_entrant', result['failureAndPublicationStateRecords'][0]['tag'])
        candidates = result['compileIdCandidates'][result['taskLogSpans'][0]['compileIdentityCandidatesIndex']]
        self.assertEqual([0], candidates['nmethodIndices'])
        self.assertFalse(result['codeSizeProven'])

    def test_duplicate_compile_ids_keep_every_task_and_publication_candidate(self):
        task = ("<task compile_id='7' method='p.C f ()V' stamp='1.001'>"
                "<task_done success='1' stamp='1.005'/></task>")
        publication = "<nmethod compile_id='7' method='p.C f ()V'/>"
        result = review.inspect_text(
            "<hotspot_log process='100'>" + publication * 2 + "<compilation_log thread='101'>"
            "<start_compile_thread thread='101' process='100'/>" + task * 2 +
            '</compilation_log></hotspot_log>')

        self.assertEqual(2, len(result['taskLogSpans']))
        for row in result['taskLogSpans']:
            candidates = result['compileIdCandidates'][row['compileIdentityCandidatesIndex']]
            self.assertEqual([0, 1], candidates['taskIndices'])
            self.assertEqual([0, 1], candidates['nmethodIndices'])
            self.assertFalse(row['actualCompilerInvocationProven'])

    def test_inconsistent_thread_process_and_start_ownership_declines_the_pair(self):
        marker = "<start_compile_thread thread='101' process='100'/>"
        text = ("<hotspot_log process='100'><compilation_log thread='101'>" + marker +
                "<task compile_id='7' method='p.C f ()V' stamp='1.001'>"
                "<task_done success='1' stamp='1.005'/></task></compilation_log></hotspot_log>")
        variants = [text.replace(marker, ''), text.replace(marker, marker * 2),
                    text.replace("start_compile_thread thread='101'", "start_compile_thread thread='102'"),
                    text.replace(marker, marker.replace("process='100'", "process='102'")),
                    text.replace(marker, '').replace('</task>', '</task>' + marker),
                    text.replace("hotspot_log process='100'", 'hotspot_log'),
                    text.replace("compilation_log thread='101'", 'compilation_log')]
        for changed in variants:
            with self.subTest(log=changed):
                result = review.inspect_text(changed)

                self.assertFalse(result['taskLogSpans'][0]['loggedStampPairValid'])
                self.assertTrue(result['taskLogSpans'][0]['errors'])

    def test_fragments_orphans_and_empty_logs_cannot_claim_a_complete_pair_inventory(self):
        variants = ["<hotspot_log process='100'><task_done success='1' stamp='1.005'/></hotspot_log>",
                    "<hotspot_log process='100'><compilation_log thread='101'>"
                    "<fragment><![CDATA[<task incomplete]]></fragment></compilation_log></hotspot_log>",
                    '<hotspot_log/>']
        for text in variants:
            with self.subTest(log=text):
                result = review.inspect_text(text)

                self.assertFalse(result['allLoggedTaskStampPairsValid'])
                self.assertEqual(review.identities.parse_log(text), result['logRecords'])
                self.assertFalse(result['rootCensusProven'])

    def test_document_declarations_foreign_roots_and_input_budget_fail_before_identity_review(self):
        for text in ['<!DOCTYPE hotspot_log><hotspot_log/>', '<other/>', '<hotspot_log/>']:
            with self.subTest(log=text), patch.object(review, 'MAX_INPUT_BYTES', 1 if text == '<hotspot_log/>' else 1024), \
                    patch.object(review.identities, 'parse_log') as parse:
                with self.assertRaises(ValueError):
                    review.inspect_text(text)

                parse.assert_not_called()

    def test_output_budget_keeps_input_provenance_and_clears_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'review.json'
            report = {'completed': True, 'sourceSha256': 'a' * 64,
                      'inputFiles': {'raw.xml': {'sha256': 'b' * 64}}, 'taskLogSpans': ['large derived data']}

            with patch.object(review, 'MAX_OUTPUT_BYTES', 1), self.assertRaises(ValueError):
                review.write_report(output, report)

            result = json.loads(output.read_text())
            self.assertFalse(result['completed'])
            self.assertEqual(report['inputFiles'], result['inputFiles'])
            self.assertEqual('OutputLimitExceeded', result['error']['type'])
            self.assertNotIn('taskLogSpans', result)
            for key in review.DENIED:
                self.assertFalse(result[key])

    def test_invalid_saved_xml_keeps_raw_input_hashes_and_a_negative_report(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            raw_log = destination / 'saved-task-log-inputs/live/hotspot.xml'
            raw_log.parent.mkdir(parents=True)
            raw_log.write_bytes(b'<hotspot_log><unfinished')
            output = destination / 'review.json'
            argv = ['review_jvm_task_log.py', 'manifest.json', 'a' * 64, 'capture.tar.gz', str(output)]

            with patch.object(review.sys, 'argv', argv), \
                    patch.object(review.identities.original, 'extract_inputs', return_value={'profilePrefix': 'profile1/'}), \
                    self.assertRaises(review.ET.ParseError):
                review.main()

            result = json.loads(output.read_text())
            self.assertFalse(result['completed'])
            self.assertEqual('ParseError', result['error']['type'])
            self.assertEqual(review.hashlib.sha256(raw_log.read_bytes()).hexdigest(),
                             result['inputFiles'][str(raw_log)]['sha256'])
            self.assertEqual(b'<hotspot_log><unfinished', raw_log.read_bytes())
            for key in review.DENIED:
                self.assertFalse(result[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
