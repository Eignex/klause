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
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `optimization resumes the state prepared after its slice expires`() {
        var expired = false
        var initializations = 0
        var primed = false
        val restart = FixedCadenceRestart()
        val policy = object : RestartPolicy by restart {
            override fun reset() {
                restart.reset()
                initializations++
            }

            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                restart.restart(state, bestSoFar)
                if (!primed) {
                    expired = true
                    primed = true
                }
            }
        }
        val session = LocalSearchSolver(weightLearningProblem().bake(), restartPolicy = policy).session()
        val objective = LinearObjective(boolWeights = LongArray(6) { 1L })

        session.resumable(objective, LocalSearchParams(maxInstructions = 5L, randomSeed = 1L)).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) {})
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false
            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

            assertIs<MinimizeResult.Unknown>(result)
            assertEquals(5.0, handle.stats.ls.moves.sum)
            assertEquals(1, initializations)
        }
    }

    @Test
    fun `sliced optimization preserves the unsliced walk and learned weights`() {
        val objective = LinearObjective(boolWeights = LongArray(6) { 1L })
        val params = LocalSearchParams(maxFlips = 12L, randomSeed = 7L)
        val uninterrupted = LocalSearchSolver(weightLearningProblem().bake()).session()
        val expected = uninterrupted.minimize(objective, params)
        val sliced = LocalSearchSolver(weightLearningProblem().bake()).session()

        sliced.resumable(objective, params).use { handle ->
            val counted = assertIs<InstructionSlicedSearch>(handle)
            repeat(3) {
                assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {})
                assertFalse(handle.isDone)
            }
            val result = assertNotNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {})

            assertEquals(expected.stats.ls.moves, result.stats.ls.moves)
            assertEquals(expected.stats.ls.incumbentViolation, result.stats.ls.incumbentViolation)
            assertTrue(handle.isDone)
            assertTrue(
                assertNotNull(uninterrupted.warmState.factorWeights)
                    .contentEquals(assertNotNull(sliced.warmState.factorWeights)),
            )
        }
    }

    @Test
    fun `optimization handle keeps the session assumption stack`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val session = LocalSearchSolver(problem.bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))

        session.resumable(LinearObjective(boolWeights = longArrayOf(1)), LocalSearchParams(maxFlips = 1L))
            .use { handle ->
                val result = assertIs<MinimizeResult.BestFound>(
                    handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {},
                )

                assertTrue(result.sample.bools[0])
                assertEquals(1.0, result.objective)
            }
    }

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
    fun `paused satisfaction exports learned weights to the session`() {
        val session = LocalSearchSession(LocalSearchSolver(weightLearningProblem().bake()))

        session.resumableSolve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L)).use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1_000L))

            val captured = assertNotNull(session.warmState.factorWeights)
            assertTrue(captured.any { it != 1.0 })
        }
    }

    @Test
    fun `resumed satisfaction imports the session learned weights`() {
        val session = LocalSearchSession(LocalSearchSolver(weightLearningProblem().bake()))
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        val learned = assertNotNull(session.warmState.factorWeights).copyOf()

        session.resumableSolve(LocalSearchParams(maxFlips = 0L, randomSeed = 2L)).use { handle ->
            handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
        }

        assertTrue(learned.contentEquals(assertNotNull(session.warmState.factorWeights)))
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
