package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.DEFAULT_REFACTOR_UPDATE_LIMIT
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds

internal class UnresolvedRealLeafFixture(val withIncumbent: Boolean) {
    val problem = Problem(
        numBoolVars = 0,
        numIntVars = if (withIncumbent) 1 else 0,
        intDomains = if (withIncumbent) arrayOf(IntDomain(0L, 1L)) else emptyArray(),
        factors = arrayOf(
            Linear(
                if (withIncumbent) longArrayOf(1L) else longArrayOf(),
                if (withIncumbent) intArrayOf(0) else intArrayOf(),
                doubleArrayOf(2.0),
                intArrayOf(0),
                LinearOp.EQ,
                if (withIncumbent) 2L else 1L,
            ),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(1.0),
    ).bake()
    val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
    var opened = 0
    var closed = 0
    var visited = 0
    val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
        override fun newPersistentSolver(
            model: LpModel,
            cancellation: Cancellation,
            refactorUpdateLimit: Int,
            iterationLimit: Int,
            workLimit: Long,
            trackDegeneracy: Boolean,
            pricing: LpPricingOptions,
        ): PersistentLpSolver {
            val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation, refactorUpdateLimit,
                iterationLimit, workLimit, trackDegeneracy, pricing)
            val leaf = iterationLimit == 0 && workLimit == 0L
            if (leaf) opened++
            return object : PersistentLpSolver by delegate {
                override fun solve(warm: Basis?): FloatLpResult? {
                    if (leaf) visited++
                    return delegate.solve(warm)
                }

                override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                    if (leaf) visited++
                    return delegate.resolveBounds(allowance)
                }

                override fun close() {
                    if (leaf) closed++
                    delegate.close()
                }
            }
        }
    })
    var acceptProof: (Int, LpCertifier) -> Boolean = { leaf, _ -> withIncumbent && leaf == 1 }
    val attempts = ArrayList<Pair<Int, Boolean>>()
    val context = LpSolveContext(
        factory,
        object : LpCertificationPolicy {
            override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
                val leaf = visited
                attempts += leaf to successful
                return successful && acceptProof(leaf, certifier)
            }
        },
    )
    val solver = BacktrackSolver(problem, context)
    val params = BacktrackParams(
        randomSeed = 0L,
        valueSelector = IndomainMin,
        lpPlan = LpPlan(componentSplit = false),
    )

    fun assertVisitedLeaves(ownersReleased: Boolean = true) {
        assertEquals(if (withIncumbent) 2 else 1, visited)
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
        assertTrue(attempts.any { it.first == (if (withIncumbent) 2 else 1) && it.second })
        assertEquals(if (ownersReleased) opened else 0, closed)
    }

    fun assertIncumbent(sample: Sample) {
        assertEquals(0L, sample.ints.single())
        assertEquals(1.0, sample.reals.single())
        assertEquals(2.0, sample.ints.single() + 2.0 * sample.reals.single())
        assertTrue(sample.reals.single() in 0.0..1.0)
        // The other source-feasible region improves this incumbent: 1 + 2 * (1/2) = 2.
        assertEquals(2.0, 1.0 + 2.0 * 0.5)
        assertTrue(0.5 < objective.evaluate(sample))
    }
}

internal object FiniteCutoffKnapsackFixture {
    val weights = intArrayOf(6, 3)
    val profits = longArrayOf(7, 8)
    val problem = Problem(
        0,
        weights.size,
        Array(weights.size) { IntDomain(0, 1) },
        arrayOf(Linear(weights.copyOf(), IntArray(weights.size) { it }, LinearOp.LE, 25)),
    ).bake()
    val objective = LinearObjective(intCoefficients = LongArray(profits.size) { -profits[it] }, constant = 11L)
    val params = BacktrackParams(
        randomSeed = 62L,
        lubyRestartBase = 1,
        lpPlan = LpPlan(bounding = true, boundEvery = 1, rootMaxWork = 1L),
    )

    fun sourceCost(mask: Int): Long = 11L - profits.indices.sumOf { if (mask and (1 shl it) != 0) profits[it] else 0L }

