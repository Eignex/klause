package com.eignex.klause.presolve.structural

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
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
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComparisonClauseFoldTest {
    @Test
    fun `source and finite folding should restore every original comparison independently of clause polarity`() {
        for (op in LinearOp.entries) for (positive in listOf(false, true)) {
            val model = problemOf(
                2,
                Array(2) { IntDomain(0, 2) },
                listOf(
                    reif(0, 0, op, 1),
                    reif(1, 1, LinearOp.EQ, 1),
                    Clause(intArrayOf(Lit.make(0, positive), Lit.make(1, true))),
                ),
            )
            val source = ComparisonClauseFold.foldSource(model)
            val finite = ComparisonClauseFold.fold(model.bake())
            for (x in 0L..2L) {
                val expected = when (op) {
                    LinearOp.LE -> x <= 1L
                    LinearOp.EQ -> x == 1L
                    LinearOp.GE -> x >= 1L
                    LinearOp.NE -> x != 1L
                }
                val sample = Sample(booleanArrayOf(!expected, false), longArrayOf(x, 1))
                val restored = listOf(
                    assertNotNull(source.rebuild.asSampleLift())(sample),
                    assertNotNull(finite.reconstruct)(sample),
                )

                for (point in restored) {
                    assertContentEquals(booleanArrayOf(expected, true), point.bools, "$op $positive $x")
                    assertContentEquals(sample.ints, point.ints)
                }
                assertContentEquals(booleanArrayOf(!expected, false), sample.bools)
            }
        }
    }

    @Test
    fun `folding should reconstruct indicators from every consumed clause with signed coefficients and constants`() {
        val model = problemOf(
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(1, 1)),
            listOf(
                ReifiedLinear(0, intArrayOf(-1, 1), intArrayOf(0, 2), LinearOp.LE, 0),
                reif(1, 1, LinearOp.EQ, 1),
                reif(2, 0, LinearOp.NE, 1),
                reif(3, 1, LinearOp.GE, 2),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
            ),
        )
        val sample = Sample(booleanArrayOf(true, false, false, true), longArrayOf(0, 1, 1))
        val source = ComparisonClauseFold.foldSource(model)
        val finite = ComparisonClauseFold.fold(model.bake())

        val restored = listOf(
            assertNotNull(source.rebuild.asSampleLift())(sample),
            assertNotNull(finite.reconstruct)(sample),
        )

        for (point in restored) {
            assertContentEquals(booleanArrayOf(false, true, true, false), point.bools)
            assertContentEquals(sample.ints, point.ints)
        }
    }

    @Test
    fun `finite folding should reconstruct comparisons simplified by root fixings`() {
        val model = constantShifted(openConstants = false)
        val unfixed = Problem(
            2,
            4,
            Array(4) { IntDomain(0, 3) },
            model.factors + arrayOf(
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
            ),
        )
        val sample = Sample(booleanArrayOf(false, true), longArrayOf(0, 2, 1, 1))
        assertTrue(ComparisonClauseFold.foldSource(unfixed).isEmpty)

        val delta = ComparisonClauseFold.fold(unfixed.bake())
        val restored = assertNotNull(delta.reconstruct)(sample)

        assertContentEquals(booleanArrayOf(true, false), restored.bools)
        assertContentEquals(sample.ints, restored.ints)
    }

    @Test
    fun `declared reified comparisons should fold without changing integer solutions`() {
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
    fun `source folding should fold comparisons over open columns`() {
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
    fun `source folding should keep a term over an open column out of the bound`() {
        val delta = ComparisonClauseFold.foldSource(constantShifted(openConstants = true))

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `an indicator the objective weighs should keep its reified definition`() {
        val problem = constantShifted(openConstants = false)

        val delta = ComparisonClauseFold.foldSource(problem, objectiveBoolVars = setOf(1))

        assertTrue(delta.isEmpty)
    }

    private fun intSolutions(problem: Problem): HashSet<List<Long>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = 1L)).take(10_000).map { it.ints.toList() }.toHashSet()

    @Test
    fun `a negated indicator should fold as the complement comparison`() {
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
    fun `an indicator shared by another factor should not fold`() {
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                reif(0, 0, LinearOp.LE, 1),
                reif(1, 1, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a multi-variable reified comparison should not fold`() {
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
        val problem = problemOf(
            numBool = 2,
            domains = domains,
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
                reif(1, 2, LinearOp.LE, 1),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val baked = problem.bake()
        val delta = Presolve.foldComparisonClauses(baked)
        assertTrue(delta.isEmpty)
    }
}
