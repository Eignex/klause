package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingletonInequalityProjectionTest {

    /** `x + y <= 10` with `x` only there, declared `lo..9` and open below when [openBelow] is set. */
    private fun sourceModel(openBelow: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(2, 9), IntDomain(0, 20)),
        factors = listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)),
        openIntLo = booleanArrayOf(openBelow, false),
    )

    @Test
    fun `the source form pins a column whose relaxing side is closed`() {
        val delta = SingletonInequalityProjection.projectSource(sourceModel(openBelow = false), emptySet())
        val ints = longArrayOf(0, 8)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertEquals(8L, (delta.addedFactors.single() as Linear).integerConstants!!.bound, "y <= 10 - 2")
        assertEquals(2L, ints[0])
    }

    @Test
    fun `the source form drops the row of a column free on its relaxing side`() {
        val delta = SingletonInequalityProjection.projectSource(sourceModel(openBelow = true), emptySet())
        val ints = longArrayOf(0, 15)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertTrue(delta.addedFactors.isEmpty(), "x can always absorb the row, so nothing remains of it")
        assertEquals(-5L, ints[0], "x + 15 <= 10")
    }

    @Test
    fun `a declared singleton inequality reconstructs its projected variable`() {
        val source = Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 10)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {}
        val model = problem(arrayOf(IntDomain(0, 5), IntDomain(0, 20)), factor)

        val delta = SingletonInequalityProjection.project(model, objectiveIntVars = setOf(1))

        assertEquals(1, delta.droppedIndices.size)
        val sample = delta.reconstruct!!(Sample(bools = booleanArrayOf(), ints = longArrayOf(99, 5)))
        assertEquals(0L, sample.ints[0])
        assertTrue(isFeasible(model, sample))
    }

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList()).bake()

    private fun isFeasible(p: Problem, s: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until p.numIntVars) a = a.withInt(v, s.ints[v])
        return p.propagate(a) !is PropagationResult.Unsat
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
    fun `does not project a variable used by another constraint`() {
        // x (var 0) appears in both inequalities ⇒ not a singleton column.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20), IntDomain(0, 20)),
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
            Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 10),
        )
        val d = SingletonInequalityProjection.project(p, emptySet())
        // Only the pure singletons y (var1) and z (var2) are projected; both survivors are still on x.
        assertEquals(listOf(listOf(0), listOf(0)), d.addedFactors.map { (it as Linear).vars.toList() })
        val rebuilt = d.reconstruct!!(Sample(bools = BooleanArray(0), ints = longArrayOf(4, 6, 6)))
        assertEquals(4L, rebuilt.ints[0], "x must not be pinned by the projection")
    }
}
