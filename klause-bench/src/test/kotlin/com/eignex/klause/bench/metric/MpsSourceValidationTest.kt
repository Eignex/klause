package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpsSourceValidationTest {
    private val record = SolveRecord("fixture", "klause", null, 1, "free", 3, 10_000,
        "optimize", false, true, 1.0, 10, proven = true, gitSha = "producer", timestamp = "t", command = "cli",
        finalWitness = "v x=1", sourceHashes = mapOf("model" to "source"), sourceOutputSha256 = "stdout")
    private val bindings = mapOf("source.mps" to "source", "stdout.txt" to "stdout",
        "record.json" to "producer-record", "request.json" to "request")
    private val tools = mapOf("checker" to "pinned-tool")
    private val runtime = MpsCheckerRuntime("3.13.7", "/usr/bin/python3", "a".repeat(64), "x86_64",
        listOf("glibc", "2.39"), "4.15.3.0", "4.15.3", 29_056_003,
        "a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384",
        "https://files.pythonhosted.org/packages/" +
            "b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/" +
            "z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
        "b".repeat(64), listOf(MpsCheckerFile("z3/__init__.py", 1, "c".repeat(64)),
            MpsCheckerFile("z3/lib/libz3.so", 1, "d".repeat(64))), false)
    private val emptyStream = MpsCheckerStream(0, 0, true, sha256(byteArrayOf()))
    private val bootstrap = MpsCheckerProcess(listOf("/usr/bin/python3", "-I",
        "/owned/case/tools/smt_witness_validation.py", "bootstrap", "/owned/case", "/private/runtime/dependencies"),
        "/private/runtime", 0, false, false, null, mapOf("stdout" to emptyStream, "stderr" to emptyStream),
        true, 1_000_000, 2_147_483_648, 30)
    private val check = bootstrap.copy(command = listOf("/usr/bin/python3", "-I",
        "/owned/case/tools/mps_witness_validation.py", "check", "/owned/case", "/private/runtime/dependencies"),
        addressSpaceBytes = 4_294_967_296)
    private val arithmetic = MpsGroundArithmetic(MPS_GROUND_CHECKER, runtime.pythonVersion,
        runtime.pythonExecutableSha256, mapOf(
            "fractions" to MpsCheckerFile("/stdlib/fractions.py", 100, "e".repeat(64)),
            "numbers" to MpsCheckerFile("/stdlib/numbers.py", 100, "f".repeat(64)),
            "math" to MpsCheckerFile("/stdlib/math.so", 100, "a".repeat(64)),
        ))
    private val base = MpsSourceValidation.Receipt("valid", "ground checked", "ground-original-mps-witness",
        bindings, tools, 0, false, true, "v x=1", MPS_GROUND_CHECKER, 125, 4, runtime, arithmetic, bootstrap, check,
        exactFeasible = true, toleranceAccepted = false,
        sourceTolerance = mapOf("absolute" to "1/10000000", "relative" to "1/10000000"),
        witnessCheck = "all original MPS rows bounds integrality and objective evaluated at supplied point")
    private val childFields = setOf("status", "reason", "scope", "bindings", "solverSearchQueries",
        "proofCertificateChecked", "checkedWitness", "checker", "groundArithmetic", "exactFeasible",
        "toleranceAccepted", "sourceTolerance", "witnessCheck")
    private val child = JsonObject(Reports.json.parseToJsonElement(Reports.json.encodeToString(base)).jsonObject
        .filterKeys { it in childFields }).toString() + "\n"
    private val childStream = MpsCheckerStream(child.toByteArray().size.toLong(), child.toByteArray().size.toLong(),
        true, sha256(child.toByteArray()))
    private val receipt = base.copy(checkProcess = check.copy(streams = check.streams + ("stdout" to childStream)),
        processLogs = mapOf("bootstrap.stdout" to "", "bootstrap.stderr" to "", "check.stdout" to child,
            "check.stderr" to ""))

    private fun boundReceipt(value: MpsGroundArithmetic): MpsSourceValidation.Receipt {
        val changed = base.copy(groundArithmetic = value)
        val log = JsonObject(Reports.json.parseToJsonElement(Reports.json.encodeToString(changed)).jsonObject
            .filterKeys { it in childFields }).toString() + "\n"
        val bytes = log.toByteArray()
        return changed.copy(checkProcess = check.copy(streams = check.streams + ("stdout" to
            MpsCheckerStream(bytes.size.toLong(), bytes.size.toLong(), true, sha256(bytes)))),
            processLogs = mapOf("bootstrap.stdout" to "", "bootstrap.stderr" to "", "check.stdout" to log,
                "check.stderr" to ""))
    }

    private fun builtinArithmetic(shared: Boolean): MpsGroundArithmetic {
        val executable = "/usr/bin/python3.13"
        val library = "/usr/lib/libpython3.13.so.1.0"
        val maps = "1000-2000 r-xp 00000000 08:01 123 $executable\n" +
            if (shared) "3000-4000 r-xp 00000000 08:01 124 $library\n" else ""
        val providers = listOf(MpsCheckerFile(executable, 8_388_608, "a".repeat(64))) +
            if (shared) listOf(MpsCheckerFile(library, 16_777_216, "b".repeat(64))) else emptyList()
        return arithmetic.copy(modules = arithmetic.modules - "math", builtinMath = MpsBuiltinModule(
            "built-in", "_frozen_importlib.BuiltinImporter", executable, maps, sha256(maps.toByteArray()), providers))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `builtin math should bind static or shared interpreter providers larger than source modules`(shared: Boolean) {
        val bound = boundReceipt(builtinArithmetic(shared))

        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(bound), record, bindings, tools)

        assertEquals("valid", result.validation.status)
        assertEquals(record.finalWitness, result.validation.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["origin", "loader", "missing-fractions", "missing-numbers", "ambiguous-math",
        "missing-provider", "duplicate-provider", "provider-path", "provider-hash", "provider-bytes",
        "executable-hash", "maps-hash", "maps-shape", "maps-address", "maps-device", "maps-inode",
        "maps-version", "maps-deleted", "maps-permissions", "maps-missing-executable", "maps-extra-provider",
        "executable-path", "child-mismatch"])
    fun `unsupported builtin math custody should remain unknown with matching child fields`(failure: String) {
        val original = builtinArithmetic(true)
        val builtin = requireNotNull(original.builtinMath)
        val providers = builtin.providers
        val changedBuiltin = when (failure) {
            "origin" -> builtin.copy(origin = "frozen")
            "loader" -> builtin.copy(loader = "unidentified")
            "missing-provider" -> builtin.copy(providers = providers.drop(1))
            "duplicate-provider" -> builtin.copy(providers = providers + providers.first())
            "provider-path" -> builtin.copy(providers = providers.map { it.copy(path = "/other/provider") })
            "provider-hash" -> builtin.copy(providers = providers.map { it.copy(sha256 = "unhashed") })
            "provider-bytes" -> builtin.copy(providers = providers.map { it.copy(bytes = 67_108_865) })
            "executable-hash" -> builtin.copy(providers = providers.map { it.copy(sha256 = "0".repeat(64)) })
            "maps-hash" -> builtin.copy(mapsSha256 = "0".repeat(64))
            "executable-path" -> builtin.copy(executablePath = "relative")
            else -> builtin
        }
        val maps = when (failure) {
            "maps-shape" -> "malformed\n"
            "maps-address" -> builtin.maps.replace("1000-2000", "2000-1000")
            "maps-device" -> builtin.maps.replace("08:01", "device")
            "maps-inode" -> builtin.maps.replace(" 123 ", " 0 ")
            "maps-version" -> builtin.maps.replace("libpython3.13", "libpython3.12")
            "maps-deleted" -> builtin.maps.replace("python3.13\n", "python3.13 (deleted)\n")
            "maps-permissions" -> builtin.maps.replace("r-xp", "r--p")
            "maps-missing-executable" -> builtin.maps.lineSequence().drop(1).joinToString("\n")
            "maps-extra-provider" -> builtin.maps + "5000-6000 r-xp 00000000 08:01 125 /lib/libpython3.so\n"
            else -> changedBuiltin.maps
        }
        val math = if (maps != changedBuiltin.maps) {
            changedBuiltin.copy(maps = maps, mapsSha256 = sha256(maps.toByteArray()))
        } else {
            changedBuiltin
        }
        val modules = when (failure) {
            "missing-fractions" -> original.modules - "fractions"
            "missing-numbers" -> original.modules - "numbers"
            "ambiguous-math" -> arithmetic.modules
            else -> original.modules
        }
        val changed = original.copy(modules = modules, builtinMath = math)
        val bound = if (failure == "child-mismatch") {
            boundReceipt(original).copy(groundArithmetic = original.copy(builtinMath = builtin.copy(loader = "other")))
        } else {
            boundReceipt(changed)
        }

        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(bound), record, bindings, tools)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }

    @Test
    fun `a bound ground receipt should retain the literal witness and separate checker timings`() {
        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(receipt), record, bindings, tools)

        assertEquals("valid", result.validation.status)
        assertEquals(record.finalWitness, result.validation.checkedWitness)
        assertEquals("125", result.stats["sourceValidationSetupMs"])
        assertEquals("4", result.stats["sourceValidationCheckMs"])
        assertEquals("false", result.stats["sourceValidationProofChecked"])
    }

    @Test
    fun `ordinary case JSON should retain the exact producer record receipt witness and source custody`() {
        val original = Reports.json.encodeToString(record)
        val receiptJson = Reports.json.encodeToString(receipt)
        val checked = record.sourceChecked(SourceValidation("valid", "checked", checkedWitness = record.finalWitness),
            approximation = false, witnessOnly = true)
        val saved = checked.copy(stats = checked.stats + mapOf(
            "sourceValidationProducerRecordJson" to original,
            "sourceValidationRecordSha256" to sha256(original.toByteArray()),
            "sourceValidationReceiptJson" to receiptJson,
            "sourceValidationReceiptSha256" to sha256(receiptJson.toByteArray()),
        ))

        val decoded = Reports.json.decodeFromString<SolveRecord>(Reports.json.encodeToString(saved))
        val producer = decoded.stats.getValue("sourceValidationProducerRecordJson")
        val retainedReceipt = decoded.stats.getValue("sourceValidationReceiptJson")

        assertEquals(record, Reports.json.decodeFromString<SolveRecord>(producer))
        assertEquals(receipt, Reports.json.decodeFromString<MpsSourceValidation.Receipt>(retainedReceipt))
        assertEquals(sha256(producer.toByteArray()), decoded.stats["sourceValidationRecordSha256"])
        assertEquals(sha256(retainedReceipt.toByteArray()), decoded.stats["sourceValidationReceiptSha256"])
        assertEquals(record.finalWitness, decoded.finalWitness)
        assertEquals(record.finalWitness, decoded.checkedWitness)
        assertEquals(record.sourceHashes, decoded.sourceHashes)
        assertEquals(record.sourceOutputSha256, decoded.sourceOutputSha256)
        assertEquals("true", decoded.stats["reportedFeasible"])
        assertEquals("true", decoded.stats["reportedProven"])
        assertEquals("1.0", decoded.stats["reportedObjective"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["process", "malformed", "source", "record", "tools", "witness", "queries", "proof",
        "cleanup", "scope", "status", "runtime", "negative-time", "missing-runtime", "wheel", "runtime-hash",
        "platform", "package", "files", "missing-bootstrap", "missing-check", "missing-logs", "bootstrap-exit",
        "check-timeout", "stream-hash", "stream-truncated", "log-changed", "child-bindings", "memory", "command",
        "wall-time", "process-time", "missing-arithmetic", "arithmetic-authority", "arithmetic-python",
        "arithmetic-runtime-hash", "arithmetic-modules", "arithmetic-module-hash", "arithmetic-module-path"])
    fun `incomplete or changed checker custody should remain unknown`(failure: String) {
        val changed = when (failure) {
            "source" -> receipt.copy(bindings = bindings + ("source.mps" to "changed"))
            "record" -> receipt.copy(bindings = bindings + ("record.json" to "changed"))
            "tools" -> receipt.copy(toolHashes = tools + ("checker" to "changed"))
            "witness" -> receipt.copy(checkedWitness = "v x=2")
            "queries" -> receipt.copy(solverSearchQueries = 1)
            "proof" -> receipt.copy(proofCertificateChecked = true)
            "cleanup" -> receipt.copy(cleanupComplete = false)
            "scope" -> receipt.copy(scope = "proof")
            "status" -> receipt.copy(status = "accepted")
            "runtime" -> receipt.copy(checker = "unversioned")
            "negative-time" -> receipt.copy(checkMs = -1)
            "missing-runtime" -> receipt.copy(runtime = null)
            "wheel" -> receipt.copy(runtime = runtime.copy(wheelSha256 = "0".repeat(64)))
            "runtime-hash" -> receipt.copy(runtime = runtime.copy(pythonExecutableSha256 = "unhashed"))
            "platform" -> receipt.copy(runtime = runtime.copy(machine = "aarch64"))
            "package" -> receipt.copy(runtime = runtime.copy(packageVersion = "unversioned"))
            "files" -> receipt.copy(runtime = runtime.copy(extractedFiles = emptyList()))
            "missing-bootstrap" -> receipt.copy(bootstrapProcess = null)
            "missing-check" -> receipt.copy(checkProcess = null)
            "missing-logs" -> receipt.copy(processLogs = null)
            "bootstrap-exit" -> receipt.copy(bootstrapProcess = bootstrap.copy(exit = 1))
            "check-timeout" -> receipt.copy(checkProcess = receipt.checkProcess?.copy(timedOut = true))
            "stream-hash" -> receipt.copy(checkProcess = check.copy(streams = check.streams +
                ("stdout" to childStream.copy(sha256 = "0".repeat(64)))))
            "stream-truncated" -> receipt.copy(checkProcess = check.copy(streams = check.streams +
                ("stdout" to childStream.copy(observedBytes = childStream.bytes + 1))))
            "log-changed" -> receipt.copy(processLogs = receipt.processLogs.orEmpty() + ("check.stdout" to child + "x"))
            "child-bindings" -> {
                val changedLog = child.replace("producer-record", "changed-record")
                val size = changedLog.toByteArray().size.toLong()
                receipt.copy(processLogs = receipt.processLogs.orEmpty() + ("check.stdout" to changedLog),
                    checkProcess = check.copy(streams = check.streams + ("stdout" to
                        MpsCheckerStream(size, size, true, sha256(changedLog.toByteArray())))))
            }
            "memory" -> receipt.copy(bootstrapProcess = bootstrap.copy(addressSpaceBytes = 1))
            "command" -> receipt.copy(bootstrapProcess = bootstrap.copy(command = listOf("arbitrary")))
            "wall-time" -> receipt.copy(bootstrapProcess = bootstrap.copy(processWallNs = 50_000_000_000))
            "process-time" -> receipt.copy(bootstrapProcess = bootstrap.copy(timeoutSeconds = 180))
            "missing-arithmetic" -> receipt.copy(groundArithmetic = null)
            "arithmetic-authority" -> receipt.copy(groundArithmetic = arithmetic.copy(implementation = "z3"))
            "arithmetic-python" -> receipt.copy(groundArithmetic = arithmetic.copy(pythonVersion = "different"))
            "arithmetic-runtime-hash" -> receipt.copy(groundArithmetic = arithmetic.copy(
                pythonExecutableSha256 = "0".repeat(64)))
            "arithmetic-modules" -> receipt.copy(groundArithmetic = arithmetic.copy(modules = emptyMap()))
            "arithmetic-module-hash" -> receipt.copy(groundArithmetic = arithmetic.copy(modules = arithmetic.modules +
                ("fractions" to arithmetic.modules.getValue("fractions").copy(sha256 = "unhashed"))))
            "arithmetic-module-path" -> receipt.copy(groundArithmetic = arithmetic.copy(modules = arithmetic.modules +
                ("fractions" to arithmetic.modules.getValue("fractions").copy(path = "relative"))))
            else -> receipt
        }
        val text = if (failure == "malformed") "{" else Reports.json.encodeToString(changed)

        val result = MpsSourceValidation.checkerResult(if (failure == "process") 1 else 0,
            text, record, bindings, tools)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid", "encoding", "incomplete", "wire-bytes", "decoded-bytes", "wire-hash",
        "decoded-hash", "envelope-bytes", "envelope-hash", "decoder-python", "decoder-executable",
        "decoder-modules", "decoder-module-hash", "decoder-module-path", "decoder-module-size", "zlib-version",
        "zlib-runtime-version", "maps-hash", "maps-shape", "maps-provider", "native-provider-hash",
        "native-provider-size", "native-provider-path", "child-mismatch", "missing-transport", "legacy-policy",
        "missing-wire-binding", "unexpected-legacy-transport"])
    fun `compressed receipts should require complete bound transport and decoder custody`(failure: String) {
        val maps = "1000-2000 r-xp 00000000 08:01 123 /usr/lib/libz.so.1\n"
        val decoder = MpsGzipRuntime(runtime.pythonVersion, runtime.pythonExecutableSha256,
            mapOf("gzip" to MpsCheckerFile("/stdlib/gzip.py", 100, "a".repeat(64)),
                "zlib" to MpsCheckerFile("/stdlib/zlib.so", 100, "b".repeat(64)),
                "_compression" to MpsCheckerFile("/stdlib/_compression.py", 100, "c".repeat(64))),
            "1.2.13", "1.2.13", maps, sha256(maps.toByteArray()),
            listOf(MpsCheckerFile("/usr/lib/libz.so.1", 100, "d".repeat(64))))
        val transport = MpsStdoutTransport("gzip", 128, 9_000_000, "e".repeat(64), "f".repeat(64),
            32, "a".repeat(64), true, decoder)
        val changedDecoder = when (failure) {
            "decoder-python" -> decoder.copy(pythonVersion = "3.12.0")
            "decoder-executable" -> decoder.copy(pythonExecutableSha256 = "0".repeat(64))
            "decoder-modules" -> decoder.copy(modules = decoder.modules - "zlib")
            "decoder-module-hash" -> decoder.copy(modules = decoder.modules +
                ("gzip" to decoder.modules.getValue("gzip").copy(sha256 = "missing")))
            "decoder-module-path" -> decoder.copy(modules = decoder.modules +
                ("gzip" to decoder.modules.getValue("gzip").copy(path = "relative")))
            "decoder-module-size" -> decoder.copy(modules = decoder.modules +
                ("gzip" to decoder.modules.getValue("gzip").copy(bytes = 4_194_305)))
            "zlib-version" -> decoder.copy(zlibVersion = "unknown")
            "zlib-runtime-version" -> decoder.copy(zlibRuntimeVersion = "unknown")
            "maps-hash" -> decoder.copy(mapsSha256 = "0".repeat(64))
            "maps-shape" -> decoder.copy(maps = "malformed\n", mapsSha256 = sha256("malformed\n".toByteArray()))
            "maps-provider" -> decoder.copy(nativeProviders = emptyList())
            "native-provider-hash" -> decoder.copy(nativeProviders = listOf(decoder.nativeProviders.single()
                .copy(sha256 = "unknown")))
            "native-provider-size" -> decoder.copy(nativeProviders = listOf(decoder.nativeProviders.single()
                .copy(bytes = 67_108_865)))
            "native-provider-path" -> decoder.copy(nativeProviders = listOf(decoder.nativeProviders.single()
                .copy(path = "/other/libz.so.1")))
            else -> decoder
        }
        val changedTransport = when (failure) {
            "encoding" -> transport.copy(encoding = "plain")
            "incomplete" -> transport.copy(complete = false)
            "wire-bytes" -> transport.copy(wireBytes = 129)
            "decoded-bytes" -> transport.copy(decodedBytes = 8_999_999)
            "wire-hash" -> transport.copy(wireSha256 = "0".repeat(64))
            "decoded-hash" -> transport.copy(decodedSha256 = "0".repeat(64))
            "envelope-bytes" -> transport.copy(envelopeBytes = 8_388_609)
            "envelope-hash" -> transport.copy(envelopeSha256 = "missing")
            else -> transport.copy(decoderRuntime = changedDecoder)
        }
        val gzipBindings = bindings - "stdout.txt" + ("stdout.txt.gz" to transport.wireSha256)
        val value = base.copy(bindings = gzipBindings, stdoutTransport = changedTransport)
        val fields = childFields + "stdoutTransport"
        val log = JsonObject(Reports.json.parseToJsonElement(Reports.json.encodeToString(value)).jsonObject
            .filterKeys { it in fields }).toString() + "\n"
        val bytes = log.toByteArray()
        val bound = value.copy(checkProcess = check.copy(streams = check.streams + ("stdout" to
            MpsCheckerStream(bytes.size.toLong(), bytes.size.toLong(), true, sha256(bytes)))),
            processLogs = mapOf("bootstrap.stdout" to "", "bootstrap.stderr" to "", "check.stdout" to log,
                "check.stderr" to ""),
            stdoutTransport = when (failure) {
                "missing-transport" -> null
                "child-mismatch" -> transport.copy(envelopeBytes = 33)
                else -> changedTransport
            })
        val producer = record.copy(validationPolicy = if (failure == "legacy-policy") MPS_SOURCE_POLICY else
            MPS_GZIP_SOURCE_POLICY, sourceOutputSha256 = transport.decodedSha256)
        val expectedBindings = if (failure == "missing-wire-binding") bindings else gzipBindings
        val json = Reports.json.encodeToString(bound.copy(bindings = expectedBindings))

        val result = MpsSourceValidation.checkerResult(0, json, producer, expectedBindings, tools,
            gzip = MpsGzipExpectation(128, 9_000_000).takeUnless { failure == "unexpected-legacy-transport" })

        assertEquals(if (failure == "valid") "valid" else "unknown", result.validation.status)
        assertEquals(record.finalWitness.takeIf { failure == "valid" }, result.validation.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid", "missing-builtin", "ambiguous-file", "origin", "loader", "provider-hash",
        "provider-missing", "provider-path", "maps-hash", "maps-version", "maps-shape", "python-version",
        "child-mismatch"])
    fun `built in zlib should require the executing interpreter provider ledger`(failure: String) {
        val builtin = requireNotNull(builtinArithmetic(shared = true).builtinMath)
        val changedBuiltin = when (failure) {
            "origin" -> builtin.copy(origin = "frozen")
            "loader" -> builtin.copy(loader = "SourceFileLoader")
            "provider-hash" -> builtin.copy(providers = builtin.providers.map { it.copy(sha256 = "missing") })
            "provider-missing" -> builtin.copy(providers = builtin.providers.drop(1))
            "provider-path" -> builtin.copy(providers = builtin.providers.map { it.copy(path = "/other/provider") })
            "maps-hash" -> builtin.copy(mapsSha256 = "0".repeat(64))
            "maps-version" -> {
                val maps = builtin.maps.replace("libpython3.13", "libpython3.12")
                builtin.copy(maps = maps, mapsSha256 = sha256(maps.toByteArray()))
            }
            "maps-shape" -> builtin.copy(maps = "malformed\n", mapsSha256 = sha256("malformed\n".toByteArray()))
            else -> builtin
        }
        val modules = mapOf("gzip" to MpsCheckerFile("/stdlib/gzip.py", 100, "a".repeat(64)),
            "_compression" to MpsCheckerFile("/stdlib/_compression.py", 100, "b".repeat(64)))
        val decoder = MpsGzipRuntime(if (failure == "python-version") "3.12.0" else runtime.pythonVersion,
            runtime.pythonExecutableSha256, modules + if (failure == "ambiguous-file") {
                mapOf("zlib" to MpsCheckerFile("/stdlib/zlib.so", 100, "c".repeat(64)))
            } else {
                emptyMap()
            }, "1.2.13", "1.2.13", builtin.maps, builtin.mapsSha256, emptyList(),
            changedBuiltin.takeUnless { failure == "missing-builtin" })
        val transport = MpsStdoutTransport("gzip", 128, 9_000_000, "e".repeat(64), "f".repeat(64),
            32, "a".repeat(64), true, decoder)
        val gzipBindings = bindings - "stdout.txt" + ("stdout.txt.gz" to transport.wireSha256)
        val value = base.copy(bindings = gzipBindings, stdoutTransport = transport)
        val log = JsonObject(Reports.json.parseToJsonElement(Reports.json.encodeToString(value)).jsonObject
            .filterKeys { it in childFields + "stdoutTransport" }).toString() + "\n"
        val bytes = log.toByteArray()
        val bound = value.copy(checkProcess = check.copy(streams = check.streams + ("stdout" to
            MpsCheckerStream(bytes.size.toLong(), bytes.size.toLong(), true, sha256(bytes)))),
            processLogs = mapOf("bootstrap.stdout" to "", "bootstrap.stderr" to "", "check.stdout" to log,
                "check.stderr" to ""), stdoutTransport = if (failure == "child-mismatch") {
                transport.copy(decoderRuntime = decoder.copy(zlibVersion = "1.3.1"))
            } else {
                transport
            })
        val producer = record.copy(validationPolicy = MPS_GZIP_SOURCE_POLICY,
            sourceOutputSha256 = transport.decodedSha256)

        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(bound), producer,
            gzipBindings, tools, gzip = MpsGzipExpectation(128, 9_000_000))

        assertEquals(if (failure == "valid") "valid" else "unknown", result.validation.status)
        assertEquals(record.finalWitness.takeIf { failure == "valid" }, result.validation.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid-linux-raw", "valid-darwin-raw", "valid-linux-gzip", "valid-darwin-gzip",
        "platform", "image-hash", "image-size", "symbol", "module", "bootstrap-child", "bootstrap-command",
        "check-command", "memory", "child", "query", "proof", "tolerance", "decoder", "decoder-hash",
        "decoder-path", "wire", "decoded", "native-borrow"])
    fun `stdlib receipts should bind interpreter processes and complete portable transport`(variant: String) {
        val gzip = !variant.endsWith("-raw")
        val platform = if (variant.contains("linux")) "linux" else "darwin"
        val machine = if (platform == "linux") "x86_64" else "arm64"
        val portable = MpsStdlibRuntime("cpython-stdlib-ground-v1", "3.14.8", platform, machine,
            MpsCheckerFile(runtime.pythonExecutable, 100, runtime.pythonExecutableSha256),
            MpsCheckerFile("/runtime/Python", 100, "b".repeat(64)), "Py_GetVersion",
            mapOf("ctypes" to MpsCheckerFile("/stdlib/ctypes.py", 100, "c".repeat(64)),
                "_ctypes" to MpsCheckerFile("/stdlib/_ctypes.so", 100, "d".repeat(64))), false)
        val changed = when (variant) {
            "platform" -> portable.copy(platform = "unknown")
            "image-hash" -> portable.copy(interpreterImage = portable.interpreterImage.copy(sha256 = "missing"))
            "image-size" -> portable.copy(interpreterImage = portable.interpreterImage.copy(bytes = 67_108_865))
            "symbol" -> portable.copy(interpreterSymbol = "other")
            "module" -> portable.copy(modules = portable.modules - "_ctypes")
            else -> portable
        }
        val decoderFile = MpsCheckerFile("/owned/case/tools/mps_stdout_deflate.py", 100, "e".repeat(64))
        val decoder = MpsGzipRuntime(changed.pythonVersion, changed.executable.sha256,
            mapOf("mps_stdout_deflate" to decoderFile), "", "", "", sha256(byteArrayOf()), emptyList(),
            implementation = "python-deflate-gzip-v1")
        val changedDecoder = when (variant) {
            "decoder" -> decoder.copy(implementation = "python-zlib-gzip-v1")
            "decoder-hash" -> decoder.copy(modules = mapOf("mps_stdout_deflate" to
                decoderFile.copy(sha256 = "f".repeat(64))))
            "decoder-path" -> decoder.copy(modules = mapOf("mps_stdout_deflate" to
                decoderFile.copy(path = "/other/decoder.py")))
            "native-borrow" -> decoder.copy(nativeProviders = listOf(decoderFile))
            else -> decoder
        }
        val transport = MpsStdoutTransport("gzip", if (variant == "wire") 129 else 128,
            if (variant == "decoded") 9_000_001 else 9_000_000, "e".repeat(64), "f".repeat(64),
            32, "a".repeat(64), true, changedDecoder)
        val expectedBindings = if (gzip) {
            bindings - "stdout.txt" + ("stdout.txt.gz" to transport.wireSha256)
        } else bindings
        val producer = record.copy(validationPolicy = if (gzip) MPS_GZIP_SOURCE_POLICY else MPS_SOURCE_POLICY,
            sourceOutputSha256 = transport.decodedSha256)
        val value = base.copy(runtime = null, stdlibRuntime = changed, bindings = expectedBindings,
            toolHashes = tools + ("mps_stdout_deflate.py" to decoderFile.sha256),
            groundArithmetic = arithmetic.copy(pythonVersion = changed.pythonVersion),
            stdoutTransport = transport.takeIf { gzip },
            solverSearchQueries = if (variant == "query") 1 else 0,
            proofCertificateChecked = variant == "proof",
            sourceTolerance = if (variant == "tolerance") {
                mapOf("absolute" to "1", "relative" to "1")
            } else base.sourceTolerance)
        val fields = childFields + "stdlibRuntime" + if (gzip) setOf("stdoutTransport") else emptySet()
        val log = JsonObject(Reports.json.parseToJsonElement(Reports.json.encodeToString(value)).jsonObject
            .filterKeys { it in fields }).toString() + "\n"
        val setupValue = if (variant == "bootstrap-child") portable.copy(machine = "x86_64") else changed
        val setupLog = Reports.json.encodeToString(setupValue) + "\n"
        val setupBytes = setupLog.toByteArray()
        val bytes = log.toByteArray()
        val setup = bootstrap.copy(command = bootstrap.command.toMutableList().apply {
            this[2] = "/owned/case/tools/mps_witness_validation.py"
            if (variant == "bootstrap-command") this[2] = "/other/bootstrap.py"
        }, streams = bootstrap.streams + ("stdout" to MpsCheckerStream(
            setupBytes.size.toLong(), setupBytes.size.toLong(), true, sha256(setupBytes))))
        val checked = check.copy(command = check.command.toMutableList().apply {
            if (variant == "check-command") this[2] = "/other/check.py"
        }, addressSpaceBytes = if (variant == "memory") 2_147_483_648 else 4_294_967_296,
            streams = check.streams + ("stdout" to MpsCheckerStream(
                bytes.size.toLong(), bytes.size.toLong(), true, sha256(bytes))))
        val reportedRuntime = if (variant == "child") changed.copy(machine = "x86_64") else changed
        val bound = value.copy(bootstrapProcess = setup, checkProcess = checked,
            processLogs = mapOf("bootstrap.stdout" to setupLog, "bootstrap.stderr" to "", "check.stdout" to log,
                "check.stderr" to ""), stdlibRuntime = reportedRuntime)
        val packaged = tools + ("mps_stdout_deflate.py" to decoderFile.sha256)

        val result = MpsSourceValidation.checkerResult(0, Reports.json.encodeToString(bound), producer,
            expectedBindings, packaged, gzip = MpsGzipExpectation(128, 9_000_000).takeIf { gzip })

        assertEquals(if (variant.startsWith("valid-")) "valid" else "unknown", result.validation.status)
        assertEquals(record.finalWitness.takeIf { variant.startsWith("valid-") }, result.validation.checkedWitness)
    }

}
