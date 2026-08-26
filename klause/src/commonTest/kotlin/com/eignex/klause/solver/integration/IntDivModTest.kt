package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.lowering.flatzinc.parseFlatZinc
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IntDivModTest {

    @Test
    fun `int_div positive operands`() {
        val src = """
            var 7..7: a;
            var 3..3: b;
            var -10..10: q;
            constraint int_div(a, b, q);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val qId = program.intVarsByName.getValue("q")
        assertEquals(2, sat.assignment.ints[qId], "7 / 3 truncated = 2")
    }

    @Test
    fun `int_mod positive operands`() {
        val src = """
            var 7..7: a;
            var 3..3: b;
            var -10..10: r;
            constraint int_mod(a, b, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val res = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(res)
        val rId = program.intVarsByName.getValue("r")
        assertEquals(1, sat.assignment.ints[rId], "7 mod 3 = 1")
    }

    @Test
    fun `int_mod with negative dividend keeps sign-aligned remainder`() {
        // -7 div 3 = -2 (truncated), -7 mod 3 = -1 (sign of dividend).
        val src = """
            var -7..-7: a;
            var 3..3: b;
            var -10..10: r;
            constraint int_mod(a, b, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val res = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(res)
        val rId = program.intVarsByName.getValue("r")
        assertEquals(-1, sat.assignment.ints[rId], "−7 mod 3 truncated = −1")
    }
}
