package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Constraint subsumption (#447). Removing a redundant constraint must preserve the feasible set
 * *exactly* — every test enumerates the whole assignment space and compares the count before and
 * after, and asserts the expected drop (or no-op).
 */
class SubsumptionTest {

    private fun isFeasible(problem: Problem, ints: IntArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun feasibleCount(problem: Problem): Int {
        val n = problem.numIntVars
        val ints = IntArray(n) { problem.intDomains[it].min }
        var count = 0
        while (true) {
            if (isFeasible(problem, ints.copyOf())) count++
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.intDomains[i].max) break
                ints[i] = problem.intDomains[i].min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun checkPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val out = Presolve.removeRedundantConstraints(problem)
        assertEquals(feasibleCount(problem), feasibleCount(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertSame(problem, out, "$name: expected no change")
        }
        return out
    }

    private fun dom(n: Int, hi: Int) = Array(n) { IntDomain(0, hi) }
    private fun le(b: Int, vararg vc: Int) =
        Linear(IntArray(vc.size / 2) { vc[2 * it + 1] }, IntArray(vc.size / 2) { vc[2 * it] }, LinearOp.LE, b)

    @Test
    fun `dominated less-equal constraint is dropped`() {
        // x + y <= 3 implies x + y <= 5.
        val problem = Problem(
            0,
            2,
            dom(2, 5),
            listOf(le(5, 0, 1, 1, 1), le(3, 0, 1, 1, 1)),
        )
        val out = checkPreserved("dominated-le", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
        assertEquals(3, (out.factors[0] as Linear).bound, "the tighter bound is kept")
    }

    @Test
    fun `dominated greater-equal constraint is dropped`() {
        // x >= 4 implies x >= 2; the tighter (>=4) survives.
        val problem = Problem(
            0,
            1,
            dom(1, 5),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val out = checkPreserved("dominated-ge", problem, expectDrop = true)
        assertEquals(4, (out.factors.single() as Linear).bound)
    }

    @Test
    fun `equality dominates the matching inequalities`() {
        // x = 3 implies both x <= 5 and x >= 1 — both inequalities drop, the equality stays.
        val problem = Problem(
            0,
            1,
            dom(1, 5),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val out = checkPreserved("eq-dominates", problem, expectDrop = true)
        assertEquals(LinearOp.EQ, (out.factors.single() as Linear).op)
    }

    @Test
    fun `exact-duplicate constraints are removed`() {
        // Two identical rows and two identical AllDifferents → one of each survives (structuralKey).
        val problem = Problem(
            0,
            3,
            dom(3, 2),
            listOf(
                le(2, 0, 1, 1, 1),
                le(2, 0, 1, 1, 1),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
            ),
        )
        val out = checkPreserved("exact-dup", problem, expectDrop = true)
        assertEquals(2, out.factors.size)
    }

    @Test
    fun `independent constraints are left untouched`() {
        // Different variables / opposite directions ⇒ not comparable ⇒ no-op (same problem instance).
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(le(3, 1, 0), le(3, 1, 1)),
        )
        checkPreserved("independent", problem, expectDrop = false)
    }

    @Test
    fun `negated-equivalent inequalities are deduplicated`() {
        // x + y <= 3 and -x - y >= -3 are the same half-space; one survives.
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(
                le(3, 0, 1, 1, 1),
                Linear(intArrayOf(-1, -1), intArrayOf(0, 1), LinearOp.GE, -3),
            ),
        )
        val factorsBefore = problem.factors
        val out = checkPreserved("negated-equiv", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
        assertTrue(out.factors.single() in factorsBefore, "a surviving original is kept verbatim")
    }
}
