package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.diffn
import com.eignex.klause.schema.regular
import com.eignex.klause.schema.sort
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Library-API (no-FlatZinc) smoke coverage for the globals exposed via Builders.kt:
 * `sort`, `diffn`, `regular`. Each test builds a tiny [VariableSchema] with the new
 * builder, compiles, finds a satisfying sample with the backtrack solver, and asserts
 * the sample obeys the constraint semantics.
 */
class ExposedGlobalsDslTest {

    @Test
    fun `sort - ys is the ascending permutation of xs`() {
        class S : VariableSchema() {
            val x0 by intVar(1, 3)
            val x1 by intVar(1, 3)
            val y0 by intVar(1, 3)
            val y1 by intVar(1, 3)
            val rule by constraint { sort(listOf(x0, x1), listOf(y0, y1)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "sort: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        val xs = listOf(v("x0"), v("x1")).sorted()
        val ys = listOf(v("y0"), v("y1"))
        assertTrue(ys[0] <= ys[1], "sort: ys not ascending: $ys")
        assertTrue(ys == xs, "sort: ys ($ys) != sorted xs ($xs)")
    }

    @Test
    fun `diffn - two unit squares do not overlap`() {
        class S : VariableSchema() {
            val x0 by intVar(0, 2)
            val y0 by intVar(0, 2)
            val x1 by intVar(0, 2)
            val y1 by intVar(0, 2)
            val rule by constraint {
                diffn(listOf(x0, x1), listOf(y0, y1), listOf(1, 1), listOf(1, 1))
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "diffn: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        val ax = v("x0")
        val ay = v("y0")
        val bx = v("x1")
        val by = v("y1")
        val disjoint = ax + 1 <= bx || bx + 1 <= ax || ay + 1 <= by || by + 1 <= ay
        assertTrue(disjoint, "diffn: rectangles overlap at ($ax,$ay) ($bx,$by)")
    }

    @Test
    fun `regular - DFA accepts the sequence`() {
        // 2 states, 2 symbols. From any state, symbol 1 -> state 1, symbol 2 -> state 2.
        // Accept only in state 1 => the FINAL symbol must be 1 (final state = last symbol).
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val s1 by intVar(1, 2)
            val rule by constraint {
                regular(
                    seq = listOf(s0, s1),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = listOf(
                        1,
                        2, // from state 1: sym1->1, sym2->2
                        1,
                        2, // from state 2: sym1->1, sym2->2
                    ),
                    q0 = 1,
                    accepting = listOf(1),
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem)
            .enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "regular: solver found no sample")
        fun v(n: String) = sample.ints[compiled.intVarIdByName.getValue(n)]
        assertTrue(v("s1") == 1L, "regular: final symbol not accepting (s1=${v("s1")})")
    }
}
