package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiophantineReductionTest {
    @Test
    fun `already cancelled source and finite passes should leave the model unchanged without charging work`() {
        val source = sourceProblem(xHi = 7)
        val finite = source.bake()
        for (sourceLane in listOf(true, false)) {
            val budget = PresolveBudget(1000)
            var polls = 0
            val token = budget.orSpent(Cancellation { polls++; true })

            if (sourceLane) {
                val delta = DiophantineReduction.reduceSource(source, token)
                assertTrue(delta.isEmpty)
                assertFalse(delta.infeasible)
            } else {
                val delta = DiophantineReduction.reduce(finite, token)
                assertTrue(delta.isEmpty)
                assertFalse(delta.infeasible)
            }

            assertTrue(polls > 0)
            assertEquals(0L, budget.spent())
        }
    }

    @Test
    fun `exhaustion during residue extraction should not publish an unfinished refutation`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )
        val finite = source.bake()
        for (sourceLane in listOf(true, false)) {
            val budget = PresolveBudget(1)
            val token = budget.orSpent(Cancellation.Never)

            if (sourceLane) {
                val delta = DiophantineReduction.reduceSource(source, token)
                assertTrue(delta.isEmpty)
                assertFalse(delta.infeasible)
            } else {
                val delta = DiophantineReduction.reduce(finite, token)
                assertTrue(delta.isEmpty)
                assertFalse(delta.infeasible)
            }

            assertEquals(30L, budget.spent())
        }
    }

    @Test
    fun `cancellation within a wide coefficient scan should stop residue extraction`() {
        val width = 192
        val source = Problem(
            0,
            width,
            Array(width) { IntDomain(-1, 1) },
            listOf(Linear(LongArray(width) { 2 }, IntArray(width) { it }, LinearOp.EQ, 0)),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        var extractionPolls = 0
        val token = budget.orSpent(Cancellation { budget.spent() > 0 && ++extractionPolls >= 3 })

        val delta = DiophantineReduction.reduceSource(source, token)

        assertEquals(3, extractionPolls)
        assertEquals(10L * (1 + width), budget.spent())
        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `cancellation during source narrowing should retain only completed bounds`() {
        val source = Problem(
            0,
            6,
            Array(6) { if (it % 3 == 0) IntDomain(0, 7) else IntDomain(0, 2) },
            listOf(
                Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10),
                Linear(longArrayOf(2, 3, 3), intArrayOf(3, 4, 5), LinearOp.EQ, 10),
            ),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        var cancelled = false
        val token = budget.orSpent(Cancellation { (budget.spent() > 80).also { cancelled = it } })

        val delta = DiophantineReduction.reduceSource(source, token)

        assertTrue(cancelled)
        assertEquals(81L, budget.spent())
        val bounds = assertNotNull(delta.bounds)
        assertEquals(2, bounds.lower(0))
        assertEquals(5, bounds.upper(0))
        assertEquals(0, bounds.lower(3))
        assertEquals(7, bounds.upper(3))
        assertFalse(delta.infeasible)
    }

    @Test
    fun `interrupted finite scans should charge bounded chunks and retain only completed carves`() {
        val model = problem(
            arrayOf(IntDomain(0, 3), IntDomain(-1, 0), IntDomain(0, 4095), IntDomain(-2047, 0)),
            Linear(longArrayOf(1, 2), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(longArrayOf(1, 2), intArrayOf(2, 3), LinearOp.EQ, 0),
        )
        for (workLimited in listOf(true, false)) {
            val budget = PresolveBudget(if (workLimited) 64 else Long.MAX_VALUE)
            var polls = 0
            val token = budget.orSpent(Cancellation { polls++; !workLimited && budget.spent() >= 64 })

            val delta = DiophantineReduction.reduce(model, token)

            assertTrue(polls > 0)
            assertTrue(budget.spent() in 64L..127L)
            val domains = assertNotNull(delta.domains)
            assertTrue(0L in domains[0] && 2L in domains[0] && 1L !in domains[0])
            assertEquals(model.rootIntDomain(2), domains[2])
            assertTrue(1L in domains[2])
            assertTrue(delta.addedFactors.isEmpty())
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `finite congruence reduction should charge every visited value including the final short chunk`() {
        val model = problem(
            arrayOf(IntDomain(0, 4095), IntDomain(-2047, 0)),
            Linear(longArrayOf(1, 2), intArrayOf(0, 1), LinearOp.EQ, 0),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        var polls = 0
        val token = budget.orSpent(Cancellation { polls++; false })

        val delta = DiophantineReduction.reduce(model, token)

        assertEquals(30L + model.rootIntDomain(0).span().size, budget.spent())
        assertTrue(polls >= 64)
        val domain = assertNotNull(delta.domains)[0]
        for (value in 0L..4094L) assertEquals(value % 2 == 0L, value in domain)
        assertTrue(delta.addedFactors.isEmpty())
    }

    @Test
    fun `an implied equality should carve off-residue values without replacing its factor`() {
        val source = Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {
            override val linearForm: LinearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = problem(arrayOf(IntDomain(0, 5), IntDomain(0, 2), IntDomain(0, 2)), factor)

        val delta = DiophantineReduction.reduce(model)

        val domain = assertNotNull(delta.domains)[0]
        assertTrue(3L !in domain && 4L !in domain)
        assertTrue(2L in domain && 5L in domain)
        assertTrue(delta.droppedIndices.isEmpty())
    }

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList()).bake()

    private fun sourceProblem(xHi: Long, openX: Boolean = false) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, 2), IntDomain(0, 2)),
        factors = listOf(Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10)),
        openIntHi = booleanArrayOf(openX, false, false),
    )

    @Test
    fun `source reduction should move each closed side to the nearest in-class value`() {
        // x = 2 (mod 3), so the declared 0..7 narrows to 2..5.
        val bounds = assertNotNull(DiophantineReduction.reduceSource(sourceProblem(xHi = 7)).bounds)

        assertEquals(2, bounds.lower(0))
        assertEquals(5, bounds.upper(0))
    }

    @Test
    fun `source reduction should leave an open side open`() {
        val bounds = assertNotNull(DiophantineReduction.reduceSource(sourceProblem(xHi = 0, openX = true)).bounds)

        assertEquals(2, bounds.lower(0))
        assertTrue(bounds.isOpenUpper(0))
    }

    @Test
    fun `source reduction should refute a congruence with no solution`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )

        assertTrue(DiophantineReduction.reduceSource(p).infeasible)
    }
}
