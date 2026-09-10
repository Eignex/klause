package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.hfactor.BundledHfactor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.math.max

/** Explicit real-basis corpus tool. Ordinary test discovery never runs capture or timing. */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "list"
    val options = args.drop(1).associate { argument ->
        val separator = argument.indexOf('=')
        require(separator > 0) { "expected key=value, got '$argument'" }
        argument.substring(0, separator) to argument.substring(separator + 1)
    }
    when (command) {
        "list" -> tracePaths(options).forEach { printListRecord(it) }
        "replay" -> tracePaths(options).forEach { replay(it, benchmark = false) }
        "benchmark" -> tracePaths(options).forEach { replay(it, benchmark = true) }
        "capture" -> capture(options)
        "verify" -> verifyCorpus(tracePaths(options))
        else -> error("usage: basisTrace list|replay|benchmark|capture|verify [key=value ...]")
    }
}

private fun capture(options: Map<String, String>) {
    val format = when (options.required("format").lowercase()) {
        "mps" -> BasisTraceFormat.MPS
        "minizinc", "mzn", "fzn" -> BasisTraceFormat.MINIZINC
        "smt", "smtlib", "smt2" -> BasisTraceFormat.SMTLIB
        else -> error("unknown format ${options["format"]}")
    }
    val id = options.required("id")
    val output = options["output"]?.let(Path::of) ?: corpusDirectory().resolve("$id.kbtrace")
    val result = BasisTraceCapture.capture(
        BasisCaptureRequest(
            id,
            format,
            Path.of(options.required("source")),
            options["source-label"] ?: options.required("source"),
            options.required("license"),
            output,
            options["seed"]?.toLong() ?: 0L,
        ),
    )
    println(
        json(
            "command" to "capture",
            "id" to result.trace.metadata.id,
            "format" to result.trace.metadata.format.name,
            "route" to result.trace.metadata.route.name,
            "output" to output.toAbsolutePath().toString(),
            "bytes" to result.encodedBytes,
            "rows" to result.trace.matrix.rows,
            "columns" to result.trace.matrix.columns,
            "nnz" to result.trace.matrix.entries,
            "operations" to result.trace.operations.size,
            "termination" to result.trace.metadata.termination,
            "truncated" to result.trace.metadata.truncated,
        ),
    )
}

private fun printListRecord(path: Path) {
    val trace = BasisTraceCodec.read(path)
    val updates = trace.operations.count { it is BasisTraceOperation.Update }
    val accepted = trace.operations.count {
        it is BasisTraceOperation.Update && it.outcome != BasisUpdate.SINGULAR
    }
    println(
        json(
            "command" to "list",
            "id" to trace.metadata.id,
            "format" to trace.metadata.format.name,
            "route" to trace.metadata.route.name,
            "artifactSha256" to sha256(Files.readAllBytes(path)),
            "sourceSha256" to trace.metadata.sourceSha256,
            "rows" to trace.matrix.rows,
            "columns" to trace.matrix.columns,
            "nnz" to trace.matrix.entries,
            "density" to trace.matrix.entries.toDouble() / (trace.matrix.rows.toLong() * trace.matrix.columns),
            "operations" to trace.operations.size,
            "updates" to updates,
            "acceptedUpdates" to accepted,
            "mixedBasis" to hasMixedBasis(trace),
            "termination" to trace.metadata.termination,
            "path" to path.toAbsolutePath().toString(),
        ),
    )
}

private fun replay(path: Path, benchmark: Boolean) {
    val trace = BasisTraceCodec.read(path)
    val artifactSha = sha256(Files.readAllBytes(path))
    if (!benchmark) {
        val pair = BasisTraceReplay.replay(trace)
        println(reportJson("replay", trace, artifactSha, pair.custom, repetitions = 1))
        println(reportJson("replay", trace, artifactSha, pair.hfactor, repetitions = 1))
        return
    }
    BasisTraceReplay.replay(trace) // fixed warmup, never reported as a measured repetition
    val reports = ArrayList<BasisReplayReport>()
    repeat(3) {
        val pair = BasisTraceReplay.replay(trace)
        reports += pair.custom
        reports += pair.hfactor
    }
    for (backend in listOf("custom", "hfactor")) {
        val arm = reports.filter { it.backend == backend }
        println(benchmarkJson(trace, artifactSha, arm))
    }
}

