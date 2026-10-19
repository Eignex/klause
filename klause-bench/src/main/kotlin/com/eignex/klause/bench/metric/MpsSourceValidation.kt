package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val MPS_SOURCE_POLICY = "mps-miplib2017-point-v1"
internal const val MPS_GZIP_SOURCE_POLICY = "mps-miplib2017-point-gzip-v1"
internal const val MPS_GROUND_CHECKER = "miplib2017-solchecker-1.0.4"

internal object MpsSourceValidation {
    private const val SCOPE = "ground-original-mps-witness"
    private const val ARCHIVE_SHA256 = "37b21a7f1ee968aed60675fb070c9286eccc6a953c8bc50120c9246b052b47b9"
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_RECORD_BYTES = 4 * 1024 * 1024

    internal data class Result(val validation: SourceValidation, val stats: Map<String, String> = emptyMap())

    @Serializable
    internal data class Receipt(
        val checker: String,
        val policy: String,
        val bindings: Map<String, String>,
        val binarySha256: String,
        val archiveSha256: String,
        val processes: List<MpsNativeProcess>,
        val status: String,
        val exactFeasible: Boolean?,
        val toleranceAccepted: Boolean?,
        val solverSearchQueries: Int = 0,
        val proofCertificateChecked: Boolean = false,
    )

    fun unknown(reason: String): SourceValidation = SourceValidation("unknown", reason, scope = SCOPE)

    fun validate(ref: ProblemRef, record: SolveRecord, raw: String, exact: Boolean, evidenceRoot: File): Result =
        validate(ref, record, raw, exact, evidenceRoot, gzip = false)

    fun validateGzip(ref: ProblemRef, record: SolveRecord, raw: String, exact: Boolean, evidenceRoot: File): Result =
        validate(ref, record, raw, exact, evidenceRoot, gzip = true)

