package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveEmphasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourcePresolveOptionsTest {
    @Test
    fun `effort overrides should stay local to the request`() {
        val params = listOf("presolve-abort-fraction=0.01", "presolve-max-rounds=3", "seed=7")

        val edited = SourceExecutionPolicy().finiteRequest(
            FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null),
            FiniteEngine.BACKTRACK, executionOptions = ExecutionOptions.parse(params),
        ).presolveConfig
        val next = PresolveConfig.DEFAULT

        assertEquals(0.01, edited.abortFraction)
        assertEquals(3, edited.maxRounds)
        assertEquals(listOf("presolve-abort-fraction=0.01", "presolve-max-rounds=3", "seed=7"), params)
        assertEquals(0.001, next.abortFraction)
        assertEquals(16, next.maxRounds)
    }

    @Test
    fun `plan adaptations should retain experiment effort`() {
        val base = PresolveConfig(PresolveEmphasis.AGGRESSIVE)
        val params = listOf("presolve-probe-per-var=9", "presolve-probe-total=40", "presolve-max-rounds=2")

        val adapted = SourceExecutionPolicy().finiteRequest(
            FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null),
            FiniteEngine.LOCAL_SEARCH, presolveConfig = base, executionOptions = ExecutionOptions.parse(params),
        ).presolveConfig.forLocalSearch()
            .withAffinePivotOrder(AffinePivotOrder.STABLE_ID)

        assertEquals(9, adapted.probeBudgetPerVar())
        assertEquals(40, adapted.probeTotalBudget())
        assertEquals(2, adapted.maxRounds)
        assertEquals(4096, base.probeBudgetPerVar())
    }

    @Test
    fun `nonfinite fractions should be rejected`() {
        assertFailsWith<PipelineConfigException> {
            ExecutionOptions.parse(listOf("presolve-abort-fraction=NaN")).reader().presolve(PresolveConfig.DEFAULT)
        }
    }

    @Test
    fun `negative round limits should be rejected`() {
        assertFailsWith<PipelineConfigException> {
            ExecutionOptions.parse(listOf("presolve-max-rounds=-1")).reader().presolve(PresolveConfig.DEFAULT)
        }
    }
}
