package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictFeasibilityTest {
    @Test
    fun `strict eligibility declines before scanning an unadmitted source`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            List(3) { emptyList() }, emptyList(),
            List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true)), integral = false) },
            emptyList(), ExactLpObjective(List(3) { zero }),
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
    fun `disabled auxiliaries skip strict eligibility admission`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true)), integral = false)),
            emptyList(), ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache,
                    LpRefinementLimits(maxAuxiliaries = 0, maxCoordinates = 0, maxWork = 1)),
            )

            assertEquals(LpRefinementDecline.CANDIDATE, result.metrics.decline)
            assertEquals(0, result.metrics.strictAttempts)
            assertEquals(1L, owner.refinementCache.work)
            assertNull(owner.lastWorkingMetrics)
        }
    }

    @Test
    fun `a stronger closed logical bound preserves strict margin recovery`() {
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
    fun `strict rational admission respects the source bit budget`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 40))
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
    fun `expanded strict models decline admission before creating owners`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = refineLp(
                assertNotNull(owner.state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxCoordinates = 1)),
            )

            assertEquals(LpRefinementDecline.DIMENSION, result.metrics.decline)
            assertNull(result.witness)
            assertEquals(1, result.metrics.strictAttempts)
            assertNull(owner.lastWorkingMetrics)
            assertTrue(owner.refinementCache.work > 0L)
        }
    }

    @Test
    fun `a positive auxiliary witness is sufficient without an objective certificate`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(
                        ExactLpSide(zero, strict = true),
                        ExactLpSide(ExactLpNumber.of(1L), strict = true),
                    ),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
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
                val engine = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by engine {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = engine.resolveBounds(
                        allowance,
                    )?.let {
                        FloatLpResult(
                            it.basis,
                            it.objective,
                            DoubleArray(model.m),
                            it.primal,
                            exactState = it.exactState,
                        )
                    }
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
            assertEquals(0, result.metrics.rounds)
        }
    }

    @Test
    fun `equivalent source replacement retains strict attempt spending`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val limits = LpRefinementLimits(maxWork = 100)
        LpScopedSolver(LpExactState(source)).use { donor ->
            val first = refineLp(
                assertNotNull(donor.state.toWorkingModel()),
                LpRefinementRequest(donor, donor.refinementCache, limits),
            )
            assertNull(first.witness)
            val receipt = assertNotNull(donor.exportEpochReceipt())
            LpScopedSolver(LpExactState(source)).use { recipient ->
                assertTrue(recipient.importEpochReceipt(receipt))
                val spent = recipient.refinementCache.work

                val repeated = refineLp(
                    assertNotNull(recipient.state.toWorkingModel()),
                    LpRefinementRequest(recipient, recipient.refinementCache, limits),
                )

                assertEquals(LpRefinementDecline.REPEATED, repeated.metrics.decline)
                assertNull(repeated.witness)
                assertEquals(spent, recipient.refinementCache.work)
                assertEquals(donor.refinementCache.work, spent)
            }
        }
    }

    @Test
    fun `a closure endpoint moves inside exact open intervals`() {
        for (denominator in listOf(1L, 1_000_000_000_000L)) {
            val upper = BigFraction.of(BigInteger.ONE, BigInteger.fromLong(denominator))
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
    fun `signed strict rows retain closed source sides and origins`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-2L)), ExactLpEntry(1, one))),
            listOf(zero, one),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)),
                    origin = ExactLpNumber.of(10L),
                    integral = false,
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
            ),
            List(2) { ExactLpRow(strict = true) },
            ExactLpObjective(
                listOf(ExactLpNumber.of(6L), zero, zero),
                constant = ExactLpNumber.of(3L),
                scale = ExactLpNumber.of(2L),
                externalConstant = one,
            ),
        )

        val result = solveAndCertify(model)

        val x = assertNotNull(result.exactPrimal).single() - BigFraction.ofLong(10L)
        assertTrue(x > BigFraction.ZERO && x < BigFraction.ONE)
        assertEquals(
            (BigFraction.ofLong(6L) * x + BigFraction.ofLong(3L)) *
                BigFraction.ofLong(2L).reciprocal() + BigFraction.ONE,
            assertNotNull(result.witness).objective,
        )
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertTrue(assertNotNull(result.lowerBound) < result.witness.objective)
    }

    @Test
    fun `strict cycle conflict contains only source equations and active witnesses`() {
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
            assertEquals(2, rho.size)
            assertEquals(rho[0], rho[1])
            assertTrue(rho.all { it.signum() < 0 })
            assertEquals(BigFraction.ZERO, rho[0] - rho[1])
            assertTrue(support.sides.any { it.side.strict })
            assertEquals(1, result.metrics.strictConflicts)
            assertTrue(owner.pop(0))
            assertTrue(owner.state !== support.state)
            assertNotNull(owner.solve()?.witness)
        }
    }

    @Test
    fun `strict zero rows and fixed sides are refuted exactly`() {
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
    fun `cancelled and bounded margin attempts restore the source owner`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
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
                assertNull(result.conflict)
                owner.requireAvailable()
                assertTrue(owner.refinementCache.work > 0L)
                assertNotNull(owner.solve(refinementLimits = LpRefinementLimits(maxWork = 2_000_000))?.witness)
                owner.lastWorkingMetrics?.let { assertEquals(0L, it.owners.currentOwners) }
            }
        }
    }

    @Test
    fun `failed auxiliary engine callbacks close the child and retain spending`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))),
                    integral = false,
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        var fail = true
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
                val engine = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by engine {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        if (fail) error("injected auxiliary failure")
                        return engine.resolveBounds(allowance)
                    }
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
            assertTrue(spent > 0L)
            val closed = assertNotNull(owner.lastWorkingMetrics).owners
            assertEquals(1L, closed.createdOwners)
            assertEquals(0L, closed.currentOwners)
            owner.requireAvailable()
            fail = false

            assertNotNull(owner.solve(refinementLimits = LpRefinementLimits(maxWork = 2_000_000))?.witness)
            assertTrue(owner.refinementCache.work > spent)
        }
    }
}
