package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CutoffBoundTest {

    /** Two integer columns over the invented box; [openHi] / [openLo] mark column 0's invented sides. */
    private fun problem(openHi: Boolean = true, openLo: Boolean = false, hi: Long = 1_000_000L): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(if (openLo) -1_000_000L else 0L, hi), IntDomain(0L, 10L)),
        factors = emptyList(),
        openIntLo = booleanArrayOf(openLo, false),
        openIntHi = booleanArrayOf(openHi, false),
    )

    private val objective = LinearObjective(intCoefficients = longArrayOf(3L, 1L))

    @Test
    fun `an open column should close at the steps the incumbent still pays for`() {
        // Minimum over the domains is 0; a solution beating 31 spends at most 30 on 3*x0 so x0 <= 10.
        val bounds = objectiveCutoffBounds(problem(), objective, incumbent = 31L)

        assertEquals(1, bounds.size)
        assertEquals(0, bounds[0].varId)
        assertEquals(10L, bounds[0].hi)
    }

    @Test
    fun `a column already bounded below the cutoff should stay as it is`() {
        val bounds = objectiveCutoffBounds(problem(hi = 4L), objective, incumbent = 31L)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `a column the box also opened below should stay open`() {
        val bounds = objectiveCutoffBounds(problem(openLo = true), objective, incumbent = 31L)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `a column the model itself bounds should stay untouched`() {
        val bounds = objectiveCutoffBounds(problem(openHi = false), objective, incumbent = 31L)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `a costless open column should stay open`() {
        val free = LinearObjective(intCoefficients = longArrayOf(0L, 1L))

        val bounds = objectiveCutoffBounds(problem(), free, incumbent = 31L)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `an incumbent nothing can beat should yield no bound at all`() {
        val bounds = objectiveCutoffBounds(problem(), objective, incumbent = 0L)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `a term against the invented box should not wrap the objective floor`() {
        // The second column reaches -3*2^61 so the gap to the incumbent exceeds a Long; only exact
        // 128-bit accumulation still reads a bound off it instead of a wrapped negative gap.
        val boxed = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0L, 1L shl 62), IntDomain(-(1L shl 61), 0L)),
            factors = emptyList(),
            openIntHi = booleanArrayOf(true, false),
        )

        val bounds = objectiveCutoffBounds(boxed, LinearObjective(intCoefficients = longArrayOf(3L, 3L)), 4e18.toLong())

        assertEquals(1, bounds.size)
        assertEquals(3639176342547027285L, bounds[0].hi)
    }

    @Test
    fun `minimize should still reach the optimum once the cutoff closes an open column`() {
        // x0 + x1 >= 5 with x0 open above and priced at 3: the optimum spends x1 first. The root
        // tightening runs against each incumbent it finds on the way there.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0L, 100L), IntDomain(0L, 10L)),
            factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5)),
            openIntHi = booleanArrayOf(true, false),
        )

        val r = BacktrackSolver(p.bake()).minimize(objective, BacktrackParams(randomSeed = 1L))

        assertEquals(5.0, assertIs<MinimizeResult.Optimal>(r).objective)
    }
}