private fun verifyCorpus(paths: List<Path>) {
    val traces = paths.map(BasisTraceCodec::read)
    val formats = traces.map { it.metadata.format }.toSet()
    val successful = traces.filter { trace ->
        trace.operations.any { it is BasisTraceOperation.Factorize && it.success }
    }
    val acceptedChains = traces.filter {
        it.metadata.route == BasisTraceRoute.PRODUCTION_RELAXATION
    }.maxOfOrNull { trace ->
        var chain = 0
        var longest = 0
        for (operation in trace.operations) {
            when (operation) {
                is BasisTraceOperation.Factorize -> chain = 0

                is BasisTraceOperation.Update -> if (operation.outcome == BasisUpdate.SINGULAR) {
                    chain = 0
                } else {
                    chain++
                    longest = max(longest, chain)
                }

                else -> Unit
            }
        }
        longest
    } ?: 0
    val mixed = traces.count(::hasMixedBasis)
    val large = traces.count { it.matrix.rows > 16 }
    val replayErrors = traces.sumOf {
        val replay = BasisTraceReplay.replay(it)
        replay.custom.stateErrors + replay.hfactor.stateErrors
    }
    val failures = buildList {
        if (formats != BasisTraceFormat.entries.toSet()) {
            add("missing-format")
        }
        if (successful.map { it.metadata.format }.toSet() != BasisTraceFormat.entries.toSet()) {
            add("format-without-build")
        }
        if (large < 2) add("fewer-than-two-bases-above-16")
        if (mixed < 2) add("fewer-than-two-mixed-bases")
        if (acceptedChains < 2) add("no-two-update-production-chain")
        if (replayErrors != 0) add("replay-errors=$replayErrors")
    }
    println(
        json(
            "command" to "verify",
            "traces" to traces.size,
            "formats" to formats.map(Enum<*>::name).sorted().joinToString(","),
            "largeBases" to large,
            "mixedBases" to mixed,
            "longestAcceptedChain" to acceptedChains,
            "replayErrors" to replayErrors,
            "passed" to failures.isEmpty(),
            "failures" to failures.joinToString(","),
        ),
    )
    check(failures.isEmpty()) { "basis corpus acceptance failed: ${failures.joinToString()}" }
}

private fun hasMixedBasis(trace: BasisTrace): Boolean = trace.operations.any { operation ->
    operation is BasisTraceOperation.Factorize && operation.success &&
        operation.headings.any { it is BasisHeading.Source } && operation.headings.any { it is BasisHeading.Unit }
}

private fun reportJson(
    command: String,
    trace: BasisTrace,
    artifactSha: String,
    report: BasisReplayReport,
    repetitions: Int,
): String = json(*commonFields(command, trace, artifactSha, report, repetitions).toTypedArray())

internal fun benchmarkJson(trace: BasisTrace, artifactSha: String, reports: List<BasisReplayReport>): String {
    val validReports = reports.filter { it.stateErrors == 0 }
    val representative = validReports.firstOrNull() ?: reports.first()
    val aggregate = representative.copy(
        stateErrors = reports.sumOf(BasisReplayReport::stateErrors),
        errors = reports.flatMap(BasisReplayReport::errors),
        timing = validReports.firstOrNull()?.timing ?: BasisReplayTiming(),
    )
    val fields = commonFields(
        "benchmark",
        trace,
        artifactSha,
        aggregate,
        reports.size,
        timingValid = validReports.isNotEmpty(),
    ).toMutableList()
    fun addSpread(name: String, values: List<Long>) {
        if (values.isEmpty()) {
            fields += "${name}Median" to null
            fields += "${name}Min" to null
            fields += "${name}Max" to null
            return
        }
        val sorted = values.sorted()
        fields += "${name}Median" to sorted[sorted.size / 2]
        fields += "${name}Min" to sorted.first()
        fields += "${name}Max" to sorted.last()
    }
    addSpread("setupNanos", validReports.map { it.timing.setupNanos })
    addSpread("buildNanos", validReports.map { it.timing.buildNanos })
    addSpread("ftranNanos", validReports.map { it.timing.ftranNanos })
    addSpread("btranNanos", validReports.map { it.timing.btranNanos })
    addSpread("updateOnlyNanos", validReports.map { it.timing.updateOnlyNanos })
    addSpread("preparedUpdateNanos", validReports.map { it.timing.preparedUpdateNanos })
    addSpread("composedNanos", validReports.map { it.timing.lifecycleNanos })
    addSpread("totalNanos", validReports.map { it.timing.setupNanos + it.timing.lifecycleNanos })
    addSpread("setupBytes", validReports.map { it.timing.setupBytes })
    addSpread("buildBytes", validReports.map { it.timing.buildBytes })
    addSpread("ftranBytes", validReports.map { it.timing.ftranBytes })
    addSpread("btranBytes", validReports.map { it.timing.btranBytes })
    addSpread("updateOnlyBytes", validReports.map { it.timing.updateOnlyBytes })
    addSpread("preparedUpdateBytes", validReports.map { it.timing.preparedUpdateBytes })
    fields += "validRepetitions" to validReports.size
    fields += "allRepetitionsValid" to reports.all { it.stateErrors == 0 }
    return json(*fields.toTypedArray())
}

