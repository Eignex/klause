package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridAbsTest {
    @Test
    fun `FlatZinc grid absolute value retains its source relation`() {
        val parsed = parseFlatZinc(
            "var -2.0..2.0: x; var 0.0..2.0: y; constraint float_abs(x,y); solve satisfy;",
            floatBuckets = 5,
        )
        val source = parsed.problem
        val relation = source.factors.filterIsInstance<GridAbs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map {
            it.ints[relation.operand] to it.ints[relation.result]
        }.toSet()

        assertEquals(setOf(0L to 4L, 1L to 2L, 2L to 0L, 3L to 2L, 4L to 4L), solutions)
    }

    @Test
    fun `grid absolute value preserves its declared nearest bucket rounding`() {
        val parsed = parseFlatZinc(
            "var -1.0..1.0: x; var 0.0..0.8: y; constraint float_abs(x,y); solve satisfy;",
            floatBuckets = 5,
        )
        val relation = parsed.problem.factors.filterIsInstance<GridAbs>().single()

        val solutions = BacktrackSolver(parsed.problem.bake()).enumerate().map {
            it.ints[relation.operand] to it.ints[relation.result]
        }.toSet()

        assertEquals(setOf(1L to 2L, 2L to 0L, 3L to 2L), solutions)
        assertTrue(parsed.floatVarsByName.values.none { it.lpOnly })
    }

    @Test
    fun `grid absolute value aliases retain only diagonal image points`() {
        val parsed = parseFlatZinc(
            "var -2.0..2.0: x; constraint float_abs(x,x); solve satisfy;",
            floatBuckets = 5,
        )
        val relation = parsed.problem.factors.filterIsInstance<GridAbs>().single()

        val solutions = BacktrackSolver(parsed.problem.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(2L, 3L, 4L), solutions)
    }

    @Test
    fun `private grid linear encodings agree with every native image point`() {
        for (result in listOf(0, 1)) {
            for (relation in listOf(
                GridAbs(0, result, -2.0, 2.0, 5, -2.0, 2.0, 5),
                GridAbs(0, result, -1.0, 1.0, 5, 0.0, 0.8, 5),
            )) {
                val source = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(relation))
                val fragment = assertNotNull(FactorProjectionProviders.Default.prepare(source)
                    .linearTheoryModel(EncodingTarget.LINEAR))
                for (input in 0L..4L) for (output in 0L..4L) {
                    val values = longArrayOf(input, output)
                    val held = fragment.factors.all { factor ->
                        assertEquals(0, (factor as EncodedTheoryFactor).attribution.sourceFactorIndex)
                        val form = assertNotNull(factor.linearForm)
                        val rows = form.rows.map { row ->
                            val constants = row.constants as IntegralConstants
                            var activity = BIG_ZERO
                            for (i in 0 until row.size) {
                                activity += constants.exactCoeff(i) * bigIntOf(values[Term.intVar(row.ref(i))])
                            }
                            val comparison = activity.compareTo(constants.exactBound)
                            when (row.relation) {
                                LinearOp.EQ -> comparison == 0
                                LinearOp.NE -> comparison != 0
                                LinearOp.LE -> comparison <= 0
                                LinearOp.GE -> comparison >= 0
                            }
                        }
                        if (form is LinearForm.Disjunction) rows.any { it } else rows.all { it }
                    }

                    assertEquals(values[result] == relation.image[input.toInt()].toLong(), held)
                }
            }
        }
    }

    @Test
    fun `grid alias move deltas agree with complete tuple grading`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 4)),
            arrayOf(GridAbs(0, 0, -2.0, 2.0, 5, -2.0, 2.0, 5)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        for (old in 0L..4L) for (target in 0L..4L) {
            state.assignment.setInt(0, old)
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(Move.IntSet(0, target))
            state.assignment.setInt(0, target)
            state.recompute()

            assertEquals(state.cost - before, predicted)
        }
    }

    @Test
    fun `unrepresentable grid absolute values retain the frontend decline`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc(
                "var 2.0..3.0: x; var 0.0..0.2: y; constraint float_abs(x,y); solve satisfy;",
                floatBuckets = 5,
            )
        }
    }
}
