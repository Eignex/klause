package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PresolveSession] applies pass deltas incrementally against one persistent [PropagationState] and
 * materializes the solver [Problem] once at the end. Each test checks the incremental result against
 * an independent one-shot [PresolveShared.rebuildProblem] over the same final factor set + narrowings —
 * they must agree because the propagators are monotone (the greatest fixpoint is unique).
 */
class PresolveSessionTest {

    private fun domains() = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10))

    private fun leq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)

    private fun base(vararg factors: Factor) = Problem(0, 3, domains(), factors.toList())

    private fun bounds(problem: Problem) = (0 until problem.numIntVars).map {
        problem.requireFiniteIntDomains()[it].min to
            problem.requireFiniteIntDomains()[it].max
    }

    @Test
    fun `dropping a redundant factor and adding one matches a one-shot build`() {
        val f0 = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val f1 = leq(intArrayOf(1), intArrayOf(0), 7) // x0 <= 7, redundant given f0
        val f2 = leq(intArrayOf(1, -1), intArrayOf(1, 0), 0) // x1 <= x0
        val g = leq(intArrayOf(1, -1), intArrayOf(2, 1), 0) // x2 <= x1

        val session = PresolveSession(base(f0, f1, f2))
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(1), addedFactors = listOf(g))))
        val got = session.materialize()

        val reference = PresolveShared.rebuildProblem(base(f0, f2, g), listOf(f0, f2, g), domains())
        assertEquals(bounds(reference), bounds(got))
        assertEquals(3, got.factors.size)
        // x2 upper-bounded through the chain
        val x2 = got.requireFiniteIntDomains()[2]
        assertEquals(0L to 5L, x2.min to x2.max)
    }

    @Test
    fun `a pushed domain narrowing propagates and matches a one-shot build`() {
        val f0 = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val f2 = leq(intArrayOf(1, -1), intArrayOf(1, 0), 0) // x1 <= x0
        val g = leq(intArrayOf(1, -1), intArrayOf(2, 1), 0) // x2 <= x1

        val session = PresolveSession(base(f0, f2))
        assertTrue(session.apply(PresolveDelta(addedFactors = listOf(g))))
        // A dual-fix-style direct narrowing: x0 <= 3, not implied by any factor.
        val narrowed = arrayOf(IntDomain(0, 3), IntDomain(0, 10), IntDomain(0, 10))
        assertTrue(session.apply(PresolveDelta(domains = narrowed)))
        val got = session.materialize()

        val reference = PresolveShared.rebuildProblem(
            base(f0, f2, g),
            listOf(f0, f2, g),
            arrayOf(IntDomain(0, 3), IntDomain(0, 10), IntDomain(0, 10)),
        )
        assertEquals(bounds(reference), bounds(got))
        // x2 upper-bounded through the chain
        val x2 = got.requireFiniteIntDomains()[2]
        assertEquals(0L to 3L, x2.min to x2.max)
    }

    @Test
    fun `a conflicting narrowing latches infeasibility`() {
        val f0 = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val g = leq(intArrayOf(-1), intArrayOf(0), -8) // -x0 <= -8, i.e. x0 >= 8

        val session = PresolveSession(base(f0))
        assertFalse(session.apply(PresolveDelta(addedFactors = listOf(g)))) // 8 <= x0 <= 5 is infeasible
        assertTrue(session.infeasible)
    }
}
