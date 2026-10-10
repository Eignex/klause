package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the bool-indicator decomposition for set predicates: each test
 * parses a tiny FZN model, runs the backtrack solver, and checks the indicator bools yield
 * a feasible set assignment matching the constraint's semantics.
 */
class SetPredicateTest {

    @Test
    fun `set_in with no reachable value is unsatisfiable rather than crashing`() {
        // x's domain is disjoint from the target set, so membership is infeasible. The compiler
        // posts a false factor; it must not build an empty Clause (which the factor rejects).
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
    fun `set_in_reif channels bool to indicator`() {
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
    fun `set_card with var target ties cardinality to int var`() {
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
    fun `FZN writer reconstructs set output from indicators`() {
        val src = """
            var set of 1..3: s;
            constraint set_in(1, s);
            constraint set_in(3, s);
            constraint set_card(s, 2);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val output = writeFlatZincSolution(program, sat.assignment)
        assertTrue(output.contains("s = {1, 3};"), "expected s = {1, 3} in output: $output")
    }

    @Test
    fun `var set initializer pins indicators to a literal or a range`() {
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
                assertEquals(
                    expected,
                    sat.assignment.bools[layout.indicatorBoolIds[i]],
                    "element $e expected in-set=$expected",
                )
            }
        }
    }
}
