package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

internal const val MPS_SOURCE_POLICY = "mps-original-rational-ground-witness-v2"
internal const val MPS_GROUND_CHECKER = "python-fractions-mps-ground-v1"

internal object MpsSourceValidation {
    private const val SCOPE = "ground-original-mps-witness"
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_RESULT_BYTES = 4 * 1024 * 1024
    private val names = listOf("mps_witness_validation.py", "smt_witness_validation.py",
        "verify_nra_source_results.py", "verify_source_results.py", "requirements-source-checks.txt")

    internal data class Result(val validation: SourceValidation, val stats: Map<String, String> = emptyMap())

    @Serializable
    internal data class Receipt(
        val status: String,
        val reason: String,
        val scope: String,
        val bindings: Map<String, String>,
        val toolHashes: Map<String, String>,
        val solverSearchQueries: Int,
        val proofCertificateChecked: Boolean,
        val cleanupComplete: Boolean,
        val checkedWitness: String? = null,
        val checker: String? = null,
        val setupMs: Long = 0,
        val checkMs: Long = 0,
        val runtime: MpsCheckerRuntime? = null,
        val groundArithmetic: MpsGroundArithmetic? = null,
        val bootstrapProcess: MpsCheckerProcess? = null,
        val checkProcess: MpsCheckerProcess? = null,
        val processLogs: Map<String, String>? = null,
        val exactFeasible: Boolean? = null,
        val toleranceAccepted: Boolean? = null,
        val sourceTolerance: Map<String, String>? = null,
        val witnessCheck: String? = null,
    )

    fun unknown(reason: String): SourceValidation = SourceValidation("unknown", reason, scope = SCOPE)

    fun validate(
        ref: ProblemRef,
        record: SolveRecord,
        raw: String,
        exact: Boolean,
        evidenceRoot: File,
    ): Result {
        if (record.feasible != true || record.finalWitness == null) {
            return Result(unknown("no feasible original MPS witness to check"))
        }
        val started = System.nanoTime()
        var directory: File? = null
        var captured: Map<String, String> = emptyMap()
        return runCatching {
            check(evidenceRoot.isDirectory || evidenceRoot.mkdirs())
            val out = Files.createTempDirectory(evidenceRoot.toPath(), "mps-source-check-").toFile()
            directory = out
            val source = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            check(source.size <= MAX_SOURCE_BYTES) { "original source exceeds checker limit" }
            check(record.sourceHashes == mapOf("model" to sha256(source))) { "original source hash changed" }
            val inputs = mapOf(
                "source.mps" to source,
                "stdout.txt" to raw.toByteArray(),
                "record.json" to Reports.json.encodeToString(record).toByteArray(),
                "request.json" to "{\"exact\":$exact}".toByteArray(),
            )
            check(inputs.getValue("stdout.txt").size <= 8 * 1024 * 1024) { "stdout exceeds checker limit" }
            check(inputs.getValue("record.json").size <= MAX_RESULT_BYTES) { "producer record exceeds checker limit" }
            captured = mapOf(
                "sourceValidationRecordSha256" to sha256(inputs.getValue("record.json")),
                "sourceValidationProducerRecordJson" to inputs.getValue("record.json").decodeToString(),
                "sourceValidationSourceIdentityJson" to Reports.json.encodeToString(sourceIdentity(ref.source) + mapOf(
                    "problem" to ref.name, "catalogRevision" to record.gitSha.orEmpty(),
                    "sourceSha256" to sha256(source),
                )),
            )
            val bindings = inputs.mapValues { (_, bytes) -> sha256(bytes) }
            for ((name, bytes) in inputs) File(out, name).writeBytes(bytes)
            val tools = File(out, "tools").apply { check(mkdir()) }
            val hashes = names.associateWith { name ->
                val bytes = MpsSourceValidation::class.java.getResourceAsStream("/smt-witness-validation/$name")
                    .use { requireNotNull(it).readNBytes(1024 * 1024 + 1) }
                check(bytes.size <= 1024 * 1024) { "packaged checker exceeds limit" }
                File(tools, name).writeBytes(bytes)
                sha256(bytes)
            }
            File(out, "tools.json").writeText(Reports.json.encodeToString(hashes))
            val remainingMs = 70_000 - (System.nanoTime() - started) / 1_000_000
            val exit = run(out, tools, bindings, hashes, remainingMs)
            val receiptFile = File(out, "result.json")
            val bytes = receiptFile.inputStream().use { it.readNBytes(MAX_RESULT_BYTES + 1) }
            check(bytes.size <= MAX_RESULT_BYTES) { "checker receipt exceeds limit" }
            val validation = checkerResult(exit, bytes.decodeToString(throwOnInvalidSequence = true),
                record, bindings, hashes, exact)
            check(inputs.all { (name, bytes) -> sha256(File(out, name).readBytes()) == sha256(bytes) }) {
                "checker inputs changed"
            }
            check(names.all { sha256(File(tools, it).readBytes()) == hashes[it] }) { "checker tools changed" }
            check(SolveEvidence.sourceHashes(ref) == record.sourceHashes) { "original source changed during checking" }
            Result(validation.validation.copy(sourceHashes = record.sourceHashes), captured + validation.stats + mapOf(
                "sourceValidationReceiptJson" to bytes.decodeToString(),
                "sourceValidationReceiptSha256" to sha256(bytes),
                "sourceValidationRecordSha256" to bindings.getValue("record.json"),
                "sourceValidationToolHashes" to Reports.json.encodeToString(hashes),
            ))
        }.getOrElse { failure ->
            Result(unknown("MPS source checking failed: ${failure.message.orEmpty().take(400)}"),
                captured + directory?.let { mapOf("sourceValidationDirectory" to it.absolutePath) }.orEmpty())
        }
    }

