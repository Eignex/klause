package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deterministic work meter.
 *
 * The point of counting operations rather than timing them is that a budget keyed on the count behaves
 * the same on a loaded machine as on an idle one. That only holds if the count is a function of the
 * model and the pivot path alone, so these pin exactly that.
 */
class RevisedSimplexWorkTest {
    @Test
    fun `repair uses the retained invocation allowance and charges its completed overrun`() {
        var factorCalls = 0
        var repairAllowance: Long? = null
        val retainedLimit = 1000L
        RevisedSimplex(
            cover(),
            workLimit = 100000,
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        factorCalls++
                        return false
                    }

                    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
                        repairAllowance = control.maxWork
                        control.charge(assertNotNull(control.maxWork) + 7)
                        return null
                    }
                }
            },
        ).use { solver ->
            val result = solver.resolveBounds(LpFloatAllowance(retainedLimit, 100))

            assertNull(result)
            assertTrue(assertNotNull(repairAllowance) in 1 until retainedLimit)
            assertEquals(retainedLimit + 7, solver.lastWorkOps)
            assertEquals(1, factorCalls)
        }
    }

    /** A covering LP that costs several pivots from the all-slack start. */
    private fun cover(): LpModel {
        val b = LpBuilder()
        val x1 = b.addVar(0L, 10L, cost = 1L)
        val x2 = b.addVar(0L, 10L, cost = 1L)
        val x3 = b.addVar(0L, 10L, cost = 1L)
        val x4 = b.addVar(0L, 10L, cost = 1L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        b.addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        b.addRow(intArrayOf(x3, x4), longArrayOf(1L, 1L), Relation.GE, 5L)
        b.addRow(intArrayOf(x1, x4), longArrayOf(1L, 1L), Relation.GE, 2L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a solve charges work for what it did`() {
        val simplex = RevisedSimplex(cover())

        assertNotNull(simplex.solve(null))

        assertTrue(simplex.lastWorkOps > 0L, "a solve that pivots and factorizes cannot cost nothing")
    }

    @Test
    fun `a work budget stops the solve and still bounds below`() {
        val full = RevisedSimplex(cover())
        val fullResult = assertNotNull(full.solve(null))
        assertTrue(full.lastWorkOps > 2L, "fixture must cost enough work to halve")

        val capped = RevisedSimplex(cover(), workLimit = full.lastWorkOps / 2)
        val cappedResult = assertNotNull(capped.solve(null))

        assertTrue(cappedResult.optimal.not(), "a solve stopped on budget must not claim an optimum")
        assertTrue(
            cappedResult.objective <= fullResult.objective + 1e-9,
            "a dual-feasible iterate bounds below: ${cappedResult.objective} vs ${fullResult.objective}",
        )
    }

    @Test
    fun `measuring degeneracy for the budget does not charge the budget`() {
        val plain = RevisedSimplex(cover())
        assertNotNull(plain.solve(null))

        val tracking = RevisedSimplex(cover(), trackDegeneracy = true)
        assertNotNull(tracking.solve(null))

        // A meter that grew when the policy reading it was switched on would be measuring itself, and a
        // budget derived from it would depend on whether it was in use.
        assertEquals(
            plain.lastWorkOps,
            tracking.lastWorkOps,
            "the degeneracy pass is instrumentation for the policy, not work the solve did",
        )
    }

}
