package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.element
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.ifThenElse
import com.eignex.klause.schema.max
import com.eignex.klause.schema.min
import com.eignex.klause.schema.not
import com.eignex.klause.schema.plus
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompilerSelectionLiftTest {
    @Test
    fun `nested extrema retain repeated operands and their native relations`() {
        class S : VariableSchema() {
            val x by intVar(0, 2)
            val y by intVar(0, 2)
            val relation by constraint { min(x, max(y, x, y)) eq x }
        }
        val compiled = S().compile()
        val extrema = compiled.problem.factors.filterIsInstance<ArrayMinMax>()

        assertEquals(listOf(true, false), extrema.map { it.max })
        assertEquals(listOf(1, 0, 1), extrema.first().xs.toList())
        assertIs<SolveResult.Sat>(BacktrackSolver(compiled).solve(BacktrackParams(randomSeed = 0)))
    }

    @Test
    fun `affine selection retains ordered entries and excludes invalid indexes`() {
        class S : VariableSchema() {
            val i by intVar(-1, 2)
            val x by intVar(2, 2)
            val relation by constraint { element(i, listOf(x + 1, x)) eq x }
        }
        val compiled = S().compile()
        val selection = compiled.problem.factors.filterIsInstance<Element>().single()
        val solver = BacktrackSolver(compiled)

        assertEquals(0, selection.indexOffset)
        assertEquals(compiled.intVarIdByName.getValue("x").toLong(), selection.arr[1])
        for (index in listOf(-1L, 0L, 2L)) {
            assertIs<SolveResult.Unsat>(solver.solve(BacktrackParams(
                assumptions = Assumptions(ints = mapOf(compiled.intVarIdByName.getValue("i") to index)),
            )))
        }
        assertIs<SolveResult.Sat>(solver.solve(BacktrackParams(randomSeed = 0)))
    }

    @Test
    fun `negated conditional selection follows both condition values`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(0, 1)
            val y by intVar(2, 3)
            val relation by constraint { ifThenElse(!b, x, y) eq 2 }
        }
        val compiled = S().compile()

        assertEquals(1, compiled.problem.factors.filterIsInstance<Element>().size)
        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(compiled).solve(BacktrackParams(randomSeed = 0)))
            .assignment
        assertEquals(true, sample.bools[compiled.boolVarIdByName.getValue("b")])
        assertEquals(2L, sample.ints[compiled.intVarIdByName.getValue("y")])
    }
}
