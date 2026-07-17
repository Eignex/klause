package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `emitFloatBinaryCmp` on a var-versus-constant comparison. `value(var) = lo + step·bucket`, so a
 * constant operand contributes `sign·(const − lo)` to the bound; an earlier sign slip on that term
 * pinned the wrong bucket (e.g. `float_eq(x, -0.5)` selected +0.5).
 */
class FlatZincFloatCompareTest {

    // Exact five-bucket grids so the declared constants land on bucket boundaries.
    private fun program(src: String) = parseFlatZinc(src, floatBuckets = 5)

    private fun solve(src: String): SolveResult =
        BacktrackSolver(program(src).problem).solve(BacktrackParams(randomSeed = 0L))

    private fun pinnedValue(src: String, varName: String): Double {
        val prog = program(src)
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(prog.problem).solve(BacktrackParams(randomSeed = 0L)))
        val bk = prog.floatVarsByName.getValue(varName)
        return if (bk.lpOnly) sat.assignment.reals[bk.varId] else bk.valueOf(sat.assignment.ints[bk.varId].toInt())
    }

    @Test
    fun `float_eq pins a var to the constant value`() {
        val src = """
            var -1.0..1.0: x;
            constraint float_eq(x, -0.5);
            solve satisfy;
        """.trimIndent()
        assertEquals(-0.5, pinnedValue(src, "x"), 1e-9)
    }

    @Test
    fun `float_eq with the constant on the left pins identically`() {
        val src = """
            var -1.0..1.0: x;
            constraint float_eq(0.5, x);
            solve satisfy;
        """.trimIndent()
        assertEquals(0.5, pinnedValue(src, "x"), 1e-9)
    }

    @Test
    fun `float_le bounds the var from above by the constant`() {
        val base = """
            var 0.0..1.0: x;
            constraint float_le(x, 0.5);
        """.trimIndent()
        assertIs<SolveResult.Sat>(solve("$base\nconstraint float_lin_eq([1.0], [x], 0.5);\nsolve satisfy;"))
        assertIs<SolveResult.Unsat>(solve("$base\nconstraint float_lin_eq([1.0], [x], 0.75);\nsolve satisfy;"))
    }

    @Test
    fun `float_le with the constant on the left bounds the var from below`() {
        val base = """
            var 0.0..1.0: x;
            constraint float_le(0.5, x);
        """.trimIndent()
        assertIs<SolveResult.Sat>(solve("$base\nconstraint float_lin_eq([1.0], [x], 0.5);\nsolve satisfy;"))
        assertIs<SolveResult.Unsat>(solve("$base\nconstraint float_lin_eq([1.0], [x], 0.25);\nsolve satisfy;"))
    }
}
