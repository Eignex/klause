package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Cumulatives
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `fzn_cumulatives(s, d, r, m, b, upper, min_m)` routes to the native graded [Cumulatives]
 * factor (issue #44). Mirrors the flattened form MiniZinc emits and checks the multi-machine
 * capacity semantics hold on the solved assignment.
 */
class CumulativesTest {

    @Test
    fun `fzn_cumulatives routes native and enforces per-machine capacity`() {
        // 4 unit-resource tasks, duration 2, two machines of capacity 1, horizon 0..6.
        // Each machine can hold at most one running task at a time → tasks on the same
        // machine must not overlap.
        val src = """
            array [1..2] of int: b = [1, 1];
            array [1..4] of int: r = [1, 1, 1, 1];
            array [1..4] of int: d = [2, 2, 2, 2];
            var 0..6: s1; var 0..6: s2; var 0..6: s3; var 0..6: s4;
            var 1..2: m1; var 1..2: m2; var 1..2: m3; var 1..2: m4;
            constraint fzn_cumulatives([s1, s2, s3, s4], d, r, [m1, m2, m3, m4], b, true, 1);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertTrue(
            program.problem.factors.any { it is Cumulatives },
            "expected a native Cumulatives factor",
        )
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val starts = listOf("s1", "s2", "s3", "s4").map { sat.assignment.ints[program.intVarsByName[it]!!] }
        val machines = listOf("m1", "m2", "m3", "m4").map { sat.assignment.ints[program.intVarsByName[it]!!] }
        // Verify: no two tasks on the same machine overlap (each occupies [start, start+2)).
        for (i in 0 until 4) for (j in i + 1 until 4) {
            if (machines[i] != machines[j]) continue
            val overlap = starts[i] < starts[j] + 2 && starts[j] < starts[i] + 2
            assertTrue(
                !overlap,
                "tasks $i and $j share machine ${machines[i]} and overlap " +
                    "(starts ${starts[i]}, ${starts[j]})",
            )
        }
    }
}
