package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlatZincSetSelectionTest {
    @Test
    fun `variable set selection uses the source one based index`() {
        val program = parseFlatZinc("""
            var 3..3: i;
            var set of 1..3: a = {1};
            var set of 1..3: b = {2};
            var set of 1..3: c = {3};
            var set of 1..3: r;
            constraint array_var_set_element(i, [a,b,c], r);
            solve satisfy;
        """.trimIndent())

        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(program.problem.bake())
            .solve(BacktrackParams(randomSeed = 0))).assignment

        val result = program.setVarsByName.getValue("r")
        assertEquals(listOf(false, false, true), result.indicatorBoolIds.map(sample::boolValue))
        assertEquals(3, program.problem.factors.filterIsInstance<Element>().size)
    }

    @Test
    fun `set selection forbids a selected member outside the result universe`() {
        for (operation in listOf("array_set_element", "array_var_set_element")) {
            val arrays = if (operation == "array_set_element") "array[1..2] of set of int: choices = [{1}, {2}];"
                else "var set of 1..2: a = {1}; var set of 1..2: b = {2}; " +
                    "array[1..2] of var set of 1..2: choices = [a,b];"
            val program = parseFlatZinc("""
                $arrays
                var 2..2: i;
                var set of {1}: r;
                constraint $operation(i, choices, r);
                solve satisfy;
            """.trimIndent())

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

            assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `empty set universes still enforce the index range`() {
        val program = parseFlatZinc("""
            array[1..2] of set of int: choices = [{}, {}];
            var 0..0: i;
            var set of {}: r;
            constraint array_set_element(i, choices, r);
            solve satisfy;
        """.trimIndent())

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(1, program.problem.factors.filterIsInstance<Element>().size)
        assertIs<SolveResult.Unsat>(result)
    }
}
