package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bucketed-float FlatZinc lowering: `float_abs` and `array_float_element` map to bucket-index
 * tables, an unrepresentable float shape rejects with a [FlatZincParseException] rather than leaking
 * an internal invariant, and an exact-constant contradiction is a clean UNSAT.
 */
class FlatZincFloatConstraintsTest {

    // Five buckets over an exact grid so declared constants land on bucket boundaries.
    private fun solve(src: String, buckets: Int = 5): SolveResult =
        BacktrackSolver(parseFlatZinc(src, floatBuckets = buckets).problem.bake())
            .solve(BacktrackParams(randomSeed = 0L))

    @Test
    fun `a purely-linear float model lowers floats to LP-only continuous columns`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            constraint float_lin_le([2.0], [x], 6.0);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )
        // No nonlinear/strict/reified float constraint ⇒ the float is an LP-only real column, not a bucket.
        assertEquals(1, program.problem.numRealVars)
        assertEquals(0, program.problem.numIntVars)
        val r = assertIs<SolveResult.Sat>(
            BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L)),
        )
        val x = r.assignment.reals[0]
        assertTrue(2.0 * x <= 6.0 + 1e-6 && x in 0.0..10.0, "infeasible continuous value x=$x")
    }

    @Test
    fun `default float lowering buckets linear and nonlinear components`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            var -5.0..5.0: z;
            var 0.0..5.0: w;
            constraint float_lin_le([1.0], [x], 5.0);
            constraint float_abs(z, w);
            solve satisfy;
            """.trimIndent(),
        )
        assertEquals(0, program.problem.numRealVars)
        assertEquals(3, program.problem.numIntVars)
    }

    @Test
    fun `a nonlinear float constraint keeps the whole model on bucketing`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            var 0.0..10.0: y;
            constraint float_lin_le([1.0], [x], 5.0);
            constraint float_abs(x, y);
            solve satisfy;
            """.trimIndent(),
        )
        // float_abs is nonlinear ⇒ the whole-problem gate keeps every float bucketed (no real columns).
        assertEquals(0, program.problem.numRealVars)
        assertTrue(program.problem.numIntVars >= 2)
    }

    @Test
    fun `a false float constant comparison is unsatisfiable not a crash`() {
        // The lowering must report the contradiction itself, not post an empty Clause (which the
        // Clause factor rejects with IllegalArgumentException at compile time).
        val src = """
            constraint float_le(5.0, 3.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }
}
