package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.portfolio.PoolClauseExchange
import com.eignex.klause.portfolio.SharedClausePool
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.kumulant.bandit.univariate.BetaBernoulliTS
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlnsTest {

    @Test
    fun `random destroy returns expected fraction`() {
        val problem = Problem(numBoolVars = 10, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val incumbent = Sample(BooleanArray(10) { false }, LongArray(0))
        val obj = LinearObjective(boolWeights = LongArray(10) { 1L })
        val freed = DestroyOperator.Random.destroy(Random(0), problem, incumbent, obj, fraction = 0.3)
        assertEquals(3, freed.bools.size, "expected 3 freed bools (fraction 0.3 of 10)")
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
        val problem = Problem(
            numBoolVars = 8,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(fA, fB),
        )
        val incumbent = Sample(BooleanArray(8) { false }, LongArray(0))
        val obj = LinearObjective(boolWeights = LongArray(8) { 1L })
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
        val problem = Problem(
            numBoolVars = 8,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(fA, fB),
        )
        val incumbent = Sample(BooleanArray(8) { false }, LongArray(0))
        val obj = LinearObjective(boolWeights = LongArray(8) { 1L })
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem, incumbent, obj, fraction = 0.75)
        assertEquals(6, freed.bools.size)
        val fromA = freed.bools.count { it in 0..3 }
        val fromB = freed.bools.count { it in 4..7 }
        assertTrue(fromA > 0 && fromB > 0, "expected vars from both components, got A=$fromA B=$fromB")
    }

    @Test
    fun `worst objective destroy picks high contribution vars`() {
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val incumbent = Sample(booleanArrayOf(false, false, false, true), LongArray(0))
        val obj = LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L, 100L))
        val freed = DestroyOperator.WorstObjective.destroy(Random(0), problem, incumbent, obj, fraction = 0.25)
        assertEquals(1, freed.bools.size)
        assertEquals(3, freed.bools[0], "expected var 3 (highest weighted-and-set)")
    }

    @Test
    fun `alns minimizes weighted exactly-one`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val alns = Alns(
            inner = inner,
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 20,
            flipsPerIteration = 200L,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }

    @Test
    fun `alns with backtrack CP repair minimizes weighted exactly-one`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem.bake()),
            backtrackParams = BacktrackParams(),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 20,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample), "CP repair reaches the optimal weighted exactly-one")
    }

    @Test
    fun `alns publishes its accepted incumbents to the sink`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val published = mutableListOf<Pair<Sample, Double>>()
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 20,
            acceptance = AcceptanceCriterion.BetterOrEqual,
            improvedSolutionSink = { sample, obj -> published.add(sample to obj) },
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertTrue(published.isNotEmpty(), "at least the initial incumbent must be published")
        assertEquals(objective.evaluate(sample), published.minOf { it.second }, "best published equals the result")
    }

    @Test
    fun `alns adopts a better pooled solution before destroying`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        var polls = 0
        val optimal = Sample(booleanArrayOf(false, false, false, true), LongArray(0))
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 20,
            acceptance = AcceptanceCriterion.BetterOrEqual,
            pooledSolutionSupplier = {
                polls++
                optimal
            },
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertTrue(polls > 0, "the pool must be consulted each iteration")
        assertEquals(3.0, objective.evaluate(sample), "the pooled optimum is adopted as the incumbent")
    }

    @Test
    fun `alns CP repair with a gated shared clause pool stays sound and optimal`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        // Cross-repair clause sharing (#644) with the soundness gate: search-conditioned (permanent)
        // clauses and Farkas nogoods are withheld, so sharing globally-valid learning across repairs
        // must not prune away the optimum.
        val pool = SharedClausePool()
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem.bake()),
            backtrackParams = BacktrackParams(
                clauseExchange = PoolClauseExchange(pool, skipPermanent = true, shareGlobalNogoods = false),
            ),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 10,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample), "gated cross-repair sharing keeps the optimum reachable")
    }

    @Test
    fun `alns with multiple repair operators logs at least one repair pick`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val alns = Alns(
            inner = inner,
            repairOperators = listOf(InnerLsRepair("quick", 200L), InnerLsRepair("deep", 1_000L)),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 12,
            flipsPerIteration = 500L,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
        val repairIdxs = alns.iterationLog.map { it.repairIdx }.toSet()
        assertTrue(repairIdxs.isNotEmpty(), "ALNS should have logged at least one iteration")
    }

    @Test
    fun `greedy construction repair climbs from infeasible incumbent to feasible optimum`() {
        // 4 vars in exactly-one with weighted objective. Incumbent is all-false (infeasible).
        // Greedy needs to flip one bool to true; under FeasibilityFirst shaping the only
        // accepted flips are those reaching feasibility, so greedy picks bool 3 (cheapest
        // weight = 3) and rejects flips to 0/1/2 which would have higher shaped score.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val incumbent = Sample(booleanArrayOf(false, false, false, false), LongArray(0))
        val context = RepairContext(
            inner = inner,
            params = LocalSearchParams(randomSeed = 0L),
            objective = objective,
            pinAssumptions = Assumptions.None,
            incumbent = incumbent,
            freed = FreedVars(intArrayOf(0, 1, 2, 3), IntArray(0)),
            rng = Random(0),
        )
        val sample = GreedyConstructionRepair().repair(context)
        assertNotNull(sample)
        val trueCount = (0..3).count { sample.bools[it] }
        assertEquals(1, trueCount, "expected exactly one true after greedy repair")
    }

    @Test
    fun `greedy construction respects pinned vars`() {
        // 4 bools, exactly-one. Pin bools 0..2 false; only bool 3 free. Greedy must set
        // bool 3 = true (the only path to feasibility); pinned vars stay at 0.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val pinAssumptions = Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))
        val context = RepairContext(
            inner = inner,
            params = LocalSearchParams(randomSeed = 0L),
            objective = objective,
            pinAssumptions = pinAssumptions,
            incumbent = Sample(booleanArrayOf(false, false, false, false), LongArray(0)),
            freed = FreedVars(intArrayOf(3), IntArray(0)),
            rng = Random(0),
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
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 5L))
        val inner = LocalSearchSolver(problem.bake())
        val repair = InnerLsRepair(label = "test", flipsOverride = 100L)
        val context = RepairContext(
            inner = inner,
            params = LocalSearchParams(maxFlips = 999_999L, randomSeed = 0L),
            objective = objective,
            pinAssumptions = Assumptions.None,
            incumbent = Sample(booleanArrayOf(false, true), LongArray(0)),
            freed = FreedVars(intArrayOf(0, 1), IntArray(0)),
        )
        val s = repair.repair(context)
        assertNotNull(s)
        assertEquals(1.0, objective.evaluate(s))
    }

    @Test
    fun `alns over a session accumulates activity recency and feeds activity-biased destroy`() {
        // Verify the cross-iteration wiring: when ALNS runs over a LocalSearchSession,
        // the inner solver's per-call activity capture survives into the next iteration's
        // destroy phase, where activityBiased reads it.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val solver = LocalSearchSolver(problem.bake())
        val session = solver.session()

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
        alns.minimize(objective, LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L)).assignment
        val touches = session.warmStateView.activityTouches()
        assertEquals(4, touches.size)
        assertTrue(touches.any { it > 0 }, "expected at least one touched variable")
    }

    @Test
    fun `alns solves with a kumulant Thompson sampling bandit in place of the roulette wheel`() {
        // Smoke test: plug a MultiArmedBandit(BetaBernoulliTS()) into Alns and verify it
        // produces a feasible sample. The kumulant bandit family is tested in kumulant
        // itself; here we just verify the integration point.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val alns = Alns(
            inner = inner,
            destroyOperators = DestroyOperator.Defaults,
            repairOperators = RepairOperator.Defaults,
            destroyBandit = MultiArmedBandit(
                DestroyOperator.Defaults.size,
                policy = BetaBernoulliTS(),
                random = Random(1),
            ),
            repairBandit = MultiArmedBandit(
                RepairOperator.Defaults.size,
                policy = BetaBernoulliTS(),
                random = Random(2),
            ),
            // BetaBernoulliTS expects rewards in [0, 1]; normalize from the (3, 1, 0) ALNS defaults.
            newBestReward = 1.0,
            acceptedReward = 0.33,
            rejectedReward = 0.0,
            maxIterations = 10,
            flipsPerIteration = 200L,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
    }

    @Test
    fun `freed vars empty triggers reward and continue`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.atLeastOne(intArrayOf(Lit.make(0, true)))),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val emptyOp = DestroyOperator { _, _, _, _, _ -> FreedVars(IntArray(0), IntArray(0)) }
        val inner = LocalSearchSolver(problem.bake())
        val alns = Alns(inner = inner, destroyOperators = listOf(emptyOp), maxIterations = 5, flipsPerIteration = 100L)
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 0L)).assignment
        assertNotNull(sample, "ALNS should still return the initial solve's incumbent")
    }

    @Test
    fun `randomized destroy size varies across iterations`() {
        val problem = Problem(
            numBoolVars = 20,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(20) { Lit.make(it, true) }, min = 5, max = 20)),
        )
        val objective = LinearObjective(boolWeights = LongArray(20) { (it + 1).toLong() })
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.1,
            maxDestroyFraction = 0.6,
            maxIterations = 20,
        )
        alns.minimize(objective, LocalSearchParams(maxFlips = 500L, randomSeed = 1L))
        val freedCounts = alns.iterationLog.map { it.freedCount }.toSet()
        assertTrue(freedCounts.size >= 2, "the destroy size must vary across iterations, got $freedCounts")
    }

    @Test
    fun `acceptanceFor overrides the fixed acceptance and sees the initial objective`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        var seenInitial = Double.NaN
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 12,
            // The fixed acceptance would allow worsening incumbents; the factory's Improving must win.
            acceptance = AcceptanceCriterion.RandomWalk,
            acceptanceFor = { initial ->
                seenInitial = initial
                AcceptanceCriterion.Improving
            },
        )
        alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L))
        assertTrue(seenInitial.isFinite(), "the factory receives the initial incumbent's objective")
        val incumbents = alns.iterationLog.map { it.incumbentObjective }
        assertTrue(
            incumbents.zipWithNext().all { (a, b) -> b <= a },
            "the factory's Improving policy must govern, so the incumbent never worsens: $incumbents",
        )
    }

    /** A local-search stub that never reaches feasibility — its minimize always returns [MinimizeResult.Unknown]. */
    private class NoFeasibleLs(override val problem: BakedProblem) : Optimizer<LocalSearchParams> {
        override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted)

        override fun solve(params: LocalSearchParams) = error("unused")
        override fun samples(params: LocalSearchParams) = error("unused")
        override fun enumerate(params: LocalSearchParams) = error("unused")
    }

    @Test
    fun `falls back to a backtrack bootstrap when local search finds no feasible incumbent`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        // The inner LS never finds feasible; the complete backtrack bootstrap must supply the incumbent
        // so ALNS optimises instead of returning empty.
        val alns = Alns(
            inner = NoFeasibleLs(problem.bake()),
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem.bake()),
            backtrackParams = BacktrackParams(),
            maxIterations = 20,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample, "the backtrack bootstrap must supply a feasible incumbent when LS fails")
        assertEquals(3.0, objective.evaluate(sample), "the bootstrapped incumbent optimises to the true optimum")
    }

    @Test
    fun `alns with noisy greedy repair still reaches the optimum`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            repairOperators = listOf(GreedyConstructionRepair(noise = 0.5)),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 20,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(
            3.0,
            objective.evaluate(sample),
            "insertion noise diversifies but repair stays feasible and optimal",
        )
    }
}
