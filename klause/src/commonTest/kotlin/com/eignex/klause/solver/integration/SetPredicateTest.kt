package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.SetVarLayout
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.solver.SolveResult
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        val twoIdx = layout.elements.indexOf(2)
        assertTrue(
            sat.assignment.bools[layout.indicatorBoolIds[twoIdx]],
            "element 2 must be in s; bools=${sat.assignment.bools.toList()}",
        )
    }

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
    fun `set_in over a hole domain skips hole values and stays satisfiable`() {
        // Regression: set_in(x, lo..hi) lowers to one ReifiedLinear(chan = x==v) per value v
        // in the range, OR-ed via atLeastOne. Values that are interior holes of x's domain
        // can never satisfy x==v and must contribute no chan. They used to be emitted anyway
        // (only out-of-[min,max] values were skipped); accumulating those forced-false
        // reifications across several vars wrongly drove the engine to UNSAT — a false UNSAT
        // on the MiniZinc Challenge `is/1YHXeG1xYs` instance. Each var below is independently
        // satisfiable (only 31 lies in both the domain {0,31,32,33,34} and the range 1..31),
        // so the conjunction is trivially SAT with every var = 31.
        val decls = (0 until 6).joinToString("\n") { "var {0,31,32,33,34}: a$it;" }
        val cons = (0 until 6).joinToString("\n") { "constraint set_in(a$it,1..31);" }
        val src = "$decls\n$cons\nsolve satisfy;"
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        for (i in 0 until 6) {
            val v = program.intVarsByName.getValue("a$i")
            assertEquals(31, sat.assignment.ints[v], "a$i must be forced to 31")
        }
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sLayout = program.setVarsByName.getValue("s")
        val tLayout = program.setVarsByName.getValue("t")
        for (i in sLayout.elements.indices) {
            if (sat.assignment.bools[sLayout.indicatorBoolIds[i]]) {
                val tIdx = tLayout.elements.indexOf(sLayout.elements[i])
                assertTrue(
                    sat.assignment.bools[tLayout.indicatorBoolIds[tIdx]],
                    "element ${sLayout.elements[i]} in s but not t",
                )
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 1L))
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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
    fun `set_in with var int x forces x into the set`() {
        val src = """
            var 1..3: x;
            var set of 1..3: s;
            constraint set_in(x, s);
            constraint set_card(s, 1);
            constraint int_eq(x, 2);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xId = program.intVarsByName.getValue("x")
        assertEquals(2, sat.assignment.ints[xId])
        val layout = program.setVarsByName.getValue("s")
        val twoIdx = layout.elements.indexOf(2)
        assertTrue(sat.assignment.bools[layout.indicatorBoolIds[twoIdx]])
    }

    @Test
    fun `set_in_reif with var int x channels r to membership`() {
        // Force x = 3, s = {3}. r must be true since 3 ∈ s.
        val src = """
            var 1..3: x;
            var bool: r;
            var set of 1..3: s;
            constraint int_eq(x, 3);
            constraint set_in(3, s);
            constraint set_card(s, 1);
            constraint set_in_reif(x, s, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val res = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(res)
        assertTrue(sat.assignment.bools[program.boolVarsByName.getValue("r")])
    }

    @Test
    fun `set_in_reif false when x is not a member`() {
        // x = 1, s = {2}. r must be false.
        val src = """
            var 1..3: x;
            var bool: r;
            var set of 1..3: s;
            constraint int_eq(x, 1);
            constraint set_in(2, s);
            constraint set_card(s, 1);
            constraint set_in_reif(x, s, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val res = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(res)
        assertTrue(!sat.assignment.bools[program.boolVarsByName.getValue("r")])
    }

    @Test
    fun `set_subset_reif fires when subset relation holds`() {
        // Make s = {1, 2}, t = {1, 2, 3}; r must be true.
        val src = """
            var bool: r;
            var set of 1..3: s;
            var set of 1..3: t;
            constraint set_in(1, s);
            constraint set_in(2, s);
            constraint set_in(1, t);
            constraint set_in(2, t);
            constraint set_in(3, t);
            constraint set_card(s, 2);
            constraint set_subset_reif(s, t, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(
            sat.assignment.bools[program.boolVarsByName.getValue("r")],
            "r must be true when s ⊆ t",
        )
    }

    @Test
    fun `set_subset_reif fires false when subset relation fails`() {
        // s = {1, 3}, t = {1, 2}. r must be false.
        val src = """
            var bool: r;
            var set of 1..3: s;
            var set of 1..3: t;
            constraint set_in(1, s);
            constraint set_in(3, s);
            constraint set_card(s, 2);
            constraint set_in(1, t);
            constraint set_in(2, t);
            constraint set_card(t, 2);
            constraint set_subset_reif(s, t, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 1L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(
            !sat.assignment.bools[program.boolVarsByName.getValue("r")],
            "r must be false when 3 ∈ s but 3 ∉ t",
        )
    }

    @Test
    fun `set_eq_reif holds when sets coincide`() {
        val src = """
            var bool: r;
            var set of 1..3: s;
            var set of 1..3: t;
            constraint set_in(1, s);
            constraint set_in(3, s);
            constraint set_card(s, 2);
            constraint set_in(1, t);
            constraint set_in(3, t);
            constraint set_card(t, 2);
            constraint set_eq_reif(s, t, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 2L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[program.boolVarsByName.getValue("r")])
    }

    @Test
    fun `set_eq_reif false when one element differs`() {
        val src = """
            var bool: r;
            var set of 1..3: s;
            var set of 1..3: t;
            constraint set_in(1, s);
            constraint set_card(s, 1);
            constraint set_in(2, t);
            constraint set_card(t, 1);
            constraint set_eq_reif(s, t, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(!sat.assignment.bools[program.boolVarsByName.getValue("r")])
    }

    @Test
    fun `set_ne forces inequality`() {
        val src = """
            var set of 1..2: s;
            var set of 1..2: t;
            constraint set_in(1, s);
            constraint set_card(s, 1);
            constraint set_ne(s, t);
            constraint set_card(t, 1);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sLayout = program.setVarsByName.getValue("s")
        val tLayout = program.setVarsByName.getValue("t")
        // s = {1}, t must differ from s — under the constraints, t = {2}.
        val sMembers = sLayout.elements.filterIndexed { i, _ -> sat.assignment.bools[sLayout.indicatorBoolIds[i]] }
        val tMembers = tLayout.elements.filterIndexed { i, _ -> sat.assignment.bools[tLayout.indicatorBoolIds[i]] }
        assertTrue(sMembers != tMembers, "s=$sMembers t=$tMembers should differ")
    }

    @Test
    fun `all_disjoint enforces pairwise empty intersection`() {
        val src = """
            array[1..3] of var set of 1..3: a;
            constraint set_in(1, a[1]);
            constraint set_in(2, a[2]);
            constraint set_in(3, a[3]);
            constraint all_disjoint(a);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sets = (1..3).map { i -> program.setVarsByName.getValue("a[$i]") }
        val membersOf: (SetVarLayout) -> Set<Int> = { layout ->
            layout.elements.filterIndexed { idx, _ -> sat.assignment.bools[layout.indicatorBoolIds[idx]] }.toSet()
        }
        for (i in sets.indices) {
            for (j in i + 1 until sets.size) {
                val m1 = membersOf(sets[i])
                val m2 = membersOf(sets[j])
                assertTrue(
                    m1.intersect(m2).isEmpty(),
                    "sets a[${i + 1}]=$m1 and a[${j + 1}]=$m2 must be disjoint",
                )
            }
        }
    }

    @Test
    fun `set_partition_into covers universe with disjoint sets`() {
        val src = """
            array[1..2] of var set of 1..4: a;
            var set of 1..4: u;
            constraint set_partition_into(a, u);
            constraint set_card(u, 4);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 3L))
        val sat = assertIs<SolveResult.Sat>(r)
        val sets = (1..2).map { i -> program.setVarsByName.getValue("a[$i]") }
        val u = program.setVarsByName.getValue("u")
        val membersOf: (SetVarLayout) -> Set<Int> = { layout ->
            layout.elements.filterIndexed { idx, _ -> sat.assignment.bools[layout.indicatorBoolIds[idx]] }.toSet()
        }
        val all = sets.flatMap { membersOf(it) }
        val uniq = all.toSet()
        assertEquals(all.size, uniq.size, "sets must be disjoint")
        assertEquals(membersOf(u), uniq, "union must equal u")
        assertEquals(setOf(1, 2, 3, 4), uniq)
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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

    @Test
    fun `var set initializer pins indicators to literal`() {
        val src = """
            var set of 1..5: s = {1, 3, 5};
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        for ((i, e) in layout.elements.withIndex()) {
            val expected = e in setOf(1, 3, 5)
            assertEquals(
                expected,
                sat.assignment.bools[layout.indicatorBoolIds[i]],
                "element $e expected in-set=$expected",
            )
        }
    }

    @Test
    fun `var set initializer pins indicators to range`() {
        val src = """
            var set of 1..5: s = 2..4;
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val layout = program.setVarsByName.getValue("s")
        for ((i, e) in layout.elements.withIndex()) {
            val expected = e in 2..4
            assertEquals(
                expected,
                sat.assignment.bools[layout.indicatorBoolIds[i]],
                "element $e expected in-set=$expected",
            )
        }
    }
}
