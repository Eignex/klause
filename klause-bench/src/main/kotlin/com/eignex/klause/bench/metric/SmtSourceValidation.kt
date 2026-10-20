package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal const val SMT_SOURCE_POLICY = "smt-external-original-claims-v2"

internal object SmtSourceValidation {
    const val GROUND = "ground-original-smt-witness"
    const val REFUTATION = "independent-original-smt-refutation"
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_MODEL_BYTES = 8 * 1024 * 1024
    private const val MAX_RECORD_BYTES = 4 * 1024 * 1024

    internal data class Result(val validation: SourceValidation, val stats: Map<String, String> = emptyMap())

    internal data class Tool(
        val executable: File,
        val binarySha256: String,
        val artifactUrl: String? = null,
        val artifactSha256: String? = null,
        val owned: Boolean = false,
    ) : AutoCloseable {
        fun verify() {
            require(executable.isAbsolute && executable.isFile && executable.canExecute()) {
                "checker executable is unavailable"
            }
            require(Regex("[0-9a-f]{64}").matches(binarySha256) && executable.length() <= 256 * 1024 * 1024) {
                "checker executable identity is unavailable"
            }
            require(sha256(executable.readBytes()) == binarySha256) {
                "checker executable hash differs"
            }
        }

        override fun close() {
            check(!owned || !executable.exists() || executable.delete()) { "external checker cleanup incomplete" }
        }
    }

    @Serializable
    internal data class Receipt(
        val policy: String,
        val checker: String,
        val scope: String,
        val bindings: Map<String, String>,
        val executablePath: String,
        val executableSha256: String,
        val process: SmtToolProcess,
        val status: String,
        val solverSearchQueries: Int?,
        val proofCertificateChecked: Boolean = false,
        val artifactUrl: String? = null,
        val artifactSha256: String? = null,
    )

    fun unknown(reason: String): SourceValidation = SourceValidation(
        "unknown", reason, scope = "original-smt-no-accepted-claim",
    )

    fun validate(ref: ProblemRef, record: SolveRecord, raw: String, evidenceRoot: File): Result =
        validateSaved(ref, record, raw, evidenceRoot, null)

    internal fun validateSaved(
        ref: ProblemRef,
        record: SolveRecord,
        raw: String,
        evidenceRoot: File,
        tool: Tool?,
        producerBytes: ByteArray? = null,
    ): Result {
        val scope = eligible(record) ?: return Result(unknown("no eligible original SMT claim to check"))
        val started = System.nanoTime()
        var directory: File? = null
        var captured: Map<String, String> = emptyMap()
        return runCatching {
            check(evidenceRoot.isDirectory || evidenceRoot.mkdirs())
            val out = Files.createTempDirectory(evidenceRoot.toPath(), "smt-source-check-").toFile()
            directory = out
            val source = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            val producer = producerBytes ?: Reports.json.encodeToString(record).toByteArray()
            check(producer.size <= MAX_RECORD_BYTES && raw.toByteArray().size <= MAX_MODEL_BYTES) {
                "producer output exceeds checker limit"
            }
            captured = mapOf(
                "sourceValidationProducerRecordJson" to producer.decodeToString(),
                "sourceValidationRecordSha256" to sha256(producer),
                "sourceValidationProducerStdout" to raw,
                "sourceValidationDirectory" to out.absolutePath,
            )
            val resolved = tool ?: SmtSourceTools.resolve(scope, out)
            val setupMs = (System.nanoTime() - started) / 1_000_000
            val result = resolved.use {
                validateOriginal(source, record, raw, out, resolved, producer)
            }
            check(SolveEvidence.sourceHashes(ref) == record.sourceHashes) { "original source changed during checking" }
            result.copy(stats = captured + result.stats + ("sourceValidationSetupMs" to setupMs.toString()))
        }.getOrElse { failure ->
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Result(unknown("SMT source checking incomplete: ${failure.message.orEmpty().take(400)}"),
                captured + directory?.let { mapOf("sourceValidationDirectory" to it.absolutePath) }.orEmpty())
        }
    }

