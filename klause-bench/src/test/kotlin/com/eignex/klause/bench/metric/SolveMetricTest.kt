package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SolveMetricTest {
    private val ref = ProblemRef("fam/inst", Format.MINIZINC, corpus("absent.mzn"), Category.CSP, Expected.Unknown)

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
    fun `a failure record should retain captured hashes when the source is unavailable`() {
        val hashes = mapOf("model" to "captured-source-identity")

        val record = SolveMetric.loadFailureRecord(
            ref, SolverInvocation.Settings(), Budget(1000), "now", null,
            IllegalStateException("load failed"), sourceHashes = hashes,
        )

        assertEquals(hashes, record.sourceHashes)
    }

    @Test
    fun `a solve should retain complete subprocess diagnostics`() {
        val (result, _) = SolveMetric.solve(entry(), SolverInvocation.Settings(), Budget(),
            "now", "fixture", installedBuild = { null },
            invoke = { _, _, _, _ ->
                SolverInvocation.Result(null, null, null, proven = false, stats = emptyMap(), rawOutput = "",
                    command = "cli", stderr = DIAGNOSTICS, stderrComplete = true)
            })

        assertEquals(DIAGNOSTICS, result.stderr)
        assertEquals(true, result.stderrComplete)
    }

    @Test
    fun `a failed invocation retains its exit and stderr metadata in the solve record`() {
        val capture = StderrCapture("diagnostic\n".byteInputStream()).start().await(100, processKilled = false)
        val invocation = SolverInvocation.Result(null, null, null, proven = false,
            stats = mapOf("exit" to "7"), rawOutput = "partial\n", command = "fake",
            stderr = capture.text, stderrComplete = capture.complete, stderrCapture = capture.status,
            compiledInputHashes = mapOf("flatzinc" to "retained-input"))

        val (result, raw) = SolveMetric.solve(entry(), SolverInvocation.Settings(), Budget(),
            "now", "fixture", installedBuild = { null },
            invoke = { _, _, _, _ -> throw SolverInvocation.Failure(invocation, "failed") })

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

        val (result, _) = SolveMetric.solve(entry(), settings, Budget(),
            "now", "fixture", installedBuild = { null },
            invoke = { _, native, _, _ ->
                invoked = native
                SolverInvocation.Result(true, 0.125, 1, proven = true, stats = emptyMap(),
                    rawOutput = "o 1/8\nv I=0 X=1/10\n", command = "cli", exactObjective = "1/8")
            })

        assertEquals(settings, invoked)
        assertEquals(REPORTED_RESULT_POLICY, result.validationPolicy)
        assertEquals("v I=0 X=1/10", result.finalWitness)
        assertEquals("1/8", result.exactObjective)
        assertEquals(true, result.feasible)
        assertEquals(true, result.proven)
        assertNull(result.stats["sourceValidation"])
    }

    private fun entry(): ResolvedProblem {
        val mps = ProblemRef("bounded-decimal-constant", Format.MPS,
            corpus("mps-linear-source-v1/bounded-decimal-constant.mps"), Category.OPTIMIZATION, Expected.Unknown)
        return ResolvedProblem(mps, lazy { error("source parsing must stay lazy") })
    }

    private companion object {
        const val DIAGNOSTICS = "source preparation: factors 3, ints 1, reals 0\n"
    }
}
