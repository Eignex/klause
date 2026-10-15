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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllDifferentInvariantTest {

    @Test
    fun `matching seed should succeed where first-fit greedy fails on tight domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 0), IntDomain(1, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0))
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `structured moves should include feasibility-preserving 3-cycles`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        fun seeded(seed: Long): LocalSearchState {
            val state = LocalSearchState(problem.bake(), Random(seed))
            for (i in 0 until 5) state.assignment.setInt(i, i.toLong())
            state.recompute()
            return state
        }
        var sawRotation = false
        for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L, 29L)) {
            val state = seeded(seed)
            assertEquals(0L, state.cost)
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (m in sink.list) {
                if (m is Compound && m.parts.size == 3) sawRotation = true
                val check = seeded(0)
                check.apply(m)
                assertEquals(0L, check.cost, "$m")
            }
        }
        assertTrue(sawRotation)
    }

    @Test
    fun `repair should propose multiple unused targets when the domain has spare values`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 5)
        state.assignment.setInt(2, 0)
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, factorId = 0, sink = sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        assertTrue(intSets.size in 2..4, "$intSets")
        val occupantSet = intSets.map { it.varId }.toSet()
        assertTrue(occupantSet.size == 1 && (occupantSet.contains(0) || occupantSet.contains(1)), "$occupantSet")
        val targetSet = intSets.map { it.newValue }.toSet()
        assertEquals(intSets.size, targetSet.size, "$intSets")
        for (t in targetSet) {
            assertTrue(t != 5L && t != 0L, "target $t")
        }
    }

    @Test
    fun `repair should emit value-swap candidates when the domain is saturated`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 0)
        state.recompute()
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, factorId = 0, sink = sink)
        val compounds = sink.list.filterIsInstance<Compound>()
        assertTrue(compounds.isNotEmpty(), "${sink.list}")
        for (c in compounds) {
            assertEquals(2, c.parts.size)
            val a = c.parts[0] as IntSet
            val b = c.parts[1] as IntSet
            assertTrue(a.varId != b.varId)
            assertEquals(state.assignment.intValue(b.varId), a.newValue)
            assertEquals(state.assignment.intValue(a.varId), b.newValue)
        }
    }
}
