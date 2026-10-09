package com.eignex.klause.cli

import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveEmphasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PresolveEffortParamsTest {
    @Test
    fun `effort overrides stay local to the request`() {
        val params = mutableListOf("presolve-abort-fraction=0.01", "presolve-max-rounds=3", "seed=7")

        val edited = presolveEffortParams(params, PresolveConfig.DEFAULT)
        val next = presolveEffortParams(mutableListOf(), PresolveConfig.DEFAULT)

        assertEquals(0.01, edited.abortFraction)
        assertEquals(3, edited.maxRounds)
        assertEquals(listOf("seed=7"), params)
        assertEquals(0.001, next.abortFraction)
        assertEquals(16, next.maxRounds)
    }

    @Test
    fun `plan adaptations retain experiment effort`() {
        val base = PresolveConfig(PresolveEmphasis.AGGRESSIVE)
        val params = mutableListOf("presolve-probe-per-var=9", "presolve-probe-total=40", "presolve-max-rounds=2")

        val adapted = presolveEffortParams(params, base).forLocalSearch()
            .withAffinePivotOrder(AffinePivotOrder.STABLE_ID)

        assertEquals(9, adapted.probeBudgetPerVar())
        assertEquals(40, adapted.probeTotalBudget())
        assertEquals(2, adapted.maxRounds)
        assertEquals(4096, base.probeBudgetPerVar())
    }

    @Test
    fun `nonfinite fractions are rejected`() {
        assertFailsWith<CliUsageException> {
            presolveEffortParams(mutableListOf("presolve-abort-fraction=NaN"), PresolveConfig.DEFAULT)
        }
    }

    @Test
    fun `negative round limits are rejected`() {
        assertFailsWith<CliUsageException> {
            presolveEffortParams(mutableListOf("presolve-max-rounds=-1"), PresolveConfig.DEFAULT)
        }
    }
}
