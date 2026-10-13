"""Bounded original corpus-byte capture. No source parsing or solver calls."""
import contextlib
import hashlib
import http.client
import json
import os
import resource
import signal
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request
import urllib.parse
from pathlib import Path

MAX_ARCHIVE = 256 * 1024 * 1024
MAX_DOWNLOADS = 512 * 1024 * 1024
MAX_DECODED = 8 * 1024 * 1024 * 1024
MAX_SOURCE = 32 * 1024 * 1024
MAX_SOURCES = 128 * 1024 * 1024
MAX_MEMBERS = 200000
MAX_STDERR = 1024 * 1024
SECONDS = 1200
BLOCK = 65536
MAX_RECEIPT = 4 * 1024 * 1024
MAX_RECEIPTS = 32 * 1024 * 1024
RECEIPT_SIZES = {}
MAX_ERROR_TEXT = 2048


class Stopped(RuntimeError):
    pass


def canonical_member(name):
    while name.startswith("./"):
        name = name[2:]
    if not name or name.startswith("/") or "\\" in name or "\0" in name:
        raise ValueError("unsafe archive member")
    parts = name.rstrip("/").split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("unsafe archive member")
    return "/".join(parts)


def error_text(error):
    return (type(error).__name__ + ": " + str(error))[:MAX_ERROR_TEXT]


def cleanup_action(errors, label, action):
    try:
        return action()
    except Exception as error:
        errors.append(label + ": " + error_text(error))
        return None


def preserve_failure(primary, errors, message):
    if primary is not None:
        for error in errors:
            if hasattr(primary, "add_note"):
                primary.add_note(error)
        raise primary
    if errors:
        raise OSError(message + ": " + "; ".join(errors))


def close_output(output, errors):
    cleanup_action(errors, "flush", output.flush)
    cleanup_action(errors, "fsync", lambda: os.fsync(output.fileno()))
    cleanup_action(errors, "close", output.close)


@contextlib.contextmanager
def closing_preserving(stream):
    primary = None
    errors = []
    try:
        yield stream
    except Exception as error:
        primary = error
    finally:
        cleanup_action(errors, "stream close", stream.close)
    preserve_failure(primary, errors, "stream cleanup failed")


def write_json(path, value):
    key = str(path)
    written_bytes = 0
    output = None
    primary = None
    errors = []
    try:
        raw = (json.dumps(value, indent=2) + "\n").encode("utf-8")
        if len(raw) > MAX_RECEIPT:
            raise ValueError("receipt byte cap reached")
        if sum(RECEIPT_SIZES.values()) - RECEIPT_SIZES.get(key, 0) + len(raw) > MAX_RECEIPTS:
            raise ValueError("aggregate receipt byte cap reached")
        output = path.open("wb", buffering=0)
        offset = 0
        while offset < len(raw):
            written = output.write(raw[offset:])
            if not written:
                raise OSError("receipt write made no progress")
            offset += written
            written_bytes += written
    except Exception as error:
        primary = error
    finally:
        if output is not None:
            RECEIPT_SIZES[key] = written_bytes
            close_output(output, errors)
    preserve_failure(primary, errors, "receipt durability failed")


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        require_https(url)
        return super().redirect_request(request, fp, code, message, headers, url)


