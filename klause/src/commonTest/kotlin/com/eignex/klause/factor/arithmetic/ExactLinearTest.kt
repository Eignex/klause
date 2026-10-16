package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExactLinearTest {
    @Test
    fun `numerical completion should retain exact negative values of a free real column`() {
        val value = BigFraction.of(bigIntOf(-1), bigIntOf(10))
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.EQ, value)
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf(row),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        val result = LeafRealSolver(source, null).use { it.solve(Sample(booleanArrayOf(), longArrayOf(0))) }

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(listOf(value), result.exactReals)
    }

    @Test
    fun `remapped repeated coordinates should enforce the combined exact relation`() {
        val source = ExactLinear.of(
            intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
            listOf(BigFraction.of(BIG_ONE, bigIntOf(10)), BigFraction.of(BIG_ONE, bigIntOf(5))),
            LinearOp.EQ,
            BigFraction.of(bigIntOf(3), bigIntOf(5)),
        )
        val remapped = source.remap(VarRemap(intArrayOf(), intArrayOf(0, 0)))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(remapped))

        val result = BacktrackSolver(model.bake()).enumerate().map { it.intValue(0) }.toList()

        assertEquals(listOf(2L), result)
    }

    @Test
    fun `wide exact rows should retain holes in finite domains`() {
        val coefficient = BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(coefficient), LinearOp.EQ, coefficient)
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2).excludeValue(1)), arrayOf(row))

        val result = BacktrackSolver(model.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `exact linear scores should agree with source truth outside signed word arithmetic`() {
        val row = ExactLinear.of(
            intArrayOf(Term.ofIntVar(0)),
            listOf(BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)),
            LinearOp.EQ,
            BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE),
        )
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(row))
        val state = LocalSearchState(LocalSearchModel.open(model), Random(3))

        for (value in 0L..2L) {
            state.assignment.setInt(0, value)
            state.recompute()

            assertEquals(value != 1L, state.cost != 0L)
        }
    }

    @Test
    fun `floating exact row score deltas should preserve integer and real namespaces`() {
        val row = ExactLinear.of(
            intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ofLong(-2)),
            LinearOp.EQ,
            BigFraction.ZERO,
        )
        val model = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(model), Random(3))
        state.assignment.setInt(0, 2)
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val invariant = row.scoringView()
        val before = invariant.violationDegree(state, 0)

        val delta = invariant.deltaIfRealSet(state, 0, 0, 0.5)
        state.assignment.setReal(0, 0.5)
        state.recompute()

        assertEquals(invariant.violationDegree(state, 0) - before, delta)
    }

    @Test
    fun `strict integer rows should retain exact lattice thresholds after denominator clearing`() {
        for ((relation, expected) in listOf(LinearOp.LE to listOf(0L, 1L), LinearOp.GE to listOf(2L, 3L))) {
            val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE), relation,
                BigFraction.of(bigIntOf(3), bigIntOf(2)), strict = true)
            val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(row))

            val values = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) }.sorted().toList()

            assertEquals(expected, values)
            assertFalse(row.strict)
        }
    }

    @Test
    fun `reified exact integer rows should enforce both truth directions`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
            BigFraction.of(bigIntOf(3), bigIntOf(2)), strict = true, activator = 0)
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf(row))

        val points = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) to it.boolValue(0) }.toSet()

        assertEquals(setOf(0L to true, 1L to true, 2L to false), points)
    }

    @Test
    fun `strict exact real rows should exclude a pinned boundary`() {
        val rows = arrayOf<Factor>(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ONE, strict = true),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.EQ, BigFraction.ONE),
        )
        val source = Problem(0, 0, emptyArray(), rows, numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val result = BacktrackSolver.source(source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `false reified exact real rows should complement strictness`() {
        for (strict in listOf(false, true)) {
            val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ONE, strict = strict, activator = 0)
            val source = Problem(1, 0, emptyArray(), arrayOf(row, Clause(intArrayOf(Lit.make(0, false)))),
                numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))

            val result = BacktrackSolver.source(source).solve()

            if (strict) assertIs<OpenTheoryResult.Sat>(result) else assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `strict reified real score changes should agree with fresh grading`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
            BigFraction.ONE, strict = true, activator = 0)
        val source = Problem(1, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        state.assignment.setReal(0, 1.0)
        state.assignment.setBool(0, false)
        state.recompute()
        val invariant = row.scoringView()
        val before = invariant.violationDegree(state, 0)

        val delta = invariant.deltaIfBoolFlipped(state, 0, 0)
        state.assignment.setBool(0, true)
        state.recompute()

        assertEquals(0, before)
        assertTrue(invariant.violationDegree(state, 0) > 0)
        assertEquals(invariant.violationDegree(state, 0) - before, delta)
    }

    @Test
    fun `exact row keys and remapping should retain strictness and reifying identity`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
            BigFraction.ONE, strict = true, activator = 0)
        val mapping = VarRemap(intArrayOf(1), intArrayOf(), intArrayOf(2))

        val image = row.remap(mapping)

        assertEquals(1, image.activator)
        assertTrue(image.strict)
        assertEquals(Term.ofRealVar(2), image.ref(0))
        assertEquals(image.structuralKey().hashCode(), row.remapStructuralHash(mapping))
        assertNotEquals(row.structuralKey(), ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ONE, activator = 0).structuralKey())
    }
}
