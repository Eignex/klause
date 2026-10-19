package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val SMT_SOURCE_POLICY = "smt-original-polynomial-claims-v3"
internal const val SMT_CHECKER = "smt-original-claims-v2"

internal object SmtSourceValidation {
    const val GROUND = "ground-original-smt-witness"
    const val REFUTATION = "independent-original-smt-refutation"
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_RESULT_BYTES = 4 * 1024 * 1024
    private val names = listOf(
        "smt_source_validation.py",
        "mps_witness_validation.py",
        "smt_witness_validation.py",
        "verify_nra_source_results.py",
        "verify_source_results.py",
        "requirements-source-checks.txt",
    )

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
        val referenceComparison: String? = null,
        val nativeRuntime: SmtNativeRuntime? = null,
        val solverQueryCountObserved: Boolean = false,
        val referenceLogic: String? = null,
        val sourceDerivation: SmtSourceDerivation? = null,
        val residentMemory: Map<String, SmtResidentMemory>? = null,
    )

    fun unknown(reason: String): SourceValidation = SourceValidation(
        "unknown",
        reason,
        scope = "original-smt-no-accepted-claim",
    )

    fun validate(ref: ProblemRef, record: SolveRecord, raw: String, evidenceRoot: File): Result {
        val scope = eligible(record) ?: return Result(unknown("no eligible original SMT claim to check"))
        val started = System.nanoTime()
        var directory: File? = null
        var captured: Map<String, String> = emptyMap()
        return runCatching {
            check(evidenceRoot.isDirectory || evidenceRoot.mkdirs())
            val out = Files.createTempDirectory(evidenceRoot.toPath(), "smt-source-check-").toFile()
            directory = out
            val source = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            check(source.size <= MAX_SOURCE_BYTES) { "original source exceeds checker limit" }
            check(record.sourceHashes == mapOf("model" to sha256(source))) { "original source hash changed" }
            val inputs = mapOf(
                "source.smt2" to source,
                "stdout.txt" to raw.toByteArray(),
                "record.json" to Reports.json.encodeToString(record).toByteArray(),
                "model.smt2" to record.finalWitness.orEmpty().toByteArray(),
                "request.json" to "{\"scope\":\"$scope\",\"queryMillis\":30000}".toByteArray(),
            )
            check(inputs.getValue("model.smt2").size <= 8 * 1024 * 1024) { "model exceeds checker limit" }
            check(inputs.getValue("stdout.txt").size <= 8 * 1024 * 1024) { "stdout exceeds checker limit" }
            check(inputs.getValue("record.json").size <= MAX_RESULT_BYTES) { "producer record exceeds checker limit" }
            captured = mapOf(
                "sourceValidationRecordSha256" to sha256(inputs.getValue("record.json")),
                "sourceValidationProducerRecordJson" to inputs.getValue("record.json").decodeToString(),
                "sourceValidationProducerStdout" to raw,
                "sourceValidationSourceIdentityJson" to Reports.json.encodeToString(
                    sourceIdentity(ref.source) + mapOf(
                    "problem" to ref.name,
                    "catalogRevision" to record.gitSha.orEmpty(),
                    "sourceSha256" to sha256(source),
                )
                ),
            )
            val bindings = inputs.mapValues { (_, bytes) -> sha256(bytes) }
            for ((name, bytes) in inputs) File(out, name).writeBytes(bytes)
            val tools = File(out, "tools").apply { check(mkdir()) }
            val hashes = names.associateWith { name ->
                val bytes = SmtSourceValidation::class.java.getResourceAsStream("/smt-witness-validation/$name")
                    .use { requireNotNull(it).readNBytes(1024 * 1024 + 1) }
                check(bytes.size <= 1024 * 1024) { "packaged checker exceeds limit" }
                File(tools, name).writeBytes(bytes)
                sha256(bytes)
            }
            File(out, "tools.json").writeText(Reports.json.encodeToString(hashes))
            val remainingMs = (if (scope == GROUND) 70_000 else 100_000) - (System.nanoTime() - started) / 1_000_000
            val exit = run(out, tools, bindings, hashes, remainingMs)
            val receiptFile = File(out, "result.json")
            val bytes = receiptFile.inputStream().use { it.readNBytes(MAX_RESULT_BYTES + 1) }
            check(bytes.size <= MAX_RESULT_BYTES) { "checker receipt exceeds limit" }
            val validation = checkerResult(
                exit,
                bytes.decodeToString(throwOnInvalidSequence = true),
                record,
                bindings,
                hashes,
            )
            check(
                inputs.all { (name, original) ->
                val retained = File(out, name).inputStream().use { it.readNBytes(original.size + 1) }
                sha256(retained) == sha256(original)
            }
            ) {
                "checker inputs changed"
            }
            check(
                names.all { name ->
                val retained = File(tools, name).inputStream().use { it.readNBytes(1024 * 1024 + 1) }
                sha256(retained) == hashes[name]
            }
            ) { "checker tools changed" }
            check(SolveEvidence.sourceHashes(ref) == record.sourceHashes) { "original source changed during checking" }
            Result(
                validation.validation.copy(sourceHashes = record.sourceHashes),
                captured + validation.stats + mapOf(
                "sourceValidationReceiptJson" to bytes.decodeToString(),
                "sourceValidationReceiptSha256" to sha256(bytes),
                "sourceValidationRecordSha256" to bindings.getValue("record.json"),
                "sourceValidationToolHashes" to Reports.json.encodeToString(hashes),
            )
            )
        }.getOrElse { failure ->
            Result(
                unknown("SMT source checking failed: ${failure.message.orEmpty().take(400)}"),
                captured + directory?.let { mapOf("sourceValidationDirectory" to it.absolutePath) }.orEmpty(),
            )
        }
    }

    private fun sourceIdentity(source: ProblemSource): Map<String, String> = when (source) {
        is ProblemSource.Vendored -> mapOf("kind" to "vendored", "path" to source.workspaceRelPath)

        is ProblemSource.External -> mapOf(
            "kind" to "external",
            "collection" to source.collection.id,
            "url" to source.collection.url,
            "path" to source.relPath,
        )

        is ProblemSource.ExternalIndexed -> mapOf(
            "kind" to "indexed",
            "collection" to source.collection.id,
            "url" to source.collection.url,
            "index" to source.index.toString(),
            "extension" to source.ext,
        )

        is ProblemSource.MpsFeasibility -> mapOf(
            "kind" to "mps-feasibility",
            "source" to Reports.json.encodeToString(sourceIdentity(source.source)),
        )

        is ProblemSource.InCode -> error("SMT checking requires original file bytes")
    }

    internal fun checkerResult(
        exit: Int?,
        json: String,
        record: SolveRecord,
        bindings: Map<String, String>,
        tools: Map<String, String>,
    ): Result = runCatching {
        check(exit == 0) { "checker process incomplete" }
        val receipt = Reports.json.decodeFromString<Receipt>(json)
        val scope = requireNotNull(eligible(record))
        check(bindings.keys == setOf("source.smt2", "model.smt2", "stdout.txt", "record.json", "request.json"))
        check(tools.keys == names.toSet() && (bindings.values + tools.values).all { Regex("[0-9a-f]{64}").matches(it) })
        check(
            bindings["source.smt2"] == record.sourceHashes["model"] &&
                bindings["stdout.txt"] == record.sourceOutputSha256,
        )
        check(bindings["model.smt2"] == sha256(record.finalWitness.orEmpty().toByteArray()))
        check(bindings["record.json"] == sha256(Reports.json.encodeToString(record).toByteArray()))
        check(bindings["request.json"] == sha256("{\"scope\":\"$scope\",\"queryMillis\":30000}".toByteArray()))
        check(receipt.status in setOf("valid", "invalid", "unknown") && receipt.scope == scope)
        check(receipt.bindings == bindings && receipt.toolHashes == tools)
        check(!receipt.proofCertificateChecked && receipt.cleanupComplete)
        check(receipt.solverSearchQueries in 0..(if (scope == GROUND) 0 else 1))
        check(receipt.setupMs >= 0 && receipt.checkMs >= 0)
        val complete = runCatching {
            SmtSourceReceipt.requireComplete(receipt, Reports.json.parseToJsonElement(json).jsonObject)
        }.isSuccess
        check(receipt.status == "unknown" || complete)
        if (receipt.status == "valid") {
            check(receipt.checker == SMT_CHECKER)
            if (scope == GROUND) {
                check(
                    receipt.checkedWitness == record.finalWitness && receipt.referenceComparison == null &&
                        receipt.referenceLogic == null && receipt.sourceDerivation == null,
                )
            } else if (receipt.sourceDerivation != null) {
                check(
                    receipt.checkedWitness == null && receipt.referenceComparison == null &&
                        receipt.referenceLogic == null && receipt.solverSearchQueries == 0,
                )
            } else {
                check(
                    receipt.checkedWitness == null && receipt.referenceComparison == "unsat" &&
                        receipt.solverSearchQueries == 1 && receipt.referenceLogic in setOf("QF_NRA", "SMT"),
                )
            }
        }
        Result(
            SourceValidation(
                receipt.status,
                receipt.reason,
                scope = scope,
                checkedWitness = receipt.checkedWitness.takeIf { receipt.status == "valid" },
                checker = receipt.checker,
            ),
            mapOf(
                "sourceValidationSetupMs" to receipt.setupMs.toString(),
                "sourceValidationCheckMs" to receipt.checkMs.toString(),
                "sourceValidationSolverQueries" to if (complete) receipt.solverSearchQueries.toString() else "unknown",
                "sourceValidationSolverQueryLimit" to if (scope == GROUND) "0" else "1",
                "sourceValidationReferenceLogic" to receipt.referenceLogic.orEmpty(),
                "sourceValidationDerivationChecked" to
                    (receipt.status == "valid" && scope == REFUTATION && receipt.sourceDerivation != null).toString(),
                "sourceValidationProofChecked" to "false",
                "independentSourceRefutation" to (receipt.status == "valid" && scope == REFUTATION).toString(),
            ),
        )
    }.getOrElse { Result(unknown("incomplete or unbound SMT checker receipt")) }

    internal fun eligible(record: SolveRecord): String? {
        if (record.kind != "satisfy" || record.objective != null || record.exactObjective != null ||
            record.stats["exit"] != "0" || record.command.isEmpty() ||
            listOf("killed", "unsupported", "quarantined").any { it in record.stats }
        ) {
            return null
        }
        return when {
            record.feasible == true && !record.finalWitness.isNullOrEmpty() -> GROUND
            record.feasible == false && record.proven -> REFUTATION
            else -> null
        }
    }

    internal fun apply(record: SolveRecord, result: Result): SolveRecord {
        val scope = eligible(record)
        val validation = result.validation
        val accepted = validation.status == "valid" && validation.scope == scope && when (scope) {
            GROUND -> validation.checkedWitness == record.finalWitness

            REFUTATION ->
                validation.checkedWitness == null &&
                result.stats["independentSourceRefutation"] == "true" &&
                ((result.stats["sourceValidationSolverQueries"] == "1" &&
                    result.stats["sourceValidationDerivationChecked"] != "true") ||
                    (result.stats["sourceValidationSolverQueries"] == "0" &&
                        result.stats["sourceValidationDerivationChecked"] == "true" &&
                        result.stats["sourceValidationReferenceLogic"].isNullOrEmpty()))

            else -> false
        }
        val checked = if (accepted) {
            validation
        } else {
            validation.copy(
                status =
            if (validation.status == "invalid") "invalid" else "unknown",
                checkedWitness = null
            )
        }
        return record.sourceChecked(
            checked,
            approximation = false,
            witnessOnly = !(accepted && scope == REFUTATION),
        ).let {
            it.copy(
                stats = it.stats + result.stats + mapOf(
                "independentSourceRefutation" to (accepted && scope == REFUTATION).toString(),
                "sourceValidationDerivationChecked" to (accepted && scope == REFUTATION &&
                    result.stats["sourceValidationDerivationChecked"] == "true").toString(),
                "sourceValidationProofChecked" to "false",
            )
            )
        }
    }

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
                ProcessBuilder(
                    "python3",
                    "-I",
                    File(tools, "smt_source_validation.py").absolutePath,
                    "supervise",
                    out.absolutePath,
                    Reports.json.encodeToString(bindings),
                    Reports.json.encodeToString(hashes),
                    ProcessHandle.current().pid().toString(),
                )
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
