package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RefinementTest {
    @Test
    fun `refinement work cap stops between cancellation polls`() {
        val cache = LpRefinementCache()
        val meter = RefinementMeter(LpRefinementLimits(maxWork = 1L), cache, Cancellation.Never)
        meter.charge()

        val stopped = assertFailsWith<RefinementStop> { meter.charge() }

        assertEquals(LpRefinementDecline.WORK, stopped.reason)
        assertEquals(1L, cache.work)
    }

    @Test
    fun `refinement direct poll observes cancellation between charges`() {
        var cancelled = false
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation { cancelled })
        meter.charge()
        cancelled = true

        val stopped = assertFailsWith<RefinementStop> { meter.poll() }

        assertEquals(LpRefinementDecline.CANCELLED, stopped.reason)
    }

    @Test
    fun `refinement polls work metered cancellation on every charge`() {
        var cancelled = false
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = WorkMeter { }
        }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), token)
        meter.charge()
        cancelled = true

        val stopped = assertFailsWith<RefinementStop> { meter.charge() }

        assertEquals(LpRefinementDecline.CANCELLED, stopped.reason)
    }

    @Test
    fun `null correction distinguishes resource exits from numerical decline`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
        val limits = LpRefinementLimits(maxRounds = 1, maxAuxiliaries = 0, time = 30.seconds)
        val cases = listOf(
            "cancel" to LpRefinementDecline.CANCELLED,
            "time" to LpRefinementDecline.TIME,
            "numerical" to LpRefinementDecline.NUMERICAL,
        )
        for ((mode, expected) in cases) {
            var cancelled = false
            var solves = 0
            var completed = LpSolveMetrics()
            lateinit var cache: LpRefinementCache
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
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        override val lastTermination: LpFloatTermination? get() = null
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                            assertNotNull(delegate.resolveBounds(allowance))
                            completed = delegate.lastMetrics
                            solves++
                            when (mode) {
                                "cancel" -> cancelled = true
                                "time" -> cache.elapsed = limits.time
                            }
                            return null
                        }
                    }
                }
            }
            LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
                cache = owner.refinementCache

                val result = refineLp(
                    assertNotNull(state.toWorkingModel()),
                    LpRefinementRequest(owner, cache, limits),
                    doubleArrayOf(0.25),
                    doubleArrayOf(0.0),
                    cancellation = Cancellation { cancelled },
                )

                assertEquals(1, solves)
                assertEquals(expected, result.metrics.decline)
                assertEquals(completed.pivots, result.metrics.pivots)
                assertEquals(completed.workOps, result.metrics.floatWork)
                assertTrue(result.metrics.preparationWork > 0L)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
                owner.requireAvailable()
            }
        }
    }

    @Test
    fun `real child work and pivot stops retain their refinement reason`() {
        val builder = LpBuilder()
        repeat(4) {
            val column = builder.addVar(0L, 4L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        for ((local, expected) in listOf(
            LpFloatTermination.WORK to LpRefinementDecline.WORK,
            LpFloatTermination.PIVOTS to LpRefinementDecline.PIVOTS,
        )) {
            var childResult: FloatLpResult? = null
            var childReason: LpFloatTermination? = null
            var childCalls = 0
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
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                            childCalls++
                            childResult = delegate.resolveBounds(
                                if (local == LpFloatTermination.WORK) {
                                    LpFloatAllowance(1L, 100)
                                } else {
                                    LpFloatAllowance(assertNotNull(allowance).work, 1)
                                },
                            )
                            childReason = delegate.lastTermination
                            return childResult?.takeIf { it.optimal }
                        }
                    }
                }
            }
            LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
                val result = refineLp(
                    assertNotNull(state.toWorkingModel()),
                    LpRefinementRequest(
                        owner,
                        owner.refinementCache,
                        LpRefinementLimits(maxRounds = 1, maxAuxiliaries = 0, time = 30.seconds),
                    ),
                    DoubleArray(4) { 0.5 },
                    DoubleArray(4),
                )

                assertEquals(1, childCalls)
                assertTrue(childResult?.optimal != true)
                assertEquals(local, childReason)
                assertEquals(expected, result.metrics.decline)
                assertNull(result.conflict)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            }
        }
    }

    @Test
    fun `correction replacement distinguishes cancellation from adoption decline`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val cases = listOf(
            true to LpRefinementDecline.CANCELLED,
            false to LpRefinementDecline.ADOPTION,
        )
        for ((cancelAtReplacement, expected) in cases) {
            var cancelled = false
            var adoptions = 0
            var solves = 0
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
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                            adoptions++
                            if (adoptions == 3) {
                                cancelled = cancelAtReplacement
                                return false
                            }
                            return delegate.adopt(state, token)
                        }
                        override fun solve(warm: Basis?): FloatLpResult {
                            solves++
                            return FloatLpResult(basis, 0.0, doubleArrayOf(0.0), doubleArrayOf(0.0))
                        }
                        override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                    }
                }
            }
            LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
                val result = refineLp(
                    assertNotNull(state.toWorkingModel()),
                    LpRefinementRequest(
                        owner,
                        owner.refinementCache,
                        LpRefinementLimits(maxRounds = 2, maxAuxiliaries = 0),
                    ),
                    doubleArrayOf(0.25),
                    doubleArrayOf(0.0),
                    basis,
                    cancellation = Cancellation { cancelled },
                )

                assertEquals(3, adoptions)
                assertEquals(1, solves)
                assertEquals(expected, result.metrics.decline)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
                owner.requireAvailable()
            }
        }
    }

    @Test
    fun `an unchanged checked point can gain a certified objective bound`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxRounds = 1)),
                doubleArrayOf(0.5),
                doubleArrayOf(0.0),
                Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
                witness = ExactLpWitness(listOf(third), third),
            )

            assertEquals(listOf(third), assertNotNull(result.witness).primal)
            assertEquals(third, assertNotNull(result.bound).value)
            assertEquals(1, result.metrics.rounds)
            assertNull(result.conflict)
        }
    }

    @Test
    fun `an exact point without a float optimum seeds refinement to the certified optimum`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 8.0, cost = 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 3.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val five = BigFraction.ofLong(5L)
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                witness = ExactLpWitness(listOf(five), BigFraction.ofLong(2L) * five),
                needPoint = false,
            )

            assertEquals(listOf(BigFraction.ofLong(3L)), assertNotNull(result.witness).primal)
            assertEquals(BigFraction.ofLong(6L), assertNotNull(result.bound).value)
        }
    }

    @Test
    fun `a seeded attempt is not a repeat of an unseeded one on the same state`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 8.0, cost = 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 3.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val seed = ExactLpWitness(listOf(BigFraction.ofLong(5L)), BigFraction.ofLong(10L))
        val other = ExactLpWitness(listOf(BigFraction.ofLong(4L)), BigFraction.ofLong(8L))
        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())
            refineLp(assertNotNull(state.toWorkingModel()), request)
            val seeded = refineLp(assertNotNull(state.toWorkingModel()), request, witness = seed, needPoint = false)
            val repeated = refineLp(assertNotNull(state.toWorkingModel()), request, witness = other, needPoint = false)

            assertEquals(BigFraction.ofLong(6L), assertNotNull(seeded.bound).value)
            assertEquals(LpRefinementDecline.REPEATED, repeated.metrics.decline)
        }
    }

    @Test
    fun `residual correction reaches a fractional optimum including logical cost`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val model = assertNotNull(state.toWorkingModel())
            val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
            val result = refineLp(
                model,
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(0.25),
                doubleArrayOf(0.0),
                basis,
            )

            val point = assertNotNull(result.witness)
            assertEquals(BigFraction.of(BIG_ONE, bigIntOf(3)), point.primal.single())
            assertEquals(BigFraction.ONE, point.primal.single() * BigFraction.ofLong(3L))
            assertEquals(BigFraction.ZERO, point.objective)
            assertEquals(point.objective, assertNotNull(result.bound).value)
            assertTrue(result.metrics.rounds > 0)
            assertSame(state, owner.state)
            assertNull(owner.lastResult)
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
        }
    }

    @Test
    fun `exact seeds preserve constants beyond double integer precision`() {
        val origin = BigFraction.of(BIG_ONE shl 80, BIG_ONE)
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(ExactLpNumber.of(1L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(), origin = ExactLpNumber.of(origin), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(
                    listOf(ExactLpNumber.of(1L), zero),
                    constant = ExactLpNumber.of(origin),
                ),
            ),
        )

        val result = reconstructRationalCertificate(
            assertNotNull(state.toWorkingModel()),
            listOf(origin + third),
            listOf(third),
        )

        assertEquals(listOf(origin + third), assertNotNull(result.witness).primal)
        assertEquals(origin + third, assertNotNull(result.bound).value)
        assertTrue(result.complementary)
    }

    @Test
    fun `an unchanged exhausted attempt cannot buy another child`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxWork = 1L))
            val first = refineLp(assertNotNull(state.toWorkingModel()), request, doubleArrayOf(1.0), doubleArrayOf())
            val second = refineLp(assertNotNull(state.toWorkingModel()), request, doubleArrayOf(0.5), doubleArrayOf())

            assertEquals(LpRefinementDecline.WORK, first.metrics.decline)
            assertEquals(LpRefinementDecline.REPEATED, second.metrics.decline)
            assertEquals(0L, second.metrics.work)
            assertEquals(0L, owner.metrics.createdOwners)
            assertNull(owner.lastWorkingMetrics)
        }
    }

    @Test
    fun `cancellation withholds correction publication and leaves source available`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(1.0),
                doubleArrayOf(),
                cancellation = Cancellation { true },
            )

            assertEquals(LpRefinementDecline.CANCELLED, result.metrics.decline)
            assertNull(result.witness)
            assertSame(state, owner.state)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `large precision inputs decline before creating a numerical child`() {
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 160, BIG_ONE))
        val state = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(huge)), integral = false)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxBits = 64)),
            )

            assertEquals(LpRefinementDecline.BITS, result.metrics.decline)
            assertNull(result.witness)
            assertNull(owner.lastWorkingMetrics)
        }
    }

    @Test
    fun `the default limits bound work and never the clock`() {
        assertEquals(Duration.INFINITE, LpRefinementLimits().time)
    }
}
