package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.element
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.ifThenElse
import com.eignex.klause.schema.iff
import com.eignex.klause.schema.max
import com.eignex.klause.schema.maxOf
import com.eignex.klause.schema.min
import com.eignex.klause.schema.not
import com.eignex.klause.schema.plus
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompilerSelectionLiftTest {
    @Test
    fun `reified extrema should preserve both truth values`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(0, 2)
            val relation by constraint { b iff (x.max(1) eq 2) }
        }
        val compiled = S().compile()
        val solver = BacktrackSolver(compiled.problem.bake())
        val choice = compiled.boolVarIdByName.getValue("b")
        val value = compiled.intVarIdByName.getValue("x")

        for ((truth, expected) in listOf(false to setOf(0L, 1L), true to setOf(2L))) {
            val samples = solver.enumerate(BacktrackParams(assumptions = Assumptions(bools = mapOf(choice to truth))))

            assertEquals(expected, samples.map { it.intValue(value) }.toSet())
        }
        assertTrue(compiled.problem.factors.filterIsInstance<ArrayMinMax>().isNotEmpty())
    }

    @Test
    fun `optional defaults should retain their selection inside an extremum`() {
        class S : VariableSchema() {
            val x by optIntVar(1, 3)
            val relation by constraint { x.valueOr(2).min(3) eq 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem.bake())
        val presence = compiled.boolVarIdByName.getValue("x__present")

        for (present in listOf(false, true)) {
            val result = solver.solve(
                BacktrackParams(
                    randomSeed = 0,
                    assumptions = Assumptions(bools = mapOf(presence to present)),
                ),
            )

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertEquals(if (present) 2L else null, compiled.decode(schema.x, sample))
        }
        assertEquals(1, compiled.problem.factors.filterIsInstance<Element>().size)
        assertEquals(1, compiled.problem.factors.filterIsInstance<ArrayMinMax>().size)
    }

    @Test
    fun `nested extrema should retain repeated operands and their native relations`() {
        class S : VariableSchema() {
            val x by intVar(0, 2)
            val y by intVar(0, 2)
            val relation by constraint { x.min(maxOf(y, x, y)) eq x }
        }
        val compiled = S().compile()
        val extrema = compiled.problem.factors.filterIsInstance<ArrayMinMax>()

        assertEquals(listOf(true, false), extrema.map { it.max })
        assertEquals(listOf(1, 0, 1), extrema.first().xs.toList())
        assertIs<SolveResult.Sat>(BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 0)))
    }

    @Test
    fun `affine selection should retain ordered entries and exclude invalid indexes`() {
        class S : VariableSchema() {
            val i by intVar(-1, 2)
            val x by intVar(2, 2)
            val relation by constraint { element(i, listOf(x + 1, x)) eq x }
        }
        val compiled = S().compile()
        val selection = compiled.problem.factors.filterIsInstance<Element>().single()
        val solver = BacktrackSolver(compiled.problem.bake())

        assertEquals(0, selection.indexOffset)
        assertEquals(compiled.intVarIdByName.getValue("x").toLong(), selection.arr[1])
        for (index in listOf(-1L, 0L, 2L)) {
            assertIs<SolveResult.Unsat>(
                solver.solve(
                    BacktrackParams(
                        assumptions = Assumptions(ints = mapOf(compiled.intVarIdByName.getValue("i") to index)),
                    ),
                ),
            )
        }
        assertIs<SolveResult.Sat>(solver.solve(BacktrackParams(randomSeed = 0)))
    }

    @Test
    fun `negated conditional selection should follow both condition values`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(0, 1)
            val y by intVar(2, 3)
            val relation by constraint { ifThenElse(!b, x, y) eq 2 }
        }
        val compiled = S().compile()

        assertEquals(1, compiled.problem.factors.filterIsInstance<Element>().size)
        val sample = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 0)),
        ).assignment
        assertEquals(true, sample.bools[compiled.boolVarIdByName.getValue("b")])
        assertEquals(2L, sample.ints[compiled.intVarIdByName.getValue("y")])
    }
}
