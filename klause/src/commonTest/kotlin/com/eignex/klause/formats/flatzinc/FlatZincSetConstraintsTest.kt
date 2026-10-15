package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincSetConstraintsTest {

    @Test
    fun `a set universe should allocate one indicator per distinct value`() {
        val program = parseFlatZinc(
            """
            var set of {1, 1, 2}: s;
            solve satisfy;
            """.trimIndent(),
        )

        assertEquals(2, program.problem.numBoolVars)
        assertEquals(intArrayOf(1, 2).toList(), program.setVarsByName.getValue("s").elements.toList())
    }

    @Test
    fun `set membership should reject an integer outside the signed integer universe`() {
        val program = parseFlatZinc(
            """
            var 4294967296..4294967296: x;
            var set of {0}: s = {0};
            constraint set_in(x, s);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `wide integer constants should not alias set universe elements`() {
        for (set in listOf("s", "{0}")) {
            val program = parseFlatZinc(
                """
                var set of {0}: s = {0};
                constraint set_in_reif(4294967296, $set, false);
                solve satisfy;
                """.trimIndent(),
            )

            val result = BacktrackSolver(program.problem.bake()).solve()

            assertIs<SolveResult.Sat>(result)
        }
    }

    @Test
    fun `reified membership should preserve values when the integer domain crosses the signed integer boundary`() {
        val program = parseFlatZinc(
            """
            var -2147483649..-2147483647: x;
            var set of {-2147483648}: s = {-2147483648};
            constraint int_eq(x, -2147483648);
            constraint set_in_reif(x, s, true);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `set_in with a disjoint domain should be unsat`() {
        val src = """
            var 10..20: x;
            constraint set_in(x, {1, 2, 3});
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `set_in_reif should channel the bool to the indicator`() {
        val src = """
            var bool: r;
            var set of 1..3: s;
            constraint set_in_reif(2, s, r);
            constraint bool_clause([r], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val rId = program.boolVarsByName.getValue("r")
        assertTrue(sat.assignment.bools[rId])
        val layout = program.setVarsByName.getValue("s")
        val twoIdx = layout.elements.indexOf(2)
        assertTrue(sat.assignment.bools[layout.indicatorBoolIds[twoIdx]])
    }

    @Test
    fun `set_card with a var target should tie cardinality to the int var`() {
        val src = """
            var set of 1..4: s;
            var 0..4: n;
            constraint set_card(s, n);
            constraint int_eq(n, 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 1L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        val card = layout.indicatorBoolIds.count { sat.assignment.bools[it] }
        assertEquals(3, card)
    }

    @Test
    fun `var set initializer should pin indicators to a literal or a range`() {
        val cases = listOf<Pair<String, (Int) -> Boolean>>(
            "var set of 1..5: s = {1, 3, 5};" to { e -> e in setOf(1, 3, 5) },
            "var set of 1..5: s = 2..4;" to { e -> e in 2..4 },
        )
        for ((decl, isExpected) in cases) {
            val src = "$decl\nsolve satisfy;"
            val program = parseFlatZinc(src)
            val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
            val sat = assertIs<SolveResult.Sat>(r)
            val layout = program.setVarsByName.getValue("s")
            for ((i, e) in layout.elements.withIndex()) {
                val expected = isExpected(e)
                assertEquals(expected, sat.assignment.bools[layout.indicatorBoolIds[i]], "element $e")
            }
        }
    }
}
