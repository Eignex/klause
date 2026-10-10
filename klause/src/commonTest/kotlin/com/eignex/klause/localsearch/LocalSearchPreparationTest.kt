package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalSearchPreparationTest {
    @Test
    fun `another arm completes shared preparation without repeating completed factors`() {
        var expired = false
        val visits = IntArray(512)
        val factors = Array<Factor>(visits.size) { fid ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override val boolVars: IntArray
                    get() {
                        visits[fid]++
                        if (fid == 255) expired = true
                        return clause.boolVars
                    }
            }
        }
        val problem = Problem(1, 0, emptyArray(), factors)
        visits.fill(0)
        expired = false
        val preparation = LocalSearchPreparation(problem, emptyArray())

        assertNull(preparation.get(Cancellation { expired }))
        assertEquals(256, visits.sum())
        val completed = assertNotNull(preparation.get(Cancellation.Never))

        assertContentEquals(IntArray(512) { it }, completed.boolOccurrences[0])
        assertTrue(visits.all { it == 2 })
        assertSame(completed, preparation.get(Cancellation { true }))
    }

    @Test
    fun `completed indexes preserve factor order and exclude inert invariants`() {
        val clause = Clause(intArrayOf(Lit.make(0, true)))
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                clause,
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
                Clause(intArrayOf(Lit.make(0, false))),
                object : Factor by clause, Propagator {},
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val preparation = LocalSearchPreparation(problem, arrayOf(IntDomain(0, 5)))

        val completed = assertNotNull(preparation.get(Cancellation.Never))

        assertContentEquals(intArrayOf(0, 2), completed.boolOccurrences[0])
        assertContentEquals(intArrayOf(1), completed.intOccurrences[0])
        assertContentEquals(intArrayOf(1), completed.realOccurrences[0])
        assertSame(NoInvariant, completed.invariants[3])
    }
}