def require_https(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("HTTPS URL without credentials required")


def copy_source(stream, destination, expected, budget, checkpoint, maximum=MAX_SOURCE):
    digest = hashlib.sha256()
    received = 0
    complete = False
    matched = False
    output = None
    primary = None
    errors = []
    try:
        output = destination.open("xb", buffering=0)
        while True:
            checkpoint()
            block = stream.read(min(BLOCK, maximum - received + 1))
            if not block:
                complete = True
                break
            allowed = min(len(block), maximum - received, MAX_SOURCES - budget[0])
            offset = 0
            while offset < allowed:
                written = output.write(block[offset:allowed])
                if not written:
                    raise OSError("source prefix write made no progress")
                digest.update(block[offset:offset + written])
                offset += written
                received += written
                budget[0] += written
            if allowed != len(block):
                raise ValueError("source byte cap reached")
        matched = digest.hexdigest() == expected
        if not matched:
            raise ValueError("original source hash differs")
    except Exception as error:
        primary = error
    finally:
        if output is not None:
            close_output(output, errors)
        cleanup_action(errors, "source receipt", lambda: write_json(destination.with_suffix(".transfer.json"), {
            "expectedSha256": expected, "receivedBytes": received, "receivedSha256": digest.hexdigest(),
            "streamComplete": complete, "hashMatched": matched, "durabilityComplete": not errors,
            "copyError": None if primary is None else error_text(primary), "durabilityErrors": list(errors),
        }))
    preserve_failure(primary, errors, "source prefix durability failed")
    return {"bytes": received, "sha256": digest.hexdigest()}


def transfer_archive(response, destination, budget, checkpoint, maximum=MAX_ARCHIVE):
    digest = hashlib.sha256()
    retained = 0
    observed = 0
    eof = False
    complete = False
    primary = None
    cleanup_errors = []
    retention_errors = []
    declared = None
    output = None
    try:
        output = destination.open("xb", buffering=0)
        length = response.headers.get("Content-Length")
        declared = int(length) if length is not None else None
        if declared is not None and not 0 <= declared <= maximum:
            raise ValueError("declared archive size exceeds cap")
        while True:
            checkpoint()
            failure = None
            try:
                block = response.read(min(BLOCK, maximum - retained + 1, MAX_DOWNLOADS - budget[0] + 1))
            except http.client.IncompleteRead as error:
                primary = error
                block = error.partial
                failure = error
            observed += len(block)
            allowed = min(len(block), maximum - retained, MAX_DOWNLOADS - budget[0])
            budget[0] += len(block)
            offset = 0
            while offset < allowed:
                written = output.write(block[offset:allowed])
                if not written:
                    raise OSError("archive prefix write made no progress")
                digest.update(block[offset:offset + written])
                offset += written
                retained += written
            if failure is not None:
                raise failure
            if allowed != len(block):
                raise ValueError("archive transfer byte cap reached")
            if not block:
                eof = True
                break
        if declared is not None and retained != declared:
            raise ValueError("archive HTTP length differs")
        complete = True
    except Exception as error:
        if primary is None:
            primary = error
        elif error is not primary:
            retention_errors.append("partial retention: " + error_text(error))
    finally:
        if output is not None:
            close_output(output, cleanup_errors)
        result = {
            "downloadBytes": observed, "observedBytes": observed, "retainedBytes": retained,
            "omittedBytes": observed - retained,
            "sha256": digest.hexdigest(), "streamEof": eof, "declaredBytes": declared,
            "transportComplete": complete and not cleanup_errors and not retention_errors,
            "observedBytesScope": "read results and IncompleteRead.partial; other socket-buffer bytes unavailable",
            "transportError": None if primary is None else error_text(primary),
            "durabilityErrors": cleanup_errors, "retentionErrors": retention_errors,
        }
        try:
            write_json(destination.with_suffix(".transfer.json"), result)
        except Exception as error:
            cleanup_errors.append("transfer receipt: " + error_text(error))
    preserve_failure(primary, retention_errors + cleanup_errors, "archive prefix durability failed")
    return result


class DecodeStream:
    def __init__(self, stream, checkpoint):
        self.stream = stream
        self.checkpoint = checkpoint
        self.bytes = 0

    def read(self, size=-1):
        self.checkpoint()
        if size < 0 or size > BLOCK:
            size = BLOCK
        data = self.stream.read(min(size, MAX_DECODED - self.bytes + 1))
        self.bytes += len(data)
        if self.bytes > MAX_DECODED:
            raise ValueError("decoded archive byte cap reached")
        return data


def cleanup_decoder(process, drain, record, started, stderr_errors):
    errors = []
    if process is None:
        return errors
    status = cleanup_action(errors, "decoder poll", process.poll)
    if status is None:
        cleanup_action(errors, "decoder kill", process.kill)
    exit_code = cleanup_action(errors, "decoder reap", lambda: process.wait(timeout=5))
    if process.stdout is not None:
        cleanup_action(errors, "decoder stdout close", process.stdout.close)
    if drain is not None:
        cleanup_action(errors, "decoder stderr drain", lambda: drain.join(timeout=5))
    drain_alive = False if drain is None else cleanup_action(errors, "decoder drain state", drain.is_alive)
    if process.stderr is not None:
        if drain_alive is False:
            cleanup_action(errors, "decoder stderr close", process.stderr.close)
        else:
            errors.append("decoder stderr close withheld while drain retirement is unestablished")
    record.setdefault("exit", exit_code)
    record.setdefault("processWallNs", time.monotonic_ns() - started)
    record["stderrDrainComplete"] = drain_alive is False and not stderr_errors
    record["decoderReaped"] = exit_code is not None
    record["cleanupComplete"] = not errors and record["decoderReaped"] and record["stderrDrainComplete"]
    record["cleanupIssues"] = errors
    return errors


def finalize_capture(out, value, previous, stop, process=None, drain=None, started=0, stderr_errors=()):
    errors = []
    previous_mask = None
    try:
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT})
        stop.set()
        if process is not None:
            record = {}
            errors.extend(cleanup_decoder(process, drain, record, started, stderr_errors))
            value["finalDecoderCleanup"] = record
    except Exception as error:
        errors.append("final cleanup: " + error_text(error))
    finally:
        try:
            value["workerCleanupIssues"] = list(errors)
            value["workerCleanupComplete"] = not errors
            value["allRequestedSourcesCaptured"] = value["allRequestedSourcesCaptured"] and not errors
            cleanup_action(errors, "capture receipt", lambda: write_json(out / "capture.json", value))
        finally:
            try:
                for number, handler in previous.items():
                    cleanup_action(errors, "handler restore", lambda n=number, h=handler: signal.signal(n, h))
            finally:
                if previous_mask is not None:
                    cleanup_action(errors, "mask restore", lambda: signal.pthread_sigmask(signal.SIG_SETMASK,
                                                                                       previous_mask))
    return errors


