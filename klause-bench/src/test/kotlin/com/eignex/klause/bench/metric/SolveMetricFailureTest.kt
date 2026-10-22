package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolveMetricFailureTest {
    private val ref = ProblemRef("php4", Format.DIMACS, corpus("dimacs/php4.cnf"), Category.CSP, Expected.Unknown)
    private val entry = ResolvedProblem(ref, lazy { error("subprocess input must stay lazy") })
    private val build = BuildProvenance(mapOf("lib/solver.jar" to "fixture"), emptyMap(), emptyMap(), "Linux", "amd64")
    private val settings = SolverInvocation.Settings(engine = "cp", processors = 2, seed = 17)

    private fun persisted(failure: Throwable): SolveRecord {
        val (record, raw) = SolveMetric.solve(
            entry, settings, Budget(1000),
            "timestamp", "revision",
            installedBuild = { build },
            invoke = { _, _, _, _ -> throw failure },
        )
        assertNull(raw)
        val file = File.createTempFile("solve-error-", ".json")
        try {
            file.writeText(Reports.json.encodeToString(record))
            return Reports.json.decodeFromString(file.readText())
        } finally {
            file.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["argument", "state", "io", "no-message"])
    fun `caught solve failures should retain their class and message on disk`(type: String) {
        val failure = when (type) {
            "argument" -> IllegalArgumentException("bad argument\nsecond line")
            "state" -> IllegalStateException("invalid state")
            "io" -> IOException("cannot launch")
            else -> IOException()
        }

        val record = persisted(failure)

        assertEquals(failure.javaClass.name, record.stats["errorClass"])
        assertEquals(failure.message, record.stats["errorMessage"])
        assertEquals("ERROR", record.command)
        assertNull(record.feasible)
        assertNull(record.objective)
        assertFalse(record.proven)
    }

    @Test
    fun `caught solve failures should retain source build and invocation identity`() {
        val record = persisted(IOException("cannot launch"))

        assertEquals(mapOf("model" to sha256(CorpusFetcher.resolve(ref.source).readBytes())), record.sourceHashes)
        assertEquals(build, record.buildProvenance)
        assertEquals(build.fingerprint, record.buildFingerprint)
        assertEquals("revision", record.gitSha)
        assertEquals("cp", record.engine)
        assertEquals(2, record.processors)
        assertEquals(17, record.seed)
        assertEquals(1000, record.budgetMs)
    }

    @Test
    fun `caught solve failures should retain a nested cause on disk`() {
        val cause = IOException("launcher missing", IllegalArgumentException("path"))
        val failure = IllegalStateException("solve failed", cause)

        val record = persisted(failure)

        assertEquals("java.io.IOException", record.stats["errorCause1Class"])
        assertEquals("launcher missing", record.stats["errorCause1Message"])
        assertEquals("java.lang.IllegalArgumentException", record.stats["errorCause2Class"])
        assertEquals("path", record.stats["errorCause2Message"])
    }

    @Test
    fun `oversized caught messages should retain a bounded prefix with explicit truncation`() {
        val record = persisted(IllegalStateException("root:" + "x".repeat(100_000), IOException("y".repeat(100_000))))

        assertEquals("root:" + "x".repeat(1019), record.stats["errorMessage"])
        assertEquals("y".repeat(1024), record.stats["errorCause1Message"])
        assertEquals("true", record.stats["errorMessageTruncated"])
        assertEquals("true", record.stats["errorCause1MessageTruncated"])
        assertTrue(Reports.json.encodeToString(record.stats).length < 3000)
    }

    @Test
    fun `deep caught cause chains should retain bounded causes with explicit truncation`() {
        val failure = (1..20).fold<Int, Throwable>(IOException("deepest")) { cause, depth ->
            IllegalStateException("level $depth", cause)
        }

        val record = persisted(failure)

        assertEquals("level 17", record.stats["errorCause3Message"])
        assertEquals("depth", record.stats["errorCausesTruncated"])
        assertNull(record.stats["errorCause4Class"])
    }

    @Test
    fun `cyclic caught cause chains should terminate with an explicit marker`() {
        val failure = IllegalStateException("outer")
        val cause = IOException("inner", failure)
        failure.initCause(cause)

        val record = persisted(failure)

        assertEquals("inner", record.stats["errorCause1Message"])
        assertEquals("cycle", record.stats["errorCausesTruncated"])
        assertNull(record.stats["errorCause2Class"])
    }

    @Test
    fun `an unavailable build capture should not mask its exception or invent an identity`() {
        val (record, _) = SolveMetric.solve(
            entry, settings, Budget(1000), "timestamp", "revision",
            installedBuild = { throw IOException("missing installation") },
            invoke = { _, _, _, _ -> error("must not launch") },
        )

        assertEquals("missing installation", record.stats["errorMessage"])
        assertTrue(record.sourceHashes.isNotEmpty())
        assertNull(record.buildProvenance)
        assertNull(record.buildFingerprint)
    }

    @ParameterizedTest
    @ValueSource(strings = ["success", "unknown"])
    fun `ordinary outcomes should not acquire caught exception diagnostics`(outcome: String) {
        val result = SolverInvocation.Result(
            feasible = if (outcome == "success") true else null,
            objective = null, timeToBestMs = null, proven = false, stats = emptyMap(), rawOutput = "",
            command = "cli", buildProvenance = build,
        )
        val (record, _) = SolveMetric.solve(
            entry, settings, Budget(1000), "timestamp", "revision",
            installedBuild = { build }, invoke = { _, _, _, _ -> result },
        )

        assertEquals(result.feasible, record.feasible)
        assertEquals("cli", record.command)
        assertTrue(record.stats.isEmpty())
    }
}
