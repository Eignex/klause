"""Replay pinned archived XCSP CLI distributions without replacing the installed harness."""
import hashlib
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import tarfile
import time
import zipfile

IDENTITY = re.compile(r"[a-z0-9][a-z0-9-]{0,63}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
CLASS = re.compile(r"com/eignex/klause/(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+\.class\Z")
LIMIT = 2 * 1024 * 1024


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def fields(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() <= set(required) | set(optional):
        raise ValueError("Unexpected request fields")


def keyed(values, maximum):
    if not isinstance(values, list) or not 1 <= len(values) <= maximum:
        raise ValueError("Invalid request cardinality")
    result = {}
    for value in values:
        if not IDENTITY.fullmatch(value["id"]) or value["id"] in result:
            raise ValueError("Invalid or duplicate identity")
        result[value["id"]] = value
    return result


def validate(request):
    fields(request, ("bucket", "region", "archives", "sources", "cases"))
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{2,62}", request["bucket"]):
        raise ValueError("Invalid bucket")
    if not re.fullmatch(r"[a-z]{2}-[a-z]+-[0-9]", request["region"]):
        raise ValueError("Invalid region")
    archives = keyed(request["archives"], 4)
    sources = keyed(request["sources"], 12)
    cases = keyed(request["cases"], 12)
    for archive in archives.values():
        fields(archive, ("id", "key", "etag"), ("sha256",))
        if not re.fullmatch(r"builds/(?:verified-v1/)?[0-9a-f]{40}\.tar\.zst", archive["key"]):
            raise ValueError("Expected a revision-addressed build archive")
        if not re.fullmatch(r'"[0-9a-f]{32}(?:-[0-9]+)?"', archive["etag"]):
            raise ValueError("Expected an exact ETag")
        if "sha256" in archive and not HASH.fullmatch(archive["sha256"]):
            raise ValueError("Invalid archive hash")
    for source in sources.values():
        fields(source, ("id", "text", "sha256", "witness"))
        if not HASH.fullmatch(source["sha256"]) or len(source["text"].encode()) > LIMIT:
            raise ValueError("Invalid source identity or size")
        if hashlib.sha256(source["text"].encode()).hexdigest() != source["sha256"]:
            raise ValueError("Source bytes differ from the requested hash")
        if not 1 <= len(source["witness"].encode()) <= 64 * 1024:
            raise ValueError("Invalid control witness size")
    for case in cases.values():
        fields(case, ("id", "archive", "source"), ("overlay", "wallMillis", "args", "seed", "timeoutMillis"))
        if case["archive"] not in archives or case["source"] not in sources:
            raise ValueError("Unknown archive or source")
        if "overlay" in case:
            overlay = case["overlay"]
            fields(overlay, ("archive", "classes"))
            if overlay["archive"] not in archives or not 1 <= len(overlay["classes"]) <= 8:
                raise ValueError("Invalid class overlay")
            if any(not CLASS.fullmatch(name) for name in overlay["classes"]):
                raise ValueError("Overlay class is outside Klause")
        if "wallMillis" in case and not 2000 <= case["wallMillis"] <= 60000:
            raise ValueError("Invalid diagnostic wall cap")
        if not 1 <= case.get("timeoutMillis", 60000) <= 60000 or not 0 <= case.get("seed", 3) <= 2**31 - 1:
            raise ValueError("Invalid solve allowance")
        args = case.get("args", [])
        if not isinstance(args, list) or len(args) > 24 or any(not isinstance(a, str) or len(a) > 256 for a in args):
            raise ValueError("Invalid CLI arguments")
        if len(args) % 2:
            raise ValueError("CLI arguments must be engine or parameter pairs")
        for option, value in zip(args[::2], args[1::2]):
            if option in ("--engine", "-e") and value in ("cp", "mixed", "ls", "alns"):
                continue
            if option == "--param" and re.fullmatch(r"[a-z][a-z0-9-]*=[A-Za-z0-9_,.+-]+", value):
                if value.split("=", 1)[0] not in ("seed", "cores", "processors", "timeout"):
                    continue
            raise ValueError("Unsupported CLI argument")
    return archives, sources, cases


def command(args, directory, label, timeout):
    stdout, stderr = directory / (label + ".stdout"), directory / (label + ".stderr")
    write(directory / (label + "-command.json"), args)
    started = time.monotonic()
    stopped = None
    with stdout.open("wb") as out, stderr.open("wb") as err:
        process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        try:
            while process.poll() is None:
                if time.monotonic() - started > timeout:
                    stopped = "watchdog"
                if stdout.stat().st_size > LIMIT or stderr.stat().st_size > LIMIT:
                    stopped = "output-limit"
                if stopped:
                    process.kill()
                    break
                try:
                    process.wait(timeout=0.05)
                except subprocess.TimeoutExpired:
                    pass
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
    write(directory / (label + "-process.json"), {
        "exit": process.returncode, "stop": stopped, "wallSeconds": time.monotonic() - started,
        "stdoutSha256": sha(stdout), "stderrSha256": sha(stderr),
    })
    if stopped:
        raise RuntimeError("Child exceeded its allowance: " + stopped)
    return process.returncode, stdout


def archive(spec, request, root):
    directory = root / ("archive-" + spec["id"])
    directory.mkdir()
    obj = directory / "original.tar.zst"
    prefix = ["aws", "--region", request["region"], "s3api"]
    location = ["--bucket", request["bucket"], "--key", spec["key"], "--if-match", spec["etag"]]
    exit_code, metadata = command(prefix + ["head-object"] + location, directory, "head", 30)
    expected_size = json.loads(metadata.read_text())["ContentLength"] if exit_code == 0 else None
    if expected_size is None or expected_size > 64 * 1024 * 1024:
        raise ValueError("Archive is unavailable or oversized")
    exit_code, _ = command(prefix + ["get-object"] + location + [str(obj)], directory, "download", 60)
    if exit_code or obj.stat().st_size != expected_size:
        raise ValueError("Archive download failed")
    identity = sha(obj)
    write(directory / "archive.json", {"key": spec["key"], "etag": spec["etag"], "sha256": identity})
    if "sha256" in spec and spec["sha256"] != identity:
        raise ValueError("Archive bytes differ from the requested hash")
    install = directory / "install"
    install.mkdir()
    process = subprocess.Popen(["zstd", "-dc", str(obj)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    complete = False
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as tar:
            extract(tar, install)
        if len(process.stdout.read(1024 * 1024 + 1)) > 1024 * 1024:
            raise ValueError("Oversized archive trailer")
        complete = True
    finally:
        process.stdout.close()
        if not complete and process.poll() is None:
            process.kill()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise
    if process.returncode:
        raise ValueError("Archive decoder failed")
    write(directory / "installed-hashes.json", {
        str(p.relative_to(install)): {"bytes": p.stat().st_size, "sha256": sha(p)}
        for p in sorted(install.rglob("*")) if p.is_file()
    })
    lib = install / "klause-cli/build/install/klause-cli-jvm/lib"
    if not (lib / "klause-jvm-SNAPSHOT.jar").is_file():
        raise ValueError("Archived JVM CLI distribution is absent")
    return lib


def extract(tar, install):
    size, count = 0, 0
    for entry in tar:
        name = pathlib.PurePosixPath(entry.name)
        if name.is_absolute() or ".." in name.parts or not (entry.isdir() or entry.isfile()):
            raise ValueError("Unsafe archive member")
        roots = ("klause-cli/build/install", "klause-bench/build/install")
        if not any(entry.name == root or entry.name.startswith(root + "/") for root in roots):
            raise ValueError("Archive member outside installed distributions")
        count += 1
        size += entry.size
        if count > 4096 or size > 96 * 1024 * 1024:
            raise ValueError("Expanded archive is oversized")
        dest = install / name
        if entry.isdir():
            dest.mkdir(parents=True, exist_ok=True)
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(entry) as source, dest.open("xb") as target:
                while block := source.read(1024 * 1024):
                    target.write(block)


def wall_patch(data, millis):
    buf = bytearray(data)
    if buf[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class header")
    count = struct.unpack_from(">H", buf, 8)[0]
    index, pos, edits = 1, 10, []
    while index < count:
        tag = buf[pos]
        pos += 1
        if tag == 1:
            pos += 2 + struct.unpack_from(">H", buf, pos)[0]
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            if tag == 5 and struct.unpack_from(">q", buf, pos)[0] == 2000:
                edits.append(pos)
                struct.pack_into(">q", buf, pos, millis)
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError("Unsupported constant pool tag")
        index += 1
    if len(edits) != 1:
        raise ValueError("Expected exactly one default wall cap per portfolio class")
    return bytes(buf), edits


def main():
    request_path, root, java_home, classpath = sys.argv[1:]
    root, java_home = pathlib.Path(root), pathlib.Path(java_home)
    request = json.loads(pathlib.Path(request_path).read_text())
    archives, sources, cases = validate(request)
    write(root / "runtime-hashes.json", {
        str(p.relative_to(java_home)): {"sha256": sha(p), "bytes": p.stat().st_size}
        for p in sorted(java_home.rglob("*")) if p.is_file() and not p.is_symlink()
    })
    command([str(java_home / "bin/java"), "-version"], root, "runtime", 10)
    java = [str(java_home / "bin/java"), "-Xmx512m", "-XX:ActiveProcessorCount=1", "-cp", classpath,
            "com.eignex.klause.bench.metric.Xcsp3SourceChecker"]
    controls = {}
    for identity, source in sources.items():
        directory = root / ("source-" + identity)
        directory.mkdir()
        model, witness = directory / "source.xml", directory / "control.xml"
        model.write_text(source["text"])
        witness.write_text(source["witness"])
        exit_code, stdout = command(java + [str(model), str(witness)], directory, "control", 12)
        checked = json.loads(stdout.read_text())
        if exit_code or checked.get("status") != "valid":
            raise ValueError("The original-source positive control was not accepted")
        controls[identity] = model
    installed = {identity: archive(spec, request, root) for identity, spec in archives.items()}
    results = []
    for identity, case in cases.items():
        directory = root / ("case-" + identity)
        directory.mkdir()
        lib = installed[case["archive"]]
        overlay = directory / "overlay"
        changes = []
        if "overlay" in case:
            with zipfile.ZipFile(installed[case["overlay"]["archive"]] / "klause-jvm-SNAPSHOT.jar") as jar:
                for name in case["overlay"]["classes"]:
                    if jar.getinfo(name).file_size > LIMIT:
                        raise ValueError("Oversized overlay class")
                    data = jar.read(name)
                    dest = overlay / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
                    changes.append({"class": name, "archive": case["overlay"]["archive"], "sha256": sha(dest)})
        if "wallMillis" in case:
            with zipfile.ZipFile(lib / "klause-jvm-SNAPSHOT.jar") as jar:
                for name in ("com/eignex/klause/portfolio/Portfolio.class",
                             "com/eignex/klause/portfolio/Portfolio$Companion.class"):
                    if (overlay / name).exists():
                        raise ValueError("Wall cap and class overlay collide")
                    original = jar.read(name)
                    patched, offsets = wall_patch(original, case["wallMillis"])
                    dest = overlay / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(patched)
                    changes.append({"class": name, "beforeMillis": 2000, "afterMillis": case["wallMillis"],
                                    "byteOffsets": offsets, "originalSha256": hashlib.sha256(original).hexdigest(),
                                    "sha256": sha(dest)})
        write(directory / "interventions.json", changes)
        cp = ":".join(([str(overlay)] if changes else []) + [str(p) for p in sorted(lib.glob("*.jar"))])
        args = [str(java_home / "bin/java"), "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector", "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
                "-cp", cp, "com.eignex.klause.cli.MainKt", str(controls[case["source"]])]
        args += case.get("args", []) + ["-t", str(case.get("timeoutMillis", 60000)),
                                      "-r", str(case.get("seed", 3)), "-s", "-p", "1"]
        exit_code, stdout = command(args, directory, "solve", case.get("timeoutMillis", 60000) / 1000 + 10)
        text = stdout.read_text()
        result = {"case": identity, "exit": exit_code, "instrumented": bool(changes),
                  "sourceSha256": sources[case["source"]]["sha256"], "witnessStatus": None,
                  "contradictsAcceptedWitness": exit_code == 0 and "s UNSATISFIABLE" in text.splitlines()}
        witnesses = [line[2:] for line in text.splitlines() if line.startswith("v <instantiation>")]
        if exit_code == 0 and len(witnesses) == 1:
            witness = directory / "witness.xml"
            witness.write_text(witnesses[0])
            check_exit, checked = command(java + [str(controls[case["source"]]), str(witness)], directory, "check", 12)
            result["witnessStatus"] = json.loads(checked.read_text()).get("status") if check_exit == 0 else "error"
        write(directory / "result.json", result)
        results.append(result)
        write(root / "results.json", results)
        print(identity, result["witnessStatus"], result["contradictsAcceptedWitness"], flush=True)
    write(root / "completion.json", {"completedCases": len(results), "expectedCases": len(cases),
                                     "allExitedSuccessfully": all(r["exit"] == 0 for r in results),
                                     "originalHistoricalRuntimeRecovered": False})


if __name__ == "__main__":
    try:
        main()
    except BaseException as error:
        if len(sys.argv) >= 3:
            write(pathlib.Path(sys.argv[2]) / "failure.json", {"error": repr(error), "complete": False})
        raise
