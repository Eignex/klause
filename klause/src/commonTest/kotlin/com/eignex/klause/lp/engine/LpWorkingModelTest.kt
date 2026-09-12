package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpWorkingModelTest {
    @Test
    fun `nested full cost bound and rhs overrides preserve source authority and optimum`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val source = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(three),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        LpScopedSolver(source).use { owner ->
            val original = assertNotNull(owner.solve())
            val sourceSolver = assertNotNull(owner.solveFloat()).first
            var escaped: LpWorkingScope? = null
            val auxiliary = LpWorkingModel.overrides(
                source,
                ExactLpObjective(listOf(zero, one), scale = ExactLpNumber.of(2L), externalConstant = one),
            )
            owner.withWorkingModel(auxiliary) { scope ->
                escaped = scope
                val result = assertNotNull(scope.solve())
                assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
                assertEquals(BigFraction.ONE, result.lowerBound)
                assertSame(auxiliary.state, result.float?.exactState)
                assertEquals(
                    LpVerdict.INDETERMINATE,
                    certifyLpResult(
                        assertNotNull(source.toWorkingModel()),
                        sourceSolver,
                        result.float,
                    ).verdict,
                )
                assertFailsWith<IllegalStateException> { owner.solve() }
                assertFailsWith<IllegalStateException> { owner.prepare() }
                assertFailsWith<IllegalStateException> { owner.push() }
                assertFailsWith<IllegalStateException> { owner.close() }
                val nested = LpWorkingModel.overrides(
                    scope.state,
                    ExactLpObjective(listOf(one, zero)),
                    listOf(ExactLpBounds(ExactLpSide(one), ExactLpSide(three)), ExactLpBounds(ExactLpSide(zero))),
                    listOf(ExactLpNumber.of(2L)),
                )
                scope.withWorkingModel(nested) { inner ->
                    assertFailsWith<IllegalStateException> { scope.solve() }
                    val point = assertNotNull(inner.solve())
                    assertEquals(listOf(BigFraction.ONE), point.exactPrimal)
                    assertEquals(BigFraction.ONE, point.lowerBound)
                }
                assertEquals(result.exactPrimal, assertNotNull(scope.solve()).exactPrimal)
            }
            assertFailsWith<IllegalStateException> { assertNotNull(escaped).solve() }
            assertSame(source, owner.state)
            assertNull(owner.lastResult)
            assertEquals(original.exactPrimal, assertNotNull(owner.solve()).exactPrimal)
            assertEquals(BigFraction.ZERO, owner.lastResult?.lowerBound)
            val metrics = assertNotNull(owner.lastWorkingMetrics)
            assertEquals(2L, metrics.attempts)
            assertEquals(1L, metrics.owners.createdOwners)
            assertEquals(1L, metrics.owners.closedOwners)
            assertEquals(1L, metrics.children.single().owners.closedOwners)
            assertTrue(metrics.measuredWork > 0L)
        }
    }

    @Test
    fun `auxiliary row cannot become source infeasibility and original factors remain reusable`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        LpScopedSolver(source).use { owner ->
            val (solver, result) = assertNotNull(owner.solveFloat())
            val basis = assertNotNull(result).basis
            val model = assertNotNull(source.toWorkingModel())
            val cache = assertNotNull(solver.exactBasisCache)
            assertEquals(1, verifyExactBasis(model, basis, cache = cache).metrics.factoryCalls)
            val auxiliary = LpWorkingModel(
                source,
                source.model.appendScopedRow(
                    LpScopedRow(
                        1L,
                        listOf(0 to one),
                        ExactLpNumber.of(2L),
                        ExactLpColumn(
                            ExactLpBounds(
                                ExactLpSide(zero),
                                ExactLpSide(zero),
                            ),
                        ),
                        ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(7))),
                    ),
                ),
            )
            owner.withWorkingModel(auxiliary) { scope ->
                val conflict = assertNotNull(scope.solve())
                assertEquals(LpVerdict.INFEASIBLE, conflict.verdict)
                assertSame(scope.state, assertNotNull(conflict.conflictSupport).state)
            }
            val checked = verifyExactBasis(model, basis, cache = cache)
            assertEquals(1, checked.metrics.reuse)
            assertEquals(BigFraction.ZERO, assertNotNull(checked.bound).value)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `infeasible unbounded and cancelled scopes leave original solve available`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
                emptyList(),
                ExactLpObjective(listOf(one)),
            ),
        )
        for (terminal in listOf("infeasible", "unbounded", "cancelled")) {
            LpScopedSolver(source).use { owner ->
                val bounds = when (terminal) {
                    "infeasible" -> ExactLpBounds(ExactLpSide(one), ExactLpSide(zero))
                    "unbounded" -> ExactLpBounds()
                    else -> source.model.column(0).bounds
                }
                owner.withWorkingModel(LpWorkingModel.overrides(source, bounds = listOf(bounds))) { scope ->
                    val result = scope.solve(token = Cancellation { terminal == "cancelled" })
                    if (terminal == "infeasible") assertEquals(LpVerdict.INFEASIBLE, result?.verdict)
                    if (terminal == "cancelled") assertNull(result)
                    if (terminal == "unbounded") assertFalse(result?.verdict == LpVerdict.ATTAINED_OPTIMUM)
                }
                assertNull(owner.lastResult)
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            }
        }
    }

    @Test
    fun `primary and cleanup failures close only the working owner and preserve accounting`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
                emptyList(),
                ExactLpObjective(listOf(one)),
            ),
        )
        val primary = IllegalStateException("body")
        val cleanup = IllegalStateException("close")
        var created = 0
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
                val working = ++created == 2
                assertEquals(17L, pricing.tieSeed)
                assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, pricing.zeroObjective)
                assertEquals(1_000_000L, workLimit)
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing, reuseRationalOrder = false)
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        delegate.close()
                        if (working) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(
            source,
            context = LpSolveContext(engineFactory = factory),
            workLimit = 1_000_000L,
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT, 17L),
        ).use { owner ->
            assertNotNull(owner.solve())
            val failure = assertFailsWith<IllegalStateException> {
                owner.withWorkingModel(LpWorkingModel.overrides(source)) { scope ->
                    assertNotNull(scope.solve())
                    throw primary
                }
            }
            assertSame(primary, failure)
            assertSame(cleanup, failure.suppressedExceptions.single())
            assertEquals(1L, assertNotNull(owner.lastWorkingMetrics).owners.closedOwners)
            assertEquals(1L, owner.lastWorkingMetrics?.attempts)
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `failed working preparation and solve retain costs and restore the parent`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
                emptyList(),
                ExactLpObjective(listOf(one)),
            ),
        )
        for (preparation in listOf(true, false)) {
            val primary = IllegalStateException("operation")
            val cleanup = IllegalStateException("cleanup")
            var created = 0
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
                    val working = ++created == 2
                    val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? {
                            if (working && preparation) throw primary
                            return delegate.prepareLogicals(token)
                        }
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                            if (working && !preparation) throw primary
                            return delegate.resolveBounds(allowance)
                        }
                        override fun close() {
                            delegate.close()
                            if (working) throw cleanup
                        }
                    }
                }
            }
            LpScopedSolver(source, context = LpSolveContext(engineFactory = factory)).use { owner ->
                assertNotNull(owner.solve())
                val failure = assertFailsWith<IllegalStateException> {
                    owner.withWorkingModel(LpWorkingModel.overrides(source)) { it.solve() }
                }
                assertSame(primary, failure)
                assertSame(cleanup, failure.suppressedExceptions.single())
                val metrics = assertNotNull(owner.lastWorkingMetrics)
                assertEquals(1L, metrics.attempts)
                assertEquals(1L, metrics.owners.preparationAttempts)
                assertEquals(if (preparation) 0L else 1L, metrics.owners.preparationSuccesses)
                assertEquals(1L, metrics.owners.closedOwners)
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            }
        }
    }

    @Test
    fun `permanent replacement drops root deductions and local and parent rows from the old objective`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L))))),
                emptyList(),
                ExactLpObjective(listOf(one)),
            ),
        )
        LpScopedSolver(source).use { owner ->
            assertTrue(owner.assertBound(0, true, ExactLpSide(one), 0L))
            assertTrue(
                owner.append(
                    LpScopedRow(
                        0L,
                        listOf(0 to one),
                        one,
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                        ExactLpRow(global = false),
                    ),
                    false,
                ),
            )
            assertTrue(owner.push())
            assertTrue(
                owner.append(
                    LpScopedRow(
                        1L,
                        listOf(0 to one),
                        zero,
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                        ExactLpRow(global = false),
                    ),
                    true,
                ),
            )
            assertNotNull(owner.solve())

            assertTrue(owner.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L))), source))

            assertEquals(0, owner.state.depth)
            assertTrue(owner.state.assertions.isEmpty())
            assertEquals(0, owner.state.model.m)
            assertNull(owner.lastResult)
            val result = assertNotNull(owner.solve())
            assertEquals(BigFraction.ofLong(-3L), result.lowerBound)
            assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
        }
    }

    @Test
    fun `working scopes cannot replenish source continuation work`() {
        val zero = ExactLpNumber.of(0L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L))))),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            ),
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
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = null
                    override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
                }
            }
        }
        LpScopedSolver(source, context = LpSolveContext(engineFactory = factory)).use { owner ->
            val limits = ExactContinuationLimits(maxWork = 10_000L)
            var result = assertNotNull(owner.solve(continuationLimits = limits))
            repeat(64) {
                if (result.continuation?.decline != ContinuationDecline.WORK) {
                    result = assertNotNull(owner.solve(continuationLimits = limits))
                }
            }
            assertEquals(ContinuationDecline.WORK, result.continuation?.decline)
            owner.withWorkingModel(LpWorkingModel.overrides(source)) { assertNotNull(it.solve()) }
            val exhausted = assertNotNull(owner.solve(continuationLimits = limits))
            assertEquals(ContinuationDecline.WORK, exhausted.continuation?.decline)
            assertEquals(0, exhausted.continuation?.builds)
            assertNull(exhausted.witness)
        }
    }

    @Test
    fun `failed source capture remains exhausted after an auxiliary scope`() {
        val zero = ExactLpNumber.of(0L)
        val source = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L))))),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            ),
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
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = null
                    override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
                }
            }
        }
        LpScopedSolver(source, context = LpSolveContext(engineFactory = factory)).use { owner ->
            val limits = ExactContinuationLimits(maxWork = 100L, maxAllocation = 768L)
            var result = assertNotNull(owner.solve(continuationLimits = limits))
            assertEquals(ContinuationDecline.ALLOCATION, result.continuation?.decline)
            assertEquals(0, result.continuation?.builds)
            assertEquals(com.eignex.klause.simplex.exact.ContinuationPhase.INPUT, result.continuation?.phase)
            repeat(128) {
                if (result.continuation?.decline != ContinuationDecline.WORK) {
                    result = assertNotNull(owner.solve(continuationLimits = limits))
                }
            }
            assertEquals(ContinuationDecline.WORK, result.continuation?.decline)
            owner.withWorkingModel(LpWorkingModel.overrides(source)) {
                val stopped = assertNotNull(it.solve(continuationLimits = ExactContinuationLimits(maxWork = 1L)))
                assertEquals(ContinuationDecline.WORK, stopped.continuation?.decline)
            }
            val exhausted = assertNotNull(owner.solve(continuationLimits = limits))
            assertEquals(ContinuationDecline.WORK, exhausted.continuation?.decline)
            assertEquals(0, exhausted.continuation?.builds)
            assertNull(exhausted.witness)
        }
    }
}
