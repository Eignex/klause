package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HintTest {

    @Test
    fun `feasible hint is yielded as first sample`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val hint = Sample(booleanArrayOf(false, false, true, false), intArrayOf())
        val sample = LocalSearchSolver(problem).sample(LocalSearchParams(
            maxFlips = 100L, randomSeed = 1L, hint = hint,
        ))
        assertEquals(hint, sample)
    }

    @Test
    fun `infeasible hint is repaired without erroring`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        // Hint violates exactly-one (all false). LS must repair it.
        val hint = Sample(booleanArrayOf(false, false, false), intArrayOf())
        val sample = LocalSearchSolver(problem)
            .sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L, hint = hint))
        assertNotNull(sample)
        assertEquals(1, sample.bools.count { it })
    }

    @Test
    fun `frozen assumption overrides hint`() {
        // Hint says bool 0 = false, but assumption pins it true. Result must respect pin.
        val problem = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(1, true)))),
        )
        val hint = Sample(booleanArrayOf(false, true), intArrayOf())
        val sample = LocalSearchSolver(problem).sample(LocalSearchParams(
            maxFlips = 100L, randomSeed = 2L,
            assumptions = Assumptions(bools = mapOf(0 to true)),
            hint = hint,
        ))
        assertNotNull(sample)
        assertTrue(sample.bools[0], "assumption should override hint")
        assertTrue(sample.bools[1], "clause forces bool 1 true")
    }

    @Test
    fun `int hint is honoured`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
        )
        val hint = Sample(booleanArrayOf(), intArrayOf(3))
        val sample = LocalSearchSolver(problem).sample(LocalSearchParams(
            maxFlips = 100L, randomSeed = 9L, hint = hint,
        ))
        assertEquals(hint, sample)
    }
}