    fun improvingMasks(cutoff: Long, firstValue: Long? = null): List<Int> =
        (0 until (1 shl weights.size)).filter { mask ->
            (firstValue == null || (mask and 1).toLong() == firstValue) &&
                weights.indices.sumOf { if (mask and (1 shl it) != 0) weights[it] else 0 } <= 25 &&
                sourceCost(mask) < cutoff
        }
}

class ResumableMinimizeTest {
    @Test
    fun `rebind can prove the same optimum after an exhausted fragment`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        ResumableMinimize(solver, objective, BacktrackParams(), pausable = false, rebindable = true).use { search ->
            repeat(3) {
                if (it > 0) search.rebind(Assumptions.None, 100L)
                val result = assertIs<MinimizeResult.Optimal>(
                    search.runSlice(Cancellation.Never, 1000L, 100L) {},
                )
                assertEquals(-1.0, result.objective)
            }
        }
    }

    @Test
    fun `rebind preserves a restricted optimum after LP bounding`() {
        val weights = intArrayOf(6, 7, 2, 3, 5, 9, 4, 3, 4)
        val profits = longArrayOf(7, 5, 4, 3, 8, 6, 5, 8, 6)
        val problem = Problem(
            0,
            weights.size,
            Array(weights.size) { IntDomain(0, 1) },
            arrayOf(Linear(weights.copyOf(), IntArray(weights.size) { it }, LinearOp.LE, 25)),
        ).bake()
        val objective = LinearObjective(intCoefficients = LongArray(profits.size) { -profits[it] })
        val params = BacktrackParams(
            randomSeed = 62L,
            lubyRestartBase = 1,
            lpPlan = LpPlan(bounding = true, boundEvery = 1),
        )

        ResumableMinimize(BacktrackSolver(problem), objective, params, rebindable = true).use { search ->
            val initial = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-38.0, initial.objective)

            search.rebind(Assumptions.None.withInt(0, 0), 100000L)
            val restricted = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-36.0, restricted.objective)
            assertEquals(0L, restricted.sample.ints[0])
            assertTrue(weights.indices.sumOf { weights[it] * restricted.sample.ints[it] } <= 25L)
            assertEquals(-36L, objective.evaluateLong(restricted.sample))
        }
    }

    @Test
    fun `finite cutoff LP fixings preserve improving source assignments across rebind`() {
        val fixture = FiniteCutoffKnapsackFixture
        var cutoff = 4.0
        var observedSession: PropagationSession? = null
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchStart(session: PropagationSession) {
                observedSession = session
            }

            override fun publishGlobal(clause: SharedClause) = Unit
        }
        val params = fixture.params.copy(objectiveBoundSupplier = { cutoff }, clauseExchange = exchange)
        assertEquals(-4L, fixture.improvingMasks(4L).minOf(fixture::sourceCost))
        assertEquals(3L, fixture.improvingMasks(4L, 0L).minOf(fixture::sourceCost))

        ResumableMinimize(
            BacktrackSolver(fixture.problem),
            fixture.objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val initial = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, initial.reason)
            assertEquals(-4.0, initial.objective)
            val initialMask = fixture.weights.indices.sumOf { initial.sample.ints[it].toInt() shl it }
            assertTrue(initialMask in fixture.improvingMasks(4L))
            assertEquals(fixture.sourceCost(initialMask), fixture.objective.evaluateLong(initial.sample))
            assertTrue(search.stats.lp.nodePasses.sum > 0.0)
            assertTrue(search.stats.lp.fixed.sum > 0.0)
            assertTrue(search.stats.lp.rootReducedCostFixes.sum > 0.0)

            val session = assertNotNull(observedSession)
            assertEquals(0, session.decisionLevel)
            assertEquals(1L, session.intDomain(1).min)
            assertEquals(1L, session.intDomain(1).max)
            for (mask in fixture.improvingMasks(4L)) {
                for (v in fixture.weights.indices) {
                    assertTrue((mask shr v and 1).toLong() in session.intDomain(v))
                }
            }

            search.rebind(Assumptions.None.withInt(0, 0), 100000L)
            assertEquals(1L, session.intDomain(1).min)
            for (mask in fixture.improvingMasks(4L, 0L)) {
                for (v in fixture.weights.indices) {
                    assertTrue((mask shr v and 1).toLong() in session.intDomain(v))
                }
            }
            val restricted = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, restricted.reason)
            assertEquals(3.0, restricted.objective)
            val restrictedMask = fixture.weights.indices.sumOf { restricted.sample.ints[it].toInt() shl it }
            assertTrue(restrictedMask in fixture.improvingMasks(4L, 0L))
            assertEquals(fixture.sourceCost(restrictedMask), fixture.objective.evaluateLong(restricted.sample))

            cutoff = 3.0
            search.rebind(Assumptions.None.withInt(0, 0), 100000L)
            val excluded = assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, excluded.reason)
            assertTrue(fixture.improvingMasks(3L, 0L).isEmpty())

            cutoff = 2.0
            search.rebind(Assumptions.None.withInt(0, 1), 100000L)
            for (mask in fixture.improvingMasks(2L, 1L)) {
                for (v in fixture.weights.indices) {
                    assertTrue((mask shr v and 1).toLong() in session.intDomain(v))
                }
            }
            val improving = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, improving.reason)
            assertEquals(-4.0, improving.objective)
            assertEquals(-4L, fixture.improvingMasks(2L, 1L).minOf(fixture::sourceCost))

            cutoff = -4.0
            search.rebind(Assumptions.None, 100000L)
            val exhausted = assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, exhausted.reason)
            assertTrue(fixture.improvingMasks(-4L).isEmpty())
        }
    }

    @Test
    fun `finite cutoff without LP leaves the source domain free`() {
        val fixture = FiniteCutoffKnapsackFixture
        var observedSession: PropagationSession? = null
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchStart(session: PropagationSession) {
                observedSession = session
            }

            override fun publishGlobal(clause: SharedClause) = Unit
        }
        val params = fixture.params.copy(
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 4.0 },
            clauseExchange = exchange,
        )

        ResumableMinimize(
            BacktrackSolver(fixture.problem),
            fixture.objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-4.0, result.objective)
            assertEquals(0.0, search.stats.lp.nodePasses.sum)
            assertEquals(0.0, search.stats.lp.fixed.sum)
            val domain = assertNotNull(observedSession).intDomain(1)
            assertEquals(0L, domain.min)
            assertEquals(1L, domain.max)
        }
    }

    @Test
    fun `raw repair rejects an increasing or NaN cutoff`() {
        val fixture = FiniteCutoffKnapsackFixture
        for (invalid in listOf(5.0, Double.NaN)) {
            var cutoff = 4.0
            val params = fixture.params.copy(objectiveBoundSupplier = { cutoff })
            ResumableMinimize(
                BacktrackSolver(fixture.problem),
                fixture.objective,
                params,
                pausable = false,
                rebindable = true,
            ).use { search ->
                assertIs<MinimizeResult.BestFound>(
                    search.runSlice(Cancellation.Never, 1000L, 100000L) {},
                )
                cutoff = invalid
                search.rebind(Assumptions.None.withInt(0, 2), 100000L)
                assertFailsWith<IllegalArgumentException> {
                    search.runSlice(Cancellation.Never, 1000L, 100000L) {}
                }
            }
        }
    }

    @Test
    fun `an optimization leaf whose assignment violates a factor fails as unsound`() {
        // A selector that stops before any column is fixed stands in for an engine defect that reads an open
        // node as a solved leaf: the minima it reports put both columns of the AllDifferent at 0.
        val stopsEarly = object : VariableSelector {
            override fun pick(session: PropagationSession, rng: Random): VarRef? = null

            override fun fresh(): VariableSelector = this
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val solver = BacktrackSolver(problem.bake())

        assertFailsWith<UnsoundnessException> {
            solver.minimize(objective, BacktrackParams(variableSelector = stopsEarly))
        }
    }

    @Test
    fun `raw repair publishes a second exact improvement with the same displayed score`() {
        val values = listOf(-9_007_199_254_740_992L, -9_007_199_254_740_993L)
        val problem = Problem(0, 1, arrayOf(IntDomain(values.min(), values.max())), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(valueSelector = IndomainMax, objectiveBoundSupplier = { Double.POSITIVE_INFINITY })

        ResumableMinimize(
            BacktrackSolver(problem),
            objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val offers = ArrayList<Long>()
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100L) { offers += it.sample.ints.single() },
            )
            assertEquals(TerminationReason.SearchExhausted, result.reason)
            assertEquals(values, offers)
            assertEquals(values.min(), result.sample.ints.single())
        }
    }

    @Test
    fun `rebind clears an indeterminate verdict after a resolved fragment`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.acceptProof = { _, _ -> false }

        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            val first = assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 100L) {},
            )
            assertEquals(TerminationReason.Unsupported, first.reason)

            fixture.acceptProof = { _, _ -> true }
            search.rebind(Assumptions.None, 100L)
            val second = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100L) {},
            )
            assertEquals(0.5, second.objective)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `constant real leaf completion requires its independently accepted bound`() {
        for (acceptBound in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(false)
            val objective = LinearObjective()
            var opened = 0
            var closed = 0
            var cappedSolves = 0
            var continuationExports = 0
            var boundChecked = false
            var sharedClauses = 0
            var observedSession: PropagationSession? = null
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val leafWork = if (workLimit == 0L && iterationLimit == 0) 1L else workLimit
                    val leafIterations = if (workLimit == 0L && iterationLimit == 0) 1 else iterationLimit
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        leafIterations,
                        leafWork,
                        trackDegeneracy,
                        pricing,
                    )
                    opened++
                    return object : PersistentLpSolver by delegate {
                        override fun solve(warm: Basis?): FloatLpResult? = delegate.solve(warm).also {
                            if (leafWork == 1L) {
                                cappedSolves++
                                assertNull(it)
                                assertTrue(delegate.lastWorkOps >= leafWork)
                            }
                        }

                        override fun continuationBasis(model: LpModel): Basis? =
                            delegate.continuationBasis(model).also {
                                if (leafWork == 1L && it != null) continuationExports++
                            }

                        override fun close() {
                            closed++
                            delegate.close()
                        }
                    }
                }
            }
            val policy = LpCertificationPolicy { certifier, success ->
                if (certifier == LpCertifier.INTEGER) boundChecked = success
                success && (acceptBound || certifier != LpCertifier.INTEGER)
            }
            val context = LpSolveContext(factory, policy)
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) = Unit

                override fun onSearchStart(session: PropagationSession) {
                    observedSession = session
                }

                override fun publishGlobal(clause: SharedClause) {
                    sharedClauses++
                }
            }
            val params = fixture.params.copy(clauseExchange = exchange)
            BacktrackSolver(fixture.problem, context).resumable(objective, params).use { search ->
                val offered = ArrayList<Double>()

                val result = assertNotNull(
                    search.runSlice(Cancellation.Never, 1000L, 256L) {
                        offered += it.objectiveValue
                    },
                )

                val sample = if (acceptBound) {
                    assertIs<MinimizeResult.Optimal>(result).sample
                } else {
                    val incomplete = assertIs<MinimizeResult.BestFound>(result)
                    assertEquals(TerminationReason.Unsupported, incomplete.reason)
                    incomplete.sample
                }
                assertEquals(
                    BigFraction.ONE,
                    assertNotNull(BigFraction.ofDouble(sample.reals.single())) * BigFraction.ofLong(2L),
                )
                assertEquals(0.0, objective.evaluate(sample))
                assertEquals(listOf(0.0), offered)
                assertTrue(search.isDone)
                assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) { error("duplicate incumbent") })
                assertEquals(0, assertNotNull(observedSession).learnedClauseCount)
                assertEquals(0, sharedClauses)
            }
            assertEquals(0, sharedClauses)
            assertEquals(1, cappedSolves)
            assertTrue(continuationExports > 0)
            assertTrue(boundChecked)
            assertEquals(opened, closed)
        }
    }

    @Test
    fun `a real leaf LP is handed the run deadline`() {
        val deadlines = ArrayList<ComparableTimeMark?>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                deadlines += cancellation.deadline()
                return ProductionLpEngineFactory.newPersistentSolver(model, cancellation, refactorUpdateLimit,
                    iterationLimit, workLimit, trackDegeneracy, pricing)
            }
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        ).bake()
        val run = Cancellation.after(60.seconds)
        val params = BacktrackParams(lpPlan = LpPlan(bounding = false, componentSplit = false))

        BacktrackSolver(problem, LpSolveContext(factory))
            .resumable(LinearObjective(realCoefficients = doubleArrayOf(1.0)), params)
            .use { it.runSlice(run, 1000L, 256L) {} }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() }, "every leaf LP budgets against the run's own deadline")
    }

    @Test
    fun `node LPs are handed the run deadline`() {
        val deadlines = ArrayList<ComparableTimeMark?>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                deadlines += cancellation.deadline()
                return ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
            }
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0L, 9L), IntDomain(0L, 9L)),
            factors = arrayOf<Factor>(Linear(longArrayOf(2L, 3L), intArrayOf(0, 1), LinearOp.GE, 7L)),
        ).bake()
        val run = Cancellation.after(60.seconds)
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true, componentSplit = false))

        BacktrackSolver(problem, LpSolveContext(factory))
            .resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L)), params)
            .use { it.runSlice(run, 1000L, 256L) {} }

        assertTrue(deadlines.isNotEmpty())
        assertTrue(deadlines.all { it == run.deadline() }, "every node LP budgets against the run's own deadline")
    }

    @Test
    fun `a genuine shared cutoff retains exhaustive coverage without a local incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val result = BacktrackSolver(problem).minimize(
            objective,
            BacktrackParams(objectiveBoundSupplier = { 0.0 }),
        )
        assertEquals(TerminationReason.SearchExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
        assertEquals(0.0, objective.evaluate(Sample(booleanArrayOf(), longArrayOf(0L))))
    }

    @Test
    fun `verified real point without an optimum is published once before termination`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.acceptProof = { _, certifier -> certifier == LpCertifier.EXACT_POINT }
        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objectiveValue
                },
            )
            assertEquals(TerminationReason.Unsupported, result.reason)
            assertEquals(listOf(0.5), offered)
            assertEquals(0.5, result.sample.reals.single())
            fixture.assertVisitedLeaves(ownersReleased = false)
            assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) { error("duplicate incumbent") })
            search.rebind(Assumptions.None, 256L)
            fixture.acceptProof = { _, _ -> false }
            assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 256L) { error("stale incumbent") },
            )
            assertEquals(1, fixture.opened)
            assertEquals(0, fixture.closed)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `rebind recertifies an unresolved improving region without blocking it`() {
        val fixture = UnresolvedRealLeafFixture(true)
        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            assertIs<MinimizeResult.BestFound>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            fixture.assertVisitedLeaves(ownersReleased = false)
            search.rebind(Assumptions.None.withInt(0, 1L), 256L)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objectiveValue
                },
            )
            assertEquals(listOf(0.5), offered)
            assertEquals(1L, result.sample.ints.single())
            assertEquals(0.5, result.sample.reals.single())
            assertEquals(3, fixture.visited)
            assertEquals(1, fixture.opened)
            assertEquals(0, fixture.closed)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `cancelled slice resumes to an unresolved real terminal`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.solver.resumable(fixture.objective, fixture.params).use { search ->
            assertNull(search.runSlice(Cancellation { true }, 1000L, 256L) {})
            assertEquals(0, fixture.opened)
            val result = assertIs<MinimizeResult.Unknown>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(TerminationReason.Unsupported, result.reason)
            fixture.assertVisitedLeaves()
        }
    }

    @Test
    fun `decision cap does not become real infeasibility`() {
        val fixture = UnresolvedRealLeafFixture(true)
        val result = fixture.solver.minimize(fixture.objective, fixture.params.copy(maxDecisions = 0L))
        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
        assertEquals(0, fixture.opened)
    }

    @Test
    fun `exact real exhaustion retains complete shared coverage`() {
        for (shared in listOf(false, true)) {
            for (feasible in listOf(false, true)) {
                val problem = Problem(
                    0,
                    0,
                    emptyArray(),
                    arrayOf(
                        Linear(
                            longArrayOf(),
                            intArrayOf(),
                            doubleArrayOf(2.0),
                            intArrayOf(0),
                            LinearOp.EQ,
                            if (feasible) 1L else 3L,
                        ),
                    ),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(1.0),
                ).bake()
                val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
                val params = BacktrackParams(
                    objectiveBoundSupplier = if (shared) ({ Double.POSITIVE_INFINITY }) else null,
                )
                val result = BacktrackSolver(problem).minimize(objective, params)
                when {
                    shared && feasible -> assertEquals(
                        TerminationReason.SearchExhausted,
                        assertIs<MinimizeResult.BestFound>(result).reason,
                    )

                    shared -> assertEquals(
                        TerminationReason.SearchExhausted,
                        assertIs<MinimizeResult.Unknown>(result).reason,
                    )

                    feasible -> assertEquals(0.5, assertIs<MinimizeResult.Optimal>(result).sample.reals.single())

                    else -> assertIs<MinimizeResult.Infeasible>(result)
                }
            }
        }
    }

    @Test
    fun `unresolved real leaf cannot prove infeasibility under shared bounds`() {
        for (shared in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(false)
            val params = fixture.params.copy(
                objectiveBoundSupplier = if (shared) ({ Double.POSITIVE_INFINITY }) else null,
            )
            fixture.solver.resumable(fixture.objective, params).use { search ->
                val result = assertIs<MinimizeResult.Unknown>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(TerminationReason.Unsupported, result.reason)
                fixture.assertVisitedLeaves()
                assertTrue(search.isDone)
                assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) {})
            }
        }
    }

    @Test
    fun `unresolved improving region preserves an independently verified incumbent`() {
        for (shared in listOf(false, true)) {
            val fixture = UnresolvedRealLeafFixture(true)
            var bound = Double.POSITIVE_INFINITY
            val params = fixture.params.copy(objectiveBoundSupplier = if (shared) ({ bound }) else null)
            fixture.solver.resumable(fixture.objective, params).use { search ->
                val result = assertIs<MinimizeResult.BestFound>(
                    search.runSlice(Cancellation.Never, 1000L, 256L) {
                        fixture.assertIncumbent(it.sample)
                        bound = it.objectiveValue
                    },
                )
                assertEquals(TerminationReason.Unsupported, result.reason)
                fixture.assertIncumbent(result.sample)
                fixture.assertVisitedLeaves()
            }
        }
    }

    @Test
    fun `carried incumbent must satisfy replacement assumptions and deductions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        for (assumptions in listOf(Assumptions.None.withInt(0, 3L), Assumptions.None.withTightenedMin(0, 2L))) {
            val params = BacktrackParams(randomSeed = 0L)
            val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
            assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
            first.replacingObjective(objective, params.copy(assumptions = assumptions)).use { second ->
                val offered = ArrayList<Long>()
                val result = assertIs<MinimizeResult.Optimal>(
                    second.runSlice(Cancellation.Never, 1000L, 256L) {
                        offered += it.sample.ints[0]
                    },
                )
                assertTrue(offered.all { it >= 2L })
                assertEquals(if (assumptions.numInts > 0) 3L else 2L, result.sample.ints[0])
                second.replacingObjective(objective, params).use { third ->
                    val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                    assertEquals(0L, restored.sample.ints[0])
                }
            }
        }
    }

    @Test
    fun `contradictory replacement deductions discard a carried incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withTightenedMin(0, 4)),
        ).use { second ->
            var offers = 0
            assertIs<MinimizeResult.Infeasible>(second.runSlice(Cancellation.Never, 1000L, 256L) { offers++ })
            assertEquals(0, offers)
        }
    }

    @Test
    fun `replacement can remove an earlier boolean root assumption`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withBool(0, true)),
        ).use { second ->
            val restricted = assertIs<MinimizeResult.Optimal>(second.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(true, restricted.sample.bools[0])
            second.replacingObjective(objective, params).use { third ->
                val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(false, restored.sample.bools[0])
            }
        }
    }

    @Test
    fun `cancelled replacement leaves the original search usable`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        for (initiallyCancelled in listOf(true, false)) {
            var cancelled = initiallyCancelled
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) = Unit
                override fun onSearchStart(session: PropagationSession) {
                    cancelled = true
                }
            }
            ResumableMinimize(BacktrackSolver(problem), objective, params).use { first ->
                assertFailsWith<CancellationException> {
                    first.replacingObjective(
                        objective,
                        params.copy(cancellation = Cancellation { cancelled }, clauseExchange = exchange),
                    )
                }
                val result = assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(0L, result.sample.ints[0])
                first.replacingObjective(objective, params).use { replacement ->
                    assertIs<MinimizeResult.Optimal>(replacement.runSlice(Cancellation.Never, 1000L, 256L) {})
                }
            }
        }
    }

    @Test
    fun `throwing publication token leaves the original search usable`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val failure = IllegalStateException("publication token")
        var initialized = false
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchStart(session: PropagationSession) {
                initialized = true
            }
        }
        val token = Cancellation {
            if (initialized) throw failure
            false
        }
        ResumableMinimize(BacktrackSolver(problem), objective, params).use { first ->
            val thrown = assertFailsWith<IllegalStateException> {
                first.replacingObjective(objective, params.copy(cancellation = token, clauseExchange = exchange))
            }
            assertSame(failure, thrown)
            assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
            first.replacingObjective(objective, params).use { replacement ->
                assertIs<MinimizeResult.Optimal>(replacement.runSlice(Cancellation.Never, 1000L, 256L) {})
            }
        }
    }

    @Test
    fun `a leaf the LP cannot decide is passed over instead of ending the search`() {
        // x0 + x1 + x2 + r >= 5 over x in [0,2], r in [0,10]; with every certifier vetoed no leaf LP decides.
        val n = 3
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Linear(LongArray(n) { 1L }, IntArray(n) { it }, doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        ).bake()
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val objective = LinearObjective(
            intCoefficients = longArrayOf(2L, 3L, 1L),
            realCoefficients = doubleArrayOf(1.5),
        )
        val search = BacktrackSolver(problem, vetoed).resumable(objective, BacktrackParams(randomSeed = 0L))

        var terminal: MinimizeResult? = null
        while (terminal == null) {
            terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { }
        }

        assertTrue(terminal is MinimizeResult.Unknown && search.stats.lp.standalonePasses.sum > 1.0, "$terminal")
    }
    @Test
    fun `cancelled optimization preparation cannot expose partially initialized state`() {
        val fixture = FiniteCutoffKnapsackFixture
        val factory = RecordingLpEngineFactory()
        var cancelled = false
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchStart(session: PropagationSession) {
                cancelled = true
            }
        }
        val params = fixture.params.copy(
            cancellation = Cancellation { cancelled },
            clauseExchange = exchange,
        )
        val solver = BacktrackSolver(fixture.problem, LpSolveContext(factory))

        solver.resumable(fixture.objective, params).use { search ->
            assertFailsWith<SearchInitializationCancelled> {
                search.runSlice(params.cancellation, Long.MAX_VALUE, -1L) {}
            }
        }

        solver.resumable(fixture.objective, fixture.params).use { search ->
            assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }
    }

    @Test
    fun `cancelled one shot optimization returns an unknown verdict`() {
        val fixture = FiniteCutoffKnapsackFixture

        val result = BacktrackSolver(fixture.problem).minimize(
            fixture.objective, fixture.params.copy(cancellation = Cancellation { true }),
        )

        assertIs<MinimizeResult.Unknown>(result)
    }

}