private fun commonFields(
    command: String,
    trace: BasisTrace,
    artifactSha: String,
    report: BasisReplayReport,
    repetitions: Int,
    timingValid: Boolean = true,
): List<Pair<String, Any?>> = listOf(
    "command" to command,
    "id" to trace.metadata.id,
    "source" to trace.metadata.source,
    "sourceSha256" to trace.metadata.sourceSha256,
    "format" to trace.metadata.format.name,
    "route" to trace.metadata.route.name,
    "captureRevision" to trace.metadata.captureRevision,
    "configuration" to trace.metadata.configuration,
    "backend" to report.backend,
    "artifactSha256" to artifactSha,
    "koblasArtifactSha256" to artifactHash(SparseMatrix::class.java),
    "hfactorArtifactSha256" to artifactHash(BundledHfactor::class.java),
    "javaRuntime" to System.getProperty("java.runtime.version"),
    "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    "os" to "${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
    "rows" to trace.matrix.rows,
    "columns" to trace.matrix.columns,
    "nnz" to trace.matrix.entries,
    "density" to trace.matrix.entries.toDouble() / (trace.matrix.rows.toLong() * trace.matrix.columns),
    "repetitions" to repetitions,
    "adapterInclusive" to report.adapterInclusive,
    "adapterCopyIsolation" to if (report.adapterInclusive) "not-isolated-private-adapter" else "not-applicable",
    "allocationCoverage" to report.allocationCoverage,
    "builds" to report.builds,
    "ftrans" to report.ftrans,
    "btrans" to report.btrans,
    "acceptedUpdates" to report.acceptedUpdates,
    "advisedUpdates" to report.advisedUpdates,
    "declinedUpdates" to report.declinedUpdates,
    "checkpoints" to report.checkpoints,
    "peakFill" to report.peakFill,
    "maxChainAge" to report.maxChainAge,
    "absoluteResidual" to report.absoluteResidual,
    "relativeResidual" to report.relativeResidual,
    "residualTolerance" to report.residualTolerance,
    "stateErrors" to report.stateErrors,
    "errors" to report.errors.joinToString(" | "),
    "setupNanos" to report.timing.setupNanos.takeIf { timingValid },
    "buildNanos" to report.timing.buildNanos.takeIf { timingValid },
    "ftranNanos" to report.timing.ftranNanos.takeIf { timingValid },
    "btranNanos" to report.timing.btranNanos.takeIf { timingValid },
    "updateOnlyNanos" to report.timing.updateOnlyNanos.takeIf { timingValid },
    "preparedUpdateNanos" to report.timing.preparedUpdateNanos.takeIf { timingValid },
    "composedNanos" to report.timing.lifecycleNanos.takeIf { timingValid },
    "totalNanos" to (report.timing.setupNanos + report.timing.lifecycleNanos).takeIf { timingValid },
    "setupBytes" to report.timing.setupBytes.takeIf { timingValid },
    "buildBytes" to report.timing.buildBytes.takeIf { timingValid },
    "ftranBytes" to report.timing.ftranBytes.takeIf { timingValid },
    "btranBytes" to report.timing.btranBytes.takeIf { timingValid },
    "updateOnlyBytes" to report.timing.updateOnlyBytes.takeIf { timingValid },
    "preparedUpdateBytes" to report.timing.preparedUpdateBytes.takeIf { timingValid },
    "preparedUpdateMode" to "synthetic_prepared",
    "repair" to "not_comparable",
    "snapshots" to "not_comparable",
    "extension" to "not_comparable",
)

private fun tracePaths(options: Map<String, String>): List<Path> {
    options["trace"]?.let { return listOf(Path.of(it)) }
    val directory = options["dir"]?.let(Path::of) ?: corpusDirectory()
    Files.list(directory).use { paths ->
        return paths.filter { it.extension == "kbtrace" }.sorted().toList()
    }
}

private fun corpusDirectory(): Path {
    val root = System.getProperty("klause.workspace.root")?.let(Path::of) ?: Path.of("").toAbsolutePath()
    return root.resolve("klause/src/jvmTest/resources/basis-corpus")
}

private fun artifactHash(type: Class<*>): String {
    val path = runCatching { Path.of(type.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        ?: return "unavailable"
    return if (Files.isRegularFile(path)) sha256(Files.readAllBytes(path)) else "unavailable-directory"
}

private fun Map<String, String>.required(key: String): String =
    requireNotNull(this[key]) { "missing required $key=..." }

private fun json(vararg fields: Pair<String, Any?>): String = fields.joinToString(",", "{", "}") { (key, value) ->
    "\"${escape(key)}\":" + when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }
}

private fun escape(value: String): String = buildString(value.length) {
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
