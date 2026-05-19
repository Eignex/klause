package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
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
    fun `set_in pins indicator true`() {
        val src = """
            var set of 1..3: s;
            constraint set_in(2, s);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        val twoIdx = layout.elements.indexOf(2)
        assertTrue(sat.assignment.bools[layout.indicatorBoolIds[twoIdx]],
            "element 2 must be in s; bools=${sat.assignment.bools.toList()}")
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
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val rId = program.boolVarsByName.getValue("r")
        assertTrue(sat.assignment.bools[rId])
        val layout = program.setVarsByName.getValue("s")
        val twoIdx = layout.elements.indexOf(2)
        assertTrue(sat.assignment.bools[layout.indicatorBoolIds[twoIdx]])
    }

    @Test
    fun `set_subset forces S inside T`() {
        val src = """
            var set of 1..4: s;
            var set of 1..4: t;
            constraint set_subset(s, t);
            constraint set_in(1, s);
            constraint set_in(2, s);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sLayout = program.setVarsByName.getValue("s")
        val tLayout = program.setVarsByName.getValue("t")
        for (i in sLayout.elements.indices) {
            if (sat.assignment.bools[sLayout.indicatorBoolIds[i]]) {
                val tIdx = tLayout.elements.indexOf(sLayout.elements[i])
                assertTrue(sat.assignment.bools[tLayout.indicatorBoolIds[tIdx]],
                    "element ${sLayout.elements[i]} in s but not t")
            }
        }
    }

    @Test
    fun `set_card with const target forces cardinality`() {
        val src = """
            var set of 1..5: s;
            constraint set_card(s, 2);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        val card = layout.indicatorBoolIds.count { sat.assignment.bools[it] }
        assertEquals(2, card, "set_card violated")
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
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 1L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        val card = layout.indicatorBoolIds.count { sat.assignment.bools[it] }
        assertEquals(3, card)
    }

    @Test
    fun `set_eq matches element by element`() {
        val src = """
            var set of 1..3: s;
            var set of 1..3: t;
            constraint set_eq(s, t);
            constraint set_in(2, s);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sLayout = program.setVarsByName.getValue("s")
        val tLayout = program.setVarsByName.getValue("t")
        for (i in sLayout.elements.indices) {
            val e = sLayout.elements[i]
            val tIdx = tLayout.elements.indexOf(e)
            assertEquals(
                sat.assignment.bools[sLayout.indicatorBoolIds[i]],
                sat.assignment.bools[tLayout.indicatorBoolIds[tIdx]],
                "element $e differs between s and t",
            )
        }
        val twoIdx = sLayout.elements.indexOf(2)
        assertTrue(sat.assignment.bools[sLayout.indicatorBoolIds[twoIdx]])
    }

    @Test
    fun `set_union covers both operand sets`() {
        val src = """
            var set of 1..3: s;
            var set of 1..3: t;
            var set of 1..3: u;
            constraint set_union(s, t, u);
            constraint set_in(1, s);
            constraint set_in(3, t);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val uLayout = program.setVarsByName.getValue("u")
        fun inSet(name: String, elem: Int): Boolean {
            val l = program.setVarsByName.getValue(name)
            val idx = l.elements.indexOf(elem)
            return idx >= 0 && sat.assignment.bools[l.indicatorBoolIds[idx]]
        }
        // u must contain every element that's in s OR t.
        for (e in uLayout.elements) {
            val expected = inSet("s", e) || inSet("t", e)
            val actual = inSet("u", e)
            assertEquals(expected, actual, "element $e: union mismatch")
        }
    }

    @Test
    fun `set_intersect contains only shared elements`() {
        val src = """
            var set of 1..3: s;
            var set of 1..3: t;
            var set of 1..3: u;
            constraint set_intersect(s, t, u);
            constraint set_in(2, s);
            constraint set_in(2, t);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        fun inSet(name: String, elem: Int): Boolean {
            val l = program.setVarsByName.getValue(name)
            val idx = l.elements.indexOf(elem)
            return idx >= 0 && sat.assignment.bools[l.indicatorBoolIds[idx]]
        }
        assertTrue(inSet("u", 2), "shared element 2 must be in u")
        for (e in 1..3) {
            assertEquals(inSet("s", e) && inSet("t", e), inSet("u", e), "element $e: intersect mismatch")
        }
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
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val output = writeFlatZincSolution(program, sat.assignment)
        assertTrue(output.contains("s = {1, 3};"), "expected s = {1, 3} in output: $output")
    }

    @Test
    fun `set_diff drops T from S`() {
        val src = """
            var set of 1..3: s;
            var set of 1..3: t;
            var set of 1..3: u;
            constraint set_diff(s, t, u);
            constraint set_in(1, s);
            constraint set_in(2, s);
            constraint set_in(2, t);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        fun inSet(name: String, elem: Int): Boolean {
            val l = program.setVarsByName.getValue(name)
            val idx = l.elements.indexOf(elem)
            return idx >= 0 && sat.assignment.bools[l.indicatorBoolIds[idx]]
        }
        for (e in 1..3) {
            assertEquals(inSet("s", e) && !inSet("t", e), inSet("u", e), "element $e: diff mismatch")
        }
    }
}
