package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSearchEngineRestartTest {
    @Test
    fun `satisfaction custom restarts should preserve the exact best anchor across pauses and policy mutation`() {
        for (sliced in listOf(false, true)) {
            val problem = Problem(
                65,
                1,
                arrayOf(IntDomain(0, 9)),
                arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
            )
            val anchors = mutableListOf<Sample>()
            val policy = object : RestartPolicy {
                override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= 2
                override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                    val anchor = checkNotNull(bestSoFar)
                    if (anchors.size == 1) {
                        anchors.first().ints[0] = 9L
                        anchors.first().bools[64] = false
                    }
                    anchors += anchor
                    state.assignment.setInt(0, 0L)
                    state.assignment.setBool(0, false)
                    state.assignment.setBool(64, false)
                    state.recompute()
                }
            }
            var picks = 0
            val strategy = SourceDrivenStrategy(
                sources = emptyList(),
                perturbation = {
                    if (picks++ % 2 == 0) {
                        Move.Compound(listOf(Move.BoolFlip(64), Move.IntSet(0, 3L)))
                    } else {
                        Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 2L)))
                    }
                },
                feasibleDescent = FeasibleDescent.SelfOwned,
            )
            val search = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)
            val params = LocalSearchParams(
                maxFlips = 9L,
                randomSeed = 3L,
                initialAssignment = Sample(BooleanArray(65), longArrayOf(0L)),
            )

            if (sliced) {
                search.resumableSolve(params).use { handle ->
                    val counted = assertIs<InstructionSlicedSolve>(handle)
                    while (!handle.isDone) counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
                }
            } else {
                assertTrue(search.samples(params, warm = null).toList().isEmpty())
            }

            assertEquals(3, anchors.size)
            assertEquals(3L, anchors.last().ints.single())
            assertTrue(anchors.last().bools[64])
        }
    }

    @Test
    fun `anchoring satisfaction policies should restart from the best infeasible assignment`() {
        val policies = listOf(
            AdaptivePerturbationRestart(maxFlipsBeforeRestart = 2, perturbationStrength = 0),
            StagnationRestart(maxFlipsBeforeRestart = 2, perturbationStrength = 0),
            IteratedLocalSearchRestart(maxFlipsBeforeRestart = 2, initialPerturbationStrength = 0),
        )
        for (policy in policies) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 9)),
                arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
            )
            val visited = mutableListOf<Long>()
            val strategy = SourceDrivenStrategy(
                sources = emptyList(),
                perturbation = {
                    visited += it.assignment.intValue(0)
                    Move.IntSet(0, if (visited.size == 1) 3L else 2L)
                },
                feasibleDescent = FeasibleDescent.SelfOwned,
            )
            val search = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)
            val params = LocalSearchParams(
                maxFlips = 6L,
                randomSeed = 3L,
                initialAssignment = Sample(BooleanArray(0), longArrayOf(0L)),
            )

            assertTrue(search.samples(params, warm = null).toList().isEmpty())

            assertEquals(listOf(0L, 3L, 3L, 2L), visited)
        }
    }

    @Test
    fun `satisfaction should reset its anchor after each published sample`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
        )
        val anchors = mutableListOf<Sample?>()
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                anchors += bestSoFar
                state.assignment.setInt(0, if (bestSoFar == null) 3L else 2L)
                state.recompute()
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            restartPolicy = policy,
            strategy = SourceDrivenStrategy(sources = emptyList(), feasibleDescent = FeasibleDescent.SelfOwned),
        )
        val params = LocalSearchParams(maxFlips = 4L, initialAssignment = Sample(BooleanArray(0), longArrayOf(0L)))

        val samples = search.openSamples(params, warm = null).use { it.take(3).toList() }

        assertEquals(listOf(0L, 2L, 2L), samples.map { it.ints.single() })
        assertEquals(listOf(null, 3L, null, 3L), anchors.map { it?.ints?.single() })
    }

    @Test
    fun `random satisfaction restarts should preserve seeded moves and RNG draws through assignment preparation`() {
        for (luby in listOf(false, true)) {
            val traces = listOf(false, true).map { custom ->
                val problem = Problem(
                    65,
                    1,
                    arrayOf(IntDomain(0, 9)),
                    arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
                )
                val randomPolicy = if (luby) LubyRestart(unit = 2) else FixedCadenceRestart(maxFlipsBeforeRestart = 2)
                val policy = if (!custom) {
                    randomPolicy
                } else {
                    object : RestartPolicy {
                        override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
                            randomPolicy.shouldRestart(stepsSinceLastRestart)
                        override fun restart(state: LocalSearchState, bestSoFar: Sample?) =
                            randomPolicy.restart(state, null)
                        override fun reset() = randomPolicy.reset()
                    }
                }
                val trace = mutableListOf<Pair<Sample, Long>>()
                val strategy = SourceDrivenStrategy(
                    sources = emptyList(),
                    perturbation = {
                        trace += it.assignment.snapshot() to it.rng.nextLong()
                        Move.IntSet(0, (it.assignment.intValue(0) + 1L) % 10L)
                    },
                    feasibleDescent = FeasibleDescent.SelfOwned,
                )
                val search = LocalSearchEngine(
                    LocalSearchModel.open(problem),
                    strategy = strategy,
                    restartPolicy = policy,
                    seedImplicitOnRestart = true,
                )

                search.resumableSolve(LocalSearchParams(maxFlips = 12L, randomSeed = 7L)).use { handle ->
                    val counted = assertIs<InstructionSlicedSolve>(handle)
                    while (!handle.isDone) counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
                    assertEquals(12L, handle.stats.ls.moves.sum.toLong())
                }
                trace
            }

            assertTrue(traces.first().isNotEmpty())
            assertEquals(traces.first(), traces.last())
        }
    }

    @Test
    fun `custom restarts should retain independent feasible incumbent anchors`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
        )
        val anchors = mutableListOf<Sample>()
        val restart = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= 2
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                if (bestSoFar != null) anchors += bestSoFar
                state.assignment.setInt(0, 3)
                state.recompute()
            }
        }
        var picks = 0
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            perturbation = { if (it.cost == 0L) null else Move.IntSet(0, longArrayOf(1, 2)[picks++]) },
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            restartPolicy = restart,
            greedyRepairOnRestart = false,
        )

        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(-1)),
            LocalSearchParams(maxFlips = 4L, initialAssignment = Sample(BooleanArray(0), longArrayOf(3))),
        ).use { handle ->
            assertIs<MinimizeResult.BestFound>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }

        assertEquals(listOf(Sample(BooleanArray(0), longArrayOf(1)), Sample(BooleanArray(0), longArrayOf(2))), anchors)
    }

    @Test
    fun `optimization should restart without an anchor before finding a feasible incumbent`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(1, true)))),
        )
        val anchors = mutableListOf<Sample?>()
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= 1
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                anchors += bestSoFar
                state.restart()
            }
        }
        val search = LocalSearchEngine(LocalSearchModel.open(problem), restartPolicy = policy)

        search.resumable(
            LinearObjective(boolWeights = longArrayOf(1, 1)),
            LocalSearchParams(
                maxFlips = 3L,
                randomSeed = 3L,
                assumptions = Assumptions(bools = mapOf(0 to false)),
                initialAssignment = Sample(booleanArrayOf(false, false), LongArray(0)),
            ),
        ).use { handle ->
            assertIs<MinimizeResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }

        assertTrue(anchors.isNotEmpty())
        assertTrue(anchors.all { it == null })
    }

    @Test
    fun `optimization should restart from its feasible incumbent`() {
        val problem = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))))
        val anchors = mutableListOf<Sample?>()
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= 1
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                anchors += bestSoFar
                state.assignment.setBool(0, true)
                state.recompute()
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = SourceDrivenStrategy(
                sources = emptyList(),
                feasibleDescent = FeasibleDescent.RatchetAsConstraint,
            ),
            restartPolicy = policy,
        )

        search.resumable(
            LinearObjective(boolWeights = longArrayOf(1)),
            LocalSearchParams(maxFlips = 3L, randomSeed = 3L),
        ).use { handle ->
            assertIs<MinimizeResult.BestFound>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }

        assertTrue(anchors.size > 1)
        assertTrue(anchors.drop(1).all { it?.bools?.single() == true })
    }
}
