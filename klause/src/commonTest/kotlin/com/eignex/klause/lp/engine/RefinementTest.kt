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
import kotlin.time.Duration.Companion.seconds

class RefinementTest {
    @Test
    fun `refinement work cap should stop between cancellation polls`() {
        val cache = LpRefinementCache()
        val meter = RefinementMeter(LpRefinementLimits(maxWork = 1L), cache, Cancellation.Never)
        meter.charge()

        val stopped = assertFailsWith<RefinementStop> { meter.charge() }

        assertEquals(LpRefinementDecline.WORK, stopped.reason)
        assertEquals(1L, cache.work)
    }

    @Test
    fun `refinement should poll work metered cancellation on every charge`() {
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
    fun `null correction should distinguish resource exits from numerical decline`() {
        val state = oneThirdState()
        val limits = LpRefinementLimits(maxRounds = 1, maxAuxiliaries = 0, time = 30.seconds)
        val cases = listOf(
            "cancel" to LpRefinementDecline.CANCELLED,
            "time" to LpRefinementDecline.TIME,
            "numerical" to LpRefinementDecline.NUMERICAL,
        )
        for ((mode, expected) in cases) {
            var cancelled = false
            var completed = LpSolveMetrics()
            lateinit var cache: LpRefinementCache
            val factory = persistentDecorator { delegate ->
                object : PersistentLpSolver by delegate {
                    override val lastTermination: LpFloatTermination? get() = null
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        assertNotNull(delegate.resolveBounds(allowance))
                        completed = delegate.lastMetrics
                        when (mode) {
                            "cancel" -> cancelled = true
                            "time" -> cache.elapsed = limits.time
                        }
                        return null
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

                assertEquals(expected, result.metrics.decline)
                assertEquals(completed.pivots, result.metrics.pivots)
                assertEquals(completed.workOps, result.metrics.floatWork)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            }
        }
    }

    @Test
    fun `correction replacement should distinguish cancellation from adoption decline`() {
        val state = oneThirdState()
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        for ((cancelAtReplacement, expected) in listOf(
            true to LpRefinementDecline.CANCELLED,
            false to LpRefinementDecline.ADOPTION,
        )) {
            var cancelled = false
            var adoptions = 0
            val factory = persistentDecorator { delegate ->
                object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                        adoptions++
                        if (adoptions == 3) {
                            cancelled = cancelAtReplacement
                            return false
                        }
                        return delegate.adopt(state, token)
                    }
                    override fun solve(warm: Basis?) = FloatLpResult(basis, 0.0, doubleArrayOf(0.0), doubleArrayOf(0.0))
                    override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
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

                assertEquals(expected, result.metrics.decline)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            }
        }
    }

    @Test
    fun `an unchanged checked point should still gain a certified objective bound`() {
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
    fun `residual correction should reach a fractional optimum including logical cost`() {
        val state = oneThirdState()
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(0.25),
                doubleArrayOf(0.0),
                Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
            )

            val point = assertNotNull(result.witness)
            assertEquals(BigFraction.of(BIG_ONE, bigIntOf(3)), point.primal.single())
            assertEquals(BigFraction.ZERO, point.objective)
            assertEquals(point.objective, assertNotNull(result.bound).value)
            assertSame(state, owner.state)
        }
    }

    @Test
    fun `an unchanged exhausted attempt should not buy another child`() {
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
    fun `cancellation should withhold correction publication and leave the source available`() {
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
    fun `large precision inputs should decline before creating a numerical child`() {
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

    private fun oneThirdState(): LpExactState {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        return LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
    }

    private fun persistentDecorator(wrap: (PersistentLpSolver) -> PersistentLpSolver) =
        object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = wrap(
                ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                ),
            )
        }
}
