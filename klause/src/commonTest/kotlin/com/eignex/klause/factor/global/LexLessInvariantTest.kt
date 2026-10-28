package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.Compound
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class LexLessInvariantTest {

    @Test
    fun `seeding should preserve deciding coordinates repeated in the suffix`() {
        val factor = LexLess(intArrayOf(0, 2, 1), intArrayOf(1, 3, 0), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))

        val seeded = state.factors[0].seedFeasible(state, 0)

        assertTrue(seeded)
        assertTrue(!state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `repair moves should restore violated strict lex at the first decided position`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 4)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        assertTrue(intSets.any { it.varId == 0 && it.newValue == 1L }, "$intSets")
        assertTrue(intSets.any { it.varId == 2 && it.newValue == 4L }, "$intSets")
    }

    @Test
    fun `repair should add a swap compound when both opposites fit domains`() {
        val factor = LexLess(intArrayOf(0), intArrayOf(1), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
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
            "$compounds",
        )
    }

    @Test
    fun `repair should step a lowered x across a hole to the next present value`() {
        val factor = LexLess(intArrayOf(0), intArrayOf(1), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        val xTargets = sink.list.filterIsInstance<IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertTrue(2L in xTargets, "$xTargets")
        assertTrue(3L !in xTargets, "$xTargets")
    }
}
