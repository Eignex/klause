package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchResidualFactor
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchEngineTest {
    @Test
    fun `satisfaction should resume an interrupted pick without a restart instruction`() {
        val problem = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))))
        var expired = false
        var picks = 0
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = {
                if (picks++ == 0) {
                    expired = true
                    null
                } else {
                    Move.BoolFlip(0)
                }
            },
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            greedyRepairOnRestart = false,
        )
        val params = LocalSearchParams(
            maxFlips = 2L,
            randomSeed = 0L,
            initialAssignment = Sample(booleanArrayOf(false), longArrayOf()),
        )

        search.resumableSolve(params).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L))
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)

            assertTrue(assertIs<SolveResult.Sat>(result).assignment.boolValue(0))
        }
    }

    @Test
    fun `feasible optimization should resume an interrupted pick without a restart instruction`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val objective = LinearObjective(boolWeights = longArrayOf(1))
        var expired = false
        var picks = 0
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = {
                if (picks++ == 0) {
                    expired = true
                    null
                } else {
                    Move.BoolFlip(0)
                }
            },
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            greedyRepairOnRestart = false,
        )
        val params = LocalSearchParams(
            maxFlips = 2L,
            randomSeed = 0L,
            initialAssignment = Sample(booleanArrayOf(true), longArrayOf()),
        )

        search.resumable(objective, params).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) {})
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

            assertEquals(0.0, objective.evaluate(assertIs<MinimizeResult.BestFound>(result).sample))
        }
    }

    @Test
    fun `native objective descent should replace support around maintained unit definitions`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            greedyRepairOnRestart = false,
            perMoveInvariants = true,
            definitionalSweep = DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(0, 5, 1, 100))
        val params = LocalSearchParams(
            maxFlips = 2L,
            randomSeed = 0L,
            initialAssignment = Sample(booleanArrayOf(false, false, false, true), longArrayOf()),
        )

        val result = search.improvements(objective, params, warm = null)
            .filterIsInstance<MinimizeResult.WithSample>().last()

        assertEquals(1.0, objective.evaluate(result.sample))
    }

    @Test
    fun `self owned descent should follow feasibility first objectives`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val objective = LinearObjective(boolWeights = longArrayOf(1))
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            scoring = MoveScoring.Break,
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            restartPolicy = AdaptivePerturbationRestart(perturbationStrength = 0),
        )
        val params = LocalSearchParams(
            maxFlips = 2L,
            randomSeed = 0L,
            initialAssignment = Sample(booleanArrayOf(true), longArrayOf()),
        )

        val result = search.improvements(objective, params, warm = null)
            .filterIsInstance<MinimizeResult.WithSample>().last()

        assertEquals(0.0, objective.evaluate(result.sample))
    }

    @Test
    fun `optimization seed cost observations should follow definition reconciliation`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        val observed = mutableListOf<Long>()
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            perturbation = {
                observed += it.bestCostSeen
                null
            },
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            greedyRepairOnRestart = false,
            perMoveInvariants = true,
            definitionalSweep = DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )
        val params = LocalSearchParams(
            maxFlips = 1L,
            initialAssignment = Sample(booleanArrayOf(true, true), longArrayOf()),
        )

        search.improvements(LinearObjective(boolWeights = longArrayOf(1, 0)), params, warm = null).toList()

        assertEquals(listOf(2L), observed)
    }

    @Test
    fun `restart cost observations should follow definition reconciliation`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        val observed = mutableListOf<Long>()
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            perturbation = {
                observed += it.bestCostSeen
                null
            },
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                state.assignment.setBool(0, true)
                state.assignment.setBool(1, true)
                state.recompute()
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            restartPolicy = policy,
            greedyRepairOnRestart = false,
            perMoveInvariants = true,
            definitionalSweep = DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )

        search.samples(LocalSearchParams(maxFlips = 1L), warm = null).toList()

        assertEquals(listOf(2L), observed)
    }

    @Test
    fun `suspended definition evaluation should retain completed outputs`() {
        val assignment = Assignment(1, 513)
        assignment.setBool(0, true)
        val domains = Array(513) { IntDomain(0, 1) }
        val sweep = DefinitionalSweep(List(513) { DefinitionalSweep.SweepNode.Bool2Int(it, 0) })
        val steps = sweep.pinnedSteps(assignment, domains, emptyArray(), Assumptions.None).iterator()
        steps.next()
        steps.next()
        steps.next()
        assertEquals(1L, assignment.intValue(255))
        assertEquals(0L, assignment.intValue(256))

        assignment.setBool(0, false)
        while (steps.hasNext()) steps.next()

        assertTrue((0..255).all { assignment.intValue(it) == 1L })
        assertTrue((256..512).all { assignment.intValue(it) == 0L })
    }

    @Test
    fun `lifetime cancellation should bound suspended initial preparation`() {
        val problem = Problem(1, 513, Array(513) { IntDomain(0, 9) }, emptyArray())
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        var expired = false
        val params = LocalSearchParams(randomSeed = 7L).withLifetimeCancellation(Cancellation { expired })
        val warm = WarmState()
        search.resumableSolve(params, warm).use { handle ->
            var polls = 0
            assertNull(handle.runSlice(Cancellation { ++polls >= 12 }, Long.MAX_VALUE, -1L))
            expired = true

            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertEquals(0.0, handle.stats.ls.moves.sum)
            assertFalse(handle.isDone)
        }
        search.resumableSolve(LocalSearchParams(randomSeed = 7L)).use {
            assertIs<SolveResult.Sat>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
        }
    }

    @Test
    fun `initial preparation pauses should preserve seeded walks and charge only committed instructions`() {
        val problem = Problem(
            65,
            65,
            Array(65) { IntDomain(0, 9) },
            arrayOf<Factor>(
                Product(0, 1, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
            ),
        )
        for (seeded in listOf(false, true)) {
            val traces = listOf(0, 8, 32).map { stopAt ->
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
                    greedyRepairOnRestart = false,
                    definitionalSweep = DefinitionalSweep.infer(problem),
                    perMoveInvariants = true,
                    seedImplicitOnRestart = true,
                )
                val budget = NodeBudget(10L)
                val warm = WarmState().also {
                    it.factorWeights = doubleArrayOf(3.0, 4.0)
                    it.activityTouches = IntArray(130) { 2 }
                }
                val params = LocalSearchParams(
                    maxFlips = 4L,
                    randomSeed = 7L,
                    nodeBudget = budget,
                    assumptions = Assumptions(bools = mapOf(64 to true), ints = mapOf(1 to 1L)),
                    initialAssignment = if (seeded) Sample(BooleanArray(65), LongArray(65) { 1L }) else null,
                )
                search.resumableSolve(params, warm).use { handle ->
                    if (stopAt != 0) {
                        var polls = 0
                        assertNull(handle.runSlice(Cancellation { ++polls >= stopAt }, Long.MAX_VALUE, -1L))
                        assertTrue(trace.isEmpty())
                    }
                    handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
                }
                trace to budget.movesLeft()
            }
            assertTrue(traces.first().first.isNotEmpty())
            assertTrue(traces.all { it == traces.first() })
        }
    }

    @Test
    fun `closing suspended initialization should leave warm state private and permit reopening`() {
        val problem = Problem(
            1,
            513,
            Array(513) { IntDomain(0, 9) },
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
        )
        val params = LocalSearchParams(maxFlips = 1L, randomSeed = 3L)
        for (stopAt in listOf(1, 10, 20, 30, 40, 60, 70)) {
            val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
            val warm = WarmState()
            var polls = 0
            val handle = search.resumableSolve(params, warm)
            assertNull(handle.runSlice(Cancellation { ++polls >= stopAt }, Long.MAX_VALUE, -1L))
            handle.close()
            handle.close()
            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertTrue(warm.activityTouches().isEmpty())
            assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) }
            search.resumableSolve(params, warm).use {
                assertIs<SolveResult.Unknown>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
            }
            assertTrue(warm.bestCostSeen() < Long.MAX_VALUE)
        }
    }

    @Test
    fun `resumable minimization should report active phases and objective views only when requested`() {
        for (enabled in listOf(false, true)) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 3)),
                arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
            )
            val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
            val params = LocalSearchParams(
                maxFlips = 3,
                randomSeed = 3L,
                reportPhaseTimes = enabled,
                initialAssignment = Sample(BooleanArray(0), longArrayOf(0)),
            )

            search.resumable(LinearObjective(intCoefficients = longArrayOf(1)), params).use { handle ->
                val counted = assertIs<InstructionSlicedSearch>(handle)
                assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {})
                val paused = handle.stats.ls.phaseNanos

                assertEquals(paused, handle.stats.ls.phaseNanos)
                assertEquals(enabled, paused.isNotEmpty())
                assertEquals(enabled, handle.stats.ls.objectiveScoring.isNotEmpty())
                if (enabled) {
                    assertEquals(1L, handle.stats.ls.objectiveScoring.getValue("plain"))
                    assertEquals(setOf("preparation", "seeding", "selection", "application", "other"), paused.keys)
                    assertTrue(paused.values.all { it >= 0L })
                    assertTrue(paused.values.sum() > 0L)
                    assertTrue(paused.getValue("selection") > 0L)
                    assertTrue(paused.getValue("application") > 0L)
                }
            }
        }
    }

    @Test
    fun `residual reporting should retain the best committed assignment after a worse move`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
        )
        var picks = 0
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            perturbation = { Move.IntSet(0, longArrayOf(1, 3, 0)[picks++]) },
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            strategy = strategy,
            greedyRepairOnRestart = false,
        )
        val params = LocalSearchParams(
            maxFlips = 3,
            reportResiduals = true,
            initialAssignment = Sample(BooleanArray(0), longArrayOf(0)),
        )

        search.resumable(LinearObjective(intCoefficients = longArrayOf(1)), params).use { handle ->
            val counted = assertIs<InstructionSlicedSearch>(handle)
            assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 2L) {})
            val paused = handle.stats.ls.bestResidual

            val result = assertIs<MinimizeResult.Unknown>(
                counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 2L) {},
            )

            assertEquals(7L, paused?.cost)
            assertEquals(mapOf("Linear" to 7L), result.stats.ls.bestResidual?.byKind)
            assertEquals(
                listOf(
                    LocalSearchResidualFactor(
                        0,
                        "Linear",
                        7L,
                        emptyMap(),
                        mapOf(0 to 3L),
                        details = mapOf(
                            "op" to "LE",
                            "bound" to "-10",
                            "term.0" to "-1:i0",
                            "omittedTerms" to "0",
                            "activity" to "-3",
                            "i0.definition.kind" to "none",
                        ),
                    ),
                ),
                result.stats.ls.bestResidual?.factors,
            )
        }
    }

    @Test
    fun `satisfaction should resume after projection preparation expires`() {
        var expired = false
        val factors = Array<Factor>(512) { fid ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override val boolVars: IntArray
                    get() {
                        if (fid == 255) expired = true
                        return clause.boolVars
                    }
            }
        }
        val problem = Problem(1, 0, emptyArray(), factors + Clause(intArrayOf(Lit.make(0, true))))
        expired = false
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val warm = WarmState()
        val params = LocalSearchParams(maxFlips = 2L, initialAssignment = Sample(booleanArrayOf(true), LongArray(0)))

        search.resumableSolve(params, warm).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L))
            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertEquals(0.0, handle.stats.ls.moves.sum)

            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertTrue(result.assignment.bools[0])
        }
    }

    @Test
    fun `satisfaction should resume initial scoring without repeating completed factors`() {
        var expired = false
        val initialized = IntArray(512)
        val factors = Array<Factor>(initialized.size) { _ ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override fun initialize(state: LocalSearchState, factorId: Int) {
                    initialized[factorId]++
                    if (factorId == 255) expired = true
                }
            }
        }
        val problem = Problem(1, 0, emptyArray(), factors + Clause(intArrayOf(Lit.make(0, true))))
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val warm = WarmState()
        val params = LocalSearchParams(maxFlips = 2L, initialAssignment = Sample(booleanArrayOf(false), LongArray(0)))

        search.resumableSolve(params, warm).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L))
            assertEquals(256, initialized.sum())
            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false

            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertTrue(result.assignment.bools[0])
            assertTrue(initialized.all { it == 1 })
        }
    }

    @Test
    fun `optimization should not publish a partially scored assignment`() {
        var expired = false
        val initialized = IntArray(512)
        val factors = Array<Factor>(initialized.size) { _ ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override fun initialize(state: LocalSearchState, factorId: Int) {
                    initialized[factorId]++
                    if (factorId == 255) expired = true
                }
            }
        }
        val problem = Problem(
            1,
            0,
            emptyArray(),
            factors + arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
        )
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(boolWeights = longArrayOf(1)),
            LocalSearchParams(maxFlips = 1L),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) { found += it.sample })
            assertEquals(256, initialized.sum())
            assertTrue(found.isEmpty())
            expired = false

            val result = assertIs<MinimizeResult.Unknown>(
                handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { found += it.sample },
            )

            assertTrue(found.isEmpty())
            assertEquals(1.0, result.stats.ls.incumbentViolation)
        }
    }

    @Test
    fun `optimization should complete initial greedy repair after a cancelled slice`() {
        val problem = Problem(
            32,
            0,
            emptyArray(),
            Array<Factor>(32) { Clause(intArrayOf(Lit.make(it, true))) },
        )
        val search = LocalSearchEngine(LocalSearchModel.open(problem))
        val found = mutableListOf<Sample>()
        var polls = 0

        search.resumable(
            LinearObjective(boolWeights = LongArray(32) { 1L }),
            LocalSearchParams(maxFlips = 1L, randomSeed = 3L),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { ++polls >= 3 }, Long.MAX_VALUE, -1L) { found += it.sample })
            assertTrue(found.isEmpty())

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { found += it.sample }

            assertIs<MinimizeResult.BestFound>(result)
            assertTrue(found.single().bools.all { it })
        }
    }

    @Test
    fun `local search should run a nonlinear factor over wide domains`() {
        val wide = IntDomain(0, 1L shl 40)
        val maxOf = ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)
        val model = Problem(0, 3, Array(3) { wide }, arrayOf<Factor>(maxOf))

        assertTrue(localSearchSupports(LocalSearchModel.of(model.bake())))
    }

    private fun wideSchedule(): Problem = Problem(
        0,
        2,
        Array(2) { IntDomain(0, 1L shl 40) },
        arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1)),
    )

    @Test
    fun `a schedule over wide domains should be searched`() {
        val result = LocalSearchSolver(wideSchedule().bake()).solve(LocalSearchParams(maxFlips = 1_000, randomSeed = 1))

        val starts = assertIs<SolveResult.Sat>(result).assignment.ints
        assertTrue(abs(starts[0] - starts[1]) >= 2, "starts=${starts.toList()}")
    }

    @Test
    fun `a resumed local search should retain its bound lifetime cancellation scope`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)),
        )
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        var cancelled = false
        val params = LocalSearchParams(
            randomSeed = 3L,
            initialAssignment = Sample(BooleanArray(0), longArrayOf(0)),
        ).withLifetimeCancellation(Cancellation { cancelled })

        search.resumable(LinearObjective(intCoefficients = longArrayOf(1)), params).use { handle ->
            val counted = assertIs<InstructionSlicedSearch>(handle)
            assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {})
            val before = handle.stats.ls.moves.sum
            cancelled = true

            assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {})

            assertEquals(before, handle.stats.ls.moves.sum)
        }
    }
}
