package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.absSourceWitnessContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.toSample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AffineAbsTest {
    @Test
    fun `finite wide absolute value should retain digit coordinates and the native relation`() {
        val input = SmtLib.parse(
            "(declare-const x Int) (assert (<= (- 2) x 2)) " +
                "(assert (= (abs (* 9223372036854775808 x)) 9223372036854775808))",
        )
        val source = input.model
        assertNotNull(source.factors.filterIsInstance<AffineAbs>().singleOrNull())
        val operand = input.intVarNames.getValue("x")

        val values = BacktrackSolver(source.bake()).enumerate().map { it.intValue(operand) }.toSet()

        assertEquals(setOf(-1L, 1L), values)
    }

    @Test
    fun `wide source witnesses should preserve magnitudes beyond signed word coordinates`() {
        val input = SmtLib.parse(
            "(declare-const x Int) (assert (= x 9223372036854775808)) " +
                "(assert (= (abs (+ 9223372036854775808 x)) 18446744073709551616))",
        )
        val source = input.model
        val context = assertNotNull(source.absSourceWitnessContext(LinearObjective(), Cancellation.Never))

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())
        val sample = result.assignment.toSample(source)

        assertEquals("9223372036854775808", result.assignment.intValue(input.intVarNames.getValue("x")))
        assertTrue(assertNotNull(sample.exactInts).any { !it.fitsLong() })
        assertTrue(source.factors.filterIsInstance<AffineAbs>().single().isSatisfied(sample::exactIntValue))
        assertEquals(
            SourceEvidenceCheck.Checked,
            assertNotNull(context.semanticsChecker).witness(sample, context.scope, Cancellation.Never),
        )
    }

    @Test
    fun `affine absolute value aliases should retain only nonnegative expression values`() {
        val expression = AbsAffineValue(mapOf(0 to parseBigInt("9223372036854775808")), BIG_ZERO)
        val source = Problem(0, 1, arrayOf(IntDomain(-2, 2)), arrayOf(AffineAbs(expression, expression)))

        val values = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) }.toSet()

        assertEquals(setOf(0L, 1L, 2L), values)
    }

    @Test
    fun `affine absolute value should filter the magnitude of a constant outside signed word range`() {
        val magnitude = parseBigInt("9223372036854775808")
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf(
                AffineAbs(
                    AbsAffineValue(emptyMap(), -magnitude),
                    AbsAffineValue(mapOf(0 to magnitude), BIG_ZERO),
                ),
            ),
        )

        val values = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) }.toList()

        assertEquals(listOf(1L), values)
    }

    @Test
    fun `affine absolute value remapping should coalesce every aliased expression term`() {
        val relation = AffineAbs(
            AbsAffineValue(mapOf(0 to BIG_ONE, 1 to -BIG_ONE), bigIntOf(-2)),
            AbsAffineValue(mapOf(2 to BIG_ONE), BIG_ZERO),
        )
        val mapping = VarRemap(intArrayOf(), intArrayOf(0, 0, 1))

        val remapped = relation.remap(mapping) as AffineAbs

        assertEquals(emptyMap(), remapped.operand.coefficients)
        assertEquals(relation.remapStructuralHash(mapping), remapped.structuralKey().hashCode())
        assertTrue(remapped.isSatisfied { bigIntOf(if (it == 1) 2 else 99) })
        assertTrue(remapped.variables.ints.contentEquals(intArrayOf(1)))
    }

    @Test
    fun `overlapping affine absolute value roles should replace every occurrence in move grading`() {
        val wide = parseBigInt("9223372036854775808")
        val source = Problem(
            0,
            3,
            Array(3) { IntDomain(-2, 2) },
            arrayOf(
                AffineAbs(
                    AbsAffineValue(mapOf(0 to wide, 2 to BIG_ONE), BIG_ONE),
                    AbsAffineValue(mapOf(1 to wide, 2 to BIG_ONE), BIG_ONE),
                ),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        for (variable in 0..2) {
            for (old in -1L..1L) {
                for (target in -1L..1L) {
                    state.assignment.setInt(variable, old)
                    state.recompute()
                    val before = state.cost
                    val delta = state.netDelta(Move.IntSet(variable, target))
                    state.apply(Move.IntSet(variable, target))
                    val committed = state.cost
                    state.recompute()

                    assertEquals(state.cost - before, delta)
                    assertEquals(state.cost, committed)
                }
            }
        }
    }
}
