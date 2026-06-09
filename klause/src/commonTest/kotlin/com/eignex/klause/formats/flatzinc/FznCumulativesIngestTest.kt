package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `fzn_cumulatives(s, d, r, m, b, upper, min_m)` decomposes (issue #209) — the native
 * `Cumulatives` factor was dropped. `upper = true` lowers to one per-machine `Cumulative`
 * gated by `m[i] = k`; `upper = false` lowers to a time-indexed reified `usage > 0 → usage ≥ b`.
 * These tests check the multi-machine capacity / min-load semantics hold on the solved
 * assignment (and that an over-subscribed instance is correctly UNSAT).
 */
class FznCumulativesIngestTest {

    @Test
    fun `fzn_cumulatives upper enforces per-machine capacity`() {
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
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val starts = listOf("s1", "s2", "s3", "s4").map { sat.assignment.ints[program.intVarsByName.getValue(it)] }
        val machines = listOf("m1", "m2", "m3", "m4").map { sat.assignment.ints[program.intVarsByName.getValue(it)] }
        // Verify: no two tasks on the same machine overlap (each occupies [start, start+2)).
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
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

    @Test
    fun `fzn_cumulatives upper is unsat when machine capacity is exceeded`() {
        // Two duration-3 demand-2 tasks pinned to overlap on a single capacity-2 machine →
        // combined demand 4 > 2 wherever both run, and the pins force an overlap → UNSAT.
        val src = """
            array [1..1] of int: b = [2];
            array [1..2] of int: r = [2, 2];
            array [1..2] of int: d = [3, 3];
            var 0..0: s1; var 1..1: s2;
            var 1..1: m1; var 1..1: m2;
            constraint fzn_cumulatives([s1, s2], d, r, [m1, m2], b, true, 1);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertIs<SolveResult.Unsat>(BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `fzn_cumulatives lower enforces per-machine minimum load`() {
        // Min-load floor 2: any machine in use at some time must carry usage >= 2 there. Two
        // unit-demand tasks → a machine running exactly one of them (usage 1) violates the
        // floor, so both present tasks must co-run on the same machine over a common time point.
        val src = """
            array [1..2] of int: b = [2, 2];
            array [1..2] of int: r = [1, 1];
            array [1..2] of int: d = [2, 2];
            var 0..4: s1; var 0..4: s2;
            var 1..2: m1; var 1..2: m2;
            constraint fzn_cumulatives([s1, s2], d, r, [m1, m2], b, false, 1);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val s1 = sat.assignment.ints[program.intVarsByName.getValue("s1")]
        val s2 = sat.assignment.ints[program.intVarsByName.getValue("s2")]
        val m1 = sat.assignment.ints[program.intVarsByName.getValue("m1")]
        val m2 = sat.assignment.ints[program.intVarsByName.getValue("m2")]
        // The only way two unit tasks meet a floor of 2 is to share a machine and overlap.
        val overlap = s1 < s2 + 2 && s2 < s1 + 2
        assertTrue(
            m1 == m2 && overlap,
            "min-load floor 2 needs both tasks co-running on one machine (s1=$s1 s2=$s2 m1=$m1 m2=$m2)",
        )
    }
}