    private fun sourceIdentity(source: ProblemSource): Map<String, String> = when (source) {
        is ProblemSource.Vendored -> mapOf("kind" to "vendored", "path" to source.workspaceRelPath)
        is ProblemSource.External -> mapOf("kind" to "external", "collection" to source.collection.id,
            "url" to source.collection.url, "path" to source.relPath)
        is ProblemSource.ExternalIndexed -> mapOf("kind" to "indexed", "collection" to source.collection.id,
            "url" to source.collection.url, "index" to source.index.toString(), "extension" to source.ext)
        is ProblemSource.MpsFeasibility -> mapOf("kind" to "mps-feasibility",
            "source" to Reports.json.encodeToString(sourceIdentity(source.source)))
        is ProblemSource.InCode -> error("MPS checking requires original file bytes")
    }

    internal fun checkerResult(
        exit: Int?,
        json: String,
        record: SolveRecord,
        bindings: Map<String, String>,
        tools: Map<String, String>,
        exact: Boolean = false,
    ): Result = runCatching {
        check(exit == 0) { "checker process incomplete" }
        val receipt = Reports.json.decodeFromString<Receipt>(json)
        check(receipt.status in setOf("valid", "invalid", "unknown") && receipt.scope == SCOPE)
        check(receipt.bindings == bindings && receipt.toolHashes == tools)
        check(receipt.solverSearchQueries == 0 && !receipt.proofCertificateChecked && receipt.cleanupComplete)
        check(receipt.setupMs >= 0 && receipt.checkMs >= 0)
        if (receipt.status != "unknown") {
            MpsSourceReceipt.requireComplete(receipt, Reports.json.parseToJsonElement(json).jsonObject, exact)
        }
        check(receipt.status != "valid" || (record.feasible == true && record.finalWitness != null &&
            receipt.checkedWitness == record.finalWitness && receipt.checker == MPS_GROUND_CHECKER))
        Result(SourceValidation(receipt.status, receipt.reason, scope = SCOPE,
            checkedWitness = receipt.checkedWitness.takeIf { receipt.status == "valid" }, checker = receipt.checker),
            mapOf("sourceValidationSetupMs" to receipt.setupMs.toString(),
                "sourceValidationCheckMs" to receipt.checkMs.toString(),
                "sourceValidationSolverQueries" to "0", "sourceValidationProofChecked" to "false"))
    }.getOrElse { Result(unknown("incomplete or unbound MPS checker receipt")) }

    private fun run(
        out: File,
        tools: File,
        bindings: Map<String, String>,
        hashes: Map<String, String>,
        timeoutMs: Long,
    ): Int? {
        check(timeoutMs > 0) { "checker preparation exhausted its allowance" }
        val gate = Any()
        var active: Process? = null
        val stopping = AtomicBoolean()
        val shutdown = Thread {
            stopping.set(true)
            synchronized(gate) { active?.let(::stop) }
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        return try {
            val process = synchronized(gate) {
                check(!stopping.get()) { "checker owner stopped" }
                ProcessBuilder("python3", "-I", File(tools, "mps_witness_validation.py").absolutePath,
                    "supervise", out.absolutePath, Reports.json.encodeToString(bindings),
                    Reports.json.encodeToString(hashes), ProcessHandle.current().pid().toString())
                    .redirectErrorStream(true).redirectOutput(File(out, "supervisor.log")).start()
                    .also { active = it }
            }
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && !stopping.get()) process.exitValue() else null
        } finally {
            synchronized(gate) {
                active?.let(::stop)
                active = null
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
    }

    private fun stop(process: Process) {
        if (process.isAlive) process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
