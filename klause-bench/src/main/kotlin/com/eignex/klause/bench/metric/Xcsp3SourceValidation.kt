package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import kotlinx.serialization.decodeFromString
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal const val XCSP_SOURCE_POLICY = "xcsp3-source-witness-v3"

internal object Xcsp3SourceValidation {
    private const val TIMEOUT_SECONDS = 10L
    internal const val RESULT_PREFIX = "klause-source-check: "

    fun candidate(rawOutput: String): String? {
        val lines = rawOutput.lineSequence().filter { it.startsWith("v ") }.map { it.removePrefix("v ") }.toList()
        val start = lines.indexOfLast { it.trimStart().startsWith("<instantiation") }
        val witness = lines.drop(start.coerceAtLeast(0)).joinToString("\n").trim()
        return witness.takeIf { it.endsWith("</instantiation>") }
    }

    fun objective(rawOutput: String): String? = rawOutput.lineSequence().lastOrNull { it.startsWith("o ") }
        ?.removePrefix("o ")?.trim()

    fun validate(ref: ProblemRef, rawOutput: String, exactObjective: String? = null): SourceValidation {
        val hashes = SolveEvidence.sourceHashes(ref)
        val witness = candidate(rawOutput) ?: return Xcsp3SourceChecker
            .result("unknown", "no complete XCSP3 instantiation").copy(sourceHashes = hashes)
        val reported = objective(rawOutput)
        if (exactObjective != null && reported != exactObjective) {
            return Xcsp3SourceChecker.result("invalid", "reported objective differs from retained output")
                .copy(sourceHashes = hashes)
        }
        val directory = Files.createTempDirectory("klause-xcsp-source-check-").toFile()
        val validation = runCatching {
            CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { source ->
                val pinnedSource = source.copyTo(File(directory, "source.xml"))
                val input = File(directory, "witness.xml").apply { writeText(witness) }
                val log = File(directory, "checker.log")
                val command = listOf(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                    Xcsp3SourceChecker::class.java.name, pinnedSource.absolutePath, input.absolutePath,
                ) + listOfNotNull(reported)
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        Xcsp3SourceChecker.result("unknown", "source checker timed out")
                    } else {
                        checkerResult(process.exitValue(), log.readText())
                    }
                } finally {
                    if (process.isAlive) process.destroyForcibly().waitFor()
                }
            }
        }.getOrElse { failure ->
            Xcsp3SourceChecker.result("unknown", "source checking failed: ${failure.message.orEmpty().take(500)}")
        }.also { directory.deleteRecursively() }
        return if (SolveEvidence.sourceHashes(ref) != hashes) {
            Xcsp3SourceChecker.result("unknown", "source files changed during validation").copy(sourceHashes = hashes)
        } else {
            validation.copy(sourceHashes = hashes)
        }
    }

    fun checkerResult(exitCode: Int, log: String): SourceValidation {
        if (exitCode == 0) {
            val lines = log.lines()
            val start = lines.indexOfLast { it.startsWith(RESULT_PREFIX) }
            val result = if (start < 0) null else lines.drop(start).joinToString("\n").removePrefix(RESULT_PREFIX)
            if (result != null) {
                return runCatching { Reports.json.decodeFromString<SourceValidation>(result) }.getOrElse {
                    Xcsp3SourceChecker.result("unknown", "malformed source checker result")
                }
            }
        }
        return Xcsp3SourceChecker.result("unknown", "source checker failed: ${log.takeLast(500)}")
    }
}
