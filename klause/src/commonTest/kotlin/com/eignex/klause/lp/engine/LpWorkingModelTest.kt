package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpWorkingModelTest {
    @Test
    fun `an objective override should solve its own objective without disturbing the source optimum`() {
        val source = boxedSource()
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        LpScopedSolver(source).use { owner ->
            val original = assertNotNull(owner.solve())
            val sourceSolver = assertNotNull(owner.solveFloat()).first
            val auxiliary = LpWorkingModel.overrides(
                source,
                ExactLpObjective(listOf(zero, one), scale = ExactLpNumber.of(2L), externalConstant = one),
            )
            owner.withWorkingModel(auxiliary) { scope ->
                val result = assertNotNull(scope.solve())

                assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
                assertEquals(BigFraction.ONE, result.lowerBound)
                val sourceModel = assertNotNull(source.toWorkingModel())
                assertEquals(LpVerdict.INDETERMINATE, certifyLpResult(sourceModel, sourceSolver, result.float).verdict)
            }

            assertSame(source, owner.state)
            assertEquals(original.exactPrimal, assertNotNull(owner.solve()).exactPrimal)
        }
    }

    @Test
    fun `the owner and an escaped scope should reject solves outside the scope lifetime`() {
        val source = boxedSource()
        LpScopedSolver(source).use { owner ->
            var escaped: LpWorkingScope? = null
            owner.withWorkingModel(LpWorkingModel.overrides(source)) { scope ->
                escaped = scope
                assertFailsWith<IllegalStateException> { owner.solve() }
            }

            assertFailsWith<IllegalStateException> { assertNotNull(escaped).solve() }
        }
    }

    @Test
    fun `nested bound and rhs overrides should solve inside a working scope`() {
        val source = boxedSource()
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        LpScopedSolver(source).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(source)) { scope ->
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
            }
        }
    }

    @Test
    fun `a scope ledger should charge the work every certifier invocation declares`() {
        val source = boxedSource()
        var metered = 0L
        var invocations = 0
        val declared = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                invocations++
                if (cost is LpCertifierCost.Metered) metered += cost.work
            }

            override fun observeExactInput(accepted: Boolean) = Unit

            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }
        LpScopedSolver(source).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(source)) { scope -> scope.solve(observer = declared) }

            val metrics = assertNotNull(owner.lastWorkingMetrics)
            assertTrue(invocations > 0 && metered > 0L)
            assertEquals(metered, metrics.certificationWork)
            assertTrue(metrics.measuredWork >= metered)
            assertTrue(metrics.owners.constructionWork in 1L..metrics.owners.reservedConstructionWork)
        }
    }

    @Test
    fun `an auxiliary row should not become source infeasibility or spoil the original factors`() {
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
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
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
    fun `failed working preparation and solve should retain costs and restore the parent`() {
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
                assertEquals(if (preparation) 0L else 1L, metrics.owners.preparationSuccesses)
                assertEquals(1L, metrics.owners.closedOwners)
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            }
        }
    }

    @Test
    fun `permanent replacement should drop root deductions and rows from the old objective`() {
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
        val slack = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        fun localRow(id: Long, rhs: ExactLpNumber) =
            LpScopedRow(id, listOf(0 to one), rhs, slack, ExactLpRow(global = false))
        LpScopedSolver(source).use { owner ->
            assertTrue(owner.assertBound(0, true, ExactLpSide(one), 0L))
            assertTrue(owner.append(localRow(0L, one), false))
            assertTrue(owner.push())
            assertTrue(owner.append(localRow(1L, zero), true))
            assertNotNull(owner.solve())

            assertTrue(owner.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L))), source))

            assertEquals(0, owner.state.depth)
            assertTrue(owner.state.assertions.isEmpty())
            assertEquals(0, owner.state.model.m)
            assertEquals(BigFraction.ofLong(-3L), assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `working scopes should not replenish source continuation work`() {
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

    private fun boxedSource(): LpExactState {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        return LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(three),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
    }
}
