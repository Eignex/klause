package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Bucketed-float FlatZinc lowering: `float_abs` and `array_float_element` map to bucket-index
 * tables, an unrepresentable float shape rejects with a [FlatZincParseException] rather than leaking
 * an internal invariant, and an exact-constant contradiction is a clean UNSAT.
 */
class FlatZincFloatConstraintsTest {

    // Five buckets over an exact grid so declared constants land on bucket boundaries.
    private fun solve(src: String, buckets: Int = 5): SolveResult =
        BacktrackSolver(parseFlatZinc(src, floatBuckets = buckets).problem)
            .solve(BacktrackParams(randomSeed = 0L))

    @Test
    fun `float_abs equates the result to the magnitude`() {
        val src = """
            var -1.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_lin_eq([1.0], [x], -0.5);
            constraint float_abs(x, y);
            constraint float_lin_eq([1.0], [y], 0.5);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Sat>(solve(src))
    }

    @Test
    fun `float_abs rejects a result that is not the magnitude`() {
        val src = """
            var -1.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_lin_eq([1.0], [x], -0.5);
            constraint float_abs(x, y);
            constraint float_lin_eq([1.0], [y], 0.25);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }

    @Test
    fun `array_float_element selects the indexed constant`() {
        val src = """
            var 1..3: i;
            var 0.0..4.0: x;
            constraint int_eq(i, 2);
            constraint array_float_element(i, [1.0, 2.0, 3.0], x);
            constraint float_lin_eq([1.0], [x], 2.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Sat>(solve(src))
    }

    @Test
    fun `array_float_element rejects a value other than the indexed constant`() {
        val src = """
            var 1..3: i;
            var 0.0..4.0: x;
            constraint int_eq(i, 2);
            constraint array_float_element(i, [1.0, 2.0, 3.0], x);
            constraint float_lin_eq([1.0], [x], 1.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }

    @Test
    fun `float_times with no representable bucket rejects cleanly`() {
        // a*b lands near 0.81..1.0 but c is confined to 0.0..0.1: no bucket triple realises it, so the
        // bucketing cannot encode the constraint and lowering must reject rather than crash or claim UNSAT.
        val src = """
            var 0.9..1.0: a;
            var 0.9..1.0: b;
            var 0.0..0.1: c;
            constraint float_times(a, b, c);
            solve satisfy;
        """.trimIndent()
        assertFailsWith<FlatZincParseException> { parseFlatZinc(src, floatBuckets = 2) }
    }

    @Test
    fun `float_times with a constant operand is lowered as a linear product`() {
        // c = 2.0 * x with x = 2 is linear (no var*var table); c must equal 4.
        val src = """
            var 0.0..4.0: x;
            var 0.0..8.0: c;
            constraint float_lin_eq([1.0], [x], 2.0);
            constraint float_times(2.0, x, c);
            constraint float_lin_eq([1.0], [c], 4.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Sat>(solve(src))
    }

    @Test
    fun `float_times with a constant operand rejects a wrong result`() {
        val src = """
            var 0.0..4.0: x;
            var 0.0..8.0: c;
            constraint float_lin_eq([1.0], [x], 2.0);
            constraint float_times(2.0, x, c);
            constraint float_lin_eq([1.0], [c], 6.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }

    @Test
    fun `a false float constant comparison is unsatisfiable not a crash`() {
        // Previously posted an empty Clause, which threw IllegalArgumentException at compile time.
        val src = """
            constraint float_le(5.0, 3.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }
}
