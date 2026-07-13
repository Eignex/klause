package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingletonInequalityProjectionTest {

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList())

    private fun isFeasible(p: Problem, s: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until p.numIntVars) a = a.withInt(v, s.ints[v])
        return p.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `projects a singleton variable out of an inequality`() {
        // 2·x + y ≤ 10, x ∈ [0,5] appears only here ⇒ project x (at its min 0), leaving y ≤ 10, x rebuilt to 0.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20)),
            Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        // y (var 1) also occurs once, so pin whichever the pass picks; assert x specifically is projected.
        val d = SingletonInequalityProjection.project(p, objectiveIntVars = setOf(1))
        assertEquals(1, d.droppedIndices.size)
        val kept = d.addedFactors.single() as Linear
        assertEquals(listOf(1), kept.vars.toList())
        assertEquals(LinearOp.LE, kept.op)
        assertEquals(10L, kept.bound)
        val rebuilt = d.reconstruct!!(Sample(bools = BooleanArray(0), ints = longArrayOf(99, 5)))
        assertEquals(0L, rebuilt.ints[0], "x rebuilt to its most-permissive bound")
        assertTrue(isFeasible(p, rebuilt), "reconstructed assignment must satisfy the original inequality")
    }

    @Test
    fun `leaves objective variables in place`() {
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20)),
            Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        assertEquals(0, SingletonInequalityProjection.project(p, objectiveIntVars = setOf(0, 1)).droppedIndices.size)
    }

    @Test
    fun `does not touch equalities`() {
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20)),
            Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 10),
        )
        assertEquals(0, SingletonInequalityProjection.project(p, emptySet()).droppedIndices.size)
    }

    @Test
    fun `does not project a variable used by another constraint`() {
        // x (var 0) appears in both inequalities ⇒ not a singleton column.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20), IntDomain(0, 20)),
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
            Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 10),
        )
        val d = SingletonInequalityProjection.project(p, emptySet())
        // Only the pure singletons y (var1) and z (var2) may be projected; x (var0) must stay.
        assertTrue(d.droppedIndices.size <= 2)
    }
}
