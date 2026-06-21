package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move.Compound
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.LexLess
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class LexLessInvariantTest {

    @Test
    fun `repair moves restore violated strict lex at first decided position`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        // xs = [3, 1], ys = [2, 4]. Violation at k=0 (xs[0]=3 > ys[0]=2).
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 4)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        // Expect a move lowering xs[0] to ≤ 1 (strict ≤ b-1 = 1) and/or raising ys[0] to ≥ 4.
        val intSets = sink.list.filterIsInstance<IntSet>()
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 },
            "expected IntSet(xs[0]=1) in $intSets",
        )
        assertTrue(
            intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected IntSet(ys[0]=4) in $intSets",
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
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val compounds = sink.list.filterIsInstance<Compound>()
        assertTrue(
            compounds.any { c ->
                c.parts == listOf(
                    IntSet(0, 2),
                    IntSet(1, 4),
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
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        // xs == ys. Strict requires a strict break; the comparable prefix is fully equal.
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 3)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        // Must propose lowering xs[0] or raising ys[0] (the earliest position with room).
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 } ||
                intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected prefix-break move at index 0 in $intSets",
        )
    }
}
