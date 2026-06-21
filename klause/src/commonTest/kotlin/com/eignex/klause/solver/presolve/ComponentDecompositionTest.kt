package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Connected-component decomposition. The partition is purely structural, so each test asserts the
 * grouping of variables and factors — two variables share a component iff they are linked through a
 * chain of co-occurrences — never any rewrite of the problem.
 */
class ComponentDecompositionTest {

    private fun dom(n: Int, hi: Int) = Array(n) { IntDomain(0, hi) }
    private fun le(b: Int, vararg vc: Int) =
        Linear(IntArray(vc.size / 2) { vc[2 * it + 1] }, IntArray(vc.size / 2) { vc[2 * it] }, LinearOp.LE, b)

    /** The set of int-var components, each as the sorted set of int vars it contains. */
    private fun intGroups(c: ProblemComponents): Set<Set<Int>> {
        val byComp = HashMap<Int, MutableSet<Int>>()
        for (v in c.componentOfInt.indices) byComp.getOrPut(c.componentOfInt[v]) { mutableSetOf() }.add(v)
        return byComp.values.map { it.toSet() }.toSet()
    }

    @Test
    fun `two independent subproblems split into two components`() {
        // {x0,x1} co-occur in one row, {x2,x3} in another; the two rows share no variable.
        val problem = Problem(0, 4, dom(4, 3), listOf(le(3, 0, 1, 1, 1), le(3, 2, 1, 3, 1)))
        val c = Presolve.decomposeComponents(problem)
        assertEquals(2, c.count)
        assertFalse(c.isConnected)
        assertEquals(setOf(setOf(0, 1), setOf(2, 3)), intGroups(c))
    }

    @Test
    fun `a chain of overlapping factors links everything into one component`() {
        // x0-x1, x1-x2, x2-x3: each row overlaps the next, so the transitive closure is the whole set.
        val problem = Problem(
            0,
            4,
            dom(4, 3),
            listOf(le(3, 0, 1, 1, 1), le(3, 1, 1, 2, 1), le(3, 2, 1, 3, 1)),
        )
        val c = Presolve.decomposeComponents(problem)
        assertEquals(1, c.count)
        assertTrue(c.isConnected)
        assertEquals(setOf(setOf(0, 1, 2, 3)), intGroups(c))
    }

    @Test
    fun `an isolated variable is its own component`() {
        // x2 appears in no factor, so it is a singleton component alongside the {x0,x1} row.
        val problem = Problem(0, 3, dom(3, 3), listOf(le(3, 0, 1, 1, 1)))
        val c = Presolve.decomposeComponents(problem)
        assertEquals(2, c.count)
        assertEquals(setOf(setOf(0, 1), setOf(2)), intGroups(c))
    }

    @Test
    fun `a variable shared across two factor clusters merges them`() {
        // {x0,x1} and {x2,x3} would be disjoint, but x4 occurs in a factor with each, bridging them.
        val problem = Problem(
            0,
            5,
            dom(5, 3),
            listOf(le(3, 0, 1, 1, 1), le(3, 2, 1, 3, 1), le(3, 0, 1, 4, 1), le(3, 2, 1, 4, 1)),
        )
        val c = Presolve.decomposeComponents(problem)
        assertEquals(1, c.count)
        assertEquals(setOf(setOf(0, 1, 2, 3, 4)), intGroups(c))
    }

    @Test
    fun `a reified mixed factor links a boolean and an integer variable`() {
        // A clause over b0,b1 and a row over x0,x1 are disjoint until a reified row ties b1 to x0.
        val problem = Problem(
            2,
            2,
            dom(2, 3),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                le(3, 0, 1, 1, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )
        val c = Presolve.decomposeComponents(problem)
        assertEquals(1, c.count)
        assertEquals(c.componentOfBool[1], c.componentOfInt[0], "the bridged bool and int share a component")
    }

    @Test
    fun `each factor is grouped under its component`() {
        // Two independent rows: each component owns exactly the factor that built it.
        val problem = Problem(0, 4, dom(4, 3), listOf(le(3, 0, 1, 1, 1), le(3, 2, 1, 3, 1)))
        val c = Presolve.decomposeComponents(problem)
        val factorCounts = c.componentFactors.map { it.size }.sorted()
        assertEquals(listOf(1, 1), factorCounts)
        assertTrue(c.factorlessFactors.isEmpty())
    }
}
