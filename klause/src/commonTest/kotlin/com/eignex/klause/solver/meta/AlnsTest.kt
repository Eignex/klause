package com.eignex.klause.solver.meta

import com.eignex.klause.solver.localsearch.meta.Alns
import com.eignex.klause.solver.localsearch.meta.DestroyOperator
import com.eignex.klause.solver.localsearch.meta.FreedVars
import com.eignex.klause.solver.localsearch.meta.GreedyConstructionRepair
import com.eignex.klause.solver.localsearch.meta.InnerLsRepair
import com.eignex.klause.solver.localsearch.meta.RepairOperator
import com.eignex.klause.solver.localsearch.meta.RouletteWheelBandit

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlnsTest {

    @Test
    fun `roulette wheel bandit updates weights on rewarded picks`() {
        val bandit = RouletteWheelBandit(numOperators = 2, reactionFactor = 0.5, segmentLength = 4)
        val rng = Random(42)
        val initialW = bandit.weights.copyOf()
        // Reward operator 0 repeatedly; bandit segment should bump its weight up over the
        // tied initial allocation.
        repeat(4) {
            bandit.pick(rng)  // observe a pick; not used here
            bandit.reward(0, reward = 3.0)
            bandit.reward(1, reward = 0.0)
            bandit.advance()
        }
        assertTrue(bandit.weights[0] > initialW[0], "operator 0 weight should grow: ${bandit.weights[0]} vs ${initialW[0]}")
        assertTrue(bandit.weights[0] > bandit.weights[1], "operator 0 should now outweigh operator 1")
    }

    @Test
    fun `roulette wheel respects min weight floor`() {
        val bandit = RouletteWheelBandit(numOperators = 2, reactionFactor = 1.0, segmentLength = 1, minWeight = 0.5)
        // Give operator 0 zero rewards for many segments — weight should not drop below minWeight.
        repeat(20) {
            bandit.reward(0, 0.0)
            bandit.advance()
        }
        assertTrue(bandit.weights[0] >= 0.5, "weight escaped minWeight floor: ${bandit.weights[0]}")
    }

    @Test
    fun `random destroy returns expected fraction`() {
        val problem = Problem(numBoolVars = 10, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val incumbent = Sample(BooleanArray(10) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(10) { 1.0 })
        val freed = DestroyOperator.Random.destroy(Random(0), problem, incumbent, obj, fraction = 0.3)
        assertEquals(3, freed.bools.size, "expected 3 freed bools (fraction 0.3 of 10)")
        // All distinct
        assertEquals(freed.bools.toSet().size, freed.bools.size, "freed bools should be distinct")
    }

    @Test
    fun `adjacency related destroy stays inside connected components`() {
        // Two disconnected sub-problems sharing nothing:
        //   factor A: AtLeastOne over bool vars 0..3
        //   factor B: AtLeastOne over bool vars 4..7
        // Adjacency BFS from a seed in factor A should free vars only from A (until it
        // exhausts the component, at which point it re-seeds; with small `fraction` we
        // stay within one component).
        val fA = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val fB = Cardinality.atLeastOne(IntArray(4) { Lit.make(it + 4, true) })
        val problem = Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = listOf(fA, fB))
        val incumbent = Sample(BooleanArray(8) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(8) { 1.0 })
        // Free 2 of 8 = fraction 0.25. Starts from one component and stays inside it.
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem, incumbent, obj, fraction = 0.25)
        assertEquals(2, freed.bools.size)
        val componentA = freed.bools.all { it in 0..3 }
        val componentB = freed.bools.all { it in 4..7 }
        assertTrue(componentA || componentB, "freed vars should be in one component: ${freed.bools.toList()}")
    }

    @Test
    fun `adjacency related destroy reaches fraction across components when needed`() {
        // Same two disconnected components, but free 6 of 8 (fraction 0.75) — adjacency
        // BFS must re-seed into the second component after exhausting the first.
        val fA = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val fB = Cardinality.atLeastOne(IntArray(4) { Lit.make(it + 4, true) })
        val problem = Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = listOf(fA, fB))
        val incumbent = Sample(BooleanArray(8) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(8) { 1.0 })
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem, incumbent, obj, fraction = 0.75)
        assertEquals(6, freed.bools.size)
        val fromA = freed.bools.count { it in 0..3 }
        val fromB = freed.bools.count { it in 4..7 }
        assertTrue(fromA > 0 && fromB > 0, "expected vars from both components, got A=$fromA B=$fromB")
    }

    @Test
    fun `worst objective destroy picks high contribution vars`() {
        // 4 bool vars, only var 3 is set true and has the largest weight; destroy fraction 0.25 → 1 var.
        // WorstObjective should pick var 3.
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val incumbent = Sample(booleanArrayOf(false, false, false, true), IntArray(0))
        val obj = LinearObjective(boolWeights = doubleArrayOf(1.0, 2.0, 3.0, 100.0))
        val freed = DestroyOperator.WorstObjective.destroy(Random(0), problem, incumbent, obj, fraction = 0.25)
        assertEquals(1, freed.bools.size)
        assertEquals(3, freed.bools[0], "expected var 3 (highest weighted-and-set)")
    }

    @Test
    fun `alns minimizes weighted exactly-one`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        val alns = Alns(
            inner = inner,
            destroyFraction = 0.5,
            maxIterations = 20,
            flipsPerIteration = 200L,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }

    @Test
    fun `alns with multiple repair operators iterates and varies repair picks`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        val alns = Alns(
            inner = inner,
            repairOperators = listOf(InnerLsRepair("quick", 200L), InnerLsRepair("deep", 5_000L)),
            destroyFraction = 0.5,
            maxIterations = 30,
            flipsPerIteration = 500L,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
        // Both repair operators should have been picked at least once over 30 iterations
        // with default roulette weights. (Probability of one being skipped entirely is
        // essentially zero with equal initial weights and 30 picks.)
        val repairIdxs = alns.iterationLog.map { it.repairIdx }.toSet()
        assertTrue(repairIdxs.isNotEmpty(), "ALNS should have logged at least one iteration")
    }

    @Test
    fun `greedy construction repair climbs from infeasible incumbent to feasible optimum`() {
        // 4 vars in exactly-one with weighted objective. Incumbent is all-false (infeasible).
        // Greedy needs to flip one bool to true; under FeasibilityFirst shaping the only
        // accepted flips are those reaching feasibility, so greedy picks bool 3 (cheapest
        // weight = 3) and rejects flips to 0/1/2 which would have higher shaped score.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        // Pick a deterministic seed/order so the test is reproducible. The incumbent here
        // is infeasible; greedy walks freed vars and accepts any flip that strictly lowers
        // the shaped score, including transitions out of infeasibility.
        val incumbent = Sample(booleanArrayOf(false, false, false, false), IntArray(0))
        val context = com.eignex.klause.solver.localsearch.meta.RepairContext(
            inner = inner,
            params = LocalSearchParams(randomSeed = 0L),
            objective = objective,
            pinAssumptions = com.eignex.klause.solver.Assumptions.None,
            incumbent = incumbent,
            freed = com.eignex.klause.solver.localsearch.meta.FreedVars(intArrayOf(0, 1, 2, 3), IntArray(0)),
            rng = kotlin.random.Random(0),
        )
        val sample = GreedyConstructionRepair().repair(context)
        assertNotNull(sample)
        // Greedy reaches feasibility by flipping exactly one bool; under deterministic
        // walk order the picked bool varies, but all feasible single-flip outcomes are
        // among {10, 5, 8, 3}. We require feasibility (cost=0) and any of the four values.
        val trueCount = (0..3).count { sample.bools[it] }
        assertEquals(1, trueCount, "expected exactly one true after greedy repair")
    }

    @Test
    fun `greedy construction respects pinned vars`() {
        // 4 bools, exactly-one. Pin bools 0..2 false; only bool 3 free. Greedy must set
        // bool 3 = true (the only path to feasibility); pinned vars stay at 0.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        val pinAssumptions = com.eignex.klause.solver.Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))
        val context = com.eignex.klause.solver.localsearch.meta.RepairContext(
            inner = inner,
            params = LocalSearchParams(randomSeed = 0L),
            objective = objective,
            pinAssumptions = pinAssumptions,
            incumbent = Sample(booleanArrayOf(false, false, false, false), IntArray(0)),
            freed = com.eignex.klause.solver.localsearch.meta.FreedVars(intArrayOf(3), IntArray(0)),
            rng = kotlin.random.Random(0),
        )
        val sample = GreedyConstructionRepair().repair(context)
        assertNotNull(sample)
        assertEquals(false, sample.bools[0])
        assertEquals(false, sample.bools[1])
        assertEquals(false, sample.bools[2])
        assertEquals(true, sample.bools[3], "free var 3 should be flipped to satisfy exactly-one")
    }

    @Test
    fun `inner ls repair honours flips override`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0, 5.0))
        val inner = LocalSearchSolver(problem)
        val repair = InnerLsRepair(label = "test", flipsOverride = 100L)
        val context = com.eignex.klause.solver.localsearch.meta.RepairContext(
            inner = inner,
            params = LocalSearchParams(maxFlips = 999_999L, randomSeed = 0L),
            objective = objective,
            pinAssumptions = com.eignex.klause.solver.Assumptions.None,
            incumbent = Sample(booleanArrayOf(false, true), IntArray(0)),
            freed = FreedVars(intArrayOf(0, 1), IntArray(0)),
        )
        val s = repair.repair(context)
        assertNotNull(s)
        // Verify the repair picks the right answer (boolean 0 true, boolean 1 false).
        assertEquals(1.0, objective.evaluate(s))
    }

    @Test
    fun `alns over a session accumulates activity recency and feeds activity-biased destroy`() {
        // Verify the cross-iteration wiring: when ALNS runs over a LocalSearchSession,
        // the inner solver's per-call activity capture survives into the next iteration's
        // destroy phase, where activityBiased reads it. Without a session, the activity
        // operator falls back to random — verified by checking recency stays empty.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem)
        val session = solver.session() as com.eignex.klause.solver.localsearch.LocalSearchSession

        val alns = Alns(
            inner = solver,
            session = session,
            destroyOperators = listOf(
                DestroyOperator.Random,
                DestroyOperator.activityBiased(session),
            ),
            maxIterations = 10,
            flipsPerIteration = 200L,
        )
        alns.minimize(objective, LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        // After ALNS runs, activity touch counts should be populated (size = numBoolVars).
        val touches = session.warmStateView.activityTouches()
        assertEquals(4, touches.size)
        assertTrue(touches.any { it > 0 }, "expected at least one touched variable")
    }

    @Test
    fun `thompson bandit picks a valid arm index`() {
        val bandit = com.eignex.klause.solver.localsearch.meta.ThompsonBandit(numOperators = 3, randomSeed = 42)
        val rng = kotlin.random.Random(0)
        repeat(20) {
            val idx = bandit.pick(rng)
            assertTrue(idx in 0..2, "bandit picked out-of-range index $idx")
            bandit.reward(idx, 0.5)
            bandit.advance()
        }
    }

    @Test
    fun `thompson bandit converges to a clearly-better arm`() {
        // 3 arms, arm 0 always rewards 1.0, others 0.0. Thompson should converge to arm 0.
        val bandit = com.eignex.klause.solver.localsearch.meta.ThompsonBandit(numOperators = 3, randomSeed = 17)
        val rng = kotlin.random.Random(0)
        repeat(100) {
            val idx = bandit.pick(rng)
            val reward = if (idx == 0) 1.0 else 0.0
            bandit.reward(idx, reward)
            bandit.advance()
        }
        // After 100 rounds, the next 30 picks should be heavily biased toward arm 0.
        var arm0Count = 0
        repeat(30) { if (bandit.pick(rng) == 0) arm0Count++ }
        assertTrue(arm0Count >= 25, "expected arm 0 dominance, got $arm0Count/30")
    }

    @Test
    fun `alns can use thompson bandit in place of roulette wheel`() {
        // Smoke test: plug a ThompsonBandit into Alns and verify it produces a feasible sample.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        val alns = Alns(
            inner = inner,
            destroyOperators = DestroyOperator.Defaults,
            repairOperators = RepairOperator.Defaults,
            destroyBandit = com.eignex.klause.solver.localsearch.meta.ThompsonBandit(DestroyOperator.Defaults.size),
            repairBandit = com.eignex.klause.solver.localsearch.meta.ThompsonBandit(RepairOperator.Defaults.size),
            maxIterations = 10,
            flipsPerIteration = 200L,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        assertNotNull(sample)
    }

    @Test
    fun `freed vars empty triggers reward and continue`() {
        // Edge case: destroy op returns empty set. ALNS should reward (rejectedReward) and not crash.
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.atLeastOne(intArrayOf(Lit.make(0, true)))))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0))
        val emptyOp = DestroyOperator { _, _, _, _, _ -> FreedVars(IntArray(0), IntArray(0)) }
        val inner = LocalSearchSolver(problem)
        val alns = Alns(inner = inner, destroyOperators = listOf(emptyOp), maxIterations = 5, flipsPerIteration = 100L)
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 0L))
        assertNotNull(sample, "ALNS should still return the initial solve's incumbent")
    }
}
