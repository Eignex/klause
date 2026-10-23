package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
    fun `a basin attempt on an unrelated factor should decline board diversification`() {
        val problem = Problem(
            0, 5, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0), IntDomain(1, 1), IntDomain(0, 0)),
            arrayOf<Factor>(Product(2, 3, 4), AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), object : Random() {
            private var draws = 0
            override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else draws++ % 2
        })
        state.assignment.setInt(1, 1L)
        state.assignment.setInt(3, 1L)
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
    fun `a maintained integer target should diversify through its searched board input`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 2)),
            arrayOf<Factor>(AllDifferent(intArrayOf(1, 2), 0, 2), Product(1, 3, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), object : Random() {
            private var draws = 0
            override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else draws++ % 2
        })
        state.assignment.setInt(2, 1L)
        state.assignment.setInt(3, 2L)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 2)

        policy.restart(state, anchor)

        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(2L, state.assignment.intValue(0))
        assertEquals(true, policy.lastBoardDiversificationChanged)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `a maintained Boolean target should diversify through its searched board input`() {
        val problem = Problem(
            1, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 2),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), object : Random() {
            private var draws = 0
            override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else draws++ % 2
        })
        state.assignment.setBool(0, true)
        state.assignment.setInt(1, 1L)
        state.invariants = InvariantNetwork(listOf(EqualityPredicateDefinition(0, 0, 0L)), 2, 1)
        state.recompute()
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 2)

        policy.restart(state, state.assignment.snapshot())

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(true, policy.lastBoardDiversificationChanged)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `two uniform input slots should admit a complete board swap`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 2)

        policy.restart(state, anchor)

        assertNotEquals(anchor, state.assignment.snapshot())
        assertEquals(true, policy.lastBoardDiversificationChanged)
        assertEquals(0L, state.cost)
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
    fun `a kick should decline when ownership blocks a maintained output`() {
        val problem = Problem(
            0, 5, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1)),
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 2), AllDifferent(intArrayOf(2, 3), 0, 2), Product(0, 4, 2),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.assignment.setInt(4, 1L)
        state.seedImplicitFeasible()
        state.assignment.setInt(3, 1L - state.assignment.intValue(0))
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        assertEquals(0L, state.cost)
        policy.restart(state, anchor)

        assertEquals(anchor, state.assignment.snapshot())
        assertEquals(false, policy.lastBoardDiversificationChanged)
    }

    @Test
    fun `a kick should decline when a pin blocks a maintained output`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(2, 2)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2), Product(0, 3, 2)),
        )
        val state = LocalSearchState(
            LocalSearchModel.open(problem), Random(7), Assumptions(ints = mapOf(2 to 0L)),
        )
        state.assignment.setInt(1, 1L)
        state.assignment.setInt(3, 2L)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.recompute()
        val anchor = state.assignment.snapshot()
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, perturbationKind = PerturbationKind.BasinHopping,
        )

        assertEquals(0L, state.cost)
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
    fun `restart preparation should precede an effective owned board kick`() {
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
    fun `a retained board restart should preserve enabled implicit preparation`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 0, adaptivePerturbation = false,
            perturbationKind = PerturbationKind.BasinHopping,
        )
        val observed = mutableListOf<List<Long>>()
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = { state ->
                observed.add((0 until 3).map(state.assignment::intValue))
                null
            },
        )
        val engine = LocalSearchEngine(
            LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy, seedImplicitOnRestart = true,
        )

        engine.resumable(
            LinearObjective(),
            LocalSearchParams(
                maxFlips = 2L, randomSeed = 7L, initialAssignment = Sample(BooleanArray(0), longArrayOf(2, 0, 1)),
            ),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf(listOf(2L, 0L, 1L), listOf(0L, 1L, 2L)), observed)
    }

    @Test
    fun `post restart repair should report an undone board kick as declined`() {
        val problem = Problem(
            0, 32, Array(32) { if (it < 2) IntDomain(0, 1) else IntDomain(0, 0) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 2),
                Linear(longArrayOf(2L), intArrayOf(0), LinearOp.EQ, 0L),
                Linear(longArrayOf(2L), intArrayOf(1), LinearOp.EQ, 2L),
            ),
        )
        val policy = IteratedLocalSearchRestart(
            initialPerturbationStrength = 1, adaptivePerturbation = false,
            perturbationKind = PerturbationKind.BasinHopping,
        )
        val observed = mutableListOf<Pair<List<Long>, Boolean?>>()
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = { state ->
                observed.add((0 until 2).map(state.assignment::intValue) to policy.lastBoardDiversificationChanged)
                null
            },
        )
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)

        engine.resumable(
            LinearObjective(),
            LocalSearchParams(
                maxFlips = 2L, randomSeed = 7L,
                initialAssignment = Sample(BooleanArray(0), LongArray(32) { if (it == 1) 1L else 0L }),
            ),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf(listOf(0L, 1L) to null, listOf(0L, 1L) to false), observed)
    }

    @Test
    fun `board kicks should honor the implicit initialization ownership setting`() {
        for (enabled in listOf(false, true)) {
            val problem = Problem(
                0, 2, Array(2) { IntDomain(0, 1) },
                arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
            )
            val policy = IteratedLocalSearchRestart(
                initialPerturbationStrength = 1, adaptivePerturbation = false,
                perturbationKind = PerturbationKind.BasinHopping,
            )
            val observed = mutableListOf<Boolean>()
            val strategy = SourceDrivenStrategy(
                sources = emptyList(),
                acceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                perturbation = { state ->
                    observed.add(state.seeding.ownerInt != null)
                    null
                },
            )
            val engine = LocalSearchEngine(
                LocalSearchModel.open(problem), strategy = strategy,
                restartPolicy = policy, seedImplicitOnRestart = enabled,
            )

            engine.resumable(
                LinearObjective(),
                LocalSearchParams(
                    maxFlips = 2L, randomSeed = 7L, initialAssignment = Sample(BooleanArray(0), longArrayOf(0, 1)),
                ),
            ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

            assertEquals(listOf(false, enabled), observed)
        }
    }

    @Test
    fun `prepared crossover outcomes should describe the searched board assignment`() {
        for (seed in 0L..7L) {
            val problem = Problem(
                0, 2, Array(2) { IntDomain(0, 1) },
                arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2)),
            )
            val policy = IteratedLocalSearchRestart(
                initialPerturbationStrength = 1, adaptivePerturbation = false, populationSize = 2,
                crossoverRate = 1.0, perturbationKind = PerturbationKind.BasinHopping,
            )
            val observed = mutableListOf<Pair<Sample, Boolean?>>()
            val strategy = SourceDrivenStrategy(
                sources = emptyList(),
                acceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                perturbation = { state ->
                    observed.add(state.assignment.snapshot() to policy.lastBoardDiversificationChanged)
                    null
                },
            )
            val engine = LocalSearchEngine(
                LocalSearchModel.open(problem), strategy = strategy,
                restartPolicy = policy, seedImplicitOnRestart = true,
            )

            engine.resumable(
                LinearObjective(),
                LocalSearchParams(
                    maxFlips = 4L, randomSeed = seed, initialAssignment = Sample(BooleanArray(0), longArrayOf(0, 1)),
                ),
            ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

            assertEquals(4, observed.size)
            for (i in 1 until observed.size) {
                assertEquals(observed[i - 1].first != observed[i].first, observed[i].second)
            }
        }
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
