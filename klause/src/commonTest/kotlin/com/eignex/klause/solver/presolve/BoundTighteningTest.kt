package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Iterated activity-based bound tightening (FME bound propagation). The pass only ever narrows a
 * domain by a valid implication, so each test asserts the tightened bounds (or detected
 * infeasibility) the activity reasoning implies, independently of the construction-time bake — the
 * tightening is driven through [BoundTightening.tightenDomains] over a raw domains array.
 */
class BoundTighteningTest {

    private fun domains(vararg bounds: Pair<Int, Int>): Array<IntDomain> =
        Array(bounds.size) { IntDomain(bounds[it].first, bounds[it].second) }

    private fun tighten(factors: List<Factor>, domains: Array<IntDomain>): Array<IntDomain>? =
        BoundTightening.tightenDomains(factors.toTypedArray(), domains)

    @Test
    fun `a single inequality tightens an upper bound`() {
        // 3*x0 + x1 <= 7, x0 in [0,10], x1 in [0,1]: minActivityWithout(x0) = 0, so x0 <= 7/3 ⇒ x0 <= 2.
        val out = tighten(
            listOf(Linear(intArrayOf(3, 1), intArrayOf(0, 1), LinearOp.LE, 7)),
            domains(0 to 10, 0 to 1),
        )!!
        assertEquals(0, out[0].min)
        assertEquals(2, out[0].max)
    }

    @Test
    fun `an inequality tightens a lower bound through a negative coefficient`() {
        // -2*x0 + x1 <= -3, x0 in [0,10], x1 in [0,1]: minActivityWithout(x0) = 0, -2*x0 <= -3 ⇒
        // x0 >= ceil(3/2) = 2.
        val out = tighten(
            listOf(Linear(intArrayOf(-2, 1), intArrayOf(0, 1), LinearOp.LE, -3)),
            domains(0 to 10, 0 to 1),
        )!!
        assertEquals(2, out[0].min)
        assertEquals(10, out[0].max)
    }

    @Test
    fun `a fractional implied upper bound is floored and a lower bound ceiled`() {
        // 4*x0 <= 10, x0 in [0,9] ⇒ x0 <= floor(10/4) = 2 (not 2.5).
        val floored = tighten(listOf(Linear(intArrayOf(4), intArrayOf(0), LinearOp.LE, 10)), domains(0 to 9))!!
        assertEquals(2, floored[0].max)
        // 4*x0 >= 10, x0 in [0,9] ⇒ x0 >= ceil(10/4) = 3 (not 2.5).
        val ceiled = tighten(listOf(Linear(intArrayOf(4), intArrayOf(0), LinearOp.GE, 10)), domains(0 to 9))!!
        assertEquals(3, ceiled[0].min)
    }

    @Test
    fun `an equality tightens both bounds at once`() {
        // 2*x0 + x1 = 5, x0 in [0,9], x1 in [0,1]: x1=[0,1] forces 2*x0 in [4,5] ⇒ x0 = 2 (floor 5/2,
        // ceil 4/2).
        val out = tighten(
            listOf(Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 5)),
            domains(0 to 9, 0 to 1),
        )!!
        assertEquals(2, out[0].min)
        assertEquals(2, out[0].max)
    }

    @Test
    fun `coupled rows cascade to a tighter fixpoint than either row alone`() {
        // x0 - x1 <= 0 (x0 <= x1) and x1 <= 3, x0 in [0,9], x1 in [0,9]. First x1 <= 3, then x0 <= x1
        // pulls x0 <= 3 — a tightening only the second round, over the first round's tightened x1, reaches.
        val out = tighten(
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
            domains(0 to 9, 0 to 9),
        )!!
        assertEquals(3, out[0].max)
        assertEquals(3, out[1].max)
    }

    @Test
    fun `an unreachable bound is detected as infeasible`() {
        // x0 + x1 >= 10, both in [0,3]: maxActivity 6 < 10 ⇒ no assignment satisfies it.
        assertNull(
            tighten(
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 10)),
                domains(0 to 3, 0 to 3),
            ),
        )
    }

    @Test
    fun `a tightened lower bound above the upper bound is detected as infeasible`() {
        // 5*x0 >= 12 ⇒ x0 >= 3, and 5*x0 <= 8 ⇒ x0 <= 1: the two implied bounds cross.
        assertNull(
            tighten(
                listOf(
                    Linear(intArrayOf(5), intArrayOf(0), LinearOp.GE, 12),
                    Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 8),
                ),
                domains(0 to 9),
            ),
        )
    }

    @Test
    fun `nothing tightens when the rows are already at a bound fixpoint`() {
        // x0 + x1 <= 5 with x0,x1 in [0,2]: maxActivity 4 <= 5, so no bound is implied — a no-op.
        val input = domains(0 to 2, 0 to 2)
        val out = tighten(listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)), input)
        assertSame(input, out)
    }

    @Test
    fun `non-linear factors and not-equals rows carry no activity bound`() {
        // An AllDifferent and a != row bound nothing here; the activity pass leaves the domains alone.
        val input = domains(0 to 3, 0 to 3)
        val out = tighten(
            listOf(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 2),
            ),
            input,
        )
        assertSame(input, out)
    }

    @Test
    fun `the pass surfaces a proven-infeasible problem the way the bake does`() {
        // x0 + x1 >= 10 over [0,3] is infeasible; the pass appends an unsatisfiable row so the rebuilt
        // problem's construction-time bake folds to Unsat — the same signal every trivially-infeasible
        // problem raises.
        val problem = Problem(
            0,
            2,
            domains(0 to 3, 0 to 3),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 10)),
        )
        val out = Presolve.tightenBounds(problem)
        assertTrue(out.factors.size > problem.factors.size, "an infeasibility-witness row is posted")
        assertTrue(out.baked is PropagationResult.Unsat, "the rebuilt problem bakes to Unsat")
    }
}
