package com.eignex.klause.solver.meta.coreguided

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OllTest {

    private val mutex01 = Problem(
        numBoolVars = 2,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
    )
    private val softs = listOf(Oll.Soft(Lit.make(0, true)), Oll.Soft(Lit.make(1, true)))

    @Test
    fun `softCost counts the weight of every unsatisfied soft`() {
        val both = Sample(bools = booleanArrayOf(false, false), ints = intArrayOf())
        val one = Sample(bools = booleanArrayOf(true, false), ints = intArrayOf())
        val none = Sample(bools = booleanArrayOf(true, true), ints = intArrayOf())
        assertEquals(2L, Oll.softCost(both, softs))
        assertEquals(1L, Oll.softCost(one, softs))
        assertEquals(0L, Oll.softCost(none, softs))
    }

    @Test
    fun `recoverOptimalSample replaces an over-relaxed witness with a cost-lb model`() {
        // #80: the witness violates both softs (cost 2) but the proven optimum is lb = 1.
        // recoverOptimalSample must re-solve under the true-cost cap and return a model
        // whose cost is exactly lb — here, exactly one of b0/b1 true.
        val overRelaxed = Sample(bools = booleanArrayOf(false, false), ints = intArrayOf())
        val recovered = Oll.recoverOptimalSample(mutex01, softs, overRelaxed, lb = 1L, BacktrackParams())
        assertEquals(1L, Oll.softCost(recovered, softs))
        assertTrue(recovered.bools[0] != recovered.bools[1])
    }

    @Test
    fun `recoverOptimalSample is a no-op when the witness already costs lb`() {
        val good = Sample(bools = booleanArrayOf(true, false), ints = intArrayOf())
        val recovered = Oll.recoverOptimalSample(mutex01, softs, good, lb = 1L, BacktrackParams())
        assertSame(good, recovered)
    }
}
