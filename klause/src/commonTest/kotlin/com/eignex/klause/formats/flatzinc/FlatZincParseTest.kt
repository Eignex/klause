package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.BacktrackParams
import com.eignex.klause.solver.BacktrackSolver
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlatZincParseTest {

    @Test
    fun `bool var with clause constraint`() {
        val src = """
            var bool: x;
            var bool: y;
            constraint bool_clause([x, y], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.numBoolVars)
        assertEquals(SolveDirective.Satisfy, program.solve)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams())
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0] || sat.assignment.bools[1])
    }

    @Test
    fun `int range and linear le`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.numIntVars)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertTrue(sample.ints[0] + sample.ints[1] <= 5)
    }

    @Test
    fun `solve minimize references int objective`() {
        val src = """
            var 1..10: cost;
            constraint int_lin_ge([1], [cost], 3);
            solve minimize cost;
        """.trimIndent()
        val program = parseFlatZinc(src.replace("int_lin_ge", "int_lin_le").replace("[1]", "[-1]").replace(", 3", ", -3"))
        // FlatZinc has no `int_lin_ge` natively; encoded as negated LE.
        val solve = assertIs<SolveDirective.Minimize>(program.solve)
        assertEquals("cost", solve.objVar)
        assertEquals(SolveDirective.ObjKind.Int, solve.kind)
    }

    @Test
    fun `output renders default when no output clause`() {
        val src = """
            var bool: x;
            constraint bool_clause([x], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams())!!
        val rendered = writeFlatZincSolution(program, sample)
        assertTrue(rendered.contains("x = true"), "got: $rendered")
        assertTrue(rendered.contains("----------"))
    }

    @Test
    fun `output renders custom output items`() {
        val src = """
            var 0..5: a;
            var 0..5: b;
            constraint int_lin_eq([1, 1], [a, b], 3);
            solve satisfy;
            output ["a=", show(a), " b=", show(b), "\n"];
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams())!!
        val rendered = writeFlatZincSolution(program, sample)
        // Result should look like "a=N b=M\n----------\n"
        assertTrue(rendered.startsWith("a="), "got: $rendered")
        assertTrue(rendered.contains(" b="), "got: $rendered")
        assertTrue(rendered.contains("----------"))
        assertEquals(3, sample.ints[0] + sample.ints[1])
    }

    @Test
    fun `parameter array used as coefficients`() {
        val src = """
            array [1..3] of int: coefs = [2, 3, 1];
            var 0..5: a;
            var 0..5: b;
            var 0..5: c;
            constraint int_lin_le(coefs, [a, b, c], 10);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L))
        assertNotNull(sample)
        assertTrue(2 * sample.ints[0] + 3 * sample.ints[1] + sample.ints[2] <= 10)
    }

    @Test
    fun `all_different_int`() {
        val src = """
            array [1..3] of var 0..2: xs;
            constraint all_different_int(xs);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams())
        assertNotNull(sample)
        val seen = setOf(sample.ints[0], sample.ints[1], sample.ints[2])
        assertEquals(3, seen.size)
    }

    @Test
    fun `float vars are bucketed and float_lin_le works`() {
        val src = """
            var 0.0..10.0: x;
            var 0.0..10.0: y;
            constraint float_lin_le([1.0, 1.0], [x, y], 5.0);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src, floatBuckets = 100)
        assertEquals(2, program.floatVarsByName.size)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 3L))
        assertNotNull(sample)
        val xVal = program.floatVarsByName["x"]!!.valueOf(sample.ints[0])
        val yVal = program.floatVarsByName["y"]!!.valueOf(sample.ints[1])
        // Allow a small tolerance for rounding through the bucket/scale pipeline.
        assertTrue(xVal + yVal <= 5.0 + 0.5, "x+y = ${xVal + yVal}")
    }

    @Test
    fun `unsupported builtin throws`() {
        val src = """
            var 0..5: x;
            constraint table_int([x], [[1], [3], [5]]);
            solve satisfy;
        """.trimIndent()
        try {
            parseFlatZinc(src)
            error("expected FlatZincParseException")
        } catch (e: FlatZincParseException) {
            assertTrue(e.message!!.contains("table_int"), "got: ${e.message}")
        }
    }

    @Test
    fun `comments are skipped`() {
        val src = """
            % this is a comment
            var bool: x;  % trailing comment
            constraint bool_clause([x], []);  % another
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(1, program.problem.numBoolVars)
    }

    @Test
    fun `int_eq with constant`() {
        val src = """
            var 0..5: x;
            constraint int_eq(x, 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams())
        assertNotNull(sample)
        assertEquals(3, sample.ints[0])
    }
}
