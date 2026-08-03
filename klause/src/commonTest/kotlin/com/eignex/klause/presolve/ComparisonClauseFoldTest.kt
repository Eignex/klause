package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparisonClauseFoldTest {

    private fun reif(aux: Int, v: Int, op: LinearOp, bound: Int) =
        ReifiedLinear(auxBoolVar = aux, coeffs = intArrayOf(1), vars = intArrayOf(v), op = op, bound = bound)

    private fun problemOf(numBool: Int, domains: Array<IntDomain>, factors: List<Factor>) =
        Problem(numBool, domains.size, domains, factors.toTypedArray())

    /** Enumerate a problem's solutions projected onto its integer variables. */
    private fun intSolutions(problem: Problem): HashSet<List<Long>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = 1L)).take(10_000).map { it.ints.toList() }.toHashSet()

    @Test
    fun `folds a reified LE disjunction into one ComparisonClause`() {
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                reif(0, 0, LinearOp.LE, 1),
                reif(1, 1, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val delta = Presolve.foldComparisonClauses(problem)
        assertEquals(3, delta.droppedIndices.size, "the clause and both reified definitions are consumed")
        assertEquals(1, delta.addedFactors.size)
        assertTrue(delta.addedFactors.single() is ComparisonClause)

        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        val brute = HashSet<List<Long>>()
        for (a in 0..3) for (b in 0..3) if (a <= 1 || b <= 1) brute.add(listOf(a.toLong(), b.toLong()))
        assertEquals(brute, intSolutions(reduced), "folded model must have the same integer solution set")
    }

    @Test
    fun `folds a negated indicator as the complement comparison`() {
        // Clause(not b0, b1) with b0 <-> (x0 <= 1) is (x0 >= 2) v (x1 <= 1).
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                reif(0, 0, LinearOp.LE, 1),
                reif(1, 1, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            ),
        )
        val delta = Presolve.foldComparisonClauses(problem)
        assertTrue(delta.addedFactors.single() is ComparisonClause)
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        val brute = HashSet<List<Long>>()
        for (a in 0..3) for (b in 0..3) if (a >= 2 || b <= 1) brute.add(listOf(a.toLong(), b.toLong()))
        assertEquals(brute, intSolutions(reduced))
    }

    @Test
    fun `does not fold when an indicator is shared by another factor`() {
        // b0 is used by a second clause too, so it cannot be dropped; neither clause folds through it.
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                reif(0, 0, LinearOp.LE, 1),
                reif(1, 1, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true))), // extra consumer of b0
            ),
        )
        val delta = Presolve.foldComparisonClauses(problem)
        assertTrue(delta.isEmpty, "a shared indicator must keep the reified encoding")
    }

    @Test
    fun `folds a reified body whose extra term is a fixed constant variable`() {
        // The FlatZinc shape: `b <-> (x - k <= 0)` with k a {1} constant var is `x <= 1`.
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(1, 1), IntDomain(1, 1))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val delta = Presolve.foldComparisonClauses(problem)
        assertTrue(delta.addedFactors.singleOrNull() is ComparisonClause, "constant term must fold into the bound")
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        val brute = HashSet<List<Long>>()
        for (a in 0..3) for (b in 0..3) if (a <= 1 || b <= 1) brute.add(listOf(a.toLong(), b.toLong(), 1L, 1L))
        assertEquals(brute, intSolutions(reduced))
    }

    @Test
    fun `does not fold a multi-variable reified comparison`() {
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2), // two-variable body
                reif(1, 2, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val delta = Presolve.foldComparisonClauses(problem)
        assertTrue(delta.isEmpty, "a multi-variable comparison is not a single-variable literal")
    }
}
