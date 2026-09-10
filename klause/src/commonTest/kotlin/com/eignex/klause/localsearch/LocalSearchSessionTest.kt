package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.schedule.AdaptiveCooling
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchSessionTest {

    private fun weightLearningProblem(): Problem = Problem(
        numBoolVars = 6,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
        ),
    )

    @Test
    fun `maxInstructions tightens flip budget vs maxFlips when smaller`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake())
        val tight = assertIs<SolveResult.Unknown>(
            solver.solve(
                LocalSearchParams(
                    maxFlips = Long.MAX_VALUE,
                    maxInstructions = 5L,
                    randomSeed = 0L,
                ),
            ),
        )
        assertEquals(5.0, tight.stats.ls.moves.sum, "maxInstructions must cap local-search work")
    }

    @Test
    fun `session captures learned factor weights after a call`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake(), strategy = Cbls())
        val session = LocalSearchSession(solver)
        assertNull(session.warmState.factorWeights)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        val captured = session.warmState.factorWeights
        assertNotNull(captured, "session should capture factorWeights")
        assertEquals(problem.numFactors, captured.size)
        assertTrue(captured.any { it != 1.0 }, "CBLS should learn non-default weights")
    }

    @Test
    fun `reset clears warm state`() {
        val problem = weightLearningProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem.bake(), strategy = Cbls()))
        session.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 2L))
        assertNotNull(session.warmState.factorWeights)
        session.reset()
        assertNull(session.warmState.factorWeights)
    }

    @Test
    fun `warm weights survive across two minimize calls`() {
        val problem = weightLearningProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem.bake(), strategy = Cbls()))
        val obj = LinearObjective(boolWeights = LongArray(6) { 1L })
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 5L))
        val firstWeights = session.warmState.factorWeights!!.copyOf()
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 6L))
        val secondWeights = session.warmState.factorWeights!!
        val allOnes = DoubleArray(problem.numFactors) { 1.0 }
        assertTrue(
            !secondWeights.contentEquals(allOnes),
            "second call should have learned weights, not reset to defaults",
        )
        assertTrue(
            firstWeights.size == secondWeights.size,
            "weight array shape must match across calls",
        )
    }

    @Test
    fun `session implements Session interface and is returned by solver session factory`() {
        val solver = LocalSearchSolver(weightLearningProblem().bake())
        val session: LocalSearchSession = solver.session()
        assertEquals(0, session.depth)
        session.push(Assumptions(bools = mapOf(0 to true)))
        assertEquals(1, session.depth)
        session.pop()
        assertEquals(0, session.depth)
    }

    @Test
    fun `session captures variable activity counts after a call`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake())
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 7L))
        val touches = session.warmStateView.activityTouches()
        assertEquals(6, touches.size, "touches should cover all (bool + int) var slots")
        assertTrue(touches.any { it > 0 }, "expected at least one touched variable")
    }

    @Test
    fun `bestCostSeen watermark survives session call boundaries`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake())
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        val firstWatermark = session.warmStateView.bestCostSeen()
        assertTrue(
            firstWatermark < Int.MAX_VALUE,
            "expected watermark after first call, got $firstWatermark",
        )

        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 2L))
        val secondWatermark = session.warmStateView.bestCostSeen()
        assertTrue(
            secondWatermark <= firstWatermark,
            "watermark must monotone-decrease: $firstWatermark -> $secondWatermark",
        )
    }

    @Test
    fun `reset clears bestCostSeen alongside other warm fields`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake())
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 3L))
        assertTrue(session.warmStateView.bestCostSeen() < Long.MAX_VALUE)
        session.reset()
        assertEquals(
            Long.MAX_VALUE,
            session.warmStateView.bestCostSeen(),
            "reset should restore the bestCost watermark to its empty default",
        )
    }

    @Test
    fun `cbls smoothing bounds weight growth vs bump-only`() {
        fun peakWeightAfterRun(strategy: SourceDrivenStrategy): Double {
            val session = LocalSearchSession(LocalSearchSolver(weightLearningProblem().bake(), strategy = strategy))
            session.sample(LocalSearchParams(maxFlips = 3_000L, randomSeed = 4L))
            return session.warmState.factorWeights!!.max()
        }
        val bumpOnlyPeak = peakWeightAfterRun(Cbls())
        val smoothedPeak = peakWeightAfterRun(Cbls(smoothProb = 1.0, smoothFactor = 0.5))
        assertTrue(bumpOnlyPeak > 1.0, "bump-only run should grow weights, got peak=$bumpOnlyPeak")
        assertTrue(
            smoothedPeak < bumpOnlyPeak,
            "smoothing should bound growth: smoothed=$smoothedPeak vs bump-only=$bumpOnlyPeak",
        )
    }

    @Test
    fun `engine drives per-round feedback to an adaptive cooling schedule`() {
        val cooling = AdaptiveCooling(initialRate = 0.999)
        val strategy = SimulatedAnnealing.withSchedule(cooling, tabu = TabuFilter.Disabled)
        val solver = LocalSearchSolver(weightLearningProblem().bake(), strategy = strategy)
        LocalSearchSession(solver).sample(LocalSearchParams(maxFlips = 6_000L, randomSeed = 4L))
        assertTrue(
            cooling.coolingRate != 0.999,
            "the engine must drive schedule.observe each round; rate stayed at ${cooling.coolingRate}",
        )
    }

    @Test
    fun `bare solver call does not touch the session warm state`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake(), strategy = Cbls())
        val session = LocalSearchSession(solver)
        solver.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 9L))
        assertNull(session.warmState.factorWeights, "bare solver call must not write to session warm state")
    }
}
