package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LexLessTest {

    @Test
    fun `strict lex less enforces strict ordering`() {
        // xs = [x0, x1], ys = [y0, y1]. All ∈ [0..2]. Strict less.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            val xs = listOf(sample.ints[0], sample.ints[1])
            val ys = listOf(sample.ints[2], sample.ints[3])
            assertTrue(lexLess(xs, ys, strict = true), "lex_less violated: xs=$xs ys=$ys")
        }
    }

    @Test
    fun `non-strict lex lesseq allows equality`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = false)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = listOf(sat.assignment.ints[0], sat.assignment.ints[1])
        val ys = listOf(sat.assignment.ints[2], sat.assignment.ints[3])
        assertTrue(lexLess(xs, ys, strict = false), "lex_lesseq violated: xs=$xs ys=$ys")
    }

    @Test
    fun `strict lex on equal pair is Unsat`() {
        // xs = [1, 1], ys = [1, 1] pinned. Strict lex < must fail.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    private fun lexLess(xs: List<Int>, ys: List<Int>, strict: Boolean): Boolean {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            if (xs[i] < ys[i]) return true
            if (xs[i] > ys[i]) return false
        }
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false
        }
    }
}
