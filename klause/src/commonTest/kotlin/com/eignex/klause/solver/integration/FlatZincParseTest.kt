package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0] || sat.assignment.bools[1])
    }

    @Test
    fun `solve minimize references int objective`() {
        val src = """
            var 1..10: cost;
            constraint int_lin_ge([1], [cost], 3);
            solve minimize cost;
        """.trimIndent()
        val program = parseFlatZinc(
            src.replace("int_lin_ge", "int_lin_le").replace("[1]", "[-1]").replace(", 3", ", -3"),
        )
        // FlatZinc has no `int_lin_ge` natively; encoded as negated LE.
        val solve = assertIs<SolveDirective.Minimize>(program.solve)
        assertEquals("cost", solve.objVar)
        assertEquals(SolveDirective.ObjKind.Int, solve.kind)
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
        val sample = BacktrackSolver(program.problem.bake()).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val rendered = writeFlatZincSolution(program, sample)
        // Result should look like "a=N b=M\n----------\n"
        assertTrue(rendered.startsWith("a="), "got: $rendered")
        assertTrue(rendered.contains(" b="), "got: $rendered")
        assertTrue(rendered.contains("----------"))
        assertEquals(3, sample.ints[0] + sample.ints[1])
    }

    @Test
    fun `unsupported builtin throws`() {
        // `not_a_real_builtin` is a deliberately fake name that no klause emitter handles.
        val src = """
            var 0..5: x;
            constraint not_a_real_builtin(x);
            solve satisfy;
        """.trimIndent()
        try {
            parseFlatZinc(src)
            error("expected FlatZincParseException")
        } catch (e: FlatZincParseException) {
            assertTrue(e.message!!.contains("not_a_real_builtin"), "got: ${e.message}")
        }
    }

    @Test
    fun `klause_enum_labels annotation populates enumLabelsByVar`() {
        val src = """
            var 1..3: color :: klause_enum_labels(["Red","Green","Blue"]);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(listOf("Red", "Green", "Blue"), program.enumLabelsByVar["color"])
    }

    @Test
    fun `redundant_constraint dropped under forLocalSearch`() {
        // With LS no-op, the constraint disappears entirely: x is free.
        val src = """
            var bool: x;
            constraint redundant_constraint(x);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src, forLocalSearch = true)
        assertEquals(0, program.problem.factors.size)
    }
}
