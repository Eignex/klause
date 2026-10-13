import hashlib
import http.client
import json
import io
import signal
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from capture_corpus_sources import canonical_member, copy_source, finalize_capture, transfer_archive


class CorpusSourceCaptureTest(unittest.TestCase):
    def test_original_bytes_keep_line_endings_and_hash(self):
        raw = b"opaque\r\nsource\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.part"
            budget = [0]
            result = copy_source(io.BytesIO(raw), path, hashlib.sha256(raw).hexdigest(), budget, lambda: None)
            self.assertEqual(raw, path.read_bytes())
            self.assertEqual(len(raw), result["bytes"])
            self.assertEqual(len(raw), budget[0])

    def test_mismatched_hash_retains_complete_unaccepted_bytes(self):
        raw = b"opaque source"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.part"
            with self.assertRaisesRegex(ValueError, "hash differs"):
                copy_source(io.BytesIO(raw), path, "0" * 64, [0], lambda: None)
            self.assertEqual(raw, path.read_bytes())

    def test_byte_decline_retains_only_the_bounded_partial_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.part"
            budget = [0]
            with self.assertRaisesRegex(ValueError, "byte cap"):
                copy_source(io.BytesIO(b"abcd"), path, "0" * 64, budget, lambda: None, maximum=3)
            self.assertEqual(b"abc", path.read_bytes())
            self.assertEqual(3, budget[0])

    def test_containing_stop_never_changes_partial_copy_into_success(self):
        def stopped():
            raise RuntimeError("containing stop")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.part"
            with self.assertRaisesRegex(RuntimeError, "containing stop"):
                copy_source(io.BytesIO(b"opaque"), path, "0" * 64, [0], stopped)
            self.assertEqual(b"", path.read_bytes())

    def test_incomplete_http_read_preserves_and_debits_bounded_exception_bytes(self):
        failure = http.client.IncompleteRead(b"cdefgh", 10)

        class Response:
            headers = {}
            calls = 0

            def read(self, _maximum):
                self.calls += 1
                if self.calls == 1:
                    return b"ab"
                raise failure

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.part"
            budget = [0]
            response = Response()
            with self.assertRaises(http.client.IncompleteRead) as caught:
                transfer_archive(response, path, budget, lambda: None, maximum=5)
            self.assertIs(failure, caught.exception)
            self.assertEqual(2, response.calls)
            self.assertEqual(b"abcde", path.read_bytes())
            self.assertEqual(8, budget[0])
            receipt = json.loads(path.with_suffix(".transfer.json").read_text())
            self.assertEqual(8, receipt["observedBytes"])
            self.assertEqual(5, receipt["retainedBytes"])
            self.assertEqual(3, receipt["omittedBytes"])
            self.assertEqual(hashlib.sha256(b"abcde").hexdigest(), receipt["sha256"])
            self.assertFalse(receipt["transportComplete"])
            self.assertFalse(receipt["streamEof"])

    def test_prefix_durability_failure_does_not_replace_original_http_failure(self):
        failure = http.client.IncompleteRead(b"opaque", 7)

        class Response:
            headers = {}

            def read(self, _maximum):
                raise failure

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.part"
            with patch("capture_corpus_sources.os.fsync", side_effect=OSError("durability failure")):
                with self.assertRaises(http.client.IncompleteRead) as caught:
                    transfer_archive(Response(), path, [0], lambda: None)
            self.assertIs(failure, caught.exception)
            self.assertEqual(b"opaque", path.read_bytes())
            receipt = json.loads(path.with_suffix(".transfer.json").read_text())
            self.assertFalse(receipt["transportComplete"])
            self.assertTrue(receipt["durabilityErrors"])
            self.assertTrue(any("durability failure" in note for note in caught.exception.__notes__))

    def test_partial_http_write_failure_preserves_the_original_read_exception(self):
        for prefix, partial_bytes in ((b"", 0), (b"ab", 0), (b"ab", 2)):
            with self.subTest(prefix=prefix, partial_bytes=partial_bytes), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "archive.part"
                failure = http.client.IncompleteRead(b"cdef", 8)
                write_failure = OSError("partial storage unavailable")

                class Response:
                    headers = {}
                    calls = 0

                    def read(self, _maximum):
                        self.calls += 1
                        if prefix and self.calls == 1:
                            return prefix
                        raise failure

                class FailingWriter:
                    def __init__(self, output):
                        self.output = output
                        self.calls = 0
                        self.partial_written = False

                    def write(self, block):
                        self.calls += 1
                        if prefix and self.calls == 1:
                            return self.output.write(block)
                        if partial_bytes and not self.partial_written:
                            self.partial_written = True
                            return self.output.write(block[:partial_bytes])
                        raise write_failure

                    def flush(self):
                        self.output.flush()

                    def fileno(self):
                        return self.output.fileno()

                    def close(self):
                        self.output.close()

                original_open = Path.open

                def open_output(target, *args, **kwargs):
                    output = original_open(target, *args, **kwargs)
                    return FailingWriter(output) if target == path else output

                budget = [0]
                response = Response()
                with patch("capture_corpus_sources.Path.open", new=open_output):
                    with self.assertRaises(http.client.IncompleteRead) as caught:
                        transfer_archive(response, path, budget, lambda: None)
                self.assertIs(failure, caught.exception)
                expected_prefix = prefix + b"cdef"[:partial_bytes]
                self.assertEqual(expected_prefix, path.read_bytes())
                self.assertEqual(1 + bool(prefix), response.calls)
                self.assertEqual(len(prefix) + 4, budget[0])
                receipt = json.loads(path.with_suffix(".transfer.json").read_text())
                self.assertEqual(len(prefix) + 4, receipt["observedBytes"])
                self.assertEqual(len(expected_prefix), receipt["retainedBytes"])
                self.assertEqual(4 - partial_bytes, receipt["omittedBytes"])
                self.assertEqual(hashlib.sha256(expected_prefix).hexdigest(), receipt["sha256"])
                self.assertFalse(receipt["transportComplete"])
                self.assertFalse(receipt["streamEof"])
                self.assertIn("IncompleteRead", receipt["transportError"])
                self.assertTrue(any("partial storage unavailable" in issue for issue in receipt["retentionErrors"]))
                self.assertTrue(any("partial storage unavailable" in note for note in failure.__notes__))

    def test_source_read_failure_survives_failed_durability_and_receipt(self):
        failure = OSError("source read failure")

        class Source:
            calls = 0

            def read(self, _maximum):
                self.calls += 1
                if self.calls == 1:
                    return b"opaque"
                raise failure

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.part"
            budget = [0]
            with patch("capture_corpus_sources.os.fsync", side_effect=OSError("secondary fsync failure")):
                with self.assertRaises(OSError) as caught:
                    copy_source(Source(), path, "0" * 64, budget, lambda: None)
            self.assertIs(failure, caught.exception)
            self.assertEqual(b"opaque", path.read_bytes())
            self.assertEqual(6, budget[0])
            receipt = json.loads(path.with_suffix(".transfer.json").read_text())
            self.assertIn("source read failure", receipt["copyError"])
            self.assertFalse(receipt["durabilityComplete"])
            self.assertFalse(receipt["streamComplete"])
            self.assertTrue(any("secondary fsync failure" in note for note in failure.__notes__))

    def test_decoder_cleanup_failures_still_save_capture_and_restore_signals(self):
        actions = []

        class Output:
            def close(self):
                actions.append("stdout close")
                raise OSError("stream close failure")

        class ErrorOutput:
            def close(self):
                actions.append("stderr close")

        class Process:
            stdout = Output()
            stderr = ErrorOutput()

            def poll(self):
                return None

            def kill(self):
                actions.append("kill")
                raise OSError("kill failure")

            def wait(self, timeout):
                actions.append("reap")
                self.timeout = timeout
                raise subprocess.TimeoutExpired("fake decoder", timeout)

        class Drain:
            def join(self, timeout):
                actions.append("drain")
                self.timeout = timeout
                raise InterruptedError("drain interruption")

            def is_alive(self):
                return True

        def mask(how, _mask):
            actions.append("block" if how == signal.SIG_BLOCK else "unblock")
            return set()

        def restore(_number, _handler):
            actions.append("handler restore")

        process, drain = Process(), Drain()
        value = {"allRequestedSourcesCaptured": True}
        stop = threading.Event()
        with tempfile.TemporaryDirectory() as directory:
            with patch("capture_corpus_sources.signal.pthread_sigmask", side_effect=mask):
                with patch("capture_corpus_sources.signal.signal", side_effect=restore):
                    issues = finalize_capture(Path(directory), value, {signal.SIGTERM: signal.SIG_DFL},
                                              stop, process, drain)
            receipt = json.loads((Path(directory) / "capture.json").read_text())
        self.assertTrue(stop.is_set())
        self.assertFalse(receipt["allRequestedSourcesCaptured"])
        self.assertFalse(receipt["workerCleanupComplete"])
        self.assertFalse(receipt["finalDecoderCleanup"]["decoderReaped"])
        self.assertFalse(receipt["finalDecoderCleanup"]["stderrDrainComplete"])
        self.assertEqual(5, process.timeout)
        self.assertEqual(5, drain.timeout)
        self.assertTrue(any("kill failure" in issue for issue in issues))
        self.assertTrue(any("TimeoutExpired" in issue for issue in issues))
        self.assertTrue(any("drain interruption" in issue for issue in issues))
        self.assertEqual(["block", "kill", "reap", "stdout close", "drain",
                          "handler restore", "unblock"], actions)

    def test_failed_final_receipt_does_not_skip_signal_retirement(self):
        actions = []

        def mask(how, _mask):
            actions.append("block" if how == signal.SIG_BLOCK else "unblock")
            return set()

        def restore(_number, _handler):
            actions.append("handler restore")

        with tempfile.TemporaryDirectory() as directory:
            with patch("capture_corpus_sources.signal.pthread_sigmask", side_effect=mask):
                with patch("capture_corpus_sources.signal.signal", side_effect=restore):
                    with patch("capture_corpus_sources.write_json", side_effect=OSError("receipt failure")):
                        issues = finalize_capture(Path(directory), {"allRequestedSourcesCaptured": True},
                                                  {signal.SIGINT: signal.SIG_DFL}, threading.Event())
        self.assertTrue(any("receipt failure" in issue for issue in issues))
        self.assertEqual(["block", "handler restore", "unblock"], actions)

    def test_archive_paths_cannot_escape_the_declared_member_identity(self):
        self.assertEqual("non-incremental/QF_NRA/family/input.smt2",
                         canonical_member("./non-incremental/QF_NRA/family/input.smt2"))
        for name in ("../input", "/input", "family/../input", "family\\input", "family//input"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                canonical_member(name)


if __name__ == "__main__":
    unittest.main()
