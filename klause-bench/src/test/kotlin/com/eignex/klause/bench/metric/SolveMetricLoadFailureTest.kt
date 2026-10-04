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

class SolveMetricLoadFailureTest {
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
    fun `a model klause declines is recorded as unsupported with its reason`() {
        val failure = IllegalStateException(
            "load failed",
            UnsupportedFlatZincException("unbounded `float` not supported", 3, 1),
        )

        assertEquals(setOf("unsupported"), stats(failure).keys)
    }

    @Test
    fun `a model that does not compile is recorded as a load error`() {
        val failure = IllegalArgumentException("minizinc compile failed (exit 1) for x.mzn: Error: include error\nmore")

        assertEquals(
            mapOf("loadError" to "minizinc compile failed (exit 1) for x.mzn: Error: include error / more"),
            stats(failure),
        )
    }
}
