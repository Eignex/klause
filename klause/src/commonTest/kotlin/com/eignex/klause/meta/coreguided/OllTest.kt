package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
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
    ).bake()
    private val softs = listOf(Oll.Soft(Lit.make(0, true)), Oll.Soft(Lit.make(1, true)))

    @Test
    fun `soft cost should count the weight of every unsatisfied soft`() {
        val both = Sample(bools = booleanArrayOf(false, false), ints = longArrayOf())
        val one = Sample(bools = booleanArrayOf(true, false), ints = longArrayOf())
        val none = Sample(bools = booleanArrayOf(true, true), ints = longArrayOf())
        assertEquals(2L, Oll.softCost(both, softs))
        assertEquals(1L, Oll.softCost(one, softs))
        assertEquals(0L, Oll.softCost(none, softs))
    }

    @Test
    fun `optimal sample recovery should replace an over-relaxed witness with a model costing the lower bound`() {
        val overRelaxed = Sample(bools = booleanArrayOf(false, false), ints = longArrayOf())
        val recovered = Oll.recoverOptimalSample(mutex01, softs, overRelaxed, lb = 1L, BacktrackParams())
        assertEquals(1L, Oll.softCost(recovered, softs))
        assertTrue(recovered.bools[0] != recovered.bools[1])
    }

    @Test
    fun `optimal sample recovery should keep a witness that already costs the lower bound`() {
        val good = Sample(bools = booleanArrayOf(true, false), ints = longArrayOf())
        val recovered = Oll.recoverOptimalSample(mutex01, softs, good, lb = 1L, BacktrackParams())
        assertSame(good, recovered)
    }
}
