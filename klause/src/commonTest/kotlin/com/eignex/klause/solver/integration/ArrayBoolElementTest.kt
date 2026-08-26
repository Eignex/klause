package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.lowering.flatzinc.parseFlatZinc
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `array_bool_element` / `array_var_bool_element` route through the native int [Element]
 * factor (Boolean operands channeled to `[0,1]` ints) — issue #45. These pin the ingest:
 * the program carries an [Element] factor and the channeled `result = arr[idx]` semantics hold.
 */
class ArrayBoolElementTest {

    @Test
    fun `array_bool_element constant array routes through native Element`() {
        // arr = [true, false, true], 1-based; pick idx so result = arr[idx].
        val src = """
            var 1..3: idx;
            var bool: r;
            constraint array_bool_element(idx, [true, false, true], r);
            constraint int_eq(idx, 2);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertTrue(
            program.problem.factors.any { it is Element },
            "expected a native Element factor in the compiled program",
        )
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val rId = program.boolVarsByName.getValue("r")
        assertEquals(false, sat.assignment.bools[rId], "arr[2] = false")
    }

    @Test
    fun `array_var_bool_element channels result back to selected operand`() {
        // result is pinned true; the selected operand must therefore be true.
        val src = """
            var 1..3: idx;
            var bool: a;
            var bool: b;
            var bool: c;
            var bool: r;
            constraint array_var_bool_element(idx, [a, b, c], r);
            constraint bool_eq(r, true);
            constraint int_eq(idx, 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertTrue(
            program.problem.factors.any { it is Element },
            "expected a native Element factor in the compiled program",
        )
        val res = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(res)
        val cId = program.boolVarsByName.getValue("c")
        assertEquals(true, sat.assignment.bools[cId], "arr[3] = c must be true")
    }
}
