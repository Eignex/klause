package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class MpsStdlibRuntime(
    val implementation: String,
    val pythonVersion: String,
    val platform: String,
    val machine: String,
    val executable: MpsCheckerFile,
    val interpreterImage: MpsCheckerFile,
    val interpreterSymbol: String,
    val modules: Map<String, MpsCheckerFile>,
    val globalInstallationPerformed: Boolean,
)

internal object MpsStdlibReceipt {
    private val digest = Regex("[0-9a-f]{64}")

    fun requireComplete(
        receipt: MpsSourceValidation.Receipt,
        json: JsonObject,
        record: SolveRecord,
        bindings: Map<String, String>,
        tools: Map<String, String>,
        exact: Boolean,
        gzip: MpsGzipExpectation?,
    ) {
        require(receipt.runtime == null)
        val runtime = requireNotNull(receipt.stdlibRuntime)
        require(runtime.implementation == "cpython-stdlib-ground-v1" && !runtime.globalInstallationPerformed)
        require(runtime.platform in setOf("linux", "darwin"))
        require(runtime.machine in if (runtime.platform == "darwin") setOf("x86_64", "arm64")
            else setOf("x86_64", "aarch64"))
        val version = runtime.pythonVersion.split('.').map { it.toInt() }
        require(version.size == 3 && version[0] == 3 && version[1] >= 9)
        require(runtime.interpreterSymbol in setOf("Py_GetVersion", "_Py_GetVersion"))
        for (file in listOf(runtime.executable, runtime.interpreterImage)) requireFile(file, 67_108_864L)
        require(runtime.modules.keys == setOf("ctypes", "_ctypes"))
        runtime.modules.values.forEach { requireFile(it, 4_194_304L) }
        require(runtime.executable.bytes + runtime.interpreterImage.bytes +
            runtime.modules.values.sumOf { it.bytes } <= 201_326_592L)
        val arithmetic = requireNotNull(receipt.groundArithmetic)
        require(arithmetic.implementation == MPS_GROUND_CHECKER)
        require(arithmetic.pythonVersion == runtime.pythonVersion &&
            arithmetic.pythonExecutableSha256 == runtime.executable.sha256)
        val moduleNames = if (arithmetic.builtinMath == null) setOf("fractions", "numbers", "math")
        else setOf("fractions", "numbers")
        require(arithmetic.modules.keys == moduleNames)
        arithmetic.modules.values.forEach { requireFile(it, 4_194_304L) }
        arithmetic.builtinMath?.let {
            require(runtime.platform == "linux")
            MpsSourceReceipt.requireBuiltinProvider(it, runtime.pythonVersion, runtime.executable.sha256)
        }
        val bootstrap = requireNotNull(receipt.bootstrapProcess)
        val check = requireNotNull(receipt.checkProcess)
        val logs = requireNotNull(receipt.processLogs)
        require(logs.keys == setOf("bootstrap.stdout", "bootstrap.stderr", "check.stdout", "check.stderr"))
        MpsSourceReceipt.requireProcess(bootstrap, logs, "bootstrap", "mps_witness_validation.py",
            runtime.executable.path, 2_147_483_648L)
        MpsSourceReceipt.requireProcess(check, logs, "check", "mps_witness_validation.py",
            runtime.executable.path, 4_294_967_296L)
        require(bootstrap.cwd == check.cwd && bootstrap.command.takeLast(2) == check.command.takeLast(2))
        require(Reports.json.parseToJsonElement(logs.getValue("bootstrap.stdout")) ==
            Reports.json.parseToJsonElement(Reports.json.encodeToString(runtime)))
        require(receipt.setupMs >= bootstrap.processWallNs / 1_000_000 && receipt.setupMs <= 40_000)
        require(receipt.checkMs >= check.processWallNs / 1_000_000 && receipt.checkMs <= 40_000)
        require(receipt.setupMs + receipt.checkMs <= 70_000)
        val child = Reports.json.parseToJsonElement(logs.getValue("check.stdout")).jsonObject
        val fields = setOf("status", "reason", "scope", "bindings", "solverSearchQueries",
            "proofCertificateChecked", "checkedWitness", "checker", "groundArithmetic", "stdlibRuntime") +
            (if (gzip != null) setOf("stdoutTransport") else emptySet()) +
            if (receipt.status == "valid") setOf("exactFeasible", "toleranceAccepted", "sourceTolerance", "witnessCheck")
            else emptySet()
        require(child.keys == fields && fields.all { child[it] == json[it] })
        if (receipt.status == "valid") {
            val exactFeasible = requireNotNull(receipt.exactFeasible)
            require(receipt.toleranceAccepted == (!exact && !exactFeasible) && (!exact || exactFeasible))
            val tolerance = if (exact) "0" else "1/10000000"
            require(receipt.sourceTolerance == mapOf("absolute" to tolerance, "relative" to tolerance))
            require(receipt.witnessCheck ==
                "all original MPS rows bounds integrality and objective evaluated at supplied point")
        }
        gzip?.let {
            requireTransport(requireNotNull(receipt.stdoutTransport), it, record, bindings, runtime, tools,
                check.command[4])
        }
    }

    private fun requireFile(file: MpsCheckerFile, maximum: Long) {
        require(file.path.startsWith('/') && file.path.none { it == '\\' || it == '\u0000' })
        require(file.bytes in 1..maximum && digest.matches(file.sha256))
    }

    private fun requireTransport(
        transport: MpsStdoutTransport,
        expected: MpsGzipExpectation,
        record: SolveRecord,
        bindings: Map<String, String>,
        runtime: MpsStdlibRuntime,
        tools: Map<String, String>,
        directory: String,
    ) {
        require(record.validationPolicy == MPS_GZIP_SOURCE_POLICY)
        require(bindings.keys == setOf("source.mps", "stdout.txt.gz", "record.json", "request.json"))
        require(transport.encoding == "gzip" && transport.complete)
        require(transport.wireBytes == expected.wireBytes && transport.wireBytes in 1..8_388_608L)
        require(transport.decodedBytes == expected.decodedBytes && transport.decodedBytes in 0..67_108_864L)
        require(transport.wireSha256 == bindings["stdout.txt.gz"] && digest.matches(transport.wireSha256))
        require(transport.decodedSha256 == record.sourceOutputSha256 && digest.matches(transport.decodedSha256))
        require(transport.envelopeBytes in 1..8_388_608L && digest.matches(transport.envelopeSha256))
        val decoder = transport.decoderRuntime
        require(decoder.implementation == "python-deflate-gzip-v1")
        require(decoder.pythonVersion == runtime.pythonVersion &&
            decoder.pythonExecutableSha256 == runtime.executable.sha256)
        require(decoder.modules.keys == setOf("mps_stdout_deflate"))
        val module = decoder.modules.getValue("mps_stdout_deflate")
        requireFile(module, 4_194_304L)
        require(module.path == "$directory/tools/mps_stdout_deflate.py" &&
            module.sha256 == tools["mps_stdout_deflate.py"])
        require(decoder.zlibVersion.isEmpty() && decoder.zlibRuntimeVersion.isEmpty())
        require(decoder.maps.isEmpty() && decoder.mapsSha256 == sha256(byteArrayOf()))
        require(decoder.nativeProviders.isEmpty() && decoder.builtinZlib == null)
    }
}
