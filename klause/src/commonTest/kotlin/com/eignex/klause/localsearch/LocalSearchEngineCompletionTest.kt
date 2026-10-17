package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchEngineCompletionTest {
    @Test
    fun `continuous satisfaction should complete before requiring zero heuristic violation`() {
        val problem = Problem(
            0, 0, emptyArray(),
            arrayOf(ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ofLong(3)),
                LinearOp.EQ, BigFraction.ONE)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val search = LocalSearchEngine(LocalSearchModel.of(problem.bake()),
            greedyRepairOnRestart = false, completion = LeafRealCompletion(problem, null))
        val initial = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.0))

        val result = search.solve(LocalSearchParams(maxFlips = 1, initialAssignment = initial), null)

        val point = assertIs<SolveResult.Sat>(result).assignment
        assertEquals(listOf(BigFraction.ofLong(3).reciprocal()), point.exactReals)
        assertTrue(result.stats.ls.completions.sum > 0)
    }

    @Test
    fun `continuous optimization should publish a charged completion beyond its move slice`() {
        val problem = Problem(
            0, 0, emptyArray(),
            arrayOf(ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ofLong(3)),
                LinearOp.GE, BigFraction.ONE)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val search = LocalSearchEngine(LocalSearchModel.of(problem.bake()),
            greedyRepairOnRestart = false, completion = LeafRealCompletion(problem, objective))
        val initial = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.0))
        val budget = NodeBudget(100)

        val result = search.improvements(objective,
            LocalSearchParams(maxFlips = 1, initialAssignment = initial, nodeBudget = budget), null).last()

        val best = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(listOf(BigFraction.ofLong(3).reciprocal()), best.sample.exactReals)
        assertEquals(TerminationReason.BudgetExhausted, best.reason)
        assertTrue(budget.spent > 0)
    }

    @Test
    fun `an exhausted allowance should not begin continuous completion`() {
        val problem = Problem(
            0, 0, emptyArray(),
            arrayOf(ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ofLong(3)),
                LinearOp.EQ, BigFraction.ONE)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val search = LocalSearchEngine(LocalSearchModel.of(problem.bake()),
            completion = { _, _ -> error("an exhausted allowance cannot begin completion") })
        val params = LocalSearchParams(nodeBudget = NodeBudget(0))

        val solve = search.solve(params, null)
        val optimize = search.improvements(LinearObjective(realCoefficients = doubleArrayOf(1.0)), params, null).last()

        assertEquals(TerminationReason.BudgetExhausted, assertIs<SolveResult.Unknown>(solve).reason)
        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(optimize).reason)
        assertEquals(0L, assertNotNull(params.nodeBudget).spent)
    }

    // x + k = 2.5 and x ≤ 1 over a continuous x in [0, 10] and an integer k in [0, 5]: k ≥ 2 and x = 2.5 - k.
    private fun mixedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(10.0),
    )

    private fun engine(completion: CandidateCompletion) =
        LocalSearchEngine(LocalSearchModel.of(mixedProblem().bake()), completion = completion)

    @Test
    fun `cancellation during completion should preserve the candidate`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), reals = doubleArrayOf(0.5))
        val search = engine { candidate, cancellation ->
            if (cancellation()) Completion.Undecided() else Completion.Witness(candidate)
        }
        var polls = 0

        search.resumableSolve(LocalSearchParams(maxFlips = 1L, initialAssignment = initial)).use { handle ->
            val paused = handle.runSlice(Cancellation { ++polls >= 3 }, Long.MAX_VALUE, -1L)

            assertNull(paused)
            assertFalse(handle.isDone)
            assertEquals(0.0, handle.stats.ls.moves.sum)
            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
            assertEquals(initial, result.assignment)
        }
    }

    @Test
    fun `optimization should resume a candidate whose completion was cancelled`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), reals = doubleArrayOf(0.5))
        var expired = false
        var calls = 0
        val search = engine { candidate, _ ->
            if (++calls == 1) {
                expired = true
                Completion.Undecided()
            } else {
                Completion.Witness(candidate)
            }
        }
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(1)),
            LocalSearchParams(maxFlips = 1L, initialAssignment = initial),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) { found += it.sample })
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false
            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { found += it.sample }

            assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
            assertEquals(listOf(initial), found)
        }
    }

    @Test
    fun `a model with continuous columns should be declined without a completion`() {
        val result = LocalSearchSolver(mixedProblem().bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))

        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(result).reason)
    }

    @Test
    fun `a candidate over continuous columns should satisfy its rows within tolerance`() {
        val result = engine { candidate, _ ->
            Completion.Witness(
                candidate,
            )
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val x = sample.approximateRealValue(0)
        val k = sample.ints[0]
        assertTrue(abs(x + k - 2.5) <= 1e-6 && x <= 1.0 + 1e-6, "x=$x k=$k")
    }

    @Test
    fun `search should continue past a refuted candidate`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted(intArrayOf(0)) else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Sat>(result)
        assertEquals(2, calls)
    }

    @Test
    fun `completion work should be charged to the search budget`() {
        var calls = 0
        val result = engine { _, _ ->
            calls++
            Completion.Refuted(work = 2_000L)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Unknown>(result)
        assertTrue(calls in 1..3, "calls=$calls")
    }

    @Test
    fun `decided candidates should be counted in the stats`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted() else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val ls = assertIs<SolveResult.Sat>(result).stats.ls
        assertEquals(2.0, ls.completions.sum)
        assertEquals(1.0, ls.completionsRefuted.sum)
    }

    @Test
    fun `numerical completion should improve the original mixed MPS objective`() {
        val parsed = Mps.parse(
            """
            NAME FIXEDCOST
            ROWS
             N COST
             G COVER
            COLUMNS
             M0 'MARKER' 'INTORG'
             B COST 1 COVER 1
             M1 'MARKER' 'INTEND'
             X COST 10 COVER 1
            RHS
             RHS COVER 1
            BOUNDS
             BV BND B
             LO BND X 0
             UP BND X 1
            ENDATA
            """.trimIndent(),
        )
        for (source in listOf(parsed.toProblem(), parsed.toExactSourceProblem())) {
            val objective = checkNotNull(source.objective).toLinearObjective()
            val search = LocalSearchEngine(
                LocalSearchModel.of(source.model.bake()),
                greedyRepairOnRestart = false,
                completion = LeafRealCompletion(source.model, objective),
            )
            val coordinates = DoubleArray(source.model.numRealVars) { if (it == 0) 1.0 else 10.0 }
            val initial = Sample(BooleanArray(0), longArrayOf(0), coordinates)

            val result = search.improvements(
                objective,
                LocalSearchParams(maxInstructions = 5, randomSeed = 3, initialAssignment = initial),
                null,
            ).last()

            val best = assertIs<MinimizeResult.BestFound>(result)
            val checked = source.sourceWitness(best.sample.ints, best.sample.exactReals)
            assertEquals(BigFraction.ofLong(10), source.sourceWitness(initial.ints, listOf(BigFraction.ONE)).objective)
            assertEquals(BigFraction.ONE, checked.objective)
            assertEquals(listOf(BigFraction.ONE, BigFraction.ZERO), checked.values)
        }
    }

    @Test
    fun `continuous savings should admit a directly more expensive integer move`() {
        val supplied = object : MoveSource {
            override val id = MoveSourceId("supplied")
            override val phase = Phase.Feasible
            override val pool = Pool.ScoreOnly
            override fun generate(state: LocalSearchState, sink: MoveSink) = sink.addIntSet(0, 1)
        }
        val strategies = listOf(
            Cbls(),
            SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(supplied)),
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
            ),
        )
        for ((relation, strategy) in listOf(LinearOp.GE, LinearOp.EQ).flatMap { relation ->
            strategies.map { relation to it }
        }) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 1)),
                arrayOf<Factor>(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        relation,
                        1.0,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
            )
            val initial = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0))
            val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0))
            val completed = mutableListOf<Long>()
            val search = LocalSearchEngine(
                LocalSearchModel.open(problem),
                strategy = strategy,
                greedyRepairOnRestart = false,
                completion = { candidate, _ ->
                    val b = candidate.intValue(0)
                    completed += b
                    Completion.Witness(candidate.copy(reals = doubleArrayOf(1.0 - b)))
                },
            )

            val result = search.improvements(
                objective,
                LocalSearchParams(maxInstructions = 20, randomSeed = 3, initialAssignment = initial),
                null,
            ).last()

            val best = assertIs<MinimizeResult.BestFound>(result)
            assertTrue(1L in completed)
            assertEquals(1.0, best.objective)
            assertEquals(1L, best.sample.intValue(0))
            assertEquals(0.0, best.sample.approximateRealValue(0))
            assertEquals(1.0, best.sample.intValue(0) + best.sample.approximateRealValue(0))
        }
    }

    @Test
    fun `failed continuous move probes should preserve the incumbent and spend their work`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        for (failure in listOf(Completion.Refuted(work = 2), Completion.Undecided(work = 2))) {
            var calls = 0
            val search = engine { candidate, _ ->
                calls++
                if (candidate.intValue(0) == 2L) Completion.Witness(candidate) else failure
            }

            val result = search.improvements(
                LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
                LocalSearchParams(maxInstructions = 2, randomSeed = 3, initialAssignment = initial),
                null,
            ).last()

            val best = assertIs<MinimizeResult.BestFound>(result)
            assertEquals(initial, best.sample)
            assertEquals(7.0, best.objective)
            assertEquals(2, calls)
            assertEquals(2.0, best.stats.ls.moves.sum)
        }
    }

    @Test
    fun `continuous move probing should stop at the containing move allowance`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        val budget = NodeBudget(5)
        var calls = 0
        val search = engine { candidate, _ ->
            calls++
            if (candidate.intValue(0) == 2L) Completion.Witness(candidate) else Completion.Undecided(work = 4)
        }

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 20, nodeBudget = budget, initialAssignment = initial),
            null,
        ).last()

        assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
        assertTrue(budget.exhausted())
        assertEquals(2, calls)
    }

    @Test
    fun `residual finite repairs should complete before raw objective rejection`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ONE,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val source = object : MoveSource {
            override val id = MoveSourceId("supplied")
            override val phase = Phase.Infeasible
            override val pool = Pool.ScoreOnly
            override fun generate(state: LocalSearchState, sink: MoveSink) = sink.addIntSet(0, 1)
        }
        for (weight in listOf(0L, 1L)) {
            val search = LocalSearchEngine(
                LocalSearchModel.open(problem),
                greedyRepairOnRestart = false,
                strategy = SourceDrivenStrategy(
                    sources = listOf(ConfiguredSource(source)),
                    feasibleDescent = FeasibleDescent.SelfOwned,
                    feasibleAcceptance = AcceptanceRule.GreedyDescent,
                ),
                completion = { candidate, _ ->
                    Completion.Witness(
                        candidate.copy(
                            reals = doubleArrayOf(
                                if (candidate.intValue(0) == 0L) 1.0 - 1e-12 else 0.0,
                            ),
                        ),
                    )
                },
            )

            val result = search.improvements(
                LinearObjective(intCoefficients = longArrayOf(weight), realCoefficients = doubleArrayOf(10.0)),
                LocalSearchParams(
                    maxInstructions = 3,
                    initialAssignment = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0)),
                ),
                null,
            ).last()

            val best = assertIs<MinimizeResult.BestFound>(result)
            assertEquals(weight.toDouble(), best.objective)
            assertEquals(1L, best.sample.intValue(0))
            assertEquals(0.0, best.sample.approximateRealValue(0))
        }
    }

    @Test
    fun `supplied residual incumbents should use charged completion before repair`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ONE,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val initial = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0 - 1e-12))
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            completion = { candidate, _ -> Completion.Witness(candidate, work = 4) },
        )

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 4, initialAssignment = initial),
            null,
        ).last()

        val best = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(initial, best.sample)
        assertEquals(4.0, best.stats.ls.moves.sum)
        assertEquals(1.0, best.stats.ls.completions.sum)
    }

    @Test
    fun `supplied mixed starts should retain exact finite row checks`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0)),
                    listOf(BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ONE,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            completion = { candidate, _ -> Completion.Witness(candidate.copy(reals = doubleArrayOf(0.0))) },
        )

        val first = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(
                maxInstructions = 4,
                initialAssignment = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0)),
            ),
            null,
        ).filterIsInstance<MinimizeResult.BestFound>().first()

        assertEquals(1L, first.sample.intValue(0))
    }

    @Test
    fun `interrupted residual start completion should resume before repair`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ONE,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val initial = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0 - 1e-12))
        var expired = false
        var interrupted = false
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            completion = { candidate, _ ->
                if (!interrupted) {
                    interrupted = true
                    expired = true
                    Completion.Undecided(work = 1)
                } else {
                    Completion.Witness(candidate, work = 3)
                }
            },
        )
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 4, initialAssignment = initial),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1) { found += it.sample })
            assertTrue(found.isEmpty())
            expired = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { found += it.sample }

            val best = assertIs<MinimizeResult.BestFound>(result)
            assertEquals(initial, best.sample)
            assertEquals(listOf(initial), found)
            assertEquals(4.0, best.stats.ls.moves.sum)
        }
    }

    @Test
    fun `worse restart states should retain ordinary completion admission`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val initial = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(0.0))
        var calls = 0
        var restarts = 0
        val restart = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int) = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                restarts++
                state.assignment.setInt(0, 0)
                state.assignment.setReal(0, 1.0)
                state.recompute()
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            restartPolicy = restart,
            strategy = SourceDrivenStrategy(
                sources = emptyList(),
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
            ),
            completion = { candidate, _ ->
                calls++
                Completion.Witness(candidate)
            },
        )

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 3, initialAssignment = initial),
            null,
        ).last()

        assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
        assertTrue(restarts > 0)
        assertEquals(1, calls)
    }

    @Test
    fun `unproductive completion descent should return residual rows to repair`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE),
                    LinearOp.GE,
                    BigFraction.ONE,
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        var repairs = 0
        val source = object : MoveSource {
            override val id = MoveSourceId("supplied")
            override val phase = Phase.Infeasible
            override val pool = Pool.ScoreOnly
            override fun generate(state: LocalSearchState, sink: MoveSink) {
                repairs++
                sink.addRealSet(0, 1.0)
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(source), ConfiguredSource(ObjectiveSeed())),
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
            ),
            completion = { candidate, _ ->
                Completion.Witness(candidate.copy(reals = doubleArrayOf(1.0 - 1e-12)))
            },
        )

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(
                maxInstructions = 3,
                initialAssignment = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0)),
            ),
            null,
        ).last()

        assertTrue(repairs > 0)
        assertEquals(0L, assertIs<MinimizeResult.BestFound>(result).sample.intValue(0))
    }

    @Test
    fun `completion work should leave search steps between numerical move probes`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        var picks = 0
        val queriedAt = mutableListOf<Int>()
        val source = object : MoveSource {
            override val id = MoveSourceId("supplied")
            override val phase = Phase.Feasible
            override val pool = Pool.ScoreOnly
            override fun generate(state: LocalSearchState, sink: MoveSink) {
                sink.addIntSet(0, (++picks % 2).toLong())
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(mixedProblem()),
            greedyRepairOnRestart = false,
            strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(source)),
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
                feasibleResampleCap = 20,
            ),
            completion = { candidate, _ ->
                if (candidate.intValue(0) == 2L) {
                    Completion.Witness(candidate)
                } else {
                    queriedAt += picks
                    Completion.Refuted(work = 4)
                }
            },
        )

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 20, initialAssignment = initial),
            null,
        ).last()

        assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
        assertTrue(queriedAt.size >= 2)
        assertTrue(queriedAt.zipWithNext().all { (first, second) -> second - first >= 4 })
    }

    @Test
    fun `unavailable objective sources should not add completion exploration`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val initial = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0))
        for (source in listOf(
            ConfiguredSource(ObjectiveSeed(), cap = 0),
            ConfiguredSource(ObjectiveSeed(), enabled = false),
        )) {
            var calls = 0
            val search = LocalSearchEngine(
                LocalSearchModel.open(problem),
                greedyRepairOnRestart = false,
                strategy = SourceDrivenStrategy(
                    sources = listOf(source),
                    feasibleDescent = FeasibleDescent.SelfOwned,
                    feasibleAcceptance = AcceptanceRule.GreedyDescent,
                ),
                completion = { candidate, _ ->
                    calls++
                    Completion.Witness(candidate.copy(reals = doubleArrayOf(1.0 - candidate.intValue(0))))
                },
            )

            val result = search.improvements(
                LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
                LocalSearchParams(maxInstructions = 1, initialAssignment = initial),
                null,
            ).last()

            assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `continuous move exploration should preserve finite invocation pins`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        val candidates = mutableListOf<Long>()
        val search = engine { candidate, _ ->
            candidates += candidate.intValue(0)
            Completion.Witness(candidate)
        }

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(
                maxInstructions = 3,
                initialAssignment = initial,
                assumptions = Assumptions(ints = mapOf(0 to 2L)),
            ),
            null,
        ).last()

        assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
        assertEquals(listOf(2L), candidates)
    }

    @Test
    fun `cancellation after completed selection should keep the live assignment private`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val initial = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0))
        var live: LocalSearchState? = null
        var expired = false
        var interrupted = false
        var probes = 0
        val source = object : MoveSource {
            override val id = MoveSourceId("supplied")
            override val phase = Phase.Feasible
            override val pool = Pool.ScoreOnly
            override fun generate(state: LocalSearchState, sink: MoveSink) {
                live = state
                sink.addIntSet(0, 1)
            }
        }
        val search = LocalSearchEngine(
            LocalSearchModel.open(problem),
            greedyRepairOnRestart = false,
            strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(source)),
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
                tabu = TabuFilter(dynamicTenure = {
                    if (probes > 0 && !interrupted) {
                        expired = true
                        interrupted = true
                    }
                    0
                }),
            ),
            completion = { candidate, _ ->
                if (candidate.intValue(0) == 0L) {
                    Completion.Witness(candidate)
                } else {
                    probes++
                    Completion.Witness(candidate.copy(reals = doubleArrayOf(0.0)))
                }
            },
        )
        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 5, initialAssignment = initial),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) {})
            assertEquals(initial, checkNotNull(live).assignment.snapshot())
            expired = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

            assertEquals(1.0, assertIs<MinimizeResult.BestFound>(result).objective)
            assertEquals(1, probes)
        }
    }

    @Test
    fun `cancelled continuous move probing should resume from the unchanged incumbent`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        var expired = false
        var interrupted = false
        val search = engine { candidate, _ ->
            if (candidate.intValue(0) != 2L && !interrupted) {
                interrupted = true
                expired = true
                Completion.Undecided()
            } else if (candidate.intValue(0) == 2L) {
                Completion.Witness(candidate)
            } else {
                Completion.Refuted(work = 1)
            }
        }
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 3, initialAssignment = initial),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1) { found += it.sample })
            assertEquals(listOf(initial), found)
            expired = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { found += it.sample }

            assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
            assertEquals(listOf(initial), found)
        }
    }

    @Test
    fun `continuous completion should not replace a move with different finite coordinates`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(0.5))
        var rejected = 0
        val search = engine { candidate, _ ->
            if (candidate.intValue(0) == 2L) {
                Completion.Witness(candidate)
            } else {
                rejected++
                Completion.Witness(initial.copy(reals = doubleArrayOf(0.0)), work = 2)
            }
        }

        val result = search.improvements(
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0)),
            LocalSearchParams(maxInstructions = 2, initialAssignment = initial),
            null,
        ).last()

        assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
        assertEquals(1, rejected)
    }
}