def main(argv):
    request_path, out = Path(argv[0]), Path(argv[1])
    raw_request = request_path.read_bytes()
    if len(raw_request) > 512 * 1024:
        raise ValueError("resolved request oversized")
    request = json.loads(raw_request)
    if set(request) != {"schema", "cases"} or not 1 <= len(request["cases"]) <= 128:
        raise ValueError("invalid resolved request")
    if request["schema"] != "corpus-original-source-resolved-request-v1":
        raise ValueError("unsupported request")
    deadline = time.monotonic() + SECONDS
    stop = threading.Event()
    active = None
    results = [{**entry, "state": "notReached", "issues": []} for entry in request["cases"]]
    write_json(out / "planned-results.json", {"allRequestedSourcesCaptured": False, "results": results})
    archives = []
    download_budget = [0]
    source_budget = [0]
    worker_errors = []
    drain = None
    started = 0
    stderr_errors = []
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024 * 1024 * 1024, 2 * 1024 * 1024 * 1024))

    def checkpoint():
        if stop.is_set() or time.monotonic() >= deadline:
            raise Stopped("containing capture stopped")

    def interrupted(_number, _frame):
        stop.set()
        raise Stopped("containing capture signal")

    previous = {number: signal.signal(number, interrupted) for number in (signal.SIGTERM, signal.SIGINT)}

    def watchdog():
        if not stop.wait(SECONDS):
            os.kill(os.getpid(), signal.SIGTERM)

    watcher = threading.Thread(target=watchdog, daemon=True)
    try:
        watcher.start()
        groups = {}
        for result in results:
            groups.setdefault(result["archiveUrl"], []).append(result)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), HttpsRedirect())
        for group_index, (url, planned) in enumerate(groups.items()):
            checkpoint()
            if download_budget[0] >= MAX_DOWNLOADS or source_budget[0] >= MAX_SOURCES:
                raise Stopped("containing transfer byte allowance exhausted")
            record = {"url": url, "complete": False, "issues": []}
            archives.append(record)
            packed = out / ("archive-%d.part" % group_index)
            stderr_path = out / ("archive-%d.decode.stderr" % group_index)
            retained = []
            drain = None
            stderr_errors = []
            group_cleanup_errors = []
            try:
                require_https(url)
                record["transferPath"] = packed.with_suffix(".transfer.json").name
                with closing_preserving(opener.open(url, timeout=30)) as response:
                    if response.status != 200:
                        raise ValueError("archive HTTP status differs")
                    require_https(response.url)
                    record["finalUrl"] = response.url
                    record.update(transfer_archive(response, packed, download_budget, checkpoint))
                decoder = Path("/usr/bin/zstd")
                with decoder.open("rb") as binary:
                    decoder_bytes = binary.read(4 * 1024 * 1024 + 1)
                if len(decoder_bytes) > 4 * 1024 * 1024:
                    raise ValueError("decoder binary is oversized")
                record["decoderSha256"] = hashlib.sha256(decoder_bytes).hexdigest()
                command = [str(decoder), "-dcq", "--", str(packed)]
                started = time.monotonic_ns()
                active = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                record.update(command=command, pid=active.pid, stderrPath=stderr_path.name)
                stderr_overflow = threading.Event()
                stderr_errors = []

                def stderr_copy(process):
                    try:
                        copy_stderr(process)
                    except Exception as error:
                        stderr_errors.append(error_text(error))
                        cleanup_action(stderr_errors, "stderr failure kill", process.kill)

                def copy_stderr(process):
                    with closing_preserving(stderr_path.open("xb")) as target:
                        count = 0
                        while True:
                            block = process.stderr.read(BLOCK)
                            if not block:
                                break
                            allowed = min(len(block), MAX_STDERR - count)
                            target.write(block[:allowed])
                            count += allowed
                            if allowed != len(block):
                                stderr_overflow.set()
                                process.kill()
                                break
                        target.flush()
                        os.fsync(target.fileno())

                drain = threading.Thread(target=stderr_copy, args=(active,), daemon=True)
                drain.start()
                stream = DecodeStream(active.stdout, checkpoint)
                by_member = {entry["member"]: entry for entry in planned}
                seen = set()
                member_count = 0
                with closing_preserving(tarfile.open(fileobj=stream, mode="r|")) as archive:
                    for member in archive:
                        checkpoint()
                        member_count += 1
                        if member_count > MAX_MEMBERS:
                            raise ValueError("archive member count cap reached")
                        if member.isdir() and member.name in (".", "./"):
                            continue
                        name = canonical_member(member.name)
                        entry = by_member.get(name)
                        if entry is None:
                            continue
                        if name in seen:
                            raise ValueError("duplicate requested archive member")
                        seen.add(name)
                        if not member.isreg() or member.size > MAX_SOURCE:
                            raise ValueError("requested source is not a bounded regular member")
                        partial = out / ("source-%d.part" % entry["index"])
                        entry["partialPath"] = partial.name
                        payload = archive.extractfile(member)
                        if payload is None:
                            raise ValueError("source stream missing")
                        with closing_preserving(payload):
                            info = copy_source(payload, partial, entry["expectedSha256"], source_budget, checkpoint)
                        if info["bytes"] != member.size:
                            raise ValueError("source size differs from archive member")
                        entry.update(info, partialPath=partial.name)
                        retained.append(entry)
                while stream.read(BLOCK):
                    pass
                exit_code = active.wait(timeout=max(0.001, deadline - time.monotonic()))
                drain.join(timeout=5)
                record.update(command=command, pid=active.pid, exit=exit_code, decodedBytes=stream.bytes,
                              processWallNs=time.monotonic_ns() - started, stderrPath=stderr_path.name,
                              stderrDrainComplete=not drain.is_alive() and not stderr_errors,
                              stderrOverflow=stderr_overflow.is_set(), stderrErrors=stderr_errors,
                              memberCount=member_count)
                if exit_code != 0 or drain.is_alive() or stderr_overflow.is_set() or stderr_errors:
                    raise ValueError("decoder did not complete successfully")
                checkpoint()
                record["complete"] = True
                for entry in retained:
                    checkpoint()
                    final = out / ("source-%d.smt2" % entry["index"])
                    (out / entry["partialPath"]).rename(final)
                    entry.update(state="captured", path=final.name)
                for entry in planned:
                    if entry["state"] != "captured":
                        entry.update(state="failed", issues=["requested member absent from archive"])
                packed.unlink()
                record["archiveRawRetained"] = False
            except Exception as error:
                record["complete"] = False
                record["issues"].append(error_text(error))
                record["archiveRawRetained"] = cleanup_action(group_cleanup_errors, "failed archive stat", packed.exists)
                for entry in planned:
                    entry.update(state="failed")
                    entry["issues"].append(record["issues"][-1])
            finally:
                group_mask = None
                try:
                    group_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT})
                    transfer = packed.with_suffix(".transfer.json")
                    exists = cleanup_action(group_cleanup_errors, "transfer receipt state", transfer.exists)
                    metadata = None
                    if exists:
                        metadata = cleanup_action(group_cleanup_errors, "transfer receipt read", lambda:
                                                  json.loads(transfer.read_text(encoding="utf-8")))
                    if metadata is not None:
                        record.update(metadata)
                    else:
                        record.update(transportComplete=False, transferUnavailable="transfer receipt unavailable")
                    record["retainedArchiveBytes"] = cleanup_action(group_cleanup_errors, "archive size", lambda:
                                                                   packed.stat().st_size if packed.exists() else 0)
                    for entry in planned:
                        partial = out / ("source-%d.part" % entry["index"])
                        entry["partialBytes"] = cleanup_action(group_cleanup_errors, "source prefix size", lambda p=partial:
                                                              p.stat().st_size if p.exists() else 0)
                except Exception as error:
                    group_cleanup_errors.append("collection metadata: " + error_text(error))
                finally:
                    try:
                        group_cleanup_errors.extend(cleanup_decoder(active, drain, record, started, stderr_errors))
                    finally:
                        active = None
                        if group_cleanup_errors:
                            record["complete"] = False
                            record["issues"].extend(group_cleanup_errors)
                            for entry in planned:
                                entry["state"] = "failed"
                                entry["issues"].append("collection cleanup or durability incomplete")
                        cleanup_action(group_cleanup_errors, "progress receipt", lambda:
                                       write_json(out / "progress.json", {"results": results, "archives": archives}))
                        if group_mask is not None:
                            cleanup_action(group_cleanup_errors, "collection mask restore", lambda:
                                           signal.pthread_sigmask(signal.SIG_SETMASK, group_mask))
                if group_cleanup_errors:
                    record["complete"] = False
                    for entry in planned:
                        entry["state"] = "failed"
                    worker_errors.extend(group_cleanup_errors)
            if group_cleanup_errors:
                raise Stopped("collection cleanup or durability incomplete")
    except Exception as error:
        worker_errors.append(error_text(error))
        for entry in results:
            if entry["state"] == "notReached":
                entry["issues"].append(error_text(error))
    finally:
        value = {
            "schema": "corpus-original-source-byte-capture-v1", "results": results, "archives": archives,
            "allRequestedSourcesCaptured": all(entry["state"] == "captured" for entry in results) and not worker_errors,
            "sourceOnly": True, "solverCalls": 0, "sourceParserCalls": 0, "checkerCalls": 0,
            "sourceBytesRetained": source_budget[0], "downloadBytes": download_budget[0],
            "downloadBytesScope": "observed read results including omitted IncompleteRead.partial bytes",
            "pythonVersion": sys.version, "pythonExecutable": sys.executable,
            "issues": worker_errors,
            "limits": {"seconds": SECONDS, "archiveBytes": MAX_ARCHIVE, "downloadBytes": MAX_DOWNLOADS,
                       "decodedArchiveBytes": MAX_DECODED, "sourceBytes": MAX_SOURCE,
                       "totalSourceBytes": MAX_SOURCES, "members": MAX_MEMBERS, "receiptBytes": MAX_RECEIPT,
                       "totalReceiptBytes": MAX_RECEIPTS},
        }
        errors = finalize_capture(out, value, previous, stop, active, drain, started, stderr_errors)
        if errors:
            raise OSError("worker cleanup or receipt failed: " + "; ".join(errors))


if __name__ == "__main__":
    main(sys.argv[1:])
