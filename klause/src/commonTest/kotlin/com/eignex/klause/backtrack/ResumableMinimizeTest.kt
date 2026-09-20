package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
        override fun newGeneralSolver(model: LpModel, cancellation: Cancellation, pricing: LpPricingOptions): LpSolver {
            val delegate = ProductionLpEngineFactory.newGeneralSolver(model, cancellation, pricing)
            opened++
            return object : LpSolver by delegate {
                override fun close() {
                    closed++
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
                val leaf = factory.calls.count { it.kind == EngineConstruction.GENERAL }
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

    fun assertVisitedLeaves() {
        assertEquals(if (withIncumbent) 2 else 1, factory.calls.count { it.kind == EngineConstruction.GENERAL })
        assertTrue(attempts.any { it.first == (if (withIncumbent) 2 else 1) && it.second })
        assertEquals(opened, closed)
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

class ResumableMinimizeTest {
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
            fixture.assertVisitedLeaves()
            assertSame(result, search.runSlice(Cancellation.Never, 1000L, 256L) { error("duplicate incumbent") })
            search.rebind(Assumptions.None, 256L)
            fixture.acceptProof = { _, _ -> false }
            assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 256L) { error("stale incumbent") },
            )
            assertEquals(2, fixture.opened)
            assertEquals(fixture.opened, fixture.closed)
        }
    }

    @Test
    fun `rebind recertifies an unresolved improving region without blocking it`() {
        val fixture = UnresolvedRealLeafFixture(true)
        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            assertIs<MinimizeResult.BestFound>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            fixture.assertVisitedLeaves()
            search.rebind(Assumptions.None.withInt(0, 1L), 256L)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objectiveValue
                },
            )
            assertEquals(TerminationReason.Unsupported, result.reason)
            assertEquals(listOf(0.5), offered)
            assertEquals(1L, result.sample.ints.single())
            assertEquals(0.5, result.sample.reals.single())
            assertEquals(3, fixture.opened)
            assertEquals(fixture.opened, fixture.closed)
        }
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
}