    private fun validate(
        ref: ProblemRef,
        record: SolveRecord,
        raw: String,
        exact: Boolean,
        evidenceRoot: File,
        gzip: Boolean,
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
            fun remaining() = 70_000 - (System.nanoTime() - started) / 1_000_000
            fun active() { check(remaining() > 0 && !Thread.currentThread().isInterrupted) { "checker cancelled or timed out" } }
            active()
            val source = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            check(source.size <= MAX_SOURCE_BYTES) { "original source exceeds checker limit" }
            check(record.sourceHashes == mapOf("model" to sha256(source))) { "original source hash changed" }
            check(System.getProperty("os.name") == "Linux") { "MIPLIB invocation requires Linux resource controls" }
            val profile = MpsSolcheckerInput.qualify(source.decodeToString(throwOnInvalidSequence = true))
            check(profile.maximize == record.maximize) { "source objective sense differs" }
            val decoded = raw.toByteArray()
            check(record.sourceOutputSha256 == sha256(decoded)) { "producer stdout hash differs" }
            val policy = if (gzip) MPS_GZIP_SOURCE_POLICY else MPS_SOURCE_POLICY
            check(record.validationPolicy == policy) { "producer validation policy differs" }
            val wire = if (gzip) MpsStdoutGzip.encode(decoded) { remaining() <= 0 || Thread.currentThread().isInterrupted }
                else decoded.also { check(it.size <= MpsStdoutGzip.MAX_WIRE_BYTES) { "stdout exceeds checker limit" } }
            val producer = Reports.json.encodeToString(record).toByteArray()
            check(producer.size <= MAX_RECORD_BYTES) { "producer record exceeds checker limit" }
            val solution = MpsSolution.convert(record, raw, exact).toByteArray()
            val inputs = mapOf("source.mps" to source, (if (gzip) "stdout.txt.gz" else "stdout.txt") to wire,
                "record.json" to producer, "point.sol" to solution,
                "request.txt" to "policy=$policy\nlinear=${if (exact) "0" else "1e-7"}\nintegrality=0\nobjective=0\n".toByteArray())
            for ((name, bytes) in inputs) File(out, name).writeBytes(bytes)
            val bindings = inputs.mapValues { sha256(it.value) } + ("decodedStdout" to sha256(decoded))
            captured = mapOf("sourceValidationProducerRecordJson" to producer.decodeToString(),
                "sourceValidationRecordSha256" to sha256(producer),
                "sourceValidationSourceIdentityJson" to Reports.json.encodeToString(sourceIdentity(ref.source) + mapOf(
                    "problem" to ref.name, "catalogRevision" to record.gitSha.orEmpty(), "sourceSha256" to sha256(source))))
            active()
            val binaryBytes = resource("solchecker", 16 * 1024 * 1024)
            val binary = File(out, "solchecker").apply { writeBytes(binaryBytes); check(setExecutable(true, true)) }
            File(out, "LICENSE").writeBytes(resource("LICENSE", 65_536))
            active()
            val setupMs = (System.nanoTime() - started) / 1_000_000
            val processes = mutableListOf<MpsNativeProcess>()
            val coordinates = solution.decodeToString().lineSequence().count { it.isNotEmpty() } - 1
            val verdicts = try {
                val tolerances = if (exact) listOf("0") else listOf("0", "1e-7")
                tolerances.mapIndexed { index, tolerance ->
                    active()
                    val checkDirectory = File(out, "check-$index").apply { check(mkdir()) }
                    val process = MpsSourceReceipt.run(listOf("/usr/bin/timeout", "--signal=KILL", "30s",
                        "/usr/bin/prlimit", "--as=4294967296", "--fsize=1048576", "--core=0", "--",
                        binary.absolutePath, File(out, "source.mps").absolutePath,
                        File(out, "point.sol").absolutePath, tolerance, "0"), checkDirectory, minOf(30_000, remaining()))
                    processes += process
                    runCatching { MpsSourceReceipt.interpret(process, coordinates,
                        if (tolerance == "0") "0" else "1/10000000") }.getOrNull()
                }
            } finally {
                val unchanged = binary.readBytes().contentEquals(binaryBytes)
                val removed = binary.delete()
                check(unchanged && removed) { "checker executable changed or cleanup failed" }
            }
            active()
            check(inputs.all { (name, bytes) -> File(out, name).readBytes().contentEquals(bytes) }) { "checker inputs changed" }
            check(SolveEvidence.sourceHashes(ref) == record.sourceHashes) { "original source changed during checking" }
            val nominal = verdicts.first()
            val accepted = verdicts.last()
            val complete = nominal != null && accepted != null
            val valid = complete && accepted!!.integral && accepted.feasible && nominal!!.objective
            val exactFeasible = nominal?.let { it.integral && it.feasible }
            val status = if (!complete) "unknown" else if (valid) "valid" else "invalid"
            val receipt = Receipt(MPS_GROUND_CHECKER, policy, bindings, sha256(binaryBytes), ARCHIVE_SHA256,
                processes, status, exactFeasible, if (valid) exactFeasible == false else null)
            val json = Reports.json.encodeToString(receipt)
            File(out, "result.json").writeText(json)
            val validation = SourceValidation(status, when (status) {
                "valid" -> "original MPS point and exact objective checked by MIPLIB"
                "invalid" -> "MIPLIB rejected original MPS point or objective"
                else -> "MIPLIB checking incomplete or unsupported"
            }, scope = SCOPE, sourceHashes = record.sourceHashes,
                checkedWitness = record.finalWitness.takeIf { status == "valid" }, checker = MPS_GROUND_CHECKER)
            Result(validation, captured + mapOf("sourceValidationReceiptJson" to json,
                "sourceValidationReceiptSha256" to sha256(json.toByteArray()),
                "sourceValidationSetupMs" to setupMs.toString(), "sourceValidationCheckMs" to processes.sumOf { it.elapsedMs }.toString(),
                "sourceValidationSolverQueries" to "0", "sourceValidationProofChecked" to "false",
                "sourceValidationBinarySha256" to receipt.binarySha256,
                "sourceValidationArchiveSha256" to receipt.archiveSha256))
        }.getOrElse { failure ->
            Result(unknown("MPS source checking failed: ${failure.message.orEmpty().take(400)}"),
                captured + directory?.let { mapOf("sourceValidationDirectory" to it.absolutePath) }.orEmpty())
        }
    }

    private fun resource(name: String, limit: Int): ByteArray = MpsSourceValidation::class.java
        .getResourceAsStream("/miplib-solchecker/$name").use { stream ->
            val bytes = requireNotNull(stream) { "missing packaged MIPLIB $name" }.readNBytes(limit + 1)
            check(bytes.size in 1..limit) { "packaged checker exceeds limit" }
            bytes
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
}
