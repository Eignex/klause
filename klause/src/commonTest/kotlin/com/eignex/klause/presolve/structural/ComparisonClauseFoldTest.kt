package com.eignex.klause.presolve.structural

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComparisonClauseFoldTest {

    @Test
    fun `declared reified comparisons fold without changing integer solutions`() {
        val first = reif(0, 0, LinearOp.LE, 1)
        val second = reif(1, 1, LinearOp.LE, 1)
        val model = problemOf(
            2,
            Array(2) { IntDomain(0, 2) },
            listOf(
                object : Factor by first, Propagator by first.propagatorProjection() {},
                object : Factor by second, Propagator by second.propagatorProjection() {},
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val baked = model.bake()

        val delta = Presolve.foldComparisonClauses(baked)

        assertEquals(3, delta.droppedIndices.size)
        assertEquals(intSolutions(model), intSolutions(baked.withPassDelta(delta, BakeConfig.NONE)))
    }

    private fun reif(aux: Int, v: Int, op: LinearOp, bound: Int) =
        ReifiedLinear(auxBoolVar = aux, coeffs = intArrayOf(1), vars = intArrayOf(v), op = op, bound = bound)

    private fun problemOf(
        numBool: Int,
        domains: Array<IntDomain>,
        factors: List<Factor>,
        openHi: BooleanArray? = null,
    ) = Problem(numBool, domains.size, domains, factors.toTypedArray(), openIntHi = openHi)

    /** `b0 ⇔ (x0 − x2 ≤ 0)`, `b1 ⇔ (x1 − x3 ≤ 0)` and `b0 ∨ b1`, with x2 and x3 declared `{1}` unless open above. */
    private fun constantShifted(openConstants: Boolean) = problemOf(
        numBool = 2,
        domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(1, 1), IntDomain(1, 1)),
        factors = listOf(
            ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.LE, 0),
            ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        ),
        openHi = booleanArrayOf(false, false, openConstants, openConstants),
    )

    @Test
    fun `the source form folds comparisons over open columns`() {
        val problem = problemOf(
            numBool = 2,
            domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                reif(0, 0, LinearOp.LE, 1),
                reif(1, 1, LinearOp.GE, 2),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ),
            openHi = booleanArrayOf(true, true),
        )

        val delta = ComparisonClauseFold.foldSource(problem)

        assertEquals(setOf(0, 1, 2), delta.droppedIndices.toSet())
        val folded = assertIs<ComparisonClause>(delta.addedFactors.single())
        assertContentEquals(intArrayOf(0, 1), folded.vars)
        assertContentEquals(arrayOf(LinearOp.LE, LinearOp.LE), folded.ops)
        assertContentEquals(longArrayOf(1, 1), folded.consts)
    }

    @Test
    fun `the source form moves a declared constant term into the bound`() {
        val delta = ComparisonClauseFold.foldSource(constantShifted(openConstants = false))

        val folded = assertIs<ComparisonClause>(delta.addedFactors.single())
        assertContentEquals(longArrayOf(1, 1), folded.consts)
    }

    @Test
    fun `the source form keeps a term over an open column out of the bound`() {
        val delta = ComparisonClauseFold.foldSource(constantShifted(openConstants = true))

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `an indicator the objective weighs keeps its reified definition`() {
        val problem = constantShifted(openConstants = false)

        val delta = ComparisonClauseFold.foldSource(problem, objectiveBoolVars = setOf(1))

        assertTrue(delta.isEmpty)
    }

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
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertEquals(3, delta.droppedIndices.size, "the clause and both reified definitions are consumed")
        assertEquals(1, delta.addedFactors.size)
        assertTrue(delta.addedFactors.single() is ComparisonClause)

        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
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
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertTrue(delta.addedFactors.single() is ComparisonClause)
        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
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
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
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
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertTrue(delta.addedFactors.singleOrNull() is ComparisonClause, "constant term must fold into the bound")
        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
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
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertTrue(delta.isEmpty, "a multi-variable comparison is not a single-variable literal")
    }
}
