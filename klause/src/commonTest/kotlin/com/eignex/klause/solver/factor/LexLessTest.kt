package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LexLessTest {

    @Test
    fun `strict lex less enforces strict ordering`() {
        // xs = [x0, x1], ys = [y0, y1]. All ∈ [0..2]. Strict less.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
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
            numBoolVars = 0,
            numIntVars = 4,
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
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `repair moves restore violated strict lex at first decided position`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(
            problem,
            kotlin.random.Random(0),
        )
        // xs = [3, 1], ys = [2, 4]. Violation at k=0 (xs[0]=3 > ys[0]=2).
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 4)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = com.eignex.klause.solver.localsearch.MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        // Expect a move lowering xs[0] to ≤ 1 (strict ≤ b-1 = 1) and/or raising ys[0] to ≥ 4.
        val intSets = sink.list.filterIsInstance<com.eignex.klause.solver.Move.IntSet>()
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 },
            "expected IntSet(xs[0]=1) in $intSets"
        )
        assertTrue(
            intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected IntSet(ys[0]=4) in $intSets"
        )
    }

    @Test
    fun `repair adds swap compound when both opposites fit domains`() {
        val factor = LexLess(intArrayOf(0), intArrayOf(1), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(
            problem,
            kotlin.random.Random(0),
        )
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = com.eignex.klause.solver.localsearch.MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val compounds = sink.list.filterIsInstance<com.eignex.klause.solver.Move.Compound>()
        assertTrue(
            compounds.any { c ->
                c.parts == listOf(
                    com.eignex.klause.solver.Move.IntSet(0, 2),
                    com.eignex.klause.solver.Move.IntSet(1, 4),
                )
            },
            "expected swap Compound(IntSet(0,2), IntSet(1,4)) in $compounds",
        )
    }

    @Test
    fun `repair on equal-length prefix-equal under strict proposes prefix break`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(
            problem,
            kotlin.random.Random(0),
        )
        // xs == ys. Strict requires a strict break; the comparable prefix is fully equal.
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 3)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = com.eignex.klause.solver.localsearch.MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<com.eignex.klause.solver.Move.IntSet>()
        // Must propose lowering xs[0] or raising ys[0] (the earliest position with room).
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 } ||
                intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected prefix-break move at index 0 in $intSets",
        )
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
