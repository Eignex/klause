package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.freshState
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardRestartOutcomeTest {
    @Test
    fun `an unchanged target should explicitly decline the board restart`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val state = freshState(problem, 7L)
        state.assignment.setInt(1, 1L)
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        restart.afterPolicy(state)
        restart.afterPreparation(state)

        assertEquals(BoardRestartOutcome.DeclinedUnchanged, restart.finish(state))
    }

    @Test
    fun `a surviving searched input change should be reported after preparation`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val state = freshState(problem, 7L)
        state.assignment.setInt(1, 1L)
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        state.apply(Move.Compound(listOf(Move.IntSet(0, 1L), Move.IntSet(1, 0L))))
        restart.afterPolicy(state)
        restart.afterPreparation(state)

        assertEquals(BoardRestartOutcome.ChangedInputs, restart.finish(state))
    }

    @Test
    fun `preparation returning to the preceding board should decline the restart`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val state = freshState(problem, 7L)
        state.assignment.setInt(1, 1L)
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))
        state.apply(Move.Compound(listOf(Move.IntSet(0, 1L), Move.IntSet(1, 0L))))
        restart.afterPolicy(state)

        state.apply(Move.Compound(listOf(Move.IntSet(0, 0L), Move.IntSet(1, 1L))))
        restart.afterPreparation(state)

        assertEquals(BoardRestartOutcome.DeclinedPreparation, restart.finish(state))
    }

    @Test
    fun `repair returning to the preceding board should decline the restart`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val state = freshState(problem, 7L)
        state.assignment.setInt(1, 1L)
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))
        state.apply(Move.Compound(listOf(Move.IntSet(0, 1L), Move.IntSet(1, 0L))))
        restart.afterPolicy(state)
        restart.afterPreparation(state)

        state.apply(Move.Compound(listOf(Move.IntSet(0, 0L), Move.IntSet(1, 1L))))

        assertEquals(BoardRestartOutcome.DeclinedRepair, restart.finish(state))
    }

    @Test
    fun `frozen board inputs should be unavailable for diversification`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val state = LocalSearchState(
            LocalSearchModel.open(problem), Random(7), Assumptions(ints = mapOf(0 to 0L, 1 to 1L)),
        )
        state.randomizeAssignment()
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        assertEquals(BoardRestartOutcome.DeclinedUnavailable, restart.finish(state))
    }

    @Test
    fun `fixed root domains should be unavailable for diversification`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 0), IntDomain(1, 1)), arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.randomizeAssignment()
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        assertEquals(BoardRestartOutcome.DeclinedUnavailable, restart.finish(state))
    }

    @Test
    fun `foreign implicit owners should exclude board inputs from diversification`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(Circuit(intArrayOf(0, 1, 2)), AllDifferent(intArrayOf(0, 1))),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        assertEquals(BoardRestartOutcome.DeclinedUnavailable, restart.finish(state))
    }

    @Test
    fun `maintained board outputs should not count as searched inputs`() {
        val problem = Problem(
            0, 5, Array(5) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1)), Product(2, 4, 0), Product(3, 4, 1)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.assignment.setInt(3, 1L)
        state.assignment.setInt(4, 1L)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        assertEquals(BoardRestartOutcome.DeclinedUnavailable, restart.finish(state))
    }

    @Test
    fun `values outside root domains should not count as admissible changes`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 1), IntDomain(1, 1)), arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.randomizeAssignment()
        state.recompute()
        val restart = assertNotNull(PreparedBoardRestart.capture(state))

        state.assignment.setInt(0, 2L)
        restart.afterPolicy(state)
        restart.afterPreparation(state)

        assertEquals(BoardRestartOutcome.DeclinedUnchanged, restart.finish(state))
    }

    @Test
    fun `non board models should have no board restart outcome`() {
        val state = freshState(Problem(1, 0, emptyArray(), emptyArray()), 7L)

        assertNull(PreparedBoardRestart.capture(state))
    }

    @Test
    fun `greedy repair undoing a kick should not report changed board inputs`() {
        val problem = Problem(
            0, 32, Array(32) { IntDomain(0, if (it < 2) 1 else 0) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1)),
                Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(2), intArrayOf(1), LinearOp.EQ, 2),
            ),
        )
        val outcomes = mutableListOf<BoardRestartOutcome?>()
        for (seed in 0L..7L) {
            val policy = IteratedLocalSearchRestart(
                initialPerturbationStrength = 1, adaptivePerturbation = false,
                perturbationKind = PerturbationKind.BasinHopping,
            )
            val strategy = SourceDrivenStrategy(
                sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                perturbation = { state ->
                    if (policy.lastBoardRestartOutcome != null) {
                        assertEquals(listOf(0L, 1L), (0 until 2).map(state.assignment::intValue))
                        outcomes.add(policy.lastBoardRestartOutcome)
                    }
                    null
                },
            )
            val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)

            engine.resumable(
                LinearObjective(), LocalSearchParams(
                    maxFlips = 2L, randomSeed = seed,
                    initialAssignment = Sample(BooleanArray(0), LongArray(32) { if (it == 1) 1L else 0L }),
                ),
            ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }
        }

        assertEquals(8, outcomes.size)
        assertTrue(outcomes.all {
            it == BoardRestartOutcome.DeclinedRepair || it == BoardRestartOutcome.DeclinedUnchanged
        })
        assertTrue(BoardRestartOutcome.DeclinedRepair in outcomes)
    }

    @Test
    fun `suspended restart repair should keep its outcome pending until completion`() {
        val problem = Problem(
            0, 32, Array(32) { IntDomain(0, if (it < 2) 1 else 0) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))),
        )
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 1)
        val strategy = SourceDrivenStrategy(
            sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)
        var polls = 0

        engine.resumable(
            LinearObjective(), LocalSearchParams(
                maxFlips = 1L, randomSeed = 7L,
                initialAssignment = Sample(BooleanArray(0), LongArray(32) { if (it == 1) 1L else 0L }),
            ),
        ).use { handle ->
            assertNull(handle.runSlice(
                Cancellation { policy.incumbents.isNotEmpty() && ++polls >= 3 }, Long.MAX_VALUE, -1L,
            ) {})
            assertNull(policy.lastBoardRestartOutcome)
            assertEquals(0L, policy.changedBoardRestarts + policy.declinedBoardRestarts)

            val result = assertNotNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})

            assertNotNull(policy.lastBoardRestartOutcome)
            assertEquals(1.0, result.stats.ls.boardRestartChanges.sum + result.stats.ls.boardRestartDeclines.sum)
        }
    }

    @Test
    fun `enabled canonical preparation should attribute erased board changes to preparation`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2))))
        val outcomes = mutableListOf<BoardRestartOutcome?>()
        for (seed in 0L..7L) {
            val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 5, adaptivePerturbation = false)
            val strategy = SourceDrivenStrategy(
                sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                perturbation = {
                    if (policy.lastBoardRestartOutcome != null) outcomes.add(policy.lastBoardRestartOutcome)
                    null
                },
            )
            val engine = LocalSearchEngine(
                LocalSearchModel.open(problem), strategy = strategy,
                restartPolicy = policy, seedImplicitOnRestart = true,
            )

            engine.resumable(
                LinearObjective(), LocalSearchParams(
                    maxFlips = 2L, randomSeed = seed, initialAssignment = Sample(BooleanArray(0), longArrayOf(0, 1, 2)),
                ),
            ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }
        }

        assertEquals(8, outcomes.size)
        assertTrue(outcomes.all {
            it == BoardRestartOutcome.DeclinedPreparation || it == BoardRestartOutcome.DeclinedUnchanged
        })
        assertTrue(BoardRestartOutcome.DeclinedPreparation in outcomes)
    }

    @Test
    fun `reset should clear prepared board outcomes and counts`() {
        val policy = IteratedLocalSearchRestart()
        policy.recordBoardRestart(BoardRestartOutcome.ChangedInputs)
        policy.recordBoardRestart(BoardRestartOutcome.DeclinedRepair)

        policy.reset()

        assertNull(policy.lastBoardRestartOutcome)
        assertEquals(0L, policy.changedBoardRestarts)
        assertEquals(0L, policy.declinedBoardRestarts)
    }

    @Test
    fun `unchanged crossover boards should explicitly decline diversification`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2))))
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 0, adaptivePerturbation = false, populationSize = 2, crossoverRate = 1.0,
        )
        val strategy = SourceDrivenStrategy(
            sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)

        engine.resumable(
            LinearObjective(), LocalSearchParams(
                maxFlips = 3L, randomSeed = 7L, initialAssignment = Sample(BooleanArray(0), longArrayOf(0, 1, 2)),
            ),
        ).use { handle ->
            val result = assertNotNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})

            assertEquals(BoardRestartOutcome.DeclinedUnchanged, policy.lastBoardRestartOutcome)
            assertEquals(3.0, result.stats.ls.boardRestartDeclines.sum)
            assertEquals(0.0, result.stats.ls.boardRestartChanges.sum)
        }
    }

    @Test
    fun `non board restart assignments and random draws should follow the original perturbation`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val baseline = freshState(problem, 7L)
        val candidate = freshState(problem, 7L)
        val anchor = baseline.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 5)

        anchorAndPerturb(baseline, anchor, 5)
        policy.restart(candidate, anchor)

        assertEquals(baseline.assignment.snapshot(), candidate.assignment.snapshot())
        assertEquals(baseline.rng.nextLong(), candidate.rng.nextLong())
        assertNull(policy.lastBoardRestartOutcome)
    }
}