    internal fun validateOriginal(
        source: ByteArray,
        record: SolveRecord,
        raw: String,
        out: File,
        tool: Tool,
        producerBytes: ByteArray = Reports.json.encodeToString(record).toByteArray(),
    ): Result {
        val scope = requireNotNull(eligible(record))
        require(source.size <= MAX_SOURCE_BYTES) { "original source exceeds checker limit" }
        require(record.sourceHashes == mapOf("model" to sha256(source))) { "original source hash differs" }
        require(record.sourceOutputSha256 == sha256(raw.toByteArray())) { "producer stdout hash differs" }
        require(producerBytes.size <= MAX_RECORD_BYTES) { "producer record exceeds checker limit" }
        require(Reports.json.decodeFromString<SolveRecord>(producerBytes.decodeToString()) == record) {
            "producer record bytes differ"
        }
        if (scope == GROUND) require(SolveEvidence.finalWitness(Format.SMTLIB, raw) == record.finalWitness) {
            "model differs from producer stdout"
        }
        val inputs = mapOf(
            "source.smt2" to source,
            "model.smt2" to record.finalWitness.orEmpty().toByteArray(),
            "response.rsmt2" to (if (scope == GROUND) "sat\n${record.finalWitness}\n" else "").toByteArray(),
            "stdout.txt" to raw.toByteArray(),
            "record.json" to producerBytes,
        )
        require(inputs.getValue("model.smt2").size <= MAX_MODEL_BYTES &&
            inputs.getValue("stdout.txt").size <= MAX_MODEL_BYTES &&
            inputs.getValue("record.json").size <= MAX_RECORD_BYTES) { "producer output exceeds checker limit" }
        check(out.isDirectory || out.mkdirs())
        for ((name, bytes) in inputs) File(out, name).writeBytes(bytes)
        tool.verify()
        val arguments = listOf(tool.executable.absolutePath) + if (scope == GROUND) {
            listOf("--time=30s", "--size=4G", "--strict=false", "--check-model=true",
                "--report-style=minimal", "--warn=-all", File(out, "source.smt2").absolutePath)
        } else {
            listOf("-smt2", "-T:30", "-memory:4096", File(out, "source.smt2").absolutePath)
        }
        val process = SmtSourceReceipt.run(arguments, File(out, "response.rsmt2"), out, 35_000)
        tool.verify()
        check(inputs.all { (name, bytes) -> File(out, name).readBytes().contentEquals(bytes) }) {
            "checker inputs changed"
        }
        val status = interpret(scope, process)
        val checker = if (scope == GROUND) "dolmen" else "z3-reference"
        val queries = if (scope == GROUND) 0 else if (process.exit == 0 && !process.timedOut &&
            process.cleanupComplete && process.stdout.trim() in setOf("sat", "unsat", "unknown")) 1 else null
        val receipt = Receipt(SMT_SOURCE_POLICY, checker, scope, inputs.mapValues { sha256(it.value) },
            tool.executable.absolutePath, tool.binarySha256, process, status, queries,
            artifactUrl = tool.artifactUrl, artifactSha256 = tool.artifactSha256)
        val json = Reports.json.encodeToString(receipt)
        File(out, "result.json").writeText(json)
        return Result(SourceValidation(status, when (status) {
            "valid" -> if (scope == GROUND) {
                "Dolmen accepted the original-source model"
            } else {
                "Z3 returned UNSAT on the original source"
            }
            "invalid" -> "Dolmen rejected the original-source model"
            else -> when {
                process.memory?.exceeded == true -> "external SMT checker exceeded its memory limit"
                process.memory?.qualified() != true -> "external SMT checker memory observation incomplete"
                else -> "external SMT checking incomplete or unsupported"
            }
        }, sourceHashes = record.sourceHashes, scope = scope,
            checkedWitness = record.finalWitness.takeIf { status == "valid" && scope == GROUND }, checker = checker),
            mapOf(
                "sourceValidationReceiptJson" to json,
                "sourceValidationReceiptSha256" to sha256(json.toByteArray()),
                "sourceValidationBinarySha256" to tool.binarySha256,
                "sourceValidationCheckMs" to process.elapsedMs.toString(),
                "sourceValidationSolverQueries" to (queries?.toString() ?: "unknown"),
                "sourceValidationSolverQueryLimit" to if (scope == REFUTATION) "1" else "0",
                "sourceValidationProofChecked" to "false",
                "sourceValidationDerivationChecked" to "false",
                "independentSourceRefutation" to (status == "valid" && scope == REFUTATION).toString(),
            ))
    }

    internal fun interpret(scope: String, process: SmtToolProcess): String {
        if (process.memory?.qualified() != true) return "unknown"
        if (process.exit != 0 || process.timedOut || !process.cleanupComplete) {
            return if (scope == GROUND && process.exit == 6 && !process.timedOut && process.cleanupComplete &&
                process.stderr.trim() == "E:bad-model") "invalid" else "unknown"
        }
        return when (scope) {
            GROUND -> if (process.stdout.isBlank() && process.stderr.isBlank()) "valid" else "unknown"
            REFUTATION -> if (process.stdout.trim() == "unsat" && process.stderr.isBlank()) "valid" else "unknown"
            else -> "unknown"
        }
    }

    internal fun eligible(record: SolveRecord): String? {
        if (record.kind != "satisfy" || record.objective != null || record.exactObjective != null ||
            record.stats["exit"] != "0" || record.command.isEmpty() ||
            listOf("killed", "unsupported", "quarantined").any { it in record.stats }
        ) return null
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
            REFUTATION -> validation.checkedWitness == null &&
                result.stats["independentSourceRefutation"] == "true" &&
                result.stats["sourceValidationSolverQueries"] == "1" &&
                result.stats["sourceValidationDerivationChecked"] != "true"
            else -> false
        }
        val checked = if (accepted) validation else validation.copy(
            status = if (validation.status == "invalid") "invalid" else "unknown", checkedWitness = null,
        )
        return record.sourceChecked(checked, approximation = false,
            witnessOnly = !(accepted && scope == REFUTATION)).let {
            it.copy(stats = it.stats + result.stats + mapOf(
                "independentSourceRefutation" to (accepted && scope == REFUTATION).toString(),
                "sourceValidationDerivationChecked" to "false", "sourceValidationProofChecked" to "false",
            ))
        }
    }
}
