package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AllDifferentTest {

    @Test
    fun `four vars permutation over four values`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 4, "duplicates in ${s.ints.toList()}")
        }
    }

    @Test
    fun `three vars room for one duplicate requires unique values`() {

        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 3, "duplicates in ${s.ints.toList()}")
        }
    }

    @Test
    fun `repair proposes multiple unused targets when domain has many spare values`() {
        // Setup: 3 vars over [0, 9] with both x0 and x1 forced to 5 (so 5 is duplicated).
        // The domain has 9 unused values for the conflict occupant to take — the factor
        // should propose multiple targets rather than just one.
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = com.eignex.klause.solver.Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(factor),
        )
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 5)
        state.assignment.setInt(2, 0)
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        val sink = com.eignex.klause.solver.localsearch.MoveSink()
        factor.proposeRepairMoves(state, factorId = 0, sink = sink)
        // The sink should now contain multiple IntSet moves on the same occupant. With 9 free
        // targets and a cap of MAX_REPAIR_TARGETS=4, expect exactly 4 candidates.
        val intSets = sink.list.filterIsInstance<com.eignex.klause.solver.Move.IntSet>()
        assertTrue(intSets.size in 2..4, "expected 2-4 candidates (cap 4), got ${intSets.size}: $intSets")
        // All candidates must be on the same conflict occupant (var 0 or 1).
        val occupantSet = intSets.map { it.varId }.toSet()
        assertTrue(occupantSet.size == 1 && (occupantSet.contains(0) || occupantSet.contains(1)),
            "candidates should pin one occupant, got $occupantSet")
        // All candidates must target distinct unused values (not 5, not 0 since x2=0).
        val targetSet = intSets.map { it.newValue }.toSet()
        assertTrue(targetSet.size == intSets.size, "duplicate targets: $intSets")
        for (t in targetSet) {
            assertTrue(t != 5 && t != 0, "target $t collides with existing assignment")
        }
    }

    @Test
    fun `repair emits value-swap candidates when domain is fully saturated`() {
        // 4 vars over [0..2] — all three values present, duplicate at 0. Every contiguous
        // domain value is held by some var, so the conflict occupant has no unused target
        // and the swap pass kicks in. (Pigeonhole-infeasible problem; that's fine — we're
        // verifying the move-generation mechanism, not solving.)
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 3)
        val problem = com.eignex.klause.solver.Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(factor),
        )
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 0)
        state.recompute()
        val sink = com.eignex.klause.solver.localsearch.MoveSink()
        factor.proposeRepairMoves(state, factorId = 0, sink = sink)
        val compounds = sink.list.filterIsInstance<com.eignex.klause.solver.Move.Compound>()
        assertTrue(compounds.isNotEmpty(), "expected swap candidates with saturated domain; got ${sink.list}")
        // Verify each Compound is a well-formed value-swap.
        for (c in compounds) {
            assertTrue(c.parts.size == 2, "swap should be 2-part, got ${c.parts.size}")
            val a = c.parts[0] as com.eignex.klause.solver.Move.IntSet
            val b = c.parts[1] as com.eignex.klause.solver.Move.IntSet
            assertTrue(a.varId != b.varId, "swap should target distinct vars")
            assertTrue(a.newValue == state.assignment.intValue(b.varId),
                "swap part 0 takes part 1's old value")
            assertTrue(b.newValue == state.assignment.intValue(a.varId),
                "swap part 1 takes part 0's old value")
        }
    }

    @Test
    fun `mismatched domain bounds fail at initialize`() {

        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 2)),
            factors = listOf(factor),
        )

        assertFails {
            LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 50)).enumerate(LocalSearchParams(maxFlips = 100, randomSeed = 1)).take(1).toList()
        }
    }
}
