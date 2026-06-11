package com.eignex.klause.solver

import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Symmetry breaking (#317). The non-negotiable property is **soundness**: breaking must never
 * turn a satisfiable problem unsatisfiable. Every test enumerates the whole assignment space and
 * compares feasible-solution counts before and after; the broken problem must keep ≥1 solution per
 * orbit (so counts agree on satisfiability) and only ever remove solutions.
 */
class SymmetryBreakingTest {

    private fun isFeasible(problem: Problem, bools: BooleanArray, ints: IntArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Count feasible assignments over the full (contiguous-domain) space; capped for safety. */
    private fun countFeasible(problem: Problem): Int {
        val b = problem.numBoolVars
        val n = problem.numIntVars
        val ints = IntArray(n) { problem.intDomains[it].min }
        var count = 0
        while (true) {
            for (mask in 0 until (1 shl b).coerceAtLeast(1)) {
                val bools = BooleanArray(b) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, bools, ints.copyOf())) count++
            }
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

    private fun checkSound(name: String, problem: Problem, expectReduced: Boolean) {
        val broken = Presolve.breakSymmetries(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after <= orig, "$name: breaking ADDED solutions ($orig -> $after)")
        assertEquals(orig > 0, after > 0, "$name: breaking changed satisfiability ($orig -> $after)")
        if (expectReduced) {
            assertTrue(after < orig, "$name: expected fewer solutions but $orig -> $after")
        } else {
            assertSame(problem, broken, "$name: expected no symmetry detected")
        }
    }

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `interchangeable matrix rows are lex-ordered`() {
        // Two rows: x0 + 2·x1 ≤ 3 and x2 + 2·x3 ≤ 3. The rows are interchangeable as blocks, but the
        // cells within a row are NOT (different coefficients) — so this is block/row symmetry, broken
        // by a lex-leader between the rows rather than per-variable ordering.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        checkSound("matrix-rows", problem, expectReduced = true)
    }

    @Test
    fun `verified detection orders interchangeable vars in separate isomorphic factors`() {
        // x0 in (x0 <= 3) and x1 in (x1 <= 3): different factors, but swapping x0/x1 preserves the
        // factor set, so they ARE interchangeable. The same-factor-set heuristic misses this;
        // verified detection (remap + structural key) catches it and orders them.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        checkSound("cross-factor", problem, expectReduced = true)
    }

    @Test
    fun `asymmetric separate factors are not grouped`() {
        // x0 <= 3, x1 <= 4: NOT interchangeable (swapping changes the bounds). Must not reduce.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )
        checkSound("asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `interchangeable alldifferent variables are ordered`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        checkSound("alldiff", problem, expectReduced = true)
        // 3! permutations collapse to the single sorted one.
        assertEquals(1, countFeasible(Presolve.breakSymmetries(problem)))
    }

    @Test
    fun `equal-coefficient sum variables are interchangeable`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 2)),
        )
        checkSound("equalCoeffSum", problem, expectReduced = true)
    }

    @Test
    fun `interchangeable booleans in a cardinality are ordered`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 2, max = 3)),
        )
        checkSound("cardBools", problem, expectReduced = true)
    }

    @Test
    fun `unequal coefficients are not grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("unequalCoeff", problem, expectReduced = false)
    }

    @Test
    fun `same role in different factors is not grouped`() {
        // x0 in (x0 <= 1); x1 in (x1 <= 2). Same role token but different factors and bounds —
        // grouping them would be unsound, so the factorId in the role key must keep them apart.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
            ),
        )
        checkSound("differentFactors", problem, expectReduced = false)
    }

    @Test
    fun `different domains block grouping`() {
        // Same role token but different domains ⇒ not interchangeable.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("differentDomains", problem, expectReduced = false)
    }
}
