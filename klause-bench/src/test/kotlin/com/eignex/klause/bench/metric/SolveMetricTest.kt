package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SolveMetricTest {
    private val ref = ProblemRef("fam/inst", Format.MINIZINC, corpus("absent.mzn"), Category.CSP, Expected.Unknown)

    private fun stats(failure: Throwable) = SolveMetric.loadFailureRecord(
        ref,
        SolverInvocation.KLAUSE,
        SolverInvocation.Settings(),
        Budget(1000),
        "now",
        null,
        failure,
    ).stats

    @Test
    fun `exact and default runs should use different result and cache names`() {
        val settings = SolverInvocation.Settings()
        val budget = Budget(1000)

        val default = SolveMetric.configTag(SolverInvocation.KLAUSE, settings, budget)
        val exact = SolveMetric.configTag(SolverInvocation.KLAUSE, settings.copy(exact = true), budget)

        assertNotEquals(default, exact)
    }

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
            ref, SolverInvocation.KLAUSE, SolverInvocation.Settings(), Budget(1000), "now", null,
            IllegalStateException("reference failed"), sourceHashes = hashes,
        )

        assertEquals(hashes, record.sourceHashes)
    }
}
