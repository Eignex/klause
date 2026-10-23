package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RetainedBoardRestartTest {
    @Test
    fun `a basin attempt should change searched board inputs at the configured strength`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, adaptivePerturbation = false,
            perturbationKind = PerturbationKind.BasinHopping,
        )

        policy.restart(state, anchor)

        assertNotEquals(anchor, state.assignment.snapshot())
        assertEquals(true, policy.lastBoardDiversificationChanged)
        assertEquals(1, policy.perturbationStrength)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `a fully pinned board should explicitly decline diversification`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = LocalSearchState(
            LocalSearchModel.open(problem), Random(7), Assumptions(ints = mapOf(0 to 0L, 1 to 1L)),
        )
        state.randomizeAssignment()
        state.seedImplicitFeasible()
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        policy.restart(state, anchor)

        assertEquals(anchor, state.assignment.snapshot())
        assertEquals(false, policy.lastBoardDiversificationChanged)
    }

    @Test
    fun `one uniform input slot should decline an atomic board swap`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 1)

        policy.restart(state, anchor)

        assertEquals(anchor, state.assignment.snapshot())
        assertEquals(false, policy.lastBoardDiversificationChanged)
    }

    @Test
    fun `a board kick should propagate maintained outputs from searched inputs`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(2, 2)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2), Product(0, 3, 2)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.assignment.setInt(3, 2L)
        state.seedImplicitFeasible()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
        state.recompute()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        policy.restart(state, state.assignment.snapshot())

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(2L, state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `a defined board coordinate should never leave a partial swap`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0), IntDomain(1, 1)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2), Product(2, 3, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.assignment.setInt(3, 1L)
        state.seedImplicitFeasible()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        policy.restart(state, anchor)

        assertEquals(anchor, state.assignment.snapshot())
        assertEquals(false, policy.lastBoardDiversificationChanged)
    }

    @Test
    fun `foreign ownership should never leave a partial board kick`() {
        val problem = Problem(
            0, 4, Array(4) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 3), Circuit(intArrayOf(0, 2, 3))),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.seedImplicitFeasible()
        state.assignment.setInt(1, 0L)
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        policy.restart(state, anchor)

        assertEquals(anchor, state.assignment.snapshot())
        assertEquals(false, policy.lastBoardDiversificationChanged)
    }

    @Test
    fun `restart preparation should retain an effective owned board kick`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, adaptivePerturbation = false,
            perturbationKind = PerturbationKind.BasinHopping,
        )
        val observed = mutableListOf<Pair<Long, Boolean?>>()
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = { state ->
                observed.add(state.assignment.intValue(0) to policy.lastBoardDiversificationChanged)
                null
            },
        )
        val engine = LocalSearchEngine(
            LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy, seedImplicitOnRestart = true,
        )

        engine.resumable(
            LinearObjective(),
            LocalSearchParams(
                maxFlips = 2L, randomSeed = 7L, initialAssignment = Sample(BooleanArray(0), longArrayOf(0, 1)),
            ),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf(0L to null, 1L to true), observed)
    }

    @Test
    fun `crossover should reapply changed assumption pins`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.assignment.setInt(0, 1L)
        state.assignment.setInt(1, 0L)
        state.recompute()
        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0)
        policy.onLocalOptimum(state, state.assignment.snapshot(), 0.0)
        policy.onLocalOptimum(state, state.assignment.snapshot(), 1.0)
        state.assumptions = Assumptions(ints = mapOf(0 to 0L))

        policy.restart(state, null)

        assertEquals(0L, state.assignment.intValue(0))
    }

    @Test
    fun `non board perturbations should retain their assignments and random draws`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val baseline = freshState(problem, 7L)
        val candidate = freshState(problem, 7L)
        val anchor = baseline.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 5, perturbationKind = PerturbationKind.BasinHopping,
        )

        anchorAndPerturb(baseline, anchor, 5, PerturbationKind.BasinHopping)
        policy.restart(candidate, anchor)

        assertEquals(baseline.assignment.snapshot(), candidate.assignment.snapshot())
        assertEquals(baseline.rng.nextLong(), candidate.rng.nextLong())
        assertNull(policy.lastBoardDiversificationChanged)
    }
}
