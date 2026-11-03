package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincIdentityEvidence
import com.eignex.klause.bench.runner.MiniZincIdentityFailure
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SolveMetricTest {
    private val ref = ProblemRef("fam/inst", Format.MINIZINC, corpus("absent.mzn"), Category.CSP, Expected.Unknown)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a solve should retain the emitted numerical policy without inventing absent declarations`(declared: Boolean) {
        val text = "{\"schema\":\"mps-decimal-semantics-v1\",\"effective\":{\"objective\":\"exact\"}}"
            .takeIf { declared }
        val (record, _) = SolveMetric.solve(
            entry(),
            SolverInvocation.Settings(),
            Budget(),
            "now",
            "fixture",
            installedBuild = { null },
            invoke = { _, _, _, _ ->
                SolverInvocation.Result(
                    true, null, null, proven = false,
                    stats = text?.let { mapOf("numericalSemantics" to it) }.orEmpty(),
                    rawOutput = "v X=1\n", command = "cli",
                )
            },
        )

        assertEquals(text?.let { Json.parseToJsonElement(it) }, record.numericalSemantics)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `best incumbent should compare the full signed objective range`(maximize: Boolean) {
        val attribution = listOf(
            Attribution("minimum", exactObjective = Long.MIN_VALUE.toString(), elapsedMs = 10),
            Attribution("middle", exactObjective = "0", elapsedMs = 20),
            Attribution("maximum", exactObjective = Long.MAX_VALUE.toString(), elapsedMs = 30),
        )

        val best = SolveMetric.best(attribution, maximize)

        assertEquals(if (maximize) attribution.last() else attribution.first(), best)
    }

    private fun stats(failure: Throwable) = SolveMetric.loadFailureRecord(
        ref,
        SolverInvocation.Settings(),
        Budget(1000),
        "now",
        null,
        failure,
    ).stats

    @Test
    fun `a declined model should be recorded as unsupported with its reason`() {
        val failure = IllegalStateException(
            "load failed",
            UnsupportedFlatZincException("unbounded `float` not supported", 3, 1),
        )

        assertEquals(setOf("unsupported"), stats(failure).keys)
    }

    @Test
    fun `a model that does not compile should be recorded as a load error`() {
        val failure = IllegalArgumentException("minizinc compile failed (exit 1) for x.mzn: Error: include error\nmore")

        assertEquals(
            mapOf("loadError" to "minizinc compile failed (exit 1) for x.mzn: Error: include error / more"),
            stats(failure),
        )
    }

    @Test
    fun `failed compiler discovery retains evidence without accepting an identity or excluding the source`() {
        val evidence = MiniZincIdentityEvidence(
            phase = "--config-dirs",
            command = listOf("minizinc", "--config-dirs"),
            startedAt = "now",
            outcome = "timed-out",
            elapsedNs = 123,
            timeoutNs = 100,
            exitCode = 137,
        )

        val record = SolveMetric.loadFailureRecord(
            ref, SolverInvocation.Settings(), Budget(), "now", null,
            MiniZincIdentityFailure(evidence), sourceHashes = mapOf("model" to "captured"),
        )

        assertEquals(evidence, record.compilerDiscovery)
        assertNull(record.compilerIdentity)
        assertEquals(setOf("loadError"), record.stats.keys)
    }

    @Test
    fun `a failure record should retain captured hashes when the source is unavailable`() {
        val hashes = mapOf("model" to "captured-source-identity")

        val record = SolveMetric.loadFailureRecord(
            ref,
            SolverInvocation.Settings(),
            Budget(1000),
            "now",
            null,
            IllegalStateException("load failed"),
            sourceHashes = hashes,
        )

        assertEquals(hashes, record.sourceHashes)
    }

    @Test
    fun `a solve should retain complete subprocess diagnostics`() {
        val (result, _) = SolveMetric.solve(
            entry(),
            SolverInvocation.Settings(),
            Budget(),
            "now",
            "fixture",
            installedBuild = { null },
            invoke = { _, _, _, _ ->
                SolverInvocation.Result(
                    null, null, null, proven = false, stats = emptyMap(), rawOutput = "",
                    command = "cli", stderr = DIAGNOSTICS, stderrComplete = true,
                )
            },
        )

        assertEquals(DIAGNOSTICS, result.stderr)
        assertEquals(true, result.stderrComplete)
    }

    @Test
    fun `a failed invocation retains its exit and stderr metadata in the solve record`() {
        val capture = StderrCapture("diagnostic\n".byteInputStream()).start().await(100, processKilled = false)
        val invocation = SolverInvocation.Result(
            null, null, null, proven = false,
            stats = mapOf("exit" to "7"), rawOutput = "partial\n", command = "fake",
            stderr = capture.text, stderrComplete = capture.complete, stderrCapture = capture.status,
            compiledInputHashes = mapOf("flatzinc" to "retained-input"),
        )

        val (result, raw) = SolveMetric.solve(
            entry(),
            SolverInvocation.Settings(),
            Budget(),
            "now",
            "fixture",
            installedBuild = { null },
            invoke = { _, _, _, _ -> throw SolverInvocation.Failure(invocation, "failed") },
        )

        assertEquals("7", result.stats["exit"])
        assertNotNull(result.stats["errorClass"])
        assertEquals(invocation.stderrCapture, result.stderrCapture)
        assertEquals(invocation.compiledInputHashes, result.compiledInputHashes)
        assertEquals(invocation.stderr, result.stderr)
        assertEquals("partial\n", raw)
    }

    @Test
    fun `a former source validation param should reach the solver and leave the claims unchecked`() {
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true", "bt-arm=satOptimized"))
        var invoked: SolverInvocation.Settings? = null

        val (result, _) = SolveMetric.solve(
            entry(),
            settings,
            Budget(),
            "now",
            "fixture",
            installedBuild = { null },
            invoke = { _, native, _, _ ->
                invoked = native
                SolverInvocation.Result(
                    true,
                    0.125,
                    1,
                    proven = true,
                    stats = emptyMap(),
                    rawOutput = "o 1/8\nv I=0 X=1/10\n",
                    command = "cli",
                    exactObjective = "1/8",
                )
            },
        )

        assertEquals(settings, invoked)
        assertEquals(REPORTED_RESULT_POLICY, result.validationPolicy)
        assertEquals("v I=0 X=1/10", result.finalWitness)
        assertEquals("1/8", result.exactObjective)
        assertEquals(true, result.feasible)
        assertEquals(true, result.proven)
        assertNull(result.stats["sourceValidation"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `harness timings retain invocation boundaries and requested budgets on success and failure`(fails: Boolean) {
        var nanos = 0L
        val settings = SolverInvocation.Settings(seed = 17)
        val budget = Budget(10000)
        val invocation = SolverInvocation.Result(
            true, null, null, proven = false,
            stats = mapOf("sourceInvocationMs" to "7"), rawOutput = "v X=1\n", command = "cli", elapsedMs = 7,
        )

        val (record, raw) = SolveMetric.solve(
            entry(), settings, budget, "now", "fixture",
            installedBuild = { nanos += 30_000_000; null },
            invoke = { _, supplied, allowance, _ ->
                assertEquals(settings, supplied)
                assertEquals(budget, allowance)
                nanos += 10_000_000
                if (fails) throw SolverInvocation.Failure(invocation, "failed")
                invocation
            },
            timings = HarnessTimings { nanos },
        )

        assertEquals("30", record.stats["harnessBuildProvenanceMs"])
        assertEquals("10", record.stats["harnessInvocationMs"])
        assertEquals("40", record.stats["harnessSolveMs"])
        assertEquals("7", record.stats["sourceInvocationMs"])
        assertEquals(7L, record.elapsedMs)
        assertEquals(10000L, record.budgetMs)
        assertEquals(invocation.rawOutput, raw)
        assertEquals(fails, "errorClass" in record.stats)
    }

    private fun entry(): ResolvedProblem {
        val mps = ProblemRef(
            "bounded-decimal-constant",
            Format.MPS,
            corpus("mps-linear-source-v1/bounded-decimal-constant.mps"),
            Category.OPTIMIZATION,
            Expected.Unknown,
        )
        return ResolvedProblem(mps, lazy { error("source parsing must stay lazy") })
    }

    private companion object {
        const val DIAGNOSTICS = "source preparation: factors 3, ints 1, reals 0\n"
    }
}
