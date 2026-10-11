package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveSessionTest {

    private fun domains() = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10))

    private fun leq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)

    private fun base(vararg factors: Factor) = Problem(0, 3, domains(), factors.toList()).bake()

    private fun unitBase() = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true))))).bake()

    private fun rootBool(problem: Problem, v: Int) = (problem.baked as PropagationResult.Implied).boolValueOrNull(v)

    @Test
    fun `incremental delta propagation consumes native work without charging prior deltas twice`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val session = PresolveSession(base(), cancellation = budget.orSpent(Cancellation.Never))
        val before = budget.spent()

        session.apply(PresolveDelta(addedFactors = listOf(leq(intArrayOf(1), intArrayOf(0), 5))))
        session.apply(PresolveDelta(addedFactors = listOf(leq(intArrayOf(-1), intArrayOf(1), -2))))

        assertEquals(7L, budget.rootConstructionEntries)
        assertTrue(budget.rootPropagationWork > 0L)
        assertTrue(budget.spent() > before)
        assertEquals(53L * (budget.rootConstructionEntries + budget.rootPropagationWork), budget.spent())
    }

    @Test
    fun `incremental conflict returns retain their native charges`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val session = PresolveSession(unitBase(), cancellation = budget.orSpent(Cancellation.Never))
        val work = budget.rootPropagationWork

        val feasible = session.apply(PresolveDelta(addedFactors = listOf(Clause(intArrayOf(Lit.make(0, false))))))

        assertFalse(feasible)
        assertTrue(session.infeasible)
        assertTrue(budget.rootPropagationWork > work)
        assertEquals(53L * (budget.rootConstructionEntries + budget.rootPropagationWork), budget.spent())
    }

    @Test
    fun `incremental views and materialization keep the exhausted phase token`() {
        val budget = PresolveBudget(1)
        val token = budget.orSpent(Cancellation.Never)
        val session = PresolveSession(unitBase(), cancellation = token)

        val input = session.passInput()
        val result = session.materialize()

        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.probeCalls)
        assertSame(token.workMeter(), input.cancellation.workMeter())
        assertSame(token.workMeter(), result.cancellation.workMeter())
        assertEquals(true, rootBool(result, 0))
        assertFalse(session.infeasible)
    }

    @Test
    fun `a pass input keeps a boolean fixed by a dropped factor`() {
        val session = PresolveSession(unitBase())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0))))

        assertEquals(true, rootBool(session.passInput(), 0))
    }

    @Test
    fun `the materialized problem keeps a boolean fixed by a dropped factor`() {
        val session = PresolveSession(unitBase())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0))))

        assertEquals(true, rootBool(session.materialize(), 0))
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
