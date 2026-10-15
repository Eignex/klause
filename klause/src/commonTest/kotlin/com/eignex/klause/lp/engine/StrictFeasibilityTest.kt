package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictFeasibilityTest {
    @Test
    fun `strict eligibility should decline before scanning an unadmitted source`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            List(3) { emptyList() },
            emptyList(),
            List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true)), integral = false) },
            emptyList(),
            ExactLpObjective(List(3) { zero }),
        )
        val cases = listOf(
            LpRefinementLimits(maxCoordinates = 2) to LpRefinementDecline.DIMENSION,
            LpRefinementLimits(maxWork = 2) to LpRefinementDecline.WORK,
        )
        for ((limits, decline) in cases) {
            LpScopedSolver(LpExactState(source)).use { owner ->
                val result = refineLp(
                    assertNotNull(owner.state.toWorkingModel()),
                    LpRefinementRequest(owner, owner.refinementCache, limits),
                )

                assertEquals(decline, result.metrics.decline)
                assertEquals(0, result.metrics.strictAttempts)
                assertEquals(1L, owner.refinementCache.work)
                assertNull(result.witness)
                assertNull(owner.lastWorkingMetrics)
            }
        }
    }

    @Test
    fun `a stronger closed logical bound should preserve strict margin recovery`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(2L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
            ),
            listOf(ExactLpRow(strict = true)),
            ExactLpObjective(listOf(zero, zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertTrue(owner.assertBound(1, false, ExactLpSide(one), 7L))

            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            val x = assertNotNull(result.witness).primal.single()
            assertTrue(x > BigFraction.ZERO && x <= BigFraction.ONE)
            assertTrue(BigFraction.ofLong(2L) - x >= BigFraction.ONE)
            assertEquals(1, result.metrics.strictWitnesses)
        }
    }

    @Test
    fun `strict rational admission should respect the source bit budget`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 40))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(tiny)), integral = false)),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxBits = 24)),
            )

            assertEquals(LpRefinementDecline.BITS, result.metrics.decline)
            assertNull(result.witness)
            assertNull(owner.lastWorkingMetrics)
            assertTrue(owner.refinementCache.work > 0L)
        }
    }

    @Test
    fun `a positive auxiliary witness should suffice without an objective certificate`() {
        val source = unitInterval(upperStrict = true)
        val factory = persistentDecorator { engine, model ->
            object : PersistentLpSolver by engine {
                override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                    engine.resolveBounds(allowance)?.let {
                        val duals = DoubleArray(model.m)
                        FloatLpResult(it.basis, it.objective, duals, it.primal, exactState = it.exactState)
                    }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { owner ->
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxRounds = 0)),
            )

            assertEquals(BigFraction.ONE, assertNotNull(result.witness).primal.single() * BigFraction.ofLong(2L))
            assertEquals(1, result.metrics.strictWitnesses)
            assertEquals(0, result.metrics.luFactories)
        }
    }

    @Test
    fun `a strict endpoint should not be published as a source witness`() {
        val source = unitInterval()
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxAuxiliaries = 0)),
                witness = ExactLpWitness(listOf(BigFraction.ZERO), BigFraction.ZERO),
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.CANDIDATE, result.metrics.decline)
        }
    }

    @Test
    fun `spent cumulative pivots should prevent another strict child`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
            listOf(one),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)),
                    integral = false,
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            val first = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxPivots = 4)),
            )
            val spent = first.metrics.pivots
            assertTrue(spent > 0)
            assertTrue(spent < 4)
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxPivots = spent)),
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.PIVOTS, result.metrics.decline)
            assertEquals(spent, owner.refinementCache.pivots)
        }
    }

    @Test
    fun `a closure endpoint should move inside exact open intervals`() {
        for (denominator in listOf(1L, 1_000_000_000_000L)) {
            val upper = BigFraction.of(BIG_ONE, bigIntOf(denominator))
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(
                    ExactLpColumn(
                        ExactLpBounds(
                            ExactLpSide(ExactLpNumber.of(0L), strict = true),
                            ExactLpSide(ExactLpNumber.of(upper), strict = true),
                        ),
                        integral = false,
                    ),
                ),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(0L))),
            )

            val result = solveAndCertify(model)

            val point = assertNotNull(result.exactPrimal).single()
            assertTrue(point > BigFraction.ZERO && point < upper)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(1, assertNotNull(result.refinement).strictWitnesses)
        }
    }

    @Test
    fun `a strict cycle conflict should contain only source equations and active witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, one), ExactLpEntry(1, minusOne)),
                listOf(ExactLpEntry(0, minusOne), ExactLpEntry(1, one)),
            ),
            listOf(zero, zero),
            List(4) { ExactLpColumn(ExactLpBounds(), integral = false) },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(4) { zero }),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertTrue(owner.push())
            assertTrue(owner.assertBound(2, false, ExactLpSide(zero, strict = true), 41L))
            assertTrue(owner.assertBound(3, false, ExactLpSide(zero), 43L))
            val state = owner.state
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxRounds = 0)),
            )

            val support = assertNotNull(result.support)
            assertEquals(state, support.state)
            assertEquals(listOf(41L, 43L), support.sides.map { it.witness })
            assertEquals(listOf(0, 1), support.rows.map { it.first })
            val proof = assertNotNull(result.conflict)
            val rho = MutableList(2) { BigFraction.ZERO }
            for (index in proof.rows.indices) rho[proof.rows[index]] -= proof.multipliers[index]
            assertEquals(rho[0], rho[1])
            assertTrue(rho[0].signum() < 0)
        }
    }

    @Test
    fun `strict zero rows and fixed sides should be refuted exactly`() {
        val zero = ExactLpNumber.of(0L)
        for (structural in listOf(false, true)) {
            val source = ExactLpModel(
                if (structural) listOf(emptyList()) else emptyList(),
                if (structural) emptyList() else listOf(zero),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(zero)), integral = false),
                ),
                if (structural) emptyList() else listOf(ExactLpRow(strict = true)),
                ExactLpObjective(listOf(zero)),
            )

            val result = solveAndCertify(source)

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            val proof = assertNotNull(result.boundConflict)
            assertEquals(proof.lower.side.number, proof.upper.side.number)
            assertTrue(proof.lower.side.strict)
        }
    }

    @Test
    fun `cancelled and bounded margin attempts should restore the source owner`() {
        val source = unitInterval()
        for (cancel in listOf(false, true)) {
            var polls = 0
            LpScopedSolver(LpExactState(source)).use { owner ->
                val result = refineLp(
                    assertNotNull(owner.state.toWorkingModel()),
                    LpRefinementRequest(
                        owner,
                        owner.refinementCache,
                        if (cancel) LpRefinementLimits() else LpRefinementLimits(maxWork = 100),
                    ),
                    cancellation = Cancellation { cancel && ++polls > 30 },
                )

                assertNull(result.witness)
                assertNotNull(owner.solve(refinementLimits = LpRefinementLimits(maxWork = 2_000_000))?.witness)
            }
        }
    }

    @Test
    fun `failed auxiliary engine callbacks should close the child and retain spending`() {
        val source = unitInterval()
        for (failAfterSolve in listOf(false, true)) {
            var fail = true
            val factory = persistentDecorator { engine, _ ->
                object : PersistentLpSolver by engine {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        if (fail && !failAfterSolve) error("injected auxiliary failure")
                        val result = engine.resolveBounds(allowance)
                        if (fail) error("injected auxiliary failure")
                        return result
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { owner ->
                assertFailsWith<IllegalStateException> {
                    refineLp(
                        assertNotNull(owner.state.toWorkingModel()),
                        LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                    )
                }
                val spent = owner.refinementCache.work
                assertEquals(owner.refinementCache.lastMetrics.observedPivots, owner.refinementCache.pivots)
                assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
                fail = false

                assertNotNull(owner.solve(refinementLimits = LpRefinementLimits(maxWork = 2_000_000))?.witness)
                assertTrue(owner.refinementCache.work > spent)
            }
        }
    }

    private fun unitInterval(upperStrict: Boolean = false): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        return ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L), upperStrict)),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
    }

    private fun persistentDecorator(wrap: (PersistentLpSolver, LpModel) -> PersistentLpSolver) =
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
                model,
            )
        }
}
