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
    fun `hall interval prunes other vars' bounds via propagation`() {
        // Three vars sharing the same 3-value domain [1, 3] form a Hall interval. A
        // fourth var with domain [0, 5] must skip the Hall interval — its bounds get
        // shaved on either side, but since [1, 3] is in the *interior* of [0, 5], only
        // the endpoint cases land: with current bounds, neither min nor max sits inside
        // [1, 3], so no bound prune happens directly. Use a fourth var with domain [2, 5]
        // instead: its min (2) is inside the Hall interval, so it gets bumped to 4.
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3),  // Hall set on [1, 3]
                IntDomain(2, 5),                                     // overlapping intruder
            ),
            factors = listOf(factor),
        )
        // BacktrackSolver runs propagation to fixpoint at session init.
        val session = com.eignex.klause.solver.propagation.PropagationSession(problem)
        // After init: v3's min should be pushed up past the Hall interval to 4.
        val v3Domain = session.intDomain(3)
        kotlin.test.assertEquals(4, v3Domain.min,
            "v3's min should be tightened to 4 (Hall set [1,3] forbids 2,3 for v3); got $v3Domain")
        kotlin.test.assertEquals(5, v3Domain.max,
            "v3's max should remain 5; got $v3Domain")
    }

    @Test
    fun `hall interval detects infeasibility - pigeonhole over interval`() {
        // Four vars all confined to [1, 3] — 4 vars but only 3 values. Hall-interval
        // count > span detects infeasibility at propagation time.
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)),
            factors = listOf(factor),
        )
        // Bake-time propagation should mark the problem Unsat.
        val baked = problem.baked
        assertTrue(baked is com.eignex.klause.solver.propagation.PropagationResult.Unsat,
            "expected bake-time Unsat from Hall pigeonhole; got $baked")
    }

    @Test
    fun `hall interval prunes overlapping bounds on both sides`() {
        // Hall set with values [3, 4] (two vars). A third var with domain [4, 7] should
        // have its min pushed to 5. A fourth var with domain [1, 3] should have its max
        // pushed to 2.
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 8)
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(3, 4), IntDomain(3, 4),   // Hall set on [3, 4]
                IntDomain(4, 7),                     // min 4 ∈ [3, 4] → push to 5
                IntDomain(1, 3),                     // max 3 ∈ [3, 4] → push to 2
            ),
            factors = listOf(factor),
        )
        val session = com.eignex.klause.solver.propagation.PropagationSession(problem)
        val v2 = session.intDomain(2)
        val v3 = session.intDomain(3)
        kotlin.test.assertEquals(5, v2.min, "v2's min should be pushed past Hall set; got $v2")
        kotlin.test.assertEquals(7, v2.max)
        kotlin.test.assertEquals(1, v3.min)
        kotlin.test.assertEquals(2, v3.max, "v3's max should be pulled below Hall set; got $v3")
    }

    @Test
    fun `singleton-taken value punched out of interior of other domains`() {
        // With sparse-domain support, singletons remove the taken value even when it
        // lands in the interior of another var's domain — not just at endpoints.
        // Setup: v0 pinned to 3 (a singleton). v1 has domain [1, 5]. After
        // propagation, v1's domain should retain min=1 and max=5 but have value 3
        // excluded (size = 4 instead of 5).
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 5)),
            factors = listOf(factor),
        )
        val session = com.eignex.klause.solver.propagation.PropagationSession(problem)
        val d1 = session.intDomain(1)
        kotlin.test.assertEquals(1, d1.min, "v1's min should remain 1 (3 is interior)")
        kotlin.test.assertEquals(5, d1.max, "v1's max should remain 5 (3 is interior)")
        kotlin.test.assertEquals(4, d1.size, "v1 should have 4 values after punching out 3; got $d1")
        kotlin.test.assertTrue(3 !in d1, "v1 should no longer contain 3")
        kotlin.test.assertTrue(2 in d1 && 4 in d1, "v1 should still contain 2 and 4")
    }

    @Test
    fun `hall interval with spanning intruder punches every interior value`() {
        // Hall set with values [3, 5] (three vars). A fourth var with domain [1, 7]
        // spans across the Hall interval — sparse-domain pruning now punches 3, 4,
        // and 5 out of its domain even though min and max can't move past the holes.
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 8)
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(3, 5), IntDomain(3, 5), IntDomain(3, 5),  // Hall set on [3, 5]
                IntDomain(1, 7),                                     // spanning intruder
            ),
            factors = listOf(factor),
        )
        val session = com.eignex.klause.solver.propagation.PropagationSession(problem)
        val d3 = session.intDomain(3)
        kotlin.test.assertEquals(1, d3.min)
        kotlin.test.assertEquals(7, d3.max)
        for (h in 3..5) kotlin.test.assertTrue(h !in d3, "value $h should be a hole, got $d3")
        for (k in intArrayOf(1, 2, 6, 7)) {
            kotlin.test.assertTrue(k in d3, "value $k should remain; got $d3")
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
